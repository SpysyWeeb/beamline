package io.github.spysyweeb.beamline.routing.planner

import io.github.spysyweeb.beamline.routing.model.Geo
import io.github.spysyweeb.beamline.routing.model.LatLon
import io.github.spysyweeb.beamline.routing.preset.EveryRequest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * A place tied to the street its address names (the owner, after batch C1; docs/architecture.md
 * 2.11 "Every request" 2). valhalla-mobile 0.6.4 has no locate call, so the planner finds the streets
 * around the place with one trace_attributes call ([request]: a grid of point pairs, each pair
 * matched alone to its nearest edge) and takes the nearest edge named like the address street
 * ([point]). A point of interest (batch E) is probed with a finer grid near it and each edge's use,
 * and ends in its own lot when there is one ([lotPoint]). The same as route_lib.address_probe_request /
 * address_tie_point / lot_tie_point: the requests are equal number for number (whole micro-degrees),
 * so the Python reference's recordings answer them.
 */
object AddressTie {
    /** route_lib.street_key: lower case, ASCII letters and digits, each word through [words] ("E 12th St" = "East 12th Street"). */
    fun streetKey(name: String, words: Map<String, String>): String {
        val out = mutableListOf<String>()
        val word = StringBuilder()
        for (ch in name.lowercase() + " ") {
            if (ch.code < 128 && ch.isLetterOrDigit()) word.append(ch)
            else if (word.isNotEmpty()) {
                val w = word.toString()
                out += words[w] ?: w
                word.setLength(0)
            }
        }
        return out.joinToString(" ")
    }

    private fun round6(x: Double) = floor(x * 1e6 + 0.5) / 1e6

    /** route_lib._grid: probe points every [step] m within [within] m of the place, each twice 0.55 m apart, whole micro-degrees. */
    private fun grid(lat: Double, lon: Double, step: Int, within: Int): List<JsonObject> {
        val latU = floor(lat * 1e6 + 0.5).toLong()
        val lonU = floor(lon * 1e6 + 0.5).toLong()
        val dLat = floor(step / 111320.0 * 1e6 + 0.5).toLong()
        val dLon = floor(dLat / cos(Math.toRadians(Math.floorDiv(latU, 10000L) / 100.0)) + 0.5).toLong()
        val n = within / step
        val out = mutableListOf<JsonObject>()
        for (i in -n..n) for (j in -n..n) {
            if ((i.toLong() * i + j.toLong() * j) * step * step <= within.toLong() * within) {
                val la = latU + i * dLat
                val lo = lonU + j * dLon
                out += point(la / 1e6, lo / 1e6)
                out += point((la + 5) / 1e6, lo / 1e6)
            }
        }
        return out
    }

    /** route_lib._probe. */
    private fun probe(shape: List<JsonObject>, rule: EveryRequest.AddressStreet, use: Boolean): JsonObject {
        val attributes = listOf("edge.names", "edge.road_class") + (if (use) listOf("edge.use") else emptyList()) +
            listOf("edge.begin_shape_index", "edge.end_shape_index", "shape")
        return buildJsonObject {
            put("shape", JsonArray(shape))
            put("costing", "auto")
            put("shape_match", "map_snap")
            put("trace_options", buildJsonObject {
                put("search_radius", rule.probeRadiusM)
                put("breakage_distance", 5)
                put("gps_accuracy", 5)
                put("interpolation_distance", 0)
            })
            put("filters", buildJsonObject {
                put("attributes", JsonArray(attributes.map(::JsonPrimitive)))
                put("action", "include")
            })
        }
    }

    /** route_lib.address_probe_request: [use] (a point of interest, batch E) adds edge.use (the match stays the same). */
    fun request(lat: Double, lon: Double, rule: EveryRequest.AddressStreet, use: Boolean = false): JsonObject =
        probe(grid(lat, lon, rule.probeStepM, rule.withinM), rule, use)

    /** route_lib.lot_probe_request (batch E): the second, small probe of a point of interest, a finer grid near it with edge.use. */
    fun lotRequest(lat: Double, lon: Double, rule: EveryRequest.AddressStreet, lot: EveryRequest.Lot): JsonObject =
        probe(grid(lat, lon, lot.probeStepM, lot.probeWithinM), rule, true)

    private fun point(lat: Double, lon: Double) = buildJsonObject {
        put("lat", lat)
        put("lon", lon)
    }

