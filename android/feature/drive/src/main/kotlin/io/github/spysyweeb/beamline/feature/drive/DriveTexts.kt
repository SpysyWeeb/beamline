package io.github.spysyweeb.beamline.feature.drive

import io.github.spysyweeb.beamline.api.nav.LaneArrow
import io.github.spysyweeb.beamline.api.nav.LaneMark
import io.github.spysyweeb.beamline.api.nav.ManeuverKind

/**
 * Every fixed word the drive feature shows or puts in the guidance notification, in one place.
 * Distances, durations and clock times are not here: they come from :core:units (docs/architecture.md 2.9).
 * Texts given by the contract are quoted from it (3.9, 2.7); the others are this track's own and
 * are listed in docs/experiments/15-drive-screens.md.
 */
object DriveTexts {
    /** RouteSheetUi.fromText for Origin.CurrentPosition (2.7). */
    const val YOUR_POSITION = "Your position"

    /** Rule D10: what a screen reader calls the route sheet's handle. */
    const val ROUTE_CHOICE = "Route choice"

    /** RouteSheetUi.startBlockedText (rule D4). */
    const val START_BLOCKED_NO_PRECISE_LOCATION = "Allow precise location to start guidance"

    /** Rule D4 notes. */
    const val NOTE_TOLLS = "Tolls"
    const val NOTE_HIGHWAYS = "Highways"

    /** Rule D8: the sign's two words (MUTCD R2-1), the units, the dash for no good speed, what a screen reader says. */
    const val SPEED = "SPEED"
    const val LIMIT = "LIMIT"
    const val MPH = "mph"
    const val KMH = "km/h"
    const val NO_SPEED = "–"
    fun speedLimitLabel(limit: String, unit: String) = "Speed limit $limit $unit"
    /** Item 32: what a tap on the speed pair does, for a screen reader. */
    const val REPORT_SPEED_LIMIT = "Report the speed limit"
    /** Item 22: what a screen reader says for the current-road label. */
    fun onRoad(road: String) = "On $road"
    fun speedLabel(speed: String, unit: String) = if (speed == NO_SPEED) "Speed unknown" else "Speed $speed $unit"

    /** Rule D1. */
    const val ONTO = "onto "
    const val TOWARD = "toward "
    const val ROAD_SEPARATOR = " / "
    const val TOWARDS_SEPARATOR = ", "

    /** Route cards (2.7: "Fastest · Simpler", "via I 90, US 19", "Arrive 3:42 PM"); the trip bar uses the same arrival word. */
    const val LABEL_SEPARATOR = " · "
    const val VIA = "via "
    const val VIA_SEPARATOR = ", "
    const val ARRIVE = "Arrive "

    /** TripBarUi.nextStopText: "Stop 2 · 4.1 mi" (rule D3). */
    fun nextStop(number: Int, distanceText: String) = "Stop $number$LABEL_SEPARATOR$distanceText"

    /** Status texts (GuidanceStatusUi; "Finding a new route..." and "GPS signal lost" are the contract's). */
    const val REROUTING = "Finding a new route..."
    const val GPS_LOST = "GPS signal lost"
    const val WAITING_FOR_GPS = "Waiting for GPS"
    const val GPS_WEAK = "Weak GPS signal"

    /** GuidanceStatusUi.Arrived.sideText, from the arrival maneuver's kind. */
    const val SIDE_LEFT = "On the left"
    const val SIDE_RIGHT = "On the right"

    /** GuidanceStatusUi.Arrived.awayText: the straight line from the end of the route to the place ("Route ends 500 ft from the place", P4). */
    fun destinationAway(distance: String) = "Route ends $distance from the place"

    /**
     * The arrival's own words, as :routing:banner writes them (B7), for the notification's one-line title (play test
     * P34: the banner names the stop now, "Arrive on the left at Erie Zoo"). :feature:drive does not see
     * :routing:banner (rule 1.1), so the three are repeated here.
     */
    fun arriveWords(kind: ManeuverKind): String = when (kind) {
        ManeuverKind.ARRIVE_LEFT -> "Arrive, on the left"
        ManeuverKind.ARRIVE_RIGHT -> "Arrive, on the right"
        else -> "Arrive"
    }

