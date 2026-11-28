package io.github.spysyweeb.beamline.feature.drive

import io.github.spysyweeb.beamline.api.map.CameraMode
import io.github.spysyweeb.beamline.api.map.CameraState
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.nav.GpsState
import io.github.spysyweeb.beamline.api.nav.Guidance
import io.github.spysyweeb.beamline.api.nav.LaneWarningView
import io.github.spysyweeb.beamline.api.nav.LiveBanner
import io.github.spysyweeb.beamline.api.nav.ManeuverKind
import io.github.spysyweeb.beamline.api.nav.Origin
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.ProblemKind
import io.github.spysyweeb.beamline.api.nav.TripEvent
import io.github.spysyweeb.beamline.api.nav.TripState
import io.github.spysyweeb.beamline.units.UnitFormatter
import io.github.spysyweeb.beamline.units.UnitSystem
import java.time.ZoneId
import kotlin.math.roundToLong

/**
 * The body of [DrivePresenter] (docs/architecture.md 3.9, rules D1-D7). Pure: no Android, no clock of
 * its own, no state; every text of a distance, a duration or a clock time comes from [UnitFormatter].
 */
internal object Presentation {

    /**
     * Rule D1: the roads of these maneuvers read "onto US 19 / Peach St". The contract names turns and
     * ramps (not exits and forks); merges and roundabouts lead onto a road as turns do, so they read
     * the same ("Merge" onto "I 90"). Exits, forks, depart, arrive, ferry and other: the names alone.
     */
    val ONTO_KINDS: Set<ManeuverKind> = setOf(
        ManeuverKind.STRAIGHT,
        ManeuverKind.SLIGHT_LEFT, ManeuverKind.LEFT, ManeuverKind.SHARP_LEFT,
        ManeuverKind.SLIGHT_RIGHT, ManeuverKind.RIGHT, ManeuverKind.SHARP_RIGHT,
        ManeuverKind.UTURN_LEFT, ManeuverKind.UTURN_RIGHT,
        ManeuverKind.RAMP_LEFT, ManeuverKind.RAMP_RIGHT, ManeuverKind.RAMP_STRAIGHT,
        ManeuverKind.MERGE, ManeuverKind.MERGE_LEFT, ManeuverKind.MERGE_RIGHT,
        ManeuverKind.ROUNDABOUT, ManeuverKind.ROUNDABOUT_EXIT,
    )

    /**
     * At most this many road names on the banner: the engine gives the left turn of the exit 24 trip
     * three ("US 19", "Peach St", "General Strong Vincent Memorial Hwy"); the sketch and the
     * end-to-end test (section 8, row 4) read "onto US 19 / Peach St".
     */
    const val MAX_ROADS = 2

    /**
     * Rule D1: a name of at most this many characters ("Peach St", "US 19", "Waterford") is kept on
     * one line of the banner; a longer one may still wrap between its words.
     */
    const val SHORT_NAME_CHARS = 16

    /** The no-break space that keeps a short name together (D1). */
    const val NO_BREAK_SPACE = ' '

    /** Rule D1: a short name never breaks across two lines: its spaces become no-break spaces. */
    fun keepTogether(name: String): String =
        if (name.length <= SHORT_NAME_CHARS) name.replace(' ', NO_BREAK_SPACE) else name

    /** The arrival maneuvers (rule B7; play test P34: the notification's title keeps their plain words). */
    private val ARRIVE_KINDS = setOf(ManeuverKind.ARRIVE, ManeuverKind.ARRIVE_LEFT, ManeuverKind.ARRIVE_RIGHT)

    /** Phases in which stops can be added, removed and moved (rule C1). */
    private val EDIT_PHASES = setOf(Phase.PLANNING, Phase.CHOOSING, Phase.GUIDING, Phase.REROUTING)

