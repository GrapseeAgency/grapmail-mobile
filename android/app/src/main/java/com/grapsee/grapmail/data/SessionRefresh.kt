package com.grapsee.grapmail.data

import android.content.Context
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Rotating the gateway's JWT pair.
 *
 * The access token lives one hour and the refresh token thirty days, so a
 * session that cannot refresh silently dies hourly while the person is reading
 * mail. The web client has done this since it moved to the gateway; the app has
 * to do the same or the migration would trade one broken screen for another.
 *
 * Rotation is single-flight. The gateway invalidates the presented refresh token
 * on every rotation, so two concurrent renewals mean the second one presents a
 * token the first just spent: it is refused, its caller sees a 401, and the
 * person is signed out of a session that is perfectly alive. The mailbox fires
 * several calls at once (list, stats, me), which is exactly when a stale token
 * expires — so the race is not a corner case, it is the normal path.
 *
 * Under the lock the store is re-checked: the first caller rotates and writes a
 * fresh token, and every caller waiting behind it returns with that token
 * instead of rotating again.
 */
object SessionRefresh {

    private val mutex = Mutex()

    private class Rotation(val sessionId: String, val rejected: String, val renewed: String)
    private var lastRotation: Rotation? = null

    private class FailedRotation(val sessionId: String, val bearer: String, val atMillis: Long)
    private var lastUnavailable: FailedRotation? = null

    suspend fun refresh(context: Context): Boolean = mutex.withLock {
        val original = SessionStore.snapshot(context) ?: return@withLock false
        if (original.unexpired) return@withLock true
        try {
            rotate(context, original) != null
        } catch (_: java.io.IOException) {
            false
        }
    }

    suspend fun retryToken(context: Context, rejected: String): String? = mutex.withLock {
        val current = SessionStore.snapshot(context) ?: return@withLock null
        if (current.token == rejected) return@withLock rotate(context, current)
        // A request may reach the authenticator after another request rotated
        // the same session. The session ID prevents replay across sign-ins.
        val rotation = lastRotation ?: return@withLock null
        current.token.takeIf {
            rotation.sessionId == current.id && rotation.rejected == rejected && rotation.renewed == current.token
        }
    }

    private suspend fun rotate(context: Context, original: SessionSnapshot): String? {
        val now = android.os.SystemClock.elapsedRealtime()
        val unavailable = lastUnavailable
        if (unavailable != null && unavailable.sessionId == original.id && unavailable.bearer == original.token &&
            now - unavailable.atMillis < 2_000
        ) throw java.io.IOException("The session could not be refreshed. Try again.")
        val refresh = SessionStore.refreshToken(context) ?: return null
        val pair = try {
            Api.refreshSession(refresh)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            if (e is ApiException && (e.status == 401 || e.status == 403)) return null
            lastUnavailable = FailedRotation(original.id, original.token, android.os.SystemClock.elapsedRealtime())
            throw java.io.IOException("The session could not be refreshed. Try again.", e)
        }
        val renewed = pair.accessToken ?: pair.token
            ?: throw java.io.IOException("The session refresh returned no access token.")
        if (!SessionStore.updateTokens(context, renewed, pair.refreshToken, pair.expiresAtMillis, original.token)) {
            return null
        }
        lastUnavailable = null
        lastRotation = Rotation(original.id, original.token, renewed)
        return renewed
    }
}
