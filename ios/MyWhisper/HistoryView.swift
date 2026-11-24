import SwiftUI

struct ActivityView: View {
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.scenePhase) private var scenePhase
    @StateObject private var model = HistoryViewModel()
    @State private var confirmingClear = false
    @State private var original: HistoryItem?
    @State private var lastCopied: String?
    @ObservedObject var flow: AppFlow
    let keyboardStrings: OnboardingStrings
    let onOpenProviders: () -> Void

    var body: some View {
        List {
            if model.searchText.isEmpty {
                inputSection
                usageSection
            }
            if let message = model.errorMessage {
                Section {
                    Text(message).font(.footnote).foregroundStyle(.secondary)
                    Button("Retry", action: model.refresh)
                } header: {
                    Text("History unavailable")
                }
            }
            historySections
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(WhisperColors.background(colorScheme))
        .navigationTitle(TabStrings.current(languageCode: Locale.current.language.languageCode?.identifier).activity)
        .searchable(text: $model.searchText, prompt: "Search transcripts")
        .toolbar {
            if !model.items.isEmpty {
                ToolbarItem(placement: .topBarTrailing) {
                    Menu {
                        Button("Clear history", systemImage: "trash", role: .destructive) { confirmingClear = true }
                    } label: {
                        Label("History actions", systemImage: "ellipsis.circle")
                    }
                    .disabled(model.isBusy)
                }
            }
        }
        .confirmationDialog("Delete all saved dictations?", isPresented: $confirmingClear, titleVisibility: .visible) {
            Button("Delete everything", role: .destructive, action: model.clearAll)
        }
        .sheet(item: $original) { item in
            NavigationStack {
                ScrollView {
                    Text(item.rawText ?? item.text)
                        .textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding()
                }
                .navigationTitle("Original transcription")
                .navigationBarTitleDisplayMode(.inline)
                .toolbar {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(keyboardStrings.done) { original = nil }
                    }
                }
            }
        }
        .onAppear(perform: model.refresh)
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { model.refresh() }
        }
        .onChange(of: flow.showRecorder) { _, showing in
            if !showing { model.refresh() }
        }
        .sensoryFeedback(.success, trigger: lastCopied)
    }

    private var inputSection: some View {
        Section {
            NavigationLink {
                KeyboardSetupView(strings: keyboardStrings)
            } label: {
                HStack(spacing: 12) {
                    Image(systemName: "keyboard")
                        .font(.title3.weight(.medium))
                        .foregroundStyle(WhisperColors.brandCyan)
                        .frame(width: 44, height: 44)
                        .background(WhisperColors.brandNavy, in: RoundedRectangle(cornerRadius: 12))
                        .accessibilityHidden(true)
                    VStack(alignment: .leading, spacing: 3) {
                        Text(keyboardStrings.keyboardTitle).font(.headline)
                        Text(keyboardStrings.keyboardSummary)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                    }
                }
                .padding(.vertical, 6)
            }
            if let error = flow.coreError {
                VStack(alignment: .leading, spacing: 6) {
                    Text("Couldn't start the speech engine.")
                    Text(error).font(.footnote).foregroundStyle(.secondary).textSelection(.enabled)
                    Button("Retry", action: flow.openCore)
                }
            } else if !isServerConfigured(settings: flow.settings) {
                Button(action: onOpenProviders) {
                    Label("Set up speech provider", systemImage: "waveform")
                }
            } else if !flow.micGranted {
                Button(action: flow.requestMic) {
                    Label("Enable microphone", systemImage: "mic")
                }
            } else {
                Button { flow.showRecorder = true } label: {
                    Label(keyboardStrings.dictate, systemImage: "mic")
                }
            }
        }
    }

    private var usageSection: some View {
        Section {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 0) {
                    usageMetric("Dictations", model.stats?.dictations.formatted() ?? "0")
                    Divider()
                    usageMetric("Words spoken", model.stats?.wordsSpoken.formatted() ?? "0")
                    Divider()
                    usageMetric("Words per minute", (model.stats?.wordsPerMinute ?? 0).formatted(.number.precision(.fractionLength(0))))
                }
                VStack(alignment: .leading) {
                    LabeledContent("Dictations", value: model.stats?.dictations.formatted() ?? "0")
                    LabeledContent("Words spoken", value: model.stats?.wordsSpoken.formatted() ?? "0")
                    LabeledContent("Words per minute", value: (model.stats?.wordsPerMinute ?? 0).formatted(.number.precision(.fractionLength(0))))
                }
            }
            .padding(.vertical, 4)

            if !model.activity.isEmpty {
                SpeakingActivityView(activity: model.activity)
                    .padding(.vertical, 4)
            }
        } header: {
            Text("Usage")
        } footer: {
            Text("Based on your most recent saved dictations.")
        }
    }

    private func usageMetric(_ title: String, _ value: String) -> some View {
        VStack(spacing: 4) {
            Text(value)
                .font(.title2.weight(.semibold).monospacedDigit())
                .foregroundStyle(WhisperColors.primary(colorScheme))
            Text(title).font(.caption).foregroundStyle(.secondary)
        }
        .fixedSize(horizontal: true, vertical: false)
        .frame(maxWidth: .infinity)
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private var historySections: some View {
        if model.visibleItems.isEmpty && model.errorMessage == nil {
            Section("History") {
                if model.isBusy {
                    ProgressView()
                } else {
                    Text(model.searchText.isEmpty ? "No saved dictations yet." : "No matches.")
                        .foregroundStyle(.secondary)
                }
            }
        }
        ForEach(model.days) { day in
            Section(day.title) {
                ForEach(day.items) { item in
                    historyRow(item)
                }
            }
        }
        if model.searchText.isEmpty && !model.retentionFooter.isEmpty {
            Section {
                Text(model.retentionFooter)
                    .font(.footnote)
                    .foregroundStyle(.secondary)
                    .listRowBackground(Color.clear)
            }
        }
    }

    private func historyRow(_ item: HistoryItem) -> some View {
        HStack(alignment: .top, spacing: 8) {
            Button {
                if Delivery.copyToClipboard(item.text) { lastCopied = item.id }
            } label: {
                VStack(alignment: .leading, spacing: 5) {
                    Text(item.discarded ? "Discarded recording" : item.text)
                        .foregroundStyle(.primary)
                        .lineLimit(3)
                    Text(lastCopied == item.id ? "Copied" : subtitle(for: item))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .contentShape(Rectangle())
            }
            .disabled(item.text.isEmpty)
            .accessibilityHint("Copies the transcript")
            if item.rawText?.isEmpty == false {
                Button {
                    original = item
                } label: {
                    Label("Original transcription", systemImage: "doc.text.magnifyingglass")
                        .labelStyle(.iconOnly)
                        .frame(width: 44, height: 44)
                }
            }
        }
        .buttonStyle(.borderless)
        .contextMenu {
            if item.rawText?.isEmpty == false {
                Button("Original transcription", systemImage: "doc.text.magnifyingglass") { original = item }
            }
            if !item.text.isEmpty {
                Button("Copy", systemImage: "doc.on.doc") {
                    if Delivery.copyToClipboard(item.text) { lastCopied = item.id }
                }
            }
        }
        .swipeActions {
            Button("Delete", systemImage: "trash", role: .destructive) { model.deleteItem(item) }
                .disabled(model.isBusy)
        }
    }

    private func subtitle(for item: HistoryItem) -> String {
        let words = item.wordCount == 1 ? "1 word" : "\(item.wordCount) words"
        guard item.recordedAt > 0 else { return item.dateLabel.isEmpty ? words : item.dateLabel }
        let date = Date(timeIntervalSince1970: TimeInterval(item.recordedAt))
        return "\(date.formatted(date: .omitted, time: .shortened)) · \(words)"
    }
}

private struct SpeakingActivityView: View {
    @Environment(\.colorScheme) private var colorScheme
    let activity: [DailyBucket]

    var body: some View {
        let calendar = Calendar.current
        let today = calendar.startOfDay(for: .now)
        let counts = Dictionary(uniqueKeysWithValues: activity.map { ($0.dayStart, $0.utterances) })
        let recentDays = (0..<84).reversed().compactMap { calendar.date(byAdding: .day, value: -$0, to: today) }
        let values = recentDays.map { counts[Int64($0.timeIntervalSince1970), default: 0] }

        VStack(alignment: .leading, spacing: 9) {
            Text("Speaking activity").font(.subheadline.weight(.semibold))
            LazyHGrid(rows: Array(repeating: GridItem(.fixed(10), spacing: 3), count: 7), spacing: 3) {
                ForEach(values.indices, id: \.self) { index in
                    let count = values[index]
                    RoundedRectangle(cornerRadius: 2)
                        .fill(color(for: count))
                        .frame(width: 10, height: 10)
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Speaking activity in the last 12 weeks")
        .accessibilityValue("\(values.reduce(0, +)) saved dictations")
    }

    private func color(for count: Int) -> Color {
        if count == 0 { return WhisperColors.onSurfaceVariant(colorScheme).opacity(0.14) }
        return WhisperColors.primary(colorScheme).opacity(0.3 + Double(min(count, 4)) * 0.17)
    }
}

struct HistoryDay: Identifiable {
    let id: Date?
    let items: [HistoryItem]

    var title: String {
        guard let id else { return "Unknown date" }
        if Calendar.current.isDateInToday(id) { return "Today" }
        if Calendar.current.isDateInYesterday(id) { return "Yesterday" }
        return id.formatted(date: .abbreviated, time: .omitted)
    }

    static func group(_ items: [HistoryItem], calendar: Calendar = .current) -> [HistoryDay] {
        let groups = Dictionary(grouping: items) { item -> Date? in
            guard item.recordedAt > 0 else { return nil }
            return calendar.startOfDay(for: Date(timeIntervalSince1970: TimeInterval(item.recordedAt)))
        }
        return groups.keys.sorted { ($0 ?? .distantPast) > ($1 ?? .distantPast) }.map { day in
            HistoryDay(id: day, items: groups[day, default: []])
        }
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
    @Published private(set) var days: [HistoryDay] = []
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
        activity = StatsStore.recentActivity(committed, days: 84)
        retentionFooter = Self.footer(policy: policy)
        applyFilter()
        isBusy = false
        errorMessage = nil
    }

    private func applyFilter() {
        visibleItems = StatsStore.filterItems(items, query: searchText)
        days = HistoryDay.group(visibleItems)
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

    static func describe(_ error: Error) -> String {
        if let core = error as? CoreError {
            return String(describing: core)
        }
        return error.localizedDescription
    }
}
