package com.grapsee.grapmail.ui.screens.mail

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.Forward
import androidx.compose.material.icons.automirrored.rounded.Reply
import androidx.compose.material.icons.automirrored.rounded.ReplyAll
import androidx.compose.material.icons.rounded.Group
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.UnfoldMore
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.R
import com.grapsee.grapmail.data.MailMessageFullDto
import com.grapsee.grapmail.ui.components.SenderAvatar
import com.grapsee.grapmail.ui.components.mailTime
import com.grapsee.grapmail.ui.theme.LocalPalette

/**
 * The conversation view's pieces (ThreadScreen.kt composes them):
 *
 *  - [ConversationExpansion]: which cards are open. The latest message, any
 *    message that was unread when the reader first saw it, and the message
 *    the reader was opened on start expanded; every other card is a one-row
 *    summary until tapped. A choice the person makes (open / fold) wins over
 *    the defaults for as long as the conversation is open.
 *  - [conversationRows]: the list the reader draws, with a run of more than
 *    three folded cards shortened to first, "N earlier messages", last.
 *  - [ConversationHeader] / [CompactConversationTitle]: the subject as a large
 *    title at the top of the list, handing over to a compact title in the
 *    toolbar as it scrolls away.
 *  - [QuickReplyBar]: "Reply to <name>…" with Reply all / Forward behind it.
 *
 * Colours are palette tokens only; motion is scroll-linked (it follows the
 * finger, it is never a timed loop) and becomes a plain switch under the
 * system "Remove animations" setting.
 */

/** One entry of the reader's lazy list below the header. */
internal sealed interface ConversationRow {
    val key: String

    data class Message(val message: MailMessageFullDto, val expanded: Boolean) : ConversationRow {
        override val key: String get() = message.id
    }

    data class Earlier(val hidden: Int, val firstHiddenId: String) : ConversationRow {
        override val key: String get() = "earlier-$firstHiddenId"
    }
}

@Stable
internal class ConversationExpansion(private val focusId: String?) {
    private val overrides = mutableStateMapOf<String, Boolean>()
    // Plain sets, written only when a message is first seen and never
    // shrunk, so reading them during composition is stable.
    private val seen = HashSet<String>()
    private val unreadAtFirstSight = HashSet<String>()
    private val wasLatest = HashSet<String>()

    /** True once "N earlier messages" has been tapped. */
    var earlierRevealed by mutableStateOf(false)

    /** The card the toolbar acts on: the last one opened, else the latest. */
    var activeId by mutableStateOf(focusId)
        private set

    fun note(messages: List<MailMessageFullDto>) {
        for (m in messages) {
            if (seen.add(m.id) && !m.isRead) unreadAtFirstSight.add(m.id)
        }
        // Any message that has been the latest keeps its default: a reply
        // arriving while one is read must not fold the one being read.
        messages.lastOrNull()?.let { wasLatest.add(it.id) }
    }

    fun isExpanded(message: MailMessageFullDto): Boolean =
        overrides[message.id]
            ?: (message.id in wasLatest || message.id == focusId || message.id in unreadAtFirstSight)

    /** True when [id] was unread the first time this reader saw it (where the reader lands on open). */
    fun wasUnreadAtFirstSight(id: String): Boolean = id in unreadAtFirstSight

    fun expand(id: String) {
        overrides[id] = true
        activeId = id
    }

    fun collapse(id: String) {
        overrides[id] = false
        if (activeId == id) activeId = null
    }
}

/** The rows below the header. A folded run longer than three keeps its ends and hides the middle behind a pill. */
internal fun conversationRows(
    messages: List<MailMessageFullDto>,
    expansion: ConversationExpansion,
): List<ConversationRow> {
    val rows = ArrayList<ConversationRow>(messages.size + 1)
    var i = 0
    while (i < messages.size) {
        if (expansion.isExpanded(messages[i])) {
            rows += ConversationRow.Message(messages[i], expanded = true)
            i++
            continue
        }
        var j = i
        while (j < messages.size && !expansion.isExpanded(messages[j])) j++
        val run = messages.subList(i, j)
        if (run.size > 3 && !expansion.earlierRevealed) {
            rows += ConversationRow.Message(run.first(), expanded = false)
            rows += ConversationRow.Earlier(hidden = run.size - 2, firstHiddenId = run[1].id)
            rows += ConversationRow.Message(run.last(), expanded = false)
        } else {
            run.forEach { rows += ConversationRow.Message(it, expanded = false) }
        }
        i = j
    }
    return rows
}

