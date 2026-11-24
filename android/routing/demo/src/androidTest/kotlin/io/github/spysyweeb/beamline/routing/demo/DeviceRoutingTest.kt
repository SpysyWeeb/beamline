package io.github.spysyweeb.beamline.routing.demo

import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.spysyweeb.beamline.routing.config.EngineConfig
import io.github.spysyweeb.beamline.routing.demo.DeviceTestSupport.asset
import io.github.spysyweeb.beamline.routing.demo.DeviceTestSupport.bannerCase
import io.github.spysyweeb.beamline.routing.demo.DeviceTestSupport.openEngine
import io.github.spysyweeb.beamline.routing.demo.DeviceTestSupport.place
import io.github.spysyweeb.beamline.routing.demo.DeviceTestSupport.reference
import io.github.spysyweeb.beamline.routing.demo.DeviceTestSupport.target
import io.github.spysyweeb.beamline.routing.demo.DeviceTestSupport.trip
import io.github.spysyweeb.beamline.routing.demo.DeviceTestSupport.tripIds
import io.github.spysyweeb.beamline.routing.demo.DeviceTestSupport.writeResult
import io.github.spysyweeb.beamline.routing.engine.EngineException
import io.github.spysyweeb.beamline.routing.mobile.ValhallaEngine
import io.github.spysyweeb.beamline.routing.model.LatLon
import io.github.spysyweeb.beamline.routing.model.RoadFlag
import io.github.spysyweeb.beamline.routing.model.RouteRequest
import io.github.spysyweeb.beamline.routing.model.Stop
import io.github.spysyweeb.beamline.routing.packs.TileTar
import io.github.spysyweeb.beamline.routing.planner.PlanResult
import io.github.spysyweeb.beamline.routing.planner.RoutePlanner
import io.github.spysyweeb.beamline.routing.planner.RouteWarning
import io.github.spysyweeb.beamline.routing.planner.RoutingProblem
import io.github.spysyweeb.beamline.routing.planner.TileCoverage
import io.github.spysyweeb.beamline.routing.preset.PresetSet
import io.github.spysyweeb.beamline.routing.valhalla.ValhallaRequests
import io.github.spysyweeb.beamline.routing.valhalla.ValhallaResponses
import io.github.spysyweeb.beamline.routing.valhalla.canonicalJson
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import kotlin.math.abs

/**
 * valhalla-mobile on the emulator, on the PA+OH tiles (the tile tar :routing:packs wrote from the
 * real packs), against the pyvalhalla reference recorded on the same tiles.
 */
@RunWith(AndroidJUnit4::class)
class DeviceRoutingTest {
    private val presets = PresetSet.bundled()

    /** The six presets of format 1 the old reference was recorded with (reference/presets-format1.json). */
    private val format1 by lazy { PresetSet.parseFormat1(DeviceTestSupport.assetText("reference/presets-format1.json")) }

    private fun f1(id: String) = format1.first { it.id == id }

