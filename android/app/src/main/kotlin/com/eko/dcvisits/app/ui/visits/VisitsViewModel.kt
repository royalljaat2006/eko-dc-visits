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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import com.eko.dcvisits.app.data.net.ApiClient
import com.eko.dcvisits.app.util.Ist
import androidx.annotation.StringRes
import com.eko.dcvisits.app.R
import com.eko.dcvisits.app.ui.text.UiText

/**
 * Out-of-radius reason codes — must match checkin-event.schema.json. The code is
 * what is sent; only the display label (a string resource) is translated.
 */
val OUT_OF_RADIUS_REASONS: List<Pair<String, Int>> = listOf(
    "INSIDE_PREMISES_GPS_WEAK" to R.string.reason_inside_premises_gps_weak,
    "CSP_RELOCATED" to R.string.reason_csp_relocated,
    "MASTER_PIN_WRONG" to R.string.reason_master_pin_wrong,
    "OTHER" to R.string.reason_other,
)

/** Photo slots — must match visit-photo.schema.json `category`. */
val PHOTO_CATEGORIES = listOf("SHOPFRONT", "INSIDE", "QR_DEVICE", "BRANDING")

/** Display label for a photo category code (the code itself is what is sent and watermarked). */
@StringRes
fun photoCategoryLabel(category: String): Int? = when (category) {
    "SHOPFRONT" -> R.string.photo_category_shopfront
    "INSIDE" -> R.string.photo_category_inside
    "QR_DEVICE" -> R.string.photo_category_qr_device
    "BRANDING" -> R.string.photo_category_branding
    else -> null
}

fun photoCategoryText(category: String): UiText =
    photoCategoryLabel(category)?.let { UiText.res(it) } ?: UiText.Raw(category.replace('_', ' '))

data class VisitsUi(
    val busy: Boolean = false,
    val message: UiText? = null,
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
        ServiceLocator.outboxRepository().pendingCheckinCount
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), 0)

    private val _ui = MutableStateFlow(VisitsUi())
    val ui: StateFlow<VisitsUi> = _ui

    init {
        // The open visit used to live only in memory: after the app was closed or
        // killed, the DC could neither add photos nor check out. Rebuild it from the
        // outbox — the latest check-in of today (manual or auto) with no check-out.
        viewModelScope.launch { restoreOpenVisit() }
    }

    private suspend fun restoreOpenVisit() {
        if (_ui.value.awaitingPhotoFor != null) return
        val (visitId, locationId) = findOpenVisit() ?: return
        val code = locationId?.let { csps.byId(it)?.code } ?: ""
        _ui.value = _ui.value.copy(awaitingPhotoFor = visitId, awaitingPhotoCsp = code)
    }

    /** Today's latest check-in (manual or auto) with no check-out, as (visit id, location id). */
    private suspend fun findOpenVisit(): Pair<String, String?>? {
        val rows = ServiceLocator.outboxRepository().dayOps.first()
        val today = Ist.today()
        fun payloadOf(json: String) = runCatching { ApiClient.json.decodeFromString(JsonObject.serializer(), json) }.getOrNull()
        val closed = rows.filter { it.type == SyncPayloads.OP_VISIT_CHECKOUT }
            .mapNotNull { payloadOf(it.payload)?.get("visit_id")?.jsonPrimitive?.content }
            .toSet()
        val open = rows.asSequence()
            .filter { it.type == SyncPayloads.OP_VISIT_CHECKIN }
            .mapNotNull { payloadOf(it.payload) } // rows are newest first
            .firstOrNull { p ->
                val wall = p["timestamps"]?.jsonObject?.get("device_wall_time")?.jsonPrimitive?.content
                wall != null && Ist.date(java.time.Instant.parse(wall)) == today &&
                    p["id"]?.jsonPrimitive?.content !in closed
            } ?: return null
        val visitId = open["id"]?.jsonPrimitive?.content ?: return null
        return visitId to open["location_id"]?.jsonPrimitive?.content
    }

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
            // An auto check-in may already have opened this visit while the screen was up:
            // reopen it instead of logging a second visit at the same CSP.
            findOpenVisit()?.takeIf { it.second == csp.cspLocationId }?.let { (visitId, _) ->
                _ui.value = _ui.value.copy(
                    busy = false,
                    awaitingPhotoFor = visitId,
                    awaitingPhotoCsp = csp.code,
                    message = UiText.res(R.string.visits_already_checked_in, csp.code),
                )
                return@launch
            }
            val fix = LocationProvider.currentFix(context)
            if (fix == null) {
                _ui.value = _ui.value.copy(
                    busy = false,
                    message = UiText.res(
                        if (LocationProvider.isLocationEnabled(context)) R.string.visits_gps_fix_failed
                        else R.string.visits_turn_on_location,
                    ),
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
                _ui.value = _ui.value.copy(busy = false, message = UiText.res(R.string.visits_lost_fix))
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
                    message = UiText.res(R.string.visits_checked_out),
                )
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(busy = false, message = UiText.messageOr(e, R.string.visits_could_not_check_out))
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
                    message = UiText.res(R.string.visits_photo_queued, photoCategoryText(category)),
                )
            } catch (e: Exception) {
                _ui.value = _ui.value.copy(busy = false, message = UiText.messageOr(e, R.string.visits_could_not_attach_photo))
            }
        }
    }

    private suspend fun commit(csp: CspCacheEntity, fix: Fix, reason: String?, remarks: String?) {
        try {
            val visitId = visits.checkIn(csp, fix, reason, remarks)
            lastFix = fix
            _ui.value = _ui.value.copy(
                busy = false,
                message = UiText.res(R.string.visits_recorded, csp.code),
                awaitingPhotoFor = visitId,
                awaitingPhotoCsp = csp.code,
                capturedPhotos = emptyMap(),
            )
            refresh()
        } catch (e: Exception) {
            _ui.value = _ui.value.copy(busy = false, message = UiText.messageOr(e, R.string.visits_could_not_record))

        }
    }
}
