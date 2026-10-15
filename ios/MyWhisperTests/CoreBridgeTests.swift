import XCTest
@testable import MyWhisper

/// Core round-trip through the real `mywhisper-core` static lib
/// (requires `scripts/build-core-ios.sh` first so the xcframework the
/// app target links exists). Dictation-free: T2 owns the utterance flow.
///
/// Byte-parity note: `getSettings` returns the bytes of the shared
/// `AppSettings` struct — the same struct Android's `nativeGetSettings`
/// serializes — so a fresh-install payload matches Android's for the
/// seed schema. The host-side proof lives in
/// `core/src/capple.rs::open_get_put_roundtrip_is_byte_identical`
/// (runs under `cargo test`); these tests assert the same properties
/// through the Swift envelope parsing.
final class CoreBridgeTests: XCTestCase {
    private var dir: String!

    override func setUpWithError() throws {
        dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("mywhisper-xctest-\(UUID().uuidString)", isDirectory: true).path
        try FileManager.default.createDirectory(atPath: dir, withIntermediateDirectories: true)
        _ = try CoreBridge.open(filesDir: dir, dbKeyHex: String(repeating: "ab", count: 32))
    }

    // MARK: - Envelope parsing (pure Swift, mirrors CoreBridge.kt dataOf)

    func testDataOfRoundTrip() throws {
        let data = try CoreBridge.dataOf(#"{"ok":true,"data":{"version":"0.1.0"}}"#) as? [String: Any]
        XCTAssertEqual(data?["version"] as? String, "0.1.0")
        XCTAssertNil(try CoreBridge.dataOf(#"{"ok":true,"data":null}"#))
        XCTAssertThrowsError(try CoreBridge.dataOf(#"{"ok":false,"error":"boom"}"#))
        XCTAssertThrowsError(try CoreBridge.dataOf("not json"))
    }

    // MARK: - open/get/put with a tmp dir

    func testOpenReportsVersion() throws {
        let version = try CoreBridge.open(filesDir: dir, dbKeyHex: String(repeating: "ab", count: 32))
        XCTAssertFalse(version.isEmpty)
    }

    func testSettingsRoundTripIsByteIdentical() throws {
        let first = try CoreBridge.settingsJSON()
        // Seed schema: the 8 top-level sections Android serializes.
        let obj = try CoreBridge.getSettings()
        for key in ["preferences", "hotkeys", "stt", "language_models",
                    "privacy", "system", "dictionary", "sync"] {
            XCTAssertNotNil(obj[key], "missing settings section: \(key)")
        }
        // Idempotent put reads back byte-for-byte.
        try CoreBridge.putSettings(json: first)
        XCTAssertEqual(try CoreBridge.settingsJSON(), first)
    }

    func testSettingsMutationPersists() throws {
        var obj = try CoreBridge.getSettings()
        var privacy = obj["privacy"] as? [String: Any] ?? [:]
        privacy["history_enabled"] = false
        obj["privacy"] = privacy
        try CoreBridge.putSettings(obj)
        let back = try CoreBridge.getSettings()
        XCTAssertEqual((back["privacy"] as? [String: Any])?["history_enabled"] as? Bool, false)
    }

    func testHistoryWordsSnippetsStartEmpty() throws {
        XCTAssertEqual(try CoreBridge.history(limit: 10).count, 0)
        XCTAssertEqual(try CoreBridge.getWords().count, 0)
        XCTAssertEqual(try CoreBridge.getSnippets().count, 0)
    }
}
