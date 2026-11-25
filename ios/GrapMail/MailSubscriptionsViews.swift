import SwiftUI

/// Subscriptions — the mobile form of the web's `/mail/subscriptions`. Swift
/// mirror of Android's `SubscriptionsScreen.kt`; the wording is deliberately
/// identical on both platforms.
///
/// The list comes from the account's own mail, grouped by sender. Nothing is
/// seeded and no row is invented: with no incoming mail the screen says exactly
/// that, and names what it is waiting for. The grouping rules are the web's
/// (`src/app/lib/subscriptions.ts:111-165`) kept line for line, because two
/// clients disagreeing about WHO counts as a subscription is two different
/// lists from one mailbox.
///
/// ── WHY THE ACTION IS A HANDOFF, NOT A CALL ──────────────────────────────────
/// The web POSTs one-click unsubscribe requests from ITS server, behind an
/// SSRF guard (`src/app/api/grapmail/unsubscribe/route.ts`). That route is a
/// Next.js surface; the phones talk to the API gateway directly and no
/// equivalent exists there, and making the POST from the device would leak the
/// user's IP and skip the guard entirely. So the mobile action is the per-row
/// browser handoff the plan allows — the SAME confirmation the thread's
/// unsubscribe sheet already ships, and the screen says so instead of offering
/// a button that would lie.

/// One detected subscription. Mirrors the web's `Subscription` and Android's
/// `SubscriptionRow`. `Identifiable` because the confirmation is presented as
/// `.sheet(item:)` — the row the sheet belongs to is the row's identity, and
/// the address is the key detection grouped by.
struct SubscriptionRowData: Identifiable {
    let address: String
    var name: String
    var count: Int
    var lastAt: String
    var frequency: String
    var unsubscribe: MailUnsubscribe?
    var id: String { address }
}

private let serviceLocalPattern =
    "(noreply|no-reply|donotreply|do-not-reply|mailer|bounce|notifications?)"

/// The web's `looksLikeAPerson`, kept as its inverse on the local part.
private func looksLikeAPerson(_ address: String) -> Bool {
    let local = address.lowercased().split(separator: "@").first.map(String.init) ?? ""
    return local.range(of: serviceLocalPattern, options: .regularExpression) == nil
}

/// The web's `frequencyLabel`, same three buckets, same phrases.
func frequencyLabel(_ count: Int) -> String {
    if count >= 20 { return String(localized: "20+ emails recently") }
    if count >= 10 { return String(localized: "10-20 emails recently") }
    return String(localized: "10 or fewer emails recently")
}

/// The web's `detectSubscriptions` over the messages this device has loaded:
/// a sender qualifies with two rows carrying an unsubscribe offer, or three
/// rows when the address does not look like a person; the newest row wins for
/// the display name and the offer; the noisiest sender sorts first.
///
/// "Rows the app has fetched" is the whole scope and the screen says it — a
/// list that claimed every subscription in the mailbox would claim more than
/// the code can see.
func detectSubscriptions(_ messages: [MailMessage]) -> [SubscriptionRowData] {
    final class Bucket {
        var name = ""
        var count = 0
        var lastAt = ""
        var unsubscribe: MailUnsubscribe?
    }
    var bySender: [String: Bucket] = [:]
    var order: [String] = []
    for m in messages {
        let address = (m.from?.address ?? "").trimmingCharacters(in: .whitespaces).lowercased()
        if address.isEmpty || !address.contains("@") { continue }
        // Subscriptions arrive; they are not things you sent.
        let direction = (m.direction ?? "").lowercased()
        guard direction == "in" || direction == "inbound" else { continue }
        let at = m.createdAt ?? ""
        if let prior = bySender[address] {
            prior.count += 1
            if at > prior.lastAt {
                prior.lastAt = at
                if let n = m.from?.name?.nilIfBlank() { prior.name = n }
                if let u = m.unsubscribe { prior.unsubscribe = u }
            }
        } else {
            let b = Bucket()
            b.name = m.from?.name?.nilIfBlank() ?? String(address.split(separator: "@")[0])
            b.count = 1
            b.lastAt = at
            b.unsubscribe = m.unsubscribe
            bySender[address] = b
            order.append(address)
        }
    }
    return order.compactMap { address -> SubscriptionRowData? in
        guard let b = bySender[address] else { return nil }
        let hasUnsub = unsubscribeTarget(b.unsubscribe) != nil ||
            unsubscribeMailto(b.unsubscribe) != nil
        if hasUnsub {
            guard b.count >= 2 else { return nil }
        } else {
            guard b.count >= 3 && !looksLikeAPerson(address) else { return nil }
        }
        return SubscriptionRowData(address: address, name: b.name, count: b.count,
                                   lastAt: b.lastAt, frequency: frequencyLabel(b.count),
                                   unsubscribe: b.unsubscribe)
    }
    .sorted { a, b in
        if a.count != b.count { return a.count > b.count }
        return a.lastAt > b.lastAt
    }
}

