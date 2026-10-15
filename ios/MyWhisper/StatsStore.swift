import Foundation

/// One history row, as returned by the core `history_json` (via
/// `CoreBridge.history`). Field names match the core mapping exactly:
/// `uuid`, `text`, `rawText`, `dateLabel`, `wordCount`, `durationSecs`,
/// `recordedAt` (unix seconds), `discarded`.
///
/// `recordedAt` arrives as a JSON number (Int or Double depending on the
/// platform serializer); the failable init accepts both. Rows the parser
/// cannot understand are dropped by callers (`compactMap`), never crashed
/// on — history must survive one malformed row.
struct HistoryItem: Equatable, Identifiable, Codable {
    var id: String { uuid }
    let uuid: String
    let text: String
    let dateLabel: String
    let wordCount: Int
    let durationSecs: Int64
    let recordedAt: Int64
    let discarded: Bool

    init(
        uuid: String,
        text: String,
        dateLabel: String,
        wordCount: Int,
        durationSecs: Int64,
        recordedAt: Int64,
        discarded: Bool
    ) {
        self.uuid = uuid
        self.text = text
        self.dateLabel = dateLabel
        self.wordCount = wordCount
        self.durationSecs = durationSecs
        self.recordedAt = recordedAt
        self.discarded = discarded
    }

    init?(dict: [String: Any]) {
        guard let uuid = dict["uuid"] as? String, uuid.isEmpty == false,
              let text = dict["text"] as? String else {
            return nil
        }
        self.init(
            uuid: uuid,
            text: text,
            dateLabel: dict["dateLabel"] as? String ?? "",
            wordCount: HistoryItem.intValue(dict["wordCount"]) ?? 0,
            durationSecs: HistoryItem.int64Value(dict["durationSecs"]) ?? 0,
            recordedAt: HistoryItem.int64Value(dict["recordedAt"]) ?? 0,
            discarded: dict["discarded"] as? Bool ?? false
        )
    }

    static func intValue(_ raw: Any?) -> Int? {
        if let value = raw as? Int {
            return value
        }
        if let value = raw as? Int64 {
            return Int(value)
        }
        if let value = raw as? Double {
            return Int(value)
        }
        if let value = raw as? NSNumber {
            return value.intValue
        }
        return nil
    }

    static func int64Value(_ raw: Any?) -> Int64? {
        if let value = raw as? Int64 {
            return value
        }
        if let value = raw as? Int {
            return Int64(value)
        }
        if let value = raw as? Double {
            return Int64(value)
        }
        if let value = raw as? NSNumber {
            return value.int64Value
        }
        return nil
    }
}

/// Privacy knobs governing history (`settings.privacy`, core
/// `PrivacySettings` shape). Parsed from the same settings dict the
/// Settings screens already read — no new bridge call.
struct RetentionPolicy: Equatable {
    /// Master switch: `finish_utterance` stores a row only when true and
    /// the transcript is non-blank (core `finalize_delivery`).
    let historyEnabled: Bool
    /// Transcript auto-delete in days; 0 = keep forever (core default).
    let autoDeleteDays: UInt32
    /// Cancelled-midway rows are stored (as textless `discarded` rows) only
    /// when true (core `cancel_utterance`).
    let saveDiscarded: Bool

    static let defaults = RetentionPolicy(historyEnabled: true, autoDeleteDays: 0, saveDiscarded: false)

    static func parse(settings: [String: Any]) -> RetentionPolicy {
        let privacy = settings["privacy"] as? [String: Any] ?? [:]
        let historyEnabled = privacy["history_enabled"] as? Bool ?? true
        let saveDiscarded = privacy["save_discarded_transcriptions"] as? Bool ?? false
        let days: UInt32
        if let raw = privacy["transcript_auto_delete_days"] as? Int, raw > 0 {
            days = UInt32(raw)
        } else if let raw = privacy["transcript_auto_delete_days"] as? UInt32 {
            days = raw
        } else {
            days = 0
        }
        return RetentionPolicy(historyEnabled: historyEnabled, autoDeleteDays: days, saveDiscarded: saveDiscarded)
    }
}

/// Totals over the saved (non-discarded, non-deleted) history. Computed
/// client-side from fetched rows: `capple.rs` (frozen at T1) exposes no
/// `usage_stats` / `daily_aggregates` native (Android has
/// `nativeUsageStats` / `nativeDailyActivity`), so Swift aggregates the
/// `history_json` rows instead. Field semantics match core
/// `store::usage_stats`: zero-duration legacy rows count toward
/// words/dictations but not toward speaking speed.
struct UsageStats: Equatable {
    let wordsSpoken: UInt64
    let dictations: UInt64
    let wordsPerMinute: Double
}

