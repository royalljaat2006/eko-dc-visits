package com.eko.dcvisits.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// Light, high-contrast, neutral-background palette (field-readability pass).
// Accent colours double as both text/icon tint on light surfaces AND button
// fills with white text on top — every value below is dark/saturated enough
// to clear WCAG AA (4.5:1) against white in both directions.
val EkoBlue = Color(0xFF1D4ED8) // primary accent + CTA fill
val EkoViolet = Color(0xFF6D28D9) // secondary accent
val EkoCyan = Color(0xFF0E7490)
val EkoPink = Color(0xFFBE185D)
val EkoAmber = Color(0xFFB45309) // warning
val EkoGreen = Color(0xFF15803D) // success / live
val EkoRed = Color(0xFFB91C1C) // danger / error

private val PageBackground = Color(0xFFF7F8FC)
private val OnPageText = Color(0xFF14161B)
private val SurfaceVariantLight = Color(0xFFEDEFF6)
private val OnSurfaceVariantLight = Color(0xFF53586B)

private val AppColors = lightColorScheme(
    primary = EkoBlue,
    onPrimary = Color.White,
    secondary = EkoViolet,
    onSecondary = Color.White,
    tertiary = EkoAmber,
    onTertiary = Color.White,
    background = PageBackground,
    onBackground = OnPageText,
    surface = Color.White,
    onSurface = OnPageText,
    surfaceVariant = SurfaceVariantLight,
    onSurfaceVariant = OnSurfaceVariantLight,
    error = EkoRed,
    onError = Color.White,
    outline = Color(0x1F14161B),
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

/**
 * One deliberate look, regardless of system dark-mode: a light, neutral,
 * high-contrast surface reads far better than a dark one in direct outdoor
 * sunlight, which is the dominant use case for this app (field DC visits).
 */
@Composable
fun DcVisitsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = AppColors,
        typography = AppTypography,
        content = content,
    )
}
