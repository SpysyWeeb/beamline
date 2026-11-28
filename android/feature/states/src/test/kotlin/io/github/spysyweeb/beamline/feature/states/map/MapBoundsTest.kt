package io.github.spysyweeb.beamline.feature.states.map

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * states.md P15 (audit F66): the map's centre is kept so the card never shows more than its 16 dp
 * margin past the country, and a zoom keeps the point under the finger. A 1080 x 726 px card with
 * 44 px margins and the asset's 1000 x 632 units; floats compared with a tolerance only.
 */
class MapBoundsTest {
    private val w = 1080f
    private val h = 726f
    private val m = 44f
    private val fit = minOf((w - 2 * m) / 1000f, (h - 2 * m) / 632f)

    @Test
    fun atTheFitTheCountryDoesNotMove() {
        for (c in listOf(-5000f, 0f, 500f, 9000f)) {
            assertEquals(500f, clampCentre(c, 1000f, w / 2 / fit, m / fit), 0.01f, "x from $c")
            assertEquals(316f, clampCentre(c, 632f, h / 2 / fit, m / fit), 0.01f, "y from $c")
        }
    }

    @Test
    fun zoomedInTheCardStopsAtTheCountrysEdge() {
        val s = fit * 4
        val lo = clampCentre(-1000f, 1000f, w / 2 / s, m / s)
        // the map's west edge (x 0) lands one margin inside the card's left edge
        assertEquals(m, w / 2 + (0f - lo) * s, 0.5f)
        val hi = clampCentre(9000f, 1000f, w / 2 / s, m / s)
        assertEquals(w - m, w / 2 + (1000f - hi) * s, 0.5f)
        assertEquals(600f, clampCentre(600f, 1000f, w / 2 / s, m / s), 0.01f, "inside the bounds nothing moves")
    }

    @Test
    fun zoomAboutKeepsThePointUnderTheFinger() {
        for ((c, f) in listOf(500f to 754.3f, 316f to 259.9f, 120f to 800f)) {
            val before = (f - c) * fit * 1f
            val after = (f - zoomAbout(c, f, 1f, 2f)) * fit * 2f
            assertEquals(before, after, 0.01f, "c $c, f $f")
            assertEquals((f - c) * fit * 2f, (f - zoomAbout(c, f, 2f, 8f)) * fit * 8f, 0.01f)
        }
    }
}
