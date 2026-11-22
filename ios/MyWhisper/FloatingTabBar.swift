import SwiftUI
import UIKit

/// Floating glass tab bar (Liquid Glass idiom, materials-backed so it
/// compiles on the current SDK): detached pill, frosted material, teal
/// active capsule. Drives the same `AppTab` selection the `TabView` owns.
struct FloatingTabBar: View {
    @Binding var selected: AppTab
    var strings: TabStrings
    var languageCode: String?
    @Environment(\.colorScheme) private var colorScheme

    private var capsuleColor: Color { WhisperColors.primary(colorScheme) }

    private var tabs: [AppTab] { [.record, .speech, .history, .setup] }

    private func iconName(for tab: AppTab) -> String {
        switch tab {
        case .record: return "mic.fill"
        case .speech: return "waveform"
        case .history: return "clock.fill"
        case .setup: return "ellipsis.circle"
        case .cleanup: return "wand.and.stars"
        case .dictionary: return "book.fill"
        case .devices: return "antenna.radiowaves.left.and.right"
        }
    }

    var body: some View {
        HStack(spacing: 4) {
            ForEach(tabs, id: \.self) { tab in
                Button {
                    UIImpactFeedbackGenerator(style: .light).impactOccurred()
                    withAnimation(.spring(response: 0.32, dampingFraction: 0.8)) {
                        selected = tab
                    }
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: iconName(for: tab))
                            .font(.system(size: 19, weight: .semibold))
                            .foregroundStyle(selected == tab ? .white : Color.primary.opacity(0.55))
                        Text(label(for: tab))
                            .font(.subheadline).bold()
                            .foregroundStyle(.white)
                            .opacity(selected == tab ? 1 : 0)
                    }
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, 10)
                    .padding(.horizontal, 16)
                    .background {
                        if selected == tab {
                            Capsule(style: .continuous)
                                .fill(capsuleColor)
                                .shadow(color: capsuleColor.opacity(0.45), radius: 12)
                        }
                    }
                    .animation(.spring(response: 0.32, dampingFraction: 0.8), value: selected)
                }
                .accessibilityLabel(label(for: tab))
                .accessibilityAddTraits(selected == tab ? [.isButton, .isSelected] : .isButton)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(.ultraThinMaterial, in: Capsule(style: .continuous))
        .overlay(
            Capsule(style: .continuous)
                .stroke(.white.opacity(0.22), lineWidth: 1)
        )
        .shadow(color: .black.opacity(0.25), radius: 18, y: 8)
        .padding(.horizontal, 20)
    }

    private func label(for tab: AppTab) -> String {
        switch tab {
        case .record: return strings.record
        case .speech: return strings.speech
        case .history: return strings.history
        case .setup: return languageCode == "es" ? "Más" : "More"
        case .cleanup: return strings.cleanup
        case .dictionary: return strings.dictionary
        case .devices: return strings.devices
        }
    }
}
