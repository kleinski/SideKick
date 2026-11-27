import Carbon.HIToolbox
import Cocoa

/// Injects keyboard events and UTF-8 text received from the Android client into macOS
/// using `CGEvent` (requires Accessibility permission, shared with touch injection).
enum KeyboardInjector {
    private static let modShift: UInt8 = 1
    private static let modCtrl: UInt8 = 2
    private static let modAlt: UInt8 = 4
    private static let modCmd: UInt8 = 8

    static func injectKeyEvent(androidKeyCode: Int, isDown: Bool, modifiers: UInt8) {
        guard let macKey = mapAndroidKeyCode(androidKeyCode) else { return }
        guard let event = CGEvent(keyboardEventSource: nil, virtualKey: macKey, keyDown: isDown) else { return }

        var flags: CGEventFlags = []
        if (modifiers & modShift) != 0 { flags.insert(.maskShift) }
        if (modifiers & modCtrl) != 0 { flags.insert(.maskControl) }
        if (modifiers & modAlt) != 0 { flags.insert(.maskAlternate) }
        if (modifiers & modCmd) != 0 { flags.insert(.maskCommand) }
        event.flags = flags

        event.post(tap: .cghidEventTap)
    }

    static func injectText(_ text: String) {
        guard !text.isEmpty else { return }

        // Process newline characters as explicit Return key presses so macOS apps
        // treat them identically to pressing Enter.
        let parts = text.split(separator: "\n", omittingEmptySubsequences: false)
        for (index, part) in parts.enumerated() {
            if !part.isEmpty {
                postUnicodeString(String(part))
            }
            if index < parts.count - 1 {
                injectReturnKey()
            }
        }
    }

    static func injectBackspace(count: Int) {
        let clamped = max(1, min(count, 100))
        let keyCode = CGKeyCode(kVK_Delete)
        for _ in 0..<clamped {
            CGEvent(keyboardEventSource: nil, virtualKey: keyCode, keyDown: true)?.post(tap: .cghidEventTap)
            CGEvent(keyboardEventSource: nil, virtualKey: keyCode, keyDown: false)?.post(tap: .cghidEventTap)
        }
    }

    private static func injectReturnKey() {
        let keyCode = CGKeyCode(kVK_Return)
        CGEvent(keyboardEventSource: nil, virtualKey: keyCode, keyDown: true)?.post(tap: .cghidEventTap)
        CGEvent(keyboardEventSource: nil, virtualKey: keyCode, keyDown: false)?.post(tap: .cghidEventTap)
    }

    private static func postUnicodeString(_ string: String) {
        let utf16 = Array(string.utf16)
        // CGEvent.keyboardSetUnicodeString accepts up to 20 UTF-16 code units per event.
        let chunkSize = 16
        var offset = 0
        while offset < utf16.count {
            let end = min(offset + chunkSize, utf16.count)
            var chunk = Array(utf16[offset..<end])
            if let down = CGEvent(keyboardEventSource: nil, virtualKey: 0, keyDown: true),
               let up = CGEvent(keyboardEventSource: nil, virtualKey: 0, keyDown: false) {
                down.keyboardSetUnicodeString(stringLength: chunk.count, unicodeString: &chunk)
                up.keyboardSetUnicodeString(stringLength: chunk.count, unicodeString: &chunk)
                down.post(tap: .cghidEventTap)
                up.post(tap: .cghidEventTap)
            }
            offset = end
        }
    }

