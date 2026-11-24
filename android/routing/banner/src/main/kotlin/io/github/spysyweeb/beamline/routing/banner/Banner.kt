package io.github.spysyweeb.beamline.routing.banner

import io.github.spysyweeb.beamline.routing.model.Lane
import io.github.spysyweeb.beamline.routing.model.LaneState
import io.github.spysyweeb.beamline.routing.model.Maneuver
import io.github.spysyweeb.beamline.routing.model.ManeuverType
import io.github.spysyweeb.beamline.routing.model.ManeuverType.*
import io.github.spysyweeb.beamline.routing.model.Route

/** Which arrow the banner draws for a maneuver. */
enum class ManeuverIcon {
    DEPART, ARRIVE, ARRIVE_LEFT, ARRIVE_RIGHT, STRAIGHT, SLIGHT_LEFT, LEFT, SHARP_LEFT, SLIGHT_RIGHT, RIGHT,
    SHARP_RIGHT, UTURN_LEFT, UTURN_RIGHT, RAMP_LEFT, RAMP_RIGHT, RAMP_STRAIGHT, EXIT_LEFT, EXIT_RIGHT, FORK_LEFT,
    FORK_RIGHT, FORK_STRAIGHT, MERGE, MERGE_LEFT, MERGE_RIGHT, ROUNDABOUT, ROUNDABOUT_EXIT, FERRY, OTHER,
}

/**
 * The main instruction for one maneuver. [text] is the short verb ("Turn left", "Exit 24",
 * "Keep right"), [roads] the road(s) the maneuver leads onto (route numbers first for exits),
 * display-ready: the last word abbreviated by banner.json road_abbreviations ("Peach St").
 * [towards] the sign's destinations. [approachM] is the length of the stretch before the maneuver
 * (from the previous maneuver); the live distance comes from guidance and is formatted by
 * :core:units UnitFormatter, not here.
 */
data class MainInstruction(
    val icon: ManeuverIcon,
    val typeCode: Int,
    val text: String,
    val roads: List<String>,
    val towards: List<String>,
    val exitNumber: String?,
    val roundaboutExit: Int?,
    val approachM: Double,
)

/**
 * The following turn beside the main one, with the distance and time from the first turn to the
 * second (the route's own estimate; the chip's "In 0.2 mi" is UnitFormatter.distanceIn(distanceM)).
 */
data class FollowingTurn(
    val icon: ManeuverIcon,
    val typeCode: Int,
    val text: String,
    val distanceM: Double,
    val timeS: Double,
)

enum class LaneMark { BEST, ALLOWED, NOT_FOR_ROUTE }

/** One lane of the lane row, left to right. [directions] are the lane's arrows (LaneBits mask). */
data class LaneCell(val directions: Int, val mark: LaneMark, val usedDirection: Int?)

/**
 * What the banner shows while the driver approaches maneuver [maneuverIndex] of the route
 * (index into [Route.maneuvers], all legs in order). [lanes] is null when there is no lane row.
 */
data class Banner(
    val maneuverIndex: Int,
    val main: MainInstruction,
    val following: FollowingTurn?,
    val lanes: List<LaneCell>?,
)

/**
 * The banner model: a pure function from a route (and the rules in banner.json) to one banner per
 * maneuver. Decisions (docs/design/README.md): the following turn is shown when it comes within
 * 0.5 mile AND 1 minute of the main one by the route's own estimate; lanes have three states and
 * the best lane takes the following turn into account; no lane row without lane data.
 */
object BannerModel {

    fun build(route: Route, rules: BannerRules = BannerRules.bundled()): List<Banner> {
        val out = ArrayList<Banner>()
        var index = 0
        var previous: Maneuver? = null
        for (leg in route.legs) {
            leg.maneuvers.forEachIndexed { i, m ->
                val next = leg.maneuvers.getOrNull(i + 1)
                val following = followingTurn(m, next, rules)
                out += Banner(
                    maneuverIndex = index,
                    main = main(m, previous?.lengthM ?: 0.0, next, rules),
                    following = following,
                    lanes = laneRow(m.lanes, following?.let { next }, rules),
                )
                previous = m
                index++
            }
        }
        return out
    }

