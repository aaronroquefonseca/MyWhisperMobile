import UIKit
import XCTest

final class KeyboardSwipeTests: XCTestCase {
    private let pitch: CGFloat = 38
    private var centers: [UInt8: CGPoint] {
        let rows = [("qwertyuiop", 0.0), ("asdfghjkl", 0.5), ("zxcvbnm", 1.4)]
        var result: [UInt8: CGPoint] = [:]
        for (row, entry) in rows.enumerated() {
            for (column, letter) in entry.0.utf8.enumerated() {
                result[letter] = CGPoint(x: (CGFloat(column) + entry.1) * pitch, y: CGFloat(row) * 54)
            }
        }
        return result
    }

    private func trace(_ keys: String) -> [CGPoint] {
        keys.utf8.compactMap { centers[$0] }
    }

    func testDecodesRepeatedLettersFromSingleVisit() {
        let lexicon = KeyboardSwipeLexicon("170\thello\n150\thelp\n150\theld\n175\thero\n")
        let words = KeyboardSwipeDecoder.candidates(trace: trace("helo"), centers: centers, pitch: pitch, lexicon: lexicon)
        XCTAssertEqual(words.first, "hello")
    }

    func testSpanishAccentsKeepOriginalSpelling() {
        let lexicon = KeyboardSwipeLexicon("165\ttambién\n140\ttambor\n80\ttabón\n")
        let words = KeyboardSwipeDecoder.candidates(trace: trace("tambien"), centers: centers, pitch: pitch, lexicon: lexicon)
        XCTAssertEqual(words.first, "también")
    }

    func testSpanishSwipeUsesPhysicalEnyeKey() throws {
        let enye = try XCTUnwrap(KeyboardSwipeLexicon.key(for: "ñ"))
        var spanishCenters = centers
        spanishCenters[enye] = CGPoint(x: 9 * pitch, y: 54)
        let path = try "año".map { letter in
            try XCTUnwrap(spanishCenters[try XCTUnwrap(KeyboardSwipeLexicon.key(for: letter))])
        }
        let lexicon = KeyboardSwipeLexicon("165\taño\n140\tano\n")
        let words = KeyboardSwipeDecoder.candidates(trace: path, centers: spanishCenters, pitch: pitch, lexicon: lexicon)
        XCTAssertEqual(words.first, "año")
    }

    func testPreferencesTransferPreservesLanguageOrderAndToggles() throws {
        var settings = KeyboardPreferences()
        for language in KeyboardLanguage.allCases { settings.setLanguage(language, enabled: true) }
        settings.defaultLanguage = .spanish
        settings.autoCorrection = false
        settings.switchesLanguageBySwipe = false

        let imported = try XCTUnwrap(KeyboardPreferences(transferText: settings.transferText))
        XCTAssertEqual(imported.languages, [.englishUS, .englishUK, .spanish])
        XCTAssertEqual(imported.defaultLanguage, .spanish)
        XCTAssertFalse(imported.autoCorrection)
        XCTAssertFalse(imported.switchesLanguageBySwipe)
        XCTAssertEqual(imported.language(after: .spanish, movingRight: false), .englishUS)
        XCTAssertEqual(imported.language(after: .englishUS, movingRight: true), .spanish)
        XCTAssertNil(KeyboardPreferences(transferText: settings.transferText + "0"))
        XCTAssertNil(KeyboardPreferences(transferText: "MyWhisper-K1-0"))

        settings.setLanguage(.spanish, enabled: false)
        settings.setLanguage(.englishUK, enabled: false)
        settings.setLanguage(.englishUS, enabled: false)
        XCTAssertEqual(settings.languages, [.englishUS])
        XCTAssertEqual(settings.defaultLanguage, .englishUS)
    }

    func testSentenceBoundariesHandlePunctuationQuotesAndNewlines() {
        for boundary in ["", "  ", "Done.", "Done. ", "Really?” ", "Done!\n  ", "¿Listo?"] {
            XCTAssertTrue(KeyboardTextEditing.startsSentence(after: boundary), boundary)
        }
        for continuation in ["Hello", "Hello ", "3.14", "él dijo “hola", "note: "] {
            XCTAssertFalse(KeyboardTextEditing.startsSentence(after: continuation), continuation)
        }
    }

    func testEndpointsCanLandBetweenKeysAndDuplicatesAreSafe() {
        let lexicon = KeyboardSwipeLexicon("165\tword\n150\tward\n140\twork\n")
        var path = trace("word")
        path[0].x += 14
        path[3].x -= 9
        path.insert(path[1], at: 1)
        let words = KeyboardSwipeDecoder.candidates(trace: path, centers: centers, pitch: pitch, lexicon: lexicon)
        XCTAssertEqual(words.first, "word")
    }

