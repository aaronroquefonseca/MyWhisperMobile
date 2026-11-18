import Foundation

/// DEPRECATED PIPELINE (v1 wires `Dictation`, not this): `Transcriber`,
/// `TranscriptionState`, `TranscriptionEngine`, `CoreTranscriptionEngine`,
/// and `TranscriptionListener` have zero production references — pairing
/// `Transcriber` with `AudioCapture` double-opens the core utterance
/// (`startUtterance` in both paths; see `AppFlow`). Kept (not deleted)
/// because `SttPipelineTests` still pins the session-token/cancel safety
/// semantics that `Dictation` re-implements; unifying the two controllers
/// is convergence debt. Everything else in this file (`SttFailure`,
/// `SttStrings`, `mapSttError`, copies) is live and used by `AppFlow` /
/// `ResultView`.
///
/// Utterance finish pipeline: PCM streaming during record, then the core
/// `server_stt` chain via `CoreBridge.finishUtterance` on a background queue.
///
/// Mirrors Android `Dictation` (finish/cancel session semantics) minus the
/// IME/local-hook parts, which have no iOS counterpart:
/// - No on-device hook: mobile v1 is server-only, so there is no
///   `localTranscribe` — the core chain skips `Local` entries itself.
/// - No text commit: iOS cannot inject text into other apps (see
///   `Delivery.swift`). Results go to `ResultView` + clipboard/share.
/// - No audio capture here: the caller (mic owner) streams PCM via
///   `pushPcmChunk`; this type owns session state, the bg finish worker,
///   witness-cancel semantics, and error mapping.
///
/// Threading: `finishUtterance` blocks on the network — it always runs on
/// `workers`, never the caller thread. State mutation is lock-guarded and
/// every method is safe to call from any thread, but listener callbacks hop
/// to the main queue asynchronously: callbacks issued from different caller
/// threads can arrive out of call order, so `currentState` (synchronous) is
/// the source of truth, not callback order.
@available(*, deprecated, message: "Dead pipeline: v1 wires Dictation + AudioCapture (see AppFlow).")
enum TranscriptionState: Equatable {
    case idle
    case recording
    case working
}

/// User-facing STT failure categories. Android parity
/// (`OverlayService.shouldSurfaceError`):
/// only silence/too-short stay silent (empty state) — everything else,
/// including cancel/supersede races from a provider switch, surfaces.
enum SttFailureCategory: Equatable {
    /// Routine drop: never an error UI (empty state instead).
    case routineDrop
    case network
    case auth
    case server
    /// Chain has no usable remote entry (Local-only or unconfigured).
    case localSkipped
    /// Gemini cloud STT: explicitly unimplemented.
    case geminiUnsupported
}

/// A mapped failure: machine category + user string + raw core text.
struct SttFailure: Equatable {
    let category: SttFailureCategory
    let userMessage: String
    let raw: String
}

/// Same user-facing copy as the Android `stt_chain_sub` skip clause
/// (PR #40 Local-enablement rewrite): a Local entry without its verified
/// model pack is skipped in favor of the server chain.
let localSkippedCopy = "Local on-device speech is experimental and off by default — "
    + "without its verified model pack the entry is skipped and the server chain is used."

/// Spanish twin (Android values-es `stt_chain_sub` skip clause).
let localSkippedCopyEs = "La voz local es experimental y está apagada por defecto — "
    + "sin su pack verificado la entrada se omite y se usa la cadena del servidor."

/// Same user-facing copy as Android `stt_gemini_note`.
let geminiUnsupportedCopy = "Gemini speech is not implemented in this build yet."

/// Spanish twin (Android values-es `stt_gemini_note`).
let geminiUnsupportedCopyEs = "La voz de Gemini aún no está disponible en esta versión."

/// Empty-state hint for genuine stop-with-silence (not an error).
let silenceEmptyCopy = "No speech captured — try again."

/// Spanish twin (Android values-es parity).
let silenceEmptyCopyEs = "No se capturó voz — inténtalo de nuevo."

/// User-facing pipeline strings with Spanish twins (Android values-es
/// parity). No `.lproj` infrastructure exists yet and no thread owns it,
/// so twins live beside the English copy and resolve via an explicit
/// language code — pure and unit-tested. A future localization task moves
/// these to String Catalogs.
struct SttStrings: Equatable {
    let transcribing: String
    let copy: String
    let share: String
    let retry: String
    let tryAgain: String
    let emptyHint: String
    let localSkipped: String
    let geminiUnsupported: String

