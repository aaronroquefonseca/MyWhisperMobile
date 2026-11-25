import Foundation

/// Carries keyboard commands, transcript replies, and preview levels in the App Group.
struct KeyboardDictationMailbox {
    struct Session: Codable {
        let id: UUID
        let heartbeat: Date

        func isAvailable(at now: Date) -> Bool {
            (0...4).contains(now.timeIntervalSince(heartbeat))
        }
    }

    enum Action: String, Codable {
        case start, stop, cancel, consume
    }

    struct Request: Codable {
        let id: UUID
        let session: UUID
        let action: Action
        let created: Date

        init(id: UUID, session: UUID, action: Action, created: Date = Date()) {
            self.id = id
            self.session = session
            self.action = action
            self.created = created
        }

        func belongs(to sessionID: UUID, at now: Date) -> Bool {
            session == sessionID && (0...4).contains(now.timeIntervalSince(created))
        }
    }

    struct Reply: Codable {
        enum Phase: String, Codable { case recording, working, result, error }
        let id: UUID
        let phase: Phase
        var text: String? = nil
    }

    struct MeterReading: Codable {
        let level: Float
        let measured: Date

        func isCurrent(at now: Date) -> Bool {
            (0...0.5).contains(now.timeIntervalSince(measured)) && (0...1).contains(level)
        }
    }

    let directory: URL

    static var shared: Self? {
        KeyboardPreferences.sharedURL.map { Self(directory: $0.deletingLastPathComponent()) }
    }

    var session: Session? { read("session") }
    var request: Request? { read("request") }
    var reply: Reply? { read("reply") }
    var meter: MeterReading? { read("meter") }

    func publish(_ session: Session) throws { try write(session, to: "session") }
    func send(_ request: Request) throws { try write(request, to: "request") }
    func respond(_ reply: Reply) throws { try write(reply, to: "reply") }

    func publishMeter(_ reading: MeterReading) throws { try write(reading, to: "meter") }
    func clearMeter() { remove("meter") }

    func clearSession() { remove("session") }
    func clearReply() { remove("reply") }

    private func location(_ name: String) -> URL {
        directory.appendingPathComponent("keyboard-dictation-\(name).json")
    }

    private func read<Value: Decodable>(_ name: String) -> Value? {
        guard let data = try? Data(contentsOf: location(name)), data.count <= 256_000 else { return nil }
        return try? JSONDecoder().decode(Value.self, from: data)
    }

    private func write<Value: Encodable>(_ value: Value, to name: String) throws {
        // Not `.completeFileProtection`: the mic session keeps writing levels while the phone is locked,
        // and those writes failed and ended the session.
        try JSONEncoder().encode(value).write(
            to: location(name), options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication]
        )
    }

    private func remove(_ name: String) {
        try? FileManager.default.removeItem(at: location(name))
    }
}
