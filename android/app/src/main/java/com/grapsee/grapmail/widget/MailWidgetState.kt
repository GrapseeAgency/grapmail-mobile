package com.grapsee.grapmail.widget

import android.content.Context
import androidx.glance.appwidget.updateAll

/**
 * What the home-screen widget is allowed to say.
 *
 * The last unread count a real read found — the background poll, or the stats
 * follow-up a push lands on — plus the newest sender line and the three most
 * recent messages (sender + subject) from that same read. The widget never
 * fetches on its own; when it has nothing, it says that instead of drawing a
 * zero it did not witness. A signed-out device shows silence, because silence
 * is not "0 unread".
 */
object MailWidgetState {
    private const val PREFS = "grapmail-widget"
    private const val KEY_UNREAD = "unread"
    private const val KEY_LATEST = "latest"
    private const val KEY_RECENT = "recent"
    private const val RECENT_SEP = "\u0001"
    private const val FIELD_SEP = "\u0002"
    private const val RECENT_LIMIT = 3

    data class Snapshot(
        val unread: Int?,
        val latest: String?,
        val recent: List<Pair<String, String>>,
    )

    fun load(context: Context): Snapshot {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        return Snapshot(
            unread = prefs.getInt(KEY_UNREAD, -1).takeIf { it >= 0 },
            latest = prefs.getString(KEY_LATEST, null),
            recent = decode(prefs.getString(KEY_RECENT, null)),
        )
    }

    /**
     * Record one real read's answer and refresh every widget on the device.
     * [recent] carries the newest three (sender, subject) pairs when the caller
     * has them; a null [recent] keeps whatever the last real read stored.
     */
    /** What the widgets are currently showing; an unchanged read skips the redraw. */
    private var lastPublished: Triple<Int, String, String?>? = null

    suspend fun publish(
        context: Context,
        unread: Int?,
        latest: String?,
        recent: List<Pair<String, String>>? = null,
    ) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val editor = prefs.edit()
            .putInt(KEY_UNREAD, unread ?: -1)
            .putString(KEY_LATEST, latest?.take(120))
        if (recent != null) editor.putString(KEY_RECENT, encode(recent))
        editor.apply()
        // Every publish used to wake Glance and redraw every widget even when
        // unread, latest and recent were byte-identical — one redraw per poll
        // with nothing new to show. Compare the effective triple and skip.
        val signature = Triple(
            unread ?: -1,
            latest?.take(120) ?: prefs.getString(KEY_LATEST, null).orEmpty(),
            recent?.let { encode(it) } ?: prefs.getString(KEY_RECENT, null),
        )
        if (signature == lastPublished) return
        lastPublished = signature
        runCatching { UnreadWidget().updateAll(context) }
    }

    /** A sign-out takes its count with it. */
    suspend fun clear(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().clear().apply()
        runCatching { UnreadWidget().updateAll(context) }
    }

    private fun encode(recent: List<Pair<String, String>>): String =
        recent.take(RECENT_LIMIT).joinToString(RECENT_SEP) { pair ->
            pair.first.take(48).replace(RECENT_SEP, " ").replace(FIELD_SEP, " ") +
                FIELD_SEP +
                pair.second.take(80).replace(RECENT_SEP, " ").replace(FIELD_SEP, " ")
        }

    private fun decode(raw: String?): List<Pair<String, String>> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(RECENT_SEP).mapNotNull { chunk ->
            val parts = chunk.split(FIELD_SEP)
            if (parts.size >= 2) parts[0] to parts[1] else null
        }
    }
}