    static let english = SttStrings(
        transcribing: "Transcribing…",
        copy: "Copy",
        share: "Share",
        retry: "Retry",
        tryAgain: "Try again",
        emptyHint: silenceEmptyCopy,
        localSkipped: localSkippedCopy,
        geminiUnsupported: geminiUnsupportedCopy
    )
    static let spanish = SttStrings(
        transcribing: "Transcribiendo…",
        copy: "Copiar",
        share: "Compartir",
        retry: "Reintentar",
        tryAgain: "Inténtalo de nuevo",
        emptyHint: silenceEmptyCopyEs,
        localSkipped: localSkippedCopyEs,
        geminiUnsupported: geminiUnsupportedCopyEs
    )

    /// `languageCode` is `Locale.current.languageCode` at call sites;
    /// explicit here so tests pin both languages.
    static func current(languageCode: String?) -> SttStrings {
        languageCode == "es" ? .spanish : .english
    }
}

/// Pure error mapping: core `finish_utterance` text → user-facing failure.
/// Matches Android exactly: only silence/too-short stay silent; every other
/// chain error — including the provider-switch "cancelled (chain
/// superseded…)" witness message — surfaces with its raw text. (Own-session
/// witness-cancel drops never reach mapping: the worker returns early.)
/// `languageCode` (`Locale.current.languageCode` at call sites, nil =
/// English) selects the user-facing copy; categories and `raw` never vary.
/// Order matters: routine drops first, then explicit provider states, then
/// transport classes.
func mapSttError(_ raw: String, languageCode: String? = nil) -> SttFailure {
    let strings = SttStrings.current(languageCode: languageCode)
    let lower = raw.lowercased()
    if lower.contains("too short") || lower.contains("silence") || lower.contains("empty transcript") {
        return SttFailure(category: .routineDrop, userMessage: strings.emptyHint, raw: raw)
    }
    if lower.contains("gemini") {
        return SttFailure(category: .geminiUnsupported, userMessage: strings.geminiUnsupported, raw: raw)
    }
    if lower.contains("no server stt configured")
        || lower.contains("on-device inference not supported")
        || lower.contains("not supported yet") {
        return SttFailure(category: .localSkipped, userMessage: strings.localSkipped, raw: raw)
    }
    if lower.contains("401") || lower.contains("403") || lower.contains("unauthorized")
        || lower.contains("invalid api key") || lower.contains("authentication")
        || lower.contains("auth ") || lower.hasPrefix("auth") {
        return SttFailure(category: .auth, userMessage: raw, raw: raw)
    }
    if lower.contains("request:") || lower.contains("ws connect") || lower.contains("ws send")
        || lower.contains("ws read") || lower.contains("ws done") || lower.contains("timeout")
        || lower.contains("timed out") || lower.contains("network") || lower.contains("offline")
        || lower.contains("connection") || lower.contains("unreachable") || lower.contains("dns")
        || lower.contains("could not connect") || lower.contains("connection refused") {
        return SttFailure(category: .network, userMessage: raw, raw: raw)
    }
    return SttFailure(category: .server, userMessage: raw, raw: raw)
}

/// Pure toast/banner filter mirroring Android `shouldSurfaceError` exactly:
/// only the silence/too-short family stays silent.
func shouldSurfaceSttError(_ failure: SttFailure) -> Bool {
    failure.category != .routineDrop
}

/// Raw-string overload for call sites holding core text (e.g. mic errors).
/// Mirrors Android `shouldSurfaceError(String)` exactly: only messages
/// containing the three routine-drop tags stay silent.
func shouldSurfaceSttMessage(_ message: String) -> Bool {
    let lower = message.lowercased()
    return lower.contains("too short") == false
        && lower.contains("silence") == false
        && lower.contains("empty transcript") == false
}

/// Transport for the core utterance calls. Default is the real bridge;
/// tests inject a fake (no FFI, no network).
@available(*, deprecated, message: "Dead pipeline: v1 wires Dictation + AudioCapture (see AppFlow).")
protocol TranscriptionEngine {
    func startUtterance(sampleRate: UInt32) throws
    func pushPcm(_ samples: [Int16]) throws
    func finishUtterance() throws -> (text: String, engine: String)
    func cancelUtterance() throws
}

/// Live engine: thin wrapper over the frozen `CoreBridge` API.
@available(*, deprecated, message: "Dead pipeline: v1 wires Dictation + AudioCapture (see AppFlow).")
struct CoreTranscriptionEngine: TranscriptionEngine {
    func startUtterance(sampleRate: UInt32) throws {
        try CoreBridge.startUtterance(sampleRate: sampleRate)
    }

