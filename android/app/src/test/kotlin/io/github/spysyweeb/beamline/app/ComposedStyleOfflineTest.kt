package io.github.spysyweeb.beamline.app

import io.github.spysyweeb.beamline.api.data.PackInfo
import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.packs.ComposeRules
import io.github.spysyweeb.beamline.packs.StyleComposer
import io.github.spysyweeb.beamline.packs.Theme
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * Rule N5 (docs/architecture-downloads.md 4.2 item 3): the styles the app composes, day and night,
 * from its real style assets, the URLs AppGraph passes (AppStyles), the bundled overview and an
 * installed pack, point only at local files. Every `sources.*.url`, `sources.*.tiles[]`, `glyphs`
 * and `sprite` value starts with one of the allowed prefixes (an allow-list: no other scheme passes).
 */
class ComposedStyleOfflineTest {

    private val appDir = File(System.getProperty("beamline.appDir") ?: ".")
    private fun asset(path: String) = File(appDir, "src/main/assets/$path").readText(Charsets.UTF_8)

    private val allowed = listOf("asset://", "file://", "pmtiles://asset://", "pmtiles://file://")

    private val pack = PackInfo(
        packId = "us-pa", name = "Pennsylvania", state = "PA", osmSnapshot = "2026-11-14",
        bounds = Bounds(-80.52, 39.72, -74.69, 42.27),
        attribution = "© OpenStreetMap contributors; U.S. Census Bureau TIGER/Line",
        folder = "/storage/emulated/0/Android/data/io.github.spysyweeb.beamline/files/packs/us-pa",
        mapFile = "/storage/emulated/0/Android/data/io.github.spysyweeb.beamline/files/packs/us-pa/map.pmtiles",
        mapMinZoom = 0, mapMaxZoom = 14,
        searchFile = null, addressesFile = null, addressesSkipped = false, searchProblem = null,
        searchRankingSha256 = null, inRoutingSet = true, verified = true, bytesOnDisk = 1L, packVersion = 202611160853,
    )

    /** Every URL a MapLibre style names (sources, glyphs, sprite in both forms). */
    private fun urls(styleJson: String): List<String> {
        val style = Json.parseToJsonElement(styleJson).jsonObject
        val out = mutableListOf<String>()
        (style["sources"] as? JsonObject)?.values?.forEach { s ->
            val o = s.jsonObject
            (o["url"] as? JsonPrimitive)?.let { out += it.content }
            (o["tiles"] as? JsonArray)?.forEach { out += it.jsonPrimitive.content }
            (o["data"] as? JsonPrimitive)?.takeIf { it.isString }?.let { out += it.content }
        }
        (style["glyphs"] as? JsonPrimitive)?.let { out += it.content }
        when (val sprite = style["sprite"]) {
            is JsonPrimitive -> out += sprite.content
            is JsonArray -> sprite.forEach { out += it.jsonObject["url"]!!.jsonPrimitive.content }
            else -> Unit
        }
        return out
    }

    private fun offending(styleJson: String) = urls(styleJson).filter { u -> allowed.none { u.startsWith(it) } }

    @Test
    fun theComposedStylesPointOnlyAtLocalFiles() {
        val rules = ComposeRules.parse(asset("rules/map-compose.json"))
        val style = AppStyles.appStyle(::asset, rules)
        for (theme in Theme.entries) {
            for (packs in listOf(emptyList(), listOf(pack))) {
                val composed = StyleComposer.compose(style, theme, AppStyles.overview(null), packs)
                val all = urls(composed.styleJson)
                assertTrue(all.size >= 3, "$theme: a style with sources, glyphs and sprites")
                assertEquals(emptyList<String>(), offending(composed.styleJson), "$theme with ${packs.size} pack(s)")
                assertTrue(all.contains("pmtiles://asset://basemap/us-base.pmtiles"))
                if (packs.isNotEmpty()) assertTrue(all.any { it.startsWith("pmtiles://file://") && it.endsWith("/us-pa/map.pmtiles") })
            }
        }
    }

    @Test
    fun theCheckItselfCatchesARemoteUrl() {
        val remote = """{"version":8,"sources":{"a":{"type":"vector","tiles":["https://tiles.example.org/{z}/{x}/{y}.pbf"]}},
            "glyphs":"asset://g/{fontstack}/{range}.pbf","sprite":[{"id":"default","url":"http://example.org/s"}],"layers":[]}"""
        assertEquals(listOf("https://tiles.example.org/{z}/{x}/{y}.pbf", "http://example.org/s"), offending(remote))
    }
}
