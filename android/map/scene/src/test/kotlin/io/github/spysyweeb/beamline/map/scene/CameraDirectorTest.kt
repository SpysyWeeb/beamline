package io.github.spysyweeb.beamline.map.scene

import io.github.spysyweeb.beamline.api.map.CameraMode
import io.github.spysyweeb.beamline.api.map.CameraState
import io.github.spysyweeb.beamline.api.map.CameraTarget
import io.github.spysyweeb.beamline.api.map.EdgeInsetsDp
import io.github.spysyweeb.beamline.api.map.MapGesture
import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.nav.Motion
import io.github.spysyweeb.beamline.api.nav.Origin
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.RouteCursor
import io.github.spysyweeb.beamline.api.nav.TripState
import io.github.spysyweeb.beamline.api.nav.TripStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Rules K1-K7 (docs/architecture.md 3.7) on a virtual clock, with the bundled camera.json. */
class CameraDirectorTest {
    private val rules = CameraRules.bundled()
    private val clock = VirtualClock()
    private val cam = CameraDirector(rules, clock)
    private val insets = EdgeInsetsDp(top = 120f, bottom = 200f, start = 0f, end = 0f)

    init {
        cam.onViewport(411f, 891f, insets)
    }

    private val target: CameraTarget? get() = cam.target.value
    private val mode: CameraMode get() = cam.state.value.mode

    /** One fix per second along the route, as the session publishes them. */
    private fun drive(i: Int, ms: Long, bearing: Double? = 57.0, speed: Double? = 25.0, bannerM: Double? = 2000.0, phase: Phase = Phase.GUIDING) =
        cam.onTrip(Fx.guiding(Fx.vehicle(Fx.ROUTE[i], ms, bearing = bearing, speed = speed), phase = phase, bannerM = bannerM))

    // ---- K1 -------------------------------------------------------------------------------

    @Test
    fun `K1 browse is never moved by the app`() {
        assertEquals(CameraState(CameraMode.BROWSE, showRecenter = false), cam.state.value)
        assertNull(target)
        cam.onTrip(TripState())
        cam.onPuck(Fx.puck(Fx.ORIGIN))
        cam.onPuck(Fx.puck(Fx.ROUTE[1]))
        cam.tick()
        cam.onViewport(411f, 891f, EdgeInsetsDp(top = 80f))
        assertNull(target)
        assertEquals(CameraMode.BROWSE, mode)
    }

    @Test
    fun `K1 my-location follows the puck at follow_zoom until a gesture`() {
        cam.onPuck(Fx.puck(Fx.ORIGIN))
        cam.onMyLocation()
        assertEquals(CameraMode.BROWSE_FOLLOW, mode)
        // eased since 0.1.33 (a move the user started)
        assertEquals(CameraTarget(center = Fx.ORIGIN, zoom = 16.0, bearingDeg = 0.0, pitchDeg = 0.0, padding = insets, animateMs = 800, ease = true), target)
        clock.advanceS(1.0)
        cam.onPuck(Fx.puck(Fx.ROUTE[1]))
        // the time between the two puck updates, so the camera keeps moving between 1 Hz fixes
        assertEquals(CameraTarget(center = Fx.ROUTE[1], zoom = 16.0, bearingDeg = 0.0, pitchDeg = 0.0, padding = insets, animateMs = 1_000), target)
        clock.advanceS(0.5)
        cam.onPuck(Fx.puck(Fx.ROUTE[2]))
        assertEquals(500, target!!.animateMs)
        assertEquals(Fx.ROUTE[2], target!!.center)
        // a long gap jumps
        clock.advanceS(10.0)
        cam.onPuck(Fx.puck(Fx.ROUTE[4]))
        assertEquals(0, target!!.animateMs)
        clock.advanceS(1.0)
        cam.onPuck(Fx.puck(Fx.ROUTE[2]))
        cam.onGesture(MapGesture.CameraMovedByUser)
        assertEquals(CameraMode.BROWSE, mode)
        assertNull(target)
        cam.onPuck(Fx.puck(Fx.ROUTE[3]))
        assertNull(target)
    }

