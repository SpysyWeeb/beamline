package io.github.spysyweeb.beamline.packs

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import io.github.spysyweeb.beamline.api.data.PackInfo
import java.io.File

enum class Theme { DAY, NIGHT }

/**
 * Rules for putting the map together, read from the app's `assets/rules/map-compose.json`
 * (rules live in data files). Field names are the JSON keys.
 */
@Serializable
data class ComposeRules(
    /** The vector source in the style templates that the tiles feed. */
    @SerialName("tile_source") val tileSource: String = "protomaps",
    /** Tile schema and major version the app's style is written for. */
    val schema: String = "protomaps-basemap",
    @SerialName("schema_major") val schemaMajor: Int = 4,
    /** With a state pack installed: state layers start at this zoom ... */
    @SerialName("state_min_zoom") val stateMinZoom: Double = 8.0,
    /** ... and the overview map's labels stop here, so a place is never labelled twice. */
    @SerialName("base_label_max_zoom") val baseLabelMaxZoom: Double = 8.0,
    /**
     * The overview (z0-7 tiles) is not drawn above this zoom at all. Overzoomed tiles are expensive
     * in MapLibre Native (measured once on a loaded emulator: Pittsburgh z14 panned at about 2
     * frames/s with the overview at every zoom, 10 with this cap; probably each overzoomed tile
     * draws its whole parent tile). Above this zoom, places without a state pack show the plain
     * background; with no state pack at all the camera stops here ([ComposedMap.maxZoom]).
     */
    @SerialName("overview_max_zoom") val overviewMaxZoom: Double = 10.0,
    /** Layer types that count as labels for [baseLabelMaxZoom]. */
    @SerialName("label_layer_types") val labelLayerTypes: List<String> = listOf("symbol", "circle"),
    /** Camera box (west, south, east, north) when only the overview map is installed. */
    @SerialName("overview_bounds") val overviewBounds: List<Double> = listOf(-125.0, 24.4, -66.9, 49.4),
) {
    fun readers() = Readers(mapSchema = schema, mapSchemaMajor = schemaMajor)

    companion object {
        fun parse(text: String): ComposeRules = PackManifest.json.decodeFromString(serializer(), text)
    }
}

/** The style that ships in the app: templates and where its glyphs and sprites are. */
data class AppStyle(
    val dayTemplate: String,
    val nightTemplate: String,
    /** e.g. `asset://style/glyphs/{fontstack}/{range}.pbf` */
    val glyphsUrl: String,
    /** e.g. `asset://style/sprites/light` */
    val spriteDayUrl: String,
    val spriteNightUrl: String,
    val rules: ComposeRules,
)

/** The whole-US overview map bundled in the app (map only). */
data class OverviewMap(
    /** e.g. `pmtiles://asset://basemap/us-base.pmtiles` */
    val url: String,
    val attribution: String,
)

