import Foundation

/// Mailbox sector — the GrapMail API surface, in Swift.
///
/// Every type mirrors a `c.json(...)` in
/// `grapsee-backend/services/shared/src/routes/grapmail*.ts`, read from the
/// handler rather than inferred from another client, and the handler's line is
/// on the type it describes so drift is visible instead of assumed.
///
/// ── WHY EVERY DTO IS `Decodable` AND HAND-WRITTEN ───────────────────────────
/// Swift's synthesized `Decodable` throws `keyNotFound` for a key the response
/// omits even when the property has a `= default` — a default is not consulted
/// during decoding. This backend omits optional-in-practice fields per endpoint,
/// so a synthesized conformance fails the whole response on one absent key,
/// which is the bug that took out sign-in and every security screen in this
/// app's first month. Each type therefore decodes field by field through
/// `lenient` / `lenientOpt` (`APIClient.swift`), where an absent key becomes the
/// default and a key of the wrong type is logged rather than swallowed.
///
/// They are `Decodable`, never `Codable`: a `CodingKeys` case with no matching
/// stored property (an alias like `colour`) breaks the synthesized `encode(to:)`
/// for a type that only ever reads.
///
/// ── WHY SO MANY FIELDS ARE OPTIONAL ─────────────────────────────────────────
/// A defaulted non-null field invents a value the server never sent, and on a
/// mailbox an invented value is something the user acts on: a message with no
/// `createdAt` shown as a time, a sender with no name shown as the wrong person.
/// So anything the API may omit is optional and the screen decides what unknown
/// looks like. The exception is the flag columns the schema declares `NOT NULL`
/// — `isRead`, `isStarred`, `hasAttachments`, `isE2eEncrypted` — which
/// `publicRow` always emits; those default to `false`, matching the database.

// ── rows ─────────────────────────────────────────────────────────────────────

/// `publicRow.from` — `grapmail.ts:583`. Both halves are separately optional.
struct MailPerson: Decodable {
    var address: String?
    var name: String?

    /// A blank person. Swift gives no memberwise init to a type with a
    /// hand-written `init(from:)`, and the snapshot host and the cache both need
    /// to build one rather than reach for a decoding they do not have.
    init() {}

    private enum CodingKeys: String, CodingKey { case address, name }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        address = c.lenientOpt(.address)
        name = c.lenientOpt(.name)
    }

    /** What a row should show as its sender: the name if there is one, the
     *  address if there is not, and nothing invented when there is neither. */
    var display: String { name?.nilIfBlank() ?? address ?? "" }
}

/// `publicRow` — `grapmail.ts:579-607`. The list shape; carries no body by
/// design, because a listing renders previews and never a message.
struct MailMessage: Decodable, Identifiable {
    var id = ""
    var threadId: String?
    /// `inbound` or `outbound`.
    var direction: String?
    /// Delivery state: `queued`, `sent`, `failed`, … Never rendered as a date.
    var status: String?
    var from: MailPerson?
    var to: [String] = []
    var cc: [String] = []
    /// nil is real: a message can legitimately have no subject.
    var subject: String?
    var snippet: String?
    var isRead = false
    var isStarred = false
    var hasAttachments = false
    /// The sender's List-Unsubscribe offer — `grapmail.ts:589`. The server
    /// sends the PARSED record, not a header string; see `MailUnsubscribe`.
    var unsubscribe: MailUnsubscribe?
    var isE2eEncrypted = false
    /// ISO instant. nil means there is no trustworthy time to display.
    var createdAt: String?

    /// A blank row. Swift gives no memberwise init to a type with a
    /// hand-written `init(from:)`, and the cache needs to build a patched copy of
    /// a row it already holds.
    init() {}

    private enum CodingKeys: String, CodingKey {
        case id, threadId, direction, status, from, to, cc, subject, snippet,
             isRead, isStarred, hasAttachments, unsubscribe, isE2eEncrypted, createdAt
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.lenient(.id, "")
        threadId = c.lenientOpt(.threadId)
        direction = c.lenientOpt(.direction)
        status = c.lenientOpt(.status)
        from = c.lenientOpt(.from)
        to = c.lenient(.to, [])
        cc = c.lenient(.cc, [])
        subject = c.lenientOpt(.subject)
        snippet = c.lenientOpt(.snippet)
        isRead = c.lenient(.isRead, false)
        isStarred = c.lenient(.isStarred, false)
        hasAttachments = c.lenient(.hasAttachments, false)
        unsubscribe = try? c.decodeIfPresent(MailUnsubscribe.self, forKey: .unsubscribe)
        isE2eEncrypted = c.lenient(.isE2eEncrypted, false)
        createdAt = c.lenientOpt(.createdAt)
    }

    /// The line the row shows where a subject is absent. Not a guess about the
    /// message — a statement that it has no subject.
    var subjectOrNone: String { subject?.nilIfBlank() ?? "(no subject)" }
}

/// `fullRow` — `grapmail.ts:609-623`, plus the attachment rows the single-
/// message route bolts on (`grapmail.ts:769-774`).
struct MailMessageFull: Decodable, Identifiable {
    var id = ""

    /// See `MailMessage.init()` — a hand-decoded type has no memberwise init,
    /// and a caller that must produce a value on decode failure needs one.
    init() {}
    var threadId: String?
    var direction: String?
    var status: String?
    var from: MailPerson?
    var to: [String] = []
    var cc: [String] = []
    var subject: String?
    var snippet: String?
    var isRead = false
    var isStarred = false
    var hasAttachments = false
    var unsubscribe: MailUnsubscribe?
    var isE2eEncrypted = false
    var createdAt: String?
    var bodyText: String?
    var bodyHtml: String?
    var inReplyTo: String?
    var sizeBytes: Int?
    /// The sealed envelope, opaque to the server and returned byte for byte.
    /// Nothing here decrypts it; it is carried so "is this encrypted" can be
    /// answered with the proof rather than the flag alone.
    var e2eEnvelope: String?
    var attachments: [MailAttachment] = []

    private enum CodingKeys: String, CodingKey {
        case id, threadId, direction, status, from, to, cc, subject, snippet,
             isRead, isStarred, hasAttachments, unsubscribe, isE2eEncrypted,
             createdAt, bodyText, bodyHtml, inReplyTo, sizeBytes, e2eEnvelope,
             attachments
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.lenient(.id, "")
        threadId = c.lenientOpt(.threadId)
        direction = c.lenientOpt(.direction)
        status = c.lenientOpt(.status)
        from = c.lenientOpt(.from)
        to = c.lenient(.to, [])
        cc = c.lenient(.cc, [])
        subject = c.lenientOpt(.subject)
        snippet = c.lenientOpt(.snippet)
        isRead = c.lenient(.isRead, false)
        isStarred = c.lenient(.isStarred, false)
        hasAttachments = c.lenient(.hasAttachments, false)
        unsubscribe = try? c.decodeIfPresent(MailUnsubscribe.self, forKey: .unsubscribe)
        isE2eEncrypted = c.lenient(.isE2eEncrypted, false)
        createdAt = c.lenientOpt(.createdAt)
        bodyText = c.lenientOpt(.bodyText)
        bodyHtml = c.lenientOpt(.bodyHtml)
        inReplyTo = c.lenientOpt(.inReplyTo)
        sizeBytes = c.lenientOpt(.sizeBytes)
        // The envelope is a JSON OBJECT on the wire — the server stores it
        // verbatim and hands it back the same shape Android keeps as a
        // `JsonObject`. Decoding it as a String would nil it on every real
        // message and leave the decrypt UI with nothing to open, so the
        // object is re-serialised for the crypto layer instead.
        e2eEnvelope = Self.decodeEnvelope(c, forKey: .e2eEnvelope)
        attachments = c.lenient(.attachments, [])
    }

    private static func decodeEnvelope<K: CodingKey>(_ c: KeyedDecodingContainer<K>,
                                                     forKey key: K) -> String? {
        guard c.contains(key) else { return nil }
        if let s = try? c.decode(String.self, forKey: key) { return s }
        guard let obj = try? c.decode(JSONValue.self, forKey: key),
              case .object(let dict) = obj,
              let data = try? JSONSerialization.data(withJSONObject: dict) else { return nil }
        return String(decoding: data, as: UTF8.self)
    }

