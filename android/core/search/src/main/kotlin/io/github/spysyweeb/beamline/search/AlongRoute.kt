package io.github.spysyweeb.beamline.search

import io.github.spysyweeb.beamline.api.model.GeoPoint
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Item 51 (rule S12 of docs/architecture.md 3.8): places along a route line, for a new stop of a trip. Pure
 * functions; the search controller runs [search] on the search thread, one engine search per anchor. Distances
 * are equirectangular per segment (a few metres off over a 10 km segment, far below the 3 km corridor). Never
 * logs (PV2: the line is where the user drives).
 */
object AlongRoute {
    /** Km per degree of latitude on Geo.haversineKm's sphere (6371.0088 km). */
    private const val KM_PER_DEG = 6371.0088 * Math.PI / 180.0

    /** Shape points closer than this to the last one kept are dropped first, so [place] stays cheap on long legs. */
    private const val THIN_KM = 0.05

    /** Rows whose lead is one of these answer "which of these is near": a category word ("gas") or a brand ("sheetz") (S11's classes). */
    private val CLASS_TIERS = setOf("category", "brand")

    /** Points every [sampleKm] along [line] from its first point (included) up to [aheadKm] (or the line's end). */
    fun anchors(line: List<GeoPoint>, sampleKm: Double, aheadKm: Double): List<GeoPoint> {
        val pts = thin(line)
        if (pts.isEmpty()) return emptyList()
        val out = arrayListOf(pts[0])
        if (sampleKm <= 0.0) return out
        var start = 0.0        // distance along the line where the current segment starts
        var next = sampleKm    // the next anchor's distance along the line
        for (i in 1 until pts.size) {
            val a = pts[i - 1]
            val b = pts[i]
            val len = km(a, b)
            while (next <= aheadKm + EPS && next <= start + len + EPS) {
                val t = if (len <= 0.0) 0.0 else ((next - start) / len).coerceIn(0.0, 1.0)
                out.add(GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t))
                next += sampleKm
            }
            start += len
            if (next > aheadKm + EPS) break
        }
        return out
    }

    /**
     * (offKm, alongKm) of [p] to [line] cut at [aheadKm]: the distance to the nearest point of the line and the
     * length along the line to that point; null for an empty line.
     */
    fun place(line: List<GeoPoint>, p: GeoPoint, aheadKm: Double): Pair<Double, Double>? = placeOn(thin(line), p, aheadKm)

    /**
     * [ask] (one search of the text around a point) at every anchor of [line]; rows deduplicated by pack and id
     * (the first answer kept). An answer led by a category or a brand match: the rows within
     * [SearchAppRules.alongCorridorKm] of the line (cut at [SearchAppRules.alongAheadKm]), the
     * [SearchAppRules.alongMaxResults] nearest the line (ties: nearest ahead), listed in order along the line;
     * when none is within the corridor, the [SearchAppRules.alongNearestWhenNone] nearest the line anyway, in
     * order along it, and [Along.widened] is true. Any other answer (a name, a street, an address): the rows by
     * score, the best [limit], in that order (no corridor: a store named by the user a few miles off the road
     * still shows).
     */
    fun search(line: List<GeoPoint>, rules: SearchAppRules, limit: Int, ask: (GeoPoint) -> MultiSearchResponse): Along {
        val pts = thin(line)
        val answers = anchors(pts, rules.alongSampleKm, rules.alongAheadKm).map(ask)
        val seen = HashSet<String>()
        val rows = answers.flatMap { it.results }.filter { seen.add("${it.packId}:${it.result.id}") }
        val lead = answers.firstNotNullOfOrNull { a -> a.results.firstOrNull() }?.result?.tier
        var widened = false
        val chosen = if (lead in CLASS_TIERS) {
            val placed = rows.mapNotNull { r -> placeOn(pts, GeoPoint(r.result.lat, r.result.lon), rules.alongAheadKm)?.let { r to it } }
            val nearest = compareBy<Pair<PackResult, Pair<Double, Double>>>({ it.second.first }, { it.second.second })
            val within = placed.filter { it.second.first <= rules.alongCorridorKm }
            val kept = if (within.isNotEmpty()) within.sortedWith(nearest).take(rules.alongMaxResults)
            else placed.sortedWith(nearest).take(rules.alongNearestWhenNone).also { widened = it.isNotEmpty() }
            kept.sortedBy { it.second.second }.map { it.first }
        } else {
            rows.withIndex().sortedWith(compareBy({ -it.value.result.score }, { it.index })).map { it.value }.take(limit)
        }
        val covers = answers.map { it.outOfCoverage }
        val response = MultiSearchResponse(
            query = answers.firstOrNull()?.query ?: "",
            results = chosen,
            packsAsked = answers.flatMap { it.packsAsked }.distinct(),
            mergedByScore = answers.all { it.mergedByScore },
            houseNumber = answers.firstNotNullOfOrNull { it.houseNumber },
            outOfCoverage = when {
                covers.isEmpty() || covers.any { it == null } -> null
                else -> covers.all { it == true }
            },
            ms = answers.sumOf { it.ms },
            namedPlace = answers.firstNotNullOfOrNull { it.namedPlace },
        )
        return Along(response, widened)
    }

    private const val EPS = 1e-6

    /** Equirectangular km between two points (the mean latitude's scale). */
    private fun km(a: GeoPoint, b: GeoPoint): Double {
        val x = (b.lon - a.lon) * cos(Math.toRadians((a.lat + b.lat) / 2)) * KM_PER_DEG
        val y = (b.lat - a.lat) * KM_PER_DEG
        return sqrt(x * x + y * y)
    }

    /** [line] without shape points closer than [THIN_KM] to the last one kept; its last point always kept. */
    private fun thin(line: List<GeoPoint>): List<GeoPoint> {
        if (line.size <= 2) return line
        val out = arrayListOf(line[0])
        for (i in 1 until line.size - 1) if (km(out.last(), line[i]) >= THIN_KM) out.add(line[i])
        val last = line.last()
        if (km(out.last(), last) > 0.0 || out.size == 1) out.add(last)
        return out
    }

    private fun placeOn(pts: List<GeoPoint>, p: GeoPoint, aheadKm: Double): Pair<Double, Double>? {
        if (pts.isEmpty()) return null
        if (pts.size == 1) return km(pts[0], p) to 0.0
        var best: Pair<Double, Double>? = null
        var start = 0.0
        for (i in 1 until pts.size) {
            if (start > aheadKm) break
            val a = pts[i - 1]
            val b = pts[i]
            val scale = cos(Math.toRadians((a.lat + b.lat) / 2)) * KM_PER_DEG
            val bx = (b.lon - a.lon) * scale
            val by = (b.lat - a.lat) * KM_PER_DEG
            val px = (p.lon - a.lon) * scale
            val py = (p.lat - a.lat) * KM_PER_DEG
            val len2 = bx * bx + by * by
            val len = sqrt(len2)
            val maxT = if (len <= 0.0) 0.0 else ((aheadKm - start) / len).coerceIn(0.0, 1.0)
            val t = if (len2 <= 0.0) 0.0 else ((px * bx + py * by) / len2).coerceIn(0.0, maxT)
            val dx = px - bx * t
            val dy = py - by * t
            val off = sqrt(dx * dx + dy * dy)
            val along = start + len * t
            val cur = best
            if (cur == null || off < cur.first) best = off to along
            start += len
        }
        return best
    }
}

/** [AlongRoute.search]'s answer: [response] as one engine answer; [widened]: no class match was within the corridor, the nearest are shown. */
data class Along(val response: MultiSearchResponse, val widened: Boolean)