/** The bare address out of "Name <addr>" or "addr", lowercased; null for a blank. */
internal fun bareAddress(raw: String?): String? {
    val s = raw?.trim().orEmpty()
    if (s.isEmpty()) return null
    val inside = s.substringAfterLast('<', "").substringBefore('>', "").trim()
    return (inside.ifEmpty { s }).trim('"', ' ').lowercase().takeIf { it.isNotEmpty() }
}

/** The display part of "Name <addr>", else the address itself. */
internal fun displayOfRecipient(raw: String): String {
    val s = raw.trim()
    val name = s.substringBefore('<', s).trim().trim('"').trim()
    return name.ifEmpty { bareAddress(s) ?: s }
}

/** Distinct people across the conversation: senders and recipients. */
internal fun participantCount(messages: List<MailMessageFullDto>): Int {
    val people = HashSet<String>()
    for (m in messages) {
        bareAddress(m.from?.address)?.let(people::add)
        m.to.forEach { r -> bareAddress(r)?.let(people::add) }
        m.cc.forEach { r -> bareAddress(r)?.let(people::add) }
    }
    return people.size
}

/** The account's own message: outbound, or sent from the signed-in address. */
internal fun isOwnMessage(message: MailMessageFullDto, accountAddress: String?): Boolean {
    val direction = message.direction?.lowercase()
    if (direction == "outbound" || direction == "out" || direction == "sent") return true
    val from = bareAddress(message.from?.address) ?: return false
    return from == bareAddress(accountAddress)
}

/**
 * The account's own message against every address the account sends from
 * (the signed-in address and its aliases, lowercased bare addresses in [me]);
 * outbound messages count as own regardless. Same rule as iOS `isOwn`.
 */
internal fun isOwnMessageOf(message: MailMessageFullDto, me: Set<String>): Boolean {
    val direction = message.direction?.lowercase()
    if (direction == "outbound" || direction == "out" || direction == "sent") return true
    val from = bareAddress(message.from?.address) ?: return false
    return from in me
}

/** The one-line summary of a folded card: the reply's own text, not the quote under it. */
internal fun collapsedSnippet(message: MailMessageFullDto): String? {
    val source = message.bodyText?.takeIf { it.isNotBlank() }
        ?.let { QuotedText.splitPlain(it, QuotedText.isForwardSubject(message.subject)).visible }
        ?.takeIf { it.isNotBlank() }
        ?: message.snippet
    return source?.replace(Regex("""\s+"""), " ")?.trim()?.take(240)?.takeIf { it.isNotEmpty() }
}

/**
 * Who "Reply to …" names, by first name (round8-spec §5.7; iOS
 * `replyTargetFirstName`): the sender, or for one's own message its first
 * recipient. A bare address gives its local part.
 */
internal fun replyTargetName(message: MailMessageFullDto, own: Boolean): String? {
    val full = (if (own) message.to.firstOrNull()?.let(::displayOfRecipient)
        else message.from?.name?.takeIf { it.isNotBlank() } ?: message.from?.address)
        ?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    if (full.contains('@') && !full.contains(' ')) return full.substringBefore('@').takeIf { it.isNotEmpty() } ?: full
    return full.split(' ', ',').firstOrNull { it.isNotBlank() } ?: full
}

/**
 * "N earlier messages": the middle of a long folded run. Drawn as a pill on a
 * hairline, so it reads as a fold in the stack rather than a button floating
 * in it.
 */
@Composable
internal fun EarlierMessagesPill(hidden: Int, onReveal: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val action = stringResource(R.string.reader_earlier_action)
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(palette.border))
        Row(
            Modifier
                .padding(horizontal = 10.dp)
                .minimumInteractiveComponentSize()
                .clip(CircleShape)
                .clickable(role = Role.Button, onClickLabel = action, onClick = onReveal)
                .testTag("mail-earlier-pill")
                .heightIn(min = 34.dp)
                .background(palette.softFill, CircleShape)
                .border(1.dp, palette.border, CircleShape)
                .padding(horizontal = 14.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(Icons.Rounded.UnfoldMore, contentDescription = null, tint = palette.textSecondary,
                modifier = Modifier.size(16.dp))
            Text(pluralStringResource(R.plurals.reader_earlier_messages, hidden, hidden),
                fontSize = 13.sp, fontWeight = FontWeight.Medium, color = palette.textSecondary, maxLines = 2)
        }
        Box(Modifier.weight(1f).height(1.dp).background(palette.border))
    }
}

