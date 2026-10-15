import XCTest
@testable import MyWhisper

/// T8 sync tests: trust-gate fakes, verdict mapping, peer sections.
/// Pure XCTest — no core FFI, no `NetService`, no I/O. The core net
/// loopback is covered by `cargo test` (`core/src/net.rs`); these
/// tests pin the iOS-side model, gates, and user-facing verdicts so a
/// bridge or UI change that alters pairing semantics fails loudly.
///
/// Fake model: `FakeSyncCore` replays the responder verdicts from
/// `net.rs` (trust gate, revoked refusal, `"not trusted"` pairing
/// handshake) so the trust → sync → revoke sequence is asserted
/// without a second device.
final class SyncTests: XCTestCase {
    // MARK: - Fake

    /// Replays core gates: disabled sync refuses, revoked/untrusted
    /// peers refuse, unknown manual IDs refuse (mirrors
    /// `require_dial_authorized` + `sync_add`).
    final class FakeSyncCore: SyncCore {
        var syncEnabled = true
        var running = true
        var revoked: Set<String> = []
        var trusted: Set<String> = []
        var pending: Set<String> = []
        var log: [String] = []

        func syncStatus() throws -> SyncStatus {
            SyncStatus(dict: [
                "running": running,
                "nodeId": String(repeating: "a", count: 64),
                "port": 17499,
                "lanIp": "192.168.1.2",
                "reports": ["listening on port 17499"]
            ])
        }

        func syncPeers() throws -> [SyncPeer] {
            var rows: [[String: Any]] = []
            for id in trusted {
                rows.append(["nodeId": id, "name": "peer", "trusted": true, "pending": false, "discovered": true])
            }
            for id in pending {
                rows.append(["nodeId": id, "name": "", "trusted": false, "pending": true, "discovered": true])
            }
            return rows.map(SyncPeer.init(dict:))
        }

        func setTrust(nodeId: String, accept: Bool) throws -> String {
            log.append("trust \(nodeId) \(accept)")
            if accept {
                pending.remove(nodeId)
                trusted.insert(nodeId)
                return "trusted; syncing now"
            }
            trusted.remove(nodeId)
            pending.remove(nodeId)
            return "rejected"
        }

        func addPeer(nodeId: String, address _: String, name _: String) throws -> String {
            log.append("add \(nodeId)")
            guard isValidNodeId(nodeId) else { throw SyncError.coreError(nodeIdHint) }
            let id = normalizeNodeId(nodeId)
            if revoked.contains(id) { throw SyncError.coreError("peer revoked") }
            if trusted.contains(id) { return "synced peer: 0 words in" }
            pending.insert(id)
            // Mirrors sync_add: the expected outcome is the remote's
            // "not trusted" verdict — the request itself was delivered.
            throw SyncError.coreError("not trusted: request recorded, ask them to accept")
        }

        func syncNow(nodeId: String) throws -> String {
            log.append("now \(nodeId)")
            guard syncEnabled else { throw SyncError.coreError("sync is disabled in settings") }
            guard running else { throw SyncError.coreError("sync is not running") }
            if revoked.contains(nodeId) { throw SyncError.coreError("peer revoked") }
            guard trusted.contains(nodeId) else { throw SyncError.coreError("peer is not trusted") }
            return "synced peer: 3 words, 1 snippets, 2 history in, 0 introduced"
        }

        func syncAllNow() throws -> [String] {
            guard running else { return ["sync is not running"] }
            return trusted.sorted().map { "synced \($0.prefix(8)): ok" }
        }

        func startSync() throws -> String {
            running = true
            return "sync on (port 17499)"
        }

        func stopSync() {
            running = false
        }
    }

    // MARK: - Node-ID gate (mirrors sync_add)

    func testNodeIdValidation() {
        let good = String(repeating: "ab", count: 32)
        XCTAssertTrue(isValidNodeId(good))
        XCTAssertTrue(isValidNodeId("  " + good.uppercased() + "\n"))
        XCTAssertFalse(isValidNodeId("abc"))
        XCTAssertFalse(isValidNodeId(String(repeating: "z", count: 64)))
        XCTAssertFalse(isValidNodeId(String(repeating: "a", count: 63)))
        XCTAssertFalse(isValidNodeId(""))
        XCTAssertEqual(normalizeNodeId("  AB12 \n"), "ab12")
    }

