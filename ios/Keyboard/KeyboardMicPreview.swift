import UIKit

/// The Android floating mic's Smart mode, adapted to the keyboard toolbar.
struct KeyboardMicPreviewState {
    enum Phase: Equatable { case idle, held, locked, processing }
    enum DropTarget: Equatable { case none, lock, cancel }

    private(set) var phase = Phase.idle
    private(set) var target = DropTarget.none

    mutating func hold() {
        guard phase == .idle else { return }
        phase = .held
    }

    mutating func move(vertical distance: CGFloat) {
        guard phase == .held else { return }
        if distance < -48 { target = .lock }
        else if distance > 48 { target = .cancel }
        else { target = .none }
    }

    mutating func release(after duration: TimeInterval) {
        switch phase {
        case .idle: phase = .locked
        case .held:
            switch target {
            case .lock: phase = .locked
            case .cancel: phase = .idle
            case .none: phase = duration < 0.23 ? .locked : .processing
            }
        case .locked: phase = .processing
        case .processing: phase = .idle
        }
        target = .none
    }

    mutating func reset() { self = Self() }
}

final class KeyboardMicPreview: UIView {
    private let button = KeyboardMicTouchSurface()
    private let face = UIView()
    private let mark = UIImageView()
    private let feedback = UIImpactFeedbackGenerator(style: .light)
    private let pill = UIView()
    private let cancelButton = UIButton(type: .system)
    private let waveform = UIStackView()
    private var state = KeyboardMicPreviewState()
    private var touchStart: (point: CGPoint, time: TimeInterval)?
    private var holdTask: Task<Void, Never>?
    private var finishTask: Task<Void, Never>?
    private var dockedLeading: Bool
    private var dragging = false
    private var consumesRelease = false
    private let onDock: (Bool) -> Void

    init(frame: CGRect, dockedLeading: Bool, onDock: @escaping (Bool) -> Void) {
        self.dockedLeading = dockedLeading
        self.onDock = onDock
        super.init(frame: frame)

        pill.backgroundColor = UIColor(red: 29 / 255, green: 27 / 255, blue: 32 / 255, alpha: 0.8)
        pill.layer.cornerRadius = 16
        addSubview(pill)

        waveform.axis = .horizontal
        waveform.spacing = 3
        waveform.alignment = .center
        waveform.isUserInteractionEnabled = false
        waveform.isAccessibilityElement = false
        for height in [6, 11, 17, 9, 24, 14, 21, 8, 16, 10, 6] {
            let bar = UIView()
            bar.backgroundColor = .white
            bar.layer.cornerRadius = 1.5
            bar.widthAnchor.constraint(equalToConstant: 3).isActive = true
            bar.heightAnchor.constraint(equalToConstant: CGFloat(height)).isActive = true
            waveform.addArrangedSubview(bar)
        }
        pill.addSubview(waveform)

        cancelButton.setImage(UIImage(systemName: "xmark", withConfiguration: UIImage.SymbolConfiguration(pointSize: 15, weight: .semibold)), for: .normal)
        cancelButton.tintColor = .white
        cancelButton.accessibilityLabel = "Cancel dictation preview"
        cancelButton.addAction(UIAction { [weak self] _ in self?.reset() }, for: .touchUpInside)
        pill.addSubview(cancelButton)

        face.layer.borderWidth = 1
        face.layer.borderColor = UIColor.black.cgColor
        face.isUserInteractionEnabled = false
        button.addSubview(face)
        mark.contentMode = .scaleAspectFit
        mark.tintColor = .black
        mark.isUserInteractionEnabled = false
        let bundle = Bundle(for: KeyboardMicPreview.self)
        if let url = bundle.url(forResource: "floating-mic-mark", withExtension: "png"),
           let image = UIImage(contentsOfFile: url.path) {
            mark.image = image.withRenderingMode(.alwaysTemplate)
        } else {
            mark.image = UIImage(systemName: "mic.fill")
        }
        face.addSubview(mark)
        addSubview(button)

        button.isAccessibilityElement = true
        button.accessibilityTraits = .button
        button.onDown = { [weak self] point in self?.press(at: point) }
        button.onMove = { [weak self] point in self?.move(to: point) }
        button.onUp = { [weak self] point in self?.release(at: point) }
        button.onCancel = { [weak self] in self?.reset() }
        button.onActivate = { [weak self] in self?.activate() }
        render()
    }

    required init?(coder: NSCoder) { nil }

