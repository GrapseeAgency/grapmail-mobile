package com.grapsee.grapmail.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * The real GrapMail account API — the same contract the website uses,
 * served at https://grapsee.com/api (verified live before this client was
 * written). No mocks: every call here hits the network.
 */

@Serializable data class AccountDto(
    val id: String = "",
    val name: String = "",
    val firstName: String? = null,
    val lastName: String? = null,
    val address: String = "",
    val handle: String = "",
    val phone: String? = null,
    val accountType: String = "USER",
)

@Serializable data class CreateRequest(
    val name: String,
    val firstName: String? = null,
    val lastName: String? = null,
    val birthday: String? = null,
    val gender: String? = null,
    val grapmailHandle: String? = null,
    val password: String,
    val acceptedTerms: Boolean? = null,
)

@Serializable data class CreateResponse(
    val success: Boolean = false,
    val account: AccountDto? = null,
    val mailboxDomain: String? = null,
    val message: String? = null,
    val phoneVerified: Boolean = false,
    val error: String? = null,
    val code: String? = null,
)

@Serializable data class CheckResponse(
    val success: Boolean = false,
    val available: Boolean = false,
    val handle: String = "",
    val address: String? = null,
    val suggestion: String? = null,
    val reason: String? = null,
)

@Serializable data class SignInRequest(val identifier: String, val password: String)

@Serializable data class SignInResponse(
    val success: Boolean = false,
    val sessionToken: String? = null,
    val accessToken: String? = null,
    val token: String? = null,
    val expiresAt: Long? = null,
    /** The live /auth/account/signin envelope answers with a RELATIVE lifetime
     *  (seconds), not an absolute `expiresAt`. Only the absolute one was ever
     *  read, so every stored session had expiry 0 — which SessionStore treats as
     *  "never expires". A token revoked on the server kept the app on a home
     *  screen whose every call 401s. */
    val expiresIn: Long? = null,
    val account: AccountDto? = null,
    /** The same object under the name the live envelope actually uses. */
    val user: AccountDto? = null,
    val requireOtp: Boolean = false,
    val challengeId: String? = null,
    val backupCodesRemaining: Int? = null,
    val message: String? = null,
    val error: String? = null,
    val code: String? = null,
) {
    /** Absolute epoch-milliseconds, from whichever shape the server sent. */
    val expiresAtMillis: Long
        get() {
            val at = expiresAt
            if (at != null && at > 0L) return if (at < 100_000_000_000L) at * 1000L else at
            val seconds = expiresIn
            if (seconds != null && seconds > 0L) return System.currentTimeMillis() + seconds * 1000L
            return 0L
        }

    /** The account object under either key. */
    val accountOrNull: AccountDto? get() = account ?: user

    /** A challenge in the response means a code is required — presence is the
     *  gate, not the `requireOtp` flag, which this backend omits on ordinary
     *  sign-ins. Treating an absent flag as "no code needed" would sign a 2FA
     *  account in on the password alone whenever a challenge is issued. */
    val secondFactorRequired: Boolean
        get() = challengeId != null || requireOtp
}

@Serializable data class TwoFactorRequest(
    val challengeId: String? = null,
    val code: String? = null,
    val identifier: String? = null,
    val password: String? = null,
    val backupCode: String? = null,
)

@Serializable data class TwoFactorResponse(
    val success: Boolean = false,
    val sessionToken: String? = null,
    val accessToken: String? = null,
    val token: String? = null,
    val expiresAt: Long? = null,
    val expiresIn: Long? = null,
    val user: AccountDto? = null,
    val error: String? = null,
    val code: String? = null,
) {
    /** Redeeming a code opens the session, and a session written with no
     *  expiry looks valid forever — same both-shapes rule as SignInResponse. */
    val expiresAtMillis: Long
        get() {
            val at = expiresAt
            if (at != null && at > 0L) return if (at < 100_000_000_000L) at * 1000L else at
            val seconds = expiresIn
            if (seconds != null && seconds > 0L) return System.currentTimeMillis() + seconds * 1000L
            return 0L
        }
}

class ApiException(val status: Int, val code: String?, message: String) : Exception(message)

// ── Recovery & security ─────────────────────────────────────────────────────

@Serializable data class ForgotPasswordRequest(val identifier: String)

