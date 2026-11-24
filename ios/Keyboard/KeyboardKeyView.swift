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
