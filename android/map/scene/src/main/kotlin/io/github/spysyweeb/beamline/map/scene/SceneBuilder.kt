package io.github.spysyweeb.beamline.map.scene

import io.github.spysyweeb.beamline.api.map.AlternativeLine
import io.github.spysyweeb.beamline.api.map.MapPin
import io.github.spysyweeb.beamline.api.map.MapScene
import io.github.spysyweeb.beamline.api.map.MapTheme
import io.github.spysyweeb.beamline.api.map.PinKind
import io.github.spysyweeb.beamline.api.map.RouteLines
import io.github.spysyweeb.beamline.api.map.SavedMarker
import io.github.spysyweeb.beamline.api.map.VehicleMarker
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.SavedKind
import io.github.spysyweeb.beamline.api.model.SavedPlace
import io.github.spysyweeb.beamline.api.nav.GpsState
import io.github.spysyweeb.beamline.api.nav.Guidance
import io.github.spysyweeb.beamline.api.nav.Motion
import io.github.spysyweeb.beamline.api.nav.Origin
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.TripState
import io.github.spysyweeb.beamline.location.PuckLook
import io.github.spysyweeb.beamline.location.PuckState

/**
 * What [MapSceneMapper] does (docs/architecture.md 2.5, 3.6, 3.7; docs/architecture-saved-places.md
 * 2.8). Holds the one piece of memory a map surface needs: the previous vehicle position (for
 * glideMs) and the last known bearing (so an arrow whose course became unknown keeps pointing the
 * way it went). Not thread-safe: one caller per map, as the mapper's contract says.
 */
internal class SceneBuilder(private val savedRules: SavedMarkerRules) {
    private val glide = VehicleGlide()
    private var lastBearing: Double? = null
    /** The trip's own line under a stop preview, last cut: (the route's geometry, the cut's start, the cut line). */
    private var ownCut: Triple<List<GeoPoint>, Int, List<GeoPoint>>? = null

    fun scene(trip: TripState, puck: PuckState?, theme: MapTheme, picked: Place?, saved: List<SavedPlace>, results: List<GeoPoint> = emptyList(), step: GeoPoint? = null): MapScene {
        val guidance = trip.guidance?.takeIf { trip.phase in ARROW_PHASES }
        // P22: the chosen start while a route is chosen (guidance starts from the car: none then)
        val start = (trip.origin as? Origin.FromPlace)?.place?.takeIf { trip.phase in CHOOSING_PHASES }
        // a saved place that is a stop or the drawn start shows as its pin only (Home chosen as the start: one image, not two)
        val stopIds = trip.stops.mapNotNullTo(HashSet()) { it.place.savedId }.apply { start?.savedId?.let(::add) }
        return MapScene(
            theme = theme,
            route = stopPreviewLines(trip, guidance) ?: routeLines(trip, guidance),
            pins = resultPins(results, picked) + pins(trip, start, picked),
            marker = marker(trip, guidance, puck),
            saved = saved.filter { it.id !in stopIds }.map(::savedMarker),
            savedBelowRoute = trip.phase == Phase.GUIDING || trip.phase == Phase.REROUTING,
            // item 22: the lights ahead, only while the route is driven (not on the arrival card's map)
            lights = if (trip.phase == Phase.GUIDING || trip.phase == Phase.REROUTING) guidance?.lightsAhead.orEmpty() else emptyList(),
            // P21: the step the route sheet shows, while a route is chosen
            step = step?.takeIf { trip.phase in CHOOSING_PHASES },
        )
    }

