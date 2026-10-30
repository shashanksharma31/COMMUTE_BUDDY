package com.example.lib

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.example.components.SheetSnapPoint
import com.example.data.DataLoadState
import com.example.data.DelhiPlace
import com.example.data.DelhiPlacesData

// Single-select travel modes ("Mix" is default and combines multiple transport vehicles)
enum class TravelMode(val label: String) {
    MIX("Mix"),
    METRO("Metro"),
    BUS("Bus"),
    TRAIN("Train"),
    FLIGHT("Flight"),
    CAR("Car"),
    BIKE("Bike"),
    WALK("Walk")
}

// Context holder for trip start/end places, travel mode, Reach by time, generated routes, and saved routes
@Stable
class TripStateHolder(
    private val appContext: Context? = null
) {
    var startPlace by mutableStateOf<DelhiPlace?>(null)
        private set

    var endPlace by mutableStateOf<DelhiPlace?>(null)
        private set

    var selectedMode by mutableStateOf(TravelMode.MIX)
        private set

    var reachByTime by mutableStateOf<String?>(null)
        private set

    var isRoutesSheetOpen by mutableStateOf(false)
        private set

    var sheetSnapPoint by mutableStateOf(SheetSnapPoint.HALF)
        private set

    var routeSearchCount by mutableIntStateOf(0)
        private set

    var routes by mutableStateOf<List<Route>>(emptyList())
        private set

    var recommendedRoute by mutableStateOf<Route?>(null)
        private set

    var selectedRoute by mutableStateOf<Route?>(null)
        private set

    var isStepByStepOpen by mutableStateOf(false)
        private set

    var isLiveRideActive by mutableStateOf(false)
        private set

    var isSavedScreenOpen by mutableStateOf(false)
        private set

    var isChatExpanded by mutableStateOf(false)
        private set

    var savedRoutes by mutableStateOf<List<SavedRouteEntry>>(
        appContext?.let { SavedRoutesStore.loadAll(it) } ?: emptyList()
    )
        private set

    var routesLoadState by mutableStateOf(DataLoadState.READY)
        private set

    var emptyRoutesMessage by mutableStateOf("No routes found")
        internal set

    var modeAvailability by mutableStateOf<CityModeAvailability?>(null)
        private set

    val availableModes: List<TravelMode>
        get() = modeAvailability?.availableModes ?: TravelMode.entries

    val hasBothPlaces: Boolean
        get() = startPlace != null && endPlace != null

    val isCurrentTripBookmarked: Boolean
        get() {
            val s = startPlace ?: return false
            val e = endPlace ?: return false
            val id = SavedRoutesStore.buildId(s, e, selectedMode)
            return savedRoutes.any { it.id == id }
        }

    // Ordered routes with the Recommended route placed first
    val orderedRoutes: List<Route>
        get() {
            val recId = recommendedRoute?.id ?: return routes
            val rec = routes.find { it.id == recId } ?: return routes
            return listOf(rec) + routes.filter { it.id != recId }
        }

    private fun refreshInstantAvailability() {
        val s = startPlace
        val e = endPlace
        if (s != null && e != null) {
            val avail = TransitAvailabilityApi.evaluateInstantModeAvailability(s, e, reachByTime)
            modeAvailability = avail
            if (selectedMode !in avail.availableModes) {
                selectedMode = TravelMode.MIX
            }
        } else {
            modeAvailability = null
        }
    }

    internal fun onLiveAvailabilityUpdated(liveAvail: CityModeAvailability) {
        modeAvailability = liveAvail
        if (selectedMode !in liveAvail.availableModes) {
            selectedMode = TravelMode.MIX
        }
    }

    private fun persistSavedRoutes(updated: List<SavedRouteEntry>) {
        savedRoutes = updated
        appContext?.let { SavedRoutesStore.saveAll(it, updated) }
    }

    fun toggleBookmarkCurrentTrip() {
        val s = startPlace ?: return
        val e = endPlace ?: return
        val id = SavedRoutesStore.buildId(s, e, selectedMode)
        val exists = savedRoutes.any { it.id == id }
        val next = if (exists) {
            savedRoutes.filterNot { it.id == id }
        } else {
            listOf(
                SavedRouteEntry(
                    id = id,
                    start = s,
                    end = e,
                    vehicle = selectedMode
                )
            ) + savedRoutes
        }
        persistSavedRoutes(next)
    }

    fun removeSavedRoute(id: String) {
        persistSavedRoutes(savedRoutes.filterNot { it.id == id })
    }

    fun restoreSavedRoute(entry: SavedRouteEntry, index: Int) {
        val filtered = savedRoutes.filterNot { it.id == entry.id }.toMutableList()
        val targetIndex = index.coerceIn(0, filtered.size)
        filtered.add(targetIndex, entry)
        persistSavedRoutes(filtered)
    }

    fun openSavedScreen() {
        isChatExpanded = false
        isSavedScreenOpen = true
    }

    fun closeSavedScreen() {
        isSavedScreenOpen = false
    }

    fun setChatExpandedState(expanded: Boolean) {
        isChatExpanded = expanded
    }

    // Fills the trip, runs a fresh search, and returns to the map
    fun checkSavedRouteNow(entry: SavedRouteEntry) {
        startPlace = entry.start
        endPlace = entry.end
        selectedMode = entry.vehicle
        isSavedScreenOpen = false
        isStepByStepOpen = false
        isLiveRideActive = false
        isChatExpanded = false
        refreshInstantAvailability()
        triggerRouteSearch()
    }

    fun updateStart(place: DelhiPlace?) {
        startPlace = place
        isStepByStepOpen = false
        isLiveRideActive = false
        refreshInstantAvailability()
        if (place == null) {
            isRoutesSheetOpen = false
            routes = emptyList()
            recommendedRoute = null
            selectedRoute = null
        } else if (endPlace != null) {
            triggerRouteSearch()
        }
    }

    fun updateEnd(place: DelhiPlace?) {
        endPlace = place
        isStepByStepOpen = false
        isLiveRideActive = false
        refreshInstantAvailability()
        if (place == null) {
            isRoutesSheetOpen = false
            routes = emptyList()
            recommendedRoute = null
            selectedRoute = null
        } else if (startPlace != null) {
            triggerRouteSearch()
        }
    }

    fun swapPlaces() {
        val temp = startPlace
        startPlace = endPlace
        endPlace = temp
        isStepByStepOpen = false
        isLiveRideActive = false
        isChatExpanded = false
        refreshInstantAvailability()
        if (hasBothPlaces) {
            triggerRouteSearch()
        }
    }

    // Selecting a chip triggers route search automatically and closes chat overlay so routes are visible
    fun selectTravelMode(mode: TravelMode) {
        selectedMode = mode
        isStepByStepOpen = false
        isChatExpanded = false
        if (hasBothPlaces) {
            triggerRouteSearch()
        }
    }

    fun updateReachByTime(time: String?) {
        reachByTime = time
        isStepByStepOpen = false
        isChatExpanded = false
        refreshInstantAvailability()
        if (hasBothPlaces) {
            triggerRouteSearch()
        }
    }

    fun selectRoute(route: Route) {
        selectedRoute = route
    }

    fun openStepByStep(route: Route) {
        selectedRoute = route
        isStepByStepOpen = true
        isLiveRideActive = true
        isChatExpanded = false
    }

    fun closeStepByStep() {
        isStepByStepOpen = false
    }

    fun startLiveRide(route: Route? = selectedRoute) {
        if (route != null) {
            selectedRoute = route
        }
        isLiveRideActive = true
        isChatExpanded = false
        if (isRoutesSheetOpen && sheetSnapPoint == SheetSnapPoint.FULL) {
            sheetSnapPoint = SheetSnapPoint.HALF
        }
    }

    fun toggleLiveRide() {
        isLiveRideActive = !isLiveRideActive
        if (isLiveRideActive) {
            isChatExpanded = false
            if (isRoutesSheetOpen && sheetSnapPoint == SheetSnapPoint.FULL) {
                sheetSnapPoint = SheetSnapPoint.HALF
            }
        }
    }

    fun stopLiveRide() {
        isLiveRideActive = false
    }

    fun updateSheetSnapPoint(snap: SheetSnapPoint) {
        sheetSnapPoint = snap
    }

    fun setRoutesSheetOpenState(open: Boolean) {
        isRoutesSheetOpen = open
        if (!open) {
            isStepByStepOpen = false
        } else {
            isChatExpanded = false
            if (sheetSnapPoint == SheetSnapPoint.PEEK) {
                sheetSnapPoint = SheetSnapPoint.HALF
            }
        }
    }

    fun triggerRouteSearch() {
        isRoutesSheetOpen = true
        isStepByStepOpen = false
        isChatExpanded = false
        sheetSnapPoint = SheetSnapPoint.HALF
        if (routes.isEmpty()) {
            routesLoadState = DataLoadState.LOADING
        }
        routeSearchCount += 1
    }

    internal fun onSearchLoading() {
        routesLoadState = DataLoadState.LOADING
    }

    internal fun onSearchSuccess(result: List<Route>) {
        routes = result
        val rec = Recommend.recommendRoute(result)
        recommendedRoute = rec
        selectedRoute = rec ?: result.firstOrNull()
        routesLoadState = if (result.isEmpty()) DataLoadState.EMPTY else DataLoadState.READY
    }

    internal fun onSearchError() {
        routes = emptyList()
        recommendedRoute = null
        selectedRoute = null
        routesLoadState = DataLoadState.ERROR
    }
}

