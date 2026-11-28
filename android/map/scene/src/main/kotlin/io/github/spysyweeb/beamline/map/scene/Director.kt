package io.github.spysyweeb.beamline.map.scene

import io.github.spysyweeb.beamline.api.map.CameraMode
import io.github.spysyweeb.beamline.api.map.CameraState
import io.github.spysyweeb.beamline.api.map.CameraTarget
import io.github.spysyweeb.beamline.api.map.EdgeInsetsDp
import io.github.spysyweeb.beamline.api.map.MapGesture
import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.nav.Guidance
import io.github.spysyweeb.beamline.api.nav.Motion
import io.github.spysyweeb.beamline.api.nav.Origin
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.TripState
import io.github.spysyweeb.beamline.location.Clock
import io.github.spysyweeb.beamline.location.PuckState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What [CameraDirector] does: the camera modes and moves of rules K1-K7 (docs/architecture.md 3.7).
 *
 * The camera is moved by the app only through [target]. A null target means "leave the camera where
 * the user put it": in BROWSE (K1; except right after guidance, for a picked place, and for a place
 * tapped on the map once the free area stopped holding its pin (K1b), until a gesture), in GUIDE_FREE (K4), and in PREVIEW or ARRIVED after the user
 * moved the map. Every other mode always has a target, recomputed whenever one of its inputs
 * changes (viewport, insets, trip, puck), so re-applying the current target (after a rotation, a
 * style reload) is always right.
 *
 * Animation: a change of mode, of viewport or of the overlay insets animates over transition_ms;
 * a follow move animates over the time between the last two positions (K3: the vehicle's glide,
 * the same as VehicleMarker.Arrow.glideMs; for the browsing puck the time between its last two
 * updates, the same 3 s limit).
 *
 * Adding a stop on the map (K8, item 51): while [stopView] holds points, PREVIEW and the guide modes
 * fit them top-down, north up (guiding: with the vehicle, re-fitted at most every STOP_REFIT_MS when it
 * leaves the middle of the view); a gesture leaves the camera until the next points, Recenter or
 * recenter_after_s; K4/K5's return to the follow waits until adding a stop ends. Since 0.1.41 (item
 * 54b) the points may come with their own padding (the Add stop search's typing view), and without
 * room for the fit the camera stays.
 *
 * Timers (K4, K5) are checked in [tick] against [clock]. All calls come from one thread (the main
 * thread in the app); the flows may be read from any thread.
 */
internal class Director(private val rules: CameraRules, private val clock: Clock) {
    private val compassFilter = CompassFilter()
    private val _target = MutableStateFlow<CameraTarget?>(null)
    val target: StateFlow<CameraTarget?> = _target.asStateFlow()
    private val _state = MutableStateFlow(CameraState())
    val state: StateFlow<CameraState> = _state.asStateFlow()

    private var heightDp = 0f
    private var widthDp = 0f
    private var insets = EdgeInsetsDp()
    private var trip = TripState()
    private var puck: PuckState? = null

    // K1a (the owner, 2026-11-24): browsing may be turned; my-location steps through following,
    // following turned by the phone's compass, and following north up (0.1.28: one button).
    /** The bearing browsing's own moves use: 0 (north up), the user's own turn, or the compass's. */
    private var browseBearing = 0.0
    /** The map's bearing when the camera last stopped (PackMap). */
    private var mapBearing = 0.0

    /** K1a: browsing, my-location turned the map north up where it was; the next tap follows (until a gesture). */
    private var browseNorth = false

    /**
     * K1a (0.1.33, the owner: "the rotation should be done in less than a second, but it should be
     * smooth"): a turn my-location started runs until this time; the follow updates and the compass
     * readings wait for it (before, each 1 Hz fix re-timed it and stretched it past a second).
     */
    private var turnUntilMs = 0L
    private val _compassFollow = MutableStateFlow(false)
    /** True while browsing follows the phone's compass (the compass button shows it). */
    val compassFollow: StateFlow<Boolean> = _compassFollow.asStateFlow()

    private var mode = CameraMode.BROWSE

    /** BROWSE right after guidance ended: one move back to top-down, north up (cleared by a gesture). */
    private var browseReset: GeoPoint? = null

    /** BROWSE: a place picked from a search, a list or a sheet, shown at follow_zoom (K1; cleared by a gesture). */
    private var place: GeoPoint? = null

    /** BROWSE: the search results on the map, nearest first, and the zoom they may be fitted at (S11; cleared like [place]). */
    private var results: List<GeoPoint>? = null
    private var resultsMinZoom = 0.0

    /** PREVIEW or ARRIVED: the user moved the map; leave the camera until the content changes. */
    private var userMoved = false

    /** PREVIEW or ARRIVED: my-location asked to look at this point (top-down) instead of the content. */
    private var lookAt: GeoPoint? = null
    private var lookAtZoom = 0.0

    /** ARRIVED: the car's position the arrival view frames with the destination (K6, P4); taken again past ARRIVED_REFRAME_M. */
    private var arrivedCar: GeoPoint? = null

    /** K2a (P46): the place whose window Back from directions reopens; shown once the trip has ended. */
    private var afterRoute: GeoPoint? = null

    /** K8 (item 51): the points the stop view fits (the stop results and where they join, or the way to one); null: none. */
    private var stopView: List<GeoPoint>? = null
    /** K8 (item 54b): the padding the stop view asked for on each side (the Add stop search's typing view); null: preview.padding_dp. */
    private var stopPadding: Float? = null
    /** K8: the box the stop view was last fitted to, and when (the guided re-fit's reference). */
    private var stopFitBox: Bounds? = null
    private var stopFitAtMs = 0L

    /** BROWSE: a place tapped on the map whose sheet is open (K1b, P8); cleared like [place]. */
    private var tapped: GeoPoint? = null
    /** K1b: the free area stopped holding [tapped]'s pin once; it is now centred there at the map's zoom. */
    private var tappedShown = false
    /** K1b: the camera as the map last reported it ([onCameraAt]): the point at the view's middle, the zoom, the bearing. */
    private var seenMiddle: GeoPoint? = null
    private var seenZoom = 0.0
    private var seenBearing = 0.0
    /** Item 54a: the following a tapped place ended (K1b), which the next my-location gives back as it was; null: none. */
    private var followBeforeTap: FollowKind? = null

    /** What PREVIEW fits; a change re-fits even after the user moved the map (new routes to show). */
    private var previewKey: Any? = null

    /** GUIDE_FREE: time of the last gesture (K4). GUIDE_OVERVIEW: time it was entered (K5). */
    private var sinceMs = 0L
    private var overviewBounds: Bounds? = null
    private var overviewRouteId: Long? = null

    // Guidance follow (K3)
    private val glide = VehicleGlide()
    private val bearing = BearingFilter(rules.bearingSmoothing, rules.bearingTurnDeg)
    private var speedMps = 0.0

    // The lean into a turn (docs/next-round.md item 22, guide.lean; [Lean])
    /** The maneuver the lean was last asked for: route id and index of the banner's maneuver. */
    private var leanKey: Pair<Long, Int>? = null
    /** That maneuver's turn, read from the route line once it comes within reach; null: not yet, or no turn. */
    private var leanTurn: Lean.Turn? = null
    /** The pose last applied for it; null: none. */
    private var leanPose: Lean.Pose? = null
    /** The map bearing that pose gave (the vehicle's bearing then, plus the lean). */
    private var leanBearing = 0.0
    /** After the maneuver: the map bearing to stay at until the vehicle has turned as far ([heldBearing]). */
    private var leanHold: Hold? = null

    /** [side]: +1 for a right turn, -1 for a left one. */
    private class Hold(val bearingDeg: Double, val side: Double, val untilMs: Long)

    /** Item 54a: which following a tap ended. */
    private enum class FollowKind { NORTH_UP, COMPASS }

    // Browse follow (K1)
    private var puckAtMs: Long? = null
    private var puckGlideMs = 0
    private var followFirst = true

    fun onViewport(widthDp: Float, heightDp: Float, insets: EdgeInsetsDp) {
        if (widthDp == this.widthDp && heightDp == this.heightDp && insets == this.insets) return
        this.widthDp = widthDp
        this.heightDp = heightDp
        this.insets = insets
        // K1b (P8): the sheet raised over a tapped place (or the bar over it): from now on it is centred above the sheet
        if (mode == CameraMode.BROWSE && tapped != null && !tappedShown) tappedShown = tappedCovered()
        publish(rules.transitionMs)
    }

    fun onTrip(next: TripState) {
        val prev = trip
        trip = next
        val prevKind = kindOf(prev.phase)
        val kind = kindOf(next.phase)
        if (kind != prevKind) {
            enter(kind, prev)
            return
        }
        when (kind) {
            Kind.BROWSE -> Unit
            Kind.PREVIEW -> {
                val key = previewKeyOf(next)
                if (key != previewKey) {
                    previewKey = key
                    userMoved = false
                    lookAt = null
                    publish(rules.transitionMs)
                }
            }
            Kind.GUIDE -> {
                val moved = takeVehicle(next.guidance)
                if (stopView != null) {
                    // K8: no follow moves and no overview re-fit while a stop is being added; a new fit with the
                    // vehicle once it leaves the middle of the view, at most every STOP_REFIT_MS
                    if (moved && !userMoved && leavingFit(next.guidance?.vehicle?.point) && clock.elapsedMs() - stopFitAtMs >= STOP_REFIT_MS) {
                        publish(rules.transitionMs)
                    }
                    return
                }
                if (mode == CameraMode.GUIDE_OVERVIEW && next.guidance?.route?.id != overviewRouteId) {
                    overviewBounds = remainingBounds(next.guidance)
                    overviewRouteId = next.guidance?.route?.id
                    publish(rules.transitionMs)
                } else if (mode == CameraMode.GUIDE_FOLLOW && moved) {
                    publish(if (glide.first) rules.transitionMs else glide.glideMs)
                } else if (mode == CameraMode.GUIDE_FOLLOW) {
                    // Same position; the banner distance may have crossed approach_within_m.
                    publish(rules.transitionMs)
                }
            }
            Kind.ARRIVED -> {
                // K6 (P4): the frame moves on only once the car, moving, is more than ARRIVED_REFRAME_M from
                // where it was framed (a parked car's GPS jumps are left out); ARRIVED has no Recenter, so
                // that also ends a look around
                val car = carPoint(next)
                val framed = arrivedCar
                val moving = next.guidance?.vehicle?.motion != Motion.STOPPED
                if (car != null && (framed == null || (moving && Lean.distanceM(car, framed) > ARRIVED_REFRAME_M))) {
                    arrivedCar = car
                    userMoved = false
                }
                publish(rules.transitionMs)
            }
        }
    }

    fun onPuck(p: PuckState?) {
        val now = clock.elapsedMs()
        val changed = p != null && (puck == null || p.lat != puck!!.lat || p.lon != puck!!.lon)
        if (changed) {
            // The time between the two updates, so the camera keeps moving between 1 Hz fixes (an
            // ease shorter than that stops the camera each second); a longer gap than a glide jumps.
            puckGlideMs = puckAtMs?.let { d -> (now - d).takeIf { it in 1..VehicleGlide.MAX_GLIDE_MS }?.toInt() ?: 0 } ?: 0
            puckAtMs = now
        }
        puck = p
        when (mode) {
            CameraMode.BROWSE_FOLLOW -> if ((changed || _target.value == null) && now >= turnUntilMs) {
                val anim = if (followFirst) rules.transitionMs else puckGlideMs
                publish(anim)
            }
            // Guidance before its first vehicle: the puck stands in (K3 needs a position).
            CameraMode.GUIDE_FOLLOW -> if (changed && trip.guidance?.vehicle == null) publish(rules.transitionMs)
            else -> Unit
        }
    }

    fun onGesture(gesture: MapGesture) {
        if (gesture != MapGesture.CameraMovedByUser) return
        when (mode) {
            CameraMode.BROWSE, CameraMode.BROWSE_FOLLOW -> {
                browseReset = null
                place = null
                results = null
                tapped = null
                tappedShown = false
                followBeforeTap = null   // item 54a: moved by hand, the one-press return ends (K1a as today)
                // K1a: a map no longer centred on the user does not turn with the compass
                _compassFollow.value = false
                browseNorth = false
                turnUntilMs = 0L
                setMode(CameraMode.BROWSE)
            }
            CameraMode.PREVIEW, CameraMode.ARRIVED -> {
                userMoved = true
                lookAt = null
            }
            CameraMode.GUIDE_FOLLOW, CameraMode.GUIDE_OVERVIEW, CameraMode.GUIDE_FREE -> {
                sinceMs = clock.elapsedMs()
                // K8: adding a stop, the camera stays where the user put it (north up, GUIDE_OVERVIEW) until the
                // next points, Recenter or recenter_after_s ([tick])
                if (stopView != null) userMoved = true else setMode(CameraMode.GUIDE_FREE)
            }
        }
        publish(0)
    }

    fun onMyLocation() {
        when (mode) {
            CameraMode.BROWSE, CameraMode.BROWSE_FOLLOW -> {
                browseReset = null
                place = null
                results = null
                tapped = null
                tappedShown = false
                // item 54a: a tapped place ended following: one press gives it back as it was (no north-up-first
                // step: the compass turned the map, not the user's hand)
                val before = followBeforeTap
                followBeforeTap = null
                if (mode == CameraMode.BROWSE && before != null) {
                    val heading = puck?.coneDeg
                    browseNorth = false
                    if (before == FollowKind.COMPASS && heading != null) {
                        browseBearing = heading.toDouble()
                        compassFilter.reset()
                        _compassFollow.value = true
                    } else {
                        _compassFollow.value = false
                        browseBearing = 0.0
                    }
                    followFirst = true
                    // the move to the user runs first: compass readings (publish(0)) and follow updates wait for
                    // it; no fix yet: following waits for the first one, as a first press does
                    turnUntilMs = if (puck != null) clock.elapsedMs() + rules.transitionMs else 0L
                    setMode(CameraMode.BROWSE_FOLLOW)
                    publish(rules.transitionMs, ease = true)
                    return
                }
                // K1a (the owner, 2026-11-24): a map turned by hand and not following turns north up
                // first, where it is; the next tap goes to the user
                if (mode == CameraMode.BROWSE && !browseNorth && kotlin.math.abs(signedDiff(0.0, mapBearing)) >= NORTH_TOLERANCE_DEG) {
                    browseBearing = 0.0
                    browseNorth = true
                    publish(startTurn(mapBearing, 0.0), ease = true)
                    return
                }
                browseNorth = false
                // K1a: not following -> following north up; following -> also turned by the phone's
                // compass (when it has one); turned by the compass -> following north up
                val heading = puck?.coneDeg
                val from = browseBearing
                val wasFollowing = mode == CameraMode.BROWSE_FOLLOW
                if (mode == CameraMode.BROWSE_FOLLOW && !_compassFollow.value && heading != null) {
                    browseBearing = heading.toDouble()
                    compassFilter.reset()
                    _compassFollow.value = true
                } else {
                    _compassFollow.value = false
                    browseBearing = 0.0
                    followFirst = true
                }
                setMode(CameraMode.BROWSE_FOLLOW)
                // already following: a turn in place, timed by its angle; else the move to the user
                publish(if (wasFollowing) startTurn(from, browseBearing) else rules.transitionMs, ease = true)
                return
            }
            CameraMode.PREVIEW, CameraMode.ARRIVED -> {
                val here = trip.guidance?.vehicle?.point ?: puck?.let { GeoPoint(it.lat, it.lon) }
                if (here != null) {
                    lookAt = here
                    lookAtZoom = if (mode == CameraMode.ARRIVED) rules.arrivedZoom else rules.browseFollowZoom
                    userMoved = false
                }
            }
            CameraMode.GUIDE_FOLLOW, CameraMode.GUIDE_FREE, CameraMode.GUIDE_OVERVIEW -> {
                if (stopView != null) return   // K8: the stop window's map does not jump to the follow
                setMode(CameraMode.GUIDE_FOLLOW)
            }
        }
        publish(rules.transitionMs)
    }

    fun onRecenter() {
        when (mode) {
            CameraMode.GUIDE_FREE, CameraMode.GUIDE_OVERVIEW, CameraMode.GUIDE_FOLLOW ->
                // K8: adding a stop, Recenter fits the stop view again with the vehicle where it is now (no follow)
                if (stopView != null) userMoved = false else setMode(CameraMode.GUIDE_FOLLOW)
            CameraMode.PREVIEW, CameraMode.ARRIVED -> {
                userMoved = false
                lookAt = null
            }
            CameraMode.BROWSE, CameraMode.BROWSE_FOLLOW -> return
        }
        publish(rules.transitionMs)
    }

    /** K1a: the map's bearing when the camera stopped; browsing keeps a user's own turn for its next moves. */
    fun onMapBearing(deg: Double) {
        mapBearing = deg
        if (!_compassFollow.value && browsing()) browseBearing = deg
    }

    /**
     * K1a: a compass reading straight from the sensor (the app reads it only while [compassFollow]):
     * the map keeps up with the phone (0.1.29, the owner: "so if they want to look in a direction, it's
     * not making the user dizzy waiting for the screen to keep up"), no animation; since 0.1.30 calmed
     * by [CompassFilter] (the owner: "a little bit of snapping when the compass is moving 1 degree at a
     * time"), which replaced 0.1.29's half-degree steps. Moves under COMPASS_STEP_DEG (none can see
     * them) are skipped.
     */
    fun onHeading(deg: Float) {
        if (!_compassFollow.value || mode != CameraMode.BROWSE_FOLLOW) return
        val smoothed = compassFilter.next(deg.toDouble(), clock.elapsedMs())
        if (clock.elapsedMs() < turnUntilMs) return // the turn to the compass runs first
        if (kotlin.math.abs(signedDiff(smoothed, browseBearing)) < COMPASS_STEP_DEG) return
        browseBearing = smoothed
        publish(0)
    }

    /** K1a: the user began turning the map with two fingers: the compass no longer leads. */
    fun onUserRotate() {
        _compassFollow.value = false
        browseNorth = false
        turnUntilMs = 0L
        followBeforeTap = null   // item 54a: turned by hand, the one-press return ends
    }

    private fun browsing() = mode == CameraMode.BROWSE || mode == CameraMode.BROWSE_FOLLOW

    /**
     * K1: a place picked from a search result, a saved place, a recent or a sheet. Browsing leaves
     * the follow mode and centres on it, top-down at follow_zoom, in the area the insets leave free
     * (above the place sheet); the app caps the zoom outside the states. Choosing a route or ARRIVED
     * looks at it as my-location does there. Guiding never moves for it (the guide camera stays).
     */
    fun onShowPlace(point: GeoPoint) {
        when (mode) {
            CameraMode.BROWSE, CameraMode.BROWSE_FOLLOW -> {
                browseReset = null
                results = null
                tapped = null
                tappedShown = false
                followBeforeTap = null   // item 54a: a picked place ends the one-press return
                place = point
                setMode(CameraMode.BROWSE)
            }
            CameraMode.PREVIEW, CameraMode.ARRIVED -> {
                lookAt = point
                lookAtZoom = rules.browseFollowZoom
                userMoved = false
            }
            CameraMode.GUIDE_FOLLOW, CameraMode.GUIDE_FREE, CameraMode.GUIDE_OVERVIEW -> return
        }
        publish(rules.transitionMs)
    }

    /**
     * K1: the picked place's sheet closed, or a place tapped on the map replaced it. The camera stays
     * where it is: the picked place is no longer a target, so a later change of the insets (the sheet
     * going or changing height) does not carry the camera back to it.
     */
    fun onPlaceLeft() {
        if (place == null && results == null && tapped == null) return
        place = null
        results = null   // S11: a place tapped on the map, or the results closed, ends their fit too
        tapped = null    // K1b: the tapped place's sheet closed, or another place replaced it
        tappedShown = false
        if (mode == CameraMode.BROWSE) publish(0)
    }

    /**
     * K1b (P8): a place tapped on the map whose sheet opened (a POI label, a saved place's marker, a
     * dropped pin). Item 54a (0.1.41, the owner: "the tapped place wins"): while browsing follows the
     * user, the tap ends following where the map is, as a drag does (its bearing and zoom kept, the
     * compass no longer leading; no move of its own), and [followBeforeTap] keeps which following it was
     * for the next [onMyLocation]. On the browsing map the camera then stays while the place's pin is
     * wholly in the area the insets leave free and centres it there, at the map's zoom and bearing,
     * once it is not ([onViewport], [onCameraAt]); from then on it is kept there like a picked place.
     * A trip keeps its camera: nothing then.
     */
    fun onPlaceTapped(point: GeoPoint) {
        if (mode == CameraMode.BROWSE_FOLLOW) {
            // item 54a (P8; the owner, 2026-11-27: "the tapped place wins"): following ends where the map is, its
            // bearing and zoom kept, as a drag; the next my-location gives it back as it was
            followBeforeTap = if (_compassFollow.value) FollowKind.COMPASS else FollowKind.NORTH_UP
            _compassFollow.value = false
            turnUntilMs = 0L
            setMode(CameraMode.BROWSE)
        }
        if (mode != CameraMode.BROWSE) return
        browseReset = null
        place = null
        results = null
        browseNorth = false
        tapped = point
        tappedShown = tappedCovered()
        publish(rules.transitionMs)
    }

    /**
     * K1b: where the camera is: [middle], the point at the middle of the map view, at [zoom], turned to
     * [bearingDeg]; the map's idle callback and each tap or long press (the camera may still glide then).
     * A tapped place not yet centred is checked again: a move begun before the tap may have covered it.
     */
    fun onCameraAt(middle: GeoPoint, zoom: Double, bearingDeg: Double) {
        seenMiddle = middle
        seenZoom = zoom
        seenBearing = bearingDeg
        if (mode == CameraMode.BROWSE && tapped != null && !tappedShown && tappedCovered()) {
            tappedShown = true
            publish(rules.transitionMs)
        }
    }

    /** K1b: the area the insets leave free has room for a pin and its margins (else the camera stays). */
    private fun pinRoom(): Boolean =
        widthDp - insets.start - insets.end >= PIN_W_DP + 2 * IN_VIEW_MARGIN_DP &&
            heightDp - insets.top - insets.bottom >= PIN_H_DP + 2 * IN_VIEW_MARGIN_DP

    /** K1b: [tapped]'s pin is not wholly in the free area now, and that area has room for it. */
    private fun tappedCovered(): Boolean {
        val p = tapped ?: return false
        val mid = seenMiddle ?: return false
        if (widthDp <= 0f || heightDp <= 0f || !pinRoom()) return false
        return !pinInView(p, mid, seenZoom, seenBearing, widthDp, heightDp, insets)
    }

    /**
     * K2a (P46): Back from directions reopens [point]'s window (the app sends this before End). While
     * choosing a route the preview stays; at the change to browsing the camera shows the place as a
     * picked one (K1) instead of following the user. While browsing already: as [onShowPlace].
     */
    fun onShowPlaceAfterRoute(point: GeoPoint) {
        when (mode) {
            CameraMode.PREVIEW -> afterRoute = point
            CameraMode.BROWSE, CameraMode.BROWSE_FOLLOW -> onShowPlace(point)
            else -> Unit
        }
    }

    /**
     * K8 (item 51): adding a stop on the map. While a route is chosen (PREVIEW) or guided the camera
     * looks top-down, north up at [points] in the area the insets leave free, [paddingDp] on each side
     * (null: preview.padding_dp; item 54b's typing view, the route ahead the Add stop search looks along,
     * passes its own); guiding, the vehicle is in the view too (GUIDE_OVERVIEW; a new fit when it leaves
     * the middle of the view, at most every STOP_REFIT_MS). New points fit at once, also after a gesture.
     * No room for the fit in that area ([stopRoom]): the camera stays until [onViewport] gives room.
     * Empty [points]: adding a stop ended, back to K2 (the preview's fit) or K3 (the follow). Other
     * phases: nothing.
     */
    fun onStopView(points: List<GeoPoint>, paddingDp: Float? = null) {
        val kind = kindOf(trip.phase)
        if (kind != Kind.PREVIEW && kind != Kind.GUIDE) {
            stopView = null
            stopPadding = null
            return
        }
        if (points.isEmpty()) {
            if (stopView == null) return
            stopView = null
            stopPadding = null
            stopFitBox = null
            userMoved = false
            lookAt = null
            if (kind == Kind.GUIDE) setMode(CameraMode.GUIDE_FOLLOW)
            publish(rules.transitionMs)
            return
        }
        stopView = points
        stopPadding = paddingDp
        userMoved = false
        lookAt = null
        if (kind == Kind.GUIDE) setMode(CameraMode.GUIDE_OVERVIEW)   // top-down, north up
        publish(rules.transitionMs)
    }

    /**
     * S11: the search results on the map, [points] nearest first. Browsing only (results are not
     * offered while a trip exists): leaves the follow mode and fits the nearest of them, as many as
     * fit at [minZoom] or closer, in the area the insets leave free (above the results list).
     */
    fun onShowResults(points: List<GeoPoint>, minZoom: Double) {
        if (points.isEmpty()) return
        if (mode != CameraMode.BROWSE && mode != CameraMode.BROWSE_FOLLOW) return
        browseReset = null
        place = null
        tapped = null
        tappedShown = false
        followBeforeTap = null   // item 54a: the results end the one-press return
        results = points
        resultsMinZoom = minZoom
        setMode(CameraMode.BROWSE)
        publish(rules.transitionMs)
    }

    fun onOverview() {
        if (kindOf(trip.phase) != Kind.GUIDE) return
        sinceMs = clock.elapsedMs()
        overviewBounds = remainingBounds(trip.guidance)
        overviewRouteId = trip.guidance?.route?.id
        setMode(CameraMode.GUIDE_OVERVIEW)
        publish(rules.transitionMs)
    }

    fun tick() {
        val elapsedS = (clock.elapsedMs() - sinceMs) / 1000.0
        if (stopView != null) {
            // K8: no return to the follow while a stop is being added; a look around ends after
            // recenter_after_s with the stop view (and the vehicle) again
            if (mode == CameraMode.GUIDE_OVERVIEW && userMoved && elapsedS >= rules.recenterAfterS) {
                userMoved = false
                publish(rules.transitionMs)
            }
            return
        }
        val back = when (mode) {
            CameraMode.GUIDE_FREE -> elapsedS >= rules.recenterAfterS
            CameraMode.GUIDE_OVERVIEW -> elapsedS >= rules.overviewReturnAfterS
            else -> false
        }
        if (back) {
            setMode(CameraMode.GUIDE_FOLLOW)
            publish(rules.transitionMs)
        }
    }

    // ---- modes ----------------------------------------------------------------------------

    private enum class Kind { BROWSE, PREVIEW, GUIDE, ARRIVED }

    private fun kindOf(phase: Phase): Kind = when (phase) {
        Phase.IDLE, Phase.RESUME_OFFERED -> Kind.BROWSE
        Phase.PLANNING, Phase.CHOOSING -> Kind.PREVIEW
        Phase.GUIDING, Phase.REROUTING -> Kind.GUIDE
        Phase.ARRIVED -> Kind.ARRIVED
    }

    /** A change of phase group: K1 (back to browsing), K2, K3, K6. */
    private fun enter(kind: Kind, prev: TripState) {
        val wasGuide = kindOf(prev.phase) == Kind.GUIDE
        val wasPreview = kindOf(prev.phase) == Kind.PREVIEW
        // K2a (P46): the place Back from directions reopens, for this change to browsing only
        val reopen = afterRoute?.takeIf { wasPreview && kind == Kind.BROWSE }
        afterRoute = null
        // K1a: a new phase group starts north up, the compass not leading
        browseBearing = 0.0
        _compassFollow.value = false
        browseNorth = false
        userMoved = false
        lookAt = null
        place = null
        results = null
        tapped = null
        tappedShown = false
        followBeforeTap = null   // item 54a: a new phase group ends the one-press return
        arrivedCar = null
        // K8: a change of phase group ends adding a stop (ARRIVED, IDLE)
        stopView = null
        stopPadding = null
        stopFitBox = null
        when (kind) {
            Kind.BROWSE -> {
                // From guidance the camera is tilted and turned: one move back to top-down, north up,
                // where the vehicle was (K1: browsing is top-down and north up). From ARRIVED it already is.
                browseReset = if (wasGuide) lastVehiclePoint(prev) ?: puckPoint() else null
                when {
                    // K2a (P46): Back from directions: the destination's window again, shown as a picked place
                    reopen != null -> {
                        place = reopen
                        setMode(CameraMode.BROWSE)
                    }
                    // K2a (P50): a route closed before it started: the preview's framing means nothing to
                    // browsing (it left the user's position under the search bar); follow the user again,
                    // north up, at follow_zoom, as my-location does
                    wasPreview && puck != null -> {
                        followFirst = true
                        setMode(CameraMode.BROWSE_FOLLOW)
                    }
                    else -> setMode(CameraMode.BROWSE)
                }
            }
            Kind.PREVIEW -> {
                previewKey = previewKeyOf(trip)
                setMode(CameraMode.PREVIEW)
            }
            Kind.GUIDE -> {
                glide.reset()
                bearing.reset()
                speedMps = 0.0
                takeVehicle(trip.guidance)
                setMode(CameraMode.GUIDE_FOLLOW)
            }
            Kind.ARRIVED -> {
                arrivedCar = carPoint(trip)
                setMode(CameraMode.ARRIVED)
            }
        }
        if (kind != Kind.GUIDE) {
            glide.reset()
            bearing.reset()
        }
        publish(rules.transitionMs)
    }

    private fun setMode(m: CameraMode) {
        mode = m
        val recenter = m == CameraMode.GUIDE_FREE || m == CameraMode.GUIDE_OVERVIEW
        val next = CameraState(mode = m, showRecenter = recenter)
        if (next != _state.value) _state.value = next
    }

    /** Takes a new vehicle position into the glide, the bearing filter and the speed; true when it is new. */
    private fun takeVehicle(g: Guidance?): Boolean {
        val v = g?.vehicle
        if (v == null) {
            if (bearing.value == null) startBearing(g?.route?.geometry.orEmpty())?.let(bearing::set)
            return false
        }
        if (!glide.isNew(v)) return false
        glide.update(v)
        val course = v.bearingDeg
        when {
            course == null -> if (bearing.value == null) startBearing(g.route.geometry)?.let(bearing::set)
            bearing.value == null -> bearing.set(course)
            else -> bearing.add(course)
        }
        v.speedMps?.let { speedMps = it }
        return true
    }

    // ---- targets --------------------------------------------------------------------------

    /** K1a (0.1.33): the time of a turn from [from] to [to] degrees, held from now (follow updates and compass readings wait). */
    private fun startTurn(from: Double, to: Double): Int {
        val ms = (TURN_BASE_MS + TURN_MS_PER_DEG * kotlin.math.abs(signedDiff(to, from))).toInt().coerceAtMost(TURN_MAX_MS)
        turnUntilMs = clock.elapsedMs() + ms
        return ms
    }

    private fun publish(animateMs: Int, ease: Boolean = false) {
        val t = when (mode) {
            CameraMode.BROWSE -> browseReset?.let {
                CameraTarget(center = it, zoom = null, bearingDeg = browseBearing, pitchDeg = 0.0, padding = insets, animateMs = animateMs, ease = ease)
            } ?: place?.let {
                CameraTarget(center = it, zoom = rules.browseFollowZoom, bearingDeg = browseBearing, pitchDeg = 0.0, padding = insets, animateMs = animateMs, ease = ease)
            } ?: tapped?.takeIf { tappedShown && pinRoom() }?.let {
                // K1b: the pin's middle at the middle of the free area (its tip PIN_H_DP lower), the user's zoom kept
                CameraTarget(center = it, zoom = null, bearingDeg = browseBearing, pitchDeg = 0.0, padding = insets.copy(top = insets.top + PIN_H_DP), animateMs = animateMs, ease = ease)
            } ?: results?.let { resultsTarget(it, animateMs) }
                ?: if (browseNorth) CameraTarget(center = null, zoom = null, bearingDeg = 0.0, pitchDeg = 0.0, padding = insets, animateMs = animateMs, ease = ease) else null
            CameraMode.BROWSE_FOLLOW -> puckPoint()?.let {
                followFirst = false
                CameraTarget(center = it, zoom = rules.browseFollowZoom, bearingDeg = browseBearing, pitchDeg = 0.0, padding = insets, animateMs = animateMs, ease = ease)
            }
            CameraMode.PREVIEW -> lookAtTarget(animateMs) ?: if (userMoved) null else if (stopView != null) stopFitTarget(animateMs) else previewTarget(animateMs)
            CameraMode.GUIDE_FOLLOW -> followTarget(animateMs)
            CameraMode.GUIDE_FREE -> null
            // K8: adding a stop, the stop view (a gesture leaves the camera: neither the overview nor the follow)
            CameraMode.GUIDE_OVERVIEW -> if (stopView != null) {
                if (userMoved) null else stopFitTarget(animateMs)
            } else {
                overviewBounds?.let { fitTarget(it, animateMs, rules.overviewPaddingDp ?: rules.previewPaddingDp) } ?: followTarget(animateMs)
            }
            CameraMode.ARRIVED -> lookAtTarget(animateMs) ?: if (userMoved) null else arrivedTarget(animateMs)
        }
        // The same move with another duration is not a new move: re-emitting it would restart the
        // camera's animation towards where it is already going (a trip update without a new position).
        val cur = _target.value
        if (t != null && cur != null && t.copy(animateMs = cur.animateMs) == cur) return
        _target.value = t
    }

    /** K8: the stop view's box; while guiding the vehicle is always in it (the driver keeps seeing the car). */
    private fun stopBox(): Bounds? {
        val pts = stopView ?: return null
        val car = if (kindOf(trip.phase) == Kind.GUIDE) trip.guidance?.vehicle?.point else null
        return Bounds.around(pts + listOfNotNull(car))
    }

    /**
     * K8: the stop view fitted ([stopPadding], else preview.padding_dp, plus the insets: the results window
     * or the Add stop panel is in the insets); remembers the box and the time. No room ([stopRoom]): null, the
     * camera stays ([onViewport] publishes again once the insets give room back).
     */
    private fun stopFitTarget(animateMs: Int): CameraTarget? {
        if (!stopRoom()) return null
        return stopBox()?.let { b ->
            stopFitBox = b
            stopFitAtMs = clock.elapsedMs()
            fitTarget(b, animateMs, stopPadding ?: rules.previewPaddingDp)
        }
    }

    /** K8 (item 54b): the area the insets leave free, less the stop view's padding on each side, has some width and height. */
    private fun stopRoom(): Boolean {
        if (widthDp <= 0f || heightDp <= 0f) return true   // not measured yet: the map fits by itself
        val p = stopPadding ?: rules.previewPaddingDp
        return widthDp - insets.start - insets.end - 2 * p > 0f && heightDp - insets.top - insets.bottom - 2 * p > 0f
    }

    /** K8: [p] is outside the middle 80 % of the last stop fit (its box shrunk by 10 % of its span on each side). */
    private fun leavingFit(p: GeoPoint?): Boolean {
        p ?: return false
        val b = stopFitBox ?: return true
        val dLat = (b.north - b.south) * STOP_FIT_EDGE
        val dLon = (b.east - b.west) * STOP_FIT_EDGE
        return p.lat < b.south + dLat || p.lat > b.north - dLat || p.lon < b.west + dLon || p.lon > b.east - dLon
    }

    private fun lookAtTarget(animateMs: Int): CameraTarget? = lookAt?.let {
        CameraTarget(center = it, zoom = lookAtZoom, bearingDeg = 0.0, pitchDeg = 0.0, padding = insets, animateMs = animateMs)
    }

    /** K2: all choices' bounds plus the origin and the stops, padded by padding_dp plus the insets. */
    private fun previewTarget(animateMs: Int): CameraTarget? {
        val origin = when (val o = trip.origin) {
            is Origin.FromPlace -> o.place.point
            Origin.CurrentPosition -> if (trip.choices.isEmpty()) puckPoint() else null
        }
        val box = union(trip.choices.map { it.bounds }, trip.stops.map { it.place.point } + listOfNotNull(origin)) ?: return null
        return fitTarget(box, animateMs)
    }

    /** What PREVIEW shows; the selected card is not part of it (choosing a card does not re-fit). */
    private fun previewKeyOf(t: TripState): Any = listOf(
        t.choices.map { it.id to it.bounds },
        t.stops.map { it.place.point },
        (t.origin as? Origin.FromPlace)?.place?.point,
    )

    /**
     * K3: pitch guide.pitch_deg, bearing = the smoothed vehicle bearing, the vehicle at
     * vehicle_from_bottom of the free map height (by padding: MapLibre puts the centre in the middle
     * of the padded area), zoom from speed, approach_zoom when the shown distance to the maneuver is
     * at most approach_within_m. Before the first vehicle position: the puck, else the route start.
     */
    private fun followTarget(animateMs: Int): CameraTarget? {
        val g = trip.guidance ?: run {
            // no guidance: nothing of a lean outlives its trip
            leanKey = null
            leanTurn = null
            leanPose = null
            leanHold = null
            return null
        }
        val at = g.vehicle?.point ?: puckPoint() ?: g.route.geometry.firstOrNull() ?: return null
        val banner = g.banner
        val zoom = if (banner != null && banner.distanceM <= rules.approachWithinM) rules.approachZoom else zoomForSpeed(speedMps)
        val base = bearing.value ?: 0.0
        val pose = leanFor(g)
        if (pose != null) leanBearing = leanTurn?.let { Lean.bearing(base, it, pose) } ?: base
        return CameraTarget(
            center = at,
            zoom = zoom + (pose?.zoomAdd ?: 0.0),
            bearingDeg = if (pose != null) leanBearing else heldBearing(base) ?: base,
            pitchDeg = pose?.pitchDeg ?: rules.guidePitchDeg,
            padding = followPadding(),
            animateMs = animateMs,
        )
    }

    /**
     * Item 22, the lean into the turn ahead (guide.lean): the pose for the banner's maneuver when it
     * is a turn within reach ([Lean.pose]), else null. The turn is read from the route line once per
     * maneuver ([Lean.turnAt]). When the banner moves on to the next maneuver of the same route, the
     * bearing the map had is held ([leanHold]) so the map does not turn back before the vehicle has
     * turned. A new route (a re-route after a missed turn, another trip) or a vehicle off the route
     * keeps nothing of the old turn.
     */
    private fun leanFor(g: Guidance): Lean.Pose? {
        val lr = rules.lean ?: return null
        val b = g.banner
        val cursor = g.traveled
        val key = b?.let { g.route.id to it.view.maneuverIndex }
        if (key != leanKey) {
            val turn = leanTurn
            val pose = leanPose
            val sameRoute = key != null && key.first == leanKey?.first
            leanHold = if (sameRoute && !g.offRouteSuspected && turn != null && pose != null && pose.bearingAddDeg != 0.0 && lr.holdMs > 0) {
                Hold(leanBearing, Math.signum(turn.turnDeg), clock.elapsedMs() + lr.holdMs)
            } else null
            leanKey = key
            leanTurn = null
            leanPose = null
        }
        if (g.offRouteSuspected) {
            leanPose = null
            leanHold = null
            return null
        }
        if (b == null || cursor == null) return null
        if (b.distanceM >= maxOf(lr.withinM, lr.withinS * speedMps)) {
            leanPose = null
            return null
        }
        if (leanTurn == null) leanTurn = Lean.turnAt(g.route.geometry, cursor, b.distanceM, lr.lookM)
        val turn = leanTurn ?: return null
        return Lean.pose(turn, b.distanceM, speedMps, lr, rules.guidePitchDeg).also { if (it != null) { leanPose = it; leanHold = null } }
    }

    /**
     * After a turn the map stays at the bearing it leaned to ([leanHold]) while the vehicle's own
     * bearing [base] has not yet turned that far (a position a second old still points down the old
     * road), for at most guide.lean.hold_ms. Null: no hold, follow [base].
     */
    private fun heldBearing(base: Double): Double? {
        val h = leanHold ?: return null
        val past = signedDiff(base, h.bearingDeg) * h.side
        if (h.side == 0.0 || clock.elapsedMs() >= h.untilMs || past >= 0.0) {
            leanHold = null
            return null
        }
        return h.bearingDeg
    }

    /** Linear interpolation of guide.zoom_by_speed_mps, held at both ends. */
    fun zoomForSpeed(mps: Double): Double {
        val table = rules.guideZoomBySpeed
        if (mps <= table.first().first) return table.first().second
        if (mps >= table.last().first) return table.last().second
        val i = table.indexOfLast { it.first <= mps }
        val (s0, z0) = table[i]
        val (s1, z1) = table[i + 1]
        return z0 + (mps - s0) / (s1 - s0) * (z1 - z0)
    }

    /**
     * The padding that puts the camera centre (the vehicle) at vehicle_from_bottom of the free map
     * height (the view minus the overlay insets, top and bottom): the padded area's middle is there.
     */
    fun followPadding(): EdgeInsetsDp {
        val free = (heightDp - insets.top - insets.bottom).coerceAtLeast(0f)
        val f = rules.guideVehicleFromBottom.toFloat()
        val extraTop = if (f <= 0.5f) free * (1f - 2f * f) else 0f
        val extraBottom = if (f > 0.5f) free * (2f * f - 1f) else 0f
        return EdgeInsetsDp(top = insets.top + extraTop, bottom = insets.bottom + extraBottom, start = insets.start, end = insets.end)
    }

    /**
     * K6 (P4): top-down, north up; the car and the destination framed together above the arrival card:
     * centred between them at the zoom that fits both in the area the insets and overview.padding_dp
     * (else preview.padding_dp) leave free, never closer than arrived.zoom. The frame puts the car at
     * its edge: the overview's padding leaves room for the arrow's picture (136 dp, half of it on each
     * side of the car; with preview.padding_dp's 48 its brake glow went past the edge, review of 0.1.40,
     * the P32 defect). A car within about 50 m of the destination
     * ([POINT_SPAN_DEG] both ways), no car, or a viewport not yet measured: centred on the destination
     * at arrived.zoom.
     */
    private fun arrivedTarget(animateMs: Int): CameraTarget? {
        val dest = trip.stops.lastOrNull()?.place?.point ?: trip.guidance?.route?.geometry?.lastOrNull() ?: return null
        val single = CameraTarget(center = dest, zoom = rules.arrivedZoom, bearingDeg = 0.0, pitchDeg = 0.0, padding = insets, animateMs = animateMs)
        val car = arrivedCar ?: return single
        if (kotlin.math.abs(car.lat - dest.lat) < POINT_SPAN_DEG && kotlin.math.abs(car.lon - dest.lon) < POINT_SPAN_DEG) return single
        val p = rules.overviewPaddingDp ?: rules.previewPaddingDp
        val padding = EdgeInsetsDp(top = insets.top + p, bottom = insets.bottom + p, start = insets.start + p, end = insets.end + p)
        val freeW = widthDp - padding.start - padding.end
        val freeH = heightDp - padding.top - padding.bottom
        if (freeW <= 0f || freeH <= 0f) return single
        val both = listOf(car, dest)
        val zoom = minOf(fitZoom(both, freeW, freeH), rules.arrivedZoom)
        return CameraTarget(center = mercatorCentre(both), zoom = zoom, bearingDeg = 0.0, pitchDeg = 0.0, padding = padding, animateMs = animateMs)
    }

    /** The car while driving: the session's vehicle, else the puck. */
    private fun carPoint(t: TripState): GeoPoint? = t.guidance?.vehicle?.point ?: puckPoint()

    /**
     * Top-down, north up, [box] padded by [paddingDp] (preview.padding_dp; the overview's own, K5, P32)
     * plus the insets; a box of about one point is centred instead.
     */
    private fun fitTarget(box: Bounds, animateMs: Int, paddingDp: Float = rules.previewPaddingDp): CameraTarget {
        if (box.east - box.west < POINT_SPAN_DEG && box.north - box.south < POINT_SPAN_DEG) {
            val c = GeoPoint((box.south + box.north) / 2, (box.west + box.east) / 2)
            return CameraTarget(center = c, zoom = rules.browseFollowZoom, bearingDeg = 0.0, pitchDeg = 0.0, padding = insets, animateMs = animateMs)
        }
        val p = paddingDp
        val padding = EdgeInsetsDp(top = insets.top + p, bottom = insets.bottom + p, start = insets.start + p, end = insets.end + p)
        return CameraTarget(fit = box, bearingDeg = 0.0, pitchDeg = 0.0, padding = padding, animateMs = animateMs)
    }

    /**
     * S11: the nearest results that fit at [resultsMinZoom] or closer (at least the nearest one),
     * centred in the padded area at the zoom that fits them (Web Mercator, MapLibre's 512 dp world
     * at zoom 0), never closer than browse.follow_zoom. Before the viewport is known all are fitted
     * by the map itself.
     */
    private fun resultsTarget(points: List<GeoPoint>, animateMs: Int): CameraTarget {
        val p = rules.previewPaddingDp
        val padding = EdgeInsetsDp(top = insets.top + p, bottom = insets.bottom + p, start = insets.start + p, end = insets.end + p)
        val freeW = widthDp - padding.start - padding.end
        val freeH = heightDp - padding.top - padding.bottom
        if (freeW <= 0f || freeH <= 0f) {
            return if (points.size == 1) {
                CameraTarget(center = points[0], zoom = rules.browseFollowZoom, padding = insets, animateMs = animateMs)
            } else {
                CameraTarget(fit = Bounds.around(points)!!, padding = padding, animateMs = animateMs)
            }
        }
        var k = 1
        while (k < points.size && fitZoom(points.subList(0, k + 1), freeW, freeH) >= resultsMinZoom) k++
        val kept = points.subList(0, k)
        val zoom = minOf(fitZoom(kept, freeW, freeH), rules.browseFollowZoom)
        return CameraTarget(center = mercatorCentre(kept), zoom = zoom, bearingDeg = 0.0, pitchDeg = 0.0, padding = padding, animateMs = animateMs)
    }

    /** K5: the part of the route not yet driven (from the traveled cursor), with the vehicle. */
    private fun remainingBounds(g: Guidance?): Bounds? {
        g ?: return null
        val line = g.route.geometry
        val cursor = g.traveled
        val rest = if (cursor == null) line else listOf(cursor.point) + line.drop((cursor.index + 1).coerceIn(0, line.size))
        return Bounds.around(rest + listOfNotNull(g.vehicle?.point))
    }

    private fun puckPoint(): GeoPoint? = puck?.let { GeoPoint(it.lat, it.lon) }

    private fun lastVehiclePoint(t: TripState): GeoPoint? = t.guidance?.vehicle?.point

    private companion object {
        /** A fit box smaller than this (about 50 m) in both directions is one point. */
        const val POINT_SPAN_DEG = 0.0005
        /** K1a: the map turns with the compass by this much or more at a time (below it the sensor's noise). */
        const val COMPASS_STEP_DEG = 0.02

        /** K1a: a hand-turned map within this of north counts as north up (my-location then follows at once). */
        const val NORTH_TOLERANCE_DEG = 0.5

        /** K1a (0.1.33): a turn my-location starts takes TURN_BASE_MS plus TURN_MS_PER_DEG a degree, at most TURN_MAX_MS. */
        const val TURN_BASE_MS = 250
        const val TURN_MS_PER_DEG = 1.6
        const val TURN_MAX_MS = 550

        /** K6 (P4): the arrival view frames the car again once it has moved this far from where it was framed (parking). */
        const val ARRIVED_REFRAME_M = 20.0

        /**
         * K8 (item 51): guided, the stop view is fitted again at most this often (at 100 km/h the car moves
         * about 140 m meanwhile), so the map does not slide continuously under the driver's eye.
         */
        const val STOP_REFIT_MS = 5_000L
        /** K8: the vehicle leaves the view's middle when it is within this share of the box's span of an edge. */
        const val STOP_FIT_EDGE = 0.1
    }
}

/** Web Mercator x and y of [p] as fractions of the world (0..1, y down). */
private fun mercator(p: GeoPoint): Pair<Double, Double> {
    val lat = p.lat.coerceIn(-85.0511, 85.0511) * Math.PI / 180
    val x = (p.lon + 180.0) / 360.0
    val y = (1 - kotlin.math.ln(kotlin.math.tan(lat) + 1 / kotlin.math.cos(lat)) / Math.PI) / 2
    return x to y
}

/**
 * The zoom at which [points] just fit in [freeW] x [freeH] dp (MapLibre: the world is 512 dp wide
 * at zoom 0); infinite for one point.
 */
internal fun fitZoom(points: List<GeoPoint>, freeW: Float, freeH: Float): Double {
    val m = points.map(::mercator)
    val dx = m.maxOf { it.first } - m.minOf { it.first }
    val dy = m.maxOf { it.second } - m.minOf { it.second }
    val zx = if (dx <= 0.0) Double.POSITIVE_INFINITY else kotlin.math.log2(freeW / (512.0 * dx))
    val zy = if (dy <= 0.0) Double.POSITIVE_INFINITY else kotlin.math.log2(freeH / (512.0 * dy))
    return minOf(zx, zy)
}

/** The centre of [points]' box in Web Mercator (what a fit of that box centres on). */
internal fun mercatorCentre(points: List<GeoPoint>): GeoPoint {
    val m = points.map(::mercator)
    val x = (m.maxOf { it.first } + m.minOf { it.first }) / 2
    val y = (m.maxOf { it.second } + m.minOf { it.second }) / 2
    val lon = x * 360.0 - 180.0
    val lat = Math.toDegrees(kotlin.math.atan(kotlin.math.sinh(Math.PI * (1 - 2 * y))))
    return GeoPoint(lat, lon)
}

/** K1b: the map pin of :map:layers (MarkerImages.PIN_W_DP x PIN_H_DP dp, anchored at its bottom: the tip is the point). */
internal const val PIN_W_DP = 28f
internal const val PIN_H_DP = 38f
/** K1b: the room a pin keeps from the edges of the free area. */
internal const val IN_VIEW_MARGIN_DP = 8f

/**
 * K1b (P8): whether a map pin at [p] (its tip at the point) lies wholly in the area [insets] leave
 * free of a [widthDp] x [heightDp] view, with [IN_VIEW_MARGIN_DP] to spare; the camera top-down with
 * [middle] at the view's middle, at [zoom], turned to [bearingDeg] (the direction at the top of the
 * screen, clockwise from north). Web Mercator in MapLibre's 512 dp world at zoom 0.
 */
internal fun pinInView(p: GeoPoint, middle: GeoPoint, zoom: Double, bearingDeg: Double, widthDp: Float, heightDp: Float, insets: EdgeInsetsDp): Boolean {
    val (px, py) = mercator(p)
    val (cx, cy) = mercator(middle)
    val scale = 512.0 * Math.pow(2.0, zoom)
    var dx = px - cx
    if (dx > 0.5) dx -= 1.0 else if (dx < -0.5) dx += 1.0   // across the antimeridian
    val wx = dx * scale
    val wy = (py - cy) * scale
    val b = Math.toRadians(bearingDeg)
    val x = widthDp / 2.0 + wx * Math.cos(b) + wy * Math.sin(b)
    val y = heightDp / 2.0 - wx * Math.sin(b) + wy * Math.cos(b)
    val m = IN_VIEW_MARGIN_DP
    return x - PIN_W_DP / 2 - m >= insets.start && x + PIN_W_DP / 2 + m <= widthDp - insets.end &&
        y - PIN_H_DP - m >= insets.top && y + m <= heightDp - insets.bottom
}
