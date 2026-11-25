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
import kotlin.math.abs

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
        assertTrue(t.ease, "the turn to the phone's direction speeds up and slows down")
        // once the turn is done (0.1.33), the compass's first reading: the map takes it at once, without animation
        now += CameraDirectorTurn.WAIT
        cam.onHeading(120f)
        assertEquals(120.0, cam.target.value!!.bearingDeg, 1e-9)
        assertEquals(0, cam.target.value!!.animateMs, "no animation to wait for")
        cam.onPuck(Fx.puck(Fx.ORIGIN, cone = 200f))
        assertEquals(120.0, cam.target.value!!.bearingDeg, 1e-9, "the puck's smoothed cone does not steer the camera")
        cam.onMyLocation()
        assertFalse(cam.compassFollow.value)
        assertEquals(CameraMode.BROWSE_FOLLOW, mode)
        assertEquals(0.0, cam.target.value!!.bearingDeg, "following, north up again")
    }

    @Test
    fun `the compass is calmed when the phone is still and keeps up when it turns (0_1_30)`() {
        cam.onPuck(Fx.puck(Fx.ORIGIN, cone = 90f))
        cam.onMyLocation()
        cam.onMyLocation()
        now += CameraDirectorTurn.WAIT
        cam.onHeading(120f)
        fun bearing() = cam.target.value!!.bearingDeg
        // held still, a sensor wobbling 0.3 degree either way (every 8 ms): the map about a third of it
        for (i in 1..250) {
            now += 8
            cam.onHeading(if (i % 2 == 0) 120.3f else 119.7f)
            assertEquals(120.0, bearing(), 0.15, "still, reading $i")
        }
        // a slow turn, 5 degrees a second: small steps with almost every reading (0.1.29: half a degree)
        var last = bearing()
        var moves = 0
        for (i in 1..250) {
            now += 8
            cam.onHeading((120 + 5.0 * i * 0.008).toFloat())
            assertTrue(abs(signedDiff(bearing(), last)) < 0.1, "slow turn, reading $i: a step of ${bearing() - last}")
            if (bearing() != last) moves++
            last = bearing()
        }
        assertTrue(moves > 200, "the map moved with $moves of 250 readings")
        assertEquals(130.0, bearing(), 0.1, "a tenth of a degree behind at most")
        // a quick turn, 180 degrees a second, across north: never half a degree behind
        var truth = 130.0
        for (i in 1..187) {
            now += 8
            truth = (130 + 180.0 * i * 0.008) % 360
            cam.onHeading(truth.toFloat())
            val behind = signedDiff(truth, bearing())
            assertTrue(behind > -0.05 && behind < 0.5, "quick turn, reading $i: $behind degrees behind")
        }
        assertTrue(truth < 90, "the turn went past north")
        assertEquals(0.0, signedDiff(truth, bearing()), 0.15)
    }

    @Test
    fun `a turn my-location starts is quick, eased, timed by its angle, and not re-timed by the next fix (0_1_33)`() {
        cam.onPuck(Fx.puck(Fx.ORIGIN, cone = 90f))
        cam.onMyLocation()
        val follow = cam.target.value!!
        assertTrue(follow.ease, "the move to the user is eased too")
        cam.onMyLocation()
        val turn = cam.target.value!!
        assertEquals(90.0, turn.bearingDeg, 0.5)
        assertEquals(250 + (1.6 * 90).toInt(), turn.animateMs, "a quarter-second plus 1.6 ms a degree")
        assertTrue(turn.ease)
        // a fix during the turn does not re-time it
        now += 200
        cam.onPuck(Fx.puck(Fx.ORIGIN.copy(lat = Fx.ORIGIN.lat + 0.0001), cone = 90f))
        assertEquals(turn, cam.target.value, "the turn runs on")
        // after it, the next fix glides as before
        now += 400
        cam.onPuck(Fx.puck(Fx.ORIGIN.copy(lat = Fx.ORIGIN.lat + 0.0002), cone = 90f))
        assertEquals(Fx.ORIGIN.lat + 0.0002, cam.target.value!!.center!!.lat, 1e-9)
        assertFalse(cam.target.value!!.ease, "following glides at one speed")
        // compass to north up: a half turn is capped
        cam.onHeading(270f)
        now += 600
        cam.onHeading(270f)
        assertEquals(270.0, cam.target.value!!.bearingDeg, 0.5)
        cam.onMyLocation()
        assertEquals(0.0, cam.target.value!!.bearingDeg)
        assertEquals(250 + (1.6 * 90).toInt(), cam.target.value!!.animateMs, "270 to 0 is a 90-degree turn")
        assertTrue(cam.target.value!!.animateMs <= 550)
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
    fun `my-location on a map turned by hand turns it north up where it is, then follows`() {
        cam.onPuck(Fx.puck(Fx.ORIGIN, cone = 90f))
        cam.onGesture(MapGesture.CameraMovedByUser)
        cam.onUserRotate()
        cam.onMapBearing(40.0)
        cam.onMyLocation()
        assertEquals(CameraMode.BROWSE, mode, "not following yet")
        val north = cam.target.value!!
        assertEquals(0.0, north.bearingDeg, "north up")
        assertEquals(null, north.center, "the map stays where it is")
        assertEquals(null, north.zoom)
        cam.onMyLocation()
        assertEquals(CameraMode.BROWSE_FOLLOW, mode, "the second tap goes to the user (before the camera reported north)")
        assertEquals(Fx.ORIGIN.lat, cam.target.value!!.center!!.lat, 1e-9)
        assertEquals(0.0, cam.target.value!!.bearingDeg)
        // turned again and dragged: north first again; a map already north up follows at once
        cam.onGesture(MapGesture.CameraMovedByUser)
        cam.onUserRotate()
        cam.onMapBearing(300.0)
        cam.onMyLocation()
        assertEquals(CameraMode.BROWSE, mode)
        cam.onMapBearing(0.0)
        cam.onGesture(MapGesture.CameraMovedByUser)
        cam.onMyLocation()
        assertEquals(CameraMode.BROWSE_FOLLOW, mode, "north up already: straight to the user")
    }

    @Test
    fun `a route choice starts with the compass off`() {
        cam.onPuck(Fx.puck(Fx.ORIGIN, cone = 90f))
        cam.onMyLocation()
        assertEquals(CameraMode.BROWSE_FOLLOW, mode)
        cam.onMyLocation()
        assertTrue(cam.compassFollow.value)
        cam.onTrip(TripState(phase = Phase.CHOOSING))
        assertFalse(cam.compassFollow.value)
    }

    @Test
    fun `a heading while the map does not turn with the compass changes nothing`() {
        cam.onPuck(Fx.puck(Fx.ORIGIN, cone = 90f))
        cam.onMyLocation()
        cam.onHeading(45f)
        assertEquals(0.0, cam.target.value!!.bearingDeg, "following north up")
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

/** How long a test waits for a my-location turn to finish (the longest is Director.TURN_MAX_MS). */
private object CameraDirectorTurn {
    const val WAIT = 600L
}
