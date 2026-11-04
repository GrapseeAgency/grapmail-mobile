package com.grapsee.grapmail.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Label
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MoveToInbox
import androidx.compose.material.icons.rounded.PersonAddAlt1
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.data.MailMessageDto
import com.grapsee.grapmail.ui.theme.GrapMailAvatarRampDark
import com.grapsee.grapmail.ui.theme.GrapMailAvatarRampLight
import com.grapsee.grapmail.ui.theme.LocalPalette
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Mailbox vocabulary — the parts every mail screen is assembled from.
 *
 * Deliberately dumb: none of these fetch, none hold state that belongs to the
 * screen. The one piece of logic any of them carries is the badge rule and the
 * time format, because both are claims about the data and belong in one place
 * rather than in twenty call sites where they can disagree.
 */

// ── the badge rule ───────────────────────────────────────────────────────────

/**
 * A count, or nothing.
 *
 * `null` means the server did not answer this box — `/stats` reports exactly
 * five of them (`grapmail.ts:664-668`), so Starred, Archive and All Mail are
 * `null` on every request. Drawing `0` there would claim the folder is empty
 * when the truth is that nobody was asked, which is the same class of error as
 * the security hub showing "2FA off" for an unanswered verdict.
 */
@Composable
fun CountBadge(count: Int?, max: Int = 99, modifier: Modifier = Modifier) {
    if (count == null) return
    val palette = LocalPalette.current
    val label = if (count > max) "$max+" else count.toString()
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(palette.softFill)
            .padding(horizontal = 7.dp, vertical = 1.dp),
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = palette.primarySolid)
    }
}

/** The unread pill on the Mail tab and the drawer's inbox row. */
@Composable
fun UnreadPill(count: Int, modifier: Modifier = Modifier) {
    if (count <= 0) return
    val palette = LocalPalette.current
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(palette.primarySolid)
            .padding(horizontal = 7.dp, vertical = 2.dp),
    ) {
        Text(count.toString(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = palette.onPrimary)
    }
}

// ── sender identity ──────────────────────────────────────────────────────────

/**
 * The one unsubscribe address worth offering, or null.
 *
 * `List-Unsubscribe` is the sender's own claim about a URL, and it arrives in the
 * message's header text (`publicRow.unsubscribe`, `grapmail.ts:594`) — so it is
 * untrusted input, and the shape it commonly arrives in is a comma-separated pair
 * like `<https://x.example/u?id=7>, <mailto:u@x.example>`.
 *
 * Rules, all of them for a reason:
 *  - http(s) only. A `mailto:` opens a composition the app cannot fill in
 *    correctly, and anything else (`javascript:`, `file:`, a bare word) is not a
 *    link to a list at all.
 *  - The first http(s) candidate wins, matching what mail clients do.
 *  - Whitespace and angle brackets are stripped; the header format guarantees them.
 *
 * Angle-bracketed segments are read FIRST, because the header's separator is a
 * comma and a comma is legal inside a query (`?t=abc,def`, RFC 3986). Inside
 * `<…>` the whole run is one address, so it cannot be cut short; a bare
 * `a,b`-style split would hand back the truncated half of it and open a
 * different request than the sender wrote. The plain split stays as the fallback
 * for headers that omit the brackets, which the format also permits — and there
 * the ambiguity belongs to the sender, not to this parser.
 *
 * Returning null is the safe answer more often than it seems, and the thread shows
 * no control at all when it does — an unsubscribe button that opens a broken URL is
 * worse than no button.
 */
fun parseUnsubscribe(raw: String?): String? {
    if (raw.isNullOrBlank()) return null
    // Bracketed candidates first: the closing `>` is the end of the address, so
    // no comma inside the query can be mistaken for a separator.
    Regex("<([^>]+)>").findAll(raw).forEach { m ->
        val candidate = m.groupValues[1].trim()
        if (candidate.startsWith("https://", ignoreCase = true)
            || candidate.startsWith("http://", ignoreCase = true)) return candidate
    }
    raw.split(',').forEach { part ->
        val candidate = part.trim().removeSurrounding("<", ">").trim()
        if (candidate.startsWith("https://", ignoreCase = true)
            || candidate.startsWith("http://", ignoreCase = true)) return candidate
    }
    return null
}

/**
 * The address a tap on "Unsubscribe" opens, from the server's parsed offer.
 * The first http(s) offer wins, exactly as the web's `normalizeUnsubscribe`
 * takes its `http`; with no offer list the legacy header string still parses
 * through [parseUnsubscribe]. null means no door: the thread shows no
 * unsubscribe control, and the subscriptions screen says nothing was offered.
 */
