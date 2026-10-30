package com.example.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.data.DataLoadState
import com.example.lib.CommuteFormatters
import com.example.lib.Route
import com.example.ui.theme.MapsAccentBlue
import com.example.ui.theme.MapsBorder
import com.example.ui.theme.MapsSelectedTint
import com.example.ui.theme.MapsTextPrimary
import com.example.ui.theme.MapsTextSecondary

// Step-by-step route directions list inside the bottom sheet (Lucide 24px stroke-2 icons, 8px grid, 48px touch target)
@Composable
fun StepByStepView(
    route: Route,
    loadState: DataLoadState = DataLoadState.READY,
    onBack: () -> Unit,
    onRetry: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val effectiveState = when {
        loadState != DataLoadState.READY -> loadState
        route.steps.isEmpty() -> DataLoadState.EMPTY
        else -> DataLoadState.READY
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("step_by_step_view")
    ) {
        // Header row with 48dp back arrow and route summary
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            IconButton(
                onClick = onBack,
                modifier = Modifier
                    .size(48.dp)
                    .testTag("step_by_step_back_button")
            ) {
                LucideArrowLeftIcon(
                    tint = MapsTextPrimary,
                    iconSize = 24.dp,
                    contentDesc = "Back to routes"
                )
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${CommuteFormatters.formatDuration(route.durationSeconds)} • ${route.costDisplay}",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = MapsTextPrimary
                )
                Text(
                    text = "${route.label.display} • Reliable ${route.reliability}/10",
                    style = MaterialTheme.typography.bodySmall,
                    color = MapsTextSecondary
                )
            }
        }

        HorizontalDivider(color = MapsBorder, thickness = 1.dp)
        Spacer(modifier = Modifier.height(12.dp))

        if (effectiveState != DataLoadState.READY) {
            ListStateView(
                state = effectiveState,
                emptyMessage = "No steps available for this route",
                onRetry = onRetry
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("step_by_step_list"),
                contentPadding = PaddingValues(bottom = 24.dp)
            ) {
                itemsIndexed(route.steps) { index, step ->
                    val isLast = index == route.steps.lastIndex
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("route_step_row_$index"),
                        verticalAlignment = Alignment.Top
                    ) {
                        // Left timeline column: 40dp badge + vertical line connecting steps
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.width(40.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .background(MapsSelectedTint, CircleShape),
                                contentAlignment = Alignment.Center
                            ) {
                                TravelModeIcon(
                                    mode = step.mode,
                                    tint = MapsAccentBlue,
                                    iconSize = 24.dp
                                )
                            }

                            if (!isLast) {
                                Box(
                                    modifier = Modifier
                                        .width(2.dp)
                                        .height(32.dp)
                                        .background(MapsAccentBlue)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(12.dp))

                        // Middle: step instruction text (strict 8px grid padding)
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .padding(top = 8.dp, bottom = if (isLast) 8.dp else 16.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Text(
                                text = step.text,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MapsTextPrimary
                            )
                            Text(
                                text = step.mode.label,
                                style = MaterialTheme.typography.bodySmall,
                                color = MapsTextSecondary
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        // Right: step duration formatted from seconds
                        Text(
                            text = CommuteFormatters.formatDuration(step.durationSeconds),
                            style = MaterialTheme.typography.labelLarge,
                            color = MapsAccentBlue,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }
        }
    }
}
