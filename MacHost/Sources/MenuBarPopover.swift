import Cocoa
import Combine
import SwiftUI

// MARK: - Menu Bar Popover Controller

/// Native menu bar popover attached to the status bar button.
/// Provides immediate access to live stream metrics, primary actions
/// (Start/Stop, Takeover, Disconnect), quick toggles (Touch, Encryption, Mode),
/// and shortcuts to Settings and Quit.
final class MenuBarPopoverController: NSObject, NSPopoverDelegate {
    private let settings: DisplaySettings
    private let onOpenSettings: () -> Void
    private var popover: NSPopover?

    init(settings: DisplaySettings, onOpenSettings: @escaping () -> Void) {
        self.settings = settings
        self.onOpenSettings = onOpenSettings
        super.init()
    }

    var isShown: Bool {
        popover?.isShown ?? false
    }

    func toggle(relativeTo button: NSStatusBarButton) {
        if isShown {
            close()
        } else {
            show(relativeTo: button)
        }
    }

    func show(relativeTo button: NSStatusBarButton) {
        let popover = self.popover ?? makePopover()
        self.popover = popover

        NSApp.activate(ignoringOtherApps: true)
        popover.show(relativeTo: button.bounds, of: button, preferredEdge: .minY)
        if let window = popover.contentViewController?.view.window {
            window.makeKey()
        }
    }

    func close() {
        popover?.performClose(nil)
    }

    private func makePopover() -> NSPopover {
        let popover = NSPopover()
        popover.behavior = .transient
        popover.animates = true
        popover.delegate = self

        let view = MenuBarPopoverView(
            settings: settings,
            onClose: { [weak self] in self?.close() },
            onOpenSettings: { [weak self] in
                self?.close()
                self?.onOpenSettings()
            },
            onQuit: {
                NSApp.terminate(nil)
            }
        )
        let hostingController = NSHostingController(rootView: view)
        popover.contentViewController = hostingController
        return popover
    }
}

// MARK: - Menu Bar Popover SwiftUI View

struct MenuBarPopoverView: View {
    @ObservedObject var settings: DisplaySettings
    let onClose: () -> Void
    let onOpenSettings: () -> Void
    let onQuit: () -> Void

    private var statusColor: Color {
        if !settings.isRunning { return .secondary }
        return settings.clientConnected ? .green : .orange
    }

    private var statusTitle: String {
        if !settings.isRunning { return "Server stopped" }
        if settings.clientConnected { return settings.currentWirelessDevice ?? "Tablet connected" }
        return "Waiting for tablet"
    }

