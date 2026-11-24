package io.github.spysyweeb.beamline.map.demo

import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.model.SavedKind
import io.github.spysyweeb.beamline.api.model.SavedPlace
import io.github.spysyweeb.beamline.api.nav.BannerView
import io.github.spysyweeb.beamline.api.nav.GpsState
import io.github.spysyweeb.beamline.api.nav.Guidance
import io.github.spysyweeb.beamline.api.nav.LiveBanner
import io.github.spysyweeb.beamline.api.nav.ManeuverKind
import io.github.spysyweeb.beamline.api.nav.Motion
import io.github.spysyweeb.beamline.api.nav.Origin
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.RouteChoice
import io.github.spysyweeb.beamline.api.nav.RouteCursor
import io.github.spysyweeb.beamline.api.nav.TripState
import io.github.spysyweeb.beamline.api.nav.TripStop
import io.github.spysyweeb.beamline.api.nav.Vehicle

/**
 * Hand-made trip states on the exit 24 route (no navigation session: the map track checks its
 * layers and camera with states it builds itself), and made-up saved places at public spots in
 * Erie (docs/architecture-saved-places.md 10.3: never anyone's real places).
 */
class DemoStates(private val route: DemoRoute) {
    val origin = route.points.first()
    val dest = route.points.last()
    val destination = Place("Downs Drive", dest, PlaceSource.SEARCH)

    /** A made-up stop on Peach Street, between the left and the right turn. */
    val stop = Place("Stop", route.points[(route.maneuvers[2].index + route.maneuvers[3].index) / 2], PlaceSource.SEARCH)

    /** A made-up place picked on the map (Erie Art Museum's public point, 10.3). */
    val picked = Place("Dropped pin", GeoPoint(42.13108, -80.08557), PlaceSource.MAP_PIN)

    val choice = RouteChoice(
        id = 1, typeIds = listOf("fastest", "simpler"), lengthM = route.lengthM, timeS = 272.8, turnCount = 3,
        hasTolls = false, hasHighways = true, via = listOf("I 90", "US 19"), geometry = route.points,
        legEnds = listOf(route.points.lastIndex), bounds = Bounds.around(route.points)!!, warnings = emptyList(),
    )

    /** A hand-drawn other way (grey while choosing): I-90 to the exit, then further east and back west. */
    private val altLine = route.points.subList(0, route.maneuvers[1].index + 1) + listOf(
        GeoPoint(42.0520, -80.0760), GeoPoint(42.0560, -80.0745), GeoPoint(42.0560, -80.0790), dest,
    )
    val alternative = RouteChoice(
        id = 2, typeIds = listOf("simpler"), lengthM = 5600.0, timeS = 300.0, turnCount = 4, hasTolls = false,
        hasHighways = true, via = listOf("I 90"), geometry = altLine, legEnds = listOf(altLine.lastIndex),
        bounds = Bounds.around(altLine)!!, warnings = emptyList(),
    )

    private val start = Origin.FromPlace(Place("Start", origin, PlaceSource.MAP_PIN), 57.0)

    fun choosing(withStop: Boolean = false, alternatives: Boolean = true) = TripState(
        phase = Phase.CHOOSING,
        origin = start,
        stops = if (withStop) listOf(TripStop(stop), TripStop(destination)) else listOf(TripStop(destination)),
        choices = if (alternatives) listOf(choice, alternative) else listOf(choice),
        selectedChoiceId = 1,
    )

