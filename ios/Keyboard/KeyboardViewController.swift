import UIKit

/// MyWhisper keyboard with local predictions, autocorrect, and emoji.
class KeyboardViewController: UIInputViewController {

    /// In-keyboard settings (extension-local defaults: no App Group exists
    /// under free provisioning, so the main app cannot reach these yet).
    struct KeyboardSettings: Codable, Equatable {
        var autoCapitalization = true
        var autoCorrection = true
        var predictive = true
        var periodShortcut = true
        var enableCapsLock = true
        var characterPreview = true
        var keyClicks = true
        // Optional so older saved settings retain their existing values.
        var swipeTyping: Bool?
        var swipeLanguage: String?

        static let defaultsKey = "mw.keyboard.settings.v1"

        static func load() -> KeyboardSettings {
            guard let data = UserDefaults.standard.data(forKey: defaultsKey),
                  let decoded = try? JSONDecoder().decode(KeyboardSettings.self, from: data)
            else { return KeyboardSettings() }
            return decoded
        }

        func save() {
            if let data = try? JSONEncoder().encode(self) {
                UserDefaults.standard.set(data, forKey: Self.defaultsKey)
            }
        }
    }

    private enum Page { case letters, symbols, more }
    private enum Shift { case off, on, locked }

    private var page: Page = .letters
    private var shift: Shift = .off
    private var showingSettings = false
    private var settings = KeyboardSettings.load()
    private var pendingUndo: (original: String, replacement: String)?
    private var emojiMode = false
    private let rowsView = KeyboardKeyArea()
    private var layout: Layout?
    private var letterKeys: [(button: KeyboardKeyButton, letter: String)] = []
    private weak var shiftButton: KeyboardKeyButton?
    private var predictionButtons: [UIButton] = []
    private var predictionTask: Task<Void, Never>?
    private var suggestion: Suggestion?
    private var visible = false
    private var lastShiftTap: TimeInterval?
    private var cursorDragX: CGFloat = 0
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
    private lazy var spellLanguage: String? = {
        let available = UITextChecker.availableLanguages
        for language in Locale.preferredLanguages {
            let locale = Locale(identifier: language)
            if let exact = available.first(where: { Locale(identifier: $0).identifier == locale.identifier }) {
                return exact
            }
            if let match = available.first(where: { Locale(identifier: $0).languageCode == locale.languageCode }) {
                return match
            }
        }
        return available.first
    }()

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
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        visible = true
        layoutIfNeeded()
        updateAutomaticShift()
        scheduleSuggestions()
        preloadSwipeWords()
    }

    override func viewDidDisappear(_ animated: Bool) {
        super.viewDidDisappear(animated)
        visible = false
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
        layoutIfNeeded()
    }

    override func traitCollectionDidChange(_ previous: UITraitCollection?) {
        super.traitCollectionDidChange(previous)
        guard isViewLoaded else { return }
        view.setNeedsLayout()
    }

    override func textDidChange(_ textInput: UITextInput?) {
        super.textDidChange(textInput)
        guard visible else { return }
        layoutIfNeeded()
        updateAutomaticShift()
        scheduleSuggestions()
    }

    override func selectionDidChange(_ textInput: UITextInput?) {
        super.selectionDidChange(textInput)
        guard visible else { return }
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

    private func layoutIfNeeded() {
        let appearance = textDocumentProxy.keyboardAppearance
        let dark = appearance == .dark || ((appearance == nil || appearance == .default) &&
            traitCollection.userInterfaceStyle == .dark)
        let next = Layout(
            width: view.bounds.width > 0 ? view.bounds.width : 390,
            compact: traitCollection.verticalSizeClass == .compact,
            dark: dark,
            globe: needsInputModeSwitchKey,
            keyboardType: textDocumentProxy.keyboardType ?? .default,
            returnType: textDocumentProxy.returnKeyType ?? .default
        )
        guard layout != next else { return }
        if layout?.keyboardType != next.keyboardType, !emojiMode {
            page = next.keyboardType == .numbersAndPunctuation ? .symbols : .letters
        }
        layout = next
        isDark = dark
        view.backgroundColor = dark
            ? UIColor(red: 30 / 255, green: 32 / 255, blue: 35 / 255, alpha: 1)
            : UIColor(red: 217 / 255, green: 223 / 255, blue: 225 / 255, alpha: 1)
        rebuild()
        if visible { scheduleSuggestions() }
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
        suggestion = nil
        for button in predictionButtons {
            button.setTitle(nil, for: .normal)
            button.isEnabled = false
            button.isAccessibilityElement = false
        }
    }

    private func scheduleSuggestions() {
        predictionTask?.cancel()
        clearSuggestions()
        guard visible, page == .letters, !emojiMode, !showingSettings, !isNumberField, !isAddressField,
              settings.predictive || settings.autoCorrection else { return }
        if let swipe = validSwipe {
            if settings.predictive { showSuggestions([swipe.word] + swipe.alternatives) }
            return
        }
        lastSwipe = nil
        predictionTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 80_000_000)
            guard !Task.isCancelled, let self, self.visible else { return }
            let word = self.currentWord
            guard (2...28).contains(word.count) else { return }
            self.updateSuggestions(for: word, document: self.textDocumentProxy.documentIdentifier)
        }
    }

    private func updateSuggestions(for word: String, document: UUID) {
        guard let language = spellLanguage else { return }
        let range = NSRange(word.startIndex..., in: word)
        var correction: String?
        if settings.autoCorrection, word.count >= 3, textDocumentProxy.autocorrectionType != .no,
           checker.rangeOfMisspelledWord(in: word, range: range, startingAt: 0, wrap: false, language: language) == range {
            correction = checker.guesses(forWordRange: range, in: word, language: language)?.first { candidate in
                candidate.first?.lowercased() == word.first?.lowercased() &&
                    abs(candidate.count - word.count) <= 1 && !candidate.contains(" ")
            }
        }
        suggestion = Suggestion(document: document, word: word, correction: correction)
        guard settings.predictive else { return }
        let completions = checker.completions(forPartialWordRange: range, in: word, language: language) ?? []
        var seen = Set<String>()
        let choices = ([correction].compactMap { $0 } + completions).filter {
            $0.caseInsensitiveCompare(word) != .orderedSame && seen.insert($0.lowercased()).inserted
        }
        showSuggestions(choices)
    }

    private func showSuggestions(_ choices: [String]) {
        for (button, choice) in zip(predictionButtons, choices) {
            button.setTitle(choice, for: .normal)
            button.isEnabled = true
            button.isAccessibilityElement = true
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
        for _ in word { proxy.deleteBackward() }
        // UIKit may notify the extension during a proxy edit.
        let inserted = replacement + " "
        proxy.insertText(inserted)
        pendingUndo = (original: word, replacement: inserted)
    }

    // MARK: - Layout

    private func rebuild() {
        guard let layout else { return }
        stopDelete()
        hidePopup()
        clearAccents()
        swipeGesture.isEnabled = false
        swipeGesture.isEnabled = settings.swipeTyping != false
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

        if showingSettings {
            addSettingsPane(width: width, pad: pad, height: height)
            return
        }
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
            addLettersRow(keys: "ASDFGHJKL", y: y + step, h: rowHeight, width: width, pad: pad, inset: 0.5)
            addLettersRow(keys: "ZXCVBNM", y: y + 2 * step, h: rowHeight, width: width, pad: pad,
                          left: shiftKey(), right: deleteKey())
        case .symbols:
            addLettersRow(keys: "1234567890", y: y, h: rowHeight, width: width, pad: pad)
            let symbol = Locale.current.currencySymbol ?? "$"
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
        let settingsWidth: CGFloat = 42
        let menu = plainButton(systemName: "slider.horizontal.3", size: 17)
        menu.tintColor = accentColor
        menu.frame = CGRect(x: pad, y: 0, width: settingsWidth, height: height)
        menu.accessibilityLabel = "MyWhisper keyboard settings"
        menu.addTarget(self, action: #selector(menuTapped), for: .touchUpInside)
        rowsView.addSubview(menu)

        let available = width - pad * 2 - settingsWidth
        let slot = available / 3
        for index in 0..<3 {
            let x = pad + settingsWidth + CGFloat(index) * slot
            let button = plainButton(systemName: nil, size: 16)
            button.frame = CGRect(x: x, y: 0, width: slot, height: height)
            button.titleLabel?.font = .systemFont(ofSize: 16)
            button.titleLabel?.lineBreakMode = .byTruncatingTail
            button.setTitleColor(labelColor, for: .normal)
            button.isEnabled = false
            button.isAccessibilityElement = false
            button.addTarget(self, action: #selector(predictionTapped(_:)), for: .touchUpInside)
            rowsView.addSubview(button)
            predictionButtons.append(button)
            if index > 0 {
                let divider = UIView(frame: CGRect(x: x, y: height * 0.25, width: 1, height: height * 0.5))
                divider.backgroundColor = labelColor.withAlphaComponent(0.12)
                divider.isUserInteractionEnabled = false
                rowsView.addSubview(divider)
            }
        }
    }

    @objc private func menuTapped() {
        playClick()
        showingSettings = true
        predictionTask?.cancel()
        rebuild()
    }

    // MARK: - In-keyboard settings

    private func addSettingsPane(width: CGFloat, pad: CGFloat, height: CGFloat) {
        var y: CGFloat = 6
        let back = plainButton(systemName: "chevron.left", size: 17)
        back.frame = CGRect(x: pad, y: y, width: 110, height: 40)
        back.setTitle(" Settings", for: [])
        back.titleLabel?.font = .systemFont(ofSize: 17)
        back.setTitleColor(labelColor, for: [])
        back.addTarget(self, action: #selector(backFromSettings), for: .touchUpInside)
        rowsView.addSubview(back)
        let title = UILabel(frame: CGRect(x: pad + 118, y: y, width: width - 2 * pad - 118, height: 40))
        title.text = "Keyboard"
        title.font = .systemFont(ofSize: 17, weight: .semibold)
        title.textColor = labelColor
        rowsView.addSubview(title)
        y += 44
        let options = UIScrollView(frame: CGRect(x: 0, y: y, width: width, height: max(0, height - y)))
        rowsView.addSubview(options)
        let rows: [(String, Int, Bool)] = [
            ("Auto-Capitalization", 1, settings.autoCapitalization),
            ("Auto-Correction", 2, settings.autoCorrection),
            ("Predictive", 3, settings.predictive),
            ("Period Shortcut", 4, settings.periodShortcut),
            ("Enable Caps Lock", 5, settings.enableCapsLock),
            ("Character Preview", 6, settings.characterPreview),
            ("Key Clicks", 7, settings.keyClicks),
            ("Swipe to Type", 8, settings.swipeTyping != false),
        ]
        var rowY: CGFloat = 0
        for (label, tag, value) in rows {
            rowY = addToggleRow(title: label, tag: tag, value: value, y: rowY, width: width, pad: pad, in: options)
        }
        let languageLabel = UILabel(frame: CGRect(x: pad + 16, y: rowY, width: width - 2 * pad - 32, height: 36))
        languageLabel.text = "Swipe language"
        languageLabel.textColor = labelColor
        languageLabel.font = .systemFont(ofSize: 17)
        options.addSubview(languageLabel)

        let languages = UISegmentedControl(items: ["Auto", "English", "Español"])
        languages.frame = CGRect(x: pad + 16, y: rowY + 36, width: width - 2 * pad - 32, height: 34)
        languages.accessibilityLabel = "Swipe language"
        languages.selectedSegmentIndex = settings.swipeLanguage == "es" ? 2 : (settings.swipeLanguage == "en" ? 1 : 0)
        languages.addTarget(self, action: #selector(swipeLanguageChanged(_:)), for: .valueChanged)
        options.addSubview(languages)
        options.contentSize = CGSize(width: width, height: rowY + 82)
    }

    private func addToggleRow(
        title: String, tag: Int, value: Bool, y: CGFloat, width: CGFloat, pad: CGFloat, in options: UIScrollView
    ) -> CGFloat {
        let h: CGFloat = 48
        let label = UILabel(frame: CGRect(x: pad + 16, y: y, width: width - 2 * pad - 100, height: h))
        label.text = title
        label.font = .systemFont(ofSize: 17)
        label.textColor = labelColor
        options.addSubview(label)
        let toggle = UISwitch(frame: CGRect(x: width - pad - 16 - 51, y: y + (h - 31) / 2, width: 51, height: 31))
        toggle.isOn = value
        toggle.onTintColor = accentColor
        toggle.accessibilityLabel = title
        toggle.tag = tag
        toggle.addTarget(self, action: #selector(settingChanged(_:)), for: .valueChanged)
        options.addSubview(toggle)
        return y + h
    }

    @objc private func settingChanged(_ sender: UISwitch) {
        playClick()
        switch sender.tag {
        case 1: settings.autoCapitalization = sender.isOn
        case 2: settings.autoCorrection = sender.isOn
        case 3: settings.predictive = sender.isOn
        case 4: settings.periodShortcut = sender.isOn
        case 5:
            settings.enableCapsLock = sender.isOn
            if !sender.isOn && shift == .locked { shift = .off }
        case 6: settings.characterPreview = sender.isOn
        case 7: settings.keyClicks = sender.isOn
        case 8: settings.swipeTyping = sender.isOn
        default: return
        }
        settings.save()
        if sender.tag == 8 { preloadSwipeWords() }
    }

    @objc private func swipeLanguageChanged(_ sender: UISegmentedControl) {
        let languages: [String?] = [nil, "en", "es"]
        guard languages.indices.contains(sender.selectedSegmentIndex) else { return }
        settings.swipeLanguage = languages[sender.selectedSegmentIndex]
        settings.save()
        preloadSwipeWords()
    }

    @objc private func backFromSettings() {
        playClick()
        showingSettings = false
        updateAutomaticShift()
        rebuild()
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

    private func addBottomRow(y: CGFloat, h: CGFloat, width: CGFloat, pad: CGFloat) {
        let pageTitle = emojiMode || page != .letters ? "ABC" : "123"
        var keys: [(KeyboardKeyButton, CGFloat)] = [(pageKey(pageTitle), 1.15)]
        if layout?.globe == true {
            keys.append((globeKey(), 1))
        }
        if emojiMode {
            keys.append((deleteKey(), 1.25))
        } else {
            let emoji = symbolKey("face.smiling", label: "Emoji")
            emoji.addTarget(self, action: #selector(openEmojiPicker), for: .touchUpInside)
            keys.append((emoji, 1))
        }
        let space = makeKey(background: keyColor)
        space.setTitle("space", for: .normal)
        space.setTitleColor(labelColor.withAlphaComponent(0.8), for: .normal)
        space.titleLabel?.font = .systemFont(ofSize: 14)
        space.accessibilityLabel = "Space"
        space.addTarget(self, action: #selector(spaceTapped), for: .touchUpInside)
        let cursor = UILongPressGestureRecognizer(target: self, action: #selector(dragCursor(_:)))
        cursor.minimumPressDuration = 0.30
        space.addGestureRecognizer(cursor)
        let drag = UIPanGestureRecognizer(target: self, action: #selector(dragSpacebar(_:)))
        drag.maximumNumberOfTouches = 1
        drag.delegate = self
        space.addGestureRecognizer(drag)
        keys.append((space, 4.6))
        if isAddressField {
            keys.append((characterKey(".", cased: false), 1))
            if layout?.keyboardType == .emailAddress { keys.append((characterKey("@", cased: false), 1)) }
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
        if shift == .on {
            shift = .off
            updateShiftKeys()
        }
        textDocumentProxy.insertText(separator + text)
        updateAutomaticShift()
        scheduleSuggestions()
    }

    private func updateAutomaticShift() {
        guard shift != .locked else { return }
        let next: Shift
        if !settings.autoCapitalization || isAddressField || isNumberField {
            next = .off
        } else if let before = textDocumentProxy.documentContextBeforeInput {
            switch textDocumentProxy.autocapitalizationType ?? .sentences {
            case .none: next = .off
            case .allCharacters: next = .on
            case .words: next = before.last?.isWhitespace != false ? .on : .off
            case .sentences:
                let trimmed = before.reversed().drop(while: { $0 == " " || $0 == "\t" }).first
                next = trimmed == nil || trimmed?.isNewline == true ||
                    (before.last?.isWhitespace == true && trimmed.map { ".?!".contains($0) } == true) ? .on : .off
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
        lastShiftTap = nil
        if settings.periodShortcut, !isAddressField, let last = lastSpaceAt, now - last < 0.4,
           proxy.selectedText?.isEmpty != false, let before = proxy.documentContextBeforeInput,
           before.last == " ", let previous = before.dropLast().last,
           previous.isLetter || previous.isNumber {
            pendingUndo = nil
            lastSpaceAt = nil
            proxy.deleteBackward()
            proxy.insertText(". ")
        } else {
            if let suggestion, let correction = suggestion.correction,
               suggestion.word == currentWord, suggestion.document == proxy.documentIdentifier {
                replaceWord(suggestion.word, with: correction)
            } else {
                pendingUndo = nil
                proxy.insertText(" ")
            }
            lastSpaceAt = now
        }
        updateAutomaticShift()
        scheduleSuggestions()
    }

    @objc private func returnTapped() {
        insert("\n")
    }

    @objc private func dragCursor(_ gesture: UILongPressGestureRecognizer) {
        moveCursor(for: gesture, initialOffset: 0)
    }

    @objc private func dragSpacebar(_ gesture: UIPanGestureRecognizer) {
        moveCursor(for: gesture, initialOffset: gesture.translation(in: rowsView).x)
    }

    private func moveCursor(for gesture: UIGestureRecognizer, initialOffset: CGFloat) {
        let x = gesture.location(in: rowsView).x
        switch gesture.state {
        case .began:
            hidePopup()
            cursorDragX = x - initialOffset
            pendingSwipe?.cancel()
            lastSwipe = nil
            lastSpaceAt = nil
            pendingUndo = nil
            adjustCursor(towards: x)
        case .changed, .ended:
            adjustCursor(towards: x)
        default:
            break
        }
    }

    private func adjustCursor(towards x: CGFloat) {
        let steps = Int((x - cursorDragX) / 12)
        guard steps != 0 else { return }
        cursorDragX += CGFloat(steps) * 12
        textDocumentProxy.adjustTextPosition(byCharacterOffset: steps)
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

    private var swipeLocale: String {
        let preferred = Locale.preferredLanguages.map(Locale.init(identifier:))
        if settings.swipeLanguage == "es" { return "es" }
        let english = preferred.first { $0.languageCode == "en" }
        let englishLocale = english?.regionCode == "GB" ? "en_GB" : "en_US"
        if settings.swipeLanguage == "en" { return englishLocale }
        return preferred.first(where: { ["en", "es"].contains($0.languageCode ?? "") })?.languageCode == "es"
            ? "es" : englishLocale
    }

    private func preloadSwipeWords() {
        guard settings.swipeTyping != false else {
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
        default:
            break
        }
    }

    private func completeSwipe(trace: [CGPoint], pauses: [CGPoint]) {
        let centers: [UInt8: CGPoint] = Dictionary(uniqueKeysWithValues: letterKeys.compactMap { key in
            guard let letter = key.letter.lowercased().utf8.first else { return nil }
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
        for _ in swipe.word { proxy.deleteBackward() }
        proxy.insertText(replacement)
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
        showingSettings = false
        emojiMode = true
        rebuild()
    }

    private func addEmojiView(width: CGFloat, pad: CGFloat, height: CGFloat, rowHeight: CGFloat) {
        let bottomY = height - rowHeight - 6
        let picker = KeyboardEmojiPicker(
            frame: CGRect(x: pad, y: 2, width: width - 2 * pad, height: bottomY - 8),
            accent: accentColor
        ) { [weak self] emoji in
            self?.insert(emoji)
        }
        rowsView.addSubview(picker)
        addBottomRow(y: bottomY, h: rowHeight, width: width, pad: pad)
    }
}

extension KeyboardViewController: UIGestureRecognizerDelegate {
    func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer, shouldReceive touch: UITouch) -> Bool {
        guard gestureRecognizer === swipeGesture else { return true }
        guard settings.swipeTyping != false,
              page == .letters, !emojiMode, !showingSettings, !isNumberField, !isAddressField,
              let key = rowsView.nearestKey(at: touch.location(in: rowsView)) else { return false }
        return letterKeys.contains { $0.button === key }
    }

    func gestureRecognizerShouldBegin(_ gestureRecognizer: UIGestureRecognizer) -> Bool {
        guard let pan = gestureRecognizer as? UIPanGestureRecognizer else { return true }
        let movement = pan.translation(in: rowsView)
        guard abs(movement.x) > abs(movement.y) else { return false }
        if pan.view?.accessibilityLabel == "Delete" { return !deleteRepeated && movement.x < 0 }
        return true
    }
}
