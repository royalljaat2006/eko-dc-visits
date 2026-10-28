package com.eko.dcvisits.app.tracking

import android.content.Context
import androidx.annotation.StringRes
import com.eko.dcvisits.app.R
import com.eko.dcvisits.app.util.Ist
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

/** What the phone can tell us about location right now. */
enum class GpsStatus { OK, NO_PERMISSION, GPS_OFF }

/** Everything the on-duty notification shows. All fields are plain data so the text is unit-testable. */
data class DutySnapshot(
    val gps: GpsStatus,
    val onDutySince: Instant?,
    val now: Instant,
    /** Server count when known, else this session's auto check-ins. */
    val visitsToday: Int?,
    val cspsAssigned: Int?,
    val lastFixAt: Instant?,
    val pendingSync: Int,
    val batterySaver: Boolean,
)

/** Notification copy: short line for the collapsed card, full lines for the expanded one. */
data class NotificationCopy(val title: String, val summary: String, val details: List<String>)

/**
 * Resolves a string resource with format args. In the app this is
 * `context.getString(id, *args)` on a context in the chosen app language; unit
 * tests resolve the same ids against res/values/strings.xml so the copy stays
 * plain-JVM testable.
 */
fun interface StringLookup {
    fun get(@StringRes id: Int, vararg args: Any): String

    companion object {
        fun of(context: Context): StringLookup = StringLookup { id, args -> context.getString(id, *args) }
    }
}

object DutyNotificationText {
    private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun forDuty(s: DutySnapshot, str: StringLookup): NotificationCopy {
        val since = s.onDutySince
        val elapsed = since?.let { duration(Duration.between(it, s.now), str) }
        val title = when (s.gps) {
            GpsStatus.OK -> if (elapsed != null) str.get(R.string.notif_on_duty_elapsed, elapsed) else str.get(R.string.notif_on_duty)
            GpsStatus.GPS_OFF -> str.get(R.string.notif_gps_off_title)
            GpsStatus.NO_PERMISSION -> str.get(R.string.notif_permission_needed)
        }

        val visits = when {
            s.visitsToday == null -> null
            s.cspsAssigned != null -> str.get(R.string.notif_visits_of, s.visitsToday, s.cspsAssigned)
            else -> str.get(R.string.notif_visits, s.visitsToday)
        }
        val gpsLine = when (s.gps) {
            GpsStatus.OK -> s.lastFixAt?.let { str.get(R.string.notif_gps_live_last_fix, ago(Duration.between(it, s.now), str)) }
                ?: str.get(R.string.notif_gps_waiting)
            GpsStatus.GPS_OFF -> str.get(R.string.notif_gps_off_line)
            GpsStatus.NO_PERMISSION -> str.get(R.string.notif_no_permission_line)
        }
        val sync = if (s.pendingSync <= 0) str.get(R.string.notif_all_synced) else str.get(R.string.notif_waiting_to_sync, s.pendingSync)

        val details = buildList {
            since?.let { add(str.get(R.string.notif_on_duty_since, ZonedDateTime.ofInstant(it, Ist.ZONE).format(HHMM))) }
            visits?.let { add(it) }
            add(gpsLine)
            add(if (s.batterySaver) str.get(R.string.notif_battery_saver, sync) else sync)
        }
        val summary = listOfNotNull(visits, if (s.gps == GpsStatus.OK) str.get(R.string.notif_gps_live) else null, sync).joinToString(" · ")
            .ifEmpty { gpsLine }
        return NotificationCopy(title, if (s.gps == GpsStatus.OK) summary else gpsLine, details)
    }

    /** The heads-up alert posted when GPS or its permission disappears mid-duty. */
    fun gpsAlert(gps: GpsStatus, str: StringLookup): NotificationCopy = when (gps) {
        GpsStatus.GPS_OFF -> NotificationCopy(
            str.get(R.string.notif_alert_gps_off_title),
            str.get(R.string.notif_alert_gps_off_summary),
            listOf(str.get(R.string.notif_alert_not_recorded), str.get(R.string.notif_alert_tap_location)),
        )
        GpsStatus.NO_PERMISSION -> NotificationCopy(
            str.get(R.string.notif_alert_perm_title),
            str.get(R.string.notif_alert_perm_summary),
            listOf(str.get(R.string.notif_alert_perm_summary), str.get(R.string.notif_alert_tap_app_settings)),
        )
        GpsStatus.OK -> NotificationCopy(
            str.get(R.string.notif_alert_back_title),
            str.get(R.string.notif_alert_resumed),
            listOf(str.get(R.string.notif_alert_resumed)),
        )
    }

    internal fun duration(d: Duration, str: StringLookup): String {
        val mins = d.toMinutes().coerceAtLeast(0)
        val h = mins / 60
        val m = mins % 60
        return if (h > 0) str.get(R.string.duration_h_m, h, m) else str.get(R.string.duration_m, m)
    }

    internal fun ago(d: Duration, str: StringLookup): String {
        val secs = d.seconds.coerceAtLeast(0)
        return when {
            secs < 60 -> str.get(R.string.ago_seconds, secs)
            secs < 3600 -> str.get(R.string.ago_minutes, secs / 60)
            else -> str.get(R.string.ago_hours, secs / 3600)
        }
    }
}
