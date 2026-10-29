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
    /// The sender's List-Unsubscribe offer — `grapmail.ts:589`.
    var unsubscribe: String?
    var isE2eEncrypted = false
    /// ISO instant. nil means there is no trustworthy time to display.
    var createdAt: String?

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
        unsubscribe = c.lenientOpt(.unsubscribe)
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
    var unsubscribe: String?
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
        unsubscribe = c.lenientOpt(.unsubscribe)
        isE2eEncrypted = c.lenient(.isE2eEncrypted, false)
        createdAt = c.lenientOpt(.createdAt)
        bodyText = c.lenientOpt(.bodyText)
        bodyHtml = c.lenientOpt(.bodyHtml)
        inReplyTo = c.lenientOpt(.inReplyTo)
        sizeBytes = c.lenientOpt(.sizeBytes)
        e2eEnvelope = c.lenientOpt(.e2eEnvelope)
        attachments = c.lenient(.attachments, [])
    }
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
        case id, messageId, name, mimeType, sizeBytes, createdAt
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.lenient(.id, "")
        messageId = c.lenientOpt(.messageId)
        name = c.lenientOpt(.name)
        mimeType = c.lenientOpt(.mimeType)
        sizeBytes = c.lenientOpt(.sizeBytes)
        createdAt = c.lenientOpt(.createdAt)
    }
}

// ── envelopes ────────────────────────────────────────────────────────────────

/// `GET /inbox` — `grapmail.ts:729-737`.
struct MailInboxResponse: Decodable {
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

    private enum CodingKeys: String, CodingKey {
        case success, inbox, sent, drafts, spam, trash
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        success = c.lenient(.success, false)
        inbox = c.lenientOpt(.inbox)
        sent = c.lenientOpt(.sent)
        drafts = c.lenientOpt(.drafts)
        spam = c.lenientOpt(.spam)
        trash = c.lenientOpt(.trash)
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
    /// Server-chosen colour, carried as a hex string and never re-derived.
    /// Decoded from `colour` because that is the key the route writes.
    var color: String?
    var textColor: String?
    /// Unread count for this label; nil means unknown, not zero.
    var unread: Int?

    private enum CodingKeys: String, CodingKey {
        case id, name, color = "colour", textColor, unread
    }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.lenient(.id, "")
        name = c.lenient(.name, "")
        color = c.lenientOpt(.color)
        textColor = c.lenientOpt(.textColor)
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

/// `GET /aliases` — a second address delivering into this mailbox. This, not a
/// fake multi-account switcher, is what the reference's account chip can
/// honestly show.
struct MailAlias: Decodable, Identifiable {
    var id = ""
    var label = ""
    var address = ""
    var isActive = true
    var createdAt: String?

    private enum CodingKeys: String, CodingKey { case id, label, address, isActive, createdAt }
    init(from decoder: Decoder) throws {
        let c = try decoder.container(keyedBy: CodingKeys.self)
        id = c.lenient(.id, "")
        label = c.lenient(.label, "")
        address = c.lenient(.address, "")
        isActive = c.lenient(.isActive, true)
        createdAt = c.lenientOpt(.createdAt)
    }
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

// ── vocabularies ─────────────────────────────────────────────────────────────

/// The boxes the mail API accepts, from `grapmail-boxes.ts:43-68` plus
/// `EVERYTHING_BOX` at `:78`.
///
/// Two things a caller must not assume:
///  - `archive` and `others` are answered by the **archive** router, which
///    `next()`-falls through for every other box. Same URL, different handler.
///  - There is no `snoozed` box. `snooze` writes `snoozedUntil`
///    (`grapmail-archive.ts:171`) and the inbox hides those rows
///    (`grapmail.ts:719-726`), so a snoozed message leaves the visible set with
///    no way back. The action is offered; no box is claimed for it.
enum MailBox: String, CaseIterable, Identifiable {
    case inbox, starred, sent, drafts, archive, spam, trash, screener
    case others

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
        }
    }

    /// The drawer's order — the boxes the reference lists, minus the ones the
    /// server cannot answer (see the README's honest-UI contract).
    static let drawerOrder: [MailBox] = [.inbox, .starred, .sent, .drafts, .archive, .spam, .trash, .others]
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
    var bodyHtml: String?
    /**
     * Sent for reply threading. `grapmail.ts:851-900` is confirmed to read
     * to/cc/bcc/subject/bodyText/bodyHtml; this is the one key compose needs
     * that was not in that window, so it is re-checked against the handler when
     * the gateway is back — if the route ignores it, replies lose their
     * In-Reply-To and the thread view shows them as new messages.
     */
    var inReplyTo: String?
}

struct MailDraftRequest: Encodable {
    var to: [String] = []
    var cc: [String] = []
    var subject: String = ""
    var bodyText: String = ""
    var inReplyTo: String?
}

struct MailSnoozeRequest: Encodable { var until: String }
struct MailLabelCreateRequest: Encodable { var name: String; var colour: String? }
struct MailLabelAssignRequest: Encodable { var labelId: String }
struct MailScreenerEnableRequest: Encodable { var enabled: Bool }

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
        return try await mailGet(path, bearer: token)
    }

    static func mailMessage(token: String, id: String) async throws -> MailMessageResponse {
        try await mailGet("/message/\(id.percentEncodedPath())", bearer: token)
    }

    static func mailThread(token: String, threadId: String) async throws -> MailThreadResponse {
        try await mailGet("/thread/\(threadId.percentEncodedPath())", bearer: token)
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

    static func mailCreateLabel(token: String, name: String, colour: String?) async throws -> MailLabelResponse {
        try await request("\(mailBase)/labels", method: "POST",
                          body: try encoder.encode(MailLabelCreateRequest(name: name, colour: colour)),
                          bearer: token)
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

    static func mailScreener(_ token: String) async throws -> MailScreenerResponse {
        try await mailGet("/screener", bearer: token)
    }

    static func mailScreenerSet(token: String, enabled: Bool) async throws -> MailScreenerResponse {
        try await request("\(mailBase)/screener/enabled", method: "PUT",
                          body: try encoder.encode(MailScreenerEnableRequest(enabled: enabled)),
                          bearer: token)
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
}
