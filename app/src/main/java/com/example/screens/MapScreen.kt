package com.example.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableDoubleStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.example.components.BottomSheet
import com.example.components.ChatBar
import com.example.components.DELHI_LAT
import com.example.components.DELHI_LNG
import com.example.components.DELHI_ZOOM
import com.example.components.LeafletMapView
import com.example.components.LucideInfoIcon
import com.example.components.LucideXIcon
import com.example.components.MyLocationButton
import com.example.components.RouteCard
import com.example.components.RouteCardSkeleton
import com.example.components.SearchBar
import com.example.components.StepByStepView
import com.example.data.DataLoadState
import com.example.data.DelhiPlacesData
import com.example.lib.CommuteFormatters
import com.example.lib.LocalTripContext
import com.example.lib.Route
import com.example.lib.TripContextProvider
import kotlinx.coroutines.launch
import com.example.ui.theme.MapsAccentBlue
import com.example.ui.theme.MapsBackground
import com.example.ui.theme.MapsBorder
import com.example.ui.theme.MapsSelectedTint
import com.example.ui.theme.MapsSurface
import com.example.ui.theme.MapsTextPrimary
import com.example.ui.theme.MapsTextSecondary

@SuppressLint("MissingPermission")
private fun queryDeviceLocation(context: Context): Location? {
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
    val providers = listOf(
        LocationManager.GPS_PROVIDER,
        LocationManager.NETWORK_PROVIDER,
        LocationManager.PASSIVE_PROVIDER
    )
    var bestLocation: Location? = null
    for (provider in providers) {
        val loc = runCatching { locationManager.getLastKnownLocation(provider) }.getOrNull() ?: continue
        if (bestLocation == null || loc.accuracy < bestLocation.accuracy) {
            bestLocation = loc
        }
    }
    return bestLocation
}

// MapScreen with solid white status bar area, floating SearchBar, LeafletMapView, round 48px MyLocationButton,
// draggable 3-snap-point BottomSheet sitting ABOVE the fixed bottom ChatBar, and full-screen SavedScreen.
@Composable
fun MapScreen(
    modifier: Modifier = Modifier
) {
    TripContextProvider {
        MapScreenContent(modifier = modifier)
    }
}