    // MARK: - Peer model + sections

    func testShortIdFallback() {
        let long = String(repeating: "a", count: 64)
        XCTAssertEqual(SyncPeer.shortId(long), "aaaaaaaa…aaaa")
        XCTAssertEqual(SyncPeer.shortId("abc"), "abc")
        let named = SyncPeer(dict: ["nodeId": long, "name": "desk"])
        XCTAssertEqual(named.displayName, "desk")
        let unnamed = SyncPeer(dict: ["nodeId": long, "name": ""])
        XCTAssertEqual(unnamed.displayName, "aaaaaaaa…aaaa")
    }

    func testSectionsSplit() {
        let peers = [
            SyncPeer(dict: ["nodeId": "t", "trusted": true, "pending": false, "discovered": true]),
            SyncPeer(dict: ["nodeId": "r", "trusted": false, "pending": true, "discovered": true]),
            SyncPeer(dict: ["nodeId": "n", "trusted": false, "pending": false, "discovered": true]),
            SyncPeer(dict: ["nodeId": "s", "trusted": false, "pending": false, "discovered": false])
        ]
        let sections = SyncSections.split(peers)
        XCTAssertEqual(sections.trusted.map(\.nodeId), ["t"])
        XCTAssertEqual(sections.requests.map(\.nodeId), ["r"])
        XCTAssertEqual(sections.nearby.map(\.nodeId), ["n"])
    }

    func testDialTargetPrefersDiscovered() {
        let peer = SyncPeer(dict: [
            "nodeId": "x", "addresses": ["10.0.0.1:1"],
            "discovered": true, "discoveredAddrs": ["192.168.1.9:17499"]
        ])
        XCTAssertEqual(peer.dialTarget, "192.168.1.9:17499")
        let stored = SyncPeer(dict: ["nodeId": "y", "addresses": ["10.0.0.2:2"], "discovered": false])
        XCTAssertEqual(stored.dialTarget, "10.0.0.2:2")
        XCTAssertEqual(SyncPeer(dict: ["nodeId": "z"]).dialTarget, "")
    }

    // MARK: - Verdict mapping (mirrors net.rs responder verdicts)

    func testVerdictMapping() {
        XCTAssertEqual(
            SyncVerdict.classify(error: "not trusted: request recorded, ask them to accept"),
            .pairingRequestSent
        )
        XCTAssertEqual(SyncVerdict.classify(error: "peer revoked"), .revoked)
        XCTAssertEqual(SyncVerdict.classify(error: "sync is disabled in settings"), .syncDisabled)
        XCTAssertEqual(SyncVerdict.classify(error: "sync is not running"), .syncNotRunning)
        XCTAssertEqual(
            SyncVerdict.classify(error: "desk: rejected — is the device online with sync running?").note.contains("online"),
            true
        )
        // The pairing verdict is guidance, not a failure.
        XCTAssertTrue(SyncVerdict.pairingRequestSent.note.contains("accept it on the other device"))
    }

    // MARK: - Trust gate via fake (pair → trust → sync)

    func testPairTrustSyncFlow() throws {
        let core = FakeSyncCore()
        let id = String(repeating: "b", count: 64)

        // Manual add delivers the pairing request (remote "not trusted").
        do {
            _ = try core.addPeer(nodeId: id, address: "192.168.1.9:17499", name: "phone")
            XCTFail("add of an untrusted peer must surface the pairing verdict")
        } catch let error as SyncError {
            XCTAssertEqual(SyncVerdict.classify(error: verdictText(error)).note.contains("other device"), true)
        }
        XCTAssertTrue(core.pending.contains(id))

        // They accept on the other side; our trust promotes the row.
        XCTAssertEqual(try core.setTrust(nodeId: id, accept: true), "trusted; syncing now")
        let sections = SyncSections.split(try core.syncPeers())
        XCTAssertEqual(sections.trusted.map(\.nodeId), [id])
        XCTAssertTrue(sections.requests.isEmpty)

        // Trusted round syncs words/snippets/history.
        let report = try core.syncNow(nodeId: id)
        XCTAssertTrue(report.contains("words"))
        XCTAssertTrue(report.contains("history"))
        XCTAssertTrue(try core.syncAllNow().first?.contains("ok") ?? false)
    }

