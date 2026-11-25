package com.grapsee.grapmail.ui.mailfeatures

import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.grapsee.grapmail.R
import com.grapsee.grapmail.data.DeviceId
import com.grapsee.grapmail.data.SessionStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.text.DateFormat
import java.util.Date
import java.util.concurrent.TimeUnit

/**
 * Server-recorded read receipts (RFC 8098) — the app half of
 * `/grapmail/receipts` (grapsee-backend/services/shared/src/routes/grapmail-receipts.ts).
 *
 * Kept in its own file rather than in data/Api.kt because Api.kt is edited by
 * several work streams at once; this talks to the same base URL with the same
 * bearer and device headers Api.authed() sends. Moving these four calls into Api
 * later is a mechanical change.
 *
 * ── HONESTY ──────────────────────────────────────────────────────────────────
 * The on-device "Opened · noted on this device" marker (MailFeaturePrefs F24) is
 * left as it was. It is replaced by [SentReadLine] only when the SERVER has a
 * receipt, so the app never shows "Read" on the strength of its own guess.
 */
@Serializable data class ReceiptStateDto(
    val requested: Boolean = false,
    val channel: String? = null,
    val notifyTo: String? = null,
    val mode: String = "never",
    val response: String? = null,
    val respondedAt: String? = null,
    val allowed: Boolean = false,
    val refusal: String? = null,
)
@Serializable data class ReceiptStateResponse(val success: Boolean = false, val receipt: ReceiptStateDto? = null)
@Serializable data class ReceiptModeResponse(val success: Boolean = false, val mode: String = "never")
@Serializable data class SentReceiptDto(val messageId: String = "", val recipient: String = "", val readAt: String = "", val source: String = "")
@Serializable data class SentReceiptsResponse(
    val success: Boolean = false,
    val requested: List<String> = emptyList(),
    val receipts: List<SentReceiptDto> = emptyList(),
)

object ReceiptsApi {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()
    private val JSON_MEDIA = "application/json".toMediaType()
    private val base: String get() = com.grapsee.grapmail.BuildConfig.API_BASE + "/grapsee/grapmail/receipts"

    private fun req(path: String, token: String) = Request.Builder().url(base + path)
        .header("x-device-id", DeviceId.value)
        .header("authorization", "Bearer $token")

    private suspend fun call(r: Request): Pair<Int, String> = withContext(Dispatchers.IO) {
        client.newCall(r).execute().use { it.code to (it.body?.string() ?: "") }
    }

    suspend fun state(token: String, messageId: String): ReceiptStateDto? {
        val (s, b) = call(req("/message/$messageId", token).get().build())
        return if (s in 200..299) json.decodeFromString(ReceiptStateResponse.serializer(), b).receipt else null
    }

    /** `explicit = false` only for the automatic call in Always mode; the server decides. */
    suspend fun respond(token: String, messageId: String, send: Boolean, explicit: Boolean = true): Int {
        val body = """{"action":"${if (send) "send" else "decline"}","explicit":$explicit}"""
        return call(req("/message/$messageId", token).post(body.toRequestBody(JSON_MEDIA)).build()).first
    }

    // Kept on purpose, no longer called: the composer sends `requestReadReceipt: true` in the
    // /send body (MailboxViewModel) and the server records the request inside the send
    // transaction, so this separate call is redundant. Left for marking an already-sent message.
    suspend fun request(token: String, sentMessageId: String): Int =
        call(req("/request/$sentMessageId", token).post("{}".toRequestBody(JSON_MEDIA)).build()).first

    suspend fun mode(token: String): String? {
        val (s, b) = call(req("/settings", token).get().build())
        return if (s in 200..299) json.decodeFromString(ReceiptModeResponse.serializer(), b).mode else null
    }

    suspend fun setMode(token: String, mode: String): Boolean =
        call(req("/settings", token).put("""{"mode":"$mode"}""".toRequestBody(JSON_MEDIA)).build()).first in 200..299

    suspend fun sent(token: String, ids: List<String>): SentReceiptsResponse? {
        if (ids.isEmpty()) return SentReceiptsResponse(success = true)
        val (s, b) = call(req("/sent?ids=" + ids.take(100).joinToString(","), token).get().build())
        return if (s in 200..299) json.decodeFromString(SentReceiptsResponse.serializer(), b) else null
    }
}

private fun shortTime(iso: String): String = runCatching {
    DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
        .format(Date.from(java.time.Instant.parse(iso)))
}.getOrDefault(iso)

