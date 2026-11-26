import SwiftUI

struct ActivityView: View {
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.dynamicTypeSize) private var typeSize
    @StateObject private var model = HistoryViewModel()
    @State private var confirmingClear = false
    @State private var expandedOriginalID: String?
    @State private var lastCopied: String?
    @State private var privacyOpen = false
    @State private var autoDeleteDays = "0"
    @State private var privacyMessage: String?
    @ObservedObject var flow: AppFlow
    let keyboardStrings: OnboardingStrings
    let onOpenProviders: () -> Void

    var body: some View {
        List {
            inputSection
            usageSection
            privacySection
            historyHeader
            if let message = model.errorMessage {
                Section {
                    Text(message).font(.footnote).foregroundStyle(.secondary)
                    Button("Retry", action: model.refresh)
                } header: {
                    WhisperSectionHeading("History unavailable")
                }
                .whisperRowSurface()
            }
            historySections
        }
        .whisperListStyle()
        .confirmationDialog("Delete all saved dictations?", isPresented: $confirmingClear, titleVisibility: .visible) {
            Button("Delete everything", role: .destructive, action: model.clearAll)
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
                KeyboardSetupView(strings: keyboardStrings, flow: flow)
            } label: {
                WhisperDestinationLabel(
                    title: keyboardStrings.keyboardTitle,
                    summary: keyboardStrings.keyboardSummary,
                    symbol: "keyboard"
                )
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
        .whisperRowSurface()
    }

    private var usageSection: some View {
        Section {
            VStack(spacing: 8) {
                let columns = Array(repeating: GridItem(.flexible(), spacing: 8), count: typeSize.isAccessibilitySize ? 1 : 2)
                let stats = model.stats
                LazyVGrid(columns: columns, spacing: 8) {
                    usageMetric("Words spoken", Self.compact(stats?.wordsSpoken ?? 0), caption: "Saved history")
                    usageMetric("Words per minute", stats.flatMap {
                        $0.wordsPerMinute > 0 ? $0.wordsPerMinute.formatted(.number.precision(.fractionLength(0))) : nil
                    } ?? "–", caption: "Dictations with a recorded duration")
                    usageMetric("Dictations", (stats?.dictations ?? 0).formatted(), caption: "Saved history")
                    let streak = stats?.streakDays ?? 0
                    usageMetric("Current streak", streak == 1 ? String(localized: "1 day") : String(localized: "\(streak) days"),
                                caption: "Consecutive active days")
                }

                SpeakingActivityView(activity: model.activity)
                    .padding(16)
                    .background(WhisperColors.surface(colorScheme), in: RoundedRectangle(cornerRadius: 16))
            }
            .listRowInsets(EdgeInsets())
            .listRowBackground(Color.clear)
            .listRowSeparator(.hidden)
        } header: {
            WhisperSectionHeading("Activity")
        } footer: {
            Text("Your saved dictations, calculated on this device. Deleting history also updates these totals.")
        }
    }

    /// 1.5K style, like Android, so large totals fit a half-width card.
    static func compact(_ value: UInt64) -> String {
        guard value >= 1_000 else { return value.formatted() }
        let thousands = Double(value) / 1_000
        return thousands >= 100 ? "\(Int(thousands))K" : thousands.formatted(.number.precision(.fractionLength(1))) + "K"
    }

    private func usageMetric(_ title: LocalizedStringKey, _ value: String, caption: LocalizedStringKey) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title).font(.subheadline).foregroundStyle(.secondary)
            Text(value)
                .font(.title2.weight(.semibold).monospacedDigit())
                .foregroundStyle(WhisperColors.primary(colorScheme))
            Text(caption).font(.caption).foregroundStyle(.secondary).fixedSize(horizontal: false, vertical: true)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
        .padding(12)
        .background(WhisperColors.surface(colorScheme), in: RoundedRectangle(cornerRadius: 16))
        .accessibilityElement(children: .combine)
    }

    private var privacySection: some View {
        Section {
            DisclosureGroup(isExpanded: $privacyOpen) {
                Toggle(isOn: privacyBinding("history_enabled", default: true)) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Keep history")
                        Text("Off = dictate without storing.").font(.caption).foregroundStyle(.secondary)
                    }
                }
                Toggle(isOn: privacyBinding("save_discarded_transcriptions", default: false)) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Keep cancelled dictations")
                        Text("Store rows for cancelled recordings.").font(.caption).foregroundStyle(.secondary)
                    }
                }
                LabeledContent("Auto-delete after days (0 = keep)") {
                    TextField("0", text: $autoDeleteDays)
                        .keyboardType(.numberPad)
                        .multilineTextAlignment(.trailing)
                        .frame(maxWidth: 72)
                        .onSubmit(saveAutoDelete)
                }
                Button("Save", action: saveAutoDelete)
                Button("Delete older now") {
                    let days = Int(autoDeleteDays) ?? 0
                    guard days > 0 else {
                        privacyMessage = String(localized: "Set the number of days first.")
                        return
                    }
                    model.purge(olderThanDays: days) { removed in
                        privacyMessage = String(localized: "Deleted \(removed) old entries.")
                    }
                }
                .disabled(model.isBusy)
                Button("Clear all history", role: .destructive) { confirmingClear = true }
                    .disabled(model.items.isEmpty || model.isBusy)
                if let privacyMessage {
                    Text(privacyMessage).font(.footnote).foregroundStyle(.secondary)
                }
            } label: {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Privacy & retention")
                    Text(model.policy.historyEnabled ? "Saving your dictations on this device" : "History is off; nothing is saved")
                        .font(.caption).foregroundStyle(.secondary)
                }
            }
        }
        .whisperRowSurface()
        .onAppear { autoDeleteDays = String(model.policy.autoDeleteDays) }
        .onChange(of: model.policy) { _, policy in autoDeleteDays = String(policy.autoDeleteDays) }
    }

    private func privacyBinding(_ key: String, default fallback: Bool) -> Binding<Bool> {
        Binding {
            (flow.settings?["privacy"] as? [String: Any])?[key] as? Bool ?? fallback
        } set: { value in
            flow.updateSettings { settings in
                var privacy = settings["privacy"] as? [String: Any] ?? [:]
                privacy[key] = value
                settings["privacy"] = privacy
            }
            model.refresh()
        }
    }

    private func saveAutoDelete() {
        let days = Int(autoDeleteDays.filter(\.isNumber).prefix(4)) ?? 0
        flow.updateSettings { settings in
            var privacy = settings["privacy"] as? [String: Any] ?? [:]
            privacy["transcript_auto_delete_days"] = days
            settings["privacy"] = privacy
        }
        privacyMessage = String(localized: "Saved.")
        model.refresh()
    }

    private var historyHeader: some View {
        Section {
            if !model.items.isEmpty || !model.searchText.isEmpty {
                WhisperSearchField(text: $model.searchText, prompt: "Search transcripts")
            } else if model.isBusy {
                ProgressView()
            } else if model.errorMessage == nil {
                Text("No saved dictations yet.").foregroundStyle(.secondary)
            }
        } header: {
            HStack {
                VStack(alignment: .leading, spacing: 2) {
                    WhisperSectionHeading("History")
                    Text("Tap to copy · Hold to see the original")
                        .font(.caption).foregroundStyle(.secondary).textCase(nil)
                }
                Spacer()
                Text("\(model.items.count) entries").font(.caption).foregroundStyle(.secondary).textCase(nil)
                if !model.items.isEmpty {
                    Menu {
                        Button("Clear history", systemImage: "trash", role: .destructive) { confirmingClear = true }
                    } label: {
                        Label("History actions", systemImage: "ellipsis.circle")
                            .labelStyle(.iconOnly)
                            .frame(minWidth: 44, minHeight: 44)
                            .foregroundStyle(WhisperColors.primary(colorScheme))
                    }
                    .disabled(model.isBusy)
                }
            }
        }
        .whisperRowSurface()
    }

    @ViewBuilder
    private var historySections: some View {
        if model.visibleItems.isEmpty && model.errorMessage == nil && !model.searchText.isEmpty {
            Section {
                Text("No matches.").foregroundStyle(.secondary)
            }
            .whisperRowSurface()
        }
        ForEach(model.days) { day in
            Section {
                ForEach(day.items) { item in
                    historyRow(item)
                }
            } header: {
                WhisperSectionHeading(day.title)
            }
            .whisperRowSurface()
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
        VStack(alignment: .leading, spacing: 8) {
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
                    let expanded = expandedOriginalID == item.id
                    Button {
                        toggleOriginal(item)
                    } label: {
                        Label(expanded ? "Hide original" : "Show original", systemImage: expanded ? "chevron.up" : "chevron.down")
                            .labelStyle(.iconOnly)
                            .frame(width: 44, height: 44)
                    }
                }
            }
            if expandedOriginalID == item.id, let raw = item.rawText {
                Divider()
                Text("Original").font(.subheadline.weight(.semibold))
                if raw == item.text {
                    Text("Same as the saved text: cleanup changed nothing.").font(.subheadline).foregroundStyle(.secondary)
                } else {
                    Text(raw).font(.subheadline).foregroundStyle(.secondary).textSelection(.enabled)
                }
            }
        }
        .buttonStyle(.borderless)
        .contextMenu {
            if item.rawText?.isEmpty == false {
                Button("Original transcription", systemImage: "doc.text.magnifyingglass") { toggleOriginal(item) }
            }
            if !item.text.isEmpty {
                Button("Copy", systemImage: "doc.on.doc") {
                    if Delivery.copyToClipboard(item.text) { lastCopied = item.id }
                }
            }
            if let raw = item.rawText, !raw.isEmpty {
                Button("Copy original", systemImage: "doc.on.clipboard") {
                    if Delivery.copyToClipboard(raw) { lastCopied = item.id }
                }
            }
        }
        .swipeActions {
            Button("Delete", systemImage: "trash", role: .destructive) { model.deleteItem(item) }
                .disabled(model.isBusy)
        }
    }

    private func toggleOriginal(_ item: HistoryItem) {
        expandedOriginalID = expandedOriginalID == item.id ? nil : item.id
    }

    private func subtitle(for item: HistoryItem) -> String {
        let words = item.wordCount == 1 ? "1 word" : "\(item.wordCount) words"
        guard item.recordedAt > 0 else { return item.dateLabel.isEmpty ? words : item.dateLabel }
        let date = Date(timeIntervalSince1970: TimeInterval(item.recordedAt))
        return "\(date.formatted(date: .omitted, time: .shortened)) · \(words)"
    }
}

