import Foundation

/// `settings.quietHours`: a daily no-alert window in the zone of the device that saved it.
struct QuietHoursInfo: Codable, Equatable {
    var enabled = false
    /// `HH:MM`, 24-hour; may cross midnight.
    var start = "22:00"
    var end = "07:00"
    var timeZone = ""
    /// VIP senders and followed conversations still alert inside the window.
    var allowVip = true

    private enum CodingKeys: String, CodingKey { case enabled, start, end, timeZone, allowVip }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        enabled = c.lenient(.enabled, false)
        start = c.lenient(.start, "22:00")
        end = c.lenient(.end, "07:00")
        timeZone = c.lenient(.timeZone, "")
        allowVip = c.lenient(.allowVip, true)
    }
    init() {}

    var dictionary: [String: Any] {
        ["enabled": enabled, "start": start, "end": end, "timeZone": timeZone, "allowVip": allowVip]
    }
}

/// `settings.notifications`: who may interrupt, and when. The server enforces it
/// before it sends a push (backend lib/notification-policy.ts); `NotificationPolicy`
/// applies the same rules to the alerts this app posts itself.
struct NotificationSettingsInfo: Codable, Equatable {
    /// `all`, `priority` (VIP senders and followed conversations) or `none`.
    var mode = "all"
    /// Addresses or whole domains.
    var vipSenders: [String] = []
    var mutedThreads: [String] = []
    /// "Notify me": these conversations alert in every mode.
    var watchedThreads: [String] = []
    var quietHours = QuietHoursInfo()
    var showPreview = true

    private enum CodingKeys: String, CodingKey {
        case mode, vipSenders, mutedThreads, watchedThreads, quietHours, showPreview
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        mode = c.lenient(.mode, "all")
        vipSenders = c.lenient(.vipSenders, [])
        mutedThreads = c.lenient(.mutedThreads, [])
        watchedThreads = c.lenient(.watchedThreads, [])
        quietHours = c.lenient(.quietHours, QuietHoursInfo())
        showPreview = c.lenient(.showPreview, true)
    }
    init() {}

    /// The whole object, every key present: the server keeps any key that is not sent.
    var dictionary: [String: Any] {
        ["mode": mode, "vipSenders": vipSenders, "mutedThreads": mutedThreads,
         "watchedThreads": watchedThreads, "quietHours": quietHours.dictionary, "showPreview": showPreview]
    }
}

/// The account's notification rules, on this device.
///
/// The server applies them before it pushes, so a held-back message never wakes
/// the phone, on iOS as everywhere. This copy applies the same rules to the alerts
/// the app posts itself after a background check, kept line for line with the
/// server's decideNotification, whose tests are the spec. One copy per mailbox, so
/// each alert follows the rules of the account it is for.
enum NotificationPolicy {
    struct Decision: Equatable {
        let notify: Bool
        let generic: Bool
    }

    private static let staleAfter: TimeInterval = 6 * 60 * 60
    private static let security = "security@grapsee.com"

    private static func key(_ base: String, _ account: String?) -> String {
        "gm.notifyPolicy.\(base).\((account ?? "").lowercased())"
    }

    static func cached(account: String?) -> NotificationSettingsInfo {
        guard let data = UserDefaults.standard.data(forKey: key("doc", account)),
              let value = try? JSONDecoder().decode(NotificationSettingsInfo.self, from: data) else {
            return NotificationSettingsInfo()
        }
        return value
    }

