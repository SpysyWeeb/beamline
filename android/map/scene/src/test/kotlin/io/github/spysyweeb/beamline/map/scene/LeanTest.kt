package io.github.spysyweeb.beamline.map.scene

import io.github.spysyweeb.beamline.api.map.CameraTarget
import io.github.spysyweeb.beamline.api.map.EdgeInsetsDp
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.nav.RouteCursor
import io.github.spysyweeb.beamline.api.nav.TripState
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The camera leans into a turn (docs/next-round.md item 22, camera.json guide.lean). A route that
 * goes 300 m north, turns right and goes 200 m east; one point every 20 m.
 */
class LeanTest {
    private val rules = CameraRules.bundled()
    private val lean = rules.lean!!
    private val dLat = 20.0 / 111_320.0
    private val dLon = 20.0 / (111_320.0 * Math.cos(Math.toRadians(42.0)))
    private val north = (0..15).map { GeoPoint(42.0 + it * dLat, -80.0) }
    private val corner = north.last()
    private val line = north + (1..10).map { GeoPoint(corner.lat, -80.0 + it * dLon) }

    /** The cursor [m] metres before the corner (on the northbound part). */
    private fun cursor(m: Double): RouteCursor {
        val along = 300.0 - m
        val i = (along / 20.0).toInt().coerceAtMost(14)
        return RouteCursor(i, GeoPoint(42.0 + along / 111_320.0, -80.0))
    }

