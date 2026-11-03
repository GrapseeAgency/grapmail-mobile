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
    if count >= 20 { return "20+ emails recently" }
    if count >= 10 { return "10-20 emails recently" }
    return "10 or fewer emails recently"
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

struct SubscriptionsView: View {
    @ObservedObject var vm: MailboxModel
    let onBack: () -> Void
    @State private var rows: [MailMessage] = []
    @State private var loaded = false
    @State private var failed = false
    @State private var gone: [String] = []
    @State private var note: String?
    @State private var confirm: SubscriptionRowData?

    var body: some View {
        MailScaffold(title: "Subscriptions", onBack: onBack) {
            ScrollView {
                VStack(alignment: .leading, spacing: 0) {
                    Text("Senders that keep mailing this account, with a way to stop each one.")
                        .font(.system(size: 13)).lineSpacing(5)
                        .foregroundColor(GM.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                        .padding(.top, 6)
                    Spacer().frame(height: 10)
                    content
                    if let note {
                        Text(note)
                            .font(.system(size: 13)).lineSpacing(5)
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
                    Text("Nothing was sent to any sender until you press Unsubscribe. This screen "
                         + "never mails on your behalf on its own.")
                        .font(.system(size: 12)).lineSpacing(4)
                        .foregroundColor(GM.textTertiary)
                        .fixedSize(horizontal: false, vertical: true)
                    Spacer().frame(height: 24)
                }
                .padding(.horizontal, 20)
            }
        }
        .task {
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
                            note = "That unsubscribe address could not be opened."
                            confirm = nil
                            return
                        }
                        UIApplication.shared.open(target, options: [:]) { accepted in
                            note = (accepted
                                ? "Opened in your browser. GrapMail cannot see what the page said."
                                : "This device could not open that link.")
                                + " The row is removed here because this device "
                                + "cannot see what the page said."
                            gone = gone + [s.address]
                            confirm = nil
                        }
                    },
                    onCancel: { confirm = nil }
                )
            } else {
                VStack(alignment: .leading, spacing: 12) {
                    Text(s.name + " does not offer an unsubscribe, so there is nothing "
                         + "this device can hand off.")
                        .font(.system(size: 13)).lineSpacing(5)
                        .foregroundColor(GM.textSecondary)
                        .fixedSize(horizontal: false, vertical: true)
                    Button("Close") { confirm = nil }
                        .font(.system(size: 14))
                        .foregroundColor(GM.textTertiary)
                }
                .padding(20)
                .presentationDetents([.height(160)])
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
            Text("Could not read the mailbox just now. Try again — open the inbox, "
                 + "then come back.")
                .font(.system(size: 14)).foregroundColor(GM.error)
                .padding(.vertical, 16)
                .accessibilityIdentifier("gm-subs-failed")
        } else if !loaded {
            Text("Reading your mail…")
                .font(.system(size: 14)).foregroundColor(GM.textTertiary)
                .padding(.vertical, 16)
                .accessibilityIdentifier("gm-subs-reading")
        } else if subs.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
                Text("No subscriptions detected.")
                    .font(.system(size: 15)).foregroundColor(GM.textPrimary)
                    .padding(.vertical, 6)
                Text("This screen reads the incoming inbox mail the app has loaded on this "
                     + "device — \(scopeCount) messages — and groups the senders that keep "
                     + "mailing you. A sender counts with two messages when they offer an "
                     + "unsubscribe, or three when they do not. Nothing is shown here as "
                     + "an example; the moment mail arrives, the senders appear with their "
                     + "frequency and a way to stop them.")
                    .font(.system(size: 13)).lineSpacing(5)
                    .foregroundColor(GM.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
                scopeHint
            }
            .accessibilityIdentifier("gm-subs-empty")
        } else {
            Text("When you unsubscribe, it can take senders a few days to stop "
                 + "sending messages.")
                .font(.system(size: 13)).lineSpacing(5)
                .foregroundColor(GM.textSecondary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.vertical, 6)
            ForEach(subs) { s in row(s) }
            Text("This screen grouped the \(scopeCount) incoming messages the app has "
                 + "loaded; a sender whose mail is not on this device is not on this list.")
                .font(.system(size: 12)).lineSpacing(4)
                .foregroundColor(GM.textTertiary)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.top, 8)
            scopeHint
        }
    }

    private var scopeHint: some View {
        Text("When a sender offers a one-click unsubscribe, it is used; otherwise their "
             + "own unsubscribe page opens.")
            .font(.system(size: 12)).lineSpacing(4)
            .foregroundColor(GM.textTertiary)
            .fixedSize(horizontal: false, vertical: true)
            .padding(.top, 8)
    }

    @ViewBuilder private func row(_ s: SubscriptionRowData) -> some View {
        let webTarget = unsubscribeTarget(s.unsubscribe)
        let mailto = unsubscribeMailto(s.unsubscribe)
        HStack(spacing: 12) {
            SenderAvatar(display: s.name, address: s.address, size: 38)
            VStack(alignment: .leading, spacing: 2) {
                Text(s.name).font(.system(size: 14)).foregroundColor(GM.textPrimary)
                    .lineLimit(1)
                Text(s.address).font(.system(size: 12)).foregroundColor(GM.textSecondary)
                    .lineLimit(1)
                Text(s.frequency).font(.system(size: 12)).foregroundColor(GM.textTertiary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            // The button names the handoff it actually performs. A one-click
            // offer is advertised, not verified from here, so the label never
            // says "one-click": the sheet's own lines carry that precision.
            Button {
                if webTarget != nil { confirm = s }
                else if let addr = mailto { mailtoHandoff(addr) }
            } label: {
                Text("Unsubscribe")
                    .font(.system(size: 13, weight: .medium))
                    .foregroundColor(.white)
                    .padding(.horizontal, 18).padding(.vertical, 13)
                    .background(Capsule().fill(GM.primarySolid))
            }
            .accessibilityIdentifier("gm-subs-action")
        }
        .padding(.vertical, 14)
        .accessibilityIdentifier("gm-subs-row")
    }

    /// The mailto offer opens the MAIL, not a web page: a draft addressed to
    /// the sender's unsubscribe list, which the person then sends. The
    /// device's mail composer answers that; this app is not it, and it says
    /// what it opened rather than claiming the letter went.
    private func mailtoHandoff(_ addr: String) {
        guard let url = URL(string: "mailto:\(addr)?subject=unsubscribe") else { return }
        UIApplication.shared.open(url, options: [:]) { accepted in
            note = accepted
                ? "The device opened a draft to the sender's unsubscribe address. "
                    + "Nothing has left this phone until you send it."
                : "This device has no mail app that can open that address, so "
                    + "nothing was drafted."
        }
    }
}
