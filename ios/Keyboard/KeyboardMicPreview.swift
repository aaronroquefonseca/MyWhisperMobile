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
    private let waveform = KeyboardBarsView()
    /// While holding: slide up to lock, down to cancel. Android draws targets above and
    /// below the button; a keyboard can't draw above itself, so the hint sits beside it.
    private let lockHint = KeyboardMicPreview.hintRow(arrow: "chevron.up", symbol: "lock.fill")
    private let cancelHint = KeyboardMicPreview.hintRow(arrow: "chevron.down", symbol: "xmark")
    private static let hintWidth: CGFloat = 28
    private let meterHint = UILabel()
    private var meterTask: Task<Void, Never>?
    private var renderedPhase = KeyboardMicPreviewState.Phase.idle
    private var state = KeyboardMicPreviewState()
    private var touchStart: (point: CGPoint, time: TimeInterval)?
    private var holdTask: Task<Void, Never>?
    private var finishTask: Task<Void, Never>?
    private(set) var dockedLeading: Bool
    /// Button center x while the user drags it, so the button opposite can trade places live.
    var onDrag: ((CGFloat) -> Void)?
    private var dragging = false
    private var consumesRelease = false
    private var tapOpensSession = false
    private let onDock: (Bool) -> Void
    /// Nil when MyWhisper can't share levels (no Full Access); the mockup then runs without them.
    private let openSession: (() -> Void)?

    init(frame: CGRect, dockedLeading: Bool, openSession: (() -> Void)?, onDock: @escaping (Bool) -> Void) {
        self.dockedLeading = dockedLeading
        self.openSession = openSession
        self.onDock = onDock
        super.init(frame: frame)

        pill.backgroundColor = UIColor(red: 29 / 255, green: 27 / 255, blue: 32 / 255, alpha: 0.8)
        pill.layer.cornerRadius = 16
        pill.clipsToBounds = true
        addSubview(pill)

        pill.addSubview(waveform)
        pill.addSubview(lockHint)
        pill.addSubview(cancelHint)
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
        // Every keyboard rebuild makes a new preview: start docked, don't animate in from .zero.
        UIView.performWithoutAnimation {
            render()
            layoutIfNeeded()
        }
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
        let hint = phase == .held ? Self.hintWidth : 0
        let pillWidth = min(bounds.width, phase == .locked ? 178 : phase == .held ? 144 + hint : side)
        pill.frame = CGRect(x: dockedLeading ? 0 : bounds.width - pillWidth, y: 2, width: pillWidth, height: bounds.height - 4)
        let cancelWidth: CGFloat = 44
        let cancelX = dockedLeading ? pillWidth - cancelWidth : 0
        cancelButton.frame = CGRect(x: cancelX, y: 0, width: cancelWidth, height: pill.bounds.height)
        let waveCenter: CGFloat
        if phase == .locked {
            waveCenter = (pillWidth + (dockedLeading ? side - cancelWidth : cancelWidth - side)) / 2
        } else {
            waveCenter = (pillWidth + (dockedLeading ? side - hint : hint - side)) / 2
        }
        // At the far end of the pill, past the bars, where the thumb on the button can't cover it.
        let hintX = dockedLeading ? pillWidth - Self.hintWidth - 4 : 4
        let half = pill.bounds.height / 2
        lockHint.frame = CGRect(x: hintX, y: 3, width: Self.hintWidth, height: half - 3)
        cancelHint.frame = CGRect(x: hintX, y: half, width: Self.hintWidth, height: half - 3)
        waveform.frame = CGRect(x: waveCenter - 31.5, y: (pill.bounds.height - 24) / 2, width: 63, height: 24)
        meterHint.frame = CGRect(x: waveCenter - 40, y: 0, width: 80, height: pill.bounds.height)

    }

    override func hitTest(_ point: CGPoint, with event: UIEvent?) -> UIView? {
        let hit = super.hitTest(point, with: event)
        return hit === self || hit === pill ? nil : hit
    }

    /// Slides the button to an edge, e.g. when the user drags the other button across.
    func dock(leading: Bool, completion: (() -> Void)? = nil) {
        dockedLeading = leading
        setNeedsLayout()
        UIView.animate(withDuration: UIAccessibility.isReduceMotionEnabled ? 0 : 0.32, delay: 0,
                       usingSpringWithDamping: 0.86, initialSpringVelocity: 0,
                       options: [.allowUserInteraction, .beginFromCurrentState]) {
            self.layoutIfNeeded()
        } completion: { _ in
            completion?()
        }
    }

    func reset() {
        guard state.phase != .idle || touchStart != nil || dragging else { return }
        holdTask?.cancel()
        finishTask?.cancel()
        touchStart = nil
        dragging = false
        tapOpensSession = false
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
        // No live mic session: releasing a tap or hold opens MyWhisper to start one. Dragging still works.
        tapOpensSession = openSession != nil && !Self.sessionIsLive
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
            onDrag?(button.center.x)
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
            let changed = leading != dockedLeading
            dock(leading: leading) { [weak self] in
                if changed { self?.onDock(leading) }
            }
            return
        }
        let duration = ProcessInfo.processInfo.systemUptime - touchStart.time
        // No live session: a tap or a hold (unless slid to cancel) opens MyWhisper to start one.
        if tapOpensSession, state.phase == .idle || (state.phase == .held && state.target != .cancel) {
            tapOpensSession = false
            state.reset()
            render()
            openSession?()
            return
        }
        tapOpensSession = false
        state.release(after: duration)
        completeTransition()
    }

    private static var sessionIsLive: Bool {
        KeyboardDictationMailbox.shared?.meter?.isCurrent(at: Date()) == true
    }

    private func activate() {
        if state.phase == .idle, let openSession, !Self.sessionIsLive {
            openSession()
            return
        }
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
            self.lockHint.alpha = phase == .held ? 1 : 0
            self.cancelHint.alpha = phase == .held ? 1 : 0
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
        case .held: button.accessibilityValue = "Slide up to lock, down to cancel"
        case .locked: button.accessibilityValue = "Hands-free. Activate to finish"
        case .processing: button.accessibilityValue = "Processing. Activate to cancel"
        }
        cancelButton.tintColor = state.target == .cancel ? .systemRed : .white
        lockHint.tintColor = state.target == .lock ? .systemTeal : .white
        cancelHint.tintColor = state.target == .cancel ? .systemRed : .white
        if phase == .held, !UIAccessibility.isReduceMotionEnabled {
            Self.nudge(lockHint, by: -2)
            Self.nudge(cancelHint, by: 2)
        } else {
            lockHint.layer.removeAnimation(forKey: "nudge")
            cancelHint.layer.removeAnimation(forKey: "nudge")
        }
        waveform.barColor = state.target == .lock ? .systemTeal : .white
        if active {
            if meterTask == nil { startMeter() }
        } else {
            meterTask?.cancel()
            meterTask = nil
            meterHint.isHidden = true
            waveform.isHidden = false
            waveform.clear()
        }
    }

    private func startMeter() {
        meterHint.text = openSession == nil ? "Allow Full Access for mic levels" : "Start the mic in MyWhisper"
        meterTask = Task { @MainActor [weak self] in
            let mailbox = KeyboardDictationMailbox.shared
            while !Task.isCancelled {
                guard let self else { return }
                let reading = mailbox?.meter
                let available = reading?.isCurrent(at: Date()) == true
                self.meterHint.isHidden = available
                self.waveform.isHidden = !available
                self.waveform.push(available ? reading?.level ?? 0 : 0)
                do {
                    try await Task.sleep(nanoseconds: 40_000_000)
                } catch {
                    return
                }
            }
        }
    }

    private lazy var idleImage = mark.image

    private static func hintRow(arrow: String, symbol: String) -> UIStackView {
        let config = UIImage.SymbolConfiguration(pointSize: 9, weight: .bold)
        let row = UIStackView(arrangedSubviews: [arrow, symbol].map { name in
            let image = UIImageView(image: UIImage(systemName: name, withConfiguration: config))
            image.contentMode = .center
            return image
        })
        row.axis = .horizontal
        row.spacing = 2
        row.alignment = .center
        row.distribution = .fillEqually
        row.tintColor = .white
        row.alpha = 0
        row.isUserInteractionEnabled = false
        row.isAccessibilityElement = false
        return row
    }

    /// Gently points each hint the way the finger should go.
    private static func nudge(_ view: UIView, by offset: CGFloat) {
        guard view.layer.animation(forKey: "nudge") == nil else { return }
        let nudge = CABasicAnimation(keyPath: "transform.translation.y")
        nudge.fromValue = 0
        nudge.toValue = offset
        nudge.duration = 0.45
        nudge.autoreverses = true
        nudge.repeatCount = .infinity
        nudge.timingFunction = CAMediaTimingFunction(name: .easeInEaseOut)
        view.layer.add(nudge, forKey: "nudge")
    }
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