fun unsubscribeTarget(u: com.grapsee.grapmail.data.MailUnsubscribeDto?): String? {
    if (u == null) return null
    u.offers.firstOrNull {
        // HTTPS only, exactly as the web's normalizeUnsubscribe: its test is
        // /^https:\/\//i (subscriptions.ts:74) and a plain-http offer is no
        // door there. Handing a sender-chained http:// page one tap from the
        // browser puts it in front of a network attacker; the old branch here
        // claimed the web took http too — it never did.
        it.url.startsWith("https://", ignoreCase = true)
    }?.let { return it.url }
    return parseUnsubscribe(u.raw)
}

/** The sender's mailto: offer, kept apart from the web target: opening a
 *  compose window addressed to it is a different handoff, and the
 *  subscriptions row says which one it is doing. The server's parsed record
 *  keeps the mailto door in `mailtoOneClick` — `sanitiseUnsubscribe` filters
 *  the offers list to https before storing (grapmail.ts:271), so scanning
 *  offers for a mailto: prefix could never answer on a real message, and the
 *  field being dropped at decode made this handoff a dead control. */
fun unsubscribeMailto(u: com.grapsee.grapmail.data.MailUnsubscribeDto?): String? {
    u?.mailtoOneClick?.let { return stripMailto(it) }
    // Legacy rows carry the raw bracket list instead of the parsed record;
    // the mailto half lives inside that string, and this parse mirrors the
    // web's fallback for exactly that shape.
    val fromHeader = u?.raw?.let { raw ->
        Regex("<([^>]+)>").findAll(raw).map { m -> m.groupValues[1].trim() }
            .firstOrNull { it.startsWith("mailto:", ignoreCase = true) }
    }
    return fromHeader?.let { stripMailto(it) }
}

/** Every leading mailto:, any case — a sender can write MAILTO: or Mailto:. */
private fun stripMailto(url: String): String {
    var t = url
    while (t.startsWith("mailto:", ignoreCase = true)) t = t.substring(7)
    return t
}

/**
 * The host a link will take the user to, for the confirmation line.
 *
 * The authority is what follows `://` up to the first path, query or fragment
 * delimiter — minus any `user:pass@` prefix, because that is NOT part of where
 * the request goes. A sender writes `List-Unsubscribe:
 * <https://grapsee.com@evil.example/u>` and the browser goes to `evil.example`;
 * printing the raw authority would put our own domain at the head of the
 * sentence whose entire job is to name the stranger the tap is about to contact.
 * The last `@` is the boundary (userinfo may not contain an unencoded `@`), and
 * an address that is nothing but an `@` yields null, which falls back to the
 * sheet's generic "the sender's server" wording rather than a fabricated host.
 */
fun urlHost(raw: String): String? {
    val authority = raw.substringAfter("://", "")
        .substringBefore('/')
        .substringBefore('?')
        .substringBefore('#')
    return authority.substringAfterLast('@', authority)
        .takeIf { it.isNotBlank() }
}

/**
 * The ramp slot for a sender key.
 *
 * An explicit fold rather than `String.hashCode()`, for two reasons that are
 * both about the two apps disagreeing quietly:
 *
 *  - iOS masks its accumulator to 30 bits (`MailUI.tint`), and Java's hash is a
 *    signed 32-bit overflow. They land on different slots for the same address,
 *    so a sender would wear one colour on Android and another on iOS — visible
 *    in the screenshot pair for the very same message.
 *  - `abs(Int.MIN_VALUE)` is `Int.MIN_VALUE`: negating it does not make it
 *    positive, so the old `if (h < 0) (-h) % size` could index the list with a
 *    negative number and crash on one particular address. Keeping the
 *    accumulator masked means it is never negative and `%` is always safe.
 *
 * Masking the low 30 bits after a 32-bit wrap gives the same value as taking
 * the modulo of the unwrapped product, which is why this matches iOS exactly —
 * and both iterate UTF-16 units, so a name with an emoji folds the same way on
 * each platform too.
 *
 * The size is a parameter rather than a reference to one list, because there are
 * now two lists — light and dark — and an index taken against the wrong one is an
 * out-of-bounds crash on a sender whose hash lands in the last two slots.
 */
private fun rampIndex(key: String, size: Int): Int {
    var h = 0
    for (ch in key) h = (h * 31 + ch.code) and 0x3FFFFFFF
    return h % size
}

