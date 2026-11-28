package io.github.spysyweeb.beamline.nav

import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.nav.ManeuverKind
import io.github.spysyweeb.beamline.api.nav.NavIntent
import io.github.spysyweeb.beamline.api.nav.Origin
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.Simulation
import io.github.spysyweeb.beamline.routing.banner.BannerRules
import io.github.spysyweeb.beamline.routing.model.LatLon
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Item 22 (docs/next-round.md): the traffic lights of a route ([RouteLights]), "At the light" on the
 * banner, the road being driven, and a turn into a store's lot ([Views]).
 */
class RouteLightsTest {
    private val geo = RouteGeometry(Data.exit24)
    private val rules = BannerRules.bundled()

    @Test
    fun `an answer's lights are the ends of the edges that have one`() {
        val answer = """{"edges":[{"end_shape_index":3,"traffic_signal":false,"end_node":{"traffic_signal":true}},
            {"end_shape_index":7,"traffic_signal":false,"end_node":{"traffic_signal":false}},
            {"end_shape_index":9,"traffic_signal":true},{"end_shape_index":12}]}"""
        assertEquals(listOf(3, 9), RouteLights.lightIndexes(answer))
        assertEquals(emptyList<Int>(), RouteLights.lightIndexes("not json"))
        assertEquals(emptyList<Int>(), RouteLights.lightIndexes("""{"error":"no"}"""))
    }

