package com.grapsee.grapmail.ui.screens.mail

import com.grapsee.grapmail.ui.components.gmPrefersStackedRows
import com.grapsee.grapmail.ui.components.gmMaxWidth
import com.grapsee.grapmail.ui.components.GMLayout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import com.grapsee.grapmail.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.logic.MailboxViewModel
import com.grapsee.grapmail.ui.theme.LocalPalette
import com.grapsee.grapmail.data.parseMailInstant
import com.grapsee.grapmail.ui.components.absoluteDateTimeOrRaw
import com.grapsee.grapmail.ui.components.EmptyState
import com.grapsee.grapmail.ui.components.ErrorState
import com.grapsee.grapmail.ui.components.ListSkeleton
import com.grapsee.grapmail.ui.components.OfflineBanner

/**
 * The account's future sends, soonest first, each with its cancel. The data
 * is the server's own `GET /grapmail/scheduled` answer — mail the worker will
 * deliver at `scheduledFor` — and cancelling clears `scheduled_at`, which is
 * why the note says "it will not be sent" rather than "deleted".
 */
private fun scheduledWhenText(iso: String): String = absoluteDateTimeOrRaw(iso)

@Composable
fun ScheduledScreen(onBack: () -> Unit, vm: MailboxViewModel) {
    val palette = LocalPalette.current
    val state by vm.scheduled.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.loadScheduled() }

    // F19: while future sends exist, mirror them onto the device's own shade —
    // one notice per scheduled message, kept in step with this list on every
    // load and cancellation, and taken down when the list no longer holds the
    // message it stood for. The times shown are the server's `scheduled_at`,
    // the same instant this screen prints.
    val watchdogContext = androidx.compose.ui.platform.LocalContext.current
    val scheduledFallback = stringResource(R.string.mail_scheduled_fallback_subject)
    val watchedIds = androidx.compose.runtime.remember {
        androidx.compose.runtime.mutableStateOf(emptySet<Int>())
    }
    androidx.compose.runtime.LaunchedEffect(state.messages) {
        val current = state.messages.map { it.id.hashCode() }.toSet()
        for (gone in watchedIds.value - current) {
            com.grapsee.grapmail.ui.mailfeatures.SendLaterWatchdog.clear(watchdogContext, gone)
        }
        for (m in state.messages) {
            val at = parseMailInstant(m.scheduledFor)?.toEpochMilli()
            if (at == null) {
                com.grapsee.grapmail.ui.mailfeatures.SendLaterWatchdog.clear(watchdogContext, m.id.hashCode())
                continue
            }
            com.grapsee.grapmail.ui.mailfeatures.SendLaterWatchdog.show(
                watchdogContext,
                m.id.hashCode(),
                m.subject?.takeIf { s -> s.isNotBlank() } ?: scheduledFallback,
                at,
            )
        }
        watchedIds.value = current
    }

    val largeText = gmPrefersStackedRows()
    // The list is capped and centred on wide windows (tablets, unfolded
    // foldables, desktop windows); a phone in portrait is unchanged. The
    // background still fills the whole window behind it.
    Column(Modifier.fillMaxSize().background(palette.bg).statusBarsPadding().imePadding()
        .gmMaxWidth(GMLayout.Readable)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 1.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The 22dp arrow keeps its place; its touch target is 48dp.
            Box(
                Modifier.size(48.dp).clip(CircleShape)
                    .clickable(role = androidx.compose.ui.semantics.Role.Button, onClick = onBack),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.mail_common_back), tint = palette.textPrimary,
                    modifier = Modifier.size(22.dp))
            }
            Text(stringResource(R.string.mail_scheduled_title), fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                color = palette.textPrimary, modifier = Modifier.weight(1f))
        }

        OfflineBanner()
        // A failure over a list already on screen (a refresh, or a cancel the
        // server refused) keeps the list and says why above it; only a failure
        // with nothing to show replaces it with the error state.
        if (state.error != null && state.messages.isNotEmpty()) {
            Text(
                state.error!!, fontSize = 13.sp, color = palette.warnInk,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
                    .clip(RoundedCornerShape(10.dp)).background(palette.warnChipBg)
                    .padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
        when {
            state.loading && state.messages.isEmpty() -> {
                ListSkeleton(Modifier.fillMaxSize(), rows = 4, avatar = false,
                    label = stringResource(R.string.states_scheduled_loading))
            }
            state.error != null && state.messages.isEmpty() -> {
                ErrorState(onRetry = { vm.loadScheduled() },
                    title = stringResource(R.string.states_scheduled_failed_title),
                    message = state.error)
            }
            state.messages.isEmpty() -> {
                EmptyState(Icons.Rounded.Schedule, stringResource(R.string.states_scheduled_empty_title),
                    body = stringResource(R.string.mail_scheduled_empty))
            }
            else -> {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.messages, key = { it.id }) { m ->
                        Column(
                            Modifier.fillMaxWidth()
                                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 4.dp),
                        ) {
                            Text(
                                m.subject?.takeIf { it.isNotBlank() } ?: stringResource(R.string.mail_no_subject),
                                fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                color = palette.textPrimary, maxLines = if (largeText) 3 else 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                stringResource(R.string.mail_scheduled_to, m.to.joinToString(", ").ifBlank { "—" }),
                                fontSize = 13.sp, color = palette.textSecondary,
                                maxLines = if (largeText) 3 else 1, overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                m.scheduledFor?.let { stringResource(R.string.mail_scheduled_sends, scheduledWhenText(it)) }
                                    ?: stringResource(R.string.mail_scheduled_awaiting_instant),
                                fontSize = 12.sp, color = palette.textTertiary,
                            )
                            // 48dp target; the padding below gives back the slack.
                            Text(
                                if (state.cancellingId == m.id) stringResource(R.string.mail_scheduled_cancelling)
                                else stringResource(R.string.mail_common_cancel),
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                color = palette.primarySolid,
                                modifier = Modifier
                                    .minimumInteractiveComponentSize()
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(
                                        enabled = state.cancellingId == null,
                                        role = androidx.compose.ui.semantics.Role.Button,
                                    ) {
                                        vm.cancelScheduled(m.id)
                                    }
                                    .padding(horizontal = 8.dp, vertical = 6.dp),
                            )
                        }
                        androidx.compose.material3.HorizontalDivider(color = palette.border)
                    }
                }
            }
        }
        state.note?.let {
            Text(
                it, fontSize = 13.sp, color = palette.textSecondary,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}
