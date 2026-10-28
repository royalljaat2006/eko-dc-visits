package com.eko.dcvisits.app.ui.logi

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eko.dcvisits.app.data.repo.DayState
import com.eko.dcvisits.app.ui.components.Entrance
import com.eko.dcvisits.app.ui.components.GlassCard
import com.eko.dcvisits.app.ui.components.GlassTag
import com.eko.dcvisits.app.ui.components.GlowButton
import com.eko.dcvisits.app.ui.components.LoadingState
import com.eko.dcvisits.app.ui.components.NoEligibleCspBanner
import com.eko.dcvisits.app.ui.components.ScreenContainer
import com.eko.dcvisits.app.ui.components.StateBanner
import com.eko.dcvisits.app.ui.theme.EkoAmber
import com.eko.dcvisits.app.ui.theme.EkoBlue
import com.eko.dcvisits.app.ui.theme.EkoGreen
import com.eko.dcvisits.app.ui.theme.EkoViolet
import kotlin.math.roundToInt
import androidx.compose.ui.res.stringResource
import com.eko.dcvisits.app.R
import com.eko.dcvisits.app.ui.text.distanceLabel

/**
 * "Logi" tab: a single place for the day's field logistics — today's summary,
 * the assigned itinerary (distance-ranked, not route-optimized; no routing
 * provider is configured, ADR-0005), the Smart Next Stop recommendation, and
 * a hand-off to Google Maps for the remaining stops. Everything here reads
 * data that already exists elsewhere (CSP assignments, visits, attendance,
 * nearest-CSP) — nothing is invented or persisted again client-side.
 */
@Composable
fun LogiScreen(vm: LogiViewModel = viewModel()) {
    val context = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val day by vm.dayState.collectAsStateWithLifecycle()
    val pending by vm.pendingSync.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.refresh(context) }

    val pendingStops = ui.stops.filter { !it.visitedToday }

    ScreenContainer { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Entrance(index = 0) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text(stringResource(R.string.logi_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
                        TextButton(onClick = { vm.refresh(context) }) { Text(stringResource(R.string.action_refresh), color = EkoBlue) }
                    }
                }
            }
            if (ui.loading) {
                item { LoadingState() }
            } else {
                if (day == DayState.NOT_STARTED) {
                    item { Entrance(index = 1) { StateBanner(stringResource(R.string.logi_check_in_first), tint = EkoBlue) } }
                }
                if (ui.stale) {
                    item { Entrance(index = 1) { StateBanner(stringResource(R.string.logi_stale), tint = EkoAmber) } }
                }
                ui.openVisit?.let { visit ->
                    item {
                        Entrance(index = 2) {
                            StateBanner(
                                stringResource(
                                    R.string.logi_open_visit,
                                    visit.location_code ?: visit.location_name ?: stringResource(R.string.logi_a_csp),
                                ),
                                tint = EkoViolet,
                            )
                        }
                    }
                }

                item {
                    Entrance(index = 3) {
                        FieldSummaryCard(ui = ui)
                    }
                }

                item {
                    Entrance(index = 4) {
                        Text(stringResource(R.string.logi_smart_next_stop), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                    }
                }
                item {
                    Entrance(index = 5) {
                        when {
                            ui.recommendedState == "no_eligible_csp" -> NoEligibleCspBanner()
                            ui.recommended == null -> StateBanner(stringResource(R.string.logi_no_recommendation), tint = EkoAmber)
                            else -> NextStopCard(
                                name = ui.recommended!!.name,
                                code = ui.recommended!!.code,
                                distanceM = ui.recommended!!.distance_m,
                                onNavigate = {
                                    val uri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${ui.recommended!!.lat},${ui.recommended!!.lng}")
                                    context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                },
                            )
                        }
                    }
                }

                item {
                    Entrance(index = 6) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text(stringResource(R.string.logi_itinerary, ui.stops.size), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                            if (pendingStops.size > 1) {
                                TextButton(onClick = { openMultiStopRoute(context, pendingStops.take(9).map { it.csp.lat to it.csp.lng }) }) {
                                    Text(stringResource(R.string.logi_open_route), color = EkoBlue)
                                }
                            }
                        }
                    }
                }
                item {
                    Entrance(index = 7) {
                        Text(
                            stringResource(R.string.logi_sorted_note),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (ui.stops.isEmpty()) {
                    item { StateBanner(stringResource(R.string.logi_none_cached), tint = EkoAmber) }
                }
                itemsIndexed(ui.stops, key = { _, s -> s.csp.cspLocationId }) { i, stop ->
                    Entrance(index = i + 8) {
                        ItineraryRow(stop)
                    }
                }
            }
        }
    }
}

@Composable
private fun FieldSummaryCard(ui: LogiUiState) {
    GlassCard(Modifier.fillMaxWidth(), cornerRadius = 18.dp, contentPadding = 16.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SummaryStat(stringResource(R.string.label_assigned), "${ui.assignedCount}")
                SummaryStat(stringResource(R.string.label_visited_today), "${ui.visitedTodayCount}")
                SummaryStat(stringResource(R.string.label_pending_today), "${(ui.assignedCount - ui.visitedTodayCount).coerceAtLeast(0)}")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SummaryStat(stringResource(R.string.logi_distance_travelled), ui.kmToday?.let { stringResource(R.string.distance_km, it) } ?: "—")
                SummaryStat(stringResource(R.string.logi_gps_points_today), ui.routePointsToday?.toString() ?: "—")
            }
            Text(
                stringResource(R.string.logi_distance_note),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SummaryStat(label: String, value: String) {
    Column {
        Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun NextStopCard(name: String, code: String, distanceM: Double?, onNavigate: () -> Unit) {
    GlassCard(Modifier.fillMaxWidth(), cornerRadius = 18.dp, contentPadding = 16.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(code, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            distanceM?.let {
                GlassTag(stringResource(R.string.distance_straight_line, distanceLabel(it)), tint = EkoBlue)
            }
            Row(Modifier.padding(top = 8.dp)) {
                GlowButton(text = stringResource(R.string.action_navigate), onClick = onNavigate)
            }
        }
    }
}

@Composable
private fun ItineraryRow(stop: LogiStop) {
    GlassCard(Modifier.fillMaxWidth(), cornerRadius = 14.dp, contentPadding = 12.dp) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("${stop.sequence}", style = MaterialTheme.typography.titleMedium, color = EkoBlue)
            Column(Modifier.weight(1f)) {
                Text(stop.csp.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(stop.csp.code, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            stop.distanceM?.let { GlassTag(distanceLabel(it), tint = EkoBlue) }
            GlassTag(stringResource(if (stop.visitedToday) R.string.label_visited else R.string.label_pending), tint = if (stop.visitedToday) EkoGreen else EkoAmber)
        }
    }
}


/** Google Maps multi-stop directions — destination is the last stop, everything else is a waypoint. */
private fun openMultiStopRoute(context: android.content.Context, stops: List<Pair<Double, Double>>) {
    if (stops.isEmpty()) return
    val destination = stops.last()
    val waypoints = stops.dropLast(1)
    val uriBuilder = StringBuilder("https://www.google.com/maps/dir/?api=1&destination=${destination.first},${destination.second}")
    if (waypoints.isNotEmpty()) {
        uriBuilder.append("&waypoints=").append(waypoints.joinToString("|") { "${it.first},${it.second}" })
    }
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uriBuilder.toString())))
}
