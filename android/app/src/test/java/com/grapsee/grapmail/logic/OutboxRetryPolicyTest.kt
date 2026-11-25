package com.grapsee.grapmail.logic

import com.grapsee.grapmail.data.ApiException
import com.grapsee.grapmail.data.MailSendRequest
import com.grapsee.grapmail.data.Outbox
import com.grapsee.grapmail.data.OutboxItem
import java.io.IOException
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Outbox failure table (OutboxWorker's KDoc):
 *
 *   no connection, 5xx, 408, 429   -> retry with exponential backoff
 *   4xx the server decided on       -> give up, tell the person
 *   older than Outbox.MAX_AGE_MS    -> give up
 *
 * plus the property that makes a retry safe: the stored Idempotency-Key never
 * changes across attempts or a process restart (the item is re-read from disk).
 */
class OutboxRetryPolicyTest {

    @Test
    fun serverErrorsTimeoutsAndRateLimitsAreRetried() {
        for (status in listOf(500, 502, 503, 504, 599, 408, 429)) {
            assertTrue("HTTP $status must retry", OutboxRetryPolicy.retryableStatus(status))
            assertTrue(OutboxRetryPolicy.shouldRetry(ApiException(status, null, "")))
        }
    }

    @Test
    fun clientErrorsAreFinal() {
        for (status in listOf(400, 401, 403, 404, 409, 413, 422)) {
            assertFalse("HTTP $status must give up", OutboxRetryPolicy.retryableStatus(status))
            assertFalse(OutboxRetryPolicy.shouldRetry(ApiException(status, "NO_RECIPIENT", "")))
        }
    }

    @Test
    fun noConnectionIsRetried() {
        assertTrue(OutboxRetryPolicy.shouldRetry(IOException("Check your connection and try again.")))
        assertTrue(OutboxRetryPolicy.shouldRetry(java.net.SocketTimeoutException("timeout")))
    }

    @Test
    fun onlyTheThreeDeliveryStatesCountAsAccepted() {
        for (s in listOf("sent", "queued", "delivered")) assertTrue(s, OutboxRetryPolicy.accepted(s))
        for (s in listOf("failed", "bounced", "", "SENT", null)) assertFalse("$s", OutboxRetryPolicy.accepted(s))
    }

    @Test
    fun itemsExpireAfterThreeDays() {
        assertEquals(3L * 24 * 60 * 60 * 1000, Outbox.MAX_AGE_MS)
        val created = 1_800_000_000_000L
        assertFalse(OutboxRetryPolicy.expired(created, created))
        assertFalse("exactly the limit is still sent", OutboxRetryPolicy.expired(created, created + Outbox.MAX_AGE_MS))
        assertTrue(OutboxRetryPolicy.expired(created, created + Outbox.MAX_AGE_MS + 1))
    }

    // ── the stored item ─────────────────────────────────────────────────────

    // The Outbox file's own JSON options (Outbox.kt), so this is the on-disk shape.
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val serializer = ListSerializer(OutboxItem.serializer())

    private val item = OutboxItem(
        id = "item-1",
        sessionId = "session-a",
        request = MailSendRequest(to = listOf("bob@example.com"), subject = "Plan", bodyText = "See you at 9."),
        draftId = "draft-7",
        idempotencyKey = "o-0123456789abcdef0123456789abcdef",
        createdAt = 1_800_000_000_000L,
    )

    @Test
    fun idempotencyKeySurvivesEveryRecordedAttempt() {
        var current = item
        repeat(5) { n -> current = current.copy(attempts = current.attempts + 1, lastError = "HTTP 50$n") }
        assertEquals(5, current.attempts)
        assertEquals(item.idempotencyKey, current.idempotencyKey)
        assertEquals(item.id, current.id)
        assertEquals(item.sessionId, current.sessionId)
    }

    @Test
    fun itemRoundTripsThroughTheOutboxFileFormatUnchanged() {
        val again = json.decodeFromString(serializer, json.encodeToString(serializer, listOf(item.copy(attempts = 2, lastError = "offline"))))
        assertEquals(listOf(item.copy(attempts = 2, lastError = "offline")), again)
    }

    @Test
    fun anItemWrittenBeforeAttemptsExistedStillLoads() {
        val old = """[{"id":"x","sessionId":"s","request":{"to":["a@b.c"],"subject":"s","bodyText":"b"},
            "idempotencyKey":"o-k","createdAt":5,"someFutureField":true}]"""
        val loaded = json.decodeFromString(serializer, old).single()
        assertEquals("o-k", loaded.idempotencyKey)
        assertEquals(0, loaded.attempts)
        assertEquals("", loaded.draftId)
        assertNull(loaded.lastError)
    }
}
