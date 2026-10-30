package com.example.data

// UI state mode for every list (Loading, Empty, Error, Ready)
enum class DataLoadState {
    READY,
    LOADING,
    EMPTY,
    ERROR
}

enum class TransitMode(val label: String) {
    ALL("All Transit"),
    SUBWAY("Subway"),
    BUS("Bus"),
    RAIL("Commuter Rail")
}

data class TransitStop(
    val id: String,
    val name: String,
    val lineCode: String,
    val lineName: String,
    val mode: TransitMode,
    val platform: String,
    val nextArrivalMin: Int,
    val frequencyText: String,
    val statusText: String,
    val distanceMeters: Int,
    // Normalized map coordinates (0.1f..0.9f)
    val mapX: Float,
    val mapY: Float
)

data class CommuteRoute(
    val id: String,
    val title: String,
    val summary: String,
    val durationMin: Int,
    val departureWindow: String,
    val arrivalTime: String,
    val walkMinutes: Int,
    val transfers: Int,
    val fareText: String,
    val mode: TransitMode,
    val badgeText: String,
    val stopIds: List<String>,
    val steps: List<String>
)

data class SavedPlace(
    val id: String,
    val label: String,
    val address: String,
    val category: String,
    val etaMinutes: Int,
    val linkedStopId: String
)

data class TransitAlert(
    val id: String,
    val lineCode: String,
    val title: String,
    val detail: String,
    val updatedAgo: String,
    val mode: TransitMode,
    val isDelayed: Boolean
)

object CommuteDummyData {
    val stops: List<TransitStop> = listOf(
        TransitStop(
            id = "stop_montgomery",
            name = "Montgomery St Station",
            lineCode = "B1",
            lineName = "Blue Line Inbound",
            mode = TransitMode.SUBWAY,
            platform = "Platform 2",
            nextArrivalMin = 3,
            frequencyText = "Every 6 min",
            statusText = "On time",
            distanceMeters = 140,
            mapX = 0.48f,
            mapY = 0.44f
        ),
        TransitStop(
            id = "stop_embarcadero",
            name = "Embarcadero Plaza",
            lineCode = "B1",
            lineName = "Blue Line Bayfront",
            mode = TransitMode.SUBWAY,
            platform = "Platform 1",
            nextArrivalMin = 5,
            frequencyText = "Every 6 min",
            statusText = "On time",
            distanceMeters = 320,
            mapX = 0.72f,
            mapY = 0.30f
        ),
        TransitStop(
            id = "stop_market_3rd",
            name = "Market St & 3rd St",
            lineCode = "38R",
            lineName = "Geary Rapid",
            mode = TransitMode.BUS,
            platform = "Stop ID 15422",
            nextArrivalMin = 4,
            frequencyText = "Every 8 min",
            statusText = "On time",
            distanceMeters = 210,
            mapX = 0.36f,
            mapY = 0.56f
        ),
        TransitStop(
            id = "stop_caltrain",
            name = "4th & King Terminal",
            lineCode = "L2",
            lineName = "Southbay Express",
            mode = TransitMode.RAIL,
            platform = "Track 4",
            nextArrivalMin = 9,
            frequencyText = "Every 15 min",
            statusText = "Boarding",
            distanceMeters = 680,
            mapX = 0.64f,
            mapY = 0.74f
        ),
        TransitStop(
            id = "stop_civic",
            name = "Civic Center Hub",
            lineCode = "14R",
            lineName = "Mission Rapid",
            mode = TransitMode.BUS,
            platform = "Stop ID 11809",
            nextArrivalMin = 7,
            frequencyText = "Every 10 min",
            statusText = "Crowded",
            distanceMeters = 520,
            mapX = 0.22f,
            mapY = 0.68f
        )
    )

