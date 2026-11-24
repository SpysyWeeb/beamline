package io.github.spysyweeb.beamline.nav

import io.github.spysyweeb.beamline.routing.model.Geo
import io.github.spysyweeb.beamline.routing.model.LatLon
import io.github.spysyweeb.beamline.routing.model.Route
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A route's line as distances: the flattened shape (all legs, Route.shape), the distance along it
 * of every shape point, and where each maneuver (Route.maneuvers, flattened) sits along it. Pure
 * geometry on a local flat projection per segment (good to a few centimetres over a segment);
 * lengths are haversine (Geo.distanceM).
 */
class RouteGeometry(val route: Route) {
    val shape: List<LatLon> = route.shape
    /** Distance along the line at each shape point, metres. */
    val along: DoubleArray
    /** Flattened shape index where each maneuver begins. */
    val maneuverShapeIndex: IntArray
    /** Distance along the line of each maneuver. */
    val maneuverAlong: DoubleArray
    /** Leg of each maneuver. */
    val maneuverLeg: IntArray
    /** Flattened index of each leg's last shape point. */
    val legEndIndex: IntArray
    val lengthM: Double

    init {
        require(shape.size >= 2) { "a route needs at least two shape points" }
        along = DoubleArray(shape.size)
        for (i in 1 until shape.size) along[i] = along[i - 1] + Geo.distanceM(shape[i - 1], shape[i])
        lengthM = along.last()
        val idx = ArrayList<Int>()
        val legs = ArrayList<Int>()
        val ends = ArrayList<Int>()
        var offset = 0
        route.legs.forEachIndexed { li, leg ->
            leg.maneuvers.forEach { m ->
                idx += (offset + m.beginShapeIndex).coerceIn(0, shape.lastIndex)
                legs += li
            }
            offset += leg.shape.size
            ends += offset - 1
        }
        maneuverShapeIndex = idx.toIntArray()
        maneuverAlong = DoubleArray(idx.size) { along[maneuverShapeIndex[it]] }
        maneuverLeg = legs.toIntArray()
        legEndIndex = ends.toIntArray()
    }

    val maneuverCount get() = maneuverShapeIndex.size

    private val maneuverTimes = route.maneuvers.map { it.timeS }
    private val totalTimeS = maneuverTimes.sum()

    /** Time from [m] metres along to the end, by the maneuvers' own estimates (linear inside a stretch). */
    fun timeAfter(m: Double): Double {
        var t = 0.0
        for (k in 0 until maneuverCount) {
            val a = maneuverAlong[k]
            val b = if (k + 1 < maneuverCount) maneuverAlong[k + 1] else lengthM
            if (b <= a) {
                if (a >= m) t += maneuverTimes[k]
                continue
            }
            t += maneuverTimes[k] * ((b - maxOf(a, m)) / (b - a)).coerceIn(0.0, 1.0)
        }
        return t.coerceIn(0.0, totalTimeS)
    }

    /** Distance along the line of the end of leg [leg]. */
    fun legEndAlong(leg: Int): Double = along[legEndIndex[leg.coerceIn(0, legEndIndex.lastIndex)]]

    /** The distance along of a point the follower reports: segment [shapeIndex] and the point on it. */
    fun alongOf(shapeIndex: Int, point: LatLon): Double {
        val i = shapeIndex.coerceIn(0, shape.size - 2)
        return (along[i] + Geo.distanceM(shape[i], point)).coerceAtMost(along[i + 1].coerceAtLeast(along[i]))
    }

    /** Where a point lies on the line: segment [index] (shape[index] to shape[index + 1]), the point, the distance along. */
    data class Projection(val index: Int, val point: LatLon, val alongM: Double, val distanceM: Double)

    /**
     * The nearest point of the line to [p] among the segments whose start lies between [fromM] and
     * [toM] along the line (all by default). Ties go to the earlier segment.
     */
    fun project(p: LatLon, fromM: Double = 0.0, toM: Double = Double.MAX_VALUE): Projection {
        var best: Projection? = null
        val first = indexAt(fromM.coerceAtLeast(0.0))
        for (i in first until shape.size - 1) {
            if (along[i] > toM && best != null) break
            val q = projectOnSegment(p, i)
            if (best == null || q.distanceM < best.distanceM - 1e-9) best = q
        }
        return best ?: projectOnSegment(p, shape.size - 2)
    }