    static func store(_ settings: NotificationSettingsInfo, account: String?) {
        guard let data = try? JSONEncoder().encode(settings) else { return }
        UserDefaults.standard.set(data, forKey: key("doc", account))
        UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: key("at", account))
    }

    static func isVip(_ settings: NotificationSettingsInfo, _ fromAddress: String?) -> Bool {
        let from = (fromAddress ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        guard !from.isEmpty else { return false }
        let domain = from.split(separator: "@").last.map(String.init) ?? ""
        return settings.vipSenders.contains { v in
            v.contains("@") ? v == from : (domain == v || domain.hasSuffix("." + v))
        }
    }

    private static func minutes(_ clock: String) -> Int? {
        let parts = clock.split(separator: ":")
        guard parts.count == 2, parts[0].count == 2, parts[1].count == 2,
              let h = Int(parts[0]), let m = Int(parts[1]), (0..<24).contains(h), (0..<60).contains(m) else { return nil }
        return h * 60 + m
    }

    static func inQuietHours(_ settings: NotificationSettingsInfo, now: Date = Date()) -> Bool {
        let q = settings.quietHours
        guard q.enabled, let s = minutes(q.start), let e = minutes(q.end) else { return false }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = (q.timeZone.isEmpty ? nil : TimeZone(identifier: q.timeZone)) ?? TimeZone(identifier: "UTC")!
        let parts = calendar.dateComponents([.hour, .minute], from: now)
        let t = (parts.hour ?? 0) * 60 + (parts.minute ?? 0)
        return s < e ? (t >= s && t < e) : (t >= s || t < e)
    }

    /// The decision for one message, the same as the server's.
    static func decide(_ settings: NotificationSettingsInfo, fromAddress: String?, threadKey: String?, now: Date = Date()) -> Decision {
        let generic = !settings.showPreview
        if (fromAddress ?? "").trimmingCharacters(in: .whitespaces).lowercased() == security {
            return Decision(notify: true, generic: generic)
        }
        let thread = threadKey ?? ""
        if !thread.isEmpty, settings.mutedThreads.contains(thread) { return Decision(notify: false, generic: generic) }
        let watched = !thread.isEmpty && settings.watchedThreads.contains(thread)
        let priority = watched || isVip(settings, fromAddress)
        if settings.mode == "none", !watched { return Decision(notify: false, generic: generic) }
        if settings.mode == "priority", !priority { return Decision(notify: false, generic: generic) }
        if inQuietHours(settings, now: now), !(settings.quietHours.allowVip && priority) {
            return Decision(notify: false, generic: generic)
        }
        return Decision(notify: true, generic: generic)
    }

    static func decide(account: String?, fromAddress: String?, threadKey: String?) -> Decision {
        decide(cached(account: account), fromAddress: fromAddress, threadKey: threadKey)
    }

    /// A count-only alert names no sender, so it goes only when any message would.
    static func allowsSummary(account: String?, now: Date = Date()) -> Bool {
        let s = cached(account: account)
        return s.mode == "all" && !inQuietHours(s, now: now)
    }

    /// Re-read the rules if this copy is older than six hours. Never throws.
    static func refreshIfStale(account: String?, token: String) async {
        let at = UserDefaults.standard.double(forKey: key("at", account))
        guard Date().timeIntervalSince1970 - at >= staleAfter else { return }
        if let read = try? await ApiClient.profile(token: token).profile?.settings?.notifications {
            store(read, account: account)
        }
    }

    /// Change the rules: read the server's current copy, apply `change`, save, keep
    /// what the server stored. nil on success, otherwise the sentence to show.
    static func update(_ change: (inout NotificationSettingsInfo) -> Void) async -> String? {
        guard let token = SessionStore.token else { return "Sign in again." }
        let account = SessionStore.address
        do {
            guard var next = try await ApiClient.profile(token: token).profile?.settings?.notifications else {
                return "Your notification settings could not be read. Try again."
            }
            change(&next)
            let saved = try await ApiClient.patchProfile(token: token, settings: ["notifications": next.dictionary])
            if !saved.success, let error = saved.error { return error }
            store(saved.profile?.settings?.notifications ?? next, account: account)
            return nil
        } catch {
            return (error as? ApiError)?.userMessage ?? error.localizedDescription
        }
    }

    // ── conversation and sender shortcuts (the reader's menu) ──────────────

    static func isMuted(_ threadKey: String) -> Bool { cached(account: SessionStore.address).mutedThreads.contains(threadKey) }
    static func isWatched(_ threadKey: String) -> Bool { cached(account: SessionStore.address).watchedThreads.contains(threadKey) }
    static func isVip(_ address: String?) -> Bool { isVip(cached(account: SessionStore.address), address) }

    static func setMuted(_ threadKey: String, _ muted: Bool) async -> String? {
        await update { s in
            s.mutedThreads.removeAll { $0 == threadKey }
            if muted {
                s.mutedThreads.append(threadKey)
                s.mutedThreads = Array(s.mutedThreads.suffix(500))
                s.watchedThreads.removeAll { $0 == threadKey }
            }
        }
    }

    static func setWatched(_ threadKey: String, _ watched: Bool) async -> String? {
        await update { s in
            s.watchedThreads.removeAll { $0 == threadKey }
            if watched {
                s.watchedThreads.append(threadKey)
                s.watchedThreads = Array(s.watchedThreads.suffix(500))
                s.mutedThreads.removeAll { $0 == threadKey }
            }
        }
    }

    static func setVip(_ address: String, _ vip: Bool) async -> String? {
        let key = address.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return await update { s in
            s.vipSenders.removeAll { $0 == key }
            if vip { s.vipSenders.append(key) }
        }
    }
}
