package com.example

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import com.example.lib.configureAppCheck
import com.example.screens.MapScreen
import com.example.screens.SplashScreen
import com.example.ui.theme.MapsBackground
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        configureAppCheck(this, intent)
        // Set status bar and navigation bar to white (#ffffff) with dark icons
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(
                scrim = Color.WHITE,
                darkScrim = Color.WHITE
            ),
            navigationBarStyle = SystemBarStyle.light(
                scrim = Color.WHITE,
                darkScrim = Color.WHITE
            )
        )
        window.decorView.setBackgroundColor(Color.WHITE)
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        setContent {
            MyApplicationTheme {
                CommuteBuddyRoot()
            }
        }
    }
}

// Shows SplashScreen (white background) for 1.5s, fades out in 200ms ease, then reveals MapScreen
@Composable
fun CommuteBuddyRoot() {
    var showSplash by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        if (showSplash) {
            delay(1500L)
            showSplash = false
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MapsBackground),
        contentAlignment = Alignment.TopCenter
    ) {
        // Mobile viewport container (360-412dp width, 100dvh, no page scroll)
        Box(
            modifier = Modifier
                .fillMaxSize()
                .widthIn(max = 412.dp)
        ) {
            MapScreen(modifier = Modifier.fillMaxSize())

            AnimatedVisibility(
                visible = showSplash,
                exit = fadeOut(
                    animationSpec = tween(
                        durationMillis = 200,
                        easing = FastOutSlowInEasing
                    )
                )
            ) {
                SplashScreen(modifier = Modifier.fillMaxSize())
            }
        }
    }
}