/** What the map screen needs to show. */
data class ComposedMap(
    /** Complete MapLibre style JSON. */
    val styleJson: String,
    /**
     * Distinct credit lines. From `compose(..., List<InstalledPack>)` (round 2's rule): the lines of
     * the overview and the packs, with the OpenStreetMap credit added first when no line names it.
     * From `compose(..., List<PackInfo>)`: the credit items the overview and the installed parts
     * name, and nothing else (an overview-only map credits "Natural Earth; US Census Bureau" and no
     * OpenStreetMap, decision 6.1).
     */
    val attributions: List<String>,
    /** Where to put the camera first: the installed packs, or the lower 48 with the overview only. */
    val bounds: List<Double>,
    /** Ids of the state packs drawn on the map. */
    val packIds: List<String>,
    val hasOverview: Boolean,
    /** Box (west, south, east, north) of each state pack on the map. */
    val packBounds: List<List<Double>> = emptyList(),
    /** The overview is drawn up to this zoom; null without the overview. */
    val overviewMaxZoom: Double? = null,
    /** The one line the map shows: [attributions] joined with " · ". */
    val creditLine: String = attributions.joinToString(" · "),
) {
    /**
     * Highest zoom the camera may reach, null for no limit. With only the overview installed it is
     * just below the overview's `maxzoom`: above it the map would be blank.
     */
    val maxZoom: Double? get() = if (packIds.isEmpty()) overviewCameraLimit else null

    /**
     * The zoom to centre on a position at, at most [wanted]. Outside every state pack's box only
     * the overview can show anything, so the zoom stops just below the overview's `maxzoom`.
     */
    fun zoomFor(lat: Double, lon: Double, wanted: Double): Double {
        val limit = overviewCameraLimit ?: return wanted
        val inside = packBounds.any { b -> b.size == 4 && lon in b[0]..b[2] && lat in b[1]..b[3] }
        return if (inside) wanted else minOf(wanted, limit)
    }

    /** MapLibre hides a layer at every zoom >= its `maxzoom` (seen on the emulator: z10.00 was blank). */
    private val overviewCameraLimit: Double? get() = overviewMaxZoom?.let { it - BELOW_MAXZOOM }

    companion object {
        /** How far below the overview's `maxzoom` the camera stops. */
        const val BELOW_MAXZOOM = 0.01
    }
}

class StyleException(message: String) : Exception(message)

/**
 * Builds the MapLibre style from the app's style template, the overview map and the installed
 * state packs. Pure: no Android, no files read (the caller passes the template text).
 *
 * - Every template layer that draws the tile source is copied once per map file: first a copy
 *   for the overview map (all of them together, drawn first), then the copies for the state packs
 *   interleaved layer by layer, so states keep the template's order (all water before all roads).
 * - The overview is never drawn above [ComposeRules.overviewMaxZoom] (cost of overzoomed tiles).
 * - With both present, the state copies start at [ComposeRules.stateMinZoom] and the overview's
 *   label layers stop at [ComposeRules.baseLabelMaxZoom]. Below that zoom the overview draws
 *   everything (one consistent map, no doubled labels); above it, the states' opaque land and water
 *   cover the overview inside a state, and around the states the overview still shows land, water
 *   and main roads, without labels.
 * - Any problem with the template is a [StyleException] with a message for the screen.
 */
object StyleComposer {

    const val OSM_CREDIT = "© OpenStreetMap contributors"
    const val OVERVIEW_SOURCE = "overview"

    private val json = Json { prettyPrint = false }

    /** One state pack's map on the style: its id, `pmtiles://` URL, credit and box. */
    private class Drawn(val id: String, val url: String, val attribution: String, val bounds: List<Double>)

    /** The style from packs found by [PackScanner] (the old pack screen's path). */
    fun compose(style: AppStyle, theme: Theme, overview: OverviewMap?, packs: List<InstalledPack>): ComposedMap =
        composeDrawn(
            style, theme, overview,
            packs.map { Drawn(it.id, "pmtiles://" + fileUrl(it.mapFile), it.manifest.attribution, it.manifest.bounds) },
        ) { lines -> credits(lines) }

    /**
     * The style from the installed packs of one `InstalledSet` generation (docs/architecture.md 2.2:
     * recomposed when the generation or the theme changes). Each pack's map is
     * `pmtiles://file://<PackInfo.mapFile>`; its source carries the pack's [PackInfo.attribution].
     * The credits are the overview's and the packs' attributions split into their items
     * ("© OpenStreetMap contributors; U.S. Census Bureau TIGER/Line" is two), each item once, in
     * that order; [ComposedMap.creditLine] is the line the map shows. A pack installed without
     * house numbers credits no address source (its attribution has none).
     */
    @JvmName("composeInstalled")
    fun compose(style: AppStyle, theme: Theme, overview: OverviewMap?, packs: List<PackInfo>): ComposedMap =
        composeDrawn(
            style, theme, overview,
            packs.map { p ->
                Drawn(p.packId, "pmtiles://" + fileUrl(File(p.mapFile)), p.attribution,
                    listOf(p.bounds.west, p.bounds.south, p.bounds.east, p.bounds.north))
            },
        ) { lines -> creditItems(lines) }

