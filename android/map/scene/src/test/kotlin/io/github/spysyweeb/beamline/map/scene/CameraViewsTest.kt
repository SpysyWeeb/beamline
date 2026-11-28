package io.github.spysyweeb.beamline.map.scene

import io.github.spysyweeb.beamline.api.map.CameraMode
import io.github.spysyweeb.beamline.api.map.CameraTarget
import io.github.spysyweeb.beamline.api.map.EdgeInsetsDp
import io.github.spysyweeb.beamline.api.map.MapGesture
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.nav.Motion
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.TripState
import io.github.spysyweeb.beamline.api.nav.TripStop
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The 0.1.40 views: arrival (K6, P4), a route closed (K2a, P50, P46) and a tapped place above its sheet (K1b, P8). */
class CameraViewsTest {
    private val clock = VirtualClock()
    private val cam = CameraDirector(CameraRules.bundled(), clock)
    private val insets = EdgeInsetsDp(top = 24f, bottom = 300f)
    private val target: CameraTarget? get() = cam.target.value
    private val mode: CameraMode get() = cam.state.value.mode

    /** Downtown Erie: the middle of the view where the camera rests in the K1b tests. */
    private val mid = GeoPoint(42.12, -80.08)

    init {
        cam.onViewport(411f, 891f, insets)
    }

    // ---- K6 (P4) ----

    @Test
    fun `P4 arrival frames the car at the road's end and a destination 0,2 mi away above the card`() {
        val card = EdgeInsetsDp(top = 24f, bottom = 260f)
        cam.onViewport(411f, 891f, card)
        val end = Fx.ROUTE[4]                        // where the road ends; the car stopped there
        val park = GeoPoint(42.0525, -80.0820)       // the place, about 370 m north-east of it
        val stops = listOf(TripStop(Fx.place("Park", park)))
        cam.onTrip(Fx.guiding(Fx.vehicle(Fx.ROUTE[3], 10_000), stops = stops))
        cam.onTrip(Fx.guiding(Fx.vehicle(end, 30_000, motion = Motion.STOPPED, speed = 0.0), phase = Phase.ARRIVED, stops = stops))
        assertEquals(CameraMode.ARRIVED, mode)
        // the insets + overview.padding_dp (72): room for the arrow's 136 dp picture, half of it either side of the car
        // (review of 0.1.40: preview.padding_dp's 48 put the brake glow past the frame's edge, the P32 defect)
        val pad = EdgeInsetsDp(top = 96f, bottom = 332f, start = 72f, end = 72f)
        val both = listOf(end, park)
        val want = CameraTarget(center = mercatorCentre(both), zoom = minOf(fitZoom(both, 411f - 144f, 891f - 96f - 332f), 17.0),
            bearingDeg = 0.0, pitchDeg = 0.0, padding = pad, animateMs = 800)
        assertEquals(want, target)                   // today: center = park, zoom 17, padding = card; the car off the screen
        assertTrue(target!!.zoom!! < 17.0 && target!!.zoom!! > 15.0, "zoom ${target!!.zoom}")
        val t = target
        // parking: a few metres on, the frame stays (the same move is not emitted again)
        cam.onTrip(Fx.guiding(Fx.vehicle(GeoPoint(end.lat + 0.0001, end.lon), 31_000, speed = 1.0), phase = Phase.ARRIVED, stops = stops))
        assertTrue(t === target)
        // a GPS jump of about 90 m while stopped (a garage, tall buildings): the frame stays
        cam.onTrip(Fx.guiding(Fx.vehicle(GeoPoint(end.lat + 0.0008, end.lon), 32_000, motion = Motion.STOPPED, speed = 0.0), phase = Phase.ARRIVED, stops = stops))
        assertTrue(t === target)
        // about 60 m on, moving: framed again around the car's new position
        val on = GeoPoint(end.lat + 0.0005, end.lon + 0.0003)
        cam.onTrip(Fx.guiding(Fx.vehicle(on, 40_000, speed = 1.0), phase = Phase.ARRIVED, stops = stops))
        assertEquals(mercatorCentre(listOf(on, park)), target!!.center)
        // ARRIVED has no Recenter or my-location button: a pan holds until the car moves on 20 m
        cam.onGesture(MapGesture.CameraMovedByUser)
        assertNull(target)
        cam.onTrip(Fx.guiding(Fx.vehicle(GeoPoint(on.lat + 0.0001, on.lon), 41_000, speed = 1.0), phase = Phase.ARRIVED, stops = stops))
        assertNull(target)                           // 11 m: still where the user put it
        val further = GeoPoint(on.lat + 0.0003, on.lon)
        cam.onTrip(Fx.guiding(Fx.vehicle(further, 42_000, speed = 1.0), phase = Phase.ARRIVED, stops = stops))
        assertEquals(mercatorCentre(listOf(further, park)), target!!.center)
    }

