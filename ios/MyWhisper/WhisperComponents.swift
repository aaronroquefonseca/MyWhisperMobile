import SwiftUI

private struct WhisperListStyle: ViewModifier {
    @Environment(\.colorScheme) private var colorScheme

    func body(content: Content) -> some View {
        content
            .listStyle(.insetGrouped)
            .listSectionSpacing(16)
            .scrollContentBackground(.hidden)
            .background(WhisperColors.background(colorScheme).ignoresSafeArea())
            .scrollDismissesKeyboard(.interactively)
    }
}

private struct WhisperRowSurface: ViewModifier {
    @Environment(\.colorScheme) private var colorScheme

    func body(content: Content) -> some View {
        content
            .listRowBackground(WhisperColors.surface(colorScheme))
    }
}

extension View {
    func whisperListStyle() -> some View {
        modifier(WhisperListStyle())
    }

    func whisperRowSurface() -> some View {
        modifier(WhisperRowSurface())
    }
}

struct WhisperSectionHeading: View {
    let title: String

    init(_ title: String) { self.title = title }

    var body: some View {
        Text(title)
            .font(.subheadline.weight(.semibold))
            .textCase(nil)
            .accessibilityAddTraits(.isHeader)
    }
}

struct WhisperDestinationLabel: View {
    let title: String
    let summary: String
    let symbol: String

    var body: some View {
        Label {
            VStack(alignment: .leading, spacing: 3) {
                Text(title).font(.body.weight(.medium)).foregroundStyle(.primary)
                Text(summary).font(.subheadline).foregroundStyle(.secondary)
            }
        } icon: {
            Image(systemName: symbol)
                .foregroundStyle(.tint)
                .accessibilityHidden(true)
        }
        .padding(.vertical, 4)
        .frame(maxWidth: .infinity, alignment: .leading)
        .contentShape(Rectangle())
    }
}

struct WhisperPager<First: View, Second: View>: View {
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Binding var showSecond: Bool
    let firstTitle: String
    let secondTitle: String
    @ViewBuilder let first: () -> First
    @ViewBuilder let second: () -> Second

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 0) {
                tab(firstTitle, second: false)
                tab(secondTitle, second: true)
            }
            .padding(.horizontal, 16)
            Divider()
            TabView(selection: $showSecond) {
                first().tag(false)
                second().tag(true)
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
        }
        .background(WhisperColors.background(colorScheme).ignoresSafeArea())
    }

    private func tab(_ title: String, second: Bool) -> some View {
        let selected = second == showSecond
        return Button {
            withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.2)) {
                showSecond = second
            }
        } label: {
            Text(title)
                .font(.subheadline.weight(selected ? .semibold : .medium))
                .foregroundStyle(selected ? WhisperColors.primary(colorScheme) : WhisperColors.onSurfaceVariant(colorScheme))
                .multilineTextAlignment(.center)
                .frame(maxWidth: .infinity, minHeight: 48)
                .padding(.horizontal, 4)
                .contentShape(Rectangle())
                .overlay(alignment: .bottom) {
                    if selected {
                        Capsule().fill(WhisperColors.primary(colorScheme))
                            .frame(width: 32, height: 3)
                    }
                }
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selected ? .isSelected : [])
    }
}

struct WhisperAddLabel: View {
    @Environment(\.colorScheme) private var colorScheme
    let title: String

    var body: some View {
        Label(title, systemImage: "plus")
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(WhisperColors.primary(colorScheme))
            .frame(maxWidth: .infinity, minHeight: 44)
            .background(WhisperColors.primaryContainer(colorScheme), in: Capsule())
            .contentShape(Capsule())
    }
}

struct WhisperSearchField: View {
    @Binding var text: String
    let prompt: String

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass").foregroundStyle(.secondary).accessibilityHidden(true)
            TextField(prompt, text: $text)
                .submitLabel(.search)
                .autocorrectionDisabled()
            if !text.isEmpty {
                Button {
                    text = ""
                } label: {
                    Label("Clear search", systemImage: "xmark.circle.fill").labelStyle(.iconOnly)
                }
                .buttonStyle(.borderless)
                .foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 4)
    }
}

struct WhisperProviderRow<ExtraActions: View>: View {
    let name: String
    let summary: String
    @Binding var enabled: Bool
    let canMoveUp: Bool
    let canMoveDown: Bool
    let onMoveUp: () -> Void
    let onMoveDown: () -> Void
    let onEdit: () -> Void
    let onDelete: () -> Void
    @ViewBuilder let extraActions: () -> ExtraActions

    var body: some View {
        HStack(spacing: 8) {
            Button(action: onEdit) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(name).font(.body.weight(.medium)).foregroundStyle(.primary)
                    Text(summary).font(.subheadline).foregroundStyle(.secondary)
                }
                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                .contentShape(Rectangle())
            }
            .buttonStyle(.borderless)
            .accessibilityHint("Edit provider")

            Toggle(name, isOn: $enabled)
                .labelsHidden()
                .accessibilityLabel("Enable \(name)")

            Menu {
                Button("Edit", systemImage: "pencil", action: onEdit)
                Button("Move up", systemImage: "arrow.up", action: onMoveUp).disabled(!canMoveUp)
                Button("Move down", systemImage: "arrow.down", action: onMoveDown).disabled(!canMoveDown)
                extraActions()
                Button("Delete", systemImage: "trash", role: .destructive, action: onDelete)
            } label: {
                Label("Actions for \(name)", systemImage: "ellipsis")
                    .labelStyle(.iconOnly)
                    .foregroundStyle(.secondary)
                    .frame(minWidth: 44, minHeight: 44)
            }
            .buttonStyle(.borderless)
        }
        .padding(.vertical, 4)
    }
}
