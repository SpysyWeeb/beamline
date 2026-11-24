package io.github.spysyweeb.beamline.feature.drive

import io.github.spysyweeb.beamline.api.map.CameraMode
import io.github.spysyweeb.beamline.api.map.CameraState
import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.nav.BannerView
import io.github.spysyweeb.beamline.api.nav.FollowingView
import io.github.spysyweeb.beamline.api.nav.GpsState
import io.github.spysyweeb.beamline.api.nav.Guidance
import io.github.spysyweeb.beamline.api.nav.LaneArrow
import io.github.spysyweeb.beamline.api.nav.LaneMark
import io.github.spysyweeb.beamline.api.nav.LaneView
import io.github.spysyweeb.beamline.api.nav.LiveBanner
import io.github.spysyweeb.beamline.api.nav.ManeuverKind
import io.github.spysyweeb.beamline.api.nav.Origin
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.RouteChoice
import io.github.spysyweeb.beamline.api.nav.RouteOptions
import io.github.spysyweeb.beamline.api.nav.RouteTypeInfo
import io.github.spysyweeb.beamline.api.nav.TripEvent
import io.github.spysyweeb.beamline.api.nav.TripProblem
import io.github.spysyweeb.beamline.api.nav.TripState
import io.github.spysyweeb.beamline.api.nav.TripStop
import io.github.spysyweeb.beamline.units.UnitFormatter
import io.github.spysyweeb.beamline.units.UnitRules
import io.github.spysyweeb.beamline.units.UnitSystem
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The owner's exit 24 trip (docs/architecture.md section 8; report 09's figures) as the session
 * would hand it to the drive feature, and the three frames of docs/design/turn-banner.html. Road
 * names as N's banner gives them after banner.json road_abbreviations; the left turn keeps the
 * engine's third name (android/routing/banner BannerModelTest: "US 19", "Peach Street",
 * "General Strong Vincent Memorial Highway").
 */
internal object Exit24 {
    val newYork: ZoneId = ZoneId.of("America/New_York")
    private val rules = UnitRules.bundled()
    val miles = UnitFormatter(rules, UnitSystem.IMPERIAL)
    val miles24 = UnitFormatter(rules, UnitSystem.IMPERIAL, clock24h = true)
    val km = UnitFormatter(rules, UnitSystem.METRIC)

    /** 2026-11-15 15:30:00 in Erie. */
    val now: Long = ZonedDateTime.of(2026, 11, 15, 15, 30, 0, 0, newYork).toInstant().toEpochMilli()

    val start = GeoPoint(42.028694, -80.128884)
    val end = GeoPoint(42.053308, -80.079579)
    val downsDrive = Place("Downs Drive", end, PlaceSource.SEARCH, subtitle = "Erie, Pennsylvania")
    /** A made-up stop near exit 24 (the "stop" variant of section 8 adds one there). */
    val sheetz = Place("Sheetz", GeoPoint(42.0402, -80.0951), PlaceSource.SEARCH, subtitle = "Peach Street, Erie")

    /** presets.json route types offered (Shortest left in batch D). */
    val offered = listOf(RouteTypeInfo("fastest", "Fastest"), RouteTypeInfo("simpler", "Simpler"))

    fun choice(
        id: Long,
        typeIds: List<String>,
        lengthM: Double = 5341.6,
        timeS: Double = 370.0,
        hasTolls: Boolean = false,
        hasHighways: Boolean = true,
        via: List<String> = listOf("I 90", "US 19"),
        warnings: List<String> = emptyList(),
    ) = RouteChoice(
        id = id, typeIds = typeIds, lengthM = lengthM, timeS = timeS, turnCount = 4, hasTolls = hasTolls,
        hasHighways = hasHighways, via = via, geometry = listOf(start, end), legEnds = listOf(1),
        bounds = Bounds.around(listOf(start, end))!!, warnings = warnings,
    )

