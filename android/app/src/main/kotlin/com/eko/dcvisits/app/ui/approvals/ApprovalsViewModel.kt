package com.eko.dcvisits.app.ui.approvals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eko.dcvisits.app.data.net.CspChangeRequestDto
import com.eko.dcvisits.app.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import com.eko.dcvisits.app.R
import com.eko.dcvisits.app.ui.text.UiText

data class ApprovalsUi(
    val loading: Boolean = true,
    val items: List<CspChangeRequestDto> = emptyList(),
    val message: UiText? = null,
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
                .onFailure { _ui.value = ApprovalsUi(loading = false, message = UiText.messageOr(it, R.string.approvals_load_failed)) }
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
                        message = UiText.res(if (decision == "APPROVED") R.string.approvals_applied else R.string.approvals_rejected),
                    )
                }
                .onFailure { _ui.value = _ui.value.copy(message = UiText.messageOr(it, R.string.approvals_decision_failed))
 }
        }
    }

    fun clearMessage() { _ui.value = _ui.value.copy(message = null) }
}
