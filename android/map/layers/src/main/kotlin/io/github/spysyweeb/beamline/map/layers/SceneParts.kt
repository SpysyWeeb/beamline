package io.github.spysyweeb.beamline.map.layers

import io.github.spysyweeb.beamline.api.map.AlternativeLine
import io.github.spysyweeb.beamline.api.map.MapPin
import io.github.spysyweeb.beamline.api.map.MapScene
import io.github.spysyweeb.beamline.api.map.MapPoi
import io.github.spysyweeb.beamline.api.map.PinKind
import io.github.spysyweeb.beamline.api.map.RouteLines
import io.github.spysyweeb.beamline.api.map.SavedMarker
import io.github.spysyweeb.beamline.api.map.VehicleMarker
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.SavedKind
import io.github.spysyweeb.beamline.api.nav.RouteCursor

/**
 * The parts of a scene that need no Android: how the route is split at the traveled cursor (and cut
 * into the pieces the sources hold, [RouteCut], [RouteSources]), where the route layers go in a
 * style ([routePlacement]), the saved markers' icons and order, the pins' images, the tap rule and
 * the glide. JVM-tested.
 */
internal object SceneParts {
    /** While guiding (the arrow, K7) the route ahead goes over the road labels ([Ids.GUIDE_ROUTE_ON_TOP]). */
    fun routeOverLabels(scene: MapScene): Boolean = scene.marker is VehicleMarker.Arrow

    /**
     * The uncertainty bubble (rule G7) as a closed ring of [n] points [radiusM] metres around
     * [center] (a sphere of 6371 km; first point due north, clockwise, the last equals the first).
     */
    fun bubbleRing(center: GeoPoint, radiusM: Double, n: Int = 72): List<GeoPoint> {
        val dLat = Math.toDegrees(radiusM / EARTH_M)
        val dLon = dLat / kotlin.math.cos(Math.toRadians(center.lat)).coerceAtLeast(0.01)
        val ring = (0 until n).map { i ->
            val a = 2 * Math.PI * i / n
            GeoPoint(center.lat + dLat * kotlin.math.cos(a), center.lon + dLon * kotlin.math.sin(a))
        }
        return ring + ring.first()
    }

    /** The bubble's colour: the puck's own (the route blue of [theme], candidate B), its grey when stale (assets/markers/SPEC.md). */
    fun bubbleColour(routeFill: String, stale: Boolean): String = if (stale) STALE_GREY else routeFill

    /** The stale puck's grey (assets/markers/SPEC.md `puck_stale`). */
    const val STALE_GREY = "#8D96A0"
    private const val EARTH_M = 6_371_000.0


    /**
     * The driven part and the part ahead of [line], split at [cursor] ("after geometry[index], at
     * point"). No cursor: nothing driven. Each part has at least two points or is empty.
     */
    fun split(line: List<GeoPoint>, cursor: RouteCursor?): Pair<List<GeoPoint>, List<GeoPoint>> {
        if (cursor == null || line.size < 2) return emptyList<GeoPoint>() to line
        val i = cursor.index.coerceIn(0, line.lastIndex)
        val driven = line.subList(0, i + 1) + cursor.point
        val ahead = listOf(cursor.point) + line.subList(i + 1, line.size)
        return (if (driven.size >= 2) driven else emptyList()) to (if (ahead.size >= 2) ahead else emptyList())
    }

    /** The sprite image of a saved marker (docs/architecture-saved-places.md SP40). */
    fun savedIcon(kind: SavedKind): String = when (kind) {
        SavedKind.HOME -> "saved_home"
        SavedKind.WORK -> "saved_work"
        SavedKind.OTHER -> "saved_place"
    }

    /** symbol-sort-key: others 0, Work 1, Home 2; a higher key is drawn above (SP42). */
    fun savedSortKey(kind: SavedKind): Int = when (kind) {
        SavedKind.OTHER -> 0
        SavedKind.WORK -> 1
        SavedKind.HOME -> 2
    }

