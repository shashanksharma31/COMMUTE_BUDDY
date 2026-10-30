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
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.WorkOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.data.SavedPlace
import com.example.lib.CommuteFormatters
import com.example.ui.theme.MapsAccentBlue
import com.example.ui.theme.MapsBorder
import com.example.ui.theme.MapsSelectedTint
import com.example.ui.theme.MapsSurface
import com.example.ui.theme.MapsTextPrimary
import com.example.ui.theme.MapsTextSecondary

// Google Maps saved place card (12dp radius, 48dp min touch target)
@Composable
fun SavedPlaceCard(
    place: SavedPlace,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val icon = when (place.label.lowercase()) {
        "home" -> Icons.Outlined.Home
        "work" -> Icons.Outlined.WorkOutline
        else -> Icons.Outlined.BookmarkBorder
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
            .testTag("saved_place_${place.id}")
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
                    imageVector = icon,
                    contentDescription = place.label,
                    tint = MapsAccentBlue,
                    modifier = Modifier.size(24.dp)
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = place.label,
                    style = MaterialTheme.typography.titleMedium,
                    color = MapsTextPrimary
                )
                Text(
                    text = "${place.address} • ${place.category}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MapsTextSecondary
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = CommuteFormatters.formatEtaMinutes(place.etaMinutes),
                    style = MaterialTheme.typography.titleMedium,
                    color = MapsAccentBlue
                )
                Text(
                    text = "Transit",
                    style = MaterialTheme.typography.bodySmall,
                    color = MapsTextSecondary
                )
            }
        }
    }
}
