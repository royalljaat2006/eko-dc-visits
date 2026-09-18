package com.eko.dcvisits.app.ui.main

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.RateReview
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eko.dcvisits.app.data.repo.DayState
import com.eko.dcvisits.app.di.ServiceLocator
import com.eko.dcvisits.app.tracking.TrackingService
import com.eko.dcvisits.app.ui.approvals.ApprovalsScreen
import com.eko.dcvisits.app.ui.attendance.AttendanceScreen
import com.eko.dcvisits.app.ui.attendance.AttendanceViewModel
import com.eko.dcvisits.app.ui.components.GlassPanel
import com.eko.dcvisits.app.ui.components.LocalScreen
import com.eko.dcvisits.app.ui.components.glassSurface
import com.eko.dcvisits.app.ui.components.pressScale
import com.eko.dcvisits.app.ui.csp.CspDetailsScreen
import com.eko.dcvisits.app.ui.scorecard.ScorecardScreen
import com.eko.dcvisits.app.ui.visits.VisitsScreen
import kotlinx.coroutines.launch

private enum class Tab(val label: String, val icon: ImageVector, val dcGated: Boolean) {
    ATTENDANCE("Today", Icons.Filled.CheckCircle, dcGated = false),
    VISITS("Visits", Icons.AutoMirrored.Filled.List, dcGated = true),
    CSPS("My CSPs", Icons.Filled.Place, dcGated = true),
    APPROVALS("Approvals", Icons.Filled.RateReview, dcGated = false),
    SCORECARD("Score", Icons.Filled.Star, dcGated = true),
}

@Composable
fun MainScaffold(
    onSignedOut: () -> Unit,
    attendanceVm: AttendanceViewModel = viewModel(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val screen = LocalScreen.current
    val session by ServiceLocator.authRepository.sessionFlow.collectAsStateWithLifecycle(initialValue = null)
    val day by attendanceVm.dayState.collectAsStateWithLifecycle()

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { }
    LaunchedEffect(Unit) {
        permLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION),
        )
    }

    val role = session?.user?.role ?: "DC"
    val tabs = when (role) {
        "DC" -> listOf(Tab.ATTENDANCE, Tab.VISITS, Tab.CSPS, Tab.SCORECARD)
        "CIRCLE_HEAD" -> listOf(Tab.ATTENDANCE, Tab.APPROVALS, Tab.SCORECARD)
        "CORPORATE_ADMIN" -> listOf(Tab.ATTENDANCE, Tab.APPROVALS)
        else -> listOf(Tab.ATTENDANCE)
    }
    var selected by rememberSaveable { mutableStateOf(Tab.ATTENDANCE) }
    val gated = role == "DC" && day == DayState.NOT_STARTED

    LaunchedEffect(role, day) {
        if (role == "DC" && day == DayState.ON_DUTY) TrackingService.start(context)
        else if (day == DayState.ENDED) TrackingService.stop(context)
    }

    val onSignOut = { scope.launch { ServiceLocator.authRepository.signOut(); onSignedOut() }; Unit }
    val body: @Composable () -> Unit = {
        Box(Modifier.fillMaxSize()) {
            AnimatedContent(
                targetState = selected,
                transitionSpec = { fadeIn(tween(260)) togetherWith fadeOut(tween(140)) },
                label = "tab-content",
            ) { tab ->
                when {
                    tab != Tab.ATTENDANCE && role == "DC" && gated -> GateNotice()
                    tab == Tab.ATTENDANCE -> AttendanceScreen(attendanceVm)
                    tab == Tab.VISITS -> VisitsScreen()
                    tab == Tab.CSPS -> CspDetailsScreen()
                    tab == Tab.APPROVALS -> ApprovalsScreen()
                    tab == Tab.SCORECARD -> ScorecardScreen()
                    else -> AttendanceScreen(attendanceVm)
                }
            }
        }
    }

    if (screen.useNavRail) {
        // Tablet / wide-landscape: a vertical rail on the leading edge.
        Row(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
            SideGlassRail(tabs, selected, gated, onSelect = { selected = it }, onSignOut = onSignOut, name = session?.user?.name ?: "Eko")
            Column(Modifier.fillMaxSize()) {
                TopBar(session?.user?.name ?: "Eko CSP Visits", role, onSignOut, showAvatar = false)
                Box(Modifier.weight(1f)) { body() }
            }
        }
    } else {
        Column(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Horizontal))) {
            TopBar(session?.user?.name ?: "Eko CSP Visits", role, onSignOut, showAvatar = true)
            Box(Modifier.weight(1f).fillMaxWidth()) { body() }
            BottomGlassNav(tabs, selected, gated, onSelect = { selected = it })
        }
    }
}

