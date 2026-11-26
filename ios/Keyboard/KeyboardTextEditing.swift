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
        // UTF-16 offset of each character, plus the end: CoreText counts UTF-16.
        var offsets = [0]
        for character in paragraph { offsets.append(offsets[offsets.count - 1] + String(character).utf16.count) }
        var breaks = [0]
        var nextBreak = CTTypesetterSuggestLineBreak(typesetter, 0, Double(lineWidth))
        guard nextBreak > 0, nextBreak < length else { return nil }
        for characterIndex in 1..<offsets.count {
            let characterEnd = offsets[characterIndex]
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
        func rowEnd(_ row: Int) -> Int { breaks.indices.contains(row + 1) ? breaks[row + 1] - 1 : paragraph.count }
        func line(_ row: Int) -> CTLine {
            CTTypesetterCreateLine(typesetter, CFRange(location: offsets[breaks[row]], length: offsets[rowEnd(row)] - offsets[breaks[row]]))
        }
        // Keep the caret's horizontal position, like the system keyboard: in a proportional font
        // the same character count lands somewhere else on the next row.
        let source = breaks[row] + min(preferredColumn ?? caret - breaks[row], rowEnd(row) - breaks[row])
        let x = CTLineGetOffsetForStringIndex(line(row), offsets[source], nil)
        let hit = CTLineGetStringIndexForPosition(line(target), CGPoint(x: x, y: 0))
        let landing = hit == kCFNotFound ? rowEnd(target) : offsets.firstIndex { $0 >= hit } ?? rowEnd(target)
        let column = min(max(landing, breaks[target]), rowEnd(target)) - breaks[target]
        return (breaks[target] + column - caret, column)
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
    /// Finger travel per caret step at slow speed: wider than a glyph so slow drags land exactly.
    static let characterWidth: CGFloat = 12
    static let lineHeight: CGFloat = 25
    /// A step fires at 75% of its width and leaves the rest as a 6 pt band, so lift-off
    /// jitter and tiny reversals don't nudge the caret, but backing up one step is quick.
    private static let trigger: CGFloat = 0.75

    var preferredColumn: Int?
    private var last: CGPoint
    private var lastTime: TimeInterval?
    private var speed: CGFloat = 0
    /// Travel not yet spent on a step, per axis.
    private var x: CGFloat = 0
    private var y: CGFloat = 0

    init(origin: CGPoint) {
        last = origin
    }

    /// `time` enables acceleration for fast sweeps (up to 3x); nil keeps 1:1.
    mutating func steps(towards finger: CGPoint, at time: TimeInterval? = nil) -> (characters: Int, lines: Int) {
        let dx = finger.x - last.x
        let dy = finger.y - last.y
        last = finger
        var gain: CGFloat = 1
        if let time, let lastTime, time > lastTime {
            speed = speed * 0.7 + abs(dx) / CGFloat(time - lastTime) * 0.3
            gain = 1 + min(max((speed - 300) / 450, 0), 2)
        }
        if let time { lastTime = time }
        // Each movement counts toward the axis it mostly follows: a horizontal drag that
        // drifts never jumps lines, and a vertical one doesn't creep sideways.
        if abs(dy) > abs(dx) {
            y += dy
        } else {
            x += dx * gain
            let bleed = min(abs(y), abs(dx) * 0.5)
            y += y < 0 ? bleed : -bleed
        }
        return (Self.spend(&x, step: Self.characterWidth), Self.spend(&y, step: Self.lineHeight))
    }

    private static func spend(_ travel: inout CGFloat, step: CGFloat) -> Int {
        var count = 0
        while travel > step * trigger {
            travel -= step
            count += 1
        }
        while travel < -step * trigger {
            travel += step
            count -= 1
        }
        return count
    }
}
