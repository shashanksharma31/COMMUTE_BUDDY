package com.example.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.ui.theme.MapsAccentBlue
import com.example.ui.theme.MapsBorder
import com.example.ui.theme.MapsSurface

// Round "my location" button: white (#ffffff), 48px, soft shadow
@Composable
fun MyLocationButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MapsSurface,
        border = BorderStroke(1.dp, MapsBorder),
        modifier = modifier
            .size(48.dp)
            .shadow(elevation = 2.dp, shape = CircleShape, clip = false)
            .testTag("my_location_button")
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            LocateIcon(
                tint = MapsAccentBlue,
                iconSize = 24.dp,
                contentDesc = stringResource(R.string.recenter_map)
            )
        }
    }
}
