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

    fun scene(trip: TripState, puck: PuckState?, theme: MapTheme, picked: Place?, saved: List<SavedPlace>, results: List<GeoPoint> = emptyList()): MapScene {
        val guidance = trip.guidance?.takeIf { trip.phase in ARROW_PHASES }
        val stopIds = trip.stops.mapNotNullTo(HashSet()) { it.place.savedId }
        return MapScene(
            theme = theme,
            route = routeLines(trip, guidance),
            pins = resultPins(results, picked) + pins(trip, picked),
            marker = marker(trip, guidance, puck),
            saved = saved.filter { it.id !in stopIds }.map(::savedMarker),
            savedBelowRoute = trip.phase == Phase.GUIDING || trip.phase == Phase.REROUTING,
            // item 22: the lights ahead, only while the route is driven (not on the arrival card's map)
            lights = if (trip.phase == Phase.GUIDING || trip.phase == Phase.REROUTING) guidance?.lightsAhead.orEmpty() else emptyList(),
        )
    }

    /**
     * Guided (GUIDING, REROUTING, ARRIVED): the route being driven with its traveled cursor, no
     * alternatives (in REROUTING the old route stays drawn, rule O5). Choosing (CHOOSING, and
     * PLANNING while the choices of the last plan are kept): the selected choice (or the first) and
     * the others in grey. Otherwise none.
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
     * then the PICKED pin of [picked]. Ids: "stop:<index into TripState.stops>" and "picked".
     */
    private fun pins(trip: TripState, picked: Place?): List<MapPin> {
        val last = trip.stops.lastIndex
        val stops = trip.stops.mapIndexed { i, stop ->
            if (i == last) {
                MapPin(id = STOP_PIN_PREFIX + i, point = stop.place.point, kind = PinKind.DESTINATION, number = null, reached = stop.reached)
            } else {
                MapPin(id = STOP_PIN_PREFIX + i, point = stop.place.point, kind = PinKind.STOP, number = i + 1, reached = stop.reached)
            }
        }
        return if (picked == null) stops else stops + MapPin(id = PICKED_PIN_ID, point = picked.point, kind = PinKind.PICKED)
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
        const val STOP_PIN_PREFIX = "stop:"
        const val PICKED_PIN_ID = "picked"
        const val RESULT_PIN_PREFIX = "result:"
    }
}
