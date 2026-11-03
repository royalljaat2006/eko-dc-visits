package com.eko.dcvisits.app.ui.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.eko.dcvisits.app.data.sync.Fix
import android.os.Looper
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * One-shot GPS fix for check-in / attendance. The fix is LOGGED with the event
 * and never gates it (ADR-0004) — so every failure path here resolves to null,
 * and the field flows carry on regardless.
 */
object LocationProvider {

    fun hasPermission(context: Context): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun currentFix(context: Context): Fix? {
        if (!hasPermission(context)) return null
        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = CurrentLocationRequest.Builder()
            .setPriority(Priority.PRIORITY_HIGH_ACCURACY) // real GPS: check-in geofence is radius + accuracy
            .setMaxUpdateAgeMillis(30_000)
            .setDurationMillis(8_000)
            .build()
        return suspendCancellableCoroutine { cont ->
            try {
                client.getCurrentLocation(request, null).addOnSuccessListener { loc ->
                    cont.resume(loc?.toFix())
                }.addOnFailureListener {
                    cont.resume(null)
                }
            } catch (_: SecurityException) {
                cont.resume(null)
            }
        }
    }

    /**
     * Fixes for as long as the collector is active — a screen that follows the user
     * while it is open (admin "CSPs near me"). Stops the moment collection stops;
     * nothing runs in the background. Emits nothing without permission.
     */
    fun updates(context: Context, intervalMs: Long = 10_000): Flow<Fix> = callbackFlow {
        if (!hasPermission(context)) { close(); return@callbackFlow }
        val client = LocationServices.getFusedLocationProviderClient(context)
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, intervalMs)
            .setMinUpdateIntervalMillis(intervalMs / 2)
            .build()
        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                result.lastLocation?.let { trySend(it.toFix()) }
            }
        }
        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (_: SecurityException) {
            close()
        }
        awaitClose { client.removeLocationUpdates(callback) }
    }

    private fun android.location.Location.toFix() = Fix(
        lat = latitude,
        lng = longitude,
        accuracyM = if (hasAccuracy()) accuracy.toDouble() else null,
        isMock = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) isMock else @Suppress("DEPRECATION") isFromMockProvider,
        provider = provider ?: "fused",
    )

    fun isLocationEnabled(context: Context): Boolean {
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return lm.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
    }
}