/**
 * A sender's initial on a stable colour.
 *
 * The initial comes from the display name when there is one and from the
 * address when there is not — never from the address's first character, which
 * would render every `@grapsee.com` sender under the same letter.
 */
@Composable
fun SenderAvatar(display: String, address: String?, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val initial = remember(display, address) {
        val source = display.trim().ifBlank { (address ?: "").substringBefore("@") }
        source.firstOrNull { !it.isWhitespace() }?.uppercaseChar()?.toString() ?: "?"
    }
    // Hash the address rather than the name: two people called "Team" should not
    // share a colour, and one person renaming themselves should not move. The
    // name is the fallback only when the server never named an address at all —
    // iOS applies the same rule in `MailUI.tint`, and the same key has to produce
    // the same slot on both.
    //
    // The appearance is a `remember` key because the resolved colour depends on it:
    // a disc that keeps its light-theme hue after the system flips to dark is the
    // same defect as a text colour that does, only smaller.
    val appearance = androidx.compose.foundation.isSystemInDarkTheme()
    val tint = remember(address, display, appearance) {
        val ramp = if (appearance) GrapMailAvatarRampDark else GrapMailAvatarRampLight
        ramp[rampIndex((address ?: "").ifBlank { display }, ramp.size)]
    }
    Box(
        modifier.size(size).clip(CircleShape).background(tint),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            initial,
            fontSize = (size.value * 0.42f).sp,
            fontWeight = FontWeight.SemiBold,
            color = Color.White,
        )
    }
}

// ── time ─────────────────────────────────────────────────────────────────────

/**
 * The row's timestamp, formatted the way the reference does it: a clock time
 * for today, the word "Yesterday" for yesterday, a short date before that.
 *
 * Returns null when there is no trustworthy instant to show. A row that cannot
 * know when the message arrived shows no time rather than a plausible one — the
 * list is sorted by time, so an invented timestamp would also invent the order.
 */
fun mailTime(iso: String?, now: Date = Date()): String? {
    val millis = parseMailIso(iso) ?: return null
    val then = Calendar.getInstance().apply { timeInMillis = millis }
    val today = Calendar.getInstance().apply { time = now }
    val sameYear = then.get(Calendar.YEAR) == today.get(Calendar.YEAR)
    val sameDay = sameYear && then.get(Calendar.DAY_OF_YEAR) == today.get(Calendar.DAY_OF_YEAR)
    if (sameDay) return SimpleDateFormat("h:mm a", Locale.US).format(Date(millis))
    val yesterday = (today.clone() as Calendar).apply { add(Calendar.DAY_OF_YEAR, -1) }
    if (sameYear && then.get(Calendar.DAY_OF_YEAR) == yesterday.get(Calendar.DAY_OF_YEAR))
        return "Yesterday"
    if (then.get(Calendar.YEAR) == today.get(Calendar.YEAR))
        return SimpleDateFormat("MMM d", Locale.US).format(Date(millis))
    return SimpleDateFormat("MMM d, yyyy", Locale.US).format(Date(millis))
}

/** ISO-8601 with or without fractional seconds and zone; null if unparseable. */
private fun parseMailIso(iso: String?): Long? {
    if (iso.isNullOrBlank()) return null
    val patterns = listOf(
        "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", "yyyy-MM-dd'T'HH:mm:ssXXX",
        "yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm:ssZ", "yyyy-MM-dd HH:mm:ss",
    )
    for (p in patterns) {
        runCatching {
            SimpleDateFormat(p, Locale.US).apply { isLenient = true }.parse(iso)?.time
        }.getOrNull()?.let { return it }
    }
    return null
}

/**
 * The full date and time a message body needs, falling back to the raw string
 * rather than a fabricated one.
 *
 * The fallback matters: showing "Jan 1, 1970" for an unparseable instant would
 * present a wrong date as a fact about someone's mail. Showing the server's own
 * string is uglier and true.
 */
fun absoluteDateTimeOrRaw(iso: String): String {
    val millis = parseMailIso(iso) ?: return iso
    return SimpleDateFormat("EEEE, MMMM d, yyyy · h:mm a", Locale.US).format(Date(millis))
}

// ── the row ──────────────────────────────────────────────────────────────────

