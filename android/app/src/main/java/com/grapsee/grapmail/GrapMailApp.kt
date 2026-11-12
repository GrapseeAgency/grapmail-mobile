package com.grapsee.grapmail

import android.app.Application
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.grapsee.grapmail.data.DeviceId
import com.grapsee.grapmail.logic.MailPollWorker
import com.grapsee.grapmail.logic.Notifications
import java.util.concurrent.TimeUnit

class GrapMailApp : Application() {
    override fun onCreate() {
        super.onCreate()
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
        // The channel exists before anything can post to it. Creating it here
        // rather than at first notify means a notification raised by a process
        // the person never opened — the poll, woken by the system — is not
        // dropped for a channel that was never declared.
        Notifications.ensureChannel(this)
        // A restored session re-announces the device to the push service: a
        // token minted while signed out is parked until this moment, and a
        // registration the backend pruned is renewed. A no-op without the
        // Firebase client config or without a session; the poll is the
        // delivery either way.
        com.grapsee.grapmail.logic.PushSync.registerAsync(this)
        // The new-mail read. KEEP, not REPLACE: every launch replacing the
        // schedule would reset the system's own window and could starve the
        // poll the person expects to keep running while the app is closed.
        // Connected is a real requirement, not a nicety: this work exists to
        // make one HTTP read, and asking the system to schedule it offline
        // would spend a run on a call that cannot answer.
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
    }
}
