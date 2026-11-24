package io.github.spysyweeb.beamline.packs

import io.github.spysyweeb.beamline.api.data.PackInfo
import io.github.spysyweeb.beamline.api.model.Bounds
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File

/** The overload of docs/architecture.md 2.2: the style from one generation's PackInfo list. */
class StyleComposerInstalledTest {

    private val style = AppStyle(
        dayTemplate = TestPacks.STYLE_DAY,
        nightTemplate = TestPacks.STYLE_NIGHT,
        glyphsUrl = "asset://style/glyphs/{fontstack}/{range}.pbf",
        spriteDayUrl = "asset://style/sprites/light",
        spriteNightUrl = "asset://style/sprites/dark",
        rules = ComposeRules(),
    )

    /** The public-domain overview (decision 6.1) credits its own sources, not OpenStreetMap. */
    private val overview = OverviewMap("pmtiles://asset://basemap/us-base.pmtiles", "Natural Earth; U.S. Census Bureau")

    private val osm = "© OpenStreetMap contributors"
    private val addressCredit = "$osm; U.S. Census Bureau TIGER/Line; USDOT National Address Database; City of Scranton GIS"

    private fun info(id: String, state: String, bounds: Bounds, attribution: String) = PackInfo(
        packId = id, name = state, state = state, osmSnapshot = "2026-11-14", bounds = bounds,
        attribution = attribution,
        folder = File("/data/packs/$id").absolutePath,
        mapFile = File("/data/packs/$id/map.pmtiles").absolutePath,
        mapMinZoom = 0, mapMaxZoom = 14,
        searchFile = null, addressesFile = null, addressesSkipped = attribution == osm, searchProblem = null,
        searchRankingSha256 = null, inRoutingSet = true, verified = true, bytesOnDisk = 1, packVersion = 202611160400,
    )

    private val pa = info("us-pa", "PA", Bounds(-80.52, 39.72, -74.69, 42.52), addressCredit)
    private val oh = info("us-oh", "OH", Bounds(-84.82, 38.40, -80.52, 42.33), osm)

    private fun parse(s: String): JsonObject = Json.parseToJsonElement(s).jsonObject

    @Test
    fun `two states and the overview give one source per pack, file URLs and each credit item once`() {
        val c = StyleComposer.compose(style, Theme.DAY, overview, listOf(pa, oh))
        val sources = parse(c.styleJson)["sources"]!!.jsonObject
        assertEquals(setOf("overview", "protomaps-us-oh", "protomaps-us-pa"), sources.keys)
        val paUrl = sources["protomaps-us-pa"]!!.jsonObject["url"]!!.jsonPrimitive.content
        assertEquals("pmtiles://" + StyleComposer.fileUrl(File(pa.mapFile)), paUrl)
        assertTrue(paUrl.startsWith("pmtiles://file:///"), paUrl)
        assertEquals(addressCredit, sources["protomaps-us-pa"]!!.jsonObject["attribution"]!!.jsonPrimitive.content)
        // Sorted by id: Ohio's layers come before Pennsylvania's in each template layer.
        assertEquals(listOf("us-oh", "us-pa"), c.packIds)
        assertEquals(
            listOf("Natural Earth", "U.S. Census Bureau", osm, "U.S. Census Bureau TIGER/Line", "USDOT National Address Database", "City of Scranton GIS"),
            c.attributions,
        )
        assertEquals(c.attributions.joinToString(" · "), c.creditLine)
        assertEquals(listOf(-84.82, 38.40, -74.69, 42.52), c.bounds)
        assertEquals(listOf(listOf(-84.82, 38.40, -80.52, 42.33), listOf(-80.52, 39.72, -74.69, 42.52)), c.packBounds)
        val ids = parse(c.styleJson)["layers"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        assertTrue(ids.indexOf("water@us-oh") < ids.indexOf("water@us-pa"))
    }

    @Test
    fun `a state without house numbers credits no address source`() {
        val paWithout = pa.copy(attribution = osm, addressesSkipped = true)
        val c = StyleComposer.compose(style, Theme.NIGHT, null, listOf(paWithout))
        assertEquals(listOf(osm), c.attributions)
        assertEquals(osm, c.creditLine)
        assertFalse(c.styleJson.contains("TIGER"))
        assertEquals("asset://style/sprites/dark", parse(c.styleJson)["sprite"]!!.jsonPrimitive.content)
    }

    @Test
    fun `nothing installed is refused, the overview alone credits only itself`() {
        assertEquals("no map installed", assertThrows<StyleException> { StyleComposer.compose(style, Theme.DAY, null, emptyList<PackInfo>()) }.message)
        val c = StyleComposer.compose(style, Theme.DAY, overview, emptyList<PackInfo>())
        assertEquals(listOf("Natural Earth", "U.S. Census Bureau"), c.attributions)
        assertEquals(emptyList<String>(), c.packIds)
        assertEquals(ComposeRules().overviewBounds, c.bounds)
    }

    @Test
    fun `the style points only at local files and the app's assets`() {
        val c = StyleComposer.compose(style, Theme.DAY, overview, listOf(pa, oh))
        val urls = Regex("\"(?:url|glyphs|sprite)\":\"([^\"]+)\"").findAll(c.styleJson).map { it.groupValues[1] }.toList()
        assertTrue(urls.isNotEmpty())
        for (u in urls) assertTrue(u.startsWith("pmtiles://file:///") || u.startsWith("pmtiles://asset://") || u.startsWith("asset://"), u)
        assertFalse(c.styleJson.contains("http"), "no web address in the composed style (rule N5)")
    }
}
