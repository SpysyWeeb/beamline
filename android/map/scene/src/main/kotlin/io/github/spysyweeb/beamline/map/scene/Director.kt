package io.github.spysyweeb.beamline.map.scene

import io.github.spysyweeb.beamline.api.map.CameraMode
import io.github.spysyweeb.beamline.api.map.CameraState
import io.github.spysyweeb.beamline.api.map.CameraTarget
import io.github.spysyweeb.beamline.api.map.EdgeInsetsDp
import io.github.spysyweeb.beamline.api.map.MapGesture
import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.nav.Guidance
import io.github.spysyweeb.beamline.api.nav.Origin
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.TripState
import io.github.spysyweeb.beamline.location.Clock
import io.github.spysyweeb.beamline.location.HeadingSmoother
import io.github.spysyweeb.beamline.location.PuckState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What [CameraDirector] does: the camera modes and moves of rules K1-K7 (docs/architecture.md 3.7).
 *
 * The camera is moved by the app only through [target]. A null target means "leave the camera where
 * the user put it": in BROWSE (K1; except right after guidance and for a picked place, until a gesture), in GUIDE_FREE (K4), and in PREVIEW or ARRIVED after the user
 * moved the map. Every other mode always has a target, recomputed whenever one of its inputs
 * changes (viewport, insets, trip, puck), so re-applying the current target (after a rotation, a
 * style reload) is always right.
 *
 * Animation: a change of mode, of viewport or of the overlay insets animates over transition_ms;
 * a follow move animates over the time between the last two positions (K3: the vehicle's glide,
 * the same as VehicleMarker.Arrow.glideMs; for the browsing puck the time between its last two
 * updates, the same 3 s limit).
 *
 * Timers (K4, K5) are checked in [tick] against [clock]. All calls come from one thread (the main
 * thread in the app); the flows may be read from any thread.
 */
internal class Director(private val rules: CameraRules, private val clock: Clock) {
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
    private val compassSmoother = HeadingSmoother(COMPASS_SMOOTHING)
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

    // Browse follow (K1)
    private var puckAtMs: Long? = null
    private var puckGlideMs = 0
    private var followFirst = true

    fun onViewport(widthDp: Float, heightDp: Float, insets: EdgeInsetsDp) {
        if (widthDp == this.widthDp && heightDp == this.heightDp && insets == this.insets) return
        this.widthDp = widthDp
        this.heightDp = heightDp
        this.insets = insets
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
            Kind.ARRIVED -> publish(rules.transitionMs)
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
        // K1a: the map follows the phone's compass, smoothed, in steps of COMPASS_STEP_DEG at least
        val cone = p?.coneDeg
        if (_compassFollow.value && cone != null && mode == CameraMode.BROWSE_FOLLOW) {
            val s = compassSmoother.add(cone).toDouble()
            if (kotlin.math.abs(signedDiff(s, browseBearing)) >= COMPASS_STEP_DEG) {
                browseBearing = s
                // a new position moves the camera below, with the new bearing
                if (!changed) publish(COMPASS_ANIMATE_MS)
            }
        }
        when (mode) {
            CameraMode.BROWSE_FOLLOW -> if (changed || _target.value == null) {
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
                // K1a: a map no longer centred on the user does not turn with the compass
                _compassFollow.value = false
                setMode(CameraMode.BROWSE)
            }
            CameraMode.PREVIEW, CameraMode.ARRIVED -> {
                userMoved = true
                lookAt = null
            }
            CameraMode.GUIDE_FOLLOW, CameraMode.GUIDE_OVERVIEW, CameraMode.GUIDE_FREE -> {
                sinceMs = clock.elapsedMs()
                setMode(CameraMode.GUIDE_FREE)
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
                // K1a: not following -> following north up; following -> also turned by the phone's
                // compass (when it has one); turned by the compass -> following north up
                val heading = puck?.coneDeg
                if (mode == CameraMode.BROWSE_FOLLOW && !_compassFollow.value && heading != null) {
                    compassSmoother.reset()
                    browseBearing = compassSmoother.add(heading).toDouble()
                    _compassFollow.value = true
                } else {
                    _compassFollow.value = false
                    browseBearing = 0.0
                    followFirst = true
                }
                setMode(CameraMode.BROWSE_FOLLOW)
            }
            CameraMode.PREVIEW, CameraMode.ARRIVED -> {
                val here = trip.guidance?.vehicle?.point ?: puck?.let { GeoPoint(it.lat, it.lon) }
                if (here != null) {
                    lookAt = here
                    lookAtZoom = if (mode == CameraMode.ARRIVED) rules.arrivedZoom else rules.browseFollowZoom
                    userMoved = false
                }
            }
            CameraMode.GUIDE_FOLLOW, CameraMode.GUIDE_FREE, CameraMode.GUIDE_OVERVIEW -> setMode(CameraMode.GUIDE_FOLLOW)
        }
        publish(rules.transitionMs)
    }

