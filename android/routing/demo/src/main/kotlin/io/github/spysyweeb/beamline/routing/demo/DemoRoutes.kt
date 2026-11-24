package io.github.spysyweeb.beamline.routing.demo

import android.content.Context
import android.os.SystemClock
import android.util.Log
import io.github.spysyweeb.beamline.routing.mobile.ValhallaEngine
import io.github.spysyweeb.beamline.routing.model.LatLon
import io.github.spysyweeb.beamline.routing.model.Route
import io.github.spysyweeb.beamline.routing.model.RouteRequest
import io.github.spysyweeb.beamline.routing.model.Stop
import io.github.spysyweeb.beamline.routing.planner.PlanResult
import io.github.spysyweeb.beamline.routing.planner.RoutePlanner
import io.github.spysyweeb.beamline.routing.preset.PresetSet
import java.io.File

/** Where the demo (and its on-device tests) find their data; filled by android/routing/scripts/device.py. */
object DemoFiles {
    fun root(ctx: Context): File = ctx.getExternalFilesDir(null) ?: ctx.filesDir

    /** PA+OH tile set (the Kotlin pack manager's output for both packs). */
    fun tiles(ctx: Context) = File(root(ctx), "routing/routing.tar")

    /** PA-only tile set, for "outside the installed maps". */
    fun tilesPaOnly(ctx: Context) = File(root(ctx), "routing-pa-only/routing.tar")

    fun mapPacks(ctx: Context) = File(root(ctx), "packs")

    /** Engine configs are written to internal storage next to nothing else. */
    fun config(ctx: Context, name: String = "valhalla.json") = File(ctx.filesDir, "routing/$name")

    /** Results the tests write for the host script to pull. */
    fun results(ctx: Context) = File(root(ctx), "results").apply { mkdirs() }
}

data class PresetRoute(
    val presetId: String,
    val label: String,
    val color: Int,
    val route: Route?,
    val problem: String?,
    val planMs: Long,
    val engineCalls: Int,
)

/** The demo's work, off the main thread: open the engine, plan the trip with every route type and option. */
object DemoRoutes {
    private const val TAG = "RoutingDemo"

    /** Default trip: Lancaster to Gettysburg (trip R2 of trips.json), where the presets differ. */
    val DEFAULT_FROM = LatLon(40.0380, -76.3055)
    val DEFAULT_TO = LatLon(39.8309, -77.2311)

    val COLORS = mapOf(
        "fastest" to 0xFF1A73E8.toInt(),
        "simpler" to 0xFF12B5CB.toInt(),
        "eco" to 0xFF34A853.toInt(),
        "avoid_highways" to 0xFFA142F4.toInt(),
        "avoid_tolls" to 0xFFD93025.toInt(),
    )

    fun planAll(ctx: Context, from: LatLon, to: LatLon): Pair<Long, List<PresetRoute>> {
        val t0 = SystemClock.elapsedRealtime()
        ValhallaEngine.openTar(DemoFiles.tiles(ctx), DemoFiles.config(ctx)).use { engine ->
            val openMs = SystemClock.elapsedRealtime() - t0
            Log.i(TAG, "engine open $openMs ms")
            val presets = PresetSet.bundled()
            val planner = RoutePlanner(engine, presets)
            // the three route types (Shortest left in batch D), and Fastest with each option (presets format 2: PresetSet.resolve)
            val out = COLORS.keys.map { id ->
                val p = presets.resolve(id)
                val label = presets.options.firstOrNull { it.id == id }?.label ?: p.label
                val s = SystemClock.elapsedRealtime()
                val r = planner.plan(RouteRequest(listOf(Stop(from), Stop(to))), p)
                val ms = SystemClock.elapsedRealtime() - s
                val pr = when (r) {
                    is PlanResult.Ok -> PresetRoute(id, label, COLORS.getValue(id), r.route, null, ms, r.engineCalls)
                    is PlanResult.Failed -> PresetRoute(id, label, COLORS.getValue(id), null, r.problem.userText, ms, r.engineCalls)
                }
                Log.i(TAG, "$id: ${pr.route?.let { "%.1f km, %.1f min, %d turns".format(it.lengthM / 1000, it.timeS / 60, it.turnCount) } ?: pr.problem} in $ms ms, ${pr.engineCalls} calls")
                pr
            }
            return openMs to out
        }
    }
}
