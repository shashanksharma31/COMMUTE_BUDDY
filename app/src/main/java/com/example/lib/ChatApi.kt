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
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

private const val TAG = "CommuteChatAi"

data class ChatTripContext(
    val start: DelhiPlace?,
    val end: DelhiPlace?,
    val vehicle: TravelMode,
    val selectedRoute: Route?,
    val reachByTime: String? = null
)

data class ChatMessage(
    val id: String,
    val text: String,
    val isUser: Boolean
)

private object CommuteAiClient {
    private val explanationSchema = Schema.obj(
        properties = mapOf(
            "summary" to Schema.string(
                description = "Concise, accurate transit guidance in plain text without emojis, citing verified mode availability, duration in hr/min, local fares, and Ola/Uber fares"
            ),
            "confidenceNote" to Schema.string(
                description = "Brief note on mode availability and schedule/fare verification status without emojis"
            )
        ),
        optionalProperties = listOf("confidenceNote")
    )

    private val structuredConfig = generationConfig {
        responseMimeType = "application/json"
        responseSchema = explanationSchema
        temperature = 0.2f
    }

    private val streamingConfig = generationConfig {
        temperature = 0.2f
    }

    val structuredModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = structuredConfig
        )
    }

    val streamingModel by lazy {
        Firebase.ai.generativeModel(
            modelName = "gemini-3.8-flash",
            generationConfig = streamingConfig
        )
    }
}

/**
 * Extracts origin and destination places from a free-form user chat question
 * (e.g., "from Mathura to Prateek Grand City" or "Mathura to Vrindavan") when the user
 * asks directly in "Ask about your trip".
 */
fun extractPlacesFromQuestion(message: String): Pair<DelhiPlace?, DelhiPlace?> {
    val normalized = message.trim()
    val toRegex = Regex("(?i)(?:from\\s+)?([a-zA-Z0-9 ,.-]{3,35}?)\\s+(?:to|->|towards)\\s+([a-zA-Z0-9 ,.-]{3,35})")
    val match = toRegex.find(normalized)
    if (match != null) {
        val rawOrigin = match.groupValues[1].trim()
        val rawDest = match.groupValues[2]
            .replace(Regex("(?i)\\b(what|which|how|price|fare|cost|mode|available|by|in|and)\\b.*"), "")
            .trim()
        val startMatch = DelhiPlacesData.searchPlaces(rawOrigin, maxResults = 1).firstOrNull()
        val endMatch = DelhiPlacesData.searchPlaces(rawDest, maxResults = 1).firstOrNull()
        if (startMatch != null && endMatch != null && startMatch.id != endMatch.id) {
            return Pair(startMatch, endMatch)
        }
    }

    // Check if any known place names appear in the message
    val lower = normalized.lowercase()
    val matched = DelhiPlacesData.places.filter { place ->
        lower.contains(place.name.lowercase()) ||
            (place.name.length > 6 && lower.contains(place.name.substringBefore(",").lowercase()))
    }.distinctBy { it.id }

    return when {
        matched.size >= 2 -> Pair(matched[0], matched[1])
        matched.size == 1 -> Pair(matched[0], null)
        else -> Pair(null, null)
    }
}

private suspend fun resolveEffectiveContext(
    message: String,
    context: ChatTripContext
): Triple<ChatTripContext, CityModeAvailability?, List<Route>> {
    var effStart = context.start
    var effEnd = context.end
    if (effStart == null || effEnd == null) {
        val (parsedStart, parsedEnd) = extractPlacesFromQuestion(message)
        if (effStart == null && parsedStart != null) effStart = parsedStart
        if (effEnd == null && parsedEnd != null) effEnd = parsedEnd
    }

    val availability = if (effStart != null && effEnd != null) {
        TransitAvailabilityApi.evaluateInstantModeAvailability(effStart, effEnd, context.reachByTime)
    } else null

    val computedRoutes = if (effStart != null && effEnd != null) {
        if (context.selectedRoute != null) {
            listOf(context.selectedRoute)
        } else {
            runCatching {
                planRoutesOnDevice(
                    start = effStart,
                    end = effEnd,
                    vehicle = context.vehicle,
                    reachBy = context.reachByTime,
                    preloadedAvailability = availability,
                    allowNetworkCalls = false
                )
            }.getOrDefault(emptyList())
        }
    } else emptyList()

    val effectiveRoute = context.selectedRoute ?: Recommend.recommendRoute(computedRoutes) ?: computedRoutes.firstOrNull()
    return Triple(
        context.copy(start = effStart, end = effEnd, selectedRoute = effectiveRoute),
        availability,
        computedRoutes
    )
}

