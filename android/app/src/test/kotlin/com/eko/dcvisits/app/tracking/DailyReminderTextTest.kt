package com.eko.dcvisits.app.tracking

import com.eko.dcvisits.app.data.repo.DayState
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime
import com.eko.dcvisits.app.util.Ist
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DailyReminderTextTest {
    // The shipped English copy from res/values/strings.xml.
    private val en = TestStrings.lookup()
    private val hi = TestStrings.lookup("values-hi")

    private fun snap(slot: ReminderSlot, day: DayState, assigned: Int = 72, visited: Int = 3, code: String? = "1A850143", m: Double? = 2450.0) =
        ReminderSnapshot(slot, day, assigned, visited, code, m)

    @Test fun `morning before check in asks to check in and lists pending and nearest`() {
        val c = assertNotNull(DailyReminderText.forSlot(snap(ReminderSlot.MORNING, DayState.NOT_STARTED, visited = 0), en))
        assertEquals("Good morning! Time to Check In", c.title)
        assertEquals("Open the app and tap Check In to start your day.", c.summary)
        assertTrue("72 of 72 CSPs still to visit today" in c.details)
        assertTrue("Nearest: 1A850143 · 2.5 km away" in c.details)
    }

    @Test fun `morning already on duty shows the plan instead`() {
        val c = assertNotNull(DailyReminderText.forSlot(snap(ReminderSlot.MORNING, DayState.ON_DUTY), en))
        assertEquals("Today's CSP visits", c.title)
        assertEquals("69 of 72 CSPs still to visit today", c.summary)
        assertTrue("Open Logi to see today's route." in c.details)
    }

    @Test fun `evening on duty reminds to end day with the day's counts`() {
        val c = assertNotNull(DailyReminderText.forSlot(snap(ReminderSlot.EVENING, DayState.ON_DUTY), en))
        assertEquals("Wrapping up for today?", c.title)
        assertEquals("Visits today: 3 · 69 of 72 CSPs still to visit today", c.summary)
        assertTrue("Done for the day? Tap End Day on Home." in c.details)
    }

    @Test fun `no pop-up when there is nothing to nudge`() {
        assertNull(DailyReminderText.forSlot(snap(ReminderSlot.MORNING, DayState.ENDED), en))
        assertNull(DailyReminderText.forSlot(snap(ReminderSlot.EVENING, DayState.ENDED), en))
        assertNull(DailyReminderText.forSlot(snap(ReminderSlot.EVENING, DayState.NOT_STARTED), en))
        assertNull(DailyReminderText.forSlot(snap(ReminderSlot.MORNING, DayState.ON_DUTY, visited = 72), en))
    }

    @Test fun `no CSPs assigned still nudges check in but never claims a count`() {
        val c = assertNotNull(DailyReminderText.forSlot(snap(ReminderSlot.MORNING, DayState.NOT_STARTED, assigned = 0, visited = 0), en))
        assertEquals(listOf("Open the app and tap Check In to start your day."), c.details)
    }

    @Test fun `unknown location leaves out the nearest line`() {
        val c = assertNotNull(DailyReminderText.forSlot(snap(ReminderSlot.MORNING, DayState.NOT_STARTED, code = null, m = null), en))
        assertTrue(c.details.none { it.startsWith("Nearest") })
    }

    @Test fun `hindi copy resolves`() {
        val c = assertNotNull(DailyReminderText.forSlot(snap(ReminderSlot.MORNING, DayState.NOT_STARTED, visited = 0), hi))
        assertEquals("सुप्रभात! चेक इन का समय", c.title)
        assertTrue("आज 72 में से 72 CSP पर जाना बाकी है" in c.details)
    }

    @Test fun `next reminder is later today or tomorrow at the IST time`() {
        val at8 = ZonedDateTime.of(2026, 10, 28, 8, 0, 0, 0, Ist.ZONE)
        assertEquals(Duration.ofHours(1), DailyReminderWorker.delayUntil(LocalTime.of(9, 0), at8))
        val at10 = ZonedDateTime.of(2026, 10, 28, 10, 0, 0, 0, Ist.ZONE)
        assertEquals(Duration.ofHours(23), DailyReminderWorker.delayUntil(LocalTime.of(9, 0), at10))
    }
}
