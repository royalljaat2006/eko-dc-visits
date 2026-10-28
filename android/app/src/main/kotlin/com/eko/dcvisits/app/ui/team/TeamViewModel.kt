package com.eko.dcvisits.app.ui.team

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.eko.dcvisits.app.data.net.VisitDto
import com.eko.dcvisits.app.data.repo.TeamSnapshot
import com.eko.dcvisits.app.di.ServiceLocator
import com.eko.dcvisits.app.util.Ist
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

data class TeamUi(
    val loading: Boolean = true,
    val team: TeamSnapshot? = null,
    val failed: Boolean = false,
    val updatedAt: String? = null,
)

data class TeamVisitsUi(
    val date: LocalDate = LocalDate.parse(Ist.today()),
    val loading: Boolean = true,
    val visits: List<VisitDto> = emptyList(),
    val failed: Boolean = false,
)

/** Admin / national-head view of every DC — read-only, today only. */
class TeamViewModel : ViewModel() {
    private val repo = ServiceLocator.teamRepository

    private val _ui = MutableStateFlow(TeamUi())
    val ui: StateFlow<TeamUi> = _ui

    fun refresh() {
        _ui.value = _ui.value.copy(loading = true)
        viewModelScope.launch {
            val snap = repo.load(Ist.today())
            _ui.value = if (snap != null) {
                TeamUi(loading = false, team = snap, updatedAt = Ist.nowIso())
            } else {
                _ui.value.copy(loading = false, failed = true) // keep the last good data on screen
            }
        }
    }
}

/** Every DC's visits for one day; the date can be stepped back to see earlier days. */
class TeamVisitsViewModel : ViewModel() {
    private val repo = ServiceLocator.teamRepository

    private val _ui = MutableStateFlow(TeamVisitsUi())
    val ui: StateFlow<TeamVisitsUi> = _ui

    fun load(date: LocalDate = _ui.value.date) {
        _ui.value = _ui.value.copy(date = date, loading = true, failed = false)
        viewModelScope.launch {
            val list = repo.visits(date.toString())
            if (_ui.value.date != date) return@launch // a newer day was picked meanwhile
            _ui.value = _ui.value.copy(loading = false, visits = list.orEmpty(), failed = list == null)
        }
    }

    fun previousDay() = load(_ui.value.date.minusDays(1))

    fun nextDay() {
        val today = LocalDate.parse(Ist.today())
        if (_ui.value.date < today) load(_ui.value.date.plusDays(1))
    }
}
