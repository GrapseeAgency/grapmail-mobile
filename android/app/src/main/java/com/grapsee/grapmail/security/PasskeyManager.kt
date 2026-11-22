package com.grapsee.grapmail.security

import androidx.credentials.CreatePublicKeyCredentialRequest
import androidx.credentials.CredentialManager
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetPublicKeyCredentialOption
import androidx.credentials.PublicKeyCredential
import androidx.credentials.exceptions.CreateCredentialCancellationException
import androidx.credentials.exceptions.GetCredentialCancellationException
import androidx.credentials.exceptions.NoCredentialException
import androidx.fragment.app.FragmentActivity
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.PasskeyAuthVerifyRequest
import com.grapsee.grapmail.data.PasskeyAuthVerifyResponse
import com.grapsee.grapmail.data.PasskeyAuthenticationResponseJson
import com.grapsee.grapmail.data.PasskeyDto
import com.grapsee.grapmail.data.PasskeyRegisterVerifyRequest
import com.grapsee.grapmail.data.PasskeyRegistrationResponseJson
import com.grapsee.grapmail.data.SessionStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * The passkey ceremonies, driven through Android's Credential Manager.
 *
 * ── ACTIVITY REQUIREMENT ───────────────────────────────────────────────────
 * Both ceremonies need a FragmentActivity — Credential Manager (like
 * BiometricPrompt) builds its system sheet against one. MainActivity extends
 * FragmentActivity, so every real entry into the app qualifies; the screens
 * guard with `activity !is FragmentActivity` anyway and show "Passkeys need
 * an app update" rather than crash, so a future host that downgrades the
 * base class fails visibly, not fatally.
 *
 * ── WHAT THE SERVER MUST HOLD ──────────────────────────────────────────────
 * WebAuthn on Android validates the rp.id against THIS app: the rp id is the
 * deployment origin hostname (the host of [Api.BASE]), and it must publish a
 * Digital Asset Links statement at `/.well-known/assetlinks.json` naming
 * `com.grapsee.grapmail` and the distribution certificate's SHA-256. Without
 * it Credential Manager refuses the ceremony and this layer reports Failed —
 * the origin must match exactly, exactly like the web client's own origin.
 *
 * ── THE WIRE ───────────────────────────────────────────────────────────────
 * The server's `options` object IS the WebAuthn JSON, and the platform's
 * response JSON is the WebAuthn JSON serialization of the ceremony: every
 * byte field (clientDataJSON, attestationObject, authenticatorData,
 * signature, userHandle) ALREADY arrives as an unpadded base64url string, so
 * those strings travel to the server verbatim. Re-encoding them would be
 * double-encoding, and the verify would fail without a visible reason — the
 * only honest conversion here is shape: the route reads the response fields
 * flat (id, rawId, type, clientDataJSON, …), not nested under `response`, so
 * the parse below lifts them out. `userHandle` is dropped when blank, which
 * the sparse encoder omits from the wire entirely.
 */
object PasskeyManager {

    /** How [createPasskey] ended. Cancelled is not an error and is silent by design. */
    sealed class RegistrationResult {
        /** The credential was created AND the server recorded it. */
        data class Completed(val passkey: PasskeyDto) : RegistrationResult()

        /** The sheet reported no credential was created for this account. */
        object NoCredential : RegistrationResult()

        /** The person closed the sheet. */
        object Cancelled : RegistrationResult()

        /** The ceremony or the verify round trip failed for a stated reason. */
        data class Failed(val message: String?) : RegistrationResult()
    }

    /** How [signInWithPasskey] ended. On [SignInResult.Completed] the response carries the session. */
    sealed class SignInResult {
        data class Completed(val response: PasskeyAuthVerifyResponse) : SignInResult()

        /** This device holds no passkey the server would accept for any account. */
        object NoCredential : SignInResult()

        /** The person closed the sheet. */
        object Cancelled : SignInResult()

        data class Failed(val message: String?) : SignInResult()
    }

