package com.eko.dcvisits.app.tracking

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

/**
 * Low-end Xiaomi/Oppo/Vivo/Realme phones kill background services to save
 * battery, which silently stops route capture mid-shift. When the DC goes on
 * duty we ask once per app launch to exempt the app from battery optimisation
 * (the standard system dialog — the DC can still say no).
 */
object BatteryOptimization {
    @Volatile private var askedThisLaunch = false

    fun isExempt(context: Context): Boolean =
        context.getSystemService(PowerManager::class.java)
            ?.isIgnoringBatteryOptimizations(context.packageName) ?: true

    @SuppressLint("BatteryLife") // sideloaded field app: route capture is the core duty feature
    fun askIfNeeded(context: Context) {
        if (askedThisLaunch || isExempt(context)) return
        askedThisLaunch = true
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS)
            .setData(Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(direct) }
            .recoverCatching { context.startActivity(fallback) }
    }
}
