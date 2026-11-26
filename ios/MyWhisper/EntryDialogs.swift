import SwiftUI

/// Shared add/edit sheets for the settings screens. Forms collect raw
/// strings and build `SttChainEntry` / `Cleanup.Entry` on save; validation
/// for words/snippets reuses `DictionaryStore` (no duplicated rules).
///
/// Cloud provider lists mirror core exactly: STT `SttChainEntry.provider`
/// is `OpenAI | Groq | Mistral | Gemini | Deepgram`; cleanup
/// `LlmEntry.provider` is `CloudLlm::name` (`Groq | OpenRouter | OpenAI |
/// Claude | Google`).

// MARK: - STT entry form

/// Add/edit sheet for one `SttChainEntry`. `entry == nil` means add with
/// `kind` preset; edit locks the kind (kind switches go through
/// delete + add so ids never change meaning).
struct SttEntryForm: View {
    static let cloudProviders = ["OpenAI", "Groq", "Mistral", "Gemini", "Deepgram"]

    var entry: SttChainEntry?
    var kind: SttEntryKind
    var onSave: (SttChainEntry) -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var name: String
    @State private var url: String
    @State private var model: String
    @State private var provider: String
    @State private var apiKey: String
    @State private var streaming: Bool
    @State private var enabled: Bool

    init(entry: SttChainEntry, onSave: @escaping (SttChainEntry) -> Void) {
        self.entry = entry
        kind = entry.kind
        self.onSave = onSave
        _name = State(initialValue: entry.name)
        _url = State(initialValue: entry.url)
        _model = State(initialValue: entry.model)
        _provider = State(initialValue: entry.provider)
        _apiKey = State(initialValue: entry.apiKey)
        _streaming = State(initialValue: entry.streaming)
        _enabled = State(initialValue: entry.enabled)
    }

    init(kind: SttEntryKind, onSave: @escaping (SttChainEntry) -> Void) {
        entry = nil
        self.kind = kind
        self.onSave = onSave
        _name = State(initialValue: "")
        _url = State(initialValue: kind == .selfHosted ? "http://localhost:8000" : "")
        _model = State(initialValue: "")
        _provider = State(initialValue: kind == .cloud ? "OpenAI" : "")
        _apiKey = State(initialValue: "")
        _streaming = State(initialValue: true)
        _enabled = State(initialValue: true)
    }

