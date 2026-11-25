package com.grapsee.grapmail.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateIntAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Archive
import androidx.compose.material.icons.rounded.AttachFile
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.automirrored.rounded.Label
import androidx.compose.material.icons.rounded.MarkunreadMailbox
import androidx.compose.material.icons.rounded.Mail
import androidx.compose.material.icons.rounded.Menu
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MoveToInbox
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PersonAddAlt1
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarBorder
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.grapsee.grapmail.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.data.MailMessageDto
import com.grapsee.grapmail.ui.theme.AvatarInk
import com.grapsee.grapmail.ui.theme.avatarColor
import com.grapsee.grapmail.ui.theme.avatarFill
import com.grapsee.grapmail.ui.theme.avatarInitials
import com.grapsee.grapmail.ui.theme.LocalPalette
import com.grapsee.grapmail.data.parseMailInstant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

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
        // primaryText, not the filled-button cobalt: #2F66FF on the dark soft
        // fill is ~3:1, under AA for 11 sp text; #8EA8FF passes (round8 §1.2).
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = palette.primaryText)
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
 * A sender's avatar (round8-spec §1.6): their GrapMail photo or verified brand
 * logo when the server has one, GrapMail's initials disc otherwise.
 *
 * The disc colour is the shared slot `avatarColor` (ui/theme/AvatarColors.kt):
 * FNV-1a 32-bit over the normalised address, mod 10, into GrapMail's own
 * 10-hue palette. iOS and web implement the same function and assert the same
 * vectors, so one sender wears one colour on every platform. It replaces the
 * old `rampIndex` fold, whose slot the web never shared. The address is hashed
 * rather than the name: two people called "Team" should not share a colour, and
 * one person renaming themselves should not move. The name is the key only
 * when the server never named an address.
 *
 * The initials come from the display name when there is one and from the
 * address's local part when there is not. They never come from the address's
 * first character, which would put every `@grapsee.com` sender under the same
 * letter. Discs of 56 dp and up carry two letters when the name has two words.
 * With no letter at all, the person glyph is drawn.
 *
 * In a row the avatar is decorative (the row already announces the sender), so
 * the disc exposes nothing to TalkBack.
 */