    override func layoutSubviews() {
        super.layoutSubviews()
        let side = min(50, bounds.height)
        if !dragging {
            button.frame = CGRect(x: dockedLeading ? 0 : bounds.width - side, y: 0, width: side, height: bounds.height)
        }
        face.frame = button.bounds.insetBy(dx: 1, dy: (bounds.height - side) / 2 + 1)
        face.layer.cornerRadius = min(16, face.bounds.height / 2)
        let iconSize = min(27, face.bounds.height * 0.65)
        mark.frame = CGRect(x: (face.bounds.width - iconSize) / 2, y: (face.bounds.height - iconSize) / 2,
                            width: iconSize, height: iconSize)

        let pillWidth = min(178, bounds.width)
        pill.frame = CGRect(x: dockedLeading ? 0 : bounds.width - pillWidth, y: 2, width: pillWidth, height: bounds.height - 4)
        let cancelWidth: CGFloat = 44
        let cancelX = dockedLeading ? pill.bounds.width - cancelWidth : 0
        cancelButton.frame = CGRect(x: cancelX, y: 0, width: cancelWidth, height: pill.bounds.height)
        let waveCenter = (pill.bounds.width + (dockedLeading ? side - cancelWidth : cancelWidth - side)) / 2
        waveform.frame = CGRect(x: waveCenter - 31.5, y: 0, width: 63, height: pill.bounds.height)
    }

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        let hit = super.hitTest(point, with: event)
        return hit === self || hit === pill ? nil : hit
    }

    func reset() {
        guard state.phase != .idle || touchStart != nil || dragging else { return }
        holdTask?.cancel()
        finishTask?.cancel()
        touchStart = nil
        dragging = false
        state.reset()
        render()
    }

    private func press(at point: CGPoint) {
        consumesRelease = false
        if state.phase == .processing {
            reset()
            consumesRelease = true
            return
        }
        guard state.phase == .idle || state.phase == .locked else { return }
        touchStart = (point, ProcessInfo.processInfo.systemUptime)
        guard state.phase == .idle else { return }
        holdTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 80_000_000)
            guard !Task.isCancelled, let self else { return }
            self.state.hold()
            self.feedback.impactOccurred()
            self.render()
        }
    }

    private func move(to point: CGPoint) {
        guard let touchStart else { return }
        let dx = point.x - touchStart.point.x
        let dy = point.y - touchStart.point.y
        if state.phase == .idle, dragging || hypot(dx, dy) > 6 {
            holdTask?.cancel()
            dragging = true
            let half = button.bounds.width / 2
            button.center.x = min(max(point.x, half), bounds.width - half)
        } else if state.phase == .held {
            let oldTarget = state.target
            state.move(vertical: dy)
            if state.target != oldTarget {
                feedback.impactOccurred()
                render()
            }
        }
    }

    private func release(at point: CGPoint) {
        defer { consumesRelease = false }
        guard !consumesRelease, let touchStart else { return }
        holdTask?.cancel()
        move(to: point)
        self.touchStart = nil
        if dragging {
            dragging = false
            let leading = button.center.x < bounds.midX
            if leading != dockedLeading {
                dockedLeading = leading
                onDock(leading)
            }
            setNeedsLayout()
            return
        }
        state.release(after: ProcessInfo.processInfo.systemUptime - touchStart.time)
        completeTransition()
    }

    private func activate() {
        state.release(after: 0)
        completeTransition()
    }

    private func completeTransition() {
        feedback.impactOccurred()
        finishTask?.cancel()
        render()
        guard state.phase == .processing else { return }
        finishTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 850_000_000)
            guard !Task.isCancelled else { return }
            self?.reset()
        }
    }

    private func render() {
        let phase = state.phase
        let active = phase == .held || phase == .locked
        pill.isHidden = !active
        face.backgroundColor = phase == .held ? UIColor(white: 0.91, alpha: 1) : .white
        face.alpha = phase == .locked ? 1 : 0.65
        let icon: String?
        switch phase {
        case .processing: icon = "ellipsis"
        case .locked: icon = "checkmark"
        case .held: icon = state.target == .lock ? "lock.fill" : nil
        case .idle: icon = nil
        }
        mark.image = icon.flatMap { UIImage(systemName: $0)?.withRenderingMode(.alwaysTemplate) } ?? idleImage
        button.accessibilityLabel = "MyWhisper dictation preview"
        switch phase {
        case .idle: button.accessibilityValue = "Ready"
        case .held: button.accessibilityValue = "Hold to dictate"
        case .locked: button.accessibilityValue = "Hands-free. Activate to finish"
        case .processing: button.accessibilityValue = "Processing. Activate to cancel"
        }
        cancelButton.tintColor = state.target == .cancel ? .systemRed : .white
        for (index, bar) in waveform.arrangedSubviews.enumerated() {
            bar.backgroundColor = state.target == .lock ? .systemTeal : .white
            if active && !UIAccessibility.isReduceMotionEnabled {
                guard bar.layer.animation(forKey: "wave") == nil else { continue }
                let wave = CABasicAnimation(keyPath: "transform.scale.y")
                wave.fromValue = 0.4
                wave.toValue = 1
                wave.duration = 0.22 + Double(index % 4) * 0.07
                wave.autoreverses = true
                wave.repeatCount = .infinity
                bar.layer.add(wave, forKey: "wave")
            } else {
                bar.layer.removeAnimation(forKey: "wave")
            }
        }
    }

    private lazy var idleImage = mark.image
}

private final class KeyboardMicTouchSurface: UIControl {
    var onDown: ((CGPoint) -> Void)?
    var onMove: ((CGPoint) -> Void)?
    var onUp: ((CGPoint) -> Void)?
    var onCancel: (() -> Void)?
    var onActivate: (() -> Void)?

    override func beginTracking(_ touch: UITouch, with event: UIEvent?) -> Bool {
        onDown?(touch.location(in: superview))
        return true
    }

    override func continueTracking(_ touch: UITouch, with event: UIEvent?) -> Bool {
        onMove?(touch.location(in: superview))
        return true
    }

    override func endTracking(_ touch: UITouch?, with event: UIEvent?) {
        if let touch { onUp?(touch.location(in: superview)) }
        else { onCancel?() }
    }

    override func cancelTracking(with event: UIEvent?) { onCancel?() }

    override func accessibilityActivate() -> Bool {
        onActivate?()
        return true
    }
}
