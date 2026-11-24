package io.github.spysyweeb.beamline.nav

import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.nav.NavIntent
import io.github.spysyweeb.beamline.api.nav.Origin
import io.github.spysyweeb.beamline.api.nav.Phase
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** How a trip's place is tied to the road (the owner's answers after batch C1; docs/experiments/23-batch-c.md "C1 routing fix"). */
class StopTiesTest {
    private val p = GeoPoint(42.1243521, -80.0777055)

    @Test
    fun `a dropped pin, a saved pin and a recent pin are map points`() {
        assertTrue(StopTies.isMapPoint(Place("Dropped pin", p, PlaceSource.MAP_PIN, subtitle = "42.12435, -80.07771")))
        assertTrue(StopTies.isMapPoint(Place("Mom's", p, PlaceSource.SAVED, subtitle = "42.12435, -80.07771", savedId = "s0123456789abcdef")))
        assertTrue(StopTies.isMapPoint(Place("Point on map", p, PlaceSource.RECENT, subtitle = "42.12435, -80.07771")))
        assertFalse(StopTies.isMapPoint(Place("Family Dollar", p, PlaceSource.SEARCH, subtitle = "155 East 12th Street, Erie")))
        assertNull(StopTies.street(Place("Dropped pin", p, PlaceSource.MAP_PIN, subtitle = "155 East 12th Street, Erie")))
    }

    @Test
    fun `the street of a place's address`() {
        assertEquals("East 12th Street", StopTies.street(Place("Family Dollar", p, PlaceSource.SEARCH, subtitle = "155 East 12th Street, Erie")))
        assertEquals("East 12th Street", StopTies.street(Place("155 East 12th Street", p, PlaceSource.SEARCH, subtitle = "Erie, PA 16501", categoryId = "address")))
        assertEquals("East Front Street", StopTies.street(Place("Museum", p, PlaceSource.SAVED, subtitle = "Erie Maritime Museum, 150 East Front Street, Erie", savedId = "s0123456789abcdef")))
        assertEquals("State Street", StopTies.street(Place("Shop", p, PlaceSource.SEARCH, subtitle = "1200-1210 State Street, Erie")))
        assertNull(StopTies.street(Place("Sheetz", p, PlaceSource.SEARCH, subtitle = "Peach Street, Erie")))
        assertNull(StopTies.street(Place("Downs Drive", p, PlaceSource.SEARCH, subtitle = "Erie, Pennsylvania")))
        assertNull(StopTies.street(Place("84 Lumber", p, PlaceSource.SEARCH, subtitle = "Erie")), "a name is read only for an address result")
    }

    /** Batch E (docs/next-round.md item 18): which places end in their own lot. */
    @Test
    fun `a point of interest is a business of the search pack, a map POI or a place saved from one`() {
        assertTrue(StopTies.isPointOfInterest(Place("Eat'n Park", p, PlaceSource.SEARCH, subtitle = "7355 Peach Street, Millcreek", categoryId = "restaurant")))
        assertTrue(StopTies.isPointOfInterest(Place("Florist", p, PlaceSource.SEARCH, subtitle = "Erie", categoryId = "shop=florist")))
        assertTrue(StopTies.isPointOfInterest(Place("Family Dollar", p, PlaceSource.RECENT, subtitle = "155 East 12th Street, Erie", categoryId = "dollar_store")))
        assertTrue(StopTies.isPointOfInterest(Place("Eat'n Park", p, PlaceSource.SAVED, subtitle = "7355 Peach Street", categoryId = "restaurant", savedId = "s0123456789abcdef")))
        assertTrue(StopTies.isPointOfInterest(Place("Eat'n Park", p, PlaceSource.MAP_POI)), "a POI tapped on the map")
        assertFalse(StopTies.isPointOfInterest(Place("155 East 12th Street", p, PlaceSource.SEARCH, subtitle = "Erie, PA 16501", categoryId = "address")), "a house address")
        assertFalse(StopTies.isPointOfInterest(Place("Downs Drive", p, PlaceSource.SEARCH, subtitle = "Millcreek", categoryId = "residential")), "a street")
        assertFalse(StopTies.isPointOfInterest(Place("Erie", p, PlaceSource.SEARCH, subtitle = "Pennsylvania", categoryId = "city")), "a city")
        assertFalse(StopTies.isPointOfInterest(Place("16501", p, PlaceSource.SEARCH, categoryId = "zip")), "a ZIP code")
        assertFalse(StopTies.isPointOfInterest(Place("Home", p, PlaceSource.SAVED, subtitle = "Somewhere", savedId = "s0123456789abcdef")), "no category: not known")
        assertFalse(StopTies.isPointOfInterest(Place("Dropped pin", p, PlaceSource.MAP_PIN, categoryId = "restaurant")), "a map point never")
        assertFalse(StopTies.isPointOfInterest(Place("Shop", p, PlaceSource.SEARCH, subtitle = "42.12435, -80.07771", categoryId = "restaurant")), "a saved pin's coordinates")
    }

