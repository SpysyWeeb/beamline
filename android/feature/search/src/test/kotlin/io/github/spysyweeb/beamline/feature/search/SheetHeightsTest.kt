package io.github.spysyweeb.beamline.feature.search

import io.github.spysyweeb.beamline.ui.theme.SheetHeight
import io.github.spysyweeb.beamline.ui.theme.SheetState
import io.github.spysyweeb.beamline.ui.theme.SheetSteps
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Rule D10 (batch F, docs/next-round.md item 15): the heights of every bottom sheet, the moves
 * between them (tap, semantic action, drag) and the results list's heights. The rules live in
 * `:ui:theme` (`Sheets.kt`, shared with the route sheet of `:feature:drive`); `:ui:theme` has no
 * JVM tests of its own, so they are tested here.
 */
class SheetHeightsTest {
    private val step = 100f

    @Test
    fun `the results list has three heights, the route and place sheets two`() {
        assertEquals(listOf(SheetHeight.LOW, SheetHeight.MIDDLE, SheetHeight.FULL), SheetSteps.THREE.heights)
        assertEquals(listOf(SheetHeight.LOW, SheetHeight.FULL), SheetSteps.TWO.heights)
        assertThrows<IllegalArgumentException> { SheetSteps(listOf(SheetHeight.FULL, SheetHeight.LOW)) }
        assertThrows<IllegalArgumentException> { SheetSteps(emptyList()) }
    }

    @Test
    fun `a tap raises one height and from the highest goes back to the lowest`() {
        val s = SheetState(SheetSteps.THREE, SheetHeight.MIDDLE)
        s.tap(); assertEquals(SheetHeight.FULL, s.height)
        s.tap(); assertEquals(SheetHeight.LOW, s.height)
        s.tap(); assertEquals(SheetHeight.MIDDLE, s.height)
        val two = SheetState(SheetSteps.TWO, SheetHeight.FULL)
        two.tap(); assertEquals(SheetHeight.LOW, two.height)
        two.tap(); assertEquals(SheetHeight.FULL, two.height)
    }

    @Test
    fun `the semantic actions raise and lower one height and stop at the ends`() {
        val s = SheetState(SheetSteps.THREE, SheetHeight.MIDDLE)
        assertTrue(s.canRaise && s.canLower)
        assertTrue(s.lower()); assertEquals(SheetHeight.LOW, s.height)
        assertFalse(s.canLower); assertFalse(s.lower()); assertEquals(SheetHeight.LOW, s.height)
        assertTrue(s.raise()); assertTrue(s.raise()); assertEquals(SheetHeight.FULL, s.height)
        assertFalse(s.raise())
    }

    @Test
    fun `a drag moves one height per step in one direction, a long drag more than one`() {
        val s = SheetState(SheetSteps.THREE, SheetHeight.MIDDLE)
        assertEquals(60f, s.drag(60f, step)); assertEquals(SheetHeight.MIDDLE, s.height)   // not a step yet
        assertEquals(50f, s.drag(50f, step)); assertEquals(SheetHeight.LOW, s.height)       // 110 down: one height
        assertTrue(s.endDrag())
        assertFalse(s.endDrag())                                                            // reset after the drag
        // down at the lowest: not used (a list scrolls instead), the sheet stays
        assertEquals(0f, s.drag(300f, step)); assertEquals(SheetHeight.LOW, s.height)
        assertFalse(s.endDrag())
        // one long drag up: two heights
        repeat(21) { s.drag(-10f, step) }
        assertEquals(SheetHeight.FULL, s.height)
        // up at the highest: not used
        assertEquals(0f, s.drag(-40f, step))
        s.endDrag()
    }

    @Test
    fun `turning back in a drag starts the count again`() {
        val s = SheetState(SheetSteps.TWO, SheetHeight.FULL)
        s.drag(90f, step)          // almost a step down
        s.drag(-5f, step)          // up: the sheet cannot rise, nothing counted
        s.drag(20f, step)          // down again: 20, not 110
        assertEquals(SheetHeight.FULL, s.height)
        s.drag(80f, step)
        assertEquals(SheetHeight.LOW, s.height)
    }

    @Test
    fun `a saved height another sheet does not have becomes the nearest it has`() {
        assertEquals(SheetHeight.LOW, SheetSteps.TWO.nearest(SheetHeight.LOW))
        assertEquals(SheetHeight.FULL, SheetSteps.TWO.nearest(SheetHeight.FULL))
        val s = SheetState(SheetSteps.TWO, SheetHeight.MIDDLE)
        assertTrue(s.height in SheetSteps.TWO.heights)
        assertNull(SheetSteps.TWO.raised(SheetHeight.FULL))
        assertEquals(SheetHeight.LOW, SheetSteps.TWO.lowered(SheetHeight.FULL))
    }

    @Test
    fun `the results list's rows take none, about 30 percent and about 60 percent of the screen`() {
        assertEquals(0f, resultsListMaxDp(SheetHeight.LOW, 891))
        assertEquals(891 * 0.3f, resultsListMaxDp(SheetHeight.MIDDLE, 891))
        assertEquals(150f, resultsListMaxDp(SheetHeight.MIDDLE, 411))   // a phone on its side: at least 150 dp
        assertEquals(891 * 0.6f, resultsListMaxDp(SheetHeight.FULL, 891))
    }

    @Test
    fun `the list comes back at the height it was left after a result's sheet, new results at the middle`() {
        val ui = ResultsUi("sheetz", listOf(ResultRowUi("a", "fuel", "Sheetz", null, "1 mi", null)), emptyList())
        val key = ResultsHeightMemory.keyOf(ui)
        ResultsHeightMemory.remember(key, SheetHeight.LOW)
        assertEquals(SheetHeight.LOW, ResultsHeightMemory.heightFor(ResultsHeightMemory.keyOf(ui.copy(selected = 0))))
        val other = ResultsHeightMemory.keyOf(ui.copy(query = "walmart"))
        assertEquals(SheetHeight.MIDDLE, ResultsHeightMemory.heightFor(other))
    }
}
