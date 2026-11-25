import CoreText
import UIKit

/// MyWhisper keyboard with local predictions, autocorrect, and emoji.
class KeyboardViewController: UIInputViewController {

    private enum Page { case letters, symbols, more }
    private enum Shift { case off, on, locked }

    private var page: Page = .letters
    private var shift: Shift = .off
    private var settings = KeyboardPreferences.load()
    private lazy var language = startingLanguage
    private static let lastLanguageKey = "mw.keyboard.lastLanguage"

    /// iOS builds a fresh keyboard each time it appears: default language, or the last one used if enabled.
    private var startingLanguage: KeyboardLanguage {
        guard settings.remembersLanguage,
              let saved = UserDefaults.standard.string(forKey: Self.lastLanguageKey).flatMap(KeyboardLanguage.init(rawValue:)),
              settings.languages.contains(saved) else { return settings.defaultLanguage }
        return saved
    }
    private weak var stripNotice: UIButton?
    private weak var microphonePreview: KeyboardMicPreview?
    private var micDockedLeading = false
    /// The bottom-row key beside 123 types a comma instead of opening emoji; holding it switches, and the last choice sticks.
    private var emojiKeyIsComma = UserDefaults.standard.bool(forKey: "mw.keyboard.emojiKeyComma")
    /// What choosing an entry in the open hold popup does (accents, period symbols, emoji/comma).
    private var popupAction: ((String) -> Void)?
    private lazy var haptic = UIImpactFeedbackGenerator(style: .light)
    /// One delay for every press-and-hold (cursor, accents, symbols, delete repeat), so they all feel the same.
    private static let holdDelay: TimeInterval = 0.35
    private var pendingUndo: (original: String, replacement: String)?
    /// Text a suggestion tap inserted, ending in its automatic space. Punctuation typed next goes before that space.
    private var suggestionInsert: String?
    private var emojiMode = false
    /// Non-nil while the emoji search keyboard is up; typed letters edit this query.
    private var emojiSearch: String?
    private weak var emojiSearchBar: KeyboardEmojiSearchBar?
    private weak var spaceKey: KeyboardKeyButton?
    private let rowsView = KeyboardKeyArea()
    private var layout: Layout?
    private var letterKeys: [(button: KeyboardKeyButton, letter: String)] = []
    private weak var shiftButton: KeyboardKeyButton?
    private var predictionButtons: [KeyboardSuggestionButton] = []
    private var predictionDividers: [UIView] = []
    private var predictionTask: Task<Void, Never>?
    private var suggestion: Suggestion?
    private var scheduledSuggestion: SuggestionInput?
    private var transfer: KeyboardPreferences?
    private var visible = false
    private var replacingText = false
    private var lastShiftTap: TimeInterval?
    private var cursorDrag: KeyboardCursorDrag?
    private var cursorDocument: UUID?
    private weak var cursorGesture: UIGestureRecognizer?
    private var lastSwipe: SwipeCommit?
    private var swipeLexicon: (locale: String, words: KeyboardSwipeLexicon)?
    private var swipeLexiconLoad: Task<Void, Never>?
    private var loadingSwipeLocale: String?
    private var pendingSwipe: Task<Void, Never>?
    private lazy var swipeGesture = KeyboardSwipeGesture(target: self, action: #selector(handleSwipe(_:)))
    private let swipeTrail = CAShapeLayer()

    private struct SwipeCommit {
        let document: UUID
        let prefix: String
        let word: String
        let alternatives: [String]
        let contextAfter: String
    }
    private let accents: [String: String] = [
        "a": "áàâäãåæā", "c": "çćč", "e": "éèêëēėę", "i": "íìîïī",
        "n": "ñń", "o": "óòôöõøœō", "s": "ßśš", "u": "úùûüū", "y": "ýÿ", "z": "źżž",
    ]
    private var accentChoices: [(UILabel, String)] = []
    private var accentStrip: UIView?
    private var selectedAccent: Int?

    private struct Layout: Equatable {
        var width: CGFloat
        var compact: Bool
        var dark: Bool
        var globe: Bool
        var keyboardType: UIKeyboardType
        var returnType: UIReturnKeyType
    }

    private struct SuggestionInput: Equatable {
        var document: UUID
        var word: String
        /// Letters of `word` after the caret, when the caret sits inside an earlier word.
        var trailing: Int
        var allowsCorrection: Bool
    }

    private struct Suggestion {
        var document: UUID
        var word: String
        var trailing: Int
        var correction: String?
    }
    private var heightConstraint: NSLayoutConstraint?
    private var popup: UILabel?
    private weak var pressedKey: UIButton?
    private var pressTint: UIView?
    private var deleteTimer: Timer?
    private var deleteRepeated = false
    private var lastSpaceAt: TimeInterval?

    private var isDark = false
    private lazy var checker = UITextChecker()
    private lazy var spellLanguages: [KeyboardLanguage: String] = {
        let available = UITextChecker.availableLanguages.map { ($0, Locale(identifier: $0)) }
        var languages: [KeyboardLanguage: String] = [:]
        for language in KeyboardLanguage.allCases {
            let preferred = Locale(identifier: language.rawValue)
            let match = available.first { $0.1.identifier == preferred.identifier }
                ?? available.first { $0.1.language.languageCode == preferred.language.languageCode }
            languages[language] = match?.0
        }
        return languages
    }()

    private var spellLanguage: String? { spellLanguages[language] }

    // MARK: - Lifecycle

    // The remote host can query this before UIKit loads the extension's view.
    override var hasDictationKey: Bool {
        get { true }
        set { super.hasDictationKey = true }
    }

    override func loadView() {
        super.hasDictationKey = true
        let keyboard = KeyboardInputView(frame: .zero, inputViewStyle: .keyboard)
        // Let UIKit draw the keyboard surface and its device-specific rounded edge.
        keyboard.isOpaque = false
        keyboard.backgroundColor = .clear
        // Size from our height constraint. Without this iOS can keep a taller height from an
        // earlier layout (emoji, another keyboard) and leave empty space above the toolbar.
        keyboard.allowsSelfSizing = true
        inputView = keyboard
    }

    // Keys sit at the screen edges; let them win over the system edge gestures.
    override var preferredScreenEdgesDeferringSystemGestures: UIRectEdge { .all }

    override func viewDidLoad() {
        super.viewDidLoad()
        rowsView.translatesAutoresizingMaskIntoConstraints = false
        // Fast two-thumb typing overlaps touches; a single-touch view drops the second key.
        view.isMultipleTouchEnabled = true
        rowsView.isMultipleTouchEnabled = true
        // iOS passes touches on fully transparent keyboard pixels through, so taps in the
        // gaps never reached nearestKey. 1% black is invisible over the system surface.
        rowsView.backgroundColor = UIColor(white: 0, alpha: 0.01)
        view.addSubview(rowsView)
        swipeGesture.delegate = self
        rowsView.addGestureRecognizer(swipeGesture)
        swipeTrail.lineCap = .round
        swipeTrail.lineJoin = .round
        swipeTrail.lineWidth = 5
        swipeTrail.fillColor = UIColor.clear.cgColor
        NSLayoutConstraint.activate([
            rowsView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            rowsView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            rowsView.topAnchor.constraint(equalTo: view.topAnchor),
            rowsView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        heightConstraint = view.heightAnchor.constraint(equalToConstant: 302)
        heightConstraint?.priority = .init(999)
        heightConstraint?.isActive = true
        registerForTraitChanges([UITraitUserInterfaceStyle.self, UITraitVerticalSizeClass.self]) {
            (controller: KeyboardViewController, _) in controller.refreshLayout()
        }
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        super.hasDictationKey = true
        visible = true
        applySettings(KeyboardPreferences.load())
        refreshLayout()
        updateAutomaticShift()
        scheduleSuggestions()
        preloadSwipeWords()
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        visible = false
        microphonePreview?.reset()
        setStripNoticeVisible(false)
        transfer = nil
        cursorDrag = nil
        cursorDocument = nil
        cursorGesture = nil
        pendingSwipe?.cancel()
        lastSwipe = nil
        swipeTrail.removeFromSuperlayer()
        predictionTask?.cancel()
        clearSuggestions()
        stopDelete()
        clearAccents()
        lastSpaceAt = nil
        lastShiftTap = nil
        pendingUndo = nil
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        let width = view.bounds.width
        if layout == nil || (width > 0 && layout?.width != width) { refreshLayout() }
    }

    override func textDidChange(_ textInput: UITextInput?) {
        super.textDidChange(textInput)
        guard visible, cursorDrag == nil, !replacingText else { return }
        microphonePreview?.reset()
        refreshLayout()
        updateAutomaticShift()
        scheduleSuggestions()
    }

    override func selectionDidChange(_ textInput: UITextInput?) {
        super.selectionDidChange(textInput)
        guard visible, cursorDrag == nil, !replacingText else { return }
        updateAutomaticShift()
        scheduleSuggestions()
    }

    override func didReceiveMemoryWarning() {
        super.didReceiveMemoryWarning()
        swipeLexiconLoad?.cancel()
        loadingSwipeLocale = nil
        swipeLexicon = nil
        predictionTask?.cancel()
        clearSuggestions()
    }

    private func refreshLayout() {
        let proxy = textDocumentProxy
        let appearance = proxy.keyboardAppearance
        let dark = appearance == .dark || ((appearance == nil || appearance == .default) &&
            traitCollection.userInterfaceStyle == .dark)
        let next = Layout(
            width: view.bounds.width > 0 ? view.bounds.width : 390,
            compact: traitCollection.verticalSizeClass == .compact,
            dark: dark,
            globe: needsInputModeSwitchKey,
            keyboardType: proxy.keyboardType ?? .default,
            returnType: proxy.returnKeyType ?? .default
        )
        guard layout != next else { return }
        if layout?.keyboardType != next.keyboardType {
            page = next.keyboardType == .numbersAndPunctuation ? .symbols : .letters
            emojiMode = false
            emojiSearch = nil
        }
        layout = next
        isDark = dark
        rebuild()
    }

    private var keyColor: UIColor {
        isDark ? UIColor(white: 1, alpha: 0.16) : UIColor(white: 1, alpha: 0.94)
    }

    private var labelColor: UIColor { isDark ? .white : .black }

    /// Dimmer key ink for function keys (page switch, return).
    private var specialColor: UIColor {
        isDark ? UIColor(white: 0.24, alpha: 1)
            : UIColor(red: 0xAB / 255, green: 0xB1 / 255, blue: 0xBB / 255, alpha: 1)
    }

    // MARK: - Suggestions

    private var isAddressField: Bool {
        layout?.keyboardType == .URL || layout?.keyboardType == .emailAddress
    }

    private var isNumberField: Bool {
        guard let keyboardType = layout?.keyboardType else { return false }
        return [.numberPad, .decimalPad, .asciiCapableNumberPad].contains(keyboardType)
    }

    private var currentWord: String {
        guard !isAddressField, !isNumberField,
              textDocumentProxy.selectedText?.isEmpty != false,
              textDocumentProxy.documentContextAfterInput?.first?.isLetter != true,
              let before = textDocumentProxy.documentContextBeforeInput else { return "" }
        let suffix = before.reversed().prefix(29).prefix { $0.isLetter || $0 == "'" || $0 == "’" }
        return String(suffix.reversed())
    }

    private func clearSuggestions() {
        predictionTask?.cancel()
        scheduledSuggestion = nil
        suggestion = nil
        showSuggestions([])
    }

    private func scheduleSuggestions() {
        updateTransferAction()
        guard visible, page == .letters, !emojiMode, !isNumberField, !isAddressField,
              transfer == nil, settings.predictive || settings.autoCorrection else {
            clearSuggestions()
            return
        }
        if let swipe = validSwipe {
            predictionTask?.cancel()
            scheduledSuggestion = nil
            suggestion = nil
            if settings.predictive { showSuggestions([swipe.word] + swipe.alternatives) }
            return
        }
        lastSwipe = nil
        let (word, trailing) = wordAtCaret
        guard (2...28).contains(word.count) else {
            clearSuggestions()
            return
        }
        let proxy = textDocumentProxy
        let input = SuggestionInput(document: proxy.documentIdentifier, word: word, trailing: trailing,
                                    allowsCorrection: proxy.autocorrectionType != .no)
        guard input != scheduledSuggestion else { return }
        scheduledSuggestion = input
        suggestion = nil
        predictionTask?.cancel()
        predictionTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 42_000_000)
            guard !Task.isCancelled, let self, self.visible,
                  self.textDocumentProxy.documentIdentifier == input.document,
                  self.wordAtCaret.word == input.word, self.wordAtCaret.trailing == input.trailing else { return }
            self.updateSuggestions(for: input)
        }
    }

    private func correction(for word: String, language: String) -> String? {
        guard settings.autoCorrection, (3...28).contains(word.count) else { return nil }
        let range = NSRange(word.startIndex..., in: word)
        guard checker.rangeOfMisspelledWord(in: word, range: range, startingAt: 0, wrap: false, language: language) == range else { return nil }
        return checker.guesses(forWordRange: range, in: word, language: language)?.first { candidate in
            candidate.first?.lowercased() == word.first?.lowercased() &&
                abs(candidate.count - word.count) <= 1 && !candidate.contains(" ")
        }
    }

    private func updateSuggestions(for input: SuggestionInput) {
        guard let language = spellLanguage else {
            showSuggestions([])
            return
        }
        let word = input.word
        let correction = input.allowsCorrection ? correction(for: word, language: language) : nil
        suggestion = Suggestion(document: input.document, word: word, trailing: input.trailing, correction: correction)
        guard settings.predictive else { return }
        let range = NSRange(word.startIndex..., in: word)
        // Inside an earlier word only corrections make sense, not completions.
        let completions = input.trailing > 0 ? [] : checker.completions(forPartialWordRange: range, in: word, language: language) ?? []
        var seen = Set<String>()
        var choices = ([correction].compactMap { $0 } + completions).filter {
            $0.caseInsensitiveCompare(word) != .orderedSame && seen.insert($0.lowercased()).inserted
        }
        // iOS offers the typed word in quotes whenever Space would correct it, and marks the correction.
        let spaceCorrects = correction != nil && input.trailing == 0
        if spaceCorrects { choices.insert(Self.quoted(word), at: 0) }
        if input.trailing == 0, let emoji = KeyboardEmojiPicker.suggestion(for: word) {
            choices = Array(choices.prefix(predictionButtons.count - 1)) + [emoji]
        }
        showSuggestions(choices, emphasized: spaceCorrects ? 1 : nil)
    }

    private func showSuggestions(_ choices: [String], emphasized: Int? = nil) {
        for (index, button) in predictionButtons.enumerated() {
            button.display(choices.indices.contains(index) ? choices[index] : nil)
            button.emphasized = index == emphasized
        }
    }

    /// The word the suggestions act on: the one being typed, or the earlier word the caret was placed inside.
    private var wordAtCaret: (word: String, trailing: Int) {
        let typed = currentWord
        let proxy = textDocumentProxy
        guard typed.isEmpty, !isAddressField, !isNumberField, proxy.selectedText?.isEmpty != false,
              let before = proxy.documentContextBeforeInput,
              let after = proxy.documentContextAfterInput else { return (typed, 0) }
        let head = String(before.reversed().prefix(29).prefix(while: Self.isWordCharacter).reversed())
        let tail = String(after.prefix(29).prefix(while: Self.isWordCharacter))
        guard !head.isEmpty, !tail.isEmpty else { return (typed, 0) }
        return (head + tail, tail.count)
    }

    private static func isWordCharacter(_ character: Character) -> Bool {
        character.isLetter || character == "'" || character == "’"
    }

    @objc private func predictionTapped(_ button: UIButton) {
        microphonePreview?.reset()
        guard let replacement = button.title(for: .normal) else { return }
        if let swipe = validSwipe {
            guard replacement != swipe.word, swipe.alternatives.contains(replacement) else { return }
            replaceSwipe(swipe, with: replacement)
            return
        }
        let caret = wordAtCaret
        guard let suggestion, suggestion.document == textDocumentProxy.documentIdentifier,
              suggestion.word == caret.word, suggestion.trailing == caret.trailing else { return }
        if suggestion.trailing > 0 {
            // Fix an earlier word in place: move to its end, swap it, keep the text after it.
            textDocumentProxy.adjustTextPosition(byCharacterOffset: suggestion.trailing)
            replacingText = true
            for _ in suggestion.word { textDocumentProxy.deleteBackward() }
            textDocumentProxy.insertText(replacement)
            replacingText = false
            pendingUndo = nil
            suggestionInsert = nil
        } else if replacement == Self.quoted(suggestion.word) {
            learn(suggestion.word)
            pendingUndo = nil
            textDocumentProxy.insertText(" ")
            suggestionInsert = suggestion.word + " "
        } else {
            if KeyboardEmojiPicker.suggestion(for: suggestion.word) == replacement { KeyboardEmojiPicker.remember(replacement) }
            replaceWord(suggestion.word, with: replacement)
            suggestionInsert = replacement + " "
        }
        lastSpaceAt = nil
        playClick()
        updateAutomaticShift()
        scheduleSuggestions()
    }

    static func quoted(_ word: String) -> String { "\u{201C}\(word)\u{201D}" }

    /// Adds a word to the keyboard's spelling dictionary so it is never autocorrected.
    private func learn(_ text: String) {
        let word = text.trimmingCharacters(in: CharacterSet.whitespaces.union(.punctuationCharacters))
        guard !word.isEmpty, !UITextChecker.hasLearnedWord(word) else { return }
        UITextChecker.learnWord(word)
        suggestion = nil
    }

    private func replaceWord(_ word: String, with replacement: String) {
        let proxy = textDocumentProxy
        let inserted = replacement + " "
        replacingText = true
        for _ in word { proxy.deleteBackward() }
        proxy.insertText(inserted)
        replacingText = false
        pendingUndo = (original: word, replacement: inserted)
    }

    // MARK: - Layout

    private func setKeyboardHeight(_ height: CGFloat) {
        guard heightConstraint?.constant != height else { return }
        heightConstraint?.constant = height
        view.setNeedsLayout()
    }

    private func rebuild() {
        guard let layout else { return }
        stopDelete()
        hidePopup()
        clearAccents()
        swipeGesture.isEnabled = false
        clearSuggestions()
        swipeGesture.isEnabled = settings.usesSwipeTyping
        swipeTrail.removeFromSuperlayer()
        rowsView.clear()
        predictionButtons.removeAll(keepingCapacity: true)
        predictionDividers.removeAll(keepingCapacity: true)
        letterKeys.removeAll(keepingCapacity: true)
        shiftButton = nil

        let width = layout.width
        let pad: CGFloat = layout.compact ? 10 : 6
        let rowHeight: CGFloat = layout.compact ? 31 : 44
        let gap: CGFloat = layout.compact ? 6 : 10
        // Fits the 44 pt mic face; iOS already adds its own margin above the keyboard view.
        let stripHeight: CGFloat = layout.compact ? 36 : 46
        if emojiMode, let query = emojiSearch {
            addEmojiSearch(query: query, width: width, pad: pad, rowHeight: rowHeight, gap: gap)
            return
        }
        let numberRow = settings.showsNumberRow && !isNumberField
        let numberHeight: CGFloat = layout.compact ? 26 : 38
        let extra = numberRow ? numberHeight + gap : 0
        // The emoji picker is taller, as on the system keyboard, so emoji draw at full size.
        let emojiExtra: CGFloat = emojiMode && !layout.compact ? 44 : 0
        let height = stripHeight + extra + 4 * rowHeight + 3 * gap + 10 + emojiExtra
        setKeyboardHeight(height)

        if emojiMode {
            addEmojiView(width: width, pad: pad, height: height, rowHeight: rowHeight)
            return
        }

        addPredictionRow(width: width, pad: pad, height: stripHeight)
        var y = stripHeight + 4
        var keyHeight = rowHeight
        if numberRow, page == .letters {
            placeRow("1234567890".map { (characterKey(String($0), cased: false), 1) },
                     y: y, height: numberHeight, width: width, pad: pad)
            y += numberHeight + gap
        } else {
            // Symbol pages already start with digits: stretch their keys so the height never jumps.
            keyHeight += extra / 4
        }
        let step = keyHeight + gap
        if isNumberField {
            addNumberPad(y: y, step: step, height: rowHeight, width: width, pad: pad)
            return
        }
        switch page {
        case .letters:
            addLettersRow(keys: "QWERTYUIOP", y: y, h: keyHeight, width: width, pad: pad)
            let spanish = language == .spanish
            addLettersRow(keys: spanish ? "ASDFGHJKLÑ" : "ASDFGHJKL", y: y + step,
                          h: keyHeight, width: width, pad: pad, inset: spanish ? 0 : 0.5)
            addLettersRow(keys: "ZXCVBNM", y: y + 2 * step, h: keyHeight, width: width, pad: pad,
                          left: shiftKey(), right: deleteKey())
        case .symbols:
            addLettersRow(keys: "1234567890", y: y, h: keyHeight, width: width, pad: pad)
            let symbol = language == .spanish ? "€" : (Locale(identifier: language.rawValue).currencySymbol ?? "$")
            let currency = symbol.count == 1 ? symbol : "$"
            addLettersRow(keys: "-/:;()\(currency)&@\"", y: y + step, h: keyHeight, width: width, pad: pad)
            addLettersRow(keys: ".,?!'", y: y + 2 * step, h: keyHeight, width: width, pad: pad,
                          left: pageKey("#+="), right: deleteKey())
        case .more:
            addLettersRow(keys: "[]{}#%^*+=", y: y, h: keyHeight, width: width, pad: pad)
            addLettersRow(keys: "_\\|~<>€£¥•", y: y + step, h: keyHeight, width: width, pad: pad)
            addLettersRow(keys: ".,?!'", y: y + 2 * step, h: keyHeight, width: width, pad: pad,
                          left: pageKey("123"), right: deleteKey())
        }
        addBottomRow(y: y + 3 * step, h: keyHeight, width: width, pad: pad)
    }

    private func addPredictionRow(width: CGFloat, pad: CGFloat, height: CGFloat) {
        let toolbarWidth = width - pad * 2
        let microphoneWidth = min(50, height)
        let side = settings.showsSideButton
        let available = toolbarWidth - microphoneWidth * (side ? 2 : 1)
        let suggestionsX = pad + (micDockedLeading || side ? microphoneWidth : 0)
        let slot = available / 3
        for index in 0..<3 {
            let x = suggestionsX + CGFloat(index) * slot
            let button = KeyboardSuggestionButton(ink: labelColor)
            button.frame = CGRect(x: x, y: 0, width: slot, height: height)
            button.isEnabled = false
            button.isAccessibilityElement = false
            button.addTarget(self, action: #selector(predictionTapped(_:)), for: .touchUpInside)
            rowsView.addSubview(button)
            predictionButtons.append(button)
            if index > 0 {
                let divider = UIView(frame: CGRect(x: x, y: height * 0.28, width: 1, height: height * 0.44))
                divider.backgroundColor = labelColor.withAlphaComponent(0.12)
                divider.isUserInteractionEnabled = false
                rowsView.addSubview(divider)
                predictionDividers.append(divider)
            }
        }

        let notice = UIButton(type: .custom)
        notice.frame = CGRect(x: suggestionsX, y: 0, width: available, height: height)
        notice.titleLabel?.font = .systemFont(ofSize: 14, weight: .medium)
        notice.titleLabel?.adjustsFontSizeToFitWidth = true
        notice.setTitleColor(accentColor, for: .normal)
        notice.setTitleColor(labelColor, for: .disabled)
        notice.addTarget(self, action: #selector(applyTransferredPreferences), for: .touchUpInside)
        notice.isHidden = true
        rowsView.addSubview(notice)
        stripNotice = notice
        updateTransferAction()
        if side {
            let x = micDockedLeading ? pad + toolbarWidth - microphoneWidth : pad
            addSideButton(frame: CGRect(x: x, y: 0, width: microphoneWidth, height: height))
        }
        let preview = KeyboardMicPreview(
            frame: CGRect(x: pad, y: 0, width: toolbarWidth, height: height),
            dockedLeading: micDockedLeading,
            openSession: hasFullAccess ? { [weak self] in self?.openMyWhisper("keyboard-mic") } : nil
        ) { [weak self] leading in
            guard let self else { return }
            self.micDockedLeading = leading
            self.rebuild()
            self.scheduleSuggestions()
        }
        rowsView.addSubview(preview)
        microphonePreview = preview
    }

    /// Same face as the mic, on the other side: settings, or emoji when the emoji key types commas.
    /// Holding always opens settings without changing what a tap does.
    private func addSideButton(frame: CGRect) {
        let button = UIButton(type: .custom)
        button.frame = frame
        let face = UIView(frame: button.bounds.insetBy(dx: 1, dy: 1))
        face.backgroundColor = .white
        face.alpha = 0.65
        face.layer.borderWidth = 1
        face.layer.borderColor = UIColor.black.cgColor
        face.layer.cornerRadius = min(16, face.bounds.height / 2)
        face.isUserInteractionEnabled = false
        let icon = UIImageView(image: UIImage(
            systemName: emojiKeyIsComma ? "face.smiling" : "gearshape",
            withConfiguration: UIImage.SymbolConfiguration(pointSize: 20, weight: .medium)))
        icon.tintColor = .black
        icon.contentMode = .center
        icon.frame = face.bounds
        face.addSubview(icon)
        button.addSubview(face)
        button.accessibilityLabel = emojiKeyIsComma ? "Emoji" : "Keyboard settings"
        button.accessibilityHint = "Hold for keyboard settings"
        button.addTarget(self, action: #selector(sideButtonTapped), for: .touchUpInside)
        let hold = UILongPressGestureRecognizer(target: self, action: #selector(sideButtonHeld(_:)))
        hold.minimumPressDuration = Self.holdDelay
        button.addGestureRecognizer(hold)
        rowsView.addSubview(button)
    }

    @objc private func sideButtonTapped() {
        playHaptic()
        if emojiKeyIsComma { openEmojiPicker() } else { openMyWhisper("keyboard-settings") }
    }

    @objc private func sideButtonHeld(_ gesture: UILongPressGestureRecognizer) {
        guard gesture.state == .began else { return }
        playHaptic(hold: true)
        openMyWhisper("keyboard-settings")
    }

    /// Keyboards can't record or show app screens. Like Wispr Flow, open MyWhisper
    /// (mic session, settings); iOS shows a back link to the previous app.
    private func openMyWhisper(_ destination: String) {
        guard let url = NSURL(string: "mywhisper://" + destination) else { return }
        // UIApplication.open is unavailable to extensions; reach the host app through the responder chain.
        let selector = NSSelectorFromString("openURL:options:completionHandler:")
        var responder: UIResponder? = self
        while let current = responder {
            if let application = current as? UIApplication, application.responds(to: selector) {
                typealias Open = @convention(c) (AnyObject, Selector, NSURL, NSDictionary, AnyObject?) -> Void
                unsafeBitCast(application.method(for: selector), to: Open.self)(application, selector, url, NSDictionary(), nil)
                return
            }
            responder = current.next
        }
    }

    private func setStripNoticeVisible(_ visible: Bool) {
        stripNotice?.isHidden = !visible
        predictionButtons.forEach { $0.isHidden = visible }
        predictionDividers.forEach { $0.isHidden = visible }
    }

    private func applySettings(_ updated: KeyboardPreferences) {
        guard settings != updated else { return }
        if settings.defaultLanguage != updated.defaultLanguage || !updated.languages.contains(language) {
            language = updated.defaultLanguage
        }
        settings = updated
        lastSwipe = nil
        pendingSwipe?.cancel()
        rebuild()
        updateAutomaticShift()
        preloadSwipeWords()
    }

    private func updateTransferAction() {
        guard let stripNotice else { return }
        if KeyboardPreferences.sharedURL == nil, layout?.keyboardType == .asciiCapable,
           let text = textDocumentProxy.documentContextBeforeInput, text.hasPrefix(KeyboardPreferences.transferPrefix),
           textDocumentProxy.documentContextAfterInput?.isEmpty != false,
           textDocumentProxy.selectedText?.isEmpty != false {
            transfer = KeyboardPreferences(transferText: text)
        } else {
            transfer = nil
        }
        if transfer != nil {
            stripNotice.setTitle("Apply preferences", for: .normal)
            stripNotice.isEnabled = true
            setStripNoticeVisible(true)
        } else if stripNotice.isEnabled {
            setStripNoticeVisible(false)
        }
    }

    @objc private func applyTransferredPreferences() {
        updateTransferAction()
        guard let transfer else { return }
        transfer.saveLocally()
        applySettings(transfer)
        let message = "Keyboard preferences applied"
        UIAccessibility.post(notification: .announcement, argument: message)
        // Leave the field empty so the app can collapse the temporary transfer controls.
        replacingText = true
        let proxy = textDocumentProxy
        for _ in transfer.transferText { proxy.deleteBackward() }
        replacingText = false
        scheduleSuggestions()
    }

    private var accentColor: UIColor {
        isDark ? UIColor(red: 128 / 255, green: 217 / 255, blue: 189 / 255, alpha: 1)
            : UIColor(red: 23 / 255, green: 102 / 255, blue: 87 / 255, alpha: 1)
    }

    private func placeRow(_ keys: [(KeyboardKeyButton, CGFloat)], y: CGFloat, height: CGFloat,
                          width: CGFloat, pad: CGFloat, inset: CGFloat = 0) {
        let spacing: CGFloat = 6
        let contentWidth = width - 2 * pad - CGFloat(keys.count - 1) * spacing
        let units = keys.reduce(2 * inset) { $0 + $1.1 }
        let unit = max(1, (contentWidth - 2 * inset * spacing) / units)
        var x = pad + inset * (unit + spacing)
        for (key, weight) in keys {
            key.frame = CGRect(x: x, y: y, width: unit * weight, height: height)
            rowsView.addKey(key)
            x += key.frame.width + spacing
        }
    }

    private func addLettersRow(keys: String, y: CGFloat, h: CGFloat, width: CGFloat, pad: CGFloat,
                               inset: CGFloat = 0, left: KeyboardKeyButton? = nil, right: KeyboardKeyButton? = nil) {
        var buttons: [(KeyboardKeyButton, CGFloat)] = []
        if let left { buttons.append((left, 1.35)) }
        for character in keys {
            buttons.append((characterKey(String(character), cased: page == .letters), 1))
        }
        if let right { buttons.append((right, 1.35)) }
        placeRow(buttons, y: y, height: h, width: width, pad: pad, inset: inset)
    }

    private var fieldShortcuts: [String] {
        switch layout?.keyboardType {
        case .emailAddress: return ["@", "."]
        case .URL: return ["/", "."]
        case .webSearch: return ["@", "."]
        case .twitter: return ["@", "#"]
        default:
            switch layout?.returnType {
            case .search, .google, .yahoo: return ["@", "."]
            default: return []
            }
        }
    }

    private func addBottomRow(y: CGFloat, h: CGFloat, width: CGFloat, pad: CGFloat) {
        var keys: [(KeyboardKeyButton, CGFloat)] = [(pageKey(page == .letters ? "123" : "ABC"), 1.15)]
        if layout?.globe == true { keys.append((globeKey(), 1)) }
        keys.append((emojiKey(), 1))

        let space = makeKey(background: keyColor)
        let swipesLanguage = settings.switchesLanguageBySwipe && settings.languages.count > 1
        // Arrows hint that sliding the space bar sideways changes language.
        space.setTitle(swipesLanguage ? "‹  \(language.spaceTitle)  ›" : language.spaceTitle, for: .normal)
        space.setTitleColor(labelColor.withAlphaComponent(0.8), for: .normal)
        space.titleLabel?.font = .systemFont(ofSize: 14)
        space.titleLabel?.adjustsFontSizeToFitWidth = true
        space.accessibilityLabel = "Space"
        space.accessibilityValue = language.title
        space.addTarget(self, action: #selector(spaceTapped), for: .touchUpInside)
        spaceKey = space

        let cursor = UILongPressGestureRecognizer(target: self, action: #selector(dragCursor(_:)))
        cursor.minimumPressDuration = Self.holdDelay
        space.addGestureRecognizer(cursor)
        let vertical = UIPanGestureRecognizer(target: self, action: #selector(dragSpacebarVertically(_:)))
        vertical.maximumNumberOfTouches = 1
        vertical.delegate = self
        vertical.require(toFail: cursor)
        space.addGestureRecognizer(vertical)
        if swipesLanguage {
            for direction: UISwipeGestureRecognizer.Direction in [.left, .right] {
                let swipe = UISwipeGestureRecognizer(target: self, action: #selector(swipeSpacebar(_:)))
                swipe.direction = direction
                swipe.require(toFail: cursor)
                space.addGestureRecognizer(swipe)
            }
            space.accessibilityCustomActions = [
                UIAccessibilityCustomAction(name: "Next language", target: self, selector: #selector(nextLanguage))
            ]
        }
        keys.append((space, 4.6))
        var periodKey = false
        if page == .letters {
            let shortcuts = fieldShortcuts
            keys.append(contentsOf: shortcuts.map { (characterKey($0, cased: false), 1) })
            if settings.showsPeriodKey, shortcuts.isEmpty {
                let period = characterKey(".", cased: false)
                period.accessibilityIdentifier = "period-key"
                addHold(to: period)
                keys.append((period, 1))
                periodKey = true
            }
        }
        keys.append((returnKey(), periodKey ? 1.5 : 2.1))
        placeRow(keys, y: y, height: h, width: width, pad: pad)
    }

    private func emojiKey() -> KeyboardKeyButton {
        let key: KeyboardKeyButton
        if emojiKeyIsComma {
            key = characterKey(",", cased: false)
        } else {
            key = symbolKey("face.smiling", label: "Emoji")
            key.addTarget(self, action: #selector(openEmojiPicker), for: .touchUpInside)
        }
        key.accessibilityIdentifier = "emoji-key"
        key.accessibilityHint = "Hold to switch between emoji and comma"
        addHold(to: key)
        return key
    }

    private func chooseEmojiKey(comma: Bool) {
        if comma != emojiKeyIsComma {
            emojiKeyIsComma = comma
            UserDefaults.standard.set(comma, forKey: "mw.keyboard.emojiKeyComma")
            rebuild()
        }
        if comma { insert(",") } else { openEmojiPicker() }
    }

    private func addNumberPad(y: CGFloat, step: CGFloat, height: CGFloat, width: CGFloat, pad: CGFloat) {
        for (index, digits) in ["123", "456", "789"].enumerated() {
            let keys = digits.map { (characterKey(String($0), cased: false), CGFloat(1)) }
            placeRow(keys, y: y + CGFloat(index) * step, height: height, width: width, pad: pad)
        }
        var keys: [(KeyboardKeyButton, CGFloat)] = []
        if layout?.globe == true { keys.append((globeKey(), 0.55)) }
        let alternateWeight: CGFloat = layout?.globe == true ? 0.45 : 1
        let alternate = layout?.keyboardType == .decimalPad
            ? characterKey(Locale.current.decimalSeparator ?? ".", cased: false)
            : returnKey()
        keys.append((alternate, alternateWeight))
        keys.append((characterKey("0", cased: false), 1))
        keys.append((deleteKey(), 1))
        placeRow(keys, y: y + 3 * step, height: height, width: width, pad: pad)
    }

    private func globeKey() -> KeyboardKeyButton {
        let button = symbolKey("globe", label: "Switch keyboard")
        button.addTarget(self, action: #selector(handleInputModeList(from:with:)), for: .allTouchEvents)
        button.activateWithVoiceOver = { [weak self] in self?.advanceToNextInputMode() }
        return button
    }

    private func makeKey(background: UIColor) -> KeyboardKeyButton {
        let key = KeyboardKeyButton(type: .custom)
        key.backgroundColor = background
        key.layer.cornerRadius = 8
        key.layer.cornerCurve = .continuous
        key.setTitleColor(labelColor, for: .normal)
        key.addTarget(self, action: #selector(keyPressed(_:)), for: .touchDown)
        key.addTarget(self, action: #selector(keyReleased(_:)),
                      for: [.touchUpInside, .touchUpOutside, .touchCancel, .touchDragExit])
        return key
    }

    private func symbol(_ name: String, size: CGFloat = 19) -> UIImage? {
        UIImage(systemName: name, withConfiguration: UIImage.SymbolConfiguration(pointSize: size, weight: .medium))
    }

    private func symbolKey(_ name: String, label: String) -> KeyboardKeyButton {
        let button = makeKey(background: specialColor)
        button.setImage(symbol(name), for: .normal)
        button.tintColor = labelColor
        button.accessibilityLabel = label
        return button
    }

    private func characterKey(_ value: String, cased: Bool) -> KeyboardKeyButton {
        let button = makeKey(background: keyColor)
        button.titleLabel?.font = .systemFont(ofSize: layout?.compact == true ? 19 : 22)
        button.accessibilityIdentifier = value.lowercased()
        if cased {
            letterKeys.append((button, value))
            button.setTitle(shift == .off ? value.lowercased() : value, for: .normal)
            if accents[value.lowercased()] != nil { addHold(to: button) }
        } else {
            button.setTitle(value, for: .normal)
        }
        button.addTarget(self, action: #selector(characterTapped(_:)), for: .touchUpInside)
        return button
    }

    private func shiftKey() -> KeyboardKeyButton {
        let button = symbolKey("shift", label: "Shift")
        button.addTarget(self, action: #selector(shiftTapped), for: .touchUpInside)
        shiftButton = button
        updateShiftKeys()
        return button
    }

    private func updateShiftKeys() {
        for (button, letter) in letterKeys {
            button.setTitle(shift == .off ? letter.lowercased() : letter, for: .normal)
        }
        let image: String
        switch shift {
        case .off: image = "shift"
        case .on: image = "shift.fill"
        case .locked: image = "capslock.fill"
        }
        shiftButton?.setImage(symbol(image), for: .normal)
        shiftButton?.tintColor = shift == .off ? labelColor : accentColor
        shiftButton?.accessibilityValue = shift == .locked ? "Caps Lock" : (shift == .on ? "On" : "Off")
    }

    private func deleteKey() -> KeyboardKeyButton {
        let button = symbolKey("delete.left", label: "Delete")
        button.addTarget(self, action: #selector(deleteDown), for: .touchDown)
        button.activateWithVoiceOver = { [weak self] in
            self?.playClick()
            self?.performDelete()
        }
        button.addTarget(self, action: #selector(deleteTapped), for: .touchUpInside)
        button.addTarget(self, action: #selector(stopDelete), for: [.touchUpOutside, .touchCancel, .touchDragExit])
        let drag = UIPanGestureRecognizer(target: self, action: #selector(dragDelete(_:)))
        drag.maximumNumberOfTouches = 1
        drag.delegate = self
        button.addGestureRecognizer(drag)
        return button
    }

    private func pageKey(_ title: String) -> KeyboardKeyButton {
        let button = makeKey(background: specialColor)
        button.setTitle(title, for: .normal)
        button.titleLabel?.font = .systemFont(ofSize: 15, weight: .medium)
        button.addTarget(self, action: #selector(pageTapped(_:)), for: .touchUpInside)
        return button
    }

    private func returnKey() -> KeyboardKeyButton {
        let type = layout?.returnType ?? .default
        if type == .default {
            let button = symbolKey("return", label: "Return")
            button.addTarget(self, action: #selector(returnTapped), for: .touchUpInside)
            return button
        }
        let title: String
        switch type {
        case .go: title = "Go"
        case .search, .google, .yahoo: title = "Search"
        case .send: title = "Send"
        case .done: title = "Done"
        case .join: title = "Join"
        case .next: title = "Next"
        case .continue: title = "Continue"
        case .route: title = "Route"
        case .emergencyCall: title = "Call"
        default: title = "Return"
        }
        let button = makeKey(background: accentColor)
        button.setTitle(title, for: .normal)
        button.setTitleColor(isDark ? .black : .white, for: .normal)
        button.titleLabel?.font = .systemFont(ofSize: 15, weight: .semibold)
        button.titleLabel?.adjustsFontSizeToFitWidth = true
        button.addTarget(self, action: #selector(returnTapped), for: .touchUpInside)
        return button
    }

    // MARK: - Editing

    @objc private func keyPressed(_ button: UIButton) {
        playHaptic()
        showPopup(for: button)
    }

    /// Light tap on key down; a firmer second tap when a hold activates. Off unless enabled in settings.
    private func playHaptic(hold: Bool = false) {
        guard settings.usesHaptics else { return }
        haptic.impactOccurred(intensity: hold ? 1 : 0.55)
    }

    @objc private func keyReleased(_ button: UIButton) {
        if pressedKey === button { hidePopup() }
    }

    @objc private func characterTapped(_ button: UIButton) {
        guard let character = button.title(for: .normal) else { return }
        insert(character)
    }

    private func insert(_ text: String) {
        if emojiMode, let query = emojiSearch {
            playClick()
            updateEmojiSearch(query + text)
            if shift == .on {
                shift = .off
                updateShiftKeys()
            }
            return
        }
        microphonePreview?.reset()
        pendingSwipe?.cancel()
        let startsWord = text.first.map { $0.isLetter || $0.isNumber } == true
        let separator = startsWord && validSwipe != nil ? " " : ""
        let punctuation = text.count == 1 && ".,!?;:".contains(text)
        let word = punctuation && validSwipe == nil ? currentWord : ""
        let autoSpace = suggestionInsert
        lastSwipe = nil
        pendingUndo = nil
        suggestionInsert = nil
        lastSpaceAt = nil
        lastShiftTap = nil
        playClick()
        let proxy = textDocumentProxy
        let before = proxy.documentContextBeforeInput
        var inserted = separator + text
        var undo: (original: String, replacement: String)?
        replacingText = true
        if punctuation, let autoSpace, before?.hasSuffix(autoSpace) == true {
            // "word " + "." -> "word. ", as on the system keyboard.
            proxy.deleteBackward()
            inserted = text + " "
        } else if punctuation, let fixed = autocorrection(for: word) {
            for _ in word { proxy.deleteBackward() }
            proxy.insertText(fixed)
            undo = (original: word + text, replacement: fixed + text)
        }
        proxy.insertText(inserted)
        replacingText = false
        pendingUndo = undo
        updateAutomaticShift(before: before.map { String($0.suffix(64)) + inserted } ?? inserted)
        scheduleSuggestions()
    }

    /// The correction Space or punctuation would apply to `word`, if any.
    private func autocorrection(for word: String) -> String? {
        guard settings.autoCorrection, !word.isEmpty else { return nil }
        let proxy = textDocumentProxy
        if let suggestion, suggestion.word == word, suggestion.document == proxy.documentIdentifier {
            return suggestion.correction
        }
        guard proxy.autocorrectionType != .no, let language = spellLanguage else { return nil }
        return correction(for: word, language: language)
    }

    private func updateAutomaticShift(before editedContext: String? = nil) {
        guard shift != .locked else { return }
        let next: Shift
        if !settings.autoCapitalization || isAddressField || isNumberField {
            next = .off
        } else if let before = editedContext ?? textDocumentProxy.documentContextBeforeInput ?? (textDocumentProxy.hasText ? nil : "") {
            switch textDocumentProxy.autocapitalizationType ?? .sentences {
            case .none: next = .off
            case .allCharacters: next = .on
            case .words: next = before.last?.isWhitespace != false ? .on : .off
            case .sentences:
                next = KeyboardTextEditing.startsSentence(after: before) ? .on : .off
            @unknown default: next = .off
            }
        } else {
            next = .off
        }
        guard next != shift else { return }
        shift = next
        updateShiftKeys()
    }

    @objc private func shiftTapped() {
        playClick()
        let now = ProcessInfo.processInfo.systemUptime
        if settings.enableCapsLock, let previous = lastShiftTap, now - previous < 0.34 {
            shift = .locked
            lastShiftTap = nil
        } else {
            shift = shift == .off ? .on : .off
            lastShiftTap = now
        }
        updateShiftKeys()
    }

    @objc private func pageTapped(_ button: UIButton) {
        playClick()
        emojiMode = false
        emojiSearch = nil
        switch button.title(for: .normal) {
        case "123": page = .symbols
        case "#+=": page = .more
        default: page = .letters
        }
        lastSpaceAt = nil
        lastShiftTap = nil
        rebuild()
        scheduleSuggestions()
    }

    @objc private func deleteDown() {
        stopDelete()
        deleteRepeated = false
        let delay = Timer(timeInterval: Self.holdDelay, repeats: false) { [weak self] _ in
            guard let self else { return }
            self.deleteRepeated = true
            self.playHaptic(hold: true)
            self.playClick()
            self.performDelete()
            let repeating = Timer(timeInterval: 0.07, repeats: true) { [weak self] _ in self?.performDelete() }
            self.deleteTimer = repeating
            RunLoop.main.add(repeating, forMode: .common)
        }
        deleteTimer = delay
        RunLoop.main.add(delay, forMode: .common)
    }

    @objc private func deleteTapped() {
        stopDelete()
        if !deleteRepeated {
            playClick()
            performDelete()
        }
    }

    @objc private func dragDelete(_ gesture: UIPanGestureRecognizer) {
        if gesture.state == .began {
            stopDelete()
            hidePopup()
        } else if gesture.state == .ended, !deleteRepeated, gesture.translation(in: rowsView).x <= -22 {
            playClick()
            deleteWord()
        }
    }

    func deleteWord() {
        if emojiMode, let query = emojiSearch {
            updateEmojiSearch(String(query.dropLast(KeyboardTextEditing.trailingWordDeletionCount(in: query))))
            return
        }
        microphonePreview?.reset()
        let proxy = textDocumentProxy
        if proxy.selectedText?.isEmpty == false {
            performDelete()
            return
        }
        guard let before = proxy.documentContextBeforeInput, !before.isEmpty else { return }
        pendingSwipe?.cancel()
        lastSwipe = nil
        pendingUndo = nil
        lastSpaceAt = nil
        lastShiftTap = nil
        let count = KeyboardTextEditing.trailingWordDeletionCount(in: before)
        for _ in 0..<count { proxy.deleteBackward() }
        updateAutomaticShift()
        scheduleSuggestions()
    }

    private func performDelete() {
        if emojiMode, let query = emojiSearch {
            updateEmojiSearch(String(query.dropLast()))
            return
        }
        microphonePreview?.reset()
        pendingSwipe?.cancel()
        let proxy = textDocumentProxy
        if let swipe = validSwipe {
            lastSwipe = nil
            for _ in swipe.word { proxy.deleteBackward() }
            updateAutomaticShift()
            scheduleSuggestions()
            return
        }
        lastSwipe = nil
        let undo = pendingUndo
        pendingUndo = nil
        suggestionInsert = nil
        lastSpaceAt = nil
        lastShiftTap = nil
        if let undo, proxy.selectedText?.isEmpty != false,
           proxy.documentContextBeforeInput?.hasSuffix(undo.replacement) == true {
            for _ in undo.replacement { proxy.deleteBackward() }
            proxy.insertText(undo.original)
            // Like iOS: a reverted correction is learned so it isn't corrected again.
            learn(undo.original)
        } else {
            proxy.deleteBackward()
        }
        updateAutomaticShift()
        scheduleSuggestions()
    }

    @objc private func stopDelete() {
        deleteTimer?.invalidate()
        deleteTimer = nil
    }

    @objc private func spaceTapped() {
        if emojiMode, let query = emojiSearch {
            playClick()
            updateEmojiSearch(query + " ")
            return
        }
        microphonePreview?.reset()
        pendingSwipe?.cancel()
        lastSwipe = nil
        playClick()
        let now = ProcessInfo.processInfo.systemUptime
        let proxy = textDocumentProxy
        let before = proxy.documentContextBeforeInput
        lastShiftTap = nil
        if settings.periodShortcut, !isAddressField, let last = lastSpaceAt, now - last < 0.4,
           proxy.selectedText?.isEmpty != false, let before,
           before.last == " ", let previous = before.dropLast().last,
           previous.isLetter || previous.isNumber {
            pendingUndo = nil
            lastSpaceAt = nil
            replacingText = true
            proxy.deleteBackward()
            proxy.insertText(". ")
            replacingText = false
            updateAutomaticShift(before: String(before.suffix(64).dropLast()) + ". ")
        } else {
            let word = currentWord
            suggestionInsert = nil
            if let replacement = autocorrection(for: word) {
                replaceWord(word, with: replacement)
            } else {
                pendingUndo = nil
                proxy.insertText(" ")
            }
            lastSpaceAt = now
            updateAutomaticShift(before: before.map { String($0.suffix(64)) + " " })
        }
        if page != .letters {
            page = .letters
            rebuild()
        }
        scheduleSuggestions()
    }

    @objc private func returnTapped() {
        insert("\n")
    }

    @objc private func dragCursor(_ gesture: UILongPressGestureRecognizer) {
        moveCursor(for: gesture)
    }

    @objc private func dragSpacebarVertically(_ gesture: UIPanGestureRecognizer) {
        moveCursor(for: gesture, verticalOnly: true)
    }

    @objc private func swipeSpacebar(_ gesture: UISwipeGestureRecognizer) {
        switchLanguage(movingRight: gesture.direction == .right)
    }

    @objc private func nextLanguage() -> Bool {
        switchLanguage(movingRight: false)
        return true
    }

    private func switchLanguage(movingRight: Bool) {
        let next = settings.language(after: language, movingRight: movingRight)
        guard next != language else { return }
        language = next
        UserDefaults.standard.set(next.rawValue, forKey: Self.lastLanguageKey)
        pendingSwipe?.cancel()
        lastSwipe = nil
        lastSpaceAt = nil
        playHaptic(hold: true)
        let animate = !UIAccessibility.isReduceMotionEnabled
        UIView.transition(with: rowsView, duration: animate ? 0.2 : 0, options: [.transitionCrossDissolve, .allowUserInteraction]) {
            self.rebuild()
        }
        // The new language name slides in from the side the finger moved away from.
        if animate, let label = spaceKey?.titleLabel {
            label.transform = CGAffineTransform(translationX: movingRight ? -48 : 48, y: 0)
            label.alpha = 0
            UIView.animate(withDuration: 0.32, delay: 0, usingSpringWithDamping: 0.82, initialSpringVelocity: 0,
                           options: [.allowUserInteraction]) {
                label.transform = .identity
                label.alpha = 1
            }
        }
        updateAutomaticShift()
        scheduleSuggestions()
        preloadSwipeWords()
        UIAccessibility.post(notification: .announcement, argument: language.title)
    }

    private func moveCursor(for gesture: UIGestureRecognizer, verticalOnly: Bool = false) {
        let location = gesture.location(in: rowsView)
        let point = verticalOnly ? CGPoint(x: 0, y: location.y) : location
        switch gesture.state {
        case .began:
            hidePopup()
            cursorGesture = gesture
            let travel = (gesture as? UIPanGestureRecognizer)?.translation(in: rowsView).y ?? 0
            startCursorDrag(at: CGPoint(x: point.x, y: point.y - travel))
            continueCursorDrag(to: point, at: ProcessInfo.processInfo.systemUptime)
        case .changed:
            if cursorGesture === gesture { continueCursorDrag(to: point, at: ProcessInfo.processInfo.systemUptime) }
        case .ended, .cancelled, .failed:
            guard cursorGesture === gesture else { return }
            if gesture.state == .ended { continueCursorDrag(to: point, at: ProcessInfo.processInfo.systemUptime) }
            finishCursorDrag()
        default:
            break
        }
    }

    func startCursorDrag(at point: CGPoint) {
        cursorDocument = textDocumentProxy.documentIdentifier
        cursorDrag = KeyboardCursorDrag(origin: point)
        pendingSwipe?.cancel()
        predictionTask?.cancel()
        lastSwipe = nil
        lastSpaceAt = nil
        pendingUndo = nil
        clearSuggestions()
        setTrackpadMode(true)
    }

    /// Apple's trackpad look: blank key labels and the strip so the caret is the focus.
    private func setTrackpadMode(_ on: Bool) {
        if on { playHaptic(hold: true) }
        let alpha: CGFloat = on ? 0 : 1
        UIView.animate(withDuration: UIAccessibility.isReduceMotionEnabled ? 0 : 0.15, delay: 0,
                       options: [.beginFromCurrentState, .allowUserInteraction]) {
            for view in self.rowsView.subviews {
                if let key = view as? KeyboardKeyButton {
                    key.titleLabel?.alpha = alpha
                    key.imageView?.alpha = alpha
                } else {
                    view.alpha = alpha
                }
            }
        }
    }

    func continueCursorDrag(to point: CGPoint, at time: TimeInterval? = nil) {
        guard var drag = cursorDrag else { return }
        let steps = drag.steps(towards: point, at: time)
        guard steps.characters != 0 || steps.lines != 0 else { return }

        let proxy = textDocumentProxy
        guard proxy.documentIdentifier == cursorDocument else {
            finishCursorDrag()
            return
        }
        var offset = steps.characters
        if steps.lines != 0, proxy.selectedText?.isEmpty != false, !isAddressField {
            let before = proxy.documentContextBeforeInput ?? ""
            let after = proxy.documentContextAfterInput ?? ""
            let move = KeyboardTextEditing.verticalCursorMove(
                before: before, after: after, lines: steps.lines, preferredColumn: drag.preferredColumn
            ) ?? wrappedCursorMove(before: before, after: after, lines: steps.lines, preferredColumn: drag.preferredColumn)
            if let move {
                offset += move.offset
                drag.preferredColumn = move.column
            } else if !before.isEmpty || !after.isEmpty || proxy.hasText {
                // Some hosts stop their context at the paragraph boundary.
                // Ask for a single character across that edge. iOS clamps at the document boundary.
                let edge = steps.lines < 0 ? before.suffix(512).count : after.prefix(512).count
                if edge < 512 { offset += steps.lines < 0 ? -edge - 1 : edge + 1 }
                drag.preferredColumn = nil
            }
        }
        if steps.characters != 0 { drag.preferredColumn = nil }
        cursorDrag = drag
        if offset != 0 { proxy.adjustTextPosition(byCharacterOffset: offset) }
    }

    private func wrappedCursorMove(
        before: String, after: String, lines: Int, preferredColumn: Int?
    ) -> (offset: Int, column: Int)? {
        let font = UIFont.preferredFont(forTextStyle: .body)
        let width = max(180, (layout?.width ?? 390) - 44)
        let typesettingFont = CTFontCreateWithName(font.fontName as CFString, font.pointSize, nil)
        return KeyboardTextEditing.wrappedCursorMove(
            before: before, after: after, lines: lines, preferredColumn: preferredColumn,
            lineWidth: width, font: typesettingFont
        )
    }

    func finishCursorDrag() {
        if cursorDrag != nil { setTrackpadMode(false) }
        cursorDrag = nil
        cursorDocument = nil
        cursorGesture = nil
        if visible {
            refreshLayout()
            updateAutomaticShift()
            scheduleSuggestions()
        }
    }

    private func addHold(to key: KeyboardKeyButton) {
        let hold = UILongPressGestureRecognizer(target: self, action: #selector(holdForChoices(_:)))
        hold.minimumPressDuration = Self.holdDelay
        key.addGestureRecognizer(hold)
    }

    /// Press-and-hold popup: slide to a choice and release, like accents on the system keyboard.
    @objc private func holdForChoices(_ gesture: UILongPressGestureRecognizer) {
        guard let button = gesture.view as? KeyboardKeyButton else { return }
        let point = gesture.location(in: rowsView)
        switch gesture.state {
        case .began:
            hidePopup()
            guard let choices = holdChoices(for: button) else { return }
            showChoices(choices.rows, from: button, onSelect: choices.action)
            selectChoice(at: point)
        case .changed:
            selectChoice(at: point)
        case .ended:
            let value = selectedAccent.map { accentChoices[$0].1 }
            let action = popupAction
            clearAccents()
            if let value { action?(value) }
        case .cancelled, .failed:
            clearAccents()
        default:
            break
        }
    }

    private func holdChoices(for button: KeyboardKeyButton) -> (rows: [[String]], action: (String) -> Void)? {
        switch button.accessibilityIdentifier {
        case "period-key":
            return ([[":", "^", "%", "$", "#"], ["@", "'", "!", "?", ","]], { [weak self] in self?.insert($0) })
        case "emoji-key":
            return ([["🙂", ","]], { [weak self] in self?.chooseEmojiKey(comma: $0 == ",") })
        default:
            guard let key = button.accessibilityIdentifier, let variants = accents[key] else { return nil }
            let values = variants.map { shift == .off ? String($0) : String($0).uppercased() }
            return ([values], { [weak self] in self?.insert($0) })
        }
    }

    private func showChoices(_ rows: [[String]], from button: UIButton, onSelect: @escaping (String) -> Void) {
        clearAccents()
        let cell: CGFloat = 34
        let rowHeight: CGFloat = 40
        let width = CGFloat(rows.map(\.count).max() ?? 0) * cell
        let height = CGFloat(rows.count) * rowHeight
        let x = min(max(4, button.frame.midX - cell / 2), max(4, rowsView.bounds.width - width - 4))
        let strip = UIView(frame: CGRect(x: x, y: max(1, button.frame.minY - height - 2), width: width, height: height))
        strip.backgroundColor = isDark ? UIColor(white: 0.24, alpha: 1) : .white
        strip.layer.cornerRadius = 10
        strip.isUserInteractionEnabled = false
        for (row, values) in rows.enumerated() {
            for (column, value) in values.enumerated() {
                let label = UILabel(frame: CGRect(x: CGFloat(column) * cell, y: CGFloat(row) * rowHeight, width: cell, height: rowHeight))
                label.text = value
                label.font = .systemFont(ofSize: 23)
                label.textAlignment = .center
                label.textColor = labelColor
                label.clipsToBounds = true
                label.layer.cornerRadius = 8
                strip.addSubview(label)
                accentChoices.append((label, value))
            }
        }
        rowsView.addSubview(strip)
        accentStrip = strip
        popupAction = onSelect
        playHaptic(hold: true)
    }

    private func selectChoice(at point: CGPoint) {
        guard let strip = accentStrip else { return }
        let local = CGPoint(x: point.x - strip.frame.minX, y: point.y - strip.frame.minY)
        guard let index = KeyboardKeyGeometry.nearestIndex(at: local, in: accentChoices.map { $0.0.frame }),
              index != selectedAccent else { return }
        if let previous = selectedAccent {
            accentChoices[previous].0.backgroundColor = .clear
            accentChoices[previous].0.textColor = labelColor
        }
        selectedAccent = index
        accentChoices[index].0.backgroundColor = accentColor
        accentChoices[index].0.textColor = isDark ? .black : .white
    }

    private func clearAccents() {
        accentStrip?.removeFromSuperview()
        accentStrip = nil
        accentChoices.removeAll(keepingCapacity: true)
        selectedAccent = nil
        popupAction = nil
    }

    // MARK: - Popup and key clicks

    private func showPopup(for button: UIButton) {
        hidePopup()
        pressedKey = button
        let tint = UIView(frame: button.bounds)
        tint.backgroundColor = labelColor.withAlphaComponent(isDark ? 0.19 : 0.1)
        tint.layer.cornerRadius = button.layer.cornerRadius
        tint.isUserInteractionEnabled = false
        button.addSubview(tint)
        pressTint = tint
        guard settings.characterPreview, let title = button.title(for: .normal),
              title.count == 1, layout?.compact != true else { return }
        let width: CGFloat = max(button.frame.width, 48)
        let x = min(max(2, button.frame.midX - width / 2), max(2, rowsView.bounds.width - width - 2))
        let label = UILabel(frame: CGRect(x: x, y: max(1, button.frame.minY - 45), width: width, height: 42))
        label.text = title
        label.font = .systemFont(ofSize: 30)
        label.textAlignment = .center
        label.textColor = labelColor
        label.backgroundColor = isDark ? UIColor(white: 0.42, alpha: 1) : .white
        label.layer.cornerRadius = 9
        label.clipsToBounds = true
        rowsView.addSubview(label)
        popup = label
    }

    private func hidePopup() {
        popup?.removeFromSuperview()
        popup = nil
        pressTint?.removeFromSuperview()
        pressTint = nil
        pressedKey = nil
    }

    private func playClick() {
        guard settings.keyClicks else { return }
        UIDevice.current.playInputClick()
    }

    // MARK: - Swipe typing

    private var swipeLocale: String { language.rawValue }

    private func preloadSwipeWords() {
        guard settings.usesSwipeTyping else {
            swipeLexiconLoad?.cancel()
            loadingSwipeLocale = nil
            swipeLexicon = nil
            return
        }
        let locale = swipeLocale
        guard loadingSwipeLocale != locale else { return }
        swipeLexiconLoad?.cancel()
        guard swipeLexicon?.locale != locale else {
            loadingSwipeLocale = nil
            return
        }
        loadingSwipeLocale = locale
        let url = Bundle(for: KeyboardViewController.self).url(forResource: "swipe-" + locale, withExtension: "txt")
        swipeLexiconLoad = Task { @MainActor [weak self] in
            let words = await Task.detached(priority: .utility) {
                guard let url, let data = try? String(contentsOf: url, encoding: .utf8) else { return nil as KeyboardSwipeLexicon? }
                return KeyboardSwipeLexicon(data)
            }.value
            guard !Task.isCancelled, let self else { return }
            self.loadingSwipeLocale = nil
            if let words { self.swipeLexicon = (locale, words) }
        }
    }

    private var validSwipe: SwipeCommit? {
        let proxy = textDocumentProxy
        guard let swipe = lastSwipe, proxy.documentIdentifier == swipe.document,
              proxy.selectedText?.isEmpty != false,
              proxy.documentContextBeforeInput?.hasSuffix(swipe.prefix + swipe.word) == true,
              String((proxy.documentContextAfterInput ?? "").prefix(24)) == swipe.contextAfter else { return nil }
        return swipe
    }

    @objc private func handleSwipe(_ gesture: KeyboardSwipeGesture) {
        switch gesture.state {
        case .began, .changed:
            if gesture.state == .began {
                hidePopup()
                pendingSwipe?.cancel()
                predictionTask?.cancel()
                scheduledSuggestion = nil
                swipeTrail.strokeColor = accentColor.withAlphaComponent(0.85).cgColor
                rowsView.layer.addSublayer(swipeTrail)
            }
            let trail = UIBezierPath()
            if let first = gesture.points.first { trail.move(to: first) }
            for point in gesture.points.dropFirst() { trail.addLine(to: point) }
            swipeTrail.path = trail.cgPath
        case .ended:
            swipeTrail.removeFromSuperlayer()
            completeSwipe(trace: gesture.points, pauses: gesture.pauses)
        case .cancelled, .failed:
            swipeTrail.removeFromSuperlayer()
            scheduleSuggestions()
        default:
            break
        }
    }

    private func completeSwipe(trace: [CGPoint], pauses: [CGPoint]) {
        let centers: [UInt8: CGPoint] = Dictionary(uniqueKeysWithValues: letterKeys.compactMap { key in
            guard let character = key.letter.first, let letter = KeyboardSwipeLexicon.key(for: character) else { return nil }
            return (letter, CGPoint(x: key.button.frame.midX, y: key.button.frame.midY))
        })
        guard let q = centers[113], let w = centers[119] else { return }
        let pitch = w.x - q.x
        let locale = swipeLocale
        let proxy = textDocumentProxy
        let document = proxy.documentIdentifier
        let before = String((proxy.documentContextBeforeInput ?? "").suffix(48))
        let after = String((proxy.documentContextAfterInput ?? "").prefix(24))
        let selection = String((proxy.selectedText ?? "").prefix(24))
        guard !selection.isEmpty || before.last?.isLetter != true || after.first?.isLetter != true else { return }

        pendingSwipe?.cancel()
        if let lexicon = swipeLexicon, lexicon.locale == locale {
            insertSwipeCandidates(KeyboardSwipeDecoder.candidates(
                trace: trace, pauses: pauses, centers: centers, pitch: pitch, lexicon: lexicon.words
            ))
            return
        }

        preloadSwipeWords()
        let load = swipeLexiconLoad
        pendingSwipe = Task { @MainActor [weak self] in
            await load?.value
            guard !Task.isCancelled, let self, let lexicon = self.swipeLexicon, lexicon.locale == locale else { return }
            guard self.visible,
                  self.textDocumentProxy.documentIdentifier == document,
                  String((self.textDocumentProxy.documentContextBeforeInput ?? "").suffix(48)) == before,
                  String((self.textDocumentProxy.documentContextAfterInput ?? "").prefix(24)) == after,
                  String((self.textDocumentProxy.selectedText ?? "").prefix(24)) == selection else { return }
            self.insertSwipeCandidates(KeyboardSwipeDecoder.candidates(
                trace: trace, pauses: pauses, centers: centers, pitch: pitch, lexicon: lexicon.words
            ))
        }
    }

    func insertSwipeCandidates(_ words: [String]) {
        microphonePreview?.reset()
        let cased = words.map { word in
            switch shift {
            case .off: return word
            case .on: return word.prefix(1).uppercased() + word.dropFirst()
            case .locked: return word.uppercased()
            }
        }
        guard let word = cased.first else { return }
        let proxy = textDocumentProxy
        let before = proxy.documentContextBeforeInput ?? ""
        let needsSpace = before.last.map { $0.isLetter || $0.isNumber || ",;:!?.".contains($0) } == true
        let separator = needsSpace && proxy.selectedText?.isEmpty != false ? " " : ""
        let prefix = String(before.suffix(24)) + separator
        let document = proxy.documentIdentifier
        let after = String((proxy.documentContextAfterInput ?? "").prefix(24))
        pendingUndo = nil
        lastSpaceAt = nil
        lastShiftTap = nil
        lastSwipe = nil
        playClick()
        proxy.insertText(separator + word)
        lastSwipe = SwipeCommit(document: document, prefix: prefix, word: word,
                                alternatives: Array(cased.dropFirst()), contextAfter: after)
        updateAutomaticShift()
        scheduleSuggestions()
    }

    private func replaceSwipe(_ swipe: SwipeCommit, with replacement: String) {
        let proxy = textDocumentProxy
        replacingText = true
        for _ in swipe.word { proxy.deleteBackward() }
        proxy.insertText(replacement)
        replacingText = false
        let alternatives = [swipe.word] + swipe.alternatives.filter { $0 != replacement }
        lastSwipe = SwipeCommit(document: swipe.document, prefix: swipe.prefix, word: replacement,
                                alternatives: alternatives, contextAfter: swipe.contextAfter)
        playClick()
        scheduleSuggestions()
    }

    // MARK: - Emoji picker

    @objc private func openEmojiPicker() {
        microphonePreview?.reset()
        playClick()
        predictionTask?.cancel()
        emojiMode = true
        rebuild()
    }

    private func addEmojiView(width: CGFloat, pad: CGFloat, height: CGFloat, rowHeight: CGFloat) {
        let bottomY = height - rowHeight - 6
        let letters = pageKey("ABC")
        let delete = deleteKey()
        let globe = layout?.globe == true ? globeKey() : nil
        let picker = KeyboardEmojiPicker(
            frame: CGRect(x: pad, y: 2, width: width - 2 * pad, height: bottomY + rowHeight - 2),
            accent: accentColor, ink: labelColor, footerHeight: rowHeight,
            letters: letters, delete: delete, globe: globe,
            onSearch: { [weak self] in self?.beginEmojiSearch() }
        ) { [weak self] emoji in
            self?.insert(emoji)
        }
        rowsView.addSubview(picker)
    }

    // MARK: - Emoji search

    private func beginEmojiSearch() {
        playClick()
        emojiSearch = ""
        shift = .off
        rebuild()
    }

    @objc private func endEmojiSearch() {
        playClick()
        emojiSearch = nil
        rebuild()
    }

    /// Done leaves emoji entirely, back to the letters, like the system keyboard.
    @objc private func closeEmojiSearch() {
        playClick()
        emojiSearch = nil
        emojiMode = false
        page = .letters
        rebuild()
        updateAutomaticShift()
        scheduleSuggestions()
    }

    private func updateEmojiSearch(_ query: String) {
        let trimmed = String(query.prefix(60))
        emojiSearch = trimmed
        emojiSearchBar?.show(query: trimmed, matches: Self.emojiMatches(for: trimmed))
    }

    private static func emojiMatches(for query: String) -> [String] {
        query.trimmingCharacters(in: .whitespaces).isEmpty
            ? KeyboardEmojiPicker.searchSuggestions
            : KeyboardEmojiPicker.searchEmoji(matching: query)
    }

    /// Apple's layout: search field, one row of results, then the normal letter keys.
    private func addEmojiSearch(query: String, width: CGFloat, pad: CGFloat, rowHeight: CGFloat, gap: CGFloat) {
        let compact = layout?.compact == true
        let fieldHeight: CGFloat = compact ? 30 : 38
        let resultsHeight: CGFloat = compact ? 34 : 50
        let top = fieldHeight + resultsHeight + 12
        let height = top + 4 * rowHeight + 3 * gap + 10
        setKeyboardHeight(height)

        let bar = KeyboardEmojiSearchBar(
            frame: CGRect(x: pad, y: 4, width: width - 2 * pad, height: fieldHeight + 4 + resultsHeight),
            ink: labelColor, accent: accentColor, fieldHeight: fieldHeight,
            onClear: { [weak self] in self?.updateEmojiSearch("") },
            onPick: { [weak self] emoji in
                KeyboardEmojiPicker.remember(emoji)
                self?.playClick()
                self?.textDocumentProxy.insertText(emoji)
            }
        )
        rowsView.addSubview(bar)
        emojiSearchBar = bar
        bar.show(query: query, matches: Self.emojiMatches(for: query))

        let step = rowHeight + gap
        let spanish = language == .spanish
        addLettersRow(keys: "QWERTYUIOP", y: top, h: rowHeight, width: width, pad: pad)
        addLettersRow(keys: spanish ? "ASDFGHJKLÑ" : "ASDFGHJKL", y: top + step,
                      h: rowHeight, width: width, pad: pad, inset: spanish ? 0 : 0.5)
        addLettersRow(keys: "ZXCVBNM", y: top + 2 * step, h: rowHeight, width: width, pad: pad,
                      left: shiftKey(), right: deleteKey())

        let back = symbolKey("face.smiling", label: "Back to emoji")
        back.addTarget(self, action: #selector(endEmojiSearch), for: .touchUpInside)
        let space = makeKey(background: keyColor)
        space.setTitle(language.spaceTitle, for: .normal)
        space.setTitleColor(labelColor.withAlphaComponent(0.8), for: .normal)
        space.titleLabel?.font = .systemFont(ofSize: 14)
        space.accessibilityLabel = "Space"
        space.addTarget(self, action: #selector(spaceTapped), for: .touchUpInside)
        let done = symbolKey("checkmark", label: "Done")
        done.backgroundColor = accentColor
        done.tintColor = isDark ? .black : .white
        done.addTarget(self, action: #selector(closeEmojiSearch), for: .touchUpInside)
        placeRow([(back, 1.15), (space, 5.6), (done, 2.1)], y: top + 3 * step, height: rowHeight, width: width, pad: pad)
    }
}

extension KeyboardViewController: UIGestureRecognizerDelegate {
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        guard gestureRecognizer === swipeGesture else { return true }
        guard settings.usesSwipeTyping, emojiSearch == nil,
              page == .letters, !emojiMode, !isNumberField, !isAddressField,
              let key = rowsView.nearestKey(at: touch.location(in: rowsView)) else { return false }
        return letterKeys.contains { $0.button === key }
    }

    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard let pan = gestureRecognizer as? UIPanGestureRecognizer else { return true }
        let movement = pan.translation(in: rowsView)
        switch pan.view?.accessibilityLabel {
        case "Space": return cursorDrag == nil && !isAddressField && abs(movement.y) > abs(movement.x) * 1.5
        case "Delete": return !deleteRepeated && movement.x < -abs(movement.y)
        default: return true
        }
    }
}