@Serializable data class ForgotPasswordResponse(
    val success: Boolean = false,
    val sent: Boolean = false,
    val challengeId: String? = null,
    val expiresAt: Long? = null,
    val maskedPhone: String? = null,
    val provider: String? = null,
    val message: String? = null,
    val error: String? = null,
    val code: String? = null,
)

@Serializable data class ResetPasswordRequest(
    val challengeId: String,
    val code: String,
    val password: String,
)

@Serializable data class ResetPasswordResponse(
    val success: Boolean = false,
    val address: String? = null,
    val message: String? = null,
    val error: String? = null,
    val code: String? = null,
    val reason: String? = null,
    val attemptsLeft: Int? = null,
)

@Serializable data class FindAddressRequest(val phone: String)

@Serializable data class FindAddressResponse(
    val success: Boolean = false,
    /** Shown on screen: first character, stars, domain. */
    val maskedAddress: String? = null,
    /**
     * The whole address, returned only so the design's Copy button can put
     * something usable on the clipboard. The screen still shows the mask.
     */
    val address: String? = null,
    val message: String? = null,
    val error: String? = null,
    val code: String? = null,
)

@Serializable data class PhoneStartRequest(val phone: String)

@Serializable data class PhoneStartResponse(
    val success: Boolean = false,
    val challengeId: String? = null,
    val expiresAt: Long? = null,
    val channel: String? = null,
    val delivered: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val code: String? = null,
)

@Serializable data class PhoneVerifyRequest(val challengeId: String, val code: String)

@Serializable data class PhoneVerifyResponse(
    val success: Boolean = false,
    val phone: String? = null,
    val phoneToken: String? = null,
    /**
     * Proves this code was redeemed by a signed-in owner of that number. The 2FA
     * enable call spends it, so the phone step cannot be skipped by a client that
     * simply never shows it.
     */
    val phoneProof: String? = null,
    val expiresAt: Long? = null,
    val message: String? = null,
    val error: String? = null,
    val code: String? = null,
    val reason: String? = null,
    val attemptsLeft: Int? = null,
)

@Serializable data class LinkPhoneRequest(val phoneToken: String)

@Serializable data class LinkPhoneResponse(
    val success: Boolean = false,
    val message: String? = null,
    val error: String? = null,
    val code: String? = null,
)

// ── Security sector (the 24-screen batch) ───────────────────────────────────

@Serializable data class ProfileDto(
    val id: String = "",
    val name: String = "",
    val address: String = "",
    val handle: String = "",
    val phone: String? = null,
    /** The legacy form of the same answer, kept because it is what some
     *  endpoints actually send. Nullable on purpose: an absent string is no
     *  answer, and a defaulted "off" would claim two-step is off when nothing
     *  was said. */
    val twoFactor: String? = null,
    /** The server's own answer to "is the second factor on", so no client re-derives it from the string.
     *  Null means the server did not answer, which the hub must show as "couldn't load" rather than
     *  as "Off": a wrong Off invites the person to re-enable a factor that is already on and hides a
     *  real lock-out. `backupCodesRemaining` is null and not 0 for the same reason — 0 would mean
     *  "generate codes or you will be locked out". */
    val twoFactorEnabled: Boolean? = null,
    val twoFactorEnabledAt: String? = null,
    val backupCodesRemaining: Int? = null,
    val passwordChangedAt: String? = null,
    val createdAt: String? = null,
) {
    /** The one verdict the hub acts on: the server's own flag when it sends
     *  one, otherwise the legacy string read as a flag, otherwise null — and
     *  null is the only case the hub is allowed to call unknown. */
    val twoFactorState: Boolean?
        get() = twoFactorEnabled ?: when (twoFactor?.lowercase()) {
            "on", "true", "1", "enabled" -> true
            "off", "false", "0", "disabled" -> false
            else -> null
        }
}