val LocalTripContext = compositionLocalOf<TripStateHolder> {
    error("LocalTripContext not provided")
}

@Composable
fun TripContextProvider(
    content: @Composable () -> Unit
) {
    val context = LocalContext.current.applicationContext
    val holder = remember(context) { TripStateHolder(context) }
    val start = holder.startPlace
    val end = holder.endPlace
    val vehicle = holder.selectedMode
    val reachBy = holder.reachByTime
    val searchCount = holder.routeSearchCount

    LaunchedEffect(start, end, vehicle, reachBy, searchCount) {
        if (start != null && end != null && searchCount > 0) {
            try {
                val isSamePlace = start.id == end.id ||
                    (start.name.equals(end.name, ignoreCase = true) &&
                        DelhiPlacesData.haversineMeters(start.lat, start.lng, end.lat, end.lng) < 40.0)
                if (isSamePlace) {
                    holder.emptyRoutesMessage = "Start and destination are the same place. Choose two different locations."
                    holder.onSearchSuccess(emptyList())
                    return@LaunchedEffect
                }
                holder.emptyRoutesMessage = "No routes found"

                // 1. Evaluate instant city mode availability and compute initial routes immediately
                val instantAvail = TransitAvailabilityApi.evaluateInstantModeAvailability(start, end, reachBy)
                holder.onLiveAvailabilityUpdated(instantAvail)
                val activeVehicle = if (vehicle in instantAvail.availableModes) vehicle else TravelMode.MIX
                val initialRoutes = planRoutesOnDevice(
                    start = start,
                    end = end,
                    vehicle = activeVehicle,
                    reachBy = reachBy,
                    preloadedAvailability = instantAvail,
                    allowNetworkCalls = false
                )
                holder.onSearchSuccess(initialRoutes)

                // 2. If either place used fallback coordinates or collided on city center, resolve real coordinates via 4-API geocoder
                val resolvedStart = DelhiPlacesData.resolveAccuratePlace(start)
                val resolvedEnd = DelhiPlacesData.resolveAccuratePlace(
                    place = end,
                    avoidLat = resolvedStart.lat,
                    avoidLng = resolvedStart.lng
                )

                // 3. Refine in background with live multi-server road geometry, Overpass station check, and Gemini verification
                val liveAvailability = runCatching {
                    TransitAvailabilityApi.fetchLiveTransitAndPricingFromApis(
                        start = resolvedStart,
                        end = resolvedEnd,
                        reachBy = reachBy
                    )
                }.getOrNull()
                if (liveAvailability != null) {
                    holder.onLiveAvailabilityUpdated(liveAvailability)
                    val verifiedVehicle = if (vehicle in liveAvailability.availableModes) vehicle else TravelMode.MIX
                    val refinedRoutes = getRoutes(
                        start = resolvedStart,
                        end = resolvedEnd,
                        vehicle = verifiedVehicle,
                        reachBy = reachBy
                    )
                    if (refinedRoutes.isNotEmpty()) {
                        holder.onSearchSuccess(refinedRoutes)
                    }
                }
            } catch (_: Exception) {
                if (holder.routes.isEmpty()) {
                    holder.onSearchError()
                }
            }
        }
    }

    CompositionLocalProvider(LocalTripContext provides holder) {
        content()
    }
}
