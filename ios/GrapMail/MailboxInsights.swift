import Foundation

// MARK: - Inbox insights (the Good morning card, the row code chip, Inbox Zero)
//
// Everything here is read from what the list already holds: the rows the
// server returned, the follow-up memory (MailFeaturePrefs), the categorizer
// (MailIntelligence.swift), the action-card extractor (MailTrust.swift) and
// CodeFinder. Nothing fetches, nothing leaves the device, and sealed
// (end-to-end encrypted) mail is never read for any of it.
//
// The Android builder implements the same rules from the same spec; the
// definitions below are the ones to keep in step:
//
//   needs a reply  -- an unread inbound message, not encrypted, not from the
//                     signed-in address, that the categorizer files under
//                     People, AND either belongs to a thread the follow-up
//                     memory is waiting on, or whose subject/preview asks
//                     something ("?" or one of `askPhrases`).
//   package        -- an inbound, unencrypted message from the last 7 days
//                     whose subject + preview yield a `.package` action card
//                     (tracking number or a delivery day), and only while the
//                     smart features are on (MailFeaturePrefs).
//   sign-in code   -- `CodeFinder.find(message)` answers, the message is
//                     inbound and unencrypted, and it arrived in the last
//                     60 minutes (5 minutes of clock skew tolerated).

/// The three things the Good morning card counts, in the order it names them.
enum InboxInsightKind: String, CaseIterable, Identifiable {
    case needsReply, packages, codes

    var id: String { rawValue }

    /// One summary part. A whole phrase per count, never a spliced noun.
    func summary(_ count: Int) -> String {
        switch self {
        case .needsReply:
            return count == 1
                ? String(localized: "1 needs a reply", comment: "Inbox summary part: one message waiting for the person's answer")
                : String(localized: "\(count) need a reply", comment: "Inbox summary part: number of messages waiting for the person's answer")
        case .packages:
            return count == 1
                ? String(localized: "1 package arriving", comment: "Inbox summary part: one parcel on its way")
                : String(localized: "\(count) packages arriving", comment: "Inbox summary part: number of parcels on their way")
        case .codes:
            return count == 1
                ? String(localized: "1 sign-in code", comment: "Inbox summary part: one recent sign-in code")
                : String(localized: "\(count) sign-in codes", comment: "Inbox summary part: number of recent sign-in codes")
        }
    }

    /// The active-filter chip's words.
    var filterTitle: String {
        switch self {
        case .needsReply: return String(localized: "Needs a reply", comment: "Inbox filter chip")
        case .packages: return String(localized: "Packages", comment: "Inbox filter chip")
        case .codes: return String(localized: "Sign-in codes", comment: "Inbox filter chip")
        }
    }

    var symbol: String {
        switch self {
        case .needsReply: return "arrowshape.turn.up.left"
        case .packages: return "shippingbox"
        case .codes: return "key"
        }
    }
}

/// One non-zero part of the summary line.
struct InboxInsightPart: Identifiable, Equatable {
    let kind: InboxInsightKind
    let count: Int
    var id: String { kind.rawValue }
    var text: String { kind.summary(count) }
}

enum MailboxInsights {
    /// How long a sign-in code stays worth offering.
    static let codeWindow: TimeInterval = 60 * 60
    /// A message stamped a little in the future (server and phone clocks
    /// disagree) still counts as just arrived.
    static let clockSkew: TimeInterval = 5 * 60
    /// A tracking mail older than this is not "arriving" (Android's
    /// PACKAGE_WINDOW_MS).
    static let packageWindow: TimeInterval = 7 * 24 * 60 * 60

    /// What one render pass needs to decide every row the same way.
    struct Context {
        let now: Date
        /// The signed-in address, lowercased; nil until `/me` answered.
        let me: String?
        /// Thread ids the follow-up memory is waiting on.
        let awaiting: Set<String>
        /// The smart-features switch, read once per pass.
        let smartFeatures: Bool

        init(now: Date, me: String?, awaiting: Set<String>,
             smartFeatures: Bool = MailFeaturePrefs.shared.smartFeaturesEnabled) {
            self.now = now
            self.me = me?.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
            self.awaiting = awaiting
            self.smartFeatures = smartFeatures
        }

        /// The current follow-up memory, read once per pass. `smartFeatures`
        /// lets a view pass its @AppStorage copy of the switch, so a flip in
        /// Settings redraws the card; it defaults to the stored value.
        static func current(now: Date, me: String?,
                            smartFeatures: Bool = MailFeaturePrefs.shared.smartFeaturesEnabled) -> Context {
            Context(now: now, me: me,
                    awaiting: Set(MailFeaturePrefs.shared.awaitingReplies.map { $0.id }),
                    smartFeatures: smartFeatures)
        }
    }

    /// Phrases that ask the reader for something, on top of a question mark.
    static let askPhrases = ["can you", "could you", "would you", "are you able", "do you have",
                             "let me know", "please confirm", "please reply", "get back to me",
                             "what do you think", "your thoughts", "rsvp"]

