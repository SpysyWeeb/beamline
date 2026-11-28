package io.github.spysyweeb.beamline.search.address

/**
 * Item 55 (docs/next-round.md): the flat geometry of [AddressIndex.alongStreet], which looks a typed house number up
 * along a street the search shows. NOT part of the Python reference (addrlib has no such lookup). The plane: x east and
 * y north in metres around the point asked about, longitudes scaled by the cosine of its latitude, as StreetLines.
 */
internal object AlongStreet {
    /** Metres in a degree of latitude (StreetLines.M_PER_DEG). */
    private const val M_PER_DEG = 111_320.0

    /** A street line point step (StreetLines.SCALE: lines are lat, lon pairs in whole 1e-5 degrees). */
    private const val SCALE = 100_000.0

    /**
     * The point of [lines] nearest to ([lat], [lon]) as (metres, x, y of the direction of the segment it is on, not
     * normalised); segments of no length are skipped (their point is the next segment's end); null without a segment.
     */
    fun nearest(lines: List<IntArray>, lat: Double, lon: Double): DoubleArray? {
        val kx = M_PER_DEG * StrictMath.cos(Math.toRadians(lat))
        var best: DoubleArray? = null
        for (line in lines) {
            var ax = (line[1] / SCALE - lon) * kx
            var ay = (line[0] / SCALE - lat) * M_PER_DEG
            var i = 1
            while (2 * i + 1 < line.size) {
                val bx = (line[2 * i + 1] / SCALE - lon) * kx
                val by = (line[2 * i] / SCALE - lat) * M_PER_DEG
                val dx = bx - ax
                val dy = by - ay
                val den = dx * dx + dy * dy
                if (den > 0.0) {
                    val t = maxOf(0.0, minOf(1.0, -(ax * dx + ay * dy) / den))
                    val px = ax + t * dx
                    val py = ay + t * dy
                    val d2 = px * px + py * py
                    if (best == null || d2 < best[0]) best = doubleArrayOf(d2, dx, dy)
                }
                ax = bx
                ay = by
                i++
            }
        }
        return best?.also { it[0] = StrictMath.sqrt(it[0]) }
    }

    /**
     * The point at fraction [frac] (clamped to 0..1) of an edge ([coords]: (lon, lat) pairs, as [AddressIndex.decodeLine])
     * with no side offset, and the direction of the segment it is on (of the next segment with a length, else the one
     * before, when it has none): (lat, lon, x, y), the direction in metres and not normalised (0, 0 for an edge of one
     * point or of no length). The same measure along the edge as [AddressIndex.interpolate], on the plane of [nearest].
     */
    fun pointOn(coords: List<DoubleArray>, frac: Double): DoubleArray {
        if (coords.size == 1) return doubleArrayOf(coords[0][1], coords[0][0], 0.0, 0.0)
        val kx = M_PER_DEG * StrictMath.cos(Math.toRadians(coords[0][1]))
        val seg = DoubleArray(coords.size - 1) { i ->
            StrictMath.hypot((coords[i + 1][0] - coords[i][0]) * kx, (coords[i + 1][1] - coords[i][1]) * M_PER_DEG)
        }
        val total = seg.sum()
        if (total == 0.0) return doubleArrayOf(coords[0][1], coords[0][0], 0.0, 0.0)
        val target = maxOf(0.0, minOf(1.0, frac)) * total
        var acc = 0.0
        for (i in seg.indices) {
            val s = seg[i]
            if (acc + s >= target || i == seg.size - 1) {
                val tt = if (s == 0.0) 0.0 else maxOf(0.0, minOf(1.0, (target - acc) / s))
                val a = coords[i]
                val b = coords[i + 1]
                val d = (i until seg.size).firstOrNull { seg[it] > 0.0 } ?: (i - 1 downTo 0).first { seg[it] > 0.0 }
                return doubleArrayOf(
                    a[1] + tt * (b[1] - a[1]), a[0] + tt * (b[0] - a[0]),
                    (coords[d + 1][0] - coords[d][0]) * kx, (coords[d + 1][1] - coords[d][1]) * M_PER_DEG,
                )
            }
            acc += s
        }
        val last = coords.last()
        return doubleArrayOf(last[1], last[0], 0.0, 0.0)
    }

    /**
     * The grid cells (of [withinM] times 1.5 a side) a point within [withinM] of [lines] can lie in: a quick first test
     * before [nearest] for many points around a long line (a city's streets around a boulevard).
     */
    class Cells(lines: List<IntArray>, withinM: Double) {
        private val size = withinM * 1.5
        private val kx = M_PER_DEG * StrictMath.cos(Math.toRadians(lines.firstOrNull()?.let { it[0] / SCALE } ?: 0.0))
        private val set = HashSet<Long>()

        private fun key(cy: Long, cx: Long) = (cy shl 32) xor (cx and 0xFFFFFFFFL)

        init {
            val step = size / 2
            for (line in lines) {
                var i = 0
                while (2 * i + 1 < line.size) {
                    val y0 = line[2 * i] / SCALE * M_PER_DEG
                    val x0 = line[2 * i + 1] / SCALE * kx
                    val last = 2 * i + 3 >= line.size
                    val y1 = if (last) y0 else line[2 * i + 2] / SCALE * M_PER_DEG
                    val x1 = if (last) x0 else line[2 * i + 3] / SCALE * kx
                    val n = maxOf(1, Math.ceil(StrictMath.hypot(x1 - x0, y1 - y0) / step).toInt())
                    for (k in 0..n) {
                        val cy = Math.floor((y0 + (y1 - y0) * k / n) / size).toLong()
                        val cx = Math.floor((x0 + (x1 - x0) * k / n) / size).toLong()
                        for (dy in -1L..1L) for (dx in -1L..1L) set.add(key(cy + dy, cx + dx))
                    }
                    i++
                }
            }
        }

        /** False only for a point farther than withinM from every line. */
        fun mayHold(lat: Double, lon: Double): Boolean =
            key(Math.floor(lat * M_PER_DEG / size).toLong(), Math.floor(lon * kx / size).toLong()) in set
    }

    /**
     * Whether two directions (x, y) run within [maxDeg] of each other, either way along; false when either has no
     * length (a point does not show that it runs along a line: a crossing street's stub at the corner).
     */
    fun parallel(ax: Double, ay: Double, bx: Double, by: Double, maxDeg: Double): Boolean {
        val na = StrictMath.hypot(ax, ay)
        val nb = StrictMath.hypot(bx, by)
        if (na == 0.0 || nb == 0.0) return false
        return Math.abs(ax * bx + ay * by) / (na * nb) >= StrictMath.cos(Math.toRadians(maxDeg))
    }
}
