import CoreText
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

    func testWrappedRowsAllowMovementWithoutNewlines() throws {
        let font = CTFontCreateWithName("Menlo" as CFString, 17, nil)
        let text = String(repeating: "short words fit here ", count: 6)
        let before = String(text.prefix(4))
        let after = String(text.dropFirst(4))
        let down = try XCTUnwrap(KeyboardTextEditing.wrappedCursorMove(
            before: before, after: after, lines: 1, preferredColumn: nil, lineWidth: 216, font: font
        ))
        XCTAssertGreaterThan(down.offset, 4)
        XCTAssertEqual(down.column, 4)
        let moved = 4 + down.offset
        let up = KeyboardTextEditing.wrappedCursorMove(
            before: String(text.prefix(moved)), after: String(text.dropFirst(moved)),
            lines: -1, preferredColumn: down.column, lineWidth: 216, font: font
        )
        XCTAssertEqual(up?.offset, -down.offset)
    }

    @MainActor
    func testVerticalDragCrossesParagraphWhenHostHidesNewline() {
        let controller = TestKeyboardController()
        controller.document.text = "one\ntwo\nthree"
        controller.document.caret = 5
        controller.document.hidesNewlineContext = true
        controller.startCursorDrag(at: .zero)
        controller.continueCursorDrag(to: CGPoint(x: 0, y: -25))
        XCTAssertEqual(controller.document.caret, 3)
        controller.continueCursorDrag(to: .zero)
        XCTAssertEqual(controller.document.caret, 4)
        controller.finishCursorDrag()
    }

    @MainActor
    func testVerticalDragLeavesBlankLineWithNoExposedContext() {
        let controller = TestKeyboardController()
        controller.document.text = "one\n\nthree"
        controller.document.caret = 4
        controller.document.hidesNewlineContext = true
        controller.startCursorDrag(at: .zero)
        controller.continueCursorDrag(to: CGPoint(x: 0, y: 25))
        XCTAssertEqual(controller.document.caret, 5)
        controller.finishCursorDrag()
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
    }

    func testKeyboardDictationMailboxExpiresAndMatchesTheSession() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
        defer { try? FileManager.default.removeItem(at: directory) }

        let mailbox = KeyboardDictationMailbox(directory: directory)
        let now = Date(timeIntervalSinceReferenceDate: 100)
        let session = KeyboardDictationMailbox.Session(id: UUID(), heartbeat: now)
        try mailbox.publish(session)
        let recovered = try XCTUnwrap(mailbox.session)
        XCTAssertTrue(recovered.isAvailable(at: now))
        XCTAssertFalse(recovered.isAvailable(at: now.addingTimeInterval(5)))

        let command = KeyboardDictationMailbox.Request(id: UUID(), session: session.id, action: .start, created: now)
        try mailbox.send(command)
        let request = try XCTUnwrap(mailbox.request)
        XCTAssertTrue(request.belongs(to: session.id, at: now))
        XCTAssertFalse(request.belongs(to: UUID(), at: now))
        XCTAssertFalse(request.belongs(to: session.id, at: now.addingTimeInterval(5)))

        try mailbox.respond(.init(id: request.id, phase: .result, text: "Hello"))
        XCTAssertEqual(mailbox.reply?.id, request.id)
        XCTAssertEqual(mailbox.reply?.text, "Hello")
        mailbox.clearReply()
        XCTAssertNil(mailbox.reply)
    }

    @MainActor
    func testSpaceReturnsToLettersFromBothSymbolPages() throws {
        let controller = TestKeyboardController()
        controller.loadViewIfNeeded()
        let input = try XCTUnwrap(controller.inputView)
        input.frame = CGRect(x: 0, y: 0, width: 390, height: 260)
        controller.viewWillAppear(false)
        defer { controller.viewDidDisappear(false) }
        XCTAssertEqual(input.backgroundColor, UIColor.clear)
        XCTAssertFalse(input.isOpaque)
        let area = try XCTUnwrap(input.subviews.first { $0 is KeyboardKeyArea })

        func tap(_ label: String) throws {
            let button = area.subviews.compactMap { $0 as? UIButton }.first {
                $0.title(for: .normal) == label || $0.accessibilityLabel == label
            }
            try XCTUnwrap(button).sendActions(for: .touchUpInside)
        }

        try tap("123")
        try tap("4")
        try tap("Space")
        XCTAssertEqual(controller.document.text, "4 ")

        try tap("123")
        try tap("#+=")
        try tap("~")
        try tap("Space")
        try tap("q")
        XCTAssertEqual(controller.document.text, "4 ~ q")
    }

    func testMicPreviewMatchesSmartModeReleases() {
        var mic = KeyboardMicPreviewState()
        mic.release(after: 0.07)
        XCTAssertEqual(mic.phase, .locked)
        mic.release(after: 0.06)
        XCTAssertEqual(mic.phase, .processing)
        mic.release(after: 0.06)
        XCTAssertEqual(mic.phase, .idle)

        mic.hold()
        mic.release(after: 0.3)
        XCTAssertEqual(mic.phase, .processing)
        mic.reset()

        mic.hold()
        mic.move(vertical: -50)
        mic.release(after: 0.4)
        XCTAssertEqual(mic.phase, .locked)
        mic.reset()

        mic.hold()
        mic.move(vertical: 50)
        mic.release(after: 0.4)
        XCTAssertEqual(mic.phase, .idle)
    }

    @MainActor
    func testKeyboardOwnsDictationAndTypingResetsThePreview() throws {
        let controller = TestKeyboardController()
        controller.loadViewIfNeeded()
        let input = try XCTUnwrap(controller.inputView)
        input.frame = CGRect(x: 0, y: 0, width: 390, height: 260)
        controller.viewWillAppear(false)
        defer { controller.viewDidDisappear(false) }

        XCTAssertTrue(controller.hasDictationKey)
        let area = try XCTUnwrap(input.subviews.first { $0 is KeyboardKeyArea })
        let preview = try XCTUnwrap(area.subviews.first { $0 is KeyboardMicPreview })
        let mic = try XCTUnwrap(preview.subviews.first { $0.accessibilityLabel == "MyWhisper dictation preview" })
        XCTAssertTrue(mic.accessibilityActivate())
        XCTAssertEqual(mic.accessibilityValue, "Hands-free. Activate to finish")
        XCTAssertTrue(controller.document.text.isEmpty)
        let letter = try XCTUnwrap(area.subviews.compactMap { $0 as? UIButton }.first { $0.title(for: .normal)?.lowercased() == "a" })
        letter.sendActions(for: .touchUpInside)
        XCTAssertEqual(controller.document.text.lowercased(), "a")
        XCTAssertEqual(mic.accessibilityValue, "Ready")
        XCTAssertTrue(controller.hasDictationKey)
    }

    @MainActor
    func testEmojiDrawerScrollsAcrossCategoriesAndUpdatesTheFooter() throws {
        let picker = KeyboardEmojiPicker(
            frame: CGRect(x: 0, y: 0, width: 380, height: 254),
            accent: .systemTeal, ink: .white, footerHeight: 44,
            letters: UIButton(), delete: UIButton(), globe: nil, onInsert: { _ in }
        )
        picker.layoutIfNeeded()
        let grid = try XCTUnwrap(picker.subviews.compactMap { $0 as? UICollectionView }.first)
        let footer = try XCTUnwrap(picker.subviews.compactMap { $0 as? UIScrollView }.first { $0 !== grid })
        XCTAssertGreaterThan(grid.numberOfSections, 3)

        let firstFaces = try XCTUnwrap(grid.layoutAttributesForItem(at: IndexPath(item: 0, section: 1)))
        let firstPeople = try XCTUnwrap(grid.layoutAttributesForItem(at: IndexPath(item: 0, section: 2)))
        XCTAssertGreaterThan(firstPeople.frame.minX, firstFaces.frame.minX)
        grid.setContentOffset(CGPoint(x: firstPeople.frame.minX - 1, y: 0), animated: false)
        picker.scrollViewDidScroll(grid)
        let people = try XCTUnwrap(footer.subviews.first { $0.accessibilityLabel == "People & Body" })
        XCTAssertTrue(people.accessibilityTraits.contains(.selected))

        let animals = try XCTUnwrap(footer.subviews.compactMap { $0 as? UIButton }.first { $0.accessibilityLabel == "Animals & Nature" })
        animals.sendActions(for: .touchUpInside)
        XCTAssertGreaterThan(grid.contentOffset.x, firstPeople.frame.minX)
        XCTAssertTrue(animals.accessibilityTraits.contains(.selected))
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
    var hidesNewlineContext = false
    var documentContextBeforeInput: String? {
        let before = text[..<insertionIndex]
        guard hidesNewlineContext else { return String(before) }
        return String(before.reversed().prefix { !$0.isNewline }.reversed())
    }
    var documentContextAfterInput: String? {
        guard insertionIndex != text.endIndex else { return nil }
        let after = text[insertionIndex...]
        return hidesNewlineContext ? String(after.prefix { !$0.isNewline }) : String(after)
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
