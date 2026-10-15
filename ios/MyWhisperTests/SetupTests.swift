import SwiftUI
import XCTest
@testable import MyWhisper

/// Setup checklist state machine, server-configured probe, theme parsing,
/// and the en/es string resolver. Pure XCTest: no mic, no core, no network.
final class SetupTests: XCTestCase {
    // MARK: - Checklist state machine

    func testFreshInstallStartsEmpty() {
        let checklist = setupChecklist(micGranted: false, settings: nil, testPassed: false)
        XCTAssertEqual(checklist.doneCount, 0)
        XCTAssertFalse(checklist.isReady)
        XCTAssertEqual(checklist.nextStep, .mic)
    }

    func testChecklistOrderIsMicThenServerThenTest() {
        var checklist = setupChecklist(micGranted: false, settings: serverSettings(), testPassed: true)
        XCTAssertEqual(checklist.nextStep, .mic)
        XCTAssertFalse(checklist.isReady)

        checklist = setupChecklist(micGranted: true, settings: nil, testPassed: true)
        XCTAssertEqual(checklist.nextStep, .server)
        XCTAssertFalse(checklist.isReady)

        checklist = setupChecklist(micGranted: true, settings: serverSettings(), testPassed: false)
        XCTAssertEqual(checklist.nextStep, .test)
        XCTAssertEqual(checklist.doneCount, 2)
        XCTAssertFalse(checklist.isReady)
    }

    func testChecklistReadyWhenAllDone() {
        let checklist = setupChecklist(micGranted: true, settings: serverSettings(), testPassed: true)
        XCTAssertEqual(checklist.doneCount, 3)
        XCTAssertTrue(checklist.isReady)
        XCTAssertEqual(checklist.nextStep, .done)
    }

    // MARK: - isServerConfigured (Android MainActivity parity)

    func testServerConfiguredNeedsOneUsableRemoteEntry() {
        XCTAssertFalse(isServerConfigured(settings: nil))
        XCTAssertFalse(isServerConfigured(settings: [:]))
        XCTAssertFalse(isServerConfigured(settings: ["stt": ["entries": []]]))
    }

    func testLocalOnlyIsNotServerConfigured() {
        let settings: [String: Any] = ["stt": ["entries": [
            ["kind": "Local", "enabled": true, "model": "whisper"]
        ]]]
        XCTAssertFalse(isServerConfigured(settings: settings))
    }

    func testDisabledEntriesDoNotCount() {
        let settings: [String: Any] = ["stt": ["entries": [
            ["kind": "Cloud", "enabled": false, "model": "whisper-1"]
        ]]]
        XCTAssertFalse(isServerConfigured(settings: settings))
    }

    func testBlankModelDoesNotCount() {
        let settings: [String: Any] = ["stt": ["entries": [
            ["kind": "Cloud", "enabled": true, "model": "  "]
        ]]]
        XCTAssertFalse(isServerConfigured(settings: settings))
    }

    func testSelfHostedNeedsUrl() {
        let noURL: [String: Any] = ["stt": ["entries": [
            ["kind": "SelfHosted", "enabled": true, "model": "whisper-1", "url": ""]
        ]]]
        XCTAssertFalse(isServerConfigured(settings: noURL))
        let withURL: [String: Any] = ["stt": ["entries": [
            ["kind": "SelfHosted", "enabled": true, "model": "whisper-1", "url": "http://127.0.0.1:8080"]
        ]]]
        XCTAssertTrue(isServerConfigured(settings: withURL))
    }

    func testCloudWithModelCountsBlankUrlMeansVendorDefault() {
        let settings: [String: Any] = ["stt": ["entries": [
            ["kind": "Cloud", "enabled": true, "model": "whisper-1", "url": ""]
        ]]]
        XCTAssertTrue(isServerConfigured(settings: settings))
    }

    func testMissingEnabledDefaultsToTrue() {
        let settings: [String: Any] = ["stt": ["entries": [
            ["kind": "Cloud", "model": "whisper-1"]
        ]]]
        XCTAssertTrue(isServerConfigured(settings: settings))
    }

    // MARK: - Theme parsing (preferences.theme)

