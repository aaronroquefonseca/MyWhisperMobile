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
    private let meterHint = UILabel()
    private var meterTask: Task<Void, Never>?
    private var recentLevels = Array(repeating: CGFloat.zero, count: 11)
    private var renderedPhase = KeyboardMicPreviewState.Phase.idle
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
        pill.clipsToBounds = true
        addSubview(pill)

        waveform.axis = .horizontal
        waveform.spacing = 3
        waveform.alignment = .center
        waveform.isUserInteractionEnabled = false
        waveform.isAccessibilityElement = false
        for _ in recentLevels {
            let bar = UIView()
            bar.backgroundColor = .white
            bar.layer.cornerRadius = 1.5
            bar.widthAnchor.constraint(equalToConstant: 3).isActive = true
            bar.heightAnchor.constraint(equalToConstant: 24).isActive = true
            waveform.addArrangedSubview(bar)
        }
        pill.addSubview(waveform)
        meterHint.text = "Enable mic preview in MyWhisper"
        meterHint.textColor = .white
        meterHint.font = .systemFont(ofSize: 10, weight: .medium)
        meterHint.textAlignment = .center
        meterHint.numberOfLines = 2
        meterHint.isUserInteractionEnabled = false
        meterHint.isHidden = true
        pill.addSubview(meterHint)

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

        let phase = state.phase
        let pillWidth = min(bounds.width, phase == .locked ? 178 : phase == .held ? 144 : side)
        pill.frame = CGRect(x: dockedLeading ? 0 : bounds.width - pillWidth, y: 2, width: pillWidth, height: bounds.height - 4)
        let cancelWidth: CGFloat = 44
        let cancelX = dockedLeading ? pillWidth - cancelWidth : 0
        cancelButton.frame = CGRect(x: cancelX, y: 0, width: cancelWidth, height: pill.bounds.height)
        let waveCenter: CGFloat
        if phase == .locked {
            waveCenter = (pillWidth + (dockedLeading ? side - cancelWidth : cancelWidth - side)) / 2
        } else {
            waveCenter = (pillWidth + (dockedLeading ? side : -side)) / 2
        }
        waveform.frame = CGRect(x: waveCenter - 31.5, y: 0, width: 63, height: pill.bounds.height)
        meterHint.frame = CGRect(x: waveCenter - 40, y: 0, width: 80, height: pill.bounds.height)

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
        let phaseChanged = phase != renderedPhase
        renderedPhase = phase
        let duration = UIAccessibility.isReduceMotionEnabled ? 0 : 0.12
        pill.isUserInteractionEnabled = active
        cancelButton.isEnabled = phase == .locked || state.target == .cancel
        cancelButton.accessibilityElementsHidden = !cancelButton.isEnabled

        setNeedsLayout()
        UIView.animate(withDuration: duration, delay: 0, options: [.curveEaseOut, .beginFromCurrentState, .allowUserInteraction]) {
            self.layoutIfNeeded()
            self.pill.alpha = active ? 1 : 0
            self.face.backgroundColor = phase == .held ? UIColor(white: 0.91, alpha: 1) : .white
            self.face.alpha = phase == .locked ? 1 : 0.65
        }
        UIView.animate(withDuration: duration == 0 ? 0 : (active ? 0.1 : 0.08), delay: 0,
                       options: [.beginFromCurrentState, .allowUserInteraction]) {
            self.cancelButton.alpha = self.cancelButton.isEnabled ? 1 : 0
        }
        let icon: String?
        switch phase {
        case .processing: icon = "ellipsis"
        case .locked: icon = "checkmark"
        case .held: icon = state.target == .lock ? "lock.fill" : nil
        case .idle: icon = nil
        }
        let nextImage = icon.flatMap { UIImage(systemName: $0)?.withRenderingMode(.alwaysTemplate) } ?? idleImage
        UIView.transition(with: mark, duration: phaseChanged ? duration : 0, options: [.transitionCrossDissolve, .allowUserInteraction]) {
            self.mark.image = nextImage
        }
        button.accessibilityLabel = "MyWhisper dictation preview"
        switch phase {
        case .idle: button.accessibilityValue = "Ready"
        case .held: button.accessibilityValue = "Hold to dictate"
        case .locked: button.accessibilityValue = "Hands-free. Activate to finish"
        case .processing: button.accessibilityValue = "Processing. Activate to cancel"
        }
        cancelButton.tintColor = state.target == .cancel ? .systemRed : .white
        for bar in waveform.arrangedSubviews {
            bar.backgroundColor = state.target == .lock ? .systemTeal : .white
        }
        if active {
            if meterTask == nil { startMeter() }
        } else {
            meterTask?.cancel()
            meterTask = nil
            recentLevels = recentLevels.map { _ in 0 }
            meterHint.isHidden = true
            waveform.isHidden = false
            drawMeter(level: 0)
        }
    }

    private func startMeter() {
        meterTask = Task { @MainActor [weak self] in
            let mailbox = KeyboardDictationMailbox.shared
            while !Task.isCancelled {
                do {
                    try await Task.sleep(nanoseconds: 80_000_000)
                } catch {
                    return
                }
                guard let self else { return }
                let now = Date()
                let reading = mailbox?.meter
                let available = reading?.isCurrent(at: now) == true
                self.meterHint.isHidden = available
                self.waveform.isHidden = !available
                self.drawMeter(level: available ? CGFloat(reading?.level ?? 0) : 0)
            }
        }
    }

    private func drawMeter(level: CGFloat) {
        recentLevels.removeFirst()
        recentLevels.append(level)
        let duration = UIAccessibility.isReduceMotionEnabled ? 0 : 0.08
        UIView.animate(withDuration: duration, delay: 0, options: [.curveEaseOut, .beginFromCurrentState, .allowUserInteraction]) {
            for (bar, sample) in zip(self.waveform.arrangedSubviews, self.recentLevels) {
                bar.transform = CGAffineTransform(scaleX: 1, y: max(0.12, min(1, sqrt(sample) * 1.6)))
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
