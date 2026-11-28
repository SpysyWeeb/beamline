package io.github.spysyweeb.beamline.map.scene

import io.github.spysyweeb.beamline.api.map.CameraMode
import io.github.spysyweeb.beamline.api.map.CameraTarget
import io.github.spysyweeb.beamline.api.map.EdgeInsetsDp
import io.github.spysyweeb.beamline.api.map.MapGesture
import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.nav.Motion
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.TripState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Item 51, rule K8: adding a stop on the map. The camera looks top-down, north up at the stop results
 * and where they join the trip (guiding: with the car in the view), re-fits at most every 5 s when the
 * car leaves the middle of the view, and goes back to K2 or K3 when adding a stop ends. 0.1.41 (item 54b):
 * no room for the fit leaves the camera, and the Add stop search's typing view fits with its own padding.
 */
class StopViewCameraTest {
    private val clock = VirtualClock()
    private val cam = CameraDirector(CameraRules.bundled(), clock)
    private val insets = EdgeInsetsDp(top = 24f, bottom = 300f)
    private val target: CameraTarget? get() = cam.target.value
    private val mode: CameraMode get() = cam.state.value.mode

    /** Where the stop joins the trip (J) and two made-up places near the route ahead. */
    private val join = Fx.ROUTE[2]
    private val pts = listOf(join, GeoPoint(42.0450, -80.0950), GeoPoint(42.0380, -80.0900))

    /** The fit's padding: the insets plus preview.padding_dp (48). */
    private val padded = EdgeInsetsDp(top = 72f, bottom = 348f, start = 48f, end = 48f)

    init {
        cam.onViewport(411f, 891f, insets)
    }

    private fun drive(p: GeoPoint, ms: Long) = cam.onTrip(Fx.guiding(Fx.vehicle(p, ms)))

    @Test
    fun `a guided trip - the stop view with the car, re-fits at most every 5 s, gestures and the way back`() {
        drive(Fx.ROUTE[1], 10_000)
        assertEquals(CameraMode.GUIDE_FOLLOW, mode)
        cam.onStopView(pts)
        assertEquals(CameraMode.GUIDE_OVERVIEW, mode)
        assertEquals(CameraTarget(fit = Bounds.around(pts + Fx.ROUTE[1]), bearingDeg = 0.0, pitchDeg = 0.0, padding = padded, animateMs = 800), target)
        val first = target
        // the car leaves the view 2 s after the fit: no new fit yet (the map does not slide under the driver's eye)
        clock.advanceS(2.0)
        drive(GeoPoint(42.0300, -80.1250), 12_000)
        assertTrue(first === target)
        // 6 s after the fit, still outside: a new fit, with the car in it
        clock.advanceS(4.0)
        val out = GeoPoint(42.0290, -80.1260)
        drive(out, 16_000)
        assertEquals(Bounds.around(pts + out), target!!.fit)
        assertTrue(target!!.fit!!.contains(out))
        val second = target
        // in the middle 80 % of the view: nothing moves
        clock.advanceS(6.0)
        val inside = GeoPoint(42.0400, -80.1050)
        drive(inside, 22_000)
        assertTrue(second === target)
        // K5's return to the follow does not apply (today: GUIDE_FOLLOW after 10 s), my-location neither
        clock.advanceS(30.0)
        cam.tick()
        assertEquals(CameraMode.GUIDE_OVERVIEW, mode)
        assertTrue(second === target)
        cam.onMyLocation()
        assertEquals(CameraMode.GUIDE_OVERVIEW, mode)
        assertTrue(second === target)
        // a gesture: the camera stays where the user put it (not the overview or the follow), still north up
        cam.onGesture(MapGesture.CameraMovedByUser)
        assertEquals(CameraMode.GUIDE_OVERVIEW, mode)
        assertNull(target)
        clock.advanceS(9.0)
        cam.tick()
        assertNull(target)
        // after recenter_after_s (15 s) the stop view comes back, with the car where it is now
        clock.advanceS(6.0)
        cam.tick()
        assertEquals(CameraMode.GUIDE_OVERVIEW, mode)
        assertEquals(Bounds.around(pts + inside), target!!.fit)
        // Recenter after a gesture: the fit at once
        cam.onGesture(MapGesture.CameraMovedByUser)
        assertNull(target)
        cam.onRecenter()
        assertEquals(Bounds.around(pts + inside), target!!.fit)
        assertEquals(CameraMode.GUIDE_OVERVIEW, mode)
        // adding a stop ended: guidance follows again (K3)
        cam.onStopView(emptyList())
        assertEquals(CameraMode.GUIDE_FOLLOW, mode)
        assertEquals(inside, target!!.center)
        assertEquals(60.0, target!!.pitchDeg)
        // and K5's overview works as before
        cam.onOverview()
        clock.advanceS(10.0)
        cam.tick()
        assertEquals(CameraMode.GUIDE_FOLLOW, mode)
    }

