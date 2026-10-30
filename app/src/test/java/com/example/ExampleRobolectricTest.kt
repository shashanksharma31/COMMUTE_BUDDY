package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.data.DelhiPlacesData
import com.example.lib.TravelMode
import com.example.lib.TripStateHolder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @Test
    fun `read string from context`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val appName = context.getString(R.string.app_name)
        assertEquals("CommuteBuddy", appName)
    }

    @Test
    fun `real user journey - Mathura local trip filters out Metro and closes chat when switching modes`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val holder = TripStateHolder(context)

        val mathuraJunction = DelhiPlacesData.searchPlaces("Mathura Junction").first()
        val mathuraBusStand = DelhiPlacesData.searchPlaces("Mathura New Bus Stand").first()

        // 1. User selects Mathura Junction -> Mathura New Bus Stand
        holder.updateStart(mathuraJunction)
        holder.updateEnd(mathuraBusStand)

        assertTrue(holder.hasBothPlaces)
        assertTrue(holder.isRoutesSheetOpen)
        assertFalse(holder.isChatExpanded)

        // 2. Verify Metro and Flight are excluded for Mathura local trip
        val available = holder.availableModes
        assertTrue(TravelMode.MIX in available)
        assertTrue(TravelMode.BUS in available)
        assertTrue(TravelMode.CAR in available)
        assertTrue(TravelMode.BIKE in available)
        assertTrue(TravelMode.WALK in available)
        assertFalse("Mathura must not show Metro chip", TravelMode.METRO in available)
        assertFalse("Short local trip must not show Flight chip", TravelMode.FLIGHT in available)

        // 3. User opens "Ask about your trip"
        holder.setChatExpandedState(true)
        assertTrue(holder.isChatExpanded)

        // 4. While chat is expanded, user taps Bike chip -> chat must close automatically so Routes Sheet is visible
        holder.selectTravelMode(TravelMode.BIKE)
        assertEquals(TravelMode.BIKE, holder.selectedMode)
        assertFalse("Selecting a mode chip must close the expanded chat overlay", holder.isChatExpanded)
        assertTrue("Selecting a mode chip must open the routes sheet", holder.isRoutesSheetOpen)

        // 5. User re-opens chat and then swaps locations -> chat must close so updated routes are visible
        holder.setChatExpandedState(true)
        assertTrue(holder.isChatExpanded)
        holder.swapPlaces()
        assertFalse("Swapping places must close the expanded chat overlay", holder.isChatExpanded)
        assertTrue(holder.isRoutesSheetOpen)
        assertNotNull(holder.modeAvailability)
    }
}