@Composable
fun SenderAvatar(display: String, address: String?, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val twoLetters = size >= 56.dp
    val initials = remember(display, address, twoLetters) { avatarInitials(display, address, twoLetters) }
    // The appearance is a `remember` key because the resolved colour depends on it:
    // a disc that keeps its light-theme hue after the theme flips to dark is the
    // same defect as a text colour that does, only smaller. It is read from the
    // app's palette, so it follows the app theme rather than only the system's.
    val appearance = palette.isDark
    val fill = remember(address, display, appearance) {
        avatarFill(avatarColor(address, display, appearance))
    }
    // The sender's own photo, or their brand (BIMI) logo, when the server has
    // one (data/SenderPhotos.kt resolves both, logo on its plate). The disc is
    // drawn at once and stays under the image, so a slow or failed lookup looks
    // exactly like a sender with no picture.
    val context = androidx.compose.ui.platform.LocalContext.current
    val firstPhoto = remember(address) { com.grapsee.grapmail.data.SenderPhotos.cached(address) }
    val photo by androidx.compose.runtime.produceState(
        initialValue = firstPhoto,
        address,
    ) {
        val wanted = address?.trim().orEmpty()
        if (wanted.isNotEmpty() && value == null) {
            value = com.grapsee.grapmail.data.SenderPhotos.photo(context, wanted)
        }
    }
    val reduced = rememberMotionReduced()
    // Sized from the disc, not the font scale: the disc does not grow with text
    // either (§1.6.3), so the letters must not outgrow it.
    val glyphSize = with(androidx.compose.ui.platform.LocalDensity.current) {
        (size * (if (initials.size > 1) 0.36f else 0.42f)).toSp()
    }
    Box(
        modifier
            .size(size)
            .clip(CircleShape)
            .background(fill)
            // Dark theme only: a 1 dp white-at-10% hairline so the disc separates
            // from the card. Transparent in light.
            .border(1.dp, palette.avatarRing, CircleShape)
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center,
    ) {
        if (initials.isEmpty()) {
            Icon(Icons.Rounded.Person, contentDescription = null, tint = AvatarInk, modifier = Modifier.size(size * 0.5f))
        } else {
            Text(
                initials.joinToString(""),
                fontSize = glyphSize,
                fontWeight = FontWeight.SemiBold,
                color = AvatarInk,
                maxLines = 1,
                softWrap = false,
            )
        }
        val image = photo
        if (image != null) {
            // A picture that arrives after the first frame crossfades in over the
            // disc (150 ms). One already decoded for the first frame, or any
            // picture under reduced motion, simply appears.
            val fadeIn = !reduced && image !== firstPhoto
            val photoAlpha = remember(image) { Animatable(if (fadeIn) 0f else 1f) }
            LaunchedEffect(image) {
                if (fadeIn) photoAlpha.animateTo(1f, tween(GMMotion.ShortMs, easing = GMMotion.Standard))
            }
            androidx.compose.foundation.Image(
                bitmap = image,
                contentDescription = null,
                contentScale = androidx.compose.ui.layout.ContentScale.Crop,
                modifier = Modifier.size(size).clip(CircleShape).graphicsLayer { alpha = photoAlpha.value },
            )
        }
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
 *
 * `yesterday` is the word drawn for yesterday's mail; composable callers pass
 * the localized `R.string.mail_time_yesterday`. The default is the English
 * text, for callers outside composition.
 */
fun mailTime(iso: String?, now: Date = Date(), yesterday: String = "Yesterday"): String? {
    if (iso.isNullOrBlank()) return null
    // Mail settings → time zone and date format, when the account chose them;
    // the device's own otherwise. Read as snapshot state, so a row redraws the
    // moment either changes.
    val zone = com.grapsee.grapmail.data.MailDisplayPrefs.zone()
    val dateFormat = com.grapsee.grapmail.data.MailDisplayPrefs.dateFormat
    val locale = Locale.getDefault(Locale.Category.FORMAT)
    val today = now.toInstant().atZone(zone).toLocalDate()
    val cacheKey = "${zone.id}|${locale.toLanguageTag()}|$dateFormat|$today|$yesterday|$iso"
    formattedMailTimes.get(cacheKey)?.let { return it }

    val then = parseMailInstant(iso)?.atZone(zone) ?: return null
    val date = then.toLocalDate()
    val formatted = when {
        date == today -> shortMailTime.withLocale(locale).format(then)
        date == today.minusDays(1) -> yesterday
        date.year == today.year -> sameYearDate(dateFormat).withLocale(locale).format(then)
        else -> olderDate(dateFormat).withLocale(locale).format(then)
    }
    formattedMailTimes.put(cacheKey, formatted)
    return formatted
}

private val formattedMailTimes = android.util.LruCache<String, String>(2_048)
private val shortMailTime = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
private val olderMailDate = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM)
private val shortMailDate = DateTimeFormatter.ofPattern("MMM d")
private val fullMailDate = DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)

// The three orders Mail settings offers, matching its own examples
// ("25/10/26", "10/25/26", "2026-10-25") and the website's list.
private val dmyShort = DateTimeFormatter.ofPattern("d MMM")
private val ymdShort = DateTimeFormatter.ofPattern("MM-dd")
private val dmyOlder = DateTimeFormatter.ofPattern("dd/MM/yy")
private val mdyOlder = DateTimeFormatter.ofPattern("MM/dd/yy")
private val ymdOlder = DateTimeFormatter.ofPattern("yyyy-MM-dd")

private fun sameYearDate(format: String): DateTimeFormatter = when (format) {
    "dmy" -> dmyShort
    "ymd" -> ymdShort
    else -> shortMailDate
}

private fun olderDate(format: String): DateTimeFormatter = when (format) {
    "dmy" -> dmyOlder
    "mdy" -> mdyOlder
    "ymd" -> ymdOlder
    else -> olderMailDate
}

