package com.eko.dcvisits.app.ui.visits

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eko.dcvisits.app.camera.PhotoCaptureScreen
import com.eko.dcvisits.app.data.db.CspCacheEntity
import com.eko.dcvisits.app.data.repo.GeoAdvisory
import com.eko.dcvisits.app.data.sync.Fix
import com.eko.dcvisits.app.ui.components.Entrance
import com.eko.dcvisits.app.ui.components.ScreenContainer
import com.eko.dcvisits.app.ui.components.GlassCard
import com.eko.dcvisits.app.ui.components.GlassTag
import com.eko.dcvisits.app.ui.components.GlowButton
import com.eko.dcvisits.app.ui.components.dashedBorder
import com.eko.dcvisits.app.ui.theme.EkoAmber
import com.eko.dcvisits.app.ui.theme.EkoBlue
import com.eko.dcvisits.app.ui.theme.EkoCyan
import com.eko.dcvisits.app.ui.location.rememberLocationAsker
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VisitsScreen(vm: VisitsViewModel = viewModel()) {
    val context = LocalContext.current
    val askLocation = rememberLocationAsker()
    val csps by vm.assignedCsps.collectAsStateWithLifecycle()
    val pending by vm.pendingCheckins.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.refresh(context) }

    // Full-screen camera takes over while a category is being captured.
    ui.cameraCategory?.let { category ->
        PhotoCaptureScreen(
            category = category,
            onCaptured = vm::onPhotoCaptured,
            onCancel = vm::cancelCamera,
        )
        return
    }

    var pendingCategory by remember { mutableStateOf<String?>(null) }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) pendingCategory?.let(vm::openCamera)
        pendingCategory = null
    }
    fun requestPhoto(category: String) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            vm.openCamera(category)
        } else {
            pendingCategory = category
            cameraPermission.launch(Manifest.permission.CAMERA)
        }
    }

    ScreenContainer { pad ->
     LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = pad,
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Entrance(index = 0) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text("Log a visit", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
                    TextButton(onClick = { vm.refresh(context) }) { Text("Refresh", color = EkoBlue) }
                }
            }
        }
        item {
            Entrance(index = 1) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlassTag("Today: ${ui.todayVisits.size} synced", tint = EkoCyan)
                    if (pending > 0) GlassTag("$pending queued", tint = EkoAmber)
                }
            }
        }
        ui.message?.let { msg ->
            item {
                Entrance(index = 2) {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text(msg, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                            TextButton(onClick = vm::clearMessage) { Text("OK", color = EkoBlue) }
                        }
                    }
                }
            }
        }
        if (ui.busy) item { CircularProgressIndicator(color = EkoBlue) }

        ui.awaitingPhotoFor?.let {
            item {
                Entrance(index = 3) {
                    ActiveVisitCard(
                        cspCode = ui.awaitingPhotoCsp,
                        captured = ui.capturedPhotos,
                        busy = ui.busy,
                        onCapture = ::requestPhoto,
                        onCheckOut = vm::checkOut,
                    )
                }
            }
        }

        item {
            Entrance(index = 4) {
                Text("Tap a CSP to check in", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
            }
        }
        itemsIndexed(csps, key = { _, it -> it.cspLocationId }) { i, csp ->
            Entrance(index = i + 5) {
                CspRow(
                    csp = csp,
                    advisory = ui.currentFix?.let { fix -> vm.advisory(csp, fix) },
                    enabled = !ui.busy,
                    onCheckIn = { askLocation { vm.checkIn(context, csp) } },
                )
            }
        }
     }
    }

    ui.awaitingReasonFor?.let { csp ->
        OutOfRadiusDialog(
            cspCode = csp.code,
            distanceM = ui.awaitingReasonDistanceM,
            onDismiss = vm::cancelReason,
            onConfirm = { reason, remarks -> vm.confirmOutOfRadius(context, reason, remarks) },
        )
    }
}

