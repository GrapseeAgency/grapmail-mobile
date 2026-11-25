import Foundation

#if canImport(WidgetKit)
import WidgetKit
#endif

/// The app half of the widget contract (the widget half is
/// `ios/WidgetShared/WidgetShared.swift`, compiled into both targets): after
/// every read the app performs, the numbers it actually saw are written where
/// the widget can show them.
///
/// Nothing here fetches. Every call site has just finished a real read, so the
/// widget's number is the app's number — timestamped at the moment it was
/// true, and cleared the moment the app can no longer stand behind it
/// (sign-out, account deletion).
enum WidgetBridge {
    /// Rows the widget can show: three on the medium widget, five on the
    /// large one. The extra rows also refill a medium widget at once when a
    /// row is archived from it.
    static let recentLimit = 5

    /// Called by `MailPoll.runOnce` after `GET /stats` answered. `latest` is
    /// the newest inbox row when the poll fetched one, and nil when it did
    /// not — nil clears the sender lines rather than leaving a stale name on
    /// the Lock Screen under a fresh count.
    static func update(unread: Int?, account: String?, latest: MailMessage?, recent: [MailMessage] = [], signedIn: Bool) {
        guard let defaults = UserDefaults(suiteName: WidgetShared.suiteName) else { return }
        // Widget taps the app has not sent yet (WidgetActionSync) are laid
        // over this read, so a poll that lands before the send cannot bring an
        // archived row back or re-bold a row marked read.
        let pending = WidgetShared.pendingActions().filter {
            $0.account.caseInsensitiveCompare(account ?? "") == .orderedSame
        }
        var items = recent.prefix(recentLimit).map { message -> WidgetShared.RecentItem in
            let id = WidgetShared.mailId(message.id)
            let threadId = WidgetShared.mailId(message.threadId)
            if message.isE2eEncrypted {
                return WidgetShared.RecentItem(sender: String(localized: "Encrypted message"), subject: "",
                                               id: id, threadId: threadId,
                                               isRead: message.isRead, isStarred: message.isStarred)
            }
            let sender = (message.from?.display ?? "").nilIfBlank() ?? (message.from?.address ?? "").nilIfBlank() ?? String(localized: "Unknown sender")
            let subject = message.subject?.nilIfBlank() ?? String(localized: "No subject")
            return WidgetShared.RecentItem(sender: sender, subject: subject, id: id, threadId: threadId,
                                           isRead: message.isRead, isStarred: message.isStarred)
        }
        var shownUnread = unread
        for action in pending {
            WidgetShared.apply(action.kind, messageId: action.messageId, recent: &items, unread: &shownUnread)
        }
        // The newest row still on the widget once pending archives are laid
        // over: an archived newest message must not stay on the Lock Screen.
        let archived = Set(pending.filter { $0.kind == .archive }.map(\.messageId))
        let head = [latest].compactMap { $0 } + recent
        let shownLatest = head.first { WidgetShared.mailId($0.id).map { !archived.contains($0) } ?? true }

        if let shownUnread { defaults.set(shownUnread, forKey: WidgetShared.Key.unread) }
        if let account, !account.isEmpty { defaults.set(account, forKey: WidgetShared.Key.account) }
        // display is a non-optional String on the model, so optional chaining
        // stops at `from` and the result is String?. nilIfBlank() is declared
        // on String, not String?, so the optional is flattened first.
        let sender = (shownLatest?.from?.display ?? "").nilIfBlank() ?? (shownLatest?.from?.address ?? "").nilIfBlank()
        // Stand-in words are localized here, in the app, so the widget shows
        // them in the language the app last ran in.
        if shownLatest?.isE2eEncrypted == true {
            defaults.set(String(localized: "Encrypted message"), forKey: WidgetShared.Key.sender)
        } else if let sender {
            defaults.set(sender, forKey: WidgetShared.Key.sender)
        } else {
            defaults.removeObject(forKey: WidgetShared.Key.sender)
        }
        if let shownLatest, !shownLatest.isE2eEncrypted, let subject = shownLatest.subject?.nilIfBlank() {
            defaults.set(subject, forKey: WidgetShared.Key.subject)
        } else {
            // An encrypted message's subject line must not leak onto the Lock
            // Screen through the widget either.
            defaults.removeObject(forKey: WidgetShared.Key.subject)
        }
        if let data = try? JSONEncoder().encode(items) {
            defaults.set(data, forKey: WidgetShared.Key.recent)
        } else {
            defaults.removeObject(forKey: WidgetShared.Key.recent)
        }
        defaults.set(Date().timeIntervalSince1970, forKey: WidgetShared.Key.updatedAt)
        defaults.set(signedIn, forKey: WidgetShared.Key.signedIn)
        reload()
    }

    /// Sign-out and account deletion: the next person to pick up the phone
    /// must not read the last one's sender lines off the Lock Screen.
    ///
    /// Unsent widget taps are deliberately kept: each names its own account,
    /// and `WidgetActionSync` drops any whose account is no longer signed in
    /// on this phone — so they can never be sent as somebody else.
    static func clear() {
        guard let defaults = UserDefaults(suiteName: WidgetShared.suiteName) else { return }
        for key in [WidgetShared.Key.unread, WidgetShared.Key.account,
                    WidgetShared.Key.sender, WidgetShared.Key.subject,
                    WidgetShared.Key.recent, WidgetShared.Key.updatedAt] {
            defaults.removeObject(forKey: key)
        }
        defaults.set(false, forKey: WidgetShared.Key.signedIn)
        reload()
    }

    static func reload() {
        #if canImport(WidgetKit)
        WidgetCenter.shared.reloadTimelines(ofKind: WidgetShared.unreadWidgetKind)
        #endif
    }
}