    /** The image of a pin (drawn by [PinImages]): one per kind, number and reached state. */
    fun pinImage(pin: MapPin): String = when (pin.kind) {
        PinKind.DESTINATION -> if (pin.reached) Ids.PIN_DEST_REACHED else Ids.PIN_DEST
        PinKind.PICKED -> Ids.PIN_PICKED
        PinKind.RESULT -> Ids.PIN_RESULT
        PinKind.STOP -> Ids.pinStop(pin.number?.coerceIn(1, Ids.MAX_PIN_NUMBER), pin.reached)
    }

    /** Pins drawn later are on top: the picked place over the stops, the destination over the other stops. */
    fun pinSortKey(pin: MapPin): Int = when (pin.kind) {
        PinKind.RESULT -> -1   // search results (S11) under every other pin
        PinKind.STOP -> 0
        PinKind.DESTINATION -> 1
        PinKind.PICKED -> 2
    }

    /**
     * The tap rule (SP44): a pin wins (the one drawn on top: the highest [pinSortKey], so the
     * picked place over the destination over the stops), then a saved marker (the one on top: Home
     * over Work over the others), then a POI label. [pins] (id and sort key) and [saved] are what
     * lies under the finger; a pin or a saved marker reports no POI.
     */
    fun tap(pins: List<Pair<String, Int>>, saved: List<Pair<String, SavedKind>>, poi: MapPoi?): Pair<String?, MapPoi?> {
        pins.maxByOrNull { it.second }?.let { return it.first to null }
        saved.maxByOrNull { savedSortKey(it.second) }?.let { return SAVED_PREFIX + it.first to null }
        return null to poi
    }

    const val SAVED_PREFIX = "saved:"

    /**
     * Of the POI labels under the finger, each with its distance from the finger on the screen
     * (pixels), the nearest; the first of equals. MapLibre returns the features of a tap box in no
     * promised order, and downtown several labels lie in one box.
     */
    fun nearestPoi(hits: List<Pair<MapPoi, Double>>): MapPoi? = hits.minByOrNull { it.second }?.first

    /** A point [t] (0..1) of the way from [a] to [b] (straight in degrees: glides are a few metres). */
    fun lerp(a: GeoPoint, b: GeoPoint, t: Double): GeoPoint =
        GeoPoint(a.lat + (b.lat - a.lat) * t, a.lon + (b.lon - a.lon) * t)

    /** A bearing [t] of the way from [a] to [b], the shorter way round, 0..360. */
    fun lerpBearing(a: Double, b: Double, t: Double): Double {
        var d = (b - a) % 360.0
        if (d > 180.0) d -= 360.0
        if (d < -180.0) d += 360.0
        val r = (a + d * t) % 360.0
        return if (r < 0) r + 360.0 else r
    }

    /**
     * A style layer's id without the copy suffix StyleComposer adds ("roads_oneway@us-pa" is a copy
     * of "roads_oneway" for the pack us-pa; "@overview" for the overview map).
     */
    fun baseId(id: String): String = id.substringBefore('@')

