package io.github.spysyweeb.beamline.routing

import io.github.spysyweeb.beamline.routing.engine.EngineException
import io.github.spysyweeb.beamline.routing.engine.RoutingEngine
import io.github.spysyweeb.beamline.routing.model.Geo
import io.github.spysyweeb.beamline.routing.model.LatLon
import io.github.spysyweeb.beamline.routing.model.RoadFlag
import io.github.spysyweeb.beamline.routing.model.RouteRequest
import io.github.spysyweeb.beamline.routing.model.Stop
import io.github.spysyweeb.beamline.routing.planner.PlanResult
import io.github.spysyweeb.beamline.routing.planner.RoutePlanner
import io.github.spysyweeb.beamline.routing.planner.RouteWarning
import io.github.spysyweeb.beamline.routing.planner.RoutingProblem
import io.github.spysyweeb.beamline.routing.planner.StopOrderer
import io.github.spysyweeb.beamline.routing.preset.PresetSet
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Presets format 2 (docs/architecture.md 2.11): composition, its refusals, and the planner rules
 * that are new in format 2 (no-route fallback for every type with its warning, a type without
 * the fallback_when re-run, the pool fallback) and batch D's Simpler window, on a scripted engine. The same rules in Python:
 * packbuilder/routing/test_presets.py. On real routes: PlansReferenceTest.
 */
class PresetsFormat2Test {
    private val presets = PresetSet.bundled()
    private val format1 = PresetSet.parseFormat1(Fixtures.text("reference/presets-format1.json")).associateBy { it.id }

