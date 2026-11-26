import Foundation
import Security

/// Swift bridge to `mywhisper-core` (`core/src/capple.rs`), via the
/// `ios/Libs/libmywhisper_core.xcframework` built by
/// `scripts/build-core-ios.sh`.
///
/// Mirrors Android `CoreBridge.kt`: every mutating/status call returns
/// the core's `{ok, data?, error?}` envelope as a raw string and the
/// helpers below parse it. The raw getters (`getSettings`, history,
/// words, snippets) return unwrapped JSON exactly like their JNI twins.
/// Transcription blocks — always call `finishUtterance` off the main thread.
enum CoreBridge {
    // MARK: - FFI (symbols live in the xcframework static lib)

    @_silgen_name("mywhisper_core_version") private static func ffiCoreVersion()
        -> UnsafeMutablePointer<CChar>?
    @_silgen_name("mywhisper_free_string") private static func ffiFree(_ s: UnsafeMutablePointer<CChar>?)
    @_silgen_name("mywhisper_engine_open") private static func ffiOpen(
        _ filesDir: UnsafePointer<CChar>?, _ dbKeyHex: UnsafePointer<CChar>?
    ) -> UnsafeMutablePointer<CChar>?
    @_silgen_name("mywhisper_get_settings") private static func ffiGetSettings()
        -> UnsafeMutablePointer<CChar>?
    @_silgen_name("mywhisper_put_settings") private static func ffiPutSettings(
        _ json: UnsafePointer<CChar>?
    ) -> UnsafeMutablePointer<CChar>?
    @_silgen_name("mywhisper_start_utterance") private static func ffiStartUtterance(_ rate: UInt32)
        -> UnsafeMutablePointer<CChar>?
    @_silgen_name("mywhisper_push_pcm") private static func ffiPushPcm(
        _ data: UnsafePointer<Int16>?, _ len: Int
    ) -> UnsafeMutablePointer<CChar>?
    @_silgen_name("mywhisper_finish_utterance") private static func ffiFinishUtterance()
        -> UnsafeMutablePointer<CChar>?
    @_silgen_name("mywhisper_cancel_utterance") private static func ffiCancelUtterance()
        -> UnsafeMutablePointer<CChar>?
    @_silgen_name("mywhisper_get_history") private static func ffiGetHistory(_ limit: Int32)
        -> UnsafeMutablePointer<CChar>?
    @_silgen_name("mywhisper_get_history_page") private static func ffiGetHistoryPage(
        _ limit: Int32, _ beforeTs: Int64, _ beforeUuid: UnsafePointer<CChar>?, _ query: UnsafePointer<CChar>?
    ) -> UnsafeMutablePointer<CChar>?
    @_silgen_name("mywhisper_clear_history") private static func ffiClearHistory()
        -> UnsafeMutablePointer<CChar>?
    @_silgen_name("mywhisper_delete_history") private static func ffiDeleteHistory(
        _ uuid: UnsafePointer<CChar>?
    ) -> UnsafeMutablePointer<CChar>?
    @_silgen_name("mywhisper_get_words") private static func ffiGetWords()
        -> UnsafeMutablePointer<CChar>?
    @_silgen_name("mywhisper_add_word") private static func ffiAddWord(
        _ word: UnsafePointer<CChar>?
    ) -> UnsafeMutablePointer<CChar>?
    @_silgen_name("mywhisper_delete_word") private static func ffiDeleteWord(
        _ word: UnsafePointer<CChar>?
    ) -> UnsafeMutablePointer<CChar>?
    @_silgen_name("mywhisper_get_snippets") private static func ffiGetSnippets()
        -> UnsafeMutablePointer<CChar>?
    @_silgen_name("mywhisper_put_snippet") private static func ffiPutSnippet(
        _ trigger: UnsafePointer<CChar>?, _ replacement: UnsafePointer<CChar>?
    ) -> UnsafeMutablePointer<CChar>?
    @_silgen_name("mywhisper_delete_snippet") private static func ffiDeleteSnippet(
        _ trigger: UnsafePointer<CChar>?
    ) -> UnsafeMutablePointer<CChar>?
    @_silgen_name("mywhisper_purge_history") private static func ffiPurgeHistory(_ days: Int32)
        -> UnsafeMutablePointer<CChar>?
    @_silgen_name("mywhisper_default_cleanup_prompt") private static func ffiDefaultCleanupPrompt()
        -> UnsafeMutablePointer<CChar>?
    @_silgen_name("mywhisper_usage_stats") private static func ffiUsageStats()
        -> UnsafeMutablePointer<CChar>?
    @_silgen_name("mywhisper_daily_activity") private static func ffiDailyActivity(_ days: Int32)
        -> UnsafeMutablePointer<CChar>?
    @_silgen_name("mywhisper_display_name") private static func ffiDisplayName()
        -> UnsafeMutablePointer<CChar>?
    @_silgen_name("mywhisper_sync_start") private static func ffiSyncStart()
        -> UnsafeMutablePointer<CChar>?
    @_silgen_name("mywhisper_sync_stop") private static func ffiSyncStop()
        -> UnsafeMutablePointer<CChar>?
    @_silgen_name("mywhisper_sync_status") private static func ffiSyncStatus()
        -> UnsafeMutablePointer<CChar>?
    @_silgen_name("mywhisper_sync_peers") private static func ffiSyncPeers()
        -> UnsafeMutablePointer<CChar>?
    @_silgen_name("mywhisper_sync_add") private static func ffiSyncAdd(
        _ nodeId: UnsafePointer<CChar>?, _ address: UnsafePointer<CChar>?, _ name: UnsafePointer<CChar>?
    ) -> UnsafeMutablePointer<CChar>?
    @_silgen_name("mywhisper_sync_trust") private static func ffiSyncTrust(
        _ nodeId: UnsafePointer<CChar>?, _ accept: Bool
    ) -> UnsafeMutablePointer<CChar>?
    @_silgen_name("mywhisper_sync_now") private static func ffiSyncNow(_ nodeId: UnsafePointer<CChar>?)
        -> UnsafeMutablePointer<CChar>?
    @_silgen_name("mywhisper_sync_all_now") private static func ffiSyncAllNow()
        -> UnsafeMutablePointer<CChar>?

