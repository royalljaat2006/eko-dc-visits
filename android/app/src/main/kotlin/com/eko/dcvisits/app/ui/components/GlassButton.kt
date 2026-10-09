package com.eko.dcvisits.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
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
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.eko.dcvisits.app.ui.theme.EkoBlue

/** Minimum touch target on both axes (field-use: large thumbs, gloves, motion). */
private val MinTouchTarget = 52.dp

/** The one primary call-to-action per screen — a solid, high-contrast pill. */
@Composable
fun GlowButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    loading: Boolean = false,
    fill: Color = EkoBlue,
) {
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .pressScale(interaction)
            .alpha(if (enabled) 1f else 0.4f)
            .defaultMinSize(minHeight = MinTouchTarget)
            .clip(CircleShape)
            .background(fill)
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

/** A secondary, quieter action — an outlined pill instead of a solid fill. */
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
            .defaultMinSize(minHeight = MinTouchTarget)
            .clip(CircleShape)
            .background(Color.White)
            .border(1.5.dp, EkoBlue, CircleShape)
            .selectable(selected = false, enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .padding(vertical = 14.dp, horizontal = 22.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, style = MaterialTheme.typography.labelLarge, color = EkoBlue, textAlign = TextAlign.Center)
    }
}

/** A pill-shaped status tag, e.g. distance/visit-status/sync labels — pale fill, tint-coloured text (both already WCAG-AA on white). */
@Composable
fun GlassTag(
    text: String,
    modifier: Modifier = Modifier,
    tint: Color = EkoBlue,
    contentColor: Color = tint,
) {
    Row(
        modifier
            .glassChip(tint)
            .padding(PaddingValues(horizontal = 12.dp, vertical = 6.dp)),
    ) {
        Text(text, color = contentColor, style = MaterialTheme.typography.labelLarge)
    }
}
