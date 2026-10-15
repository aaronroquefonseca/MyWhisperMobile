import Foundation

/// Local-hook PCM cap: 60s @ 16 kHz mono (utterance copy only).
let maxLocalPcmSamples = 16_000 * 60

/// Stoppable mic handle (production: `AudioCapture`; tests: fake).
protocol DictationCapture: AnyObject {
    func stop()
}

extension Dictation {
    enum State {
        case idle
        case recording
        case working
    }

    protocol Listener: AnyObject {
        func onState(_ state: State, toggled: Bool)
        func onResult(_ text: String)
        func onError(_ message: String)
    }
}

/// Mic-button controller: hold-to-talk / toggle / smart, mirroring
/// the desktop activation modes. Mode comes from settings
/// (`preferences.mic_mode`): Push = hold talks, Tap = press toggles,
/// Smart (default) = release before `smartTapMs` toggles, release
/// after stops; a second press while toggled-on finishes.
///
/// Exact port of Android `Dictation.kt`: IDLE/RECORDING/WORKING,
/// pressDown/pressUp/cancel/shutdown, session-token cancel safety
/// (bump + recheck after tail + after blocking transcribe), release
/// tail, single-thread worker, maxPeak. All state transitions post
/// to `listener`; `finish` transcribes on the worker queue.
///
/// Pure logic, injectable for tests: capture/transcription/clock/log
/// are closures, so XCTest never touches AVAudioEngine or the core.
/// Listener callbacks may arrive on the worker queue — UI owners must
/// hop to the main actor (see `RecordView` wiring).
final class Dictation {
    typealias PcmHandler = ([Int16], Int) -> Void
    /// Production shape: (onPcm, onError, onLevel) -> capture handle.
    /// Stored plain (stored closures are escaping); the init takes it
    /// optional, which is escaping by construction.
    typealias StartCapture = (PcmHandler, (String) -> Void, (Float) -> Void) -> (any DictationCapture)?

    weak var listener: (any Listener)?
    /// Live mic peaks for the pill visualizer (capture thread).
    var onLevel: ((Float) -> Void)?
    /// Loudest peak of the current utterance (capture thread writes,
    /// finish worker reads after capture stops).
    private(set) var maxPeak: Float = 0

    private let smartTapMs: () -> Int64
    private let micMode: () -> String
    private let releaseDelayMs: () -> Int64
    private let nowMs: () -> Int64
    private let startCapture: StartCapture
    private let finishTranscription: () throws -> (String, String)
    private let cancelTranscription: () throws -> Void
    /// Forwards capture PCM to the core recorder. Injected (default
    /// `CoreBridge.pushPcm`) so unit tests fire the capture path
    /// without the native lib.
    private let pushPcm: ([Int16]) throws -> Void
    /// Local-first hook: receives the utterance PCM (16 kHz i16) and
    /// returns (text, engine) on hit, nil on miss. Default nil =
    /// server chain only (no on-device pack on iOS yet). Any throw =
    /// server fallback.
    private let localTranscribe: (([Int16]) throws -> (String, String)?)?
    /// Shutdown hook releasing the on-device recognizer. Injected
    /// (default no-op) so unit tests never touch recognizer state.
    private let releaseLocal: () -> Void
    private let log: (String) -> Void
    /// Single-thread FIFO worker. Injected so tests can settle it
    /// with `queue.sync {}` after releasing their transcribe gate.
    private let workers: DispatchQueue

    /// Utterance PCM copy for the local-first hook: primitive buffer,
    /// no boxing. Grown by doubling, hard-capped at
    /// `maxLocalPcmSamples`. Guarded by `pcmLock`; the buffer is
    /// retained across utterances to avoid realloc churn.
    private let pcmLock = NSLock()
    private var pcmBuffer = [Int16](repeating: 0, count: 16_000 * 10)
    private var pcmSize = 0
    /// True once the utterance outgrew `maxLocalPcmSamples`. Never
    /// silently truncated: the local hook is skipped and the full
    /// audio falls back to the server chain, with a log line. Set
    /// under `pcmLock`, read on the finish worker.
    private var pcmOverflowed = false