    /// Maps Android `android.view.KeyEvent.KEYCODE_*` constants to macOS virtual key codes.
    private static func mapAndroidKeyCode(_ code: Int) -> CGKeyCode? {
        switch code {
        // Letters A-Z (Android: 29..54)
        case 29: return CGKeyCode(kVK_ANSI_A)
        case 30: return CGKeyCode(kVK_ANSI_B)
        case 31: return CGKeyCode(kVK_ANSI_C)
        case 32: return CGKeyCode(kVK_ANSI_D)
        case 33: return CGKeyCode(kVK_ANSI_E)
        case 34: return CGKeyCode(kVK_ANSI_F)
        case 35: return CGKeyCode(kVK_ANSI_G)
        case 36: return CGKeyCode(kVK_ANSI_H)
        case 37: return CGKeyCode(kVK_ANSI_I)
        case 38: return CGKeyCode(kVK_ANSI_J)
        case 39: return CGKeyCode(kVK_ANSI_K)
        case 40: return CGKeyCode(kVK_ANSI_L)
        case 41: return CGKeyCode(kVK_ANSI_M)
        case 42: return CGKeyCode(kVK_ANSI_N)
        case 43: return CGKeyCode(kVK_ANSI_O)
        case 44: return CGKeyCode(kVK_ANSI_P)
        case 45: return CGKeyCode(kVK_ANSI_Q)
        case 46: return CGKeyCode(kVK_ANSI_R)
        case 47: return CGKeyCode(kVK_ANSI_S)
        case 48: return CGKeyCode(kVK_ANSI_T)
        case 49: return CGKeyCode(kVK_ANSI_U)
        case 50: return CGKeyCode(kVK_ANSI_V)
        case 51: return CGKeyCode(kVK_ANSI_W)
        case 52: return CGKeyCode(kVK_ANSI_X)
        case 53: return CGKeyCode(kVK_ANSI_Y)
        case 54: return CGKeyCode(kVK_ANSI_Z)

        // Numbers 0-9 (Android: 7..16)
        case 7: return CGKeyCode(kVK_ANSI_0)
        case 8: return CGKeyCode(kVK_ANSI_1)
        case 9: return CGKeyCode(kVK_ANSI_2)
        case 10: return CGKeyCode(kVK_ANSI_3)
        case 11: return CGKeyCode(kVK_ANSI_4)
        case 12: return CGKeyCode(kVK_ANSI_5)
        case 13: return CGKeyCode(kVK_ANSI_6)
        case 14: return CGKeyCode(kVK_ANSI_7)
        case 15: return CGKeyCode(kVK_ANSI_8)
        case 16: return CGKeyCode(kVK_ANSI_9)

        // Whitespace, Editing & Navigation
        case 61: return CGKeyCode(kVK_Tab)          // KEYCODE_TAB
        case 62: return CGKeyCode(kVK_Space)        // KEYCODE_SPACE
        case 66, 160: return CGKeyCode(kVK_Return)  // KEYCODE_ENTER, KEYCODE_NUMPAD_ENTER
        case 67: return CGKeyCode(kVK_Delete)       // KEYCODE_DEL (Backspace)
        case 111: return CGKeyCode(kVK_Escape)      // KEYCODE_ESCAPE
        case 112: return CGKeyCode(kVK_ForwardDelete) // KEYCODE_FORWARD_DEL
        case 19: return CGKeyCode(kVK_UpArrow)      // KEYCODE_DPAD_UP
        case 20: return CGKeyCode(kVK_DownArrow)    // KEYCODE_DPAD_DOWN
        case 21: return CGKeyCode(kVK_LeftArrow)    // KEYCODE_DPAD_LEFT
        case 22: return CGKeyCode(kVK_RightArrow)   // KEYCODE_DPAD_RIGHT
        case 92: return CGKeyCode(kVK_PageUp)       // KEYCODE_PAGE_UP
        case 93: return CGKeyCode(kVK_PageDown)     // KEYCODE_PAGE_DOWN
        case 122: return CGKeyCode(kVK_Home)        // KEYCODE_MOVE_HOME
        case 123: return CGKeyCode(kVK_End)         // KEYCODE_MOVE_END

        // Modifiers
        case 59: return CGKeyCode(kVK_Shift)        // KEYCODE_SHIFT_LEFT
        case 60: return CGKeyCode(kVK_RightShift)   // KEYCODE_SHIFT_RIGHT
        case 57: return CGKeyCode(kVK_Option)       // KEYCODE_ALT_LEFT
        case 58: return CGKeyCode(kVK_RightOption)  // KEYCODE_ALT_RIGHT
        case 113: return CGKeyCode(kVK_Control)     // KEYCODE_CTRL_LEFT
        case 114: return CGKeyCode(kVK_RightControl) // KEYCODE_CTRL_RIGHT
        case 117: return CGKeyCode(kVK_Command)     // KEYCODE_META_LEFT
        case 118: return CGKeyCode(kVK_RightCommand) // KEYCODE_META_RIGHT
        case 115: return CGKeyCode(kVK_CapsLock)    // KEYCODE_CAPS_LOCK

        // Punctuation
        case 55: return CGKeyCode(kVK_ANSI_Comma)       // KEYCODE_COMMA
        case 56: return CGKeyCode(kVK_ANSI_Period)      // KEYCODE_PERIOD
        case 68: return CGKeyCode(kVK_ANSI_Grave)       // KEYCODE_GRAVE
        case 69: return CGKeyCode(kVK_ANSI_Minus)       // KEYCODE_MINUS
        case 70: return CGKeyCode(kVK_ANSI_Equal)       // KEYCODE_EQUALS
        case 71: return CGKeyCode(kVK_ANSI_LeftBracket) // KEYCODE_LEFT_BRACKET
        case 72: return CGKeyCode(kVK_ANSI_RightBracket) // KEYCODE_RIGHT_BRACKET
        case 73: return CGKeyCode(kVK_ANSI_Backslash)   // KEYCODE_BACKSLASH
        case 74: return CGKeyCode(kVK_ANSI_Semicolon)   // KEYCODE_SEMICOLON
        case 75: return CGKeyCode(kVK_ANSI_Quote)       // KEYCODE_APOSTROPHE
        case 76: return CGKeyCode(kVK_ANSI_Slash)       // KEYCODE_SLASH

        // Function Keys F1-F12 (Android: 131..142)
        case 131: return CGKeyCode(kVK_F1)
        case 132: return CGKeyCode(kVK_F2)
        case 133: return CGKeyCode(kVK_F3)
        case 134: return CGKeyCode(kVK_F4)
        case 135: return CGKeyCode(kVK_F5)
        case 136: return CGKeyCode(kVK_F6)
        case 137: return CGKeyCode(kVK_F7)
        case 138: return CGKeyCode(kVK_F8)
        case 139: return CGKeyCode(kVK_F9)
        case 140: return CGKeyCode(kVK_F10)
        case 141: return CGKeyCode(kVK_F11)
        case 142: return CGKeyCode(kVK_F12)

        default: return nil
        }
    }

