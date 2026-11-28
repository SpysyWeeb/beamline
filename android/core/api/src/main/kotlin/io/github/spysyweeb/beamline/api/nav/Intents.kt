// FILE: core/api/src/main/kotlin/io/github/spysyweeb/beamline/api/nav/Intents.kt
package io.github.spysyweeb.beamline.api.nav

import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place

sealed interface NavIntent {
    /** A new trip to one place from the current position (replaces any trip that is not being guided). */
    data class PlanTo(val place: Place) : NavIntent
    /**
     * Item 47: how long the drive to [place] would take, for the place window's Directions: IDLE only,
     * from an acceptable fix, the default route type with the user's options; the answer is
     * [TripState.estimate]. No trip is made and nothing else changes.
     */
    data class Estimate(val place: Place) : NavIntent
    /**
     * Item 51 (rule C8): how much time each of [places] would add to the trip as a new stop where [AddStop]
     * without an index puts it; PLANNING, CHOOSING, GUIDING and REROUTING only. The time from where the stop
     * joins the trip (the stop before the destination, else the car while guiding, else the trip's start) to
     * the place and from the place back to the trip's route past it (the destination when near), less that
     * part of the leg's time now: Valhalla matrices on the routing thread with the trip's route type and
     * options, a few places at a time. The answer is [TripState.detours] for [requestId]; a newer Detours
     * replaces this one; an empty [places] with this [requestId] drops it (with another id it is ignored); a
     * change of the stops, start, route type or options, arrival and the end of the trip drop it too. Nothing
     * else changes.
     */
    data class Detours(val requestId: Long, val places: List<Place>) : NavIntent
    /**
     * Item 51 (rule C8): the route with [place] added as [AddStop] without an index would add it, to look at
     * before adding ([TripState.stopPreview]); null ends the look. PLANNING, CHOOSING, GUIDING and REROUTING
     * only; the trip itself does not change.
     */
    data class PreviewStop(val place: Place?) : NavIntent
    data class SetOrigin(val origin: Origin) : NavIntent
    /** Inserts at [index] of the stops; null = just before the destination (or as the destination when there is none). */
    data class AddStop(val place: Place, val index: Int? = null) : NavIntent
    data class RemoveStop(val index: Int) : NavIntent
    /** Moves a stop not reached; whatever stop ends up last is the destination (rule C1). */
    data class MoveStop(val from: Int, val to: Int) : NavIntent
    /**
     * Reorders the stops not yet reached for the least driving time; the destination stays last. A
     * changed order is told by [TripEvent.StopsReordered]; an order that was the best already by the
     * problem "These stops are already in the best order."
     */
    data object OptimizeStopOrder : NavIntent
    /**
     * Item 47: the trip the other way round, before guidance (PLANNING, CHOOSING): the destination is
     * the start, the stops in reverse, and the start the destination. From the current position, the
     * destination is that position as it is now ("Your starting point", a point with the second line
     * "Where you were when you swapped", play test P19); swapped back, the trip starts from the current
     * position again. Without a position: NO_POSITION, nothing changes.
     */
    data object SwapEnds : NavIntent
    data class SetRouteType(val typeId: String) : NavIntent
    data class SetOptions(val options: RouteOptions) : NavIntent
    /** Selects a route card; the trip's route type follows it (rule C7). */
    data class Choose(val choiceId: Long) : NavIntent
    /** Starts guidance on the selected choice; with [simulation] the session drives the route itself. */
    data class Start(val simulation: Simulation? = null) : NavIntent
    /** Asks for a new route from here now (user button). */
    data object Reroute : NavIntent
    /** Retries after a failed plan or re-route. */
    data object Retry : NavIntent
    /** Cancels planning or ends guidance; back to IDLE. */
    data object End : NavIntent
    /**
     * Takes back the End of a guided trip (rule R4): within [END_UNDO_MS] of it, and before any
     * other intent, the stops not reached are re-planned from the current position with the same
     * route type and options, and guidance goes on (a simulated drive stays simulated). Audit F31:
     * the End of a route not yet started (PLANNING, CHOOSING) is taken back the same way, the route
     * planned again as it was (its start, stops, type and options). [Estimate], [Detours] and
     * [PreviewStop] do not count as intents here. Ignored otherwise.
     */
    data object UndoEnd : NavIntent
    /** Leaves ARRIVED. */
    data object Done : NavIntent
    data object Resume : NavIntent
    data object DiscardResume : NavIntent
}

/** How long after End during guidance the app offers "Undo" and the session accepts [NavIntent.UndoEnd] (rule R4). */
const val END_UNDO_MS: Long = 10_000

enum class ReplaceReason { REROUTE, STOPS_CHANGED, OPTIONS_CHANGED, RESUMED }

enum class AnnounceStage { PREPARE, NOW }

sealed interface TripEvent {
    data class GuidanceStarted(val routeId: Long, val simulated: Boolean) : TripEvent
    data class RouteReplaced(val routeId: Long, val reason: ReplaceReason) : TripEvent
    data class StopReached(val stopIndex: Int, val name: String) : TripEvent
    data class Arrived(val name: String) : TripEvent
    data class RerouteFailed(val problem: TripProblem) : TripEvent
    /** For later voice and chime: emitted at guidance.json announce distances; nothing listens yet. */
    data class Announce(val maneuverIndex: Int, val stage: AnnounceStage, val distanceM: Double) : TripEvent
    data object GuidanceEnded : TripEvent
    /** Play test P49: [NavIntent.OptimizeStopOrder] changed the order of the stops (rule C6); the stops editor says "Stops reordered.". */
    data object StopsReordered : TripEvent
}

/** The built-in drive along the route being guided: for tests, the emulator and the phone. */
data class Simulation(
    /** 1.0 = the route's own speed per maneuver (lengthM / timeS); 4.0 = four times as fast. */
    val speedFactor: Double = 1.0,
    /** Fixes per simulated second. */
    val fixHz: Double = 1.0,
    val accuracyM: Double = 5.0,
    /** Sideways noise in metres (normal distribution, seeded). */
    val lateralNoiseM: Double = 0.0,
    val seed: Long = 1,
    /**
     * A named script that the session builds from the route at Start (section 8: "default",
     * "wrongturn", "gaps", "jitter", "drift", "tunnel"); [script] is added to it. Null: [script] only.
     */
    val scriptName: String? = null,
    val script: List<SimEvent> = emptyList(),
)

/** Something that happens when the simulated car reaches [atM] metres along the route it is driving. */
sealed interface SimEvent {
    val atM: Double
    /** Stands still (speed 0, fixes continue). */
    data class Halt(override val atM: Double, val seconds: Double) : SimEvent
    /** No fixes; the car keeps moving. */
    data class Gap(override val atM: Double, val seconds: Double) : SimEvent
    /** One fix moved sideways. */
    data class Jump(override val atM: Double, val sidewaysM: Double) : SimEvent
    /** Every fix for [seconds] moved [sidewaysM] sideways (positive = right of the route), then back. */
    data class Drift(override val atM: Double, val seconds: Double, val sidewaysM: Double) : SimEvent
    data class PoorAccuracy(override val atM: Double, val seconds: Double, val accuracyM: Double) : SimEvent
    /** One fix delivered with an old timestamp. */
    data class StaleFix(override val atM: Double, val ageS: Double) : SimEvent
    /** Leaves the route along [points]; afterwards the car follows whatever route the session guides then. */
    data class Detour(override val atM: Double, val points: List<GeoPoint>, val speedMps: Double) : SimEvent
}
