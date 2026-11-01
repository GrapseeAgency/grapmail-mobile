import Foundation
import SwiftUI

/// Mailbox sector — the parts every mail screen is assembled from.
///
/// Kept dumb on purpose: none of these fetch, and none hold state that belongs
/// to a screen. The two pieces of logic they do carry are the badge rule and the
/// time format, because both are claims about the data and belong in one place
/// rather than in twenty call sites where they can quietly disagree.
enum MailUI {

    /// The avatar disc's colour for a sender. The ramp itself is a token —
    /// `GM.avatarRamp` in `Theme.swift` — which holds the web reference's
    /// `AVATAR_COLOURS` list with a value per theme, because three of the eight web
    /// entries are theme variables rather than fixed avatar hues. Nothing here picks
    /// a colour of its own, which is what lets this file fall inside the no-literals
    /// gate instead of being exempted from it.
    ///
    /// A sender keeps its colour across visits because the choice is a hash of the
    /// address, not a position in the list — the same rule the web client follows
    /// (`page.tsx:212`).

    /// The ramp slot for a sender, on the same rule Android applies
    /// (`Mail.kt`'s `rampIndex`): the address when there is one, the name only
    /// when the server never named an address.
    ///
    /// The two implementations have to agree exactly or the same sender wears a
    /// different colour on each platform, which is visible in the screenshot
    /// pair for one message. That means the same key, the same accumulator, and
    /// the same characters: `utf16`, not `unicodeScalars`, because Kotlin
    /// iterates UTF-16 units and an emoji in a display name would fold to
    /// different numbers. Masking to 30 bits every round keeps the accumulator
    /// non-negative, so `%` needs no `abs()` — and a masked value taken mod 2³⁰
    /// is the same whether the product overflowed at 32 bits first, which is how
    /// it matches Kotlin's wrapping `Int` arithmetic.
    static func tint(address: String, display: String = "") -> Color {
        // Hash the address rather than the name: two people called "Team" should
        // not share a colour, and one person renaming themselves should not move.
        let key = address.isEmpty ? (display.isEmpty ? "unknown" : display) : address
        var hash: Int = 0
        for ch in key.utf16 { hash = (hash &* 31 &+ Int(ch)) & 0x3FFFFFFF }
        return GM.avatarRamp[hash % GM.avatarRamp.count]
    }
}

// ── the badge rule ───────────────────────────────────────────────────────────

/// A count, or nothing.
///
/// `nil` means the server did not answer this box — `/stats` reports exactly five
/// of them (`grapmail.ts:664-668`), so Starred, Archive and All Mail are always
/// `nil`. Drawing `0` there would claim the folder is empty when the truth is
/// that nobody was asked. Same rule that made the security hub's unknown verdict
/// render as "couldn't load" instead of "Off".
struct CountBadge: View {
    let count: Int?
    var max: Int = 99

    var body: some View {
        if let count {
            Text(count > max ? "\(max)+" : "\(count)")
                .font(.system(size: 11, weight: .semibold))
                .foregroundColor(GM.primarySolid)
                .padding(.horizontal, 7).padding(.vertical, 1)
                .background(Capsule().fill(GM.softFill))
        }
    }
}

/// The unread pill on the Mail tab and the drawer's inbox row.
struct UnreadPill: View {
    let count: Int
    var body: some View {
        if count > 0 {
            Text("\(count)")
                .font(.system(size: 11, weight: .bold))
                .foregroundColor(.white)
                .padding(.horizontal, 7).padding(.vertical, 2)
                .background(Capsule().fill(GM.primarySolid))
        }
    }
}

// ── sender identity ──────────────────────────────────────────────────────────

/// A sender's initial on a stable colour.
///
/// The initial comes from the display name when there is one and from the address
/// when there is not — never from the address's first character, which would
/// render every `@grapsee.com` sender under the same letter.
struct SenderAvatar: View {
    let display: String
    let address: String
    var size: CGFloat = 40

    private var initial: String {
        let source = display.trimmingCharacters(in: .whitespacesAndNewlines)
        let fallback = String(address.split(separator: "@").first ?? "")
        let base = source.isEmpty ? fallback : source
        guard let ch = base.first(where: { !$0.isWhitespace }) else { return "?" }
        return String(ch).uppercased()
    }

