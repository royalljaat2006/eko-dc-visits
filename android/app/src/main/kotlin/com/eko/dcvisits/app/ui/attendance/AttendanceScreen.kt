package com.eko.dcvisits.app.ui.attendance

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.HourglassBottom
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.NearMe
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
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
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import android.content.Intent
import android.net.Uri
import com.eko.dcvisits.app.data.repo.DayState
import com.eko.dcvisits.app.ui.components.Entrance
import com.eko.dcvisits.app.ui.components.GhostGlassButton
import com.eko.dcvisits.app.ui.components.GlassCard
import com.eko.dcvisits.app.ui.components.GlassTag
import com.eko.dcvisits.app.ui.components.GlowButton
import com.eko.dcvisits.app.ui.components.GpsUnavailableBanner
import com.eko.dcvisits.app.ui.components.NoEligibleCspBanner
import com.eko.dcvisits.app.ui.components.PendingSyncBanner
import com.eko.dcvisits.app.ui.components.ScreenContainer
import com.eko.dcvisits.app.ui.components.SuccessCheck
import com.eko.dcvisits.app.ui.components.glassChip
import com.eko.dcvisits.app.ui.components.glassSurface
import com.eko.dcvisits.app.ui.theme.EkoBlue
import com.eko.dcvisits.app.ui.theme.EkoCyan
import com.eko.dcvisits.app.ui.theme.EkoViolet
import com.eko.dcvisits.app.util.Ist
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

