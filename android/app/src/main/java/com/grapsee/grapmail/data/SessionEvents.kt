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

    private val _expired = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    val expired = _expired.asSharedFlow()

    /** Non-suspending so the interceptor can fire it from any thread. */
    fun signalExpired() {
        _expired.tryEmit(Unit)
    }
}
