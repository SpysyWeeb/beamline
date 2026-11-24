package io.github.spysyweeb.beamline.routing

import io.github.spysyweeb.beamline.routing.engine.EngineException
import io.github.spysyweeb.beamline.routing.engine.RoutingEngine
import io.github.spysyweeb.beamline.routing.model.LatLon
import io.github.spysyweeb.beamline.routing.model.RouteRequest
import io.github.spysyweeb.beamline.routing.model.Stop
import io.github.spysyweeb.beamline.routing.model.StopEnd
import io.github.spysyweeb.beamline.routing.model.StopType
import io.github.spysyweeb.beamline.routing.planner.PlanResult
import io.github.spysyweeb.beamline.routing.planner.RoutePlanner
import io.github.spysyweeb.beamline.routing.planner.RouteWarning
import io.github.spysyweeb.beamline.routing.planner.RoutingProblem
import io.github.spysyweeb.beamline.routing.planner.ArrivalSideResult
import io.github.spysyweeb.beamline.routing.planner.TieCheckResult
import io.github.spysyweeb.beamline.routing.planner.TileCoverage
import io.github.spysyweeb.beamline.routing.preset.PresetSet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Planner rules with a scripted fake engine (no recordings needed) and with recorded errors. */
class PlannerRulesTest {
    private val presets = PresetSet.bundled()

    /** Answers from a function of the parsed request; counts calls. */
    private class FakeEngine(val answer: (JsonObject) -> String) : RoutingEngine {
        val requests = mutableListOf<JsonObject>()
        override fun route(requestJson: String): String {
            val r = Json.parseToJsonElement(requestJson).jsonObject
            requests += r
            return answer(r)
        }
        override fun matrix(requestJson: String) = throw UnsupportedOperationException()
    }

    /** A minimal Valhalla answer: one leg, given maneuvers (type, km, s, highway). */
    private fun trip(vararg m: Triple<Int, Double, Boolean>, time: Double = 600.0, alternates: List<String> = emptyList(),
                     shape: String = SHAPE): String {
        val km = m.sumOf { it.second }
        val mans = m.joinToString(",") { (t, len, hw) ->
            """{"type":$t,"instruction":"x","length":$len,"time":10,"begin_shape_index":0,"end_shape_index":1${if (hw) ",\"highway\":true" else ""}}"""
        }
        val tripJson = """{"locations":[{"lat":40.0,"lon":-77.0},{"lat":40.01,"lon":-77.0}],"units":"kilometers",
            "legs":[{"maneuvers":[$mans],"summary":{"length":$km,"time":$time},"shape":"$shape"}],
            "summary":{"length":$km,"time":$time}}"""
        val alts = if (alternates.isEmpty()) "" else ",\"alternates\":[" + alternates.joinToString(",") { "{\"trip\":$it}" } + "]"
        return """{"trip":$tripJson$alts}"""
    }

    private val SHAPE = io.github.spysyweeb.beamline.routing.model.Geo.encodePolyline6(listOf(LatLon(40.0, -77.0), LatLon(40.01, -77.0)))
    /** Another way between the same two points (an alternate that is not the main route). */
    private val SHAPE2 = io.github.spysyweeb.beamline.routing.model.Geo.encodePolyline6(
        listOf(LatLon(40.0, -77.0), LatLon(40.005, -76.995), LatLon(40.01, -77.0)))

    private fun tripOnly(json: String) = Json.parseToJsonElement(json).jsonObject["trip"].toString()

    private val stops = listOf(Stop(LatLon(40.0, -77.0)), Stop(LatLon(40.01, -77.0)))

