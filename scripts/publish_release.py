#!/usr/bin/env python3
"""
Publishes the current VERSION of SideKick to GitHub Releases, uploads the macOS
DMG and Android APKs, and updates + pushes the Homebrew Cask in
/Users/kleinski/DEVEL/homebrew-sidekick.
"""

import argparse
import hashlib
import json
import os
import re
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path

REPO = "kleinski/SideKick"
ROOT_DIR = Path(__file__).resolve().parent.parent
DEFAULT_TAP_DIR = Path(os.environ.get("HOMEBREW_TAP_DIR", "/Users/kleinski/DEVEL/homebrew-sidekick"))

ATTRIBUTION_BLOCK = """\
> **🙏 A Huge Thank You to Tran Vuong Quoc Dat**
>
> All credit for the foundational architecture — the ultra-low-latency hardware-accelerated H.265 streaming pipeline, the native macOS `CGVirtualDisplay` integration, the Android `MediaCodec` decoder, and predictive touch input — goes to **[Tran Vuong Quoc Dat](https://github.com/tranvuongquocdat)** ([tranvuongquocdat/SideScreen](https://github.com/tranvuongquocdat/SideScreen)). I am deeply grateful for his outstanding engineering work on **SideScreen** and for his generous decision to release the project under the **[MIT License](https://github.com/kleinski/SideKick/blob/main/LICENSE)**. Without his work and open-source spirit, **SideKick** would not exist.
"""


def read_version() -> str:
    return (ROOT_DIR / "VERSION").read_text(encoding="utf-8").strip()


def extract_changelog_section(version: str) -> tuple[str, str]:
    """Returns (headline_suffix, body_markdown) for `version` from CHANGELOG.md."""
    changelog = (ROOT_DIR / "CHANGELOG.md").read_text(encoding="utf-8")
    lines = changelog.splitlines()
    heading_re = re.compile(rf"^## \[{re.escape(version)}\](?:\s*-\s*\d{{4}}-\d{{2}}-\d{{2}}\s*[—–-]\s*(.+))?$")

    capturing = False
    subtitle = ""
    section_lines: list[str] = []

    for line in lines:
        if not capturing:
            m = heading_re.match(line.strip())
            if m:
                capturing = True
                subtitle = (m.group(1) or "").strip()
        else:
            if line.strip().startswith("---"):
                break
            section_lines.append(line)

    body = "\n".join(section_lines).strip()
    if not body:
        body = f"See [CHANGELOG.md](https://github.com/{REPO}/blob/main/CHANGELOG.md) for details."
    return subtitle, body


def build_release_body(version: str, changelog_notes: str) -> str:
    return f"""## SideKick v{version}

{ATTRIBUTION_BLOCK}
---

{changelog_notes}

---

### Downloads & Installation
- **Homebrew (macOS):**
  ```bash
  brew install --cask kleinski/sidekick/sidekick
  ```
- **macOS (Apple Silicon + Intel Universal):** `SideKick-{version}-mac-universal.dmg`
- **Android (8.0+ Tablet):** `SideKick-{version}-android-release.apk` (or `SideKick-{version}-android-debug.apk`)
"""


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()


def get_github_token() -> str:
    env_token = os.environ.get("GITHUB_TOKEN", "").strip()
    if env_token:
        return env_token
    proc = subprocess.run(
        ["git", "credential", "fill"],
        input="protocol=https\nhost=github.com\n\n",
        text=True,
        capture_output=True,
        check=True,
    )
    for line in proc.stdout.splitlines():
        if line.startswith("password="):
            return line.split("=", 1)[1].strip()
    raise RuntimeError("Could not obtain GitHub token from GITHUB_TOKEN or git credential helper")


def api_request(url: str, token: str, method: str = "GET", data: bytes | None = None, content_type: str = "application/json") -> dict:
    headers = {
        "Authorization": f"Bearer {token}",
        "Accept": "application/vnd.github+json",
        "X-GitHub-Api-Version": "2022-11-28",
        "User-Agent": "SideKick-Release-Publisher",
    }
    if data is not None:
        headers["Content-Type"] = content_type
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    with urllib.request.urlopen(req) as resp:
        return json.loads(resp.read().decode("utf-8"))


