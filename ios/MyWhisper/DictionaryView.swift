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

    func addWord(_ word: String) {
        do {
            try DictionaryStore.addWord(word)
            load()
        } catch {
            self.error = error.localizedDescription
        }
    }

    func deleteWord(_ word: String) {
        do {
            try DictionaryStore.deleteWord(word)
            load()
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
        } catch {
            self.error = error.localizedDescription
        }
    }

    func deleteSnippet(trigger: String) {
        do {
            try DictionaryStore.deleteSnippet(trigger: trigger)
            load()
        } catch {
            self.error = error.localizedDescription
        }
    }
}

struct DictionaryView: View {
    @StateObject private var model: DictionaryViewModel

    @State private var showingWordForm = false
    @State private var editingSnippet: DictionaryStore.Snippet?
    @State private var showingSnippetForm = false
    @State private var pendingWordDelete: String?
    @State private var pendingSnippetDelete: String?

    init(model: DictionaryViewModel = DictionaryViewModel()) {
        _model = StateObject(wrappedValue: model)
    }

    var body: some View {
        List {
            if let error = model.error {
                Section {
                    Text(error).foregroundStyle(.red).font(.caption)
                }
            }
            Section("Words") {
                if model.words.isEmpty {
                    Text("No custom words yet. Words steer transcription toward your spellings.")
                        .foregroundStyle(.secondary)
                }
                ForEach(model.words, id: \.self) { word in
                    HStack {
                        Text(word)
                        Spacer()
                        Button("Delete", role: .destructive) { pendingWordDelete = word }
                            .font(.caption)
                            .buttonStyle(.borderless)
                    }
                    .accessibilityLabel("Word \(word)")
                }
                Button("Add word") { showingWordForm = true }
            }
            Section("Snippets") {
                if model.snippets.isEmpty {
                    Text("No snippets yet. A trigger expands to its replacement, longest match wins.")
                        .foregroundStyle(.secondary)
                }
                ForEach(model.snippets, id: \.trigger) { snippet in
                    VStack(alignment: .leading, spacing: 2) {
                        Text(snippet.trigger).font(.body)
                        Text("→ \(snippet.replacement)").font(.caption).foregroundStyle(.secondary)
                        HStack(spacing: 12) {
                            Button("Edit") {
                                editingSnippet = snippet
                                showingSnippetForm = true
                            }
                            Button("Delete", role: .destructive) { pendingSnippetDelete = snippet.trigger }
                        }
                        .font(.caption)
                        .buttonStyle(.borderless)
                    }
                    .accessibilityElement(children: .combine)
                    .accessibilityLabel("Snippet \(snippet.trigger) expands to \(snippet.replacement)")
                }
                Button("Add snippet") {
                    editingSnippet = nil
                    showingSnippetForm = true
                }
            }
            Section {
                NavigationLink {
                    DevicesView()
                } label: {
                    Label(OnboardingStrings.current(languageCode: languageCode).devices,
                          systemImage: "antenna.radiowaves.left.and.right")
                }
            }
        }
        .navigationTitle(TabStrings.current(languageCode: languageCode).dictionary)
        .onAppear { model.load() }
        .sheet(isPresented: $showingWordForm) {
            WordForm(onSave: { model.addWord($0) })
        }
        .sheet(isPresented: $showingSnippetForm) {
            SnippetForm(existing: editingSnippet, onSave: { trigger, replacement, old in
                model.saveSnippet(trigger: trigger, replacement: replacement, replacing: old)
            })
        }
        .alert("Delete word?", isPresented: wordDeleteBinding) {
            Button("Delete", role: .destructive) {
                if let word = pendingWordDelete { model.deleteWord(word) }
                pendingWordDelete = nil
            }
            Button("Cancel", role: .cancel) { pendingWordDelete = nil }
        } message: {
            Text("Transcription stops preferring this spelling.")
        }
        .alert("Delete snippet?", isPresented: snippetDeleteBinding) {
            Button("Delete", role: .destructive) {
                if let trigger = pendingSnippetDelete { model.deleteSnippet(trigger: trigger) }
                pendingSnippetDelete = nil
            }
            Button("Cancel", role: .cancel) { pendingSnippetDelete = nil }
        } message: {
            Text("This trigger stops expanding.")
        }
    }

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
