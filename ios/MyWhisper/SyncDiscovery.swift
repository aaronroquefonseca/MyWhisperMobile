import Foundation

/// T8 manual sync + Devices UI (foreground only).
///
/// Same Noise_XX protocol as desktop: the core in `core/src/net.rs`
/// owns the handshake, trust gate, and verdicts; this file owns the
/// iOS-side peer model, verdict mapping, and LAN discovery UI state.
/// No `BGTask` scheduling here — foreground only (T10 owns background).
///
/// Bridge status: `core/src/capple.rs` and `CoreBridge.swift` expose no
/// sync FFI yet (frozen for T8), so every core round trip below goes
/// through `SyncCore`. The app ships `UnavailableSyncCore` until the
/// bridge exposes the eight symbols Android already has — `sync_start`,
/// `sync_stop`, `sync_status`, `sync_peers`, `sync_trust`, `sync_add`,
/// `sync_now`, `sync_all` — at which point a thin `CoreSyncCore`
/// adapter replaces it with zero UI churn. Gaps are reported, not
/// papered over: `SyncError.unavailable` names the missing symbol.

// MARK: - Service identity (mirrors core/src/net.rs)

/// mDNS service type for `NetService` (core `MDNS_SERVICE` is
/// `"_mywhisper._tcp.local."`; `NetService` wants it without `.local`).
let syncServiceType = "_mywhisper._tcp."

/// TXT record keys written by `net::start_mdns`.
let syncTxtNodeKey = "node"
let syncTxtNameKey = "name"

// MARK: - Errors

enum SyncError: Error, Equatable {
    /// Core round trip is unwired: names the missing bridge symbol.
    case unavailable(String)
    case coreError(String)
    case badReply(String)
}

// MARK: - Peer model (mirrors sync_peers_json rows)

/// One row of `sync_ctl::sync_peers_json`: a stored contact annotated
/// with live discovery info, or a discovered-but-unknown candidate.
struct SyncPeer: Equatable {
    var nodeId: String
    var name: String
    var addresses: [String]
    var trusted: Bool
    var pending: Bool
    var introducer: Bool
    var lastSeen: Int64
    var lastSync: Int64
    var discovered: Bool
    var discoveredName: String
    var discoveredAddrs: [String]

    init(dict: [String: Any]) {
        nodeId = dict["nodeId"] as? String ?? ""
        name = dict["name"] as? String ?? ""
        addresses = dict["addresses"] as? [String] ?? []
        trusted = dict["trusted"] as? Bool ?? false
        pending = dict["pending"] as? Bool ?? false
        introducer = dict["introducer"] as? Bool ?? false
        lastSeen = (dict["lastSeen"] as? NSNumber)?.int64Value ?? 0
        lastSync = (dict["lastSync"] as? NSNumber)?.int64Value ?? 0
        discovered = dict["discovered"] as? Bool ?? false
        discoveredName = dict["discoveredName"] as? String ?? ""
        discoveredAddrs = dict["discoveredAddrs"] as? [String] ?? []
    }

    /// Display name with core `short_id` fallback (`first8…last4`).
    var displayName: String {
        if !name.isEmpty { return name }
        return SyncPeer.shortId(nodeId)
    }

    static func shortId(_ nodeId: String) -> String {
        if nodeId.count > 12 {
            let head = nodeId.prefix(8)
            let tail = nodeId.suffix(4)
            return "\(head)…\(tail)"
        }
        return nodeId
    }

    /// First live dial target, preferring fresh discovery observations
    /// over stored addresses (mirrors `sync_peer_now` ordering).
    var dialTarget: String {
        if let addr = discoveredAddrs.first { return addr }
        return addresses.first ?? ""
    }
}

// MARK: - Sections (mirrors Android Devices screen split)

/// Trusted / pairing-request / nearby splits for the Devices UI.
struct SyncSections {
    var trusted: [SyncPeer]
    var requests: [SyncPeer]
    var nearby: [SyncPeer]

    /// Android parity: pending rows are requests; discovered,
    /// untrusted, non-pending rows are nearby candidates.
    static func split(_ peers: [SyncPeer]) -> SyncSections {
        SyncSections(
            trusted: peers.filter { $0.trusted },
            requests: peers.filter { $0.pending },
            nearby: peers.filter { !$0.trusted && !$0.pending && $0.discovered }
        )
    }
}

// MARK: - Node-ID validation (mirrors sync_add gate)

