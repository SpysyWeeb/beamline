package io.github.spysyweeb.beamline.routing.model

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.math.sqrt

/** A point in degrees (WGS84). */
data class LatLon(val lat: Double, val lon: Double)

object Geo {
    /** Same mean Earth radius as packbuilder/routing/route_lib.py haversine_m. */
    private const val EARTH_RADIUS_M = 6371008.8

    fun distanceM(a: LatLon, b: LatLon): Double {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLat = lat2 - lat1
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2).let { it * it } + cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(h))
    }

    /** Decodes an encoded polyline with 6 digits of precision (Valhalla's shape format). */
    fun decodePolyline6(s: String): List<LatLon> {
        val out = ArrayList<LatLon>(s.length / 4)
        var i = 0
        var lat = 0L
        var lon = 0L
        while (i < s.length) {
            for (which in 0..1) {
                var shift = 0
                var result = 0L
                while (true) {
                    val b = s[i++].code - 63
                    result = result or ((b and 0x1F).toLong() shl shift)
                    shift += 5
                    if (b < 0x20) break
                }
                val d = if (result and 1L != 0L) (result shr 1).inv() else result shr 1
                if (which == 0) lat += d else lon += d
            }
            out += LatLon(lat / 1e6, lon / 1e6)
        }
        return out
    }

    fun encodePolyline6(points: List<LatLon>): String {
        val sb = StringBuilder()
        var pLat = 0L
        var pLon = 0L
        for (p in points) {
            val lat = (p.lat * 1e6).roundToLong()
            val lon = (p.lon * 1e6).roundToLong()
            encodeValue(lat - pLat, sb)
            encodeValue(lon - pLon, sb)
            pLat = lat
            pLon = lon
        }
        return sb.toString()
    }

    private fun encodeValue(v: Long, sb: StringBuilder) {
        var x = if (v < 0) (v shl 1).inv() else v shl 1
        while (x >= 0x20) {
            sb.append(((0x20L or (x and 0x1F)) + 63).toInt().toChar())
            x = x shr 5
        }
        sb.append((x + 63).toInt().toChar())
    }

    /**
     * Valhalla's tile id of the tile holding [p] at a hierarchy level (0: 4 degrees, 1: 1 degree,
     * 2: 0.25 degrees), as in baldr/tilehierarchy.h: row-major from (-90, -180).
     */
    fun tileId(p: LatLon, level: Int): Int {
        val size = TILE_SIZES[level]
        val cols = (360.0 / size).toInt()
        val row = floor((p.lat + 90.0) / size).toInt()
        val col = floor((p.lon + 180.0) / size).toInt()
        return row * cols + col
    }

    /** The tile path the tile tar uses ("2/000/747/851.gph"). */
    fun tilePath(level: Int, tileId: Int): String {
        val maxId = ((180.0 / TILE_SIZES[level]) * (360.0 / TILE_SIZES[level])).toLong() - 1
        val digits = maxId.toString().length.let { ((it + 2) / 3) * 3 }
        val padded = tileId.toString().padStart(digits, '0')
        return "$level/" + padded.chunked(3).joinToString("/") + ".gph"
    }

    private val TILE_SIZES = doubleArrayOf(4.0, 1.0, 0.25)
}
