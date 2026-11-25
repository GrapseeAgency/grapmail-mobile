package com.grapsee.grapmail.ui.screens.mail

import com.grapsee.grapmail.ui.components.gmPrefersStackedRows
import com.grapsee.grapmail.ui.components.GMLayout
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.ui.text.style.TextOverflow
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.grapsee.grapmail.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.data.MailBox
import com.grapsee.grapmail.data.MailMessageDto
import com.grapsee.grapmail.data.MailStore
import com.grapsee.grapmail.data.MailUnsubscribeDto
import com.grapsee.grapmail.logic.MailboxViewModel
import com.grapsee.grapmail.ui.components.ErrorState
import com.grapsee.grapmail.ui.components.ListSkeleton
import com.grapsee.grapmail.ui.components.OfflineBanner
import com.grapsee.grapmail.ui.components.SenderAvatar
import com.grapsee.grapmail.ui.components.unsubscribeMailto
import com.grapsee.grapmail.ui.components.unsubscribeTarget
import com.grapsee.grapmail.ui.theme.LocalPalette

/**
 * Subscriptions — the mobile form of the web's `/mail/subscriptions`.
 *
 * The list comes from the account's own mail, grouped by sender. Nothing is
 * seeded and no row is invented: with no incoming mail the screen says exactly
 * that, and names what it is waiting for. The grouping rules are the web's
 * (`src/app/lib/subscriptions.ts:111-165`) kept line for line, because two
 * clients disagreeing about WHO counts as a subscription is two different
 * lists from one mailbox.
 *
 * ── WHY THE ACTION IS A HANDOFF, NOT A CALL ──────────────────────────────────
 * The web POSTs one-click unsubscribe requests from ITS server, behind an SSRF
 * guard that keeps a third-party URL from turning a user's session into a
 * request generator (`src/app/api/grapmail/unsubscribe/route.ts`). That route
 * is a Next.js surface; the phones talk to the API gateway directly and no
 * equivalent exists there, and making the POST from the device would leak the
 * user's IP and skip the guard entirely. So the mobile action is the per-row
 * browser handoff the plan allows — the SAME confirmation the thread's
 * unsubscribe sheet already ships (§16 rules: name the stranger's host, refuse
 * "unsubscribed", state that the app cannot see the outcome), and the screen
 * says so instead of offering a button that would lie.
 */

/** One detected subscription. Mirrors the web's `Subscription`. */
data class SubscriptionRow(
    val address: String,
    val name: String,
    val count: Int,
    val lastAt: String,
    val frequency: String,
    val unsubscribe: MailUnsubscribeDto?,
)

private val SERVICE_LOCAL =
    Regex("(noreply|no-reply|donotreply|do-not-reply|mailer|bounce|notifications?)")

/** The web's `looksLikeAPerson`, kept as its inverse on the local part. */
private fun looksLikeAPerson(address: String): Boolean =
    !SERVICE_LOCAL.containsMatchIn(address.substringBefore("@").lowercase())

/**
 * The web's `frequencyLabel`, same three buckets, same phrases. This is the
 * parity key the row carries; what the row DRAWS is [frequencyText], the same
 * bucket read from the string table so it can be translated.
 */
fun frequencyLabel(count: Int): String = when {
    count >= 20 -> "20+ emails recently"
    count >= 10 -> "10-20 emails recently"
    else -> "10 or fewer emails recently"
}

/** [frequencyLabel]'s bucket as the person reads it (same thresholds). */
@Composable
fun frequencyText(count: Int): String = stringResource(
    when {
        count >= 20 -> R.string.mail_subs_freq_20_plus
        count >= 10 -> R.string.mail_subs_freq_10_20
        else -> R.string.mail_subs_freq_10_or_fewer
    },
)

/**
 * The web's `detectSubscriptions` over the messages this device has loaded:
 * a sender qualifies with two rows carrying an unsubscribe offer, or three
 * rows when the address does not look like a person; the newest row wins for
 * the display name and the offer; the noisiest sender sorts first.
 *
 * "Rows the app has fetched" is the whole scope and the screen says it — a
 * list that claimed every subscription in the mailbox would claim more than
 * the code can see.
 */
fun detectSubscriptions(rows: List<MailMessageDto>): List<SubscriptionRow> {
    val bySender = LinkedHashMap<String, MutablePair>()
    for (m in rows) {
        val address = (m.from?.address ?: "").trim().lowercase()
        if (address.isEmpty() || !address.contains("@")) continue
        // Subscriptions arrive; they are not things you sent.
        if (m.direction?.lowercase() !in setOf("in", "inbound")) continue
        val prior = bySender[address]
        if (prior != null) {
            prior.count += 1
            val at = m.createdAt ?: ""
            if (at > prior.lastAt) {
                prior.lastAt = at
                m.from?.name?.takeIf { it.isNotBlank() }?.let { prior.name = it }
                m.unsubscribe?.let { prior.unsubscribe = it }
            }
        } else {
            bySender[address] = MutablePair(
                address = address,
                name = m.from?.name?.takeIf { it.isNotBlank() } ?: address.substringBefore("@"),
                count = 1,
                lastAt = m.createdAt ?: "",
                unsubscribe = m.unsubscribe,
            )
        }
    }
    return bySender.values
        .filter { p ->
            val hasUnsub = unsubscribeTarget(p.unsubscribe) != null ||
                unsubscribeMailto(p.unsubscribe) != null
            if (hasUnsub) p.count >= 2 else p.count >= 3 && !looksLikeAPerson(p.address)
        }
        .map { p ->
            SubscriptionRow(p.address, p.name, p.count, p.lastAt,
                frequencyLabel(p.count), p.unsubscribe)
        }
        .sortedWith(compareByDescending<SubscriptionRow> { it.count }
            .thenByDescending { it.lastAt })
}