    private fun composeDrawn(
        style: AppStyle,
        theme: Theme,
        overview: OverviewMap?,
        packs: List<Drawn>,
        credit: (List<String>) -> List<String>,
    ): ComposedMap {
        if (overview == null && packs.isEmpty()) throw StyleException("no map installed")
        val template = if (theme == Theme.DAY) style.dayTemplate else style.nightTemplate
        val where = if (theme == Theme.DAY) "day style" else "night style"
        return try {
            composeFrom(style, template, theme, overview, packs.sortedBy { it.id }, where, credit)
        } catch (e: StyleException) {
            throw e
        } catch (e: Exception) {
            throw StyleException("$where: ${e.message ?: e::class.simpleName}")
        }
    }

    private fun composeFrom(
        style: AppStyle,
        templateText: String,
        theme: Theme,
        overview: OverviewMap?,
        packs: List<Drawn>,
        where: String,
        credit: (List<String>) -> List<String>,
    ): ComposedMap {
        val rules = style.rules
        val parsed = try {
            json.parseToJsonElement(templateText)
        } catch (e: Exception) {
            throw StyleException("cannot read the $where: ${e.message?.lineSequence()?.firstOrNull()}")
        }
        val template = parsed as? JsonObject ?: throw StyleException("the $where is not a JSON object")
        val templateSources = when (val s = template["sources"]) {
            null -> JsonObject(emptyMap())
            is JsonObject -> s
            else -> throw StyleException("$where: \"sources\" must be an object")
        }
        val base = templateSources[rules.tileSource] as? JsonObject
            ?: throw StyleException("$where has no vector source \"${rules.tileSource}\"")
        val templateLayers = template["layers"] as? JsonArray ?: throw StyleException("$where: \"layers\" must be an array")

        val sources = linkedMapOf<String, JsonElement>()
        for ((name, src) in templateSources) if (name != rules.tileSource) sources[name] = src
        fun vector(url: String, attribution: String) = JsonObject(
            base + mapOf("type" to JsonPrimitive("vector"), "url" to JsonPrimitive(url), "attribution" to JsonPrimitive(attribution)),
        )
        if (overview != null) sources[OVERVIEW_SOURCE] = vector(overview.url, overview.attribution)
        val packSource = packs.associate { it.id to "${rules.tileSource}-${suffix(it.id)}" }
        for (p in packs) sources[packSource.getValue(p.id)] = vector(p.url, p.attribution)

        val both = overview != null && packs.isNotEmpty()
        val checked = templateLayers.mapIndexed { index, layer ->
            val obj = layer as? JsonObject ?: throw StyleException("$where: layer #$index is not an object")
            val id = (obj["id"] as? JsonPrimitive)?.takeIf { it.isString && it.content.isNotEmpty() }?.content
                ?: throw StyleException("$where: layer #$index has no id")
            Triple(obj, id, (obj["source"] as? JsonPrimitive)?.content == rules.tileSource)
        }
        if (checked.none { it.third }) throw StyleException("$where has no layer that draws source \"${rules.tileSource}\"")

        val layers = mutableListOf<JsonElement>()
        if (overview != null) {
            for ((obj, id, drawsTiles) in checked) {
                if (!drawsTiles) {
                    layers += obj
                    continue
                }
                val isLabel = (obj["type"] as? JsonPrimitive)?.content in rules.labelLayerTypes
                val maxZoom = if (both && isLabel) minOf(rules.baseLabelMaxZoom, rules.overviewMaxZoom) else rules.overviewMaxZoom
                val gated = withZooms(obj, maxZoom = maxZoom)
                if (gated != null) layers += copy(gated, "$id@$OVERVIEW_SOURCE", OVERVIEW_SOURCE)
            }
        }
        for ((obj, id, drawsTiles) in checked) {
            if (!drawsTiles) {
                if (overview == null) layers += obj
                continue
            }
            val gated = if (both) withZooms(obj, minZoom = rules.stateMinZoom) else obj
            if (gated != null) for (p in packs) layers += copy(gated, "$id@${suffix(p.id)}", packSource.getValue(p.id))
        }

        val out = template.toMutableMap()
        out["sources"] = JsonObject(sources)
        out["layers"] = JsonArray(layers)
        out["glyphs"] = JsonPrimitive(style.glyphsUrl)
        out["sprite"] = JsonPrimitive(if (theme == Theme.DAY) style.spriteDayUrl else style.spriteNightUrl)

        val bounds = if (packs.isEmpty()) {
            rules.overviewBounds
        } else {
            val b = packs.map { it.bounds }
            listOf(b.minOf { it[0] }, b.minOf { it[1] }, b.maxOf { it[2] }, b.maxOf { it[3] })
        }
        return ComposedMap(
            styleJson = json.encodeToString(JsonObject.serializer(), JsonObject(out)),
            attributions = credit(listOfNotNull(overview?.attribution) + packs.map { it.attribution }),
            bounds = bounds,
            packIds = packs.map { it.id },
            hasOverview = overview != null,
            packBounds = packs.map { it.bounds },
            overviewMaxZoom = if (overview != null) rules.overviewMaxZoom else null,
        )
    }

