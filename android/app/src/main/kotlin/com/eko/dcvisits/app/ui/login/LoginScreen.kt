package com.eko.dcvisits.app.ui.login

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eko.dcvisits.app.ui.components.Entrance
import com.eko.dcvisits.app.ui.components.GlassCard
import com.eko.dcvisits.app.ui.components.GlassTextField
import com.eko.dcvisits.app.ui.components.GlowButton
import com.eko.dcvisits.app.ui.components.glassSurface
import com.eko.dcvisits.app.ui.theme.EkoBlue

@Composable
fun LoginScreen(
    onSignedIn: () -> Unit,
    vm: LoginViewModel = viewModel(),
) {
    val s by vm.state.collectAsStateWithLifecycle()

    Box(Modifier.fillMaxSize().safeDrawingPadding(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .imePadding()
                .widthIn(max = 460.dp)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(20.dp, Alignment.CenterVertically),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Entrance(index = 0) {
                Box(
                    Modifier.size(72.dp).glassSurface(shape = CircleShape, tint = EkoBlue, solid = true),
                    contentAlignment = Alignment.Center,
                ) {
                    Icon(Icons.Filled.Bolt, contentDescription = null, tint = Color.White, modifier = Modifier.size(34.dp))
                }
            }
            Entrance(index = 1) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("Eko CSP Visits", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.onBackground)
                    Text(
                        "Sign in with your registered mobile number",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }

            Entrance(index = 2) {
                GlassCard(Modifier.fillMaxWidth(), cornerRadius = 28.dp, contentPadding = 22.dp) {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        AnimatedContent(
                            targetState = s.step,
                            transitionSpec = {
                                (slideInHorizontally(tween(280)) { it / 3 } + fadeIn(tween(280))) togetherWith
                                    (slideOutHorizontally(tween(200)) { -it / 3 } + fadeOut(tween(180)))
                            },
                            label = "login-step",
                        ) { step ->
                            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                when {
                                    step == LoginUiState.Step.PHONE -> {
                                        GlassTextField(
                                            value = s.phone,
                                            onValueChange = vm::onPhone,
                                            label = "Mobile number",
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                                        )
                                        GlowButton(
                                            text = "Send OTP",
                                            onClick = vm::requestOtp,
                                            enabled = s.phoneValid && !s.loading,
                                            loading = s.loading,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        Text(
                                            "Use your registered Eko mobile number. An OTP is sent by SMS.",
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    else -> {
                                        Text("OTP sent to +91 ${s.phone}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
                                        GlassTextField(
                                            value = s.otp,
                                            onValueChange = vm::onOtp,
                                            label = "6-digit OTP",
                                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                                        )
                                        if (s.needsName) {
                                            Text(
                                                "New number — you're registering as a DC. What's your name?",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            )
                                            GlassTextField(
                                                value = s.name,
                                                onValueChange = vm::onName,
                                                label = "Full name",
                                            )
                                        }
                                        GlowButton(
                                            text = "Verify & sign in",
                                            onClick = { vm.verify(onSignedIn) },
                                            enabled = s.canVerify && !s.loading,
                                            loading = s.loading,
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                        TextButton(onClick = vm::back) { Text("Change number", color = EkoBlue) }
                                    }
                                }
                            }
                        }

                        if (s.loading) CircularProgressIndicator(color = EkoBlue, modifier = Modifier.size(20.dp))
                        s.error?.let {
                            Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                }
            }
        }
    }
}
