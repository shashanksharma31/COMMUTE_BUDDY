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
import com.example.components.ServiceAlertCard
import com.example.data.DataLoadState
import com.example.data.TransitAlert
import com.example.ui.theme.MapsTextPrimary
import com.example.ui.theme.MapsTextSecondary

// Transit service advisories & line status screen inside the bottom sheet
@Composable
fun UpdatesScreen(
    alerts: List<TransitAlert>,
    selectedAlertId: String?,
    loadState: DataLoadState,
    onSelectAlert: (TransitAlert) -> Unit,
    onResetFilter: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val effectiveState = when {
        loadState != DataLoadState.READY -> loadState
        alerts.isEmpty() -> DataLoadState.EMPTY
        else -> DataLoadState.READY
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("screen_updates"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = stringResource(R.string.transit_service_alerts),
                style = MaterialTheme.typography.titleMedium,
                color = MapsTextPrimary
            )
            Text(
                text = "${alerts.size} lines",
                style = MaterialTheme.typography.bodySmall,
                color = MapsTextSecondary
            )
        }

        if (effectiveState != DataLoadState.READY) {
            ListStateView(
                state = effectiveState,
                emptyMessage = stringResource(R.string.state_empty_alerts),
                onRetry = onResetFilter
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("transit_alerts_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(alerts, key = { it.id }) { alert ->
                    ServiceAlertCard(
                        alert = alert,
                        isSelected = alert.id == selectedAlertId,
                        onClick = { onSelectAlert(alert) }
                    )
                }
            }
        }
    }
}