/// One per-day aggregate for the activity list: local-midnight `dayStart`
/// (unix seconds, Android `Day.dayStart` parity), summed `words`, and the
/// dictation `utterances` count. The full heatmap grid is deferred — this
/// is the flat recent-first list the Activity section renders.
struct DailyBucket: Equatable {
    let dayStart: Int64
    let words: Int
    let utterances: Int
}

/// History + retention + usage/activity stats, computed over bridge rows.
///
/// Retention execution needs no Swift call: the core redacts rows with
/// `recorded_at < now - days` on engine open AND inside `finish_utterance`
/// before storing (`engine.rs` open + `finalize_delivery`), so purge on
/// open + after finish is automatic whenever `transcript_auto_delete_days`
/// is set. The pure helpers below mirror that predicate for tests, the
/// file-backed test backend, and UI copy — they never delete core rows.
enum StatsStore {
    // MARK: - Search (pure, no bridge)

    /// Case-insensitive substring match on the transcript. Blank queries
    /// return everything (the list, not an empty state).
    static func filterItems(_ items: [HistoryItem], query: String) -> [HistoryItem] {
        let needle = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard needle.isEmpty == false else {
            return items
        }
        let lower = needle.lowercased()
        return items.filter { $0.text.lowercased().contains(lower) }
    }

    // MARK: - Retention predicate (core `purge_old_history` parity)

    /// Cutoff for `autoDeleteDays`: rows with `recordedAt` strictly before
    /// this are redacted by the core. Nil when `days == 0` (keep forever).
    static func retentionCutoff(days: UInt32, now: Date = Date()) -> Date? {
        guard days > 0 else {
            return nil
        }
        return Calendar.current.date(byAdding: .day, value: -Int(days), to: now)
    }

    /// Rows the core would redact under `days` (strictly older than the
    /// cutoff, mirroring `recorded_at < strftime('%s','now','-N days')`).
    /// Empty when `days == 0`.
    static func expiredItems(_ items: [HistoryItem], days: UInt32, now: Date = Date()) -> [HistoryItem] {
        guard let cutoff = retentionCutoff(days: days, now: now) else {
            return []
        }
        let cutoffSecs = Int64(cutoff.timeIntervalSince1970)
        return items.filter { $0.recordedAt < cutoffSecs }
    }

    // MARK: - Usage stats (pure, no bridge)

    /// Totals over non-discarded rows (core `usage_stats` WHERE clause:
    /// `deleted = 0 AND discarded = 0`). Bridge rows are already
    /// non-deleted; the discarded filter is applied here.
    static func usageStats(_ items: [HistoryItem]) -> UsageStats {
        let kept = items.filter { $0.discarded == false }
        let words = kept.reduce(0) { $0 + max($1.wordCount, 0) }
        let measuredWords = kept
            .filter { $0.durationSecs > 0 }
            .reduce(0) { $0 + max($1.wordCount, 0) }
        let measuredSecs = kept
            .filter { $0.durationSecs > 0 }
            .reduce(Int64(0)) { $0 + $1.durationSecs }
        let wpm: Double
        if measuredSecs > 0 {
            wpm = Double(measuredWords) * 60.0 / Double(measuredSecs)
        } else {
            wpm = 0.0
        }
        return UsageStats(
            wordsSpoken: UInt64(max(words, 0)),
            dictations: UInt64(kept.count),
            wordsPerMinute: wpm
        )
    }

    // MARK: - Daily aggregates (pure, no bridge)

    /// Local-midnight day start (unix seconds) for a timestamp, matching
    /// the core's `… 'localtime'` grouping.
    static func dayStart(for recordedAt: Int64, calendar: Calendar = Calendar.current) -> Int64 {
        let date = Date(timeIntervalSince1970: TimeInterval(recordedAt))
        let start = calendar.startOfDay(for: date)
        return Int64(start.timeIntervalSince1970)
    }

