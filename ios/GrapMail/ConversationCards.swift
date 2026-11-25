import SwiftUI
import UIKit

// The conversation view's pieces (round8-spec §5.1–5.5). ThreadView
// (MailReaderViews.swift) composes them; ThreadMessageCard draws one message.
//
//  - `ReaderLayout` / `.readerText` — the 680 pt reading column and the
//    reader's type roles (body 16/25, quoted 14/21, snippet 14/20, …), all
//    through ScaledFont.swift so they follow Dynamic Type.
//  - `ReaderConversation` — who is "me", the one-line snippet, the reply
//    target's first name, the "to me / to N people" line, and the rows the
//    list draws (a long leading run of folded cards becomes one
//    "N earlier messages" bar).
//  - `ReaderQuotes` — plain-text quoted-history detection. The HTML side of
//    the same rules runs inside the document (MailHtmlBody.swift).
//  - `ReaderScrollState` + the subject header / compact title — the
//    scroll-linked shrinking subject, iOS 16 compatible (a GeometryReader
//    preference, no `scrollPosition`).
//  - Collapsed row, earlier-messages bar, quote pill, body skeleton, footer
//    actions.
//
// Colours are GM.* tokens only; motion goes through GMMotion and is gated on
// Reduce Motion; new English strings live in the "Reader" table
// (en.lproj/Reader.strings, Reader.stringsdict).

// MARK: - Layout and type

enum ReaderLayout {
    /// The reading column: `GMLayout.readable` (720) minus 2 × 20 padding.
    static let column: CGFloat = GMLayout.readable - 40
    /// Gap between message cards.
    static let cardGap: CGFloat = 10
    /// A folded message card.
    static let collapsedHeight: CGFloat = 64
    /// The subject header shrinks over this much scroll (§5.4).
    static let shrinkDistance: CGFloat = 64
    /// The toolbar title starts fading in here…
    static let titleFadeStart: CGFloat = 40
    /// …and under Reduce Motion the header simply switches here.
    static let reducedMotionSwitch: CGFloat = 48
    /// The named coordinate space of the reader's ScrollView.
    static let scrollSpace = "gm-reader-scroll"
    /// Placeholder height for a body with nothing measured yet (§5.5).
    static let placeholderBodyHeight: CGFloat = 120
}

extension View {
    /// The reader's column: a 680 pt column centred on wide windows (cards
    /// fill it, no inner margin), a 12 pt margin on each side on a phone.
    func readerColumn() -> some View {
        frame(maxWidth: ReaderLayout.column)
            .padding(.horizontal, 12)
            .frame(maxWidth: .infinity)
    }

    /// A reader type role: `size` on a `lineHeight` line at the default text
    /// size, both scaled for the person's text size (ScaledFont.swift).
    func readerText(_ size: CGFloat, lineHeight: CGFloat, weight: Font.Weight = .regular) -> some View {
        modifier(ReaderTextRole(size: size, lineHeight: lineHeight, weight: weight))
    }
}

private struct ReaderTextRole: ViewModifier {
    @Environment(\.dynamicTypeSize) private var dts
    let size: CGFloat
    let lineHeight: CGFloat
    let weight: Font.Weight

    func body(content: Content) -> some View {
        let scaled = GMType.scaledSize(size, for: dts)
        // SwiftUI's lineSpacing is the gap added to the font's own line
        // height, so the role's line height is reached by adding the rest.
        let natural = UIFont.systemFont(ofSize: scaled).lineHeight
        let spacing = max(0, scaled * lineHeight / size - natural)
        content
            .font(.gm(size, weight, scaledFor: dts))
            .lineSpacing(spacing)
    }
}

// MARK: - Session memory (heights, quote folds)

/// Per-process reader memory. Plain statics, touched from the main thread
/// only (views and the web view's delegate callbacks).
enum ReaderSession {
    /// Messages whose quoted history the person opened, for this session only
    /// (§5.2 "expansion is remembered per message for the session only").
    static var openQuotes: Set<String> = []

    private static var heights: [String: CGFloat] = [:]
    private static var heightOrder: [String] = []

    /// The last measured height of a body, so a card that is folded and
    /// opened again (or scrolls back into the lazy list) starts at the size
    /// it had instead of at the placeholder.
    static func bodyHeight(_ key: String) -> CGFloat? { heights[key] }

