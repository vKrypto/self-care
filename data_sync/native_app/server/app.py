"""Add authenticated Android ingestion to the existing Forma application.

The device schedules its own uploads with Android WorkManager. The server cannot
run a cron job on a phone; registration records the device's requested schedule.
No password is stored or included in an ingestion batch.
"""

from contextlib import asynccontextmanager
from datetime import datetime, timezone
import hashlib
import json
import math
import os
from pathlib import Path
import re
import time
from typing import Any, Literal
from uuid import UUID

from fastapi import APIRouter, Depends, HTTPException, Request, Response, Security
from fastapi.responses import FileResponse, JSONResponse, RedirectResponse
from fastapi.security import HTTPBearer
from pydantic import BaseModel, ConfigDict, EmailStr, Field, ValidationError, field_validator, model_validator
from starlette.staticfiles import StaticFiles
from starlette.types import ASGIApp, Receive, Scope, Send

from backend import server as planner


MAX_BATCH_BYTES = 8 * 1024 * 1024
MAX_SMALL_BODY_BYTES = 16 * 1024
RAW_RETENTION_DAYS = 90
RECEIPT_RETENTION_DAYS = 365
MAX_TENANT_RAW_BYTES = 256 * 1024 * 1024
MAX_TENANT_RECEIPTS = 50_000
MAX_DEVICES_PER_USER = 20
CLOCK_TOLERANCE_MS = 15 * 60 * 1000
DAY_MS = 86_400_000
SECRET_KEYS = {
    "password", "passwordhash", "passphrase", "authorization", "accesstoken",
    "refreshtoken", "clientsecret", "sessiontoken", "cookie", "cookies",
    "secret", "token", "credentials", "credential", "apikey", "bearertoken",
    "idtoken", "formasession",
}


class StrictModel(BaseModel):
    model_config = ConfigDict(extra="forbid")


class NativeLogin(StrictModel):
    email: EmailStr
    password: str = Field(min_length=1, max_length=128)

    @field_validator("password")
    @classmethod
    def valid_password_text(cls, value):
        value.encode("utf-8")
        return value


class DeviceRegistration(StrictModel):
    device_id: UUID
    platform: Literal["android"]
    name: str = Field(min_length=1, max_length=100)
    sync_interval_minutes: Literal[60] = 60
    consent_version: Literal["1"] = "1"
    history_days: int = Field(default=30, ge=1, le=365, strict=True)

    @model_validator(mode="after")
    def clean_name(self):
        self.name = self.name.strip()
        if not self.name:
            raise ValueError("Device name must not be blank.")
        self.name.encode("utf-8")
        return self


class BatchWindow(StrictModel):
    start_ms: int = Field(ge=0, strict=True)
    end_ms: int = Field(ge=0, strict=True)

    @model_validator(mode="after")
    def valid_order(self):
        if self.end_ms <= self.start_ms:
            raise ValueError("The batch window must end after it starts.")
        if self.end_ms - self.start_ms > 366 * DAY_MS:
            raise ValueError("Split history into windows no longer than 366 days.")
        return self


class NativeBatch(StrictModel):
    schema_version: Literal[1]
    batch_id: UUID
    device_id: UUID
    user_id: UUID
    window: BatchWindow
    collected_at_ms: int = Field(ge=0, strict=True)
    permissions: dict[str, Any]
    data: dict[str, Any]

    @field_validator("schema_version", mode="before")
    @classmethod
    def strict_schema_version(cls, value):
        if type(value) is not int:
            raise ValueError("Schema version must be an integer.")
        return value

    @model_validator(mode="after")
    def valid_collection_time(self):
        upper = int(time.time() * 1000) + CLOCK_TOLERANCE_MS
        if self.window.end_ms > upper or self.collected_at_ms > upper:
            raise ValueError("Device clock is ahead of the server. Correct it and retry.")
        if self.collected_at_ms < self.window.end_ms:
            raise ValueError("Collection time must be at or after the window end.")
        return self


