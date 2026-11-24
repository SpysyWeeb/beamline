package io.github.spysyweeb.beamline.nav

import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.nav.LaneMark
import io.github.spysyweeb.beamline.api.nav.NavIntent
import io.github.spysyweeb.beamline.api.nav.Origin
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.routing.banner.BannerRules
import io.github.spysyweeb.beamline.routing.model.Geo
import io.github.spysyweeb.beamline.routing.model.LatLon
import io.github.spysyweeb.beamline.routing.valhalla.ValhallaResponses
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Rule B9 (batch C1, docs/next-round.md item 12): a lane that ends in a turn. The real case is
 * Peach Street northbound at Craig Street, Erie (the owner allows it as a named test case): two
 * lanes north, a left-turn lane added for the last ~100 m, and at the intersection the right lane
 * becomes right-turn only (engine: lanes left, straight, right with only straight valid; lane
 * counts 2, then 3, then 1). Engine answers recorded from pyvalhalla 3.9.1 on the PA+OH tiles
 * (android/routing/scripts/record_session.py).
 */
class LaneWarningTest {
    private val south = Place("Panera Bread, 4014 Peach St", GeoPoint(42.093101, -80.084320), PlaceSource.MAP_PIN)
    private val north = Place("WERG, 700 Peach St", GeoPoint(42.127704, -80.086062), PlaceSource.MAP_PIN)

    /** The guided route's line: the last plan request the engine answered for this trip. */
    private fun Harness.guidedGeo(): RouteGeometry {
        val req = engine.routeRequests.last { "format" !in it }
        return RouteGeometry(ValhallaResponses.parseRoute(RecordedEngine.standard().route(req.toString())).main)
    }

    private fun Harness.guidePeach() {
        send(NavIntent.PlanTo(north))
        send(NavIntent.SetOrigin(Origin.FromPlace(south, 25.0)))
        advance(1_000)
        assertEquals(Phase.CHOOSING, phase(), "${state.problem}")
        send(NavIntent.Start())
        assertEquals(Phase.GUIDING, phase(), "${state.problem}")
    }

    @Test
    fun `Peach St at Craig St - right lane turns right, keep left, shown about 18 s ahead and gone after`() = sessionTest { h ->
        h.guidePeach()
        h.assertAllRecorded()
        val geo = h.guidedGeo()
        // the one spot of the route, from the OSRM answer and one trace_attributes call
        val osrmReq = h.engine.routeRequests.last { it["format"]?.jsonPrimitive?.content == "osrm" }
        val lw = LaneWarnings.build(geo, RecordedEngine.standard().route(osrmReq.toString()), 0, GuidanceRules.bundled(), BannerRules.bundled()) {
            RecordedEngine.standard().traceAttributes(it)
        }
        val spot = lw.spots.single()
        assertEquals("Right lane turns right: keep left for Peach St", spot.text)
        assertEquals(listOf(LaneMark.NOT_FOR_ROUTE, LaneMark.BEST, LaneMark.NOT_FOR_ROUTE), spot.lanes.map { it.mark })
        assertEquals(Triple(1, 3, 2), Triple(spot.valid, spot.total, spot.before), "1 lane goes on of 3; the road had 2")
        val craig = geo.shape[geo.along.indexOfFirst { it >= spot.alongM }]
        assertEquals(42.1036, craig.lat, 0.0005, "the intersection where the lane count drops to 1")

        // 15 m/s (34 mph): window 18 s x 15 = 270 m
        h.at(spot.alongM - 400.0, speed = 15f, geo = geo)
        h.at(spot.alongM - 330.0, speed = 15f, geo = geo)
        assertNull(h.state.guidance!!.laneWarning, "not yet: 330 m before")
        h.at(spot.alongM - 260.0, speed = 15f, geo = geo)
        val w = assertNotNull(h.state.guidance!!.laneWarning)
        assertEquals("Right lane turns right: keep left for Peach St", w.text)
        assertEquals(260.0, w.distanceM, 15.0)
        // a slower car does not hide it again (shown until passed)
        h.at(spot.alongM - 240.0, speed = 2f, geo = geo)
        assertNotNull(h.state.guidance!!.laneWarning)
        h.at(spot.alongM - 20.0, speed = 15f, geo = geo)
        assertNotNull(h.state.guidance!!.laneWarning)
        h.at(spot.alongM + 30.0, speed = 15f, geo = geo)
        assertNull(h.state.guidance!!.laneWarning, "gone after the intersection")
        h.assertAllRecorded()
    }

