package io.github.spysyweeb.beamline.feature.states.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/** The asset reader, the projection and the tap rules behind StateShapes.kt (docs/architecture-downloads.md 6.8). */
internal object ShapesCode {

    private fun frameName(i: Inset) = when (i) {
        Inset.LOWER48 -> "lower48"
        Inset.ALASKA -> "alaska"
        Inset.HAWAII -> "hawaii"
    }

    private fun inset(name: String) = when (name) {
        "lower48" -> Inset.LOWER48
        "alaska" -> Inset.ALASKA
        "hawaii" -> Inset.HAWAII
        else -> throw IllegalArgumentException("unknown inset")
    }

    fun parse(json: String): StateShapes {
        val root = Json.parseToJsonElement(json.removePrefix("﻿")).jsonObject
        require((root["format"] as? JsonPrimitive)?.content == StateShapes.FORMAT) { "not a beamline-state-shapes/1 file" }
        val scale = root["scale"]!!.jsonPrimitive.double.toFloat()
        val frames = root["frames"]!!.jsonObject.mapKeys { inset(it.key) }.mapValues { (_, v) ->
            val o = v.jsonObject
            fun d(k: String) = o[k]!!.jsonPrimitive.double
            AlbersFrame(d("lat1"), d("lat2"), d("lat0"), d("lon0"), d("k"), d("dx"), d("dy"))
        }
        val boxes = (root["inset_boxes"] as? JsonObject ?: JsonObject(emptyMap())).mapKeys { inset(it.key) }.mapValues { (_, v) ->
            v.jsonArray.map { it.jsonPrimitive.double.toFloat() }.toFloatArray()
        }
        val states = root["states"]!!.jsonArray.map { e ->
            val o = e.jsonObject
            val rings = o["rings"]!!.jsonArray.map { r ->
                val a = (r as JsonArray).map { it.jsonPrimitive.int }
                require(a.size >= 6 && a.size % 2 == 0) { "a ring needs three points" }
                val out = FloatArray(a.size)
                var x = 0
                var y = 0
                for (i in a.indices step 2) {
                    if (i == 0) {
                        x = a[0]; y = a[1]
                    } else {
                        x += a[i]; y += a[i + 1]
                    }
                    out[i] = x / scale
                    out[i + 1] = y / scale
                }
                out
            }
            val label = o["label"]!!.jsonArray.map { it.jsonPrimitive.double.toFloat() / scale }
            StateShape(
                code = o["code"]!!.jsonPrimitive.content,
                fips = o["fips"]!!.jsonPrimitive.int,
                name = o["name"]!!.jsonPrimitive.content,
                inset = inset(o["inset"]!!.jsonPrimitive.content),
                labelX = label[0],
                labelY = label[1],
                box = o["box"]!!.jsonArray.map { it.jsonPrimitive.double.toFloat() / scale }.toFloatArray(),
                rings = rings,
                neighbours = o["neighbours"]!!.jsonArray.map { it.jsonPrimitive.content },
            )
        }
        return StateShapes(
            width = root["width"]!!.jsonPrimitive.double.toFloat(),
            height = root["height"]!!.jsonPrimitive.double.toFloat(),
            states = states,
            insetBoxes = boxes,
            frames = frames,
        )
    }

    /** The frame of a position: the same rule as frame_for in packbuilder/catalog/build_state_shapes.py. */
    fun frameFor(lon: Double, lat: Double): Inset? = when {
        lat >= 50.0 && (lon <= -129.0 || lon >= 170.0) -> Inset.ALASKA
        lat in 15.0..25.0 && lon in -165.0..-150.0 -> Inset.HAWAII
        lat in 10.0..60.0 && lon in -140.0..-50.0 -> Inset.LOWER48
        else -> null
    }

    /** Spherical Albers equal-area conic (Snyder), R = 1, then the frame's k, dx, dy (y grows downwards). */
    fun project(shapes: StateShapes, lon: Double, lat: Double): FloatArray? {
        val inset = frameFor(lon, lat) ?: return null
        val f = shapes.frames[inset] ?: return null
        val p1 = Math.toRadians(f.lat1)
        val p2 = Math.toRadians(f.lat2)
        val p0 = Math.toRadians(f.lat0)
        val n = (sin(p1) + sin(p2)) / 2
        val c = cos(p1) * cos(p1) + 2 * n * sin(p1)
        val rho0 = sqrt(c - 2 * n * sin(p0)) / n
        val l = if (lon > 0) lon - 360.0 else lon
        val rho = sqrt(c - 2 * n * sin(Math.toRadians(lat))) / n
        val theta = n * Math.toRadians(l - f.lon0)
        val x = rho * sin(theta)
        val y = rho0 - rho * cos(theta)
        return floatArrayOf((f.k * x + f.dx).toFloat(), (-f.k * y + f.dy).toFloat())
    }

