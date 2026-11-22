import SwiftUI

/// First-run onboarding: welcome → mic → keyboard install → providers.
/// Forced-dark immersive glass style (brand navy gradient + frosted cards).
/// Bilingual twins follow the app pattern (no `.lproj` infra yet).
struct OnboardingStrings: Equatable {
    let skip: String
    let back: String
    let next: String
    let start: String
    let welcomeTitle: String
    let welcomeBody: String
    let micTitle: String
    let micBody: String
    let micGrant: String
    let micDone: String
    let keyboardTitle: String
    let keyboardBody: String
    let keyboardSteps: [String]
    let keyboardNote: String
    let openSettings: String
    let serverTitle: String
    let serverBody: String

    static let english = OnboardingStrings(
        skip: "Skip",
        back: "Back",
        next: "Continue",
        start: "Get started",
        welcomeTitle: "Type with your voice, anywhere",
        welcomeBody: "MyWhisper adds a keyboard with a mic key. Switch to it in any chat and type — voice dictation lights up in the next update.",
        micTitle: "Microphone first",
        micBody: "Dictation needs the mic. Audio goes only to the speech provider you choose — nothing else.",
        micGrant: "Grant microphone access",
        micDone: "Microphone ready ✓",
        keyboardTitle: "Install the keyboard",
        keyboardBody: "iOS won't switch keyboards for you — two minutes, once:",
        keyboardSteps: [
            "Open Settings → General → Keyboard → Keyboards → Add New Keyboard",
            "Pick MyWhisper Keyboard",
        ],
        keyboardNote: "Typing works right away with no extra permissions. Voice dictation — and its Full Access prompt — arrive in the next update. Some password fields always use the system keyboard.",
        openSettings: "Open Settings",
        serverTitle: "Point it at speech",
        serverBody: "After this, open the Speech tab and pick a provider. It powers dictation in the Record tab today; the keyboard’s mic key follows in the next update."
    )

    static let spanish = OnboardingStrings(
        skip: "Omitir",
        back: "Atrás",
        next: "Continuar",
        start: "Empezar",
        welcomeTitle: "Escribe con tu voz, donde sea",
        welcomeBody: "MyWhisper añade un teclado con tecla de micrófono. Cámbiate a él en cualquier chat y escribe: el dictado por voz llega en la próxima actualización.",
        micTitle: "Primero el micrófono",
        micBody: "El dictado necesita el micrófono. El audio solo va al proveedor de voz que elijas — a nada más.",
        micGrant: "Permitir acceso al micrófono",
        micDone: "Micrófono listo ✓",
        keyboardTitle: "Instala el teclado",
        keyboardBody: "iOS no cambia de teclado por ti — dos minutos, una vez:",
        keyboardSteps: [
            "Abre Ajustes → General → Teclado → Teclados → Añadir nuevo teclado",
            "Elige MyWhisper Keyboard",
        ],
        keyboardNote: "Escribir funciona desde ya, sin permisos extra. El dictado por voz —y su aviso de acceso total— llega en la próxima actualización. Algunos campos de contraseña siempre usan el teclado del sistema.",
        openSettings: "Abrir Ajustes",
        serverTitle: "Conéctalo a la voz",
        serverBody: "Después, abre la pestaña Voz y elige un proveedor. Da voz a la pestaña Grabar hoy; la tecla de micrófono del teclado llega en la próxima actualización."
    )

    static func current(languageCode: String?) -> OnboardingStrings {
        languageCode == "es" ? .spanish : .english
    }
}

/// Brand mark redrawn in SwiftUI (app-icon geometry: navy squircle, cyan
/// five-bar waveform with speech tail implied by the center bar).
struct BrandMark: View {
    var body: some View {
        ZStack {
            RoundedRectangle(cornerRadius: 28, style: .continuous)
                .fill(Color(red: 0x14 / 255, green: 0x22 / 255, blue: 0x34 / 255))
            HStack(spacing: 7) {
                ForEach([0.38, 0.62, 1.0, 0.62, 0.38], id: \.self) { f in
                    RoundedRectangle(cornerRadius: 6, style: .continuous)
                        .fill(Color(red: 0x5C / 255, green: 0xD9 / 255, blue: 0xEC / 255))
                        .frame(width: 13, height: 62 * f)
                }
            }
        }
        .frame(width: 120, height: 120)
        .shadow(color: .cyan.opacity(0.25), radius: 24)
    }
}

struct OnboardingView: View {
    var micGranted: Bool
    var strings: OnboardingStrings
    var onRequestMic: () -> Void = {}
    var onOpenSettings: () -> Void = {}
    var onDone: () -> Void = {}
    var initialPage: Int = 0

    @State private var page: Int = -1

