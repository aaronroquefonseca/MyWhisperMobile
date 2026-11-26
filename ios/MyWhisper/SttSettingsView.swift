import SwiftUI

/// Speech-chain settings: CRUD over the `stt.entries` chain (core
/// `SttChainEntry`, desktop schema). Order = failover priority, top-down.
///
/// Desktop/core parity:
/// - Enabled entries run top-down until one answers (`SttSettings::chain`).
/// - `Local` appears at most once (references the live local config).
/// - `Local` without its verified model pack is skipped in favor of the
///   server chain — the row stays, with a Why-dialog (same copy as
///   `localSkippedCopy` in `Transcription.swift`).
/// - Cloud `Gemini` speech is unimplemented — the row stays, with a
///   Why-dialog (`geminiUnsupportedCopy`).
/// - Reorder is up/down buttons; drag deferred (no `EditMode` yet).
/// - Delete guard: the last remaining row cannot be removed (disable it
///   instead) — the chain must keep at least one row; every other delete
///   asks for confirmation first.
enum SttEntryKind: String {
    case local = "Local"
    case selfHosted = "SelfHosted"
    case cloud = "Cloud"

    init?(raw: String) {
        let normalized = raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            .replacingOccurrences(of: "_", with: "").replacingOccurrences(of: "-", with: "")
        switch normalized {
        case "local": self = .local
        case "selfhosted": self = .selfHosted
        case "cloud": self = .cloud
        default: return nil
        }
    }
}

/// One STT chain row. Dict keys are the desktop serde names byte-for-byte
/// (`id`, `name`, `kind`, `enabled`, `provider`, `url`, `model`,
/// `api_key`, `streaming`); `streaming` defaults to true when absent
/// (core `default_true`).
struct SttChainEntry: Equatable, Identifiable {
    // Desktop schema key set (the SettingsTests golden pins this exactly).
    static let schemaKeys: Set<String> = [
        "id", "name", "kind", "enabled", "provider", "url", "model", "api_key", "streaming"
    ]

    let id: String
    var name: String
    var kind: SttEntryKind
    var enabled: Bool
    var provider: String
    var url: String
    var model: String
    var apiKey: String
    var streaming: Bool

    init(
        id: String, name: String, kind: SttEntryKind, enabled: Bool,
        provider: String, url: String, model: String, apiKey: String, streaming: Bool = true
    ) {
        self.id = id
        self.name = name
        self.kind = kind
        self.enabled = enabled
        self.provider = provider
        self.url = url
        self.model = model
        self.apiKey = apiKey
        self.streaming = streaming
    }

    init?(dict: [String: Any]) {
        guard let kindRaw = dict["kind"] as? String, let kind = SttEntryKind(raw: kindRaw) else {
            return nil
        }
        self.kind = kind
        id = dict["id"] as? String ?? ""
        name = dict["name"] as? String ?? ""
        enabled = dict["enabled"] as? Bool ?? true
        provider = dict["provider"] as? String ?? ""
        url = dict["url"] as? String ?? ""
        model = dict["model"] as? String ?? ""
        apiKey = dict["api_key"] as? String ?? ""
        streaming = dict["streaming"] as? Bool ?? true
    }

    func toDict() -> [String: Any] {
        [
            "id": id, "name": name, "kind": kind.rawValue, "enabled": enabled,
            "provider": provider, "url": url, "model": model, "api_key": apiKey, "streaming": streaming
        ]
    }

    /// Core `SttChainEntry::summary` parity.
    var summary: String {
        switch kind {
        case .local: return "current local configuration"
        case .selfHosted: return "\(url) · \(model)"
        case .cloud: return "\(provider) · \(model)"
        }
    }

    var isLocal: Bool { kind == .local }

    /// Cloud Gemini rows never run (see `mapSttError` gemini branch).
    var isGemini: Bool {
        kind == .cloud && provider.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() == "gemini"
    }
}

/// Pure chain helpers (no bridge — unit-tested in SettingsTests).
/// Bridge-backed load/save live on `SttSettingsStore`.
enum SttChain {
    /// Enabled entries top-down: the order dictation tries (core `chain`).
    static func chain(_ entries: [SttChainEntry]) -> [SttChainEntry] {
        entries.filter { $0.enabled }
    }

