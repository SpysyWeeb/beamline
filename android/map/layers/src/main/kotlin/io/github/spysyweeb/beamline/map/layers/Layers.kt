package io.github.spysyweeb.beamline.map.layers

import android.content.Context
import android.graphics.RectF
import android.os.SystemClock
import android.util.Log
import android.view.Choreographer
import io.github.spysyweeb.beamline.api.map.CameraTarget
import io.github.spysyweeb.beamline.api.map.MapGesture
import io.github.spysyweeb.beamline.api.map.MapPin
import io.github.spysyweeb.beamline.api.map.MapPoi
import io.github.spysyweeb.beamline.api.map.MapScene
import io.github.spysyweeb.beamline.api.map.MapTheme
import io.github.spysyweeb.beamline.api.map.SavedMarker
import io.github.spysyweeb.beamline.api.map.VehicleMarker
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.SavedKind
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.expressions.Expression.all
import org.maplibre.android.style.expressions.Expression.eq
import org.maplibre.android.style.expressions.Expression.get
import org.maplibre.android.style.expressions.Expression.has
import org.maplibre.android.style.expressions.Expression.literal
import org.maplibre.android.style.expressions.Expression.not
import org.maplibre.android.style.expressions.Expression.switchCase
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.FillLayer
import org.maplibre.android.style.layers.Layer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import org.maplibre.geojson.Polygon
import kotlin.math.hypot

/**
 * What [MapLibreController] does: the app's sources and layers on one MapLibreMap, the camera moves
 * and the gestures. Main thread only.
 *
 * Layer order (docs/architecture.md 5.1 M, assets/markers/SPEC.md, docs/architecture-saved-places.md
 * SP42-SP43): below the style's "roads_oneway" (so above roads and bridges, below one-way arrows,
 * shields and every label: route_colors.json layer_order) the saved markers' "under" layer, then the
 * alternatives, the driven part and the chosen line ahead, each a casing and a fill, the driven part
 * and the part ahead each in a far and a near layer ([RouteCut]: a cursor step sends only the short
 * near pieces); above every style layer the saved markers' "over" layer, the pins, the puck (cone,
 * dot) and the arrow (beam, glow, arrow, lit lenses). While guiding (the arrow shows) the chosen line
 * ahead is drawn instead in a copy above the road labels and below the POI and place names
 * ([Ids.GUIDE_ROUTE_ON_TOP]), so road names do not sit on it in the tilted view; the style's shield
 * layers are moved above that copy meanwhile and back when guiding ends ([Ids.GUIDE_SHIELDS]: the
 * only style layers that move, owner 2026-11-18). In a style composed with the overview map and a
 * state, the layers below the labels are there twice: the set under "roads_oneway@<state>" from the
 * zoom the states begin at, and a copy under the overview's first label layer below it
 * ([SceneParts.routePlacement]). Only one of the two saved layers is visible, as
 * MapScene.savedBelowRoute says, so they never move at run time (only the shields do); while guiding the markers are
 * therefore below the route lines and below the style's labels too (they still show: overlap allowed).
 */
internal class Layers(context: Context, private val map: MapLibreMap) {
    private val res = context.resources
    private val density = res.displayMetrics.density
    private val colors: RouteColors =
        RouteColors.parse(res.openRawResource(R.raw.route_colors).use { it.readBytes().toString(Charsets.UTF_8) })

    private val _gestures = MutableSharedFlow<MapGesture>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val gestures: SharedFlow<MapGesture> = _gestures.asSharedFlow()

    private var style: Style? = null

    /** The style's POI label layers ("pois", "pois_driving" and their copies per state, "pois@us-pa"). */
    private var poiLayers: Array<String> = emptyArray()
    private var scene: MapScene? = null

    // What the sources hold now (null = nothing drawn since the last style load).
    private val routeSources = RouteSources()
    private var drawnPins: List<MapPin>? = null
    private var drawnLights: List<GeoPoint>? = null
    /** The size each road-name layer had before guiding made it larger ([applyGuideLabels]); empty when none is changed. */
    private val roadLabelSizes = HashMap<String, Float>()
    /** The filter each driving-places layer had before guiding narrowed it ([applyGuideLabels]). */
    private val drivingFilters = HashMap<String, Expression>()
    /** That layer's filter as the style gives it back right after the narrowing was set: another text later means someone else wrote it. */
    private val drivingNarrowed = HashMap<String, String>()
    private var drawnSaved: List<SavedMarker>? = null
    private var drawnBelow: Boolean? = null
    private var drawnOverLabels: Boolean? = null
    /** The style's shield layers to move while guiding, and the layer they go back under ([SceneParts.guideShields]). */
    private var shields: Pair<List<String>, String?> = emptyList<String>() to null
    /** Whether [shields] are above the route ahead's copy now. */
    private var shieldsOver = false
    private var drawnTheme: MapTheme? = null
    private var drawnMarker: VehicleMarker? = null
    private var drawnMarkerSet = false

