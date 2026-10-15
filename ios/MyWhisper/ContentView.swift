import SwiftUI

/// v1 root: tab navigation across every merged screen (T1–T9), owned by an
/// `AppFlow` composition root. Record hosts the end-to-end dictation path
/// (RecordView → Dictation/AudioCapture → core → ResultView +
/// copy/share); history rows land via the core and HistoryView refreshes
/// on tab-appear.
struct ContentView: View {
    @StateObject private var flow = AppFlow()

    var body: some View {
        TabView(selection: $flow.selectedTab) {
            NavigationStack {
                recordTab
            }
            .tabItem { Label("Record", systemImage: "mic") }
            .tag(AppTab.record)
            NavigationStack {
                SetupView(
                    settings: flow.settings,
                    micGranted: flow.micGranted,
                    testPassed: flow.testPassed,
                    testRunning: flow.testRunning,
                    testError: flow.testError,
                    theme: flow.theme,
                    strings: SetupStrings.current(languageCode: languageCode),
                    onRequestMic: { flow.requestMic() },
                    onOpenSettings: { flow.openSystemSettings() },
                    onRunTest: { flow.runSetupTest() },
                    onThemeChange: { flow.applyTheme($0) }
                )
            }
            .tabItem { Label("Setup", systemImage: "checklist") }
            .tag(AppTab.setup)
            NavigationStack {
                SttSettingsView()
            }
            .tabItem { Label("Speech", systemImage: "waveform") }
            .tag(AppTab.speech)
            NavigationStack {
                CleanupSettingsView()
            }
            .tabItem { Label("Cleanup", systemImage: "wand.and.stars") }
            .tag(AppTab.cleanup)
            NavigationStack {
                DictionaryView()
            }
            .tabItem { Label("Dictionary", systemImage: "book") }
            .tag(AppTab.dictionary)
            NavigationStack {
                HistoryView()
            }
            .tabItem { Label("History", systemImage: "clock") }
            .tag(AppTab.history)
            NavigationStack {
                DevicesView()
            }
            .tabItem { Label("Devices", systemImage: "antenna.radiowaves.left.and.right") }
            .tag(AppTab.devices)
        }
        .preferredColorScheme(flow.theme.colorScheme)
        .sheet(isPresented: shareVisible) {
            if let text = flow.shareText {
                ShareSheet(items: [text])
            }
        }
    }

    private var recordTab: some View {
        List {
            if let error = flow.coreError {
                Section("Core") {
                    Text("Core error: \(error)")
                        .foregroundStyle(.red)
                        .textSelection(.enabled)
                    Button("Retry") { flow.openCore() }
                }
            }
            Section {
                RecordView(
                    state: flow.recordState,
                    toggled: flow.toggled,
                    micMode: flow.micMode,
                    levels: flow.levels,
                    onPressDown: { flow.pressDown() },
                    onPressUp: { flow.pressUp() },
                    onCancel: { flow.cancelRecording() }
                )
                .frame(maxWidth: .infinity, alignment: .center)
                .listRowBackground(Color.clear)
            }
            if let content = flow.result {
                Section("Result") {
                    ResultView(
                        content: content,
                        onCopy: { flow.copyResult() },
                        onShare: { flow.shareResult() },
                        onRetry: { flow.retryResult() },
                        strings: SttStrings.current(languageCode: languageCode)
                    )
                }
            }
        }
        .navigationTitle("MyWhisper")
    }

    private var shareVisible: Binding<Bool> {
        Binding(
            get: { flow.shareText != nil },
            set: { if !$0 { flow.shareText = nil } }
        )
    }

    /// Language code for the en/es string twins (nil = English).
    private var languageCode: String? {
        Locale.current.language.languageCode?.identifier
    }
}
