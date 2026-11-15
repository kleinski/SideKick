# SideKick Workspace Rules

## Versioning & Releases
- Single source of truth for the version is the root `VERSION` file (propagated via `./scripts/sync-version.sh`).
- Never overwrite an already-published Git tag or GitHub Release when adding new features or code changes; always bump `VERSION`, run `./scripts/sync-version.sh`, and publish a new version tag.

## Branding & Attribution
- All user-facing names, UI strings, virtual display names, and GitHub links (`https://github.com/kleinski/SideKick`) must use **SideKick**.
- Internal Android package names (`com.sidescreen.app`) and legacy protocol magics (`SS01`, `SSWE`, `sidescreen://` fallback) remain for backwards compatibility.
- Maintain clear attribution and gratitude (in first-person singular) to **Tran Vuong Quoc Dat** for the original [SideScreen](https://github.com/tranvuongquocdat/SideScreen) project under the MIT License.
