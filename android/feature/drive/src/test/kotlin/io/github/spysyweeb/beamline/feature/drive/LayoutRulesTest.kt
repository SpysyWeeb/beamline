package io.github.spysyweeb.beamline.feature.drive

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The two layout rules that are plain arithmetic: lane boxes for the width, and where the route sheet rests. */
class LayoutRulesTest {

    /** The row width [m] takes for [n] lanes. */
    private fun rowWidth(m: LaneMetrics, n: Int) = n * m.widthDp + (n - 1) * m.gapDp

    @Test
    fun `lanes - the sketch's boxes while they fit`() {
        // 328 dp: a 360 dp phone upright (16 dp padding each side); 288 dp: the 320 dp side panel
        assertEquals(SKETCH_LANE, laneMetrics(328f, 3))
        assertEquals(SKETCH_LANE, laneMetrics(328f, 4))
        assertEquals(SKETCH_LANE, laneMetrics(328f, 7)) // 316 dp
        assertEquals(SKETCH_LANE, laneMetrics(288f, 6)) // 270 dp
        assertEquals(SKETCH_LANE, laneMetrics(288f, 0))
    }

    @Test
    fun `lanes - more than fit share the width, every lane whole`() {
        // the review's cases: 7 lanes in the 320 dp panel, 8 on a 360 dp phone (before: the last lane about 6 dp)
        for ((available, n) in listOf(288f to 7, 328f to 8, 288f to 8, 368f to 9, 288f to 12, 288f to 20)) {
            val m = laneMetrics(available, n)
            assertTrue(rowWidth(m, n) <= available + 0.01f, "$n lanes in $available dp: row ${rowWidth(m, n)} dp")
            assertTrue(m.widthDp < SKETCH_LANE.widthDp, "$n lanes in $available dp: $m")
            assertTrue(m.iconDp <= m.widthDp * 0.65f + 0.01f && m.iconDp > 0f, "$n lanes in $available dp: $m")
        }
        assertMetrics(36f, 6f, 23.4f, laneMetrics(288f, 7))
        assertMetrics(35.75f, 6f, 23.2375f, laneMetrics(328f, 8))
        // under 24 dp a box gives up gap first: 12 lanes in 288 dp are 22.2 dp boxes 2 dp apart, not 18.5 dp boxes 6 dp apart
        assertMetrics(266f / 12f, 2f, 266f / 12f * 0.65f, laneMetrics(288f, 12))
    }

    @Test
    fun `lanes - in whole pixels as Compose lays them out, the row never runs past its width`() {
        // the emulator (density 2.625): 6 dp gaps are 15.75 px, laid out as 16; counted in dp, 8 lanes in
        // 288 dp (756 px) were 8 x 81 + 7 x 16 = 760 px and the last lane lost 4 px
        val d = 2.625f
        for ((available, n) in listOf(288f to 7, 288f to 8, 328f to 8, 368f to 9, 288f to 12, 288f to 20, 328f to 7, 328f to 3)) {
            val m = laneMetrics(available, n, pxPerDp = d)
            val boxPx = Math.round(m.widthDp * d)
            val gapPx = Math.round(m.gapDp * d)
            assertTrue(n * boxPx + (n - 1) * gapPx <= Math.round(available * d), "$n lanes in $available dp: $n x $boxPx + ${n - 1} x $gapPx px")
            assertEquals(boxPx.toFloat(), m.widthDp * d, 0.01f, "$n lanes in $available dp: a whole number of px")
        }
        assertEquals(SKETCH_LANE, laneMetrics(328f, 7, pxPerDp = d))   // 7 x 105 + 6 x 16 = 831 <= 861 px
        assertEquals(80f, laneMetrics(288f, 8, pxPerDp = d).widthDp * d, 0.01f)   // (756 - 112) / 8 = 80.5 -> 80 px
    }

