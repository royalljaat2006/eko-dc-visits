package com.eko.dcvisits.app.ui.location

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import androidx.core.content.ContextCompat
import com.eko.dcvisits.app.data.sync.Fix
import com.google.android.gms.location.CurrentLocationRequest
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
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
            .setPriority(Priority.PRIORITY_BALANCED_POWER_ACCURACY)
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