    func pushPcm(_ samples: [Int16]) throws {
        try CoreBridge.pushPcm(samples)
    }

    func finishUtterance() throws -> (text: String, engine: String) {
        try CoreBridge.finishUtterance()
    }

    func cancelUtterance() throws {
        try CoreBridge.cancelUtterance()
    }
}

/// Listener: all calls arrive on the main queue.
@available(*, deprecated, message: "Dead pipeline: v1 wires Dictation + AudioCapture (see AppFlow).")
protocol TranscriptionListener: AnyObject {
    func transcriptionStateChanged(_ state: TranscriptionState)
    func transcriptionDidFinish(text: String, engine: String)
    func transcriptionDidFail(_ failure: SttFailure)
}

/// Session-guarded finish pipeline (Android `Dictation` witness parity).
///
/// `session` is bumped on every `startRecording` and every `cancel`, and
/// the finish worker snapshots it before blocking: any token mismatch
/// after the tail sleep, after the blocking transcribe return, or before
/// commit means cancel won — drop without committing and without showing
/// an error. State already belongs to `cancel()` or the next recording,
/// so the stale worker must not touch it either.
///
/// Tail capture (Android parity): `stopAndTranscribe` flips to `.working`
/// but the mic keeps streaming through the release tail — so PCM
/// acceptance is gated on `pushGen` (the session still being recorded),
/// cleared by the worker immediately before the blocking
/// `finishUtterance()` handoff, not on the state flip.
@available(*, deprecated, message: "Dead pipeline: v1 wires Dictation + AudioCapture (see AppFlow).")
final class Transcriber {
    weak var listener: TranscriptionListener?

    private let engine: TranscriptionEngine
    private let workers: DispatchQueue
    private let releaseDelayMs: () -> Int
    private let lock = NSLock()
    private var state: TranscriptionState = .idle
    private var session: Int64 = 0
    /// Session whose PCM is still accepted (0 = none). Opened after a
    /// successful core start, shut by `cancel()` and by the finish worker
    /// right before the transcribe handoff.
    private var pushGen: Int64 = 0

    /// - Parameter releaseDelayMs: extra listening tail after stop (clamped
    ///   0…2000 ms, runs on the worker; a cancel during the tail still wins).
    init(
        engine: TranscriptionEngine = CoreTranscriptionEngine(),
        workers: DispatchQueue = DispatchQueue(label: "com.mywhisper.transcribe", qos: .userInitiated),
        releaseDelayMs: @escaping () -> Int = { 0 },
        listener: TranscriptionListener? = nil
    ) {
        self.engine = engine
        self.workers = workers
        self.releaseDelayMs = releaseDelayMs
        self.listener = listener
    }

    var currentState: TranscriptionState {
        lock.lock(); defer { lock.unlock() }
        return state
    }

    /// Begin an utterance: opens the core recorder and publishes a fresh
    /// session. Drops unless IDLE (bounded admission: at most one finish
    /// is ever queued, mirroring Android). Never holds the lock across
    /// the FFI call — admission is claimed synchronously under the lock,
    /// the blocking core call runs outside it.
    func startRecording(sampleRate: UInt32 = 16_000) {
        lock.lock()
        guard state == .idle else {
            lock.unlock()
            return
        }
        state = .recording
        session += 1
        let gen = session
        lock.unlock()
        do {
            try engine.startUtterance(sampleRate: sampleRate)
        } catch {
            lock.lock()
            // Revert only when no newer session claimed the slot: a
            // cancel that landed during the FFI window already owns the
            // state (and already emitted idle) — leave it alone.
            if gen == session {
                state = .idle
                pushGen = 0
                lock.unlock()
                emitState(.idle)
            } else {
                lock.unlock()
            }
            report(error: error)
            return
        }
        lock.lock()
        // Open the PCM gate only for the session that survived the FFI
        // window (a cancel above bumped past gen → gate stays shut).
        if gen == session {
            pushGen = gen
        }
        lock.unlock()
        emitState(.recording)
    }

    /// Stream captured PCM into the core recorder. Accepted while the
    /// session's gate is open — including the release tail after stop
    /// (state already `.working`); push errors are swallowed (Android
    /// parity: the recorder buffer already advanced; finish surfaces
    /// real failures).
    func pushPcmChunk(_ samples: [Int16]) {
        guard samples.isEmpty == false else { return }
        lock.lock()
        let accepting = pushGen == session && session != 0
        lock.unlock()
        guard accepting else { return }
        do {
            try engine.pushPcm(samples)
        } catch {
            // Swallow: finish reports the utterance outcome.
        }
    }

