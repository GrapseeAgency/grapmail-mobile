package com.grapsee.grapmail.logic

import android.content.Context
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import androidx.work.workDataOf
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.SessionSnapshot
import com.grapsee.grapmail.data.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * The push half of new-mail delivery, beside the poll (which stays: a signal
 * can be lost, and WorkManager reads whether or not any arrived).
 *
 * ── WHAT TRAVELS ───────────────────────────────────────────────────────────
 * Registration: the FCM device token, the platform and this device's id, with
 * the session bearer — so the backend binds a token to an account it has
 * authenticated, never to an anonymous device. The data payload carries no
 * message content: mail can be sealed end-to-end and the server stores a
 * notice, never a plaintext body, so the most the server could say about a
 * message it cannot open is "one arrived".
 *
 * ── WITHOUT FIREBASE CONFIG ────────────────────────────────────────────────
 * The client config (`google-services.json`) is provisioned out of the repo,
 * and a build without it has no FirebaseApp — every Firebase touch here is
 * gated on that and degrades to a quiet no-op, which is what makes a local
 * build usable without the founder's project: the feature is absent, not
 * failing, and the poll still reports new mail.
 */
class PushService : FirebaseMessagingService() {

    override fun onNewToken(token: String) {
        // A rotated token must reach the backend the same way the first one
        // did: with the bearer, only if a session is held. Without one it is
        // parked in the prefs and travels at the next sign-in or launch.
        PushSync.onDeviceToken(this, token)
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val context = applicationContext
        val id = message.data["messageId"]?.takeIf { it.isNotBlank() && it.length <= 300 }
        val mailbox = message.data["mailbox"]
        val request = OneTimeWorkRequestBuilder<MailPollWorker>()
            .setInputData(workDataOf("messageId" to id, "mailbox" to mailbox,
                "threadId" to message.data["threadId"], "sender" to message.data["sender"],
                "subject" to message.data["subject"], "encrypted" to message.data["encrypted"]))
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
            message.priority == RemoteMessage.PRIORITY_HIGH
        ) request.setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
        WorkManager.getInstance(context).enqueueUniqueWork(
            id?.let { "${MailPollWorker.PUSH_NAME}:$it" } ?: MailPollWorker.PUSH_NAME,
            ExistingWorkPolicy.KEEP,
            request.build(),
        )
    }
}

/** Registration bookkeeping shared by the service, the sign-in doors and the app's launch. */
object PushSync {
    private const val PREFS = "grapmail-push"
    private const val KEY_TOKEN = "fcmToken"

    /** Application-lifetime: registration outlives any screen that started it. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** A build without the Firebase client config has no initialized app. */
    fun configured(context: Context): Boolean = FirebaseApp.getApps(context).isNotEmpty()

    fun onDeviceToken(context: Context, token: String) {
        prefs(context).edit().putString(KEY_TOKEN, token).apply()
        registerAsync(context)
    }

    fun registerAsync(context: Context) {
        scope.launch { register(context) }
    }

    /**
     * Fetch the current device token and hand it to the backend under the
     * session bearer. A missing config, a missing session or a refused POST
     * each leave the registration where it was — the poll covers delivery.
     */
    suspend fun register(context: Context) {
        if (!configured(context)) return
        if (!Notifications.allowed(context)) return
        val deviceToken = runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull() ?: return
        prefs(context).edit().putString(KEY_TOKEN, deviceToken).apply()
        val sessions = SessionStore.snapshotAll(context)
        for (stored in sessions) {
            val session = com.grapsee.grapmail.data.SessionRefresh.forAccount(context, stored.id) ?: continue
            try {
                Api.registerPush(session.token, deviceToken)
                if (SessionStore.snapshotAll(context).none { it.id == session.id }) {
                    Api.unregisterPush(session.token, deviceToken)
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                android.util.Log.w("GrapMailNotify", "Device registration failed for a saved account")
            }
        }
    }

    /**
     * Retire the registration. Needs the bearer the session still holds, so
     * callers run it BEFORE the session is cleared. Best-effort either way: a
     * refusal here must not block the sign-out it runs beside, and the local
     * record is dropped regardless — a device that believes it is registered
     * when the server does not is the same lie as a registration nobody sends.
     */
    suspend fun unregister(context: Context, session: SessionSnapshot) {
        if (!SessionStore.isCurrent(context, session.id)) return
        val deviceToken = prefs(context).getString(KEY_TOKEN, null) ?: return
        if (!configured(context)) return
        try {
            Api.unregisterPush(session.token, deviceToken)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (_: Exception) {
            // Registration teardown is best-effort. Local sign-out still proceeds.
        }
    }

    /** Drop the local registration record without a network call — the path
     *  for an expiry discovered by a 401, where the bearer is already dead and
     *  the next sign-in re-registers the same device token. */
    fun forget(context: Context) {
        prefs(context).edit().remove(KEY_TOKEN).apply()
    }
}
