#!/bin/bash
set -e

export JAVA_HOME="${JAVA_HOME:-/Applications/Android Studio.app/Contents/jbr/Contents/Home}"
export ANDROID_HOME="${ANDROID_HOME:-$HOME/Library/Android/sdk}"
export PATH="$PATH:$ANDROID_HOME/platform-tools"

GRADLE_VERSION="8.7"
WRAPPER_BASE="https://raw.githubusercontent.com/gradle/gradle/v${GRADLE_VERSION}.0"

# Bootstrap Gradle wrapper if not present
if [ ! -f "gradlew" ]; then
    echo "==> Downloading Gradle wrapper..."
    curl -fsSL "$WRAPPER_BASE/gradlew" -o gradlew
    chmod +x gradlew
fi

if [ ! -f "gradle/wrapper/gradle-wrapper.jar" ]; then
    echo "==> Downloading gradle-wrapper.jar..."
    mkdir -p gradle/wrapper
    curl -fsSL "$WRAPPER_BASE/gradle/wrapper/gradle-wrapper.jar" \
        -o gradle/wrapper/gradle-wrapper.jar
fi

echo "==> Building Go bridge..."
(cd gobridge && bash build-android.sh)

echo "==> Building APK..."
./gradlew assembleDebug

echo "==> Installing..."
adb wait-for-device
adb install -r app/build/outputs/apk/debug/app-debug.apk

echo ""
echo "Done. Open Photon on the device."
