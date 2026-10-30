package com.example.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

// Google Maps Android Light Color Scheme
private val GoogleMapsLightScheme = lightColorScheme(
    primary = MapsAccentBlue,
    onPrimary = MapsSurface,
    primaryContainer = MapsSelectedTint,
    onPrimaryContainer = MapsAccentBlue,
    secondary = MapsTextSecondary,
    onSecondary = MapsSurface,
    secondaryContainer = MapsSelectedTint,
    onSecondaryContainer = MapsAccentBlue,
    tertiary = MapsAccentBlue,
    background = MapsBackground,
    onBackground = MapsTextPrimary,
    surface = MapsSurface,
    onSurface = MapsTextPrimary,
    surfaceVariant = MapsBackground,
    onSurfaceVariant = MapsTextSecondary,
    outline = MapsBorder,
    outlineVariant = MapsBorder
)

// 8px grid radii: Cards 12dp, Bottom Sheet top 16dp, Search pill & chips 50%
val MapsShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
    extraLarge = RoundedCornerShape(percent = 50)
)

@Composable
fun MyApplicationTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = GoogleMapsLightScheme,
        typography = Typography,
        shapes = MapsShapes,
        content = content
    )
}