    static func storeBodyHeight(_ height: CGFloat, for key: String) {
        guard height > 0 else { return }
        if heights[key] == nil {
            heightOrder.append(key)
            if heightOrder.count > 128 {
                let drop = heightOrder.removeFirst()
                heights[drop] = nil
            }
        }
        heights[key] = height
    }
}

// MARK: - Conversation rules

/// One entry of the conversation list below the subject header.
enum ConversationRow: Identifiable {
    case message(MailMessageFull, expanded: Bool, revealIndex: Int?)
    case earlier(hidden: Int, firstHiddenId: String)

    var id: String {
        switch self {
        case let .message(m, _, _): return m.id
        case let .earlier(_, first): return "earlier-\(first)"
        }
    }
}

enum ReaderConversation {
    /// The bare address out of "Name <addr>" or "addr", lowercased.
    static func bareAddress(_ raw: String?) -> String? {
        guard let s = raw?.trimmingCharacters(in: .whitespacesAndNewlines), !s.isEmpty else { return nil }
        var inner = s
        if let open = s.lastIndex(of: "<") {
            let after = s[s.index(after: open)...]
            if let close = after.firstIndex(of: ">") {
                inner = String(after[..<close])
            }
        }
        let bare = inner.trimmingCharacters(in: CharacterSet(charactersIn: "\" ")).lowercased()
        return bare.isEmpty ? nil : bare
    }

    /// The display part of "Name <addr>", else the address itself.
    static func displayOfRecipient(_ raw: String) -> String {
        let s = raw.trimmingCharacters(in: .whitespacesAndNewlines)
        if let open = s.firstIndex(of: "<") {
            let name = s[..<open].trimmingCharacters(in: CharacterSet(charactersIn: "\" ").union(.whitespaces))
            if !name.isEmpty { return name }
        }
        return bareAddress(s) ?? s
    }

    /// The account's own message: outbound, or sent from the signed-in
    /// address or one of its aliases (`me` holds them lowercased).
    static func isOwn(_ message: MailMessageFull, me: Set<String>) -> Bool {
        let direction = message.direction?.lowercased()
        if direction == "outbound" || direction == "out" || direction == "sent" { return true }
        guard let from = bareAddress(message.from?.address) else { return false }
        return me.contains(from)
    }

    /// A subject that announces a forward ("Fwd:", "FW:", "WG:", …).
    static func isForwardSubject(_ subject: String?) -> Bool {
        let s = (subject ?? "").trimmingCharacters(in: .whitespaces)
        return ReaderQuotes.matches(ReaderQuotes.forwardSubject, s)
    }

    /// The folded card's one line: the reply's own words, not the quote under it.
    static func snippet(_ message: MailMessageFull) -> String {
        if message.isE2eEncrypted || message.e2eEnvelope != nil {
            return String(localized: "Encrypted message")
        }
        var source: String? = nil
        if let text = message.bodyText?.nilIfBlank() {
            source = ReaderQuotes.splitPlain(text, forwarded: isForwardSubject(message.subject)).visible
        }
        if source?.nilIfBlank() == nil { source = message.snippet }
        let flat = (source ?? "")
            .components(separatedBy: .whitespacesAndNewlines)
            .filter { !$0.isEmpty }
            .joined(separator: " ")
        return flat.isEmpty ? String(localized: "Show message") : String(flat.prefix(240))
    }

    /// "Reply to {first name}": the sender, or for one's own message its
    /// first recipient.
    static func replyTargetFirstName(_ message: MailMessageFull, own: Bool) -> String? {
        let full: String?
        if own {
            full = message.to.first.map(displayOfRecipient)?.nilIfBlank()
        } else {
            full = message.from?.name?.nilIfBlank() ?? message.from?.address?.nilIfBlank()
        }
        guard let full else { return nil }
        if full.contains("@") && !full.contains(" ") {
            // A bare address: its local part reads better than the whole thing.
            return String(full.split(separator: "@").first ?? Substring(full))
        }
        return full.split(whereSeparator: { $0 == " " || $0 == "," }).first.map(String.init) ?? full
    }

    /// "to me" / "to Nusrat" / "to 3 people"; nil when the message names nobody.
    static func recipientsLine(_ message: MailMessageFull, me: Set<String>) -> String? {
        let all = message.to + message.cc
        guard !all.isEmpty else { return nil }
        if all.count == 1 {
            if let bare = bareAddress(all[0]), me.contains(bare) {
                return String(localized: "to me", table: "Reader")
            }
            let name = displayOfRecipient(all[0])
            return String(localized: "to \(name)", table: "Reader")
        }
        return String(localized: "to \(all.count) people", table: "Reader")
    }

