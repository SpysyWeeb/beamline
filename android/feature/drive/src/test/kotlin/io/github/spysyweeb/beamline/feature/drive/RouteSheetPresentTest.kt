package io.github.spysyweeb.beamline.feature.drive

import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.model.SavedKind
import io.github.spysyweeb.beamline.api.model.SavedPlace
import io.github.spysyweeb.beamline.api.nav.Origin
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.ProblemKind
import io.github.spysyweeb.beamline.api.nav.RouteOptions
import io.github.spysyweeb.beamline.api.nav.RouteTypeInfo
import io.github.spysyweeb.beamline.api.nav.TripProblem
import io.github.spysyweeb.beamline.api.nav.TripState
import io.github.spysyweeb.beamline.api.nav.TripStop
import io.github.spysyweeb.beamline.feature.drive.Exit24.choice
import io.github.spysyweeb.beamline.feature.drive.Exit24.choosing
import io.github.spysyweeb.beamline.feature.drive.Exit24.downsDrive
import io.github.spysyweeb.beamline.feature.drive.Exit24.fastest
import io.github.spysyweeb.beamline.feature.drive.Exit24.fastestOnly
import io.github.spysyweeb.beamline.feature.drive.Exit24.present
import io.github.spysyweeb.beamline.feature.drive.Exit24.sheetz
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Rules D4, D5, C1, C6, the route sheet and the stops editor; the saved-Home test of docs/architecture-saved-places.md 5.7. */
class RouteSheetPresentTest {

    private val simpler = choice(2, listOf("simpler"), lengthM = 5200.0, timeS = 420.0, hasTolls = true, hasHighways = false, via = listOf("US 20"))

    private fun sheet(trip: TripState, locationAllowed: Boolean = true) = present(trip, locationAllowed = locationAllowed).planning!!

    @Test
    fun `route cards in choice order with merged labels and every text`() {
        val s = sheet(choosing(choices = listOf(fastestOnly, simpler), selected = 1))
        assertEquals(
            listOf(
                RouteCardUi(1, "Fastest", "6 min", "3.3 mi", "Arrive 3:36 PM", "via I 90, US 19", listOf("Highways"), selected = true),
                RouteCardUi(2, "Simpler", "7 min", "3.2 mi", "Arrive 3:37 PM", "via US 20", listOf("Tolls"), selected = false),
            ),
            s.cards,
        )
        val both = sheet(choosing(choices = listOf(choice(3, listOf("fastest"), hasTolls = true, hasHighways = true, via = emptyList()))))
        assertEquals(listOf("Tolls", "Highways"), both.cards.single().notes)
        assertNull(both.cards.single().viaText)
        val none = sheet(choosing(choices = listOf(choice(4, listOf("fastest"), hasHighways = false))))
        assertEquals(emptyList<String>(), none.cards.single().notes)
    }

    @Test
    fun `labels follow the offered types' order in the choice and their labels`() {
        val s = sheet(choosing(choices = listOf(choice(5, listOf("fastest", "simpler")))))
        assertEquals("Fastest · Simpler", s.cards.single().labels)
    }

    @Test
    fun `Eco is never shown (D5)`() {
        // presets.json offers Fastest and Simpler (Shortest left in batch D); Eco is offered = false and so not in offeredTypes
        val withEco = choice(6, listOf("fastest", "eco"))
        val onlyEco = choice(7, listOf("eco"))
        val s = sheet(choosing(choices = listOf(withEco, onlyEco, simpler), selected = 6))
        assertEquals(listOf("Fastest", "Simpler"), s.cards.map { it.labels })
        assertTrue(s.cards.none { it.labels.contains("Eco", ignoreCase = true) })
        // even if the session listed it among the offered types under another label, an id not offered never shows
        val offeredNoEco = listOf(RouteTypeInfo("fastest", "Fastest"))
        assertEquals(listOf("Fastest"), sheet(choosing(choices = listOf(withEco, onlyEco), offeredTypes = offeredNoEco)).cards.map { it.labels })
    }

