package com.grapsee.grapmail.logic

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

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
        /** Set instead of [challengeId] when the challenge is the email-OTP gate
         *  the gateway answers a web-created account's login with: the value is
         *  the ADDRESS the code was dispatched to, not the code itself —
         *  verify-otp and resend-otp both take the address. */
        val emailOtpAddress: String? = null,
        val expiresAt: Long = 0L,
        val maskedPhone: String? = null,
        /** The masked address a two-step sign-in code was emailed to. */
        val factorDestination: String? = null,
        /** "email" when forgot-password emailed a reset link instead of sending a code. */
        val resetChannel: String? = null,
        /** The 6-digit code, proven when the password screen submits. */
        val code: String = "",
        /**
         * The trusted-device grant, minted when THIS device signed the
         * forgot-password challenge and the server accepted the signature.
         * Spent by reset-with-grant. Held here — memory only, dead with the
         * flow — for the same reason the password and the code are: a
         * recovery credential in a saved route argument is a credential on
         * disk.
         */
        val recoveryGrant: String? = null,
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
        /**
         * Round 8 — B: which remembered account the welcome-back route is
         * for. An address, not a secret, but held here rather than in the
         * route for the same reason the rest is: the back stack is written
         * to disk, and the welcome-back screen exists so the full address
         * is not on show.
         */
        val welcomeBackAddress: String? = null,
        /**
         * Round 8 — B: a sentence the standard sign-in shows once on arrival
         * (welcome-back hands over a blocked/deleted account's server
         * message here). Its presence also keeps the sign-in screen from
         * redirecting back to welcome-back.
         */
        val signInNotice: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    fun clearSignIn() {
        update {
            copy(
                password = "", code = "", challengeId = null,
                emailOtpAddress = null, expiresAt = 0L,
                factorDestination = null,
                // A grant survives only until it is spent or the flow changes
                // course; a sign-in that restarts the flow must not inherit one.
                recoveryGrant = null,
            )
        }
    }

    fun update(block: State.() -> State) {
        // Atomic read-modify-write: two coroutines updating the flow at once
        // (a resend landing while a verify settles) can no longer drop a write.
        _state.update { it.block() }
    }

    /** Round 8 — B: open welcome-back for [address] (null: the route picks the newest remembered). */
    fun openWelcomeBack(address: String?) {
        update { copy(welcomeBackAddress = address, prefillIdentifier = null, signInNotice = null) }
    }
}
