package io.github.spysyweeb.beamline.routing.demo

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import io.github.spysyweeb.beamline.routing.mobile.ValhallaEngine
import io.github.spysyweeb.beamline.routing.model.LatLon
import io.github.spysyweeb.beamline.routing.model.Stop
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Shared by the on-device tests: the app's data, the test assets (android/routing/testdata). */
object DeviceTestSupport {
    val target: Context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val testCtx: Context get() = InstrumentationRegistry.getInstrumentation().context

    fun assetText(name: String): String = testCtx.assets.open(name).use { it.readBytes().toString(Charsets.UTF_8) }

    fun asset(name: String): JSONObject = JSONObject(assetText(name))

    fun args(): android.os.Bundle = InstrumentationRegistry.getArguments()

    val trips: JSONObject by lazy { asset("trips.json") }
    /** The old 126 plans (format 1). */
    val reference: JSONObject by lazy { asset("reference/trips.json") }

    /** Presets format 2: 21 trips x 4 types x 4 option sets and the cases of docs/architecture.md 2.11. */
    val plans: JSONObject by lazy { asset("reference/plans.json") }

    fun place(id: String): LatLon = trips.getJSONObject("places").getJSONObject(id).let { LatLon(it.getDouble("lat"), it.getDouble("lon")) }

    fun trip(id: String): List<Stop> {
        val arr = trips.getJSONArray("trips")
        for (i in 0 until arr.length()) {
            val t = arr.getJSONObject(i)
            if (t.getString("id") == id) return listOf(Stop(place(t.getString("from"))), Stop(place(t.getString("to"))))
        }
        error("no trip $id")
    }

    fun tripIds(): List<String> = trips.getJSONArray("trips").let { a -> (0 until a.length()).map { a.getJSONObject(it).getString("id") } }

    fun stopOf(o: JSONObject) = Stop(LatLon(o.getDouble("lat"), o.getDouble("lon")),
        headingDeg = if (o.has("heading")) o.getInt("heading") else null, radiusM = if (o.has("radius")) o.getInt("radius") else null)

    fun bannerCase(id: String): List<Stop> {
        val a = asset("banner_cases.json").getJSONArray("cases")
        for (i in 0 until a.length()) {
            val c = a.getJSONObject(i)
            if (c.getString("id") == id) return listOf(stopOf(c.getJSONObject("start")), stopOf(c.getJSONObject("end")))
        }
        error("no case $id")
    }

    fun openEngine(name: String = "valhalla.json"): ValhallaEngine {
        val tar = DemoFiles.tiles(target)
        check(tar.isFile) { "no tiles at $tar: run android/routing/scripts/device.py push" }
        return ValhallaEngine.openTar(tar, DemoFiles.config(target, name))
    }

    fun writeResult(name: String, json: JSONObject): File =
        File(DemoFiles.results(target), name).apply { writeText(json.toString(1)) }

    fun jsonArray(values: Iterable<Any?>) = JSONArray().apply { values.forEach { put(it) } }

    /** Process memory now: /proc/self/status and Debug.MemoryInfo (PSS includes the mapped tile pages touched). */
    fun memory(): JSONObject {
        val status = File("/proc/self/status").readLines().associate { l -> l.substringBefore(':') to l.substringAfter(':').trim() }
        val mi = android.os.Debug.MemoryInfo().also { android.os.Debug.getMemoryInfo(it) }
        return JSONObject()
            .put("VmRSS", status["VmRSS"]).put("VmHWM", status["VmHWM"]).put("RssFile", status["RssFile"]).put("RssAnon", status["RssAnon"])
            .put("total_pss_kb", mi.totalPss).put("total_private_dirty_kb", mi.totalPrivateDirty)
            .put("native_pss_kb", mi.nativePss).put("dalvik_pss_kb", mi.dalvikPss).put("other_pss_kb", mi.otherPss)
            .put("summary", JSONObject(mi.memoryStats as Map<*, *>))
    }
}
