import XCTest
@testable import MyWhisper

/// Port of Android `DictationCancelTest` plus tap/hold/smart timing
/// and release-tail behavior. Pure XCTest: capture, transcription,
/// clock, and log are injected fakes — no AVAudioEngine, no core.
///
/// The fake transcribe gate is a polling door (latch semantics: once
/// opened, every queued worker passes), mirroring the Kotlin
/// `CountDownLatch` harness. `settle()` is a `queue.sync {}` barrier:
/// the worker queue is serial FIFO, so it returns after the queued
/// finish block runs. Always `openDoor()` before `settle()` when the
/// door is closed, or the barrier deadlocks by design.
final class DictationTests: XCTestCase {
    final class FakeCapture: DictationCapture {
        var stops = 0
        func stop() { stops += 1 }
    }

    final class Box<T> {
        var value: T
        init(_ value: T) { self.value = value }
    }

    final class Clock {
        var now: Int64 = 1_000_000
    }

    final class Harness: Dictation.Listener {
        let queue = DispatchQueue(label: "test-dictation-\(UUID().uuidString)")
        let clock = Clock()
        let entered = DispatchSemaphore(value: 0)
        let door = Box(false)
        let text = Box("hello world")
        let tailMs: Int64
        let modeName: String
        let smartTap: Int64
        let pcmHandler = Box<(([Int16], Int) -> Void)?>(nil)
        let levelHandler = Box<((Float) -> Void)?>(nil)
        let pushedSamples = Box(0)

        private let lock = NSLock()
        private var impl: Dictation?
        private var statesValue: [Dictation.State] = []
        private var togglesValue: [Bool] = []
        private var resultsValue: [String] = []
        private var errorsValue: [String] = []
        private var forwardedLevelsValue: [Float] = []

        init(tailMs: Int64 = 0, modeName: String = "Push", smartTap: Int64 = 150, transcribeText: String = "hello world") {
            self.tailMs = tailMs
            self.modeName = modeName
            self.smartTap = smartTap
            self.text.value = transcribeText
            build()
        }

        private func build() {
            let clock = clock
            let entered = entered
            let door = door
            let text = text
            let pcmHandler = pcmHandler
            let levelHandler = levelHandler
            let pushedSamples = pushedSamples
            let tail = tailMs
            let mode = modeName
            let tap = smartTap
            impl = Dictation(
                smartTapMs: { tap },
                mode: { mode },
                releaseDelayMs: { tail },
                nowMs: { clock.now },
                startCapture: { onPcm, _, onLevel in
                    pcmHandler.value = onPcm
                    levelHandler.value = onLevel
                    return FakeCapture()
                },
                finishTranscription: {
                    entered.signal()
                    let deadline = Date().addingTimeInterval(10)
                    while door.value == false && Date() < deadline {
                        Thread.sleep(forTimeInterval: 0.01)
                    }
                    return (text.value, "test-engine")
                },
                cancelTranscription: {},
                pushPcm: { samples in pushedSamples.value += samples.count },
                log: { _ in },
                workers: queue
            )
            impl?.listener = self
        }

        // MARK: - Driver

        func pressDown() { impl?.pressDown() }
        func pressUp() { impl?.pressUp() }
        func cancel() { impl?.cancel() }
        func shutdown() { impl?.shutdown() }
        func openDoor() { door.value = true }
        /// Barrier: serial FIFO queue runs everything queued before this.
        func settle() { queue.sync {} }

        func sendPcm(_ samples: [Int16]) { pcmHandler.value?(samples, samples.count) }
        func sendLevel(_ peak: Float) {
            levelHandler.value?(peak)
            lock.lock()
            forwardedLevelsValue.append(peak)
            lock.unlock()
        }

        var current: Dictation.State { impl?.current ?? .idle }
        var peak: Float { impl?.maxPeak ?? -1 }

        // MARK: - Dictation.Listener

        func onState(_ state: Dictation.State, toggled: Bool) {
            lock.lock()
            statesValue.append(state)
            togglesValue.append(toggled)
            lock.unlock()
        }