class NativeBodyLimitMiddleware:
    """Bound native bodies before FastAPI reads/parses them, including chunks."""

    def __init__(self, app: ASGIApp):
        self.app = app

    async def __call__(self, scope: Scope, receive: Receive, send: Send):
        if scope["type"] != "http" or not scope.get("path", "").startswith("/api/native/"):
            await self.app(scope, receive, send)
            return
        limit = MAX_BATCH_BYTES if scope["path"] == "/api/native/batches" else MAX_SMALL_BODY_BYTES
        headers = dict(scope.get("headers", []))
        if headers.get(b"content-encoding", b"identity").lower() != b"identity":
            await JSONResponse({"detail": "Send uncompressed JSON."}, status_code=415)(scope, receive, send)
            return
        try:
            stated_size = int(headers.get(b"content-length", b"0"))
            if stated_size < 0:
                raise ValueError
        except ValueError:
            await JSONResponse({"detail": "Invalid Content-Length."}, status_code=400)(scope, receive, send)
            return
        if stated_size > limit:
            await JSONResponse({"detail": "Native request exceeds its size limit."}, status_code=413)(scope, receive, send)
            return
        chunks: list[bytes] = []
        actual_size = 0
        while True:
            message = await receive()
            if message["type"] == "http.disconnect":
                return
            chunk = message.get("body", b"")
            actual_size += len(chunk)
            if actual_size > limit:
                await JSONResponse({"detail": "Native request exceeds its size limit."}, status_code=413)(scope, receive, send)
                return
            chunks.append(chunk)
            if not message.get("more_body", False):
                break
        body = b"".join(chunks)
        delivered = False

        async def replay():
            nonlocal delivered
            if delivered:
                return await receive()
            delivered = True
            return {"type": "http.request", "body": body, "more_body": False}

        await self.app(scope, replay, send)


def init_native_db():
    """Use the existing tenant database and its account deletion cascades."""
    with planner.connect() as con:
        con.executescript("""
            CREATE TABLE IF NOT EXISTS native_devices (
                device_id TEXT PRIMARY KEY,
                tenant TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                platform TEXT NOT NULL CHECK(platform = 'android'),
                name TEXT NOT NULL,
                sync_interval_minutes INTEGER NOT NULL CHECK(sync_interval_minutes = 60),
                consent_version TEXT NOT NULL,
                history_days INTEGER NOT NULL,
                registered_at_ms INTEGER NOT NULL,
                updated_at_ms INTEGER NOT NULL,
                UNIQUE(tenant, device_id)
            );
            CREATE TABLE IF NOT EXISTS native_batches (
                tenant TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
                batch_id TEXT NOT NULL,
                device_id TEXT NOT NULL,
                schema_version INTEGER NOT NULL,
                window_start_ms INTEGER NOT NULL,
                window_end_ms INTEGER NOT NULL,
                collected_at_ms INTEGER NOT NULL,
                received_at_ms INTEGER NOT NULL,
                payload_sha256 TEXT NOT NULL,
                payload_bytes INTEGER NOT NULL,
                payload TEXT,
                PRIMARY KEY(tenant, batch_id),
                FOREIGN KEY(tenant, device_id)
                    REFERENCES native_devices(tenant, device_id) ON DELETE CASCADE
            );
            CREATE INDEX IF NOT EXISTS native_batches_device_received
                ON native_batches(tenant, device_id, received_at_ms DESC);
            CREATE INDEX IF NOT EXISTS native_batches_tenant_received
                ON native_batches(tenant, received_at_ms DESC);
        """)


def bearer_token(request: Request) -> str:
    header = request.headers.get("authorization", "")
    scheme, separator, token = header.partition(" ")
    if not separator or scheme.lower() != "bearer" or not re.fullmatch(r"[A-Za-z0-9_-]{40,128}", token):
        raise HTTPException(401, "Sign in with the native app.", headers={"WWW-Authenticate": "Bearer"})
    return token


native_bearer = HTTPBearer(auto_error=False, scheme_name="Native session")


def native_account(request: Request, _authorization=Security(native_bearer)):
    return planner.resolve_session(bearer_token(request))


def reject_duplicate_keys(pairs):
    result = {}
    for key, value in pairs:
        if key in result:
            raise ValueError("Duplicate JSON field.")
        result[key] = value
    return result


def reject_nonfinite(value):
    raise ValueError("JSON numbers must be finite.")


