package com.grapsee.grapmail

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import com.grapsee.grapmail.logic.MailPollWorker

/**
 * Debug-build entry point for the platform-runtime walk.
 *
 * Android's `jobscheduler run -f` starts WorkManager's SystemJobService, but
 * WorkManager still applies its own due-time bookkeeping and may finish the
 * wrapper without running a not-yet-due periodic worker. The walk needs to
 * observe [MailPollWorker] itself, not mistake that wrapper decision for the
 * worker staying silent, so this receiver enqueues the same worker as one-time
 * work when CI asks.
 *
 * The class and its manifest entry live under `src/debug`: neither exists in
 * the Play release. Production still uses the periodic request declared by
 * [GrapMailApp] and the push-triggered one-time request in PushService.
 */
class DebugPollReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION) return
        WorkManager.getInstance(context).enqueue(
            OneTimeWorkRequestBuilder<MailPollWorker>().build(),
        )
        Log.i(LOG_TAG, "enqueued one-time MailPollWorker for runtime proof")
    }

    companion object {
        const val ACTION = "com.grapsee.grapmail.DEBUG_RUN_POLL"
        private const val LOG_TAG = "GrapMailPollTrigger"
    }
}