    /// Take ownership of a core-returned C string (frees via the core).
    private static func take(_ ptr: UnsafeMutablePointer<CChar>?) throws -> String {
        guard let ptr = ptr else { throw CoreError.nullReply }
        defer { ffiFree(ptr) }
        return String(cString: ptr)
    }

    private static func call(_ ptr: UnsafeMutablePointer<CChar>?) throws -> String {
        try take(ptr)
    }

    private static func withCString(_ s: String, _ body: (UnsafePointer<CChar>?) throws -> String) rethrows -> String {
        try s.withCString { try body($0) }
    }

    // MARK: - Envelope

    /// Parse `{ok, data?, error?}`. Returns data (NSNull when null) or throws with error text.
    static func dataOf(_ raw: String) throws -> Any? {
        guard let json = try? JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any] else {
            throw CoreError.badReply("not JSON: \(raw.prefix(120))")
        }
        guard (json["ok"] as? Bool) == true else {
            throw CoreError.coreError(json["error"] as? String ?? "unknown core error")
        }
        let data = json["data"]
        if data == nil || data is NSNull { return nil }
        return data
    }

    // MARK: - Engine

    static func coreVersion() throws -> String {
        try call(ffiCoreVersion())
    }

    /// Open (or create) `state.db` under `filesDir`. Returns core version. Call once at launch.
    @discardableResult
    static func open(filesDir: String, dbKeyHex: String) throws -> String {
        let raw = try withCString(filesDir) { dirPtr in
            try withCString(dbKeyHex) { keyPtr in try call(ffiOpen(dirPtr, keyPtr)) }
        }
        guard let obj = try dataOf(raw) as? [String: Any],
              let version = obj["version"] as? String
        else { throw CoreError.badReply("bad open reply") }
        return version
    }

    /// Raw settings JSON (unwrapped, same bytes as Android `nativeGetSettings`).
    static func settingsJSON() throws -> String {
        try call(ffiGetSettings())
    }

    static func getSettings() throws -> [String: Any] {
        let raw = try settingsJSON()
        guard let obj = try? JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any] else {
            throw CoreError.badReply("settings not an object")
        }
        return obj
    }

    static func putSettings(json: String) throws {
        _ = try dataOf(withCString(json) { try call(ffiPutSettings($0)) })
    }

    static func putSettings(_ dict: [String: Any]) throws {
        let data = try JSONSerialization.data(withJSONObject: dict)
        guard let json = String(data: data, encoding: .utf8) else { throw CoreError.badReply("encode") }
        try putSettings(json: json)
    }

    // MARK: - Utterance (T2 owns dictation flow; these are the transport)

    static func startUtterance(sampleRate: UInt32) throws {
        _ = try dataOf(call(ffiStartUtterance(sampleRate)))
    }

    static func pushPcm(_ samples: [Int16]) throws {
        let raw = try samples.withUnsafeBufferPointer { ptr in
            try call(ffiPushPcm(ptr.baseAddress, samples.count))
        }
        _ = try dataOf(raw)
    }

    /// Blocks on the server chain. Returns (text, engine) or throws.
    static func finishUtterance() throws -> (text: String, engine: String) {
        guard let obj = try dataOf(call(ffiFinishUtterance())) as? [String: Any],
              let text = obj["text"] as? String
        else { throw CoreError.badReply("bad finish reply") }
        return (text, obj["engine"] as? String ?? "")
    }

    static func cancelUtterance() throws {
        _ = try dataOf(call(ffiCancelUtterance()))
    }

    // MARK: - History

    static func history(limit: Int32 = 50) throws -> [[String: Any]] {
        let raw = try call(ffiGetHistory(max(limit, 1)))
        guard let arr = try? JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [[String: Any]] else {
            throw CoreError.badReply("history not an array")
        }
        return arr
    }

    /// Newest first, `limit` rows before the `before` row (nil = first page), filtered in the database.
    static func historyPage(limit: Int32, before: (recordedAt: Int64, uuid: String)?, query: String) throws -> [[String: Any]] {
        let raw = try call(ffiGetHistoryPage(max(limit, 1), before?.recordedAt ?? 0, before?.uuid ?? "", query))
        guard let arr = try? JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [[String: Any]] else {
            throw CoreError.badReply("history not an array")
        }
        return arr
    }

    static func clearHistory() throws -> Int {
        guard let obj = try dataOf(call(ffiClearHistory())) as? [String: Any],
              let removed = obj["removed"] as? Int
        else { throw CoreError.badReply("bad clear reply") }
        return removed
    }

    static func deleteHistory(uuid: String) throws {
        _ = try dataOf(withCString(uuid) { try call(ffiDeleteHistory($0)) })
    }

    // MARK: - Dictionary (store-backed, desktop app_dictionary parity)

    static func getWords() throws -> [String] {
        let raw = try call(ffiGetWords())
        guard let arr = try? JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String] else {
            throw CoreError.badReply("words not an array")
        }
        return arr
    }

    static func addWord(_ word: String) throws {
        _ = try dataOf(withCString(word) { try call(ffiAddWord($0)) })
    }

    static func deleteWord(_ word: String) throws {
        _ = try dataOf(withCString(word) { try call(ffiDeleteWord($0)) })
    }

    static func getSnippets() throws -> [(trigger: String, replacement: String)] {
        let raw = try call(ffiGetSnippets())
        guard let arr = try? JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [[String: String]] else {
            throw CoreError.badReply("snippets not an array")
        }
        return try arr.map {
            guard let trigger = $0["trigger"], let replacement = $0["replacement"] else {
                throw CoreError.badReply("bad snippet row")
            }
            return (trigger, replacement)
        }
    }

    static func putSnippet(trigger: String, replacement: String) throws {
        let raw = try withCString(trigger) { triggerPtr in
            try withCString(replacement) { replacementPtr in
                try call(ffiPutSnippet(triggerPtr, replacementPtr))
            }
        }
        _ = try dataOf(raw)
    }

    static func deleteSnippet(trigger: String) throws {
        _ = try dataOf(withCString(trigger) { try call(ffiDeleteSnippet($0)) })
    }

    // MARK: - Stats and retention (Android CoreBridge.kt parity)

    /// Whole-history totals: `wordsSpoken`, `dictations`, `wordsPerMinute`, `streakDays`.
    static func usageStats() throws -> [String: Any] {
        guard let obj = try dataOf(call(ffiUsageStats())) as? [String: Any] else {
            throw CoreError.badReply("bad stats reply")
        }
        return obj
    }

    /// `[{dayStart, words, utterances}]` for the trailing `days` days (dayStart: epoch seconds).
    static func dailyActivity(days: Int32) throws -> [[String: Any]] {
        guard let rows = try dataOf(call(ffiDailyActivity(days))) as? [[String: Any]] else {
            throw CoreError.badReply("bad activity reply")
        }
        return rows
    }

    /// Deletes history older than `days`; returns the removed count.
    @discardableResult
    static func purgeHistory(olderThanDays days: Int32) throws -> Int {
        guard let obj = try dataOf(call(ffiPurgeHistory(days))) as? [String: Any],
              let removed = obj["removed"] as? Int
        else { throw CoreError.badReply("bad purge reply") }
        return removed
    }

    /// The built-in cleanup prompt the core uses when the custom one is empty.
    static func defaultCleanupPrompt() throws -> String {
        guard let prompt = try dataOf(call(ffiDefaultCleanupPrompt())) as? String else {
            throw CoreError.badReply("bad prompt reply")
        }
        return prompt
    }

    // MARK: - Sync (Android CoreBridge.kt parity; blocking calls belong off the main thread)

    static func displayName() throws -> String {
        guard let name = try dataOf(call(ffiDisplayName())) as? String else {
            throw CoreError.badReply("bad name reply")
        }
        return name
    }

    @discardableResult
    static func syncStart() throws -> String {
        try dataOf(call(ffiSyncStart())) as? String ?? ""
    }

    static func syncStop() {
        _ = try? call(ffiSyncStop())
    }

    /// Raw status object; an error envelope throws.
    static func syncStatus() throws -> [String: Any] {
        let raw = try call(ffiSyncStatus())
        guard let obj = try? JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [String: Any] else {
            throw CoreError.badReply("bad sync status")
        }
        if obj["ok"] as? Bool == false { throw CoreError.coreError(obj["error"] as? String ?? "sync status failed") }
        return obj
    }

    static func syncPeers() throws -> [[String: Any]] {
        let raw = try call(ffiSyncPeers())
        guard let rows = try? JSONSerialization.jsonObject(with: Data(raw.utf8)) as? [[String: Any]] else {
            throw CoreError.badReply("peers not an array")
        }
        return rows
    }

    @discardableResult
    static func syncAdd(nodeId: String, address: String, name: String) throws -> String {
        let raw = try withCString(nodeId) { idPtr in
            try withCString(address) { addressPtr in
                try withCString(name) { namePtr in try call(ffiSyncAdd(idPtr, addressPtr, namePtr)) }
            }
        }
        return try dataOf(raw) as? String ?? ""
    }

    @discardableResult
    static func syncTrust(nodeId: String, accept: Bool) throws -> String {
        try dataOf(withCString(nodeId) { try call(ffiSyncTrust($0, accept)) }) as? String ?? ""
    }

    @discardableResult
    static func syncNow(nodeId: String) throws -> String {
        try dataOf(withCString(nodeId) { try call(ffiSyncNow($0)) }) as? String ?? ""
    }

    static func syncAllNow() throws -> [String] {
        try dataOf(call(ffiSyncAllNow())) as? [String] ?? []
    }

    // MARK: - Key (Keychain-backed, shared via App Group Keychain sharing)

    /// 64-hex-char DB key, created once in the app Keychain. Same
    /// ciphertext format as desktop/Android — a `state.db` moved
    /// between devices opens with the same key. (No access-group
    /// override: single-app v1 uses the default group, which is what
    /// the Keychain-sharing entitlement grants; extensions re-share
    /// it later.)
    static func dbKeyHex() throws -> String {
        let account = "mywhisper-db-key"
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]
        var item: CFTypeRef?
        if SecItemCopyMatching(query as CFDictionary, &item) == errSecSuccess,
           let data = item as? Data,
           let hex = String(data: data, encoding: .utf8), hex.count == 64 {
            return hex
        }
        var bytes = [UInt8](repeating: 0, count: 32)
        guard SecRandomCopyBytes(kSecRandomDefault, bytes.count, &bytes) == errSecSuccess else {
            throw CoreError.coreError("rng failed")
        }
        let hex = bytes.map { String(format: "%02x", $0) }.joined()
        var add = query
        add[kSecValueData as String] = Data(hex.utf8)
        add.removeValue(forKey: kSecReturnData as String)
        add.removeValue(forKey: kSecMatchLimit as String)
        let status = SecItemAdd(add as CFDictionary, nil)
        if status == errSecDuplicateItem { return try dbKeyHex() }
        guard status == errSecSuccess else { throw CoreError.coreError("keychain store failed (\(status))") }
        return hex
    }

    // MARK: - Generation-guard jobs (CoreBridge.kt SettingsJobs port)

    /// Generation-aware cancellable job plumbing (desktop settings-page
    /// jobs parity: `ServerProbeTask` / `ModelLoad` / `CHECK_EPOCH`).
    ///
    /// Screens claim a generation before running a worker; the worker
    /// commits its result on the main actor only when its generation is
    /// still current. Config changes, navigation or a newer job bump
    /// the generation, so stale results are dropped instead of
    /// clobbering newer committed state.
    ///
    /// Pure logic (no FFI) for unit tests. Keep it that way: members
    /// must never touch `ffi*` nor do I/O.
    enum SettingsJobs {
        private static let lock = NSLock()
        private static var epoch: Int64 = 0

        /// Committed-store warning meaning "reset left a retry behind".
        static let resetIncompleteWarning = "settings.reset.incomplete"

        /// Claim the next generation for a new job.
        static func claim() -> Int64 {
            lock.lock(); defer { lock.unlock() }
            epoch &+= 1
            return epoch
        }

        /// True when `gen` is still the latest claimed generation.
        static func isCurrent(_ gen: Int64) -> Bool {
            lock.lock(); defer { lock.unlock() }
            return gen != 0 && gen == epoch
        }

        /// Cancel all in-flight jobs: stale workers drop their results.
        static func invalidate() {
            lock.lock(); defer { lock.unlock() }
            epoch &+= 1
        }

        /// Per-screen generation guard (desktop `Rc::ptr_eq` parity):
        /// a worker commits only when its generation still equals the
        /// screen's current one.
        static func jobApplies(currentGen: Int64, jobGen: Int64) -> Bool {
            currentGen == jobGen
        }

        /// Next per-screen generation (wraps past Int64.max, never 0).
        static func nextJobGen(_ currentGen: Int64) -> Int64 {
            if currentGen == Int64.max || currentGen + 1 == 0 { return 1 }
            return currentGen + 1
        }

        /// True when the committed warning leaves a retryable reset behind.
        static func resetNeedsRetry(warning: String?) -> Bool {
            warning == resetIncompleteWarning
        }

        /// Test-only reset of the shared epoch.
        static func resetForTests() {
            lock.lock(); defer { lock.unlock() }
            epoch = 0
        }
    }
}

enum CoreError: Error, Equatable {
    case coreError(String)
    case badReply(String)
    case nullReply

    /// User-facing message: avoids leaking `String(describing:)` output
    /// like `coreError("...")` into the UI (see `AppFlow.coreError`).
    var message: String {
        switch self {
        case .coreError(let text), .badReply(let text):
            return text
        case .nullReply:
            return "empty reply from speech engine"
        }
    }

    static func displayMessage(for error: Error) -> String {
        (error as? CoreError)?.message ?? error.localizedDescription
    }
}