private fun buildGeminiPrompt(
    message: String,
    effectiveContext: ChatTripContext,
    availability: CityModeAvailability?,
    allRoutes: List<Route>
): String {
    val routeContextText = buildString {
        if (effectiveContext.start != null) {
            appendLine("Origin: ${effectiveContext.start.name} (${effectiveContext.start.area})")
        }
        if (effectiveContext.end != null) {
            appendLine("Destination: ${effectiveContext.end.name} (${effectiveContext.end.area})")
        }
        appendLine("Selected Mode: ${effectiveContext.vehicle.label}")
        if (!effectiveContext.reachByTime.isNullOrBlank()) {
            appendLine("Target Reach-By Time: ${effectiveContext.reachByTime}")
        }
        if (availability != null) {
            appendLine("Verified Available Modes: ${availability.availableModes.joinToString(", ") { it.label }}")
            if (availability.unavailableReasons.isNotEmpty()) {
                appendLine(
                    "Unavailable Modes & Reasons: " +
                        availability.unavailableReasons.entries.joinToString("; ") { "${it.key.label}: ${it.value}" }
                )
            }
            appendLine("Origin Metro Network: ${availability.startMetroCity ?: "None (No Metro in this city)"}")
            appendLine("Destination Metro Network: ${availability.endMetroCity ?: "None (No Metro in this city)"}")
            appendLine("Local Bus Operator: ${availability.localBusOperator}")
        }
        val route = effectiveContext.selectedRoute
        if (route != null) {
            appendLine("Recommended / Selected Route Label: ${route.label.display}")
            appendLine("Duration: ${CommuteFormatters.formatDuration(route.durationSeconds)} (${route.durationSeconds}s)")
            appendLine("Fare: ${route.costDisplay}")
            appendLine("Local Fare Breakdown: ${route.localFareDisplay}")
            appendLine("Ola/Uber Comparison: ${route.olaUberFareDisplay}")
            appendLine("Live Traffic & Availability: ${route.trafficAnalysis}")
            appendLine("Distance: ${route.distanceKm} km, Transfers: ${route.transfers}, Walk: ${route.walkMeters} m")
            appendLine("Steps:")
            route.steps.forEachIndexed { idx, step ->
                appendLine("  ${idx + 1}. [${step.mode.label}] ${step.text} (${CommuteFormatters.formatDuration(step.durationSeconds)})")
            }
            if (allRoutes.size > 1) {
                val fastest = allRoutes.minByOrNull { it.durationSeconds }
                val cheapest = allRoutes.minByOrNull { it.costInr }
                if (fastest != null) {
                    appendLine("Fastest Option: ${CommuteFormatters.formatDuration(fastest.durationSeconds)} at ${fastest.costDisplay}")
                }
                if (cheapest != null) {
                    appendLine("Cheapest Option: ${cheapest.costDisplay} in ${CommuteFormatters.formatDuration(cheapest.durationSeconds)}")
                }
            }
        } else {
            appendLine("No specific route selected yet; provide Pan-India transit guidance (Indian Railways, Metro only in cities that have an operational Metro, State/City Bus, Flights for >=165 km, and Local vs Ola/Uber rates).")
        }
    }

    return """
        You are the CommuteBuddy Gemini AI transit assistant for Pan-India and Delhi-NCR transit networks.
        STRICT RULES:
        1. Do NOT use any emojis anywhere in your response.
        2. NEVER claim Metro is available in a city that lack an operational Metro (such as Mathura, Vrindavan, Chandigarh, Varanasi, Amritsar, Patna). Explicitly state which modes are available and unavailable for the user's cities.
        3. Cite the exact duration (in hr/min), local public transit fare vs current Ola/Uber fare, transfer count, and station/platform names from the context.
        4. Keep answers concise (2 to 4 sentences), practical, and modeled after Google Maps transit advisories.

        TRIP_CONTEXT:
        $routeContextText

        USER_QUESTION:
        $message
    """.trimIndent()
}