/// Layout: every state (skeleton, error, list) keeps the readable column
/// (`GMLayout.readable`), centred on wide windows; a row's Unsubscribe button
/// sits beside the sender while it fits and moves under it when it does not.
/// The offline banner is MailScaffold's own — this screen used to draw a
/// second one under it.
struct SubscriptionsView: View {
    @ObservedObject var vm: MailboxModel
    let onBack: () -> Void
    @Environment(\.dynamicTypeSize) private var dts
    @State private var rows: [MailMessage] = []
    @State private var loaded = false
    @State private var failed = false
    @State private var gone: [String] = []
    @State private var note: String?
    @State private var confirm: SubscriptionRowData?
    @State private var attempt = 0
    /// Sender disc beside each row (38 pt at the default text size).
    @ScaledMetric(relativeTo: .body) private var avatarSide: CGFloat = 38
    /// The "no unsubscribe offered" sheet (160 pt at the default text size).
    @ScaledMetric(relativeTo: .body) private var noOfferSheetHeight: CGFloat = 160

    var body: some View {
        MailScaffold(title: String(localized: "Subscriptions"), onBack: onBack) {
            // First read and a failed read use the shared states, outside the
            // scroll view (they size themselves).
            if failed {
                GMErrorState(message: String(localized: "Could not read the mailbox just now. Try again — open the inbox, then come back.")) { attempt += 1 }
                    .gmMaxWidth(GMLayout.readable)
                    .accessibilityIdentifier("gm-subs-failed")
            } else if !loaded {
                GMSkeletonList(rows: 6)
                    .gmMaxWidth(GMLayout.readable)
                    .accessibilityIdentifier("gm-subs-reading")
            } else {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text("Senders that keep mailing this account, with a way to stop each one.")
                        .gmFont(13).lineSpacing(5)
                        .foregroundColor(GM.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 6)
                    Spacer().frame(height: 10)
                    content
                    if let note {
                        Text(note)
                            .gmFont(13).lineSpacing(5)
                            .foregroundColor(GM.textPrimary)
                            .fixedSize(horizontal: false, vertical: true)
                            .frame(maxWidth: .infinity, alignment: .leading)
                            .padding(.horizontal, 16).padding(.vertical, 12)
                            .background(RoundedRectangle(cornerRadius: 12, style: .continuous)
                                .fill(GM.softFill))
                            .padding(.top, 10)
                            .accessibilityIdentifier("gm-subs-note")
                    }
                    Spacer().frame(height: 14)
                    Text("Nothing was sent to any sender until you press Unsubscribe. This screen never mails on your behalf on its own.")
                        .gmFont(12).lineSpacing(4)
                        .foregroundColor(GM.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer().frame(height: 24)
                }
                .padding(.horizontal, 20)
                // Wide windows (iPad, landscape, Mac): keep the reading column
                // comfortable instead of stretching rows across the window.
                .gmMaxWidth(GMLayout.readable)
            }
            }
        }
        .task(id: attempt) {
            failed = false
            // The cache, not a fresh fetch: the same read the Contacts and
            // Files screens make. An absent inbox is "the mail has not been
            // opened here", not an empty mailbox.
            if let page = await MailStore.shared.page(.inbox, nil) {
                rows = page.messages
                loaded = true
            } else {
                failed = true
            }
        }
        .sheet(item: $confirm) { s in
            if let url = unsubscribeTarget(s.unsubscribe) {
                // The thread's own confirmation, unchanged: same four
                // statements, same host naming, same refusal to claim the
                // outcome. One sheet for one decision, not a friendlier copy
                // for the screen that sees more traffic.
                UnsubscribeSheet(
                    sender: s.address, url: url,
                    onOpen: {
                        // The thread's exact handoff rules, learned there from
                        // the compiler: only the completion-handler `open`
                        // answers, its Bool means the SYSTEM agreed to hand
                        // the link over, and no branch says "unsubscribed".
                        guard let target = URL(string: url) else {
                            note = String(localized: "That unsubscribe address could not be opened.")
                            confirm = nil
                            return
                        }
                        UIApplication.shared.open(target, options: [:]) { accepted in
                            // Whole sentences per outcome, not a translated
                            // tail appended to a translated head.
                            note = accepted
                                ? String(localized: "Opened in your browser. GrapMail cannot see what the page said. The row is removed here because this device cannot see what the page said.")
                                : String(localized: "This device could not open that link. The row is removed here because this device cannot see what the page said.")
                            gone = gone + [s.address]
                            confirm = nil
                        }
                    },
                    onCancel: { confirm = nil }
                )
            } else {
                // Scrolls, and its detent grows with the text size, so the
                // sentence is never clipped at the accessibility sizes; it can
                // also be pulled up to full height (a long sender name, a
                // short landscape window).
                ScrollView {
                    VStack(alignment: .leading, spacing: 12) {
                        Text("\(s.name) does not offer an unsubscribe, so there is nothing this device can hand off.")
                            .gmFont(13).lineSpacing(5)
                            .foregroundColor(GM.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                        Button("Close") { confirm = nil }
                            .gmFont(14)
                            .foregroundColor(GM.textTertiary)
                            .frame(minHeight: 44)
                    }
                    .padding(20)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .gmMaxWidth(GMLayout.form)
                }
                .presentationDetents([.height(noOfferSheetHeight), .large])
            }
        }
        .accessibilityIdentifier("gm-subs-screen")
    }

