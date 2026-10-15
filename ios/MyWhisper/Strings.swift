import Foundation

/// Setup-screen strings with Spanish twins. Every shared key reuses the
/// Android `values/strings.xml` / `values-es/strings.xml` copy verbatim so
/// both apps say the same things; iOS-only keys (no IME on iOS, checklist
/// progress, test dictation) are authored here in both languages.
///
/// No `.lproj` infrastructure exists yet and no thread owns it (same
/// situation as `SttStrings` in `Transcription.swift`), so twins live
/// beside the English copy and resolve via an explicit language code —
/// pure and unit-tested. A future localization task moves these to
/// String Catalogs.
struct SetupStrings: Equatable {
    // Android-parity keys (values/strings.xml ↔ values-es/strings.xml).
    let tabSetup: String
    let setupStatus: String
    let setupServerOnly: String
    let setupMicYes: String
    let setupMicNo: String
    let setupMicGrant: String
    let setupMicDenied: String
    let setupTheme: String
    let setupPrefs: String
    let actionCancel: String
    let actionChange: String
    let actionGotIt: String
    let actionRefresh: String
    // iOS-only keys (no Android counterpart), except doneServer which
    // pins setup_speech_configured ("Speech configured" / "Voz configurada").
    let noImeTitle: String
    let noImeBody: String
    let progress: String
    let progressFormat: String
    let ready: String
    let stepMic: String
    let stepServer: String
    let stepTest: String
    let doneMic: String
    let doneServer: String
    let doneTest: String
    let serverMissing: String
    let testHint: String
    let testRun: String
    let testPassed: String
    let testFailed: String
    let openSettings: String

    static let english = SetupStrings(
        tabSetup: "Setup",
        setupStatus: "Status",
        setupServerOnly: "On-device models are experimental and off by default: enable Local under Transcription / "
            + "Text cleanup only after its model pack verifies — otherwise the server chain is used.",
        setupMicYes: "Microphone granted.",
        setupMicNo: "Microphone is required for dictation.",
        setupMicGrant: "Grant microphone",
        setupMicDenied: "Mic permission is required for dictation",
        setupTheme: "Theme",
        setupPrefs: "Preferences",
        actionCancel: "Cancel",
        actionChange: "Change",
        actionGotIt: "Got it",
        actionRefresh: "Refresh",
        noImeTitle: "No keyboard setup needed",
        noImeBody: "Unlike Android, iOS does not allow third-party input methods to replace the system keyboard, "
            + "so there are no enable-and-switch steps here — you dictate from inside MyWhisper and paste or share "
            + "the result where you need it.",
        progress: "Setup progress",
        progressFormat: "Setup progress: %d of %d",
        ready: "You're ready to dictate.",
        stepMic: "1 · Microphone",
        stepServer: "2 · Speech server",
        stepTest: "3 · Test dictation",
        doneMic: "Microphone granted",
        doneServer: "Speech configured",
        doneTest: "Test dictation passed",
        serverMissing: "No speech server yet — add a Self-hosted or Cloud entry under Transcription first.",
        testHint: "Records a short sample and runs it through the server chain.",
        testRun: "Run test dictation",
        testPassed: "Test dictation worked.",
        testFailed: "Test dictation failed.",
        openSettings: "Open Settings"
    )

    static let spanish = SetupStrings(
        tabSetup: "Configuración",
        setupStatus: "Estado",
        setupServerOnly: "Los modelos locales son experimentales y están apagados por defecto: activa Local en "
            + "Transcripción / Edición solo cuando su pack verifique — si no, se usa la cadena del servidor.",
        setupMicYes: "Micrófono concedido.",
        setupMicNo: "El micrófono es necesario para dictar.",
        setupMicGrant: "Conceder micrófono",
        setupMicDenied: "El permiso del micrófono es necesario para dictar",
        setupTheme: "Tema",
        setupPrefs: "Preferencias",
        actionCancel: "Cancelar",
        actionChange: "Cambiar",
        actionGotIt: "Entendido",
        actionRefresh: "Actualizar",
        noImeTitle: "Sin configuración de teclado",
        noImeBody: "A diferencia de Android, iOS no permite que métodos de entrada de terceros reemplacen el "
            + "teclado del sistema, así que aquí no hay pasos de activación — dictas dentro de MyWhisper y pegas o "
            + "compartes el resultado donde lo necesites.",
        progress: "Progreso de configuración",
        progressFormat: "Progreso de configuración: %d de %d",
        ready: "Ya puedes dictar.",
        stepMic: "1 · Micrófono",
        stepServer: "2 · Servidor de voz",
        stepTest: "3 · Probar dictado",
        doneMic: "Micrófono concedido",
        doneServer: "Voz configurada",
        doneTest: "Prueba de dictado correcta",
        serverMissing: "Aún no hay servidor de voz — añade una entrada Autohospedada o en la Nube en Transcripción.",
        testHint: "Graba una muestra corta y la pasa por la cadena del servidor.",
        testRun: "Probar dictado",
        testPassed: "La prueba de dictado funcionó.",
        testFailed: "La prueba de dictado falló.",
        openSettings: "Abrir Ajustes"
    )

    /// `languageCode` is `Locale.current.languageCode` at call sites;
    /// explicit here so tests pin both languages.
    static func current(languageCode: String?) -> SetupStrings {
        languageCode == "es" ? .spanish : .english
    }

    /// Localized "Setup progress: 2 of 3" header.
    func progressTitle(done: Int, total: Int) -> String {
        String(format: progressFormat, done, total)
    }
}