    /// Android's form rules: a model, a valid URL, and a key for cloud providers.
    private var problem: LocalizedStringKey? {
        if let reason = ProviderTest.blockedReason(kind: kind.rawValue, url: url, model: model) { return reason }
        if kind == .cloud, apiKey.trimmingCharacters(in: .whitespaces).isEmpty { return "Enter the provider's API key." }
        return nil
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Entry") {
                    TextField("Name (optional)", text: $name)
                    Toggle("Enabled", isOn: $enabled)
                    Text(kind.rawValue).font(.caption).foregroundStyle(.secondary)
                }
                if kind == .selfHosted {
                    Section {
                        TextField("Server URL (http(s) or ws(s))", text: $url)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .keyboardType(.URL)
                        TextField("Model", text: $model)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        SecureField("API key (optional)", text: $apiKey)
                        Toggle("Streaming (ws servers)", isOn: $streaming)
                    } header: {
                        Text("Server")
                    } footer: {
                        Text("Self-hosted means any OpenAI-compatible speech endpoint, including the server bundled with MyWhisper desktop. http(s) URLs always use request/response; ws(s) URLs use the sherpa-onnx streaming protocol.")
                    }
                }
                if kind == .cloud {
                    Section {
                        Picker("Provider", selection: $provider) {
                            ForEach(Self.cloudProviders, id: \.self) { Text($0) }
                        }
                        TextField("Model", text: $model)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        SecureField("API key", text: $apiKey)
                        TextField("Base URL (blank = vendor default)", text: $url)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .keyboardType(.URL)
                    } header: {
                        Text("Provider")
                    } footer: {
                        if provider == "Gemini" { Text(geminiUnsupportedCopy) }
                    }
                }
                if kind != .local {
                    Section {
                        if let problem { Text(problem).font(.caption).foregroundStyle(.secondary) }
                        ProviderTestButton(blockedReason: ProviderTest.blockedReason(kind: kind.rawValue, url: url, model: model)) {
                            await ProviderTest.speech(url: url, model: model, apiKey: apiKey)
                        }
                    }
                }
                if kind == .local {
                    Section {
                        Text("Local references the live on-device configuration. Nothing to fill in.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle(entry == nil ? "Add speech entry" : "Edit speech entry")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }.disabled(problem != nil)
                }
            }
        }
    }

    private func save() {
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let resolvedName: String
        if trimmedName.isEmpty == false {
            resolvedName = trimmedName
        } else if kind == .selfHosted {
            resolvedName = "Self-hosted (\(model))"
        } else if kind == .cloud {
            resolvedName = "\(provider) (\(model))"
        } else {
            resolvedName = "Local"
        }
        let result = SttChainEntry(
            id: entry?.id ?? "stt-\(UUID().uuidString)", name: resolvedName, kind: kind,
            enabled: enabled, provider: provider, url: url.trimmingCharacters(in: .whitespaces),
            model: model.trimmingCharacters(in: .whitespaces), apiKey: apiKey.trimmingCharacters(in: .whitespaces), streaming: streaming
        )
        onSave(result)
        dismiss()
    }
}

// MARK: - LLM entry form

/// Add/edit sheet for one `Cleanup.Entry`. Edit locks the kind, same as STT.
struct LlmEntryForm: View {
    static let cloudProviders = ["Groq", "OpenRouter", "OpenAI", "Claude", "Google"]

    var entry: Cleanup.Entry?
    var kind: Cleanup.EntryKind
    var onSave: (Cleanup.Entry) -> Void
    @Environment(\.dismiss) private var dismiss

    @State private var name: String
    @State private var url: String
    @State private var model: String
    @State private var provider: String
    @State private var apiKey: String
    @State private var enabled: Bool

    init(entry: Cleanup.Entry, onSave: @escaping (Cleanup.Entry) -> Void) {
        self.entry = entry
        kind = entry.kind
        self.onSave = onSave
        _name = State(initialValue: entry.name)
        _url = State(initialValue: entry.url)
        _model = State(initialValue: entry.model)
        _provider = State(initialValue: entry.provider)
        _apiKey = State(initialValue: entry.apiKey)
        _enabled = State(initialValue: entry.enabled)
    }

    init(kind: Cleanup.EntryKind, onSave: @escaping (Cleanup.Entry) -> Void) {
        entry = nil
        self.kind = kind
        self.onSave = onSave
        _name = State(initialValue: "")
        _url = State(initialValue: kind == .ollama ? "http://127.0.0.1:11434" : "")
        _model = State(initialValue: "")
        _provider = State(initialValue: kind == .cloud ? "OpenAI" : "")
        _apiKey = State(initialValue: "")
        _enabled = State(initialValue: true)
    }

