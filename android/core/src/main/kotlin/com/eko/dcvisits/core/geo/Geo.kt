package com.eko.dcvisits.core.geo

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

private const val EARTH_RADIUS_M = 6_371_008.8

/**
 * Great-circle distance in metres. Mirrors backend/src/geo.ts so client-side
 * UX checks and server-side judgment (ADR-0004) share one distance basis.
 */
fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val sinLat = sin(dLat / 2)
    val sinLng = sin(dLng / 2)
    val h = sinLat * sinLat + cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sinLng * sinLng
    return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(h)))
}

/**
 * ADR-0004: geofence judgments always compare against the accuracy-inflated
 * effective radius — never punish the phone for weak GPS.
 */
fun effectiveRadiusM(radiusM: Double, accuracyM: Double?): Double = radiusM + (accuracyM ?: 0.0)