    @Test
    fun `avoid highways falls back when the excluded route still keeps motorway km`() {
        val engine = FakeEngine { req ->
            val opts = req["costing_options"]!!.jsonObject["auto"]!!.jsonObject
            if ("exclude_highways" in opts) trip(Triple(1, 0.1, false), Triple(10, 5.0, true), Triple(4, 0.0, false))
            else trip(Triple(1, 0.1, false), Triple(10, 0.4, true), Triple(15, 6.0, false), Triple(4, 0.0, false), time = 900.0)
        }
        val r = RoutePlanner(engine, presets).plan(RouteRequest(stops, "avoid_highways")) as PlanResult.Ok
        assertTrue(r.usedFallback)
        assertEquals(4, engine.requests.size, "the nearest street and the place itself (batch H), each with its fallback")
        assertEquals("0.0", engine.requests[1]["costing_options"]!!.jsonObject["auto"]!!.jsonObject["use_highways"]!!.jsonPrimitive.content)
    }

    @Test
    fun `avoid highways keeps the excluded route when it has no motorway`() {
        val engine = FakeEngine { trip(Triple(1, 0.1, false), Triple(10, 0.3, true), Triple(4, 0.0, false)) }
        val r = RoutePlanner(engine, presets).plan(RouteRequest(stops, "avoid_highways")) as PlanResult.Ok
        assertFalse(r.usedFallback)
        assertEquals(2, engine.requests.size, "the route to the nearest street and to the place itself (batch H): 0.3 motorway km is under the 0.5 km limit")
    }

    @Test
    fun `avoid highways falls back when the exclusion finds no route`() {
        val engine = FakeEngine { req ->
            if ("exclude_highways" in req["costing_options"]!!.jsonObject["auto"]!!.jsonObject) throw EngineException(442, "No path could be found for input")
            trip(Triple(1, 0.1, false), Triple(4, 0.0, false))
        }
        val r = RoutePlanner(engine, presets).plan(RouteRequest(stops, "avoid_highways")) as PlanResult.Ok
        assertTrue(r.usedFallback)
    }

    @Test
    fun `avoid highways alternates after the fallback come from the fallback options and keep its motorway limit`() {
        // review finding (major): the alternates were asked with exclude_highways, the options the
        // fallback rule had just rejected
        val fallbackMain = trip(Triple(1, 0.1, false), Triple(10, 0.4, true), Triple(15, 6.0, false), Triple(4, 0.0, false), time = 900.0)
        val altFewMotorway = tripOnly(trip(Triple(1, 0.1, false), Triple(10, 0.3, true), Triple(15, 6.5, false), Triple(4, 0.0, false), time = 950.0))
        val altMotorway = tripOnly(trip(Triple(1, 0.1, false), Triple(10, 6.0, true), Triple(4, 0.0, false), time = 920.0))
        val engine = FakeEngine { req ->
            val opts = req["costing_options"]!!.jsonObject["auto"]!!.jsonObject
            when {
                "exclude_highways" in opts -> trip(Triple(1, 0.1, false), Triple(10, 5.0, true), Triple(4, 0.0, false),
                    alternates = listOf(altMotorway))
                "alternates" in req -> trip(Triple(1, 0.1, false), Triple(10, 0.4, true), Triple(15, 6.0, false), Triple(4, 0.0, false),
                    time = 900.0, alternates = listOf(altFewMotorway, altMotorway))
                else -> fallbackMain
            }
        }
        val r = RoutePlanner(engine, presets).plan(RouteRequest(stops, "avoid_highways", withAlternates = true)) as PlanResult.Ok
        assertTrue(r.usedFallback)
        assertEquals(5, engine.requests.size, "the route and the fallback to the nearest street and to the place itself (batch H), the alternates")
        val altReq = engine.requests[4]["costing_options"]!!.jsonObject["auto"]!!.jsonObject
        assertEquals(setOf("use_highways"), altReq.keys - presets.everyRequest.costingOptions.keys, "alternates asked with the fallback options")
        assertEquals(2, engine.requests[4]["alternates"]!!.jsonPrimitive.content.toInt())
        // the copy of the chosen route is dropped, and so is the 6 km motorway alternate (> 0.5 km and > the chosen 0.4 km)
        assertEquals(listOf(950.0), r.alternates.map { it.timeS })
    }

