package com.eko.dcvisits.app.ui.scorecard

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eko.dcvisits.app.ui.components.Entrance
import com.eko.dcvisits.app.ui.components.GlassCard
import com.eko.dcvisits.app.ui.components.GlassTag
import com.eko.dcvisits.app.ui.components.GlowButton
import com.eko.dcvisits.app.ui.components.ScreenContainer
import com.eko.dcvisits.app.ui.theme.EkoAmber
import com.eko.dcvisits.app.ui.theme.EkoBlue
import com.eko.dcvisits.app.ui.theme.EkoCyan
import com.eko.dcvisits.app.ui.theme.EkoViolet

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScorecardScreen(vm: ScorecardViewModel = viewModel()) {
    val context = LocalContext.current
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { vm.load() }

    ScreenContainer { pad ->
     Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad),
        verticalArrangement = Arrangement.spacedBy(16.dp),
     ) {
        Entrance(index = 0) {
            Column {
                Text("My scorecard", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
                Text(
                    "dc_score_v1 — transparent, never affects pay.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }

        if (ui.loading) CircularProgressIndicator(color = EkoBlue)

        ui.card?.let { c ->
            Entrance(index = 1) {
                GlassCard(Modifier.fillMaxWidth(), cornerRadius = 28.dp, contentPadding = 22.dp) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Column(
                            Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                        ) {
                            c.badges.firstOrNull()?.let { GlassTag(it.replace('_', ' '), tint = EkoCyan) }
                            ScoreRing(points = c.points)
                        }
                        Text(
                            "Visits ${c.visits_done}  ·  geo-verified ${c.geo_verified_visits}  ·  streak ${c.streak_days}d",
                            color = MaterialTheme.colorScheme.onSurface,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        if (c.badges.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                val tints = listOf(EkoCyan, EkoAmber, EkoViolet)
                                c.badges.forEachIndexed { i, b -> GlassTag(b.replace('_', ' '), tint = tints[i % tints.size]) }
                            }
                        }
                        Text(
                            "Formula: visit +50 · geo-verified +20 · on-time (≤09:30 IST) +30 · streak-day +10 (cap 7).",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }

        ui.dashboardUrl?.let { url ->
            Entrance(index = 2) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    GlowButton(
                        text = "Open My Dashboard",
                        onClick = { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        "This link is yours only.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }

        ui.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
     }
    }
}

/**
 * A ring reveal (sweeps to a full circle) around the count-up number — purely
 * decorative, not a percent-of-max. dc_score_v1 is an open accumulator
 * (visit +50 each, no visit cap), so there is no real "out of N" to plot;
 * showing one anyway would misstate the formula.
 */
@Composable
private fun ScoreRing(points: Int) {
    val sweep = remember { Animatable(0f) }
    val count = remember { Animatable(0f) }
    LaunchedEffect(points) {
        sweep.animateTo(360f, tween(1100, easing = FastOutSlowInEasing))
    }
    LaunchedEffect(points) { count.animateTo(points.toFloat(), tween(900)) }

    Box(Modifier.size(176.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val stroke = Stroke(width = 14.dp.toPx(), cap = StrokeCap.Round)
            drawArc(color = Color.Black.copy(alpha = 0.08f), startAngle = -90f, sweepAngle = 360f, useCenter = false, style = stroke)
            drawArc(
                brush = Brush.sweepGradient(listOf(EkoBlue, EkoCyan, EkoViolet, EkoBlue)),
                startAngle = -90f,
                sweepAngle = sweep.value,
                useCenter = false,
                style = stroke,
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${count.value.toInt()}", style = MaterialTheme.typography.headlineLarge, color = MaterialTheme.colorScheme.onSurface)
            Text("points today", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
