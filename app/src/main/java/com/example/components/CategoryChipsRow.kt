package com.example.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.DirectionsRailway
import androidx.compose.material.icons.outlined.DirectionsSubway
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.TransitMode
import com.example.ui.theme.MapsAccentBlue
import com.example.ui.theme.MapsBorder
import com.example.ui.theme.MapsSelectedTint
import com.example.ui.theme.MapsSurface
import com.example.ui.theme.MapsTextPrimary
import com.example.ui.theme.MapsTextSecondary

// Google Maps rounded filter chips row (48dp height, #e8f0fe selected tint)
@Composable
fun CategoryChipsRow(
    selectedMode: TransitMode,
    onSelectMode: (TransitMode) -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        TransitMode.entries.forEach { mode ->
            val isSelected = mode == selectedMode
            val icon = when (mode) {
                TransitMode.ALL -> Icons.Outlined.Map
                TransitMode.SUBWAY -> Icons.Outlined.DirectionsSubway
                TransitMode.BUS -> Icons.Outlined.DirectionsBus
                TransitMode.RAIL -> Icons.Outlined.DirectionsRailway
            }

            OutlinedButton(
                onClick = { onSelectMode(mode) },
                shape = CircleShape,
                border = BorderStroke(
                    width = 1.dp,
                    color = if (isSelected) MapsAccentBlue else MapsBorder
                ),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (isSelected) MapsSelectedTint else MapsSurface,
                    contentColor = if (isSelected) MapsAccentBlue else MapsTextPrimary
                ),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .shadow(elevation = 1.dp, shape = CircleShape, clip = false)
                    .testTag("filter_chip_${mode.name.lowercase()}")
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = mode.label,
                    tint = if (isSelected) MapsAccentBlue else MapsTextSecondary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = mode.label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (isSelected) MapsAccentBlue else MapsTextPrimary
                )
            }
        }
    }
}