    /// Any JSON, decoded losslessly so an object envelope survives the trip to
    /// the string the crypto layer parses. A number or bool under the key is
    /// not an envelope and stays nil — the flag alone then tells the truth
    /// about an encrypted message whose body never arrived.
    private enum JSONValue: Decodable {
        case object([String: Any]), array([Any]), string(String),
        number(Double), bool(Bool), null
        init(from decoder: Decoder) throws {
            let c = try decoder.singleValueContainer()
            if c.decodeNil() { self = .null }
            else if let b = try? c.decode(Bool.self) { self = .bool(b) }
            else if let n = try? c.decode(Double.self) { self = .number(n) }
            else if let s = try? c.decode(String.self) { self = .string(s) }
            else if let a = try? c.decode([JSONValue].self) { self = .array(a.map(Self.raw)) }
            else if let o = try? c.decode([String: JSONValue].self) { self = .object(o.mapValues(Self.raw)) }
            else { throw DecodingError.dataCorruptedError(in: c, debugDescription: "Not JSON") }
        }
        private static func raw(_ v: JSONValue) -> Any {
            switch v {
            case .object(let d): return d
            case .array(let a): return a
            case .string(let s): return s
            case .number(let n): return n
            case .bool(let b): return b
            case .null: return NSNull()
            }
        }
    }

    /// The thread view needs the same rule as the list: a message with no
    /// subject is labelled, not shown as a blank line a reader cannot tell apart
    /// from a rendering fault.
    var subjectOrNone: String { subject?.nilIfBlank() ?? "(no subject)" }
}

/// `grapmail_attachments` rows — metadata only. `storageKey` is deliberately
/// **not** decoded: it is a server-side object key, and a field that can be
/// turned into a path is a field that should never reach the UI.
struct MailAttachment: Decodable, Identifiable {
    var id = ""
    var messageId: String?
    var name: String?
    var mimeType: String?
    var sizeBytes: Int?
    var createdAt: String?

    private enum CodingKeys: String, CodingKey {
        case id, messageId, name, mimeType, sizeBytes, size, createdAt
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.lenient(.id, "")
        messageId = c.lenientOpt(.messageId)
        name = c.lenientOpt(.name)
        mimeType = c.lenientOpt(.mimeType)
        sizeBytes = c.lenientOpt(.sizeBytes) ?? c.lenientOpt(.size)
        createdAt = c.lenientOpt(.createdAt)
    }
}

// ── envelopes ────────────────────────────────────────────────────────────────

/// `GET /inbox` — `grapmail.ts:729-737`.
struct MailInboxResponse: Decodable {
    /// See `MailMessage.init()` — a hand-decoded type has no memberwise init, and
    /// the snapshot host builds a page to load into the cache.
    init() {}

    var success = false
    var box: String?
    var page = 1
    var pageSize = 25
    /// Rows in the box, not rows on this page — the paging maths needs the total.
    var total = 0
    var messages: [MailMessage] = []
    /// The term the server actually searched, when one was sent.
    var q: String?
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey {
        case success, box, page, pageSize, total, messages, q, error, code
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        box = c.lenientOpt(.box)
        page = c.lenient(.page, 1)
        pageSize = c.lenient(.pageSize, 25)
        total = c.lenient(.total, 0)
        messages = c.lenient(.messages, [])
        q = c.lenientOpt(.q)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
    }
}

/// `GET /message/:id` — `grapmail.ts:769`. Opening a message marks it read
/// server-side, so callers must not also send `read`: the second write is
/// redundant and races the first.
struct MailMessageResponse: Decodable {
    var success = false
    var message: MailMessageFull?

    private enum CodingKeys: String, CodingKey { case success, message }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        message = c.lenientOpt(.message)
    }
}

/// `GET /thread/:threadId` — `grapmail.ts:786`.
struct MailThreadResponse: Decodable {
    var success = false
    var messages: [MailMessageFull] = []

    private enum CodingKeys: String, CodingKey { case success, messages }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        messages = c.lenient(.messages, [])
    }
}

/// `GET /message/:id/body` — the reader's render body, served by the web app's
/// same-origin route (`src/app/api/grapsee/grapmail/message/[id]/body/route.ts`),
/// not by the backend: the raw HTML part the message row carries is sanitized
/// server-side — with `sanitizeEmailHtml`, the same strict untrusted-email
/// profile the web's SafeEmailBody renders through — before it is allowed near
/// a renderer.
///
/// `bodyHtml` is therefore the ONLY string this app may put in a WebView. The
/// raw `message.bodyHtml` from the row is never a render input: no sanitiser
/// ships in this app, and the correct one is not a regex. Remote images are
/// already neutralised in the response (`src` moved to
/// `data-grapmail-blocked-src`) unless `?images=1` accompanied the request —
/// `blockedImages` is the honest count of what was blocked, and `bodyText` is
/// the row's plain part echoed back for the no-HTML case, so a null `bodyHtml`
/// means "there is no formatted view", not "the field went missing".
struct MailBodyResponse: Decodable {
    var success = false
    var bodyHtml: String?
    var bodyText: String?
    var blockedImages = 0

    private enum CodingKeys: String, CodingKey { case success, bodyHtml, bodyText, blockedImages }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        bodyHtml = c.lenientOpt(.bodyHtml)
        bodyText = c.lenientOpt(.bodyText)
        blockedImages = c.lenient(.blockedImages, 0)
    }
}

/// `GET /me` — `grapmail.ts:632-638`. `status` is the **mailbox's** state, not
/// the account's; the two must not be shown as one fact.
/// A `404` with `code == "NO_MAILBOX"` is its own screen (`66-mail-no-mailbox`),
/// not an empty inbox.
struct MailMeResponse: Decodable {
    var success = false
    var address: String?
    var handle: String?
    var status: String?
    var name: String?
    var memberSince: String?
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey {
        case success, address, handle, status, name, memberSince, error, code
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        address = c.lenientOpt(.address)
        handle = c.lenientOpt(.handle)
        status = c.lenientOpt(.status)
        name = c.lenientOpt(.name)
        memberSince = c.lenientOpt(.memberSince)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
    }

    var noMailbox: Bool { code == "NO_MAILBOX" }
}

/// `GET /stats` — `grapmail.ts:664-668`.
///
/// Exactly five boxes are reported. Starred, Archive and All Mail are not, so
/// they carry no badge — the same call the web client makes
/// (`src/app/(mail)/mail/page.tsx:2306`). A `nil` here means the server did not
/// answer, and the UI must draw nothing rather than `0`: `0` is a claim that the
/// folder is empty.
struct MailStatsResponse: Decodable {
    var success = false
    var inbox: MailInboxStats?
    var sent: Int?
    var drafts: Int?
    var spam: Int?
    var trash: Int?
    /// Counted by the same `boxCount` that builds the listing beside each badge
    /// (`grapmail.ts:666-688`). Null on a deployment that has not answered
    /// yet — and the drawer reads that null as "not offered here", never as 0.
    var starred: Int?
    var archive: Int?
    /// `?box=others` — everything still in the account (`grapmail-box-terms.ts:78`).
    var others: Int?
    /// The count of `?box=snoozed`. Null is read as two different things by
    /// the drawer, and both are true of a null: nobody was asked, or this
    /// deployment has no snoozed list. Neither licenses a `0`.
    var snoozed: Int?

    private enum CodingKeys: String, CodingKey {
        case success, inbox, sent, drafts, spam, trash, starred, archive, others, snoozed
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        inbox = c.lenientOpt(.inbox)
        sent = c.lenientOpt(.sent)
        drafts = c.lenientOpt(.drafts)
        spam = c.lenientOpt(.spam)
        trash = c.lenientOpt(.trash)
        starred = c.lenientOpt(.starred)
        archive = c.lenientOpt(.archive)
        others = c.lenientOpt(.others)
        snoozed = c.lenientOpt(.snoozed)
    }
}

struct MailInboxStats: Decodable {
    var total: Int?
    var unread: Int?

