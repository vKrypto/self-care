"""Account-scoped browser views of Android receipts and retained source records."""

from datetime import date
import hashlib
import ipaddress
import json
import os
from pathlib import Path
import time
from typing import Literal
from uuid import UUID
from urllib.parse import urlsplit

from fastapi import APIRouter, Depends, HTTPException, Query, Request
from fastapi.responses import FileResponse

from backend import server as planner


router = APIRouter(tags=["Connected devices"])
APK_DIRECTORY = Path(os.getenv("NATIVE_APK_DIR", str(Path(__file__).resolve().parents[2] / "apk"))).resolve()
APK_NAMES = frozenset(
    f"forma-data-sync-{variant}{suffix}.apk"
    for variant in ("preview", "lan-preview")
    for suffix in ("", "-arm64-v8a", "-armeabi-v7a", "-x86", "-x86_64")
)


class PrivateDeviceResponseMiddleware:
    """Keep successful and failed account-data responses out of shared caches."""

    def __init__(self, app):
        self.app = app

    async def __call__(self, scope, receive, send):
        path = scope.get("path", "")
        private = scope["type"] == "http" and (path == "/api/devices" or path.startswith("/api/devices/") or path == "/api/wellbeing")

        async def no_store(message):
            if private and message["type"] == "http.response.start":
                headers = [(k, v) for k, v in message.get("headers", []) if k.lower() not in (b"cache-control", b"pragma")]
                message = {**message, "headers": headers + [(b"cache-control", b"no-store"), (b"pragma", b"no-cache")]}
            await send(message)

        await self.app(scope, receive, no_store)


def init_device_history_db(con):
    """Add durable counters/deduplication without changing native wire schemas."""
    con.executescript("""
        CREATE TABLE IF NOT EXISTS native_device_stats (
            tenant TEXT NOT NULL,
            device_id TEXT NOT NULL,
            first_synced_at_ms INTEGER,
            last_synced_at_ms INTEGER,
            last_window_end_ms INTEGER,
            sync_count INTEGER NOT NULL DEFAULT 0,
            counter_started_at_ms INTEGER NOT NULL,
            counter_backfilled INTEGER NOT NULL DEFAULT 0,
            PRIMARY KEY(tenant, device_id),
            FOREIGN KEY(tenant, device_id) REFERENCES native_devices(tenant, device_id) ON DELETE CASCADE
        );
        CREATE TABLE IF NOT EXISTS native_seen_batches (
            tenant TEXT NOT NULL,
            batch_id TEXT NOT NULL,
            device_id TEXT NOT NULL,
            payload_sha256 TEXT NOT NULL,
            PRIMARY KEY(tenant, batch_id),
            FOREIGN KEY(tenant, device_id) REFERENCES native_devices(tenant, device_id) ON DELETE CASCADE
        );
        CREATE INDEX IF NOT EXISTS native_seen_batches_device
            ON native_seen_batches(tenant, device_id);
        CREATE TABLE IF NOT EXISTS native_device_revocations (
            tenant TEXT NOT NULL REFERENCES accounts(id) ON DELETE CASCADE,
            device_hash TEXT NOT NULL,
            revoked_at_ms INTEGER NOT NULL,
            PRIMARY KEY(tenant, device_hash)
        );
    """)
    timestamp = int(time.time() * 1000)
    con.execute("""INSERT OR IGNORE INTO native_seen_batches(tenant,batch_id,device_id,payload_sha256)
        SELECT tenant,batch_id,device_id,payload_sha256 FROM native_batches""")
    con.execute("""INSERT OR IGNORE INTO native_device_stats
        SELECT d.tenant,d.device_id,MIN(b.received_at_ms),MAX(b.received_at_ms),MAX(b.window_end_ms),
            COUNT(b.batch_id),?,1
        FROM native_devices d LEFT JOIN native_batches b ON b.tenant=d.tenant AND b.device_id=d.device_id
        GROUP BY d.tenant,d.device_id""", (timestamp,))


def ensure_device_stats(con, tenant, device, timestamp):
    con.execute("""INSERT OR IGNORE INTO native_device_stats
        (tenant,device_id,sync_count,counter_started_at_ms,counter_backfilled) VALUES(?,?,0,?,0)""",
        (tenant, device, timestamp))


def device_digest(device):
    return hashlib.sha256(device.encode("ascii")).hexdigest()


def reject_revoked_device(con, tenant, device):
    if con.execute("SELECT 1 FROM native_device_revocations WHERE tenant=? AND device_hash=?", (tenant, device_digest(device))).fetchone():
        raise HTTPException(410, "This device was removed from your account. Uploads are stopped. Reconnect using a new device identifier.")


