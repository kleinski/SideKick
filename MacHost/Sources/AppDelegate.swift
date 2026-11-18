import Cocoa
import SwiftUI
import Combine
import ApplicationServices
import os.log
@preconcurrency import ScreenCaptureKit

// Debug file logger - writes to /tmp/sidekick.log
func debugLog(_ message: String) {
    let timestamp = DateFormatter.localizedString(from: Date(), dateStyle: .none, timeStyle: .medium)
    let line = "[\(timestamp)] \(message)\n"
    print(message)
    if let data = line.data(using: .utf8) {
        let url = URL(fileURLWithPath: "/tmp/sidekick.log")
        if let handle = try? FileHandle(forWritingTo: url) {
            handle.seekToEndOfFile()
            handle.write(data)
            handle.closeFile()
        } else {
            try? data.write(to: url)
        }
    }
}

// MARK: - Gesture State Machine

enum GestureState {
    case idle
    case pending          // Touch down, waiting to determine gesture
    case scrolling        // 1-finger scroll
    case longPressReady   // Long press detected, waiting for drag or release
    case dragging         // Long press + drag (left mouse drag)
    case twoFingerScroll  // 2-finger scroll
    case pinching         // Pinch zoom
}

struct GestureThresholds {
    static let tapMaxDistance: CGFloat = 15
    static let tapMaxTime: UInt64 = 250_000_000       // 250ms
    static let doubleTapMaxTime: UInt64 = 400_000_000  // 400ms
    static let doubleTapMaxDistance: CGFloat = 20
    static let longPressTime: UInt64 = 500_000_000     // 500ms
    static let scrollSensitivity: CGFloat = 1.2
    static let pinchMinDistance: CGFloat = 20
    static let minTouchInterval: UInt64 = 8_000_000    // ~120Hz
}

class AppDelegate: NSObject, NSApplicationDelegate {
    var streamingServer: StreamingServer?
    var screenCapture: ScreenCapture?
    var virtualDisplayManager: VirtualDisplayManager?
    var settings = DisplaySettings()
    var settingsWindow: SettingsWindowController?
    var popoverController: MenuBarPopoverController?
    var contextMenu: NSMenu?
    var statusItem: NSStatusItem?
    let pairedDeviceStore = PairedDeviceStore()
    let deviceProfileStore = DeviceProfileStore()
    /// Profile key of the tablet currently streaming (nil when none, or when
    /// it is an older USB app that does not identify itself).
    private var activeProfileKey: String?
    /// True while prepareProfile copies a profile into `settings` and rebuilds
    /// the display: the observers that would restart the server or push a
    /// display config early stand down, the switch does that work itself.
    private var isSwitchingProfile = false
    /// Name of the wireless device currently streaming (nil when no wireless client is active).
    /// Used to roll its `lastConnected` timestamp forward every status refresh tick so the UI
    /// shows "just now" while connected and freezes at the disconnect moment afterward.
    private var currentWirelessDevice: String?
    private var cancellables = Set<AnyCancellable>()
    private var permissionCheckTimer: Timer?
    private var statusRefreshTimer: Timer?
    /// Reentrancy latch for startServer() — a second Start (double-clicked menu
    /// item, auto-start racing a manual click) must not build a second virtual
    /// display / server. Main-actor confined.
    private var isStartingServer = false
    /// The effective refresh rate the running pipeline was built with — lets
    /// the refresh-rate observer skip restarts that would change nothing.
    private var lastAppliedRefreshRate: Int?
    var isDaemonMode = false // Deprecated: keeping variable for ABI compatibility but unused
    /// Set to true only when the user explicitly triggers Quit (Menu Bar, ⌘Q, or Settings Power/Restart button).
    /// Prevents external window managers (e.g. Vorssaint "Quit on Close") from killing SideKick when closing Settings.
    var isExplicitQuitRequested = false
    var lastWindowCloseTime: Date = .distantPast

    func applicationDidFinishLaunching(_ notification: Notification) {
        print("✅ App launched")

        // Create menu bar item
        setupMenuBar()

        // Setup settings window
        setupSettingsWindow()

        // Setup settings observers
        setupSettingsObservers()

        // Listen for CLI / external launcher commands (e.g. from Vorssaint)
        DistributedNotificationCenter.default().addObserver(
            self,
            selector: #selector(handleExternalCommandNotification(_:)),
            name: NSNotification.Name("com.sidekick.app.command"),
            object: nil
        )

        // Automatically register SideKick actions in Vorssaint on first launch if Vorssaint.app is installed
        ensureVorssaintIntegrationIfInstalled()

        // Check permissions
        Task {
            await checkPermissions()
        }

        // Periodic status refresh for the per-mode checklist (ADB / WiFi / Listening IP).
        statusRefreshTimer = Timer.scheduledTimer(withTimeInterval: 2.0, repeats: true) { [weak self] _ in
            Task { @MainActor in
                self?.refreshStatusIndicators()
            }
        }
        // Initial refresh so the UI isn't blank for 2 seconds.
        Task { @MainActor in
            refreshStatusIndicators()
        }

        if #available(macOS 13.0, *) {
            if DaemonManager.shared.isEnabled {
                print("🚀 Launch at Login is enabled - starting silently in background")
                NSApp.setActivationPolicy(.accessory)
            } else {
                showSettings()
            }
        } else {
            showSettings()
        }

