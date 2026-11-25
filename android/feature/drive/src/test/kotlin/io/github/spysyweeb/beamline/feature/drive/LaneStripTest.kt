package io.github.spysyweeb.beamline.feature.drive

import io.github.spysyweeb.beamline.api.nav.LaneArrow
import io.github.spysyweeb.beamline.api.nav.LaneMark
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The lane strip (the owner, 2026-11-25; docs/next-round.md item 45). */
class LaneStripTest {
    private fun lane(mark: LaneMark, vararg a: LaneArrow, used: LaneArrow? = null) = LaneUi(a.toSet(), mark, used)

    @Test
    fun `the strip is for a choice of lanes, the small row for none`() {
        val pick = listOf(lane(LaneMark.NOT_FOR_ROUTE, LaneArrow.LEFT), lane(LaneMark.BEST, LaneArrow.THROUGH))
        assertTrue(laneChoice(pick))
        assertFalse(laneChoice(listOf(lane(LaneMark.BEST, LaneArrow.THROUGH), lane(LaneMark.ALLOWED, LaneArrow.THROUGH))), "every lane goes")
        assertFalse(laneChoice(listOf(lane(LaneMark.NOT_FOR_ROUTE, LaneArrow.LEFT))), "no lane goes: nothing to pick")
    }

    @Test
    fun `a lane of several arrows draws the one the route uses apart from the others`() {
        // Parade St at the Pine Ave fork: "left;slight_left", the route bearing left
        val (used, other) = DriveIcons.laneSplit(setOf(LaneArrow.LEFT, LaneArrow.SLIGHT_LEFT), LaneArrow.SLIGHT_LEFT)
        assertNotNull(other)
        assertEquals(3, used.root.size, "the stem and the bear-left arrow (shaft and head)")
        assertEquals(2, other!!.root.size, "the left arrow (shaft and head)")
        // straight or right, the route straight: the through arrow's upper half is the used part
        val (through, right) = DriveIcons.laneSplit(setOf(LaneArrow.THROUGH, LaneArrow.RIGHT), LaneArrow.THROUGH)
        assertEquals(3, through.root.size)
        assertEquals(2, right!!.root.size)
    }

    @Test
    fun `one arrow, or none used, is the whole lane as before`() {
        assertNull(DriveIcons.laneSplit(setOf(LaneArrow.LEFT), LaneArrow.LEFT).second)
        assertNull(DriveIcons.laneSplit(setOf(LaneArrow.LEFT, LaneArrow.THROUGH), null).second)
        assertNull(DriveIcons.laneSplit(setOf(LaneArrow.LEFT, LaneArrow.THROUGH), LaneArrow.RIGHT).second, "a used arrow the lane lacks")
        assertEquals(DriveIcons.lane(setOf(LaneArrow.LEFT)), DriveIcons.laneSplit(setOf(LaneArrow.LEFT), LaneArrow.LEFT).first)
    }
}
