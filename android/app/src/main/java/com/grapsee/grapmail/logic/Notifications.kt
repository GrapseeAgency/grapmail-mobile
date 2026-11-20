package com.grapsee.grapmail.logic

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.grapsee.grapmail.MainActivity
import com.grapsee.grapmail.R
import com.grapsee.grapmail.data.MailMessageDto
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.MailAction
import com.grapsee.grapmail.data.MailBox
import com.grapsee.grapmail.data.MailStore
import com.grapsee.grapmail.data.SessionStore
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The mail channel and the two producers it carries.
 *
 * ── WHAT THE COUNT IS EVIDENCE OF ──────────────────────────────────────────
 * [newMail] is a report of a read the device itself made — `GET /stats`
 * answered an inbox badge, and it is higher than the badge this app last saw.
 *
 * Every wording choice follows from that. The line gives both numbers: how many
 * arrived since the last read, and how many the server counts unread now. It
 * never says "delivered" (the relay's queue is not the recipient's inbox),
 * never says "you have new mail" after a failed read (nothing was seen), and
 * never fires on a first-ever read, where there is no previous number to
 * compare against and a difference cannot be named.
 *
 * ── WHAT THE SIGNAL IS EVIDENCE OF ─────────────────────────────────────────
 * [pushSignal] is the other producer: the backend woke the device through the
 * platform's push service. The payload carries no message content — mail may be
 * sealed end-to-end, and the server stores a notice, never a plaintext body —
 * so this notification can say only that the server sent a signal, and it says
 * exactly that. The counted summary comes from the refresh that follows, which
 * posts [newMail]'s comparison under the same id and replaces this one when the
 * read answers.
 */
object Notifications {
    const val CHANNEL_NEW_MAIL = "grapmail-new-mail"
    private const val NOTIFICATION_ID = 4200

