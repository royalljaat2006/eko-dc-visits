package com.eko.dcvisits.app.ui.components

import android.content.res.Configuration
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Width buckets — the same thresholds Material's WindowSizeClass uses, computed
 *  by hand so we take no extra dependency and it just works in both orientations. */
enum class WidthClass { COMPACT, MEDIUM, EXPANDED }

data class ScreenInfo(
    val widthClass: WidthClass,
    val widthDp: Int,
    val heightDp: Int,
    val isLandscape: Boolean,
) {
    /** Horizontal page gutter — tighter on tiny phones, roomier on tablets. */
    val pagePadding: Dp
        get() = when (widthClass) {
            WidthClass.COMPACT -> if (widthDp <= 360) 14.dp else 18.dp
            WidthClass.MEDIUM -> 24.dp
            WidthClass.EXPANDED -> 32.dp
        }

    /** Content never stretches past this — cards stay readable on wide screens. */
    val contentMaxWidth: Dp
        get() = when (widthClass) {
            WidthClass.COMPACT -> Dp.Infinity
            WidthClass.MEDIUM -> 620.dp
            WidthClass.EXPANDED -> 720.dp
        }

    /** Two-column list layouts pay off from MEDIUM up. */
    val listColumns: Int get() = if (widthClass == WidthClass.COMPACT) 1 else 2

    /** Left navigation rail instead of a bottom bar on wide/landscape-wide screens. */
    val useNavRail: Boolean get() = widthClass == WidthClass.EXPANDED || (isLandscape && widthClass == WidthClass.MEDIUM)
}

val LocalScreen = compositionLocalOf {
    ScreenInfo(WidthClass.COMPACT, 360, 780, isLandscape = false)
}

@Composable
fun ProvideScreenInfo(content: @Composable () -> Unit) {
    val cfg = LocalConfiguration.current
    val info = remember(cfg.screenWidthDp, cfg.screenHeightDp, cfg.orientation) {
        val w = cfg.screenWidthDp
        ScreenInfo(
            widthClass = when {
                w < 600 -> WidthClass.COMPACT
                w < 840 -> WidthClass.MEDIUM
                else -> WidthClass.EXPANDED
            },
            widthDp = w,
            heightDp = cfg.screenHeightDp,
            isLandscape = cfg.orientation == Configuration.ORIENTATION_LANDSCAPE,
        )
    }
    CompositionLocalProvider(LocalScreen provides info, content = content)
}

/**
 * The wrapper every screen's body sits in: applies the responsive horizontal
 * gutter, caps content width and centres it on wide screens, and pads the
 * bottom clear of the floating nav bar + system nav inset. Use it around a
 * scrollable so long content never hides behind the nav.
 */
@Composable
fun ScreenContainer(
    modifier: Modifier = Modifier,
    navBarClearance: Dp = 96.dp,
    content: @Composable (contentPadding: PaddingValues) -> Unit,
) {
    val screen = LocalScreen.current
    val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    val innerPadding = PaddingValues(
        start = screen.pagePadding,
        end = screen.pagePadding,
        top = 8.dp,
        bottom = navBarClearance + bottomInset + 8.dp,
    )
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier
                .fillMaxWidth()
                .then(if (screen.contentMaxWidth == Dp.Infinity) Modifier else Modifier.widthIn(max = screen.contentMaxWidth)),
        ) {
            content(innerPadding)
        }
    }
}

/** Safe-area padding for full-bleed overlays (camera). */
@Composable
fun safeDrawingPadding(): PaddingValues = WindowInsets.safeDrawing.asPaddingValues()