@Composable
private fun MapScreenContent(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val tripContext = LocalTripContext.current
    val coroutineScope = rememberCoroutineScope()
    var recenterTrigger by rememberSaveable { mutableIntStateOf(0) }
    var mapCenterLat by rememberSaveable { mutableDoubleStateOf(DELHI_LAT) }
    var mapCenterLng by rememberSaveable { mutableDoubleStateOf(DELHI_LNG) }
    var geolocationError by remember { mutableStateOf<String?>(null) }
    val isChatExpanded = tripContext.isChatExpanded

    fun resolveAndApplyLiveLocation(lat: Double, lng: Double) {
        geolocationError = null
        mapCenterLat = lat
        mapCenterLng = lng
        recenterTrigger += 1
        coroutineScope.launch {
            runCatching {
                DelhiPlacesData.reverseGeocodeFromBackend(lat, lng)
            }.onSuccess { place ->
                tripContext.updateStart(place)
            }.onFailure {
                geolocationError = "Unable to reverse-geocode live location. Check your network connection."
            }
        }
    }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        if (granted) {
            val deviceLoc = queryDeviceLocation(context)
            val lat = deviceLoc?.latitude?.takeIf { it in 6.5..37.2 } ?: DELHI_LAT
            val lng = deviceLoc?.longitude?.takeIf { it in 68.0..97.5 } ?: DELHI_LNG
            resolveAndApplyLiveLocation(lat, lng)
        } else {
            geolocationError = "Location permission denied. Enable GPS permission or enter your start location manually."
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(MapsBackground)
            .testTag("map_screen")
    ) {
        // Vertical layout: solid white status bar at top, map + route sheet in middle, fixed 48px ChatBar at bottom
        Column(modifier = Modifier.fillMaxSize()) {
            // Solid white (#ffffff) status bar background so map never bleeds behind status bar icons
            Spacer(
                modifier = Modifier
                    .fillMaxWidth()
                    .windowInsetsTopHeight(WindowInsets.statusBars)
                    .background(MapsSurface)
            )

            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                LeafletMapView(
                    centerLat = mapCenterLat,
                    centerLng = mapCenterLng,
                    zoom = DELHI_ZOOM,
                    startPlace = tripContext.startPlace,
                    endPlace = tripContext.endPlace,
                    selectedRoute = tripContext.selectedRoute,
                    allRoutes = tripContext.orderedRoutes,
                    isRoutesSheetOpen = tripContext.isRoutesSheetOpen,
                    isLiveRideActive = tripContext.isLiveRideActive,
                    onToggleLiveRide = { tripContext.toggleLiveRide() },
                    recenterTrigger = recenterTrigger,
                    modifier = Modifier.fillMaxSize()
                )

                // Floating SearchBar at the top (16px margin, 48px pill or animated directions card + mode chips)
                Column(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                ) {
                    SearchBar(modifier = Modifier.fillMaxWidth())

                    if (geolocationError != null) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MapsSurface,
                            border = BorderStroke(1.dp, MapsBorder),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 4.dp)
                                .testTag("geolocation_error_banner")
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(start = 12.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                LucideInfoIcon(
                                    tint = MapsAccentBlue,
                                    iconSize = 20.dp,
                                    contentDesc = "Location notice"
                                )
                                Text(
                                    text = geolocationError.orEmpty(),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MapsTextPrimary,
                                    modifier = Modifier.weight(1f)
                                )
                                IconButton(
                                    onClick = { geolocationError = null },
                                    modifier = Modifier.size(48.dp)
                                ) {
                                    LucideXIcon(
                                        tint = MapsTextSecondary,
                                        iconSize = 18.dp,
                                        contentDesc = "Dismiss location notice"
                                    )
                                }
                            }
                        }
                    }
                }

                // Round "my location" button (white, 48px, soft shadow) on the right side above the bottom area
                MyLocationButton(
                    onClick = {
                        val hasFine = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_FINE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED
                        val hasCoarse = ContextCompat.checkSelfPermission(
                            context,
                            Manifest.permission.ACCESS_COARSE_LOCATION
                        ) == PackageManager.PERMISSION_GRANTED

                        if (hasFine || hasCoarse) {
                            val deviceLoc = queryDeviceLocation(context)
                            val lat = deviceLoc?.latitude?.takeIf { it in 6.5..37.2 } ?: DELHI_LAT
                            val lng = deviceLoc?.longitude?.takeIf { it in 68.0..97.5 } ?: DELHI_LNG
                            resolveAndApplyLiveLocation(lat, lng)
                        } else {
                            // Immediately recenter and reverse geocode fallback while requesting live GPS permission
                            resolveAndApplyLiveLocation(DELHI_LAT, DELHI_LNG)
                            runCatching {
                                locationPermissionLauncher.launch(
                                    arrayOf(
                                        Manifest.permission.ACCESS_FINE_LOCATION,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                    )
                                )
                            }
                        }
                    },
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(
                            end = 16.dp,
                            bottom = if (tripContext.isRoutesSheetOpen) 112.dp else 24.dp
                        )
                )

                // Draggable 3-snap-point BottomSheet sitting directly above the ChatBar
                if (tripContext.isRoutesSheetOpen && !isChatExpanded) {
                    BottomSheet(
                        snapPoint = tripContext.sheetSnapPoint,
                        onSnapPointChange = { tripContext.updateSheetSnapPoint(it) },
                        modifier = Modifier.align(Alignment.BottomCenter)
                    ) {
                        val activeStepRoute = tripContext.selectedRoute
                        if (tripContext.isStepByStepOpen && activeStepRoute != null) {
                            StepByStepView(
                                route = activeStepRoute,
                                loadState = tripContext.routesLoadState,
                                onBack = { tripContext.closeStepByStep() },
                                onRetry = { tripContext.triggerRouteSearch() }
                            )
                        } else {
                            RoutesSheetContent()
                        }
                    }
                }
            }

            // Collapsed 48px ChatBar sits below the map & route sheet container
            if (!isChatExpanded) {
                ChatBar(
                    isExpanded = false,
                    onExpandedChange = { tripContext.setChatExpandedState(it) }
                )
            }
        }

        // Expanded ChatBar panel anchored at the bottom
        if (isChatExpanded) {
            ChatBar(
                isExpanded = true,
                onExpandedChange = { tripContext.setChatExpandedState(it) },
                modifier = Modifier.align(Alignment.BottomCenter)
            )
        }

        // Full-screen white Saved screen when opened from the top search pill bookmark icon
        if (tripContext.isSavedScreenOpen) {
            SavedScreen(
                onBack = { tripContext.closeSavedScreen() },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

@Composable
private fun RoutesSheetContent() {
    val tripContext = LocalTripContext.current
    val loadState = tripContext.routesLoadState
    val orderedRoutes = tripContext.orderedRoutes
    val recommendedId = tripContext.recommendedRoute?.id

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "${tripContext.selectedMode.label} routes",
                    style = MaterialTheme.typography.titleMedium,
                    color = MapsTextPrimary
                )
                val trafficHeader = orderedRoutes.firstOrNull()?.trafficAnalysis
                val subtitle = when {
                    tripContext.reachByTime != null && !trafficHeader.isNullOrBlank() ->
                        "Reach by ${tripContext.reachByTime} • $trafficHeader"
                    tripContext.reachByTime != null ->
                        "Reach by ${tripContext.reachByTime}"
                    !trafficHeader.isNullOrBlank() ->
                        trafficHeader
                    else ->
                        "Departing now"
                }
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MapsTextSecondary
                )
                val availabilityBanner = tripContext.modeAvailability?.availabilitySummaryBanner
                if (!availabilityBanner.isNullOrBlank()) {
                    Text(
                        text = availabilityBanner,
                        style = MaterialTheme.typography.bodySmall,
                        color = MapsAccentBlue
                    )
                }
            }

            IconButton(
                onClick = { tripContext.setRoutesSheetOpenState(false) },
                modifier = Modifier
                    .size(48.dp)
                    .testTag("close_routes_sheet_button")
            ) {
                LucideXIcon(
                    tint = MapsTextSecondary,
                    iconSize = 24.dp,
                    contentDesc = "Close routes sheet"
                )
            }
        }

        when (loadState) {
            DataLoadState.LOADING -> {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxSize()
                        .testTag("routes_skeleton_list"),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 24.dp)
                ) {
                    items(3) {
                        RouteCardSkeleton()
                    }
                }
            }

            DataLoadState.EMPTY -> {
                RoutesEmptyOrErrorBox(
                    isError = false,
                    message = tripContext.emptyRoutesMessage,
                    onRetry = { tripContext.triggerRouteSearch() }
                )
            }

            DataLoadState.ERROR -> {
                RoutesEmptyOrErrorBox(
                    isError = true,
                    message = "Unable to load routes",
                    onRetry = { tripContext.triggerRouteSearch() }
                )
            }

            DataLoadState.READY -> {
                if (orderedRoutes.isEmpty()) {
                    RoutesEmptyOrErrorBox(
                        isError = false,
                        message = tripContext.emptyRoutesMessage,
                        onRetry = { tripContext.triggerRouteSearch() }
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxSize()
                            .testTag("routes_results_list"),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(bottom = 24.dp)
                    ) {
                        items(orderedRoutes, key = { it.id }) { route ->
                            RouteCard(
                                route = route,
                                allRoutes = orderedRoutes,
                                reachByTime = tripContext.reachByTime,
                                isRecommended = route.id == recommendedId,
                                isSelected = route.id == tripContext.selectedRoute?.id,
                                isBookmarked = tripContext.isCurrentTripBookmarked,
                                isLiveRideActive = tripContext.isLiveRideActive,
                                onClick = { tripContext.selectRoute(route) },
                                onStartClick = { tripContext.openStepByStep(route) },
                                onLiveViewClick = {
                                    tripContext.selectRoute(route)
                                    tripContext.toggleLiveRide()
                                },
                                onBookmarkClick = { tripContext.toggleBookmarkCurrentTrip() }
                            )
                        }

                        // End-of-list summary card showing Fastest, Cheapest, and Recommended (time & money) comparison
                        item(key = "fastest_cheapest_summary") {
                            FastestAndCheapestSummaryCard(
                                allRoutes = orderedRoutes,
                                recommendedRoute = tripContext.recommendedRoute
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FastestAndCheapestSummaryCard(
    allRoutes: List<Route>,
    recommendedRoute: Route?
) {
    if (allRoutes.isEmpty()) return
    val fastest = allRoutes.minByOrNull { it.durationSeconds } ?: allRoutes.first()
    val cheapest = allRoutes.minByOrNull { it.costInr } ?: allRoutes.first()
    val recommended = recommendedRoute ?: fastest

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MapsSurface,
        border = BorderStroke(1.dp, MapsBorder),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("fastest_cheapest_summary_card")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Trip comparison summary (Time & Money)",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
                color = MapsTextPrimary
            )

            HorizontalDivider(color = MapsBorder, thickness = 1.dp)

            SummaryFactorRow(
                label = "Recommended (Time & Money)",
                value = "${CommuteFormatters.formatDuration(recommended.durationSeconds)} • ${recommended.costDisplay}",
                detail = recommended.steps.joinToString(" > ") { it.mode.label },
                highlight = true
            )

            SummaryFactorRow(
                label = "Fastest option",
                value = "${CommuteFormatters.formatDuration(fastest.durationSeconds)} • ${fastest.costDisplay}",
                detail = fastest.steps.joinToString(" > ") { it.mode.label },
                highlight = false
            )

            SummaryFactorRow(
                label = "Cheapest option",
                value = "${cheapest.costDisplay} • ${CommuteFormatters.formatDuration(cheapest.durationSeconds)}",
                detail = cheapest.steps.joinToString(" > ") { it.mode.label },
                highlight = false
            )

            if (recommended.olaUberFareDisplay.isNotBlank()) {
                Text(
                    text = recommended.olaUberFareDisplay,
                    style = MaterialTheme.typography.bodySmall,
                    color = MapsAccentBlue
                )
            }
        }
    }
}

@Composable
private fun SummaryFactorRow(
    label: String,
    value: String,
    detail: String,
    highlight: Boolean
) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodySmall,
                color = if (highlight) MapsAccentBlue else MapsTextSecondary
            )
            Text(
                text = value,
                style = MaterialTheme.typography.labelLarge,
                color = if (highlight) MapsAccentBlue else MapsTextPrimary
            )
        }
        if (detail.isNotBlank()) {
            Text(
                text = detail,
                style = MaterialTheme.typography.bodySmall,
                color = MapsTextSecondary
            )
        }
    }
}

@Composable
private fun RoutesEmptyOrErrorBox(
    isError: Boolean,
    message: String,
    onRetry: () -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = MapsSurface,
        border = BorderStroke(1.dp, MapsBorder),
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp)
            .testTag(if (isError) "routes_error_state" else "routes_empty_state")
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            LucideInfoIcon(
                tint = MapsTextSecondary,
                iconSize = 24.dp,
                contentDesc = message
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = message,
                style = MaterialTheme.typography.titleSmall,
                color = MapsTextPrimary,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                onClick = onRetry,
                shape = CircleShape,
                border = BorderStroke(1.dp, MapsAccentBlue),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = MapsSelectedTint,
                    contentColor = MapsAccentBlue
                ),
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("routes_retry_button")
            ) {
                Text(
                    text = "Retry",
                    style = MaterialTheme.typography.labelLarge
                )
            }
        }
    }
}
