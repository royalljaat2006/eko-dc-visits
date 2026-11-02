package com.eko.dcvisits.app.ui.team

import android.annotation.SuppressLint
import android.content.Context
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eko.dcvisits.app.R
import com.eko.dcvisits.app.data.repo.TeamRow
import com.eko.dcvisits.app.tracking.DutyNotificationText
import com.eko.dcvisits.app.tracking.StringLookup
import com.eko.dcvisits.app.ui.components.GlassCard
import com.eko.dcvisits.app.ui.components.GlassTag
import com.eko.dcvisits.app.ui.components.ScreenContainer
import com.eko.dcvisits.app.ui.theme.EkoAmber
import com.eko.dcvisits.app.ui.theme.EkoBlue
import com.eko.dcvisits.app.ui.theme.EkoGreen
import com.eko.dcvisits.app.util.Ist
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.put
import kotlinx.serialization.json.addJsonObject
import java.time.Duration
import java.time.Instant

private const val AUTO_REFRESH_MS = 30_000L

/**
 * Admin / national head: every DC on one map, like the web dashboard's Live Tracking.
 * Same reads as the Team tab ([TeamViewModel]); the map is Leaflet in a WebView,
 * bundled from assets so only the OSM tiles come over the network.
 */
@Composable
fun LiveMapScreen(vm: TeamViewModel = viewModel()) {
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) {
        while (true) {
            vm.refresh()
            delay(AUTO_REFRESH_MS)
        }
    }
    val context = LocalContext.current
    val str = StringLookup.of(context)
    val rows = ui.team?.rows.orEmpty()
    var selectedId by rememberSaveable { mutableStateOf<String?>(null) }
    var mapReady by remember { mutableStateOf(false) }
    val webView = remember { liveMapWebView(context) { mapReady = true } }
    DisposableEffect(Unit) { onDispose { webView.destroy() } }

    LaunchedEffect(mapReady, rows, selectedId) {
        if (mapReady) webView.evaluateJavascript("setDcs(${markersJson(rows, str)}, ${JsonPrimitive(selectedId)})", null)
    }

    ScreenContainer { pad ->
        Column(Modifier.fillMaxSize().padding(pad), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.live_map_title), style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
                    ui.updatedAt?.let {
                        Text(stringResource(R.string.team_updated, Ist.time(it)), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (ui.loading) CircularProgressIndicator(Modifier.padding(end = 8.dp), color = EkoBlue, strokeWidth = 2.dp)
                TextButton(onClick = vm::refresh) { Text(stringResource(R.string.action_refresh), color = EkoBlue) }
            }
            if (ui.failed) {
                Text(stringResource(R.string.team_load_failed), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            Box(Modifier.fillMaxWidth().height(340.dp).clip(RoundedCornerShape(20.dp))) {
                AndroidView(factory = { webView }, modifier = Modifier.fillMaxSize())
            }
            LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(rows, key = { it.dcUserId }) { r ->
                    LiveRow(r, selected = r.dcUserId == selectedId, str = str) {
                        selectedId = r.dcUserId
                        if (mapReady) webView.evaluateJavascript("focusDc(${JsonPrimitive(r.dcUserId)})", null)
                    }
                }
            }
        }
    }
}

@Composable
private fun LiveRow(r: TeamRow, selected: Boolean, str: StringLookup, onClick: () -> Unit) {
    val (label, tint) = liveChip(r.liveState)
    GlassCard(
        Modifier.fillMaxWidth().clickable(enabled = r.lat != null && r.lng != null, onClick = onClick),
        cornerRadius = 16.dp,
        contentPadding = 12.dp,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    r.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = if (selected) EkoBlue else MaterialTheme.colorScheme.onSurface,
                )
                val detail = listOfNotNull(
                    lastSeen(r, str),
                    r.accuracyM?.let { "±${Math.round(it)} m" },
                ).joinToString(" · ")
                Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            GlassTag(stringResource(label), tint = tint)
        }
    }
}

@Composable
private fun liveChip(state: String): Pair<Int, Color> = when (state) {
    "live" -> R.string.live_state_live to EkoGreen
    "stale" -> R.string.live_state_stale to EkoAmber
    "off_duty" -> R.string.live_state_off_duty to MaterialTheme.colorScheme.onSurfaceVariant
    else -> R.string.live_state_no_fix to MaterialTheme.colorScheme.onSurfaceVariant
}

private fun lastSeen(r: TeamRow, str: StringLookup): String? = r.lastSeenAt?.let {
    runCatching { str.get(R.string.team_last_seen, DutyNotificationText.ago(Duration.between(Instant.parse(it), Instant.now()), str)) }.getOrNull()
}

/** Only DCs with a known position get a marker; the popup says how old it is. */
private fun markersJson(rows: List<TeamRow>, str: StringLookup): String = buildJsonArray {
    for (r in rows) {
        if (r.lat == null || r.lng == null) continue
        addJsonObject {
            put("id", r.dcUserId)
            put("name", r.name)
            put("state", r.liveState)
            put("lat", r.lat)
            put("lng", r.lng)
            put("label", lastSeen(r, str) ?: "")
        }
    }
}.toString()

/**
 * Leaflet and its CSS are inlined from assets. The page is given the dashboard's
 * origin as base URL so OSM tile requests carry a real Referer, as the web map's do.
 */
@SuppressLint("SetJavaScriptEnabled")
private fun liveMapWebView(context: Context, onReady: () -> Unit): WebView {
    val assets = context.assets
    fun asset(name: String) = assets.open("livemap/$name").bufferedReader().use { it.readText() }
    val html = asset("livemap.html")
        .replace("/*LEAFLET_CSS*/", asset("leaflet.css"))
        .replace("/*LEAFLET_JS*/", asset("leaflet.js"))
    return WebView(context).apply {
        settings.javaScriptEnabled = true
        webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView?, url: String?) = onReady()
        }
        loadDataWithBaseURL("https://indev.eko.in/dc-visits-admin/", html, "text/html", "utf-8", null)
    }
}
