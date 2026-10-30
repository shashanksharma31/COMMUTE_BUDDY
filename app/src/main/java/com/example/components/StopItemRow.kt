package com.example.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.DirectionsRailway
import androidx.compose.material.icons.outlined.DirectionsSubway
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.TransitMode
import com.example.data.TransitStop
import com.example.lib.CommuteFormatters
import com.example.ui.theme.MapsAccentBlue
import com.example.ui.theme.MapsBorder
import com.example.ui.theme.MapsSelectedTint
import com.example.ui.theme.MapsSurface
import com.example.ui.theme.MapsTextPrimary
import com.example.ui.theme.MapsTextSecondary

// Google Maps transit station departure row card (12dp radius, 48dp touch target)
@Composable
fun StopItemRow(
    stop: TransitStop,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val modeIcon = when (stop.mode) {
        TransitMode.SUBWAY -> Icons.Outlined.DirectionsSubway
        TransitMode.BUS -> Icons.Outlined.DirectionsBus
        TransitMode.RAIL -> Icons.Outlined.DirectionsRailway
        TransitMode.ALL -> Icons.Outlined.DirectionsSubway
    }

    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(12.dp),
        color = if (isSelected) MapsSelectedTint else MapsSurface,
        border = BorderStroke(
            width = 1.dp,
            color = if (isSelected) MapsAccentBlue else MapsBorder
        ),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .testTag("stop_card_${stop.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(
                        color = if (isSelected) MapsSurface else MapsSelectedTint,
                        shape = RoundedCornerShape(8.dp)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = modeIcon,
                    contentDescription = stop.mode.label,
                    tint = MapsAccentBlue,
                    modifier = Modifier.size(24.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .background(MapsAccentBlue, RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = stop.lineCode,
                            style = MaterialTheme.typography.labelMedium,
                            color = MapsSurface
                        )
                    }
                    Text(
                        text = stop.name,
                        style = MaterialTheme.typography.titleMedium,
                        color = MapsTextPrimary
                    )
                }

                Text(
                    text = "${stop.lineName} • ${stop.platform} • ${CommuteFormatters.formatDistanceMeters(stop.distanceMeters)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MapsTextSecondary
                )
            }

            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = CommuteFormatters.formatEtaMinutes(stop.nextArrivalMin),
                    style = MaterialTheme.typography.titleMedium,
                    color = MapsAccentBlue
                )
                Text(
                    text = stop.frequencyText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MapsTextSecondary
                )
            }
        }
    }
}
