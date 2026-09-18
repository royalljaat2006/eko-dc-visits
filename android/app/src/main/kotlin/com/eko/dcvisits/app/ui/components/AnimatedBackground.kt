package com.eko.dcvisits.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import com.eko.dcvisits.app.ui.theme.EkoAmber
import com.eko.dcvisits.app.ui.theme.EkoBlue
import com.eko.dcvisits.app.ui.theme.EkoCyan
import com.eko.dcvisits.app.ui.theme.EkoViolet
import com.eko.dcvisits.app.ui.theme.SpaceDeep
import com.eko.dcvisits.app.ui.theme.SpaceMid
import kotlin.math.cos
import kotlin.math.sin

/**
 * The "wallpaper" every glass surface in the app floats over: a dark base plus
 * slow-drifting, heavily blurred colour blobs (the iOS Control-Center trick —
 * frosted panels only read as glass when there's something soft and colourful
 * behind them to refract). Blur is real on API 31+ and degrades to plain soft
 * gradients below that — never breaks, just slightly crisper.
 *
 * BATTERY: the drift is driven by an [Animatable] gated on the host lifecycle,
 * not a bare `rememberInfiniteTransition`. An 80dp blur with off-screen
 * compositing is genuinely expensive on the low-end BYOD phones this ships to,
 * and there is no reason to redraw it while the app is stopped (backgrounded,
 * screen off). When the lifecycle drops below STARTED the [LaunchedEffect] is
 * cancelled, the animation halts, and `t` simply holds its last value; it
 * resumes on the next ON_START. Nothing animates for pixels nobody can see.
 */
@Composable
fun AnimatedGlassBackground(modifier: Modifier = Modifier) {
    val lifecycleState by LocalLifecycleOwner.current.lifecycle.currentStateAsState()
    val animate = lifecycleState.isAtLeast(Lifecycle.State.STARTED)

    val t = remember { Animatable(0f) }
    LaunchedEffect(animate) {
        if (!animate) return@LaunchedEffect // effect cancelled → drift freezes in place
        t.animateTo(
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(24_000, easing = LinearEasing), RepeatMode.Restart),
        )
    }

    Canvas(
        modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(SpaceDeep, SpaceMid, SpaceDeep)))
            .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
            .blur(80.dp),
    ) {
        val angle = t.value * 2 * Math.PI.toFloat()
        fun blob(cx: Float, cy: Float, r: Float, color: Color) {
            drawCircle(
                brush = Brush.radialGradient(
                    listOf(color.copy(alpha = 0.55f), color.copy(alpha = 0f)),
                    center = Offset(cx, cy),
                    radius = r,
                ),
                radius = r,
                center = Offset(cx, cy),
            )
        }
        val w = size.width
        val h = size.height
        blob(w * 0.25f + cos(angle) * w * 0.12f, h * 0.18f + sin(angle) * h * 0.05f, w * 0.55f, EkoBlue)
        blob(w * 0.8f + sin(angle) * w * 0.1f, h * 0.35f + cos(angle) * h * 0.06f, w * 0.5f, EkoViolet)
        blob(w * 0.3f - cos(angle) * w * 0.1f, h * 0.85f, w * 0.6f, EkoCyan)
        blob(w * 0.75f, h * 0.9f - sin(angle) * h * 0.05f, w * 0.4f, EkoAmber)
    }
}
