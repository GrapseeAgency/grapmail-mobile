package com.grapsee.grapmail.ui.screens.mail

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.logic.MailboxViewModel
import com.grapsee.grapmail.ui.theme.LocalPalette
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/**
 * The account's future sends, soonest first, each with its cancel. The data
 * is the server's own `GET /grapmail/scheduled` answer — mail the worker will
 * deliver at `scheduledFor` — and cancelling clears `scheduled_at`, which is
 * why the note says "it will not be sent" rather than "deleted".
 */
@Composable
fun ScheduledScreen(onBack: () -> Unit, vm: MailboxViewModel) {
    val palette = LocalPalette.current
    val state by vm.scheduled.collectAsState()
    LaunchedEffect(Unit) { vm.loadScheduled() }

    Column(Modifier.fillMaxSize().background(palette.bg).statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Back", tint = palette.textPrimary,
                modifier = Modifier.size(22.dp).clickable(onClick = onBack))
            Spacer(Modifier.width(12.dp))
            Text("Scheduled", fontSize = 17.sp, fontWeight = FontWeight.SemiBold,
                color = palette.textPrimary)
        }

        when {
            state.loading -> {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = palette.primarySolid)
                }
            }
            state.error != null -> {
                Text(
                    state.error!!, fontSize = 14.sp, color = palette.textSecondary,
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                )
            }
            state.messages.isEmpty() -> {
                Text(
                    "Nothing scheduled.",
                    fontSize = 14.sp, color = palette.textTertiary,
                    modifier = Modifier.fillMaxWidth().padding(20.dp),
                )
            }
            else -> {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(state.messages, key = { it.id }) { m ->
                        Column(
                            Modifier.fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                        ) {
                            Text(
                                m.subject?.takeIf { it.isNotBlank() } ?: "(no subject)",
                                fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                                color = palette.textPrimary, maxLines = 1,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                "To " + m.to.joinToString(", ").ifBlank { "—" },
                                fontSize = 13.sp, color = palette.textSecondary,
                                maxLines = 1,
                            )
                            Spacer(Modifier.height(2.dp))
                            Text(
                                m.scheduledFor?.let {
                                    "Sends " + DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM)
                                        .format(ZonedDateTime.parse(it))
                                } ?: "Awaiting the server's instant",
                                fontSize = 12.sp, color = palette.textTertiary,
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                if (state.cancellingId == m.id) "Cancelling…" else "Cancel",
                                fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                color = palette.primarySolid,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickable(enabled = state.cancellingId == null) {
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