fun absoluteDateTimeOrRaw(iso: String): String {
    val instant = parseMailInstant(iso) ?: return iso
    val locale = Locale.getDefault(Locale.Category.FORMAT)
    val zone = com.grapsee.grapmail.data.MailDisplayPrefs.zone()
    val format = com.grapsee.grapmail.data.MailDisplayPrefs.dateFormat
    if (format.isEmpty()) return fullMailDate.withLocale(locale).withZone(zone).format(instant)
    val at = instant.atZone(zone)
    return olderDate(format).withLocale(locale).format(at) + ", " + shortMailTime.withLocale(locale).format(at)
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
    /**
     * The one-time sign-in code the list found in this row (CodeFinder, last
     * 60 minutes, never sealed mail — MailDigest in MorningCard.kt decides),
     * or null. Drawn as a "Copy 482913" chip beside the preview; hidden with
     * the preview (compact density) and while picking rows.
     */
    signInCode: String? = null,
) {
    val palette = LocalPalette.current
    val compact = com.grapsee.grapmail.data.MailDisplayPrefs.compact
    val reduced = rememberMotionReduced()
    // Keyed by the message, so the row's small animations only ever answer a
    // change to THIS message (read, starred) and never a row slot being
    // handed a different message.
    key(message.id) {
    val senderWeight = easedWeight(message.isRead, FontWeight.Medium, FontWeight.SemiBold, reduced)
    val subjectWeight = easedWeight(message.isRead, FontWeight.Normal, FontWeight.Medium, reduced)
    Row(
        modifier
            .fillMaxWidth()
            .background(if (selected) palette.infoFill else Color.Transparent)
            .combinedClickable(
                onClick = { if (selecting) onSelect() else onOpen() },
                onLongClick = onSelect,
            )
            // Mail settings → list density. Compact trims the row and drops the
            // preview line, so more messages fit on a screen.
            .padding(horizontal = 16.dp, vertical = if (compact) 7.dp else 12.dp),
        verticalAlignment = Alignment.Top,
    ) {
        // Selection checkbox or nothing — the unread dot moved to the time
        // column, where the reference puts it (beside the clock), so this slot
        // is only ever a picking affordance.
        if (selecting) {
            Box(Modifier.size(22.dp).clip(RoundedCornerShape(6.dp))
                .border(1.5.dp, if (selected) palette.primarySolid else palette.border, RoundedCornerShape(6.dp))
                .background(if (selected) palette.primarySolid else palette.card),
                contentAlignment = Alignment.Center) {
                if (selected) Icon(Icons.Rounded.Check, null, tint = palette.onPrimary, modifier = Modifier.size(15.dp))
            }
            Spacer(Modifier.width(10.dp))
        } else {
            Spacer(Modifier.width(4.dp))
        }

        SenderAvatar(message.from?.display ?: "", message.from?.address)
        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    message.from?.display?.takeIf { it.isNotBlank() } ?: stringResource(R.string.mail_unknown_sender),
                    fontSize = 15.sp,
                    fontWeight = senderWeight,
                    color = palette.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f),
                )
                Spacer(Modifier.width(8.dp))
                // The clock and the unread dot live in the right-hand column
                // now, the reference's own position: date at the top of the
                // row, dot beside it, star underneath. One fixed-width column
                // also means a long date can no longer squeeze the sender line.
            }
            Text(
                message.subjectOrNone,
                fontSize = 14.sp,
                fontWeight = subjectWeight,
                color = palette.textPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp),
            )
            // The code chip: only where the preview itself is shown, never on
            // sealed mail, never while picking (a tap there selects). At large
            // text it moves under the preview instead of squeezing it.
            val chipCode = signInCode?.takeIf {
                !compact && !selecting && !message.isE2eEncrypted && !message.snippet.isNullOrBlank()
            }
            val chipStacked = androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.3f
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp)) {
                if (!compact && !message.snippet.isNullOrBlank()) {
                    Text(
                        message.snippet!!,
                        fontSize = 13.sp,
                        color = palette.textSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                }
                if (chipCode != null && !chipStacked) {
                    Spacer(Modifier.width(6.dp))
                    SignInCodeChip(chipCode)
                }
                if (message.isE2eEncrypted) {
                    Text(stringResource(R.string.mail_row_encrypted_suffix), fontSize = 11.sp, color = palette.successInk, maxLines = 1)
                }
                // Server read-receipt status, present only on sent-list rows (routes/grapmail-sent-receipts.ts).
                if (message.direction == "outbound" && message.readReceiptStatus == "read") {
                    Text(stringResource(R.string.mail_row_receipt_read_suffix), fontSize = 11.sp, color = palette.successInk, maxLines = 1)
                } else if (message.direction == "outbound" && message.readReceiptStatus == "requested") {
                    Text(stringResource(R.string.mail_row_receipt_requested_suffix), fontSize = 11.sp, color = palette.textTertiary, maxLines = 1)
                }
            }
            if (chipCode != null && chipStacked) {
                SignInCodeChip(chipCode, Modifier.padding(top = 6.dp))
            }
        }

        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                UnreadDot(unread = !message.isRead, reduced = reduced)
                val time = mailTime(message.createdAt, yesterday = stringResource(R.string.mail_time_yesterday))
                if (time != null) {
                    Text(time, fontSize = 12.sp, color = palette.textTertiary, maxLines = 1)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (message.hasAttachments) {
                    Icon(Icons.Rounded.AttachFile, stringResource(R.string.mail_row_has_attachments), tint = palette.textTertiary,
                        modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(5.dp))
                }
                androidx.compose.material3.IconButton(
                    onClick = onStar,
                    modifier = Modifier.size(48.dp),
                ) {
                    AnimatedStarIcon(
                        starred = message.isStarred,
                        contentDescription = if (message.isStarred) stringResource(R.string.mail_action_unstar)
                            else stringResource(R.string.mail_action_star),
                        reduced = reduced,
                    )
                }
            }
        }
    }
    }
}

