import SwiftUI

struct OnboardingStrings {
    let isSpanish: Bool
    let skip: String
    let back: String
    let next: String
    let done: String
    let welcomeTitle: String
    let welcomeBody: String
    let setupGuide: String
    let input: String
    let keyboardTitle: String
    let keyboardSummary: String
    let installTitle: String
    let installPath: String
    let installHint: String
    let practiceTitle: String
    let practiceHint: String
    let practicePlaceholder: String
    let dictationSetup: String
    let dictate: String
    let dictationHint: String
    let devices: String
    let guideSummary: String
    let dictationSummary: String
    let syncSummary: String
    let library: String
    let appGroup: String
    let appearance: String
    let appearanceSummary: String

    static let english = OnboardingStrings(
        isSpanish: false, skip: "Skip", back: "Back", next: "Continue", done: "Done",
        welcomeTitle: "Your words, in any app",
        welcomeBody: "Add the MyWhisper keyboard to type with suggestions, autocorrect, and emoji wherever iOS supports custom keyboards.",
        setupGuide: "Setup guide",
        input: "Input",
        keyboardTitle: "MyWhisper keyboard",
        keyboardSummary: "Languages, typing preferences, and setup",
        installTitle: "Add the keyboard",
        installPath: "Settings → General → Keyboard → Keyboards → Add New Keyboard",
        installHint: "Choose MyWhisper Keyboard. Typing works without Full Access.",
        practiceTitle: "Try your keyboard",
        practiceHint: "Tap the field and hold the globe key. Choose MyWhisper Keyboard, then tap or slide across letters to type a word.",
        practicePlaceholder: "Type a practice message",
        dictationSetup: "Dictation setup",
        dictate: "Dictate in the app",
        dictationHint: "Choose a speech provider in Providers, then record from Activity. Copy the transcript to use it in another app.",
        devices: "Devices & sync",
        guideSummary: "Walk through setup and practice again",
        dictationSummary: "Microphone access, input priority, and a dictation test",
        syncSummary: "Keep your words and snippets on all your devices",
        library: "Library & sync",
        appGroup: "App",
        appearance: "Appearance",
        appearanceSummary: "System, light, or dark theme"
    )

    static let spanish = OnboardingStrings(
        isSpanish: true, skip: "Omitir", back: "Atrás", next: "Continuar", done: "Listo",
        welcomeTitle: "Tus palabras, en cualquier app",
        welcomeBody: "Añade el teclado MyWhisper para escribir con sugerencias, autocorrección y emojis donde iOS admita teclados personalizados.",
        setupGuide: "Guía de configuración",
        input: "Entrada",
        keyboardTitle: "Teclado MyWhisper",
        keyboardSummary: "Idiomas, preferencias de escritura e instalación",
        installTitle: "Añade el teclado",
        installPath: "Ajustes → General → Teclado → Teclados → Añadir nuevo teclado",
        installHint: "Elige MyWhisper Keyboard. Puedes escribir sin acceso total.",
        practiceTitle: "Prueba tu teclado",
        practiceHint: "Toca el campo y mantén pulsada la tecla del globo. Elige MyWhisper Keyboard y toca o desliza el dedo sobre las letras para escribir una palabra.",
        practicePlaceholder: "Escribe un mensaje de prueba",
        dictationSetup: "Configurar el dictado",
        dictate: "Dictar en la app",
        dictationHint: "Elige un proveedor de voz en Proveedores y graba desde Actividad. Copia la transcripción para usarla en otra app.",
        devices: "Dispositivos y sincronización",
        guideSummary: "Repite la configuración y la práctica",
        dictationSummary: "Acceso al micrófono, prioridad de entrada y prueba de dictado",
        syncSummary: "Mantén tus palabras y fragmentos en todos tus dispositivos",
        library: "Biblioteca y sincronización",
        appGroup: "Aplicación",
        appearance: "Aspecto",
        appearanceSummary: "Tema del sistema, claro u oscuro"
    )

