package com.eko.dcvisits.app.ui.attendance

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eko.dcvisits.app.data.net.AttendanceRowDto
import com.eko.dcvisits.app.data.net.NearestCspItemDto
import com.eko.dcvisits.app.data.repo.DayState
import com.eko.dcvisits.app.di.ServiceLocator
import com.eko.dcvisits.app.ui.location.LocationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AttendanceUi(
    val busy: Boolean = false,
    val summary: AttendanceRowDto? = null,
    val message: String? = null,
    val lastFixAccuracyM: Double? = null,
    val locationOff: Boolean = false,
    val visitedTodayCount: Int = 0,
    val remainingTodayCount: Int = 0,
    val recommended: NearestCspItemDto? = null,
    val recommendedState: String = "loading",
)

class AttendanceViewModel : ViewModel() {
    private val repo = ServiceLocator.attendanceRepository

    val dayState: StateFlow<DayState> =
        repo.localDayState.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayState.NOT_STARTED)

    /** Home-dashboard counters (UI/UX pass) — read from the same sources every other screen uses. */
    val assignedCount: StateFlow<Int> =
        ServiceLocator.cspRepository.cspFlow.map { it.size }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)
    val pendingSync: StateFlow<Int> =
        ServiceLocator.outboxRepository().pendingCount.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _ui = MutableStateFlow(AttendanceUi())
    val ui: StateFlow<AttendanceUi> = _ui

    fun refresh() {
        viewModelScope.launch {
            val summary = repo.serverSummary()
            _ui.value = _ui.value.copy(summary = summary)

            val visits = ServiceLocator.visitRepository.todayVisits()
            val assigned = assignedCount.value
            val visitedToday = visits?.map { it.location_id }?.toSet()?.size ?: 0
            _ui.value = _ui.value.copy(
                visitedTodayCount = visitedToday,
                remainingTodayCount = (assigned - visitedToday).coerceAtLeast(0),
            )

            val nearest = try {
                ServiceLocator.nearestCspRepository.fetch()
            } catch (_: Exception) {
                null
            }
            _ui.value = _ui.value.copy(
                recommended = nearest?.items?.firstOrNull(),
                recommendedState = nearest?.state ?: "error",
            )
        }
    }

    private enum class Act { START, END, RESUME }

    fun checkIn(context: Context) = act(context, Act.START)
    fun endDay(context: Context) = act(context, Act.END)
    fun resumeDay(context: Context) = act(context, Act.RESUME)

    private fun act(context: Context, which: Act) {
        if (_ui.value.busy) return
        _ui.value = _ui.value.copy(busy = true, message = null)
        viewModelScope.launch {
            val fix = LocationProvider.currentFix(context)
            try {
                when (which) {
                    Act.START -> repo.checkIn(fix)
                    Act.END -> repo.endDay(fix)
                    Act.RESUME -> repo.resumeDay(fix)
                }
                _ui.value = _ui.value.copy(
                    busy = false,
                    message = when (which) {
                        Act.START -> "Checked in — you're on duty"
                        Act.END -> "Day ended"
                        Act.RESUME -> "Day resumed — tracking again"
                    },
                    lastFixAccuracyM = fix?.accuracyM,
                    locationOff = fix == null && !LocationProvider.isLocationEnabled(context),
                )
                refresh()
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(busy = false, message = e.message ?: "Could not record")
            }
        }
    }
}
