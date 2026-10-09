package com.eko.dcvisits.app.ui.logi

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eko.dcvisits.app.data.db.CspCacheEntity
import com.eko.dcvisits.app.data.net.ApiClient
import com.eko.dcvisits.app.data.net.NearestCspItemDto
import com.eko.dcvisits.app.data.net.VisitDto
import com.eko.dcvisits.app.data.net.bearer
import com.eko.dcvisits.app.data.net.bodyOrThrow
import com.eko.dcvisits.app.data.repo.DayState
import com.eko.dcvisits.app.data.sync.Fix
import com.eko.dcvisits.app.di.ServiceLocator
import com.eko.dcvisits.app.ui.location.LocationProvider
import com.eko.dcvisits.app.util.Ist
import com.eko.dcvisits.core.geo.haversineMeters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One itinerary row — distance-ranked, never an optimized multi-stop route (no routing provider, ADR-0005). */
data class LogiStop(
    val csp: CspCacheEntity,
    val sequence: Int,
    val distanceM: Double?,
    val visitedToday: Boolean,
)

data class LogiUiState(
    val loading: Boolean = true,
    /** The last refresh either never reached the server or the device has no connectivity signal. */
    val stale: Boolean = false,
    val dayState: DayState = DayState.NOT_STARTED,
    val assignedCount: Int = 0,
    val visitedTodayCount: Int = 0,
    val pendingCount: Int = 0,
    val openVisit: VisitDto? = null,
    val stops: List<LogiStop> = emptyList(),
    val recommended: NearestCspItemDto? = null,
    val recommendedState: String = "loading",
    val fixAccuracyM: Double? = null,
    val routePointsToday: Int? = null,
    /** track_straightline_v0 — PROVISIONAL, GPS-derived, never for reimbursement (same figure Home shows). */
    val kmToday: Double? = null,
)

class LogiViewModel : ViewModel() {
    private val cspRepo = ServiceLocator.cspRepository
    private val visitRepo = ServiceLocator.visitRepository
    private val nearestRepo = ServiceLocator.nearestCspRepository
    private val attendanceRepo = ServiceLocator.attendanceRepository
    private val session = ServiceLocator.sessionStore

    val dayState: StateFlow<DayState> =
        attendanceRepo.localDayState.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayState.NOT_STARTED)
    val pendingSync: StateFlow<Int> =
        ServiceLocator.outboxRepository().pendingCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _ui = MutableStateFlow(LogiUiState())
    val ui: StateFlow<LogiUiState> = _ui

    fun refresh(context: Context) {
        _ui.value = _ui.value.copy(loading = true)
        viewModelScope.launch {
            cspRepo.refresh()
            val assigned = cspRepo.cspFlow.first()
            val visits = visitRepo.todayVisits()
            val fix = LocationProvider.currentFix(context)
            val nearest = try {
                nearestRepo.fetch(fix?.lat, fix?.lng, fix?.accuracyM)
            } catch (_: Exception) {
                null
            }
            val routePoints = try {
                routeHistoryCountToday()
            } catch (_: Exception) {
                null
            }
            val summary = attendanceRepo.serverSummary()

            val today = Ist.today()
            val visitedIds = (visits ?: emptyList()).map { it.location_id }.toSet()
            val stops = assigned
                .map { csp ->
                    val distance = fix?.let { haversineMeters(it.lat, it.lng, csp.lat, csp.lng) }
                    csp to distance
                }
                .sortedBy { (_, d) -> d ?: Double.MAX_VALUE }
                .mapIndexed { i, (csp, d) -> LogiStop(csp, i + 1, d, csp.cspLocationId in visitedIds || csp.lastVisitDate == today) }

            _ui.value = _ui.value.copy(
                loading = false,
                stale = visits == null,
                assignedCount = assigned.size,
                visitedTodayCount = stops.count { it.visitedToday },
                openVisit = visits?.firstOrNull { it.checked_out_at == null },
                stops = stops,
                recommended = nearest?.items?.firstOrNull(),
                recommendedState = nearest?.state ?: "error",
                fixAccuracyM = fix?.accuracyM,
                routePointsToday = routePoints,
                kmToday = summary?.km_today,
            )
        }
    }

    private suspend fun routeHistoryCountToday(): Int? {
        val s = session.current() ?: return null
        return ApiClient.api.routeHistory(bearer(s.accessToken), Ist.today(), s.user.id).bodyOrThrow().points.size
    }
}
