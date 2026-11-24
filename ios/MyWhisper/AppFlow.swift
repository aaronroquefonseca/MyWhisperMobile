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
/// Background modes (explicit v1 defers, foreground-only):
/// - BGTask: NOT registered. Transcription runs on a GCD worker while the
///   app is foreground; iOS suspends mic capture on backgrounding and the
///   partial utterance stays in the core buffer. A future task schedules
///   `finish_utterance` via BGProcessingTask when background dictation is
///   a requirement.
/// - Live Activity: NOT adopted. No ActivityKit widget, no lock-screen
///   recording indicator beyond the system orange dot. A future task adds
///   an ActivityKit extension showing record/working state.
final class AppFlow: ObservableObject, Dictation.Listener {
    @Published var showRecorder = false

    // Core + settings state (Setup screen inputs).
    @Published private(set) var coreVersion = "…"
    @Published private(set) var coreError: String?
    @Published private(set) var settings: [String: Any]?
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

    // Result screen state.
    @Published private(set) var result: ResultView.Content?
    @Published private(set) var resultText = ""
    @Published var shareText: String?

    private let dictation: Dictation

    init(dictation: Dictation = Dictation()) {
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

    private static func appFilesDir() throws -> String {
        let url = try FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask,
            appropriateFor: nil, create: true
        ).appendingPathComponent("MyWhisper", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url.path
    }

    // MARK: - Record gestures (RecordView owner)

    func pressDown() {
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
