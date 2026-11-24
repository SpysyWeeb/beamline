package io.github.spysyweeb.beamline.nav

import io.github.spysyweeb.beamline.location.Fix
import io.github.spysyweeb.beamline.routing.model.Geo
import io.github.spysyweeb.beamline.routing.model.LatLon
import io.github.spysyweeb.beamline.routing.model.Route

/** Makes [GeometricFollower]s: the app's route follower, also in the JVM tests and the golden trace (android/routing/testdata/nav). */
class GeometricFollowerFactory : RouteFollowerFactory {
    override fun create(route: FollowerRoute, rules: GuidanceRules): RouteFollower = GeometricFollower(route.route, rules)
}

/**
 * [RouteFollower] on plain geometry, with the contract of docs/architecture.md 2.4: the only follower
 * since 2026-11-17 (Ferrostar, which the phone used behind the same port, was dropped):
 *
 * - Snapping: the nearest point of the route line to the fix, searched from the start of the step
 *   being driven (or 300 m behind the anchor, whichever is later) to 2 km plus 60 m per second
 *   since the anchor ahead of it; the anchor is the last snapped point of a fix that lay within
 *   off_route.base_m (or accuracy_factor x its accuracy) of the line, so a wild fix far off the
 *   route does not drag the window ahead. The first fix searches the whole line. The step index
 *   never goes back, so neither does the search.
 * - Step advance (what Ferrostar called DistanceEntryAndSnappedExit): a deciding fix (accuracy at most
 *   fixes.decide_max_accuracy_m) within advance.entry_m of the upcoming maneuver's point, straight
 *   line, "enters" it; then a deciding fix snapped advance.exit_m or more past it along the route
 *   advances the step (the same fix may do both). [advance] moves one step at once.
 * - [FollowerProgress.toManeuverM] is negative once the snapped point is past the upcoming maneuver
 *   (the session's forced advance, rule B2, reads it); distances are along the shape; times scale the
 *   maneuvers' own time estimates by the part of their stretch still ahead.
 */
class GeometricFollower(route: Route, private val rules: GuidanceRules) : RouteFollower {
    val geometry = RouteGeometry(route)
    private var step = 0
    private var entered = false
    private var lastFixMs: Long? = null
    private var anchorAlong = 0.0
    private var anchorMs = 0L
    private var last: RouteGeometry.Projection = geometry.project(geometry.shape.first())
    private var lastRaw: LatLon = geometry.shape.first()

    override fun update(fix: Fix): FollowerProgress {
        val p = LatLon(fix.lat, fix.lon)
        val q = lastFixMs.let { t ->
            if (t == null) geometry.project(p)
            else {
                val gapS = ((fix.elapsedMs - anchorMs) / 1000.0).coerceAtLeast(0.0)
                val from = maxOf(anchorAlong - 300.0, geometry.maneuverAlong[step])
                geometry.project(p, from, anchorAlong + 2000.0 + 60.0 * gapS)
            }
        }
        // the search window follows fixes near the line only: a wild fix far off the route must not
        // drag it ahead (or the next good fix could not snap back behind it)
        val near = maxOf(rules.offRouteBaseM, rules.offRouteAccuracyFactor * (fix.accuracyM ?: 0f))
        if (lastFixMs == null || Geo.distanceM(p, q.point) <= near) {
            anchorAlong = q.alongM
            anchorMs = fix.elapsedMs
        }
        val deciding = fix.accuracyM?.let { it <= rules.decideMaxAccuracyM } == true
        val u = step + 1
        if (deciding && u < geometry.maneuverCount) {
            val maneuverPoint = geometry.shape[geometry.maneuverShapeIndex[u]]
            if (!entered && Geo.distanceM(p, maneuverPoint) <= rules.advanceEntryM) entered = true
            if (entered && q.alongM - geometry.maneuverAlong[u] >= rules.advanceExitM) {
                step = u
                entered = false
            }
        }
        last = q
        lastRaw = p
        lastFixMs = fix.elapsedMs
        return progress()
    }

    override fun advance(): FollowerProgress {
        if (step < geometry.maneuverCount - 1) {
            step++
            entered = false
        }
        return progress()
    }

    override fun close() {}

    private fun progress(): FollowerProgress {
        val u = step + 1
        val q = last
        return FollowerProgress(
            stepIndex = step,
            legIndex = geometry.maneuverLeg[step],
            snapped = q.point,
            shapeIndex = q.index,
            routeBearingDeg = geometry.bearingAt(q.alongM),
            lateralM = Geo.distanceM(lastRaw, q.point),
            toManeuverM = if (u < geometry.maneuverCount) geometry.maneuverAlong[u] - q.alongM else 0.0,
            remainingM = (geometry.lengthM - q.alongM).coerceAtLeast(0.0),
            remainingS = geometry.timeAfter(q.alongM),
            complete = step >= geometry.maneuverCount - 1,
        )
    }
}