    /**
     * The credit items of [lines]: each line split at ";", trimmed, blank ones dropped, each item
     * once (first occurrence wins the place). No credit is added that no line names.
     */
    fun creditItems(lines: List<String>): List<String> =
        lines.flatMap { it.split(";") }.map { it.trim() }.filter { it.isNotEmpty() }.distinct()

    private fun copy(layer: JsonObject, id: String, source: String) =
        JsonObject(layer + mapOf("id" to JsonPrimitive(id), "source" to JsonPrimitive(source)))

    /**
     * The layer with its zoom range narrowed to at least [minZoom] / at most [maxZoom];
     * null if nothing is left of it.
     */
    internal fun withZooms(layer: JsonObject, minZoom: Double? = null, maxZoom: Double? = null): JsonObject? {
        val oldMin = (layer["minzoom"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
        val oldMax = (layer["maxzoom"] as? JsonPrimitive)?.doubleOrNull ?: 24.0
        val newMin = maxOf(oldMin, minZoom ?: oldMin)
        val newMax = minOf(oldMax, maxZoom ?: oldMax)
        if (newMin >= newMax) return null
        val out = layer.toMutableMap()
        if (newMin > 0.0) out["minzoom"] = JsonPrimitive(newMin)
        if (newMax < 24.0) out["maxzoom"] = JsonPrimitive(newMax)
        return JsonObject(out)
    }

    /** Distinct, non-blank credit lines, with the OpenStreetMap credit first if no line names it. */
    fun credits(lines: List<String>): List<String> {
        val distinct = lines.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
        return if (distinct.any { it.contains("OpenStreetMap", ignoreCase = true) }) distinct else listOf(OSM_CREDIT) + distinct
    }

    /**
     * `file://` URL for a local file. The path is used as is (MapLibre Native reads the part after
     * `file://` as a path).
     */
    fun fileUrl(f: File): String {
        val path = f.absolutePath.replace('\\', '/')
        return "file://" + (if (path.startsWith("/")) path else "/$path")
    }

    /**
     * The pack id as used in source and layer ids. Letters, digits and '-' stay as they are (so
     * `us-pa` stays `us-pa`); every other character, '_' included, becomes '_' and its UTF-16 code
     * in four hex digits. The mapping is one-to-one, so two different pack ids (e.g. `col.a` and
     * `col_a`, both valid) can never share a source or a layer id.
     */
    internal fun suffix(id: String): String = buildString {
        for (c in id) {
            if (c in 'a'..'z' || c in 'A'..'Z' || c in '0'..'9' || c == '-') append(c) else append('_').append("%04x".format(c.code))
        }
    }
}
