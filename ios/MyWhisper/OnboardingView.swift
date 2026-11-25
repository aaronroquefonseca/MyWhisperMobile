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

struct OnboardingView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @FocusState private var practicing: Bool
    @State private var page: Int
    @State private var practiceText = ""
    let strings: OnboardingStrings
    let onDone: () -> Void

    init(strings: OnboardingStrings, initialPage: Int = 0, onDone: @escaping () -> Void) {
        self.strings = strings
        self.onDone = onDone
        _page = State(initialValue: min(max(initialPage, 0), 3))
    }

    var body: some View {
        VStack(spacing: 0) {
            HStack {
                Spacer()
                if page < 3 {
                    Button(strings.skip, action: onDone)
                }
            }
            .padding(.horizontal, 24)
            .frame(height: 44)

            TabView(selection: $page) {
                step(strings.welcomeTitle) {
                    BrandMark()
                } details: {
                    Text(strings.welcomeBody)
                }
                .tag(0)
                step(strings.installTitle) {
                    stepIcon("keyboard")
                } details: {
                    KeyboardInstallInstructions(strings: strings)
                }
                .tag(1)
                step(strings.practiceTitle) {
                    stepIcon("globe")
                } details: {
                    Text(strings.practiceHint)
                    TextField(strings.practicePlaceholder, text: $practiceText)
                        .textFieldStyle(.roundedBorder)
                        .focused($practicing)
                }
                .tag(2)
                step(strings.dictate) {
                    stepIcon("waveform")
                } details: {
                    Text(strings.dictationHint)
                }
                .tag(3)
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
            .onChange(of: page) { _, page in
                if page != 2 { practicing = false }
            }

            HStack(spacing: 8) {
                ForEach(0..<4, id: \.self) { index in
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
                Button(page == 3 ? strings.done : strings.next) {
                    if page == 3 { onDone() } else { movePage(to: page + 1) }
                }
                .buttonStyle(.borderedProminent)
                .tint(WhisperColors.brandCyan)
                .foregroundStyle(.black)
            }
            .buttonBorderShape(.capsule)
            .padding(.horizontal, 24)
            .padding(.bottom, 16)
        }
        .tint(.white)
        .background(WhisperColors.brandInk.ignoresSafeArea())
        .preferredColorScheme(.dark)
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

private struct KeyboardInstallInstructions: View {
    let strings: OnboardingStrings

    var body: some View {
        Text(strings.installPath).font(.body.weight(.semibold))
        Text(strings.installHint)
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
                ForEach(KeyboardLanguage.allCases) { language in
                    Toggle(language.title, isOn: Binding(
                        get: { preferences.languages.contains(language) },
                        set: { preferences.setLanguage(language, enabled: $0) }
                    ))
                    .disabled(preferences.languages == [language])
                }
                Picker(label("Default language", "Idioma predeterminado"), selection: $preferences.defaultLanguage) {
                    ForEach(preferences.languages) { language in
                        Text(language.title).tag(language)
                    }
                }
                Toggle(label("Swipe spacebar to switch languages", "Deslizar en la barra espaciadora para cambiar de idioma"), isOn: $preferences.switchesLanguageBySwipe)
                    .disabled(preferences.languages.count < 2)
            } header: {
                WhisperSectionHeading(label("Languages", "Idiomas"))
            } footer: {
                Text(label("Hold the spacebar to move the cursor. Swipe up or down to move by line, or sideways to switch languages.", "Mantén pulsada la barra espaciadora para mover el cursor. Desliza hacia arriba o abajo para cambiar de línea, o hacia un lado para cambiar de idioma."))
            }
            .whisperRowSurface()

            Section {
                Toggle(label("Swipe to type", "Deslizar para escribir"), isOn: $preferences.usesSwipeTyping)
                Toggle(label("Predictive suggestions", "Sugerencias predictivas"), isOn: $preferences.predictive)
                Toggle(label("Auto-correction", "Autocorrección"), isOn: $preferences.autoCorrection)
                Toggle(label("Auto-capitalization", "Mayúsculas automáticas"), isOn: $preferences.autoCapitalization)
                Toggle(label("Double-space for a period", "Doble espacio para insertar un punto"), isOn: $preferences.periodShortcut)
                Toggle(label("Enable Caps Lock", "Activar bloqueo de mayúsculas"), isOn: $preferences.enableCapsLock)
                Toggle(label("Character preview", "Vista previa de teclas"), isOn: $preferences.characterPreview)
                Toggle(label("Key clicks", "Sonido de teclas"), isOn: $preferences.keyClicks)
            } header: {
                WhisperSectionHeading(label("Typing", "Escritura"))
            }
            .whisperRowSurface()

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
                .whisperRowSurface()
            }

            Section {
                VStack(alignment: .leading, spacing: 8) {
                    KeyboardInstallInstructions(strings: strings)
                }
            } header: {
                WhisperSectionHeading(strings.installTitle)
            }
            .whisperRowSurface()
            Section {
                TextField(strings.practicePlaceholder, text: $practiceText)
            } header: {
                WhisperSectionHeading(strings.practiceTitle)
            } footer: {
                Text(strings.practiceHint)
            }
            .whisperRowSurface()
        }
        .whisperListStyle()
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
