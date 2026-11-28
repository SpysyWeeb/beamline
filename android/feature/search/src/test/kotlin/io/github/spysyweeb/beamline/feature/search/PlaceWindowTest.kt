package io.github.spysyweeb.beamline.feature.search

import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

/** F42 (0.1.40, P17): the place window's height key. Made-up places at public points in Erie. */
class PlaceWindowTest {
    private val p = GeoPoint(42.13646, -80.08718)
    private val pin = Place("Dropped pin", p, PlaceSource.MAP_PIN, subtitle = "42.13646, -80.08718")

    @Test
    fun theWindowsKeyIgnoresItsLineAndItsSavedId() {
        // the street arriving, and a save, keep the window's height
        assertEquals(placeSheetKey(pin), placeSheetKey(pin.copy(subtitle = "Near East 5th Street, Erie")))
        assertEquals(placeSheetKey(pin), placeSheetKey(pin.copy(subtitle = "Near East 5th Street, Erie", savedId = "s0000000000000001")))
        // another point, source or name is another window (it opens at peek, item 47)
        assertNotEquals(placeSheetKey(pin), placeSheetKey(pin.copy(point = GeoPoint(42.0971, -80.07578))))
        assertNotEquals(placeSheetKey(pin), placeSheetKey(pin.copy(source = PlaceSource.RECENT)))
        assertNotEquals(placeSheetKey(pin), placeSheetKey(pin.copy(name = "Home")))
        val museum = Place("Erie Maritime Museum", p, PlaceSource.SEARCH, "100 East Front Street, Erie", "museum", 11, "us-pa")
        assertNotEquals(placeSheetKey(museum), placeSheetKey(museum.copy(searchId = 12)))
        assertNotEquals(placeSheetKey(museum), placeSheetKey(museum.copy(packId = "us-ny")))
    }
}