    /**
     * Item 51: while a stop is being added (PLANNING to REROUTING) and [TripState.stopPreview] has its
     * route, that route is drawn as the chosen line and the trip's own route ahead under it in grey (an
     * [AlternativeLine] with tripRoute: guided, the route being driven from the start of the
     * [OWN_ROUTE_CHUNK] points the traveled cursor is in, the whole line without one; before guidance,
     * the selected choice). The other choices are not drawn meanwhile. Null otherwise (the lines of
     * [routeLines]).
     *
     * Review of 0.1.40: cut at the cursor itself, the grey line was new on every fix and the map sent
     * the whole remaining route again each second. Cut at the chunk's start, it changes only when the
     * car enters the next chunk (the same list again: :map:layers RouteSources keeps its source); the
     * few points behind the car are under the arrow or at the frame's edge, and the preview's blue line
     * covers the road ahead of the car.
     */
    private fun stopPreviewLines(trip: TripState, guidance: Guidance?): RouteLines? {
        if (trip.phase !in PREVIEW_PHASES) return null
        val preview = trip.stopPreview?.route ?: return null
        val own: Pair<Long, List<GeoPoint>>? = if (guidance != null) {
            guidance.route.id to ownLineAhead(guidance.route.geometry, guidance.traveled?.index)
        } else {
            (trip.choices.firstOrNull { it.id == trip.selectedChoiceId } ?: trip.choices.firstOrNull())?.let { it.id to it.geometry }
        }
        return RouteLines(
            chosenId = preview.id,
            chosen = preview.geometry,
            traveled = null,
            alternatives = listOfNotNull(own?.let { AlternativeLine(it.first, it.second, tripRoute = true) }),
        )
    }

    /**
     * [line] from the start of the chunk of [OWN_ROUTE_CHUNK] points [cursorIndex] is in (the whole line
     * without a cursor). The same line and chunk give the same list as the last call (no copy per fix).
     */
    private fun ownLineAhead(line: List<GeoPoint>, cursorIndex: Int?): List<GeoPoint> {
        if (cursorIndex == null || line.size < 2) return line
        val start = (cursorIndex.coerceIn(0, line.lastIndex) / OWN_ROUTE_CHUNK) * OWN_ROUTE_CHUNK
        if (start == 0) return line
        ownCut?.let { (from, at, cut) -> if (from === line && at == start) return cut }
        return line.drop(start).also { ownCut = Triple(line, start, it) }
    }

    /**
     * Guided (GUIDING, REROUTING, ARRIVED): the route being driven with its traveled cursor, no
     * alternatives (in REROUTING the old route stays drawn, rule O5). Choosing (CHOOSING, and
     * PLANNING while the choices of the last plan are kept): the selected choice (or the first) and
     * the others (AlternativeLine, grey; a tap on one chooses it, P23). Otherwise none.
     */
    private fun routeLines(trip: TripState, guidance: Guidance?): RouteLines? {
        if (guidance != null) {
            return RouteLines(guidance.route.id, guidance.route.geometry, guidance.traveled, emptyList())
        }
        if (trip.phase != Phase.CHOOSING && trip.phase != Phase.PLANNING) return null
        val chosen = trip.choices.firstOrNull { it.id == trip.selectedChoiceId } ?: trip.choices.firstOrNull() ?: return null
        return RouteLines(
            chosenId = chosen.id,
            chosen = chosen.geometry,
            traveled = null,
            alternatives = trip.choices.filter { it.id != chosen.id }.map { AlternativeLine(it.id, it.geometry) },
        )
    }

    /**
     * Every stop of the trip (decision 6.22): the last is the DESTINATION, the others STOP pins
     * numbered from 1 in driving order (reached ones keep their number and are marked reached);
     * then the PICKED pin of [picked]; first the START pin "start" of a chosen [start] while
     * choosing (P22). Ids: "start", "stop:<index into TripState.stops>" and "picked".
     */
    private fun pins(trip: TripState, start: Place?, picked: Place?): List<MapPin> {
        val last = trip.stops.lastIndex
        val stops = trip.stops.mapIndexed { i, stop ->
            if (i == last) {
                MapPin(id = STOP_PIN_PREFIX + i, point = stop.place.point, kind = PinKind.DESTINATION, number = null, reached = stop.reached)
            } else {
                MapPin(id = STOP_PIN_PREFIX + i, point = stop.place.point, kind = PinKind.STOP, number = i + 1, reached = stop.reached)
            }
        }
        // P22: first the chosen start, under the stops' and the destination's pins
        return listOfNotNull(start?.let { MapPin(id = START_PIN_ID, point = it.point, kind = PinKind.START) }) + stops +
            listOfNotNull(picked?.let { MapPin(id = PICKED_PIN_ID, point = it.point, kind = PinKind.PICKED) })
    }