@Serializable data class ProfileResponse(
    val success: Boolean = false,
    val profile: ProfileDto? = null,
    /** Short-lived proof minted by verify-password for one dangerous action. */
    val proof: String? = null,
    /** Present only on the response that generated them; never readable again. */
    val newBackupCodes: List<String> = emptyList(),
    /** Present only when the change revoked this device's own token too. */
    val accessToken: String? = null,
    /** When that replacement expires. Stored, or the app believes it is signed in forever. */
    val tokenExpiresAt: Long? = null,
    /** The relative form the server may send instead. */
    val tokenExpiresIn: Long? = null,
    val expiresIn: Long? = null,
    val message: String? = null,
    val error: String? = null,
    val code: String? = null,
) {
    /** Absolute epoch-milliseconds for a token this response reissued, from
     *  either shape. A replacement token written without an expiry is a
     *  permanent session, which is exactly the bug the sign-in path had. */
    val tokenExpiresAtMillis: Long
        get() {
            val at = tokenExpiresAt
            if (at != null && at > 0L) return if (at < 100_000_000_000L) at * 1000L else at
            val seconds = tokenExpiresIn ?: expiresIn
            if (seconds != null && seconds > 0L) return System.currentTimeMillis() + seconds * 1000L
            return 0L
        }
}

@Serializable data class SessionDto(
    val id: String = "",
    val device: String = "",
    /** The browser/app half of the label, shown beside the location on the row. */
    val browser: String = "",
    /** The stable handle for "is this the device the alert is about?". Labels collide. */
    val deviceId: String = "",
    val ip: String = "",
    val createdAt: String = "",
    val lastSeenAt: String = "",
    val current: Boolean = false,
)

@Serializable data class SessionsResponse(
    val success: Boolean = false,
    val sessions: List<SessionDto> = emptyList(),
    val count: Int = 0,
    /** True total active sessions, correct even when the list is one page. */
    val activeCount: Int = 0,
    val error: String? = null,
)

@Serializable data class ActivityDto(
    val id: String = "",
    val type: String = "",
    val at: String = "",
    val device: String = "",
    val browser: String = "",
    val deviceId: String = "",
    val ip: String = "",
    val severity: String = "info",
    val detail: String = "",
)

@Serializable data class ActivityResponse(
    val success: Boolean = false,
    val events: List<ActivityDto> = emptyList(),
    val suspicious: Boolean = false,
    /** The exact sign-in the server flagged, so the alert screens read it rather than re-pick one. */
    val suspiciousEvent: ActivityDto? = null,
    val error: String? = null,
)

@Serializable private data class TwoFactorEnableRequest(
    val twoFactor: String,
    val proof: String,
    val phoneProof: String,
)
@Serializable private data class TwoFactorDisableRequest(val twoFactor: String, val currentPassword: String)
@Serializable private data class PasswordChangeRequest(val currentPassword: String, val newPassword: String)
@Serializable private data class BackupCodesRequest(val generateBackupCodes: Boolean)
@Serializable private data class SignOutEverywhereRequest(val signOutEverywhere: Boolean)
@Serializable private data class VerifyPasswordRequest(val password: String)

object Api {
    val BASE: String = com.grapsee.grapmail.BuildConfig.API_BASE

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val JSON_MEDIA = "application/json".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
        // Every call carries the app's own identity, not just the authenticated
        // ones: the label is captured at sign-in, and a request that arrives as
        // "okhttp/4.x" is recorded as "Unknown device" forever.
        .addInterceptor { chain ->
            val response = chain.proceed(
                chain.request().newBuilder()
                    .header("user-agent", userAgent)
                    .build(),
            )
            // A 401 on a call that carried our bearer means the server no longer
            // recognises the session (expired, or revoked from another device).
            // Say so once, loudly, so the app can land the person on sign-in
            // instead of leaving them on a signed-in screen that cannot load.
            // Anonymous 401s — a wrong password at sign-in — must not do this.
            if (response.code == 401 && chain.request().header("Authorization") != null) {
                SessionEvents.signalExpired()
            }
            response
        }
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(20, TimeUnit.SECONDS)
        .build()

    private suspend fun execute(request: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
        client.newCall(request).execute().use { resp ->
            resp.code to (resp.body?.string() ?: "")
        }
    }

    private fun postBody(payload: String) = payload.toRequestBody(JSON_MEDIA)

    /**
     * What the security screen shows as the device name.
     *
     * OkHttp's default is "okhttp/4.x", which matches no platform rule, so every
     * Android sign-in was recorded as "Unknown device" and the device list could
     * not be read. Naming the app, the OS version and the model is what makes the
     * row recognisable to the person looking at it.
     */
    private val userAgent: String by lazy {
        "GrapMail/1.0 (Android ${android.os.Build.VERSION.RELEASE}; ${android.os.Build.MODEL})"
    }