async def validated_body(request: Request, model):
    """Keep validation responses free of submitted passwords and health values."""
    if request.headers.get("content-type", "").split(";", 1)[0].strip().lower() != "application/json":
        raise HTTPException(415, "Send application/json.")
    try:
        body = json.loads(await request.body(), object_pairs_hook=reject_duplicate_keys, parse_constant=reject_nonfinite)
        return model.model_validate(body)
    except (ValueError, RecursionError, UnicodeError, ValidationError):
        raise HTTPException(422, "Invalid native request. Check the API schema and device clock.") from None


def forbid_secrets(value):
    stack = [(value, 0)]
    nodes = 0
    while stack:
        item, depth = stack.pop()
        nodes += 1
        if depth > 32 or nodes > 500_000:
            raise HTTPException(422, "Batch nesting or record count exceeds the limit.")
        if isinstance(item, dict):
            for key, nested in item.items():
                normalized = re.sub(r"[^a-z0-9]", "", key.lower())
                if normalized in SECRET_KEYS or "password" in normalized or normalized.endswith("secret") or normalized.endswith("token"):
                    raise HTTPException(422, "Credentials must never be included in export data.")
                stack.append((nested, depth + 1))
        elif isinstance(item, list):
            stack.extend((nested, depth + 1) for nested in item)
        elif isinstance(item, float) and not math.isfinite(item):
            raise HTTPException(422, "JSON numbers must be finite.")


def set_web_cookie(response: Response, token: str):
    """Reuse the bearer session instead of creating a second long-lived token."""
    with planner.connect() as con:
        session = con.execute("SELECT expires FROM sessions WHERE token=? AND expires>?",
                              (hashlib.sha256(token.encode()).hexdigest(), time.time())).fetchone()
    if not session:
        raise HTTPException(401, "Your session expired. Please sign in.")
    response.set_cookie(planner.COOKIE, token, httponly=True, secure=planner.SECURE,
                        samesite="lax", max_age=max(0, int(session["expires"] - time.time())), path="/")
    response.headers["Cache-Control"] = "no-store"


def prune_tenant_batches(con, tenant, received_at_ms):
    """Cap retained raw data and receipts, without resetting device sync state."""
    con.execute("DELETE FROM native_batches WHERE tenant=? AND received_at_ms<?",
                (tenant, received_at_ms - RECEIPT_RETENTION_DAYS * DAY_MS))
    con.execute("UPDATE native_batches SET payload=NULL, payload_bytes=0 WHERE tenant=? AND received_at_ms<? AND payload IS NOT NULL",
                (tenant, received_at_ms - RAW_RETENTION_DAYS * DAY_MS))
    total = con.execute("SELECT COALESCE(SUM(payload_bytes),0) AS size FROM native_batches WHERE tenant=?", (tenant,)).fetchone()["size"]
    if total > MAX_TENANT_RAW_BYTES:
        for row in con.execute("SELECT rowid,payload_bytes FROM native_batches WHERE tenant=? AND payload IS NOT NULL ORDER BY received_at_ms,rowid", (tenant,)).fetchall():
            if total <= MAX_TENANT_RAW_BYTES:
                break
            con.execute("UPDATE native_batches SET payload=NULL,payload_bytes=0 WHERE rowid=?", (row["rowid"],))
            total -= row["payload_bytes"]
    con.execute("DELETE FROM native_batches WHERE tenant=? AND rowid NOT IN (SELECT rowid FROM native_batches WHERE tenant=? ORDER BY received_at_ms DESC,rowid DESC LIMIT ?)",
                (tenant, tenant, MAX_TENANT_RECEIPTS))


def documented_body(model):
    # Inline Pydantic's small nested schemas because bodies are parsed manually
    # to reject duplicate keys and keep error responses free of private values.
    schema = model.model_json_schema()
    definitions = schema.pop("$defs", {})

    def expand(value):
        if isinstance(value, dict):
            if "$ref" in value and value["$ref"].startswith("#/$defs/"):
                return expand(definitions[value["$ref"].split("/")[-1]])
            return {key: expand(nested) for key, nested in value.items()}
        if isinstance(value, list):
            return [expand(nested) for nested in value]
        return value

    return {"requestBody": {"required": True, "content": {"application/json": {"schema": expand(schema)}}}}


