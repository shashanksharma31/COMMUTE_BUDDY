package com.example.lib

enum class RouteLabel(val display: String) {
    FASTEST("Fastest"),
    CHEAPEST("Cheapest"),
    LEAST_WALKING("Least walking")
}

enum class CrowdLevel(val display: String) {
    LOW("Low"),
    MEDIUM("Medium"),
    HIGH("High")
}

enum class DataSourceLabel(val display: String) {
    LIVE("LIVE"),
    TIMETABLE("TIMETABLE"),
    ESTIMATED("ESTIMATED"),
    STALE("STALE"),
    UNVERIFIED("UNVERIFIED"),
    UNAVAILABLE("UNAVAILABLE"),
    SAMPLE("SAMPLE")
}

data class ModeAvailabilityStatus(
    val mode: TravelMode,
    val isAvailable: Boolean,
    val reason: String,
    val providerName: String = ""
)

data class RouteStep(
    val mode: TravelMode,
    val text: String,
    val minutes: Int,
    val durationSeconds: Int = minutes * 60
)

data class Route(
    val id: String,
    val label: RouteLabel,
    val minutes: Int,
    val costInr: Int,
    val distanceKm: Double,
    val transfers: Int,
    val walkMeters: Int,
    val crowd: CrowdLevel,
    val dataLabel: DataSourceLabel,
    val reliability: Int, // 0..10
    val steps: List<RouteStep>,
    val path: List<Pair<Double, Double>>, // [lat, lng][]
    val durationSeconds: Int = minutes * 60,
    val costDisplay: String = "₹$costInr",
    val isEstimate: Boolean = false,
    val rawJourneyJson: String? = null,
    val localFareDisplay: String = "Local fare: $costDisplay",
    val olaUberFareDisplay: String = "",
    val trafficAnalysis: String = "",
    val recommendationReason: String = ""
)
