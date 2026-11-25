package io.github.spysyweeb.beamline.map.scene

import io.github.spysyweeb.beamline.api.map.CameraMode
import io.github.spysyweeb.beamline.api.map.EdgeInsetsDp
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.TripState
import io.github.spysyweeb.beamline.location.Clock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Rule K1a (the owner, 2026-11-24): browsing may be turned; the compass button turns it north up or lets it follow the phone's compass. */
class CompassTest {
    private var now = 0L
    private val cam = CameraDirector(CameraRules.bundled(), Clock { now }).apply { onViewport(411f, 891f, EdgeInsetsDp()) }

    @Test
    fun `a tap on a north-up map follows the compass, the next goes north up again`() {
        cam.onPuck(Fx.puck(Fx.ORIGIN, cone = 90f))
        cam.onMapBearing(0.0)
        cam.onCompass()
        assertTrue(cam.compassFollow.value)
        val t = cam.target.value!!
        assertEquals(90.0, t.bearingDeg, 0.5, "the map turns to the phone's direction")
        assertNull(t.center, "browsing: the map turns in place")
        assertNull(t.zoom)
        // the phone turns: the map follows, smoothed, in steps
        cam.onPuck(Fx.puck(Fx.ORIGIN, cone = 120f))
        val b = cam.target.value!!.bearingDeg
        assertTrue(b > 90.0 && b < 120.0, "smoothed toward 120: $b")
        repeat(40) { cam.onPuck(Fx.puck(Fx.ORIGIN, cone = 120f)) }
        val settled = cam.target.value!!.bearingDeg
        assertEquals(120.0, settled, 1.6, "settled near the phone's direction")
        cam.onPuck(Fx.puck(Fx.ORIGIN, cone = 120.5f))
        assertEquals(settled, cam.target.value!!.bearingDeg, "a jitter under the step does not move the camera")
        cam.onCompass()
        assertFalse(cam.compassFollow.value)
        assertEquals(0.0, cam.target.value!!.bearingDeg, "north up")
    }

    @Test
    fun `a map turned by hand goes north up first, and a two-finger turn ends the compass's lead`() {
        cam.onPuck(Fx.puck(Fx.ORIGIN, cone = 90f))
        cam.onUserRotate()
        cam.onMapBearing(40.0)
        cam.onCompass()
        assertFalse(cam.compassFollow.value, "turned by hand: north first")
        assertEquals(0.0, cam.target.value!!.bearingDeg)
        cam.onMapBearing(0.0)
        cam.onCompass()
        assertTrue(cam.compassFollow.value)
        cam.onUserRotate()
        assertFalse(cam.compassFollow.value, "the user's own turn wins")
    }

    @Test
    fun `my-location keeps the map's turn, and a route choice starts north up with the compass off`() {
        cam.onPuck(Fx.puck(Fx.ORIGIN, cone = 90f))
        cam.onMapBearing(30.0)
        cam.onMyLocation()
        assertEquals(CameraMode.BROWSE_FOLLOW, cam.state.value.mode)
        assertEquals(30.0, cam.target.value!!.bearingDeg, "the user's turn is kept")
        cam.onMapBearing(0.0)
        cam.onCompass()
        assertTrue(cam.compassFollow.value)
        cam.onTrip(TripState(phase = Phase.CHOOSING))
        assertFalse(cam.compassFollow.value)
        cam.onCompass()
        assertFalse(cam.compassFollow.value, "the compass acts only while browsing")
    }

    @Test
    fun `without a compass reading a tap keeps north up`() {
        cam.onPuck(Fx.puck(Fx.ORIGIN, cone = null))
        cam.onMapBearing(0.0)
        cam.onCompass()
        assertFalse(cam.compassFollow.value)
    }
}
