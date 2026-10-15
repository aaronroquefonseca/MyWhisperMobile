import SwiftUI

/// T8 Devices screen: manual sync management, foreground only.
///
/// Mirrors the Android Devices screen sections — status, trusted,
/// pairing requests, nearby, add-by-ID — against the same core
/// verdicts (`net.rs` responder: `{"ok":true}` or `{"error":"…"}`).
/// Every core round trip goes through `SyncCore`; while the iOS
/// bridge has no sync FFI the actions surface `SyncError.unavailable`
/// instead of failing silently. LAN browsing runs only while this
/// view is visible (`onAppear`/`onDisappear`); no background sync —
/// T10 owns `BGTask`.
///
/// Concurrency: Swift 5.0 mode (no async/await) — core rounds run on
/// a worker queue and all state commits hop to main, mirroring
/// Android `workers.execute` + `runOnUiThread` and the
/// `Transcription.swift` deliver-on-main pattern.
struct DevicesView: View {
    var core: SyncCore = UnavailableSyncCore()
    @StateObject private var store = DiscoveryStore()

    @State private var status: SyncStatus?
    @State private var peers: [SyncPeer] = []
    @State private var notes: [String: String] = [:]
    @State private var busy = false
    @State private var banner: String?
    @State private var addNodeId = ""
    @State private var addAddress = ""
    @State private var addName = ""

    private let workers = DispatchQueue(label: "devices-sync", qos: .userInitiated)

    init(core: SyncCore = UnavailableSyncCore()) {
        self.core = core
    }

    private var rows: [SyncPeer] {
        SyncDiscovery.mergedRows(storePeers: peers, discovered: store.discovery.discovered)
    }

    var body: some View {
        List {
            Section("Status") {
                statusRow
                HStack {
                    Button("Start", action: startTapped)
                        .disabled(busy || (status?.running ?? false))
                    Button("Stop", action: stopTapped)
                        .disabled(busy || !(status?.running ?? false))
                    Spacer()
                    Button(busy ? "Syncing…" : "Sync now", action: syncAllTapped)
                        .disabled(busy || !(status?.running ?? false))
                }
                if let reports = status?.reports, !reports.isEmpty {
                    Text(reports.joined(separator: "\n"))
                        .font(.system(.caption, design: .monospaced))
                }
                if let advertiseError = store.discovery.publishError {
                    Text(advertiseError).font(.footnote)
                }
            }
            trustedSection
            requestsSection
            nearbySection
            addSection
            if let banner = banner {
                Section { Text(banner).font(.footnote) }
            }
        }
        .navigationTitle("Devices")
        .onAppear {
            store.discovery.startBrowsing()
            pullState()
        }
        .onDisappear {
            store.discovery.stopBrowsing()
        }
    }

    // MARK: - Sections

    private var statusRow: some View {
        Group {
            if let status = status {
                LabeledContent("Running", value: status.running ? "yes" : "no")
                LabeledContent("Device ID", value: status.nodeId.isEmpty ? "—" : status.nodeId)
                    .textSelection(.enabled)
                LabeledContent("Port", value: String(status.port))
                LabeledContent("LAN IP", value: status.lanIp.isEmpty ? "—" : status.lanIp)
            } else {
                Text("status unavailable — core sync FFI not exposed yet")
                    .font(.footnote)
            }
        }
    }

    private var trustedSection: some View {
        let trusted = SyncSections.split(rows).trusted
        return Section("Trusted (\(trusted.count))") {
            if trusted.isEmpty {
                Text("No trusted devices yet.").font(.footnote)
            }
            ForEach(trusted, id: \.nodeId) { peer in
                VStack(alignment: .leading) {
                    Text(peer.displayName)
                    Text(peer.nodeId).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                    if let note = notes[peer.nodeId] {
                        Text(note).font(.footnote)
                    }
                    HStack {
                        Button("Sync") { syncOneTapped(peer) }.disabled(busy)
                        Button("Remove") { removeTapped(peer) }.disabled(busy)
                    }
                }
            }
        }
    }

    private var requestsSection: some View {
        let requests = SyncSections.split(rows).requests
        return Section("Pairing requests (\(requests.count))") {
            if requests.isEmpty {
                Text("None.").font(.footnote)
            }
            ForEach(requests, id: \.nodeId) { peer in
                VStack(alignment: .leading) {
                    Text(peer.displayName)
                    Text(peer.nodeId).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                    if let note = notes[peer.nodeId] {
                        Text(note).font(.footnote)
                    }
                    HStack {
                        Button("Accept") { acceptTapped(peer) }.disabled(busy)
                        Button("Reject") { removeTapped(peer) }.disabled(busy)
                    }
                }
            }
        }
    }