        // Declarative auto-start (no Mac interaction): start the server in the
        // chosen Startup mode if enabled. No blocking permission modal here —
        // it cannot be acted on when the Mac is headless.
        if settings.autoStartStreamingOnLaunch {
            settings.connectionMode = settings.startupMode
            Task {
                await self.checkPermissions()
                if self.settings.hasScreenRecordingPermission {
                    await self.startServer()
                } else {
                    debugLog("Auto-start skipped: Screen Recording permission not granted")
                }
            }
        }
    }

    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        if !flag {
            showSettings()
        }
        return true
    }

    func application(_ application: NSApplication, open urls: [URL]) {
        for url in urls {
            let scheme = url.scheme?.lowercased() ?? ""
            guard scheme == "sidescreen" || scheme == "sidekick" else { continue }
            let cmd = (url.host ?? url.path.trimmingCharacters(in: CharacterSet(charactersIn: "/"))).lowercased()
            handleExternalAction(cmd)
        }
    }

    @objc private func handleExternalCommandNotification(_ notification: Notification) {
        if let cmd = notification.object as? String {
            handleExternalAction(cmd.lowercased())
        }
    }

    private func handleExternalAction(_ action: String) {
        debugLog("⚡ External action received: \(action)")
        switch action {
        case "takeover":
            requestTabletTakeover()
        case "disconnect":
            disconnectTablet()
        case "toggle":
            toggleServerFromMenu()
        case "start":
            if !settings.isRunning {
                Task { @MainActor in await startServer() }
            }
        case "stop":
            if settings.isRunning {
                stopServer()
            }
        case "settings":
            showSettings()
        default:
            debugLog("Unknown external action: \(action)")
        }
    }

    @MainActor
    private func refreshStatusIndicators() {
        settings.adbInstalled = StatusDetector.adbInstalled()
        settings.wifiConnected = StatusDetector.wifiReachable()
        settings.listeningAddress = LANAddressResolver.primaryIPv4()

        let axTrusted = AXIsProcessTrusted()
        if axTrusted != settings.hasAccessibilityPermission {
            settings.hasAccessibilityPermission = axTrusted
            if axTrusted {
                accessibilityWarningShown = false
            }
        }

        // "Displays have separate Spaces" off → any fullscreen app blanks the
        // virtual display (#50). Nothing we can do about it from capture side;
        // surface it so the user knows why the tablet went black.
        let separateSpaces = NSScreen.screensHaveSeparateSpaces
        if separateSpaces != settings.displaysHaveSeparateSpaces {
            settings.displaysHaveSeparateSpaces = separateSpaces
            if !separateSpaces {
                debugLog("WARNING: 'Displays have separate Spaces' is OFF — fullscreen apps on the Mac will blank the tablet (#50)")
            }
        }

        // While a wireless client is actively streaming, keep its lastConnected
        // rolling forward so the UI shows "just now". On disconnect, the
        // onClientDisconnected handler clears currentWirelessDevice — from that
        // point lastConnected stays frozen at the disconnect moment, so the
        // "X minutes ago" label counts up correctly.
        if let name = currentWirelessDevice {
            pairedDeviceStore.upsert(name: name, lastConnected: Date())
        }

        let port = Int(settings.port)
        Task.detached { [weak self] in
            let devices = StatusDetector.usbDevices()
            // Every attached device needs its own reverse (see setupADBReverse).
            let reverseOK = !devices.isEmpty
                && devices.allSatisfy { StatusDetector.adbReverseConfigured(port: port, serial: $0) }
            await MainActor.run { [weak self] in
                guard let self = self else { return }
                
                let isConnected = !devices.isEmpty

                self.settings.usbDeviceConnected = isConnected
                self.settings.adbReverseConfigured = reverseOK

                // Self-healing USB bridge (level-triggered, not edge-triggered):
                // whenever we are in USB mode with the server running and a
                // device present but adb reverse missing, (re)establish it.
                // Covers replug, adb-server restart, etc. The server lifecycle
                // is NOT tied to device events — it stays up and the tablet
                // reconnects via its own connect button.
                if self.settings.connectionMode == .usb
                    && isConnected
                    && self.settings.isRunning
                    && !reverseOK {
                    debugLog("🔌 USB bridge missing while running — (re)establishing adb reverse")
                    Task { await self.setupADBReverse() }
                }
            }
        }
    }

    @MainActor
    private func handleConnectionModeChange(to mode: ConnectionMode) async {
        debugLog("Connection mode changed to: \(mode.rawValue)")
        // Disconnect any active client immediately (per spec §6 / fix #2).
        let wasRunning = settings.isRunning
        if wasRunning {
            stopServer()
        }
        if mode == .wireless {
            // Generate token if missing; the QR will reflect it.
            _ = WirelessAuth.loadOrCreate()
        }
        if wasRunning {
            await startServer()
        }
    }

    /// Check permissions on demand (called when settings window opens or manually)
    func refreshPermissions() {
        Task {
            await checkPermissions()
        }
    }

    func setupSettingsObservers() {
        // Observer cho gaming boost changes
        settings.$gamingBoost
            .dropFirst() // Skip initial value
            .sink { [weak self] gamingBoost in
                guard let self = self, self.settings.isRunning else { return }
                print("🎮 Gaming Boost \(gamingBoost ? "ENABLED" : "DISABLED")")
                self.screenCapture?.updateEncoderSettings(
                    bitrateMbps: self.settings.effectiveBitrate,
                    quality: self.settings.effectiveQuality,
                    gamingBoost: gamingBoost
                )
            }
            .store(in: &cancellables)

        // Observer cho bitrate/quality changes (chỉ khi không gaming boost)
        Publishers.CombineLatest(settings.$bitrate, settings.$quality)
            .dropFirst()
            .sink { [weak self] bitrate, quality in
                guard let self = self, self.settings.isRunning, !self.settings.gamingBoost else { return }
                print("⚙️ Settings updated: \(bitrate)Mbps, \(quality)")
                self.screenCapture?.updateEncoderSettings(
                    bitrateMbps: bitrate,
                    quality: quality,
                    gamingBoost: false
                )
            }
            .store(in: &cancellables)

        Publishers.CombineLatest3(settings.$rotation, settings.$flipHorizontal, settings.$flipVertical)
            .dropFirst()
            .sink { [weak self] rotation, flipHorizontal, flipVertical in
                // A profile switch sends the config itself, once the codec is known.
                guard let self = self, self.settings.isRunning, !self.isSwitchingProfile else { return }
                print("🔄 Display transform changed: \(rotation)°, h=\(flipHorizontal), v=\(flipVertical)")
                self.streamingServer?.updateDisplayTransform(rotation: rotation, flipHorizontal: flipHorizontal, flipVertical: flipVertical)
            }
            .store(in: &cancellables)

        // Observer cho touch enable/disable - propagate to streaming server so
        // incoming touch frames from the client are dropped early when off.
        settings.$touchEnabled
            .dropFirst()
            .sink { [weak self] enabled in
                self?.streamingServer?.touchEnabled = enabled
            }
            .store(in: &cancellables)

        // Propagate keyboard enable/disable to streaming server and connected tablet.
        settings.$keyboardEnabled
            .dropFirst()
            .sink { [weak self] enabled in
                self?.streamingServer?.setKeyboardEnabled(enabled)
            }
            .store(in: &cancellables)

        // Observer cho connection mode changes — restart server with new auth/ADB policy.
        settings.$connectionMode
            .dropFirst()
            .sink { [weak self] mode in
                guard let self = self else { return }
                Task { @MainActor in
                    await self.handleConnectionModeChange(to: mode)
                }
            }
            .store(in: &cancellables)

        // Observer cho resolution & HiDPI changes — rebuilds the virtual display
        // and capture pipeline in-place while keeping the connected tablet session up.
        settings.$resolution
            .dropFirst()
            .removeDuplicates()
            .sink { [weak self] resolution in
                guard let self = self, !self.isSwitchingProfile else { return }
                self.restartRunningServer(reason: "Resolution changed to \(resolution)")
            }
            .store(in: &cancellables)

        settings.$hiDPI
            .dropFirst()
            .removeDuplicates()
            .sink { [weak self] hiDPI in
                guard let self = self, !self.isSwitchingProfile else { return }
                self.restartRunningServer(reason: "HiDPI changed to \(hiDPI)")
            }
            .store(in: &cancellables)

        settings.$wirelessEncryption
            .dropFirst()
            .removeDuplicates()
            .sink { [weak self] enabled in
                guard let self = self else { return }
                self.streamingServer?.wirelessEncryptionEnabled = enabled
                debugLog("Wireless encryption toggled to \(enabled ? "AES-256-GCM" : "off")")
                if self.settings.isRunning && self.settings.clientConnected && self.settings.connectionMode == .wireless {
                    self.streamingServer?.disconnectCurrentClient()
                    DispatchQueue.main.asyncAfter(deadline: .now() + .milliseconds(250)) {
                        self.requestTabletTakeover()
                    }
                }
            }
            .store(in: &cancellables)

        // Refresh rate shapes the virtual display mode AND the capture
        // interval, so it needs the same rebuild as a resolution change.
        // Debounced: the custom-FPS slider (issue #41) emits continuously
        // while dragging — restart once the value settles. The effective-rate
        // guard skips no-op restarts (drag ends back on the applied value, or
        // Gaming Boost is pinning the rate to 120).
        settings.$refreshRate
            .dropFirst()
            .removeDuplicates()
            .debounce(for: .seconds(1.2), scheduler: RunLoop.main)
            .sink { [weak self] _ in
                guard let self = self else { return }
                Task { @MainActor in
                    let effective = self.settings.effectiveRefreshRate
                    guard effective != self.lastAppliedRefreshRate else { return }
                    self.restartRunningServer(reason: "Refresh rate changed to \(effective) Hz")
                }
            }
            .store(in: &cancellables)

        // Every settings change lands in the profile it belongs to (see
        // syncCurrentProfile). objectWillChange fires before the new value is
        // stored; the debounce reads it after.
        settings.objectWillChange
            .debounce(for: .milliseconds(300), scheduler: RunLoop.main)
            .sink { [weak self] _ in
                self?.syncCurrentProfile()
            }
            .store(in: &cancellables)
    }

    /// Rebuilds the virtual display and capture pipeline in-place while keeping
    /// the active client connection open (or triggers Takeover reconnect if needed).
    private func restartRunningServer(reason: String) {
        Task { @MainActor in
            guard self.settings.isRunning, self.settings.displayCreated, let server = self.streamingServer else { return }
            debugLog("\(reason) — rebuilding virtual display in-place for connected tablet")
            self.virtualDisplayManager?.saveDisplayPosition()
            self.syncCurrentProfile()
            do {
                try await self.rebuildDisplay(for: server)
                server.notifyDisplayRebuilt()
                if !self.settings.clientConnected && self.settings.connectionMode == .wireless {
                    self.requestTabletTakeover()
                }
            } catch {
                debugLog("❌ In-place display rebuild failed (\(error)) — restarting server and reconnecting tablet")
                self.stopServer()
                await self.startServer()
                if self.settings.connectionMode == .wireless {
                    self.requestTabletTakeover()
                }
            }
        }
    }

    func setupMenuBar() {
        statusItem = NSStatusBar.system.statusItem(withLength: NSStatusItem.variableLength)

        if let button = statusItem?.button {
            button.image = NSImage(systemSymbolName: "display.2", accessibilityDescription: "SideKick")
            button.target = self
            button.action = #selector(statusBarButtonClicked(_:))
            button.sendAction(on: [.leftMouseUp, .rightMouseUp])
        }

        // Context menu used for right-click / secondary click.
        let menu = NSMenu()
        menu.delegate = self
        menu.autoenablesItems = false
        self.contextMenu = menu

        // Dim the menu bar icon while the server is stopped — at-a-glance
        // state without opening the menu.
        settings.$isRunning
            .receive(on: DispatchQueue.main)
            .sink { [weak self] running in
                self?.statusItem?.button?.appearsDisabled = !running
            }
            .store(in: &cancellables)
    }

    @objc func statusBarButtonClicked(_ sender: NSStatusBarButton) {
        guard let event = NSApp.currentEvent else {
            togglePopover(sender)
            return
        }

        if event.type == .rightMouseUp || (event.type == .leftMouseUp && event.modifierFlags.contains(.control)) {
            popoverController?.close()
            if let contextMenu = contextMenu {
                contextMenu.update()
                contextMenu.popUp(positioning: nil, at: NSPoint(x: 0, y: sender.bounds.height + 4), in: sender)
            }
        } else {
            togglePopover(sender)
        }
    }

    @objc func toggleServerFromMenu() {
        if settings.isRunning {
            stopServer()
        } else {
            Task { [weak self] in
                await self?.startServer()
            }
        }
    }

    @objc func selectUSBMode() {
        guard settings.connectionMode != .usb else { return }
        settings.connectionMode = .usb
    }

    @objc func selectWirelessMode() {
        guard settings.connectionMode != .wireless else { return }
        settings.connectionMode = .wireless
    }

    @objc func requestTabletTakeover() {
        Task { @MainActor in
            if settings.connectionMode != .wireless {
                settings.connectionMode = .wireless
            }
            if !settings.isRunning {
                await startServer()
            }
            guard settings.isRunning else { return }
            let token = WirelessAuth.loadOrCreate()
            let hostId = WirelessAuth.loadOrCreateHostId()
            let macName = Host.current().localizedName ?? "Mac"
            let knownIPs = pairedDeviceStore.all().compactMap { $0.lastAddress }
            HandoverBroadcaster.broadcastTakeover(
                token: token,
                port: settings.port,
                hostId: hostId,
                macName: macName,
                knownClientAddresses: knownIPs
            )
        }
    }

    @objc func disconnectTablet() {
        Task { @MainActor in
            guard settings.isRunning, settings.clientConnected else { return }
            debugLog("🔌 User requested Disconnect Tablet — keeping server listening on port \(settings.port)")
            streamingServer?.disconnectCurrentClient()
        }
    }

    func setupSettingsWindow() {
        settingsWindow = SettingsWindowController(settings: settings)

        settings.onToggleServer = { [weak self] in
            guard let self else { return }
            if self.settings.isRunning {
                self.stopServer()
            } else {
                Task { [weak self] in
                    await self?.startServer()
                }
            }
        }
        settings.onRequestTakeover = { [weak self] in
            self?.requestTabletTakeover()
        }
        settings.onDisconnectTablet = { [weak self] in
            self?.disconnectTablet()
        }

        // Menu bar popover with quick controls
        popoverController = MenuBarPopoverController(settings: settings) { [weak self] in
            self?.showSettings()
        }
    }

    @objc func togglePopover(_ sender: Any? = nil) {
        guard let button = statusItem?.button else { return }
        popoverController?.toggle(relativeTo: button)
    }

    @objc func showSettings() {
        NSApp.setActivationPolicy(.regular)
        settingsWindow?.showWindow(nil)
        settingsWindow?.window?.makeKeyAndOrderFront(nil)
        NSApp.activate(ignoringOtherApps: true)
    }

    @objc func closeSettingsWindowFromMenu() {
        closeSettingsWindowToBackground()
    }

    func closeSettingsWindowToBackground() {
        lastWindowCloseTime = Date()
        settingsWindow?.window?.orderOut(nil)
        // Run as a menu bar utility (no Dock icon) while Settings is closed so
        // window managers like Vorssaint "Quit on Close" don't treat it as an idle Dock app.
        NSApp.setActivationPolicy(.accessory)
        debugLog("Settings window closed — SideKick continues running in menu bar")
    }

    @objc func requestQuit(_ sender: Any?) {
        isExplicitQuitRequested = true
        NSApp.terminate(sender)
    }

    private func ensureVorssaintIntegrationIfInstalled() {
        guard FileManager.default.fileExists(atPath: "/Applications/Vorssaint.app") else { return }
        let flagKey = "vorssaintLinksRegisteredV1"
        guard !UserDefaults.standard.bool(forKey: flagKey) else { return }
        UserDefaults.standard.set(true, forKey: flagKey)
        registerInVorssaint()
    }

    @objc func registerInVorssaint() {
        guard let scriptPath = Bundle.main.path(forResource: "register_vorssaint", ofType: "sh") else {
            debugLog("register_vorssaint.sh not found in app bundle")
            return
        }
        Task.detached {
            let proc = Process()
            proc.executableURL = URL(fileURLWithPath: "/usr/bin/env")
            proc.arguments = ["python3", scriptPath]
            try? proc.run()
            proc.waitUntilExit()
            debugLog("✅ Vorssaint integration script completed with status \(proc.terminationStatus)")
        }
    }

    func checkPermissions() async {
        let version = ProcessInfo.processInfo.operatingSystemVersion
        debugLog("checkPermissions — macOS \(version.majorVersion).\(version.minorVersion).\(version.patchVersion)")
        let fingerprint = Self.currentExecutableBuildFingerprint()

        // Check Screen Recording permission using CoreGraphics API
        let hasScreenCapture = CGPreflightScreenCaptureAccess()
        await MainActor.run {
            settings.hasScreenRecordingPermission = hasScreenCapture
        }
        if hasScreenCapture {
            debugLog("Screen recording permission granted (CGPreflight)")
            UserDefaults.standard.set(fingerprint, forKey: "lastTCCScreenCaptureFingerprint")

            // On macOS 26+, also verify ScreenCaptureKit is actually functional
            if version.majorVersion >= 26 {
                do {
                    let content = try await SCShareableContent.excludingDesktopWindows(false, onScreenWindowsOnly: false)
                    debugLog("SCShareableContent verification OK — \(content.displays.count) displays found")
                } catch {
                    debugLog("WARNING: CGPreflight OK but SCShareableContent failed on macOS 26: \(error.localizedDescription)")
                    debugLog("CGDisplayStream fallback will likely activate at capture time")
                }
            }
        } else {
            debugLog("Screen recording permission not granted yet")
            // Automatically clear any stale TCC entry from an older build so the user
            // never has to manually delete the old row in System Settings.
            if UserDefaults.standard.string(forKey: "lastTCCScreenCaptureFingerprint") != fingerprint {
                UserDefaults.standard.set(fingerprint, forKey: "lastTCCScreenCaptureFingerprint")
                Self.runTCCReset(service: "ScreenCapture")
            }
            CGRequestScreenCaptureAccess()
        }

        // Check Accessibility permission (required for touch/mouse injection)
        await checkAccessibilityPermission()
    }

    func checkAccessibilityPermission() async {
        let trusted = AXIsProcessTrusted()
        let fingerprint = Self.currentExecutableBuildFingerprint()
        await MainActor.run {
            settings.hasAccessibilityPermission = trusted
        }
        if trusted {
            print("✅ Accessibility permission granted")
            UserDefaults.standard.set(fingerprint, forKey: "lastTCCAccessibilityFingerprint")
        } else {
            print("⚠️  Accessibility permission not granted - touch control will not work")
            if UserDefaults.standard.string(forKey: "lastTCCAccessibilityFingerprint") != fingerprint {
                UserDefaults.standard.set(fingerprint, forKey: "lastTCCAccessibilityFingerprint")
                Self.runTCCReset(service: "Accessibility")
            }
        }
    }

    private static func currentExecutableBuildFingerprint() -> String {
        guard let execPath = Bundle.main.executablePath,
              let attrs = try? FileManager.default.attributesOfItem(atPath: execPath) else {
            return AppInfo.version
        }
        let mtime = (attrs[.modificationDate] as? Date)?.timeIntervalSince1970 ?? 0
        let size = (attrs[.size] as? NSNumber)?.int64Value ?? 0
        return "\(AppInfo.version)-\(Int64(mtime))-\(size)"
    }

    private static func runTCCReset(service: String) {
        let bundleId = Bundle.main.bundleIdentifier ?? "com.sidekick.app"
        debugLog("🧹 Running tccutil reset \(service) \(bundleId) to remove stale permission entries")
        let proc = Process()
        proc.executableURL = URL(fileURLWithPath: "/usr/bin/tccutil")
        proc.arguments = ["reset", service, bundleId]
        try? proc.run()
        proc.waitUntilExit()
    }

    @objc func resetPermissionsAndReprompt() {
        Self.runTCCReset(service: "ScreenCapture")
        Self.runTCCReset(service: "Accessibility")
        accessibilityWarningShown = false
        _ = CGRequestScreenCaptureAccess()
        Task { @MainActor in
            self.promptAccessibilityPermission()
            await self.checkPermissions()
        }
    }

    @objc func resetAccessibilityPermissionAndReprompt() {
        Self.runTCCReset(service: "Accessibility")
        accessibilityWarningShown = false
        Task { @MainActor in
            self.promptAccessibilityPermission()
            await self.checkAccessibilityPermission()
        }
    }

    @MainActor
    func promptAccessibilityPermission() {
        // This will show the system prompt to grant Accessibility permission
        let options = [kAXTrustedCheckOptionPrompt.takeUnretainedValue() as String: true] as CFDictionary
        let trusted = AXIsProcessTrustedWithOptions(options)
        settings.hasAccessibilityPermission = trusted

        if !trusted {
            print("⚠️  User needs to grant Accessibility permission in System Settings")
        }
    }

    /// Setup ADB reverse port forwarding for USB connection
    func setupADBReverse() async {
        let port = settings.port
        print("🔌 Setting up ADB reverse for port \(port)...")
        debugLog("🔌 setupADBReverse() invoked for port \(port)...")

        await Task.detached(priority: .utility) {
            guard let adbPath = StatusDetector.adbExecutablePath() else {
                print("⚠️  ADB not found - USB connection may not work")
                print("💡 Install Android SDK or run manually: adb reverse tcp:\(port) tcp:\(port)")
                return
            }
            print("📱 Found ADB at: \(adbPath)")

            // One reverse per attached device: a bare `adb reverse` fails with
            // "more than one device" as soon as a second tablet is plugged in.
            // Devices still waiting for USB-debugging authorization are not
            // listed yet; refreshStatusIndicators re-runs this once they are.
            let serials = StatusDetector.usbDevices()
            guard !serials.isEmpty else {
                print("💡 No authorized Android device found — plug in via USB and allow debugging")
                return
            }
            for serial in serials {
                if StatusDetector.adbReverseConfigured(port: Int(port), serial: serial) {
                    debugLog("ADB reverse already configured for \(serial)")
                    continue
                }
                await Self.reverseWithRetry(adbPath: adbPath, serial: serial, port: port)
            }
        }.value
    }

    /// `adb -s <serial> reverse tcp:P tcp:P`, retried up to 3 times — handles
    /// the first-install authorization delay.
    private static func reverseWithRetry(adbPath: String, serial: String, port: UInt16) async {
        for attempt in 1...3 {
            let process = Process()
            process.executableURL = URL(fileURLWithPath: adbPath)
            process.arguments = ["-s", serial, "reverse", "tcp:\(port)", "tcp:\(port)"]

            let pipe = Pipe()
            process.standardOutput = pipe
            process.standardError = pipe

            do {
                try process.run()
                process.waitUntilExit()

                let data = pipe.fileHandleForReading.readDataToEndOfFile()
                let output = String(data: data, encoding: .utf8) ?? ""

                if process.terminationStatus == 0 {
                    print("✅ ADB reverse setup successful for \(serial): tcp:\(port) -> tcp:\(port)")
                    return
                } else {
                    print("⚠️  ADB reverse attempt \(attempt)/3 failed for \(serial): \(output.trimmingCharacters(in: .whitespacesAndNewlines))")
                    if attempt < 3 {
                        try? await Task.sleep(nanoseconds: 1_000_000_000)
                    }
                }
            } catch {
                print("⚠️  Failed to run ADB (attempt \(attempt)/3): \(error.localizedDescription)")
                if attempt < 3 {
                    try? await Task.sleep(nanoseconds: 1_000_000_000)
                }
            }
        }

        print("💡 Make sure Android device \(serial) is connected via USB with debugging enabled")
    }

    @MainActor
    func showPermissionAlert() {
        let version = ProcessInfo.processInfo.operatingSystemVersion
        let isMacOS26 = version.majorVersion >= 26

        let alert = NSAlert()
        if isMacOS26 {
            alert.messageText = "Screen & System Audio Recording Permission Required"
            alert.informativeText = "Please grant Screen & System Audio Recording permission in System Settings > Privacy & Security."
        } else {
            alert.messageText = "Screen Recording Permission Required"
            alert.informativeText = "Please grant Screen Recording permission in System Settings > Privacy & Security."
        }
        alert.alertStyle = .warning
        alert.addButton(withTitle: "Open System Settings")
        alert.addButton(withTitle: "Later")

        let response = alert.runModal()
        if response == .alertFirstButtonReturn {
            NSWorkspace.shared.open(URL(string: "x-apple.systempreferences:com.apple.preference.security?Privacy_ScreenCapture")!)
        }
    }

    func startServer() async {
        let canStart = await MainActor.run { () -> Bool in
            guard !isStartingServer, !settings.isRunning else { return false }
            isStartingServer = true
            return true
        }
        guard canStart else {
            debugLog("startServer() ignored — already starting or already running")
            return
        }
        defer {
            Task { @MainActor [weak self] in self?.isStartingServer = false }
        }
        debugLog("🚀 startServer() invoked. Check permission: \(settings.hasScreenRecordingPermission)")
        guard settings.hasScreenRecordingPermission else {
            debugLog("❌ startServer aborted: Missing Screen Recording permission")
            await showPermissionAlert()
            return
        }

        do {
            // Prepare virtual display manager, but do NOT create the virtual display yet!
            // The virtual display is created on-demand in prepareProfile() only when a
            // tablet actually connects, and destroyed when it disconnects — preventing
            // macOS from moving windows onto an unseen second desktop while waiting.
            virtualDisplayManager = VirtualDisplayManager()
            await MainActor.run {
                settings.displayCreated = false
            }

            if settings.connectionMode == .usb {
                await self.setupADBReverse()
            } else {
                debugLog("Wireless mode: skipping ADB setup")
            }

            // Setup server
            streamingServer = StreamingServer(port: settings.port)
            streamingServer?.touchEnabled = settings.touchEnabled
            streamingServer?.keyboardEnabled = settings.keyboardEnabled
            streamingServer?.wirelessEncryptionEnabled = settings.wirelessEncryption
            if settings.connectionMode == .wireless {
                streamingServer?.expectedAuthToken = WirelessAuth.loadOrCreate()
                streamingServer?.bonjourHostId = WirelessAuth.loadOrCreateHostId()
                configurePairingCode(on: streamingServer)
                streamingServer?.onWirelessClientPaired = { [weak self] deviceName, clientAddress in
                    guard let self = self else { return }
                    Task { @MainActor in
                        self.currentWirelessDevice = deviceName
                        self.settings.currentWirelessDevice = deviceName
                        self.pairedDeviceStore.upsert(name: deviceName, lastConnected: Date(), lastAddress: clientAddress)
                    }
                }
            }
            streamingServer?.onPrepareForClient = { [weak self] key, name in
                guard let self = self else { return true }
                Task { @MainActor in await self.prepareProfile(key: key, name: name) }
                return false
            }
            streamingServer?.onClientConnected = { [weak self] in
                guard let self = self else { return }
                self.screenCapture?.requestKeyframeOrReplayCachedFrame(force: true)
                Task { @MainActor in
                    self.settings.clientConnected = true
                }
            }
            // Runs synchronously on the server's network queue BEFORE the
            // display config is sent, so the config below carries the right
            // dimensions for the negotiated codec.
            streamingServer?.onCodecNegotiated = { [weak self] codec in
                guard let self = self, let capture = self.screenCapture else { return }
                capture.negotiate(
                    codec: codec,
                    clientLimit: self.streamingServer?.clientDecodeLimits,
                    clientLimitTable: self.streamingServer?.clientDecodeLimitTable
                )
                let enc = capture.encodeSize(for: codec)
                // Whatever the codec negotiation settled on, this is what the
                // stream's SPS will carry, so it is what the client must size
                // its decoder for.
                let desktop = self.settings.resolutionSize
                self.streamingServer?.setDesktopSize(width: desktop.width, height: desktop.height)
                self.streamingServer?.setDisplaySize(width: enc.width, height: enc.height, rotation: self.settings.rotation, flipHorizontal: self.settings.flipHorizontal, flipVertical: self.settings.flipVertical)
            }
            streamingServer?.onKeyframeRequested = { [weak self] force in
                self?.screenCapture?.requestKeyframeOrReplayCachedFrame(force: force)
            }

            streamingServer?.onClientDisconnected = { [weak self] in
                guard let self = self else { return }
                Task { @MainActor in
                    self.settings.clientConnected = false
                    self.closeActiveProfile()
                    self.destroyActiveDisplayAndCapture()
                    // Final lastConnected snapshot at the disconnect moment, then
                    // freeze (currentWirelessDevice = nil stops the rolling update
                    // in refreshStatusIndicators).
                    if let name = self.currentWirelessDevice {
                        self.pairedDeviceStore.upsert(name: name, lastConnected: Date())
                        self.currentWirelessDevice = nil
                        self.settings.currentWirelessDevice = nil
                    }
                }
            }

            streamingServer?.onTouchEvent = { [weak self] x, y, action, pointerCount, x2, y2 in
                self?.handleTouch(x: x, y: y, action: action, pointerCount: pointerCount, x2: x2, y2: y2)
            }

            streamingServer?.onKeyboardEvent = { [weak self] keyCode, isDown, modifiers in
                guard let self = self, self.settings.keyboardEnabled else { return }
                guard AXIsProcessTrusted() else {
                    self.noteMissingAccessibilityForInput()
                    return
                }
                KeyboardInjector.injectKeyEvent(androidKeyCode: keyCode, isDown: isDown, modifiers: modifiers)
            }

            streamingServer?.onKeyboardText = { [weak self] text in
                guard let self = self, self.settings.keyboardEnabled else { return }
                guard AXIsProcessTrusted() else {
                    self.noteMissingAccessibilityForInput()
                    return
                }
                KeyboardInjector.injectText(text)
            }

            streamingServer?.onKeyboardBackspace = { [weak self] count in
                guard let self = self, self.settings.keyboardEnabled else { return }
                guard AXIsProcessTrusted() else {
                    self.noteMissingAccessibilityForInput()
                    return
                }
                KeyboardInjector.injectBackspace(count: count)
            }

            streamingServer?.onStats = { [weak self] fps, mbps in
                let captured = self
                Task { @MainActor in
                    captured?.settings.currentFPS = fps
                    captured?.settings.currentBitrate = mbps
                }
            }

            guard let server = streamingServer else {
                throw NSError(
                    domain: "SideKick.Startup",
                    code: 2,
                    userInfo: [NSLocalizedDescriptionKey: "The streaming server could not be created."]
                )
            }
            try await server.start()

            await MainActor.run {
                settings.isRunning = true
            }

            print("✅ Server listening on port \(settings.port) (virtual display will be created when tablet connects)")
        } catch {
            print("❌ Failed to start: \(error)")
            await MainActor.run {
                self.tearDownServerResources(saveDisplayPosition: false)

                let alert = NSAlert()
                alert.messageText = "Failed to Start Server"
                alert.informativeText = error.localizedDescription
                alert.alertStyle = .critical
                alert.runModal()
            }
        }
    }

    /// Create the virtual display from the current settings, in extend mode.
    private func createVirtualDisplay(on manager: VirtualDisplayManager) throws {
        let size = settings.resolutionSize
        try manager.createDisplay(
            width: size.width,
            height: size.height,
            refreshRate: settings.refreshRate,
            hiDPI: settings.hiDPI,
            name: "SideKick"
        )

        // Disable mirror mode (may fail if already in extend mode)
        do {
            try manager.disableMirrorMode()
        } catch {
            // Not critical - continue anyway
        }
    }

    /// Capture for `manager`'s display, already positioned.
    private func makeCapture(for manager: VirtualDisplayManager) async throws -> ScreenCapture {
        // Verify display is registered in the system
        if !manager.verifyDisplayRegistered() {
            debugLog("WARNING: Virtual display not found in online display list — capture may fail")
        }

        guard let displayID = manager.displayID else {
            throw NSError(
                domain: "SideKick.Startup",
                code: 1,
                userInfo: [NSLocalizedDescriptionKey: "The virtual display was created without a display ID."]
            )
        }
        let capture = try await ScreenCapture()
        capture.onCaptureMethodChanged = { [weak self] method in
            guard let self = self else { return }
            debugLog("Capture method: \(method)")
            Task { @MainActor in
                self.settings.captureMethod = method
            }
        }
        lastAppliedRefreshRate = settings.effectiveRefreshRate
        try await capture.setupForVirtualDisplay(displayID, refreshRate: settings.effectiveRefreshRate)
        return capture
    }

    private func configureDisplayGeometry(on server: StreamingServer?, for manager: VirtualDisplayManager) {
        guard let displayID = manager.displayID else { return }
        // displayConfig carries the ENCODED size, always. The client sizes and
        // selects its decoder from it, so a friendlier number here makes it
        // check capabilities against a resolution it will never receive. The
        // logical desktop travels separately, for display only.
        let size = settings.resolutionSize
        let initialEncode = ScreenCapture.physicalSize(for: displayID)
        server?.setDesktopSize(width: size.width, height: size.height)
        server?.setDisplaySize(width: initialEncode.width, height: initialEncode.height, rotation: settings.rotation, flipHorizontal: settings.flipHorizontal, flipVertical: settings.flipVertical)
    }

    private func startStreaming(to server: StreamingServer) {
        screenCapture?.startStreaming(
            to: server,
            bitrateMbps: settings.effectiveBitrate,
            quality: settings.effectiveQuality,
            gamingBoost: settings.gamingBoost,
            frameRate: settings.effectiveRefreshRate
        )
    }

    // MARK: - Per-tablet profiles

    /// A tablet identified itself and its stream is held until this returns
    /// it to the server. Its profile is applied first: nothing to do when it
    /// matches what is running, the display just moves when only the position
    /// differs, and the display and capture are rebuilt — with the client
    /// still connected — when anything that shapes them differs. A tablet
    /// without a profile starts from the current settings, i.e. the last
    /// profile used.
    @MainActor
    private func prepareProfile(key: String?, name: String) async {
        guard let server = streamingServer else { return }
        defer { server.resumeProtocolStartup() }

        let displayWasCreated = settings.displayCreated && screenCapture != nil

        // Flush whatever the current settings belong to (the last tablet, or
        // one that never disconnected cleanly) before they are replaced.
        if displayWasCreated {
            virtualDisplayManager?.saveDisplayPosition()
        }
        syncCurrentProfile()

        let current = settings.profileSettings
        var needsRebuild = !displayWasCreated

        if let key = key {
            let stored = deviceProfileStore.profile(id: key)
            let displayName = name.isEmpty ? (stored?.name ?? "Tablet") : name
            activeProfileKey = key
            deviceProfileStore.save(DeviceProfile(id: key, name: displayName, lastConnected: Date(), settings: stored?.settings ?? current))

            if let target = stored?.settings, target != current {
                isSwitchingProfile = true
                settings.apply(target)
                isSwitchingProfile = false
                if target.displayShape != current.displayShape {
                    needsRebuild = true
                } else if displayWasCreated && (target.positionX != current.positionX || target.positionY != current.positionY) {
                    virtualDisplayManager?.restoreDisplayPosition()
                }
            }
        }

        if needsRebuild {
            debugLog("Creating/rebuilding virtual display on client connect (\(settings.resolution) @ \(settings.effectiveRefreshRate)Hz)")
            lastAppliedRefreshRate = settings.effectiveRefreshRate
            do {
                try await rebuildDisplay(for: server)
            } catch {
                debugLog("❌ Virtual display creation failed: \(error) — stopping server")
                stopServer()
            }
        }
    }

    /// Swap the virtual display and capture for ones built from the current
    /// settings while `server` and its client stay up.
    @MainActor
    private func rebuildDisplay(for server: StreamingServer) async throws {
        let manager = virtualDisplayManager ?? VirtualDisplayManager()
        virtualDisplayManager = manager
        screenCapture?.stopStreaming()
        screenCapture = nil

        try createVirtualDisplay(on: manager)
        settings.displayCreated = true
        try? await Task.sleep(nanoseconds: 500_000_000)
        manager.restoreDisplayPosition()
        let capture = try await makeCapture(for: manager)
        guard streamingServer === server else { return }
        screenCapture = capture
        configureDisplayGeometry(on: server, for: manager)
        startStreaming(to: server)
    }

    @MainActor
    private func destroyActiveDisplayAndCapture() {
        guard settings.displayCreated || screenCapture != nil else { return }
        debugLog("Tablet disconnected — destroying virtual display so windows return to main screen")
        virtualDisplayManager?.saveDisplayPosition()
        screenCapture?.stopStreaming()
        screenCapture = nil
        virtualDisplayManager?.destroyDisplay()
        settings.displayCreated = false
        settings.currentFPS = 0
        settings.currentBitrate = 0
    }

    /// The streaming tablet went away: keep where its display ended up and
    /// what it was set to, then stop tracking it.
    private func closeActiveProfile() {
        guard activeProfileKey != nil else { return }
        // Mid-switch the display is a fresh one not yet moved into place.
        if !isSwitchingProfile {
            virtualDisplayManager?.saveDisplayPosition()
        }
        syncCurrentProfile()
        activeProfileKey = nil
    }

    /// Copy the current settings into the profile they belong to: the
    /// streaming tablet, or — while none is — the one used last, so a change
    /// made between sessions is not undone when that tablet comes back.
    private func syncCurrentProfile() {
        guard let key = activeProfileKey ?? deviceProfileStore.mostRecent()?.id else { return }
        deviceProfileStore.updateSettings(settings.profileSettings, for: key)
    }

    /// Code pairing (issue #35): issue a fresh one-time code for this wireless
    /// session. The server consumes/invalidates the code atomically on its
    /// network queue (one-time use, 5-attempt budget); these callbacks only
    /// install the replacement.
    private func configurePairingCode(on server: StreamingServer?) {
        guard let server = server else { return }
        rotatePairingCode(on: server)
        server.pairingMacName = Host.current().localizedName ?? "Mac"
        server.onPairingSuccess = { [weak self, weak server] deviceName, clientAddress in
            guard let self = self else { return }
            self.rotatePairingCode(on: server)
            Task { @MainActor in
                self.pairedDeviceStore.upsert(name: deviceName, lastConnected: Date(), lastAddress: clientAddress)
            }
        }
        server.onPairingCodeExhausted = { [weak self, weak server] in
            debugLog("Pairing attempt budget exhausted — rotating code")
            self?.rotatePairingCode(on: server)
        }
    }

    private func rotatePairingCode(on server: StreamingServer?) {
        let code = PairingCode.generate()
        server?.expectedPairingCode = code
        Task { @MainActor in
            // A rotation queued just before Stop must not resurrect the code
            // in the UI after stopServer() cleared it.
            guard self.settings.isRunning || self.isStartingServer else { return }
            self.settings.pairingCode = code
        }
    }

    private func tearDownServerResources(saveDisplayPosition: Bool) {
        if saveDisplayPosition {
            virtualDisplayManager?.saveDisplayPosition()
        }
        settings.pairingCode = nil
        streamingServer?.expectedPairingCode = nil

        screenCapture?.stopStreaming()
        streamingServer?.stop()
        virtualDisplayManager?.destroyDisplay()

        screenCapture = nil
        streamingServer = nil
        virtualDisplayManager = nil
        currentWirelessDevice = nil

        settings.isRunning = false
        settings.displayCreated = false
        settings.clientConnected = false
        settings.currentWirelessDevice = nil
        settings.currentFPS = 0
        settings.currentBitrate = 0
    }

    func stopServer() {
        tearDownServerResources(saveDisplayPosition: true)
        syncCurrentProfile()
        activeProfileKey = nil

        print("⏹️ Server stopped")
    }

    // MARK: - Gesture Properties

    private let eventSource = CGEventSource(stateID: .hidSystemState)
    private var accessibilityWarningShown = false
    private var gestureState: GestureState = .idle
    private var lastTouchTime: UInt64 = 0

    // Touch tracking
    private var touchStartPosition: CGPoint = .zero
    private var touchLastPosition: CGPoint = .zero
    private var touchStartTime: UInt64 = 0
    private var touchLastMoveTime: UInt64 = 0
    private var lastScrollDeltaX: CGFloat = 0
    private var lastScrollDeltaY: CGFloat = 0

    // Double tap tracking
    private var lastTapTime: UInt64 = 0
    private var lastTapPosition: CGPoint = .zero

    // Long press timer
    private var longPressTimer: DispatchWorkItem?

    // 2-finger tracking
    private var initialPinchDistance: CGFloat = 0
    private var lastPinchDistance: CGFloat = 0

    // Momentum scrolling
    private var momentumTimer: Timer?
    private var momentumVelocityX: CGFloat = 0
    private var momentumVelocityY: CGFloat = 0
    private var lastMomentumPosition: CGPoint = .zero

    // MARK: - Touch Entry Point

    func noteMissingAccessibilityForInput() {
        if !accessibilityWarningShown {
            accessibilityWarningShown = true
            debugLog("⚠️ Accessibility not granted - input ignored")
            Task { @MainActor in
                settings.hasAccessibilityPermission = false
            }
        }
    }

    func handleTouch(x: Float, y: Float, action: Int, pointerCount: Int = 1, x2: Float = 0, y2: Float = 0) {
        guard settings.touchEnabled else { return }

        if !AXIsProcessTrusted() {
            noteMissingAccessibilityForInput()
            return
        }

        guard let displayID = virtualDisplayManager?.displayID else { return }
        let bounds = CGDisplayBounds(displayID)

        let p1 = CGPoint(
            x: bounds.origin.x + CGFloat(x) * bounds.width,
            y: bounds.origin.y + CGFloat(y) * bounds.height
        )
        let p2 = CGPoint(
            x: bounds.origin.x + CGFloat(x2) * bounds.width,
            y: bounds.origin.y + CGFloat(y2) * bounds.height
        )

        if pointerCount >= 2 {
            handleTwoFingerTouch(p1: p1, p2: p2, action: action)
        } else {
            handleOneFingerTouch(at: p1, action: action)
        }
    }

    // MARK: - 1-Finger Gesture State Machine

    private func handleOneFingerTouch(at point: CGPoint, action: Int) {
        switch action {
        case 0: oneFingerDown(at: point)
        case 1: oneFingerMove(to: point)
        case 2: oneFingerUp(at: point)
        default: break
        }
    }

    private func oneFingerDown(at point: CGPoint) {
        stopMomentumScroll()
        cancelLongPressTimer()

        touchStartPosition = point
        touchLastPosition = point
        touchStartTime = DispatchTime.now().uptimeNanoseconds
        touchLastMoveTime = touchStartTime
        gestureState = .pending

        // Move cursor to touch position (absolute)
        moveCursor(to: point)

        // Start long press timer
        let timer = DispatchWorkItem { [weak self] in
            guard let self, self.gestureState == .pending else { return }
            self.gestureState = .longPressReady
        }
        longPressTimer = timer
        DispatchQueue.main.asyncAfter(
            deadline: .now() + .nanoseconds(Int(GestureThresholds.longPressTime)),
            execute: timer
        )
    }

    private func oneFingerMove(to point: CGPoint) {
        let now = DispatchTime.now().uptimeNanoseconds
        if now - lastTouchTime < GestureThresholds.minTouchInterval { return }
        lastTouchTime = now

        let deltaX = point.x - touchLastPosition.x
        let deltaY = point.y - touchLastPosition.y
        let totalDistance = hypot(point.x - touchStartPosition.x, point.y - touchStartPosition.y)

        switch gestureState {
        case .pending:
            if totalDistance > GestureThresholds.tapMaxDistance {
                cancelLongPressTimer()
                gestureState = .scrolling
                let sx = deltaX * GestureThresholds.scrollSensitivity
                let sy = deltaY * GestureThresholds.scrollSensitivity
                injectScrollEvent(deltaX: sx, deltaY: sy, at: point)
                lastScrollDeltaX = sx
                lastScrollDeltaY = sy
            }

        case .longPressReady:
            if totalDistance > GestureThresholds.tapMaxDistance {
                // Long press + drag → left mouse drag
                gestureState = .dragging
                injectMouseDown(at: touchStartPosition)
                injectMouseDragged(to: point)
            }

        case .scrolling:
            let sx = deltaX * GestureThresholds.scrollSensitivity
            let sy = deltaY * GestureThresholds.scrollSensitivity
            injectScrollEvent(deltaX: sx, deltaY: sy, at: point)
            let timeDelta = now - touchLastMoveTime
            if timeDelta > 0 && timeDelta < 100_000_000 {
                lastScrollDeltaX = sx
                lastScrollDeltaY = sy
            }

        case .dragging:
            injectMouseDragged(to: point)

        default:
            break
        }

        touchLastPosition = point
        touchLastMoveTime = now
    }

    private func oneFingerUp(at point: CGPoint) {
        cancelLongPressTimer()
        let now = DispatchTime.now().uptimeNanoseconds
        let elapsed = now - touchStartTime
        let distance = hypot(point.x - touchStartPosition.x, point.y - touchStartPosition.y)

        switch gestureState {
        case .pending:
            // Quick release, no movement → tap or double tap
            if distance < GestureThresholds.tapMaxDistance && elapsed < GestureThresholds.tapMaxTime {
                // Check double tap
                let timeSinceLastTap = now - lastTapTime
                let distFromLastTap = hypot(point.x - lastTapPosition.x, point.y - lastTapPosition.y)

                if timeSinceLastTap < GestureThresholds.doubleTapMaxTime
                    && distFromLastTap < GestureThresholds.doubleTapMaxDistance {
                    performDoubleClick(at: point)
                    lastTapTime = 0  // Reset so triple tap doesn't trigger
                } else {
                    performClick(at: point)
                    lastTapTime = now
                    lastTapPosition = point
                }
            }

        case .longPressReady:
            // Held long but didn't drag → right click
            performRightClick(at: point)

        case .scrolling:
            // Check momentum
            let timeSinceLastMove = now - touchLastMoveTime
            if timeSinceLastMove < 50_000_000 {
                let threshold: CGFloat = 2.0
                if abs(lastScrollDeltaX) > threshold || abs(lastScrollDeltaY) > threshold {
                    startMomentumScroll(
                        velocityX: lastScrollDeltaX * 6.0,
                        velocityY: lastScrollDeltaY * 6.0,
                        at: point
                    )
                }
            }

        case .dragging:
            injectMouseUp(at: point)

        default:
            break
        }

        gestureState = .idle
    }

    // MARK: - 2-Finger Gestures

    private func handleTwoFingerTouch(p1: CGPoint, p2: CGPoint, action: Int) {
        let distance = hypot(p2.x - p1.x, p2.y - p1.y)
        let midpoint = CGPoint(x: (p1.x + p2.x) / 2, y: (p1.y + p2.y) / 2)

        switch action {
        case 0: // Down
            cancelLongPressTimer()
            stopMomentumScroll()
            gestureState = .idle  // Reset so 2-finger detection starts fresh
            initialPinchDistance = distance
            lastPinchDistance = distance
            touchLastPosition = midpoint

        case 1: // Move
            let distanceChange = abs(distance - initialPinchDistance)
            let midDelta = hypot(midpoint.x - touchLastPosition.x, midpoint.y - touchLastPosition.y)

            // Determine mode if not yet decided
            if gestureState != .twoFingerScroll && gestureState != .pinching {
                if distanceChange > GestureThresholds.pinchMinDistance {
                    gestureState = .pinching
                } else if midDelta > GestureThresholds.tapMaxDistance {
                    gestureState = .twoFingerScroll
                }
            }

            switch gestureState {
            case .twoFingerScroll:
                let dx = (midpoint.x - touchLastPosition.x) * GestureThresholds.scrollSensitivity
                let dy = (midpoint.y - touchLastPosition.y) * GestureThresholds.scrollSensitivity
                injectScrollEvent(deltaX: dx, deltaY: dy, at: midpoint)

            case .pinching:
                let scaleDelta = distance - lastPinchDistance
                // Cmd + scroll = zoom in most Mac apps
                let zoomAmount = Int32(scaleDelta * 0.5)
                if zoomAmount != 0 {
                    injectZoomEvent(delta: zoomAmount, at: midpoint)
                }
                lastPinchDistance = distance

            default:
                break
            }

            touchLastPosition = midpoint

        case 2: // Up
            gestureState = .idle
            // Reset 1-finger tracking so leftover moves don't trigger scroll
            touchStartPosition = .zero
            touchLastPosition = .zero

        default:
            break
        }
    }

    // MARK: - Event Injection

    private func moveCursor(to point: CGPoint) {
        if let event = CGEvent(mouseEventSource: eventSource, mouseType: .mouseMoved, mouseCursorPosition: point, mouseButton: .left) {
            event.post(tap: .cghidEventTap)
        }
    }

    private func performClick(at point: CGPoint) {
        if let down = CGEvent(mouseEventSource: eventSource, mouseType: .leftMouseDown, mouseCursorPosition: point, mouseButton: .left) {
            down.setIntegerValueField(.mouseEventClickState, value: 1)
            down.post(tap: .cghidEventTap)
        }
        if let up = CGEvent(mouseEventSource: eventSource, mouseType: .leftMouseUp, mouseCursorPosition: point, mouseButton: .left) {
            up.setIntegerValueField(.mouseEventClickState, value: 1)
            up.post(tap: .cghidEventTap)
        }
    }

    private func performDoubleClick(at point: CGPoint) {
        if let down = CGEvent(mouseEventSource: eventSource, mouseType: .leftMouseDown, mouseCursorPosition: point, mouseButton: .left) {
            down.setIntegerValueField(.mouseEventClickState, value: 2)
            down.post(tap: .cghidEventTap)
        }
        if let up = CGEvent(mouseEventSource: eventSource, mouseType: .leftMouseUp, mouseCursorPosition: point, mouseButton: .left) {
            up.setIntegerValueField(.mouseEventClickState, value: 2)
            up.post(tap: .cghidEventTap)
        }
    }

    private func performRightClick(at point: CGPoint) {
        if let down = CGEvent(mouseEventSource: eventSource, mouseType: .rightMouseDown, mouseCursorPosition: point, mouseButton: .right) {
            down.post(tap: .cghidEventTap)
        }
        if let up = CGEvent(mouseEventSource: eventSource, mouseType: .rightMouseUp, mouseCursorPosition: point, mouseButton: .right) {
            up.post(tap: .cghidEventTap)
        }
    }

    private func injectMouseDown(at point: CGPoint) {
        if let event = CGEvent(mouseEventSource: eventSource, mouseType: .leftMouseDown, mouseCursorPosition: point, mouseButton: .left) {
            event.setIntegerValueField(.mouseEventClickState, value: 1)
            event.post(tap: .cghidEventTap)
        }
    }

    private func injectMouseDragged(to point: CGPoint) {
        if let event = CGEvent(mouseEventSource: eventSource, mouseType: .leftMouseDragged, mouseCursorPosition: point, mouseButton: .left) {
            event.post(tap: .cghidEventTap)
        }
    }

    private func injectMouseUp(at point: CGPoint) {
        if let event = CGEvent(mouseEventSource: eventSource, mouseType: .leftMouseUp, mouseCursorPosition: point, mouseButton: .left) {
            event.post(tap: .cghidEventTap)
        }
    }

    private func injectScrollEvent(deltaX: CGFloat, deltaY: CGFloat, at position: CGPoint) {
        guard let scrollEvent = CGEvent(
            scrollWheelEvent2Source: eventSource,
            units: .pixel,
            wheelCount: 2,
            wheel1: Int32(deltaY),
            wheel2: Int32(deltaX),
            wheel3: 0
        ) else { return }
        scrollEvent.location = position
        scrollEvent.post(tap: .cghidEventTap)
    }

    private func injectZoomEvent(delta: Int32, at position: CGPoint) {
        guard let scrollEvent = CGEvent(
            scrollWheelEvent2Source: eventSource,
            units: .pixel,
            wheelCount: 1,
            wheel1: delta,
            wheel2: 0,
            wheel3: 0
        ) else { return }
        scrollEvent.location = position
        // Set Cmd flag for zoom
        scrollEvent.flags = .maskCommand
        scrollEvent.post(tap: .cghidEventTap)
    }

    // MARK: - Long Press Timer

    private func cancelLongPressTimer() {
        longPressTimer?.cancel()
        longPressTimer = nil
    }

    // MARK: - Momentum Scrolling

    private func startMomentumScroll(velocityX: CGFloat, velocityY: CGFloat, at position: CGPoint) {
        stopMomentumScroll()
        momentumVelocityX = velocityX
        momentumVelocityY = velocityY
        lastMomentumPosition = position
        momentumTimer = Timer.scheduledTimer(withTimeInterval: 0.016, repeats: true) { [weak self] _ in
            self?.momentumTick()
        }
    }

    private func momentumTick() {
        let decay: CGFloat = 0.92
        let minVelocity: CGFloat = 0.5

        if abs(momentumVelocityX) < minVelocity && abs(momentumVelocityY) < minVelocity {
            stopMomentumScroll()
            return
        }

        injectScrollEvent(deltaX: momentumVelocityX, deltaY: momentumVelocityY, at: lastMomentumPosition)
        momentumVelocityX *= decay
        momentumVelocityY *= decay
    }

    private func stopMomentumScroll() {
        momentumTimer?.invalidate()
        momentumTimer = nil
        momentumVelocityX = 0
        momentumVelocityY = 0
    }

    func applicationShouldTerminate(_ sender: NSApplication) -> NSApplication.TerminateReply {
        if isExplicitQuitRequested {
            return .terminateNow
        }
        // If the Settings window is currently open or was just closed (e.g. Vorssaint "Quit on Close"
        // sent NSRunningApplication.terminate() when clicking the red close button), hide Settings
        // and keep SideKick alive in the menu bar instead of terminating.
        let windowVisible = settingsWindow?.window?.isVisible ?? false
        let recentlyClosed = Date().timeIntervalSince(lastWindowCloseTime) < 3.0
        if windowVisible || recentlyClosed {
            debugLog("🛡️ Intercepted external quit on window close — keeping SideKick running in menu bar")
            closeSettingsWindowToBackground()
            return .terminateCancel
        }
        return .terminateNow
    }

    func applicationWillTerminate(_ notification: Notification) {
        // Stop momentum scrolling
        stopMomentumScroll()

        // Stop server and cleanup
        stopServer()

        // Cancel all combine subscriptions
        cancellables.removeAll()
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        return false
    }
}