    /**
     * K1's z9.99 cap outside the states, applied by the app with ComposedMap.zoomFor (review finding:
     * the director cannot know the installed maps). The stand-in below is ComposedMap.zoomFor with
     * Pennsylvania's pack box and the overview's maxzoom 10 (StyleComposer: 10 - 0.01).
     */
    @Test
    fun `K1 the app caps a follow outside the states at the overview's last zoom`() {
        val paBox = Bounds(-80.52, 39.72, -74.69, 42.27)
        val zoomFor = { lat: Double, lon: Double, wanted: Double ->
            if (lon in paBox.west..paBox.east && lat in paBox.south..paBox.north) wanted else minOf(wanted, 10.0 - 0.01)
        }
        val conneaut = GeoPoint(41.9476, -80.5542)   // Ohio, just west of the Pennsylvania box
        cam.onPuck(Fx.puck(Fx.MUSEUM))
        cam.onMyLocation()
        assertEquals(16.0, target!!.withZoomCap(zoomFor).zoom)
        clock.advanceS(1.0)
        cam.onPuck(Fx.puck(conneaut))
        assertEquals(16.0, target!!.zoom)   // the director asks follow_zoom ...
        val capped = target!!.withZoomCap(zoomFor)
        assertEquals(9.99, capped.zoom!!, 1e-9)   // ... the app moves to the overview's last zoom
        assertEquals(target!!.copy(zoom = 9.99), capped.copy(zoom = 9.99))   // nothing else changes
        // a target that keeps the zoom, a box, a zoom already below the cap: as they are
        val keep = CameraTarget(center = conneaut, zoom = null)
        assertEquals(keep, keep.withZoomCap(zoomFor))
        val box = CameraTarget(fit = Bounds(-80.6, 41.9, -80.5, 42.0))
        assertEquals(box, box.withZoomCap(zoomFor))
        val low = CameraTarget(center = conneaut, zoom = 7.0)
        assertEquals(low, low.withZoomCap(zoomFor))
    }

    @Test
    fun `K1 my-location before the first fix centres when it comes`() {
        cam.onMyLocation()
        assertEquals(CameraMode.BROWSE_FOLLOW, mode)
        assertNull(target)
        cam.onPuck(Fx.puck(Fx.MALL))
        assertEquals(Fx.MALL, target!!.center)
        assertEquals(16.0, target!!.zoom)
        assertEquals(800, target!!.animateMs)
    }

    @Test
    fun `K1 a picked place leaves the follow mode and is shown at follow_zoom, until a gesture`() {
        cam.onPuck(Fx.puck(Fx.ORIGIN))
        cam.onMyLocation()
        assertEquals(CameraMode.BROWSE_FOLLOW, mode)
        cam.onShowPlace(Fx.MUSEUM)
        assertEquals(CameraMode.BROWSE, mode)
        assertEquals(CameraTarget(center = Fx.MUSEUM, zoom = 16.0, bearingDeg = 0.0, pitchDeg = 0.0, padding = insets, animateMs = 800), target)
        // the puck moving does not take the camera back
        cam.onPuck(Fx.puck(Fx.ROUTE[1]))
        assertEquals(Fx.MUSEUM, target!!.center)
        // the place sheet measured: the place stays in the middle of the area above it
        val withSheet = insets.copy(bottom = 420f)
        cam.onViewport(411f, 891f, withSheet)
        assertEquals(CameraTarget(center = Fx.MUSEUM, zoom = 16.0, bearingDeg = 0.0, pitchDeg = 0.0, padding = withSheet, animateMs = 800), target)
        cam.onGesture(MapGesture.CameraMovedByUser)
        assertNull(target)
        cam.onViewport(411f, 891f, insets)
        assertNull(target)
        // picked again after a pan: shown again
        cam.onShowPlace(Fx.MUSEUM)
        assertEquals(Fx.MUSEUM, target!!.center)
        // my-location follows the puck again
        cam.onMyLocation()
        assertEquals(CameraMode.BROWSE_FOLLOW, mode)
        assertEquals(Fx.ROUTE[1], target!!.center)
    }

