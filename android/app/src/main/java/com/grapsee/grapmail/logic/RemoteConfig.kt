package com.grapsee.grapmail.logic

import android.content.Context
import com.grapsee.grapmail.BuildConfig
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.AppConfigDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.json.Json

/**
 * The server's app configuration (backend lib/app-config.ts): kill switches, the
 * minimum supported build, and a notice. Read at launch and whenever the app
 * comes back to the front (at most every 15 minutes), cached so a cold start
 * offline still knows the last answer.
 *
 * The kill switches are enforced by the server; the app reads them to explain
 * rather than to enforce: a paused feature says so before the person tries it.
 * Below `minVersion` the app shows only the update screen, because a build the
 * server has stopped supporting would fail in ways it cannot explain.
 */
object RemoteConfig {
    private const val PREFS = "grapmail-remote-config"
    private const val K_DOC = "doc"
    private const val REFRESH_MS = 15L * 60 * 1000
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }

    private val _config = MutableStateFlow(AppConfigDto())
    val config: StateFlow<AppConfigDto> = _config.asStateFlow()
    @Volatile private var lastFetch = 0L

    fun loadCached(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(K_DOC, null)
            ?.let { runCatching { json.decodeFromString(AppConfigDto.serializer(), it) }.getOrNull() }
            ?.let { _config.value = it }
    }

    /** Fetch the configuration if the copy is older than 15 minutes. Never throws. */
    suspend fun refresh(context: Context, force: Boolean = false) {
        val now = System.currentTimeMillis()
        if (!force && now - lastFetch < REFRESH_MS) return
        lastFetch = now
        val fresh = runCatching { Api.appConfig() }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .getOrNull()?.takeIf { it.success } ?: return
        _config.value = fresh
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(K_DOC, json.encodeToString(AppConfigDto.serializer(), fresh)).apply()
    }

    /** True when the server has paused [feature] (send, signup, attachments, e2eRecovery, outbox). */
    fun isPaused(feature: String): Boolean = _config.value.killSwitches[feature] == true

    /** True when this build is older than the server's minimum: only the update screen shows. */
    fun updateRequired(): Boolean = BuildConfig.VERSION_CODE < _config.value.minVersion.android

    fun flag(name: String, default: Boolean = false): Boolean = _config.value.flags[name] ?: default
}