/// Port of Android `BloomBars`: silence = dots; voice opens the bars from the
/// middle out, every bar showing the current level under a mirrored bell.
/// Smoothing rates are "per 60 Hz frame", rescaled by dt for 120 Hz screens.
struct KeyboardBloomBars {
    static let voiceGate: Float = 0.08
    /// Voice stays "on" this long after the last loud reading, bridging word gaps.
    static let hangover: TimeInterval = 0.22
    private static let bellSigma: Float = 0.32
    private static let wobbleDepth: Float = 0.16
    private static let settled: Float = 0.002

    private(set) var heights: [Float]
    private var input: Float = 0
    private var level: Float = 0
    private var spread: Float = 0
    private var clock: Float = 0
    private var lastVoice = -TimeInterval.infinity
    /// One phase set per mirrored pair, so left and right halves move together.
    private let phases: [(Float, Float, Float, Float)]

    init(count: Int = 11) {
        heights = Array(repeating: 0, count: count)
        phases = (0..<(count + 1) / 2).map { _ in
            (Float.random(in: 0..<(2 * .pi)), Float.random(in: 0..<(2 * .pi)),
             Float.random(in: 2..<5), Float.random(in: 3..<7))
        }
    }

    mutating func push(_ value: Float, at now: TimeInterval) {
        input = min(max(value, 0), 1)
        if input > Self.voiceGate { lastVoice = now }
    }