    @Test
    fun `an added turn lane alone never warns - Peach St at 26th, one lane on and a left-turn lane added`() = sessionTest { h ->
        // the same drive north on Peach St: past Craig St (the one spot), then at 26th St 1 lane and a
        // left-turn lane added (lanes left, straight+right; one valid): a candidate, never a spot
        h.guidePeach()
        val geo = h.guidedGeo()
        val osrm = RecordedEngine.standard().route(h.engine.routeRequests.last { it["format"]?.jsonPrimitive?.content == "osrm" }.toString())
        val candidates = LaneWarnings.candidates(geo, osrm, 0, 500.0, BannerRules.bundled())
        val lw = LaneWarnings.build(geo, osrm, 0, GuidanceRules.bundled(), BannerRules.bundled()) { RecordedEngine.standard().traceAttributes(it) }
        assertEquals(1, lw.spots.size, "only Craig St warns: ${lw.spots.map { it.text }}")
        assertTrue(candidates.count { c -> c.alongM > lw.spots.single().alongM + 100.0 } >= 1, "the junctions after Craig St (26th St among them) are candidates")
        h.assertAllRecorded()
    }

    @Test
    fun `the side and the words - pure rule`() {
        fun c(vararg lanes: Pair<List<String>, Boolean>, road: String? = "Peach St", highway: Boolean = false) =
            LaneWarnings.Candidate(10, 100.0, lanes.toList(), 0, road, highway)
        val l = listOf("left") to false
        val s = listOf("straight") to true
        val r = listOf("right") to false
        // Peach St at Craig St: 2 lanes became 3 (a left pocket), only the middle goes on
        assertEquals("Right lane turns right: keep left for Peach St", LaneWarnings.spot(c(l, s, r), before = 2)!!.text)
        // two lanes, the right one becomes right only
        assertEquals("Right lane turns right: keep left for Peach St", LaneWarnings.spot(c(s, r), before = 2)!!.text)
        // two lanes, the left one becomes left only; no road name
        assertEquals("Left lane turns left: keep right", LaneWarnings.spot(c(l, s, road = null), before = 2)!!.text)
        // an added left-turn lane only: 1 lane before, 1 goes on
        assertNull(LaneWarnings.spot(c(l, s), before = 1))
        // 3 lanes, both outer ones end
        assertEquals("Keep to the marked lane for Peach St", LaneWarnings.spot(c(l, s, r), before = 3)!!.text)
        // a motorway lane drop
        assertEquals("Right lane exits: keep left for I 90", LaneWarnings.spot(c(s, s, listOf("slight right") to false, road = "I 90", highway = true), before = 3)!!.text)
        // lane counts that do not fit the lanes: no warning
        assertNull(LaneWarnings.spot(c(s, r), before = 4))
    }

    // ---- item 37: the lane to take at a fork ----

    @Test
    fun `Parade St at 28th St - the road forks, the right lane goes on by Old French Rd`() = sessionTest { h ->
        // one lane becomes two at the light, the left one for Pine Ave, the right one for Old French Rd; the engine
        // counts Old French Rd as straight ahead and gives no instruction
        val from = Place("Parade St north of 26th", GeoPoint(42.1166, -80.0663), PlaceSource.MAP_PIN)
        val to = Place("Old French Rd at 35th", GeoPoint(42.1065, -80.0622), PlaceSource.MAP_PIN)
        h.send(NavIntent.PlanTo(to))
        h.send(NavIntent.SetOrigin(Origin.FromPlace(from, 25.0)))
        h.advance(1_000)
        assertEquals(Phase.CHOOSING, h.phase(), "${h.state.problem}")
        h.send(NavIntent.Start())
        assertEquals(Phase.GUIDING, h.phase(), "${h.state.problem}")
        h.assertAllRecorded()
        val geo = h.guidedGeo()
        val osrm = RecordedEngine.standard().route(h.engine.routeRequests.last { it["format"]?.jsonPrimitive?.content == "osrm" }.toString())
        val lw = LaneWarnings.build(geo, osrm, 0, GuidanceRules.bundled(), BannerRules.bundled()) { RecordedEngine.standard().traceAttributes(it) }
        val spot = lw.spots.single { it.text.startsWith("Use") }
        assertEquals("Use the right lane for Old French Rd", spot.text)
        assertEquals(listOf(LaneMark.NOT_FOR_ROUTE, LaneMark.BEST), spot.lanes.map { it.mark })
        val fork = geo.shape[geo.along.indexOfFirst { it >= spot.alongM }]
        assertEquals(42.11343, fork.lat, 0.0003)
        assertEquals(-80.06382, fork.lon, 0.0003)
        // without the fork rule (the rule file before): an added lane alone never warns
        val before = GuidanceRules.bundled().let { it.copy(laneWarning = it.laneWarning.copy(forkAngleDeg = -1.0)) }
        assertTrue(LaneWarnings.build(geo, osrm, 0, before, BannerRules.bundled()) { RecordedEngine.standard().traceAttributes(it) }
            .spots.none { it.alongM == spot.alongM })

        // 11 m/s (25 mph): shown from 198 m before the fork until it is passed
        h.at(spot.alongM - 260.0, speed = 11f, geo = geo)
        h.at(spot.alongM - 230.0, speed = 11f, geo = geo)
        assertTrue(h.state.guidance!!.laneWarning?.text != spot.text, "not yet: 230 m before")
        h.at(spot.alongM - 150.0, speed = 11f, geo = geo)
        val w = assertNotNull(h.state.guidance!!.laneWarning)
        assertEquals("Use the right lane for Old French Rd", w.text)
        assertEquals(150.0, w.distanceM, 15.0)
        h.at(spot.alongM + 30.0, speed = 11f, geo = geo)
        assertTrue(h.state.guidance!!.laneWarning?.text != spot.text, "gone after the fork")
        h.assertAllRecorded()
    }

