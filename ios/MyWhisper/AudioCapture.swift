import AVFoundation
import Foundation

/// 16 kHz mono PCM16 capture feeding the core (resamples/VADs to its
/// 16 kHz engine format). One-shot: `start()` builds the tap + read
/// path, `stop()` tears it down.
///
/// Mirrors Android `AudioCapture.kt` (`sampleRate`/`onPcm`/`onError`/
/// `onLevel`): the tap delivers Int16 frames at 16 kHz, `onLevel`
/// reports a 0..1 peak per buffer for the pill visualizer, and every
/// handler runs on the capture thread — never the main thread.
///
/// Interruption (phone call, Siri, route steal): the engine stops and
/// the partial utterance is kept — the core keeps everything pushed
/// so far and `onInterruption` lets the owner commit it via
/// `Dictation.handleInterruption()`. Recording never auto-resumes;
/// the user starts the next press.
final class AudioCapture {
    /// Engine tap format: 16 kHz mono PCM16, 2048-frame buffers.
    static let sampleRate: Double = 16_000
    static let channelCount: AVAudioChannelCount = 1
    static let tapBufferSize: AVAudioFrameCount = 2_048

    /// PCM frames (16 kHz i16) + valid sample count. Buffer reused by
    /// the tap: copy before retaining.
    var onPcm: (([Int16], Int) -> Void)?
    var wantsSamples: (() -> Bool)?
    var onError: ((String) -> Void)?
    /// 0..1 peak per tap buffer, for the level visualizer.
    var onLevel: ((Float) -> Void)?
    /// Fired on audio-session interruption (call/Siri). Engine already
    /// stopped, partial audio kept in the core.
    var onInterruption: (() -> Void)?
    /// Leave the audio session active on stop, for an owner that keeps it running (keyboard standby).
    var keepsSessionActive = false

    private let engine = AVAudioEngine()
    private let mutex = NSLock()
    private var isRunning = false
    private var interruptionObserver: NSObjectProtocol?

    var running: Bool {
        mutex.lock()
        defer { mutex.unlock() }
        return isRunning
    }

    /// Negotiated rate. Fixed 16 kHz: the tap format requests it and
    /// AVAudioEngine converts from hardware.
    var actualRate: Double { Self.sampleRate }