    @Test
    fun `K1 after the picked place's sheet closes or another place replaces it, the camera stays`() {
        cam.onShowPlace(Fx.MUSEUM)
        val withSheet = insets.copy(bottom = 420f)
        cam.onViewport(411f, 891f, withSheet)
        assertEquals(Fx.MUSEUM, target!!.center)
        // the sheet closes: no move back to the place when the insets shrink
        cam.onPlaceLeft()
        assertNull(target)
        cam.onViewport(411f, 891f, insets)
        assertNull(target)
        assertEquals(CameraMode.BROWSE, mode)
        // picked again, then a map tap shows another place's (taller) sheet: still no move
        cam.onShowPlace(Fx.MUSEUM)
        assertEquals(Fx.MUSEUM, target!!.center)
        cam.onPlaceLeft()
        cam.onViewport(411f, 891f, insets.copy(bottom = 520f))
        assertNull(target)
        // nothing picked: no effect, also while following
        cam.onPuck(Fx.puck(Fx.ORIGIN))
        cam.onMyLocation()
        cam.onPlaceLeft()
        assertEquals(CameraMode.BROWSE_FOLLOW, mode)
        assertEquals(Fx.ORIGIN, target!!.center)
    }

    @Test
    fun `K1 a picked place while choosing is looked at, while guiding the camera stays`() {
        cam.onTrip(Fx.choosing())
        cam.onShowPlace(Fx.MUSEUM)
        assertEquals(CameraMode.PREVIEW, mode)
        assertEquals(Fx.MUSEUM, target!!.center)
        assertEquals(16.0, target!!.zoom)
        drive(0, 10_000)
        val follow = target
        cam.onShowPlace(Fx.MUSEUM)
        assertEquals(CameraMode.GUIDE_FOLLOW, mode)
        assertEquals(follow, target)
        // after guidance the picked place is forgotten: one move back to top-down, keeping the zoom
        cam.onTrip(TripState())
        assertEquals(Fx.ROUTE[0], target!!.center)
        assertNull(target!!.zoom)
    }

    @Test
    fun `taps and long presses do not move the camera`() {
        cam.onPuck(Fx.puck(Fx.ORIGIN))
        cam.onMyLocation()
        val before = target
        cam.onGesture(MapGesture.Tap(Fx.MALL, null, null))
        cam.onGesture(MapGesture.LongPress(Fx.MALL))
        assertEquals(CameraMode.BROWSE_FOLLOW, mode)
        assertEquals(before, target)
    }

    // ---- K2 -------------------------------------------------------------------------------

    @Test
    fun `K2 planning and choosing fit all choices, the origin and the stops`() {
        cam.onPuck(Fx.puck(Fx.ORIGIN))
        val stop = Fx.place("Stop", GeoPoint(42.0700, -80.0400))     // outside both routes' boxes
        val trip = Fx.choosing(stops = listOf(TripStop(stop), TripStop(Fx.downs)))
        cam.onTrip(trip)
        assertEquals(CameraState(CameraMode.PREVIEW, false), cam.state.value)
        val t = target!!
        val want = Bounds.around(Fx.ROUTE + Fx.ALT + stop.point)!!
        assertEquals(want, t.fit)
        assertNull(t.center)
        assertEquals(0.0, t.pitchDeg)
        assertEquals(0.0, t.bearingDeg)
        assertEquals(EdgeInsetsDp(top = 168f, bottom = 248f, start = 48f, end = 48f), t.padding)
        assertEquals(800, t.animateMs)
    }

    @Test
    fun `K2 planning from the current position fits the puck and the destination before any route`() {
        cam.onPuck(Fx.puck(Fx.ORIGIN))
        cam.onTrip(TripState(phase = Phase.PLANNING, origin = Origin.CurrentPosition, stops = listOf(TripStop(Fx.downs))))
        assertEquals(CameraMode.PREVIEW, mode)
        assertEquals(Bounds.around(listOf(Fx.ORIGIN, Fx.DEST)), target!!.fit)
    }

    @Test
    fun `K2 the fit follows new choices and the insets, not a choice of card`() {
        cam.onTrip(Fx.choosing().copy(choices = listOf(Fx.choice(1, Fx.ROUTE)), selectedChoiceId = 1))
        val one = target!!
        assertEquals(Bounds.around(Fx.ROUTE), one.fit)
        cam.onTrip(Fx.choosing(selected = 1))
        val two = target!!
        assertEquals(Bounds.around(Fx.ROUTE + Fx.ALT), two.fit)
        cam.onTrip(Fx.choosing(selected = 2))
        assertTrue(two === target)
        // the route sheet grew
        cam.onViewport(411f, 891f, insets.copy(bottom = 400f))
        assertEquals(448f, target!!.padding.bottom)
        // the user pans: left alone, even when the sheet changes ...
        cam.onGesture(MapGesture.CameraMovedByUser)
        assertNull(target)
        cam.onViewport(411f, 891f, insets)
        assertNull(target)
        // ... until the routes change
        cam.onTrip(Fx.choosing().copy(choices = listOf(Fx.choice(3, Fx.ALT))))
        assertEquals(Bounds.around(Fx.ALT), target!!.fit)
    }

