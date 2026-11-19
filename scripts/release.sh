#!/bin/bash
set -e

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ROOT_DIR="$(cd "$SCRIPT_DIR/.." && pwd)"

VERSION=$(cat "$ROOT_DIR/VERSION" | tr -d '[:space:]')
TAG="v$VERSION"

echo "======================================="
echo "  SideKick - Release $TAG"
echo "======================================="
echo ""

cd "$ROOT_DIR"

# 1. Sync version across Info.plist & README.md
echo "[1/4] Syncing version $VERSION..."
"$SCRIPT_DIR/sync-version.sh"

# 2. Build macOS DMG & Android APKs
echo "[2/4] Building macOS & Android artifacts..."
"$SCRIPT_DIR/build_mac.sh"
"$SCRIPT_DIR/build_android.sh"

# 3. Commit, tag & push to GitHub
echo "[3/4] Pushing to GitHub..."
if [ -n "$(git status --porcelain)" ]; then
    git add -A
    git status --short
    read -p "Commit message [chore: release $TAG]: " MSG
    MSG="${MSG:-chore: release $TAG}"
    git commit -m "$MSG"
fi
git push origin main

if git rev-parse "$TAG" >/dev/null 2>&1; then
    echo "  Tag $TAG already exists — skipping tag creation"
else
    git tag "$TAG"
    git push origin "$TAG"
    echo "  ✓ Tag $TAG pushed"
fi

# 4. Publish GitHub Release + update Homebrew Cask
echo "[4/4] Publishing GitHub Release & updating Homebrew Cask..."
uv run "$SCRIPT_DIR/publish_release.py"

echo ""
echo "======================================="
echo "  Done! Released SideKick $TAG"
echo "======================================="