    // The vehicle marker as it is on screen now (it may be gliding), redrawn on each display frame
    // while it glides (Choreographer, so the developer option "animator duration scale" cannot
    // stop the glide while MapLibre's camera ease still runs).
    private val glide = Glide()
    private var vehicle: VehicleMarker? = null
    private var vehicleNight = false
    private var puckMovedAtMs: Long? = null
    private val choreographer = Choreographer.getInstance()
    private var framePosted = false
    private val onFrame = Choreographer.FrameCallback {
        framePosted = false
        if (!released) {
            val now = SystemClock.uptimeMillis()
            renderVehicle(now)
            if (glide.moving(now)) postFrame()
        }
    }
    private var released = false

    private val onClick = MapLibreMap.OnMapClickListener { ll -> onTap(ll); true }
    private val onLongClick = MapLibreMap.OnMapLongClickListener { ll ->
        _gestures.tryEmit(MapGesture.LongPress(GeoPoint(ll.latitude, ll.longitude)))
        true
    }
    private val onMoveStarted = MapLibreMap.OnCameraMoveStartedListener { reason ->
        if (reason == MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) _gestures.tryEmit(MapGesture.CameraMovedByUser)
    }

    init {
        map.addOnMapClickListener(onClick)
        map.addOnMapLongClickListener(onLongClick)
        map.addOnCameraMoveStartedListener(onMoveStarted)
    }

    // ---- style ------------------------------------------------------------------------------

    /**
     * A style was (re)loaded in [theme]. The last scene is drawn again at once, in [theme] (the
     * style's colours, the beam only on a night style) even if that scene was made for the other
     * theme: a style can load before the app's scene of its theme arrives. The next [show] decides
     * again.
     */
    fun onStyleLoaded(style: Style, theme: MapTheme) {
        if (released) return
        stopFrames()
        this.style = style
        removeOurs(style)
        style.addImages(HashMap(MarkerImages.markers(res)))
        style.addImages(HashMap(MarkerImages.pins(res)))
        style.addImage(Ids.LIGHT, MarkerImages.light(res))
        for (id in Ids.SOURCES) style.addSource(GeoJsonSource(id, FeatureCollection.fromFeatures(emptyList())))
        val layers = style.layers
        poiLayers = SceneParts.poiLayersIn(layers.map { it.id }).toTypedArray()
        val place = SceneParts.routePlacement(layers.map { StyleLayerInfo(it.id, it is SymbolLayer || it is CircleLayer, it.minZoom.toDouble()) })
        if (place.anchor == null || SceneParts.baseId(place.anchor) != Ids.ANCHOR_LAYER) {
            Log.w(TAG, "style has no roads_oneway layer; route lines go under its first label layer")
        }
        for (layer in belowLabelLayers { it }) {
            place.minZoom?.let { layer.minZoom = it.toFloat() }
            if (place.anchor != null) style.addLayerBelow(layer, place.anchor) else style.addLayer(layer)
        }
        if (place.overviewAnchor != null && place.minZoom != null) {
            for (layer in belowLabelLayers(Ids::overview)) {
                layer.maxZoom = place.minZoom.toFloat()
                style.addLayerBelow(layer, place.overviewAnchor)
            }
        }
        val guideAnchor = SceneParts.guideRouteAnchor(layers.map { it.id }, place.anchor)
        for (layer in guideRouteLayers()) if (guideAnchor != null) style.addLayerBelow(layer, guideAnchor) else style.addLayer(layer)
        shields = SceneParts.guideShields(layers.map { it.id }, place.anchor, guideAnchor)
        shieldsOver = false
        for (layer in onTopLayers()) style.addLayer(layer)
        routeSources.reset()
        drawnOverLabels = null
        roadLabelSizes.clear()
        drivingFilters.clear()
        drivingNarrowed.clear()
        drawnPins = null
        drawnLights = null
        drawnSaved = null
        drawnBelow = null
        drawnTheme = null
        drawnMarker = null
        drawnMarkerSet = false
        glide.clear()
        vehicle = null
        puckMovedAtMs = null
        bubbleDrawn = false
        scene?.let { draw(if (it.theme == theme) it else it.copy(theme = theme), glideAllowed = false) } ?: applyTheme(style, theme)
    }

    private fun removeOurs(style: Style) {
        for (id in Ids.ON_TOP.reversed() + Ids.GUIDE_ROUTE_ON_TOP.reversed() + Ids.OVERVIEW_BELOW_LABELS.reversed() + Ids.BELOW_LABELS.reversed()) style.removeLayer(id)
        for (id in Ids.SOURCES) style.removeSource(id)
    }

    private fun width(stops: List<Pair<Double, Double>>): Expression = Expression.interpolate(
        Expression.exponential(colors.widthBase),
        Expression.zoom(),
        *stops.map { (z, w) -> Expression.stop(z, w) }.toTypedArray(),
    )

