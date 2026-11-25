package com.grapsee.grapmail.data

import com.grapsee.grapmail.logic.OutboxRetryPolicy
import java.io.IOException
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Request and response shapes of the real client against a local server,
 * through the one test seam [Api.baseOverrideForTests]. Needs
 * `unitTests.isReturnDefaultValues = true` (android.util.Log in ApiException).
 */
class ApiTest {

    private lateinit var server: MockWebServer

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        Api.baseOverrideForTests = server.url("/api").toString().trimEnd('/')
        // DeviceId.value is lateinit and set from a Context in the app.
        DeviceId::class.java.getDeclaredField("value").apply { isAccessible = true }.set(null, "test-device-id")
        SessionEvents.resetExpired()
    }

    @After
    fun tearDown() {
        Api.baseOverrideForTests = null
        server.shutdown()
    }

    private fun json(code: Int, body: String) =
        MockResponse().setResponseCode(code).setHeader("Content-Type", "application/json").setBody(body)

    // ── request shape ───────────────────────────────────────────────────────

    @Test
    fun authenticatedCallCarriesBearerDeviceIdAndAppUserAgent() = runBlocking {
        server.enqueue(json(200, """{"success":true,"profile":{"id":"u1","address":"ada@grapmail.com",
            "settings":{"notifications":{"mode":"priority","vipSenders":["boss@example.com"]}}}}"""))
        val r = Api.profile("tok-1")
        assertEquals("priority", r.profile?.settings?.notifications?.mode)
        assertEquals(listOf("boss@example.com"), r.profile?.settings?.notifications?.vipSenders)
        assertEquals("missing keys take the defaults", "22:00", r.profile?.settings?.notifications?.quietHours?.start)

        val req = server.takeRequest()
        assertEquals("GET", req.method)
        assertEquals("/api/grapsee/auth/account/profile", req.path)
        assertEquals("Bearer tok-1", req.getHeader("Authorization"))
        assertEquals("test-device-id", req.getHeader("x-device-id"))
        assertTrue("not okhttp's default UA: ${req.getHeader("User-Agent")}", req.getHeader("User-Agent")!!.startsWith("GrapMail/"))
        assertNull("no DPoP proof on a non-token route", req.getHeader("DPoP"))
    }

    @Test
    fun inboxSendsTheSearchTermOnlyFromThreeCharacters() = runBlocking {
        repeat(2) { server.enqueue(json(200, """{"success":true,"messages":[]}""")) }
        Api.mailInbox("t", MailBox.Inbox, page = 2, pageSize = 50, q = "ab")
        assertEquals("/api/grapsee/grapmail/inbox?box=inbox&page=2&pageSize=50", server.takeRequest().path)
        Api.mailInbox("t", MailBox.Archive, q = "  invoices  ")
        assertEquals("/api/grapsee/grapmail/inbox?box=archive&page=1&pageSize=25&q=invoices", server.takeRequest().path)
    }

    @Test
    fun profilePatchIsSparseOnTheWire() = runBlocking {
        server.enqueue(json(200, """{"success":true}"""))
        Api.patchProfile("t", ProfilePatch(displayName = "Ada"))
        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertEquals("only the touched key is sent", """{"displayName":"Ada"}""", req.body.readUtf8())
    }

    @Test
    fun notificationSettingsAreSentWholeInsideASparsePatch() = runBlocking {
        server.enqueue(json(200, """{"success":true}"""))
        Api.patchProfile("t", ProfilePatch(settings = ProfileSettingsPatch(notifications = NotificationSettingsDto(mode = "none"))))
        val body = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
        assertEquals(setOf("settings"), body.keys)
        val settings = body["settings"]!!.jsonObject
        assertEquals(setOf("notifications"), settings.keys)
        val n = settings["notifications"]!!.jsonObject
        assertEquals(setOf("mode", "vipSenders", "mutedThreads", "watchedThreads", "quietHours", "showPreview"), n.keys)
        assertEquals("none", n["mode"]!!.jsonPrimitive.content)
        assertEquals(setOf("enabled", "start", "end", "timeZone", "allowVip"), n["quietHours"]!!.jsonObject.keys)
    }

    // ── error envelope → ApiException ───────────────────────────────────────

    @Test
    fun errorEnvelopeBecomesApiExceptionInTheAppsWording() {
        server.enqueue(json(403, """{"code":"SOMETHING_INTERNAL","message":"stack trace: x.y.z"}"""))
        val e = assertThrows(ApiException::class.java) { runBlocking { Api.profile("t") } }
        assertEquals(403, e.status)
        assertEquals("SOMETHING_INTERNAL", e.code)
        assertNotEquals("a raw server sentence is not shown for unknown codes", "stack trace: x.y.z", e.message)
        assertEquals("This action is unavailable for your account.", e.message)
    }

    @Test
    fun serverWordedCodesKeepTheServersSentence() {
        val sentence = "You can send 500 messages a day. Try again after midnight UTC."
        server.enqueue(json(429, """{"code":"DAILY_LIMIT_REACHED","message":"$sentence"}"""))
        val e = assertThrows(ApiException::class.java) { runBlocking { Api.profile("t") } }
        assertEquals(429, e.status)
        assertEquals(sentence, e.message)
    }

    @Test
    fun errorKeyIsReadWhenThereIsNoMessageAndNoCode() {
        server.enqueue(json(404, """{"error":"Not found"}"""))
        val e = assertThrows(ApiException::class.java) { runBlocking { Api.profile("t") } }
        assertEquals(404, e.status)
        assertNull(e.code)
        assertEquals("This item is no longer available.", e.message)
    }

    @Test
    fun nonJsonErrorBodyStillBecomesAnApiException() {
        server.enqueue(MockResponse().setResponseCode(502).setBody("<html>Bad gateway</html>"))
        val e = assertThrows(ApiException::class.java) { runBlocking { Api.profile("t") } }
        assertEquals(502, e.status)
        assertNull(e.code)
        assertFalse(e.message.isNullOrBlank())
        assertTrue("a 5xx is retried by the Outbox", OutboxRetryPolicy.shouldRetry(e))
    }

    @Test
    fun listingTheServerRefusedIsAnErrorNotAnEmptyBox() {
        server.enqueue(json(200, """{"success":false,"code":"NO_MAILBOX","error":"no mailbox"}"""))
        val e = assertThrows(ApiException::class.java) { runBlocking { Api.mailInbox("t", MailBox.Inbox) } }
        assertEquals(200, e.status)
        assertEquals("NO_MAILBOX", e.code)
        assertEquals("Your GrapMail inbox is not ready yet.", e.message)
    }

    // ── 401 handling ────────────────────────────────────────────────────────

    @Test
    fun authenticated401SignalsTheDeadSessionOnceAndIsNotRetriedWithoutAContext() {
        server.enqueue(json(401, """{"error":"Invalid token"}"""))
        val e = assertThrows(ApiException::class.java) { runBlocking { Api.profile("tok-dead") } }
        assertEquals(401, e.status)
        assertEquals(listOf("tok-dead"), SessionEvents.expired.replayCache)
        assertEquals("no refresh attempt is possible without the app context", 1, server.requestCount)
    }

    @Test
    fun anonymous401DoesNotSignOutAnybody() {
        server.enqueue(json(401, """{"code":"INVALID_CREDENTIALS"}"""))
        val e = assertThrows(ApiException::class.java) { runBlocking { Api.appConfig() } }
        assertEquals(401, e.status)
        assertTrue(SessionEvents.expired.replayCache.isEmpty())
    }

    // ── transport failures ──────────────────────────────────────────────────

    @Test
    fun noConnectionIsAnIOExceptionTheOutboxRetries() {
        val dead = MockWebServer().apply { start() }
        val url = dead.url("/api").toString().trimEnd('/')
        dead.shutdown()
        Api.baseOverrideForTests = url
        val e = assertThrows(IOException::class.java) { runBlocking { Api.appConfig() } }
        assertEquals("Check your connection and try again.", e.message)
        assertTrue(OutboxRetryPolicy.shouldRetry(e))
    }

    @Test
    fun undecodableSuccessIsAnIOExceptionNotACrash() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("this is not json"))
        assertThrows(IOException::class.java) { runBlocking { Api.appConfig() } }
    }

    @Test
    fun appConfigIsDecodedFromTheMailService() = runBlocking {
        server.enqueue(json(200, """{"success":true,"minVersion":{"android":7,"ios":"1.0"},"killSwitches":{"send":true},"brandNew":1}"""))
        val c = Api.appConfig()
        assertEquals("/api/grapsee/grapmail/app-config", server.takeRequest().path)
        assertTrue(c.success)
        assertEquals(7, c.minVersion.android)
        assertEquals(true, c.killSwitches["send"])
    }

    // ── send idempotency ────────────────────────────────────────────────────

    private fun request(tag: String) = MailSendRequest(
        to = listOf("bob@example.com"), subject = "$tag ${UUID.randomUUID()}", bodyText = "Hello Bob",
    )

    @Test
    fun outboxKeyIsPresentedUnchangedOnEveryRetry() = runBlocking {
        server.enqueue(json(503, """{"error":"busy"}"""))
        server.enqueue(json(200, """{"success":true,"status":"queued","id":"m1"}"""))
        val key = "o-0123456789abcdef0123456789abcdef"
        val req = request("outbox")
        val first = runCatching { Api.mailSend("t", req, idempotencyKey = key) }.exceptionOrNull()
        assertTrue(first is ApiException && OutboxRetryPolicy.shouldRetry(first))
        val ok = Api.mailSend("t", req, idempotencyKey = key)
        assertTrue(OutboxRetryPolicy.accepted(ok.status))

        val a = server.takeRequest()
        val b = server.takeRequest()
        assertEquals("/api/grapsee/grapmail/send", a.path)
        assertEquals(key, a.getHeader("Idempotency-Key"))
        assertEquals(key, b.getHeader("Idempotency-Key"))
        val sent = Json.parseToJsonElement(a.body.readUtf8()).jsonObject
        assertEquals("bob@example.com", sent["to"]!!.jsonArray.single().jsonPrimitive.content)
    }

    @Test
    fun directSendReusesItsKeyAfterA5xxAndForgetsItOnceAnswered() = runBlocking {
        server.enqueue(json(503, "{}"))
        server.enqueue(json(200, """{"success":true,"status":"sent"}"""))
        server.enqueue(json(200, """{"success":true,"status":"sent"}"""))
        val req = request("direct")
        runCatching { Api.mailSend("t", req) }
        Api.mailSend("t", req)
        Api.mailSend("t", req)
        val k1 = server.takeRequest().getHeader("Idempotency-Key")
        val k2 = server.takeRequest().getHeader("Idempotency-Key")
        val k3 = server.takeRequest().getHeader("Idempotency-Key")
        assertEquals("the retry after a lost answer is the same send", k1, k2)
        assertNotEquals("a deliberate resend after an answer is a new send", k2, k3)
    }

    @Test
    fun directSendRefusedWith4xxIsSettledSoTheFixedMessageIsANewSend() = runBlocking {
        server.enqueue(json(400, """{"code":"NO_RECIPIENT"}"""))
        server.enqueue(json(200, """{"success":true,"status":"sent"}"""))
        val req = request("refused")
        val e = runCatching { Api.mailSend("t", req) }.exceptionOrNull()
        assertTrue(e is ApiException && !OutboxRetryPolicy.shouldRetry(e))
        Api.mailSend("t", req)
        assertNotEquals(server.takeRequest().getHeader("Idempotency-Key"), server.takeRequest().getHeader("Idempotency-Key"))
    }
}
