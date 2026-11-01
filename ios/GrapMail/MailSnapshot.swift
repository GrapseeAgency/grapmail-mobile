import SwiftUI

/// Test-only entry into the mailbox screens a read-only CI walk cannot reach.
///
/// Why this exists rather than a seeded data source inside the app: the list,
/// the thread, the receipt and every sheet below only show real content after
/// mail has been delivered to a provisioned mailbox. CI signs up a throwaway
/// account, so the live path legitimately renders the loading, empty,
/// failed and no-mailbox states — those are photographed from the real screen.
/// Everything that needs a message to exist is rendered here instead, from
/// literals, with closures that do nothing, exactly like the security sector's
/// post-write confirmations.
///
/// Nothing in the real flow passes the argument, and no screen below is
/// reachable without it. Each one carries the `gm-snapshot` marker; the walk
/// requires it positively, because "no error message appeared" is not evidence
/// that the intended screen was on screen — eleven screenshots in the previous
/// sector were taken of a different view layered over the stub.
enum MailSnapshot {
    static func requested(_ name: String) -> Bool { name.hasPrefix("mail-") }
}

struct MailSnapshotView: View {
    let name: String

    /// The derived screens read the same cache the live list writes, and that
    /// cache is an actor, so their content arrives one hop after the screen asks
    /// for it. The host draws nothing until the seed has landed — otherwise the
    /// photograph catches the empty branch of a screen it means to show full.
    @State private var cached = false

    /// One model for the frame, built and seeded BEFORE the first render.
    ///
    /// Seeding in a `.task` lost a race: the thread screen asks its model for the
    /// conversation on appear, that ask goes out before the task lands, and the
    /// failure that comes back replaces a body the host had just seeded. Initial
    ///ising in `init` means the screen's first look at its model is already the
    /// seeded one. The actor cache is async and still seeds in the task — the
    /// derived screens gate on that flag for the same reason.
    @State private var model: MailboxModel

    init(name: String) {
        self.name = name
        _model = State(initialValue: MailSnapshotView.seededModel(name))
    }

    private static func seededModel(_ name: String) -> MailboxModel {
        let m = MailboxModel()
        m.applySnapshotSeeds(name)
        return m
    }

    // ── the sample mailbox ───────────────────────────────────────────────────
    // Senders and subjects are invented for the photograph. They never appear
    // in a live path, so a populated screen can be reviewed without anyone
    // mistaking the picture for delivered mail.
    private static func row(
        _ id: String, _ from: String, _ address: String, _ subject: String,
        _ snippet: String, unread: Bool = false, starred: Bool = false,
        files: Bool = false, when: String = "2026-10-29T09:12:00Z",
    ) -> MailMessage {
        var m = MailMessage()
        m.id = id
        m.threadId = "t-\(id)"
        var p = MailPerson()
        p.name = from
        p.address = address
        m.from = p
        m.to = ["you@grapmail.com"]
        m.subject = subject
        m.snippet = snippet
        m.isRead = !unread
        m.isStarred = starred
        m.hasAttachments = files
        m.createdAt = when
        return m
    }

    private static let rows = [
        row("m1", "Amira Rahman", "amira@northwind.example", "Q3 facilities invoice",
            "Attached is the invoice for the quarter, payable on receipt.", unread: true),
        row("m2", "Devon Okafor", "devon@brightline.example", "Standoff on the shared calendar",
            "Can we move the review forty minutes later? The room is booked.",
            unread: true, starred: true),
        row("m3", "Priya Nathan", "priya@havenworks.example", "Draft contract, second pass",
            "Clause 4 is the only one still open. Everything else reads clean.", files: true),
        row("m4", "GrapMail Security", "security@grapmail.com", "Your recovery kit is ready",
            "The kit is generated on your device and never leaves it unread."),
        row("m5", "Tomas Varga", "tomas@fieldkit.example", "Re: Winter delivery windows",
            "Agreed — we will hold the Thursday slot for both routes."),
    ]

    /// The sample page, in the cache the derived screens read. Contacts come from
    /// loaded mail and Files from opened bodies — the API has no contacts index
    /// and no list-all attachments — so a populated photograph means putting the
    /// sample mail where a fetch would have put it.
    fileprivate static func page() -> MailInboxResponse {
        var r = MailInboxResponse()
        r.success = true
        r.box = "inbox"
        r.total = rows.count
        r.messages = rows
        return r
    }

    fileprivate static func attachmentBody() -> MailMessageFull {
        Self.full("m1", body: "Attached is the invoice for the quarter, payable on receipt.")
    }

