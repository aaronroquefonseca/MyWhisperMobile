import XCTest
@testable import MyWhisper

/// History + retention + usage/activity stats tests.
///
/// Pure unit: the file backend stands in for the core (no FFI, no device —
/// `CoreBridge` history/clear/delete are thin wrappers exercised on-device,
/// and retention execution lives in the core itself: purge on open + after
/// finish is automatic whenever `transcript_auto_delete_days` is set).
/// These tests pin the Swift-side contract: the retention predicate, row
/// parsing, search, client-side stats/aggregates, and the tmp-dir CRUD
/// round-trip (including the 1-day purge case QA repeats on-device).
final class HistoryTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_700_000_000)

    private var utcCalendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0) ?? calendar.timeZone
        return calendar
    }

    private func item(
        uuid: String,
        text: String = "hello world",
        words: Int = 2,
        duration: Int64 = 60,
        recordedAt: Int64,
        discarded: Bool = false
    ) -> HistoryItem {
        HistoryItem(
            uuid: uuid, text: text, dateLabel: "label",
            wordCount: words, durationSecs: duration,
            recordedAt: recordedAt, discarded: discarded
        )
    }

    private func tmpDir() throws -> URL {
        let dir = FileManager.default.temporaryDirectory
            .appendingPathComponent("HistoryTests-\(UUID().uuidString)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        return dir
    }

    // MARK: - Retention predicate (core `purge_old_history` parity)

    func testRetentionCutoffNilWhenKeepForever() {
        XCTAssertNil(StatsStore.retentionCutoff(days: 0, now: now))
    }

    func testExpiredItemsEmptyWhenKeepForever() {
        let ancient = item(uuid: "a", recordedAt: 100)
        XCTAssertTrue(StatsStore.expiredItems([ancient], days: 0, now: now).isEmpty)
    }

    func testExpiredItemsStrictlyOlderThanCutoff() throws {
        let cutoff = try XCTUnwrap(StatsStore.retentionCutoff(days: 30, now: now))
        let cutoffSecs = Int64(cutoff.timeIntervalSince1970)
        let old = item(uuid: "old", recordedAt: cutoffSecs - 1)
        let boundary = item(uuid: "boundary", recordedAt: cutoffSecs)
        let fresh = item(uuid: "fresh", recordedAt: cutoffSecs + 1)
        let expired = StatsStore.expiredItems([old, boundary, fresh], days: 30, now: now)
        XCTAssertEqual(expired.map { $0.uuid }, ["old"])
    }

    func testRetentionPolicyParsesPrivacySettings() {
        let settings: [String: Any] = [
            "privacy": [
                "history_enabled": false,
                "transcript_auto_delete_days": 7,
                "save_discarded_transcriptions": true
            ] as [String: Any]
        ]
        let policy = RetentionPolicy.parse(settings: settings)
        XCTAssertEqual(policy.historyEnabled, false)
        XCTAssertEqual(policy.autoDeleteDays, 7)
        XCTAssertEqual(policy.saveDiscarded, true)
    }

    func testRetentionPolicyDefaults() {
        let policy = RetentionPolicy.parse(settings: [:])
        XCTAssertEqual(policy, .defaults)
        XCTAssertEqual(policy.historyEnabled, true)
        XCTAssertEqual(policy.autoDeleteDays, 0)
        XCTAssertEqual(policy.saveDiscarded, false)
    }

    // MARK: - Row parsing (core `history_json` shape)

    func testHistoryItemParsesCoreShape() {
        let dict: [String: Any] = [
            "uuid": "u-1", "text": "hi there", "rawText": "hi their",
            "dateLabel": "Today", "wordCount": 2, "durationSecs": 30,
            "recordedAt": 1_700_000_000, "discarded": false
        ]
        let parsed = HistoryItem(dict: dict)
        XCTAssertEqual(parsed?.uuid, "u-1")
        XCTAssertEqual(parsed?.text, "hi there")
        XCTAssertEqual(parsed?.rawText, "hi their")
        XCTAssertEqual(parsed?.wordCount, 2)
        XCTAssertEqual(parsed?.durationSecs, 30)
        XCTAssertEqual(parsed?.recordedAt, 1_700_000_000)
        XCTAssertEqual(parsed?.discarded, false)
    }

    func testHistoryItemAcceptsDoubleRecordedAt() {
        let dict: [String: Any] = [
            "uuid": "u-2", "text": "hi",
            "wordCount": 1.0, "durationSecs": 5.0, "recordedAt": 1_700_000_000.0
        ]
        let parsed = HistoryItem(dict: dict)
        XCTAssertEqual(parsed?.wordCount, 1)
        XCTAssertEqual(parsed?.recordedAt, 1_700_000_000)
        XCTAssertEqual(parsed?.dateLabel, "")
        XCTAssertNil(parsed?.rawText)
        XCTAssertEqual(parsed?.discarded, false)
    }

    func testHistoryGroupsMostRecentLocalDayFirstAndKeepsLegacyRows() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 3_600)!
        let items = [
            item(uuid: "recent", recordedAt: 86_400),
            item(uuid: "same-day", recordedAt: 86_399),
            item(uuid: "older", recordedAt: 82_799),
            item(uuid: "legacy", recordedAt: 0)
        ]

        let groups = HistoryDay.group(items, calendar: calendar)
        XCTAssertEqual(groups.map { $0.items.map(\.uuid) }, [["recent", "same-day"], ["older"], ["legacy"]])
        XCTAssertNil(groups.last?.id)
    }

    func testHistoryItemRejectsMissingIdentity() {
        XCTAssertNil(HistoryItem(dict: ["text": "hi"]))
        XCTAssertNil(HistoryItem(dict: ["uuid": "u-3"]))
        XCTAssertNil(HistoryItem(dict: ["uuid": "", "text": "hi"]))
    }

    // MARK: - Search

    func testSearchBlankReturnsAll() {
        let rows = [item(uuid: "a", text: "alpha", recordedAt: 100), item(uuid: "b", text: "beta", recordedAt: 200)]
        XCTAssertEqual(StatsStore.filterItems(rows, query: "   ").count, 2)
    }

    func testSearchIsCaseInsensitiveSubstring() {
        let rows = [
            item(uuid: "a", text: "Buy milk", recordedAt: 100),
            item(uuid: "b", text: "Call ALICE", recordedAt: 200),
            item(uuid: "c", text: "No match here", recordedAt: 300)
        ]
        XCTAssertEqual(StatsStore.filterItems(rows, query: "ali").map { $0.uuid }, ["b"])
        XCTAssertEqual(StatsStore.filterItems(rows, query: "MILK").map { $0.uuid }, ["a"])
        XCTAssertTrue(StatsStore.filterItems(rows, query: "zzz").isEmpty)
    }

    func testSearchMatchesOriginalTranscription() {
        let row = HistoryItem(uuid: "a", text: "Alice", rawText: "all is", dateLabel: "", wordCount: 1,
                              durationSecs: 5, recordedAt: 200, discarded: false)
        XCTAssertEqual(StatsStore.filterItems([row], query: "ALL IS"), [row])
    }

    func testStreakCountsLocalDaysThroughYesterdayAndIgnoresDiscarded() {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 3_600)!
        let rows = [
            item(uuid: "today-discarded", recordedAt: 345_600, discarded: true),
            item(uuid: "yesterday", recordedAt: 342_000 - 1),
            item(uuid: "yesterday-duplicate", recordedAt: 300_000),
            item(uuid: "day-before", recordedAt: 255_600 - 1),
            item(uuid: "gap", recordedAt: 82_800 - 1)
        ]

        let today = Date(timeIntervalSince1970: 345_600)
        let tomorrow = Date(timeIntervalSince1970: 432_000)
        XCTAssertEqual(StatsStore.usageStats(rows, now: today, calendar: calendar).streakDays, 2)
        XCTAssertEqual(StatsStore.usageStats(rows, now: tomorrow, calendar: calendar).streakDays, 0)
    }

    // MARK: - Usage stats (core `usage_stats` semantics)

    func testUsageStatsEmpty() {
        let stats = StatsStore.usageStats([])
        XCTAssertEqual(stats.wordsSpoken, 0)
        XCTAssertEqual(stats.dictations, 0)
        XCTAssertEqual(stats.wordsPerMinute, 0.0)
    }

    func testUsageStatsTotalsAndWpm() {
        let rows = [
            item(uuid: "a", words: 60, duration: 60, recordedAt: 100),
            item(uuid: "b", words: 60, duration: 60, recordedAt: 200)
        ]
        let stats = StatsStore.usageStats(rows)
        XCTAssertEqual(stats.wordsSpoken, 120)
        XCTAssertEqual(stats.dictations, 2)
        XCTAssertEqual(stats.wordsPerMinute, 60.0, accuracy: 0.001)
    }

    func testUsageStatsSkipsDiscardedAndUnmeasuredWpm() {
        let rows = [
            item(uuid: "kept", words: 60, duration: 60, recordedAt: 100),
            item(uuid: "legacy", words: 30, duration: 0, recordedAt: 200),
            item(uuid: "cancelled", words: 0, duration: 45, recordedAt: 300, discarded: true)
        ]
        let stats = StatsStore.usageStats(rows)
        XCTAssertEqual(stats.wordsSpoken, 90, "zero-duration legacy rows still count words")
        XCTAssertEqual(stats.dictations, 2, "discarded rows never count")
        XCTAssertEqual(stats.wordsPerMinute, 60.0, accuracy: 0.001, "only duration > 0 rows set speed")
    }

    // MARK: - Daily aggregates (core `daily_aggregates` semantics)

    func testDailyAggregatesGroupsByLocalDayAscending() {
        let calendar = utcCalendar
        let dayOne = Int64(1_700_000_000)
        let sameDay = dayOne + 3_600
        let nextDay = dayOne + 86_400 + 3_600
        let rows = [
            item(uuid: "a", words: 10, recordedAt: dayOne),
            item(uuid: "b", words: 5, recordedAt: sameDay),
            item(uuid: "c", words: 7, recordedAt: nextDay)
        ]
        let buckets = StatsStore.dailyAggregates(rows, days: 30, now: now, calendar: calendar)
        XCTAssertEqual(buckets.count, 2)
        XCTAssertEqual(buckets[0].utterances, 2)
        XCTAssertEqual(buckets[0].words, 15)
        XCTAssertEqual(buckets[1].utterances, 1)
        XCTAssertLessThan(buckets[0].dayStart, buckets[1].dayStart)
    }

    func testDailyAggregatesExcludesOldAndDiscarded() {
        let calendar = utcCalendar
        let old = Int64(now.timeIntervalSince1970) - Int64(40 * 86_400)
        let rows = [
            item(uuid: "old", words: 100, recordedAt: old),
            item(uuid: "dropped", words: 50, recordedAt: Int64(now.timeIntervalSince1970), discarded: true),
            item(uuid: "fresh", words: 9, recordedAt: Int64(now.timeIntervalSince1970))
        ]
        let buckets = StatsStore.dailyAggregates(rows, days: 30, now: now, calendar: calendar)
        XCTAssertEqual(buckets.count, 1)
        XCTAssertEqual(buckets[0].words, 9)
        XCTAssertEqual(buckets[0].utterances, 1)
    }

    func testRecentActivityIsRecentFirst() {
        let calendar = utcCalendar
        let base = Int64(1_700_000_000)
        let rows = [
            item(uuid: "a", recordedAt: base),
            item(uuid: "b", recordedAt: base + 86_400)
        ]
        let activity = StatsStore.recentActivity(rows, days: 30, now: now, calendar: calendar)
        XCTAssertEqual(activity.count, 2)
        XCTAssertGreaterThan(activity[0].dayStart, activity[1].dayStart)
    }

    // MARK: - File backend CRUD round-trip (tmp dir)

    func testFileBackendCrudRoundTrip() throws {
        let backend = FileHistoryBackend(directory: try tmpDir())
        XCTAssertTrue(try backend.list(limit: 200).isEmpty)

        let older = item(uuid: "older", text: "first", recordedAt: 1_700_000_000)
        let newer = item(uuid: "newer", text: "second", recordedAt: 1_700_001_000)
        try backend.insert(older)
        try backend.insert(newer)

        let listed = try backend.list(limit: 200)
        XCTAssertEqual(listed.map { $0.uuid }, ["newer", "older"], "newest first like the core")
        XCTAssertEqual(try backend.list(limit: 1).map { $0.uuid }, ["newer"])

        try backend.delete(uuid: "newer")
        XCTAssertEqual(try backend.list(limit: 200).map { $0.uuid }, ["older"])

        XCTAssertEqual(try backend.clear(), 1)
        XCTAssertTrue(try backend.list(limit: 200).isEmpty)
        XCTAssertEqual(try backend.clear(), 0)
    }

    func testFileBackendPurgeOneDayRetention() throws {
        let backend = FileHistoryBackend(directory: try tmpDir())
        let dayAgo = Int64(now.timeIntervalSince1970) - 86_400
        let old = item(uuid: "old", recordedAt: dayAgo - 60)
        let boundary = item(uuid: "boundary", recordedAt: dayAgo)
        let fresh = item(uuid: "fresh", recordedAt: dayAgo + 60)
        try backend.insert(old)
        try backend.insert(boundary)
        try backend.insert(fresh)

        let removed = try backend.purge(days: 1, now: now)
        XCTAssertEqual(removed, 1)
        XCTAssertEqual(Set(try backend.list(limit: 200).map { $0.uuid }), ["boundary", "fresh"])
    }

    func testFileBackendPurgeKeepForever() throws {
        let backend = FileHistoryBackend(directory: try tmpDir())
        try backend.insert(item(uuid: "a", recordedAt: 100))
        XCTAssertEqual(try backend.purge(days: 0, now: now), 0)
        XCTAssertEqual(try backend.list(limit: 200).count, 1)
    }

    // MARK: - Footer copy

    func testFooterDefaults() {
        XCTAssertEqual(
            HistoryViewModel.footer(policy: .defaults),
            "Transcripts stay on this device until deleted."
        )
    }

    func testFooterReportsPolicy() {
        let policy = RetentionPolicy(historyEnabled: false, autoDeleteDays: 7, saveDiscarded: true)
        let footer = HistoryViewModel.footer(policy: policy)
        XCTAssertTrue(footer.contains("History is off"))
        XCTAssertTrue(footer.contains("7 days"))
        XCTAssertTrue(footer.contains("Cancelled recordings are kept"))
    }
}