/**
 * "Copy 482913" — one tap puts the code on the clipboard (marked sensitive,
 * MailFeatureIntents.copySensitiveCode), plays a confirm haptic and turns the
 * chip into "Code copied" for a moment. It consumes its own tap, so the
 * email does not open. TalkBack hears "Copy sign-in code", never the code,
 * and then "Code copied" (polite live region). Grows with the text: the
 * label never ellipsizes, and the row stacks it under the preview at large
 * font sizes.
 */
@Composable
private fun SignInCodeChip(code: String, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val context = androidx.compose.ui.platform.LocalContext.current
    val view = androidx.compose.ui.platform.LocalView.current
    var copied by remember(code) { mutableStateOf(false) }
    LaunchedEffect(copied) {
        if (copied) {
            kotlinx.coroutines.delay(1_800)
            copied = false
        }
    }
    val copyLabel = stringResource(R.string.mailbox_feat_code_copy, code)
    val copyA11y = stringResource(R.string.mailbox_feat_code_copy_a11y)
    val copiedLabel = stringResource(R.string.mailbox_feat_code_copied)
    val clipLabel = stringResource(R.string.mailbox_feat_code_clip_label)
    val ink = if (copied) palette.successInk else palette.primaryText
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .clip(shape)
            .background(if (copied) palette.successChipBg else palette.primarySolid.copy(alpha = 0.10f))
            .border(1.dp, ink.copy(alpha = 0.45f), shape)
            .clickable(role = Role.Button, onClickLabel = copyA11y) {
                if (com.grapsee.grapmail.ui.mailfeatures.MailFeatureIntents.copySensitiveCode(context, clipLabel, code)) {
                    view.performHapticFeedback(
                        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R)
                            android.view.HapticFeedbackConstants.CONFIRM
                        else android.view.HapticFeedbackConstants.VIRTUAL_KEY,
                    )
                    copied = true
                }
            }
            .semantics {
                contentDescription = if (copied) copiedLabel else copyA11y
                liveRegion = androidx.compose.ui.semantics.LiveRegionMode.Polite
            }
            .heightIn(min = 32.dp)
            .padding(horizontal = 10.dp, vertical = 4.dp)
            .testTag("mail-row-code-chip"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (copied) Icons.Rounded.Check else Icons.Rounded.ContentCopy, null,
            tint = ink, modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(5.dp))
        Text(
            if (copied) copiedLabel else copyLabel,
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = ink,
            maxLines = 1, softWrap = false,
            modifier = Modifier.clearAndSetSemantics { },
        )
    }
}

/**
 * The row's text weight, eased between read and unread instead of popping.
 * Animates only when `read` changes; with reduced motion it is the plain value.
 * On a font with only static weights the step lands mid-way, which is still
 * softer than the old instant swap.
 */
@Composable
private fun easedWeight(read: Boolean, readWeight: FontWeight, unreadWeight: FontWeight, reduced: Boolean): FontWeight {
    val weight by animateIntAsState(
        targetValue = if (read) readWeight.weight else unreadWeight.weight,
        animationSpec = GMMotion.tween(reduced, GMMotion.MediumMs),
        label = "rowWeight",
    )
    return FontWeight(weight.coerceIn(1, 1000))
}