    static func current(languageCode: String?) -> OnboardingStrings {
        languageCode == "es" ? .spanish : .english
    }
}

private struct BrandMark: View {
    var body: some View {
        HStack(spacing: 7) {
            ForEach(Array([0.38, 0.62, 1.0, 0.62, 0.38].enumerated()), id: \.offset) { _, fraction in
                Capsule()
                    .fill(WhisperColors.brandCyan)
                    .frame(width: 13, height: 62 * fraction)
            }
        }
        .frame(width: 120, height: 120)
        .background(WhisperColors.brandNavy, in: RoundedRectangle(cornerRadius: 28))
        .accessibilityHidden(true)
    }
}

/// Android's first run, for the keyboard: welcome, the setup steps (the pager stops there until
/// the keyboard is added and the microphone allowed), then practice. No skipping, like Android.
struct OnboardingView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @FocusState private var practicing: Bool
    @State private var page: Int
    @State private var practiceText = ""
    @State private var setup = SetupState(keyboardAdded: false, fullAccess: false, micGranted: false, speechReady: false)
    let strings: OnboardingStrings
    @ObservedObject var flow: AppFlow
    let onDone: () -> Void

    private static let pages = 3

    init(strings: OnboardingStrings, flow: AppFlow, initialPage: Int = 0, onDone: @escaping () -> Void) {
        self.strings = strings
        self.flow = flow
        self.onDone = onDone
        _page = State(initialValue: min(max(initialPage, 0), Self.pages - 1))
    }

    var body: some View {
        VStack(spacing: 0) {
            Group {
                switch page {
                case 0: welcome
                case 1: setupPage
                default: practicePage
                }
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
            .transition(.opacity)

            HStack(spacing: 8) {
                ForEach(0..<Self.pages, id: \.self) { index in
                    Capsule()
                        .fill(index == page ? WhisperColors.brandCyan : .white.opacity(0.25))
                        .frame(width: index == page ? 18 : 6, height: 6)
                }
            }
            .accessibilityHidden(true)
            .padding(.vertical, 14)

            HStack(spacing: 12) {
                if page > 0 {
                    Button(strings.back) { movePage(to: page - 1) }
                        .buttonStyle(.bordered)
                }
                Button(primaryTitle) {
                    if page == Self.pages - 1 { onDone() } else { movePage(to: page + 1) }
                }
                .buttonStyle(.borderedProminent)
                .foregroundStyle(.black)
                .disabled(page == 1 && !setup.requiredDone)
            }
            .controlSize(.large)
            .buttonBorderShape(.capsule)
            .padding(.horizontal, 24)
            .padding(.bottom, 16)
        }
        .tint(WhisperColors.brandCyan)
        .background(WhisperColors.brandInk.ignoresSafeArea())
        .preferredColorScheme(.dark)
    }

    private var primaryTitle: String {
        switch page {
        case 0: return String(localized: "Get started")
        case Self.pages - 1: return String(localized: "Start using MyWhisper")
        default: return strings.next
        }
    }

    private var welcome: some View {
        step(String(localized: "Dictate wherever you type")) {
            BrandMark()
        } details: {
            Text("The MyWhisper keyboard types with suggestions and autocorrect, and turns your voice into text in any app.")
            VStack(alignment: .leading, spacing: 10) {
                bullet("Works in any app: messages, notes, email, search.")
                bullet("Fixes punctuation and filler words for you, if you want.")
                bullet("You choose where your voice is turned into text.")
            }
            .padding(.top, 4)
        }
    }

    private var setupPage: some View {
        step(String(localized: "Set up the keyboard")) {
            stepIcon("keyboard")
        } details: {
            Text("Add the keyboard and allow the microphone. MyWhisper only listens while you hold its mic button.")
            SetupChecklistView(flow: flow, includesSpeech: false, state: $setup)
                .foregroundStyle(.primary)
        }
    }

    private var practicePage: some View {
        step(strings.practiceTitle) {
            stepIcon("globe")
        } details: {
            Text("Tap the box, hold the globe key and choose MyWhisper Keyboard. Type, or hold the mic button above the keys and speak.")
            if !isServerConfigured(settings: flow.settings) {
                Text("To turn speech into text, add a speech provider in Providers after this.")
                    .font(.footnote)
            }
            TextField(strings.practicePlaceholder, text: $practiceText, axis: .vertical)
                .lineLimit(3...6)
                .textFieldStyle(.roundedBorder)
                .focused($practicing)
        }
    }

    private func bullet(_ text: LocalizedStringKey) -> some View {
        Label {
            Text(text)
        } icon: {
            Image(systemName: "checkmark").foregroundStyle(WhisperColors.brandCyan)
        }
    }

    private func step<Hero: View, Details: View>(
        _ title: String, @ViewBuilder hero: () -> Hero, @ViewBuilder details: () -> Details
    ) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 16) {
                hero()
                Text(title)
                    .font(.largeTitle.bold())
                    .accessibilityAddTraits(.isHeader)
                details()
                    .foregroundStyle(.secondary)
            }
            .frame(maxWidth: 460, alignment: .leading)
            .padding(24)
            .frame(maxWidth: .infinity)
        }
        .defaultScrollAnchor(.center)
        .scrollDismissesKeyboard(.interactively)
    }

    private func stepIcon(_ symbol: String) -> some View {
        Image(systemName: symbol)
            .font(.title.weight(.medium))
            .foregroundStyle(WhisperColors.brandCyan)
            .frame(width: 60, height: 60)
            .background(WhisperColors.brandNavy, in: RoundedRectangle(cornerRadius: 18))
            .accessibilityHidden(true)
    }

    private func movePage(to next: Int) {
        practicing = false
        withAnimation(reduceMotion ? nil : .default) { page = next }
    }
}