    fun present(
        trip: TripState,
        units: UnitFormatter,
        nowEpochMs: Long,
        zone: ZoneId,
        camera: CameraState,
        stopsEditorOpen: Boolean,
        shownEvent: TripEvent?,
        locationAllowed: Boolean,
    ): DriveUi = when (trip.phase) {
        Phase.IDLE -> DriveUi()
        Phase.RESUME_OFFERED -> DriveUi(resume = resume(trip))
        Phase.PLANNING, Phase.CHOOSING -> DriveUi(
            planning = routeSheet(trip, units, nowEpochMs, zone, locationAllowed),
            stopsEditor = if (stopsEditorOpen) stopsEditor(trip, shownEvent) else null,
        )
        Phase.GUIDING, Phase.REROUTING -> {
            val g = guidance(trip, units, nowEpochMs, zone, camera, shownEvent)
            DriveUi(guidance = g, stopsEditor = if (stopsEditorOpen) stopsEditor(trip, shownEvent, g?.tripBar) else null)
        }
        // Stops cannot change once arrived (C1): no stops editor.
        Phase.ARRIVED -> DriveUi(guidance = guidance(trip, units, nowEpochMs, zone, camera, shownEvent))
    }

    // ---- route sheet and stops (D4, D5, C1, C6) ----

    fun routeSheet(trip: TripState, units: UnitFormatter, nowEpochMs: Long, zone: ZoneId, locationAllowed: Boolean): RouteSheetUi {
        val labels = trip.offeredTypes.associate { it.id to it.label }
        // item 47, P23: each slower card says by how much, in the minutes the cards show ("6 min" and
        // "7 min": "+1 min"), from the fastest card shown
        val fastestMin = trip.choices.filter { c -> c.typeIds.any { it in labels } }.minOfOrNull { shownMinutes(it.timeS) }
        val cards = trip.choices.mapNotNull { choice ->
            // D5: a type the presets do not offer (Eco) is never named; a route only such a type gave is not shown.
            val names = choice.typeIds.mapNotNull { labels[it] }
            if (names.isEmpty()) return@mapNotNull null
            val slowerMin = if (fastestMin == null) 0L else shownMinutes(choice.timeS) - fastestMin
            RouteCardUi(
                choiceId = choice.id,
                labels = names.joinToString(DriveTexts.LABEL_SEPARATOR),
                durationText = units.duration(choice.timeS),
                distanceText = units.distance(choice.lengthM),
                arriveText = DriveTexts.ARRIVE + units.clockTime(arrivalEpochMs(nowEpochMs, choice.timeS), zone),
                viaText = choice.via.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
                    ?.joinToString(DriveTexts.VIA_SEPARATOR, prefix = DriveTexts.VIA),
                notes = listOfNotNull(
                    DriveTexts.NOTE_TOLLS.takeIf { choice.hasTolls },
                    DriveTexts.NOTE_HIGHWAYS.takeIf { choice.hasHighways },
                ),
                selected = choice.id == trip.selectedChoiceId,
                slowerText = if (slowerMin >= 1) "+" + units.duration(slowerMin * 60.0) else null,
            )
        }
        val selected = trip.choices.firstOrNull { c -> c.id == trip.selectedChoiceId && cards.any { it.choiceId == c.id } }
        return RouteSheetUi(
            fromText = fromText(trip.origin),
            stops = stopRows(trip),
            cards = cards,
            avoidHighways = trip.options.avoidHighways,
            avoidTolls = trip.options.avoidTolls,
            busy = busy(trip),
            problem = trip.problem?.text,
            canRetry = trip.problem?.canRetry == true,
            warnings = selected?.warnings.orEmpty(),
            canStart = selected != null && trip.phase == Phase.CHOOSING && !trip.replanning && locationAllowed,
            startBlockedText = if (selected != null && !locationAllowed) DriveTexts.START_BLOCKED_NO_PRECISE_LOCATION else null,
            canChooseStart = trip.problem?.kind == io.github.spysyweeb.beamline.api.nav.ProblemKind.NO_POSITION,
            startNote = if (trip.origin is Origin.FromPlace && selected != null) DriveTexts.STARTS_FROM_HERE else null,
            // P19, P20: "From Butler · 1 stop" under "To ..." below full height (the full height has the From row and the stops)
            contextLine = listOfNotNull(
                (trip.origin as? Origin.FromPlace)?.let { o -> DriveTexts.FROM + " " + fromText(o) },
                trip.stops.dropLast(1).count { !it.reached }.takeIf { it > 0 }?.let(DriveTexts::stopsCount),
            ).joinToString(DriveTexts.LABEL_SEPARATOR).ifEmpty { null },
            steps = selected?.steps.orEmpty().map { s ->
                val roads = roadsText(s.kind, s.roads.map(::keepTogether))
                StepUi(s.kind, listOfNotNull(keepTogether(s.primary), roads).joinToString(" "), if (s.afterM >= 1.0) units.distance(s.afterM) else "", s.point)
            },
        )
    }

