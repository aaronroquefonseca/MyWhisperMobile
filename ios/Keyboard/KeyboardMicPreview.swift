import UIKit
import WebKit

/// Turns the audio of one hold into text. Implemented in the keyboard target only, where the
/// speech core is linked; calls arrive on the main thread, the completion too.
protocol KeyboardDictating: AnyObject {
    func begin(sampleRate: Double)
    func push(_ samples: [Int16])
    func finish(_ completion: @escaping (Result<String, Error>) -> Void)
    func cancel()
}

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
    private let dots = KeyboardProcessingDots()
    private let pill = UIView()
    private let cancelButton = UIButton(type: .system)
    private let waveform = KeyboardBarsView()
    /// While holding: slide up to lock, down to cancel. Android draws targets above and
    /// below the button; a keyboard can't draw above itself, so the hint sits beside it.
    private let lockHint = KeyboardMicPreview.hintRow(arrow: "chevron.up", symbol: "lock.fill")
    private let cancelHint = KeyboardMicPreview.hintRow(arrow: "chevron.down", symbol: "xmark")
    private static let hintWidth: CGFloat = 28
    private var renderedPhase = KeyboardMicPreviewState.Phase.idle
    private var state = KeyboardMicPreviewState()
    private var touchStart: (point: CGPoint, time: TimeInterval)?
    private var holdTask: Task<Void, Never>?
    private var finishTask: Task<Void, Never>?
    private(set) var dockedLeading: Bool
    /// Button center x while the user drags it, so the button opposite can trade places live.
    var onDrag: ((CGFloat) -> Void)?
    /// The keyboard's haptic (`true` for a hold): a generator without a view stays silent in an extension.
    var haptic: ((Bool) -> Void)?
    /// Whether the pill with the bars is showing, so the toolbar can hide what would show through it.
    var onActive: ((Bool) -> Void)?
    /// Transcribes what the web mic hears. Nil: the key only previews levels.
    var dictation: KeyboardDictating?
    var onText: ((String) -> Void)?
    var onDictationError: ((String) -> Void)?
    /// Audio reaches `dictation` from the first sample until the web mic confirms it stopped.
    private var capturing = false
    /// `dictation` holds an utterance from this hold that must be finished or cancelled.
    private var utteranceOpen = false
    private var dragging = false
    private var consumesRelease = false
    private var micUnavailable = false
    private var webMicActive = false
    private let onDock: (Bool) -> Void
    /// False without Full Access: keyboards then can't use the microphone at all.
    private let fullAccess: Bool
    /// Says why there's no mic, on a tap or hold that can't record.
    private let onUnavailable: () -> Void

    init(frame: CGRect, dockedLeading: Bool, fullAccess: Bool, onUnavailable: @escaping () -> Void, onDock: @escaping (Bool) -> Void) {
        self.dockedLeading = dockedLeading
        self.fullAccess = fullAccess
        self.onUnavailable = onUnavailable
        self.onDock = onDock
        super.init(frame: frame)
        webMic?.prepare(in: self)

        pill.backgroundColor = UIColor(red: 29 / 255, green: 27 / 255, blue: 32 / 255, alpha: 0.8)
        pill.layer.cornerRadius = 16
        pill.clipsToBounds = true
        addSubview(pill)

        pill.addSubview(waveform)
        pill.addSubview(lockHint)
        pill.addSubview(cancelHint)

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
        dots.tintColor = .black
        dots.isHidden = true
        face.addSubview(dots)
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
        dots.frame = mark.frame

        // Button, gap, bars, gap, then the hints (held) or the cancel button (locked) at the far end.
        let phase = state.phase
        let gap: CGFloat = 12
        let waveWidth = KeyboardBarsView.width
        let cancelWidth: CGFloat = 44
        let endWidth = phase == .locked ? cancelWidth : Self.hintWidth + 8
        let open = phase == .held || phase == .locked
        let pillWidth = min(bounds.width, open ? side + gap + waveWidth + gap + endWidth : side)
        pill.frame = CGRect(x: dockedLeading ? 0 : bounds.width - pillWidth, y: 2, width: pillWidth, height: bounds.height - 4)
        let endX = dockedLeading ? pillWidth - endWidth : 0
        let waveX = dockedLeading ? side + gap : endWidth + gap
        waveform.frame = CGRect(x: waveX, y: (pill.bounds.height - 24) / 2, width: waveWidth, height: 24)
        // While sliding to cancel, the cross sits over the bars rather than on top of the hints.
        let cancelX = phase == .held ? waveform.frame.midX - cancelWidth / 2 : endX
        cancelButton.frame = CGRect(x: cancelX, y: 0, width: cancelWidth, height: pill.bounds.height)
        let half = pill.bounds.height / 2
        lockHint.frame = CGRect(x: endX + 4, y: 3, width: Self.hintWidth, height: half - 3)
        cancelHint.frame = CGRect(x: endX + 4, y: half, width: Self.hintWidth, height: half - 3)

    }

    override func willMove(toSuperview newSuperview: UIView?) {
        super.willMove(toSuperview: newSuperview)
        // A keyboard rebuild replaces this view: stop the mic rather than leave it running unseen.
        if newSuperview == nil { reset() }
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
        micUnavailable = false
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
        // No mic: a tap or hold says why. Dragging still works.
        micUnavailable = webMic == nil
        holdTask = Task { @MainActor [weak self] in
            try? await Task.sleep(nanoseconds: 80_000_000)
            guard !Task.isCancelled, let self else { return }
            if self.micUnavailable {
                self.micUnavailable = false
                self.consumesRelease = true
                self.haptic?(true)
                self.onUnavailable()
                return
            }
            self.state.hold()
            self.haptic?(true)
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
                haptic?(false)
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
        if micUnavailable, state.phase == .idle || (state.phase == .held && state.target != .cancel) {
            micUnavailable = false
            state.reset()
            render()
            onUnavailable()
            return
        }
        micUnavailable = false
        state.release(after: duration)
        completeTransition()
    }

    /// The keyboard's own mic, with Full Access, until WebKit refuses it once.
    private var webMic: KeyboardWebMic? {
        fullAccess && !KeyboardWebMic.shared.failed ? .shared : nil
    }

    private func activate() {
        if state.phase == .idle, webMic == nil {
            onUnavailable()
            return
        }
        state.release(after: 0)
        completeTransition()
    }

    private func completeTransition() {
        haptic?(false)
        finishTask?.cancel()
        render()
        // A transcript ends processing itself (see finishDictation).
        guard state.phase == .processing, !capturing, !utteranceOpen else { return }
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
        if phaseChanged { onActive?(active) }
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
            self.waveform.alpha = self.state.target == .cancel ? 0.3 : 1
            self.face.backgroundColor = phase == .held ? UIColor(white: 0.91, alpha: 1) : .white
            self.face.alpha = phase == .locked ? 1 : 0.65
        }
        UIView.animate(withDuration: duration == 0 ? 0 : (active ? 0.1 : 0.08), delay: 0,
                       options: [.beginFromCurrentState, .allowUserInteraction]) {
            self.cancelButton.alpha = self.cancelButton.isEnabled ? 1 : 0
        }
        let icon: String?
        switch phase {
        case .processing: icon = nil
        case .locked: icon = "checkmark"
        case .held: icon = state.target == .lock ? "lock.fill" : nil
        case .idle: icon = nil
        }
        let nextImage = phase == .processing ? nil : icon.flatMap { UIImage(systemName: $0)?.withRenderingMode(.alwaysTemplate) } ?? idleImage
        if phaseChanged {
            dots.isHidden = phase != .processing
            if phase == .processing { dots.start() } else { dots.stop() }
        }
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
            if !webMicActive { startMeter() }
        } else {
            if webMicActive {
                webMicActive = false
                // Released normally: transcribe once the last audio is in. Otherwise drop it.
                let transcribe = phase == .processing
                let stop = {
                    KeyboardWebMic.shared.stop { [weak self] in
                        guard let self else { return }
                        self.capturing = false
                        if transcribe { self.finishDictation() } else { self.cancelDictation() }
                    }
                }
                // Android's extra listening delay: keep recording briefly so final sounds aren't cut.
                let delay = transcribe ? Double(MicRoute.shared.releaseDelayMs) / 1000 : 0
                if delay > 0 {
                    DispatchQueue.main.asyncAfter(deadline: .now() + delay) { [weak self] in
                        MainActor.assumeIsolated {
                            // A new hold already took the mic over.
                            guard self?.webMicActive == false else { return }
                            stop()
                        }
                    }
                } else {
                    stop()
                }
            } else if phase == .idle {
                capturing = false
                cancelDictation()
            }
            waveform.isHidden = false
            waveform.clear()
        }
    }

    private func startMeter() {
        guard let webMic else {
            reset()
            onUnavailable()
            return
        }
        webMicActive = true
        waveform.isHidden = false
        webMic.onLevel = { [weak self] level in self?.waveform.push(level) }
        capturing = dictation != nil
        webMic.onPcm = { [weak self] samples, rate in
            guard let self, self.capturing, let dictation = self.dictation else { return }
            if !self.utteranceOpen {
                self.utteranceOpen = true
                dictation.begin(sampleRate: rate)
            }
            dictation.push(samples)
        }
        webMic.onFailure = { [weak self] in
            // WebKit refused the mic: say so, and later holds say it straight away.
            guard let self, self.webMicActive else { return }
            self.webMicActive = false
            self.capturing = false
            self.cancelDictation()
            self.reset()
            self.onUnavailable()
        }
        webMic.start(in: self)
    }

    private func finishDictation() {
        guard utteranceOpen, let dictation else {
            if state.phase == .processing { reset() }
            return
        }
        utteranceOpen = false
        dictation.finish { [weak self] result in
            // A press while processing cancelled it: drop the late transcript.
            guard let self, self.state.phase == .processing else { return }
            switch result {
            case .success(let text): self.onText?(text)
            case .failure(let error): self.onDictationError?(KeyboardMicPreview.message(for: error))
            }
            self.reset()
        }
    }

    private func cancelDictation() {
        guard utteranceOpen else { return }
        utteranceOpen = false
        dictation?.cancel()
    }

    private static func message(for error: Error) -> String {
        let text = (error as? LocalizedError)?.errorDescription ?? String(describing: error)
        return text.count > 80 ? String(text.prefix(79)) + "…" : text
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
    static let width: CGFloat = 11 * 6 - 3
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


/// Captures the mic inside the keyboard through a hidden web view. WebKit's own processes may use
/// the mic where a keyboard extension can't, so holding the key needs no app switch and the mic is
/// on only while listening. Raycast's keyboard does the same (see stephancill/ios-keyboard-dictation).
@MainActor
final class KeyboardWebMic: NSObject, WKScriptMessageHandler, WKUIDelegate, WKNavigationDelegate {
    static let shared = KeyboardWebMic()

    /// Set once WebKit refuses the mic; the keyboard then uses MyWhisper's session instead.
    private(set) var failed = false
    var onLevel: ((Float) -> Void)?
    /// 16-bit mono samples at the given rate, from start until the page confirms the stop.
    var onPcm: (([Int16], Double) -> Void)?
    var onFailure: (() -> Void)?
    private var stopped: (() -> Void)?
    private var stopCount = 0
    private var webView: WKWebView?
    private var ready = false
    private var wanted = false
    private var heartbeat: Timer?

    /// Loads the page ahead of the first hold, so a hold waits only for the mic itself.
    /// The web view must sit in the keyboard's window: WebKit throttles one outside it.
    func prepare(in host: UIView) {
        let webView = self.webView ?? makeWebView()
        if webView.superview !== host { host.insertSubview(webView, at: 0) }
    }

    func start(in host: UIView) {
        prepare(in: host)
        wanted = true
        MicRoute.preferSharedInput()
        if ready { run("startMic()") }
        heartbeat?.invalidate()
        // The page stops the mic by itself if these stop, e.g. when the keyboard process is suspended.
        heartbeat = Timer.scheduledTimer(withTimeInterval: 1, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { self?.run("beat()") }
        }
    }

    /// `then` runs after the last audio arrived, or after a short wait if the page doesn't answer.
    func stop(then: (() -> Void)? = nil) {
        wanted = false
        heartbeat?.invalidate()
        heartbeat = nil
        stopEnded()
        stopped = then
        stopCount += 1
        let count = stopCount
        guard ready else { return stopEnded() }
        run("stopMic()")
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.6) { [weak self] in
            MainActor.assumeIsolated {
                guard let self, self.stopCount == count else { return }
                self.stopEnded()
            }
        }
    }

    private func stopEnded() {
        let done = stopped
        stopped = nil
        done?()
    }

    private func makeWebView() -> WKWebView {
        let configuration = WKWebViewConfiguration()
        configuration.allowsInlineMediaPlayback = true
        configuration.mediaTypesRequiringUserActionForPlayback = []
        configuration.websiteDataStore = .nonPersistent()
        configuration.userContentController.add(self, name: "mic")
        let webView = WKWebView(frame: CGRect(x: 0, y: 0, width: 1, height: 1), configuration: configuration)
        webView.alpha = 0.01
        webView.isUserInteractionEnabled = false
        webView.accessibilityElementsHidden = true
        webView.uiDelegate = self
        webView.navigationDelegate = self
        // getUserMedia needs a secure context, which an https base URL gives.
        webView.loadHTMLString(Self.page, baseURL: URL(string: "https://keyboard-mic.invalid/"))
        self.webView = webView
        return webView
    }

    private func run(_ script: String) {
        webView?.evaluateJavaScript(script + "; 0", completionHandler: nil)
    }

    func userContentController(_ controller: WKUserContentController, didReceive message: WKScriptMessage) {
        guard let body = message.body as? [String: Any], let type = body["type"] as? String else { return }
        switch type {
        case "ready":
            ready = true
            if wanted { run("startMic()") }
        case "level":
            guard wanted, let rms = (body["rms"] as? NSNumber)?.doubleValue else { return }
            // Speech sits around -40 to -15 dBFS; map that span onto the bars.
            let decibels = 20 * log10(max(rms, 0.000_01))
            onLevel?(Float(min(max((decibels + 55) / 40, 0), 1)))
        case "pcm":
            guard let encoded = body["data"] as? String, let data = Data(base64Encoded: encoded),
                  let rate = (body["rate"] as? NSNumber)?.doubleValue, rate > 0 else { return }
            let samples = data.withUnsafeBytes { Array($0.bindMemory(to: Int16.self)) }
            onPcm?(samples, rate)
        case "ended":
            stopEnded()
        case "failed":
            guard wanted, body["reason"] as? String != "heartbeat" else { return }
            failed = true
            stop()
            onFailure?()
        default:
            break
        }
    }

    func webView(
        _ webView: WKWebView,
        requestMediaCapturePermissionFor origin: WKSecurityOrigin,
        initiatedByFrame frame: WKFrameInfo,
        type: WKMediaCaptureType,
        decisionHandler: @escaping (WKPermissionDecision) -> Void
    ) {
        decisionHandler(type == .microphone ? .grant : .deny)
    }

    func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
        ready = false
        webView.loadHTMLString(Self.page, baseURL: URL(string: "https://keyboard-mic.invalid/"))
    }

    private static let page = """
    <!doctype html><html><body><script>
    let wanted = false, starting = false, stream = null, context = null, timer = null, lastBeat = 0, tap = null;
    const post = (message) => window.webkit.messageHandlers.mic.postMessage(message);
    async function startMic() {
      wanted = true;
      lastBeat = Date.now();
      if (stream || starting) return;
      starting = true;
      try {
        if (navigator.audioSession) navigator.audioSession.type = 'play-and-record';
        const opened = await navigator.mediaDevices.getUserMedia({
          audio: { echoCancellation: true, noiseSuppression: true, autoGainControl: true },
        });
        starting = false;
        if (!wanted) { opened.getTracks().forEach((track) => track.stop()); return; }
        stream = opened;
        context = new AudioContext({ latencyHint: 'interactive' });
        const analyser = context.createAnalyser();
        analyser.fftSize = 1024;
        const source = context.createMediaStreamSource(stream);
        source.connect(analyser);
        // Raw audio for transcription. The processor only runs while connected to the output; muted.
        tap = context.createScriptProcessor(4096, 1, 1);
        const mute = context.createGain();
        mute.gain.value = 0;
        source.connect(tap);
        tap.connect(mute);
        mute.connect(context.destination);
        tap.onaudioprocess = (event) => {
          if (!wanted) return;
          const input = event.inputBuffer.getChannelData(0);
          const pcm = new Int16Array(input.length);
          for (let i = 0; i < input.length; i++) pcm[i] = Math.max(-1, Math.min(1, input[i])) * 0x7fff;
          const bytes = new Uint8Array(pcm.buffer);
          let binary = '';
          for (let i = 0; i < bytes.length; i += 0x8000) binary += String.fromCharCode.apply(null, bytes.subarray(i, i + 0x8000));
          post({ type: 'pcm', rate: context.sampleRate, data: btoa(binary) });
        };
        await context.resume();
        const samples = new Float32Array(analyser.fftSize);
        post({ type: 'started' });
        timer = setInterval(() => {
          if (Date.now() - lastBeat > 3000) { stopMic(); post({ type: 'failed', reason: 'heartbeat' }); return; }
          analyser.getFloatTimeDomainData(samples);
          let sum = 0;
          for (const value of samples) sum += value * value;
          post({ type: 'level', rms: Math.sqrt(sum / samples.length) });
        }, 40);
      } catch (error) {
        starting = false;
        stopMic();
        post({ type: 'failed', reason: String((error && error.name) || error) });
      }
    }
    function stopMic() {
      wanted = false;
      if (timer) clearInterval(timer);
      timer = null;
      if (tap) tap.onaudioprocess = null;
      tap = null;
      if (stream) stream.getTracks().forEach((track) => track.stop());
      stream = null;
      if (context) context.close();
      context = null;
      if (navigator.audioSession) navigator.audioSession.type = 'auto';
      post({ type: 'ended' });
    }
    function beat() { lastBeat = Date.now(); }
    post({ type: 'ready' });
    </script></body></html>
    """
}

