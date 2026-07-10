package com.eko.dcvisits.core

import com.eko.dcvisits.core.dwell.CspSite
import com.eko.dcvisits.core.dwell.DwellConfig
import com.eko.dcvisits.core.dwell.DwellEvent
import com.eko.dcvisits.core.dwell.DwellMatcher
import com.eko.dcvisits.core.dwell.Fix
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Design 0001 §4 scenarios. Coordinates use the Nandpur fixture CSPs; metre
 * offsets are applied via latitude degrees (1e-5° ≈ 1.11 m).
 */
class DwellMatcherTest {

    private val kishanganj = CspSite("csp-1001", 25.3602, 85.7591, radiusM = 100.0)
    private val mahua = CspSite("csp-1002", 25.3721, 85.7488, radiusM = 120.0)
    // A market twin 80 m north of Kishanganj — overlapping effective radii.
    private val marketTwin = CspSite("csp-1003", 25.3602 + 0.00072, 85.7591, radiusM = 100.0)

    private fun latOffsetForMeters(m: Double): Double = m / 111_320.0

    private fun at(site: CspSite, northM: Double, accuracyM: Double, tSec: Long): Fix =
        Fix(site.lat + latOffsetForMeters(northM), site.lng, accuracyM, tSec * 1000)

    @Test
    fun `dwell at a CSP for tIn with minFixes emits CheckIn at first-entry time`() {
        val m = DwellMatcher(listOf(kishanganj, mahua))
        assertTrue(m.onFix(at(kishanganj, 0.0, 10.0, tSec = 0)).isEmpty(), "first fix never checks in")
        assertTrue(m.onFix(at(kishanganj, 5.0, 10.0, tSec = 60)).isEmpty(), "under tIn")
        val events = m.onFix(at(kishanganj, 3.0, 10.0, tSec = 125))
        val checkIn = assertIs<DwellEvent.CheckIn>(events.single())
        assertEquals("csp-1001", checkIn.cspId)
        assertEquals(0L, checkIn.enteredAtMs, "check-in is back-dated to first entry")
    }

    @Test
    fun `drive-past - brief entry then exit emits nothing`() {
        val m = DwellMatcher(listOf(kishanganj))
        assertTrue(m.onFix(at(kishanganj, 0.0, 10.0, tSec = 0)).isEmpty())
        assertTrue(m.onFix(at(kishanganj, 900.0, 10.0, tSec = 30)).isEmpty(), "left before tIn — no visit")
        // Re-entry later restarts the dwell clock from scratch
        assertTrue(m.onFix(at(kishanganj, 0.0, 10.0, tSec = 300)).isEmpty())
        assertTrue(m.onFix(at(kishanganj, 0.0, 10.0, tSec = 360)).isEmpty(), "only 60s since re-entry")
    }

    @Test
    fun `accuracy-inflated radius (ADR-0004) - 160m away with 80m accuracy is inside a 100m CSP`() {
        val m = DwellMatcher(listOf(kishanganj))
        m.onFix(at(kishanganj, 160.0, 80.0, tSec = 0))
        val events = m.onFix(at(kishanganj, 160.0, 80.0, tSec = 130))
        assertIs<DwellEvent.CheckIn>(events.single())
    }

    @Test
    fun `grey-zone GPS drift at the counter never checks out`() {
        val m = DwellMatcher(listOf(kishanganj))
        m.onFix(at(kishanganj, 0.0, 10.0, tSec = 0))
        m.onFix(at(kishanganj, 0.0, 10.0, tSec = 125)) // checked in
        // Drift to 150 m (outside 110 m effective radius, inside the 300 m exit floor) for 10 minutes
        for (t in 200..800 step 60) {
            assertTrue(m.onFix(at(kishanganj, 150.0, 10.0, tSec = t.toLong())).isEmpty(), "drift at t=$t must not check out")
        }
    }

    @Test
    fun `sustained exit checks out, back-dated to the last inside fix`() {
        val m = DwellMatcher(listOf(kishanganj))
        m.onFix(at(kishanganj, 0.0, 10.0, tSec = 0))
        m.onFix(at(kishanganj, 0.0, 10.0, tSec = 125)) // checked in; last inside t=125
        assertTrue(m.onFix(at(kishanganj, 400.0, 10.0, tSec = 200)).isEmpty(), "exit clock starts")
        assertTrue(m.onFix(at(kishanganj, 500.0, 10.0, tSec = 300)).isEmpty(), "under tOut")
        val events = m.onFix(at(kishanganj, 600.0, 10.0, tSec = 390))
        val checkOut = assertIs<DwellEvent.CheckOut>(events.single())
        assertEquals("csp-1001", checkOut.cspId)
        assertEquals(125_000L, checkOut.lastInsideAtMs, "check-out back-dated to last inside fix")
    }

    @Test
    fun `dense-market overlap - nearest wins, losers recorded as nearby candidates`() {
        val m = DwellMatcher(listOf(kishanganj, marketTwin))
        // 10 m north of Kishanganj with 100 m accuracy: both CSPs' effective radii contain the fix
        m.onFix(at(kishanganj, 10.0, 100.0, tSec = 0))
        val events = m.onFix(at(kishanganj, 10.0, 100.0, tSec = 130))
        val checkIn = assertIs<DwellEvent.CheckIn>(events.single())
        assertEquals("csp-1001", checkIn.cspId, "nearest CSP wins")
        assertEquals(listOf("csp-1003"), checkIn.nearbyCandidates, "overlap evidence for fraud analytics")
    }

    @Test
    fun `near-tie prefers the planned stop over a marginally nearer unplanned CSP`() {
        // Standing almost exactly between the twins: scores within the tie delta.
        val midpointNorthM = 40.0
        val m = DwellMatcher(listOf(kishanganj, marketTwin), plannedCspIds = setOf("csp-1003"))
        m.onFix(at(kishanganj, midpointNorthM, 100.0, tSec = 0))
        val events = m.onFix(at(kishanganj, midpointNorthM, 100.0, tSec = 130))
        val checkIn = assertIs<DwellEvent.CheckIn>(events.single())
        assertEquals("csp-1003", checkIn.cspId, "planned stop breaks the near-tie")
    }

    @Test
    fun `qualifying dwell at the next CSP emits CheckOut(old) then CheckIn(new)`() {
        val m = DwellMatcher(listOf(kishanganj, mahua), config = DwellConfig(minExitM = 300.0))
        m.onFix(at(kishanganj, 0.0, 10.0, tSec = 0))
        m.onFix(at(kishanganj, 0.0, 10.0, tSec = 125)) // checked in at Kishanganj
        // Rides ~1.4 km to Mahua Tola and dwells there
        m.onFix(at(mahua, 0.0, 10.0, tSec = 600))
        val events = m.onFix(at(mahua, 0.0, 10.0, tSec = 730))
        assertEquals(2, events.size)
        val out = assertIs<DwellEvent.CheckOut>(events[0])
        val inn = assertIs<DwellEvent.CheckIn>(events[1])
        assertEquals("csp-1001", out.cspId)
        assertEquals("csp-1002", inn.cspId)
    }
}