/** The unread dot beside the time: fades and shrinks out when the message is read. */
@Composable
private fun UnreadDot(unread: Boolean, reduced: Boolean) {
    val palette = LocalPalette.current
    if (reduced) {
        if (unread) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(6.dp).clip(CircleShape).background(palette.primarySolid))
                Spacer(Modifier.width(5.dp))
            }
        }
        return
    }
    AnimatedVisibility(
        visible = unread,
        enter = fadeIn(tween(GMMotion.ShortMs, easing = GMMotion.Standard)) +
            scaleIn(tween(GMMotion.MediumMs, easing = GMMotion.EmphasizedDecelerate), initialScale = 0.4f) +
            expandHorizontally(tween(GMMotion.MediumMs, easing = GMMotion.Standard), expandFrom = Alignment.Start),
        exit = fadeOut(tween(GMMotion.MediumMs, easing = GMMotion.Standard)) +
            scaleOut(tween(GMMotion.MediumMs, easing = GMMotion.Standard), targetScale = 0.4f) +
            shrinkHorizontally(tween(GMMotion.MediumMs, easing = GMMotion.Standard), shrinkTowards = Alignment.Start),
        label = "unreadDot",
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(6.dp).clip(CircleShape).background(palette.primarySolid))
            Spacer(Modifier.width(5.dp))
        }
    }
}

/**
 * The row's star. Starring pops it 1 -> 1.3 -> 1 (120 ms out, a damped spring
 * back) while the fill colour eases in; un-starring dips it gently to 0.85
 * and back. Only a real toggle animates: the first composition, a theme
 * change and reduced motion all just show the state.
 */
@Composable
private fun AnimatedStarIcon(starred: Boolean, contentDescription: String, reduced: Boolean) {
    val palette = LocalPalette.current
    val tint = if (starred) palette.star else palette.textSecondary
    val scale = remember { Animatable(1f) }
    val colour = remember { androidx.compose.animation.Animatable(tint) }
    var shown by remember { mutableStateOf(starred) }
    LaunchedEffect(starred, tint) {
        val toggled = shown != starred
        shown = starred
        if (!toggled || reduced) {
            scale.snapTo(1f)
            colour.snapTo(tint)
            return@LaunchedEffect
        }
        launch { colour.animateTo(tint, tween(GMMotion.MediumMs, easing = GMMotion.Standard)) }
        if (starred) {
            scale.animateTo(1.3f, tween(120, easing = GMMotion.EmphasizedDecelerate))
            scale.animateTo(1f, spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMedium))
        } else {
            scale.animateTo(0.85f, tween(110, easing = GMMotion.Standard))
            scale.animateTo(1f, tween(170, easing = GMMotion.Standard))
        }
    }
    Icon(
        if (starred) Icons.Rounded.Star else Icons.Rounded.StarBorder,
        contentDescription,
        tint = colour.value,
        modifier = Modifier.size(19.dp).graphicsLayer {
            scaleX = scale.value
            scaleY = scale.value
        },
    )
}

// ── header, tabs, chips ──────────────────────────────────────────────────────

// The time-of-day greeting composable lived here and was removed: nothing
// composed it — the home top bar is the drawer menu, the search field and the
// account avatar, which is the whole Gmail pattern and all the chrome a mail
// list needs. Decorative filler above the list pushes every row down on a
// screen the user opens to read mail. If a greeting is ever wanted again it
// must earn its place with content, not take it by default.

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

// ── compose affordance ───────────────────────────────────────────────────────

