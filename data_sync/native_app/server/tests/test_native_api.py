"""Integration tests against the existing auth/session database and native API."""

import asyncio
import hashlib
import json
import time
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from backend import server as planner
from data_sync.native_app.server import app as native


@pytest.fixture
def client(tmp_path, monkeypatch):
    monkeypatch.setattr(planner, "DATA", tmp_path)
    monkeypatch.setattr(planner, "DB", tmp_path / "native.sqlite3")
    monkeypatch.setattr(planner, "cache", planner.Cache(tmp_path / "cache"))
    monkeypatch.setenv("SMTP_HOST", "")
    with TestClient(native.app) as connection:
        yield connection


def create_member(client, email="native@example.com"):
    result = client.post("/api/auth/signup", json={"name": "Native member", "email": email, "password": "password123"})
    assert result.status_code == 201
    # The existing signup endpoint calls now() separately for the database and
    # response; /api/me returns the authoritative persisted account timestamp.
    return client.get("/api/me").json()["account"]


def sign_in(client, email="native@example.com"):
    result = client.post("/api/native/login", json={"email": email, "password": "password123"})
    assert result.status_code == 200
    return result.json()


def authorization(session):
    return {"Authorization": "Bearer " + session["token"]}


def register(client, session, device=None):
    device = device or str(uuid4())
    result = client.post("/api/native/devices", headers=authorization(session), json={
        "device_id": device, "platform": "android", "name": "Test phone",
        "sync_interval_minutes": 60, "consent_version": "1", "history_days": 30,
    })
    assert result.status_code == 200, result.text
    assert result.json() == {"device_id": device}
    return device


def batch(session, device):
    now = int(time.time() * 1000)
    return {"schema_version": 1, "batch_id": str(uuid4()), "device_id": device,
            "user_id": session["user"]["id"], "window": {"start_ms": now - 3_600_000, "end_ms": now},
            "collected_at_ms": now, "permissions": {"usage_access": True, "health_connect": ["Steps"]},
            "data": {"usage": {"status": "available", "complete": True, "records": [{"package_name": "example.app", "foreground_ms": 120_000}]},
                     "health": {"status": "available", "complete": True, "records": [{"type": "Steps", "count": 1_000}]}}}


def post_batch(client, session, body):
    return client.post("/api/native/batches", headers=authorization(session), json=body)


def test_login_restores_existing_user_and_session_has_no_plaintext_token_in_database(client):
    account = create_member(client)
    assert client.post("/api/native/login", json={"email": account["email"], "password": "wrong"}).status_code == 401
    session = sign_in(client)
    assert session["user"] == account
    assert "password" not in session["user"]
    assert session["expires_at"].endswith("+00:00")
    assert "HttpOnly" in client.post("/api/native/web-session", headers=authorization(session)).headers["set-cookie"]
    assert client.get("/api/native/me", headers=authorization(session)).json() == {"user": account}
    assert client.get("/api/native/me").status_code == 401  # The browser cookie is not native bearer auth.
    with planner.connect() as con:
        tokens = [row["token"] for row in con.execute("SELECT token FROM sessions")]
        assert session["token"] not in tokens
        assert hashlib.sha256(session["token"].encode()).hexdigest() in tokens
    native.init_native_db()  # Native initialization is restart-safe, as is the existing login session.
    assert client.get("/api/native/me", headers=authorization(session)).status_code == 200


def test_logout_revokes_native_and_web_session(client):
    create_member(client)
    session = sign_in(client)
    assert client.post("/api/native/web-session", headers=authorization(session)).status_code == 200
    assert client.get("/api/me").status_code == 200
    assert client.post("/api/native/logout", headers=authorization(session)).json() == {"ok": True}
    assert client.get("/api/native/me", headers=authorization(session)).status_code == 401
    assert client.get("/api/me").status_code == 401