    /** A route line layer; [part]: on the near source, the piece it draws ("driven" or "ahead"). */
    private fun line(id: String, source: String, casing: Boolean, part: String? = null): LineLayer {
        val layer = LineLayer(id, source).withProperties(
            PropertyFactory.lineCap(Property.LINE_CAP_ROUND),
            PropertyFactory.lineJoin(Property.LINE_JOIN_ROUND),
            PropertyFactory.lineWidth(width(if (casing) colors.casingWidths else colors.fillWidths)),
        )
        return if (part != null) layer.withFilter(eq(get("part"), literal(part))) else layer
    }

    private fun savedLayer(id: String): SymbolLayer = SymbolLayer(id, Ids.SRC_SAVED).withProperties(
        PropertyFactory.iconImage(get("icon")),
        PropertyFactory.iconSize(get("scale")),
        PropertyFactory.symbolSortKey(get("sort")),
        PropertyFactory.iconAllowOverlap(true),
        PropertyFactory.iconIgnorePlacement(true),
        PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
        PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
        PropertyFactory.visibility(Property.NONE),
    ).withFilter(Expression.gte(Expression.zoom(), get("min_zoom")))

    /** [Ids.BELOW_LABELS] in their order, each with the id [idOf] gives it (itself, or its overview copy). */
    private fun belowLabelLayers(idOf: (String) -> String): List<Layer> = Ids.BELOW_LABELS.map { id ->
        val own = idOf(id)
        when (id) {
            Ids.L_SAVED_UNDER -> savedLayer(own)
            Ids.L_ALT_CASING -> line(own, Ids.SRC_ALTERNATIVES, casing = true)
            Ids.L_ALT -> line(own, Ids.SRC_ALTERNATIVES, casing = false)
            Ids.L_TRAVELED_CASING -> line(own, Ids.SRC_TRAVELED, casing = true)
            Ids.L_TRAVELED_NEAR_CASING -> line(own, Ids.SRC_ROUTE_NEAR, casing = true, part = PART_DRIVEN)
            Ids.L_TRAVELED -> line(own, Ids.SRC_TRAVELED, casing = false)
            Ids.L_TRAVELED_NEAR -> line(own, Ids.SRC_ROUTE_NEAR, casing = false, part = PART_DRIVEN)
            Ids.L_ROUTE_CASING -> line(own, Ids.SRC_ROUTE, casing = true)
            Ids.L_ROUTE_NEAR_CASING -> line(own, Ids.SRC_ROUTE_NEAR, casing = true, part = PART_AHEAD)
            Ids.L_ROUTE -> line(own, Ids.SRC_ROUTE, casing = false)
            Ids.L_ROUTE_NEAR -> line(own, Ids.SRC_ROUTE_NEAR, casing = false, part = PART_AHEAD)
            else -> error("no layer")
        }
    }

    /** [Ids.GUIDE_ROUTE_ON_TOP]: the route ahead again, hidden until guiding (drawRouteOverLabels). */
    private fun guideRouteLayers(): List<Layer> = listOf(
        line(Ids.L_ROUTE_TOP_CASING, Ids.SRC_ROUTE, casing = true),
        line(Ids.L_ROUTE_NEAR_TOP_CASING, Ids.SRC_ROUTE_NEAR, casing = true, part = PART_AHEAD),
        line(Ids.L_ROUTE_TOP, Ids.SRC_ROUTE, casing = false),
        line(Ids.L_ROUTE_NEAR_TOP, Ids.SRC_ROUTE_NEAR, casing = false, part = PART_AHEAD),
    ).onEach { it.setProperties(PropertyFactory.visibility(Property.NONE)) }

    /** Shows the route ahead over the labels while guiding and under them otherwise; one set visible at a time. */
    private fun drawRouteOverLabels(style: Style, over: Boolean) {
        val top = PropertyFactory.visibility(if (over) Property.VISIBLE else Property.NONE)
        val under = PropertyFactory.visibility(if (over) Property.NONE else Property.VISIBLE)
        for (id in Ids.GUIDE_ROUTE_ON_TOP) style.getLayer(id)?.setProperties(top)
        for (id in Ids.ROUTE_AHEAD) {
            style.getLayer(id)?.setProperties(under)
            style.getLayer(Ids.overview(id))?.setProperties(under)
        }
        moveShields(style, over)
    }

