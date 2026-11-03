package com.eko.dcvisits.app.ui.team

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.eko.dcvisits.app.R
import com.eko.dcvisits.app.data.net.CspLocationDto
import com.eko.dcvisits.app.data.repo.NearbyCsp
import com.eko.dcvisits.app.data.repo.formatDistance
import com.eko.dcvisits.app.data.repo.nearbyCsps
import com.eko.dcvisits.app.data.sync.Fix
import com.eko.dcvisits.app.tracking.NearbyAlertService
import com.eko.dcvisits.app.tracking.NearbyAlerts
import com.eko.dcvisits.app.ui.components.GlassCard
import com.eko.dcvisits.app.ui.location.LocationProvider
import com.eko.dcvisits.app.ui.theme.EkoAmber
import com.eko.dcvisits.app.ui.theme.EkoBlue
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import com.eko.dcvisits.app.ui.text.cspLabel

private val RADII_KM = listOf(5, 10, 25, 50)

/**
 * Super admin "CSPs near me": follows the admin's position while this view is on
 * screen (no background tracking) and lists the CSPs within the chosen radius,
 * nearest first, on an in-app map. The position never leaves the phone.
 */
@Composable
fun NearbyCspsView(source: List<CspLocationDto>, pad: PaddingValues, header: @Composable () -> Unit) {
    val context = LocalContext.current
    var radiusKm by rememberSaveable { mutableStateOf(25) }
    var me by remember { mutableStateOf<Fix?>(null) }
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var mapReady by remember { mutableStateOf(false) }
    val webView = remember { leafletWebView(context, "nearby.html") { mapReady = true } }
    DisposableEffect(Unit) { onDispose { webView.destroy() } }

    // Collecting = following; leaving the view cancels it and GPS stops.
    LaunchedEffect(Unit) { LocationProvider.updates(context).collect { me = it } }

    val near = remember(source, me, radiusKm) { me?.let { nearbyCsps(source, it.lat, it.lng, radiusKm) }.orEmpty() }

    LaunchedEffect(mapReady, me) {
        val m = me ?: return@LaunchedEffect
        if (mapReady) webView.evaluateJavascript("setMe(${m.lat}, ${m.lng}, ${m.accuracyM ?: 30.0})", null)
    }
    LaunchedEffect(mapReady, near) {
        if (!mapReady || me == null) return@LaunchedEffect
        // Refit only when the radius changes (or on the first fix), not on every step.
        webView.evaluateJavascript("setCsps(${pinsJson(near)}, ${JsonPrimitive("r$radiusKm")})", null)
        selectedId?.let { id -> if (near.any { it.csp.csp_location_id == id }) webView.evaluateJavascript("select(${JsonPrimitive(id)})", null) }
    }

    // The bottom padding goes on the list instead, so the last CSP clears the nav bar.
    val dir = LocalLayoutDirection.current
    Column(
        Modifier.fillMaxSize().padding(start = pad.calculateStartPadding(dir), end = pad.calculateEndPadding(dir), top = pad.calculateTopPadding()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        header()
        Box(Modifier.fillMaxWidth().height(250.dp).clip(RoundedCornerShape(20.dp))) {
            AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
        }
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            RADII_KM.forEach { km ->
                FilterChip(selected = radiusKm == km, onClick = { radiusKm = km }, label = { Text("$km km") })
            }
        }
        AlertSwitch()
        Text(
            when {
                me == null -> stringResource(R.string.nearby_finding_you)
                near.isEmpty() -> stringResource(R.string.nearby_none, radiusKm)
                else -> stringResource(R.string.nearby_count, near.size, radiusKm)
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            contentPadding = PaddingValues(bottom = pad.calculateBottomPadding()),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(near, key = { it.csp.csp_location_id }) { n ->
                NearbyRow(n, selected = n.csp.csp_location_id == selectedId) {
                    selectedId = n.csp.csp_location_id
                    if (mapReady) webView.evaluateJavascript("select(${JsonPrimitive(n.csp.csp_location_id)})", null)
                }
            }
        }
    }
}

/** On-the-road alert (app closed too): starts/stops the visible NearbyAlertService. */
@Composable
private fun AlertSwitch() {
    val context = LocalContext.current
    var on by remember { mutableStateOf(NearbyAlerts.isOn(context)) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.nearby_alert_switch), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(stringResource(R.string.nearby_alert_switch_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = on, onCheckedChange = { want ->
            on = want
            NearbyAlerts.setOn(context, want)
            if (want) NearbyAlertService.start(context) else NearbyAlertService.stop(context)
        })
    }
}

@Composable
private fun NearbyRow(n: NearbyCsp, selected: Boolean, onClick: () -> Unit) {
    val context = LocalContext.current
    val c = n.csp
    GlassCard(Modifier.fillMaxWidth(), cornerRadius = 16.dp, contentPadding = 12.dp, onClick = onClick) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(cspLabel(c.code, c.name), style = MaterialTheme.typography.titleSmall, color = if (selected) EkoBlue else MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                Text("${formatDistance(n.distanceM)} ${n.arrow} ${n.direction}", style = MaterialTheme.typography.titleSmall, color = EkoBlue)
            }
            val where = listOfNotNull(c.address?.takeIf { it.isNotBlank() }, c.district).joinToString(" · ")
            if (where.isNotEmpty()) Text(where, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            if (!c.coordinates_verified) {
                Text(stringResource(R.string.nearby_unverified), style = MaterialTheme.typography.labelSmall, color = EkoAmber)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    c.dc_name?.let { stringResource(R.string.nearby_dc, it) } ?: stringResource(R.string.admin_csps_no_dc),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (c.dc_name != null) MaterialTheme.colorScheme.onSurface else EkoAmber,
                    modifier = Modifier.weight(1f),
                )
                c.dc_phone?.takeIf { it.isNotBlank() }?.let { phone ->
                    TextButton(onClick = { dial(context, phone) }) { Text(stringResource(R.string.nearby_call), color = EkoBlue) }
                }
            }
        }
    }
}

/** Opens the dialler with the DC's number; the admin taps call themselves. */
private fun dial(context: Context, phone: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))) }
}

private fun pinsJson(near: List<NearbyCsp>): String = buildJsonArray {
    near.forEach { n ->
        addJsonObject {
            put("id", n.csp.csp_location_id)
            put("code", cspLabel(n.csp.code, n.csp.name))
            put("lat", n.csp.lat)
            put("lng", n.csp.lng)
            put("label", formatDistance(n.distanceM) + " " + n.direction + (n.csp.dc_name?.let { " · $it" } ?: ""))
        }
    }
}.toString()
