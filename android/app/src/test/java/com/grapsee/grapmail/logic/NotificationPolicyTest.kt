package com.grapsee.grapmail.logic

import com.grapsee.grapmail.data.NotificationSettingsDto
import com.grapsee.grapmail.data.QuietHoursDto
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device copy of the server's decideNotification (backend
 * lib/notification-policy.ts). Only the pure functions are exercised here; the
 * SharedPreferences cache is a thin wrapper around them.
 */
class NotificationPolicyTest {

    private val defaults = NotificationSettingsDto()

    // 2026-11-23T17:30Z is 23:30 in Dhaka (UTC+6, no DST); 03:00Z is 09:00 there.
    private val lateEveningDhaka = Instant.parse("2026-11-23T17:30:00Z").toEpochMilli()
    private val morningDhaka = Instant.parse("2026-11-23T03:00:00Z").toEpochMilli()

    private fun quiet(start: String = "22:00", end: String = "07:00", zone: String = "Asia/Dhaka", allowVip: Boolean = true) =
        QuietHoursDto(enabled = true, start = start, end = end, timeZone = zone, allowVip = allowVip)

    private fun decide(s: NotificationSettingsDto, from: String?, thread: String? = null, now: Long = morningDhaka) =
        NotificationPolicy.decide(s, from, thread, now)

    @Test
    fun defaultsAlertForEverythingWithAPreview() {
        assertEquals(NotificationPolicy.Decision(notify = true, generic = false), decide(defaults, "a@example.com", "t1"))
    }

    @Test
    fun previewOffMakesTheAlertGenericButStillAlerts() {
        val d = decide(defaults.copy(showPreview = false), "a@example.com")
        assertTrue(d.notify)
        assertTrue(d.generic)
    }

    @Test
    fun securityMailAlwaysAlertsWhateverTheRules() {
        val strict = defaults.copy(
            mode = "none",
            mutedThreads = listOf("t1"),
            quietHours = quiet(allowVip = false),
            showPreview = false,
        )
        val d = decide(strict, "  Security@Grapsee.com ", "t1", now = lateEveningDhaka)
        assertTrue(d.notify)
        assertTrue("preview rule still applies", d.generic)
    }

    @Test
    fun mutedThreadNeverAlertsEvenFromAVipOrWhenAlsoWatched() {
        val s = defaults.copy(mutedThreads = listOf("t1"), watchedThreads = listOf("t1"), vipSenders = listOf("boss@example.com"))
        assertFalse(decide(s, "boss@example.com", "t1").notify)
        assertTrue("other threads are unaffected", decide(s, "boss@example.com", "t2").notify)
    }

    @Test
    fun blankThreadIdIsNeverTreatedAsMutedOrWatched() {
        val s = defaults.copy(mode = "none", mutedThreads = listOf(""), watchedThreads = listOf(""))
        assertFalse(decide(s, "a@example.com", "").notify)
        assertTrue(decide(defaults.copy(mutedThreads = listOf("")), "a@example.com", null).notify)
    }

    @Test
    fun modeNoneAlertsOnlyForWatchedThreads() {
        val s = defaults.copy(mode = "none", watchedThreads = listOf("w"), vipSenders = listOf("boss@example.com"))
        assertFalse(decide(s, "a@example.com", "x").notify)
        assertFalse("VIP is not enough in none mode", decide(s, "boss@example.com", "x").notify)
        assertTrue(decide(s, "a@example.com", "w").notify)
    }

    @Test
    fun priorityModeAlertsForVipAddressesDomainsAndWatchedThreads() {
        val s = defaults.copy(mode = "priority", vipSenders = listOf("boss@example.com", "partner.org"), watchedThreads = listOf("w"))
        assertTrue(decide(s, "Boss@Example.com", "x").notify)
        assertTrue("whole domain", decide(s, "anyone@partner.org", "x").notify)
        assertTrue("subdomain of a VIP domain", decide(s, "ops@mail.partner.org", "x").notify)
        assertFalse("suffix that is not a subdomain", decide(s, "x@notpartner.org", "x").notify)
        assertFalse(decide(s, "stranger@example.com", "x").notify)
        assertTrue(decide(s, "stranger@example.com", "w").notify)
        assertFalse("unknown sender", decide(s, null, "x").notify)
    }

