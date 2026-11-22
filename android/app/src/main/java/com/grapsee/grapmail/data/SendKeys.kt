package com.grapsee.grapmail.data

import java.security.MessageDigest
import java.util.UUID

/**
 * The `Idempotency-Key` for `POST /send`.
 *
 * One key per message, reused only when that exact message (same recipients,
 * subject, body, attachments and reply target) is sent again within ten
 * minutes without an answer having come back: the timeout-then-retry case,
 * where the first send may already have gone out. The server then replays the
 * first answer instead of sending a second email.
 *
 * As soon as the server answers, the key is forgotten, so deliberately sending
 * the same text again later is a new send. Kept in memory only: a key never
 * needs to outlive the process that may retry with it.
 */
object SendKeys {
    private const val WINDOW_MS = 10 * 60 * 1000L
    private class Entry(val key: String, val at: Long)
    private val keys = HashMap<String, Entry>()

    private fun digest(body: String): String =
        MessageDigest.getInstance("SHA-256").digest(body.toByteArray())
            .joinToString("") { "%02x".format(it) }

    @Synchronized
    fun keyFor(body: String, now: Long = System.currentTimeMillis()): String {
        keys.entries.removeAll { now - it.value.at > WINDOW_MS }
        val id = digest(body)
        keys[id]?.let { return it.key }
        val key = "a-" + UUID.randomUUID().toString().replace("-", "")
        keys[id] = Entry(key, now)
        return key
    }

    /** The server answered this message; the next send of it is a new send. */
    @Synchronized
    fun settle(body: String) {
        keys.remove(digest(body))
    }
}