/** The open visit: photo progress + a 4-slot evidence grid + Check out. */
@Composable
private fun ActiveVisitCard(
    cspCode: String,
    captured: Map<String, Bitmap>,
    busy: Boolean,
    onCapture: (String) -> Unit,
    onCheckOut: () -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth(), cornerRadius = 24.dp, tint = EkoCyan) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(7.dp).clip(CircleShape).background(EkoCyan))
                Text("ACTIVE GEOFENCE", style = MaterialTheme.typography.labelSmall, color = EkoCyan)
            }
            Text(cspCode, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(
                "Checked in — add physical proofs, verify signage, then check out.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("Photos: ${captured.size}/${PHOTO_CATEGORIES.size}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
                if (captured.size < PHOTO_CATEGORIES.size) {
                    GlassTag("Required", tint = EkoAmber)
                } else {
                    GlassTag("Complete", tint = EkoCyan)
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PHOTO_CATEGORIES.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { cat ->
                            PhotoSlot(
                                modifier = Modifier.weight(1f),
                                category = cat,
                                bitmap = captured[cat],
                                enabled = !busy,
                                onClick = { onCapture(cat) },
                            )
                        }
                    }
                }
            }

            GlowButton(
                text = "Check out",
                onClick = onCheckOut,
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** One of the 4 mandatory evidence slots — filled shows the real captured thumbnail + a verified badge. */
@Composable
private fun PhotoSlot(
    modifier: Modifier = Modifier,
    category: String,
    bitmap: Bitmap?,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val label = category.replace('_', ' ')
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
                .let { m ->
                    if (bitmap != null) m.clip(RoundedCornerShape(14.dp)) else m.dashedBorder(cornerRadius = 14.dp)
                }
                .clickable(enabled = enabled, onClick = onClick),
            contentAlignment = Alignment.Center,
        ) {
            if (bitmap != null) {
                Image(bitmap = bitmap.asImageBitmap(), contentDescription = label, modifier = Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                Icon(
                    Icons.Filled.CheckCircle,
                    contentDescription = "Verified",
                    tint = EkoCyan,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(20.dp),
                )
            } else {
                Icon(Icons.Filled.CameraAlt, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(26.dp))
            }
        }
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun CspRow(
    csp: CspCacheEntity,
    advisory: GeoAdvisory?,
    enabled: Boolean,
    onCheckIn: () -> Unit,
) {
    GlassCard(Modifier.fillMaxWidth()) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                Text(csp.name, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.weight(1f))
                advisory?.let { adv ->
                    GlassTag(
                        text = distanceLabel(adv),
                        tint = if (adv.inside) EkoCyan else EkoAmber,
                    )
                }
            }
            Text(
                "${csp.code} · last visit ${csp.lastVisitDate ?: "never"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            GlowButton(
                text = "Check in here",
                onClick = onCheckIn,
                enabled = enabled,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
    }
}

private fun distanceLabel(adv: GeoAdvisory): String {
    val m = adv.distanceM.roundToInt()
    val distance = if (m < 1000) "${m}m" else "%.1fkm".format(adv.distanceM / 1000)
    return if (adv.inside) "Inside $distance" else "$distance away"
}

@Composable
private fun OutOfRadiusDialog(
    cspCode: String,
    distanceM: Double,
    onDismiss: () -> Unit,
    onConfirm: (reason: String, remarks: String?) -> Unit,
) {
    var reason by remember { mutableStateOf(OUT_OF_RADIUS_REASONS.first().first) }
    var remarks by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface,
        title = { Text("You're ${distanceM.roundToInt()} m from $cspCode") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "The check-in still goes through (geofencing is advisory). Pick a reason so your Circle Head has context.",
                    style = MaterialTheme.typography.bodySmall,
                )
                OUT_OF_RADIUS_REASONS.forEach { (code, label) ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .selectable(selected = reason == code, onClick = { reason = code })
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        RadioButton(selected = reason == code, onClick = { reason = code })
                        Text(label)
                    }
                }
                OutlinedTextField(
                    value = remarks,
                    onValueChange = { remarks = it.take(500) },
                    label = { Text("Remarks (optional)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(reason, remarks.ifBlank { null }) }) { Text("Check in anyway") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
