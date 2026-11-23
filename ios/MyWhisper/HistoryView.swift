import SwiftUI

/// History + usage/activity screen.
///
/// - List/search/delete/clear go through `HistoryBackend` (live: the
///   frozen `CoreBridge` history API — `history` / `clearHistory` /
///   `deleteHistory`; no new FFI).
/// - Retention needs no Swift purge call: the core redacts rows with
///   `recorded_at < now - transcript_auto_delete_days` on engine open AND
///   inside `finish_utterance` before storing (`engine.rs` open +
///   `finalize_delivery`), so purge on open + after finish is automatic.
///   The footer below reports the active policy for transparency.
/// - Stats (`UsageStats` + daily activity list) are aggregated client-side
///   from fetched rows: `capple.rs` (frozen at T1) exposes no
///   `usage_stats` / `daily_aggregates` native (Android has
///   `nativeUsageStats` / `nativeDailyActivity`). The heatmap grid is
///   deferred — this renders the flat recent-first activity list.
struct HistoryView: View {
    @StateObject private var model: HistoryViewModel
    @State private var confirmingClear = false

    init(backend: HistoryBackend = BridgeHistoryBackend()) {
        _model = StateObject(wrappedValue: HistoryViewModel(backend: backend))
    }

    var body: some View {
        NavigationStack {
            List {
                if let message = model.errorMessage {
                    Section {
                        Text(message)
                            .foregroundStyle(.red)
                        Button("Retry") {
                            model.refresh()
                        }
                        .buttonStyle(.borderedProminent)
                    } header: {
                        Text("Load failed")
                    }
                }
                Section {
                    statsRow(title: "Dictations", value: "\(model.stats?.dictations ?? 0)")
                    statsRow(title: "Words spoken", value: "\(model.stats?.wordsSpoken ?? 0)")
                    statsRow(
                        title: "Words per minute",
                        value: String(format: "%.1f", model.stats?.wordsPerMinute ?? 0.0)
                    )
                } header: {
                    Text("Usage")
                } footer: {
                    Text("Saved dictations only — deleting history updates these totals.")
                }
                if model.activity.isEmpty == false {
                    Section("Recent activity") {
                        ForEach(model.activity, id: \.dayStart) { bucket in
                            LabeledContent(
                                HistoryViewModel.dayLabel(for: bucket),
                                value: "\(bucket.utterances) × · \(bucket.words) words"
                            )
                        }
                    }
                }
                Section {
                    if model.visibleItems.isEmpty {
                        Text(model.searchText.isEmpty ? "No saved dictations yet." : "No matches.")
                            .foregroundStyle(.secondary)
                    } else {
                        ForEach(model.visibleItems) { item in
                            VStack(alignment: .leading, spacing: 4) {
                                Text(item.text)
                                    .lineLimit(3)
                                Text(rowSubtitle(for: item))
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                        }
                        .onDelete(perform: model.deleteItems)
                    }
                } header: {
                    Text("History (\(model.visibleItems.count))")
                } footer: {
                    Text(model.retentionFooter)
                }
            }
            .navigationTitle("History")
            .searchable(text: $model.searchText, prompt: "Search transcripts")
            .toolbar {
                ToolbarItem(placement: .navigationBarLeading) {
                    Button("Refresh") {
                        model.refresh()
                    }
                    .disabled(model.isBusy)
                }
                ToolbarItem(placement: .navigationBarTrailing) {
                    Button("Clear", role: .destructive) {
                        confirmingClear = true
                    }
                    .disabled(model.items.isEmpty || model.isBusy)
                }
            }
            .confirmationDialog(
                "Delete all saved dictations?",
                isPresented: $confirmingClear,
                titleVisibility: .visible
            ) {
                Button("Delete everything", role: .destructive) {
                    model.clearAll()
                }
                Button("Cancel", role: .cancel) {}
            }
            .overlay {
                if model.isBusy {
                    ProgressView()
                }
            }
            .onAppear {
                model.refresh()
            }
        }
    }

    private func statsRow(title: String, value: String) -> some View {
        LabeledContent(title, value: value)
    }

    private func rowSubtitle(for item: HistoryItem) -> String {
        if item.dateLabel.isEmpty {
            return "\(item.wordCount) words"
        }
        return "\(item.dateLabel) · \(item.wordCount) words"
    }
}

/// History view model: bridge I/O off the main thread, commits on it.
///
/// Generation-guarded like the Android `histGen` jobs: every load or
/// destructive op claims a generation before running and commits only
/// when still current, so a slow worker never clobbers a newer committed
/// state. Failures keep an explicit retryable error (`errorMessage` +
/// `refresh`), mirroring the Android history jobs.
@MainActor
final class HistoryViewModel: ObservableObject {
    @Published private(set) var items: [HistoryItem] = []
    @Published private(set) var visibleItems: [HistoryItem] = []
    @Published private(set) var stats: UsageStats?
    @Published private(set) var activity: [DailyBucket] = []
    @Published private(set) var isBusy = false
    @Published private(set) var errorMessage: String?
    @Published private(set) var retentionFooter = ""
    @Published var searchText = "" {
        didSet {
            applyFilter()
        }
    }

