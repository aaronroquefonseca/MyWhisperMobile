import CoreText
import Foundation

enum KeyboardTextEditing {
    static func startsSentence(after text: String) -> Bool {
        let meaningful = text.reversed().drop(while: { $0.isWhitespace && !$0.isNewline })
        let punctuation = meaningful.drop(while: { "\"'’”»)]}".contains($0) })
        guard let last = punctuation.first else { return meaningful.isEmpty }
        return last.isNewline || ".!?…".contains(last)
    }

    /// Resolve vertical steps using only the nearby text the host supplies.
    /// A newline advances the row; wrapped visual rows are unavailable.
    static func verticalCursorMove(
        before: String, after: String, lines: Int, preferredColumn: Int?
    ) -> (offset: Int, column: Int)? {
        guard lines != 0 else { return nil }
        let preceding = before.suffix(2048)
        let following = after.prefix(2048)
        let caret = preceding.count
        var lineStarts = [0]
        var position = 0
        for context in [preceding, following] {
            for character in context {
                position += 1
                if character.isNewline { lineStarts.append(position) }
            }
        }

        guard let row = lineStarts.lastIndex(where: { $0 <= caret }) else { return nil }
        let destination = row + min(max(lines, -row), lineStarts.count - 1 - row)
        guard destination != row else { return nil }

        let column = preferredColumn ?? caret - lineStarts[row]
        let destinationStart = lineStarts[destination]
        let destinationEnd = destination + 1 < lineStarts.count ? lineStarts[destination + 1] - 1 : position
        let offset = destinationStart + min(column, destinationEnd - destinationStart) - caret
        return (offset, column)
    }

    /// The proxy exposes characters but no host geometry. Estimate visually
    /// wrapped rows from the current paragraph when there is no explicit break.
    static func wrappedCursorMove(
        before: String, after: String, lines: Int, preferredColumn: Int?,
        lineWidth: CGFloat, font: CTFont
    ) -> (offset: Int, column: Int)? {
        let preceding = String(before.suffix(512).reversed().prefix { !$0.isNewline }.reversed())
        let following = String(after.prefix(512).prefix { !$0.isNewline })
        let paragraph = preceding + following
        guard !paragraph.isEmpty else { return nil }
        let attributes = [NSAttributedString.Key(kCTFontAttributeName as String): font]
        let text = NSAttributedString(string: paragraph, attributes: attributes)
        let typesetter = CTTypesetterCreateWithAttributedString(text as CFAttributedString)
        let length = paragraph.utf16.count
        var breaks = [0]
        var nextBreak = CTTypesetterSuggestLineBreak(typesetter, 0, Double(lineWidth))
        guard nextBreak > 0, nextBreak < length else { return nil }
        var characterIndex = 0
        var characterEnd = 0
        for character in paragraph {
            characterEnd += String(character).utf16.count
            characterIndex += 1
            guard characterEnd >= nextBreak, nextBreak < length else { continue }
            breaks.append(characterIndex)
            let span = CTTypesetterSuggestLineBreak(typesetter, characterEnd, Double(lineWidth))
            guard span > 0 else { break }
            nextBreak = characterEnd + span
        }

        let caret = preceding.count
        guard let row = breaks.lastIndex(where: { $0 <= caret }) else { return nil }
        let target = min(max(row + lines, 0), breaks.count - 1)
        guard row != target else { return nil }
        let column = preferredColumn ?? caret - breaks[row]
        let end = breaks.indices.contains(target + 1) ? breaks[target + 1] - 1 : paragraph.count
        return (breaks[target] + min(column, end - breaks[target]) - caret, column)
    }

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

struct KeyboardCursorDrag {
    private(set) var origin: CGPoint
    var preferredColumn: Int?
    /// Finger x after acceleration: slow drags stay precise, fast flicks cover long lines.
    private var travelX: CGFloat
    private var last: (x: CGFloat, time: TimeInterval)?
    private var speed: CGFloat = 0

    init(origin: CGPoint) {
        self.origin = origin
        travelX = origin.x
    }

    /// `time` enables gentle acceleration (up to 2x above ~250 pt/s); nil keeps 1:1.
    mutating func steps(towards finger: CGPoint, at time: TimeInterval? = nil) -> (characters: Int, lines: Int) {
        var gain: CGFloat = 1
        if let time, let last, time > last.time {
            let instant = abs(finger.x - last.x) / CGFloat(time - last.time)
            speed = speed == 0 ? instant : speed * 0.6 + instant * 0.4
            gain = 1 + min(max((speed - 250) / 400, 0), 1)
        }
        travelX += (finger.x - (last?.x ?? origin.x)) * gain
        last = (finger.x, time ?? 0)
        let point = CGPoint(x: travelX, y: finger.y)
        let dx = point.x - origin.x
        let dy = point.y - origin.y
        // 10 pt per character at slow speed; acceleration widens it for fast drags.
        let characters = Int(dx / 10)
        // Vertical motion needs a larger threshold, and must dominate, so a horizontal
        // drag that drifts up or down never jumps lines.
        let lines = abs(dy) >= abs(dx) * 0.6 ? Int(dy / 25) : 0
        origin.x += CGFloat(characters) * 10
        origin.y += CGFloat(lines) * 25
        if characters != 0 && lines == 0 && abs(dx) > abs(dy) * 2 {
            origin.y = point.y
        } else if lines != 0 && characters == 0 && abs(dy) > abs(dx) * 2 {
            origin.x = point.x
        }
        return (characters, lines)
    }
}
