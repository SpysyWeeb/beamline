package io.github.spysyweeb.beamline.app

import io.github.spysyweeb.beamline.packs.AppStyle
import io.github.spysyweeb.beamline.packs.ComposeRules
import io.github.spysyweeb.beamline.packs.OverviewMap

/**
 * Where the app's map style comes from: the style bundle in `assets/style` (copied by
 * `android/scripts/sync_assets.py`), its glyphs and sprites as `asset://` URLs, and the bundled
 * overview map. Plain Kotlin, so the JVM test `ComposedStyleOfflineTest` composes exactly what the
 * app composes (rule N5: every URL of a composed style is local).
 */
object AppStyles {
    const val GLYPHS_URL = "asset://style/glyphs/{fontstack}/{range}.pbf"
    const val SPRITE_DAY_URL = "asset://style/sprites/light"
    const val SPRITE_NIGHT_URL = "asset://style/sprites/dark"
    const val OVERVIEW_URL = "pmtiles://asset://basemap/us-base.pmtiles"

    /** The overview's credit (decision 6.1: public-domain Natural Earth and Census data). */
    const val OVERVIEW_CREDIT_DEFAULT = "Natural Earth; US Census Bureau"

    /** [asset] reads an asset of the app as text ("style/day.json"). */
    fun appStyle(asset: (String) -> String, rules: ComposeRules): AppStyle = AppStyle(
        dayTemplate = asset("style/day.json"),
        nightTemplate = asset("style/night.json"),
        glyphsUrl = GLYPHS_URL,
        spriteDayUrl = SPRITE_DAY_URL,
        spriteNightUrl = SPRITE_NIGHT_URL,
        rules = rules,
    )

    fun overview(attribution: String?): OverviewMap = OverviewMap(OVERVIEW_URL, attribution ?: OVERVIEW_CREDIT_DEFAULT)
}
