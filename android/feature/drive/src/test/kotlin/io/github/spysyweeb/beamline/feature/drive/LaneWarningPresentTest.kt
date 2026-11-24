package io.github.spysyweeb.beamline.feature.drive

import io.github.spysyweeb.beamline.api.nav.LaneArrow
import io.github.spysyweeb.beamline.api.nav.LaneMark
import io.github.spysyweeb.beamline.api.nav.LaneView
import io.github.spysyweeb.beamline.api.nav.LaneWarningView
import io.github.spysyweeb.beamline.feature.drive.Exit24.frame1
import io.github.spysyweeb.beamline.feature.drive.Exit24.guiding
import io.github.spysyweeb.beamline.feature.drive.Exit24.present
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Rule B9 (batch C1): a lane that ends in a turn, on the banner. */
class LaneWarningPresentTest {
    private val peach = LaneWarningView(
        lanes = listOf(
            LaneView(setOf(LaneArrow.LEFT), LaneMark.NOT_FOR_ROUTE, null),
            LaneView(setOf(LaneArrow.THROUGH), LaneMark.BEST, LaneArrow.THROUGH),
            LaneView(setOf(LaneArrow.RIGHT), LaneMark.NOT_FOR_ROUTE, null),
        ),
        text = "Right lane turns right: keep left for Peach St",
        distanceM = 250.0,
    )

    @Test
    fun `the warning's lanes and line replace the banner's lane row, the maneuver stays`() {
        val plain = present(guiding(banner = frame1)).guidance!!.banner!!
        val g = guiding(banner = frame1).let { t -> t.copy(guidance = t.guidance!!.copy(laneWarning = peach)) }
        val b = present(g).guidance!!.banner!!
        assertEquals("Right lane turns right: keep left for Peach St", b.laneNote)
        assertEquals(listOf(LaneMark.NOT_FOR_ROUTE, LaneMark.BEST, LaneMark.NOT_FOR_ROUTE), b.lanes!!.map { it.mark })
        assertEquals(plain.copy(lanes = b.lanes, laneNote = b.laneNote), b, "primary, distance and chip unchanged")
    }

    @Test
    fun `no warning - the banner as before`() {
        val b = present(guiding(banner = frame1)).guidance!!.banner!!
        assertNull(b.laneNote)
    }
}
