"""Browser device history, durable sync bookkeeping, and revocation integration."""

import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time
from datetime import datetime, timezone
from uuid import uuid4

import pytest
from fastapi.testclient import TestClient

from backend import server as planner
from data_sync.native_app.server import app as native
from data_sync.native_app.server import device_api


@pytest.fixture
def client(tmp_path, monkeypatch):
    monkeypatch.setattr(planner, "DATA", tmp_path)
    monkeypatch.setattr(planner, "DB", tmp_path / "devices.sqlite3")
    monkeypatch.setattr(planner, "cache", planner.Cache(tmp_path / "cache"))
    monkeypatch.setenv("SMTP_HOST", "")
    monkeypatch.delenv("NATIVE_PUBLIC_URL", raising=False)
    with TestClient(native.app) as connection:
        yield connection


def member(client, email="devices@example.com"):
    assert client.post("/api/auth/signup", json={"name": "Collector", "email": email, "password": "password123"}).status_code == 201
    result = client.post("/api/native/login", json={"email": email, "password": "password123"})
    assert result.status_code == 200
    return result.json()


def auth(session):
    return {"Authorization": "Bearer " + session["token"]}


def registration(device=None, name="Personal phone"):
    return {"device_id": device or str(uuid4()), "name": name, "platform": "android", "history_days": 30}


def register(client, session, device=None):
    body = registration(device)
    response = client.post("/api/native/devices", headers=auth(session), json=body)
    assert response.status_code == 200, response.text
    return body["device_id"]


def batch(session, device, data=None):
    now = int(time.time() * 1000)
    return {"schema_version": 1, "batch_id": str(uuid4()), "device_id": device, "user_id": session["user"]["id"],
            "window": {"start_ms": now - 3_600_000, "end_ms": now}, "collected_at_ms": now,
            "permissions": {"usage_access": True}, "data": data or {
                "usage_events": {"status": "ok", "complete": True, "records": [], "retention_note": "Device-controlled history"},
                "health_steps": {"status": "denied", "complete": False, "records": [], "permission": "android.permission.health.READ_STEPS"},
            }}


def upload(client, session, body):
    response = client.post("/api/native/batches", headers=auth(session), json=body)
    assert response.status_code == 200, response.text
    return response.json()


def prefix(device, body):
    return f"/api/devices/{device}/batches/{body['batch_id']}"


def test_browser_auth_is_cookie_only_and_private_errors_are_no_store(client):
    for path in ("/api/devices", "/api/devices/setup", "/api/wellbeing", "/api/devices/apk/forma-data-sync-preview.apk"):
        response = client.get(path)
        assert response.status_code == 401
        assert response.headers["cache-control"] == "no-store"
    session = member(client)
    device = register(client, session)
    assert client.get("/api/devices").json()["devices"][0]["device_id"] == device
    client.cookies.clear()
    assert client.get("/api/devices", headers=auth(session)).status_code == 401
    assert client.get("/api/native/status", headers=auth(session)).status_code == 200


def test_device_metadata_counters_and_duplicate_receipts(client):
    session = member(client)
    device = register(client, session)
    details = client.get(f"/api/devices/{device}").json()["device"]
    assert details["name"] == "Personal phone" and details["platform"] == "android"
    assert details["registered_at_ms"] <= int(time.time() * 1000)
    assert details["sync_count"] == 0 and details["first_synced_at_ms"] is None
    first = batch(session, device)
    upload(client, session, first)
    assert upload(client, session, first)["duplicate"] is True
    second = batch(session, device)
    upload(client, session, second)
    changed = {**first, "data": {"different": []}}
    assert client.post("/api/native/batches", headers=auth(session), json=changed).status_code == 409
    assert client.post("/api/native/devices", headers=auth(session), json=registration(device, "Renamed phone")).status_code == 200
    details = client.get(f"/api/devices/{device}").json()["device"]
    assert details["name"] == "Renamed phone"
    assert details["sync_count"] == details["retained_batch_count"] == details["retained_raw_batch_count"] == 2
    assert details["first_synced_at_ms"] <= details["last_synced_at_ms"]
    assert details["last_window_end_ms"] == second["window"]["end_ms"]
    assert details["counter_backfilled"] is False
    assert client.get("/api/devices").headers["cache-control"] == "no-store"


