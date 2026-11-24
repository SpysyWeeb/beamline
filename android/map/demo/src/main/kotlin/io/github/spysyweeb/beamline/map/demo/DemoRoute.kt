package io.github.spysyweeb.beamline.map.demo

import android.content.res.AssetManager
import io.github.spysyweeb.beamline.api.model.GeoPoint
import org.json.JSONObject
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** The exit 24 route (assets/demo/exit24.json, made by scripts/make_exit24.py from N's recording). */
class DemoRoute(val points: List<GeoPoint>, val maneuvers: List<Maneuver>) {
    data class Maneuver(val index: Int, val alongM: Double, val type: String, val modifier: String?, val name: String, val speedMps: Double)

    /** One position along the line: the point, the piece it is on (after points[segment]) and its bearing. */
    data class Along(val point: GeoPoint, val segment: Int, val bearingDeg: Double)

    /** Metres from the start to each point. */
    val along: DoubleArray = DoubleArray(points.size).also { a ->
        for (i in 1 until points.size) a[i] = a[i - 1] + metres(points[i - 1], points[i])
    }
    val lengthM: Double get() = along.last()

    fun at(m: Double): Along {
        val d = m.coerceIn(0.0, lengthM)
        var i = 0
        while (i < points.size - 2 && along[i + 1] < d) i++
        val seg = along[i + 1] - along[i]
        val t = if (seg > 0) (d - along[i]) / seg else 0.0
        val a = points[i]
        val b = points[i + 1]
        return Along(GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t), i, bearing(a, b))
    }

    /** The speed of the stretch [m] is on (the step's own distance / duration). */
    fun speedAt(m: Double): Double = maneuvers.lastOrNull { it.alongM <= m && it.speedMps > 0 }?.speedMps ?: maneuvers.first().speedMps

    /** Index in [maneuvers] of the next maneuver after [m] (the arrival last). */
    fun nextManeuver(m: Double): Int = maneuvers.indexOfFirst { it.alongM > m + 0.5 }.let { if (it < 0) maneuvers.lastIndex else it }

    /**
     * The point of the line nearest [p] among the pieces [from] to [from] + [window] (a vehicle moves
     * forward, so a piece behind it or far ahead, where the line passes close by again, is not
     * taken), with the piece it is on and that piece's bearing.
     */
    fun project(p: GeoPoint, from: Int = 0, window: Int = 20): Along {
        val k = cos(Math.toRadians(p.lat))
        var best = Along(points.first(), 0, bearing(points[0], points[1]))
        var bestD = Double.MAX_VALUE
        val first = from.coerceIn(0, points.size - 2)
        val last = (first + window).coerceAtMost(points.size - 2)
        for (i in first..last) {
            val a = points[i]
            val b = points[i + 1]
            val dx = (b.lon - a.lon) * k
            val dy = b.lat - a.lat
            val len2 = dx * dx + dy * dy
            val t = if (len2 > 0) (((p.lon - a.lon) * k * dx + (p.lat - a.lat) * dy) / len2).coerceIn(0.0, 1.0) else 0.0
            val ex = (p.lon - a.lon) * k - t * dx
            val ey = (p.lat - a.lat) - t * dy
            val d = ex * ex + ey * ey
            if (d < bestD) {
                bestD = d
                best = Along(GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t), i, bearing(a, b))
            }
        }
        return best
    }

    companion object {
        fun load(assets: AssetManager): DemoRoute {
            val o = JSONObject(assets.open("demo/exit24.json").use { it.readBytes().toString(Charsets.UTF_8) })
            val pts = o.getJSONArray("points").let { a -> (0 until a.length()).map { i -> a.getJSONArray(i).let { GeoPoint(it.getDouble(0), it.getDouble(1)) } } }
            val ms = o.getJSONArray("maneuvers").let { a ->
                (0 until a.length()).map { i ->
                    val m = a.getJSONObject(i)
                    Maneuver(
                        index = m.getInt("index"),
                        alongM = m.getDouble("along_m"),
                        type = m.getString("type"),
                        modifier = if (m.isNull("modifier")) null else m.getString("modifier"),
                        name = m.optString("name"),
                        speedMps = m.getDouble("speed_mps"),
                    )
                }
            }
            return DemoRoute(pts, ms)
        }

        fun metres(a: GeoPoint, b: GeoPoint): Double {
            val la1 = Math.toRadians(a.lat)
            val la2 = Math.toRadians(b.lat)
            val dla = la2 - la1
            val dlo = Math.toRadians(b.lon - a.lon)
            val h = sin(dla / 2) * sin(dla / 2) + cos(la1) * cos(la2) * sin(dlo / 2) * sin(dlo / 2)
            return 2 * 6371008.8 * atan2(sqrt(h), sqrt(1 - h))
        }

        fun bearing(a: GeoPoint, b: GeoPoint): Double {
            val la1 = Math.toRadians(a.lat)
            val la2 = Math.toRadians(b.lat)
            val dlo = Math.toRadians(b.lon - a.lon)
            val y = sin(dlo) * cos(la2)
            val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dlo)
            return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
        }
    }
}