    /** One edge of a probe answer and its nearest point to the place (route_lib._probe_edges): metres east/north of the place. */
    private class Near(val edge: JsonObject, val dMin: Double, val at: Double, val pts: List<Pair<Double, Double>>, val seg: List<Double>) {
        val roadClass get() = (edge["road_class"] as? JsonPrimitive)?.contentOrNull
        val use get() = (edge["use"] as? JsonPrimitive)?.contentOrNull
        val names get() = (edge["names"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.contentOrNull } ?: emptyList()
    }

    /**
     * route_lib._probe_edges. An edge matched by one pair only, across it, comes back as a single
     * point; [points] keeps those (the lot search: the pair at the place gives the edge nearest to it).
     */
    private fun near(resp: JsonObject, lat: Double, lon: Double, points: Boolean): List<Near> {
        val shape = Geo.decodePolyline6((resp["shape"] as? JsonPrimitive)?.contentOrNull ?: "")
        val kx = 111320.0 * cos(Math.toRadians(lat))
        val ky = 111320.0
        val out = mutableListOf<Near>()
        for (el in resp["edges"]?.jsonArray ?: JsonArray(emptyList())) {
            val e = el.jsonObject
            val b = e["begin_shape_index"]?.jsonPrimitive?.int ?: continue
            val en = e["end_shape_index"]?.jsonPrimitive?.int ?: continue
            if (b < 0 || en >= shape.size || en < b) continue
            val pts = (b..en).map { k -> (shape[k].lon - lon) * kx to (shape[k].lat - lat) * ky }
            if (pts.size < 2) {
                if (points) out += Near(e, hypot(pts[0].first, pts[0].second), 0.0, pts, emptyList())
                continue
            }
            val seg = pts.zipWithNext { (x1, y1), (x2, y2) -> hypot(x2 - x1, y2 - y1) }
            var along = 0.0
            var dMin = -1.0
            var at = 0.0
            for (k in seg.indices) {
                val (x1, y1) = pts[k]
                val (x2, y2) = pts[k + 1]
                val l = seg[k]
                val t = if (l == 0.0) 0.0 else max(0.0, min(1.0, -(x1 * (x2 - x1) + y1 * (y2 - y1)) / (l * l)))
                val d = hypot(x1 + t * (x2 - x1), y1 + t * (y2 - y1))
                if (dMin < 0 || d < dMin) {
                    dMin = d
                    at = along + t * l
                }
                along += l
            }
            out += Near(e, dMin, at, pts, seg)
        }
        return out
    }

    /** route_lib._point_inside: [n]'s point at [Near.at], kept [margin] metres inside the edge (a single point: that point). */
    private fun inside(n: Near, margin: Int, lat: Double, lon: Double): LatLon {
        val total = n.seg.sum()
        val m = min(margin.toDouble(), total / 2)
        var at = max(m, min(total - m, n.at))
        var (x, y) = n.pts.last()
        for (k in n.seg.indices) {
            val l = n.seg[k]
            if (at <= l) {
                val f = if (l == 0.0) 0.0 else at / l
                val (x1, y1) = n.pts[k]
                val (x2, y2) = n.pts[k + 1]
                x = x1 + f * (x2 - x1)
                y = y1 + f * (y2 - y1)
                break
            }
            at -= l
        }
        val kx = 111320.0 * cos(Math.toRadians(lat))
        return LatLon(round6(lat + y / 111320.0), round6(lon + x / kx))
    }

    /** route_lib.address_tie_point: the point on the nearest edge named [street] (a road class in [classes]) within within_m, end_margin_m inside the edge; null when none. */
    fun point(resp: JsonObject, lat: Double, lon: Double, street: String, rule: EveryRequest.AddressStreet, classes: List<String>): LatLon? {
        val want = streetKey(street, rule.words)
        if (want.isEmpty()) return null
        var best: Near? = null
        for (n in near(resp, lat, lon, points = false)) {
            if (n.roadClass !in classes || n.names.none { streetKey(it, rule.words) == want }) continue
            if (n.dMin > rule.withinM || (best != null && n.dMin >= best.dMin)) continue
            best = n
        }
        return best?.let { inside(it, rule.endMarginM, lat, lon) }
    }