    mutating func reset() {
        heights = heights.map { _ in 0 }
        input = 0
        level = 0
        spread = 0
        lastVoice = -.infinity
    }

    /// Advance `dt` seconds; returns true while still moving (keep drawing).
    mutating func step(_ dt: Float, at now: TimeInterval) -> Bool {
        clock += dt
        let voice = now - lastVoice < Self.hangover
        level = Self.approach(level, input, input > level ? 0.55 : 0.18, dt)
        // ~120 ms to open, ~400 ms to close.
        spread = Self.approach(spread, voice ? 1 : 0, voice ? 0.22 : 0.07, dt)
        let center = Float(heights.count - 1) / 2
        var moving = voice || spread > Self.settled
        for index in heights.indices {
            let distance: Float = center == 0 ? 0 : abs(Float(index) - center) / center
            let reveal: Float = min(max((spread * 1.25 - distance) / 0.25, 0), 1)
            let bellExponent: Float = -(distance * distance) / (2 * Self.bellSigma * Self.bellSigma)
            let bell: Float = 0.12 + 0.88 * exp(bellExponent)
            let phase = phases[min(index, heights.count - 1 - index)]
            let slow: Float = sin(clock * phase.2 + phase.0)
            let fast: Float = sin(clock * phase.3 + phase.1)
            let wobble: Float = 1 - Self.wobbleDepth + Self.wobbleDepth * (0.5 + 0.25 * slow + 0.25 * fast)
            heights[index] = Self.approach(heights[index], reveal * level * bell * wobble, 0.45, dt)
            if heights[index] > Self.settled { moving = true }
        }
        return moving
    }

    private static func approach(_ current: Float, _ target: Float, _ perFrame60: Float, _ dt: Float) -> Float {
        current + (target - current) * (1 - pow(1 - perFrame60, dt * 60))
    }
}

/// Android `BarsView` on iOS: 11 white bars, 3 pt wide, 3 pt gap, 4 pt resting dots.
final class KeyboardBarsView: UIView {
    private var bloom = KeyboardBloomBars()
    private var bars: [CALayer] = []
    private var link: CADisplayLink?
    private var lastFrame: CFTimeInterval = 0

    var barColor: UIColor = .white {
        didSet { bars.forEach { $0.backgroundColor = barColor.cgColor } }
    }

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        isAccessibilityElement = false
        bars = bloom.heights.map { _ in
            let bar = CALayer()
            bar.backgroundColor = barColor.cgColor
            bar.cornerRadius = 1.5
            layer.addSublayer(bar)
            return bar
        }
    }

    required init?(coder: NSCoder) { nil }

    func push(_ level: Float) {
        bloom.push(level, at: CACurrentMediaTime())
        guard link == nil, window != nil else { return }
        let link = CADisplayLink(target: self, selector: #selector(advance(_:)))
        link.add(to: .main, forMode: .common)
        self.link = link
    }

    func clear() {
        stopLink()
        bloom.reset()
        layoutBars()
    }

    override func willMove(toWindow newWindow: UIWindow?) {
        super.willMove(toWindow: newWindow)
        // The display link retains its target; never outlive the keyboard.
        if newWindow == nil { stopLink() }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        layoutBars()
    }

    @objc private func advance(_ link: CADisplayLink) {
        let now = CACurrentMediaTime()
        let dt = lastFrame == 0 ? 1.0 / 60 : min(max(now - lastFrame, 0), 0.1)
        lastFrame = now
        if !bloom.step(Float(dt), at: now) { stopLink() }
        layoutBars()
    }

    private func stopLink() {
        link?.invalidate()
        link = nil
        lastFrame = 0
    }

    private func layoutBars() {
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        let middle = bounds.height / 2
        for (index, bar) in bars.enumerated() {
            // Mirrors Android HardeningGuards.barHalfHeight: 2..18 pt half height, capped by the view.
            let half = min(2 + CGFloat(bloom.heights[index]) * 16, max(middle - 1, 0))
            bar.frame = CGRect(x: CGFloat(index) * 6, y: middle - half, width: 3, height: half * 2)
        }
        CATransaction.commit()
    }
}
