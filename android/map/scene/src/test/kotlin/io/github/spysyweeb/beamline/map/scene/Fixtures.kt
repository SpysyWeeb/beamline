package io.github.spysyweeb.beamline.map.scene

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
import io.github.spysyweeb.beamline.location.Clock
import io.github.spysyweeb.beamline.location.PuckLook
import io.github.spysyweeb.beamline.location.PuckState
import java.io.File

/**
 * Made-up trips on public points of the contract (docs/architecture.md 8: the exit 24 trip from
 * I-90 to Downs Drive, Erie) and made-up saved places at public spots in Erie
 * (docs/architecture-saved-places.md 10.3).
 */
object Fx {
    /** The exit 24 origin on I-90 and the Downs Drive destination (docs/architecture.md 8). */
    val ORIGIN = GeoPoint(42.028694, -80.128884)
    val DEST = GeoPoint(42.053308, -80.079579)

    /** A rough line from the origin to the destination (enough for the scene and the camera). */
    val ROUTE = listOf(
        ORIGIN,
        GeoPoint(42.0330, -80.1180),
        GeoPoint(42.0400, -80.1000),
        GeoPoint(42.0452, -80.0905),
        GeoPoint(42.0500, -80.0850),
        DEST,
    )

    /** Another way to the same place (grey while choosing). */
    val ALT = listOf(ORIGIN, GeoPoint(42.0250, -80.1000), GeoPoint(42.0350, -80.0800), DEST)

    val MUSEUM = GeoPoint(42.13646, -80.08718)
    val AIRPORT = GeoPoint(42.08418, -80.17321)
    val MALL = GeoPoint(42.06875, -80.09813)

    val home = SavedPlace("s0000000000000001", SavedKind.HOME, "Home", MUSEUM, "Erie Maritime Museum")
    val work = SavedPlace("s0000000000000002", SavedKind.WORK, "Work", AIRPORT, "42.08418, -80.17321")
    val star = SavedPlace("s0000000000000003", SavedKind.OTHER, "Quokka Shop", MALL, "Millcreek Mall")

    fun place(name: String, p: GeoPoint) = Place(name = name, point = p, source = PlaceSource.SEARCH)

    val downs = place("Downs Drive", DEST)

    fun choice(id: Long, line: List<GeoPoint>, types: List<String> = listOf("fastest")) = RouteChoice(
        id = id,
        typeIds = types,
        lengthM = 5341.6,
        timeS = 400.0,
        turnCount = 3,
        hasTolls = false,
        hasHighways = true,
        via = listOf("I 90"),
        geometry = line,
        legEnds = listOf(line.lastIndex),
        bounds = Bounds.around(line)!!,
        warnings = emptyList(),
    )

    fun choosing(
        selected: Long? = 1,
        stops: List<TripStop> = listOf(TripStop(downs)),
        phase: Phase = Phase.CHOOSING,
        origin: Origin = Origin.FromPlace(place("Start", ORIGIN), 57.0),
    ) = TripState(
        phase = phase,
        origin = origin,
        stops = stops,
        choices = listOf(choice(1, ROUTE), choice(2, ALT, listOf("simpler"))),
        selectedChoiceId = selected,
        gps = GpsState.OFF,
    )

    fun vehicle(
        p: GeoPoint,
        ms: Long,
        bearing: Double? = 57.0,
        motion: Motion = Motion.MOVING,
        speed: Double? = 25.0,
    ) = Vehicle(point = p, bearingDeg = bearing, snapped = true, motion = motion, speedMps = speed, elapsedMs = ms)

    fun banner(distanceM: Double) = LiveBanner(
        view = BannerView(
            maneuverIndex = 1, kind = ManeuverKind.EXIT_RIGHT, primary = "Exit 24", roads = listOf("US 19", "Peach St"),
            towards = listOf("Waterford"), exitNumber = "24", roundaboutExit = null, approachOnHighway = true,
            following = null, lanes = null,
        ),
        distanceM = distanceM,
        showLanes = false,
    )

    fun guiding(
        v: Vehicle?,
        phase: Phase = Phase.GUIDING,
        gps: GpsState = GpsState.GOOD,
        traveled: RouteCursor? = v?.let { RouteCursor(1, it.point) },
        stops: List<TripStop> = listOf(TripStop(downs)),
        bannerM: Double? = 2000.0,
        route: RouteChoice = choice(1, ROUTE),
    ) = TripState(
        phase = phase,
        origin = Origin.FromPlace(place("Start", ORIGIN), 57.0),
        stops = stops,
        selectedChoiceId = route.id,
        guidance = Guidance(
            route = route,
            banner = bannerM?.let(::banner),
            vehicle = v,
            traveled = traveled,
            legIndex = 0,
            nextStopIndex = stops.indexOfFirst { !it.reached }.takeIf { it >= 0 },
            toNextStopM = 3000.0,
            toNextStopS = 200.0,
            remainingM = 3000.0,
            remainingS = 200.0,
            offRouteSuspected = false,
            rerouteAttempts = 0,
            simulated = true,
        ),
        gps = gps,
    )

    fun puck(p: GeoPoint, cone: Float? = 30f, look: PuckLook = PuckLook.LIVE) =
        PuckState(lat = p.lat, lon = p.lon, look = look, coneDeg = cone, accuracyM = 5f)

    /** The rules file of the tree (the bundled copy). */
    fun cameraText(): String = File(repoRoot(), "packbuilder/routing/camera.json").readText(Charsets.UTF_8)

    fun repoRoot() = File(System.getProperty("beamline.repoRoot") ?: error("beamline.repoRoot not set"))
}

/** A virtual monotonic clock for the camera timers. */
class VirtualClock(var now: Long = 1_000_000L) : Clock {
    override fun elapsedMs(): Long = now
    fun advanceS(s: Double) {
        now += (s * 1000).toLong()
    }
}