    @Test
    fun `a fit of one point is a centre instead`() {
        cam.onTrip(TripState(phase = Phase.PLANNING, origin = Origin.CurrentPosition, stops = listOf(TripStop(Fx.downs))))
        assertEquals(CameraTarget(center = Fx.DEST, zoom = 16.0, padding = insets, animateMs = 800), target)
    }

    // ---- K3 -------------------------------------------------------------------------------

    @Test
    fun `K3 guiding follows the vehicle tilted and heading up`() {
        cam.onTrip(Fx.choosing())
        drive(0, 10_000, bearing = 57.0, speed = 0.0)
        assertEquals(CameraState(CameraMode.GUIDE_FOLLOW, false), cam.state.value)
        val t = target!!
        assertEquals(Fx.ROUTE[0], t.center)
        assertEquals(60.0, t.pitchDeg)
        assertEquals(57.0, t.bearingDeg)
        assertEquals(17.0, t.zoom)
        assertEquals(800, t.animateMs)      // the move from the preview
        assertNull(t.fit)
    }

    @Test
    fun `K3 the vehicle sits at vehicle_from_bottom of the free map height`() {
        drive(0, 10_000)
        val p = target!!.padding
        // free height = 891 - 120 - 200 = 571; the padded area's middle is 0.28 * 571 above the bottom inset
        val free = 891f - 120f - 200f
        assertEquals(120f + free * (1f - 2f * 0.28f), p.top, 1e-3f)
        assertEquals(200f, p.bottom)
        val middleFromBottom = p.bottom + (891f - p.top - p.bottom) / 2f
        assertEquals(200f + 0.28f * free, middleFromBottom, 1e-3f)
    }

    @Test
    fun `K3 zoom from speed by linear interpolation, held at the ends`() {
        val d = Director(rules, clock)
        assertEquals(17.0, d.zoomForSpeed(0.0), 1e-9)
        assertEquals(16.5, d.zoomForSpeed(13.4), 1e-9)
        assertEquals(16.5 + (20.0 - 13.4) / (24.6 - 13.4) * (15.75 - 16.5), d.zoomForSpeed(20.0), 1e-9)
        assertEquals(15.25, d.zoomForSpeed(31.3), 1e-9)
        assertEquals(15.25, d.zoomForSpeed(45.0), 1e-9)
        drive(0, 10_000, speed = 20.0)
        assertEquals(d.zoomForSpeed(20.0), target!!.zoom!!, 1e-9)
        drive(1, 11_000, speed = 31.3)
        assertEquals(15.25, target!!.zoom!!, 1e-9)
        // speed unknown: the last one holds
        drive(2, 12_000, speed = null)
        assertEquals(15.25, target!!.zoom!!, 1e-9)
    }

    @Test
    fun `K3 approach_zoom within approach_within_m of the maneuver`() {
        drive(0, 10_000, speed = 31.3, bannerM = 251.0)
        assertEquals(15.25, target!!.zoom)
        drive(1, 11_000, speed = 31.3, bannerM = 250.0)
        assertEquals(17.0, target!!.zoom)
        // the shown distance crosses the line without a new position: the zoom follows
        cam.onTrip(Fx.guiding(Fx.vehicle(Fx.ROUTE[1], 11_000, speed = 31.3), bannerM = 400.0))
        assertEquals(15.25, target!!.zoom)
    }

    @Test
    fun `K3 each move animates over the time between the last two positions`() {
        drive(0, 10_000)
        assertEquals(800, target!!.animateMs)
        drive(1, 11_000)
        assertEquals(1_000, target!!.animateMs)
        drive(2, 11_500)
        assertEquals(500, target!!.animateMs)
        // the same position again: no new move
        val same = target
        drive(2, 11_500)
        assertTrue(same === target)
        // back after a long gap: a jump, as the arrow (glideMs 0)
        drive(3, 50_000)
        assertEquals(0, target!!.animateMs)
        assertEquals(Fx.ROUTE[3], target!!.center)
    }