    /// Distinct people across the conversation: senders and recipients.
    static func participantCount(_ messages: [MailMessageFull]) -> Int {
        var people = Set<String>()
        for m in messages {
            if let a = bareAddress(m.from?.address) { people.insert(a) }
            for r in m.to + m.cc { if let a = bareAddress(r) { people.insert(a) } }
        }
        return people.count
    }

    /// The rows below the header (Android `conversationRows`): a run of more
    /// than three folded cards keeps its first and last card and hides the
    /// middle behind one "N earlier messages" bar, so the first message of
    /// the conversation always stays visible. `revealIndex` numbers the cards
    /// that were hidden, for the staggered fade once the bar is tapped.
    static func rows(_ messages: [MailMessageFull],
                     isExpanded: (MailMessageFull) -> Bool,
                     earlierRevealed: Bool) -> [ConversationRow] {
        guard !messages.isEmpty else { return [] }
        var rows: [ConversationRow] = []
        rows.reserveCapacity(messages.count + 1)
        var i = 0
        while i < messages.count {
            if isExpanded(messages[i]) {
                rows.append(.message(messages[i], expanded: true, revealIndex: nil))
                i += 1
                continue
            }
            var j = i
            while j < messages.count && !isExpanded(messages[j]) { j += 1 }
            let run = Array(messages[i..<j])
            if run.count > 3, let first = run.first, let last = run.last {
                let middle = Array(run.dropFirst().dropLast())
                rows.append(.message(first, expanded: false, revealIndex: nil))
                if earlierRevealed {
                    for (k, message) in middle.enumerated() {
                        rows.append(.message(message, expanded: false, revealIndex: k))
                    }
                } else if let hiddenFirst = middle.first {
                    rows.append(.earlier(hidden: middle.count, firstHiddenId: hiddenFirst.id))
                }
                rows.append(.message(last, expanded: false, revealIndex: nil))
            } else {
                for message in run {
                    rows.append(.message(message, expanded: false, revealIndex: nil))
                }
            }
            i = j
        }
        return rows
    }
}

// MARK: - Plain-text quoted history

/// Quoted-history detection for plain-text bodies (§5.2). Conservative on
/// purpose: a fold that hides something the sender wrote is worse than a long
/// message. Only TRAILING history folds; a forward, a message whose own text
/// would be empty, and a quote under three lines never fold.
enum ReaderQuotes {
    struct PlainSplit {
        let visible: String
        let quoted: String?
    }

    private static func rx(_ pattern: String) -> NSRegularExpression? {
        try? NSRegularExpression(pattern: pattern, options: [.caseInsensitive])
    }

    static func matches(_ regex: NSRegularExpression?, _ s: String) -> Bool {
        guard let regex else { return false }
        return regex.firstMatch(in: s, range: NSRange(s.startIndex..., in: s)) != nil
    }

    // "On <date>, <name> wrote:" and its common translations, anchored to the
    // whole (possibly joined) line.
    private static let attribution: [NSRegularExpression] = [
        #"^\s*On\s.{4,320}\swrote\s*:\s*$"#,
        #"^\s*Am\s.{4,320}\sschrieb\s.{0,160}:\s*$"#,
        #"^\s*Le\s.{4,320}\sa\s+écrit\s*:\s*$"#,
        #"^\s*El\s.{4,320}\sescribió\s*:\s*$"#,
        #"^\s*Em\s.{4,320}\sescreveu\s*:\s*$"#,
        #"^\s*Il\s.{4,320}\sha\s+scritto\s*:\s*$"#,
        #"^\s*Op\s.{4,320}\sschreef\s.{0,160}:\s*$"#,
    ].compactMap { rx($0) }

