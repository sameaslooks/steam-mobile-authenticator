#!/usr/bin/env bash
set -euo pipefail

DEBUG=0
CLEAN=0
for arg in "$@"; do
  case "$arg" in
    --debug) DEBUG=1 ;;
    --clean) CLEAN=1 ;;
    *) echo "Unknown argument: $arg" >&2; exit 1 ;;
  esac
done

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
TOOLS="$ROOT/.tools"
mkdir -p "$TOOLS"

# OS / architecture detection
case "$(uname -s)" in
  Linux)  OS=linux; CMDLINE_ZIP=commandlinetools-linux-11076708_latest.zip ;;
  Darwin) OS=mac;   CMDLINE_ZIP=commandlinetools-mac-11076708_latest.zip ;;
  *) echo "Unsupported OS: $(uname -s)" >&2; exit 1 ;;
esac
case "$(uname -m)" in
  x86_64|amd64)  ARCH=x64 ;;
  arm64|aarch64) ARCH=aarch64 ;;
  *) echo "Unsupported architecture: $(uname -m)" >&2; exit 1 ;;
esac

# 1. JDK 17
JDK_DIR="$TOOLS/jdk17"
if [ "$OS" = mac ]; then JAVA_HOME_DIR="$JDK_DIR/Contents/Home"; else JAVA_HOME_DIR="$JDK_DIR"; fi
if [ ! -x "$JAVA_HOME_DIR/bin/java" ]; then
  echo "Downloading JDK 17..."
  curl -fsSL -o "$TOOLS/jdk17.tar.gz" \
    "https://api.adoptium.net/v3/binary/latest/17/ga/$OS/$ARCH/jdk/hotspot/normal/eclipse"
  rm -rf "$TOOLS/jdk-tmp"
  mkdir -p "$TOOLS/jdk-tmp"
  tar -xzf "$TOOLS/jdk17.tar.gz" -C "$TOOLS/jdk-tmp"
  INNER="$(find "$TOOLS/jdk-tmp" -mindepth 1 -maxdepth 1 -type d | head -n 1)"
  rm -rf "$JDK_DIR"
  mv "$INNER" "$JDK_DIR"
  rm -rf "$TOOLS/jdk-tmp" "$TOOLS/jdk17.tar.gz"
fi
export JAVA_HOME="$JAVA_HOME_DIR"
export PATH="$JAVA_HOME/bin:$PATH"

# 2. Android SDK command-line tools, licenses, packages
SDK_ROOT="$TOOLS/android-sdk"
SDKMANAGER="$SDK_ROOT/cmdline-tools/latest/bin/sdkmanager"
if [ ! -x "$SDKMANAGER" ]; then
  echo "Downloading Android command-line tools..."
  curl -fsSL -o "$TOOLS/cmdline-tools.zip" "https://dl.google.com/android/repository/$CMDLINE_ZIP"
  rm -rf "$TOOLS/cmdline-tmp"
  mkdir -p "$TOOLS/cmdline-tmp"
  unzip -q "$TOOLS/cmdline-tools.zip" -d "$TOOLS/cmdline-tmp"
  mkdir -p "$SDK_ROOT/cmdline-tools"
  rm -rf "$SDK_ROOT/cmdline-tools/latest"
  mv "$TOOLS/cmdline-tmp/cmdline-tools" "$SDK_ROOT/cmdline-tools/latest"
  rm -rf "$TOOLS/cmdline-tmp" "$TOOLS/cmdline-tools.zip"
  chmod +x "$SDKMANAGER"
fi
export ANDROID_HOME="$SDK_ROOT"
export ANDROID_SDK_ROOT="$SDK_ROOT"

if [ ! -d "$SDK_ROOT/platform-tools" ] || [ ! -d "$SDK_ROOT/platforms/android-35" ] || [ ! -d "$SDK_ROOT/build-tools/35.0.0" ]; then
  { for _ in $(seq 1 30); do echo y; done; } | "$SDKMANAGER" --sdk_root="$SDK_ROOT" --licenses >/dev/null
  "$SDKMANAGER" --sdk_root="$SDK_ROOT" "platform-tools" "platforms;android-35" "build-tools;35.0.0"
