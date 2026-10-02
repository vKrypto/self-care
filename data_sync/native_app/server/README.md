# Android ingestion and connected-device server

Both Forma server entry points install Android ingestion, connected-device
history, and Digital wellbeing APIs. They use the existing accounts, salted
password hashes and hashed 30-day sessions. Device registrations, exports,
durable sync counters/retry hashes, and derived daily records live in the same
SQLite database. Existing account deletion also deletes its device data.

From the repository root:

```sh
.venv/bin/pip install -r data_sync/native_app/server/requirements.txt
npm run build
.venv/bin/python -m data_sync.native_app.server
```

Run one server process. The companion provides the existing API, MCP transport,
built dashboard, and device routes on one origin. Its default bind address is
`0.0.0.0:8000`; use `NATIVE_HOST` and
`NATIVE_PORT` to override it. `NATIVE_FRONTEND_DIST` can point at another built
dashboard directory. The underlying backend still reads the repository `.env`
and honors `FORMA_DATA_DIR`, `ADMIN_EMAIL`, `ADMIN_PASSWORD`, `COOKIE_SECURE`,
and the other existing configuration.

`.venv/bin/python -m backend.server` installs the same native/browser routes and
database initialization, with its existing loopback listener. The extension is
installed once regardless of which entry point imports it. Build the frontend
before starting either process so its assets can be mounted.

