# Android ingestion server

This optional ASGI entrypoint extends the existing Forma API without editing its
backend. It uses the existing accounts, salted password hashes and hashed
30-day sessions, and adds tenant-owned `native_devices` and `native_batches`
tables to the same SQLite database. Existing account deletion also deletes its
device registrations and exports.

From the repository root:

```sh
.venv/bin/pip install -r data_sync/native_app/server/requirements.txt
npm run build
.venv/bin/python -m data_sync.native_app.server
```

Run this entrypoint **instead of** `python -m backend.server`. It provides the
existing API, MCP transport and built dashboard on one origin, plus the native
routes below. The default bind address is `0.0.0.0:8000`; use `NATIVE_HOST` and
`NATIVE_PORT` to override it. `NATIVE_FRONTEND_DIST` can point at another built
dashboard directory. The underlying backend still reads the repository `.env`
and honors `FORMA_DATA_DIR`, `ADMIN_EMAIL`, `ADMIN_PASSWORD`, `COOKIE_SECURE`,
and the other existing configuration.

Use an HTTPS origin and `COOKIE_SECURE=true` for a deployed server. A phone can
reach a development server using the computer's LAN address; the Android
emulator uses `http://10.0.2.2:8000`. Create a member account in the existing UI
before signing in on the phone. Set a server URL containing only its origin,
for example `https://forma.example.com`.

The regular standalone Android preview requires HTTPS. For a physical phone
using a local URL such as `http://192.168.1.50:8000`, install the standalone
**LAN preview** described in the [APK guide](../../README.md). It accepts
HTTP only to literal private IPv4 addresses and runs without Metro. HTTP
transmits credentials and records without encryption; use it only for testing
on a trusted LAN and keep `COOKIE_SECURE=false` for its dashboard session.
Open `/api/health` in the phone's browser to check connectivity, then enter only
the origin in the Android app.

Login and server setup are optional for local Android collection. Connecting an
account authorizes uploads of anonymous queued records; records already assigned
to another account cannot be uploaded through the current account. Signing out
stops uploads while local collection continues.

The current Android app opens the server origin in an external browser using
**Open website**. It does not pass native credentials to the browser, whose
login session is independent. The cookie bootstrap endpoints below remain
available for older native clients and other integrations.

Hourly collection and upload scheduling run separately on the phone with Android WorkManager.
Registration records the requested 60-minute schedule; server cron cannot
collect data from a sleeping phone. WorkManager execution can be delayed by
Doze, connectivity, force-stop or manufacturer battery policies. The server
acknowledges a batch only after its database transaction commits. The device
keeps the exact queued batch until it receives that acknowledgement, then removes
it from the local queue. Collection cursors advance after durable local storage,
independently of the network or server acknowledgement.

## HTTP contract

Send JSON as `Content-Type: application/json`. Authenticated native calls use
`Authorization: Bearer <token>`; cookie-only requests do not authenticate native
ingestion. Login is the only route receiving a password. Login responses and
session cookies contain credentials; avoid logging them. Upload batches must
contain the user ID and token in their respective fields/header, with no
password or token in `data` or `permissions`.

| Route | Request | Response |
| --- | --- | --- |
| `POST /api/native/login` | `{email,password}` | `{user:{id,email,name,role,created},token,expires_at}`; also sets the existing HttpOnly session cookie |
| `GET /api/native/me` | Bearer token | `{user:{id,email,name,role,created}}` |
| `POST /api/native/devices` | `{device_id,platform:"android",name,sync_interval_minutes:60,consent_version:"1",history_days:30}` | `{device_id}` |
| `POST /api/native/batches` | Batch below | `{batch_id,accepted:true,duplicate:false}`; exact retries return `duplicate:true` |
| `GET /api/native/status` | Bearer token | `{devices,last_batches,retention}`; metadata for only the signed-in account |
| `POST /api/native/web-session` | Bearer token | `{ok:true}` and an HttpOnly cookie for the same session |
| `GET /api/native/dashboard` | Bearer header from an older native client, or existing session cookie | Sets the same session cookie and redirects to `/` with HTTP 303 |
| `POST /api/native/logout` | Bearer token | `{ok:true}`; revokes the provided native session |

`expires_at` is an ISO 8601 UTC timestamp. `device_id`, `batch_id` and `user_id`
must be UUIDs. Re-registering an owned device updates its consent/settings;
another account cannot claim its identifier. Generate a new device identifier
when switching accounts. There are at most 20 devices per account, and initial
`history_days` is an integer from 1 to 365. These settings cannot override
Android's actual history availability or granted permissions.

```json
{
  "schema_version": 1,
  "batch_id": "65c1b1fb-658b-4353-a1b4-1f7ca1ec8e7d",
  "device_id": "f0d34ebd-c645-4d65-aa18-76b2eed258ac",
  "user_id": "153187a9-e51d-4a1e-8d51-ffecae04bd2b",
  "window": {"start_ms": 1790895600000, "end_ms": 1790899200000},
  "collected_at_ms": 1790899200000,
  "permissions": {"usage_access": true},
  "data": {
    "usage": {
      "status": "available",
      "complete": true,
      "records": [{"package_name": "example.app", "foreground_ms": 120000}]
    }
  }
}
```

Times are Unix milliseconds. Collection time must be at or after the window
end, and future times permit at most 15 minutes of device/server clock drift.
Each window must be positive and no longer than 366 days. Initial history and
large sources should be split into smaller batches. Android collectors supply
the source-specific `data` records; the server stores the submitted JSON and
does not imply that denied, unavailable or empty sources were collected.

The server rejects requests over 8 MiB before JSON parsing, including streaming
bodies without `Content-Length`. Other native request bodies are limited to
16 KiB. Compressed bodies, duplicate JSON keys, non-finite numbers, unsupported
schema versions, invalid windows, excessive nesting and credential fields are
rejected. Validation responses omit submitted passwords and health values.
Changing an already accepted batch while reusing its identifier returns 409;
field order and insignificant JSON whitespace do not affect duplicate detection.
Expired, logged-out or password-reset sessions return 401. Cross-user batch
identities return 403; unowned/unregistered devices return 404.

## Storage and verification

New uploads prune raw JSON older than 90 days and enforce a 256 MiB cap per
account. Older payloads are removed first when the cap is reached, while compact
receipts retain their hashes to recognize retries. The same transaction prunes
receipts older than 365 days and enforces a 50,000-receipt cap per account.
Time-based pruning runs when that account uploads a new batch; an inactive
account's existing rows are retained until its next upload or account deletion.
These caps bound stored data; receipts pruned after those limits no
longer provide duplicate detection. Status returns metadata for the last 20
receipts, not private exported records. The raw exports remain in SQLite and
are not yet consumed by the planner or displayed as charts. Source records can
overlap across batches: the phone refreshes recent health/calendar history to
capture delayed inserts and updates. Future aggregations must identify Health
Connect records by their record ID and last-modified time, and calendar instances
by event ID and instance start, rather than sum repeated raw rows.

```sh
.venv/bin/python -m pytest data_sync/native_app/server/tests -q
```

Integration tests exercise the existing auth and account deletion behavior,
session revocation, tenant isolation, repeated device registration, batch
idempotency, conflicting retries, request validation, nested credentials,
declared/streaming size limits, raw-data retention and WebView cookie bootstrap.
The WebView test expects the existing dashboard's `dist/index.html`, created by
`npm run build` above.