    fun fromText(origin: Origin): String = when (origin) {
        Origin.CurrentPosition -> DriveTexts.YOUR_POSITION
        is Origin.FromPlace -> origin.place.name
    }

    /** D7: busy while PLANNING and while an engine request for the current revision runs. */
    fun busy(trip: TripState): Boolean = trip.phase == Phase.PLANNING || trip.replanning

    fun stopsEditor(trip: TripState, shownEvent: TripEvent? = null, bar: TripBarUi? = null): StopsEditorUi {
        val open = trip.stops.count { !it.reached }
        return StopsEditorUi(
            rows = stopRows(trip),
            // C6: "Best order" is offered when at least 3 stops are not reached.
            canOptimize = trip.phase in EDIT_PHASES && open >= 3,
            busy = busy(trip),
            // play test P49: the editor takes the route sheet's place, so it says what the sheet would: the trip's
            // problem ("These stops are already in the best order.", a failed change), else that Best order changed
            // the order (EventCards' own slot for StopsReordered). The app's Undo offer goes over it (canUndo).
            note = trip.problem?.text ?: DriveTexts.STOPS_REORDERED.takeIf { shownEvent == TripEvent.StopsReordered },
            // while guided the editor takes the trip bar's place: its arrival and remaining time ("Arrive 8:42 PM ·
            // 1 h 7 min"), or "No route from here" (P5), so the effect of a change can be read at once
            arriveText = bar?.let { it.noRouteText ?: (it.arriveText + DriveTexts.LABEL_SEPARATOR + it.remainingTimeText) },
        )
    }

    /**
     * Rule C1: reached stops cannot change; whatever stop ends up last is the destination, so the last
     * row cannot move down and can move up. The only stop left to drive to cannot be removed (End
     * cancels the trip instead).
     */
    fun stopRows(trip: TripState): List<StopRowUi> {
        val stops = trip.stops
        val open = stops.count { !it.reached }
        val editable = trip.phase in EDIT_PHASES
        return stops.mapIndexed { i, stop ->
            val change = editable && !stop.reached
            StopRowUi(
                index = i,
                title = stop.place.name,
                subtitle = stop.place.subtitle,
                reached = stop.reached,
                canRemove = change && open > 1,
                canMoveUp = change && i > 0 && !stops[i - 1].reached,
                canMoveDown = change && i < stops.lastIndex && !stops[i + 1].reached,
            )
        }
    }

    fun resume(trip: TripState): ResumeUi = ResumeUi(
        destinationName = trip.stops.lastOrNull()?.place?.name.orEmpty(),
        stopsLeft = trip.stops.count { !it.reached },
        // play test P27: a simulated drive offered again after the app was killed says so (Resume drives it simulated)
        simulated = trip.resumeSimulated,
    )

    // ---- guidance (D1, D2, D3, D7) ----

