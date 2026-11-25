import Foundation

#if canImport(ActivityKit)
import ActivityKit
#endif

/// The one file both the app and the widget extension compile.
///
/// The widget process is not the app process: it cannot read the app's own
/// UserDefaults, cannot hold its session, and — the part that shapes this
/// file — is not allowed to fetch anything on its own. So the app writes what
/// it last knew into the shared App Group and the widget shows exactly that,
/// timestamp included. A widget that fetched on its own would need its own
/// session and its own answer to "what is unread", which is a second tally of
/// the same thing — the mistake the launcher badge is deliberately built to
/// avoid.
enum WidgetShared {
    /// The App Group both targets carry (declared in project.yml on the app's
    /// and the widget's entitlements): the only container either writes.
    static let suiteName = "group.com.grapsee.grapmail"

    /// The unread widget's `kind` (UnreadWidget.swift). Shared so the app and
    /// the widget's own intents reload exactly that timeline.
    static let unreadWidgetKind = "GrapMailUnread"

    enum Key {
        static let unread = "widget.unread"
        static let account = "widget.account"
        static let sender = "widget.sender"
        static let subject = "widget.subject"
        static let updatedAt = "widget.updatedAt"
        static let signedIn = "widget.signedIn"
        static let recent = "widget.recent"
        /// Widget button taps the app has not yet sent (see `PendingAction`).
        static let pendingActions = "widget.pendingActions"
    }

    /// What the app last knew, as the widget reads it. Every field optional:
    /// nil means "never written", which renders as "no read yet" — never as a
    /// zero the app did not actually see.
    /// Up to five of the newest messages, as the widget draws them: who and
    /// what, never a full message - the widget shows, the app opens.
    ///
    /// The ids and flags are optional so a snapshot written by an older build
    /// (sender and subject only) still decodes; such a row simply shows no
    /// buttons until the app's next read rewrites it.
    struct RecentItem: Codable, Hashable {
        var sender: String
        var subject: String
        var id: String? = nil
        var threadId: String? = nil
        var isRead: Bool? = nil
        var isStarred: Bool? = nil
    }

    struct Snapshot {
        var unread: Int?
        var account: String?
        var sender: String?
        var subject: String?
        var updatedAt: Date?
        var signedIn: Bool
        var recent: [RecentItem] = []
    }

    static func read() -> Snapshot {
        guard let defaults = UserDefaults(suiteName: suiteName) else {
            return Snapshot(unread: nil, account: nil, sender: nil, subject: nil,
                            updatedAt: nil, signedIn: false)
        }
        let updated = defaults.object(forKey: Key.updatedAt) as? Double
        let recentData = defaults.data(forKey: Key.recent)
        let recentItems = recentData.flatMap { try? JSONDecoder().decode([RecentItem].self, from: $0) } ?? []
        return Snapshot(
            unread: defaults.object(forKey: Key.unread) as? Int,
            account: defaults.string(forKey: Key.account),
            sender: defaults.string(forKey: Key.sender),
            subject: defaults.string(forKey: Key.subject),
            updatedAt: updated.map { Date(timeIntervalSince1970: $0) },
            signedIn: defaults.bool(forKey: Key.signedIn),
            recent: recentItems
        )
    }

    /// The same canonical-uuid check `DeepLink.mailId` applies (DeepLinks.swift
    /// is app-only): every mail id the server mints is a lowercase 8-4-4-4-12
    /// uuid, so anything else is refused before it reaches a queue or a URL.
    static func mailId(_ raw: String?) -> String? {
        guard let raw,
              raw.range(of: "^[0-9A-Fa-f]{8}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}-[0-9A-Fa-f]{12}$",
                        options: .regularExpression) != nil
        else { return nil }
        return raw.lowercased()
    }
}

// ── widget actions ───────────────────────────────────────────────────────────

/// A button tapped on the widget (Mark as read, Archive, Star), waiting for the
/// app to send it.
///
/// ── WHY A QUEUE AND NOT A REQUEST ───────────────────────────────────────────
/// The widget extension has no session to send it with: the bearer lives in
/// the app's own Keychain access group, which the widget's entitlements do
/// not carry, and refreshing it needs the device-bound key and the pinned
/// session, which only the app compiles. Giving the widget all three would
/// make it a second signed-in client. Instead the tap is written here — ids
/// only, never content — and the app sends it through its normal
/// authenticated path (`WidgetActionSync`, app target) at its next launch,
/// foreground or background refresh. Meanwhile the snapshot is changed at
/// once, so the row the person acted on reads as done.
extension WidgetShared {
    struct PendingAction: Codable, Hashable, Identifiable {
        enum Kind: String, Codable {
            case read, archive, star, unstar

            /// star/unstar cancel each other while neither has been sent.
            var opposite: Kind? {
                switch self {
                case .star: return .unstar
                case .unstar: return .star
                default: return nil
                }
            }
        }

        var id: String
        var kind: Kind
        var messageId: String
        /// The account the widget showed when the tap happened. The app only
        /// ever sends it as that account, and drops it if that account has
        /// been signed out since.
        var account: String
        var createdAt: Date
        var attempts: Int
    }