    /**
     * Where [Ids.GUIDE_ROUTE_ON_TOP] goes: under the first state's POI layer after the route's
     * anchor, so the route ahead is above the road labels, shields and one-way arrows but below the
     * POI and place names; null (on top) in a style without one.
     */
    fun guideRouteAnchor(layerIds: List<String>, routeAnchor: String?): String? {
        val from = routeAnchor?.let { layerIds.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        return layerIds.drop(from).firstOrNull { baseId(it) == Ids.GUIDE_ROUTE_BELOW && !isOverview(it) }
    }

    private fun isOverview(id: String) = id.endsWith(OVERVIEW_COPY)

    /**
     * The driven road's shields while guiding (owner, 2026-11-18): the state copies of
     * [Ids.GUIDE_SHIELDS] that lie between the route's [routeAnchor] and [guideAnchor] (where
     * [Ids.GUIDE_ROUTE_ON_TOP] goes), in drawing order, and the style layer each goes back under when
     * guiding ends (the layer that followed the last of them; null: none follows, so they go back
     * under [Ids.GUIDE_ROUTE_ON_TOP] itself). While guiding they are moved above the route ahead's
     * copy, so the route number of the road being driven is not hidden under the line; the road
     * names stay under it. Empty when the style has no such layer.
     */
    fun guideShields(layerIds: List<String>, routeAnchor: String?, guideAnchor: String?): Pair<List<String>, String?> {
        val from = routeAnchor?.let { layerIds.indexOf(it) }?.takeIf { it >= 0 } ?: 0
        val to = guideAnchor?.let { layerIds.indexOf(it) }?.takeIf { it >= from } ?: layerIds.size
        val window = layerIds.subList(from, to)
        val shields = window.filter { baseId(it) == Ids.GUIDE_SHIELDS && !isOverview(it) }
        if (shields.isEmpty()) return emptyList<String>() to null
        val after = layerIds.indexOf(shields.last()) + 1
        return shields to layerIds.getOrNull(after)?.takeIf { after < to }
    }

    /** StyleComposer's suffix of the overview map's copies of the style layers. */
    const val OVERVIEW_COPY = "@overview"

    /**
     * Where the route layers go (route_colors.json layer_order: above roads and bridges, below
     * one-way arrows, shields and every label), from the style's layers in drawing order.
     *
     * - The first state copy of [Ids.ANCHOR_LAYER] ("roads_oneway@us-pa"; in a plain style the layer
     *   itself): every state road and bridge is below it, every state label above.
     * - With the overview map composed in as well (StyleComposer: all the overview's copies first,
     *   its labels only below the zoom where the state copies begin, map-compose.json), a second set
     *   of route layers goes under the overview's first label layer (or its copy of
     *   [Ids.ANCHOR_LAYER], whichever comes first) and is drawn only below that zoom, the first set
     *   only from it on. Otherwise the route would be drawn above the overview's labels and shields
     *   below that zoom (review finding of 2026-11-16).
     * - No state copy: under any copy of [Ids.ANCHOR_LAYER] (only the overview: it has none, as
     *   roads_oneway starts at z15), else under the first label layer, else on top (null).
     */
    fun routePlacement(layers: List<StyleLayerInfo>): RoutePlacement {
        val anchors = layers.filter { baseId(it.id) == Ids.ANCHOR_LAYER }
        val stateAnchor = anchors.firstOrNull { !isOverview(it.id) }?.id
        if (stateAnchor == null) {
            val firstLabel = layers.firstOrNull { it.label && !it.id.startsWith(Ids.PREFIX) }?.id
            return RoutePlacement(anchors.firstOrNull()?.id ?: firstLabel, null, null)
        }
        val overviewAnchor = layers.firstOrNull { isOverview(it.id) && (it.label || baseId(it.id) == Ids.ANCHOR_LAYER) }?.id
        val statesFrom = layers.filter { it.id.contains('@') && !isOverview(it.id) }.minOfOrNull { it.minZoom } ?: 0.0
        return if (overviewAnchor != null && statesFrom > 0.0) {
            RoutePlacement(stateAnchor, statesFrom, overviewAnchor)
        } else {
            RoutePlacement(stateAnchor, null, null)
        }
    }

    /** The copies of the style layer [base] ("pois" and "pois@us-pa", never "pois_driving"). */
    fun copiesOf(ids: List<String>, base: String): List<String> = ids.filter { baseId(it) == base }

    /** The style's POI label layers a tap looks at: every copy of [Ids.POI_LAYERS]. */
    fun poiLayersIn(ids: List<String>): List<String> = ids.filter { baseId(it) in Ids.POI_LAYERS }

    /** The markers in drawing order (lowest sort key first), so the source itself is in order too. */
    fun savedInOrder(markers: List<SavedMarker>): List<SavedMarker> = markers.sortedBy { savedSortKey(it.kind) }
}

/** A style layer as [SceneParts.routePlacement] sees it: its id, a label layer (symbol or circle), its minimum zoom. */
internal data class StyleLayerInfo(val id: String, val label: Boolean, val minZoom: Double)

/**
 * Where the route layers go: under [anchor] (null: on top), from zoom [minZoom] on (null: every
 * zoom); with the overview map composed in, a second set under [overviewAnchor], below [minZoom].
 */
internal data class RoutePlacement(val anchor: String?, val minZoom: Double?, val overviewAnchor: String?)

/**
 * The chosen line cut for drawing at the traveled cursor (MapController.show: "cheap when little
 * changed"; review finding of 2026-11-16: a cursor step used to send the whole line again). The
 * line is cut into chunks of [CHUNK] points. The far pieces (from the start to the cursor's chunk,
 * from the end of that chunk to the end) change only when the cursor enters another chunk; the two
 * near pieces (the chunk's start to the cursor, the cursor to the chunk's end) on every step.
 * driven = [RoutePieces.drivenFar] + [RoutePieces.drivenNear], ahead = [RoutePieces.aheadNear] +
 * [RoutePieces.aheadFar]: the same lines as [SceneParts.split], cut where two pieces touch. Each
 * piece has at least two points or is empty.
 */
internal data class RoutePieces(
    val drivenFar: List<GeoPoint>,
    val drivenNear: List<GeoPoint>,
    val aheadNear: List<GeoPoint>,
    val aheadFar: List<GeoPoint>,
)

internal object RouteCut {
    /** Points per chunk: a step sends at most this many points and two more. */
    const val CHUNK = 64

