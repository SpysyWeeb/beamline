// FILE: feature/drive/src/main/kotlin/io/github/spysyweeb/beamline/feature/drive/DriveUi.kt
package io.github.spysyweeb.beamline.feature.drive

import io.github.spysyweeb.beamline.api.map.CameraState
import io.github.spysyweeb.beamline.api.nav.LaneArrow
import io.github.spysyweeb.beamline.api.nav.LaneMark
import io.github.spysyweeb.beamline.api.nav.ManeuverKind
import io.github.spysyweeb.beamline.api.nav.NavIntent
import io.github.spysyweeb.beamline.api.nav.TripEvent
import io.github.spysyweeb.beamline.api.nav.TripState
import io.github.spysyweeb.beamline.units.UnitFormatter
import java.time.ZoneId

data class StopRowUi(
    val index: Int,
    val title: String,
    val subtitle: String?,
    val reached: Boolean,
    val canRemove: Boolean,
    val canMoveUp: Boolean,
    val canMoveDown: Boolean,
)

data class RouteCardUi(
    val choiceId: Long,
    /** "Fastest · Simpler" */
    val labels: String,
    val durationText: String,
    val distanceText: String,
    /** "Arrive 3:42 PM" */
    val arriveText: String,
    /** "via I 90, US 19" */
    val viaText: String?,
    /** "Tolls", "Highways" */
    val notes: List<String>,
    val selected: Boolean,
)

data class RouteSheetUi(
    /** "Your position" or the origin's name; tapping it chooses another start (DriveEvent.ChooseStart). */
    val fromText: String,
    val stops: List<StopRowUi>,
    val cards: List<RouteCardUi>,
    val avoidHighways: Boolean,
    val avoidTolls: Boolean,
    val busy: Boolean,
    val problem: String?,
    val canRetry: Boolean,
    val warnings: List<String>,
    val canStart: Boolean,
    /** Why Start is off although a route is chosen ("Allow precise location to start guidance"); null when it is not off for that. */
    val startBlockedText: String?,
)

data class StopsEditorUi(val rows: List<StopRowUi>, val canOptimize: Boolean, val busy: Boolean)

/** The chip beside the main instruction: arrow and "In 0.2 mi" (does not count down). */
data class ThenUi(val kind: ManeuverKind, val distanceText: String)

data class LaneUi(val arrows: Set<LaneArrow>, val mark: LaneMark, val usedArrow: LaneArrow?)

data class BannerUi(
    val kind: ManeuverKind,
    val roundaboutExit: Int?,
    /** "0.3 mi", "400 ft" */
    val distanceText: String,
    /** "Exit 24", "Turn left" */
    val primary: String,
    /** "US 19 / Peach St" (exits), "onto US 19 / Peach St" (turns) */
    val roadsText: String?,
    /** "toward Waterford" */
    val towardsText: String?,
    val then: ThenUi?,
    /** Null: no lane row. */
    val lanes: List<LaneUi>?,
    /**
     * Rule B9 (batch C1): a lane ends in a turn ahead; then [lanes] are that intersection's lanes and
     * this line is shown under them ("Right lane turns right: keep left for Peach St").
     */
    val laneNote: String? = null,
)

sealed interface GuidanceStatusUi {
    data object OnRoute : GuidanceStatusUi
    data object WaitingForGps : GuidanceStatusUi
    /** "Finding a new route..." */
    data object Rerouting : GuidanceStatusUi
    data class RerouteFailed(val text: String, val canRetry: Boolean) : GuidanceStatusUi
    data object GpsWeak : GuidanceStatusUi
    /** "GPS signal lost" */
    data object GpsLost : GuidanceStatusUi
    data class StopReached(val name: String) : GuidanceStatusUi
    /** [awayText]: "Destination 500 ft away" when the route ends more than 100 m from the place (a place tied to a street up to 300 m away). */
    data class Arrived(val name: String, val sideText: String?, val awayText: String? = null) : GuidanceStatusUi
}

data class TripBarUi(
    val arriveText: String,
    val remainingTimeText: String,
    val remainingDistanceText: String,
    /** "Stop 2 · 4.1 mi" */
    val nextStopText: String?,
    val simulated: Boolean,
)

/** [banner] is null in REROUTING: the status card takes its place (rule D7). */
/** How far over the limit the current speed is (rule D8): yellow at 5 mph (8 km/h) over, red at 10 mph (16 km/h). */
enum class SpeedOver { NO, YELLOW, RED }

