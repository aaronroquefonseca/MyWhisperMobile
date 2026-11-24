import UIKit

final class KeyboardInputView: UIInputView, UIInputViewAudioFeedback {
    var enableInputClicksWhenVisible: Bool { true }
}

/// Route touches in the gaps to the closest visible key edge. The same
/// region is used on release, so a tap in a gap counts as touchUpInside.
final class KeyboardKeyArea: UIView {
    private var keys: [KeyboardKeyButton] = []
    private var keyFrames: [CGRect] = []
    private var typingBounds = CGRect.null

    func addKey(_ button: KeyboardKeyButton) {
        keys.append(button)
        keyFrames.append(button.frame)
        typingBounds = typingBounds.union(button.frame)
        addSubview(button)
    }

    func clear() {
        keys.removeAll(keepingCapacity: true)
        keyFrames.removeAll(keepingCapacity: true)
        typingBounds = .null
        subviews.forEach { $0.removeFromSuperview() }
    }

    func nearestKey(at point: CGPoint) -> KeyboardKeyButton? {
        guard !typingBounds.isNull,
              CGRect(x: bounds.minX, y: typingBounds.minY - 4, width: bounds.width,
                     height: typingBounds.height + 12).contains(point) else { return nil }
        return KeyboardKeyGeometry.nearestIndex(at: point, in: keyFrames).map { keys[$0] }
    }

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        guard isUserInteractionEnabled, !isHidden, alpha > 0.01, bounds.contains(point) else { return nil }
        return nearestKey(at: point) ?? super.hitTest(point, with: event)
    }
}

final class KeyboardKeyButton: UIButton {
    var activateWithVoiceOver: (() -> Void)?

    override func accessibilityActivate() -> Bool {
        guard let activateWithVoiceOver else { return super.accessibilityActivate() }
        activateWithVoiceOver()
        return true
    }

    override func point(inside point: CGPoint, with event: UIEvent?) -> Bool {
        if super.point(inside: point, with: event) { return true }
        guard let area = superview as? KeyboardKeyArea else { return false }
        return area.nearestKey(at: convert(point, to: area)) === self
    }
}

/// Keeps unchanged letters in place while the replacement suffix rolls into the slot.
final class KeyboardSuggestionButton: UIButton {
    private let stem = UILabel()
    private let suffix = UILabel()
    private let outgoing = UILabel()
    private let ink: UIColor

    init(ink: UIColor) {
        self.ink = ink
        super.init(frame: .zero)
        clipsToBounds = true
        setTitleColor(.clear, for: .normal)
        setTitleColor(.clear, for: .disabled)
        for label in [stem, outgoing, suffix] {
            label.font = .systemFont(ofSize: 16, weight: .medium)
            label.textAlignment = .center
            label.lineBreakMode = .byTruncatingTail
            label.isUserInteractionEnabled = false
            label.isAccessibilityElement = false
            addSubview(label)
        }
    }

    required init?(coder: NSCoder) { nil }

    override func layoutSubviews() {
        super.layoutSubviews()
        for label in [stem, outgoing, suffix] {
            label.bounds = bounds.insetBy(dx: 5, dy: 0)
            label.center = CGPoint(x: bounds.midX, y: bounds.midY)
        }
    }

    func display(_ word: String?) {
        guard word != title(for: .normal) else { return }
        let oldWord = title(for: .normal) ?? ""
        setTitle(word, for: .normal)
        isEnabled = word != nil
        isAccessibilityElement = word != nil
        accessibilityLabel = word

        for label in [outgoing, suffix] {
            label.layer.removeAllAnimations()
            label.transform = .identity
            label.alpha = 1
        }
        let newWord = word ?? ""
        guard !UIAccessibility.isReduceMotionEnabled, window != nil else {
            stem.attributedText = attributed(newWord, prefixLength: newWord.utf16.count, renderPrefix: true)
            suffix.attributedText = nil
            outgoing.attributedText = nil
            return
        }

        let shared = String(zip(oldWord, newWord).prefix { $0 == $1 }.map { $0.0 }).utf16.count
        stem.attributedText = attributed(newWord, prefixLength: shared, renderPrefix: true)
        outgoing.attributedText = attributed(oldWord, prefixLength: shared, renderPrefix: false)
        suffix.attributedText = attributed(newWord, prefixLength: shared, renderPrefix: false)
        suffix.transform = CGAffineTransform(translationX: 0, y: 7)
        suffix.alpha = 0
        UIView.animate(withDuration: 0.16, delay: 0, options: [.curveEaseOut, .allowUserInteraction]) {
            self.outgoing.transform = CGAffineTransform(translationX: 0, y: -7)
            self.outgoing.alpha = 0
            self.suffix.transform = .identity
            self.suffix.alpha = 1
        }
    }

    private func attributed(_ text: String, prefixLength: Int, renderPrefix: Bool) -> NSAttributedString {
        let full = NSMutableAttributedString(string: text, attributes: [.foregroundColor: UIColor.clear])
        let visible = renderPrefix
            ? NSRange(location: 0, length: prefixLength)
            : NSRange(location: prefixLength, length: full.length - prefixLength)
        full.addAttribute(.foregroundColor, value: ink, range: visible)
        return full
    }
}
