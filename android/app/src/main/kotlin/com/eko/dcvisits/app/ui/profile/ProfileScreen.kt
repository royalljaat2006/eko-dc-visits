package com.eko.dcvisits.app.ui.profile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eko.dcvisits.app.di.ServiceLocator
import com.eko.dcvisits.app.ui.components.Entrance
import com.eko.dcvisits.app.ui.components.GhostGlassButton
import com.eko.dcvisits.app.ui.components.GlassCard
import com.eko.dcvisits.app.ui.components.ScreenContainer
import com.eko.dcvisits.app.ui.scorecard.ScorecardScreen
import com.eko.dcvisits.app.ui.theme.EkoBlue

/**
 * Identity + sign-out + (role-permitting) a path to the Scorecard. Deliberately
 * thin — every heavier action already has its own tab; Profile is where the
 * "who am I / how do I leave" affordances live so they're not hunting for the
 * avatar tap in the top bar.
 */
@Composable
fun ProfileScreen(role: String, onSignOut: () -> Unit) {
    val session by ServiceLocator.authRepository.sessionFlow.collectAsStateWithLifecycle(initialValue = null)
    var showScorecard by remember { mutableStateOf(false) }
    val hasScorecard = role == "DC" || role == "CIRCLE_HEAD"

    if (showScorecard) {
        Column(Modifier.fillMaxSize()) {
            TextButton(onClick = { showScorecard = false }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null, tint = EkoBlue, modifier = Modifier.padding(end = 6.dp))
                Text("Back to profile", color = EkoBlue)
            }
            ScorecardScreen()
        }
        return
    }

    ScreenContainer { pad ->
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(pad),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Entrance(index = 0) {
                Text("Profile", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
            }
            Entrance(index = 1) {
                GlassCard(Modifier.fillMaxWidth(), cornerRadius = 20.dp, contentPadding = 18.dp) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(session?.user?.name ?: "—", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurface)
                        Text(
                            role.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() },
                            style = MaterialTheme.typography.labelLarge,
                            color = EkoBlue,
                        )
                        Text("+91 ${session?.user?.phone ?: "—"}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        session?.user?.employee_code?.let {
                            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
            if (hasScorecard) {
                Entrance(index = 2) {
                    GlassCard(Modifier.fillMaxWidth(), onClick = { showScorecard = true }) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                Icon(Icons.Filled.Star, contentDescription = null, tint = EkoBlue)
                                Text("My Scorecard", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurface)
                            }
                            Text("View →", color = EkoBlue)
                        }
                    }
                }
            }
            Entrance(index = 3) {
                GhostGlassButton(text = "Sign out", onClick = onSignOut, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