    private enum CodingKeys: String, CodingKey { case total, unread }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        total = c.lenientOpt(.total)
        unread = c.lenientOpt(.unread)
    }
}

/// `grapmail_labels` rows — `grapmail-labels.ts:93-96`.
struct MailLabel: Decodable, Identifiable {
    var id = ""
    var name = ""

    /// See `MailPerson.init()` — the same reason, the same consequence.
    init() {}
    /// The server's palette KEY — `slate`, `red`, `amber`, `green`, `teal`,
    /// `blue`, `violet`, `pink` — never a hex value. Decoded from `colour` because
    /// that is the key the route writes. Resolved by `labelSwatch(_:)`; the hex
    /// parse this used to go through could never succeed, so every label drew the
    /// same fallback.
    var color: String?
    /// Unread count for this label; nil means unknown, not zero.
    var unread: Int?

    private enum CodingKeys: String, CodingKey {
        case id, name, color = "colour", unread
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.lenient(.id, "")
        name = c.lenient(.name, "")
        color = c.lenientOpt(.color)
        unread = c.lenientOpt(.unread)
    }
}

struct MailLabelsResponse: Decodable {
    var success = false
    var labels: [MailLabel] = []
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey { case success, labels, error, code }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        labels = c.lenient(.labels, [])
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
    }
}

struct MailLabelResponse: Decodable {
    var success = false
    var label: MailLabel?
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey { case success, label, error, code }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        label = c.lenientOpt(.label)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
    }
}

/// One (message, label) pair — `GET /labels/assignments`.
///
/// The route answers with every pair the caller has in one response, so a page of
/// twenty messages costs one request rather than twenty (`grapmail-labels.ts:21-27`).
struct MailAssignment: Decodable {
    var messageId = ""
    var labelId = ""

    init() {}

    private enum CodingKeys: String, CodingKey { case messageId, labelId }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        messageId = c.lenient(.messageId, "")
        labelId = c.lenient(.labelId, "")
    }
}

struct MailAssignmentsResponse: Decodable {
    var success = false
    var assignments: [MailAssignment] = []
    var error: String?
    var code: String?

    init() {}

    private enum CodingKeys: String, CodingKey { case success, assignments, error, code }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        assignments = c.lenient(.assignments, [])
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
    }
}

/// How a label stands against the current selection in the Label sheet.
///
/// Four states, because two would be wrong twice: `Mixed` is real (three messages
/// selected, two carry the label) and drawing it as either extreme tells the user
/// to add or remove something they did not ask about; `Unknown` is the state before
/// the read answers and the state it stays in when the read fails — marking a label
/// then would assert a fact nobody was asked, the same class as a badge drawn `0`
/// because nobody was consulted.
enum LabelMark {
    case on, mixed, off, unknown
}

/// Whether the assignment read has answered. Kept apart from the map so “read, and
/// nothing is labelled” is never confused with “never asked”.
enum AssignmentsRead {
    case notRead, reading, read, failed
}

/// `GET /aliases` — a second address delivering into this mailbox. This, not a
/// fake multi-account switcher, is what the reference's account chip can
/// honestly show.
///
/// `id` and `isActive` used to live here with defaults, and both were lies with
/// consequences: `GET /aliases` answers three columns — label, address,
/// createdAt (`grapmail-aliases.ts:131-152`) — so a cached list that defaulted
/// `isActive` to true drew a mailbox whose aliases were all asserted live (and
/// the "paused" tag could therefore never appear), and every row carried the
/// same empty `id`, which is a duplicate identity in a list keyed by one.
/// `address` is the identity here: the DELETE route takes an address, not an id
/// (`:277`). Paused state comes from the profile's alias rows, where the
/// account store really does answer `isActive`.
struct MailAlias: Decodable, Identifiable {
    var label = ""
    var address = ""
    var createdAt: String?
    var id: String { address }

    private enum CodingKeys: String, CodingKey { case label, address, createdAt }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        label = c.lenient(.label, "")
        address = c.lenient(.address, "")
        createdAt = c.lenientOpt(.createdAt)
    }
    /// A blank row for the same reason `MailMessage.init()` exists: a
    /// hand-decoded type has no memberwise init, and callers need one.
    init() {}
}

struct MailAliasesResponse: Decodable {
    var success = false
    var aliases: [MailAlias] = []

    private enum CodingKeys: String, CodingKey { case success, aliases }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        aliases = c.lenient(.aliases, [])
    }
}

/// The body of `POST /grapmail/aliases` — one field, `label`
/// (`grapmail-aliases.ts:169` reads `body?.label`, slugifies it, and builds the
/// address at `:188`). There is deliberately no address field to fill in: the
/// server owns the domain and the handle, so this client cannot name the
/// mailbox an alias is created in.
struct MailAliasLabelRequest: Encodable { var label: String }

/// `POST /grapmail/aliases` — `grapmail-aliases.ts:155-275`. The three
/// refusals — duplicate, taken, limit — come back as `success: false` with a
/// sentence, and a 429 with `retryAfter` when the throttle trips.
struct MailAliasCreateResponse: Decodable {
    var success = false
    var alias: MailAlias?
    var message: String?
    var error: String?
    var code: String?
    var retryAfter: Int?

    private enum CodingKeys: String, CodingKey {
        case success, alias, message, error, code, retryAfter
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        alias = c.lenientOpt(.alias)
        message = c.lenientOpt(.message)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
        retryAfter = c.lenientOpt(.retryAfter)
    }
}

/// `DELETE /grapmail/aliases/:address` — `grapmail-aliases.ts:277-300`. A row
/// that was not the caller's is answered 404 "No such alias" rather than 403,
/// so this cannot be used to learn whether somebody else owns an address.
struct MailAliasDeleteResponse: Decodable {
    var success = false
    var address: String?
    var error: String?
    /// The gateway route answers one; the JSONL route may. Optional because
    /// an absent sentence is no answer, and the screen falls back to its own.
    var message: String?
    var retryAfter: Int?

    private enum CodingKeys: String, CodingKey { case success, address, error, message, retryAfter }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        address = c.lenientOpt(.address)
        error = c.lenientOpt(.error)
        message = c.lenientOpt(.message)
        retryAfter = c.lenientOpt(.retryAfter)
    }
}

/// `DELETE /grapmail/attachments/:id` — `grapmail-attachments.ts:412`. The
/// route is scoped to the caller's own rows, so an attachment id belonging to
/// another account is refused the same way as one that does not exist.
struct MailAttachmentDeleteResponse: Decodable {
    var success = false
    var id: String?
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey { case success, id, error, code }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        id = c.lenientOpt(.id)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
    }
}

// ── End-to-end encryption: wire types ──────────────────────────────────────────
//
// Field names are copied from the route answers, not invented: one published
// key carries the base64 SPKI DER plus the identifiers the SERVER derived from
// it, and a client that re-derived a fingerprint by its own rules would compare
// unequal to the one the web shows for the same key.

/// One published key, with the identifiers the server derives from it.
struct MailE2eKey: Decodable {
    /// base64 SPKI DER — X25519 for encryption, Ed25519 for signing.
    var publicKey = ""
    /// SHA-256 over the DER, uppercase hex, colon-separated.
    var fingerprint = ""
    /// The low-order 64 bits of the fingerprint, for list views and logs.
    var keyId = ""

    private enum CodingKeys: String, CodingKey { case publicKey, fingerprint, keyId }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        publicKey = c.lenient(.publicKey, "")
        fingerprint = c.lenient(.fingerprint, "")
        keyId = c.lenient(.keyId, "")
    }
    init() {}
}

struct MailE2eKeys: Decodable {
    var userId = ""
    var encryption: MailE2eKey?
    var signing: MailE2eKey?
    var publishedAt: String?
    var updatedAt: String?

    private enum CodingKeys: String, CodingKey {
        case userId, encryption, signing, publishedAt, updatedAt
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        userId = c.lenient(.userId, "")
        encryption = c.lenientOpt(.encryption)
        signing = c.lenientOpt(.signing)
        publishedAt = c.lenientOpt(.publishedAt)
        updatedAt = c.lenientOpt(.updatedAt)
    }
}