    static func pendingActions() -> [PendingAction] {
        guard let defaults = UserDefaults(suiteName: suiteName),
              let data = defaults.data(forKey: Key.pendingActions) else { return [] }
        return (try? JSONDecoder().decode([PendingAction].self, from: data)) ?? []
    }

    private static func writePending(_ actions: [PendingAction], to defaults: UserDefaults) {
        if actions.isEmpty {
            defaults.removeObject(forKey: Key.pendingActions)
        } else if let data = try? JSONEncoder().encode(actions) {
            defaults.set(data, forKey: Key.pendingActions)
        }
    }

    /// The widget side: queue the tap and change the snapshot at once.
    /// Returns false (and changes nothing) when there is no signed-in account
    /// on the widget, the id is not a mail id, or the row is not on it.
    @discardableResult
    static func enqueue(_ kind: PendingAction.Kind, messageId raw: String) -> Bool {
        guard let defaults = UserDefaults(suiteName: suiteName),
              let messageId = mailId(raw),
              defaults.bool(forKey: Key.signedIn),
              let account = defaults.string(forKey: Key.account)?.lowercased(), !account.isEmpty
        else { return false }
        var snapshot = read()
        guard snapshot.recent.contains(where: { $0.id == messageId }) else { return false }

        var queue = pendingActions()
        if let opposite = kind.opposite,
           let index = queue.firstIndex(where: { $0.messageId == messageId && $0.kind == opposite && $0.account == account }) {
            // Star then unstar before the app ran: nothing to send.
            queue.remove(at: index)
        } else if !queue.contains(where: { $0.messageId == messageId && $0.kind == kind && $0.account == account }) {
            queue.append(PendingAction(id: UUID().uuidString, kind: kind, messageId: messageId,
                                       account: account, createdAt: Date(), attempts: 0))
        }
        writePending(queue, to: defaults)

        apply(kind, messageId: messageId, recent: &snapshot.recent, unread: &snapshot.unread)
        writeOptimistic(snapshot, to: defaults)
        return true
    }

    /// The optimistic change one action makes to what the widget shows. Pure,
    /// and shared with the app, which re-applies still-unsent actions on top of
    /// every fresh read so a poll cannot bring an archived row back.
    static func apply(_ kind: PendingAction.Kind, messageId: String,
                      recent: inout [RecentItem], unread: inout Int?) {
        guard let index = recent.firstIndex(where: { $0.id == messageId }) else { return }
        let wasUnread = recent[index].isRead == false
        switch kind {
        case .read:
            recent[index].isRead = true
            if wasUnread, let count = unread { unread = max(0, count - 1) }
        case .archive:
            recent.remove(at: index)
            if wasUnread, let count = unread { unread = max(0, count - 1) }
        case .star:
            recent[index].isStarred = true
        case .unstar:
            recent[index].isStarred = false
        }
    }

    /// Writes the changed rows and count; the "latest" lines (the Lock Screen
    /// card) follow the first row, so archiving the newest message does not
    /// leave its sender on the Lock Screen. The timestamp is NOT touched: the
    /// widget still says when the app last actually read the mailbox.
    private static func writeOptimistic(_ snapshot: Snapshot, to defaults: UserDefaults) {
        if let unread = snapshot.unread { defaults.set(unread, forKey: Key.unread) }
        if let data = try? JSONEncoder().encode(snapshot.recent) {
            defaults.set(data, forKey: Key.recent)
        }
        if let first = snapshot.recent.first {
            defaults.set(first.sender, forKey: Key.sender)
            if first.subject.isEmpty { defaults.removeObject(forKey: Key.subject) }
            else { defaults.set(first.subject, forKey: Key.subject) }
        } else if defaults.data(forKey: Key.recent) != nil {
            defaults.removeObject(forKey: Key.sender)
            defaults.removeObject(forKey: Key.subject)
        }
    }

    /// The app side, after a drain: drop what was sent (or finally refused)
    /// and record attempts on the rest. Re-reads first, so a tap queued while
    /// the app was sending is kept.
    static func finishPending(done: Set<String>, attempts: [String: Int]) {
        guard let defaults = UserDefaults(suiteName: suiteName) else { return }
        let remaining = pendingActions().compactMap { action -> PendingAction? in
            if done.contains(action.id) { return nil }
            var copy = action
            if let n = attempts[action.id] { copy.attempts = n }
            return copy
        }
        writePending(remaining, to: defaults)
    }
}

/// The send-later Live Activity, shared by the app (which starts and ends it)
/// and the widget extension (which draws it).
///
/// One activity per server-side scheduled send, keyed by the scheduling id, so
/// a re-scheduled send updates its one activity instead of stacking a second.
/// The schedule itself stays on the server; this type only mirrors it, which is
/// why nothing here can change a send — the deepest the tap can reach is the
/// Scheduled screen, where cancel actually clears `scheduled_at`.
@available(iOS 16.1, *)
struct SendLaterAttributes: ActivityAttributes {
    struct ContentState: Codable, Hashable {
        var subject: String?
        var to: String
        var scheduledFor: Date?
    }
    var scheduledId: String
}
