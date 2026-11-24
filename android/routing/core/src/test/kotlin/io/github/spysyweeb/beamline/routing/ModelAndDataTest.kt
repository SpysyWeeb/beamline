package io.github.spysyweeb.beamline.routing

import io.github.spysyweeb.beamline.routing.config.EngineConfig
import io.github.spysyweeb.beamline.routing.model.Geo
import io.github.spysyweeb.beamline.routing.model.LaneBits
import io.github.spysyweeb.beamline.routing.model.LaneState
import io.github.spysyweeb.beamline.routing.model.LatLon
import io.github.spysyweeb.beamline.routing.model.ManeuverType
import io.github.spysyweeb.beamline.routing.model.RoadFlag
import io.github.spysyweeb.beamline.routing.planner.StopOrder
import io.github.spysyweeb.beamline.routing.planner.StopOrderer
import io.github.spysyweeb.beamline.routing.preset.PresetDefault
import io.github.spysyweeb.beamline.routing.preset.PresetSet
import io.github.spysyweeb.beamline.routing.preset.PresetValidator
import io.github.spysyweeb.beamline.routing.valhalla.ValhallaRequests
import io.github.spysyweeb.beamline.routing.valhalla.ValhallaResponses
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

class ModelAndDataTest {

    @Test
    fun `the owner's exit 24 trip parses into maneuvers, exit sign and lanes`() {
        val rec = Fixtures.json("reference/banner-routes.json.gz")["recordings"]!!.jsonArray.first().jsonObject
        assertEquals("exit24", rec["case"]!!.jsonPrimitive.content)
        val route = ValhallaResponses.parseRoute(rec["response"].toString()).main
        val m = route.maneuvers
        assertEquals(listOf(ManeuverType.START, ManeuverType.STAY_RIGHT, ManeuverType.LEFT, ManeuverType.RIGHT,
            ManeuverType.DESTINATION), m.map { it.type })
        val exit = m[1]
        assertTrue(exit.instruction.startsWith("Keep right to take exit 24"))
        assertEquals(listOf("24"), exit.sign!!.numbers)
        assertEquals(listOf("US 19", "Peach Street"), exit.sign!!.branches)
        assertEquals(listOf("Waterford"), exit.sign!!.towards)
        assertNull(exit.lanes, "no lane data for the exit")
        assertEquals(0.26, exit.lengthM / 1609.344, 0.005, "ramp 0.26 mi")

        val left = m[2]
        assertEquals(listOf("US 19", "Peach Street", "General Strong Vincent Memorial Highway"), left.streetNames)
        assertEquals(listOf(LaneBits.LEFT, LaneBits.LEFT or LaneBits.THROUGH, LaneBits.RIGHT), left.lanes!!.map { it.directions })
        assertEquals(listOf(LaneState.VALID, LaneState.ACTIVE, LaneState.NONE), left.lanes!!.map { it.state })
        assertEquals(0.22, left.lengthM / 1609.344, 0.005, "0.22 mi to the right turn")
        assertTrue(!left.multiCue, "Valhalla's own multi-cue does not chain the left and the right")

        val right = m[3]
        assertEquals("Turn right onto Downs Drive.", right.instruction)
        assertEquals(listOf(LaneBits.LEFT, LaneBits.THROUGH, LaneBits.THROUGH, LaneBits.THROUGH or LaneBits.RIGHT), right.lanes!!.map { it.directions })
        assertEquals(listOf(LaneState.NONE, LaneState.NONE, LaneState.NONE, LaneState.ACTIVE), right.lanes!!.map { it.state })
        assertEquals(LaneBits.RIGHT, right.lanes!![3].direction)
        assertEquals(listOf("left", "through"), LaneBits.describe(10))
    }

    @Test
    fun `polyline6 round trip and Valhalla tile ids`() {
        val pts = listOf(LatLon(42.12345, -80.08521), LatLon(42.1, -80.1), LatLon(-33.9, 151.2))
        val back = Geo.decodePolyline6(Geo.encodePolyline6(pts))
        pts.zip(back).forEach { (a, b) -> assertEquals(a.lat, b.lat, 1e-9); assertEquals(a.lon, b.lon, 1e-9) }
        // values from pyvalhalla 3.9.1 get_tile_id_from_lon_lat
        assertEquals(2994, Geo.tileId(LatLon(42.127, -80.0852), 0))
        assertEquals(47619, Geo.tileId(LatLon(42.127, -80.0852), 1))
        assertEquals(760719, Geo.tileId(LatLon(42.127, -80.0852), 2))
        assertEquals(747779, Geo.tileId(LatLon(39.9526, -75.1635), 2))
        assertEquals(754956, Geo.tileId(LatLon(41.2, -80.8), 2))
        assertEquals("0/002/904.gph", Geo.tilePath(0, 2904))
        assertEquals("1/046/953.gph", Geo.tilePath(1, 46953))
        assertEquals("2/000/747/779.gph", Geo.tilePath(2, 747779))
    }

