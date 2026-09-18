package com.eko.dcvisits.app.util

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/**
 * IST (Asia/Kolkata, fixed +05:30) helpers. Mirrors backend/src/geo.ts
 * `istDateOf` / `istMinutesOfDay` so the app and server agree on which
 * calendar day an event belongs to and on the 21:00 auto-close cutoff.
 */
object Ist {
    val ZONE: ZoneId = ZoneOffset.ofHoursMinutes(5, 30)
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val DISPLAY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm")

    /** Today's IST calendar date as YYYY-MM-DD. */
    fun today(now: Instant = Instant.now()): String = date(now)

    /** The IST calendar date (YYYY-MM-DD) an instant falls on. */
    fun date(instant: Instant): String =
        ZonedDateTime.ofInstant(instant, ZONE).toLocalDate().format(DATE)

    /** Render a UTC ISO instant as `DD-MM-YYYY HH:mm` IST; em dash when absent/invalid. */
    fun display(iso: String?): String {
        if (iso.isNullOrBlank()) return "—"
        return try {
            ZonedDateTime.ofInstant(Instant.parse(iso), ZONE).format(DISPLAY)
        } catch (_: Exception) {
            "—"
        }
    }

    /** Minutes since IST midnight for an instant (0..1439). */
    fun minutesOfDay(instant: Instant): Int {
        val t = ZonedDateTime.ofInstant(instant, ZONE)
        return t.hour * 60 + t.minute
    }

    /** ISO-8601 UTC instant string, e.g. 2026-09-04T08:30:00.000Z (device wall time). */
    fun nowIso(now: Instant = Instant.now()): String = DateTimeFormatter.ISO_INSTANT.format(now)

    fun parseDateOrNull(s: String?): LocalDate? =
        try { if (s.isNullOrBlank()) null else LocalDate.parse(s) } catch (_: Exception) { null }
}