    @Test
    fun `avoid highways alternates without the fallback drop an alternate that stays on the motorway`() {
        val engine = FakeEngine { req ->
            if ("alternates" in req) trip(Triple(1, 0.1, false), Triple(10, 0.2, true), Triple(15, 6.0, false), Triple(4, 0.0, false),
                time = 900.0, alternates = listOf(
                    tripOnly(trip(Triple(1, 0.1, false), Triple(10, 3.0, true), Triple(4, 0.0, false), time = 905.0)),
                    tripOnly(trip(Triple(1, 0.1, false), Triple(10, 0.5, true), Triple(15, 6.0, false), Triple(4, 0.0, false), time = 990.0))))
            else trip(Triple(1, 0.1, false), Triple(10, 0.2, true), Triple(15, 6.0, false), Triple(4, 0.0, false), time = 900.0)
        }
        val r = RoutePlanner(engine, presets).plan(RouteRequest(stops, "avoid_highways", withAlternates = true)) as PlanResult.Ok
        assertFalse(r.usedFallback)
        assertEquals(setOf("exclude_highways"), engine.requests[1]["costing_options"]!!.jsonObject["auto"]!!.jsonObject.keys - presets.everyRequest.costingOptions.keys)
        assertEquals(listOf(990.0), r.alternates.map { it.timeS }, "0.5 km is within the limit, 3 km is not")
    }

    @Test
    fun `an engine answer that cannot be read is a failed plan, not an exception`() {
        // review finding: parse errors escaped plan() as IllegalArgumentException / IllegalStateException
        for (body in listOf("not json", """{"trip":{"units":"furlongs","legs":[],"summary":{"length":1,"time":1},"locations":[]}}""", "{}")) {
            val r = RoutePlanner(FakeEngine { body }, presets).plan(RouteRequest(stops))
            r as PlanResult.Failed
            assertTrue(r.problem is RoutingProblem.EngineFailure, "$body -> ${r.problem}")
            assertEquals("The route could not be planned.", r.problem.userText)
        }
        // Avoid highways: an unreadable first answer is a failure, so the fallback is asked (as for 442)
        val engine = FakeEngine { req ->
            if ("exclude_highways" in req["costing_options"]!!.jsonObject["auto"]!!.jsonObject) "garbage"
            else trip(Triple(1, 0.1, false), Triple(4, 0.0, false))
        }
        assertTrue((RoutePlanner(engine, presets).plan(RouteRequest(stops, "avoid_highways")) as PlanResult.Ok).usedFallback)
    }

    @Test
    fun `simpler picks the fewest maneuvers within 10 percent and 10 minutes, not beyond`() {
        fun turns(n: Int) = Array(n + 2) { i -> Triple(if (i == 0) 1 else if (i == n + 1) 4 else 10, 1.0, false) }
        fun plan(base: Double, alt: Double, mp60: Double, mp300: Double, major: Double): Pair<PlanResult.Ok, FakeEngine> {
            val engine = FakeEngine { req ->
                val auto = req["costing_options"]!!.jsonObject["auto"]!!.jsonObject
                val mp = auto["maneuver_penalty"]?.jsonPrimitive?.content
                when {
                    mp == null -> trip(*turns(10), time = base, alternates = listOf(tripOnly(trip(*turns(4), time = alt, shape = SHAPE2))))
                    "use_highways" in auto -> trip(*turns(8), time = major)
                    mp == "60" -> trip(*turns(6), time = mp60)
                    else -> trip(*turns(6), time = mp300)
                }
            }
            return (RoutePlanner(engine, presets).plan(RouteRequest(stops, "simpler")) as PlanResult.Ok) to engine
        }
        // 10 %: main 10 turns 1000 s, alternate 4 turns 1110 s (11 %: out); 6 turns 1090 s (9 %: in) picked,
        // the same count slower (1095 s) not; the major-road candidate 8 turns
        val (r, engine) = plan(1000.0, 1110.0, 1090.0, 1095.0, 1020.0)
        assertEquals(6, r.route.turnCount)
        assertEquals(1090.0, r.route.timeS)
        assertEquals(5, r.candidatesConsidered)
        assertEquals(2, engine.requests.first { "alternates" in it }["alternates"]!!.jsonPrimitive.content.toInt(), "after the decision's Fastest")
        assertEquals(0, r.chosen.routeIndex)
        assertEquals("60", r.chosen.request["costing_options"]!!.jsonObject["auto"]!!.jsonObject["maneuver_penalty"]!!.jsonPrimitive.content)
        // 10 minutes: from 8000 s, 10 % would be 800 s; the alternate 4 turns at 8650 s (+650 s) is out,
        // 6 turns at 8590 s (+590 s) in
        val (r2, _) = plan(8000.0, 8650.0, 8590.0, 8595.0, 8100.0)
        assertEquals(6, r2.route.turnCount)
        assertEquals(8590.0, r2.route.timeS)
        // within both: the alternate with 4 turns (+8 %, +480 s) is the pick
        val (r3, _) = plan(6000.0, 6480.0, 6100.0, 6100.0, 6100.0)
        assertEquals(4, r3.route.turnCount)
        assertEquals(1, r3.chosen.routeIndex)
        // batch E2: no tie check per type (the end is decided once, with Fastest); batch H: the nearest street kept
        // against the place itself
        assertEquals(TieCheckResult.KEPT, r3.tieCheck)
        assertEquals(6480.0, r3.route.timeS)
    }

