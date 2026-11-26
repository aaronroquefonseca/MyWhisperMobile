import SwiftUI

/// Screens pushed inside the Settings tab, so other tabs and the keyboard can open one in place.
enum SettingsRoute: String, Hashable {
    case setupGuide, finishSetup, keyboard, keyboardLanguages, microphone, devices, appearance, diagnostics
}

struct ContentView: View {
    @Environment(\.scenePhase) private var scenePhase
    @Environment(\.colorScheme) private var colorScheme
    @StateObject private var flow = AppFlow()
    @StateObject private var speechStore = SttSettingsStore()
    @StateObject private var cleanupStore = CleanupSettingsStore()
    @State private var selectedTab = AppTab.activity
    @State private var voiceCleanup = false
    @State private var settingsPath: [SettingsRoute] = []
    @State private var showOnboarding = {
        #if DEBUG
        if CommandLine.arguments.contains("-mwSkipOnboarding") { return false }
        #endif
        return !UserDefaults.standard.bool(forKey: "mw.onboarded.v1")
    }()

    var body: some View {
        Group {
            if showOnboarding {
                OnboardingView(strings: keyboardStrings, flow: flow, initialPage: initialOnboardingPage) {
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
        // The keyboard's settings button opens this link.
        .onOpenURL(perform: openLink)
    }

    private var mainView: some View {
        TabView(selection: $selectedTab) {
            ForEach(AppTab.allCases, id: \.self) { tab in
                Group {
                    if tab == .settings {
                        NavigationStack(path: $settingsPath) {
                            settingsHub
                                .navigationTitle(tab.title(using: tabStrings))
                                .navigationDestination(for: SettingsRoute.self, destination: settingsScreen)
                        }
                    } else {
                        NavigationStack { tabRoot(tab) }
                    }
                }
                .whisperTabBarBackground(colorScheme)
                .tabItem { Label(tab.title(using: tabStrings), systemImage: tab.systemImage) }
                .tag(tab)
            }
        }
        .tint(WhisperColors.primary(colorScheme))
        .onChange(of: selectedTab) { _, tab in
            if tab == .activity || tab == .settings { flow.reloadSettings() }
        }
        .onAppear(perform: selectDebugTab)
    }

    @ViewBuilder
    private func tabRoot(_ tab: AppTab) -> some View {
        switch tab {
        case .activity:
            ActivityView(flow: flow, keyboardStrings: keyboardStrings, onOpenProviders: openSpeechProviders, onOpenSettings: openSettings)
                .navigationTitle(tab.title(using: tabStrings))
        case .providers:
            Group {
                if voiceCleanup {
                    CleanupSettingsView(store: cleanupStore)
                } else {
                    SttSettingsView(store: speechStore)
                }
            }
            .navigationTitle(tab.title(using: tabStrings))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                // Like Phone's Recents: two views of one list, switched in the navigation bar.
                ToolbarItem(placement: .principal) {
                    Picker(tab.title(using: tabStrings), selection: $voiceCleanup) {
                        Text(tabStrings.speechShort).tag(false)
                        Text(tabStrings.cleanupShort).tag(true)
                    }
                    .pickerStyle(.segmented)
                    .frame(maxWidth: 280)
                }
            }
        case .dictionary:
            DictionaryView()
                .navigationTitle(tab.title(using: tabStrings))
        case .settings:
            EmptyView()
        }
    }

    @ViewBuilder
    private func settingsScreen(_ route: SettingsRoute) -> some View {
        switch route {
        case .setupGuide:
            SetupGuidePage(strings: keyboardStrings, flow: flow)
        case .finishSetup:
            SetupScreen(flow: flow, onOpenProviders: openSpeechProviders)
        case .keyboard:
            KeyboardSetupView(strings: keyboardStrings)
        case .keyboardLanguages:
            KeyboardLanguagesView()
        case .microphone:
            MicrophoneSettingsView(flow: flow)
        case .devices:
            DevicesView()
        case .appearance:
            AppearanceSettingsView(flow: flow)
        case .diagnostics:
            DiagnosticsView(flow: flow)
        }
    }

    private var settingsHub: some View {
        List {
            Section {
                link(.setupGuide, keyboardStrings.setupGuide, keyboardStrings.guideSummary, "list.bullet.clipboard")
            }
            Section {
                link(.keyboard, keyboardStrings.keyboardTitle, keyboardStrings.keyboardSummary, "keyboard")
                link(.microphone, String(localized: "Microphone"), String(localized: "Listening delay and preferred microphone"), "mic")
            } header: {
                Text(keyboardStrings.input)
            }
            Section {
                link(.devices, keyboardStrings.devices, keyboardStrings.syncSummary, "antenna.radiowaves.left.and.right")
            } header: {
                Text(keyboardStrings.library)
            }
            Section {
                link(.appearance, keyboardStrings.appearance, keyboardStrings.appearanceSummary, "circle.lefthalf.filled")
                link(.diagnostics, String(localized: "Diagnostics"), String(localized: "Details to share when you report a problem"), "stethoscope")
            } header: {
                Text(keyboardStrings.appGroup)
            }
        }
        .whisperListStyle()
    }

    private func link(_ route: SettingsRoute, _ title: String, _ summary: String, _ symbol: String) -> some View {
        NavigationLink(value: route) {
            WhisperDestinationLabel(title: title, summary: summary, symbol: symbol)
        }
    }

    private func openSpeechProviders() {
        voiceCleanup = false
        selectedTab = .providers
    }

    /// A shortcut from another tab: switch to Settings with that screen open, not a copy pushed here.
    private func openSettings(_ route: SettingsRoute) {
        settingsPath = route == .keyboardLanguages ? [.keyboard, route] : [route]
        selectedTab = .settings
    }

    private var languageCode: String? { Locale.current.language.languageCode?.identifier }
    private var tabStrings: TabStrings { .current(languageCode: languageCode) }
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

    /// Links from the keyboard, which can't show settings itself.
    private func openLink(_ url: URL) {
        guard url.scheme == "mywhisper" else { return }
        if url.host == "keyboard-settings" { openSettings(.keyboard) }
    }

    private func selectDebugTab() {
        #if DEBUG
        let args = CommandLine.arguments
        // CI: exercise the link handler without iOS's "Open in MyWhisper?" prompt.
        if let index = args.firstIndex(of: "-mwOpenURL"), args.indices.contains(index + 1),
           let url = URL(string: args[index + 1]) {
            openLink(url)
        }
        if let index = args.firstIndex(of: "-mwRoute"), args.indices.contains(index + 1),
           let route = SettingsRoute(rawValue: args[index + 1]) {
            openSettings(route)
        }
        guard let index = args.firstIndex(of: "-mwTab"), args.indices.contains(index + 1) else { return }
        switch args[index + 1] {
        case "cleanup":
            selectedTab = .providers
            voiceCleanup = true
        default:
            if let tab = AppTab(rawValue: args[index + 1]) { selectedTab = tab }
        }
        #endif
    }
}

private struct SetupGuidePage: View {
    @Environment(\.dismiss) private var dismiss
    let strings: OnboardingStrings
    @ObservedObject var flow: AppFlow

    var body: some View {
        OnboardingView(strings: strings, flow: flow) { dismiss() }
            .toolbar(.hidden, for: .navigationBar)
            .toolbar(.hidden, for: .tabBar)
    }
}

struct AppearanceSettingsView: View {
    @ObservedObject var flow: AppFlow

    var body: some View {
        List {
            Section("Theme") {
                Picker("Theme", selection: Binding(get: { flow.theme }, set: flow.applyTheme)) {
                    ForEach(AppTheme.allCases, id: \.self) { option in
                        Text(LocalizedStringKey(option.rawValue)).tag(option)
                    }
                }
                .pickerStyle(.inline)
                .labelsHidden()
            }
            Section {
                Toggle(isOn: Binding {
                    (flow.settings?["preferences"] as? [String: Any])?["black_background"] as? Bool ?? false
                } set: { value in
                    flow.updateSettings { settings in
                        var prefs = settings["preferences"] as? [String: Any] ?? [:]
                        prefs["black_background"] = value
                        settings["preferences"] = prefs
                    }
                }) {
                    Text("Pure black background")
                }
            } footer: {
                Text("Use black instead of grey in dark mode. Also works with System theme.")
            }
        }
        .whisperListStyle()
        .navigationTitle("Appearance")
        .navigationBarTitleDisplayMode(.inline)
    }
}
