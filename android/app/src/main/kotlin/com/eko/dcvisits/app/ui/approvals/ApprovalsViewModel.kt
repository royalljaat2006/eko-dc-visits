package com.eko.dcvisits.app.ui.approvals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eko.dcvisits.app.data.net.CspChangeRequestDto
import com.eko.dcvisits.app.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ApprovalsUi(
    val loading: Boolean = true,
    val items: List<CspChangeRequestDto> = emptyList(),
    val message: String? = null,
)

class ApprovalsViewModel : ViewModel() {
    private val repo = ServiceLocator.approvalsRepository

    private val _ui = MutableStateFlow(ApprovalsUi())
    val ui: StateFlow<ApprovalsUi> = _ui

    fun load() {
        _ui.value = _ui.value.copy(loading = true, message = null)
        viewModelScope.launch {
            runCatching { repo.queue("PENDING") }
                .onSuccess { _ui.value = ApprovalsUi(loading = false, items = it) }
                .onFailure { _ui.value = ApprovalsUi(loading = false, message = it.message ?: "Could not load the queue") }
        }
    }

    fun approve(id: String) = decide(id, "APPROVED", null)
    fun reject(id: String, reason: String) = decide(id, "REJECTED", reason.ifBlank { "Rejected" })

    private fun decide(id: String, decision: String, reason: String?) {
        viewModelScope.launch {
            runCatching { repo.decide(id, decision, reason) }
                .onSuccess {
                    _ui.value = _ui.value.copy(
                        items = _ui.value.items.filterNot { r -> r.id == id },
                        message = if (decision == "APPROVED") "Applied to the CSP master" else "Rejected — DC notified",
                    )
                }
                .onFailure { _ui.value = _ui.value.copy(message = it.message ?: "Decision failed") }
        }
    }

    fun clearMessage() { _ui.value = _ui.value.copy(message = null) }
}