    /** The chunk the cursor is in; -1 without a cursor (nothing driven) or without a line. */
    fun chunkOf(line: List<GeoPoint>, cursor: RouteCursor?, chunk: Int = CHUNK): Int =
        if (cursor == null || line.size < 2) -1 else cursor.index.coerceIn(0, line.lastIndex) / chunk

    fun pieces(line: List<GeoPoint>, cursor: RouteCursor?, chunk: Int = CHUNK): RoutePieces {
        fun piece(p: List<GeoPoint>) = if (p.size >= 2) p else emptyList()
        if (line.size < 2) return RoutePieces(emptyList(), emptyList(), emptyList(), emptyList())
        if (cursor == null) return RoutePieces(emptyList(), emptyList(), emptyList(), line)
        val last = line.lastIndex
        val i = cursor.index.coerceIn(0, last)
        val s = (i / chunk) * chunk
        val e = minOf(s + chunk, last)
        return RoutePieces(
            drivenFar = piece(line.subList(0, s + 1)),
            drivenNear = piece(line.subList(s, i + 1) + cursor.point),
            aheadNear = piece(listOf(cursor.point) + line.subList(i + 1, e + 1)),
            aheadFar = if (e < last) line.subList(e, last + 1) else emptyList(),
        )
    }
}

/**
 * What the route sources hold, and what a new [RouteLines] changes: a source is sent again only
 * when its own content changed (the far pieces when the line or the cursor's chunk changes, the
 * near pieces when the cursor moves, the alternatives when they change).
 */
internal class RouteSources(private val chunk: Int = RouteCut.CHUNK) {
    /** What to send; null = the source keeps what it holds. Pairs are (driven, ahead). */
    data class Update(
        val far: Pair<List<GeoPoint>, List<GeoPoint>>?,
        val near: Pair<List<GeoPoint>, List<GeoPoint>>?,
        val alternatives: List<List<GeoPoint>>?,
    )

    private var sent = false
    private var chosenId: Long? = null
    private var line: List<GeoPoint> = emptyList()
    private var chunkIndex = -1
    private var cursor: RouteCursor? = null
    private var alternatives: List<AlternativeLine> = emptyList()

    fun update(route: RouteLines?): Update {
        val newLine = route?.chosen.orEmpty()
        val newCursor = route?.traveled
        val newChunk = RouteCut.chunkOf(newLine, newCursor, chunk)
        val newAlternatives = route?.alternatives.orEmpty()
        val farChanged = !sent || route?.chosenId != chosenId || newLine != line || newChunk != chunkIndex
        val nearChanged = farChanged || newCursor != cursor
        val alternativesChanged = !sent || newAlternatives != alternatives
        val pieces = if (nearChanged) RouteCut.pieces(newLine, newCursor, chunk) else null
        sent = true
        chosenId = route?.chosenId
        line = newLine
        chunkIndex = newChunk
        cursor = newCursor
        alternatives = newAlternatives
        return Update(
            far = pieces?.takeIf { farChanged }?.let { it.drivenFar to it.aheadFar },
            near = pieces?.let { it.drivenNear to it.aheadNear },
            alternatives = if (alternativesChanged) newAlternatives.map { it.points } else null,
        )
    }