    /// The label colours are the server's palette KEYS, not hex values: a hex is
    /// not something `grapmail-labels.ts` can answer with, because `normaliseColour`
    /// rejects it and stores `slate`. Sample data shaped like something the server
    /// cannot send would make a broken colour lookup look correct in this file.
    fileprivate static func labels() -> [MailLabel] {
        var work = MailLabel()
        work.id = "l1"; work.name = "Work"; work.color = "blue"; work.unread = 2
        var bills = MailLabel()
        bills.id = "l2"; bills.name = "Bills"; bills.color = "green"; bills.unread = nil
        var receipts = MailLabel()
        receipts.id = "l3"; receipts.name = "Receipts"; receipts.color = "amber"
        receipts.unread = 1
        return [work, bills, receipts]
    }

    /// All three states the assignment read can produce, for the Label sheet
    /// frame: on every selected message, on some, on none. Photographing only the
    /// unmarked sheet would leave the tick and the dash unproven.
    fileprivate static let labelMarks: [String: LabelMark] = [
        "l1": .on, "l2": .mixed, "l3": .off,
    ]

    fileprivate static func full(
        _ id: String, body: String?, encrypted: Bool = false,
    ) -> MailMessageFull {
        let dec = JSONDecoder()
        // Built by decoding the same JSON the server sends, so the snapshot
        // exercises the real decoder rather than a parallel object graph. If the
        // shape drifts, this photograph drifts with it — which is the point.
        let json = """
        {"id":"\(id)","threadId":"t-\(id)","direction":"in","status":"delivered",
         "from":{"name":"Amira Rahman","address":"amira@northwind.example"},
         "to":["you@grapmail.com"],"subject":"Q3 facilities invoice",
         "isRead":true,"isStarred":true,"hasAttachments":\(encrypted ? "false" : "true"),
         "isE2eEncrypted":\(encrypted),
         "createdAt":"2026-10-29T09:12:00Z",
         "bodyText":\(body.map { "\"\($0)\"" } ?? "null"),
         "attachments":[{"id":"a1","name":"invoice-q3.pdf","mimeType":"application/pdf",
                         "sizeBytes":1415732}]}
        """
        return (try? dec.decode(MailMessageFull.self, from: Data(json.utf8))) ?? MailMessageFull()
    }

    var body: some View {
        frame
            .task(id: name) {
                // Re-assert the seeds, then fill the cache. The re-assert is not
                // redundant: the thread screen's own appear-time ask is still in
                // flight when this lands, and when it fails it writes an error
                // state over the seeded conversation — so the content goes back
                // once more after that arrives. The cache is an actor, so its
                // writes cannot happen in `init` at all, and the screens that
                // read it wait on `cached` instead of drawing their empty branch.
                model.applySnapshotSeeds(name)
                guard Self.derivedNames.contains(name) else { return }
                await MailStore.shared.putPage(.inbox, nil, Self.page(), replace: true)
                await MailStore.shared.putBody(Self.attachmentBody())
                cached = true
            }
            .accessibilityIdentifier("gm-snapshot")
    }

