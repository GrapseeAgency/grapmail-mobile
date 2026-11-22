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
import kotlinx.coroutines.sync.withLock

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
        // Security alerts post their own notification and never touch the mail
        // read: the payload names an event, not a message, and waking the poll
        // for it would run a mailbox refresh the alert did not ask for. The
        // server renders title/body when the payload carries a notification
        // block; a data-only alert falls back to the event type's own words.
        if (message.data["type"] == "security.alert") {
            Notifications.securityAlert(
                context,
                message.data,
                message.notification?.title,
                message.notification?.body,
            )
            return
        }
        val id = message.data["messageId"]?.takeIf { it.isNotBlank() && it.length <= 300 }
        val mailbox = message.data["mailbox"]?.takeIf { it.length <= 320 }
        if (id != null && mailbox.isNullOrBlank()) return
        val request = OneTimeWorkRequestBuilder<MailPollWorker>()
            .setInputData(workDataOf(
                "messageId" to id,
                "mailbox" to mailbox,
                "privatePreview" to (message.data["privatePreview"] == "1"),
            ))
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

/**
 * One signed-in account's per-account push state on this device, as the
 * per-account notifications screen reads it. `deviceRowId` is null when no
 * registration this app could read a row id from exists — the mute write
 * addresses the row, so the screen shows such an account as not registered
 * rather than offering a toggle wired to nothing.
 */
data class AccountPushState(
    val email: String,
    val deviceRowId: String?,
    val muted: Boolean,
    val preview: String,
)

/** Registration bookkeeping shared by the service, the sign-in doors and the app's launch. */
object PushSync {
    private const val PREFS = "grapmail-push"
    private const val KEY_TOKEN = "fcmToken"

    /** Application-lifetime: registration outlives any screen that started it. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun genericPreviews(context: Context): Boolean = prefs(context).getBoolean("genericPreviews", false)

    fun setGenericPreviews(context: Context, enabled: Boolean) {
        prefs(context).edit().putBoolean("genericPreviews", enabled).apply()
        registerAsync(context.applicationContext)
    }

    // ── per-account mute state ───────────────────────────────────────────────
    //
    // The same prefs store that tracks the FCM token also keeps one record per
    // account: the device row the register route minted for it (`device.id`,
    // the handle the mute write addresses), the mute flag the server last
    // confirmed, and the preview mode the registration carried. Keyed by the
    // lowercased address, the identity the session store answers with.

    private fun deviceRowKey(address: String) = "deviceRow:${address.lowercase()}"
    private fun mutedKey(address: String) = "muted:${address.lowercase()}"
    private fun previewKey(address: String) = "preview:${address.lowercase()}"

    /**
     * Record the row a successful registration minted. The muted flag carries
     * only when the row id is UNCHANGED — the route upserts on (account,
     * token), so a same id means the server still holds the mute it was set
     * on; a NEW id is a row the server created with notifications enabled,
     * and the local flag must say so rather than carry a mute across to a row
     * that never had one.
     */
    fun storeDeviceRow(context: Context, address: String, rowId: String, preview: String?) {
        val preferences = prefs(context)
        val key = address.lowercase()
        val carried = preferences.getString(deviceRowKey(key), null) == rowId &&
            preferences.getBoolean(mutedKey(key), false)
        val editor = preferences.edit().putString(deviceRowKey(key), rowId)
        preview?.let { editor.putString(previewKey(key), it) }
        editor.putBoolean(mutedKey(key), carried).apply()
    }

    /** Flip the stored mute once the server confirmed the same value. */
    fun setMuted(context: Context, address: String, muted: Boolean) {
        prefs(context).edit().putBoolean(mutedKey(address.lowercase()), muted).apply()
    }

    /** Drop the per-account record beside the registration teardown. */
    private fun forgetAccount(context: Context, address: String) {
        val key = address.lowercase()
        prefs(context).edit()
            .remove(deviceRowKey(key))
            .remove(mutedKey(key))
            .remove(previewKey(key))
            .apply()
    }

    /** Every signed-in account with its stored per-account push state. */
    suspend fun accountPushState(context: Context): List<AccountPushState> {
        val preferences = prefs(context)
        return SessionStore.snapshotAll(context).map { stored ->
            val key = stored.address.lowercase()
            AccountPushState(
                email = stored.address,
                deviceRowId = preferences.getString(deviceRowKey(key), null),
                muted = preferences.getBoolean(mutedKey(key), false),
                preview = preferences.getString(previewKey(key), null)
                    ?: if (genericPreviews(context)) "generic" else "full",
            )
        }
    }

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
    private val registrationLock = kotlinx.coroutines.sync.Mutex()

    suspend fun register(context: Context) = registrationLock.withLock {
        if (!configured(context)) return@withLock
        if (!Notifications.allowed(context)) return@withLock
        val deviceToken = runCatching { FirebaseMessaging.getInstance().token.await() }.getOrNull() ?: return@withLock
        prefs(context).edit().putString(KEY_TOKEN, deviceToken).apply()
        val sessions = SessionStore.snapshotAll(context)
        for (stored in sessions) {
            val session = com.grapsee.grapmail.data.SessionRefresh.forAccount(context, stored.id) ?: continue
            try {
                val preferences = prefs(context)
                val preview = preferences.getBoolean("genericPreviews", false)
                    .takeIf { preferences.contains("genericPreviews") }
                val registered = Api.registerPush(session.token, deviceToken, preview)
                if (SessionStore.snapshotAll(context).none { it.id == session.id }) {
                    Api.unregisterPush(session.token, deviceToken)
                } else {
                    // The row id is the per-account mute handle; it is stored
                    // only for an account still held, so a sign-out that raced
                    // the registration cannot leave a record of a row the
                    // unregister above just retired.
                    registered.device?.id?.let { rowId ->
                        storeDeviceRow(
                            context, session.address, rowId,
                            preview?.let { if (it) "generic" else "full" },
                        )
                    }
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
        // The per-account record goes with the registration: the row the
        // DELETE removed no longer exists, and a stored mute against it is the
        // same lie as a registration nobody sends.
        forgetAccount(context, session.address)
    }

    /** Drop the local registration record without a network call — the path
     *  for an expiry discovered by a 401, where the bearer is already dead and
     *  the next sign-in re-registers the same device token. */
    fun forget(context: Context) {
        prefs(context).edit().remove(KEY_TOKEN).apply()
    }
}
