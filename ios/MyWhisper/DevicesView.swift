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
/// `Transcription.swift` deliver-on-main pattern. This is a struct,
/// so closures capture it by value: no capture lists, no cycles.
struct DevicesView: View {
    var core: SyncCore = BridgeSyncCore()
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

    @State private var syncEnabled = false
    @State private var deviceNameDraft = ""
    @State private var portDraft = ""
    @State private var advertise = true
    @State private var shownName = ""

    init(core: SyncCore = BridgeSyncCore()) {
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
            settingsSection
            trustedSection
            requestsSection
            nearbySection
            addSection
            if let banner = banner {
                Section { Text(banner).font(.footnote) }
            }
        }
        .whisperListStyle()
        .navigationTitle(OnboardingStrings.current(languageCode: Locale.current.language.languageCode?.identifier).devices)
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            store.discovery.startBrowsing()
            loadSyncSettings()
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
                Text("Sync is off. Turn it on below to pair with your other devices.")
                    .font(.footnote)
            }
        }
    }

    /// Android's sync settings (`sync.*`). iOS runs sync only while MyWhisper is open.
    private var settingsSection: some View {
        Section {
            Toggle("Sync", isOn: Binding(get: { syncEnabled }, set: { on in
                syncEnabled = on
                saveSync { $0["enabled"] = on }
                if on { startTapped() } else { stopTapped() }
            }))
            TextField(shownName.isEmpty ? "Device name" : "Device name (\(shownName))", text: $deviceNameDraft)
                .onSubmit { saveSync { $0["device_name"] = deviceNameDraft.trimmingCharacters(in: .whitespaces) }; restartIfRunning() }
            LabeledContent("Port") {
                TextField("42973", text: $portDraft)
                    .keyboardType(.numberPad)
                    .multilineTextAlignment(.trailing)
                    .frame(maxWidth: 90)
                    .onSubmit(savePort)
            }
            Toggle(isOn: Binding(get: { advertise }, set: { on in
                advertise = on
                saveSync { $0["advertise"] = on }
                restartIfRunning()
            })) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Visible on the network")
                    Text("Off = invisible, still sees others.").font(.caption).foregroundStyle(.secondary)
                }
            }
            Button("Save name and port") {
                saveSync { $0["device_name"] = deviceNameDraft.trimmingCharacters(in: .whitespaces) }
                savePort()
            }
        } header: {
            Text("Settings")
        } footer: {
            Text("iPhone syncs while MyWhisper is open; your other devices catch up when you return.")
        }
    }

    private func loadSyncSettings() {
        let sync = (try? CoreBridge.getSettings())?["sync"] as? [String: Any] ?? [:]
        syncEnabled = sync["enabled"] as? Bool ?? false
        deviceNameDraft = sync["device_name"] as? String ?? ""
        portDraft = String((sync["listen_port"] as? NSNumber)?.intValue ?? 42973)
        advertise = sync["advertise"] as? Bool ?? true
        shownName = core.displayName()
        // iOS can't keep a listener alive in the background: resume on every visit when enabled.
        if syncEnabled, status?.running != true { startTapped() }
    }

    private func savePort() {
        let port = min(Int(portDraft.filter(\.isNumber).prefix(5)) ?? 42973, 65_535)
        portDraft = String(port)
        saveSync { $0["listen_port"] = port }
        restartIfRunning()
    }

    private func saveSync(_ change: (inout [String: Any]) -> Void) {
        do {
            var settings = try CoreBridge.getSettings()
            var sync = settings["sync"] as? [String: Any] ?? [:]
            change(&sync)
            settings["sync"] = sync
            try CoreBridge.putSettings(settings)
        } catch {
            banner = CoreError.displayMessage(for: error)
        }
    }

    private func restartIfRunning() {
        guard status?.running == true else { return }
        startTapped()
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
        workers.async {
            do {
                _ = try self.core.startSync()
            } catch {
                self.mainFail(error)
                return
            }
            self.mainPull()
        }
    }

    private func stopTapped() {
        busy = true
        banner = nil
        workers.async {
            self.core.stopSync()
            self.mainPull()
        }
    }

    private func syncAllTapped() {
        busy = true
        banner = nil
        workers.async {
            let note: String
            do {
                let lines = try self.core.syncAllNow()
                if lines.isEmpty {
                    note = "done"
                } else {
                    note = lines.joined(separator: "\n")
                }
            } catch {
                self.mainFail(error)
                return
            }
            DispatchQueue.main.async {
                self.banner = note
                self.pullState()
            }
        }
    }

    private func syncOneTapped(_ peer: SyncPeer) {
        busy = true
        banner = nil
        workers.async {
            let note: String
            do {
                note = try self.core.syncNow(nodeId: peer.nodeId)
            } catch {
                note = "sync failed: \(self.userNote(for: error))"
            }
            DispatchQueue.main.async {
                self.notes[peer.nodeId] = note
                self.pullState()
            }
        }
    }

    private func removeTapped(_ peer: SyncPeer) {
        busy = true
        banner = nil
        workers.async {
            do {
                _ = try self.core.setTrust(nodeId: peer.nodeId, accept: false)
            } catch {
                self.mainFail(error)
                return
            }
            self.mainPull()
        }
    }

    /// Android `acceptPeer` parity: trust, then an immediate round so
    /// both sides converge without a second tap. A post-trust dial
    /// failure is the usual "they haven't accepted us yet" case, so
    /// it reads as waiting — not as an error.
    private func acceptTapped(_ peer: SyncPeer) {
        busy = true
        banner = nil
        workers.async {
            let trustMessage: String
            do {
                trustMessage = try self.core.setTrust(nodeId: peer.nodeId, accept: true)
            } catch {
                self.mainFail(error)
                return
            }
            let note: String
            do {
                note = try self.core.syncNow(nodeId: peer.nodeId)
            } catch {
                note = trustMessage + "; " + self.waitingNote(for: error, peer: peer)
            }
            DispatchQueue.main.async {
                self.notes[peer.nodeId] = note
                self.pullState()
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
        workers.async {
            let note: String
            do {
                let message = try self.core.addPeer(nodeId: nodeId, address: address, name: name)
                if message.hasPrefix("synced") {
                    do {
                        note = try self.core.setTrust(nodeId: nodeId, accept: true)
                    } catch {
                        note = message
                    }
                } else {
                    note = message
                }
            } catch {
                note = self.userNote(for: error)
            }
            DispatchQueue.main.async {
                self.notes[key] = note
                self.pullState()
            }
        }
    }

    /// Fetch status + peers on the worker, commit on main. Also drives
    /// foreground advertising from the reported id/port (no-op until
    /// the bridge exposes status) and always records the own id so
    /// self-observations are filtered.
    private func pullState() {
        busy = true
        workers.async {
            var nextStatus: SyncStatus?
            var nextPeers: [SyncPeer] = []
            var deviceName = ""
            do {
                nextStatus = try self.core.syncStatus()
            } catch {
                nextStatus = nil
            }
            do {
                nextPeers = try self.core.syncPeers()
            } catch {
                nextPeers = []
            }
            deviceName = self.core.displayName()
            DispatchQueue.main.async {
                self.status = nextStatus
                self.peers = nextPeers
                self.applyAdvertise(status: nextStatus, name: deviceName)
                self.busy = false
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
        DispatchQueue.main.async {
            self.banner = self.userNote(for: error)
            self.busy = false
        }
    }

    private func mainPull() {
        DispatchQueue.main.async {
            self.pullState()
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
        // Class scope (not the struct above): `weak` is legal here and
        // required — `store -> discovery -> onChange -> store` would
        // otherwise cycle and leak the browser after dismissal.
        discovery.onChange = { [weak self] in
            DispatchQueue.main.async { self?.objectWillChange.send() }
        }
    }
}
