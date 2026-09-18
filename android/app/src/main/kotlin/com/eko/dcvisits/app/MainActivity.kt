package com.eko.dcvisits.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eko.dcvisits.app.di.ServiceLocator
import com.eko.dcvisits.app.ui.components.AnimatedGlassBackground
import com.eko.dcvisits.app.ui.components.ProvideScreenInfo
import com.eko.dcvisits.app.ui.login.LoginScreen
import com.eko.dcvisits.app.ui.main.MainScaffold
import com.eko.dcvisits.app.ui.theme.DcVisitsTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DcVisitsTheme {
                ProvideScreenInfo {
                    Box(Modifier.fillMaxSize()) {
                        AnimatedGlassBackground()
                        RootNav()
                    }
                }
            }
        }
    }
}

@Composable
private fun RootNav() {
    val session by ServiceLocator.authRepository.sessionFlow.collectAsStateWithLifecycle(initialValue = null)
    AnimatedContent(
        targetState = session != null,
        transitionSpec = {
            (fadeIn(tween(420)) + scaleIn(tween(420), initialScale = 0.96f)) togetherWith
                (fadeOut(tween(200)) + scaleOut(tween(200), targetScale = 1.02f))
        },
        label = "auth-switch",
    ) { signedIn ->
        if (signedIn) {
            MainScaffold(onSignedOut = { /* sessionFlow drives the switch */ })
        } else {
            LoginScreen(onSignedIn = { /* sessionFlow drives the switch */ })
        }
    }
}
