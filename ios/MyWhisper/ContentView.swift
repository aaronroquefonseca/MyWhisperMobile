import SwiftUI

struct ContentView: View {
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.colorScheme) private var colorScheme
    @StateObject private var flow = AppFlow()
    @StateObject private var speechStore = SttSettingsStore()
    @StateObject private var cleanupStore = CleanupSettingsStore()
    @State private var selectedTab = AppTab.activity
    @State private var voiceCleanup = false
    @State private var showGuide = false
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
    }

    private var mainView: some View {
        TabView(selection: $selectedTab) {
            ForEach(AppTab.allCases, id: \.self) { tab in
                NavigationStack {
                    switch tab {
                    case .activity:
                        ActivityView(flow: flow, keyboardStrings: keyboardStrings, onOpenProviders: openSpeechProviders)
                    case .providers:
                        providers
                    case .dictionary:
                        DictionaryView()
                    case .settings:
                        settingsHub
                    }
                }
                .tabItem { Label(tab.title(using: tabStrings), systemImage: tab.systemImage) }
                .tag(tab)
            }
        }
        .whisperTabBarMinimize()
        .tint(WhisperColors.primary(colorScheme))
        .onChange(of: selectedTab) { _, tab in
            if tab == .activity || tab == .settings { flow.reloadSettings() }
        }
        .onAppear(perform: selectDebugTab)
        .sheet(isPresented: $showGuide) {
            OnboardingView(strings: keyboardStrings) { showGuide = false }
        }
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

    private var providers: some View {
        VStack(spacing: 0) {
            Picker(tabStrings.providers, selection: $voiceCleanup) {
                Text(tabStrings.speech).tag(false)
                Text(tabStrings.cleanup).tag(true)
            }
            .pickerStyle(.segmented)
            .padding(.horizontal, 16)
            .padding(.vertical, 8)

            // Keep both editors mounted when switching segments.
            ZStack {
                SttSettingsView(store: speechStore, navigationTitle: tabStrings.providers)
                    .opacity(voiceCleanup ? 0 : 1)
                    .allowsHitTesting(!voiceCleanup)
                    .accessibilityHidden(voiceCleanup)
                CleanupSettingsView(store: cleanupStore, navigationTitle: tabStrings.providers)
                    .opacity(voiceCleanup ? 1 : 0)
                    .allowsHitTesting(voiceCleanup)
                    .accessibilityHidden(!voiceCleanup)
            }
        }
        .background(WhisperColors.background(colorScheme))
        .navigationTitle(tabStrings.providers)
    }

    private var settingsHub: some View {
        List {
            Section {
                Button { showGuide = true } label: {
                    Label(keyboardStrings.setupGuide, systemImage: "list.bullet.clipboard")
                }
            }
            Section(keyboardStrings.input) {
                NavigationLink {
                    KeyboardSetupView(strings: keyboardStrings)
                } label: {
                    Label(keyboardStrings.keyboardTitle, systemImage: "keyboard")
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
                    Label(keyboardStrings.dictationSetup, systemImage: "mic")
                }
            }
            Section {
                NavigationLink {
                    DevicesView()
                } label: {
                    Label(keyboardStrings.devices, systemImage: "antenna.radiowaves.left.and.right")
                }
            }
            Section(setupStrings.setupTheme) {
                Picker(setupStrings.setupTheme, selection: Binding(get: { flow.theme }, set: flow.applyTheme)) {
                    ForEach(AppTheme.allCases, id: \.self) { option in
                        Text(option.rawValue).tag(option)
                    }
                }
                .pickerStyle(.segmented)
            }
        }
        .navigationTitle(tabStrings.settings)
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

    private func selectDebugTab() {
        #if DEBUG
        let args = CommandLine.arguments
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
