import SwiftUI

/// T1 stub screen: proves the core opens, reports its version, and that
/// the settings JSON loads. Dictation UI lands in T2.
struct ContentView: View {
    @State private var version = "…"
    @State private var settingsJSON = "loading…"
    @State private var status = "starting"

    var body: some View {
        NavigationStack {
            List {
                Section("Core") {
                    LabeledContent("Version", value: version)
                    LabeledContent("Status", value: status)
                }
                Section("Settings JSON") {
                    Text(settingsJSON)
                        .font(.system(.caption, design: .monospaced))
                        .textSelection(.enabled)
                }
                Section {
                    Button("Reload") { load() }
                }
            }
            .navigationTitle("MyWhisper")
            .onAppear { load() }
        }
    }

    private func load() {
        do {
            let dir = try appFilesDir()
            let key = try CoreBridge.dbKeyHex()
            version = try CoreBridge.open(filesDir: dir, dbKeyHex: key)
            settingsJSON = try CoreBridge.settingsJSON()
            status = "open"
        } catch {
            status = "error: \(error)"
        }
    }

    private func appFilesDir() throws -> String {
        let url = try FileManager.default.url(
            for: .applicationSupportDirectory, in: .userDomainMask,
            appropriateFor: nil, create: true
        ).appendingPathComponent("MyWhisper", isDirectory: true)
        try FileManager.default.createDirectory(at: url, withIntermediateDirectories: true)
        return url.path
    }
}
