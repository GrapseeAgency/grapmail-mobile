package com.grapsee.grapmail.data

import androidx.compose.runtime.Immutable
import com.grapsee.grapmail.R
import com.grapsee.grapmail.ui.i18n.CoreStrings
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Mailbox sector — the wire shapes of the GrapMail API.
 *
 * Every type here mirrors a `c.json(...)` in
 * `grapsee-backend/services/shared/src/routes/grapmail*.ts`, read from the
 * handler rather than inferred from a client. The file:line of each source is
 * on the type it describes, so drift is visible instead of assumed.
 *
 * ── WHY SO MANY FIELDS ARE NULLABLE ──────────────────────────────────────────
 * A defaulted non-null field silently invents a value the server never sent,
 * and on a mailbox screen an invented value is a lie the user acts on: a message
 * with no `createdAt` shown as "9:41", a sender with no name shown as blank
 * where the address should be. So a field the API may omit is `T?` and the
 * screen decides what "unknown" looks like.
 *
 * The exception is the flag columns the schema declares `NOT NULL` — `isRead`,
 * `isStarred`, `hasAttachments`, `isE2eEncrypted` — which `publicRow` always
 * emits (it coerces the E2E flag with `Boolean(...)` precisely so an absent key
 * cannot be read as `false` by accident). Those default to `false`, matching
 * the database rather than guessing past it.
 */

/** `publicRow.from` — `grapmail.ts:583`. Both halves are separately optional. */
@Immutable
@Serializable
data class MailPersonDto(
    val address: String? = null,
    val name: String? = null,
) {
    /** What a row shows as its sender: the name if there is one, the address if
     *  there is not, and nothing invented when there is neither. */
    val display: String get() = name?.takeIf { it.isNotBlank() } ?: address ?: ""
}

/**
 * The sender's List-Unsubscribe offer, as the mail service PARSES it —
 * `{ offers: [{ url, oneClick }], oneClickAdvertised, mailtoOneClick }` (the
 * shape `sanitiseUnsubscribe` stores, `grapmail.ts:261-287`, and the web
 * client's `normalizeUnsubscribe` reads, `src/app/lib/subscriptions.ts:61-81`).
 *
 * It was decoded as a raw header String here, and the server does not send
 * one: a String field nils the object on every real message, which is how the
 * unsubscribe door stays closed on mail that offers it — the same defect
 * class `e2eEnvelope` was caught in, one shape-drift, one silent absence.
 *
 * The offers list is HTTPS-ONLY at the server (the same regex the web applies
 * before storing), so the mailto door lives in its own `mailtoOneClick`
 * field — dropping it at decode made the row's email handoff a dead control
 * on every real message.
 *
 * `raw` carries the legacy header form for the same reason the browser keeps
 * its fallback: an older row may still hold the comma-separated bracket list,
 * and the existing header parser is honest about that shape.
 */
@Immutable
@Serializable(with = MailUnsubscribeSerializer::class)
data class MailUnsubscribeDto(
    val offers: List<MailUnsubscribeOfferDto> = emptyList(),
    val oneClickAdvertised: Boolean = false,
    val mailtoOneClick: String? = null,
    val raw: String? = null,
)

@Immutable
@Serializable
data class MailUnsubscribeOfferDto(val url: String = "", val oneClick: Boolean = false)

// A plain @Serializable shadow of the same shape: the custom reader below
// must not reach for `MailUnsubscribeDto.serializer()` (the plugin makes
// that THIS object), so the boring field-by-field half is generated here
// and reused for the descriptor and the write path. (An `object` cannot use
// `by` delegation -- that syntax belongs to class constructors -- so the
// three members are spelled out and forward to the Wire.)
@Serializable
private data class MailUnsubscribeWire(
    val offers: List<MailUnsubscribeOfferDto> = emptyList(),
    val oneClickAdvertised: Boolean = false,
    val mailtoOneClick: String? = null,
    val raw: String? = null,
)

private fun MailUnsubscribeWire.toDto() =
    MailUnsubscribeDto(offers = offers, oneClickAdvertised = oneClickAdvertised,
        mailtoOneClick = mailtoOneClick, raw = raw)

private object MailUnsubscribeSerializer : kotlinx.serialization.KSerializer<MailUnsubscribeDto> {
    private val wire = MailUnsubscribeWire.serializer()
    override val descriptor get() = wire.descriptor

    override fun serialize(encoder: kotlinx.serialization.encoding.Encoder, value: MailUnsubscribeDto) {
        encoder.encodeSerializableValue(wire,
            MailUnsubscribeWire(value.offers, value.oneClickAdvertised, value.mailtoOneClick, value.raw))
    }

