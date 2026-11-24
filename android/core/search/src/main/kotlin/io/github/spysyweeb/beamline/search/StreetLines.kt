package io.github.spysyweeb.beamline.search

/**
 * The lines of the streets in the search pack (table `street_line`; docs/next-round.md item 31), read
 * exactly as packbuilder/search/lines.py reads them: the same blob, the same formulas in the same
 * order, so the app and the Python reference give the same points.
 *
 * The blob: varint number of lines; per line a varint number of points (2 or more), then per point a
 * zigzag varint latitude step and a zigzag varint longitude step, in units of 1e-5 degree from the
 * point before (the very first point from 0, 0). A varint is 7 bits a byte, low bits first, the high
 * bit set on every byte but the last.
 *
 * A line here is an IntArray of lat, lon pairs (lat at even indexes), in whole 1e-5 degrees.
 */
internal object StreetLines {
    const val SCALE = 100_000.0
    /** Metres in a degree of latitude: the flat plane the helpers measure on. */
    const val M_PER_DEG = 111_320.0

    /** The lines of [blob]. Throws IllegalArgumentException for a blob that is not one. */
    fun decode(blob: ByteArray): List<IntArray> {
        var pos = 0
        val n = blob.size

        fun varint(): Long {
            var v = 0L
            var shift = 0
            while (true) {
                require(pos < n && shift <= 63) { BROKEN }
                val b = blob[pos].toInt() and 0xFF
                pos++
                v = v or ((b and 0x7F).toLong() shl shift)
                if (b < 0x80) return v
                shift += 7
            }
        }

        fun signed(): Long {
            val v = varint()
            return if (v and 1L == 0L) v ushr 1 else -((v + 1) ushr 1)
        }

        val count = varint()
        require(count in 0..n) { BROKEN }
        val lines = ArrayList<IntArray>(count.toInt())
        var plat = 0L
        var plon = 0L
        repeat(count.toInt()) {
            val points = varint()
            require(points in 2..n) { BROKEN }
            val line = IntArray(points.toInt() * 2)
            for (i in 0 until points.toInt()) {
                plat += signed()
                plon += signed()
                require(plat in -9_000_000L..9_000_000L && plon in -18_000_000L..18_000_000L) { BROKEN }
                line[2 * i] = plat.toInt()
                line[2 * i + 1] = plon.toInt()
            }
            lines.add(line)
        }
        require(pos == n) { BROKEN }
        return lines
    }

    private fun kx(latDeg: Double): Double = M_PER_DEG * StrictMath.cos(Math.toRadians(latDeg))

    /**
     * The point of [lines] nearest to ([lat], [lon]): metres, latitude, longitude; null without a line.
     * The plane: x east and y north in metres from the point, longitudes scaled by the cosine of [lat].
     */
    fun nearest(lines: List<IntArray>, lat: Double, lon: Double): DoubleArray? {
        val kx = kx(lat)
        var found = false
        var bestD2 = 0.0
        var bestX = 0.0
        var bestY = 0.0
        for (line in lines) {
            var ax = (line[1] / SCALE - lon) * kx
            var ay = (line[0] / SCALE - lat) * M_PER_DEG
            var i = 1
            while (2 * i < line.size) {
                val bx = (line[2 * i + 1] / SCALE - lon) * kx
                val by = (line[2 * i] / SCALE - lat) * M_PER_DEG
                val dx = bx - ax
                val dy = by - ay
                val den = dx * dx + dy * dy
                val t = if (den == 0.0) 0.0 else maxOf(0.0, minOf(1.0, -(ax * dx + ay * dy) / den))
                val px = ax + t * dx
                val py = ay + t * dy
                val d2 = px * px + py * py
                if (!found || d2 < bestD2) {
                    found = true
                    bestD2 = d2
                    bestX = px
                    bestY = py
                }
                ax = bx
                ay = by
                i++
            }
        }
        if (!found) return null
        return doubleArrayOf(StrictMath.sqrt(bestD2), lat + bestY / M_PER_DEG, lon + bestX / kx)
    }