/// Trimmed, lowercased pairing ID as `sync_add` normalizes it.
func normalizeNodeId(_ raw: String) -> String {
    raw.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
}

/// Full 64-hex-char device ID. Error text matches the core gate so the
/// UI can show it verbatim.
func isValidNodeId(_ raw: String) -> Bool {
    let id = normalizeNodeId(raw)
    return id.count == 64 && id.allSatisfy { $0.isHexDigit }
}

let nodeIdHint = "enter the other device's full 64-character hexadecimal ID"

// MARK: - Verdict mapping (mirrors net.rs responder verdicts)

/// User-facing reading of a core sync error.
///
/// The remote `"not trusted: request recorded, …"` verdict IS the
/// delivered pairing request (the responder recorded us as pending
/// before refusing — `net.rs` send path). The *local* pre-dial
/// `"peer is not trusted"` (`sync_ctl.rs` gate) sent nothing and must
/// NOT read as a delivered request — it stays `.rejected`. Only the
/// `"request recorded"` marker proves delivery.
enum SyncVerdict: Equatable {
    /// Pairing request delivered; they accept on the other device.
    case pairingRequestSent
    /// Peer is revoked: refused before any contact (pre-dial gate).
    case revoked
    case syncDisabled
    case syncNotRunning
    /// Peer unreachable: stored address stale / device offline.
    case offline(display: String)
    case rejected(String)
    case ok(String)

    static func classify(error: String) -> SyncVerdict {
        if error.contains("request recorded") { return .pairingRequestSent }
        if error.contains("revoked") { return .revoked }
        if error.contains("sync is disabled") { return .syncDisabled }
        if error.contains("sync is not running") { return .syncNotRunning }
        if error.contains("is the device online") { return .offline(display: error) }
        return .rejected(error)
    }

    /// One-line UI note for a verdict.
    var note: String {
        switch self {
        case .pairingRequestSent:
            return "pairing request sent — accept it on the other device, then sync"
        case .revoked:
            return "peer revoked — refused"
        case .syncDisabled:
            return "sync is disabled in settings"
        case .syncNotRunning:
            return "sync is not running"
        case .offline(let display):
            return display
        case .rejected(let message):
            return message
        case .ok(let message):
            return message
        }
    }
}

// MARK: - Status (mirrors sync_status_json)

struct SyncStatus: Equatable {
    var running: Bool
    var nodeId: String
    var port: Int
    var lanIp: String
    var reports: [String]

    init(dict: [String: Any]) {
        running = dict["running"] as? Bool ?? false
        nodeId = dict["nodeId"] as? String ?? ""
        port = (dict["port"] as? NSNumber)?.intValue ?? 0
        lanIp = dict["lanIp"] as? String ?? ""
        reports = dict["reports"] as? [String] ?? []
    }
}

// MARK: - Core abstraction (bridge seam)

/// Sync round trips. A thin `CoreSyncCore` adapter implementing these
/// against `CoreBridge` lands with the bridge work; until then the app
/// uses `UnavailableSyncCore` and every action reports the gap.
///
/// Threading contract: the UI calls these from the main actor, so
/// implementations must return promptly — blocking dials run on a
/// worker inside the adapter (Android `workers.execute` parity), never
/// on the caller.
protocol SyncCore {
    func syncStatus() throws -> SyncStatus
    func syncPeers() throws -> [SyncPeer]
    func setTrust(nodeId: String, accept: Bool) throws -> String
    func addPeer(nodeId: String, address: String, name: String) throws -> String
    func syncNow(nodeId: String) throws -> String
    func syncAllNow() throws -> [String]
    func startSync() throws -> String
    func stopSync()
    /// Device display name (Android `displayName` parity: "" on failure).
    func displayName() -> String
}

/// Placeholder while the iOS bridge has no sync FFI. Every mutating
/// call names the missing symbol so QA/logs point at the bridge gap,
/// not at the UI.
final class UnavailableSyncCore: SyncCore {
    func syncStatus() throws -> SyncStatus {
        throw SyncError.unavailable("mywhisper_sync_status")
    }

    func syncPeers() throws -> [SyncPeer] {
        throw SyncError.unavailable("mywhisper_sync_peers")
    }

    func setTrust(nodeId _: String, accept _: Bool) throws -> String {
        throw SyncError.unavailable("mywhisper_sync_trust")
    }

    func addPeer(nodeId _: String, address _: String, name _: String) throws -> String {
        throw SyncError.unavailable("mywhisper_sync_add")
    }

