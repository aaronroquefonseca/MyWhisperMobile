import XCTest
@testable import MyWhisper

/// STT pipeline tests: cancel-supersede, tail capture, error-mapping
/// table (Android-exact surface filter), Local-skip/Gemini copy,
/// CoreError/NSError branches, render mapping, es twins, envelope error
/// parsing, delivery guards.
///
/// Pure unit: a fake `TranscriptionEngine` stands in for the core (no FFI,
/// no network — no stub server; `URLSession` loopback is unnecessary since
/// the seam is the engine protocol, not HTTP). `CoreBridge.dataOf` cases
/// exercise envelope parsing only and never invoke FFI.
final class SttPipelineTests: XCTestCase {
    // MARK: - Fake engine (no FFI, gate-controlled blocking finish)

    final class FakeEngine: TranscriptionEngine {
        var starts = 0
        var pushes = 0
        var cancels = 0
        var finishCalls = 0
        var text = "hello world"
        var engineName = "test-engine"
        var finishError: String?
        /// Arbitrary throw for the next finishes (CoreError cases, NSError).
        var finishThrowable: Error?
        /// Blocks the first `blockFirstFinishes` finish calls on `gate`.
        var blockFirstFinishes = 0
        let gate = DispatchSemaphore(value: 0)
        let enteredFinish = DispatchSemaphore(value: 0)

        func startUtterance(sampleRate: UInt32) throws {
            starts += 1
        }

        func pushPcm(_ samples: [Int16]) throws {
            pushes += 1
        }

        func finishUtterance() throws -> (text: String, engine: String) {
            finishCalls += 1
            enteredFinish.signal()
            if finishCalls <= blockFirstFinishes {
                gate.wait()
            }
            if let throwable = finishThrowable {
                throw throwable
            }
            if let message = finishError {
                throw CoreError.coreError(message)
            }
            return (text, engineName)
        }

        func cancelUtterance() throws {
            cancels += 1
        }
    }

    final class Listener: TranscriptionListener {
        var states: [TranscriptionState] = []
        var results: [(text: String, engine: String)] = []
        var failures: [SttFailure] = []
        let lock = NSLock()

        func transcriptionStateChanged(_ state: TranscriptionState) {
            lock.lock(); defer { lock.unlock() }
            states.append(state)
        }

        func transcriptionDidFinish(text: String, engine: String) {
            lock.lock(); defer { lock.unlock() }
            results.append((text, engine))
        }

        func transcriptionDidFail(_ failure: SttFailure) {
            lock.lock(); defer { lock.unlock() }
            failures.append(failure)
        }
    }

    private var queue = DispatchQueue(label: "test.transcribe")
    private var engine = FakeEngine()
    private var listener = Listener()
    private var transcriber = Transcriber(engine: FakeEngine())

    override func setUp() {
        queue = DispatchQueue(label: "test.transcribe")
        engine = FakeEngine()
        listener = Listener()
        transcriber = Transcriber(engine: engine, workers: queue, listener: listener)
    }

    /// Drain the serial worker, then flush main-queue listener callbacks.
    private func settle() {
        queue.sync {}
        RunLoop.main.run(until: Date(timeIntervalSinceNow: 0.5))
    }

    // MARK: - Cancel-supersede (Android DictationCancelTest parity)

    func testCancelDuringBlockedTranscribeDropsResult() {
        engine.blockFirstFinishes = 1
        transcriber.startRecording()
        XCTAssertEqual(transcriber.currentState, .recording)
        transcriber.pushPcmChunk([1000, 2000])
        transcriber.stopAndTranscribe()
        XCTAssertTrue(engine.enteredFinish.wait(timeout: .now() + 10) == .success)

        transcriber.cancel()
        XCTAssertEqual(transcriber.currentState, .idle)
        engine.gate.signal()
        settle()

        XCTAssertTrue(listener.results.isEmpty, "stale transcribe must not commit")
        XCTAssertTrue(listener.failures.isEmpty, "stale transcribe must not error either")
        XCTAssertEqual(transcriber.currentState, .idle)
        XCTAssertEqual(engine.cancels, 1)
    }

