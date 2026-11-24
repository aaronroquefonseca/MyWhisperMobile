enum KeyboardTextEditing {
    static func trailingWordDeletionCount(in text: String) -> Int {
        let reversed = text.reversed()
        var index = reversed.startIndex
        var count = 0
        while index != reversed.endIndex, reversed[index].isWhitespace, !reversed[index].isNewline {
            count += 1
            reversed.formIndex(after: &index)
        }
        while index != reversed.endIndex, ".,:;!?)]}”".contains(reversed[index]) {
            count += 1
            reversed.formIndex(after: &index)
        }
        while index != reversed.endIndex {
            let character = reversed[index]
            guard character.isLetter || character.isNumber || character == "'" || character == "’" else { break }
            count += 1
            reversed.formIndex(after: &index)
        }
        if count > 0 { return count }
        return text.last.map { $0.isNewline ? 0 : 1 } ?? 0
    }
}