    /// Start capture and open the core utterance. Returns false (and
    /// reports via `onError`) when permission is missing, the tap
    /// format is unavailable, the engine won't start, or the core
    /// rejects the utterance.
    @discardableResult
    func start(prepareUtterance: Bool = true) -> Bool {
        mutex.lock()
        if isRunning {
            mutex.unlock()
            return true
        }
        mutex.unlock()
        switch AVAudioSession.sharedInstance().recordPermission {
        case .granted:
            break
        case .denied:
            onError?("mic permission denied")
            return false
        case .undetermined:
            AVAudioSession.sharedInstance().requestRecordPermission { _ in }
            onError?("mic permission undetermined")
            return false
        @unknown default:
            onError?("mic permission unavailable")
            return false
        }
        do {
            let session = AVAudioSession.sharedInstance()
            let priority = MicRoute.priority(from: try? CoreBridge.getSettings())
            // HFP only when Bluetooth can win: enabling it alone may switch earbuds to call mode.
            let bluetooth = MicRoute.pick(priority: priority, items: [MicRoute.bluetooth, MicRoute.builtIn]) { $0 }
                == MicRoute.bluetooth
            var options: AVAudioSession.CategoryOptions = bluetooth ? [.allowBluetooth] : []
            if !prepareUtterance { options.formUnion([.mixWithOthers, .defaultToSpeaker]) }
            try session.setCategory(prepareUtterance ? .record : .playAndRecord, mode: .measurement, options: options)
            // The keyboard mic stays on in the background; without this iOS mutes haptics while it records.
            try? session.setAllowHapticsAndSystemSoundsDuringRecording(true)
            try session.setActive(true)
            if let port = MicRoute.pick(priority: priority, items: session.availableInputs ?? [], kind: {
                MicRoute.kind(of: $0.portType)
            }) {
                try session.setPreferredInput(port)
            }
        } catch {
            onError?("mic start: \(error.localizedDescription)")
            return false
        }
        let input = engine.inputNode
        let hardware = input.outputFormat(forBus: 0)
        guard hardware.sampleRate > 0, hardware.channelCount > 0,
              let format = AVAudioFormat(
                commonFormat: .pcmFormatInt16, sampleRate: Self.sampleRate,
                channels: Self.channelCount, interleaved: true
              ),
              let converter = AVAudioConverter(from: hardware, to: format) else {
            if !keepsSessionActive {
                try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
            }
            onError?("mic unavailable on this device")
            return false
        }
        var wasDelivering = false
        input.installTap(onBus: 0, bufferSize: Self.tapBufferSize, format: nil) { [weak self] buffer, _ in
            guard let self else { return }
            guard self.wantsSamples?() != false else {
                if wasDelivering { converter.reset() }
                wasDelivering = false
                return
            }
            wasDelivering = true
            let capacity = AVAudioFrameCount(ceil(Double(buffer.frameLength) * format.sampleRate / hardware.sampleRate)) + 64
            guard let converted = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: capacity) else { return }
            var consumed = false
            let status = converter.convert(to: converted, error: nil) { _, status in
                status.pointee = consumed ? .noDataNow : .haveData
                guard !consumed else { return nil }
                consumed = true
                return buffer
            }
            if status != .error { self.handleTap(buffer: converted) }
        }
        observeInterruptions()
        do {
            if prepareUtterance {
                try CoreBridge.startUtterance(sampleRate: UInt32(Self.sampleRate))
            }
            engine.prepare()
            try engine.start()
        } catch {
            teardownEngine()
            removeInterruptionObserver()
            if !keepsSessionActive {
                try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
            }
            if prepareUtterance { try? CoreBridge.cancelUtterance() }
            onError?("mic start: \(error.localizedDescription)")
            return false
        }
        mutex.lock()
        isRunning = true
        mutex.unlock()
        return true
    }

    /// Stop capture. Idempotent; safe from any thread.
    func stop() {
        mutex.lock()
        let was = isRunning
        isRunning = false
        mutex.unlock()
        if was {
            teardownEngine()
            removeInterruptionObserver()
            if !keepsSessionActive {
                try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
            }
        }
    }

    deinit {
        teardownEngine()
        removeInterruptionObserver()
    }

    // MARK: - Tap

    private func handleTap(buffer: AVAudioPCMBuffer) {
        guard let channels = buffer.int16ChannelData else { return }
        let frames = Int(buffer.frameLength)
        guard frames > 0 else { return }
        var samples = [Int16](repeating: 0, count: frames)
        samples.withUnsafeMutableBufferPointer { dest in
            guard let base = dest.baseAddress else { return }
            memcpy(base, channels[0], frames * MemoryLayout<Int16>.size)
        }
        onPcm?(samples, frames)
        guard let onLevel else { return }
        var peak = 0
        for i in 0..<frames {
            let sample = Int(samples[i])
            let magnitude = sample < 0 ? -sample : sample
            if magnitude > peak { peak = magnitude }
        }
        onLevel(min(max(Float(peak) / 32_768, 0), 1))
    }

    // MARK: - Interruptions

    private func observeInterruptions() {
        removeInterruptionObserver()
        interruptionObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification,
            object: nil,
            queue: nil
        ) { [weak self] note in
            guard let raw = note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt,
                  AVAudioSession.InterruptionType(rawValue: raw) == .began else { return }
            self?.handleInterruptionBegan()
        }
    }

    private func removeInterruptionObserver() {
        if let observer = interruptionObserver {
            NotificationCenter.default.removeObserver(observer)
            interruptionObserver = nil
        }
    }

    /// Graceful stop on interruption: halt the engine, keep the
    /// partial utterance in the core, hand off to the owner. State
    /// stays the owner's to change (Dictation commits the partial).
    private func handleInterruptionBegan() {
        mutex.lock()
        isRunning = false
        mutex.unlock()
        teardownEngine()
        removeInterruptionObserver()
        onInterruption?()
    }

    private func teardownEngine() {
        engine.stop()
        engine.inputNode.removeTap(onBus: 0)
    }
}

// MARK: - Dictation transport

extension AudioCapture: DictationCapture {}

/// Ranked input kinds (`preferences.mic_priority`, core `MicKind`).
/// Mirrors Android `MicRoute`.
enum MicRoute {
    static let wired = "Wired"
    static let builtIn = "BuiltIn"
    static let bluetooth = "Bluetooth"
    static let defaults = [wired, builtIn, bluetooth]

    static func kind(of port: AVAudioSession.Port) -> String? {
        switch port {
        case .builtInMic: return builtIn
        case .headsetMic, .usbAudio, .lineIn: return wired
        case .bluetoothHFP, .bluetoothLE, .carAudio: return bluetooth
        default: return nil
        }
    }

    /// Saved order with unknown kinds dropped and missing ones appended.
    static func priority(from settings: [String: Any]?) -> [String] {
        let prefs = settings?["preferences"] as? [String: Any]
        let saved = (prefs?["mic_priority"] as? [String] ?? []).filter { defaults.contains($0) }
        var seen = Set<String>()
        return (saved + defaults).filter { seen.insert($0).inserted }
    }

    /// First item whose kind ranks highest; nil when none is ranked.
    static func pick<T>(priority: [String], items: [T], kind: (T) -> String?) -> T? {
        for wanted in priority {
            if let hit = items.first(where: { kind($0) == wanted }) { return hit }
        }
        return nil
    }

    /// Pure `preferences.mic_priority` write transform.
    static func applying(_ order: [String], to settings: [String: Any]) -> [String: Any] {
        var settings = settings
        var prefs = settings["preferences"] as? [String: Any] ?? [:]
        prefs["mic_priority"] = order
        settings["preferences"] = prefs
        return settings
    }
}
