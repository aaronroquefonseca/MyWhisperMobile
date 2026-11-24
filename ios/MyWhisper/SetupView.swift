import AVFoundation
import SwiftUI

/// Fresh-install checklist state. Pure logic (no mic, no core) so XCTest
/// covers the state machine without hardware.
struct SetupChecklist: Equatable {
    var micGranted: Bool
    var serverConfigured: Bool
    var testPassed: Bool

    enum NextStep: Equatable {
        case mic
        case server
        case test
        case done
    }

    var doneCount: Int {
        [micGranted, serverConfigured, testPassed].filter { $0 }.count
    }

    var isReady: Bool {
        micGranted && serverConfigured && testPassed
    }

    var nextStep: NextStep {
        if !micGranted { return .mic }
        if !serverConfigured { return .server }
        if !testPassed { return .test }
        return .done
    }
}

/// True when at least one enabled, non-Local STT entry has a model
/// (SelfHosted additionally needs a URL; Cloud allows blank URL = vendor
/// default). Exact port of Android `MainActivity.isServerConfigured` over
/// the decoded settings dict (`CoreBridge.getSettings()` shape).
/// Divergence note (PR #54): Android's setup row now gates on
/// `isSpeechConfigured(...)`, which additionally counts a verified Local
/// pack. On-device Local is deferred on iOS, so this port intentionally
/// keeps the `isServerConfigured` semantics (Local skipped).
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

func setupChecklist(micGranted: Bool, settings: [String: Any]?, testPassed: Bool) -> SetupChecklist {
    SetupChecklist(micGranted: micGranted, serverConfigured: isServerConfigured(settings: settings), testPassed: testPassed)
}

/// Microphone, provider readiness, and the foreground dictation test.
struct SetupView: View {
    var settings: [String: Any]?
    var micGranted: Bool
    var testPassed: Bool
    var testError: String?
    var strings: SetupStrings
    var onRequestMic: () -> Void = {}
    var onOpenSettings: () -> Void = {}
    var onOpenProviders: () -> Void = {}
    var onRunTest: () -> Void = {}
    var onMicPriorityChange: ([String]) -> Void = { _ in }

    @State private var showRationale = false
    @State private var showDenied = false

    private var checklist: SetupChecklist {
        setupChecklist(micGranted: micGranted, settings: settings, testPassed: testPassed)
    }

    private func micName(_ kind: String) -> String {
        switch kind {
        case MicRoute.wired: return strings.micWired
        case MicRoute.bluetooth: return strings.micBluetooth
        default: return strings.micBuiltIn
        }
    }

    private func moving(_ order: [String], from: Int, to: Int) -> [String] {
        var order = order
        order.insert(order.remove(at: from), at: to)
        return order
    }

    var body: some View {
        List {
            Section(strings.progressTitle(done: checklist.doneCount, total: 3)) {
                if checklist.isReady {
                    Text(strings.ready)
                }
                checklistRow(done: checklist.micGranted, label: strings.doneMic)
                checklistRow(done: checklist.serverConfigured, label: strings.doneServer)
                checklistRow(done: checklist.testPassed, label: strings.doneTest)
            }
            .whisperRowSurface()
            Section(strings.stepMic) {
                Text(micGranted ? strings.setupMicYes : strings.setupMicNo)
                Button(strings.setupMicGrant) {
                    if micGranted { return }
                    if AVAudioSession.sharedInstance().recordPermission == .denied {
                        showDenied = true
                    } else {
                        showRationale = true
                    }
                }
                .disabled(micGranted)
            }
            .whisperRowSurface()
            Section(strings.stepServer) {
                if checklist.serverConfigured {
                    Text(strings.doneServer)
                } else {
                    Button(TabStrings.current(languageCode: Locale.current.language.languageCode?.identifier).providers,
                           action: onOpenProviders)
                }
            }
            .whisperRowSurface()
            Section(strings.stepTest) {
                Text(strings.testHint)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                Button(strings.testRun) { onRunTest() }
                    .disabled(!checklist.micGranted || !checklist.serverConfigured)
                if testPassed {
                    Text(strings.testPassed)
                } else if let error = testError {
                    Text(strings.testFailed + " " + error)
                        .font(.subheadline)
                        .foregroundStyle(.red)
                }
            }
            .whisperRowSurface()
            Section {
                let order = MicRoute.priority(from: settings)
                ForEach(Array(order.enumerated()), id: \.element) { index, kind in
                    let name = micName(kind)
                    Text("\(index + 1). \(name)")
                        .accessibilityAction(named: String(format: strings.micMoveUp, name)) {
                            if index > 0 { onMicPriorityChange(moving(order, from: index, to: index - 1)) }
                        }
                        .accessibilityAction(named: String(format: strings.micMoveDown, name)) {
                            if index < order.count - 1 { onMicPriorityChange(moving(order, from: index, to: index + 1)) }
                        }
                }
                .onMove { source, destination in
                    var next = order
                    next.move(fromOffsets: source, toOffset: destination)
                    onMicPriorityChange(next)
                }
                // Always show reorder handles, like Android's drag handles.
                .environment(\.editMode, .constant(.active))
            } header: {
                WhisperSectionHeading(strings.micPriority)
            } footer: {
                Text(strings.micPrioritySub)
            }
            .whisperRowSurface()
        }
        .whisperListStyle()
        .navigationTitle(OnboardingStrings.current(languageCode: Locale.current.language.languageCode?.identifier).dictationSetup)
        .navigationBarTitleDisplayMode(.inline)
        .alert(strings.stepMic, isPresented: $showRationale) {
            Button(strings.setupMicGrant) {
                showRationale = false
                onRequestMic()
            }
            Button(strings.actionCancel, role: .cancel) {}
        } message: {
            Text(strings.setupMicNo)
        }
        .alert(strings.stepMic, isPresented: $showDenied) {
            Button(strings.actionChange) {
                showDenied = false
                onOpenSettings()
            }
            Button(strings.actionCancel, role: .cancel) {}
        } message: {
            Text(strings.setupMicDenied)
        }
    }

    private func checklistRow(done: Bool, label: String) -> some View {
        Text((done ? "✓ " : "○ ") + label)
    }
}

/// Production mic-permission helper: reports the current record permission
/// and requests it when undetermined. Calls back on the main queue.
enum MicPermission {
    static var isDenied: Bool {
        AVAudioSession.sharedInstance().recordPermission == .denied
    }

    static func current() -> Bool {
        AVAudioSession.sharedInstance().recordPermission == .granted
    }

    static func request(completion: @escaping (Bool) -> Void) {
        switch AVAudioSession.sharedInstance().recordPermission {
        case .granted:
            completion(true)
        case .denied:
            completion(false)
        case .undetermined:
            AVAudioSession.sharedInstance().requestRecordPermission { granted in
                DispatchQueue.main.async { completion(granted) }
            }
        @unknown default:
            completion(false)
        }
    }
}

#Preview {
    NavigationStack {
        SetupView(
            settings: ["stt": ["entries": [["kind": "Cloud", "model": "whisper-1"]]]],
            micGranted: false,
            testPassed: false,
            testError: nil,
            strings: .english
        )
    }
}