    /**
     * Item 22: while guiding the style's general place names are hidden and its road names are larger
     * and upright, and of its driving places only fuel, parking and the like show ([Ids.GUIDE_HIDDEN],
     * [Ids.GUIDE_ROAD_LABELS], [Ids.GUIDE_DRIVING]); when guiding ends they are as the style has them.
     * A road-name layer whose size is not a plain number (an expression) is left alone.
     */
    private fun applyGuideLabels(style: Style, guiding: Boolean) {
        val ids = style.layers.map { it.id }
        val places = PropertyFactory.visibility(if (guiding) Property.NONE else Property.VISIBLE)
        for (id in SceneParts.copiesOf(ids, Ids.GUIDE_HIDDEN)) style.getLayer(id)?.setProperties(places)
        for (base in Ids.GUIDE_ROAD_LABELS) for (id in SceneParts.copiesOf(ids, base)) {
            val layer = style.getLayer(id) as? SymbolLayer ?: continue
            if (guiding) {
                val size = roadLabelSizes[id] ?: layer.textSize.takeIf { it.isValue }?.value ?: continue
                roadLabelSizes[id] = size
                layer.setProperties(
                    PropertyFactory.textSize(size * Ids.GUIDE_ROAD_LABEL_SCALE),
                    PropertyFactory.textPitchAlignment(Property.TEXT_PITCH_ALIGNMENT_VIEWPORT),
                )
            } else {
                roadLabelSizes.remove(id)?.let {
                    layer.setProperties(PropertyFactory.textSize(it), PropertyFactory.textPitchAlignment(Property.TEXT_PITCH_ALIGNMENT_AUTO))
                }
            }
        }
        for (id in SceneParts.copiesOf(ids, Ids.GUIDE_DRIVING)) {
            val layer = style.getLayer(id) as? SymbolLayer ?: continue
            if (guiding) {
                narrow(layer, drivingFilters[id] ?: layer.filter ?: continue)
            } else {
                val own = drivingFilters.remove(id)
                // put the style's filter back only when the layer still has ours: another writer's newer one stays
                if (own != null && drivingNarrowed.remove(id) == layer.filter?.toString()) layer.setFilter(own)
            }
        }
        if (!guiding) drivingNarrowed.clear()
    }

    /** [layer] shows only [Ids.GUIDE_KINDS] of what [own] (its filter without the narrowing) lets through. */
    private fun narrow(layer: SymbolLayer, own: Expression) {
        val kinds = Expression.any(*Ids.GUIDE_KINDS.map { eq(get("kind"), literal(it)) }.toTypedArray())
        drivingFilters[layer.id] = own
        layer.setFilter(all(own, kinds))
        drivingNarrowed[layer.id] = layer.filter?.toString().orEmpty()
    }

    /**
     * While guiding: the app's own-changes layer also writes these layers' filters (a place the user
     * just edited is hidden from the tiles). When a layer's filter is no longer the one set here, it is
     * the new filter to narrow, and to put back when guiding ends.
     */
    private fun keepGuidePlaces(style: Style) {
        for ((id, narrowed) in drivingNarrowed.toMap()) {
            val layer = style.getLayer(id) as? SymbolLayer ?: continue
            val now = layer.filter ?: continue
            if (now.toString() != narrowed) narrow(layer, now)
        }
    }

    /**
     * While guiding the style's shield layers go above the route ahead's copy, in their order; after
     * it back where they were (under the layer that followed them). A removed layer keeps its
     * properties and is added again as it was.
     */
    private fun moveShields(style: Style, over: Boolean) {
        if (over == shieldsOver || shields.first.isEmpty()) return
        var above = Ids.GUIDE_ROUTE_ON_TOP.last()
        for (id in shields.first) {
            val layer = style.getLayer(id) ?: continue
            style.removeLayer(layer)
            if (over) {
                style.addLayerAbove(layer, above)
                above = id
            } else {
                style.addLayerBelow(layer, shields.second ?: Ids.GUIDE_ROUTE_ON_TOP.first())
            }
        }
        shieldsOver = over
    }

    /** [image]: an expression, or a String for one fixed image (MapLibre refuses a bare literal expression there). */
    private fun marker(id: String, image: Any, filter: Expression, flat: Boolean, rotate: String?): SymbolLayer {
        val props = mutableListOf(
            if (image is Expression) PropertyFactory.iconImage(image) else PropertyFactory.iconImage(image as String),
            PropertyFactory.iconAllowOverlap(true),
            PropertyFactory.iconIgnorePlacement(true),
            PropertyFactory.iconPitchAlignment(if (flat) Property.ICON_PITCH_ALIGNMENT_MAP else Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
            PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_MAP),
        )
        if (rotate != null) props += PropertyFactory.iconRotate(get(rotate))
        return SymbolLayer(id, Ids.SRC_VEHICLE).withProperties(*props.toTypedArray()).withFilter(filter)
    }

