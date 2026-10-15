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
struct DevicesView: View {
    var core: SyncCore = UnavailableSyncCore()
    @StateObject private var store = DiscoveryStore()

    init(core: SyncCore = UnavailableSyncCore()) {
        self.core = core
    }

    @State private var status: SyncStatus?
    @State private var peers: [SyncPeer] = []
    @State private var notes: [String: String] = [:]
    @State private var busy = false
    @State private var banner: String?
    @State private var addNodeId = ""
    @State private var addAddress = ""
    @State private var addName = ""

    private var rows: [SyncPeer] {
        SyncDiscovery.mergedRows(storePeers: peers, discovered: store.discovery.discovered)
    }

    var body: some View {
        List {
            Section("Status") {
                statusRow
                HStack {
                    Button("Start") {
                        run {
                            _ = try core.startSync()
                            await refresh()
                        }
                    }
                    .disabled(busy || (status?.running ?? false))
                    Button("Stop") {
                        run {
                            core.stopSync()
                            await refresh()
                        }
                    }
                    .disabled(busy || !(status?.running ?? false))
                    Spacer()
                    Button(busy ? "Syncing…" : "Sync now") { run { try await syncAll() } }
                        .disabled(busy || !(status?.running ?? false))
                }
                if let reports = status?.reports, !reports.isEmpty {
                    Text(reports.joined(separator: "\n"))
                        .font(.system(.caption, design: .monospaced))
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
            run { await refresh() }
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
                        Button("Sync") { run { try await syncOne(peer) } }.disabled(busy)
                        Button("Remove") {
                            run {
                                _ = try core.setTrust(nodeId: peer.nodeId, accept: false)
                                await refresh()
                            }
                        }
                        .disabled(busy)
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
                        Button("Accept") { run { try await accept(peer) } }.disabled(busy)
                        Button("Reject") {
                            run {
                                _ = try core.setTrust(nodeId: peer.nodeId, accept: false)
                                await refresh()
                            }
                        }
                        .disabled(busy)
                    }
                }
            }
        }
    }

    private var nearbySection: some View {
        let nearby = SyncSections.split(rows).nearby
        return Section("Nearby (\(nearby.count))") {
            Text("Discovered on the LAN. Accept to trust and sync (works with desktops and other phones).")
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
                    HStack {
                        Button("Accept") { run { try await accept(peer) } }.disabled(busy)
                        Button("Request") { run { try await request(peer) } }.disabled(busy)
                    }
                }
            }
            Button("Refresh") { run { await refresh() } }.disabled(busy)
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
            Button("Send pairing request") { run { try await addManual() } }.disabled(busy)
            if let note = notes["__add"] {
                Text(note).font(.footnote)
            }
        }
    }

    // MARK: - Actions (main-actor; implementations must not block — see SyncCore)

    private func run(_ work: @escaping () async throws -> Void) {
        busy = true
        banner = nil
        Task { @MainActor in
            do {
                try await work()
            } catch let error as SyncError {
                banner = userNote(for: error)
            } catch {
                banner = error.localizedDescription
            }
            busy = false
        }
    }

    private func refresh() async {
        do {
            status = try core.syncStatus()
        } catch {
            status = nil
        }
        do {
            peers = try core.syncPeers()
        } catch {
            peers = []
        }
    }

    private func syncOne(_ peer: SyncPeer) async throws {
        do {
            notes[peer.nodeId] = try core.syncNow(nodeId: peer.nodeId)
        } catch {
            notes[peer.nodeId] = "sync failed: \(userNote(for: error))"
        }
        await refresh()
    }

    private func syncAll() async throws {
        let lines = try core.syncAllNow()
        banner = lines.first ?? "done"
        await refresh()
    }

    private func accept(_ peer: SyncPeer) async throws {
        notes[peer.nodeId] = try core.setTrust(nodeId: peer.nodeId, accept: true)
        await refresh()
    }

    private func request(_ peer: SyncPeer) async throws {
        do {
            let message = try core.addPeer(nodeId: peer.nodeId, address: peer.dialTarget, name: peer.displayName)
            // Mutual round: both sides already consented → promote.
            if message.hasPrefix("synced") {
                notes[peer.nodeId] = try core.setTrust(nodeId: peer.nodeId, accept: true)
            } else {
                notes[peer.nodeId] = message
            }
        } catch {
            notes[peer.nodeId] = userNote(for: error)
        }
        await refresh()
    }

    private func addManual() async throws {
        guard isValidNodeId(addNodeId) else {
            notes["__add"] = nodeIdHint
            return
        }
        do {
            notes["__add"] = try core.addPeer(
                nodeId: normalizeNodeId(addNodeId),
                address: addAddress.trimmingCharacters(in: .whitespacesAndNewlines),
                name: addName.trimmingCharacters(in: .whitespacesAndNewlines)
            )
        } catch {
            notes["__add"] = userNote(for: error)
        }
        await refresh()
    }

    private func userNote(for error: Error) -> String {
        if let syncError = error as? SyncError {
            switch syncError {
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