    func syncNow(nodeId _: String) throws -> String {
        throw SyncError.unavailable("mywhisper_sync_now")
    }

    func syncAllNow() throws -> [String] {
        throw SyncError.unavailable("mywhisper_sync_all_now")
    }

    func startSync() throws -> String {
        throw SyncError.unavailable("mywhisper_sync_start")
    }

    func stopSync() {}

    func displayName() -> String {
        return ""
    }
}

// MARK: - LAN discovery (foreground only)

/// A live `_mywhisper._tcp.` observation: node id + name from the TXT
/// record, dial targets resolved from the service addresses.
struct DiscoveredPeer: Equatable {
    var nodeId: String
    var name: String
    var addresses: [String]
}

/// Browses `_mywhisper._tcp.` via `NetServiceBrowser` and resolves TXT
/// `node`/`name` records (the same records `net::start_mdns` writes,
/// so desktop and phone find each other).
///
/// Foreground only: the owner starts browsing in `onAppear` and stops
/// in `onDisappear`. No `BGTask`, no background browsing (T10).
/// State fans out through `onChange`; the UI polls the store peers
/// through `SyncCore` and merges with `mergedRows`.
final class SyncDiscovery: NSObject {
    private(set) var discovered: [String: DiscoveredPeer] = [:]
    /// Last advertise failure, if any. Surfaced so the UI can report it.
    private(set) var publishError: String?
    /// Own node id: resolved self-observations are dropped, mirroring
    /// the core (`net.rs` skips its own id in browse results).
    var ownNodeId: String = ""
    var onChange: (() -> Void)?

    private var browser: NetServiceBrowser?
    private var resolving: [NetService] = []
    private var serviceNodes: [String: String] = [:]
    private var ownService: NetService?
    private var published: (nodeId: String, port: Int32)?
    private var browsing = false

    /// Merge store peers with live LAN observations. Discovered-but-
    /// unknown nodes become pairing candidates (mirrors
    /// `sync_peers_json`); fresh discovery addresses are prepended to
    /// known rows so dials prefer live observations.
    static func mergedRows(storePeers: [SyncPeer], discovered: [String: DiscoveredPeer]) -> [SyncPeer] {
        var rows = storePeers.map { peer -> SyncPeer in
            var row = peer
            if let live = discovered[peer.nodeId], !live.addresses.isEmpty {
                var addrs = live.addresses
                for addr in row.discoveredAddrs where !addrs.contains(addr) {
                    addrs.append(addr)
                }
                row.discovered = true
                row.discoveredAddrs = addrs
                if row.discoveredName.isEmpty { row.discoveredName = live.name }
            }
            return row
        }
        for live in discovered.values where !rows.contains(where: { $0.nodeId == live.nodeId }) {
            var row = SyncPeer(dict: [:])
            row.nodeId = live.nodeId
            row.name = live.name
            row.discovered = true
            row.discoveredName = live.name
            row.discoveredAddrs = live.addresses
            rows.append(row)
        }
        return rows.sorted { $0.displayName.lowercased() < $1.displayName.lowercased() }
    }

    func startBrowsing() {
        if browsing { return }
        browsing = true
        let browser = NetServiceBrowser()
        browser.delegate = self
        self.browser = browser
        browser.searchForServices(ofType: syncServiceType, inDomain: "")
    }

    func stopBrowsing() {
        browsing = false
        browser?.stop()
        browser = nil
        for service in resolving {
            service.stop()
        }
        resolving.removeAll()
        discovered.removeAll()
        serviceNodes.removeAll()
        unpublish()
    }

    /// Advertise this device while in the foreground (mirrors the core
    /// `advertise` flag; the core owns this itself once the bridge
    /// lands — until then the UI advertises so two phones see each
    /// other). Idempotent for the same id/port: repeat calls from
    /// state refreshes must not flap mDNS. The instance name is the
    /// 8-char short id exactly like `net::start_mdns` (a full 64-char
    /// node id would blow the 63-byte mDNS label limit).
    func publish(nodeId: String, name: String, port: Int32) {
        if let current = published, current.nodeId == nodeId, current.port == port {
            return
        }
        unpublish()
        publishError = nil
        ownNodeId = nodeId
        let instance = String(nodeId.prefix(8))
        let service = NetService(domain: "", type: syncServiceType, name: instance, port: port)
        service.delegate = self
        ownService = service
        let txt: [String: Data] = [
            syncTxtNodeKey: Data(nodeId.utf8),
            syncTxtNameKey: Data(name.utf8)
        ]
        service.setTXTRecord(NetService.data(fromTXTRecord: txt))
        service.publish()
        published = (nodeId: nodeId, port: port)
    }