@Composable
fun BoxTab(
    title: String,
    icon: ImageVector,
    tint: Color,
    badgeColor: Color,
    count: Int?,
    active: Boolean,
    /**
     * The word drawn on the tab. `title` stays the English box key, because the
     * CI walk finds the tab by its `mail-box-tab-$title` tag in every locale.
     */
    label: String = title,
    onClick: () -> Unit,
) {
    val palette = LocalPalette.current
    Row(
        Modifier.testTag("mail-box-tab-$title")
            .clip(CircleShape)
            .background(if (active) tint.copy(alpha = 0.14f) else palette.card)
            .border(1.dp, if (active) tint else palette.border, CircleShape)
            .selectable(selected = active, role = Role.Tab, onClick = onClick)
            .height(44.dp)
            .padding(horizontal = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(17.dp))
        Text(label, color = palette.textPrimary, fontSize = 13.sp,
            fontWeight = FontWeight.Medium, softWrap = false, maxLines = 1)
        if (count != null && count > 0) {
            Text(
                if (count > 99) "99+" else count.toString(),
                color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                softWrap = false, maxLines = 1,
                modifier = Modifier.background(badgeColor, CircleShape)
                    .padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }
}

/**
 * The floating compose button.
 *
 * `testTag` is what the CI walk taps. A floating button found by coordinates is
 * a walk that breaks whenever the list scrolls, so it names itself.
 */
@Composable
fun MailFab(onClick: () -> Unit, modifier: Modifier = Modifier, expanded: Boolean = true) {
    val palette = LocalPalette.current
    val composeLabel = stringResource(R.string.mail_compose)
    Row(
        modifier
            .clip(RoundedCornerShape(24.dp))
            .background(palette.primarySolid)
            .clickable(role = Role.Button, onClick = onClick)
            .semantics { contentDescription = composeLabel }
            .animateContentSize(alignment = Alignment.CenterEnd)
            .height(48.dp)
            .padding(horizontal = if (expanded) 20.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Edit, null, tint = palette.onPrimary, modifier = Modifier.size(20.dp))
        if (expanded) {
            Spacer(Modifier.width(10.dp))
            Text(composeLabel, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = palette.onPrimary)
        }
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
        modifier.clickable(role = Role.Button, onClick = onClick)
            .heightIn(min = 48.dp)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = palette.textPrimary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.height(4.dp))
        // One line: the bar scrolls sideways when the actions outgrow it, so a
        // long translation never wraps into a column of letters.
        Text(label, fontSize = 11.sp, color = palette.textPrimary, maxLines = 1, softWrap = false)
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
    /// Selection-scoped read state, the same pair the web's bulk bar carries
    /// ("Mark read" / "Mark unread", page.tsx:2646-2647). The actions and the
    /// local flag writes existed all along; the buttons were never drawn, so
    /// mail the server marked unread with no open could never be marked back,
    /// and read mail could never go back to the unread count.
    onMarkRead: () -> Unit,
    onMarkUnread: () -> Unit,
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
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            // A real 48dp button: the bare 22dp glyph was too small a target.
            androidx.compose.material3.IconButton(onClick = onClose) {
                Icon(Icons.Rounded.Close, stringResource(R.string.mail_selection_close), tint = palette.textPrimary,
                    modifier = Modifier.size(22.dp))
            }
            Spacer(Modifier.width(4.dp))
            Text(pluralStringResource(R.plurals.mail_selection_count, count, count),
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // Seven or eight actions do not fit a 320-360 dp phone (nor the list
        // pane of the tablet layout): without the sideways scroll the last
        // ones, More (snooze) among them, were squeezed to nothing.
        Row(
            Modifier.fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 4.dp, vertical = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ToolAction(Icons.Rounded.Archive, stringResource(R.string.mail_action_archive), onArchive)
            ToolAction(Icons.Rounded.Delete, stringResource(R.string.mail_action_delete), onDelete)
            ToolAction(Icons.Rounded.MoveToInbox, stringResource(R.string.mail_action_move), onMove)
            ToolAction(Icons.AutoMirrored.Rounded.Label, stringResource(R.string.mail_action_label), onLabel)
            ToolAction(Icons.Rounded.DoneAll, stringResource(R.string.mail_action_mark_read), onMarkRead)
            ToolAction(Icons.Rounded.MarkunreadMailbox, stringResource(R.string.mail_action_mark_unread), onMarkUnread)
            onSender?.let { ToolAction(Icons.Rounded.PersonAddAlt1, stringResource(R.string.mail_action_sender), it) }
            ToolAction(Icons.Rounded.MoreVert, stringResource(R.string.mail_action_more), onMore)
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
    filtered: Boolean = false,
    boxTitle: String,
    onRetry: () -> Unit,
    /**
     * The box's name as drawn in the sentences below. `boxTitle` stays the
     * English key (the Inbox welcome is chosen by it); callers pass the
     * localized name here.
     */
    boxLabel: String = boxTitle,
    /**
     * The Inbox Zero moment (ui/mailfeatures/InboxZero.kt), drawn instead of
     * the welcome line when the unfiltered Inbox is empty. The caller passes
     * it only after a successful, online load; null keeps the old state.
     */
    inboxZero: (@Composable () -> Unit)? = null,
    content: @Composable () -> Unit,
) {
    val palette = LocalPalette.current
    when {
        !failed && !noMailbox && !empty -> content()

        // First load with nothing cached: rows shaped like the rows to come
        // (ScreenStates.kt), announced to TalkBack as the same sentence the
        // text placeholder used to show.
        loading -> ListSkeleton(Modifier.fillMaxSize(), rows = 8,
            label = stringResource(R.string.mail_state_loading))

        noMailbox -> CenteredState(
            stringResource(R.string.mail_state_no_mailbox_title),
            stringResource(R.string.mail_state_no_mailbox_body),
            palette,
        )

        failed -> CenteredState(
            stringResource(R.string.mail_state_failed_title, boxLabel),
            stringResource(R.string.mail_state_failed_body),
            palette, action = stringResource(R.string.mail_common_try_again) to onRetry,
        )

        empty && boxTitle == "Inbox" && !filtered && inboxZero != null -> inboxZero()

        empty && boxTitle == "Inbox" && !filtered -> CenteredState(
            stringResource(R.string.mail_state_welcome_title),
            stringResource(R.string.mail_state_welcome_body),
            palette,
        )

        else -> CenteredState(
            if (filtered) stringResource(R.string.mail_state_no_matches) else stringResource(R.string.mail_state_box_empty, boxLabel),
            if (filtered) stringResource(R.string.mail_state_no_matches_body) else stringResource(R.string.mail_state_box_empty_body),
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
    Box(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 12.dp, bottom = if (action != null) 146.dp else 12.dp), contentAlignment = Alignment.Center) {
        Column(Modifier.widthIn(max = 340.dp).verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                color = palette.textPrimary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            if (body != null) {
                Spacer(Modifier.height(8.dp))
                Text(body, fontSize = 14.sp, lineHeight = 21.sp, color = palette.textSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
            }
            if (action != null) {
                Spacer(Modifier.height(16.dp))
                OutlineButton(action.first, action.second)
            }
        }
    }
}

// ── label colours ────────────────────────────────────────────────────────────

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

/**
 * "Subscriber" next to a sender's name when the server says their account pays
 * for GrapMail. Deliberately not a check mark: paying is not verification.
 */
@Composable
fun SenderBadges(address: String?, modifier: Modifier = Modifier) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val badges by androidx.compose.runtime.produceState(initialValue = emptyList<String>(), address) {
        val wanted = address?.trim().orEmpty()
        value = if (wanted.isEmpty()) emptyList() else com.grapsee.grapmail.data.SenderPhotos.badges(context, wanted)
    }
    if ("subscriber" !in badges) return
    val palette = com.grapsee.grapmail.ui.theme.LocalPalette.current
    val badgeDescription = stringResource(R.string.mail_badge_subscriber_description)
    Text(
        stringResource(R.string.mail_badge_subscriber),
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        color = palette.primarySolid,
        maxLines = 1,
        modifier = modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, palette.primarySolid.copy(alpha = 0.5f), RoundedCornerShape(50))
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .semantics { contentDescription = badgeDescription },
    )
}

/**
 * A box's name as the person reads it. [com.grapsee.grapmail.data.MailBox.title]
 * stays the English key: test tags (`mail-box-tab-$title`) and the CI walk are
 * built from it, so only what is drawn goes through the string table.
 */
@Composable
fun com.grapsee.grapmail.data.MailBox.displayTitle(): String = stringResource(
    when (this) {
        com.grapsee.grapmail.data.MailBox.Inbox -> R.string.mail_box_inbox
        com.grapsee.grapmail.data.MailBox.Starred -> R.string.mail_box_starred
        com.grapsee.grapmail.data.MailBox.Sent -> R.string.mail_box_sent
        com.grapsee.grapmail.data.MailBox.Drafts -> R.string.mail_box_drafts
        com.grapsee.grapmail.data.MailBox.Snoozed -> R.string.mail_box_snoozed
        com.grapsee.grapmail.data.MailBox.Archive -> R.string.mail_box_archive
        com.grapsee.grapmail.data.MailBox.Spam -> R.string.mail_box_spam
        com.grapsee.grapmail.data.MailBox.Trash -> R.string.mail_box_trash
        com.grapsee.grapmail.data.MailBox.Screener -> R.string.mail_box_screener
        com.grapsee.grapmail.data.MailBox.All -> R.string.mail_box_all
        com.grapsee.grapmail.data.MailBox.None -> R.string.mail_box_inbox
    },
)
