package com.grapsee.grapmail.ui.mailfeatures

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.grapsee.grapmail.R
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.grapsee.grapmail.MainActivity
import com.grapsee.grapmail.logic.MailFeaturePrefs
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * GrapMail 2026-11 feature wave — tools layer.
 *
 * Roadmap coverage:
 *  - F12  Follow-up reminders            -> FollowUpCenterScreen (+ MailFeaturePrefs bookkeeping)
 *  - F19  Send-later countdown + cancel  -> SendLaterWatchdog (ongoing notification, Android analog)
 *  - F26  Import & migration assistant   -> ImportAssistantScreen (vCard/iCal validation + guidance)
 */

@Composable
fun MailFeatureScreenDialog(onClose: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(Modifier.fillMaxSize()) {
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                content()
            }
        }
    }
}

// ---------------------------------------------------------------------------
// F12 — Follow-up reminder center
// ---------------------------------------------------------------------------

@Composable
fun FollowUpCenterScreen(onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { MailFeaturePrefs(context) }
    var days by remember { mutableStateOf(prefs.followUpDays) }
    var items by remember { mutableStateOf(prefs.overdueAwaitingReplies(days)) }

    MailFeatureScreenDialog(onClose = onClose) {
        Text(stringResource(R.string.mail_features_followups_title), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(
            pluralStringResource(R.plurals.mail_followup_window_intro, days, days),
            fontSize = 12.sp,
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            TextButton(onClick = {
                days = (days - 1).coerceAtLeast(1)
                prefs.followUpDays = days
                items = prefs.overdueAwaitingReplies(days)
            }) { Text(stringResource(R.string.mail_followup_minus_day)) }
            Text(pluralStringResource(R.plurals.mail_followup_window, days, days), fontSize = 12.sp)
            TextButton(onClick = {
                days = (days + 1).coerceAtMost(30)
                prefs.followUpDays = days
                items = prefs.overdueAwaitingReplies(days)
            }) { Text(stringResource(R.string.mail_followup_plus_day)) }
        }
        if (items.isEmpty()) {
            Text(stringResource(R.string.mail_followup_nothing_overdue), fontSize = 13.sp)
        }
        val nudgeText = stringResource(R.string.mail_followup_nudge_text)
        items.forEach { pair ->
            val key = pair.first
            val title = pair.second
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title.ifBlank { stringResource(R.string.mail_followup_awaiting_reply) },
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium,
                    )
                    Text(stringResource(R.string.mail_followup_no_reply_yet), fontSize = 11.sp)
                }
                TextButton(onClick = {
                    MailFeatureIntents.copyText(
                        context,
                        "follow-up",
                        nudgeText,
                    )
                }) { Text(stringResource(R.string.mail_followup_nudge)) }
                TextButton(onClick = {
                    prefs.clearAwaitingReply(key)
                    items = prefs.overdueAwaitingReplies(days)
                }) { Text(stringResource(R.string.mail_common_clear)) }
            }
        }
        Text(
            stringResource(R.string.mail_followup_local_note),
            fontSize = 11.sp,
        )
        TextButton(onClick = onClose) { Text(stringResource(R.string.mail_common_close)) }
    }
}

// ---------------------------------------------------------------------------
// F19 — Send-later watchdog notification (ongoing + cancel)
// ---------------------------------------------------------------------------

object SendLaterWatchdog {
    const val CHANNEL_ID = "grapmail_sendlater"

    /**
     * Post (or refresh) the ongoing countdown for a scheduled send. The Cancel
     * action opens GrapMail on the Scheduled surface, where the pending send
     * can be stopped or rescheduled; the notification removes itself once the
     * send time passes.
     */
    fun show(context: Context, notificationId: Int, subject: String, sendAtMillis: Long) {
        ensureChannel(context)
        val whenText = SimpleDateFormat("EEE HH:mm", Locale.getDefault()).format(Date(sendAtMillis))
        val open = Intent(context, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            putExtra("grapmail_action", "scheduled")
        }
        val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        val cancelIntent = PendingIntent.getActivity(context, notificationId, open, flags)
        val remaining = (sendAtMillis - System.currentTimeMillis() - 60_000L).coerceAtLeast(60_000L)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            // The monochrome mark every other GrapMail notification uses. The
            // launcher icon is a full-colour adaptive icon, which the status
            // bar draws as a blank square (and some Android 8 builds refuse).
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setContentTitle(context.getString(R.string.mail_sendlater_title, whenText))
            .setContentText(subject.take(90))
            .setContentIntent(cancelIntent)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setTimeoutAfter(remaining)
            .addAction(0, context.getString(R.string.mail_sendlater_cancel), cancelIntent)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(notificationId, notification) }
    }

    fun clear(context: Context, notificationId: Int) {
        runCatching { NotificationManagerCompat.from(context).cancel(notificationId) }
    }

    private fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    context.getString(R.string.mail_sendlater_channel_name),
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = context.getString(R.string.mail_sendlater_channel_desc)
                },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// F26 — Import & migration assistant
// ---------------------------------------------------------------------------

@Composable
fun ImportAssistantScreen(onClose: () -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var pasted by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }

    MailFeatureScreenDialog(onClose = onClose) {
        Text(stringResource(R.string.mail_import_assistant_title), fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(stringResource(R.string.mail_import_assistant_intro), fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Text(
            stringResource(R.string.mail_import_assistant_step1),
            fontSize = 12.sp,
        )
        Text(
            stringResource(R.string.mail_import_assistant_step2),
            fontSize = 12.sp,
        )
        Text(
            stringResource(R.string.mail_import_assistant_step3),
            fontSize = 12.sp,
        )
        OutlinedTextField(
            value = pasted,
            onValueChange = { pasted = it },
            label = { Text(stringResource(R.string.mail_import_assistant_paste)) },
            modifier = Modifier.fillMaxWidth().height(160.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            TextButton(onClick = {
                val contacts = Regex("(?im)^BEGIN:VCARD").findAll(pasted).count()
                val events = Regex("(?im)^BEGIN:VEVENT").findAll(pasted).count()
                result = when {
                    pasted.isBlank() -> context.getString(R.string.mail_import_assistant_nothing)
                    contacts == 0 && events == 0 ->
                        context.getString(R.string.mail_import_assistant_none_found)
                    else -> context.getString(
                        R.string.mail_import_assistant_validated,
                        context.resources.getQuantityString(R.plurals.mail_import_contacts, contacts, contacts),
                        context.resources.getQuantityString(R.plurals.mail_import_events, events, events),
                    )
                }
            }) { Text(stringResource(R.string.mail_import_assistant_validate)) }
            TextButton(onClick = { pasted = ""; result = null }) { Text(stringResource(R.string.mail_common_clear)) }
        }
        result?.let { Text(it, fontSize = 13.sp) }
        Text(
            stringResource(R.string.mail_import_assistant_privacy),
            fontSize = 11.sp,
        )
        TextButton(onClick = onClose) { Text(stringResource(R.string.mail_common_close)) }
    }
}
