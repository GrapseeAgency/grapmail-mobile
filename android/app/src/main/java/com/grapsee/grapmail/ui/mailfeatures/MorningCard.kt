package com.grapsee.grapmail.ui.mailfeatures

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.R
import com.grapsee.grapmail.data.MailBox
import com.grapsee.grapmail.data.MailMessageDto
import com.grapsee.grapmail.data.parseMailInstant
import com.grapsee.grapmail.features.ActionCardExtractor
import com.grapsee.grapmail.features.CardKind
import com.grapsee.grapmail.features.MailCategorizer
import com.grapsee.grapmail.features.MailCategory
import com.grapsee.grapmail.logic.CodeFinder
import com.grapsee.grapmail.ui.components.GMMotion
import com.grapsee.grapmail.ui.components.rememberMotionReduced
import com.grapsee.grapmail.ui.theme.LocalPalette
import java.time.LocalDateTime
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * The Good morning card at the top of the Inbox, and the on-device digest it
 * reads. Spec shared with iOS (same rules, same words):
 *
 *  - Greeting by local time: morning 05–12, afternoon 12–17, evening 17–05,
 *    with the mailbox's first name when `/me` gave one.
 *  - One line from what the app already detects, non-zero parts only:
 *      "need a reply"     → an unread inbound row, not sealed, not from the
 *                           signed-in address, that MailCategorizer files under
 *                           People, AND either belongs to a thread the
 *                           follow-up memory is waiting on (every awaiting key,
 *                           not only the overdue set), or whose subject/preview
 *                           asks something: "?" or one of [MailDigest.ASK_PHRASES]
 *                           (iOS MailboxInsights.askPhrases, same list);
 *      "package arriving" → ActionCardExtractor's TRACKING card on the row's
 *                           subject + preview, last 7 days, smart features on;
 *      "sign-in codes"    → CodeFinder.find(row), received in the last 60 min;
 *                           also offered as a row chip in All mail, Starred,
 *                           Archive and Snoozed.
 *    Sealed (end-to-end) mail is never read for any of them.
 *  - A tap on a part narrows the inbox to those rows; a clearable chip names
 *    the active filter. All zero: the greeting and "You're all caught up".
 *  - ✕ hides the card until the next time-of-day period.
 *  - Enters once per period (not on every scroll back), never under reduced
 *    motion. TalkBack reads one sentence and offers each part as an action.
 */

/** What tapping a part of the summary narrows the inbox to. */
enum class DigestFilter { Reply, Packages, Codes }

