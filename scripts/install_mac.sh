#!/bin/bash
# Installs SideKick.app into /Applications, clears quarantine attributes,
# and launches it cleanly.
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"
APP_SRC="$ROOT_DIR/SideKick.app"
APP_DST="/Applications/SideKick.app"

if [ ! -d "$APP_SRC" ]; then
    echo "❌ $APP_SRC not found. Run ./scripts/build_mac.sh first."
    exit 1
fi

echo "⏹️  Stopping running SideKick..."
pkill -f SideKick 2>/dev/null || true
sleep 0.5

echo "📦 Installing SideKick.app to /Applications..."
rm -rf "$APP_DST"
cp -R "$APP_SRC" "$APP_DST"
xattr -cr "$APP_DST"

echo "🚀 Launching /Applications/SideKick.app..."
open "$APP_DST"
echo "✅ Done!"
