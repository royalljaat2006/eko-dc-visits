package com.eko.dcvisits.app.ui.scorecard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eko.dcvisits.app.data.net.ScorecardRowDto
import com.eko.dcvisits.app.di.ServiceLocator
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

data class ScorecardUi(
    val loading: Boolean = true,
    val card: ScorecardRowDto? = null,
    val dashboardUrl: String? = null,
    val error: String? = null,
)

class ScorecardViewModel : ViewModel() {
    private val repo = ServiceLocator.scorecardRepository

    private val _ui = MutableStateFlow(ScorecardUi())
    val ui: StateFlow<ScorecardUi> = _ui

    fun load() {
        _ui.value = _ui.value.copy(loading = true, error = null)
        viewModelScope.launch {
            val url = repo.myDashboardUrl()
            val card = repo.myCard()
            _ui.value = ScorecardUi(
                loading = false,
                card = card,
                dashboardUrl = url,
                error = if (card == null) "Scorecard unavailable offline" else null,
            )
        }
    }
}
