import SwiftUI

private struct WhisperListStyle: ViewModifier {
    @Environment(\.colorScheme) private var colorScheme
    @AppStorage(WhisperColors.pureBlackKey) private var pureBlack = false

    func body(content: Content) -> some View {
        content
            .listStyle(.insetGrouped)
            .listSectionSpacing(16)
            .contentMargins(.top, 12, for: .scrollContent)
            .scrollContentBackground(.hidden)
            .background(WhisperColors.background(colorScheme, pureBlack: pureBlack).ignoresSafeArea())
            .scrollDismissesKeyboard(.interactively)
    }
}

extension View {
    func whisperListStyle() -> some View {
        modifier(WhisperListStyle())
    }
}

struct WhisperSectionHeading: View {
    let title: String

    init(_ title: String) { self.title = title }

    var body: some View {
        Text(title)
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

/// One chain entry: tap to edit, switch to enable, and a menu for Test, order and Delete.
/// A test's result shows under the row, not inside the menu.
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
    /// Nil hides Test (Local rows); a blocked reason disables it.
    var testBlockedReason: LocalizedStringKey?
    var test: (() async -> ProviderTest.Outcome)?
    @ViewBuilder let extraActions: () -> ExtraActions
    @State private var testing = false
    @State private var outcome: ProviderTest.Outcome?

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(spacing: 12) {
                Button(action: onEdit) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text(name).foregroundStyle(.primary)
                        Text(summary)
                            .font(.subheadline)
                            .foregroundStyle(.secondary)
                            .lineLimit(1)
                            .truncationMode(.middle)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.borderless)
                .accessibilityHint("Edit provider")

                Toggle(name, isOn: $enabled)
                    .labelsHidden()
                    .accessibilityLabel("Enable \(name)")

                Menu {
                    if test != nil {
                        Button("Test", systemImage: "checkmark.seal", action: runTest)
                            .disabled(testing || testBlockedReason != nil)
                    }
                    Button("Edit", systemImage: "pencil", action: onEdit)
                    Button("Move up", systemImage: "arrow.up", action: onMoveUp).disabled(!canMoveUp)
                    Button("Move down", systemImage: "arrow.down", action: onMoveDown).disabled(!canMoveDown)
                    extraActions()
                    Button("Delete", systemImage: "trash", role: .destructive, action: onDelete)
                } label: {
                    Label("Actions for \(name)", systemImage: "ellipsis.circle")
                        .labelStyle(.iconOnly)
                        .imageScale(.large)
                        .frame(minWidth: 44, minHeight: 44)
                }
                .buttonStyle(.borderless)
            }
            if testing {
                Label { Text("Testing…") } icon: { ProgressView().controlSize(.small) }
                    .font(.caption)
                    .foregroundStyle(.secondary)
            } else if let outcome {
                Group {
                    if outcome.passed {
                        Label("Works · \(outcome.latencyMs) ms · \(outcome.excerpt)", systemImage: "checkmark.circle.fill")
                    } else {
                        Label("Failed · \(outcome.latencyMs) ms · \(outcome.excerpt)", systemImage: "xmark.octagon.fill")
                    }
                }
                .font(.caption)
                .foregroundStyle(outcome.passed ? Color.green : WhisperColors.error)
                .textSelection(.enabled)
            } else if let testBlockedReason, test != nil {
                Text(testBlockedReason).font(.caption).foregroundStyle(.secondary)
            }
        }
        .padding(.vertical, 2)
        .sensoryFeedback(.success, trigger: outcome?.passed == true ? outcome : nil)
        .sensoryFeedback(.error, trigger: outcome?.passed == false ? outcome : nil)
        .swipeActions {
            Button("Delete", systemImage: "trash", role: .destructive, action: onDelete)
        }
    }

    private func runTest() {
        guard let test else { return }
        testing = true
        outcome = nil
        Task {
            let result = await test()
            outcome = result
            testing = false
        }
    }
}