    private fun fail(status: Int, body: String): Nothing {
        val code = runCatching { json.parseToJsonElement(body).jsonObjectOrNull()?.get("code")?.toString()?.trim('"') }.getOrNull()
        val message = runCatching { json.parseToJsonElement(body).jsonObjectOrNull()?.get("message")?.toString()?.trim('"') }.getOrNull()
            ?: runCatching { json.parseToJsonElement(body).jsonObjectOrNull()?.get("error")?.toString()?.trim('"') }.getOrNull()
            ?: "Request failed ($status)"
        throw ApiException(status, code, message)
    }

    private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull() =
        this as? kotlinx.serialization.json.JsonObject

    // ── Endpoints ───────────────────────────────────────────────────────────

    suspend fun checkHandle(handle: String): CheckResponse {
        val req = Request.Builder()
            .url("$BASE/auth/grapmail/check?handle=${URLEncoder.encode(handle, "UTF-8")}")
            .header("x-device-id", DeviceId.value)
            .get().build()
        val (status, body) = execute(req)
        if (status >= 400) fail(status, body)
        return json.decodeFromString<CheckResponse>(body)
    }

    suspend fun createAccount(req: CreateRequest): CreateResponse {
        val request = Request.Builder()
            .url("$BASE/auth/account/create")
            .header("x-device-id", DeviceId.value)
            .post(postBody(json.encodeToString(CreateRequest.serializer(), req)))
            .build()
        val (status, body) = execute(request)
        if (status >= 400) fail(status, body)
        return json.decodeFromString<CreateResponse>(body)
    }

    suspend fun signIn(identifier: String, password: String): SignInResponse {
        val request = Request.Builder()
            .url("$BASE/auth/account/signin")
            .header("x-device-id", DeviceId.value)
            .post(postBody(json.encodeToString(SignInRequest.serializer(), SignInRequest(identifier, password))))
            .build()
        val (status, body) = execute(request)
        if (status >= 400) fail(status, body)
        return json.decodeFromString<SignInResponse>(body)
    }

    suspend fun twoFactorCode(challengeId: String, code: String): TwoFactorResponse =
        twoFactor(TwoFactorRequest(challengeId = challengeId, code = code))

    suspend fun twoFactorBackup(identifier: String, password: String, backupCode: String): TwoFactorResponse =
        twoFactor(TwoFactorRequest(identifier = identifier, password = password, backupCode = backupCode))

    /**
     * Step 1 of a password reset. The reply is deliberately neutral about
     * whether the identifier exists; what the client can rely on is
     * `challengeId` — present when a code really went out, absent when the
     * account has to take the slower manual route.
     */
    suspend fun forgotPassword(identifier: String): ForgotPasswordResponse {
        val request = Request.Builder()
            .url("$BASE/auth/account/reset/start")
            .header("x-device-id", DeviceId.value)
            .post(postBody(json.encodeToString(ForgotPasswordRequest.serializer(), ForgotPasswordRequest(identifier))))
            .build()
        val (status, body) = execute(request)
        if (status >= 400) fail(status, body)
        return json.decodeFromString<ForgotPasswordResponse>(body)
    }

    /** Step 2: the code and the new password are checked together, by design. */
    suspend fun resetPassword(challengeId: String, code: String, password: String): ResetPasswordResponse {
        val request = Request.Builder()
            .url("$BASE/auth/account/reset/confirm")
            .header("x-device-id", DeviceId.value)
            .post(
                postBody(
                    json.encodeToString(
                        ResetPasswordRequest.serializer(),
                        ResetPasswordRequest(challengeId, code, password),
                    )
                )
            )
            .build()
        val (status, body) = execute(request)
        if (status >= 400) fail(status, body)
        return json.decodeFromString<ResetPasswordResponse>(body)
    }

    /** Phone -> partially hidden GrapMail address. Never returns the full one. */
    suspend fun findAddress(phone: String): FindAddressResponse {
        val request = Request.Builder()
            .url("$BASE/auth/account/find")
            .header("x-device-id", DeviceId.value)
            .post(postBody(json.encodeToString(FindAddressRequest.serializer(), FindAddressRequest(phone))))
            .build()
        val (status, body) = execute(request)
        if (status >= 400) fail(status, body)
        return json.decodeFromString<FindAddressResponse>(body)
    }

