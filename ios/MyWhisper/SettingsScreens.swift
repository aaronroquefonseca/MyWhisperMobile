import SwiftUI
import UIKit

/// Android's Microphone settings: press behavior, the Smart quick-tap limit and the extra
/// listening delay after release.
struct MicrophoneSettingsView: View {
    @ObservedObject var flow: AppFlow

    private static let modes: [(value: String, label: LocalizedStringKey, help: LocalizedStringKey)] = [
        ("Smart", "Smart", "A quick tap starts and stops recording. Press and hold to talk, release to stop."),
        ("Push", "Hold", "Records while you hold the button. Release to stop."),
        ("Tap", "Tap", "Tap to start recording, tap again to stop."),
    ]
    private static let tapLimits: [Int] = [100, 150, 200, 250, 300, 400, 500]
    private static let delays: [Int] = [0, 100, 250, 500, 1000]

    var body: some View {
        List {
            Section {
                Picker("Behavior", selection: binding("preferences", "mic_mode", fallback: "Smart")) {
                    ForEach(Self.modes.indices, id: \.self) { index in
                        Text(Self.modes[index].label).tag(Self.modes[index].value)
                    }
                }
                Picker("Quick tap limit", selection: nearest("hotkeys", "smart_tap_ms", in: Self.tapLimits, fallback: 150)) {
                    ForEach(Self.tapLimits, id: \.self) { Text("\($0) ms").tag($0) }
                }
                .disabled(mode != "Smart")
            } header: {
                WhisperSectionHeading("Button press")
            } footer: {
                VStack(alignment: .leading, spacing: 6) {
                    Text(Self.modes.first { $0.value == mode }?.help ?? Self.modes[0].help)
                    Text(mode == "Smart"
                         ? "Shorter presses start hands-free recording. Longer presses record only while held."
                         : "The quick tap limit is only used in Smart mode.")
                }
            }
            .whisperRowSurface()
            Section {
                Picker("Extra listening delay", selection: nearest("stt", "release_delay_ms", in: Self.delays, fallback: 0)) {
                    ForEach(Self.delays, id: \.self) { delay in
                        if delay == 0 { Text("Off").tag(delay) } else { Text("\(delay) ms").tag(delay) }
                    }
                }
            } header: {
                WhisperSectionHeading("Recording")
            } footer: {
                Text("Keeps recording briefly after release so final sounds aren't cut.")
            }
            .whisperRowSurface()
        }
        .whisperListStyle()
        .navigationTitle("Microphone")
        .navigationBarTitleDisplayMode(.inline)
    }

    private var mode: String {
        (flow.settings?["preferences"] as? [String: Any])?["mic_mode"] as? String ?? "Smart"
    }

    private func binding(_ section: String, _ key: String, fallback: String) -> Binding<String> {
        Binding {
            (flow.settings?[section] as? [String: Any])?[key] as? String ?? fallback
        } set: { value in
            flow.updateSettings { Self.put(value, section, key, into: &$0) }
        }
    }

    /// Stored values snap to the nearest offered option, like Android's option sliders.
    private func nearest(_ section: String, _ key: String, in options: [Int], fallback: Int) -> Binding<Int> {
        Binding {
            let stored = ((flow.settings?[section] as? [String: Any])?[key] as? NSNumber)?.intValue ?? fallback
            return options.min { abs($0 - stored) < abs($1 - stored) } ?? fallback
        } set: { value in
            flow.updateSettings { Self.put(value, section, key, into: &$0) }
        }
    }

    private static func put(_ value: Any, _ section: String, _ key: String, into settings: inout [String: Any]) {
        var group = settings[section] as? [String: Any] ?? [:]
        group[key] = value
        settings[section] = group
    }
}

/// Android's Diagnostics page: technical details to copy or share with a problem report.
struct DiagnosticsView: View {
    @ObservedObject var flow: AppFlow
    @State private var copied = false

    var body: some View {
        let report = Self.report(flow: flow)
        List {
            Section {
                Text("Technical details about this app and device. Share them when you report a problem.")
                    .foregroundStyle(.secondary)
                HStack {
                    Button(copied ? "Copied" : "Copy", systemImage: "doc.on.doc") {
                        copied = Delivery.copyToClipboard(report)
                    }
                    Spacer()
                    ShareLink(item: report) { Label("Share", systemImage: "square.and.arrow.up") }
                }
                .buttonStyle(.borderless)
            }
            .whisperRowSurface()
            Section {
                Text(report).font(.footnote.monospaced()).textSelection(.enabled)
            }
            .whisperRowSurface()
        }
        .whisperListStyle()
        .navigationTitle("Diagnostics")
        .navigationBarTitleDisplayMode(.inline)
        .sensoryFeedback(.success, trigger: copied)
    }

    static func report(flow: AppFlow) -> String {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "?"
        let build = info?["CFBundleVersion"] as? String ?? "?"
        let device = UIDevice.current
        var lines = [
            "app \(version) (\(build))",
            "core \(flow.coreVersion)",
            "\(device.systemName) \(device.systemVersion) · \(device.model)",
            "microphone \(flow.micGranted ? "granted" : "not granted")",
            "shared container \(KeyboardPreferences.coreDirectoryURL == nil ? "missing" : "available")",
        ]
        if let free = try? URL(fileURLWithPath: NSHomeDirectory())
            .resourceValues(forKeys: [.volumeAvailableCapacityForImportantUsageKey]).volumeAvailableCapacityForImportantUsage {
            lines.append("storage free=\(free / 1_000_000)MB")
        }
        let stt = flow.settings?["stt"] as? [String: Any]
        let entries = (stt?["entries"] as? [[String: Any]] ?? []).map { entry in
            "\(entry["kind"] as? String ?? "?")\(entry["enabled"] as? Bool == false ? " (off)" : "") \(entry["provider"] as? String ?? "") \(entry["model"] as? String ?? "")"
        }
        lines.append("speech chain: " + (entries.isEmpty ? "empty" : entries.joined(separator: " → ")))
        if let error = flow.coreError { lines.append("core error: \(error)") }
        if let error = flow.testError { lines.append("last test error: \(error)") }
        return lines.joined(separator: "\n")
    }
}
