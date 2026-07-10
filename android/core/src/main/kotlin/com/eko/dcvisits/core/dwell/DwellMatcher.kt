package com.eko.dcvisits.core.dwell

import com.eko.dcvisits.core.geo.effectiveRadiusM
import com.eko.dcvisits.core.geo.haversineMeters

/**
 * Auto check-in / check-out dwell matcher — design 0001 §4, implemented as a
 * pure state machine over the duty-session tracking stream.
 *
 * Deliberately NOT the Android Geofencing API (100-fence cap; killed by OEM
 * battery managers): the already-running foreground tracking service feeds
 * every fix here against the on-device assigned-CSP table. Fully offline.
 *
 * Rules (thresholds are remote-config in production; defaults per the design):
 *  - CHECK-IN: >= minFixes consecutive fixes spanning >= tInMs inside a CSP's
 *    effective radius (radius + accuracy, ADR-0004) → CheckIn at first-entry
 *    time, carrying overlapping candidates (dense-market evidence).
 *  - CHECK-OUT: sustained exit — fixes >= max(exitFactor × effRadius,
 *    minExitM) away for >= tOutMs → CheckOut back-dated to the last inside
 *    fix. Grey-zone drift (outside radius, inside exit threshold) never
 *    starts the exit clock: a DC at the counter with bouncing GPS stays
 *    checked in.
 *  - OVERLAP: best candidate by distance/effectiveRadius score; near-ties
 *    prefer the planned stop, then the nearest; losers are recorded as
 *    nearby_candidates on the event (C1 checkin-event schema).
 *  - A qualifying dwell at a different CSP emits CheckOut(old) then
 *    CheckIn(new) — one physical DC is at one shop at a time.
 *
 * The caller feeds fixes ONLY between Start Day and End Day (DPDP hard stop —
 * BUILD_PLAN §3.2); this class holds no timers and no Android dependencies.
 */

data class CspSite(val id: String, val lat: Double, val lng: Double, val radiusM: Double)

data class Fix(val lat: Double, val lng: Double, val accuracyM: Double, val timestampMs: Long)

sealed interface DwellEvent {
    /** Emit as sync op visit.checkin with trigger AUTO_GEOFENCE (C1/C3). */
    data class CheckIn(val cspId: String, val enteredAtMs: Long, val nearbyCandidates: List<String>) : DwellEvent

    /** Emit as sync op visit.checkout with trigger AUTO_GEOFENCE, back-dated to last-inside. */
    data class CheckOut(val cspId: String, val lastInsideAtMs: Long) : DwellEvent
}

data class DwellConfig(
    val tInMs: Long = 120_000,
    val tOutMs: Long = 180_000,
    val minFixes: Int = 2,
    val exitFactor: Double = 2.0,
    val minExitM: Double = 300.0,
    /** Candidates within this score of the best are "near ties" for planned preference. */
    val tieScoreDelta: Double = 0.05,
    val maxNearbyCandidates: Int = 5,
)

class DwellMatcher(
    csps: Collection<CspSite>,
    private val plannedCspIds: Set<String> = emptySet(),
    private val config: DwellConfig = DwellConfig(),
) {
    private val sites = csps.toList()

    // Pre-check-in dwell accumulation
    private var dwellCspId: String? = null
    private var dwellFirstMs: Long = 0
    private var dwellFixCount: Int = 0
    private var dwellCandidates: MutableSet<String> = mutableSetOf()

    // Checked-in state
    private var checkedInCspId: String? = null
    private var lastInsideMs: Long = 0
    private var outsideSinceMs: Long? = null

    fun onFix(fix: Fix): List<DwellEvent> {
        val events = mutableListOf<DwellEvent>()

        val candidates = sites
            .map { it to haversineMeters(fix.lat, fix.lng, it.lat, it.lng) }
            .filter { (site, dist) -> dist <= effectiveRadiusM(site.radiusM, fix.accuracyM) }
            .map { (site, dist) -> Candidate(site, dist, dist / effectiveRadiusM(site.radiusM, fix.accuracyM)) }
            .sortedBy { it.score }
        val best = pickBest(candidates)

        val current = checkedInCspId
        if (current != null) {
            if (best?.site?.id == current) {
                lastInsideMs = fix.timestampMs
                outsideSinceMs = null
                resetDwell()
                return events
            }
            // Not inside the current CSP: run the exit clock only beyond the
            // exit threshold (grey-zone drift keeps the DC checked in).
            val site = sites.first { it.id == current }
            val dist = haversineMeters(fix.lat, fix.lng, site.lat, site.lng)
            val exitThreshold = maxOf(config.exitFactor * effectiveRadiusM(site.radiusM, fix.accuracyM), config.minExitM)
            if (dist >= exitThreshold) {
                val since = outsideSinceMs ?: fix.timestampMs.also { outsideSinceMs = it }
                if (fix.timestampMs - since >= config.tOutMs) {
                    events += DwellEvent.CheckOut(current, lastInsideMs)
                    checkedInCspId = null
                    outsideSinceMs = null
                }
            } else {
                outsideSinceMs = null // back inside the grey zone — not a sustained exit
            }
        }

        if (best != null && best.site.id != checkedInCspId) {
            accumulateDwell(best, candidates, fix.timestampMs)
            if (dwellQualifies(fix.timestampMs)) {
                // One shop at a time: a qualifying dwell elsewhere closes the
                // current visit first (design 0001 §4).
                checkedInCspId?.let { previous ->
                    events += DwellEvent.CheckOut(previous, lastInsideMs)
                }
                events += DwellEvent.CheckIn(
                    cspId = best.site.id,
                    enteredAtMs = dwellFirstMs,
                    nearbyCandidates = (dwellCandidates - best.site.id).take(config.maxNearbyCandidates),
                )
                checkedInCspId = best.site.id
                lastInsideMs = fix.timestampMs
                outsideSinceMs = null
                resetDwell()
            }
        } else if (best == null) {
            resetDwell() // drive-past: brief entry never becomes a visit
        }

        return events
    }

    private data class Candidate(val site: CspSite, val distM: Double, val score: Double)

    private fun pickBest(candidates: List<Candidate>): Candidate? {
        if (candidates.isEmpty()) return null
        val bestScore = candidates.first().score
        val nearTies = candidates.filter { it.score <= bestScore + config.tieScoreDelta }
        return nearTies.firstOrNull { plannedCspIds.contains(it.site.id) } ?: nearTies.first()
    }

    private fun accumulateDwell(best: Candidate, candidates: List<Candidate>, nowMs: Long) {
        if (dwellCspId != best.site.id) {
            dwellCspId = best.site.id
            dwellFirstMs = nowMs
            dwellFixCount = 0
            dwellCandidates = mutableSetOf()
        }
        dwellFixCount += 1
        dwellCandidates.addAll(candidates.map { it.site.id })
    }

    private fun dwellQualifies(nowMs: Long): Boolean =
        dwellCspId != null && dwellFixCount >= config.minFixes && nowMs - dwellFirstMs >= config.tInMs

    private fun resetDwell() {
        dwellCspId = null
        dwellFixCount = 0
        dwellCandidates = mutableSetOf()
    }
}