    suspend fun startPhoneVerification(phone: String): PhoneStartResponse {
        val request = Request.Builder()
            .url("$BASE/auth/phone/start")
            .header("x-device-id", DeviceId.value)
            .post(postBody(json.encodeToString(PhoneStartRequest.serializer(), PhoneStartRequest(phone))))
            .build()
        val (status, body) = execute(request)
        if (status >= 400) fail(status, body)
        return json.decodeFromString<PhoneStartResponse>(body)
    }

    suspend fun verifyPhone(challengeId: String, code: String, token: String? = null): PhoneVerifyResponse {
        // The bearer is optional on purpose: the sign-up wizard verifies a number
        // with no session yet, while the 2FA flow verifies the account's own number
        // from a signed-in device. Only the latter can mint the phone proof, and the
        // server mints it only when this header is present and names a live session.
        val builder = if (token != null) authed("$BASE/auth/phone/verify", token)
            else Request.Builder().url("$BASE/auth/phone/verify").header("x-device-id", DeviceId.value)
        val request = builder
            .post(
                postBody(
                    json.encodeToString(
                        PhoneVerifyRequest.serializer(),
                        PhoneVerifyRequest(challengeId, code),
                    )
                )
            )
            .build()
        val (status, body) = execute(request)
        if (status >= 400) fail(status, body)
        return json.decodeFromString<PhoneVerifyResponse>(body)
    }

    /**
     * Saves the verified number to the signed-in account. The proof of
     * possession is the phone token; the account comes from the bearer token,
     * so a caller can only ever change their own number.
     */
    suspend fun linkPhone(sessionToken: String, phoneToken: String): LinkPhoneResponse {
        val request = Request.Builder()
            .url("$BASE/auth/account/profile")
            .header("x-device-id", DeviceId.value)
            .header("authorization", "Bearer $sessionToken")
            .patch(postBody(json.encodeToString(LinkPhoneRequest.serializer(), LinkPhoneRequest(phoneToken))))
            .build()
        val (status, body) = execute(request)
        if (status >= 400) fail(status, body)
        return json.decodeFromString<LinkPhoneResponse>(body)
    }

    private suspend fun twoFactor(payload: TwoFactorRequest): TwoFactorResponse {
        val request = Request.Builder()
            .url("$BASE/auth/account/twofactor")
            .header("x-device-id", DeviceId.value)
            .post(postBody(json.encodeToString(TwoFactorRequest.serializer(), payload)))
            .build()
        val (status, body) = execute(request)
        if (status >= 400) fail(status, body)
        return json.decodeFromString<TwoFactorResponse>(body)
    }

    // ── Security sector ─────────────────────────────────────────────────────

    private fun authed(url: String, token: String): Request.Builder =
        Request.Builder().url(url)
            .header("x-device-id", DeviceId.value)
            .header("authorization", "Bearer $token")

    private suspend fun <T> read(request: Request, decode: (String) -> T): T {
        val (status, body) = execute(request)
        if (status >= 400) fail(status, body)
        return decode(body)
    }

    suspend fun profile(token: String): ProfileResponse = read(authed("$BASE/auth/account/profile", token).get().build()) {
        json.decodeFromString<ProfileResponse>(it)
    }

    suspend fun enableTwoFactor(token: String, proof: String?, phoneProof: String?): ProfileResponse {
        val req = authed("$BASE/auth/account/profile", token)
            .patch(postBody(json.encodeToString(
                TwoFactorEnableRequest.serializer(),
                TwoFactorEnableRequest("on", proof ?: "", phoneProof ?: ""),
            )))
            .build()
        return read(req) { json.decodeFromString<ProfileResponse>(it) }
    }

    suspend fun disableTwoFactor(token: String, password: String): ProfileResponse {
        val req = authed("$BASE/auth/account/profile", token)
            .patch(postBody(json.encodeToString(TwoFactorDisableRequest.serializer(), TwoFactorDisableRequest("off", password))))
            .build()
        return read(req) { json.decodeFromString<ProfileResponse>(it) }
    }