    @Test
    fun `a trip to a dropped pin is not tied, alleys are still avoided`() = sessionTest { h ->
        h.guideExit24()   // Data.DOWNS_DRIVE is a MAP_PIN place
        val plan = h.engine.routeRequests.first { "format" !in it }
        val dest = plan["locations"]!!.jsonArray.last().jsonObject
        assertFalse("search_filter" in dest || "search_cutoff" in dest, "$dest")
        assertEquals(5, plan["costing_options"]!!.jsonObject["auto"]!!.jsonObject["alley_factor"]!!.jsonPrimitive.int)
        assertTrue(h.engine.asked.none { "shape" in it && it["shape_match"]?.jsonPrimitive?.content == "map_snap" && it["trace_options"] != null && "edge.names" in it.toString() },
            "no address probe for a pin")
    }
    /**
     * Batch E2 (docs/next-round.md item 19): the plan decides where a place's stop ends once (a doughnut shop on
     * Peach Street: in its lot); the second route type and a re-route send that same end, with no probe or check again.
     */
    @Test
    fun `a re-route keeps the end the plan decided`() = sessionTest { h ->
        val shop = Place("Krispy Kreme", GeoPoint(42.0525432, -80.0840009), PlaceSource.SEARCH, subtitle = "7501 Peach Street, Erie",
            categoryId = "fast_food")
        h.send(NavIntent.PlanTo(shop))
        h.send(NavIntent.SetOrigin(Origin.FromPlace(Data.EXIT24_ORIGIN, 57.0)))
        h.advance(1_000)
        h.assertAllRecorded()
        assertEquals(Phase.CHOOSING, h.phase(), "${h.state.problem}")
        fun dest(q: kotlinx.serialization.json.JsonObject) = q["locations"]!!.jsonArray.last().jsonObject
        // the planner's own looks at a route's edges ask for "edge.use" (the fork check of item 37 asks for another road's use)
        fun decisionCalls() = h.engine.asked.count { q ->
            "shape" in q && q["filters"]?.jsonObject?.get("attributes")?.jsonArray?.any { it.jsonPrimitive.content == "edge.use" } == true
        }
        val planned = h.engine.routeRequests.filter { "format" !in it }
        val end = dest(planned.last())
        assertEquals("either", end["preferred_side"]!!.jsonPrimitive.content, "the shop's lot: $end")
        val afterDecision = planned.indexOfFirst { "alternates" in it }
        assertTrue(afterDecision > 0 && planned.drop(afterDecision).all { dest(it) == end }, "Simpler's pool asks the same end")
        val asked = decisionCalls()
        assertTrue(asked >= 2, "the address and lot probes (and the tail check) once")
        h.send(NavIntent.Start(null))
        assertEquals(Phase.GUIDING, h.phase(), "${h.state.problem}")
        h.drive(500.0, 600.0)
        for (i in 0 until 5) h.at(625.0 + 25.0 * i, sideM = 80.0)   // off I-90 (OffRouteTest.leaveI90)
        h.assertAllRecorded()
        val reroute = h.engine.routeRequests.last { "format" !in it }
        assertTrue(reroute["locations"]!!.jsonArray.first().jsonObject.containsKey("heading"), "the re-route from the car")
        assertEquals(end, dest(reroute), "the re-route ends at the same point")
        // item 35: every plan looks once at the way into the lot it ends in (one trace); nothing else is asked again
        assertEquals(asked + 1, decisionCalls(), "no probe and no check again, only the look at the way into the lot")
    }

    /**
     * Item 28 (docs/next-round.md): a large store's place is its centroid, the middle of the roof; the session
     * reads its front door once (the search packs' poi_door, here given by the test) and the plan ends at the
     * lane by that door, for every route type. The same Walmart Supercenter as reference_routes.py door_parking.
     */
    @Test
    fun `a large store is planned to the lane by its front door`() = kotlinx.coroutines.test.runTest {
        val store = Place("Walmart Supercenter", GeoPoint(42.0520658, -80.0798629), PlaceSource.SEARCH, subtitle = "1825 Downs Drive, Erie",
            categoryId = "grocery")
        val door = GeoPoint(42.0518263, -80.0804005)
        val from = Place("West 26th Street", GeoPoint(42.1050, -80.1080), PlaceSource.MAP_PIN)
        val asked = mutableListOf<Place>()
        fun dest(q: kotlinx.serialization.json.JsonObject) = q["locations"]!!.jsonArray.last().jsonObject
        fun toDoorM(q: kotlinx.serialization.json.JsonObject) = io.github.spysyweeb.beamline.routing.model.Geo.distanceM(
            io.github.spysyweeb.beamline.routing.model.LatLon(dest(q)["lat"]!!.jsonPrimitive.content.toDouble(), dest(q)["lon"]!!.jsonPrimitive.content.toDouble()),
            io.github.spysyweeb.beamline.routing.model.LatLon(door.lat, door.lon))
        val h = Harness(this, doorOf = { p, _ -> asked += p; door.takeIf { p == store } })
        try {
            h.send(NavIntent.PlanTo(store))
            h.send(NavIntent.SetOrigin(Origin.FromPlace(from, null)))
            h.advance(1_000)
            h.assertAllRecorded()
            assertEquals(Phase.CHOOSING, h.phase(), "${h.state.problem}")
            val planned = h.engine.routeRequests.filter { "format" !in it }
            val end = dest(planned.last())
            assertEquals("either", end["preferred_side"]!!.jsonPrimitive.content, "its lot: $end")
            assertTrue(toDoorM(planned.last()) < 20.0, "${toDoorM(planned.last())} m from the door")
            assertTrue(planned.drop(planned.indexOfFirst { "alternates" in it }).all { dest(it) == end }, "Simpler ends at the same point")
            assertEquals(listOf(store), asked, "the door is read once per place, and only for a point of interest (never for the origin's pin)")
            // planning the same trip again does not read it again
            val before = h.engine.routeRequests.size
            h.send(NavIntent.SetOrigin(Origin.FromPlace(from, null)))
            h.advance(1_000)
            h.assertAllRecorded()
            assertTrue(h.engine.routeRequests.size > before, "planned again")
            assertEquals(listOf(store), asked)
            assertTrue(toDoorM(h.engine.routeRequests.last { "format" !in it }) < 20.0)
        } finally {
            h.close()
        }
    }
}
