// FILE: core/api/src/main/kotlin/io/github/spysyweeb/beamline/api/nav/Banner.kt
package io.github.spysyweeb.beamline.api.nav

/** Which arrow a maneuver gets (same names as :routing:banner ManeuverIcon; mapped by name). */
enum class ManeuverKind {
    DEPART, ARRIVE, ARRIVE_LEFT, ARRIVE_RIGHT, STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT,
    SHARP_RIGHT, UTURN_LEFT, UTURN_RIGHT, RAMP_LEFT, RAMP_RIGHT, RAMP_STRAIGHT, EXIT_LEFT, EXIT_RIGHT, FORK_LEFT,
    FORK_RIGHT, FORK_STRAIGHT, MERGE, MERGE_LEFT, MERGE_RIGHT, ROUNDABOUT, ROUNDABOUT_EXIT, FERRY, OTHER,
}

/** One arrow painted on a lane (Valhalla lane bits, :routing:core LaneBits). */
enum class LaneArrow { REVERSE, SHARP_LEFT, LEFT, SLIGHT_LEFT, THROUGH, SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, MERGE_TO_LEFT, MERGE_TO_RIGHT, NONE }

/** Same names as :routing:banner LaneMark. */
enum class LaneMark { BEST, ALLOWED, NOT_FOR_ROUTE }

/** One lane, left to right. [usedArrow]: the arrow this route uses, in a best or allowed lane. */
data class LaneView(val arrows: Set<LaneArrow>, val mark: LaneMark, val usedArrow: LaneArrow?)

/** The following turn beside the main one; distance and time of the stretch between the two (route's own estimate). */
data class FollowingView(val kind: ManeuverKind, val primary: String, val distanceM: Double, val timeS: Double)

/**
 * What the banner says about one maneuver; built once per route from :routing:banner. Road names
 * are display-ready (banner.json road_abbreviations applied: "Peach St").
 */
data class BannerView(
    /** Index into the route's maneuvers, all legs flattened (Route.maneuvers). */
    val maneuverIndex: Int,
    val kind: ManeuverKind,
    /** "Exit 24", "Turn left", "Roundabout, 2nd exit", "Arrive, on the right". */
    val primary: String,
    val roads: List<String>,
    val towards: List<String>,
    val exitNumber: String?,
    val roundaboutExit: Int?,
    /** The stretch before the maneuver is flagged highway (motorway) by the engine. */
    val approachOnHighway: Boolean,
    val following: FollowingView?,
    /** Null: no lane row (no lane data, or none marked). */
    val lanes: List<LaneView>?,
)

/** The banner on screen now. */
data class LiveBanner(
    val view: BannerView,
    /** Live distance to the maneuver in metres (held against jitter, rule B3). */
    val distanceM: Double,
    /** Whether the lane row is shown now (rule B5). */
    val showLanes: Boolean,
)

/**
 * A lane that ends in a turn ahead (rule B9, batch C1): the route goes straight through the next
 * intersection, where fewer lanes go on than the road had before its turn lanes began.
 */
data class LaneWarningView(
    /** The intersection's lanes, left to right: BEST for the lanes that go on with the route, NOT_FOR_ROUTE for the others. */
    val lanes: List<LaneView>,
    /** One line: "Right lane turns right: keep left for Peach St". */
    val text: String,
    /** Live distance to the intersection in metres. */
    val distanceM: Double,
)
