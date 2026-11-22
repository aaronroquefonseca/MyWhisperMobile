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
    private var selectedForeground: Color { colorScheme == .dark ? .black : .white }

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
                    tabLabel(for: tab, selected: selected == tab)
                        .animation(.spring(response: 0.32, dampingFraction: 0.8), value: selected)
                }
                .accessibilityLabel(label(for: tab))
                .accessibilityAddTraits(selected == tab ? [.isButton, .isSelected] : .isButton)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .whisperGlass(cornerRadius: 100)
        .shadow(color: .black.opacity(0.25), radius: 18, y: 8)
        .padding(.horizontal, 20)
    }

    @ViewBuilder
    private func tabLabel(for tab: AppTab, selected: Bool) -> some View {
        if selected {
            Label(label(for: tab), systemImage: iconName(for: tab))
                .labelStyle(.titleAndIcon)
                .font(.system(size: 17, weight: .semibold))
                .foregroundStyle(selectedForeground)
                .padding(.vertical, 10)
                .padding(.horizontal, 16)
                .background {
                    Capsule(style: .continuous)
                        .fill(capsuleColor)
                        .shadow(color: capsuleColor.opacity(0.45), radius: 12)
                }
        } else {
            Label(label(for: tab), systemImage: iconName(for: tab))
                .labelStyle(.iconOnly)
                .font(.system(size: 19, weight: .regular))
                .foregroundStyle(Color.primary.opacity(0.55))
                .padding(.vertical, 10)
                .padding(.horizontal, 16)
        }
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