    @Test
    fun `P4 a car at the destination, or a view not yet measured, is centred on the destination as before`() {
        // the car within about 50 m of the place: today's K6 target (CameraDirectorTest's K6 case)
        cam.onTrip(Fx.guiding(Fx.vehicle(Fx.ROUTE[4], 10_000)))
        cam.onTrip(Fx.guiding(Fx.vehicle(GeoPoint(Fx.DEST.lat + 0.0002, Fx.DEST.lon), 30_000, motion = Motion.STOPPED, speed = 0.0), phase = Phase.ARRIVED))
        assertEquals(CameraTarget(center = Fx.DEST, zoom = 17.0, bearingDeg = 0.0, pitchDeg = 0.0, padding = insets, animateMs = 800), target)
        // no view size yet: centred on the destination
        val fresh = CameraDirector(CameraRules.bundled(), clock)
        fresh.onTrip(Fx.guiding(Fx.vehicle(Fx.ROUTE[3], 10_000)))
        fresh.onTrip(Fx.guiding(Fx.vehicle(Fx.ROUTE[4], 30_000, motion = Motion.STOPPED, speed = 0.0), phase = Phase.ARRIVED))
        assertEquals(CameraTarget(center = Fx.DEST, zoom = 17.0, bearingDeg = 0.0, pitchDeg = 0.0, padding = EdgeInsetsDp(), animateMs = 800), fresh.target.value)
    }

    @Test
    fun `P4 a camera file without the overview's padding frames the arrival as the preview`() {
        val d = CameraDirector(CameraRules.bundled().copy(overviewPaddingDp = null), clock)
        d.onViewport(411f, 891f, EdgeInsetsDp(top = 24f, bottom = 260f))
        val stops = listOf(TripStop(Fx.place("Park", GeoPoint(42.0525, -80.0820))))
        d.onTrip(Fx.guiding(Fx.vehicle(Fx.ROUTE[3], 10_000), stops = stops))
        d.onTrip(Fx.guiding(Fx.vehicle(Fx.ROUTE[4], 30_000, motion = Motion.STOPPED, speed = 0.0), phase = Phase.ARRIVED, stops = stops))
        assertEquals(EdgeInsetsDp(top = 72f, bottom = 308f, start = 48f, end = 48f), d.target.value!!.padding)
    }

    // ---- K2a (P50, P46) ----

    @Test
    fun `P50 a route closed before it started follows the user again, north up, at follow_zoom`() {
        cam.onPuck(Fx.puck(Fx.MALL))
        cam.onTrip(Fx.choosing())
        assertEquals(CameraMode.PREVIEW, mode)
        cam.onGesture(MapGesture.CameraMovedByUser)  // a look around the route first: does not matter
        cam.onTrip(TripState())                      // X (Cancel) or Back: End
        assertEquals(CameraMode.BROWSE_FOLLOW, mode) // today: BROWSE, target null (the preview's framing stays)
        assertEquals(CameraTarget(center = Fx.MALL, zoom = 16.0, bearingDeg = 0.0, pitchDeg = 0.0, padding = insets, animateMs = 800), target)
        assertFalse(cam.compassFollow.value)
        // the route sheet gone, the search bar back: still in the middle of the free area
        val browse = EdgeInsetsDp(top = 24f, bottom = 64f)
        cam.onViewport(411f, 891f, browse)
        assertEquals(Fx.MALL, target!!.center)
        assertEquals(browse, target!!.padding)
    }