    // ── per-message facts, cached ─────────────────────────────────────────

    /// The text-derived facts of one row. The regex batteries (CodeFinder,
    /// the action cards) are the expensive part, and the list asks on every
    /// render, so they are computed once per id + text and kept.
    private final class Facts: NSObject {
        let code: String?
        let package: Bool
        let asks: Bool
        init(code: String?, package: Bool, asks: Bool) {
            self.code = code
            self.package = package
            self.asks = asks
        }
    }

    private static let cache: NSCache<NSString, Facts> = {
        let cache = NSCache<NSString, Facts>()
        cache.countLimit = 1_000
        return cache
    }()

    private static func facts(_ m: MailMessage) -> Facts {
        let subject = m.subject ?? ""
        let snippet = m.snippet ?? ""
        let key = "\(m.id)|\(m.isE2eEncrypted)|\(subject.hashValue)|\(snippet.hashValue)" as NSString
        if let hit = cache.object(forKey: key) { return hit }
        let facts: Facts
        if m.isE2eEncrypted {
            // Sealed mail is never read here, not even its subject.
            facts = Facts(code: nil, package: false, asks: false)
        } else {
            let lowered = (subject + "\n" + snippet).lowercased()
            facts = Facts(
                code: CodeFinder.find(m),
                package: ActionCardExtractor.extract(subject: subject, body: snippet)
                    .contains { $0.kind == .package },
                asks: lowered.contains("?") || askPhrases.contains { lowered.contains($0) })
        }
        cache.setObject(facts, forKey: key)
        return facts
    }

    // ── time ──────────────────────────────────────────────────────────────

    private static let isoFractional: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()
    private static let isoPlain = ISO8601DateFormatter()
    private static let sqlStamp: DateFormatter = {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US_POSIX")
        f.dateFormat = "yyyy-MM-dd HH:mm:ss"
        f.timeZone = .autoupdatingCurrent
        return f
    }()

    /// When the row arrived, or nil when the server gave no usable instant
    /// (then nothing time-bound is offered for it).
    static func receivedAt(_ m: MailMessage) -> Date? {
        guard let raw = m.createdAt, !raw.isEmpty else { return nil }
        return isoFractional.date(from: raw) ?? isoPlain.date(from: raw) ?? sqlStamp.date(from: raw)
    }

    private static func isInbound(_ m: MailMessage) -> Bool { m.direction != "outbound" }

    // ── the three questions ───────────────────────────────────────────────

    /// The row's sign-in code while it is still fresh, or nil. Sealed mail
    /// never answers (CodeFinder already refuses it; checked here too).
    static func freshCode(_ m: MailMessage, now: Date = Date()) -> String? {
        guard !m.isE2eEncrypted, isInbound(m), let at = receivedAt(m) else { return nil }
        let age = now.timeIntervalSince(at)
        guard age >= -clockSkew, age <= codeWindow else { return nil }
        return facts(m).code
    }

    /// Smart features on, arrived in the last `packageWindow` (a row with no
    /// usable instant is never counted), and a package card in the text.
    static func isPackage(_ m: MailMessage, _ ctx: Context) -> Bool {
        guard ctx.smartFeatures, !m.isE2eEncrypted, isInbound(m), let at = receivedAt(m),
              ctx.now.timeIntervalSince(at) <= packageWindow else { return false }
        return facts(m).package
    }

    static func needsReply(_ m: MailMessage, _ ctx: Context) -> Bool {
        guard !m.isE2eEncrypted, !m.isRead, isInbound(m) else { return false }
        let from = (m.from?.address ?? "").lowercased()
        if let me = ctx.me, !me.isEmpty, from == me { return false }
        let decision = MailCategorizer.classify(
            senderAddress: m.from?.address ?? "",
            subject: m.subject ?? "",
            hasListUnsubscribe: m.unsubscribe != nil,
            knownSenders: MailFeaturePrefs.shared.prioritySenders)
        guard decision.category == .people else { return false }
        if let thread = m.threadId, ctx.awaiting.contains(thread) { return true }
        return facts(m).asks
    }

    static func matches(_ kind: InboxInsightKind, _ m: MailMessage, _ ctx: Context) -> Bool {
        switch kind {
        case .needsReply: return needsReply(m, ctx)
        case .packages: return isPackage(m, ctx)
        case .codes: return freshCode(m, now: ctx.now) != nil
        }
    }

    /// The non-zero parts, in the card's order. Empty means all caught up.
    static func parts(_ rows: [MailMessage], _ ctx: Context) -> [InboxInsightPart] {
        InboxInsightKind.allCases.compactMap { kind in
            let count = rows.reduce(0) { $0 + (matches(kind, $1, ctx) ? 1 : 0) }
            return count > 0 ? InboxInsightPart(kind: kind, count: count) : nil
        }
    }

    /// "3 need a reply · 1 package arriving · 2 sign-in codes".
    static func summaryLine(_ parts: [InboxInsightPart]) -> String {
        parts.isEmpty
            ? String(localized: "You're all caught up", comment: "Inbox summary when nothing needs attention")
            : parts.map(\.text).joined(separator: " · ")
    }