struct MailE2eKeysResponse: Decodable {
    var success = false
    var published = false
    var keys: MailE2eKeys?
    var error: String?
    var code: String?
    var detail: String?

    private enum CodingKeys: String, CodingKey {
        case success, published, keys, error, code, detail
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        published = c.lenient(.published, false)
        keys = c.lenientOpt(.keys)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
        detail = c.lenientOpt(.detail)
    }
}

/// `PUT /e2e/keys`. `discoverable` is part of the answer and not a flourish:
/// "your key is published" and "your key is published and senders can find it"
/// are different sentences, and an account whose handle could not be resolved
/// gets the first without the second.
struct MailE2ePublishResponse: Decodable {
    var success = false
    var keys: MailE2eKeys?
    var discoverable = false
    var error: String?
    var detail: String?

    private enum CodingKeys: String, CodingKey {
        case success, keys, discoverable, error, detail
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        keys = c.lenientOpt(.keys)
        discoverable = c.lenient(.discoverable, false)
        error = c.lenientOpt(.error)
        detail = c.lenientOpt(.detail)
    }
}

struct MailE2eUnpublishResponse: Decodable {
    var success = false
    var removed = false
    var error: String?
    var detail: String?

    private enum CodingKeys: String, CodingKey { case success, removed, error, detail }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        removed = c.lenient(.removed, false)
        error = c.lenientOpt(.error)
        detail = c.lenientOpt(.detail)
    }
}

/// Why one address could not be sealed to. A closed set, mirroring the
/// server's (`mailE2eApi.ts:50-54`), because the sentence a user needs differs
/// per reason — and anything this build does not know lands on `unknown` and is
/// said plainly rather than drawn as the wrong sentence.
enum MailE2eBlockReason: String {
    case notAnAddress = "not-an-address"
    case remoteDomain = "remote-domain"
    case noSuchAccount = "no-such-account"
    case noKeyPublished = "no-key-published"
    case unknown
}

/// Read through the closed set, never by trusting `reason` as a string the UI
/// branches on directly.
func mailE2eBlockReason(_ reason: String?) -> MailE2eBlockReason {
    MailE2eBlockReason(rawValue: reason ?? "") ?? .unknown
}

struct MailE2eBlocked: Decodable {
    var status = "unavailable"
    var address = ""
    var reason: String?
    var domain = ""
    var why: MailE2eBlockReason { mailE2eBlockReason(reason) }

    private enum CodingKeys: String, CodingKey { case status, address, reason, domain }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        status = c.lenient(.status, "unavailable")
        address = c.lenient(.address, "")
        reason = c.lenientOpt(.reason)
        domain = c.lenient(.domain, "")
    }
}

/// `POST /e2e/plan` — `grapmail-e2e.ts:194-229`.
struct MailE2ePlan: Decodable {
    /// `seal` or `plain`.
    var mode = ""
    var keys: [String] = []
    var recipients: [String] = []
    var blocked: [MailE2eBlocked] = []
    var canSeal: Bool { mode == "seal" }

    private enum CodingKeys: String, CodingKey { case mode, keys, recipients, blocked }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        mode = c.lenient(.mode, "")
        keys = c.lenient(.keys, [])
        recipients = c.lenient(.recipients, [])
        blocked = c.lenient(.blocked, [])
    }
}

struct MailE2ePlanResponse: Decodable {
    var success = false
    var plan: MailE2ePlan?
    var error: String?
    var detail: String?

    private enum CodingKeys: String, CodingKey { case success, plan, error, detail }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        plan = c.lenientOpt(.plan)
        error = c.lenientOpt(.error)
        detail = c.lenientOpt(.detail)
    }
}

/// One answer from `POST /e2e/lookup` — the sender's signing key, or why none.
struct MailE2eLookupEntry: Decodable {
    /// `found` or `unavailable`.
    var status = ""
    var address = ""
    var signing: MailE2eKey?
    var reason: String?
    var domain = ""
    var why: MailE2eBlockReason { mailE2eBlockReason(reason) }

    private enum CodingKeys: String, CodingKey {
        case status, address, signing, reason, domain
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        status = c.lenient(.status, "")
        address = c.lenient(.address, "")
        signing = c.lenientOpt(.signing)
        reason = c.lenientOpt(.reason)
        domain = c.lenient(.domain, "")
    }
}

struct MailE2eLookupResponse: Decodable {
    var success = false
    var results: [MailE2eLookupEntry] = []
    var error: String?
    var detail: String?

    private enum CodingKeys: String, CodingKey { case success, results, error, detail }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        results = c.lenient(.results, [])
        error = c.lenientOpt(.error)
        detail = c.lenientOpt(.detail)
    }
}

/// `PUT /e2e/keys` body. Only the public halves — see the block comment above.
struct MailE2ePublishRequest: Encodable {
    var encryptionPublicKey: String
    var signingPublicKey: String?
}

/// `POST /e2e/plan` and `POST /e2e/lookup` bodies.
struct MailE2eAddressesRequest: Encodable {
    var addresses: [String]
}

/// `POST /send` — `grapmail.ts:1136-1143`.
///
/// `status` is the truth about the send and `deliveryError` is the server's own
/// reason. The receipt screen shows both verbatim: a compose screen that says
/// "Sent" because the request returned 200 would be inventing a delivery the
/// queue has not confirmed.
struct MailSendResponse: Decodable {
    var success = false
    var id: String?
    var threadId: String?
    var status: String?
    var deliveryError: String?
    var error: String?
    var code: String?
    var message: String?

    private enum CodingKeys: String, CodingKey {
        case success, id, threadId, status, deliveryError, error, code, message
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        id = c.lenientOpt(.id)
        threadId = c.lenientOpt(.threadId)
        status = c.lenientOpt(.status)
        deliveryError = c.lenientOpt(.deliveryError)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
        message = c.lenientOpt(.message)
    }
}

struct MailDraftResponse: Decodable {
    var success = false
    var id: String?
    var threadId: String?
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey { case success, id, threadId, error, code }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        id = c.lenientOpt(.id)
        threadId = c.lenientOpt(.threadId)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
    }
}

/// `GET /screener`. Note `?box=screener` is the queue itself
/// (`grapmail-boxes.ts:57-66`) — this route only reads and writes the switch, so
/// the list still comes from `mailInbox` like any other box.
struct MailScreenerResponse: Decodable {
    var success = false
    var enabled: Bool?
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey { case success, enabled, error, code }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        enabled = c.lenientOpt(.enabled)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
    }
}

/// `POST /attachments` answers with the row it wrote. The id inside it is what a
/// later send must carry — an attachment the server never stored is a filename
/// the recipient cannot open, so the upload is only real once this comes back.
struct MailAttachmentResponse: Decodable {
    var success = false
    var attachment: MailAttachment?
    var error: String?
    var code: String?

    private enum CodingKeys: String, CodingKey { case success, attachment, error, code }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        attachment = c.lenientOpt(.attachment)
        error = c.lenientOpt(.error)
        code = c.lenientOpt(.code)
    }
}

// ── vocabularies ─────────────────────────────────────────────────────────────

/// The boxes the mail API accepts, from `grapmail-boxes.ts:43-68` plus
/// `EVERYTHING_BOX` at `:78`.
///
/// Two things a caller must not assume:
///  - `archive` and `others` are answered by the **archive** router, which
///    `next()`-falls through for every other box. Same URL, different handler.
///  - There is no `snoozed` box in what is deployed. `snooze` writes `snoozedUntil`
///    (`grapmail-archive.ts:171`) and the inbox hides those rows
///    (`grapmail.ts:719-726`), so a postponed message is not listable anywhere the
///    app can reach. It is NOT without a way back: the app offers Undo for exactly
///    the mail it just snoozed, because that is the only set it can name honestly,
///    and `MailAction.unsnooze` is wired to it on both platforms. A `?box=snoozed`
///    listing is written and tested in the backend's history (T6.1) but undeployed;
///    until it ships this enum stays as it is, and `scripts/mail-honesty.py` pins
///    the drawer against advertising a box nothing serves.
enum MailBox: String, CaseIterable, Identifiable {
    case inbox, starred, sent, drafts, archive, spam, trash, screener
    case others
    /// `?box=snoozed` -- implemented server-side (`grapmail-boxes.ts:178`) and
    /// offered by the drawer only when `/stats` answers the `snoozed` key, so a
    /// deployment without the box never shows a row that would refuse it.
    case snoozed

