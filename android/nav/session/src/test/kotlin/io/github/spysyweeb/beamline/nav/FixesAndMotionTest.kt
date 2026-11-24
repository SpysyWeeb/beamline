package io.github.spysyweeb.beamline.nav

import io.github.spysyweeb.beamline.api.nav.GpsState
import io.github.spysyweeb.beamline.api.nav.Motion
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.location.Fix
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Rules G1-G6 (fixes and GPS state) and M1-M3 (motion), docs/architecture.md 3.1 and 3.6. */
class FixesAndMotionTest {

    @Test
    fun `G1 unusable fixes are dropped before the follower - network, stale, out of order, poor or unknown accuracy`() = sessionTest { h ->
        h.guideExit24()
        h.at(1000.0)
        assertEquals(1000.0, h.state.vehicleAlong(), 1.0)
        // (within 2.5 s, so GPS stays GOOD and nothing is dead-reckoned)
        h.at(1500.0, provider = "network", afterMs = 300)       // the network provider never guides
        h.at(1500.0, ageMs = 3_500, afterMs = 300)              // older than fixes.max_age_s
        h.at(1500.0, accuracy = 51f, afterMs = 300)             // worse than progress_max_accuracy_m
        h.advance(300)
        h.fix(Fix(EXIT24.pointAt(1500.0).lat, EXIT24.pointAt(1500.0).lon, null, h.scheduler.currentTime, 25f, null, "gps")) // unknown accuracy
        assertEquals(1000.0, h.state.vehicleAlong(), 1.0, "none of them moved the vehicle")
        assertEquals(GpsState.GOOD, h.state.gps)
        h.at(1050.0)
        assertEquals(1050.0, h.state.vehicleAlong(), 1.0)
        // out of order: older than the last usable fix
        h.advance(1_000)
        val p = EXIT24.pointAt(1500.0)
        h.fix(Fix(p.lat, p.lon, 5f, h.scheduler.currentTime - 1_500, 25f, null, "gps"))
        assertEquals(1050.0, h.state.vehicleAlong(), 1.0)
        // the session handled 7 fixes; 5 were dropped (the rows list every fix it saw)
        assertEquals(7, h.rows.size)
    }

    @Test
    fun `G2 WAITING until the first usable fix, GOOD, WEAK after 2_5 s, LOST after 30 s, GOOD again on one fix`() = sessionTest { h ->
        h.guideExit24()
        h.advance(1_000)
        assertEquals(GpsState.WAITING, h.state.gps)
        h.at(500.0, afterMs = 0)
        assertEquals(GpsState.GOOD, h.state.gps)
        h.advance(2_000)
        assertEquals(GpsState.GOOD, h.state.gps, "2 s old")
        h.advance(1_000)
        assertEquals(GpsState.WEAK, h.state.gps, "3 s old")
        h.advance(26_000)
        assertEquals(GpsState.WEAK, h.state.gps, "29 s old")
        h.advance(2_000)
        assertEquals(GpsState.LOST, h.state.gps, "31 s old")
        h.at(520.0, afterMs = 0)
        assertEquals(GpsState.GOOD, h.state.gps)
    }

    @Test
    fun `G3 WEAK dead-reckons along the route at the last speed, lowers the distance, never advances`() = sessionTest { h ->
        h.guideExit24()
        h.drive(3900.0, 4000.0)                     // 25 m/s, U = 1 (exit 24 at m(1))
        val s0 = h.state.shown!!
        h.advance(4_000)                            // WEAK
        assertEquals(GpsState.WEAK, h.state.gps)
        val along = h.state.vehicleAlong()
        assertTrue(along > 4050.0, "moved on to $along")
        assertTrue(h.state.shown!! < s0 - 50.0)
        assertTrue(h.state.guidance!!.vehicle!!.snapped)
        h.advance(10_000)                           // would pass exit 24: held at it, distance floor 0
        assertEquals(1, h.state.u, "dead reckoning never advances U")
        assertEquals(m(1), h.state.vehicleAlong(), 1.0)
        assertEquals(0.0, h.state.shown!!, 0.01)
        assertFalse(h.state.guidance!!.offRouteSuspected)
        assertEquals(Phase.GUIDING, h.phase())
    }

    @Test
    fun `G3 below the dead-reckoning speed the vehicle holds still in WEAK`() = sessionTest { h ->
        h.guideExit24()
        h.at(1000.0, speed = 1.5f)
        h.at(1001.5, speed = 1.5f)
        h.advance(5_000)
        assertEquals(GpsState.WEAK, h.state.gps)
        assertEquals(1001.5, h.state.vehicleAlong(), 1.0)
    }

