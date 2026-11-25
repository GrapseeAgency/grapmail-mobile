package com.grapsee.grapmail.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * Connected provider accounts (Outlook via Microsoft Graph, generic IMAP/SMTP).
 * Backend: services/shared/src/routes/grapmail-providers.ts, mounted by
 * auth-service at `/grapmail` → reachable at `$BASE/grapsee/grapmail/providers…`
 * like every other mail route in [Api].
 *
 * Kept in its own file (like [LaunchApi]) so Api.kt, which other work edits in
 * parallel, stays untouched. Like LaunchApi: no retries, no redirects — the IMAP
 * connect body carries a password and must never be replayed.
 *
 * Gmail is intentionally absent: the paid annual assessment is off.
 */
object ProviderApi {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; explicitNulls = false }
    private val JSON = "application/json".toMediaType()
    private val client = OkHttpClient.Builder()
        .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .connectTimeout(15, TimeUnit.SECONDS).callTimeout(30, TimeUnit.SECONDS).build()

    private val PROVIDERS: String get() = "${Api.BASE}/grapsee/grapmail/providers"
    private fun enc(v: String) = URLEncoder.encode(v, "UTF-8")

    private suspend fun call(method: String, url: String, token: String, body: String? = null): String =
        withContext(Dispatchers.IO) {
            val req = Request.Builder().url(url)
                .header("authorization", "Bearer $token")
                .header("x-device-id", DeviceId.value)
                .method(method, body?.toRequestBody(JSON) ?: if (method == "GET") null else "{}".toRequestBody(JSON))
                .build()
            client.newCall(req).execute().use { r ->
                val text = r.body?.string().orEmpty()
                if (!r.isSuccessful) {
                    // The provider routes answer `{ success:false, error:"…" }` with
                    // sentences written for people (no credentials echoed), so the
                    // server's words are used; anything unparsable gets the generic line.
                    val msg = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.jsonPrimitive?.content }.getOrNull()
                    throw ProviderApiException(r.code, msg)
                }
                text
            }
        }

    /** GET /providers — accounts (all statuses, including revoked) + capabilities. */
    suspend fun list(token: String): ProviderListResponse =
        json.decodeFromString(ProviderListResponse.serializer(), call("GET", PROVIDERS, token))

    /** POST /providers/microsoft/authorize — 501 when the deployment has no client id. */
    suspend fun authorizeOutlook(token: String): ProviderAuthorizeResponse =
        json.decodeFromString(ProviderAuthorizeResponse.serializer(), call("POST", "$PROVIDERS/microsoft/authorize", token))

    /** POST /providers/microsoft/connect — exchanges the code; redirectUri must equal the authorize one. */
    suspend fun connectOutlook(token: String, req: OAuthConnectRequest): ProviderConnectResponse =
        json.decodeFromString(ProviderConnectResponse.serializer(),
            call("POST", "$PROVIDERS/microsoft/connect", token, json.encodeToString(OAuthConnectRequest.serializer(), req)))

    /** POST /providers/imap/connect — stored without a probe; the first sync verifies it. */
    suspend fun connectImap(token: String, req: ImapConnectRequest): ProviderConnectResponse =
        json.decodeFromString(ProviderConnectResponse.serializer(),
            call("POST", "$PROVIDERS/imap/connect", token, json.encodeToString(ImapConnectRequest.serializer(), req)))

    /** POST /providers/:id/sync — enqueues a manual sync job. */
    suspend fun syncNow(token: String, id: String): ProviderActionResponse =
        json.decodeFromString(ProviderActionResponse.serializer(), call("POST", "$PROVIDERS/${enc(id)}/sync", token))

    /** DELETE /providers/:id — marks the account revoked server-side. Nothing local is deleted. */
    suspend fun disconnect(token: String, id: String): ProviderActionResponse =
        json.decodeFromString(ProviderActionResponse.serializer(), call("DELETE", "$PROVIDERS/${enc(id)}", token))
}

class ProviderApiException(val status: Int, val serverMessage: String?) :
    Exception(serverMessage ?: "HTTP $status")