@Stable
class MailDigest(
    val replyIds: Set<String>,
    val packageIds: Set<String>,
    /** Row id → its sign-in code, only for codes received in the last hour. */
    val codes: Map<String, String>,
) {
    val isEmpty: Boolean get() = replyIds.isEmpty() && packageIds.isEmpty() && codes.isEmpty()

    fun matches(filter: DigestFilter, row: MailMessageDto): Boolean = when (filter) {
        DigestFilter.Reply -> row.id in replyIds
        DigestFilter.Packages -> row.id in packageIds
        DigestFilter.Codes -> row.id in codes
    }

    fun count(filter: DigestFilter): Int = when (filter) {
        DigestFilter.Reply -> replyIds.size
        DigestFilter.Packages -> packageIds.size
        DigestFilter.Codes -> codes.size
    }

    companion object {
        val Empty = MailDigest(emptySet(), emptySet(), emptyMap())

        /** A code older than this is no longer worth a chip — it has expired in practice. */
        const val CODE_WINDOW_MS = 60L * 60L * 1000L
        /** A tracking mail older than this is not "arriving". */
        private const val PACKAGE_WINDOW_MS = 7L * 24L * 60L * 60L * 1000L
        /** Clock skew allowance for "received in the last hour". */
        private const val FUTURE_SKEW_MS = 5L * 60L * 1000L

        /**
         * Phrases that ask the reader for something, on top of a question
         * mark. Exactly iOS `MailboxInsights.askPhrases`; keep the two lists
         * in step.
         */
        val ASK_PHRASES = listOf(
            "can you", "could you", "would you", "are you able", "do you have",
            "let me know", "please confirm", "please reply", "get back to me",
            "what do you think", "your thoughts", "rsvp",
        )

        /** The subject or preview asks something: a "?" or one of [ASK_PHRASES]. */
        internal fun asks(row: MailMessageDto): Boolean {
            val lowered = (row.subject.orEmpty() + "\n" + row.snippet.orEmpty()).lowercase(Locale.ROOT)
            return lowered.contains('?') || ASK_PHRASES.any { lowered.contains(it) }
        }

        /**
         * The shared "needs a reply" rule (iOS `MailboxInsights.needsReply`):
         * unread, inbound, not sealed, not from [self] (the signed-in address,
         * lowercased; empty until `/me` answered), filed under People by
         * MailCategorizer, and either in a thread the follow-up memory is
         * waiting on ([awaiting]) or asking something. The overdue follow-up
         * set alone no longer counts.
         */
        internal fun needsReply(
            row: MailMessageDto,
            self: String,
            awaiting: Set<String>,
            knownSenders: Set<String>,
        ): Boolean {
            if (row.isE2eEncrypted || row.isRead || row.direction == "outbound") return false
            val sender = row.from?.address.orEmpty()
            if (self.isNotEmpty() && sender.trim().lowercase(Locale.ROOT) == self) return false
            val decision = MailCategorizer.classify(
                senderAddress = sender,
                subject = row.subject.orEmpty(),
                bodyText = "",
                hasListUnsubscribe = row.unsubscribe != null,
                knownSenders = knownSenders,
            )
            if (decision.category != MailCategory.PEOPLE) return false
            val thread = row.threadId
            // Android's follow-up memory is keyed by the message that was
            // replied to; a thread id is honoured too.
            if (row.id in awaiting || (thread != null && thread in awaiting)) return true
            return asks(row)
        }

        /**
         * Pure and CPU-only — call it off the main thread. Reply and package
         * sets are read for the Inbox only (the card's box); codes also for
         * All mail, Starred, Archive and Snoozed, where the row chip is
         * offered too (the same boxes as iOS).
         *
         * [awaitingKeys] is every key the follow-up memory is still waiting
         * on (MailFeaturePrefs.awaitingReplyKeys), [me] the signed-in address,
         * [knownSenders] the person's priority senders (the categorizer's
         * known set).
         */
        fun of(
            rows: List<MailMessageDto>,
            box: MailBox,
            awaitingKeys: Set<String>,
            smartFeatures: Boolean,
            nowMillis: Long,
            me: String? = null,
            knownSenders: Set<String> = emptySet(),
        ): MailDigest {
            if (rows.isEmpty()) return Empty
            val inbox = box == MailBox.Inbox
            // The same five boxes as iOS's MailboxView.codeBoxes (All mail is
            // its `.others`); scripts/api-parity.py compares the two sets.
            val codeBox = box == MailBox.Inbox || box == MailBox.All || box == MailBox.Starred ||
                box == MailBox.Archive || box == MailBox.Snoozed
            val self = me?.trim()?.lowercase(Locale.ROOT).orEmpty()
            val reply = LinkedHashSet<String>()
            val packages = LinkedHashSet<String>()
            val codes = LinkedHashMap<String, String>()
            for (row in rows) {
                if (row.isE2eEncrypted || row.direction == "outbound") continue
                val at = parseMailInstant(row.createdAt)?.toEpochMilli()
                if (inbox && needsReply(row, self, awaitingKeys, knownSenders)) reply += row.id
                if (inbox && smartFeatures && at != null && nowMillis - at <= PACKAGE_WINDOW_MS) {
                    val tracking = ActionCardExtractor.extract(row.subject.orEmpty(), row.snippet.orEmpty())
                        .any { it.kind == CardKind.TRACKING }
                    if (tracking) packages += row.id
                }
                if (codeBox && at != null && nowMillis - at <= CODE_WINDOW_MS && at - nowMillis <= FUTURE_SKEW_MS) {
                    CodeFinder.find(row)?.let { codes[row.id] = it }
                }
            }
            return MailDigest(reply, packages, codes)
        }
    }
}

// ── the time of day ─────────────────────────────────────────────────────────

enum class DayPart { Morning, Afternoon, Evening }

/**
 * The period [time] falls in, and a key naming that one occurrence of it.
 * Evening runs 17:00–05:00, so the small hours belong to the previous day's
 * evening — a card dismissed at 23:00 stays hidden at 01:00.
 */
internal fun dayPartAt(time: LocalDateTime): Pair<DayPart, String> {
    val date = time.toLocalDate()
    return when (time.hour) {
        in 5..11 -> DayPart.Morning to "$date-m"
        in 12..16 -> DayPart.Afternoon to "$date-a"
        in 17..23 -> DayPart.Evening to "$date-e"
        else -> DayPart.Evening to "${date.minusDays(1)}-e"
    }
}

