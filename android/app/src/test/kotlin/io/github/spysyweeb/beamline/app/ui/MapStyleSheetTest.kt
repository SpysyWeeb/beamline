package io.github.spysyweeb.beamline.app.ui

import io.github.spysyweeb.beamline.settings.MapStyle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The map style sheet (0.1.34): each choice has its line, in the words Settings uses. */
class MapStyleSheetTest {
    @Test
    fun `every map style has its line`() {
        assertEquals(MapStyle.entries.size, MapStyle.entries.map(::mapStyleLine).toSet().size)
        assertTrue(mapStyleLine(MapStyle.AUTOMATIC).contains("sunrise and sunset"))
        assertTrue(mapStyleLine(MapStyle.SAME_AS_PHONE).contains("dark mode"))
    }
}
