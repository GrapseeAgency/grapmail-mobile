package com.grapsee.grapmail.data

import java.util.UUID
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The `Idempotency-Key` for `POST /send` when the composer sends directly (the
 * Outbox carries its own stored key; see OutboxRetryPolicyTest / ApiTest).
 * SendKeys is process-global, so every test uses its own unique body.
 */
class SendKeysTest {

    private fun body(tag: String) = """{"to":["bob@example.com"],"subject":"$tag ${UUID.randomUUID()}","bodyText":"hi"}"""
    private val t0 = 1_800_000_000_000L
    private val tenMinutes = 10 * 60 * 1000L

    @Test
    fun sameMessageInsideTheWindowReusesItsKey() {
        val b = body("retry")
        val first = SendKeys.keyFor(b, now = t0)
        assertEquals(first, SendKeys.keyFor(b, now = t0 + 1_000))
        assertEquals("the window is inclusive at ten minutes", first, SendKeys.keyFor(b, now = t0 + tenMinutes))
    }

    @Test
    fun keyHasTheAndroidPrefixAndIsOpaque() {
        val key = SendKeys.keyFor(body("format"), now = t0)
        assertTrue(key, Regex("^a-[0-9a-f]{32}$").matches(key))
    }

    @Test
    fun differentMessagesGetDifferentKeys() {
        assertNotEquals(SendKeys.keyFor(body("one"), now = t0), SendKeys.keyFor(body("two"), now = t0))
    }

    @Test
    fun oneCharacterChangesTheMessage() {
        val a = """{"to":["bob@example.com"],"subject":"${UUID.randomUUID()}","bodyText":"hi"}"""
        val b = a.replace("\"hi\"", "\"hi!\"")
        assertNotEquals(SendKeys.keyFor(a, now = t0), SendKeys.keyFor(b, now = t0))
    }

    @Test
    fun settlingForgetsTheKeySoASecondDeliberateSendIsNew() {
        val b = body("settle")
        val first = SendKeys.keyFor(b, now = t0)
        SendKeys.settle(b)
        assertNotEquals(first, SendKeys.keyFor(b, now = t0 + 1))
    }

    @Test
    fun keyExpiresAfterTenMinutes() {
        val b = body("expire")
        val first = SendKeys.keyFor(b, now = t0)
        assertNotEquals(first, SendKeys.keyFor(b, now = t0 + tenMinutes + 1))
    }

    @Test
    fun settlingAnUnknownMessageIsHarmless() {
        SendKeys.settle(body("never-sent"))
    }
}
