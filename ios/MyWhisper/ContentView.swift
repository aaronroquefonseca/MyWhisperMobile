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
            ForEach(AppTab.allCases, id: \.self) { tab in
                NavigationStack {
                    tabContent(for: tab)
                }
                .tabItem { Label(tab.title(using: tabStrings), systemImage: tab.systemImage) }
                .tag(tab)
            }
        }
        .preferredColorScheme(flow.theme.colorScheme)
        .sheet(isPresented: shareVisible) {
            if let text = flow.shareText {
                ShareSheet(items: [text])
            }
        }
    }

    /// Exhaustive: adding an `AppTab` case without a screen is a compile
    /// error, so the tab root can never silently drop a merged screen.
    @ViewBuilder
    private func tabContent(for tab: AppTab) -> some View {
        switch tab {
        case .record:
            recordTab
        case .setup:
            SetupView(
                settings: flow.settings,
                micGranted: flow.micGranted,
                testPassed: flow.testPassed,
                // Reserved for a future headless test utterance (v1 routes
                // to the Record tab instead); SetupView's branch stays off.
                testRunning: false,
                testError: flow.testError,
                theme: flow.theme,
                strings: SetupStrings.current(languageCode: languageCode),
                onRequestMic: { flow.requestMic() },
                onOpenSettings: { flow.openSystemSettings() },
                onRunTest: { flow.runSetupTest() },
                onThemeChange: { flow.applyTheme($0) }
            )
        case .speech:
            SttSettingsView()
        case .cleanup:
            CleanupSettingsView()
        case .dictionary:
            DictionaryView()
        case .history:
            HistoryView()
        case .devices:
            DevicesView()
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

    private var tabStrings: TabStrings {
        TabStrings.current(languageCode: languageCode)
    }
}