        func onResult(_ text: String) {
            lock.lock()
            resultsValue.append(text)
            lock.unlock()
        }

        func onError(_ message: String) {
            lock.lock()
            errorsValue.append(message)
            lock.unlock()
        }

        // MARK: - Observations

        var results: [String] {
            lock.lock()
            defer { lock.unlock() }
            return resultsValue
        }

        var errors: [String] {
            lock.lock()
            defer { lock.unlock() }
            return errorsValue
        }

        var states: [Dictation.State] {
            lock.lock()
            defer { lock.unlock() }
            return statesValue
        }

        var toggles: [Bool] {
            lock.lock()
            defer { lock.unlock() }
            return togglesValue
        }

        var pushedCount: Int { pushedSamples.value }

        var forwardedLevels: [Float] {
            lock.lock()
            defer { lock.unlock() }
            return forwardedLevelsValue
        }

        func clearStates() {
            lock.lock()
            statesValue.removeAll()
            togglesValue.removeAll()
            lock.unlock()
        }

        func waitEntered() -> Bool {
            entered.wait(timeout: .now() + 10) == .success
        }
    }

    // MARK: - Cancel wins (DictationCancelTest port)

    func testCancelDuringBlockedTranscribe_dropsResult() {
        let harness = Harness()
        harness.pressDown()
        XCTAssertEqual(harness.current, .recording)
        harness.pressUp()
        XCTAssertEqual(harness.current, .working)
        XCTAssertTrue(harness.waitEntered())

        harness.cancel()
        XCTAssertEqual(harness.current, .idle)
        harness.openDoor()
        harness.settle()

        XCTAssertTrue(harness.results.isEmpty, "stale transcribe must not commit")
        XCTAssertTrue(harness.errors.isEmpty, "stale transcribe must not error either")
        XCTAssertEqual(harness.current, .idle)
    }

    func testCancelDuringTail_neverTranscribes() {
        let harness = Harness(tailMs: 500)
        harness.pressDown()
        harness.pressUp()
        XCTAssertEqual(harness.current, .working)

        harness.cancel()
        harness.openDoor()
        harness.settle()

        XCTAssertTrue(harness.results.isEmpty)
        XCTAssertEqual(harness.current, .idle)
    }

    func testStaleWorker_doesNotClobberNewRecording() {
        let harness = Harness()
        harness.pressDown()
        harness.pressUp()
        XCTAssertTrue(harness.waitEntered())

        harness.cancel()
        harness.pressDown()
        XCTAssertEqual(harness.current, .recording)

        harness.openDoor()
        harness.settle()

        XCTAssertTrue(harness.results.isEmpty, "stale worker must not commit")
        XCTAssertEqual(harness.current, .recording, "stale worker must not clobber new RECORDING")
        harness.cancel()
    }

    func testFinishWithoutCancel_commitsResult() {
        let harness = Harness()
        harness.pressDown()
        harness.sendPcm([Int16](repeating: 100, count: 160))
        harness.pressUp()
        harness.openDoor()
        harness.settle()

        XCTAssertEqual(harness.results, ["hello world"])
        XCTAssertEqual(harness.pushedCount, 160, "capture PCM must reach the core transport")
        XCTAssertEqual(harness.current, .idle)
    }

    func testShutdownDuringBlockedTranscribe_dropsResult() {
        let harness = Harness()
        harness.pressDown()
        harness.pressUp()
        XCTAssertEqual(harness.current, .working)
        XCTAssertTrue(harness.waitEntered())

        harness.shutdown()
        harness.openDoor()
        harness.settle()

        XCTAssertTrue(harness.results.isEmpty, "post-shutdown transcribe must not commit")
        XCTAssertTrue(harness.errors.isEmpty, "post-shutdown transcribe must not error either")
    }

