package com.grapsee.grapmail.data

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * One app-wide signal: the server rejected our bearer token.
 *
 * A dead session is only ever discovered by the API, not by us — the stored
 * token still *looks* valid, so [SessionStore.hasValidSession] keeps saying yes
 * while every request comes back 401. Without this event the person is stuck on
 * a signed-in screen that cannot load anything, with no route back in short of
 * reinstalling. [com.grapsee.grapmail.data.Api]'s interceptor raises it on an
 * authenticated 401 and the navigation layer clears the session and lands on
 * sign-in.
 *
 * It lives here rather than in OkHttp's callback because the store is
 * asynchronous and needs a Context the network layer never has.
 */
object SessionEvents {

    // replay = 1, not 0: the interceptor can take the 401 BEFORE the navigation
    // layer subscribes — an early failing request on a restored-but-dead
    // session, or any 401 that lands while the collector is between
    // subscriptions. With replay 0 that signal was emitted into nobody: the
    // exact stuck signed-in-with-no-route state this event exists to escape.
    // One subscriber, one pending signal: the collector resets the replay
    // cache the moment it has acted, so a new subscription never re-fires an
    // answered expiry.
    private val _expired = MutableSharedFlow<String>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val expired = _expired.asSharedFlow()

    /** Non-suspending so the interceptor can fire it from any thread. */
    fun signalExpired(rejectedToken: String) {
        _expired.tryEmit(rejectedToken)
    }

    /** Called by the collector after it has taken the session down, so the
     *  replayed value cannot fire a second sign-out on the next subscribe. */
    fun resetExpired() {
        _expired.resetReplayCache()
    }

    // ── Round 8 — B: why a session ended (welcome-back banner) ─────────────
    //
    // The 401 above carries only the bearer, and the server sends no
    // "revoked" code on it. The layer that learns WHY — the refresh that was
    // refused (SessionRefresh) — notes it here against the dead bearer, and
    // SessionStore.clearIfToken takes it when the collector drops that
    // session, writing it onto the remembered-account record. In memory
    // only, a handful of entries, keyed by a bearer that is already dead.

    private val endings = object : java.util.LinkedHashMap<String, RememberedAccounts.Ending>(8, 0.75f, false) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RememberedAccounts.Ending>?): Boolean =
            size > 8
    }

    /** Record why the session holding [token] is ending. Non-suspending, any thread. */
    fun noteEnding(token: String, ending: RememberedAccounts.Ending) {
        synchronized(endings) { endings[token] = ending }
    }

    /** The reason noted for [token], removed as it is read; null when nothing was noted. */
    fun takeEnding(token: String): RememberedAccounts.Ending? =
        synchronized(endings) { endings.remove(token) }

    /**
     * For a caller that knows the session was ended elsewhere (another
     * device, "This wasn't me", "Sign out everywhere"): the same signal as
     * [signalExpired], with the reason the welcome-back banner shows.
     */
    fun signalRevoked(rejectedToken: String) {
        noteEnding(rejectedToken, RememberedAccounts.Ending.Revoked)
        signalExpired(rejectedToken)
    }
}
