<a id="readme-top"></a>

<div align="center">

<img src="resources/logo/sidescreen-icon.png" alt="SideKick" width="128"/>

<h1>SideKick</h1>

<p><em>Turn your Android tablet into a true second display across multiple MacBooks — USB-C or wireless over WiFi with instant Multi-Mac Takeover</em></p>

<p>
  <a href="https://github.com/kleinski/SideKick/releases">
    <img src="https://img.shields.io/github/v/release/kleinski/SideKick?style=for-the-badge&color=007AFF" alt="Latest Release">
  </a>
  <a href="https://github.com/kleinski/SideKick/blob/main/LICENSE">
    <img src="https://img.shields.io/github/license/kleinski/SideKick?style=for-the-badge&color=34C759" alt="License">
  </a>
</p>

![Swift](https://img.shields.io/badge/Swift-FA7343?style=for-the-badge&logo=swift&logoColor=white)
![Kotlin](https://img.shields.io/badge/Kotlin-7F52FF?style=for-the-badge&logo=kotlin&logoColor=white)
![macOS](https://img.shields.io/badge/macOS_13+-000000?style=for-the-badge&logo=apple&logoColor=white)
![Android](https://img.shields.io/badge/Android_8+-3DDC84?style=for-the-badge&logo=android&logoColor=white)
![Universal Binary](https://img.shields.io/badge/Universal_Binary-Apple_Silicon_+_Intel-000000?style=for-the-badge&logo=apple&logoColor=white)

</div>

---

## Origin & Attribution: Original `SideScreen` vs. `SideKick`

**SideKick** is an independent fork of [**SideScreen**](https://github.com/tranvuongquocdat/SideScreen), originally created by [**Tran Vuong Quoc Dat**](https://github.com/tranvuongquocdat) ([sidescreen.dev](https://sidescreen.dev)).

> **🙏 A Huge Thank You to Tran Vuong Quoc Dat**
>
> All credit for the foundational architecture — the ultra-low-latency hardware-accelerated H.265 streaming pipeline, the native macOS `CGVirtualDisplay` integration, the Android `MediaCodec` decoder, and predictive touch input — goes to **Tran Vuong Quoc Dat**.
>
> I am deeply grateful for his outstanding engineering work on **SideScreen** and for his generous decision to release the entire project as open source under the **[MIT License](LICENSE)**. Without his craftsmanship and commitment to open source, **SideKick** would not exist. If you enjoy using SideKick, please consider [supporting his original work](#acknowledgements--upstream-support)!

While the original **SideScreen** provides an exceptional streaming engine for connecting an Android tablet to a single Mac, **SideKick** extends and re-engineers the macOS host and Android client specifically for **seamless wireless multi-Mac workflows** — allowing you to move one Android tablet effortlessly between two or more MacBooks.

### Comparison: What's from Original `SideScreen` vs. What's New in `SideKick`

| Capability | Original `SideScreen` (Upstream) | `SideKick` Fork (My Additions) |
|---|---|---|
| **Core Streaming Engine** | Hardware-accelerated H.265 (`VideoToolbox` → `MediaCodec`), sub-16ms USB / <30ms WiFi latency | Inherited from upstream |
| **Virtual Display & Input** | Native macOS `CGVirtualDisplay`, HiDPI (Retina 2×), 30–120 FPS, Gaming Boost, Touch Prediction | Inherited from upstream |
| **Headless Mac Support** | Launch at Login & Auto-start streaming (v0.11.0) | Inherited from upstream |
| **Wireless Pairing** | Single paired Mac (scanning a second Mac overwrites the first) | **Multi-Mac Pairing:** Pair once per MacBook; Android stores all paired Macs persistently |
| **IP Address Changes** | Requires re-scanning the QR code if the Mac's LAN IP changes | **Automatic Bonjour / mDNS (`_sidescreen._tcp`):** Dynamically discovers paired Macs and updates their IP addresses |
| **Smart Startup on Android** | Auto-reconnects to the single saved IP | **Smart Multi-Mac Connect:** Auto-connects if 1 paired Mac is online; opens a pre-filtered Mac picker if multiple paired Macs are online |
| **Switching Between Macs** | Manual disconnect + re-pair or manual IP swap | **Two-Way Instant Takeover (`⌘⇧T`):** Pull the tablet to your current Mac via HMAC-SHA256 UDP handover (`54322`) or switch Macs directly from the Android in-stream dialog |
| **Virtual Display Lifecycle** | Created when the streaming server starts | **Strictly On-Demand + Disconnect Tablet (`⌘⇧D`):** Display is created only when a tablet connects and destroyed immediately on disconnect so windows never get lost on an unseen monitor |
| **Resolution / HiDPI Changes** | Rebuilds server and drops active tablet connection | **Live Display Rebuild:** Switching resolution, HiDPI, refresh rate, or Portrait/Landscape rotation updates the virtual display in-place while keeping the tablet connected |
| **Wireless Security** | Plaintext token handshake (`SSWA`) & unencrypted TCP stream | **End-to-End Encrypted Wireless (`SSWE`):** Mutual HMAC-SHA256 challenge-response (token never sent in plaintext) + optional **AES-256-GCM** stream encryption (enabled by default) + live Android security status HUD (`🔒 AES-256` / `🔓 Off` / `🔌 USB`) |
| **Keyboard Forwarding** | Touch & mouse input only | **Hardware & On-Screen Keyboard Forwarding:** Forward Bluetooth/USB/cover keyboards and the Android soft keyboard (plus a vertical Special Keys & Mac shortcuts strip with sticky `⌘`/`⌥`/`⌃`/`⇧`, arrows, `Esc`/`Tab`/`Del`, and `⌘ Space`/`⌘ Tab`/`⌘⌥ Esc`) to macOS, gated by the Mac's **Keyboard Control** permission toggle |
| **macOS Menu Bar & UI** | Classic `NSMenu` dropdown + single scrolling settings window | **Native Menu Bar Popover (`NSPopover`) + 4-Tab Settings UI:** Left-click (or `⌘⇧W`) opens live metrics, Start/Stop, Takeover, Disconnect, Mode switch, live `Rotation` (`0°/90°/180°/270°`), and Touch/Keyboard/Encryption toggles; right-click opens the full dropdown menu |
| **macOS TCC Permissions** | Signed with upstream developer ID; manual TCC handling on custom builds | **Persistent Local Signing & One-Click TCC Reset:** Includes `./scripts/setup_signing_cert.sh` (`"SideKick Local Signing"`), automatic TCC reset on binary changes, and live Accessibility status + **Reset & Prompt** right inside *Display & Touch* |
| **Automation & Launcher** | GUI only | **URL Scheme, CLI & Vorssaint Integration:** Control via `sidekick://takeover`, `sidekick://disconnect`, `sidekick://toggle`, CLI flags, and automatic Vorssaint Quick Links |
| **App Identity & Updates** | `SideScreen.app` (`com.sidescreen.app`) with Sparkle & Play Store auto-update | **`SideKick.app` (`com.sidekick.app`):** Separate bundle/app ID with upstream auto-updaters disabled so custom builds are never overwritten |

---

## SideKick Highlights (Added in This Fork)

- **Multi-Mac Wireless Pairing & Bonjour Discovery (`_sidescreen._tcp`):** Pair once per MacBook via QR code. The Android app stores every paired MacBook persistently, discovers which Macs are currently online via mDNS/Bonjour, and automatically tracks changing DHCP IP addresses.
- **Two-Way Instant Takeover (`⌘⇧T`):** Switch the active tablet stream between MacBooks at any time:
  - **From Mac:** Click **Take Over Tablet** in the menu bar / Settings footer or press `⌘⇧T` (sends an HMAC-SHA256 authenticated UDP broadcast on port `54322`).
  - **From Android:** Open the in-stream settings overlay (`⚙`) while streaming and tap another paired MacBook to switch immediately.
- **End-to-End Wireless Encryption (`SSWE` / AES-256-GCM):** Wireless connections use a mutual HMAC-SHA256 challenge-response handshake (so the 256-bit pairing token is never sent in plaintext over the air) and encrypt all video frames, display configs, touch input, and keyboard events using hardware-accelerated **AES-256-GCM** with per-session ephemeral keys. The Android stats overlay (`SECURITY` column), in-stream settings banner, and connect toast display the active encryption state in real time.
- **Native macOS Menu Bar Popover (`NSPopover`):** Left-click the SideKick menu bar icon (or press `⌘⇧W`) for an instant quick-control popover showing live FPS, bitrate, resolution, Start/Stop, Takeover (`⌘⇧T`), Disconnect (`⌘⇧D`), USB/Wi-Fi switch, live `Rotation` (`0° / 90° / 180° / 270°`), and Touch / Keyboard / Encryption toggles. Right-click (or Control-click) opens the classic dropdown menu.
- **Tablet-to-Mac Keyboard Forwarding, Mac Actions & Zoom/Pan:** Use a Bluetooth/USB/cover keyboard or tap the floating keyboard icon on Android to type directly into macOS (including `⌘`, `⌥`, `⌃`, `⇧` shortcuts, navigation, `F1–F12`, and international characters). The top overlay also includes 1-tap Mac system actions (**Apps**, **Mission Control**, **Desktop**), **Zoom - / 1:1 / Zoom + / Fit** controls (plus pinch-to-zoom & pan), and a dedicated `⌘` button that opens a vertical Special Keys panel with one-shot sticky modifiers (`⌘`, `⌥`, `⌃`, `⇧`), `Esc`/`Tab`/`Del`/arrow keys, and 1-tap Mac shortcuts (`⌘ Space`, `⌘ Tab`, `⌘⌥ Esc`). Keyboard input can be blocked in one click from the Mac app (*Menu Bar Popover* or *Settings → Display & Touch*), and re-enabling it requires **Touch ID / macOS user password**.
- **On-Demand Virtual Display & Disconnect Tablet (`⌘⇧D`):** The macOS virtual display is only active while a tablet is actually streaming. Use **Disconnect Tablet** (`⌘⇧D`) to end the tablet session and remove the virtual display while keeping the Mac server listening in standby.
- **Live Resolution, HiDPI, Refresh Rate & Rotation Switching:** Change resolution, Retina/HiDPI mode, frame rate, or Portrait/Landscape rotation on the fly — the Mac rebuilds the virtual display and notifies the Android decoder without dropping your session.
- **URL Scheme, CLI & Vorssaint Command Bar Integration:**
  - **URL Scheme:** `sidekick://takeover`, `sidekick://disconnect`, `sidekick://toggle`, `sidekick://start`, `sidekick://stop`, `sidekick://settings`
  - **CLI Flags:** `/Applications/SideKick.app/Contents/MacOS/SideKick --takeover` (or `--disconnect`, `--toggle`, `--start`, `--stop`, `--settings`)
  - **Vorssaint Integration:** Automatically registers Quick Links when `/Applications/Vorssaint.app` is present (or re-register any time under *Settings → System & Status → Maintenance & Integrations*).

---

<div align="center">
  <img src="resources/screenshots/hero_screenshot.jpeg" alt="SideKick — Mac + Android tablet as second display" width="800"/>
</div>

---

## Core Features (from Original `SideScreen`)

### USB-C or Wireless
Two ways to connect, same picture quality. **USB-C** plugs in the cable for the lowest possible latency — `adb reverse` port forwarding is set up automatically. **Wireless** lets you scan a QR code from the Mac once and connect over WiFi (5 GHz strongly recommended). The auth token is generated locally and stays on your Mac; reset it any time to revoke access.

### Virtual Display
Creates a true hardware-backed virtual display on your Mac. Drag windows to your tablet like a real monitor — extending your desktop rather than mirroring.

<div align="center">
  <img src="resources/screenshots/feature_virtual_display.png" alt="Virtual Display in macOS Display Preferences" width="600"/>
</div>

### Ultra-Low Latency
Hardware-accelerated H.265 encoding on Mac and decoding on Android. Async pipeline architecture delivers frames in under 30ms.

<div align="center">
  <img src="resources/screenshots/android_performance.png" alt="Low Latency Streaming with Stats Overlay" width="700"/>
</div>

### Touch Support
Use your tablet's touchscreen to interact with macOS. Touch prediction compensates for network latency, making taps and drags feel natural.

### HiDPI (Retina) Support
Enable HiDPI mode to render at 2× resolution internally — text and icons are sharp at any logical resolution, just like a MacBook Retina display. Perfect for 2K/4K tablets.

### Gaming Mode
Enable Gaming Boost for optimized settings: 1 Gbps bitrate, ultra-low latency encoding, 120 FPS.

### Headless / Portable Mac
Run a Mac with no display of its own — a Mac Studio or Mini on the go, or a laptop in clamshell — using the tablet as its only screen. Enable **Launch at Login** and **Auto-start streaming on launch** under *Settings → Connection*, and the Mac boots straight into standby for your tablet.

<p align="right"><a href="#readme-top">↑ Back to top</a></p>

---

## Requirements

| | macOS Host | Android Client |
|---|---|---|
| **OS** | macOS 13 (Ventura)+ | Android 8.0 (API 26)+ |
| **Hardware** | Apple Silicon or Intel | H.265 hardware decoder |
| **USB mode** | USB-C port + `adb` (`brew install --cask android-platform-tools`) | USB-C cable + USB Debugging enabled |
| **Wireless mode** | Same WiFi network as the tablet (5 GHz recommended) | Camera (for QR scan) + Google Play Services (for ML Kit barcode) |

---

## Installation

### Homebrew (macOS)

Install or upgrade **SideKick** on macOS via my Homebrew Cask tap ([`kleinski/homebrew-sidekick`](https://github.com/kleinski/homebrew-sidekick)):

```bash
brew install --cask kleinski/sidekick/sidekick
```

To upgrade later:

```bash
brew upgrade --cask kleinski/sidekick/sidekick
```

> Quarantine attributes (`com.apple.quarantine`) are cleared automatically during Homebrew installation via `postflight_steps`.

### Download from SideKick GitHub Releases

Download the latest **SideKick** builds from [**GitHub Releases (`kleinski/SideKick`)**](https://github.com/kleinski/SideKick/releases):

- **macOS (`SideKick-<version>-mac-universal.dmg`):**
  1. Download the `.dmg`, open it, and drag **SideKick.app** to `/Applications`.
  2. Since SideKick is built as an independent open-source fork without an Apple Developer ID notarization certificate, clear the quarantine attribute before first launch if macOS Gatekeeper prompts you:
     ```bash
     xattr -cr /Applications/SideKick.app
     ```
- **Android (`SideKick-<version>-android-release.apk`):**
  - Download and install the `.apk` directly on your Android tablet, or install via ADB:
    ```bash
    adb install -r SideKick-1.2.13-android-release.apk
    ```
  - Because SideKick uses the package ID `com.sidekick.app`, it can be installed alongside the original Google Play `SideScreen` app without conflict.

> **Looking for the original single-Mac version?**
> The upstream project by Tran Vuong Quoc Dat is available on [Google Play](https://play.google.com/store/apps/details?id=com.sidescreen.app) and [tranvuongquocdat/SideScreen Releases](https://github.com/tranvuongquocdat/SideScreen/releases).

> **⚠️ ADB Required for USB Mode**
> For wired USB-C connections, the Mac app needs `adb` to set up port forwarding:
> ```bash
> brew install --cask android-platform-tools
> ```

<details>
<summary><strong>Build from source & persistent local code signing (recommended for macOS updates)</strong></summary>

```bash
git clone https://github.com/kleinski/SideKick.git
cd SideKick

# 1. Optional (once per Mac): create a persistent local code-signing certificate
#    so macOS remembers Screen Recording & Accessibility permissions across updates
./scripts/setup_signing_cert.sh

# 2. Build & install macOS app (/Applications/SideKick.app + universal .dmg)
./scripts/build_mac.sh
./scripts/install_mac.sh

# 3. Build & install Android APK
./scripts/build_android.sh
./scripts/install_android.sh
```
</details>

---

## Usage

### Wireless Multi-Mac Mode (SideKick Workflow)

1. Launch **SideKick** on each of your MacBooks → in the **Connection** tab, select **Wireless** and click **Start** (a QR code appears).
2. Open **SideKick** on your Android tablet → switch to the **Wireless** tab → tap **Scan QR Code** and scan each MacBook once.
3. **Automatic Connection & Switching:**
   - When only **one** paired MacBook is running SideKick on your WiFi, opening the Android app connects to it automatically.
   - When **multiple** paired MacBooks are online, the Android app highlights the online Macs (`● Online`) so you can pick which Mac to connect to.
   - **Switch Macs at any time:** Press `⌘⇧T` (or click **Takeover** in the Menu Bar Popover) on the MacBook you want to switch to, or open the in-stream settings (`⚙`) on the tablet and tap another paired Mac.
   - **Disconnect without stopping the server:** Press `⌘⇧D` (or click **Disconnect Tablet** in the Menu Bar Popover) to close the tablet session and remove the virtual display while keeping the Mac ready for the next connection.
   - **Type from your tablet:** Connect a hardware keyboard or tap the floating **Keyboard** icon next to `⚙` on Android to open the on-screen keyboard. Toggle **Keyboard Control** in the Mac Menu Bar Popover (or *Settings → Display & Touch*) to allow or block keyboard input.

### USB Mode (Lowest Latency)

1. Connect the tablet to your Mac via **USB-C**.
2. In **SideKick** on Mac → **Connection** tab → select **USB (Cable)** and click **Start**.
3. Open **SideKick** on the tablet → **USB** tab → tap **Connect**.

---

## Configuration

Settings in the macOS app are organized into four tabs:

| Tab | Settings & Actions |
|---|---|
| **Connection** | Mode (`USB` / `Wireless`), QR Code & Paired Token management, `Launch at Login`, `Auto-start streaming on launch`, `Startup mode` |
| **Display & Touch** | Resolution presets (`720p` to `8K` + custom), `HiDPI (Retina)` mode, `Enable Touch Input`, `Enable Keyboard Input`, `Accessibility Permission` status + `Reset & Prompt` (all display changes apply live without disconnecting the tablet) |
| **Stream & Quality** | `Refresh Rate` (30–120 Hz), `Target Bitrate` (20–5000 Mbps), `Encoding Latency`, `Gaming Boost` (1 Gbps, 120 Hz) |
| **System & Status** | Live connection & permission status, `Reset macOS Permissions (TCC)`, `Register Actions in Vorssaint` |

---

## Troubleshooting

<details>
<summary><strong>macOS permissions (Screen Recording / Accessibility) stop working after an update</strong></summary>

Open **SideKick Settings → System & Status → Maintenance & Integrations** and click **Reset Permissions**, then re-enable **SideKick** in **System Settings → Privacy & Security → Screen Recording** and **Accessibility**. To prevent macOS from invalidating permissions on future local builds, run `./scripts/setup_signing_cert.sh` once and mark `"SideKick Local Signing"` as *Always Trust* in Keychain Access.
</details>

<details>
<summary><strong>"Connection refused" on Android (USB mode)</strong></summary>

The Mac app sets up `adb reverse` automatically when streaming starts. Make sure `adb` is installed (`brew install --cask android-platform-tools`) and USB debugging is enabled on your tablet.
</details>

<details>
<summary><strong>Wireless: "Couldn't reach Mac" / connection times out</strong></summary>

- Both devices must be on the same WiFi network and subnet (5 GHz strongly recommended).
- Click **Start** on the Mac so the server and Bonjour service (`_sidescreen._tcp`) are active.
- Ensure macOS **Local Network** permission is granted for **SideKick**.
</details>

<details>
<summary><strong>Tablet goes black whenever an app is fullscreen on the Mac</strong></summary>

With **"Displays have separate Spaces"** turned off, macOS blanks every other display when an app enters fullscreen. Turn it on in **System Settings → Desktop & Dock → Mission Control → Displays have separate Spaces**, then log out and back in.
</details>

---

## Acknowledgements & Upstream Support

Special thanks and my sincere gratitude go to **[Tran Vuong Quoc Dat](https://github.com/tranvuongquocdat)** for creating **[SideScreen](https://github.com/tranvuongquocdat/SideScreen)** ([sidescreen.dev](https://sidescreen.dev)) and generously publishing his work under the permissive **[MIT License](LICENSE)**. Building a reliable, sub-16ms virtual display and H.265 streaming stack across macOS and Android is a remarkable technical achievement, and sharing it freely with the community made it possible for me to build **SideKick** on top of his solid foundation.

If **SideKick** or **SideScreen** is useful to your daily workflow, please consider supporting **Tran Vuong Quoc Dat** and his original project:

<div align="center">

[![Buy Me a Coffee](https://img.shields.io/badge/Buy%20Me%20a%20Coffee-FFDD00?style=for-the-badge&logo=buy-me-a-coffee&logoColor=black)](https://buymeacoffee.com/tranvuongqk)
[![GitHub Sponsors](https://img.shields.io/badge/GitHub%20Sponsors-EA4AAA?style=for-the-badge&logo=github-sponsors&logoColor=white)](https://github.com/sponsors/tranvuongquocdat)
[![VietQR](https://img.shields.io/badge/Vietnam-VietQR-DA251D?style=for-the-badge&logoColor=white)](https://sidescreen.dev/donate.html)

</div>

- **Original Project (`SideScreen`):** [**Tran Vuong Quoc Dat**](https://github.com/tranvuongquocdat) — [tranvuongquocdat/SideScreen](https://github.com/tranvuongquocdat/SideScreen) · [sidescreen.dev](https://sidescreen.dev)
- **SideKick Multi-Mac Fork:** Maintained by [**kleinski**](https://github.com/kleinski) — [kleinski/SideKick](https://github.com/kleinski/SideKick)

---

## License

[MIT License](LICENSE) — free for personal and commercial use.