def test_lifetime_counter_and_retry_deduplication_survive_receipt_expiry(client):
    session = member(client)
    device = register(client, session)
    first = batch(session, device)
    upload(client, session, first)
    with planner.connect() as con:
        con.execute("UPDATE native_batches SET received_at_ms=? WHERE tenant=?", (int(time.time() * 1000) - 366 * native.DAY_MS, session["user"]["id"]))
        native.prune_tenant_batches(con, session["user"]["id"], int(time.time() * 1000))
    assert client.get(f"/api/devices/{device}/batches").json()["total"] == 0
    assert upload(client, session, first)["duplicate"] is True
    assert client.get(f"/api/devices/{device}").json()["device"]["sync_count"] == 1
    with planner.connect() as con:
        assert con.execute("SELECT COUNT(*) FROM native_batches").fetchone()[0] == 0
    native.init_native_db()
    assert client.get(f"/api/devices/{device}").json()["device"]["sync_count"] == 1
    upload(client, session, batch(session, device))
    assert client.get(f"/api/devices/{device}").json()["device"]["sync_count"] == 2


def test_retained_history_is_paginated_deterministically_with_bounded_parameters(client):
    session = member(client)
    device = register(client, session)
    bodies = [batch(session, device) for _ in range(5)]
    for body in bodies:
        upload(client, session, body)
    with planner.connect() as con:
        con.execute("UPDATE native_batches SET received_at_ms=12345 WHERE tenant=?", (session["user"]["id"],))
    first = client.get(f"/api/devices/{device}/batches?limit=2").json()
    second = client.get(f"/api/devices/{device}/batches?limit=2&offset=2").json()
    last = client.get(f"/api/devices/{device}/batches?limit=2&offset=4").json()
    assert first["total"] == 5 and first["next_offset"] == 2 and second["next_offset"] == 4
    assert last["has_more"] is False and last["next_offset"] is None
    assert [row["batch_id"] for row in first["batches"] + second["batches"] + last["batches"]] == [body["batch_id"] for body in reversed(bodies)]
    assert all("payload" not in row for row in first["batches"])
    for query in ("limit=0", "limit=101", "offset=-1", "offset=1000001"):
        assert client.get(f"/api/devices/{device}/batches?{query}").status_code == 422


def test_complete_source_metadata_and_every_record_survive_paging(client):
    session = member(client)
    device = register(client, session)
    records = [{"timestamp_ms": 123 + n, "event_type": 999, "package_name": "example.future", "new_field": {"nested": n}} for n in range(7)]
    data = {"usage_events": {"status": "ok", "complete": True, "records": records, "interval_note": "bucket caveat"},
            "health_steps": {"status": "denied", "records": [], "permission": "READ_STEPS"},
            "unknown/source": {"status": "partial", "records": [{"future": [1, 2]}], "extra": {"anything": True}},
            "legacy_array": [1, {"value": 2}], "legacy_scalar": "retained unchanged"}
    body = batch(session, device, data)
    upload(client, session, body)
    route = prefix(device, body)
    detail = client.get(route).json()
    assert detail["permissions"] == body["permissions"] and detail["raw_retained"] is True
    sources = {row["key"]: row for row in detail["sources"]}
    assert set(sources) == set(data)
    assert sources["usage_events"]["record_count"] == 7
    assert sources["usage_events"]["metadata"]["interval_note"] == "bucket caveat"
    assert sources["health_steps"]["status"] == "denied" and sources["health_steps"]["record_count"] == 0
    pages = [client.get(route + f"/sources/usage_events?limit=3&offset={offset}").json() for offset in (0, 3, 6)]
    assert [record for page in pages for record in page["records"]] == records
    assert pages[-1]["next_offset"] is None and pages[0]["total"] == 7
    assert client.get(route + "/sources/unknown/source").json()["source"]["metadata"]["extra"] == {"anything": True}
    assert client.get(route + "/sources/legacy_scalar").json()["source"]["metadata"] == {"value": "retained unchanged"}
    assert client.get(route + "/raw").json() == body
    assert client.get(route + "/sources/missing").status_code == 404
    assert client.get(route + "/sources/usage_events?limit=501").status_code == 422


