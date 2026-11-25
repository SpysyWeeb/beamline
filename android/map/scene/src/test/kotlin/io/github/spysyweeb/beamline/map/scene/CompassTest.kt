package io.github.spysyweeb.beamline.map.scene

import io.github.spysyweeb.beamline.api.map.CameraMode
import io.github.spysyweeb.beamline.api.map.EdgeInsetsDp
import io.github.spysyweeb.beamline.api.map.MapGesture
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.TripState
import io.github.spysyweeb.beamline.location.Clock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Rule K1a (the owner, 2026-11-24; one button since 0.1.28): browsing may be turned, and my-location
 * steps through following, following turned by the phone's compass, and following north up.
 */
class CompassTest {
    private var now = 0L
    private val cam = CameraDirector(CameraRules.bundled(), Clock { now }).apply { onViewport(411f, 891f, EdgeInsetsDp()) }
    private val mode: CameraMode get() = cam.state.value.mode

    @Test
    fun `my-location follows north up, then turns with the compass, then north up again`() {
        cam.onPuck(Fx.puck(Fx.ORIGIN, cone = 90f))
        cam.onMyLocation()
        assertEquals(CameraMode.BROWSE_FOLLOW, mode)
        assertFalse(cam.compassFollow.value)
        assertEquals(0.0, cam.target.value!!.bearingDeg, "following, north up")
        cam.onMyLocation()
        assertTrue(cam.compassFollow.value)
        val t = cam.target.value!!
        assertEquals(90.0, t.bearingDeg, 0.5, "turned to the phone's direction")
        assertEquals(Fx.ORIGIN.lat, t.center!!.lat, 1e-9, "still centred on the user")
        // the phone turns: the map follows, smoothed, in steps
        cam.onPuck(Fx.puck(Fx.ORIGIN, cone = 120f))
        val b = cam.target.value!!.bearingDeg
        assertTrue(b > 90.0 && b < 120.0, "smoothed toward 120: $b")
        repeat(40) { cam.onPuck(Fx.puck(Fx.ORIGIN, cone = 120f)) }
        val settled = cam.target.value!!.bearingDeg
        assertEquals(120.0, settled, 1.6, "settled near the phone's direction")
        cam.onPuck(Fx.puck(Fx.ORIGIN, cone = 120.5f))
        assertEquals(settled, cam.target.value!!.bearingDeg, "a jitter under the step does not move the camera")
        cam.onMyLocation()
        assertFalse(cam.compassFollow.value)
        assertEquals(CameraMode.BROWSE_FOLLOW, mode)
        assertEquals(0.0, cam.target.value!!.bearingDeg, "following, north up again")
    }

    @Test
    fun `a two-finger turn ends only the compass turn, a drag ends the following too`() {
        cam.onPuck(Fx.puck(Fx.ORIGIN, cone = 90f))
        cam.onMyLocation()
        cam.onMyLocation()
        assertTrue(cam.compassFollow.value)
        cam.onUserRotate()
        assertFalse(cam.compassFollow.value)
        assertEquals(CameraMode.BROWSE_FOLLOW, mode, "still following")
        cam.onMyLocation()
        assertTrue(cam.compassFollow.value)
        cam.onGesture(MapGesture.CameraMovedByUser)
        assertEquals(CameraMode.BROWSE, mode)
        assertFalse(cam.compassFollow.value, "a map not centred on the user does not turn with the compass")
    }

    @Test
    fun `my-location on a turned map centres north up, and a route choice starts with the compass off`() {
        cam.onPuck(Fx.puck(Fx.ORIGIN, cone = 90f))
        cam.onUserRotate()
        cam.onMapBearing(40.0)
        cam.onMyLocation()
        assertEquals(CameraMode.BROWSE_FOLLOW, mode)
        assertEquals(0.0, cam.target.value!!.bearingDeg, "turned by hand: following north up")
        cam.onMyLocation()
        assertTrue(cam.compassFollow.value)
        cam.onTrip(TripState(phase = Phase.CHOOSING))
        assertFalse(cam.compassFollow.value)
    }

    @Test
    fun `without a compass reading the second tap keeps following north up`() {
        cam.onPuck(Fx.puck(Fx.ORIGIN, cone = null))
        cam.onMyLocation()
        cam.onMyLocation()
        assertFalse(cam.compassFollow.value)
        assertEquals(0.0, cam.target.value!!.bearingDeg)
    }
}