router = APIRouter(prefix="/api/native", tags=["Android data sync"])


@router.post("/login", openapi_extra=documented_body(NativeLogin))
async def login(request: Request, response: Response):
    body = await validated_body(request, NativeLogin)
    with planner.connect() as con:
        account = con.execute("SELECT * FROM accounts WHERE email=?", (str(body.email).lower(),)).fetchone()
    if not account or not planner.verify_password(body.password, account["password"]):
        raise HTTPException(401, "Incorrect email or password.")
    token = planner.issue_session(response, account["id"])
    with planner.connect() as con:
        expires = con.execute("SELECT expires FROM sessions WHERE token=?", (hashlib.sha256(token.encode()).hexdigest(),)).fetchone()["expires"]
    response.headers["Cache-Control"] = "no-store"
    return {"user": planner.public(dict(account)), "token": token,
            "expires_at": datetime.fromtimestamp(expires, timezone.utc).isoformat()}


@router.get("/me")
def me(response: Response, account=Depends(native_account)):
    response.headers["Cache-Control"] = "no-store"
    return {"user": planner.public(account)}


@router.post("/devices", openapi_extra=documented_body(DeviceRegistration))
async def register_device(request: Request, account=Depends(native_account)):
    body = await validated_body(request, DeviceRegistration)
    identifier = str(body.device_id)
    timestamp = int(time.time() * 1000)
    with planner.connect() as con:
        con.execute("BEGIN IMMEDIATE")
        existing = con.execute("SELECT tenant FROM native_devices WHERE device_id=?", (identifier,)).fetchone()
        if existing and existing["tenant"] != account["id"]:
            raise HTTPException(409, "This device identifier is already registered. Create a new identifier for this account.")
        if not existing and con.execute("SELECT COUNT(*) AS n FROM native_devices WHERE tenant=?", (account["id"],)).fetchone()["n"] >= MAX_DEVICES_PER_USER:
            raise HTTPException(409, "Device limit reached for this account.")
        con.execute("""INSERT INTO native_devices VALUES(?,?,?,?,?,?,?,?,?)
            ON CONFLICT(device_id) DO UPDATE SET name=excluded.name,
            sync_interval_minutes=excluded.sync_interval_minutes, consent_version=excluded.consent_version,
            history_days=excluded.history_days, updated_at_ms=excluded.updated_at_ms""",
            (identifier, account["id"], body.platform, body.name, body.sync_interval_minutes,
             body.consent_version, body.history_days, timestamp, timestamp))
    return {"device_id": identifier}


@router.post("/batches", openapi_extra=documented_body(NativeBatch))
async def ingest_batch(request: Request, account=Depends(native_account)):
    body = await validated_body(request, NativeBatch)
    if str(body.user_id) != account["id"]:
        raise HTTPException(403, "Export user does not match the signed-in account.")
    forbid_secrets(body.permissions)
    forbid_secrets(body.data)
    try:
        payload = json.dumps(body.model_dump(mode="json"), ensure_ascii=False, separators=(",", ":"), sort_keys=True, allow_nan=False)
        encoded = payload.encode("utf-8")
    except (UnicodeError, ValueError):
        raise HTTPException(422, "Batch must contain valid UTF-8 strings and finite JSON numbers.") from None
    if len(encoded) > MAX_BATCH_BYTES:
        raise HTTPException(413, "Batch exceeds the 8 MiB limit. Split it into smaller windows.")
    digest = hashlib.sha256(encoded).hexdigest()
    identifier, device = str(body.batch_id), str(body.device_id)
    received = int(time.time() * 1000)
    with planner.connect() as con:
        con.execute("BEGIN IMMEDIATE")
        owned = con.execute("SELECT device_id FROM native_devices WHERE device_id=? AND tenant=?", (device, account["id"])).fetchone()
        if not owned:
            raise HTTPException(404, "Register this device for the signed-in account before syncing.")
        duplicate = con.execute("SELECT payload_sha256 FROM native_batches WHERE tenant=? AND batch_id=?", (account["id"], identifier)).fetchone()
        if duplicate:
            if duplicate["payload_sha256"] != digest:
                raise HTTPException(409, "Batch identifier already exists with different data.")
            return {"batch_id": identifier, "accepted": True, "duplicate": True}
        con.execute("INSERT INTO native_batches VALUES(?,?,?,?,?,?,?,?,?,?,?)",
                    (account["id"], identifier, device, body.schema_version, body.window.start_ms,
                     body.window.end_ms, body.collected_at_ms, received, digest, len(encoded), payload))
        prune_tenant_batches(con, account["id"], received)
    return {"batch_id": identifier, "accepted": True, "duplicate": False}