    @Test
    fun isVipNeedsANonBlankSender() {
        val s = defaults.copy(vipSenders = listOf("example.com"))
        assertFalse(NotificationPolicy.isVip(s, null))
        assertFalse(NotificationPolicy.isVip(s, "   "))
        assertTrue(NotificationPolicy.isVip(s, " a@EXAMPLE.com "))
    }

    @Test
    fun quietHoursCrossMidnightInTheSavedTimeZone() {
        val s = defaults.copy(quietHours = quiet())
        assertTrue(NotificationPolicy.inQuietHours(s, lateEveningDhaka))
        assertFalse(NotificationPolicy.inQuietHours(s, morningDhaka))
        // 00:59 Dhaka is inside, 07:00 sharp is the end (exclusive).
        assertTrue(NotificationPolicy.inQuietHours(s, Instant.parse("2026-11-23T18:59:00Z").toEpochMilli()))
        assertFalse(NotificationPolicy.inQuietHours(s, Instant.parse("2026-11-24T01:00:00Z").toEpochMilli()))
        // 22:00 sharp is the start (inclusive).
        assertTrue(NotificationPolicy.inQuietHours(s, Instant.parse("2026-11-23T16:00:00Z").toEpochMilli()))
    }

    @Test
    fun sameDayWindowIsStartInclusiveEndExclusive() {
        val s = defaults.copy(quietHours = quiet(start = "09:00", end = "17:00", zone = "UTC"))
        assertFalse(NotificationPolicy.inQuietHours(s, Instant.parse("2026-11-23T08:59:00Z").toEpochMilli()))
        assertTrue(NotificationPolicy.inQuietHours(s, Instant.parse("2026-11-23T09:00:00Z").toEpochMilli()))
        assertTrue(NotificationPolicy.inQuietHours(s, Instant.parse("2026-11-23T16:59:00Z").toEpochMilli()))
        assertFalse(NotificationPolicy.inQuietHours(s, Instant.parse("2026-11-23T17:00:00Z").toEpochMilli()))
    }

    @Test
    fun blankOrUnknownZoneFallsBackToUtc() {
        val at = Instant.parse("2026-11-23T23:30:00Z").toEpochMilli()
        assertTrue(NotificationPolicy.inQuietHours(defaults.copy(quietHours = quiet(zone = "")), at))
        assertTrue(NotificationPolicy.inQuietHours(defaults.copy(quietHours = quiet(zone = "Mars/Olympus_Mons")), at))
    }

    @Test
    fun disabledOrMalformedWindowIsNeverQuiet() {
        assertFalse(NotificationPolicy.inQuietHours(defaults.copy(quietHours = quiet().copy(enabled = false)), lateEveningDhaka))
        for ((start, end) in listOf("24:00" to "07:00", "22:00" to "7:00", "ab:cd" to "07:00", "22:60" to "07:00", "" to "")) {
            val s = defaults.copy(quietHours = quiet(start = start, end = end))
            assertFalse("$start-$end is not a window", NotificationPolicy.inQuietHours(s, lateEveningDhaka))
        }
    }

    @Test
    fun quietHoursHoldBackOrdinaryMailButLetVipThroughWhenAllowed() {
        val s = defaults.copy(quietHours = quiet(), vipSenders = listOf("boss@example.com"), watchedThreads = listOf("w"))
        assertFalse(decide(s, "a@example.com", "x", now = lateEveningDhaka).notify)
        assertTrue(decide(s, "boss@example.com", "x", now = lateEveningDhaka).notify)
        assertTrue("watched counts as priority", decide(s, "a@example.com", "w", now = lateEveningDhaka).notify)
        val noVip = s.copy(quietHours = quiet(allowVip = false))
        assertFalse(decide(noVip, "boss@example.com", "x", now = lateEveningDhaka).notify)
        assertTrue("outside the window everything alerts", decide(noVip, "a@example.com", "x", now = morningDhaka).notify)
    }
}
