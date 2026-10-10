package com.eko.dcvisits.app.ui.location

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.eko.dcvisits.app.tracking.GpsStatus

/**
 * Location access policy for the field app:
 *  - The OS permission is asked EVERY time the app comes to the foreground while it
 *    is missing (and again before Check In / End Day / visit check-in), not once.
 *  - If the OS will no longer show its dialog (denied twice / "Don't ask again"),
 *    we explain why and send the user to the app's settings instead.
 *  - If Location services (GPS) are off we say so and offer to turn them on.
 *
 * None of this GATES a field action (ADR-0004: the fix is logged, never a gate) —
 * "Continue without GPS" always exists; the server flags what it can't verify.
 */
object LocationAccess {

    fun status(context: Context): GpsStatus = when {
        !LocationProvider.hasPermission(context) -> GpsStatus.NO_PERMISSION
        !LocationProvider.isLocationEnabled(context) -> GpsStatus.GPS_OFF
        else -> GpsStatus.OK
    }

    /** Location + (Android 13+) notification permission — the notification shows duty data and GPS alerts. */
    fun runtimePermissions(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
    }.toTypedArray()

    fun notificationsAllowed(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

    fun locationSettingsIntent(): Intent =
        Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun appSettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    /** True when the OS will not show the permission dialog any more (so we must go to settings). */
    fun permanentlyDenied(activity: Activity?): Boolean {
        activity ?: return false
        return LocationProvider.hasPermission(activity).not() &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION) &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_COARSE_LOCATION)
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

private enum class Prompt { SETTINGS_FOR_PERMISSION, GPS_OFF }

/**
 * Put once near the top of the signed-in UI. On every resume it re-checks location
 * permission, notification permission and GPS, and asks / explains again if
 * anything is missing.
 */
@Composable
fun LocationGate() {
    val context = LocalContext.current
    val activity = context.findActivity()
    var prompt by remember { mutableStateOf<Prompt?>(null) }
    // The OS permission sheet itself pauses/resumes our activity; don't re-ask for that resume.
    var skipNextResume by remember { mutableStateOf(false) }

    fun check(launch: () -> Unit) {
        val missingLocation = !LocationProvider.hasPermission(context)
        when {
            missingLocation || !LocationAccess.notificationsAllowed(context) -> {
                if (missingLocation && LocationAccess.permanentlyDenied(activity)) prompt = Prompt.SETTINGS_FOR_PERMISSION
                else launch()
            }
            !LocationProvider.isLocationEnabled(context) -> prompt = Prompt.GPS_OFF
            else -> prompt = null
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        skipNextResume = true
        // After the answer: if location is still denied for good, explain; if granted but GPS is off, say so.
        if (!LocationProvider.hasPermission(context)) {
            if (LocationAccess.permanentlyDenied(activity)) prompt = Prompt.SETTINGS_FOR_PERMISSION
        } else if (!LocationProvider.isLocationEnabled(context)) {
            prompt = Prompt.GPS_OFF
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (skipNextResume) skipNextResume = false
                else check { launcher.launch(LocationAccess.runtimePermissions()) }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    when (prompt) {
        Prompt.SETTINGS_FOR_PERMISSION -> AlertDialog(
            onDismissRequest = { prompt = null },
            title = { Text("Location permission needed") },
            text = { Text("Eko records where you check in and your route during duty. Please allow Location for this app in Settings → Permissions.") },
            confirmButton = {
                TextButton(onClick = { prompt = null; context.startActivity(LocationAccess.appSettingsIntent(context)) }) { Text("Open settings") }
            },
            dismissButton = { TextButton(onClick = { prompt = null }) { Text("Not now") } },
        )
        Prompt.GPS_OFF -> AlertDialog(
            onDismissRequest = { prompt = null },
            title = { Text("GPS is off") },
            text = { Text("Turn on Location so your check-ins and route are recorded.") },
            confirmButton = {
                TextButton(onClick = { prompt = null; context.startActivity(LocationAccess.locationSettingsIntent()) }) { Text("Turn on GPS") }
            },
            dismissButton = { TextButton(onClick = { prompt = null }) { Text("Later") } },
        )
        null -> Unit
    }
}

/**
 * Wrap a field action (Check In / End Day / visit check-in): the permission is asked again
 * right before the action if it's missing, and a GPS-off warning is shown if Location is
 * off. The action ALWAYS still runs — after the answer, or via "Continue without GPS" —
 * because the fix is logged, never a gate (ADR-0004).
 */
@Composable
fun rememberLocationAsker(): (() -> Unit) -> Unit {
    val context = LocalContext.current
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    var gpsOffFor by remember { mutableStateOf<(() -> Unit)?>(null) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val action = pending
        pending = null
        if (action != null) {
            if (LocationProvider.hasPermission(context) && !LocationProvider.isLocationEnabled(context)) gpsOffFor = action
            else action()
        }
    }

    gpsOffFor?.let { action ->
        AlertDialog(
            onDismissRequest = { gpsOffFor = null },
            title = { Text("GPS is off") },
            text = { Text("Without GPS this will be recorded with no location and may be flagged for review.") },
            confirmButton = {
                TextButton(onClick = { gpsOffFor = null; context.startActivity(LocationAccess.locationSettingsIntent()) }) { Text("Turn on GPS") }
            },
            dismissButton = { TextButton(onClick = { gpsOffFor = null; action() }) { Text("Continue without GPS") } },
        )
    }

    return remember(context) {
        { action: () -> Unit ->
            when {
                !LocationProvider.hasPermission(context) -> {
                    pending = action
                    launcher.launch(LocationAccess.runtimePermissions())
                }
                !LocationProvider.isLocationEnabled(context) -> gpsOffFor = action
                else -> action()
            }
        }
    }
}
