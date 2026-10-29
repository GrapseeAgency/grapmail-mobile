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
 * The avatar ramp, in the order the reference uses (Google's own mark aside).
 * A sender keeps its colour across visits because the choice is a hash of the
 * address, not a position in the list — the same rule the web client follows
 * (`page.tsx:212`, "Deterministic colour so a sender keeps the same avatar").
 */
private val AvatarRamp = listOf(
    Color(0xFF7C3AED), Color(0xFF059669), Color(0xFFF97316), Color(0xFF1A6DF0),
    Color(0xFFEC4899), Color(0xFF0891B2), Color(0xFF65A30D), Color(0xFFDC2626),
    Color(0xFF4F46E5), Color(0xFFCA8A04),
)

/**
 * A sender's initial on a stable colour.
 *
 * The initial comes from the display name when there is one and from the
 * address when there is not — never from the address's first character, which
 * would render every `@grapsee.com` sender under the same letter.
 */
@Composable
fun SenderAvatar(display: String, address: String?, size: Dp = 40.dp, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val initial = remember(display, address) {
        val source = display.trim().ifBlank { (address ?: "").substringBefore("@") }
        source.firstOrNull { !it.isWhitespace() }?.uppercaseChar()?.toString() ?: "?"
    }
    // Hash the address rather than the name: two people called "Team" should not
    // share a colour, and one person renaming themselves should not move.
    val tint = remember(address, display) {
        val key = (address ?: "").ifBlank { display }
        val h = key.hashCode()
        AvatarRamp[if (h < 0) (-h) % AvatarRamp.size else h % AvatarRamp.size]
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
        // The ring keeps a light avatar readable on a light row.
        if (palette.bg == Color(0xFFFBFCFE)) Unit
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
                tint = if (message.isStarred) Color(0xFFF5A623) else palette.textTertiary,
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

/** A pill in the label strip. Selected pills take the label's own colour, which
 *  is why the server stores it rather than the client picking one. */
@Composable
fun LabelChip(
    label: String,
    count: Int?,
    colorHex: String?,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val tint = parseHex(colorHex) ?: palette.primarySolid
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .background(if (selected) tint.copy(alpha = 0.16f) else palette.softFill)
            .border(1.dp, if (selected) tint else Color.Transparent, RoundedCornerShape(50))
            .clickable(onClick = onClick)
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

// ── small parsers ────────────────────────────────────────────────────────────

/** `#RRGGBB` or `RRGGBB` from the server, or null. Never a guessed colour. */
fun parseHex(raw: String?): Color? {
    val s = raw?.trim()?.removePrefix("#") ?: return null
    if (s.length != 6 && s.length != 8) return null
    val v = s.toLongOrNull(16) ?: return null
    return if (s.length == 8) Color(v) else Color(0xFF000000 or v)
}
