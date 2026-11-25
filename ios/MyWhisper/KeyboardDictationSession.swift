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


/// A user-started, five-minute microphone session for the keyboard preview.
final class KeyboardMicLevelSession: ObservableObject {
    @Published private(set) var isEnabled = false
    @Published private(set) var error: String?
    let isAvailable = KeyboardDictationMailbox.shared != nil

    private var monitor: AudioCapture?
    private var timer: Timer?
    private var end = Date.distantPast
    private let peak = Peak()

    func start() {
        guard !isEnabled, let mailbox = KeyboardDictationMailbox.shared else { return }
        error = nil
        peak.set(0)
        mailbox.clearMeter()
        let capture = AudioCapture()
        capture.onLevel = { [peak] in peak.set($0) }
        capture.onInterruption = { [weak self] in
            DispatchQueue.main.async { self?.stop() }
        }
        guard capture.start(prepareUtterance: false) else {
            error = "Couldn’t start the microphone preview."
            return
        }
        monitor = capture
        end = Date().addingTimeInterval(300)
        isEnabled = true
        let timer = Timer(timeInterval: 0.08, repeats: true) { [weak self] _ in
            guard let self else { return }
            let now = Date()
            guard now < self.end, self.monitor?.running == true else {
                self.stop()
                return
            }
            do {
                try mailbox.publishMeter(.init(level: self.peak.get(), measured: now))
            } catch {
                self.stop()
                self.error = "Couldn’t share microphone levels with the keyboard."
            }
        }
        self.timer = timer
        RunLoop.main.add(timer, forMode: .common)
    }

    func stop() {
        guard isEnabled else { return }
        timer?.invalidate()
        timer = nil
        monitor?.stop()
        monitor = nil
        KeyboardDictationMailbox.shared?.clearMeter()
        isEnabled = false
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
