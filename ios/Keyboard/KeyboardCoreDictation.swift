import Foundation

/// The keyboard extension's principal class: the shared controller plus the speech core, which
/// only this target links (the unit tests compile the controller without it).
final class MyWhisperKeyboardViewController: KeyboardViewController {
    override var dictation: KeyboardDictating? { KeyboardCoreDictation.shared }
}

/// Dictation through the same speech core and database as the app, so the keyboard uses the
/// providers, dictionary, cleanup and history set up there. Core calls block (the transcription
/// is a network round trip), so they run in order on one background queue.
final class KeyboardCoreDictation: KeyboardDictating {
    static let shared = KeyboardCoreDictation()

    private let queue = DispatchQueue(label: "com.mywhisper.keyboard.dictation", qos: .userInitiated)
    // Touched only on `queue`.
    private var opened = false
    private var beginError: Error?

    func begin(sampleRate: Double) {
        queue.async {
            do {
                try self.openIfNeeded()
                try CoreBridge.startUtterance(sampleRate: UInt32(sampleRate.rounded()))
                self.beginError = nil
            } catch {
                self.beginError = error
            }
        }
    }

    func push(_ samples: [Int16]) {
        queue.async {
            guard self.beginError == nil else { return }
            // The core's finish reports what went wrong with the utterance.
            try? CoreBridge.pushPcm(samples)
        }
    }

    func finish(_ completion: @escaping (Result<String, Error>) -> Void) {
        queue.async {
            let result: Result<String, Error>
            if let error = self.beginError {
                result = .failure(KeyboardDictationError(error))
                DictationIssue.save(DictationIssue(cause: .other, detail: KeyboardDictationError(error).errorDescription ?? ""))
            } else {
                result = Result { try CoreBridge.finishUtterance().text }.flatMapError { error in
                    // Same rules as the app: silence and too-short clips end quietly.
                    let failure = mapSttError(CoreError.displayMessage(for: error), languageCode: Locale.current.language.languageCode?.identifier)
                    guard shouldSurfaceSttError(failure) else { return .success("") }
                    DictationIssue.save(DictationIssue(cause: Self.cause(of: failure), detail: failure.raw))
                    return .failure(KeyboardDictationError.core(failure.userMessage))
                }
                if case .success(let text) = result, !text.isEmpty { DictationIssue.save(nil) }
            }
            self.beginError = nil
            DispatchQueue.main.async { completion(result) }
        }
    }

    func cancel() {
        queue.async {
            self.beginError = nil
            try? CoreBridge.cancelUtterance()
        }
    }

    private static func cause(of failure: SttFailure) -> DictationIssue.Cause {
        switch failure.category {
        case .localSkipped: return .noProvider
        case .auth: return .signIn
        case .network: return .unreachable
        default: return .other
        }
    }

    private func openIfNeeded() throws {
        guard !opened else { return }
        // The app moves its database into the shared container on launch; never start an empty one here.
        guard let directory = KeyboardPreferences.coreDirectoryURL,
              FileManager.default.fileExists(atPath: directory.appendingPathComponent("state.db").path)
        else { throw KeyboardDictationError.setUp }
        _ = try CoreBridge.open(filesDir: directory.path, dbKeyHex: Self.databaseKey())
        opened = true
    }

    /// The app's key through the shared keychain group.
    /// shortcut: iOS builds store state.db unencrypted (core/Cargo.toml), so when the sideload's
    /// signing drops keychain sharing any valid key opens it. Remove the fallback when SQLCipher
    /// lands on iOS: the keyboard must then have the real key.
    private static func databaseKey() -> String {
        if let key = try? SystemKeychainBackend().loadHex(account: KeychainStore.dbKeyAccount),
           KeychainStore.isValidHexKey(key) {
            return key
        }
        return String(repeating: "0", count: KeychainStore.keyLength * 2)
    }
}

enum KeyboardDictationError: LocalizedError {
    case setUp
    case core(String)

    init(_ error: Error) {
        if let error = error as? KeyboardDictationError {
            self = error
        } else {
            self = .core(CoreError.displayMessage(for: error))
        }
    }

    var errorDescription: String? {
        switch self {
        case .setUp: return "Open MyWhisper once to set up dictation"
        case .core(let message): return message
        }
    }
}