    /** Presets format 2: every route type with every option set on the 21 trips, and the cases of 2.11. */
    @Test
    fun format2PlansMatchPyvalhalla() {
        val optionSets = DeviceTestSupport.plans.getJSONObject("option_sets").let { o ->
            o.keys().asSequence().associateWith { k -> o.getJSONArray(k).let { a -> (0 until a.length()).map { a.getString(it) }.toSet() } }
        }
        openEngine().use { engine ->
            val planner = RoutePlanner(engine, presets)
            val rows = JSONArray()
            var mismatches = 0
            fun compare(where: String, stops: List<Stop>, preset: io.github.spysyweeb.beamline.routing.preset.Preset, want: JSONObject) {
                val t0 = SystemClock.elapsedRealtimeNanos()
                val got = planner.plan(RouteRequest(stops), preset)
                val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
                val ok = got as? PlanResult.Ok
                val r = ok?.route
                val warning = ok?.warnings?.filterIsInstance<RouteWarning.OptionFallback>()?.singleOrNull()?.userText
                val same = r != null && abs(want.getDouble("time_s") - r.timeS) <= 0.051 &&
                    abs(want.getDouble("length_km") - r.lengthM / 1000) <= 0.0006 &&
                    want.getInt("maneuvers") == r.turnCount && want.getInt("legs") == r.legs.size &&
                    abs(want.getDouble("highway_km") - r.flaggedM(RoadFlag.HIGHWAY) / 1000) <= 0.006 &&
                    abs(want.getDouble("toll_km") - r.flaggedM(RoadFlag.TOLL) / 1000) <= 0.006 &&
                    want.getBoolean("has_toll") == r.hasToll &&
                    want.optBoolean("used_fallback", false) == ok.usedFallback &&
                    (if (want.has("fallback_warning")) want.getString("fallback_warning") else null) == warning
                if (!same) mismatches++
                rows.put(JSONObject().put("plan", where).put("same", same).put("time_s", r?.timeS).put("length_km", r?.let { it.lengthM / 1000 })
                    .put("ref_time_s", want.getDouble("time_s")).put("ref_length_km", want.getDouble("length_km"))
                    .put("warning", warning).put("plan_ms", ms).put("engine_calls", (got as? PlanResult.Ok)?.engineCalls))
            }
            val plans = DeviceTestSupport.plans.getJSONObject("plans")
            for (type in presets.routeTypes) for ((key, opts) in optionSets) for (id in tripIds()) {
                compare("${type.id}/$key $id", trip(id), presets.compose(type.id, opts), plans.getJSONObject(type.id).getJSONObject(key).getJSONObject(id))
            }
            val cases = DeviceTestSupport.plans.getJSONObject("cases")
            for (cid in cases.keys()) {
                val row = cases.getJSONObject(cid)
                val stopsJson = row.getJSONArray("stops")
                val stops = (0 until stopsJson.length()).map { DeviceTestSupport.stopOf(stopsJson.getJSONObject(it)) }
                for (run in row.keys().asSequence().filter { "/" in it }) {
                    val (type, key) = run.split("/")
                    compare("$cid $run", stops, presets.compose(type, optionSets.getValue(key)), row.getJSONObject(run))
                }
            }
            writeResult("plans-device.json", JSONObject().put("rows", rows).put("mismatches", mismatches).put("compared", rows.length()))
            assertEquals("plans compared", 21 * 4 * 4 + 11, rows.length())
            assertEquals("plans that differ from pyvalhalla", 0, mismatches)
        }
    }

    /** The old 126 plans: the 21 trips with the six presets of format 1. */
    @Test
    fun the21TripsWithSixPresetsMatchPyvalhalla() {
        openEngine().use { engine ->
            val planner = RoutePlanner(engine, presets)
            val rows = JSONArray()
            var mismatches = 0
            for (p in format1) for (id in tripIds()) {
                val want = reference.getJSONObject("presets").getJSONObject(p.id).getJSONObject(id)
                val t0 = SystemClock.elapsedRealtimeNanos()
                val got = planner.plan(RouteRequest(trip(id)), p)
                val ms = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6
                got as PlanResult.Ok
                val r = got.route
                val same = abs(want.getDouble("time_s") - r.timeS) <= 0.051 &&
                    abs(want.getDouble("length_km") - r.lengthM / 1000) <= 0.0006 &&
                    want.getInt("maneuvers") == r.turnCount && want.getInt("legs") == r.legs.size &&
                    abs(want.getDouble("highway_km") - r.flaggedM(RoadFlag.HIGHWAY) / 1000) <= 0.006
                if (!same) mismatches++
                rows.put(JSONObject().put("preset", p.id).put("trip", id).put("same", same)
                    .put("time_s", r.timeS).put("length_km", r.lengthM / 1000).put("maneuvers", r.turnCount)
                    .put("ref_time_s", want.getDouble("time_s")).put("ref_length_km", want.getDouble("length_km"))
                    .put("ref_maneuvers", want.getInt("maneuvers")).put("plan_ms", ms).put("engine_calls", got.engineCalls))
            }
            writeResult("trips-device.json", JSONObject().put("rows", rows).put("mismatches", mismatches))
            assertEquals("plans that differ from pyvalhalla", 0, mismatches)
        }
    }

    /** Alternates of the 21 two-stop trips with every preset, against reference/trips.json "alternates". */
    @Test
    fun alternatesOfTwoStopTripsMatchPyvalhalla() {
        openEngine().use { engine ->
            val planner = RoutePlanner(engine, presets)
            var compared = 0
            for (p in format1) for (id in tripIds()) {
                val want = reference.getJSONObject("alternates").getJSONObject(p.id).getJSONObject(id).getJSONArray("alternates")
                val got = planner.plan(RouteRequest(trip(id), withAlternates = true), p) as PlanResult.Ok
                assertSameAlternates(want, got.alternates, "${p.id} $id")
                compared++
            }
            assertEquals(6 * 21, compared)
        }
    }

