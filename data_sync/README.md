# Android data sync: build and install an APK

The Android app and companion server live in [`native_app/`](native_app/).
Android `0.3.7` has two setup steps: **Start collecting data** on the phone, then
optional **Sync to server**. Local collection needs no login or server. Sign in
to an existing Forma account and accept upload consent in the second card to
upload queued data in the background. **Open website** opens the current
dashboard in your phone's browser.
See the [app README](native_app/README.md) for collectors, permissions, and the
upload contract.

## Review synced data on the website

Phase 4 adds **Connected devices** and **Digital wellbeing** to the Forma web
sidebar. These views use the existing collector's exports and need a web build
and server restart. Sign in on the phone,
connect it to the same server/account, accept upload consent, and tap **Sync now**.
The app's **Open website** opens your browser, which has its own login session.

**Connected devices** lists your registered devices, first/latest known sync
dates, and successful unique sync counts. **Connect device** provides the server
address and available APKs. Select a device to browse all retained receipts,
source metadata, actual paginated records, and original JSON exports. Removal
requires confirmation and deletes its server history and daily summaries while
blocking uploads for the removed identifier; local phone records remain there.

Android `0.3.7` supports reconnecting the same phone after removal. Update the app
with `adb install -r` or choose **Update** to preserve its local data. When the app
shows **Previous connection removed**, choose **Reconnect & review upload consent**
and approve upload consent in the **Sync to server** card. Earlier batches assigned
to the removed connection stay local; new and unassigned data can upload to the
new connection. A login or restart does not automatically reconnect the phone.

**Digital wellbeing** organizes synced metrics by day and calendar week, with a
device filter, app usage, screen/unlock events, network totals, and available
Health Connect measurements. Weeks include seven days. Missing or denied sources
are labeled rather than replaced with zeros. Android buckets remain estimates;
health values identify a selected reporting origin instead of adding overlapping
origins. Previously retained exports are backfilled in bounded batches, and the
page reports when more history is still being imported. Expired raw exports
cannot be reconstructed, but newly stored daily summaries and lifetime sync
counters have separate retention. See the [server guide](native_app/server/README.md)
for API details, exact storage limits, and connection removal.

Collection checks identify sources needing permission or background access even
when no records were uploaded. Those checks apply to the collection date;
aggregate Android unlock counts are shown as estimates. Retained exports refresh
automatically when their daily projection needs an update, without duplicate
totals or sync counts.

## Install the generated APK

The generated, standalone test build is
[`apk/forma-data-sync-preview.apk`](apk/forma-data-sync-preview.apk).
Its checksum is in
[`apk/forma-data-sync-preview.apk.sha256`](apk/forma-data-sync-preview.apk.sha256).
These are local build artifacts and are excluded from Git.

### Choose a smaller APK

Version `0.3.7` builds a separate APK for each CPU architecture, plus a universal
APK. The `0.3.7` LAN builds have these download sizes:

| Device architecture | APK | Size |
| --- | --- | --- |
| ARM64 (`arm64-v8a`) | [ARM64 LAN APK](apk/forma-data-sync-lan-preview-arm64-v8a.apk) | 6.70 MB |
| 32-bit ARM (`armeabi-v7a`) | [ARM LAN APK](apk/forma-data-sync-lan-preview-armeabi-v7a.apk) | 6.14 MB |
| 64-bit x86 (`x86_64`) | [x86_64 LAN APK](apk/forma-data-sync-lan-preview-x86_64.apk) | 6.80 MB |
| 32-bit x86 (`x86`) | [x86 LAN APK](apk/forma-data-sync-lan-preview-x86.apk) | 7.11 MB |
| All four architectures | [Universal LAN APK](apk/forma-data-sync-lan-preview.apk) | 19.07 MB |

Sizes use decimal MB. The previous universal APK was 51.98 MB. The connected
Android test device uses `x86_64`; its APK is about 87% smaller. To identify your
own phone's supported architectures, run:

```bash
adb shell getprop ro.product.cpu.abilist
```