    var body: some View {
        ZStack {
            Circle().fill(MailUI.tint(address: address, display: display))
            Text(initial)
                .font(.system(size: size * 0.42, weight: .semibold))
                .foregroundColor(.white)
        }
        .frame(width: size, height: size)
    }
}

// ── time ─────────────────────────────────────────────────────────────────────

enum MailTime {
    /**
     * The row's timestamp, formatted the way the reference does it: a clock time
     * for today, the word "Yesterday" for yesterday, a short date before that.
     *
     * Returns nil when there is no trustworthy instant. A row that cannot know
     * when the message arrived shows no time rather than a plausible one — the
     * list is ordered by time, so an invented timestamp would also invent the
     * order.
     */
    static func short(_ iso: String?, now: Date = Date()) -> String? {
        guard let date = parse(iso) else { return nil }
        let cal = Calendar.current
        if cal.isDate(date, inSameDayAs: now) {
            return fmt("h:mm a", date)
        }
        if let yesterday = cal.date(byAdding: .day, value: -1, to: now),
           cal.isDate(date, inSameDayAs: yesterday) {
            return "Yesterday"
        }
        if cal.component(.year, from: date) == cal.component(.year, from: now) {
            return fmt("MMM d", date)
        }
        return fmt("MMM d, yyyy", date)
    }

    /// The full date a message body needs. Falls back to the raw string rather
    /// than a fabricated date: showing "Jan 1, 1970" for an unparseable instant
    /// would present a wrong date as a fact about someone's mail.
    static func full(_ iso: String?) -> String? {
        guard let raw = iso, !raw.isEmpty else { return nil }
        guard let date = parse(raw) else { return raw }
        return fmt("EEEE, MMMM d, yyyy 'at' h:mm a", date)
    }

    private static func parse(_ iso: String?) -> Date? {
        guard let iso, !iso.isEmpty else { return nil }
        for pattern in [
            "yyyy-MM-dd'T'HH:mm:ss.SSSZZZZZ", "yyyy-MM-dd'T'HH:mm:ssZZZZZ",
            "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ssZ",
            "yyyy-MM-dd HH:mm:ss",
        ] {
            let f = DateFormatter()
            f.locale = Locale(identifier: "en_US")
            f.dateFormat = pattern
            f.isLenient = true
            if let d = f.date(from: iso) { return d }
        }
        let iso8601 = ISO8601DateFormatter()
        iso8601.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return iso8601.date(from: iso) ?? ISO8601DateFormatter().date(from: iso)
    }

    private static func fmt(_ pattern: String, _ date: Date) -> String {
        let f = DateFormatter()
        f.locale = Locale(identifier: "en_US")
        f.dateFormat = pattern
        return f.string(from: date)
    }
}

// ── the row ──────────────────────────────────────────────────────────────────

/// One message in the list.
///
/// The unread dot, the star and the attachment glyph all come from `publicRow`;
/// none is inferred. `isE2eEncrypted` is shown too, because a lock is the only
/// honest signal that the body will not open here.
struct MessageRow: View {
    let message: MailMessage
    let selecting: Bool
    let selected: Bool
    let onSelect: () -> Void
    let onOpen: () -> Void
    let onStar: () -> Void

    private var unread: Bool { !message.isRead }

