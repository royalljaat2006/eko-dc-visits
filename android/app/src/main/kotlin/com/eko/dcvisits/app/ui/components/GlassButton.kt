package com.eko.dcvisits.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.eko.dcvisits.app.ui.theme.EkoBlue
import com.eko.dcvisits.app.ui.theme.EkoViolet

/** The one call-to-action per screen — a bold gradient pill with iOS-style press spring. */
@Composable
fun GlowButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    gradient: List<Color> = listOf(EkoBlue, EkoViolet),
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(interaction)
            .alpha(if (enabled) 1f else 0.4f)
            .clip(CircleShape)
            .background(Brush.horizontalGradient(gradient))
            .selectable(selected = false, enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 15.dp, horizontal = 24.dp),
        contentAlignment = Alignment.Center,
    ) {
        CompositionLocalProvider(LocalContentColor provides Color.White) {
            if (loading) {
                CircularProgressIndicator(modifier = Modifier.padding(2.dp), strokeWidth = 2.dp, color = Color.White)
            } else {
                Text(text, style = MaterialTheme.typography.labelLarge, textAlign = TextAlign.Center)
            }
        }
    }
}

/** A secondary, quieter action — translucent glass instead of a solid gradient. */
@Composable
fun GhostGlassButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(interaction)
            .alpha(if (enabled) 1f else 0.4f)
            .glassSurface(shape = CircleShape, fillAlphaTop = 0.14f, fillAlphaBottom = 0.06f)
            .selectable(selected = false, enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = Color.White)
    }
}

/** A pill-shaped glass tag, e.g. status/category labels. */
@Composable
fun GlassTag(
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
    contentColor: Color = Color.White,
) {
    Row(
        modifier
            .glassChip(tint)
            .padding(PaddingValues(horizontal = 12.dp, vertical = 6.dp)),
    ) {
        Text(text, color = contentColor, style = MaterialTheme.typography.labelLarge)
    }
}
