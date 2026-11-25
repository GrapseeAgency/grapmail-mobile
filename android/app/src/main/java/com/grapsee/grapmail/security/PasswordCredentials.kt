package com.grapsee.grapmail.security

import android.app.Activity
import android.content.Context
import androidx.credentials.CreatePasswordRequest
import androidx.credentials.Credential
import androidx.credentials.CredentialManager
import androidx.credentials.CredentialOption
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPasswordOption
import androidx.credentials.PasswordCredential
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.grapsee.grapmail.findActivity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.security.MessageDigest

/**
 * The GrapMail password in the person's password manager, through Android's
 * Credential Manager: Google Password Manager, and any third-party provider
 * the person enabled (1Password, Bitwarden, …) on Android 14+; Google's
 * provider through credentials-play-services-auth on Android 13 and older.
 *
 * Two jobs, both best-effort and both silent when they cannot happen:
 *  - [offerToSave]: after the server has ACCEPTED a password (a completed
 *    sign-in, a created account, a reset or changed password) the system's
 *    "Save password?" sheet is offered. It never blocks or delays the flow
 *    that called it — it is launched on the activity's lifecycle scope and
 *    the caller carries on — and a declined sheet or any failure is
 *    swallowed.
 *  - [requestSavedLogin]: when the sign-in screen opens empty, the saved
 *    GrapMail logins (and passkeys, when the server hands out options) are
 *    offered in one sheet; nothing saved, or a cancel, shows nothing.
 *
 * App ↔ website sharing (a password saved on grapsee.com offered here and the
 * reverse) rests on the `delegate_permission/common.get_login_creds`
 * statement in grapsee.com's assetlinks.json; nothing in the app changes for it.
 */
object PasswordCredentials {

    /** What the person picked from the saved-login sheet. */
    sealed class SavedLogin {
        /** A saved password. Not a data class: a credential must never reach a toString. */
        class Password(val id: String, val password: String) : SavedLogin() {
            override fun toString(): String = "SavedLogin.Password(id=$id)"
        }

        /** A passkey, already verified by the server (or refused, with the reason). */
        class Passkey(val result: PasskeyManager.SignInResult) : SavedLogin()

        /** Nothing saved, the sheet was closed, or no provider could answer. */
        object None : SavedLogin()
    }

    private sealed class Attempt {
        class Got(val credential: Credential) : Attempt()

        /** Cancelled or nothing saved: the answer is final, ask nothing more. */
        object Quiet : Attempt()

        /** The request itself failed (provider missing, passkey origin refused, …). */
        object Failed : Attempt()
    }

    /**
     * Ask Credential Manager for a saved GrapMail login. [activity] hosts the
     * sheet. [stillWanted] is checked again after the (network) passkey
     * options arrive, so a person who has started typing in the meantime is
     * not interrupted by a sheet.
     *
     * The passkey option is folded in when the server's usernameless options
     * can be fetched; if the combined request is refused for a reason other
     * than "nothing saved" or a cancel (for example the passkey origin check
     * while the site's asset links are not live), saved passwords are asked
     * for alone. `preferImmediatelyAvailableCredentials` keeps the platform
     * from drawing any UI (such as "use a passkey from another device") when
     * nothing is saved on this device.
     */
    suspend fun requestSavedLogin(
        activity: Activity,
        stillWanted: () -> Boolean,
        // Round 8 — B (welcome-back "Continue as", round8-spec §2.3): with an
        // address, only that account's saved password is asked for and a
        // password saved under any other id is discarded; without a passkey
        // hint the passkey option is left out of the sheet.
        forAddress: String? = null,
        includePasskey: Boolean = true,
    ): SavedLogin {
        val manager = CredentialManager.create(activity)
        val passkey = if (includePasskey) PasskeyManager.prepareSignIn() else null
        if (!stillWanted()) return SavedLogin.None
        val passwordOption = forAddress?.trim()?.takeIf(String::isNotEmpty)
            ?.let { GetPasswordOption(allowedUserIds = setOf(it)) }
            ?: GetPasswordOption()
        if (passkey != null) {
            when (val outcome = ask(manager, activity, listOf(passwordOption, passkey.option))) {
                is Attempt.Got -> return resolve(outcome.credential, passkey).onlyFor(forAddress)
                Attempt.Quiet -> return SavedLogin.None
                Attempt.Failed -> Unit
            }
            if (!stillWanted()) return SavedLogin.None
        }
        val outcome = ask(manager, activity, listOf(passwordOption))
        return if (outcome is Attempt.Got) resolve(outcome.credential, null).onlyFor(forAddress) else SavedLogin.None
    }