    @Test
    fun `a new list or a selected row fits again at once, also after a gesture`() {
        drive(Fx.ROUTE[1], 10_000)
        cam.onStopView(pts)
        cam.onGesture(MapGesture.CameraMovedByUser)
        assertNull(target)
        val row = listOf(join, GeoPoint(42.0450, -80.0950))   // a row selected: J and the place
        cam.onStopView(row)
        assertEquals(Bounds.around(row + Fx.ROUTE[1]), target!!.fit)
        // the phase group changes (arrival): it ends without a word from the app
        cam.onTrip(Fx.guiding(Fx.vehicle(Fx.DEST, 20_000, motion = Motion.STOPPED, speed = 0.0), phase = Phase.ARRIVED))
        assertEquals(CameraMode.ARRIVED, mode)
        val arrived = target
        cam.onStopView(emptyList())
        assertTrue(arrived === target)
    }

    @Test
    fun `choosing a route - the stop view fits the points, not the choices, and the K2 fit comes back`() {
        cam.onTrip(Fx.choosing())
        assertEquals(CameraMode.PREVIEW, mode)
        val k2 = target
        assertNotNull(k2!!.fit)
        cam.onStopView(pts)
        assertEquals(CameraMode.PREVIEW, mode)
        assertEquals(CameraTarget(fit = Bounds.around(pts), bearingDeg = 0.0, pitchDeg = 0.0, padding = padded, animateMs = 800), target)
        // a gesture leaves the camera; the next points fit again
        cam.onGesture(MapGesture.CameraMovedByUser)
        assertNull(target)
        cam.onStopView(pts.take(2))
        assertEquals(Bounds.around(pts.take(2)), target!!.fit)
        cam.onStopView(emptyList())
        assertEquals(k2, target)
    }

    @Test
    fun `browsing or arrived - the stop view changes nothing`() {
        cam.onStopView(pts)
        assertEquals(CameraMode.BROWSE, mode)
        assertNull(target)
        cam.onTrip(TripState())
        cam.onStopView(emptyList())
        assertNull(target)
        drive(Fx.ROUTE[3], 10_000)
        cam.onTrip(Fx.guiding(Fx.vehicle(Fx.DEST, 20_000, motion = Motion.STOPPED, speed = 0.0), phase = Phase.ARRIVED))
        val arrived = target
        cam.onStopView(pts)
        assertEquals(CameraMode.ARRIVED, mode)
        assertTrue(arrived === target)
    }

    @Test
    fun `54b without room for the fit the stop view leaves the camera, and fits once there is room`() {
        // a split screen 450 dp tall: the banner to 120 and a window of 300 leave 30 dp, less than the padding
        cam.onViewport(411f, 450f, EdgeInsetsDp(top = 120f, bottom = 300f))
        drive(Fx.ROUTE[1], 10_000)
        cam.onStopView(pts)
        assertEquals(CameraMode.GUIDE_OVERVIEW, mode)
        assertNull(target)                           // today: a fit padded (top 168, bottom 348) taller than the view
        // the view made taller: room again, the fit at once
        cam.onViewport(411f, 891f, EdgeInsetsDp(top = 120f, bottom = 300f))
        assertEquals(Bounds.around(pts + Fx.ROUTE[1]), target!!.fit)
    }

    /** 54b (AF-2): the Add stop search's typing view pads by its own 16 dp; the stop results keep preview.padding_dp (48). */
    @Test
    fun aTypingViewFitsWithItsOwnPadding() {
        drive(Fx.ROUTE[1], 10_000)
        cam.onStopView(pts, 16f)
        assertEquals(CameraMode.GUIDE_OVERVIEW, mode)
        assertEquals(CameraTarget(fit = Bounds.around(pts + Fx.ROUTE[1]), bearingDeg = 0.0, pitchDeg = 0.0,
            padding = EdgeInsetsDp(top = 40f, bottom = 316f, start = 16f, end = 16f), animateMs = 800), target)   // today: 48 on each side
        // kept with the view: other insets fit again with it
        cam.onViewport(411f, 891f, EdgeInsetsDp(top = 120f, bottom = 300f))
        assertEquals(EdgeInsetsDp(top = 136f, bottom = 316f, start = 16f, end = 16f), target!!.padding)
        // the stop results (no padding given): 48 again
        cam.onStopView(pts)
        assertEquals(EdgeInsetsDp(top = 168f, bottom = 348f, start = 48f, end = 48f), target!!.padding)
        // the room guard reads the view's own padding: an 80 dp strip holds the typing view's fit, not the results'
        cam.onViewport(411f, 891f, EdgeInsetsDp(top = 24f, bottom = 787f))
        assertNull(target)
        cam.onStopView(pts, 16f)
        assertEquals(EdgeInsetsDp(top = 40f, bottom = 803f, start = 16f, end = 16f), target!!.padding)
        // choosing a route: the same padding
        cam.onViewport(411f, 891f, insets)
        cam.onTrip(Fx.choosing())
        cam.onStopView(pts, 16f)
        assertEquals(CameraTarget(fit = Bounds.around(pts), bearingDeg = 0.0, pitchDeg = 0.0,
            padding = EdgeInsetsDp(top = 40f, bottom = 316f, start = 16f, end = 16f), animateMs = 800), target)
    }
}
