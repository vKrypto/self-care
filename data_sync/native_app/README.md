# Forma Android Data Connect

Phase 3 lives entirely in this folder. It contains an Android React Native app,
shared TypeScript screens, Kotlin collectors and persistent upload workers, and a
companion entry point for the existing Forma server. Existing web and backend
source files do not need modification. iOS is deferred.

## Run the server

From the repository root, install the existing backend dependencies if needed,
build the current web dashboard, then start the companion API:

```bash
.venv/bin/python -m pip install -r data_sync/native_app/server/requirements.txt
npm run build
.venv/bin/python -m data_sync.native_app.server
```

This serves the existing API, `/api/native/*`, and the built dashboard at one
origin, by default `http://0.0.0.0:8000`. Use existing Forma accounts. The usual
`FORMA_DATA_DIR`, `ADMIN_EMAIL`, and other backend environment settings still
apply. Set `NATIVE_PORT` / `NATIVE_HOST` to change the listener; set
`NATIVE_FRONTEND_DIST` for a different built frontend directory. Build the web
dashboard before starting the companion process so its assets are mounted.

In production, expose this server over HTTPS and set `COOKIE_SECURE=true`.
The app accepts HTTP only in debug builds. A phone needs your server's reachable
LAN address or HTTPS hostname; `localhost` on the phone refers to the phone.
Android emulators can reach a local host server at `http://10.0.2.2:8000`.

## Run Android

Use Node 22.11+, JDK 17, Android SDK platform 36 / build tools 36.0.0, and NDK
27.1.12297006. The app supports Android 8+ (API 26); Health Connect needs a
supported device/provider, normally Android 9+ and the Health Connect app on
Android 13 and earlier. Android 14+ integrates Health Connect into the system.

```bash
cd data_sync/native_app
npm ci
npm start
```

In another terminal in this folder, with an emulator or USB debugging device:

```bash
npm run android
```

Configure Android Studio or `ANDROID_HOME` with your SDK location. The checked-in
Gradle wrapper uses 8.14.3 with React Native 0.84.1, avoiding an upstream Gradle 9
incompatibility in that release's toolchain resolver. Debug signing uses Android's generated
debug key. Release builds require your deployment signing configuration; the
project does not ship a release private key.

### Install without Metro

Create the self-contained preview APK:

```bash
cd data_sync/native_app
npm ci
npm run build:apk
adb install -r android/app/build/outputs/apk/preview/app-preview.apk
```

This preview contains the JavaScript bundle and Hermes bytecode and runs without
the Metro development server. It uses Android's local debug signing key and a
separate `com.forma.datasync.preview` application ID, so it can be installed
alongside the development app. Preview requires an HTTPS server URL. Use the
development build above for a local HTTP server. The preview key is for testing;
configure your own signing key when distributing a release.

## Flow

1. Sign in with email, password and the companion server origin. The origin is
   needed before login so credentials reach the intended server.
2. Onboarding confirms the upload server, requested history (1–365 days), source
   permissions, background settings, and explicit data-upload consent. Usage
   Access is required; other sources can be granted individually or denied.
3. Submit registers the device, starts history catch-up, and schedules a unique
   hourly Android WorkManager job with a network requirement. Existing signed-in,
   onboarded users go directly to the dashboard on reopening.
4. The dashboard opens the current Forma web app inside a WebView using the same
   HttpOnly session cookie. Native controls show last upload/error and provide
   Sync now, Pause/Resume, and Data settings. Sign out cancels work and clears the
   session, pending local batch and WebView cookies.

The schedule runs **on the Android device**, because a server cron cannot wake a
phone and access its local usage APIs. WorkManager persists jobs across process
death and reboot. Hourly execution is best effort: Doze, network outages, OEM
battery restrictions and force-stop affect timing. Force-stopped applications
need reopening before Android resumes their jobs.

## Sources and permissions

| Source | Exported information | Required access / limits |
| --- | --- | --- |
| Usage summaries | Package foreground/visible/service time and retained aggregate timestamps | Usage Access; Android bucket boundaries may overlap the requested interval |
| Usage events | Chronological activity transitions, screen interactive/noninteractive, keyguard/unlock and other exposed numeric event types | Usage Access; detailed events are usually retained for only a few days |
| Screen event summaries | Android daily event counts and durations | Usage Access; API 28+ |
| Network usage | Per-UID Wi-Fi/mobile received/sent bytes and packets, metering/roaming/state and visible package names | Usage Access; unavailable transports are reported independently |
| App metadata | Visible packages, versions, labels, install/update timestamps and launchability | Android package visibility restrictions; this is not guaranteed to list every installed package |
| Device snapshot | Device/OS, battery/charging, memory/storage, timezone, connectivity and power state | Public APIs; snapshot at capture time, not historical device state |
| Calendar | Accessible calendar occurrences, titles, times, descriptions, location and calendar IDs | `READ_CALENDAR`; only calendars exposed by the device's provider |
| Location snapshot | Available provider location, timestamp, accuracy and exposed coordinate fields | Fine/coarse location; background collection separately requires background location on Android 10+; cached location may be old |
| Activity snapshot | A brief step-counter reading with sensor timestamp and steps since reboot | Activity recognition on Android 10+ and a supported sensor; not a reconstructed activity timeline |
| Health Connect | All 41 record classes supported by the pinned Health Connect SDK, including nested metadata, units, samples and stages | Each record's read permission; optional background/history permissions and feature gates |