    private fun onTopLayers(): List<Layer> {
        val isPuck = eq(get("kind"), literal("puck"))
        val isArrow = eq(get("kind"), literal("arrow"))
        val stale = eq(get("stale"), literal(true))
        val night = eq(get("night"), literal(true))
        val brakes = eq(get("brakes"), literal(true))
        fun img(piece: String) = literal(Ids.marker(piece))
        return listOf(
            // Rule G7: the browsing puck's uncertainty bubble, a soft disc with a blurred edge, under every marker.
            FillLayer(Ids.L_BUBBLE, Ids.SRC_BUBBLE).withProperties(
                PropertyFactory.fillColor(get("colour")),
                PropertyFactory.fillOpacity(switchCase(night, literal(BUBBLE_FILL_NIGHT), literal(BUBBLE_FILL_DAY))),
                PropertyFactory.fillAntialias(true),
            ),
            LineLayer(Ids.L_BUBBLE_EDGE, Ids.SRC_BUBBLE).withProperties(
                PropertyFactory.lineColor(get("colour")),
                PropertyFactory.lineOpacity(BUBBLE_EDGE_OPACITY),
                PropertyFactory.lineWidth(BUBBLE_EDGE_DP),
                PropertyFactory.lineBlur(BUBBLE_EDGE_BLUR_DP),
            ),
            savedLayer(Ids.L_SAVED_OVER),
            // Item 22: a traffic light standing at each light of the route ahead, upright, never hidden by a label.
            SymbolLayer(Ids.L_LIGHTS, Ids.SRC_LIGHTS).withProperties(
                PropertyFactory.iconImage(Ids.LIGHT),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
            ).apply { minZoom = Ids.LIGHTS_MIN_ZOOM },
            SymbolLayer(Ids.L_PINS, Ids.SRC_PINS).withProperties(
                PropertyFactory.iconImage(get("image")),
                PropertyFactory.iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                PropertyFactory.symbolSortKey(get("sort")),
                PropertyFactory.iconAllowOverlap(true),
                PropertyFactory.iconIgnorePlacement(true),
                PropertyFactory.iconPitchAlignment(Property.ICON_PITCH_ALIGNMENT_VIEWPORT),
                PropertyFactory.iconRotationAlignment(Property.ICON_ROTATION_ALIGNMENT_VIEWPORT),
            ),
            // Browsing: the cone turns with the compass and lies on the map; the dot stays round.
            marker(Ids.L_PUCK_CONE, switchCase(night, img("puck_cone_night"), img("puck_cone_day")),
                all(isPuck, has("cone"), not(stale)), flat = true, rotate = "cone"),
            marker(Ids.L_PUCK, switchCase(stale, img("puck_stale"), night, img("puck_night"), img("puck_day")),
                isPuck, flat = false, rotate = null),
            // Driving: beam (night map only), glow (stopped), arrow, lit lenses (stopped); all turn with the course and lie on the road.
            marker(Ids.L_BEAM, Ids.marker("arrow_beam"), all(isArrow, not(stale), night), flat = true, rotate = "bearing"),
            marker(Ids.L_GLOW, switchCase(night, img("arrow_brake_glow_night"), img("arrow_brake_glow_day")),
                all(isArrow, not(stale), brakes), flat = true, rotate = "bearing"),
            marker(Ids.L_ARROW, switchCase(stale, img("arrow_stale"), img("arrow")), isArrow, flat = true, rotate = "bearing"),
            marker(Ids.L_LIT, Ids.marker("arrow_brakes_lit"), all(isArrow, not(stale), brakes), flat = true, rotate = "bearing"),
        )
    }

    // ---- scene ------------------------------------------------------------------------------

    fun show(scene: MapScene) {
        if (released) return
        this.scene = scene
        draw(scene, glideAllowed = true)
    }

    private fun draw(scene: MapScene, glideAllowed: Boolean) {
        val style = style?.takeIf { it.isFullyLoaded } ?: return
        if (scene.theme != drawnTheme) {
            applyTheme(style, scene.theme)
            drawnTheme = scene.theme
            // the marker pieces depend on the theme (night beam, colours)
            drawnMarkerSet = false
        }
        drawRoute(style, routeSources.update(scene.route))
        if (scene.pins != drawnPins) {
            style.getSourceAs<GeoJsonSource>(Ids.SRC_PINS)?.setGeoJson(FeatureCollection.fromFeatures(scene.pins.map(::pinFeature)))
            drawnPins = scene.pins
        }
        if (scene.lights != drawnLights) {
            val points = scene.lights.map { Feature.fromGeometry(Point.fromLngLat(it.lon, it.lat)) }
            style.getSourceAs<GeoJsonSource>(Ids.SRC_LIGHTS)?.setGeoJson(FeatureCollection.fromFeatures(points))
            drawnLights = scene.lights
        }
        if (scene.saved != drawnSaved) {
            val features = SceneParts.savedInOrder(scene.saved).map(::savedFeature)
            style.getSourceAs<GeoJsonSource>(Ids.SRC_SAVED)?.setGeoJson(FeatureCollection.fromFeatures(features))
            drawnSaved = scene.saved
        }
        val over = SceneParts.routeOverLabels(scene)
        if (over != drawnOverLabels) {
            drawRouteOverLabels(style, over)
            applyGuideLabels(style, over)
            drawnOverLabels = over
        } else if (over) {
            keepGuidePlaces(style)
        }
        if (scene.savedBelowRoute != drawnBelow) {
            val under = PropertyFactory.visibility(if (scene.savedBelowRoute) Property.VISIBLE else Property.NONE)
            style.getLayer(Ids.L_SAVED_UNDER)?.setProperties(under)
            style.getLayer(Ids.overview(Ids.L_SAVED_UNDER))?.setProperties(under)
            style.getLayer(Ids.L_SAVED_OVER)?.setProperties(PropertyFactory.visibility(if (scene.savedBelowRoute) Property.NONE else Property.VISIBLE))
            drawnBelow = scene.savedBelowRoute
        }
        if (!drawnMarkerSet || scene.marker != drawnMarker) {
            drawVehicle(scene.marker, scene.theme == MapTheme.NIGHT, glideAllowed && drawnMarkerSet)
            drawnMarker = scene.marker
            drawnMarkerSet = true
        }
    }

