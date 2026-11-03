package com.eko.dcvisits.app.ui.team

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eko.dcvisits.app.R
import com.eko.dcvisits.app.data.repo.TeamVisitGroup
import com.eko.dcvisits.app.data.repo.groupTeamVisits
import com.eko.dcvisits.app.ui.components.Entrance
import com.eko.dcvisits.app.ui.components.GlassCard
import com.eko.dcvisits.app.ui.components.GlassTag
import com.eko.dcvisits.app.ui.components.ScreenContainer
import com.eko.dcvisits.app.ui.theme.EkoAmber
import com.eko.dcvisits.app.ui.theme.EkoBlue
import com.eko.dcvisits.app.ui.theme.EkoGreen
import com.eko.dcvisits.app.util.Ist
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import com.eko.dcvisits.app.ui.text.cspLabel

private val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("dd-MM-yyyy")

@Composable
fun TeamVisitsScreen(vm: TeamVisitsViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }
    val isToday = ui.date == LocalDate.parse(Ist.today())
    val groups = remember(ui.visits) { groupTeamVisits(ui.visits) }

    ScreenContainer { pad ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = pad,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Column {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.tab_visits), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
                        if (ui.loading) CircularProgressIndicator(color = EkoBlue, strokeWidth = 2.dp)
                        TextButton(onClick = { vm.load() }) { Text(stringResource(R.string.action_refresh), color = EkoBlue) }
                    }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = vm::previousDay) { Text("◀", color = EkoBlue) }
                        Text(
                            if (isToday) stringResource(R.string.team_today, ui.date.format(DAY)) else ui.date.format(DAY),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = vm::nextDay, enabled = !isToday) { Text("▶", color = if (isToday) MaterialTheme.colorScheme.outline else EkoBlue) }
                    }
                    if (!ui.loading) {
                        val inside = groups.count { it.inside }
                        Text(
                            stringResource(R.string.team_visits_summary, groups.size, inside, groups.size - inside),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (ui.failed) {
                item { Text(stringResource(R.string.team_load_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            } else if (!ui.loading && ui.visits.isEmpty()) {
                item { Text(stringResource(R.string.team_no_visits), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            // One card per DC, their CSPs inside (newest DC activity first).
            val byDc = groups.groupBy { it.first.dc_user_id }.values.toList()
            itemsIndexed(byDc, key = { _, dc -> dc.first().first.dc_user_id }) { i, dc ->
                Entrance(index = i) { DcVisitsCard(dc) }
            }
        }
    }
}

@Composable
private fun DcVisitsCard(visits: List<TeamVisitGroup>) {
    GlassCard(Modifier.fillMaxWidth(), cornerRadius = 20.dp, contentPadding = 16.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(visits.first().first.dc_name ?: "—", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                GlassTag(stringResource(R.string.team_dc_visit_count, visits.size), tint = EkoBlue)
            }
            visits.forEach { CspVisit(it) }
        }
    }
}

@Composable
private fun CspVisit(g: TeamVisitGroup) {
    val v = g.first
    val context = LocalContext.current
    val csp = v.location_code?.let { cspLabel(it, v.location_name) } ?: v.location_name ?: "—"
    Column(
        Modifier.fillMaxWidth().clickable { openMap(context, v.checkin.fix.lat, v.checkin.fix.lng, "${v.dc_name ?: ""} · $csp") },
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            val again = g.repeats.joinToString(", ") { Ist.time(it.checkin.occurred_at) }
            Text(
                stringResource(R.string.team_visit_line, csp, Ist.time(v.checkin.occurred_at)) +
                    (if (again.isEmpty()) "" else " " + stringResource(R.string.team_visit_again, again)),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
            if (g.inside) GlassTag(stringResource(R.string.team_inside), tint = EkoGreen)
            else GlassTag(stringResource(R.string.team_outside), tint = EkoAmber)
        }
        val out = g.checkedOutAt?.let { stringResource(R.string.team_checked_out, Ist.time(it)) } ?: stringResource(R.string.team_still_open)
        Text(
            listOf(stringResource(R.string.team_photos, g.photoCount), out).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}
