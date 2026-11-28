package io.github.spysyweeb.beamline.search

import io.github.spysyweeb.beamline.api.data.PackInfo
import io.github.spysyweeb.beamline.api.model.GeoPoint

/** One row of a search over several packs: the engine's [result] and the pack it came from. */
data class PackResult(val packId: String, val result: SearchResult)

/** The answer of [SearchHub.search]. */
data class MultiSearchResponse(
    /** The text searched: the engine's cut (rule S5). */
    val query: String,
    val results: List<PackResult>,
    /** The packs asked (rule S3), in merge order: the one containing the position first. */
    val packsAsked: List<String>,
    /** True when every asked pack has the same ranking_sha256, so the rows are merged by score (S4). */
    val mergedByScore: Boolean,
    /** The house number the engine read ("4319 peach st"), if any (from the first asked pack). */
    val houseNumber: String?,
    /** True when every asked pack said the position is outside it; null without a position or a pack. */
    val outOfCoverage: Boolean?,
    val ms: Double,
    /** 0.1.40 (P6): the place the words name, from the first asked pack whose answer names one ("coffee in corry"); null otherwise. */
    val namedPlace: NamedPlace? = null,
)

/**
 * Rules S3 and S4 of docs/architecture.md 3.8, as pure functions ([SearchHub] runs them on the
 * search thread over the open packs).
 */
object MultiPackSearch {
    /**
     * S3: every pack whose bounds grown by [marginKm] contain [near]; with none, the pack nearest
     * to it. Order: a pack containing [near] (plain bounds) first, then by distance from [near] to
     * the pack's bounds, then pack id. Without a position every pack is asked, by pack id (a choice
     * of this track: S3 names no rule for that case).
     */
    fun packsToAsk(packs: List<PackInfo>, near: GeoPoint?, marginKm: Double): List<PackInfo> {
        if (packs.isEmpty()) return emptyList()
        if (near == null) return packs.sortedBy { it.packId }
        val order = compareBy<PackInfo>({ if (it.bounds.contains(near)) 0 else 1 }, { distanceToBoundsKm(near, it) }, { it.packId })
        val inside = packs.filter { it.bounds.grownByKm(marginKm).contains(near) }
        if (inside.isNotEmpty()) return inside.sortedWith(order)
        return listOf(packs.minWith(order))
    }

    /** Great-circle km from [p] to the nearest point of [pack]'s bounds (0 inside). */
    fun distanceToBoundsKm(p: GeoPoint, pack: PackInfo): Double {
        val b = pack.bounds
        val lat = p.lat.coerceIn(b.south, b.north)
        val lon = p.lon.coerceIn(b.west, b.east)
        return Geo.haversineKm(p.lat, p.lon, lat, lon)
    }

    /**
     * S4: [answers] in the order of [packsToAsk]. When every asked pack has the same non-null
     * searchRankingSha256 (or only one was asked) the rows are merged by score (ties: pack order,
     * then each pack's own order); otherwise pack by pack in that order, each in its own order.
     * Duplicates (same kind, same folded title — the exact title when the fold is empty — within
     * [dedupeM] metres) keep the higher score, in the place of the first seen. At most [limit].
     */
    fun merge(answers: List<Pair<PackInfo, SearchResponse>>, dedupeM: Double, limit: Int): Pair<List<PackResult>, Boolean> {
        val hashes = answers.map { it.first.searchRankingSha256 }
        val byScore = answers.size <= 1 || (hashes.all { it != null } && hashes.toSet().size == 1)
        val rows = answers.flatMap { (pack, r) -> r.results.map { PackResult(pack.packId, it) } }
        val ordered = if (byScore) rows.sortedByDescending { it.result.score } else rows   // stable: ties keep pack order
        val kept = ArrayList<PackResult>()
        for (row in ordered) {
            val i = kept.indexOfFirst { same(it.result, row.result, dedupeM) }
            if (i < 0) kept.add(row) else if (row.result.score > kept[i].result.score) kept[i] = row
        }
        return kept.take(limit) to byScore
    }

    private fun same(a: SearchResult, b: SearchResult, dedupeM: Double): Boolean {
        if (a.kind != b.kind) return false
        val fa = foldText(a.title)
        val fb = foldText(b.title)
        val equal = if (fa.isEmpty() && fb.isEmpty()) a.title == b.title else fa == fb
        return equal && Geo.haversineKm(a.lat, a.lon, b.lat, b.lon) * 1000.0 <= dedupeM
    }
}
