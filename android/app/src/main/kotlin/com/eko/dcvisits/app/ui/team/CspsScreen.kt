package com.eko.dcvisits.app.ui.team

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eko.dcvisits.app.R
import com.eko.dcvisits.app.data.net.DashboardCspDto
import com.eko.dcvisits.app.ui.components.GlassCard
import com.eko.dcvisits.app.ui.components.GlassTag
import com.eko.dcvisits.app.ui.components.GlassTextField
import com.eko.dcvisits.app.ui.components.ScreenContainer
import com.eko.dcvisits.app.ui.theme.EkoAmber
import com.eko.dcvisits.app.ui.theme.EkoBlue
import com.eko.dcvisits.app.ui.text.cspLabel

/** Admin "All CSPs": search by code, address, district, circle or DC; filter to unassigned. */
@Composable
fun CspsScreen(vm: TeamCspsViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }
    var query by rememberSaveable { mutableStateOf("") }
    var unassignedOnly by rememberSaveable { mutableStateOf(false) }
    val shown = remember(ui.csps, query, unassignedOnly) { filterCsps(ui.csps, query, unassignedOnly) }
    val unassigned = remember(ui.csps) { ui.csps.count { it.dc_user_id == null } }
    // "Near me" exists only when the server granted it to this person; it opens first.
    val nearSource = ui.nearbySource
    var nearMode by rememberSaveable { mutableStateOf(true) }
    val modeSwitch: @Composable () -> Unit = {
        if (nearSource != null) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = nearMode, onClick = { nearMode = true }, label = { Text(stringResource(R.string.nearby_tab)) })
                FilterChip(selected = !nearMode, onClick = { nearMode = false }, label = { Text(stringResource(R.string.nearby_all_tab)) })
            }
        }
    }

    ScreenContainer { pad ->
        if (nearSource != null && nearMode) {
            NearbyCspsView(nearSource, pad) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.nearby_title), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onBackground)
                    modeSwitch()
                }
            }
            return@ScreenContainer
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pad, verticalArrangement = Arrangement.spacedBy(10.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    modeSwitch()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.admin_csps_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
                        if (ui.loading) CircularProgressIndicator(color = EkoBlue, strokeWidth = 2.dp)
                        TextButton(onClick = vm::load) { Text(stringResource(R.string.action_refresh), color = EkoBlue) }
                    }
                    if (!ui.loading && !ui.failed) {
                        Text(
                            stringResource(R.string.admin_csps_summary, ui.csps.size, unassigned),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    GlassTextField(value = query, onValueChange = { query = it }, label = stringResource(R.string.admin_csps_search), modifier = Modifier.fillMaxWidth())
                    FilterChip(
                        selected = unassignedOnly,
                        onClick = { unassignedOnly = !unassignedOnly },
                        label = { Text(stringResource(R.string.admin_csps_unassigned_only)) },
                    )
                    if (query.isNotBlank() || unassignedOnly) {
                        Text(stringResource(R.string.admin_csps_showing, shown.size), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (ui.failed) {
                item { Text(stringResource(R.string.team_load_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            items(shown, key = { it.csp_location_id }) { CspRow(it) }
        }
    }
}

@Composable
private fun CspRow(c: DashboardCspDto) {
    GlassCard(Modifier.fillMaxWidth(), cornerRadius = 16.dp, contentPadding = 12.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(cspLabel(c.code, c.name), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                if (c.dc_name != null) GlassTag(c.dc_name, tint = EkoBlue)
                else GlassTag(stringResource(R.string.admin_csps_no_dc), tint = EkoAmber)
            }
            val where = listOfNotNull(c.address?.takeIf { it.isNotBlank() }, c.district, c.circle).joinToString(" · ")
            if (where.isNotEmpty()) {
                Text(where, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Case-insensitive match on every field an admin would search by. */
internal fun filterCsps(csps: List<DashboardCspDto>, query: String, unassignedOnly: Boolean): List<DashboardCspDto> {
    val needle = query.trim().lowercase()
    return csps.filter { c ->
        (!unassignedOnly || c.dc_user_id == null) &&
            (needle.isEmpty() || listOfNotNull(c.code, c.name, c.address, c.district, c.state, c.circle, c.dc_name).any { it.lowercase().contains(needle) })
    }
}