For a reverse proxy, set `NATIVE_PUBLIC_URL=https://forma.example.com` to control
the origin advertised by **Connect device**. It must contain only an HTTP/HTTPS
origin, without a path, query, fragment, or embedded credentials. Without this
override, setup uses the current request origin. `NATIVE_APK_DIR` optionally
points at a directory of generated APKs; its default is `data_sync/apk`.

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
    "usage_events": {
      "status": "ok",
      "complete": true,
      "records": [
        {"timestamp_ms": 1790895600000, "event_type": 1, "package_name": "example.app"},
        {"timestamp_ms": 1790895720000, "event_type": 2, "package_name": "example.app"}
      ]
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

## Browser APIs: Connected devices and Digital wellbeing

These routes authenticate the existing HttpOnly `forma_session` browser cookie,
scope every device/receipt/source query to that account, and return
`Cache-Control: no-store` on data and error responses. A native bearer header alone does not
authenticate them. Foreign-account device IDs return 404. Administrators can
view only devices registered to their own account through these views. The
current collector's external browser has an independent sign-in session; no
native credential is attached to **Open website**.

| Route | Response / parameters |
| --- | --- |
| `GET /api/devices` | `{devices,retention}`; name, platform, registration/update dates, first/latest known sync dates, lifetime `sync_count`, retained receipt/raw counts, and counter-backfill notes |
| `GET /api/devices/setup` | `{server_origin,current_account,platforms,apks,steps,notes,lan_preview}`; APK entries include `filename`, `label`, `abi`, `size_bytes`, and relative `url` |
| `GET /api/devices/apk/{filename}` | Authenticated APK download; only known generated filenames, no symlinks or arbitrary filesystem paths |
| `GET /api/devices/{device_id}` | `{device,retention}` for one owned device |
| `GET /api/devices/{device_id}/batches?limit=25&offset=0` | `{batches,total,limit,offset,has_more,next_offset}`; receipt metadata without full raw payloads; limit 1–100 |
| `GET /api/devices/{device_id}/batches/{batch_id}` | `{batch,raw_retained,permissions,sources}`; each source has key, status, completeness, record count, and original metadata |
| `GET /api/devices/{device_id}/batches/{batch_id}/sources/{source_key}?limit=100&offset=0` | `{source,records,total,limit,offset,has_more,next_offset}`; limit 1–500; preserves unknown sources and nested record fields |
| `GET /api/devices/{device_id}/batches/{batch_id}/raw` | Original accepted JSON envelope; 410 when raw records expired |
| `DELETE /api/devices/{device_id}` | `{deleted:true,device_id}`; removes server device history and derived records, and revokes that account's old device identifier |
| `GET /api/wellbeing` | Daily/weekly metrics, source statuses, device list, timezone, notes, retention, and backfill progress; optional `device_id`, `period=daily\|weekly`, `start_date`, `end_date` |

Browser **Connect device** selects LAN preview downloads only for HTTP origins
using literal RFC1918 private IPv4 addresses. Other origins list regular HTTPS
preview builds. Generated APKs are authenticated downloads, including LAN builds
with their editable development defaults. No account password is returned by
setup. Install an architecture-specific APK or universal fallback as described
in the [APK guide](../../README.md#choose-a-smaller-apk).

Wellbeing dates use `YYYY-MM-DD`; ranges are inclusive, bounded to 1–93 days,
and default to the latest 14 days. The profile timezone takes priority, followed
by a reported device timezone and UTC. Weekly results contain full Monday–Sunday
weeks with seven daily entries, including missing days. Accepted uploads derive
their dated metrics in the same transaction as the receipt. Previously retained
raw exports are backfilled once, processing at most 500 receipts / 16 MiB per
request; `{backfill:{processed_this_request,pending_batches,complete}}` lets the
page report progress and refresh until the import finishes.

Observed app transitions, screen/unlock events, network buckets and supported
Health Connect metrics populate these views. Missing, denied and unavailable
values retain their status instead of becoming invented zeros. Repeated events
and source IDs are deduplicated; Android usage/network buckets are estimates,
not additive hourly deltas. App transitions count observed activity-resumed
events, which can include switching activities in one app. Health values describe
a selected reporting origin, retain origin/method information, and do not claim
to reproduce Health Connect's user-selected origin priority totals. All-device
health views select one device per metric so overlapping wearable exports are
not added together. Full records for every retained source remain accessible
through device history even when no daily metric is derived for that source.

## Storage, retention and removal

New uploads prune raw JSON older than 90 days and enforce a 256 MiB cap per
account. Older payloads are removed first when the cap is reached. The same
transaction prunes
receipts older than 365 days and enforces a 50,000-receipt cap per account.
Time-based pruning runs when that account uploads a new batch; an inactive
account's existing rows are retained until its next upload or account deletion.
The native status endpoint returns metadata for the latest 20 receipts; the
browser device-history endpoint paginates all retained receipts and source data.
Expired raw payloads are explicitly labeled and raw/source requests return 410.

Durable `native_device_stats` counters and `native_seen_batches` batch hashes
survive raw/receipt pruning. Exact retry uploads stay idempotent and do not
increase the unique sync count. New devices track all accepted batches from
registration. On upgrade, legacy device counters and first/latest known dates
are backfilled from retained receipts; `counter_backfilled` and `sync_count_note`
explain that older expired history cannot be reconstructed.

Derived wellbeing history has separate limits: up to **365 days, 100,000 compact
facts / 64 MiB, and 60,000 source observations per device**. Ingestion derives at
most 100,000 records per batch. Pruning occurs while processing device exports;
large histories can be incomplete under these caps. Daily summaries use retained
facts and remain available after their original raw batch expires, within the
derived limits. Source record counts describe the latest read for a day; weekly
source counts are daily observations rather than unique raw record counts.
Health rereads update source IDs/revisions; they do not multiply repeated totals.
The Android app's rolling reread still does not provide a complete deletion/change
feed for older provider records.

Confirmed **Remove device** deletes its registration, raw batches, receipts,
counters, retry ledger, derived facts/day views and source observations through
database cascades. A minimal account-owned SHA-256 identifier tombstone remains
to prevent the old background worker from silently registering again. Later
registration/upload attempts for that account and identifier return **410**,
which the existing collector treats as a stopped upload connection. Reconnection
requires a new device identifier. Local Android data is managed independently;
browser removal does not erase the phone's encrypted queue or local history.
Account deletion also removes the revocation tombstones. Signing out or revoking
a source permission alone does not delete exports already stored on the server.

## Verification

```sh
.venv/bin/python -m pytest data_sync/native_app/server/tests -q
```

Integration tests exercise existing auth/account deletion, session revocation,
tenant isolation, repeated registration, idempotency after receipt expiry,
conflicting retries, request validation, credential rejection, body limits,
retention, cookie bootstrap compatibility, complete source/record pagination,
authenticated APK setup/downloads, populated-history removal and upload
revocation, and both server import orders. Wellbeing tests cover timezone/day
boundaries, calendar weeks, overlapping events/buckets/health origins, SDK units,
denied/missing data, bounded backfills/storage, and malformed future fields.

```sh
npm run build
npx playwright test tests/devices.spec.js
```

The device browser suite exercises both sidebar tabs, connection setup, raw
source history, daily/weekly views, and confirmed removal with isolated data.
The existing cookie-bootstrap test expects `dist/index.html`, created by the
web build above. No APK rebuild is required to use Phase 4 with the current
collector; the native ingestion wire contract is unchanged.