    /** The maneuver after [m] when it is a turn within the limits; distance = [m]'s own stretch. */
    fun followingTurn(m: Maneuver, next: Maneuver?, rules: BannerRules): FollowingTurn? {
        if (next == null || next.typeCode in rules.notATurnTypes) return null
        if (m.type.isDestination) return null
        if (m.lengthM > rules.followingMaxDistanceM || m.timeS > rules.followingMaxTimeS) return null
        val n = main(next, m.lengthM, null)
        return FollowingTurn(n.icon, next.typeCode, n.text, m.lengthM, m.timeS)
    }

    /**
     * The lane row: the engine's states (active = best, valid = also allowed, others = not for this
     * route). When a following turn is shown and the engine left several best lanes, the one nearest
     * the side of the following turn stays best and the others become "also allowed" (Valhalla does
     * the same itself below 0.402 km; banner.json says why).
     */
    fun laneRow(lanes: List<Lane>?, following: Maneuver?, rules: BannerRules): List<LaneCell>? {
        if (lanes.isNullOrEmpty()) return null
        if (rules.rowNeedsMarkedLane && lanes.none { it.state != LaneState.NONE }) return null
        val cells = lanes.map {
            LaneCell(it.directions, when (it.state) {
                LaneState.ACTIVE -> LaneMark.BEST
                LaneState.VALID -> LaneMark.ALLOWED
                LaneState.NONE -> LaneMark.NOT_FOR_ROUTE
            }, it.direction)
        }
        val best = cells.indices.filter { cells[it].mark == LaneMark.BEST }
        if (!rules.refineBestLaneForFollowingTurn || following == null || best.size < 2 ||
            following.typeCode in rules.noSideTypes) return cells
        val keep = when (sideOf(following.type)) {
            Side.LEFT -> best.first()
            Side.RIGHT -> best.last()
            null -> return cells
        }
        return cells.mapIndexed { i, c -> if (i in best && i != keep) c.copy(mark = LaneMark.ALLOWED) else c }
    }

    private enum class Side { LEFT, RIGHT }

    /** As Valhalla's ActivateTurnLanes (drive on the right): which side the next maneuver is on. */
    private fun sideOf(t: ManeuverType): Side? = when (t) {
        SLIGHT_LEFT, LEFT, SHARP_LEFT, UTURN_LEFT, RAMP_LEFT, EXIT_LEFT, STAY_LEFT, DESTINATION_LEFT, MERGE_LEFT, MERGE -> Side.LEFT
        SLIGHT_RIGHT, RIGHT, SHARP_RIGHT, UTURN_RIGHT, RAMP_RIGHT, EXIT_RIGHT, STAY_RIGHT, DESTINATION_RIGHT, MERGE_RIGHT, DESTINATION -> Side.RIGHT
        else -> null
    }