    private fun assertMetrics(width: Float, gap: Float, icon: Float, m: LaneMetrics) {
        assertEquals(width, m.widthDp, 0.001f, "width $m")
        assertEquals(gap, m.gapDp, 0.001f, "gap $m")
        assertEquals(icon, m.iconDp, 0.001f, "icon $m")
    }

    // ---- the route sheet's resting place ----

    // The 640 x 360 dp sheet on its side, in px: "To" + "Downs Drive" + "Erie, Pennsylvania" = 0..150,
    // "Add stop" 150..255, the chosen card 255..497; the scrolling part is 260 px tall.
    private val line = Span(0, 150)
    private val card = Span(255, 497)

    @Test
    fun `sheet - a stop line cut at the top goes whole or wholly away, whichever is nearer`() {
        assertNull(sheetRestingScroll(top = 0, viewport = 260, maxScroll = 400, lines = listOf(line), card = null))
        assertNull(sheetRestingScroll(top = 150, viewport = 260, maxScroll = 400, lines = listOf(line), card = null))
        assertNull(sheetRestingScroll(top = 300, viewport = 260, maxScroll = 400, lines = listOf(line), card = null))
        assertEquals(0, sheetRestingScroll(top = 40, viewport = 260, maxScroll = 400, lines = listOf(line), card = null))
        assertEquals(150, sheetRestingScroll(top = 110, viewport = 260, maxScroll = 400, lines = listOf(line), card = null))
        // several stops: the one that is cut
        val two = listOf(Span(0, 150), Span(150, 260))
        assertEquals(260, sheetRestingScroll(top = 230, viewport = 300, maxScroll = 500, lines = two, card = null))
        assertEquals(150, sheetRestingScroll(top = 170, viewport = 300, maxScroll = 500, lines = two, card = null))
    }

    @Test
    fun `sheet - a cut through a line's empty space is no cut`() {
        // The 800 x 360 dp sheet on the emulator (measured): the second stop's line is 180..318 px, with
        // 11 px of padding and 11 px of the name's leading above its letters (22 px free) and 11 px of
        // padding below; the card's bring-into-view leaves the top at 197, 17 px in, the letters whole:
        // it stays. (Counting only the padding, the rule hid both stops there.)
        val lines = listOf(Span(0, 180, 0, 11), Span(180, 318, 22, 11))
        val card = Span(444, 686)
        assertNull(sheetRestingScroll(top = 197, viewport = 489, maxScroll = 612, lines = lines, card = card))
        assertNull(sheetRestingScroll(top = 310, viewport = 489, maxScroll = 612, lines = lines, card = null)) // only the bottom padding left
        assertEquals(180, sheetRestingScroll(top = 205, viewport = 300, maxScroll = 612, lines = lines, card = null))
        // the "To" label has no free space above it: the first line is cut from its first px
        assertEquals(0, sheetRestingScroll(top = 3, viewport = 300, maxScroll = 612, lines = lines, card = null))
    }

    @Test
    fun `sheet - the selected card stays whole`() {
        // The review's frame: the card brought into view leaves the top inside the stop line, its name
        // gone and its second line showing (120); the nearer edge (150) keeps the card whole.
        assertEquals(150, sheetRestingScroll(top = 120, viewport = 380, maxScroll = 400, lines = listOf(line), card = card))
        // the nearer edge is the top (0), but there the card's bottom (497) is below 0 + 440: away instead
        assertEquals(150, sheetRestingScroll(top = 60, viewport = 440, maxScroll = 400, lines = listOf(line), card = card))
        // the line cannot be scrolled wholly away (max 120) and showing it whole would cut the card: stay
        assertNull(sheetRestingScroll(top = 100, viewport = 400, maxScroll = 120, lines = listOf(line), card = card))
        // a card that is not whole now does not hold the line back
        assertEquals(0, sheetRestingScroll(top = 40, viewport = 260, maxScroll = 400, lines = listOf(line), card = card))
        // a line that cannot be scrolled wholly away (the part is too short) is shown whole
        assertEquals(0, sheetRestingScroll(top = 100, viewport = 260, maxScroll = 120, lines = listOf(line), card = null))
    }
}
