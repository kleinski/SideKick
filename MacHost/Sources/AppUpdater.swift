import Cocoa
import Combine
import Foundation

/// GitHub Releases API updater for SideKick (macOS).
/// Checks `kleinski/SideKick` latest release, compares SemVer against the running app version,
/// detects Homebrew Cask installations, and presents either inline UI state (Settings → About & Updates)
/// or a native alert when triggered from the menu bar.
@MainActor
final class AppUpdater: NSObject, ObservableObject, NSMenuItemValidation {
    static let shared = AppUpdater()

    struct ReleaseInfo: Equatable {
        let version: String
        let tagName: String
        let title: String
        let notes: String
        let releaseURL: URL
        let dmgURL: URL?
    }

    enum UpdateState: Equatable {
        case idle
        case checking
        case upToDate(latestVersion: String)
        case updateAvailable(ReleaseInfo)
        case failed(String)
    }

    @Published private(set) var canCheckForUpdates = true
    @Published private(set) var state: UpdateState = .idle
    @Published var autoCheckEnabled: Bool {
        didSet {
            UserDefaults.standard.set(autoCheckEnabled, forKey: Self.autoCheckKey)
        }
    }

    private static let autoCheckKey = "SideKick.AutoCheckUpdates"
    private static let lastCheckKey = "SideKick.LastUpdateCheckTimestamp"
    private static let latestReleaseAPI = URL(string: "https://api.github.com/repos/kleinski/SideKick/releases/latest")!

    var isInstalledViaHomebrew: Bool {
        let fm = FileManager.default
        return fm.fileExists(atPath: "/opt/homebrew/Caskroom/sidekick")
            || fm.fileExists(atPath: "/usr/local/Caskroom/sidekick")
    }

    private override init() {
        let defaults = UserDefaults.standard
        if defaults.object(forKey: Self.autoCheckKey) == nil {
            self.autoCheckEnabled = true
        } else {
            self.autoCheckEnabled = defaults.bool(forKey: Self.autoCheckKey)
        }
        super.init()

        if autoCheckEnabled {
            let lastCheck = defaults.double(forKey: Self.lastCheckKey)
            let now = Date().timeIntervalSince1970
            if now - lastCheck > 24 * 60 * 60 {
                Task { [weak self] in
                    try? await Task.sleep(nanoseconds: 5_000_000_000)
                    await self?.performCheck(showNativeAlert: false)
                }
            }
        }
    }

    /// Menu bar action — triggers a check and shows a native macOS dialog with the result.
    @objc func checkForUpdates(_ sender: Any?) {
        Task {
            await performCheck(showNativeAlert: true)
        }
    }

    /// Settings UI action — updates `state` inline without blocking modal alerts.
    func checkForUpdatesInline() {
        Task {
            await performCheck(showNativeAlert: false)
        }
    }

    func copyBrewUpgradeCommand() {
        let pb = NSPasteboard.general
        pb.clearContents()
        pb.setString("brew upgrade --cask kleinski/sidekick/sidekick", forType: .string)
    }

    nonisolated func validateMenuItem(_ menuItem: NSMenuItem) -> Bool {
        true
    }

