package com.example.lib

import android.util.Log
import com.example.data.DelhiPlace
import com.example.data.DelhiPlacesData
import com.google.firebase.Firebase
import com.google.firebase.ai.ai
import com.google.firebase.ai.type.Schema
import com.google.firebase.ai.type.generationConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Calendar
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

private const val TAG = "TransitAvailabilityApi"

data class CityModeAvailability(
    val startMetroCity: String?,
    val endMetroCity: String?,
    val hasMetroAtStart: Boolean,
    val hasMetroAtEnd: Boolean,
    val metroSingleModeAvailable: Boolean,
    val trainAvailable: Boolean,
    val busAvailable: Boolean,
    val flightAvailable: Boolean,
    val carAvailable: Boolean,
    val bikeAvailable: Boolean,
    val walkAvailable: Boolean,
    val availableModes: List<TravelMode>,
    val unavailableReasons: Map<TravelMode, String>,
    val localBusOperator: String,
    val metroOperator: String?,
    val availabilitySummaryBanner: String,
    val liveRoadDistanceKm: Double? = null,
    val liveRoadDurationSeconds: Int? = null,
    val liveRoadPolyline: List<Pair<Double, Double>>? = null,
    val geminiPricingNote: String? = null
)

data class LiveOsrmRouteResult(
    val distanceKm: Double,
    val durationSeconds: Int,
    val polyline: List<Pair<Double, Double>>
)

data class GeminiTransitVerification(
    val metroAvailableInOrigin: Boolean,
    val metroAvailableInDestination: Boolean,
    val directMetroPossible: Boolean,
    val trainAvailable: Boolean,
    val flightAvailable: Boolean,
    val primaryOperatorSummary: String,
    val recommendedChainSummary: String,
    val localFareInr: Int,
    val olaUberMiniInr: Int,
    val olaUberSedanInr: Int
)

object TransitAvailabilityApi {
    private val osrmCache = ConcurrentHashMap<String, LiveOsrmRouteResult>()
    private val geminiVerificationCache = ConcurrentHashMap<String, GeminiTransitVerification>()
    private val overpassMetroCache = ConcurrentHashMap<String, Boolean>()

    private val verificationSchema = Schema.obj(
        properties = mapOf(
            "metroAvailableInOrigin" to Schema.boolean(
                description = "True only if the origin city has an operational Metro rail system"
            ),
            "metroAvailableInDestination" to Schema.boolean(
                description = "True only if the destination city has an operational Metro rail system"
            ),
            "directMetroPossible" to Schema.boolean(
                description = "True only if origin and destination are connected by the same urban Metro network"
            ),
            "trainAvailable" to Schema.boolean(
                description = "True if Indian Railways or suburban rail connects the origin and destination"
            ),
            "flightAvailable" to Schema.boolean(
                description = "True only if commercial flights operate between the origin and destination cities (distance >= 165 km)"
            ),
            "primaryOperatorSummary" to Schema.string(
                description = "Short plain-text summary of real operators (e.g. Indian Railways, DMRC, UPSRTC, DTC, BMTC) without emojis"
            ),
            "recommendedChainSummary" to Schema.string(
                description = "Concise multi-modal step summary with real prices in INR without emojis"
            ),
            "localFareInr" to Schema.integer(
                description = "Estimated total local public transit fare in INR"
            ),
            "olaUberMiniInr" to Schema.integer(
                description = "Estimated current Ola/Uber Mini cab fare in INR for the trip"
            ),
            "olaUberSedanInr" to Schema.integer(
                description = "Estimated current Ola/Uber Sedan cab fare in INR for the trip"
            )
        )
    )

    private val verificationConfig = generationConfig {
        responseMimeType = "application/json"
        responseSchema = verificationSchema
        temperature = 0.1f
    }