    /// The first word of the mailbox's own name, or nil when `/me` gave none
    /// (or gave an address rather than a name).
    static func firstName(_ accountName: String?) -> String? {
        guard let raw = accountName?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty
        else { return nil }
        let first = raw.split(whereSeparator: { $0.isWhitespace }).first.map(String.init) ?? raw
        return first.contains("@") ? nil : first
    }
}

// MARK: - Time of day

/// The greeting's three periods, by the phone's local clock: morning 5–12,
/// afternoon 12–17, evening 17–5. Dismissing the card hides it until the
/// period changes, so the period carries a stable id.
enum InboxDayPart: String {
    case morning, afternoon, evening

    static func at(_ date: Date, calendar: Calendar = .current) -> InboxDayPart {
        switch calendar.component(.hour, from: date) {
        case 5..<12: return .morning
        case 12..<17: return .afternoon
        default: return .evening
        }
    }

    /// "2026-11-24.morning". The small hours (0–5) belong to the previous
    /// day's evening, so a card dismissed at 23:00 stays dismissed at 01:00.
    static func periodID(_ date: Date, calendar: Calendar = .current) -> String {
        let part = at(date, calendar: calendar)
        var day = date
        if part == .evening, calendar.component(.hour, from: date) < 5 {
            day = calendar.date(byAdding: .day, value: -1, to: date) ?? date
        }
        let c = calendar.dateComponents([.year, .month, .day], from: day)
        return "\(c.year ?? 0)-\(c.month ?? 0)-\(c.day ?? 0).\(part.rawValue)"
    }

    func greeting(_ name: String?) -> String {
        if let name {
            switch self {
            case .morning: return String(localized: "Good morning, \(name)", comment: "Inbox greeting; the person's first name")
            case .afternoon: return String(localized: "Good afternoon, \(name)", comment: "Inbox greeting; the person's first name")
            case .evening: return String(localized: "Good evening, \(name)", comment: "Inbox greeting; the person's first name")
            }
        }
        switch self {
        case .morning: return String(localized: "Good morning", comment: "Inbox greeting without a name")
        case .afternoon: return String(localized: "Good afternoon", comment: "Inbox greeting without a name")
        case .evening: return String(localized: "Good evening", comment: "Inbox greeting without a name")
        }
    }
}

// MARK: - Handled today (Inbox Zero's number)

/// How many messages this device archived, trashed, reported, snoozed or
/// deleted for good today, per account. Counted in ONE place: MailboxModel,
/// where the server accepted the write (`actOn` -- swipes, row menus and the
/// reader --, `act` for the bulk toolbar, `deletePermanently`); an accepted
/// Undo takes its count back there too. Kept in UserDefaults as
/// `{day, count}` under one key per account, and reset by simply reading a
/// different day. Never synced, never sent.
enum MailHandledToday {
    /// The filings that take mail off the person's plate.
    static func counts(_ action: MailAction) -> Bool {
        switch action {
        case .archive, .trash, .spam, .snooze: return true
        default: return false
        }
    }

    /// The inverse an undo banner offers for a counted filing.
    static func undoes(_ inverse: MailAction?) -> Bool {
        switch inverse {
        case .unarchive?, .restore?, .unspam?: return true
        default: return false
        }
    }

    private static func accountID(_ account: String?) -> String {
        let id = (account ?? "").trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
        return id.isEmpty ? "default" : id
    }

    private static func key(_ account: String?) -> String { "gm.handledToday.\(accountID(account))" }

    static func dayStamp(_ date: Date = Date(), calendar: Calendar = .current) -> String {
        let c = calendar.dateComponents([.year, .month, .day], from: date)
        return "\(c.year ?? 0)-\(c.month ?? 0)-\(c.day ?? 0)"
    }

    static func count(account: String?, now: Date = Date(), defaults: UserDefaults = .standard) -> Int {
        guard let entry = defaults.dictionary(forKey: key(account)),
              entry["day"] as? String == dayStamp(now) else { return 0 }
        return max(0, entry["count"] as? Int ?? 0)
    }

    static func record(_ n: Int, account: String?, now: Date = Date(), defaults: UserDefaults = .standard) {
        guard n != 0 else { return }
        let next = max(0, count(account: account, now: now, defaults: defaults) + n)
        defaults.set(["day": dayStamp(now), "count": next], forKey: key(account))
    }

    static func undo(_ n: Int, account: String?) { record(-n, account: account) }

    /// True the first time it is asked on a given day for an account, then
    /// false until tomorrow: the confetti's once-a-day rule.
    static func takeConfetti(account: String?, now: Date = Date(), defaults: UserDefaults = .standard) -> Bool {
        let k = "gm.inboxZero.confettiDay.\(accountID(account))"
        let today = dayStamp(now)
        guard defaults.string(forKey: k) != today else { return false }
        defaults.set(today, forKey: k)
        return true
    }
}