def test_expired_and_password_revoked_sessions_cannot_sync(client):
    create_member(client)
    session = sign_in(client)
    device = register(client, session)
    body = batch(session, device)
    with planner.connect() as con:
        con.execute("UPDATE sessions SET expires=? WHERE token=?", (time.time() - 1, hashlib.sha256(session["token"].encode()).hexdigest()))
    assert post_batch(client, session, body).status_code == 401
    session = sign_in(client)
    client.post("/api/auth/login", json={"email": "admin@example.com", "password": "admin123"})
    assert client.put("/api/admin/users/" + session["user"]["id"] + "/password", json={"password": "newpassword123"}).status_code == 200
    assert post_batch(client, session, body).status_code == 401


def test_batch_is_persisted_once_and_conflicting_retry_is_rejected(client):
    create_member(client)
    session = sign_in(client)
    device = register(client, session)
    assert register(client, session, device) == device
    body = batch(session, device)
    expected = {"batch_id": body["batch_id"], "accepted": True, "duplicate": False}
    assert post_batch(client, session, body).json() == expected
    assert post_batch(client, session, body).json() == {**expected, "duplicate": True}
    # Field order and insignificant whitespace do not change batch identity.
    reordered = json.dumps(dict(reversed(list(body.items()))), indent=2)
    assert client.post("/api/native/batches", headers={**authorization(session), "Content-Type": "application/json"}, content=reordered).json()["duplicate"] is True
    changed = {**body, "data": {"different": []}}
    assert post_batch(client, session, changed).status_code == 409
    with planner.connect() as con:
        rows = con.execute("SELECT * FROM native_batches").fetchall()
        assert len(rows) == 1
        assert json.loads(rows[0]["payload"]) == body
    status = client.get("/api/native/status", headers=authorization(session)).json()
    assert status["devices"][0]["retained_batch_count"] == 1
    assert status["devices"][0]["last_window_end_ms"] == body["window"]["end_ms"]
    assert status["last_batches"][0]["batch_id"] == body["batch_id"]
    assert "payload" not in status["last_batches"][0]


def test_tenant_isolation_and_device_ownership_are_bound_to_bearer(client):
    create_member(client)
    first = sign_in(client)
    device = register(client, first)
    assert post_batch(client, first, batch(first, device)).status_code == 200
    create_member(client, "other@example.com")
    other = sign_in(client, "other@example.com")
    assert client.get("/api/native/status", headers=authorization(other)).json()["devices"] == []
    assert client.get("/api/native/status", headers=authorization(other)).json()["last_batches"] == []
    registration = {"device_id": device, "platform": "android", "name": "Other", "history_days": 1}
    assert client.post("/api/native/devices", headers=authorization(other), json=registration).status_code == 409
    assert post_batch(client, other, batch(first, device)).status_code == 403
    assert post_batch(client, other, batch(other, device)).status_code == 404
    own_device = register(client, other)
    duplicate_id = batch(other, own_device)
    with planner.connect() as con:
        duplicate_id["batch_id"] = con.execute("SELECT batch_id FROM native_batches WHERE tenant=?", (first["user"]["id"],)).fetchone()["batch_id"]
    assert post_batch(client, other, duplicate_id).json()["duplicate"] is False


def test_unregistered_device_and_invalid_window_are_rejected(client):
    create_member(client)
    session = sign_in(client)
    assert post_batch(client, session, batch(session, str(uuid4()))).status_code == 404
    device = register(client, session)
    for mutate in (
        lambda value: value.update(device_id="not-a-uuid"),
        lambda value: value.update(schema_version=2),
        lambda value: value.update(schema_version=True),
        lambda value: value.update(extra="unknown"),
        lambda value: value["window"].update(start_ms=-1),
        lambda value: value["window"].update(start_ms=value["window"]["end_ms"]),
        lambda value: value["window"].update(end_ms=int(time.time() * 1000) + 86_400_000),
        lambda value: value.update(collected_at_ms=0),
        lambda value: value["window"].update(start_ms=True),
        lambda value: value.update(permissions=[]),
        lambda value: value.update(data=[]),
    ):
        value = batch(session, device)
        mutate(value)
        assert post_batch(client, session, value).status_code == 422, value
    with planner.connect() as con:
        assert con.execute("SELECT COUNT(*) AS n FROM native_batches").fetchone()["n"] == 0


