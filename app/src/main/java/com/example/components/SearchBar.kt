package com.example.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.data.DataLoadState
import com.example.data.DelhiPlace
import com.example.data.DelhiPlacesData
import com.example.lib.LocalTripContext
import com.example.ui.theme.MapsAccentBlue
import com.example.ui.theme.MapsBackground
import com.example.ui.theme.MapsBorder
import com.example.ui.theme.MapsSelectedTint
import com.example.ui.theme.MapsSurface
import com.example.ui.theme.MapsTextPrimary
import com.example.ui.theme.MapsTextSecondary
import kotlinx.coroutines.delay

private val DestinationRed = Color(0xFFD93025)

private enum class FocusedField {
    NONE,
    START,
    END
}

// Floating Google Maps "Where to?" pill (margin 16px, height 48px) with Lucide icons, 8px grid, and >=48px touch targets.
@Composable
fun SearchBar(
    modifier: Modifier = Modifier
) {
    val tripContext = LocalTripContext.current
    val focusManager = LocalFocusManager.current

    var isExpanded by rememberSaveable { mutableStateOf(false) }
    var focusedField by remember { mutableStateOf(FocusedField.NONE) }
    var isDropdownOpen by remember { mutableStateOf(false) }
    var startQuery by rememberSaveable { mutableStateOf("") }
    var endQuery by rememberSaveable { mutableStateOf("") }
    var listState by remember { mutableStateOf(DataLoadState.READY) }
    var matchedPlaces by remember { mutableStateOf<List<DelhiPlace>>(emptyList()) }
    var retryCounter by remember { mutableStateOf(0) }

    LaunchedEffect(tripContext.startPlace, tripContext.endPlace) {
        tripContext.startPlace?.let { startQuery = it.name }
        tripContext.endPlace?.let { endQuery = it.name }
    }

    LaunchedEffect(tripContext.isChatExpanded) {
        if (tripContext.isChatExpanded) {
            isDropdownOpen = false
            focusedField = FocusedField.NONE
            focusManager.clearFocus()
            isExpanded = false
        }
    }

    fun commitPendingQueryIfNeeded() {
        val trimmedStart = startQuery.trim()
        if (trimmedStart.isNotEmpty() && (tripContext.startPlace == null || !tripContext.startPlace!!.name.equals(trimmedStart, ignoreCase = true))) {
            val candidate = if (focusedField == FocusedField.START && matchedPlaces.isNotEmpty()) {
                matchedPlaces.first()
            } else {
                DelhiPlacesData.searchPlaces(trimmedStart, maxResults = 1, includeFallbackSynthesis = true).firstOrNull()
            }
            if (candidate != null) {
                startQuery = candidate.name
                tripContext.updateStart(candidate)
            }
        }
        val trimmedEnd = endQuery.trim()
        if (trimmedEnd.isNotEmpty() && (tripContext.endPlace == null || !tripContext.endPlace!!.name.equals(trimmedEnd, ignoreCase = true))) {
            val candidate = if (focusedField == FocusedField.END && matchedPlaces.isNotEmpty()) {
                matchedPlaces.first()
            } else {
                DelhiPlacesData.searchPlaces(trimmedEnd, maxResults = 1, includeFallbackSynthesis = true).firstOrNull()
            }
            if (candidate != null) {
                endQuery = candidate.name
                tripContext.updateEnd(candidate)
            }
        }
    }

    val activeQuery = when (focusedField) {
        FocusedField.START -> startQuery
        FocusedField.END -> endQuery
        FocusedField.NONE -> ""
    }

    LaunchedEffect(activeQuery, focusedField, isDropdownOpen, retryCounter) {
        if (focusedField == FocusedField.NONE || !isDropdownOpen) {
            matchedPlaces = emptyList()
            listState = DataLoadState.READY
            return@LaunchedEffect
        }
        val trimmedQuery = activeQuery.trim()
        if (trimmedQuery.isEmpty()) {
            matchedPlaces = DelhiPlacesData.places.take(5)
            listState = DataLoadState.READY
            return@LaunchedEffect
        }

        // 1. Show only verified high-confidence local matches immediately (no fake fallback coordinates at 0ms)
        val verifiedInstantMatches = DelhiPlacesData.searchPlaces(
            query = trimmedQuery,
            maxResults = 5,
            includeFallbackSynthesis = false
        )
        if (verifiedInstantMatches.isNotEmpty()) {
            matchedPlaces = verifiedInstantMatches
            listState = DataLoadState.READY
        } else {
            listState = DataLoadState.LOADING
        }

        // 2. Debounce 120ms and query the 4-API geocoding pipeline with location bias from the other selected endpoint
        val biasPlace = if (focusedField == FocusedField.START) tripContext.endPlace else tripContext.startPlace
        delay(120L)
        try {
            val remotePlaces = DelhiPlacesData.fetchPlacesFromBackend(
                query = trimmedQuery,
                maxResults = 6,
                lat = biasPlace?.lat,
                lng = biasPlace?.lng
            )
            if (remotePlaces.isNotEmpty()) {
                matchedPlaces = remotePlaces
                listState = DataLoadState.READY
            } else if (verifiedInstantMatches.isEmpty()) {
                matchedPlaces = emptyList()
                listState = DataLoadState.EMPTY
            }
        } catch (_: Exception) {
            if (verifiedInstantMatches.isEmpty()) {
                val fallback = DelhiPlacesData.searchPlaces(trimmedQuery, maxResults = 5, includeFallbackSynthesis = true)
                matchedPlaces = fallback
                listState = if (fallback.isNotEmpty()) DataLoadState.READY else DataLoadState.ERROR
            }
        }
    }

    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 4.dp)
            .testTag("search_bar_wrapper"),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        AnimatedContent(
            targetState = isExpanded,
            transitionSpec = {
                fadeIn(tween(durationMillis = 180, easing = FastOutSlowInEasing)) togetherWith
                    fadeOut(tween(durationMillis = 180, easing = FastOutSlowInEasing))
            },
            label = "search_bar_transition"
        ) { expanded ->
            if (!expanded) {
                val pillLabel = when {
                    tripContext.startPlace != null && tripContext.endPlace != null ->
                        "${tripContext.startPlace?.name} → ${tripContext.endPlace?.name}"
                    tripContext.endPlace != null ->
                        "To: ${tripContext.endPlace?.name}"
                    tripContext.startPlace != null ->
                        "From: ${tripContext.startPlace?.name}"
                    else -> "Where to?"
                }
                val hasSelection = tripContext.startPlace != null || tripContext.endPlace != null

                Surface(
                    shape = CircleShape,
                    color = MapsSurface,
                    border = BorderStroke(1.dp, MapsBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .heightIn(min = 48.dp)
                        .shadow(elevation = 2.dp, shape = CircleShape, clip = false)
                        .testTag("where_to_pill")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 48.dp)
                            .padding(start = 16.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .heightIn(min = 48.dp)
                                .clickable(
                                    interactionSource = remember { MutableInteractionSource() },
                                    indication = null
                                ) {
                                    tripContext.setChatExpandedState(false)
                                    isExpanded = true
                                    isDropdownOpen = true
                                    startQuery = tripContext.startPlace?.name ?: startQuery
                                    endQuery = tripContext.endPlace?.name ?: endQuery
                                    focusedField = if (tripContext.startPlace == null) FocusedField.START else FocusedField.END
                                },
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            LucideSearchIcon(
                                tint = MapsAccentBlue,
                                iconSize = 24.dp,
                                contentDesc = "Search"
                            )
                            Text(
                                text = pillLabel,
                                style = MaterialTheme.typography.bodyLarge,
                                color = if (hasSelection) MapsTextPrimary else MapsTextSecondary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        // Bookmark icon in the top search pill (48dp touch target)
                        IconButton(
                            onClick = { tripContext.openSavedScreen() },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("open_saved_screen_button")
                        ) {
                            BookmarkIcon(
                                isSaved = tripContext.savedRoutes.isNotEmpty(),
                                tint = if (tripContext.savedRoutes.isNotEmpty()) MapsAccentBlue else MapsTextSecondary,
                                iconSize = 24.dp,
                                contentDesc = "Saved routes"
                            )
                        }
                    }
                }
            } else {
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = MapsSurface,
                    border = BorderStroke(1.dp, MapsBorder),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp)
                        .shadow(elevation = 2.dp, shape = RoundedCornerShape(12.dp), clip = false)
                        .testTag("directions_card")
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(
                            onClick = {
                                commitPendingQueryIfNeeded()
                                isDropdownOpen = false
                                focusedField = FocusedField.NONE
                                focusManager.clearFocus(force = true)
                                isExpanded = false
                                if (tripContext.hasBothPlaces) {
                                    tripContext.setRoutesSheetOpenState(true)
                                }
                            },
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("collapse_directions_button")
                        ) {
                            LucideArrowLeftIcon(
                                tint = MapsTextPrimary,
                                iconSize = 24.dp,
                                contentDesc = "Collapse directions"
                            )
                        }

                        // Route icons column: Blue circle, dotted vertical line, Red pin
                        Column(
                            modifier = Modifier
                                .width(24.dp)
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Canvas(modifier = Modifier.size(12.dp)) {
                                drawCircle(
                                    color = MapsAccentBlue,
                                    radius = size.minDimension / 2f
                                )
                                drawCircle(
                                    color = MapsSurface,
                                    radius = size.minDimension / 4f
                                )
                            }

                            Canvas(
                                modifier = Modifier
                                    .width(4.dp)
                                    .height(32.dp)
                                    .padding(vertical = 4.dp)
                            ) {
                                val dotRadius = 1.5.dp.toPx()
                                val step = 6.dp.toPx()
                                var y = dotRadius
                                while (y < size.height) {
                                    drawCircle(
                                        color = MapsTextSecondary,
                                        radius = dotRadius,
                                        center = Offset(size.width / 2f, y)
                                    )
                                    y += step
                                }
                            }

                            MapPinIcon(
                                tint = DestinationRed,
                                iconSize = 24.dp,
                                contentDesc = "Trip end pin"
                            )
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            DirectionInputRow(
                                value = startQuery,
                                placeholder = "Your location / Trip start",
                                isFocused = focusedField == FocusedField.START && isDropdownOpen,
                                onValueChange = { text ->
                                    if (text != startQuery) {
                                        startQuery = text
                                        focusedField = FocusedField.START
                                        isDropdownOpen = true
                                        tripContext.setChatExpandedState(false)
                                        tripContext.setRoutesSheetOpenState(false)
                                        if (text.isEmpty()) tripContext.updateStart(null)
                                    }
                                },
                                onFocus = {
                                    focusedField = FocusedField.START
                                    isDropdownOpen = true
                                    tripContext.setChatExpandedState(false)
                                    tripContext.setRoutesSheetOpenState(false)
                                },
                                onClear = {
                                    startQuery = ""
                                    tripContext.updateStart(null)
                                    focusedField = FocusedField.START
                                    isDropdownOpen = true
                                    tripContext.setRoutesSheetOpenState(false)
                                },
                                onSubmit = {
                                    commitPendingQueryIfNeeded()
                                    if (tripContext.endPlace == null) {
                                        focusedField = FocusedField.END
                                        isDropdownOpen = true
                                    } else {
                                        isDropdownOpen = false
                                        focusedField = FocusedField.NONE
                                        focusManager.clearFocus(force = true)
                                    }
                                },
                                testTag = "input_trip_start"
                            )

                            DirectionInputRow(
                                value = endQuery,
                                placeholder = "Trip end",
                                isFocused = focusedField == FocusedField.END && isDropdownOpen,
                                onValueChange = { text ->
                                    if (text != endQuery) {
                                        endQuery = text
                                        focusedField = FocusedField.END
                                        isDropdownOpen = true
                                        tripContext.setChatExpandedState(false)
                                        tripContext.setRoutesSheetOpenState(false)
                                        if (text.isEmpty()) tripContext.updateEnd(null)
                                    }
                                },
                                onFocus = {
                                    focusedField = FocusedField.END
                                    isDropdownOpen = true
                                    tripContext.setChatExpandedState(false)
                                    tripContext.setRoutesSheetOpenState(false)
                                },
                                onClear = {
                                    endQuery = ""
                                    tripContext.updateEnd(null)
                                    focusedField = FocusedField.END
                                    isDropdownOpen = true
                                    tripContext.setRoutesSheetOpenState(false)
                                },
                                onSubmit = {
                                    commitPendingQueryIfNeeded()
                                    if (tripContext.startPlace == null) {
                                        focusedField = FocusedField.START
                                        isDropdownOpen = true
                                    } else {
                                        isDropdownOpen = false
                                        focusedField = FocusedField.NONE
                                        focusManager.clearFocus(force = true)
                                    }
                                },
                                testTag = "input_trip_end"
                            )
                        }

                        Spacer(modifier = Modifier.width(4.dp))

                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            IconButton(
                                onClick = { tripContext.openSavedScreen() },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("directions_saved_button")
                            ) {
                                BookmarkIcon(
                                    isSaved = tripContext.savedRoutes.isNotEmpty(),
                                    tint = if (tripContext.savedRoutes.isNotEmpty()) MapsAccentBlue else MapsTextSecondary,
                                    iconSize = 24.dp,
                                    contentDesc = "Saved routes"
                                )
                            }

                            IconButton(
                                onClick = {
                                    commitPendingQueryIfNeeded()
                                    isDropdownOpen = false
                                    focusedField = FocusedField.NONE
                                    focusManager.clearFocus(force = true)
                                    tripContext.swapPlaces()
                                    val tempText = startQuery
                                    startQuery = endQuery
                                    endQuery = tempText
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .testTag("swap_locations_button")
                            ) {
                                LucideArrowUpDownIcon(
                                    tint = MapsTextSecondary,
                                    iconSize = 24.dp,
                                    contentDesc = "Swap start and end"
                                )
                            }
                        }
                    }
                }
            }
        }

        // Horizontally scrollable mode chip row under the directions card (filtered by verified city mode availability)
        if (tripContext.hasBothPlaces && (!tripContext.isRoutesSheetOpen || tripContext.sheetSnapPoint != SheetSnapPoint.FULL)) {
            ModeChipsRow(
                selectedMode = tripContext.selectedMode,
                reachByTime = tripContext.reachByTime,
                availableModes = tripContext.availableModes,
                onSelectMode = { mode ->
                    commitPendingQueryIfNeeded()
                    isDropdownOpen = false
                    focusedField = FocusedField.NONE
                    focusManager.clearFocus(force = true)
                    tripContext.selectTravelMode(mode)
                },
                onSelectReachByTime = { time ->
                    commitPendingQueryIfNeeded()
                    isDropdownOpen = false
                    focusedField = FocusedField.NONE
                    focusManager.clearFocus(force = true)
                    tripContext.updateReachByTime(time)
                }
            )
        }

        // Autocomplete dropdown under the focused input (max 5 results)
        if (isExpanded && isDropdownOpen && focusedField != FocusedField.NONE) {
            Box(modifier = Modifier.padding(horizontal = 16.dp)) {
                AutocompleteDropdown(
                    results = matchedPlaces,
                    query = activeQuery,
                    loadState = listState,
                    onSelectPlace = { place ->
                        if (focusedField == FocusedField.START) {
                            startQuery = place.name
                            tripContext.updateStart(place)
                            if (tripContext.endPlace == null) {
                                focusedField = FocusedField.END
                                isDropdownOpen = true
                            } else {
                                isDropdownOpen = false
                                focusedField = FocusedField.NONE
                                focusManager.clearFocus(force = true)
                            }
                        } else if (focusedField == FocusedField.END) {
                            endQuery = place.name
                            tripContext.updateEnd(place)
                            if (tripContext.startPlace == null) {
                                focusedField = FocusedField.START
                                isDropdownOpen = true
                            } else {
                                isDropdownOpen = false
                                focusedField = FocusedField.NONE
                                focusManager.clearFocus(force = true)
                            }
                        }
                    },
                    onRetry = {
                        retryCounter += 1
                    }
                )
            }
        }
    }
}

