package io.github.spysyweeb.beamline.app.map

import io.github.spysyweeb.beamline.app.online.OwnChange
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point

/**
 * The own-changes layer (batch O3, docs/next-round.md item 26): the user's sent additions, renames and
 * replacements drawn on the map like the map's own places, until an installed pack has them.
 *
 * [inject] adds to the style, before it is loaded, a GeoJSON source and a copy of the style's own POI
 * layer (its text font, size, colours and halo, day or night as the style is) that takes its icon from
 * each point (the category's sprite, the same as the map's and the result rows'). Its id "pois@own" has
 * the base id "pois", so the map's tap code treats its labels as POIs. The old label of a renamed,
 * replaced or closed place is hidden by a filter on every copy of the map's POI layers on the tiles'
 * feature id (Protomaps: (type << 44) | OSM id; [OwnChanges.tileId]), exact, with no guessing by name.
 * [OwnLayerHost] sets the points and the filters when the changes change. Nothing here logs (PV2).
 */
object OwnLayer {
    const val SOURCE = "own-places"
    const val LAYER = "pois@own"
    private val POI_BASES = setOf("pois", "pois_driving")
    /** The own points show from this zoom on (the map's shops and cafes from about 15-16; a little earlier for one's own). */
    const val MIN_ZOOM = 14.0

    /** The style with the own-changes source and layer, and the original filter of every POI layer copy. */
    class Injected(val json: String, val filters: Map<String, JsonElement?>)

    fun inject(styleJson: String): Injected = try {
        val root = Json.parseToJsonElement(styleJson) as JsonObject
        val layers = (root["layers"] as JsonArray).map { it as JsonObject }
        fun id(l: JsonObject) = (l["id"] as JsonPrimitive).content
        fun base(l: JsonObject) = id(l).substringBefore('@')
        val pois = layers.filter { base(it) in POI_BASES }
        val template = pois.firstOrNull { base(it) == "pois" && !id(it).endsWith("@overview") } ?: pois.firstOrNull { base(it) == "pois" }
        if (template == null) Injected(styleJson, emptyMap()) else {
            val layout = (template["layout"] as? JsonObject).orEmpty().toMutableMap()
            layout["icon-image"] = Json.parseToJsonElement("""["get","icon"]""")
            layout.remove("symbol-sort-key")
            val own = buildMap<String, JsonElement> {
                put("id", JsonPrimitive(LAYER))
                put("type", JsonPrimitive("symbol"))
                put("source", JsonPrimitive(SOURCE))
                template["minzoom"]?.let { put("minzoom", it) }
                put("filter", Json.parseToJsonElement("""[">=",["zoom"],$MIN_ZOOM]"""))
                put("layout", JsonObject(layout))
                ownPaint(template, pois.firstOrNull { base(it) == "pois_driving" })?.let { put("paint", it) }
            }
            val last = layers.indexOfLast { base(it) in POI_BASES && !id(it).endsWith("@overview") }.takeIf { it >= 0 } ?: layers.indexOf(template)
            val out = layers.toMutableList<JsonElement>()
            out.add(last + 1, JsonObject(own))
            val sources = (root["sources"] as JsonObject).toMutableMap()
            sources[SOURCE] = Json.parseToJsonElement("""{"type":"geojson","data":{"type":"FeatureCollection","features":[]}}""")
            val next = root.toMutableMap()
            next["sources"] = JsonObject(sources)
            next["layers"] = JsonArray(out)
            Injected(JsonObject(next).toString(), pois.associate { id(it) to it["filter"] })
        }
    } catch (_: Exception) {
        Injected(styleJson, emptyMap())   // a style this code does not know: no own layer, the map as before
    }

    /**
     * The template's paint, its text colour by kind completed: the "pois" layer colours only its own kinds
     * and falls back to its halo colour (its other kinds are drawn by "pois_driving"); the own layer draws
     * every kind, so the fallback becomes "pois_driving"'s colour by kind (fuel, parking, banks ...).
     */
    private fun ownPaint(template: JsonObject, driving: JsonObject?): JsonObject? {
        val paint = template["paint"] as? JsonObject ?: return null
        val color = paint["text-color"] as? JsonArray ?: return paint
        val other = (driving?.get("paint") as? JsonObject)?.get("text-color") ?: return paint
        if ((color.firstOrNull() as? JsonPrimitive)?.content != "case") return paint
        return JsonObject(paint + ("text-color" to JsonArray(color.dropLast(1) + other)))
    }

    /** A POI layer's filter with the features [hidden] left out (the original filter and "not one of these ids"). */
    fun filterWith(original: JsonElement?, hidden: List<Long>): String {
        val not = """["!",["in",["id"],["literal",[${hidden.joinToString(",")}]]]]"""
        return if (original == null) not else """["all",$original,$not]"""
    }
}

/** Puts the own changes on a loaded style made from [OwnLayer.Injected]; on the main thread. */
class OwnLayerHost(private val injected: OwnLayer.Injected) {
    private var lastHidden: List<Long>? = null

    fun apply(style: Style, changes: List<OwnChange>) {
        if (!style.isFullyLoaded) return
        val src = style.getSourceAs<GeoJsonSource>(OwnLayer.SOURCE) ?: return
        val features = changes.filter { it.drawn }.map { c ->
            Feature.fromGeometry(Point.fromLngLat(c.ui.point.lon, c.ui.point.lat)).apply {
                addStringProperty("name", c.ui.name)
                addStringProperty("icon", c.icon ?: "building")
                // the style colours a label by the tiles' kind: the OSM value of the place's main tag
                // ("fast_food"), else the icon's name, which is the kind for the kinds both use
                addStringProperty("kind", c.kind ?: c.icon ?: "building")
            }
        }
        src.setGeoJson(FeatureCollection.fromFeatures(features))
        val hidden = changes.flatMap { it.hiddenTileIds }.distinct().sorted()
        if (hidden == lastHidden) return
        for ((id, original) in injected.filters) {
            val layer = style.getLayerAs<SymbolLayer>(id) ?: continue
            if (hidden.isEmpty()) {
                if (original != null) layer.setFilter(Expression.raw(original.toString())) else layer.setFilter(Expression.literal(true))
            } else {
                layer.setFilter(Expression.raw(OwnLayer.filterWith(original, hidden)))
            }
        }
        lastHidden = hidden
    }
}