    @Test
    fun `Start is blocked without precise location, with the reason, Simulate stays possible`() {
        val trip = choosing()
        val allowed = sheet(trip, locationAllowed = true)
        assertTrue(allowed.canStart)
        assertNull(allowed.startBlockedText)
        val blocked = sheet(trip, locationAllowed = false)
        assertFalse(blocked.canStart)
        assertEquals("Allow precise location to start guidance", blocked.startBlockedText)
        // a route is still chosen and nothing is busy: the overflow menu's "Simulate drive" can start it
        assertTrue(blocked.cards.single().selected)
        assertFalse(blocked.busy)
    }

    @Test
    fun `Start needs a chosen route, CHOOSING and nothing replanning (D4)`() {
        assertFalse(sheet(choosing(selected = null)).canStart)
        assertNull(sheet(choosing(selected = null), locationAllowed = false).startBlockedText) // no route chosen: not off for that
        assertFalse(sheet(choosing(phase = Phase.PLANNING)).canStart)
        assertFalse(sheet(choosing(replanning = true)).canStart)
        assertFalse(sheet(choosing(selected = 99)).canStart) // not one of the cards
    }

    @Test
    fun `busy while planning and while replanning (D7)`() {
        assertTrue(sheet(choosing(phase = Phase.PLANNING, choices = emptyList())).busy)
        assertTrue(sheet(choosing(replanning = true)).busy)
        assertFalse(sheet(choosing()).busy)
        assertTrue(present(choosing(replanning = true), stopsEditorOpen = true).stopsEditor!!.busy)
    }

    @Test
    fun `problem, retry and the chosen route's warnings`() {
        val failed = sheet(choosing(choices = emptyList(), problem = TripProblem(ProblemKind.NO_ROUTE, "No route found.", canRetry = true)))
        assertEquals("No route found.", failed.problem)
        assertTrue(failed.canRetry)
        assertTrue(failed.cards.isEmpty())
        assertFalse(failed.canStart)
        val warn = "The route reaches stop 2 0.9 mi from the point you chose."
        val s = sheet(choosing(choices = listOf(choice(1, listOf("fastest"), warnings = listOf(warn)), simpler), selected = 1))
        assertEquals(listOf(warn), s.warnings)
        assertEquals(emptyList<String>(), sheet(choosing(choices = listOf(choice(1, listOf("fastest"), warnings = listOf(warn)), simpler), selected = 2)).warnings)
        assertNull(s.problem)
        assertFalse(s.canRetry)
    }

    @Test
    fun `the two option toggles follow the trip`() {
        val s = sheet(choosing(options = RouteOptions(avoidHighways = true, avoidTolls = false)))
        assertTrue(s.avoidHighways)
        assertFalse(s.avoidTolls)
        assertTrue(sheet(choosing(options = RouteOptions(avoidTolls = true))).avoidTolls)
    }

    @Test
    fun `the start row - your position or the chosen start`() {
        assertEquals("Your position", sheet(choosing()).fromText)
        assertEquals("Downs Drive", sheet(choosing(origin = Origin.FromPlace(downsDrive, 90.0))).fromText)
    }

    @Test
    fun `stop rows - move, remove, reached (C1)`() {
        val stops = listOf(TripStop(sheetz), TripStop(Place("Erie Zoo", GeoPoint(42.1489, -80.0869), PlaceSource.SEARCH)), TripStop(downsDrive))
        val rows = sheet(choosing(stops = stops)).stops
        assertEquals(listOf("Sheetz", "Erie Zoo", "Downs Drive"), rows.map { it.title })
        assertEquals(listOf(0, 1, 2), rows.map { it.index })
        assertEquals(listOf(false, true, true), rows.map { it.canMoveUp })
        assertEquals(listOf(true, true, false), rows.map { it.canMoveDown })
        assertEquals(listOf(true, true, true), rows.map { it.canRemove })
        assertEquals("Peach Street, Erie", rows[0].subtitle)
        // the only stop: no move, no remove (End cancels the trip)
        val one = sheet(choosing()).stops.single()
        assertFalse(one.canRemove || one.canMoveUp || one.canMoveDown)
        // guided, first stop reached: it cannot change, the next cannot move above it
        val guided = present(Exit24.guiding(stops = stops.mapIndexed { i, s -> if (i == 0) s.copy(reached = true) else s }), stopsEditorOpen = true).stopsEditor!!
        assertEquals(listOf(true, false, false), guided.rows.map { it.reached })
        assertEquals(listOf(false, false, true), guided.rows.map { it.canMoveUp })
        assertEquals(listOf(false, true, false), guided.rows.map { it.canMoveDown })
        assertEquals(listOf(false, true, true), guided.rows.map { it.canRemove })
    }