@router.get("/status")
def sync_status(response: Response, account=Depends(native_account)):
    response.headers["Cache-Control"] = "no-store"
    with planner.connect() as con:
        devices = [dict(row) for row in con.execute("""SELECT d.device_id,d.platform,d.name,
            d.sync_interval_minutes,d.consent_version,d.history_days,d.registered_at_ms,d.updated_at_ms,
            MAX(b.received_at_ms) AS last_received_at_ms,MAX(b.window_end_ms) AS last_window_end_ms,
            COUNT(b.batch_id) AS retained_batch_count
            FROM native_devices d LEFT JOIN native_batches b ON b.tenant=d.tenant AND b.device_id=d.device_id
            WHERE d.tenant=? GROUP BY d.device_id ORDER BY d.registered_at_ms,d.device_id""", (account["id"],))]
        batches = [dict(row) for row in con.execute("""SELECT batch_id,device_id,schema_version,
            window_start_ms,window_end_ms,collected_at_ms,received_at_ms,payload_bytes,
            payload IS NOT NULL AS raw_retained FROM native_batches WHERE tenant=?
            ORDER BY received_at_ms DESC,rowid DESC LIMIT 20""", (account["id"],))]
    return {"devices": devices, "last_batches": batches,
            "retention": {"raw_days": RAW_RETENTION_DAYS, "receipt_days": RECEIPT_RETENTION_DAYS,
                          "max_raw_bytes_per_user": MAX_TENANT_RAW_BYTES,
                          "max_receipts_per_user": MAX_TENANT_RECEIPTS}}


@router.post("/web-session")
def web_session(request: Request, response: Response, account=Depends(native_account)):
    set_web_cookie(response, bearer_token(request))
    return {"ok": True}


@router.get("/dashboard")
def dashboard(request: Request):
    token = bearer_token(request) if "authorization" in request.headers else request.cookies.get(planner.COOKIE)
    planner.resolve_session(token)
    response = RedirectResponse("/", status_code=303)
    set_web_cookie(response, token)
    return response


@router.post("/logout")
def logout(request: Request, response: Response, account=Depends(native_account)):
    token = bearer_token(request)
    with planner.connect() as con:
        con.execute("DELETE FROM sessions WHERE token=? AND account=?", (hashlib.sha256(token.encode()).hexdigest(), account["id"]))
    response.delete_cookie(planner.COOKIE, path="/")
    response.headers["Cache-Control"] = "no-store"
    return {"ok": True}


app = planner.app
if not getattr(app.state, "native_sync_installed", False):
    original_lifespan = app.router.lifespan_context

    @asynccontextmanager
    async def native_lifespan(application):
        async with original_lifespan(application):
            init_native_db()
            yield

    app.router.lifespan_context = native_lifespan
    app.add_middleware(NativeBodyLimitMiddleware)
    app.include_router(router)
    # A single origin gives WebView the same HttpOnly cookie as the existing UI.
    # Build the unchanged planner frontend with `npm run build` before launch.
    frontend_dist = Path(os.getenv("NATIVE_FRONTEND_DIST", str(Path(__file__).resolve().parents[3] / "dist"))).resolve()
    if (frontend_dist / "assets").is_dir():
        app.mount("/assets", StaticFiles(directory=frontend_dist / "assets"), name="native-dashboard-assets")
    if (frontend_dist / "library").is_dir():
        app.mount("/library", StaticFiles(directory=frontend_dist / "library"), name="native-dashboard-library")

    @app.get("/", include_in_schema=False)
    def built_dashboard():
        index = frontend_dist / "index.html"
        if not index.is_file():
            raise HTTPException(503, "Build the planner dashboard with npm run build, then restart this server.")
        return FileResponse(index, headers={"Cache-Control": "no-cache"})

    app.state.native_sync_installed = True
