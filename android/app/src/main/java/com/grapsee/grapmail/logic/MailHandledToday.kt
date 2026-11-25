package com.grapsee.grapmail.logic

import android.content.Context
import com.grapsee.grapmail.data.MailAction
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * "You handled N emails today." — counted here, on this device, per account
 * and per local calendar day, for the Inbox Zero moment (InboxZero.kt).
 *
 * What counts: a message the server ACCEPTED an archive, trash, spam or
 * snooze for (it left the inbox), and a permanent delete. Marking read alone
 * does not move mail, so it does not count. Each message counts once per day
 * however often it is filed (the store is a set of ids), and an Undo that the
 * server accepted takes the ids back out, so "handled" never includes mail
 * that came straight back.
 *
 * Written by MailboxViewModel after each accepted write — the same place the
 * haptics and the undo banner are raised — so the reader's archive counts
 * too. Nothing leaves the device; the ids are only ever compared, never shown.
 */
object MailHandledToday {
    private const val PREFS = "grapmail-handled-today"
    private const val MAX_IDS = 4_000

    private val _changes = MutableStateFlow(0)
    /** Bumped on every change, so a screen showing the count can re-read it. */
    val changes: StateFlow<Int> = _changes.asStateFlow()

    /** Whether an accepted [action] takes a message out of the inbox. */
    fun counts(action: MailAction): Boolean = when (action) {
        MailAction.Archive, MailAction.Trash, MailAction.Spam, MailAction.Snooze -> true
        else -> false
    }

    fun record(context: Context, account: String?, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val acct = key(account) ?: return
        synchronized(this) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val today = LocalDate.now().toString()
            val current = if (prefs.getString(dayKey(acct), null) == today) readIds(prefs.getString(idsKey(acct), null))
                else LinkedHashSet()
            val before = current.size
            ids.forEach { if (it.isNotBlank() && !it.contains(' ')) current.add(it) }
            if (current.size == before && prefs.getString(dayKey(acct), null) == today) return
            prefs.edit()
                .putString(dayKey(acct), today)
                .putString(idsKey(acct), current.toList().takeLast(MAX_IDS).joinToString(" "))
                .apply()
        }
        _changes.value = _changes.value + 1
    }

    fun forget(context: Context, account: String?, ids: Collection<String>) {
        if (ids.isEmpty()) return
        val acct = key(account) ?: return
        synchronized(this) {
            val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            if (prefs.getString(dayKey(acct), null) != LocalDate.now().toString()) return
            val current = readIds(prefs.getString(idsKey(acct), null))
            if (!current.removeAll(ids.toSet())) return
            prefs.edit().putString(idsKey(acct), current.joinToString(" ")).apply()
        }
        _changes.value = _changes.value + 1
    }

    /** Messages handled today for [account]; 0 on a new day or an unknown account. */
    fun count(context: Context, account: String?): Int {
        val acct = key(account) ?: return 0
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        if (prefs.getString(dayKey(acct), null) != LocalDate.now().toString()) return 0
        return readIds(prefs.getString(idsKey(acct), null)).size
    }

    /**
     * True exactly once per account per day: the first Inbox Zero of the day
     * may celebrate (subtle confetti), every later one is calm.
     */
    fun takeCelebration(context: Context, account: String?): Boolean {
        val acct = key(account) ?: return false
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val today = LocalDate.now().toString()
        if (prefs.getString(celebratedKey(acct), null) == today) return false
        prefs.edit().putString(celebratedKey(acct), today).apply()
        return true
    }

    private fun key(account: String?): String? =
        account?.trim()?.lowercase(Locale.ROOT)?.takeIf { it.isNotEmpty() }

    private fun readIds(raw: String?): LinkedHashSet<String> =
        raw.orEmpty().split(' ').filterTo(LinkedHashSet()) { it.isNotEmpty() }

    private fun dayKey(acct: String) = "day:$acct"
    private fun idsKey(acct: String) = "ids:$acct"
    private fun celebratedKey(acct: String) = "celebrated:$acct"
}
