import XCTest
@testable import MyWhisper

/// `KeychainStore` unit tests. Never touch the real Keychain or the
/// system RNG: every test runs through `FakeBackend` + stub random
/// bytes, so these run under `xcodebuild test` on a Mac with no
/// Keychain state involved.
final class KeychainTests: XCTestCase {
    private final class FakeBackend: KeychainBackend {
        var storage: [String: String] = [:]
        var loads = 0
        var saves = 0

        func loadHex(account: String) throws -> String? {
            loads += 1
            return storage[account]
        }

        func saveHex(_ hex: String, account: String) throws {
            saves += 1
            storage[account] = hex
        }

        func delete(account: String) throws {
            storage.removeValue(forKey: account)
        }
    }

    private static let account = "test-db-key"
    private static let validKey = String(repeating: "ab", count: 32)
    private static let otherKey = String(repeating: "cd", count: 32)

    private func stubBytes(_ bytes: [UInt8]) -> KeychainRandomBytes {
        { count in
            XCTAssertEqual(count, KeychainStore.keyLength)
            return bytes
        }
    }

    // MARK: - isValidHexKey vectors (mirror KeystoreKey.isValidHexKey)

    func testValidHexKeyVectors() {
        XCTAssertTrue(KeychainStore.isValidHexKey(String(repeating: "0", count: 64)))
        XCTAssertTrue(KeychainStore.isValidHexKey(String(repeating: "f", count: 64)))
        XCTAssertTrue(KeychainStore.isValidHexKey(String(repeating: "F", count: 64)))
        XCTAssertTrue(KeychainStore.isValidHexKey(Self.validKey))
        XCTAssertTrue(KeychainStore.isValidHexKey("0123456789abcdefABCDEF" + String(repeating: "0", count: 42)))
    }

    func testInvalidHexKeyVectors() {
        XCTAssertFalse(KeychainStore.isValidHexKey(""))
        XCTAssertFalse(KeychainStore.isValidHexKey(String(repeating: "a", count: 63)))
        XCTAssertFalse(KeychainStore.isValidHexKey(String(repeating: "a", count: 65)))
        XCTAssertFalse(KeychainStore.isValidHexKey(String(repeating: "g", count: 64)))
        XCTAssertFalse(KeychainStore.isValidHexKey(String(repeating: "z", count: 64)))
        XCTAssertFalse(KeychainStore.isValidHexKey(String(repeating: " ", count: 64)))
        XCTAssertFalse(KeychainStore.isValidHexKey(String(repeating: "ab", count: 31) + " "))
        XCTAssertFalse(KeychainStore.isValidHexKey("550e8400-e29b-41d4-a716-446655440000" + String(repeating: "0", count: 28)))
        XCTAssertFalse(KeychainStore.isValidHexKey(Self.validKey + "\n"))
        XCTAssertFalse(KeychainStore.isValidHexKey("0x" + String(repeating: "a", count: 62)))
    }

    // MARK: - getOrCreateHex

    func testGetOrCreateReturnsStoredValidKeyWithoutGenerating() throws {
        let backend = FakeBackend()
        backend.storage[Self.account] = Self.validKey
        let hex = try KeychainStore.getOrCreateHex(account: Self.account, backend: backend) { _ in
            XCTFail("RNG must not run when a valid key is stored")
            return []
        }
        XCTAssertEqual(hex, Self.validKey)
        XCTAssertEqual(backend.saves, 0)
    }

    func testGetOrCreateGeneratesThirtyTwoBytesWhenEmpty() throws {
        let backend = FakeBackend()
        let hex = try KeychainStore.getOrCreateHex(
            account: Self.account,
            backend: backend,
            randomBytes: stubBytes([UInt8](repeating: 0xAB, count: 32))
        )
        XCTAssertEqual(hex, String(repeating: "ab", count: 32))
        XCTAssertEqual(backend.storage[Self.account], hex)
        XCTAssertEqual(backend.saves, 1)
    }

    func testGetOrCreateReplacesMalformedStoredValues() throws {
        let malformed = [
            "",
            "short",
            String(repeating: "g", count: 64),
            String(repeating: "a", count: 63),
            String(repeating: "a", count: 65),
            String(repeating: "zz", count: 32)
        ]
        for bad in malformed {
            let backend = FakeBackend()
            backend.storage[Self.account] = bad
            let hex = try KeychainStore.getOrCreateHex(
                account: Self.account,
                backend: backend,
                randomBytes: stubBytes([UInt8](repeating: 0xCD, count: 32))
            )
            XCTAssertTrue(KeychainStore.isValidHexKey(hex), "replacement for \(bad.prefix(16)) must be valid hex")
            XCTAssertNotEqual(hex, bad)
            XCTAssertEqual(backend.storage[Self.account], hex)
        }
    }

    func testHexEncodingPadsNibbles() throws {
        let backend = FakeBackend()
        var bytes = [UInt8](repeating: 0, count: 32)
        bytes[0] = 0x00
        bytes[1] = 0x0F
        bytes[2] = 0x10
        bytes[3] = 0xFF
        let hex = try KeychainStore.getOrCreateHex(
            account: Self.account,
            backend: backend,
            randomBytes: stubBytes(bytes)
        )
        XCTAssertEqual(hex, "000f10ff" + String(repeating: "00", count: 28))
    }

    // MARK: - rotateHex

    func testRotateReplacesStoredKey() throws {
        let backend = FakeBackend()
        backend.storage[Self.account] = Self.validKey
        let fresh = try KeychainStore.rotateHex(
            account: Self.account,
            backend: backend,
            randomBytes: stubBytes([UInt8](repeating: 0xCD, count: 32))
        )
        XCTAssertEqual(fresh, Self.otherKey)
        XCTAssertNotEqual(fresh, Self.validKey)
        XCTAssertEqual(backend.storage[Self.account], fresh)
    }

    func testRotateFromEmptyStoresNewKey() throws {
        let backend = FakeBackend()
        let fresh = try KeychainStore.rotateHex(
            account: Self.account,
            backend: backend,
            randomBytes: stubBytes([UInt8](repeating: 0xAB, count: 32))
        )
        XCTAssertEqual(fresh, Self.validKey)
        XCTAssertEqual(backend.storage[Self.account], fresh)
    }

    // MARK: - deleteHex

    func testDeleteThenGetOrCreateGeneratesFresh() throws {
        let backend = FakeBackend()
        backend.storage[Self.account] = Self.validKey
        try KeychainStore.deleteHex(account: Self.account, backend: backend)
        XCTAssertNil(backend.storage[Self.account])
        let hex = try KeychainStore.getOrCreateHex(
            account: Self.account,
            backend: backend,
            randomBytes: stubBytes([UInt8](repeating: 0xCD, count: 32))
        )
        XCTAssertEqual(hex, Self.otherKey)
    }
}