    func testNewSessionAfterCancel_commitsWhileStaleDrops() {
        let harness = Harness()
        harness.pressDown()
        harness.pressUp()
        XCTAssertTrue(harness.waitEntered())

        harness.cancel()
        harness.pressDown()
        XCTAssertEqual(harness.current, .recording)
        harness.pressUp()
        XCTAssertEqual(harness.current, .working)

        harness.openDoor()
        harness.settle()

        XCTAssertEqual(harness.results, ["hello world"], "stale drops, fresh commits exactly once")
        XCTAssertEqual(harness.current, .idle)
    }

    func testBlankTranscription_neverCommits_routesToSilence() {
        let harness = Harness(transcribeText: "   ")
        harness.pressDown()
        harness.pressUp()
        harness.openDoor()
        harness.settle()

        XCTAssertTrue(harness.results.isEmpty, "blank must never commit")
        XCTAssertEqual(harness.errors, ["silence"], "silence routes to the empty state")
        XCTAssertEqual(harness.current, .idle)
    }

    func testCancelDuringWorking_neverShowsWorkingAfter() {
        let harness = Harness()
        harness.pressDown()
        harness.pressUp()
        XCTAssertTrue(harness.waitEntered())

        harness.clearStates()
        harness.cancel()
        harness.openDoor()
        harness.settle()

        XCTAssertTrue(harness.results.isEmpty, "cancelled session must not commit")
        XCTAssertTrue(harness.errors.isEmpty, "cancelled session must not report either")
        XCTAssertFalse(harness.states.contains(.working), "WORKING must never reappear after cancel")
        XCTAssertEqual(harness.current, .idle)
    }

    // MARK: - Tap / hold / smart timing (fake clock)

    func testTapMode_togglesThenSecondPressFinishes() {
        let harness = Harness(modeName: "Tap")
        harness.pressDown()
        XCTAssertEqual(harness.current, .recording)
        harness.pressUp()
        XCTAssertEqual(harness.current, .recording, "Tap release toggles, keeps recording")
        XCTAssertEqual(harness.toggles.last ?? false, true)

        harness.pressDown()
        XCTAssertEqual(harness.current, .recording, "second press arms the stop, never finishes on DOWN")
        harness.pressUp()
        XCTAssertEqual(harness.current, .working)
        harness.openDoor()
        harness.settle()

        XCTAssertEqual(harness.results, ["hello world"])
        XCTAssertEqual(harness.current, .idle)
    }

    func testPushMode_holdToTalk() {
        let harness = Harness(modeName: "Push")
        harness.pressDown()
        harness.clock.now += 2_000
        harness.pressUp()
        XCTAssertEqual(harness.current, .working, "Push release always finishes")
        harness.openDoor()
        harness.settle()

        XCTAssertEqual(harness.results, ["hello world"])
        XCTAssertEqual(harness.current, .idle)
    }

    func testSmartMode_shortPressToggles_longPressFinishes() {
        let harness = Harness(modeName: "Smart", smartTap: 150)
        harness.pressDown()
        harness.clock.now += 100
        harness.pressUp()
        XCTAssertEqual(harness.current, .recording, "sub-threshold release toggles")
        XCTAssertEqual(harness.toggles.last ?? false, true)

        harness.pressDown()
        harness.pressUp()
        XCTAssertEqual(harness.current, .working)
        harness.openDoor()
        harness.settle()
        XCTAssertEqual(harness.results, ["hello world"])

        harness.clock.now += 10_000
        harness.pressDown()
        harness.clock.now += 300
        harness.pressUp()
        XCTAssertEqual(harness.current, .working, "supra-threshold release talks, finishes on UP")
        harness.openDoor()
        harness.settle()

        XCTAssertEqual(harness.results, ["hello world", "hello world"])
        XCTAssertEqual(harness.current, .idle)
    }

    func testPressDownWhileWorking_drops() {
        let harness = Harness()
        harness.pressDown()
        harness.pressUp()
        XCTAssertTrue(harness.waitEntered())

        harness.pressDown()
        XCTAssertEqual(harness.current, .working, "bounded admission: at most one finish queued")

        harness.openDoor()
        harness.settle()
        XCTAssertEqual(harness.results, ["hello world"])
        XCTAssertEqual(harness.current, .idle)
    }

