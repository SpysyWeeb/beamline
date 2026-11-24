package io.github.spysyweeb.beamline.nav

import io.github.spysyweeb.beamline.api.nav.NavIntent
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.ProblemKind
import io.github.spysyweeb.beamline.api.nav.RouteOptions
import io.github.spysyweeb.beamline.api.nav.TripStop
import io.github.spysyweeb.beamline.location.Fix
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files

/**
 * What the session does when something underneath it fails: it must never end the app in the
 * middle of a drive (an exception on the session's thread would), and it must never be left half
 * way (a pack-change hold taken, a busy flag for ever). docs/architecture.md 1.2, 2.4, 3.
 */
class RobustnessTest {

    /** A follower that throws on its [failAt]th update (a stand-in for a native follower's error). */
    private class FailOn(val inner: RouteFollower, val failAt: Int) : RouteFollower by inner {
        var n = 0
        override fun update(fix: Fix): FollowerProgress {
            if (++n == failAt) throw IllegalStateException("follower failed")
            return inner.update(fix)
        }
    }

    @Test
    fun `a follower that fails on one fix does not end guidance - the next fixes are handled`() = sessionTest(
        followers = { r, rules -> FailOn(GeometricFollower(r.route, rules), failAt = 3) },
    ) { h ->
        h.guideExit24()
        h.drive(500.0, 600.0)                                 // five fixes; the third one fails in the follower
        assertEquals(listOf(IllegalStateException::class.java.name), h.session.core.failures, "caught, class name only")
        h.session.core.failures.clear()
        assertEquals(Phase.GUIDING, h.phase())
        assertEquals(600.0, h.state.vehicleAlong(), 1.0, "the fixes after it moved the vehicle")
        h.drive(625.0, 700.0)
        assertEquals(700.0, h.state.vehicleAlong(), 1.0)
    }

    @Test
    fun `a follower that cannot be made from the route refuses Start with ENGINE and releases the hold`() = sessionTest(
        followers = { _, _ -> throw IllegalArgumentException("no follower for this answer") },
    ) { h ->
        h.planExit24()
        h.send(NavIntent.Start())
        assertEquals(Phase.CHOOSING, h.phase())
        assertEquals(ProblemKind.ENGINE, h.state.problem!!.kind)
        assertTrue(h.state.problem!!.canRetry)
        assertEquals(false, h.state.replanning)
        assertEquals(1, h.holds)
        assertEquals(1, h.released, "the pack-change hold is given back")
        assertNull(h.store.load())
        assertTrue(h.session.core.failures.isEmpty(), "an expected failure, handled where it happens")
    }

    @Test
    fun `a simulated drive with an unknown script name is refused before guidance starts`() = sessionTest { h ->
        h.planExit24()
        h.send(NavIntent.Start(io.github.spysyweeb.beamline.api.nav.Simulation(scriptName = "wrongtrun")))
        assertEquals(Phase.CHOOSING, h.phase())
        assertEquals("There is no simulated drive with that name.", h.state.problem!!.text)
        assertEquals(0, h.holds, "no hold taken")
        assertNull(h.store.load())
        h.send(NavIntent.Start(io.github.spysyweeb.beamline.api.nav.Simulation(scriptName = "wrongturn")))
        assertEquals(Phase.GUIDING, h.phase(), "a known name starts")
    }

    @Test
    fun `a trip json that cannot be written does not stop guidance`() = runTest {
        val dir = Files.createTempDirectory("nav-unwritable").toFile()
        val blocker = File(dir, "not-a-folder").apply { writeText("x") }   // trip.json's folder is a file: every write fails
        val h = Harness(this, dir = dir, tripFile = File(blocker, "trip.json"))
        try {
            h.guideExit24()
            assertEquals(Phase.GUIDING, h.phase())
            assertTrue(h.session.core.tripWriteFailures >= 1, "the write at Start failed")
            h.drive(500.0, 500.0 + 25.0 * 35)                  // past a resume.refresh_s rewrite
            assertEquals(Phase.GUIDING, h.phase())
            assertTrue(h.session.core.tripWriteFailures >= 2)
            h.send(NavIntent.End)
            assertEquals(Phase.IDLE, h.phase())
            assertEquals(1, h.released)
            h.assertAllRecorded()
        } finally {
            h.close()
        }
    }

    @Test
    fun `P5 close while Start waits for the engine gives the pack-change hold back and guides nothing`() = sessionTest { h ->
        h.planExit24()
        h.routingGate.gated = true                             // the OSRM answer has not come yet
        h.send(NavIntent.Start())
        assertEquals(1, h.holds)
        assertEquals(0, h.released)
        h.session.close()
        h.run()
        h.routingGate.release()                                // the engine work that was waiting runs (and the engine closes)
        h.run()
        h.advance(2_000)
        assertEquals(1, h.released, "the hold Start took is given back: installs are not refused for ever")
        assertEquals(Phase.CHOOSING, h.phase(), "no guidance begins after close")
        assertNull(h.store.load())
    }

    @Test
    fun `R3 close while Resume waits for the engine gives the pack-change hold back - trip json stays`() = runTest {
        val dir = Files.createTempDirectory("close-resume").toFile()
        val wall = 1_763_280_000_000L
        TripStore(File(dir, "trip.json")).save(TripSnapshot(wall - 60_000L, listOf(TripStop(Data.DOWNS_DRIVE)), "simpler", RouteOptions()))
        val h = Harness(this, dir = dir, wallStartMs = wall)
        try {
            assertEquals(Phase.RESUME_OFFERED, h.phase())
            h.gps(Data.EXIT24_ORIGIN.point.lat, Data.EXIT24_ORIGIN.point.lon, 8f, 10f, 57f)
            h.routingGate.gated = true
            h.send(NavIntent.Resume)
            assertEquals(1, h.holds)
            h.session.close()
            h.run()
            h.routingGate.release()
            h.run()
            h.advance(2_000)
            assertEquals(1, h.released, "the hold Resume took is given back")
            assertEquals(Phase.RESUME_OFFERED, h.phase(), "no guidance begins after close")
            assertNotNull(h.store.load(), "kept for the next start (R2)")
            h.assertAllRecorded()
        } finally {
            h.close()
        }
    }

    @Test
    fun `close releases the hold, stops the drive and closes the engine - trip json stays for the resume offer`() = sessionTest { h ->
        h.guideExit24()
        h.drive(500.0, 550.0)
        val handled = h.rows.size
        h.close()
        assertEquals(1, h.released)
        assertTrue(h.engine.closed >= 1, "the engine is closed on the routing thread")
        h.at(600.0)
        h.advance(5_000)
        assertEquals(handled, h.rows.size, "no fix handled after close")
        assertNotNull(h.store.load(), "kept: the next start of the app offers to resume (R2)")
        h.send(NavIntent.End)                                  // ignored after close
        assertEquals(Phase.GUIDING, h.phase())
    }
}