/// Android's heatmap: weeks as columns (Sunday on top), shaded by words, month labels,
/// a Less → More key, and a tap for the day's totals. Wider screens show more weeks.
private struct SpeakingActivityView: View {
    @Environment(\.colorScheme) private var colorScheme
    let activity: [DailyBucket]
    @State private var tapped: DailyBucket?

    private static let gap: CGFloat = 3
    private static let labelHeight: CGFloat = 16

    var body: some View {
        VStack(alignment: .leading, spacing: 9) {
            HStack {
                Text("Speaking activity").font(.subheadline.weight(.semibold))
                Spacer()
                if !activity.isEmpty { legend }
            }
            if activity.isEmpty {
                Text("Nothing yet — dictate something first.")
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            } else {
                GeometryReader { proxy in heatmap(width: proxy.size.width) }
                    .frame(height: Self.labelHeight + 7 * (12 + Self.gap))
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .alert(tapped.map { Date(timeIntervalSince1970: TimeInterval($0.dayStart)).formatted(date: .abbreviated, time: .omitted) } ?? "",
               isPresented: Binding { tapped != nil } set: { if !$0 { tapped = nil } }) {
            Button("Close", role: .cancel) {}
        } message: {
            if let tapped {
                Text("\(tapped.words) words · \(tapped.utterances) dictations")
            }
        }
    }

    private var legend: some View {
        HStack(spacing: 3) {
            Text("Less").font(.caption2).foregroundStyle(.secondary)
            ForEach(0..<4) { level in
                RoundedRectangle(cornerRadius: 2).fill(shade(level)).frame(width: 10, height: 10)
            }
            Text("More").font(.caption2).foregroundStyle(.secondary)
        }
        .accessibilityHidden(true)
    }

    private func heatmap(width: CGFloat) -> some View {
        let calendar = Calendar.current
        let today = calendar.startOfDay(for: .now)
        // Columns end with the current week; rows run Sunday to Saturday.
        let weekday = calendar.component(.weekday, from: today) - 1
        let weeks = max(12, Int((width + Self.gap) / (12 + Self.gap)))
        let cell = (width - Self.gap * CGFloat(weeks - 1)) / CGFloat(weeks)
        let byDay = Dictionary(activity.map { ($0.dayStart, $0) }, uniquingKeysWith: { first, _ in first })
        let firstDay = calendar.date(byAdding: .day, value: -((weeks - 1) * 7 + weekday), to: today) ?? today
        func day(_ week: Int, _ row: Int) -> Date {
            calendar.date(byAdding: .day, value: week * 7 + row, to: firstDay) ?? today
        }
        let months: [(week: Int, name: String)] = {
            var labels: [(Int, String)] = []
            var lastMonth = -1
            var lastEnd: CGFloat = -.infinity
            for week in 0..<weeks {
                let month = calendar.component(.month, from: day(week, 0))
                defer { lastMonth = month }
                guard month != lastMonth else { continue }
                let x = CGFloat(week) * (cell + Self.gap)
                // Skip a cramped first label and any that would overlap the previous one.
                if week == 0, calendar.component(.month, from: day(1, 0)) != month { continue }
                guard x >= lastEnd else { continue }
                labels.append((week, day(week, 0).formatted(.dateTime.month(.abbreviated))))
                lastEnd = x + 30
            }
            return labels
        }()
        return ZStack(alignment: .topLeading) {
            ForEach(months.indices, id: \.self) { index in
                Text(months[index].name).font(.caption2).foregroundStyle(.secondary)
                    .offset(x: CGFloat(months[index].week) * (cell + Self.gap))
            }
            ForEach(0..<weeks, id: \.self) { week in
                ForEach(0..<7, id: \.self) { row in
                    let date = day(week, row)
                    if date <= today {
                        let key = Int64(date.timeIntervalSince1970)
                        let bucket = byDay[key]
                        RoundedRectangle(cornerRadius: cell * 0.2)
                            .fill(shade(Self.level(words: bucket?.words ?? 0)))
                            .frame(width: cell, height: cell)
                            .offset(x: CGFloat(week) * (cell + Self.gap), y: Self.labelHeight + CGFloat(row) * (cell + Self.gap))
                            .onTapGesture { tapped = bucket ?? DailyBucket(dayStart: key, words: 0, utterances: 0) }
                    }
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Speaking activity")
        .accessibilityValue("\(activity.reduce(0) { $0 + $1.words }) words in the last \(weeks) weeks")
    }

    /// Desktop thresholds: none, under 50 words, under 200, more.
    static func level(words: Int) -> Int {
        words <= 0 ? 0 : words < 50 ? 1 : words < 200 ? 2 : 3
    }

    private func shade(_ level: Int) -> Color {
        guard level > 0 else { return WhisperColors.onSurfaceVariant(colorScheme).opacity(0.14) }
        return WhisperColors.primary(colorScheme).opacity([0.35, 0.65, 1][level - 1])
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
    @Published private(set) var policy = RetentionPolicy.defaults
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
                let core = strongSelf.backend.coreStats()
                DispatchQueue.main.async { [weak self] in
                    guard let inner = self,
                          CoreBridge.SettingsJobs.jobApplies(currentGen: inner.screenGen, jobGen: gen) else {
                        return
                    }
                    inner.commit(committed, policy: policy, core: core)
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

    /// Deletes rows older than `days` (Android "Delete older now"); reports the count.
    func purge(olderThanDays days: Int, done: @escaping (Int) -> Void) {
        var removed = 0
        runMutation({ removed = try CoreBridge.purgeHistory(olderThanDays: Int32(days)) }, done: { done(removed) })
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

    private func runMutation(_ work: @escaping () throws -> Void, done: (() -> Void)? = nil) {
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
                let core = strongSelf.backend.coreStats()
                DispatchQueue.main.async { [weak self] in
                    guard let inner = self,
                          CoreBridge.SettingsJobs.jobApplies(currentGen: inner.screenGen, jobGen: gen) else {
                        return
                    }
                    inner.commit(committed, policy: policy, core: core)
                    done?()
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

    private func commit(_ committed: [HistoryItem], policy: RetentionPolicy, core: (UsageStats, [DailyBucket])?) {
        items = committed
        stats = core?.0 ?? StatsStore.usageStats(committed)
        activity = core?.1 ?? StatsStore.recentActivity(committed, days: 371)
        self.policy = policy
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