    private func performCheck(showNativeAlert: Bool) async {
        guard canCheckForUpdates else { return }
        canCheckForUpdates = false
        state = .checking
        defer { canCheckForUpdates = true }

        do {
            var request = URLRequest(url: Self.latestReleaseAPI)
            request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
            request.setValue("SideKick/\(AppInfo.version)", forHTTPHeaderField: "User-Agent")
            request.timeoutInterval = 12

            let (data, response) = try await URLSession.shared.data(for: request)
            guard let http = response as? HTTPURLResponse, (200...299).contains(http.statusCode) else {
                let code = (response as? HTTPURLResponse)?.statusCode ?? -1
                throw NSError(
                    domain: "SideKick.Updater",
                    code: code,
                    userInfo: [NSLocalizedDescriptionKey: "GitHub returned HTTP \(code)"]
                )
            }

            let decoded = try JSONDecoder().decode(GitHubReleaseResponse.self, from: data)
            UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: Self.lastCheckKey)

            let remoteVersion = decoded.tagName.trimmingCharacters(in: CharacterSet(charactersIn: "vV "))
            let dmgAsset = decoded.assets.first(where: { $0.name.lowercased().hasSuffix(".dmg") })
            let releaseURL = URL(string: decoded.htmlUrl) ?? AppInfo.releases
            let dmgURL = dmgAsset.flatMap { URL(string: $0.browserDownloadUrl) }

            let info = ReleaseInfo(
                version: remoteVersion,
                tagName: decoded.tagName,
                title: decoded.name.isEmpty ? "SideKick \(decoded.tagName)" : decoded.name,
                notes: Self.summarizeNotes(decoded.body),
                releaseURL: releaseURL,
                dmgURL: dmgURL
            )

            if Self.isVersion(remoteVersion, newerThan: AppInfo.version) {
                state = .updateAvailable(info)
                if showNativeAlert {
                    presentUpdateAvailableAlert(info)
                }
            } else {
                state = .upToDate(latestVersion: remoteVersion)
                if showNativeAlert {
                    presentUpToDateAlert(currentVersion: AppInfo.version)
                }
            }
        } catch {
            let msg = error.localizedDescription
            state = .failed(msg)
            if showNativeAlert {
                presentErrorAlert(msg)
            }
        }
    }

    private func presentUpToDateAlert(currentVersion: String) {
        NSApp.activate(ignoringOtherApps: true)
        let alert = NSAlert()
        alert.messageText = "You're up to date!"
        alert.informativeText = "SideKick \(currentVersion) is currently the newest version available."
        alert.alertStyle = .informational
        alert.icon = NSApp.applicationIconImage
        alert.addButton(withTitle: "OK")
        alert.runModal()
    }

    private func presentUpdateAvailableAlert(_ info: ReleaseInfo) {
        NSApp.activate(ignoringOtherApps: true)
        let alert = NSAlert()
        alert.messageText = "SideKick v\(info.version) Available"
        let brewHint = isInstalledViaHomebrew
            ? "\n\nInstalled via Homebrew: run `brew upgrade --cask kleinski/sidekick/sidekick`."
            : ""
        alert.informativeText = "\(info.notes)\(brewHint)"
        alert.alertStyle = .informational
        alert.icon = NSApp.applicationIconImage

        if isInstalledViaHomebrew {
            alert.addButton(withTitle: "Copy brew upgrade")
            alert.addButton(withTitle: "Download DMG")
        } else {
            alert.addButton(withTitle: "Download DMG")
            alert.addButton(withTitle: "View on GitHub")
        }
        alert.addButton(withTitle: "Later")

        let resp = alert.runModal()
        if isInstalledViaHomebrew {
            if resp == .alertFirstButtonReturn {
                copyBrewUpgradeCommand()
            } else if resp == .alertSecondButtonReturn {
                NSWorkspace.shared.open(info.dmgURL ?? info.releaseURL)
            }
        } else {
            if resp == .alertFirstButtonReturn {
                NSWorkspace.shared.open(info.dmgURL ?? info.releaseURL)
            } else if resp == .alertSecondButtonReturn {
                NSWorkspace.shared.open(info.releaseURL)
            }
        }
    }

    private func presentErrorAlert(_ message: String) {
        NSApp.activate(ignoringOtherApps: true)
        let alert = NSAlert()
        alert.messageText = "Update Check Failed"
        alert.informativeText = "Could not check GitHub Releases for updates.\n\n\(message)"
        alert.alertStyle = .warning
        alert.addButton(withTitle: "OK")
        alert.runModal()
    }

    static func isVersion(_ remote: String, newerThan local: String) -> Bool {
        let rParts = remote.split(separator: ".").compactMap { Int($0) }
        let lParts = local.split(separator: ".").compactMap { Int($0) }
        guard !rParts.isEmpty, !lParts.isEmpty else { return false }
        let count = max(rParts.count, lParts.count)
        for i in 0..<count {
            let rv = i < rParts.count ? rParts[i] : 0
            let lv = i < lParts.count ? lParts[i] : 0
            if rv != lv { return rv > lv }
        }
        return false
    }

    private static func summarizeNotes(_ rawBody: String) -> String {
        let lines = rawBody
            .components(separatedBy: .newlines)
            .map { $0.trimmingCharacters(in: .whitespaces) }
            .filter { !$0.isEmpty && !$0.hasPrefix(">") && !$0.hasPrefix("---") && !$0.hasPrefix("## SideKick") }
            .prefix(8)
        let joined = lines.joined(separator: "\n")
        return joined.isEmpty ? "A new version of SideKick is available on GitHub." : joined
    }

    private struct GitHubReleaseResponse: Decodable {
        let tagName: String
        let name: String
        let body: String
        let htmlUrl: String
        let assets: [Asset]

        struct Asset: Decodable {
            let name: String
            let browserDownloadUrl: String

            enum CodingKeys: String, CodingKey {
                case name
                case browserDownloadUrl = "browser_download_url"
            }
        }

        enum CodingKeys: String, CodingKey {
            case tagName = "tag_name"
            case name
            case body
            case htmlUrl = "html_url"
            case assets
        }
    }
}

enum AppInfo {
    static let version: String =
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "dev"

    static let github = URL(string: "https://github.com/kleinski/SideKick")!
    static let releases = URL(string: "https://github.com/kleinski/SideKick/releases")!
    static let changelog = URL(string: "https://github.com/kleinski/SideKick/blob/main/CHANGELOG.md")!
    static let reportProblem = URL(string: "https://github.com/kleinski/SideKick/issues")!
    static let upstreamGithub = URL(string: "https://github.com/tranvuongquocdat/SideScreen")!
    static let upstreamWebsite = URL(string: "https://sidescreen.dev")!
    static let upstreamPro = URL(string: "https://www.sidescreen.dev/pro.html")!
    static let upstreamDonate = URL(string: "https://sidescreen.dev/donate.html")!
}