def publish_github_release(version: str, title: str, body: str, assets: list[tuple[Path, str]], dry_run: bool) -> None:
    tag = f"v{version}"
    if dry_run:
        print(f"[dry-run] Would publish GitHub release {tag}: {title}")
        for path, _ in assets:
            print(f"[dry-run]   asset: {path.name} ({path.stat().st_size / (1024 * 1024):.1f} MB)")
        return

    token = get_github_token()
    base_url = f"https://api.github.com/repos/{REPO}/releases"

    try:
        release = api_request(f"{base_url}/tags/{tag}", token)
        print(f"Found existing release for {tag}, updating...")
        payload = json.dumps({
            "tag_name": tag,
            "name": title,
            "body": body,
            "draft": False,
            "prerelease": False,
        }).encode("utf-8")
        release = api_request(f"{base_url}/{release['id']}", token, method="PATCH", data=payload)
    except urllib.error.HTTPError as e:
        if e.code == 404:
            print(f"Creating new release for {tag}...")
            payload = json.dumps({
                "tag_name": tag,
                "target_commitish": "main",
                "name": title,
                "body": body,
                "draft": False,
                "prerelease": False,
            }).encode("utf-8")
            release = api_request(base_url, token, method="POST", data=payload)
        else:
            print(f"HTTP Error {e.code}: {e.read().decode('utf-8', errors='replace')}", file=sys.stderr)
            sys.exit(1)

    upload_url_template = release["upload_url"].split("{")[0]
    existing_assets = {a["name"]: a["id"] for a in release.get("assets", [])}

    for path, mime in assets:
        if path.name in existing_assets:
            asset_id = existing_assets[path.name]
            print(f"Deleting existing asset {path.name} ({asset_id})...")
            req = urllib.request.Request(
                f"{base_url}/assets/{asset_id}",
                headers={
                    "Authorization": f"Bearer {token}",
                    "Accept": "application/vnd.github+json",
                    "User-Agent": "SideKick-Release-Publisher",
                },
                method="DELETE",
            )
            with urllib.request.urlopen(req):
                pass
        print(f"Uploading {path.name} ({path.stat().st_size / (1024 * 1024):.1f} MB)...")
        upload_url = f"{upload_url_template}?name={path.name}"
        asset_resp = api_request(upload_url, token, method="POST", data=path.read_bytes(), content_type=mime)
        print(f"  ✓ {asset_resp['browser_download_url']}")

    print(f"\nRelease published: {release['html_url']}")


def update_homebrew_cask(tap_dir: Path, version: str, dmg_sha256: str, dry_run: bool) -> None:
    cask_path = tap_dir / "Casks" / "sidekick.rb"
    if not cask_path.is_file():
        print(f"⚠️  Homebrew cask not found at {cask_path} — skipping tap update", file=sys.stderr)
        return

    original = cask_path.read_text(encoding="utf-8")
    updated, ver_subs = re.subn(r'^(\s*version\s+")[^"]+(")', rf'\g<1>{version}\g<2>', original, count=1, flags=re.MULTILINE)
    updated, sha_subs = re.subn(r'^(\s*sha256\s+")[^"]+(")', rf'\g<1>{dmg_sha256}\g<2>', updated, count=1, flags=re.MULTILINE)

    if ver_subs != 1 or sha_subs != 1:
        raise RuntimeError(f"Unexpected format in {cask_path}: matched version={ver_subs}, sha256={sha_subs}")

    if updated == original:
        print(f"Homebrew cask already at v{version} ({dmg_sha256[:12]}…) — nothing to commit.")
        return

    if dry_run:
        print(f"[dry-run] Would update {cask_path}: version={version}, sha256={dmg_sha256}")
        return

    cask_path.write_text(updated, encoding="utf-8")
    subprocess.run(["git", "-C", str(tap_dir), "add", "Casks/sidekick.rb"], check=True)
    subprocess.run(["git", "-C", str(tap_dir), "commit", "-m", f"chore: bump sidekick to {version}"], check=True)
    subprocess.run(["git", "-C", str(tap_dir), "push", "origin", "main"], check=True)
    print(f"✓ Homebrew tap updated & pushed: v{version} ({dmg_sha256[:12]}…)")


def main() -> None:
    parser = argparse.ArgumentParser(description="Publish SideKick release to GitHub and update Homebrew Cask.")
    parser.add_argument("--tap-dir", type=Path, default=DEFAULT_TAP_DIR, help="Path to local homebrew-sidekick tap repo")
    parser.add_argument("--dry-run", action="store_true", help="Print planned actions without publishing or pushing")
    args = parser.parse_args()

    version = read_version()
    subtitle, notes = extract_changelog_section(version)
    title = f"SideKick v{version} — {subtitle}" if subtitle else f"SideKick v{version}"
    body = build_release_body(version, notes)

    dmg_path = ROOT_DIR / f"SideKick-{version}-mac-universal.dmg"
    apk_rel = ROOT_DIR / f"SideKick-{version}-android-release.apk"
    apk_dbg = ROOT_DIR / f"SideKick-{version}-android-debug.apk"

    assets = [
        (dmg_path, "application/x-apple-diskimage"),
        (apk_rel, "application/vnd.android.package-archive"),
        (apk_dbg, "application/vnd.android.package-archive"),
    ]
    for path, _ in assets:
        if not path.is_file():
            raise FileNotFoundError(f"Missing release artifact: {path}")

    dmg_sha256 = sha256_file(dmg_path)
    print(f"Preparing SideKick v{version} (DMG sha256: {dmg_sha256})")

    publish_github_release(version, title, body, assets, dry_run=args.dry_run)
    update_homebrew_cask(args.tap_dir, version, dmg_sha256, dry_run=args.dry_run)


if __name__ == "__main__":
    main()
