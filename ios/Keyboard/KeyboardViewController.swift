import AudioToolbox
import UIKit

/// MyWhisper prototype keyboard: Wispr Flow visual language (borderless
/// translucent keys, utility strip with mode pill + round mic, brand space
/// bar), Apple behavior (shift states, delete repeat, double-space period,
/// auto-caps, symbol pages, globe switch). Completions come from the system
/// lexicon via `UITextChecker` and appear as a strip row only while typing.
/// Mic + menu are visual for now (toast); wiring needs Full Access.
final class KeyboardViewController: UIInputViewController {

    private enum Page { case letters, symbols, more }
    private enum Shift { case off, on, locked }

    private var page: Page = .letters
    private var shift: Shift = .off
    private var rowsView = UIView()
    private var heightConstraint: NSLayoutConstraint?
    private var popup: UILabel?
    private var deleteTimer: Timer?
    private var lastSpaceAt: Date?
    private var toast: UILabel?
    override var needsInputModeSwitchKey: Bool { false }

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
        heightConstraint = view.heightAnchor.constraint(equalToConstant: 302)
        heightConstraint?.priority = .init(999)
        heightConstraint?.isActive = true
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
            ? UIColor(red: 0x1B / 255, green: 0x1B / 255, blue: 0x1F / 255, alpha: 1)
            : UIColor(red: 0xD8 / 255, green: 0xDB / 255, blue: 0xE0 / 255, alpha: 1)
    }

    /// Borderless translucent key ink (Wispr-style): near-solid white on
    /// light, white-at-16% on dark.
    private var keyColor: UIColor {
        isDark ? UIColor(white: 1, alpha: 0.16) : UIColor(white: 1, alpha: 0.94)
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

    @discardableResult
    private func refreshPredictions() -> Bool {
        let old = predictions
        let (word, _) = currentWord()
        if word.count >= 2 {
            let range = NSRange(location: 0, length: (word as NSString).length)
            predictions = Array(checker.completions(forPartialWordRange: range, in: word, language: "en_US")?.prefix(3) ?? [])
        } else {
            predictions = []
        }
        return predictions != old
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
        var gap: CGFloat = 6
        var y: CGFloat = 6
        addUtilityStrip(y: y, width: w, pad: pad)
        y += 46 + gap
        gap = 11
        if !predictions.isEmpty {
            addPredictionRow(y: y, width: w, pad: pad)
            y += 36 + gap
        }
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
            y += rowH
        case .symbols:
            addPunctRow(keys: ["1", "2", "3", "4", "5", "6", "7", "8", "9", "0"], y: y, h: rowH, width: w, pad: pad)
            y += rowH + gap
            addPunctRow(keys: ["-", "/", ":", ";", "(", ")", "$", "&", "@"], y: y, h: rowH, width: w, pad: pad)
            y += rowH + gap
            addLettersRow(keys: "_,\"',.?!", y: y, h: rowH, width: w, pad: pad, left: pageKey("#+="), right: deleteKey())
            y += rowH + gap
            addBottomRow(y: y, h: rowH, width: w, pad: pad, leftTitle: "ABC")
            y += rowH
        case .more:
            addPunctRow(keys: ["[", "]", "{", "}", "#", "%", "^", "*", "+", "="], y: y, h: rowH, width: w, pad: pad)
            y += rowH + gap
            addPunctRow(keys: ["_", "\\", "|", "~", "<", ">", "€", "£", "¥", "•"], y: y, h: rowH, width: w, pad: pad)
            y += rowH + gap
            addLettersRow(keys: "`\"';:.,", y: y, h: rowH, width: w, pad: pad, left: pageKey("123"), right: deleteKey())
            y += rowH + gap
            addBottomRow(y: y, h: rowH, width: w, pad: pad, leftTitle: "ABC")
            y += rowH
        }
        heightConstraint?.constant = y + 8
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        rebuild()
    }

    private func unitWidth(keys: Int, width: CGFloat, pad: CGFloat, inset: CGFloat = 0, gap: CGFloat = 6) -> CGFloat {
        (width - pad * 2 - gap * (CGFloat(keys) - 1 + inset * 2)) / (CGFloat(keys) + inset * 2)
    }

    /// Utility strip (Wispr-style): menu, mode pill, round mic.
    private func addUtilityStrip(y: CGFloat, width: CGFloat, pad: CGFloat) {
        let menuW: CGFloat = 54
        let micD: CGFloat = 46
        var x = pad
        let menu = plainButton(systemName: "line.3.horizontal", size: 20)
        menu.frame = CGRect(x: x, y: y, width: menuW, height: 46)
        menu.addTarget(self, action: #selector(menuTapped), for: .touchUpInside)
        rowsView.addSubview(menu)
        x += menuW + 6
        let pillW = width - pad * 2 - menuW - micD - 18
        let pill = makeKey(bg: .clear, glass: true)
        pill.frame = CGRect(x: x, y: y, width: pillW, height: 46)
        pill.layer.cornerRadius = 23
        pill.setTitle("Smart.", for: [])
        pill.titleLabel?.font = .systemFont(ofSize: 17)
        pill.setTitleColor(labelColor, for: [])
        pill.addTarget(self, action: #selector(modeTapped), for: .touchUpInside)
        rowsView.addSubview(pill)
        x += pillW + 6
        let mic = UIButton(type: .custom)
        mic.frame = CGRect(x: x, y: y, width: micD, height: 46)
        mic.backgroundColor = keyColor
        mic.layer.cornerRadius = micD / 2
        mic.setImage(UIImage(systemName: "mic.fill")?.withTintColor(cyan(), renderingMode: .alwaysOriginal), for: [])
        mic.addTarget(self, action: #selector(micTapped), for: .touchUpInside)
        rowsView.addSubview(mic)
    }

    private func addPredictionRow(y: CGFloat, width: CGFloat, pad: CGFloat) {
        let total = width - pad * 2
        let slotW = (total - 12) / 3
        var x = pad
        for i in 0..<3 {
            let title = i < predictions.count ? predictions[i] : ""
            let b = plainButton(systemName: nil, size: 16)
            b.frame = CGRect(x: x, y: y, width: slotW, height: 36)
            b.setTitle(title, for: [])
            b.titleLabel?.font = .systemFont(ofSize: 16)
            b.setTitleColor(labelColor, for: [])
            if !title.isEmpty {
                b.accessibilityIdentifier = title
                b.addTarget(self, action: #selector(predictionTapped(_:)), for: .touchUpInside)
            }
            if i < 2 {
                let sep = UIView(frame: CGRect(x: x + slotW + 3, y: y + 6, width: 1, height: 24))
                sep.backgroundColor = labelColor.withAlphaComponent(0.2)
                rowsView.addSubview(sep)
            }
            rowsView.addSubview(b)
            x += slotW + 6
        }
    }

    @objc private func predictionTapped(_ b: UIButton) {
        hidePopup()
        guard let p = b.accessibilityIdentifier, !p.isEmpty else { return }
        applyPrediction(p)
    }

    @objc private func menuTapped() {
        hidePopup()
        playClick()
        showToast("Providers and settings live in the app")
    }

    @objc private func modeTapped() {
        hidePopup()
        playClick()
        showToast("Voice modes arrive with the mic update")
    }

    private func cyan() -> UIColor {
        UIColor(red: 0x5C / 255, green: 0xD9 / 255, blue: 0xEC / 255, alpha: 1)
    }

    private func addLettersRow(keys: String, y: CGFloat, h: CGFloat, width: CGFloat, pad: CGFloat, inset: CGFloat = 0, left: UIButton? = nil, right: UIButton? = nil) {
        let chars = keys.map(String.init)
        let extras = (left == nil ? 0 : 1.5) + (right == nil ? 0 : 1.5)
        let u = unitWidth(keys: chars.count, width: width, pad: pad, inset: inset + extras / 2)
        var x = pad + inset * (u + 6) + (left == nil ? 0 : 1.5 * (u + 6))
        for k in chars {
            let shown = page == .letters ? (shift == .off ? k.lowercased() : k) : k
            addKey(display: shown, insert: typedForm(k), x: x, y: y, w: u, h: h)
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
        let u = unitWidth(keys: keys.count, width: width, pad: pad)
        var x = pad
        for k in keys {
            addKey(display: k, insert: k, x: x, y: y, w: u, h: h)
            x += u + 6
        }
    }

    /// What actually gets inserted (shift-aware); display mirrors shift.
    private func typedForm(_ k: String) -> String {
        guard page == .letters else { return k }
        return shift == .off ? k.lowercased() : k.uppercased()
    }

    private func addBottomRow(y: CGFloat, h: CGFloat, width: CGFloat, pad: CGFloat, leftTitle: String) {
        // Units: page 1.5, globe 1, space 4, period 1, return 1.5 (total 9).
        let u = unitWidth(keys: 9, width: width, pad: pad)
        var x = pad
        let page = pageKey(leftTitle)
        page.frame = CGRect(x: x, y: y, width: u * 1.5, height: h)
        rowsView.addSubview(page)
        x += u * 1.5 + 6
        let globe = symbolKey("globe", size: 21)
        globe.frame = CGRect(x: x, y: y, width: u, height: h)
        globe.addTarget(self, action: #selector(nextKeyboard), for: .touchUpInside)
        rowsView.addSubview(globe)
        x += u + 6
        let spaceW = u * 4
        let space = makeKey(bg: keyColor)
        space.frame = CGRect(x: x, y: y, width: spaceW, height: h)
        space.setTitle("MyWhisper", for: [])
        space.titleLabel?.font = .systemFont(ofSize: 15)
        space.setTitleColor(labelColor.withAlphaComponent(0.85), for: [])
        space.addTarget(self, action: #selector(spaceTapped), for: .touchUpInside)
        space.addTarget(self, action: #selector(keyTouchDown(_:)), for: .touchDown)
        space.addTarget(self, action: #selector(keyTouchCancel(_:)), for: [.touchUpOutside, .touchCancel])
        space.accessibilityIdentifier = " "
        rowsView.addSubview(space)
        x += spaceW + 6
        let period = makeKey(bg: keyColor)
        period.frame = CGRect(x: x, y: y, width: u, height: h)
        period.setTitle(".", for: [])
        period.titleLabel?.font = .systemFont(ofSize: 22)
        period.setTitleColor(labelColor, for: [])
        period.addTarget(self, action: #selector(keyTouchUp(_:)), for: .touchUpInside)
        period.addTarget(self, action: #selector(keyTouchDown(_:)), for: .touchDown)
        period.addTarget(self, action: #selector(keyTouchCancel(_:)), for: [.touchUpOutside, .touchCancel])
        period.accessibilityIdentifier = "."
        rowsView.addSubview(period)
        x += u + 6
        let ret = makeKey(bg: specialColor)
        ret.frame = CGRect(x: x, y: y, width: u * 1.5, height: h)
        ret.setTitle("return", for: [])
        ret.titleLabel?.font = .systemFont(ofSize: 15)
        ret.setTitleColor(labelColor, for: [])
        ret.addTarget(self, action: #selector(returnTapped), for: .touchUpInside)
        ret.addTarget(self, action: #selector(keyTouchDown(_:)), for: .touchDown)
        ret.addTarget(self, action: #selector(keyTouchCancel(_:)), for: [.touchUpOutside, .touchCancel])
        rowsView.addSubview(ret)
    }

    // MARK: - Keys

    /// Borderless translucent key. `glass` adds the frosted pill treatment.
    private func makeKey(bg: UIColor, glass: Bool = false) -> UIButton {
        let b = UIButton(type: .custom)
        b.backgroundColor = bg
        b.layer.cornerRadius = 5.5
        if glass {
            b.layer.borderColor = UIColor.white.withAlphaComponent(0.22).cgColor
            b.layer.borderWidth = 1
        }
        return b
    }

    private func plainButton(systemName: String?, size: CGFloat) -> UIButton {
        let b = UIButton(type: .custom)
        if let systemName {
            b.setImage(UIImage(systemName: systemName)?.withTintColor(labelColor, renderingMode: .alwaysOriginal), for: [])
        }
        return b
    }

    private func symbolKey(_ name: String, size: CGFloat) -> UIButton {
        let b = makeKey(bg: keyColor)
        b.setImage(UIImage(systemName: name)?.withTintColor(labelColor, renderingMode: .alwaysOriginal), for: [])
        b.imageView?.contentMode = .scaleAspectFit
        return b
    }

    private func addKey(display: String, insert: String, x: CGFloat, y: CGFloat, w: CGFloat, h: CGFloat) {
        let b = makeKey(bg: keyColor)
        b.frame = CGRect(x: x, y: y, width: w, height: h)
        b.setTitle(display, for: [])
        b.titleLabel?.font = .systemFont(ofSize: 22)
        b.setTitleColor(labelColor, for: [])
        b.addTarget(self, action: #selector(keyTouchDown(_:)), for: .touchDown)
        b.addTarget(self, action: #selector(keyTouchUp(_:)), for: .touchUpInside)
        b.addTarget(self, action: #selector(keyTouchCancel(_:)), for: [.touchUpOutside, .touchCancel])
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
        let b = symbolKey(name, size: 21)
        b.addTarget(self, action: #selector(shiftTapped), for: .touchUpInside)
        let dbl = UITapGestureRecognizer(target: self, action: #selector(shiftDoubleTapped))
        dbl.numberOfTapsRequired = 2
        b.addGestureRecognizer(dbl)
        return b
    }

    private func deleteKey() -> UIButton {
        let b = symbolKey("delete.left", size: 21)
        b.addTarget(self, action: #selector(deleteDown(_:)), for: .touchDown)
        b.addTarget(self, action: #selector(deleteUp), for: .touchUpInside)
        b.addTarget(self, action: #selector(deleteCancel), for: [.touchUpOutside, .touchCancel])
        return b
    }

    private func pageKey(_ title: String) -> UIButton {
        let b = makeKey(bg: keyColor)
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
        let wasShifted = shift == .on
        if wasShifted {
            shift = .off
        }
        let autoShifted = autoShiftCheck(inserted: k)
        if wasShifted || autoShifted || refreshPredictions() {
            rebuild()
        }
    }

    /// Sentence-start auto-caps. Returns true when it flipped shift state
    /// (callers redraw); never rebuilds itself.
    @discardableResult
    private func autoShiftCheck(inserted: String) -> Bool {
        if inserted == " ", let before = textDocumentProxy.documentContextBeforeInput, before.count >= 2 {
            let two = String(before.suffix(2))
            if [". ", "? ", "! "].contains(two), shift == .off {
                shift = .on
                return true
            }
        }
        return false
    }

    @objc private func keyTouchDown(_ b: UIButton) {
        showPopup(for: b)
    }

    @objc private func keyTouchUp(_ b: UIButton) {
        hidePopup()
        guard let k = b.accessibilityIdentifier, !k.isEmpty, b.title(for: []) != nil else { return }
        insert(k)
    }

    @objc private func keyTouchCancel(_ b: UIButton) {
        hidePopup()
    }

    @objc private func shiftTapped() {
        hidePopup()
        playClick()
        shift = shift == .off ? .on : .off
        rebuild()
    }

    @objc private func shiftDoubleTapped() {
        hidePopup()
        playClick()
        shift = .locked
        rebuild()
    }

    @objc private func pageTapped(_ b: UIButton) {
        hidePopup()
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
        if refreshPredictions() {
            rebuild()
        }
        deleteTimer?.invalidate()
        deleteTimer = Timer.scheduledTimer(withTimeInterval: 0.45, repeats: false) { [weak self] _ in
            self?.deleteTimer = Timer.scheduledTimer(withTimeInterval: 0.08, repeats: true) { [weak self] _ in
                self?.textDocumentProxy.deleteBackward()
                if self?.refreshPredictions() == true {
                    self?.rebuild()
                }
            }
        }
    }

    @objc private func deleteUp() {
        hidePopup()
        deleteTimer?.invalidate()
        deleteTimer = nil
    }

    @objc private func deleteCancel() {
        hidePopup()
        deleteTimer?.invalidate()
        deleteTimer = nil
    }

    @objc private func spaceTapped() {
        hidePopup()
        playClick()
        let now = Date()
        if let last = lastSpaceAt, now.timeIntervalSince(last) < 0.4,
           let before = textDocumentProxy.documentContextBeforeInput,
           before.hasSuffix(" "), !before.hasSuffix("  ") {
            textDocumentProxy.deleteBackward()
            textDocumentProxy.insertText(". ")
            lastSpaceAt = nil
            shift = .on
            rebuild()
            return
        }
        textDocumentProxy.insertText(" ")
        lastSpaceAt = now
        if autoShiftCheck(inserted: " ") || refreshPredictions() {
            rebuild()
        }
    }

    @objc private func returnTapped() {
        hidePopup()
        playClick()
        textDocumentProxy.insertText("\n")
    }

    @objc private func nextKeyboard() {
        hidePopup()
        playClick()
        advanceToNextInputMode()
    }

    @objc private func micTapped() {
        hidePopup()
        playClick()
        showToast("Voice dictation coming soon")
    }

    // MARK: - Popup, toast, click

    private func showPopup(for b: UIButton) {
        hidePopup()
        let highlight = UIView(frame: b.bounds)
        highlight.backgroundColor = isDark
            ? UIColor(white: 1, alpha: 0.22)
            : UIColor(white: 0, alpha: 0.12)
        highlight.layer.cornerRadius = 5.5
        highlight.isUserInteractionEnabled = false
        highlight.tag = 991
        b.addSubview(highlight)
        guard let title = b.title(for: []), title.count <= 3 else { return }
        let label = UILabel()
        label.text = title
        label.font = .systemFont(ofSize: 34)
        label.textAlignment = .center
        label.textColor = labelColor
        label.backgroundColor = isDark ? UIColor(white: 0.42, alpha: 1) : .white
        label.layer.cornerRadius = 10
        label.clipsToBounds = true
        label.layer.shadowColor = UIColor.black.withAlphaComponent(0.3).cgColor
        label.layer.shadowOffset = CGSize(width: 0, height: 1)
        label.layer.shadowOpacity = 1
        label.layer.shadowRadius = 0
        let w = max(b.frame.width + 18, 56)
        label.frame = CGRect(x: b.frame.midX - w / 2, y: b.frame.minY - 70, width: w, height: 64)
        rowsView.addSubview(label)
        popup = label
    }

    private func hidePopup() {
        popup?.removeFromSuperview()
        popup = nil
        rowsView.subviews.forEach { $0.viewWithTag(991)?.removeFromSuperview() }
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