    private var nearbySection: some View {
        let nearby = SyncSections.split(rows).nearby
        return Section("Nearby (\(nearby.count))") {
            Text("Discovered on the LAN. Request pairing, then accept on the other device (works with desktops and other phones).")
                .font(.footnote)
            if nearby.isEmpty {
                Text("Nobody nearby.").font(.footnote)
            }
            ForEach(nearby, id: \.nodeId) { peer in
                VStack(alignment: .leading) {
                    Text(peer.displayName)
                    Text(peer.nodeId).font(.system(.caption, design: .monospaced)).textSelection(.enabled)
                    if let note = notes[peer.nodeId] {
                        Text(note).font(.footnote)
                    }
                    Button("Request") { requestTapped(peer) }.disabled(busy)
                }
            }
            Button("Refresh", action: pullState).disabled(busy)
        }
    }

    private var addSection: some View {
        Section("Add device manually") {
            Text("Copy the Device ID from MyWhisper on your other device. Send a pairing request, then accept it there.")
                .font(.footnote)
            TextField("Device ID (64 hex chars)", text: $addNodeId)
                .font(.system(.caption, design: .monospaced))
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            TextField("Address (optional, ip:port)", text: $addAddress)
                .font(.system(.caption, design: .monospaced))
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
            TextField("Name (optional)", text: $addName)
            Button("Send pairing request", action: addTapped).disabled(busy)
            if let note = notes["__add"] {
                Text(note).font(.footnote)
            }
        }
    }

    // MARK: - Actions (worker rounds, main commits)

    private func startTapped() {
        busy = true
        banner = nil
        workers.async { [weak self] in
            guard let strong = self else { return }
            do {
                _ = try strong.core.startSync()
            } catch {
                strong.mainFail(error)
                return
            }
            strong.mainPull()
        }
    }

    private func stopTapped() {
        busy = true
        banner = nil
        workers.async { [weak self] in
            guard let strong = self else { return }
            strong.core.stopSync()
            strong.mainPull()
        }
    }

    private func syncAllTapped() {
        busy = true
        banner = nil
        workers.async { [weak self] in
            guard let strong = self else { return }
            let note: String
            do {
                let lines = try strong.core.syncAllNow()
                if lines.isEmpty {
                    note = "done"
                } else {
                    note = lines.joined(separator: "\n")
                }
            } catch {
                strong.mainFail(error)
                return
            }
            let final = note
            DispatchQueue.main.async { [weak self] in
                self?.banner = final
                self?.pullState()
            }
        }
    }

    private func syncOneTapped(_ peer: SyncPeer) {
        busy = true
        banner = nil
        workers.async { [weak self] in
            guard let strong = self else { return }
            let note: String
            do {
                note = try strong.core.syncNow(nodeId: peer.nodeId)
            } catch {
                note = "sync failed: \(strong.userNote(for: error))"
            }
            let final = note
            DispatchQueue.main.async { [weak self] in
                self?.notes[peer.nodeId] = final
                self?.pullState()
            }
        }
    }

    private func removeTapped(_ peer: SyncPeer) {
        busy = true
        banner = nil
        workers.async { [weak self] in
            guard let strong = self else { return }
            do {
                _ = try strong.core.setTrust(nodeId: peer.nodeId, accept: false)
            } catch {
                strong.mainFail(error)
                return
            }
            strong.mainPull()
        }
    }

    /// Android `acceptPeer` parity: trust, then an immediate round so
    /// both sides converge without a second tap. A post-trust dial
    /// failure is the usual "they haven't accepted us yet" case, so
    /// it reads as waiting — not as an error.
    private func acceptTapped(_ peer: SyncPeer) {
        busy = true
        banner = nil
        workers.async { [weak self] in
            guard let strong = self else { return }
            let trustMessage: String
            do {
                trustMessage = try strong.core.setTrust(nodeId: peer.nodeId, accept: true)
            } catch {
                strong.mainFail(error)
                return
            }
            let note: String
            do {
                note = try strong.core.syncNow(nodeId: peer.nodeId)
            } catch {
                note = trustMessage + "; " + strong.waitingNote(for: error, peer: peer)
            }
            let final = note
            DispatchQueue.main.async { [weak self] in
                self?.notes[peer.nodeId] = final
                self?.pullState()
            }
        }
    }