    @Test
    fun `the arrival side - tertiary and above only, one block at most`() {
        // batch D (next-round item 16): the side request carries street_side_cutoff tertiary (on a
        // residential street the engine ignores the side), and the side route is kept with at most
        // two more guidance instructions (and 60 s)
        val rule = presets.everyRequest.arrivalSide!!
        assertEquals("tertiary", rule.locationOptions["street_side_cutoff"]!!.jsonPrimitive.content)
        assertEquals(2, rule.maxExtraManeuvers)
        for ((extra, kept) in listOf(2 to ArrivalSideResult.SIDE, 3 to ArrivalSideResult.ASKED_KEPT)) {
            val engine = FakeEngine { req ->
                if ("preferred_side" in req["locations"]!!.jsonArray[1].jsonObject)
                    trip(Triple(1, 0.1, false), *Array(1 + extra) { Triple(10, 0.1, false) }, Triple(5, 0.0, false), time = 630.0)
                else trip(Triple(1, 0.1, false), Triple(10, 0.1, false), Triple(6, 0.0, false), time = 600.0)
            }
            val pin = listOf(stops[0], stops[1].copy(tied = false))
            val r = RoutePlanner(engine, presets).plan(RouteRequest(pin)) as PlanResult.Ok
            assertEquals(kept, r.arrivalSide, "$extra more")
            assertEquals("tertiary", engine.requests.last()["locations"]!!.jsonArray[1].jsonObject["street_side_cutoff"]!!.jsonPrimitive.content)
        }
    }

    @Test
    fun `simpler does not ask for alternates with three stops`() {
        val engine = FakeEngine { trip(Triple(1, 1.0, false), Triple(4, 0.0, false)) }
        val three = stops + Stop(LatLon(40.02, -77.0), StopType.BREAK)
        RoutePlanner(engine, presets).plan(RouteRequest(three, "simpler"))
        assertTrue(engine.requests.none { "alternates" in it })
    }

    @Test
    fun `stop types and heading reach the request`() {
        val engine = FakeEngine { trip(Triple(1, 1.0, false), Triple(4, 0.0, false)) }
        val s = listOf(Stop(LatLon(40.0, -77.0), headingDeg = 57, radiusM = 60), Stop(LatLon(40.005, -77.0), StopType.THROUGH),
            Stop(LatLon(40.01, -77.0)))
        RoutePlanner(engine, presets).plan(RouteRequest(s))
        val locs = engine.requests[0]["locations"]!!.jsonArray.map { it.jsonObject }
        assertEquals("57", locs[0]["heading"]!!.jsonPrimitive.content)
        assertEquals("60", locs[0]["radius"]!!.jsonPrimitive.content)
        assertEquals("through", locs[1]["type"]!!.jsonPrimitive.content)
        assertFalse("type" in locs[2], "break is Valhalla's default and is left out, as in route_lib")
    }

