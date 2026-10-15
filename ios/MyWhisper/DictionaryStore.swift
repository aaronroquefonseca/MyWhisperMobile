import Foundation

/// Dictionary + snippets store errors (Swift-side validation; the core
/// re-validates: empty word and empty trigger/replacement fail there too).
enum DictionaryError: Error, Equatable {
    case emptyWord
    case emptyTrigger
    case emptyReplacement
}

/// Words/snippets CRUD through `CoreBridge` (store-backed, desktop
/// `app_dictionary` parity: edits hit the sync tables directly, so
/// transcription and sync see them without a restart).
///
/// Snippet expansion happens core-side (`textnorm::expand_snippets`,
/// longest-first with word boundaries): Swift only passes through and
/// validates. Display order is longest-trigger-first so the list reads the
/// way the matcher applies it.
enum DictionaryStore {
    struct Snippet: Equatable {
        let trigger: String
        let replacement: String
    }

    // MARK: - Validation (pure, no bridge)

    @discardableResult
    static func validateWord(_ word: String) throws -> String {
        let trimmed = word.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty {
            throw DictionaryError.emptyWord
        }
        return trimmed
    }

    @discardableResult
    static func validateSnippet(trigger: String, replacement: String) throws -> (String, String) {
        let trimmedTrigger = trigger.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmedTrigger.isEmpty {
            throw DictionaryError.emptyTrigger
        }
        if replacement.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            throw DictionaryError.emptyReplacement
        }
        return (trimmedTrigger, replacement)
    }

    // MARK: - Words (bridge)

    static func words() throws -> [String] {
        try CoreBridge.getWords()
    }

    static func addWord(_ word: String) throws {
        try CoreBridge.addWord(validateWord(word))
    }

    static func deleteWord(_ word: String) throws {
        try CoreBridge.deleteWord(word)
    }

    // MARK: - Snippets (bridge)

    static func snippets() throws -> [Snippet] {
        try CoreBridge.getSnippets().map { Snippet(trigger: $0.trigger, replacement: $0.replacement) }
    }

    static func putSnippet(trigger: String, replacement: String) throws {
        let valid = try validateSnippet(trigger: trigger, replacement: replacement)
        try CoreBridge.putSnippet(trigger: valid.0, replacement: valid.1)
    }

    static func deleteSnippet(trigger: String) throws {
        try CoreBridge.deleteSnippet(trigger: trigger)
    }

    // MARK: - Display order (pure, no bridge)

    /// Longest trigger first (core matcher applies longest-first, so
    /// "investor ask" lists above "ask"); ties break alphabetically for
    /// a stable list.
    static func sortedForDisplay(_ snippets: [Snippet]) -> [Snippet] {
        snippets.sorted {
            if $0.trigger.count != $1.trigger.count {
                return $0.trigger.count > $1.trigger.count
            }
            return $0.trigger < $1.trigger
        }
    }
}
