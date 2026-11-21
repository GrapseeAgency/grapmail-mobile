package com.grapsee.grapmail.data

import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeParseException

/** Older database timestamps without an offset are serialized in UTC. */
internal fun parseMailInstant(value: String?): Instant? {
    val iso = value?.trim()?.replace(' ', 'T')?.takeIf { it.isNotEmpty() } ?: return null
    return try {
        OffsetDateTime.parse(iso).toInstant()
    } catch (_: DateTimeParseException) {
        try {
            LocalDateTime.parse(iso).toInstant(ZoneOffset.UTC)
        } catch (_: DateTimeParseException) {
            null
        }
    }
}
