package com.eko.dcvisits.app.ui.nearestcsp

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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eko.dcvisits.app.data.net.NearestCspItemDto
import com.eko.dcvisits.app.ui.components.EmptyState
import com.eko.dcvisits.app.ui.components.Entrance
import com.eko.dcvisits.app.ui.components.GlassCard
import com.eko.dcvisits.app.ui.components.GlassTag
import com.eko.dcvisits.app.ui.components.GlowButton
import com.eko.dcvisits.app.ui.components.GpsUnavailableBanner
import com.eko.dcvisits.app.ui.components.LoadingState
import com.eko.dcvisits.app.ui.components.LocationStaleBanner
import com.eko.dcvisits.app.ui.components.NoEligibleCspBanner
import com.eko.dcvisits.app.ui.components.ScreenContainer
import com.eko.dcvisits.app.ui.components.StateBanner
import com.eko.dcvisits.app.ui.theme.EkoAmber
import com.eko.dcvisits.app.ui.theme.EkoBlue
import com.eko.dcvisits.app.ui.theme.EkoCyan
import com.eko.dcvisits.app.ui.theme.EkoGreen
import com.eko.dcvisits.app.ui.theme.EkoRed
import com.eko.dcvisits.app.ui.theme.EkoViolet
import com.eko.dcvisits.app.util.Ist
import java.time.Instant
import kotlin.math.roundToInt

/**
 * "Navigate" tab (C2 v0.12.0): the DC's nearest eligible CSP, with in-app
 * distance + a Google Maps hand-off. Opening navigation is advisory only —
 * it never records or completes a visit; check-in still happens through the
 * existing dwell-matcher / manual check-in flow elsewhere in the app.
 */
@Composable
fun NearestCspScreen(vm: NearestCspViewModel = viewModel()) {
    val context = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    val busy by vm.busy.collectAsStateWithLifecycle()
    var showOthers by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) { vm.refresh(context) }

    val others = if (ui.items.size > 1) ui.items.drop(1) else emptyList()

    ScreenContainer { pad ->
        LazyColumn(Modifier.fillMaxSize(), contentPadding = pad, verticalArrangement = Arrangement.spacedBy(12.dp)) {
            item {
                Entrance(index = 0) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("Navigate", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
                        TextButton(onClick = { vm.refresh(context) }, enabled = !busy) { Text("Refresh recommendation", color = EkoBlue) }
                    }
                }
            }
            if (ui.lastUpdatedAtMs != null) {
                item {
                    Entrance(index = 1) {
                        Text(
                            "Updated ${Ist.display(Instant.ofEpochMilli(ui.lastUpdatedAtMs!!).toString())} IST" +
                                (ui.fixAccuracyM?.let { " · GPS accuracy ±${it.roundToInt()} m" } ?: " · no fresh GPS fix"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            item {
                Entrance(index = 2) {
                    when (ui.state) {
                        NearestCspState.LOADING -> LoadingState()
                        NearestCspState.INACTIVE_SESSION -> StateBanner("Check In to see your nearest CSP.", tint = EkoBlue)
                        NearestCspState.NO_ELIGIBLE_CSP -> NoEligibleCspBanner()
                        NearestCspState.ERROR -> StateBanner(ui.errorMessage ?: "Could not load nearest CSP.", tint = EkoRed)
                        NearestCspState.NO_FIX -> GpsUnavailableBanner()
                        NearestCspState.STALE_GPS -> LocationStaleBanner()
                        NearestCspState.OK -> {}
                    }
                }
            }
            if (ui.items.isNotEmpty() && (ui.state == NearestCspState.OK || ui.state == NearestCspState.NO_FIX || ui.state == NearestCspState.STALE_GPS)) {
                item {
                    Entrance(index = 3) {
                        NearestCspCard(item = ui.items[0], recommended = ui.state == NearestCspState.OK)
                    }
                }
                if (others.isNotEmpty()) {
                    item {
                        Entrance(index = 4) {
                            TextButton(onClick = { showOthers = !showOthers }) {
                                Text(
                                    if (showOthers) "Hide other eligible CSPs" else "Other eligible CSPs (${others.size})",
                                    color = EkoBlue,
                                )
                            }
                        }
                    }
                    if (showOthers) {
                        itemsIndexed(others, key = { _, it -> it.csp_location_id }) { i, item ->
                            Entrance(index = i + 4) {
                                NearestCspCard(item = item, recommended = false, compact = true)
                            }
                        }
                    }
                }
            } else if (ui.state != NearestCspState.LOADING) {
                item { EmptyState("Nothing to navigate to right now.") }
            }
        }
    }
}

@Composable
private fun NearestCspCard(item: NearestCspItemDto, recommended: Boolean, compact: Boolean = false) {
    val context = LocalContext.current
    val onNavigate = {
        val uri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${item.lat},${item.lng}")
        context.startActivity(Intent(Intent.ACTION_VIEW, uri))
    }
    GlassCard(Modifier.fillMaxWidth(), cornerRadius = if (compact) 14.dp else 20.dp, contentPadding = if (compact) 12.dp else 18.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (recommended) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("★", color = EkoViolet)
                    Text("RECOMMENDED NEXT STOP", style = MaterialTheme.typography.labelMedium, color = EkoViolet)
                }
            }
            Text(
                item.name,
                style = if (compact) MaterialTheme.typography.titleSmall else MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(item.code, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!compact && item.address.isNotBlank()) {
                Text(item.address, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                item.distance_m?.let {
                    GlassTag(
                        formatDistance(it) + (if (item.distance_basis == "straight_line") " · straight-line" else ""),
                        tint = EkoCyan,
                    )
                }
                GlassTag(
                    if (item.last_visit_date != null) "Last visit: ${item.last_visit_date}" else "Never visited",
                    tint = if (item.last_visit_date != null) EkoGreen else EkoAmber,
                )
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlowButton(text = "Navigate", onClick = onNavigate)
            }
        }
    }
}

private fun formatDistance(m: Double): String =
    if (m < 1000) "${m.roundToInt()} m away" else "${"%.1f".format(m / 1000)} km away"