    /** A new style has new, empty sources: the next [update] sends everything. */
    fun reset() {
        sent = false
    }
}

/**
 * Where the vehicle marker is while it moves to a new position: the arrow's glide over
 * VehicleMarker.Arrow.glideMs (rule K3) and the browsing puck's glide over the time between its
 * last two positions (the time CameraDirector eases a BROWSE_FOLLOW move over), so the marker stays
 * still on the screen while the camera follows. Android-free: the controller passes the time.
 *
 * A new point starts a glide from where the marker is at that moment (also in the middle of a
 * glide); the same point again (another look: lenses, stale, night, cone) keeps the running glide;
 * a duration of 0 jumps.
 */
internal class Glide {
    private var from: GeoPoint? = null
    private var to: GeoPoint? = null
    private var fromBearing = 0.0
    private var toBearing = 0.0
    private var startMs = 0L
    private var durationMs = 0L

    /** The point the marker is going to (or is at); null when nothing is drawn. */
    val target: GeoPoint? get() = to

    /** The point and bearing at [nowMs]; null when nothing is drawn. */
    fun at(nowMs: Long): Pair<GeoPoint, Double>? {
        val b = to ?: return null
        val a = from ?: return b to toBearing
        val t = if (durationMs <= 0L) 1.0 else ((nowMs - startMs).toDouble() / durationMs).coerceIn(0.0, 1.0)
        return SceneParts.lerp(a, b, t) to SceneParts.lerpBearing(fromBearing, toBearing, t)
    }

    /** True while the marker is still on its way at [nowMs]. */
    fun moving(nowMs: Long): Boolean = from != null && durationMs > 0L && nowMs - startMs < durationMs

    /** The time left of the glide at [nowMs] (0 when it has ended). */
    fun leftMs(nowMs: Long): Long = if (moving(nowMs)) startMs + durationMs - nowMs else 0L

    /** Goes to [point] with [bearing] over [durationMs] from where the marker is at [nowMs]; 0 jumps. */
    fun moveTo(point: GeoPoint, bearing: Double, durationMs: Long, nowMs: Long) {
        val now = at(nowMs)
        if (now == null || durationMs <= 0L || now.first == point) {
            jump(point, bearing)
            return
        }
        from = now.first
        fromBearing = now.second
        to = point
        toBearing = bearing
        startMs = nowMs
        this.durationMs = durationMs
    }

    /** The same point, another bearing: a running glide turns towards it and keeps its time. */
    fun turnTo(bearing: Double) {
        toBearing = bearing
    }

    fun jump(point: GeoPoint, bearing: Double) {
        from = null
        to = point
        toBearing = bearing
        durationMs = 0L
    }

    fun clear() {
        from = null
        to = null
        durationMs = 0L
    }

    companion object {
        /**
         * The browsing puck's longest glide: the same 3 s as :map:scene's VehicleGlide.MAX_GLIDE_MS
         * (the camera director's limit for a BROWSE_FOLLOW ease); a longer gap jumps.
         */
        const val MAX_PUCK_GLIDE_MS = 3_000L
    }
}

/** Source, layer and image ids of the app's layers (all start with "beamline-", the style's never do). */
internal object Ids {
    const val PREFIX = "beamline-"

    /** The part ahead, far from the cursor (RoutePieces.aheadFar; the whole line while choosing). */
    const val SRC_ROUTE = "beamline-route"

    /** The driven part, far from the cursor (RoutePieces.drivenFar). */
    const val SRC_TRAVELED = "beamline-route-traveled"

    /** The two pieces of the cursor's chunk, property "part": "driven" or "ahead" (sent on every cursor step). */
    const val SRC_ROUTE_NEAR = "beamline-route-near"
    const val SRC_ALTERNATIVES = "beamline-route-alternatives"
    const val SRC_PINS = "beamline-pins"
    const val SRC_SAVED = "beamline-saved"
    const val SRC_VEHICLE = "beamline-vehicle"

    /** The browsing puck's uncertainty bubble (rule G7): one polygon, or nothing. */
    const val SRC_BUBBLE = "beamline-puck-bubble"

