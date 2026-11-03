package com.eko.dcvisits.app.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import com.eko.dcvisits.app.MainActivity
import com.eko.dcvisits.app.R
import com.eko.dcvisits.app.data.net.CspLocationDto
import com.eko.dcvisits.app.data.repo.NearbyAlertTracker
import com.eko.dcvisits.app.data.repo.NearbyCsp
import com.eko.dcvisits.app.data.repo.formatDistance
import com.eko.dcvisits.app.di.ServiceLocator
import com.eko.dcvisits.app.ui.location.LocationProvider
import com.eko.dcvisits.app.util.AppLocale
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.eko.dcvisits.app.ui.text.cspLabel

/**
 * Super admin "CSP nearby" alert on the road: while switched on, a visible foreground
 * service follows the admin's position (battery-friendly, about once a minute) and
 * posts a notification when a CSP comes within 1 km — also with the app closed.
 *
 * Only for admins the server granted "CSPs near me" (the CSP list is a 403 otherwise),
 * and the position is compared on the phone; it is never sent anywhere. The admin
 * switches it off from the CSPs tab or the notification's "Turn off" button.
 */
class NearbyAlertService : Service() {

    private val crashGuard = CoroutineExceptionHandler { _, e -> android.util.Log.e("NearbyAlertService", "alert task failed", e) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1) + crashGuard)
    private val tracker = NearbyAlertTracker()
    private var csps: List<CspLocationDto> = emptyList()
    private var started = false

    override fun onCreate() {
        super.onCreate()
        createChannels()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            NearbyAlerts.setOn(this, false)
            stopEverything()
            return START_NOT_STICKY
        }
        if (!NearbyAlerts.isOn(this)) {
            stopEverything()
            return START_NOT_STICKY
        }
        if (!started) begin()
        return START_STICKY
    }

    private fun begin() {
        started = true
        startForegroundCompat()
        scope.launch {
            // The list rarely changes; reload a few times a day. Signed out or no grant -> stop.
            while (isActive) {
                if (ServiceLocator.sessionStore.current() == null) { stopEverything(); return@launch }
                ServiceLocator.teamRepository.cspLocations()?.let { csps = it; updateWatchNotification() }
                delay(if (csps.isEmpty()) RETRY_MS else RELOAD_MS)
            }
        }
        scope.launch {
            LocationProvider.updates(this@NearbyAlertService, FIX_EVERY_MS, Priority.PRIORITY_BALANCED_POWER_ACCURACY)
                .collect { fix ->
                    if (csps.isEmpty()) return@collect
                    val fresh = tracker.onFix(csps, fix.lat, fix.lng)
                    if (fresh.isNotEmpty()) postAlert(fresh)
                }
        }
    }

    private fun stopEverything() {
        scope.cancel()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- notifications ---------------------------------------------------

    private fun strings(): StringLookup = StringLookup.of(AppLocale.localized(this))

    private fun createChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        val str = strings()
        nm.createNotificationChannel(
            NotificationChannel(WATCH_CHANNEL, str.get(R.string.nearby_alert_watch_channel), NotificationManager.IMPORTANCE_MIN),
        )
        nm.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL, str.get(R.string.nearby_alert_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
            },
        )
    }

    private fun openApp(): PendingIntent = PendingIntent.getActivity(
        this, 10, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun watchNotification(): Notification {
        val str = strings()
        val turnOff = PendingIntent.getService(
            this, 11, Intent(this, NearbyAlertService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, WATCH_CHANNEL)
            .setContentTitle(str.get(R.string.nearby_alert_watch_title))
            .setContentText(
                if (csps.isEmpty()) str.get(R.string.nearby_alert_watch_loading)
                else str.get(R.string.nearby_alert_watch_text, csps.size),
            )
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openApp())
            .addAction(0, str.get(R.string.nearby_alert_turn_off), turnOff)
            .build()
    }

    private fun updateWatchNotification() {
        runCatching { getSystemService(NotificationManager::class.java).notify(WATCH_ID, watchNotification()) }
    }

    /** One heads-up per batch: the nearest CSP in the title, the rest listed below. */
    private fun postAlert(fresh: List<NearbyCsp>) {
        val str = strings()
        val first = fresh.first()
        val lines = fresh.map { line(it, str) }
        val title = if (fresh.size == 1) str.get(R.string.nearby_alert_title_one, cspLabel(first.csp))
        else str.get(R.string.nearby_alert_title_many, fresh.size)
        val n = NotificationCompat.Builder(this, ALERT_CHANNEL)
            .setContentTitle(title)
            .setContentText(lines.first())
            .setStyle(NotificationCompat.BigTextStyle().bigText(lines.joinToString("\n")))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setContentIntent(openApp())
            .setAutoCancel(true)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(ALERT_ID_BASE + (alertSeq++ % 50), n) }
    }

    private fun line(n: NearbyCsp, str: StringLookup): String {
        val where = listOfNotNull(n.csp.dc_name?.let { str.get(R.string.nearby_dc, it) }, n.csp.district).joinToString(" · ")
        return "${cspLabel(n.csp)} — ${formatDistance(n.distanceM)} ${n.arrow} ${n.direction}" + if (where.isNotEmpty()) " · $where" else ""
    }

    private fun cspLabel(c: CspLocationDto): String = cspLabel(c.code, c.name)

    private fun startForegroundCompat() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(WATCH_ID, watchNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(WATCH_ID, watchNotification())
            }
        } catch (_: Exception) {
            // Location FGS refused (no permission / started from background): nothing to watch with.
            stopEverything()
        }
    }

    companion object {
        private const val ACTION_STOP = "com.eko.dcvisits.nearby.STOP"
        private const val WATCH_CHANNEL = "nearby_watch"
        private const val ALERT_CHANNEL = "nearby_alerts"
        private const val WATCH_ID = 4301
        private const val ALERT_ID_BASE = 4310
        private var alertSeq = 0

        private const val FIX_EVERY_MS = 30_000L
        private const val RELOAD_MS = 6 * 60 * 60 * 1000L
        private const val RETRY_MS = 5 * 60 * 1000L

        /** Starts only while switched on and with location permission (a location FGS needs it). */
        fun start(context: Context) {
            if (!NearbyAlerts.isOn(context) || !LocationProvider.hasPermission(context)) return
            runCatching {
                androidx.core.content.ContextCompat.startForegroundService(context, Intent(context, NearbyAlertService::class.java))
            }
        }

        fun stop(context: Context) {
            runCatching { context.stopService(Intent(context, NearbyAlertService::class.java)) }
        }
    }
}

/** The admin's own on/off choice for the road alert, kept on this phone. */
object NearbyAlerts {
    private const val PREFS = "nearby_alerts"
    private const val KEY_ON = "on"

    fun isOn(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ON, false)

    fun setOn(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_ON, on).apply()
    }
}
