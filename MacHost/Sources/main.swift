import Foundation
import AppKit

// CLI control mode for external launchers (Vorssaint, Shortcuts, Raycast, shell scripts):
// Usage: SideScreen --takeover | --toggle | --start | --stop | --settings
let cliArgs = Set(CommandLine.arguments.dropFirst())
let cliMap: [String: String] = [
    "--takeover": "takeover",
    "--toggle": "toggle",
    "--start": "start",
    "--stop": "stop",
    "--settings": "settings"
]
if let matched = cliMap.first(where: { cliArgs.contains($0.key) }) {
    let command = matched.value
    let running = NSRunningApplication.runningApplications(withBundleIdentifier: "com.sidekick.app")
        + NSRunningApplication.runningApplications(withBundleIdentifier: "com.sidescreen.app")
    if !running.isEmpty {
        DistributedNotificationCenter.default().postNotificationName(
            NSNotification.Name("com.sidescreen.app.command"),
            object: command,
            userInfo: nil,
            deliverImmediately: true
        )
        print("✅ Sent '\(command)' to running SideKick instance.")
        exit(0)
    } else if let url = URL(string: "sidekick://\(command)") {
        let config = NSWorkspace.OpenConfiguration()
        config.activates = (command == "settings")
        let sem = DispatchSemaphore(value: 0)
        NSWorkspace.shared.open(url, configuration: config) { _, _ in
            sem.signal()
        }
        _ = sem.wait(timeout: .now() + 3.0)
        exit(0)
    }
}

print("🚀 SideKick starting...")

// Entry point
let app = NSApplication.shared
let delegate = AppDelegate()

// Setup main menu for keyboard shortcuts & macOS Application Menu bar
let mainMenu = NSMenu()

// App menu ("SideKick")
let appMenu = NSMenu()
let appMenuItem = NSMenuItem()
appMenuItem.submenu = appMenu
appMenu.addItem(NSMenuItem(title: "About SideKick", action: #selector(NSApplication.orderFrontStandardAboutPanel(_:)), keyEquivalent: ""))
appMenu.addItem(NSMenuItem.separator())

let appTakeoverItem = NSMenuItem(title: "Connect Tablet Here (Takeover)", action: #selector(AppDelegate.requestTabletTakeover), keyEquivalent: "T")
appTakeoverItem.keyEquivalentModifierMask = [.command, .shift]
appTakeoverItem.target = delegate
appMenu.addItem(appTakeoverItem)

let appSettingsItem = NSMenuItem(title: "Settings…", action: #selector(AppDelegate.showSettings), keyEquivalent: ",")
appSettingsItem.target = delegate
appMenu.addItem(appSettingsItem)

let closeWindowItem = NSMenuItem(title: "Close Settings Window", action: #selector(AppDelegate.closeSettingsWindowFromMenu), keyEquivalent: "w")
closeWindowItem.target = delegate
appMenu.addItem(closeWindowItem)

appMenu.addItem(NSMenuItem.separator())
let quitItem = NSMenuItem(title: "Quit SideKick", action: #selector(AppDelegate.requestQuit(_:)), keyEquivalent: "q")
quitItem.target = delegate
appMenu.addItem(quitItem)
mainMenu.addItem(appMenuItem)

// Stream menu (also indexed by Vorssaint Command Bar menu search!)
let streamMenu = NSMenu(title: "Stream")
let streamMenuItem = NSMenuItem(title: "Stream", action: nil, keyEquivalent: "")
streamMenuItem.submenu = streamMenu

let streamTakeoverItem = NSMenuItem(title: "Connect Tablet Here (Takeover)", action: #selector(AppDelegate.requestTabletTakeover), keyEquivalent: "T")
streamTakeoverItem.keyEquivalentModifierMask = [.command, .shift]
streamTakeoverItem.target = delegate
streamMenu.addItem(streamTakeoverItem)

let streamToggleItem = NSMenuItem(title: "Start / Stop Streaming", action: #selector(AppDelegate.toggleServerFromMenu), keyEquivalent: "t")
streamToggleItem.target = delegate
streamMenu.addItem(streamToggleItem)

streamMenu.addItem(NSMenuItem.separator())

let streamWirelessItem = NSMenuItem(title: "Switch to Wireless Mode", action: #selector(AppDelegate.selectWirelessMode), keyEquivalent: "")
streamWirelessItem.target = delegate
streamMenu.addItem(streamWirelessItem)

let streamUSBItem = NSMenuItem(title: "Switch to USB Mode", action: #selector(AppDelegate.selectUSBMode), keyEquivalent: "")
streamUSBItem.target = delegate
streamMenu.addItem(streamUSBItem)

mainMenu.addItem(streamMenuItem)

// Edit menu (for standard text editing shortcuts)
let editMenu = NSMenu(title: "Edit")
let editMenuItem = NSMenuItem()
editMenuItem.submenu = editMenu
editMenu.addItem(NSMenuItem(title: "Cut", action: #selector(NSText.cut(_:)), keyEquivalent: "x"))
editMenu.addItem(NSMenuItem(title: "Copy", action: #selector(NSText.copy(_:)), keyEquivalent: "c"))
editMenu.addItem(NSMenuItem(title: "Paste", action: #selector(NSText.paste(_:)), keyEquivalent: "v"))
editMenu.addItem(NSMenuItem(title: "Select All", action: #selector(NSText.selectAll(_:)), keyEquivalent: "a"))
mainMenu.addItem(editMenuItem)

app.mainMenu = mainMenu
app.setActivationPolicy(.regular)
app.delegate = delegate
app.run()
