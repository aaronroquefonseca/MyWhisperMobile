import SwiftUI

/// Dictionary editor: custom words + text snippets (trigger → replacement).
///
/// Thin UI over `DictionaryStore` (`DictionaryStore.swift`) — validation
/// and display order reuse it directly:
/// - Words hit the sync tables via the bridge (no restart needed).
/// - Snippets expand core-side, longest-trigger-first; the list reads in
///   `sortedForDisplay` order so it matches the matcher.
/// - Empty states explain what each list does; deletes confirm first.
final class DictionaryViewModel: ObservableObject {
    @Published var words: [String] = []
    @Published var snippets: [DictionaryStore.Snippet] = []
    @Published var error: String?

    func load() {
        do {
            words = try DictionaryStore.words().sorted()
            snippets = DictionaryStore.sortedForDisplay(try DictionaryStore.snippets())
            error = nil
        } catch {
            self.error = error.localizedDescription
        }
    }

    func addWord(_ word: String) -> Bool {
        do {
            let word = try DictionaryStore.validateWord(word)
            guard !words.contains(where: { $0.caseInsensitiveCompare(word) == .orderedSame }) else {
                error = "This word is already in your dictionary."
                return false
            }
            try DictionaryStore.addWord(word)
            load()
            Self.kickSync()
            return true
        } catch {
            self.error = error.localizedDescription
            return false
        }
    }

    func deleteWord(_ word: String) {
        do {
            try DictionaryStore.deleteWord(word)
            load()
            Self.kickSync()
        } catch {
            self.error = error.localizedDescription
        }
    }

    func saveSnippet(trigger: String, replacement: String, replacing oldTrigger: String?) {
        do {
            try DictionaryStore.putSnippet(trigger: trigger, replacement: replacement)
            if let oldTrigger, oldTrigger != trigger {
                try DictionaryStore.deleteSnippet(trigger: oldTrigger)
            }
            load()
            Self.kickSync()
        } catch {
            self.error = error.localizedDescription
        }
    }

    /// Android `kickSync`: push dictionary edits to trusted devices right away (no-op when sync is off).
    static func kickSync() {
        DispatchQueue.global(qos: .utility).async { _ = try? CoreBridge.syncAllNow() }
    }

    func deleteSnippet(trigger: String) {
        do {
            try DictionaryStore.deleteSnippet(trigger: trigger)
            load()
            Self.kickSync()
        } catch {
            self.error = error.localizedDescription
        }
    }
}

struct DictionaryView: View {
    @StateObject private var model: DictionaryViewModel

    @State private var showingSnippets = false
    @State private var newWord = ""
    @State private var wordQuery = ""
    @State private var snippetQuery = ""
    @State private var editingSnippet: DictionaryStore.Snippet?
    @State private var showingSnippetForm = false
    @State private var pendingWordDelete: String?
    @State private var pendingSnippetDelete: String?

    /// Devices lives in Settings; this row switches there instead of pushing a copy here.
    var onOpenSettings: (SettingsRoute) -> Void

    init(model: DictionaryViewModel = DictionaryViewModel(), onOpenSettings: @escaping (SettingsRoute) -> Void = { _ in }) {
        _model = StateObject(wrappedValue: model)
        self.onOpenSettings = onOpenSettings
    }

