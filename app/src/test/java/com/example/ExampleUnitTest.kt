package com.example

import com.example.data.DelhiPlacesData
import com.example.lib.ChatTripContext
import com.example.lib.CommuteFormatters
import com.example.lib.DataSourceLabel
import com.example.lib.Fares
import com.example.lib.Recommend
import com.example.lib.TravelMode
import com.example.lib.buildDeterministicTransitReply
import com.example.lib.getRoutes
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExampleUnitTest {
    @Test
    fun formatDuration_and_formatEtaMinutes_convertMinsToHoursWhenRequired() {
        assertEquals("4 min", CommuteFormatters.formatDuration(240))
        assertEquals("59 min", CommuteFormatters.formatDuration(3540))
        assertEquals("1 hr", CommuteFormatters.formatDuration(3600))
        assertEquals("1 hr 11 min", CommuteFormatters.formatDuration(4260))
        assertEquals("1 d", CommuteFormatters.formatDuration(86400))
        assertEquals("1 d 2 hr", CommuteFormatters.formatDuration(93600))

        // formatEtaMinutes converts >= 60 mins to hours
        assertEquals("45 min", CommuteFormatters.formatEtaMinutes(45))
        assertEquals("1 hr 30 min", CommuteFormatters.formatEtaMinutes(90))
        assertEquals("2 hr", CommuteFormatters.formatEtaMinutes(120))
    }

    @Test
    fun formatVerifiedFareDisplay_handlesOfficialEstimatedAndUnavailableFares() {
        val official = Fares.formatVerifiedFareDisplay(totalInr = 40, isEstimate = false, status = "scheduled")
        assertEquals(40, official.costInr)
        assertEquals("₹40", official.display)
        assertFalse(official.isEstimate)

        val estimated = Fares.formatVerifiedFareDisplay(totalInr = 95, isEstimate = true, status = "estimate")
        assertEquals(95, estimated.costInr)
        assertEquals("₹95 (Estimated)", estimated.display)
        assertTrue(estimated.isEstimate)

        val unavailable = Fares.formatVerifiedFareDisplay(totalInr = null, isEstimate = false, status = "unavailable")
        assertEquals(0, unavailable.costInr)
        assertEquals("Fare unavailable", unavailable.display)
        assertFalse(unavailable.isEstimate)

        assertEquals(DataSourceLabel.LIVE, Fares.mapEnvelopeStatusToDataLabel("live"))
        assertEquals(DataSourceLabel.TIMETABLE, Fares.mapEnvelopeStatusToDataLabel("scheduled"))
        assertEquals(DataSourceLabel.ESTIMATED, Fares.mapEnvelopeStatusToDataLabel("estimate"))
        assertEquals(DataSourceLabel.STALE, Fares.mapEnvelopeStatusToDataLabel("stale"))
        assertEquals(DataSourceLabel.UNVERIFIED, Fares.mapEnvelopeStatusToDataLabel("unverified"))
        assertEquals(DataSourceLabel.UNAVAILABLE, Fares.mapEnvelopeStatusToDataLabel("unavailable"))
    }

    @Test
    fun panIndiaPlacesSearch_findsMathuraPrateekGrandCityAndSubLocations() {
        val mathuraResults = DelhiPlacesData.searchPlaces("Mathura")
        assertTrue(mathuraResults.isNotEmpty())
        assertTrue(mathuraResults.any { it.name.contains("Mathura", ignoreCase = true) })

        val prateekResults = DelhiPlacesData.searchPlaces("Prateek Grand City")
        assertTrue(prateekResults.isNotEmpty())
        assertTrue(prateekResults.first().name.contains("Prateek Grand City", ignoreCase = true))

        // Verify sub-locations: sectors, khands, colonies, temples, and tech hubs
        val indirapuramResults = DelhiPlacesData.searchPlaces("Vaibhav Khand Indirapuram")
        assertTrue(indirapuramResults.isNotEmpty())
        assertTrue(indirapuramResults.any { it.name.contains("Indirapuram", ignoreCase = true) || it.area.contains("Indirapuram", ignoreCase = true) })

        val sector62Results = DelhiPlacesData.searchPlaces("Sector 62 Noida")
        assertTrue(sector62Results.isNotEmpty())
        assertTrue(sector62Results.first().name.contains("Sector 62", ignoreCase = true))

        val dampierResults = DelhiPlacesData.searchPlaces("Dampier Nagar Mathura")
        assertTrue(dampierResults.isNotEmpty())
        assertTrue(dampierResults.first().name.contains("Dampier Nagar", ignoreCase = true))

        val vrindavanResults = DelhiPlacesData.searchPlaces("Bankey Bihari Vrindavan")
        assertTrue(vrindavanResults.isNotEmpty())
        assertTrue(vrindavanResults.first().name.contains("Vrindavan", ignoreCase = true))

        val koramangalaResults = DelhiPlacesData.searchPlaces("Koramangala")
        assertTrue(koramangalaResults.isNotEmpty())

        // Verify custom sub-location synthesis (e.g., specific tower/block inside a township)
        val customSubLoc = DelhiPlacesData.searchPlaces("Tower A4 Prateek Grand City")
        assertTrue(customSubLoc.isNotEmpty())
        assertTrue(customSubLoc.first().name.contains("Prateek Grand City", ignoreCase = true))

        // Regression test: searching "Mathura Junction Railway Station" must only return Mathura places
        // and never contaminate results with Ghaziabad, Bengaluru, or New Delhi stations
        val mathuraJnResults = DelhiPlacesData.searchPlaces("Mathura Junction Railway Station", maxResults = 5)
        assertTrue(mathuraJnResults.isNotEmpty())
        assertEquals("Mathura Junction Railway Station", mathuraJnResults.first().name)
        assertFalse(mathuraJnResults.any { it.name.contains("Ghaziabad", ignoreCase = true) || it.area.contains("Ghaziabad", ignoreCase = true) })
        assertFalse(mathuraJnResults.any { it.name.contains("Bengaluru", ignoreCase = true) || it.area.contains("Bengaluru", ignoreCase = true) })
        assertFalse(mathuraJnResults.any { it.name.contains("New Delhi", ignoreCase = true) || it.area.contains("New Delhi", ignoreCase = true) })
    }

    @Test
    fun mixMode_mathuraToPrateekGrandCity_isStrictlyMultiModalWithTrafficAndOlaUberComparison() = runTest {
        val mathura = DelhiPlacesData.searchPlaces("Mathura Junction").first()
        val prateek = DelhiPlacesData.searchPlaces("Prateek Grand City").first()

        val mixRoutes = getRoutes(mathura, prateek, TravelMode.MIX)
        assertEquals(3, mixRoutes.size)

        // Every Mix route must combine multiple modes (never a single mode of transport)
        for (route in mixRoutes) {
            val distinctModes = route.steps.map { it.mode }.distinct()
            assertTrue("Mix route ${route.id} must have >= 2 distinct transport modes", distinctModes.size >= 2)
            assertTrue("Route must include local fare breakdown", route.localFareDisplay.isNotBlank())
            assertTrue("Route must include Ola/Uber comparison", route.olaUberFareDisplay.contains("Ola/Uber", ignoreCase = true))
            assertTrue("Route must include real-life traffic analysis", route.trafficAnalysis.contains("Traffic:", ignoreCase = true))
        }

        // Verify Recommended route balances time & money and includes platform details
        val recommended = Recommend.recommendRoute(mixRoutes)
        assertNotNull(recommended)
        assertTrue(recommended!!.steps.any { it.text.contains("Platform", ignoreCase = true) })

        // Verify Car, Bus, Train, and Flight single modes also work for A -> B
        val carRoutes = getRoutes(mathura, prateek, TravelMode.CAR)
        assertTrue(carRoutes.isNotEmpty())
        val busRoutes = getRoutes(mathura, prateek, TravelMode.BUS)
        assertTrue(busRoutes.isNotEmpty())
        val trainRoutes = getRoutes(mathura, prateek, TravelMode.TRAIN)
        assertTrue(trainRoutes.isNotEmpty())
        val flightRoutes = getRoutes(mathura, prateek, TravelMode.FLIGHT)
        assertTrue(flightRoutes.isNotEmpty())

        // Verify Chat advisor formats duration in hr/min and compares Local vs Ola/Uber
        val chatReply = buildDeterministicTransitReply(
            "Compare prices and time for this route",
            ChatTripContext(
                start = mathura,
                end = prateek,
                vehicle = TravelMode.MIX,
                selectedRoute = recommended
            )
        )
        assertTrue(chatReply.contains("hr"))
        assertTrue(chatReply.contains("Ola/Uber"))
    }

    @Test
    fun cityModeAvailability_excludesMetroInNonMetroCities_and_enablesMetroInMetroCities() = runTest {
        val mathura = DelhiPlacesData.searchPlaces("Mathura Junction").first()
        val vrindavan = DelhiPlacesData.searchPlaces("Bankey Bihari Vrindavan").first()

        // 1. Mathura -> Vrindavan: Neither city has a Metro network or commercial airport
        val mathuraVrindavanAvail = com.example.lib.TransitAvailabilityApi.evaluateInstantModeAvailability(
            mathura,
            vrindavan,
            "10:00 AM"
        )
        assertFalse("Mathura must not report an operational Metro", mathuraVrindavanAvail.hasMetroAtStart)
        assertFalse("Vrindavan must not report an operational Metro", mathuraVrindavanAvail.hasMetroAtEnd)
        assertFalse("Single-mode Metro must be unavailable in Mathura -> Vrindavan", mathuraVrindavanAvail.metroSingleModeAvailable)
        assertFalse("Metro must not appear in availableModes for Mathura -> Vrindavan", TravelMode.METRO in mathuraVrindavanAvail.availableModes)
        assertFalse("Flight must not appear for 12 km local trip", TravelMode.FLIGHT in mathuraVrindavanAvail.availableModes)

        // Mix routes in Mathura -> Vrindavan must NEVER contain a Metro leg
        val localMixRoutes = getRoutes(mathura, vrindavan, TravelMode.MIX, "10:00 AM")
        assertTrue(localMixRoutes.isNotEmpty())
        for (route in localMixRoutes) {
            assertFalse(
                "Mix route in Mathura -> Vrindavan must not contain TravelMode.METRO",
                route.steps.any { it.mode == TravelMode.METRO }
            )
        }

        // Single-mode Metro query for Mathura -> Vrindavan must return emptyList
        val metroOnlyInMathura = getRoutes(mathura, vrindavan, TravelMode.METRO, "10:00 AM")
        assertTrue(metroOnlyInMathura.isEmpty())

        // 2. Connaught Place (Delhi) -> Sector 62 (Noida): Both are in Delhi-NCR Metro network
        val cp = DelhiPlacesData.searchPlaces("Connaught Place").first()
        val sec62 = DelhiPlacesData.searchPlaces("Sector 62 Noida").first()
        val ncrAvail = com.example.lib.TransitAvailabilityApi.evaluateInstantModeAvailability(cp, sec62, "10:00 AM")
        assertTrue("Delhi must report operational Metro", ncrAvail.hasMetroAtStart)
        assertTrue("Noida must report operational Metro", ncrAvail.hasMetroAtEnd)
        assertTrue("Single-mode Metro must be available inside Delhi-NCR", ncrAvail.metroSingleModeAvailable)
        assertTrue("Metro must appear in availableModes for Delhi -> Noida", TravelMode.METRO in ncrAvail.availableModes)
    }

    @Test
    fun distinctPlaceCoordinates_neverCollapseTwoDifferentPlacesToZeroDistance() = runTest {
        // 1. Mathura Junction vs Mathura New Bus Stand must have distinct coordinates (> 1 km apart)
        val mathuraJn = DelhiPlacesData.searchPlaces("Mathura Junction Railway Station").first()
        val mathuraBus = DelhiPlacesData.searchPlaces("Mathura New Bus Stand").first()
        val mathuraMeters = DelhiPlacesData.haversineMeters(mathuraJn.lat, mathuraJn.lng, mathuraBus.lat, mathuraBus.lng)
        assertTrue("Mathura Jn and Mathura New Bus Stand must be > 1000m apart (was $mathuraMeters)", mathuraMeters > 1000.0)

        // 2. Agra Cantt vs Taj Mahal must be distinct places with > 4 km separation
        val agraCantt = DelhiPlacesData.searchPlaces("Agra Cantt").first()
        val tajMahal = DelhiPlacesData.searchPlaces("Taj Mahal").first()
        val agraMeters = DelhiPlacesData.haversineMeters(agraCantt.lat, agraCantt.lng, tajMahal.lat, tajMahal.lng)
        assertTrue("Agra Cantt and Taj Mahal must be > 4000m apart (was $agraMeters)", agraMeters > 4000.0)

        // 3. Koramangala vs Whitefield in Bengaluru must be distinct places (> 10 km apart)
        val koramangala = DelhiPlacesData.searchPlaces("Koramangala").first()
        val whitefield = DelhiPlacesData.searchPlaces("Whitefield").first()
        val blrMeters = DelhiPlacesData.haversineMeters(koramangala.lat, koramangala.lng, whitefield.lat, whitefield.lng)
        assertTrue("Koramangala and Whitefield must be > 10000m apart (was $blrMeters)", blrMeters > 10000.0)

        // 4. Two custom sub-locations in the same city must never share identical 0.0m fallback coordinates
        val customA = DelhiPlacesData.searchPlaces("Radhika Vihar Colony Mathura", includeFallbackSynthesis = true).first()
        val customB = DelhiPlacesData.searchPlaces("Keshav Dev Nagar Mathura", includeFallbackSynthesis = true).first()
        val customSeparation = DelhiPlacesData.haversineMeters(customA.lat, customA.lng, customB.lat, customB.lng)
        assertTrue("Two custom sub-locations must have distinct coordinates (> 300m apart, was $customSeparation)", customSeparation > 300.0)
    }
}
