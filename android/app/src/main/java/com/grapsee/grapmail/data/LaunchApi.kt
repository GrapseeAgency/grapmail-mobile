package com.grapsee.grapmail.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.MediaType.Companion.toMediaType
import java.util.concurrent.TimeUnit

@Serializable
data class ActionResponse(val success: Boolean = false, val message: String? = null, val error: String? = null)

/** These writes must never be replayed with another account's bearer, even after a 401.
 * No authenticator, redirects, automatic retries, response-body logging, or global expiry event. */
internal class LaunchApi(
    private val base: String,
    private val deviceId: String,
    private val userAgent: String,
    private val client: OkHttpClient = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build(),
) {
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun deleteAccount(token: String, password: String): ActionResponse = post(
        "/grapsee/auth/account/delete", token,
        buildJsonObject { put("password", password) }.toString(),
    )

    suspend fun registerPush(token: String, pushToken: String): ActionResponse = post(
        "/grapsee/grapmail/push/register", token,
        buildJsonObject {
            put("platform", "android")
            put("token", pushToken)
            put("deviceId", deviceId)
        }.toString(),
    )

    /** Same path, DELETE, the token to retire in the body. Best-effort at the
     *  call site: a refusal here must not block the sign-out it runs beside. */
    suspend fun unregisterPush(token: String, pushToken: String): ActionResponse = send(
        "DELETE", "/grapsee/grapmail/push/register", token,
        buildJsonObject { put("token", pushToken) }.toString(),
    )

    private suspend fun post(path: String, token: String, body: String): ActionResponse =
        send("POST", path, token, body)

    private suspend fun send(method: String, path: String, token: String, body: String): ActionResponse =
        withContext(Dispatchers.IO) {
            val request = Request.Builder().url(base.trimEnd('/') + path)
                .header("authorization", "Bearer $token")
                .header("x-device-id", deviceId).header("user-agent", userAgent)
                .method(method, body.toRequestBody("application/json".toMediaType())).build()
            client.newCall(request).execute().use { response ->
                // Do not display an untrusted body that could echo credentials.
                if (!response.isSuccessful) throw ApiException(response.code, null, when (response.code) {
                    401 -> "Authentication was refused. Check the password or sign in again."
                    429 -> "Too many attempts. Try again later."
                    else -> "The request could not be confirmed (${response.code})."
                })
                // The unregister's 204 carries no body by contract: the status is
                // the confirmation, and decoding "" would invent a failure the
                // server did not send.
                val text = response.body?.string().orEmpty()
                if (text.isBlank()) return@use ActionResponse(success = true)
                val result = json.decodeFromString<ActionResponse>(text)
                if (!result.success) throw ApiException(response.code, null, "The server did not confirm the request.")
                result
            }
        }
}