    private val geminiModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = verificationConfig
        )
    }

    /**
     * Identifies which operational Indian Metro network (if any) serves a given place.
     * Cities like Mathura, Vrindavan, Chandigarh, Amritsar, Varanasi, Bhopal, Patna, etc.
     * return null because they do NOT have an operational Metro network.
     */
    fun resolveMetroNetworkRegion(place: DelhiPlace): String? {
        val text = "${place.name} ${place.area}".lowercase()

        // Explicitly exclude cities without operational Metro networks
        val nonMetroKeywords = listOf(
            "mathura", "vrindavan", "govardhan", "barsana", "palwal", "aligarh", "hathras",
            "chandigarh", "mohali", "panchkula", "amritsar", "jalandhar", "ludhiana",
            "varanasi", "ayodhya", "prayagraj", "gorakhpur", "jhansi", "gwalior",
            "bhopal", "ujjain", "jabalpur", "raipur", "ranchi", "patna", "gaya",
            "dehradun", "haridwar", "rishikesh", "shimla", "manali", "jammu", "srinagar",
            "udaipur", "jodhpur", "ajmer", "pushkar", "kota", "bikaner", "mount abu",
            "surat", "vadodara", "rajkot", "goa", "panaji", "mysuru", "mangaluru",
            "coimbatore", "madurai", "tiruchirappalli", "visakhapatnam", "vijayawada",
            "bhubaneswar", "puri", "guwahati", "shillong", "siliguri", "darjeeling"
        )
        if (nonMetroKeywords.any { text.contains(it) }) {
            return null
        }

        // Check coordinate bounding boxes + city names for operational Indian Metro networks
        val lat = place.lat
        val lng = place.lng

        // 1. Delhi-NCR Metro Network (DMRC / NMRC / Rapid Metro: Delhi, Noida, Greater Noida, Ghaziabad, Gurugram, Faridabad)
        if (lat in 28.32..28.88 && lng in 76.88..77.62) {
            return "Delhi-NCR Metro (DMRC / NMRC)"
        }
        if (text.contains("delhi") || text.contains("noida") || text.contains("ghaziabad") ||
            text.contains("siddharth vihar") || text.contains("indirapuram") || text.contains("vaishali") ||
            text.contains("vasundhara") || text.contains("gurugram") || text.contains("faridabad")
        ) {
            return "Delhi-NCR Metro (DMRC / NMRC)"
        }

        // 2. Mumbai MMR Metro (MMRDA / MMRC)
        if ((lat in 18.88..19.32 && lng in 72.75..73.12) || text.contains("mumbai") || text.contains("bandra") || text.contains("andheri")) {
            return "Mumbai Metro (MMRC)"
        }

        // 3. Bengaluru Namma Metro (BMRCL)
        if ((lat in 12.82..13.15 && lng in 77.45..77.78) || text.contains("bengaluru") || text.contains("bangalore") || text.contains("whitefield") || text.contains("koramangala")) {
            return "Namma Metro Bengaluru (BMRCL)"
        }

        // 4. Hyderabad Metro (HMRL)
        if ((lat in 17.30..17.56 && lng in 78.30..78.62) || text.contains("hyderabad") || text.contains("hitec city") || text.contains("secunderabad")) {
            return "Hyderabad Metro (HMRL)"
        }

        // 5. Chennai Metro (CMRL)
        if ((lat in 12.90..13.20 && lng in 80.12..80.35) || text.contains("chennai") || text.contains("guindy")) {
            return "Chennai Metro (CMRL)"
        }

        // 6. Kolkata Metro
        if ((lat in 22.45..22.68 && lng in 88.28..88.48) || text.contains("kolkata") || text.contains("howrah") || text.contains("salt lake")) {
            return "Kolkata Metro"
        }

        // 7. Jaipur Metro (JMRC)
        if ((lat in 26.82..26.98 && lng in 75.72..75.88) || text.contains("jaipur")) {
            return "Jaipur Metro (JMRC)"
        }

        // 8. Lucknow Metro (UPMRC)
        if ((lat in 26.75..26.95 && lng in 80.85..81.05) || text.contains("lucknow") || text.contains("gomti nagar")) {
            return "Lucknow Metro (UPMRC)"
        }

        // 9. Agra Metro (UPMRC Priority Corridor)
        if ((lat in 27.14..27.22 && lng in 77.95..78.06) || text.contains("agra")) {
            return "Agra Metro (UPMRC)"
        }

        // 10. Ahmedabad Metro (GMRC)
        if ((lat in 22.95..23.12 && lng in 72.50..72.68) || text.contains("ahmedabad") || text.contains("sabarmati")) {
            return "Ahmedabad Metro (GMRC)"
        }

        // 11. Pune Metro (MahaMetro)
        if ((lat in 18.45..18.65 && lng in 73.72..73.96) || text.contains("pune") || text.contains("hinjewadi") || text.contains("shivajinagar")) {
            return "Pune Metro (MahaMetro)"
        }

        // 12. Kochi Metro (KMRL)
        if ((lat in 9.90..10.12 && lng in 76.24..76.40) || text.contains("kochi") || text.contains("ernakulam")) {
            return "Kochi Metro (KMRL)"
        }

        // 13. Nagpur, Kanpur, Indore Metro corridors
        if (text.contains("nagpur")) return "Nagpur Metro (MahaMetro)"
        if (text.contains("kanpur")) return "Kanpur Metro (UPMRC)"
        if (text.contains("indore")) return "Indore Metro (MPMRCL)"

        return null
    }

    fun resolveLocalBusOperator(start: DelhiPlace, end: DelhiPlace, isIntercity: Boolean): String {
        val combined = "${start.name} ${start.area} ${end.name} ${end.area}".lowercase()
        return when {
            combined.contains("mathura") || combined.contains("vrindavan") || combined.contains("agra") ||
                combined.contains("lucknow") || combined.contains("varanasi") || combined.contains("ghaziabad") ||
                combined.contains("noida") -> {
                if (isIntercity) "UPSRTC Janrath / Shatabdi AC Bus" else "UP City Electric Bus / UPSRTC Local"
            }
            combined.contains("jaipur") || combined.contains("rajasthan") -> {
                if (isIntercity) "RSRTC Super Luxury / Express Bus" else "JCTSL Jaipur City Bus"
            }
            combined.contains("chandigarh") || combined.contains("amritsar") || combined.contains("punjab") -> {
                if (isIntercity) "PRTC / CTU HVAC Intercity Bus" else "CTU Chandigarh Electric City Bus"
            }
            combined.contains("mumbai") || combined.contains("pune") -> {
                if (isIntercity) "MSRTC Shivneri AC Volvo" else "BEST / PMPML AC Electric Bus"
            }
            combined.contains("bengaluru") || combined.contains("koramangala") || combined.contains("whitefield") -> {
                if (isIntercity) "KSRTC Airavat Club Class" else "BMTC Vajra AC Electric Bus"
            }
            combined.contains("hyderabad") || combined.contains("secunderabad") -> {
                if (isIntercity) "TGSRTC Garuda Plus AC" else "TGSRTC Metro Express City Bus"
            }
            combined.contains("chennai") -> {
                if (isIntercity) "SETC Ultra Deluxe AC" else "MTC Chennai City Bus"
            }
            combined.contains("kolkata") || combined.contains("howrah") -> {
                if (isIntercity) "WBTC AC Volvo Express" else "WBTC Kolkata AC City Bus"
            }
            combined.contains("ahmedabad") -> {
                if (isIntercity) "GSRTC Volvo Express" else "AMTS / Janmarg BRTS Electric Bus"
            }
            combined.contains("kochi") -> {
                if (isIntercity) "KSRTC Swift Garuda AC" else "Kochi City Private / KSRTC Feeder Bus"
            }
            combined.contains("delhi") || combined.contains("gurugram") || combined.contains("faridabad") -> {
                if (isIntercity) "DTC / Interstate ISBT AC Bus" else "DTC / OTD Delhi Electric Bus"
            }
            else -> if (isIntercity) "State Roadways AC Express Bus" else "City Electric Transit Bus"
        }
    }

    /**
     * Synchronous instant mode availability evaluation so UI chips and route planning
     * never show unavailable modes (like Metro in Mathura/Vrindavan or Flight on short trips).
     */
    fun evaluateInstantModeAvailability(
        start: DelhiPlace,
        end: DelhiPlace,
        reachBy: String? = null
    ): CityModeAvailability {
        val straightMeters = DelhiPlacesData.haversineMeters(start.lat, start.lng, end.lat, end.lng)
            .coerceAtLeast(300.0)
        val distanceKm = ((straightMeters * 1.26) / 1000.0 * 10.0).roundToInt() / 10.0

        val istCal = Calendar.getInstance(TimeZone.getTimeZone("Asia/Kolkata"))
        val hour24 = parseHour24(reachBy) ?: istCal.get(Calendar.HOUR_OF_DAY)
        val isMetroOperatingHours = hour24 in 5..22

        val startMetroRegion = resolveMetroNetworkRegion(start)
        val endMetroRegion = resolveMetroNetworkRegion(end)
        val overpassStartMetro = overpassMetroCache.get("${(start.lat * 100).roundToInt()}_${(start.lng * 100).roundToInt()}")
        val overpassEndMetro = overpassMetroCache.get("${(end.lat * 100).roundToInt()}_${(end.lng * 100).roundToInt()}")

        val hasMetroAtStart = (startMetroRegion != null) || (overpassStartMetro == true)
        val hasMetroAtEnd = (endMetroRegion != null) || (overpassEndMetro == true)

        // Single-mode Metro is ONLY available if both origin and destination are in the same operational Metro network
        val sameMetroNetwork = startMetroRegion != null && startMetroRegion == endMetroRegion && distanceKm <= 65.0
        val metroSingleModeAvailable = hasMetroAtStart && hasMetroAtEnd && sameMetroNetwork && isMetroOperatingHours

        // Train is available when distance >= 4.5 km (intercity or suburban rail corridor)
        val trainAvailable = distanceKm >= 4.5

        // Commercial Flights are only available for intercity trips >= 165 km
        val flightAvailable = distanceKm >= 165.0

        // Walk is only practical up to 18 km; Bike/Two-wheeler up to 160 km
        val walkAvailable = distanceKm <= 18.0
        val bikeAvailable = distanceKm <= 160.0
        val busAvailable = true
        val carAvailable = true

        val unavailableReasons = mutableMapOf<TravelMode, String>()
        if (!metroSingleModeAvailable) {
            val reason = when {
                !hasMetroAtStart && !hasMetroAtEnd ->
                    "No operational Metro network in ${start.area} or ${end.area}"
                !hasMetroAtStart ->
                    "No Metro network in ${start.area} (use Mix for Train/Bus + ${endMetroRegion ?: "destination transit"})"
                !hasMetroAtEnd ->
                    "No Metro network in ${end.area} (use Mix for ${startMetroRegion ?: "origin transit"} + Train/Bus)"
                !sameMetroNetwork ->
                    "Direct Metro does not run intercity between ${start.area} and ${end.area} (use Mix or Train)"
                else ->
                    "Metro is closed during late-night hours (operates 05:30 AM – 11:30 PM)"
            }
            unavailableReasons[TravelMode.METRO] = reason
        }
        if (!flightAvailable) {
            unavailableReasons[TravelMode.FLIGHT] =
                "No commercial flight route for ${distanceKm.roundToInt()} km trip between ${start.area} and ${end.area}"
        }
        if (!trainAvailable) {
            unavailableReasons[TravelMode.TRAIN] =
                "Short ${distanceKm} km local trip — use Bus, Auto/Bike, or Walk instead of Railway"
        }
        if (!walkAvailable) {
            unavailableReasons[TravelMode.WALK] =
                "Walking is unavailable for ${distanceKm.roundToInt()} km intercity distance"
        }
        if (!bikeAvailable) {
            unavailableReasons[TravelMode.BIKE] =
                "Two-wheeler taxi is unavailable for ${distanceKm.roundToInt()} km long-distance trip"
        }

        val availableModes = buildList {
            add(TravelMode.MIX)
            if (metroSingleModeAvailable) add(TravelMode.METRO)
            if (busAvailable) add(TravelMode.BUS)
            if (trainAvailable) add(TravelMode.TRAIN)
            if (flightAvailable) add(TravelMode.FLIGHT)
            if (carAvailable) add(TravelMode.CAR)
            if (bikeAvailable) add(TravelMode.BIKE)
            if (walkAvailable) add(TravelMode.WALK)
        }

        val isIntercity = distanceKm >= 38.0
        val localBusOp = resolveLocalBusOperator(start, end, isIntercity)
        val metroOp = startMetroRegion ?: endMetroRegion

        val activeModeNames = availableModes.filter { it != TravelMode.MIX }.joinToString(", ") { it.label }
        val missingNote = when {
            !hasMetroAtStart && !hasMetroAtEnd -> "No Metro in ${start.area}/${end.area}"
            !metroSingleModeAvailable && (hasMetroAtStart || hasMetroAtEnd) ->
                "Direct Metro unavailable (${if (!hasMetroAtStart) "no Metro in ${start.area}" else "no Metro in ${end.area}"}; Metro included in Mix leg)"
            !flightAvailable -> "No Flight (<165 km)"
            else -> "All transit modes active"
        }

        val banner = "Verified modes: $activeModeNames • $missingNote"

        return CityModeAvailability(
            startMetroCity = startMetroRegion,
            endMetroCity = endMetroRegion,
            hasMetroAtStart = hasMetroAtStart,
            hasMetroAtEnd = hasMetroAtEnd,
            metroSingleModeAvailable = metroSingleModeAvailable,
            trainAvailable = trainAvailable,
            busAvailable = busAvailable,
            flightAvailable = flightAvailable,
            carAvailable = carAvailable,
            bikeAvailable = bikeAvailable,
            walkAvailable = walkAvailable,
            availableModes = availableModes,
            unavailableReasons = unavailableReasons,
            localBusOperator = localBusOp,
            metroOperator = metroOp,
            availabilitySummaryBanner = banner
        )
    }

    /**
     * Performs live API requests in parallel:
     * 1. OSRM Live Road Routing API (`router.project-osrm.org`) for real road distance, duration, and geometry.
     * 2. Overpass OpenStreetMap Infrastructure API (`overpass-api.de`) to verify subway/metro station presence.
     * 3. Firebase AI Logic (`gemini-3.8-flash`) for live transit availability & pricing verification.
     */
    suspend fun fetchLiveTransitAndPricingFromApis(
        start: DelhiPlace,
        end: DelhiPlace,
        reachBy: String? = null
    ): CityModeAvailability = withContext(Dispatchers.IO) {
        val base = evaluateInstantModeAvailability(start, end, reachBy)

        coroutineScope {
            val osrmDeferred = async { fetchLiveOsrmRoadRoute(start, end) }
            val overpassStartDeferred = async {
                if (base.startMetroCity != null) true else queryOverpassMetroNear(start.lat, start.lng)
            }
            val overpassEndDeferred = async {
                if (base.endMetroCity != null) true else queryOverpassMetroNear(end.lat, end.lng)
            }
            val geminiDeferred = async {
                fetchGeminiVerification(start, end, base)
            }

            val osrm = osrmDeferred.await()
            val hasStartMetro = overpassStartDeferred.await()
            val hasEndMetro = overpassEndDeferred.await()
            val gemini = geminiDeferred.await()

            val updatedBase = evaluateInstantModeAvailability(start, end, reachBy)
            val geminiNote = gemini?.let {
                "${it.primaryOperatorSummary} • Est. Local ₹${it.localFareInr} vs Ola/Uber ₹${it.olaUberMiniInr}–₹${it.olaUberSedanInr}"
            }

            updatedBase.copy(
                hasMetroAtStart = updatedBase.hasMetroAtStart || hasStartMetro,
                hasMetroAtEnd = updatedBase.hasMetroAtEnd || hasEndMetro,
                liveRoadDistanceKm = osrm?.distanceKm,
                liveRoadDurationSeconds = osrm?.durationSeconds,
                liveRoadPolyline = osrm?.polyline,
                geminiPricingNote = geminiNote,
                availabilitySummaryBanner = if (!geminiNote.isNullOrBlank()) {
                    "${updatedBase.availabilitySummaryBanner} • $geminiNote"
                } else {
                    updatedBase.availabilitySummaryBanner
                }
            )
        }
    }

    fun fetchLiveOsrmRoadRoute(
        start: DelhiPlace,
        end: DelhiPlace,
        profile: String = "driving"
    ): LiveOsrmRouteResult? {
        if (DelhiPlacesData.haversineMeters(start.lat, start.lng, end.lat, end.lng) < 35.0) {
            return null
        }
        val safeProfile = when (profile) {
            "bike", "cycling" -> "bike"
            "foot", "walking" -> "foot"
            else -> "driving"
        }
        val osmDeService = when (safeProfile) {
            "bike" -> "routed-bike"
            "foot" -> "routed-foot"
            else -> "routed-car"
        }
        val coordKey = "${(start.lat * 10000).roundToInt()}_${(start.lng * 10000).roundToInt()}_${(end.lat * 10000).roundToInt()}_${(end.lng * 10000).roundToInt()}_$safeProfile"
        osrmCache.get(coordKey)?.let { return it }

        val candidateUrls = listOf(
            "https://routing.openstreetmap.de/$osmDeService/route/v1/driving/${start.lng},${start.lat};${end.lng},${end.lat}?overview=full&geometries=geojson",
            "https://router.project-osrm.org/route/v1/$safeProfile/${start.lng},${start.lat};${end.lng},${end.lat}?overview=full&geometries=geojson"
        )

        for (urlStr in candidateUrls) {
            val parsed = runCatching {
                val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = 2400
                    readTimeout = 2600
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14; CommuteBuddy/3.0)")
                    setRequestProperty("Accept", "application/json")
                }
                if (conn.responseCode != 200) return@runCatching null
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(body)
                val routes = root.optJSONArray("routes") ?: return@runCatching null
                val first = routes.optJSONObject(0) ?: return@runCatching null

                val rawDistMeters = first.optDouble("distance", 0.0)
                val rawDurSec = first.optDouble("duration", 0.0).roundToInt()
                if (rawDistMeters <= 0.0 || rawDurSec <= 0) return@runCatching null

                val straightMeters = DelhiPlacesData.haversineMeters(start.lat, start.lng, end.lat, end.lng)
                // Guard against OSRM snapping two off-road Indian landmarks to the same highway node
                val distMeters = if (straightMeters > 120.0 && rawDistMeters < straightMeters * 1.05) {
                    straightMeters * 1.26
                } else {
                    rawDistMeters
                }
                val durSec = if (distMeters > rawDistMeters) {
                    max(rawDurSec, (distMeters / 8.5).roundToInt())
                } else {
                    rawDurSec
                }

                val coordsArr = first.optJSONObject("geometry")?.optJSONArray("coordinates")
                val pts = ArrayList<Pair<Double, Double>>()
                if (coordsArr != null && coordsArr.length() >= 2) {
                    val step = max(1, coordsArr.length() / 80)
                    var i = 0
                    while (i < coordsArr.length()) {
                        val pair = coordsArr.optJSONArray(i)
                        if (pair != null && pair.length() >= 2) {
                            val lng = pair.optDouble(0, Double.NaN)
                            val lat = pair.optDouble(1, Double.NaN)
                            if (!lat.isNaN() && !lng.isNaN()) {
                                pts.add(Pair(lat, lng))
                            }
                        }
                        i += step
                    }
                    val lastPair = coordsArr.optJSONArray(coordsArr.length() - 1)
                    if (lastPair != null && lastPair.length() >= 2) {
                        val lastLat = lastPair.optDouble(1, end.lat)
                        val lastLng = lastPair.optDouble(0, end.lng)
                        pts.add(Pair(lastLat, lastLng))
                    }
                }

                LiveOsrmRouteResult(
                    distanceKm = (((distMeters / 1000.0) * 10.0).roundToInt() / 10.0).coerceAtLeast(0.1),
                    durationSeconds = durSec,
                    polyline = if (pts.size >= 2) pts else listOf(Pair(start.lat, start.lng), Pair(end.lat, end.lng))
                )
            }.getOrNull()

            if (parsed != null) {
                osrmCache.put(coordKey, parsed)
                return parsed
            }
        }
        return null
    }

    private fun queryOverpassMetroNear(lat: Double, lng: Double): Boolean {
        val key = "${(lat * 100).roundToInt()}_${(lng * 100).roundToInt()}"
        overpassMetroCache.get(key)?.let { return it }

        return runCatching {
            val query = "[out:json][timeout:2];node[\"station\"=\"subway\"](around:12000,$lat,$lng);out count;"
            val encoded = URLEncoder.encode(query, "UTF-8")
            val conn = (URL("https://overpass-api.de/api/interpreter?data=$encoded").openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1400
                readTimeout = 1400
                setRequestProperty("User-Agent", "CommuteBuddy/1.0")
            }
            if (conn.responseCode != 200) return false
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            val hasSubway = body.contains("\"total\"") && !body.contains("\"total\": \"0\"") && !body.contains("\"total\":\"0\"")
            overpassMetroCache.put(key, hasSubway)
            hasSubway
        }.getOrDefault(false)
    }

    suspend fun fetchGeminiVerification(
        start: DelhiPlace,
        end: DelhiPlace,
        instantAvailability: CityModeAvailability
    ): GeminiTransitVerification? {
        val cacheKey = "${start.id}_${end.id}"
        geminiVerificationCache.get(cacheKey)?.let { return it }

        val distKm = ((DelhiPlacesData.haversineMeters(start.lat, start.lng, end.lat, end.lng) * 1.26) / 1000.0).roundToInt()
        val prompt = """
            Verify real-world Indian public transport availability and current fares in INR for:
            Origin: ${start.name} (${start.area}, lat=${start.lat}, lng=${start.lng})
            Destination: ${end.name} (${end.area}, lat=${end.lat}, lng=${end.lng})
            Approximate road distance: $distKm km
            Pre-checked Metro at Origin: ${instantAvailability.hasMetroAtStart} (${instantAvailability.startMetroCity ?: "None"})
            Pre-checked Metro at Destination: ${instantAvailability.hasMetroAtEnd} (${instantAvailability.endMetroCity ?: "None"})
            Pre-checked Local Bus Operator: ${instantAvailability.localBusOperator}
            Rules:
            - Do NOT say Metro is available in cities that lack an operational Metro (such as Mathura, Vrindavan, Chandigarh, Varanasi, Amritsar, Patna).
            - Provide realistic Indian Railways, State/City Bus, Metro (only where operational), Auto, and Ola/Uber Mini & Sedan fares in INR.
            - Never use emojis.
        """.trimIndent()

        return try {
            val response = withTimeout(12_000L) {
                geminiModel.generateContent(prompt)
            }
            val rawJson = response.text ?: return null
            val json = JSONObject(rawJson)
            val verified = GeminiTransitVerification(
                metroAvailableInOrigin = json.optBoolean("metroAvailableInOrigin", instantAvailability.hasMetroAtStart),
                metroAvailableInDestination = json.optBoolean("metroAvailableInDestination", instantAvailability.hasMetroAtEnd),
                directMetroPossible = json.optBoolean("directMetroPossible", instantAvailability.metroSingleModeAvailable),
                trainAvailable = json.optBoolean("trainAvailable", instantAvailability.trainAvailable),
                flightAvailable = json.optBoolean("flightAvailable", instantAvailability.flightAvailable),
                primaryOperatorSummary = json.optString("primaryOperatorSummary", instantAvailability.localBusOperator),
                recommendedChainSummary = json.optString("recommendedChainSummary", ""),
                localFareInr = json.optInt("localFareInr", 60).coerceAtLeast(10),
                olaUberMiniInr = json.optInt("olaUberMiniInr", 180).coerceAtLeast(60),
                olaUberSedanInr = json.optInt("olaUberSedanInr", 240).coerceAtLeast(80)
            )
            geminiVerificationCache.put(cacheKey, verified)
            verified
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            runCatching { Log.d(TAG, "Gemini transit verification skipped/fallback: ${e.message}") }
            null
        }
    }

    private fun parseHour24(raw: String?): Int? {
        if (raw.isNullOrBlank()) return null
        val cleaned = raw.trim().uppercase()
        val isPm = cleaned.contains("PM")
        val isAm = cleaned.contains("AM")
        val h = cleaned.replace("AM", "").replace("PM", "").trim().substringBefore(":").trim().toIntOrNull()
            ?: return null
        return when {
            isPm && h < 12 -> h + 12
            isAm && h == 12 -> 0
            else -> h
        }
    }
}
