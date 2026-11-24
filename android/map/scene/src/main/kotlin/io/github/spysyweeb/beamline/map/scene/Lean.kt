package io.github.spysyweeb.beamline.map.scene

import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.nav.RouteCursor
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * The camera leans into a turn (docs/next-round.md item 22, camera.json guide.lean): as the vehicle
 * comes up to a turn the map turns part of the way toward the road it turns onto, zooms in a little
 * and tilts up a little, so the turn and the road after it are in view before the vehicle gets
 * there; after the turn the camera eases back to the driving view. Pure geometry on the route line:
 * what is a turn is read from the line itself (the directions before and after the banner's
 * maneuver point), so a "Continue" or a bend of the same road never leans.
 */
internal object Lean {
    /** How the driving camera differs from the plain follow camera now: add to the bearing and the zoom, use this pitch. */
    data class Pose(val bearingAddDeg: Double, val zoomAdd: Double, val pitchDeg: Double)

    /** The directions of the route [lookM] metres before and after the point [aheadM] metres ahead of [cursor]. */
    data class Turn(val entryDeg: Double, val exitDeg: Double) {
        /** Right positive, left negative, -180 < turn <= 180. */
        val turnDeg: Double get() = signedDiff(exitDeg, entryDeg)
    }

    /**
     * The turn [aheadM] metres along [geometry] from [cursor] (the vehicle between geometry[index]
     * and geometry[index + 1], at cursor.point). Null when the line ends less than [MIN_AFTER_M]
     * after that point (an arrival), or the point is not on the line.
     */
    fun turnAt(geometry: List<GeoPoint>, cursor: RouteCursor, aheadM: Double, lookM: Double): Turn? {
        if (aheadM < 0.0 || cursor.index < 0 || cursor.index >= geometry.size - 1) return null
        // the line from the vehicle on, with the distance of each point from the vehicle
        val pts = ArrayList<GeoPoint>()
        val at = ArrayList<Double>()
        pts += cursor.point
        at += 0.0
        val end = aheadM + lookM
        var i = cursor.index + 1
        while (i < geometry.size && at.last() < end) {
            val d = distanceM(pts.last(), geometry[i])
            if (d > 0.0) {
                pts += geometry[i]
                at += at.last() + d
            }
            i++
        }
        if (at.last() < aheadM + MIN_AFTER_M) return null
        val turn = pointAt(pts, at, aheadM)
        val after = pointAt(pts, at, minOf(end, at.last()))
        // before the turn: the line behind the vehicle is gone, so from the vehicle when it is nearer than lookM
        val before = pointAt(pts, at, maxOf(0.0, aheadM - lookM))
        if (distanceM(before, turn) < MIN_BEFORE_M) return null
        return Turn(bearingDeg(before, turn), bearingDeg(turn, after))
    }

    /**
     * The pose [distanceM] before a [turn] for a vehicle at [speedMps]: nothing (null) for a turn of
     * less than min_turn_deg or farther away than the larger of within_m and within_s at that speed;
     * growing smoothly from there to the turn.
     */
    fun pose(turn: Turn, distanceM: Double, speedMps: Double, rules: LeanRules, guidePitchDeg: Double): Pose? {
        val t = turn.turnDeg
        if (abs(t) < rules.minTurnDeg) return null
        val within = maxOf(rules.withinM, rules.withinS * speedMps)
        if (within <= 0.0 || distanceM >= within) return null
        val x = (1.0 - distanceM / within).coerceIn(0.0, 1.0)
        val p = x * x * (3.0 - 2.0 * x) // smoothstep: no jump at either end
        val add = (rules.bearingShare * t).coerceIn(-rules.maxBearingDeg, rules.maxBearingDeg)
        return Pose(p * add, p * rules.zoomAdd, guidePitchDeg + p * (rules.pitchDeg - guidePitchDeg))
    }

    /**
     * The map bearing for a vehicle heading [baseDeg] with [pose] before [turn]: the vehicle's bearing
     * moved toward the road after the turn by the pose's share of what is left of the turn. A vehicle
     * still on the way in gets the pose's whole lean; one that has begun to turn gets less; one that
     * has turned as far as the new road, or farther, gets none. So the map never turns past the new road.
     */
    fun bearing(baseDeg: Double, turn: Turn, pose: Pose): Double {
        val full = turn.turnDeg
        if (full == 0.0 || pose.bearingAddDeg == 0.0) return baseDeg
        val left = signedDiff(turn.exitDeg, baseDeg)
        if (left * full <= 0.0) return baseDeg
        val add = (pose.bearingAddDeg / full) * left
        // never more than the pose's own lean (a vehicle heading away from the turn for a moment)
        return normalizeDeg(baseDeg + if (abs(add) > abs(pose.bearingAddDeg)) pose.bearingAddDeg else add)
    }

    private fun pointAt(pts: List<GeoPoint>, at: List<Double>, d: Double): GeoPoint {
        if (d <= 0.0) return pts.first()
        for (k in 1 until pts.size) {
            if (at[k] >= d) {
                val seg = at[k] - at[k - 1]
                val f = if (seg <= 0.0) 0.0 else (d - at[k - 1]) / seg
                return GeoPoint(pts[k - 1].lat + f * (pts[k].lat - pts[k - 1].lat), pts[k - 1].lon + f * (pts[k].lon - pts[k - 1].lon))
            }
        }
        return pts.last()
    }

    /** Metres between two near points (flat: the camera's few hundred metres). */
    fun distanceM(a: GeoPoint, b: GeoPoint): Double {
        val ky = 111_320.0
        val kx = ky * cos(Math.toRadians((a.lat + b.lat) / 2))
        return hypot((b.lon - a.lon) * kx, (b.lat - a.lat) * ky)
    }

    /** A line that ends less than this after the maneuver point has no road to lean toward. */
    const val MIN_AFTER_M = 10.0
    /** Less of the line than this before the maneuver point gives no direction of arrival. */
    const val MIN_BEFORE_M = 3.0
}
