package com.example.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.example.lib.SavedRouteEntry
import com.example.ui.theme.MapsAccentBlue
import com.example.ui.theme.MapsBorder
import com.example.ui.theme.MapsSelectedTint
import com.example.ui.theme.MapsSurface
import com.example.ui.theme.MapsTextPrimary
import com.example.ui.theme.MapsTextSecondary

// Saved route row card: "Start to End", vehicle label, "Check now" text button, and delete icon (24px stroke-2, 8px grid, >=48px touch targets)
@Composable
fun SavedRouteItem(
    entry: SavedRouteEntry,
    onCheckNow: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MapsSurface,
        border = BorderStroke(1.dp, MapsBorder),
        modifier = modifier
            .fillMaxWidth()
            .testTag("saved_route_item_${entry.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 12.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .background(MapsSelectedTint, RoundedCornerShape(8.dp)),
                contentAlignment = Alignment.Center
            ) {
                TravelModeIcon(
                    mode = entry.vehicle,
                    tint = MapsAccentBlue,
                    iconSize = 24.dp
                )
            }

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "${entry.start.name} to ${entry.end.name}",
                    style = MaterialTheme.typography.titleSmall,
                    color = MapsTextPrimary,
                    modifier = Modifier.testTag("saved_route_title_${entry.id}")
                )
                Text(
                    text = entry.vehicle.label,
                    style = MaterialTheme.typography.bodySmall,
                    color = MapsTextSecondary
                )
            }

            // "Check now" text button (48dp min touch target, no tap highlight)
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onCheckNow
                    )
                    .padding(horizontal = 8.dp)
                    .testTag("check_now_${entry.id}"),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "Check now",
                    style = MaterialTheme.typography.labelLarge,
                    color = MapsAccentBlue
                )
            }

            // Delete icon button (48dp touch target, 24dp stroke-2 icon)
            IconButton(
                onClick = onDelete,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("delete_saved_${entry.id}")
            ) {
                TrashDeleteIcon(
                    tint = MapsTextSecondary,
                    iconSize = 24.dp,
                    contentDesc = "Delete saved route"
                )
            }
        }
    }
}
