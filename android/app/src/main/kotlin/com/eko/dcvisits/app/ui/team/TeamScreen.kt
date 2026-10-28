package com.eko.dcvisits.app.ui.team

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eko.dcvisits.app.R
import com.eko.dcvisits.app.data.repo.TeamRow
import com.eko.dcvisits.app.tracking.DutyNotificationText
import com.eko.dcvisits.app.tracking.StringLookup
import com.eko.dcvisits.app.ui.components.Entrance
import com.eko.dcvisits.app.ui.components.GlassCard
import com.eko.dcvisits.app.ui.components.GlassTag
import com.eko.dcvisits.app.ui.components.ScreenContainer
import com.eko.dcvisits.app.ui.theme.EkoAmber
import com.eko.dcvisits.app.ui.theme.EkoBlue
import com.eko.dcvisits.app.ui.theme.EkoGreen
import com.eko.dcvisits.app.util.Ist
import kotlinx.coroutines.delay
import java.time.Duration
import java.time.Instant
import java.util.Locale

private const val AUTO_REFRESH_MS = 30_000L

@Composable
fun TeamScreen(vm: TeamViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        while (true) {
            vm.refresh()
            delay(AUTO_REFRESH_MS)
        }
    }
    val rows = ui.team?.rows.orEmpty()
    val o = ui.team?.overview

    ScreenContainer { pad ->
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = pad,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(stringResource(R.string.team_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
                        ui.updatedAt?.let {
                            Text(stringResource(R.string.team_updated, Ist.time(it)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    if (ui.loading) CircularProgressIndicator(Modifier.padding(end = 8.dp), color = EkoBlue, strokeWidth = 2.dp)
                    TextButton(onClick = vm::refresh) { Text(stringResource(R.string.action_refresh), color = EkoBlue) }
                }
            }
            if (ui.failed) {
                item { Text(stringResource(R.string.team_load_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            }
            o?.let { ov ->
                item {
                    Entrance(index = 0) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Kpi(stringResource(R.string.team_kpi_on_duty), "${ov.attendance.on_duty}/${ov.attendance.total_dcs}", EkoGreen, Modifier.weight(1f))
                            Kpi(stringResource(R.string.team_kpi_visits), "${ov.visits.total}", EkoBlue, Modifier.weight(1f))
                            Kpi(stringResource(R.string.team_kpi_flagged), "${ov.visits.flagged}", EkoAmber, Modifier.weight(1f))
                            Kpi(stringResource(R.string.team_kpi_unassigned), "${ov.csps.unassigned}", EkoAmber, Modifier.weight(1f))
                        }
                    }
                }
            }
            if (!ui.loading && ui.team != null && rows.isEmpty()) {
                item { Text(stringResource(R.string.team_empty), color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            itemsIndexed(rows, key = { _, r -> r.dcUserId }) { i, r ->
                Entrance(index = i + 1) { DcCard(r) }
            }
        }
    }
}

@Composable
private fun Kpi(label: String, value: String, tint: Color, modifier: Modifier = Modifier) {
    GlassCard(modifier, cornerRadius = 16.dp, contentPadding = 10.dp) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
            Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = tint)
            Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

@Composable
private fun DcCard(r: TeamRow) {
    val context = LocalContext.current
    val str = StringLookup.of(context)
    GlassCard(Modifier.fillMaxWidth(), cornerRadius = 20.dp, contentPadding = 16.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(r.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                val (label, tint) = when (r.attendance) {
                    "ON_DUTY" -> stringResource(R.string.status_on_duty) to EkoGreen
                    "ENDED" -> stringResource(R.string.status_day_ended) to MaterialTheme.colorScheme.onSurfaceVariant
                    "AUTO_CLOSED" -> stringResource(R.string.team_auto_closed) to EkoAmber
                    else -> stringResource(R.string.status_not_started) to EkoAmber
                }
                GlassTag(label, tint = tint)
            }
            if (r.startedAt != null) {
                val times = buildList {
                    add(stringResource(R.string.team_started, Ist.time(r.startedAt)))
                    r.endedAt?.let { add(stringResource(R.string.team_ended, Ist.time(it))) }
                    r.hoursWorked?.let { add(stringResource(R.string.team_hours, String.format(Locale.ROOT, "%.1f", it))) }
                }.joinToString(" · ")
                Text(times, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
            }
            Text(
                stringResource(
                    R.string.team_counts,
                    r.visitsToday ?: 0,
                    String.format(Locale.ROOT, "%.1f", r.kmToday ?: 0.0),
                    r.cspCount ?: 0,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                val ago = r.lastSeenAt?.let { runCatching { DutyNotificationText.ago(Duration.between(Instant.parse(it), Instant.now()), str) }.getOrNull() }
                val (liveText, liveTint) = when {
                    r.liveState == "live" && ago != null -> stringResource(R.string.team_live, ago) to EkoGreen
                    ago != null -> stringResource(R.string.team_last_seen, ago) to EkoAmber
                    else -> stringResource(R.string.team_no_location) to MaterialTheme.colorScheme.onSurfaceVariant
                }
                Text(liveText, style = MaterialTheme.typography.bodySmall, color = liveTint, modifier = Modifier.weight(1f))
                if (r.lat != null && r.lng != null) {
                    TextButton(onClick = { openMap(context, r.lat, r.lng, r.name) }) {
                        Text(stringResource(R.string.team_open_map), color = EkoBlue)
                    }
                }
            }
        }
    }
}

/** Drops a pin in Google Maps (or any maps app) at the DC's last known position. */
internal fun openMap(context: Context, lat: Double, lng: Double, label: String) {
    val uri = Uri.parse("geo:0,0?q=$lat,$lng(${Uri.encode(label)})")
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, uri)) }
}
