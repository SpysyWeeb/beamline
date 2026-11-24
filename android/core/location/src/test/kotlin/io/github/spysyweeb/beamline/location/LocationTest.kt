package io.github.spysyweeb.beamline.location

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.cos
import kotlin.math.sin

@OptIn(ExperimentalCoroutinesApi::class)
class LocationTest {

    private val rules = PositionRules()

    /** Peach St, Erie, PA, from W 26th St north to W 12th St (OSM, rounded; for tests only). */
    private val peachSt = """<?xml version="1.0"?>
        <gpx version="1.1" creator="Beamline test"><trk><trkseg>
          <trkpt lat="42.10862" lon="-80.08433"><time>2026-11-15T12:00:00Z</time></trkpt>
          <trkpt lat="42.11240" lon="-80.08432"><time>2026-11-15T12:00:20Z</time></trkpt>
          <trkpt lat="42.11618" lon="-80.08428"><time>2026-11-15T12:00:40Z</time></trkpt>
          <trkpt lat="42.12008" lon="-80.08423"><time>2026-11-15T12:01:00Z</time></trkpt>
        </trkseg></trk></gpx>"""

    @Test
    fun `gpx track points are read, with and without times`() {
        val pts = Gpx.parse(peachSt)
        assertEquals(4, pts.size)
        assertEquals(42.10862, pts[0].lat)
        assertEquals(-80.08433, pts[0].lon)
        assertEquals(20.0, pts[1].timeS!! - pts[0].timeS!!)
        assertNull(Gpx.parse("""<gpx><trk><trkseg><trkpt lat="1" lon="2"/></trkseg></trk></gpx>""").single().timeS)
        assertEquals(listOf(TrackPoint(1.0, 2.0)), Gpx.parse("""<gpx><wpt lat="1" lon='2'/></gpx>"""))
        // Heading north along Peach St.
        assertTrue(SimulatedPositionSource.bearing(pts[0], pts[1]) < 1f)
    }

    @Test
    fun `puck is blue when fresh and accurate, grey when old or poor`() {
        val fix = Fix(42.1, -80.08, accuracyM = 8f, elapsedMs = 10_000)
        assertEquals(PuckLook.LIVE, PuckTracker.stateFor(fix, null, 10_500, rules).look)
        val old = PuckTracker.stateFor(fix, null, 10_000 + 10_001, rules)
        assertEquals(PuckLook.GREY, old.look)
        assertEquals("old", old.why)
        val poor = PuckTracker.stateFor(fix.copy(accuracyM = 250f), null, 10_100, rules)
        assertEquals("poor", poor.why)
        assertEquals("poor", PuckTracker.stateFor(fix.copy(accuracyM = null), null, 10_100, rules).why)
        // The cone follows a recent compass reading and hides when the compass goes quiet.
        val h = Heading(32f, 10_000)
        assertEquals(32f, PuckTracker.stateFor(fix, h, 12_000, rules).coneDeg)
        assertNull(PuckTracker.stateFor(fix, h, 13_500, rules).coneDeg)
    }

    @Test
    fun `tracker follows a simulated drive and greys out when fixes stop`() = runTest {
        val clock = Clock { testScheduler.currentTime }
        val track = Gpx.parse(peachSt)
        val tracker = PuckTracker(
            SimulatedPositionSource(track, clock, intervalMs = 1000),
            SimulatedHeadingSource(listOf(350f, 10f, 10f, 10f), clock, intervalMs = 100),
            rules,
            clock,
        )
        tracker.start(backgroundScope)
        runCurrent()
        assertEquals(track[0].lat, tracker.puck.value!!.lat)
        assertEquals(PuckLook.LIVE, tracker.puck.value!!.look)
        // Compass 350, then 10, 10, 10: the smoothed cone crosses north, never swings through south.
        advanceTimeBy(350)
        val cone = tracker.puck.value!!.coneDeg!!
        assertTrue(cone > 350f || cone < 10f, "cone $cone")
        advanceTimeBy(20_000) // the track's own times: second point at 20 s
        runCurrent()
        assertEquals(track[1].lat, tracker.puck.value!!.lat)
        assertNull(tracker.puck.value!!.coneDeg, "compass readings ended 20 s ago")
        advanceTimeBy(40_000)
        runCurrent()
        assertEquals(track[3].lat, tracker.puck.value!!.lat)
        assertEquals(PuckLook.LIVE, tracker.puck.value!!.look)
        // The drive ended at 60 s; ten seconds later the last fix is old and the puck grey.
        advanceTimeBy(11_000)
        runCurrent()
        assertEquals(PuckLook.GREY, tracker.puck.value!!.look)
        assertEquals("old", tracker.puck.value!!.why)
        tracker.stop()
    }

