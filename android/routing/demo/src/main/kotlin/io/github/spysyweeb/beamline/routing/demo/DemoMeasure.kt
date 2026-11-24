package io.github.spysyweeb.beamline.routing.demo

import android.content.Context
import android.os.Debug
import android.os.SystemClock
import io.github.spysyweeb.beamline.routing.mobile.ValhallaEngine
import io.github.spysyweeb.beamline.routing.model.LatLon
import io.github.spysyweeb.beamline.routing.model.RouteRequest
import io.github.spysyweeb.beamline.routing.model.Stop
import io.github.spysyweeb.beamline.routing.planner.PlanResult
import io.github.spysyweeb.beamline.routing.planner.RoutePlanner
import io.github.spysyweeb.beamline.routing.planner.StopOrderer
import io.github.spysyweeb.beamline.routing.preset.PresetSet
import io.github.spysyweeb.beamline.routing.valhalla.ValhallaRequests
import io.github.spysyweeb.beamline.routing.valhalla.ValhallaResponses
import kotlinx.serialization.json.JsonObject
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Latency and memory of the routing modules in this process. Called by the instrumented MeasureTest
 * (debug build) and by DemoActivity with the extra `measure` (release build, R8, not debuggable),
 * started in a fresh process for every run by android/routing/scripts/device.py measure.
 * Trips come from files/testdata (trips.json and reference/trips.json, pushed by device.py push).
 */
object DemoMeasure {
    private fun ms(t0: Long) = (SystemClock.elapsedRealtimeNanos() - t0) / 1e6

    fun run(ctx: Context, first: String, build: String): JSONObject {
        val td = File(DemoFiles.root(ctx), "testdata")
        val trips = JSONObject(File(td, "trips.json").readText())
        val reference = JSONObject(File(td, "reference/trips.json").readText())
        fun place(id: String) = trips.getJSONObject("places").getJSONObject(id).let { LatLon(it.getDouble("lat"), it.getDouble("lon")) }
        fun trip(id: String): List<Stop> {
            val a = trips.getJSONArray("trips")
            for (i in 0 until a.length()) a.getJSONObject(i).let { if (it.getString("id") == id) return listOf(Stop(place(it.getString("from"))), Stop(place(it.getString("to")))) }
            error("no trip $id")
        }
        val other = if (first == "U1") "I1" else "U1"
        val out = JSONObject().put("build", build).put("first_trip", first).put("memory_before_engine", memory())
        var t0 = SystemClock.elapsedRealtimeNanos()
        ValhallaEngine.openTar(DemoFiles.tiles(ctx), DemoFiles.config(ctx, "valhalla-measure.json")).use { engine ->
            out.put("engine_open_ms", ms(t0)).put("memory_after_open", memory())
            val fastest = JsonObject(emptyMap())
            fun routeMs(id: String): Double {
                val req = ValhallaRequests.route(trip(id), fastest).toString()
                val s = SystemClock.elapsedRealtimeNanos()
                ValhallaResponses.parseRoute(engine.route(req))
                return ms(s)
            }
            val lat = JSONObject()
            for (id in listOf(first, other)) {
                val cold = routeMs(id)
                val warm = (1..5).map { routeMs(id) }.sorted()
                lat.put(id, JSONObject().put("first_ms", cold).put("warm_median_ms", warm[2]).put("warm_all_ms", JSONArray(warm)))
            }
            out.put("route_latency", lat)

            // three presets for one trip (what a preset picker shows), Simpler (a pool of 4 calls since batch D), alternates
            val planner = RoutePlanner(engine, PresetSet.bundled())
            val three = JSONObject()
            for (id in listOf("I1", "R2")) {
                val per = JSONObject()
                val s = SystemClock.elapsedRealtimeNanos()
                for (p in listOf("fastest", "eco", "avoid_highways")) { // Shortest left in batch D
                    val ps = SystemClock.elapsedRealtimeNanos()
                    check(planner.plan(RouteRequest(trip(id), p)) is PlanResult.Ok)
                    per.put(p, ms(ps))
                }
                per.put("total_ms", ms(s))
                val ss = SystemClock.elapsedRealtimeNanos()
                val simpler = planner.plan(RouteRequest(trip(id), "simpler")) as PlanResult.Ok
                per.put("simpler_ms", ms(ss)).put("simpler_engine_calls", simpler.engineCalls)
                val sa = SystemClock.elapsedRealtimeNanos()
                val withAlt = planner.plan(RouteRequest(trip(id), "fastest", withAlternates = true)) as PlanResult.Ok
                per.put("fastest_with_alternates_ms", ms(sa)).put("alternates", withAlt.alternates.size)
                three.put(id, per)
            }
            out.put("three_presets", three)

            // 1 x 40 matrix along I1 (the reference's targets)
            val mreq = reference.getJSONObject("matrix_1x40").getJSONObject("request").toString()
            val runs = (1..4).map {
                val s = SystemClock.elapsedRealtimeNanos()
                val m = ValhallaResponses.parseMatrix(engine.matrix(mreq))
                check(m.nulls == 0) { "${m.nulls} null cells" }
                ms(s)
            }
            out.put("matrix_1x40", JSONObject().put("first_ms", runs[0]).put("warm_median_ms", runs.drop(1).sorted()[1]).put("all_ms", JSONArray(runs)))

            // stop order: 8 stops, one 8x8 matrix + 720 orders
            val stops = listOf("erie_perry_sq", "youngstown", "meadville", "pgh_market_sq", "sharon", "franklin", "hubbard", "ridgway").map(::place)
            val so = SystemClock.elapsedRealtimeNanos()
            val order = checkNotNull(StopOrderer(engine).bestOrder(stops)) { "no order connects the 8 stops" }
            out.put("reorder_8_stops", JSONObject().put("ms", ms(so)).put("order", JSONArray(order.order)).put("time_s", order.timeS))
            out.put("memory_after_routes", memory())
        }
        // a second engine in the same process: what of the opening cost is paid once per process
        t0 = SystemClock.elapsedRealtimeNanos()
        ValhallaEngine.openTar(DemoFiles.tiles(ctx), DemoFiles.config(ctx, "valhalla-measure-2.json")).use { out.put("engine_open_again_ms", ms(t0)) }
        File(DemoFiles.results(ctx), "measure-$build-$first-${System.currentTimeMillis()}.json").writeText(out.toString(1))
        return out
    }

    /** /proc/self/status and Debug.MemoryInfo; PSS counts the touched pages of the mapped tile file. */
    fun memory(): JSONObject {
        val status = File("/proc/self/status").readLines().associate { l -> l.substringBefore(':') to l.substringAfter(':').trim() }
        val mi = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        return JSONObject()
            .put("VmRSS", status["VmRSS"]).put("VmHWM", status["VmHWM"]).put("RssFile", status["RssFile"]).put("RssAnon", status["RssAnon"])
            .put("total_pss_kb", mi.totalPss).put("total_private_dirty_kb", mi.totalPrivateDirty)
            .put("native_pss_kb", mi.nativePss).put("dalvik_pss_kb", mi.dalvikPss).put("other_pss_kb", mi.otherPss)
            .put("summary", JSONObject(mi.memoryStats as Map<*, *>))
    }
}
