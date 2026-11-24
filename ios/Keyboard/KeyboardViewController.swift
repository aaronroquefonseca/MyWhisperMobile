import CoreText
import UIKit

/// MyWhisper keyboard with local predictions, autocorrect, and emoji.
class KeyboardViewController: UIInputViewController {

    private enum Page { case letters, symbols, more }
    private enum Shift { case off, on, locked }

    private var page: Page = .letters
    private var shift: Shift = .off
    private var settings = KeyboardPreferences.load()
    private lazy var language = settings.defaultLanguage
    private weak var stripNotice: UIButton?
    private var noticeTask: Task<Void, Never>?
    private var pendingUndo: (original: String, replacement: String)?
    private var emojiMode = false
    private let rowsView = KeyboardKeyArea()
    private var layout: Layout?
    private var letterKeys: [(button: KeyboardKeyButton, letter: String)] = []
    private weak var shiftButton: KeyboardKeyButton?
    private var predictionButtons: [KeyboardSuggestionButton] = []
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
        var allowsCorrection: Bool
    }

    private struct Suggestion {
        var document: UUID
        var word: String
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

    override func loadView() {
        inputView = KeyboardInputView(frame: .zero, inputViewStyle: .keyboard)
    }

    override func viewDidLoad() {
        super.viewDidLoad()
        rowsView.translatesAutoresizingMaskIntoConstraints = false
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
        noticeTask?.cancel()
        stripNotice?.isHidden = true
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
        }
        layout = next
        isDark = dark
        view.backgroundColor = dark
            ? UIColor(red: 30 / 255, green: 32 / 255, blue: 35 / 255, alpha: 1)
            : UIColor(red: 217 / 255, green: 223 / 255, blue: 225 / 255, alpha: 1)
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
        let word = currentWord
        guard (2...28).contains(word.count) else {
            clearSuggestions()
            return
        }
        let proxy = textDocumentProxy
        let input = SuggestionInput(document: proxy.documentIdentifier, word: word,
                                    allowsCorrection: proxy.autocorrectionType != .no)
        guard input != scheduledSuggestion else { return }
        scheduledSuggestion = input
        suggestion = nil
        predictionTask?.cancel()
        predictionTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 42_000_000)
            guard !Task.isCancelled, let self, self.visible,
                  self.textDocumentProxy.documentIdentifier == input.document, self.currentWord == input.word else { return }
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
        suggestion = Suggestion(document: input.document, word: word, correction: correction)
        guard settings.predictive else { return }
        let range = NSRange(word.startIndex..., in: word)
        let completions = checker.completions(forPartialWordRange: range, in: word, language: language) ?? []
        var seen = Set<String>()
        let choices = ([correction].compactMap { $0 } + completions).filter {
            $0.caseInsensitiveCompare(word) != .orderedSame && seen.insert($0.lowercased()).inserted
        }
        showSuggestions(choices)
    }

    private func showSuggestions(_ choices: [String]) {
        for (index, button) in predictionButtons.enumerated() {
            button.display(choices.indices.contains(index) ? choices[index] : nil)
        }
    }

    @objc private func predictionTapped(_ button: UIButton) {
        guard let replacement = button.title(for: .normal) else { return }
        if let swipe = validSwipe {
            guard replacement != swipe.word, swipe.alternatives.contains(replacement) else { return }
            replaceSwipe(swipe, with: replacement)
            return
        }
        guard let suggestion, suggestion.document == textDocumentProxy.documentIdentifier, suggestion.word == currentWord else { return }
        replaceWord(suggestion.word, with: replacement)
        lastSpaceAt = nil
        playClick()
        updateAutomaticShift()
        scheduleSuggestions()
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
        letterKeys.removeAll(keepingCapacity: true)
        shiftButton = nil

        let width = layout.width
        let pad: CGFloat = layout.compact ? 10 : 6
        let rowHeight: CGFloat = layout.compact ? 31 : 44
        let gap: CGFloat = layout.compact ? 6 : 10
        let stripHeight: CGFloat = layout.compact ? 30 : 43
        let height = stripHeight + 4 * rowHeight + 3 * gap + 10
        if heightConstraint?.constant != height { heightConstraint?.constant = height }

        if emojiMode {
            addEmojiView(width: width, pad: pad, height: height, rowHeight: rowHeight)
            return
        }

        addPredictionRow(width: width, pad: pad, height: stripHeight)
        let y = stripHeight + 4
        let step = rowHeight + gap
        if isNumberField {
            addNumberPad(y: y, step: step, height: rowHeight, width: width, pad: pad)
            return
        }
        switch page {
        case .letters:
            addLettersRow(keys: "QWERTYUIOP", y: y, h: rowHeight, width: width, pad: pad)
            let spanish = language == .spanish
            addLettersRow(keys: spanish ? "ASDFGHJKLÑ" : "ASDFGHJKL", y: y + step,
                          h: rowHeight, width: width, pad: pad, inset: spanish ? 0 : 0.5)
            addLettersRow(keys: "ZXCVBNM", y: y + 2 * step, h: rowHeight, width: width, pad: pad,
                          left: shiftKey(), right: deleteKey())
        case .symbols:
            addLettersRow(keys: "1234567890", y: y, h: rowHeight, width: width, pad: pad)
            let symbol = language == .spanish ? "€" : (Locale(identifier: language.rawValue).currencySymbol ?? "$")
            let currency = symbol.count == 1 ? symbol : "$"
            addLettersRow(keys: "-/:;()\(currency)&@\"", y: y + step, h: rowHeight, width: width, pad: pad)
            addLettersRow(keys: ".,?!'", y: y + 2 * step, h: rowHeight, width: width, pad: pad,
                          left: pageKey("#+="), right: deleteKey())
        case .more:
            addLettersRow(keys: "[]{}#%^*+=", y: y, h: rowHeight, width: width, pad: pad)
            addLettersRow(keys: "_\\|~<>€£¥•", y: y + step, h: rowHeight, width: width, pad: pad)
            addLettersRow(keys: ".,?!'", y: y + 2 * step, h: rowHeight, width: width, pad: pad,
                          left: pageKey("123"), right: deleteKey())
        }
        addBottomRow(y: y + 3 * step, h: rowHeight, width: width, pad: pad)
    }

    private func addPredictionRow(width: CGFloat, pad: CGFloat, height: CGFloat) {
        let microphoneWidth: CGFloat = 44
        let microphone = plainButton(systemName: "mic", size: 19)
        microphone.tintColor = accentColor
        microphone.frame = CGRect(x: width - pad - microphoneWidth, y: 0, width: microphoneWidth, height: height)
        microphone.accessibilityLabel = "MyWhisper dictation"
        microphone.addTarget(self, action: #selector(microphoneTapped), for: .touchUpInside)
        rowsView.addSubview(microphone)

        let available = width - pad * 2 - microphoneWidth
        let slot = available / 3
        for index in 0..<3 {
            let x = pad + CGFloat(index) * slot
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
            }
        }

        let notice = plainButton(systemName: nil, size: 15)
        notice.frame = CGRect(x: pad, y: 0, width: available, height: height)
        notice.titleLabel?.font = .systemFont(ofSize: 14, weight: .medium)
        notice.titleLabel?.adjustsFontSizeToFitWidth = true
        notice.backgroundColor = view.backgroundColor
        notice.setTitleColor(accentColor, for: .normal)
        notice.setTitleColor(labelColor, for: .disabled)
        notice.addTarget(self, action: #selector(applyTransferredPreferences), for: .touchUpInside)
        notice.isHidden = true
        rowsView.addSubview(notice)
        stripNotice = notice
        updateTransferAction()
    }

    @objc private func microphoneTapped() {
        playClick()
        guard let stripNotice, transfer == nil else { return }
        let message = "Dictate in the MyWhisper app"
        stripNotice.setTitle(message, for: .normal)
        stripNotice.isEnabled = false
        stripNotice.isHidden = false
        UIAccessibility.post(notification: .announcement, argument: message)
        noticeTask?.cancel()
        noticeTask = Task { @MainActor [weak self, weak stripNotice] in
            try? await Task.sleep(nanoseconds: 2_500_000_000)
            guard !Task.isCancelled, self?.transfer == nil else { return }
            stripNotice?.isHidden = true
        }
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
            noticeTask?.cancel()
            stripNotice.setTitle("Apply preferences", for: .normal)
            stripNotice.isEnabled = true
            stripNotice.isHidden = false
        } else if stripNotice.isEnabled {
            stripNotice.isHidden = true
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
        let emoji = symbolKey("face.smiling", label: "Emoji")
        emoji.addTarget(self, action: #selector(openEmojiPicker), for: .touchUpInside)
        keys.append((emoji, 1))

        let space = makeKey(background: keyColor)
        space.setTitle(language.spaceTitle, for: .normal)
        space.setTitleColor(labelColor.withAlphaComponent(0.8), for: .normal)
        space.titleLabel?.font = .systemFont(ofSize: 14)
        space.titleLabel?.adjustsFontSizeToFitWidth = true
        space.accessibilityLabel = "Space"
        space.accessibilityValue = language.title
        space.addTarget(self, action: #selector(spaceTapped), for: .touchUpInside)

        let cursor = UILongPressGestureRecognizer(target: self, action: #selector(dragCursor(_:)))
        cursor.minimumPressDuration = 0.30
        space.addGestureRecognizer(cursor)
        let vertical = UIPanGestureRecognizer(target: self, action: #selector(dragSpacebarVertically(_:)))
        vertical.maximumNumberOfTouches = 1
        vertical.delegate = self
        vertical.require(toFail: cursor)
        space.addGestureRecognizer(vertical)
        if settings.switchesLanguageBySwipe, settings.languages.count > 1 {
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
        if page == .letters {
            keys.append(contentsOf: fieldShortcuts.map { (characterKey($0, cased: false), 1) })
        }
        keys.append((returnKey(), 2.1))
        placeRow(keys, y: y, height: h, width: width, pad: pad)
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

    private func plainButton(systemName: String?, size: CGFloat) -> UIButton {
        let button = UIButton(type: .custom)
        if let systemName {
            button.setImage(symbol(systemName, size: size), for: .normal)
            button.tintColor = labelColor
        }
        return button
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
            if accents[value.lowercased()] != nil {
                let hold = UILongPressGestureRecognizer(target: self, action: #selector(selectAccent(_:)))
                hold.minimumPressDuration = 0.34
                button.addGestureRecognizer(hold)
            }
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
        showPopup(for: button)
    }

    @objc private func keyReleased(_ button: UIButton) {
        if pressedKey === button { hidePopup() }
    }

    @objc private func characterTapped(_ button: UIButton) {
        guard let character = button.title(for: .normal) else { return }
        insert(character)
    }

    private func insert(_ text: String) {
        pendingSwipe?.cancel()
        let startsWord = text.first.map { $0.isLetter || $0.isNumber } == true
        let separator = startsWord && validSwipe != nil ? " " : ""
        lastSwipe = nil
        pendingUndo = nil
        lastSpaceAt = nil
        lastShiftTap = nil
        playClick()
        let proxy = textDocumentProxy
        let before = proxy.documentContextBeforeInput
        let inserted = separator + text
        replacingText = true
        proxy.insertText(inserted)
        replacingText = false
        updateAutomaticShift(before: before.map { String($0.suffix(64)) + inserted } ?? inserted)
        scheduleSuggestions()
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
        let delay = Timer(timeInterval: 0.42, repeats: false) { [weak self] _ in
            guard let self else { return }
            self.deleteRepeated = true
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
        lastSpaceAt = nil
        lastShiftTap = nil
        if let undo, proxy.selectedText?.isEmpty != false,
           proxy.documentContextBeforeInput?.hasSuffix(undo.replacement) == true {
            for _ in undo.replacement { proxy.deleteBackward() }
            proxy.insertText(undo.original)
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
            let word = settings.autoCorrection ? currentWord : ""
            let replacement: String?
            if let suggestion, suggestion.word == word, suggestion.document == proxy.documentIdentifier {
                replacement = suggestion.correction
            } else if !word.isEmpty, proxy.autocorrectionType != .no, let language = spellLanguage {
                replacement = correction(for: word, language: language)
            } else {
                replacement = nil
            }
            if let replacement {
                replaceWord(word, with: replacement)
            } else {
                pendingUndo = nil
                proxy.insertText(" ")
            }
            lastSpaceAt = now
            updateAutomaticShift(before: before.map { String($0.suffix(64)) + " " })
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
        pendingSwipe?.cancel()
        lastSwipe = nil
        lastSpaceAt = nil
        rebuild()
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
            continueCursorDrag(to: point)
        case .changed:
            if cursorGesture === gesture { continueCursorDrag(to: point) }
        case .ended, .cancelled, .failed:
            guard cursorGesture === gesture else { return }
            if gesture.state == .ended { continueCursorDrag(to: point) }
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
    }

    func continueCursorDrag(to point: CGPoint) {
        guard var drag = cursorDrag else { return }
        let steps = drag.steps(towards: point)
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
        cursorDrag = nil
        cursorDocument = nil
        cursorGesture = nil
        if visible {
            refreshLayout()
            updateAutomaticShift()
            scheduleSuggestions()
        }
    }

    @objc private func selectAccent(_ gesture: UILongPressGestureRecognizer) {
        guard let button = gesture.view as? UIButton, let key = button.accessibilityIdentifier,
              let variants = accents[key] else { return }
        let point = gesture.location(in: rowsView)
        switch gesture.state {
        case .began:
            hidePopup()
            showAccents(variants, from: button)
            selectAccent(at: point.x)
        case .changed:
            selectAccent(at: point.x)
        case .ended:
            let value = selectedAccent.map { accentChoices[$0].1 }
            clearAccents()
            if let value { insert(value) }
        case .cancelled, .failed:
            clearAccents()
        default:
            break
        }
    }

    private func showAccents(_ variants: String, from button: UIButton) {
        clearAccents()
        let cell: CGFloat = 34
        let width = CGFloat(variants.count) * cell
        let x = min(max(4, button.frame.midX - cell / 2), max(4, rowsView.bounds.width - width - 4))
        let strip = UIView(frame: CGRect(x: x, y: max(1, button.frame.minY - 42), width: width, height: 40))
        strip.backgroundColor = isDark ? UIColor(white: 0.24, alpha: 1) : .white
        strip.layer.cornerRadius = 10
        strip.isUserInteractionEnabled = false
        for (index, variant) in variants.enumerated() {
            let value = shift == .off ? String(variant) : String(variant).uppercased()
            let label = UILabel(frame: CGRect(x: CGFloat(index) * cell, y: 0, width: cell, height: 40))
            label.text = value
            label.font = .systemFont(ofSize: 23)
            label.textAlignment = .center
            label.textColor = labelColor
            label.clipsToBounds = true
            label.layer.cornerRadius = 8
            strip.addSubview(label)
            accentChoices.append((label, value))
        }
        rowsView.addSubview(strip)
        accentStrip = strip
    }

    private func selectAccent(at x: CGFloat) {
        guard let strip = accentStrip else { return }
        let index = min(max(0, Int((x - strip.frame.minX) / 34)), accentChoices.count - 1)
        guard index != selectedAccent else { return }
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
        playClick()
        predictionTask?.cancel()
        emojiMode = true
        rebuild()
    }

    private func addEmojiView(width: CGFloat, pad: CGFloat, height: CGFloat, rowHeight: CGFloat) {
        let bottomY = height - rowHeight - 6
        let picker = KeyboardEmojiPicker(
            frame: CGRect(x: pad, y: 2, width: width - 2 * pad, height: bottomY - 8),
            accent: accentColor, ink: labelColor
        ) { [weak self] emoji in
            self?.insert(emoji)
        }
        rowsView.addSubview(picker)
        let letters = pageKey("ABC")
        let delete = deleteKey()
        let keyWidth: CGFloat = 51
        letters.frame = CGRect(x: pad, y: bottomY, width: keyWidth, height: rowHeight)
        delete.frame = CGRect(x: width - pad - keyWidth, y: bottomY, width: keyWidth, height: rowHeight)
        rowsView.addSubview(letters)
        rowsView.addSubview(delete)
        if layout?.globe == true {
            let globe = globeKey()
            globe.frame = CGRect(x: pad + keyWidth + 7, y: bottomY, width: rowHeight, height: rowHeight)
            rowsView.addSubview(globe)
        }
    }
}

extension KeyboardViewController: UIGestureRecognizerDelegate {
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        guard gestureRecognizer === swipeGesture else { return true }
        guard settings.usesSwipeTyping,
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