    private var problem: LocalizedStringKey? {
        if let reason = ProviderTest.blockedReason(kind: CleanupChain.capitalizedKind(kind), url: url, model: model) { return reason }
        if kind == .cloud, apiKey.trimmingCharacters(in: .whitespaces).isEmpty { return "Enter the provider's API key." }
        return nil
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Entry") {
                    TextField("Name (optional)", text: $name)
                    Toggle("Enabled", isOn: $enabled)
                    Text(CleanupChain.capitalizedKind(kind)).font(.caption).foregroundStyle(.secondary)
                }
                if kind == .ollama {
                    Section("Server") {
                        TextField("URL", text: $url)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .keyboardType(.URL)
                        TextField("Model", text: $model)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        SecureField("API key (optional)", text: $apiKey)
                    }
                }
                if kind == .cloud {
                    Section("Provider") {
                        Picker("Provider", selection: $provider) {
                            ForEach(Self.cloudProviders, id: \.self) { Text($0) }
                        }
                        TextField("Model", text: $model)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                        SecureField("API key", text: $apiKey)
                        TextField("URL (blank = vendor default)", text: $url)
                            .textInputAutocapitalization(.never)
                            .autocorrectionDisabled()
                            .keyboardType(.URL)
                    }
                }
                if kind != .local {
                    Section {
                        if let problem { Text(problem).font(.caption).foregroundStyle(.secondary) }
                        let kindName = CleanupChain.capitalizedKind(kind)
                        ProviderTestButton(blockedReason: ProviderTest.blockedReason(kind: kindName, url: url, model: model)) {
                            await ProviderTest.cleanup(kind: kindName, url: url, model: model, apiKey: apiKey)
                        }
                    }
                }
                if kind == .local {
                    Section {
                        Text("Local cleanup has no on-device engine — this row is always skipped.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .navigationTitle(entry == nil ? "Add LLM entry" : "Edit LLM entry")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }.disabled(problem != nil)
                }
            }
        }
    }

    private func save() {
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        let resolvedName: String
        if trimmedName.isEmpty == false {
            resolvedName = trimmedName
        } else if kind == .ollama {
            resolvedName = "Ollama (\(model))"
        } else if kind == .cloud {
            resolvedName = "\(provider) (\(model))"
        } else {
            resolvedName = "Local"
        }
        let result = Cleanup.Entry(
            id: entry?.id ?? "lm-\(UUID().uuidString)", name: resolvedName, kind: kind,
            enabled: enabled, url: url.trimmingCharacters(in: .whitespaces), model: model.trimmingCharacters(in: .whitespaces),
            provider: provider, apiKey: apiKey.trimmingCharacters(in: .whitespaces)
        )
        onSave(result)
        dismiss()
    }
}

// MARK: - Snippet form

/// User-facing text for `DictionaryError` (forms show it inline).
func dictionaryErrorText(_ error: DictionaryError) -> String {
    switch error {
    case .emptyWord: return "Enter a word."
    case .emptyTrigger: return "Enter a trigger."
    case .emptyReplacement: return "Enter a replacement."
    }
}

/// Add/edit snippet sheet; validation via `DictionaryStore.validateSnippet`.
/// `existing == nil` means add, otherwise edit (trigger is the identity —
/// changing it deletes the old row; the owner handles that).
struct SnippetForm: View {
    var existing: DictionaryStore.Snippet?
    var onSave: (String, String, String?) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var trigger: String
    @State private var replacement: String
    @State private var error: String?

    init(existing: DictionaryStore.Snippet? = nil, onSave: @escaping (String, String, String?) -> Void) {
        self.existing = existing
        self.onSave = onSave
        _trigger = State(initialValue: existing?.trigger ?? "")
        _replacement = State(initialValue: existing?.replacement ?? "")
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Trigger") {
                    TextField("e.g. investor ask", text: $trigger)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
                Section("Replacement") {
                    TextField("e.g. investor asks", text: $replacement)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                }
                if let error = error {
                    Text(error).foregroundStyle(.red).font(.caption)
                }
            }
            .navigationTitle(existing == nil ? "Add snippet" : "Edit snippet")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") {
                        do {
                            let valid = try DictionaryStore.validateSnippet(trigger: trigger, replacement: replacement)
                            onSave(valid.0, valid.1, existing?.trigger)
                            dismiss()
                        } catch let validation as DictionaryError {
                            error = dictionaryErrorText(validation)
                        } catch {
                            self.error = error.localizedDescription
                        }
                    }
                }
            }
        }
    }
}

#Preview {
    SttEntryForm(kind: .cloud, onSave: { _ in })
}