    private fun applyTheme(style: Style, theme: MapTheme) {
        val t = colors.lines(theme)
        for (id in Ids.ROUTE_LINES) {
            val colour = when (id) {
                Ids.L_ALT_CASING -> t.alternative.casing
                Ids.L_ALT -> t.alternative.fill
                Ids.L_TRAVELED_CASING, Ids.L_TRAVELED_NEAR_CASING -> t.traveled.casing
                Ids.L_TRAVELED, Ids.L_TRAVELED_NEAR -> t.traveled.fill
                Ids.L_ROUTE_CASING, Ids.L_ROUTE_NEAR_CASING -> t.route.casing
                else -> t.route.fill
            }
            style.getLayer(id)?.setProperties(PropertyFactory.lineColor(colour))
            style.getLayer(Ids.overview(id))?.setProperties(PropertyFactory.lineColor(colour))
        }
        style.getLayer(Ids.L_ROUTE_TOP_CASING)?.setProperties(PropertyFactory.lineColor(t.route.casing))
        style.getLayer(Ids.L_ROUTE_NEAR_TOP_CASING)?.setProperties(PropertyFactory.lineColor(t.route.casing))
        style.getLayer(Ids.L_ROUTE_TOP)?.setProperties(PropertyFactory.lineColor(t.route.fill))
        style.getLayer(Ids.L_ROUTE_NEAR_TOP)?.setProperties(PropertyFactory.lineColor(t.route.fill))
    }

    /** Sends only the route sources whose content changed ([RouteSources]); one cursor step: the near pieces. */
    private fun drawRoute(style: Style, update: RouteSources.Update) {
        update.far?.let { (driven, ahead) ->
            style.getSourceAs<GeoJsonSource>(Ids.SRC_TRAVELED)?.setGeoJson(lines(listOf(driven)))
            style.getSourceAs<GeoJsonSource>(Ids.SRC_ROUTE)?.setGeoJson(lines(listOf(ahead)))
        }
        update.near?.let { (driven, ahead) ->
            val features = listOfNotNull(lineFeature(driven, PART_DRIVEN), lineFeature(ahead, PART_AHEAD))
            style.getSourceAs<GeoJsonSource>(Ids.SRC_ROUTE_NEAR)?.setGeoJson(FeatureCollection.fromFeatures(features))
        }
        update.alternatives?.let { alternatives ->
            style.getSourceAs<GeoJsonSource>(Ids.SRC_ALTERNATIVES)?.setGeoJson(lines(alternatives))
        }
    }

    private fun lineFeature(points: List<GeoPoint>, part: String? = null): Feature? {
        if (points.size < 2) return null
        return Feature.fromGeometry(LineString.fromLngLats(points.map { Point.fromLngLat(it.lon, it.lat) })).apply {
            if (part != null) addStringProperty("part", part)
        }
    }

    private fun lines(parts: List<List<GeoPoint>>): FeatureCollection = FeatureCollection.fromFeatures(parts.mapNotNull { lineFeature(it) })

    private fun pinFeature(pin: MapPin): Feature = Feature.fromGeometry(Point.fromLngLat(pin.point.lon, pin.point.lat)).apply {
        addStringProperty("id", pin.id)
        addStringProperty("image", SceneParts.pinImage(pin))
        addNumberProperty("sort", SceneParts.pinSortKey(pin))
    }

    private fun savedFeature(m: SavedMarker): Feature = Feature.fromGeometry(Point.fromLngLat(m.point.lon, m.point.lat)).apply {
        addStringProperty("id", m.savedId)
        addStringProperty("kind", m.kind.name)
        addStringProperty("icon", SceneParts.savedIcon(m.kind))
        addNumberProperty("sort", SceneParts.savedSortKey(m.kind))
        addNumberProperty("min_zoom", m.minZoom)
        addNumberProperty("scale", m.scale)
    }

    // ---- the vehicle and its glide ------------------------------------------------------------