def test_unknown_or_malformed_summary_fields_do_not_prevent_native_raw_collection(client):
    session = member(client)
    device = register(client, session)
    data = {
        "health_steps": {"status": "ok", "complete": True, "records": [{"_type": [], "count": 10**350}, {"_type": {"future": "SDK type"}}]},
        "usage_events": {"status": "ok", "records": [{"timestamp_ms": 10**350, "event_type": 1, "package_name": {"future": True}}]},
        "health_status": {"status": {"future": True}, "records": []},
        "device_snapshot": {"status": "ok", "records": [{"timezone": ["UTC"]}]},
        "future_source": [{"future": {"measurement": 12.5}}],
    }
    body = batch(session, device, data)
    upload(client, session, body)
    assert client.get(prefix(device, body) + "/raw").json() == body
    assert client.get(f"/api/devices/{device}").json()["device"]["sync_count"] == 1


def test_source_display_json_preserves_64bit_integer_precision_and_original_types(client):
    session = member(client)
    device = register(client, session)
    large = 9007199254740993
    record = {"sensor_timestamp_nanos": large, "same_digits_text": str(large),
              "missing": None, "fraction": 1.25, "nested": [large, str(large), None, 1.25],
              "label": "測定"}
    body = batch(session, device, {"activity_snapshot": {"status": "ok", "complete": True,
        "captured_at_nanos": large, "records": [record, str(large), None, 1.25]}})
    body["permissions"]["captured_at_nanos"] = large
    upload(client, session, body)
    route = prefix(device, body)
    details = client.get(route).json()
    assert details["permissions"]["captured_at_nanos"] == large
    assert f'"captured_at_nanos": {large}' in details["permissions_json"]
    descriptor = details["sources"][0]
    assert f'"captured_at_nanos": {large}' in descriptor["metadata_json"]
    assert json.loads(descriptor["metadata_json"]) == descriptor["metadata"]
    first = client.get(route + "/sources/activity_snapshot?limit=2").json()
    second = client.get(route + "/sources/activity_snapshot?limit=2&offset=2").json()
    assert first["records"] == [record, str(large)] and second["records"] == [None, 1.25]
    assert first["record_jsons"] == [json.dumps(record, ensure_ascii=False, indent=2), f'"{large}"']
    assert second["record_jsons"] == ["null", "1.25"]
    assert len(first["record_jsons"]) == len(first["records"]) == 2
    assert f'"sensor_timestamp_nanos": {large}' in first["record_jsons"][0]
    assert f'"same_digits_text": "{large}"' in first["record_jsons"][0]
    assert "測定" in first["record_jsons"][0]
    assert json.loads(first["record_jsons"][0]) == record
    raw = client.get(route + "/raw")
    assert raw.json() == body
    assert f'"sensor_timestamp_nanos":{large}' in raw.text
    assert f'"same_digits_text":"{large}"' in raw.text


def test_raw_expiry_is_visible_without_losing_receipt_or_lifetime_counter(client):
    session = member(client)
    device = register(client, session)
    body = batch(session, device)
    upload(client, session, body)
    with planner.connect() as con:
        con.execute("UPDATE native_batches SET received_at_ms=?", (int(time.time() * 1000) - 91 * native.DAY_MS,))
        native.prune_tenant_batches(con, session["user"]["id"], int(time.time() * 1000))
    route = prefix(device, body)
    assert client.get(route).json()["raw_retained"] is False
    assert client.get(route).json()["sources"] == []
    for path in (route + "/raw", route + "/sources/usage_events"):
        response = client.get(path)
        assert response.status_code == 410 and "expired" in response.json()["detail"]
        assert response.headers["cache-control"] == "no-store"
    details = client.get(f"/api/devices/{device}").json()["device"]
    assert details["sync_count"] == details["retained_batch_count"] == 1
    assert details["retained_raw_batch_count"] == 0