    @Test
    fun `K3 the bearing is smoothed on the circle`() {
        // bearing_smoothing alone (bearing_turn_deg 0): each position moves the camera 0.3 of the way
        val plain = CameraDirector(rules.copy(bearingTurnDeg = 0.0), clock)
        plain.onViewport(411f, 891f, insets)
        fun drive(i: Int, ms: Long, bearing: Double?) =
            plain.onTrip(Fx.guiding(Fx.vehicle(Fx.ROUTE[i], ms, bearing = bearing, speed = 25.0), phase = Phase.GUIDING, bannerM = 2000.0))
        fun target() = plain.target.value
        drive(0, 10_000, bearing = 350.0)
        assertEquals(350.0, target()!!.bearingDeg, 1e-9)
        drive(1, 11_000, bearing = 10.0)
        // 0.3 of the 20 degrees the short way: 356, not 350 + 0.3 * -340
        assertEquals(356.0, target()!!.bearingDeg, 1e-9)
        drive(2, 12_000, bearing = 10.0)
        assertEquals(356.0 + 0.3 * 14.0 - 360.0, target()!!.bearingDeg, 1e-9)
        // an unknown course keeps the smoothed bearing
        drive(3, 13_000, bearing = null)
        assertEquals(0.2, target()!!.bearingDeg, 1e-9)
        // a U-turn turns the camera, it does not flip it
        drive(4, 14_000, bearing = 180.2)
        assertEquals(54.2, target()!!.bearingDeg, 1e-6)
    }

    @Test
    fun `K3 a turn is followed at the next position, straight driving stays smoothed`() {
        assertEquals(30.0, rules.bearingTurnDeg)
        drive(0, 10_000, bearing = 57.0)
        // the wobble of straight driving (plus or minus 3 degrees each second) moves the camera less than the course
        var ms = 11_000L
        val seen = mutableListOf<Double>()
        for (k in 0 until 10) {
            drive(1 + k % 4, ms, bearing = if (k % 2 == 0) 60.0 else 54.0)
            seen += target!!.bearingDeg
            ms += 1_000
        }
        assertTrue(seen.all { it in 55.0..59.0 }, "camera bearings $seen")
        // a right turn spread over two positions (45 degrees each): the camera is on the new heading
        // at the second, and each move eases over the 1 s glide
        drive(1, ms, bearing = 102.0)
        assertEquals(102.0, target!!.bearingDeg, 3.0)
        drive(2, ms + 1_000, bearing = 147.0)
        assertEquals(147.0, target!!.bearingDeg, 0.5)
        assertEquals(1_000, target!!.animateMs)
        // a 90 degree turn between two positions is followed at once
        drive(3, ms + 2_000, bearing = 237.0)
        assertEquals(237.0, target!!.bearingDeg, 1e-6)
        // a reversal in one position (a glitch while stopped) is smoothed, it does not flip the map
        drive(4, ms + 3_000, bearing = 57.0)
        assertEquals(237.0 + 0.3 * 180.0, target!!.bearingDeg, 1e-6)
    }

    @Test
    fun `K3 a sharp corner of 100 to 120 degrees between two fixes is followed at once, with no step above`() {
        // part 1's review: the weight fell from 1 to 0.3 just above 90 degrees
        for (turn in listOf(100.0, 110.0, 120.0)) {
            val f = BearingFilter(0.3, 30.0)
            f.set(10.0)
            assertEquals(10.0 + turn, f.add(10.0 + turn), 1e-9, "turn $turn")
        }
        val f = BearingFilter(0.3, 30.0)
        // the weight never jumps: steps of 1 degree change it by little, and it ends at 0.3 for a reversal
        var last = f.turnWeight(0.0)
        for (d in 1..180) {
            val w = f.turnWeight(d.toDouble())
            assertTrue(Math.abs(w - last) < 0.03, "weight jumps at $d: $last -> $w")
            last = w
        }
        assertEquals(0.3, f.turnWeight(180.0), 1e-9)
        assertEquals(1.0, f.turnWeight(90.0), 1e-9)
        assertTrue(f.turnWeight(150.0) in 0.3..1.0)
    }

    @Test
    fun `K3 before the first position the camera goes to the route start along the route`() {
        cam.onTrip(Fx.guiding(null))
        assertEquals(CameraMode.GUIDE_FOLLOW, mode)
        assertEquals(Fx.ROUTE[0], target!!.center)
        assertEquals(startBearing(Fx.ROUTE)!!, target!!.bearingDeg, 1e-9)
        assertEquals(60.0, target!!.pitchDeg)
        // the puck stands in when there is one
        cam.onPuck(Fx.puck(Fx.ROUTE[1]))
        assertEquals(Fx.ROUTE[1], target!!.center)
        // then the vehicle
        drive(2, 10_000)
        assertEquals(Fx.ROUTE[2], target!!.center)
    }