    var body: some View {
        ZStack {
            LinearGradient(
                colors: [
                    Color(red: 0x14 / 255, green: 0x22 / 255, blue: 0x34 / 255),
                    Color(red: 0x07 / 255, green: 0x0E / 255, blue: 0x18 / 255),
                ],
                startPoint: .topLeading, endPoint: .bottomTrailing
            )
            .ignoresSafeArea()
            VStack(spacing: 0) {
                HStack {
                    Spacer()
                    if page < 3 {
                        Button(strings.skip, action: onDone)
                            .foregroundStyle(.white.opacity(0.7))
                    }
                }
                .padding(.horizontal, 24)
                .frame(height: 44)
                TabView(selection: $page) {
                    welcome.tag(0)
                    mic.tag(1)
                    keyboard.tag(2)
                    server.tag(3)
                }
                .tabViewStyle(.page(indexDisplayMode: .never))
                .onAppear { if page < 0 { page = min(max(initialPage, 0), 3) } }
                HStack(spacing: 8) {
                    ForEach(0..<4, id: \.self) { i in
                        Circle()
                            .fill(i == page ? Color(red: 0x5C / 255, green: 0xD9 / 255, blue: 0xEC / 255) : .white.opacity(0.25))
                            .frame(width: 8, height: 8)
                    }
                }
                .padding(.bottom, 20)
                HStack(spacing: 12) {
                    if page > 0 {
                        Button(strings.back) { withAnimation { page -= 1 } }
                            .buttonStyle(.bordered)
                            .tint(.white)
                    }
                    Button(page == 3 ? strings.start : strings.next) {
                        if page == 3 { onDone() } else { withAnimation { page += 1 } }
                    }
                    .buttonStyle(.borderedProminent)
                    .tint(Color(red: 0x5C / 255, green: 0xD9 / 255, blue: 0xEC / 255))
                    .foregroundStyle(.black)
                }
                .padding(.horizontal, 24)
                .padding(.bottom, 32)
            }
        }
        .preferredColorScheme(.dark)
    }

    private var welcome: some View {
        VStack(spacing: 20) {
            Spacer()
            BrandMark()
            Text(strings.welcomeTitle)
                .font(.largeTitle).bold()
                .foregroundStyle(.white)
                .multilineTextAlignment(.center)
            Text(strings.welcomeBody)
                .font(.body)
                .foregroundStyle(.white.opacity(0.75))
                .multilineTextAlignment(.center)
                .padding(.horizontal, 32)
            Spacer()
        }
        .padding(.bottom, 24)
    }

    private var mic: some View {
        card(title: strings.micTitle, systemImage: "mic.fill") {
            Text(strings.micBody)
                .font(.body)
                .foregroundStyle(.white.opacity(0.8))
            if micGranted {
                Label(strings.micDone, systemImage: "checkmark.circle.fill")
                    .foregroundStyle(.green)
                    .font(.headline)
            } else {
                Button(strings.micGrant, action: onRequestMic)
                    .buttonStyle(.borderedProminent)
                    .tint(Color(red: 0x5C / 255, green: 0xD9 / 255, blue: 0xEC / 255))
                    .foregroundStyle(.black)
            }
        }
    }

    private var keyboard: some View {
        card(title: strings.keyboardTitle, systemImage: "keyboard.fill") {
            Text(strings.keyboardBody)
                .font(.body)
                .foregroundStyle(.white.opacity(0.8))
            VStack(alignment: .leading, spacing: 10) {
                ForEach(Array(strings.keyboardSteps.enumerated()), id: \.offset) { i, step in
                    HStack(alignment: .top, spacing: 10) {
                        Text("\(i + 1)")
                            .font(.headline)
                            .foregroundStyle(Color(red: 0x5C / 255, green: 0xD9 / 255, blue: 0xEC / 255))
                            .frame(width: 22)
                        Text(step)
                            .font(.subheadline)
                            .foregroundStyle(.white)
                    }
                }
            }
            Button(strings.openSettings, action: onOpenSettings)
                .buttonStyle(.bordered)
                .tint(.white)
            Text(strings.keyboardNote)
                .font(.footnote)
                .foregroundStyle(.white.opacity(0.6))
        }
    }

    private var server: some View {
        card(title: strings.serverTitle, systemImage: "waveform") {
            Text(strings.serverBody)
                .font(.body)
                .foregroundStyle(.white.opacity(0.8))
        }
    }

    private func card<Content: View>(title: String, systemImage: String, @ViewBuilder content: () -> Content) -> some View {
        VStack(spacing: 0) {
            Spacer()
            VStack(alignment: .leading, spacing: 16) {
                Image(systemName: systemImage)
                    .font(.system(size: 34))
                    .foregroundStyle(Color(red: 0x5C / 255, green: 0xD9 / 255, blue: 0xEC / 255))
                Text(title)
                    .font(.title2).bold()
                    .foregroundStyle(.white)
                content()
            }
            .padding(24)
            .whisperGlass()
            .padding(.horizontal, 20)
            Spacer()
        }
        .padding(.bottom, 12)
    }
}

#Preview {
    OnboardingView(micGranted: false, strings: .english)
}