    var id: String { rawValue }

    var title: String {
        switch self {
        case .inbox:    return "Inbox"
        case .starred:  return "Starred"
        case .sent:     return "Sent"
        case .drafts:   return "Drafts"
        case .archive:  return "Archive"
        case .spam:     return "Spam"
        case .trash:    return "Trash"
        case .screener: return "Pending approval"
        case .others:   return "All Mail"
        case .snoozed:  return "Snoozed"
        }
    }

    /// The drawer's order — the boxes the reference lists, minus the ones the
    /// server cannot answer (see the README's honest-UI contract).
    static let drawerOrder: [MailBox] = [.inbox, .starred, .sent, .drafts, .archive, .spam, .trash, .others]

    /// The tab row. The reference draws Gmail's category tabs
    /// (`Primary / Social / Promotions / Updates`); no `category` column exists
    /// on the messages table, so the row carries the boxes that do. Starred
    /// leads the derived-people read because a starred message is the one the
    /// user chose to keep naming someone for.
    static let tabOrder: [MailBox] = [.inbox, .starred, .sent, .drafts, .archive]
}

/// The actions a message accepts. The action travels in the **path**
/// (`PATCH /grapmail/message/:id/:action`, `grapmail.ts:1253`), never the body.
/// The two halves come from `grapmail-boxes.ts:265-270` and
/// `grapmail-archive.ts:146-173`.
///
/// `trash` and `deletePermanent` are deliberately different: trashing is
/// reversible, `DELETE /message/:id` is not and is only valid from Trash. A UI
/// that maps both to one button would destroy mail under a label that promised
/// to file it.
enum MailAction: String {
    case read, unread, star, unstar, trash, restore
    case archive, unarchive, spam, unspam, snooze, unsnooze

    var apiValue: String { rawValue }

    /// The action that reverses this one, for the six filing actions the undo
    /// banner offers -- `PATCH /message/:id/:action` is reversible by the
    /// route's own shape, so the inverse is another call, not a local guess.
    /// Read/star return nil: their rows stay visible with their own toggle.
    /// Snooze has its dedicated undo path.
    func inverse() -> MailAction? {
        switch self {
        case .archive: return .unarchive
        case .unarchive: return .archive
        case .spam: return .unspam
        case .unspam: return .spam
        case .trash: return .restore
        case .restore: return .trash
        default: return nil
        }
    }

    /// The banner's past tense, in the same words for the same fact on both
    /// platforms: what the app just did to the selection.
    func pastLine(_ count: Int) -> String {
        let noun = count == 1 ? "1 message" : "\(count) messages"
        switch self {
        case .archive: return "Archived \(noun)"
        case .unarchive: return "Moved \(noun) back to the inbox"
        case .spam: return "Reported \(noun) as spam"
        case .unspam: return "Moved \(noun) out of Spam"
        case .trash: return "Moved \(noun) to Trash"
        case .restore: return "Restored \(noun)"
        default: return noun
        }
    }
}

/// Sort options. The server orders by UID descending and nothing else
/// (`grapmail-boxes.ts:34-39`), so everything below `.newest` is applied by the
/// client to the **loaded** pages — which the sort sheet states out loud rather
/// than implies.
enum MailSort: String, CaseIterable, Identifiable {
    case newest, oldest, senderAsc, senderDesc, subjectAsc, subjectDesc
    var id: String { rawValue }

    var title: String {
        switch self {
        case .newest:      return "Newest first"
        case .oldest:      return "Oldest first"
        case .senderAsc:   return "Sender (A → Z)"
        case .senderDesc:  return "Sender (Z → A)"
        case .subjectAsc:  return "Subject (A → Z)"
        case .subjectDesc: return "Subject (Z → A)"
        }
    }

    /// True when the ordering is the server's own and therefore covers the whole
    /// box rather than only what has been fetched.
    var isServerSide: Bool { self == .newest }
}

/// The filters the API can actually answer.
enum MailFilter: String, CaseIterable, Identifiable {
    case all, unread
    var id: String { rawValue }
    var title: String { self == .all ? "All" : "Unread" }
}

// ── request bodies ───────────────────────────────────────────────────────────

/// A JSON tree carried as text and re-emitted as a real JSON value on encode.
/// The sealed envelope lives in the compose model as the exact string the
/// encryptor produced (byte-stable, testable, never rebuilt field by field),
/// but the wire is not allowed to see it as a string: `grapmail-e2e.ts:129-130`
/// returns undefined for any `e2eEnvelope` where `typeof raw !== 'object'`,
/// and the web client — the existing contract — sends the object itself
/// (`page.tsx:1846` `e2eEnvelope: seal.envelope`). A string on the wire is a
/// dropped envelope plus a 400 for the blank body the sealed send
/// deliberately leaves behind. This type is the one place the text form is
/// allowed to become JSON again.
enum RawJSONValue: Encodable {
    case object([String: RawJSONValue]), array([RawJSONValue]), string(String),
    number(Double), bool(Bool), null

    func encode(to encoder: Encoder) throws {
        var c = encoder.singleValueContainer()
        switch self {
        case .object(let d): try c.encode(d)
        case .array(let a): try c.encode(a)
        case .string(let s): try c.encode(s)
        case .number(let n): try c.encode(n)
        case .bool(let b): try c.encode(b)
        case .null: try c.encodeNil()
        }
    }
}

struct RawJSON: Encodable {
    private let value: RawJSONValue

    /// nil when the text is not a JSON object: an unparseable envelope must
    /// never degrade into a plaintext send, so the caller checks the result
    /// and refuses the send outright.
    init?(object jsonText: String) {
        guard let data = jsonText.data(using: .utf8),
              case let .object(d)? = RawJSON.convert(jsonSerialization: data),
              !d.isEmpty else { return nil }
        value = .object(d)
    }

    private static func convert(jsonSerialization data: Data) -> RawJSONValue? {
        (try? JSONSerialization.jsonObject(with: data)).flatMap(convert(_:))
    }

    private static func convert(_ any: Any) -> RawJSONValue? {
        if any is NSNull { return .null }
        if let s = any as? String { return .string(s) }
        if let n = any as? NSNumber {
            // JSON booleans arrive as __NSCFBoolean; every NSNumber answers to
            // as? Bool, so the CF type is the only honest discriminator.
            if CFGetTypeID(n) == CFBooleanGetTypeID() { return .bool(n.boolValue) }
            return .number(n.doubleValue)
        }
        if let a = any as? [Any] {
            let items = a.compactMap(convert(_:))
            return items.count == a.count ? .array(items) : nil
        }
        if let d = any as? [String: Any] {
            var out: [String: RawJSONValue] = [:]
            for (k, v) in d { guard let cv = convert(v) else { return nil }; out[k] = cv }
            return .object(out)
        }
        return nil
    }

    func encode(to encoder: Encoder) throws { try value.encode(to: encoder) }
}

