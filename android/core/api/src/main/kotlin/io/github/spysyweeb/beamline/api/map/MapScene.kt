// FILE: core/api/src/main/kotlin/io/github/spysyweeb/beamline/api/map/MapScene.kt
package io.github.spysyweeb.beamline.api.map

import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.SavedKind
import io.github.spysyweeb.beamline.api.nav.RouteCursor
import kotlinx.coroutines.flow.SharedFlow

enum class MapTheme { DAY, NIGHT }

data class EdgeInsetsDp(val top: Float = 0f, val bottom: Float = 0f, val start: Float = 0f, val end: Float = 0f)

/** Everything the app draws on top of the map style. Built by :map:scene MapSceneMapper. */
data class MapScene(
    val theme: MapTheme,
    val route: RouteLines? = null,
    val pins: List<MapPin> = emptyList(),
    val marker: VehicleMarker? = null,
    /** The user's saved places (none when hidden): above the labels, below the pins and the vehicle; above the route lines unless [savedBelowRoute]. */
    val saved: List<SavedMarker> = emptyList(),
    /** True while guiding (GUIDING, REROUTING): [saved] is drawn below the route lines, so they never hide the line or the turn. */
    val savedBelowRoute: Boolean = false,
    /**
     * The traffic lights on the guided route ahead of the vehicle (Guidance.lightsAhead; docs/next-round.md
     * item 22): a small traffic-light image on the route line at each, above the line, below the pins
     * and the vehicle. Only for the route being driven, so the map stays calm.
     */
    val lights: List<GeoPoint> = emptyList(),
)

/**
 * One saved place on the map: the style sprite image saved_home, saved_work or saved_place by
 * [kind], [scale] times its sprite size, upright, shown from map zoom [minZoom] on. A tap on it is
 * reported as MapGesture.Tap with pinId = "saved:" + [savedId]. [toString] shows only [savedId] and
 * [kind], never the point (privacy rule PV2).
 */
data class SavedMarker(val savedId: String, val point: GeoPoint, val kind: SavedKind, val minZoom: Double, val scale: Double) {
    override fun toString(): String = "SavedMarker(savedId=$savedId, kind=$kind)"
}

data class RouteLines(
    val chosenId: Long,
    val chosen: List<GeoPoint>,
    /** Driven part of [chosen]; null before guidance. */
    val traveled: RouteCursor?,
    /** The other choices (grey), only while choosing. */
    val alternatives: List<AlternativeLine>,
)

data class AlternativeLine(val id: Long, val points: List<GeoPoint>)

/** RESULT: one of the search results on the map (rule S11), under every other pin; the selected one is the PICKED pin. */
enum class PinKind { DESTINATION, STOP, PICKED, RESULT }

data class MapPin(val id: String, val point: GeoPoint, val kind: PinKind, val number: Int? = null, val reached: Boolean = false)

sealed interface VehicleMarker {
    /**
     * Browsing: puck, compass cone (null = hidden), grey when stale; [bubbleM]: the radius in metres
     * of the soft uncertainty circle around it (rule G7), null = none. Never while guiding (K7).
     */
    data class Puck(val point: GeoPoint, val coneDeg: Double?, val stale: Boolean, val bubbleM: Double? = null) : VehicleMarker
    /**
     * Driving: arrow along [bearingDeg]; lenses lit only when [brakesLit]; the beam is drawn with
     * the night theme only. [glideMs]: how long the move from the previous position animates (the
     * time between the two positions, rule K3).
     */
    data class Arrow(val point: GeoPoint, val bearingDeg: Double, val brakesLit: Boolean, val stale: Boolean, val glideMs: Int) : VehicleMarker
}

enum class CameraMode { BROWSE, BROWSE_FOLLOW, PREVIEW, GUIDE_FOLLOW, GUIDE_FREE, GUIDE_OVERVIEW, ARRIVED }

/**
 * One camera move: [center] (with [zoom]) or [fit]. [ease]: the move speeds up and slows down (a
 * turn the user started, K1a since 0.1.33); otherwise it runs at one speed (following glides).
 */
data class CameraTarget(
    val center: GeoPoint? = null,
    val fit: Bounds? = null,
    val zoom: Double? = null,
    val bearingDeg: Double = 0.0,
    val pitchDeg: Double = 0.0,
    val padding: EdgeInsetsDp = EdgeInsetsDp(),
    val animateMs: Int = 0,
    val ease: Boolean = false,
)

data class CameraState(val mode: CameraMode = CameraMode.BROWSE, val showRecenter: Boolean = false)

/** A POI label tapped on the map (from the style's POI layers). */
data class MapPoi(val name: String, val kind: String, val point: GeoPoint)

sealed interface MapGesture {
    /** [pinId]: the MapPin.id, or "saved:" + SavedMarker.savedId, under the tap; [poi]: a POI label under it. */
    data class Tap(val point: GeoPoint, val pinId: String?, val poi: MapPoi?) : MapGesture
    data class LongPress(val point: GeoPoint) : MapGesture
    /** Pan, zoom, rotate or tilt by the user. */
    data object CameraMovedByUser : MapGesture
}

/** The map as the app commands it. Implemented on MapLibre by :map:layers (track M); Android-free. */
interface MapController {
    val gestures: SharedFlow<MapGesture>
    /** Replaces what is drawn; cheap when little changed (GeoJSON sources updated, layers kept). */
    fun show(scene: MapScene)
    fun moveCamera(target: CameraTarget)
}
