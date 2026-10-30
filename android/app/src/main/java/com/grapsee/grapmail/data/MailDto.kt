package com.grapsee.grapmail.data

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
 * `publicRow` — `grapmail.ts:579-607`. The list shape. It deliberately carries
 * no body: a listing renders fifty previews and none of them shows a message, so
 * fetching bodies here would read fifty payloads to draw fifty rows.
 */
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
    val unsubscribe: String? = null,
    val isE2eEncrypted: Boolean = false,
    /** ISO instant. Null means the row has no trustworthy time to show. */
    val createdAt: String? = null,
) {
    /**
     * The line the row shows where a subject is absent. Not a guess about the
     * message — a statement that it has no subject.
     */
    val subjectOrNone: String get() = subject?.takeIf { it.isNotBlank() } ?: "(no subject)"
}

/**
 * `fullRow` — `grapmail.ts:609-623`, plus the attachment rows the single-message
 * route bolts on (`grapmail.ts:769-774`).
 */
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
    val unsubscribe: String? = null,
    val isE2eEncrypted: Boolean = false,
    val createdAt: String? = null,
    val bodyText: String? = null,
    val bodyHtml: String? = null,
    val inReplyTo: String? = null,
    val sizeBytes: Int? = null,
    /**
     * The sealed envelope, opaque to the server and returned byte for byte.
     * Nothing in the app decrypts it here — that is the E2E path's job — so it
     * is carried only so "is this encrypted" can be answered with the proof.
     */
    val e2eEnvelope: String? = null,
    val attachments: List<MailAttachmentDto> = emptyList(),
)

/**
 * `grapmail_attachments` rows — metadata only; the bytes live in object storage
 * and `storageKey` is server-generated so a client filename can never become a
 * path. Never sent to the UI: it is a server-side key, not a display field.
 */
@Serializable
data class MailAttachmentDto(
    val id: String = "",
    val messageId: String? = null,
    val name: String? = null,
    val mimeType: String? = null,
    val sizeBytes: Int? = null,
    val createdAt: String? = null,
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
 * `GET /grapmail/stats` — `grapmail.ts:664-668`.
 *
 * Exactly five boxes are reported. Starred, Archive and All Mail are not, so
 * they carry no badge — the same call the web client makes
 * (`src/app/(mail)/mail/page.tsx:2306`). `null` here means "the server did not
 * answer", and the UI must render nothing rather than `0`, because `0` is a
 * claim that the folder is empty.
 */
@Serializable
data class MailStatsResponse(
    val success: Boolean = false,
    val inbox: MailInboxStatsDto? = null,
    val sent: Int? = null,
    val drafts: Int? = null,
    val spam: Int? = null,
    val trash: Int? = null,
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
    /** Server-chosen colour, carried as a hex string and never re-derived. */
    val color: String? = null,
    val textColor: String? = null,
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

/** `GET /grapmail/aliases` — a second address delivering into this mailbox. */
@Serializable
data class MailAliasDto(
    val id: String = "",
    val label: String = "",
    val address: String = "",
    val isActive: Boolean = true,
    val createdAt: String? = null,
)

@Serializable
data class MailAliasesResponse(
    val success: Boolean = false,
    val aliases: List<MailAliasDto> = emptyList(),
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
    val apiValue: String, val title: String, val done: String, val note: String,
) {
    Approved("approved", "Approve", "Approved", "Their future mail goes straight to the inbox."),
    Muted("muted", "Mute", "Muted", "It arrives, but quietly — held out of the unread count."),
    Later("later", "Keep waiting", "Kept waiting", "Nothing is decided. It stays in this queue."),
    Blocked("blocked", "Block", "Blocked", "Future mail from them is refused at the door."),
}

/**
 * How far a decision reaches. `SCOPES` in the same file: `address` or `domain`.
 *
 * Domain is the one that can go wrong quietly — approving `example.com` admits
 * every mailbox at it, now and later, including addresses a stranger registers
 * next year. The sheet says so on the option rather than leaving the choice to
 * be inferred from a radio button.
 */
enum class MailSenderScope(val apiValue: String, val title: String, val warning: String) {
    Address("address", "Just this address", ""),
    Domain("domain", "The whole domain", "Applies to every address at this domain, including ones created later."),
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
 *  - There is no `snoozed` box. `snooze` writes `snoozedUntil`
 *    (`grapmail-archive.ts:171`) and the inbox hides those rows
 *    (`grapmail.ts:719-726`), so a snoozed message leaves the visible set with
 *    no way back. The action is offered; no box is claimed for it.
 */
enum class MailBox(val apiValue: String) {
    Inbox("inbox"),
    Starred("starred"),
    Sent("sent"),
    Drafts("drafts"),
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
        Inbox -> "Inbox"
        Starred -> "Starred"
        Sent -> "Sent"
        Drafts -> "Drafts"
        Archive -> "Archive"
        Spam -> "Spam"
        Trash -> "Trash"
        Screener -> "Pending approval"
        All -> "All Mail"
        None -> "Inbox"
    }

    companion object {
        /** The drawer's order — the boxes the reference lists, minus the ones the
         *  server cannot answer (see the README's honest-UI contract). */
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

/** Sort options. The server only ever orders by UID descending, so everything
 *  below `Newest` is applied by the client to the loaded page — which the sort
 *  sheet has to say out loud rather than imply. */
enum class MailSort { Newest, Oldest, SenderAsc, SenderDesc, SubjectAsc, SubjectDesc }

/** Labels for the sort sheet, in the wording the reference uses. */
val MailSort.title: String get() = when (this) {
    MailSort.Newest -> "Newest first"
    MailSort.Oldest -> "Oldest first"
    MailSort.SenderAsc -> "Sender (A → Z)"
    MailSort.SenderDesc -> "Sender (Z → A)"
    MailSort.SubjectAsc -> "Subject (A → Z)"
    MailSort.SubjectDesc -> "Subject (Z → A)"
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
    MailFilter.All -> "All"
    MailFilter.Unread -> "Unread"
}
