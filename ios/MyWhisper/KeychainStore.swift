import Foundation
import Security

/// Keychain-backed database key for `state.db`, iOS mirror of Android
/// `KeystoreKey` (Keystore-backed EncryptedSharedPreferences) and the
/// desktop Secret Service `mywhisper/state-db` entry.
///
/// Contract: a random 32-byte key, handed to the core as 64 lowercase
/// hex chars via `CoreBridge.open(filesDir:dbKeyHex:)`. Same encoding
/// on all platforms — a `state.db` moved between devices opens with
/// the same key.
///
/// Keychain item: `kSecClassGenericPassword`, account
/// `"mywhisper-db-key"`, default access group. There is deliberately
/// NO custom `kSecAttrAccessGroup` override: single-app v1 uses the
/// default group granted by the Keychain-sharing entitlement, and
/// extensions re-share it later.
///
/// Reinstall semantics: iOS Keychain items survive app uninstall while
/// `state.db` (app container) does not. After a reinstall
/// `getOrCreateHex` finds the previous key and pairs it with a fresh
/// DB — harmless (no lockout, no orphaned ciphertext).
///
/// SQLCipher is explicitly deferred (Android also ships plaintext for
/// now): the key is generated, stored, and plumbed through, but the
/// core does not yet encrypt `state.db`. The convergence task (enable
/// SQLCipher on both platforms, wire re-key on rotation) is recorded
/// in the PR body, not implemented here.
///
/// Fail-closed validation mirrors `KeystoreKey.isValidHexKey`: a
/// stored value that is not 64 hex chars is dropped and replaced,
/// never handed to the core.
enum KeychainStore {
    static let dbKeyAccount = "mywhisper-db-key"
    static let keyLength = 32

    private static let hexDigits = Set("0123456789abcdefABCDEF")

    /// True when `value` is exactly 64 hex chars (mirrors
    /// `KeystoreKey.isValidHexKey`, case-insensitive by definition).
    static func isValidHexKey(_ value: String) -> Bool {
        value.count == 64 && value.allSatisfy(hexDigits.contains)
    }

    static func hexString(_ bytes: [UInt8]) -> String {
        bytes.map { String(format: "%02x", $0) }.joined()
    }

    /// Production RNG: `count` bytes via `SecRandomCopyBytes`.
    static func systemRandomBytes(_ count: Int) throws -> [UInt8] {
        var bytes = [UInt8](repeating: 0, count: count)
        let status = bytes.withUnsafeMutableBytes { buffer -> OSStatus in
            guard let base = buffer.baseAddress else { return errSecParam }
            return SecRandomCopyBytes(kSecRandomDefault, count, base)
        }
        guard status == errSecSuccess else { throw KeychainError.rngFailed(status) }
        return bytes
    }

    // MARK: - Production entry points (system Keychain + system RNG)

    static func getOrCreateHex() throws -> String {
        try getOrCreateHex(backend: SystemKeychainBackend(), randomBytes: systemRandomBytes)
    }

    static func rotateHex() throws -> String {
        try rotateHex(backend: SystemKeychainBackend(), randomBytes: systemRandomBytes)
    }

    static func deleteHex() throws {
        try deleteHex(backend: SystemKeychainBackend())
    }

    // MARK: - Injectable core (unit tests use a fake backend + stub RNG)

    static func getOrCreateHex(
        account: String = dbKeyAccount,
        backend: KeychainBackend,
        randomBytes: KeychainRandomBytes
    ) throws -> String {
        if let stored = try backend.loadHex(account: account), isValidHexKey(stored) {
            return stored
        }
        let fresh = hexString(try randomBytes(keyLength))
        try backend.saveHex(fresh, account: account)
        return fresh
    }

    /// Generate a fresh key and persist it, replacing the old one.
    /// NOTE: SQLCipher re-key of the live `state.db` is deferred with
    /// SQLCipher itself — until then this only rotates the stored key.
    static func rotateHex(
        account: String = dbKeyAccount,
        backend: KeychainBackend,
        randomBytes: KeychainRandomBytes
    ) throws -> String {
        let fresh = hexString(try randomBytes(keyLength))
        try backend.saveHex(fresh, account: account)
        return fresh
    }

    static func deleteHex(
        account: String = dbKeyAccount,
        backend: KeychainBackend
    ) throws {
        try backend.delete(account: account)
    }
}

typealias KeychainRandomBytes = (Int) throws -> [UInt8]

/// Minimal Keychain surface `KeychainStore` needs. The production
/// implementation below is a thin `SecItem*` wrapper; tests inject a
/// fake so no test ever touches the real Keychain.
protocol KeychainBackend {
    func loadHex(account: String) throws -> String?
    func saveHex(_ hex: String, account: String) throws
    func delete(account: String) throws
}

struct SystemKeychainBackend: KeychainBackend {
    private func baseQuery(account: String) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: account
        ]
    }

    func loadHex(account: String) throws -> String? {
        var query = baseQuery(account: account)
        query[kSecReturnData as String] = true
        query[kSecMatchLimit as String] = kSecMatchLimitOne
        var item: CFTypeRef?
        switch SecItemCopyMatching(query as CFDictionary, &item) {
        case errSecSuccess:
            guard let data = item as? Data, let hex = String(data: data, encoding: .utf8) else {
                return nil
            }
            return hex
        case errSecItemNotFound:
            return nil
        case let status:
            throw KeychainError.loadFailed(status)
        }
    }

    func saveHex(_ hex: String, account: String) throws {
        var add = baseQuery(account: account)
        add[kSecValueData as String] = Data(hex.utf8)
        switch SecItemAdd(add as CFDictionary, nil) {
        case errSecSuccess:
            return
        case errSecDuplicateItem:
            let status = SecItemUpdate(
                baseQuery(account: account) as CFDictionary,
                [kSecValueData as String: Data(hex.utf8)] as CFDictionary
            )
            guard status == errSecSuccess else { throw KeychainError.storeFailed(status) }
        case let status:
            throw KeychainError.storeFailed(status)
        }
    }

    func delete(account: String) throws {
        switch SecItemDelete(baseQuery(account: account) as CFDictionary) {
        case errSecSuccess, errSecItemNotFound:
            return
        case let status:
            throw KeychainError.deleteFailed(status)
        }
    }
}

enum KeychainError: Error, Equatable {
    case rngFailed(OSStatus)
    case loadFailed(OSStatus)
    case storeFailed(OSStatus)
    case deleteFailed(OSStatus)
}
