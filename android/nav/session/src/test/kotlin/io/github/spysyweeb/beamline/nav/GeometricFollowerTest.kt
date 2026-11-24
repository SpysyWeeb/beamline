package io.github.spysyweeb.beamline.nav

import io.github.spysyweeb.beamline.location.Fix
import io.github.spysyweeb.beamline.routing.model.Geo
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The test follower against the contract of docs/architecture.md 2.4 (Ports.kt), on the exit 24 route. */
class GeometricFollowerTest {
    private val rules = GuidanceRules.bundled()
    private var t = 0L

    private fun fix(along: Double, side: Double = 0.0, accuracy: Float = 5f): Fix {
        t += 1_000
        val p = if (side == 0.0) EXIT24.pointAt(along) else EXIT24.offset(along, side)
        return Fix(p.lat, p.lon, accuracy, t, 20f, EXIT24.bearingAt(along)?.toFloat(), "gps")
    }

    private fun follower() = GeometricFollowerFactory().create(FollowerRoute(Data.exit24, "{}", 0), rules)

    @Test
    fun `the step advances on entry then exit with deciding fixes only`() {
        val f = follower()
        assertEquals(0, f.update(fix(4100.0)).stepIndex)
        // within entry_m and then exit_m past maneuver 1, but with fixes worse than decide_max_accuracy_m
        assertEquals(0, f.update(fix(m(1) - 5.0, accuracy = 40f)).stepIndex)
        assertEquals(0, f.update(fix(m(1) + 15.0, accuracy = 40f)).stepIndex)
        // deciding fixes: entry (within 20 m), then 10 m past
        assertEquals(0, f.update(fix(m(1) + 5.0)).stepIndex)
        val p = f.update(fix(m(1) + 12.0))
        assertEquals(1, p.stepIndex)
        assertEquals(m(2) - (m(1) + 12.0), p.toManeuverM, 0.5)
        assertEquals(0, p.legIndex)
    }

    @Test
    fun `progress - snapped point, lateral, remaining, a negative distance past the maneuver, never back`() {
        val f = follower()
        f.update(fix(1000.0))
        val p = f.update(fix(1025.0, side = 12.0))
        assertEquals(12.0, p.lateralM, 0.3)
        assertEquals(1025.0, EXIT24.alongOf(p.shapeIndex, p.snapped), 0.5)
        assertEquals(0.0, Geo.distanceM(EXIT24.pointAt(1025.0), p.snapped), 0.5, "snapped on the line")
        assertEquals(EXIT24.lengthM - 1025.0, p.remainingM, 0.5)
        assertTrue(p.remainingS > 0 && p.remainingS < Data.exit24.timeS)
        assertEquals(EXIT24.bearingAt(1025.0)!!, p.routeBearingDeg!!, 0.5)
        // past maneuver 1 without an entry (a gap): toManeuverM < 0, the step stays (the session forces it)
        f.update(fix(3000.0))
        val past = f.update(fix(m(1) + 60.0))
        assertEquals(0, past.stepIndex)
        assertEquals(-60.0, past.toManeuverM, 0.5)
        // advance() moves one step at once; a fix far behind never takes the step back
        assertEquals(1, f.advance().stepIndex)
        assertEquals(1, f.update(fix(500.0)).stepIndex)
        assertFalse(f.update(fix(m(1) + 70.0)).complete)
    }

    @Test
    fun `complete once advanced past the last maneuver`() {
        val f = follower()
        f.update(fix(100.0))
        var p = f.advance()
        repeat(5) { p = f.advance() }
        assertEquals(Data.exit24.maneuvers.lastIndex, p.stepIndex)
        assertTrue(p.complete)
    }
}
