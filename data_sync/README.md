# Android data sync: build and install an APK

The Android app and companion server live in [`native_app/`](native_app/).
The app lets you skip login and collect permitted Android data locally. Sign in
to an existing Forma account and connect a server to upload queued data in the
background. **Open website** opens the current dashboard in your phone's browser.
See the [app README](native_app/README.md) for collectors, permissions, and the
upload contract.

## Install the generated APK

The generated, standalone test build is
[`apk/forma-data-sync-preview.apk`](apk/forma-data-sync-preview.apk).
Its checksum is in
[`apk/forma-data-sync-preview.apk.sha256`](apk/forma-data-sync-preview.apk.sha256).
These are local build artifacts and are excluded from Git.

Copy the APK to an Android 8+ phone, open it, and allow that browser or file
manager to install apps when Android prompts. Alternatively, enable USB
debugging, connect the phone, and run this from the repository root:

```bash
adb devices
adb install -r data_sync/apk/forma-data-sync-preview.apk
```

Open **Forma Data Sync Preview** and choose **Skip login** to start local
collection. Complete permission onboarding and choose your history range; no
server is required. You can sign in later with a reachable **HTTPS server
origin** (for example, `https://forma.example.com`) and your Forma email and
password, then accept upload consent to connect. This APK includes its JavaScript
bundle and runs without a Metro development server.

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
`build:apk` runs `assemblePreview` using the Gradle wrapper.

The resulting file is:

```text
data_sync/native_app/android/app/build/outputs/apk/preview/app-preview.apk
```

To build the standalone LAN test variant instead, run this from
`data_sync/native_app`:

```bash
npm run build:apk:lan
adb install -r android/app/build/outputs/apk/lan/app-lan.apk
```

Both standalone variants use `com.forma.datasync.preview`, so installing one
updates the other rather than creating a second app. The LAN variant bundles
JavaScript and Hermes and does not need Metro. On Windows, use
`.\gradlew.bat assembleLan` from `data_sync/native_app/android`.

From the native app folder, install that new build with:

```bash
adb install -r android/app/build/outputs/apk/preview/app-preview.apk
```

The preview build contains all four configured Android architectures
(`armeabi-v7a`, `arm64-v8a`, `x86`, and `x86_64`) and uses application ID
`com.forma.datasync.preview`. It is signed with your local Android debug key for
testing. A production release needs your own signing configuration; the current
`assembleRelease` output is unsigned.

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
adb install -r app\build\outputs\apk\preview\app-preview.apk
```

## Start the companion server

Server sync needs the native companion entrypoint, which serves the existing
API, native upload endpoints, and built web dashboard together. Local collection
works without it. From the repository root, create the Python environment if
needed and run:

```bash
python3 -m venv .venv
.venv/bin/python -m pip install -r data_sync/native_app/server/requirements.txt
npm ci
npm run build
.venv/bin/python -m data_sync.native_app.server
```

Use this entrypoint in place of `python -m backend.server`. It listens on
`0.0.0.0:8000` by default. Put it behind HTTPS and set `COOKIE_SECURE=true` in
the repository `.env` for the preview APK. Create a member account through the
existing Forma web dashboard before signing in on the phone. The server origin
must be reachable from the phone; `localhost` there refers to the phone itself.
See the [server README](native_app/server/README.md) for configuration details.

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