    /**
     * [next] supplies the road names of a roundabout entry (Valhalla names them on the exit
     * maneuver); with [rules] the road names are abbreviated (banner.json road_abbreviations).
     */
    fun main(m: Maneuver, approachM: Double, next: Maneuver? = null, rules: BannerRules? = null): MainInstruction {
        val sign = m.sign
        val exitNumber = sign?.numbers?.takeIf { it.isNotEmpty() }?.joinToString("/")
        val signed = sign != null && (sign.branches.isNotEmpty() || sign.towards.isNotEmpty() || sign.names.isNotEmpty())
        val (icon, text) = when (m.type) {
            START, START_LEFT, START_RIGHT -> ManeuverIcon.DEPART to "Start"
            DESTINATION -> ManeuverIcon.ARRIVE to "Arrive"
            DESTINATION_LEFT -> ManeuverIcon.ARRIVE_LEFT to "Arrive, on the left"
            DESTINATION_RIGHT -> ManeuverIcon.ARRIVE_RIGHT to "Arrive, on the right"
            BECOMES, CONTINUE -> ManeuverIcon.STRAIGHT to "Continue"
            SLIGHT_LEFT -> ManeuverIcon.SLIGHT_LEFT to "Bear left"
            LEFT -> ManeuverIcon.LEFT to "Turn left"
            SHARP_LEFT -> ManeuverIcon.SHARP_LEFT to "Turn sharp left"
            SLIGHT_RIGHT -> ManeuverIcon.SLIGHT_RIGHT to "Bear right"
            RIGHT -> ManeuverIcon.RIGHT to "Turn right"
            SHARP_RIGHT -> ManeuverIcon.SHARP_RIGHT to "Turn sharp right"
            UTURN_LEFT -> ManeuverIcon.UTURN_LEFT to "Make a U-turn"
            UTURN_RIGHT -> ManeuverIcon.UTURN_RIGHT to "Make a U-turn"
            RAMP_LEFT -> ManeuverIcon.RAMP_LEFT to "Take the ramp on the left"
            RAMP_RIGHT -> ManeuverIcon.RAMP_RIGHT to "Take the ramp on the right"
            RAMP_STRAIGHT -> ManeuverIcon.RAMP_STRAIGHT to "Take the ramp"
            EXIT_LEFT -> ManeuverIcon.EXIT_LEFT to (exitNumber?.let { "Exit $it on the left" } ?: "Take the exit on the left")
            EXIT_RIGHT -> ManeuverIcon.EXIT_RIGHT to (exitNumber?.let { "Exit $it" } ?: "Take the exit")
            STAY_LEFT -> ManeuverIcon.FORK_LEFT to (exitNumber?.let { "Exit $it on the left" } ?: "Keep left")
            STAY_RIGHT -> ManeuverIcon.FORK_RIGHT to (exitNumber?.let { "Exit $it" } ?: "Keep right")
            STAY_STRAIGHT -> ManeuverIcon.FORK_STRAIGHT to "Keep straight"
            MERGE -> ManeuverIcon.MERGE to "Merge"
            MERGE_LEFT -> ManeuverIcon.MERGE_LEFT to "Merge left"
            MERGE_RIGHT -> ManeuverIcon.MERGE_RIGHT to "Merge right"
            ROUNDABOUT_ENTER -> ManeuverIcon.ROUNDABOUT to (m.roundaboutExitCount?.let { "Roundabout, ${ordinal(it)} exit" } ?: "Enter the roundabout")
            ROUNDABOUT_EXIT -> ManeuverIcon.ROUNDABOUT_EXIT to "Exit the roundabout"
            FERRY_ENTER -> ManeuverIcon.FERRY to "Take the ferry"
            FERRY_EXIT -> ManeuverIcon.FERRY to "Leave the ferry"
            else -> ManeuverIcon.OTHER to m.instruction
        }
        val roads = when {
            m.type.isDestination -> emptyList()
            signed && sign!!.branches.isNotEmpty() -> sign.branches
            m.streetNames.isNotEmpty() -> m.streetNames
            m.type == ROUNDABOUT_ENTER && next?.type == ROUNDABOUT_EXIT ->
                next.beginStreetNames.ifEmpty { next.streetNames }
            else -> m.beginStreetNames
        }
        val shown = if (rules == null) roads else roads.map(rules::abbreviate)
        // A ramp or an exit the data gives no sign and no name for ("Take the ramp on the left" alone says
        // little): it leads toward the road the next maneuver is on (docs/next-round.md item 22).
        val signTowards = sign?.towards ?: emptyList()
        val towards = if (signTowards.isEmpty() && shown.isEmpty() && m.type in RAMPS && next != null && !next.type.isDestination) {
            next.streetNames.take(1).map { rules?.abbreviate(it) ?: it }
        } else signTowards
        return MainInstruction(icon, m.typeCode, text, shown, towards, exitNumber,
            m.roundaboutExitCount, approachM)
    }

    /** The maneuvers that leave a road by a ramp. */
    private val RAMPS = setOf(RAMP_LEFT, RAMP_RIGHT, RAMP_STRAIGHT, EXIT_LEFT, EXIT_RIGHT)

    private fun ordinal(n: Int) = n.toString() + when {
        n % 100 in 11..13 -> "th"
        n % 10 == 1 -> "st"
        n % 10 == 2 -> "nd"
        n % 10 == 3 -> "rd"
        else -> "th"
    }
}