    private fun assertSameAlternates(want: JSONArray, got: List<io.github.spysyweeb.beamline.routing.model.Route>, where: String) {
        assertEquals("$where alternates", want.length(), got.size)
        for (i in 0 until want.length()) {
            val w = want.getJSONObject(i)
            assertEquals("$where alt $i time", w.getDouble("time_s"), got[i].timeS, 0.051)
            assertEquals("$where alt $i length", w.getDouble("length_km"), got[i].lengthM / 1000, 0.0006)
            assertEquals("$where alt $i highway km", w.getDouble("highway_km"), got[i].flaggedM(RoadFlag.HIGHWAY) / 1000, 0.006)
        }
    }

    /** Trips that start on a motorway: Avoid highways' fallback and the alternates that go with it. */
    @Test
    fun motorwayOriginTripsMatchPyvalhalla() {
        val cases = reference.getJSONObject("motorway_origin")
        val out = JSONObject()
        openEngine().use { engine ->
            val planner = RoutePlanner(engine, presets)
            for (name in cases.keys()) {
                val row = cases.getJSONObject(name)
                val stopsJson = row.getJSONArray("stops")
                val stops = (0 until stopsJson.length()).map { DeviceTestSupport.stopOf(stopsJson.getJSONObject(it)) }
                for (pid in listOf("fastest", "avoid_highways")) {
                    val want = row.getJSONObject(pid)
                    val got = planner.plan(RouteRequest(stops, withAlternates = true), f1(pid)) as PlanResult.Ok
                    val where = "$name $pid"
                    assertEquals("$where time", want.getDouble("time_s"), got.route.timeS, 0.051)
                    assertEquals("$where highway km", want.getDouble("highway_km"), got.route.flaggedM(RoadFlag.HIGHWAY) / 1000, 0.006)
                    assertEquals("$where fallback", want.optBoolean("used_fallback", false), got.usedFallback)
                    assertSameAlternates(want.getJSONArray("alternates"), got.alternates, where)
                    out.put(where, JSONObject().put("used_fallback", got.usedFallback)
                        .put("highway_km", got.route.flaggedM(RoadFlag.HIGHWAY) / 1000)
                        .put("alternates_highway_km", JSONArray(got.alternates.map { it.flaggedM(RoadFlag.HIGHWAY) / 1000 })))
                }
            }
        }
        writeResult("motorway-origin-device.json", out)
    }

