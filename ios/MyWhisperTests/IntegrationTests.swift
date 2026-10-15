import SwiftUI
import XCTest
@testable import MyWhisper

/// T10 wiring contracts: the Record→Result→Delivery path and the tab root,
/// pinned at the pure layer. No mic, no core, no network — every test here
/// runs in the simulator (and most on any host).
final class IntegrationTests: XCTestCase {
    // MARK: - Tab root covers every merged screen

    func testAllTabsPresentInFlowOrder() {
        XCTAssertEqual(AppTab.allCases.count, 7)
        XCTAssertEqual(AppTab.allCases.first, .record)
        XCTAssertEqual(
            AppTab.allCases,
            [.record, .setup, .speech, .cleanup, .dictionary, .history, .devices]
        )
    }

    // MARK: - Record→Result: silence never renders red

    func testSilenceFailureDisplaysEmptyNeverError() {
        let failure = mapSttError("utterance too short: silence")
        XCTAssertEqual(failure.category, .routineDrop)
        XCTAssertEqual(
            ResultView.displayContent(for: .failure(failure)),
            .empty
        )
    }

    func testRealFailureStillDisplaysError() {
        let failure = mapSttError("ws connect: connection refused")
        XCTAssertEqual(failure.category, .network)
        let shown = ResultView.displayContent(for: .failure(failure))
        XCTAssertEqual(shown, .failure(failure))
    }

    func testResultContentRoundTripsText() {
        let content = ResultView.Content.result(text: "hello", engine: "")
        if case .result(let text, _) = content {
            XCTAssertEqual(text, "hello")
        } else {
            XCTFail("expected result content")
        }
    }

    // MARK: - Delivery guards: blank input copies/shares nothing

    func testBlankTranscriptSharesNothing() {
        XCTAssertTrue(Delivery.shareItems(for: "   ").isEmpty)
        XCTAssertFalse(Delivery.copyToClipboard("  "))
    }

    func testNonBlankTranscriptSharesText() {
        let items = Delivery.shareItems(for: "  hello  ")
        XCTAssertEqual(items.count, 1)
        XCTAssertEqual(items.first as? String, "hello")
    }

    // MARK: - Setup gate: Local-skipped chain is not "configured" (#40)

    func testLocalOnlyChainIsNotServerConfigured() {
        let settings: [String: Any] = [
            "stt": ["entries": [["kind": "Local", "model": "tiny", "enabled": true]]],
        ]
        XCTAssertFalse(isServerConfigured(settings: settings))
    }

    func testCloudEntryWithModelIsServerConfigured() {
        let settings: [String: Any] = [
            "stt": ["entries": [["kind": "Cloud", "model": "whisper-1", "enabled": true]]],
        ]
        XCTAssertTrue(isServerConfigured(settings: settings))
    }

    func testSetupChecklistUnblocksInMicServerTestOrder() {
        let cloud = ["stt": ["entries": [["kind": "Cloud", "model": "whisper-1"]]]]
        var checklist = setupChecklist(micGranted: false, settings: cloud, testPassed: true)
        XCTAssertEqual(checklist.nextStep, .mic)
        checklist = setupChecklist(micGranted: true, settings: cloud, testPassed: false)
        XCTAssertEqual(checklist.nextStep, .test)
        checklist = setupChecklist(micGranted: true, settings: cloud, testPassed: true)
        XCTAssertTrue(checklist.isReady)
    }

    // MARK: - Strings + theme resolve without .lproj infrastructure

    func testStringTwinsResolveByLanguageCode() {
        XCTAssertEqual(SttStrings.current(languageCode: nil), .english)
        XCTAssertEqual(SttStrings.current(languageCode: "es"), .spanish)
        XCTAssertEqual(SetupStrings.current(languageCode: nil), .english)
        XCTAssertEqual(SetupStrings.current(languageCode: "es"), .spanish)
    }

    func testThemeParsesCoreSpellingAndFailsOpen() {
        XCTAssertEqual(AppTheme.parse("Dark"), .dark)
        XCTAssertEqual(AppTheme.parse(nil), .system)
        XCTAssertEqual(AppTheme.parse("bogus"), .system)
        XCTAssertNil(AppTheme.system.colorScheme)
    }

    // MARK: - Theme persists via preferences.theme (write-back, not local)

    func testThemeWriteTransformRoundTripsThroughSettings() {
        let base: [String: Any] = ["preferences": ["theme": "System", "mic_mode": "Smart"]]
        let updated = applyingTheme(.dark, to: base)
        // Picked theme reads back exactly (this is what applyTheme persists).
        XCTAssertEqual(AppTheme.from(settings: updated), .dark)
        // Unrelated prefs survive the read-modify-write.
        let prefs = updated["preferences"] as? [String: Any]
        XCTAssertEqual(prefs?["mic_mode"] as? String, "Smart")
    }

    func testThemeWriteTransformCreatesMissingPreferences() {
        let updated = applyingTheme(.light, to: [:])
        XCTAssertEqual(AppTheme.from(settings: updated), .light)
    }

    // MARK: - Tabs: every case has a label in both languages

    func testEveryTabHasTitleAndIcon() {
        for tab in AppTab.allCases {
            XCTAssertFalse(tab.title(using: .english).isEmpty)
            XCTAssertFalse(tab.title(using: .spanish).isEmpty)
            XCTAssertFalse(tab.systemImage.isEmpty)
        }
        XCTAssertEqual(AppTab.record.title(using: .english), "Record")
        XCTAssertEqual(AppTab.setup.title(using: .spanish), "Configuración")
        XCTAssertEqual(TabStrings.current(languageCode: nil), .english)
        XCTAssertEqual(TabStrings.current(languageCode: "es"), .spanish)
    }
}