    @Test
    fun `errors 170 and 171 name the stop outside the installed tiles`() {
        // tiles: only the 0.25 degree tile around the first stop
        val coverage = TileCoverage.fromTilePaths(listOf("2/000/749/212.gph"))
        for (code in listOf(170, 171)) {
            val engine = FakeEngine { throw EngineException(code, "x") }
            val first = LatLon(40.1, -76.9) // tile 749212
            val r = RoutePlanner(engine, presets, coverage).plan(RouteRequest(listOf(Stop(first), Stop(LatLon(41.2, -81.3)))))
            r as PlanResult.Failed
            val p = r.problem as RoutingProblem.OutsideInstalledMaps
            assertEquals(listOf(1), p.stopIndices, "code $code")
            assertTrue(p.userText.startsWith("Stop 2 is outside the installed maps"))
        }
    }

    @Test
    fun `170 with every stop on installed tiles is no route, and 442 too`() {
        val coverage = TileCoverage.fromTilePaths(listOf("2/000/749/212.gph"))
        val inside = listOf(Stop(LatLon(40.1, -76.9)), Stop(LatLon(40.12, -76.92)))
        val r170 = RoutePlanner(FakeEngine { throw EngineException(170, "x") }, presets, coverage).plan(RouteRequest(inside))
        assertTrue((r170 as PlanResult.Failed).problem is RoutingProblem.NoRoute)
        // review finding: 171 with every stop on installed tiles said "outside the installed maps"
        val r171 = RoutePlanner(FakeEngine { throw EngineException(171, "No suitable edges near location") }, presets, coverage)
            .plan(RouteRequest(inside)) as PlanResult.Failed
        assertTrue(r171.problem is RoutingProblem.NoRoadNearStop, "${r171.problem}")
        assertEquals(171, r171.problem.engineCode)
        val r442 = RoutePlanner(FakeEngine { throw EngineException(442, "x") }, presets).plan(RouteRequest(inside))
        assertTrue((r442 as PlanResult.Failed).problem is RoutingProblem.NoRoute)
        val r154 = RoutePlanner(FakeEngine { throw EngineException(154, "x") }, presets).plan(RouteRequest(inside))
        assertTrue((r154 as PlanResult.Failed).problem is RoutingProblem.TooLong)
        val one = RoutePlanner(FakeEngine { error("not called") }, presets).plan(RouteRequest(inside.take(1)))
        assertEquals(RoutingProblem.NeedTwoStops, (one as PlanResult.Failed).problem)
    }