/**
 * Streams a live response from Firebase AI Logic (`gemini-3.8-flash`) into `onPartialUpdate`
 * for low-latency conversational UI in `ChatBar`, falling back immediately to the verified
 * transit advisor if offline or if App Check attestation is unavailable.
 */
suspend fun streamChatMessage(
    message: String,
    context: ChatTripContext,
    onPartialUpdate: (String) -> Unit
): String = withContext(Dispatchers.IO) {
    val (effectiveContext, availability, allRoutes) = resolveEffectiveContext(message, context)
    val prompt = buildGeminiPrompt(message, effectiveContext, availability, allRoutes)

    val accumulated = StringBuilder()
    try {
        withTimeout(12_000L) {
            CommuteAiClient.streamingModel.generateContentStream(prompt).collect { chunk ->
                val part = chunk.text.orEmpty()
                if (part.isNotEmpty()) {
                    accumulated.append(part)
                    val clean = accumulated.toString().trim()
                    if (clean.isNotEmpty()) {
                        withContext(Dispatchers.Main) {
                            onPartialUpdate(clean)
                        }
                    }
                }
            }
        }
        val finalStreamed = accumulated.toString().trim()
        if (finalStreamed.isNotEmpty()) {
            return@withContext finalStreamed
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        runCatching { Log.w(TAG, "Gemini stream fallback to verified transit advisor: ${e.message}") }
    }

    val fallbackReply = buildDeterministicTransitReply(message, effectiveContext)
    withContext(Dispatchers.Main) {
        onPartialUpdate(fallbackReply)
    }
    fallbackReply
}

/**
 * Generates transit and route explanations using Firebase AI Logic (`gemini-3.8-flash`)
 * with structured JSON output (`Schema.obj`) and App Check attestation, plus a deterministic
 * context-aware transit advisor fallback if offline or if App Check attestation is pending.
 */
suspend fun sendMessage(
    message: String,
    context: ChatTripContext
): String = withContext(Dispatchers.IO) {
    val (effectiveContext, availability, allRoutes) = resolveEffectiveContext(message, context)
    val prompt = buildGeminiPrompt(message, effectiveContext, availability, allRoutes)

    try {
        val response = withTimeout(45_000L) {
            CommuteAiClient.structuredModel.generateContent(prompt)
        }
        val rawJson = response.text
        if (!rawJson.isNullOrBlank()) {
            val parsed = JSONObject(rawJson)
            val summary = parsed.optString("summary", "").trim()
            val confidenceNote = parsed.optString("confidenceNote", "").trim()
            if (summary.isNotEmpty()) {
                return@withContext if (confidenceNote.isNotEmpty()) {
                    "$summary $confidenceNote"
                } else {
                    summary
                }
            }
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        runCatching { Log.w(TAG, "Firebase AI call fell back to verified transit advisor: ${e.message}") }
    }

    buildDeterministicTransitReply(message, effectiveContext)
}

internal fun buildDeterministicTransitReply(
    message: String,
    context: ChatTripContext
): String {
    val q = message.lowercase()
    val startPlace = context.start
    val endPlace = context.end
    val startName = startPlace?.name ?: "your origin"
    val endName = endPlace?.name ?: "your destination"
    val route = context.selectedRoute

    val availability = if (startPlace != null && endPlace != null) {
        TransitAvailabilityApi.evaluateInstantModeAvailability(startPlace, endPlace, context.reachByTime)
    } else null

    if (route == null) {
        val (parsedStart, parsedEnd) = extractPlacesFromQuestion(message)
        if (parsedStart != null) {
            val metroRegion = TransitAvailabilityApi.resolveMetroNetworkRegion(parsedStart)
            val metroNote = if (metroRegion != null) {
                "$metroRegion is operational in ${parsedStart.area}"
            } else {
                "Metro is not available in ${parsedStart.area} (use Indian Railways, ${TransitAvailabilityApi.resolveLocalBusOperator(parsedStart, parsedEnd ?: parsedStart, false)}, Auto, or Ola/Uber Cabs)"
            }
            return "For ${parsedStart.name}${if (parsedEnd != null) " to ${parsedEnd.name}" else ""}: $metroNote. Select both origin and destination in the search bar to view live verified routes, durations in hr/min, and Local vs Ola/Uber fare comparisons."
        }
        return "Select a start and destination anywhere in India (for example, Mathura Junction to Prateek Grand City, Ghaziabad or Mathura to Vrindavan) to verify city transport availability, Indian Railways platforms, live traffic, and Local vs Ola/Uber fares."
    }

    val durationStr = CommuteFormatters.formatDuration(route.durationSeconds)
    val chainStr = route.steps.joinToString(" -> ") { it.mode.label }
    val leaveBy = CommuteFormatters.formatLeaveBy(context.reachByTime, route.minutes)
    val availNote = availability?.let {
        val active = it.availableModes.joinToString(", ") { m -> m.label }
        val unavail = if (!it.metroSingleModeAvailable) {
            " (${it.unavailableReasons[TravelMode.METRO] ?: "Direct Metro unavailable"})"
        } else ""
        "Verified modes: $active$unavail."
    } ?: ""

    return when {
        q.contains("available") || q.contains("mode") || q.contains("metro") -> {
            "$availNote Recommended ${context.vehicle.label} route from $startName to $endName takes $durationStr via $chainStr. ${route.localFareDisplay} (${route.olaUberFareDisplay}). ${route.trafficAnalysis}."
        }
        q.contains("price") || q.contains("cost") || q.contains("fare") || q.contains("ola") || q.contains("uber") || q.contains("cheap") -> {
            "For $startName to $endName ($durationStr), ${route.localFareDisplay} while ${route.olaUberFareDisplay}. The multi-modal ${route.label.display} option ($chainStr) balances both travel time and total cost. $availNote"
        }
        q.contains("miss") || q.contains("next") || q.contains("frequency") -> {
            val nextFreq = if (availability?.metroSingleModeAvailable == true) {
                "Metro trains run every 3 to 5 minutes and feeder autos/cabs are available outside the exit gate"
            } else {
                "${availability?.localBusOperator ?: "State/City buses"} run every 10 to 15 minutes and shared autos/Ola-Uber cabs are available outside the stand"
            }
            "If you miss the current connection on the $durationStr ${route.label.display} route ($chainStr), $nextFreq. ${route.localFareDisplay}. ${route.olaUberFareDisplay}."
        }
        q.contains("safe") || q.contains("night") -> {
            "This $durationStr route from $startName to $endName uses main transit corridors with monitored stations and verified stops. Late at night, prefer pre-booked Ola/Uber cabs (${route.olaUberFareDisplay}) from the pickup zone instead of walking."
        }
        q.contains("leave") || q.contains("time") || q.contains("when") -> {
            val leaveNote = leaveBy ?: "Depart now to allow a 10 min platform transfer buffer"
            "$leaveNote for your $durationStr trip from $startName to $endName via $chainStr. ${route.localFareDisplay} (${route.olaUberFareDisplay}). ${route.trafficAnalysis}."
        }
        else -> {
            "Recommended ${context.vehicle.label} route from $startName to $endName takes $durationStr (${route.distanceKm} km) via $chainStr with ${route.transfers} transfers. ${route.localFareDisplay} (${route.olaUberFareDisplay}). $availNote"
        }
    }
}