    /**
     * The full registration ceremony: fetch the creation options under the
     * signed-in bearer, run Credential Manager's sheet, then post the result
     * to the verify route with the label [name] the person chose. Everything
     * runs on the caller's context except the ceremony itself, which must run
     * on Main because it draws a system dialog.
     */
    suspend fun createPasskey(activity: FragmentActivity, name: String): RegistrationResult {
        return try {
            val token = SessionStore.token(activity.applicationContext)
                ?: return RegistrationResult.Failed("Sign in again.")
            val options = Api.passkeyRegisterOptions(token)
            val challengeId = options.challengeId
            if (challengeId.isNullOrBlank()) {
                return RegistrationResult.Failed("The server did not send passkey parameters.")
            }
            val optionsJson = options.options?.toString()
                ?: return RegistrationResult.Failed("The server did not send passkey parameters.")
            // The server's `options` object IS the WebAuthn creation options;
            // re-serialize through JSONObject so Credential Manager receives a
            // validated copy of exactly what the server sent.
            val request = CreatePublicKeyCredentialRequest(JSONObject(optionsJson).toString())
            val manager = CredentialManager.create(activity)
            val credential = withContext(Dispatchers.Main) {
                val response = manager.createCredential(activity, request)
                response.credential as? PublicKeyCredential
                    ?: throw IllegalStateException("The passkey ceremony returned an unexpected credential.")
            }
            val wire = parseRegistrationJson(credential.registrationResponseJson)
                ?: return RegistrationResult.Failed("The passkey ceremony returned an unreadable result.")
            val verified = Api.passkeyRegisterVerify(
                token,
                PasskeyRegisterVerifyRequest(
                    challengeId = challengeId,
                    name = name.takeIf(String::isNotBlank),
                    response = wire,
                ),
            )
            if (!verified.success) return RegistrationResult.Failed("The passkey was not saved.")
            RegistrationResult.Completed(verified.passkey ?: PasskeyDto(id = wire.id, name = name))
        } catch (e: CancellationException) {
            throw e
        } catch (_: NoCredentialException) {
            RegistrationResult.NoCredential
        } catch (_: GetCredentialCancellationException) {
            RegistrationResult.Cancelled
        } catch (_: CreateCredentialCancellationException) {
            RegistrationResult.Cancelled
        } catch (e: Exception) {
            RegistrationResult.Failed(e.message)
        }
    }

    /**
     * The full authentication ceremony: fetch the (public, usernameless)
     * request options, run Credential Manager's sheet, then post the
     * assertion to the verify route. The completed result carries the same
     * session envelope a password sign-in returns, so the caller stores it
     * through the very same [SessionStore.save] path.
     */
    suspend fun signInWithPasskey(activity: FragmentActivity): SignInResult {
        return try {
            val options = Api.passkeyAuthOptions()
            val challengeId = options.challengeId
            if (challengeId.isNullOrBlank()) {
                return SignInResult.Failed("The server did not send passkey parameters.")
            }
            val optionsJson = options.options?.toString()
                ?: return SignInResult.Failed("The server did not send passkey parameters.")
            val option = GetPublicKeyCredentialOption(JSONObject(optionsJson).toString())
            val request = GetCredentialRequest(listOf(option))
            val manager = CredentialManager.create(activity)
            val credential = withContext(Dispatchers.Main) {
                val response = manager.getCredential(activity, request)
                response.credential as? PublicKeyCredential
                    ?: throw IllegalStateException("The passkey ceremony returned an unexpected credential.")
            }
            val wire = parseAuthenticationJson(credential.authenticationResponseJson)
                ?: return SignInResult.Failed("The passkey ceremony returned an unreadable result.")
            val verified = Api.passkeyAuthVerify(PasskeyAuthVerifyRequest(challengeId, wire))
            if (!verified.success) return SignInResult.Failed("The passkey was not accepted.")
            SignInResult.Completed(verified)
        } catch (e: CancellationException) {
            throw e
        } catch (_: NoCredentialException) {
            SignInResult.NoCredential
        } catch (_: GetCredentialCancellationException) {
            SignInResult.Cancelled
        } catch (e: Exception) {
            SignInResult.Failed(e.message)
        }
    }

    /** Flatten the platform's RegistrationResponseJSON into the route's shape. */
    private fun parseRegistrationJson(raw: String): PasskeyRegistrationResponseJson? = try {
        val top = JSONObject(raw)
        val inner = top.optJSONObject("response") ?: return null
        val rawId = top.optString("rawId")
        val clientData = inner.optString("clientDataJSON")
        val attestation = inner.optString("attestationObject")
        if (clientData.isBlank() || attestation.isBlank()) return null
        PasskeyRegistrationResponseJson(
            id = top.optString("id").ifBlank { rawId },
            rawId = rawId,
            type = top.optString("type", "public-key").ifBlank { "public-key" },
            clientDataJSON = clientData,
            attestationObject = attestation,
            transports = inner.optJSONArray("transports")?.let { array ->
                buildList {
                    for (i in 0 until array.length()) add(array.optString(i))
                }.takeIf { it.isNotEmpty() }
            },
        )
    } catch (_: Exception) {
        null
    }

    /** Flatten the platform's AuthenticationResponseJSON into the route's shape. */
    private fun parseAuthenticationJson(raw: String): PasskeyAuthenticationResponseJson? = try {
        val top = JSONObject(raw)
        val inner = top.optJSONObject("response") ?: return null
        val rawId = top.optString("rawId")
        val clientData = inner.optString("clientDataJSON")
        val authenticatorData = inner.optString("authenticatorData")
        val signature = inner.optString("signature")
        if (clientData.isBlank() || authenticatorData.isBlank() || signature.isBlank()) return null
        PasskeyAuthenticationResponseJson(
            id = top.optString("id").ifBlank { rawId },
            rawId = rawId,
            type = top.optString("type", "public-key").ifBlank { "public-key" },
            clientDataJSON = clientData,
            authenticatorData = authenticatorData,
            signature = signature,
            userHandle = inner.optString("userHandle").takeIf { it.isNotBlank() },
        )
    } catch (_: Exception) {
        null
    }
}