@Composable
fun AttendanceScreen(vm: AttendanceViewModel = viewModel(), onNavigate: (String) -> Unit = {}) {
    val context = LocalContext.current
    val day by vm.dayState.collectAsStateWithLifecycle()
    val ui by vm.ui.collectAsStateWithLifecycle()
    val assignedCount by vm.assignedCount.collectAsStateWithLifecycle()
    val pendingSync by vm.pendingSync.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { vm.refresh() }

    // A brief success pop the moment the day state actually transitions.
    var lastDay by remember { mutableStateOf<DayState?>(null) }
    var showSuccess by remember { mutableStateOf(false) }
    LaunchedEffect(day) {
        if (lastDay != null && lastDay != day) {
            showSuccess = true
            delay(1000)
            showSuccess = false
        }
        lastDay = day
    }

    ScreenContainer { pad ->
      Box(Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(pad),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Entrance(index = 0) {
                Text("Home", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
            }

            if (day != DayState.NOT_STARTED) {
                Entrance(index = 1) {
                    GlassCard(Modifier.fillMaxWidth(), cornerRadius = 18.dp, contentPadding = 12.dp) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            Box(
                                Modifier.size(28.dp).glassSurface(shape = CircleShape, tint = EkoCyan),
                                contentAlignment = Alignment.Center,
                            ) { Icon(Icons.Filled.LockOpen, contentDescription = null, tint = EkoCyan, modifier = Modifier.size(16.dp)) }
                            Text(
                                "Attendance active — Visits, Navigate, My CSPs and Logi unlocked.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }

            Entrance(index = 2) {
                StatusHero(day = day, busy = ui.busy)
            }

            Entrance(index = 3) {
                when (day) {
                    DayState.NOT_STARTED -> GlowButton(
                        text = "Check In — Start Day",
                        onClick = { vm.checkIn(context) },
                        enabled = !ui.busy,
                        loading = ui.busy,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    DayState.ON_DUTY -> {
                        var confirmEnd by remember { mutableStateOf(false) }
                        GhostGlassButton(
                            text = "End Day",
                            onClick = { confirmEnd = true },
                            enabled = !ui.busy,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (confirmEnd) {
                            AlertDialog(
                                onDismissRequest = { confirmEnd = false },
                                containerColor = MaterialTheme.colorScheme.surface,
                                title = { Text("End today's day?") },
                                text = { Text("Route tracking stops and today's hours/distance are finalized. You can still Resume if you end by mistake.") },
                                confirmButton = {
                                    TextButton(onClick = { confirmEnd = false; vm.endDay(context) }) { Text("End Day") }
                                },
                                dismissButton = { TextButton(onClick = { confirmEnd = false }) { Text("Cancel") } },
                            )
                        }
                    }
                    DayState.ENDED -> {
                        var confirmResume by remember { mutableStateOf(false) }
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            GhostGlassButton(
                                text = "Resume Day",
                                onClick = { confirmResume = true },
                                enabled = !ui.busy,
                                modifier = Modifier.fillMaxWidth(),
                            )
                            Text(
                                "Ended by mistake, or still working? Resuming turns route tracking back on for today. Past dates stay locked.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (confirmResume) {
                            AlertDialog(
                                onDismissRequest = { confirmResume = false },
                                containerColor = MaterialTheme.colorScheme.surface,
                                title = { Text("Resume today's day?") },
                                text = {
                                    Text(
                                        "Route tracking starts again until you End Day. Your day will then cover the whole period worked, including this gap. The 21:00 IST auto-checkout still applies.",
                                    )
                                },
                                confirmButton = {
                                    TextButton(onClick = {
                                        confirmResume = false
                                        vm.resumeDay(context)
                                    }) { Text("Resume & track") }
                                },
                                dismissButton = {
                                    TextButton(onClick = { confirmResume = false }) { Text("Cancel") }
                                },
                            )
                        }
                    }
                }
            }

            if (day != DayState.NOT_STARTED) {
                Entrance(index = 4) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        StatTile(Modifier.weight(1f), Icons.Filled.Place, "Assigned", "$assignedCount", "CSPs")
                        StatTile(Modifier.weight(1f), Icons.Filled.LockOpen, "Visited today", "${ui.visitedTodayCount}", "of $assignedCount")
                        StatTile(Modifier.weight(1f), Icons.Filled.Schedule, "Remaining", "${ui.remainingTodayCount}", "today")
                    }
                }
                if (pendingSync > 0) {
                    Entrance(index = 4) { PendingSyncBanner(pendingSync) }
                }
                Entrance(index = 4) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Recommended next stop", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
                        when {
                            ui.recommendedState == "no_eligible_csp" -> NoEligibleCspBanner()
                            ui.recommendedState == "no_fix" -> GpsUnavailableBanner()
                            ui.recommended != null -> {
                                val rec = ui.recommended!!
                                GlassCard(Modifier.fillMaxWidth(), cornerRadius = 16.dp, contentPadding = 14.dp) {
                                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Text("★", color = EkoViolet)
                                            Text(rec.name, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurface)
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            rec.distance_m?.let { GlassTag(formatHomeDistance(it), tint = EkoCyan) }
                                            GlassTag(rec.code, tint = EkoBlue)
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                            GlowButton(text = "Navigate", onClick = {
                                                val uri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${rec.lat},${rec.lng}")
                                                context.startActivity(Intent(Intent.ACTION_VIEW, uri))
                                            })
                                            GhostGlassButton(text = "My CSPs", onClick = { onNavigate("MY_CSPS") })
                                        }
                                    }
                                }
                            }
                            ui.recommendedState == "loading" -> Text(
                                "Finding your nearest CSP…",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            else -> Text(
                                "Couldn't load a recommendation right now — check the Navigate tab.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                Entrance(index = 4) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Quick actions", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onBackground)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            GhostGlassButton(text = "Visits", onClick = { onNavigate("VISITS") })
                            GhostGlassButton(text = "Navigate", onClick = { onNavigate("NAVIGATE") })
                            GhostGlassButton(text = "Logi", onClick = { onNavigate("LOGI") })
                        }
                    }
                }
            }

            ui.summary?.let { s ->
                Entrance(index = 4) {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatTile(
                                Modifier.weight(1f), Icons.Filled.Schedule, "Duty Hours",
                                s.hours_worked?.let { "%.1fh".format(it) } ?: "—",
                                if (day == DayState.ON_DUTY) "Continuous sync" else "Today",
                            )
                            StatTile(
                                Modifier.weight(1f), Icons.Filled.NearMe, "Transit Today",
                                "%.1f km".format(s.km_today), "~20s GPS polling",
                            )
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            StatTile(
                                Modifier.weight(1f), Icons.Filled.Fingerprint, "First Punch",
                                Ist.display(s.started_at), "Check-in time",
                            )
                            if (day == DayState.ENDED) {
                                StatTile(
                                    Modifier.weight(1f), Icons.Filled.HourglassBottom, "Check-out",
                                    Ist.display(s.ended_at) + if (s.auto_closed) " ⚠" else "", "Auto-close safe guard",
                                )
                            } else {
                                StatTile(
                                    Modifier.weight(1f), Icons.Filled.HourglassBottom, "Shift Boundary",
                                    "21:00 IST", "Auto-close safe guard",
                                )
                            }
                        }
                    }
                }
            }

            Entrance(index = 5) {
                Row(
                    Modifier.fillMaxWidth().glassChip(EkoCyan).padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Box(Modifier.size(6.dp).glassSurface(shape = CircleShape, tint = EkoCyan, fillAlphaTop = 1f, fillAlphaBottom = 1f, borderAlpha = 0f))
                    Text(
                        "GPS: live · polling ~20s · never blocks · ADR-0004",
                        style = MaterialTheme.typography.labelSmall,
                        color = EkoCyan,
                    )
                }
            }

            if (ui.lastFixAccuracyM != null || ui.locationOff) {
                Entrance(index = 6) {
                    GlassCard(Modifier.fillMaxWidth()) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.LocationOn, contentDescription = null, tint = EkoCyan, modifier = Modifier.size(18.dp))
                            Text(
                                if (ui.locationOff) "  Location off — day recorded without a fix (that's allowed)."
                                else "  Location logged (±${ui.lastFixAccuracyM?.toInt()} m)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }

            ui.message?.let {
                Entrance(index = 7) {
                    GlassCard(Modifier.fillMaxWidth()) { Text(it, color = MaterialTheme.colorScheme.onSurface) }
                }
            }

            Entrance(index = 8) {
                Text(
                    "GPS is logged with attendance, never required. Auto-checkout runs at 21:00 IST if you forget End Day.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        AnimatedVisibility(
            visible = showSuccess,
            enter = fadeIn(tween(200)) + scaleIn(initialScale = 0.7f),
            exit = fadeOut(tween(150)) + scaleOut(targetScale = 0.85f),
            modifier = Modifier.align(Alignment.Center),
        ) {
            SuccessCheck(size = 84.dp)
        }
      }
    }
}

@Composable
private fun StatusHero(day: DayState, busy: Boolean) {
    GlassCard(Modifier.fillMaxWidth(), cornerRadius = 28.dp, contentPadding = 24.dp) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
            PulsingRing(active = day == DayState.ON_DUTY || busy)
            val (label, sub) = when (day) {
                DayState.NOT_STARTED -> "Not started" to "Tap below to begin your day"
                DayState.ON_DUTY -> "On duty" to "Tracking your route"
                DayState.ENDED -> "Day ended" to "See you tomorrow"
            }
            Text(label, style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onSurface)
            Text(sub, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PulsingRing(active: Boolean) {
    val transition = rememberInfiniteTransition(label = "ring")
    val spin by transition.animateFloat(
        initialValue = 0f, targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(3200, easing = LinearEasing)),
        label = "spin",
    )
    val pulse by transition.animateFloat(
        initialValue = 0.85f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(900), RepeatMode.Reverse),
        label = "pulse",
    )
    Box(Modifier.size(96.dp), contentAlignment = Alignment.Center) {
        Box(
            Modifier
                .size(if (active) 96.dp * pulse else 96.dp)
                .rotate(if (active) spin else 0f)
                .glassSurface(shape = CircleShape, tint = if (active) EkoCyan else EkoBlue, solid = true),
        )
        Icon(
            if (active) Icons.Filled.Stop else Icons.Filled.PlayArrow,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(30.dp),
        )
    }
}

@Composable
private fun StatTile(
    modifier: Modifier = Modifier,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    label: String,
    value: String,
    caption: String,
) {
    GlassCard(modifier, cornerRadius = 18.dp, contentPadding = 14.dp) {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Icon(icon, contentDescription = null, tint = EkoCyan, modifier = Modifier.size(15.dp))
            }
            Text(value, style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
            Text(caption, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun formatHomeDistance(m: Double): String =
    if (m < 1000) "${m.roundToInt()} m away" else "${"%.1f".format(m / 1000)} km away"