    /**
     * route_lib.lot_tie_point (batch E): from the probes of a point of interest, the point of the nearest
     * edge whose use is one of [EveryRequest.Lot.uses] (driveway, parking_aisle; never an alley or a
     * drive-through lane) within [EveryRequest.Lot.withinM] and at least [EveryRequest.Lot.streetMarginM]
     * nearer than every street of a road class in [classes], [margin] metres inside the edge; null when none.
     * [door] (docs/next-round.md item 28): the point is the place's door, so the uses are
     * [EveryRequest.Lot.doorUses] (a plain service road too: the lane along a store's front is often
     * mapped as one, and by the door it is not the delivery road behind the building).
     */
    fun lotPoint(resps: List<JsonObject>, lat: Double, lon: Double, lot: EveryRequest.Lot, margin: Int, classes: List<String>, door: Boolean = false): LatLon? {
        var best: Near? = null
        var streetD: Double? = null
        val uses = if (door) lot.doorUses else lot.uses
        for (n in resps.flatMap { near(it, lat, lon, points = true) }) {
            if (n.use in uses) {
                if (n.dMin <= lot.withinM && (best == null || n.dMin < best.dMin)) best = n
            } else if (n.roadClass in classes) {
                if (streetD == null || n.dMin < streetD) streetD = n.dMin
            }
        }
        val b = best ?: return null
        if (streetD != null && b.dMin + lot.streetMarginM > streetD) return null
        return inside(b, margin, lat, lon)
    }

    private fun distToPolyline(x: Double, y: Double, pts: List<Pair<Double, Double>>): Double {
        if (pts.size == 1) return hypot(pts[0].first - x, pts[0].second - y)
        var best = -1.0
        for (k in 0 until pts.size - 1) {
            val (x1, y1) = pts[k]
            val (x2, y2) = pts[k + 1]
            val dx = x2 - x1
            val dy = y2 - y1
            val l2 = dx * dx + dy * dy
            val t = if (l2 == 0.0) 0.0 else max(0.0, min(1.0, ((x - x1) * dx + (y - y1) * dy) / l2))
            val d = hypot(x1 + t * dx - x, y1 + t * dy - y)
            if (best < 0 || d < best) best = d
        }
        return best
    }

    /**
     * route_lib.left_out_rings (batch E2, docs/next-round.md item 19): the drive-through lanes and alleys the
     * stop's probes see (an edge whose use is one of [EveryRequest.LeftOut.uses] within [EveryRequest.LeftOut.withinM] of the place) as
     * exclude_polygons rings, nearest first: a square of 2 x boxHalfM at the middle of each such edge, in
     * whole micro-degrees, only where the middle is more than clearM from both ends of its edge and from
     * every other edge the probes see (the same edge matched the other way is not another edge; the box
     * never touches another edge). Each ring is five corners, the
     * first again last.
     */
    fun leftOutRings(resps: List<JsonObject>, lat: Double, lon: Double, rule: EveryRequest.LeftOut): List<List<LatLon>> {
        val edges = resps.flatMap { near(it, lat, lon, points = false) }
        val clear = rule.clearM.toDouble()
        val kx = 111320.0 * cos(Math.toRadians(lat))
        val found = mutableListOf<Pair<Double, Pair<Long, Long>>>()
        for (n in edges) {
            if (n.use !in rule.uses || n.dMin > rule.withinM) continue
            val total = n.seg.sum()
            if (total / 2 <= clear) continue
            val mid = inside(Near(n.edge, n.dMin, total / 2, n.pts, n.seg), 0, lat, lon)
            val mx = (mid.lon - lon) * kx
            val my = (mid.lat - lat) * 111320.0
            val rev = n.pts.asReversed()
            if (edges.any { o -> o.pts != n.pts && o.pts != rev && distToPolyline(mx, my, o.pts) <= clear }) continue
            val cu = floor(mid.lat * 1e6 + 0.5).toLong()
            val lu = floor(mid.lon * 1e6 + 0.5).toLong()
            if (found.any { it.second == cu to lu }) continue
            found += n.dMin to (cu to lu)
        }
        val h = rule.boxHalfM
        val corners = listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1, -1 to -1)
        return found.sortedBy { it.first }.map { (_, c) ->
            val (cu, lu) = c
            val dLat = floor(h / 111320.0 * 1e6 + 0.5).toLong()
            val dLon = floor(dLat / cos(Math.toRadians(Math.floorDiv(cu, 10000L) / 100.0)) + 0.5).toLong()
            corners.map { (i, j) -> LatLon((cu + j * dLat) / 1e6, (lu + i * dLon) / 1e6) }
        }
    }
}
