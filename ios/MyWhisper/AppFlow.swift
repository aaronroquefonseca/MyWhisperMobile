import SwiftUI
import UIKit

enum AppTab: String, CaseIterable {
    case activity
    case providers
    case dictionary
    case settings

    func title(using strings: TabStrings) -> String {
        switch self {
        case .activity: return strings.activity
        case .providers: return strings.providers
        case .dictionary: return strings.dictionary
        case .settings: return strings.settings
        }
    }

    var systemImage: String {
        switch self {
        case .activity: return "waveform"
        case .providers: return "server.rack"
        case .dictionary: return "book"
        case .settings: return "gearshape"
        }
    }
}

/// Pure `preferences.theme` write transform (read-modify-write twin of
/// `AppTheme.from`): pins the exact settings-dict edit `applyTheme`
/// persists, so XCTest covers the write path without the core.
func applyingTheme(_ theme: AppTheme, to settings: [String: Any]) -> [String: Any] {
    var settings = settings
    var prefs = settings["preferences"] as? [String: Any] ?? [:]
    prefs["theme"] = theme.rawValue
    settings["preferences"] = prefs
    return settings
}

/// Tab labels with Spanish twins (same en/es-twin pattern as `SttStrings` /
/// `SetupStrings`: no `.lproj` infrastructure exists yet, so twins live
/// beside the English copy and resolve via an explicit language code).
struct TabStrings: Equatable {
    let activity: String
    let providers: String
    let dictionary: String
    let settings: String
    let speech: String
    let cleanup: String
    let speechShort: String
    let cleanupShort: String
    let words: String
    let snippets: String

    static let english = TabStrings(
        activity: "Activity",
        providers: "Providers",
        dictionary: "Dictionary",
        settings: "Settings",
        speech: "Speech recognition",
        cleanup: "Text cleanup",
        speechShort: "Speech",
        cleanupShort: "Cleanup",
        words: "Words",
        snippets: "Snippets"
    )
    static let spanish = TabStrings(
        activity: "Actividad",
        providers: "Proveedores",
        dictionary: "Diccionario",
        settings: "Ajustes",
        speech: "Reconocimiento de voz",
        cleanup: "Limpieza de texto",
        speechShort: "Voz",
        cleanupShort: "Limpieza",
        words: "Palabras",
        snippets: "Fragmentos"
    )

    static func current(languageCode: String?) -> TabStrings {
        languageCode == "es" ? .spanish : .english
    }
}

/// Composition root: owns core open, settings/theme and mic permission. Dictation itself runs in
/// the keyboard (`KeyboardCoreDictation`).
final class AppFlow: ObservableObject {
    // Core + settings state.
    @Published private(set) var coreVersion = "…"
    @Published private(set) var coreError: String?
    @Published private(set) var settings: [String: Any]? {
        didSet {
            let prefs = settings?["preferences"] as? [String: Any]
            UserDefaults.standard.set(prefs?["black_background"] as? Bool ?? false, forKey: WhisperColors.pureBlackKey)
            MicRoute.share(from: settings)
        }
    }
    @Published private(set) var theme: AppTheme = .system
    @Published private(set) var micGranted = false

    init() {
        micGranted = MicPermission.current()
        openCore()
    }

    // MARK: - Core lifecycle

    func openCore() {
        do {
            let dir = try Self.appFilesDir()
            let key = try CoreBridge.dbKeyHex()
            coreVersion = try CoreBridge.open(filesDir: dir, dbKeyHex: key)
            coreError = nil
            reloadSettings()
            // Android starts sync with the app when it's on; iOS keeps it running while open.
            if (settings?["sync"] as? [String: Any])?["enabled"] as? Bool == true {
                DispatchQueue.global(qos: .utility).async { _ = try? CoreBridge.syncStart() }
            }
        } catch {
            coreError = CoreError.displayMessage(for: error)
        }
    }

    func reloadSettings() {
        do {
            settings = try CoreBridge.getSettings()
            theme = AppTheme.from(settings: settings)
        } catch {
            coreError = CoreError.displayMessage(for: error)
        }
    }

    /// The core's files live in the app group, where the keyboard dictates with the same database.
    /// The first launch with a group moves the older private folder there; without a group (or if
    /// the move fails) the app keeps the private folder and only the keyboard loses dictation.
    private static func appFilesDir() throws -> String {
        let manager = FileManager.default
        let legacy = try manager.url(
            for: .applicationSupportDirectory, in: .userDomainMask,
            appropriateFor: nil, create: true
        ).appendingPathComponent("MyWhisper", isDirectory: true)
        if let shared = KeyboardPreferences.coreDirectoryURL {
            do {
                if !manager.fileExists(atPath: shared.path), manager.fileExists(atPath: legacy.path) {
                    try manager.moveItem(at: legacy, to: shared)
                }
                try manager.createDirectory(at: shared, withIntermediateDirectories: true)
                return shared.path
            } catch {
                // Fall through to the private folder, which still has the data.
            }
        }
        try manager.createDirectory(at: legacy, withIntermediateDirectories: true)
        return legacy.path
    }

    // MARK: - Setup actions

    func refreshMicPermission() {
        micGranted = MicPermission.current()
    }

    func requestMic() {
        if MicPermission.isDenied {
            openSystemSettings()
        } else {
            MicPermission.request { [weak self] granted in
                self?.micGranted = granted
            }
        }
    }

    func openSystemSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }

    /// Persist the theme to core `preferences.theme` (read-modify-write,
    /// same pattern as `SttSettingsStore.persist`): `SetupView` reads the
    /// theme back from settings on every launch, so a local-only set would
    /// revert to System on relaunch.
    func applyTheme(_ next: AppTheme) {
        do {
            let updated = applyingTheme(next, to: try CoreBridge.getSettings())
            try CoreBridge.putSettings(updated)
            settings = updated
            theme = next
        } catch {
            coreError = CoreError.displayMessage(for: error)
        }
    }

    /// Read-modify-write of the core settings, for screens that change a few keys.
    func updateSettings(_ change: (inout [String: Any]) -> Void) {
        do {
            var updated = try CoreBridge.getSettings()
            change(&updated)
            try CoreBridge.putSettings(updated)
            reloadSettings()
        } catch {
            coreError = CoreError.displayMessage(for: error)
        }
    }

    /// Persist the ranked mic kinds; the keyboard reads them at each recording.
    func applyMicPriority(_ order: [String]) {
        do {
            let updated = MicRoute.applying(order, to: try CoreBridge.getSettings())
            try CoreBridge.putSettings(updated)
            settings = updated
        } catch {
            coreError = CoreError.displayMessage(for: error)
        }
    }
}