    @Test
    fun `a fork is another plain road onward, as important as the route's, that may be entered`() {
        val pts = listOf(LatLon(42.1140, -80.0643), LatLon(42.11343, -80.06382), LatLon(42.1131, -80.0636))
        fun answer(other: String, onward: String = """"road_class":"primary","names":["Old French Road","PA 97"]""") = """{"shape":"${Geo.encodePolyline6(pts)}","edges":[
            {"end_shape_index":1,"end_heading":147,"road_class":"primary","end_node":{"intersecting_edges":[
                {"begin_heading":63,"road_class":"tertiary","use":"road","driveability":"both"}, $other,
                {"begin_heading":245,"road_class":"residential","use":"road","driveability":"both"}]}},
            {"end_shape_index":2,$onward}]}"""
        val pine = """{"begin_heading":106,"road_class":"primary","use":"road","driveability":"both"}"""
        assertEquals("Old French Road", LaneWarnings.forkOf(answer(pine), pts[1], 50.0)!!.street)
        // 41 degrees is no fork for a narrower rule; nor is a junction somewhere else
        assertNull(LaneWarnings.forkOf(answer(pine), pts[1], 30.0))
        assertNull(LaneWarnings.forkOf(answer(pine), LatLon(42.1135, -80.0637), 50.0), "8 m from where the edge ends")
        // a lesser road, a ramp, a road that cannot be entered: no fork
        assertNull(LaneWarnings.forkOf(answer(pine.replace("primary", "secondary")), pts[1], 50.0))
        assertNull(LaneWarnings.forkOf(answer(pine.replace("\"road\"", "\"ramp\"")), pts[1], 50.0))
        assertNull(LaneWarnings.forkOf(answer(pine.replace("both", "backward")), pts[1], 50.0))
        // a more important road is one; the road onward may have no name
        val fork = LaneWarnings.forkOf(answer(pine.replace("primary", "trunk"), onward = """"road_class":"secondary""""), pts[1], 50.0)
        assertNull(assertNotNull(fork).street)
        // answers that cannot be read
        assertNull(LaneWarnings.forkOf("not json", pts[1], 50.0))
        assertNull(LaneWarnings.forkOf("""{"shape":"${Geo.encodePolyline6(pts)}","edges":[{"end_shape_index":1,"end_heading":147}]}""", pts[1], 50.0), "no edge after the junction")
    }

    @Test
    fun `the words of a fork name the lanes to use and the road onward`() {
        fun c(vararg lanes: Pair<List<String>, Boolean>, road: String? = "Parade St") = LaneWarnings.Candidate(10, 100.0, lanes.toList(), 0, road, false, forkLike = true)
        val no = listOf("left", "slight left") to false
        val yes = listOf("straight", "right") to true
        assertEquals("Use the right lane for Old French Rd", LaneWarnings.forkSpot(c(no, yes), 1, "Old French Rd")!!.text)
        assertEquals("Use the left lane for Old French Rd", LaneWarnings.forkSpot(c(yes, no), 1, "Old French Rd")!!.text)
        assertEquals("Use the right 2 lanes for Old French Rd", LaneWarnings.forkSpot(c(no, yes, yes), 2, "Old French Rd")!!.text)
        assertEquals("Use the left 2 lanes for Parade St", LaneWarnings.forkSpot(c(yes, yes, no), 2, null)!!.text, "no name onward: the road being driven")
        assertEquals("Use the marked lane for Old French Rd", LaneWarnings.forkSpot(c(no, yes, no), 1, "Old French Rd")!!.text)
        assertEquals("Use the marked lanes", LaneWarnings.forkSpot(c(yes, no, yes, road = null), 2, " ")!!.text)
        assertNull(LaneWarnings.forkSpot(c(yes, yes), 2, "Old French Rd"), "every lane goes on")
        assertNull(LaneWarnings.forkSpot(c(no, no), 2, "Old French Rd"))
        val spot = LaneWarnings.forkSpot(c(no, yes), 1, "Old French Rd")!!
        assertEquals(Triple(1, 2, 1), Triple(spot.valid, spot.total, spot.before))
    }

    @Test
    fun `the exit 24 drive has no lane warning`() = sessionTest { h ->
        h.guideExit24()
        h.drive(0.0, m(4), stepM = 50.0)
        val shown = h.rows.mapNotNull { r -> r.second.guidance?.laneWarning?.let { "${it.text} at ${it.distanceM.toInt()} m, vehicle ${r.second.vehicleAlong().toInt()}" } }
        assertTrue(shown.isEmpty(), "$shown")
        h.assertAllRecorded()
    }
}

private fun <T> assertNotNull(v: T?): T {
    org.junit.jupiter.api.Assertions.assertNotNull(v)
    return v!!
}
