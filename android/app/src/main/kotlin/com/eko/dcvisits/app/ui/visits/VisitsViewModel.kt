package com.eko.dcvisits.app.ui.visits

import android.content.Context
import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eko.dcvisits.app.data.db.CspCacheEntity
import com.eko.dcvisits.app.data.net.VisitDto
import com.eko.dcvisits.app.data.repo.GeoAdvisory
import com.eko.dcvisits.app.data.sync.Fix
import com.eko.dcvisits.app.di.ServiceLocator
import com.eko.dcvisits.app.ui.location.LocationProvider
import com.eko.dcvisits.app.data.sync.SyncPayloads
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** Out-of-radius reason codes — must match checkin-event.schema.json. */
val OUT_OF_RADIUS_REASONS = listOf(
    "INSIDE_PREMISES_GPS_WEAK" to "Inside the shop, GPS weak",
    "CSP_RELOCATED" to "CSP has moved",
    "MASTER_PIN_WRONG" to "Saved location is wrong",
    "OTHER" to "Other",
)

/** Photo slots — must match visit-photo.schema.json `category`. */
val PHOTO_CATEGORIES = listOf("SHOPFRONT", "INSIDE", "QR_DEVICE", "BRANDING")

data class VisitsUi(
    val busy: Boolean = false,
    val message: String? = null,
    val todayVisits: List<VisitDto> = emptyList(),
    /** A best-effort fix taken on refresh, purely for the list's distance/geofence chips. */
    val currentFix: Fix? = null,
    /** Set when a check-in is outside the effective radius and needs a reason first. */
    val awaitingReasonFor: CspCacheEntity? = null,
    val awaitingReasonDistanceM: Double = 0.0,
    /** After a successful check-in: prompt to attach photos for this visit id. */
    val awaitingPhotoFor: String? = null,
    val awaitingPhotoCsp: String = "",
    /** category -> captured thumbnail, this session only (not persisted — mirrors photosAdded before it). */
    val capturedPhotos: Map<String, Bitmap> = emptyMap(),
    /** Non-null → show the in-app camera for this photo category. */
    val cameraCategory: String? = null,
)

class VisitsViewModel : ViewModel() {
    private val visits = ServiceLocator.visitRepository
    private val csps = ServiceLocator.cspRepository
    private val photos = ServiceLocator.photoRepository
    private var lastFix: Fix? = null

    val assignedCsps: StateFlow<List<CspCacheEntity>> =
        csps.cspFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val pendingCheckins: StateFlow<Int> =
        ServiceLocator.outboxRepository().recent
            .map { rows ->
                rows.count {
                    it.type == SyncPayloads.OP_VISIT_CHECKIN && it.state in setOf("PENDING", "IN_FLIGHT")
                }
            }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _ui = MutableStateFlow(VisitsUi())
    val ui: StateFlow<VisitsUi> = _ui

    fun refresh(context: Context? = null) {
        viewModelScope.launch {
            csps.refresh()
            visits.todayVisits()?.let { list -> _ui.value = _ui.value.copy(todayVisits = list) }
            // Best-effort: powers the list's distance/geofence chips only, never blocks anything (ADR-0004).
            if (context != null) {
                LocationProvider.currentFix(context)?.let { fix -> _ui.value = _ui.value.copy(currentFix = fix) }
            }
        }
    }

    /** Same advisory math the check-in flow uses — keeps the list's chips consistent with what commit() will do. */
    fun advisory(csp: CspCacheEntity, fix: Fix): GeoAdvisory = visits.advisory(csp, fix)

    /** Step 1: attempt a check-in. Outside the radius → ask for a reason and pause. */
    fun checkIn(context: Context, csp: CspCacheEntity) {
        if (_ui.value.busy) return
        _ui.value = _ui.value.copy(busy = true, message = null)
        viewModelScope.launch {
            val fix = LocationProvider.currentFix(context)
            if (fix == null) {
                _ui.value = _ui.value.copy(
                    busy = false,
                    message = if (LocationProvider.isLocationEnabled(context))
                        "Getting a GPS fix failed — try again in the open." else
                        "Turn on location to check in (a fix is required).",
                )
                return@launch
            }
            val advisory = visits.advisory(csp, fix)
            if (!advisory.inside) {
                _ui.value = _ui.value.copy(
                    busy = false,
                    awaitingReasonFor = csp,
                    awaitingReasonDistanceM = advisory.distanceM,
                )
                return@launch
            }
            commit(csp, fix, reason = null, remarks = null)
        }
    }

    /** Step 2: reason chosen for an out-of-radius check-in. */
    fun confirmOutOfRadius(context: Context, reason: String, remarks: String?) {
        val csp = _ui.value.awaitingReasonFor ?: return
        _ui.value = _ui.value.copy(busy = true, awaitingReasonFor = null)
        viewModelScope.launch {
            val fix = LocationProvider.currentFix(context) ?: run {
                _ui.value = _ui.value.copy(busy = false, message = "Lost the GPS fix — try again.")
                return@launch
            }
            commit(csp, fix, reason, remarks)
        }
    }

    fun cancelReason() { _ui.value = _ui.value.copy(awaitingReasonFor = null, busy = false) }

    fun clearMessage() { _ui.value = _ui.value.copy(message = null) }

    // ---- photos ---------------------------------------------------------

    fun openCamera(category: String) { _ui.value = _ui.value.copy(cameraCategory = category) }
    fun cancelCamera() { _ui.value = _ui.value.copy(cameraCategory = null) }

    /** Closes the open visit — with or without photos attached first. */
    fun checkOut() {
        val visitId = _ui.value.awaitingPhotoFor ?: return
        if (_ui.value.busy) return
        _ui.value = _ui.value.copy(busy = true)
        viewModelScope.launch {
            try {
                visits.checkOut(visitId, lastFix)
                _ui.value = _ui.value.copy(
                    busy = false,
                    awaitingPhotoFor = null,
                    awaitingPhotoCsp = "",
                    capturedPhotos = emptyMap(),
                    message = "Checked out — will sync",
                )
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(busy = false, message = e.message ?: "Could not check out")
            }
        }
    }

    fun onPhotoCaptured(bitmap: Bitmap) {
        val visitId = _ui.value.awaitingPhotoFor ?: return
        val category = _ui.value.cameraCategory ?: return
        _ui.value = _ui.value.copy(cameraCategory = null, busy = true)
        viewModelScope.launch {
            try {
                photos.capture(visitId, _ui.value.awaitingPhotoCsp, category, bitmap, lastFix)
                _ui.value = _ui.value.copy(
                    busy = false,
                    capturedPhotos = _ui.value.capturedPhotos + (category to bitmap),
                    message = "$category photo queued",
                )
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(busy = false, message = e.message ?: "Could not attach the photo")
            }
        }
    }

    private suspend fun commit(csp: CspCacheEntity, fix: Fix, reason: String?, remarks: String?) {
        try {
            val visitId = visits.checkIn(csp, fix, reason, remarks)
            lastFix = fix
            _ui.value = _ui.value.copy(
                busy = false,
                message = "Visit to ${csp.code} recorded — will sync",
                awaitingPhotoFor = visitId,
                awaitingPhotoCsp = csp.code,
                capturedPhotos = emptyMap(),
            )
            refresh()
        } catch (e: Exception) {
            _ui.value = _ui.value.copy(busy = false, message = e.message ?: "Could not record the visit")
        }
    }
}
