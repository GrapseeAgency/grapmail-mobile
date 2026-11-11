package com.grapsee.grapmail.logic

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.grapsee.grapmail.MainActivity
import com.grapsee.grapmail.R

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

    /**
     * Say what arrived. Returns false when nothing was posted — a denied
     * permission, or a system that would not take it — which the caller reports
     * as "nothing announced", never as a success.
     */
    fun newMail(context: Context, grew: Int, total: Int): Boolean {
        if (!allowed(context)) return false
        ensureChannel(context)
        val content = NotificationCompat.Builder(context, CHANNEL_NEW_MAIL)
            // The small icon is a monochrome alpha silhouette — the platform tints it;
            // the full-color mark only ever appears on the launcher and the splash.
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setContentTitle("$grew new message" + (if (grew == 1) "" else "s"))
            .setContentText("$total unread in the inbox")
            .setStyle(androidx.core.app.NotificationCompat.InboxStyle()
                .setSummaryText("$total unread in the inbox"))
            .setContentIntent(pendingOpen(context))
            .setAutoCancel(true)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, content)
            true
        } catch (e: SecurityException) {
            // The grant can disappear between the check and the post. Said as a
            // failure, not swallowed: the poll's caller logs that nothing was
            // announced.
            false
        }
    }

    /**
     * The push path's placeholder. The payload carries no content, so the only
     * true sentence available is that the server sent a signal; the counted
     * summary that follows replaces this under the same id. Returns false when
     * the permission or the channel refused, exactly like [newMail].
     */
    fun pushSignal(context: Context, address: String?): Boolean {
        if (!allowed(context)) return false
        ensureChannel(context)
        val content = NotificationCompat.Builder(context, CHANNEL_NEW_MAIL)
            // The small icon is a monochrome alpha silhouette — the platform tints it;
            // the full-color mark only ever appears on the launcher and the splash.
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setContentTitle("New mail")
            .setContentText(if (address.isNullOrBlank()) "A new-mail signal arrived for this account."
            else "A new-mail signal arrived for $address.")
            .setContentIntent(pendingOpen(context))
            .setAutoCancel(true)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, content)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    private fun pendingOpen(context: Context) = PendingIntent.getActivity(
        context, 0,
        Intent(context, MainActivity::class.java).apply {
            action = Intent.ACTION_MAIN
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
}
