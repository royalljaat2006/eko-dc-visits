package com.eko.dcvisits.app.tracking

import com.eko.dcvisits.app.R
import com.eko.dcvisits.app.data.repo.DayState
import java.util.Locale

/** The two daily reminder pop-ups a DC gets. */
enum class ReminderSlot { MORNING, EVENING }

/** Everything a reminder shows. All fields are plain data so the text is unit-testable. */
data class ReminderSnapshot(
    val slot: ReminderSlot,
    val day: DayState,
    val cspsAssigned: Int,
    val visitedToday: Int,
    /** Nearest CSP not yet visited today, when the phone knows where it is. */
    val nearestPendingCode: String?,
    val nearestPendingMeters: Double?,
)

object DailyReminderText {

    /** Null = nothing worth interrupting the DC for in this slot. */
    fun forSlot(s: ReminderSnapshot, str: StringLookup): NotificationCopy? {
        val pending = (s.cspsAssigned - s.visitedToday).coerceAtLeast(0)
        val pendingLine = if (s.cspsAssigned > 0) str.get(R.string.remind_pending, pending, s.cspsAssigned) else null
        val nearestLine = if (pending > 0 && s.nearestPendingCode != null && s.nearestPendingMeters != null) {
            str.get(R.string.remind_nearest, s.nearestPendingCode, String.format(Locale.ROOT, "%.1f", s.nearestPendingMeters / 1000))
        } else {
            null
        }

        return when (s.slot) {
            ReminderSlot.MORNING -> when (s.day) {
                DayState.NOT_STARTED -> {
                    val checkIn = str.get(R.string.remind_morning_check_in)
                    NotificationCopy(str.get(R.string.remind_morning_title), checkIn, listOfNotNull(checkIn, pendingLine, nearestLine))
                }
                DayState.ON_DUTY -> pendingLine?.takeIf { pending > 0 }?.let {
                    NotificationCopy(str.get(R.string.remind_today_title), it, listOfNotNull(it, nearestLine, str.get(R.string.remind_open_logi)))
                }
                DayState.ENDED -> null
            }
            ReminderSlot.EVENING -> when (s.day) {
                DayState.ON_DUTY -> {
                    val endDay = str.get(R.string.remind_evening_end_day)
                    NotificationCopy(
                        str.get(R.string.remind_evening_title),
                        listOfNotNull(str.get(R.string.remind_visited, s.visitedToday), pendingLine).joinToString(" · "),
                        listOfNotNull(str.get(R.string.remind_visited, s.visitedToday), pendingLine, endDay, str.get(R.string.remind_auto_close)),
                    )
                }
                DayState.NOT_STARTED, DayState.ENDED -> null
            }
        }
    }
}
