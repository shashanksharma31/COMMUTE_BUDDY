package com.example.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Clear
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.DataLoadState
import com.example.ui.theme.MapsAccentBlue
import com.example.ui.theme.MapsBorder
import com.example.ui.theme.MapsSelectedTint
import com.example.ui.theme.MapsSurface
import com.example.ui.theme.MapsTextPrimary
import com.example.ui.theme.MapsTextSecondary

// Google Maps fully-rounded search pill with 48dp min touch target and soft elevation
@Composable
fun SearchPillBar(
    query: String,
    onQueryChange: (String) -> Unit,
    dataState: DataLoadState,
    onCycleDataState: () -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .shadow(elevation = 2.dp, shape = CircleShape, clip = false)
            .border(BorderStroke(1.dp, MapsBorder), CircleShape)
            .testTag("search_pill_bar"),
        shape = CircleShape,
        color = MapsSurface
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(
                modifier = Modifier.size(48.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Outlined.Search,
                    contentDescription = stringResource(R.string.search_placeholder),
                    tint = MapsTextSecondary,
                    modifier = Modifier.size(24.dp)
                )
            }

            Box(
                modifier = Modifier.weight(1f),
                contentAlignment = Alignment.CenterStart
            ) {
                if (query.isEmpty()) {
                    Text(
                        text = stringResource(R.string.search_placeholder),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MapsTextSecondary
                    )
                }
                BasicTextField(
                    value = query,
                    onValueChange = onQueryChange,
                    singleLine = true,
                    textStyle = MaterialTheme.typography.bodyLarge.copy(color = MapsTextPrimary),
                    cursorBrush = SolidColor(MapsAccentBlue),
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("search_input")
                )
            }

            if (query.isNotEmpty()) {
                IconButton(
                    onClick = { onQueryChange("") },
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("clear_search_button")
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Clear,
                        contentDescription = stringResource(R.string.clear_search),
                        tint = MapsTextSecondary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // State simulator pill (Live / Loading / Empty / Error) for testing list states
            val stateLabel = when (dataState) {
                DataLoadState.READY -> "Live"
                DataLoadState.LOADING -> "Load"
                DataLoadState.EMPTY -> "Empty"
                DataLoadState.ERROR -> "Err"
            }
            Box(
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .clickable(onClick = onCycleDataState)
                    .padding(horizontal = 8.dp)
                    .testTag("cycle_state_button"),
                contentAlignment = Alignment.Center
            ) {
                Row(
                    modifier = Modifier
                        .background(
                            color = if (dataState == DataLoadState.READY) MapsSelectedTint else MapsSurface,
                            shape = CircleShape
                        )
                        .border(BorderStroke(1.dp, MapsBorder), CircleShape)
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Tune,
                        contentDescription = "Switch state",
                        tint = MapsAccentBlue,
                        modifier = Modifier.size(16.dp)
                    )
                    Text(
                        text = stateLabel,
                        style = MaterialTheme.typography.labelMedium,
                        color = MapsAccentBlue
                    )
                }
            }
        }
    }
}