    func testRejectsTapAndOutOfBoundsExit() {
        let lexicon = KeyboardSwipeLexicon("170\thello\n150\thelp\n")
        XCTAssertTrue(KeyboardSwipeDecoder.candidates(trace: trace("hh"), centers: centers, pitch: pitch, lexicon: lexicon).isEmpty)
        XCTAssertTrue(KeyboardSwipeDecoder.candidates(trace: trace("hel") + [CGPoint(x: 240, y: 230)],
                                                      centers: centers, pitch: pitch, lexicon: lexicon).isEmpty)
    }

    func testPackagedWordListsRecognizeEverydayEnglishAndSpanish() throws {
        let plugIns = try XCTUnwrap(Bundle.main.builtInPlugInsURL)
        let keyboard = try XCTUnwrap(Bundle(url: plugIns.appendingPathComponent("MyWhisperKeyboard.appex")))
        let cases = [
            ("en_GB", "swipe", "swipe"),
            ("en_US", "typing", "typing"),
            ("es", "tambien", "también"),
        ]
        for (locale, keys, expected) in cases {
            let url = try XCTUnwrap(keyboard.url(forResource: "swipe-" + locale, withExtension: "txt"))
            let lexicon = KeyboardSwipeLexicon(try String(contentsOf: url, encoding: .utf8))
            let pauses = expected == "typing" ? trace("y") : []
            let candidates = KeyboardSwipeDecoder.candidates(trace: trace(keys), pauses: pauses,
                                                             centers: centers, pitch: pitch, lexicon: lexicon)
            XCTAssertEqual(candidates.first, expected, locale)
        }
    }

    func testWordDeletionHandlesAccentsAndContractionsWithoutCrossingLines() {
        XCTAssertEqual(KeyboardTextEditing.trailingWordDeletionCount(in: "hello don't  "), 7)
        XCTAssertEqual(KeyboardTextEditing.trailingWordDeletionCount(in: "también"), 7)
        XCTAssertEqual(KeyboardTextEditing.trailingWordDeletionCount(in: "hello world! "), 7)
        XCTAssertEqual(KeyboardTextEditing.trailingWordDeletionCount(in: "line\n  "), 2)
        XCTAssertEqual(KeyboardTextEditing.trailingWordDeletionCount(in: "line\n"), 0)
        XCTAssertEqual(KeyboardTextEditing.trailingWordDeletionCount(in: ""), 0)
    }

    func testVerticalMovementHandlesEmptyLinesAndGraphemes() {
        let down = KeyboardTextEditing.verticalCursorMove(
            before: "a👩🏽‍💻", after: "z\n\ne\u{301}bc", lines: 2, preferredColumn: nil
        )
        XCTAssertEqual(down?.offset, 5)
        XCTAssertEqual(down?.column, 2)
        let up = KeyboardTextEditing.verticalCursorMove(before: "abc\r\n", after: "", lines: -1, preferredColumn: nil)
        XCTAssertEqual(up?.offset, -4)
        XCTAssertNil(KeyboardTextEditing.verticalCursorMove(before: "ab", after: "cd", lines: 1, preferredColumn: nil))
    }

    func testCursorDragConsumesSmallHorizontalWobbleDuringVerticalMotion() {
        var drag = KeyboardCursorDrag(origin: .zero)
        for index in 1...4 {
            let steps = drag.steps(towards: CGPoint(x: index * 5, y: index * 25))
            XCTAssertEqual(steps.characters, 0)
            XCTAssertEqual(steps.lines, 1)
        }
        let steps = drag.steps(towards: CGPoint(x: 44, y: 100))
        XCTAssertEqual(steps.characters, 2)
        XCTAssertEqual(steps.lines, 0)
    }

    @MainActor
    func testCursorDragMovesThroughProxyAndRestoresColumnAfterShortLine() {
        let controller = TestKeyboardController()
        controller.document.text = "abcdef\nxy\nuvwxyz"
        controller.document.caret = 4
        controller.startCursorDrag(at: .zero)

        controller.continueCursorDrag(to: CGPoint(x: 0, y: 25))
        XCTAssertEqual(controller.document.caret, 9)
        controller.continueCursorDrag(to: CGPoint(x: 0, y: 50))
        XCTAssertEqual(controller.document.caret, 14)
        controller.continueCursorDrag(to: .zero)
        XCTAssertEqual(controller.document.caret, 4)
        controller.continueCursorDrag(to: CGPoint(x: 24, y: 0))
        XCTAssertEqual(controller.document.caret, 6)
        controller.finishCursorDrag()

        // Some hosts return nil for the context after the end of a field.
        controller.document.caret = controller.document.text.count
        controller.startCursorDrag(at: .zero)
        controller.continueCursorDrag(to: CGPoint(x: 0, y: -25))
        XCTAssertEqual(controller.document.caret, 9)
        controller.finishCursorDrag()
    }