    /** Notification (rule D6): title in ARRIVED ("Arrived at Downs Drive"); [ARRIVED] when the trip has no name. */
    fun arrivedAt(name: String) = "Arrived at $name"
    const val ARRIVED = "Arrived"

    /** Notification title while guided before the first maneuver is known, when the trip has no name (not expected). */
    const val GUIDANCE = "Guidance"

    /** Notification title and text when no maneuver is known yet, and text while re-routing: "To Downs Drive". */
    fun toDestination(name: String) = "To $name"

    // ---- the overlay's own words (this track's; the sketch gives "Then") ----

    /** The chip's small label (the sketch's "THEN", drawn upper-case). */
    const val THEN = "Then"
    const val FROM = "From"
    const val TO = "To"
    const val START = "Start"
    /** The route sheet's overflow menu (decision 6.29). */
    const val SIMULATE_DRIVE = "Simulate drive"
    const val MORE_OPTIONS = "More options"
    const val CANCEL_ROUTE = "Cancel"
    const val AVOID_HIGHWAYS = "Avoid highways"
    const val AVOID_TOLLS = "Avoid tolls"
    const val RETRY = "Retry"
    const val ADD_STOP = "Add stop"
    const val EDIT_STOPS = "Edit stops"
    const val STOPS = "Stops"
    const val BEST_ORDER = "Best order"
    const val DONE = "Done"
    /** The trip bar's end button (the notification's action says "End trip" too, section 4). */
    const val END_TRIP = "End trip"
    const val MOVE_UP = "Move up"
    const val MOVE_DOWN = "Move down"
    const val REMOVE = "Remove"
    const val REACHED = "Reached"
    const val RECENTER = "Recenter"
    const val OVERVIEW = "Overview"
    const val SIMULATED = "Simulated"
    const val STOP_REACHED = "Stop reached"
    const val CONTINUE = "Continue"
    const val DISCARD = "Discard"

    /** The resume card (rule R3: "Continue to …?"; play test P27: a simulated drive says so). */
    fun continueTo(name: String, simulated: Boolean = false): String = when {
        simulated && name.isBlank() -> "Continue the simulated drive?"
        simulated -> "Continue the simulated drive to $name?"
        name.isBlank() -> "Continue your trip?"
        else -> "Continue to $name?"
    }
    fun stopsLeft(n: Int): String? = if (n > 1) "$n stops left" else null

    // ---- what a screen reader says (the sketch's aria labels) ----

    fun maneuverName(kind: ManeuverKind): String = when (kind) {
        ManeuverKind.DEPART -> "start"
        ManeuverKind.ARRIVE -> "arrive"
        ManeuverKind.ARRIVE_LEFT -> "arrive on the left"
        ManeuverKind.ARRIVE_RIGHT -> "arrive on the right"
        ManeuverKind.STRAIGHT -> "straight"
        ManeuverKind.SLIGHT_LEFT -> "bear left"
        ManeuverKind.LEFT -> "left"
        ManeuverKind.SHARP_LEFT -> "sharp left"
        ManeuverKind.SLIGHT_RIGHT -> "bear right"
        ManeuverKind.RIGHT -> "right"
        ManeuverKind.SHARP_RIGHT -> "sharp right"
        ManeuverKind.UTURN_LEFT, ManeuverKind.UTURN_RIGHT -> "U-turn"
        ManeuverKind.RAMP_LEFT -> "ramp on the left"
        ManeuverKind.RAMP_RIGHT -> "ramp on the right"
        ManeuverKind.RAMP_STRAIGHT -> "ramp straight ahead"
        ManeuverKind.EXIT_LEFT -> "exit left"
        ManeuverKind.EXIT_RIGHT -> "exit right"
        ManeuverKind.FORK_LEFT -> "keep left"
        ManeuverKind.FORK_RIGHT -> "keep right"
        ManeuverKind.FORK_STRAIGHT -> "keep straight"
        ManeuverKind.MERGE -> "merge"
        ManeuverKind.MERGE_LEFT -> "merge left"
        ManeuverKind.MERGE_RIGHT -> "merge right"
        ManeuverKind.ROUNDABOUT -> "roundabout"
        ManeuverKind.ROUNDABOUT_EXIT -> "leave the roundabout"
        ManeuverKind.FERRY -> "ferry"
        ManeuverKind.OTHER -> "continue"
    }