    override fun deserialize(decoder: kotlinx.serialization.encoding.Decoder): MailUnsubscribeDto {
        val jsonDecoder = decoder as? kotlinx.serialization.json.JsonDecoder
            ?: return wire.deserialize(decoder).toDto()
        val el = jsonDecoder.decodeJsonElement()
        return when (el) {
            is kotlinx.serialization.json.JsonObject -> MailUnsubscribeDto(
                offers = (el["offers"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { o ->
                        val obj = (o as? kotlinx.serialization.json.JsonObject) ?: return@mapNotNull null
                        val url = (obj["url"] as? kotlinx.serialization.json.JsonPrimitive)?.content
                            ?: return@mapNotNull null
                        MailUnsubscribeOfferDto(
                            url = url,
                            oneClick = (obj["oneClick"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true",
                        )
                    } ?: emptyList(),
                oneClickAdvertised =
                    (el["oneClickAdvertised"] as? kotlinx.serialization.json.JsonPrimitive)?.content == "true",
                // JSON null under the key is "no mailto door", not the string
                // "null": a JsonPrimitive's `content` answers "null" for
                // either, so the isString test is what keeps them apart.
                mailtoOneClick = (el["mailtoOneClick"] as?
                    kotlinx.serialization.json.JsonPrimitive)?.let {
                    if (it.isString) it.content else null
                },
            )
            // A bare string is the legacy header, kept parseable rather than
            // dropped: null under the old wire, this under the new one, and
            // `unsubscribeTarget` reads the header when there is no offer list.
            is kotlinx.serialization.json.JsonPrimitive ->
                if (el.isString) MailUnsubscribeDto(raw = el.content) else MailUnsubscribeDto()
            else -> MailUnsubscribeDto()
        }
    }
}

/**
 * `publicRow` — `grapmail.ts:579-607`. The list shape. It deliberately carries
 * no body: a listing renders fifty previews and none of them shows a message, so
 * fetching bodies here would read fifty payloads to draw fifty rows.
 */
@Immutable
@Serializable
data class MailMessageDto(
    val id: String = "",
    val threadId: String? = null,
    /** `inbound` or `outbound`. Drives the Sent/Drafts reading of a row. */
    val direction: String? = null,
    /** Delivery state: `queued`, `sent`, `failed`, … Never rendered as a date. */
    val status: String? = null,
    val from: MailPersonDto? = null,
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    /** Null is a real value here: a message can legitimately have no subject. */
    val subject: String? = null,
    val snippet: String? = null,
    val isRead: Boolean = false,
    val isStarred: Boolean = false,
    val hasAttachments: Boolean = false,
    /** The sender's List-Unsubscribe offer, or null. `grapmail.ts:589`. */
    val unsubscribe: MailUnsubscribeDto? = null,
    val isE2eEncrypted: Boolean = false,
    /** ISO instant. Null means the row has no trustworthy time to show. */
    val createdAt: String? = null,
    /** Sent list only: `none` | `requested` | `read` (routes/grapmail-sent-receipts.ts). Null elsewhere. */
    val readReceiptStatus: String? = null,
    /** ISO time of the first recorded receipt, or null. */
    val readReceiptReadAt: String? = null,
) {
    /**
     * The line the row shows where a subject is absent. Not a guess about the
     * message — a statement that it has no subject.
     */
    val subjectOrNone: String get() = subject?.takeIf { it.isNotBlank() } ?: CoreStrings.get(R.string.core_notif_no_subject) { "(no subject)" }
}

/**
 * `fullRow` — `grapmail.ts:609-623`, plus the attachment rows the single-message
 * route bolts on (`grapmail.ts:769-774`).
 */
@Immutable
@Serializable
data class MailMessageFullDto(
    val id: String = "",
    val threadId: String? = null,
    val direction: String? = null,
    val status: String? = null,
    val from: MailPersonDto? = null,
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val subject: String? = null,
    val snippet: String? = null,
    val isRead: Boolean = false,
    val isStarred: Boolean = false,
    val hasAttachments: Boolean = false,
    val unsubscribe: MailUnsubscribeDto? = null,
    val isE2eEncrypted: Boolean = false,
    val createdAt: String? = null,
    val bodyText: String? = null,
    val bodyHtml: String? = null,
    val hasHtml: Boolean = !bodyHtml.isNullOrBlank(),
    val inReplyTo: String? = null,
    val sizeBytes: Int? = null,
    /**
     * The sealed envelope, opaque to the server and returned byte for byte
     * (`grapmail.ts:618-625` — "the one route where it is the point").
     *
     * It is a JSON OBJECT, not a string: the row stores `e2e_envelope` as JSONB
     * and the handler answers with the parsed value. Declaring this as a String
     * made every sealed message fail to decode, which is how an encrypted
     * message would have shown up as "this message could not be loaded" — a
     * network-shaped excuse for a working server.
     *
     * [MailE2e.envelopeFrom] reads it, and nothing else in the app opens it
     * without this device's key and passphrase.
     */
    val e2eEnvelope: kotlinx.serialization.json.JsonObject? = null,
    val attachments: List<MailAttachmentDto> = emptyList(),
)

/**
 * `grapmail_attachments` rows — metadata only; the bytes live in object storage
 * and `storageKey` is server-generated so a client filename can never become a
 * path. Never sent to the UI: it is a server-side key, not a display field.
 */
@Immutable
@Serializable
data class MailAttachmentDto(
    val id: String = "",
    val messageId: String? = null,
    val name: String? = null,
    val mimeType: String? = null,
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    @kotlinx.serialization.json.JsonNames("size")
    val sizeBytes: Int? = null,
    val createdAt: String? = null,
    /** Set for an image placed inside the message body; the HTML refers to it as `cid:<id>`. */
    val contentId: String? = null,
)

/** `GET /grapmail/inbox` — `grapmail.ts:729-737`. */
@Serializable
data class MailInboxResponse(
    val success: Boolean = false,
    val box: String? = null,
    val page: Int = 1,
    val pageSize: Int = 25,
    /** Total rows in the box, not rows on this page — the paging maths needs it. */
    val total: Int = 0,
    val messages: List<MailMessageDto> = emptyList(),
    /**
     * Present when the request carried a search term. The server answers the
     * term it actually used, so the UI can say what was searched rather than
     * echoing what the field happened to contain when the call went out.
     */
    val q: String? = null,
    /**
     * Declared so both platforms decode the same envelope. iOS carried these two
     * and Android did not, which the parity gate could not see while it matched
     * only `…Dto` names.
     *
     * They are populated only if the route ever answers 200 with `success: false`:
     * today `NO_MAILBOX` arrives as a 404 and reaches the caller through the error
     * path. [Api.mailInbox] therefore checks `success` before returning, so a
     * listing the server refused cannot be drawn as an empty box.
     */
    val error: String? = null,
    val code: String? = null,
)

/**
 * `GET /grapmail/message/:id` — `grapmail.ts:769`. Opening a message marks it
 * read on the server, so the caller must not also send a `read` action: the
 * second write is redundant and races the first.
 */
@Serializable
data class MailMessageResponse(
    val success: Boolean = false,
    val message: MailMessageFullDto? = null,
)

/** `GET /grapmail/thread/:threadId` — `grapmail.ts:786`. */
@Serializable
data class MailThreadResponse(
    val success: Boolean = false,
    val messages: List<MailMessageFullDto> = emptyList(),
    val nextCursor: String? = null,
    val hasMore: Boolean = false,
)

/**
 * `GET /grapmail/message/:id/body` — the reader's render body, served by the
 * web app's same-origin route (`src/app/api/grapsee/grapmail/message/[id]/body/
 * route.ts`), not by the backend: the raw HTML part the message row carries is
 * sanitized server-side — with `sanitizeEmailHtml`, the same strict
 * untrusted-email profile the web's SafeEmailBody renders through — before it
 * is allowed near a renderer.
 *
 * `bodyHtml` is therefore the ONLY string this app may put in a WebView. The
 * raw `message.bodyHtml` from the row is never a render input: no sanitiser
 * ships in this app, and the correct one is not a regex. Remote images are
 * already neutralised in the response (`src` moved to
 * `data-grapmail-blocked-src`) unless `?images=1` accompanied the request —
 * `blockedImages` is the honest count of what was blocked, and `bodyText` is
 * the row's plain part echoed back for the no-HTML case, so a null `bodyHtml`
 * means "there is no formatted view", not "the field went missing".
 */
@OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
@Serializable
data class MailBodyResponse(
    @kotlinx.serialization.json.JsonNames("success")
    val success: Boolean = true,
    @kotlinx.serialization.json.JsonNames("bodyHtml", "html")
    val bodyHtml: String? = null,
    @kotlinx.serialization.json.JsonNames("bodyText", "text")
    val bodyText: String? = null,
    @kotlinx.serialization.json.JsonNames("blockedImages", "remoteImageCount")
    val blockedImages: Int = 0,
    val hasHtml: Boolean = !bodyHtml.isNullOrBlank(),
)

/**
 * `GET /grapmail/me` — `grapmail.ts:632-638`. `status` is the mailbox's own
 * state, which is not the account's state; keep them apart in the UI.
 */
@Serializable
data class MailMeResponse(
    val success: Boolean = false,
    val address: String? = null,
    val handle: String? = null,
    val status: String? = null,
    val name: String? = null,
    val memberSince: String? = null,
    val error: String? = null,
    val code: String? = null,
)

/**
 * `GET /grapmail/stats` — `grapmail.ts:647-691`.
 *
 * `null` here means "the server did not answer", and the UI must render nothing
 * rather than `0`, because `0` is a claim that the folder is empty.
 *
 * The four extra fields (`starred`, `archive`, `others`, `snoozed`) are what the
 * backend added when `/stats` began counting the same containers the listings
 * read (`boxCount` + `EVERYTHING_BOX` + `SNOOZED_BOX`). They stay nullable for a
 * second reason than "not asked": an older deployment simply does not send them.
 * That is load-bearing for Snoozed — the drawer row and its badge appear only
 * when the server reports the box, so the app never advertises a list it cannot
 * fetch, and it needs no hardcoded build flag that goes stale the day production
 * moves.
 */
@Serializable
data class MailStatsResponse(
    val success: Boolean = false,
    val inbox: MailInboxStatsDto? = null,
    val sent: Int? = null,
    val drafts: Int? = null,
    val spam: Int? = null,
    val trash: Int? = null,
    val starred: Int? = null,
    val archive: Int? = null,
    /** `?box=others` — everything still in the account (`grapmail-box-terms.ts:78`). */
    val others: Int? = null,
    /**
     * The count of `?box=snoozed`. Null is read as two different things by the
     * drawer, and both are true of a null: nobody was asked, or this deployment
     * has no snoozed list. Neither licenses a `0`.
     */
    val snoozed: Int? = null,
)

@Serializable
data class MailInboxStatsDto(
    val total: Int? = null,
    val unread: Int? = null,
)

/** `grapmail_labels` rows — `grapmail-labels.ts:93-96`. */
@Serializable
data class MailLabelDto(
    val id: String = "",
    val name: String = "",
    /**
     * The server's palette KEY — `slate`, `red`, `amber`, `green`, `teal`, `blue`,
     * `violet`, `pink` (`grapmail-labels.ts:39`) — never a hex value. Declared with
     * `@SerialName("colour")` because the column is spelled with a u: without it
     * this field decoded as null for every label the server had coloured, so the
     * dot beside each name silently fell back to the neutral grey on Android while
     * iOS, which does map the key, showed something. The two apps disagreed about
     * the same label, which no screenshot of one of them would reveal.
     */
    @SerialName("colour")
    val color: String? = null,
    /** Unread count for this label; absent means unknown, not zero. */
    val unread: Int? = null,
)

@Serializable
data class MailLabelsResponse(
    val success: Boolean = false,
    val labels: List<MailLabelDto> = emptyList(),
    val error: String? = null,
    val code: String? = null,
)

/** The create/rename routes answer with the one row they wrote. */
@Serializable
data class MailLabelResponse(
    val success: Boolean = false,
    val label: MailLabelDto? = null,
    val error: String? = null,
    val code: String? = null,
)

/**
 * One (message, label) pair — `GET /labels/assignments`.
 *
 * The route returns every pair the caller has, in one response, precisely so a
 * list of twenty messages costs one request rather than twenty (`grapmail-labels.ts`
 * header). The client therefore holds a messageId → labelIds map and answers the
 * question “what is this row wearing?” locally.
 */
@Serializable
data class MailAssignmentDto(
    val messageId: String = "",
    val labelId: String = "",
)

@Serializable
data class MailAssignmentsResponse(
    val success: Boolean = false,
    val assignments: List<MailAssignmentDto> = emptyList(),
    val error: String? = null,
    val code: String? = null,
)

/**
 * How a label stands against the current selection, in the Label sheet.
 *
 * Four states, not two, because two would be a lie twice over:
 *
 *  - `Mixed` is real and reachable — three messages selected, two carry the label.
 *    Drawing it as `Off` would tell the user to add a label they already have on
 *    most of the selection; drawing it as `On` would tell them to remove it from
 *    the ones that never had it.
 *  - `Unknown` is the state before the read answers, and the state it stays in
 *    when the read fails. Marking anything then would be the sheet asserting a
 *    label's presence from nothing — the same class as a badge that draws `0`
 *    because nobody was asked.
 */
enum class LabelMark { On, Mixed, Off, Unknown }

/** Whether the assignment read has answered. Kept apart from the map itself so
 *  “read, and nothing is labelled” is not confused with “never asked”. */
enum class AssignmentsRead { NotRead, Reading, Read, Failed }

/**
 * `GET /grapmail/aliases` — a second address delivering into this mailbox.
 *
 * Three fields, because three is what the route selects: `grapmail-aliases.ts:131-152`
 * reads `label`, `address` and `createdAt` and nothing else. This used to declare
 * `id` and `isActive` with defaults, and both were lies with consequences — an
 * alias was asserted live when nobody had said so (and the "paused" tag could
 * therefore never appear), and every row carried the same empty `id`, which is a
 * duplicate identity in a list keyed by one. `address` is the identity here: the
 * DELETE route takes an address, not an id (`:277`).
 */
@Serializable
data class MailAliasDto(
    val label: String = "",
    val address: String = "",
    val createdAt: String? = null,
)

@Serializable
data class MailAliasesResponse(
    val success: Boolean = false,
    val aliases: List<MailAliasDto> = emptyList(),
)

/**
 * The body of `POST /grapmail/aliases` — one field, `label`
 * (`grapmail-aliases.ts:169` reads `body?.label`, slugifies it, and builds the
 * address at `:188` as slug + "." + handle + "@" + the account's domain).
 *
 * There is deliberately no address field to fill in: the server owns the domain
 * and the handle, so this client cannot name the mailbox an alias is created in.
 * Sending `color`-style extras is worse than useless here — the route ignores
 * unknown keys, so an invented `isActive` would read back as the server's own
 * default and the screen would show a state nobody set.
 */
@Serializable
data class MailAliasLabelRequest(val label: String)

/**
 * `POST /grapmail/aliases` — `grapmail-aliases.ts:155-275`.
 *
 * The server mints the address itself (`label + '.' + handle + '@' + domain`);
 * the client sends only a label and never a full address, which is what stops one
 * account claiming another's primary. The three refusals — `duplicate`, `taken`,
 * `limit` — come back as `success: false` with a sentence, and a 429 with
 * `retryAfter` when the throttle trips, so the screen can say which of them it is
 * rather than "something went wrong".
 */
@Serializable
data class MailAliasCreateResponse(
    val success: Boolean = false,
    val alias: MailAliasDto? = null,
    val message: String? = null,
    val error: String? = null,
    val code: String? = null,
    val retryAfter: Int? = null,
)

/**
 * `DELETE /grapmail/aliases/:address` — `grapmail-aliases.ts:277-300`.
 *
 * A row that was not the caller's is answered 404 "No such alias" rather than
 * 403, so this cannot be used to learn whether somebody else owns an address.
 */
@Serializable
data class MailAliasDeleteResponse(
    val success: Boolean = false,
    val address: String? = null,
    val error: String? = null,
    /** The gateway route answers one; the JSONL route may. Optional because
     *  an absent sentence is no answer, and the screen falls back to its own. */
    val message: String? = null,
    val retryAfter: Int? = null,
)

/**
 * `DELETE /grapmail/attachments/:id` — `grapmail-attachments.ts:412`.
 *
 * The route is scoped to the caller's own rows, so an attachment id belonging to
 * another account is refused the same way as one that does not exist.
 */
@Serializable
data class MailAttachmentDeleteResponse(
    val success: Boolean = false,
    val id: String? = null,
    val error: String? = null,
    val code: String? = null,
)

// ── End-to-end encryption ────────────────────────────────────────────────────
//
// The four routes are mounted by the auth service at `/grapmail/e2e`
// (`services/auth-service/src/index.ts:139`) and forwarded to the mail service's
// key directory (`services/mail-service/src/routes/e2e.ts`). They are app-facing:
// the account id is filled in from the session and is never sent from here, so
// there is no field through which this client could name another account.
//
// What the directory holds is the PUBLIC half only. The store refuses a body that
// carries any private material (`grapmail-e2e.ts:151-169`, and the private-material
// check in `mail-service/src/routes/e2e.ts:209`), so a client that tried to upload
// a private key is answered 400 with the sentence that names the field. Nothing in
// this file has a place to put one.

/** One published key, with the identifiers the server derives from it. */
@Serializable
data class MailE2eKeyDto(
    /** base64 SPKI DER — X25519 for encryption, Ed25519 for signing. */
    val publicKey: String = "",
    /** SHA-256 over the DER, uppercase hex, colon-separated. */
    val fingerprint: String = "",
    /** The low-order 64 bits of the fingerprint, for list views and logs. */
    val keyId: String = "",
)

@Serializable
data class MailE2eKeysDto(
    val userId: String = "",
    val encryption: MailE2eKeyDto? = null,
    val signing: MailE2eKeyDto? = null,
    val publishedAt: String? = null,
    val updatedAt: String? = null,
)

/**
 * `GET /e2e/keys` — `grapmail-e2e.ts:137-149`.
 *
 * `published: false` is a real answer ("this account has no key"), and it is kept
 * distinct from a failed read, which is the same distinction the label list makes
 * with [AssignmentsRead]: an unknown must not be drawn as a no.
 */
@Serializable
data class MailE2eKeysResponse(
    val success: Boolean = false,
    val published: Boolean = false,
    val keys: MailE2eKeysDto? = null,
    val error: String? = null,
    val code: String? = null,
    val detail: String? = null,
)

/**
 * `PUT /e2e/keys` — `grapmail-e2e.ts:151-169`, forwarding to
 * `mail-service/src/routes/e2e.ts:199-268`.
 *
 * `discoverable` is part of the answer and not a flourish: "your key is published"
 * and "your key is published and senders can find it" are different sentences, and
 * an account whose handle could not be resolved gets the first without the second.
 */
@Serializable
data class MailE2ePublishResponse(
    val success: Boolean = false,
    val keys: MailE2eKeysDto? = null,
    val discoverable: Boolean = false,
    val error: String? = null,
    val detail: String? = null,
)

/** `DELETE /e2e/keys` — idempotent: `removed: false` is a success, not an error. */
@Serializable
data class MailE2eUnpublishResponse(
    val success: Boolean = false,
    val removed: Boolean = false,
    val error: String? = null,
    val detail: String? = null,
)

/**
 * Why one address could not be sealed to. A closed set, mirroring the server's
 * (`mailE2eApi.ts:50-54`), because the sentence a user needs differs per reason:
 * "this person has not set up encryption" and "this is not a GrapMail address"
 * lead to different actions.
 */
enum class MailE2eBlockReason(val apiValue: String) {
    NotAnAddress("not-an-address"),
    RemoteDomain("remote-domain"),
    NoSuchAccount("no-such-account"),
    NoKeyPublished("no-key-published"),
    /** Anything the server says that is not in this build's list. */
    Unknown("unknown"),
    ;

    companion object {
        fun from(value: String?): MailE2eBlockReason =
            entries.firstOrNull { it.apiValue == value } ?: Unknown
    }
}

@Serializable
data class MailE2eBlockedDto(
    val status: String = "unavailable",
    val address: String = "",
    val reason: String? = null,
    val domain: String = "",
) {
    /** Read through the closed set, never by trusting `reason` as a string the
     *  UI branches on directly: a server that adds a reason must land on
     *  [Unknown] and be said plainly, not drawn as the wrong sentence. */
    val why: MailE2eBlockReason get() = MailE2eBlockReason.from(reason)
}

/**
 * `POST /e2e/plan` — `grapmail-e2e.ts:194-229`, and the shape the web client
 * declares at `src/app/lib/mailE2eApi.ts:70-72`.
 *
 * The addresses travel in a POST body and never a query string, on both the plan
 * and the lookup routes: a URL is written to access logs, proxy logs and browser
 * history, and recipient addresses are exactly the metadata an encrypted message
 * exists to keep out of those.
 */
@Serializable
data class MailE2ePlanDto(
    /** `seal` or `plain`. */
    val mode: String = "",
    val keys: List<String> = emptyList(),
    val recipients: List<String> = emptyList(),
    val blocked: List<MailE2eBlockedDto> = emptyList(),
) {
    val canSeal: Boolean get() = mode == "seal"
}

@Serializable
data class MailE2ePlanResponse(
    val success: Boolean = false,
    val plan: MailE2ePlanDto? = null,
    val error: String? = null,
    val detail: String? = null,
)

/** One answer from `POST /e2e/lookup` — the sender's signing key, or why there is none. */
@Serializable
data class MailE2eLookupEntryDto(
    /** `found` or `unavailable`. */
    val status: String = "",
    val address: String = "",
    val signing: MailE2eKeyDto? = null,
    val reason: String? = null,
    val domain: String = "",
) {
    val why: MailE2eBlockReason get() = MailE2eBlockReason.from(reason)
}

@Serializable
data class MailE2eLookupResponse(
    val success: Boolean = false,
    val results: List<MailE2eLookupEntryDto> = emptyList(),
    val error: String? = null,
    val detail: String? = null,
)

/** `PUT /e2e/keys` body. Only the public halves — see the block comment above. */
@Serializable
data class MailE2ePublishRequest(
    val encryptionPublicKey: String,
    val signingPublicKey: String? = null,
)

/** `POST /e2e/plan` and `POST /e2e/lookup` bodies. */
@Serializable
data class MailE2eAddressesRequest(
    val addresses: List<String>,
)


/**
 * `POST /grapmail/send` — `grapmail.ts:1136-1143`.
 *
 * `status` is the truth about the send and `deliveryError` is the server's own
 * reason. The receipt screen shows both verbatim: a compose screen that says
 * "Sent" because the request returned 200 would be inventing a delivery the
 * queue has not confirmed.
 */
@Serializable
data class MailSendResponse(
    val success: Boolean = false,
    val id: String? = null,
    val threadId: String? = null,
    val status: String? = null,
    val deliveryError: String? = null,
    val error: String? = null,
    val code: String? = null,
    val message: String? = null,
    /**
     * Present only when `status` is `scheduled`: the instant the server recorded
     * (`scheduledFor.toISOString()`, grapmail.ts), so the composer confirms the
     * time the server will send at rather than echoing the one it asked for.
     */
    val scheduledFor: String? = null,
)

/** `POST /grapmail/draft` — `grapmail.ts:1160`. */
@Serializable
data class MailDraftResponse(
    val success: Boolean = false,
    val id: String? = null,
    val threadId: String? = null,
    val error: String? = null,
    val code: String? = null,
)

/** `GET /grapmail/screener` — the sender-approval queue's own settings. */
@Serializable
data class MailScreenerResponse(
    val success: Boolean = false,
    val enabled: Boolean? = null,
    val error: String? = null,
    val code: String? = null,
)

/**
 * What the screener does with one sender, once the user has decided.
 *
 * The four values are the server's own set — `DECISIONS` in
 * `mail-service/src/routes/screener.ts:60` — not a client invention, and they
 * are not the same axis as a message action: this writes a standing rule the
 * delivery path reads on the NEXT message, so it cannot file the one in front of
 * you. `later` is a decision too: it keeps the mail in the queue deliberately,
 * which is why the set has four entries and not three verbs.
 */
enum class MailDecision(
    val apiValue: String,
    private val titleRes: Int, private val titleEn: String,
    private val doneRes: Int, private val doneEn: String,
    private val noteRes: Int, private val noteEn: String,
) {
    Approved("approved", R.string.core_decision_approve, "Approve", R.string.core_decision_approved, "Approved",
        R.string.core_decision_approved_note, "Their future mail goes straight to the inbox."),
    Muted("muted", R.string.core_decision_mute, "Mute", R.string.core_decision_muted, "Muted",
        R.string.core_decision_muted_note, "It arrives, but quietly — held out of the unread count."),
    Later("later", R.string.core_decision_later, "Keep waiting", R.string.core_decision_kept_waiting, "Kept waiting",
        R.string.core_decision_later_note, "Nothing is decided. It stays in this queue."),
    Blocked("blocked", R.string.core_decision_block, "Block", R.string.core_decision_blocked, "Blocked",
        R.string.core_decision_blocked_note_text, "Future mail from them is refused at the door."),
    ;

    // Display text only; `apiValue` is the server's word and is never localized.
    val title: String get() = CoreStrings.get(titleRes) { titleEn }
    val done: String get() = CoreStrings.get(doneRes) { doneEn }
    val note: String get() = CoreStrings.get(noteRes) { noteEn }
}

/**
 * How far a decision reaches. `SCOPES` in the same file: `address` or `domain`.
 *
 * Domain is the one that can go wrong quietly — approving `example.com` admits
 * every mailbox at it, now and later, including addresses a stranger registers
 * next year. The sheet says so on the option rather than leaving the choice to
 * be inferred from a radio button.
 */
enum class MailSenderScope(
    val apiValue: String,
    private val titleRes: Int, private val titleEn: String,
    private val warningRes: Int?, private val warningEn: String,
) {
    Address("address", R.string.core_scope_address, "Just this address", null, ""),
    Domain("domain", R.string.core_scope_domain, "The whole domain",
        R.string.core_scope_domain_warning, "Applies to every address at this domain, including ones created later."),
    ;

    val title: String get() = CoreStrings.get(titleRes) { titleEn }
    val warning: String get() = warningRes?.let { id -> CoreStrings.get(id) { warningEn } } ?: warningEn
}

/** `PUT /screener/decision` body: three strings, validated upstream, not here. */
@Serializable
data class MailScreenerDecisionRequest(
    val sender: String,
    val scope: String,
    val decision: String,
)

/**
 * `PUT /screener/decision` answers `{ success, decision: {…} }`; the DELETE
 * answers `{ success, removed }`. `removed: false` is a 200 by design — the
 * caller's intent, "there should be no decision for this sender", is satisfied
 * either way — so the app must not read a false there as a failure.
 */
@Serializable
data class MailScreenerDecisionRecord(
    val sender: String = "",
    val scope: String = "",
    val decision: String = "",
    val decidedAt: String? = null,
)

@Serializable
data class MailScreenerDecisionResponse(
    val success: Boolean = false,
    val decision: MailScreenerDecisionRecord? = null,
    val removed: Boolean? = null,
    val error: String? = null,
)

/**
 * The boxes the mail API accepts.
 *
 * `grapmail-boxes.ts:43-68` is the source: `inbox, sent, drafts, spam, trash,
 * archive, screener` plus the flag-box `starred`, and `others`
 * (`EVERYTHING_BOX`, `grapmail-boxes.ts:78`).
 *
 * Two things a caller must not assume:
 *  - `archive` and `others` are answered by the *archive* router, which
 *    `next()`-falls through for every other box. Same URL, different handler.
 *  - `?box=snoozed` is served by the deployment that answers `/stats` with a
 *    `snoozed` count (`grapmail-box-terms.ts:43,66-68`, `grapmail.ts:670,688`).
 *    The app therefore treats that field as the capability signal: the drawer
 *    shows Snoozed when the server reports it and hides it when it does not,
 *    rather than shipping a flag that goes stale the day production moves. The
 *    in-session Undo stays whatever the box answers, because it is the one
 *    snooze the app knows it caused itself.
 */
enum class MailBox(val apiValue: String) {
    Inbox("inbox"),
    Starred("starred"),
    Sent("sent"),
    Drafts("drafts"),
    Snoozed("snoozed"),
    Archive("archive"),
    Spam("spam"),
    Trash("trash"),
    Screener("screener"),
    All("others"),

    /**
     * Not a box — the value the route rejects. Kept so the compose and filing
     * code can name "no box chosen" without an empty string drifting through
     * the call sites.
     */
    None("");

    /** The label the drawer and tab row show. `Screener` is named for what it
     *  is — a queue the sender has no standing in — not "Updates". */
    val title: String get() = when (this) {
        Inbox -> CoreStrings.get(R.string.core_box_inbox) { "Inbox" }
        Starred -> CoreStrings.get(R.string.core_box_starred) { "Starred" }
        Sent -> CoreStrings.get(R.string.core_box_sent) { "Sent" }
        Drafts -> CoreStrings.get(R.string.core_box_drafts) { "Drafts" }
        Snoozed -> CoreStrings.get(R.string.core_box_snoozed) { "Snoozed" }
        Archive -> CoreStrings.get(R.string.core_box_archive) { "Archive" }
        Spam -> CoreStrings.get(R.string.core_box_spam) { "Spam" }
        Trash -> CoreStrings.get(R.string.core_box_trash) { "Trash" }
        Screener -> CoreStrings.get(R.string.core_box_screener) { "Pending approval" }
        All -> CoreStrings.get(R.string.core_box_all) { "All Mail" }
        None -> CoreStrings.get(R.string.core_box_inbox) { "Inbox" }
    }

    companion object {
        /** The drawer's order, in the reference's sequence. `Snoozed` is not in
         *  this list — it is inserted by the drawer only when `/stats` reports
         *  the box, which is why it is still a member of the enum. */
        val drawerOrder: List<MailBox> = listOf(Inbox, Starred, Sent, Drafts, Archive, Spam, Trash, All)

        /**
         * The tab row. The reference draws Gmail's category tabs
         * (`Primary / Social / Promotions / Updates`); no `category` column
         * exists on `grapmail_messages`, and the website's own tab list is
         * exactly this one (`src/app/(mail)/mail/page.tsx:199-208`). The app
         * shows the boxes that are real rather than four counts nobody produced.
         */
        val tabOrder: List<MailBox> = listOf(Inbox, Starred, Sent, Drafts, Archive)
    }
}

/**
 * The actions a message accepts. The action travels in the **path**
 * (`PATCH /grapmail/message/:id/:action`), never in the body —
 * `grapmail.ts:1253`, and the two halves of the enum come from
 * `grapmail-boxes.ts:265-270` and `grapmail-archive.ts:146-173`.
 *
 * `Trash` and `DeletePermanent` are deliberately different values: trashing is
 * reversible, `DELETE /message/:id` is not and is only valid from Trash. A UI
 * that maps both to one button would destroy mail under a label that promised
 * to file it.
 */
enum class MailAction(val apiValue: String) {
    Read("read"),
    Unread("unread"),
    Star("star"),
    Unstar("unstar"),
    Trash("trash"),
    Restore("restore"),
    Archive("archive"),
    Unarchive("unarchive"),
    Spam("spam"),
    Unspam("unspam"),
    Snooze("snooze"),
    Unsnooze("unsnooze"),
}

/**
 * The action that reverses this one, for the six filing actions the undo
 * banner offers — `PATCH /message/:id/:action` is reversible by the route's own
 * shape, so the inverse is another call, not a local guess. Read/star are not
 * here because their rows stay visible and keep their own toggle: an undo
 * banner for a star would cover the star. Snooze has its dedicated path
 * because its inverse must not fire after the offer is superseded.
 */
fun MailAction.inverse(): MailAction? = when (this) {
    MailAction.Archive -> MailAction.Unarchive
    MailAction.Unarchive -> MailAction.Archive
    MailAction.Spam -> MailAction.Unspam
    MailAction.Unspam -> MailAction.Spam
    MailAction.Trash -> MailAction.Restore
    MailAction.Restore -> MailAction.Trash
    else -> null
}

/** The banner's past tense, in the same words for the same fact on both
 *  platforms: what the app just did to the selection. */
fun MailAction.pastLine(count: Int): String {
    // Each line is one whole sentence per count, so a language can inflect the
    // verb and the noun together; the English is unchanged.
    val noun = if (count == 1) "1 message" else "$count messages"
    return when (this) {
        MailAction.Archive -> CoreStrings.plural(R.plurals.core_past_archived, count, count) { "Archived $noun" }
        MailAction.Unarchive -> CoreStrings.plural(R.plurals.core_past_unarchived, count, count) { "Moved $noun back to the inbox" }
        MailAction.Spam -> CoreStrings.plural(R.plurals.core_past_spam, count, count) { "Reported $noun as spam" }
        MailAction.Unspam -> CoreStrings.plural(R.plurals.core_past_unspam, count, count) { "Moved $noun out of Spam" }
        MailAction.Trash -> CoreStrings.plural(R.plurals.core_past_trash, count, count) { "Moved $noun to Trash" }
        MailAction.Restore -> CoreStrings.plural(R.plurals.core_past_restored, count, count) { "Restored $noun" }
        else -> CoreStrings.plural(R.plurals.core_count_messages, count, count) { noun }
    }
}

/** Sort options. The server only ever orders by UID descending, so everything
 *  below `Newest` is applied by the client to the loaded page — which the sort
 *  sheet has to say out loud rather than imply. */
enum class MailSort { Newest, Oldest, SenderAsc, SenderDesc, SubjectAsc, SubjectDesc }

/** Labels for the sort sheet, in the wording the reference uses. */
val MailSort.title: String get() = when (this) {
    MailSort.Newest -> CoreStrings.get(R.string.core_sort_newest) { "Newest first" }
    MailSort.Oldest -> CoreStrings.get(R.string.core_sort_oldest) { "Oldest first" }
    MailSort.SenderAsc -> CoreStrings.get(R.string.core_sort_sender_asc) { "Sender (A → Z)" }
    MailSort.SenderDesc -> CoreStrings.get(R.string.core_sort_sender_desc) { "Sender (Z → A)" }
    MailSort.SubjectAsc -> CoreStrings.get(R.string.core_sort_subject_asc) { "Subject (A → Z)" }
    MailSort.SubjectDesc -> CoreStrings.get(R.string.core_sort_subject_desc) { "Subject (Z → A)" }
}

/**
 * True only for the server's own ordering. Everything else is applied by the
 * client to the pages already fetched, and the sheet says so — see
 * `MailboxViewModel.reproject`.
 */
val MailSort.isServerSide: Boolean get() = this == MailSort.Newest

/** The two filters `/stats` and `/inbox` can actually answer. */
enum class MailFilter { All, Unread }

val MailFilter.title: String get() = when (this) {
    MailFilter.All -> CoreStrings.get(R.string.core_filter_all) { "All" }
    MailFilter.Unread -> CoreStrings.get(R.string.core_filter_unread) { "Unread" }
}

/**
 * Scheduled send, the client half of backend `lib/mail-schedule.ts`.
 *
 * The server's rule, mirrored exactly so the compose box refuses what the route
 * would refuse instead of finding out from a 400:
 *
 *  - `sendAt` is an ISO 8601 timestamp WITH a zone (`Z` or `±hh:mm`). A bare
 *    local time is refused. This client always writes UTC (`...Z`), converted
 *    from the person's local date and time with java.time, so no time zone or
 *    DST rule is guessed on either side.
 *  - at least [MIN_AHEAD_MS] (one minute) after the server's "now";
 *  - at most [MAX_AHEAD_DAYS] days (exactly `30 * 86 400 000` ms) after it.
 *
 * A refused `sendAt` is never turned into an immediate send by the server, and
 * this client never does that either.
 */
object MailSchedule {
    const val MIN_AHEAD_MS: Long = 60_000L
    const val MAX_AHEAD_DAYS: Int = 30
    const val MAX_AHEAD_MS: Long = MAX_AHEAD_DAYS * 86_400_000L

    /**
     * The undo-send hold (logic/MailboxViewModel.send): the request leaves this
     * long after the tap, so a time is checked against "now + hold", the moment
     * the server will actually see it.
     */
    const val UNDO_WINDOW_MS: Long = 5_000L

    enum class Verdict { OK, TOO_SOON, TOO_FAR }

    /** The server's own comparison (`at < now + MIN` / `at > now + MAX`), in ms. */
    fun check(at: java.time.Instant, nowMs: Long): Verdict {
        val ms = at.toEpochMilli()
        return when {
            ms < nowMs + MIN_AHEAD_MS -> Verdict.TOO_SOON
            ms > nowMs + MAX_AHEAD_MS -> Verdict.TOO_FAR
            else -> Verdict.OK
        }
    }

    /** The latest instant the server accepts for a request made at [nowMs]. */
    fun latest(nowMs: Long): java.time.Instant = java.time.Instant.ofEpochMilli(nowMs + MAX_AHEAD_MS)

    /**
     * The wire form: UTC, whole seconds, no fraction (`2026-11-25T02:00:00Z`),
     * which the route's `ISO_WITH_ZONE` pattern accepts.
     */
    fun toWire(at: java.time.Instant): String =
        java.time.format.DateTimeFormatter.ISO_INSTANT.format(at.truncatedTo(java.time.temporal.ChronoUnit.SECONDS))

    /** Reads the wire form, or the server's `toISOString()` echo; null when unreadable. */
    fun parse(value: String?): java.time.Instant? {
        val text = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return runCatching { java.time.OffsetDateTime.parse(text).toInstant() }.getOrNull()
            ?: runCatching { java.time.Instant.parse(text) }.getOrNull()
    }
}
