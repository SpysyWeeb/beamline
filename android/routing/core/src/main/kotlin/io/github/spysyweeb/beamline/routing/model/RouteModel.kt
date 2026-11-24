package io.github.spysyweeb.beamline.routing.model

/**
 * How the route treats a stop between the first and the last (Valhalla location types).
 * BREAK: a real stop (arrive, then depart; a new leg). THROUGH: drive past this point, no
 * U-turn there. VIA: pass this point, a U-turn there is allowed. BREAK_THROUGH: a stop that must
 * be approached and left in the same direction (a fuel stop on a divided road).
 */
enum class StopType(val valhalla: String) { BREAK("break"), THROUGH("through"), VIA("via"), BREAK_THROUGH("break_through") }

/**
 * A stop of a trip. [headingDeg] and [radiusM] matter when routing from the car's position on a
 * divided road: with only a heading, loki still snaps to the nearer carriageway
 * (docs/experiments/01-routing.md, "Border proof").
 */
data class Stop(
    val point: LatLon,
    val type: StopType = StopType.BREAK,
    val headingDeg: Int? = null,
    val radiusM: Int? = null,
    val name: String? = null,
    /**
     * Other Valhalla location keys, sent as given; they win over presets.json every_request's stop
     * options (a test point that is on a motorway on purpose sets the engine's default search_filter).
     */
    val locationOptions: kotlinx.serialization.json.JsonObject? = null,
    /**
     * The street the place's address names ("East 12th Street"): the planner ties the stop to that
     * street when it passes within presets.json every_request.address_street.within_m (docs/architecture.md
     * 2.11 "Every request" 2; route_lib `_street`). Null: the nearest street of residential class or above.
     */
    val street: String? = null,
    /** False for a map point (a dropped pin): no every_request stop options, the route goes to the road at the pin (route_lib `_tie` "none"). */
    val tied: Boolean = true,
    /** Where the place is when [point] is the planner's point on its address street or in its lot: sent as display_lat/display_lon, so the side of the street is the place's. */
    val displayPoint: LatLon? = null,
    /**
     * The place is a point of interest (a business, shop, amenity; batch E, docs/next-round.md item 18):
     * the planner ends its route in its own lot when a driveway or parking aisle is near
     * (presets.json every_request.lot; route_lib `_poi`). False: a house address, a street, a city, a pin.
     */
    val pointOfInterest: Boolean = false,
    /**
     * Where customers go into a point of interest that is a large building (docs/next-round.md item 28;
     * the search pack's poi_door table; route_lib `_door`): the planner looks for the lot around it, not
     * around [point], which for a large store is the middle of the roof. Null: around the place.
     */
    val door: LatLon? = null,
    /**
     * The end this stop was given when its plan decided it (batch E2, docs/next-round.md item 19;
     * route_lib `_end`): its lot, its address street, the nearest street, or a map point. Null: not decided.
     */
    val end: StopEnd? = null,
    /**
     * True once the planner decided where this stop ends ([end]; route_lib `_fixed`): every route type and a
     * re-route then send it exactly as it is (its point, tie, side options), nothing is decided again.
     */
    val fixed: Boolean = false,
    /**
     * Drive-through lanes and alleys near the place (batch E2; route_lib `_avoid`): rings of five corners (the first
     * again last) the request leaves out (Valhalla exclude_polygons), for every route of the plan.
     */
    val avoid: List<List<LatLon>> = emptyList(),
    /**
     * The way into the stop's lot that this plan leaves out (docs/next-round.md item 35; route_lib `_entry`):
     * rings as [avoid]'s, set by the planner when another way in is faster by the clock and costs the engine
     * hardly more. It belongs to one plan: every plan drops it and decides again from where the trip is then.
     */
    val entry: List<List<LatLon>> = emptyList(),
)

/** Where a decided stop ends (route_lib `_end`): its own lot, its address street, the nearest street, the place itself untied (batch H), a map point as it is. */
enum class StopEnd(val id: String) { LOT("lot"), STREET("street"), NEAR("near"), PLACE("place"), PIN("pin") }

/** What the user asks for: stops in order and a preset id from presets.json. */
data class RouteRequest(
    val stops: List<Stop>,
    val presetId: String = "fastest",
    /** Alternates are only offered for two-stop trips (Valhalla does not do multipoint alternates). */
    val withAlternates: Boolean = false,
)

/** Valhalla maneuver types (proto/descriptors/directions.proto at 3.9.1, DirectionsLeg.Maneuver.Type). */
enum class ManeuverType(val code: Int) {
    NONE(0), START(1), START_RIGHT(2), START_LEFT(3), DESTINATION(4), DESTINATION_RIGHT(5), DESTINATION_LEFT(6),
    BECOMES(7), CONTINUE(8), SLIGHT_RIGHT(9), RIGHT(10), SHARP_RIGHT(11), UTURN_RIGHT(12), UTURN_LEFT(13),
    SHARP_LEFT(14), LEFT(15), SLIGHT_LEFT(16), RAMP_STRAIGHT(17), RAMP_RIGHT(18), RAMP_LEFT(19), EXIT_RIGHT(20),
    EXIT_LEFT(21), STAY_STRAIGHT(22), STAY_RIGHT(23), STAY_LEFT(24), MERGE(25), ROUNDABOUT_ENTER(26),
    ROUNDABOUT_EXIT(27), FERRY_ENTER(28), FERRY_EXIT(29), TRANSIT(30), TRANSIT_TRANSFER(31), TRANSIT_REMAIN_ON(32),
    TRANSIT_CONNECTION_START(33), TRANSIT_CONNECTION_TRANSFER(34), TRANSIT_CONNECTION_DESTINATION(35),
    POST_TRANSIT_CONNECTION_DESTINATION(36), MERGE_RIGHT(37), MERGE_LEFT(38), ELEVATOR_ENTER(39), STEPS_ENTER(40),
    ESCALATOR_ENTER(41), BUILDING_ENTER(42), BUILDING_EXIT(43), LEVEL_CHANGE(44), PARK_VEHICLE(45);

