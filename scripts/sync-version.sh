#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
VERSION_FILE="$ROOT_DIR/VERSION"

VERSION=$(cat "$VERSION_FILE" | tr -d '[:space:]')
echo "Syncing version $VERSION across project..."

# Update MacHost/Info.plist
INFO_PLIST="$ROOT_DIR/MacHost/Info.plist"
if [ -f "$INFO_PLIST" ]; then
    /usr/libexec/PlistBuddy -c "Set :CFBundleVersion $VERSION" "$INFO_PLIST" 2>/dev/null || true
    /usr/libexec/PlistBuddy -c "Set :CFBundleShortVersionString $VERSION" "$INFO_PLIST" 2>/dev/null || true
    echo "  ✓ MacHost/Info.plist updated to $VERSION"
fi

# Update README.md adb command examples
README="$ROOT_DIR/README.md"
if [ -f "$README" ]; then
    gsed -i -E "s/SideKick-[0-9]+\.[0-9]+\.[0-9]+-android-release\.apk/SideKick-${VERSION}-android-release.apk/g" "$README" 2>/dev/null || \
    sed -i '' -E "s/SideKick-[0-9]+\.[0-9]+\.[0-9]+-android-release\.apk/SideKick-${VERSION}-android-release.apk/g" "$README" 2>/dev/null || true
    echo "  ✓ README.md updated to $VERSION"
fi

echo "Version sync complete: $VERSION"