@pytest.mark.parametrize("key", ["password", "PASSWORD", "saved_password", "access_token", "apiKey", "Authorization", "refreshToken", "cookies", "client_secret", "credentials"])
def test_nested_credentials_are_rejected_without_echoing_the_value(client, key):
    create_member(client)
    session = sign_in(client)
    body = batch(session, register(client, session))
    body["data"]["extra"] = [{"nested": {key: "highly-sensitive-secret-value"}}]
    response = post_batch(client, session, body)
    assert response.status_code == 422
    assert "highly-sensitive-secret-value" not in response.text


def test_json_parser_rejects_duplicate_keys_nonfinite_values_and_bad_encoding(client):
    create_member(client)
    session = sign_in(client)
    headers = {**authorization(session), "Content-Type": "application/json"}
    assert client.post("/api/native/batches", headers=headers, content='{"schema_version":1,"schema_version":2}').status_code == 422
    body = batch(session, register(client, session))
    body["data"]["bad"] = "REPLACE_INFINITY"
    encoded = json.dumps(body)
    for invalid in ("NaN", "Infinity", "1e1000"):
        assert client.post("/api/native/batches", headers=headers, content=encoded.replace('"REPLACE_INFINITY"', invalid)).status_code == 422
    assert client.post("/api/native/batches", headers=headers, content=b"\xff\xfe").status_code == 422
    assert client.post("/api/native/batches", headers=headers, content=encoded.replace('"REPLACE_INFINITY"', '"\\ud800"')).status_code == 422
    assert client.post("/api/native/login", json={"email": "bad", "password": "do-not-echo-this"}).status_code == 422
    assert "do-not-echo-this" not in client.post("/api/native/login", json={"email": "bad", "password": "do-not-echo-this"}).text


def test_declared_and_streamed_body_limits_apply_before_json_parsing(client, monkeypatch):
    create_member(client)
    session = sign_in(client)
    body = batch(session, register(client, session))
    monkeypatch.setattr(native, "MAX_BATCH_BYTES", 256)
    assert post_batch(client, session, body).status_code == 413
    assert client.post("/api/native/batches", headers={**authorization(session), "Content-Type": "application/json", "Content-Length": "999"}, content=b"{}").status_code == 413
    assert client.post("/api/native/batches", headers={**authorization(session), "Content-Type": "application/json", "Content-Encoding": "gzip"}, content=b"{}").status_code == 415

    # ASGI receives each chunk separately, so this covers missing Content-Length.
    sent = []
    messages = iter([{"type": "http.request", "body": b"x" * 200, "more_body": True},
                     {"type": "http.request", "body": b"x" * 100, "more_body": False}])

    async def downstream(scope, receive, send):
        pytest.fail("Oversized streaming bodies must never reach the application.")

    async def receive():
        return next(messages)

    async def send(message):
        sent.append(message)

    asyncio.run(native.NativeBodyLimitMiddleware(downstream)({"type": "http", "path": "/api/native/batches", "headers": []}, receive, send))
    assert sent[0]["status"] == 413
    with planner.connect() as con:
        assert con.execute("SELECT COUNT(*) AS n FROM native_batches").fetchone()["n"] == 0


