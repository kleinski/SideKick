#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

echo "🔨 Building Android Client..."
cd "$ROOT_DIR/AndroidClient"

# Prefer local .toolchain JDK & Android SDK if present, otherwise Android Studio's bundled JDK
if [ -d "$ROOT_DIR/.toolchain/jdk/Contents/Home" ]; then
    export JAVA_HOME="$ROOT_DIR/.toolchain/jdk/Contents/Home"
elif [ -d "/Applications/Android Studio.app/Contents/jbr/Contents/Home" ]; then
    export JAVA_HOME="/Applications/Android Studio.app/Contents/jbr/Contents/Home"
fi

if [ -d "$ROOT_DIR/.toolchain/android-sdk" ]; then
    export ANDROID_HOME="$ROOT_DIR/.toolchain/android-sdk"
    export PATH="$ANDROID_HOME/platform-tools:$PATH"
fi

# Check if Java is available
if [ -z "${JAVA_HOME:-}" ] || [ ! -d "$JAVA_HOME" ]; then
    echo "❌ Java not found."
    echo "   Please install Android Studio or place a JDK in .toolchain/jdk"
    exit 1
fi

./gradlew assembleDebug

echo ""
echo "✅ Build successful!"
echo ""
echo "📦 APK: $ROOT_DIR/AndroidClient/app/build/outputs/apk/debug/app-debug.apk"
echo ""
echo "To install on device:"
echo "  adb install -r $ROOT_DIR/AndroidClient/app/build/outputs/apk/debug/app-debug.apk"
echo ""
echo "Or run: ./scripts/install_android.sh"