@Composable
private fun DirectionInputRow(
    value: String,
    placeholder: String,
    isFocused: Boolean,
    onValueChange: (String) -> Unit,
    onFocus: () -> Unit,
    onClear: () -> Unit,
    onSubmit: () -> Unit,
    testTag: String
) {
    var hadHardwareFocus by remember { mutableStateOf(false) }
    val focusRequester = remember { FocusRequester() }
    val showEditableField = hadHardwareFocus || value.isEmpty()

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .background(
                color = if (isFocused) MapsSelectedTint else MapsBackground,
                shape = RoundedCornerShape(8.dp)
            )
            .border(
                width = 1.dp,
                color = if (isFocused) MapsAccentBlue else MapsBorder,
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                onFocus()
                runCatching { focusRequester.requestFocus() }
            }
            .padding(start = 12.dp, end = if (value.isNotEmpty()) 4.dp else 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .clipToBounds()
                .padding(end = 4.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            if (value.isEmpty()) {
                Text(
                    text = placeholder,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MapsTextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            } else if (!showEditableField) {
                Text(
                    text = value,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MapsTextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = MapsTextPrimary),
                cursorBrush = SolidColor(MapsAccentBlue),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { onSubmit() },
                    onDone = { onSubmit() }
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .alpha(if (showEditableField) 1f else 0f)
                    .onFocusChanged { state ->
                        if (state.isFocused && !hadHardwareFocus) {
                            onFocus()
                        }
                        hadHardwareFocus = state.isFocused
                    }
                    .testTag(testTag)
            )
        }

        if (value.isNotEmpty()) {
            // 48dp minimum touch target on clear button
            IconButton(
                onClick = onClear,
                modifier = Modifier.size(48.dp)
            ) {
                LucideXIcon(
                    tint = MapsTextSecondary,
                    iconSize = 20.dp,
                    contentDesc = "Clear input"
                )
            }
        }
    }
}