/**
 * The reader's Ask prompt. Shows only when the server says a receipt was asked
 * for, none has been decided, and an explicit tap would be allowed. In Always mode
 * it sends without asking (the server re-checks the mode).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReadReceiptAskPrompt(messageId: String, direction: String?) {
    if (direction == "outbound") return
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var state by remember(messageId) { mutableStateOf<ReceiptStateDto?>(null) }
    var done by remember(messageId) { mutableStateOf<Boolean?>(null) }
    LaunchedEffect(messageId) {
        val token = SessionStore.token(context) ?: return@LaunchedEffect
        val s = runCatching { ReceiptsApi.state(token, messageId) }.getOrNull() ?: return@LaunchedEffect
        state = s
        if (s.requested && s.response == null && s.mode == "always") {
            val code = runCatching { ReceiptsApi.respond(token, messageId, send = true, explicit = false) }.getOrDefault(0)
            if (code in 200..299) done = true
        }
    }
    val s = state ?: return
    if (done == true || s.response == "sent") {
        Text(stringResource(R.string.mail_receipt_server_sent), fontSize = 11.sp, modifier = Modifier.padding(horizontal = 12.dp))
        return
    }
    if (!s.requested || s.response != null || s.mode != "ask" || done == false) return
    if (!s.allowed) return
    Card(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.mail_receipt_ask_title), fontSize = 14.sp)
            Text(stringResource(R.string.mail_receipt_ask_body, s.notifyTo.orEmpty()), fontSize = 12.sp)
            // Wraps at large text / narrow windows instead of clipping Decline.
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(onClick = {
                    scope.launch {
                        val token = SessionStore.token(context) ?: return@launch
                        val code = runCatching { ReceiptsApi.respond(token, messageId, send = true) }.getOrDefault(0)
                        done = code in 200..299
                    }
                }) { Text(stringResource(R.string.mail_receipt_ask_send)) }
                TextButton(onClick = {
                    scope.launch {
                        val token = SessionStore.token(context) ?: return@launch
                        runCatching { ReceiptsApi.respond(token, messageId, send = false) }
                        done = false
                    }
                }) { Text(stringResource(R.string.mail_receipt_ask_decline)) }
            }
        }
    }
}

/**
 * "Read <time>" on a sent message, from server data only. Shows nothing when the
 * server has nothing, so the existing local label stays the truth until then.
 * Returns whether a server receipt was shown, so a caller can hide the local chip.
 */
@Composable
fun SentReadLine(messageId: String, direction: String?): Boolean {
    if (direction != "outbound") return false
    val context = LocalContext.current
    var data by remember(messageId) { mutableStateOf<SentReceiptsResponse?>(null) }
    LaunchedEffect(messageId) {
        val token = SessionStore.token(context) ?: return@LaunchedEffect
        data = runCatching { ReceiptsApi.sent(token, listOf(messageId)) }.getOrNull()
    }
    val d = data ?: return false
    val first = d.receipts.firstOrNull { it.messageId == messageId }
    return when {
        first != null -> {
            Text(stringResource(R.string.mail_receipt_read_at, shortTime(first.readAt)), fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 12.dp))
            true
        }
        messageId in d.requested -> {
            Text(stringResource(R.string.mail_receipt_requested_pending), fontSize = 11.sp,
                modifier = Modifier.padding(horizontal = 12.dp))
            false
        }
        else -> false
    }
}

/** Settings: Never (default) / Ask / Always, stored on the server. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReadReceiptModeSetting() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var mode by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val token = SessionStore.token(context) ?: return@LaunchedEffect
        mode = runCatching { ReceiptsApi.mode(token) }.getOrNull()
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text(stringResource(R.string.mail_receipt_mode_title), fontSize = 14.sp)
        Text(stringResource(R.string.mail_receipt_mode_body), fontSize = 12.sp)
        if (mode == null) {
            Text(stringResource(R.string.mail_receipt_mode_unavailable), fontSize = 12.sp)
            return@Column
        }
        // Wraps: three chips do not fit one line at large text on a small phone.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(
                "never" to R.string.mail_receipt_mode_never,
                "ask" to R.string.mail_receipt_mode_ask,
                "always" to R.string.mail_receipt_mode_always,
            ).forEach { (value, label) ->
                FilterChip(
                    selected = mode == value,
                    onClick = {
                        val previous = mode
                        mode = value
                        scope.launch {
                            val token = SessionStore.token(context) ?: return@launch
                            if (!runCatching { ReceiptsApi.setMode(token, value) }.getOrDefault(false)) mode = previous
                        }
                    },
                    label = { Text(stringResource(label)) },
                )
            }
        }
        if (mode == "always") Text(stringResource(R.string.mail_receipt_mode_always_note), fontSize = 11.sp)
    }
}

/** Compose: the per-message "Request read receipt" switch. The caller calls [ReceiptsApi.request] after send. */
@Composable
fun RequestReceiptToggle(checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(R.string.mail_compose_request_receipt), fontSize = 13.sp, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
