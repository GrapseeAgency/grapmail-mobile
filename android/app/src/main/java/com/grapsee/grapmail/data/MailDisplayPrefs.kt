package com.grapsee.grapmail.data

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.time.ZoneId

/**
 * The account's display preferences, held where the mailbox list can read them.
 *
 * Mail settings saves `density`, `locale.dateFormat` and `locale.timezone` on the
 * account, and they used to stop there: the list drew every row at the same
 * height and every date in the device's own style, whatever the screen said.
 * This object is the bridge. The account record stays the source of truth (the
 * website edits the same fields); a copy is kept in SharedPreferences so the
 * list draws correctly at launch, before the profile has been re-read.
 *
 * The values are snapshot state, so a row recomposes the moment a setting
 * changes rather than on the next scroll.
 *
 * An empty date format or time zone means "automatic": the device's own locale
 * and time zone, which is also what the app did before these were applied.
 */
object MailDisplayPrefs {
    private const val PREFS = "grapmail-display"
    private const val K_DENSITY = "density"
    private const val K_DATE = "dateFormat"
    private const val K_ZONE = "timezone"

    var compact by mutableStateOf(false)
        private set
    /** "", "dmy", "mdy" or "ymd". */
    var dateFormat by mutableStateOf("")
        private set
    /** "" for the device's zone, otherwise an IANA id such as "Europe/Berlin". */
    var timezone by mutableStateOf("")
        private set

    @Volatile private var loaded = false

    fun ensureLoaded(context: Context) {
        if (loaded) return
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        compact = prefs.getString(K_DENSITY, "comfortable") == "compact"
        dateFormat = normalizeDateFormat(prefs.getString(K_DATE, "") ?: "")
        timezone = prefs.getString(K_ZONE, "") ?: ""
        loaded = true
    }

    /** Take the account's settings as the new truth and remember them. */
    fun apply(context: Context, settings: ProfileSettingsDto?) {
        settings ?: return
        compact = settings.density == "compact"
        dateFormat = normalizeDateFormat(settings.locale.dateFormat)
        timezone = settings.locale.timezone.trim().takeIf { zoneOrNull(it) != null } ?: ""
        loaded = true
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString(K_DENSITY, if (compact) "compact" else "comfortable")
            .putString(K_DATE, dateFormat)
            .putString(K_ZONE, timezone)
            .apply()
    }

    /** Re-read the account so a change made on another device shows up here. */
    suspend fun refresh(context: Context) {
        ensureLoaded(context)
        val token = SessionStore.token(context) ?: return
        runCatching { Api.profile(token).profile?.settings }
            .getOrNull()
            ?.let { apply(context, it) }
    }

    /** The zone timestamps are drawn in. */
    fun zone(): ZoneId = zoneOrNull(timezone) ?: ZoneId.systemDefault()

    fun zoneOrNull(id: String): ZoneId? =
        id.trim().takeIf { it.isNotEmpty() }?.let { runCatching { ZoneId.of(it) }.getOrNull() }

    private fun normalizeDateFormat(value: String): String =
        value.trim().lowercase().takeIf { it == "dmy" || it == "mdy" || it == "ymd" } ?: ""
}
