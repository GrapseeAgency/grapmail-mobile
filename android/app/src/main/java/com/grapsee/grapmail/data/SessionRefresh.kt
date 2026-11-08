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

    /** True when a usable session is stored after this call. */
    suspend fun refresh(context: Context, rejectedToken: String? = null): Boolean = mutex.withLock {
        // Somebody else may have rotated while this caller waited. Asking the
        // store — not the caller's stale request — is the point of the lock.
        val original = SessionStore.snapshot(context) ?: return@withLock false
        if (rejectedToken != null && rejectedToken != original.token) return@withLock false
        if (rejectedToken == null && SessionStore.hasValidSession(context)) return@withLock true

        val refresh = SessionStore.refreshToken(context) ?: return@withLock false
        val pair = try { Api.refreshSession(refresh) }
            catch (e: kotlinx.coroutines.CancellationException) { throw e }
            catch (_: Exception) { return@withLock false }
        val token = pair.accessToken ?: pair.token ?: return@withLock false
        SessionStore.updateTokens(context, token, pair.refreshToken, pair.expiresAtMillis, original.token)
    }
}
