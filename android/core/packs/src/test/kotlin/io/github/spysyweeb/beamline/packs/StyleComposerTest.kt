package io.github.spysyweeb.beamline.packs

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import java.io.File

class StyleComposerTest {

    @TempDir
    lateinit var root: File

    private val rules = ComposeRules()
    private fun style(day: String = TestPacks.STYLE_DAY, night: String = TestPacks.STYLE_NIGHT) = AppStyle(
        dayTemplate = day,
        nightTemplate = night,
        glyphsUrl = "asset://style/glyphs/{fontstack}/{range}.pbf",
        spriteDayUrl = "asset://style/sprites/light",
        spriteNightUrl = "asset://style/sprites/dark",
        rules = rules,
    )
    private val overview = OverviewMap("pmtiles://asset://basemap/us-base.pmtiles", "© OpenStreetMap contributors")

    private fun packs() = PackScanner(root, TestPacks.READERS).scan().packs
    private fun parse(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject
    private fun JsonObject.layers() = this["layers"]!!.jsonArray.map { it.jsonObject }
    private fun JsonObject.ids() = layers().map { it["id"]!!.jsonPrimitive.content }
    private fun JsonObject.layer(id: String) = layers().single { it["id"]!!.jsonPrimitive.content == id }
    private fun JsonObject.zoom(key: String) = this[key]?.jsonPrimitive?.double

    @Test
    fun `overview only, from the app's assets`() {
        val c = StyleComposer.compose(style(), Theme.DAY, overview, emptyList<InstalledPack>())
        val s = parse(c.styleJson)
        assertEquals(setOf("overview"), s["sources"]!!.jsonObject.keys)
        assertEquals("pmtiles://asset://basemap/us-base.pmtiles", s["sources"]!!.jsonObject["overview"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals("asset://style/glyphs/{fontstack}/{range}.pbf", s["glyphs"]!!.jsonPrimitive.content)
        assertEquals("asset://style/sprites/light", s["sprite"]!!.jsonPrimitive.content)
        // No state pack: labels are not cut off at z8, but nothing of the overview is drawn above
        // z10 (overzoomed tiles are expensive); the address layer (from z18) is left out.
        assertEquals(listOf("background", "water@overview", "roads@overview", "pois_dot@overview", "places@overview"), s.ids())
        assertEquals(10.0, s.layer("places@overview").zoom("maxzoom"))
        assertEquals(10.0, s.layer("roads@overview").zoom("maxzoom"))
        assertEquals(rules.overviewBounds, c.bounds)
        assertEquals(listOf("© OpenStreetMap contributors"), c.attributions)
        assertTrue(c.hasOverview)
    }

    @Test
    fun `one pack without the overview points the style at the pack file`() {
        val dir = TestPacks.write(root, "us-pa")
        val c = StyleComposer.compose(style(), Theme.NIGHT, null, packs())
        val s = parse(c.styleJson)
        assertEquals("pmtiles://${StyleComposer.fileUrl(dir)}/map.pmtiles", s["sources"]!!.jsonObject["protomaps-us-pa"]!!.jsonObject["url"]!!.jsonPrimitive.content)
        assertEquals("asset://style/sprites/dark", s["sprite"]!!.jsonPrimitive.content)
        assertEquals("#000000", s.layer("background")["paint"]!!.jsonObject["background-color"]!!.jsonPrimitive.content)
        assertNull(s.layer("places@us-pa").zoom("minzoom"))
        assertEquals(listOf(-80.52, 39.66, -74.69, 42.52), c.bounds)
    }

    @Test
    fun `overview underneath, states on top from zoom 8, overview labels stop at zoom 8`() {
        TestPacks.write(root, "us-pa")
        TestPacks.write(root, "us-oh", edit = { it.replace("[-80.52,39.66,-74.69,42.52]", "[-84.82,38.4,-80.51,42.33]") })
        val c = StyleComposer.compose(style(), Theme.DAY, overview, packs())
        val s = parse(c.styleJson)
        assertEquals(setOf("overview", "protomaps-us-oh", "protomaps-us-pa"), s["sources"]!!.jsonObject.keys)
        assertEquals(
            listOf(
                "background",
                // the overview first, all of it; its address layer (from z18) cannot exist below z8: dropped
                "water@overview", "roads@overview", "pois_dot@overview", "places@overview",
                // then the states, interleaved layer by layer
                "water@us-oh", "water@us-pa", "roads@us-oh", "roads@us-pa", "pois_dot@us-oh", "pois_dot@us-pa",
                "address@us-oh", "address@us-pa", "places@us-oh", "places@us-pa",
            ),
            s.ids(),
        )
        assertEquals(8.0, s.layer("places@overview").zoom("maxzoom"))
        assertEquals(8.0, s.layer("pois_dot@overview").zoom("maxzoom"))
        assertEquals(10.0, s.layer("roads@overview").zoom("maxzoom")) // the overview stops at z10
        assertEquals(10.0, s.layer("water@overview").zoom("maxzoom"))
        assertEquals(8.0, s.layer("water@us-pa").zoom("minzoom"))
        assertEquals(18.0, s.layer("address@us-pa").zoom("minzoom"))
        assertEquals(12.0, s.layer("roads@us-pa").zoom("maxzoom"))
        assertEquals(listOf(-84.82, 38.4, -74.69, 42.52), c.bounds)
        assertEquals(listOf("us-oh", "us-pa"), c.packIds)
    }

    /** Break-review finding: `col.a` and `col_a` are both valid ids and once shared layer ids. */
    @Test
    fun `pack ids that differ only by a dot and an underscore get their own sources and layers`() {
        TestPacks.write(root, "col.a")
        TestPacks.write(root, "col_a")
        val s = parse(StyleComposer.compose(style(), Theme.DAY, overview, packs()).styleJson)
        val ids = s.ids()
        assertEquals(ids.size, ids.toSet().size, "duplicate layer ids in $ids")
        assertEquals(setOf("overview", "protomaps-col_002ea", "protomaps-col_005fa"), s["sources"]!!.jsonObject.keys)
        assertTrue("water@col_002ea" in ids && "water@col_005fa" in ids, ids.toString())
        assertEquals("us-pa", StyleComposer.suffix("us-pa"))
    }

    /** Break-review finding: with no state pack, zooming past the overview gave a blank map. */
    @Test
    fun `with only the overview the camera stops where it stops, and outside the states centring does too`() {
        val only = StyleComposer.compose(style(), Theme.DAY, overview, emptyList<InstalledPack>())
        // MapLibre hides a layer at zoom >= its maxzoom (10 here), so the camera stops just below.
        assertEquals(9.99, only.maxZoom!!, 1e-9)
        assertTrue(only.maxZoom!! < parse(only.styleJson).layer("water@overview").zoom("maxzoom")!!)
        assertEquals(9.99, only.zoomFor(42.12, -80.08, 16.0), 1e-9)
        TestPacks.write(root, "us-pa")
        val withPa = StyleComposer.compose(style(), Theme.DAY, overview, packs())
        assertNull(withPa.maxZoom)
        assertEquals(16.0, withPa.zoomFor(42.12, -80.08, 16.0)) // Erie: inside Pennsylvania's box
        assertEquals(9.99, withPa.zoomFor(34.05, -118.24, 16.0), 1e-9) // Los Angeles: overview only
        assertEquals(8.0, withPa.zoomFor(34.05, -118.24, 8.0))
        val noOverview = StyleComposer.compose(style(), Theme.DAY, null, packs())
        assertNull(noOverview.maxZoom)
        assertEquals(16.0, noOverview.zoomFor(34.05, -118.24, 16.0))
    }

    @Test
    fun `zoom gating keeps what is left of a layer`() {
        val layer = parse("""{"id":"x","minzoom":3,"maxzoom":12}""")
        assertEquals(8.0, StyleComposer.withZooms(layer, minZoom = 8.0)!!.zoom("minzoom"))
        assertEquals(8.0, StyleComposer.withZooms(layer, maxZoom = 8.0)!!.zoom("maxzoom"))
        assertNull(StyleComposer.withZooms(layer, minZoom = 12.0))
        assertNull(StyleComposer.withZooms(parse("""{"id":"x","minzoom":18}"""), maxZoom = 8.0))
    }

    @Test
    fun `nothing installed at all is an error the screen can show`() {
        assertEquals("no map installed", assertThrows<StyleException> { StyleComposer.compose(style(), Theme.DAY, null, emptyList<InstalledPack>()) }.message)
    }

    /** Skeleton review finding, kept: a template of the wrong shape must not crash the app. */
    @ParameterizedTest(name = "{0}")
    @CsvSource(
        delimiter = '|',
        value = [
            "sources is an array      | {\"version\":8,\"sources\":[1],\"layers\":[]}                      | \"sources\" must be an object",
            "layers is an object      | {\"version\":8,\"sources\":{\"protomaps\":{}},\"layers\":{\"a\":1}} | \"layers\" must be an array",
            "a layer is a number      | {\"version\":8,\"sources\":{\"protomaps\":{}},\"layers\":[5]}         | layer #0 is not an object",
            "a layer has no id        | {\"version\":8,\"sources\":{\"protomaps\":{}},\"layers\":[{\"type\":\"line\"}]} | layer #0 has no id",
            "empty style              | {}                                                                     | no vector source",
            "style is an array        | [1,2]                                                                  | is not a JSON object",
            "no layer draws the tiles | {\"version\":8,\"sources\":{\"protomaps\":{}},\"layers\":[{\"id\":\"bg\",\"type\":\"background\"}]} | has no layer that draws",
            "not JSON                 | {not json                                                              | cannot read the day style",
        ],
    )
    fun `a style template of the wrong shape is a StyleException`(case: String, template: String, expected: String) {
        val e = assertThrows<StyleException>(case) { StyleComposer.compose(style(day = template), Theme.DAY, overview, emptyList<InstalledPack>()) }
        assertTrue(e.message!!.contains(expected), "$case: ${e.message}")
    }

    @Test
    fun `the OpenStreetMap credit is always there`() {
        TestPacks.write(root, "us-pa", edit = { it.replace("\"© OpenStreetMap contributors\"", "\"  \"") })
        val c = StyleComposer.compose(style(), Theme.DAY, null, packs())
        assertEquals(listOf(StyleComposer.OSM_CREDIT), c.attributions)
        assertEquals(listOf("© OpenStreetMap contributors", "Protomaps"), StyleComposer.credits(listOf("Protomaps", "")))
    }

    @Test
    fun `the rules file the app ships is read`() {
        // Gradle runs the tests in the module folder (android/core/packs).
        val r = ComposeRules.parse(File("../../app/src/main/assets/rules/map-compose.json").readText())
        assertEquals("protomaps", r.tileSource)
        assertEquals(4, r.schemaMajor)
        assertEquals(8.0, r.stateMinZoom)
        assertEquals(10.0, r.overviewMaxZoom)
    }
}
