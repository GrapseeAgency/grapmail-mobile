import CryptoKit
import Foundation

/// The `Idempotency-Key` for `POST /send`.
///
/// One key per message, reused only when that exact message (same
/// recipients, subject, body, attachments and reply target) is sent again
/// within ten minutes without an answer having come back: the
/// timeout-then-retry case, where the first send may already have gone out.
/// The server then replays the first answer instead of sending a second email.
///
/// As soon as the server answers, the key is forgotten, so deliberately
/// sending the same text again later is a new send. Kept in memory only.
final class SendKeys: @unchecked Sendable {
    static let shared = SendKeys()
    private static let window: TimeInterval = 10 * 60

    private struct Entry { let key: String; let at: Date }
    private let lock = NSLock()
    private var keys: [String: Entry] = [:]

    private func digest(_ body: Data) -> String {
        SHA256.hash(data: body).map { String(format: "%02x", $0) }.joined()
    }

    func key(for body: Data, now: Date = Date()) -> String {
        let id = digest(body)
        return lock.withLock {
            keys = keys.filter { now.timeIntervalSince($0.value.at) <= Self.window }
            if let existing = keys[id] { return existing.key }
            let key = "i-" + UUID().uuidString.replacingOccurrences(of: "-", with: "").lowercased()
            keys[id] = Entry(key: key, at: now)
            return key
        }
    }

    /// The server answered this message; the next send of it is a new send.
    func settle(_ body: Data) {
        let id = digest(body)
        lock.withLock { keys[id] = nil }
    }
}
