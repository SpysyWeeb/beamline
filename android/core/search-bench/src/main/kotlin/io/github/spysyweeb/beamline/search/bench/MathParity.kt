package io.github.spysyweeb.beamline.search.bench

import io.github.spysyweeb.beamline.search.Geo

/**
 * A checksum over the distance function on fixed pseudo-random inputs. The engine uses
 * StrictMath (fdlibm), which should give the same bits on the JVM and on Android; equal
 * checksums on both confirm it (scores and distances then match bit for bit across them).
 */
object MathParity {
    fun checksum(n: Int = 100_000): String {
        var s = 0x2545F4914F6CDD1DL
        fun next(): Double {
            s = s * 6364136223846793005L + 1442695040888963407L
            return (s ushr 11).toDouble() / (1L shl 53).toDouble()
        }
        var acc = 0L
        repeat(n) {
            val lat1 = 39.5 + next() * 3.1
            val lon1 = -80.6 + next() * 6.0
            val lat2 = lat1 + (next() - 0.5) * 2.0
            val lon2 = lon1 + (next() - 0.5) * 2.0
            acc = acc * 31 + java.lang.Double.doubleToRawLongBits(Geo.haversineKm(lat1, lon1, lat2, lon2))
        }
        return java.lang.Long.toHexString(acc)
    }
}
