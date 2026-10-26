package com.eko.dcvisits.app.tracking

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DutyNotificationTextTest {
    // The shipped English copy from res/values/strings.xml.
    private val en = TestStrings.lookup()
    // 09:12 IST = 03:42 UTC
    private val since = Instant.parse("2026-10-10T03:42:00Z")
    private val now = Instant.parse("2026-10-10T07:02:00Z") // 12:32 IST → 3h 20m on duty

    private fun snap(gps: GpsStatus = GpsStatus.OK, pending: Int = 0, visits: Int? = 2, csps: Int? = 9) = DutySnapshot(
        gps = gps, onDutySince = since, now = now, visitsToday = visits, cspsAssigned = csps,
        lastFixAt = Instant.parse("2026-10-10T07:01:20Z"), pendingSync = pending, batterySaver = false,
    )

    @Test fun `on duty shows elapsed time, visits, gps freshness and sync state`() {
        val c = DutyNotificationText.forDuty(snap(), en)
        assertEquals("On duty · 3h 20m", c.title)
        assertTrue("On duty since 09:12 IST" in c.details)
        assertTrue("Visits today: 2 of 9 CSPs" in c.details)
        assertTrue("GPS live · last fix 40s ago" in c.details)
        assertTrue("All synced" in c.details)
        assertEquals("Visits today: 2 of 9 CSPs · GPS live · All synced", c.summary)
    }

    @Test fun `pending sync items are counted`() {
        assertTrue("3 waiting to sync" in DutyNotificationText.forDuty(snap(pending = 3), en).details)
    }

    @Test fun `gps off turns the ongoing notification into a warning`() {
        val c = DutyNotificationText.forDuty(snap(GpsStatus.GPS_OFF), en)
        assertEquals("GPS is off — route not recording", c.title)
        assertEquals("Turn on Location to keep recording your visits", c.summary)
    }

    @Test fun `missing permission is called out`() {
        assertEquals("Location permission needed", DutyNotificationText.forDuty(snap(GpsStatus.NO_PERMISSION), en).title)
    }

    @Test fun `unknown visit count is omitted rather than shown as zero`() {
        val c = DutyNotificationText.forDuty(snap(visits = null), en)
        assertTrue(c.details.none { it.startsWith("Visits today") })
    }

    @Test fun `alert copy explains the consequence and the fix`() {
        val off = DutyNotificationText.gpsAlert(GpsStatus.GPS_OFF, en)
        assertEquals("GPS is off", off.title)
        assertTrue("not being recorded" in off.summary)
        assertTrue("permission" in DutyNotificationText.gpsAlert(GpsStatus.NO_PERMISSION, en).title.lowercase())
    }

    @Test fun `duration and ago formatting`() {
        assertEquals("45m", DutyNotificationText.duration(java.time.Duration.ofMinutes(45), en))
        assertEquals("1h 5m", DutyNotificationText.duration(java.time.Duration.ofMinutes(65), en))
        assertEquals("5m ago", DutyNotificationText.ago(java.time.Duration.ofSeconds(300), en))
    }

    @Test fun `hindi copy resolves with the same arguments`() {
        val hi = TestStrings.lookup("values-hi")
        val c = DutyNotificationText.forDuty(snap(pending = 3), hi)
        assertEquals("ड्यूटी पर · 3 घंटे 20 मिनट", c.title)
        assertTrue("आज की विज़िट: 9 में से 2 CSP" in c.details)
        assertTrue("09:12 IST से ड्यूटी पर" in c.details)
        assertTrue("3 सिंक होना बाकी" in c.details)
        assertEquals("GPS बंद है", DutyNotificationText.gpsAlert(GpsStatus.GPS_OFF, hi).title)
    }
}