    val isStart get() = this == START || this == START_RIGHT || this == START_LEFT
    val isDestination get() = this == DESTINATION || this == DESTINATION_RIGHT || this == DESTINATION_LEFT

    companion object {
        private val byCode = entries.associateBy { it.code }

        /** Unknown codes (a newer engine) become NONE rather than failing the whole route. */
        fun of(code: Int): ManeuverType = byCode[code] ?: NONE
    }
}

/**
 * Lane direction bits (valhalla/baldr/turnlanes.h at 3.9.1). A lane's [Lane.directions] is a mask
 * of these, e.g. LEFT or THROUGH = 10 ("left or straight on").
 */
object LaneBits {
    const val NONE = 1 shl 0
    const val THROUGH = 1 shl 1
    const val SHARP_LEFT = 1 shl 2
    const val LEFT = 1 shl 3
    const val SLIGHT_LEFT = 1 shl 4
    const val SLIGHT_RIGHT = 1 shl 5
    const val RIGHT = 1 shl 6
    const val SHARP_RIGHT = 1 shl 7
    const val REVERSE = 1 shl 8
    const val MERGE_TO_LEFT = 1 shl 9
    const val MERGE_TO_RIGHT = 1 shl 10

    val names = linkedMapOf(
        REVERSE to "reverse", SHARP_LEFT to "sharp_left", LEFT to "left", SLIGHT_LEFT to "slight_left",
        THROUGH to "through", SLIGHT_RIGHT to "slight_right", RIGHT to "right", SHARP_RIGHT to "sharp_right",
        MERGE_TO_LEFT to "merge_to_left", MERGE_TO_RIGHT to "merge_to_right", NONE to "none",
    )

    fun describe(mask: Int): List<String> = names.filter { (bit, _) -> mask and bit != 0 }.values.toList()
}

/** The engine's verdict on one lane for the maneuver it belongs to. */
enum class LaneState {
    /** Not for this route. */
    NONE,

    /** The lane may be used for this maneuver ("also allowed"). */
    VALID,

    /** The engine's preferred lane(s) for this maneuver. */
    ACTIVE,
}

/** One lane, left to right, at the approach to a maneuver. [direction] is the mask the state is for. */
data class Lane(val directions: Int, val state: LaneState, val direction: Int? = null)

/** Exit sign of a maneuver (Valhalla "sign"): exit number, branch (route numbers), toward, exit name. */
data class ExitSign(
    val numbers: List<String> = emptyList(),
    val branches: List<String> = emptyList(),
    val towards: List<String> = emptyList(),
    val names: List<String> = emptyList(),
) {
    val isEmpty get() = numbers.isEmpty() && branches.isEmpty() && towards.isEmpty() && names.isEmpty()
}

/**
 * One maneuver, as the engine reports it. Lengths in metres, times in seconds. The maneuver
 * starts at [beginShapeIndex] of its leg's shape; [lengthM] / [timeS] cover the stretch from this
 * maneuver to the next one. [lanes] are the lanes on the road just before this maneuver (null when
 * the map has no lane data there); [multiCue] is Valhalla's verbal_multi_cue flag.
 */
data class Maneuver(
    val type: ManeuverType,
    val typeCode: Int,
    val instruction: String,
    val verbalPreTransition: String?,
    val lengthM: Double,
    val timeS: Double,
    val streetNames: List<String>,
    val beginStreetNames: List<String>,
    val beginShapeIndex: Int,
    val endShapeIndex: Int,
    val toll: Boolean,
    val highway: Boolean,
    val sign: ExitSign?,
    val roundaboutExitCount: Int?,
    val lanes: List<Lane>?,
    val multiCue: Boolean,
)

data class Leg(val lengthM: Double, val timeS: Double, val shape: List<LatLon>, val maneuvers: List<Maneuver>)

/**
 * A route as the app uses it. [requested] are the points the request asked for (Valhalla 3.9.1
 * echoes them in trip.locations), so the gap to the route's ends shows silent far snapping.
 */
data class Route(
    val lengthM: Double,
    val timeS: Double,
    val legs: List<Leg>,
    val requested: List<LatLon>,
    val hasToll: Boolean,
    val hasHighway: Boolean,
    /** The engine's own cost of the route (trip.summary.cost: seconds plus its penalties); null when the answer has none. */
    val cost: Double? = null,
) {
    val maneuvers: List<Maneuver> get() = legs.flatMap { it.maneuvers }
    val shape: List<LatLon> get() = legs.flatMap { it.shape }

    /** Turns and the like: every maneuver except departures and arrivals (route_lib's count). */
    val turnCount: Int get() = maneuvers.count { !it.type.isStart && !it.type.isDestination }

    /** Metres of maneuvers the engine flags as motorway (highway) or toll. */
    fun flaggedM(flag: RoadFlag): Double = maneuvers.filter { if (flag == RoadFlag.HIGHWAY) it.highway else it.toll }
        .sumOf { it.lengthM }

    /** Distance from each requested end point to where the route really starts and ends. */
    fun endpointGapsM(): Pair<Double, Double>? {
        val s = shape
        if (s.isEmpty() || requested.size < 2) return null
        return Geo.distanceM(requested.first(), s.first()) to Geo.distanceM(requested.last(), s.last())
    }
}

enum class RoadFlag(val key: String) { HIGHWAY("highway"), TOLL("toll") }