def record_device_sync(con, tenant, device, batch, digest, received, window_end):
    con.execute("INSERT INTO native_seen_batches VALUES(?,?,?,?)", (tenant, batch, device, digest))
    con.execute("""UPDATE native_device_stats SET sync_count=sync_count+1,
        first_synced_at_ms=CASE WHEN first_synced_at_ms IS NULL THEN ? ELSE MIN(first_synced_at_ms,?) END,
        last_synced_at_ms=CASE WHEN last_synced_at_ms IS NULL THEN ? ELSE MAX(last_synced_at_ms,?) END,
        last_window_end_ms=CASE WHEN last_window_end_ms IS NULL THEN ? ELSE MAX(last_window_end_ms,?) END
        WHERE tenant=? AND device_id=?""", (received, received, received, received, window_end, window_end, tenant, device))


def retention():
    # Import lazily: the native extension also imports this route module.
    from .app import RAW_RETENTION_DAYS, RECEIPT_RETENTION_DAYS, MAX_TENANT_RAW_BYTES, MAX_TENANT_RECEIPTS
    return {"raw_days": RAW_RETENTION_DAYS, "receipt_days": RECEIPT_RETENTION_DAYS,
            "max_raw_bytes_per_user": MAX_TENANT_RAW_BYTES, "max_receipts_per_user": MAX_TENANT_RECEIPTS,
            "note": "All retained receipts and raw records are available. Older raw records expire after the raw-data retention period or storage limit; lifetime sync counters continue."}


DEVICE_SELECT = """SELECT d.device_id,d.platform,d.name,d.sync_interval_minutes,d.consent_version,
    d.history_days,d.registered_at_ms,d.updated_at_ms,s.first_synced_at_ms,s.last_synced_at_ms,
    s.last_window_end_ms,s.sync_count,s.counter_started_at_ms,s.counter_backfilled,
    COUNT(b.batch_id) AS retained_batch_count,
    COALESCE(SUM(b.payload IS NOT NULL),0) AS retained_raw_batch_count
    FROM native_devices d JOIN native_device_stats s ON s.tenant=d.tenant AND s.device_id=d.device_id
    LEFT JOIN native_batches b ON b.tenant=d.tenant AND b.device_id=d.device_id
    WHERE d.tenant=?"""


def device_view(row):
    value = dict(row)
    value["counter_backfilled"] = bool(value["counter_backfilled"])
    value["sync_count_note"] = ("Includes receipts retained when device tracking was upgraded, plus every unique sync since then. Earlier expired receipts cannot be reconstructed."
                                if value["counter_backfilled"] else "Unique accepted batches since this device connected; retry uploads count once.")
    return value


def owned_device(con, tenant, device):
    row = con.execute(DEVICE_SELECT + " AND d.device_id=? GROUP BY d.device_id", (tenant, str(device))).fetchone()
    if not row:
        raise HTTPException(404, "Device not found.")
    return device_view(row)


def receipt_view(row):
    value = dict(row)
    value.pop("payload", None)
    value["raw_retained"] = bool(value["raw_retained"])
    return value


RECEIPT_FIELDS = """batch_id,device_id,schema_version,window_start_ms,window_end_ms,
    collected_at_ms,received_at_ms,payload_sha256,payload_bytes,payload IS NOT NULL AS raw_retained"""


def owned_receipt(con, tenant, device, batch, raw=False):
    owned_device(con, tenant, device)
    row = con.execute("SELECT " + RECEIPT_FIELDS + (",payload" if raw else "") +
                      " FROM native_batches WHERE tenant=? AND device_id=? AND batch_id=?",
                      (tenant, str(device), str(batch))).fetchone()
    if not row:
        raise HTTPException(404, "Sync receipt not found. It may have expired under the receipt retention policy.")
    return row


def retained_payload(row):
    if row["payload"] is None:
        raise HTTPException(410, "Raw records for this sync expired under the raw-data retention or storage policy. Its receipt and daily summaries remain available.")
    return json.loads(row["payload"])


def display_json(value):
    """Serialize before browser JSON parsing can round Android 64-bit integers."""
    return json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False)


def source_view(key, value):
    if isinstance(value, dict):
        records = value.get("records", [])
        is_records = isinstance(records, list)
        metadata = {k: v for k, v in value.items() if k != "records" or not is_records}
        records = records if is_records else []
        status = value.get("status")
        complete = value.get("complete")
    elif isinstance(value, list):
        records, metadata, status, complete = value, {}, None, None
    else:
        records, metadata, status, complete = [], {"value": value}, None, None
    return {"key": key, "status": status, "complete": complete,
            "record_count": len(records), "metadata": metadata,
            "metadata_json": display_json(metadata)}, records


