package io.github.spysyweeb.beamline.api

import io.github.spysyweeb.beamline.api.map.MapScene
import io.github.spysyweeb.beamline.api.map.MapTheme
import io.github.spysyweeb.beamline.api.map.SavedMarker
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.model.SavedKind
import io.github.spysyweeb.beamline.api.model.SavedPlace
import io.github.spysyweeb.beamline.api.nav.NavIntent
import io.github.spysyweeb.beamline.api.nav.TripState
import io.github.spysyweeb.beamline.api.nav.TripStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Saved places in the frozen model (Round SP0; docs/architecture-saved-places.md 5.1 and privacy
 * rule PV2 item 1): a saved place, a place, a trip stop and a map marker print no name, place
 * name, subtitle, point or search id, and a saved place becomes an ordinary Place when used.
 * The canary place is made up: "Quokka Shop" at Millcreek Mall, Erie.
 */
class SavedPlaceTest {

    private val point = GeoPoint(42.06875, -80.09813)
    private val quokka = SavedPlace(
        id = "s1234567890abcdef", kind = SavedKind.OTHER, name = "Quokka Shop", point = point,
        placeName = "Millcreek Mall", subtitle = "5800 Peach Street, Erie", categoryId = "mall",
    )
    private val searchId = 4_242_424_242L
    private val canaries = listOf("Quokka", "Millcreek", "Peach", "42.06", "80.09", searchId.toString())

    private fun assertNoCanary(text: String) {
        for (c in canaries) assertFalse(text.contains(c, ignoreCase = true), "\"$c\" printed by toString(): the rule PV2 is broken")
    }

    @Test
    fun `no name, place name, subtitle, point or search id in any toString`() {
        val place = quokka.toPlace()
        val searched = Place(
            name = "Quokka Shop", point = point, source = PlaceSource.SEARCH, subtitle = "Millcreek Mall, 5800 Peach Street, Erie",
            categoryId = "mall", searchId = searchId, packId = "us-pa",
        )
        val marker = SavedMarker(quokka.id, quokka.point, quokka.kind, minZoom = 12.0, scale = 1.25)
        val printed = listOf(
            quokka.toString(), place.toString(), searched.toString(), TripStop(place).toString(),
            TripStop(searched, reached = true).toString(), NavIntent.PlanTo(place).toString(), NavIntent.AddStop(searched).toString(),
            TripState(stops = listOf(TripStop(place), TripStop(searched))).toString(), marker.toString(),
            MapScene(MapTheme.DAY, saved = listOf(marker)).toString(), "$quokka $place $searched $marker",
        )
        printed.forEach(::assertNoCanary)
        assertEquals("SavedPlace(id=s1234567890abcdef, kind=OTHER)", quokka.toString())
        assertEquals("Place(source=SAVED, savedId=s1234567890abcdef)", place.toString())
        assertEquals("Place(source=SEARCH, savedId=null)", searched.toString())
        assertEquals("SavedMarker(savedId=s1234567890abcdef, kind=OTHER)", marker.toString())
    }

    @Test
    fun `description is the place name unless it is the name, then the subtitle`() {
        assertEquals("Millcreek Mall, 5800 Peach Street, Erie", quokka.description)
        assertEquals("Millcreek Mall", quokka.copy(subtitle = null).description)
        val sameName = quokka.copy(name = "Millcreek Mall")
        assertEquals("5800 Peach Street, Erie", sameName.description)
        assertEquals("5800 Peach Street, Erie", quokka.copy(name = "MILLCREEK MALL").description, "the name is compared ignoring case")
        assertNull(sameName.copy(subtitle = null).description)
        val work = SavedPlace("s8899aabbccddeeff", SavedKind.WORK, "Work", GeoPoint(42.08418, -80.17321), placeName = "42.08418, -80.17321")
        assertEquals("42.08418, -80.17321", work.description, "a saved point is described by its coordinates alone")
        val home = SavedPlace(
            "s0a1b2c3d4e5f6071", SavedKind.HOME, "Home", GeoPoint(42.13646, -80.08718),
            placeName = "Erie Maritime Museum", subtitle = "100 East Front Street, Erie", categoryId = "museum",
        )
        assertEquals("Erie Maritime Museum, 100 East Front Street, Erie", home.description)
    }

    @Test
    fun `a saved place becomes a Place with source SAVED, its id, its name and its description`() {
        val p = quokka.toPlace()
        assertEquals(PlaceSource.SAVED, p.source)
        assertEquals(quokka.id, p.savedId)
        assertEquals("Quokka Shop", p.name)
        assertEquals(quokka.description, p.subtitle)
        assertEquals(point, p.point)
        assertEquals("mall", p.categoryId)
        assertNull(p.searchId)
        assertNull(p.packId)
    }

    @Test
    fun `savedId is an ordinary field of the data class and defaults to null`() {
        val plain = Place("Dropped pin", point, PlaceSource.MAP_PIN, subtitle = "42.06875, -80.09813")
        assertNull(plain.savedId)
        assertEquals(plain, plain.copy())
        assertNotEquals(plain, plain.copy(savedId = "s0000000000000001"))
        assertEquals(quokka.toPlace(), quokka.copy().toPlace())
        assertTrue(PlaceSource.entries.last() == PlaceSource.SAVED, "SAVED was added at the end: the earlier names keep their order")
        val scene = MapScene(MapTheme.NIGHT)
        assertTrue(scene.saved.isEmpty())
        assertFalse(scene.savedBelowRoute)
    }
}