    var body: some View {
        HStack(alignment: .top, spacing: 10) {
            if selecting {
                Image(systemName: selected ? "checkmark.square.fill" : "square")
                    .foregroundColor(selected ? GM.primarySolid : GM.textTertiary)
                    .font(.system(size: 20))
                    .padding(.top, 10)
            } else {
                Circle()
                    .fill(unread ? GM.primarySolid : Color.clear)
                    .frame(width: 8, height: 8)
                    .padding(.top, 17)
            }

            SenderAvatar(display: message.from?.display ?? "",
                         address: message.from?.address ?? "")

            VStack(alignment: .leading, spacing: 2) {
                HStack(alignment: .firstTextBaseline) {
                    // `display` is non-optional on MailPerson — the type already
                    // folds name-over-address — so the question is whether it
                    // said anything at all, not whether the field exists.
                    Text(message.from.flatMap { $0.display.nilIfBlank() } ?? "Unknown sender")
                        .font(.system(size: 15, weight: unread ? .semibold : .regular))
                        .foregroundColor(GM.textPrimary)
                        .lineLimit(1)
                    Spacer(minLength: 8)
                    if let time = MailTime.short(message.createdAt) {
                        Text(time).font(.system(size: 12)).foregroundColor(GM.textTertiary)
                            .lineLimit(1)
                    }
                }
                Text(message.subjectOrNone)
                    .font(.system(size: 14, weight: unread ? .medium : .regular))
                    .foregroundColor(GM.textPrimary).lineLimit(1)
                HStack(spacing: 4) {
                    if let snippet = message.snippet, !snippet.isEmpty {
                        Text(snippet).font(.system(size: 13)).foregroundColor(GM.textSecondary)
                            .lineLimit(1)
                    }
                    if message.isE2eEncrypted {
                        Text("· encrypted").font(.system(size: 11)).foregroundColor(GM.successInk)
                    }
                }
            }

            VStack(spacing: 6) {
                if message.hasAttachments {
                    Image(systemName: "paperclip").font(.system(size: 13))
                        .foregroundColor(GM.textTertiary)
                }
                Image(systemName: message.isStarred ? "star.fill" : "star")
                    .font(.system(size: 16))
                    // The star token, not `GM.warning`: this is the row the dark
                    // home screenshot photographs, and `warning` carries a single
                    // value with no dark counterpart, so the list and the thread of
                    // the same starred message showed two different ambers.
                    .foregroundColor(message.isStarred ? GM.star : GM.textTertiary)
                    .onTapGesture(perform: onStar)
            }
            .frame(width: 24)
        }
        .padding(.horizontal, 16).padding(.vertical, 12)
        .contentShape(Rectangle())
        .onTapGesture { selecting ? onSelect() : onOpen() }
        .onLongPressGesture(perform: onSelect)
        .accessibilityIdentifier(selecting ? "mail-row-select" : "mail-row")
    }
}

// ── header pieces ────────────────────────────────────────────────────────────

/// The greeting. Time of day only, and the name comes from `/me`: with no name
/// from the server the greeting has no name, rather than a plausible one.
struct MailGreeting: View {
    let name: String?
    let address: String?

    private var part: String {
        switch Calendar.current.component(.hour, from: Date()) {
        case 0..<12: return "Good morning"
        case 12..<18: return "Good afternoon"
        default: return "Good evening"
        }
    }

    private var who: String? {
        if let n = name?.trimmingCharacters(in: .whitespacesAndNewlines), !n.isEmpty { return n }
        let handle = address?.split(separator: "@").first.map(String.init) ?? ""
        return handle.isEmpty ? nil : handle
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            HStack(spacing: 6) {
                Text(who.map { "\(part), \($0)" } ?? part)
                    .font(.system(size: 22, weight: .bold)).foregroundColor(GM.textPrimary)
                Text(Calendar.current.component(.hour, from: Date()) < 18 ? "☀️" : "🌙")
                    .font(.system(size: 18))
            }
            Text("Here's what's happening in your inbox today.")
                .font(.system(size: 13)).foregroundColor(GM.textSecondary)
        }
    }
}

/// A pill in the label strip. Selected pills take the label's own colour, which
/// is why the server stores it rather than the client picking one.
///
/// Not a control, and deliberately so — the same rule Android applies in
/// `Mail.kt`. A label cannot filter this list: the rows the listing returns
/// carry no label field (`MailMessage`), and the route takes no label query
/// beyond `box`, `q`, `page`, `pageSize`. Drawing a chip that asks to be tapped
/// and then doing nothing is the failure this sector was told not to ship, so
/// the chip reports what is real — the name and the unread count — and takes no
/// tap.
struct LabelChip: View {
    let label: String
    let count: Int?
    let color: Color
    let selected: Bool