    @Test
    fun `the bundled rules`() {
        assertEquals(LeanRules(withinM = 120.0, withinS = 7.0, bearingShare = 0.5, maxBearingDeg = 55.0, zoomAdd = 0.5, pitchDeg = 50.0,
            minTurnDeg = 30.0, lookM = 35.0, holdMs = 4000), lean)
        // a file without guide.lean (before item 22): the camera never leans
        val text = CameraFile.bundledText()
        val start = text.indexOf("\"lean\"")
        val end = text.indexOf("},", start) + 2
        assertNull(CameraRules.parse(text.removeRange(start, end)).lean)
        val bad = text.replace("\"bearing_share\": 0.5", "\"bearing_share\": 1.5")
        assertTrue(runCatching { CameraRules.parse(bad) }.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun `the turn is read from the route line`() {
        val t = Lean.turnAt(line, cursor(100.0), 100.0, lean.lookM)!!
        assertEquals(0.0, t.entryDeg, 0.5)
        assertEquals(90.0, t.exitDeg, 0.5)
        assertEquals(90.0, t.turnDeg, 1.0)
        // the same line the other way: a left turn
        val back = line.reversed()
        val left = Lean.turnAt(back, RouteCursor(0, back.first()), 200.0, lean.lookM)!!
        assertEquals(-90.0, left.turnDeg, 1.0)
        // a point on the straight part: no change of direction
        assertEquals(0.0, Lean.turnAt(line, cursor(250.0), 100.0, lean.lookM)!!.turnDeg, 0.5)
        // the end of the line (an arrival): nothing to lean toward
        assertNull(Lean.turnAt(line, RouteCursor(24, line[24]), 20.0, lean.lookM))
        assertNull(Lean.turnAt(line, RouteCursor(line.lastIndex, line.last()), 0.0, lean.lookM))
    }

    @Test
    fun `the pose grows smoothly from within_m to the turn`() {
        val turn = Lean.Turn(0.0, 90.0)
        assertNull(Lean.pose(turn, 120.0, 10.0, lean, 60.0), "at within_m: nothing yet")
        val half = Lean.pose(turn, 60.0, 10.0, lean, 60.0)!!
        assertEquals(22.5, half.bearingAddDeg, 1e-9)
        assertEquals(0.25, half.zoomAdd, 1e-9)
        assertEquals(55.0, half.pitchDeg, 1e-9)
        val at = Lean.pose(turn, 0.0, 10.0, lean, 60.0)!!
        assertEquals(Lean.Pose(45.0, 0.5, 50.0), at)
        assertEquals(-45.0, Lean.pose(Lean.Turn(90.0, 0.0), 0.0, 10.0, lean, 60.0)!!.bearingAddDeg, 1e-9, "a left turn leans left")
        // a U-turn is capped; a bend below min_turn_deg is no turn
        assertEquals(55.0, Lean.pose(Lean.Turn(0.0, 180.0), 0.0, 10.0, lean, 60.0)!!.bearingAddDeg, 1e-9)
        assertNull(Lean.pose(Lean.Turn(0.0, 25.0), 10.0, 10.0, lean, 60.0))
        // at highway speed it starts earlier: 7 s at 30 m/s = 210 m
        assertNotNull(Lean.pose(turn, 200.0, 30.0, lean, 60.0))
        assertNull(Lean.pose(turn, 211.0, 30.0, lean, 60.0))
    }

    @Test
    fun `the map never turns past the road the route turns onto`() {
        val turn = Lean.Turn(0.0, 90.0)
        val full = Lean.pose(turn, 0.0, 10.0, lean, 60.0)!!   // 45 degrees for a vehicle still heading in
        assertEquals(45.0, Lean.bearing(0.0, turn, full), 1e-9)
        assertEquals(65.0, Lean.bearing(40.0, turn, full), 1e-9, "40 turned, 50 left: half of what is left")
        assertEquals(90.0, Lean.bearing(90.0, turn, full), 1e-9, "on the new road: the vehicle's own bearing")
        assertEquals(100.0, Lean.bearing(100.0, turn, full), 1e-9, "turned farther: no lean back")
        assertEquals(350.0 + 45.0 - 360.0, Lean.bearing(350.0, turn, full), 1e-9, "heading 10 off the other way: the pose's lean at most")
        val left = Lean.Turn(90.0, 0.0)
        val fullLeft = Lean.pose(left, 0.0, 10.0, lean, 60.0)!!
        assertEquals(45.0, Lean.bearing(90.0, left, fullLeft), 1e-9)
        assertEquals(0.0, Lean.bearing(0.0, left, fullLeft), 1e-9)
    }

    // ---- the director -----------------------------------------------------------------------

    private val clock = VirtualClock()
    private val cam = CameraDirector(rules, clock).also { it.onViewport(411f, 891f, EdgeInsetsDp(top = 120f, bottom = 200f)) }
    private val target: CameraTarget get() = cam.target.value!!

    /** The vehicle [m] metres before the corner heading north, or past it ([m] negative) with [bearing]; the banner's maneuver and distance. */
    private fun at(m: Double, ms: Long, bannerM: Double, maneuver: Int, bearing: Double = 0.0, speed: Double = 10.0) {
        clock.now = ms
        val cur = if (m >= 0) cursor(m) else RouteCursor(15, GeoPoint(corner.lat, -80.0 + (-m) / (111_320.0 * Math.cos(Math.toRadians(42.0)))))
        val s: TripState = Fx.guiding(Fx.vehicle(cur.point, ms, bearing = bearing, speed = speed), traveled = cur, bannerM = bannerM,
            route = Fx.choice(7, line))
        val g = s.guidance!!
        cam.onTrip(s.copy(guidance = g.copy(banner = g.banner!!.copy(view = g.banner!!.view.copy(maneuverIndex = maneuver)))))
    }

    @Test
    fun `the driving camera leans into the turn and eases back after it`() {
        at(200.0, 10_000, bannerM = 200.0, maneuver = 1)
        assertEquals(0.0, target.bearingDeg!!, 1e-6)
        assertEquals(60.0, target.pitchDeg!!, 1e-9)
        assertEquals(17.0, target.zoom!!, 1e-9)
        at(60.0, 11_000, bannerM = 60.0, maneuver = 1)
        assertEquals(22.5, target.bearingDeg!!, 0.5)
        assertEquals(17.25, target.zoom!!, 0.01)
        assertEquals(55.0, target.pitchDeg!!, 0.2)
        at(10.0, 12_000, bannerM = 10.0, maneuver = 1)
        assertTrue(target.bearingDeg!! > 40.0 && target.bearingDeg!! <= 45.0, "${target.bearingDeg}")
        // at the corner itself the turn is still known (it was read while the line before it was ahead)
        at(0.0, 13_000, bannerM = 0.0, maneuver = 1)
        assertEquals(45.0, target.bearingDeg!!, 0.5)
        assertEquals(17.5, target.zoom!!, 0.01)
        assertEquals(50.0, target.pitchDeg!!, 0.2)
        // the vehicle is already on the new road while the banner still shows the turn: no lean past it
        at(0.0, 13_500, bannerM = 0.0, maneuver = 1, bearing = 90.0)
        assertEquals(90.0, target.bearingDeg!!, 0.5)
        at(0.0, 13_800, bannerM = 0.0, maneuver = 1, bearing = 0.0)
        // past the corner the banner shows the next maneuver; the position still points north: the map stays turned
        at(-5.0, 14_000, bannerM = 195.0, maneuver = 2, bearing = 0.0)
        assertEquals(45.0, target.bearingDeg!!, 0.5)
        assertEquals(60.0, target.pitchDeg!!, 1e-9, "the tilt and the zoom ease back at once")
        assertEquals(17.0, target.zoom!!, 1e-9)
        // the vehicle has turned: the map follows it again
        at(-20.0, 15_000, bannerM = 180.0, maneuver = 2, bearing = 90.0)
        assertEquals(90.0, target.bearingDeg!!, 0.5)
        at(-40.0, 16_000, bannerM = 160.0, maneuver = 2, bearing = 90.0)
        assertEquals(90.0, target.bearingDeg!!, 0.5)
    }

    @Test
    fun `the held lean ends after hold_ms when the vehicle never turns`() {
        at(60.0, 10_000, bannerM = 60.0, maneuver = 1)
        at(0.0, 11_000, bannerM = 0.0, maneuver = 1)
        at(-5.0, 12_000, bannerM = 195.0, maneuver = 2, bearing = 0.0)
        assertEquals(45.0, target.bearingDeg!!, 0.5)
        at(-5.0, 12_000 + lean.holdMs, bannerM = 195.0, maneuver = 2, bearing = 0.0)
        cam.tick()
        at(-6.0, 13_000 + lean.holdMs, bannerM = 194.0, maneuver = 2, bearing = 0.0)
        assertEquals(0.0, target.bearingDeg!!, 0.5)
    }

    /** The review of 2026-11-21: a missed turn and its re-route, or another trip, keep nothing of the old turn. */
    @Test
    fun `a new route or a vehicle off the route keeps no lean`() {
        at(60.0, 10_000, bannerM = 60.0, maneuver = 1)
        at(0.0, 11_000, bannerM = 0.0, maneuver = 1)
        assertEquals(45.0, target.bearingDeg!!, 0.5)
        // the car went straight on; the new route has another id: the map follows the car at once
        clock.now = 12_000
        val cur = RouteCursor(15, corner)
        val s = Fx.guiding(Fx.vehicle(corner, 12_000, bearing = 0.0, speed = 10.0), traveled = cur, bannerM = 500.0, route = Fx.choice(8, line))
        cam.onTrip(s)
        assertEquals(0.0, target.bearingDeg!!, 0.5)
        assertEquals(60.0, target.pitchDeg!!, 1e-9)
        // off the route while leaning: no lean and no hold
        at(60.0, 20_000, bannerM = 60.0, maneuver = 1)
        clock.now = 21_000
        val off = Fx.guiding(Fx.vehicle(cursor(40.0).point, 21_000, bearing = 0.0, speed = 10.0), traveled = cursor(40.0), bannerM = 40.0, route = Fx.choice(7, line))
        cam.onTrip(off.copy(guidance = off.guidance!!.copy(offRouteSuspected = true)))
        assertEquals(0.0, target.bearingDeg!!, 0.5)
    }

    @Test
    fun `no lean on a straight maneuver or at an arrival`() {
        // a "Continue" 100 m before the corner: the line goes straight on there
        at(160.0, 10_000, bannerM = 60.0, maneuver = 1)
        assertEquals(0.0, target.bearingDeg!!, 1e-6)
        assertEquals(60.0, target.pitchDeg!!, 1e-9)
        // the arrival at the end of the line
        val last = RouteCursor(24, line[24])
        clock.now = 20_000
        val s = Fx.guiding(Fx.vehicle(last.point, 20_000, bearing = 90.0, speed = 5.0), traveled = last, bannerM = 20.0, route = Fx.choice(7, line))
        cam.onTrip(s.copy(guidance = s.guidance!!.copy(banner = s.guidance!!.banner!!.copy(view = s.guidance!!.banner!!.view.copy(maneuverIndex = 3)))))
        assertEquals(60.0, target.pitchDeg!!, 1e-9)
    }
}
