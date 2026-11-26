import SwiftUI

/// Android `ProviderTest`: one lightweight check per chain row (and per add/edit form), never a
/// real transcription or generation. A gated 401/403 fails; any other HTTP status proves the
/// server is reachable, since healthy POST-only routes answer 404/405 to a GET. A blank vendor
/// URL or a `ws(s)` URL has no HTTP surface, so the config check stands in.
enum ProviderTest {
    struct Outcome: Equatable {
        let passed: Bool
        let latencyMs: Int
        let excerpt: String
    }

    static let timeout: TimeInterval = 10

    /// Why the Test button is off for this config, or nil when it can run.
    static func blockedReason(kind: String, url: String, model: String) -> LocalizedStringKey? {
        let url = url.trimmingCharacters(in: .whitespaces)
        if kind == "Local" { return nil }
        if model.trimmingCharacters(in: .whitespaces).isEmpty { return "Enter a model first." }
        let scheme = url.components(separatedBy: "://").first?.lowercased() ?? ""
        switch kind {
        case "SelfHosted":
            if url.isEmpty { return "Enter a server URL first." }
            if !url.contains("://") || !["http", "https", "ws", "wss"].contains(scheme) {
                return "URL must start with http(s):// or ws(s)://."
            }
        case "Ollama":
            if url.isEmpty { return "Enter a server URL first." }
            if !url.contains("://") || !["http", "https"].contains(scheme) { return "Base URL must start with http(s)://." }
        default:
            if !url.isEmpty, !url.contains("://") || !["http", "https"].contains(scheme) {
                return "Base URL must start with http(s)://."
            }
        }
        return nil
    }

    static func speech(url: String, model: String, apiKey: String) async -> Outcome {
        let url = url.trimmingCharacters(in: .whitespaces)
        let model = model.trimmingCharacters(in: .whitespaces)
        if url.isEmpty || url.lowercased().hasPrefix("ws") {
            return await run { "config ok · \(model)" }
        }
        return await run { try await reachable(url, model: model, apiKey: apiKey) }
    }

    static func cleanup(kind: String, url: String, model: String, apiKey: String) async -> Outcome {
        let url = url.trimmingCharacters(in: .whitespaces)
        let model = model.trimmingCharacters(in: .whitespaces)
        if url.isEmpty { return await run { "config ok · \(model)" } }
        if kind == "Ollama" {
            return await run {
                // Ollama lists its models: the configured one must be there.
                let base = url.hasSuffix("/") ? String(url.dropLast()) : url
                let (body, code) = try await get(base + "/api/tags", apiKey: apiKey)
                guard (200..<300).contains(code) else { throw ProbeError("http \(code)") }
                guard body.localizedCaseInsensitiveContains(model) else { throw ProbeError("reachable · model not listed") }
                return "model ok · \(model)"
            }
        }
        return await run { try await reachable(url, model: model, apiKey: apiKey) }
    }

    private static func reachable(_ url: String, model: String, apiKey: String) async throws -> String {
        let (_, code) = try await get(url, apiKey: apiKey)
        if code == 401 || code == 403 { throw ProbeError("http \(code) · \(model)") }
        return "HTTP \(code) · \(model)"
    }

    private static func get(_ url: String, apiKey: String) async throws -> (String, Int) {
        guard let target = URL(string: url) else { throw ProbeError("bad url") }
        var request = URLRequest(url: target, timeoutInterval: 8)
        request.setValue("application/json", forHTTPHeaderField: "Accept")
        let key = apiKey.trimmingCharacters(in: .whitespaces)
        if !key.isEmpty { request.setValue("Bearer \(key)", forHTTPHeaderField: "Authorization") }
        let (data, response) = try await URLSession.shared.data(for: request)
        return (String(decoding: data.prefix(4096), as: UTF8.self), (response as? HTTPURLResponse)?.statusCode ?? 0)
    }

    private static func run(_ probe: () async throws -> String) async -> Outcome {
        let start = Date()
        let result: Result<String, Error>
        do { result = .success(try await probe()) } catch { result = .failure(error) }
        let latency = Int(Date().timeIntervalSince(start) * 1000)
        switch result {
        case .success(let text):
            return Outcome(passed: latency <= Int(timeout * 1000) && !text.isEmpty, latencyMs: latency, excerpt: excerpt(text))
        case .failure(let error):
            return Outcome(passed: false, latencyMs: latency, excerpt: excerpt((error as? ProbeError)?.message ?? error.localizedDescription))
        }
    }

    static func excerpt(_ text: String, max: Int = 120) -> String {
        let flat = text.split(whereSeparator: \.isWhitespace).joined(separator: " ")
        return flat.count <= max ? flat : String(flat.prefix(max)).trimmingCharacters(in: .whitespaces) + "…"
    }

    private struct ProbeError: Error {
        let message: String
        init(_ message: String) { self.message = message }
    }
}

/// Android `ProviderTestButton`: Test, then the outcome inline under it.
struct ProviderTestButton: View {
    let blockedReason: LocalizedStringKey?
    let probe: () async -> ProviderTest.Outcome
    @State private var running = false
    @State private var outcome: ProviderTest.Outcome?

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Button {
                running = true
                outcome = nil
                Task {
                    let result = await probe()
                    outcome = result
                    running = false
                }
            } label: {
                Label(running ? "Testing…" : "Test", systemImage: "checkmark.seal")
            }
            .disabled(running || blockedReason != nil)
            if let outcome {
                Text(outcome.passed ? "PASS · \(outcome.latencyMs) ms · \(outcome.excerpt)" : "FAIL · \(outcome.latencyMs) ms · \(outcome.excerpt)")
                    .font(.caption)
                    .foregroundStyle(outcome.passed ? Color.green : Color.red)
                    .textSelection(.enabled)
            } else if let blockedReason {
                Text(blockedReason).font(.caption).foregroundStyle(.secondary)
            }
        }
        .sensoryFeedback(.success, trigger: outcome?.passed == true ? outcome : nil)
    }
}
