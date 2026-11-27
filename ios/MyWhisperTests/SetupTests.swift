import AVFoundation
import SwiftUI
import XCTest
@testable import MyWhisper

/// Setup checklist state machine, server-configured probe, theme parsing,
/// and the en/es string resolver. Pure XCTest: no mic, no core, no network.
final class SetupTests: XCTestCase {
    // MARK: - Setup checklist

    func testSetupNeedsKeyboardAndMicrophoneToContinue() {
        var state = SetupState(keyboardAdded: true, fullAccess: false, micGranted: false, speechReady: true)
        XCTAssertFalse(state.requiredDone)
        state.micGranted = true
        XCTAssertTrue(state.requiredDone)
        XCTAssertFalse(state.isComplete)
        XCTAssertEqual(state.doneCount, 3)
        state.fullAccess = true
        XCTAssertTrue(state.isComplete)
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

    func testKeyboardInstallPathInBothLanguages() {
        XCTAssertTrue(OnboardingStrings.english.installPath.contains("Keyboards → Add New Keyboard"))
        XCTAssertTrue(OnboardingStrings.spanish.installPath.contains("Teclados → Añadir nuevo teclado"))
    }

    // MARK: - Helpers

    private func serverSettings() -> [String: Any] {
        ["stt": ["entries": [
            ["kind": "Cloud", "enabled": true, "model": "whisper-1"]
        ]]]
    }

    // MARK: - Mic priority

    func testMicPriorityNormalizesAndPicksFirstConnectedKind() {
        XCTAssertEqual(MicRoute.priority(from: nil), ["Wired", "BuiltIn", "Bluetooth", "CarPlay"])
        let saved = MicRoute.applying(["Bluetooth", "Mystery", "Bluetooth"], to: [:])
        let order = MicRoute.priority(from: saved)
        XCTAssertEqual(order, ["Bluetooth", "Wired", "BuiltIn", "CarPlay"])
        XCTAssertEqual(MicRoute.kind(of: .carAudio), "CarPlay")
        let ports: [AVAudioSession.Port] = [.builtInMic, .bluetoothHFP, .usbAudio]
        XCTAssertEqual(MicRoute.pick(priority: order, items: ports) { MicRoute.kind(of: $0) }, .bluetoothHFP)
        XCTAssertEqual(MicRoute.pick(priority: MicRoute.defaults, items: ports) { MicRoute.kind(of: $0) }, .usbAudio)
        XCTAssertNil(MicRoute.pick(priority: ["Bluetooth"], items: [AVAudioSession.Port.builtInMic]) { MicRoute.kind(of: $0) })
    }
}
