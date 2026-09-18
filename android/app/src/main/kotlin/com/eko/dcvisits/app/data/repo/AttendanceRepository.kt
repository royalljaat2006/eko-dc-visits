package com.eko.dcvisits.app.data.repo

import android.content.Context
import android.os.SystemClock
import com.eko.dcvisits.app.data.net.ApiClient
import com.eko.dcvisits.app.data.net.AttendanceRowDto
import com.eko.dcvisits.app.data.net.C2Api
import com.eko.dcvisits.app.data.net.bearer
import com.eko.dcvisits.app.data.net.bodyOrThrow
import com.eko.dcvisits.app.data.session.SessionStore
import com.eko.dcvisits.app.data.sync.Fix
import com.eko.dcvisits.app.data.sync.OutboxRepository
import com.eko.dcvisits.app.data.sync.SyncPayloads
import com.eko.dcvisits.app.data.sync.SyncWorker
import com.eko.dcvisits.app.data.sync.Uuidv7
import com.eko.dcvisits.app.tracking.TrackingService
import com.eko.dcvisits.app.util.Ist
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

enum class DayState { NOT_STARTED, ON_DUTY, ENDED }

/**
 * Attendance = the day boundary (spec §3). START/END are evidence ops on the
 * standard C3 path; the GPS fix is attached when available but the day is never
 * location-gated (ADR-0004). Gating of the other tabs reads [localDayState],
 * which is derived from the outbox so "Check In" unlocks the app instantly,
 * offline, before any server round-trip.
 */
class AttendanceRepository(
    private val appContext: Context,
    private val outbox: OutboxRepository,
    private val session: SessionStore,
    private val api: C2Api = ApiClient.api,
) {
    /** Local, optimistic day state from today's enqueued attendance ops (IST). */
    val localDayState: Flow<DayState> = outbox.recent.map { rows ->
        val today = Ist.today()
        val todays = rows
            .filter { it.type == SyncPayloads.OP_ATTENDANCE_START || it.type == SyncPayloads.OP_ATTENDANCE_END }
            .mapNotNull { row ->
                val payload = runCatching {
                    ApiClient.json.decodeFromString(JsonObject.serializer(), row.payload)
                }.getOrNull() ?: return@mapNotNull null
                val wall = payload["timestamps"]?.jsonObject?.get("device_wall_time")?.jsonPrimitive?.content
                    ?: return@mapNotNull null
                if (Ist.date(java.time.Instant.parse(wall)) != today) return@mapNotNull null
                wall to (payload["kind"]?.jsonPrimitive?.content ?: "")
            }
            .sortedBy { it.first }
        when (todays.lastOrNull()?.second) {
            "START" -> DayState.ON_DUTY
            "END" -> DayState.ENDED
            else -> DayState.NOT_STARTED
        }
    }

    suspend fun checkIn(fix: Fix?) = submit("START", fix, "Check In")
    suspend fun endDay(fix: Fix?) = submit("END", fix, "End Day")

    /**
     * Reopen today's day after an End Day (same IST date only — the ENDED state
     * this is offered from is derived from *today's* ops). Emits a fresh START:
     * the server keeps min(START)/max(END) (see backend mergeAttendanceDay), so
     * once the DC ends again the day simply spans the whole worked period, gap
     * included. Route capture restarts — a fresh DPDP consent event, which is
     * why the UI puts a confirmation in front of it.
     */
    suspend fun resumeDay(fix: Fix?) = submit("START", fix, "Resume Day")

    private suspend fun submit(kind: String, fix: Fix?, label: String) {
        val s = session.current() ?: error("Not signed in")
        val payload = SyncPayloads.attendanceEvent(
            id = Uuidv7.next(),
            dcUserId = s.user.id,
            deviceId = s.deviceId,
            kind = kind,
            fix = fix,
            wallIso = Ist.nowIso(),
            monotonicMs = SystemClock.elapsedRealtime(),
        )
        val opType = if (kind == "START") SyncPayloads.OP_ATTENDANCE_START else SyncPayloads.OP_ATTENDANCE_END
        outbox.enqueue(opType, payload, label = label)
        SyncWorker.kick(appContext)

        // Route capture runs strictly Start Day → End Day (DPDP hard stop).
        if (kind == "START") TrackingService.start(appContext) else TrackingService.stop(appContext)
    }

    /** Server truth for hours + provisional km. Null when offline. */
    suspend fun serverSummary(): AttendanceRowDto? {
        val s = session.current() ?: return null
        return try {
            api.attendance(bearer(s.accessToken), Ist.today()).bodyOrThrow()
                .items.firstOrNull { it.dc_user_id == s.user.id }
        } catch (_: Exception) {
            null
        }
    }
}
