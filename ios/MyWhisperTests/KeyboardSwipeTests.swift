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

    func testCursorDragIgnoresDriftAndSmallReversals() {
        var drag = KeyboardCursorDrag(origin: .zero)
        var characters = 0
        // 120 pt to the right while drifting 30 pt down: ten characters, no line jump.
        for index in 1...40 {
            let steps = drag.steps(towards: CGPoint(x: CGFloat(index) * 3, y: CGFloat(index) * 0.75))
            characters += steps.characters
            XCTAssertEqual(steps.lines, 0)
        }
        XCTAssertEqual(characters, 10)
        // A 4 pt roll back as the finger lifts doesn't move the caret.
        XCTAssertEqual(drag.steps(towards: CGPoint(x: 116, y: 30)).characters, 0)
        XCTAssertEqual(drag.steps(towards: CGPoint(x: 108, y: 30)).characters, -1)
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

    func testKeyboardMicLevelsExpireAndRejectInvalidReadings() {
        let now = Date(timeIntervalSince1970: 1_000)
        let reading = KeyboardDictationMailbox.MeterReading(level: 0.5, measured: now)
        XCTAssertTrue(reading.isCurrent(at: now.addingTimeInterval(0.1)))
        XCTAssertFalse(reading.isCurrent(at: now.addingTimeInterval(1)))
        XCTAssertFalse(KeyboardDictationMailbox.MeterReading(level: 2, measured: now).isCurrent(at: now))
    }

    @MainActor
    func testEmojiSearchUsesEnglishAndSpanishAnnotations() {
        XCTAssertTrue(KeyboardEmojiPicker.searchEmoji(matching: "red heart").contains("❤️"))
        XCTAssertTrue(KeyboardEmojiPicker.searchEmoji(matching: "corazon rojo").contains("❤️"))
        XCTAssertTrue(KeyboardEmojiPicker.searchEmoji(matching: "dog face").contains("🐶"))
        XCTAssertTrue(KeyboardEmojiPicker.searchEmoji(matching: "notanemojiquery").isEmpty)
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
        XCTAssertTrue(controller.hasDictationKey)
        controller.hasDictationKey = false
        XCTAssertTrue(controller.hasDictationKey)
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
            letters: UIButton(), delete: UIButton(), globe: nil, onSearch: {}, onInsert: { _ in }
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

    func testEmojiSuggestionsPreferCommonPicksThenNames() {
        XCTAssertEqual(KeyboardEmojiPicker.suggestion(for: "Perro"), "🐶")
        XCTAssertEqual(KeyboardEmojiPicker.suggestion(for: "corazón"), "❤️")
        XCTAssertEqual(KeyboardEmojiPicker.suggestion(for: "keyboard"), "⌨️")
        XCTAssertNil(KeyboardEmojiPicker.suggestion(for: "the"))
    }

    @MainActor
    func testEmojiSearchTypesIntoTheQueryAndInsertsResults() throws {
        let controller = TestKeyboardController()
        controller.loadViewIfNeeded()
        let input = try XCTUnwrap(controller.inputView)
        input.frame = CGRect(x: 0, y: 0, width: 390, height: 260)
        controller.viewWillAppear(false)
        defer { controller.viewDidDisappear(false) }
        let area = try XCTUnwrap(input.subviews.first { $0 is KeyboardKeyArea })

        func buttons(in view: UIView) -> [UIButton] {
            view.subviews.flatMap { subview in (subview as? UIButton).map { [$0] } ?? buttons(in: subview) }
        }
        func tap(_ label: String) throws {
            let button = buttons(in: area).first {
                $0.title(for: .normal)?.caseInsensitiveCompare(label) == .orderedSame || $0.accessibilityLabel == label
            }
            try XCTUnwrap(button, label).sendActions(for: .touchUpInside)
        }

        try tap("Emoji")
        try tap("Search Emoji")
        for letter in "heart" { try tap(String(letter)) }
        XCTAssertEqual(controller.document.text, "", "search letters must not reach the document")
        try tap("❤️")
        XCTAssertEqual(controller.document.text, "❤️")
        try tap("Done")
        XCTAssertNil(buttons(in: area).first { $0.accessibilityLabel == "Search Emoji" }, "Done returns to the letters")
        XCTAssertNotNil(buttons(in: area).first { $0.title(for: .normal)?.lowercased() == "q" })
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

    init() {
        super.init(nibName: nil, bundle: nil)
        // A real host reports each caret move back; the keyboard sends the next one after that.
        // Set here: UIKit reads the proxy during dealloc, when no reference may be formed.
        document.onAdjust = { [weak self] in self?.selectionDidChange(nil) }
    }

    required init?(coder: NSCoder) { nil }
}

@MainActor
private final class TestKeyboardDocument: NSObject, UITextDocumentProxy {
    var text = ""
    var caret: Int?
    var onAdjust: (() -> Void)?
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
        onAdjust?()
    }
    func setMarkedText(_ markedText: String, selectedRange: NSRange) {}
    func unmarkText() {}
}

/// Port of Android `BloomBarsTest`.
final class KeyboardBloomBarsTests: XCTestCase {
    /// 60 Hz frames, a level reading every 32 ms; returns the end time.
    @discardableResult
    private func run(_ bars: inout KeyboardBloomBars, level: Float, seconds: Double, from start: Double = 0) -> Double {
        var now = start
        var nextPush = start
        while now < start + seconds {
            if now >= nextPush {
                bars.push(level, at: now)
                nextPush += 0.032
            }
            _ = bars.step(1 / 60, at: now)
            now += 1.0 / 60
        }
        return now
    }

    func testSilenceStaysFlat() {
        var bars = KeyboardBloomBars()
        run(&bars, level: 0, seconds: 1)
        XCTAssertTrue(bars.heights.allSatisfy { $0 == 0 })
    }

    func testVoiceIsSymmetricAndCenterTallest() {
        var bars = KeyboardBloomBars()
        run(&bars, level: 0.8, seconds: 0.6)
        for index in 0..<5 { XCTAssertEqual(bars.heights[index], bars.heights[10 - index], accuracy: 1e-6) }
        XCTAssertGreaterThan(bars.heights[5], 0.5)
        XCTAssertGreaterThan(bars.heights[5], bars.heights[2])
        XCTAssertGreaterThan(bars.heights[2], bars.heights[0])
    }

    func testOpensFromCenterFirst() {
        var bars = KeyboardBloomBars()
        run(&bars, level: 0.8, seconds: 0.048)
        XCTAssertGreaterThan(bars.heights[5], 0)
        XCTAssertEqual(bars.heights[0], 0)
    }

    func testClosesAndSettlesAfterVoiceStops() {
        var bars = KeyboardBloomBars()
        let end = run(&bars, level: 0.8, seconds: 0.6)
        let settled = run(&bars, level: 0, seconds: 2, from: end)
        XCTAssertFalse(bars.step(1 / 60, at: settled))
        XCTAssertTrue(bars.heights.allSatisfy { $0 < 0.01 })
    }
}
