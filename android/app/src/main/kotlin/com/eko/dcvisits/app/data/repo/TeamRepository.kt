package com.eko.dcvisits.app.data.repo

import com.eko.dcvisits.app.data.net.ApiClient
import com.eko.dcvisits.app.data.net.AttendanceRowDto
import com.eko.dcvisits.app.data.net.C2Api
import com.eko.dcvisits.app.data.net.DashboardCspDto
import com.eko.dcvisits.app.data.net.LiveLocationRowDto
import com.eko.dcvisits.app.data.net.OverviewResponse
import com.eko.dcvisits.app.data.net.RouteHistoryPointDto
import com.eko.dcvisits.app.data.net.ScorecardRowDto
import com.eko.dcvisits.app.data.net.VisitDto
import com.eko.dcvisits.app.data.net.bearer
import com.eko.dcvisits.app.data.net.bodyOrThrow
import com.eko.dcvisits.app.data.session.SessionStore
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** One DC as the admin's Team tab shows it: attendance, today's counts and last known location. */
data class TeamRow(
    val dcUserId: String,
    val name: String,
    /** NOT_STARTED | ON_DUTY | ENDED | AUTO_CLOSED */
    val attendance: String,
    val startedAt: String?,
    val endedAt: String?,
    val hoursWorked: Double?,
    val kmToday: Double?,
    val visitsToday: Int?,
    val cspCount: Int?,
    /** no_fix | off_duty | stale | live */
    val liveState: String,
    val lastSeenAt: String?,
    val lat: Double?,
    val lng: Double?,
    val accuracyM: Double?,
)

data class TeamSnapshot(
    val overview: OverviewResponse?,
    val rows: List<TeamRow>,
)

/**
 * Merges the three dashboard reads into one row per DC. The overview (admin /
 * national head only) supplies the DC list and today's counts; attendance and
 * live locations fill in times and position. Any part may be missing.
 */
fun buildTeamRows(
    overview: OverviewResponse?,
    attendance: List<AttendanceRowDto>,
    live: List<LiveLocationRowDto>,
): List<TeamRow> {
    val att = attendance.associateBy { it.dc_user_id }
    val loc = live.associateBy { it.dc_user_id }
    val fromOverview = overview?.assignments_by_dc.orEmpty().associateBy { it.dc_user_id }
    val ids = LinkedHashSet<String>().apply {
        addAll(fromOverview.keys)
        addAll(att.keys)
        addAll(loc.keys)
    }
    return ids.map { id ->
        val o = fromOverview[id]
        val a = att[id]
        val l = loc[id]
        TeamRow(
            dcUserId = id,
            name = o?.dc_name ?: a?.dc_name ?: l?.name ?: "—",
            attendance = a?.status ?: o?.attendance ?: "NOT_STARTED",
            startedAt = a?.started_at,
            endedAt = a?.ended_at,
            hoursWorked = a?.hours_worked,
            kmToday = a?.km_today ?: o?.km_today,
            visitsToday = o?.visits_today,
            cspCount = o?.csp_count,
            liveState = l?.state ?: "no_fix",
            lastSeenAt = l?.captured_at,
            lat = l?.lat,
            lng = l?.lng,
            accuracyM = l?.accuracy_m,
        )
    }.sortedWith(compareBy<TeamRow> { attendanceRank(it.attendance) }.thenBy { it.name.lowercase() })
}

/** One DC at one CSP on one day: the first check-in plus any repeats (same rule as the dashboard). */
data class TeamVisitGroup(
    val first: VisitDto,
    val repeats: List<VisitDto>,
) {
    private val all get() = listOf(first) + repeats
    /** Geo-verified if any check-in at that CSP was INSIDE — the server's scorecard rule. */
    val inside: Boolean get() = all.any { it.geofence_result == "INSIDE" }
    val photoCount: Int get() = all.sumOf { it.photo_count }
    /** The latest check-in decides whether the visit is still open. */
    val checkedOutAt: String? get() = all.last().checked_out_at
}

/**
 * Repeat auto check-ins at the same CSP (old app re-firing while the DC sat
 * there) fold into one visit, newest visit first.
 */
fun groupTeamVisits(visits: List<VisitDto>): List<TeamVisitGroup> =
    visits.sortedBy { it.checkin.occurred_at }
        .groupBy { "${it.dc_user_id}|${it.location_id}" }
        .values
        .map { TeamVisitGroup(it.first(), it.drop(1)) }
        .sortedByDescending { it.first.checkin.occurred_at }

private fun attendanceRank(status: String) = when (status) {
    "ON_DUTY" -> 0
    "ENDED", "AUTO_CLOSED" -> 1
    else -> 2
}

/** Read-only team view for CORPORATE_ADMIN / NATIONAL_HEAD — the same server reads the web dashboard uses. */
class TeamRepository(
    private val session: SessionStore,
    private val api: C2Api = ApiClient.api,
) {
    /** Null only when signed out or every read failed (offline). */
    suspend fun load(date: String): TeamSnapshot? {
        val s = session.current() ?: return null
        val auth = bearer(s.accessToken)
        return coroutineScope {
            val overview = async { runCatching { api.overview(auth, date).bodyOrThrow() }.getOrNull() }
            val attendance = async { runCatching { api.attendance(auth, date).bodyOrThrow().items }.getOrNull() }
            val live = async { runCatching { api.liveLocations(auth, LIVE_WINDOW_S).bodyOrThrow().items }.getOrNull() }
            val o = overview.await()
            val a = attendance.await()
            val l = live.await()
            if (o == null && a == null && l == null) null
            else TeamSnapshot(o, buildTeamRows(o, a.orEmpty(), l.orEmpty()))
        }
    }

    suspend fun visits(date: String): List<VisitDto>? {
        val s = session.current() ?: return null
        return runCatching { api.visits(bearer(s.accessToken), date).bodyOrThrow().items }.getOrNull()
            ?.sortedByDescending { it.checkin.occurred_at }
    }

    /** Every DC's dc_score_v1 card for the day, best first. */
    suspend fun scorecard(date: String): List<ScorecardRowDto>? {
        val s = session.current() ?: return null
        return runCatching { api.scorecard(bearer(s.accessToken), date).bodyOrThrow().items }.getOrNull()
            ?.sortedByDescending { it.points }
    }

    /** All CSPs in scope with their DC (null = unassigned), in the calling sheet's order. */
    suspend fun csps(): List<DashboardCspDto>? {
        val s = session.current() ?: return null
        return runCatching { api.dashboardCsps(bearer(s.accessToken)).bodyOrThrow().items }.getOrNull()
    }

    /** One DC's track points for the day, oldest first — drawn as their route on the Live Map. */
    suspend fun route(date: String, dcUserId: String): List<RouteHistoryPointDto>? {
        val s = session.current() ?: return null
        return runCatching { api.routeHistory(bearer(s.accessToken), date, dcUserId).bodyOrThrow().points }.getOrNull()
            ?.sortedBy { it.t }
    }

    companion object {
        /** The app sends points every ~3 min; a 5-min window keeps a moving DC "live". */
        const val LIVE_WINDOW_S = 300
    }
}
