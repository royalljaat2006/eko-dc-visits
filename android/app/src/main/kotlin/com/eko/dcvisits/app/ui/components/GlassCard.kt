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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The base flat surface used everywhere: a solid fill, a hairline neutral
 * border and a soft, low-elevation shadow. Deliberately NOT translucent —
 * outdoor sunlight washes out low-contrast/translucent surfaces, so every
 * card is a solid, high-contrast rectangle instead (field-readability pass).
 * [tint] now controls a pale wash fill rather than a frosted gradient; pass
 * [solid] = true for a fully-saturated fill (e.g. a status dot or badge).
 */
fun Modifier.glassSurface(
    shape: Shape = RoundedCornerShape(16.dp),
    tint: Color = Color.White,
    fillAlphaTop: Float = 0.16f,
    fillAlphaBottom: Float = 0.05f,
    borderAlpha: Float = 0.28f,
    solid: Boolean = false,
): Modifier = this
    .shadow(elevation = 2.dp, shape = shape, ambientColor = Color.Black.copy(alpha = 0.10f), spotColor = Color.Black.copy(alpha = 0.10f))
    .clip(shape)
    .background(
        when {
            solid -> tint
            tint == Color.White -> Color.White
            else -> tint.copy(alpha = (fillAlphaTop + fillAlphaBottom).coerceAtMost(1f))
        },
    )
    .border(1.dp, Color.Black.copy(alpha = 0.06f), shape)

/** A flat card with inner padding — the default building block for every screen. */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    contentPadding: Dp = 16.dp,
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

/** Full-bleed flat panel (no inner padding) — for things like the bottom nav bar. */
@Composable
fun GlassPanel(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(24.dp),
    content: @Composable BoxScope.() -> Unit,
) {
    Box(modifier.glassSurface(shape = shape), content = content)
}

/** A pale tinted chip background — for tags/badges that shouldn't compete with cards. */
fun Modifier.glassChip(color: Color = Color.White): Modifier =
    this
        .clip(RoundedCornerShape(50))
        .background(color.copy(alpha = 0.12f))

/** An empty-state affordance (e.g. an unfilled photo slot) — dashed instead of solid so it reads as "tap to fill." */
fun Modifier.dashedBorder(
    color: Color = Color.Black.copy(alpha = 0.25f),
    cornerRadius: Dp = 16.dp,
    strokeWidth: Dp = 1.5.dp,
    dash: Dp = 8.dp,
    gap: Dp = 6.dp,
): Modifier = this
    .clip(RoundedCornerShape(cornerRadius))
    .background(Color.Black.copy(alpha = 0.03f))
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
