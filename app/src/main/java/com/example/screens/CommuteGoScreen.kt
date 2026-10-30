package com.example.screens

import androidx.activity.compose.BackHandler
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
import com.example.components.RouteCard
import com.example.data.CommuteRoute
import com.example.data.DataLoadState
import com.example.ui.theme.MapsTextPrimary
import com.example.ui.theme.MapsTextSecondary

// Go / Commute route planner screen inside the bottom sheet
@Composable
fun CommuteGoScreen(
    routes: List<CommuteRoute>,
    selectedRouteId: String?,
    activeTripRouteId: String?,
    loadState: DataLoadState,
    onSelectRoute: (CommuteRoute) -> Unit,
    onStartTrip: (CommuteRoute) -> Unit,
    onResetFilter: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val effectiveState = when {
        loadState != DataLoadState.READY -> loadState
        routes.isEmpty() -> DataLoadState.EMPTY
        else -> DataLoadState.READY
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("screen_commute_go"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.suggested_commutes),
                style = MaterialTheme.typography.titleMedium,
                color = MapsTextPrimary
            )
            Text(
                text = "Depart now",
                style = MaterialTheme.typography.bodySmall,
                color = MapsTextSecondary
            )
        }

        if (effectiveState != DataLoadState.READY) {
            ListStateView(
                state = effectiveState,
                emptyMessage = stringResource(R.string.state_empty_routes),
                onRetry = onResetFilter
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("commute_routes_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(routes, key = { it.id }) { route ->
                    RouteCard(
                        route = route,
                        isSelected = route.id == selectedRouteId,
                        isActiveTrip = route.id == activeTripRouteId,
                        onSelect = { onSelectRoute(route) },
                        onStartTrip = { onStartTrip(route) }
                    )
                }
            }
        }
    }
}