@router.get("/api/devices/setup")
def setup_device(request: Request, account=Depends(planner.current)):
    origin = str(request.base_url).rstrip("/")
    # An explicit deployed origin avoids internal proxy host/port addresses.
    configured = os.getenv("NATIVE_PUBLIC_URL", "").strip().rstrip("/")
    if configured:
        try:
            parts = urlsplit(configured)
            valid = (parts.scheme in ("http", "https") and parts.hostname and not parts.username and
                     not parts.password and not parts.path and not parts.query and not parts.fragment)
            parts.port  # Validate a configured port before giving it to phones.
        except ValueError:
            valid = False
        if not valid:
            raise HTTPException(503, "The public server URL configuration must contain only an HTTP or HTTPS origin.")
        origin = configured
    parts = urlsplit(origin)
    try:
        address = ipaddress.ip_address(parts.hostname or "")
        private_lan = address.version == 4 and any(address in ipaddress.ip_network(network) for network in
            ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16"))
    except ValueError:
        private_lan = False
    lan_origin = parts.scheme == "http" and private_lan
    builds = []
    for filename in sorted(APK_NAMES):
        if ("-lan-" in filename) != lan_origin:
            continue
        path = APK_DIRECTORY / filename
        if path.is_file() and not path.is_symlink():
            suffix = next((abi for abi in ("arm64-v8a", "armeabi-v7a", "x86_64", "x86") if filename.endswith(f"-{abi}.apk")), "universal")
            lan = "-lan-" in filename
            builds.append({"filename": filename, "abi": suffix, "lan": lan,
                           "label": f"{'LAN preview' if lan else 'HTTPS preview'} · {suffix}",
                           "size_bytes": path.stat().st_size, "url": "/api/devices/apk/" + filename})
    return {"server_origin": origin, "current_account": {"id": account["id"], "email": account["email"]},
            "lan_preview": lan_origin,
            "platforms": ["android"], "apks": builds,
            "steps": ["Install the Android APK matching your device architecture; use universal if unsure.",
                      "In the app, connect to this server and sign in with the same account email and password.",
                      "Grant only the data permissions you want to share, enable collection and uploads, then tap Sync now.",
                      "Your device appears here after registration; accepted uploads populate daily and weekly views."],
            "notes": ["The phone must be able to reach the server URL; localhost points to the phone itself.",
                      "Regular builds require HTTPS. A LAN preview permits private LAN HTTP addresses for local development.",
                      "Removing a device stops uploads for that device identifier and erases its server history. Local phone data is managed separately."]}


@router.get("/api/devices/apk/{filename}", include_in_schema=False)
def download_apk(filename: str, account=Depends(planner.current)):
    if filename not in APK_NAMES:
        raise HTTPException(404, "APK not found.")
    path = APK_DIRECTORY / filename
    if not path.is_file() or path.is_symlink():
        raise HTTPException(404, "APK is not built on this server yet.")
    return FileResponse(path, media_type="application/vnd.android.package-archive", filename=filename,
                        headers={"X-Content-Type-Options": "nosniff"})


@router.get("/api/devices")
def list_devices(account=Depends(planner.current)):
    with planner.connect() as con:
        devices = [device_view(row) for row in con.execute(DEVICE_SELECT +
            " GROUP BY d.device_id ORDER BY s.last_synced_at_ms DESC,d.registered_at_ms DESC,d.device_id", (account["id"],))]
    return {"devices": devices, "retention": retention()}


@router.get("/api/devices/{device_id}")
def get_device(device_id: UUID, account=Depends(planner.current)):
    with planner.connect() as con:
        device = owned_device(con, account["id"], device_id)
    return {"device": device, "retention": retention()}


@router.get("/api/devices/{device_id}/batches")
def list_batches(device_id: UUID, limit: int = Query(25, ge=1, le=100), offset: int = Query(0, ge=0, le=1_000_000),
                 account=Depends(planner.current)):
    with planner.connect() as con:
        owned_device(con, account["id"], device_id)
        total = con.execute("SELECT COUNT(*) FROM native_batches WHERE tenant=? AND device_id=?", (account["id"], str(device_id))).fetchone()[0]
        batches = [receipt_view(row) for row in con.execute("SELECT " + RECEIPT_FIELDS + " FROM native_batches WHERE tenant=? AND device_id=? ORDER BY received_at_ms DESC,rowid DESC LIMIT ? OFFSET ?", (account["id"], str(device_id), limit, offset))]
    has_more = offset + len(batches) < total
    return {"batches": batches, "total": total, "limit": limit, "offset": offset,
            "has_more": has_more, "next_offset": offset + len(batches) if has_more else None}


@router.get("/api/devices/{device_id}/batches/{batch_id}")
def get_batch(device_id: UUID, batch_id: UUID, account=Depends(planner.current)):
    with planner.connect() as con:
        row = owned_receipt(con, account["id"], device_id, batch_id, raw=True)
    receipt = receipt_view(row)
    if not receipt["raw_retained"]:
        return {"batch": receipt, "raw_retained": False, "permissions": None, "permissions_json": None, "sources": [],
                "note": "Raw records expired under the raw-data retention or storage policy. Daily summaries and this receipt remain available."}
    payload = retained_payload(row)
    permissions = payload.get("permissions", {})
    return {"batch": receipt, "raw_retained": True, "permissions": permissions,
            "permissions_json": display_json(permissions),
            "sources": [source_view(key, value)[0] for key, value in sorted(payload.get("data", {}).items())]}


@router.get("/api/devices/{device_id}/batches/{batch_id}/raw")
def get_raw_batch(device_id: UUID, batch_id: UUID, account=Depends(planner.current)):
    with planner.connect() as con:
        row = owned_receipt(con, account["id"], device_id, batch_id, raw=True)
    return retained_payload(row)


@router.get("/api/devices/{device_id}/batches/{batch_id}/sources/{source_key:path}")
def get_source(device_id: UUID, batch_id: UUID, source_key: str, limit: int = Query(100, ge=1, le=500),
               offset: int = Query(0, ge=0, le=500_000), account=Depends(planner.current)):
    with planner.connect() as con:
        row = owned_receipt(con, account["id"], device_id, batch_id, raw=True)
    data = retained_payload(row).get("data", {})
    if source_key not in data:
        raise HTTPException(404, "Data source not found in this sync.")
    source, records = source_view(source_key, data[source_key])
    page = records[offset:offset + limit]
    has_more = offset + len(page) < len(records)
    return {"source": source, "records": page, "record_jsons": [display_json(record) for record in page],
            "total": len(records), "limit": limit, "offset": offset,
            "has_more": has_more, "next_offset": offset + len(page) if has_more else None}


@router.delete("/api/devices/{device_id}")
def delete_device(device_id: UUID, account=Depends(planner.current)):
    with planner.connect() as con:
        con.execute("BEGIN IMMEDIATE")
        owned_device(con, account["id"], device_id)
        con.execute("INSERT OR IGNORE INTO native_device_revocations VALUES(?,?,?)", (account["id"], device_digest(str(device_id)), int(time.time() * 1000)))
        con.execute("DELETE FROM native_devices WHERE tenant=? AND device_id=?", (account["id"], str(device_id)))
    return {"deleted": True, "device_id": str(device_id)}


@router.get("/api/wellbeing", tags=["Digital wellbeing"])
def get_wellbeing(device_id: UUID | None = None, start_date: date | None = None, end_date: date | None = None,
                  period: Literal["daily", "weekly"] = "daily", account=Depends(planner.current)):
    from .wellbeing import CURRENT_PROJECTION_VERSION, backfill_wellbeing, read_wellbeing
    with planner.connect() as con:
        if device_id is not None:
            owned_device(con, account["id"], device_id)
        processed = backfill_wellbeing(con, account["id"], str(device_id) if device_id else None, limit=500)
        params = [account["id"]]
        selected = ""
        if device_id:
            selected = " AND b.device_id=?"
            params.append(str(device_id))
        params.append(CURRENT_PROJECTION_VERSION)
        pending = con.execute("""SELECT COUNT(*) FROM native_batches b WHERE b.tenant=? AND b.payload IS NOT NULL"""
                              + selected + """ AND NOT EXISTS (SELECT 1 FROM native_wellbeing_batches w
                              WHERE w.tenant=b.tenant AND w.device_id=b.device_id AND w.batch_id=b.batch_id
                              AND w.projection_version>=?)""", params).fetchone()[0]
        try:
            result = read_wellbeing(con, account["id"], device_id=str(device_id) if device_id else None,
                                    start_date=start_date.isoformat() if start_date else None,
                                    end_date=end_date.isoformat() if end_date else None, period=period)
            result["backfill"] = {"processed_this_request": processed, "pending_batches": pending,
                                  "complete": pending == 0}
            return result
        except ValueError as exc:
            raise HTTPException(422, str(exc)) from None