/// Field names are the keys the handlers destructure, not the app's vocabulary.
/// `MailLabelCreateRequest.colour` is spelled the way `grapmail-labels.ts:105`
/// reads it: sending `color` creates the label with no colour and returns 200,
/// so the mistake is invisible.
struct MailSendRequest: Encodable {
    var to: [String] = []
    var cc: [String] = []
    var bcc: [String] = []
    var subject: String = ""
    var bodyText: String = ""
    /// Explicit `= nil`, not a bare `String?`: compose builds this request by
    /// label and leaves the HTML and threading fields out. A memberwise
    /// parameter without a usable default turns that into a compile error the
    /// day the call site is written.
    var bodyHtml: String? = nil
    /**
     * Sent for reply threading. `grapmail.ts:851-900` is confirmed to read
     * to/cc/bcc/subject/bodyText/bodyHtml; this is the one key compose needs
     * that was not in that window, so it is re-checked against the handler when
     * the gateway is back — if the route ignores it, replies lose their
     * In-Reply-To and the thread view shows them as new messages.
     */
    var inReplyTo: String? = nil
    /// The sealed envelope, when the message is encrypted. `grapmail.ts:933-955`
    /// sanitises it and stores the notice text in place of a body — the server
    /// never sees plaintext, and this client never sends the body twice: with
    /// an envelope present, `bodyText` is the empty string the route accepts.
    /// The type is an OBJECT on the wire, never a quoted string:
    /// `sanitiseE2eEnvelope` (grapmail-e2e.ts:129-130) drops anything that is
    /// not `typeof raw === 'object'`, and the dropped envelope then trips the
    /// route's `!bodyText && !bodyHtml && !e2eEnvelope` refusal — the web's
    /// `e2eEnvelope: seal.envelope` is the contract this mirrors.
    var e2eEnvelope: RawJSON? = nil
    var attachmentIds: [String] = []
}

struct MailDraftRequest: Encodable {
    var to: [String] = []
    var cc: [String] = []
    var subject: String = ""
    var bodyText: String = ""
    var inReplyTo: String? = nil
    /// The draft row this save updates, when there is one. Absent on a first
    /// save (the route inserts), present on every autosave after it (the route
    /// updates that owned draft in place instead of inserting a twin). An id
    /// that no longer resolves falls back to an insert and the response's `id`
    /// replaces the stale one — the autosave loop's own recovery.
    var id: String? = nil
}

struct MailSnoozeRequest: Encodable { var until: String }
struct MailLabelCreateRequest: Encodable { var name: String; var colour: String? }
struct MailLabelAssignRequest: Encodable { var labelId: String }
struct MailScreenerEnableRequest: Encodable { var enabled: Bool }

/// What the screener does with one sender once the user has decided. The four
/// values are the server's own set — `DECISIONS` in
/// `mail-service/src/routes/screener.ts:60` — and `done` is a word this type
/// carries rather than a suffix a call site appends, because "Approve" + "d" and
/// "Keep waiting" + "d" are not both English.
enum MailDecision: String, CaseIterable, Identifiable {
    case approved, muted, later, blocked

    var apiValue: String { rawValue }
    var id: String { rawValue }

    var title: String {
        switch self {
        case .approved: return "Approve"
        case .muted: return "Mute"
        case .later: return "Keep waiting"
        case .blocked: return "Block"
        }
    }

    var done: String {
        switch self {
        case .approved: return "Approved"
        case .muted: return "Muted"
        case .later: return "Kept waiting"
        case .blocked: return "Blocked"
        }
    }

    var note: String {
        switch self {
        case .approved: return "Their future mail goes straight to the inbox."
        case .muted: return "It arrives, but quietly — held out of the unread count."
        case .later: return "Nothing is decided. It stays in this queue."
        case .blocked: return "Future mail from them is refused at the door."
        }
    }
}

/// How far a decision reaches. `SCOPES` in the same file. Domain is the one that
/// can go wrong quietly, so its `warning` is shown before the choice is made.
enum MailSenderScope: String, CaseIterable, Identifiable {
    case address, domain

    var apiValue: String { rawValue }
    var id: String { rawValue }

    var title: String {
        self == .address ? "Just this address" : "The whole domain"
    }

    var warning: String {
        self == .domain
            ? "Applies to every address at this domain, including ones created later."
            : ""
    }
}

/// `PUT /screener/decision` body: three strings, validated upstream, not here.
struct MailScreenerDecisionRequest: Encodable {
    var sender: String
    var scope: String
    var decision: String
}

struct MailScreenerDecisionRecord: Decodable {
    var sender = ""
    var scope = ""
    var decision = ""
    var decidedAt: String?

    private enum CodingKeys: String, CodingKey { case sender, scope, decision, decidedAt }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        sender = c.lenient(.sender, "")
        scope = c.lenient(.scope, "")
        decision = c.lenient(.decision, "")
        decidedAt = c.lenientOpt(.decidedAt)
    }
}

/// `PUT` answers `{ success, decision: {…} }`; `DELETE` answers
/// `{ success, removed }`. `removed: false` is a 200 by design — the caller's
/// intent, "no decision for this sender", holds either way — so the app must not
/// read a false there as a failure. `removed` is therefore optional: nil means
/// the server never said, which is not the same as it saying no.
struct MailScreenerDecisionResponse: Decodable {
    var success = false
    var decision: MailScreenerDecisionRecord?
    var removed: Bool?
    var error: String?

    private enum CodingKeys: String, CodingKey { case success, decision, removed, error }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        decision = c.lenientOpt(.decision)
        removed = c.lenientOpt(.removed)
        error = c.lenientOpt(.error)
    }
}

// ── calls ────────────────────────────────────────────────────────────────────

extension ApiClient {

    private static func mailGet<T: Decodable>(_ suffix: String, bearer: String) async throws -> T {
        try await request("\(mailBase)\(suffix)", bearer: bearer)
    }

    static func mailMe(_ token: String) async throws -> MailMeResponse {
        try await mailGet("/me", bearer: token)
    }

    static func mailStats(_ token: String) async throws -> MailStatsResponse {
        try await mailGet("/stats", bearer: token)
    }

    /// `GET /inbox`. `q` is only sent at 3+ characters, because a one-character
    /// term scans the whole mailbox server-side (`grapmail.ts:688`) and the
    /// screen would pay for every keystroke.
    static func mailInbox(
        token: String, box: MailBox, page: Int = 1, pageSize: Int = 25, query: String? = nil,
    ) async throws -> MailInboxResponse {
        let term = query?.trimmingCharacters(in: .whitespacesAndNewlines)
            .nilIfBlank()
        var path = "/inbox?box=\(box.rawValue)&page=\(page)&pageSize=\(pageSize)"
        if let term, term.count >= 3,
           let encoded = term.addingPercentEncoding(withAllowedCharacters: .alphanumerics) {
            path += "&q=\(encoded)"
        }
        // Checked at the transport, exactly as Android does: three places read this
        // response — the list, pull-to-refresh, and the screener's pending count —
        // and a listing the server said it did not produce must not reach any of
        // them as an empty box. "Inbox is empty" is a claim about mail the server
        // never listed.
        let resp: MailInboxResponse = try await mailGet(path, bearer: token)
        guard resp.success else {
            throw ApiError.http(status: 200, code: resp.code,
                                message: resp.error ?? "The server returned no listing.")
        }
        return resp
    }

    static func mailMessage(token: String, id: String) async throws -> MailMessageResponse {
        try await mailGet("/message/\(id.percentEncodedPath())", bearer: token)
    }

    static func mailThread(token: String, threadId: String) async throws -> MailThreadResponse {
        try await mailGet("/thread/\(threadId.percentEncodedPath())", bearer: token)
    }

    /// `GET /message/:id/body` — the reader's render body. The web app
    /// sanitizes the message's HTML part with its own `sanitizeEmailHtml`
    /// profile before answering, so this response is the only HTML a renderer
    /// here may load; `allowImages` is the reader's images-gate opt-in, sent
    /// as `?images=1` only when the person asked for this message's remote
    /// images. Throws like every other call — the model decides what the
    /// reader falls back to (the stripped-text branch it always had).
    static func mailBody(token: String, id: String, allowImages: Bool) async throws -> MailBodyResponse {
        let query = allowImages ? "?images=1" : ""
        return try await mailGet("/message/\(id.percentEncodedPath())/body\(query)", bearer: token)
    }

    /// `PATCH /message/:id/:action` — path-carried action. Only `snooze` reads a
    /// body, and the key it reads is `until`.
    @discardableResult
    static func mailAction(
        token: String, id: String, action: MailAction, until: String? = nil,
    ) async throws -> MailMessageResponse {
        var body: Data? = nil
        if action == .snooze, let until {
            body = try encoder.encode(MailSnoozeRequest(until: until))
        }
        return try await request(
            "\(mailBase)/message/\(id.percentEncodedPath())/\(action.apiValue)",
            method: "PATCH", body: body, bearer: token)
    }

