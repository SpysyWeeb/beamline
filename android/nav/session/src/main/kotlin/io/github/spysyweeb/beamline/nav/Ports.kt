// FILE: nav/session/src/main/kotlin/io/github/spysyweeb/beamline/nav/Ports.kt
package io.github.spysyweeb.beamline.nav

import io.github.spysyweeb.beamline.location.Fix
import io.github.spysyweeb.beamline.routing.engine.RoutingEngine
import io.github.spysyweeb.beamline.routing.model.LatLon
import io.github.spysyweeb.beamline.routing.model.Route
import java.io.File

/** Opens the routing engine on a tile set (ValhallaEngine.open on the phone, a recording player in tests). Closed when it is AutoCloseable. */
fun interface EngineOpener {
    fun open(engineConfigFile: File): RoutingEngine
}

/** A planned route as the follower needs it. */
class FollowerRoute(
    val route: Route,
    /** The engine's OSRM-format answer for the same path (ValhallaRequests.osrm of the chosen request). */
    val osrmJson: String,
    /** Which route of [osrmJson] is [route] (0 = main). */
    val osrmRouteIndex: Int,
)

/** Where one fix lies on the route. Indexes are over Route.maneuvers (all legs flattened). */
data class FollowerProgress(
    /** The maneuver whose stretch is being driven (0 = departure); the upcoming maneuver is stepIndex + 1. */
    val stepIndex: Int,
    val legIndex: Int,
    val snapped: LatLon,
    /** Index into Route.shape (flattened) of the segment holding [snapped]. */
    val shapeIndex: Int,
    val routeBearingDeg: Double?,
    /** Fix to [snapped], metres. */
    val lateralM: Double,
    val toManeuverM: Double,
    val remainingM: Double,
    val remainingS: Double,
    /** The follower has passed the last maneuver. */
    val complete: Boolean,
)

/**
 * Follows one route geometrically. Called on the session thread only. Contract (tested in
 * :nav:session with GeometricFollower, the app's follower, and test doubles): the step index advances
 * only by guidance.json `advance` (entry within entry_m of the maneuver, then exit_m past it, with
 * fixes no worse than fixes.decide_max_accuracy_m) or by [advance]; it never goes back.
 */
interface RouteFollower : AutoCloseable {
    fun update(fix: Fix): FollowerProgress
    /** Moves to the next step now (rule B2's forced advance; called once per step to catch up). */
    fun advance(): FollowerProgress
}

fun interface RouteFollowerFactory {
    fun create(route: FollowerRoute, rules: GuidanceRules): RouteFollower
}