/** The speed-limit sign and the current speed beside it while guiding (rule D8). */
data class SpeedUi(
    /** The sign's number ("55"); null: no sign (no limit known, none posted, or off the route). */
    val limit: String?,
    /** The current speed from the GPS fixes ("57"), or "–" with a weak or lost fix. */
    val speed: String,
    /** "mph" or "km/h", as Settings' units. */
    val unit: String,
    /** The colour of [speed]'s number; the sign never changes. */
    val over: SpeedOver,
    /**
     * Item 32 (docs/next-round.md): a tap on the pair reports the posted limit ([DriveEvent.SpeedLimitTapped]).
     * The app sets it while "Contribute to OpenStreetMap" is on; off, the pair is not a button.
     */
    val canReport: Boolean = false,
)

data class GuidanceUi(
    val banner: BannerUi?,
    val status: GuidanceStatusUi,
    val tripBar: TripBarUi,
    val showRecenter: Boolean,
    /** Rule D8: in GUIDING and REROUTING; null in ARRIVED. */
    val speed: SpeedUi? = null,
    /**
     * The road being driven ("Peach St", "I 90 East"), in a small label at the bottom of the map
     * (docs/next-round.md item 22: the route line covers that road's own name on the map); null where
     * the road has no name, off the route and in ARRIVED.
     */
    val currentRoad: String? = null,
)

data class ResumeUi(val destinationName: String, val stopsLeft: Int)

/** Everything the drive feature draws. */
data class DriveUi(
    val planning: RouteSheetUi? = null,
    val stopsEditor: StopsEditorUi? = null,
    val guidance: GuidanceUi? = null,
    val resume: ResumeUi? = null,
)

sealed interface DriveEvent {
    /** Sent to the session unchanged (route choice, options, start, simulate, end, stops, retry, done, resume). */
    data class Nav(val intent: NavIntent) : DriveEvent
    data object Recenter : DriveEvent
    data object ShowOverview : DriveEvent
    data object OpenStopsEditor : DriveEvent
    data object CloseStopsEditor : DriveEvent
    /** Opens search in ADD_STOP mode; the place comes back as NavIntent.AddStop. */
    data object SearchForStop : DriveEvent
    /** Opens search in SET_START mode; the place comes back as NavIntent.SetOrigin. */
    data object ChooseStart : DriveEvent
    /**
     * The route sheet's "Allow precise location to start guidance" was tapped (section 4: Start is
     * off without precise location and the app offers to ask for it again): the app asks for precise
     * location, or points to the app's settings when Android no longer shows the question.
     */
    data object AskPreciseLocation : DriveEvent
    /**
     * Item 32: the speed sign or the speed beside it was tapped ([SpeedUi.canReport]): the app offers the
     * posted limits to pick from; the pick is kept for later, nothing is sent while driving.
     */
    data object SpeedLimitTapped : DriveEvent
}

data class GuidanceNotificationText(val title: String, val text: String)

/**
 * Which one-shot card is on screen: the latest StopReached, Arrived or RerouteFailed event, for
 * [cardMs] (guidance.json arrival.stop_card_s) after it came. Times are monotonic milliseconds.
 */
class EventCards(private val cardMs: Long) {
    fun onEvent(event: TripEvent, nowMs: Long): Unit = timer(cardMs).onEvent(event, nowMs)
    fun shown(nowMs: Long): TripEvent? = timer(cardMs).shown(nowMs)
}

/** Pure: TripState (+ units, clock, camera) to what is drawn. Rules D1-D7. */
object DrivePresenter {
    /**
     * [shownEvent]: EventCards.shown(now). [locationAllowed]: precise location is granted (an
     * approximate-only grant counts as not allowed); without it Start is off with a reason.
     */
    fun present(
        trip: TripState,
        units: UnitFormatter,
        nowEpochMs: Long,
        zone: ZoneId,
        camera: CameraState,
        stopsEditorOpen: Boolean,
        shownEvent: TripEvent?,
        locationAllowed: Boolean,
    ): DriveUi = Presentation.present(trip, units, nowEpochMs, zone, camera, stopsEditorOpen, shownEvent, locationAllowed)

    /** Title and text of the guidance notification; null outside GUIDING, REROUTING and ARRIVED. */
    fun notification(trip: TripState, units: UnitFormatter): GuidanceNotificationText? = Presentation.notification(trip, units)
}
