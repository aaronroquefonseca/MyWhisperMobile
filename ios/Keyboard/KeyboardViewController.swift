import AudioToolbox
import UIKit

/// MyWhisper prototype keyboard: Apple system look and behavior, plus a
/// dictation mic. Typing runs on our own engine (iOS gives third-party
/// keyboards no autocorrect API); completions come from the system
/// lexicon via `UITextChecker`. Mic currently shows a "coming soon" toast;
/// wiring it to the speech providers is next (Full Access + shared container).
final class KeyboardViewController: UIInputViewController {

    private enum Page { case letters, symbols, more }
    private enum Shift { case off, on, locked }

    private var page: Page = .letters
    private var shift: Shift = .off
    private var rowsView = UIView()
    private var popup: UILabel?
    private var deleteTimer: Timer?
    private var lastSpaceAt: Date?
    private var toast: UILabel?
    private var isDark = false
    private var predictions: [String] = []
    private let checker = UITextChecker()

    // MARK: - Lifecycle

    override func viewDidLoad() {
        super.viewDidLoad()
        rowsView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(rowsView)
        NSLayoutConstraint.activate([
            rowsView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            rowsView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            rowsView.topAnchor.constraint(equalTo: view.topAnchor),
            rowsView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        let h = view.heightAnchor.constraint(equalToConstant: 302)
        h.priority = .init(999)
        h.isActive = true
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        refreshAppearance()
        refreshPredictions()
        rebuild()
    }

    override func traitCollectionDidChange(_ previous: UITraitCollection?) {
        super.traitCollectionDidChange(previous)
        refreshAppearance()
        rebuild()
    }

    override func textDidChange(_ textInput: UITextInput?) {
        super.textDidChange(textInput)
        refreshPredictions()
        rebuild()
        if shift == .off, let before = textDocumentProxy.documentContextBeforeInput {
            if before.isEmpty || (before.hasSuffix(" ") && [". ", "? ", "! "].contains(String(before.suffix(2)))) {
                shift = .on
                rebuild()
            }
        }
    }

    private func refreshAppearance() {
        if let proxyAppearance = textDocumentProxy.keyboardAppearance {
            isDark = proxyAppearance == .dark
        } else {
            isDark = traitCollection.userInterfaceStyle == .dark
        }
        view.backgroundColor = isDark
            ? UIColor(red: 0x1E / 255, green: 0x1E / 255, blue: 0x20 / 255, alpha: 1)
            : UIColor(red: 0xD5 / 255, green: 0xD8 / 255, blue: 0xDD / 255, alpha: 1)
    }

    private var keyColor: UIColor {
        isDark ? UIColor(red: 0x58 / 255, green: 0x58 / 255, blue: 0x5C / 255, alpha: 1) : .white
    }

    private var specialColor: UIColor {
        isDark ? UIColor(red: 0x34 / 255, green: 0x34 / 255, blue: 0x38 / 255, alpha: 1)
            : UIColor(red: 0xAB / 255, green: 0xB1 / 255, blue: 0xBB / 255, alpha: 1)
    }

    private var labelColor: UIColor { isDark ? .white : .black }

    // MARK: - Predictions (system lexicon completions)

    private func currentWord() -> (word: String, length: Int) {
        guard let before = textDocumentProxy.documentContextBeforeInput, !before.isEmpty else { return ("", 0) }
        let scalars = Array(before)
        var i = scalars.count
        while i > 0, scalars[i - 1].isLetter || scalars[i - 1] == "'" { i -= 1 }
        let word = String(scalars[i...])
        if word.rangeOfCharacter(from: .letters) == nil { return ("", 0) }
        return (word, scalars.count - i)
    }

    private func refreshPredictions() {
        let (word, _) = currentWord()
        guard word.count >= 2 else { predictions = []; return }
        let range = NSRange(location: 0, length: (word as NSString).length)
        predictions = Array(checker.completions(forPartialWordRange: range, in: word, language: "en_US")?.prefix(3) ?? [])
    }

    private func applyPrediction(_ p: String) {
        let (_, len) = currentWord()
        for _ in 0..<len { textDocumentProxy.deleteBackward() }
        textDocumentProxy.insertText(p + " ")
        playClick()
        lastSpaceAt = nil
        refreshPredictions()
        rebuild()
    }

    // MARK: - Layout

    private func rebuild() {
        rowsView.subviews.forEach { $0.removeFromSuperview() }
        hidePopup()
        let w = view.bounds.width > 0 ? view.bounds.width : 390
        let pad: CGFloat = 6
        let gap: CGFloat = 6
        var y: CGFloat = 6
        addStrip(y: y, width: w, pad: pad)
        y += 40 + gap
        let rowH: CGFloat = 44
        switch page {
        case .letters:
            addLettersRow(keys: "QWERTYUIOP", y: y, h: rowH, width: w, pad: pad)
            y += rowH + gap
            addLettersRow(keys: "ASDFGHJKL", y: y, h: rowH, width: w, pad: pad, inset: 0.5)
            y += rowH + gap
            addLettersRow(keys: "ZXCVBNM", y: y, h: rowH, width: w, pad: pad, left: shiftKey(), right: deleteKey())
            y += rowH + gap
            addBottomRow(y: y, h: rowH, width: w, pad: pad, leftTitle: "123")
        case .symbols:
            addLettersRow(keys: "1234567890", y: y, h: rowH, width: w, pad: pad)
            y += rowH + gap
            addPunctRow(keys: ["-", "/", ":", ";", "(", ")", "$", "&", "@"], y: y, h: rowH, width: w, pad: pad)
            y += rowH + gap
            addLettersRow(keys: "_,\"',.?!", y: y, h: rowH, width: w, pad: pad, left: pageKey("#+="), right: deleteKey())
            y += rowH + gap
            addBottomRow(y: y, h: rowH, width: w, pad: pad, leftTitle: "ABC")
        case .more:
            addPunctRow(keys: ["[", "]", "{", "}", "#", "%", "^", "*", "+", "="], y: y, h: rowH, width: w, pad: pad)
            y += rowH + gap
            addPunctRow(keys: ["_", "\\", "|", "~", "<", ">", "€", "£", "¥", "•"], y: y, h: rowH, width: w, pad: pad)
            y += rowH + gap
            addLettersRow(keys: "`\"';:.,", y: y, h: rowH, width: w, pad: pad, left: pageKey("123"), right: deleteKey())
            y += rowH + gap
            addBottomRow(y: y, h: rowH, width: w, pad: pad, leftTitle: "ABC")
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        rebuild()
    }

    private func unitWidth(keys: Int, width: CGFloat, pad: CGFloat, inset: CGFloat = 0, gap: CGFloat = 6) -> CGFloat {
        (width - pad * 2 - gap * (CGFloat(keys) - 1 + inset * 2)) / (CGFloat(keys) + inset * 2)
    }

    /// Prediction strip (QuickType-style) + mic button at the trailing end.
    private func addStrip(y: CGFloat, width: CGFloat, pad: CGFloat) {
        let micW: CGFloat = 52
        let total = width - pad * 2
        let slotW = (total - micW - 12) / 3
        var x = pad
        for i in 0..<3 {
            let title = i < predictions.count ? predictions[i] : ""
            let b = makeKey(bg: .clear, shadow: false)
            b.frame = CGRect(x: x, y: y, width: slotW, height: 40)
            b.setTitle(title, for: [])
            b.titleLabel?.font = .systemFont(ofSize: 16)
            b.setTitleColor(labelColor, for: [])
            if !title.isEmpty {
                let p = title
                b.addTarget(self, action: #selector(predictionTapped(_:)), for: .touchUpInside)
                b.accessibilityIdentifier = p
            }
            if i < 2 {
                let sep = UIView(frame: CGRect(x: x + slotW + 3, y: y + 8, width: 1, height: 24))
                sep.backgroundColor = labelColor.withAlphaComponent(0.2)
                rowsView.addSubview(sep)
            }
            rowsView.addSubview(b)
            x += slotW + 6
        }
        let mic = makeKey(bg: .clear, shadow: false)
        mic.frame = CGRect(x: x, y: y, width: micW, height: 40)
        mic.setImage(UIImage(systemName: "mic.fill")?.withTintColor(micTint(), renderingMode: .alwaysOriginal), for: [])
        mic.addTarget(self, action: #selector(micTapped), for: .touchUpInside)
        rowsView.addSubview(mic)
    }

    private func micTint() -> UIColor {
        UIColor(red: 0x5C / 255, green: 0xD9 / 255, blue: 0xEC / 255, alpha: 1)
    }

    @objc private func predictionTapped(_ b: UIButton) {
        guard let p = b.accessibilityIdentifier, !p.isEmpty else { return }
        applyPrediction(p)
    }

    private func addLettersRow(keys: String, y: CGFloat, h: CGFloat, width: CGFloat, pad: CGFloat, inset: CGFloat = 0, left: UIButton? = nil, right: UIButton? = nil) {
        let chars = keys.map(String.init)
        let n = CGFloat(chars.count)
        let extras = (left == nil ? 0 : 1.5) + (right == nil ? 0 : 1.5)
        let u = unitWidth(keys: chars.count, width: width, pad: pad, inset: inset + extras / 2)
        var x = pad + inset * (u + 6) + (left == nil ? 0 : 1.5 * (u + 6))
        for k in chars {
            let shown = page == .letters ? (shift == .off ? k.lowercased() : k) : k
            addKey(title: shown, display: shown, insert: typedForm(k), x: x, y: y, w: u, h: h)
            x += u + 6
        }
        if let left {
            left.frame = CGRect(x: pad + inset * (u + 6), y: y, width: u * 1.5, height: h)
            rowsView.addSubview(left)
        }
        if let right {
            right.frame = CGRect(x: x, y: y, width: u * 1.5, height: h)
            rowsView.addSubview(right)
        }
    }

    private func addPunctRow(keys: [String], y: CGFloat, h: CGFloat, width: CGFloat, pad: CGFloat) {
        addRowStrings(keys: keys, y: y, h: h, width: width, pad: pad, display: { $0 }, insert: { $0 })
    }

    private func addRowStrings(keys: [String], y: CGFloat, h: CGFloat, width: CGFloat, pad: CGFloat, display: (String) -> String, insert: (String) -> String) {
        let u = unitWidth(keys: keys.count, width: width, pad: pad)
        var x = pad
        for k in keys {
            addKey(title: display(k), display: display(k), insert: insert(k), x: x, y: y, w: u, h: h)
            x += u + 6
        }
    }

    /// What actually gets inserted (shift-aware); display stays uppercase like Apple.
    private func typedForm(_ k: String) -> String {
        guard page == .letters else { return k }
        return shift == .off ? k.lowercased() : k.uppercased()
    }

    private func addBottomRow(y: CGFloat, h: CGFloat, width: CGFloat, pad: CGFloat, leftTitle: String) {
        // Units: page 1.5, globe 1, space 4.5, return 2 (total 9).
        let u = unitWidth(keys: 9, width: width, pad: pad)
        var x = pad
        let page = pageKey(leftTitle)
        page.frame = CGRect(x: x, y: y, width: u * 1.5, height: h)
        rowsView.addSubview(page)
        x += u * 1.5 + 6
        let globe = symbolKey("globe", size: 22)
        globe.frame = CGRect(x: x, y: y, width: u, height: h)
        globe.addTarget(self, action: #selector(nextKeyboard), for: .touchUpInside)
        rowsView.addSubview(globe)
        x += u + 6
        let spaceW = u * 4.5
        let space = makeKey(bg: keyColor)
        space.frame = CGRect(x: x, y: y, width: spaceW, height: h)
        space.addTarget(self, action: #selector(spaceTapped), for: .touchUpInside)
        rowsView.addSubview(space)
        x += spaceW + 6
        let ret = makeKey(bg: specialColor)
        ret.frame = CGRect(x: x, y: y, width: u * 2, height: h)
        ret.setTitle("return", for: [])
        ret.titleLabel?.font = .systemFont(ofSize: 15)
        ret.setTitleColor(labelColor, for: [])
        ret.addTarget(self, action: #selector(returnTapped), for: .touchUpInside)
        rowsView.addSubview(ret)
    }

    // MARK: - Keys

    private func makeKey(bg: UIColor, shadow: Bool = true) -> UIButton {
        let b = UIButton(type: .custom)
        b.backgroundColor = bg
        b.layer.cornerRadius = 6
        if shadow {
            b.layer.shadowColor = UIColor.black.withAlphaComponent(isDark ? 0.4 : 0.18).cgColor
            b.layer.shadowOffset = CGSize(width: 0, height: 1)
            b.layer.shadowOpacity = 1
            b.layer.shadowRadius = 0
            if !isDark {
                b.layer.borderColor = UIColor.black.withAlphaComponent(0.06).cgColor
                b.layer.borderWidth = 0.5
            }
        }
        return b
    }

    private func symbolKey(_ name: String, size: CGFloat) -> UIButton {
        let b = makeKey(bg: specialColor)
        b.setImage(UIImage(systemName: name)?.withTintColor(labelColor, renderingMode: .alwaysOriginal), for: [])
        b.imageView?.contentMode = .scaleAspectFit
        return b
    }

    private func addKey(title: String, display: String, insert: String, x: CGFloat, y: CGFloat, w: CGFloat, h: CGFloat) {
        let b = makeKey(bg: keyColor)
        b.frame = CGRect(x: x, y: y, width: w, height: h)
        b.setTitle(display, for: [])
        b.titleLabel?.font = .systemFont(ofSize: 23, weight: .light)
        b.setTitleColor(labelColor, for: [])
        b.addTarget(self, action: #selector(keyTouchDown(_:)), for: .touchDown)
        b.addTarget(self, action: #selector(keyTouchUp(_:)), for: [.touchUpInside, .touchUpOutside, .touchCancel])
        b.accessibilityIdentifier = insert
        rowsView.addSubview(b)
    }

    private func shiftKey() -> UIButton {
        let name: String
        switch shift {
        case .off: name = "shift"
        case .on: name = "shift.fill"
        case .locked: name = "capslock.fill"
        }
        let b = symbolKey(name, size: 22)
        b.addTarget(self, action: #selector(shiftTapped), for: .touchUpInside)
        let dbl = UITapGestureRecognizer(target: self, action: #selector(shiftDoubleTapped))
        dbl.numberOfTapsRequired = 2
        b.addGestureRecognizer(dbl)
        return b
    }

    private func deleteKey() -> UIButton {
        let b = symbolKey("delete.left", size: 22)
        b.addTarget(self, action: #selector(deleteDown(_:)), for: .touchDown)
        b.addTarget(self, action: #selector(deleteUp), for: [.touchUpInside, .touchUpOutside, .touchCancel])
        return b
    }

    private func pageKey(_ title: String) -> UIButton {
        let b = makeKey(bg: specialColor)
        b.setTitle(title, for: [])
        b.titleLabel?.font = .systemFont(ofSize: 15)
        b.setTitleColor(labelColor, for: [])
        b.addTarget(self, action: #selector(pageTapped(_:)), for: .touchUpInside)
        b.accessibilityIdentifier = title
        return b
    }

    // MARK: - Actions

    private func insert(_ k: String) {
        playClick()
        textDocumentProxy.insertText(k)
        if shift == .on {
            shift = .off
            rebuild()
        }
        autoShiftCheck(inserted: k)
        refreshPredictions()
    }

    private func autoShiftCheck(inserted: String) {
        if inserted == " ", let before = textDocumentProxy.documentContextBeforeInput, before.count >= 2 {
            let two = String(before.suffix(2))
            if [". ", "? ", "! "].contains(two), shift == .off {
                shift = .on
                rebuild()
            }
        }
    }

    @objc private func keyTouchDown(_ b: UIButton) {
        showPopup(for: b)
    }

    @objc private func keyTouchUp(_ b: UIButton) {
        hidePopup()
        guard let k = b.accessibilityIdentifier, !k.isEmpty, b.title(for: []) != nil else { return }
        insert(k)
    }

    @objc private func shiftTapped() {
        playClick()
        shift = shift == .off ? .on : .off
        rebuild()
    }

    @objc private func shiftDoubleTapped() {
        playClick()
        shift = .locked
        rebuild()
    }

    @objc private func pageTapped(_ b: UIButton) {
        playClick()
        switch b.accessibilityIdentifier {
        case "123": page = .symbols
        case "ABC": page = .letters
        case "#+=": page = .more
        default: break
        }
        rebuild()
    }

    @objc private func deleteDown(_ b: UIButton) {
        showPopup(for: b)
        textDocumentProxy.deleteBackward()
        playClick()
        refreshPredictions()
        deleteTimer?.invalidate()
        deleteTimer = Timer.scheduledTimer(withTimeInterval: 0.45, repeats: false) { [weak self] _ in
            self?.deleteTimer = Timer.scheduledTimer(withTimeInterval: 0.08, repeats: true) { [weak self] _ in
                self?.textDocumentProxy.deleteBackward()
                self?.refreshPredictions()
            }
        }
    }

    @objc private func deleteUp() {
        hidePopup()
        deleteTimer?.invalidate()
        deleteTimer = nil
    }

    @objc private func spaceTapped() {
        playClick()
        let now = Date()
        if let last = lastSpaceAt, now.timeIntervalSince(last) < 0.4,
           let before = textDocumentProxy.documentContextBeforeInput, before.hasSuffix(" ") == false {
            textDocumentProxy.deleteBackward()
            textDocumentProxy.insertText(". ")
            lastSpaceAt = nil
            shift = .on
            rebuild()
            return
        }
        textDocumentProxy.insertText(" ")
        lastSpaceAt = now
        autoShiftCheck(inserted: " ")
        refreshPredictions()
    }

    @objc private func returnTapped() {
        playClick()
        textDocumentProxy.insertText("\n")
    }

    @objc private func nextKeyboard() {
        playClick()
        advanceToNextInputMode()
    }

    @objc private func micTapped() {
        playClick()
        showToast("Voice dictation coming soon")
    }

    // MARK: - Popup, toast, click

    private func showPopup(for b: UIButton) {
        hidePopup()
        guard let title = b.title(for: []), title.count <= 3 else { return }
        let label = UILabel()
        label.text = title
        label.font = .systemFont(ofSize: 30, weight: .light)
        label.textAlignment = .center
        label.textColor = labelColor
        label.backgroundColor = keyColor
        label.layer.cornerRadius = 8
        label.clipsToBounds = true
        let w = max(b.frame.width + 16, 52)
        label.frame = CGRect(x: b.frame.midX - w / 2, y: b.frame.minY - 58, width: w, height: 52)
        label.layer.shadowColor = UIColor.black.withAlphaComponent(0.25).cgColor
        label.layer.shadowOffset = CGSize(width: 0, height: 1)
        label.layer.shadowOpacity = 1
        rowsView.addSubview(label)
        popup = label
    }

    private func hidePopup() {
        popup?.removeFromSuperview()
        popup = nil
    }

    private func showToast(_ s: String) {
        toast?.removeFromSuperview()
        let label = UILabel()
        label.text = s
        label.font = .systemFont(ofSize: 15)
        label.textColor = .white
        label.backgroundColor = UIColor.black.withAlphaComponent(0.75)
        label.textAlignment = .center
        label.layer.cornerRadius = 10
        label.clipsToBounds = true
        label.frame = CGRect(x: 40, y: 4, width: view.bounds.width - 80, height: 34)
        view.addSubview(label)
        toast = label
        DispatchQueue.main.asyncAfter(deadline: .now() + 1.2) { [weak self, weak label] in
            if self?.toast == label { label?.removeFromSuperview() }
        }
    }

    private func playClick() {
        AudioServicesPlaySystemSound(1104)
    }
}