    @MainActor
    func testFieldTraitsUpdateShortcutsAndCapitalization() throws {
        let controller = TestKeyboardController()
        controller.loadViewIfNeeded()
        let input = try XCTUnwrap(controller.inputView)
        input.frame = CGRect(x: 0, y: 0, width: 390, height: 260)
        controller.viewWillAppear(false)
        defer { controller.viewDidDisappear(false) }
        let area = try XCTUnwrap(input.subviews.first { $0 is KeyboardKeyArea })

        func key(_ title: String) -> UIButton? {
            area.subviews.compactMap { $0 as? KeyboardKeyButton }.first { $0.title(for: .normal) == title }
        }

        XCTAssertNotNil(key("A"))
        XCTAssertNil(key("@"))
        controller.document.returnKeyType = .search
        controller.textDidChange(nil)
        XCTAssertNotNil(key("@"))
        controller.document.text = "Hello"
        controller.textDidChange(nil)
        try XCTUnwrap(key(".")).sendActions(for: .touchUpInside)
        XCTAssertEqual(controller.document.text, "Hello.")
        XCTAssertNotNil(key("A"))

        controller.document.keyboardType = .URL
        controller.document.returnKeyType = .go
        controller.textDidChange(nil)
        XCTAssertNotNil(key("."))
        XCTAssertNotNil(key("/"))
        XCTAssertNotNil(key("a"))

        controller.document.keyboardType = .emailAddress
        controller.textDidChange(nil)
        XCTAssertNotNil(key("@"))
        XCTAssertNil(key("/"))
        XCTAssertNotNil(area.subviews.first { $0.accessibilityLabel == "MyWhisper dictation" })
    }

    @MainActor
    func testSwipeInsertionAlternativesAndPunctuationUseKeyboardProxy() throws {
        let controller = TestKeyboardController()
        controller.loadViewIfNeeded()
        let input = try XCTUnwrap(controller.inputView)
        input.frame = CGRect(x: 0, y: 0, width: 390, height: 260)
        input.setNeedsLayout()
        input.layoutIfNeeded()
        controller.viewWillAppear(false)

        controller.insertSwipeCandidates(["hello", "held"])
        XCTAssertEqual(controller.document.text, "Hello")
        let area = try XCTUnwrap(input.subviews.first { $0 is KeyboardKeyArea })
        let alternative = try XCTUnwrap(area.subviews.compactMap { $0 as? UIButton }.first { $0.title(for: .normal) == "Held" })
        alternative.sendActions(for: .touchUpInside)
        XCTAssertEqual(controller.document.text, "Held")

        controller.insertSwipeCandidates(["there"])
        XCTAssertEqual(controller.document.text, "Held there")
        let delete = try XCTUnwrap(area.subviews.compactMap { $0 as? UIButton }.first { $0.accessibilityLabel == "Delete" })
        delete.sendActions(for: .touchDown)
        delete.sendActions(for: .touchUpInside)
        XCTAssertEqual(controller.document.text, "Held ")

        controller.insertSwipeCandidates(["there"])
        let symbols = try XCTUnwrap(area.subviews.compactMap { $0 as? UIButton }.first { $0.title(for: .normal) == "123" })
        symbols.sendActions(for: .touchUpInside)
        let punctuation = try XCTUnwrap(area.subviews.compactMap { $0 as? UIButton }.first { $0.title(for: .normal) == "." })
        punctuation.sendActions(for: .touchUpInside)
        XCTAssertEqual(controller.document.text, "Held there.")
        controller.document.text = "Held there"
        controller.deleteWord()
        XCTAssertEqual(controller.document.text, "Held ")
        controller.viewDidDisappear(false)
    }
}

@MainActor
private final class TestKeyboardController: KeyboardViewController {
    let document = TestKeyboardDocument()
    override var textDocumentProxy: any UITextDocumentProxy { document }
}

@MainActor
private final class TestKeyboardDocument: NSObject, UITextDocumentProxy {
    var text = ""
    var caret: Int?
    private var insertionIndex: String.Index { text.index(text.startIndex, offsetBy: caret ?? text.count) }
    var hasText: Bool { !text.isEmpty }
    var documentContextBeforeInput: String? { String(text[..<insertionIndex]) }
    var documentContextAfterInput: String? {
        insertionIndex == text.endIndex ? nil : String(text[insertionIndex...])
    }
    var selectedText: String? { nil }
    var documentIdentifier = UUID()
    var keyboardType: UIKeyboardType = .default
    var returnKeyType: UIReturnKeyType = .default
    var documentInputMode: UITextInputMode? { nil }
    func insertText(_ string: String) { text += string }
    func deleteBackward() { if !text.isEmpty { text.removeLast() } }
    func adjustTextPosition(byCharacterOffset offset: Int) {
        caret = min(max(0, (caret ?? text.count) + offset), text.count)
    }
    func setMarkedText(_ markedText: String, selectedRange: NSRange) {}
    func unmarkText() {}
}
