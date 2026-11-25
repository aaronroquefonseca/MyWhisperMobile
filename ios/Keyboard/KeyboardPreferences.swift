import Foundation

enum KeyboardLanguage: String, CaseIterable, Codable, Identifiable {
    case englishUS = "en_US"
    case englishUK = "en_GB"
    case spanish = "es"

    var id: String { rawValue }

    var title: String {
        switch self {
        case .englishUS: return "English (US)"
        case .englishUK: return "English (UK)"
        case .spanish: return "Español"
        }
    }

    var spaceTitle: String {
        switch self {
        case .englishUS: return "English"
        case .englishUK: return "English (UK)"
        case .spanish: return "Español"
        }
    }

    static var preferredEnglish: Self {
        let english = Locale.preferredLanguages.map(Locale.init(identifier:)).first { $0.language.languageCode?.identifier == "en" }
        return english?.region?.identifier == "GB" ? .englishUK : .englishUS
    }

    static var preferred: Self {
        let supported = Locale.preferredLanguages.map(Locale.init(identifier:)).first {
            $0.language.languageCode?.identifier == "en" || $0.language.languageCode?.identifier == "es"
        }
        return supported?.language.languageCode?.identifier == "es" ? .spanish : preferredEnglish
    }
}

struct KeyboardPreferences: Codable, Equatable {
    var autoCapitalization = true
    var autoCorrection = true
    var predictive = true
    var periodShortcut = true
    var enableCapsLock = true
    var characterPreview = true
    var keyClicks = true
    var swipeTyping: Bool?
    private var swipeLanguage: String?
    private var enabledLanguages: [KeyboardLanguage]?
    private var primaryLanguage: KeyboardLanguage?
    var swipeToSwitch: Bool?
    // Optional so older saved settings still decode. Not in `transferText`: new bits would break existing codes.
    var haptics: Bool?
    var numberRow: Bool?
    var periodKey: Bool?
    var sideButton: Bool?

    static let defaultsKey = "mw.keyboard.settings.v1"
    static let group = "group.com.mywhisper.mobile"
    static let transferPrefix = "MyWhisper-K1-"
    private static let flags: [WritableKeyPath<Self, Bool>] = [
        \.autoCapitalization, \.autoCorrection, \.predictive, \.periodShortcut,
        \.enableCapsLock, \.characterPreview, \.keyClicks, \.usesSwipeTyping, \.switchesLanguageBySwipe,
    ]

    var usesSwipeTyping: Bool {
        get { swipeTyping != false }
        set { swipeTyping = newValue }
    }

    var switchesLanguageBySwipe: Bool {
        get { swipeToSwitch != false }
        set { swipeToSwitch = newValue }
    }

    var usesHaptics: Bool {
        get { haptics == true }
        set { haptics = newValue }
    }

    var showsNumberRow: Bool {
        get { numberRow == true }
        set { numberRow = newValue }
    }

    var showsPeriodKey: Bool {
        get { periodKey == true }
        set { periodKey = newValue }
    }

    /// Settings/emoji button opposite the mic, so suggestions sit centered.
    var showsSideButton: Bool {
        get { sideButton != false }
        set { sideButton = newValue }
    }

    var languages: [KeyboardLanguage] {
        let enabled = enabledLanguages ?? [legacyLanguage, legacyLanguage == .spanish ? .preferredEnglish : .spanish]
        return KeyboardLanguage.allCases.filter(enabled.contains)
    }

    var defaultLanguage: KeyboardLanguage {
        get {
            if let primaryLanguage, languages.contains(primaryLanguage) { return primaryLanguage }
            return languages.contains(legacyLanguage) ? legacyLanguage : (languages.first ?? .preferred)
        }
        set {
            guard languages.contains(newValue) else { return }
            primaryLanguage = newValue
        }
    }

    private var legacyLanguage: KeyboardLanguage {
        switch swipeLanguage {
        case "es": return .spanish
        case "en": return .preferredEnglish
        default: return .preferred
        }
    }

    mutating func setLanguage(_ language: KeyboardLanguage, enabled: Bool) {
        var updated = languages
        if enabled {
            if !updated.contains(language) { updated.append(language) }
        } else {
            guard updated.count > 1 else { return }
            updated.removeAll { $0 == language }
        }
        primaryLanguage = updated.contains(defaultLanguage) ? defaultLanguage : updated.first
        enabledLanguages = updated
    }

    func language(after current: KeyboardLanguage, movingRight: Bool) -> KeyboardLanguage {
        let enabled = languages
        guard let index = enabled.firstIndex(of: current) else { return defaultLanguage }
        return enabled[(index + (movingRight ? -1 : 1) + enabled.count) % enabled.count]
    }

    static func load() -> Self {
        let shared = sharedURL.flatMap { try? Data(contentsOf: $0) }
        for data in [shared, UserDefaults.standard.data(forKey: defaultsKey)].compactMap({ $0 }) {
            if let settings = try? JSONDecoder().decode(Self.self, from: data) { return settings }
        }
        return Self()
    }

    func saveLocally() {
        guard let data = try? JSONEncoder().encode(self) else { return }
        UserDefaults.standard.set(data, forKey: Self.defaultsKey)
    }

    /// Writes only from the containing app. A keyboard without Full Access can read the shared file.
    @discardableResult
    func saveFromApp() -> Bool {
        saveLocally()
        guard let url = Self.sharedURL, let data = try? JSONEncoder().encode(self) else { return false }
        do {
            try data.write(to: url, options: .atomic)
            return true
        } catch {
            return false
        }
    }

    static let sharedURL: URL? = {
        let manager = FileManager.default
        let bundleID = Bundle.main.bundleIdentifier ?? ""
        // SideStore appends the team to the group and inserts it before the extension suffix.
        let team = bundleID.split(separator: ".").first { component in
            component.utf8.count == 10 && component.utf8.allSatisfy { (48...57).contains($0) || (65...90).contains($0) }
        }
        let identifiers = [group] + (team.map { [group + "." + String($0)] } ?? [])
        return identifiers.lazy.compactMap { manager.containerURL(forSecurityApplicationGroupIdentifier: $0) }
            .first?.appendingPathComponent("keyboard-preferences.json")
    }()

    /// Manual transfer for sideloads whose signing profile omits the shared group.
    /// Contains typing toggles and language choices only.
    var transferText: String {
        var bits = 0
        for (index, key) in Self.flags.enumerated() where self[keyPath: key] { bits |= 1 << index }
        for (index, language) in KeyboardLanguage.allCases.enumerated() {
            if languages.contains(language) { bits |= 1 << (index + Self.flags.count) }
            if language == defaultLanguage { bits |= index << (Self.flags.count + 3) }
        }
        return Self.transferPrefix + String(bits, radix: 16)
    }

    init() {}

    init?(transferText: String) {
        guard transferText.hasPrefix(Self.transferPrefix),
              let bits = Int(transferText.dropFirst(Self.transferPrefix.count), radix: 16),
              (0..<(1 << (Self.flags.count + 5))).contains(bits) else { return nil }
        let choices = KeyboardLanguage.allCases
        let enabled = choices.enumerated().compactMap { index, language in
            bits & (1 << (index + Self.flags.count)) == 0 ? nil : language
        }
        let primary = bits >> (Self.flags.count + 3)
        guard choices.indices.contains(primary), enabled.contains(choices[primary]) else { return nil }
        enabledLanguages = enabled
        primaryLanguage = choices[primary]
        for (index, key) in Self.flags.enumerated() { self[keyPath: key] = bits & (1 << index) != 0 }
        guard self.transferText == transferText else { return nil }
    }
}
