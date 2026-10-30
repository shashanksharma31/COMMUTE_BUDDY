package com.example.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.components.ListStateView
import com.example.components.StopItemRow
import com.example.data.DataLoadState
import com.example.data.TransitStop
import com.example.ui.theme.MapsTextPrimary
import com.example.ui.theme.MapsTextSecondary

// Explore tab content inside the 16dp top-rounded Google Maps bottom sheet
@Composable
fun ExploreScreen(
    stops: List<TransitStop>,
    selectedStopId: String?,
    loadState: DataLoadState,
    onSelectStop: (TransitStop) -> Unit,
    onResetFilter: () -> Unit,
    modifier: Modifier = Modifier
) {
    val effectiveState = when {
        loadState != DataLoadState.READY -> loadState
        stops.isEmpty() -> DataLoadState.EMPTY
        else -> DataLoadState.READY
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("screen_explore"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.nearby_departures),
                style = MaterialTheme.typography.titleMedium,
                color = MapsTextPrimary
            )
            Text(
                text = "${stops.size} stations",
                style = MaterialTheme.typography.bodySmall,
                color = MapsTextSecondary
            )
        }

        if (effectiveState != DataLoadState.READY) {
            ListStateView(
                state = effectiveState,
                emptyMessage = stringResource(R.string.state_empty_stops),
                onRetry = onResetFilter
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("explore_stops_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(stops, key = { it.id }) { stop ->
                    StopItemRow(
                        stop = stop,
                        isSelected = stop.id == selectedStopId,
                        onClick = { onSelectStop(stop) }
                    )
                }
            }
        }
    }
}
