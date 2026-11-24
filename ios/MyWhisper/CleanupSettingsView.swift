import SwiftUI

/// Cleanup settings: master switch + custom prompt + LLM failover chain.
///
/// Built on `Cleanup` (`Cleanup.swift`) — no duplicated chain logic:
/// - Enabled top-down order via `Cleanup.chain`, runnable set via
///   `Cleanup.runnableChain` (`Local` rows never run: no on-device
///   engine on phone or desktop).
/// - Prompt fallback via `Cleanup.effectivePrompt`, default text from
///   `Cleanup.defaultPrompt` (byte-identical to core).
/// - Deletes guarded like the speech chain: the last row cannot be
///   removed; other deletes confirm first. Reorder is up/down; drag
///   deferred.
/// - Serialization uses desktop serde keys (`id`, `name`, `kind`,
///   `enabled`, `url`, `model`, `provider`, `api_key`); capitalized
///   kind strings (`Ollama`/`Local`/`Cloud`) match core serde.
enum CleanupChain {
    /// Desktop schema key set written by `toDict` (golden in SettingsTests).
    static let schemaKeys: Set<String> = [
        "id", "name", "kind", "enabled", "url", "model", "provider", "api_key"
    ]

    static func capitalizedKind(_ kind: Cleanup.EntryKind) -> String {
        switch kind {
        case .ollama: return "Ollama"
        case .local: return "Local"
        case .cloud: return "Cloud"
        }
    }

    static func toDict(_ entry: Cleanup.Entry) -> [String: Any] {
        [
            "id": entry.id, "name": entry.name, "kind": capitalizedKind(entry.kind),
            "enabled": entry.enabled, "url": entry.url, "model": entry.model,
            "provider": entry.provider, "api_key": entry.apiKey
        ]
    }

    static func applying(_ config: CleanupSettingsDraft, to settings: [String: Any]) -> [String: Any] {
        var settings = settings
        var models = settings["language_models"] as? [String: Any] ?? [:]
        models["cleanup_enabled"] = config.enabled
        models["cleanup_prompt"] = config.customPrompt
        models["entries"] = config.entries.map { toDict($0) }
        settings["language_models"] = models
        return settings
    }

    static func move(_ entries: [Cleanup.Entry], id: String, delta: Int) -> [Cleanup.Entry] {
        guard let from = entries.firstIndex(where: { $0.id == id }) else { return entries }
        let to = from + delta
        guard to >= 0 && to < entries.count else { return entries }
        var next = entries
        next.swapAt(from, to)
        return next
    }

    static func moveUp(_ entries: [Cleanup.Entry], id: String) -> [Cleanup.Entry] {
        move(entries, id: id, delta: -1)
    }

    static func moveDown(_ entries: [Cleanup.Entry], id: String) -> [Cleanup.Entry] {
        move(entries, id: id, delta: 1)
    }

    /// Delete guard: keep at least one row; disable instead of deleting.
    static func canDelete(_ entries: [Cleanup.Entry]) -> Bool {
        entries.count > 1
    }

    static func canAddLocal(_ entries: [Cleanup.Entry]) -> Bool {
        entries.contains(where: { $0.kind == .local }) == false
    }

    /// Cloud rows backed by Gemini (provider `Google`/`Gemini`) surface a
    /// Why-dialog: Gemini cleanup errors are a known failure mode.
    static func isGeminiLike(_ entry: Cleanup.Entry) -> Bool {
        guard entry.kind == .cloud else { return false }
        let provider = entry.provider.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return provider == "google" || provider == "gemini"
    }

    static func isSkippedLocal(_ entry: Cleanup.Entry) -> Bool {
        entry.kind == .local
    }
}

/// Editable snapshot of `Cleanup.Configuration` (entries are value
/// types, so the draft owns a copy until saved).
struct CleanupSettingsDraft: Equatable {
    var enabled: Bool
    var customPrompt: String
    var entries: [Cleanup.Entry]

    static func from(_ config: Cleanup.Configuration) -> CleanupSettingsDraft {
        CleanupSettingsDraft(enabled: config.enabled, customPrompt: config.customPrompt, entries: config.entries)
    }
}