    @Test
    fun `G4 LOST - the vehicle stays, the banner keeps its distance, motion unknown, nothing advances`() = sessionTest { h ->
        h.guideExit24()
        h.drive(2000.0, 2100.0)
        h.advance(31_000)
        assertEquals(GpsState.LOST, h.state.gps)
        val where = h.state.vehicleAlong()
        val shown = h.state.shown
        assertEquals(Motion.UNKNOWN, h.state.guidance!!.vehicle!!.motion)
        h.advance(20_000)
        assertEquals(where, h.state.vehicleAlong(), 0.01)
        assertEquals(shown, h.state.shown)
        assertEquals(1, h.state.u)
        assertEquals(Phase.GUIDING, h.phase(), "nothing re-routes")
    }

    @Test
    fun `G5 a lone sideways jump is suspected for one fix only, a steady 5 m drift never`() = sessionTest { h ->
        h.guideExit24()
        h.drive(500.0, 600.0)
        h.at(625.0, sideM = 70.0)
        assertTrue(h.state.guidance!!.offRouteSuspected, "the jump counts toward off-route (O2)")
        assertFalse(h.state.guidance!!.vehicle!!.snapped, "drawn at the raw fix while suspected")
        h.at(650.0)
        assertFalse(h.state.guidance!!.offRouteSuspected)
        assertEquals(Phase.GUIDING, h.phase())
        for (i in 0 until 10) {
            h.at(675.0 + 25.0 * i, sideM = 5.0)
            assertFalse(h.state.guidance!!.offRouteSuspected, "drift fix $i")
            assertTrue(h.state.guidance!!.vehicle!!.snapped)
            assertEquals(675.0 + 25.0 * i, h.state.vehicleAlong(), 1.0, "drawn at the snapped point")
        }
    }

    @Test
    fun `G6 a step back under 15 m on the same step is held, a larger one is taken`() = sessionTest { h ->
        h.guideExit24()
        h.at(1000.0)
        h.at(990.0, speed = 0.2f)
        assertEquals(1000.0, h.state.vehicleAlong(), 0.5, "10 m back: held")
        h.at(960.0, speed = 0.2f)
        assertEquals(960.0, h.state.vehicleAlong(), 0.5, "40 m back: taken")
    }

    @Test
    fun `M1 speed from the fix, else distance over time between the last two usable fixes`() = sessionTest { h ->
        h.guideExit24()
        h.at(1000.0, speed = 17f)
        assertEquals(17.0, h.state.guidance!!.vehicle!!.speedMps!!, 1e-6)
        h.at(1030.0, speed = null)
        assertEquals(30.0, h.state.guidance!!.vehicle!!.speedMps!!, 0.5)
    }

    @Test
    fun `M2 STOPPED after a second below 0_5, MOVING above 1, held in between, UNKNOWN before two fixes and in LOST`() = sessionTest { h ->
        h.guideExit24()
        h.at(1000.0, speed = 10f)
        assertEquals(Motion.UNKNOWN, h.state.guidance!!.vehicle!!.motion, "one usable fix")
        h.at(1010.0, speed = 10f)
        assertEquals(Motion.MOVING, h.state.guidance!!.vehicle!!.motion)
        h.at(1010.0, speed = 0.3f)
        assertEquals(Motion.MOVING, h.state.guidance!!.vehicle!!.motion, "slow for less than a second")
        h.at(1010.0, speed = 0.3f)
        assertEquals(Motion.STOPPED, h.state.guidance!!.vehicle!!.motion)
        h.at(1010.5, speed = 0.8f)
        assertEquals(Motion.STOPPED, h.state.guidance!!.vehicle!!.motion, "between the two thresholds the previous value holds")
        h.at(1012.0, speed = 1.5f)
        assertEquals(Motion.MOVING, h.state.guidance!!.vehicle!!.motion)
        h.advance(31_000)
        assertEquals(Motion.UNKNOWN, h.state.guidance!!.vehicle!!.motion, "LOST")
    }

    @Test
    fun `M3 the brake lenses' inputs - STOPPED with a live fix, never in LOST`() = sessionTest { h ->
        // brakesLit = motion STOPPED and not stale (M's MapSceneMapper); the session gives both
        h.guideExit24()
        h.at(1000.0, speed = 5f)
        h.at(1000.0, speed = 0f)
        h.at(1000.0, speed = 0f)
        val s = h.state
        assertEquals(Motion.STOPPED, s.guidance!!.vehicle!!.motion)
        assertEquals(GpsState.GOOD, s.gps)
        assertNotNull(s.guidance!!.vehicle)
        h.advance(31_000)
        assertEquals(GpsState.LOST, h.state.gps)
        assertEquals(Motion.UNKNOWN, h.state.guidance!!.vehicle!!.motion, "no lenses on a stale arrow")
        assertNull(h.state.problem)
    }
}
