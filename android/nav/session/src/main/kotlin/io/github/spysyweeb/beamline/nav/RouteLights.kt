package io.github.spysyweeb.beamline.nav

import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.routing.model.LatLon
import io.github.spysyweeb.beamline.routing.model.Geo
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * The traffic lights on a guided route (docs/next-round.md item 22): where they are along the line.
 * The map draws the ones ahead of the vehicle and the banner says "At the light, turn left" when a
 * turn is at one.
 *
 * They come from the routing data: one `trace_attributes` call per stretch of the route
 * ([requests]: the route's own points, `edge_walk`, so the engine walks the edges the route uses and
 * matches nothing), asked for each edge's last shape index and whether a light stands at its end
 * (`edge.traffic_signal`: a light on the way just before the junction; `node.traffic_signal`: a
 * light on the junction itself). On the PC: 2 ms for 6 km in town (12 lights), 132 ms for 664 km (49).
 * A stretch whose call fails has no lights (the icons and the words are an extra, never a promise).
 *
 * And from the installed packs (item 36, [build]): the map's lights that stand at a junction of the
 * route without being on its line, or on its line without the engine knowing.
 */
class RouteLights(alongM: List<Double>, points: List<LatLon>) {
    /** Distance along the route of each light, growing. */
    val alongM: DoubleArray = alongM.toDoubleArray()
    val points: List<LatLon> = points.toList()

    init {
        require(this.alongM.size == this.points.size)
    }

    val size: Int get() = alongM.size

    /**
     * True when a light stands at the turn [atM] metres along the route: at most [beforeM] before its
     * maneuver point (both are junction points; the same node a few metres apart at most) or [afterM]
     * after it (the far side of a wide crossing). A light the car passes 10 m or more before it turns
     * is another junction's: "At the light" would be untrue there.
     */
    fun isAt(atM: Double, beforeM: Double = AT_TURN_BEFORE_M, afterM: Double = AT_TURN_AFTER_M): Boolean =
        alongM.any { it >= atM - beforeM && it <= atM + afterM }

    /**
     * The lights from [fromM] metres along the route to [aheadM] metres farther (by default to the
     * route's end: the owner, 2026-11-24, looks ahead on the map and expects every light there),
     * nearest first, one per junction: a light less than [SAME_JUNCTION_M] after the one before it
     * is the same junction (the two sides of a wide crossing).
     */
    fun ahead(fromM: Double, aheadM: Double = Double.POSITIVE_INFINITY): List<GeoPoint> {
        val out = ArrayList<GeoPoint>()
        var last = Double.NEGATIVE_INFINITY
        for (i in alongM.indices) {
            val a = alongM[i]
            val same = a - last < SAME_JUNCTION_M
            last = a
            if (same || a <= fromM) continue
            if (a > fromM + aheadM) break
            out += GeoPoint(points[i].lat, points[i].lon)
        }
        return out
    }

