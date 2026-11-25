package com.grapsee.grapmail.logic

import android.content.Intent
import android.net.Uri

/**
 * The links that open a place inside GrapMail, parsed in one place.
 *
 * ── THE FORMATS ─────────────────────────────────────────────────────────────
 *   grapmail://thread/<threadId>              a conversation
 *   grapmail://message/<messageId>            one message (opened on the message
 *                                             route — the thread route answers a
 *                                             message id with a 404)
 *   grapmail://scheduled                      the send-later list
 *   grapmail://inbox                          the mailbox home on the Inbox
 *                                             (no reader, no multi-select)
 *   grapmail://compose[?to=<address>]         a blank composer, optionally
 *                                             addressed
 *   https://grapsee.com/mail/thread/<id>      the same conversation as a web
 *                                             link (an App Link once
 *                                             `/.well-known/assetlinks.json`
 *                                             is live — see well-known/README.md)
 *   mailto:<address>[?…]                      a composer addressed to the first
 *                                             recipient; subject/body are not
 *                                             carried because the composer's
 *                                             route has no slot for them
 *
 * iOS parses the identical set in `ios/GrapMail/DeepLinks.swift`. The two are
 * kept in step by hand and by `mail-honesty.py` §39: a link that opens a thread
 * on one phone and the inbox on the other is a link nobody can share.
 *
 * ── WHY THE IDS ARE CHECKED SO STRICTLY ─────────────────────────────────────
 * A link is input from anywhere: another app, a web page, a QR code. Both ids
 * are Postgres `uuid` columns on the server (`grapmail_messages.id` and
 * `.thread_id`, `services/shared/src/db/schema.ts`), and the server mints them
 * with `crypto.randomUUID()`. So the only id a real link can carry is a
 * canonical 8-4-4-4-12 hex uuid. Anything else — a path traversal, a second
 * segment, a query smuggled into the id, a 10 kB string — is refused here and
 * the link does nothing, rather than reaching `Routes.mailThread`, the URL
 * builder in `Api.mailThread`, or a screen that would answer it with an error
 * page the person did not ask for. The id is lowercased so a link and a list
 * row name the same conversation the same way.
 *
 * ── WHAT A PARSED LINK IS NOT ───────────────────────────────────────────────
 * It is a request, not a navigation. `MainActivity` parks it; the graph opens
 * it only once a session exists AND the app lock's door is open (see
 * [AppLock.awaitOpen]). A link can never be a way around either.
 */
object DeepLinks {
    sealed interface Link {
        data class Thread(val threadId: String) : Link
        data class Message(val messageId: String) : Link
        data object Scheduled : Link
        /** The mailbox home on the Inbox: the graph pops to HOME, clears the
         *  two-pane reader and any multi-select, and switches the folder.
         *  Carries nothing (iOS: `DeepLink.inbox`). */
        data object Inbox : Link
        /** [to] is "" for a blank composer. */
        data class Compose(val to: String) : Link
    }

    const val SCHEME = "grapmail"

    /** The one web host whose links the app claims. It must match the
     *  manifest's autoVerify filter and the host that serves
     *  `/.well-known/assetlinks.json` — `www.` is deliberately absent: it would
     *  need its own copy of the file, and a host that fails verification
     *  quietly sends every link to the browser instead. */
    const val WEB_HOST = "grapsee.com"

    private val UUID_RE =
        Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")

    /** A deliberately plain address check: one @, no whitespace, no characters
     *  that would let a link smuggle a second recipient or a header, a dot in
     *  the domain, and the RFC 5321 length cap. The composer re-validates on
     *  send; this only decides whether the field is pre-filled at all. */
    private val ADDRESS_RE = Regex("^[^\\s@<>(),;:\"\\[\\]\\\\]+@[^\\s@<>(),;:\"\\[\\]\\\\]+\\.[^\\s@<>(),;:\"\\[\\]\\\\]+$")

    /** The canonical, lowercased mail id, or null for anything else. */
    fun mailId(raw: String?): String? = raw?.takeIf { UUID_RE.matches(it) }?.lowercase()

    fun address(raw: String?): String? =
        raw?.trim()?.takeIf { it.length <= 254 && ADDRESS_RE.matches(it) }

    /**
     * The link an incoming intent carries, or null. Only VIEW (a tapped link)
     * and SENDTO (another app's "send email") are links; the shortcut, widget
     * and notification intents keep their own explicit actions and extras and
     * are answered elsewhere in `MainActivity.takeComposeRequest`.
     */
    fun parse(intent: Intent?): Link? {
        intent ?: return null
        if (intent.action != Intent.ACTION_VIEW && intent.action != Intent.ACTION_SENDTO) return null
        return parse(intent.data)
    }

    fun parse(uri: Uri?): Link? {
        uri ?: return null
        return when (uri.scheme?.lowercase()) {
            SCHEME -> custom(uri)
            "https" -> web(uri)
            "mailto" -> mailto(uri)
            else -> null
        }
    }

    private fun custom(uri: Uri): Link? {
        val segments = uri.pathSegments.orEmpty()
        return when (uri.host?.lowercase()) {
            "thread" -> if (segments.size == 1) mailId(segments[0])?.let { Link.Thread(it) } else null
            "message" -> if (segments.size == 1) mailId(segments[0])?.let { Link.Message(it) } else null
            "scheduled" -> if (segments.isEmpty()) Link.Scheduled else null
            // Exactly `grapmail://inbox` (a trailing slash adds nothing); a path
            // under it is not a place this app has.
            "inbox" -> if (segments.isEmpty()) Link.Inbox else null
            "compose" -> {
                if (segments.isNotEmpty()) return null
                val to = runCatching { uri.getQueryParameter("to") }.getOrNull()
                // No `to` is a blank composer; a `to` that is not an address is
                // refused outright rather than silently opened blank — the
                // person tapped a link that promised a recipient.
                if (to.isNullOrBlank()) Link.Compose("") else address(to)?.let { Link.Compose(it) }
            }
            // "notification" is the data URI the notification's own explicit
            // intents carry for PendingIntent identity; it is not a link and
            // the manifest does not advertise it.
            else -> null
        }
    }

    private fun web(uri: Uri): Link? {
        if (uri.host?.lowercase() != WEB_HOST) return null
        if (uri.port != -1 || uri.userInfo != null) return null
        val segments = uri.pathSegments.orEmpty()
        if (segments.size != 3 || segments[0] != "mail" || segments[1] != "thread") return null
        return mailId(segments[2])?.let { Link.Thread(it) }
    }

    private fun mailto(uri: Uri): Link? {
        // `mailto:` is opaque — Uri does not split its query — so the first
        // recipient is everything before `?`, and before any `,` list.
        val first = uri.schemeSpecificPart.orEmpty()
            .substringBefore('?').substringBefore(',')
        if (first.isBlank()) return Link.Compose("")
        return address(Uri.decode(first))?.let { Link.Compose(it) }
    }
}
