package com.eko.dcvisits.app.ui.csp

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eko.dcvisits.app.data.db.CspCacheEntity
import com.eko.dcvisits.app.data.sync.Fix
import com.eko.dcvisits.app.di.ServiceLocator
import com.eko.dcvisits.app.ui.location.LocationProvider
import com.eko.dcvisits.core.geo.haversineMeters
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class CspDetailsViewModel : ViewModel() {
    private val repo = ServiceLocator.cspRepository

    /** Whitelisted change-request fields — mirrors backend CR_CORE_FIELDS + CR_PROFILE_FIELDS. */
    val editableFields = listOf(
        "name", "address", "lat", "lng",
        "gender", "csp_mail_id", "mobile_number", "alternative_mobile_number",
        "relationship_manager", "district", "state", "ao", "ao_email",
        "branch_code", "branch_name", "branch_email", "rbo_name", "rbo_email",
        "circle_head_name", "lho_name", "lho_mail_id", "population", "pin_code",
    )

    val csps: StateFlow<List<CspCacheEntity>> =
        repo.cspFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _fix = MutableStateFlow<Fix?>(null)
    val fix: StateFlow<Fix?> = _fix

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    fun start(context: Context) {
        viewModelScope.launch { repo.refresh() }
        viewModelScope.launch { _fix.value = LocationProvider.currentFix(context) }
    }

    fun refresh(context: Context) {
        _busy.value = true
        viewModelScope.launch {
            repo.refresh()
            _fix.value = LocationProvider.currentFix(context)
            _busy.value = false
        }
    }

    fun distanceMeters(csp: CspCacheEntity): Double? {
        val f = _fix.value ?: return null
        return haversineMeters(f.lat, f.lng, csp.lat, csp.lng)
    }

    /** The §3.1 master-template fields cached with this CSP (from /dc/csp-details csp_profile). */
    fun profileOf(csp: CspCacheEntity): Map<String, String> = runCatching {
        com.eko.dcvisits.app.data.net.ApiClient.json
            .decodeFromString(kotlinx.serialization.json.JsonObject.serializer(), csp.profileJson)
            .mapNotNull { (k, v) ->
                val p = v as? kotlinx.serialization.json.JsonPrimitive ?: return@mapNotNull null
                if (p is kotlinx.serialization.json.JsonNull) null else k to p.content
            }
            .toMap()
    }.getOrDefault(emptyMap())

    fun proposeEdit(cspLocationId: String, field: String, value: String) {
        if (_busy.value) return
        _busy.value = true
        _message.value = null
        viewModelScope.launch {
            try {
                repo.proposeEdit(cspLocationId, field, value.trim())
                _message.value = "Change submitted — pending Circle Head approval"
            } catch (e: Exception) {
                _message.value = e.message ?: "Could not submit the change"
            }
            _busy.value = false
        }
    }

    fun clearMessage() { _message.value = null }
}
