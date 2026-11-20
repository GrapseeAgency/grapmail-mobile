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

    enum Key {
        static let unread = "widget.unread"
        static let account = "widget.account"
        static let sender = "widget.sender"
        static let subject = "widget.subject"
        static let updatedAt = "widget.updatedAt"
        static let signedIn = "widget.signedIn"
        static let recent = "widget.recent"
    }

    /// What the app last knew, as the widget reads it. Every field optional:
    /// nil means "never written", which renders as "no read yet" — never as a
    /// zero the app did not actually see.
    /// Up to three of the newest messages, as the widget draws them: who and
    /// what, never a full message - the widget shows, the app opens.
    struct RecentItem: Codable, Hashable {
        var sender: String
        var subject: String
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
