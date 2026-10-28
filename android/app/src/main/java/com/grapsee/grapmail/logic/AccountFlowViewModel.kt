package com.grapsee.grapmail.logic

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Everything the recovery and security screens carry between each other.
 *
 * It lives in one object rather than in navigation arguments because two of
 * these values are secrets: the password a 2FA challenge was opened with, and
 * the code that proves the challenge. Route arguments are written into the
 * back stack and survive process death in plain text; a screen-local state
 * holder that dies with the flow is the right place for both.
 */
class AccountFlowViewModel : ViewModel() {

    data class State(
        /** What the person typed on sign-in or forgot-password. */
        val identifier: String = "",
        /** Held only while a 2FA challenge is open — it re-sends the code. */
        val password: String = "",
        val challengeId: String? = null,
        val expiresAt: Long = 0L,
        val maskedPhone: String? = null,
        /** The 6-digit code, proven when the password screen submits. */
        val code: String = "",
        /** Address the backend reported after a completed reset. */
        val resetAddress: String? = null,
        val foundAddress: String? = null,
        /** The unmasked address, only ever used for Copy and the sign-in prefill. */
        val foundAddressFull: String? = null,
        val phone: String = "",
        val phoneChallengeId: String? = null,
        val phoneExpiresAt: Long = 0L,
        val verifiedPhone: String? = null,
        /** Prefills the sign-in field when a flow hands the person back. */
        val prefillIdentifier: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun update(block: State.() -> State) {
        _state.value = _state.value.block()
    }
}
