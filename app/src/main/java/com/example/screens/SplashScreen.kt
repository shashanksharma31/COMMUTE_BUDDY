package com.example.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.components.MapPinIcon
import com.example.ui.theme.MapsAccentBlue
import com.example.ui.theme.MapsSurface
import com.example.ui.theme.MapsTextPrimary
import com.example.ui.theme.RobotoFontFamily

// Splash screen: white background (#ffffff), blue (#1a73e8) map-pin icon and "CommuteBuddy" in Roboto Medium 22px centered
@Composable
fun SplashScreen(
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MapsSurface)
            .testTag("splash_screen"),
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            MapPinIcon(
                tint = MapsAccentBlue,
                iconSize = 24.dp,
                contentDesc = "CommuteBuddy map pin",
                modifier = Modifier.testTag("splash_map_pin_icon")
            )
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = "CommuteBuddy",
                fontFamily = RobotoFontFamily,
                fontWeight = FontWeight.Medium,
                fontSize = 22.sp,
                lineHeight = 28.sp,
                color = MapsTextPrimary,
                modifier = Modifier.testTag("splash_title")
            )
        }
    }
}