    /// A plain function with one explicit return per arm, not a ViewBuilder
    /// switch. Twenty-six heterogeneous branch types inside one builder
    /// expression is past what the compiler will resolve — it fails with
    /// 'generic parameter R could not be inferred' against the Group and names
    /// nothing useful. AnyView at each arm keeps the branches independent.
    private var frame: AnyView {
        switch name {
            case "mail-home":
                // No `mail-home-dark` case: the dark home is this same screen with the
                // device appearance forced (`overrideUserInterfaceStyle = -gmDark` in
                // `GrapMailUITests/ScreenshotWalkUITests.swift`). A second name for the
                // second palette was a second copy of a screen, launched by nothing,
                // and the name-set pin in `scripts/mail-honesty.py` now refuses that.
                return AnyView(
                listFrame(rows: Self.rows, loading: false, failed: false,
                          noMailbox: false, box: .inbox)
                )
                return AnyView(
                drawerFrame
                )
            case "mail-empty":
                return AnyView(
                listFrame(rows: [], loading: false, failed: false,
                          noMailbox: false, box: .archive)
                )
            case "mail-loading":
                return AnyView(
                listFrame(rows: [], loading: true, failed: false,
                          noMailbox: false, box: .inbox)
                )
            case "mail-load-failed":
                return AnyView(
                listFrame(rows: [], loading: false, failed: true,
                          noMailbox: false, box: .inbox)
                )
            case "mail-no-mailbox":
                return AnyView(
                listFrame(rows: [], loading: false, failed: false,
                          noMailbox: true, box: .inbox)
                )
                return AnyView(
                searchFrame
                )
            case "mail-selection":
                return AnyView(
                selectionFrame
                )
            // No `mail-drawer`, `mail-search` or `mail-sort-filter` case, and no
            // `drawerFrame`/`searchFrame` to draw them: frames 62, 67 and 69 are
            // photographed from the LIVE screen on both platforms (the walk opens the
            // drawer, types the term and raises the sort sheet itself). A host case for
            // a screen the walk already reaches is a second copy of that screen -- the
            // rule this sector already enforces for the send receipt -- and the name-set
            // pin in `scripts/mail-honesty.py` now refuses one on either side, which is
            // how these three were found dead here and on Android at once.
            case "mail-move-sheet":
                return AnyView(
                sheetFrame(MoveSheet { _ in })
                )
            case "mail-delete-sheet":
                return AnyView(
                sheetFrame(PermanentDeleteSheet(count: 3, cancel: {}, confirm: {}))
                )
            case "mail-assign-label-sheet":
                return AnyView(
                sheetFrame(AssignLabelSheet(
                    labels: Self.labels(),
                    labelsRead: .read,
                    marks: Self.labelMarks,
                    read: .read,
                    note: nil,
                    selectedCount: 3) { _ in })
                )
            case "mail-snooze-sheet":
                return AnyView(
                sheetFrame(SnoozeSheet { _ in })
                )
            // The unsubscribe confirmation is reachable only from a message carrying
            // the sender's `List-Unsubscribe` header, which no CI mailbox has, so
            // without this frame the screen would ship unphotographed. The address is
            // an invented newsletter host on a reserved TLD; the renderer is the live
            // `UnsubscribeSheet`, so the photograph is of the screen a person sees.
            case "mail-unsubscribe":
                return AnyView(
                sheetFrame(UnsubscribeSheet(
                    sender: "hello@newsletter.example",
                    url: "https://lists.newsletter.example/unsub?id=7f3",
                    onOpen: {}, onCancel: {}))
                )
            // The sender-decision sheet appears only when the screener queue is
            // open with messages selected, so no read-only walk reaches it on a
            // fresh CI mailbox — the queue is empty there. Two senders rather than
            // one: the plural line is the branch that would never otherwise be
            // photographed, and the scope row reads differently. Android's host
            // draws the same two addresses.
            case "mail-sender-sheet":
                return AnyView(
                sheetFrame(SenderDecisionSheet(
                    senders: ["priya@havenworks.example", "tomas@fieldkit.example"],
                    busy: false, result: nil, onPick: { _, _ in }))
                )
            case "mail-labels":
                return AnyView(
                secondary(LabelsView(vm: model, onBack: {}, onCreate: {}, onEdit: { _ in }))
                )
            case "mail-label-create":
                return AnyView(
                secondary(LabelCreateView(vm: model, onDone: {}))
                )
            case "mail-thread":
                return AnyView(
                secondary(ThreadView(vm: model, id: "t-m1",
                                     isThread: true, onBack: {}, onReply: { _ in }))
                )
            case "mail-thread-encrypted":
                return AnyView(
                secondary(ThreadView(vm: model, id: "t-m9",
                                     isThread: true, onBack: {}, onReply: { _ in }))
                )
            case "mail-compose":
                return AnyView(
                secondary(ComposeView(vm: model, onBack: {}))
                )
            case "mail-send-receipt":
                return AnyView(
                sheetFrame(VStack(alignment: .leading, spacing: 12) {
                    // The live `SendReceipt`, with the strings a server would
                    // send standing in for a real send. Only the DATA is invented
                    // here; the rendering is the shipping one, which is what makes
                    // the photograph evidence rather than a likeness.
                    SendReceipt(status: "queued",
                                    detail: "Accepted for delivery.", error: nil)
                    SendReceipt(status: "failed",
                                    detail: nil, error: "Recipient domain not reachable.")
                })
                )
            case "mail-contacts":
                return AnyView(
                derived(ContactsView(vm: model, onBack: {}, onOpen: { _ in }))
                )
            case "mail-contact-detail":
                return AnyView(
                derived(ContactDetailView(vm: model, address: "amira@northwind.example",
                                         onBack: {}, onWrite: {}))
                )
            case "mail-files":
                return AnyView(
                derived(FilesView(vm: model, onBack: {}, onOpen: { _ in }))
                )
            case "mail-attachment-preview":
                return AnyView(
                derived(AttachmentPreviewView(vm: model, attachmentId: "a1",
                                              onBack: {}))
                )
            case "mail-settings":
                return AnyView(
                secondary(MailSettingsView(vm: model, onBack: {},
                                           onSecurity: {}, onScreener: {}, onLabels: {},
                                           onHelp: {}, onSignOut: {}))
                )
            case "mail-screener":
                return AnyView(
                secondary(ScreenerView(vm: model, onBack: {}, onOpenQueue: {}))
                )
            default:
                return AnyView(
                Text("Unknown mail snapshot: \(name)")
                    .foregroundColor(GM.textSecondary)
                    .accessibilityIdentifier("gm-snapshot-unknown")
                )
        }
    }