def test_raw_retention_is_bounded_but_retries_still_match_the_receipt(client, monkeypatch):
    create_member(client)
    session = sign_in(client)
    device = register(client, session)
    first = batch(session, device)
    assert post_batch(client, session, first).status_code == 200
    with planner.connect() as con:
        first_bytes = con.execute("SELECT payload_bytes FROM native_batches").fetchone()["payload_bytes"]
    monkeypatch.setattr(native, "MAX_TENANT_RAW_BYTES", first_bytes + 100)
    second = batch(session, device)
    assert post_batch(client, session, second).status_code == 200
    with planner.connect() as con:
        row = con.execute("SELECT payload,payload_bytes FROM native_batches WHERE batch_id=?", (first["batch_id"],)).fetchone()
        assert row["payload"] is None and row["payload_bytes"] == 0
        assert con.execute("SELECT SUM(payload_bytes) AS n FROM native_batches").fetchone()["n"] <= native.MAX_TENANT_RAW_BYTES
    assert post_batch(client, session, first).json()["duplicate"] is True
    monkeypatch.setattr(native, "MAX_TENANT_RECEIPTS", 2)
    third = batch(session, device)
    assert post_batch(client, session, third).status_code == 200
    with planner.connect() as con:
        assert con.execute("SELECT COUNT(*) AS n FROM native_batches").fetchone()["n"] == 2


def test_history_retention_and_account_deletion_remove_owned_exports(client):
    create_member(client)
    session = sign_in(client)
    device = register(client, session)
    old = batch(session, device)
    assert post_batch(client, session, old).status_code == 200
    now = int(time.time() * 1000)
    with planner.connect() as con:
        con.execute("UPDATE native_batches SET received_at_ms=?", (now - 91 * native.DAY_MS,))
        native.prune_tenant_batches(con, session["user"]["id"], now)
        assert con.execute("SELECT payload FROM native_batches").fetchone()["payload"] is None
        con.execute("UPDATE native_batches SET received_at_ms=?", (now - 366 * native.DAY_MS,))
        native.prune_tenant_batches(con, session["user"]["id"], now)
        assert con.execute("SELECT COUNT(*) AS n FROM native_batches").fetchone()["n"] == 0
    assert post_batch(client, session, batch(session, device)).status_code == 200
    client.post("/api/auth/login", json={"email": "admin@example.com", "password": "admin123"})
    assert client.delete("/api/admin/users/" + session["user"]["id"]).status_code == 200
    with planner.connect() as con:
        assert con.execute("SELECT COUNT(*) AS n FROM native_batches").fetchone()["n"] == 0
        assert con.execute("SELECT COUNT(*) AS n FROM native_devices").fetchone()["n"] == 0


def test_dashboard_bootstraps_cookie_and_uses_existing_frontend(client):
    create_member(client)
    session = sign_in(client)
    client.cookies.clear()
    assert client.get("/api/native/dashboard", follow_redirects=False).status_code == 401
    response = client.get("/api/native/dashboard", headers=authorization(session), follow_redirects=False)
    assert response.status_code == 303
    assert response.headers["location"] == "/"
    assert "HttpOnly" in response.headers["set-cookie"]
    assert response.headers["cache-control"] == "no-store"
    assert client.get("/api/me").json()["account"] == session["user"]
    assert client.get("/api/native/dashboard", follow_redirects=False).status_code == 303
    assert client.get("/api/native/dashboard", headers={"Authorization": "Bearer invalid"}, follow_redirects=False).status_code == 401
    # The checked-out build is served on the API origin, enabling the WebView.
    root = client.get("/")
    assert root.status_code == 200
    assert "text/html" in root.headers["content-type"]


def test_native_schemas_are_available_in_openapi(client):
    schemas = client.get("/openapi.json").json()["paths"]
    batch_schema = schemas["/api/native/batches"]["post"]["requestBody"]["content"]["application/json"]["schema"]
    assert batch_schema["properties"]["window"]["properties"]["start_ms"]["type"] == "integer"
    assert batch_schema["additionalProperties"] is False
    assert schemas["/api/native/login"]["post"]["requestBody"]["required"] is True