    /**
     * Guiding at [m] metres along the route: the vehicle snapped there with the line's bearing, the
     * driven part up to it, the banner distance to the next maneuver.
     */
    fun guiding(
        m: Double,
        elapsedMs: Long,
        motion: Motion = Motion.MOVING,
        gps: GpsState = GpsState.GOOD,
        phase: Phase = Phase.GUIDING,
        withStop: Boolean = false,
        stops: List<TripStop>? = null,
    ): TripState {
        val at = route.at(m)
        val next = route.nextManeuver(m)
        val speed = if (motion == Motion.STOPPED) 0.0 else route.speedAt(m)
        val tripStops = stops ?: if (withStop) listOf(TripStop(stop), TripStop(destination)) else listOf(TripStop(destination))
        return TripState(
            phase = phase,
            origin = start,
            stops = tripStops,
            selectedChoiceId = 1,
            guidance = Guidance(
                route = choice,
                banner = LiveBanner(banner(next), distanceM = (route.maneuvers[next].alongM - m).coerceAtLeast(0.0), showLanes = false),
                vehicle = Vehicle(at.point, at.bearingDeg, snapped = true, motion = motion, speedMps = speed, elapsedMs = elapsedMs),
                traveled = RouteCursor(at.segment, at.point),
                legIndex = 0,
                nextStopIndex = tripStops.indexOfFirst { !it.reached }.takeIf { it >= 0 },
                toNextStopM = route.lengthM - m,
                toNextStopS = (route.lengthM - m) / 12.0,
                remainingM = route.lengthM - m,
                remainingS = (route.lengthM - m) / 12.0,
                offRouteSuspected = false,
                rerouteAttempts = 0,
                simulated = true,
            ),
            gps = gps,
        )
    }

    /**
     * The trip as the session gave it after one fix of N's golden trace: the vehicle at the trace's
     * (snapped) point with the route's bearing there, its motion, GPS state and phase, the driven
     * part up to it, the banner of the trace's upcoming maneuver at its shown distance. [fromSegment]:
     * the route piece of the previous row (the vehicle only moves forward). Returns the state and the
     * piece the vehicle is on.
     */
    fun traced(r: DemoTrace.Row, elapsedMs: Long, fromSegment: Int): Pair<TripState, Int> {
        val point = GeoPoint(r.lat, r.lon)
        val on = route.project(point, from = (fromSegment - 1).coerceAtLeast(0))
        val motion = Motion.valueOf(r.motion)
        val next = r.u.coerceIn(0, route.maneuvers.lastIndex)
        val state = TripState(
            phase = Phase.valueOf(r.phase),
            origin = start,
            stops = listOf(TripStop(destination)),
            selectedChoiceId = 1,
            guidance = Guidance(
                route = choice,
                banner = LiveBanner(banner(next), distanceM = r.shownM, showLanes = false),
                vehicle = Vehicle(
                    point = point,
                    bearingDeg = if (r.snapped) on.bearingDeg else r.fixBearingDeg,
                    snapped = r.snapped,
                    motion = motion,
                    speedMps = r.speedMps,
                    elapsedMs = elapsedMs,
                ),
                traveled = RouteCursor(on.segment, on.point),
                legIndex = 0,
                nextStopIndex = 0,
                toNextStopM = r.remainingM,
                toNextStopS = r.remainingM / 12.0,
                remainingM = r.remainingM,
                remainingS = r.remainingM / 12.0,
                offRouteSuspected = false,
                rerouteAttempts = 0,
                simulated = true,
            ),
            gps = GpsState.valueOf(r.gps),
        )
        return state to on.segment
    }

    // ---- a made-up long trip (the overview map's zooms, a line of thousands of points) ----------

    /**
     * Downtown Erie to Pittsburgh to Carlisle (public points), straight pieces with a point every
     * 100 m (about 4,300 points). Not a real road: it shows the route line among the overview map's
     * labels at low zoom and costs what a long Valhalla line costs.
     */
    val long: DemoRoute = DemoRoute(densify(listOf(ERIE, PITTSBURGH, CARLISLE), 100.0), emptyList())

    private val longChoice = RouteChoice(
        id = 3, typeIds = listOf("fastest"), lengthM = long.lengthM, timeS = long.lengthM / 30.0, turnCount = 1,
        hasTolls = false, hasHighways = true, via = listOf("Made-up line"), geometry = long.points,
        legEnds = listOf(long.points.lastIndex), bounds = Bounds.around(long.points)!!, warnings = emptyList(),
    )
    private val longDestination = Place("Carlisle", CARLISLE, PlaceSource.SEARCH)
    private val longStart = Origin.FromPlace(Place("Start", ERIE, PlaceSource.MAP_PIN), 180.0)

    fun longChoosing() = TripState(
        phase = Phase.CHOOSING,
        origin = longStart,
        stops = listOf(TripStop(longDestination)),
        choices = listOf(longChoice),
        selectedChoiceId = 3,
    )