    var body: some View {
        HStack(spacing: 7) {
            Circle().fill(color).frame(width: 8, height: 8)
            Text(label).font(.system(size: 13, weight: .medium))
                .foregroundColor(selected ? color : GM.textPrimary)
                .lineLimit(1)
            if let count {
                Text("\(count)").font(.system(size: 12)).foregroundColor(GM.textSecondary)
            }
        }
        .padding(.horizontal, 12).padding(.vertical, 7)
        .background(Capsule().fill(selected ? color.opacity(0.16) : GM.softFill))
        .overlay(Capsule().strokeBorder(selected ? color : Color.clear, lineWidth: 1))
    }
}

/// A box tab with the underline the reference shows on the active one.
struct BoxTab: View {
    let title: String
    let count: Int?
    let active: Bool
    let onClick: () -> Void

    var body: some View {
        Button(action: onClick) {
            VStack(spacing: 6) {
                HStack(spacing: 6) {
                    Text(title)
                        .font(.system(size: 14, weight: active ? .semibold : .regular))
                        .foregroundColor(active ? GM.primarySolid : GM.textSecondary)
                    CountBadge(count: count)
                }
                Rectangle()
                    .fill(active ? GM.primarySolid : Color.clear)
                    .frame(height: 2)
            }
            .padding(.horizontal, 14).padding(.vertical, 10)
        }
        .buttonStyle(.plain)
    }
}

/// The floating compose button. Its identifier is what the CI walk taps — a
/// floating button found by coordinates is a walk that breaks whenever the list
/// scrolls.
struct MailFab: View {
    let onClick: () -> Void

    var body: some View {
        Button(action: onClick) {
            Image(systemName: "pencil")
                .font(.system(size: 19, weight: .semibold))
                .foregroundColor(.white)
                .frame(width: 56, height: 56)
                .background(Circle().fill(GM.primarySolid))
                .shadow(color: Color.black.opacity(0.18), radius: 10, x: 0, y: 4)
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("mail-compose")
    }
}

// ── selection toolbar ────────────────────────────────────────────────────────

/// One action in the selection toolbar: glyph above its own word, as the
/// reference draws it.
struct ToolAction: View {
    let icon: String
    let label: String
    let onClick: () -> Void

    var body: some View {
        Button(action: onClick) {
            VStack(spacing: 4) {
                Image(systemName: icon).font(.system(size: 18)).foregroundColor(GM.textPrimary)
                Text(label).font(.system(size: 11)).foregroundColor(GM.textPrimary)
            }
            .frame(maxWidth: .infinity)
            .padding(.vertical, 8)
        }
        .buttonStyle(.plain)
    }
}

/// The bar that replaces the header while messages are picked.
struct SelectionToolbar: View {
    let count: Int
    let onClose: () -> Void
    let onArchive: () -> Void
    let onDelete: () -> Void
    let onMove: () -> Void
    let onLabel: () -> Void
    let onMore: () -> Void
    /// The "Sender" action, offered only while the screener queue is open — the
    /// same rule Android applies in `Mail.kt`. Every other box's mail has already
    /// been delivered, so deciding its sender would be a rule about a message that
    /// needs none, and a toolbar item that opens a sheet of inapplicable actions is
    /// a control aimed at nothing.
    var onSender: (() -> Void)? = nil

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                Button(action: onClose) {
                    Image(systemName: "xmark").font(.system(size: 16, weight: .semibold))
                        .foregroundColor(GM.textPrimary)
                }.buttonStyle(.plain)
                Text("\(count) selected")
                    .font(.system(size: 16, weight: .semibold)).foregroundColor(GM.textPrimary)
                Spacer()
            }
            .padding(.horizontal, 16).padding(.vertical, 10)

            HStack(spacing: 0) {
                ToolAction(icon: "archivebox", label: "Archive", onClick: onArchive)
                ToolAction(icon: "trash", label: "Delete", onClick: onDelete)
                ToolAction(icon: "arrow.right.doc.on.clipboard", label: "Move", onClick: onMove)
                ToolAction(icon: "tag", label: "Label", onClick: onLabel)
                if let onSender {
                    ToolAction(icon: "person.badge.plus", label: "Sender", onClick: onSender)
                }
                ToolAction(icon: "ellipsis", label: "More", onClick: onMore)
            }
        }
        .background(GM.card)
        .accessibilityIdentifier("mail-selection-bar")
    }
}