/** "Inbox · 3 people · 5 messages": the compact bar's second line and the header's chips, from one place. */
@Composable
internal fun conversationFacts(boxTitle: String, participants: Int, messageCount: Int): List<String> = buildList {
    add(boxTitle)
    if (participants > 1) add(pluralStringResource(R.plurals.reader_participants, participants, participants))
    if (messageCount > 1) add(pluralStringResource(R.plurals.reader_message_count, messageCount, messageCount))
}

/**
 * The subject as a large title, first item of the list. As it scrolls under
 * the toolbar it eases down a little and fades ([collapse] 0 → 1, read in the
 * draw phase only), while [CompactConversationTitle] fades in above it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ConversationHeader(
    subject: String,
    facts: List<String>,
    collapse: () -> Float,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
    trailing: @Composable () -> Unit = {},
) {
    val palette = LocalPalette.current
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    // Up to three lines (spec §5.4); at a very large font the whole subject,
    // since the compact title in the toolbar is a single ellipsised line.
    val subjectLines = if (LocalDensity.current.fontScale > 1.3f) Int.MAX_VALUE else 3
    Row(
        modifier
            .fillMaxWidth()
            .graphicsLayer {
                if (!reducedMotion) {
                    val f = collapse()
                    alpha = 1f - f
                    val s = 1f - 0.04f * f
                    scaleX = s
                    scaleY = s
                    transformOrigin = TransformOrigin(if (rtl) 1f else 0f, 1f)
                }
            }
            .padding(start = 6.dp, end = 2.dp, top = 4.dp, bottom = 6.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(subject, style = ReaderType.subject, color = palette.textPrimary,
                maxLines = subjectLines, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.semantics { heading() }.testTag("mail-subject"))
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                facts.forEachIndexed { index, fact ->
                    val first = index == 0
                    Row(
                        Modifier
                            .background(if (first) palette.softFill else palette.bgTinted, CircleShape)
                            .then(if (first) Modifier else Modifier.border(1.dp, palette.border, CircleShape))
                            .padding(horizontal = 9.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        if (index == 1 && facts.size > 1) {
                            Icon(Icons.Rounded.Group, contentDescription = null, tint = palette.textSecondary,
                                modifier = Modifier.size(13.dp))
                        }
                        Text(fact, fontSize = 11.sp, fontWeight = FontWeight.Medium,
                            color = if (first) palette.primaryText else palette.textSecondary, maxLines = 1)
                    }
                }
            }
        }
        trailing()
    }
}

/**
 * The toolbar's compact title: subject on one line, facts under it. Visible
 * (and heard by TalkBack) only once the large title has mostly scrolled away;
 * [shown] is that threshold, [collapse] drives the fade.
 */
@Composable
internal fun CompactConversationTitle(
    subject: String,
    subtitle: String,
    collapse: () -> Float,
    shown: Boolean,
    reducedMotion: Boolean,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    Column(
        modifier
            .graphicsLayer {
                val f = if (reducedMotion) (if (shown) 1f else 0f) else collapse()
                alpha = f
                translationY = (1f - f) * 6.dp.toPx()
            }
            .then(if (shown) Modifier.semantics { heading() } else Modifier.clearAndSetSemantics {})
            .padding(horizontal = 4.dp),
    ) {
        Text(subject, fontSize = 16.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
            color = palette.textPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(subtitle, fontSize = 12.sp, lineHeight = 16.sp, color = palette.textSecondary,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The quick reply bar at the foot of the conversation: one wide "Reply to
 * <name>…" target that opens the composer as a reply (the composer builds the
 * quote and addressing from the full row), and Reply all / Forward behind the
 * overflow. The hairline spans the window; the bar keeps the reading width.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun QuickReplyBar(
    targetName: String?,
    onReply: () -> Unit,
    onReplyAll: () -> Unit,
    onForward: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    var menu by remember { mutableStateOf(false) }
    val shape = RoundedCornerShape(24.dp)
    val region = stringResource(R.string.reader_quick_reply)
    Column(Modifier.fillMaxWidth().background(palette.bg)
        .semantics { paneTitle = region }) {
        Spacer(Modifier.fillMaxWidth().height(1.dp).background(palette.border))
        Row(
            modifier.padding(start = 12.dp, end = 6.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Row(
                Modifier
                    .weight(1f)
                    .heightIn(min = 48.dp)
                    .clip(shape)
                    .background(palette.inputFill)
                    .border(1.dp, palette.border, shape)
                    .clickable(role = Role.Button, onClick = onReply)
                    .testTag("mail-quick-reply")
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(Icons.AutoMirrored.Rounded.Reply, contentDescription = null, tint = palette.primaryText,
                    modifier = Modifier.size(18.dp))
                Text(
                    if (targetName != null) stringResource(R.string.reader_reply_to, targetName)
                    else stringResource(R.string.reader_reply_fallback),
                    modifier = Modifier.weight(1f),
                    fontSize = 15.sp, color = palette.textSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
            Box {
                IconButton(onClick = { menu = true }, modifier = Modifier.testTag("mail-reply-more")) {
                    Icon(Icons.Rounded.MoreVert, stringResource(R.string.reader_reply_options),
                        tint = palette.textPrimary, modifier = Modifier.size(21.dp))
                }
                DropdownMenu(
                    expanded = menu,
                    onDismissRequest = { menu = false },
                    containerColor = palette.card,
                    shape = RoundedCornerShape(16.dp),
                ) {
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.mail_reply_all)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.ReplyAll, null, tint = palette.primaryText) },
                        modifier = Modifier.testTag("mail-reply-all"),
                        onClick = { menu = false; onReplyAll() },
                    )
                    DropdownMenuItem(
                        text = { Text(stringResource(R.string.mail_forward)) },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.Forward, null, tint = palette.primaryText) },
                        modifier = Modifier.testTag("mail-forward"),
                        onClick = { menu = false; onForward() },
                    )
                }
            }
        }
    }
}