struct KeyboardSetupView: View {
    let strings: OnboardingStrings
    @State private var practiceText = ""
    @State private var preferences = KeyboardPreferences.load()
    @State private var needsTransfer = KeyboardPreferences.sharedURL == nil
    @State private var transferText = ""
    @FocusState private var transferring: Bool

    var body: some View {
        List {
            Section {
                NavigationLink(value: SettingsRoute.keyboardLanguages) {
                    LabeledContent(label("Languages", "Idiomas"), value: preferences.languages.map(\.title).joined(separator: ", "))
                }
                Toggle(label("Swipe spacebar to switch languages", "Deslizar en la barra espaciadora para cambiar de idioma"), isOn: $preferences.switchesLanguageBySwipe)
                    .disabled(preferences.languages.count < 2)
                Toggle(label("Keep the last language used", "Mantener el último idioma usado"), isOn: $preferences.remembersLanguage)
                    .disabled(preferences.languages.count < 2)
            } footer: {
                Text(label("Hold the spacebar to move the cursor. Swipe up or down to move by line, or sideways to switch languages.", "Mantén pulsada la barra espaciadora para mover el cursor. Desliza hacia arriba o abajo para cambiar de línea, o hacia un lado para cambiar de idioma."))
            }

            Section {
                Toggle(label("Swipe to type", "Deslizar para escribir"), isOn: $preferences.usesSwipeTyping)
                Toggle(label("Predictive suggestions", "Sugerencias predictivas"), isOn: $preferences.predictive)
                Toggle(label("Auto-correction", "Autocorrección"), isOn: $preferences.autoCorrection)
                Toggle(label("Auto-capitalization", "Mayúsculas automáticas"), isOn: $preferences.autoCapitalization)
                Toggle(label("Double-space for a period", "Doble espacio para insertar un punto"), isOn: $preferences.periodShortcut)
                Toggle(label("Enable Caps Lock", "Activar bloqueo de mayúsculas"), isOn: $preferences.enableCapsLock)
            } header: {
                Text(label("Typing", "Escritura"))
            }

            Section {
                Toggle(label("Character preview", "Vista previa de teclas"), isOn: $preferences.characterPreview)
                Toggle(label("Key clicks", "Sonido de teclas"), isOn: $preferences.keyClicks)
                Toggle(label("Haptic feedback", "Respuesta háptica"), isOn: $preferences.usesHaptics)
                Toggle(label("Number row", "Fila de números"), isOn: $preferences.showsNumberRow)
                Toggle(label("Period key beside Space", "Tecla de punto junto al espacio"), isOn: $preferences.showsPeriodKey)
                Toggle(label("Button beside suggestions", "Botón junto a las sugerencias"), isOn: $preferences.showsSideButton)
            } header: {
                Text(label("Keys", "Teclas"))
            }

            if needsTransfer {
                Section {
                    Button(label("Apply preferences to keyboard", "Aplicar preferencias al teclado")) {
                        transferText = preferences.transferText
                        transferring = true
                    }
                    if !transferText.isEmpty || transferring {
                        TextField(label("Keyboard preferences code", "Código de preferencias del teclado"), text: $transferText)
                            .keyboardType(.asciiCapable)
                            .autocorrectionDisabled()
                            .textInputAutocapitalization(.never)
                            .focused($transferring)
                    }
                } footer: {
                    Text(label("If prompted, switch to MyWhisper Keyboard and tap Apply preferences above the keys.", "Si se solicita, cambia al teclado de MyWhisper y toca Aplicar preferencias encima de las teclas."))
                }
            }

            Section {
                TextField(strings.practicePlaceholder, text: $practiceText, axis: .vertical)
            } header: {
                Text(label("Try it", "Pruébalo"))
            }
        }
        .whisperListStyle()
        // The languages page saves on its own; pick up its changes when coming back.
        .onAppear { preferences = KeyboardPreferences.load() }
        .onChange(of: preferences) { _, updated in
            needsTransfer = !updated.saveFromApp()
            if transferring { transferText = updated.transferText }
        }
        .onChange(of: transferText) { _, updated in
            if updated.isEmpty { transferring = false }
        }
        .navigationTitle(strings.keyboardTitle)
        .navigationBarTitleDisplayMode(.inline)
    }

