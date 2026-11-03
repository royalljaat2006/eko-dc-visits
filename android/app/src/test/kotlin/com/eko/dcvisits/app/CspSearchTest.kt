package com.eko.dcvisits.app

import com.eko.dcvisits.app.data.net.DashboardCspDto
import com.eko.dcvisits.app.ui.team.filterCsps
import kotlin.test.Test
import kotlin.test.assertEquals

class CspSearchTest {
    private val csps = listOf(
        DashboardCspDto("1", "1A850249", address = "Main Road", district = "Patna", dc_user_id = "b", dc_name = "Balram"),
        DashboardCspDto("2", "1A852394", district = "Vaishali", dc_user_id = "a", dc_name = "Ashutosh Pathak"),
        DashboardCspDto("3", "1A851000", district = "Lucknow"),
    )

    @Test fun `search matches code, district and DC name, ignoring case`() {
        assertEquals(listOf("1"), filterCsps(csps, "850249", false).map { it.csp_location_id })
        assertEquals(listOf("2"), filterCsps(csps, "vaishali", false).map { it.csp_location_id })
        assertEquals(listOf("1"), filterCsps(csps, " BALRAM ", false).map { it.csp_location_id })
    }

    @Test fun `unassigned filter keeps only CSPs without a DC, and combines with search`() {
        assertEquals(listOf("3"), filterCsps(csps, "", true).map { it.csp_location_id })
        assertEquals(emptyList(), filterCsps(csps, "patna", true).map { it.csp_location_id })
    }
}