/// Bridge-backed cleanup store; pure logic stays on `Cleanup` /
/// `CleanupChain` so tests never touch FFI.
final class CleanupSettingsStore: ObservableObject {
    @Published var draft = CleanupSettingsDraft(enabled: true, customPrompt: "", entries: [])
    @Published var error: String?

    init(draft: CleanupSettingsDraft = CleanupSettingsDraft(enabled: true, customPrompt: "", entries: [])) {
        self.draft = draft
    }

    func load() {
        do {
            draft = CleanupSettingsDraft.from(Cleanup.configuration(settings: try CoreBridge.getSettings()))
            error = nil
        } catch {
            self.error = error.localizedDescription
        }
    }

    func persist() {
        do {
            let settings = CleanupChain.applying(draft, to: try CoreBridge.getSettings())
            try CoreBridge.putSettings(settings)
            error = nil
        } catch {
            self.error = error.localizedDescription
        }
    }

    func setEnabled(_ value: Bool) {
        draft.enabled = value
        persist()
    }

    func setCustomPrompt(_ value: String) {
        draft.customPrompt = value
        persist()
    }

    func resetPrompt() {
        draft.customPrompt = ""
        persist()
    }

    func setEntryEnabled(_ entry: Cleanup.Entry, enabled: Bool) {
        guard let index = draft.entries.firstIndex(where: { $0.id == entry.id }) else { return }
        let current = draft.entries[index]
        draft.entries[index] = Cleanup.Entry(
            id: current.id, name: current.name, kind: current.kind, enabled: enabled,
            url: current.url, model: current.model, provider: current.provider, apiKey: current.apiKey
        )
        persist()
    }

    func moveUp(_ entry: Cleanup.Entry) {
        draft.entries = CleanupChain.moveUp(draft.entries, id: entry.id)
        persist()
    }

    func moveDown(_ entry: Cleanup.Entry) {
        draft.entries = CleanupChain.moveDown(draft.entries, id: entry.id)
        persist()
    }

    func upsert(_ entry: Cleanup.Entry) {
        if let index = draft.entries.firstIndex(where: { $0.id == entry.id }) {
            draft.entries[index] = entry
        } else {
            draft.entries.append(entry)
        }
        persist()
    }

    func remove(_ entry: Cleanup.Entry) {
        guard CleanupChain.canDelete(draft.entries) else { return }
        draft.entries.removeAll(where: { $0.id == entry.id })
        persist()
    }
}

/// Cleanup editor screen.
struct CleanupSettingsView: View {
    @ObservedObject var store: CleanupSettingsStore

    @State private var sheet: CleanupSheetKind?
    @State private var pendingDelete: Cleanup.Entry?
    @State private var guardBlocked = false
    @State private var whyText: String?
    @State private var promptDraft = ""
    @State private var promptEditing = false