    /// The names whose screens compute content from the cache rather than from
    /// the state the host seeds directly.
    static let derivedNames: Set<String> = [
        "mail-contacts", "mail-contact-detail", "mail-files", "mail-attachment-preview",
    ]

    /// `AnyView` on the three helpers that take a caller's view type. Without
    /// it the switch above has a dozen distinct branch types to resolve in one
    /// pass, and the compiler reports 'generic parameter R could not be inferred'
    /// against the Group — the same oversized-body class this repo has been
    /// bitten by twice. Erasing at the boundary costs nothing here: this is a
    /// test-only host, not a rendered list.
    private func derived<V: View>(_ content: V) -> AnyView {
        cached ? secondary(content) : AnyView(CenteredState(title: "Preparing snapshot…"))
    }

    // ── frames ───────────────────────────────────────────────────────────────

    /// The list states, drawn by the same `MailStateBlock` the live screen uses.
    /// The distinction between empty, failed and no-mailbox is the thing being
    /// photographed, so it must not be re-implemented here.
    private func listFrame(
        rows: [MailMessage], loading: Bool, failed: Bool, noMailbox: Bool, box: MailBox,
    ) -> some View {
        VStack(spacing: 0) {
            MailGreeting(name: "Amira Rahman", address: "amira@grapmail.com")
                .padding(.horizontal, 16).padding(.top, 10)
            MailStateBlock(
                loading: loading, failed: failed, noMailbox: noMailbox,
                empty: rows.isEmpty, boxTitle: box.title, onRetry: {}
            ) {
                VStack(spacing: 0) {
                    ForEach(rows) { m in
                        MessageRow(message: m, selecting: false, selected: false,
                                   onSelect: {}, onOpen: {}, onStar: {})
                        Rectangle().fill(GM.border).frame(height: 1)
                            .padding(.horizontal, 16)
                    }
                }
            }
        }
        .background(GM.bg)
    }

    private var selectionFrame: some View {
        VStack(spacing: 0) {
            SelectionToolbar(count: 2, onClose: {}, onArchive: {}, onDelete: {},
                             onMove: {}, onLabel: {}, onMore: {})
            MessageRow(message: Self.rows[0], selecting: true, selected: true,
                       onSelect: {}, onOpen: {}, onStar: {})
            MessageRow(message: Self.rows[1], selecting: true, selected: true,
                       onSelect: {}, onOpen: {}, onStar: {})
            MessageRow(message: Self.rows[2], selecting: true, selected: false,
                       onSelect: {}, onOpen: {}, onStar: {})
            Spacer()
        }
        .background(GM.bg)
    }

    private func sheetFrame<V: View>(_ content: V) -> AnyView {
        AnyView(
            VStack(alignment: .leading, spacing: 0) {
                content
                Spacer()
            }
            .background(GM.card)
        )
    }

    private func secondary<V: View>(_ content: V) -> AnyView {
        AnyView(content.frame(maxWidth: .infinity, maxHeight: .infinity)
            .background(GM.bg))
    }

    // ── models seeded for the photograph only ────────────────────────────────


}

// The receipt frame is drawn by the live `SendReceipt` in `MailReaderViews.swift`.
// Nothing here reimplements it: the host's own copy used to handle only two of
// the four status words, so the photograph and the screen were already different
// pictures of the same fact.

// MARK: - Snapshot seeds on the model

extension MailboxModel {
    func applySnapshotSeeds(_ name: String) {
        seedForSnapshot { s in
            s.accountName = "Amira Rahman"
            s.accountAddress = "amira@grapmail.com"
            s.accountStatus = "active"
            s.memberSince = "March 2026"
        }
        // Both stores: the labels screen reads `labelState`, the inbox chip strip
        // reads `state.labels`. Filling one and not the other is how `mail-labels`
        // photographed its empty branch with the sample data set.
        seedLabelsForSnapshot(MailSnapshotView.labels())
        switch name {
        case "mail-thread":
            seedThreadForSnapshot([MailSnapshotView.full("m1",
                body: "Attached is the invoice for the quarter, payable on receipt. "
                      + "The facilities line item moved again — see page two.")],
                for: "t-m1")
        case "mail-thread-encrypted":
            seedThreadForSnapshot([MailSnapshotView.full("m9", body: nil,
                                                            encrypted: true)],
                                  for: "t-m9")
        case "mail-screener":
            seedScreenerForSnapshot(enabled: true, pending: 3)
        default:
            break
        }
    }
}
