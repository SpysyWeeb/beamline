package io.github.spysyweeb.beamline.feature.search

import java.io.ByteArrayInputStream
import java.io.FileNotFoundException
import java.io.InputStream
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * The sprite cache remembers a missing sheet (review finding: a stored null made Kotlin's getOrPut
 * read and parse the sheet again for every icon, on the main thread). No Android code runs here:
 * the assets are a fake that has no sheet, or a JSON without its PNG.
 */
class SpriteCacheTest {
    @Test
    fun aMissingSheetIsLookedForOncePerTheme() {
        val asked = ArrayList<String>()
        val none: (String) -> InputStream = { asked.add(it); throw FileNotFoundException("not in this APK") }
        val c = SpriteCache()
        repeat(5) { assertNull(c.icon(none, night = false, name = "fuel")) }
        assertNull(c.icon(none, night = false, name = "cafe"))
        assertEquals(listOf("style/sprites/light@2x.json"), asked)
        assertNull(c.icon(none, night = true, name = "fuel"))
        assertNull(c.icon(none, night = true, name = "rest_area"))
        assertEquals(listOf("style/sprites/light@2x.json", "style/sprites/dark@2x.json"), asked)
        assertEquals(2, c.sheetReads)
    }

    @Test
    fun aSheetWhoseImageIsMissingIsAlsoTriedOnce() {
        val asked = ArrayList<String>()
        val jsonOnly: (String) -> InputStream = { path ->
            asked.add(path)
            if (path.endsWith(".json")) ByteArrayInputStream("""{"fuel":{"x":0,"y":0,"width":48,"height":48,"pixelRatio":2}}""".toByteArray())
            else throw FileNotFoundException("no png")
        }
        val c = SpriteCache()
        repeat(4) { assertNull(c.icon(jsonOnly, night = false, name = "fuel")) }
        for (name in listOf("cafe", "rest_area", "fuel")) assertNull(c.icon(jsonOnly, night = false, name = name))   // other icons: the sheet is not read again
        assertEquals(listOf("style/sprites/light@2x.json", "style/sprites/light@2x.png"), asked)
        assertEquals(1, c.sheetReads)
    }
}
