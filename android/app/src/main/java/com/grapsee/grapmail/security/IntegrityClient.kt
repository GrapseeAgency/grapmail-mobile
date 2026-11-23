package com.grapsee.grapmail.security

import android.content.Context
import com.google.android.play.core.integrity.IntegrityManagerFactory
import com.google.android.play.core.integrity.IntegrityTokenRequest
import com.grapsee.grapmail.data.Api
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

/**
 * Play Integrity, wired but honest.
 *
 * ── WHAT IT DOES ───────────────────────────────────────────────────────────
 * One standard verdict request: the integrity library asks Play for a verdict
 * token bound to this app, and the token is POSTed to the device attest route
 * (POST /grapsee/auth/devices/:id/attest) with `attestationLevel:
 * "play_integrity"` for the device row the enrollment just wrote. The SERVER
 * owns the verdict: it verifies the token with its decoder key, or — on a
 * deployment without `GOOGLE_PLAY_INTEGRITY_DECODER_KEY` — records the level
 * as self-reported. Either way the client's duty is the same: send the token
 * whenever it can obtain one.
 *
 * ── WITHOUT A CLOUD PROJECT ────────────────────────────────────────────────
 * The cloud project number is provisioned outside the repo (the Firebase
 * client config's rule again) as a build config field this file reads. A
 * build without it is an UNCONFIGURED deployment: [canUseIntegrity] answers
 * false and every path here is a quiet no-op — the feature is absent, not
 * failing, and enrollment behaves exactly as it did before. Nothing here
 * retries or surfaces an error: an attest attempt is best-effort by
 * contract, and a failed verdict leaves the row exactly as the enrollment
 * wrote it.
 */
object IntegrityClient {

    /** The attest route's own level word. */
    const val LEVEL_PLAY_INTEGRITY = "play_integrity"

    /** Application-lifetime: the attest attempt outlives the enrollment screen. */
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The feature's switch. The build config field is always declared and
     * empty when unconfigured (a conditionally absent field would be a
     * compile error at every reference), so blank means off.
     */
    fun canUseIntegrity(): Boolean =
        com.grapsee.grapmail.BuildConfig.PLAY_INTEGRITY_CLOUD_PROJECT.isNotBlank()

    /**
     * Fire-and-forget entry for [TrustedDeviceManager.enroll]: called the
     * moment the enroll response names the device row this install just
     * wrote. A blank row id (the enroll response carried none) is a no-op —
     * the attest route is per-row and guessing an id would attest nothing.
     */
    fun attestAfterEnrollmentAsync(context: Context, token: String, rowId: String?) {
        if (!canUseIntegrity()) return
        scope.launch { attestAfterEnrollment(context.applicationContext, token, rowId) }
    }

    /** The attest attempt itself: one token request, one POST, no retries. */
    suspend fun attestAfterEnrollment(context: Context, token: String, rowId: String?) {
        if (!canUseIntegrity()) return
        val row = rowId?.takeIf(String::isNotBlank) ?: return
        try {
            val integrityToken = requestToken(context) ?: return
            Api.attestDevice(token, row, LEVEL_PLAY_INTEGRITY, integrityToken)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // A refused or failed verdict leaves the row as the enrollment
            // wrote it; the server reads the level as self-reported in that
            // case. Nothing to retry, nothing to report.
        }
    }

    /**
     * The standard verdict request. Null when the request could not even be
     * made (an unparseable project number, a refused verdict) — the caller
     * treats that as "no token this time", not as an error.
     */
    private suspend fun requestToken(context: Context): String? {
        val projectNumber = com.grapsee.grapmail.BuildConfig.PLAY_INTEGRITY_CLOUD_PROJECT
            .toLongOrNull() ?: return null
        val manager = IntegrityManagerFactory.create(context)
        val response = manager.requestIntegrityToken(
            IntegrityTokenRequest.builder().setCloudProjectNumber(projectNumber).build(),
        ).await()
        return response.token().takeIf(String::isNotBlank)
    }
}
