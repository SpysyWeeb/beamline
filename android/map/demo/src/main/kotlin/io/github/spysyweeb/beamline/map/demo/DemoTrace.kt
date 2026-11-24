package io.github.spysyweeb.beamline.map.demo

import android.content.res.AssetManager
import org.json.JSONArray
import org.json.JSONObject

/**
 * Track N's golden trace of the exit 24 drive (assets/demo/trace24.json, made by
 * scripts/make_exit24.py from android/routing/testdata/nav/exit24.json): the session's vehicle and
 * state after each fix of the `default` simulation (1 fix/s, the 20 s halt before the left turn,
 * ARRIVED at the end). The demo's `trace` scene replays it, so the map shows what the real session
 * gives it rather than states the demo made up.
 */
class DemoTrace(val rows: List<Row>) {
    data class Row(
        val tMs: Long,
        val lat: Double,
        val lon: Double,
        val snapped: Boolean,
        val fixBearingDeg: Double?,
        val speedMps: Double?,
        val phase: String,
        val motion: String,
        val gps: String,
        val u: Int,
        val shownM: Double,
        val remainingM: Double,
    )

    companion object {
        private val COLUMNS = listOf(
            "t_ms", "lat", "lon", "snapped", "fix_bearing_deg", "speed_mps", "phase", "motion", "gps", "u", "shown_m", "remaining_m",
        )

        fun load(assets: AssetManager): DemoTrace {
            val o = JSONObject(assets.open("demo/trace24.json").use { it.readBytes().toString(Charsets.UTF_8) })
            val cols = o.getJSONArray("columns").let { a -> (0 until a.length()).map { a.getString(it) } }
            require(cols == COLUMNS) { "trace24.json: unexpected columns (run make_exit24.py)" }
            val rows = o.getJSONArray("rows")
            return DemoTrace((0 until rows.length()).map { row(rows.getJSONArray(it)) })
        }

        private fun row(r: JSONArray) = Row(
            tMs = r.getLong(0),
            lat = r.getDouble(1),
            lon = r.getDouble(2),
            snapped = r.getInt(3) == 1,
            fixBearingDeg = if (r.isNull(4)) null else r.getDouble(4),
            speedMps = if (r.isNull(5)) null else r.getDouble(5),
            phase = r.getString(6),
            motion = r.getString(7),
            gps = r.getString(8),
            u = r.getInt(9),
            shownM = r.getDouble(10),
            remainingM = r.getDouble(11),
        )
    }
}