    static func parseSection(_ settings: [String: Any]) -> [SttChainEntry] {
        let stt = settings["stt"] as? [String: Any] ?? [:]
        let raw = stt["entries"] as? [[String: Any]] ?? []
        return raw.compactMap { SttChainEntry(dict: $0) }
    }

    static func applying(_ entries: [SttChainEntry], to settings: [String: Any]) -> [String: Any] {
        var settings = settings
        var stt = settings["stt"] as? [String: Any] ?? [:]
        stt["entries"] = preservingUnknownKeys(entries.map { $0.toDict() }, from: stt["entries"])
        settings["stt"] = stt
        return settings
    }

    /// Move one row by `delta` (-1 up, +1 down). Unknown id or clamped
    /// edges return the input unchanged.
    static func move(_ entries: [SttChainEntry], id: String, delta: Int) -> [SttChainEntry] {
        guard let from = entries.firstIndex(where: { $0.id == id }) else { return entries }
        let to = from + delta
        guard to >= 0 && to < entries.count else { return entries }
        var next = entries
        next.swapAt(from, to)
        return next
    }

    static func moveUp(_ entries: [SttChainEntry], id: String) -> [SttChainEntry] {
        move(entries, id: id, delta: -1)
    }

    static func moveDown(_ entries: [SttChainEntry], id: String) -> [SttChainEntry] {
        move(entries, id: id, delta: 1)
    }

    /// Delete guard: the chain keeps at least one row — the last row
    /// cannot be removed (disable it instead).
    static func canDelete(_ entries: [SttChainEntry]) -> Bool {
        entries.count > 1
    }

    /// Local appears at most once (core doc: references live config).
    static func canAddLocal(_ entries: [SttChainEntry]) -> Bool {
        entries.contains(where: { $0.isLocal }) == false
    }

    static func localEntry() -> SttChainEntry {
        SttChainEntry(
            id: "stt-local-\(UUID().uuidString)", name: "Local", kind: .local,
            enabled: true, provider: "", url: "", model: "", apiKey: ""
        )
    }

    static func selfHostedEntry(url: String = "http://localhost:8000", model: String = "") -> SttChainEntry {
        SttChainEntry(
            id: "stt-self-\(UUID().uuidString)", name: "Self-hosted (\(model))", kind: .selfHosted,
            enabled: true, provider: "", url: url, model: model, apiKey: ""
        )
    }

    static func cloudEntry(provider: String = "OpenAI", model: String = "whisper-1", apiKey: String = "") -> SttChainEntry {
        SttChainEntry(
            id: "stt-cloud-\(UUID().uuidString)", name: "\(provider) (\(model))", kind: .cloud,
            enabled: true, provider: provider, url: "", model: model, apiKey: apiKey
        )
    }
}

/// Bridge-backed STT chain store. Pure helpers stay on `SttChain` so
/// tests never touch FFI.
final class SttSettingsStore: ObservableObject {
    @Published var entries: [SttChainEntry] = []
    @Published var error: String?
    /// `stt.language` ("auto" or an ISO code) and the voice-activity trim (`vad_*`).
    @Published var language = "auto"
    @Published var vadEnabled = false
    @Published var vadThreshold = 0.1

    init(entries: [SttChainEntry] = []) {
        self.entries = entries
    }

    func load() {
        do {
            let settings = try CoreBridge.getSettings()
            entries = SttChain.parseSection(settings)
            let stt = settings["stt"] as? [String: Any] ?? [:]
            language = (stt["language"] as? String).flatMap { $0.isEmpty ? nil : $0 } ?? "auto"
            vadEnabled = stt["vad_enabled"] as? Bool ?? false
            vadThreshold = (stt["vad_threshold"] as? NSNumber)?.doubleValue ?? 0.1
            error = nil
        } catch {
            self.error = error.localizedDescription
        }
    }

    func persist() {
        do {
            let settings = SttChain.applying(entries, to: try CoreBridge.getSettings())
            try CoreBridge.putSettings(settings)
            error = nil
        } catch {
            self.error = error.localizedDescription
        }
    }

    /// Writes the language and voice-activity options next to the chain.
    func persistOptions() {
        do {
            var settings = try CoreBridge.getSettings()
            var stt = settings["stt"] as? [String: Any] ?? [:]
            stt["language"] = language
            stt["vad_enabled"] = vadEnabled
            stt["vad_threshold"] = vadThreshold
            settings["stt"] = stt
            try CoreBridge.putSettings(settings)
            error = nil
        } catch {
            self.error = error.localizedDescription
        }
    }