    func testStaleWorkerDoesNotClobberNewRecording() {
        engine.blockFirstFinishes = 1
        transcriber.startRecording()
        transcriber.stopAndTranscribe()
        XCTAssertTrue(engine.enteredFinish.wait(timeout: .now() + 10) == .success)

        transcriber.cancel()
        transcriber.startRecording()
        XCTAssertEqual(transcriber.currentState, .recording)

        engine.gate.signal()
        settle()

        XCTAssertTrue(listener.results.isEmpty, "stale worker must not commit")
        XCTAssertEqual(transcriber.currentState, .recording, "stale worker must not clobber new RECORDING")
    }

    func testNewSessionAfterCancelCommitsWhileStaleDrops() {
        engine.blockFirstFinishes = 1
        transcriber.startRecording()
        transcriber.stopAndTranscribe()
        XCTAssertTrue(engine.enteredFinish.wait(timeout: .now() + 10) == .success)

        transcriber.cancel()
        transcriber.startRecording()
        transcriber.stopAndTranscribe()
        engine.gate.signal()
        settle()

        XCTAssertEqual(listener.results.count, 1, "stale drops, fresh commits exactly once")
        XCTAssertEqual(listener.results.first?.text, "hello world")
        XCTAssertEqual(transcriber.currentState, .idle)
    }

    func testFinishWithoutCancelCommitsResult() {
        transcriber.startRecording()
        transcriber.pushPcmChunk([1000])
        transcriber.stopAndTranscribe()
        settle()

        XCTAssertEqual(listener.results.count, 1)
        XCTAssertEqual(listener.results.first?.engine, "test-engine")
        XCTAssertTrue(listener.failures.isEmpty)
        XCTAssertEqual(transcriber.currentState, .idle)
    }

    func testBlankTranscriptionRoutesToSilenceEmptyState() {
        engine.text = "   "
        transcriber.startRecording()
        transcriber.stopAndTranscribe()
        settle()

        XCTAssertTrue(listener.results.isEmpty, "blank must never commit")
        XCTAssertEqual(listener.failures.count, 1)
        // The pipeline still reports routineDrop; ResultView maps it to the
        // non-red empty state at render time (displayContent test below).
        guard let failure = listener.failures.first else {
            return XCTFail("expected one routine-drop failure")
        }
        XCTAssertEqual(failure.category, .routineDrop)
        XCTAssertEqual(failure.userMessage, silenceEmptyCopy)
        XCTAssertFalse(shouldSurfaceSttError(failure))
    }

    func testTailAudioStillStreamsAfterStop() {
        transcriber = Transcriber(
            engine: engine, workers: queue, releaseDelayMs: { 300 }, listener: listener
        )
        transcriber.startRecording()
        transcriber.stopAndTranscribe()
        // Worker is tail-sleeping (state .working) — the gate stays open.
        transcriber.pushPcmChunk([1000, 2000])
        XCTAssertEqual(engine.pushes, 1, "tail audio must reach the core")
        settle()
        // Transcribe handed off: the gate is shut, late audio drops.
        transcriber.pushPcmChunk([3000])
        XCTAssertEqual(engine.pushes, 1, "post-handoff audio must not reach the core")
        XCTAssertEqual(listener.results.count, 1)
    }

    // MARK: - Error mapping table (Android error categories)

