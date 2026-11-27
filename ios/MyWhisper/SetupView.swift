import AVFoundation
import SwiftUI
import UIKit

/// True when at least one enabled, non-Local STT entry has a model
/// (SelfHosted additionally needs a URL; Cloud allows blank URL = vendor
/// default). Port of Android `MainActivity.isServerConfigured` over the
/// decoded settings dict; on-device Local is not offered on iOS.
func isServerConfigured(settings: [String: Any]?) -> Bool {
    guard let entries = (settings?["stt"] as? [String: Any])?["entries"] as? [[String: Any]] else {
        return false
    }
    for entry in entries {
        if entry["enabled"] as? Bool == false { continue }
        let kind = entry["kind"] as? String ?? ""
        if kind == "Local" { continue }
        let model = (entry["model"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
        if model.isEmpty { continue }
        if kind == "SelfHosted" {
            let url = (entry["url"] as? String ?? "").trimmingCharacters(in: .whitespacesAndNewlines)
            if url.isEmpty { continue }
        }
        return true
    }
    return false
}

/// Android's setup checklist, for the keyboard: what the app can check from outside it.
struct SetupState: Equatable {
    var keyboardAdded: Bool
    var fullAccess: Bool
    var micGranted: Bool
    var speechReady: Bool

    /// Onboarding stops at the setup page until these are done, like Android's permissions.
    var requiredDone: Bool { keyboardAdded && micGranted }
    var doneCount: Int { [keyboardAdded, fullAccess, micGranted, speechReady].filter { $0 }.count }
    var isComplete: Bool { doneCount == 4 }

    static func current(settings: [String: Any]?) -> SetupState {
        SetupState(
            keyboardAdded: keyboardAdded(),
            fullAccess: KeyboardPreferences.fullAccessSeen,
            micGranted: MicPermission.current(),
            speechReady: isServerConfigured(settings: settings)
        )
    }

    /// iOS lists enabled keyboards but has no public field for their bundle; `identifier` is what
    /// every keyboard app checks. If a future iOS drops it, count the step as done rather than
    /// trap onboarding, which waits for it.
    private static func keyboardAdded() -> Bool {
        guard let app = Bundle.main.bundleIdentifier else { return true }
        let identifiers = UITextInputMode.activeInputModes.compactMap { mode -> String? in
            guard mode.responds(to: NSSelectorFromString("identifier")) else { return nil }
            return mode.value(forKey: "identifier") as? String
        }
        return identifiers.isEmpty || identifiers.contains { $0.hasPrefix(app + ".") }
    }
}

/// The setup steps, one at a time, shared by onboarding and Activity's "Finish setup".
struct SetupChecklistView: View {
    @Environment(\.scenePhase) private var scenePhase
    @ObservedObject var flow: AppFlow
    /// Speech providers live in Providers, outside onboarding (like Android).
    var includesSpeech = true
    var onOpenProviders: () -> Void = {}
    @Binding var state: SetupState

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            VStack(alignment: .leading, spacing: 10) {
                row(state.keyboardAdded, "Add the MyWhisper keyboard")
                row(state.fullAccess, "Allow Full Access")
                row(state.micGranted, "Microphone access")
                if includesSpeech { row(state.speechReady, "Speech provider") }
            }
            .padding(16)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(.fill.quaternary, in: RoundedRectangle(cornerRadius: 16))

            VStack(alignment: .leading, spacing: 12) {
                if !state.keyboardAdded {
                    Text("In Settings, tap Keyboards and turn on MyWhisper Keyboard.")
                    Button("Open Settings", action: flow.openSystemSettings).buttonStyle(.borderedProminent)
                } else if !state.micGranted {
                    Text("Microphone access lets the keyboard record your voice while you hold its mic button.")
                    Button("Allow microphone", action: flow.requestMic).buttonStyle(.borderedProminent)
                } else if !state.fullAccess {
                    Text("Turn on Allow Full Access in Settings › Keyboards so the keyboard can use the microphone and your speech provider. Then open the MyWhisper keyboard once.")
                    Button("Open Settings", action: flow.openSystemSettings).buttonStyle(.borderedProminent)
                } else if includesSpeech && !state.speechReady {
                    Text("Choose a speech provider: your own server or a cloud service.")
                    Button("Set up speech provider", action: onOpenProviders).buttonStyle(.borderedProminent)
                } else {
                    Label("Setup complete", systemImage: "checkmark.circle.fill").foregroundStyle(.tint)
                }
            }
            .buttonBorderShape(.capsule)
        }
        .onAppear(perform: refresh)
        .onChange(of: scenePhase) { _, phase in if phase == .active { refresh() } }
        .onChange(of: flow.micGranted) { _, _ in refresh() }
    }

    private func refresh() {
        flow.refreshMicPermission()
        state = .current(settings: flow.settings)
    }

    private func row(_ done: Bool, _ title: LocalizedStringKey) -> some View {
        Label {
            Text(title).foregroundStyle(done ? .secondary : .primary)
        } icon: {
            Image(systemName: done ? "checkmark.circle.fill" : "circle")
                .foregroundStyle(done ? AnyShapeStyle(.tint) : AnyShapeStyle(.secondary))
        }
        .accessibilityValue(done ? Text("Done") : Text("Not done"))
    }
}

/// Activity's "Finish setup": the same steps outside onboarding.
struct SetupScreen: View {
    @ObservedObject var flow: AppFlow
    let onOpenProviders: () -> Void
    @State private var state = SetupState(keyboardAdded: false, fullAccess: false, micGranted: false, speechReady: false)

    var body: some View {
        ScrollView {
            SetupChecklistView(flow: flow, onOpenProviders: onOpenProviders, state: $state)
                .padding()
        }
        .navigationTitle("Finish setup")
        .navigationBarTitleDisplayMode(.inline)
    }
}

/// Production mic-permission helper: reports the current record permission
/// and requests it when undetermined. Calls back on the main queue.
enum MicPermission {
    static var isDenied: Bool {
        AVAudioApplication.shared.recordPermission == .denied
    }

    static func current() -> Bool {
        AVAudioApplication.shared.recordPermission == .granted
    }

    static func request(completion: @escaping (Bool) -> Void) {
        AVAudioApplication.requestRecordPermission { granted in
            DispatchQueue.main.async { completion(granted) }
        }
    }
}
