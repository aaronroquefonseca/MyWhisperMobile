import AVFoundation
import Combine
import Foundation

/// The containing app owns the audio session and the existing Dictation pipeline.
final class KeyboardDictationSession: ObservableObject, Dictation.Listener {
    @Published private(set) var isEnabled = false
    @Published private(set) var error: String?

    let mailbox = KeyboardDictationMailbox.shared
    private var monitor: AudioCapture?
    private let input = KeyboardDictationInput()
    private var timer: Timer?
    private var sessionID = UUID()
    private var expiresAt = Date.distantPast
    private var nextHeartbeat = Date.distantPast
    private var requestID: UUID?
    private var lastStarted: UUID?
    private var phase: KeyboardDictationMailbox.Reply.Phase?
    private var deadline = Date.distantPast

    private lazy var dictation: Dictation = {
        let controller = Dictation(mode: { "Tap" }, startCapture: { [weak self] onPcm, onError, onLevel in
            guard let self, self.monitor?.running == true else {
                onError("Microphone session ended")
                return nil
            }
            do {
                try CoreBridge.startUtterance(sampleRate: UInt32(AudioCapture.sampleRate))
                self.input.connect(onPcm, onLevel: onLevel)
                return self.input
            } catch {
                onError(error.localizedDescription)
                return nil
            }
        })
        controller.listener = self
        return controller
    }()

    var isBusy: Bool { requestID != nil }

    func enable() {
        guard !isEnabled, let mailbox else { return }
        error = nil
        let monitor = AudioCapture()
        monitor.wantsSamples = { [input] in input.isConnected }
        monitor.onPcm = { [input] samples, count in input.receive(samples, count: count) }
        monitor.onInterruption = { [weak self] in
            DispatchQueue.main.async {
                self?.disable()
                self?.error = "The microphone session was interrupted."
            }
        }
        guard monitor.start(prepareUtterance: false) else {
            error = "Couldn’t start the microphone. Check permissions or close the app using it."
            return
        }

        let now = Date()
        sessionID = UUID()
        expiresAt = now.addingTimeInterval(300)
        lastStarted = nil
        guard (try? mailbox.publish(.init(id: sessionID, heartbeat: now))) != nil else {
            monitor.stop()
            error = "MyWhisper couldn’t share its microphone session with the keyboard."
            return
        }
        mailbox.clearReply()
        self.monitor = monitor
        nextHeartbeat = now.addingTimeInterval(1)
        isEnabled = true
        let timer = Timer(timeInterval: 0.2, repeats: true) { [weak self] _ in self?.tick() }
        self.timer = timer
        RunLoop.main.add(timer, forMode: .common)
    }

    func disable() {
        timer?.invalidate()
        timer = nil
        if isBusy { dictation.cancel() }
        requestID = nil
        phase = nil
        monitor?.stop()
        monitor = nil
        mailbox?.clearSession()
        mailbox?.clearReply()
        isEnabled = false
    }

    private func tick() {
        let now = Date()
        guard let mailbox, monitor?.running == true, now < expiresAt else {
            disable()
            return
        }
        if now >= nextHeartbeat {
            do {
                try mailbox.publish(.init(id: sessionID, heartbeat: now))
                nextHeartbeat = now.addingTimeInterval(1)
            } catch {
                disable()
                return
            }
        }
        if let command = mailbox.request, command.belongs(to: sessionID, at: now) {
            switch command.action {
            case .start where phase == nil && lastStarted != command.id:
                lastStarted = command.id
                requestID = command.id
                phase = .recording
                deadline = now.addingTimeInterval(60)
                dictation.pressDown()
                dictation.pressUp()
                if dictation.current == .recording {
                    respond(.recording, to: command.id)
                }
            case .stop where requestID == command.id && phase == .recording:
                finish(now: now)
            case .cancel where requestID == command.id:
                dictation.cancel()
                requestID = nil
                phase = nil
                mailbox.clearReply()
            case .consume where mailbox.reply?.id == command.id:
                mailbox.clearReply()
            default:
                break
            }
        }
        if let requestID, now >= deadline {
            if phase == .recording {
                finish(now: now)
            } else {
                dictation.cancel()
                self.requestID = nil
                phase = nil
                respond(.error, to: requestID, text: "Transcription timed out.")
            }
        }
    }

