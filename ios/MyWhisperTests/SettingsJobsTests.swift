import XCTest
@testable import MyWhisper

/// Port of Android `SettingsJobsTest` (pure logic, no core/FFI).
/// T2's dictation workers reuse `SettingsJobs` for cancel-on-config-change.
final class SettingsJobsTests: XCTestCase {
    override func setUp() {
        CoreBridge.SettingsJobs.resetForTests()
    }

    // MARK: - Shared epoch (CHECK_EPOCH parity)

    func testClaimIsCurrentLatestOnly() {
        let first = CoreBridge.SettingsJobs.claim()
        XCTAssertTrue(CoreBridge.SettingsJobs.isCurrent(first))
        let second = CoreBridge.SettingsJobs.claim()
        XCTAssertTrue(CoreBridge.SettingsJobs.isCurrent(second))
        XCTAssertFalse(CoreBridge.SettingsJobs.isCurrent(first), "older check cannot replace a newer one")
    }

    func testInvalidateDropsInflight() {
        let gen = CoreBridge.SettingsJobs.claim()
        CoreBridge.SettingsJobs.invalidate()
        XCTAssertFalse(CoreBridge.SettingsJobs.isCurrent(gen))
    }

    func testZeroGenerationNeverCurrent() {
        _ = CoreBridge.SettingsJobs.claim()
        XCTAssertFalse(CoreBridge.SettingsJobs.isCurrent(0))
    }

    // MARK: - Per-screen generation guard (ptr_eq parity)

    func testJobAppliesOnlyEqualGenerations() {
        XCTAssertTrue(CoreBridge.SettingsJobs.jobApplies(currentGen: 3, jobGen: 3))
        XCTAssertFalse(CoreBridge.SettingsJobs.jobApplies(currentGen: 3, jobGen: 2))
        XCTAssertFalse(CoreBridge.SettingsJobs.jobApplies(currentGen: 2, jobGen: 3))
    }

    func testNextJobGenIncrementsAndSkipsZero() {
        XCTAssertEqual(CoreBridge.SettingsJobs.nextJobGen(0), 1)
        XCTAssertEqual(CoreBridge.SettingsJobs.nextJobGen(3), 4)
        XCTAssertEqual(CoreBridge.SettingsJobs.nextJobGen(-1), 1)
        XCTAssertEqual(CoreBridge.SettingsJobs.nextJobGen(Int64.max), 1)
    }

    func testStaleWorkerDropsCommitNewerWins() {
        var current: Int64 = 0
        current = CoreBridge.SettingsJobs.nextJobGen(current)
        let first = current
        current = CoreBridge.SettingsJobs.nextJobGen(current)
        let second = current
        XCTAssertFalse(CoreBridge.SettingsJobs.jobApplies(currentGen: current, jobGen: first))
        XCTAssertTrue(CoreBridge.SettingsJobs.jobApplies(currentGen: current, jobGen: second))
    }

    // MARK: - Committed-state retry (reset.incomplete parity)

    func testResetNeedsRetryOnlyIncompleteWarning() {
        XCTAssertTrue(CoreBridge.SettingsJobs.resetNeedsRetry(warning: "settings.reset.incomplete"))
        XCTAssertFalse(CoreBridge.SettingsJobs.resetNeedsRetry(warning: nil))
        XCTAssertFalse(CoreBridge.SettingsJobs.resetNeedsRetry(warning: ""))
        XCTAssertFalse(CoreBridge.SettingsJobs.resetNeedsRetry(warning: "settings.reset.failed"))
    }
}