    func setEnabled(_ entry: SttChainEntry, enabled: Bool) {
        guard let index = entries.firstIndex(where: { $0.id == entry.id }) else { return }
        entries[index].enabled = enabled
        persist()
    }

    func moveUp(_ entry: SttChainEntry) {
        entries = SttChain.moveUp(entries, id: entry.id)
        persist()
    }

    func moveDown(_ entry: SttChainEntry) {
        entries = SttChain.moveDown(entries, id: entry.id)
        persist()
    }

    func upsert(_ entry: SttChainEntry) {
        if let index = entries.firstIndex(where: { $0.id == entry.id }) {
            entries[index] = entry
        } else {
            entries.append(entry)
        }
        persist()
    }

    func remove(_ entry: SttChainEntry) {
        guard SttChain.canDelete(entries) else { return }
        entries.removeAll(where: { $0.id == entry.id })
        persist()
    }
}

/// Speech-chain editor screen.
struct SttSettingsView: View {
    @ObservedObject var store: SttSettingsStore

    @State private var sheet: SttSheetKind?
    @State private var pendingDelete: SttChainEntry?
    @State private var guardBlocked = false
    @State private var whyText: String?

    init(store: SttSettingsStore = SttSettingsStore()) {
        self.store = store
    }

    var body: some View {
        List {
            if let error = store.error {
                Section {
                    Text(error).foregroundStyle(.red).font(.caption)
                }
                .whisperRowSurface()
            }
            Section {
                Picker("Language", selection: $store.language) {
                    ForEach(SttLanguage.all.indices, id: \.self) { index in
                        Text(SttLanguage.all[index].name).tag(SttLanguage.all[index].code)
                    }
                }
                .onChange(of: store.language) { _, _ in store.persistOptions() }
            } header: {
                WhisperSectionHeading("Language you speak")
            } footer: {
                Text("Auto-detect understands whichever language you speak, so you can switch languages from one dictation to the next. Pick a single language only if it guesses wrong.")
            }
            .whisperRowSurface()
            Section {
                Menu {
                    Button("Self-hosted server") { sheet = .add(.selfHosted) }
                    Button("Cloud provider") { sheet = .add(.cloud) }
                } label: {
                    WhisperAddLabel(title: "Add provider")
                }
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
            }
            Section {
                if store.entries.isEmpty {
                    Text("Add a provider to start transcribing.")
                        .foregroundStyle(.secondary)
                }
                ForEach(store.entries) { entry in
                    SttRow(
                        entry: entry,
                        canMoveUp: store.entries.first?.id != entry.id,
                        canMoveDown: store.entries.last?.id != entry.id,
                        onToggle: { store.setEnabled(entry, enabled: $0) },
                        onMoveUp: { store.moveUp(entry) },
                        onMoveDown: { store.moveDown(entry) },
                        onEdit: { sheet = .edit(entry) },
                        onDelete: { requestDelete(entry) },
                        onWhy: { whyText = $0 }
                    )
                }
            } header: {
                WhisperSectionHeading("Speech providers")
            } footer: {
                Text("Enabled providers run in order until one answers.")
            }
            .whisperRowSurface()
            Section {
                DisclosureGroup {
                    Toggle(isOn: $store.vadEnabled) {
                        VStack(alignment: .leading, spacing: 2) {
                            Text("Voice activity trim")
                            Text("Cut silence at the start and end of each recording").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    .onChange(of: store.vadEnabled) { _, _ in store.persistOptions() }
                    VStack(alignment: .leading) {
                        Text("VAD gate: \(store.vadThreshold.formatted(.number.precision(.fractionLength(2))))")
                        Slider(value: $store.vadThreshold, in: 0...0.5) { editing in
                            if !editing { store.persistOptions() }
                        }
                    }
                } label: {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("Advanced")
                        Text("Silence trimming and noise gate").font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            .whisperRowSurface()
        }
        .whisperListStyle()
        .onAppear(perform: store.load)
        .sheet(item: $sheet) { kind in
            switch kind {
            case .add(let preset):
                SttEntryForm(kind: preset, onSave: { store.upsert($0) })
            case .edit(let entry):
                SttEntryForm(entry: entry, onSave: { store.upsert($0) })
            }
        }
        .alert("Cannot delete", isPresented: $guardBlocked) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("The chain keeps at least one entry. Disable this row instead of deleting it.")
        }
        .alert("Confirm delete", isPresented: deleteConfirmBinding) {
            Button("Delete", role: .destructive) {
                if let entry = pendingDelete { store.remove(entry) }
                pendingDelete = nil
            }
            Button("Cancel", role: .cancel) { pendingDelete = nil }
        } message: {
            Text("Remove this speech entry? Dictation will use the remaining chain.")
        }
        .alert("Why?", isPresented: whyBinding) {
            Button("OK", role: .cancel) { whyText = nil }
        } message: {
            Text(whyText ?? "")
        }
    }

    private func requestDelete(_ entry: SttChainEntry) {
        if SttChain.canDelete(store.entries) == false {
            guardBlocked = true
        } else {
            pendingDelete = entry
        }
    }

    private var deleteConfirmBinding: Binding<Bool> {
        Binding(get: { pendingDelete != nil }, set: { if !$0 { pendingDelete = nil } })
    }

    private var whyBinding: Binding<Bool> {
        Binding(get: { whyText != nil }, set: { if !$0 { whyText = nil } })
    }
}

/// Android's speech-language list: "auto" or one ISO code (`stt.language`).
enum SttLanguage {
    static let all: [(code: String, name: LocalizedStringKey)] = [
        ("auto", "Auto-detect (any language)"), ("en", "English"), ("es", "Spanish"),
        ("de", "German"), ("fr", "French"), ("it", "Italian"), ("pt", "Portuguese"), ("nl", "Dutch"),
        ("pl", "Polish"), ("ru", "Russian"), ("uk", "Ukrainian"), ("tr", "Turkish"), ("ar", "Arabic"),
        ("hi", "Hindi"), ("zh", "Chinese"), ("ja", "Japanese"), ("ko", "Korean"), ("ca", "Catalan"),
        ("gl", "Galician"), ("eu", "Basque"), ("sv", "Swedish"), ("da", "Danish"), ("fi", "Finnish"),
        ("no", "Norwegian"), ("cs", "Czech"), ("ro", "Romanian"), ("hu", "Hungarian"), ("el", "Greek"),
    ]
}

enum SttSheetKind: Identifiable {
    case add(SttEntryKind)
    case edit(SttChainEntry)

    var id: String {
        switch self {
        case .add(let kind): return "add-\(kind.rawValue)"
        case .edit(let entry): return "edit-\(entry.id)"
        }
    }
}

private struct SttRow: View {
    var entry: SttChainEntry
    var canMoveUp: Bool
    var canMoveDown: Bool
    var onToggle: (Bool) -> Void
    var onMoveUp: () -> Void
    var onMoveDown: () -> Void
    var onEdit: () -> Void
    var onDelete: () -> Void
    var onWhy: (String) -> Void

    var body: some View {
        WhisperProviderRow(
            name: entry.name,
            summary: entry.summary,
            enabled: Binding(get: { entry.enabled }, set: onToggle),
            canMoveUp: canMoveUp,
            canMoveDown: canMoveDown,
            onMoveUp: onMoveUp,
            onMoveDown: onMoveDown,
            onEdit: onEdit,
            onDelete: onDelete
        ) {
            if entry.isLocal {
                Button("Why skipped?") { onWhy(localSkippedCopy) }
            }
            if entry.isGemini {
                Button("Why unsupported?") { onWhy(geminiUnsupportedCopy) }
            }
            if !entry.isLocal {
                ProviderTestButton(blockedReason: ProviderTest.blockedReason(kind: entry.kind.rawValue, url: entry.url, model: entry.model)) {
                    await ProviderTest.speech(url: entry.url, model: entry.model, apiKey: entry.apiKey)
                }
            }
        }
    }
}

#Preview {
    SttSettingsView(store: SttSettingsStore(entries: [
        SttChainEntry(id: "a", name: "Local", kind: .local, enabled: true, provider: "", url: "", model: "", apiKey: ""),
        SttChain.selfHostedEntry(url: "http://192.168.1.10:8000", model: "whisper"),
        SttChain.cloudEntry(provider: "Gemini", model: "gemini-2.0-flash")
    ]))
}