    val fastest = choice(1, listOf("fastest", "simpler"))
    /** Fastest alone, beside a Simpler card of its own (a two-card sheet). */
    val fastestOnly = choice(1, listOf("fastest"))

    // ---- the three maneuvers and the arrival (banners built once per route by N) ----

    val exitView = BannerView(
        maneuverIndex = 1, kind = ManeuverKind.FORK_RIGHT, primary = "Exit 24", roads = listOf("US 19", "Peach St"),
        towards = listOf("Waterford"), exitNumber = "24", roundaboutExit = null, approachOnHighway = true,
        following = FollowingView(ManeuverKind.LEFT, "Turn left", distanceM = 421.6, timeS = 33.0), lanes = null,
    )
    val leftView = BannerView(
        maneuverIndex = 2, kind = ManeuverKind.LEFT, primary = "Turn left",
        roads = listOf("US 19", "Peach St", "General Strong Vincent Memorial Hwy"), towards = emptyList(),
        exitNumber = null, roundaboutExit = null, approachOnHighway = false,
        following = FollowingView(ManeuverKind.RIGHT, "Turn right", distanceM = 356.0, timeS = 41.0),
        lanes = listOf(
            LaneView(setOf(LaneArrow.LEFT), LaneMark.ALLOWED, LaneArrow.LEFT),
            LaneView(setOf(LaneArrow.LEFT, LaneArrow.THROUGH), LaneMark.BEST, LaneArrow.LEFT),
            LaneView(setOf(LaneArrow.RIGHT), LaneMark.NOT_FOR_ROUTE, null),
        ),
    )
    val rightView = BannerView(
        maneuverIndex = 3, kind = ManeuverKind.RIGHT, primary = "Turn right", roads = listOf("Downs Dr"),
        towards = emptyList(), exitNumber = null, roundaboutExit = null, approachOnHighway = false, following = null,
        lanes = listOf(
            LaneView(setOf(LaneArrow.LEFT), LaneMark.NOT_FOR_ROUTE, null),
            LaneView(setOf(LaneArrow.THROUGH), LaneMark.NOT_FOR_ROUTE, null),
            LaneView(setOf(LaneArrow.THROUGH), LaneMark.NOT_FOR_ROUTE, null),
            LaneView(setOf(LaneArrow.THROUGH, LaneArrow.RIGHT), LaneMark.BEST, LaneArrow.RIGHT),
        ),
    )
    val arriveView = BannerView(
        maneuverIndex = 4, kind = ManeuverKind.ARRIVE_RIGHT, primary = "Arrive, on the right", roads = emptyList(),
        towards = emptyList(), exitNumber = null, roundaboutExit = null, approachOnHighway = false, following = null, lanes = null,
    )

    /** Sketch frame 1: on I-90, 0.5 mi before the exit; no lane data there. */
    val frame1 = LiveBanner(exitView, distanceM = 804.672, showLanes = false)
    /** Sketch frame 2: on the ramp, 400 ft before the left turn. */
    val frame2 = LiveBanner(leftView, distanceM = 121.92, showLanes = true)
    /** Sketch frame 3: on Peach St, 0.2 mi before Downs Dr. */
    val frame3 = LiveBanner(rightView, distanceM = 321.8688, showLanes = true)

