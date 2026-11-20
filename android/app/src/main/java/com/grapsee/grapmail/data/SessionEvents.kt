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
}