    @Test
    fun `K3 rerouting keeps following`() {
        drive(0, 10_000)
        drive(1, 11_000, phase = Phase.REROUTING)
        assertEquals(CameraMode.GUIDE_FOLLOW, mode)
        assertEquals(Fx.ROUTE[1], target!!.center)
        assertEquals(1_000, target!!.animateMs)
    }

    // ---- K4 -------------------------------------------------------------------------------

    @Test
    fun `K4 a gesture frees the camera, Recenter or 15 s bring it back`() {
        drive(0, 10_000)
        cam.onGesture(MapGesture.CameraMovedByUser)
        assertEquals(CameraState(CameraMode.GUIDE_FREE, showRecenter = true), cam.state.value)
        assertNull(target)
        // positions keep coming: the camera stays where the user put it
        clock.advanceS(1.0); drive(1, 11_000); cam.tick()
        assertNull(target)
        clock.advanceS(13.0); cam.tick()
        assertEquals(CameraMode.GUIDE_FREE, mode)
        // another gesture at 14 s starts the 15 s again
        cam.onGesture(MapGesture.CameraMovedByUser)
        clock.advanceS(14.9); cam.tick()
        assertEquals(CameraMode.GUIDE_FREE, mode)
        clock.advanceS(0.1); cam.tick()
        assertEquals(CameraState(CameraMode.GUIDE_FOLLOW, showRecenter = false), cam.state.value)
        assertEquals(Fx.ROUTE[1], target!!.center)
        assertEquals(800, target!!.animateMs)
        // Recenter at once
        cam.onGesture(MapGesture.CameraMovedByUser)
        cam.onRecenter()
        assertEquals(CameraMode.GUIDE_FOLLOW, mode)
        assertNotNull(target)
    }

    @Test
    fun `K4 the bearing keeps up while the camera is free`() {
        drive(0, 10_000, bearing = 0.0)
        cam.onGesture(MapGesture.CameraMovedByUser)
        drive(1, 11_000, bearing = 100.0)
        cam.onRecenter()
        assertEquals(100.0, target!!.bearingDeg, 1e-9) // fed while free; a 100 degree change is followed at once (K3)
        assertEquals(Fx.ROUTE[1], target!!.center)
    }

    // ---- K5 -------------------------------------------------------------------------------

    @Test
    fun `K5 overview fits the remaining route top-down and returns after 10 s`() {
        cam.onTrip(Fx.guiding(Fx.vehicle(GeoPoint(42.0420, -80.0960), 10_000), traveled = RouteCursor(2, GeoPoint(42.0420, -80.0960))))
        cam.onOverview()
        assertEquals(CameraState(CameraMode.GUIDE_OVERVIEW, showRecenter = true), cam.state.value)
        val t = target!!
        assertEquals(Bounds.around(listOf(GeoPoint(42.0420, -80.0960)) + Fx.ROUTE.drop(3)), t.fit)
        assertEquals(0.0, t.pitchDeg)
        assertEquals(0.0, t.bearingDeg)
        // P32 (0.1.40): padded by overview.padding_dp (72), room for the driving arrow's 136 dp picture
        assertEquals(EdgeInsetsDp(top = 192f, bottom = 272f, start = 72f, end = 72f), t.padding)
        // positions keep coming: the overview stays put
        drive(3, 11_000)
        assertTrue(t === target)
        clock.advanceS(9.9); cam.tick()
        assertEquals(CameraMode.GUIDE_OVERVIEW, mode)
        clock.advanceS(0.1); cam.tick()
        assertEquals(CameraMode.GUIDE_FOLLOW, mode)
        assertEquals(Fx.ROUTE[3], target!!.center)
        assertEquals(60.0, target!!.pitchDeg)
    }

    @Test
    fun `K5 a camera file without the overview's padding pads as the preview`() {
        val d = CameraDirector(rules.copy(overviewPaddingDp = null), clock)
        d.onViewport(411f, 891f, insets)
        d.onTrip(Fx.guiding(Fx.vehicle(GeoPoint(42.0420, -80.0960), 10_000), traveled = RouteCursor(2, GeoPoint(42.0420, -80.0960))))
        d.onOverview()
        assertEquals(EdgeInsetsDp(top = 168f, bottom = 248f, start = 48f, end = 48f), d.target.value!!.padding)
    }

