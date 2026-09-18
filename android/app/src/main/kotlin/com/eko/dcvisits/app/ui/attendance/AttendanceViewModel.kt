package com.eko.dcvisits.app.ui.attendance

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eko.dcvisits.app.data.net.AttendanceRowDto
import com.eko.dcvisits.app.data.repo.DayState
import com.eko.dcvisits.app.di.ServiceLocator
import com.eko.dcvisits.app.ui.location.LocationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class AttendanceUi(
    val busy: Boolean = false,
    val summary: AttendanceRowDto? = null,
    val message: String? = null,
    val lastFixAccuracyM: Double? = null,
    val locationOff: Boolean = false,
)

class AttendanceViewModel : ViewModel() {
    private val repo = ServiceLocator.attendanceRepository

    val dayState: StateFlow<DayState> =
        repo.localDayState.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DayState.NOT_STARTED)

    private val _ui = MutableStateFlow(AttendanceUi())
    val ui: StateFlow<AttendanceUi> = _ui

    fun refresh() {
        viewModelScope.launch {
            val summary = repo.serverSummary()
            _ui.value = _ui.value.copy(summary = summary)
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