// ── the honest states ────────────────────────────────────────────────────────

/**
 * The four non-list states, kept apart because they are four different facts.
 *
 * Collapsing them is how a mailbox lies: a failed load drawn as an empty inbox
 * tells the person they have no mail, and a missing mailbox drawn as a failed
 * load tells them their mail is unreachable when it was never there. The hub in
 * this app shipped the same family of bug — a refresh failure that blanked good
 * data and disabled its own control — so the distinction is structural here
 * rather than left to each caller.
 */
struct MailStateBlock<Content: View>: View {
    let loading: Bool
    let failed: Bool
    let noMailbox: Bool
    let empty: Bool
    let boxTitle: String
    let onRetry: () -> Void
    @ViewBuilder let content: Content

    var body: some View {
        Group {
            if !failed && !noMailbox && !empty {
                content
            } else if loading {
                CenteredState(title: "Checking your mailbox…")
            } else if noMailbox {
                CenteredState(
                    title: "No GrapMail mailbox on this account",
                    detail: "The account you signed in with has no mailbox provisioned, so there is " +
                            "nothing to list. This is not a connection problem.")
            } else if failed {
                CenteredState(
                    title: "Couldn't load \(boxTitle)",
                    detail: "Your mailbox could not be reached. Nothing has been deleted.",
                    action: ("Try again", onRetry))
            } else {
                CenteredState(title: "\(boxTitle) is empty",
                              detail: "No messages here yet.")
            }
        }
    }
}

/// `detail`, never `body`: a stored property called `body` in a `View` shadows
/// the protocol's own requirement, and the compiler reports the conformance
/// against the String instead of the view. This bit the security sector once.
struct CenteredState: View {
    let title: String
    var detail: String? = nil
    var action: (String, () -> Void)? = nil