    @Test
    fun `best order is offered with at least three stops not reached (C6)`() {
        val zoo = TripStop(Place("Erie Zoo", GeoPoint(42.1489, -80.0869), PlaceSource.SEARCH))
        val museum = TripStop(Place("Maritime Museum", GeoPoint(42.1365, -80.0912), PlaceSource.SEARCH))
        assertFalse(present(choosing(stops = listOf(zoo, TripStop(downsDrive))), stopsEditorOpen = true).stopsEditor!!.canOptimize)
        assertTrue(present(choosing(stops = listOf(zoo, museum, TripStop(downsDrive))), stopsEditorOpen = true).stopsEditor!!.canOptimize)
        val guided = Exit24.guiding(stops = listOf(TripStop(sheetz, reached = true), zoo, museum, TripStop(downsDrive)))
        assertTrue(present(guided, stopsEditorOpen = true).stopsEditor!!.canOptimize)
        val guidedTwoLeft = Exit24.guiding(stops = listOf(TripStop(sheetz, reached = true), zoo, TripStop(downsDrive)))
        assertFalse(present(guidedTwoLeft, stopsEditorOpen = true).stopsEditor!!.canOptimize)
    }

    @Test
    fun `the sheet only in PLANNING and CHOOSING, nothing in IDLE, the resume card in RESUME_OFFERED`() {
        assertEquals(DriveUi(), present(TripState()))
        assertEquals(DriveUi(), present(TripState(), stopsEditorOpen = true))
        val planning = present(choosing(phase = Phase.PLANNING, choices = emptyList()))
        assertNotNull(planning.planning)
        assertNull(planning.guidance)
        assertNull(planning.stopsEditor)
        val resume = present(TripState(phase = Phase.RESUME_OFFERED, stops = listOf(TripStop(sheetz, reached = true), TripStop(Place("Erie Zoo", GeoPoint(42.1489, -80.0869), PlaceSource.SEARCH)), TripStop(downsDrive))))
        assertEquals(DriveUi(resume = ResumeUi("Downs Drive", 2)), resume)
        assertNull(present(Exit24.guiding()).planning)
    }

    @Test
    fun `a stop made from a saved Home reads Home with its description, and a Home start reads Home (saved places 5_7)`() {
        val home = SavedPlace(
            id = "s0123456789abcdef", kind = SavedKind.HOME, name = "Home", point = GeoPoint(42.1292, -80.0851),
            placeName = "Perry Square", subtitle = "Erie",
        )
        val stop = TripStop(home.toPlace())
        val rows = sheet(choosing(stops = listOf(TripStop(sheetz), stop))).stops
        assertEquals("Home", rows[1].title)
        assertEquals("Perry Square, Erie", rows[1].subtitle)
        assertEquals(home.description, rows[1].subtitle)
        assertEquals("Home", sheet(choosing(origin = Origin.FromPlace(home.toPlace()))).fromText)
        // the stops editor and the resume card use the same names
        assertEquals("Home", present(choosing(stops = listOf(stop)), stopsEditorOpen = true).stopsEditor!!.rows.single().title)
        assertEquals("Home", present(TripState(phase = Phase.RESUME_OFFERED, stops = listOf(stop))).resume!!.destinationName)
    }
}
