package com.grapsee.grapmail

import android.app.Application
import android.os.Handler
import android.os.Looper
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.grapsee.grapmail.data.DeviceId
import com.grapsee.grapmail.logic.MailPollWorker
import com.grapsee.grapmail.logic.Notifications
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.launch

class GrapMailApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // First, so a crash anywhere after this is recorded (logic/CrashReporter.kt).
        com.grapsee.grapmail.logic.CrashReporter.install(this)
        DeviceId.init(this)
        // The API layer needs one thing it cannot take from a screen: an
        // application context for the token authenticator, which rotates the
        // gateway pair from an OkHttp thread when a call 401s.
        com.grapsee.grapmail.data.Api.init(this)
        // Offline boot: reload the last session's mailbox snapshot off disk so
        // the first frame of a cold open shows mail instead of a spinner. The
        // network refresh still runs on top of it; the snapshot only decides
        // what the first paint holds.
        com.grapsee.grapmail.data.MailStore.initDisk(this)
        // Channel creation, Firebase registration and WorkManager database boot
        // are not first-frame prerequisites. Run them after the first traversal;
        // notification producers also call ensureChannel defensively, and KEEP
        // preserves the existing periodic schedule.
        Handler(Looper.getMainLooper()).postDelayed({
            Notifications.ensureChannel(this)
            com.grapsee.grapmail.logic.PushSync.registerAsync(this)
            val request = PeriodicWorkRequestBuilder<MailPollWorker>(30, TimeUnit.MINUTES)
                .setConstraints(
                    androidx.work.Constraints.Builder()
                        .setRequiredNetworkType(androidx.work.NetworkType.CONNECTED)
                        .build(),
                )
                .build()
            WorkManager.getInstance(this).enqueueUniquePeriodicWork(
                MailPollWorker.NAME, ExistingPeriodicWorkPolicy.KEEP, request,
            )
            // Anything left in the Outbox by an earlier process goes out as soon as
            // the device is online; a no-op when the Outbox is empty.
            com.grapsee.grapmail.logic.OutboxWorker.schedule(this)
            // This launch, and the last crash or hang if there was one.
            com.grapsee.grapmail.logic.PushSync.scope.launch {
                com.grapsee.grapmail.logic.CrashReporter.sendAtLaunch(this@GrapMailApp)
            }
        }, 1_000)
    }
}
