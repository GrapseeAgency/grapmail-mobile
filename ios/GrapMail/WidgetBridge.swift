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
    /// Called by `MailPoll.runOnce` after `GET /stats` answered. `latest` is
    /// the newest inbox row when the poll fetched one, and nil when it did
    /// not — nil clears the sender lines rather than leaving a stale name on
    /// the Lock Screen under a fresh count.
    static func update(unread: Int?, account: String?, latest: MailMessage?, recent: [MailMessage] = [], signedIn: Bool) {
        guard let defaults = UserDefaults(suiteName: WidgetShared.suiteName) else { return }
        if let unread { defaults.set(unread, forKey: WidgetShared.Key.unread) }
        if let account, !account.isEmpty { defaults.set(account, forKey: WidgetShared.Key.account) }
        // display is a non-optional String on the model, so optional chaining
        // stops at `from` and the result is String?. nilIfBlank() is declared
        // on String, not String?, so the optional is flattened first.
        let sender = (latest?.from?.display ?? "").nilIfBlank() ?? latest?.from?.address.nilIfBlank()
        if let sender {
            defaults.set(sender, forKey: WidgetShared.Key.sender)
        } else {
            defaults.removeObject(forKey: WidgetShared.Key.sender)
        }
        if let latest, !latest.isE2eEncrypted, let subject = latest.subject?.nilIfBlank() {
            defaults.set(subject, forKey: WidgetShared.Key.subject)
        } else {
            // An encrypted message's subject line must not leak onto the Lock
            // Screen through the widget either.
            defaults.removeObject(forKey: WidgetShared.Key.subject)
        }
        let items = recent.prefix(3).map { message -> WidgetShared.RecentItem in
            let sender = message.from?.display.nilIfBlank() ?? message.from?.address.nilIfBlank() ?? "Unknown sender"
            let subject = message.isE2eEncrypted ? "Encrypted message" : (message.subject?.nilIfBlank() ?? "No subject")
            return WidgetShared.RecentItem(sender: sender, subject: subject)
        }
        if let data = try? JSONEncoder().encode(Array(items)) {
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

    private static func reload() {
        #if canImport(WidgetKit)
        WidgetCenter.shared.reloadAllTimelines()
        #endif
    }
}
