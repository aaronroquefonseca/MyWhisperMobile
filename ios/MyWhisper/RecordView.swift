import SwiftUI

/// Live mic-level history: port of Android `BarsView` (11 bars, 3pt
/// wide, 3pt gap, 0.82 decay, 4pt resting height). Push 0..1 peaks
/// from `Dictation.onLevel` on the main thread — `LevelMeter` redraws
/// from `bars` on every publish.
final class LevelStore: ObservableObject {
    /// Bar count, decay, and resting height match `BarsView`.
    static let barCount = 11
    static let decay: Float = 0.82

    @Published private(set) var bars = [Float](repeating: 0, count: barCount)

    /// Push a 0..1 peak; older bars decay. Main thread only.
    func pushLevel(_ peak: Float) {
        let clamped = min(max(peak, 0), 1)
        var next = bars
        var i = next.count - 1
        while i >= 1 {
            next[i] = next[i - 1] * Self.decay
            i -= 1
        }
        next[0] = max(clamped, next[0] * Self.decay)
        bars = next
    }

    func clear() {
        bars = [Float](repeating: 0, count: Self.barCount)
    }
}

/// Symmetric bar visualizer driven by a `LevelStore`.
struct LevelMeter: View {
    @ObservedObject var levels: LevelStore

    static let barWidth: CGFloat = 3
    static let barGap: CGFloat = 3
    static let restingHeight: CGFloat = 4
    static let maxExtraHeight: CGFloat = 28

    var body: some View {
        HStack(spacing: Self.barGap) {
            ForEach(0..<LevelStore.barCount, id: \.self) { index in
                RoundedRectangle(cornerRadius: Self.barWidth / 2)
                    .fill(Color.primary)
                    .frame(
                        width: Self.barWidth,
                        height: Self.restingHeight + CGFloat(levels.bars[index]) * Self.maxExtraHeight
                    )
            }
        }
        .accessibilityHidden(true)
    }
}

/// Record button + level meter + cancel. Pure view over
/// `Dictation.State`: the owner forwards press/release/cancel and
/// publishes levels — this view never touches `Dictation` or the
/// core, so it previews without a mic.
///
/// Gestures mirror the IME: press-and-hold talks (Push), tap toggles
/// (Tap/Smart), a second press finishes, and sliding left past the
/// threshold cancels instead of committing.
struct RecordView: View {
    /// Horizontal slide distance (pt) that turns a release into cancel.
    static let cancelSlideThreshold: CGFloat = -60

    var state: Dictation.State
    var toggled: Bool
    var micMode: String
    @ObservedObject var levels: LevelStore
    var onPressDown: () -> Void = {}
    var onPressUp: () -> Void = {}
    var onCancel: () -> Void = {}

    @State private var pressed = false

    var body: some View {
        VStack(spacing: 12) {
            Text(hint)
                .font(.subheadline)
                .foregroundStyle(.secondary)
                .accessibilityLabel(hint)
            ZStack {
                Circle()
                    .fill(buttonColor)
                    .frame(width: buttonDiameter, height: buttonDiameter)
                if state == .working {
                    ProgressView()
                        .tint(.white)
                        .scaleEffect(1.4)
                        .accessibilityLabel("Transcribing")
                } else {
                    Image(systemName: state == .recording ? "mic.fill" : "mic")
                        .font(.system(size: 30))
                        .foregroundStyle(.white)
                }
            }
            .gesture(pressGesture)
            .accessibilityAddTraits(.isButton)
            .accessibilityLabel(micVoiceOverLabel)
            .accessibilityHint(micVoiceOverHint)
            LevelMeter(levels: levels)
                .frame(height: 36)
                .opacity(state == .recording ? 1 : 0.25)
            if state != .idle {
                Button("Cancel", action: { onCancel() })
                    .buttonStyle(.bordered)
                    .accessibilityLabel("Cancel dictation")
                    .accessibilityHint("Discards this recording without transcribing")
            }
        }
    }

    // MARK: - Gesture

    private var pressGesture: some Gesture {
        DragGesture(minimumDistance: 0)
            .onChanged { _ in
                if !pressed {
                    pressed = true
                    onPressDown()
                }
            }
            .onEnded { value in
                pressed = false
                if value.translation.width < Self.cancelSlideThreshold {
                    onCancel()
                } else {
                    onPressUp()
                }
            }
    }

    // MARK: - Visuals (IME micVisualFor parity)

    /// IDLE purple 0x6750A4, RECORDING red 0xB3261E, WORKING amber
    /// 0x7C4D00 — same hex as `MyWhisperIME.micVisualFor`.
    private var buttonColor: Color {
        switch state {
        case .idle: return Self.color(0x6750A4)
        case .recording: return Self.color(0xB3261E)
        case .working: return Self.color(0x7C4D00)
        }
    }

    /// RECORDING scales up 1.12, WORKING shrinks to 0.94 (IME parity).
    private var buttonDiameter: CGFloat {
        switch state {
        case .idle: return 76
        case .recording: return 76 * 1.12
        case .working: return 76 * 0.94
        }
    }

    private static func color(_ hex: UInt32) -> Color {
        Color(
            red: Double((hex >> 16) & 0xFF) / 255,
            green: Double((hex >> 8) & 0xFF) / 255,
            blue: Double(hex & 0xFF) / 255
        )
    }

    // MARK: - Copy

    private var hint: String {
        switch state {
        case .working:
            return "Working…"
        case .recording where toggled:
            return "Tap again to finish"
        case .recording:
            return "Listening… slide left to cancel"
        case .idle:
            switch micMode {
            case "Push": return "Hold to talk"
            case "Tap": return "Tap to start"
            default: return "Tap or hold to talk"
            }
        }
    }

    private var micVoiceOverLabel: String {
        switch state {
        case .idle: return "Microphone"
        case .recording: return toggled ? "Microphone, recording, toggled on" : "Microphone, listening"
        case .working: return "Microphone, working"
        }
    }

    private var micVoiceOverHint: String {
        switch micMode {
        case "Push": return "Press and hold to dictate, release to transcribe"
        case "Tap": return "Tap to start recording, tap again to transcribe"
        default: return "Tap to toggle recording, or hold to talk"
        }
    }
}

#Preview {
    RecordView(state: .recording, toggled: false, micMode: "Smart", levels: LevelStore())
}
