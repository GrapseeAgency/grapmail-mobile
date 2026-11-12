package com.grapsee.grapmail.logic

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.SessionStore
import com.grapsee.grapmail.data.MailStatsResponse
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex

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
 *
 * ── WHY THE BRANCH IS WRITTEN TO THE SYSTEM LOG ────────────────────────────
 * A background read that stays silent is indistinguishable from one that never
 * ran, and "indistinguishable" is the whole problem: the honest branches above
 * are the reason the feature exists, and nothing outside the process could see
 * which one was taken. So each run names its branch in logcat under
 * `GrapMailPoll`. Nothing is computed for the log — every line reports the
 * decision the code had already made, with the server's own status when the
 * refusal is the server's — and no screen reads it.
 */
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

    override suspend fun doWork(): Result {
        if (!activePoll.tryLock()) return Result.success()
        return try {
            pollMailbox()
        } finally {
            activePoll.unlock()
        }
    }

    private suspend fun pollMailbox(): Result {
        val session = SessionStore.snapshot(applicationContext)
        if (session == null) {
            report("silent signed-out")
            return Result.success()
        }
        val token = session.token
        val stats = runCatching { Api.mailStats(token) }
            .onFailure { error ->
                if (error is kotlinx.coroutines.CancellationException) throw error
                // The status is the server's answer, not something this file
                // invented: a refused read is the branch a person would call
                // "the app stopped telling me", and it is also the branch that
                // lets a CI run name an upstream refusal instead of guessing it.
                val status = (error as? com.grapsee.grapmail.data.ApiException)?.status
                report("silent read-refused" + (status?.let { " status=$it" } ?: ""))
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
        prefs.edit().putString("sessionId", session.id).putInt(sawKey, unread).apply()
        if (!hadPrevious) {
            report("silent first-read unread=$unread")
            return Result.success()
        }
        if (unread <= previous) {
            report("silent no-rise $previous->$unread")
            return Result.success()
        }
        // The post itself can be refused by the permission; either way the read
        // happened and the new baseline is stored, so the next run does not
        // re-announce the same arrivals.
        val posted = Notifications.newMail(
            applicationContext, grew = unread - previous, total = unread,
        )
        report(if (posted) "announced +${unread - previous} of $unread"
        else "silent post-refused-by-permission +${unread - previous} of $unread")
        return Result.success()
    }
}