    suspend fun changePassword(token: String, currentPassword: String, newPassword: String): ProfileResponse {
        val req = authed("$BASE/auth/account/profile", token)
            .patch(postBody(json.encodeToString(PasswordChangeRequest.serializer(), PasswordChangeRequest(currentPassword, newPassword))))
            .build()
        return read(req) { json.decodeFromString<ProfileResponse>(it) }
    }

    suspend fun generateBackupCodes(token: String): ProfileResponse {
        val req = authed("$BASE/auth/account/profile", token)
            .patch(postBody(json.encodeToString(BackupCodesRequest.serializer(), BackupCodesRequest(true))))
            .build()
        return read(req) { json.decodeFromString<ProfileResponse>(it) }
    }

    /** Signs every OTHER device out. The token passed here stays valid. */
    suspend fun signOutEverywhere(token: String): ProfileResponse {
        val req = authed("$BASE/auth/account/profile", token)
            .patch(postBody(json.encodeToString(SignOutEverywhereRequest.serializer(), SignOutEverywhereRequest(true))))
            .build()
        return read(req) { json.decodeFromString<ProfileResponse>(it) }
    }

    suspend fun verifyPassword(token: String, password: String): ProfileResponse {
        val req = authed("$BASE/auth/account/verify-password", token)
            .post(postBody(json.encodeToString(VerifyPasswordRequest.serializer(), VerifyPasswordRequest(password))))
            .build()
        return read(req) { json.decodeFromString<ProfileResponse>(it) }
    }

    suspend fun sessions(token: String): SessionsResponse = read(authed("$BASE/auth/account/sessions", token).get().build()) {
        json.decodeFromString<SessionsResponse>(it)
    }

    suspend fun revokeSession(token: String, id: String): ProfileResponse {
        val url = "$BASE/auth/account/sessions?id=" + URLEncoder.encode(id, "UTF-8")
        return read(authed(url, token).delete().build()) { json.decodeFromString<ProfileResponse>(it) }
    }

    suspend fun activity(token: String): ActivityResponse = read(authed("$BASE/auth/account/activity", token).get().build()) {
        json.decodeFromString<ActivityResponse>(it)
    }

    /** "This sign-in was me": recorded, not deleted, so the alert can clear honestly. */
    suspend fun acknowledgeSignIn(token: String): ProfileResponse {
        val req = authed("$BASE/auth/account/activity", token)
            .post(postBody("{}"))
            .build()
        return read(req) { json.decodeFromString<ProfileResponse>(it) }
    }

    // ── Mailbox sector ───────────────────────────────────────────────────────
    //
    // Mail is served by the gateway, reached through the same same-origin proxy
    // the website uses: `src/app/api/grapsee/grapmail/[...path]/route.ts:130`
    // forwards to `<upstream>/grapmail/*` and passes the bearer through
    // unchanged. So the app's mail base is `$BASE/grapsee/grapmail`, not
    // `$BASE/grapmail` — the latter is a Next.js 404 page, and an HTML body
    // where JSON was expected is precisely the failure that would otherwise be
    // read as "the mailbox is empty".
    //
    // These calls reuse `authed`, `read`, `postBody` and `fail` as they are, so
    // the bearer-401 → SessionEvents.signalExpired() interceptor applies to
    // every mail request without repeating itself.

    private val MAIL: String get() = "$BASE/grapsee/grapmail"

    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    suspend fun mailMe(token: String): MailMeResponse =
        read(authed("$MAIL/me", token).get().build()) { json.decodeFromString(it) }

    /**
     * `GET /stats`. Only five boxes are reported; a null on this type means the
     * server did not answer, and the caller must draw no badge rather than a
     * zero — see `MailStatsResponse`.
     */
    suspend fun mailStats(token: String): MailStatsResponse =
        read(authed("$MAIL/stats", token).get().build()) { json.decodeFromString(it) }

    /**
     * `GET /inbox`. `box` is the API value from [MailBox]; `q` is only sent when
     * the term is at least 3 characters, because a one-character term scans the
     * whole mailbox server-side (`grapmail.ts:688`) and the screen would pay for
     * every keystroke.
     */
    suspend fun mailInbox(
        token: String, box: MailBox, page: Int = 1, pageSize: Int = 25, q: String? = null,
    ): MailInboxResponse {
        val term = q?.trim()?.takeIf { it.length >= 3 }
        val url = buildString {
            append("$MAIL/inbox?box=").append(enc(box.apiValue))
            append("&page=").append(page).append("&pageSize=").append(pageSize)
            if (term != null) append("&q=").append(enc(term))
        }
        return read(authed(url, token).get().build()) { json.decodeFromString(it) }
    }

