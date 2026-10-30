package com.example.lib

import android.content.Context
import com.example.data.DelhiPlace
import org.json.JSONArray
import org.json.JSONObject

data class SavedRouteEntry(
    val id: String,
    val start: DelhiPlace,
    val end: DelhiPlace,
    val vehicle: TravelMode,
    val createdAt: Long = System.currentTimeMillis()
)

// Persists saved routes (start, end, vehicle) to device storage (SharedPreferences)
object SavedRoutesStore {
    private const val PREFS_NAME = "commutebuddy_saved_routes"
    private const val KEY_ROUTES_JSON = "saved_routes_v2"

    fun buildId(start: DelhiPlace, end: DelhiPlace, vehicle: TravelMode): String {
        return "${start.id}_${end.id}_${vehicle.name.lowercase()}"
    }

    private fun serializePlace(place: DelhiPlace): JSONObject = JSONObject().apply {
        put("id", place.id)
        put("name", place.name)
        put("area", place.area)
        put("category", place.category)
        put("lat", place.lat)
        put("lng", place.lng)
        if (place.stationId != null) {
            put("stationId", place.stationId)
        }
    }

    private fun deserializePlace(obj: JSONObject?): DelhiPlace? {
        if (obj == null) return null
        val lat = obj.optDouble("lat", Double.NaN)
        val lng = obj.optDouble("lng", Double.NaN)
        if (lat.isNaN() || lng.isNaN()) return null
        val stationId = obj.optString("stationId", "").ifEmpty { null }
        return DelhiPlace(
            id = obj.optString("id", "saved_place"),
            name = obj.optString("name", "Saved Place"),
            area = obj.optString("area", "India"),
            category = obj.optString("category", "Place"),
            lat = lat,
            lng = lng,
            stationId = stationId
        )
    }

    fun loadAll(context: Context): List<SavedRouteEntry> {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_ROUTES_JSON, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val result = mutableListOf<SavedRouteEntry>()
            for (i in 0 until array.length()) {
                val obj = array.getJSONObject(i)
                val startPlace = deserializePlace(obj.optJSONObject("start")) ?: continue
                val endPlace = deserializePlace(obj.optJSONObject("end")) ?: continue
                val modeName = obj.optString("vehicle", TravelMode.MIX.name)
                val createdAt = obj.optLong("createdAt", 0L)
                val vehicle = runCatching { TravelMode.valueOf(modeName) }.getOrDefault(TravelMode.MIX)
                val id = obj.optString("id").ifEmpty { buildId(startPlace, endPlace, vehicle) }

                result.add(
                    SavedRouteEntry(
                        id = id,
                        start = startPlace,
                        end = endPlace,
                        vehicle = vehicle,
                        createdAt = createdAt
                    )
                )
            }
            result
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun saveAll(context: Context, entries: List<SavedRouteEntry>) {
        val array = JSONArray()
        entries.forEach { entry ->
            val obj = JSONObject().apply {
                put("id", entry.id)
                put("start", serializePlace(entry.start))
                put("end", serializePlace(entry.end))
                put("vehicle", entry.vehicle.name)
                put("createdAt", entry.createdAt)
            }
            array.put(obj)
        }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ROUTES_JSON, array.toString())
            .apply()
    }
}