    /** The traffic lights on the guided route ahead (MapScene.lights; docs/next-round.md item 22): points. */
    const val SRC_LIGHTS = "beamline-route-lights"

    val SOURCES = listOf(SRC_ROUTE, SRC_TRAVELED, SRC_ROUTE_NEAR, SRC_ALTERNATIVES, SRC_PINS, SRC_SAVED, SRC_VEHICLE, SRC_BUBBLE, SRC_LIGHTS)

    // Below the style's "roads_oneway", bottom to top. A far and a near layer of one kind touch
    // where their pieces meet; every casing of a kind is below every fill of it, so the joint is
    // not seen (opaque colours, round caps and joins).
    const val L_SAVED_UNDER = "beamline-saved-under"
    const val L_ALT_CASING = "beamline-route-alternatives-casing"
    const val L_ALT = "beamline-route-alternatives"
    const val L_TRAVELED_CASING = "beamline-route-traveled-casing"
    const val L_TRAVELED_NEAR_CASING = "beamline-route-traveled-near-casing"
    const val L_TRAVELED = "beamline-route-traveled"
    const val L_TRAVELED_NEAR = "beamline-route-traveled-near"
    const val L_ROUTE_CASING = "beamline-route-casing"
    const val L_ROUTE_NEAR_CASING = "beamline-route-near-casing"
    const val L_ROUTE = "beamline-route"
    const val L_ROUTE_NEAR = "beamline-route-near"

    /** The route's line layers, bottom to top. */
    val ROUTE_LINES = listOf(
        L_ALT_CASING, L_ALT,
        L_TRAVELED_CASING, L_TRAVELED_NEAR_CASING, L_TRAVELED, L_TRAVELED_NEAR,
        L_ROUTE_CASING, L_ROUTE_NEAR_CASING, L_ROUTE, L_ROUTE_NEAR,
    )

    /** The copy of one of [BELOW_LABELS] among the overview map's layers (RoutePlacement.overviewAnchor). */
    fun overview(id: String) = "$id-overview"

    /** The layer of [overview] copy [id] is a copy of (an id that is no copy: itself). */
    fun original(id: String) = id.removeSuffix("-overview")

    // Above every style layer, bottom to top
    /** The uncertainty bubble's soft fill and its blurred edge, under every marker (rule G7). */
    const val L_BUBBLE = "beamline-puck-bubble"
    const val L_BUBBLE_EDGE = "beamline-puck-bubble-edge"
    const val L_SAVED_OVER = "beamline-saved-over"
    /** The traffic lights of the route ahead (item 22): above the lines and the saved markers, below the pins and the vehicle. */
    const val L_LIGHTS = "beamline-route-lights"
    const val L_PINS = "beamline-pins"
    const val L_PUCK_CONE = "beamline-puck-cone"
    const val L_PUCK = "beamline-puck"
    const val L_BEAM = "beamline-arrow-beam"
    const val L_GLOW = "beamline-arrow-glow"
    const val L_ARROW = "beamline-arrow"
    const val L_LIT = "beamline-arrow-lit"

    /** The saved markers' "under" layer and the route layers, bottom to top, inserted before [ANCHOR_LAYER]. */
    val BELOW_LABELS = listOf(L_SAVED_UNDER) + ROUTE_LINES

    /** Their copies under the overview map's labels (only in a style composed with the overview and a state). */
    val OVERVIEW_BELOW_LABELS = BELOW_LABELS.map(::overview)

    // While guiding (the arrow is the vehicle marker) the chosen route ahead is drawn again above the
    // road labels (under the style's "pois", SceneParts.guideRouteAnchor), so the names of the road
    // being driven (the banner names it) do not sit on the line in the tilted view; its copies under
    // the labels are hidden meanwhile. The shields are moved above it then (GUIDE_SHIELDS).
    const val L_ROUTE_TOP_CASING = "beamline-route-guide-casing"
    const val L_ROUTE_NEAR_TOP_CASING = "beamline-route-guide-near-casing"
    const val L_ROUTE_TOP = "beamline-route-guide"
    const val L_ROUTE_NEAR_TOP = "beamline-route-guide-near"

