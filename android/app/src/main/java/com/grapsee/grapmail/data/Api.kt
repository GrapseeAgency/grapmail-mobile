package com.grapsee.grapmail.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
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
    val account: AccountDto? = null,
    val requireOtp: Boolean = false,
    val challengeId: String? = null,
    val delivered: Boolean = false,
    val backupCodesRemaining: Int? = null,
    val message: String? = null,
    val error: String? = null,
    val code: String? = null,
)

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
    val user: AccountDto? = null,
    val error: String? = null,
    val code: String? = null,
)

class ApiException(val status: Int, val code: String?, message: String) : Exception(message)

object Api {
    val BASE: String = com.grapsee.grapmail.BuildConfig.API_BASE

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val JSON_MEDIA = "application/json".toMediaType()

    private val client: OkHttpClient = OkHttpClient.Builder()
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
}
