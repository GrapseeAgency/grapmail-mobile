import Foundation
import SwiftUI

/// The account's display preferences, held where the mailbox list can read them.
///
/// Mail settings saves `density`, `locale.dateFormat` and `locale.timezone` on the
/// account, and they used to stop there: every row drew at the same height and
/// every date in one fixed style, whatever the screen said. This is the bridge.
/// The account record stays the source of truth (the website edits the same
/// fields); a copy is kept in UserDefaults so the list draws correctly at launch,
/// and the account is re-read once per launch so a change made elsewhere shows up.
///
/// An empty date format or time zone means "automatic": this device's own locale
/// and time zone.
final class MailDisplay: ObservableObject {
    static let shared = MailDisplay()

    @Published private(set) var compact = false
    /// "", "dmy", "mdy" or "ymd".
    @Published private(set) var dateFormat = ""
    /// "" for this device's zone, otherwise an IANA id such as "Europe/Berlin".
    @Published private(set) var timezone = ""

    private enum Key {
        static let density = "grapmail.display.density"
        static let dateFormat = "grapmail.display.dateFormat"
        static let timezone = "grapmail.display.timezone"
    }

    private var refreshed = false

    private init() {
        let d = UserDefaults.standard
        compact = d.string(forKey: Key.density) == "compact"
        dateFormat = Self.normalized(d.string(forKey: Key.dateFormat) ?? "")
        timezone = d.string(forKey: Key.timezone) ?? ""
    }

    /// The zone timestamps are drawn in.
    var zone: TimeZone { Self.zone(timezone) ?? .autoupdatingCurrent }

    static func zone(_ id: String) -> TimeZone? {
        let trimmed = id.trimmingCharacters(in: .whitespaces)
        return trimmed.isEmpty ? nil : TimeZone(identifier: trimmed)
    }

    /// Take the account's settings as the new truth and remember them.
    func apply(_ settings: ProfileSettings?) {
        guard let settings else { return }
        let apply = {
            self.compact = settings.density == "compact"
            self.dateFormat = Self.normalized(settings.locale.dateFormat)
            self.timezone = Self.zone(settings.locale.timezone) == nil ? "" : settings.locale.timezone
            let d = UserDefaults.standard
            d.set(self.compact ? "compact" : "comfortable", forKey: Key.density)
            d.set(self.dateFormat, forKey: Key.dateFormat)
            d.set(self.timezone, forKey: Key.timezone)
        }
        if Thread.isMainThread { apply() } else { DispatchQueue.main.async(execute: apply) }
    }

    /// Re-read the account once per launch, so a change made on the website or
    /// another device reaches this list. Called by the first row that draws.
    func refreshOnce() {
        guard !refreshed, let token = SessionStore.token else { return }
        refreshed = true
        Task {
            if let settings = try? await ApiClient.profile(token: token).profile?.settings {
                self.apply(settings)
            }
        }
    }

    private static func normalized(_ value: String) -> String {
        let v = value.trimmingCharacters(in: .whitespaces).lowercased()
        return ["dmy", "mdy", "ymd"].contains(v) ? v : ""
    }
}
