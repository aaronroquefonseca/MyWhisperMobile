import AudioToolbox
import UIKit

/// MyWhisper prototype keyboard: Apple system look and behavior, plus a
/// dictation mic key. Typing runs on our own engine (iOS gives third-party
/// keyboards no autocorrect API); predictions bar is out of scope for the
/// prototype. Mic currently shows a "coming soon" toast; wiring it to the
/// speech providers is the next step (needs Full Access + shared container).
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
    private var isDark = false

    // MARK: - Lifecycle

    override func viewDidLoad() {
        super.viewDidLoad()
        rowsView = UIView()
        rowsView.translatesAutoresizingMaskIntoConstraints = false
        view.addSubview(rowsView)
        NSLayoutConstraint.activate([
            rowsView.leadingAnchor.constraint(equalTo: view.leadingAnchor),
            rowsView.trailingAnchor.constraint(equalTo: view.trailingAnchor),
            rowsView.topAnchor.constraint(equalTo: view.topAnchor),
            rowsView.bottomAnchor.constraint(equalTo: view.bottomAnchor),
        ])
        heightConstraint = view.heightAnchor.constraint(equalToConstant: 280)
        heightConstraint?.priority = .init(999)
        heightConstraint?.isActive = true
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        refreshAppearance()
        rebuild()
    }

    override func traitCollectionDidChange(_ previous: UITraitCollection?) {
        super.traitCollectionDidChange(previous)
        refreshAppearance()
        rebuild()
    }

    override func textDidChange(_ textInput: UITextInput?) {
        super.textDidChange(textInput)
        // Keep shift honest with the document (sentence start => shift on).
        if shift == .off, let before = textDocumentProxy.documentContextBeforeInput {
            if before.isEmpty || ".?!\"\n".contains(before.suffix(2).first ?? Character(" ")) && before.hasSuffix(" ") {
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
        view.backgroundColor = isDark ? UIColor(red: 0.13, green: 0.13, blue: 0.14, alpha: 1) : UIColor(red: 0.82, green: 0.83, blue: 0.85, alpha: 1)
    }

    // MARK: - Layout

    private var keyColor: UIColor {
        isDark ? UIColor(red: 0.42, green: 0.42, blue: 0.44, alpha: 1) : .white
    }

    private var specialColor: UIColor {
        isDark ? UIColor(red: 0.24, green: 0.24, blue: 0.26, alpha: 1) : UIColor(red: 0.67, green: 0.70, blue: 0.74, alpha: 1)
    }

    private var labelColor: UIColor { isDark ? .white : .black }

    private func rebuild() {
        rowsView.subviews.forEach { $0.removeFromSuperview() }
        hidePopup()
        let w = view.bounds.width > 0 ? view.bounds.width : 390
        let pad: CGFloat = 6
        let rowH: CGFloat = 44
        let gap: CGFloat = 6
        var y: CGFloat = 8
        switch page {
        case .letters:
            addRow(keys: "qwertyuiop".map(String.init), y: y, h: rowH, weights: nil, width: w, pad: pad)
            y += rowH + gap
            addRow(keys: "asdfghjkl".map(String.init), y: y, h: rowH, weights: nil, width: w, pad: pad, inset: 0.5)
            y += rowH + gap
            addShiftDeleteRow(y: y, h: rowH, width: w, pad: pad, left: shiftKey(), letters: "zxcvbnm".map(String.init))
            y += rowH + gap
            addBottomRow(y: y, h: rowH, width: w, pad: pad, leftTitle: "123")
        case .symbols:
            addRow(keys: "1234567890".map(String.init), y: y, h: rowH, weights: nil, width: w, pad: pad)
            y += rowH + gap
            addRow(keys: ["-", "/", ":", ";", "(", ")", "$", "&", "@"], y: y, h: rowH, weights: nil, width: w, pad: pad)
            y += rowH + gap
            addShiftDeleteRow(y: y, h: rowH, width: w, pad: pad, left: pageKey("#+="), letters: ["_", "\"", "'", ",", ".", "?", "!"])
            y += rowH + gap
            addBottomRow(y: y, h: rowH, width: w, pad: pad, leftTitle: "ABC")
        case .more:
            addRow(keys: ["[", "]", "{", "}", "#", "%", "^", "*", "+", "="], y: y, h: rowH, weights: nil, width: w, pad: pad)
            y += rowH + gap
            addRow(keys: ["_", "\\", "|", "~", "<", ">", "€", "£", "¥", "•"], y: y, h: rowH, weights: nil, width: w, pad: pad)
            y += rowH + gap
            addShiftDeleteRow(y: y, h: rowH, width: w, pad: pad, left: pageKey("123"), letters: ["`", "'", "\"", ";", ":", ".", ","])
            y += rowH + gap
            addBottomRow(y: y, h: rowH, width: w, pad: pad, leftTitle: "ABC")
        }
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        rebuild()
    }

    private func unitWidth(totalUnits: CGFloat, width: CGFloat, pad: CGFloat) -> CGFloat {
        (width - pad * 2 - 6 * (totalUnits - 1)) / totalUnits
    }

    private func addRow(keys: [String], y: CGFloat, h: CGFloat, weights: [CGFloat]?, width: CGFloat, pad: CGFloat, inset: CGFloat = 0) {
        let total = CGFloat(keys.count) + inset * 2
        let u = unitWidth(totalUnits: total, width: width, pad: pad)
        var x = pad + inset * (u + 6)
        for k in keys {
            addKey(title: displayTitle(k), x: x, y: y, w: u, h: h, action: { [weak self] in self?.insert(k) })
            x += u + 6
        }
    }

    private func addShiftDeleteRow(y: CGFloat, h: CGFloat, width: CGFloat, pad: CGFloat, left: UIButton, letters: [String]) {
        let totalUnits: CGFloat = 1.5 + CGFloat(letters.count) + 1.5
        let u = unitWidth(totalUnits: totalUnits, width: width, pad: pad)
        var x = pad
        left.frame = CGRect(x: x, y: y, width: u * 1.5, height: h)
        rowsView.addSubview(left)
        x += u * 1.5 + 6
        for k in letters {
            addKey(title: displayTitle(k), x: x, y: y, w: u, h: h, action: { [weak self] in self?.insert(k) })
            x += u + 6
        }
        let del = makeKey(bg: specialColor)
        del.frame = CGRect(x: x, y: y, width: u * 1.5, height: h)
        del.setTitle("⌫", for: [])
        del.titleLabel?.font = .systemFont(ofSize: 22)
        del.setTitleColor(labelColor, for: [])
        del.addTarget(self, action: #selector(deleteDown), for: .touchDown)
        del.addTarget(self, action: #selector(deleteUp), for: [.touchUpInside, .touchUpOutside, .touchCancel])
        rowsView.addSubview(del)
    }

    private func addBottomRow(y: CGFloat, h: CGFloat, width: CGFloat, pad: CGFloat, leftTitle: String) {
        // Units: page 1.5, globe 1, mic 1, space 4, return 1.5 (total 9).
        let u = unitWidth(totalUnits: 9, width: width, pad: pad)
        var x = pad
        let page = pageKey(leftTitle)
        page.frame = CGRect(x: x, y: y, width: u * 1.5, height: h)
        rowsView.addSubview(page)
        x += u * 1.5 + 6
        let globe = makeKey(bg: specialColor)
        globe.frame = CGRect(x: x, y: y, width: u, height: h)
        globe.setTitle("🌐", for: [])
        globe.titleLabel?.font = .systemFont(ofSize: 22)
        globe.addTarget(self, action: #selector(nextKeyboard), for: .touchUpInside)
        rowsView.addSubview(globe)
        x += u + 6
        let mic = makeKey(bg: UIColor(red: 0.36, green: 0.85, blue: 0.93, alpha: 1))
        mic.frame = CGRect(x: x, y: y, width: u, height: h)
        mic.setTitle("🎙", for: [])
        mic.titleLabel?.font = .systemFont(ofSize: 22)
        mic.addTarget(self, action: #selector(micTapped), for: .touchUpInside)
        rowsView.addSubview(mic)
        x += u + 6
        let spaceW = u * 4
        let space = makeKey(bg: keyColor)
        space.frame = CGRect(x: x, y: y, width: spaceW, height: h)
        space.setTitle("space", for: [])
        space.titleLabel?.font = .systemFont(ofSize: 16)
        space.setTitleColor(labelColor, for: [])
        space.addTarget(self, action: #selector(spaceTapped), for: .touchUpInside)
        rowsView.addSubview(space)
        x += spaceW + 6
        let ret = makeKey(bg: specialColor)
        ret.frame = CGRect(x: x, y: y, width: u * 1.5, height: h)
        ret.setTitle("return", for: [])
        ret.titleLabel?.font = .systemFont(ofSize: 15)
        ret.setTitleColor(labelColor, for: [])
        ret.addTarget(self, action: #selector(returnTapped), for: .touchUpInside)
        rowsView.addSubview(ret)
    }

    // MARK: - Keys

    private func makeKey(bg: UIColor) -> UIButton {
        let b = UIButton(type: .custom)
        b.backgroundColor = bg
        b.layer.cornerRadius = 6
        b.layer.shadowColor = UIColor.black.withAlphaComponent(0.25).cgColor
        b.layer.shadowOffset = CGSize(width: 0, height: 1)
        b.layer.shadowOpacity = 1
        b.layer.shadowRadius = 0
        return b
    }

    private func addKey(title: String, x: CGFloat, y: CGFloat, w: CGFloat, h: CGFloat, action: @escaping () -> Void) {
        let b = makeKey(bg: keyColor)
        b.frame = CGRect(x: x, y: y, width: w, height: h)
        b.setTitle(title, for: [])
        b.titleLabel?.font = .systemFont(ofSize: 23)
        b.setTitleColor(labelColor, for: [])
        b.addTarget(self, action: #selector(keyTouchDown(_:)), for: .touchDown)
        b.addTarget(self, action: #selector(keyTouchUp(_:)), for: [.touchUpInside, .touchUpOutside, .touchCancel])
        b.accessibilityIdentifier = title
        objc_setAssociatedObject(b, "kbAction", action, .OBJC_ASSOCIATION_COPY_NONATOMIC)
        rowsView.addSubview(b)
    }

    private func displayTitle(_ k: String) -> String {
        shift == .off ? k : k.uppercased()
    }

    private func shiftKey() -> UIButton {
        let b = makeKey(bg: shift == .off ? specialColor : keyColor)
        b.setTitle(shift == .locked ? "⇪" : "⇧", for: [])
        b.titleLabel?.font = .systemFont(ofSize: 22)
        b.setTitleColor(labelColor, for: [])
        b.addTarget(self, action: #selector(shiftTapped), for: .touchUpInside)
        let dbl = UITapGestureRecognizer(target: self, action: #selector(shiftDoubleTapped))
        dbl.numberOfTapsRequired = 2
        b.addGestureRecognizer(dbl)
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
        textDocumentProxy.insertText(displayTitle(k))
        if shift == .on {
            shift = .off
            rebuild()
        }
        autoShiftCheck(inserted: k)
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
        if let action = objc_getAssociatedObject(b, "kbAction") as? () -> Void {
            action()
        }
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
        deleteTimer?.invalidate()
        deleteTimer = Timer.scheduledTimer(withTimeInterval: 0.45, repeats: false) { [weak self] _ in
            self?.deleteTimer = Timer.scheduledTimer(withTimeInterval: 0.08, repeats: true) { [weak self] _ in
                self?.textDocumentProxy.deleteBackward()
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
        label.font = .systemFont(ofSize: 30)
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
