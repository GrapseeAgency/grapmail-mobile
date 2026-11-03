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
    static func requested(_ name: String) -> Bool {
        name.hasPrefix("mail-") || name.hasPrefix("account-") || name.hasPrefix("e2e-")
    }
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

    /// The account record the eight Phase-1 screens read, seeded the same way
    /// as the mailbox model: the host owns the document for these frames, and
    /// the model's snapshot guard keeps its own boot read from overwriting it
    /// with the CI account's real (empty) notes list — the race the mailbox
    /// seeds already guard against.
    @State private var profile: ProfileModel

    /// The encryption sector's model, seeded before the first render like the
    /// other two. The E2E frames photograph state cards and gated actions, so
    /// the record, the published answer and the unlock flag all belong to the
    /// frame, never to a live probe (`seedForSnapshot` guards `load()` out).
    @State private var e2e: E2eModel

    init(name: String) {
        self.name = name
        _model = State(initialValue: MailSnapshotView.seededModel(name))
        let pm = ProfileModel()
        pm.seedForSnapshot(MailSnapshotView.sampleProfile())
        _profile = State(initialValue: pm)
        let em = E2eModel()
        let record = name == "e2e-status-none" ? nil : MailSnapshotView.sampleIdentity
        em.seedForSnapshot(record: record,
                           published: name == "e2e-publish" ? MailSnapshotView.samplePublished : nil,
                           fingerprint: record == nil ? nil : MailSnapshotView.sampleFingerprint,
                           unlocked: name != "e2e-unlock" && record != nil,
                           lookup: name == "e2e-recipients" ? MailSnapshotView.sampleLookup : [],
                           lookupFor: name == "e2e-recipients"
                               ? ["priya@havenworks.example", "tomas@fieldkit.example"] : [])
        _e2e = State(initialValue: em)
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
        unsub: MailUnsubscribe? = nil,
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
        m.unsubscribe = unsub
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
        // The subscription seeds, decoded through the REAL offer decoder from
        // the server's parsed shape (Android seeds the same two senders with
        // the same three rows each): a newsletter with the offers array, and
        // a no-reply digest carrying the legacy header string, so both
        // branches of `unsubscribeTarget` are photographed.
        row("n1", "The Weekly Draft", "hello@newsletter.example", "This week in type",
            "Three essays and a link dump.", when: "2026-10-27T08:00:00Z",
            unsub: MailSnapshotView.newsletterUnsub),
        row("n2", "The Weekly Draft", "hello@newsletter.example", "This week in letterforms",
            "Kerning quarrels, mostly.", when: "2026-10-28T08:00:00Z",
            unsub: MailSnapshotView.newsletterUnsub),
        row("n3", "The Weekly Draft", "hello@newsletter.example", "This week in ink",
            "A risograph issue.", when: "2026-10-29T08:00:00Z",
            unsub: MailSnapshotView.newsletterUnsub),
        row("g1", "Geo Weekly", "noreply@digest.example.org", "Your weekly map digest",
            "Six layers changed since Sunday.", when: "2026-10-26T07:00:00Z",
            unsub: MailSnapshotView.legacyDigestUnsub),
        row("g2", "Geo Weekly", "noreply@digest.example.org", "Your weekly map digest",
            "Two layers changed.", when: "2026-10-27T07:00:00Z",
            unsub: MailSnapshotView.legacyDigestUnsub),
        row("g3", "Geo Weekly", "noreply@digest.example.org", "Your weekly map digest",
            "One layer changed.", when: "2026-10-28T07:00:00Z",
            unsub: MailSnapshotView.legacyDigestUnsub),
    ]

    /// The parsed-offer record, decoded from the JSON the mail service
    /// answers — `oneClickAdvertised` deliberately false: offering the header
    /// is not the same claim as RFC 8058's POST, and the seed must not
    /// overstate what the sender advertised.
    fileprivate static let newsletterUnsub: MailUnsubscribe? = {
        let json = """
        {"offers":[{"url":"https://lists.newsletter.example/unsub?id=7f3","oneClick":false},
                    {"url":"mailto:leave@newsletter.example","oneClick":false}],
         "oneClickAdvertised":false}
        """
        return try? JSONDecoder().decode(MailUnsubscribe.self, from: Data(json.utf8))
    }()

    /// The legacy header string, so the frame exercises the parser fallback
    /// rather than only the offer array. Android seeds the same bytes.
    fileprivate static let legacyDigestUnsub: MailUnsubscribe? = {
        let json = "\"<https://digest.example.org/remove?id=42>, " +
            "<mailto:remove@digest.example.org>\""
        return try? JSONDecoder().decode(MailUnsubscribe.self, from: Data(json.utf8))
    }()

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

    /// The account record the eight Phase-1 frames are photographed with.
    /// Invented, like every seed in this file — it never appears in a live
    /// path — but built by DECODING the JSON `GET /auth/account/profile`
    /// answers (`auth-account-profile.ts:175-214`), so the snapshot exercises
    /// the real decoder rather than a parallel object graph. The alias rows
    /// carry the table's own fields; the connection list mixes a first-party
    /// product (no host, so it draws a monogram) with a third-party app (a
    /// host, so it draws its domain); and every settled marker the walk waits
    /// for — the birthday, the note, the task, the signature, the alias
    /// address, the product name, the created date — exists only once this
    /// document has landed.
    fileprivate static func sampleProfile() -> ProfileDto {
        let json = """
        {"id":"p1","name":"Amira Rahman","firstName":"Amira","lastName":"Rahman",
         "birthday":"1993-04-12","gender":"Female",
         "address":"amira@grapmail.com","handle":"amira","domain":"grapmail.com",
         "phone":"+8801711223344","phoneVerified":true,"twoFactor":"sms",
         "twoFactorEnabled":true,"backupCodesRemaining":7,
         "displayName":"Amira Rahman","recoveryEmail":"amira@northwind.example",
         "fromHeader":"Amira Rahman <amira@grapmail.com>",
         "createdAt":"2026-03-14T04:30:00.000Z",
         "passwordChangedAt":"2026-06-02T11:00:00.000Z",
         "termsAcceptedAt":"2026-03-14T04:31:10.000Z",
         "settings":{"signature":"Sent with GrapMail","density":"comfortable",
           "notes":["Send the Q3 numbers before Friday"],
           "tasks":["Book the studio visit"],
           "signatures":{"business@grapmail.com":"Northwind Studio"},
           "blockedSenders":["deals@spamyard.example"],
           "safeSenders":["hello@northwind.example"]},
         "aliases":[{"address":"business@grapmail.com","label":"Business",
                     "isActive":true,"createdAt":"2026-04-02T10:00:00.000Z"},
                    {"address":"old@grapmail.com","label":"Old",
                     "isActive":false,"createdAt":"2026-03-20T09:00:00.000Z"}],
         "connections":[{"product":"Grapsee Projects","scopes":["profile","projects"],
                         "connectedAt":"2026-07-01T08:00:00.000Z"},
                        {"product":"Northwind SSO","host":"sso.northwind.example",
                         "scopes":["profile"],"connectedAt":"2026-07-19T15:20:00.000Z"}]}
        """
        // A snapshot that decoded nothing would photograph the empty branches
        // of eight screens and call them populated, so a seed that fails to
        // decode — because the shape drifted from the route — stops the build
        // here instead of shipping eight lies.
        guard let seeded = try? JSONDecoder().decode(ProfileDto.self,
                                                    from: Data(json.utf8)) else {
            preconditionFailure("sampleProfile did not decode: the seed drifted "
                              + "from the profile route's answer")
        }
        return seeded
    }

    // ── the encryption seeds ─────────────────────────────────────────────────

    /// The device identity the encryption frames are photographed with.
    /// Invented like every seed here — it never appears in a live path — and
    /// shaped exactly like `MailE2e.saveIdentity` writes it: passphrase-
    /// protected records whose private material is opaque base64, because the
    /// screens may show the public half and the fingerprint and NOTHING else.
    /// The plaintext key does not exist in this file; that is the point of the
    /// §27 pin over it. Decoded through the store's own parser, so a drift in
    /// the container format fails the frame here rather than photographing an
    /// empty branch.
    fileprivate static let samplePublicKey = "bXNhbXBsZS1wdWJsaWMta2V5"
    fileprivate static let sampleFingerprint =
        "7C:0F:2A:91:B4:D8:3E:6C:05:AA:1F:BE:D2:48:67:90:13:EC:B6:24:FA:5D:71:09:8C:30:B7:E4:16:9F:A2:C8"

    fileprivate static let sampleIdentity: MailE2e.Identity? = {
        let json = """
        {"format":"grapmail-e2e-identity/1","createdAt":"2026-08-02T10:00:00Z",
         "encryption":{"format":"grapmail-e2e-protected-key/1","kdf":"PBKDF2-SHA256",
           "kind":"X25519","publicKey":"\(samplePublicKey)","iterations":600000,
           "salt":"c2VlZC1zYWx0","iv":"c2VlZC1pdg==","ct":"c2VlZC13cmFwcGVkLXByaXZhdGUtY3R8"},
         "signing":{"format":"grapmail-e2e-protected-key/1","kdf":"PBKDF2-SHA256",
           "kind":"Ed25519","publicKey":"\(samplePublicKey)","iterations":600000,
           "salt":"c2VlZC1zYWx0","iv":"c2VlZC1pdg==","ct":"c2VlZC13cmFwcGVkLXNpZ25pbmctY3R8"}}
        """
        let obj = (try? JSONSerialization.jsonObject(with: Data(json.utf8))) as? [String: Any]
        guard let raw = obj?["encryption"] as? [String: Any],
              let enc = MailSnapshotView.protectedSeed(raw) else { return nil }
        let sig = (obj?["signing"] as? [String: Any]).flatMap(MailSnapshotView.protectedSeed)
        return MailE2e.Identity(encryption: enc, signing: sig,
                                createdAt: obj?["createdAt"] as? String ?? "")
    }()

    private static func protectedSeed(_ obj: [String: Any]) -> MailE2e.ProtectedPrivateKey? {
        guard obj["format"] as? String == "grapmail-e2e-protected-key/1",
              obj["kdf"] as? String == "PBKDF2-SHA256",
              let kind = obj["kind"] as? String,
              let publicKey = obj["publicKey"] as? String,
              let iterations = obj["iterations"] as? Int,
              let salt = obj["salt"] as? String, let iv = obj["iv"] as? String,
              let ct = obj["ct"] as? String else { return nil }
        return MailE2e.ProtectedPrivateKey(kind: kind, publicKey: publicKey,
                                           iterations: iterations, salt: salt, iv: iv, ct: ct)
    }

    /// The directory's published answer, built through the real decoder from
    /// the route's own JSON shape. Its fingerprint is the seed the details
    /// frame waits on, so publish and mismatch compose as populated.
    fileprivate static let samplePublished: MailE2eKeys? = {
        let json = """
        {"userId":"p1",
         "encryption":{"publicKey":"\(samplePublicKey)",
           "fingerprint":"\(sampleFingerprint)","keyId":"7C0FB624FA5D7109"},
         "signing":null,
         "publishedAt":"2026-08-02T10:05:00Z","updatedAt":"2026-08-02T10:05:00Z"}
        """
        return try? JSONDecoder().decode(MailE2eKeys.self, from: Data(json.utf8))
    }()

    /// One found recipient and one refusal, so the lookup frame photographs
    /// both branches the screen can show — the fingerprint to compare, and the
    /// directory's own reason where there is no key. Decoded from the route's
    /// shape, not hand-built.
    fileprivate static let sampleLookup: [MailE2eLookupEntry] = {
        let json = """
        [{"status":"found","address":"priya@havenworks.example",
          "signing":{"publicKey":"bm90LXJlYWwtc2lnbmluZy1wdWJ8",
            "fingerprint":"9E:41:77:CA:0B:5D:2F:86:A3:C1:F0:64:18:9B:7A:DD:0C:E7:35:52:BF:81:6E:40:2D:A9:15:C3:FE:07:84:B1",
            "keyId":"9E413552BF816E40"},
          "reason":null,"domain":"havenworks.example"},
         {"status":"unavailable","address":"tomas@fieldkit.example",
          "signing":null,"reason":"no-key-published","domain":"fieldkit.example"}]
        """
        return (try? JSONDecoder().decode([MailE2eLookupEntry].self, from: Data(json.utf8))) ?? []
    }()

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
         \(encrypted ? "\"e2eEnvelope\":{\"v\":2,\"alg\":\"X25519+HKDF-SHA256+AES-256-GCM (multi-recipient)\",\"iv\":\"c2VlZC1pdg==\",\"ct\":\"c2VlZC1jdA==\",\"tag\":\"c2VlZC10YWc=\",\"recipients\":[{\"epk\":\"c2VlZC1lcGs=\",\"wrapIv\":\"c2VlZC13aXY=\",\"wrappedKey\":\"c2VlZC13aw==\",\"wrapTag\":\"c2VlZC13dA==\"}]}," : "")
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
                if name == "mail-subs-empty" {
                    // The honest empty read: the server ANSWERED the inbox with
                    // nothing. "Unread" and "empty" are different sentences on
                    // every screen, and the frame proving the empty branch must
                    // not stand in the failed one.
                    var empty = MailInboxResponse()
                    empty.success = true
                    empty.box = "inbox"
                    empty.total = 0
                    empty.messages = []
                    await MailStore.shared.putPage(.inbox, nil, empty, replace: true)
                    cached = true
                    return
                }
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
                secondary(ThreadView(vm: model, e2e: e2e, id: "t-m1",
                                     isThread: true, onBack: {}, onReply: { _, _ in }))
                )
            case "mail-thread-encrypted":
                return AnyView(
                secondary(ThreadView(vm: model, e2e: e2e, id: "t-m9",
                                     isThread: true, onBack: {}, onReply: { _, _ in }))
                )
            case "mail-compose":
                return AnyView(
                secondary(ComposeView(vm: model, account: profile, e2e: e2e,
                                      replyId: "", mode: "reply", onBack: {}))
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
                                           onNotes: {}, onTasks: {}, onPrefs: {},
                                           onAddresses: {}, onConnections: {},
                                           onSubscriptions: {},
                                           onProfile: {}, onPrivacy: {}, onEncryption: {},
                                           onHelp: {}, onSignOut: {}))
                )
            // The eight account-record frames. One document seeds them all;
            // each frame's settled marker is a value that exists only once
            // that document has landed (the walk waits for it).
            case "mail-notes":
                return AnyView(secondary(NotesView(vm: profile, onBack: {})))
            case "mail-tasks":
                return AnyView(secondary(TasksView(vm: profile, onBack: {})))
            case "mail-settings-prefs":
                return AnyView(secondary(MailPrefsView(vm: profile, onBack: {})))
            case "mail-addresses":
                return AnyView(secondary(AddressesView(vm: profile, onBack: {})))
            case "account-connections":
                return AnyView(secondary(ConnectionsView(vm: profile, onBack: {}, onOpen: { _ in })))
            // The per-app detail frames, both over the ONE seeded record: the
            // linked frame names an app the document lists, the unlinked one
            // asks about an app it does not — which IS the server's answer for
            // that name, so "not connected" is a true read, not a fake-empty
            // profile (Android's host draws the same two).
            case "account-connection-detail":
                return AnyView(secondary(ConnectionDetailView(vm: profile, product: "Northwind SSO",
                                                              onBack: {}, onSupport: {})))
            case "account-connection-unknown":
                return AnyView(secondary(ConnectionDetailView(vm: profile, product: "Stranger App",
                                                              onBack: {}, onSupport: {})))
            // The subscriptions band. The populated frame photographs the two
            // detected senders seeded in the cache; the empty one photographs
            // the screen's own no-mail sentence over an honest empty read.
            case "mail-subs-list":
                return AnyView(derived(SubscriptionsView(vm: model, onBack: {})))
            case "mail-subs-empty":
                return AnyView(derived(SubscriptionsView(vm: model, onBack: {})))
            case "account-profile":
                return AnyView(secondary(ProfileEditView(vm: profile, onBack: {})))
            case "account-privacy":
                return AnyView(secondary(PrivacyView(vm: profile, onBack: {},
                                                     onProfile: {}, onPhone: {},
                                                     onAddresses: {}, onPrefs: {},
                                                     onSupport: {})))
            case "mail-support":
                return AnyView(secondary(SupportView(onBack: {}, onForgot: {},
                                                     onPhone: {}, onAddresses: {})))
            // The encryption band (Phase 3). Nine frames over the seeds above:
            // two of the three states of the hub, and one screen per action.
            // The settled markers are the sentences themselves — each exists
            // only once the right state has been seeded, so a frame that
            // photographed the wrong state fails the walk rather than the eye.
            case "e2e-status-none":
                return AnyView(secondary(E2eStatusScreen(vm: e2e, onBack: {}, onSetup: {},
                                                         onUnlock: {}, onDetails: {}, onPublish: {},
                                                         onChangePassphrase: {}, onRemove: {},
                                                         onRecipients: {})))
            case "e2e-status-unlocked":
                return AnyView(secondary(E2eStatusScreen(vm: e2e, onBack: {}, onSetup: {},
                                                         onUnlock: {}, onDetails: {}, onPublish: {},
                                                         onChangePassphrase: {}, onRemove: {},
                                                         onRecipients: {})))
            case "e2e-setup":
                return AnyView(secondary(E2eSetupScreen(vm: e2e, onBack: {})))
            case "e2e-unlock":
                return AnyView(secondary(E2eUnlockScreen(vm: e2e, onBack: {})))
            case "e2e-details":
                return AnyView(secondary(E2eDetailsScreen(vm: e2e, onBack: {})))
            case "e2e-publish":
                return AnyView(secondary(E2ePublishScreen(vm: e2e, onBack: {})))
            case "e2e-passphrase":
                return AnyView(secondary(E2ePassphraseScreen(vm: e2e, onBack: {})))
            case "e2e-remove":
                return AnyView(secondary(E2eRemoveScreen(vm: e2e, onBack: {})))
            case "e2e-recipients":
                return AnyView(secondary(E2eRecipientsScreen(vm: e2e, onBack: {}, onCompose: {})))
            case "mail-screener":
                return AnyView(
                secondary(ScreenerView(vm: model, onBack: {}, onOpenQueue: {}))
                )
            // The advanced-search sheet, drawn by the live component with the
            // scope numbers the seed puts in state: five loaded, forty in the
            // box. The line is the thing under test -- a sheet that promised a
            // whole-box search would photograph a lie.
            case "mail-adv-search":
                return AnyView(
                sheetFrame(AdvancedSearchSheet(
                    state: model.state,
                    onApply: { _, _, _, _, _, _ in },
                    onClear: {}, onDone: {}))
                )
            // The filing undo banner over the live list: a read-only walk
            // cannot archive anything, so the words a real archive produces
            // are drawn by the same GMUndoBanner the live screen raises.
            case "mail-undo":
                return AnyView(
                VStack(spacing: 0) {
                    GMUndoBanner(note: "Archived 2 messages", marker: "mail-undo",
                                 onUndo: {}, onDone: {})
                    listFrame(rows: Self.rows, loading: false, failed: false,
                              noMailbox: false, box: .inbox)
                }
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
        "mail-subs-list", "mail-subs-empty",
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
        case "mail-adv-search":
            // The scope line's numbers: the sheet says what it ran over.
            seedForSnapshot { s in
                s.total = 40
                s.advScope = 5
            }
        case "mail-undo":
            // The banner's words: exactly what a real archive of two raises.
            seedForSnapshot { s in
                s.undoIds = ["m1", "m2"]
                s.undoInverse = .unarchive
                s.undoNote = "Archived 2 messages"
            }
        default:
            break
        }
    }
}