    /** Guiding on the long trip at [m] metres, at 30 m/s. */
    fun longGuiding(m: Double, elapsedMs: Long): TripState {
        val at = long.at(m)
        val left = (long.lengthM - m).coerceAtLeast(0.0)
        return TripState(
            phase = Phase.GUIDING,
            origin = longStart,
            stops = listOf(TripStop(longDestination)),
            selectedChoiceId = 3,
            guidance = Guidance(
                route = longChoice,
                banner = LiveBanner(
                    BannerView(
                        maneuverIndex = 1, kind = ManeuverKind.ARRIVE, primary = "arrive", roads = listOf("Carlisle"), towards = emptyList(),
                        exitNumber = null, roundaboutExit = null, approachOnHighway = false, following = null, lanes = null,
                    ),
                    distanceM = left, showLanes = false,
                ),
                vehicle = Vehicle(at.point, at.bearingDeg, snapped = true, motion = Motion.MOVING, speedMps = 30.0, elapsedMs = elapsedMs),
                traveled = RouteCursor(at.segment, at.point),
                legIndex = 0,
                nextStopIndex = 0,
                toNextStopM = left,
                toNextStopS = left / 30.0,
                remainingM = left,
                remainingS = left / 30.0,
                offRouteSuspected = false,
                rerouteAttempts = 0,
                simulated = true,
            ),
            gps = GpsState.GOOD,
        )
    }

    private fun banner(i: Int): BannerView {
        val m = route.maneuvers[i]
        val kind = when {
            m.type == "arrive" -> ManeuverKind.ARRIVE
            m.type == "fork" -> ManeuverKind.EXIT_RIGHT
            m.modifier == "left" -> ManeuverKind.LEFT
            m.modifier == "right" -> ManeuverKind.RIGHT
            else -> ManeuverKind.STRAIGHT
        }
        return BannerView(
            maneuverIndex = i, kind = kind, primary = m.type, roads = listOf(m.name), towards = emptyList(),
            exitNumber = null, roundaboutExit = null, approachOnHighway = i == 1, following = null, lanes = null,
        )
    }

    // ---- made-up saved places (10.3) ---------------------------------------------------------

    val home = SavedPlace("s0000000000000a01", SavedKind.HOME, "Home", GeoPoint(42.13646, -80.08718), "Erie Maritime Museum")
    val work = SavedPlace("s0000000000000a02", SavedKind.WORK, "Work", GeoPoint(42.08418, -80.17321), "42.08418, -80.17321")
    val quokka = SavedPlace("s0000000000000a03", SavedKind.OTHER, "Quokka Shop", GeoPoint(42.06875, -80.09813), "Millcreek Mall")

    /** A made-up star on the exit 24 route (I-90, before the exit): over the line while choosing, under it while guiding. */
    val onRoute = SavedPlace("s0000000000000a04", SavedKind.OTHER, "Route Star", route.points[route.maneuvers[1].index - 6], "I 90")

    /** A made-up star at Home's very point: the house must be drawn on top. */
    val atHome = SavedPlace("s0000000000000a05", SavedKind.OTHER, "Twin Star", home.point, "Erie Maritime Museum")

    fun saved(which: String): List<SavedPlace> = when (which) {
        "three" -> listOf(home, work, quokka)
        "route" -> listOf(home, work, quokka, onRoute)
        "stack" -> listOf(atHome, home, work, quokka)
        else -> emptyList()
    }

    companion object {
        val ERIE = GeoPoint(42.1292, -80.0851)
        val PITTSBURGH = GeoPoint(40.4406, -79.9959)
        val CARLISLE = GeoPoint(40.2015, -77.1889)

        /** [corners] joined by straight pieces with a point at least every [stepM] metres. */
        fun densify(corners: List<GeoPoint>, stepM: Double): List<GeoPoint> {
            val out = mutableListOf(corners.first())
            for (i in 1 until corners.size) {
                val a = corners[i - 1]
                val b = corners[i]
                val n = kotlin.math.ceil(DemoRoute.metres(a, b) / stepM).toInt().coerceAtLeast(1)
                for (k in 1..n) out += GeoPoint(a.lat + (b.lat - a.lat) * k / n, a.lon + (b.lon - a.lon) * k / n)
            }
            return out
        }
    }
}
