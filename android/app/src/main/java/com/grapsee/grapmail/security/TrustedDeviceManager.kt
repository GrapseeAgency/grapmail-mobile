package com.grapsee.grapmail.security

import android.content.Context
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.DeviceEnrollRequest
import com.grapsee.grapmail.data.SessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Enrollment orchestration for this device's identity key.
 *
 * ── THE RULE ───────────────────────────────────────────────────────────────
 * Enroll once per installation, per account, right after a sign-in lands. The
 * enroll endpoint is an upsert keyed on `installationId` (the `x-device-id`
 * [DeviceId] carries), so a repeat with the same key re-confirms rather than
 * duplicates — but the call still costs a round trip, and the flag below is
 * what keeps every launch from paying it. The flag is stored per account
 * email, because one handset can hold several GrapMail accounts and each
 * enrollment binds the key to ONE account's device list.
 *
 * The flag records "this device already told that account about this key". It
 * is not proof the server still trusts the device — a revoke on the web clears
 * nothing here — which is why [requestReEnroll] exists: the trusted-devices
 * screen calls it after a revoke of this device's own row, and the next
 * sign-in re-offers the key instead of assuming the old grant stands.
 *
 * Everything is best-effort: a refusal here must never block the sign-in that
 * triggered it. The next sign-in retries, because a failed enroll does not set
 * the flag.
 */
object TrustedDeviceManager {

    private const val PREFS = "grapmail-trusted-device"
    private const val KEY_ENROLLED_PREFIX = "enrolled."
    private const val KEY_DEVICE_ID_PREFIX = "deviceRow."

    /** Application-lifetime, shared with the push sync: enrollment outlives the screen. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val enrollLock = Mutex()

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun enrolledKey(address: String) = KEY_ENROLLED_PREFIX + address.lowercase()

    private fun deviceIdKey(address: String) = KEY_DEVICE_ID_PREFIX + address.lowercase()

    /** The server-side device row id this enrollment produced, if known. The
     *  trusted-devices screen reads it to mark "This device" on the list. */
    fun enrolledDeviceRowId(context: Context, address: String): String? =
        prefs(context).getString(deviceIdKey(address), null)?.takeIf(String::isNotBlank)

    /**
     * Fire-and-forget entry for the sign-in doors. One completed sign-in, one
     * enrollment attempt; a process death mid-enroll costs nothing but a
     * retry at the next sign-in, because the flag is only written on success.
     */
    fun enrollAfterSignInAsync(context: Context) {
        scope.launch { enrollAfterSignIn(context.applicationContext) }
    }

    /**
     * Enroll this device for every account that currently holds a session and
     * has not been told about this key yet. Runs the keystore work inline —
     * key generation is a one-time, sub-second TEE operation — and the network
     * call on the caller's dispatcher (IO).
     */
    suspend fun enrollAfterSignIn(context: Context) {
        val sessions = SessionStore.snapshotAll(context)
        for (stored in sessions) {
            try {
                val session = com.grapsee.grapmail.data.SessionRefresh.forAccount(context, stored.id) ?: continue
                enroll(context, session.token, session.address)
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                // One account's refusal does not stop the others; the flag is
                // unset, so the next sign-in retries this enrollment.
            }
        }
    }

    /**
     * The upsert itself, guarded so a double fire (sign-in + a fast refresh)
     * cannot send two enroll bodies for the same account in one instant.
     */
    suspend fun enroll(context: Context, token: String, address: String) {
        enrollLock.withLock {
            val preferences = prefs(context)
            if (preferences.getBoolean(enrolledKey(address), false)) return@withLock
            // The context decides the key's biometric gate at generation time:
            // a device with strong biometrics gets a per-operation-gated key,
            // everything else the declared no-biometric fallback.
            val publicKey = DeviceKeyManager.ensurePublicKeyBase64(context)
                ?: throw IllegalStateException("The device key could not be created.")
            val response = Api.enrollDevice(
                token,
                DeviceEnrollRequest(
                    installationId = com.grapsee.grapmail.data.DeviceId.value,
                    publicKey = publicKey,
                    displayName = DeviceKeyManager.deviceDisplayName(),
                ),
            )
            preferences.edit()
                .putBoolean(enrolledKey(address), true)
                .putString(deviceIdKey(address), response.device?.id ?: "")
                .apply()
            // Play Integrity's verdict for the row just written, whenever the
            // deployment is configured for it; unconfigured, this is a quiet
            // no-op — the same discipline as push without its Firebase config.
            IntegrityClient.attestAfterEnrollmentAsync(context, token, response.device?.id)
        }
    }

    /**
     * Forget the enrollment flag for one account: the next sign-in sends the
     * key again. Used after revoking THIS device's own row, and by the
     * trusted-devices screen's explicit re-enroll action.
     */
    fun requestReEnroll(context: Context, address: String) {
        prefs(context).edit()
            .putBoolean(enrolledKey(address), false)
            .remove(deviceIdKey(address))
            .apply()
    }
}