    private var state: State = .idle
    private var toggled = false
    private var secondDown = false
    private var downAt: Int64 = 0
    private var capture: (any DictationCapture)?
    /// GCD has no `shutdownNow`: set on `shutdown()` so late presses
    /// and stray callbacks drop. Queued finish blocks still run to
    /// their session-token checks and drop there.
    private var isShutdown = false
    /// Session token: bumped on every new recording (`pressDown`),
    /// every `cancel`, and `shutdown`. The `finish` worker snapshots
    /// it and rechecks after the tail sleep, after a local miss
    /// BEFORE the server upload, AND after the blocking
    /// `finishTranscription` return — a stale token means cancel won,
    /// so drop without committing and without uploading dead audio.
    /// Nullness of `capture` alone is racy (snapshot-then-cancel
    /// still transcribes). Guarded by `sessionLock`.
    private let sessionLock = NSLock()
    private var session = 0

    var current: State { state }

    init(
        smartTapMs: @escaping () -> Int64 = { 150 },
        mode: @escaping () -> String = { "Smart" },
        releaseDelayMs: @escaping () -> Int64 = { 0 },
        nowMs: @escaping () -> Int64 = { Int64(Date().timeIntervalSince1970 * 1_000) },
        startCapture: StartCapture? = nil,
        finishTranscription: @escaping () throws -> (String, String) = { try CoreBridge.finishUtterance() },
        cancelTranscription: @escaping () throws -> Void = { try CoreBridge.cancelUtterance() },
        pushPcm: @escaping ([Int16]) throws -> Void = { try CoreBridge.pushPcm($0) },
        localTranscribe: (([Int16]) throws -> (String, String)?)? = nil,
        releaseLocal: @escaping () -> Void = {},
        log: @escaping (String) -> Void = { msg in NSLog("MwDict %@", msg) },
        workers: DispatchQueue = DispatchQueue(label: "mw-dictation")
    ) {
        self.smartTapMs = smartTapMs
        self.micMode = mode
        self.releaseDelayMs = releaseDelayMs
        self.nowMs = nowMs
        self.startCapture = startCapture ?? Self.defaultStartCapture
        self.finishTranscription = finishTranscription
        self.cancelTranscription = cancelTranscription
        self.pushPcm = pushPcm
        self.localTranscribe = localTranscribe
        self.releaseLocal = releaseLocal
        self.log = log
        self.workers = workers
    }

    /// Production capture: `AudioCapture` at 16 kHz with interruption
    /// routed to `handleInterruption`. Nil (no capture) when the mic
    /// won't start — `pressDown` then drops, staying IDLE.
    private static func defaultStartCapture(
        _ onPcm: @escaping PcmHandler,
        _ onError: @escaping (String) -> Void,
        _ onLevel: @escaping (Float) -> Void
    ) -> (any DictationCapture)? {
        // No owner to interrupt back into from a static factory; the
        // instance path below (`startedCapture`) wires interruptions.
        // This entry only serves constructor-time capture overrides.
        let cap = AudioCapture()
        cap.onPcm = onPcm
        cap.onError = onError
        cap.onLevel = onLevel
        return cap.start() ? cap : nil
    }

    private func startedCapture(
        _ onPcm: @escaping PcmHandler,
        _ onError: @escaping (String) -> Void,
        _ onLevel: @escaping (Float) -> Void
    ) -> (any DictationCapture)? {
        guard let cap = startCapture(onPcm, onError, onLevel) else { return nil }
        if let audio = cap as? AudioCapture {
            audio.onInterruption = { [weak self] in self?.handleInterruption() }
        }
        return cap
    }

    private func set(_ next: State) {
        state = next
        listener?.onState(next, toggled: toggled)
    }