/**
 * A folded message card (spec §5.3; iOS `CollapsedMessageRow`): avatar 32 ·
 * sender (bold while unread) · time, and one line of the reply's own words
 * under them. The whole card is the tap target and opens it in place. The
 * person's own message reads "You", shows a person glyph instead of an
 * avatar, and carries the leading accent bar on `ownMessageFill`. At a very
 * large font the name and the snippet may take two lines each.
 */
@Composable
internal fun CollapsedMessageCard(
    message: MailMessageFullDto,
    own: Boolean,
    onExpand: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val shape = RoundedCornerShape(20.dp)
    val large = LocalDensity.current.fontScale > 1.3f
    val lines = if (large) 2 else 1
    val unread = !message.isRead
    val encrypted = message.isE2eEncrypted || message.e2eEnvelope != null
    val sender = if (own) stringResource(R.string.reader_you)
        else message.from?.display?.takeIf { it.isNotBlank() } ?: stringResource(R.string.mail_unknown_sender)
    val computed = remember(message.id, message.bodyText, message.snippet, message.subject) { collapsedSnippet(message) }
    val snippet = if (encrypted) stringResource(R.string.mail_thread_encrypted_message)
        else computed ?: stringResource(R.string.mail_thread_show_message)
    val time = mailTime(message.createdAt, yesterday = stringResource(R.string.mail_time_yesterday))
    val unreadLabel = stringResource(R.string.reader_unread)
    val action = stringResource(R.string.reader_open_message_action)
    val accent = palette.primarySolid
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(shape)
            .background(if (own) palette.ownMessageFill else palette.card)
            .drawBehind {
                // The own-message accent: 3 dp on the leading edge, inset 12 dp.
                if (own) {
                    val w = 3.dp.toPx()
                    val inset = 12.dp.toPx()
                    val x = if (layoutDirection == LayoutDirection.Rtl) size.width - w else 0f
                    val h = (size.height - 2 * inset).coerceAtLeast(0f)
                    drawRoundRect(accent, topLeft = Offset(x, inset), size = Size(w, h),
                        cornerRadius = CornerRadius(2.dp.toPx()))
                }
            }
            .border(1.dp, palette.border, shape)
            .clickable(role = Role.Button, onClickLabel = action, onClick = onExpand)
            .semantics { if (unread) stateDescription = unreadLabel }
            .testTag("mail-collapsed-card")
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = if (large) Alignment.Top else Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (own) {
            Box(Modifier.size(32.dp).background(palette.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center) {
                Icon(Icons.Rounded.Person, contentDescription = null, tint = palette.onPrimaryContainer,
                    modifier = Modifier.size(16.dp))
            }
        } else {
            SenderAvatar(message.from?.display.orEmpty(), message.from?.address, size = 32.dp)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(sender, modifier = Modifier.weight(1f), fontSize = 15.sp,
                    fontWeight = if (unread) FontWeight.Bold else FontWeight.Medium,
                    color = palette.textPrimary, maxLines = lines, overflow = TextOverflow.Ellipsis)
                time?.let { Text(it, fontSize = 13.sp, color = palette.textTertiary, maxLines = 1) }
            }
            Text(snippet, fontSize = 14.sp, lineHeight = 20.sp, color = palette.textSecondary,
                maxLines = lines, overflow = TextOverflow.Ellipsis)
        }
    }
}