    private var statusSubtitle: String {
        let mode = settings.connectionMode == .wireless ? "Wireless" : "USB"
        return settings.isRunning ? "\(mode) • port \(settings.port)" : "\(mode) mode"
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            // Header: Live status + Close button
            header

            // Live Performance Metrics
            statsRow

            // Primary actions: Start/Stop & Multi-Mac Takeover
            HStack(spacing: 8) {
                Button(action: { settings.toggleServer() }) {
                    Label(settings.isRunning ? "Stop" : "Start",
                          systemImage: settings.isRunning ? "stop.fill" : "play.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .tint(settings.isRunning ? .red : .accentColor)
                .controlSize(.regular)
                .disabled(!settings.isRunning && !settings.hasScreenRecordingPermission)
                .help(settings.hasScreenRecordingPermission || settings.isRunning
                      ? "Start or stop streaming"
                      : "Screen Recording permission required")

                Button(action: { settings.requestTakeover() }) {
                    Label("Takeover", systemImage: "ipad.and.arrow.forward")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .controlSize(.regular)
                .help("Pull your paired tablet to this Mac (⌘⇧T)")
            }

            // Disconnect Active Session button
            Button(action: { settings.disconnectTablet() }) {
                Label("Disconnect Tablet", systemImage: "xmark.circle")
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .controlSize(.small)
            .disabled(!(settings.isRunning && settings.clientConnected))
            .help("Drop the tablet session, keep the server listening (⌘⇧D)")

            Divider()

            // Mode Selector: USB vs Wireless
            HStack(spacing: 8) {
                Image(systemName: settings.connectionMode == .wireless ? "wifi" : "cable.connector")
                    .frame(width: 16)
                    .foregroundColor(.accentColor)
                Text("Mode")
                    .font(.system(size: 12, weight: .medium))
                Spacer()
                Picker("", selection: $settings.connectionMode) {
                    Text("USB").tag(ConnectionMode.usb)
                    Text("Wi-Fi").tag(ConnectionMode.wireless)
                }
                .pickerStyle(.segmented)
                .frame(width: 130)
            }

            // Touch Control Quick Toggle
            toggleRow(
                icon: "hand.tap",
                title: "Touch Control",
                subtitle: settings.touchEnabled && !settings.hasAccessibilityPermission
                    ? "Accessibility needed"
                    : "Control Mac from tablet",
                warn: settings.touchEnabled && !settings.hasAccessibilityPermission,
                isOn: $settings.touchEnabled
            )

            // Keyboard Control Quick Toggle
            toggleRow(
                icon: "keyboard",
                title: "Keyboard Control",
                subtitle: settings.keyboardEnabled && !settings.hasAccessibilityPermission
                    ? "Accessibility needed"
                    : (settings.keyboardEnabled ? "Allow tablet keyboard" : "Blocked on Mac"),
                warn: settings.keyboardEnabled && !settings.hasAccessibilityPermission,
                isOn: $settings.keyboardEnabled
            )

            // Wireless Encryption Quick Toggle
            toggleRow(
                icon: settings.wirelessEncryption ? "lock.fill" : "lock.open",
                title: "Encryption",
                subtitle: settings.wirelessEncryption ? "AES-256-GCM" : "Disabled (unencrypted)",
                warn: !settings.wirelessEncryption,
                isOn: $settings.wirelessEncryption
            )

            Divider()

            // Footer: Settings… & Quit
            HStack {
                Button(action: onOpenSettings) {
                    HStack(spacing: 4) {
                        Image(systemName: "gearshape")
                        Text("Settings…")
                    }
                }
                .buttonStyle(.borderless)
                .font(.system(size: 12))
                .help("Open SideKick Preferences (⌘,)")

                Spacer()

                Button(action: onQuit) {
                    HStack(spacing: 4) {
                        Image(systemName: "power")
                        Text("Quit")
                    }
                }
                .buttonStyle(.borderless)
                .font(.system(size: 12))
                .foregroundColor(.secondary)
                .help("Quit SideKick (⌘Q)")
            }
        }
        .padding(14)
        .frame(width: 290)
    }

    private var header: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(statusColor)
                .frame(width: 9, height: 9)
            VStack(alignment: .leading, spacing: 1) {
                Text(statusTitle)
                    .font(.system(size: 13, weight: .semibold))
                    .lineLimit(1)
                Text(statusSubtitle)
                    .font(.system(size: 10))
                    .foregroundColor(.secondary)
                    .lineLimit(1)
            }
            Spacer(minLength: 4)
            Button(action: onClose) {
                Image(systemName: "xmark.circle.fill")
                    .foregroundColor(.secondary)
            }
            .buttonStyle(.borderless)
            .help("Close Popover")
        }
    }

    private var statsRow: some View {
        HStack(spacing: 0) {
            stat("FPS", settings.clientConnected ? String(format: "%.0f", settings.currentFPS) : "—")
            stat("Bitrate", settings.clientConnected ? String(format: "%.1f M", settings.currentBitrate) : "—")
            stat("Display", settings.resolution)
        }
        .padding(.vertical, 6)
        .background(
            RoundedRectangle(cornerRadius: 8, style: .continuous)
                .fill(Color.primary.opacity(0.06))
        )
    }

    private func stat(_ label: String, _ value: String) -> some View {
        VStack(spacing: 1) {
            Text(value)
                .font(.system(size: 12, weight: .semibold, design: .monospaced))
                .lineLimit(1)
                .minimumScaleFactor(0.7)
            Text(label)
                .font(.system(size: 9))
                .foregroundColor(.secondary)
        }
        .frame(maxWidth: .infinity)
    }

    private func toggleRow(
        icon: String,
        title: String,
        subtitle: String,
        warn: Bool,
        isOn: Binding<Bool>
    ) -> some View {
        HStack(spacing: 8) {
            Image(systemName: icon)
                .frame(width: 16)
                .foregroundColor(warn ? .orange : .accentColor)
            VStack(alignment: .leading, spacing: 1) {
                Text(title)
                    .font(.system(size: 12, weight: .medium))
                Text(subtitle)
                    .font(.system(size: 10))
                    .foregroundColor(warn ? .orange : .secondary)
                    .lineLimit(1)
            }
            Spacer()
            Toggle("", isOn: isOn)
                .labelsHidden()
                .toggleStyle(.switch)
                .controlSize(.small)
        }
    }
}