    fun guidance(
        trip: TripState,
        units: UnitFormatter,
        nowEpochMs: Long,
        zone: ZoneId,
        camera: CameraState,
        shownEvent: TripEvent?,
    ): GuidanceUi? {
        val g = trip.guidance ?: return null
        return GuidanceUi(
            // D7: no banner in REROUTING; in ARRIVED the arrival card has the top (this track's choice).
            banner = if (trip.phase == Phase.GUIDING) g.banner?.let { withLaneWarning(banner(it, units), g.laneWarning) } else null,
            status = status(trip, shownEvent, units),
            tripBar = tripBar(trip, g, units, nowEpochMs, zone),
            // K4, K5: Recenter brings the camera back from a pan and from the overview.
            showRecenter = trip.phase != Phase.ARRIVED &&
                (camera.showRecenter || camera.mode == CameraMode.GUIDE_FREE || camera.mode == CameraMode.GUIDE_OVERVIEW),
            speed = speed(trip, g, units.system),
            // item 22: the road being driven, while the car is on the route
            currentRoad = g.currentRoad?.takeIf { trip.phase == Phase.GUIDING && !g.offRouteSuspected }?.let(::keepTogether),
        )
    }

    // ---- the speed-limit sign and the current speed (D8; owner, 2026-11-17) ----

    const val MPS_TO_MPH = 2.2369362920544
    const val MPS_TO_KMH = 3.6
    const val KMH_PER_MPH = 1.609344

    /** Over the limit by this much (in the units shown) the number turns yellow, then red: 5 and 10 mph, 8 and 16 km/h. */
    fun overSteps(system: UnitSystem): Pair<Int, Int> = if (system == UnitSystem.IMPERIAL) 5 to 10 else 8 to 16

    /** The sign's number for a limit of [kmh] km/h: whole mph or km/h (the engine gives 89 km/h for a 55 mph sign). */
    fun limitShown(kmh: Double, system: UnitSystem): Int =
        Math.round(if (system == UnitSystem.IMPERIAL) kmh / KMH_PER_MPH else kmh).toInt()

    fun speedShown(mps: Double, system: UnitSystem): Int =
        Math.round(mps * if (system == UnitSystem.IMPERIAL) MPS_TO_MPH else MPS_TO_KMH).toInt()

    /** Colour of the speed shown against the limit shown (both whole numbers in the units shown). */
    fun over(speed: Int, limit: Int?, system: UnitSystem): SpeedOver {
        if (limit == null) return SpeedOver.NO
        val (yellow, red) = overSteps(system)
        return when {
            speed - limit >= red -> SpeedOver.RED
            speed - limit >= yellow -> SpeedOver.YELLOW
            else -> SpeedOver.NO
        }
    }

    /**
     * Rule D8: in GUIDING and REROUTING (never while browsing or choosing, and not in ARRIVED). The
     * sign while the stretch's limit is known and the car is on the route (not in REROUTING, not
     * while off-route fixes are being counted); the speed beside it all the time, a dash unless the
     * GPS is GOOD and the fixes carry a speed.
     */
    fun speed(trip: TripState, g: Guidance, system: UnitSystem): SpeedUi? {
        if (trip.phase != Phase.GUIDING && trip.phase != Phase.REROUTING) return null
        val onRoute = trip.phase == Phase.GUIDING && !g.offRouteSuspected
        val limit = g.speedLimitKmh?.takeIf { onRoute && it > 0.0 }?.let { limitShown(it, system) }
        val mps = g.vehicle?.speedMps?.takeIf { trip.gps == GpsState.GOOD && it.isFinite() && it >= 0.0 }
        val unit = if (system == UnitSystem.IMPERIAL) DriveTexts.MPH else DriveTexts.KMH
        if (mps == null) return SpeedUi(limit?.toString(), DriveTexts.NO_SPEED, unit, SpeedOver.NO)
        val shown = speedShown(mps, system)
        return SpeedUi(limit?.toString(), shown.toString(), unit, over(shown, limit, system))
    }

