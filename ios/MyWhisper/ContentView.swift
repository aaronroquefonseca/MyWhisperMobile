import SwiftUI

struct ContentView: View {
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.colorScheme) private var colorScheme
    @StateObject private var flow = AppFlow()
    @StateObject private var speechStore = SttSettingsStore()
    @StateObject private var cleanupStore = CleanupSettingsStore()
    @State private var selectedTab = AppTab.activity
    @State private var voiceCleanup = false
    @State private var keyboardSettingsOpen = false
    @State private var showOnboarding = {
        #if DEBUG
        if CommandLine.arguments.contains("-mwSkipOnboarding") { return false }
        #endif
        return !UserDefaults.standard.bool(forKey: "mw.onboarded.v1")
    }()

    var body: some View {
        Group {
            if showOnboarding {
                OnboardingView(strings: keyboardStrings, initialPage: initialOnboardingPage) {
                    UserDefaults.standard.set(true, forKey: "mw.onboarded.v1")
                    showOnboarding = false
                }
            } else {
                mainView
            }
        }
        .preferredColorScheme(flow.theme.colorScheme)
        .onChange(of: scenePhase) { _, phase in
            if phase == .active { flow.refreshMicPermission() }
        }
        // The keyboard can't record; its mic button opens this link to start the shared session.
        .onOpenURL(perform: openLink)
        .sheet(isPresented: $keyboardSettingsOpen) {
            NavigationStack {
                KeyboardSetupView(strings: keyboardStrings, flow: flow)
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button(keyboardStrings.done) { keyboardSettingsOpen = false }
                        }
                    }
            }
        }
    }

    private var mainView: some View {
        TabView(selection: $selectedTab) {
            ForEach(AppTab.allCases, id: \.self) { tab in
                NavigationStack {
                    tabRoot(tab)
                        .navigationTitle(tab.title(using: tabStrings))
                        .navigationBarTitleDisplayMode(.inline)
                        .toolbarBackground(WhisperColors.background(colorScheme), for: .navigationBar)
                        .toolbarBackground(.visible, for: .navigationBar)
                        .whisperTabBarBackground(colorScheme)
                }
                .tabItem { Label(tab.title(using: tabStrings), systemImage: tab.systemImage) }
                .tag(tab)
            }
        }
        .tint(WhisperColors.primary(colorScheme))
        .onChange(of: selectedTab) { _, tab in
            if tab == .activity || tab == .settings { flow.reloadSettings() }
        }
        .onAppear(perform: selectDebugTab)
        .sheet(isPresented: $flow.showRecorder) {
            NavigationStack {
                recordView
                    .navigationTitle(keyboardStrings.dictate)
                    .navigationBarTitleDisplayMode(.inline)
                    .toolbar {
                        ToolbarItem(placement: .confirmationAction) {
                            Button(keyboardStrings.done) { flow.showRecorder = false }
                                .disabled(flow.recordState != .idle)
                        }
                    }
            }
            .interactiveDismissDisabled(flow.recordState != .idle)
            .sheet(isPresented: shareVisible) {
                if let text = flow.shareText {
                    ShareSheet(items: [text])
                }
            }
        }
    }

    @ViewBuilder
    private func tabRoot(_ tab: AppTab) -> some View {
        switch tab {
        case .activity:
            ActivityView(flow: flow, keyboardStrings: keyboardStrings, onOpenProviders: openSpeechProviders)
        case .providers:
            WhisperPager(showSecond: $voiceCleanup, firstTitle: tabStrings.speech, secondTitle: tabStrings.cleanup) {
                SttSettingsView(store: speechStore)
            } second: {
                CleanupSettingsView(store: cleanupStore)
            }
        case .dictionary:
            DictionaryView()
        case .settings:
            settingsHub
        }
    }

    private var settingsHub: some View {
        List {
            Section {
                NavigationLink {
                    SetupGuidePage(strings: keyboardStrings)
                } label: {
                    WhisperDestinationLabel(
                        title: keyboardStrings.setupGuide,
                        summary: keyboardStrings.guideSummary,
                        symbol: "list.bullet.clipboard"
                    )
                }
            }
            .whisperRowSurface()
            Section {
                NavigationLink {
                    KeyboardSetupView(strings: keyboardStrings, flow: flow)
                } label: {
                    WhisperDestinationLabel(
                        title: keyboardStrings.keyboardTitle,
                        summary: keyboardStrings.keyboardSummary,
                        symbol: "keyboard"
                    )
                }
                NavigationLink {
                    SetupView(
                        settings: flow.settings,
                        micGranted: flow.micGranted,
                        testPassed: flow.testPassed,
                        testError: flow.testError,
                        strings: setupStrings,
                        onRequestMic: flow.requestMic,
                        onOpenSettings: flow.openSystemSettings,
                        onOpenProviders: openSpeechProviders,
                        onRunTest: flow.runSetupTest,
                        onMicPriorityChange: flow.applyMicPriority
                    )
                } label: {
                    WhisperDestinationLabel(
                        title: keyboardStrings.dictationSetup,
                        summary: keyboardStrings.dictationSummary,
                        symbol: "mic"
                    )
                }
            } header: {
                WhisperSectionHeading(keyboardStrings.input)
            }
            .whisperRowSurface()
            Section {
                NavigationLink {
                    DevicesView()
                } label: {
                    WhisperDestinationLabel(
                        title: keyboardStrings.devices,
                        summary: keyboardStrings.syncSummary,
                        symbol: "antenna.radiowaves.left.and.right"
                    )
                }
            } header: {
                WhisperSectionHeading(keyboardStrings.library)
            }
            .whisperRowSurface()
            Section {
                NavigationLink {
                    appearanceSettings
                } label: {
                    WhisperDestinationLabel(
                        title: keyboardStrings.appearance,
                        summary: keyboardStrings.appearanceSummary,
                        symbol: "circle.lefthalf.filled"
                    )
                }
            } header: {
                WhisperSectionHeading(keyboardStrings.appGroup)
            }
            .whisperRowSurface()
        }
        .whisperListStyle()
    }

    private var appearanceSettings: some View {
        List {
            Section {
                Picker(setupStrings.setupTheme, selection: Binding(get: { flow.theme }, set: flow.applyTheme)) {
                    ForEach(AppTheme.allCases, id: \.self) { option in
                        Text(option.rawValue).tag(option)
                    }
                }
                .pickerStyle(.inline)
            }
            .whisperRowSurface()
        }
        .whisperListStyle()
        .navigationTitle(keyboardStrings.appearance)
        .navigationBarTitleDisplayMode(.inline)
    }

    private var recordView: some View {
        ScrollView {
            VStack(spacing: 16) {
                if let error = flow.coreError {
                    VStack(alignment: .leading, spacing: 8) {
                        Text("Couldn't start the speech engine.")
                            .font(.subheadline)
                        Text(error)
                            .font(.caption)
                            .foregroundStyle(WhisperColors.error)
                            .textSelection(.enabled)
                        Button("Retry", action: flow.openCore)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding()
                    .whisperSurface()
                }
                RecordView(
                    state: flow.recordState,
                    toggled: flow.toggled,
                    micMode: flow.micMode,
                    levels: flow.levels,
                    onPressDown: flow.pressDown,
                    onPressUp: flow.pressUp,
                    onCancel: flow.cancelRecording
                )
                .frame(maxWidth: .infinity)
                .padding(.vertical, 12)
                .background(
                    LinearGradient(
                        colors: [WhisperColors.brandNavy, WhisperColors.brandInk],
                        startPoint: .topLeading, endPoint: .bottomTrailing
                    ),
                    in: RoundedRectangle(cornerRadius: 24)
                )
                if let content = flow.result {
                    ResultView(
                        content: content,
                        onCopy: flow.copyResult,
                        onShare: flow.shareResult,
                        onRetry: flow.retryResult,
                        strings: SttStrings.current(languageCode: languageCode)
                    )
                    .whisperSurface()
                }
            }
            .padding()
        }
        .background(WhisperColors.background(colorScheme))
    }

    private func openSpeechProviders() {
        voiceCleanup = false
        selectedTab = .providers
    }

    private var shareVisible: Binding<Bool> {
        Binding(get: { flow.shareText != nil }, set: { if !$0 { flow.shareText = nil } })
    }

    private var languageCode: String? { Locale.current.language.languageCode?.identifier }
    private var tabStrings: TabStrings { .current(languageCode: languageCode) }
    private var setupStrings: SetupStrings { .current(languageCode: languageCode) }
    private var keyboardStrings: OnboardingStrings { .current(languageCode: languageCode) }

    private var initialOnboardingPage: Int {
        #if DEBUG
        let args = CommandLine.arguments
        if let index = args.firstIndex(of: "-mwOnboardingPage"), args.indices.contains(index + 1) {
            return Int(args[index + 1]) ?? 0
        }
        #endif
        return 0
    }

    /// Links from the keyboard, which can't record or show settings itself.
    private func openLink(_ url: URL) {
        guard url.scheme == "mywhisper" else { return }
        switch url.host {
        case "keyboard-mic": flow.startKeyboardMicPreview()
        case "keyboard-settings": keyboardSettingsOpen = true
        default: break
        }
    }

    private func selectDebugTab() {
        #if DEBUG
        let args = CommandLine.arguments
        // CI: exercise the link handler without iOS's "Open in MyWhisper?" prompt.
        if let index = args.firstIndex(of: "-mwOpenURL"), args.indices.contains(index + 1),
           let url = URL(string: args[index + 1]) {
            openLink(url)
        }
        guard let index = args.firstIndex(of: "-mwTab"), args.indices.contains(index + 1) else { return }
        switch args[index + 1] {
        case "record": flow.showRecorder = true
        case "history": selectedTab = .activity
        case "speech": selectedTab = .providers
        case "setup": selectedTab = .settings
        default:
            if let tab = AppTab(rawValue: args[index + 1]) { selectedTab = tab }
        }
        #endif
    }
}

private struct SetupGuidePage: View {
    @Environment(\.dismiss) private var dismiss
    let strings: OnboardingStrings

    var body: some View {
        OnboardingView(strings: strings) { dismiss() }
            .toolbar(.hidden, for: .navigationBar)
    }
}