    fun laneArrowName(arrow: LaneArrow): String = when (arrow) {
        LaneArrow.REVERSE -> "U-turn"
        LaneArrow.SHARP_LEFT -> "sharp left"
        LaneArrow.LEFT -> "left"
        LaneArrow.SLIGHT_LEFT -> "slight left"
        LaneArrow.THROUGH -> "straight"
        LaneArrow.SLIGHT_RIGHT -> "slight right"
        LaneArrow.RIGHT -> "right"
        LaneArrow.SHARP_RIGHT -> "sharp right"
        LaneArrow.MERGE_TO_LEFT -> "merge left"
        LaneArrow.MERGE_TO_RIGHT -> "merge right"
        LaneArrow.NONE -> "no arrow"
    }

    /** "left or straight (best)", as the sketch's lane labels (US wording: "straight", not "straight on"). */
    fun laneName(lane: LaneUi): String {
        val arrows = (lane.arrows - LaneArrow.NONE).sorted()
        val name = if (arrows.isEmpty()) laneArrowName(LaneArrow.NONE) else arrows.joinToString(" or ") { laneArrowName(it) }
        return name + when (lane.mark) {
            LaneMark.BEST -> " (best)"
            LaneMark.ALLOWED -> " (allowed)"
            LaneMark.NOT_FOR_ROUTE -> ""
        }
    }

    /** "Lanes, left to right: left (allowed), left or straight (best), right". */
    fun lanesDescription(lanes: List<LaneUi>) = "Lanes, left to right: " + lanes.joinToString(", ") { laneName(it) }

    /** "Then left, In 0.3 mi". */
    fun thenDescription(then: ThenUi) = "Then ${maneuverName(then.kind)}, ${then.distanceText}"

    /** The peek's chip (item 47): "2 routes". */
    fun routes(n: Int): String = if (n == 1) "1 route" else "$n routes"

    /** The steps preview (item 47). */
    const val STEPS = "Steps"
    fun showAllSteps(n: Int): String = "Show all $n steps"

    /** P21: the step view at the middle height ("Step 3 of 12"), its buttons and what a screen reader calls them. */
    fun stepOf(index: Int, count: Int): String = "Step ${index + 1} of $count"
    const val PREVIOUS = "Previous"
    const val NEXT = "Next"
    const val PREVIOUS_STEP = "Previous step"
    const val NEXT_STEP = "Next step"
    const val BACK_TO_STEPS = "Back to the steps"

    /** Item 47: the stops editor lowered, and the arrival card's Save. */
    fun stopsCount(n: Int): String = if (n == 1) "1 stop" else "$n stops"
    const val SAVE_PLACE = "Save this place"
    /** Play test P31, audit F34: the arrival card once its place is saved, and what its handle's raise does. */
    const val SAVED = "Saved"
    const val SHOW_DETAILS = "Show place details"
    /** Play test P49: the stops editor's note after Best order changed the order. */
    const val STOPS_REORDERED = "Stops reordered."
    /** Play test P10, audit F30: the stops editor's Undo beside "Stop removed". */
    const val UNDO = "Undo"
    /** Play test P5: the trip bar and the notification's title outside the installed maps (rule O8). */
    const val NO_ROUTE_FROM_HERE = "No route from here"
    /** Item 47: the From row's swap button. */
    const val SWAP = "Swap start and destination"

    /** Audit F2, F3, F27: the directions window at every height. */
    const val FINDING_ROUTES = "Finding routes..."
    const val CHOOSE_START = "Choose start"
    const val STARTS_FROM_HERE = "Guidance starts from where you are."
}