Choose the APK matching the first supported architecture. Use the universal APK
if you do not know the architecture. HTTPS preview builds have the same suffixes
without `lan-`, for example
[`apk/forma-data-sync-preview-arm64-v8a.apk`](apk/forma-data-sync-preview-arm64-v8a.apk).
Each exported APK has an adjacent `.apk.sha256` checksum file.

All variants retain collection, background sync, and history. Release-derived
builds remove unused code and resources and compress native libraries. Version
`0.3.5` also compresses the bundled Hermes bytecode using React Native's supported
bundle-compression setting. Android decompresses the bundle when the app starts
instead of directly memory-mapping it; this reduces the download at the cost of
some startup work, as described in React Native's
[bundle-compression documentation](https://reactnative.dev/docs/0.84/react-native-gradle-plugin#enablebundlecompression).
Android extracts native libraries at installation, so installed
storage use is greater than the APK download size. See Android's guides to
[APK splits](https://developer.android.com/build/configure-apk-splits) and
[reducing APK size](https://developer.android.com/topic/performance/reduce-apk-size).

Copy the APK to an Android 8+ phone, open it, and allow that browser or file
manager to install apps when Android prompts. Alternatively, enable USB
debugging, connect the phone, and run this from the repository root:

```bash
adb devices
adb install -r data_sync/apk/forma-data-sync-preview.apk
```

For an existing installation, use `adb install -r` with the matching smaller APK
or choose **Update** when opening it on the phone. Preview and LAN preview builds
use the same app ID and local signing key, so an update preserves the login,
collection permissions, settings, queued records, and sync history. Uninstalling
or clearing app storage deletes local records; neither is needed for this update.

Open **Forma Data Sync Preview**. In the first setup card, choose your history
range, accept local collection consent, and tap **Start**.
The app opens required Android dialogs and settings one at a time. **Usage
Access** is required for app usage, screen events, and network usage: enable
Forma on that Android settings screen and return to the app. If it remains
disabled, the setup shows what to grant and lets you retry. Each compact
permission row has a status, a permission action, and an info icon with the
exact data and a short reason for access.

Calendar, location, activity recognition, notifications, Health Connect,
background location, and battery settings are checked where supported. These
are optional: denied access skips that source while granted sources collect.
Foreground location is requested before background location. If Health Connect
is unavailable, automatic setup skips it and its row offers an install/settings
link. Android requires the user to approve special access on its own settings
screens; the app cannot enable it silently.

The second card, **Sync to server**, is optional. Enter a reachable **HTTPS
server origin** (for example, `https://forma.example.com`) and your Forma email
and password, accept the separate upload consent, and tap **Sync**. It signs
in and connects the device after checking permissions. Starting local collection
alone does not enable uploads. This APK includes its
JavaScript bundle and runs without a Metro development server.

For a server on the same local network, install the standalone
[`apk/forma-data-sync-lan-preview.apk`](apk/forma-data-sync-lan-preview.apk)
instead. This test build also accepts HTTP to private IPv4 addresses, such as
`http://192.168.1.50:8000`. It updates the regular preview app using the same
signing key and preserves its local collection data. HTTP does not encrypt
passwords, tokens, or collected records in transit; use it only for testing on
a trusted LAN. HTTPS remains available and is required by the regular preview
and release builds.

The current LAN test APK prefills the requested test server
`http://192.168.100.8:8000` and login `admin@example.com` / `admin123`; the fields
remain editable. The header shows **LAN preview** and its version. An HTTPS-only
error means the regular preview is installed: install the LAN APK as an update.
These test defaults are excluded from the normal preview and release variants.

The connected app shows collection and upload controls, **Sync history**, and
an **Open website** button. The website opens in the external browser, which may
ask you to sign in. Browser login/logout and the app's upload session are
independent; use the app's controls to pause uploads or sign out of the collector.

Collection runs hourly without a network requirement. Uploads run separately
when the app has an authenticated server connection and network access. Signing
out leaves local collection running; pause collection explicitly to stop it.
The encrypted queue is limited to 64 MiB and reports when full instead of
discarding unuploaded records. Acknowledged uploads free queue space. Batches
already assigned to another account remain local until that account reconnects.

Open **Sync history** on either dashboard to see collection jobs and their
upload status. Tap a job, then a source, to inspect the collected records and
metadata. Queued records remain in the outbox until uploaded. Confirmed uploads
remain viewable in a separate encrypted local archive for up to 30 days / 64 MiB;
older job summaries can remain after their raw records expire.

## Build your own standalone APK

### 1. Install the build tools

Use Node.js **22.11.0 or newer**, npm, and **JDK 17**. Install Android Studio and
use its SDK Manager to install these packages (enable **Show Package Details**
under SDK Tools to choose versions):

| SDK package | Version |
| --- | --- |
| Android SDK Platform | Android 16 / API 36 |
| Android SDK Build-Tools | 36.0.0 |
| Android SDK Platform-Tools | Install the available version; includes `adb` |
| Android SDK Command-line Tools | Install the available version; includes `sdkmanager` |
| NDK (Side by side) | 27.1.12297006 |
| CMake | 3.22.1 |

The repository includes the Gradle 8.14.3 wrapper; a separate Gradle installation
is unnecessary. The first build downloads Gradle and dependencies and needs
internet access.

### 2. Set Java and Android SDK paths

On Linux, adjust the JDK path if your installation differs:

```bash
export JAVA_HOME="/usr/lib/jvm/java-17-openjdk-amd64"
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
```

On macOS:

```bash
export JAVA_HOME="$(/usr/libexec/java_home -v 17)"
export ANDROID_HOME="$HOME/Library/Android/sdk"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$ANDROID_HOME/cmdline-tools/latest/bin:$PATH"
```

Use the SDK location shown in Android Studio if it differs from these examples.
Keep these exports in your shell profile for future terminals. Check the tools:

```bash
node --version
java -version
adb version
```

If using command-line SDK installation, after installing the Android
Command-line Tools, run:

```bash
sdkmanager --licenses
sdkmanager "platform-tools" "platforms;android-36" "build-tools;36.0.0" "ndk;27.1.12297006" "cmake;3.22.1"
```

### 3. Build on Linux or macOS

Start in the **repository root** (`exercise_planner/`):

```bash
cd data_sync/native_app
npm ci
npm run build:apk
```

If you are already in `data_sync/native_app`, run only the last two commands.
`build:apk` runs `assemblePreview` using the Gradle wrapper, then exports the
universal and four architecture-specific APKs with SHA-256 checksums.

The resulting file is:

```text
data_sync/apk/forma-data-sync-preview.apk
data_sync/apk/forma-data-sync-preview-arm64-v8a.apk
data_sync/apk/forma-data-sync-preview-armeabi-v7a.apk
data_sync/apk/forma-data-sync-preview-x86.apk
data_sync/apk/forma-data-sync-preview-x86_64.apk
```

To build the standalone LAN test variant instead, run this from
`data_sync/native_app`:

```bash
npm run build:apk:lan
adb install -r ../apk/forma-data-sync-lan-preview.apk
```

For a smaller install, use the matching architecture-specific file instead,
such as `../apk/forma-data-sync-lan-preview-arm64-v8a.apk` for ARM64 phones or
`../apk/forma-data-sync-lan-preview-x86_64.apk` for the connected test device.

Both standalone variants use `com.forma.datasync.preview`, so installing one
updates the other rather than creating a second app. The LAN variant bundles
JavaScript and Hermes and does not need Metro. On Windows, use
`.\gradlew.bat assembleLan` from `data_sync/native_app/android`.

From the native app folder, install that new build with:

```bash
adb install -r ../apk/forma-data-sync-preview.apk
```

The universal preview contains all four configured Android architectures
(`armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`); each smaller APK contains one.
All preview APKs use application ID
`com.forma.datasync.preview`. They are signed with your local Android debug key for
testing. A production release needs your own signing configuration; the current
`assembleRelease` output is unsigned.

Gradle's original files are under `android/app/build/outputs/apk/<variant>/`,
named `app-<architecture>-<variant>.apk` and `app-universal-<variant>.apk`.
The exporter reads `output-metadata.json` to select the current build outputs,
ignoring stale APK filenames. If you already built with Gradle directly, run
`npm run export:apk` or `npm run export:apk:lan` from the native app folder to
refresh the downloads and checksums without rebuilding.

### Build on Windows

In PowerShell, set paths for your installed JDK 17 and Android SDK, then start
from the repository root. Use the Windows Gradle wrapper directly because the
`build:apk` npm script uses the Unix wrapper:

```powershell
$env:JAVA_HOME = "C:\Program Files\Java\jdk-17"
$env:ANDROID_HOME = "$env:LOCALAPPDATA\Android\Sdk"
$env:Path = "$env:JAVA_HOME\bin;$env:ANDROID_HOME\platform-tools;$env:Path"
cd data_sync\native_app
npm ci
cd android
.\gradlew.bat assemblePreview
node ..\scripts\export-apks.mjs preview
adb install -r ..\..\apk\forma-data-sync-preview.apk
```

For LAN HTTP, replace `assemblePreview` with `assembleLan`, export with
`node ..\scripts\export-apks.mjs lan`, and install
`..\..\apk\forma-data-sync-lan-preview.apk` or the matching smaller APK.

## Start the companion server

Both server entry points include native uploads and Phase 4 browser APIs. The
companion serves the built dashboard and binds to all interfaces for LAN access.
Local collection works without a server. From the repository root, create the
Python environment if needed and run:

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -r data_sync/native_app/server/requirements.txt
npm ci
npm run build
.venv/bin/python -m data_sync.native_app.server
```

Run one server process: the companion listens on `0.0.0.0:8000` by default;
`python -m backend.server` includes the same APIs and defaults to loopback.
Put it behind HTTPS and set `COOKIE_SECURE=true` in
the repository `.env` for the preview APK. Create a member account through the
existing Forma web dashboard before signing in on the phone. The server origin
must be reachable from the phone; `localhost` there refers to the phone itself.
See the [server README](native_app/server/README.md) for configuration details.
Set `NATIVE_PUBLIC_URL=https://forma.example.com` when connection instructions
should advertise a specific public origin behind a reverse proxy.

For a phone on the same LAN, use the **LAN preview APK** and enter
`http://<computer-LAN-IP>:8000`. Keep the server running and allow inbound port
8000 through the computer's firewall if needed. First open
`http://<computer-LAN-IP>:8000/api/health` in the phone's browser to check
connectivity. Enter only the origin in the app, without `/api`, `/docs`, or
`/api/health`. Login uses an existing Forma account's email and password;
the app receives its user ID from the server. For local HTTP, keep
`COOKIE_SECURE=false` so the dashboard can use its session cookie.

On Windows, replace `python3` with `python` and `.venv/bin/python` with
`.venv\Scripts\python.exe` in those commands.

## Development and troubleshooting

For a local HTTP server, use the standalone LAN test build above or the
development app: from `data_sync/native_app`,
run `npm start`, then run `npm run android` in another terminal in that same
folder. The debug app requires Metro and uses `com.forma.datasync`, so it can
coexist with the preview app. An Android emulator reaches your computer at
`http://10.0.2.2:8000`; a physical phone uses your computer's LAN address.

If Java or the SDK cannot be found, check `JAVA_HOME`, `ANDROID_HOME`, and any
existing `android/local.properties` SDK path. For missing SDK, NDK, CMake, or
license errors, install the exact packages above and accept SDK licenses. Run
`npm ci` inside `native_app` for missing JavaScript dependencies. If Android
reports a signing mismatch when updating an APK built on another computer,
uninstall the existing preview app and install again; uninstalling clears its
local settings, session, and collected data. Enter an HTTPS origin for regular preview
connection errors and confirm that it serves `/api/native/*`.