    /// Permanent destruction, valid only from Trash.
    static func mailDeletePermanent(token: String, id: String) async throws {
        _ = try await request(
            "\(mailBase)/message/\(id.percentEncodedPath())",
            method: "DELETE", bearer: token) as MailSendResponse
    }

    /// Remove one message row by id. Same DELETE route as above; this
    /// wrapper exists so the CALLER'S INTENT is named — a draft that was
    /// just sent is not mail being destroyed, it is the copy the person
    /// already replaced. Send-then-delete, never the reverse: deleting
    /// first would lose the mail if the send fails.
    static func mailDeleteMessage(token: String, id: String) async throws {
        _ = try await request(
            "\(mailBase)/message/\(id.percentEncodedPath())",
            method: "DELETE", bearer: token) as MailSendResponse
    }

    static func mailSend(token: String, _ req: MailSendRequest) async throws -> MailSendResponse {
        try await request("\(mailBase)/send", method: "POST",
                          body: try encoder.encode(req), bearer: token)
    }

    static func mailDraft(token: String, _ req: MailDraftRequest) async throws -> MailDraftResponse {
        try await request("\(mailBase)/draft", method: "POST",
                          body: try encoder.encode(req), bearer: token)
    }

    static func mailLabels(_ token: String) async throws -> MailLabelsResponse {
        try await mailGet("/labels", bearer: token)
    }

    /// Every (message, label) pair the account has, in one call.
    ///
    /// This is the read that makes the label surface honest. Without it the app can
    /// write a label onto a message and cannot tell whether it is there, so the
    /// sheet must mark every label the same way whatever the mail actually carries,
    /// and a label can only be removed by someone who happens to remember adding it.
    /// `mailLabelDetach` existed on both platforms with nothing calling it for
    /// exactly that reason.
    static func mailLabelAssignments(_ token: String) async throws -> MailAssignmentsResponse {
        try await mailGet("/labels/assignments", bearer: token)
    }

    static func mailCreateLabel(token: String, name: String, colour: String?) async throws -> MailLabelResponse {
        try await request("\(mailBase)/labels", method: "POST",
                          body: try encoder.encode(MailLabelCreateRequest(name: name, colour: colour)),
                          bearer: token)
    }

    /// `PATCH /labels/:id` — rename and recolour. The body keys are the create
    /// route's, which the handler shares: `name` and the British `colour`, for
    /// the same reason as create — sending `color` saves nothing and says nothing.
    static func mailRenameLabel(token: String, id: String, name: String,
                                colour: String?) async throws {
        _ = try await request("\(mailBase)/labels/\(id.percentEncodedPath())",
                              method: "PATCH",
                              body: try encoder.encode(MailLabelCreateRequest(name: name,
                                                                              colour: colour)),
                              bearer: token) as MailLabelResponse
    }

    static func mailDeleteLabel(token: String, id: String) async throws {
        _ = try await request("\(mailBase)/labels/\(id.percentEncodedPath())",
                              method: "DELETE", bearer: token) as MailLabelsResponse
    }

    static func mailLabelAssign(token: String, messageId: String, labelId: String) async throws {
        _ = try await request("\(mailBase)/labels/message/\(messageId.percentEncodedPath())",
                              method: "POST",
                              body: try encoder.encode(MailLabelAssignRequest(labelId: labelId)),
                              bearer: token) as MailLabelsResponse
    }

    static func mailLabelDetach(token: String, messageId: String, labelId: String) async throws {
        _ = try await request(
            "\(mailBase)/labels/message/\(messageId.percentEncodedPath())/\(labelId.percentEncodedPath())",
            method: "DELETE", bearer: token) as MailLabelsResponse
    }

    static func mailAliases(_ token: String) async throws -> MailAliasesResponse {
        try await mailGet("/aliases", bearer: token)
    }

    /// `POST /aliases` — only a label goes over the wire; the server composes
    /// the address from the account's own handle and domain
    /// (`grapmail-aliases.ts:169,188`). The caller re-reads the alias list
    /// after, because the address the app would build and the address the
    /// route built are two chances to disagree.
    static func mailCreateAlias(
        _ token: String, label: String,
    ) async throws -> MailAliasCreateResponse {
        try await request("\(mailBase)/aliases", method: "POST",
                          body: try encoder.encode(MailAliasLabelRequest(label: label)),
                          bearer: token)
    }

    /// `DELETE /aliases/:address` — the route keys the row by address, not id,
    /// because `GET /aliases` answers no id at all.
    static func mailDeleteAlias(
        _ token: String, address: String,
    ) async throws -> MailAliasDeleteResponse {
        try await request("\(mailBase)/aliases/\(address.percentEncodedPath())",
                          method: "DELETE", bearer: token)
    }

    /// `DELETE /attachments/:id` — take one stored upload out of the message
    /// for good, scoped to the caller's own rows (`grapmail-attachments.ts:412`).
    static func mailDeleteAttachment(
        _ token: String, id: String,
    ) async throws -> MailAttachmentDeleteResponse {
        try await request("\(mailBase)/attachments/\(id.percentEncodedPath())",
                          method: "DELETE", bearer: token)
    }

    // ── End-to-end encryption ────────────────────────────────────────────
    //
    // The four routes are mounted by the auth service at `/grapmail/e2e`
    // (`services/auth-service/src/index.ts:139`) and forwarded to the mail
    // service's key directory (`services/mail-service/src/routes/e2e.ts`). What
    // the directory holds is the PUBLIC half only — the store refuses a body
    // that carries any private material (`grapmail-e2e.ts:151-169`), so nothing
    // in these types has a place to put one. The device-side crypto that uses
    // them arrives with the encryption screens; until those exist on this
    // platform these calls are the same unwired surface Android already holds.

    /// `GET /e2e/keys` — `grapmail-e2e.ts:137-149`. `published: false` is a
    /// real answer ("this account has no key"), kept distinct from a failed
    /// read the same way the label list keeps `notRead` apart from `read`.
    static func mailE2eKeys(_ token: String) async throws -> MailE2eKeysResponse {
        try await mailGet("/e2e/keys", bearer: token)
    }

    /// `PUT /e2e/keys` — publish (or replace) the public halves. Omitting the
    /// signing half rather than sending a null: the route writes what arrives.
    static func mailE2ePublish(
        _ token: String, encryptionPublicKey: String, signingPublicKey: String?,
    ) async throws -> MailE2ePublishResponse {
        try await request("\(mailBase)/e2e/keys", method: "PUT",
                          body: try encoder.encode(MailE2ePublishRequest(
                            encryptionPublicKey: encryptionPublicKey,
                            signingPublicKey: signingPublicKey)),
                          bearer: token)
    }

    /// `DELETE /e2e/keys` — idempotent: `removed: false` is a success, not an error.
    static func mailE2eUnpublish(_ token: String) async throws -> MailE2eUnpublishResponse {
        try await request("\(mailBase)/e2e/keys", method: "DELETE", bearer: token)
    }

    /// Can a message to these addresses be encrypted? The addresses travel in
    /// a POST body and never a query string: a URL is written to access logs
    /// and proxy history, and recipient addresses are exactly the metadata an
    /// encrypted message exists to keep out of those.
    static func mailE2ePlan(
        _ token: String, addresses: [String],
    ) async throws -> MailE2ePlanResponse {
        try await request("\(mailBase)/e2e/plan", method: "POST",
                          body: try encoder.encode(MailE2eAddressesRequest(addresses: addresses)),
                          bearer: token)
    }

    static func mailE2eLookup(
        _ token: String, addresses: [String],
    ) async throws -> MailE2eLookupResponse {
        try await request("\(mailBase)/e2e/lookup", method: "POST",
                          body: try encoder.encode(MailE2eAddressesRequest(addresses: addresses)),
                          bearer: token)
    }