    /**
     * Not on the trip: frame 3 with eight lanes, the best on the far right (a made-up wide junction;
     * eight boxes of the sketch's size do not fit a 360 dp phone, seven do not fit the 320 dp side panel).
     */
    val eightLanes = LiveBanner(
        rightView.copy(
            lanes = listOf(
                LaneView(setOf(LaneArrow.LEFT), LaneMark.NOT_FOR_ROUTE, null),
                LaneView(setOf(LaneArrow.LEFT), LaneMark.NOT_FOR_ROUTE, null),
                LaneView(setOf(LaneArrow.LEFT, LaneArrow.THROUGH), LaneMark.NOT_FOR_ROUTE, null),
                LaneView(setOf(LaneArrow.THROUGH), LaneMark.NOT_FOR_ROUTE, null),
                LaneView(setOf(LaneArrow.THROUGH), LaneMark.NOT_FOR_ROUTE, null),
                LaneView(setOf(LaneArrow.THROUGH), LaneMark.NOT_FOR_ROUTE, null),
                LaneView(setOf(LaneArrow.THROUGH, LaneArrow.RIGHT), LaneMark.ALLOWED, LaneArrow.RIGHT),
                LaneView(setOf(LaneArrow.RIGHT), LaneMark.BEST, LaneArrow.RIGHT),
            ),
        ),
        distanceM = 321.8688,
        showLanes = true,
    )

    /** Metres still to drive at frame 1: 0.5 mi to the exit, then 422, 356 and 391 m (section 8). */
    const val REMAINING_AT_FRAME1_M = 804.672 + 422.0 + 356.0 + 391.0

    fun guiding(
        banner: LiveBanner? = frame1,
        phase: Phase = Phase.GUIDING,
        gps: GpsState = GpsState.GOOD,
        problem: TripProblem? = null,
        stops: List<TripStop> = listOf(TripStop(downsDrive)),
        nextStopIndex: Int? = stops.indexOfFirst { !it.reached }.takeIf { it >= 0 },
        remainingM: Double = REMAINING_AT_FRAME1_M,
        remainingS: Double = 150.0,
        toNextStopM: Double = remainingM,
        offRouteSuspected: Boolean = false,
        simulated: Boolean = true,
        replanning: Boolean = false,
        route: RouteChoice = fastest,
    ) = TripState(
        phase = phase,
        revision = 7,
        origin = Origin.FromPlace(Place("Start", start, PlaceSource.MAP_PIN), headingDeg = 57.0),
        stops = stops,
        routeType = "fastest",
        options = RouteOptions(),
        offeredTypes = offered,
        guidance = Guidance(
            route = route, banner = banner, vehicle = null, traveled = null, legIndex = 0, nextStopIndex = nextStopIndex,
            toNextStopM = toNextStopM, toNextStopS = remainingS, remainingM = remainingM, remainingS = remainingS,
            offRouteSuspected = offRouteSuspected, rerouteAttempts = if (problem != null) 1 else 0, simulated = simulated,
        ),
        gps = gps,
        problem = problem,
        replanning = replanning,
    )

    fun choosing(
        choices: List<RouteChoice> = listOf(fastest),
        selected: Long? = choices.firstOrNull()?.id,
        phase: Phase = Phase.CHOOSING,
        stops: List<TripStop> = listOf(TripStop(downsDrive)),
        origin: Origin = Origin.CurrentPosition,
        options: RouteOptions = RouteOptions(),
        problem: TripProblem? = null,
        replanning: Boolean = false,
        offeredTypes: List<RouteTypeInfo> = offered,
    ) = TripState(
        phase = phase, revision = 3, origin = origin, stops = stops, routeType = "fastest", options = options,
        offeredTypes = offeredTypes, choices = choices, selectedChoiceId = selected, gps = GpsState.GOOD,
        problem = problem, replanning = replanning,
    )

    val follow = CameraState(CameraMode.GUIDE_FOLLOW, showRecenter = false)
    val preview = CameraState(CameraMode.PREVIEW, showRecenter = false)

    fun present(
        trip: TripState,
        units: UnitFormatter = miles,
        nowEpochMs: Long = now,
        zone: ZoneId = newYork,
        camera: CameraState = if (trip.phase == Phase.PLANNING || trip.phase == Phase.CHOOSING) preview else follow,
        stopsEditorOpen: Boolean = false,
        shownEvent: TripEvent? = null,
        locationAllowed: Boolean = true,
    ): DriveUi = DrivePresenter.present(trip, units, nowEpochMs, zone, camera, stopsEditorOpen, shownEvent, locationAllowed)
}