// MARK: - Menu bar quick actions

extension AppDelegate: NSMenuDelegate, NSMenuItemValidation {
    func validateMenuItem(_ menuItem: NSMenuItem) -> Bool {
        if menuItem.action == #selector(disconnectTablet) {
            return settings.isRunning && settings.clientConnected
        }
        if menuItem.action == #selector(toggleServerFromMenu) {
            return settings.isRunning || settings.hasScreenRecordingPermission
        }
        return true
    }

    func menuNeedsUpdate(_ menu: NSMenu) {
        menu.removeAllItems()

        // Live status line (not clickable)
        let statusTitle: String
        if settings.isRunning {
            if settings.clientConnected {
                let device = settings.currentWirelessDevice ?? "tablet"
                statusTitle = "🟢 Connected — \(device)"
            } else {
                statusTitle = "🟡 Waiting for tablet on port \(settings.port)"
            }
        } else {
            statusTitle = "⚪️ Server stopped"
        }
        let statusLine = NSMenuItem(title: statusTitle, action: nil, keyEquivalent: "")
        statusLine.isEnabled = false
        menu.addItem(statusLine)
        menu.addItem(.separator())

        // Connect Tablet Here (Takeover) — switches a paired tablet from another Mac to this Mac.
        // Placed at the very top of actions and always enabled so it can be triggered any time.
        let takeover = NSMenuItem(
            title: "Connect Tablet Here (Takeover)",
            action: #selector(requestTabletTakeover),
            keyEquivalent: "T"
        )
        takeover.keyEquivalentModifierMask = [.command, .shift]
        takeover.target = self
        takeover.image = NSImage(systemSymbolName: "ipad.and.arrow.forward", accessibilityDescription: "Takeover")
        takeover.isEnabled = true
        menu.addItem(takeover)

        // Disconnect Tablet — drops the active tablet session and virtual display while keeping the server listening.
        let disconnect = NSMenuItem(
            title: "Disconnect Tablet",
            action: #selector(disconnectTablet),
            keyEquivalent: "D"
        )
        disconnect.keyEquivalentModifierMask = [.command, .shift]
        disconnect.target = self
        disconnect.image = NSImage(systemSymbolName: "xmark.circle", accessibilityDescription: "Disconnect Tablet")
        disconnect.isEnabled = settings.isRunning && settings.clientConnected
        menu.addItem(disconnect)

        // Start / Stop
        let toggle = NSMenuItem(
            title: settings.isRunning ? "Stop Streaming" : "Start Streaming",
            action: #selector(toggleServerFromMenu),
            keyEquivalent: "t"
        )
        toggle.target = self
        // Mirror the settings-window Start button: starting needs the Screen
        // Recording permission, stopping is always allowed.
        toggle.isEnabled = settings.isRunning || settings.hasScreenRecordingPermission
        menu.addItem(toggle)

        // Connection mode (switching while running restarts the server, same
        // as changing it in the settings window)
        let modeMenu = NSMenu()
        modeMenu.autoenablesItems = false
        let usb = NSMenuItem(title: "USB", action: #selector(selectUSBMode), keyEquivalent: "")
        usb.target = self
        usb.state = settings.connectionMode == .usb ? .on : .off
        modeMenu.addItem(usb)
        let wireless = NSMenuItem(title: "Wireless", action: #selector(selectWirelessMode), keyEquivalent: "")
        wireless.target = self
        wireless.state = settings.connectionMode == .wireless ? .on : .off
        modeMenu.addItem(wireless)
        let modeItem = NSMenuItem(title: "Connection Mode", action: nil, keyEquivalent: "")
        modeItem.submenu = modeMenu
        menu.addItem(modeItem)

        let popoverItem = NSMenuItem(
            title: "Quick Controls Popover",
            action: #selector(togglePopover),
            keyEquivalent: "W"
        )
        popoverItem.keyEquivalentModifierMask = [.command, .shift]
        popoverItem.target = self
        popoverItem.image = NSImage(systemSymbolName: "slider.horizontal.3", accessibilityDescription: "Quick Controls")
        menu.addItem(popoverItem)

        menu.addItem(.separator())

        let settingsItem = NSMenuItem(title: "Settings…", action: #selector(showSettings), keyEquivalent: "s")
        settingsItem.target = self
        menu.addItem(settingsItem)

        menu.addItem(.separator())
        let quitMenuItem = NSMenuItem(title: "Quit SideKick", action: #selector(requestQuit(_:)), keyEquivalent: "q")
        quitMenuItem.target = self
        menu.addItem(quitMenuItem)
    }
}