    /// `GET /attachments/:id` — the bytes, not JSON. Separate from `request`,
    /// which decodes and would corrupt binary by round-tripping it as text.
    ///
    /// A 401 here does NOT end the account session, and that is deliberate: these
    /// are `mailBase` paths, and every other mail route on both platforms carves
    /// them out because the mail service refuses a bearer the account endpoints
    /// still accept. The status is thrown to the caller as itself, so the file
    /// screen says what happened. A revoked token is not kept alive by this — the
    /// next account call rejects it and lands the person on sign-in the way it
    /// already does for every other screen.
    static func mailAttachmentBytes(token: String, id: String) async throws -> Data {
        let (data, http) = try await mailBinary(
            url: URL(string: "\(base)\(mailBase)/attachments/\(id.percentEncodedPath())"),
            method: "GET", bearer: token, body: nil, contentType: nil)
        guard http.statusCode < 400 else {
            throw mailFailure(data: data, http: http)
        }
        return data
    }

    /// `POST /attachments` — multipart, with the field names the route reads:
    /// `file` and `messageId` (`grapmail-attachments.ts:225,236`). `messageId`
    /// is sent empty for an unsent draft rather than omitted, because the route
    /// coerces an absent field to null and stores the bytes with no row to
    /// attach them to.
    static func mailAttachmentUpload(
        token: String, bytes: Data, filename: String, mimeType: String,
        messageId: String? = nil,
    ) async throws -> MailAttachment {
        let boundary = "----GrapMail\(UUID().uuidString)"
        let safeName = filename
            .replacingOccurrences(of: "\"", with: "")
            .replacingOccurrences(of: "\r", with: "")
            .replacingOccurrences(of: "\n", with: "")
        var body = Data()
        func text(_ s: String) { body.append(Data(s.utf8)) }
        text("--\(boundary)\r\n")
        text("Content-Disposition: form-data; name=\"file\"; filename=\"\(safeName)\"\r\n")
        text("Content-Type: \(mimeType.isEmpty ? "application/octet-stream" : mimeType)\r\n\r\n")
        body.append(bytes)
        text("\r\n")
        text("--\(boundary)\r\n")
        text("Content-Disposition: form-data; name=\"messageId\"\r\n\r\n")
        text("\(messageId ?? "")\r\n")
        text("--\(boundary)--\r\n")

        let (data, http) = try await mailBinary(
            url: URL(string: "\(base)\(mailBase)/attachments"),
            method: "POST", bearer: token, body: body,
            contentType: "multipart/form-data; boundary=\(boundary)")
        guard http.statusCode < 400 else {
            throw mailFailure(data: data, http: http)
        }
        let r = try decoder.decode(MailAttachmentResponse.self, from: data)
        guard let a = r.attachment else {
            throw ApiError.http(status: http.statusCode, code: r.code,
                                message: r.error ?? "The server stored no attachment.")
        }
        return a
    }

    /// The transport the two attachment calls share. Kept private and tiny: it
    /// returns the bytes and the status, and leaves the interpretation to the
    /// caller, because one wants the body and the other wants JSON.
    private static func mailBinary(
        url: URL?, method: String, bearer: String, body: Data?, contentType: String?,
    ) async throws -> (Data, HTTPURLResponse) {
        guard let url else {
            throw ApiError.http(status: -1, code: nil, message: "Bad URL")
        }
        var req = mailRequest(url: url, method: method, bearer: bearer)
        if let contentType { req.setValue(contentType, forHTTPHeaderField: "Content-Type") }
        req.httpBody = body
        return try await authenticatedResponse(for: req, bearer: bearer)
    }

    private static func mailFailure(
        data: Data, http: HTTPURLResponse,
    ) -> ApiError {
        let carrier = try? decoder.decode(ErrorCarrier.self, from: data)
        return ApiError.http(
            status: http.statusCode,
            code: carrier?.code,
            message: carrier?.message ?? carrier?.error ?? ""
        )
    }

    static func mailScreener(_ token: String) async throws -> MailScreenerResponse {
        try await mailGet("/screener", bearer: token)
    }

    static func mailScreenerSet(token: String, enabled: Bool) async throws -> MailScreenerResponse {
        try await request("\(mailBase)/screener/enabled", method: "PUT",
                          body: try encoder.encode(MailScreenerEnableRequest(enabled: enabled)),
                          bearer: token)
    }

    /// Record a standing decision about one sender: `PUT /screener/decision`.
    ///
    /// Not a message action. The message actions file what is already in the
    /// mailbox; a decision is a rule the delivery path reads when the NEXT
    /// message arrives, so approving a sender here does not move the message in
    /// front of the user. The proxy validates nothing on purpose — mail-service
    /// owns the four decisions and the two scopes, and a second validator would
    /// be a second opinion about what is legal that could drift from the store's
    /// own. The app sends the server's spellings verbatim and relays its refusal
    /// word for word.
    static func mailScreenerDecision(
        token: String, sender: String,
        scope: MailSenderScope, decision: MailDecision,
    ) async throws -> MailScreenerDecisionResponse {
        try await request("\(mailBase)/screener/decision", method: "PUT",
                          body: try encoder.encode(MailScreenerDecisionRequest(
                            sender: sender, scope: scope.apiValue, decision: decision.apiValue)),
                          bearer: token)
    }

    /// Forget a decision, so the sender is undecided again and their mail returns
    /// to the queue. Parameters ride in the query string because that is what the
    /// route reads (`grapmail-screener.ts:127-133`): a DELETE with a body is not
    /// something every client and proxy carries.
    static func mailScreenerDecisionRemove(
        token: String, sender: String, scope: MailSenderScope,
    ) async throws -> MailScreenerDecisionResponse {
        let url = "\(mailBase)/screener/decision?scope=\(scope.apiValue.percentEncodedQuery())"
            + "&sender=\(sender.percentEncodedQuery())"
        return try await request(url, method: "DELETE", bearer: token)
    }
}

// ── small helpers used across the sector ─────────────────────────────────────

extension String {
    /// A blank-but-present string is not information. Treating `"   "` as a
    /// subject would render whitespace as content.
    func nilIfBlank() -> String? {
        let t = trimmingCharacters(in: .whitespacesAndNewlines)
        return t.isEmpty ? nil : self
    }

    /// Percent-encodes a value that goes into a **path segment**.
    ///
    /// Required, not defensive: the mail proxy refuses a decoded segment that is
    /// `.`, `..`, contains a slash or a NUL (`route.ts:110-115`), so an id with
    /// anything unexpected in it must arrive encoded or be rejected as a
    /// traversal attempt rather than as an id.
    func percentEncodedPath() -> String {
        addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? self
    }

    /// Percent-encodes a value that goes into a **query parameter**.
    ///
    /// Separate from the path encoder because the characters that must not leak
    /// are different: a sender address dropped into `&sender=` unencoded can
    /// inject a `&` of its own and become a second parameter, which is exactly
    /// how a value stops being one value. Encoding every non-alphanumeric is
    /// over-broad but never wrong, and it is the same set the path encoder uses,
    /// so the two cannot drift.
    func percentEncodedQuery() -> String {
        addingPercentEncoding(withAllowedCharacters: .alphanumerics) ?? self
    }
}

// ── scheduled sends ──────────────────────────────────────────────────────────

/// One future send, as `GET /grapmail/scheduled` answers it: mail the server
/// holds with status 'scheduled' until the worker promotes it at
/// `scheduledFor`. Reading and cancelling these is gate-legal — no client
/// scheduling field is involved.
struct MailScheduledMessage: Decodable, Identifiable {
    let id: String
    let subject: String?
    let to: [String]?
    let scheduledFor: String?
    let createdAt: String?
}

struct MailScheduledResponse: Decodable {
    let success: Bool?
    let messages: [MailScheduledMessage]?
}

extension ApiClient {
    /// `GET /grapmail/scheduled` — the account's future sends, soonest first.
    static func mailScheduledList(_ token: String) async throws -> MailScheduledResponse {
        try await request("\(mailBase)/scheduled", bearer: token)
    }

    /// `DELETE /grapmail/scheduled/:id` — take one future send back. The
    /// server clears `scheduled_at`, so the worker will never deliver it.
    static func mailScheduledCancel(_ token: String, id: String) async throws -> MailScheduledResponse {
        try await request("\(mailBase)/scheduled/\(id.percentEncodedPath())",
                          method: "DELETE", bearer: token)
    }
}