private class MutablePair(
    val address: String,
    var name: String,
    var count: Int,
    var lastAt: String,
    var unsubscribe: MailUnsubscribeDto?,
)

@Composable
fun SubscriptionsScreen(vm: MailboxViewModel, onBack: () -> Unit) {
    val palette = LocalPalette.current
    val context = LocalContext.current
    var rows by remember { mutableStateOf<List<MailMessageDto>>(emptyList()) }
    var loaded by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    var gone by remember { mutableStateOf(listOf<String>()) }
    var note by remember { mutableStateOf<String?>(null) }
    var confirm by remember { mutableStateOf<SubscriptionRow?>(null) }
    var attempt by remember { mutableStateOf(0) }

    LaunchedEffect(attempt) {
        failed = false
        // The cache, not a fresh fetch: the same read the Contacts and Files
        // screens make. An absent inbox is "the mail has not been opened
        // here", not an empty mailbox.
        val page = MailStore.page(MailBox.Inbox, null)
        if (page == null) failed = true else { rows = page.messages; loaded = true }
    }

    val subs = remember(rows, gone) {
        detectSubscriptions(rows).filter { it.address !in gone }
    }
    val scopeCount = rows.count {
        it.direction?.lowercase() in setOf("in", "inbound")
    }

    MailScaffold(stringResource(R.string.mail_subs_title), onBack, maxContentWidth = GMLayout.Readable) {
        OfflineBanner()
        // First load and a failed read draw the shared states outside the
        // scrolling column (they size and scroll themselves).
        if (failed) {
            ErrorState(onRetry = { attempt += 1 },
                message = stringResource(R.string.mail_subs_failed),
                modifier = Modifier.testTag("gm-subs-failed"))
        } else if (!loaded) {
            ListSkeleton(Modifier.fillMaxSize().testTag("gm-subs-reading"), rows = 6,
                label = stringResource(R.string.mail_subs_reading))
        } else Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
        ) {
            Text(
                stringResource(R.string.mail_subs_intro),
                fontSize = 13.sp, lineHeight = 20.sp, color = palette.textSecondary,
                modifier = Modifier.padding(top = 6.dp),
            )
            Spacer(Modifier.height(10.dp))
            when {
                subs.size == 0 -> Column(Modifier.testTag("gm-subs-empty")) {
                    Text(
                        stringResource(R.string.mail_subs_empty_title),
                        fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                        color = palette.textPrimary,
                        modifier = Modifier.padding(top = 48.dp, bottom = 10.dp),
                    )
                    Text(
                        stringResource(R.string.mail_subs_empty_body, scopeCount),
                        fontSize = 13.sp, lineHeight = 20.sp, color = palette.textSecondary,
                    )
                    ScopeHint(palette)
                }
                else -> {
                    val mailtoOpened = stringResource(R.string.mail_subs_mailto_opened)
                    val mailtoNone = stringResource(R.string.mail_subs_mailto_none)
                    Text(
                        stringResource(R.string.mail_subs_delay_note),
                        fontSize = 13.sp, color = palette.textSecondary,
                        modifier = Modifier.padding(vertical = 6.dp),
                    )
                    subs.forEach { s ->
                        SubscriptionRowLine(s, palette,
                            onUnsubscribe = { confirm = s },
                            onMailto = { addr ->
                                // The mailto offer opens the MAIL, not a web page:
                                // a draft addressed to the sender's unsubscribe
                                // list, which the person then sends. The device's
                                // mail composer answers that; this app is not it,
                                // and it says what it opened rather than claiming
                                // the letter went.
                                val intent = Intent(Intent.ACTION_SENDTO,
                                    Uri.parse("mailto:$addr?subject=unsubscribe"))
                                try {
                                    context.startActivity(intent)
                                    note = mailtoOpened
                                } catch (e: android.content.ActivityNotFoundException) {
                                    note = mailtoNone
                                }
                            })
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        stringResource(R.string.mail_subs_scope, scopeCount),
                        fontSize = 12.sp, lineHeight = 18.sp, color = palette.textTertiary,
                    )
                    ScopeHint(palette)
                }
            }
            note?.let {
                Text(
                    it, fontSize = 13.sp, color = palette.textPrimary,
                    modifier = Modifier.fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp)).background(palette.softFill)
                        .padding(horizontal = 16.dp, vertical = 12.dp)
                        .padding(top = 10.dp).testTag("gm-subs-note"),
                )
            }
            Spacer(Modifier.height(14.dp))
            Text(
                stringResource(R.string.mail_subs_footer),
                fontSize = 12.sp, lineHeight = 18.sp, color = palette.textTertiary,
            )
            Spacer(Modifier.height(24.dp))
        }

        val s = confirm
        if (s != null) {
            val url = unsubscribeTarget(s.unsubscribe)
            // The same sheet shell the thread and the bulk bar use -- one
            // bottom-sheet mechanism per app, not a bespoke scrim per screen.
            MailSheet(onDismiss = { confirm = null }) {
                if (url != null) {
                    // The thread's own confirmation, unchanged: same four
                    // statements, same host naming, same refusal to claim
                    // the outcome. One sheet for one decision, not a
                    // friendlier copy for the screen that sees more traffic.
                    UnsubscribeContent(
                        sender = s.address,
                        url = url,
                        onOpen = {
                            val outcome = openExternally(context, url)
                            gone = gone + s.address
                            note = context.getString(R.string.mail_subs_removed_note, outcome)
                            confirm = null
                        },
                        onCancel = { confirm = null },
                    )
                } else {
                    Text(
                        stringResource(R.string.mail_subs_no_offer, s.name),
                        fontSize = 13.sp, lineHeight = 20.sp, color = palette.textSecondary,
                        modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp),
                    )
                    Text(
                        stringResource(R.string.mail_common_close), fontSize = 14.sp, color = palette.textTertiary,
                        // The whole line is the 48dp target, not just the word.
                        modifier = Modifier.fillMaxWidth()
                            .clickable(role = androidx.compose.ui.semantics.Role.Button) { confirm = null }
                            .padding(horizontal = 20.dp, vertical = 14.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun ScopeHint(palette: com.grapsee.grapmail.ui.theme.Palette) {
    Text(
        stringResource(R.string.mail_subs_scope_hint),
        fontSize = 12.sp, lineHeight = 18.sp, color = palette.textTertiary,
        modifier = Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun SubscriptionRowLine(
    s: SubscriptionRow,
    palette: com.grapsee.grapmail.ui.theme.Palette,
    onUnsubscribe: () -> Unit,
    onMailto: (String) -> Unit,
) {
    val webTarget = unsubscribeTarget(s.unsubscribe)
    val mailto = unsubscribeMailto(s.unsubscribe)
    // At large text the button moves under the sender instead of squeezing
    // the name and address to a sliver; at the default size the row is as it was.
    val stacked = gmPrefersStackedRows()
    val details: @Composable (Modifier) -> Unit = { mod ->
        Column(mod) {
            Text(s.name, fontSize = 14.sp, color = palette.textPrimary,
                maxLines = if (stacked) 3 else 1, overflow = TextOverflow.Ellipsis)
            Text(s.address, fontSize = 12.sp, color = palette.textSecondary,
                maxLines = if (stacked) 3 else 1, overflow = TextOverflow.Ellipsis)
            Text(frequencyText(s.count), fontSize = 12.sp, color = palette.textTertiary)
        }
    }
    if (stacked) {
        Column(
            Modifier.fillMaxWidth().padding(vertical = 14.dp).testTag("gm-subs-row"),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SenderAvatar(display = s.name, address = s.address, size = 38.dp)
                Spacer(Modifier.width(12.dp))
                details(Modifier.weight(1f))
            }
            UnsubscribeAction(palette, Modifier.align(Alignment.End)) {
                if (webTarget != null) onUnsubscribe()
                else if (mailto != null) onMailto(mailto)
            }
        }
        return
    }
    Row(
        Modifier.fillMaxWidth().padding(vertical = 14.dp).testTag("gm-subs-row"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SenderAvatar(display = s.name, address = s.address, size = 38.dp)
        Spacer(Modifier.width(12.dp))
        details(Modifier.weight(1f))
        Spacer(Modifier.width(10.dp))
        UnsubscribeAction(palette, Modifier) {
            if (webTarget != null) onUnsubscribe()
            else if (mailto != null) onMailto(mailto)
        }
    }
}

@Composable
private fun UnsubscribeAction(
    palette: com.grapsee.grapmail.ui.theme.Palette,
    modifier: Modifier,
    onClick: () -> Unit,
) {
        // The button names the handoff it actually performs. A one-click offer
        // is advertised, not verified from here, so the label never says
        // "one-click": the sheet's own lines carry that precision.
        Text(
            stringResource(R.string.mail_subs_unsubscribe),
            fontSize = 13.sp, fontWeight = FontWeight.Medium, color = palette.onPrimary,
            textAlign = TextAlign.Center,
            modifier = modifier.clip(CircleShape)
                .background(palette.primarySolid)
                .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
                .padding(horizontal = 18.dp, vertical = 15.dp)
                .testTag("gm-subs-action"),
        )
}
