package com.grapsee.grapmail.logic

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipDescription
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Shader
import android.graphics.Typeface
import android.text.SpannableString
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.StyleSpan
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.Person
import androidx.core.app.RemoteInput
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.IconCompat
import com.grapsee.grapmail.MainActivity
import com.grapsee.grapmail.R
import com.grapsee.grapmail.data.MailMessageDto
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.MailAction
import com.grapsee.grapmail.data.MailBox
import com.grapsee.grapmail.data.MailStore
import com.grapsee.grapmail.data.SenderPhotos
import com.grapsee.grapmail.data.SessionStore
import com.grapsee.grapmail.ui.i18n.CoreStrings
import com.grapsee.grapmail.ui.theme.GrapMailAvatarRampDark
import com.grapsee.grapmail.ui.theme.GrapMailAvatarRampLight
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
 *
 * ── HOW A MESSAGE LOOKS IN THE SHADE ───────────────────────────────────────
 * One notification per message (tag = message id, as before), drawn the way a
 * mail client draws it: the sender's photo as the large icon (SenderPhotos, the
 * same cache the list rows use, under a short timeout) or the coloured letter
 * disc the list draws (same ramp, same hash — ui/components/Mail.kt), the
 * sender as the title, the subject in bold and the preview under it
 * (BigTextStyle), and the sender as a Person so the system can rank it.
 *
 * Messages of one account stack into one group with an InboxStyle summary —
 * "3 new messages", one line per message, the account address as summary
 * text. The summary is posted from the second message on, kept up to date as
 * messages are read, archived, replied to or swiped away, and cancelled when
 * the group is empty. It never alerts itself (GROUP_ALERT_CHILDREN).
 *
 * ── SIGN-IN CODES ──────────────────────────────────────────────────────────
 * When [CodeFinder] finds a one-time code in a message that is neither sealed
 * nor preview-hidden, the code leads the title ("482913 · Sender") and a "Copy
 * code" action comes first. The copy is marked sensitive (Android 13+ hides it
 * from the clipboard preview). Sealed mail is never searched: the message's
 * own flag, and the push's `encrypted: "1"` recorded by [markSealed], both stop it.
 */
object Notifications {
    const val CHANNEL_NEW_MAIL = "grapmail-new-mail"

    /**
     * The security channel, distinct from the mail one on purpose: a stranger
     * signing in is not a message waiting, and the channel's IMPORTANCE_HIGH
     * is what lets it break through while a person has mail alerts muted.
     * Quiet hours never apply to this channel — the notification-prefs screen
     * says the same thing on its security row.
     */
    const val CHANNEL_SECURITY = "grapmail-security"
    private const val NOTIFICATION_ID = 4200
    /** The per-account stack's summary; tagged by the group key. */
    private const val SUMMARY_ID = 4201
    private const val SECURITY_NOTIFICATION_ID = 4300

    private const val GROUP_PREFIX = "grapmail.mail."
    private const val EXTRA_LINE = "grapmail.line"
    private const val EXTRA_ACCOUNT = "grapmail.account"
    private const val EXTRA_CODE = "grapmail.code"
    private const val AVATAR_PX = 128
    private const val PHOTO_TIMEOUT_MS = 2_000L
    private const val SUMMARY_LINES = 6

    /** The app context, remembered by the first entry point that had one — the
     *  tap path ([target]) has none of its own and refreshes the stack after
     *  the system auto-cancels the tapped message. */
    @Volatile
    private var appContext: Context? = null
    @Volatile
    private var refreshedOnce = false

    private fun remember(context: Context) {
        if (appContext == null) appContext = context.applicationContext
    }

