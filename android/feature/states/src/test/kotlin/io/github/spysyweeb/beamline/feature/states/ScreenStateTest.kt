package io.github.spysyweeb.beamline.feature.states

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** states.md P12: a search that hides the selected state clears the selection (its outline and zoom go). */
class ScreenStateTest {
    private val names = mapOf("OH" to "Ohio")::get

    @Test
    fun aSearchThatHidesTheSelectionClearsIt() {
        val s = ScreenState(selected = "OH", selectedOnMap = true)
        assertEquals("OH", s.typed("ohi", names).selected)
        assertEquals("OH", s.typed("oh", names).selected)
        assertTrue(s.typed("oh", names).selectedOnMap, "still the map's pick")
        val hidden = s.typed("zz", names)
        assertNull(hidden.selected)
        assertEquals("zz", hidden.query)
        assertFalse(hidden.selectedOnMap)
        // nothing selected: only the query changes
        assertEquals(ScreenState(query = "pa"), ScreenState().typed("pa", names))
    }
}
