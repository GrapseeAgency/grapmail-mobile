import Foundation

/// The on-device switches and small memories the mail-feature family shares.
/// One store, keyed by kind, so a switch flipped on one screen reads the same
/// on every other — and so nothing here ever needs the network to answer a
/// question about itself.
///
/// What is NOT here is as deliberate as what is: a receipt is a record of
/// what the person chose to send, the tracker shield is a render-time
/// decision, and the staging hand-off is a sixty-second window — never a draft.
final class MailFeaturePrefs {
    static let shared = MailFeaturePrefs()

    private let defaults: UserDefaults

    private enum Key {
        static let readReceipts = "grapmail.features.readReceipts"
        static let trackerShield = "grapmail.features.trackerShield"
        static let remoteImages = "grapmail.features.allowRemoteImages"
        static let receiptsSent = "grapmail.features.receiptsSent"
        static let prioritySenders = "grapmail.features.prioritySenders"
        static let awaiting = "grapmail.features.awaitingReplies"
        static let stagedText = "grapmail.features.stagedInsert"
        static let stagedAt = "grapmail.features.stagedInsertAt"
    }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
    }

    // MARK: - The switches

    /// Send a read receipt for incoming mail. Off until the person turns it
    /// on: a receipt is a statement about them, and the app does not make it
    /// on their behalf. The toggle in Preferences is the whole opt-in.
    var readReceiptsEnabled: Bool {
        get { defaults.bool(forKey: Key.readReceipts) }
        set { defaults.set(newValue, forKey: Key.readReceipts) }
    }

    /// Hide known tracking pixels and remote images while reading. On by
    /// default — the same stance the web reader takes, because a remote pixel
    /// is the tracking mechanism itself, not a decoration.
    var trackerShieldEnabled: Bool {
        get { defaults.object(forKey: Key.trackerShield) as? Bool ?? true }
        set { defaults.set(newValue, forKey: Key.trackerShield) }
    }

    /// Load remote images anyway. Off by default, and only meaningful as the
    /// person's own exception to the shield above.
    var allowRemoteImages: Bool {
        get { defaults.bool(forKey: Key.remoteImages) }
        set { defaults.set(newValue, forKey: Key.remoteImages) }
    }

    // MARK: - Receipts (read receipts, per message)

    /// The ids a receipt was actually sent for. A set, not a counter: the
    /// question the UI asks is "was THIS message answered", never "how many".
    var receiptsSent: Set<String> {
        Set(defaults.stringArray(forKey: Key.receiptsSent) ?? [])
    }

    func markReceiptSent(_ messageId: String) {
        var sent = receiptsSent
        guard sent.insert(messageId).inserted else { return }
        defaults.set(Array(sent), forKey: Key.receiptsSent)
    }

    func wasReceiptSent(_ messageId: String) -> Bool {
        receiptsSent.contains(messageId)
    }

    // MARK: - Priority senders (the person's own known-good list)

    /// Addresses the person marked as priority. Lowercased on write so the
    /// match against a message's sender cannot fail on capitalization alone.
    var prioritySenders: Set<String> {
        Set(defaults.stringArray(forKey: Key.prioritySenders) ?? [])
    }

    func setPriority(_ address: String, on: Bool) {
        let key = address.lowercased()
        guard !key.isEmpty else { return }
        var set = prioritySenders
        if on { set.insert(key) } else { set.remove(key) }
        defaults.set(Array(set), forKey: Key.prioritySenders)
    }

    // MARK: - Follow-up memory (a reply the person is still waiting on)

    struct Awaiting: Codable {
        let title: String
        let since: Date
    }

    /// Everything marked as waiting on an answer, newest first — the follow-up
    /// center's list. Stored as one JSON blob so a clear is one write.
    private func readAwaiting() -> [String: Awaiting] {
        guard let data = defaults.data(forKey: Key.awaiting),
              let map = try? JSONDecoder().decode([String: Awaiting].self, from: data)
        else { return [:] }
        return map
    }

    private func writeAwaiting(_ map: [String: Awaiting]) {
        guard let data = try? JSONEncoder().encode(map) else { return }
        defaults.set(data, forKey: Key.awaiting)
    }

    var awaitingReplies: [(id: String, title: String, since: Date)] {
        readAwaiting()
            .map { (id: $0.key, title: $0.value.title, since: $0.value.since) }
            .sorted { $0.since > $1.since }
    }

    /// Record that a reply went out into a thread and no answer has come back.
    /// Called at the send's acceptance — the moment the wait actually starts.
    func markAwaitingReply(threadId: String, title: String) {
        guard !threadId.isEmpty else { return }
        var map = readAwaiting()
        map[threadId] = Awaiting(title: title, since: Date())
        writeAwaiting(map)
    }

    func clearAwaitingReply(threadId: String) {
        var map = readAwaiting()
        guard map.removeValue(forKey: threadId) != nil else { return }
        writeAwaiting(map)
    }

    // MARK: - Smart-reply staging (strip → compose hand-off)

    /// How long a parked suggestion stays fresh. The composer is a full-screen
    /// cover, not a child of the strip, so the text waits here between them —
    /// and expires, so a suggestion parked by someone who walked away does not
    /// ambush them an hour later.
    static let stagedWindow: TimeInterval = 60

    func stageInsert(_ text: String) {
        guard !text.isEmpty else { return }
        defaults.set(text, forKey: Key.stagedText)
        defaults.set(Date().timeIntervalSince1970, forKey: Key.stagedAt)
    }

    /// The staged suggestion, if one is still fresh. Reading consumes it: a
    /// suggestion inserted once must not re-insert itself on the next open.
    func consumeStagedInsert() -> String? {
        defer {
            defaults.removeObject(forKey: Key.stagedText)
            defaults.removeObject(forKey: Key.stagedAt)
        }
        guard let text = defaults.string(forKey: Key.stagedText), !text.isEmpty else { return nil }
        let at = defaults.double(forKey: Key.stagedAt)
        guard at > 0, Date().timeIntervalSince1970 - at <= Self.stagedWindow else { return nil }
        return text
    }
}