    private func finish(now: Date) {
        guard let requestID else { return }
        phase = .working
        deadline = now.addingTimeInterval(45)
        respond(.working, to: requestID)
        dictation.pressDown()
        dictation.pressUp()
    }

    private func respond(_ phase: KeyboardDictationMailbox.Reply.Phase, to requestID: UUID, text: String? = nil) {
        try? mailbox?.respond(.init(id: requestID, phase: phase, text: text))
    }

    func onState(_ state: Dictation.State, toggled: Bool) {}

    func onResult(_ text: String) {
        DispatchQueue.main.async { [weak self] in self?.complete(text, phase: .result) }
    }

    func onError(_ message: String) {
        let display = message == "silence" ? "No speech detected." : mapSttError(message).userMessage
        DispatchQueue.main.async { [weak self] in self?.complete(display, phase: .error) }
    }

    private func complete(_ text: String, phase: KeyboardDictationMailbox.Reply.Phase) {
        guard let requestID else { return }
        respond(phase, to: requestID, text: text)
        self.requestID = nil
        self.phase = nil
    }
}

/// Discards mic data between requests. Stopping a request detaches its
/// callbacks and waits for any in-flight PCM delivery to finish.
private final class KeyboardDictationInput: DictationCapture {
    private let lock = NSLock()
    private var pcm: Dictation.PcmHandler?
    private var level: ((Float) -> Void)?

    var isConnected: Bool {
        lock.lock()
        defer { lock.unlock() }
        return pcm != nil
    }

    func connect(_ pcm: @escaping Dictation.PcmHandler, onLevel: @escaping (Float) -> Void) {
        lock.lock()
        defer { lock.unlock() }
        self.pcm = pcm
        level = onLevel
    }

    func receive(_ samples: [Int16], count: Int) {
        lock.lock()
        defer { lock.unlock() }
        guard let pcm else { return }
        pcm(samples, count)
        let peak = samples.prefix(count).reduce(0) { max($0, abs(Int($1))) }
        level?(Float(peak) / 32_768)
    }

    func stop() {
        lock.lock()
        defer { lock.unlock() }
        pcm = nil
        level = nil
    }
}


/// Keeps MyWhisper running in the background for the keyboard with the mic off, like
/// Wispr Flow: the keyboard asks for the mic only while its key is held, and the mic turns
/// off again shortly after. Stays ready until the user stops it or iOS interrupts it.
final class KeyboardMicLevelSession: ObservableObject {
    @Published private(set) var isEnabled = false
    @Published private(set) var error: String?
    let isAvailable = KeyboardDictationMailbox.shared != nil

    /// Renders silence: an active audio app keeps running in the background without the mic.
    private let keepAlive = AVAudioEngine()
    private var capture: AudioCapture?
    private var timer: Timer?
    private var interruptionObserver: NSObjectProtocol?
    private var sessionID = UUID()
    private var nextHeartbeat = Date.distantPast
    private var lastWanted = Date.distantPast
    private let peak = Peak()

