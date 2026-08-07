#!/usr/bin/env bash
# =============================================================================
#  LAN FPS - one-shot release build (Linux / macOS)
#
#  Produces:
#     release/server.zip                     Windows server bundle
#     release/lanfps-client-release.apk      signed Android APK  (needs the SDK)
#     release/lanfps-client-debug.apk        debug APK           (needs the SDK)
#
#  The APK steps are skipped automatically when no Android SDK is configured,
#  so this script always succeeds at producing the server bundle.
# =============================================================================
set -euo pipefail

cd "$(dirname "$0")/.."
ROOT="$(pwd)"
OUT="$ROOT/release"
mkdir -p "$OUT"

echo "=============================================================="
echo " LAN FPS release build"
echo " project: $ROOT"
echo "=============================================================="

# --- 1. signing key ----------------------------------------------------------
KEYSTORE="$ROOT/keystore/lanfps.keystore"
if [ ! -f "$KEYSTORE" ]; then
  echo "[1/4] generating a local signing key (password: lanfps)"
  mkdir -p "$ROOT/keystore"
  keytool -genkeypair -v \
    -keystore "$KEYSTORE" \
    -alias lanfps -keyalg RSA -keysize 2048 -validity 10000 \
    -storepass lanfps -keypass lanfps \
    -dname "CN=LAN FPS, OU=LAN, O=LAN FPS, L=LAN, S=LAN, C=LT"
else
  echo "[1/4] signing key already present"
fi

# --- 2. tests ----------------------------------------------------------------
echo "[2/4] running the shared + server test suites"
./gradlew :shared:test :server:test --console=plain

# --- 3. server bundle --------------------------------------------------------
echo "[3/4] building the server bundle"
./gradlew :server:packageServer --console=plain
echo "      -> $OUT/server.zip"

# --- 4. Android APK ----------------------------------------------------------
SDK="${ANDROID_SDK_ROOT:-${ANDROID_HOME:-}}"
if [ -z "$SDK" ] && [ -f "$ROOT/local.properties" ]; then
  SDK="$(sed -n 's/^sdk\.dir=//p' "$ROOT/local.properties" | head -1)"
fi

if [ -z "$SDK" ] || [ ! -d "$SDK" ]; then
  cat <<'EOF'
[4/4] SKIPPED - no Android SDK found.

      The server bundle above is complete and ready to use.
      To build the APK, install the Android SDK and re-run this script:

        # command-line tools only, no Android Studio needed
        export ANDROID_SDK_ROOT="$HOME/android-sdk"
        mkdir -p "$ANDROID_SDK_ROOT/cmdline-tools"
        curl -L -o /tmp/clt.zip \
          https://dl.google.com/android/repository/commandlinetools-linux-11076708_latest.zip
        unzip -q /tmp/clt.zip -d /tmp/clt
        mv /tmp/clt/cmdline-tools "$ANDROID_SDK_ROOT/cmdline-tools/latest"
        yes | "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" --licenses
        "$ANDROID_SDK_ROOT/cmdline-tools/latest/bin/sdkmanager" \
            "platform-tools" "platforms;android-34" "build-tools;34.0.0"

      (Android Studio users: just open the project and Build > Build APK.)
EOF
  exit 0
fi

echo "[4/4] building the Android client with SDK at $SDK"
./gradlew :client-android:assembleDebug :client-android:assembleRelease --console=plain

DEBUG_APK="$ROOT/client-android/build/outputs/apk/debug/client-android-debug.apk"
RELEASE_APK="$ROOT/client-android/build/outputs/apk/release/client-android-release.apk"

[ -f "$DEBUG_APK" ] && cp -f "$DEBUG_APK" "$OUT/lanfps-client-debug.apk"
[ -f "$RELEASE_APK" ] && cp -f "$RELEASE_APK" "$OUT/lanfps-client-release.apk"

echo
echo "=============================================================="
echo " done. artefacts in release/:"
ls -lh "$OUT"
echo "=============================================================="
