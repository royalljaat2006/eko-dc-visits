package com.eko.dcvisits.app.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Staggered fade + rise-in for list/grid items — every screen's cards use this
 * so the first paint never feels like a static form dump. [index] staggers the
 * start time; pass the item's position in its list.
 */
@Composable
fun Entrance(
    index: Int = 0,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val alpha = remember(index) { Animatable(0f) }
    val rise = remember(index) { Animatable(28f) }
    LaunchedEffect(index) {
        delay((index * 55L).coerceAtMost(400L))
        launch { alpha.animateTo(1f, tween(380, easing = FastOutSlowInEasing)) }
        launch { rise.animateTo(0f, spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = 220f)) }
    }
    Box(
        modifier.graphicsLayer {
            this.alpha = alpha.value
            translationY = rise.value
        },
    ) { content() }
}
