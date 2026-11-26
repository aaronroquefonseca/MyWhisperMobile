import XCTest
@testable import MyWhisper

/// Settings screens logic (T6): STT chain + cleanup chain + dictionary
/// helpers behind `SttSettingsView` / `CleanupSettingsView` /
/// `DictionaryView`.
///
/// Pure Swift throughout — no FFI, no network — so these run anywhere
/// (including without the `libmywhisper_core.xcframework` built).
/// Bridge-backed round-trips live in `CleanupTests` /
/// `SttPipelineTests`, which need the static lib.
final class SettingsTests: XCTestCase {
    // MARK: - Helpers

    private func sttEntry(
        id: String, kind: SttEntryKind, enabled: Bool = true,
        provider: String = "", url: String = "", model: String = ""
    ) -> SttChainEntry {
        SttChainEntry(
            id: id, name: "\(kind.rawValue)-\(id)", kind: kind, enabled: enabled,
            provider: provider, url: url, model: model, apiKey: "", streaming: true
        )
    }

    private func cleanupEntry(
        id: String, kind: Cleanup.EntryKind, enabled: Bool = true,
        provider: String = "", model: String = "m"
    ) -> Cleanup.Entry {
        Cleanup.Entry(
            id: id, name: "\(id)", kind: kind, enabled: enabled,
            url: "http://127.0.0.1:11434", model: model, provider: provider, apiKey: ""
        )
    }

