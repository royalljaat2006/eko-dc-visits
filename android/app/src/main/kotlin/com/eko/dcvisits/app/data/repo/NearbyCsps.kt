package com.eko.dcvisits.app.data.repo

import com.eko.dcvisits.app.data.net.CspLocationDto
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/** One CSP as the "near me" list shows it: how far, and which way from the admin. */
data class NearbyCsp(
    val csp: CspLocationDto,
    val distanceM: Double,
    /** Compass bearing from the admin to the CSP, 0..360 (0 = north). */
    val bearingDeg: Double,
) {
    /** Eight-point compass label, e.g. "NE". */
    val direction: String get() = COMPASS[((bearingDeg + 22.5) / 45).toInt() % 8]
    val arrow: String get() = ARROWS[((bearingDeg + 22.5) / 45).toInt() % 8]

    private companion object {
        val COMPASS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        val ARROWS = listOf("↑", "↗", "→", "↘", "↓", "↙", "←", "↖")
    }
}

private const val EARTH_RADIUS_M = 6_371_008.8

/** Great-circle distance in metres (same formula as the server's geo.ts). */
fun haversineM(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dp = Math.toRadians(lat2 - lat1)
    val dl = Math.toRadians(lng2 - lng1)
    val a = sin(dp / 2) * sin(dp / 2) + cos(p1) * cos(p2) * sin(dl / 2) * sin(dl / 2)
    return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
}

/** Initial compass bearing from point 1 to point 2, 0..360. */
fun bearingDeg(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val p1 = Math.toRadians(lat1)
    val p2 = Math.toRadians(lat2)
    val dl = Math.toRadians(lng2 - lng1)
    val y = sin(dl) * cos(p2)
    val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dl)
    return (Math.toDegrees(atan2(y, x)) + 360) % 360
}

/**
 * The CSPs within [radiusKm] of the admin, nearest first, at most [limit]. Runs on
 * the phone so the admin's own position never leaves it.
 */
fun nearbyCsps(all: List<CspLocationDto>, lat: Double, lng: Double, radiusKm: Int, limit: Int = 50): List<NearbyCsp> =
    all.asSequence()
        .map { NearbyCsp(it, haversineM(lat, lng, it.lat, it.lng), bearingDeg(lat, lng, it.lat, it.lng)) }
        .filter { it.distanceM <= radiusKm * 1000.0 }
        .sortedBy { it.distanceM }
        .take(limit)
        .toList()

/** "850 m" under a kilometre, else "3.4 km" / "27 km". */
fun formatDistance(m: Double): String = when {
    m < 1000 -> "${m.roundToInt()} m"
    m < 10_000 -> "${(m / 100).roundToInt() / 10.0} km"
    else -> "${(m / 1000).roundToInt()} km"
}