    /** Create the channel. Idempotent — the system ignores a repeat with the
     *  same id, so this is safe from every entry point that could need it. */
    fun ensureChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_NEW_MAIL,
            context.getString(R.string.notif_channel_new_mail),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notif_channel_new_mail_desc)
            // No badge on the launcher icon: an icon count this app cannot
            // reconcile with the server's own unread number would be a second,
            // contradictory tally of the same thing.
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    /** Whether the person has allowed alerts. Android 13 made this runtime, and
     *  below it the channel's own state is the answer. */
    fun allowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.notificationChannels.any { it.id == CHANNEL_NEW_MAIL &&
                it.importance > NotificationManager.IMPORTANCE_NONE }
        } else {
            manager.areNotificationsEnabled()
        }
    }

    // The entry-path ask is a once-per-install event. The flag records "asked",
    // never "granted" — the system owns that answer — so a denial stays a state
    // the notification settings screen can reverse without this ask repeating
    // at the person, and a grant never gets asked about again.
    private const val ASK_PREFS = "grapmail-notify-ask"
    private const val ASKED_KEY = "asked"

    /** True once per install, after the entry path has asked for the permission. */
    fun wasPermissionAsked(context: Context): Boolean =
        context.getSharedPreferences(ASK_PREFS, Context.MODE_PRIVATE).getBoolean(ASKED_KEY, false)

    /** Record that the entry-path ask happened, whatever the person answered. */
    fun markPermissionAsked(context: Context) {
        context.getSharedPreferences(ASK_PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(ASKED_KEY, true).apply()
    }

    /**
     * Say what arrived. Returns false when nothing was posted — a denied
     * permission, or a system that would not take it — which the caller reports
     * as "nothing announced", never as a success.
     */
    data class MailTarget(val messageId: String, val threadId: String?, val sessionId: String, val action: String)

    const val OPEN_MAIL = "com.grapsee.grapmail.OPEN_MAIL"

    /** The inline reply's RemoteInput key; the typed text arrives under it. */
    const val REPLY_INPUT = "grapmail.reply.text"

    fun target(intent: Intent?): MailTarget? {
        if (intent?.action != OPEN_MAIL) return null
        val id = intent.getStringExtra("messageId")?.takeIf { it.isNotBlank() } ?: return null
        val session = intent.getStringExtra("sessionId") ?: return null
        return MailTarget(id, intent.getStringExtra("threadId"), session, intent.getStringExtra("mailAction") ?: "open")
    }

    fun newMail(context: Context, grew: Int, total: Int, message: MailMessageDto? = null,
                account: String? = null, sessionId: String? = null, image: Bitmap? = null): Boolean {
        if (!allowed(context)) return false
        ensureChannel(context)
        val sender = message?.let { it.from?.display?.trim()?.takeIf(String::isNotEmpty) ?: "New sender" }
        val summary = "$total unread in the inbox"
        val subject = if (message?.isE2eEncrypted == true) "New encrypted message"
        else message?.subject?.takeIf { it.isNotBlank() } ?: "(no subject)"
        val target = if (message != null && sessionId != null) MailTarget(message.id, message.threadId, sessionId, "open") else null
        val preview = message?.snippet?.trim()?.takeIf { it.isNotEmpty() && !message.isE2eEncrypted }
        val expandedText = if (sender != null) listOfNotNull(subject, preview).joinToString("\n") else summary
        val compactText = if (sender != null) listOfNotNull(subject, preview).joinToString(" · ") else summary
        val content = NotificationCompat.Builder(context, CHANNEL_NEW_MAIL)
            // The small icon is a monochrome alpha silhouette — the platform tints it;
            // the full-color mark only ever appears on the launcher and the splash.
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setContentTitle(sender ?: "$grew new message" + (if (grew == 1) "" else "s"))
            .setContentText(compactText)
            .setSubText(account?.takeIf { it.isNotBlank() })
            .setStyle(if (image != null) {
                NotificationCompat.BigPictureStyle().bigPicture(image).setSummaryText(expandedText)
            } else {
                NotificationCompat.BigTextStyle().bigText(expandedText)
            })
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .addExtras(android.os.Bundle().apply { putString("grapmail.session", sessionId) })
            .apply {
                if (sender != null) setLargeIcon(senderAvatar(sender, message?.from?.address ?: sender))
                if (target != null) {
                    addAction(0, "Archive", pendingUpdate(context, target.copy(action = "archive")))
                    addAction(0, "Mark as read", pendingUpdate(context, target.copy(action = "read")))
                    val replyAddress = message?.from?.address?.trim()?.takeIf { it.isNotEmpty() }
                    if (replyAddress != null && message?.isE2eEncrypted != true) {
                        addAction(NotificationCompat.Action.Builder(
                            0, "Reply",
                            pendingReply(context, target, replyAddress, subject),
                        ).addRemoteInput(
                            android.app.RemoteInput.Builder(REPLY_INPUT).setLabel("Reply").build(),
                        ).build())
                    } else {
                        addAction(0, "Reply", pendingOpen(context, target.copy(action = "reply")))
                    }
                    addAction(0, "More", pendingOpen(context, target.copy(action = "more")))
                }
            }
            .setContentIntent(pendingOpen(context, target))
            .setAutoCancel(true)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(message?.id, NOTIFICATION_ID, content)
            true
        } catch (e: SecurityException) {
            // The grant can disappear between the check and the post. Said as a
            // failure, not swallowed: the poll's caller logs that nothing was
            // announced.
            false
        }
    }

    private fun senderAvatar(sender: String, identity: String): Bitmap {
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.HSVToColor(floatArrayOf(Math.floorMod(identity.lowercase().hashCode(), 360).toFloat(), 0.65f, 0.63f))
        canvas.drawCircle(64f, 64f, 64f, paint)
        paint.color = Color.WHITE
        paint.textSize = 56f
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        val initial = String(Character.toChars(sender.codePointAt(0))).uppercase()
        canvas.drawText(initial, 64f, 64f - (paint.ascent() + paint.descent()) / 2f, paint)
        return bitmap
    }

    fun dismiss(context: Context, id: String) {
        NotificationManagerCompat.from(context).cancel(id, NOTIFICATION_ID)
    }

    private fun pendingUpdate(context: Context, target: MailTarget) = PendingIntent.getBroadcast(
        context, target.hashCode(),
        Intent(context, MailNotificationActionReceiver::class.java).apply {
            action = OPEN_MAIL
            data = android.net.Uri.Builder().scheme("grapmail").authority("notification")
                .appendPath(target.messageId).appendPath(target.action).build()
            putExtra("messageId", target.messageId)
            putExtra("sessionId", target.sessionId)
            putExtra("mailAction", target.action)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun pendingOpen(context: Context, target: MailTarget? = null) = PendingIntent.getActivity(
        context, target?.hashCode() ?: 0,
        Intent(context, MainActivity::class.java).apply {
            action = if (target == null) Intent.ACTION_MAIN else OPEN_MAIL
            target?.let {
                data = android.net.Uri.Builder().scheme("grapmail").authority("notification")
                    .appendPath(it.messageId).appendPath(it.action).build()
                putExtra("messageId", it.messageId)
                putExtra("threadId", it.threadId)
                putExtra("sessionId", it.sessionId)
                putExtra("mailAction", it.action)
            }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    /**
     * The inline reply rides a broadcast so the text field can answer without
     * the app opening: RemoteInput carries the typed text back into
     * [MailNotificationActionReceiver], which sends it through the same
     * endpoint a composer reply uses.
     *
     * Mutable by design — RemoteInput requires a mutable pending intent on
     * Android 12+, and the flag is only set where the platform knows it.
     */
    private fun pendingReply(
        context: Context, target: MailTarget, replyAddress: String, subject: String,
    ) = PendingIntent.getBroadcast(
        context, target.hashCode() + 17,
        Intent(context, MailNotificationActionReceiver::class.java).apply {
            action = OPEN_MAIL
            data = android.net.Uri.Builder().scheme("grapmail").authority("notification")
                .appendPath(target.messageId).appendPath("reply").build()
            putExtra("messageId", target.messageId)
            putExtra("threadId", target.threadId)
            putExtra("sessionId", target.sessionId)
            putExtra("mailAction", "reply.text")
            putExtra("replyAddress", replyAddress)
            putExtra("replySubject", subject)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or
            (if (android.os.Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0),
    )
}

class MailNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val target = Notifications.target(intent) ?: return
        val action = when (target.action) {
            "archive" -> MailAction.Archive
            "read" -> MailAction.Read
            "reply.text" -> null
            else -> return
        }
        val pending = goAsync()
        val app = context.applicationContext
        PushSync.scope.launch {
            try {
                withTimeoutOrNull(8_000) {
                    val session = com.grapsee.grapmail.data.SessionRefresh.forAccount(app, target.sessionId)
                        ?: return@withTimeoutOrNull
                    if (action == null) {
                        replyFromNotification(app, intent, target, session.token)
                        return@withTimeoutOrNull
                    }
                    Api.mailAction(session.token, target.messageId, action)
                    Notifications.dismiss(app, target.messageId)
                    if (!SessionStore.isCurrent(app, session.id)) return@withTimeoutOrNull
                    if (action == MailAction.Read) {
                        MailStore.patchFlags(target.messageId, read = true, sessionContext = app, sessionId = session.id)
                    } else {
                        MailStore.remove(target.messageId, MailBox.Inbox, app, session.id)
                        MailStore.invalidate(MailBox.Archive, app, session.id)
                    }
                    MailStore.invalidate(MailBox.Inbox, app, session.id)
                    Notifications.dismiss(app, target.messageId)
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                android.util.Log.w("GrapMailNotify", "Notification action failed: ${error.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }

    /**
     * The typed reply, sent through the same authenticated endpoint the
     * composer uses — threading by the message id that produced the
     * notification, subject prefixed the way every mail client prefixes it.
     * The notification is dismissed only after the server accepted it: a
     * refusal keeps the shade entry so the text is not lost with it.
     */
    private suspend fun replyFromNotification(
        app: Context, intent: Intent, target: Notifications.MailTarget, token: String,
    ) {
        val text = android.app.RemoteInput.getResultsFromIntent(intent)
            ?.getCharSequence(Notifications.REPLY_INPUT)?.toString()
            ?.trim().orEmpty()
        if (text.isEmpty()) return
        val address = intent.getStringExtra("replyAddress")?.takeIf { it.isNotBlank() } ?: return
        val subject = intent.getStringExtra("replySubject").orEmpty()
        val replySubject = if (subject.lowercase().startsWith("re:")) subject else "Re: $subject"
        val r = Api.mailSend(token, com.grapsee.grapmail.data.MailSendRequest(
            to = listOf(address), subject = replySubject,
            bodyText = text, inReplyTo = target.messageId,
        ))
        if (r.status in listOf("sent", "queued", "delivered")) {
            Notifications.dismiss(app, target.messageId)
        }
    }
}
