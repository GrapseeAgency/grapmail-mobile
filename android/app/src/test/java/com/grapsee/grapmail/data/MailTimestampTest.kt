package com.grapsee.grapmail.data

import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MailTimestampTest {
    @Test
    fun offsetAndUtcRepresentTheSameInstant() {
        val expected = Instant.parse("2026-11-21T18:30:45.125456Z")
        assertEquals(expected, parseMailInstant("2026-11-22T00:30:45.125456+06:00"))
        assertEquals(expected, parseMailInstant("2026-11-21T18:30:45.125456Z"))
        assertEquals(expected, parseMailInstant("2026-11-21 18:30:45.125456"))
    }

    @Test
    fun utcCanFallOnTheNextDayInDhaka() {
        val local = parseMailInstant("2026-11-21T18:30:00Z")!!.atZone(ZoneId.of("Asia/Dhaka"))
        assertEquals(22, local.dayOfMonth)
        assertEquals(0, local.hour)
        assertEquals(30, local.minute)
    }

    @Test
    fun invalidDatesStayUnknown() {
        assertNull(parseMailInstant("2026-02-30T18:30:00Z"))
        assertNull(parseMailInstant("2026-11-21T18:30:00Z extra"))
        assertNull(parseMailInstant(null))
    }
}