    @Test
    fun `P50 without a position the camera stays where the preview left it`() {
        cam.onTrip(Fx.choosing())
        cam.onTrip(TripState())
        assertEquals(CameraMode.BROWSE, mode)
        assertNull(target)
    }

    @Test
    fun `K2a Back from directions shows the reopened place once the trip has ended`() {
        cam.onPuck(Fx.puck(Fx.MALL))
        cam.onTrip(Fx.choosing())
        val fit = target
        cam.onShowPlaceAfterRoute(Fx.DEST)           // sent before End
        assertEquals(CameraMode.PREVIEW, mode)
        assertEquals(fit, target)                    // the preview stays until the trip has ended
        cam.onTrip(TripState())
        assertEquals(CameraMode.BROWSE, mode)
        assertEquals(CameraTarget(center = Fx.DEST, zoom = 16.0, bearingDeg = 0.0, pitchDeg = 0.0, padding = insets, animateMs = 800), target)
        // used once: the next route closed with X follows the user
        cam.onTrip(Fx.choosing())
        cam.onTrip(TripState())
        assertEquals(CameraMode.BROWSE_FOLLOW, mode)
        // a route started instead forgets it
        cam.onTrip(Fx.choosing())
        cam.onShowPlaceAfterRoute(Fx.DEST)
        cam.onTrip(Fx.guiding(Fx.vehicle(Fx.ROUTE[1], 10_000)))
        cam.onTrip(TripState())
        assertEquals(CameraMode.BROWSE, mode)
        assertEquals(Fx.ROUTE[1], target!!.center)   // the one move back after guidance, not the place
    }

    // ---- K1b (P8) ----

    private val peek = EdgeInsetsDp(top = 80f, bottom = 264f)   // status bar + search bar; the peek sheet + credit row
    private val full = EdgeInsetsDp(top = 80f, bottom = 688f)   // the details height (0.7 x 891) + credit row

    @Test
    fun `P8 a place tapped on the map stays put while in view, and is centred above the sheet once it rises over it`() {
        cam.onViewport(411f, 891f, peek)
        cam.onCameraAt(mid, 16.0, 0.0)
        cam.onPlaceLeft()                            // all today's app does for a tapped place
        cam.onPlaceTapped(mid)                       // in the middle of the view: in clear view at peek
        assertEquals(CameraMode.BROWSE, mode)
        assertNull(target)
        cam.onViewport(411f, 891f, full)             // the sheet raised over it; today: target stays null
        assertEquals(CameraTarget(center = mid, zoom = null, bearingDeg = 0.0, pitchDeg = 0.0,
            padding = full.copy(top = 80f + 38f), animateMs = 800), target)
        cam.onViewport(411f, 891f, peek)             // lowered: kept in the middle of the larger area
        assertEquals(mid, target!!.center)
        assertEquals(peek.copy(top = 118f), target!!.padding)
        cam.onGesture(MapGesture.CameraMovedByUser)  // a pan ends it
        assertNull(target)
        cam.onViewport(411f, 891f, full)
        assertNull(target)
    }