    private fun projectOnSegment(p: LatLon, i: Int): Projection {
        val a = shape[i]
        val b = shape[i + 1]
        val kx = 111_320.0 * cos(Math.toRadians(a.lat))
        val ky = 110_574.0
        val bx = (b.lon - a.lon) * kx
        val by = (b.lat - a.lat) * ky
        val px = (p.lon - a.lon) * kx
        val py = (p.lat - a.lat) * ky
        val len2 = bx * bx + by * by
        val t = if (len2 <= 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, 1.0)
        val q = LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
        val dx = px - bx * t
        val dy = py - by * t
        return Projection(i, q, along[i] + (along[i + 1] - along[i]) * t, sqrt(dx * dx + dy * dy))
    }

    /** The segment holding the point [m] metres along (the last segment for the end). */
    fun indexAt(m: Double): Int {
        if (m <= 0.0) return 0
        if (m >= lengthM) return shape.size - 2
        var lo = 0
        var hi = shape.size - 1
        while (hi - lo > 1) {
            val mid = (lo + hi) ushr 1
            if (along[mid] <= m) lo = mid else hi = mid
        }
        return lo.coerceAtMost(shape.size - 2)
    }

    /** The point [m] metres along the line (clamped to the line). */
    fun pointAt(m: Double): LatLon {
        val c = m.coerceIn(0.0, lengthM)
        val i = indexAt(c)
        val seg = along[i + 1] - along[i]
        val t = if (seg <= 0.0) 0.0 else ((c - along[i]) / seg).coerceIn(0.0, 1.0)
        val a = shape[i]
        val b = shape[i + 1]
        return LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
    }

    /** Bearing of the line at [m] metres along, degrees from true north; null on a zero-length stretch. */
    fun bearingAt(m: Double): Double? {
        var i = indexAt(m.coerceIn(0.0, lengthM))
        // a zero-length segment (a leg boundary) takes the next real one
        while (i < shape.size - 2 && along[i + 1] - along[i] < 0.01) i++
        if (along[i + 1] - along[i] < 0.01) return null
        return bearing(shape[i], shape[i + 1])
    }

    /** The point [sideM] metres to the right (negative: left) of the line at [m] metres along. */
    fun offset(m: Double, sideM: Double): LatLon {
        val p = pointAt(m)
        val b = bearingAt(m) ?: return p
        return move(p, b + 90.0, sideM)
    }

    /** The maneuver whose stretch holds [m] metres along (the last one whose position is at or before it). */
    fun stepAt(m: Double): Int {
        var k = 0
        for (i in maneuverAlong.indices) if (maneuverAlong[i] <= m + 1e-9) k = i
        return k
    }

    companion object {
        fun bearing(a: LatLon, b: LatLon): Double {
            val f1 = Math.toRadians(a.lat)
            val f2 = Math.toRadians(b.lat)
            val dl = Math.toRadians(b.lon - a.lon)
            val y = sin(dl) * cos(f2)
            val x = cos(f1) * sin(f2) - sin(f1) * cos(f2) * cos(dl)
            return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
        }

        /** [p] moved [m] metres towards [bearingDeg] (flat-earth step, fine for metres to kilometres). */
        fun move(p: LatLon, bearingDeg: Double, m: Double): LatLon {
            val r = Math.toRadians(bearingDeg)
            val dLat = m * cos(r) / 110_574.0
            val dLon = m * sin(r) / (111_320.0 * cos(Math.toRadians(p.lat)))
            return LatLon(p.lat + dLat, p.lon + dLon)
        }

        /** Smallest angle between two bearings, 0..180. */
        fun angleDiff(a: Double, b: Double): Double {
            val d = ((a - b) % 360.0 + 360.0) % 360.0
            return if (d > 180.0) 360.0 - d else d
        }
    }
}
