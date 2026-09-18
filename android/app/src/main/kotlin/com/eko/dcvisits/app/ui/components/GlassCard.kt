package com.eko.dcvisits.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The base frosted surface used everywhere: a soft vertical sheen, a hairline
 * light border, and a diffuse shadow — glass without needing a true backdrop
 * blur (there's nothing but the [AnimatedGlassBackground] behind it anyway).
 */
fun Modifier.glassSurface(
    shape: Shape = RoundedCornerShape(24.dp),
    tint: Color = Color.White,
    fillAlphaTop: Float = 0.16f,
    fillAlphaBottom: Float = 0.05f,
    borderAlpha: Float = 0.28f,
): Modifier = this
    .shadow(elevation = 18.dp, shape = shape, ambientColor = Color.Black.copy(alpha = 0.5f), spotColor = Color.Black.copy(alpha = 0.5f))
    .clip(shape)
    .background(
        Brush.linearGradient(
            colors = listOf(tint.copy(alpha = fillAlphaTop), tint.copy(alpha = fillAlphaBottom)),
            start = Offset(0f, 0f),
            end = Offset(0f, 400f),
        ),
    )
    .border(1.dp, Brush.linearGradient(listOf(tint.copy(alpha = borderAlpha), tint.copy(alpha = borderAlpha * 0.3f))), shape)

/** A frosted card with inner padding — the default building block for every screen. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 24.dp,
    contentPadding: Dp = 18.dp,
    tint: Color = Color.White,
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit,
) {
    val shape = RoundedCornerShape(cornerRadius)
    val interaction = remember { MutableInteractionSource() }
    val base = Modifier
        .glassSurface(shape = shape, tint = tint)
        .let { m -> if (onClick != null) m.clickable(interactionSource = interaction, indication = null, onClick = onClick) else m }
    Box(base.padding(contentPadding), content = content)
}

/** Full-bleed glass panel (no inner padding) — for things like the bottom nav bar. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(28.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.glassSurface(shape = shape, fillAlphaTop = 0.22f, fillAlphaBottom = 0.10f), content = content)
}

/** A borderless glass chip — for tags/badges that shouldn't compete with cards. */
fun Modifier.glassChip(color: Color = Color.White): Modifier =
    this.glassSurface(shape = RoundedCornerShape(50), tint = color, fillAlphaTop = 0.18f, fillAlphaBottom = 0.10f, borderAlpha = 0.3f)

/** An empty-state affordance (e.g. an unfilled photo slot) — dashed instead of solid so it reads as "tap to fill." */
fun Modifier.dashedBorder(
    color: Color = Color.White.copy(alpha = 0.35f),
    cornerRadius: Dp = 16.dp,
    strokeWidth: Dp = 1.5.dp,
    dash: Dp = 8.dp,
    gap: Dp = 6.dp,
): Modifier = this
    .clip(RoundedCornerShape(cornerRadius))
    .background(Color.White.copy(alpha = 0.04f))
    .drawWithContent {
        drawContent()
        val strokePx = strokeWidth.toPx()
        val inset = strokePx / 2
        drawRoundRect(
            color = color,
            topLeft = Offset(inset, inset),
            size = Size(size.width - strokePx, size.height - strokePx),
            cornerRadius = CornerRadius(cornerRadius.toPx(), cornerRadius.toPx()),
            style = Stroke(width = strokePx, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash.toPx(), gap.toPx()), 0f)),
        )
    }