    @Test
    fun `P8 a tapped place under the search bar is centred at once, or once a glide ends, following and trips are left alone`() {
        cam.onViewport(411f, 891f, peek)
        cam.onCameraAt(mid, 16.0, 0.0)
        val nearTop = GeoPoint(mid.lat + 0.0032, mid.lon)   // about 400 dp above the middle: its head under the bar
        cam.onPlaceTapped(nearTop)
        assertEquals(nearTop, target!!.center)
        assertNull(target!!.zoom)
        cam.onPlaceLeft()                            // the sheet closed: the camera stays
        assertNull(target)
        cam.onViewport(411f, 891f, full)
        assertNull(target)
        // a glide still running at the tap: its end is checked again
        cam.onViewport(411f, 891f, peek)
        cam.onCameraAt(mid, 16.0, 0.0)
        cam.onPlaceTapped(mid)
        assertNull(target)                           // in clear view at the tap
        cam.onCameraAt(GeoPoint(mid.lat - 0.003, mid.lon), 16.0, 0.0)   // came to rest about 377 dp further south
        assertEquals(mid, target!!.center)           // the pin's head went under the bar: centred
        assertEquals(peek.copy(top = 118f), target!!.padding)
        // following, turned by the compass: a tap changes nothing (the follow keeps the user in view)
        cam.onPuck(Fx.puck(mid))
        cam.onMyLocation()                           // follow, north up
        cam.onMyLocation()                           // follow turned by the compass (Fx.puck's cone)
        assertEquals(CameraMode.BROWSE_FOLLOW, mode)
        assertTrue(cam.compassFollow.value)
        val follow = target
        cam.onPlaceTapped(nearTop)
        assertEquals(CameraMode.BROWSE_FOLLOW, mode)
        assertTrue(cam.compassFollow.value)
        assertTrue(follow === target)
        // a trip: nothing changes
        cam.onTrip(Fx.choosing())
        val fit = target
        cam.onPlaceTapped(Fx.MALL)
        assertEquals(CameraMode.PREVIEW, mode)
        assertTrue(fit === target)
    }

    @Test
    fun `P8 on its side the camera stays without room for the pin, and centres a place under P7's side panel`() {
        val peekWide = EdgeInsetsDp(top = 80f, bottom = 120f)           // 891 x 411: the peek sheet across the width
        cam.onViewport(891f, 411f, peekWide)
        cam.onCameraAt(mid, 16.0, 0.0)
        val west = GeoPoint(mid.lat, mid.lon - 0.0015)                // about 140 dp west of the middle: x 306
        cam.onPlaceTapped(west)
        assertNull(target)                                            // in clear view at peek
        cam.onViewport(891f, 411f, EdgeInsetsDp(top = 80f, bottom = 344f))   // the middle height across the width
        assertNull(target)                                            // no room for the pin: the camera stays
        val panel = EdgeInsetsDp(top = 80f, start = 400f)             // landscape.md P7: the window in a side panel
        cam.onViewport(891f, 411f, panel)
        assertEquals(CameraTarget(center = west, zoom = null, bearingDeg = 0.0, pitchDeg = 0.0,
            padding = panel.copy(top = 118f), animateMs = 800), target)
        // centred, then no room again: the camera stays rather than ask for a padding taller than the view
        cam.onViewport(891f, 411f, EdgeInsetsDp(top = 80f, bottom = 360f))
        assertNull(target)
    }

    @Test
    fun `pinInView reads the zoom, the insets and the turned map`() {
        assertTrue(pinInView(mid, mid, 16.0, 0.0, 411f, 891f, peek))
        assertFalse(pinInView(mid, mid, 16.0, 0.0, 411f, 891f, full))           // the tip under the sheet
        val east = GeoPoint(mid.lat, mid.lon + 0.003)                          // about 280 dp east at z16
        assertFalse(pinInView(east, mid, 16.0, 0.0, 411f, 891f, peek))         // north up: past the right edge
        assertTrue(pinInView(east, mid, 16.0, 90.0, 411f, 891f, peek))         // east up: above the middle, below the bar
        assertFalse(pinInView(east, mid, 16.0, 270.0, 411f, 891f, peek))       // west up: under the sheet
        assertTrue(pinInView(east, mid, 15.0, 0.0, 411f, 891f, peek))          // one zoom out: 140 dp, in view
    }
}