def test_ingestion_populates_daily_and_weekly_views_before_raw_history_expires(client):
    session = member(client)
    device = register(client, session)
    now = int(time.time() * 1000)
    resumed = now // native.DAY_MS * native.DAY_MS - native.DAY_MS + 12 * 3_600_000
    paused = resumed + 60_000
    body = batch(session, device, {
        "usage_events": {"status": "ok", "complete": True, "records": [
            {"timestamp_ms": resumed, "event_type": 1, "package_name": "example.app"},
            {"timestamp_ms": paused, "event_type": 2, "package_name": "example.app"}]},
        "device_snapshot": {"status": "ok", "mode": "snapshot", "records": [{"timezone": "UTC"}]},
    })
    body["window"] = {"start_ms": resumed - 60_000, "end_ms": paused + 60_000}
    upload(client, session, body)
    selected = datetime.fromtimestamp(resumed / 1000, timezone.utc).date().isoformat()
    path = f"/api/wellbeing?device_id={device}&start_date={selected}&end_date={selected}"
    result = client.get(path).json()
    assert result["timezone"] == "UTC"
    assert result["days"][0]["usage"]["foreground_ms"] == 60_000
    assert result["backfill"]["complete"] is True
    weekly = client.get(path + "&period=weekly").json()
    assert len(weekly["weeks"][0]["days"]) == 7
    assert weekly["weeks"][0]["usage"]["foreground_ms"] == 60_000
    with planner.connect() as con:
        con.execute("UPDATE native_batches SET received_at_ms=? WHERE batch_id=?", (now - 91 * native.DAY_MS, body["batch_id"]))
        native.prune_tenant_batches(con, session["user"]["id"], now)
    assert client.get(prefix(device, body) + "/raw").status_code == 410
    assert client.get(path).json()["days"][0]["usage"]["foreground_ms"] == 60_000


def test_foreign_account_cannot_read_delete_or_aggregate_a_device(client):
    first = member(client)
    device = register(client, first)
    body = batch(first, device)
    upload(client, first, body)
    other = member(client, "other-device@example.com")
    assert client.get("/api/devices").json()["devices"] == []
    route = prefix(device, body)
    for path in (f"/api/devices/{device}", f"/api/devices/{device}/batches", route, route + "/raw", route + "/sources/usage_events", f"/api/wellbeing?device_id={device}"):
        assert client.get(path).status_code == 404
    assert client.delete(f"/api/devices/{device}").status_code == 404
    assert client.post("/api/native/batches", headers=auth(other), json=batch(other, device)).status_code == 404
    assert client.post("/api/native/web-session", headers=auth(first)).status_code == 200
    assert client.get(route + "/raw").json() == body


def test_delete_erases_only_owned_history_and_blocks_background_reconnection(client):
    session = member(client)
    device = register(client, session)
    remaining_device = register(client, session)
    now = int(time.time() * 1000)
    body = batch(session, device, {"usage_events": {"status": "ok", "complete": True, "records": [
        {"timestamp_ms": now - 60_000, "event_type": 1, "package_name": "example.app"},
        {"timestamp_ms": now - 30_000, "event_type": 2, "package_name": "example.app"}]}})
    upload(client, session, body)
    upload(client, session, batch(session, remaining_device))
    with planner.connect() as con:
        assert con.execute("SELECT COUNT(*) FROM native_wellbeing_facts WHERE tenant=? AND device_id=?", (session["user"]["id"], device)).fetchone()[0] == 2
    response = client.delete(f"/api/devices/{device}")
    assert response.json() == {"deleted": True, "device_id": device}
    assert client.delete(f"/api/devices/{device}").status_code == 404
    assert client.get(prefix(device, body) + "/raw").status_code == 404
    assert [row["device_id"] for row in client.get("/api/devices").json()["devices"]] == [remaining_device]
    with planner.connect() as con:
        tables = [row["name"] for row in con.execute("SELECT name FROM sqlite_master WHERE type='table' AND name LIKE 'native_%'")]
        for table in tables:
            columns = {row["name"] for row in con.execute(f'PRAGMA table_info("{table}")')}
            if {"tenant", "device_id"} <= columns:
                assert con.execute(f'SELECT COUNT(*) FROM "{table}" WHERE tenant=? AND device_id=?', (session["user"]["id"], device)).fetchone()[0] == 0, table
        tombstone = con.execute("SELECT * FROM native_device_revocations").fetchone()
        assert tombstone["device_hash"] == hashlib.sha256(device.encode()).hexdigest()
        assert device not in json.dumps(dict(tombstone))
    for result in (client.post("/api/native/devices", headers=auth(session), json=registration(device)),
                   client.post("/api/native/batches", headers=auth(session), json=body)):
        assert result.status_code == 410 and "removed" in result.json()["detail"]
    native.init_native_db()
    assert client.post("/api/native/devices", headers=auth(session), json=registration(device)).status_code == 410
    new_device = register(client, session)
    assert new_device != device
    assert client.get(f"/api/devices/{remaining_device}").json()["device"]["sync_count"] == 1