    @Test
    fun `presets format 2 loads three route types and two options, and a typo is refused`() {
        val p = PresetSet.bundled()
        assertEquals("beamline-route-presets/2", PresetSet.FORMAT)
        assertEquals(listOf("fastest", "simpler", "eco"), p.routeTypes.map { it.id }, "Shortest left in batch D")
        assertEquals(listOf("Fastest", "Simpler", "Eco"), p.routeTypes.map { it.label })
        assertEquals(listOf("fastest", "simpler"), p.offered().map { it.id }, "Eco is not offered")
        assertEquals(listOf("avoid_highways", "avoid_tolls"), p.options.map { it.id })
        assertEquals(listOf("Avoid highways", "Avoid tolls"), p.options.map { it.label })
        assertEquals(PresetDefault("fastest", emptySet()), p.default)
        assertTrue(p.type("simpler").isPool)
        assertEquals(4, p.type("simpler").candidates.size)
        assertEquals(0.10, p.type("simpler").maxTimeIncrease)
        assertEquals(600.0, p.type("simpler").maxTimeExtraS)
        assertTrue(p.routeTypes.all { it.optionFallbackWhen }, "no type sets option_fallback_when false since batch D")
        assertEquals(500.0, p.options[0].fallbackWhen.values.single())
        assertEquals(setOf(RoadFlag.HIGHWAY), p.options[0].avoids)
        assertEquals(setOf(RoadFlag.TOLL), p.options[1].avoids)
        assertEquals(1000.0, p.checks.warnIfEndpointGapMOver)
        assertEquals(2, p.maxAlternates)
        assertEquals("3.9.1", p.valhallaVersion)
        fun opts(s: String) = Json.parseToJsonElement(s).jsonObject
        assertEquals(1, PresetValidator.validateOptions(opts("""{"use_distnace": 0.5}""")).size)
        assertEquals(1, PresetValidator.validateOptions(opts("""{"use_distance": 5}""")).size)
        assertEquals(1, PresetValidator.validateOptions(opts("""{"use_tolls": "x"}""")).size)
        assertEquals(1, PresetValidator.validateOptions(opts("""{"exclude_highways": "yes"}""")).size)
        assertEquals(0, PresetValidator.validateOptions(opts("""{"use_highways": 0.0, "shortest": true}""")).size)
        assertThrows(IllegalArgumentException::class.java) {
            PresetSet.parse(resourcePresets().replace("\"use_distance\": 0.5", "\"use_distnace\": 0.5"))
        }
        // route_lib applies request_options; the Kotlin planner does not, so the key is refused
        val e = assertThrows(IllegalArgumentException::class.java) {
            PresetSet.parse(resourcePresets().replace("\"costing_options\": {\"use_distance\": 0.5}",
                "\"costing_options\": {\"use_distance\": 0.5}, \"request_options\": {\"date_time\": {\"type\": 0}}"))
        }
        assertTrue(e.message!!.contains("eco: request_options is not supported"), e.message)
        // a format-1 file is refused by the format-2 parser
        assertThrows(IllegalArgumentException::class.java) { PresetSet.parse(Fixtures.text("reference/presets-format1.json")) }
    }

    private fun resourcePresets() = PresetSet::class.java.getResourceAsStream("/io/github/spysyweeb/beamline/routing/presets.json")!!
        .readBytes().toString(Charsets.UTF_8)

    @Test
    fun `stop order by brute force equals the Python reference and is no worse than optimized_route`() {
        val ref = Fixtures.json("reference/reorder.json")
        val matrix = ValhallaResponses.parseMatrix(ref["response"].toString())
        assertEquals(0, matrix.nulls)
        for ((mode, keepLast) in listOf("keep_last" to true, "free_last" to false)) {
            val got = StopOrderer.bestOrder(matrix.timesS, keepLast)!!
            val want = ref["brute_force"]!!.jsonObject[mode]!!.jsonObject
            assertEquals(want["order"]!!.jsonArray.map { it.jsonPrimitive.int }, got.order, mode)
            assertEquals(want["time_s"]!!.jsonPrimitive.double, got.timeS, 0.01, mode)
            assertEquals(if (keepLast) 720L else 5040L, got.permutationsTried)
        }
        val opt = ref["optimized_route"]!!.jsonObject["matrix_time_s"]!!.jsonPrimitive.double
        assertTrue(StopOrderer.bestOrder(matrix.timesS, true)!!.timeS <= opt + 0.01)
        // a missing connection is never used
        val t = listOf(listOf(0.0, 1.0, 5.0), listOf(1.0, 0.0, null), listOf(5.0, 1.0, 0.0))
        assertEquals(listOf(0, 2, 1), StopOrderer.bestOrder(t, keepLast = false)!!.order)
    }