    init(store: CleanupSettingsStore = CleanupSettingsStore()) {
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
                Toggle("Enable cleanup", isOn: Binding(get: { store.draft.enabled }, set: { store.setEnabled($0) }))
                if store.draft.enabled {
                    let runnable = Cleanup.runnableChain(store.draft.entries)
                    let trying = runnable.map { $0.name }.joined(separator: " → ")
                    Text(runnable.isEmpty ? "No runnable entry — output passes through." : "Trying: \(trying)")
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
            }
            .whisperRowSurface()
            Section {
                if promptEditing {
                    TextEditor(text: $promptDraft)
                        .frame(minHeight: 120)
                        .font(.body)
                    HStack {
                        Button("Save") {
                            store.setCustomPrompt(promptDraft)
                            promptEditing = false
                        }
                        Button("Cancel", role: .cancel) { promptEditing = false }
                        Spacer()
                        Button("Reset to default") { store.resetPrompt() }
                    }
                    .buttonStyle(.borderless)
                } else {
                    Text(store.draft.customPrompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
                        ? "(Default prompt)" : store.draft.customPrompt)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                        .lineLimit(6)
                    HStack {
                        Button("Edit custom prompt") {
                            promptDraft = store.draft.customPrompt
                            promptEditing = true
                        }
                        if store.draft.customPrompt.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == false {
                            Button("Reset to default") { store.resetPrompt() }
                        }
                    }
                    .buttonStyle(.borderless)
                }
            }
            .whisperRowSurface()
            Section {
                Menu {
                    Button("Ollama") { sheet = .add(.ollama) }
                    Button("Cloud provider") { sheet = .add(.cloud) }
                } label: {
                    WhisperAddLabel(title: "Add provider")
                }
                .listRowInsets(EdgeInsets())
                .listRowBackground(Color.clear)
            }
            Section {
                if store.draft.entries.isEmpty {
                    Text("Add a provider to clean up transcripts.")
                        .foregroundStyle(.secondary)
                }
                ForEach(store.draft.entries, id: \.id) { entry in
                    CleanupRow(
                        entry: entry,
                        canMoveUp: store.draft.entries.first?.id != entry.id,
                        canMoveDown: store.draft.entries.last?.id != entry.id,
                        onToggle: { store.setEntryEnabled(entry, enabled: $0) },
                        onMoveUp: { store.moveUp(entry) },
                        onMoveDown: { store.moveDown(entry) },
                        onEdit: { sheet = .edit(entry) },
                        onDelete: { requestDelete(entry) },
                        onWhy: { whyText = $0 }
                    )
                }
            } header: {
                WhisperSectionHeading("Cleanup providers")
            } footer: {
                Text("Enabled providers run in order until one answers.")
            }
            .whisperRowSurface()
        }
        .whisperListStyle()
        .onAppear { if store.draft.entries.isEmpty { store.load() } }
        .sheet(item: $sheet) { kind in
            switch kind {
            case .add(let preset):
                LlmEntryForm(kind: preset, onSave: { store.upsert($0) })
            case .edit(let entry):
                LlmEntryForm(entry: entry, onSave: { store.upsert($0) })
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
            Text("Remove this LLM entry? Cleanup will use the remaining chain.")
        }
        .alert("Why?", isPresented: whyBinding) {
            Button("OK", role: .cancel) { whyText = nil }
        } message: {
            Text(whyText ?? "")
        }
    }

    private func requestDelete(_ entry: Cleanup.Entry) {
        if CleanupChain.canDelete(store.draft.entries) == false {
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

enum CleanupSheetKind: Identifiable {
    case add(Cleanup.EntryKind)
    case edit(Cleanup.Entry)

    var id: String {
        switch self {
        case .add(let kind): return "add-\(kind)"
        case .edit(let entry): return "edit-\(entry.id)"
        }
    }
}

private struct CleanupRow: View {
    var entry: Cleanup.Entry
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
            summary: "\(CleanupChain.capitalizedKind(entry.kind)) · \(entry.model)",
            enabled: Binding(get: { entry.enabled }, set: onToggle),
            canMoveUp: canMoveUp,
            canMoveDown: canMoveDown,
            onMoveUp: onMoveUp,
            onMoveDown: onMoveDown,
            onEdit: onEdit,
            onDelete: onDelete
        ) {
            if CleanupChain.isSkippedLocal(entry) {
                Button("Why skipped?") { onWhy(localSkippedCleanupCopy) }
            }
            if CleanupChain.isGeminiLike(entry) {
                Button("Why errors?") { onWhy(geminiCleanupCopy) }
            }
        }
    }
}

/// Local-skip copy for cleanup rows (same cause as the STT chain one:
/// no on-device cleanup engine on phone or desktop).
let localSkippedCleanupCopy = "Local cleanup has no on-device engine — "
    + "the row never runs and the chain falls through to the next entry. Order is unchanged."

/// Gemini-backed cleanup failures surface raw core text; this explains why.
let geminiCleanupCopy = "Gemini cleanup entries fail often in this build — "
    + "check the raw error after dictation, or move a working entry above this one."

/// Local placeholder row (mirrors the desktop Local option shape).
extension Cleanup {
    static func localFallbackEntry() -> Entry {
        Entry(
            id: "lm-local-\(UUID().uuidString)", name: "Local", kind: .local, enabled: true,
            url: "", model: "", provider: "", apiKey: ""
        )
    }
}

#Preview {
    CleanupSettingsView(store: CleanupSettingsStore(draft: CleanupSettingsDraft(
        enabled: true, customPrompt: "",
        entries: [Cleanup.ollamaEntry(), Cleanup.cloudEntry(provider: "Google", model: "gemini-3.8-flash")]
    )))
}