    /**
     * `GET /message/:id` — marks the message read as a side effect
     * (`grapmail.ts:750-757`), so callers must NOT also send `MailAction.Read`:
     * the second write is redundant and races the first.
     */
    suspend fun mailMessage(token: String, id: String): MailMessageResponse =
        read(authed("$MAIL/message/${enc(id)}", token).get().build()) { json.decodeFromString(it) }

    suspend fun mailThread(token: String, threadId: String): MailThreadResponse =
        read(authed("$MAIL/thread/${enc(threadId)}", token).get().build()) { json.decodeFromString(it) }

    /**
     * `PATCH /message/:id/:action` — the action is in the **path**, never the
     * body (`grapmail.ts:1253`). The two halves of [MailAction] come from
     * `grapmail-boxes.ts:265-270` and `grapmail-archive.ts:146-173`; only
     * `snooze` reads a body, and the key it reads is `until`.
     */
    suspend fun mailAction(
        token: String, id: String, action: MailAction, until: String? = null,
    ): MailMessageResponse {
        val body = if (action == MailAction.Snooze && until != null)
            json.encodeToString(MailSnoozeRequest.serializer(), MailSnoozeRequest(until))
        else "{}"
        val req = authed("$MAIL/message/${enc(id)}/${action.apiValue}", token)
            .method("PATCH", body.toRequestBody(JSON_MEDIA))
            .build()
        return read(req) { json.decodeFromString(it) }
    }

    /** Permanent destruction. Valid only from Trash — see [MailAction]. */
    suspend fun mailDeletePermanent(token: String, id: String) {
        val (status, body) = execute(authed("$MAIL/message/${enc(id)}", token).delete().build())
        if (status >= 400) fail(status, body)
    }

    suspend fun mailSend(token: String, req: MailSendRequest): MailSendResponse {
        val r = authed("$MAIL/send", token).post(postBody(
            json.encodeToString(MailSendRequest.serializer(), req))).build()
        return read(r) { json.decodeFromString(it) }
    }

    suspend fun mailDraft(token: String, req: MailDraftRequest): MailDraftResponse {
        val r = authed("$MAIL/draft", token).post(postBody(
            json.encodeToString(MailDraftRequest.serializer(), req))).build()
        return read(r) { json.decodeFromString(it) }
    }

    suspend fun mailLabels(token: String): MailLabelsResponse =
        read(authed("$MAIL/labels", token).get().build()) { json.decodeFromString(it) }

    /**
     * Create a label. The body key is **`colour`**, not `color`
     * (`grapmail-labels.ts:105`) — sending the American spelling creates the
     * label with no colour and returns 200, so the mistake is invisible.
     */
    suspend fun mailCreateLabel(token: String, name: String, colour: String?): MailLabelResponse {
        val r = authed("$MAIL/labels", token).post(postBody(
            json.encodeToString(MailLabelCreateRequest.serializer(),
                MailLabelCreateRequest(name, colour)))).build()
        return read(r) { json.decodeFromString(it) }
    }

    suspend fun mailRenameLabel(token: String, id: String, name: String, colour: String?) {
        val r = authed("$MAIL/labels/${enc(id)}", token)
            .method("PATCH", postBody(json.encodeToString(
                MailLabelCreateRequest.serializer(), MailLabelCreateRequest(name, colour))))
            .build()
        execute(r)
    }

    suspend fun mailDeleteLabel(token: String, id: String) {
        execute(authed("$MAIL/labels/${enc(id)}", token).delete().build())
    }

    /** Attach / detach a label from a message. */
    suspend fun mailLabelAssign(token: String, messageId: String, labelId: String) {
        val r = authed("$MAIL/labels/message/${enc(messageId)}", token)
            .post(postBody(json.encodeToString(
                MailLabelAssignRequest.serializer(), MailLabelAssignRequest(labelId))))
            .build()
        execute(r)
    }

    suspend fun mailLabelDetach(token: String, messageId: String, labelId: String) {
        execute(authed("$MAIL/labels/message/${enc(messageId)}/${enc(labelId)}", token).delete().build())
    }