    func pressDown() {
        if isShutdown { return }
        switch state {
        case .working:
            return
        case .recording:
            if toggled {
                // Second press while toggled-on: arm the stop, decided
                // on release (quick tap finishes, slide cancels,
                // hold+release finishes). Never finish on DOWN so
                // slide-to-cancel always gets a chance.
                secondDown = true
                downAt = nowMs()
            }
            return
        case .idle:
            break
        }
        guard let cap = startedCapture(
            { [weak self] buf, count in self?.handlePcm(buf, count: count) },
            { [weak self] msg in
                self?.listener?.onError(msg)
                self?.hardStop()
            },
            { [weak self] peak in
                guard let strongSelf = self else { return }
                if peak > strongSelf.maxPeak { strongSelf.maxPeak = peak }
                strongSelf.onLevel?(peak)
            }
        ) else { return }
        // Bump before publish so workers that see the capture see the token.
        bumpSession()
        capture = cap
        toggled = false
        downAt = nowMs()
        maxPeak = 0
        pcmLock.lock()
        pcmSize = 0
        pcmOverflowed = false
        pcmLock.unlock()
        set(.recording)
    }

    func pressUp() {
        if state != .recording { return }
        if secondDown {
            // Stop press released (slide-cancel preempts via cancel()
            // flipping to IDLE above).
            secondDown = false
            finish()
            return
        }
        if toggled { return }
        let modeName = micMode()
        if modeName == "Push" {
            finish()
        } else if modeName == "Tap" {
            // Toggle: keep recording; the next press finishes.
            toggled = true
            listener?.onState(state, toggled: true)
        } else {
            // Smart: short press toggles, hold talks.
            let held = nowMs() - downAt
            if held < smartTapMs() {
                toggled = true
                listener?.onState(state, toggled: true)
            } else {
                finish()
            }
        }
    }

    func cancel() {
        if state == .idle { return }
        // Invalidate the finish worker FIRST so a blocked
        // finishTranscription that returns later drops, never commits.
        let was = state
        bumpSession()
        log("cancel \(was)->idle session=\(currentSession())")
        hardStop()
        do { try cancelTranscription() } catch { }
    }

    func shutdown() {
        // Invalidate the finish worker FIRST, same as cancel(): a
        // transcription blocked at destroy time must drop, never
        // commit to a dead listener. State is left alone (terminal).
        bumpSession()
        isShutdown = true
        capture?.stop()
        capture = nil
        // Release the on-device recognizer. Never throws (hook is a
        // plain closure; production keeps it non-throwing).
        releaseLocal()
    }

    /// Audio-session interruption (call/Siri) with a live recording:
    /// commit the partial utterance instead of dropping it. Idle or
    /// already working: nothing to keep.
    func handleInterruption() {
        if state == .recording { finish() }
    }

    private func hardStop() {
        capture?.stop()
        capture = nil
        toggled = false
        secondDown = false
        set(.idle)
    }

    private func handlePcm(_ buf: [Int16], count: Int) {
        // Copy: the buffer is reused by the read loop.
        let copy = Array(buf.prefix(count))
        pcmLock.lock()
        let room = max(maxLocalPcmSamples - pcmSize, 0)
        let take = min(count, room)
        if pcmSize + take > pcmBuffer.count {
            let want = min(max(pcmBuffer.count * 2, pcmSize + take), maxLocalPcmSamples)
            pcmBuffer.append(contentsOf: [Int16](repeating: 0, count: want - pcmBuffer.count))
        }
        if take > 0 {
            pcmBuffer.replaceSubrange(pcmSize..<(pcmSize + take), with: copy.prefix(take))
            pcmSize += take
        }
        if take < count, !pcmOverflowed {
            pcmOverflowed = true
            pcmLock.unlock()
            log("local PCM capped at 60s, overflow falls back to server")
        } else {
            pcmLock.unlock()
        }
        do { try pushPcm(copy) } catch { }
    }

