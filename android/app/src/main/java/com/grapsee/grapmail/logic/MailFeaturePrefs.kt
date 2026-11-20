package com.grapsee.grapmail.logic

import android.content.Context
import com.grapsee.grapmail.features.TemplateEngine
import java.util.Locale

/** One user-managed canned response (F11 templates & snippets). */
data class MailTemplate(
    val name: String,
    val body: String,
    val builtin: Boolean = false,
)

/**
 * GrapMail feature preferences — SharedPreferences-backed switches and local
 * stores introduced by the 2026-11 feature wave. No new dependencies.
 *
 * F16 tracker shield, F23 QR warnings, F24 read receipts (opt-in, off by
 * default), F12 follow-up reminders (awaiting-reply bookkeeping), F13 priority
 * senders + screener lists, F11 templates.
 */
class MailFeaturePrefs(context: Context) {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    var smartFeaturesEnabled: Boolean
        get() = prefs.getBoolean(K_SMART, true)
        set(value) { prefs.edit().putBoolean(K_SMART, value).apply() }

    var trackerShieldEnabled: Boolean
        get() = prefs.getBoolean(K_SHIELD, true)
        set(value) { prefs.edit().putBoolean(K_SHIELD, value).apply() }

    var allowRemoteImages: Boolean
        get() = prefs.getBoolean(K_REMOTE_IMAGES, false)
        set(value) { prefs.edit().putBoolean(K_REMOTE_IMAGES, value).apply() }

    var qrWarningsEnabled: Boolean
        get() = prefs.getBoolean(K_QR, true)
        set(value) { prefs.edit().putBoolean(K_QR, value).apply() }

    var readReceiptsEnabled: Boolean
        get() = prefs.getBoolean(K_RECEIPTS, false)
        set(value) { prefs.edit().putBoolean(K_RECEIPTS, value).apply() }

    var followUpsEnabled: Boolean
        get() = prefs.getBoolean(K_FOLLOWUPS, true)
        set(value) { prefs.edit().putBoolean(K_FOLLOWUPS, value).apply() }

    var followUpDays: Int
        get() = prefs.getInt(K_FOLLOWUP_DAYS, 3).coerceIn(1, 30)
        set(value) { prefs.edit().putInt(K_FOLLOWUP_DAYS, value.coerceIn(1, 30)).apply() }

    // ---- F24 read receipts (opt-in) ---------------------------------------

    fun markReceiptSent(messageId: String) {
        prefs.edit().putBoolean(receiptKey(messageId), true).apply()
    }

    fun wasReceiptSent(messageId: String): Boolean =
        prefs.getBoolean(receiptKey(messageId), false)

    private fun receiptKey(messageId: String): String = "receipt_" + messageId.hashCode()

    // ---- F11 templates -----------------------------------------------------

    fun templates(): List<MailTemplate> {
        val raw = prefs.getString(K_TEMPLATES, null) ?: return emptyList()
        if (raw.isEmpty()) return emptyList()
        val out = ArrayList<MailTemplate>()
        for (chunk in raw.split(ITEM_SEP)) {
            if (chunk.isEmpty()) continue
            val parts = chunk.split(NAME_SEP, limit = 3)
            if (parts.size < 2) continue
            out.add(MailTemplate(parts[0], parts[1], parts.size == 3 && parts[2] == "1"))
        }
        return out
    }

    fun saveTemplates(list: List<MailTemplate>) {
        val encoded = list.joinToString(ITEM_SEP) { t ->
            t.name.replace(NAME_SEP, " ") + NAME_SEP +
                t.body.replace(NAME_SEP, " ") + NAME_SEP +
                (if (t.builtin) "1" else "0")
        }
        prefs.edit().putString(K_TEMPLATES, encoded).apply()
    }

    fun seedDefaultTemplates() {
        if (templates().isNotEmpty()) return
        saveTemplates(DEFAULT_TEMPLATES)
    }

    fun fillTemplate(template: String, firstName: String?, myName: String?): String =
        TemplateEngine.expand(
            template,
            mapOf("first_name" to (firstName ?: ""), "my_name" to (myName ?: "")),
        )

    // ---- F13 priority senders / screener ----------------------------------

    fun prioritySenders(): Set<String> =
        prefs.getStringSet(K_PRIORITY, emptySet())?.toSet() ?: emptySet()

    fun setPrioritySender(address: String, on: Boolean) {
        val set = prioritySenders().toMutableSet()
        val key = normalize(address)
        if (on) set.add(key) else set.remove(key)
        prefs.edit().putStringSet(K_PRIORITY, set).apply()
    }

    fun screenedSenders(): Set<String> =
        prefs.getStringSet(K_SCREENED, emptySet())?.toSet() ?: emptySet()