    @Test
    fun `stop order - no connected order is null, small inputs, and the caps`() {
        // review finding: when no order connects every stop the identity order came back as if it worked
        val cut = listOf(listOf(0.0, null, null), listOf(null, 0.0, 1.0), listOf(null, 1.0, 0.0))
        assertEquals(null, StopOrderer.bestOrder(cut, keepLast = true))
        assertEquals(null, StopOrderer.bestOrder(cut, keepLast = false))
        assertEquals(StopOrder(listOf(0), 0.0, 1), StopOrderer.bestOrder(listOf(listOf(0.0)), keepLast = false))
        assertEquals(StopOrder(listOf(0, 1), 7.0, 1), StopOrderer.bestOrder(listOf(listOf(0.0, 7.0), listOf(7.0, 0.0)), keepLast = true))
        assertEquals(null, StopOrderer.bestOrder(listOf(listOf(0.0, null), listOf(7.0, 0.0)), keepLast = true))
        // caps: 8! orders at most, i.e. 10 stops with the last kept, 9 with a free end
        fun full(n: Int) = List(n) { i -> List(n) { j -> if (i == j) 0.0 else 1.0 + i * 0.1 + j * 0.01 } }
        assertEquals(StopOrderer.MAX_ORDERS, StopOrderer.bestOrder(full(10), keepLast = true)!!.permutationsTried)
        assertEquals(StopOrderer.MAX_ORDERS, StopOrderer.bestOrder(full(9), keepLast = false)!!.permutationsTried)
        assertThrows(IllegalArgumentException::class.java) { StopOrderer.bestOrder(full(10), keepLast = false) }
        assertThrows(IllegalArgumentException::class.java) { StopOrderer.bestOrder(full(11), keepLast = true) }
    }

    @Test
    fun `engine config holds every device setting and no absolute path`() {
        val base = EngineConfig.bundledBase()
        assertEquals(emptyList<String>(), EngineConfig.absolutePaths(base))
        val settings = EngineConfig.bundledDeviceSettings()
        for (k in listOf("service_limits.allow_hard_exclusions", "service_limits.max_alternates",
            "service_limits.auto.max_matrix_distance", "thor.source_to_target_algorithm", "thor.costmatrix.allow_second_pass")) {
            assertTrue(k in settings, k)
        }
        val cfg = EngineConfig.build("/data/user/0/x/files/routing/routing.tar")
        assertEquals(emptyList<String>(), EngineConfig.mismatches(cfg, settings))
        assertEquals("/data/user/0/x/files/routing/routing.tar", EngineConfig.get(cfg, "mjolnir.tile_extract")!!.jsonPrimitive.content)
        assertEquals("", EngineConfig.get(cfg, "mjolnir.tile_url")!!.jsonPrimitive.content)
        assertEquals(1, EngineConfig.mismatches(EngineConfig.set(cfg, "thor.costmatrix.allow_second_pass", JsonPrimitive(false)), settings).size)
    }

    @Test
    fun `OSRM request for Ferrostar keeps the route request's path options`() {
        val stops = listOf(io.github.spysyweeb.beamline.routing.model.Stop(LatLon(42.0, -80.0)), io.github.spysyweeb.beamline.routing.model.Stop(LatLon(42.1, -80.0)))
        val req = ValhallaRequests.route(stops, Json.parseToJsonElement("""{"use_tolls":0.0}""").jsonObject, alternates = 2)
        val osrm = ValhallaRequests.osrm(req)
        assertEquals("osrm", osrm["format"]!!.jsonPrimitive.content)
        assertEquals(true, osrm["banner_instructions"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(true, osrm["voice_instructions"]!!.jsonPrimitive.content.toBoolean())
        assertEquals(req["costing_options"], osrm["costing_options"])
        assertEquals(req["locations"], osrm["locations"])
        assertEquals(req["alternates"], osrm["alternates"])
        assertTrue("turn_lanes" !in osrm)
        // the recorded one (pyvalhalla) has what Ferrostar's parser reads
        val o = Fixtures.json("reference/exit24-osrm.json")["response"]!!.jsonObject
        assertEquals("Ok", o["code"]!!.jsonPrimitive.content)
        val steps = o["routes"]!!.jsonArray[0].jsonObject["legs"]!!.jsonArray[0].jsonObject["steps"]!!.jsonArray
        assertEquals(5, steps.size)
        assertTrue(steps.all { "bannerInstructions" in it.jsonObject && "voiceInstructions" in it.jsonObject && "intersections" in it.jsonObject })
    }

    /** The copies in the Android project must equal their source in packbuilder/ (sync_data.py). */
    @Test
    fun `data files equal their packbuilder source`() {
        val root = System.getProperty("beamline.repoRoot")?.let(::File)
        assumeTrue(root != null && File(root, "packbuilder/routing/presets.json").exists(), "repo root not available")
        val pairs = listOf(
            "packbuilder/routing/presets.json" to "android/routing/core/src/main/resources/io/github/spysyweeb/beamline/routing/presets.json",
            "packbuilder/routing/device_config.json" to "android/routing/core/src/main/resources/io/github/spysyweeb/beamline/routing/device_config.json",
            "packbuilder/routing/banner.json" to "android/routing/banner/src/main/resources/io/github/spysyweeb/beamline/routing/banner/banner.json",
            "packbuilder/routing/trips.json" to "android/routing/testdata/trips.json",
        )
        for ((src, copy) in pairs) {
            assertTrue(File(root, src).readBytes().contentEquals(File(root, copy).readBytes()),
                "$copy differs from $src: run python android/routing/scripts/sync_data.py")
        }
    }
}