    func unpublish() {
        ownService?.stop()
        ownService = nil
        published = nil
    }

    /// NetService delegate callbacks can arrive off the main queue;
    /// all state below is read on main, so hop first.
    private func onMain(_ body: @escaping () -> Void) {
        if Thread.isMainThread {
            body()
        } else {
            DispatchQueue.main.async(execute: body)
        }
    }

    private func note(_ peer: DiscoveredPeer, serviceName: String) {
        serviceNodes[serviceName] = peer.nodeId
        discovered[peer.nodeId] = peer
        onChange?()
    }
}

extension SyncDiscovery: NetServiceBrowserDelegate {
    func netServiceBrowser(
        _: NetServiceBrowser,
        didFind service: NetService,
        moreComing: Bool
    ) {
        onMain { [weak self] in
            guard let strong = self else { return }
            service.delegate = strong
            strong.resolving.append(service)
            service.resolve(withTimeout: 5)
            if !moreComing {
                strong.onChange?()
            }
        }
    }

    func netServiceBrowser(
        _: NetServiceBrowser,
        didRemove service: NetService,
        moreComing: Bool
    ) {
        onMain { [weak self] in
            guard let strong = self else { return }
            strong.resolving.removeAll(where: { $0 === service })
            if let nodeId = strong.serviceNodes.removeValue(forKey: service.name) {
                strong.discovered.removeValue(forKey: nodeId)
            }
            if !moreComing {
                strong.onChange?()
            }
        }
    }
}

extension SyncDiscovery: NetServiceDelegate {
    func netServiceDidResolveAddress(_ sender: NetService) {
        onMain { [weak self] in
            guard let strong = self else { return }
            strong.resolving.removeAll(where: { $0 === sender })
            guard let txt = sender.txtRecordData(),
                let record = NetService.dictionary(fromTXTRecord: txt) else { return }
            guard let nodeData = record[syncTxtNodeKey],
                let nodeId = String(data: nodeData, encoding: .utf8),
                !nodeId.isEmpty,
                nodeId != strong.ownNodeId else { return }
            let name = record[syncTxtNameKey].flatMap { String(data: $0, encoding: .utf8) } ?? ""
            var addresses: [String] = []
            if let addrs = sender.addresses {
                for addr in addrs {
                    if let target = SyncDiscovery.sockAddrDescription(addr, port: sender.port), !target.isEmpty {
                        addresses.append(target)
                    }
                }
            }
            if addresses.isEmpty { return }
            strong.note(DiscoveredPeer(nodeId: nodeId, name: name, addresses: addresses), serviceName: sender.name)
        }
    }

    func netService(_ sender: NetService, didNotResolve _: [String: NSNumber]) {
        onMain { [weak self] in
            guard let strong = self else { return }
            strong.resolving.removeAll(where: { $0 === sender })
        }
    }

    func netService(_ sender: NetService, didNotPublish errorDict: [String: NSNumber]) {
        onMain { [weak self] in
            guard let strong = self else { return }
            let code = errorDict["NSNetServicesErrorCode"]
            if let code = code {
                strong.publishError = "advertise failed (code \(code.intValue))"
            } else {
                strong.publishError = "advertise failed"
            }
            strong.onChange?()
        }
    }

    /// `ip:port` from a resolved sockaddr (v4/v6). Returns nil for
    /// unparseable families so callers drop them like the core does
    /// when a record has no usable address.
    static func sockAddrDescription(_ data: Data, port: Int) -> String? {
        guard data.count >= 2 else { return nil }
        var host = [CChar](repeating: 0, count: Int(NI_MAXHOST))
        let result: Int32 = data.withUnsafeBytes { raw in
            guard let base = raw.baseAddress else { return EAI_FAIL }
            return getnameinfo(
                base.assumingMemoryBound(to: sockaddr.self),
                socklen_t(data.count),
                &host, socklen_t(host.count),
                nil, 0,
                NI_NUMERICHOST
            )
        }
        guard result == 0 else { return nil }
        let ip = String(cString: host)
        if ip.contains(":") { return "[\(ip)]:\(port)" }
        return "\(ip):\(port)"
    }
}
