package com.example.lib

import com.example.data.CommuteRoute
import com.example.data.SavedPlace
import com.example.data.TransitAlert
import com.example.data.TransitMode
import com.example.data.TransitStop
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

// Pure formatting, comparison, and time calculation helpers for CommuteBuddy UI
object CommuteFormatters {
    fun formatEtaMinutes(minutes: Int): String {
        val safeMin = minutes.coerceAtLeast(0)
        return if (safeMin == 0) "0 min" else formatDuration(safeMin * 60)
    }

    /**
     * Shared duration formatter taking durationSeconds (Int):
     * - Under 60 min (< 3600s): "X min"
     * - 60 min to < 24 hr (3600s..86399s): "X hr Y min" (or "X hr" if Y == 0)
     * - >= 24 hr (>= 86400s): "X d Y hr" (or "X d" if Y == 0)
     */
    fun formatDuration(durationSeconds: Int): String {
        val safeSeconds = durationSeconds.coerceAtLeast(0)
        if (safeSeconds < 3600) {
            val mins = (safeSeconds / 60.0).roundToInt().coerceAtLeast(1)
            return "$mins min"
        }
        if (safeSeconds < 86400) {
            val totalMinutes = (safeSeconds / 60.0).roundToInt()
            val hours = totalMinutes / 60
            val minutes = totalMinutes % 60
            return if (minutes == 0) "$hours hr" else "$hours hr $minutes min"
        }
        val totalHours = (safeSeconds / 3600.0).roundToInt()
        val days = totalHours / 24
        val hours = totalHours % 24
        return if (hours == 0) "$days d" else "$days d $hours hr"
    }

    fun formatDistanceMeters(meters: Int): String {
        return if (meters >= 1000) {
            val km = meters / 1000f
            String.format(Locale.US, "%.1f km", km)
        } else {
            "$meters m"
        }
    }

    // Formats "Saves ₹20, adds 8 min vs fastest" (or "adds 1 hr 15 min vs fastest" if >= 60 min)
    fun formatVsFastest(selected: Route, allRoutes: List<Route>): String {
        val fastest = allRoutes.find { it.label == RouteLabel.FASTEST }
            ?: allRoutes.minByOrNull { it.durationSeconds }
            ?: return "Saves ₹0, adds 0 min vs fastest"

        val savedInr = fastest.costInr - selected.costInr
        val extraMin = ((selected.durationSeconds - fastest.durationSeconds) / 60.0).roundToInt().coerceAtLeast(0)
        val extraTimeStr = if (extraMin == 0) "0 min" else formatDuration(extraMin * 60)

        return when {
            savedInr > 0 -> "Saves ₹$savedInr, adds $extraTimeStr vs fastest"
            savedInr < 0 -> "Costs ₹${abs(savedInr)} more, adds $extraTimeStr vs fastest"
            else -> "Saves ₹0, adds $extraTimeStr vs fastest"
        }
    }

    // Computes "Leave by 8:40" = reachBy time minus route.minutes minus 5 min buffer
    fun formatLeaveBy(reachByTime: String?, routeMinutes: Int, bufferMinutes: Int = 5): String? {
        if (reachByTime.isNullOrBlank()) return null
        val parsedMinutesOfDay = parseTimeMinutesOfDay(reachByTime) ?: return null
        val totalSubtract = routeMinutes + bufferMinutes
        var leaveMinutesOfDay = (parsedMinutesOfDay - totalSubtract) % (24 * 60)
        if (leaveMinutesOfDay < 0) leaveMinutesOfDay += 24 * 60

        val hour24 = leaveMinutesOfDay / 60
        val minute = leaveMinutesOfDay % 60
        val hour12 = when {
            hour24 == 0 -> 12
            hour24 > 12 -> hour24 - 12
            else -> hour24
        }
        val minStr = String.format(Locale.US, "%02d", minute)
        return "Leave by $hour12:$minStr"
    }

    private fun parseTimeMinutesOfDay(raw: String): Int? {
        val cleaned = raw.trim().uppercase(Locale.US)
        val isPm = cleaned.contains("PM")
        val isAm = cleaned.contains("AM")
        val timePart = cleaned.replace("AM", "").replace("PM", "").trim()
        val parts = timePart.split(":")
        if (parts.size != 2) return null
        val rawHour = parts[0].trim().toIntOrNull() ?: return null
        val rawMin = parts[1].trim().toIntOrNull() ?: return null

        val hour24 = when {
            isPm && rawHour < 12 -> rawHour + 12
            isAm && rawHour == 12 -> 0
            else -> rawHour
        }
        return hour24 * 60 + rawMin
    }

    fun filterStops(
        stops: List<TransitStop>,
        query: String,
        mode: TransitMode
    ): List<TransitStop> {
        val normalized = query.trim().lowercase()
        return stops.filter { stop ->
            val matchesMode = mode == TransitMode.ALL || stop.mode == mode
            val matchesQuery = normalized.isEmpty() ||
                stop.name.lowercase().contains(normalized) ||
                stop.lineCode.lowercase().contains(normalized) ||
                stop.lineName.lowercase().contains(normalized)
            matchesMode && matchesQuery
        }
    }

    fun filterRoutes(
        routes: List<CommuteRoute>,
        query: String,
        mode: TransitMode
    ): List<CommuteRoute> {
        val normalized = query.trim().lowercase()
        return routes.filter { route ->
            val matchesMode = mode == TransitMode.ALL || route.mode == mode
            val matchesQuery = normalized.isEmpty() ||
                route.title.lowercase().contains(normalized) ||
                route.summary.lowercase().contains(normalized)
            matchesMode && matchesQuery
        }
    }

    fun filterSavedPlaces(
        places: List<SavedPlace>,
        query: String,
        category: String
    ): List<SavedPlace> {
        val normalized = query.trim().lowercase()
        return places.filter { place ->
            val matchesCat = category == "All" || place.category.equals(category, ignoreCase = true)
            val matchesQuery = normalized.isEmpty() ||
                place.label.lowercase().contains(normalized) ||
                place.address.lowercase().contains(normalized)
            matchesCat && matchesQuery
        }
    }

    fun filterAlerts(
        alerts: List<TransitAlert>,
        query: String,
        mode: TransitMode
    ): List<TransitAlert> {
        val normalized = query.trim().lowercase()
        return alerts.filter { alert ->
            val matchesMode = mode == TransitMode.ALL || alert.mode == mode
            val matchesQuery = normalized.isEmpty() ||
                alert.title.lowercase().contains(normalized) ||
                alert.lineCode.lowercase().contains(normalized) ||
                alert.detail.lowercase().contains(normalized)
            matchesMode && matchesQuery
        }
    }
}