    /// Stop and transcribe on the bg queue. Safe from any thread. The tail
    /// config is read BEFORE locking (closures never run under the lock).
    func stopAndTranscribe() {
        let tail = min(max(releaseDelayMs(), 0), 2000)
        lock.lock()
        guard state == .recording else {
            lock.unlock()
            return
        }
        state = .working
        let gen = session
        lock.unlock()
        emitState(.working)
        workers.async { [weak self] in
            self?.runFinish(gen: gen, tailMs: tail)
        }
    }

    /// Cancel wins over everything: invalidate the finish worker FIRST so
    /// a blocked `finishUtterance` that returns later drops, never commits.
    /// The session bump also shuts the PCM gate (`pushGen` goes stale).
    func cancel() {
        lock.lock()
        guard state != .idle else {
            lock.unlock()
            return
        }
        session += 1
        pushGen = 0
        state = .idle
        lock.unlock()
        emitState(.idle)
        do {
            try engine.cancelUtterance()
        } catch {
            // Cancel is best-effort; the session bump already won.
        }
    }

    // MARK: - Worker

    private func runFinish(gen: Int64, tailMs: Int) {
        if tailMs > 0 {
            Thread.sleep(forTimeInterval: Double(tailMs) / 1000.0)
        }
        // Cancelled while tailing: return silently, state belongs to
        // cancel() or a new recording.
        if isStale(gen) {
            return
        }
        lock.lock()
        // Tail over: shut the PCM gate immediately before the blocking
        // transcribe handoff — tail samples streamed until this point.
        // (When still current this always matches; the `if` is defensive:
        // a stale gen returned above.)
        if gen == session {
            pushGen = 0
        }
        lock.unlock()
        let outcome: Result<(text: String, engine: String), Error>
        do {
            outcome = .success(try engine.finishUtterance())
        } catch {
            outcome = .failure(error)
        }
        // Cancelled while blocked in transcription: drop, never commit.
        // Rechecked again inside the main-queue delivery block below:
        // a cancel landing between this check and delivery must still win.
        if isStale(gen) {
            return
        }
        let blank: Bool
        switch outcome {
        case .success(let result):
            blank = result.text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
            if blank == false {
                deliverOnMain(gen: gen) { [weak self] in
                    self?.listener?.transcriptionDidFinish(text: result.text, engine: result.engine)
                }
                return
            }
            // Genuine stop-with-silence → empty state, never a commit.
            // (A cancelled session dropped above, keeping its silence.)
            deliverOnMain(gen: gen) { [weak self] in
                let failure = SttFailure(
                    category: .routineDrop, userMessage: silenceEmptyCopy, raw: "silence"
                )
                self?.listener?.transcriptionDidFail(failure)
            }
        case .failure(let error):
            deliverOnMain(gen: gen) { [weak self] in
                guard let self = self else { return }
                self.listener?.transcriptionDidFail(self.sttFailure(for: error))
            }
        }
    }

    /// Hop to main, then — inside ONE locked region — recheck the witness
    /// and mark idle, so a cancel that bumped the session always wins over
    /// a stale delivery regardless of thread interleaving. No check-then-act
    /// gap: the commit decision and the state mutation are atomic.
    private func deliverOnMain(gen: Int64, deliver: @escaping () -> Void) {
        DispatchQueue.main.async { [weak self] in
            guard let self = self else { return }
            self.lock.lock()
            guard gen == self.session else {
                self.lock.unlock()
                return
            }
            self.state = .idle
            self.lock.unlock()
            self.listener?.transcriptionStateChanged(.idle)
            deliver()
        }
    }

    private func report(error: Error) {
        emitFailure(sttFailure(for: error))
    }

    private func sttFailure(for error: Error) -> SttFailure {
        let raw: String
        if let core = error as? CoreError {
            switch core {
            case .coreError(let message), .badReply(let message):
                raw = message
            case .nullReply:
                raw = "empty reply from speech core"
            }
        } else {
            raw = error.localizedDescription
        }
        return mapSttError(raw)
    }

    // MARK: - Helpers (main-queue emission, lock-guarded state)

    private func isStale(_ gen: Int64) -> Bool {
        lock.lock(); defer { lock.unlock() }
        return gen != session
    }

    private func emitState(_ next: TranscriptionState) {
        DispatchQueue.main.async { [weak self] in
            self?.listener?.transcriptionStateChanged(next)
        }
    }

    private func emitFailure(_ failure: SttFailure) {
        DispatchQueue.main.async { [weak self] in
            self?.listener?.transcriptionDidFail(failure)
        }
    }
}
