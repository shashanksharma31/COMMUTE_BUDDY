package com.example.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.components.ListStateView
import com.example.components.SavedPlaceCard
import com.example.data.DataLoadState
import com.example.data.SavedPlace
import com.example.ui.theme.MapsAccentBlue
import com.example.ui.theme.MapsBorder
import com.example.ui.theme.MapsSelectedTint
import com.example.ui.theme.MapsSurface
import com.example.ui.theme.MapsTextPrimary

// Saved places screen with category chips and Loading/Empty/Error list states
@Composable
fun SavedPlacesScreen(
    places: List<SavedPlace>,
    selectedCategory: String,
    selectedPlaceId: String?,
    loadState: DataLoadState,
    onSelectCategory: (String) -> Unit,
    onSelectPlace: (SavedPlace) -> Unit,
    onResetFilter: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    BackHandler(onBack = onBack)

    val categories = listOf("All", "Favorites", "Stations", "Recent")
    val effectiveState = when {
        loadState != DataLoadState.READY -> loadState
        places.isEmpty() -> DataLoadState.EMPTY
        else -> DataLoadState.READY
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .testTag("screen_saved_places"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            categories.forEach { category ->
                val isSelected = category == selectedCategory
                OutlinedButton(
                    onClick = { onSelectCategory(category) },
                    shape = CircleShape,
                    border = BorderStroke(1.dp, if (isSelected) MapsAccentBlue else MapsBorder),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (isSelected) MapsSelectedTint else MapsSurface,
                        contentColor = if (isSelected) MapsAccentBlue else MapsTextPrimary
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                    modifier = Modifier
                        .heightIn(min = 48.dp)
                        .testTag("saved_cat_${category.lowercase()}")
                ) {
                    Text(
                        text = category,
                        style = MaterialTheme.typography.labelLarge
                    )
                }
            }
        }

        if (effectiveState != DataLoadState.READY) {
            ListStateView(
                state = effectiveState,
                emptyMessage = stringResource(R.string.state_empty_saved),
                onRetry = onResetFilter
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .testTag("saved_places_list"),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                items(places, key = { it.id }) { place ->
                    SavedPlaceCard(
                        place = place,
                        isSelected = place.id == selectedPlaceId,
                        onClick = { onSelectPlace(place) }
                    )
                }
            }
        }
    }
}