    /**
     * §2.3 rung 2: a saved password whose id is not [address] (canonical
     * comparison) is not this account's, whatever the provider returned. A
     * passkey is kept whichever account it signs in — the caller accepts it
     * and says so.
     */
    private fun SavedLogin.onlyFor(address: String?): SavedLogin {
        if (address == null || this !is SavedLogin.Password) return this
        val wanted = com.grapsee.grapmail.data.RememberedAccounts.canonical(address)
        return if (com.grapsee.grapmail.data.RememberedAccounts.canonical(id) == wanted) this else SavedLogin.None
    }

    private suspend fun ask(
        manager: CredentialManager,
        activity: Activity,
        options: List<CredentialOption>,
    ): Attempt {
        return try {
            val request = GetCredentialRequest.Builder()
                .setCredentialOptions(options)
                .setPreferImmediatelyAvailableCredentials(true)
                .build()
            Attempt.Got(manager.getCredential(activity, request).credential)
        } catch (e: CancellationException) {
            throw e
        } catch (_: GetCredentialCancellationException) {
            Attempt.Quiet
        } catch (_: NoCredentialException) {
            Attempt.Quiet
        } catch (_: Exception) {
            Attempt.Failed
        }
    }

    private suspend fun resolve(credential: Credential, passkey: PasskeyManager.PreparedSignIn?): SavedLogin {
        if (credential is PasswordCredential) {
            return if (credential.id.isNotBlank() && credential.password.isNotEmpty()) {
                SavedLogin.Password(credential.id.trim(), credential.password)
            } else {
                SavedLogin.None
            }
        }
        if (credential is PublicKeyCredential && passkey != null) {
            return SavedLogin.Passkey(PasskeyManager.verifyPickedPasskey(passkey, credential))
        }
        return SavedLogin.None
    }

    // ── Saving ─────────────────────────────────────────────────────────────

    /** Fingerprint of the last pair offered this process, so one password is not offered twice in a row. */
    @Volatile
    private var lastOffered: String? = null

    /** A password the server accepted while the sign-in is still waiting on its second step. */
    @Volatile
    private var held: String? = null

    /**
     * Offer to save [password] for [address]. Call only once the server has
     * accepted the pair. Returns at once; the sheet is shown on the hosting
     * activity's lifecycle scope, so leaving the calling screen does not
     * cancel it. Needs an Activity in [context]'s chain — without one (or
     * without a lifecycle owner) it does nothing.
     */
    fun offerToSave(context: Context, address: String, password: String) {
        val id = address.trim()
        if (id.isEmpty() || password.isEmpty()) return
        val activity = context.findActivity() ?: return
        val owner = activity as? LifecycleOwner ?: return
        val key = fingerprint(id, password)
        if (key == lastOffered) return
        lastOffered = key
        owner.lifecycleScope.launch {
            try {
                CredentialManager.create(activity)
                    .createCredential(activity, CreatePasswordRequest(id, password))
            } catch (e: CancellationException) {
                throw e
            } catch (_: CreateCredentialCancellationException) {
                // The person chose not to save it; nothing to report.
            } catch (_: Exception) {
                // No provider, provider refused, interrupted: saving is a
                // convenience, never a failure the person has to read.
            }
        }
    }

    /**
     * The sign-in screen's password was accepted but a second step (a code,
     * a backup code) still stands between it and a session: keep it in
     * memory only until [offerHeldSave] or [clearHeld].
     */
    fun holdUntilSignedIn(password: String) {
        held = password.takeIf { it.isNotEmpty() }
    }

    /** Forget a held password (the sign-in screen opened again, the flow restarted). */
    fun clearHeld() {
        held = null
    }

    /** A sign-in completed as [address]: offer the held password, if any, and forget it. */
    fun offerHeldSave(context: Context, address: String) {
        val password = held ?: return
        held = null
        offerToSave(context, address, password)
    }

    private fun fingerprint(id: String, password: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest("${id.lowercase()}\u0000$password".toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
