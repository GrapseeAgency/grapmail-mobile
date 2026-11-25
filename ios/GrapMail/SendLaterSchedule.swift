import Foundation

/// Send later: the times, the window and the wire format, in one place so the
/// sheet, the model and the receipt cannot disagree about any of them.
/// docs/SEND_LATER_IOS_HANDOFF.md §1 and §3; Android's twin is
/// `SendLaterSheet.kt` + `MailboxViewModel.scheduleSend`.
///
/// ── THE WINDOW ──────────────────────────────────────────────────────────────
/// `services/shared/src/lib/mail-schedule.ts` accepts a `sendAt` at least 60 s
/// and at most 30 days ahead of the SERVER's clock and answers anything else
/// with a 400 — it never sends a refused time immediately. The client applies
/// the same rule first, so a time that cannot be scheduled is said in the
/// composer and never reaches the network.
///
/// ── THE CLOCK ───────────────────────────────────────────────────────────────
/// Every option is a wall-clock time in the device time zone
/// (`TimeZone.current`), computed with a Gregorian calendar so "Monday" and
/// "8:00" mean the same thing under any calendar the person has chosen for
/// display. The wire value is UTC with whole seconds (`2026-11-25T02:00:00Z`).
enum SendLaterSchedule {
    /// `MIN_SCHEDULE_AHEAD_MS` on the server.
    static let minimumLead: TimeInterval = 60
    /// `MAX_SCHEDULE_AHEAD_DAYS` (30 × 86 400 000 ms) on the server.
    static let maximumLead: TimeInterval = 30 * 86_400

    /// The preset rows, in the order the sheet lists them.
    enum Option: String, CaseIterable, Identifiable {
        case laterToday, tomorrowMorning, mondayMorning
        var id: String { rawValue }

        var title: String {
            switch self {
            case .laterToday: return String(localized: "Later today", comment: "Send later option: about three hours from now")
            case .tomorrowMorning: return String(localized: "Tomorrow morning", comment: "Send later option: tomorrow at 08:00")
            case .mondayMorning: return String(localized: "Monday morning", comment: "Send later option: next Monday at 08:00")
            }
        }

        var symbol: String {
            switch self {
            case .laterToday: return "clock"
            case .tomorrowMorning: return "sunrise"
            case .mondayMorning: return "calendar.badge.clock"
            }
        }
    }

    struct Choice: Identifiable, Equatable {
        let option: Option
        let date: Date
        var id: String { option.rawValue }
    }

    /// Gregorian, in the device time zone, rebuilt per call so a time-zone
    /// change while the sheet is open is picked up by the next 30 s tick.
    static var calendar: Calendar {
        var c = Calendar(identifier: .gregorian)
        c.timeZone = .current
        c.locale = .autoupdatingCurrent
        return c
    }

    /// The preset rows that apply right now. Every one passes the window check.
    static func choices(now: Date = Date(), calendar cal: Calendar = calendar) -> [Choice] {
        var out: [Choice] = []
        if let date = laterToday(now: now, calendar: cal) { out.append(Choice(option: .laterToday, date: date)) }
        if let date = tomorrowMorning(now: now, calendar: cal) { out.append(Choice(option: .tomorrowMorning, date: date)) }
        if let date = mondayMorning(now: now, calendar: cal) { out.append(Choice(option: .mondayMorning, date: date)) }
        return out.filter { windowError(for: $0.date, now: now) == nil }
    }

    /// Now + 3 h, rounded UP to the next :00 or :30 (14:07 → 17:30; an exact
    /// :30 stays). Hidden from 20:00, and whenever the result is not today.
    static func laterToday(now: Date, calendar cal: Calendar = calendar) -> Date? {
        guard cal.component(.hour, from: now) < 20,
              let minute = cal.dateInterval(of: .minute, for: now)?.start else { return nil }
        let candidate = minute.addingTimeInterval(3 * 3600)
        guard let hour = cal.dateInterval(of: .hour, for: candidate)?.start else { return nil }
        let into = candidate.timeIntervalSince(hour)
        let rounded = into <= 0 ? candidate
            : hour.addingTimeInterval(into <= 1800 ? 1800 : 3600)
        guard cal.isDate(rounded, inSameDayAs: now) else { return nil }
        return rounded
    }

    /// Tomorrow at 08:00. A DST gap at 08:00 moves forward (`.nextTime`).
    static func tomorrowMorning(now: Date, calendar cal: Calendar = calendar) -> Date? {
        guard let tomorrow = cal.date(byAdding: .day, value: 1, to: cal.startOfDay(for: now)) else { return nil }
        return cal.date(bySettingHour: 8, minute: 0, second: 0, of: tomorrow)
    }

    /// The next Monday at 08:00 after today (a week ahead on a Monday). Nil on
    /// a Sunday, where it would be the same instant as "Tomorrow morning".
    static func mondayMorning(now: Date, calendar cal: Calendar = calendar) -> Date? {
        // Gregorian weekday 1 is Sunday whatever the locale's first weekday is.
        guard cal.component(.weekday, from: now) != 1,
              let tomorrow = cal.date(byAdding: .day, value: 1, to: cal.startOfDay(for: now)) else { return nil }
        return cal.nextDate(after: tomorrow,
                            matching: DateComponents(hour: 8, minute: 0, second: 0, weekday: 2),
                            matchingPolicy: .nextTime)
    }

