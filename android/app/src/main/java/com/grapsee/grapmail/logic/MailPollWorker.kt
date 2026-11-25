package com.grapsee.grapmail.logic

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.ApiException
import com.grapsee.grapmail.data.SessionStore
import com.grapsee.grapmail.data.MailStatsResponse
import com.grapsee.grapmail.data.MailBox
import com.grapsee.grapmail.data.MailMessageDto
import com.grapsee.grapmail.data.MailMessageFullDto
import com.grapsee.grapmail.data.MailStore
import com.grapsee.grapmail.data.SessionSnapshot
import com.grapsee.grapmail.R
import com.grapsee.grapmail.ui.i18n.CoreStrings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Resolves push IDs through the authenticated message endpoint. Scheduled runs
 *  compare recent inbox rows and unread counts when push delivery is unavailable. */
class MailPollWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        /** One unique schedule, named once: the app enqueues it and the system
         *  keeps exactly one of it across launches and reboots. */
        const val NAME = "grapmail-mail-poll"
        const val PUSH_NAME = "grapmail-push-refresh"
        private val activePoll = Mutex()
        private val completedPolls = MutableSharedFlow<PollResult>(extraBufferCapacity = 1)
        val results = completedPolls.asSharedFlow()
        /** The logcat tag the CI walk greps. Named here so the walk and the
         *  worker cannot drift apart without the pin noticing. */
        const val LOG_TAG = "GrapMailPoll"
    }

    data class PollResult(val sessionId: String, val stats: MailStatsResponse)

    /** One line per run, saying which branch decided and why. */
    private fun report(branch: String) {
        android.util.Log.i(LOG_TAG, branch)
    }

    override suspend fun doWork(): Result = activePoll.withLock { pollMailbox() }

    private val privatePreview get() = inputData.getBoolean("privatePreview", false) || PushSync.genericPreviews(applicationContext)

    private suspend fun pollMailbox(): Result {
        val session = SessionStore.snapshot(applicationContext)
        if (session == null) {
            // Nothing signed in means the widget has nothing true to draw; it
            // is cleared rather than left holding a stale count.
            com.grapsee.grapmail.widget.MailWidgetState.clear(applicationContext)
            report("silent signed-out")
            return Result.success()
        }
        // Keep this device's copy of the notification rules current (at most every
        // six hours); a rule changed on another device then applies here too.
        NotificationPolicy.refreshIfStale(applicationContext, session.address, session.token)
        val pushedId = inputData.getString("messageId")
        if (pushedId != null && !inputData.getString("mailbox").equals(session.address, ignoreCase = true)) {
            val saved = SessionStore.snapshotAll(applicationContext).firstOrNull {
                it.address.equals(inputData.getString("mailbox"), ignoreCase = true)
            } ?: return Result.success()
            if (!Notifications.allowed(applicationContext)) return Result.success()
            val fresh = com.grapsee.grapmail.data.SessionRefresh.forAccount(applicationContext, saved.id)
                ?: return Result.success()
            return notifyExact(fresh, pushedId) ?: notifyFromInbox(fresh, pushedId)
        }
        if (pushedId != null && Notifications.allowed(applicationContext)) {
            notifyExact(session, pushedId)?.let { return it }
        }
        val token = session.token
        val stats = runCatching { Api.mailStats(token) }
            .onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                // The status is the server's answer, not something this file
                // invented: a refused read is the branch a person would call
                // "the app stopped telling me", and it is also the branch that
                // lets a CI run name an upstream refusal instead of guessing it.
                val status = (error as? ApiException)?.status
                report("silent read-refused" + (status?.let { " status=$it" } ?: ""))
                if (pushedId != null && runAttemptCount < 3 && (status == null || status == 429 || status >= 500)) {
                    return Result.retry()
                }
            }
            // A refused read is not a failure worth retrying in a loop the
            // person never asked for: the next scheduled run asks again, and
            // nothing is announced meanwhile.
            .getOrNull() ?: return Result.success()
        if (!SessionStore.isCurrent(applicationContext, session.id)) {
            report("silent session changed")
            return Result.success()
        }
        completedPolls.tryEmit(PollResult(session.id, stats))
        val unread = stats.inbox?.unread
        if (unread == null) {
            report("silent no-inbox-count")
            return Result.success()
        }
        val prefs = applicationContext
            .getSharedPreferences("grapmail-poll", Context.MODE_PRIVATE)
        val sawKey = "lastUnread"
        val hadPrevious = prefs.getString("sessionId", null) == session.id && prefs.contains(sawKey)
        val previous = prefs.getInt(sawKey, 0)
        val priorHead = prefs.getString("inboxHead", null).takeIf { hadPrevious }
        val recent = if (Notifications.allowed(applicationContext)) {
            runCatching { Api.mailInbox(token, MailBox.Inbox, pageSize = 12).messages.filter { it.id.isNotBlank() } }
                .onFailure {
                    if (it is kotlinx.coroutines.CancellationException) throw it
                    if (pushedId != null && runAttemptCount < 3) return Result.retry()
                }
                .getOrNull()
        } else null
        if (!SessionStore.isCurrent(applicationContext, session.id)) return Result.success()
        // The widget follows the read this poll just made: its count and the
        // newest sender line, nothing the poll did not witness.
        com.grapsee.grapmail.widget.MailWidgetState.publish(
            applicationContext, unread,
            recent?.firstOrNull()?.let { head ->
                if (head.isE2eEncrypted) CoreStrings.get(R.string.core_widget_encrypted_message) { "Encrypted message" }
                else (head.from?.name.orEmpty()).takeIf { it.isNotBlank() } ?: head.subject
            },
            recent = recent?.take(3)?.map { m ->
                if (m.isE2eEncrypted) return@map CoreStrings.get(R.string.core_widget_encrypted_message) { "Encrypted message" } to ""
                val who = (m.from?.name.orEmpty()).takeIf { it.isNotBlank() } ?: CoreStrings.get(R.string.core_widget_unknown_sender) { "Unknown sender" }
                who to (m.subject ?: CoreStrings.get(R.string.core_notif_no_subject) { "(no subject)" })
            },
        )
        val announced = announcedIds(prefs, session.id)
        val pushedMessage = recent?.firstOrNull {
            it.id == pushedId && it.id !in announced && !it.isRead && it.direction != "outbound"
        }
        val update = prefs.edit().putString("sessionId", session.id).putInt(sawKey, unread)
        if (recent != null) update.putString("inboxHead", recent.firstOrNull()?.id.orEmpty())
        else update.remove("inboxHead")
        update.apply()
        if (pushedId != null && pushedMessage == null) return Result.success()
        if (!hadPrevious && pushedMessage == null) {
            report("silent first-read unread=$unread")
            return Result.success()
        }
        val arrived = when {
            priorHead == "" -> recent
            priorHead != null && recent?.any { it.id == priorHead } == true ->
                recent.takeWhile { it.id != priorHead }
            else -> null
        }?.filter { !it.isRead && it.direction != "outbound" && it.id !in announced }
        val grew = if (pushedMessage != null) 1 else arrived?.size ?: (unread - previous)
        if (grew <= 0) {
            report("silent no-rise $previous->$unread")
            return Result.success()
        }
        com.grapsee.grapmail.logic.MailboxEvents.emitInboxChanged()
        val message = pushedMessage ?: arrived?.singleOrNull()?.takeIf { grew == 1 }
        val threadId = message?.threadId
        val page = if (threadId != null) {
            kotlinx.coroutines.withTimeoutOrNull(2_500) {
                runCatching { Api.mailThread(token, threadId) }
                    .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                    .getOrNull()?.also {
                        com.grapsee.grapmail.data.MailStore.putThread(
                            threadId, it.messages, it.nextCursor, it.hasMore, applicationContext, session.id,
                        )
                    }
            }
        } else null
        val image = page?.messages?.firstOrNull { it.id == message?.id && !it.isE2eEncrypted }
            ?.attachments?.firstOrNull {
                it.id.isNotBlank() && it.mimeType in setOf("image/jpeg", "image/png", "image/webp", "image/gif") &&
                    (it.sizeBytes ?: 0) in 1..2_097_152
            }
        val thumbnail = if (privatePreview) null else image?.let { notificationImage(token, it.id) }
        if (!SessionStore.isCurrent(applicationContext, session.id)) return Result.success()
        val posted = Notifications.newMail(
            applicationContext, grew = grew, total = unread,
            message = message, account = session.address, sessionId = session.id, image = thumbnail, hidePreview = privatePreview,
        )
        if (posted) {
            val announcedNow = if (pushedMessage != null) listOf(pushedMessage) else arrived.orEmpty()
            prefs.edit().putString("pushed:${session.id}",
                (announced + announcedNow.map { it.id }).distinct().takeLast(60).joinToString(" "),
            ).apply()
        }
        report(if (posted) "announced +$grew of $unread"
        else "silent post-refused-by-permission +$grew of $unread")
        return Result.success()
    }

    private suspend fun notifyFromInbox(session: SessionSnapshot, id: String): Result {
        val prefs = applicationContext.getSharedPreferences("grapmail-poll", Context.MODE_PRIVATE)
        val announced = announcedIds(prefs, session.id)
        if (id in announced) return Result.success()

        val messages = try {
            Api.mailInbox(session.token, MailBox.Inbox, pageSize = 12).messages
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: ApiException) {
            return if (runAttemptCount < 3 && (error.status == 429 || error.status >= 500)) Result.retry()
            else Result.success()
        } catch (error: java.io.IOException) {
            return if (runAttemptCount < 3) Result.retry() else Result.success()
        }
        val message = messages.firstOrNull { it.id == id && !it.isRead && it.direction != "outbound" }
            ?: return Result.success()
        if (SessionStore.snapshotAll(applicationContext).none { it.id == session.id }) return Result.success()
        if (Notifications.newMail(applicationContext, 1, 0, message, session.address, session.id, hidePreview = privatePreview)) {
            prefs.edit().putString("pushed:${session.id}", (announced + id).distinct().takeLast(60).joinToString(" ")).apply()
        }
        return Result.success()
    }

    private fun announcedIds(prefs: android.content.SharedPreferences, sessionId: String): List<String> {
        return prefs.getString("announcedIds", null)
            .takeIf { prefs.getString("announcedSessionId", null) == sessionId }
            ?.split(' ')?.filter { it.isNotEmpty() }.orEmpty() +
            prefs.getString("pushed:${sessionId}", "").orEmpty().split(' ').filter { it.isNotEmpty() }
    }

    /** null allows the inbox polling fallback on older gateways. */
    private suspend fun notifyExact(session: SessionSnapshot, id: String): Result? {
        val prefs = applicationContext.getSharedPreferences("grapmail-poll", Context.MODE_PRIVATE)
        val dedup = "pushed:${session.id}"
        val announced = announcedIds(prefs, session.id)
        if (id in announced) return Result.success()
        val message = try {
            Api.notificationMessage(session.token, id)
        } catch (error: kotlinx.coroutines.CancellationException) {
            throw error
        } catch (error: ApiException) {
            if (error.status == 404) return null
            return if (runAttemptCount < 3 && (error.status == 429 || error.status >= 500)) Result.retry()
            else null
        } catch (error: java.io.IOException) {
            return if (runAttemptCount < 3) Result.retry() else null
        } ?: return null
        if (message.id != id || message.isRead || message.direction != "inbound") return Result.success()
        MailStore.saveNotificationMessage(applicationContext, message, session.id)
        val thumbnail = if (privatePreview) null else previewImage(session.token, message)
        if (SessionStore.snapshotAll(applicationContext).none { it.id == session.id }) return Result.success()
        // A sealed envelope is encryption whatever the flag says: the row is
        // drawn as encrypted, and its text never reaches the sign-in code search.
        val sealed = message.isE2eEncrypted || message.e2eEnvelope != null
        val row = MailMessageDto(
            id = message.id, threadId = message.threadId, from = message.from,
            subject = message.subject, snippet = message.snippet ?: message.bodyText?.trim()?.take(200),
            isE2eEncrypted = sealed,
        )
        if (Notifications.newMail(applicationContext, 1, 0, row, session.address, session.id, thumbnail,
                hidePreview = privatePreview, bodyText = message.bodyText?.takeUnless { sealed })) {
            prefs.edit().putString(dedup, (announced + id).distinct().takeLast(60).joinToString(" ")).apply()
            if (!SessionStore.isCurrent(applicationContext, session.id)) return Result.success()
            runCatching { Api.mailStats(session.token) }
                .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
                .getOrNull()?.let { stats ->
                    if (SessionStore.isCurrent(applicationContext, session.id)) {
                        completedPolls.tryEmit(PollResult(session.id, stats))
                        com.grapsee.grapmail.widget.MailWidgetState.publish(
                            applicationContext, stats.inbox?.unread,
                            if (row.isE2eEncrypted) CoreStrings.get(R.string.core_widget_encrypted_message) { "Encrypted message" }
                            else (row.from?.name.orEmpty()).takeIf { it.isNotBlank() } ?: row.subject,
                        )
                    }
                }
        }
        return Result.success()
    }

    private suspend fun previewImage(token: String, message: MailMessageFullDto): android.graphics.Bitmap? {
        if (message.isE2eEncrypted) return null
        val image = message.attachments.firstOrNull {
            it.id.isNotBlank() && it.mimeType in setOf("image/jpeg", "image/png", "image/webp", "image/gif") &&
                (it.sizeBytes ?: 0) in 1..2_097_152
        }
        return image?.let { notificationImage(token, it.id) }
    }

    private suspend fun notificationImage(token: String, id: String): android.graphics.Bitmap? =
        kotlinx.coroutines.withTimeoutOrNull(1_500) {
            val file = java.io.File.createTempFile("mail-preview-", null, applicationContext.cacheDir)
            try {
                Api.mailAttachmentFile(token, id, file)
                if (file.length() !in 1..2_097_152) return@withTimeoutOrNull null
                val options = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
                android.graphics.BitmapFactory.decodeFile(file.path, options)
                if (options.outWidth <= 0 || options.outHeight <= 0) return@withTimeoutOrNull null
                var sample = 1
                while (maxOf(options.outWidth, options.outHeight) / sample > 768) sample *= 2
                options.inJustDecodeBounds = false
                options.inSampleSize = sample
                android.graphics.BitmapFactory.decodeFile(file.path, options)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                null
            } finally {
                file.delete()
            }
        }

}