/// Android's floating-button processing dots: each lights up in turn (1.2s cycle, 200ms stagger).
final class KeyboardProcessingDots: UIView {
    private let dotLayers = (0..<3).map { _ in CALayer() }

    override init(frame: CGRect) {
        super.init(frame: frame)
        isUserInteractionEnabled = false
        isAccessibilityElement = false
        dotLayers.forEach { layer.addSublayer($0) }
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) has not been implemented") }

    override func tintColorDidChange() {
        super.tintColorDidChange()
        dotLayers.forEach { $0.backgroundColor = tintColor.cgColor }
    }

    override func layoutSubviews() {
        super.layoutSubviews()
        // Android's 24-unit drawable: dots of radius 2 centred at x 4, 12 and 20.
        let unit = bounds.width / 24
        CATransaction.begin()
        CATransaction.setDisableActions(true)
        for (index, dot) in dotLayers.enumerated() {
            dot.bounds = CGRect(x: 0, y: 0, width: 4 * unit, height: 4 * unit)
            dot.cornerRadius = 2 * unit
            dot.position = CGPoint(x: CGFloat(4 + 8 * index) * unit, y: bounds.midY)
        }
        CATransaction.commit()
    }

    func start() {
        stop()
        // Reduce Motion: plain dots, no pulsing.
        let still = UIAccessibility.isReduceMotionEnabled
        let easing = CAMediaTimingFunction(controlPoints: 0.4, 0, 0.2, 1)
        let now = layer.convertTime(CACurrentMediaTime(), from: nil)
        for (index, dot) in dotLayers.enumerated() {
            dot.opacity = still ? 1 : 0.2
            dot.transform = still ? CATransform3DIdentity : CATransform3DMakeScale(0.85, 0.85, 1)
            guard !still else { continue }
            let opacity = CAKeyframeAnimation(keyPath: "opacity")
            opacity.values = [0.2, 1, 0.2, 0.2]
            let scale = CAKeyframeAnimation(keyPath: "transform.scale")
            scale.values = [0.85, 1, 0.85, 0.85]
            for animation in [opacity, scale] {
                animation.keyTimes = [0, 0.35, 0.7, 1]
                animation.timingFunctions = [easing, easing, CAMediaTimingFunction(name: .linear)]
            }
            let pulse = CAAnimationGroup()
            pulse.animations = [opacity, scale]
            pulse.duration = 1.2
            pulse.repeatCount = .infinity
            pulse.beginTime = now + 0.2 * Double(index)
            pulse.isRemovedOnCompletion = false
            dot.add(pulse, forKey: "pulse")
        }
    }

    func stop() {
        dotLayers.forEach { $0.removeAnimation(forKey: "pulse") }
    }
}