    @Test
    fun `K5 Recenter ends the overview at once, a gesture frees the camera`() {
        drive(0, 10_000)
        cam.onOverview()
        cam.onRecenter()
        assertEquals(CameraMode.GUIDE_FOLLOW, mode)
        cam.onOverview()
        cam.onGesture(MapGesture.CameraMovedByUser)
        assertEquals(CameraState(CameraMode.GUIDE_FREE, true), cam.state.value)
        assertNull(target)
    }

    @Test
    fun `K5 a new route during the overview is fitted again`() {
        drive(0, 10_000)
        cam.onOverview()
        cam.onTrip(Fx.guiding(Fx.vehicle(Fx.ORIGIN, 11_000), traveled = null, route = Fx.choice(7, Fx.ALT)))
        assertEquals(Bounds.around(Fx.ALT), target!!.fit)
    }

    @Test
    fun `overview is only offered while guiding`() {
        cam.onOverview()
        assertEquals(CameraMode.BROWSE, mode)
        assertNull(target)
    }

    // ---- K6 -------------------------------------------------------------------------------

    @Test
    fun `K6 arrived centres on the destination top-down, Done goes back to browsing`() {
        drive(4, 10_000)
        cam.onTrip(Fx.guiding(Fx.vehicle(Fx.DEST, 30_000, motion = Motion.STOPPED, speed = 0.0), phase = Phase.ARRIVED))
        assertEquals(CameraState(CameraMode.ARRIVED, false), cam.state.value)
        assertEquals(CameraTarget(center = Fx.DEST, zoom = 17.0, bearingDeg = 0.0, pitchDeg = 0.0, padding = insets, animateMs = 800), target)
        // later positions in ARRIVED do not move it again
        val t = target
        cam.onTrip(Fx.guiding(Fx.vehicle(Fx.DEST, 31_000, motion = Motion.STOPPED, speed = 0.0), phase = Phase.ARRIVED))
        assertTrue(t === target)
        cam.onTrip(TripState())
        assertEquals(CameraState(CameraMode.BROWSE, false), cam.state.value)
        assertNull(target)
    }

    @Test
    fun `ending guidance turns the camera back to top-down, north up, once`() {
        drive(2, 10_000, bearing = 120.0)
        cam.onTrip(TripState())
        assertEquals(CameraMode.BROWSE, mode)
        assertEquals(CameraTarget(center = Fx.ROUTE[2], zoom = null, bearingDeg = 0.0, pitchDeg = 0.0, padding = insets, animateMs = 800), target)
        cam.onGesture(MapGesture.CameraMovedByUser)
        assertNull(target)
    }

    // ---- K7 is the mapper's (MapSceneMapperTest: the arrow from Start to the end of ARRIVED) ----

    @Test
    fun `my-location while guiding is Recenter`() {
        drive(0, 10_000)
        cam.onGesture(MapGesture.CameraMovedByUser)
        cam.onMyLocation()
        assertEquals(CameraMode.GUIDE_FOLLOW, mode)
        assertNotNull(target)
    }

    @Test
    fun `my-location while choosing looks at the puck until the routes change`() {
        cam.onPuck(Fx.puck(Fx.MALL))
        cam.onTrip(Fx.choosing())
        cam.onMyLocation()
        assertEquals(CameraMode.PREVIEW, mode)
        assertEquals(Fx.MALL, target!!.center)
        assertEquals(16.0, target!!.zoom)
        cam.onRecenter()
        assertEquals(Bounds.around(Fx.ROUTE + Fx.ALT), target!!.fit)
    }

    @Test
    fun `a trip that starts guided (resume) follows at once`() {
        cam.onTrip(Fx.guiding(Fx.vehicle(Fx.ROUTE[1], 10_000)))
        assertEquals(CameraMode.GUIDE_FOLLOW, mode)
        assertEquals(Fx.ROUTE[1], target!!.center)
    }

    @Test
    fun `the state flow names showRecenter only for the free and overview modes`() {
        drive(0, 10_000)
        assertFalse(cam.state.value.showRecenter)
        cam.onOverview()
        assertTrue(cam.state.value.showRecenter)
        cam.onRecenter()
        assertFalse(cam.state.value.showRecenter)
    }
}