    /// Per-day aggregates over the trailing `days` window, ascending by
    /// day (core `daily_aggregates` order). Discarded rows are excluded;
    /// empty days are omitted (the heatmap grid that needs them is
    /// deferred — callers render this flat list).
    static func dailyAggregates(
        _ items: [HistoryItem],
        days: UInt32,
        now: Date = Date(),
        calendar: Calendar = Calendar.current
    ) -> [DailyBucket] {
        guard days > 0 else {
            return []
        }
        let windowStart = now.addingTimeInterval(-TimeInterval(days) * 86_400)
        var grouped: [Int64: (words: Int, utterances: Int)] = [:]
        for item in items {
            guard item.discarded == false else {
                continue
            }
            let date = Date(timeIntervalSince1970: TimeInterval(item.recordedAt))
            guard date >= windowStart else {
                continue
            }
            let start = dayStart(for: item.recordedAt, calendar: calendar)
            let current = grouped[start] ?? (words: 0, utterances: 0)
            grouped[start] = (words: current.words + max(item.wordCount, 0), utterances: current.utterances + 1)
        }
        return grouped
            .map { DailyBucket(dayStart: $0.key, words: $0.value.words, utterances: $0.value.utterances) }
            .sorted { $0.dayStart < $1.dayStart }
    }

    /// Simple activity list: trailing-window aggregates, recent first.
    static func recentActivity(
        _ items: [HistoryItem],
        days: UInt32 = 30,
        now: Date = Date(),
        calendar: Calendar = Calendar.current
    ) -> [DailyBucket] {
        dailyAggregates(items, days: days, now: now, calendar: calendar).reversed().map { $0 }
    }
}

/// History backend seam: the live app uses the frozen `CoreBridge` API
/// (`history` / `clearHistory` / `deleteHistory` — no new FFI); tests use
/// the file backend against a tmp dir (no core, no device).
protocol HistoryBackend {
    func list(limit: Int32) throws -> [HistoryItem]
    func delete(uuid: String) throws
    /// Returns the core's removed-row count (file backend: rows deleted).
    @discardableResult
    func clear() throws -> Int
}

/// Live backend: thin wrapper over the frozen `CoreBridge` history API.
/// Purge needs no method — the core auto-redacts on open + after finish.
struct BridgeHistoryBackend: HistoryBackend {
    func list(limit: Int32 = 200) throws -> [HistoryItem] {
        let rows = try CoreBridge.history(limit: limit)
        return rows.compactMap { HistoryItem(dict: $0) }
    }

    func delete(uuid: String) throws {
        try CoreBridge.deleteHistory(uuid: uuid)
    }

    @discardableResult
    func clear() throws -> Int {
        try CoreBridge.clearHistory()
    }
}

/// File-backed backend for tests (and previews): one JSON array file in a
/// directory. CRUD round-trips without the core so `HistoryTests` runs on
/// any host. Newest first on read, matching the core's `ORDER BY
/// recorded_at DESC` list order.
struct FileHistoryBackend: HistoryBackend {
    let directory: URL

    private var fileURL: URL {
        directory.appendingPathComponent("history.json")
    }

    func list(limit: Int32 = 200) throws -> [HistoryItem] {
        let items = try readAll()
        let sorted = items.sorted { $0.recordedAt > $1.recordedAt }
        guard limit > 0 else {
            return sorted
        }
        return Array(sorted.prefix(Int(limit)))
    }

    func insert(_ item: HistoryItem) throws {
        var items = try readAll()
        items.removeAll { $0.uuid == item.uuid }
        items.append(item)
        try writeAll(items)
    }

    func delete(uuid: String) throws {
        var items = try readAll()
        items.removeAll { $0.uuid == uuid }
        try writeAll(items)
    }

    @discardableResult
    func clear() throws -> Int {
        let count = try readAll().count
        try writeAll([])
        return count
    }

    /// Test-side retention: drop rows the core would redact under `days`.
    /// Returns the removed count (core `purge_old_history` parity).
    @discardableResult
    func purge(days: UInt32, now: Date = Date()) throws -> Int {
        guard days > 0 else {
            return 0
        }
        let items = try readAll()
        let expired = Set(StatsStore.expiredItems(items, days: days, now: now).map { $0.uuid })
        guard expired.isEmpty == false else {
            return 0
        }
        try writeAll(items.filter { expired.contains($0.uuid) == false })
        return expired.count
    }

    // MARK: - Storage

    private func readAll() throws -> [HistoryItem] {
        guard FileManager.default.fileExists(atPath: fileURL.path) else {
            return []
        }
        let data = try Data(contentsOf: fileURL)
        guard data.isEmpty == false else {
            return []
        }
        return try JSONDecoder().decode([HistoryItem].self, from: data)
    }

    private func writeAll(_ items: [HistoryItem]) throws {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let data = try JSONEncoder().encode(items)
        try data.write(to: fileURL, options: .atomic)
    }
}
