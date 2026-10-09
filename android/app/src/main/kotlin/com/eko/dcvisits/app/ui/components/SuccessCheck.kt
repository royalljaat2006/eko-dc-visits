package com.eko.dcvisits.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.eko.dcvisits.app.ui.theme.EkoGreen

/** A one-shot spring "pop" checkmark for confirmations (check-in, photo queued, decision applied). */
@Composable
fun SuccessCheck(modifier: Modifier = Modifier, size: androidx.compose.ui.unit.Dp = 56.dp) {
    val scale = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        scale.animateTo(1f, spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow))
    }
    Box(
        modifier
            .size(size)
            .graphicsLayer { scaleX = scale.value; scaleY = scale.value }
            .glassSurface(shape = CircleShape, tint = EkoGreen, solid = true),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Filled.Check, contentDescription = null, tint = Color.White, modifier = Modifier.size(size * 0.5f))
    }
}