    companion object {
        val NONE = RouteLights(emptyList(), emptyList())

        /** A turn is "at the light" when a light is at most this far before its maneuver point along the route, */
        const val AT_TURN_BEFORE_M = 5.0
        /** or this far after it. */
        const val AT_TURN_AFTER_M = 15.0
        /** Two lights nearer than this are one junction (a light on each side of a wide crossing). */
        const val SAME_JUNCTION_M = 30.0
        /** The engine walks at most 200 km and 16,000 points per call (service_limits.trace); a stretch stays well under both. */
        const val STRETCH_M = 150_000.0
        const val STRETCH_POINTS = 12_000

        private val json = Json { ignoreUnknownKeys = true }

        /** One stretch of the route to ask about: its first and last flattened shape index (both asked). */
        class Stretch(val from: Int, val to: Int)

        /**
         * The stretches of [geo]: each leg alone (the engine's walk does not cross a stop), a long leg
         * in pieces of at most [STRETCH_M] metres and [STRETCH_POINTS] points; neighbours share a point.
         */
        fun stretches(geo: RouteGeometry): List<Stretch> {
            val out = ArrayList<Stretch>()
            var start = 0
            for (end in geo.legEndIndex) {
                var a = start
                while (a < end) {
                    var b = a + 1
                    while (b < end && b - a < STRETCH_POINTS && geo.along[b + 1] - geo.along[a] <= STRETCH_M) b++
                    out += Stretch(a, b)
                    a = b
                }
                start = end + 1
            }
            return out
        }

        /**
         * The trace_attributes request of one stretch ([match]: edge_walk, else map_snap). [junctions]: also
         * what the pack's lights are joined by ([build]): whether another road can be driven at each edge's
         * end, and whether the edge is a bridge, a tunnel or a motorway.
         */
        fun request(geo: RouteGeometry, s: Stretch, match: String = "edge_walk", junctions: Boolean = false): JsonObject = buildJsonObject {
            put("shape", buildJsonArray {
                for (i in s.from..s.to) add(buildJsonObject { put("lat", geo.shape[i].lat); put("lon", geo.shape[i].lon) })
            })
            put("costing", "auto")
            put("shape_match", match)
            put("filters", buildJsonObject {
                put("attributes", buildJsonArray {
                    add(JsonPrimitive("edge.end_shape_index")); add(JsonPrimitive("edge.traffic_signal")); add(JsonPrimitive("node.traffic_signal"))
                    if (junctions) {
                        add(JsonPrimitive("edge.bridge")); add(JsonPrimitive("edge.tunnel")); add(JsonPrimitive("edge.road_class"))
                        add(JsonPrimitive("node.intersecting_edge.driveability"))
                    }
                })
                put("action", "include")
            })
        }

        /**
         * One edge of an answer: the shape index (within the request's shape) where it ends, whether the
         * engine has a [light] there, whether its end is a [junction] (another road can be driven into or
         * out of there) and whether it is [plain] road (no bridge, no tunnel, no motorway).
         */
        class EdgeEnd(val index: Int, val light: Boolean, val junction: Boolean, val plain: Boolean)

        fun edgeEnds(traceJson: String): List<EdgeEnd> = try {
            json.parseToJsonElement(traceJson).jsonObject["edges"]!!.jsonArray.mapNotNull { e ->
                val o = e.jsonObject
                val index = o["end_shape_index"]?.jsonPrimitive?.intOrNull ?: return@mapNotNull null
                val node = o["end_node"] as? JsonObject
                val onEdge = o["traffic_signal"]?.jsonPrimitive?.booleanOrNull == true
                val onNode = node?.get("traffic_signal")?.jsonPrimitive?.booleanOrNull == true
                val junction = (node?.get("intersecting_edges") as? JsonArray)?.any { x ->
                    ((x as? JsonObject)?.get("driveability") as? JsonPrimitive)?.content in DRIVEABLE
                } == true
                val plain = o["bridge"]?.jsonPrimitive?.booleanOrNull != true && o["tunnel"]?.jsonPrimitive?.booleanOrNull != true &&
                    (o["road_class"] as? JsonPrimitive)?.content != "motorway"
                EdgeEnd(index, onEdge || onNode, junction, plain)
            }
        } catch (e: Exception) {
            emptyList()
        }

        /** The shape indexes (within the request's shape) where an answer says a light stands. */
        fun lightIndexes(traceJson: String): List<Int> = edgeEnds(traceJson).filter { it.light }.map { it.index }

        /** The pack's cell of a point (packbuilder/search/signals.py): a square of 0.01 degree. */
        fun cell(lat: Double, lon: Double): Long =
            (Math.floor(lat * 100).toLong() + 9000) * 40000 + Math.floor(lon * 100).toLong() + 18000

        /**
         * The lights of the route [geo]: [trace] answers a trace_attributes request (null or an
         * exception: that stretch has none). The route's last point is never a light (an arrival).
         *
         * [signals] (docs/next-round.md item 36): the traffic lights the installed packs have in the given
         * [cell]s, or null when the app has no packs to ask (the tests of the rules before). The engine
         * knows a light only when the signal is on the route's very line and the map has it on a junction
         * or says which way it faces; a light drawn on the cross street's side of a junction, or just past
         * the point where the route turns off, is not on that line. So a pack's light also counts
         *  - at a junction of the route (the end of an edge where another road can be driven) within
         *    [NEAR_JUNCTION_M] of it: the light is drawn at the junction;
         *  - where it is within [ON_LINE_M] of the route's line (a light between junctions);
         * in both cases on plain road only (a bridge, a tunnel or a motorway has no lights: the signal is
         * on the road below or above), and only where the engine has no light within [SAME_JUNCTION_M]
         * along the route. Not told apart: a signal of a crossing for people on a side street, mapped as a
         * traffic light within [NEAR_JUNCTION_M] of a junction that has none, counts as that junction's. Measured on 300 routes in Pennsylvania (3,857 km): 3,262 junctions with a light
         * from the engine, 141 more by this rule; a line-only rule of 18 m added 221, of which a third were
         * lights of another road.
         */
        fun build(geo: RouteGeometry, signals: ((Collection<Long>) -> List<LatLon>)? = null, trace: (String) -> String?): RouteLights {
            val at = ArrayList<Int>()
            val junctions = ArrayList<Int>()
            // plain[i]: the piece of the route from point i to point i + 1 is plain road
            val plain = BooleanArray(geo.shape.size)
            for (s in stretches(geo)) {
                val answer = try {
                    trace(request(geo, s, junctions = signals != null).toString())
                } catch (e: Exception) {
                    null
                } ?: continue
                var begin = s.from
                val ends = edgeEnds(answer)
                for ((n, e) in ends.withIndex()) {
                    val k = s.from + e.index
                    if (e.index < 0 || k > s.to) continue
                    if (e.light && k < geo.shape.lastIndex) at += k
                    if (signals != null) {
                        if (e.plain) for (j in begin until k) plain[j] = true
                        // the walk's last edge ends at a stop or where a long leg is cut: the engine lists the
                        // road onward as another road there, so that end is no junction
                        if (e.junction && e.plain && n < ends.lastIndex && k > 0 && k < geo.shape.lastIndex) junctions += k
                        if (k > begin) begin = k
                    }
                }
            }
            val sorted = at.distinct().sorted()
            val lights = java.util.TreeMap<Double, LatLon>()
            for (k in sorted) lights.putIfAbsent(geo.along[k], geo.shape[k])
            if (signals != null) {
                val engine = sorted.map { geo.along[it] }
                for ((m, point) in beside(geo, junctions, plain, signals)) {
                    if (engine.none { Math.abs(it - m) < SAME_JUNCTION_M }) lights.putIfAbsent(m, point)
                }
            }
            return RouteLights(lights.keys.toList(), lights.values.toList())
        }

        /** The pack's lights that belong to the route ([build]): metres along it and where each is drawn. */
        private fun beside(
            geo: RouteGeometry, junctions: List<Int>, plain: BooleanArray, signals: (Collection<Long>) -> List<LatLon>,
        ): List<Pair<Double, LatLon>> {
            val junctionsIn = HashMap<Long, MutableList<Int>>()
            val piecesIn = HashMap<Long, MutableList<Int>>()
            // a junction or a piece is listed in every cell a light near it can be in
            fun list(into: HashMap<Long, MutableList<Int>>, lat: Double, lon: Double, v: Int) {
                for (dy in CELL_MARGIN) for (dx in CELL_MARGIN) {
                    val l = into.getOrPut(cell(lat + dy, lon + dx)) { ArrayList() }
                    if (l.lastOrNull() != v) l += v
                }
            }
            for (k in junctions) list(junctionsIn, geo.shape[k].lat, geo.shape[k].lon, k)
            for (i in 0 until geo.shape.lastIndex) {
                if (!plain[i]) continue
                val a = geo.shape[i]
                val b = geo.shape[i + 1]
                val steps = Math.ceil((geo.along[i + 1] - geo.along[i]) / CELL_STEP_M).toInt().coerceIn(1, 1_000)
                for (n in 0..steps) {
                    val t = n.toDouble() / steps
                    list(piecesIn, a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t, i)
                }
            }
            val cells = junctionsIn.keys + piecesIn.keys
            if (cells.isEmpty()) return emptyList()
            val found = try {
                signals(cells)
            } catch (e: Exception) {
                emptyList()
            }
            val out = ArrayList<Pair<Double, LatLon>>()
            for (p in found) {
                val c = cell(p.lat, p.lon)
                var k = -1
                var best = NEAR_JUNCTION_M
                for (j in junctionsIn[c].orEmpty()) {
                    val d = Geo.distanceM(p, geo.shape[j])
                    if (d < best || (d == best && k < 0)) { best = d; k = j }
                }
                if (k >= 0) {
                    out += geo.along[k] to geo.shape[k]
                    continue
                }
                var onLine: Pair<Double, LatLon>? = null
                var near = ON_LINE_M
                for (i in piecesIn[c].orEmpty()) {
                    val a = geo.shape[i]
                    val b = geo.shape[i + 1]
                    val kx = Math.cos(Math.toRadians(p.lat))
                    val ax = (a.lon - p.lon) * kx
                    val ay = a.lat - p.lat
                    val dx = (b.lon - a.lon) * kx
                    val dy = b.lat - a.lat
                    val den = dx * dx + dy * dy
                    val t = if (den == 0.0) 0.0 else (-(ax * dx + ay * dy) / den).coerceIn(0.0, 1.0)
                    val q = LatLon(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)
                    val d = Geo.distanceM(p, q)
                    if (d <= near) {
                        near = d
                        onLine = (geo.along[i] + (geo.along[i + 1] - geo.along[i]) * t) to q
                    }
                }
                onLine?.takeIf { it.first > 0.0 && it.first < geo.lengthM - ON_LINE_M }?.let { out += it }
            }
            return out
        }

        /** A pack's light belongs to a junction of the route at most this far from it, */
        const val NEAR_JUNCTION_M = 20.0
        /** or stands on the route's own line: at most this far from it. */
        const val ON_LINE_M = 3.0
        /**
         * A long piece of the route is listed in the cells along it at steps of this many metres: less than the
         * margin below, so no cell the piece only clips is left out.
         */
        private const val CELL_STEP_M = 30.0
        /** 0.0004 degree: more than [NEAR_JUNCTION_M] north-south, and east-west up to 63 degrees of latitude. */
        private val CELL_MARGIN = doubleArrayOf(-0.0004, 0.0004)
        private val DRIVEABLE = setOf("forward", "backward", "both")
    }
}
