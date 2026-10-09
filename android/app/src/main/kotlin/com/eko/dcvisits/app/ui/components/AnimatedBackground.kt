package com.eko.dcvisits.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The page background every screen sits on: a flat, neutral fill — no motion,
 * no blur. The previous version was an animated, 80dp-blurred gradient
 * (the "iOS Control Center" look); replaced because (a) a moving, low-contrast
 * backdrop actively hurts outdoor-sunlight legibility, and (b) continuous
 * offscreen-composited blur has a real battery cost on the low-end BYOD phones
 * this ships to. Name kept (one call site in MainActivity.kt) even though the
 * glass effect it originally existed for is gone.
 */
@Composable
fun AnimatedGlassBackground(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background))
}