    /** Config keys reach the engine: each one is switched off in a variant config and the answer changes. */
    @Test
    fun configKeysReachTheEngine() {
        val out = JSONObject()
        val device = EngineConfig.build(DemoFiles.tiles(target).absolutePath)
        fun variant(name: String, vararg kv: Pair<String, JsonElement>): ValhallaEngine {
            var cfg: JsonObject = device
            for ((k, v) in kv) cfg = EngineConfig.set(cfg, k, v)
            val f = DemoFiles.config(target, "variant-$name.json")
            f.parentFile!!.mkdirs()
            f.writeText(cfg.toString())
            return ValhallaEngine.open(f)
        }
        assertEquals(emptyList<String>(), EngineConfig.mismatches(device, EngineConfig.bundledDeviceSettings()))

        // 1. hard exclusions (service_limits.allow_hard_exclusions): Harrisburg -> Hershey with exclude_highways
        val r1 = trip("R1")
        val excl = ValhallaRequests.route(r1, JsonObject(mapOf("exclude_highways" to JsonPrimitive(true)))).toString()
        val onRaw = openEngine().use { it.route(excl) }
        val on = ValhallaResponses.parseRoute(onRaw).main
        val offRaw = variant("no-hard-exclusions", "service_limits.allow_hard_exclusions" to JsonPrimitive(false)).use { it.route(excl) }
        val off = ValhallaResponses.parseRoute(offRaw).main
        fun warnings(raw: String) = JSONObject(raw).getJSONObject("trip").optJSONArray("warnings")
        out.put("hard_exclusions", JSONObject().put("device_highway_km", on.flaggedM(RoadFlag.HIGHWAY) / 1000)
            .put("device_warnings", warnings(onRaw))
            .put("without_key_highway_km", off.flaggedM(RoadFlag.HIGHWAY) / 1000)
            .put("without_key_warnings", warnings(offRaw)))
        assertEquals(0.0, on.flaggedM(RoadFlag.HIGHWAY), 0.0)
        assertEquals(null, warnings(onRaw))
        // Without the key loki warns 208 "Hard exclusions are not allowed on this server, ignoring hard
        // excludes". In 3.9.1 it still applies them: loki_worker.cc clears the flags on a copy
        // (`auto opts = pair.second.options();`), so the route is the same; the warning is the proof.
        assertEquals(208, warnings(offRaw)!!.getJSONObject(0).getInt("code"))

        // 2. CostMatrix settings: 1x40 along I1 (479 km), as reference/trips.json matrix_1x40
        val mreq = reference.getJSONObject("matrix_1x40").getJSONObject("request").toString()
        val devNulls = openEngine().use { ValhallaResponses.parseMatrix(it.matrix(mreq)).nulls }
        val defNulls = variant("matrix-defaults",
            "service_limits.auto.max_matrix_distance" to JsonPrimitive(400000.0),
            "thor.costmatrix.allow_second_pass" to JsonPrimitive(false)).use { ValhallaResponses.parseMatrix(it.matrix(mreq)).nulls }
        out.put("matrix_1x40_nulls", JSONObject().put("device", devNulls).put("valhalla_defaults", defNulls))
        assertEquals(reference.getJSONObject("matrix_1x40").getInt("nulls"), devNulls)
        assertTrue("default matrix settings leave null cells on a 479 km trip", defNulls > 0)

        // 3. service_limits.max_alternates: ask for 3
        val alt3 = ValhallaRequests.route(trip("I1"), JsonObject(emptyMap()), alternates = 3).toString()
        val devAlts = openEngine().use { ValhallaResponses.parseRoute(it.route(alt3)).alternates.size }
        val oneAlt = variant("one-alternate", "service_limits.max_alternates" to JsonPrimitive(1)).use {
            ValhallaResponses.parseRoute(it.route(alt3)).alternates.size
        }
        out.put("alternates_for_3", JSONObject().put("device_max_2", devAlts).put("max_1", oneAlt))
        assertEquals(2, devAlts)
        assertEquals(1, oneAlt)
        writeResult("config-keys.json", out)
    }

    @Test
    fun errorsBecomePlainCauses() {
        val out = JSONObject()
        openEngine().use { engine ->
            val planner = RoutePlanner(engine, presets)
            val buffalo = planner.plan(RouteRequest(listOf(Stop(place("erie_perry_sq")), Stop(LatLon(42.8864, -78.8784))))) as PlanResult.Failed
            assertTrue(buffalo.problem is RoutingProblem.OutsideInstalledMaps)
            assertEquals(171, buffalo.problem.engineCode)
            val lake = planner.plan(RouteRequest(listOf(Stop(place("erie_perry_sq")), Stop(LatLon(42.30, -80.05))))) as PlanResult.Ok
            val w = lake.warnings.single() as RouteWarning.StopSnappedFar
            assertTrue("gap ${w.gapM}", w.gapM in 14000.0..14200.0)
            out.put("buffalo", buffalo.problem.toString()).put("lake_erie", w.userText)
            // the raw engine error as valhalla-mobile reports it
            try { engine.route(ValhallaRequests.route(listOf(Stop(place("erie_perry_sq")), Stop(LatLon(42.8864, -78.8784))), JsonObject(emptyMap())).toString()) }
            catch (e: EngineException) { out.put("raw_171", e.message) }
        }
        val paOnly = DemoFiles.tilesPaOnly(target)
        if (paOnly.isFile) {
            val coverage = TileCoverage.fromTilePaths(TileTar.members(paOnly).map { it.name })
            ValhallaEngine.openTar(paOnly, DemoFiles.config(target, "pa-only.json")).use { engine ->
                val planner = RoutePlanner(engine, presets, coverage)
                val probes = DeviceTestSupport.trips.getJSONObject("pa_only_edge_probes")
                val pts = probes.getJSONArray("points")
                val rows = JSONArray()
                for (i in 0 until pts.length()) {
                    val p = pts.getJSONObject(i)
                    val r = planner.plan(RouteRequest(listOf(Stop(place(probes.getString("from"))), Stop(LatLon(p.getDouble("lat"), p.getDouble("lon"))))))
                    val got = when (r) {
                        is PlanResult.Ok -> "route"
                        is PlanResult.Failed -> r.problem.engineCode.toString()
                    }
                    rows.put(JSONObject().put("lon", p.getDouble("lon")).put("expect", p.getString("expect")).put("got", got)
                        .put("user_text", (r as? PlanResult.Failed)?.problem?.userText))
                    assertEquals("Sharon -> ${p.getDouble("lon")}", p.getString("expect"), got)
                    if (r is PlanResult.Failed) {
                        val prob = r.problem as RoutingProblem.OutsideInstalledMaps
                        assertEquals(listOf(1), prob.stopIndices)
                    }
                }
                out.put("pa_only_probes", rows)
            }
        }
        writeResult("errors-device.json", out)
        assumeTrue("PA-only tiles not pushed", paOnly.isFile)
    }