    @Test
    fun `a network fix does not replace a fresh satellite fix`() {
        val gps = Fix(42.1, -80.08, 5f, 10_000, provider = "gps")
        val net = Fix(42.2, -80.1, 900f, 11_000, provider = "network")
        assertTrue(FixFilter.accept(gps, null, rules))
        assertEquals(false, FixFilter.accept(net, gps, rules))
        assertTrue(FixFilter.accept(net.copy(elapsedMs = 25_000), gps, rules), "the satellite fix is old by then")
        assertTrue(FixFilter.accept(net.copy(accuracyM = 4f), gps, rules))
        assertTrue(FixFilter.accept(gps.copy(elapsedMs = 12_000), net, rules))
        // Which providers count as satellite is a rule (assets/rules/position.json).
        val worseGps = gps.copy(elapsedMs = 12_000, accuracyM = 950f)
        assertTrue(FixFilter.accept(worseGps, net, rules))
        assertEquals(false, FixFilter.accept(worseGps, net, rules.copy(satelliteProviders = emptyList())))
    }

    @Test
    fun `compass smoothing wraps around north`() {
        val s = HeadingSmoother(0.5)
        assertEquals(350f, s.add(350f), 0.01f)
        val v = s.add(10f)
        assertTrue(v > 355f || v < 5f, "got $v")
    }

    @Test
    fun `heading from the rotation matrix, flat and upright, in portrait and landscape`() {
        // Phone flat, screen up, top edge pointing at azimuth a: device Y = (sin a, cos a, 0).
        fun flat(a: Double): FloatArray {
            val r = Math.toRadians(a)
            // columns: device X, Y, Z in world (East, North, Up)
            val x = doubleArrayOf(cos(r), -sin(r), 0.0)
            val y = doubleArrayOf(sin(r), cos(r), 0.0)
            val z = doubleArrayOf(0.0, 0.0, 1.0)
            return floatArrayOf(
                x[0].toFloat(), y[0].toFloat(), z[0].toFloat(),
                x[1].toFloat(), y[1].toFloat(), z[1].toFloat(),
                x[2].toFloat(), y[2].toFloat(), z[2].toFloat(),
            )
        }
        assertEquals(0f, Compass.azimuth(flat(0.0), 0)!!, 0.01f)
        assertEquals(90f, Compass.azimuth(flat(90.0), 0)!!, 0.01f)
        assertEquals(225f, Compass.azimuth(flat(225.0), 0)!!, 0.01f)
        // Landscape (ROTATION_90): the phone's right edge (+X) is the screen's top.
        assertEquals(90f, Compass.azimuth(flat(0.0), 1)!!, 0.01f)
        assertEquals(270f, Compass.azimuth(flat(0.0), 3)!!, 0.01f)
        // Upright in a car mount facing north: Y up, back of the phone (-Z) points north.
        // device X = East, Y = Up, Z = South (toward the driver).
        val upright = floatArrayOf(
            1f, 0f, 0f,
            0f, 0f, -1f,
            0f, 1f, 0f,
        )
        assertEquals(0f, Compass.azimuth(upright, 0)!!, 0.01f)
        assertNotNull(Compass.azimuth(upright, 1))
    }

    @Test
    fun `rules are read from the app's rules file`() {
        val r = PositionRules.parse(java.io.File("../../app/src/main/assets/rules/position.json").readText())
        assertEquals(30.0, r.staleAfterS)
        assertEquals(100.0, r.poorAccuracyM)
        assertEquals(listOf("gps"), r.satelliteProviders)
        assertEquals(25.0, r.bubble.showAboveM)
        assertEquals(120.0, r.bubble.stillWindowS)
    }
}