    private fun json(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `compose with no options equals format 1's preset of the same id`() {
        // Simpler changed in batch D (window, cap, a fourth candidate); Shortest left
        for (id in listOf("fastest", "eco")) {
            val c = presets.compose(id, emptySet())
            assertTrue(c.sameRun(format1.getValue(id)), "$id: $c")
            assertEquals(id, c.id)
            assertEquals(emptyList<String>(), c.optionIds)
            assertEquals(emptySet<RoadFlag>(), c.avoids)
        }
    }

    @Test
    fun `fastest with Avoid highways equals format 1's avoid_highways`() {
        val c = presets.compose("fastest", setOf("avoid_highways"))
        assertTrue(c.sameRun(format1.getValue("avoid_highways")), "$c")
        assertEquals("fastest+avoid_highways", c.id)
        assertEquals(setOf(RoadFlag.HIGHWAY), c.avoids)
        // and the legacy name resolves to it
        assertEquals(c, presets.resolve("avoid_highways"))
        assertEquals(c, presets.resolve("fastest+avoid_highways"))
    }

    @Test
    fun `Avoid tolls is exclude_tolls for every type with use_tolls 0 as its fallback`() {
        for (t in presets.routeTypes.filter { !it.isPool }) {
            val c = presets.compose(t.id, setOf("avoid_tolls"))
            assertEquals("true", c.costingOptions["exclude_tolls"]!!.jsonPrimitive.content, t.id)
            assertEquals("0.0", c.fallbackCostingOptions!!["use_tolls"]!!.jsonPrimitive.content, t.id)
            assertFalse("exclude_tolls" in c.fallbackCostingOptions!!, t.id)
            assertTrue(c.fallbackWhen.isEmpty(), "${t.id}: Avoid tolls has no fallback_when")
        }
        val e = presets.compose("eco", setOf("avoid_tolls"))
        assertEquals(json("""{"use_distance":0.5,"exclude_tolls":true}"""), e.costingOptions)
        assertEquals(json("""{"use_distance":0.5,"use_tolls":0.0}"""), e.fallbackCostingOptions)
    }

    /** No type sets option_fallback_when false since Shortest left (batch D); the format still allows it. */
    private val noFallbackWhen = PresetSet.parse(PresetSet::class.java.getResourceAsStream("/io/github/spysyweeb/beamline/routing/presets.json")!!.bufferedReader().readText()
        .replace("\"offered\": false, \"costing\": \"auto\"", "\"offered\": false, \"option_fallback_when\": false, \"costing\": \"auto\""))

    @Test
    fun `a type with option_fallback_when false has no fallback_when, the other types unite theirs`() {
        val s = noFallbackWhen.compose("eco", setOf("avoid_highways"))
        assertTrue(s.fallbackWhen.isEmpty())
        assertEquals(json("""{"use_distance":0.5,"use_highways":0.0}"""), s.fallbackCostingOptions)
        val both = presets.compose("fastest", setOf("avoid_tolls", "avoid_highways"))
        assertEquals("fastest+avoid_highways+avoid_tolls", both.id, "options in file order")
        assertEquals(mapOf(RoadFlag.HIGHWAY to 500.0), both.fallbackWhen)
        assertEquals(json("""{"exclude_highways":true,"exclude_tolls":true}"""), both.costingOptions)
        assertEquals(json("""{"use_highways":0.0,"use_tolls":0.0}"""), both.fallbackCostingOptions)
        assertEquals(setOf(RoadFlag.HIGHWAY, RoadFlag.TOLL), both.avoids)
    }

    @Test
    fun `a pool type gets the options on every candidate and a fallback pool`() {
        val c = presets.compose("simpler", setOf("avoid_tolls"))
        assertEquals(listOf(json("""{"exclude_tolls":true}"""), json("""{"maneuver_penalty":60,"exclude_tolls":true}"""),
            json("""{"maneuver_penalty":300,"exclude_tolls":true}"""),
            json("""{"use_highways":1.0,"maneuver_penalty":60,"exclude_tolls":true}""")), c.candidates)
        assertEquals(listOf(json("""{"use_tolls":0.0}"""), json("""{"maneuver_penalty":60,"use_tolls":0.0}"""),
            json("""{"maneuver_penalty":300,"use_tolls":0.0}"""),
            json("""{"use_highways":1.0,"maneuver_penalty":60,"use_tolls":0.0}""")), c.fallbackCandidates)
        assertEquals(0.10, c.maxTimeIncrease)
        assertEquals(600.0, c.maxTimeExtraS)
        assertEquals(json("""{"use_highways":0.0,"maneuver_penalty":60}"""), presets.compose("simpler", setOf("avoid_highways")).fallbackCandidates[3],
            "the option's fallback wins over the candidate's use_highways")
        assertNull(c.fallbackCostingOptions)
        assertEquals(mapOf(RoadFlag.HIGHWAY to 500.0), presets.compose("simpler", setOf("avoid_highways")).fallbackWhen)
    }

    @Test
    fun `refusals - unknown names, two options setting one key, a default that is not offered`() {
        assertThrows(IllegalArgumentException::class.java) { presets.compose("quickest", emptySet()) }
        assertThrows(IllegalArgumentException::class.java) { presets.compose("fastest", setOf("avoid_ferries")) }
        val text = resourcePresets()
        val clash = assertThrows(IllegalArgumentException::class.java) {
            PresetSet.parse(text.replace("\"costing_options\": {\"exclude_tolls\": true}", "\"costing_options\": {\"exclude_tolls\": true, \"exclude_highways\": true}"))
        }
        assertTrue(clash.message!!.contains("options avoid_highways and avoid_tolls both set [exclude_highways]"), clash.message)
        val notOffered = assertThrows(IllegalArgumentException::class.java) {
            PresetSet.parse(text.replace("\"default\": {\"route_type\": \"fastest\"", "\"default\": {\"route_type\": \"eco\""))
        }
        assertTrue(notOffered.message!!.contains("not an offered type"), notOffered.message)
        val badOffered = assertThrows(IllegalArgumentException::class.java) {
            PresetSet.parse(text.replace("\"offered\": false", "\"offered\": \"no\""))
        }
        assertTrue(badOffered.message!!.contains("offered must be true or false"), badOffered.message)
    }

    private fun resourcePresets() = PresetSet::class.java.getResourceAsStream("/io/github/spysyweeb/beamline/routing/presets.json")!!
        .readBytes().toString(Charsets.UTF_8)

    // ---------------------------------------------------------------- the planner with format 2

    private class FakeEngine(val answer: (JsonObject) -> String) : RoutingEngine {
        val requests = mutableListOf<JsonObject>()
        var matrices = mutableListOf<JsonObject>()
        var matrixAnswer: (JsonObject) -> String = { throw UnsupportedOperationException() }
        override fun route(requestJson: String): String {
            val r = Json.parseToJsonElement(requestJson).jsonObject
            requests += r
            return answer(r)
        }
        override fun matrix(requestJson: String): String {
            val r = Json.parseToJsonElement(requestJson).jsonObject
            matrices += r
            return matrixAnswer(r)
        }
    }

    private val shape = Geo.encodePolyline6(listOf(LatLon(40.0, -77.0), LatLon(40.01, -77.0)))

    /** A minimal Valhalla answer: one leg; maneuvers (type, km, "highway"/"toll" flags). */
    private fun trip(vararg m: Pair<Int, Pair<Double, Set<String>>>, time: Double = 600.0): String {
        val km = m.sumOf { it.second.first }
        val mans = m.joinToString(",") { (t, rest) ->
            val flags = rest.second.joinToString("") { ",\"$it\":true" }
            """{"type":$t,"instruction":"x","length":${rest.first},"time":10,"begin_shape_index":0,"end_shape_index":1$flags}"""
        }
        val hasToll = m.any { "toll" in it.second.second }
        return """{"trip":{"locations":[{"lat":40.0,"lon":-77.0},{"lat":40.01,"lon":-77.0}],"units":"kilometers",
            "legs":[{"maneuvers":[$mans],"summary":{"length":$km,"time":$time},"shape":"$shape"}],
            "summary":{"length":$km,"time":$time,"has_toll":$hasToll}}}"""
    }

    private fun man(type: Int, km: Double, vararg flags: String) = type to (km to flags.toSet())

    private fun auto(req: JsonObject) = req["costing_options"]!!.jsonObject["auto"]!!.jsonObject

    private val stops = listOf(Stop(LatLon(40.0, -77.0)), Stop(LatLon(40.01, -77.0)))

    @Test
    fun `no route with the options - the fallback plans it for every type and the route says so`() {
        // a destination on a toll road: every request with exclude_tolls finds no path (Valhalla 442)
        for (type in presets.routeTypes) {
            val engine = FakeEngine { req ->
                if ("exclude_tolls" in auto(req)) throw EngineException(442, "No path could be found for input")
                trip(man(1, 0.1), man(10, 2.0, "toll"), man(4, 0.0))
            }
            val r = RoutePlanner(engine, presets).plan(RouteRequest(stops), presets.compose(type.id, setOf("avoid_tolls")))
            assertTrue(r is PlanResult.Ok, "${type.id}: $r")
            r as PlanResult.Ok
            assertTrue(r.usedFallback && r.noRouteFallback, type.id)
            val w = r.warnings.filterIsInstance<RouteWarning.OptionFallback>().single()
            assertEquals("No route to this place avoids toll roads. This route uses them.", w.userText)
            assertEquals(setOf(RoadFlag.TOLL), w.used)
            // batch E2: a pool type's plan first decides the end with Fastest (its request and fallback)
            // (Eco, not Fastest, also asks the decision's two: 4)
            assertEquals(if (type.isPool) 12 else if (type.id == "fastest") 4 else 6, engine.requests.size, type.id)
        }
    }

    @Test
    fun `the fallback that also fails is the engine's problem, not a route`() {
        val engine = FakeEngine { throw EngineException(442, "No path could be found for input") }
        val r = RoutePlanner(engine, presets).plan(RouteRequest(stops), presets.compose("simpler", setOf("avoid_tolls")))
        assertTrue((r as PlanResult.Failed).problem is RoutingProblem.NoRoute)
        assertEquals(12, r.engineCalls, "the decision's Fastest and its fallback to the nearest street and to the place (batch H), the pool and the fallback pool")
    }

    @Test
    fun `warning texts name what the route uses`() {
        val both = setOf(RoadFlag.HIGHWAY, RoadFlag.TOLL)
        assertEquals("No route to this place avoids highways. This route uses them.", RouteWarning.OptionFallback.text(both, setOf(RoadFlag.HIGHWAY)))
        assertEquals("No route to this place avoids highways and toll roads. This route uses them.", RouteWarning.OptionFallback.text(both, both))
        assertEquals("No route to this place avoids highways and toll roads.", RouteWarning.OptionFallback.text(both, emptySet()))
        // a fallback_when re-run carries no warning (format 1 behaviour)
        val engine = FakeEngine { req ->
            if ("exclude_highways" in auto(req)) trip(man(1, 0.1), man(10, 5.0, "highway"), man(4, 0.0))
            else trip(man(1, 0.1), man(10, 0.4, "highway"), man(15, 6.0), man(4, 0.0), time = 900.0)
        }
        val r = RoutePlanner(engine, presets).plan(RouteRequest(stops), presets.compose("fastest", setOf("avoid_highways"))) as PlanResult.Ok
        assertTrue(r.usedFallback)
        assertFalse(r.noRouteFallback)
        assertTrue(r.warnings.isEmpty())
    }

    @Test
    fun `a type with option_fallback_when false skips the fallback_when re-run`() {
        val engine = FakeEngine { trip(man(1, 0.1), man(10, 5.0, "highway"), man(4, 0.0)) }
        val r = RoutePlanner(engine, noFallbackWhen).plan(RouteRequest(stops), noFallbackWhen.compose("eco", setOf("avoid_highways"))) as PlanResult.Ok
        assertFalse(r.usedFallback)
        assertEquals(5, engine.requests.size, "the decision's Fastest and its fallback_when re-run to the nearest street and to the place (batch H), then Eco's own route with no re-run")
        assertEquals("true", auto(engine.requests.last())["exclude_highways"]!!.jsonPrimitive.content)
    }

    @Test
    fun `a pool runs again with the fallback options past a fallback_when limit and keeps less motorway`() {
        val engine = FakeEngine { req ->
            if ("exclude_highways" in auto(req)) trip(man(1, 0.1), man(10, 5.0, "highway"), man(4, 0.0), time = 600.0)
            else trip(man(1, 0.1), man(10, 0.2, "highway"), man(15, 6.0), man(4, 0.0), time = 650.0)
        }
        val r = RoutePlanner(engine, presets).plan(RouteRequest(stops), presets.compose("simpler", setOf("avoid_highways"))) as PlanResult.Ok
        assertTrue(r.usedFallback)
        assertFalse(r.noRouteFallback)
        assertEquals(11, engine.requests.size, "the decision's Fastest and its fallback to the nearest street and to the place (batches E2, H), a pool of four, a fallback pool of three "
            + "(with use_highways 0 merged on top the fourth candidate is the second one, asked once)")
        assertEquals("2", engine.requests[8]["alternates"]!!.jsonPrimitive.content, "the fallback pool's first request asks for alternates")
        assertEquals(200.0, r.route.flaggedM(RoadFlag.HIGHWAY), 1e-6)
        assertEquals(setOf("use_highways"), auto(r.chosen.request).keys - PresetSet.bundled().everyRequest.costingOptions.keys)
    }

    @Test
    fun `a pool keeps its first route when the fallback pool has no less motorway`() {
        val engine = FakeEngine { trip(man(1, 0.1), man(10, 5.0, "highway"), man(4, 0.0)) }
        val r = RoutePlanner(engine, presets).plan(RouteRequest(stops), presets.compose("simpler", setOf("avoid_highways"))) as PlanResult.Ok
        assertFalse(r.usedFallback, "tie: the first")
        assertTrue("exclude_highways" in auto(r.chosen.request))
    }

    @Test
    fun `best order asks the fallback matrix when the options leave a stop unconnected`() {
        val engine = FakeEngine { error("no route calls") }
        // with exclude_tolls stop 2 cannot be reached from anywhere; with use_tolls 0 it can
        engine.matrixAnswer = { req ->
            val excl = "exclude_tolls" in auto(req)
            val rows = (0 until 4).joinToString(",") { i ->
                (0 until 4).joinToString(",", "[", "]") { j ->
                    if (excl && j == 2 && i != 2) "{}" else """{"time":${if (i == j) 0 else 10 + i * 3 + j},"distance":1.0}"""
                }
            }
            """{"units":"kilometers","sources_to_targets":[$rows]}"""
        }
        val pts = List(4) { LatLon(40.0 + it * 0.01, -77.0) }
        val order = StopOrderer(engine, presets).bestOrder(pts, keepLast = true, presets.compose("fastest", setOf("avoid_tolls")))
        assertEquals(2, engine.matrices.size)
        assertTrue("exclude_tolls" in auto(engine.matrices[0]))
        assertTrue("use_tolls" in auto(engine.matrices[1]))
        assertEquals(0, order!!.order.first())
        assertEquals(3, order.order.last(), "the destination stays last")
        assertEquals(setOf(0, 1, 2, 3), order.order.toSet())
        // without a fallback (no options) a missing connection is simply no order
        val plain = FakeEngine { error("no route calls") }
        plain.matrixAnswer = {
            """{"units":"kilometers","sources_to_targets":[[{"time":0},{},{"time":5}],[{"time":5},{"time":0},{"time":5}],[{"time":5},{"time":5},{"time":0}]]}"""
        }
        assertNull(StopOrderer(plain, presets).bestOrder(pts.take(3), keepLast = true, presets.compose("fastest", emptySet())))
        assertEquals(1, plain.matrices.size)
    }

    /** Batch E (docs/next-round.md item 18): every_request.lot as route_lib reads it. */
    @Test
    fun `every_request lot is read and checked like route_lib`() {
        val lot = presets.everyRequest.lot!!
        assertEquals(listOf("driveway", "parking_aisle"), lot.uses)
        assertEquals(80, lot.withinM)
        assertEquals(10, lot.streetMarginM)
        val every = Json.parseToJsonElement(PresetSet::class.java.getResourceAsStream("/io/github/spysyweeb/beamline/routing/presets.json")!!
            .readBytes().toString(Charsets.UTF_8)).jsonObject["every_request"]!!.jsonObject
        val bad = JsonObject(every + ("lot" to Json.parseToJsonElement("""{"within_m": 80, "uses": ["driveway", "alley"], "street_margin_m": 10, "probe_step_m": 10, "probe_within_m": 30}""")))
        val e = assertThrows(IllegalArgumentException::class.java) { io.github.spysyweeb.beamline.routing.preset.EveryRequest.parse(bad) }
        assertTrue("lot uses" in e.message!!, e.message)
    }

    /** Item 28 (docs/next-round.md): every_request.lot.door_uses, the uses around a place's door. */
    @Test
    fun `every_request lot door_uses is read and checked like route_lib`() {
        val parse = io.github.spysyweeb.beamline.routing.preset.EveryRequest::parse
        assertEquals(listOf("driveway", "parking_aisle", "service_road"), presets.everyRequest.lot!!.doorUses)
        val every = Json.parseToJsonElement(PresetSet::class.java.getResourceAsStream("/io/github/spysyweeb/beamline/routing/presets.json")!!
            .readBytes().toString(Charsets.UTF_8)).jsonObject["every_request"]!!.jsonObject
        val lot = """"within_m": 80, "uses": ["driveway", "parking_aisle"], "street_margin_m": 10, "probe_step_m": 10, "probe_within_m": 30"""
        val bad = JsonObject(every + ("lot" to Json.parseToJsonElement("""{$lot, "door_uses": ["driveway", "alley"]}""")))
        val e = assertThrows(IllegalArgumentException::class.java) { parse(bad) }
        assertTrue("lot door_uses" in e.message!!, e.message)
        // a file without door_uses (presets before 2026-11-21): the lot uses
        val old = parse(JsonObject(every + ("lot" to Json.parseToJsonElement("{$lot}"))))
        assertEquals(listOf("driveway", "parking_aisle"), old.lot!!.doorUses)
        // the walk a lot by the door saves (tie_check.door_walk_m_per_s)
        assertEquals(1.4, presets.everyRequest.tieCheck!!.doorWalkMPerS)
        val tie = """"max_extra_s": 90, "tail_m": 300, "never_uses": ["alley"]"""
        assertEquals(null, parse(JsonObject(every + ("tie_check" to Json.parseToJsonElement("{$tie}")))).tieCheck!!.doorWalkMPerS)
        val badWalk = JsonObject(every + ("tie_check" to Json.parseToJsonElement("""{$tie, "door_walk_m_per_s": 0}""")))
        assertTrue("door_walk_m_per_s" in assertThrows(IllegalArgumentException::class.java) { parse(badWalk) }.message!!)
    }

    /** Batch E2 (docs/next-round.md item 19): every_request.left_out and the ends' check as route_lib reads them. */
    @Test
    fun `every_request left_out and the ends' check are read and checked like route_lib`() {
        val out = presets.everyRequest.leftOut!!
        assertEquals(listOf("drive_through", "alley"), out.uses)
        assertEquals(20, out.maxRings, "5 vertices a ring, the app's engine allows 100")
        val tie = presets.everyRequest.tieCheck!!
        assertEquals(300, tie.tailM)
        assertEquals(listOf("alley", "drive_through"), tie.neverUses)
        val every = Json.parseToJsonElement(PresetSet::class.java.getResourceAsStream("/io/github/spysyweeb/beamline/routing/presets.json")!!
            .readBytes().toString(Charsets.UTF_8)).jsonObject["every_request"]!!.jsonObject
        val bad = JsonObject(every + ("left_out" to Json.parseToJsonElement("""{"uses": ["drive_through"], "within_m": 80, "box_half_m": 2, "clear_m": 3, "max_rings": 21}""")))
        val e = assertThrows(IllegalArgumentException::class.java) { io.github.spysyweeb.beamline.routing.preset.EveryRequest.parse(bad) }
        assertTrue("max_rings" in e.message!!, e.message)
    }
}
