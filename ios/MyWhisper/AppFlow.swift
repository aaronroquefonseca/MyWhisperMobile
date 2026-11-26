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
    let words: String
    let snippets: String

    static let english = TabStrings(
        activity: "Activity",
        providers: "Providers",
        dictionary: "Dictionary",
        settings: "Settings",
        speech: "Speech recognition",
        cleanup: "Text cleanup",
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
        words: "Palabras",
        snippets: "Fragmentos"
    )

    static func current(languageCode: String?) -> TabStrings {
        languageCode == "es" ? .spanish : .english
    }
}

/// v1 composition root: owns core open, settings/theme, mic permission,
/// the Record→Result pipeline, and result copy/share.
///
/// Pipeline wiring (T10): this owns a `Dictation` — NOT a `Transcriber`.
/// `Transcriber.startRecording` opens the core utterance via its engine
/// AND `AudioCapture.start` unconditionally opens it too
/// (`CoreBridge.startUtterance` in both paths), so pairing Transcriber +
/// AudioCapture double-opens the utterance. `Dictation` is the controller
/// designed for `AudioCapture`: capture opens the utterance, Dictation's
/// `finishTranscription` defaults to `CoreBridge.finishUtterance`, and
/// `RecordView` is a pure view over `Dictation.State`. Unifying
/// `TranscriptionState`/`Dictation.State` (and the engine label
/// `Dictation.Listener.onResult` drops) is convergence debt, not v1 work.
///
final class AppFlow: ObservableObject, Dictation.Listener {
    @Published var showRecorder = false

    // Core + settings state (Setup screen inputs).
    @Published private(set) var coreVersion = "…"
    @Published private(set) var coreError: String?
    @Published private(set) var settings: [String: Any]? {
        didSet {
            let prefs = settings?["preferences"] as? [String: Any]
            UserDefaults.standard.set(prefs?["black_background"] as? Bool ?? false, forKey: WhisperColors.pureBlackKey)
        }
    }
    @Published private(set) var theme: AppTheme = .system

    // Mic + setup-test state.
    @Published private(set) var micGranted = false
    @Published private(set) var testPassed = false
    @Published private(set) var testError: String?

    // Record screen state (mirrors Dictation callbacks, main queue).
    @Published private(set) var recordState: Dictation.State = .idle
    @Published private(set) var toggled = false
    @Published private(set) var micMode = "Smart"
    let levels = LevelStore()
    let keyboardMicPreview = KeyboardMicLevelSession()

    // Result screen state.
    @Published private(set) var result: ResultView.Content?
    @Published private(set) var resultText = ""
    @Published var shareText: String?

    private let dictation: Dictation

    /// Android's mic controls: `preferences.mic_mode`, `hotkeys.smart_tap_ms`, `stt.release_delay_ms`,
    /// read at each press so changes apply without a restart.
    static func liveDictation() -> Dictation {
        func value(_ section: String, _ key: String) -> Any? {
            ((try? CoreBridge.getSettings())?[section] as? [String: Any])?[key]
        }
        return Dictation(
            smartTapMs: { (value("hotkeys", "smart_tap_ms") as? NSNumber)?.int64Value ?? 150 },
            mode: { value("preferences", "mic_mode") as? String ?? "Smart" },
            releaseDelayMs: { (value("stt", "release_delay_ms") as? NSNumber)?.int64Value ?? 0 }
        )
    }

    init(dictation: Dictation = AppFlow.liveDictation()) {
        self.dictation = dictation
        self.dictation.listener = self
        self.dictation.onLevel = { [weak self] peak in
            DispatchQueue.main.async { self?.levels.pushLevel(peak) }
        }
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
            let prefs = settings?["preferences"] as? [String: Any]
            if let mode = prefs?["mic_mode"] as? String, mode.isEmpty == false {
                micMode = mode
            }
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

    // MARK: - Record gestures (RecordView owner)

    func pressDown() {
        keyboardMicPreview.stop()
        guard micGranted else {
            MicPermission.request { [weak self] granted in
                self?.micGranted = granted
            }
            return
        }
        levels.clear()
        dictation.pressDown()
    }

    func pressUp() {
        dictation.pressUp()
    }

    func cancelRecording() {
        dictation.cancel()
    }

    func startKeyboardMicPreview() {
        guard dictation.current == .idle else { return }
        refreshMicPermission()
        guard micGranted else {
            if MicPermission.isDenied {
                openSystemSettings()
            } else {
                MicPermission.request { [weak self] granted in
                    self?.micGranted = granted
                    if granted { self?.keyboardMicPreview.start() }
                }
            }
            return
        }
        keyboardMicPreview.start()
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

    /// The setup test uses the same foreground recording flow as Activity.
    func runSetupTest() {
        retryResult()
        showRecorder = true
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

    /// Persist the ranked mic kinds; `AudioCapture` reads them at each start.
    func applyMicPriority(_ order: [String]) {
        do {
            let updated = MicRoute.applying(order, to: try CoreBridge.getSettings())
            try CoreBridge.putSettings(updated)
            settings = updated
        } catch {
            coreError = CoreError.displayMessage(for: error)
        }
    }

    // MARK: - Result actions (Delivery surface)

    func copyResult() {
        _ = Delivery.copyToClipboard(resultText)
    }

    func shareResult() {
        let items = Delivery.shareItems(for: resultText)
        guard let first = items.first as? String else { return }
        shareText = first
    }

    func retryResult() {
        result = nil
        resultText = ""
        testError = nil
    }

    // MARK: - Dictation.Listener (may arrive off-main; hop before publish)

    func onState(_ state: Dictation.State, toggled: Bool) {
        DispatchQueue.main.async { [weak self] in
            self?.recordState = state
            self?.toggled = toggled
        }
    }

    func onResult(_ text: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            // Engine label is unavailable: Dictation.Listener drops the
            // engine string (convergence debt) — empty hides the row.
            self.resultText = text
            self.result = .result(text: text, engine: "")
            self.testPassed = true
            self.testError = nil
        }
    }

    func onError(_ message: String) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            let failure = mapSttError(message)
            let display = ResultView.displayContent(for: .failure(failure))
            switch display {
            case .empty:
                // Genuine silence: empty state, never red.
                self.resultText = ""
                self.result = .empty
            case .failure(let mapped):
                self.resultText = ""
                self.result = .failure(mapped)
                self.testError = mapped.userMessage
            default:
                self.resultText = ""
                self.result = display
            }
        }
    }
}