    private var subs: [SubscriptionRowData] {
        detectSubscriptions(rows).filter { !gone.contains($0.address) }
    }

    private var scopeCount: Int {
        rows.filter {
            let d = ($0.direction ?? "").lowercased()
            return d == "in" || d == "inbound"
        }.count
    }

    @ViewBuilder private var content: some View {
        if failed {
            Text("Could not read the mailbox just now. Try again — open the inbox, then come back.")
                .gmFont(14).foregroundColor(GM.error)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.vertical, 16)
                .accessibilityIdentifier("gm-subs-failed")
        } else if !loaded {
            Text("Reading your mail…")
                .gmFont(14).foregroundColor(GM.textTertiary)
                .padding(.vertical, 16)
                .accessibilityIdentifier("gm-subs-reading")
        } else if subs.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
                Text("No subscriptions detected.")
                    .gmFont(17, .semibold).foregroundColor(GM.textPrimary)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.top, 48).padding(.bottom, 10)
                Text("This screen reads the incoming inbox mail the app has loaded on this device — \(scopeCount) messages — and groups the senders that keep mailing you. A sender counts with two messages when they offer an unsubscribe, or three when they do not. Nothing is shown here as an example; the moment mail arrives, the senders appear with their frequency and a way to stop them.")
                    .gmFont(13).lineSpacing(5)
                    .foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                scopeHint
            }
            .accessibilityIdentifier("gm-subs-empty")
        } else {
            Text("When you unsubscribe, it can take senders a few days to stop sending messages.")
                .gmFont(13).lineSpacing(5)
                .foregroundColor(GM.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.vertical, 6)
            ForEach(subs) { s in row(s) }
            Text("This screen grouped the \(scopeCount) incoming messages the app has loaded; a sender whose mail is not on this device is not on this list.")
                .gmFont(12).lineSpacing(4)
                .foregroundColor(GM.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 8)
            scopeHint
        }
    }

    private var scopeHint: some View {
        Text("When a sender offers a one-click unsubscribe, it is used; otherwise their own unsubscribe page opens.")
            .gmFont(12).lineSpacing(4)
            .foregroundColor(GM.textTertiary)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.top, 8)
    }

    @ViewBuilder private func row(_ s: SubscriptionRowData) -> some View {
        let webTarget = unsubscribeTarget(s.unsubscribe)
        let mailto = unsubscribeMailto(s.unsubscribe)
        // The button names the handoff it actually performs. A one-click
        // offer is advertised, not verified from here, so the label never
        // says "one-click": the sheet's own lines carry that precision.
        let action = Button {
            if webTarget != nil { confirm = s }
            else if let addr = mailto { mailtoHandoff(addr) }
        } label: {
            Text("Unsubscribe")
                .gmFont(13, .medium)
                .foregroundColor(.white)
                .lineLimit(1)
                .minimumScaleFactor(0.8)
                .padding(.horizontal, 18).padding(.vertical, 13)
                .background(Capsule().fill(GM.primarySolid))
        }
        .accessibilityIdentifier("gm-subs-action")

        let identity = HStack(spacing: 12) {
            SenderAvatar(display: s.name, address: s.address, size: min(avatarSide, 56))
            VStack(alignment: .leading, spacing: 2) {
                Text(s.name).gmFont(14).foregroundColor(GM.textPrimary)
                    .lineLimit(2)
                Text(s.address).gmFont(12).foregroundColor(GM.textSecondary)
                    .lineLimit(dts.isAccessibilitySize ? 3 : 1)
                    .truncationMode(.middle)
                Text(s.frequency).gmFont(12).foregroundColor(GM.textTertiary)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
        }

        Group {
            if dts.isAccessibilitySize {
                // Avatar + name, then the button on its own line: side by side
                // they leave the name a few letters at the largest sizes.
                VStack(alignment: .leading, spacing: 10) {
                    identity
                    action
                }
            } else if dts <= .large {
                // The design's row, exactly as it was.
                HStack(spacing: 12) {
                    identity
                    action
                }
            } else {
                // Larger (non-accessibility) text, or a narrow Split View /
                // Slide Over column: beside while the button still fits at
                // its full size, under the sender once it does not.
                ViewThatFits(in: .horizontal) {
                    HStack(spacing: 12) {
                        identity
                        action.fixedSize()
                    }
                    VStack(alignment: .leading, spacing: 10) {
                        identity
                        action
                    }
                }
            }
        }
        .padding(.vertical, 14)
        .accessibilityIdentifier("gm-subs-row")
    }

    /// The mailto offer opens the MAIL, not a web page: a draft addressed to
    /// the sender's unsubscribe list, which the person then sends. The
    /// device's mail composer answers that; this app is not it, and it says
    /// what it opened rather than claiming the letter went.
    private func mailtoHandoff(_ addr: String) {
        guard let url = URL(string: "mailto:\(addr)?subject=unsubscribe") else {
            // The address is sender-controlled; an unbuildable URL must be
            // SAID, not swallowed — a tap that silently does nothing reads as
            // a broken button, and this screen's rule is it says what opened.
            note = String(localized: "That unsubscribe address could not be opened, so nothing was drafted.")
            return
        }
        UIApplication.shared.open(url, options: [:]) { accepted in
            note = accepted
                ? String(localized: "The device opened a draft to the sender's unsubscribe address. Nothing has left this phone until you send it.")
                : String(localized: "This device has no mail app that can open that address, so nothing was drafted.")
        }
    }
}