    func testThemeParseDefaultsToSystem() {
        XCTAssertEqual(AppTheme.parse(nil), .system)
        XCTAssertEqual(AppTheme.parse("Bogus"), .system)
        XCTAssertEqual(AppTheme.parse("System"), .system)
        XCTAssertEqual(AppTheme.parse("Light"), .light)
        XCTAssertEqual(AppTheme.parse("Dark"), .dark)
    }

    func testThemeFromSettings() {
        XCTAssertEqual(AppTheme.from(settings: nil), .system)
        XCTAssertEqual(AppTheme.from(settings: [:]), .system)
        XCTAssertEqual(AppTheme.from(settings: ["preferences": ["theme": "Dark"]]), .dark)
        XCTAssertEqual(AppTheme.from(settings: ["preferences": ["theme": "Light"]]), .light)
    }

    func testThemeColorSchemeMapping() {
        XCTAssertNil(AppTheme.system.colorScheme)
        XCTAssertEqual(AppTheme.light.colorScheme, .light)
        XCTAssertEqual(AppTheme.dark.colorScheme, .dark)
    }

    // MARK: - Locale resolver (Android values/values-es parity)

    func testSetupStringsDefaultToEnglish() {
        XCTAssertEqual(SetupStrings.current(languageCode: nil), .english)
        XCTAssertEqual(SetupStrings.current(languageCode: "en"), .english)
        XCTAssertEqual(SetupStrings.current(languageCode: "fr"), .english)
    }

    func testSetupStringsSpanish() {
        let strings = SetupStrings.current(languageCode: "es")
        XCTAssertEqual(strings, .spanish)
        XCTAssertEqual(strings.tabSetup, "Configuración")
        XCTAssertEqual(strings.setupMicGrant, "Conceder micrófono")
        XCTAssertEqual(strings.setupTheme, "Tema")
    }

    func testSetupStringsMirrorAndroidKeys() {
        // Spot-check the shared keys against values/strings.xml copy.
        XCTAssertEqual(SetupStrings.english.setupMicYes, "Microphone granted.")
        XCTAssertEqual(SetupStrings.english.setupMicNo, "Microphone is required for dictation.")
        XCTAssertEqual(SetupStrings.english.setupMicDenied, "Mic permission is required for dictation")
        XCTAssertEqual(SetupStrings.spanish.setupMicYes, "Micrófono concedido.")
        XCTAssertEqual(SetupStrings.spanish.setupMicNo, "El micrófono es necesario para dictar.")
        XCTAssertEqual(SetupStrings.spanish.setupMicDenied, "El permiso del micrófono es necesario para dictar")
        XCTAssertEqual(SetupStrings.english.setupTheme, "Theme")
        XCTAssertEqual(SetupStrings.spanish.setupTheme, "Tema")
        XCTAssertEqual(SetupStrings.english.setupPrefs, "Preferences")
        XCTAssertEqual(SetupStrings.spanish.setupPrefs, "Preferencias")
        // Checklist server row pins setup_speech_configured (PR #54 renamed
        // the hardcoded "Speech server configured" copy).
        XCTAssertEqual(SetupStrings.english.doneServer, "Speech configured")
        XCTAssertEqual(SetupStrings.spanish.doneServer, "Voz configurada")
    }

    func testSetupStringsDocumentNoIme() {
        // Both locales must explain why there are no IME steps.
        for strings in [SetupStrings.english, SetupStrings.spanish] {
            XCTAssertFalse(strings.noImeTitle.isEmpty)
            XCTAssertFalse(strings.noImeBody.isEmpty)
        }
        XCTAssertTrue(SetupStrings.english.noImeBody.contains("iOS"))
        XCTAssertTrue(SetupStrings.spanish.noImeBody.contains("iOS"))
    }

    func testProgressTitleLocalizesCount() {
        XCTAssertEqual(SetupStrings.english.progressTitle(done: 2, total: 3), "Setup progress: 2 of 3")
        XCTAssertEqual(SetupStrings.spanish.progressTitle(done: 2, total: 3), "Progreso de configuración: 2 de 3")
    }

    // MARK: - Helpers

    private func serverSettings() -> [String: Any] {
        ["stt": ["entries": [
            ["kind": "Cloud", "enabled": true, "model": "whisper-1"]
        ]]]
    }
}
