package com.grapsee.grapmail.logic

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.SessionStore

/**
 * The periodic new-mail read.
 *
 * ── WHAT THIS IS EVIDENCE OF ───────────────────────────────────────────────
 * There is no push service in this backend, so nothing can tell this device
 * that mail arrived. This worker does the next real thing: it asks `GET /stats`
 * — the same call the mailbox's own badge uses — and compares the answer to the
 * unread count this device last saw. A notification from here is a report of
 * that comparison.
 *
 * Every branch that cannot state a difference stays silent:
 *   * no token — signed out, so there is no mailbox to report on;
 *   * the read refused — nothing was seen, and "no new mail" would be a claim
 *     about an answer the server did not give;
 *   * no previous number — a first read has no difference to name;
 *   * the count did not rise — nothing arrived that this device does not know.
 *
 * The interval is WorkManager's floor (30 minutes is the shortest period it
 * honours) and the system may run it later than that. No screen promises a
 * minute; the channel description says what actually happens.
 */
class MailPollWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    companion object {
        /** One unique schedule, named once: the app enqueues it and the system
         *  keeps exactly one of it across launches and reboots. */
        const val NAME = "grapmail-mail-poll"
    }

    override suspend fun doWork(): Result {
        val token = SessionStore.token(applicationContext) ?: return Result.success()
        val stats = runCatching { Api.mailStats(token) }.getOrNull()
            // A refused read is not a failure worth retrying in a loop the
            // person never asked for: the next scheduled run asks again, and
            // nothing is announced meanwhile.
            ?: return Result.success()
        val unread = stats.inbox?.unread ?: return Result.success()
        val prefs = applicationContext
            .getSharedPreferences("grapmail-poll", Context.MODE_PRIVATE)
        val sawKey = "lastUnread"
        val hadPrevious = prefs.contains(sawKey)
        val previous = prefs.getInt(sawKey, 0)
        prefs.edit().putInt(sawKey, unread).apply()
        if (!hadPrevious || unread <= previous) return Result.success()
        // The post itself can be refused by the permission; either way the read
        // happened and the new baseline is stored, so the next run does not
        // re-announce the same arrivals.
        Notifications.newMail(applicationContext, grew = unread - previous, total = unread)
        return Result.success()
    }
}
