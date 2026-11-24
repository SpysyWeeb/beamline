// FILE: core/api/src/main/kotlin/io/github/spysyweeb/beamline/api/nav/Trip.kt
package io.github.spysyweeb.beamline.api.nav

import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** The one navigation session of the process; the phone screens and later Android Auto render it. */
interface NavigationSession {
    /** Current trip; read from any thread. Changes at most once per accepted fix or tick, plus once per intent. */
    val state: StateFlow<TripState>
    /** One-shot happenings (cards, the notification, later voice and chime). Not replayed. */
    val events: SharedFlow<TripEvent>
    /** Thread-safe; handled in order on the session's thread. */
    fun send(intent: NavIntent)
    /** Ends position collection and timers; the session cannot be used afterwards. */
    fun close()
}

enum class Phase {
    /** No trip. The session does not collect positions. */
    IDLE,
    /** Stops known; routes being computed (also after a change of type, options or stops before guidance). */
    PLANNING,
    /** Route choices ready; nothing guided yet. */
    CHOOSING,
    GUIDING,
    /** Off the route (confirmed): a new route is being asked for, or the last attempt failed ([TripState.problem]). */
    REROUTING,
    /** At the destination; ends on Done or when the car leaves (guidance.json arrival.leave_m). */
    ARRIVED,
    /** A trip saved before the process died can be resumed or discarded. */
    RESUME_OFFERED,
}

enum class GpsState {
    /** The session is not collecting positions. */
    OFF,
    /** Collecting, no usable fix yet. */
    WAITING,
    GOOD,
    /** No usable fix for a few seconds; the vehicle is dead-reckoned along the route. */
    WEAK,
    /** No usable fix for longer; the vehicle is shown stale and nothing advances. */
    LOST,
}

enum class Motion { MOVING, STOPPED, UNKNOWN }

sealed interface Origin {
    data object CurrentPosition : Origin
    /** A chosen start (preview, simulation). [headingDeg] picks the carriageway on a divided road. */
    data class FromPlace(val place: Place, val headingDeg: Double? = null) : Origin
}

data class RouteOptions(val avoidHighways: Boolean = false, val avoidTolls: Boolean = false)

/** A route type offered to the user (presets.json route_types with offered = true, in file order). */
data class RouteTypeInfo(val id: String, val label: String)

data class TripStop(val place: Place, val reached: Boolean = false)

/** One distinct route of the choice sheet; types that gave the same route share one choice. */
data class RouteChoice(
    val id: Long,
    /** Route type ids that produced this route, in presets order ("fastest", "simpler"). */
    val typeIds: List<String>,
    val lengthM: Double,
    val timeS: Double,
    val turnCount: Int,
    val hasTolls: Boolean,
    val hasHighways: Boolean,
    /** Names of the two longest stretches, for "via I 90, US 19". */
    val via: List<String>,
    /** The whole route as one line (all legs). */
    val geometry: List<GeoPoint>,
    /** Index into [geometry] where each leg ends (one per stop). */
    val legEnds: List<Int>,
    val bounds: Bounds,
    /** Plain-word warnings ("The route reaches stop 2 0.9 mi from the point you chose."). */
    val warnings: List<String>,
)

/** Where the driven part of the route line ends: after geometry[index], at [point]. */
data class RouteCursor(val index: Int, val point: GeoPoint)

/** The vehicle as the map draws it. */
data class Vehicle(
    val point: GeoPoint,
    /** Route bearing at the snapped point when snapped, else the GPS course; null when unknown. */
    val bearingDeg: Double?,
    val snapped: Boolean,
    val motion: Motion,
    val speedMps: Double?,
    /** Monotonic time of the position (fix or dead-reckoning tick). */
    val elapsedMs: Long,
)

data class Guidance(
    val route: RouteChoice,
    /** Null only until the first progress after a start or a route change. */
    val banner: LiveBanner?,
    val vehicle: Vehicle?,
    val traveled: RouteCursor?,
    val legIndex: Int,
    /** Index into [TripState.stops] of the next stop not reached. */
    val nextStopIndex: Int?,
    val toNextStopM: Double,
    val toNextStopS: Double,
    val remainingM: Double,
    val remainingS: Double,
    /** Off-route fixes are being counted but not yet confirmed (the arrow is off the line). */
    val offRouteSuspected: Boolean,
    /** Failed re-route attempts in a row (0 normally). */
    val rerouteAttempts: Int,
    val simulated: Boolean,
    /**
     * The posted speed limit of the stretch being driven, km/h (the engine's `annotation.maxspeed`
     * at [traveled]; batch A, rule D8); null where it is unknown or there is none.
     */
    val speedLimitKmh: Double? = null,
    /** A lane ending in a turn ahead, shown now (rule B9, batch C1); null when there is none. */
    val laneWarning: LaneWarningView? = null,
    /**
     * The traffic lights on the route ahead of the vehicle, nearest first, one per junction, for the
     * rest of the route (docs/next-round.md items 22 and 39); empty where the routing data knows none.
     */
    val lightsAhead: List<GeoPoint> = emptyList(),
    /** The road being driven, display-ready ("Peach St", "I 90 East"); null where it has no name (item 22). */
    val currentRoad: String? = null,
)

enum class ProblemKind {
    NO_POSITION, OUTSIDE_INSTALLED_MAPS, NO_ROUTE, NO_ROAD_NEAR_STOP, TOO_LONG, TOO_MANY_STOPS,
    /** The installed packs changed under a plan (rule C5), or a stop's state was removed. */
    PACKS_CHANGED,
    /** An install or removal is running; guidance cannot start until it has finished (rule P5). */
    PACKS_BUSY,
    ENGINE,
}

/** A problem in plain words (texts from :routing:core RoutingProblem where one exists). */
data class TripProblem(val kind: ProblemKind, val text: String, val canRetry: Boolean)

data class TripState(
    val phase: Phase = Phase.IDLE,
    /** +1 on every change of stops, type, options or route; answers for an older revision are dropped. */
    val revision: Long = 0,
    val origin: Origin = Origin.CurrentPosition,
    /** The stops in driving order; the last is the destination. */
    val stops: List<TripStop> = emptyList(),
    /** The trip's route type: re-routes, stop changes and trip.json use it (rule C7). */
    val routeType: String = "fastest",
    val options: RouteOptions = RouteOptions(),
    val offeredTypes: List<RouteTypeInfo> = emptyList(),
    /** Filled in CHOOSING (and kept while PLANNING a change); empty while guiding. */
    val choices: List<RouteChoice> = emptyList(),
    val selectedChoiceId: Long? = null,
    /** Non-null in GUIDING, REROUTING and ARRIVED. */
    val guidance: Guidance? = null,
    val gps: GpsState = GpsState.OFF,
    val problem: TripProblem? = null,
    /**
     * An engine request for the current revision is running: planning, a stop, type or option
     * change during guidance (rules C3, C4), or a re-route. Screens show "busy" from it.
     */
    val replanning: Boolean = false,
)
