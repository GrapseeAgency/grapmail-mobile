package com.grapsee.grapmail.logic

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import com.grapsee.grapmail.BuildConfig
import com.grapsee.grapmail.data.Api
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.File

/**
 * Crash, hang and session reports to GrapMail's own server (backend
 * routes/grapmail-client.ts), so the release plan's crash-free rate can be measured
 * without a paid service.
 *
 *   crash    an uncaught exception: written to a file as the process dies, sent at
 *            the next launch (a dying process cannot be trusted to finish a request)
 *   hang     an ANR or a native crash, from Android's own exit records (API 30+)
 *   session  one per launch, counted per day and version on the server
 *
 * What is sent: platform, app version, Android version, device model, the kind, and
 * for a crash its message and stack trace. Nothing about the account or its mail; the
 * server scrubs anything shaped like an address or a token as well. Settings → "Send
 * crash reports" turns all of it off.
 */
object CrashReporter {
    private const val PREFS = "grapmail-crash-reporter"
    private const val K_ENABLED = "enabled"
    private const val K_LAST_EXIT = "lastExitTimestamp"
    private const val PENDING = "grapmail-crash-pending.json"

    fun enabled(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(K_ENABLED, true)

    fun setEnabled(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(K_ENABLED, on).apply()
        if (!on) File(context.noBackupFilesDir, PENDING).delete()
    }

    /** Install the handler. Chains to the previous one, so the system still shows its dialog. */
    fun install(context: Context) {
        val app = context.applicationContext
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                if (enabled(app)) {
                    val report = base("crash").toMutableMap().apply {
                        put("message", JsonPrimitive("${error.javaClass.name}: ${error.message ?: ""}".take(2000)))
                        put("stack", JsonPrimitive(error.stackTraceToString().take(16000)))
                        put("thread", JsonPrimitive(thread.name.take(60)))
                    }
                    File(app.noBackupFilesDir, PENDING).writeText(JsonObject(report).toString())
                }
            }
            previous?.uncaughtException(thread, error)
        }
    }

    private fun base(kind: String): Map<String, JsonPrimitive> = mapOf(
        "platform" to JsonPrimitive("android"),
        "appVersion" to JsonPrimitive("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"),
        "osVersion" to JsonPrimitive("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"),
        "device" to JsonPrimitive("${Build.MANUFACTURER} ${Build.MODEL}".take(80)),
        "kind" to JsonPrimitive(kind),
        "occurredAt" to JsonPrimitive(java.time.Instant.now().toString()),
    )

    /** At launch: this session, the last crash if one was written, and new exit records. Never throws. */
    suspend fun sendAtLaunch(context: Context) {
        val app = context.applicationContext
        if (!enabled(app)) return
        val reports = mutableListOf<JsonObject>(JsonObject(base("session")))
        val pending = File(app.noBackupFilesDir, PENDING)
        runCatching { if (pending.exists()) reports += kotlinx.serialization.json.Json.parseToJsonElement(pending.readText()) as JsonObject }
        reports += exitRecords(app)
        val body = buildJsonObject { put("reports", JsonArray(reports.take(20))) }.toString()
        val sent = runCatching { Api.clientReports(body) }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .isSuccess
        if (sent) pending.delete()
    }

    /** ANRs and native crashes the system recorded since the last report (Android 11+). */
    private fun exitRecords(context: Context): List<JsonObject> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return emptyList()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val since = prefs.getLong(K_LAST_EXIT, 0L)
        val manager = context.getSystemService(ActivityManager::class.java) ?: return emptyList()
        val exits = runCatching { manager.getHistoricalProcessExitReasons(context.packageName, 0, 10) }.getOrNull()
            ?: return emptyList()
        val fresh = exits.filter {
            it.timestamp > since &&
                (it.reason == ApplicationExitInfo.REASON_ANR || it.reason == ApplicationExitInfo.REASON_CRASH_NATIVE)
        }
        exits.maxOfOrNull { it.timestamp }?.let { prefs.edit().putLong(K_LAST_EXIT, maxOf(it, since)).apply() }
        return fresh.map { exit ->
            val kind = if (exit.reason == ApplicationExitInfo.REASON_ANR) "hang" else "crash"
            val trace = runCatching { exit.traceInputStream?.bufferedReader()?.use { it.readText().take(16000) } }.getOrNull()
            JsonObject(base(kind) + mapOf(
                "message" to JsonPrimitive((if (kind == "hang") "ANR: " else "Native crash: ") + (exit.description ?: "").take(500)),
                "stack" to JsonPrimitive(trace ?: ""),
                "occurredAt" to JsonPrimitive(java.time.Instant.ofEpochMilli(exit.timestamp).toString()),
            ))
        }
    }
}