def test_upgrade_backfills_retained_receipts_once_and_discloses_older_history_limit(client):
    session = member(client)
    device = register(client, session)
    for _ in range(2):
        upload(client, session, batch(session, device))
    with planner.connect() as con:
        con.execute("DELETE FROM native_device_stats")
        con.execute("DELETE FROM native_seen_batches")
        device_api.init_device_history_db(con)
        device_api.init_device_history_db(con)
    view = client.get(f"/api/devices/{device}").json()["device"]
    assert view["sync_count"] == 2 and view["counter_backfilled"] is True
    assert "expired" in view["sync_count_note"]
    upload(client, session, batch(session, device))
    assert client.get(f"/api/devices/{device}").json()["device"]["sync_count"] == 3


def test_administrator_can_only_view_devices_connected_to_their_own_account(client):
    session = member(client)
    member_device = register(client, session)
    result = client.post("/api/native/login", json={"email": "admin@example.com", "password": "admin123"})
    assert result.status_code == 200
    admin = result.json()
    device = register(client, admin)
    upload(client, admin, batch(admin, device))
    assert [row["device_id"] for row in client.get("/api/devices").json()["devices"]] == [device]
    assert client.get(f"/api/devices/{member_device}").status_code == 404
    assert client.delete(f"/api/devices/{member_device}").status_code == 404


def test_connect_instructions_and_apk_downloads_are_authenticated_and_whitelisted(client, tmp_path, monkeypatch):
    session = member(client)
    monkeypatch.setattr(device_api, "APK_DIRECTORY", tmp_path)
    monkeypatch.setenv("NATIVE_PUBLIC_URL", "http://192.168.100.8:8000")
    filename = "forma-data-sync-lan-preview-arm64-v8a.apk"
    contents = b"fixture-apk"
    (tmp_path / filename).write_bytes(contents)
    (tmp_path / "unrelated.apk").write_bytes(b"never served")
    (tmp_path / "forma-data-sync-preview.apk").symlink_to(tmp_path / filename)
    setup = client.get("/api/devices/setup")
    assert setup.status_code == 200 and setup.headers["cache-control"] == "no-store"
    data = setup.json()
    assert data["server_origin"] == "http://192.168.100.8:8000" and data["lan_preview"] is True
    assert data["current_account"] == {"id": session["user"]["id"], "email": session["user"]["email"]}
    assert data["apks"] == [{"filename": filename, "abi": "arm64-v8a", "lan": True,
                             "label": "LAN preview · arm64-v8a", "size_bytes": len(contents),
                             "url": "/api/devices/apk/" + filename}]
    assert "password123" not in setup.text and "admin123" not in setup.text
    download = client.get(data["apks"][0]["url"])
    assert download.content == contents
    assert download.headers["content-type"] == "application/vnd.android.package-archive"
    assert client.get("/api/devices/apk/unrelated.apk").status_code == 404
    assert client.get("/api/devices/apk/forma-data-sync-preview.apk").status_code == 404
    client.cookies.clear()
    assert client.get(data["apks"][0]["url"]).status_code == 401