    private func label(_ english: String, _ spanish: String) -> String {
        strings.isSpanish ? spanish : english
    }
}

/// Like iOS's own Settings › Keyboards: the enabled languages (tap one for the default, swipe to
/// remove), then the ones left to add.
struct KeyboardLanguagesView: View {
    @State private var preferences = KeyboardPreferences.load()

    var body: some View {
        List {
            Section {
                ForEach(preferences.languages) { language in
                    Button {
                        preferences.defaultLanguage = language
                    } label: {
                        LabeledContent(language.title) {
                            if language == preferences.defaultLanguage { Text("Default") }
                        }
                    }
                    .foregroundStyle(.primary)
                    .deleteDisabled(preferences.languages.count == 1)
                }
                .onDelete { offsets in
                    for language in offsets.map({ preferences.languages[$0] }) {
                        preferences.setLanguage(language, enabled: false)
                    }
                }
            } header: {
                Text("Enabled languages")
            } footer: {
                Text("Tap a language to type in it by default. Swipe left to remove one.")
            }
            let available = KeyboardLanguage.allCases.filter { !preferences.languages.contains($0) }
            if !available.isEmpty {
                Section("Add a language") {
                    ForEach(available) { language in
                        Button {
                            preferences.setLanguage(language, enabled: true)
                        } label: {
                            Label(language.title, systemImage: "plus.circle.fill")
                        }
                    }
                }
            }
        }
        .whisperListStyle()
        .onChange(of: preferences) { _, updated in _ = updated.saveFromApp() }
        .navigationTitle("Languages")
        .navigationBarTitleDisplayMode(.inline)
    }
}