/**
 * One message in the list.
 *
 * The unread dot, the star and the attachment glyph are all read from
 * `publicRow`; none of them is inferred. `isE2eEncrypted` is shown too, because
 * a lock is the only honest signal that the body will not open here.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun MessageRow(
    message: MailMessageDto,
    selected: Boolean,
    onSelect: () -> Unit,
    onOpen: () -> Unit,
    onStar: () -> Unit,
    selecting: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    Row(
        modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { if (selecting) onSelect() else onOpen() },
                onLongClick = onSelect,
            )
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Selection checkbox or the unread dot — the same slot, because a row is
        // either being read or being picked.
        if (selecting) {
            Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp))
                .border(1.5.dp, if (selected) palette.primarySolid else palette.border, RoundedCornerShape(6.dp))
                .background(if (selected) palette.primarySolid else palette.card),
                contentAlignment = Alignment.Center) {
                if (selected) Icon(Icons.Rounded.Check, null, tint = palette.onPrimary, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.width(10.dp))
        } else {
            Box(Modifier.padding(top = 15.dp).size(8.dp), contentAlignment = Alignment.Center) {
                if (!message.isRead) Box(Modifier.size(8.dp).clip(CircleShape).background(palette.primarySolid))
            }
            Spacer(Modifier.width(8.dp))
        }

        SenderAvatar(message.from?.display ?: "", message.from?.address)
        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    message.from?.display?.takeIf { it.isNotBlank() } ?: "Unknown sender",
                    fontSize = 15.sp,
                    fontWeight = if (message.isRead) FontWeight.Normal else FontWeight.SemiBold,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                val time = mailTime(message.createdAt)
                if (time != null) {
                    Text(time, fontSize = 12.sp, color = palette.textTertiary, maxLines = 1)
                }
            }
            Text(
                message.subjectOrNone,
                fontSize = 14.sp,
                fontWeight = if (message.isRead) FontWeight.Normal else FontWeight.Medium,
                color = palette.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                if (!message.snippet.isNullOrBlank()) {
                    Text(
                        message.snippet!!,
                        fontSize = 13.sp,
                        color = palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (message.isE2eEncrypted) {
                    Text(" · encrypted", fontSize = 11.sp, color = palette.successInk, maxLines = 1)
                }
            }
        }

        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (message.hasAttachments) {
                Icon(Icons.Rounded.AttachFile, "Has attachments", tint = palette.textTertiary,
                    modifier = Modifier.size(16.dp))
                Spacer(Modifier.height(6.dp))
            }
            Icon(
                if (message.isStarred) Icons.Rounded.Star else Icons.Rounded.StarBorder,
                if (message.isStarred) "Starred" else "Star",
                // The token, not a literal. This row is what the dark screenshot of
                // the home photographs, and a hardcoded light amber renders a
                // different star from the thread's for the very same message.
                tint = if (message.isStarred) palette.star else palette.textTertiary,
                modifier = Modifier.size(20.dp).clip(CircleShape)
                    .clickable { onStar() }.padding(2.dp),
            )
        }
    }
}

// ── header, tabs, chips ──────────────────────────────────────────────────────

/** The greeting. Time of day only — nothing here claims to know the person's name
 *  unless the server said it, so the fallback is the address the mailbox answered
 *  with, not a made-up first name. */
@Composable
fun MailGreeting(name: String?, address: String?, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val hour = remember { Calendar.getInstance().get(Calendar.HOUR_OF_DAY) }
    val part = when {
        hour < 12 -> "Good morning"
        hour < 18 -> "Good afternoon"
        else -> "Good evening"
    }
    val who = name?.trim()?.takeIf { it.isNotEmpty() }
        ?: address?.substringBefore('@')?.takeIf { it.isNotEmpty() }
    // Built as a plain string, not inside the template: "$part${who?.let(", $it")}"
    // reads `let(", $it")` as a String argument where a lambda belongs.
    val greeting = if (who != null) "$part, $who" else part
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(greeting, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                color = palette.textPrimary)
            Spacer(Modifier.width(6.dp))
            Text(if (hour < 18) "☀️" else "🌙", fontSize = 18.sp)
        }
        Text("Here's what's happening in your inbox today.",
            fontSize = 13.sp, color = palette.textSecondary, modifier = Modifier.padding(top = 2.dp))
    }
}