    /** Rule D1. The live distance is already held against jitter by the session (B3). */
    fun banner(live: LiveBanner, units: UnitFormatter): BannerUi {
        val v = live.view
        return BannerUi(
            kind = v.kind,
            roundaboutExit = v.roundaboutExit,
            distanceText = units.distance(live.distanceM),
            primary = keepTogether(v.primary),
            roadsText = roadsText(v.kind, v.roads.map(::keepTogether)),
            towardsText = towardsText(v.towards.map(::keepTogether)),
            then = v.following?.let { ThenUi(it.kind, units.distanceIn(it.distanceM)) },
            lanes = if (live.showLanes) v.lanes?.takeIf { it.isNotEmpty() }?.map { LaneUi(it.arrows, it.mark, it.usedArrow) } else null,
            laneDistanceM = if (live.showLanes && !v.lanes.isNullOrEmpty()) live.distanceM else null,
        )
    }

    /**
     * Rule B9 (batch C1): while the session shows a lane warning, the banner's lane row is that
     * intersection's lanes (the lanes that go on marked) with its one line under it; the banner's
     * own maneuver, distance and chip stay.
     */
    fun withLaneWarning(b: BannerUi, w: LaneWarningView?): BannerUi {
        if (w == null || w.lanes.isEmpty()) return b
        return b.copy(lanes = w.lanes.map { LaneUi(it.arrows, it.mark, it.usedArrow) }, laneNote = w.text)
    }

    fun roadsText(kind: ManeuverKind, roads: List<String>): String? {
        val names = roads.filter { it.isNotBlank() }.take(MAX_ROADS)
        if (names.isEmpty()) return null
        return (if (kind in ONTO_KINDS) DriveTexts.ONTO else "") + names.joinToString(DriveTexts.ROAD_SEPARATOR)
    }