    func testErrorMappingTable() {
        let cases: [(raw: String, category: SttFailureCategory, surfaced: Bool)] = [
            ("too short (under 0.3s of audio)", .routineDrop, false),
            ("silence (VAD dropped everything)", .routineDrop, false),
            ("silence, nothing captured", .routineDrop, false),
            ("empty transcript", .routineDrop, false),
            // Provider-switch witness messages surface with raw text on
            // Android (only silence/too-short filter) — same here.
            ("cancelled (chain superseded mid-utterance; fallback uploads aborted)", .server, true),
            ("superseded (provider switched mid-utterance)", .server, true),
            ("Stub: request: connection refused", .network, true),
            ("ws connect: handshake failed", .network, true),
            ("transcription: request timeout", .network, true),
            ("network unreachable", .network, true),
            ("server 401: bad key", .auth, true),
            ("server 403: forbidden", .auth, true),
            ("unauthorized: invalid api key", .auth, true),
            ("server 500: boom", .server, true),
            ("no text in reply: {}", .server, true),
            (
                "no server STT configured: add a Self-hosted or Cloud entry (Local/on-device is not supported yet)",
                .localSkipped,
                true
            ),
            ("Entry: on-device inference not supported yet (mobile v1 is server-only)", .localSkipped, true),
            ("Gemini cloud STT not implemented on mobile yet", .geminiUnsupported, true),
        ]
        for item in cases {
            let failure = mapSttError(item.raw)
            XCTAssertEqual(failure.category, item.category, "raw: \(item.raw)")
            XCTAssertEqual(shouldSurfaceSttError(failure), item.surfaced, "raw: \(item.raw)")
            XCTAssertEqual(failure.raw, item.raw)
        }
    }

    func testMessageFilterParityWithAndroid() {
        // Exact mirror of Android shouldSurfaceError: only the three
        // routine-drop tags stay silent — cancel/supersede surface.
        XCTAssertTrue(shouldSurfaceSttMessage("mic unavailable"))
        XCTAssertTrue(shouldSurfaceSttMessage(""))
        XCTAssertFalse(shouldSurfaceSttMessage("utterance too short"))
        XCTAssertFalse(shouldSurfaceSttMessage("silence, nothing captured"))
        XCTAssertFalse(shouldSurfaceSttMessage("empty transcript"))
        XCTAssertTrue(shouldSurfaceSttMessage("cancelled (chain superseded mid-utterance)"))
        XCTAssertTrue(shouldSurfaceSttMessage("superseded (provider switched mid-utterance)"))
    }

    // MARK: - CoreError / NSError branches (sttFailure(for:))

    func testNullReplyMapsToSurfacedServerError() {
        engine.finishThrowable = CoreError.nullReply
        transcriber.startRecording()
        transcriber.stopAndTranscribe()
        settle()

        XCTAssertTrue(listener.results.isEmpty)
        guard let failure = listener.failures.first else {
            return XCTFail("expected one failure")
        }
        XCTAssertEqual(failure.category, .server)
        XCTAssertTrue(shouldSurfaceSttError(failure))
    }

    func testBadReplySurfacesRawText() {
        engine.finishThrowable = CoreError.badReply("weird envelope")
        transcriber.startRecording()
        transcriber.stopAndTranscribe()
        settle()

        guard let failure = listener.failures.first else {
            return XCTFail("expected one failure")
        }
        XCTAssertEqual(failure.category, .server)
        XCTAssertEqual(failure.userMessage, "weird envelope")
        XCTAssertTrue(shouldSurfaceSttError(failure))
    }

    func testNonCoreErrorUsesLocalizedDescription() {
        engine.finishThrowable = NSError(
            domain: "test", code: -1009,
            userInfo: [NSLocalizedDescriptionKey: "request: timed out"]
        )
        transcriber.startRecording()
        transcriber.stopAndTranscribe()
        settle()

        guard let failure = listener.failures.first else {
            return XCTFail("expected one failure")
        }
        XCTAssertEqual(failure.category, .network)
        XCTAssertTrue(shouldSurfaceSttError(failure))
    }

    // MARK: - Spanish twins (Android values-es parity)

