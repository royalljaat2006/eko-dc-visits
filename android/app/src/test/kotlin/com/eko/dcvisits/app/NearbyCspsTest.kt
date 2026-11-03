package com.eko.dcvisits.app

import com.eko.dcvisits.app.data.net.CspLocationDto
import com.eko.dcvisits.app.data.repo.NearbyAlertTracker
import com.eko.dcvisits.app.data.repo.formatDistance
import com.eko.dcvisits.app.data.repo.haversineM
import com.eko.dcvisits.app.data.repo.nearbyCsps
import com.eko.dcvisits.app.ui.text.cspLabel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NearbyCspsTest {
    // Admin standing in Patna; CSPs north-east (~1.5 km), west (~10 km) and far away in Lucknow.
    private val me = 25.6000 to 85.1000
    private val csps = listOf(
        CspLocationDto("ne", "1A000001", lat = 25.6100, lng = 85.1110),
        CspLocationDto("w", "1A000002", lat = 25.6000, lng = 85.0000),
        CspLocationDto("lko", "1A000003", lat = 26.8467, lng = 80.9462),
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

    @Test fun `road alert fires once per CSP and again only after driving away`() {
        val t = NearbyAlertTracker(alertM = 1_000.0, rearmM = 2_000.0)
        val csp = listOf(CspLocationDto("a", "1A000009", lat = 25.6000, lng = 85.1000))
        // ~3 km away: nothing. ~500 m: alert. Still ~500 m / wobble to ~1.5 km: no repeat.
        assertEquals(emptyList(), t.onFix(csp, 25.6270, 85.1000).map { it.csp.csp_location_id })
        assertEquals(listOf("a"), t.onFix(csp, 25.6045, 85.1000).map { it.csp.csp_location_id })
        assertEquals(emptyList(), t.onFix(csp, 25.6045, 85.1000).map { it.csp.csp_location_id })
        assertEquals(emptyList(), t.onFix(csp, 25.6135, 85.1000).map { it.csp.csp_location_id })
        // Beyond 2 km re-arms it; coming back alerts again.
        assertEquals(emptyList(), t.onFix(csp, 25.6270, 85.1000).map { it.csp.csp_location_id })
        assertEquals(listOf("a"), t.onFix(csp, 25.6045, 85.1000).map { it.csp.csp_location_id })
    }

    @Test fun `CSP label shows the operator name next to the code once it is known`() {
        assertEquals("1A850004 · Kaushar Jahan", cspLabel("1A850004", " Kaushar Jahan "))
        assertEquals("1A850004", cspLabel("1A850004", "1A850004"))
        assertEquals("1A850004", cspLabel("1A850004", null))
    }
}