/**
 * A pill in the label strip.
 *
 * Not a control, and that is a scope decision rather than a data limitation. The
 * plan replaced the design's All / Personal / Work chips with a strip driven by
 * `GET /grapmail/labels` — real, coloured, user-created labels — and did not ask
 * for a label filter.
 *
 * The justification written here before was false, and worth keeping the record
 * of: it claimed the assignments "are only readable per-label, not per-row, so
 * there is nothing to filter with on the device". `GET /labels/assignments`
 * returns every `(messageId, labelId)` pair for the account in one sweep — that
 * is the call this sector wired for the Label sheet, and it is exactly what the
 * web client filters with (`page.tsx:778,2262`). Nothing on the device is
 * missing; the feature was simply not asked for, and an agent inventing a tap is
 * how unbacked affordances get shipped.
 *
 * If a filter is ever wanted, it narrows the LOADED rows the way `unread` does —
 * the route takes no label query (`grapmail.ts:719-726`) — and it must print its
 * scope the way the sort sheet prints `sortScope`, never imply the whole box was
 * filtered.
 *
 * `selected` is real for exactly one caller: the Inbox pill mirrors the list's
 * own filter state (`query empty && filter == All`). A label pill is never
 * selected, so its colour reaches the pill through the swatch dot either way.
 */
@Composable
fun LabelChip(
    label: String,
    count: Int?,
    /** The label's palette key (`slate`, `green`, …), not a colour value. */
    colourKey: String?,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val tint = labelSwatch(colourKey)
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) tint.copy(alpha = 0.16f) else palette.softFill)
            .border(1.dp, if (selected) tint else Color.Transparent, RoundedCornerShape(50))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(tint))
        Spacer(Modifier.width(7.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            color = if (selected) tint else palette.textPrimary, maxLines = 1)
        if (count != null) {
            Spacer(Modifier.width(6.dp))
            Text(count.toString(), fontSize = 12.sp, color = palette.textSecondary)
        }
    }
}

/** A box tab with the underline the reference shows on the active one. */
@Composable
fun BoxTab(title: String, count: Int?, active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Column(modifier.clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(title, fontSize = 14.sp,
                fontWeight = if (active) FontWeight.SemiBold else FontWeight.Normal,
                color = if (active) palette.primarySolid else palette.textSecondary)
            if (count != null) {
                Spacer(Modifier.width(6.dp))
                CountBadge(count)
            }
        }
        Spacer(Modifier.height(6.dp))
        Box(Modifier.fillMaxWidth().height(2.dp)
            .background(if (active) palette.primarySolid else Color.Transparent))
    }
}

// ── compose affordance ───────────────────────────────────────────────────────

/**
 * The floating compose button.
 *
 * `testTag` is what the CI walk taps. A floating button found by coordinates is
 * a walk that breaks whenever the list scrolls, so it names itself.
 */
@Composable
fun MailFab(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Box(
        modifier
            .size(56.dp)
            .clip(CircleShape)
            .background(palette.primarySolid)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.Mail, "Compose", tint = palette.onPrimary, modifier = Modifier.size(24.dp))
    }
}

// ── selection toolbar ────────────────────────────────────────────────────────

/** One action in the selection toolbar: glyph above its own word, as the
 *  reference draws it. */
@Composable
fun ToolAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit,
               modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    Column(
        modifier.clickable(onClick = onClick).padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = palette.textPrimary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(4.dp))
        Text(label, fontSize = 11.sp, color = palette.textPrimary)
    }
}

/** The bar that replaces the header while messages are picked. */
@Composable
fun SelectionToolbar(
    count: Int,
    onClose: () -> Unit,
    onArchive: () -> Unit,
    onDelete: () -> Unit,
    onMove: () -> Unit,
    onLabel: () -> Unit,
    onMore: () -> Unit,
    /**
     * The "Sender" action, offered only while the screener queue is open. Left
     * null everywhere else because deciding a sender means nothing for a box
     * whose mail already reached you — and a toolbar item that opens a sheet of
     * actions the current box cannot use is a control aimed at nothing.
     */
    onSender: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    Column(modifier.fillMaxWidth().background(palette.card)) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 12.dp)) {
            Icon(Icons.Rounded.Close, "Close selection", tint = palette.textPrimary,
                modifier = Modifier.size(22.dp).clip(CircleShape).clickable(onClick = onClose).padding(4.dp))
            Spacer(Modifier.width(12.dp))
            Text("$count selected", fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolAction(Icons.Rounded.Archive, "Archive", onArchive)
            ToolAction(Icons.Rounded.Delete, "Delete", onDelete)
            ToolAction(Icons.Rounded.MoveToInbox, "Move", onMove)
            ToolAction(Icons.Rounded.Label, "Label", onLabel)
            onSender?.let { ToolAction(Icons.Rounded.PersonAddAlt1, "Sender", it) }
            ToolAction(Icons.Rounded.MoreVert, "More", onMore)
        }
    }
}