    fun onRecenter() {
        when (mode) {
            CameraMode.GUIDE_FREE, CameraMode.GUIDE_OVERVIEW, CameraMode.GUIDE_FOLLOW -> setMode(CameraMode.GUIDE_FOLLOW)
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

    /** K1a: the user began turning the map with two fingers: the compass no longer leads. */
    fun onUserRotate() {
        _compassFollow.value = false
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
        if (place == null && results == null) return
        place = null
        results = null   // S11: a place tapped on the map, or the results closed, ends their fit too
        if (mode == CameraMode.BROWSE) publish(0)
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
        // K1a: a new phase group starts north up, the compass not leading
        browseBearing = 0.0
        _compassFollow.value = false
        userMoved = false
        lookAt = null
        place = null
        results = null
        when (kind) {
            Kind.BROWSE -> {
                // From guidance the camera is tilted and turned: one move back to top-down, north up,
                // where the vehicle was (K1: browsing is top-down and north up). From ARRIVED and
                // PREVIEW it already is.
                browseReset = if (wasGuide) lastVehiclePoint(prev) ?: puckPoint() else null
                setMode(CameraMode.BROWSE)
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
            Kind.ARRIVED -> setMode(CameraMode.ARRIVED)
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

    private fun publish(animateMs: Int) {
        val t = when (mode) {
            CameraMode.BROWSE -> browseReset?.let {
                CameraTarget(center = it, zoom = null, bearingDeg = browseBearing, pitchDeg = 0.0, padding = insets, animateMs = animateMs)
            } ?: place?.let {
                CameraTarget(center = it, zoom = rules.browseFollowZoom, bearingDeg = browseBearing, pitchDeg = 0.0, padding = insets, animateMs = animateMs)
            } ?: results?.let { resultsTarget(it, animateMs) }
            CameraMode.BROWSE_FOLLOW -> puckPoint()?.let {
                followFirst = false
                CameraTarget(center = it, zoom = rules.browseFollowZoom, bearingDeg = browseBearing, pitchDeg = 0.0, padding = insets, animateMs = animateMs)
            }
            CameraMode.PREVIEW -> lookAtTarget(animateMs) ?: if (userMoved) null else previewTarget(animateMs)
            CameraMode.GUIDE_FOLLOW -> followTarget(animateMs)
            CameraMode.GUIDE_FREE -> null
            CameraMode.GUIDE_OVERVIEW -> overviewBounds?.let { fitTarget(it, animateMs) } ?: followTarget(animateMs)
            CameraMode.ARRIVED -> lookAtTarget(animateMs) ?: if (userMoved) null else arrivedTarget(animateMs)
        }
        // The same move with another duration is not a new move: re-emitting it would restart the
        // camera's animation towards where it is already going (a trip update without a new position).
        val cur = _target.value
        if (t != null && cur != null && t.copy(animateMs = cur.animateMs) == cur) return
        _target.value = t
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

    /** K6: top-down, north up, centred on the destination at arrived.zoom. */
    private fun arrivedTarget(animateMs: Int): CameraTarget? {
        val dest = trip.stops.lastOrNull()?.place?.point ?: trip.guidance?.route?.geometry?.lastOrNull() ?: return null
        return CameraTarget(center = dest, zoom = rules.arrivedZoom, bearingDeg = 0.0, pitchDeg = 0.0, padding = insets, animateMs = animateMs)
    }

    /** Top-down, north up, [box] padded by preview.padding_dp plus the insets; a box of about one point is centred instead. */
    private fun fitTarget(box: Bounds, animateMs: Int): CameraTarget {
        if (box.east - box.west < POINT_SPAN_DEG && box.north - box.south < POINT_SPAN_DEG) {
            val c = GeoPoint((box.south + box.north) / 2, (box.west + box.east) / 2)
            return CameraTarget(center = c, zoom = rules.browseFollowZoom, bearingDeg = 0.0, pitchDeg = 0.0, padding = insets, animateMs = animateMs)
        }
        val p = rules.previewPaddingDp
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
        /** K1a: the weight of each compass reading in the camera's own smoothing (the puck's reading is smoothed already). */
        const val COMPASS_SMOOTHING = 0.2
        /** K1a: the map turns with the compass only by this much or more at a time (no shiver). */
        const val COMPASS_STEP_DEG = 1.5
        /** K1a: how long each such turn takes. */
        const val COMPASS_ANIMATE_MS = 250
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