    /** The route ahead over the labels while guiding, bottom to top: the copies of [ROUTE_AHEAD], in its order. */
    val GUIDE_ROUTE_ON_TOP = listOf(L_ROUTE_TOP_CASING, L_ROUTE_NEAR_TOP_CASING, L_ROUTE_TOP, L_ROUTE_NEAR_TOP)

    /** The style layer [GUIDE_ROUTE_ON_TOP] goes under (the state's first POI layer: POI and place names stay above it). */
    const val GUIDE_ROUTE_BELOW = "pois"

    /**
     * The style's route-number shields (US 19, I 90): while guiding their state copies are moved
     * above [GUIDE_ROUTE_ON_TOP] (SceneParts.guideShields), and back when guiding ends.
     */
    const val GUIDE_SHIELDS = "roads_shields"

    /** The chosen route ahead among [ROUTE_LINES] (hidden under the labels while [GUIDE_ROUTE_ON_TOP] shows). */
    val ROUTE_AHEAD = listOf(L_ROUTE_CASING, L_ROUTE_NEAR_CASING, L_ROUTE, L_ROUTE_NEAR)

    /** The marker layers, bottom to top, on top of everything. */
    val ON_TOP = listOf(L_BUBBLE, L_BUBBLE_EDGE, L_SAVED_OVER, L_LIGHTS, L_PINS, L_PUCK_CONE, L_PUCK, L_BEAM, L_GLOW, L_ARROW, L_LIT)

    /** The lights are drawn from this zoom on (not on the whole-route overview, where they would be a row of dots). */
    const val LIGHTS_MIN_ZOOM = 13.5f

    /**
     * While guiding (docs/next-round.md item 22, the lead's walkthrough of 0.1.13): the style's general
     * place names ([GUIDE_HIDDEN]: shops, restaurants, parks, ...) are hidden, so the tilted view
     * shows the road and what a driver looks for: of the style's "pois_driving" ([GUIDE_DRIVING]) only
     * the kinds of [GUIDE_KINDS] (fuel, charging, parking, rest areas, services, toll booths; not its
     * banks, hotels, clinics and car dealers; the owner, 2026-11-21: "only gas, parking and your
     * destination"). The road names ([GUIDE_ROAD_LABELS]) are [GUIDE_ROAD_LABEL_SCALE] times their size
     * and stand upright (in the tilted view a name lying on the road is half as tall). Back as they
     * were when guiding ends.
     */
    const val GUIDE_HIDDEN = "pois"
    const val GUIDE_DRIVING = "pois_driving"
    val GUIDE_KINDS = arrayOf("fuel", "charging_station", "parking", "parking_entrance", "rest_area", "services", "toll_booth")
    val GUIDE_ROAD_LABELS = arrayOf("roads_labels_minor", "roads_labels_major")
    const val GUIDE_ROAD_LABEL_SCALE = 1.3f

    /** route_colors.json layer_order: above roads and bridges, below one-way arrows, shields and labels. */
    const val ANCHOR_LAYER = "roads_oneway"

    /** The style's POI label layers a tap reports (docs/architecture.md 5.1 M). */
    val POI_LAYERS = arrayOf("pois", "pois_driving")

    // Images: marker pieces (assets/markers/SPEC.md) under "beamline-marker-<piece>"
    fun marker(piece: String) = "beamline-marker-$piece"

    /** The traffic light of [L_LIGHTS] (drawn by MarkerImages.light; approved by the owner on 2026-11-21). */
    const val LIGHT = "beamline-route-light"

    const val PIN_DEST = "beamline-pin-destination"
    const val PIN_DEST_REACHED = "beamline-pin-destination-reached"
    const val PIN_PICKED = "beamline-pin-picked"
    /** S11: a search result on the map, the destination pin's art at 80 % (the selected result is the picked pin). */
    const val PIN_RESULT = "beamline-pin-result"

    /** At most 9 stops (guidance.json planning.max_stops), so stop numbers 1-8; 9 is room to spare. */
    const val MAX_PIN_NUMBER = 9

    fun pinStop(number: Int?, reached: Boolean) = "beamline-pin-stop-${number ?: 0}" + if (reached) "-reached" else ""
}
