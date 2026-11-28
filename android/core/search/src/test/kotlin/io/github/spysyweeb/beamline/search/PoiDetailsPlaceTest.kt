package io.github.spysyweeb.beamline.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** 0.1.41 (wave 0): PoiDetails carries the pack entry's name, type and point; none of them is ever printed (PV2). */
class PoiDetailsPlaceTest {

    @Test
    fun `the new fields default to null, so a details row made as before is the same`() {
        val old = PoiDetails("Mo-Su 08:00-22:00", "+1 814 555 0100", null, "1 Example Street, Erie", "Fast food", "n1")
        assertNull(old.name)
        assertNull(old.type)
        assertNull(old.lat)
        assertNull(old.lon)
        assertEquals(old, old.copy(name = null, type = null, lat = null, lon = null))
    }

    @Test
    fun `toString names no place, no type and no point`() {
        val d = PoiDetails("24/7", null, null, osm = "n1", name = "Example Diner", type = "fast_food", lat = 42.1234, lon = -80.0567)
        val s = d.toString()
        assertEquals("PoiDetails(hours=true, phone=false, website=false, address=false, category=false)", s)
        for (secret in listOf("Example Diner", "fast_food", "42.1234", "-80.0567", "n1")) assertFalse(secret in s, secret)
    }
}
