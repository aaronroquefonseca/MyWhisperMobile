import SwiftUI

/// In-app result screen: the iOS delivery surface (there is no
/// direct-insert — see `Delivery.swift`). Shows the transcript with
/// Copy/Share actions, a working state, an empty state for silence, and
/// an error state with Retry.
struct ResultView: View {
    enum Content: Equatable {
        case working
        case result(text: String, engine: String)
        case empty
        case failure(SttFailure)
    }

    let content: Content
    let onCopy: () -> Void
    let onShare: () -> Void
    let onRetry: () -> Void
    /// Display strings (default English; hosts pass
    /// `SttStrings.current(languageCode:)` for Spanish).
    let strings: SttStrings

    /// Explicit memberwise init: a same-type static default (`.english`
    /// above) keeps older Swift toolchains from synthesizing the
    /// memberwise initializer, so spell it out.
    init(
        content: Content,
        onCopy: @escaping () -> Void,
        onShare: @escaping () -> Void,
        onRetry: @escaping () -> Void,
        strings: SttStrings = .english
    ) {
        self.content = content
        self.onCopy = onCopy
        self.onShare = onShare
        self.onRetry = onRetry
        self.strings = strings
    }

    /// Render mapping, pure and unit-tested: a routine-drop failure
    /// (genuine stop-with-silence) renders the empty state — never red.
    /// Only real failures reach the error UI.
    static func displayContent(for content: Content) -> Content {
        if case .failure(let failure) = content, failure.category == .routineDrop {
            return .empty
        }
        return content
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            switch Self.displayContent(for: content) {
            case .working:
                ProgressView(strings.transcribing)
                    .frame(maxWidth: .infinity, alignment: .center)
            case .result(let text, let engine):
                Text(text)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                if engine.isEmpty == false {
                    Text(engine)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                HStack {
                    Button(strings.copy) { onCopy() }
                        .buttonStyle(.borderedProminent)
                    Button(strings.share) { onShare() }
                        .buttonStyle(.bordered)
                }
            case .empty:
                Text(strings.emptyHint)
                    .foregroundStyle(.secondary)
                    .frame(maxWidth: .infinity, alignment: .center)
                Button(strings.tryAgain) { onRetry() }
                    .buttonStyle(.bordered)
                    .frame(maxWidth: .infinity, alignment: .center)
            case .failure(let failure):
                Text(failure.userMessage)
                    .foregroundStyle(.red)
                    .textSelection(.enabled)
                    .frame(maxWidth: .infinity, alignment: .leading)
                Button(strings.retry) { onRetry() }
                    .buttonStyle(.borderedProminent)
            }
        }
        .padding()
    }
}

/// UIKit share-sheet bridge for `ResultView` hosts.
struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]

    func makeUIViewController(context: Context) -> UIActivityViewController {
        UIActivityViewController(activityItems: items, applicationActivities: nil)
    }

    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}