    private func dict(_ json: String) -> [String: Any] {
        guard let obj = try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any] else {
            XCTFail("fixture parses: \(json)")
            return [:]
        }
        return obj
    }

    // MARK: - STT schema golden (desktop serde shape)

    func testSttSchemaKeysExact() {
        let entry = sttEntry(id: "x", kind: .cloud, provider: "OpenAI", model: "whisper-1")
        XCTAssertEqual(Set(entry.toDict().keys), SttChainEntry.schemaKeys)
    }

    func testSttRoundTripFromDesktopJSON() {
        let entry = SttChainEntry(dict: dict(
            #"{"id":"stt-local","name":"Local","kind":"Local","enabled":true,"provider":"","url":"","model":"","api_key":""}"#
        ))
        XCTAssertNotNil(entry, "Local row parses")
        XCTAssertEqual(entry?.kind, .local)
        // `streaming` absent → core `default_true`.
        XCTAssertEqual(entry?.streaming, true)
        XCTAssertEqual(entry?.summary, "current local configuration")
        guard let back = entry?.toDict() else { return }
        XCTAssertEqual(back["kind"] as? String, "Local")
        XCTAssertEqual(back["streaming"] as? Bool, true)
        XCTAssertEqual(Set(back.keys), SttChainEntry.schemaKeys)
    }

    func testSttSelfHostedAndCloudShapes() {
        let hostedJSON = #"{"id":"s1","name":"Self-hosted (m)","kind":"SelfHosted","enabled":true,"# +
            #""provider":"","url":"http://x:8000","model":"m","api_key":"","streaming":false}"#
        let hosted = SttChainEntry(dict: dict(hostedJSON))
        XCTAssertEqual(hosted?.kind, .selfHosted)
        XCTAssertEqual(hosted?.streaming, false)
        XCTAssertEqual(hosted?.summary, "http://x:8000 · m")
        let cloudJSON = #"{"id":"c1","name":"OpenAI (whisper-1)","kind":"Cloud","enabled":false,"# +
            #""provider":"OpenAI","url":"","model":"whisper-1","api_key":"k"}"#
        let cloud = SttChainEntry(dict: dict(cloudJSON))
        XCTAssertEqual(cloud?.kind, .cloud)
        XCTAssertEqual(cloud?.enabled, false)
        XCTAssertEqual(cloud?.apiKey, "k")
        XCTAssertEqual(cloud?.summary, "OpenAI · whisper-1")
    }

    func testSttUnknownKindRejected() {
        XCTAssertNil(SttChainEntry(dict: ["kind": "Quantum", "enabled": true]))
        XCTAssertNil(SttChainEntry(dict: ["enabled": true]))
    }

    func testSttSectionParseAppliesRoundTrip() {
        let settings: [String: Any] = ["stt": ["entries": [
            sttEntry(id: "a", kind: .local).toDict(),
            sttEntry(id: "b", kind: .cloud, provider: "Groq", model: "m").toDict()
        ]]]
        let parsed = SttChain.parseSection(settings)
        XCTAssertEqual(parsed.map { $0.id }, ["a", "b"])
        let applied = SttChain.applying(parsed, to: [:])
        let entries = (applied["stt"] as? [String: Any])?["entries"] as? [[String: Any]]
        XCTAssertEqual(entries?.count, 2)
        XCTAssertEqual(entries?.first?["kind"] as? String, "Local")
        // Other top-level sections survive the surgery.
        let withPrefs = SttChain.applying(parsed, to: ["preferences": ["x": 1]])
        XCTAssertNotNil(withPrefs["preferences"])
    }

    // MARK: - STT chain: order, reorder, guards

    func testSttChainRespectsEnabledOrder() {
        let entries = [
            sttEntry(id: "a", kind: .local, enabled: false),
            sttEntry(id: "b", kind: .cloud, provider: "OpenAI"),
            sttEntry(id: "c", kind: .selfHosted)
        ]
        XCTAssertEqual(SttChain.chain(entries).map { $0.id }, ["b", "c"])
    }

    func testSttReorderUpDown() {
        let entries = [
            sttEntry(id: "a", kind: .local),
            sttEntry(id: "b", kind: .cloud),
            sttEntry(id: "c", kind: .selfHosted)
        ]
        XCTAssertEqual(SttChain.moveUp(entries, id: "b").map { $0.id }, ["b", "a", "c"])
        XCTAssertEqual(SttChain.moveDown(entries, id: "b").map { $0.id }, ["a", "c", "b"])
        // Clamped edges and unknown ids are no-ops.
        XCTAssertEqual(SttChain.moveUp(entries, id: "a").map { $0.id }, ["a", "b", "c"])
        XCTAssertEqual(SttChain.moveDown(entries, id: "c").map { $0.id }, ["a", "b", "c"])
        XCTAssertEqual(SttChain.moveUp(entries, id: "nope").map { $0.id }, ["a", "b", "c"])
    }

    func testSttDeleteGuardKeepsOneRow() {
        XCTAssertFalse(SttChain.canDelete([sttEntry(id: "a", kind: .local)]))
        XCTAssertFalse(SttChain.canDelete([]))
        XCTAssertTrue(SttChain.canDelete([sttEntry(id: "a", kind: .local), sttEntry(id: "b", kind: .cloud)]))
    }

    func testSttLocalAtMostOnce() {
        XCTAssertFalse(SttChain.canAddLocal([sttEntry(id: "a", kind: .local)]))
        XCTAssertTrue(SttChain.canAddLocal([sttEntry(id: "b", kind: .cloud)]))
        XCTAssertTrue(SttChain.canAddLocal([]))
    }

    func testSttGeminiDetection() {
        XCTAssertTrue(sttEntry(id: "g", kind: .cloud, provider: "Gemini").isGemini)
        XCTAssertTrue(sttEntry(id: "g", kind: .cloud, provider: "  gemini ").isGemini)
        XCTAssertFalse(sttEntry(id: "o", kind: .cloud, provider: "OpenAI").isGemini)
        XCTAssertFalse(sttEntry(id: "l", kind: .local).isGemini)
        XCTAssertFalse(sttEntry(id: "s", kind: .selfHosted).isGemini)
    }

    // MARK: - Cleanup schema golden (desktop serde shape)

    func testCleanupSchemaKeysExact() {
        XCTAssertEqual(Set(CleanupChain.toDict(cleanupEntry(id: "x", kind: .ollama)).keys), CleanupChain.schemaKeys)
    }

    func testCleanupRoundTripToDesktopSchema() {
        let entry = Cleanup.Entry(dict: [
            "id": "lm-1", "name": "Ollama (m)", "kind": "Ollama", "enabled": true,
            "url": "http://127.0.0.1:11434", "model": "m", "provider": "", "api_key": ""
        ])
        XCTAssertNotNil(entry)
        guard let entry, let reparsed = Cleanup.Entry(dict: CleanupChain.toDict(entry)) else { return }
        XCTAssertEqual(reparsed, entry)
        XCTAssertEqual(CleanupChain.toDict(entry)["kind"] as? String, "Ollama")
    }

    func testCleanupCapitalizedKindsMatchSerde() {
        XCTAssertEqual(CleanupChain.capitalizedKind(.ollama), "Ollama")
        XCTAssertEqual(CleanupChain.capitalizedKind(.local), "Local")
        XCTAssertEqual(CleanupChain.capitalizedKind(.cloud), "Cloud")
    }

    func testCleanupApplyingKeepsFieldsThisAppDoesNotEdit() {
        let draft = CleanupSettingsDraft(enabled: true, customPrompt: "", entries: [cleanupEntry(id: "a", kind: .ollama)])
        let stored: [String: Any] = ["language_models": ["entries": [
            ["id": "a", "kind": "Ollama", "keep_alive": "10m", "num_ctx": 8192, "thinking": true, "model": "old"],
        ]]]
        let models = CleanupChain.applying(draft, to: stored)["language_models"] as? [String: Any]
        let row = (models?["entries"] as? [[String: Any]])?.first
        XCTAssertEqual(row?["keep_alive"] as? String, "10m")
        XCTAssertEqual(row?["num_ctx"] as? Int, 8192)
        XCTAssertEqual(row?["thinking"] as? Bool, true)
        XCTAssertEqual(row?["model"] as? String, cleanupEntry(id: "a", kind: .ollama).model)
    }

    func testCleanupApplyingPreservesSections() {
        let draft = CleanupSettingsDraft(
            enabled: false, customPrompt: "Be terse.",
            entries: [cleanupEntry(id: "a", kind: .ollama)]
        )
        let applied = CleanupChain.applying(draft, to: ["stt": ["entries": []]])
        let models = applied["language_models"] as? [String: Any]
        XCTAssertEqual(models?["cleanup_enabled"] as? Bool, false)
        XCTAssertEqual(models?["cleanup_prompt"] as? String, "Be terse.")
        XCTAssertEqual((models?["entries"] as? [[String: Any]])?.count, 1)
        XCTAssertNotNil(applied["stt"], "unrelated sections survive")
    }

    // MARK: - Cleanup chain: reuse, reorder, guards, Why-badges

    func testCleanupEffectiveOrderReusesChainLogic() {
        let entries = [
            cleanupEntry(id: "a", kind: .ollama, enabled: false),
            cleanupEntry(id: "b", kind: .local),
            cleanupEntry(id: "c", kind: .cloud, provider: "OpenAI")
        ]
        // Enabled top-down via Cleanup (not duplicated here).
        XCTAssertEqual(Cleanup.chain(entries).map { $0.id }, ["b", "c"])
        // Runnable skips Local via Cleanup.
        XCTAssertEqual(Cleanup.runnableChain(entries).map { $0.id }, ["c"])
        // Prompt fallback via Cleanup.
        XCTAssertEqual(Cleanup.effectivePrompt(custom: ""), Cleanup.defaultPrompt)
    }

    func testCleanupReorderUpDown() {
        let entries = [
            cleanupEntry(id: "a", kind: .ollama),
            cleanupEntry(id: "b", kind: .cloud),
            cleanupEntry(id: "c", kind: .local)
        ]
        XCTAssertEqual(CleanupChain.moveUp(entries, id: "c").map { $0.id }, ["a", "c", "b"])
        XCTAssertEqual(CleanupChain.moveDown(entries, id: "a").map { $0.id }, ["b", "a", "c"])
        XCTAssertEqual(CleanupChain.moveUp(entries, id: "a").map { $0.id }, ["a", "b", "c"])
        XCTAssertEqual(CleanupChain.moveDown(entries, id: "c").map { $0.id }, ["a", "b", "c"])
        XCTAssertEqual(CleanupChain.move(entries, id: "nope", delta: 1).map { $0.id }, ["a", "b", "c"])
    }

    func testCleanupDeleteGuardAndLocalOnce() {
        XCTAssertFalse(CleanupChain.canDelete([cleanupEntry(id: "a", kind: .ollama)]))
        XCTAssertTrue(CleanupChain.canDelete(
            [cleanupEntry(id: "a", kind: .ollama), cleanupEntry(id: "b", kind: .cloud)]
        ))
        XCTAssertFalse(CleanupChain.canAddLocal([cleanupEntry(id: "a", kind: .local)]))
        XCTAssertTrue(CleanupChain.canAddLocal([cleanupEntry(id: "a", kind: .ollama)]))
    }

    func testCleanupWhyBadges() {
        XCTAssertTrue(CleanupChain.isSkippedLocal(cleanupEntry(id: "a", kind: .local)))
        XCTAssertFalse(CleanupChain.isSkippedLocal(cleanupEntry(id: "b", kind: .ollama)))
        XCTAssertTrue(CleanupChain.isGeminiLike(cleanupEntry(id: "c", kind: .cloud, provider: "Google")))
        XCTAssertTrue(CleanupChain.isGeminiLike(cleanupEntry(id: "c", kind: .cloud, provider: "gemini")))
        XCTAssertFalse(CleanupChain.isGeminiLike(cleanupEntry(id: "d", kind: .cloud, provider: "OpenAI")))
        XCTAssertFalse(CleanupChain.isGeminiLike(cleanupEntry(id: "e", kind: .ollama)))
    }

    // MARK: - Dictionary (reuses DictionaryStore rules)

    func testDictionaryValidationReused() {
        XCTAssertThrowsError(try DictionaryStore.validateWord("   ")) { error in
            XCTAssertEqual(error as? DictionaryError, .emptyWord)
        }
        XCTAssertThrowsError(try DictionaryStore.validateSnippet(trigger: "", replacement: "x")) { error in
            XCTAssertEqual(error as? DictionaryError, .emptyTrigger)
        }
        XCTAssertThrowsError(try DictionaryStore.validateSnippet(trigger: "x", replacement: "  ")) { error in
            XCTAssertEqual(error as? DictionaryError, .emptyReplacement)
        }
        XCTAssertNoThrow(try DictionaryStore.validateSnippet(trigger: "x", replacement: "y"))
    }

    func testDictionaryDisplayOrderLongestFirst() {
        let snippets = [
            DictionaryStore.Snippet(trigger: "ask", replacement: "asks"),
            DictionaryStore.Snippet(trigger: "investor ask", replacement: "investor asks"),
            DictionaryStore.Snippet(trigger: "buy", replacement: "buys")
        ]
        let sorted = DictionaryStore.sortedForDisplay(snippets)
        XCTAssertEqual(sorted.first?.trigger, "investor ask")
        XCTAssertEqual(sorted.map { $0.trigger }, ["investor ask", "ask", "buy"])
    }

    func testDictionaryErrorCopy() {
        XCTAssertFalse(dictionaryErrorText(.emptyWord).isEmpty)
        XCTAssertFalse(dictionaryErrorText(.emptyTrigger).isEmpty)
        XCTAssertFalse(dictionaryErrorText(.emptyReplacement).isEmpty)
    }
}