    /**
     * Batch H (docs/next-round.md, the owner's answers after batch G; route_lib test_presets
     * `test_the_place_itself_untied_is_the_last_end_and_never_wins_with_an_alley`): the nearest street is checked
     * against the place itself, untied; the place never wins with an alley or a drive-through lane in its last 300 m.
     */
    @Test
    fun `the place itself is the last end and never wins with an alley or a drive-through lane`() {
        val placeShape = io.github.spysyweeb.beamline.routing.model.Geo.encodePolyline6(listOf(LatLon(40.0, -77.0), LatLon(40.0102, -77.0)))
        data class Case(val nearTime: Double?, val nearUturn: Boolean, val placeTail: List<String>, val end: StopEnd)
        val cases = listOf(
            Case(586.6, true, listOf("road"), StopEnd.PLACE),
            Case(712.0, false, listOf("road"), StopEnd.PLACE),
            Case(711.0, false, listOf("road"), StopEnd.NEAR),
            Case(586.6, true, listOf("road", "alley"), StopEnd.NEAR),
            Case(586.6, true, listOf("drive_through", "parking_aisle"), StopEnd.NEAR),
            Case(null, false, listOf("road", "alley"), StopEnd.PLACE))
        for (c in cases) {
            var tails = 0
            val engine = object : RoutingEngine {
                override fun route(requestJson: String): String {
                    val dest = Json.parseToJsonElement(requestJson).jsonObject["locations"]!!.jsonArray[1].jsonObject
                    if ("search_filter" !in dest) return trip(Triple(1, 0.1, false), Triple(5, 0.0, false), time = 621.9, shape = placeShape)
                    val t = c.nearTime ?: throw EngineException(442, "No path could be found for input")
                    return if (c.nearUturn) trip(Triple(1, 0.1, false), Triple(13, 0.1, false), Triple(5, 0.0, false), time = t)
                    else trip(Triple(1, 0.1, false), Triple(5, 0.0, false), time = t)
                }
                override fun matrix(requestJson: String) = throw UnsupportedOperationException()
                override fun traceAttributes(requestJson: String): String {
                    tails++
                    val last = Json.parseToJsonElement(requestJson).jsonObject["shape"]!!.jsonArray.last().jsonObject
                    val atPlace = kotlin.math.abs(last["lat"]!!.jsonPrimitive.content.toDouble() - 40.0102) < 1e-6
                    val uses = if (atPlace) c.placeTail else listOf("road")
                    return """{"edges":[${uses.joinToString(",") { "{\"use\":\"$it\"}" }}]}"""
                }
            }
            val r = RoutePlanner(engine, presets).plan(RouteRequest(stops, "fastest")) as PlanResult.Ok
            assertEquals(c.end, r.ends.last().end, "$c")
            val dest = r.chosen.request["locations"]!!.jsonArray.last().jsonObject
            if (c.end == StopEnd.PLACE) {
                assertFalse("search_filter" in dest, "the place itself: no stop tie")
                assertEquals(TieCheckResult.UNTIED, r.tieCheck)
            } else {
                assertTrue("search_filter" in dest, "the nearest street, tied by rule 2")
                assertEquals(TieCheckResult.KEPT, r.tieCheck)
            }
            assertTrue(tails <= 2, "at most the place's tail and the kept end's tail: $tails")
        }
        // a map point has no list: no place end, nothing checked
        val pin = RoutePlanner(FakeEngine { trip(Triple(1, 0.1, false), Triple(5, 0.0, false)) }, presets)
            .plan(RouteRequest(listOf(stops[0], stops[1].copy(tied = false)), "fastest")) as PlanResult.Ok
        assertEquals(StopEnd.PIN, pin.ends.last().end)
        assertEquals(TieCheckResult.NOT_ASKED, pin.tieCheck)
    }

    @Test
    fun `recorded errors - Buffalo is outside, Lake Erie snaps 14 km away with a warning`() {
        val rec = Fixtures.json("reference/errors.json")["recordings"]!!.jsonArray.map { it.jsonObject }
        val engine = ReplayEngine(rec)
        val planner = RoutePlanner(engine, presets)
        fun stopsOf(id: String) = rec.first { it["id"]!!.jsonPrimitive.content == id }["request"]!!.jsonObject["locations"]!!.jsonArray
            .map { l -> Stop(LatLon(l.jsonObject["lat"]!!.jsonPrimitive.content.toDouble(), l.jsonObject["lon"]!!.jsonPrimitive.content.toDouble())) }

        val buffalo = planner.plan(RouteRequest(stopsOf("buffalo_ny"))) as PlanResult.Failed
        assertEquals(171, buffalo.problem.engineCode)
        assertTrue(buffalo.problem is RoutingProblem.OutsideInstalledMaps)

        val lake = planner.plan(RouteRequest(stopsOf("lake_erie"))) as PlanResult.Ok
        val w = lake.warnings.single() as RouteWarning.StopSnappedFar
        assertEquals(1, w.stopIndex)
        assertTrue(w.gapM in 14000.0..14200.0, "gap ${w.gapM}")
        assertEquals("The route reaches stop 2 8.8 mi from the point you chose.", w.userText)

        val jamestown = planner.plan(RouteRequest(stopsOf("jamestown_ny"))) as PlanResult.Ok
        assertEquals(1, jamestown.warnings.size, "5.6 km is over the 1 km threshold of presets.json")
    }
}
