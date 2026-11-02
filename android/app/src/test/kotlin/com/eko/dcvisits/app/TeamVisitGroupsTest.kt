package com.eko.dcvisits.app

import com.eko.dcvisits.app.data.net.GeoPointDto
import com.eko.dcvisits.app.data.net.VisitCheckinDto
import com.eko.dcvisits.app.data.net.VisitDto
import com.eko.dcvisits.app.data.repo.groupTeamVisits
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TeamVisitGroupsTest {
    private fun visit(id: String, dc: String, csp: String, at: String, geofence: String = "INSIDE") = VisitDto(
        id = id,
        dc_user_id = dc,
        location_id = csp,
        checkin = VisitCheckinDto(occurred_at = at, fix = GeoPointDto(25.0, 85.0)),
        geofence_result = geofence,
        sync_state = "SYNCED",
    )

    @Test fun `repeat check-ins at one CSP fold into one visit, newest visit first`() {
        val groups = groupTeamVisits(
            listOf(
                visit("a3", "ashutosh", "1A852394", "2026-11-02T05:00:00Z"),
                visit("a1", "ashutosh", "1A852394", "2026-11-02T04:45:00Z"),
                visit("b1", "balram", "1A850249", "2026-11-02T04:27:00Z"),
                visit("a2", "ashutosh", "1A852394", "2026-11-02T04:55:00Z"),
                visit("b2", "balram", "1A852405", "2026-11-02T05:36:00Z"),
            ),
        )
        assertEquals(listOf("b2", "a1", "b1"), groups.map { it.first.id })
        assertEquals(listOf("a2", "a3"), groups[1].repeats.map { it.id })
    }

    @Test fun `inside if any check-in at that CSP was inside`() {
        val g = groupTeamVisits(
            listOf(
                visit("v1", "vijay", "1A851394", "2026-11-02T05:00:00Z", "OUTSIDE_FLAGGED"),
                visit("v2", "vijay", "1A851394", "2026-11-02T05:10:00Z"),
            ),
        ).single()
        assertTrue(g.inside)
    }
}
