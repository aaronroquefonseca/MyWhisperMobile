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
    var hasText: Bool { !text.isEmpty }
    var documentContextBeforeInput: String? { text }
    var documentContextAfterInput: String? { "" }
    var selectedText: String? { nil }
    var documentIdentifier = UUID()
    var documentInputMode: UITextInputMode? { nil }
    func insertText(_ string: String) { text += string }
    func deleteBackward() { if !text.isEmpty { text.removeLast() } }
    func adjustTextPosition(byCharacterOffset offset: Int) {}
    func setMarkedText(_ markedText: String, selectedRange: NSRange) {}
    func unmarkText() {}
}