    /**
     * A new vehicle marker. A new point glides from where the marker is on screen: the arrow over
     * its glideMs (rule K3), the browsing puck over the time since its last new point (at most
     * [Glide.MAX_PUCK_GLIDE_MS], the time CameraDirector eases a BROWSE_FOLLOW move over), so the
     * marker stays still on the screen while the camera follows. The same point with another look
     * (lenses, stale, night, cone) keeps the running glide. A change between puck and arrow, a
     * style load or [glideAllowed] false with a new point: drawn at once.
     */
    private fun drawVehicle(marker: VehicleMarker?, night: Boolean, glideAllowed: Boolean) {
        val now = SystemClock.uptimeMillis()
        val prev = vehicle
        vehicle = marker
        vehicleNight = night
        val sameKind = prev != null && marker != null && prev::class == marker::class
        when (marker) {
            null -> {
                glide.clear()
                puckMovedAtMs = null
            }
            is VehicleMarker.Puck -> if (!sameKind || marker.point != glide.target) {
                val since = puckMovedAtMs?.let { now - it }
                puckMovedAtMs = now
                val ms = if (glideAllowed && sameKind && since != null && since in 1..Glide.MAX_PUCK_GLIDE_MS) since else 0L
                glide.moveTo(marker.point, 0.0, ms, now)
            }
            is VehicleMarker.Arrow -> {
                puckMovedAtMs = null
                if (!sameKind || marker.point != glide.target) {
                    glide.moveTo(marker.point, marker.bearingDeg, if (glideAllowed && sameKind) marker.glideMs.toLong() else 0L, now)
                } else {
                    glide.turnTo(marker.bearingDeg)
                }
            }
        }
        renderVehicle(now)
        if (glide.moving(now)) postFrame() else stopFrames()
    }

    /** The vehicle source with the marker where its glide is at [nowMs]. */
    private fun renderVehicle(nowMs: Long) {
        val source = style?.takeIf { it.isFullyLoaded }?.getSourceAs<GeoJsonSource>(Ids.SRC_VEHICLE) ?: return
        val m = vehicle
        val at = glide.at(nowMs)
        val feature = when {
            m == null || at == null -> null
            m is VehicleMarker.Puck -> puckFeature(m, vehicleNight, at.first)
            m is VehicleMarker.Arrow -> arrowFeature(m, vehicleNight, at.first, at.second)
            else -> null
        }
        if (feature == null) source.setGeoJson(FeatureCollection.fromFeatures(emptyList())) else source.setGeoJson(feature)
        // The bubble moves with the puck's glide; none for the arrow (G7, K7).
        val bubble = style?.getSourceAs<GeoJsonSource>(Ids.SRC_BUBBLE) ?: return
        val radius = (m as? VehicleMarker.Puck)?.bubbleM
        if (radius == null || at == null) {
            if (bubbleDrawn) bubble.setGeoJson(FeatureCollection.fromFeatures(emptyList()))
            bubbleDrawn = false
        } else {
            bubble.setGeoJson(bubbleFeature(m, vehicleNight, at.first, radius))
            bubbleDrawn = true
        }
    }

    private var bubbleDrawn = false

    private fun bubbleFeature(p: VehicleMarker.Puck, night: Boolean, at: GeoPoint, radiusM: Double): Feature {
        val ring = SceneParts.bubbleRing(at, radiusM).map { Point.fromLngLat(it.lon, it.lat) }
        val fill = colors.lines(if (night) MapTheme.NIGHT else MapTheme.DAY).route.fill
        return Feature.fromGeometry(Polygon.fromLngLats(listOf(ring))).apply {
            addStringProperty("colour", SceneParts.bubbleColour(fill, p.stale))
            addBooleanProperty("night", night)
        }
    }

    private fun postFrame() {
        if (framePosted) return
        framePosted = true
        choreographer.postFrameCallback(onFrame)
    }

    private fun stopFrames() {
        if (!framePosted) return
        choreographer.removeFrameCallback(onFrame)
        framePosted = false
    }

    private fun arrowFeature(a: VehicleMarker.Arrow, night: Boolean, at: GeoPoint, bearing: Double): Feature =
        Feature.fromGeometry(Point.fromLngLat(at.lon, at.lat)).apply {
            addStringProperty("kind", "arrow")
            addBooleanProperty("stale", a.stale)
            addBooleanProperty("brakes", a.brakesLit && !a.stale)
            addBooleanProperty("night", night)
            addNumberProperty("bearing", bearing)
        }

    private fun puckFeature(p: VehicleMarker.Puck, night: Boolean, at: GeoPoint): Feature =
        Feature.fromGeometry(Point.fromLngLat(at.lon, at.lat)).apply {
            addStringProperty("kind", "puck")
            addBooleanProperty("stale", p.stale)
            addBooleanProperty("night", night)
            if (!p.stale) p.coneDeg?.let { addNumberProperty("cone", it) }
        }

    // ---- camera -----------------------------------------------------------------------------

