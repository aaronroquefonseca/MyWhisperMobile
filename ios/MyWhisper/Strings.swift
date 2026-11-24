import Foundation

/// Setup-screen strings with Spanish twins. Every shared key reuses the
/// Android `values/strings.xml` / `values-es/strings.xml` copy verbatim so
/// both apps say the same things; iOS-only keys (checklist progress and test dictation) are authored here in both languages.
///
/// No `.lproj` infrastructure exists yet and no thread owns it (same
/// situation as `SttStrings` in `Transcription.swift`), so twins live
/// beside the English copy and resolve via an explicit language code —
/// pure and unit-tested. A future localization task moves these to
/// String Catalogs.
struct SetupStrings: Equatable {
    // Android-parity keys (values/strings.xml ↔ values-es/strings.xml).
    let setupMicYes: String
    let setupMicNo: String
    let setupMicGrant: String
    let setupMicDenied: String
    let setupTheme: String
    let actionCancel: String
    let actionChange: String
    // iOS-only keys (no Android counterpart), except doneServer which
    // pins setup_speech_configured ("Speech configured" / "Voz configurada").
    let progressFormat: String
    let ready: String
    let stepMic: String
    let stepServer: String
    let stepTest: String
    let doneMic: String
    let doneServer: String
    let doneTest: String
    let testHint: String
    let testRun: String
    let testPassed: String
    let testFailed: String
    // Android-parity: mic_priority_* / mic_kind_*.
    let micPriority: String
    let micPrioritySub: String
    let micWired: String
    let micBuiltIn: String
    let micBluetooth: String
    let micMoveUp: String
    let micMoveDown: String

    static let english = SetupStrings(
        setupMicYes: "Microphone granted.",
        setupMicNo: "Microphone is required for dictation.",
        setupMicGrant: "Grant microphone",
        setupMicDenied: "Mic permission is required for dictation",
        setupTheme: "Theme",
        actionCancel: "Cancel",
        actionChange: "Change",
        progressFormat: "Setup progress: %d of %d",
        ready: "You're ready to dictate.",
        stepMic: "1 · Microphone",
        stepServer: "2 · Speech server",
        stepTest: "3 · Test dictation",
        doneMic: "Microphone granted",
        doneServer: "Speech configured",
        doneTest: "Test dictation passed",
        testHint: "Open the recorder and dictate a short sample with your speech provider.",
        testRun: "Run test dictation",
        testPassed: "Test dictation worked.",
        testFailed: "Test dictation failed.",
        micPriority: "Preferred microphone",
        micPrioritySub: "Dictation uses the first one that is connected. The built-in microphone is always available, "
            + "so anything below it is never used. Bluetooth switches earbuds to call mode while you dictate.",
        micWired: "Wired and USB",
        micBuiltIn: "Built-in microphone",
        micBluetooth: "Bluetooth",
        micMoveUp: "Move %@ up",
        micMoveDown: "Move %@ down"
    )

    static let spanish = SetupStrings(
        setupMicYes: "Micrófono concedido.",
        setupMicNo: "El micrófono es necesario para dictar.",
        setupMicGrant: "Conceder micrófono",
        setupMicDenied: "El permiso del micrófono es necesario para dictar",
        setupTheme: "Tema",
        actionCancel: "Cancelar",
        actionChange: "Cambiar",
        progressFormat: "Progreso de configuración: %d de %d",
        ready: "Ya puedes dictar.",
        stepMic: "1 · Micrófono",
        stepServer: "2 · Servidor de voz",
        stepTest: "3 · Probar dictado",
        doneMic: "Micrófono concedido",
        doneServer: "Voz configurada",
        doneTest: "Prueba de dictado correcta",
        testHint: "Abre la grabadora y dicta una muestra corta con tu proveedor de voz.",
        testRun: "Probar dictado",
        testPassed: "La prueba de dictado funcionó.",
        testFailed: "La prueba de dictado falló.",
        micPriority: "Micrófono preferido",
        micPrioritySub: "El dictado usa el primero que esté conectado. El micrófono integrado siempre está disponible, "
            + "así que lo que quede debajo nunca se usa. Bluetooth pasa los auriculares a modo llamada mientras dictas.",
        micWired: "Con cable y USB",
        micBuiltIn: "Micrófono integrado",
        micBluetooth: "Bluetooth",
        micMoveUp: "Subir %@",
        micMoveDown: "Bajar %@"
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