    func testSttStringsSpanishTwins() {
        XCTAssertEqual(silenceEmptyCopyEs, "No se capturó voz — inténtalo de nuevo.")
        XCTAssertEqual(SttStrings.current(languageCode: "es"), .spanish)
        XCTAssertEqual(SttStrings.current(languageCode: "en"), .english)
        XCTAssertEqual(SttStrings.current(languageCode: nil), .english)
        XCTAssertEqual(SttStrings.current(languageCode: "fr"), .english)
        XCTAssertNotEqual(SttStrings.spanish.emptyHint, SttStrings.english.emptyHint)
        XCTAssertNotEqual(SttStrings.spanish.copy, SttStrings.english.copy)
        XCTAssertNotEqual(SttStrings.spanish.localSkipped, SttStrings.english.localSkipped)
        XCTAssertNotEqual(SttStrings.spanish.geminiUnsupported, SttStrings.english.geminiUnsupported)
    }

    // MARK: - Local-skip + Gemini copy (Android strings)

    func testLocalSkipCopyMatchesAndroidChainSub() {
        // Skip clause of Android stt_chain_sub (PR #40 Local-enablement).
        XCTAssertEqual(
            localSkippedCopy,
            "Local on-device speech is experimental and off by default — "
                + "without its verified model pack the entry is skipped and the server chain is used."
        )
        XCTAssertEqual(
            localSkippedCopyEs,
            "La voz local es experimental y está apagada por defecto — "
                + "sin su pack verificado la entrada se omite y se usa la cadena del servidor."
        )
        let failure = mapSttError("X: on-device inference not supported yet")
        XCTAssertEqual(failure.category, .localSkipped)
        XCTAssertEqual(failure.userMessage, localSkippedCopy)
        let spanish = mapSttError("X: on-device inference not supported yet", languageCode: "es")
        XCTAssertEqual(spanish.category, .localSkipped)
        XCTAssertEqual(spanish.userMessage, localSkippedCopyEs)
        XCTAssertEqual(spanish.raw, failure.raw, "locale changes copy only, never category or raw")
    }

    func testGeminiCopyMatchesAndroid() {
        XCTAssertEqual(geminiUnsupportedCopy, "Gemini speech is not implemented in this build yet.")
        XCTAssertEqual(geminiUnsupportedCopyEs, "La voz de Gemini aún no está disponible en esta versión.")
        let failure = mapSttError("Gemini cloud STT not implemented on mobile yet")
        XCTAssertEqual(failure.category, .geminiUnsupported)
        XCTAssertEqual(failure.userMessage, geminiUnsupportedCopy)
        let spanish = mapSttError("Gemini cloud STT not implemented on mobile yet", languageCode: "es")
        XCTAssertEqual(spanish.userMessage, geminiUnsupportedCopyEs)
    }

    func testRoutineDropLocaleRoutesEmptyHint() {
        let spanish = mapSttError("silence (VAD dropped everything)", languageCode: "es")
        XCTAssertEqual(spanish.category, .routineDrop)
        XCTAssertEqual(spanish.userMessage, silenceEmptyCopyEs)
    }

    // MARK: - Envelope error parsing (no FFI invocation)

    func testEnvelopeErrorParsing() throws {
        let data = try CoreBridge.dataOf(#"{"ok":true,"data":{"text":"hi","engine":"stub"}}"#)
        let obj = try XCTUnwrap(data as? [String: Any])
        XCTAssertEqual(obj["text"] as? String, "hi")

        XCTAssertNil(try CoreBridge.dataOf(#"{"ok":true,"data":null}"#))

        XCTAssertThrowsError(try CoreBridge.dataOf(#"{"ok":false,"error":"server 500: boom"}"#)) { error in
            XCTAssertEqual(error as? CoreError, CoreError.coreError("server 500: boom"))
        }
        XCTAssertThrowsError(try CoreBridge.dataOf("not json"))
    }

    // MARK: - Delivery items (pure, no pasteboard in unit tests)

    func testShareItemsRejectBlank() {
        XCTAssertTrue(Delivery.shareItems(for: "   ").isEmpty)
        XCTAssertEqual(Delivery.shareItems(for: " hello ").count, 1)
    }

    func testBlankCopyGuardRejectsBeforePasteboard() {
        // Early false return — never touches UIPasteboard, safe headless.
        XCTAssertFalse(Delivery.copyToClipboard("   "))
    }
}