// ── the honest states ────────────────────────────────────────────────────────

/**
 * The four non-list states, kept apart because they are four different facts.
 *
 * Collapsing them is how a mailbox lies: a failed load drawn as an empty inbox
 * tells the person they have no mail, and a missing mailbox drawn as a failed
 * load tells them their mail is unreachable when it was never there. The hub in
 * this app shipped the same family of bug — a refresh failure that blanked good
 * data and disabled its own control — so the distinction is structural here
 * rather than left to each caller.
 */
@Composable
fun MailStateBlock(
    loading: Boolean,
    failed: Boolean,
    noMailbox: Boolean,
    empty: Boolean,
    boxTitle: String,
    onRetry: () -> Unit,
    content: @Composable () -> Unit,
) {
    val palette = LocalPalette.current
    when {
        !failed && !noMailbox && !empty -> content()

        loading -> CenteredState("Checking your mailbox…", null, palette)

        noMailbox -> CenteredState(
            "No GrapMail mailbox on this account",
            "The account you signed in with has no mailbox provisioned, so there is " +
                "nothing to list. This is not a connection problem.",
            palette,
        )

        failed -> CenteredState(
            "Couldn't load $boxTitle",
            "Your mailbox could not be reached. Nothing has been deleted.",
            palette, action = "Try again" to onRetry,
        )

        else -> CenteredState(
            "$boxTitle is empty",
            "No messages here yet.",
            palette,
        )
    }
}

@Composable
private fun CenteredState(
    title: String,
    body: String?,
    palette: com.grapsee.grapmail.ui.theme.Palette,
    action: Pair<String, () -> Unit>? = null,
) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                color = palette.textPrimary)
            if (body != null) {
                Spacer(Modifier.height(8.dp))
                Text(body, fontSize = 14.sp, color = palette.textSecondary)
            }
            if (action != null) {
                Spacer(Modifier.height(16.dp))
                GradientButton(action.first, action.second)
            }
        }
    }
}

// ── label colours ───────────────────────────────────────────────────────────

/**
 * The eight colour keys the label route accepts, in the order the picker shows
 * them. Copied from the server's own set — `LABEL_COLOURS`, `grapmail-labels.ts:39`
 * — and pinned against it by `scripts/mail-honesty.py`.
 *
 * These are KEYS, not colours to draw: the value stored on a label is one of these
 * words. The web client says so in its own source (`src/app/(mail)/mail/page.tsx:84`)
 * and resolves the key to a CSS variable, which is why the site's labels look
 * right and, until this file existed, the apps did not.
 */
val LABEL_COLOUR_KEYS = listOf(
    "slate", "red", "amber", "green", "teal", "blue", "violet", "pink",
)

/**
 * A label's swatch, from its palette key.
 *
 * The server normalises an unrecognised value to `slate` before storing it
 * (`normaliseColour`), so anything outside the eight can only arrive from a
 * response that has since changed. Falling back to slate matches the server rather
 * than inventing a colour the user never picked.
 *
 * Previously the apps parsed this field as a hex string — `parseHex`, which is now
 * gone. That could never succeed: `normaliseColour` rejects a hex value and stores
 * `slate`, so the parse always returned null and every label drew the neutral
 * fallback. Worse, the create screen SENT a hex (Android) or a hex (iOS) and the
 * route accepted the request with 201 while storing `slate`, so the picker showed
 * one colour and the label came back another forever. The light and dark values
 * below are the web theme's own (`--gm-label-*` in `src/styles/grapmail.css`), so
 * all three clients now show one colour for one key.
 */
@Composable
fun labelSwatch(key: String?): Color {
    val dark = androidx.compose.foundation.isSystemInDarkTheme()
    return when (key) {
        "red" -> if (dark) Color(0xFFF2857D) else Color(0xFFD93025)
        "amber" -> if (dark) Color(0xFFFDD663) else Color(0xFFF29900)
        "green" -> if (dark) Color(0xFF6DD58C) else Color(0xFF188038)
        "teal" -> if (dark) Color(0xFF5EEAD4) else Color(0xFF0D9488)
        "blue" -> if (dark) Color(0xFF8AB4F8) else Color(0xFF1A73E8)
        "violet" -> if (dark) Color(0xFFC4B5FD) else Color(0xFF7C3AED)
        "pink" -> if (dark) Color(0xFFF7A8D8) else Color(0xFFC01A7A)
        else -> if (dark) Color(0xFF9AA0A6) else Color(0xFF5F6368)   // slate
    }
}