    private func requestTapped(_ peer: SyncPeer) {
        requestPeer(nodeId: peer.nodeId, address: peer.dialTarget, name: peer.displayName, key: peer.nodeId)
    }

    private func addTapped() {
        guard isValidNodeId(addNodeId) else {
            notes["__add"] = nodeIdHint
            return
        }
        requestPeer(
            nodeId: normalizeNodeId(addNodeId),
            address: addAddress.trimmingCharacters(in: .whitespacesAndNewlines),
            name: addName.trimmingCharacters(in: .whitespacesAndNewlines),
            key: "__add"
        )
    }

    /// Manual pairing (`sync_add` parity): park the row and dial once.
    /// A mutual round means both sides already consented, so promote
    /// to trusted like Android does on the `"synced"` prefix.
    private func requestPeer(nodeId: String, address: String, name: String, key: String) {
        busy = true
        banner = nil
        workers.async { [weak self] in
            guard let strong = self else { return }
            let note: String
            do {
                let message = try strong.core.addPeer(nodeId: nodeId, address: address, name: name)
                if message.hasPrefix("synced") {
                    do {
                        note = try strong.core.setTrust(nodeId: nodeId, accept: true)
                    } catch {
                        note = message
                    }
                } else {
                    note = message
                }
            } catch {
                note = strong.userNote(for: error)
            }
            let final = note
            DispatchQueue.main.async { [weak self] in
                self?.notes[key] = final
                self?.pullState()
            }
        }
    }

    /// Fetch status + peers on the worker, commit on main. Also drives
    /// foreground advertising from the reported id/port (no-op until
    /// the bridge exposes status) and always records the own id so
    /// self-observations are filtered.
    private func pullState() {
        busy = true
        workers.async { [weak self] in
            guard let strong = self else { return }
            var nextStatus: SyncStatus?
            var nextPeers: [SyncPeer] = []
            var deviceName = ""
            do {
                nextStatus = try strong.core.syncStatus()
            } catch {
                nextStatus = nil
            }
            do {
                nextPeers = try strong.core.syncPeers()
            } catch {
                nextPeers = []
            }
            deviceName = strong.core.displayName()
            let status = nextStatus
            let peers = nextPeers
            let name = deviceName
            DispatchQueue.main.async { [weak self] in
                guard let strong = self else { return }
                strong.status = status
                strong.peers = peers
                strong.applyAdvertise(status: status, name: name)
                strong.busy = false
            }
        }
    }

    private func applyAdvertise(status: SyncStatus?, name: String) {
        guard let status = status, status.running, status.port > 0, !status.nodeId.isEmpty else {
            store.discovery.unpublish()
            store.discovery.ownNodeId = ""
            return
        }
        store.discovery.ownNodeId = status.nodeId
        store.discovery.publish(nodeId: status.nodeId, name: name, port: Int32(status.port))
    }

    private func mainFail(_ error: Error) {
        DispatchQueue.main.async { [weak self] in
            guard let strong = self else { return }
            strong.banner = strong.userNote(for: error)
            strong.busy = false
        }
    }

    private func mainPull() {
        DispatchQueue.main.async { [weak self] in
            self?.pullState()
        }
    }

    private func waitingNote(for error: Error, peer: SyncPeer) -> String {
        if let sync = error as? SyncError {
            if case .coreError(let message) = sync {
                let verdict = SyncVerdict.classify(error: message)
                if verdict == .pairingRequestSent {
                    return "waiting for \(peer.displayName) — accept on that device"
                }
                return verdict.note
            }
        }
        return userNote(for: error)
    }

    private func userNote(for error: Error) -> String {
        if let sync = error as? SyncError {
            switch sync {
            case .unavailable(let symbol):
                return "sync unavailable on iOS yet (missing bridge: \(symbol))"
            case .coreError(let message):
                return SyncVerdict.classify(error: message).note
            case .badReply(let message):
                return message
            }
        }
        return error.localizedDescription
    }
}

/// Binds `SyncDiscovery.onChange` to SwiftUI refreshes. The view owns
/// browsing through this store: foreground only, no background modes.
final class DiscoveryStore: ObservableObject {
    let discovery = SyncDiscovery()

    init() {
        discovery.onChange = { [weak self] in
            DispatchQueue.main.async { self?.objectWillChange.send() }
        }
    }
}
