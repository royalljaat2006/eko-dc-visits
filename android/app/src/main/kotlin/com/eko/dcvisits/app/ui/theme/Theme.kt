package com.eko.dcvisits.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// A deep-space navy base — glass cards read best floating over something dark
// and slightly saturated, the way iOS's frosted sheets sit over a wallpaper.
val SpaceDeep = Color(0xFF0A0E1A)
val SpaceMid = Color(0xFF121834)
val EkoBlue = Color(0xFF3E8BFF)
val EkoCyan = Color(0xFF5FE0D0)
val EkoViolet = Color(0xFF8B6BFF)
val EkoPink = Color(0xFFFF6FA5)
val EkoAmber = Color(0xFFFFB454)

private val DarkColors = darkColorScheme(
    primary = EkoBlue,
    onPrimary = Color.White,
    secondary = EkoCyan,
    tertiary = EkoAmber,
    background = SpaceDeep,
    onBackground = Color(0xFFF2F4FF),
    surface = SpaceMid,
    onSurface = Color(0xFFF2F4FF),
    surfaceVariant = Color(0xFF1B2244),
    onSurfaceVariant = Color(0xFFB6BEE0),
    error = Color(0xFFFF6B6B),
    outline = Color(0x33FFFFFF),
)

private val LightColors = lightColorScheme(
    primary = EkoBlue,
    secondary = EkoViolet,
    tertiary = EkoAmber,
    background = Color(0xFFEFF2FB),
    surface = Color.White,
)

private val Display = FontWeight.Bold
private val AppTypography = Typography().let { base ->
    base.copy(
        headlineSmall = base.headlineSmall.copy(fontWeight = Display, letterSpacing = 0.2.sp),
        headlineMedium = base.headlineMedium.copy(fontWeight = Display, letterSpacing = 0.1.sp),
        titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        titleSmall = base.titleSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 0.4.sp),
        labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
        bodyMedium = base.bodyMedium.copy(letterSpacing = 0.15.sp),
    )
}

/** True while the app is on the dark, glassy surface — screens use this to pick glass tints. */
@Composable
fun isGlassDark(): Boolean = true // the whole app commits to the dark glass look, per design

@Composable
fun DcVisitsTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = DarkColors, // glass aesthetic is dark-first by design; see isGlassDark()
        typography = AppTypography,
        content = content,
    )
}
