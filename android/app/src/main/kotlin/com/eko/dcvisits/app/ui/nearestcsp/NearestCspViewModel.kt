package com.eko.dcvisits.app.ui.nearestcsp

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eko.dcvisits.app.data.net.ApiException
import com.eko.dcvisits.app.data.net.NearestCspItemDto
import com.eko.dcvisits.app.di.ServiceLocator
import com.eko.dcvisits.app.ui.location.LocationProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Mirrors the server's /dc/nearest-csp `state` field 1:1 (C2 v0.12.0). */
enum class NearestCspState { LOADING, INACTIVE_SESSION, NO_ELIGIBLE_CSP, NO_FIX, STALE_GPS, OK, ERROR }

data class NearestCspUiState(
    val state: NearestCspState = NearestCspState.LOADING,
    val items: List<NearestCspItemDto> = emptyList(),
    val errorMessage: String? = null,
    /** The fix this recommendation was computed from, if the device could get one just now. */
    val fixAccuracyM: Double? = null,
    val lastUpdatedAtMs: Long? = null,
)

class NearestCspViewModel : ViewModel() {
    private val repo = ServiceLocator.nearestCspRepository

    private val _ui = MutableStateFlow(NearestCspUiState())
    val ui: StateFlow<NearestCspUiState> = _ui

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    /** A fresh fix is preferred; the server falls back to the last synced track point when none is supplied. */
    fun refresh(context: Context) {
        if (_busy.value) return
        _busy.value = true
        viewModelScope.launch {
            val fix = LocationProvider.currentFix(context)
            try {
                val res = repo.fetch(fix?.lat, fix?.lng, fix?.accuracyM)
                val state = when (res.state) {
                    "inactive_session" -> NearestCspState.INACTIVE_SESSION
                    "no_eligible_csp" -> NearestCspState.NO_ELIGIBLE_CSP
                    "no_fix" -> NearestCspState.NO_FIX
                    "stale_gps" -> NearestCspState.STALE_GPS
                    else -> NearestCspState.OK
                }
                _ui.value = NearestCspUiState(
                    state = state,
                    items = res.items,
                    fixAccuracyM = fix?.accuracyM,
                    lastUpdatedAtMs = System.currentTimeMillis(),
                )
            } catch (e: ApiException) {
                _ui.value = NearestCspUiState(state = NearestCspState.ERROR, errorMessage = e.message)
            } catch (e: Exception) {
                _ui.value = NearestCspUiState(state = NearestCspState.ERROR, errorMessage = e.message ?: "Could not load nearest CSP")
            }
            _busy.value = false
        }
    }
}
