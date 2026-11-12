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
            else if let o = try? c.decode(