def test_connection_origin_selects_compatible_build_and_supports_reverse_proxy_configuration(client, tmp_path, monkeypatch):
    member(client)
    monkeypatch.setattr(device_api, "APK_DIRECTORY", tmp_path)
    for filename in ("forma-data-sync-preview.apk", "forma-data-sync-lan-preview.apk"):
        (tmp_path / filename).write_bytes(b"fixture")
    for origin in ("https://forma.example.com", "http://localhost:8000", "http://127.0.0.1:8000", "http://172.15.0.1:8000"):
        monkeypatch.setenv("NATIVE_PUBLIC_URL", origin)
        setup = client.get("/api/devices/setup").json()
        assert setup["server_origin"] == origin and setup["lan_preview"] is False
        assert [row["filename"] for row in setup["apks"]] == ["forma-data-sync-preview.apk"]
    for origin in ("http://10.0.0.1:8000", "http://172.16.1.2:8000", "http://192.168.1.1:8000"):
        monkeypatch.setenv("NATIVE_PUBLIC_URL", origin)
        setup = client.get("/api/devices/setup").json()
        assert setup["lan_preview"] is True
        assert [row["filename"] for row in setup["apks"]] == ["forma-data-sync-lan-preview.apk"]
    for origin in ("https://example.com/path", "https://user:password@example.com", "https://example.com?x=1", "https://example.com:bad", "https://[bad"):
        monkeypatch.setenv("NATIVE_PUBLIC_URL", origin)
        response = client.get("/api/devices/setup")
        assert response.status_code == 503 and response.headers["cache-control"] == "no-store"


def test_account_deletion_erases_bookkeeping_and_revocation_tombstones(client):
    session = member(client)
    device = register(client, session)
    upload(client, session, batch(session, device))
    client.delete(f"/api/devices/{device}")
    with planner.connect() as con:
        con.execute("DELETE FROM accounts WHERE id=?", (session["user"]["id"],))
        assert con.execute("SELECT COUNT(*) FROM native_device_revocations").fetchone()[0] == 0
        assert con.execute("SELECT COUNT(*) FROM native_seen_batches").fetchone()[0] == 0
        assert con.execute("SELECT COUNT(*) FROM native_device_stats").fetchone()[0] == 0


@pytest.mark.parametrize("entry", ["backend.server", "data_sync.native_app.server.app"])
def test_both_server_entrypoint_import_orders_install_routes_once_and_initialize_storage(tmp_path, entry):
    # A clean interpreter catches circular-import failures hidden by pytest's
    # shared import cache. Import the same module uvicorn loads for each command.
    script = """
import importlib, sys
from fastapi.testclient import TestClient
def route_paths(routes):
    for route in routes:
        if hasattr(route, 'path'):
            yield route.path
        nested = getattr(route, 'original_router', None)
        if nested is not None:
            yield from route_paths(nested.routes)
entry = importlib.import_module(sys.argv[1])
for path in ('/api/devices', '/api/wellbeing', '/api/native/devices'):
    assert list(route_paths(entry.app.routes)).count(path) == 1, path
from backend import server as planner
from data_sync.native_app.server import app as native
assert entry.app is native.app is planner.app
for path in ('/api/devices', '/api/wellbeing', '/api/native/devices'):
    assert list(route_paths(planner.app.routes)).count(path) == 1, path
with TestClient(entry.app) as client:
    assert client.get('/api/health').status_code == 200
    assert client.get('/api/devices').status_code == 401
    assert client.get('/api/native/status').status_code == 401
    with planner.connect() as con:
        tables = {row['name'] for row in con.execute("SELECT name FROM sqlite_master WHERE type='table'")}
        assert {'native_devices','native_device_stats','native_seen_batches','native_wellbeing_days'} <= tables
print('entrypoint checks passed')
"""
    result = subprocess.run([sys.executable, "-c", script, entry], cwd=Path(__file__).resolve().parents[4],
                            env={**os.environ, "FORMA_DATA_DIR": str(tmp_path), "SMTP_HOST": ""},
                            text=True, capture_output=True, timeout=30)
    assert result.returncode == 0, result.stderr
    assert "entrypoint checks passed" in result.stdout