    /// A first suggestion for the custom picker: an hour from now on the next
    /// :00 / :30, so the wheel opens on a time that passes the window check.
    static func pickerDefault(now: Date = Date(), calendar cal: Calendar = calendar) -> Date {
        let candidate = (cal.dateInterval(of: .minute, for: now)?.start ?? now).addingTimeInterval(3600)
        guard let hour = cal.dateInterval(of: .hour, for: candidate)?.start else { return candidate }
        let into = candidate.timeIntervalSince(hour)
        return into <= 0 ? candidate : hour.addingTimeInterval(into <= 1800 ? 1800 : 3600)
    }

    /// The window check the server applies, said the way the composer says it.
    /// Nil when `date` can be scheduled.
    static func windowError(for date: Date, now: Date = Date()) -> String? {
        let lead = date.timeIntervalSince(now)
        if lead < minimumLead {
            return String(localized: "Pick a time at least a minute from now.")
        }
        if lead > maximumLead {
            return String(localized: "Pick a time no more than 30 days ahead.")
        }
        return nil
    }

    /// Drops seconds: the picker edits hours and minutes, and a stray second
    /// carried over from the seed value is not a time anyone chose.
    static func wholeMinute(_ date: Date, calendar cal: Calendar = calendar) -> Date {
        cal.dateInterval(of: .minute, for: date)?.start ?? date
    }

    // ── wire format ──────────────────────────────────────────────────────────

    private static let wireFormatter: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        f.timeZone = TimeZone(identifier: "UTC")
        return f
    }()

    private static let fractionalParser: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return f
    }()

    private static let wholeParser: ISO8601DateFormatter = {
        let f = ISO8601DateFormatter()
        f.formatOptions = [.withInternetDateTime]
        return f
    }()

    /// `sendAt` for `POST /send`: UTC, whole seconds, `Z`.
    static func wireString(_ date: Date) -> String {
        wireFormatter.string(from: Date(timeIntervalSince1970: date.timeIntervalSince1970.rounded(.down)))
    }

    /// Reads the server's instants. `scheduledFor` on the send reply is
    /// `Date.toISOString()` — WITH milliseconds (`…T02:00:00.000Z`), which a
    /// default `ISO8601DateFormatter` refuses — so both shapes are accepted.
    static func parse(_ iso: String?) -> Date? {
        guard let raw = iso?.trimmingCharacters(in: .whitespacesAndNewlines), !raw.isEmpty else { return nil }
        if let date = fractionalParser.date(from: raw) ?? wholeParser.date(from: raw) { return date }
        // A Postgres text timestamp ("2026-11-25 02:00:00+00:00") if a route
        // ever answers with one.
        let normalized = raw.replacingOccurrences(of: " ", with: "T")
        return fractionalParser.date(from: normalized) ?? wholeParser.date(from: normalized)
    }

    // ── display ──────────────────────────────────────────────────────────────

    /// "Tue 8:00 AM" or "Tue 08:00" (the system's 12/24-hour setting), with
    /// month and day added when the time is more than six days away.
    static func display(_ date: Date, now: Date = Date()) -> String {
        var style = Date.FormatStyle.dateTime.weekday(.abbreviated).hour().minute()
        if date.timeIntervalSince(now) > 6 * 86_400 {
            style = style.month(.abbreviated).day()
        }
        return date.formatted(style)
    }

    /// The footer's zone name ("Central European Time"), or its identifier.
    static var timeZoneName: String {
        let zone = TimeZone.current
        return zone.localizedName(for: .generic, locale: .autoupdatingCurrent) ?? zone.identifier
    }
}

/// The account's scheduled sends as this device last knew them, shared by the
/// composer (which inserts a send the moment the server accepts it), the
/// Scheduled screen (which seeds from it and replaces it with every read) and
/// the Live Activity sync. Scoped to the session generation: a sign-out or an
/// account switch starts it empty, so one account's pending sends are never
/// shown under another's name.
///
/// `isComplete` is true only once a whole server list has been stored. The
/// Live Activity keeps the soonest send of the WHOLE list and ends the rest,
/// so it is only ever synced from a complete list — never from a list that
/// holds just the send this device made a moment ago.
final class ScheduledStore {
    static let shared = ScheduledStore()

    private let lock = NSLock()
    private var owner: UUID?
    private var messages: [MailScheduledMessage] = []
    private var complete = false

    private func scoped<T>(_ body: () -> T) -> T {
        lock.lock(); defer { lock.unlock() }
        let generation = SessionStore.generation
        if owner != generation {
            owner = generation
            messages = []
            complete = false
        }
        return body()
    }

    var current: [MailScheduledMessage] { scoped { messages } }
    var isComplete: Bool { scoped { complete } }

    /// A whole server answer.
    func replace(_ list: [MailScheduledMessage]) {
        scoped {
            messages = Self.sorted(list)
            complete = true
        }
    }

    /// One send the server just accepted, placed by its time.
    func insert(_ message: MailScheduledMessage) {
        scoped {
            messages.removeAll { $0.id == message.id }
            messages = Self.sorted(messages + [message])
        }
    }

    func remove(id: String) {
        scoped { messages.removeAll { $0.id == id } }
    }

    private static func sorted(_ list: [MailScheduledMessage]) -> [MailScheduledMessage] {
        list.sorted {
            (SendLaterSchedule.parse($0.scheduledFor) ?? .distantFuture)
                < (SendLaterSchedule.parse($1.scheduledFor) ?? .distantFuture)
        }
    }
}