    @Test
    fun `the request walks the route's own points and asks only for the lights`() {
        val s = RouteLights.stretches(geo)
        assertEquals(1, s.size, "one leg of 5 km: one call")
        assertEquals(0 to geo.shape.lastIndex, s[0].from to s[0].to)
        val req = RouteLights.request(geo, s[0])
        assertEquals("edge_walk", req["shape_match"]!!.jsonPrimitive.content)
        assertEquals(geo.shape.size, req["shape"]!!.jsonArray.size)
        assertEquals(listOf("edge.end_shape_index", "edge.traffic_signal", "node.traffic_signal"),
            req["filters"]!!.jsonObject["attributes"]!!.jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun `lights are found per stretch, a failed stretch has none, the arrival is never one`() {
        val last = geo.shape.lastIndex
        val asked = ArrayList<Int>()
        val lights = RouteLights.build(geo) { req ->
            asked += Json.parseToJsonElement(req).jsonObject["shape"]!!.jsonArray.size
            """{"edges":[{"end_shape_index":10,"traffic_signal":true},{"end_shape_index":40,"end_node":{"traffic_signal":true}},
                {"end_shape_index":$last,"traffic_signal":true},{"end_shape_index":${last + 5},"traffic_signal":true}]}"""
        }
        assertEquals(listOf(geo.shape.size), asked)
        assertEquals(2, lights.size)
        assertEquals(geo.along[10], lights.alongM[0], 1e-9)
        assertEquals(geo.shape[40], lights.points[1])
        assertEquals(0, RouteLights.build(geo) { throw IllegalStateException("engine") }.size)
        assertEquals(0, RouteLights.build(geo) { null }.size)
    }

    // ---- item 36: the packs' lights ----

    /** A point [m] metres to the right of the route's line at [alongM]. */
    private fun beside(alongM: Double, m: Double): LatLon = RouteGeometry.move(geo.pointAt(alongM), geo.bearingAt(alongM)!! + 90.0, m)

    @Test
    fun `with packs the request also asks what a junction is and where the road is plain`() {
        val req = RouteLights.request(geo, RouteLights.stretches(geo)[0], junctions = true)
        assertEquals(
            listOf("edge.end_shape_index", "edge.traffic_signal", "node.traffic_signal", "edge.bridge", "edge.tunnel", "edge.road_class",
                "node.intersecting_edge.driveability"),
            req["filters"]!!.jsonObject["attributes"]!!.jsonArray.map { it.jsonPrimitive.content })
        val ends = RouteLights.edgeEnds("""{"edges":[
            {"end_shape_index":3,"end_node":{"traffic_signal":true,"intersecting_edges":[{"driveability":"both"}]}},
            {"end_shape_index":5,"bridge":true,"end_node":{"intersecting_edges":[{"driveability":"none"}]}},
            {"end_shape_index":8,"road_class":"motorway","end_node":{"intersecting_edges":[{"driveability":"forward"}]}},
            {"end_shape_index":9,"tunnel":true},{"end_node":{}}]}""")
        assertEquals(listOf(3, 5, 8, 9), ends.map { it.index }, "an edge without its end index is left out")
        assertEquals(listOf(true, false, false, false), ends.map { it.light })
        assertEquals(listOf(true, false, true, false), ends.map { it.junction }, "a path beside the road makes no junction")
        assertEquals(listOf(true, false, false, false), ends.map { it.plain })
        // the pack's cell: packbuilder/search/signals.py
        assertEquals((4211L + 9000) * 40000 + (-8007 + 18000), RouteLights.cell(42.11343, -80.06382))
        assertEquals(0L, RouteLights.cell(-89.999, -179.999))
    }

    @Test
    fun `a pack's light counts at a junction of the route or on its line, on plain road, where the engine has none`() {
        val n = geo.shape.lastIndex
        val (a, b, c, d, e) = listOf(n / 10, 3 * n / 10, 5 * n / 10, 7 * n / 10, 9 * n / 10)
        // the engine: a light at a; junctions at a, b, c and e; the edge that ends at e is a bridge
        val answer = """{"edges":[
            {"end_shape_index":$a,"traffic_signal":true,"end_node":{"intersecting_edges":[{"driveability":"both"}]}},
            {"end_shape_index":$b,"end_node":{"intersecting_edges":[{"driveability":"both"}]}},
            {"end_shape_index":$c,"end_node":{"intersecting_edges":[{"driveability":"forward"}]}},
            {"end_shape_index":$d,"end_node":{"intersecting_edges":[{"driveability":"none"}]}},
            {"end_shape_index":$e,"bridge":true,"end_node":{"intersecting_edges":[{"driveability":"both"}]}},
            {"end_shape_index":$n}]}"""
        val atB = RouteGeometry.move(geo.shape[b], 0.0, 15.0)                 // 15 m from the junction at b: its light
        val pastC = RouteGeometry.move(geo.shape[c], 0.0, 30.0)               // 30 m from the junction at c: another junction's
        val between = (geo.along[c] + geo.along[d]) / 2
        val onLine = beside(between, 1.0)                                    // on the route's own line between junctions
        val besideLine = beside((geo.along[b] + geo.along[c]) / 2, 10.0)      // 10 m beside the line, no junction there: another road's
        val atD = RouteGeometry.move(geo.shape[d], 0.0, 8.0)                  // 8 m from an edge's end that is no junction
        val onBridge = beside((geo.along[d] + geo.along[e]) / 2, 0.0)         // on the line, but the route is on a bridge there
        val atE = RouteGeometry.move(geo.shape[e], 0.0, 5.0)                  // a junction at the bridge's end: not plain road
        val beforeA = beside(geo.along[a] - 12.0, 0.0)                        // the stop line of the engine's light at a
        val all = listOf(atB, pastC, onLine, besideLine, atD, onBridge, atE, beforeA)
        for (p in listOf(pastC, besideLine, atD)) {
            assertTrue(geo.project(p).distanceM > RouteLights.ON_LINE_M, "the test's own points: not on the line by chance")
        }
        var asked = 0
        val lights = RouteLights.build(geo, signals = { cells ->
            asked++
            all.filter { RouteLights.cell(it.lat, it.lon) in cells }
        }) { answer }
        assertEquals(1, asked, "one read of the packs for the whole route")
        assertEquals(3, lights.size, "${lights.alongM.toList()}")
        assertEquals(geo.along[a], lights.alongM[0], 1e-9)
        assertEquals(geo.along[b], lights.alongM[1], 1e-9)
        assertEquals(between, lights.alongM[2], 1.0)
        assertEquals(geo.shape[b], lights.points[1], "a junction's light is drawn at the junction, not where the map has the signal")
        // the end of the walk's last edge is a stop, not a junction, whatever the engine lists there
        val stop = """{"edges":[{"end_shape_index":$b,"end_node":{"intersecting_edges":[{"driveability":"both"}]}}]}"""
        assertEquals(0, RouteLights.build(geo, signals = { listOf(atB) }) { stop }.size)
        // without packs nothing changes; packs that fail leave the engine's lights
        assertEquals(listOf(geo.along[a]), RouteLights.build(geo) { answer }.alongM.toList())
        assertEquals(listOf(geo.along[a]), RouteLights.build(geo, signals = { throw IllegalStateException("pack") }) { answer }.alongM.toList())
        assertEquals(listOf(geo.along[a]), RouteLights.build(geo, signals = { emptyList() }) { answer }.alongM.toList())
    }

    @Test
    fun `the lights ahead are one per junction, nearest first, to the route's end`() {
        val p = LatLon(42.0, -80.0)
        val l = RouteLights(listOf(100.0, 120.0, 500.0, 2_900.0, 3_400.0, 250_000.0), List(6) { p })
        assertEquals(5, l.ahead(0.0).size, "100 and 120 are one junction; 250 km ahead still counts")
        assertEquals(4, l.ahead(100.0).size, "the junction at 100 is passed, its second light with it")
        assertEquals(4, l.ahead(450.0).size)
        assertEquals(3, l.ahead(600.0).size)
        assertEquals(1, l.ahead(3_400.0).size)
        assertEquals(0, l.ahead(250_000.0).size)
        assertEquals(3, l.ahead(0.0, aheadM = 3_000.0).size, "a window still works when asked for")
        assertEquals(0, RouteLights.NONE.ahead(0.0).size)
        assertTrue(l.isAt(110.0) && l.isAt(500.0) && l.isAt(496.0) && l.isAt(485.0), "5 m before the turn point to 15 m after it")
        assertFalse(l.isAt(150.0) || l.isAt(480.0) || l.isAt(510.0))
    }

    @Test
    fun `a plain turn at a light says so, an exit never does`() {
        val banners = Views.banners(Data.exit24, rules)
        val exit = banners.indexOfFirst { it.primary == "Exit 24" }
        val left = banners.indexOfFirst { it.kind == ManeuverKind.LEFT }
        val p = LatLon(42.0, -80.0)
        val lights = RouteLights(listOf(geo.maneuverAlong[exit], geo.maneuverAlong[left] + 10.0), listOf(p, p))
        val out = Views.atLights(banners, geo, lights)
        assertEquals("Exit 24", out[exit].primary)
        assertEquals("At the light, turn left", out[left].primary)
        assertEquals(banners[left].copy(primary = "At the light, turn left"), out[left], "nothing else of the banner changes")
        assertEquals("Turn right", out[left].following!!.primary, "the chip keeps its short words")
        assertEquals(banners, Views.atLights(banners, geo, RouteLights.NONE))
        // a light the car passes 10 m (or 40 m) before it turns is another junction's; 20 m past the turn too
        for (off in listOf(-40.0, -10.0, 20.0)) {
            val other = RouteLights(listOf(geo.maneuverAlong[left] + off), listOf(p))
            assertEquals("Turn left", Views.atLights(banners, geo, other)[left].primary, "$off")
        }
        assertEquals("At the light, turn left", Views.atLights(banners, geo, RouteLights(listOf(geo.maneuverAlong[left] - 4.0), listOf(p)))[left].primary)
    }

    /** Play test P34: the last 5 mi said only "Arrive, on the left"; each arrival names the stop its leg ends at. */
    @Test
    fun `P34 each arrival names its stop, a dropped pin keeps Arrive`() {
        val banners = Views.banners(Data.exit24, rules)
        val zoo = Place("Erie Zoo", Data.DOWNS_DRIVE.point, PlaceSource.SEARCH)
        val out = Views.atStops(banners, geo, listOf(zoo))
        val last = banners.last()
        assertEquals(ManeuverKind.ARRIVE, last.kind, "exit 24 arrives with no side")
        assertEquals("Arrive at Erie Zoo", out.last().primary)
        assertEquals(Views.arriveAt(last.kind, "Erie Zoo"), out.last().primary)
        assertEquals(last.copy(primary = "Arrive at Erie Zoo"), out.last(), "nothing else of the banner changes")
        assertEquals(banners.dropLast(1), out.dropLast(1), "every other banner unchanged")
        assertEquals(banners, Views.atStops(banners, geo, listOf(Data.DOWNS_DRIVE)), "a dropped pin (MAP_PIN) keeps the plain words")
        assertEquals(banners, Views.atStops(banners, geo, emptyList()))
        assertEquals(banners, Views.atStops(banners, geo, listOf(zoo.copy(name = "  "))), "no name: the plain words")
        assertEquals("Arrive on the left at Erie Zoo", Views.arriveAt(ManeuverKind.ARRIVE_LEFT, "Erie Zoo"))
        assertEquals("Arrive at Erie Zoo", Views.arriveAt(ManeuverKind.ARRIVE, "Erie Zoo"))
        // the steps preview says the same (audit F40)
        val geometry = Data.exit24.shape.map { GeoPoint(it.lat, it.lon) }
        assertEquals("Arrive on the right at Erie Zoo", Views.arriveAt(ManeuverKind.ARRIVE_RIGHT, "Erie Zoo"))
        assertEquals("Arrive at Erie Zoo", Views.steps(Data.exit24, rules, geometry, legPlaces = listOf(zoo)).last().primary)
        assertEquals(banners.last().primary, Views.steps(Data.exit24, rules, geometry).last().primary, "without the stops: as before")
    }

    /**
     * Review F-n 2: item 52 hands over a point with no label (named by its coordinates or its street line); driven
     * to, it is a recent, and a recent or resumed copy of a point still has no name of its own: the plain words.
     */
    @Test
    fun `P34 a point named by its coordinates or its street line keeps Arrive, also as a recent`() {
        val banners = Views.banners(Data.exit24, rules)
        val at = Data.DOWNS_DRIVE.point
        val coords = "42.05331, -80.07958"
        for (source in listOf(PlaceSource.RECENT, PlaceSource.RESUMED, PlaceSource.SEARCH, PlaceSource.SAVED)) {
            assertEquals(banners, Views.atStops(banners, geo, listOf(Place(coords, at, source))), "$source named by its coordinates")
            assertEquals(banners, Views.atStops(banners, geo, listOf(Place("Near Downs Drive, Erie", at, source, subtitle = coords))),
                "$source named by its street line")
        }
        // a name of its own stays: a saved point the driver named, another app's label for a point
        assertEquals("Arrive at Grandma's", Views.atStops(banners, geo,
            listOf(Place("Grandma's", at, PlaceSource.SAVED, subtitle = coords, savedId = "s0000000000000001"))).last().primary)
        assertEquals("Arrive at Erie Zoo", Views.atStops(banners, geo, listOf(Place("Erie Zoo", at, PlaceSource.RECENT, subtitle = coords))).last().primary)
        // the steps preview says the same
        val geometry = Data.exit24.shape.map { GeoPoint(it.lat, it.lon) }
        assertEquals(banners.last().primary, Views.steps(Data.exit24, rules, geometry, legPlaces = listOf(Place(coords, at, PlaceSource.RECENT))).last().primary)
    }

    @Test
    fun `the road being driven is named by the stretch behind the banner's maneuver`() {
        val mans = Data.exit24.maneuvers
        assertNull(Views.currentRoad(Data.exit24, 0, rules), "before the first maneuver")
        assertEquals("I 90 East", Views.currentRoad(Data.exit24, 1, rules), "no memorial name beside the road's own")
        assertNull(Views.currentRoad(Data.exit24, 2, rules), "on the exit ramp: no name")
        assertEquals("US 19 / Peach St", Views.currentRoad(Data.exit24, 3, rules))
        assertEquals("Downs Dr", Views.currentRoad(Data.exit24, mans.lastIndex, rules))
    }

    @Test
    fun `the session shows the lights ahead and the road while it guides`() = sessionTest { h ->
        h.guideExit24(Simulation(speedFactor = 1.0))
        val seen = ArrayList<Triple<String, String?, Int>>()
        for (i in 0 until 300) {
            h.advance(1_000)
            val g = h.state.guidance ?: break
            if (h.phase() != Phase.GUIDING) break
            val b = g.banner ?: continue
            seen += Triple(b.view.primary, g.currentRoad, g.lightsAhead.size)
        }
        assertTrue(seen.any { it.first == "Exit 24" && it.second == "I 90 East" })
        val left = seen.filter { it.first == "At the light, turn left" }
        assertTrue(left.isNotEmpty() && left.all { it.second == null }, "the exit ramp has no name")
        assertTrue(left.first().third >= 1, "the light at the end of the ramp is ahead")
        assertTrue(seen.any { it.first == "Turn right" && it.second == "US 19 / Peach St" })
        assertEquals(0, seen.last().third, "every light is behind at the end")
    }

    /** Audit F40: the route sheet's steps preview says what the banner will say ("At the light, turn left"). */
    @Test
    fun `F40 the steps preview says At the light as the banner does`() = sessionTest { h ->
        h.planExit24()
        h.advance(1_000)
        h.assertAllRecorded()
        assertEquals(Phase.CHOOSING, h.phase(), "${h.state.problem}")
        val step = h.state.choices.first { it.id == h.state.selectedChoiceId }.steps.first { it.kind == ManeuverKind.LEFT }
        assertEquals("At the light, turn left", step.primary, "the preview in the banner's words")
        h.send(NavIntent.Start())
        assertEquals(Phase.GUIDING, h.phase(), "${h.state.problem}")
        h.drive(4100.0, m(1) + 15.0, stepM = 20.0)
        assertEquals(2, h.state.u)
        assertEquals(step.primary, h.state.guidance!!.banner!!.view.primary, "the banner says what the preview said")
        val guided = h.state.guidance!!.route.steps.first { it.kind == ManeuverKind.LEFT }
        assertEquals("At the light, turn left", guided.primary, "the guided route's steps too")
    }

    /**
     * Review F-n 3: Start tapped while the steps preview's lights walk runs on the routing thread (it cannot be
     * stopped there) waits for that walk and uses it: the route's lights are walked once, not twice.
     */
    @Test
    fun `F40 Start while the preview's lights walk runs uses that walk`() = sessionTest { h ->
        fun lights(req: kotlinx.serialization.json.JsonObject) =
            req["filters"]?.jsonObject?.get("attributes")?.jsonArray?.any { it.jsonPrimitive.content == "node.traffic_signal" } == true
        var tapped = false
        h.engine.failWhen = { req ->
            if (!tapped && lights(req)) {
                tapped = true
                h.session.send(NavIntent.Start())   // handled on the session thread while the walk runs
            }
            null
        }
        h.planExit24()
        h.advance(1_000)
        assertTrue(tapped, "the preview walked the lights")
        assertEquals(Phase.GUIDING, h.phase(), "${h.state.problem}")
        assertEquals(1, h.engine.asked.count(::lights), "one walk for the preview and Start")
        h.drive(4100.0, m(1) + 15.0, stepM = 20.0)
        assertEquals(2, h.state.u)
        assertEquals("At the light, turn left", h.state.guidance!!.banner!!.view.primary, "Start has the walk's lights")
    }

    /**
     * A turn into a store's lot: the plan of reference_routes.py door_parking (a Walmart Supercenter, its
     * end in its lot by the door) says "into the parking lot" on the turn that leaves the last named road.
     */
    @Test
    fun `the turn that leaves the last named road for a store's lot says into the parking lot`() = kotlinx.coroutines.test.runTest {
        val store = Place("Walmart Supercenter", GeoPoint(42.0520658, -80.0798629), PlaceSource.SEARCH, subtitle = "1825 Downs Drive, Erie",
            categoryId = "grocery")
        val from = Place("West 26th Street", GeoPoint(42.1050, -80.1080), PlaceSource.MAP_PIN)
        val h = Harness(this, doorOf = { p, _ -> GeoPoint(42.0518263, -80.0804005).takeIf { p == store } })
        try {
            h.send(NavIntent.PlanTo(store))
            h.send(NavIntent.SetOrigin(Origin.FromPlace(from, null)))
            h.advance(1_000)
            // audit F40, play test P34: the steps preview says it too, and its arrival names the store
            val steps = h.state.choices.first { it.id == h.state.selectedChoiceId }.steps
            assertEquals(1, steps.count { it.primary.endsWith(Views.INTO_THE_PARKING_LOT) }, "${steps.map { it.primary }}")
            assertTrue(steps.last().primary.startsWith("Arrive") && steps.last().primary.endsWith(" at Walmart Supercenter"), steps.last().primary)
            h.send(NavIntent.Start(Simulation(speedFactor = 4.0)))
            h.advance(1_000)
            h.assertAllRecorded()
            assertEquals(Phase.GUIDING, h.phase(), "${h.state.problem}")
            val texts = LinkedHashSet<String>()
            for (i in 0 until 400) {
                h.advance(1_000)
                val b = h.state.guidance?.banner ?: continue
                texts += b.view.primary + (if (b.view.roads.isEmpty()) "" else " onto " + b.view.roads.joinToString(" / "))
                if (h.phase() != Phase.GUIDING) break
            }
            h.assertAllRecorded()
            val into = texts.filter { it.endsWith(Views.INTO_THE_PARKING_LOT) }
            assertEquals(1, into.size, "$texts")
            assertTrue(into.single().startsWith("Turn ") || into.single().startsWith("At the light, turn "), into.single())
            // play test P34: the last stretch's banner names the stop it ends at
            assertTrue(texts.any { it.startsWith("Arrive") && it.endsWith(" at Walmart Supercenter") }, "$texts")
        } finally {
            h.close()
        }
    }
}
