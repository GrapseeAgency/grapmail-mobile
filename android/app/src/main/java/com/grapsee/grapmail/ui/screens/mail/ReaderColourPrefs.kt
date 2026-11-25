package com.grapsee.grapmail.ui.screens.mail

import android.content.Context
import androidx.compose.runtime.mutableStateMapOf

/**
 * "Show original colours" — the reader's per-sender memory of whether a dark
 * reader should leave a sender's HTML design as the sender drew it instead of
 * redrawing it dark (ui/components/EmailDarkening.kt).
 *
 * ── WHAT IS KEPT, AND WHERE ──────────────────────────────────────────────
 * Only the exception is stored: a sender whose mail the person asked to see
 * in its original colours. Dark colours are the default, so choosing them
 * again removes the entry instead of writing a second state. The store is
 * this device's own SharedPreferences (the pattern MailDisplayPrefs,
 * NotificationPolicy and the other local stores use); nothing is sent to the
 * server, and the choice does not follow the person to another device.
 *
 * The sender address is never written in the clear: the key is a SHA-256 of
 * the trimmed, lower-cased address, so the file is not a list of who writes
 * to this person. The value is the time of the choice, which lets the store
 * forget the oldest entries once it holds [MAX_ENTRIES].
 *
 * ── HOW THE SCREEN READS IT ──────────────────────────────────────────────
 * [showsOriginal] is safe to call from composition: the in-memory map is
 * snapshot state, so every open card from the same sender flips together
 * when one of them changes, and a read never writes state. The first read
 * per sender goes to SharedPreferences, which is memory-backed after the
 * file's first load.
 */
internal object ReaderColourPrefs {
    private const val PREFS = "grapmail-reader-colours"
    private const val KEY_PREFIX = "o:"
    const val MAX_ENTRIES: Int = 500

    /** Sender key -> shows original colours. Holds only what this process has written. */
    private val written = mutableStateMapOf<String, Boolean>()

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    /** The stored key for [address], or null when there is no usable address. */
    fun keyOf(address: String?): String? {
        val normal = address?.trim()?.lowercase()?.takeIf { it.isNotEmpty() && it.contains('@') } ?: return null
        val digest = java.security.MessageDigest.getInstance("SHA-256").digest(normal.toByteArray(Charsets.UTF_8))
        val hex = StringBuilder(KEY_PREFIX.length + 64).append(KEY_PREFIX)
        for (b in digest) {
            val v = b.toInt() and 0xFF
            hex.append(HEX[v ushr 4]).append(HEX[v and 0x0F])
        }
        return hex.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()

    /** Whether mail from [address] should be shown in its original colours in a dark reader. */
    fun showsOriginal(context: Context, address: String?): Boolean {
        val key = keyOf(address) ?: return false
        written[key]?.let { return it }
        return prefs(context).contains(key)
    }

    /** Remember the choice for [address]. A sender without an address is not remembered. */
    fun setShowsOriginal(context: Context, address: String?, original: Boolean) {
        val key = keyOf(address) ?: return
        written[key] = original
        val store = prefs(context)
        val edit = store.edit()
        if (original) {
            edit.putLong(key, System.currentTimeMillis())
            // Forget the oldest choices beyond the cap, so the file cannot grow forever.
            val entries = store.all.filterKeys { it.startsWith(KEY_PREFIX) && it != key }
            val over = entries.size + 1 - MAX_ENTRIES
            if (over > 0) {
                entries.entries.sortedBy { (it.value as? Long) ?: 0L }.take(over).forEach { edit.remove(it.key) }
            }
        } else {
            edit.remove(key)
        }
        edit.apply()
    }

    /** Forget every choice (sign-out / account removal; see the hand-off note). */
    fun clear(context: Context) {
        written.clear()
        prefs(context).edit().clear().apply()
    }
}
