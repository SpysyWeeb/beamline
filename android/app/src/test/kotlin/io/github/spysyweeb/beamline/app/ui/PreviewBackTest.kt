package io.github.spysyweeb.beamline.app.ui

import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.feature.search.ResultRowUi
import io.github.spysyweeb.beamline.feature.search.ResultsUi
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** Play test P46 (route-sheet.md T46): Back from a route preview planned by a window's Directions goes back to that window and its results. */
class PreviewBackTest {
    private val cafe = Place("Cafe", GeoPoint(42.12, -80.08), PlaceSource.SEARCH)
    private val bakery = Place("Bakery", GeoPoint(42.13, -80.07), PlaceSource.SEARCH)
    private val results = ResultsUi(
        "coffee",
        listOf(ResultRowUi("r0", "cafe", "Cafe", null, null, null), ResultRowUi("r1", "bakery", "Bakery", null, null, null)),
        listOf(cafe, bakery),
        selected = 0,
    )
    private val way = PreviewBack.Way(cafe, results, fromGrid = false)

    @Test
    fun `1 Back opens the window again and its results come back once the route has ended`() {
        val p = PreviewBack()
        p.planned(way)
        assertNull(p.onPhase(Phase.IDLE, Phase.PLANNING))
        assertNull(p.onPhase(Phase.PLANNING, Phase.CHOOSING))
        assertEquals(cafe, p.back())
        assertNull(p.onPhase(Phase.CHOOSING, Phase.CHOOSING))
        assertSame(way, p.onPhase(Phase.CHOOSING, Phase.IDLE))
        assertNull(p.back())
    }

    @Test
    fun `2 X forgets the way back`() {
        val p = PreviewBack()
        p.planned(way)
        p.forget()
        assertNull(p.onPhase(Phase.CHOOSING, Phase.IDLE))
        assertNull(p.back())
    }

    @Test
    fun `3 Start ends the way back`() {
        val p = PreviewBack()
        p.planned(way)
        assertNull(p.onPhase(Phase.CHOOSING, Phase.GUIDING))
        assertNull(p.back())
    }

    @Test
    fun `4 a trip planned another way closes with Undo as before`() {
        assertNull(PreviewBack().back())
        val p = PreviewBack()
        p.planned(way)
        p.planned(null)   // a later one-tap PlanTrip
        assertNull(p.back())
    }

    @Test
    fun `5 a window with no list behind it opens again alone`() {
        val p = PreviewBack()
        p.planned(PreviewBack.Way(cafe, null, false))
        assertEquals(cafe, p.back())
        assertNull(p.onPhase(Phase.PLANNING, Phase.IDLE)!!.results)
    }

    @Test
    fun `6 changes in IDLE before the plan starts keep the way`() {
        val p = PreviewBack()
        p.planned(way)
        assertNull(p.onPhase(Phase.IDLE, Phase.IDLE))
        assertNull(p.onPhase(Phase.IDLE, Phase.PLANNING))
        assertEquals(cafe, p.back())
    }

    @Test
    fun `7 the copy does not depend on the live list (Change start or Add stop wiped it meanwhile)`() {
        val p = PreviewBack()
        p.planned(way)
        assertEquals(cafe, p.back())
        assertSame(results, p.onPhase(Phase.CHOOSING, Phase.IDLE)!!.results)
    }
}
