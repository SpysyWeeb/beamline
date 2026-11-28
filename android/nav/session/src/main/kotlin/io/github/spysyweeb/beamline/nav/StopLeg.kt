package io.github.spysyweeb.beamline.nav

import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.nav.Origin
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.RouteChoice
import io.github.spysyweeb.beamline.api.nav.TripState
import io.github.spysyweeb.beamline.api.nav.TripStop
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min

/**
 * Item 51 (rules C1, C8): where NavIntent.AddStop(place) without an index puts a stop, and the part of the
 * trip's route it joins. Pure; the session and the app use the same answers. Nothing here logs (PV2).
 */
object StopLeg {
    /** C1: just before the destination, after every other stop; the destination itself when there is none (SessionCore.addStop). */
    fun insertIndex(stops: List<TripStop>): Int {
        val reached = stops.count { it.reached }
        return (if (stops.size > reached) stops.size - 1 else stops.size).coerceIn(reached, stops.size)
    }

    /**
     * The route line of the leg a new stop joins, ahead of the car: the last leg (into the destination) of the
     * guided route (GUIDING, REROUTING), from the traveled cursor when the car is on that leg, or of the selected
     * route choice (PLANNING, CHOOSING; the first choice when none is selected). Before any choice: the start
     * (Origin.FromPlace) and the destination, when both are known (the stop before the destination instead of the
     * start when there is one). Empty otherwise (IDLE, ARRIVED, RESUME_OFFERED, no stop). Its first point is where
     * the stop joins the trip.
     */
    fun lineAhead(trip: TripState): List<GeoPoint> {
        val guided = trip.phase == Phase.GUIDING || trip.phase == Phase.REROUTING
        val choosing = trip.phase == Phase.PLANNING || trip.phase == Phase.CHOOSING
        if (!guided && !choosing) return emptyList()
        val unreached = trip.stops.filter { !it.reached }
        if (unreached.isEmpty()) return emptyList()
        val route = if (guided) trip.guidance?.route else trip.choices.firstOrNull { it.id == trip.selectedChoiceId } ?: trip.choices.firstOrNull()
        if (route == null || route.geometry.isEmpty()) {
            if (!choosing) return emptyList()
            val join = if (unreached.size >= 2) unreached[unreached.size - 2].place.point else (trip.origin as? Origin.FromPlace)?.place?.point
            return if (join == null) emptyList() else listOf(join, unreached.last().place.point)
        }
        val g = route.geometry
        val ends = route.legEnds
        val start = (if (ends.size >= 2) ends[ends.size - 2] else 0).coerceIn(0, g.lastIndex)
        val cursor = if (guided) trip.guidance?.traveled else null
        return if (cursor != null && cursor.index >= start) {
            listOf(cursor.point) + g.subList(min(cursor.index + 1, g.size), g.size)
        } else {
            g.subList(start, g.size)
        }
    }

    /** The part of a StopPreview route from where the new stop joins to the stop (the leg that ends at it: its second-to-last leg). */
    fun lineToStop(route: RouteChoice): List<GeoPoint> {
        val g = route.geometry
        val ends = route.legEnds
        if (ends.size < 2 || g.isEmpty()) return g
        val to = ends[ends.size - 2].coerceIn(0, g.lastIndex)
        val from = (if (ends.size >= 3) ends[ends.size - 3] else 0).coerceIn(0, to)
        return g.subList(from, to + 1)
    }

    /**
     * The unreached stop a new stop goes after (the one before the destination), null when the new stop joins
     * at the car or the trip's start. Its name is shown: "Along your route after Wegmans", "Goes after Wegmans".
     */
    fun joinsAfter(trip: TripState): Place? {
        val unreached = trip.stops.filter { !it.reached }
        return if (unreached.size >= 2) unreached[unreached.size - 2].place else null
    }

    /**
     * Item 51 (rule C8): where the detour of [places] is measured back to: the point of [line] (lineAhead)
     * [marginKm] past the farthest of [places] along it (each projected on the line, equirectangular per
     * segment), with the line's bearing there (degrees, 0-359). Null when that is at or past the line's end
     * (then the destination itself is the rejoin point) or the line has fewer than 2 points.
     */
    fun rejoin(line: List<GeoPoint>, places: List<GeoPoint>, marginKm: Double): Pair<GeoPoint, Double>? {
        if (line.size < 2) return null
        val along = DoubleArray(line.size)
        for (i in 1 until line.size) along[i] = along[i - 1] + segmentM(line[i - 1], line[i])
        var farthest = 0.0
        for (p in places) farthest = maxOf(farthest, alongOf(line, along, p))
        val target = farthest + marginKm * 1000.0
        if (target >= along.last()) return null
        var i = 1
        while (i < line.lastIndex && along[i] < target) i++
        val a = line[i - 1]
        val b = line[i]
        val len = along[i] - along[i - 1]
        val t = if (len > 0.0) ((target - along[i - 1]) / len).coerceIn(0.0, 1.0) else 0.0
        val point = GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
        return point to bearingDeg(a, b)
    }

    private const val EARTH_M = 6_371_008.8
    private const val RAD = Math.PI / 180.0

    /** East and north metres of [p] from [o] (equirectangular around [o]). */
    private fun xy(o: GeoPoint, p: GeoPoint): Pair<Double, Double> =
        (p.lon - o.lon) * RAD * cos(o.lat * RAD) * EARTH_M to (p.lat - o.lat) * RAD * EARTH_M

    private fun segmentM(a: GeoPoint, b: GeoPoint): Double = xy(a, b).let { (x, y) -> hypot(x, y) }

    /** 0-359 degrees clockwise from north, from [a] to [b]. */
    private fun bearingDeg(a: GeoPoint, b: GeoPoint): Double {
        val (x, y) = xy(a, b)
        val d = Math.toDegrees(atan2(x, y))
        return ((d % 360.0) + 360.0) % 360.0
    }

    /** The length along [line] to the point of it nearest [p] (each segment equirectangular around its start). */
    private fun alongOf(line: List<GeoPoint>, along: DoubleArray, p: GeoPoint): Double {
        var best = Double.MAX_VALUE
        var bestAlong = 0.0
        for (i in 1 until line.size) {
            val a = line[i - 1]
            val (bx, by) = xy(a, line[i])
            val (px, py) = xy(a, p)
            val len2 = bx * bx + by * by
            val t = if (len2 > 0.0) ((px * bx + py * by) / len2).coerceIn(0.0, 1.0) else 0.0
            val d = hypot(px - bx * t, py - by * t)
            if (d < best) {
                best = d
                bestAlong = along[i - 1] + t * (along[i] - along[i - 1])
            }
        }
        return bestAlong
    }
}