    suspend fun mailAliases(token: String): MailAliasesResponse =
        read(authed("$MAIL/aliases", token).get().build()) { json.decodeFromString(it) }

    /**
     * `GET /attachments/:id` — the bytes, not JSON.
     *
     * Separate from `read`, which assumes a String body and would corrupt binary
     * by decoding it as text. Returns null only when the server answered 200 with
     * nothing; a 404 or a 401 still throws through the same path so the caller
     * sees the real reason and the 401 interceptor still fires.
     */
    suspend fun mailAttachmentBytes(token: String, id: String): ByteArray {
        val request = authed("$MAIL/attachments/${enc(id)}", token).get().build()
        val bytes = withContext(Dispatchers.IO) {
            client.newCall(request).execute().use { resp ->
                if (resp.code >= 400) {
                    fail(resp.code, resp.body?.string() ?: "")
                }
                resp.body?.bytes() ?: ByteArray(0)
            }
        }
        return bytes
    }

    /**
     * `POST /attachments` — multipart, with the field names the route reads:
     * `file` and `messageId` (`grapmail-attachments.ts:225,236`).
     *
     * `messageId` is sent empty for an unsent draft rather than omitted, because
     * the route coerces an absent field to null and stores the bytes with no row
     * to attach them to.
     */
    suspend fun mailAttachmentUpload(
        token: String, bytes: ByteArray, filename: String, mimeType: String, messageId: String?,
    ): MailAttachmentDto {
        val body = MultipartBody.Builder().setType(MultipartBody.FORM)
            .addFormDataPart("file", filename, bytes.toRequestBody(mimeType.toMediaType()))
            .addFormDataPart("messageId", messageId ?: "")
            .build()
        val req = authed("$MAIL/attachments", token).post(body).build()
        return read(req) {
            json.decodeFromString(MailAttachmentResponseJson.serializer(), it).attachment
        }
    }
    /**
     * The screener queue's own settings. Note `?box=screener` is the queue
     * itself (`grapmail-boxes.ts:57-66`) — this route only reads and writes the
     * switch, so the list comes from [mailInbox] like every other box.
     */
    suspend fun mailScreener(token: String): MailScreenerResponse =
        read(authed("$MAIL/screener", token).get().build()) { json.decodeFromString(it) }

    suspend fun mailScreenerSet(token: String, enabled: Boolean): MailScreenerResponse {
        val r = authed("$MAIL/screener/enabled", token)
            .method("PUT", postBody(json.encodeToString(
                MailScreenerEnableRequest.serializer(), MailScreenerEnableRequest(enabled))))
            .build()
        return read(r) { json.decodeFromString(it) }
    }
}

// ── Mailbox request bodies ───────────────────────────────────────────────────
// Field names are the keys the handlers destructure, not the app's vocabulary.
// `MailLabelCreateRequest.colour` is spelled the way `grapmail-labels.ts:105`
// reads it for exactly that reason.

@Serializable
data class MailSendRequest(
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val bcc: List<String> = emptyList(),
    val subject: String = "",
    val bodyText: String = "",
    val bodyHtml: String? = null,
    /**
     * Sent for reply threading. `grapmail.ts:851-900` is confirmed to read
     * to/cc/bcc/subject/bodyText/bodyHtml; this key is the one field the compose
     * screen needs that was not in that window, so it is re-checked against the
     * handler when the gateway is back — if the route ignores it, replies lose
     * their In-Reply-To and the thread view shows them as new messages.
     */
    val inReplyTo: String? = null,
)

@Serializable
data class MailDraftRequest(
    val to: List<String> = emptyList(),
    val cc: List<String> = emptyList(),
    val subject: String = "",
    val bodyText: String = "",
    val inReplyTo: String? = null,
)

@Serializable
data class MailSnoozeRequest(val until: String)

@Serializable
data class MailLabelCreateRequest(val name: String, val colour: String? = null)

@Serializable
data class MailLabelAssignRequest(val labelId: String)

@Serializable
data class MailScreenerEnableRequest(val enabled: Boolean)

/** The upload route answers with the row it wrote; this unwraps it. */
@Serializable
private data class MailAttachmentResponseJson(val attachment: MailAttachmentDto)