    enum MacSystemAction: UInt8 {
        case apps = 0
        case missionControl = 1
        case showDesktop = 2
        case appExpose = 3
        case spacePrev = 4
        case spaceNext = 5
    }

    private typealias CoreDockSendNotificationFn = @convention(c) (CFString, UnsafeMutableRawPointer?) -> Void

    private static let coreDockSendNotification: CoreDockSendNotificationFn? = {
        guard let handle = dlopen("/System/Library/Frameworks/ApplicationServices.framework/ApplicationServices", RTLD_LAZY),
              let sym = dlsym(handle, "CoreDockSendNotification") else {
            return nil
        }
        return unsafeBitCast(sym, to: CoreDockSendNotificationFn.self)
    }()

    /// Triggers a macOS system action (Apps/Launchpad, Mission Control, Show Desktop, App Exposé, or Space switch).
    static func triggerSystemAction(_ rawAction: UInt8) {
        guard let action = MacSystemAction(rawValue: rawAction) else { return }
        switch action {
        case .apps:
            let candidates = [
                "/System/Applications/Apps.app",
                "/System/Applications/Launchpad.app",
            ]
            for path in candidates where FileManager.default.fileExists(atPath: path) {
                NSWorkspace.shared.open(URL(fileURLWithPath: path))
                return
            }
        case .missionControl:
            let path = "/System/Applications/Mission Control.app"
            if FileManager.default.fileExists(atPath: path) {
                NSWorkspace.shared.open(URL(fileURLWithPath: path))
            } else if let send = coreDockSendNotification {
                send("com.apple.expose.awake" as CFString, nil)
            }
        case .showDesktop:
            if let send = coreDockSendNotification {
                send("com.apple.showdesktop.awake" as CFString, nil)
            } else {
                let key = CGKeyCode(kVK_F11)
                CGEvent(keyboardEventSource: nil, virtualKey: key, keyDown: true)?.post(tap: .cghidEventTap)
                CGEvent(keyboardEventSource: nil, virtualKey: key, keyDown: false)?.post(tap: .cghidEventTap)
            }
        case .appExpose:
            if let send = coreDockSendNotification {
                send("com.apple.expose.front.awake" as CFString, nil)
            } else {
                postControlArrow(CGKeyCode(kVK_DownArrow))
            }
        case .spacePrev:
            postControlArrow(CGKeyCode(kVK_LeftArrow))
        case .spaceNext:
            postControlArrow(CGKeyCode(kVK_RightArrow))
        }
    }

    private static func postControlArrow(_ key: CGKeyCode) {
        if let down = CGEvent(keyboardEventSource: nil, virtualKey: key, keyDown: true),
           let up = CGEvent(keyboardEventSource: nil, virtualKey: key, keyDown: false) {
            down.flags = .maskControl
            up.flags = .maskControl
            down.post(tap: .cghidEventTap)
            up.post(tap: .cghidEventTap)
        }
    }
}