/** The card's per-account visibility: hidden from ✕ until the period changes. */
@Stable
class MorningCardState internal constructor(context: Context, account: String) {
    private val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val dismissKey = "dismissed:$account"
    /** Which entrances already played, so a scroll back does not replay one. */
    internal val entranceKey = "$account|"

    var part by mutableStateOf(dayPartAt(LocalDateTime.now()).first)
        internal set
    var periodKey by mutableStateOf(dayPartAt(LocalDateTime.now()).second)
        internal set
    private var dismissedPeriod by mutableStateOf(prefs.getString(dismissKey, null))

    val visible: Boolean get() = dismissedPeriod != periodKey

    fun dismiss() {
        dismissedPeriod = periodKey
        prefs.edit().putString(dismissKey, periodKey).apply()
    }

    internal fun tick() {
        val (p, k) = dayPartAt(LocalDateTime.now())
        if (p != part) part = p
        if (k != periodKey) periodKey = k
    }

    private companion object {
        const val PREFS = "grapmail-morning-card"
    }
}

@Composable
fun rememberMorningCardState(account: String?): MorningCardState {
    val context = LocalContext.current
    val key = account?.trim()?.lowercase().orEmpty()
    val state = remember(key) { MorningCardState(context, key) }
    LaunchedEffect(state) {
        while (true) {
            state.tick()
            delay(60_000)
        }
    }
    return state
}

/** Entrances already played this process, by account and period. */
private val playedEntrances = HashSet<String>()