@Composable
private fun TopBar(name: String, role: String, onSignOut: () -> Unit, showAvatar: Boolean) {
    val screen = LocalScreen.current
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = screen.pagePadding, vertical = 12.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f, fill = false)) {
            Text(name, style = MaterialTheme.typography.titleLarge, color = Color.White, maxLines = 1)
            Text(
                role.replace('_', ' ').lowercase().replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (showAvatar) {
            Box(
                Modifier
                    .pressScale()
                    .size(40.dp)
                    .glassSurface(shape = CircleShape, fillAlphaTop = 0.16f, fillAlphaBottom = 0.06f)
                    .clickable(onClick = onSignOut),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Sign out", tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun BottomGlassNav(tabs: List<Tab>, selected: Tab, gated: Boolean, onSelect: (Tab) -> Unit) {
    val screen = LocalScreen.current
    GlassPanel(
        Modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(horizontal = screen.pagePadding, vertical = 10.dp),
        shape = RoundedCornerShape(30.dp),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            tabs.forEach { tab ->
                NavItem(tab, selected == tab, enabled = !(tab.dcGated && gated), onClick = { onSelect(tab) })
            }
        }
    }
}

@Composable
private fun SideGlassRail(
    tabs: List<Tab>,
    selected: Tab,
    gated: Boolean,
    onSelect: (Tab) -> Unit,
    onSignOut: () -> Unit,
    name: String,
) {
    GlassPanel(
        Modifier
            .fillMaxHeight()
            .systemBarsPadding()
            .padding(start = 12.dp, top = 12.dp, bottom = 12.dp)
            .width(96.dp),
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(vertical = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            tabs.forEach { tab ->
                NavItem(tab, selected == tab, enabled = !(tab.dcGated && gated), onClick = { onSelect(tab) })
            }
            Box(Modifier.weight(1f).size(1.dp))
            Box(
                Modifier
                    .pressScale()
                    .size(44.dp)
                    .glassSurface(shape = CircleShape, fillAlphaTop = 0.16f, fillAlphaBottom = 0.06f)
                    .clickable(onClick = onSignOut),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = "Sign out", tint = Color.White, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun NavItem(tab: Tab, isSelected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val highlight by animateFloatAsState(if (isSelected) 1f else 0f, label = "navHighlight")
    val tint = if (isSelected) Color.White else Color.White.copy(alpha = 0.5f)

    Column(
        Modifier
            .pressScale()
            .clip(RoundedCornerShape(18.dp))
            .background(Color.White.copy(alpha = 0.16f * highlight))
            .clickable(enabled = enabled, onClick = onClick)
            .alpha(if (enabled) 1f else 0.35f)
            .padding(horizontal = 12.dp, vertical = 9.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(tab.icon, contentDescription = tab.label, tint = tint, modifier = Modifier.size(21.dp))
        AnimatedVisibility(visible = isSelected) {
            Text(
                tab.label,
                color = Color.White,
                style = MaterialTheme.typography.labelSmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 3.dp),
            )
        }
    }
}

@Composable
private fun GateNotice() {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Box(Modifier.glassSurface(shape = RoundedCornerShape(24.dp)).padding(24.dp)) {
            Text(
                "Mark attendance (Check In) to unlock the other sections.",
                color = Color.White,
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center,
            )
        }
    }
}
