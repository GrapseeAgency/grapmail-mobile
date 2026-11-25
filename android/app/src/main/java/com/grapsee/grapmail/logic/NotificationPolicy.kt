package com.grapsee.grapmail.logic

import android.content.Context
import com.grapsee.grapmail.R
import com.grapsee.grapmail.data.Api
import com.grapsee.grapmail.data.NotificationSettingsDto
import com.grapsee.grapmail.data.ProfilePatch
import com.grapsee.grapmail.data.ProfileSettingsPatch
import com.grapsee.grapmail.data.SessionStore
import com.grapsee.grapmail.ui.i18n.CoreStrings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/**
 * The account's notification rules, on this device.
 *
 * The server enforces them where it sends the push (backend
 * lib/notification-policy.ts), so a held-back message never wakes the phone. This
 * is the same rule set for the alerts the app builds itself: the background check
 * that runs when no push arrived, and the full alert Android draws from a push. The
 * two implementations are kept line for line; the server's tests are the spec.
 *
 * The copy here is refreshed from the profile at most every six hours by the
 * background check, and immediately whenever this device changes a rule.
 */
object NotificationPolicy {
    private const val PREFS = "grapmail-notify-policy"
    private const val K_DOC = "doc"
    private const val K_AT = "fetchedAt"
    private const val K_GENERIC = "genericIds"
    private const val STALE_MS = 6L * 60 * 60 * 1000
    private const val SECURITY = "security@grapsee.com"

    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true; encodeDefaults = true }
    private val writes = Mutex()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    // One copy per mailbox: with several accounts signed in, each alert follows
    // the rules of the account it is for.
    private fun key(base: String, account: String?) = base + ":" + account?.trim()?.lowercase(Locale.ROOT).orEmpty()

    /** The cached rules for [account], or the defaults (alert for everything). */
    fun cached(context: Context, account: String?): NotificationSettingsDto =
        prefs(context).getString(key(K_DOC, account), null)
            ?.let { runCatching { json.decodeFromString(NotificationSettingsDto.serializer(), it) }.getOrNull() }
            ?: NotificationSettingsDto()

    fun store(context: Context, account: String?, settings: NotificationSettingsDto) {
        prefs(context).edit()
            .putString(key(K_DOC, account), json.encodeToString(NotificationSettingsDto.serializer(), settings))
            .putLong(key(K_AT, account), System.currentTimeMillis())
            .apply()
    }

    /** The signed-in account's rules, for the screens. */
    suspend fun cachedForCurrent(context: Context): NotificationSettingsDto =
        cached(context, SessionStore.address(context))

    suspend fun storeForCurrent(context: Context, settings: NotificationSettingsDto) =
        store(context, SessionStore.address(context), settings)

    data class Decision(val notify: Boolean, val generic: Boolean)

    fun isVip(settings: NotificationSettingsDto, fromAddress: String?): Boolean {
        val from = fromAddress?.trim()?.lowercase(Locale.ROOT).orEmpty()
        if (from.isEmpty()) return false
        val domain = from.substringAfterLast('@', "")
        return settings.vipSenders.any { v ->
            if (v.contains('@')) v == from else domain == v || domain.endsWith(".$v")
        }
    }

    private fun minutes(clock: String): Int? {
        val m = Regex("^([01]\\d|2[0-3]):([0-5]\\d)$").find(clock) ?: return null
        return m.groupValues[1].toInt() * 60 + m.groupValues[2].toInt()
    }

    fun inQuietHours(settings: NotificationSettingsDto, nowMillis: Long): Boolean {
        val q = settings.quietHours
        if (!q.enabled) return false
        val start = minutes(q.start) ?: return false
        val end = minutes(q.end) ?: return false
        val zone = runCatching { if (q.timeZone.isBlank()) ZoneOffset.UTC else ZoneId.of(q.timeZone) }
            .getOrDefault(ZoneOffset.UTC)
        val local = Instant.ofEpochMilli(nowMillis).atZone(zone)
        val t = local.hour * 60 + local.minute
        return if (start < end) t in start until end else t >= start || t < end
    }

    /** The decision for one message, the same as the server's decideNotification. */
    fun decide(
        settings: NotificationSettingsDto,
        fromAddress: String?,
        threadId: String?,
        nowMillis: Long = System.currentTimeMillis(),
    ): Decision {
        val generic = !settings.showPreview
        if (fromAddress?.trim()?.lowercase(Locale.ROOT) == SECURITY) return Decision(true, generic)
        val thread = threadId.orEmpty()
        if (thread.isNotEmpty() && thread in settings.mutedThreads) return Decision(false, generic)
        val watched = thread.isNotEmpty() && thread in settings.watchedThreads
        val priority = watched || isVip(settings, fromAddress)
        if (settings.mode == "none" && !watched) return Decision(false, generic)
        if (settings.mode == "priority" && !priority) return Decision(false, generic)
        if (inQuietHours(settings, nowMillis) && !(settings.quietHours.allowVip && priority)) return Decision(false, generic)
        return Decision(true, generic)
    }

    fun decide(context: Context, account: String?, fromAddress: String?, threadId: String?): Decision =
        decide(cached(context, account), fromAddress, threadId)

    /**
     * A count-only alert ("3 new messages") names no sender, so it is allowed only
     * when an ordinary message would be: all-mail mode, outside quiet hours.
     */
    fun allowsSummary(context: Context, account: String?, nowMillis: Long = System.currentTimeMillis()): Boolean {
        val s = cached(context, account)
        return s.mode == "all" && !inQuietHours(s, nowMillis)
    }

    // ── pushes the server already made generic ─────────────────────────────

    /** The push for this message said preview '0'; the alert drawn for it stays generic. */
    fun markGeneric(context: Context, messageId: String) {
        val p = prefs(context)
        val ids = p.getString(K_GENERIC, "").orEmpty().split(' ').filter { it.isNotEmpty() }
        p.edit().putString(K_GENERIC, (ids + messageId).takeLast(50).joinToString(" ")).apply()
    }

    fun isGeneric(context: Context, messageId: String?): Boolean =
        messageId != null && prefs(context).getString(K_GENERIC, "").orEmpty().split(' ').contains(messageId)

    // ── keeping the copy current ───────────────────────────────────────────

    /** Re-read [account]'s rules from the profile if the copy is older than six hours. Never throws. */
    suspend fun refreshIfStale(context: Context, account: String?, token: String) {
        val at = prefs(context).getLong(key(K_AT, account), 0L)
        if (System.currentTimeMillis() - at < STALE_MS) return
        runCatching { Api.profile(token).profile?.settings?.notifications }
            .onFailure { if (it is kotlinx.coroutines.CancellationException) throw it }
            .getOrNull()
            ?.let { store(context, account, it) }
    }

    /**
     * Change the rules: read the current ones from the server, apply [change],
     * save, and keep what the server stored. Returns null on success or the
     * sentence to show. Reading first means a rule changed on another device is
     * not overwritten by this device's older copy.
     */
    suspend fun update(
        context: Context,
        change: (NotificationSettingsDto) -> NotificationSettingsDto,
    ): String? = withContext(Dispatchers.IO) {
        writes.withLock {
            val token = SessionStore.token(context) ?: return@withLock CoreStrings.get(R.string.core_sign_in_again) { "Sign in again." }
            val account = SessionStore.address(context)
            try {
                val current = Api.profile(token).profile?.settings?.notifications
                    ?: return@withLock CoreStrings.get(R.string.core_notify_prefs_read_failed) { "Your notification settings could not be read. Try again." }
                val next = change(current)
                val saved = Api.patchProfile(token, ProfilePatch(settings = ProfileSettingsPatch(notifications = next)))
                val stored = saved.profile?.settings?.notifications ?: next
                if (!saved.success && saved.error != null) return@withLock saved.error
                store(context, account, stored)
                null
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                e.message ?: CoreStrings.get(R.string.core_notify_prefs_save_failed) { "The change could not be saved. Try again." }
            }
        }
    }

    // ── conversation and sender shortcuts (the reader's menu) ──────────────

    fun isMuted(context: Context, account: String?, threadKey: String) = threadKey in cached(context, account).mutedThreads
    fun isWatched(context: Context, account: String?, threadKey: String) = threadKey in cached(context, account).watchedThreads
    fun isVip(context: Context, account: String?, address: String?) = isVip(cached(context, account), address)

    suspend fun setMuted(context: Context, threadKey: String, muted: Boolean): String? = update(context) { s ->
        s.copy(
            mutedThreads = if (muted) (s.mutedThreads - threadKey + threadKey).takeLast(500) else s.mutedThreads - threadKey,
            watchedThreads = if (muted) s.watchedThreads - threadKey else s.watchedThreads,
        )
    }

    suspend fun setWatched(context: Context, threadKey: String, watched: Boolean): String? = update(context) { s ->
        s.copy(
            watchedThreads = if (watched) (s.watchedThreads - threadKey + threadKey).takeLast(500) else s.watchedThreads - threadKey,
            mutedThreads = if (watched) s.mutedThreads - threadKey else s.mutedThreads,
        )
    }

    suspend fun setVip(context: Context, address: String, vip: Boolean): String? {
        val key = address.trim().lowercase(Locale.ROOT)
        return update(context) { s ->
            s.copy(vipSenders = if (vip) (s.vipSenders - key + key) else s.vipSenders - key)
        }
    }
}
