package com.grapsee.grapmail.logic

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.grapsee.grapmail.MainActivity
import com.grapsee.grapmail.R
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.ApiException
import com.grapsee.grapmail.data.MailBox
import com.grapsee.grapmail.data.MailStore
import com.grapsee.grapmail.data.Outbox
import com.grapsee.grapmail.data.OutboxItem
import com.grapsee.grapmail.data.SessionRefresh
import java.util.concurrent.TimeUnit

/**
 * Delivers the [Outbox] once the device is online.
 *
 * WorkManager holds the schedule, so the send happens with the app closed, after
 * a reboot, or under Doze (the CONNECTED constraint is what lets the system pick
 * the moment). Failures are sorted by what they mean:
 *
 *   no connection, 5xx, 408, 429   -> retry with exponential backoff
 *   4xx the server decided on       -> give up, tell the person, the draft stays
 *   signed out of that account      -> give up, tell the person
 *   older than Outbox.MAX_AGE_MS    -> give up, tell the person
 */
class OutboxWorker(appContext: Context, params: WorkerParameters) : CoroutineWorker(appContext, params) {

    companion object {
        const val NAME = "grapmail-outbox"
        private const val CHANNEL = "grapmail-outbox"
        private const val NOTIFICATION_BASE = 5300

        /** Schedule a delivery run. Safe to call often: one run at a time, appended. */
        fun schedule(context: Context) {
            val request = OneTimeWorkRequestBuilder<OutboxWorker>()
                .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
        }
    }

    override suspend fun doWork(): Result {
        val items = Outbox.items(applicationContext)
        if (items.isEmpty()) return Result.success()
        // Paused by the server (logic/RemoteConfig.kt): the mail stays queued and
        // the run comes back on the backoff, exactly as if the network were down.
        RemoteConfig.refresh(applicationContext)
        if (RemoteConfig.isPaused("outbox")) return Result.retry()
        var retry = false
        for (item in items) {
            when (deliver(item)) {
                Outcome.SENT -> Outbox.remove(applicationContext, item.id)
                Outcome.GAVE_UP -> Outbox.remove(applicationContext, item.id)
                Outcome.RETRY -> retry = true
            }
        }
        MailStore.invalidate(MailBox.Sent)
        MailStore.invalidate(MailBox.Drafts)
        return if (retry) Result.retry() else Result.success()
    }

    private enum class Outcome { SENT, GAVE_UP, RETRY }

    private suspend fun deliver(item: OutboxItem): Outcome {
        if (System.currentTimeMillis() - item.createdAt > Outbox.MAX_AGE_MS) {
            notifyFailed(item, "It waited too long without a connection.")
            return Outcome.GAVE_UP
        }
        val session = try {
            SessionRefresh.forAccount(applicationContext, item.sessionId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            return Outcome.RETRY
        }
        if (session == null) {
            notifyFailed(item, "You are signed out of the account it was sent from.")
            return Outcome.GAVE_UP
        }
        return try {
            val r = Api.mailSend(session.token, item.request, idempotencyKey = item.idempotencyKey)
            val accepted = r.status == "sent" || r.status == "queued" || r.status == "delivered"
            if (!accepted) {
                notifyFailed(item, r.deliveryError ?: r.error ?: "The server did not accept it.")
                return Outcome.GAVE_UP
            }
            if (item.draftId.isNotEmpty()) runCatching { Api.mailDeleteMessage(session.token, item.draftId) }
            AppReview.countSend(applicationContext)
            Outcome.SENT
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: ApiException) {
            if (e.status >= 500 || e.status == 408 || e.status == 429) {
                Outbox.recordAttempt(applicationContext, item.id, "HTTP ${e.status}")
                Outcome.RETRY
            } else {
                notifyFailed(item, e.message ?: "The server refused it.")
                Outcome.GAVE_UP
            }
        } catch (_: Exception) {
            Outbox.recordAttempt(applicationContext, item.id, "offline")
            Outcome.RETRY
        }
    }

    @android.annotation.SuppressLint("MissingPermission") // checked: Notifications.allowed
    private fun notifyFailed(item: OutboxItem, reason: String) {
        val context = applicationContext
        // Without notification permission the failure is still visible: the
        // draft stays in Drafts. Nothing is posted that the person turned off.
        if (!Notifications.allowed(context)) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = context.getSystemService(NotificationManager::class.java)
            if (manager.getNotificationChannel(CHANNEL) == null) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL, "Unsent mail", NotificationManager.IMPORTANCE_DEFAULT).apply {
                        description = "Mail from the Outbox that could not be sent"
                    },
                )
            }
        }
        val subject = item.request.subject.ifBlank { "(no subject)" }
        val open = PendingIntent.getActivity(
            context, item.id.hashCode(),
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setContentTitle("Not sent: $subject")
            .setContentText(reason + if (item.draftId.isNotEmpty()) " It is still in Drafts." else "")
            .setStyle(NotificationCompat.BigTextStyle().bigText(reason + if (item.draftId.isNotEmpty()) " It is still in Drafts." else ""))
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(NOTIFICATION_BASE + (item.id.hashCode() and 0xfff), notification) }
    }
}
