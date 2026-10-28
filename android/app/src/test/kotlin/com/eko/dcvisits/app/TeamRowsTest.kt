package com.eko.dcvisits.app

import com.eko.dcvisits.app.data.net.AttendanceRowDto
import com.eko.dcvisits.app.data.net.LiveLocationRowDto
import com.eko.dcvisits.app.data.net.OverviewDcDto
import com.eko.dcvisits.app.data.net.OverviewResponse
import com.eko.dcvisits.app.data.repo.buildTeamRows
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TeamRowsTest {
    private val overview = OverviewResponse(
        date = "2026-10-28",
        assignments_by_dc = listOf(
            OverviewDcDto("a", "Anish", csp_count = 46, attendance = "ON_DUTY", visits_today = 3, km_today = 12.0),
            OverviewDcDto("b", "Balram", csp_count = 31),
            OverviewDcDto("c", "Ajay", csp_count = 48, attendance = "ENDED", visits_today = 5),
        ),
    )
    private val attendance = listOf(
        AttendanceRowDto("a", "Anish", "ON_DUTY", started_at = "2026-10-28T03:42:00Z", km_today = 14.2),
        AttendanceRowDto("c", "Ajay", "ENDED", started_at = "2026-10-28T03:00:00Z", ended_at = "2026-10-28T12:00:00Z", hours_worked = 9.0),
    )
    private val live = listOf(
        LiveLocationRowDto("a", "Anish", on_duty = true, state = "live", lat = 26.63, lng = 85.42, captured_at = "2026-10-28T06:00:00Z"),
    )

    @Test fun `one row per DC, on duty first, then ended, then not started`() {
        val rows = buildTeamRows(overview, attendance, live)
        assertEquals(listOf("Anish", "Ajay", "Balram"), rows.map { it.name })
    }

    @Test fun `attendance times and km come from the attendance board, counts from the overview`() {
        val anish = buildTeamRows(overview, attendance, live).first { it.dcUserId == "a" }
        assertEquals("2026-10-28T03:42:00Z", anish.startedAt)
        assertEquals(14.2, anish.kmToday)
        assertEquals(3, anish.visitsToday)
        assertEquals(46, anish.cspCount)
        assertEquals("live", anish.liveState)
        assertEquals(26.63, anish.lat)
    }

    @Test fun `a DC with no location yet reads no_fix`() {
        val balram = buildTeamRows(overview, attendance, live).first { it.dcUserId == "b" }
        assertEquals("NOT_STARTED", balram.attendance)
        assertEquals("no_fix", balram.liveState)
        assertNull(balram.lat)
    }

    @Test fun `without the overview the attendance board still lists the DCs`() {
        val rows = buildTeamRows(null, attendance, live)
        assertEquals(listOf("Anish", "Ajay"), rows.map { it.name })
        assertNull(rows.first().visitsToday)
    }
}
