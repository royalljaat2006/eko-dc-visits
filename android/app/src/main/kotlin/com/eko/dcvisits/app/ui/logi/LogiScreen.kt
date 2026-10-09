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
                        Text("Today's logistics", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
                        TextButton(onClick = { vm.refresh(context) }) { Text("Refresh", color = EkoBlue) }
                    }
                }
            }
            if (ui.loading) {
                item { LoadingState() }
            } else {
                if (day == DayState.NOT_STARTED) {
                    item { Entrance(index = 1) { StateBanner("Check In on Home to start today's tracking and itinerary.", tint = EkoBlue) } }
                }
                if (ui.stale) {
                    item { Entrance(index = 1) { StateBanner("Couldn't reach the server just now — showing the last known plan; it may be stale.", tint = EkoAmber) } }
                }
                ui.openVisit?.let { visit ->
                    item {
                        Entrance(index = 2) {
                            StateBanner(
                                "You have an open visit at ${visit.location_code ?: visit.location_name ?: "a CSP"} — finish it on the Visits tab before starting another.",
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
                        Text("Smart next stop", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                    }
                }
                item {
                    Entrance(index = 5) {
                        when {
                            ui.recommendedState == "no_eligible_csp" -> NoEligibleCspBanner()
                            ui.recommended == null -> StateBanner("No recommendation available right now.", tint = EkoAmber)
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
                            Text("Today's itinerary (${ui.stops.size})", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onBackground)
                            if (pendingStops.size > 1) {
                                TextButton(onClick = { openMultiStopRoute(context, pendingStops.take(9).map { it.csp.lat to it.csp.lng }) }) {
                                    Text("Open route in Maps", color = EkoBlue)
                                }
                            }
                        }
                    }
                }
                item {
                    Entrance(index = 7) {
                        Text(
                            "Sorted by straight-line distance from your last known location — not an optimized multi-stop route (no routing provider is configured).",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (ui.stops.isEmpty()) {
                    item { StateBanner("No assigned CSPs cached yet.", tint = EkoAmber) }
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
                SummaryStat("Assigned", "${ui.assignedCount}")
                SummaryStat("Visited today", "${ui.visitedTodayCount}")
                SummaryStat("Pending today", "${(ui.assignedCount - ui.visitedTodayCount).coerceAtLeast(0)}")
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                SummaryStat("Distance travelled", ui.kmToday?.let { "%.1f km".format(it) } ?: "—")
                SummaryStat("GPS points today", ui.routePointsToday?.toString() ?: "—")
            }
            Text(
                "Distance travelled is a provisional GPS estimate (track_straightline_v0) — not a planned-route figure; no routing provider is configured to compute one.",
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
                GlassTag(formatDistance(it) + " · straight-line", tint = EkoBlue)
            }
            Row(Modifier.padding(top = 8.dp)) {
                GlowButton(text = "Navigate", onClick = onNavigate)
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
            stop.distanceM?.let { GlassTag(formatDistance(it), tint = EkoBlue) }
            GlassTag(if (stop.visitedToday) "Visited" else "Pending", tint = if (stop.visitedToday) EkoGreen else EkoAmber)
        }
    }
}

private fun formatDistance(m: Double): String =
    if (m < 1000) "${m.roundToInt()} m" else "${"%.1f".format(m / 1000)} km"

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
