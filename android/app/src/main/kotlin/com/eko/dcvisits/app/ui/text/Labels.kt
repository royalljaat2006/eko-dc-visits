package com.eko.dcvisits.app.ui.text

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.eko.dcvisits.app.R
import kotlin.math.roundToInt

/** Display label for a server role code (the code itself is never translated). */
@StringRes
private fun roleLabelRes(role: String): Int? = when (role) {
    "DC" -> R.string.role_dc
    "CIRCLE_HEAD" -> R.string.role_circle_head
    "NATIONAL_HEAD" -> R.string.role_national_head
    "HR_ADMIN" -> R.string.role_hr_admin
    "CORPORATE_ADMIN" -> R.string.role_corporate_admin
    "BANK_OFFICIAL" -> R.string.role_bank_official
    else -> null
}

@Composable
fun roleLabel(role: String): String =
    roleLabelRes(role)?.let { stringResource(it) }
        ?: role.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() }

/** "120 m away" / "1.4 km away". */
@Composable
fun distanceAway(m: Double): String =
    if (m < 1000) stringResource(R.string.distance_m_away, m.roundToInt())
    else stringResource(R.string.distance_km_away, m / 1000)

/** "120 m" / "1.4 km". */
@Composable
fun distanceLabel(m: Double): String =
    if (m < 1000) stringResource(R.string.distance_m, m.roundToInt())
    else stringResource(R.string.distance_km, m / 1000)