    var body: some View {
        VStack(spacing: 8) {
            Text(title).font(.system(size: 17, weight: .semibold))
                .foregroundColor(GM.textPrimary).multilineTextAlignment(.center)
            if let detail {
                Text(detail).font(.system(size: 14)).foregroundColor(GM.textSecondary)
                    .multilineTextAlignment(.center)
            }
            if let action {
                GradientButton(title: action.0, action: action.1)
                    .padding(.top, 8)
            }
        }
        .padding(32)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

// ── label colours ────────────────────────────────────────────────────────────

/// The eight colour keys the label route accepts (`LABEL_COLOURS`,
/// `grapmail-labels.ts:39`), in the order the picker shows them. The value stored
/// on a label is one of these WORDS — never a colour value — exactly as the web
/// client treats it (`src/app/(mail)/mail/page.tsx:84`).
let labelColourKeys = ["slate", "red", "amber", "green", "teal", "blue", "violet", "pink"]

/// A label's swatch from its palette key, light and dark.
///
/// This replaces `String.hexColor`, which parsed the field as a hex value. That
/// could never succeed: the route's `normaliseColour` rejects anything outside the
/// eight words and stores `slate`, so the parse returned nil for every label and
/// each dot fell back to a neutral grey. The create screen then sent a hex of its
/// own and the route answered 201 while storing `slate`, so the colour a user
/// picked was never the colour the label received.
///
/// The values are the web theme's own `--gm-label-*` pair for each key
/// (`src/styles/grapmail.css`), resolved through `GM.color(light:dark:)`, so the
/// swatch follows the trait the rest of the app does and all three clients show one
/// colour for one key. An unknown key becomes slate — the server's own fallback —
/// rather than a colour nobody chose.
func labelSwatch(_ key: String?) -> Color {
    switch key {
    case "red":    return GM.color(light: 0xD93025, dark: 0xF2857D)
    case "amber":  return GM.color(light: 0xF29900, dark: 0xFDD663)
    case "green":  return GM.color(light: 0x188038, dark: 0x6DD58C)
    case "teal":   return GM.color(light: 0x0D9488, dark: 0x5EEAD4)
    case "blue":   return GM.color(light: 0x1A73E8, dark: 0x8AB4F8)
    case "violet": return GM.color(light: 0x7C3AED, dark: 0xC4B5FD)
    case "pink":   return GM.color(light: 0xC01A7A, dark: 0xF7A8D8)
    default:       return GM.color(light: 0x5F6368, dark: 0x9AA0A6)   // slate
    }
}

/// The one unsubscribe address worth offering, or nil.
///
/// `List-Unsubscribe` is the sender's own claim about a URL and arrives in the
/// message's header text (`publicRow.unsubscribe`, `grapmail.ts:594`), so it is
/// untrusted input, and it commonly arrives as a comma-separated pair:
/// `<https://x.example/u?id=7>, <mailto:u@x.example>`.
///
/// Angle brackets are read FIRST, because the header's separator is a comma and a
/// comma is legal inside a query (`?t=abc,def`, RFC 3986). Inside `<…>` the whole run
/// is one address and cannot be cut short; splitting first would return the truncated
/// half and open a different request than the sender wrote. The plain split stays as
/// the fallback for headers that omit the brackets, which the format also permits.
/// Android's `parseUnsubscribe` applies the same rules.
func parseUnsubscribe(_ raw: String?) -> String? {
    guard let raw, !raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return nil }
    // `NSRegularExpression` under `try?`, the same shape the HTML stripper in
    // `MailReaderViews.swift` uses. There is no `String.matches(in:)` in the
    // standard library — writing one here is a compile error, not a shortcut.
    if let re = try? NSRegularExpression(pattern: "<([^>]+)>") {
        let ns = raw as NSString
        for match in re.matches(in: raw, range: NSRange(location: 0, length: ns.length)) {
            guard match.numberOfRanges > 1 else { continue }
            let candidate = ns.substring(with: match.range(at: 1))
                .trimmingCharacters(in: .whitespacesAndNewlines)
            let lowered = candidate.lowercased()
            if lowered.hasPrefix("https://") || lowered.hasPrefix("http://") { return candidate }
        }
    }
    for part in raw.split(separator: ",") {
        let candidate = part.trimmingCharacters(in: .whitespacesAndNewlines)
            .trimmingCharacters(in: CharacterSet(charactersIn: "<>"))
            .trimmingCharacters(in: .whitespacesAndNewlines)
        let lowered = candidate.lowercased()
        if lowered.hasPrefix("https://") || lowered.hasPrefix("http://") { return candidate }
    }
    return nil
}

/// The host a link will take the user to, for the confirmation line. Written on the
/// string rather than on `URL` because the confirmation must be able to name the
/// destination even for an address this device cannot open.
///
/// Any `user:pass@` prefix is dropped, because it is not part of where the request
/// goes: a sender writes `List-Unsubscribe: <https://grapsee.com@evil.example/u>`, the
/// browser goes to `evil.example`, and printing the raw authority would put our own
/// domain at the head of the one sentence whose job is to name the stranger the tap is
/// about to contact. The last `@` is the boundary — userinfo may not carry an
/// unencoded `@`. An address that is nothing but an `@` returns nil, which falls back
/// to the sheet's generic "the sender's server" wording instead of inventing a host.
func urlHost(_ raw: String) -> String? {
    guard let range = raw.range(of: "://") else { return nil }
    var host = Substring(raw[range.upperBound...])
    // `: Character`, because `firstIndex(of:)` on a Substring wants the
    // element type and an unannotated literal array here infers `[String]` --
    // "cannot convert value of type 'String' to expected argument type
    // 'Substring.Element' (aka 'Character')", named by the compiler at this line.
    for stop: Character in ["/", "?", "#"] {
        if let cut = host.firstIndex(of: stop) { host = host[host.startIndex..<cut] }
    }
    if let at = host.lastIndex(of: "@") { host = host[host.index(after: at)...] }
    return host.isEmpty ? nil : String(host)
}

/// Human-readable size, so a 1.4 MB report does not read as "1415732".
func humanBytes(_ n: Int) -> String {
    if n < 1024 { return "\(n) B" }
    if n < 1024 * 1024 { return String(format: "%.0f KB", Double(n) / 1024.0) }
    return String(format: "%.1f MB", Double(n) / (1024.0 * 1024.0))
}
