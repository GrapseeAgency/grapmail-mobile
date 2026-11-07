package com.grapsee.grapmail.data

import android.content.Context

/**
 * Rotating the gateway's JWT pair.
 *
 * The access token lives one hour and the refresh token thirty days, so a
 * session that cannot refresh silently dies hourly while the person is reading
 * mail. The web client has done this since it moved to the gateway; the app has
 * to do the same or the migration would trade one broken screen for another.
 *
 * Rotation is single-flight on the OkHttp side (the authenticator runs per
 * call chain), and the gateway invalidates the presented refresh token, so
 * every successful refresh must be written back — reading the old one again
 * would strand the session.
 */
object SessionRefresh {

    /** True when the pair was rotated and the stored session is usable again. */
    suspend fun refresh(context: Context): Boolean {
        val refresh = SessionStore.refreshToken(context) ?: return false
        val pair = runCatching { Api.refreshSession(refresh) }.getOrNull() ?: return false
        val token = pair.accessToken ?: pair.token ?: return false
        SessionStore.updateTokens(
            context,
            token,
            pair.refreshToken,
            pair.expiresAtMillis,
        )
        return true
    }
}
