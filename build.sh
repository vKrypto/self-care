#!/usr/bin/env bash
# Rebuild the Forma Android APKs and export them, with SHA-256 checksums, to data_sync/apk.
#
#   ./build.sh                  build the HTTPS preview and the LAN preview APKs
#   ./build.sh lan              build only the LAN preview (accepts http:// on a private LAN)
#   ./build.sh preview          build only the HTTPS preview
#   ./build.sh lan --install    also update the app on the connected device (adb install -r keeps its data)
#
# Options: --install, --offline (use only Gradle's download cache), -h | --help.
# JAVA_HOME (JDK 17), ANDROID_HOME and GRADLE_USER_HOME are used when set; otherwise common
# install locations are searched. Set ANDROID_SERIAL to choose between several devices.
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP="$ROOT/data_sync/native_app"
APK_DIR="$ROOT/data_sync/apk"

usage() { sed -n '2,11p' "$0" | sed 's/^# \{0,1\}//'; }
fail() { echo "build.sh: $*" >&2; exit 1; }

variants=(preview lan)
# Both variants share one application ID, so installing replaces whichever is on the device.
install_variant=lan
install=false
offline=""
for arg in "$@"; do
  case "$arg" in
    all) variants=(preview lan); install_variant=lan ;;
    preview|lan) variants=("$arg"); install_variant="$arg" ;;
    --install) install=true ;;
    --offline) offline=--offline ;;
    -h|--help) usage; exit 0 ;;
    *) usage >&2; fail "unknown argument: $arg" ;;
  esac
done

# React Native 0.84 builds with Gradle 8.14, which needs JDK 17; newer JDKs fail.
is_jdk17() { [[ -x "$1/bin/java" ]] && "$1/bin/java" -version 2>&1 | grep -q 'version "17'; }
find_jdk() {
  local candidate
  for candidate in "${JAVA_HOME:-}" /tmp/forma-jdk17 /usr/lib/jvm/java-17-openjdk* /usr/lib/jvm/temurin-17* \
      "$(/usr/libexec/java_home -v 17 2>/dev/null || true)"; do
    if [[ -n "$candidate" ]] && is_jdk17 "$candidate"; then echo "$candidate"; return; fi
  done
  return 1
}
find_sdk() {
  local candidate configured=""
  if [[ -f "$APP/android/local.properties" ]]; then
    configured="$(sed -n 's/^sdk\.dir=//p' "$APP/android/local.properties")"
  fi
  for candidate in "${ANDROID_HOME:-}" "${ANDROID_SDK_ROOT:-}" "$configured" /tmp/forma-android-sdk \
      "$HOME/Android/Sdk" "$HOME/Library/Android/sdk"; do
    if [[ -n "$candidate" && -d "$candidate/platforms" ]]; then echo "$candidate"; return; fi
  done
  return 1
}

JAVA_HOME="$(find_jdk)" || fail "JDK 17 not found. Install it and set JAVA_HOME; see data_sync/README.md."
ANDROID_HOME="$(find_sdk)" || fail "Android SDK not found. Set ANDROID_HOME; see data_sync/README.md."
export JAVA_HOME ANDROID_HOME ANDROID_SDK_ROOT="$ANDROID_HOME"
export PATH="$JAVA_HOME/bin:$ANDROID_HOME/platform-tools:$PATH"
# Reuse an existing dependency cache instead of downloading every dependency again.
if [[ -z "${GRADLE_USER_HOME:-}" && ! -d "$HOME/.gradle/caches" && -d /tmp/forma-gradle/caches ]]; then
  export GRADLE_USER_HOME=/tmp/forma-gradle
fi
command -v node >/dev/null || fail "Node.js 22.11 or newer is required; see data_sync/README.md."

echo "==> JDK $JAVA_HOME"
echo "==> Android SDK $ANDROID_HOME"
echo "==> Gradle cache ${GRADLE_USER_HOME:-$HOME/.gradle}"
if [[ ! -d "$APP/node_modules" ]]; then
  echo "==> Installing JavaScript dependencies"
  (cd "$APP" && npm ci)
fi

for variant in "${variants[@]}"; do
  case "$variant" in preview) task=assemblePreview ;; lan) task=assembleLan ;; esac
  echo "==> Building $variant APKs"
  (cd "$APP/android" && ./gradlew ${offline:+"$offline"} ":app:$task")
  (cd "$APP" && node scripts/export-apks.mjs "$variant")
done

if $install; then
  adb="${ADB:-$ANDROID_HOME/platform-tools/adb}"
  abi="$("$adb" shell getprop ro.product.cpu.abi | tr -d '\r')" ||
    fail "No device available. Connect one, or set ANDROID_SERIAL when several are connected."
  case "$install_variant" in preview) prefix=forma-data-sync-preview ;; lan) prefix=forma-data-sync-lan-preview ;; esac
  apk="$APK_DIR/$prefix-$abi.apk"
  [[ -f "$apk" ]] || apk="$APK_DIR/$prefix.apk"
  [[ -f "$apk" ]] || fail "No $install_variant APK to install. Run ./build.sh $install_variant first."
  echo "==> Installing $(basename "$apk") ($abi)"
  "$adb" install -r "$apk"
fi

echo "==> Done in ${SECONDS}s. APKs and checksums are in data_sync/apk/."