    /**
     * [CameraTarget]: a centre (zoom null keeps the current zoom) or a box, with bearing, pitch and
     * padding (dp; start = left). animateMs > 0 eases linearly over that time, so a follow move and
     * the arrow's glide over the same time keep the arrow still on the screen; 0 jumps.
     */
    fun moveCamera(target: CameraTarget) {
        if (released) return
        val pad = target.padding
        val l = pad.start * density
        val t = pad.top * density
        val r = pad.end * density
        val b = pad.bottom * density
        val update = try {
            val fit = target.fit
            val centre = target.center
            when {
                // K1a: no centre and no zoom: a turn in place (the compass button), keeping the padding
                fit == null && centre == null && target.zoom == null -> CameraUpdateFactory.bearingTo(target.bearingDeg)
                fit != null -> CameraUpdateFactory.newLatLngBounds(
                    LatLngBounds.from(fit.north, fit.east, fit.south, fit.west),
                    target.bearingDeg, target.pitchDeg, l.toInt(), t.toInt(), r.toInt(), b.toInt(),
                )
                else -> {
                    val now = map.cameraPosition
                    val builder = CameraPosition.Builder()
                        .target(centre?.let { LatLng(it.lat, it.lon) } ?: now.target)
                        .zoom(target.zoom ?: now.zoom)
                        .bearing(target.bearingDeg)
                        .tilt(target.pitchDeg)
                        .padding(l.toDouble(), t.toDouble(), r.toDouble(), b.toDouble())
                    CameraUpdateFactory.newCameraPosition(builder.build())
                }
            }
        } catch (e: IllegalArgumentException) {
            // A box MapLibre cannot use (empty, crossing the antimeridian): the camera stays.
            Log.w(TAG, "camera not moved: target refused")
            return
        }
        if (target.animateMs > 0) map.easeCamera(update, target.animateMs, false) else map.moveCamera(update)
    }

    // ---- gestures ---------------------------------------------------------------------------

    private fun onTap(ll: LatLng) {
        val style = style?.takeIf { it.isFullyLoaded }
        val at = GeoPoint(ll.latitude, ll.longitude)
        if (style == null) {
            _gestures.tryEmit(MapGesture.Tap(at, null, null))
            return
        }
        val p = map.projection.toScreenLocation(ll)
        val slop = TAP_SLOP_DP * density
        val box = RectF(p.x - slop, p.y - slop, p.x + slop, p.y + slop)
        fun query(vararg ids: String) = ids.filter { style.getLayer(it) != null }.toTypedArray()
            .let { if (it.isEmpty()) emptyList() else map.queryRenderedFeatures(box, *it) }
        val pins = query(Ids.L_PINS).mapNotNull { f ->
            val id = f.getStringProperty("id") ?: return@mapNotNull null
            id to (if (f.hasProperty("sort")) f.getNumberProperty("sort").toInt() else 0)
        }
        val savedLayers = if (drawnBelow == true) arrayOf(Ids.L_SAVED_UNDER, Ids.overview(Ids.L_SAVED_UNDER)) else arrayOf(Ids.L_SAVED_OVER)
        val saved = query(*savedLayers).mapNotNull { f ->
            val id = f.getStringProperty("id") ?: return@mapNotNull null
            val kind = f.getStringProperty("kind")?.let { k -> SavedKind.entries.firstOrNull { it.name == k } } ?: SavedKind.OTHER
            id to kind
        }
        // Every named label under the finger with its distance from the finger: the nearest wins.
        val pois = query(*poiLayers).mapNotNull { f ->
            val poi = poiOf(f, at) ?: return@mapNotNull null
            val own = f.geometry() as? Point
            val d = if (own == null) Double.MAX_VALUE else map.projection.toScreenLocation(LatLng(own.latitude(), own.longitude()))
                .let { s -> hypot((s.x - p.x).toDouble(), (s.y - p.y).toDouble()) }
            poi to d
        }
        val (pinId, hitPoi) = SceneParts.tap(pins, saved, SceneParts.nearestPoi(pois))
        _gestures.tryEmit(MapGesture.Tap(at, pinId, hitPoi))
    }

    private fun poiOf(f: Feature, tap: GeoPoint): MapPoi? {
        val name = (if (f.hasProperty("name:en")) f.getStringProperty("name:en") else null)
            ?: (if (f.hasProperty("name")) f.getStringProperty("name") else null)
            ?: return null
        val kind = if (f.hasProperty("kind")) f.getStringProperty("kind") else ""
        val point = (f.geometry() as? Point)?.let { GeoPoint(it.latitude(), it.longitude()) } ?: tap
        return MapPoi(name = name, kind = kind, point = point)
    }

    fun release() {
        if (released) return
        released = true
        stopFrames()
        map.removeOnMapClickListener(onClick)
        map.removeOnMapLongClickListener(onLongClick)
        map.removeOnCameraMoveStartedListener(onMoveStarted)
        style = null
        scene = null
    }

    private companion object {
        const val TAG = "BeamlineLayers"

        /** Half the side of the box a tap looks in (a fingertip is about 7 mm, ~40 dp, across). */
        const val TAP_SLOP_DP = 12f

        // Rule G7: the bubble's look (the puck's colour, faint: the map stays readable under it).
        const val BUBBLE_FILL_DAY = 0.16f
        const val BUBBLE_FILL_NIGHT = 0.22f
        const val BUBBLE_EDGE_OPACITY = 0.5f
        const val BUBBLE_EDGE_DP = 1.5f
        const val BUBBLE_EDGE_BLUR_DP = 2f

        /** The "part" of a near piece (Ids.SRC_ROUTE_NEAR). */
        const val PART_DRIVEN = "driven"
        const val PART_AHEAD = "ahead"
    }
}
