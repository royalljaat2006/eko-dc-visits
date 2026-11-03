package com.eko.dcvisits.app

import com.eko.dcvisits.app.data.net.CspLocationDto
import com.eko.dcvisits.app.data.repo.formatDistance
import com.eko.dcvisits.app.data.repo.haversineM
import com.eko.dcvisits.app.data.repo.nearbyCsps
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NearbyCspsTest {
    // Admin standing in Patna; CSPs north-east (~1.5 km), west (~10 km) and far away in Lucknow.
    private val me = 25.6000 to 85.1000
    private val csps = listOf(
        CspLocationDto("ne", "1A000001", 25.6100, 85.1110),
        CspLocationDto("w", "1A000002", 25.6000, 85.0000),
        CspLocationDto("lko", "1A000003", 26.8467, 80.9462),
    )

    @Test fun `only CSPs inside the radius, nearest first`() {
        val near = nearbyCsps(csps, me.first, me.second, radiusKm = 25)
        assertEquals(listOf("ne", "w"), near.map { it.csp.csp_location_id })
        assertTrue(near[0].distanceM < near[1].distanceM)
        assertEquals(listOf("ne"), nearbyCsps(csps, me.first, me.second, radiusKm = 5).map { it.csp.csp_location_id })
    }

    @Test fun `direction is the compass way from the admin to the CSP`() {
        val near = nearbyCsps(csps, me.first, me.second, radiusKm = 25).associateBy { it.csp.csp_location_id }
        assertEquals("NE", near.getValue("ne").direction)
        assertEquals("W", near.getValue("w").direction)
    }

    @Test fun `distance matches a known pair and formats for people`() {
        val km = haversineM(25.6000, 85.1000, 25.6000, 85.0000) / 1000
        assertTrue(km in 9.9..10.1, "0.1 deg of longitude at 25.6N is about 10 km, got $km")
        assertEquals("850 m", formatDistance(850.4))
        assertEquals("3.4 km", formatDistance(3_420.0))
        assertEquals("27 km", formatDistance(26_800.0))
    }
}