    var body: some View {
        Group {
            if showingSnippets {
                dictionaryList { snippetSections }
            } else {
                dictionaryList { wordSections }
            }
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Picker(strings.words, selection: $showingSnippets) {
                    Text("\(strings.words) · \(model.words.count)").tag(false)
                    Text("\(strings.snippets) · \(model.snippets.count)").tag(true)
                }
                .pickerStyle(.segmented)
                .frame(maxWidth: 280)
            }
        }
        .onAppear { model.load() }
        .sheet(isPresented: $showingSnippetForm) {
            SnippetForm(existing: editingSnippet, onSave: { trigger, replacement, old in
                model.saveSnippet(trigger: trigger, replacement: replacement, replacing: old)
            })
        }
        .alert("Delete this entry?", isPresented: wordDeleteBinding) {
            Button("Delete", role: .destructive) {
                if let word = pendingWordDelete { model.deleteWord(word) }
                pendingWordDelete = nil
            }
            Button("Cancel", role: .cancel) { pendingWordDelete = nil }
        } message: {
            Text("This cannot be undone. The deletion also applies to devices that sync this data.")
        }
        .alert("Delete this entry?", isPresented: snippetDeleteBinding) {
            Button("Delete", role: .destructive) {
                if let trigger = pendingSnippetDelete { model.deleteSnippet(trigger: trigger) }
                pendingSnippetDelete = nil
            }
            Button("Cancel", role: .cancel) { pendingSnippetDelete = nil }
        } message: {
            Text("This cannot be undone. The deletion also applies to devices that sync this data.")
        }
    }

    private func dictionaryList<Rows: View>(@ViewBuilder rows: () -> Rows) -> some View {
        List {
            Section {
                Button { onOpenSettings(.devices) } label: {
                    WhisperDestinationLabel(
                        title: keyboardStrings.devices,
                        summary: keyboardStrings.syncSummary,
                        symbol: "antenna.radiowaves.left.and.right"
                    )
                }
                .foregroundStyle(.primary)
            }
            if let error = model.error {
                Section {
                    Text(error).font(.subheadline).foregroundStyle(.secondary)
                }
            }
            rows()
        }
        .whisperListStyle()
    }

    @ViewBuilder
    private var wordSections: some View {
        Section {
            HStack {
                TextField("Add a word", text: $newWord)
                    .autocorrectionDisabled()
                    .submitLabel(.done)
                    .onSubmit(addWord)
                Button(action: addWord) {
                    Label("Add word", systemImage: "plus.circle.fill")
                        .labelStyle(.iconOnly)
                        .font(.title3)
                        .frame(minWidth: 44, minHeight: 44)
                }
                .buttonStyle(.borderless)
                .disabled(newWord.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
            }
        } header: {
            Text("Names and terms you say often, spelled the way you want them written.").textCase(nil)
        }

        if model.words.count > 8 || !wordQuery.isEmpty {
            Section {
                WhisperSearchField(text: $wordQuery, prompt: "Search words")
            }
        }
        Section {
            let words = model.words.filter { wordQuery.isEmpty || $0.localizedCaseInsensitiveContains(wordQuery) }
            if words.isEmpty {
                Text(wordQuery.isEmpty ? "No custom words yet." : "No matches.").foregroundStyle(.secondary)
            }
            ForEach(words, id: \.self) { word in
                HStack {
                    Text(word)
                    Spacer()
                    Button(role: .destructive) { pendingWordDelete = word } label: {
                        Label("Delete \(word)", systemImage: "trash")
                            .labelStyle(.iconOnly)
                            .frame(minWidth: 44, minHeight: 44)
                    }
                    .foregroundStyle(.secondary)
                    .buttonStyle(.borderless)
                }
            }
        }
    }

    @ViewBuilder
    private var snippetSections: some View {
        Section {
            Button {
                editingSnippet = nil
                showingSnippetForm = true
            } label: {
                WhisperAddLabel(title: "Add snippet")
            }
            .buttonStyle(.plain)
            .listRowInsets(EdgeInsets())
            .listRowBackground(Color.clear)
        } header: {
            Text("Say a short phrase and MyWhisper types the longer text for you.").textCase(nil)
        }

        if model.snippets.count > 5 || !snippetQuery.isEmpty {
            Section {
                WhisperSearchField(text: $snippetQuery, prompt: "Search snippets")
            }
        }
        Section {
            let snippets = model.snippets.filter { snippet in
                snippetQuery.isEmpty || snippet.trigger.localizedCaseInsensitiveContains(snippetQuery)
                    || snippet.replacement.localizedCaseInsensitiveContains(snippetQuery)
            }
            if snippets.isEmpty {
                Text(snippetQuery.isEmpty ? "No snippets yet." : "No matches.").foregroundStyle(.secondary)
            }
            ForEach(snippets, id: \.trigger) { snippet in
                HStack {
                    Button {
                        editingSnippet = snippet
                        showingSnippetForm = true
                    } label: {
                        VStack(alignment: .leading, spacing: 3) {
                            Text("Say “\(snippet.trigger)”").font(.subheadline.weight(.semibold)).foregroundStyle(.primary)
                            Text(snippet.replacement).font(.subheadline).foregroundStyle(.secondary).lineLimit(2)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    Button(role: .destructive) { pendingSnippetDelete = snippet.trigger } label: {
                        Label("Delete \(snippet.trigger)", systemImage: "trash")
                            .labelStyle(.iconOnly)
                            .frame(minWidth: 44, minHeight: 44)
                    }
                    .foregroundStyle(.secondary)
                }
                .buttonStyle(.borderless)
            }
        }
    }

    private func addWord() {
        guard !newWord.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        if model.addWord(newWord) { newWord = "" }
    }

    private var strings: TabStrings { .current(languageCode: languageCode) }
    private var keyboardStrings: OnboardingStrings { .current(languageCode: languageCode) }
    private var languageCode: String? { Locale.current.language.languageCode?.identifier }

    private var wordDeleteBinding: Binding<Bool> {
        Binding(get: { pendingWordDelete != nil }, set: { if !$0 { pendingWordDelete = nil } })
    }

    private var snippetDeleteBinding: Binding<Bool> {
        Binding(get: { pendingSnippetDelete != nil }, set: { if !$0 { pendingSnippetDelete = nil } })
    }
}

#Preview {
    DictionaryView()
}