    /** Create the channels. Idempotent — the system ignores a repeat with the
     *  same id, so this is safe from every entry point that could need it. */
    fun ensureChannel(context: Context) {
        remember(context)
        if (!refreshedOnce) {
            // A cold start (e.g. after the tapped message's process died): the
            // stack may still say "3" for a shade that now holds two.
            refreshedOnce = true
            scheduleSummaryRefresh()
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val mail = NotificationChannel(
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
        val security = NotificationChannel(
            CHANNEL_SECURITY,
            context.getString(R.string.notif_channel_security),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notif_channel_security_desc)
            setShowBadge(false)
        }
        manager.createNotificationChannel(mail)
        manager.createNotificationChannel(security)
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

    // ── sealed messages announced by push ───────────────────────────────────
    //
    // The push payload says `encrypted: "1"` for an end-to-end sealed message
    // (push-dispatch.ts). PushService records it here before the worker fetches
    // the message, so the alert this app draws treats it as sealed even if the
    // fetched row's flag were ever missing: no preview, no code, no copy.

    private const val SEALED_PREFS = "grapmail-notify-sealed"
    private const val SEALED_KEY = "ids"

    fun markSealed(context: Context, messageId: String) {
        if (messageId.isBlank() || messageId.contains(' ')) return
        val prefs = context.getSharedPreferences(SEALED_PREFS, Context.MODE_PRIVATE)
        val ids = prefs.getString(SEALED_KEY, null).orEmpty().split(' ').filter { it.isNotEmpty() && it != messageId }
        prefs.edit().putString(SEALED_KEY, (ids + messageId).takeLast(60).joinToString(" ")).apply()
    }

    private fun isSealed(context: Context, messageId: String?): Boolean {
        if (messageId.isNullOrBlank()) return false
        return context.getSharedPreferences(SEALED_PREFS, Context.MODE_PRIVATE)
            .getString(SEALED_KEY, null).orEmpty().split(' ').contains(messageId)
    }

    /**
     * Say what arrived. Returns false when nothing was posted — a denied
     * permission, or a system that would not take it — which the caller reports
     * as "nothing announced", never as a success.
     */
    data class MailTarget(val messageId: String, val threadId: String?, val sessionId: String, val action: String)

    const val OPEN_MAIL = "com.grapsee.grapmail.OPEN_MAIL"

    /** The broadcast action "Copy code" fires; [MailNotificationActionReceiver] answers it. */
    const val ACTION_COPY_CODE = "com.grapsee.grapmail.COPY_CODE"

    /** The broadcast a swiped-away message notification fires, so the stack's count follows. */
    const val ACTION_MAIL_DISMISSED = "com.grapsee.grapmail.MAIL_DISMISSED"

    /** The inline reply's RemoteInput key; the typed text arrives under it. */
    const val REPLY_INPUT = "grapmail.reply.text"

    fun target(intent: Intent?): MailTarget? {
        if (intent?.action != OPEN_MAIL) return null
        val id = intent.getStringExtra("messageId")?.takeIf { it.isNotBlank() } ?: return null
        val session = intent.getStringExtra("sessionId") ?: return null
        // A tap auto-cancels the message's notification; the stack's summary
        // learns of it a moment later, once the system has removed it.
        scheduleSummaryRefresh()
        return MailTarget(id, intent.getStringExtra("threadId"), session, intent.getStringExtra("mailAction") ?: "open")
    }

    /**
     * [bodyText] is the message's plain text when the caller fetched it (the
     * exact-push path); it widens the sign-in code search beyond the snippet.
     * It is never shown, and never read for a sealed or preview-hidden message.
     */
    suspend fun newMail(context: Context, grew: Int, total: Int, message: MailMessageDto? = null,
                        account: String? = null, sessionId: String? = null, image: Bitmap? = null,
                        hidePreview: Boolean = false, bodyText: String? = null): Boolean {
        remember(context)
        if (!allowed(context)) return false
        // The account's notification rules (logic/NotificationPolicy.kt): the
        // same decision the server makes before it pushes, applied to the alerts
        // this app builds itself — the background check and the push's full alert.
        val decision = message?.let {
            NotificationPolicy.decide(context, account, it.from?.address, it.threadId?.takeIf(String::isNotBlank) ?: it.id)
        }
        if (decision != null && !decision.notify) return false
        if (message == null && !NotificationPolicy.allowsSummary(context, account)) return false
        // Hidden previews: "New mail", no sender, subject, photo or inline reply.
        val generic = decision?.generic == true || NotificationPolicy.isGeneric(context, message?.id)
        if (generic) return genericMail(context, message, account, sessionId)
        ensureChannel(context)
        val encrypted = message?.isE2eEncrypted == true || isSealed(context, message?.id)
        val privateContent = encrypted || hidePreview
        val sender = message?.takeUnless { privateContent }?.let { it.from?.display?.trim()?.takeIf(String::isNotEmpty) ?: CoreStrings.get(R.string.core_notif_new_sender) { "New sender" } }
        val senderAddress = message?.from?.address?.trim()?.takeIf { it.isNotEmpty() }
        val summary = if (total > 0) CoreStrings.plural(R.plurals.core_notif_unread_in_inbox, total, total) { "$total unread in the inbox" } else CoreStrings.get(R.string.core_notif_open_to_read) { "Open GrapMail to read it" }
        val subject = message?.subject?.takeIf { it.isNotBlank() } ?: CoreStrings.get(R.string.core_notif_no_subject) { "(no subject)" }
        val target = if (message != null && sessionId != null) MailTarget(message.id, message.threadId, sessionId, "open") else null
        val preview = message?.snippet?.trim()?.takeIf { it.isNotEmpty() && !encrypted }
        // The sign-in code: never for sealed mail, never where the preview is
        // hidden (the code IS the preview), never without a message.
        val code = if (message != null && !privateContent && sender != null) {
            CodeFinder.find(message.subject, listOfNotNull(message.snippet, bodyText).joinToString("\n"), encrypted = encrypted)
        } else null
        val boldSubject = bold(subject)
        val expandedText: CharSequence = if (sender != null) joined("\n", boldSubject, preview) else summary
        val compactText: CharSequence = if (sender != null) joined(" · ", boldSubject, preview) else summary
        val title = when {
            encrypted -> CoreStrings.get(R.string.core_notif_new_encrypted) { "New encrypted message" }
            sender != null && code != null -> CoreStrings.get(R.string.notif_rich_title_with_code, code, sender) { "$code · $sender" }
            sender != null -> sender
            else -> CoreStrings.plural(R.plurals.core_notif_new_messages, grew, grew) {
                "$grew new message" + (if (grew == 1) "" else "s") }
        }
        // The stack's line for this message: who, then what — or only that one arrived.
        val line: CharSequence = when {
            sender != null -> joined("  ", bold(sender), subject)
            encrypted -> CoreStrings.get(R.string.core_notif_new_encrypted) { "New encrypted message" }
            message != null -> CoreStrings.get(R.string.core_notif_generic_new_mail) { "New mail" }
            else -> title
        }
        val largeIcon = if (sender != null) senderIcon(context, sender, senderAddress) else null
        val group = groupKey(account)
        val tag = message?.id
        val content = NotificationCompat.Builder(context, CHANNEL_NEW_MAIL)
            // The small icon is a monochrome alpha silhouette — the platform tints it;
            // the full-color mark only ever appears on the launcher and the splash.
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(compactText)
            .setSubText(account?.takeIf { !privateContent && it.isNotBlank() })
            .setStyle(if (image != null && !privateContent) {
                NotificationCompat.BigPictureStyle().bigPicture(image).setSummaryText(expandedText)
            } else {
                NotificationCompat.BigTextStyle().bigText(expandedText)
            })
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setGroup(group)
            .addExtras(android.os.Bundle().apply {
                putString("grapmail.session", sessionId)
                putString(EXTRA_ACCOUNT, account)
                putCharSequence(EXTRA_LINE, line)
            })
            .apply {
                if (sender != null && largeIcon != null) {
                    setLargeIcon(largeIcon)
                    addPerson(Person.Builder()
                        .setName(sender)
                        .setIcon(IconCompat.createWithBitmap(largeIcon))
                        .apply { if (senderAddress != null) setKey(senderAddress.lowercase()).setUri("mailto:$senderAddress") }
                        .build())
                }
                if (code != null && message != null) {
                    // First, so it is one of the three buttons the shade shows.
                    addAction(0, CoreStrings.get(R.string.notif_rich_action_copy_code) { "Copy code" },
                        pendingCopy(context, message.id, code))
                }
                if (target != null) {
                    addAction(0, CoreStrings.get(R.string.core_notif_action_archive) { "Archive" }, pendingUpdate(context, target.copy(action = "archive")))
                    addAction(0, CoreStrings.get(R.string.core_notif_action_mark_read) { "Mark as read" }, pendingUpdate(context, target.copy(action = "read")))
                    val replyAddress = message?.from?.address?.trim()?.takeIf { it.isNotEmpty() }
                    if (replyAddress != null && !privateContent) {
                        addAction(NotificationCompat.Action.Builder(
                            0, CoreStrings.get(R.string.core_notif_action_reply) { "Reply" },
                            pendingReply(context, target, replyAddress, subject),
                        ).addRemoteInput(
                            // androidx.core.app.RemoteInput, not android.app:
                            // addRemoteInput takes the compat type, and the
                            // framework one is a different class that does not
                            // convert — it compiled as a type mismatch, not a
                            // behaviour difference.
                            RemoteInput.Builder(REPLY_INPUT).setLabel(CoreStrings.get(R.string.core_notif_action_reply) { "Reply" }).build(),
                        ).build())
                    } else {
                        addAction(0, CoreStrings.get(R.string.core_notif_action_reply) { "Reply" }, pendingOpen(context, target.copy(action = "reply")))
                    }
                    addAction(0, CoreStrings.get(R.string.core_notif_action_more) { "More" }, pendingOpen(context, target.copy(action = "more")))
                }
            }
            .setContentIntent(pendingOpen(context, target))
            .setDeleteIntent(pendingDismissed(context, tag))
            .setAutoCancel(true)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(tag, NOTIFICATION_ID, content)
            refreshSummaries(context, added = Child(tag, group, account, line, System.currentTimeMillis()))
            true
        } catch (e: SecurityException) {
            // The grant can disappear between the check and the post. Said as a
            // failure, not swallowed: the poll's caller logs that nothing was
            // announced.
            false
        }
    }

    /** The alert for an account that hides previews: it says mail arrived, and opens it on a tap. */
    private fun genericMail(context: Context, message: MailMessageDto?, account: String?, sessionId: String?): Boolean {
        ensureChannel(context)
        val target = if (message != null && sessionId != null) MailTarget(message.id, message.threadId, sessionId, "open") else null
        val line = CoreStrings.get(R.string.core_notif_generic_new_mail) { "New mail" }
        val group = groupKey(account)
        val tag = message?.id
        val content = NotificationCompat.Builder(context, CHANNEL_NEW_MAIL)
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setContentTitle("GrapMail")
            .setContentText(line)
            .setSubText(account?.takeIf { it.isNotBlank() })
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setGroup(group)
            .addExtras(android.os.Bundle().apply {
                putString("grapmail.session", sessionId)
                putString(EXTRA_ACCOUNT, account)
                putCharSequence(EXTRA_LINE, line)
            })
            .apply {
                if (target != null) {
                    addAction(0, CoreStrings.get(R.string.core_notif_action_archive) { "Archive" }, pendingUpdate(context, target.copy(action = "archive")))
                    addAction(0, CoreStrings.get(R.string.core_notif_action_mark_read) { "Mark as read" }, pendingUpdate(context, target.copy(action = "read")))
                }
            }
            .setContentIntent(pendingOpen(context, target))
            .setDeleteIntent(pendingDismissed(context, tag))
            .setAutoCancel(true)
            .build()
        return try {
            NotificationManagerCompat.from(context).notify(tag, NOTIFICATION_ID, content)
            refreshSummaries(context, added = Child(tag, group, account, line, System.currentTimeMillis()))
            true
        } catch (e: SecurityException) {
            false
        }
    }

    // ── the sender's face ───────────────────────────────────────────────────

    /**
     * The sender's photo from the list's own cache/endpoint (SenderPhotos —
     * `POST /avatars/resolve` + `GET /avatars/photo`, disk-cached by revision),
     * under [PHOTO_TIMEOUT_MS]; the coloured letter disc when there is none or
     * it is slow. Never throws.
     */
    private suspend fun senderIcon(context: Context, sender: String, address: String?): Bitmap {
        val wanted = address?.takeIf { it.contains("@") }
        val photo = if (wanted == null) null else try {
            SenderPhotos.cached(wanted) ?: withTimeoutOrNull(PHOTO_TIMEOUT_MS) { SenderPhotos.photo(context, wanted) }
        } catch (cancelled: kotlinx.coroutines.CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            null
        }
        val round = photo?.let { runCatching { circle(it.asAndroidBitmap()) }.getOrNull() }
        return round ?: letterAvatar(context, sender, address)
    }

    private fun circle(source: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(AVATAR_PX, AVATAR_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val shader = BitmapShader(source, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        val scale = AVATAR_PX.toFloat() / minOf(source.width, source.height).coerceAtLeast(1)
        shader.setLocalMatrix(Matrix().apply {
            setScale(scale, scale)
            postTranslate((AVATAR_PX - source.width * scale) / 2f, (AVATAR_PX - source.height * scale) / 2f)
        })
        val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG).apply { this.shader = shader }
        canvas.drawCircle(AVATAR_PX / 2f, AVATAR_PX / 2f, AVATAR_PX / 2f, paint)
        return out
    }

    /**
     * The list's letter disc as a bitmap: the same ramp (ui/theme/Color.kt,
     * dark ramp when the system is dark), the same hash of the address (the
     * name only when there is no address) and the same initial rule as
     * `SenderAvatar` in ui/components/Mail.kt — so a sender wears the same
     * colour in the shade as in the inbox.
     */
    private fun letterAvatar(context: Context, sender: String, address: String?): Bitmap {
        val dark = (context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        val ramp = if (dark) GrapMailAvatarRampDark else GrapMailAvatarRampLight
        val tint = ramp[rampIndex((address ?: "").ifBlank { sender }, ramp.size)].toArgb()
        val source = sender.trim().ifBlank { (address ?: "").substringBefore("@") }
        val initial = source.firstOrNull { !it.isWhitespace() }?.uppercaseChar()?.toString() ?: "?"
        val bitmap = Bitmap.createBitmap(AVATAR_PX, AVATAR_PX, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = tint
        canvas.drawCircle(AVATAR_PX / 2f, AVATAR_PX / 2f, AVATAR_PX / 2f, paint)
        paint.color = Color.WHITE
        paint.textSize = AVATAR_PX * 0.44f
        paint.textAlign = Paint.Align.CENTER
        paint.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        canvas.drawText(initial, AVATAR_PX / 2f, AVATAR_PX / 2f - (paint.ascent() + paint.descent()) / 2f, paint)
        return bitmap
    }

    /** Mail.kt's `rampIndex`, kept identical (it is private there). */
    private fun rampIndex(key: String, size: Int): Int {
        var h = 0
        for (ch in key) h = (h * 31 + ch.code) and 0x3FFFFFFF
        return h % size
    }

    private fun bold(text: CharSequence): CharSequence =
        SpannableString(text).apply { setSpan(StyleSpan(Typeface.BOLD), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE) }

    private fun joined(separator: String, vararg parts: CharSequence?): CharSequence {
        val out = SpannableStringBuilder()
        parts.filterNotNull().filter { it.isNotEmpty() }.forEachIndexed { i, part ->
            if (i > 0) out.append(separator)
            out.append(part)
        }
        return out
    }

    // ── the per-account stack ───────────────────────────────────────────────

    private class Child(val tag: String?, val group: String, val account: String?, val line: CharSequence, val time: Long)

    private fun groupKey(account: String?): String =
        GROUP_PREFIX + (account?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: "default")

    private fun scheduleSummaryRefresh() {
        val context = appContext ?: return
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            runCatching { refreshSummaries(context) }
        }, 800)
    }

    /**
     * Recompute every mail stack from what the shade actually holds. [removed]
     * is a message just cancelled and [added] one just posted — the system
     * applies both asynchronously, so the active list may not show them yet.
     *
     * One child and no summary: nothing (a lone message stays a lone message).
     * Two or more, or a summary already up: the summary is posted/updated.
     * None left: the summary is cancelled — only then, because cancelling a
     * group summary also cancels its children.
     */
    private fun refreshSummaries(context: Context, removed: String? = null, added: Child? = null) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        val active = try { manager.activeNotifications } catch (_: Exception) { return } ?: return
        val children = HashMap<String, MutableList<Child>>()
        val summaries = HashSet<String>()
        for (sbn in active) {
            val notification = sbn.notification ?: continue
            val group = notification.group ?: continue
            if (!group.startsWith(GROUP_PREFIX)) continue
            if (sbn.id == SUMMARY_ID) { summaries += group; continue }
            if (sbn.id != NOTIFICATION_ID) continue
            if (removed != null && sbn.tag == removed) continue
            if (added != null && sbn.tag == added.tag) continue
            val extras = notification.extras
            val line = extras.getCharSequence(EXTRA_LINE) ?: extras.getCharSequence(Notification.EXTRA_TITLE) ?: continue
            children.getOrPut(group) { mutableListOf() } += Child(sbn.tag, group, extras.getString(EXTRA_ACCOUNT), line, sbn.postTime)
        }
        if (added != null) children.getOrPut(added.group) { mutableListOf() } += added
        for (group in children.keys + summaries) {
            val list = children[group].orEmpty().sortedByDescending { it.time }
            when {
                list.isEmpty() -> NotificationManagerCompat.from(context).cancel(group, SUMMARY_ID)
                list.size == 1 && group !in summaries -> Unit
                else -> postSummary(context, group, list.firstNotNullOfOrNull { it.account }, list)
            }
        }
    }

    private fun postSummary(context: Context, group: String, account: String?, children: List<Child>) {
        val count = children.size
        val title = CoreStrings.plural(R.plurals.core_notif_new_messages, count, count) {
            "$count new message" + (if (count == 1) "" else "s") }
        val shownAccount = account?.takeIf { it.isNotBlank() }
        val style = NotificationCompat.InboxStyle().setBigContentTitle(title)
        children.take(SUMMARY_LINES).forEach { style.addLine(it.line) }
        if (shownAccount != null) style.setSummaryText(shownAccount)
        val summary = NotificationCompat.Builder(context, CHANNEL_NEW_MAIL)
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(shownAccount ?: children.first().line)
            .setSubText(shownAccount)
            .setStyle(style)
            .setNumber(count)
            .setCategory(NotificationCompat.CATEGORY_EMAIL)
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setGroup(group)
            .setGroupSummary(true)
            // The children ring; the summary only ever re-counts them.
            .setGroupAlertBehavior(NotificationCompat.GROUP_ALERT_CHILDREN)
            .setOnlyAlertOnce(true)
            .addExtras(android.os.Bundle().apply { putString(EXTRA_ACCOUNT, account) })
            .setContentIntent(pendingOpen(context, null))
            .setAutoCancel(true)
            .build()
        try {
            NotificationManagerCompat.from(context).notify(group, SUMMARY_ID, summary)
        } catch (_: SecurityException) {
            // Permission revoked between posts: the children went with it.
        }
    }

    fun dismiss(context: Context, id: String) {
        remember(context)
        NotificationManagerCompat.from(context).cancel(id, NOTIFICATION_ID)
        runCatching { refreshSummaries(context, removed = id) }
    }

    /** A message notification was swiped away: the stack re-counts without it. */
    internal fun onDismissed(context: Context, intent: Intent) {
        val tag = intent.getStringExtra("messageId")?.takeIf { it.isNotBlank() }
        runCatching { refreshSummaries(context.applicationContext, removed = tag) }
    }

    // ── "Copy code" ─────────────────────────────────────────────────────────

    /**
     * Put the code on the clipboard, marked sensitive — the framework key on
     * Android 13+, the documented literal below it (ignored where unknown) —
     * and say so. The code is re-checked against [CodeFinder.isPlausible] so a
     * forged broadcast cannot plant arbitrary text (the receiver is not
     * exported, so this is belt and braces). The toast does not repeat the code.
     */
    internal fun copyCode(context: Context, intent: Intent) {
        val code = intent.getStringExtra(EXTRA_CODE)?.takeIf { CodeFinder.isPlausible(it) } ?: return
        val app = context.applicationContext
        val clipboard = app.getSystemService(ClipboardManager::class.java) ?: return
        val clip = ClipData.newPlainText(CoreStrings.get(R.string.notif_rich_code_clip_label) { "Sign-in code" }, code)
        clip.description.extras = android.os.PersistableBundle().apply {
            putBoolean(
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) ClipDescription.EXTRA_IS_SENSITIVE
                else "android.content.extra.IS_SENSITIVE",
                true,
            )
        }
        try {
            clipboard.setPrimaryClip(clip)
        } catch (e: Exception) {
            android.util.Log.w("GrapMailNotify", "Copy code refused: ${e.javaClass.simpleName}")
            return
        }
        android.widget.Toast.makeText(
            app, CoreStrings.get(R.string.notif_rich_code_copied) { "Code copied" }, android.widget.Toast.LENGTH_SHORT,
        ).show()
    }

