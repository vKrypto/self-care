# Forma Android Data Connect

The Phase 3 collector lives in this folder. It contains an Android React Native app,
shared TypeScript screens, Kotlin collectors and persistent collection/upload workers, and a
companion entry point for the existing Forma server. Phase 4 adds account-owned
device history and Digital wellbeing to the web dashboard, using the collector's
existing export contract. iOS is deferred.

Version `0.3.7` uses two compact setup cards: **Start collecting data**, then
optional **Sync to server**. Primary actions request Android permissions in
sequence. Permission rows show status and an action; info icons explain the
exact data and a short reason for access. Login and local collection remain
independent of upload consent.

The app also recovers a device removed from the website. It stops uploads for
that connection and keeps collecting locally. Review upload consent to create
a new connection without clearing the app's storage or local history.
Queued data assigned to the removed connection remains local; fresh and
unassigned collections can upload through the new connection.

For tool installation, platform-specific build commands, and APK installation,
see the [Android APK build guide](../README.md). The generated standalone test
APK is available at [`../apk/forma-data-sync-preview.apk`](../apk/forma-data-sync-preview.apk).
Version `0.3.7` also exports smaller APKs for each architecture. See
[Choose a smaller APK](../README.md#choose-a-smaller-apk). The last measured
`0.3.6` downloads were 6.69 MB for ARM64 LAN, 6.80 MB for the connected x86_64
test device, and 19.07 MB for universal LAN, reduced from 51.98 MB.

## Run the server

From the repository root, install the existing backend dependencies if needed,
build the current web dashboard, then start the companion API:

```bash
.venv/bin/python -m pip install -r data_sync/native_app/server/requirements.txt
npm run build
.venv/bin/python -m data_sync.native_app.server
```

This serves the existing API, `/api/native/*`, `/api/devices*`, `/api/wellbeing`,
and the built dashboard. It listens on `0.0.0.0:8000` by default; use a reachable
LAN address or HTTPS hostname on the phone. Use existing Forma accounts. The usual
`FORMA_DATA_DIR`, `ADMIN_EMAIL`, and other backend environment settings still
apply. Set `NATIVE_PORT` / `NATIVE_HOST` to change the listener; set
`NATIVE_FRONTEND_DIST` for a different built frontend directory. Build the web
dashboard before starting the companion process so its assets are mounted.
The regular `.venv/bin/python -m backend.server` entry point installs the same
native/browser APIs, with its existing loopback listener. Run one server process.
For a reverse proxy, `NATIVE_PUBLIC_URL` can set the origin shown by **Connect
device**; see the [server guide](server/README.md).

In production, expose this server over HTTPS and set `COOKIE_SECURE=true`.
The app accepts HTTP in debug builds and the explicit LAN test variant. A phone needs your server's reachable
LAN address or HTTPS hostname; `localhost` on the phone refers to the phone.
Android emulators can reach a local host server at `http://10.0.2.2:8000`
using debug or LAN builds.

### Connect a phone on the same LAN

Start the companion API above, then open
`http://<computer-LAN-IP>:8000/api/health` from the phone's browser. Use the
computer's active Ethernet/Wi-Fi IPv4 address; `localhost` on the phone points
back to the phone. If the browser cannot connect, check the computer's firewall
and router client isolation.

For local HTTP without Metro, build and install the **LAN preview**:

```bash
# From data_sync/native_app
npm run build:apk:lan
adb install -r ../apk/forma-data-sync-lan-preview.apk
```

Enter `http://<computer-LAN-IP>:8000` as the server origin, then your existing
Forma email and password. Login remains optional for collection. This variant
accepts HTTP only to literal private IPv4 addresses in `10.0.0.0/8`,
`172.16.0.0/12`, and `192.168.0.0/16`; public addresses and HTTP hostnames are
rejected. HTTP leaves credentials and uploads unencrypted in transit, so this
build is for testing on a trusted LAN. Keep `COOKIE_SECURE=false` for HTTP.
The normal preview and release still require HTTPS.

The LAN build has the same application ID and signing key as preview, preserving
local records when installed as an update. The generated download is
[`../apk/forma-data-sync-lan-preview.apk`](../apk/forma-data-sync-lan-preview.apk).
For Windows, run `gradlew.bat assembleLan` and
`node ..\scripts\export-apks.mjs lan` from the `android` folder.

At the user's request, this LAN test build prefills `http://192.168.100.8:8000`,
`admin@example.com`, and `admin123`. These editable test defaults are compiled
only into the LAN variant; normal preview/release defaults remain empty.
The app header identifies the build and version. If you see **HTTPS preview**
or an HTTPS-only error for your local URL, update it with the **LAN preview APK**.
An existing signed-in session keeps its own server/account and clears the test
password. Login still requires tapping **Sign in**; enable uploads separately
with upload consent and **Sync to server** in the second setup card.

## Run Android

Use Node 22.11+, JDK 17, Android SDK platform 36 / build tools 36.0.0, NDK
27.1.12297006, and CMake 3.22.1. The app supports Android 8+ (API 26); Health
Connect needs a supported device/provider, normally Android 9+ and the Health
Connect app on Android 13 and earlier. Android 14+ integrates Health Connect
into the system.

From the repository root:

```bash
cd data_sync/native_app
npm ci
npm start
```

In another terminal, enter `data_sync/native_app` and run the following with an
emulator or USB debugging device connected:

```bash
npm run android
```

Configure Android Studio or `ANDROID_HOME` with your SDK location. The checked-in
Gradle wrapper uses 8.14.3 with React Native 0.84.1, avoiding an upstream Gradle 9
incompatibility in that release's toolchain resolver. Debug signing uses Android's generated
debug key. Release builds require your deployment signing configuration; the
project does not ship a release private key.

### Install without Metro

Create the self-contained preview APK, starting from the repository root:

```bash
cd data_sync/native_app
npm ci
npm run build:apk
adb install -r ../apk/forma-data-sync-preview.apk
```

If already in this folder, omit `cd data_sync/native_app`. Windows users should
run `gradlew.bat assemblePreview` from the `android` folder; see the
[APK build guide](../README.md#build-on-windows) for the PowerShell commands.

This preview contains the JavaScript bundle and Hermes bytecode and runs without
the Metro development server. It uses Android's local debug signing key and a
separate `com.forma.datasync.preview` application ID, so it can be installed
alongside the development app. Local collection needs no server. Connecting the
preview app requires an HTTPS server URL; use the LAN preview or the development
build above for a local HTTP server. The preview key is for testing;
configure your own signing key when distributing a release.

The build scripts export all APKs and checksums into `data_sync/apk`. Prefer the
file matching your device's first ABI from `adb shell getprop ro.product.cpu.abilist`:
`forma-data-sync-preview-arm64-v8a.apk`, `-armeabi-v7a.apk`, `-x86.apk`, or
`-x86_64.apk`. LAN builds use the prefix `forma-data-sync-lan-preview`.
The unsuffixed filename is universal. Install the matching smaller APK with
`adb install -r` or open it on the phone and choose **Update**. An update with the
same app ID and signing key preserves the session, granted permissions,
collection/upload settings, local queue, and archived history. Do not uninstall
the existing app or clear its storage to apply an update.

Release, preview, and LAN builds enable R8 optimization and resource shrinking;
native libraries are compressed in APKs and extracted during installation.
Starting with `0.3.5`, React Native's
[`enableBundleCompression` setting](https://reactnative.dev/docs/0.84/react-native-gradle-plugin#enablebundlecompression) also
compresses the Hermes bytecode bundle. Android decompresses it into memory at
startup rather than directly memory-mapping the APK asset, trading some startup
work for a smaller download. Collection sources, background workers, upload
logic, sync history, and the external-browser website link remain available.
Health Connect record and unit public members retain their names because the
collector serializes them through reflection. WorkManager and React Native
consumer rules preserve worker constructors and native bridge entry points.
Use `npm run export:apk` / `npm run export:apk:lan` to refresh exported APKs
after a direct Gradle build.

## Flow

1. In **Start collecting data**, select requested history (1–365 days), accept
   explicit local-collection consent, and press the primary collection action.
   No account or server is required. The app opens Android permission dialogs
   and settings sequentially, refreshing status when returning to the app.
2. **Usage Access** is required for app usage, screen events, and network usage.
   Enable Forma on the Android Usage Access screen and return. If it remains
   denied, setup displays an actionable message and lets you retry. Other
   sources are optional; denied or unavailable sources keep their status while
   collection proceeds with granted sources. Each compact permission row has
   a status, action, and info icon explaining the exact data and why it is read.
3. Collection stores permitted data in an encrypted local queue and schedules a
   unique hourly Android WorkManager job with no network requirement. Local
   onboarding survives app restarts. The dashboard shows collection status,
   queue size, **Collect now**, and separate collection pause/resume controls.
4. **Sync to server** is the second, optional setup card. Enter the server URL,
   sign in, accept separate upload consent, and press **Sync to server**. The
   action runs permission checks before registering the device and enabling a
   separate hourly upload worker requiring a network. Signing in alone or
   starting local collection does not enable uploads. Enabling uploads sends
   eligible queued records and newly collected data. The connected
   dashboard provides upload status, **Sync now**, upload pause/resume and
   connection settings. **Open website** launches the configured server origin
   in the phone's external browser, with no token or password in the URL. The
   browser may ask you to sign in; its session is independent of the collector.
5. Sign out in the app removes its native session and cancels uploads.
   Local collection and queued records remain. Native session expiry also
   stops uploads. Browser sign-out affects only the browser session; use the
   app's sign-out or upload pause control to stop the collector uploading.
   Pause collection explicitly to stop collecting.
6. Open **Sync history** from either dashboard. Tap a collection job to review
   its sources, records, collected time windows and upload receipts. This works
   locally without signing in; successfully uploaded records remain viewable
   within the local history retention limits described below.
7. Removing a device in the website's **Connected devices** tab disconnects that
   upload identity. Version `0.3.7` recognizes removal from the server's device
   list or a rejected upload. **Reconnect & review upload consent** opens settings;
   approving upload consent and pressing **Sync to server** registers a new identity.
   A failed registration keeps the prior identity and data. Signing in, restarting,
   or pressing **Resume uploads** does not bypass removal. Batches for the previous
   connection remain in **Sync history**, use local queue storage, and are counted
   separately from batches eligible for the current connection.

The schedule runs **on the Android device**, because a server cron cannot wake a
phone and access its local usage APIs. WorkManager persists jobs across process
death and reboot. Hourly execution is best effort: Doze, network outages, OEM
battery restrictions and force-stop affect timing. Force-stopped applications
need reopening before Android resumes their jobs.

## Sync history and collection details

**Sync history** lists collection runs, including manual collection and scheduled
background work. Each row shows when collection ran, its outcome, record/source
counts, and how many batches are queued or confirmed uploaded. A collection run
can contain several batches while catching up historical data.

Tap a job to review collected sources and their record counts, denied or
unavailable sources, collection errors, individual batch time windows, and
server upload status. Tap a source to inspect its actual structured records and
source metadata. Lists and records are paginated so large exports can be opened
without loading all device history at once. **Synced** means the server
acknowledged the exact batch UUID; local collection alone is shown as queued.

Queued data uses the existing encrypted 64 MiB outbox and remains until the
server acknowledges it. A separate encrypted archive retains acknowledged
records for up to **30 days / 64 MiB**, pruning the oldest synced copies first.
The latest **500 finished job summaries**, plus all jobs still containing queued
batches, remain visible. A summary can outlive its raw records; the detail screen
explicitly reports when those records are no longer retained locally. These
limits do not change server retention.

Existing unsent batches become history entries when upgrading. Uploads deleted
from the local queue before this history feature was installed cannot be
reconstructed on the device. All new collections are tracked. Signing out
preserves device history; clearing app storage or uninstalling deletes it.

## Phase 4: Review synced records on the website

Keep the existing collector installed; Phase 4 needs a web build/server restart,
with no APK rebuild or reinstall. Sign in on the phone with the same Forma
account/server, accept upload consent, and use **Sync now**. **Open website**
opens the browser, where you sign in independently of the native upload session.

The web **Connected devices** tab lists account devices, first/latest known
sync dates, and unique successful sync counts. **Connect device** provides the
server address, account email, compatible APK downloads and setup instructions.
Select a device to review all retained receipts, every exported source and its
original metadata, paginated records, and full JSON downloads. Confirmed removal
deletes its server history and derived daily records and prevents uploads for
that device identifier. Local phone records remain under local storage controls.

**Digital wellbeing** organizes synced metrics into daily and seven-day calendar
week views, for one device or the account's devices. It includes observed app
foreground/screen time and unlocks, network estimates, and supported Health
Connect measurements. Missing or denied sources retain their status, and
overlapping exports are deduplicated. Health metrics describe a selected
reporting origin instead of summing overlapping phone/wearable origins or
claiming Health Connect priority totals. Other retained sources remain fully
inspectable in device history. Device counters and daily summaries have their
own retention; earlier raw records that already expired cannot be backfilled.
See the [server README](server/README.md#storage-retention-and-removal) for limits,
backfill progress, browser APIs, and revocation behavior.

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
worker reports that restriction and other sources still collect; use **Collect now**
while the app is open to collect granted health records. Connected users can also
use **Sync now** to collect and upload. Third-party exercise
routes may require per-session foreground consent and are not automatically
unlocked by the exercise read permission. Multiple health origins can report
overlapping measurements; raw exports preserve source IDs for deduplication.

The primary collection action and explicit **Sync to server** action open
Android's real dialogs/settings sequentially, rather than requiring every
permission row to be opened manually. Already granted permissions are skipped.
Usage Access is a special-access setting: Android requires the user to enable
Forma there and return to the app; a runtime permission popup cannot grant it.
Compact rows allow a denied permission to be retried later, and their info icons
describe the exact data and purpose.

Optional requests cover calendar, foreground location, activity recognition,
notifications, supported Health Connect read categories, background location,
and battery restrictions. Background location is requested separately after
foreground location. Battery settings let the user review restrictions.
Notifications support sync status/error messages. If the Health Connect provider
is unavailable, the automatic sequence skips it; its row provides an
install/settings link. The Health Connect rationale activity explains the data
categories and recipient. Read permissions are used; the app does not modify
health records. Denying an optional permission skips the affected source while
other granted sources continue collecting.

Android does not expose complete lifetime history, other apps' private storage,
private messages, passwords or retrospective continuous sensor/GPS recordings
through these APIs. Source status and timestamps describe these limits instead
of manufacturing missing history.

## Upload contract and storage

The password is sent only to `/api/native/login`. Subsequent requests use a
30-day bearer session from the existing account system. Account identity comes
from that session and is checked against the batch's `user_id`; device ownership
is checked separately. Passwords are never persisted or included in data batches.

Android Keystore AES-GCM protects the local session state and durable batch
queue. Backups are disabled. A batch has a stable random UUID and is saved before
its source cursors advance, so collection can continue without an account,
network, or upload acknowledgement. Denied/incomplete sources keep their own
cursor. An acknowledged batch is removed from the local queue; retries reuse its
exact UUID and payload.

The encrypted queue is bounded to **64 MiB**. If it fills, collection reports an
error and waits for space instead of silently evicting unuploaded records.
Connecting and uploading frees space. Clearing Android app storage or
uninstalling deletes the local queue and history. Acknowledged records move to
the separate bounded history archive so the collection details can still be
viewed after syncing.

Anonymous batches are assigned to the connected account when first selected for
upload, after connection consent. That assignment persists across retries.
Already assigned batches remain bound to their original server, account and
device; switching accounts cannot send them to the new account. Reconnect the
original account to upload those records. Local collection history and cursors
survive account switching.

Pause uploads and sign-out cancel active upload requests without stopping
collection. Collection has its own pause control. Before each upload, the app
checks the current session, account, device and permissions again, including the
allowed health history boundary. Queued records exceeding newly revoked access
remain encrypted locally and cannot upload while that access is unavailable.
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
Expired sessions stop uploads and require another login; local collection continues.

| Endpoint | Purpose |
| --- | --- |
| `POST /api/native/login` | Existing-account login → public user, bearer token and expiry |
| `GET /api/native/me` | Validate session / current user |
| `POST /api/native/devices` | Register or update an owned Android device and consent |
| `POST /api/native/batches` | Validate and acknowledge an owned export batch |
| `GET /api/native/status` | Owned devices and last 20 batch receipts |
| `GET /api/native/dashboard` | Cookie bootstrap retained for older native clients |
| `POST /api/native/web-session` | Optional cookie bootstrap for other native clients |
| `POST /api/native/logout` | Revoke the current session |

Native records live in `native_devices` and `native_batches` in the existing
Forma SQLite database. Raw batches are retained for up to 90 days / 256 MiB per
user; receipts for up to 365 days / 50,000 batches per user. Pruning runs on
ingestion. Durable device counters and retry hashes survive receipt pruning;
legacy counters are backfilled from retained receipts and disclose unrecoverable
older history. Derived wellbeing facts have separate bounded 365-day retention.
The web **Remove device** action deletes its server history, including derived
records, and blocks uploads for the old identifier. Existing account deletion
cascades through these rows and removes revocation tombstones. Grant revocation
blocks affected future reads and queued uploads. Signing out stops uploads and
removes the local session while collection and queued records remain. Neither
deletes exports already stored on the server. Server-side disk encryption and
access controls remain the deployment operator's responsibility.

## Verify

```bash
# From this folder
npm run typecheck
npm test
npm run bundle:android
cd android
./gradlew :app:compileDebugKotlin :app:testDebugUnitTest
./gradlew :app:lintPreview :app:assemblePreview

# From repository root
.venv/bin/python -m pytest data_sync/native_app/server/tests -q
```

The Android 15 (API 35) smoke check covered updating the installed preview APK,
skipping login, granting Usage Access, collecting without an account/server,
keeping the local queue after an app restart, and separate collection
pause/resume. Sync history checks covered importing existing queued batches,
manual and background job entries, opening actual app metadata records,
loading additional record pages, denied-source summaries, Android Back
navigation, and preserving history after a process restart. The current checks
passed 77 Android unit tests and 17 TypeScript tests; Android lint reported no
errors. Automated checks cover archive retention and acknowledgements, queue
recovery, ownership, permissions, and lifecycle routing. A real server upload
was not part of the initial history device check; server ingestion has separate
tests. The later LAN check verified updating from `0.3.0-preview` to
`0.3.2-lan-preview`, preserving nine queued batches, showing the LAN build label,
and a live signed-in device with successful uploads confirmed by server batch
receipts. The LAN variant's private-address validation, standalone bundle,
signing compatibility, test defaults, and variant-specific transport settings
were also checked. Updating to `0.3.3-lan-preview` retained the active collection
and upload settings; the native dashboard had no embedded website, and tapping
**Open website** launched the device's separate browser at the configured LAN
address. Updating to the optimized `0.3.4-lan-preview` x86_64 APK preserved the
signed-in session, collection/upload settings, and archived history. The device
check opened retained raw records, collected a new local batch, uploaded queued
batches to the LAN server, and displayed their server acknowledgements in
collection details. Both optimized preview variants passed Android lint with no
errors. All ten exported APKs passed signature, checksum, architecture, and
Hermes bundle checks; the signing certificate matches the earlier installed
preview. The minified DEX audit confirmed that Health Connect public serializer
members, native bridge methods, and persisted worker class names remain intact.

Updating to `0.3.5-lan-preview` preserved the signed-in session and enabled
collection/upload settings across three successful cold launches. The device
check collected a new manual job, uploaded queued data to the LAN server, and
showed the server's exact batch acknowledgement in collection details. Archived
app metadata records decrypted and rendered, and an empty usage source showed
its metadata and no-record explanation. **Open website** launched the external
browser; returning retained the active native session and collection/upload
settings. No app crash appeared in the device's crash log. All ten exported APKs
passed signature, checksum, architecture, and compression checks;
their compressed bundles decompress to exactly the same Hermes bytecode as
`0.3.4`. Both preview variants passed Android lint with no errors. Cold-launch
checks measured Android activity launch, not complete React Native startup.
Health Connect categories still need validation on a phone with populated records.

Version `0.3.6` passed 84 Kotlin/JVM tests covering removal, per-account identity
isolation, registration acknowledgment, and retained queue ownership. TypeScript
checks and tests passed, and both APK variants passed Android lint with no errors.
All ten exported APKs passed checksum, signing-certificate, ABI, version, and
compressed Hermes bundle checks. Updating the connected test device preserved
its signed-in session, enabled collection/uploads, and all 24 local collection
jobs; retained collection details and server acknowledgments remained readable.
The startup check confirmed its existing device through the server's authenticated
device list. Removed-device recovery was tested with isolated state and queue
fixtures; the active live device and its server history were preserved.

Further device validation should cover a real Android phone with populated usage and
Health Connect data: skip login, complete local onboarding, collect in airplane
mode, reopen after process death/reboot, connect later and upload the queue,
deny then grant a category, grant health history later, check an expired
session, pause collection separately from uploads, sign out and verify continued
local collection, and switch accounts without uploading another account's queue.
Check that acknowledged receipts appear at
`/api/native/status` and that **Open website** launches the configured server
in a browser. Phase 4 web charts and record views consume those same exports;
the collector continues to use its native collection/history screens and external
browser link. iOS is deferred.

Implementation references: [React Native environment setup](https://reactnative.dev/docs/set-up-your-environment),
[UsageStatsManager](https://developer.android.com/reference/android/app/usage/UsageStatsManager),
[NetworkStatsManager](https://developer.android.com/reference/android/app/usage/NetworkStatsManager),
[persistent background work](https://developer.android.com/develop/background-work/background-tasks/persistent),
and [Health Connect reads, background and history permissions](https://developer.android.com/health-and-fitness/health-connect/read-data).
