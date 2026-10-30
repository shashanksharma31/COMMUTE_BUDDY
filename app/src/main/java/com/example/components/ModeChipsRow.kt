package com.example.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.example.lib.TravelMode
import com.example.ui.theme.MapsAccentBlue
import com.example.ui.theme.MapsBorder
import com.example.ui.theme.MapsSelectedTint
import com.example.ui.theme.MapsSurface
import com.example.ui.theme.MapsTextPrimary
import com.example.ui.theme.MapsTextSecondary

private val REACH_BY_SLOTS = listOf(
    null to "Depart now (Anytime)",
    "08:30 AM" to "Reach by 08:30 AM",
    "09:00 AM" to "Reach by 09:00 AM",
    "09:30 AM" to "Reach by 09:30 AM",
    "10:00 AM" to "Reach by 10:00 AM",
    "12:30 PM" to "Reach by 12:30 PM",
    "06:00 PM" to "Reach by 06:00 PM",
    "08:00 PM" to "Reach by 08:00 PM"
)

// Horizontally scrollable mode chip row under the directions card:
// Displays only the verified available transport modes for the selected origin & destination + "Reach by" chip.
@Composable
fun ModeChipsRow(
    selectedMode: TravelMode,
    reachByTime: String?,
    onSelectMode: (TravelMode) -> Unit,
    onSelectReachByTime: (String?) -> Unit,
    availableModes: List<TravelMode> = TravelMode.entries,
    modifier: Modifier = Modifier
) {
    var showTimePicker by remember { mutableStateOf(false) }
    val scrollState = rememberScrollState()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .testTag("mode_chips_row"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        availableModes.forEach { mode ->
            val isSelected = mode == selectedMode
            val tintColor = if (isSelected) MapsAccentBlue else MapsTextPrimary

            Surface(
                onClick = { onSelectMode(mode) },
                shape = CircleShape,
                color = if (isSelected) MapsSelectedTint else MapsSurface,
                border = BorderStroke(
                    width = if (isSelected) 1.5.dp else 1.dp,
                    color = if (isSelected) MapsAccentBlue else MapsBorder
                ),
                shadowElevation = 2.dp,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("mode_chip_${mode.name.lowercase()}")
            ) {
                Row(
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TravelModeIcon(
                        mode = mode,
                        tint = tintColor,
                        iconSize = 24.dp
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = mode.label,
                        style = MaterialTheme.typography.labelLarge,
                        color = tintColor
                    )
                }
            }
        }

        // Chip-sized "Reach by" time picker at the end of the row
        val hasReachBy = reachByTime != null
        val reachChipColor = if (hasReachBy) MapsAccentBlue else MapsTextPrimary
        Surface(
            onClick = { showTimePicker = true },
            shape = CircleShape,
            color = if (hasReachBy) MapsSelectedTint else MapsSurface,
            border = BorderStroke(
                width = if (hasReachBy) 1.5.dp else 1.dp,
                color = if (hasReachBy) MapsAccentBlue else MapsBorder
            ),
            shadowElevation = 2.dp,
            modifier = Modifier
                .heightIn(min = 48.dp)
                .testTag("reach_by_chip")
        ) {
            Row(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                ClockIcon(
                    tint = reachChipColor,
                    iconSize = 24.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (reachByTime != null) "Reach by $reachByTime" else "Reach by",
                    style = MaterialTheme.typography.labelLarge,
                    color = reachChipColor
                )
            }
        }
    }

    if (showTimePicker) {
        Dialog(onDismissRequest = { showTimePicker = false }) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MapsSurface,
                border = BorderStroke(1.dp, MapsBorder),
                modifier = Modifier
                    .fillMaxWidth()
                    .shadow(elevation = 2.dp, shape = RoundedCornerShape(12.dp), clip = false)
                    .testTag("reach_by_dialog")
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp)
                ) {
                    Text(
                        text = "Select arrival time",
                        style = MaterialTheme.typography.titleMedium,
                        color = MapsTextPrimary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                    )
                    HorizontalDivider(color = MapsBorder, thickness = 1.dp)
                    REACH_BY_SLOTS.forEachIndexed { idx, (slotValue, slotLabel) ->
                        val isSelectedSlot = reachByTime == slotValue
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(min = 48.dp)
                                .clickable {
                                    onSelectReachByTime(slotValue)
                                    showTimePicker = false
                                }
                                .padding(horizontal = 16.dp),
                            contentAlignment = Alignment.CenterStart
                        ) {
                            Text(
                                text = slotLabel,
                                style = MaterialTheme.typography.bodyMedium,
                                color = if (isSelectedSlot) MapsAccentBlue else MapsTextPrimary
                            )
                        }
                        if (idx < REACH_BY_SLOTS.lastIndex) {
                            HorizontalDivider(
                                color = MapsBorder,
                                thickness = 1.dp,
                                modifier = Modifier.padding(horizontal = 16.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