    private fun pendingCopy(context: Context, messageId: String, code: String) = PendingIntent.getBroadcast(
        context, (messageId + "|copy").hashCode(),
        Intent(context, MailNotificationActionReceiver::class.java).apply {
            action = ACTION_COPY_CODE
            data = android.net.Uri.Builder().scheme("grapmail").authority("notification")
                .appendPath(messageId).appendPath("copy").build()
            putExtra("messageId", messageId)
            putExtra(EXTRA_CODE, code)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun pendingDismissed(context: Context, tag: String?) = PendingIntent.getBroadcast(
        context, ((tag ?: "") + "|dismissed").hashCode(),
        Intent(context, MailNotificationActionReceiver::class.java).apply {
            action = ACTION_MAIL_DISMISSED
            data = android.net.Uri.Builder().scheme("grapmail").authority("notification")
                .appendPath(tag ?: "summary").appendPath("dismissed").build()
            putExtra("messageId", tag)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    fun dismissSecurity(context: Context, eventId: String) {
        NotificationManagerCompat.from(context).cancel(eventId, SECURITY_NOTIFICATION_ID)
    }

    // ── Security alerts ────────────────────────────────────────────────────────
    //
    // The push payload the backend sends when something happens to the
    // account: data.type == "security.alert", with the event's id, type,
    // action, timestamp and a canAcknowledge flag. The server renders the
    // title and body when the payload carries a notification block; when it
    // does not (data-only), this function falls back to the event type it can
    // name and a line that claims nothing more than that something happened.
    //
    // The security channel is judged by the runtime permission and the app's
    // overall notification state, NOT by the mail channel's switch: muting
    // new-mail alerts must never mute the account's own alarm.

    /** The deep-link action "Secure account" fires; MainActivity routes it to the hub. */
    const val OPEN_SECURITY = "com.grapsee.grapmail.OPEN_SECURITY"

    /** The broadcast action "This was me" fires; [SecurityNotificationActionReceiver] answers it. */
    const val ACTION_SECURITY_ACK = "com.grapsee.grapmail.SECURITY_ACK"

    private fun securityAlertsAllowed(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED) return false
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.areNotificationsEnabled()
    }

    /** The alert's spoken title, when the payload did not carry a rendered one. */
    private fun securityFallbackTitle(eventType: String): String = when {
        eventType.contains("signin", ignoreCase = true) -> CoreStrings.get(R.string.core_notif_security_signin) { "New GrapMail sign-in" }
        eventType.contains("password", ignoreCase = true) -> CoreStrings.get(R.string.core_notif_security_password) { "Your GrapMail password was changed" }
        eventType.contains("device", ignoreCase = true) -> CoreStrings.get(R.string.core_notif_security_device) { "Trusted device change" }
        else -> CoreStrings.get(R.string.core_notif_security_generic) { "GrapMail security alert" }
    }

    fun securityAlert(context: Context, data: Map<String, String>, title: String?, body: String?): Boolean {
        if (!securityAlertsAllowed(context)) return false
        ensureChannel(context)
        val eventId = data["eventId"]?.takeIf { it.isNotBlank() && it.length <= 200 } ?: return false
        val eventType = data["eventType"].orEmpty()
        val canAcknowledge = data["canAcknowledge"] == "1"
        val shownTitle = title?.takeIf { it.isNotBlank() } ?: securityFallbackTitle(eventType)
        val shownBody = body?.takeIf { it.isNotBlank() } ?: CoreStrings.get(R.string.core_notif_security_body) { "Open GrapMail to review this security alert." }
        val content = NotificationCompat.Builder(context, CHANNEL_SECURITY)
            .setSmallIcon(R.mipmap.ic_launcher_monochrome)
            .setContentTitle(shownTitle)
            .setContentText(shownBody)
            .setStyle(NotificationCompat.BigTextStyle().bigText(shownBody))
            .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .apply {
                if (canAcknowledge) {
                    addAction(0, CoreStrings.get(R.string.core_notif_action_this_was_me) { "This was me" }, pendingAck(context, eventId))
                }
                addAction(0, CoreStrings.get(R.string.core_notif_action_secure_account) { "Secure account" }, pendingOpenSecurity(context))
            }
            .build()
        return try {
            // Tagged by the event, so a second alert about a different event
            // arrives as its own entry and an ack dismisses exactly the one
            // that was acknowledged.
            NotificationManagerCompat.from(context).notify(eventId, SECURITY_NOTIFICATION_ID, content)
            true
        } catch (e: SecurityException) {
            false
        }
    }

    private fun pendingAck(context: Context, eventId: String) = PendingIntent.getBroadcast(
        context, eventId.hashCode(),
        Intent(context, SecurityNotificationActionReceiver::class.java).apply {
            action = ACTION_SECURITY_ACK
            data = android.net.Uri.Builder().scheme("grapmail").authority("security")
                .appendPath(eventId).build()
            putExtra("eventId", eventId)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    private fun pendingOpenSecurity(context: Context) = PendingIntent.getActivity(
        context, OPEN_SECURITY.hashCode(),
        Intent(context, MainActivity::class.java).apply {
            action = OPEN_SECURITY
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

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
        // The two local-only answers: no session, no network, no goAsync.
        when (intent.action) {
            Notifications.ACTION_COPY_CODE -> { Notifications.copyCode(context, intent); return }
            Notifications.ACTION_MAIL_DISMISSED -> { Notifications.onDismissed(context, intent); return }
        }
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
        // The send endpoint answers with the outgoing queue's vocabulary —
        // "queued" (always: the worker is the delivery path) and "scheduled"
        // (never here, an inline reply carries no scheduledFor). "sent" stays
        // because an older server build answered with it. "delivered" is an
        // inbound row's status and can never arrive from this call; naming it
        // here would read like the app claiming a delivery it did not see.
        if (r.status in listOf("sent", "queued", "scheduled")) {
            Notifications.dismiss(app, target.messageId)
        }
    }
}

/**
 * The security alert's "This was me" — acknowledges the event through the
 * same endpoint the security hub uses, then dismisses exactly the notification
 * that was acknowledged (tagged by its event id). goAsync plus the push scope,
 * because the call needs a bearer and the bearer may need a rotation first;
 * the notification stays if the ack is refused, so a failed "this was me"
 * never claims it was.
 */
class SecurityNotificationActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Notifications.ACTION_SECURITY_ACK) return
        val eventId = intent.getStringExtra("eventId")?.takeIf { it.isNotBlank() } ?: return
        val pending = goAsync()
        val app = context.applicationContext
        PushSync.scope.launch {
            try {
                withTimeoutOrNull(8_000) {
                    val session = com.grapsee.grapmail.data.SessionRefresh.forAccount(
                        app, SessionStore.snapshot(app)?.id ?: "",
                    ) ?: return@withTimeoutOrNull
                    Api.acknowledgeSecurityEvent(session.token, eventId)
                    Notifications.dismissSecurity(app, eventId)
                }
            } catch (error: Exception) {
                if (error is kotlinx.coroutines.CancellationException) throw error
                android.util.Log.w("GrapMailNotify", "Security ack failed: ${error.javaClass.simpleName}")
            } finally {
                pending.finish()
            }
        }
    }
}