    /** Rule S11: a RESULT pin per result, drawn first (under the others); the selected one is the PICKED pin. */
    private fun resultPins(results: List<GeoPoint>, picked: Place?): List<MapPin> =
        results.mapIndexedNotNull { i, p ->
            if (picked != null && picked.point == p) null else MapPin(id = RESULT_PIN_PREFIX + i, point = p, kind = PinKind.RESULT)
        }

    /**
     * Rule K7: the arrow from Start to the end of ARRIVED, the browsing puck otherwise. While a
     * guided trip has no vehicle yet (before its first progress) the puck stands in. Rule M3:
     * lenses lit only when stopped and not stale; stale = GPS LOST (rule G4).
     */
    private fun marker(trip: TripState, guidance: Guidance?, puck: PuckState?): VehicleMarker? {
        val v = guidance?.vehicle
        if (v == null) {
            glide.reset()
            if (guidance == null) lastBearing = null
            return puckMarker(puck)
        }
        val glideMs = glide.update(v)
        val bearing = v.bearingDeg?.let(::normalizeDeg) ?: lastBearing ?: startBearing(guidance.route.geometry) ?: 0.0
        lastBearing = bearing
        val stale = trip.gps == GpsState.LOST
        return VehicleMarker.Arrow(
            point = v.point,
            bearingDeg = bearing,
            brakesLit = v.motion == Motion.STOPPED && !stale,
            stale = stale,
            glideMs = glideMs,
        )
    }

    /** Grey when the puck is GREY; a stale puck has no cone (assets/markers/SPEC.md, decision 6.21); the bubble as the puck has it (G7). */
    private fun puckMarker(puck: PuckState?): VehicleMarker.Puck? {
        puck ?: return null
        val stale = puck.look == PuckLook.GREY
        return VehicleMarker.Puck(
            point = GeoPoint(puck.lat, puck.lon),
            coneDeg = if (stale) null else puck.coneDeg?.toDouble(),
            stale = stale,
            bubbleM = puck.bubbleM?.toDouble(),
        )
    }

    /** Rule SP41: Home and Work from home_work_min_zoom, the others from other_min_zoom, all at icon_scale. */
    private fun savedMarker(p: SavedPlace) = SavedMarker(
        savedId = p.id,
        point = p.point,
        kind = p.kind,
        minZoom = if (p.kind == SavedKind.OTHER) savedRules.otherMinZoom else savedRules.homeWorkMinZoom,
        scale = savedRules.iconScale,
    )

    companion object {
        /** The phases in which the arrow replaces the puck (rule K7) and the guided route is drawn. */
        val ARROW_PHASES = setOf(Phase.GUIDING, Phase.REROUTING, Phase.ARRIVED)
        /** While a route is chosen: the step mark (P21) and the start pin (P22). */
        val CHOOSING_PHASES = setOf(Phase.PLANNING, Phase.CHOOSING)
        /** Item 51: a stop's preview route is drawn from route choosing to guiding (not on the arrival map). */
        val PREVIEW_PHASES = setOf(Phase.PLANNING, Phase.CHOOSING, Phase.GUIDING, Phase.REROUTING)
        /** Points per chunk of the trip's own line under a stop preview (as :map:layers RouteCut.CHUNK). */
        const val OWN_ROUTE_CHUNK = 64
        const val START_PIN_ID = "start"
        const val STOP_PIN_PREFIX = "stop:"
        const val PICKED_PIN_ID = "picked"
        const val RESULT_PIN_PREFIX = "result:"
    }
}
