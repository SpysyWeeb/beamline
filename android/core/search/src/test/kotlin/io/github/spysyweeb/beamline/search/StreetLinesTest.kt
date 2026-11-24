package io.github.spysyweeb.beamline.search

import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The same small grid as packbuilder/search/tests/test_lines.py: State Street north-south, 12th Street across it, at 42 N. */
class StreetLinesTest {
    private val lat0 = 42.0
    private val lon0 = -80.0
    private val ky = 111320.0
    private val kx = ky * cos(Math.toRadians(lat0))

    private fun line(vararg xy: Pair<Double, Double>): IntArray = IntArray(xy.size * 2).also {
        for ((i, p) in xy.withIndex()) {
            it[2 * i] = ((lat0 + p.second / ky) * StreetLines.SCALE).roundToInt()
            it[2 * i + 1] = ((lon0 + p.first / kx) * StreetLines.SCALE).roundToInt()
        }
    }

    private fun metres(p: LatLon) = Pair((p.lon - lon0) * kx, (p.lat - lat0) * ky)
    private fun deg(x: Double, y: Double) = LatLon(lat0 + y / ky, lon0 + x / kx)

    private val state = listOf(line(0.0 to -500.0, 0.0 to 0.0, 0.0 to 500.0))
    private val twelfth = listOf(line(-400.0 to 0.0, 0.0 to 0.0, 400.0 to 0.0))

    /** lines.py encode(), to make blobs for the tests. */
    private fun encode(lines: List<IntArray>): ByteArray {
        val out = ArrayList<Byte>()
        fun varint(v0: Long) {
            var v = v0
            while (v >= 0x80) {
                out.add(((v and 0x7F) or 0x80).toByte())
                v = v ushr 7
            }
            out.add(v.toByte())
        }
        fun zigzag(n: Long) = if (n >= 0) n shl 1 else ((-n) shl 1) - 1
        varint(lines.size.toLong())
        var plat = 0L
        var plon = 0L
        for (l in lines) {
            varint((l.size / 2).toLong())
            for (i in 0 until l.size / 2) {
                varint(zigzag(l[2 * i] - plat))
                varint(zigzag(l[2 * i + 1] - plon))
                plat = l[2 * i].toLong()
                plon = l[2 * i + 1].toLong()
            }
        }
        return out.toByteArray()
    }

    @Test
    fun aBlobGivesBackItsPoints() {
        val sets = listOf(
            listOf(intArrayOf(4200000, -8000000, 4200010, -8000020)),
            state + twelfth,
            listOf(intArrayOf(0, 0, 1, -1), intArrayOf(-9000000, 18000000, 9000000, -18000000, 5, 5)),
        )
        for (ls in sets) {
            val back = StreetLines.decode(encode(ls))
            assertEquals(ls.size, back.size)
            for (i in ls.indices) assertArrayEquals(ls[i], back[i])
        }
        assertEquals(0, StreetLines.decode(encode(emptyList())).size)
        // the bytes lines.py writes for [[(4200000, -8000000), (4200010, -8000020), (4200030, -8000010)]]
        val blob = encode(listOf(intArrayOf(4200000, -8000000, 4200010, -8000020, 4200030, -8000010)))
        assertEquals(1 + 1 + (4 + 4) + 2 + 2, blob.size)
    }

    @Test
    fun aBrokenBlobIsRefused() {
        val good = encode(state)
        val bad = listOf(good.copyOf(good.size - 1), good + 0, byteArrayOf(1, 1, 2, 2), byteArrayOf(1), ByteArray(12) { 0xFF.toByte() })
        for (b in bad) assertThrows(IllegalArgumentException::class.java) { StreetLines.decode(b) }
    }

    @Test
    fun theNearestPointOfAStreet() {
        var at = deg(120.0, 80.0)
        var n = StreetLines.nearest(state, at.lat, at.lon)!!
        assertEquals(120.0, n[0], 1.5)
        val (x, y) = metres(LatLon(n[1], n[2]))
        assertEquals(0.0, x, 1.5)
        assertEquals(80.0, y, 1.5)
        at = deg(30.0, 900.0)   // beyond the end of the line: its end point
        n = StreetLines.nearest(state, at.lat, at.lon)!!
        assertEquals(hypot(30.0, 400.0), n[0], 1.5)
        at = deg(0.0, 250.0)    // on the street
        assertTrue(StreetLines.nearest(state, at.lat, at.lon)!![0] < 1.5)
        assertNull(StreetLines.nearest(emptyList(), 42.0, -80.0))
    }

    @Test
    fun whereTwoStreetsMeet() {
        val pts = StreetLines.crossings(state, twelfth, 15.0, 120.0)
        assertEquals(1, pts.size)
        assertTrue(metres(pts[0]).let { hypot(it.first, it.second) } < 2.0)
        val back = StreetLines.crossings(twelfth, state, 15.0, 120.0)
        assertEquals(1, back.size)
        assertTrue(metres(back[0]).let { hypot(it.first, it.second) } < 2.0)
        // a side street that stops 6 m short of the line meets it; 40 m short does not
        val side = StreetLines.crossings(state, listOf(line(300.0 to 200.0, 6.0 to 200.0)), 15.0, 120.0)
        assertEquals(1, side.size)
        assertEquals(3.0, metres(side[0]).first, 2.0)
        assertEquals(200.0, metres(side[0]).second, 2.0)
        assertEquals(0, StreetLines.crossings(state, listOf(line(300.0 to 200.0, 40.0 to 200.0)), 15.0, 120.0).size)
        // a divided road is one crossing; the same name 600 m apart is two; parallel streets do not meet
        val divided = listOf(line(-400.0 to 0.0, 400.0 to 0.0), line(400.0 to 25.0, -400.0 to 25.0))
        assertEquals(1, StreetLines.crossings(state, divided, 15.0, 120.0).size)
        val twice = listOf(line(-400.0 to -300.0, 400.0 to -300.0), line(-400.0 to 300.0, 400.0 to 300.0))
        assertEquals(2, StreetLines.crossings(state, twice, 15.0, 120.0).size)
        assertEquals(0, StreetLines.crossings(state, listOf(line(60.0 to -500.0, 60.0 to 500.0)), 15.0, 120.0).size)
        assertEquals(0, StreetLines.crossings(state, emptyList(), 15.0, 120.0).size)
    }
}
