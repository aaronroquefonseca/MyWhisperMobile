import SwiftUI
import UIKit

/// Android's Microphone settings, for the keyboard's mic: the extra listening delay after release
/// and which microphone to record from. (Android's press modes belong to its floating button; the
/// keyboard's mic is hold to talk, slide up to lock.)
struct MicrophoneSettingsView: View {
    @ObservedObject var flow: AppFlow

    private static let delays: [Int] = [0, 100, 250, 500, 1000]

    var body: some View {
        List {
            Section {
                Picker("Extra listening delay", selection: delayBinding) {
                    ForEach(Self.delays, id: \.self) { delay in
                        if delay == 0 { Text("Off").tag(delay) } else { Text("\(delay) ms").tag(delay) }
                    }
                }
            } footer: {
                Text("Keeps recording briefly after release so final sounds aren't cut.")
            }
            Section {
                ForEach(MicRoute.priority(from: flow.settings), id: \.self) { kind in
                    Label {
                        VStack(alignment: .leading, spacing: 2) {
                            Text(Self.title(kind))
                            Text(Self.detail(kind)).font(.subheadline).foregroundStyle(.secondary)
                        }
                    } icon: {
                        Image(systemName: Self.symbol(kind))
                    }
                }
                .onMove { source, destination in
                    var order = MicRoute.priority(from: flow.settings)
                    order.move(fromOffsets: source, toOffset: destination)
                    flow.applyMicPriority(order)
                }
            } header: {
                Text("Preferred order")
            } footer: {
                Text("Dictation uses the first connected microphone in this list. The built-in microphone is always available, so anything below it is never used. Bluetooth switches earbuds to call mode while you dictate.")
            }
        }
        // Drag handles stay visible, like iOS's own ordered lists.
        .environment(\.editMode, .constant(.active))
        .whisperListStyle()
        .navigationTitle("Microphone")
        .navigationBarTitleDisplayMode(.inline)
    }

    /// Stored values snap to the nearest offered option, like Android's option sliders.
    private var delayBinding: Binding<Int> {
        Binding {
            let stored = ((flow.settings?["stt"] as? [String: Any])?["release_delay_ms"] as? NSNumber)?.intValue ?? 0
            return Self.delays.min { abs($0 - stored) < abs($1 - stored) } ?? 0
        } set: { value in
            flow.updateSettings { settings in
                var stt = settings["stt"] as? [String: Any] ?? [:]
                stt["release_delay_ms"] = value
                settings["stt"] = stt
            }
        }
    }

    private static func title(_ kind: String) -> LocalizedStringKey {
        switch kind {
        case MicRoute.wired: return "Wired"
        case MicRoute.bluetooth: return "Bluetooth"
        case MicRoute.carPlay: return "CarPlay"
        default: return "Built-in microphone"
        }
    }

    private static func detail(_ kind: String) -> LocalizedStringKey {
        switch kind {
        case MicRoute.wired: return "Headsets and USB microphones"
        case MicRoute.bluetooth: return "AirPods, headsets, and cars"
        case MicRoute.carPlay: return "The car's microphone"
        default: return "The microphone on this iPhone"
        }
    }

    private static func symbol(_ kind: String) -> String {
        switch kind {
        case MicRoute.wired: return "cable.connector"
        case MicRoute.bluetooth: return "headphones"
        case MicRoute.carPlay: return "car"
        default: return "iphone"
        }
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
            Section {
                Text(report).font(.footnote.monospaced()).textSelection(.enabled)
            }
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
            "keyboard full access \(KeyboardPreferences.fullAccessSeen ? "seen" : "not seen")",
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
        if let issue = DictationIssue.load() { lines.append("last dictation issue: \(issue.cause.rawValue) \(issue.detail)") }
        return lines.joined(separator: "\n")
    }
}