    func start() {
        guard !isEnabled, let mailbox = KeyboardDictationMailbox.shared else { return }
        error = nil
        do {
            // The capture's category, so turning the mic on later doesn't reconfigure the session.
            try AVAudioSession.sharedInstance().setCategory(
                .playAndRecord, mode: .measurement, options: [.mixWithOthers, .defaultToSpeaker]
            )
            try AVAudioSession.sharedInstance().setActive(true)
            _ = keepAlive.mainMixerNode
            keepAlive.prepare()
            try keepAlive.start()
        } catch {
            self.error = "Couldn’t keep MyWhisper ready for the keyboard."
            return
        }
        mailbox.clearMeter()
        mailbox.clearListen()
        sessionID = UUID()
        nextHeartbeat = .distantPast
        lastWanted = .distantPast
        isEnabled = true
        interruptionObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification, object: nil, queue: .main
        ) { [weak self] note in
            guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  AVAudioSession.InterruptionType(rawValue: raw) == .began else { return }
            self?.stop()
        }
        let timer = Timer(timeInterval: 0.08, repeats: true) { [weak self] _ in self?.tick(mailbox) }
        self.timer = timer
        RunLoop.main.add(timer, forMode: .common)
    }

    func stop() {
        guard isEnabled else { return }
        timer?.invalidate()
        timer = nil
        if let interruptionObserver { NotificationCenter.default.removeObserver(interruptionObserver) }
        interruptionObserver = nil
        capture?.stop()
        capture = nil
        keepAlive.stop()
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
        if let mailbox = KeyboardDictationMailbox.shared {
            mailbox.clearStandby()
            mailbox.clearMeter()
            mailbox.clearListen()
        }
        isEnabled = false
    }

    private func tick(_ mailbox: KeyboardDictationMailbox) {
        let now = Date()
        guard keepAlive.isRunning else {
            stop()
            return
        }
        if now >= nextHeartbeat {
            try? mailbox.publishStandby(.init(id: sessionID, heartbeat: now))
            nextHeartbeat = now.addingTimeInterval(1)
        }
        if mailbox.listen?.isCurrent(at: now) == true { lastWanted = now }
        // A short hangover, so pressing again right away doesn't restart the mic.
        let wanted = now.timeIntervalSince(lastWanted) < 1.5
        if wanted, capture == nil {
            guard startCapture() else {
                // Likely iOS refusing to start recording from the background. Stop, so the next
                // keyboard press opens MyWhisper instead of waiting on a mic that never comes.
                stop()
                error = "iOS didn’t let MyWhisper turn the microphone on in the background."
                return
            }
        } else if !wanted, let capture {
            capture.stop()
            self.capture = nil
            mailbox.clearMeter()
        }
        guard let capture, capture.running else { return }
        // A missed write (busy disk) skips one reading; it doesn't end the session.
        try? mailbox.publishMeter(.init(level: peak.get(), measured: now))
    }

    private func startCapture() -> Bool {
        peak.set(0)
        let capture = AudioCapture()
        capture.keepsSessionActive = true
        let normalizer = LevelNormalizer()
        capture.onPcm = { [peak] samples, count in
            peak.set(normalizer.push(samples: samples.prefix(count)))
        }
        capture.onInterruption = { [weak self] in
            DispatchQueue.main.async { self?.stop() }
        }
        guard capture.start(prepareUtterance: false) else { return false }
        self.capture = capture
        return true
    }

    private final class Peak {
        private let lock = NSLock()
        private var value: Float = 0

        func set(_ level: Float) {
            lock.lock()
            defer { lock.unlock() }
            value = level
        }

        func get() -> Float {
            lock.lock()
            defer { lock.unlock() }
            return value
        }
    }
}

/// Port of Android `LevelNormalizer`: RMS in dB against a learned noise floor
/// and a relaxing ceiling, so speech fills the bars and room noise reads 0.
/// Constants are per 32 ms block, as on Android; `push(samples:)` splits
/// longer tap buffers into 32 ms blocks and reports the loudest one.
final class LevelNormalizer {
    static let blockSize = 512
    private static let floorRiseDb = 0.05
    private static let ceilingFallDb = 0.2
    private static let minFloorDb = -70.0
    private static let gateDb = 6.0
    private static let minSpanDb = 24.0

    // Seeded from the first real block: a noisy room is learned at once.
    private var floorDb = Double.nan
    private var ceilingDb = Double.nan

    func push(samples: ArraySlice<Int16>) -> Float {
        var level: Float = 0
        var start = samples.startIndex
        while start < samples.endIndex {
            let end = min(start + Self.blockSize, samples.endIndex)
            let block = samples[start..<end]
            let energy = block.reduce(0.0) { $0 + Double($1) * Double($1) }
            level = max(level, push(rms: (energy / Double(block.count)).squareRoot() / 32_768))
            start = end
        }
        return level
    }

    func push(rms: Double) -> Float {
        // Digital-zero warm-up blocks say nothing about room noise.
        guard rms > 0 else { return 0 }
        let db = 20 * log10(max(rms, 1e-6))
        if floorDb.isNaN {
            floorDb = db
            ceilingDb = db + Self.minSpanDb
        }
        floorDb = min(max(db < floorDb ? db : floorDb + Self.floorRiseDb, Self.minFloorDb), -20)
        ceilingDb = max(db, ceilingDb - Self.ceilingFallDb, floorDb + Self.minSpanDb)
        let gate = floorDb + Self.gateDb
        return Float(min(max((db - gate) / (ceilingDb - gate), 0), 1))
    }
}