    val routes: List<CommuteRoute> = listOf(
        CommuteRoute(
            id = "route_1",
            title = "B1 Subway to Embarcadero",
            summary = "From Montgomery St Station • Platform 2",
            durationMin = 14,
            departureWindow = "8:18 AM – 8:32 AM",
            arrivalTime = "Arrive 8:32 AM",
            walkMinutes = 3,
            transfers = 0,
            fareText = "$2.50",
            mode = TransitMode.SUBWAY,
            badgeText = "Fastest",
            stopIds = listOf("stop_civic", "stop_market_3rd", "stop_montgomery", "stop_embarcadero"),
            steps = listOf(
                "Walk 2 min (140 m) to Montgomery St Station",
                "Board B1 Blue Line toward Bayfront (3 stops, 9 min)",
                "Exit at Embarcadero Plaza • East Concourse"
            )
        ),
        CommuteRoute(
            id = "route_2",
            title = "38R Bus via Market St",
            summary = "From Market St & 3rd St • Stop 15422",
            durationMin = 19,
            departureWindow = "8:19 AM – 8:38 AM",
            arrivalTime = "Arrive 8:38 AM",
            walkMinutes = 2,
            transfers = 0,
            fareText = "$2.50",
            mode = TransitMode.BUS,
            badgeText = "Less walking",
            stopIds = listOf("stop_civic", "stop_market_3rd", "stop_montgomery"),
            steps = listOf(
                "Walk 2 min (210 m) to Market St & 3rd St",
                "Ride 38R Geary Rapid Bus (4 stops, 15 min)",
                "Walk 2 min to destination entrance"
            )
        ),
        CommuteRoute(
            id = "route_3",
            title = "L2 Express to 4th & King",
            summary = "Transfer at Montgomery St • Track 4",
            durationMin = 22,
            departureWindow = "8:21 AM – 8:43 AM",
            arrivalTime = "Arrive 8:43 AM",
            walkMinutes = 5,
            transfers = 1,
            fareText = "$3.75",
            mode = TransitMode.RAIL,
            badgeText = "Direct rail",
            stopIds = listOf("stop_montgomery", "stop_caltrain"),
            steps = listOf(
                "Walk 3 min to Montgomery St Station",
                "Connect to L2 Southbay Express at 4th & King",
                "Arrive at Track 4 North Concourse"
            )
        )
    )

    val savedPlaces: List<SavedPlace> = listOf(
        SavedPlace(
            id = "place_home",
            label = "Home",
            address = "742 Grove St, Civic Center",
            category = "Favorites",
            etaMinutes = 16,
            linkedStopId = "stop_civic"
        ),
        SavedPlace(
            id = "place_work",
            label = "Work",
            address = "101 California St, Suite 1400",
            category = "Favorites",
            etaMinutes = 11,
            linkedStopId = "stop_embarcadero"
        ),
        SavedPlace(
            id = "place_station",
            label = "Caltrain Depot",
            address = "700 4th St, South Beach",
            category = "Stations",
            etaMinutes = 18,
            linkedStopId = "stop_caltrain"
        ),
        SavedPlace(
            id = "place_library",
            label = "Downtown Library",
            address = "100 Larkin St",
            category = "Recent",
            etaMinutes = 9,
            linkedStopId = "stop_market_3rd"
        )
    )

    val alerts: List<TransitAlert> = listOf(
        TransitAlert(
            id = "alert_1",
            lineCode = "B1",
            title = "Blue Line running on schedule",
            detail = "All 6-car trains operating every 6 min between Civic Center and Embarcadero.",
            updatedAgo = "Updated 2 min ago",
            mode = TransitMode.SUBWAY,
            isDelayed = false
        ),
        TransitAlert(
            id = "alert_2",
            lineCode = "38R",
            title = "Minor boarding delay at 3rd St",
            detail = "Expect 3 min additional dwell time due to transit lane signal priority maintenance.",
            updatedAgo = "Updated 6 min ago",
            mode = TransitMode.BUS,
            isDelayed = true
        ),
        TransitAlert(
            id = "alert_3",
            lineCode = "L2",
            title = "Track 4 platform assignment",
            detail = "Southbay Express departures board from Track 4 until 10:00 AM.",
            updatedAgo = "Updated 11 min ago",
            mode = TransitMode.RAIL,
            isDelayed = false
        )
    )
}
