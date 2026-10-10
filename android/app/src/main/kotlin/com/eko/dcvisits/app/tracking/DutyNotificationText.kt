package com.eko.dcvisits.app.tracking

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

object DutyNotificationText {
    private val HHMM: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm")

    fun forDuty(s: DutySnapshot): NotificationCopy {
        val since = s.onDutySince
        val elapsed = since?.let { duration(Duration.between(it, s.now)) }
        val title = when (s.gps) {
            GpsStatus.OK -> if (elapsed != null) "On duty · $elapsed" else "On duty"
            GpsStatus.GPS_OFF -> "GPS is off — route not recording"
            GpsStatus.NO_PERMISSION -> "Location permission needed"
        }

        val visits = when {
            s.visitsToday == null -> null
            s.cspsAssigned != null -> "Visits today: ${s.visitsToday} of ${s.cspsAssigned} CSPs"
            else -> "Visits today: ${s.visitsToday}"
        }
        val gpsLine = when (s.gps) {
            GpsStatus.OK -> s.lastFixAt?.let { "GPS live · last fix ${ago(Duration.between(it, s.now))}" } ?: "GPS on · waiting for a fix"
            GpsStatus.GPS_OFF -> "Turn on Location to keep recording your visits"
            GpsStatus.NO_PERMISSION -> "Allow Location for this app to record your route"
        }
        val sync = if (s.pendingSync <= 0) "All synced" else "${s.pendingSync} waiting to sync"

        val details = buildList {
            since?.let { add("On duty since ${ZonedDateTime.ofInstant(it, Ist.ZONE).format(HHMM)} IST") }
            visits?.let { add(it) }
            add(gpsLine)
            add(if (s.batterySaver) "$sync · battery saver on" else sync)
        }
        val summary = listOfNotNull(visits, if (s.gps == GpsStatus.OK) "GPS live" else null, sync).joinToString(" · ")
            .ifEmpty { gpsLine }
        return NotificationCopy(title, if (s.gps == GpsStatus.OK) summary else gpsLine, details)
    }

    /** The heads-up alert posted when GPS or its permission disappears mid-duty. */
    fun gpsAlert(gps: GpsStatus): NotificationCopy = when (gps) {
        GpsStatus.GPS_OFF -> NotificationCopy(
            "GPS is off",
            "Your route and visits are not being recorded. Tap to turn Location on.",
            listOf("Your route and visits are not being recorded.", "Tap to open Location settings and turn GPS on."),
        )
        GpsStatus.NO_PERMISSION -> NotificationCopy(
            "Location permission is off",
            "Allow Location for Eko so your route and visits can be recorded.",
            listOf("Allow Location for Eko so your route and visits can be recorded.", "Tap to open app settings."),
        )
        GpsStatus.OK -> NotificationCopy("GPS is back", "Recording resumed.", listOf("Recording resumed."))
    }

    internal fun duration(d: Duration): String {
        val mins = d.toMinutes().coerceAtLeast(0)
        val h = mins / 60
        val m = mins % 60
        return if (h > 0) "${h}h ${m}m" else "${m}m"
    }

    internal fun ago(d: Duration): String {
        val secs = d.seconds.coerceAtLeast(0)
        return when {
            secs < 60 -> "${secs}s ago"
            secs < 3600 -> "${secs / 60}m ago"
            else -> "${secs / 3600}h ago"
        }
    }
}