    /** The owner's exit 24 trip in OSRM format, for Ferrostar; kept for :routing:ferrostarcheck. */
    @Test
    fun osrmFormatForFerrostarEqualsPyvalhalla() {
        openEngine().use { engine ->
            val plan = RoutePlanner(engine, presets).plan(RouteRequest(bannerCase("exit24"))) as PlanResult.Ok
            val req = ValhallaRequests.osrm(plan.chosen.request)
            val body = engine.route(req.toString())
            val ref = asset("reference/exit24-osrm.json")
            val got = JSONObject(body)
            writeResult("exit24-osrm-device.json", JSONObject().put("meta", JSONObject().put("engine", "valhalla-mobile 0.6.4 (Valhalla 3.9.1) on the emulator"))
                .put("request", JSONObject(req.toString())).put("response", got))
            val a = ref.getJSONObject("response").getJSONArray("routes").getJSONObject(0)
            val b = got.getJSONArray("routes").getJSONObject(0)
            assertEquals(a.getDouble("distance"), b.getDouble("distance"), 0.001)
            assertEquals(a.getDouble("duration"), b.getDouble("duration"), 0.001)
            assertEquals(a.getString("geometry"), b.getString("geometry"))
            val sa = a.getJSONArray("legs").getJSONObject(0).getJSONArray("steps")
            val sb = b.getJSONArray("legs").getJSONObject(0).getJSONArray("steps")
            assertEquals(sa.length(), sb.length())
            fun canon(o: Any) = canonicalJson(Json.parseToJsonElement(o.toString()))
            for (i in 0 until sa.length()) {
                for (k in listOf("bannerInstructions", "voiceInstructions", "intersections", "maneuver")) {
                    assertEquals("step $i $k", canon(sa.getJSONObject(i).get(k)), canon(sb.getJSONObject(i).get(k)))
                }
            }
        }
    }

    /**
     * The banner trips route on the device as recorded with pyvalhalla (reference/banner-routes.json.gz):
     * same time, length and maneuvers (type and instruction), and the case's expected maneuver is there.
     */
    @Test
    fun bannerTripsEqualTheRecordings() {
        val cases = asset("banner_cases.json").getJSONArray("cases")
        // the APK packager stores .gz assets decompressed, without the .gz (testdata/reference/banner-routes.json.gz)
        val recorded = asset("reference/banner-routes.json").getJSONArray("recordings")
        assertEquals(cases.length(), recorded.length())
        openEngine().use { engine ->
            for (i in 0 until cases.length()) {
                val id = cases.getJSONObject(i).getString("id")
                val rec = recorded.getJSONObject(i)
                assertEquals(id, rec.getString("case"))
                val want = ValhallaResponses.parseRoute(rec.getJSONObject("response").toString()).main
                val r = ValhallaResponses.parseRoute(engine.route(ValhallaRequests.route(bannerCase(id), JsonObject(emptyMap())).toString())).main
                assertEquals("$id time", want.timeS, r.timeS, 0.051)
                assertEquals("$id length", want.lengthM, r.lengthM, 0.6)
                assertEquals("$id maneuvers", want.maneuvers.map { it.typeCode to it.instruction }, r.maneuvers.map { it.typeCode to it.instruction })
                assertEquals("$id lanes", want.maneuvers.map { m -> m.lanes?.map { it.state } }, r.maneuvers.map { m -> m.lanes?.map { it.state } })
                val instr = r.maneuvers.map { it.instruction }
                assertTrue("$id: ${cases.getJSONObject(i).getString("expect_instruction")} in $instr",
                    instr.any { it.contains(cases.getJSONObject(i).getString("expect_instruction")) })
            }
        }
    }
}