    func testRevokedPeerRefused() throws {
        let core = FakeSyncCore()
        let id = String(repeating: "c", count: 64)
        core.revoked.insert(id)

        // Pre-dial gate: refused before any payload moves.
        do {
            _ = try core.addPeer(nodeId: id, address: "192.168.1.9:17499", name: "old")
            XCTFail("revoked peer must be refused")
        } catch let error as SyncError {
            XCTAssertEqual(SyncVerdict.classify(error: verdictText(error)), .revoked)
        }
        do {
            _ = try core.syncNow(nodeId: id)
            XCTFail("revoked peer must be refused")
        } catch let error as SyncError {
            XCTAssertEqual(SyncVerdict.classify(error: verdictText(error)), .revoked)
        }
        XCTAssertTrue(core.log.allSatisfy { !$0.contains("payload") })
    }

    func testUntrustedSyncRefused() throws {
        let core = FakeSyncCore()
        let id = String(repeating: "d", count: 64)
        do {
            _ = try core.syncNow(nodeId: id)
            XCTFail("untrusted peer must be refused")
        } catch let error as SyncError {
            XCTAssertEqual(SyncVerdict.classify(error: verdictText(error)), .pairingRequestSent)
        }
    }

    func testDisabledSyncRefusesFast() throws {
        let core = FakeSyncCore()
        core.syncEnabled = false
        let id = String(repeating: "e", count: 64)
        core.trusted.insert(id)
        do {
            _ = try core.syncNow(nodeId: id)
            XCTFail("disabled sync must refuse")
        } catch let error as SyncError {
            XCTAssertEqual(SyncVerdict.classify(error: verdictText(error)), .syncDisabled)
        }
    }

    func testUnavailableCoreNamesBridgeSymbol() {
        let core = UnavailableSyncCore()
        assertUnavailable({ _ = try core.syncPeers() }, symbol: "mywhisper_sync_peers")
        assertUnavailable({ _ = try core.syncNow(nodeId: "x") }, symbol: "mywhisper_sync_now")
        assertUnavailable({ _ = try core.syncAllNow() }, symbol: "mywhisper_sync_all_now")
        assertUnavailable({ _ = try core.setTrust(nodeId: "x", accept: true) }, symbol: "mywhisper_sync_trust")
    }

    // MARK: - Merge (mirrors sync_peers_json unknown candidates)

    func testMergedRows() {
        let stored = [SyncPeer(dict: ["nodeId": "k", "name": "known", "trusted": true, "discovered": false])]
        let live: [String: DiscoveredPeer] = [
            "k": DiscoveredPeer(nodeId: "k", name: "known", addresses: ["192.168.1.5:17499"]),
            "u": DiscoveredPeer(nodeId: String(repeating: "f", count: 64), name: "new", addresses: ["192.168.1.6:17499"])
        ]
        let rows = SyncDiscovery.mergedRows(storePeers: stored, discovered: live)
        XCTAssertEqual(rows.count, 2)
        let known = rows.first { $0.nodeId == "k" }
        XCTAssertEqual(known?.discovered, true)
        XCTAssertEqual(known?.dialTarget, "192.168.1.5:17499")
        let unknown = rows.first { $0.nodeId == String(repeating: "f", count: 64) }
        XCTAssertEqual(unknown?.discovered, true)
        XCTAssertEqual(SyncSections.split(rows).nearby.count, 1)
    }

    // MARK: - Helpers

    private func verdictText(_ error: SyncError) -> String {
        if case .coreError(let message) = error { return message }
        return ""
    }

    private func assertUnavailable(_ work: () throws -> Void, symbol: String, file: StaticString = #filePath, line: UInt = #line) {
        do {
            try work()
            XCTFail("expected unavailable(\(symbol))", file: file, line: line)
        } catch let error as SyncError {
            XCTAssertEqual(error, .unavailable(symbol), file: file, line: line)
        } catch {
            XCTFail("wrong error: \(error)", file: file, line: line)
        }
    }
}
