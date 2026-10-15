import SwiftUI

/// App theme: direct port of core `settings::Theme` (`preferences.theme`).
/// Raw values match the core/JSON spelling exactly ("System"/"Light"/"Dark")
/// so settings round-trip without mapping tables — same as Android
/// `MainActivity` reading `preferences.optString("theme", "System")`.
enum AppTheme: String, CaseIterable, Equatable {
    case system = "System"
    case light = "Light"
    case dark = "Dark"

    /// Parse the `preferences.theme` string. Unknown/missing → `.system`
    /// (fail-open to the OS appearance, matching Android's `else` branch).
    static func parse(_ raw: String?) -> AppTheme {
        guard let raw = raw else { return .system }
        return AppTheme(rawValue: raw) ?? .system
    }

    /// Read from a decoded settings dict (`CoreBridge.getSettings()` shape).
    static func from(settings: [String: Any]?) -> AppTheme {
        let prefs = settings?["preferences"] as? [String: Any]
        return parse(prefs?["theme"] as? String)
    }

    /// `nil` = follow the system (`.system`); call sites apply via
    /// `.preferredColorScheme(...)`.
    var colorScheme: ColorScheme? {
        switch self {
        case .system: return nil
        case .light: return .light
        case .dark: return .dark
        }
    }
}