    private let backend: HistoryBackend
    private let workers = DispatchQueue(label: "com.mywhisper.history", qos: .userInitiated)
    private var screenGen: Int64 = 0

    init(backend: HistoryBackend = BridgeHistoryBackend()) {
        self.backend = backend
    }

    /// Reload the list (and derived stats/activity) from the committed
    /// store state. Main-thread entry (SwiftUI actions); bridge I/O runs
    /// on the worker queue and commits back on main.
    func refresh() {
        let gen = CoreBridge.SettingsJobs.nextJobGen(screenGen)
        screenGen = gen
        isBusy = true
        errorMessage = nil
        workers.async { [weak self] in
            guard let strongSelf = self else {
                return
            }
            do {
                let committed = try strongSelf.backend.list(limit: 200)
                let policy = HistoryViewModel.loadPolicy()
                DispatchQueue.main.async { [weak self] in
                    guard let inner = self,
                          CoreBridge.SettingsJobs.jobApplies(currentGen: inner.screenGen, jobGen: gen) else {
                        return
                    }
                    inner.commit(committed, policy: policy)
                }
            } catch {
                DispatchQueue.main.async { [weak self] in
                    guard let inner = self,
                          CoreBridge.SettingsJobs.jobApplies(currentGen: inner.screenGen, jobGen: gen) else {
                        return
                    }
                    inner.isBusy = false
                    inner.errorMessage = HistoryViewModel.describe(error)
                }
            }
        }
    }

    func deleteItems(at offsets: IndexSet) {
        let backend = backend
        let uuids = offsets.map { visibleItems[$0].uuid }
        runMutation {
            for uuid in uuids {
                try backend.delete(uuid: uuid)
            }
        }
    }

    func deleteItem(_ item: HistoryItem) {
        let backend = backend
        let uuid = item.uuid
        runMutation { try backend.delete(uuid: uuid) }
    }

    func clearAll() {
        let backend = backend
        runMutation { _ = try backend.clear() }
    }

    // MARK: - Internals

    private func runMutation(_ work: @escaping () throws -> Void) {
        let gen = CoreBridge.SettingsJobs.nextJobGen(screenGen)
        screenGen = gen
        isBusy = true
        errorMessage = nil
        workers.async { [weak self] in
            guard let strongSelf = self else {
                return
            }
            do {
                try work()
                let committed = try strongSelf.backend.list(limit: 200)
                let policy = HistoryViewModel.loadPolicy()
                DispatchQueue.main.async { [weak self] in
                    guard let inner = self,
                          CoreBridge.SettingsJobs.jobApplies(currentGen: inner.screenGen, jobGen: gen) else {
                        return
                    }
                    inner.commit(committed, policy: policy)
                }
            } catch {
                DispatchQueue.main.async { [weak self] in
                    guard let inner = self,
                          CoreBridge.SettingsJobs.jobApplies(currentGen: inner.screenGen, jobGen: gen) else {
                        return
                    }
                    inner.isBusy = false
                    inner.errorMessage = HistoryViewModel.describe(error)
                }
            }
        }
    }

    private func commit(_ committed: [HistoryItem], policy: RetentionPolicy) {
        items = committed
        stats = StatsStore.usageStats(committed)
        activity = StatsStore.recentActivity(committed)
        retentionFooter = Self.footer(policy: policy)
        applyFilter()
        isBusy = false
        errorMessage = nil
    }

    private func applyFilter() {
        visibleItems = StatsStore.filterItems(items, query: searchText)
    }

    private static func loadPolicy() -> RetentionPolicy {
        guard let settings = try? CoreBridge.getSettings() else {
            return .defaults
        }
        return RetentionPolicy.parse(settings: settings)
    }

    nonisolated static func footer(policy: RetentionPolicy) -> String {
        var parts: [String] = []
        if policy.historyEnabled == false {
            parts.append("History is off — new dictations are not stored.")
        }
        if policy.autoDeleteDays > 0 {
            parts.append("Auto-deletes transcripts older than \(policy.autoDeleteDays) days (purged on open + after each dictation).")
        }
        if policy.saveDiscarded {
            parts.append("Cancelled recordings are kept as discarded rows.")
        }
        if parts.isEmpty {
            return "Transcripts stay on this device until deleted."
        }
        return parts.joined(separator: " ")
    }

    static func dayLabel(for bucket: DailyBucket) -> String {
        let date = Date(timeIntervalSince1970: TimeInterval(bucket.dayStart))
        let formatter = DateFormatter()
        formatter.dateStyle = .medium
        formatter.timeStyle = .none
        return formatter.string(from: date)
    }

    static func describe(_ error: Error) -> String {
        if let core = error as? CoreError {
            return String(describing: core)
        }
        return error.localizedDescription
    }
}
