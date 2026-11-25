package io.github.spysyweeb.beamline.nav

import io.github.spysyweeb.beamline.api.nav.LaneArrow
import io.github.spysyweeb.beamline.api.nav.LaneMark
import io.github.spysyweeb.beamline.api.nav.LaneView
import io.github.spysyweeb.beamline.routing.banner.BannerRules
import io.github.spysyweeb.beamline.routing.model.Geo
import io.github.spysyweeb.beamline.routing.model.LatLon
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Lanes that end in a turn (rule B9, batch C1, docs/next-round.md item 12). A spot is an
 * intersection the route goes straight through (an OSRM step's intersection after its first, so no
 * maneuver there) whose lanes say fewer lanes go on with the route ([valid]) than the road had
 * before its turn lanes began ([before]: the fewest lanes of the road pieces in the
 * `lane_warning.lookback_m` before the intersection, not before the step's own maneuver, from the
 * engine's `trace_attributes` edge.lane_count). An added turn lane alone never makes a spot: then
 * the lanes that go on are as many as the road had. When the road before shows as many lanes as the
 * intersection (no turn lane seen being added), it counts only after `same_count_evidence_m` of it:
 * a short stretch (the route just turned onto the road) may be the turn pocket itself.
 *
 * Which side's lanes end: the lanes not for the route to the right of the ones that go on, or to the
 * left; when both sides have some, the lanes the road gained are taken to be the left-turn lanes on
 * the left (the usual US pocket), so the right ones are the through lanes that end.
 *
 * The lane to take at a fork (docs/next-round.md item 37, 2026-11-21): an added turn lane alone never
 * warns, and that is wrong where the road forks. Two roads go on from the junction, the engine counts
 * one of them as straight ahead and gives no instruction, and the lanes the road gains just before
 * are one for each road (the left lane to one, the right lane to the road the route takes; a sign
 * over the road says so, the app said nothing). A candidate that makes no spot
 * by the rule above is a fork when another road leaves its junction within `lane_warning.fork_angle_deg`
 * of straight ahead ([Candidate.forkLike], from the OSRM bearings), and the engine says of that
 * junction ([forkRequest], [forkOf]: one more trace_attributes call) that such a road is a plain road
 * the driver may enter, as important as the road the route goes on by, or more. The spot then names
 * the lanes to use and that road: "Use the right lane for Mill Rd". A side street at that angle,
 * a ramp, a driveway or a turn lane's own little road is no fork. Two roads of one class that cross at
 * less than that angle are taken for a fork too: the lanes shown are right there as well. On 300 routes in Pennsylvania
 * (3,854 km) 13 junctions are forks by this rule.
 */
class LaneWarnings internal constructor(val spots: List<Spot>) {

    /** One intersection: [alongM] along the route (RouteGeometry.along), its lanes and the line to show. */
    class Spot(val alongM: Double, val lanes: List<LaneView>, val text: String, val valid: Int, val total: Int, val before: Int)

    /** The first spot not yet passed by more than [afterM] at [alongM], with its index. */
    fun next(alongM: Double, afterM: Double): IndexedValue<Spot>? =
        spots.withIndex().firstOrNull { it.value.alongM + afterM > alongM }

    /** An intersection worth asking the engine about: lanes where some are not for the route. */
    class Candidate(
        val shapeIndex: Int,
        val alongM: Double,
        val lanes: List<Pair<List<String>, Boolean>>,
        /** First shape index of the stretch to trace (lookback, not before the step's maneuver). */
        val fromIndex: Int,
        val road: String?,
        val highway: Boolean,
        /** Another road the driver may enter leaves the intersection within lane_warning.fork_angle_deg of straight ahead. */
        val forkLike: Boolean = false,
    )

    companion object {
        private val json = Json { ignoreUnknownKeys = true }
        val NONE = LaneWarnings(emptyList())

        /** A spot this close before the route's next maneuver is that maneuver's lanes, not a lane ending. */
        const val NEAR_TURN_M = 60.0

        /** How close the route's next turn must be for lanes turning its way to count as its own (2026-11-25). */
        const val SAME_SIDE_TURN_M = 300.0

        private fun sideOf(type: io.github.spysyweeb.beamline.routing.model.ManeuverType?): Int = when (type?.name) {
            "SLIGHT_RIGHT", "RIGHT", "SHARP_RIGHT", "RAMP_RIGHT", "EXIT_RIGHT", "STAY_RIGHT", "UTURN_RIGHT" -> 1
            "SLIGHT_LEFT", "LEFT", "SHARP_LEFT", "RAMP_LEFT", "EXIT_LEFT", "STAY_LEFT", "UTURN_LEFT" -> -1
            else -> 0
        }

        /**
         * [lanes] with the lanes on the side the route turns to next ([type]), outside the ones that go
         * on and every arrow pointing that way, marked as going on: they are the next turn's lanes,
         * painted (and tagged) before it, not lanes to leave.
         */
        fun withNextTurnLanes(lanes: List<Pair<List<String>, Boolean>>, type: io.github.spysyweeb.beamline.routing.model.ManeuverType?): List<Pair<List<String>, Boolean>> {
            val side = sideOf(type)
            val first = lanes.indexOfFirst { it.second }
            val last = lanes.indexOfLast { it.second }
            if (side == 0 || first < 0) return lanes
            val word = if (side > 0) "right" else "left"
            val range = if (side > 0) (last + 1) until lanes.size else 0 until first
            if (range.isEmpty() || range.any { i -> lanes[i].first.isEmpty() || lanes[i].first.any { word !in it } }) return lanes
            return lanes.mapIndexed { i, l -> if (i in range) l.first to true else l }
        }

        /** The route goes straight on here: a real intersection (3 or more roads), out within 30 degrees of in, a valid lane marked straight. */
        private fun goesStraight(it: JsonObject, lanes: List<Pair<List<String>, Boolean>>): Boolean {
            val b = (it["bearings"] as? JsonArray)?.map { x -> x.jsonPrimitive.doubleOrNull ?: return false } ?: return false
            val inI = it["in"]?.jsonPrimitive?.intOrNull ?: return false
            val outI = it["out"]?.jsonPrimitive?.intOrNull ?: return false
            if (b.size < 3 || inI !in b.indices || outI !in b.indices) return false
            var turn = (b[outI] - (b[inI] + 180.0)) % 360.0
            if (turn < 0) turn += 360.0
            if (turn > 180.0) turn -= 360.0
            return kotlin.math.abs(turn) <= 30.0 && lanes.any { l -> l.second && "straight" in l.first }
        }

        /** Another road the driver may enter leaves the intersection within [angleDeg] of straight ahead (never when [angleDeg] is negative). */
        private fun otherRoadAhead(it: JsonObject, angleDeg: Double): Boolean {
            if (angleDeg < 0.0) return false
            val b = (it["bearings"] as? JsonArray)?.map { x -> x.jsonPrimitive.doubleOrNull ?: return false } ?: return false
            val entry = (it["entry"] as? JsonArray)?.map { x -> x.jsonPrimitive.booleanOrNull ?: false }
            val inI = it["in"]?.jsonPrimitive?.intOrNull ?: return false
            val outI = it["out"]?.jsonPrimitive?.intOrNull ?: return false
            if (inI !in b.indices || outI !in b.indices) return false
            return b.indices.any { k ->
                k != inI && k != outI && entry?.getOrNull(k) != false && RouteGeometry.angleDiff(b[k], b[inI] + 180.0) <= angleDeg
            }
        }

        /**
         * The candidates of an OSRM answer laid over [geo] (the same path: the OSRM request is the
         * chosen request). Never throws: a broken answer gives none.
         */
        fun candidates(
            geo: RouteGeometry, osrmJson: String, routeIndex: Int, lookbackM: Double, bannerRules: BannerRules, forkAngleDeg: Double = -1.0,
        ): List<Candidate> = try {
            val out = ArrayList<Candidate>()
            val r = json.parseToJsonElement(osrmJson).jsonObject["routes"]!!.jsonArray[routeIndex].jsonObject
            var cursor = 0
            for (leg in r["legs"]!!.jsonArray) for (step in leg.jsonObject["steps"]!!.jsonArray) {
                val ints = (step.jsonObject["intersections"] as? JsonArray) ?: continue
                if (ints.isEmpty()) continue
                val stepStart = vertexOf(geo, ints[0].jsonObject, cursor) ?: continue
                cursor = stepStart
                // a turn-lane tag covers every piece of its way: the same lanes repeat at each node
                // before the real intersection, so of a run of equal lanes only the last counts
                var run: Pair<Int, List<Pair<List<String>, Boolean>>>? = null
                val picked = ArrayList<Pair<Int, List<Pair<List<String>, Boolean>>>>()
                val straightAt = HashMap<Int, Boolean>()
                val forkAt = HashMap<Int, Boolean>()
                for (k in 1 until ints.size) {
                    val it = ints[k].jsonObject
                    val lanes = (it["lanes"] as? JsonArray)?.map { l ->
                        val o = l.jsonObject
                        val ind = (o["indications"] as? JsonArray)?.map { s -> s.jsonPrimitive.content } ?: emptyList()
                        ind to ((o["valid"]?.jsonPrimitive?.booleanOrNull ?: false) || (o["active"]?.jsonPrimitive?.booleanOrNull ?: false))
                    }
                    if (lanes == null) {
                        run?.let { picked += it }
                        run = null
                        continue
                    }
                    val idx = vertexOf(geo, it, cursor) ?: continue
                    cursor = idx
                    straightAt[idx] = goesStraight(it, lanes)
                    forkAt[idx] = otherRoadAhead(it, forkAngleDeg)
                    if (run != null && run!!.second != lanes) picked += run!!
                    run = idx to lanes
                }
                run?.let { picked += it }
                for ((idx, raw) in picked) {
                    if (straightAt[idx] != true) continue
                    val along = geo.along[idx]
                    val k = maneuverAt(geo, idx)
                    val nextTurnAlong = if (k + 1 < geo.maneuverCount) geo.maneuverAlong[k + 1] else geo.lengthM
                    if (nextTurnAlong - along < NEAR_TURN_M) continue // the lanes belong to the turn just after
                    // a reported drive, 2026-11-25 (west on West 26th St, right onto Peninsula Dr): the
                    // road's pieces 70-90 m before the turn carry the turn's arrows ("left;through|
                    // through|right", "left|left|through|through|right"), and the right lane, the one for
                    // the turn, was called a lane to leave. Lanes on the side the route turns to next,
                    // that close and pointing its way, lead to that turn: they go on with the route.
                    val lanes = if (nextTurnAlong - along < SAME_SIDE_TURN_M) withNextTurnLanes(raw, geo.route.maneuvers.getOrNull(k + 1)?.type) else raw
                    val v = lanes.count { l -> l.second }
                    if (v == 0 || v == lanes.size) continue
                    var from = idx
                    while (from > stepStart && along - geo.along[from - 1] <= lookbackM) from--
                    if (from >= idx) continue
                    val m = geo.route.maneuvers.getOrNull(k)
                    out += Candidate(idx, along, lanes, from, m?.streetNames?.firstOrNull()?.let(bannerRules::abbreviate), m?.highway ?: false,
                        forkAt[idx] == true)
                }
            }
            out
        } catch (e: Exception) {
            emptyList()
        }

        /** The trace_attributes request for [c]: the route's points from [Candidate.fromIndex] to the intersection. */
        fun traceRequest(geo: RouteGeometry, c: Candidate): JsonObject = buildJsonObject {
            put("shape", buildJsonArray {
                for (i in c.fromIndex..c.shapeIndex) add(buildJsonObject { put("lat", geo.shape[i].lat); put("lon", geo.shape[i].lon) })
            })
            put("costing", "auto")
            put("shape_match", "edge_walk")
            put("filters", buildJsonObject {
                put("attributes", buildJsonArray { add(JsonPrimitive("edge.lane_count")); add(JsonPrimitive("edge.length")) })
                put("action", "include")
            })
            put("units", "kilometers")
        }

        /** The road before: its fewest lanes (>= 1) and the metres of road with that many; null when the answer has none. */
        class Before(val lanes: Int, val lengthM: Double)

        fun lanesBefore(traceJson: String): Before? = try {
            val edges = json.parseToJsonElement(traceJson).jsonObject["edges"]!!.jsonArray.mapNotNull { e ->
                val o = e.jsonObject
                val n = o["lane_count"]?.jsonPrimitive?.intOrNull?.takeIf { it >= 1 } ?: return@mapNotNull null
                n to (o["length"]?.jsonPrimitive?.doubleOrNull ?: 0.0) * 1000.0
            }
            edges.minOfOrNull { it.first }?.let { b -> Before(b, edges.filter { it.first == b }.sumOf { it.second }) }
        } catch (e: Exception) {
            null
        }

        /** The junction walk of [forkRequest] starts about this far before the junction (not before the candidate's own stretch) */
        const val FORK_BACK_M = 60.0
        /** and ends about this far after it (not after the leg): the engine then names the road the route goes on by. */
        const val FORK_ON_M = 30.0

        /**
         * The trace_attributes request about the junction of [c] (item 37): the route's points from about
         * [FORK_BACK_M] before it to [FORK_ON_M] after, asked for the road before and after and for the
         * other roads at each edge's end. Null when the route has no point on either side.
         */
        fun forkRequest(geo: RouteGeometry, c: Candidate): JsonObject? {
            val legEnd = geo.legEndIndex.firstOrNull { it >= c.shapeIndex } ?: geo.shape.lastIndex
            var lo = c.shapeIndex
            while (lo > c.fromIndex && c.alongM - geo.along[lo] < FORK_BACK_M) lo--
            var hi = c.shapeIndex
            while (hi < legEnd && geo.along[hi] - c.alongM < FORK_ON_M) hi++
            if (lo >= c.shapeIndex || hi <= c.shapeIndex) return null
            return buildJsonObject {
                put("shape", buildJsonArray {
                    for (i in lo..hi) add(buildJsonObject { put("lat", geo.shape[i].lat); put("lon", geo.shape[i].lon) })
                })
                put("costing", "auto")
                put("shape_match", "edge_walk")
                put("filters", buildJsonObject {
                    put("attributes", buildJsonArray {
                        for (a in FORK_ATTRIBUTES) add(JsonPrimitive(a))
                    })
                    put("action", "include")
                })
            }
        }

        private val FORK_ATTRIBUTES = listOf(
            "edge.end_shape_index", "edge.names", "edge.road_class", "edge.end_heading", "node.intersecting_edge.begin_heading",
            "node.intersecting_edge.road_class", "node.intersecting_edge.use", "node.intersecting_edge.driveability", "shape",
        )
        /** The engine's road classes, the most important first. */
        private val ROAD_RANK = listOf("motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential", "service_other")
            .withIndex().associate { it.value to it.index }

        /** A fork: the road the route goes on by after it (the engine's first name of it; null: it has none). */
        class Fork(val street: String?)

        /**
         * What the engine says of the junction at [junction] ([forkRequest]'s answer): a [Fork] when another
         * road leaves it within [angleDeg] of the way the route arrives, is a plain road (the engine's use
         * "road": not a ramp, a turn lane's own road, a driveway or an alley), may be driven into, and is as
         * important as the road the route goes on by or more. Null: no fork, or the answer cannot be read.
         * The junction is found by its place (the edge whose end is within 3 m of it): the answer's shape
         * indexes are those of its own shape, not always the request's.
         */
        fun forkOf(traceJson: String, junction: LatLon, angleDeg: Double): Fork? = try {
            val root = json.parseToJsonElement(traceJson).jsonObject
            val edges = root["edges"]!!.jsonArray.map { it.jsonObject }
            val pts = Geo.decodePolyline6(root["shape"]!!.jsonPrimitive.content)
            var k = -1
            var best = 3.0
            edges.forEachIndexed { i, e ->
                val p = e["end_shape_index"]?.jsonPrimitive?.intOrNull?.let(pts::getOrNull) ?: return@forEachIndexed
                val d = Geo.distanceM(p, junction)
                if (d < best) { best = d; k = i }
            }
            val into = edges.getOrNull(k)
            val onward = edges.getOrNull(k + 1)
            val heading = into?.get("end_heading")?.jsonPrimitive?.doubleOrNull
            val rank = onward?.let { ROAD_RANK[(it["road_class"] as? JsonPrimitive)?.content] }
            val others = (into?.get("end_node") as? JsonObject)?.get("intersecting_edges") as? JsonArray
            if (k < 0 || heading == null || rank == null || others == null) {
                null
            } else {
                val fork = others.any { x ->
                    val o = x as? JsonObject ?: return@any false
                    val h = o["begin_heading"]?.jsonPrimitive?.doubleOrNull ?: return@any false
                    (o["use"] as? JsonPrimitive)?.content == "road" &&
                        (o["driveability"] as? JsonPrimitive)?.content in setOf("forward", "both") &&
                        (ROAD_RANK[(o["road_class"] as? JsonPrimitive)?.content] ?: Int.MAX_VALUE) <= rank &&
                        RouteGeometry.angleDiff(h, heading) <= angleDeg
                }
                if (fork) Fork((onward["names"] as? JsonArray)?.firstOrNull()?.jsonPrimitive?.content) else null
            }
        } catch (e: Exception) {
            null
        }

        /**
         * The spots of a route: [trace] answers a trace_attributes request (null or an exception: no
         * spot there). At most [maxCalls] candidates are asked, nearest first. Never throws.
         */
        fun build(geo: RouteGeometry, osrmJson: String, routeIndex: Int, rules: GuidanceRules, bannerRules: BannerRules, trace: (String) -> String?): LaneWarnings {
            val spots = ArrayList<Spot>()
            val lw = rules.laneWarning
            for (c in candidates(geo, osrmJson, routeIndex, lw.lookbackM, bannerRules, lw.forkAngleDeg).take(lw.maxCalls)) {
                val before = try {
                    trace(traceRequest(geo, c).toString())?.let(::lanesBefore)
                } catch (e: Exception) {
                    null
                }
                // the road's own lane count is known when turn lanes were seen to be added (fewer lanes
                // before than at the intersection), or when it had that many lanes for a long stretch
                val ends = before?.takeIf { it.lanes < c.lanes.size || it.lengthM >= lw.sameCountEvidenceM }?.let { spot(c, it.lanes) }
                if (ends != null) {
                    spots += ends
                    continue
                }
                // item 37: no lane ends, but the road may fork here
                if (!c.forkLike) continue
                val fork = try {
                    forkRequest(geo, c)?.let { trace(it.toString()) }?.let { forkOf(it, geo.shape[c.shapeIndex], lw.forkAngleDeg) }
                } catch (e: Exception) {
                    null
                } ?: continue
                forkSpot(c, before?.lanes ?: c.lanes.size, fork.street?.let(bannerRules::abbreviate))?.let { spots += it }
            }
            return LaneWarnings(spots)
        }

        /**
         * The spot of a fork (item 37): the lanes to use, by their side when they are together at one
         * ("Use the right lane for Mill Rd", "Use the left 2 lanes"), else "Use the marked lane";
         * [street]: the road the route goes on by (else the road it is on). Null when every lane, or none, is for the route.
         */
        fun forkSpot(c: Candidate, before: Int, street: String?): Spot? {
            val n = c.lanes.size
            val v = c.lanes.count { it.second }
            if (v == 0 || v == n) return null
            val first = c.lanes.indexOfFirst { it.second }
            val last = c.lanes.indexOfLast { it.second }
            val together = last - first + 1 == v
            val lanes = when {
                together && last == n - 1 -> if (v == 1) "Use the right lane" else "Use the right $v lanes"
                together && first == 0 -> if (v == 1) "Use the left lane" else "Use the left $v lanes"
                v == 1 -> "Use the marked lane"
                else -> "Use the marked lanes"
            }
            return Spot(c.alongM, views(c), lanes + road(street?.takeIf { it.isNotBlank() } ?: c.road), v, n, before)
        }

        /** The lanes of [c] as the banner draws them: BEST for the route's, NOT_FOR_ROUTE for the others. */
        private fun views(c: Candidate): List<LaneView> = c.lanes.map { (ind, ok) ->
            val arrows = ind.mapNotNull(::arrowOf).toSet().ifEmpty { setOf(LaneArrow.NONE) }
            val used = if (ok) (arrows.firstOrNull { it == LaneArrow.THROUGH } ?: arrows.first()) else null
            LaneView(arrows, if (ok) LaneMark.BEST else LaneMark.NOT_FOR_ROUTE, used)
        }

        /** The spot of [c] when the road had [before] lanes, or null when no lane that went on ends here. */
        fun spot(c: Candidate, before: Int): Spot? {
            val n = c.lanes.size
            val v = c.lanes.count { it.second }
            if (before <= v || before > n) return null
            val first = c.lanes.indexOfFirst { it.second }
            val last = c.lanes.indexOfLast { it.second }
            val l = first
            val r = n - 1 - last
            val trapped = before - v
            val added = n - before
            val trappedLeft = when {
                r == 0 -> trapped
                l == 0 -> 0
                else -> maxOf(0, minOf(l, l - added))
            }
            val trappedRight = minOf(r, trapped - trappedLeft)
            val text = when {
                trappedRight > 0 && trappedLeft == 0 ->
                    sideText("Right", c.lanes.takeLast(trappedRight).map { it.first }, trappedRight, c.highway) + ": keep left" + road(c.road)
                trappedLeft > 0 && trappedRight == 0 ->
                    sideText("Left", c.lanes.take(trappedLeft).map { it.first }, trappedLeft, c.highway) + ": keep right" + road(c.road)
                else -> (if (v == 1) "Keep to the marked lane" else "Keep to the marked lanes") + road(c.road)
            }
            return Spot(c.alongM, views(c), text, v, n, before)
        }

        private fun road(name: String?) = if (name.isNullOrBlank()) "" else " for $name"

        /** "Right lane turns right", "Left 2 lanes turn left", "Right lane exits" (highway), "Right lane ends". */
        private fun sideText(side: String, ind: List<List<String>>, count: Int, highway: Boolean): String {
            val lanes = if (count == 1) "$side lane" else "$side $count lanes"
            val word = side.lowercase()
            val allTurn = ind.all { l -> l.isNotEmpty() && l.all { it.endsWith(word) } }
            val verb = when {
                highway -> if (count == 1) "exits" else "exit"
                allTurn -> if (count == 1) "turns $word" else "turn $word"
                else -> if (count == 1) "ends" else "end"
            }
            return "$lanes $verb"
        }

        private fun arrowOf(s: String): LaneArrow? = when (s) {
            "left" -> LaneArrow.LEFT
            "slight left" -> LaneArrow.SLIGHT_LEFT
            "sharp left" -> LaneArrow.SHARP_LEFT
            "straight" -> LaneArrow.THROUGH
            "slight right" -> LaneArrow.SLIGHT_RIGHT
            "right" -> LaneArrow.RIGHT
            "sharp right" -> LaneArrow.SHARP_RIGHT
            "uturn" -> LaneArrow.REVERSE
            "none" -> LaneArrow.NONE
            else -> null
        }

        /** The shape vertex at an OSRM intersection's location, searched from [from] on (within 3 m). */
        private fun vertexOf(geo: RouteGeometry, intersection: JsonObject, from: Int): Int? {
            val loc = intersection["location"]?.jsonArray ?: return null
            val p = LatLon(loc[1].jsonPrimitive.doubleOrNull ?: return null, loc[0].jsonPrimitive.doubleOrNull ?: return null)
            var best = -1
            var bestD = Double.MAX_VALUE
            for (i in from until geo.shape.size) {
                val d = Geo.distanceM(p, geo.shape[i])
                if (d < bestD) { bestD = d; best = i }
                if (bestD < 0.5 && d > 50.0) break
            }
            return best.takeIf { it >= 0 && bestD <= 3.0 }
        }

        /** The maneuver whose stretch holds shape point [idx]. */
        private fun maneuverAt(geo: RouteGeometry, idx: Int): Int {
            var k = 0
            while (k + 1 < geo.maneuverShapeIndex.size && geo.maneuverShapeIndex[k + 1] <= idx) k++
            return k
        }
    }
}