Health categories include steps and cadence, wheelchair pushes, distance,
elevation/floors, exercise and cycling cadence, speed/power/VO2 max, energy and
basal metabolic rate, sleep stages, hydration/nutrition, heart rate/variability,
resting heart rate, respiration, blood pressure/glucose/oxygen, body and basal
temperature, height/weight/fat/water/bone/lean mass, menstruation, cervical mucus,
ovulation and sexual activity. Skin temperature, planned exercise and mindfulness
are requested only when the provider supports them.

Health Connect contains records shared by participating apps, and can be empty.
Without older-history permission, Android limits historical access (normally to
30 days before the first grant). Without background health-read permission, the
worker reports that restriction and other sources still sync; use **Sync now**
while the app is open to upload granted health records. Third-party exercise
routes may require per-session foreground consent and are not automatically
unlocked by the exercise read permission. Multiple health origins can report
overlapping measurements; raw exports preserve source IDs for deduplication.

Permission buttons open Android's real dialogs/settings. Background location is
requested separately after foreground location. Battery settings let the user
review restrictions. Notifications are requested to support sync status/error
messages. The Health Connect rationale activity explains the data categories and
recipient. Read permissions are used; the app does not modify health records.

Android does not expose complete lifetime history, other apps' private storage,
private messages, passwords or retrospective continuous sensor/GPS recordings
through these APIs. Source status and timestamps describe these limits instead
of manufacturing missing history.

## Upload contract and storage

The password is sent only to `/api/native/login`. Subsequent requests use a
30-day bearer session from the existing account system. Account identity comes
from that session and is checked against the batch's `user_id`; device ownership
is checked separately. Passwords are never persisted or included in data batches.

Android Keystore AES-GCM protects the local session state and persisted retry
batch. Backups are disabled. A batch has a stable random UUID and is saved before
upload; retries reuse it. A source's cursor advances only after the server
acknowledges that exact batch. Denied/incomplete sources keep their own cursor.
Source collection is paginated and bounded; overflow is reported rather than
silently discarded. Oversized exports shrink their time window, and an
irreducibly oversized source is reported with an error while other sources
continue. Its cursor remains intact for another attempt.

Health and calendar records receive a rolling 48-hour reread, at most once per
hour, to capture recent records that arrive late or change. Downstream consumers
should deduplicate by origin and source record ID. Changes or deletions to older
records outside that lookback are not automatically synchronized; a full Health
Connect change-token feed is a future extension.

```json
{
  "schema_version": 1,
  "batch_id": "<UUID>",
  "device_id": "<UUID>",
  "user_id": "<authenticated user UUID>",
  "window": {"start_ms": 0, "end_ms": 1},
  "collected_at_ms": 1,
  "permissions": {},
  "data": {
    "usage_events": {"status": "ok", "complete": true, "records": []}
  }
}
```

Timestamps are Unix milliseconds. History uses at most one-day collection windows;
snapshot sections include their actual capture time. Source formats retain
Android units and origin metadata rather than assuming every number is a daily
total. The server validates JSON, window order, device clocks, identities and
request size (8 MiB), rejects credential fields, and accepts identical repeated
UUID batches idempotently. Conflicting contents for an existing UUID are rejected.
Expired sessions stop uploads and require another login.

| Endpoint | Purpose |
| --- | --- |
| `POST /api/native/login` | Existing-account login → public user, bearer token and expiry |
| `GET /api/native/me` | Validate session / current user |
| `POST /api/native/devices` | Register or update an owned Android device and consent |
| `POST /api/native/batches` | Validate and acknowledge an owned export batch |
| `GET /api/native/status` | Owned devices and last 20 batch receipts |
| `GET /api/native/dashboard` | Bearer-to-HttpOnly-cookie dashboard bootstrap |
| `POST /api/native/web-session` | Optional cookie bootstrap for other native clients |
| `POST /api/native/logout` | Revoke the current session |

Native records live in `native_devices` and `native_batches` in the existing
Forma SQLite database. Raw batches are retained for up to 90 days / 256 MiB per
user; receipts for up to 365 days / 50,000 batches per user. Pruning runs on
ingestion. Existing account deletion cascades to these rows. Grant revocation or
sign-out stops future access and clears local state, but does not delete exports
already stored on the server. Server-side disk encryption and access controls
remain the deployment operator's responsibility.

## Verify

```bash
# From this folder
npm run typecheck
npm test
npm run bundle:android
cd android
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest

# From repository root
.venv/bin/python -m pytest data_sync/native_app/server/tests -q
```

Device validation should cover a real Android phone with populated usage and
Health Connect data: deny then grant a category, grant health history later,
turn the network off/on, reopen after process death/reboot, check an expired
session, pause/resume and sign out. Check that acknowledged receipts appear at
`/api/native/status` and that the existing authenticated dashboard opens. There
is no iOS build or native chart/dashboard implementation in this phase.

Implementation references: [React Native environment setup](https://reactnative.dev/docs/set-up-your-environment),
[UsageStatsManager](https://developer.android.com/reference/android/app/usage/UsageStatsManager),
[NetworkStatsManager](https://developer.android.com/reference/android/app/usage/NetworkStatsManager),
[persistent background work](https://developer.android.com/develop/background-work/background-tasks/persistent),
and [Health Connect reads, background and history permissions](https://developer.android.com/health-and-fitness/health-connect/read-data).