    private func finish() {
        if state != .recording { return }
        // Extra listening delay (desktop release tail). Runs on the
        // worker so release stays responsive; the mic keeps capturing
        // meanwhile and a slide-cancel during the tail still wins.
        let tail = min(max(releaseDelayMs(), 0), 2_000)
        toggled = false
        secondDown = false
        set(.working)
        let gen = currentSession()
        log("finish ->working gen=\(gen)")
        workers.async { [weak self] in
            guard let strongSelf = self else { return }
            strongSelf.runFinish(gen: gen, tailMs: tail)
        }
    }

    /// Finish body (worker queue): tail-sleep, stale-tail recheck,
    /// stop capture, transcribe, commit. Every exit after the tail
    /// rechecks the session token — cancel always wins.
    private func runFinish(gen: Int, tailMs tail: Int64) {
        if tail > 0 {
            Thread.sleep(forTimeInterval: Double(tail) / 1_000)
        }
        // Cancelled while tailing: cancel() bumps session, so the
        // token rules — capture nullness alone races (worker may
        // have snapshotted non-null just before cancel). Return
        // silently: state already belongs to cancel()/a new press.
        if gen != currentSession() {
            log("finish drop stale-tail gen=\(gen)")
            return
        }
        guard let cap = capture, gen == currentSession() else {
            log("finish drop stale-capture gen=\(gen)")
            return
        }
        cap.stop()
        capture = nil
        let text: String
        do {
            guard let decoded = try transcribeSnapshot(gen: gen) else { return }
            text = decoded
        } catch {
            if gen == currentSession() {
                let message = (error as? LocalizedError)?.errorDescription ?? "transcription failed"
                listener?.onError(message)
                set(.idle)
            } else {
                log("finish drop stale-error gen=\(gen)")
            }
            return
        }
        commitResult(text, gen: gen)
    }

    /// Local-first hook on the utterance PCM; miss or throw → server
    /// chain (fallback intact). Only the LOCAL hook is guarded: a
    /// server throw propagates to the caller (session-gated drop) so
    /// cancel/shutdown during a blocked transcribe still wins instead
    /// of retrying into a dead worker.
    ///
    /// An overflowed PCM copy is incomplete by construction, so the
    /// local hook is skipped outright (server owns the full audio) —
    /// truncation never commits silently.
    ///
    /// Returns nil without uploading when the session died during a
    /// blocking local decode.
    private func transcribeSnapshot(gen: Int) throws -> String? {
        pcmLock.lock()
        let pcm = Array(pcmBuffer.prefix(pcmSize))
        let overflow = pcmOverflowed
        pcmLock.unlock()
        let local: String?
        if overflow {
            log("local PCM overflowed 60s cap, server fallback")
            local = nil
        } else if let hook = localTranscribe {
            do {
                local = try hook(pcm)?.0
            } catch {
                local = nil
            }
        } else {
            local = nil
        }
        // Cancelled during the (blocking) local decode: the result
        // below already drops post-call, but the server upload itself
        // must not happen for a dead utterance.
        if local == nil, gen != currentSession() { return nil }
        if let decoded = local { return decoded }
        return try finishTranscription().0
    }

    /// Commit path (worker queue): stale sessions drop without ever
    /// touching the listener; blank maps to the "silence" empty state.
    private func commitResult(_ text: String, gen: Int) {
        // Cancelled while blocked in transcription: the call above
        // returned after cancel() — drop, never commit.
        if gen != currentSession() {
            log("finish drop stale-result gen=\(gen)")
            return
        }
        log("utterance max peak=\(maxPeak)")
        log("utterance text len=\(text.count)")
        if text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            // Silence is never committed and never surfaced: the
            // "silence" tag maps to the empty state on every surface.
            // Only a real stop-with-silence reaches here — a cancelled
            // session drops above, keeping its "Cancelled" note.
            log("finish blank gen=\(gen): empty state, no commit")
            listener?.onError("silence")
        } else {
            listener?.onResult(text)
        }
        set(.idle)
    }

    // MARK: - Session token

    private func bumpSession() {
        sessionLock.lock()
        session &+= 1
        sessionLock.unlock()
    }

    private func currentSession() -> Int {
        sessionLock.lock()
        defer { sessionLock.unlock() }
        return session
    }
}