/**
 * The card. Draw it only while [state] is visible. [active] is the inbox's
 * current digest filter; [onFilter] sets or clears it (null).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GoodMorningCard(
    state: MorningCardState,
    name: String?,
    digest: MailDigest,
    active: DigestFilter?,
    onFilter: (DigestFilter?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val palette = LocalPalette.current
    val reduced = rememberMotionReduced()
    val first = remember(name) {
        name?.trim()?.split(Regex("\\s+"))?.firstOrNull()?.takeIf { it.isNotBlank() && !it.contains('@') }
    }
    val greeting = when (state.part) {
        DayPart.Morning -> if (first != null) stringResource(R.string.mailbox_feat_greeting_morning_name, first)
            else stringResource(R.string.mailbox_feat_greeting_morning)
        DayPart.Afternoon -> if (first != null) stringResource(R.string.mailbox_feat_greeting_afternoon_name, first)
            else stringResource(R.string.mailbox_feat_greeting_afternoon)
        DayPart.Evening -> if (first != null) stringResource(R.string.mailbox_feat_greeting_evening_name, first)
            else stringResource(R.string.mailbox_feat_greeting_evening)
    }
    val parts = DigestFilter.entries.mapNotNull { f ->
        val n = digest.count(f)
        if (n <= 0) null else f to when (f) {
            DigestFilter.Reply -> pluralStringResource(R.plurals.mailbox_feat_digest_reply, n, n)
            DigestFilter.Packages -> pluralStringResource(R.plurals.mailbox_feat_digest_package, n, n)
            DigestFilter.Codes -> pluralStringResource(R.plurals.mailbox_feat_digest_code, n, n)
        }
    }
    val caughtUp = stringResource(R.string.mailbox_feat_digest_caught_up)
    val joiner = stringResource(R.string.mailbox_feat_digest_a11y_join)
    val sentence = stringResource(R.string.mailbox_feat_digest_a11y, greeting,
        if (parts.isEmpty()) caughtUp else parts.joinToString(joiner) { it.second })
    val actionLabels = mapOf(
        DigestFilter.Reply to stringResource(R.string.mailbox_feat_action_show_reply),
        DigestFilter.Packages to stringResource(R.string.mailbox_feat_action_show_packages),
        DigestFilter.Codes to stringResource(R.string.mailbox_feat_action_show_codes),
    )
    val clearLabel = stringResource(R.string.mailbox_feat_action_show_all)
    val dismissLabel = stringResource(R.string.mailbox_feat_dismiss)

    // One entrance per account and period: a lazy item is recomposed every
    // time it scrolls back into view, and that must not replay it.
    val entranceId = state.entranceKey + state.periodKey
    val enters = remember(entranceId) { !reduced && entranceId !in playedEntrances }
    val entrance = remember(entranceId) { Animatable(if (enters) 0f else 1f) }
    LaunchedEffect(entranceId) {
        playedEntrances += entranceId
        if (entrance.value < 1f) {
            entrance.animateTo(1f, tween(GMMotion.LongMs + 120, easing = GMMotion.EmphasizedDecelerate))
        }
    }

    val shape = RoundedCornerShape(20.dp)
    Column(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .graphicsLayer {
                val p = entrance.value
                alpha = p
                translationY = (1f - p) * 12.dp.toPx()
            }
            .testTag("mail-morning-card"),
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .clip(shape)
                .background(palette.card)
                .border(1.dp, palette.border, shape)
                // One sentence for TalkBack, each part (and ✕) an action.
                .clearAndSetSemantics {
                    contentDescription = sentence
                    customActions = parts.map { (f, _) ->
                        CustomAccessibilityAction(if (active == f) clearLabel else actionLabels.getValue(f)) {
                            onFilter(if (active == f) null else f); true
                        }
                    } + CustomAccessibilityAction(dismissLabel) {
                        onFilter(null); state.dismiss(); true
                    }
                }
                .padding(start = 16.dp, top = 12.dp, bottom = 12.dp, end = 4.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Column(Modifier.weight(1f).padding(top = 2.dp)) {
                Text(greeting, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = palette.textPrimary)
                Spacer(Modifier.size(4.dp))
                if (parts.isEmpty()) {
                    Text(caughtUp, fontSize = 13.sp, color = palette.textSecondary)
                } else {
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                        verticalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        parts.forEachIndexed { i, (f, text) ->
                            if (i > 0) {
                                Text("·", fontSize = 13.sp, color = palette.textTertiary,
                                    modifier = Modifier.padding(vertical = 4.dp).align(Alignment.CenterVertically))
                            }
                            val on = active == f
                            Text(
                                text,
                                fontSize = 13.sp,
                                fontWeight = if (on) FontWeight.SemiBold else FontWeight.Medium,
                                color = if (on) palette.primarySolid else palette.textSecondary,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (on) palette.primarySolid.copy(alpha = 0.12f)
                                        else androidx.compose.ui.graphics.Color.Transparent)
                                    .clickable(role = Role.Button) { onFilter(if (on) null else f) }
                                    .heightIn(min = 28.dp)
                                    .padding(horizontal = 6.dp, vertical = 4.dp)
                                    .testTag("mail-morning-part-${f.name.lowercase()}"),
                            )
                        }
                    }
                }
            }
            IconButton(
                onClick = { onFilter(null); state.dismiss() },
                modifier = Modifier.testTag("mail-morning-dismiss"),
            ) {
                Icon(Icons.Rounded.Close, dismissLabel, tint = palette.textTertiary, modifier = Modifier.size(18.dp))
            }
        }
        if (active != null) {
            DigestFilterChip(active, onClear = { onFilter(null) },
                modifier = Modifier.padding(start = 6.dp, top = 8.dp))
        }
    }
}

/** The active filter, named, with the one control that clears it. */
@Composable
fun DigestFilterChip(filter: DigestFilter, onClear: () -> Unit, modifier: Modifier = Modifier) {
    val palette = LocalPalette.current
    val label = when (filter) {
        DigestFilter.Reply -> stringResource(R.string.mailbox_feat_filter_reply)
        DigestFilter.Packages -> stringResource(R.string.mailbox_feat_filter_packages)
        DigestFilter.Codes -> stringResource(R.string.mailbox_feat_filter_codes)
    }
    val clear = stringResource(R.string.mailbox_feat_filter_clear, label)
    val shape = RoundedCornerShape(50)
    Row(
        modifier
            .clip(shape)
            .background(palette.primarySolid.copy(alpha = 0.12f))
            .border(1.dp, palette.primarySolid.copy(alpha = 0.5f), shape)
            .clickable(onClickLabel = clear, role = Role.Button, onClick = onClear)
            .semantics(mergeDescendants = true) {
                contentDescription = clear
                selected = true
                role = Role.Button
            }
            .heightIn(min = 36.dp)
            .padding(start = 12.dp, end = 8.dp, top = 6.dp, bottom = 6.dp)
            .testTag("mail-digest-filter"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.Medium, color = palette.primarySolid,
            modifier = Modifier.weight(1f, fill = false))
        Spacer(Modifier.width(6.dp))
        Icon(Icons.Rounded.Close, null, tint = palette.primarySolid, modifier = Modifier.size(16.dp))
    }
}
