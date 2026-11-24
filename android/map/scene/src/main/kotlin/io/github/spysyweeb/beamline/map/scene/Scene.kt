// FILE: map/scene/src/main/kotlin/io/github/spysyweeb/beamline/map/scene/Scene.kt
package io.github.spysyweeb.beamline.map.scene

import io.github.spysyweeb.beamline.api.map.CameraState
import io.github.spysyweeb.beamline.api.map.CameraTarget
import io.github.spysyweeb.beamline.api.map.EdgeInsetsDp
import io.github.spysyweeb.beamline.api.map.MapGesture
import io.github.spysyweeb.beamline.api.map.MapScene
import io.github.spysyweeb.beamline.api.map.MapTheme
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.SavedPlace
import io.github.spysyweeb.beamline.api.nav.TripState
import io.github.spysyweeb.beamline.location.Clock
import io.github.spysyweeb.beamline.location.PuckState
import kotlinx.coroutines.flow.StateFlow

/**
 * Trip and puck to what the map draws. One per map surface: it remembers the previous vehicle
 * position, so the arrow's glideMs is the time between the last two positions (rule K3).
 * [savedRules]: camera.json "saved_places" (docs/architecture-saved-places.md, rules SP40-SP43).
 */
class MapSceneMapper(savedRules: SavedMarkerRules = SavedMarkerRules.bundled()) {
    /**
     * [picked]: the place whose sheet is open, or the point being chosen on the map (a PICKED pin).
     * [saved]: the saved places to draw (the app passes none when they are hidden); one that is a
     * stop of [trip] (a stop's place.savedId) is left out; MapScene.savedBelowRoute is true in
     * GUIDING and REROUTING (SP43). The arrow replaces the puck while a trip is guided.
     * [results]: the search results on the map (rule S11), RESULT pins "result:<index>"; the one at
     * the point of [picked] (the selected result, whose sheet is open) is left out: the PICKED pin
     * stands there instead.
     */
    fun scene(trip: TripState, puck: PuckState?, theme: MapTheme, picked: Place?, saved: List<SavedPlace> = emptyList(), results: List<GeoPoint> = emptyList()): MapScene =
        builder.scene(trip, puck, theme, picked, saved, results)

    private val builder = SceneBuilder(savedRules)
}

/** camera.json "saved_places" (the same file as CameraRules, copied by sync_data.py). Field = JSON key. */
data class SavedMarkerRules(
    val homeWorkMinZoom: Double,            // saved_places.home_work_min_zoom
    val otherMinZoom: Double,               // saved_places.other_min_zoom
    val iconScale: Double,                  // saved_places.icon_scale
) {
    companion object {
        fun parse(text: String): SavedMarkerRules = CameraFile.savedMarkerRules(text)
        fun bundled(): SavedMarkerRules = parse(CameraFile.bundledText())
    }
}

/** camera.json (source: packbuilder/routing/camera.json, copied by android/routing/scripts/sync_data.py). Field = JSON key. */
data class CameraRules(
    val guidePitchDeg: Double,              // guide.pitch_deg
    val guideVehicleFromBottom: Double,     // guide.vehicle_from_bottom (fraction of the free map height)
    val guideZoomBySpeed: List<Pair<Double, Double>>, // guide.zoom_by_speed_mps [[mps, zoom], ...]
    val approachWithinM: Double,            // guide.approach_within_m
    val approachZoom: Double,               // guide.approach_zoom
    val bearingSmoothing: Double,           // guide.bearing_smoothing
    val recenterAfterS: Double,             // guide.recenter_after_s
    val overviewReturnAfterS: Double,       // overview.return_after_s
    val browseFollowZoom: Double,           // browse.follow_zoom
    val previewPaddingDp: Float,            // preview.padding_dp
    val arrivedZoom: Double,                // arrived.zoom
    val transitionMs: Int,                  // transition_ms
    val bearingTurnDeg: Double = 0.0,       // guide.bearing_turn_deg (optional; 0 = plain smoothing)
    val lean: LeanRules? = null,            // guide.lean (optional; absent = the camera never leans into a turn)
) {
    companion object {
        fun parse(text: String): CameraRules = CameraFile.cameraRules(text)
        fun bundled(): CameraRules = parse(CameraFile.bundledText())
    }
}

/**
 * camera.json guide.lean (docs/next-round.md item 22): approaching a turn the driving camera turns
 * part of the way toward the road the route turns onto, zooms in and tilts up a little, then eases
 * back after the turn. Field = JSON key.
 */
data class LeanRules(
    val withinM: Double,                    // within_m: the lean starts this far before the turn,
    val withinS: Double,                    // within_s: or this many seconds before it at the current speed, whichever is farther
    val bearingShare: Double,               // bearing_share: the share of the turn's angle the map turns before the vehicle does
    val maxBearingDeg: Double,              // max_bearing_deg: and never more than this
    val zoomAdd: Double,                    // zoom_add: zoom levels added at the turn
    val pitchDeg: Double,                   // pitch_deg: the pitch at the turn (guide.pitch_deg elsewhere)
    val minTurnDeg: Double,                 // min_turn_deg: a smaller change of direction is no turn
    val lookM: Double,                      // look_m: the route's direction is read this far before and after the turn
    val holdMs: Long,                       // hold_ms: after the turn the map waits at most this long for the vehicle to turn as far
)

/** Camera modes and moves (rules K1-K7). UI-free: the phone map and later the car map use it the same way. */
class CameraDirector(rules: CameraRules, clock: Clock) {
    /** The move to make now; null = leave the camera where the user put it. */
    val target: StateFlow<CameraTarget?> get() = director.target
    val state: StateFlow<CameraState> get() = director.state
    fun onViewport(widthDp: Float, heightDp: Float, insets: EdgeInsetsDp): Unit = director.onViewport(widthDp, heightDp, insets)
    fun onTrip(trip: TripState): Unit = director.onTrip(trip)
    fun onPuck(puck: PuckState?): Unit = director.onPuck(puck)
    fun onGesture(gesture: MapGesture): Unit = director.onGesture(gesture)
    fun onMyLocation(): Unit = director.onMyLocation()
    fun onRecenter(): Unit = director.onRecenter()
    fun onOverview(): Unit = director.onOverview()
    /**
     * The search results on the map (rule S11), [points] nearest first: browsing, the camera fits
     * the nearest of them, as many as fit at map zoom [minZoom] or closer (the zoom from which the
     * installed states draw), never closer than browse.follow_zoom (K1). Cleared like a picked place.
     */
    fun onShowResults(points: List<GeoPoint>, minZoom: Double): Unit = director.onShowResults(points, minZoom)
    /** A place picked from a search, a list or a sheet: the camera goes to it (K1). */
    fun onShowPlace(point: GeoPoint): Unit = director.onShowPlace(point)
    /** The picked place's sheet closed or another place replaced it, or the results on the map closed: the camera stays where it is (K1, S11). */
    fun onPlaceLeft(): Unit = director.onPlaceLeft()
    fun tick(): Unit = director.tick()

    private val director = Director(rules, clock)
}
