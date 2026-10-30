package com.example.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.components.BookmarkIcon
import com.example.components.ListStateView
import com.example.components.LucideArrowLeftIcon
import com.example.components.SavedRouteItem
import com.example.data.DataLoadState
import com.example.lib.LocalTripContext
import com.example.lib.SavedRouteEntry
import com.example.ui.theme.MapsAccentBlue
import com.example.ui.theme.MapsBorder
import com.example.ui.theme.MapsSelectedTint
import com.example.ui.theme.MapsSurface
import com.example.ui.theme.MapsTextPrimary
import com.example.ui.theme.MapsTextSecondary
import kotlinx.coroutines.delay

// Full-screen white Saved screen with top app bar (back arrow + "Saved" title),
// saved routes list, "Removed" + "Undo" snackbar, and "No saved routes yet" empty state.
@Composable
fun SavedScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val tripContext = LocalTripContext.current
    val savedRoutes = tripContext.savedRoutes
    var loadState by remember { mutableStateOf(DataLoadState.READY) }

    var recentlyRemoved by remember { mutableStateOf<Pair<Int, SavedRouteEntry>?>(null) }

    LaunchedEffect(recentlyRemoved) {
        if (recentlyRemoved != null) {
            delay(4000L)
            recentlyRemoved = null
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MapsSurface)
            .testTag("saved_screen")
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.statusBars)
                .windowInsetsPadding(WindowInsets.navigationBars)
        ) {
            // Top App Bar: back arrow + title "Saved" (56dp height on 8px grid)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                IconButton(
                    onClick = onBack,
                    modifier = Modifier
                        .size(48.dp)
                        .testTag("saved_back_button")
                ) {
                    LucideArrowLeftIcon(
                        tint = MapsTextPrimary,
                        iconSize = 24.dp,
                        contentDesc = "Back to map"
                    )
                }

                Text(
                    text = "Saved",
                    style = MaterialTheme.typography.titleLarge,
                    color = MapsTextPrimary,
                    modifier = Modifier.testTag("saved_screen_title")
                )
            }

            HorizontalDivider(color = MapsBorder, thickness = 1.dp)

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                when {
                    loadState == DataLoadState.LOADING -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                color = MapsAccentBlue,
                                strokeWidth = 2.dp,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                    }

                    loadState == DataLoadState.ERROR -> {
                        ListStateView(
                            state = DataLoadState.ERROR,
                            emptyMessage = "Unable to load saved routes",
                            onRetry = { loadState = DataLoadState.READY }
                        )
                    }

                    savedRoutes.isEmpty() -> {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp)
                                .testTag("saved_empty_state"),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            BookmarkIcon(
                                isSaved = false,
                                tint = MapsTextSecondary,
                                iconSize = 24.dp,
                                contentDesc = "No saved routes yet"
                            )
                            Spacer(modifier = Modifier.height(12.dp))
                            Text(
                                text = "No saved routes yet",
                                style = MaterialTheme.typography.titleMedium,
                                color = MapsTextPrimary,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "Bookmark a route to check it anytime.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MapsTextSecondary,
                                textAlign = TextAlign.Center
                            )
                        }
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier
                                .fillMaxSize()
                                .testTag("saved_routes_list"),
                            contentPadding = PaddingValues(top = 16.dp, bottom = 80.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(savedRoutes, key = { it.id }) { entry ->
                                SavedRouteItem(
                                    entry = entry,
                                    onCheckNow = {
                                        tripContext.checkSavedRouteNow(entry)
                                    },
                                    onDelete = {
                                        val index = savedRoutes.indexOfFirst { it.id == entry.id }.coerceAtLeast(0)
                                        recentlyRemoved = index to entry
                                        tripContext.removeSavedRoute(entry.id)
                                    }
                                )
                            }
                        }
                    }
                }
            }
        }

        // Bottom Snackbar: "Removed" and "Undo"
        val removedPair = recentlyRemoved
        if (removedPair != null) {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MapsTextPrimary,
                border = BorderStroke(1.dp, MapsBorder),
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .windowInsetsPadding(WindowInsets.navigationBars)
                    .padding(16.dp)
                    .fillMaxWidth()
                    .shadow(elevation = 2.dp, shape = RoundedCornerShape(12.dp), clip = false)
                    .testTag("removed_snackbar")
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Removed",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MapsSurface
                    )

                    Box(
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .clickable(
                                interactionSource = remember { MutableInteractionSource() },
                                indication = null
                            ) {
                                tripContext.restoreSavedRoute(removedPair.second, removedPair.first)
                                recentlyRemoved = null
                            }
                            .padding(horizontal = 8.dp)
                            .testTag("snackbar_undo_button"),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "Undo",
                            style = MaterialTheme.typography.labelLarge,
                            color = MapsSelectedTint
                        )
                    }
                }
            }
        }
    }
}