    // MARK: - Release tail

    func testReleaseTail_delaysTranscribe_cancelWins() {
        let harness = Harness(tailMs: 600)
        harness.pressDown()
        harness.pressUp()
        XCTAssertEqual(harness.current, .working)

        XCTAssertTrue(
            harness.entered.wait(timeout: .now() + 0.15) == .timedOut,
            "tail must hold the worker past release"
        )
        harness.cancel()
        harness.openDoor()
        harness.settle()

        XCTAssertTrue(harness.results.isEmpty, "cancel during the tail must drop without transcribing")
        XCTAssertEqual(harness.current, .idle)
    }

    func testReleaseTail_elapsedCommits() {
        let harness = Harness(tailMs: 200)
        harness.pressDown()
        harness.pressUp()
        XCTAssertTrue(harness.waitEntered(), "worker must run after the tail elapses")
        harness.openDoor()
        harness.settle()

        XCTAssertEqual(harness.results, ["hello world"])
        XCTAssertEqual(harness.current, .idle)
    }

    // MARK: - Level tracking

    func testLevelTracking_updatesMaxPeak() {
        let harness = Harness()
        harness.pressDown()
        harness.sendLevel(0.25)
        harness.sendLevel(0.75)
        XCTAssertEqual(harness.peak, 0.75, accuracy: 0.001)
        XCTAssertEqual(harness.forwardedLevels, [0.25, 0.75])
        harness.pressUp()
        harness.openDoor()
        harness.settle()

        XCTAssertEqual(harness.results, ["hello world"])
    }
}

/// Port of Android `LevelNormalizerTest`.
final class LevelNormalizerTests: XCTestCase {
    private func rms(_ db: Double) -> Double { pow(10, db / 20) }

    @discardableResult
    private func feed(_ normalizer: LevelNormalizer, db: Double, blocks: Int) -> Float {
        var last: Float = 0
        for _ in 0..<blocks { last = normalizer.push(rms: rms(db)) }
        return last
    }

    func testSteadyRoomNoiseIsGatedToZero() {
        XCTAssertEqual(feed(LevelNormalizer(), db: -45, blocks: 200), 0)
    }

    func testNormalSpeechAfterSilenceFillsMostOfTheBar() {
        let normalizer = LevelNormalizer()
        feed(normalizer, db: -65, blocks: 30)
        XCTAssertGreaterThan(normalizer.push(rms: rms(-30)), 0.9)
        XCTAssertTrue((0.4...0.9).contains(normalizer.push(rms: rms(-40))))
    }

    func testQuietTalkerIsAutoGained() {
        let normalizer = LevelNormalizer()
        feed(normalizer, db: -70, blocks: 30)
        feed(normalizer, db: -42, blocks: 60)
        XCTAssertGreaterThan(normalizer.push(rms: rms(-42)), 0.9)
    }

    func testDigitalSilenceWarmupDoesNotPinFloor() {
        let normalizer = LevelNormalizer()
        for _ in 0..<10 { _ = normalizer.push(rms: 0) }
        XCTAssertEqual(feed(normalizer, db: -50, blocks: 120), 0)
    }

    func testNoisyRoomIsLearnedImmediately() {
        let normalizer = LevelNormalizer()
        XCTAssertEqual(feed(normalizer, db: -35, blocks: 3), 0)
        XCTAssertGreaterThan(normalizer.push(rms: rms(-12)), 0.9)
    }

    func testTapBuffersSplitIntoAndroidSizedBlocks() {
        let normalizer = LevelNormalizer()
        // A 2048-sample buffer whose last 32 ms block is loud: the loud block wins.
        let quiet = [Int16](repeating: 3, count: LevelNormalizer.blockSize * 3)
        let loud = (0..<LevelNormalizer.blockSize).map { $0 % 2 == 0 ? Int16(8_000) : Int16(-8_000) }
        XCTAssertGreaterThan(normalizer.push(samples: (quiet + loud)[...]), 0.9)
    }
}