    private static let originalMessage = rx(
        #"^\s*-{2,}\s*(Original Message|Ursprüngliche Nachricht|Message d'origine|Mensaje original|Mensagem original|Messaggio originale)\s*-{2,}\s*$"#)
    private static let underline = rx(#"^\s*_{10,}\s*$"#)
    private static let headerFrom = rx(#"^\s*\*?(From|De|Von|Da|Van)\s*:\*?\s+\S.*$"#)
    private static let headerSent = rx(#"^\s*\*?(Sent|Date|Gesendet|Datum|Envoyé|Enviado|Inviato|Verzonden)\s*:\*?\s+\S.*$"#)
    private static let forwardMarker = rx(
        #"(-{2,}\s*Forwarded message\s*-{2,}|Begin forwarded message|Weitergeleitete Nachricht|Message transféré|Mensaje reenviado|Mensagem encaminhada|Messaggio inoltrato)"#)
    static let forwardSubject = rx(#"^(fwd?|wg|tr|rv|enc|i)\s*:"#)

    private static func isBlank(_ line: String) -> Bool {
        line.trimmingCharacters(in: .whitespaces).isEmpty
    }

    private static func isQuoteLine(_ line: String) -> Bool {
        line.drop(while: { $0 == " " || $0 == "\t" }).first == ">"
    }

    private static func isAttribution(_ line: String) -> Bool {
        line.count <= 480 && attribution.contains { matches($0, line) }
    }

    /// Every non-blank line from `from` to the end is a `>` line (and there is one).
    private static func restIsQuoted(_ lines: [String], from: Int) -> Bool {
        guard from < lines.count else { return false }
        var any = false
        for line in lines[from...] where !isBlank(line) {
            if !isQuoteLine(line) { return false }
            any = true
        }
        return any
    }

    private static var cache: [String: PlainSplit] = [:]

    /// Split a plain-text body at the start of its trailing quoted history.
    /// `quoted` is nil when nothing should fold. Memoised per text.
    static func splitPlain(_ text: String, forwarded: Bool) -> PlainSplit {
        let key = (forwarded ? "f|" : "r|") + text
        if let hit = cache[key] { return hit }
        let result = computeSplit(text, forwarded: forwarded)
        if cache.count > 64 { cache.removeAll() }
        cache[key] = result
        return result
    }

    private static func computeSplit(_ text: String, forwarded: Bool) -> PlainSplit {
        let normalised = text.replacingOccurrences(of: "\r\n", with: "\n").replacingOccurrences(of: "\r", with: "\n")
        let whole = PlainSplit(visible: normalised, quoted: nil)
        if forwarded || matches(forwardMarker, normalised) { return whole }
        let lines = normalised.components(separatedBy: "\n")
        var cut: Int? = nil
        for i in lines.indices {
            let line = lines[i]
            if isBlank(line) { continue }
            // Attribution on one line, or wrapped onto a second one (plain
            // parts wrap long attributions at ~78 characters). It folds only
            // when everything after it is `>` quoted.
            var attributionEnd: Int? = nil
            if isAttribution(line) {
                attributionEnd = i
            } else if i + 1 < lines.count,
                      lines[i + 1].trimmingCharacters(in: .whitespaces).hasSuffix(":"),
                      isAttribution(line.trimmingCharacters(in: .whitespaces) + " "
                                    + lines[i + 1].trimmingCharacters(in: .whitespaces)) {
                attributionEnd = i + 1
            }
            if let end = attributionEnd {
                if restIsQuoted(lines, from: end + 1) { cut = i; break }
                continue
            }
            if matches(originalMessage, line) { cut = i; break }
            if matches(headerFrom, line),
               (i + 1 ..< min(lines.count, i + 5)).contains(where: { matches(headerSent, lines[$0]) }) {
                cut = (i > 0 && matches(underline, lines[i - 1])) ? i - 1 : i
                break
            }
            if isQuoteLine(line) && restIsQuoted(lines, from: i) { cut = i; break }
        }
        guard var start = cut, start > 0 else { return whole }
        // A trailing signature ("-- " on its own line) right above the
        // history folds into the same pill.
        if let signature = lines[0..<start].lastIndex(where: { $0 == "-- " }), signature > 0 {
            start = signature
        }
        let visible = lines[0..<start].joined(separator: "\n")
            .replacingOccurrences(of: #"\s+$"#, with: "", options: .regularExpression)
        let quotedLines = Array(lines[start...])
        let quoted = quotedLines.joined(separator: "\n").trimmingCharacters(in: .newlines)
        guard visible.nilIfBlank() != nil, quoted.nilIfBlank() != nil else { return whole }
        guard quotedLines.filter({ !isBlank($0) }).count >= 3 else { return whole }
        return PlainSplit(visible: visible, quoted: quoted)
    }
}

// MARK: - Scroll-linked subject header (§5.4)

/// The reader's scroll position, held OUTSIDE ThreadView's observation (it is
/// kept in `@State` as a plain reference) so a scroll frame redraws only the
/// two header pieces that observe it, never the conversation list.
final class ReaderScrollState: ObservableObject {
    /// Scroll distance from the top, clamped to what the header uses.
    @Published private(set) var offset: CGFloat = 0
    /// Every realised card's frame in the scroll view's coordinate space
    /// (for keeping the reading position when a card above it grows).
    var cardFrames: [String: CGRect] = [:]
    var viewportHeight: CGFloat = 0

    /// The key the conversation list itself reports its frame under, in the
    /// same preference as the cards, so one report holds both the scroll
    /// position and every card's position.
    static let contentKey = "gm-reader-content"

    /// Where the reading position is pinned: the card under the top edge of
    /// the viewport, its top in the viewport, and the list's top at the time.
    private var anchorId: String?
    private var anchorMinY: CGFloat = 0
    private var anchorContentTop: CGFloat = 0

    /// A scroll that puts the anchored card back where it was.
    struct Correction {
        let id: String
        let anchor: UnitPoint
    }

    func update(rawOffset: CGFloat) {
        let value = min(max(rawOffset, 0), 96).rounded()
        if value != offset { offset = value }
    }

    /// §5.5.2: content above the viewport that grows or shrinks (a body
    /// measuring itself late, earlier messages loading in) must not move what
    /// is being read. Called with every frames report. A plain scroll moves
    /// a card and the list's top by the same amount, so only a change in the
    /// card's distance from the list's top counts as drift; then the card is
    /// scrolled back to where it sat. Nil when nothing needs to move.
    func readingPositionCorrection(_ frames: [String: CGRect]) -> Correction? {
        cardFrames = frames
        guard let contentTop = frames[Self.contentKey]?.minY else {
            anchorId = nil
            return nil
        }
        if let id = anchorId, let frame = frames[id] {
            let drift = (frame.minY - contentTop) - (anchorMinY - anchorContentTop)
            // scrollTo lines up the same unit point of the card and of the
            // viewport: card.minY = u × (viewport − card height).
            let span = viewportHeight - frame.height
            if abs(drift) > 1, abs(span) > 1, viewportHeight > 0 {
                let correction = Correction(id: id, anchor: UnitPoint(x: 0.5, y: anchorMinY / span))
                // Re-picked from the next report, after the scroll landed.
                anchorId = nil
                return correction
            }
        }
        // The card straddling the viewport's top edge, if any (at the top of
        // the conversation nothing is above the viewport, so nothing to keep).
        let straddling = frames
            .filter { $0.key != Self.contentKey && $0.value.minY < 0 && $0.value.maxY > 0 }
            .min { $0.value.minY > $1.value.minY }
        if let top = straddling {
            anchorId = top.key
            anchorMinY = top.value.minY
            anchorContentTop = contentTop
        } else {
            anchorId = nil
        }
        return nil
    }

    /// 0 at rest, 1 once the subject has scrolled `shrinkDistance` away.
    func progress(reduced: Bool) -> CGFloat {
        if reduced { return offset > ReaderLayout.reducedMotionSwitch ? 1 : 0 }
        return min(max(offset / ReaderLayout.shrinkDistance, 0), 1)
    }

    /// The toolbar title's opacity: 0 until 40, 1 at 64.
    func titleOpacity(reduced: Bool) -> CGFloat {
        if reduced { return offset > ReaderLayout.reducedMotionSwitch ? 1 : 0 }
        let span = ReaderLayout.shrinkDistance - ReaderLayout.titleFadeStart
        return min(max((offset - ReaderLayout.titleFadeStart) / span, 0), 1)
    }

    /// Which of the two subjects VoiceOver hears.
    var collapsed: Bool { offset > ReaderLayout.reducedMotionSwitch }
}

struct ReaderScrollOffsetKey: PreferenceKey {
    static var defaultValue: CGFloat = 0
    static func reduce(value: inout CGFloat, nextValue: () -> CGFloat) { value = nextValue() }
}

struct ReaderCardFramesKey: PreferenceKey {
    static var defaultValue: [String: CGRect] = [:]
    static func reduce(value: inout [String: CGRect], nextValue: () -> [String: CGRect]) {
        value.merge(nextValue(), uniquingKeysWith: { $1 })
    }
}

/// A zero-height probe at the very top of the scroll content.
struct ReaderScrollOffsetProbe: View {
    var body: some View {
        GeometryReader { proxy in
            Color.clear.preference(key: ReaderScrollOffsetKey.self,
                                   value: -proxy.frame(in: .named(ReaderLayout.scrollSpace)).minY)
        }
        .frame(height: 0)
        .accessibilityHidden(true)
    }
}

extension View {
    /// Reports this card's frame in the reader's scroll space.
    func readerCardFrame(_ id: String) -> some View {
        background(GeometryReader { proxy in
            Color.clear.preference(key: ReaderCardFramesKey.self,
                                   value: [id: proxy.frame(in: .named(ReaderLayout.scrollSpace))])
        })
    }
}

/// The subject as a large title inside the scroll content: H2 22/28 bold,
/// up to three lines, with the chip row under it. Fades 1 → 0 and scales
/// 1 → 0.96 over the first 64 pt of scroll (interpolated by offset, not
/// time); under Reduce Motion it switches at 48 with a 150 ms crossfade.
struct ReaderSubjectHeader<Trailing: View>: View {
    @ObservedObject var scroll: ReaderScrollState
    let subject: String
    let chips: [String]
    @ViewBuilder var trailing: () -> Trailing
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var dts

    var body: some View {
        let f = scroll.progress(reduced: reduceMotion)
        HStack(alignment: .top, spacing: 8) {
            VStack(alignment: .leading, spacing: 10) {
                Text(subject)
                    .readerText(22, lineHeight: 28, weight: .bold)
                    .tracking(-0.3)
                    .foregroundColor(GM.textPrimary)
                    .lineLimit(dts.isAccessibilitySize ? nil : 3)
                    .fixedSize(horizontal: false, vertical: true)
                    .accessibilityAddTraits(.isHeader)
                    .accessibilityHidden(scroll.collapsed)
                    .accessibilityIdentifier("mail-subject")
                if !chips.isEmpty {
                    GMAdaptiveStack(spacing: 6) {
                        ForEach(Array(chips.enumerated()), id: \.offset) { index, chip in
                            Text(chip)
                                .gmFont(11, .medium)
                                .foregroundColor(index == 0 ? GM.primaryText : GM.textSecondary)
                                .lineLimit(1)
                                .padding(.horizontal, 9)
                                .padding(.vertical, 3)
                                .background(Capsule().fill(index == 0 ? GM.softFill : GM.bgTinted))
                                .overlay(Capsule().strokeBorder(index == 0 ? Color.clear : GM.border))
                        }
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            trailing()
        }
        .opacity(1 - f)
        .scaleEffect(1 - 0.04 * f, anchor: .bottomLeading)
        .animation(reduceMotion ? .easeInOut(duration: GMMotion.short) : nil, value: scroll.collapsed)
    }
}

/// The toolbar's compact title: 17 semibold, one line. Fades in between 40
/// and 64 pt of scroll; heard by VoiceOver only once the large subject has
/// gone (only one of the two is ever exposed).
struct ReaderCompactTitle: View {
    @ObservedObject var scroll: ReaderScrollState
    let subject: String
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Text(subject)
            .gmFont(17, .semibold, maxSize: 26)
            .foregroundColor(GM.textPrimary)
            .lineLimit(1)
            .truncationMode(.tail)
            .padding(.horizontal, 4)
            .frame(maxWidth: .infinity)
            .opacity(scroll.titleOpacity(reduced: reduceMotion))
            .animation(reduceMotion ? .easeInOut(duration: GMMotion.short) : nil, value: scroll.collapsed)
            .accessibilityAddTraits(.isHeader)
            .accessibilityHidden(!scroll.collapsed)
    }
}

/// The toolbar's 1 pt bottom border, fading in with the compact title.
struct ReaderToolbarHairline: View {
    @ObservedObject var scroll: ReaderScrollState
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        Rectangle()
            .fill(GM.border)
            .frame(height: 1)
            .opacity(scroll.titleOpacity(reduced: reduceMotion))
            .animation(reduceMotion ? .easeInOut(duration: GMMotion.short) : nil, value: scroll.collapsed)
            .accessibilityHidden(true)
            .allowsHitTesting(false)
    }
}

// MARK: - Folded card, earlier-messages bar

/// A folded message: 64 high — avatar 32 · sender (bold while unread) ·
/// one-line snippet · time. Own messages read "You" and show a person glyph
/// in `primaryContainer` instead of an avatar. The caller makes the whole
/// card the tap target.
struct CollapsedMessageRow: View {
    let message: MailMessageFull
    let own: Bool
    let unread: Bool
    @ScaledMetric(relativeTo: .subheadline) private var avatarSide: CGFloat = 32
    @Environment(\.dynamicTypeSize) private var dts

    private var sender: String {
        own ? String(localized: "You", table: "Reader")
            : (message.from?.display.nilIfBlank() ?? String(localized: "Unknown sender"))
    }

    var body: some View {
        let side = min(avatarSide, 56)
        HStack(alignment: dts.isAccessibilitySize ? .top : .center, spacing: 12) {
            if own {
                ZStack {
                    Circle().fill(GM.primaryContainer)
                    Image(systemName: "person.fill")
                        .font(.system(size: side * 0.45, weight: .semibold))
                        .foregroundColor(GM.onPrimaryContainer)
                }
                .frame(width: side, height: side)
                .accessibilityHidden(true)
            } else {
                SenderAvatar(display: message.from?.display ?? "", address: message.from?.address ?? "", size: side)
                    .accessibilityHidden(true)
            }
            VStack(alignment: .leading, spacing: 2) {
                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    Text(sender)
                        .gmFont(15, unread ? .bold : .medium)
                        .foregroundColor(GM.textPrimary)
                        .lineLimit(dts.isAccessibilitySize ? 2 : 1)
                    Spacer(minLength: 4)
                    if let time = MailTime.short(message.createdAt) {
                        Text(time)
                            .gmFont(13)
                            .foregroundColor(GM.textTertiary)
                            .lineLimit(1)
                            .fixedSize()
                    }
                }
                Text(ReaderConversation.snippet(message))
                    .readerText(14, lineHeight: 20)
                    .foregroundColor(GM.textSecondary)
                    .lineLimit(dts.isAccessibilitySize ? 2 : 1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }
        .frame(minHeight: ReaderLayout.collapsedHeight - 24, alignment: .center)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityValue(unread ? Text("Unread", tableName: "Reader") : Text(verbatim: ""))
    }
}

/// "N earlier messages": a centred pill between two hairlines, 40 high.
struct EarlierMessagesBar: View {
    let hidden: Int
    let onReveal: () -> Void

    var body: some View {
        HStack(spacing: 0) {
            Rectangle().fill(GM.border).frame(height: 1)
            Button(action: onReveal) {
                Text(String(localized: "\(hidden) earlier messages", table: "Reader"))
                    .gmFont(13, .medium)
                    .foregroundColor(GM.textSecondary)
                    .lineLimit(2)
                    .multilineTextAlignment(.center)
                    .padding(.horizontal, 14)
                    .padding(.vertical, 6)
                    .background(Capsule().fill(GM.softFill))
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 10)
            .fixedSize(horizontal: true, vertical: false)
            .accessibilityHint(Text("Shows the folded messages", tableName: "Reader"))
            .accessibilityIdentifier("mail-earlier-pill")
            Rectangle().fill(GM.border).frame(height: 1)
        }
        .frame(minHeight: 40)
    }
}

/// The own-message accent: a 3 pt `primary` bar on the leading edge, inset 12
/// from top and bottom.
struct OwnMessageAccent: View {
    var body: some View {
        RoundedRectangle(cornerRadius: 2, style: .continuous)
            .fill(GM.primary)
            .frame(width: 3)
            .padding(.vertical, 12)
            .accessibilityHidden(true)
            .allowsHitTesting(false)
    }
}

// MARK: - Body pieces

/// "··· Show quoted text" / "Hide quoted text": 32 high, pill, `softFill`,
/// CaptionMedium `textSecondary`, inside a 44 pt hit area.
struct ReaderQuotePill: View {
    let open: Bool
    let onToggle: () -> Void

    var body: some View {
        Button(action: onToggle) {
            HStack(spacing: 8) {
                if !open {
                    Text(verbatim: "\u{00B7}\u{00B7}\u{00B7}")
                        .gmFont(15, .bold, maxSize: 22)
                        .accessibilityHidden(true)
                }
                Text(open ? String(localized: "Hide quoted text", table: "Reader")
                          : String(localized: "Show quoted text", table: "Reader"))
                    .gmFont(13, .medium)
            }
            .foregroundColor(GM.textSecondary)
            .padding(.horizontal, 14)
            .frame(minHeight: 32)
            .background(Capsule().fill(GM.softFill))
            .padding(.vertical, 6)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityValue(open ? Text("Expanded", tableName: "Reader") : Text("Collapsed", tableName: "Reader"))
        .accessibilityIdentifier("mail-quoted-toggle")
    }
}

/// A plain-text body at the reading size (16/25), its trailing quoted
/// history folded behind `ReaderQuotePill` (14/21 `textSecondary` once open).
struct ReaderPlainBody: View {
    let text: String
    let messageId: String
    let forwarded: Bool
    @State private var open: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    init(text: String, messageId: String, forwarded: Bool) {
        self.text = text
        self.messageId = messageId
        self.forwarded = forwarded
        _open = State(initialValue: ReaderSession.openQuotes.contains(messageId))
    }

    var body: some View {
        let split = ReaderQuotes.splitPlain(text, forwarded: forwarded)
        VStack(alignment: .leading, spacing: 0) {
            Text(split.visible)
                .readerText(16, lineHeight: 25)
                .foregroundColor(GM.textPrimary)
                .textSelection(.enabled)
                .fixedSize(horizontal: false, vertical: true)
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityIdentifier("mail-body")
            if let quoted = split.quoted {
                ReaderQuotePill(open: open) {
                    withAnimation(GMMotion.gated(GMMotion.standard(GMMotion.medium), reduced: reduceMotion)) {
                        open.toggle()
                    }
                    if open { ReaderSession.openQuotes.insert(messageId) } else { ReaderSession.openQuotes.remove(messageId) }
                }
                .padding(.top, 6)
                if open {
                    Text(quoted)
                        .readerText(14, lineHeight: 21)
                        .foregroundColor(GM.textSecondary)
                        .textSelection(.enabled)
                        .fixedSize(horizontal: false, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(.top, 8)
                        .transition(.opacity)
                        .accessibilityIdentifier("mail-quoted-text")
                }
            }
        }
    }
}

/// What a body area shows while its formatted body is fetched or measured:
/// three static `softFill` bars inside the height it will most likely need
/// (the cached height, else 120), so the card does not jump. One VoiceOver
/// sentence instead of unlabeled boxes.
struct ReaderBodySkeleton: View {
    let height: CGFloat

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            bar(1)
            bar(0.92)
            bar(0.6)
        }
        .frame(maxWidth: .infinity, minHeight: height, maxHeight: height, alignment: .topLeading)
        .accessibilityElement()
        .accessibilityLabel(Text("Loading message"))
    }

    private func bar(_ fraction: CGFloat) -> some View {
        GeometryReader { proxy in
            RoundedRectangle(cornerRadius: 6, style: .continuous)
                .fill(GM.softFill)
                .frame(width: proxy.size.width * fraction, height: 12)
        }
        .frame(height: 12)
    }
}

/// Reply · Reply all · Forward as 40-high text-and-icon buttons, on the LAST
/// expanded card only (the others reach them through ⋮). Stacks when the
/// words no longer fit side by side.
struct ReaderFooterActions: View {
    let onReply: (String) -> Void

    var body: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 8) { buttons }
            VStack(alignment: .leading, spacing: 6) { buttons }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }

    @ViewBuilder private var buttons: some View {
        action("Reply", icon: "arrowshape.turn.up.left", mode: "reply", id: "mail-card-reply")
        action("Reply all", icon: "arrowshape.turn.up.left.2", mode: "all", id: "mail-card-reply-all")
        action("Forward", icon: "arrowshape.turn.up.right", mode: "forward", id: "mail-card-forward")
    }

    private func action(_ label: LocalizedStringKey, icon: String, mode: String, id: String) -> some View {
        Button { onReply(mode) } label: {
            Label(label, systemImage: icon)
                .gmFont(14, .semibold)
                .foregroundColor(GM.primaryText)
                .lineLimit(1)
                .padding(.horizontal, 14)
                .frame(minHeight: 40)
                .background(Capsule().strokeBorder(GM.border))
                .contentShape(Capsule())
                .fixedSize(horizontal: true, vertical: false)
        }
        .buttonStyle(.plain)
        .frame(minHeight: 44)
        .accessibilityIdentifier(id)
    }
}