    /** The nearest points of segment AB and segment CD: squared metres, then x and y of the middle of the two. */
    private fun segSeg(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double, dx: Double, dy: Double, out: DoubleArray) {
        val ux = bx - ax
        val uy = by - ay
        val vx = dx - cx
        val vy = dy - cy
        val wx = ax - cx
        val wy = ay - cy
        val a = ux * ux + uy * uy
        val b = ux * vx + uy * vy
        val c = vx * vx + vy * vy
        val d = ux * wx + uy * wy
        val e = vx * wx + vy * wy
        val den = a * c - b * b
        var s = if (den <= 1e-9) 0.0 else maxOf(0.0, minOf(1.0, (b * e - c * d) / den))
        val t = if (c == 0.0) 0.0 else maxOf(0.0, minOf(1.0, (b * s + e) / c))
        s = if (a == 0.0) 0.0 else maxOf(0.0, minOf(1.0, (b * t - d) / a))
        val px = ax + s * ux
        val py = ay + s * uy
        val qx = cx + t * vx
        val qy = cy + t * vy
        val ex = px - qx
        val ey = py - qy
        out[0] = ex * ex + ey * ey
        out[1] = (px + qx) / 2.0
        out[2] = (py + qy) / 2.0
    }

    /**
     * Where the lines of two streets meet, in the order found. Two segments meet when they come within
     * [withinM] of each other (they cross, or one ends on the other); a meeting nearer than [apartM] to
     * one already found is the same crossing (the two carriageways of a divided road) and is left out.
     */
    fun crossings(linesA: List<IntArray>, linesB: List<IntArray>, withinM: Double, apartM: Double): List<LatLon> {
        if (linesA.isEmpty() || linesB.isEmpty()) return emptyList()
        val lat0 = linesA[0][0] / SCALE
        val lon0 = linesA[0][1] / SCALE
        val kx = kx(lat0)

        fun plane(lines: List<IntArray>): List<DoubleArray> = lines.map { line ->
            DoubleArray(line.size).also { p ->
                var i = 0
                while (i < line.size) {
                    p[i] = (line[i + 1] / SCALE - lon0) * kx          // x
                    p[i + 1] = (line[i] / SCALE - lat0) * M_PER_DEG   // y
                    i += 2
                }
            }
        }

        val pa = plane(linesA)
        val pb = plane(linesB)
        val lim = withinM * withinM
        val fx = ArrayList<Double>()
        val fy = ArrayList<Double>()
        val r = DoubleArray(3)
        for (la in pa) {
            var i = 2
            while (i < la.size) {
                val ax = la[i - 2]
                val ay = la[i - 1]
                val bx = la[i]
                val by = la[i + 1]
                val lox = if (ax <= bx) ax else bx
                val hix = if (ax <= bx) bx else ax
                val loy = if (ay <= by) ay else by
                val hiy = if (ay <= by) by else ay
                for (lb in pb) {
                    var j = 2
                    while (j < lb.size) {
                        val cx = lb[j - 2]
                        val cy = lb[j - 1]
                        val dx = lb[j]
                        val dy = lb[j + 1]
                        j += 2
                        if ((cx < lox - withinM && dx < lox - withinM) || (cx > hix + withinM && dx > hix + withinM) ||
                            (cy < loy - withinM && dy < loy - withinM) || (cy > hiy + withinM && dy > hiy + withinM)) continue
                        segSeg(ax, ay, bx, by, cx, cy, dx, dy, r)
                        if (r[0] > lim) continue
                        val x = r[1]
                        val y = r[2]
                        var same = false
                        for (k in fx.indices) {
                            if ((x - fx[k]) * (x - fx[k]) + (y - fy[k]) * (y - fy[k]) < apartM * apartM) {
                                same = true
                                break
                            }
                        }
                        if (same) continue
                        fx.add(x)
                        fy.add(y)
                    }
                }
                i += 2
            }
        }
        return fx.indices.map { k -> LatLon(lat0 + fy[k] / M_PER_DEG, lon0 + fx[k] / kx) }
    }

    private const val BROKEN = "broken street line"
}
