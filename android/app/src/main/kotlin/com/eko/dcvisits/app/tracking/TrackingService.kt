package com.eko.dcvisits.app.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.location.LocationManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import com.eko.dcvisits.app.MainActivity
import com.eko.dcvisits.app.R
import com.eko.dcvisits.app.data.sync.Fix
import com.eko.dcvisits.app.data.sync.SyncPayloads
import com.eko.dcvisits.app.data.sync.SyncWorker
import com.eko.dcvisits.app.data.sync.Uuidv7
import com.eko.dcvisits.app.di.ServiceLocator
import com.eko.dcvisits.app.ui.location.LocationAccess
import com.eko.dcvisits.app.ui.location.LocationProvider
import com.eko.dcvisits.app.util.Ist
import com.eko.dcvisits.core.dwell.CspSite
import com.eko.dcvisits.core.dwell.DwellEvent
import com.eko.dcvisits.core.dwell.DwellMatcher
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.add
import java.time.Instant

/**
 * Duty-session route capture (BUILD_PLAN §3.2). Runs ONLY between Check In and
 * End Day, as a user-visible foreground service — a hard DPDP + trust
 * invariant. Adaptive sampling (~20 s moving), points batched into `track.chunk`
 * ops (tier T3), and every fix is fed to the design-0001 `DwellMatcher` so a
 * sustained dwell auto-emits `visit.checkin` (trigger AUTO_GEOFENCE).
 *
 * There is no `ACCESS_BACKGROUND_LOCATION`: updates flow only while this
 * visible service runs, and it stops hard at End Day or the 21:00 IST cutoff.
 */
class TrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default.limitedParallelism(1))

    private val fused by lazy { LocationServices.getFusedLocationProviderClient(this) }
    private val outbox by lazy { ServiceLocator.outboxRepository(this) }
    private val session by lazy { ServiceLocator.sessionStore }
    private val csps by lazy { ServiceLocator.cspRepository }

    private val buffer = ArrayDeque<SyncPayloads.TrackPt>()
    private var matcher: DwellMatcher? = null
    private var lastFixWallMs = 0L
    private var gapCount = 0
    private var pointCount = 0
    private var started = false

    // ---- live state shown in the notification ----
    private var gps: GpsStatus = GpsStatus.OK
    private var requestingUpdates = false
    private var visitsToday: Int? = null
    private var cspsAssigned: Int? = null
    private var pendingSync = 0
    private var dutySince: java.time.Instant? = null
    private var alertedFor: GpsStatus? = null
    private var ticksSinceAlert = 0
    private var ticksSinceVisitsRefresh = VISITS_REFRESH_EVERY_TICKS

    /** Fires the moment the user flips Location on/off in quick settings or Settings. */
    private val gpsReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            scope.launch { reevaluateGps() }
        }
    }

    /** cspId -> the client id of the visit.checkin THIS matcher session emitted for it,
     * so a later dwell-exit can close the same visit with visit.checkout. Session-scoped:
     * a checkout can't be correlated across a process restart, which is an acceptable
     * degradation (the DC can still tap "Check out" manually). */
    private val checkedInVisitIds = mutableMapOf<String, String>()

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            val locations = result.locations.toList()
            scope.launch { locations.forEach { onFix(it) } }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopEverything()
                return START_NOT_STICKY
            }
            else -> if (!started) startTracking()
        }
        return START_STICKY
    }

    private fun startTracking() {
        started = true
        gps = LocationAccess.status(this)
        startForegroundCompat(withLocation = LocationProvider.hasPermission(this))

        // Re-evaluate the instant Location is toggled, and also on a timer (permission can be
        // revoked from Settings without any broadcast).
        val filter = IntentFilter(LocationManager.PROVIDERS_CHANGED_ACTION).apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) addAction(LocationManager.MODE_CHANGED_ACTION)
        }
        androidx.core.content.ContextCompat.registerReceiver(
            this, gpsReceiver, filter, androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED,
        )

        scope.launch { ServiceLocator.attendanceRepository.onDutySince.collect { dutySince = it; updateNotification() } }
        scope.launch { outbox.pendingCount.collect { pendingSync = it; updateNotification() } }
        scope.launch {
            val sites = csps.sites()
            cspsAssigned = sites.size
            matcher = DwellMatcher(sites.map { CspSite(it.id, it.lat, it.lng, it.radiusM) })
            updateNotification()
        }
        scope.launch { reevaluateGps() }

        scope.launch {
            var ticks = 0
            while (isActive) {
                kotlinx.coroutines.delay(WATCHDOG_EVERY_MS)
                if (pastCutoff()) { stopEverything(); break }
                reevaluateGps()
                if (++ticksSinceVisitsRefresh >= VISITS_REFRESH_EVERY_TICKS) {
                    ticksSinceVisitsRefresh = 0
                    ServiceLocator.visitRepository.todayVisits()?.let { visitsToday = it.size }
                }
                if (++ticks % FLUSH_EVERY_TICKS == 0) flush()
                updateNotification()
            }
        }
    }

    /** Re-reads permission/GPS state; starts or resumes fix updates when possible; alerts on transitions. */
    private suspend fun reevaluateGps() {
        val now = LocationAccess.status(this)
        val changed = now != gps
        gps = now
        if (now == GpsStatus.OK) {
            ensureUpdates()
            if (alertedFor != null) {
                alertedFor = null
                getSystemService(NotificationManager::class.java).cancel(ALERT_ID)
            }
        } else {
            if (changed || alertedFor != now) {
                gapCount++ // we are not recording while this lasts
                postGpsAlert(now)
            } else if (++ticksSinceAlert >= REALERT_EVERY_TICKS) {
                postGpsAlert(now) // still off — nudge again
            }
        }
        updateNotification()
    }

    private fun ensureUpdates() {
        if (requestingUpdates) return
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, MOVING_INTERVAL_MS)
            .setMinUpdateIntervalMillis(MIN_INTERVAL_MS)
            .setMaxUpdateDelayMillis(MAX_BATCH_DELAY_MS)
            .build()
        try {
            fused.requestLocationUpdates(request, callback, mainLooper)
            requestingUpdates = true
        } catch (_: SecurityException) {
            gapCount++
        }
    }

    private suspend fun onFix(loc: android.location.Location) {
        if (pastCutoff()) { stopEverything(); return }

        val now = loc.time.takeIf { it > 0 } ?: System.currentTimeMillis()
        if (lastFixWallMs != 0L && now - lastFixWallMs > GAP_THRESHOLD_MS) {
            gapCount++ // TODO(contract v0.9): gap annotations as first-class track.chunk elements
        }
        lastFixWallMs = now

        val isMock = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) loc.isMock
        else @Suppress("DEPRECATION") loc.isFromMockProvider
        buffer.addLast(
            SyncPayloads.TrackPt(
                lat = loc.latitude,
                lng = loc.longitude,
                tIso = Ist.nowIso(Instant.ofEpochMilli(now)),
                accuracyM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else null,
                isMock = isMock,
            ),
        )
        pointCount++
        if (buffer.size >= CHUNK_POINTS) flush()

        matcher?.onFix(
            com.eko.dcvisits.core.dwell.Fix(
                lat = loc.latitude,
                lng = loc.longitude,
                accuracyM = if (loc.hasAccuracy()) loc.accuracy.toDouble() else 50.0,
                timestampMs = now,
            ),
        )?.forEach { handleDwell(it, loc, isMock) }

        updateNotification()
    }

    private suspend fun handleDwell(event: DwellEvent, loc: android.location.Location, isMock: Boolean) {
        val s = session.current() ?: return
        when (event) {
            is DwellEvent.CheckIn -> {
                val checkinId = Uuidv7.next()
                val payload = SyncPayloads.checkinEvent(
                    id = checkinId,
                    dcUserId = s.user.id,
                    deviceId = s.deviceId,
                    locationId = event.cspId,
                    fix = Fix(loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy.toDouble() else null, isMock),
                    wallIso = Ist.nowIso(Instant.ofEpochMilli(event.enteredAtMs)),
                    monotonicMs = SystemClock.elapsedRealtime(),
                    outOfRadiusReason = null,
                    remarks = null,
                    trigger = "AUTO_GEOFENCE",
                )
                val withCandidates = if (event.nearbyCandidates.isEmpty()) payload else buildJsonObject {
                    payload.forEach { (k, v) -> put(k, v) }
                    putJsonArray("nearby_candidates") { event.nearbyCandidates.forEach { add(it) } }
                }
                checkedInVisitIds[event.cspId] = checkinId
                outbox.enqueue(SyncPayloads.OP_VISIT_CHECKIN, withCandidates, label = "Auto check-in")
                SyncWorker.kick(this)
            }
            is DwellEvent.CheckOut -> {
                // Sustained exit — design 0001 §4. Back-dated to the last-inside fix;
                // only emitted when we can correlate it to a checkin FROM THIS SESSION.
                val visitId = checkedInVisitIds.remove(event.cspId) ?: return
                val payload = SyncPayloads.checkoutEvent(
                    id = Uuidv7.next(),
                    visitId = visitId,
                    dcUserId = s.user.id,
                    deviceId = s.deviceId,
                    fix = Fix(loc.latitude, loc.longitude, if (loc.hasAccuracy()) loc.accuracy.toDouble() else null, isMock),
                    wallIso = Ist.nowIso(Instant.ofEpochMilli(event.lastInsideAtMs)),
                    monotonicMs = SystemClock.elapsedRealtime(),
                    trigger = "AUTO_GEOFENCE",
                )
                outbox.enqueue(SyncPayloads.OP_VISIT_CHECKOUT, payload, label = "Auto check-out")
                SyncWorker.kick(this)
            }
        }
    }

    private suspend fun flush() {
        if (buffer.isEmpty()) return
        val s = session.current() ?: return
        val pts = ArrayList<SyncPayloads.TrackPt>(buffer.size)
        while (buffer.isNotEmpty() && pts.size < 500) pts.add(buffer.removeFirst())
        val payload: JsonObject = SyncPayloads.trackChunkEvent(
            id = Uuidv7.next(),
            dcUserId = s.user.id,
            deviceId = s.deviceId,
            points = pts,
            wallIso = Ist.nowIso(),
            monotonicMs = SystemClock.elapsedRealtime(),
        )
        outbox.enqueue(SyncPayloads.OP_TRACK_CHUNK, payload, label = "Track ×${pts.size}")
        SyncWorker.kick(this)
    }

    private fun pastCutoff(): Boolean = Ist.minutesOfDay(Instant.now()) >= CUTOFF_MINUTE

    private fun stopEverything() {
        runCatching { fused.removeLocationUpdates(callback) }
        runCatching { unregisterReceiver(gpsReceiver) }
        getSystemService(NotificationManager::class.java).cancel(ALERT_ID)
        scope.launch { flush() }
        scope.cancel()
        stopForegroundCompat()
        stopSelf()
    }

    override fun onDestroy() {
        runCatching { fused.removeLocationUpdates(callback) }
        runCatching { unregisterReceiver(gpsReceiver) }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- foreground notification ----------------------------------------

    private fun createChannel() {
        val nm = getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL, "On duty", NotificationManager.IMPORTANCE_LOW).apply {
                    description = "Your duty time, visits today, GPS and sync status while you are on duty"
                },
            )
        }
        if (nm.getNotificationChannel(ALERT_CHANNEL) == null) {
            nm.createNotificationChannel(
                NotificationChannel(ALERT_CHANNEL, "GPS alerts", NotificationManager.IMPORTANCE_HIGH).apply {
                    description = "Warns you when GPS or location permission is off during duty"
                    enableVibration(true)
                },
            )
        }
    }

    private fun openApp(): android.app.PendingIntent = android.app.PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun notification(): Notification {
        val power = getSystemService(PowerManager::class.java)
        val copy = DutyNotificationText.forDuty(
            DutySnapshot(
                gps = gps,
                onDutySince = dutySince,
                now = java.time.Instant.now(),
                visitsToday = visitsToday ?: checkedInVisitIds.size.takeIf { it > 0 },
                cspsAssigned = cspsAssigned,
                lastFixAt = lastFixWallMs.takeIf { it > 0 }?.let(java.time.Instant::ofEpochMilli),
                pendingSync = pendingSync,
                batterySaver = power?.isPowerSaveMode == true,
            ),
        )
        return NotificationCompat.Builder(this, CHANNEL)
            .setContentTitle(copy.title)
            .setContentText(copy.summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(copy.details.joinToString("\n")))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(openApp())
            .build()
    }

    /** Heads-up warning: GPS off / permission gone while on duty. Tapping it opens the right Settings screen. */
    private fun postGpsAlert(status: GpsStatus) {
        ticksSinceAlert = 0
        alertedFor = status
        val copy = DutyNotificationText.gpsAlert(status)
        val settings = if (status == GpsStatus.GPS_OFF) LocationAccess.locationSettingsIntent() else LocationAccess.appSettingsIntent(this)
        val toSettings = android.app.PendingIntent.getActivity(
            this, 1, settings, android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val n = NotificationCompat.Builder(this, ALERT_CHANNEL)
            .setContentTitle(copy.title)
            .setContentText(copy.summary)
            .setStyle(NotificationCompat.BigTextStyle().bigText(copy.details.joinToString("\n")))
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setContentIntent(toSettings)
            .addAction(0, if (status == GpsStatus.GPS_OFF) "Turn on GPS" else "Open settings", toSettings)
            .setAutoCancel(false)
            .build()
        runCatching { getSystemService(NotificationManager::class.java).notify(ALERT_ID, n) }
    }

    private fun startForegroundCompat(withLocation: Boolean) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && withLocation) {
                startForeground(NOTIF_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
            } else {
                startForeground(NOTIF_ID, notification())
            }
        } catch (_: Exception) {
            // FGS-start restrictions (rare here — we start from a visible activity).
            runCatching { startForeground(NOTIF_ID, notification()) }
        }
    }

    private fun updateNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, notification())
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) stopForeground(STOP_FOREGROUND_REMOVE)
        else @Suppress("DEPRECATION") stopForeground(true)
    }

    companion object {
        const val ACTION_START = "com.eko.dcvisits.tracking.START"
        const val ACTION_STOP = "com.eko.dcvisits.tracking.STOP"
        private const val CHANNEL = "on_duty"
        private const val NOTIF_ID = 4201
        private const val ALERT_CHANNEL = "gps_alerts"
        private const val ALERT_ID = 4202

        private const val MOVING_INTERVAL_MS = 20_000L
        private const val MIN_INTERVAL_MS = 10_000L
        private const val MAX_BATCH_DELAY_MS = 60_000L
        private const val WATCHDOG_EVERY_MS = 30_000L
        private const val FLUSH_EVERY_TICKS = 6 // 6 x 30 s = 3 min
        private const val VISITS_REFRESH_EVERY_TICKS = 6
        private const val REALERT_EVERY_TICKS = 20 // re-nudge about every 10 min while GPS stays off
        private const val CHUNK_POINTS = 60
        private const val GAP_THRESHOLD_MS = 90_000L
        private const val CUTOFF_MINUTE = 21 * 60 // 21:00 IST hard stop (DPDP)

        fun start(context: Context) {
            val i = Intent(context, TrackingService::class.java).setAction(ACTION_START)
            androidx.core.content.ContextCompat.startForegroundService(context, i)
        }

        fun stop(context: Context) {
            context.startService(Intent(context, TrackingService::class.java).setAction(ACTION_STOP))
        }
    }
}