    fun towardsText(towards: List<String>): String? =
        towards.filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }
            ?.joinToString(DriveTexts.TOWARDS_SEPARATOR, prefix = DriveTexts.TOWARD)

    /**
     * Rule D2: Arrived > StopReached (card time) > RerouteFailed > Rerouting > GpsLost > WaitingForGps >
     * GpsWeak > OnRoute. RerouteFailed comes from the trip's problem while guided (a failed re-route,
     * O7, O8; a stop or option change that failed, C3), so it ends with the problem.
     */
    /** The arrival card names the distance to the place when the route ends farther than this from it (docs/architecture.md 2.11 "Every request" 2). */
    const val DESTINATION_AWAY_OVER_M = 100.0

    /**
     * The straight line from the end of the route to the destination's place, when it is over
     * [DESTINATION_AWAY_OVER_M]: a place more than 100 m from any street (inside a large car park)
     * is reached at the street it is tied to, up to 300 m away. Null otherwise.
     */
    fun destinationAwayM(trip: TripState): Double? {
        val end = trip.guidance?.route?.geometry?.lastOrNull() ?: return null
        val place = trip.stops.lastOrNull()?.place?.point ?: return null
        val r = 6_371_008.8
        val p1 = Math.toRadians(end.lat)
        val p2 = Math.toRadians(place.lat)
        val dp = p2 - p1
        val dl = Math.toRadians(place.lon - end.lon)
        val h = Math.sin(dp / 2) * Math.sin(dp / 2) + Math.cos(p1) * Math.cos(p2) * Math.sin(dl / 2) * Math.sin(dl / 2)
        val d = 2 * r * Math.asin(Math.min(1.0, Math.sqrt(h)))
        return d.takeIf { it > DESTINATION_AWAY_OVER_M }
    }

    fun status(trip: TripState, shownEvent: TripEvent?, units: UnitFormatter? = null): GuidanceStatusUi {
        val problem = trip.problem
        return when {
            trip.phase == Phase.ARRIVED -> GuidanceStatusUi.Arrived(
                name = (shownEvent as? TripEvent.Arrived)?.name ?: trip.stops.lastOrNull()?.place?.name.orEmpty(),
                sideText = sideText(trip.guidance?.banner?.view?.kind),
                awayText = units?.let { u -> destinationAwayM(trip)?.let { DriveTexts.destinationAway(u.distance(it)) } },
            )
            shownEvent is TripEvent.StopReached -> GuidanceStatusUi.StopReached(shownEvent.name)
            problem != null -> GuidanceStatusUi.RerouteFailed(problem.text, problem.canRetry)
            trip.phase == Phase.REROUTING -> GuidanceStatusUi.Rerouting
            trip.gps == GpsState.LOST -> GuidanceStatusUi.GpsLost
            // OFF while guided does not happen (the session collects from Start); shown as waiting.
            trip.gps == GpsState.WAITING || trip.gps == GpsState.OFF -> GuidanceStatusUi.WaitingForGps
            // D7: after a new route (O6, C3) the card stays until the new route's first banner. The
            // session sets GUIDING at the answer and has no banner until a fix on the new route, so a
            // car still off it (the wrongturn detour: four re-routes 10 s apart) had an empty top.
            trip.phase == Phase.GUIDING && trip.guidance?.banner == null -> GuidanceStatusUi.Rerouting
            trip.gps == GpsState.WEAK -> GuidanceStatusUi.GpsWeak
            else -> GuidanceStatusUi.OnRoute
        }
    }

    fun sideText(kind: ManeuverKind?): String? = when (kind) {
        ManeuverKind.ARRIVE_LEFT -> DriveTexts.SIDE_LEFT
        ManeuverKind.ARRIVE_RIGHT -> DriveTexts.SIDE_RIGHT
        else -> null
    }

    /**
     * Play test P5: no route can be planned from the car (outside the installed maps, rule O8: the only
     * OUTSIDE_INSTALLED_MAPS problem without Retry while re-routing; a failed stop change keeps Retry and the bar's times).
     */
    fun noRouteFromHere(trip: TripState): Boolean =
        trip.phase == Phase.REROUTING && trip.problem?.kind == ProblemKind.OUTSIDE_INSTALLED_MAPS && trip.problem?.canRetry == false

    /** Rule D3. */
    fun tripBar(trip: TripState, g: Guidance, units: UnitFormatter, nowEpochMs: Long, zone: ZoneId): TripBarUi {
        val next = g.nextStopIndex
        // play test P5: the old route's arrival, times and next stop are not the trip's: the bar says so instead
        val noRoute = noRouteFromHere(trip)
        return TripBarUi(
            arriveText = DriveTexts.ARRIVE + units.clockTime(arrivalEpochMs(nowEpochMs, g.remainingS), zone),
            remainingTimeText = units.duration(g.remainingS),
            remainingDistanceText = units.distance(g.remainingM),
            // "Stop n" counts every stop of the trip, reached ones included, as the stops editor numbers them.
            nextStopText = if (!noRoute && next != null && next in trip.stops.indices && next < trip.stops.lastIndex) {
                DriveTexts.nextStop(next + 1, units.distance(g.toNextStopM))
            } else {
                null
            },
            simulated = g.simulated,
            noRouteText = DriveTexts.NO_ROUTE_FROM_HERE.takeIf { noRoute },
        )
    }

    /**
     * now + [seconds], to the nearest minute: the clock shows hours and minutes, and the duration
     * beside it is rounded to the nearest minute too (units.json), so "12 min" and the clock agree.
     */
    fun arrivalEpochMs(nowEpochMs: Long, seconds: Double): Long {
        val ms = if (seconds.isFinite() && seconds > 0) (seconds * 1000.0).roundToLong() else 0L
        return Math.floorDiv(nowEpochMs + ms + 30_000L, 60_000L) * 60_000L
    }

    // ---- notification (D6) ----

    fun statusText(status: GuidanceStatusUi): String? = when (status) {
        GuidanceStatusUi.OnRoute -> null
        GuidanceStatusUi.WaitingForGps -> DriveTexts.WAITING_FOR_GPS
        GuidanceStatusUi.Rerouting -> DriveTexts.REROUTING
        is GuidanceStatusUi.RerouteFailed -> status.text
        GuidanceStatusUi.GpsWeak -> DriveTexts.GPS_WEAK
        GuidanceStatusUi.GpsLost -> DriveTexts.GPS_LOST
        is GuidanceStatusUi.StopReached -> status.name
        is GuidanceStatusUi.Arrived -> DriveTexts.arrivedAt(status.name)
    }

    /**
     * Rule D6. GUIDING: title "Exit 24 · 0.3 mi" (primary and the banner's distance), text the roads
     * as the banner writes them, or the status text when the status is not OnRoute; an arrival (play test
     * P34) keeps its plain words and the distance as the title ("Arrive, on the right · 350 ft") and names
     * the stop as the text (a dropped pin, MAP_PIN, has no name of its own: no text). REROUTING (no banner
     * on screen, D7): title "Finding a new route...", or "No route from here" outside the installed maps
     * (P5, O8), text the failure or "To <destination>". ARRIVED: "Arrived at <destination>", text the
     * side. Only the stops' names are used, never a subtitle or a point (PV6).
     */
    fun notification(trip: TripState, units: UnitFormatter): GuidanceNotificationText? {
        val destination = trip.stops.lastOrNull()?.place?.name?.takeIf { it.isNotBlank() }
        return when (trip.phase) {
            Phase.ARRIVED -> GuidanceNotificationText(
                title = destination?.let(DriveTexts::arrivedAt) ?: DriveTexts.ARRIVED,
                text = sideText(trip.guidance?.banner?.view?.kind).orEmpty(),
            )
            Phase.REROUTING -> GuidanceNotificationText(
                // play test P5: nothing is being searched outside the installed maps; the shade says what the bar says
                title = if (noRouteFromHere(trip)) DriveTexts.NO_ROUTE_FROM_HERE else DriveTexts.REROUTING,
                text = trip.problem?.text ?: destination?.let(DriveTexts::toDestination).orEmpty(),
            )
            Phase.GUIDING -> {
                val live = trip.guidance?.banner
                val statusText = statusText(status(trip, null))
                if (live == null) {
                    GuidanceNotificationText(
                        title = destination?.let(DriveTexts::toDestination) ?: DriveTexts.GUIDANCE,
                        text = statusText.orEmpty(),
                    )
                } else {
                    val v = live.view
                    // play test P34 (review): the banner names the stop now ("Arrive on the left at Erie Zoo"); an arrival
                    // keeps its side and distance in the one-line title, the stop's name goes below
                    val arriving = v.kind in ARRIVE_KINDS
                    val stopName = trip.guidance?.nextStopIndex?.let { trip.stops.getOrNull(it) }?.place
                        ?.takeIf { it.source != PlaceSource.MAP_PIN }?.name?.takeIf { it.isNotBlank() }
                    GuidanceNotificationText(
                        title = (if (arriving) DriveTexts.arriveWords(v.kind) else v.primary) + DriveTexts.LABEL_SEPARATOR + units.distance(live.distanceM),
                        text = statusText ?: (if (arriving) stopName else null) ?: roadsText(v.kind, v.roads) ?: towardsText(v.towards) ?: "",
                    )
                }
            }
            Phase.IDLE, Phase.PLANNING, Phase.CHOOSING, Phase.RESUME_OFFERED -> null
        }
    }
}

/**
 * Item 47, P23: the whole minutes a duration text shows (units.json: rounded to the nearest minute,
 * UnitTable.duration), so "+1 min" appears whenever two cards read a minute apart (raw seconds said nothing for
 * "6 min" against "7 min", 50 s apart, and "+1 min" for "1 h 58 min" against "2 h").
 */
internal fun shownMinutes(s: Double): Long = if (s.isNaN() || s < 0) 0L else kotlin.math.floor(s / 60.0 + 0.5).toLong()