    /** Even-odd over every ring (outer rings and holes alike). */
    fun contains(s: StateShape, x: Float, y: Float): Boolean {
        if (x < s.box[0] || x > s.box[2] || y < s.box[1] || y > s.box[3]) return false
        var inside = false
        for (r in s.rings) {
            var j = r.size - 2
            var i = 0
            while (i < r.size) {
                val xi = r[i]
                val yi = r[i + 1]
                val xj = r[j]
                val yj = r[j + 1]
                if ((yi > y) != (yj > y) && x < (xj - xi) * (y - yi) / (yj - yi) + xi) inside = !inside
                j = i
                i += 2
            }
        }
        return inside
    }

    /** Distance from (x, y) to the nearest edge of [s], in map units. */
    fun edgeDistance(s: StateShape, x: Float, y: Float): Float {
        var best = Float.MAX_VALUE
        for (r in s.rings) {
            var j = r.size - 2
            var i = 0
            while (i < r.size) {
                best = minOf(best, segment(x, y, r[j], r[j + 1], r[i], r[i + 1]))
                j = i
                i += 2
            }
        }
        return best
    }

    private fun segment(px: Float, py: Float, ax: Float, ay: Float, bx: Float, by: Float): Float {
        val dx = bx - ax
        val dy = by - ay
        val len = dx * dx + dy * dy
        if (len == 0f) return hypot(px - ax, py - ay)
        val t = (((px - ax) * dx + (py - ay) * dy) / len).coerceIn(0f, 1f)
        return hypot(px - ax - t * dx, py - ay - t * dy)
    }

    /**
     * StateHitTest.resolve. Inside a state: a chooser only when the tapped state itself is small on
     * screen (box long side under [HitRules.smallDp]) and another state's outline lies within an
     * eighth of [HitRules.nearDp] (2 dp: the error of a careful tap's centre) of the tap; it then
     * lists the tapped state and every state within nearDp, small or not, nearest first, at most
     * [HitRules.chooserMax]. Else One(the tapped state). Outside every state: the nearest outline
     * within nearDp, or a chooser when that one is small and a second state is within nearDp too.
     *
     * Where this differs from the wording of 6.8 ("the other candidates that are small with an edge
     * within 16 dp -> Choose([inside and them])"), and why: 10.2's cases fix the rule, and 6.8's
     * words fail three of them (StateShapesTest pins each reason).
     * - The radius inside a state is 2 dp, not 16 dp. At the fit zoom of a 393 dp card Pennsylvania
     *   is 41 x 26 dp and Maryland and West Virginia (31 dp each, so "small") lie within 11 dp of
     *   its middle, so 16 dp opens the chooser for central Pennsylvania, against 10.2's One(PA). The
     *   10.2 cases fix the radius between 1.9 dp (RI's label must offer CT) and 2.6 dp (DC's label
     *   at 8x must be DC).
     * - The tapped state must be small. Natural Earth's Wisconsin holds its half of Lake Michigan,
     *   is small at the fit zoom (about 43 dp) and its water edge passes within 2 dp of the lake's
     *   middle, so "another small state near" gives Choose(MI, WI) there, against 10.2's One(MI).
     *   The cost: 1 dp inside a large state next to a small one (Virginia next to DC) gives the
     *   large one; DC's chooser opens from DC itself and from Maryland's side.
     * - The chooser lists every state within 16 dp, not only small ones: at DC's label point 10.2
     *   wants VA in the list, and Virginia (about 60 dp wide at the fit zoom) is not small.
     */
    fun resolve(shapes: StateShapes, x: Float, y: Float, unitsPerDp: Float, rules: HitRules): TapResult {
        val near = rules.nearDp * unitsPerDp
        val small = rules.smallDp * unitsPerDp
        val candidates = shapes.states.filter { s ->
            x >= s.box[0] - near && x <= s.box[2] + near && y >= s.box[1] - near && y <= s.box[3] + near
        }
        if (candidates.isEmpty()) return TapResult.None
        fun isSmall(s: StateShape) = maxOf(s.box[2] - s.box[0], s.box[3] - s.box[1]) < small
        val inside = candidates.firstOrNull { contains(it, x, y) }
        val distances = candidates.associateWith { if (it === inside) 0f else edgeDistance(it, x, y) }
        val within = candidates.filter { distances.getValue(it) <= near }.sortedBy { distances.getValue(it) }
        if (inside != null) {
            val close = within.any { it !== inside && distances.getValue(it) <= near / 8 }
            return if (isSmall(inside) && close) {
                TapResult.Choose((listOf(inside) + within.filter { it !== inside }).take(rules.chooserMax).map { it.code })
            } else {
                TapResult.One(inside.code)
            }
        }
        val nearest = within.firstOrNull() ?: return TapResult.None
        return if (isSmall(nearest) && within.size > 1) {
            TapResult.Choose(within.take(rules.chooserMax).map { it.code })
        } else {
            TapResult.One(nearest.code)
        }
    }
}