fi

# 3. Gradle 8.11.1
GRADLE_HOME="$TOOLS/gradle-8.11.1"
if [ ! -x "$GRADLE_HOME/bin/gradle" ]; then
  echo "Downloading Gradle 8.11.1..."
  curl -fsSL -o "$TOOLS/gradle.zip" "https://services.gradle.org/distributions/gradle-8.11.1-bin.zip"
  unzip -q "$TOOLS/gradle.zip" -d "$TOOLS"
  rm -f "$TOOLS/gradle.zip"
  chmod +x "$GRADLE_HOME/bin/gradle"
fi

# 4. local.properties
printf 'sdk.dir=%s\n' "$SDK_ROOT" > "$ROOT/local.properties"

# 5. Release signing keystore
SIGNING_DIR="$ROOT/.signing"
KEYSTORE_PROPS="$SIGNING_DIR/keystore.properties"
if [ "$DEBUG" -eq 0 ] && [ ! -f "$KEYSTORE_PROPS" ]; then
  mkdir -p "$SIGNING_DIR"
  PW="$(LC_ALL=C tr -dc 'A-Za-z0-9' < /dev/urandom | head -c 24 || true)"
  "$JAVA_HOME/bin/keytool" -genkeypair -v -keystore "$SIGNING_DIR/release.jks" -alias sma \
    -keyalg RSA -keysize 4096 -validity 10000 \
    -storepass "$PW" -keypass "$PW" -dname "CN=sameaslooks, O=SMA"
  printf 'storeFile=../.signing/release.jks\nstorePassword=%s\nkeyAlias=sma\nkeyPassword=%s\n' "$PW" "$PW" > "$KEYSTORE_PROPS"
  echo "WARNING: A new release keystore was generated in .signing/. Back up the whole .signing folder now. It is required to publish updates signed with the same key."
fi

# 6. Build and unit tests
if [ "$DEBUG" -eq 1 ]; then
  VARIANT=debug
  SUFFIX=-debug
  TASKS=(testDebugUnitTest assembleDebug)
else
  VARIANT=release
  SUFFIX=""
  TASKS=(testReleaseUnitTest assembleRelease)
fi
GRADLE_ARGS=(-p "$ROOT" --no-daemon)
if [ "$CLEAN" -eq 1 ]; then GRADLE_ARGS+=(clean); fi
"$GRADLE_HOME/bin/gradle" "${GRADLE_ARGS[@]}" "${TASKS[@]}"

# 7. Copy artifact to dist/ and print hash
VERSION_NAME="$(sed -n 's/^[[:space:]]*versionName[[:space:]]*=[[:space:]]*"\([^"]*\)".*/\1/p' "$ROOT/app/build.gradle.kts" | head -n 1)"
if [ -z "$VERSION_NAME" ]; then echo "versionName not found in app/build.gradle.kts" >&2; exit 1; fi

APK_DIR="$ROOT/app/build/outputs/apk/$VARIANT"
APK_SRC="$APK_DIR/app-$VARIANT.apk"
if [ ! -f "$APK_SRC" ]; then APK_SRC="$APK_DIR/app-$VARIANT-unsigned.apk"; fi
if [ ! -f "$APK_SRC" ]; then echo "APK not found in $APK_DIR" >&2; exit 1; fi

mkdir -p "$ROOT/dist"
DST="$ROOT/dist/sma-$VERSION_NAME$SUFFIX.apk"
cp "$APK_SRC" "$DST"
if command -v sha256sum >/dev/null 2>&1; then
  HASH="$(sha256sum "$DST" | cut -d' ' -f1)"
else
  HASH="$(shasum -a 256 "$DST" | cut -d' ' -f1)"
fi
echo "APK: $DST"
echo "SHA-256: $HASH"