    fun setScreenedSender(address: String, on: Boolean) {
        val set = screenedSenders().toMutableSet()
        val key = normalize(address)
        if (on) set.add(key) else set.remove(key)
        prefs.edit().putStringSet(K_SCREENED, set).apply()
    }

    // ---- F12 follow-up reminders ------------------------------------------

    fun markAwaitingReply(threadKey: String, title: String = "", atMillis: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putLong(awaitKey(threadKey), atMillis)
            .putString(awaitTitleKey(threadKey), title)
            .apply()
    }

    fun clearAwaitingReply(threadKey: String) {
        prefs.edit()
            .remove(awaitKey(threadKey))
            .remove(awaitTitleKey(threadKey))
            .apply()
    }

    fun awaitingSince(threadKey: String): Long? =
        if (prefs.contains(awaitKey(threadKey))) prefs.getLong(awaitKey(threadKey), 0L) else null

    /** (threadKey, display title) for subject threads older than [days], oldest first. */
    fun overdueAwaitingReplies(days: Int): List<Pair<String, String>> {
        val cutoff = System.currentTimeMillis() - days.toLong() * 24L * 60L * 60L * 1000L
        val out = ArrayList<Pair<String, String>>()
        for (entry in prefs.all) {
            val key = entry.key
            if (!key.startsWith(AWAIT_PREFIX)) continue
            if (key.startsWith(AWAIT_TITLE_PREFIX)) continue
            val at = (entry.value as? Long) ?: continue
            if (at < cutoff) {
                val threadKey = key.substring(AWAIT_PREFIX.length)
                val title = prefs.getString(awaitTitleKey(threadKey), "") ?: ""
                out.add(threadKey to title)
            }
        }
        return out.sortedBy { it.first }
    }

    private fun awaitKey(threadKey: String) = AWAIT_PREFIX + threadKey

    private fun awaitTitleKey(threadKey: String) = AWAIT_TITLE_PREFIX + threadKey

    private fun normalize(address: String) = address.trim().lowercase(Locale.ROOT)

    companion object {
        private const val PREFS_NAME = "grapmail_features"
        private const val K_SMART = "smart_features"
        private const val K_SHIELD = "tracker_shield"
        private const val K_REMOTE_IMAGES = "allow_remote_images"
        private const val K_QR = "qr_warnings"
        private const val K_RECEIPTS = "read_receipts"
        private const val K_FOLLOWUPS = "follow_ups"
        private const val K_FOLLOWUP_DAYS = "follow_up_days"
        private const val K_TEMPLATES = "templates"
        private const val K_PRIORITY = "priority_senders"
        private const val K_SCREENED = "screened_senders"
        private const val AWAIT_PREFIX = "await_"
        private const val AWAIT_TITLE_PREFIX = "awaitt_"

        // F09/F10 bridge: a smart-reply chip stages its suggestion here; the
        // composer consumes it right after opening (60-second freshness window
        // so a cancelled compose cannot leak text into a much later draft).
        private val stagedInsert: java.util.concurrent.atomic.AtomicReference<String?> =
            java.util.concurrent.atomic.AtomicReference(null)
        private val stagedInsertAt: java.util.concurrent.atomic.AtomicLong =
            java.util.concurrent.atomic.AtomicLong(0L)

        fun stageComposeInsert(text: String) {
            stagedInsert.set(text)
            stagedInsertAt.set(System.currentTimeMillis())
        }

        fun consumeStagedComposeInsert(): String? {
            val age = System.currentTimeMillis() - stagedInsertAt.get()
            if (age > 60_000L) return null
            return stagedInsert.getAndSet(null)
        }
        private const val NAME_SEP = "\u0001"
        private const val ITEM_SEP = "\u0002"

        private val DEFAULT_TEMPLATES = listOf(
            MailTemplate(
                "Quick thanks",
                "Hi {first_name},\n\nThanks so much for your note — I appreciate it.\n\nBest,\n{my_name}",
                true,
            ),
            MailTemplate(
                "Meeting follow-up",
                "Hi {first_name},\n\nThanks for your time earlier. I wanted to follow up on the points we discussed — happy to dig into any of them further.\n\nBest,\n{my_name}",
                true,
            ),
            MailTemplate(
                "Intro",
                "Hi {first_name},\n\nGood to connect! I am reaching out to introduce myself — looking forward to working together.\n\nBest,\n{my_name}",
                true,
            ),
            MailTemplate(
                "Checking in",
                "Hi {first_name},\n\nJust checking in — any update on this on your side? No rush if you are still working through it.\n\nBest,\n{my_name}",
                true,
            ),
        )
    }
}
