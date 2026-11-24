package io.github.spysyweeb.beamline.location

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * Rule G7 (batch C2): the browsing position estimate and its uncertainty bubble, on synthetic fix
 * sequences around downtown Erie (Perry Square): indoors (a fix every 20 s at about 100 m, report
 * 11), walking, driving, and leaving a building. Seeded noise, so every run sees the same fixes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class BubbleTest {
    private val rules = BubbleRules()
    private val lat0 = 42.1292
    private val lon0 = -80.0851
    private val mPerDegLon = PositionEstimator.M_PER_DEG * cos(lat0 * PI / 180)

    private fun at(eastM: Double, northM: Double) = lat0 + northM / PositionEstimator.M_PER_DEG to lon0 + eastM / mPerDegLon
    private fun metres(lat: Double, lon: Double) = (lon - lon0) * mPerDegLon to (lat - lat0) * PositionEstimator.M_PER_DEG
    private fun dist(e: PositionEstimate, eastM: Double, northM: Double): Double {
        val (x, y) = metres(e.lat, e.lon)
        return hypot(x - eastM, y - northM)
    }

    /** A fix [sd] metres (per axis, Gaussian) around the true point east/north of the square. */
    private fun fix(rnd: Random, eastM: Double, northM: Double, sd: Double, acc: Float, ms: Long, speed: Float? = null, bearing: Float? = null): Fix {
        val (lat, lon) = at(eastM + gauss(rnd) * sd, northM + gauss(rnd) * sd)
        return Fix(lat, lon, acc, ms, speed, bearing, provider = "gps")
    }

    private fun gauss(rnd: Random): Double = sqrtMinus2Ln(rnd.nextDouble(1e-9, 1.0)) * cos(2 * PI * rnd.nextDouble())
    private fun sqrtMinus2Ln(u: Double) = kotlin.math.sqrt(-2 * kotlin.math.ln(u))

    @Test
    fun indoorsTheEstimateIsSteadierAndTheBubbleAsWideAsTheScatter() {
        // Standing in a building: a fix every 20 s, reported 100 m, scattered about 50 m per axis.
        val rnd = Random(11)
        val est = PositionEstimator(rules)
        var rawErr = 0.0
        var estErr = 0.0
        var rawJump = 0.0
        var estJump = 0.0
        var prevRaw: Pair<Double, Double>? = null
        var prevEst: PositionEstimate? = null
        var last: PositionEstimate? = null
        val n = 30
        for (i in 0 until n) {
            val f = fix(rnd, 0.0, 0.0, sd = 50.0, acc = 100f, ms = i * 20_000L)
            val e = est.add(f, PhoneMotion.STILL)
            val raw = metres(f.lat, f.lon)
            if (i >= 3) {
                rawErr += hypot(raw.first, raw.second)
                estErr += dist(e, 0.0, 0.0)
                rawJump += hypot(raw.first - prevRaw!!.first, raw.second - prevRaw.second)
                estJump += hypot(metres(e.lat, e.lon).first - metres(prevEst!!.lat, prevEst.lon).first,
                    metres(e.lat, e.lon).second - metres(prevEst.lat, prevEst.lon).second)
            }
            prevRaw = raw
            prevEst = e
            last = e
            assertTrue(e.still)
        }
        assertTrue(estErr < rawErr * 0.6, "estimate error ${estErr / (n - 3)} m vs raw ${rawErr / (n - 3)} m")
        assertTrue(estJump < rawJump * 0.35, "estimate moves ${estJump / (n - 3)} m per fix vs raw ${rawJump / (n - 3)} m")
        // The scatter is about 50 m per axis, about 70 m around the point: the bubble is about that wide.
        assertTrue(last!!.radiusM in 45.0..110.0, "radius ${last.radiusM}")
        // Through the puck: the bubble shows, the puck stands on the estimate, grey as before (100 m is the grey limit).
        val f = fix(rnd, 0.0, 0.0, sd = 50.0, acc = 100f, ms = n * 20_000L)
        val e = est.add(f, PhoneMotion.STILL)
        val p = PuckTracker.stateFor(f, null, f.elapsedMs, PositionRules(), e)
        assertEquals(e.lat, p.lat)
        assertEquals(kotlin.math.round(e.radiusM).toFloat(), p.bubbleM)
        assertEquals(PuckLook.LIVE, p.look)
    }

    @Test
    fun aSingleFixGivesItsOwnAccuracyAndAnAccurateFixNoBubble() {
        val est = PositionEstimator(rules)
        val (lat, lon) = at(0.0, 0.0)
        val poor = Fix(lat, lon, 400f, 0, provider = "network")
        val e = est.add(poor, PhoneMotion.UNKNOWN)
        assertEquals(400.0, e.radiusM, 0.5)
        assertEquals(400f, PuckTracker.stateFor(poor, null, 0, PositionRules(), e).bubbleM)
        // A good GPS fix: no bubble, the puck on the fix itself (no lag).
        val good = Fix(lat + 0.0001, lon, 6f, 1_000, provider = "gps")
        val g = PuckTracker.stateFor(good, null, 1_000, PositionRules(), PositionEstimator(rules).add(good, PhoneMotion.STILL))
        assertNull(g.bubbleM)
        assertEquals(good.lat, g.lat)
        assertEquals(good.lon, g.lon)
        // The bubble is capped.
        val huge = Fix(lat, lon, 9000f, 0, provider = "network")
        assertEquals(2000f, PuckTracker.stateFor(huge, null, 0, PositionRules(), PositionEstimator(rules).add(huge, PhoneMotion.UNKNOWN)).bubbleM)
    }

    @Test
    fun walkingWithGpsNoBubbleAndNoLag() {
        // 1.4 m/s north, a fix a second at 8 m with 4 m of noise; the accelerometer says moving.
        val rnd = Random(12)
        val est = PositionEstimator(rules)
        var worst = 0.0
        var estErr = 0.0
        var rawErr = 0.0
        for (i in 0..120) {
            val north = 1.4 * i
            val f = fix(rnd, 0.0, north, sd = 4.0, acc = 8f, ms = i * 1000L, speed = 1.4f, bearing = 0f)
            val e = est.add(f, PhoneMotion.MOVING)
            if (i > 10) {
                worst = maxOf(worst, dist(e, 0.0, north))
                estErr += dist(e, 0.0, north)
                val (x, y) = metres(f.lat, f.lon)
                rawErr += hypot(x, y - north)
            }
            assertTrue(e.radiusM < rules.showAboveM, "radius ${e.radiusM} at $i")
            assertTrue(!e.still)
        }
        assertTrue(worst < 10.0, "worst error $worst m")
        assertTrue(estErr < rawErr, "the filter is closer than the raw fixes: ${estErr / 110} vs ${rawErr / 110} m")
    }

    @Test
    fun drivingFollowsEvenWhenTheAccelerometerSaysStill() {
        // 25 m/s east (a phone in a smooth mount: the accelerometer reads still), 5 m fixes reporting their speed.
        val rnd = Random(13)
        val est = PositionEstimator(rules)
        var worst = 0.0
        var estErr = 0.0
        var rawErr = 0.0
        for (i in 0..90) {
            val east = 25.0 * i
            val f = fix(rnd, east, 0.0, sd = 3.0, acc = 5f, ms = i * 1000L, speed = 25f, bearing = 90f)
            val e = est.add(f, PhoneMotion.STILL)
            if (i > 5) {
                worst = maxOf(worst, dist(e, east, 0.0))
                estErr += dist(e, east, 0.0)
                val (x, y) = metres(f.lat, f.lon)
                rawErr += hypot(x - east, y)
            }
            assertTrue(!e.still, "a fix reporting 25 m/s is moving")
            assertTrue(e.radiusM < rules.showAboveM)
        }
        assertTrue(worst < 12.0, "worst error $worst m (no lag: a lag of one second would be 25 m)")
        assertTrue(estErr < rawErr, "the filter is closer than the raw fixes: ${estErr / 85} vs ${rawErr / 85} m")
    }

    @Test
    fun walkingOutOfABuildingStartsAgainAtTheFirstGoodFix() {
        val rnd = Random(14)
        val est = PositionEstimator(rules)
        for (i in 0 until 6) est.add(fix(rnd, 0.0, 0.0, sd = 50.0, acc = 100f, ms = i * 20_000L), PhoneMotion.STILL)
        // 400 m away, accurate: farther than 3 combined radii, so the old average is dropped.
        val e = est.add(fix(rnd, 400.0, 0.0, sd = 2.0, acc = 5f, ms = 121_000L), PhoneMotion.MOVING)
        assertTrue(dist(e, 400.0, 0.0) < 10.0, "error ${dist(e, 400.0, 0.0)}")
        assertTrue(e.radiusM < rules.showAboveM)
    }

    @Test
    fun theMotionDetectorTellsStillFromMoving() {
        val d = MotionDetector(rules)
        assertEquals(PhoneMotion.UNKNOWN, d.motion(0))
        val rnd = Random(15)
        var m = PhoneMotion.UNKNOWN
        // On a table: gravity and a little sensor noise, 50 readings a second for 3 s.
        for (i in 0..150) m = d.add(AccelSample(0.01f * rnd.nextFloat(), 0.01f * rnd.nextFloat(), 9.81f + 0.02f * rnd.nextFloat(), i * 20L))
        assertEquals(PhoneMotion.STILL, m)
        // Walking: about 2 steps a second, +-2 m/s^2.
        for (i in 151..300) m = d.add(AccelSample(0.3f, 0.2f, 9.81f + 2f * sin(2 * PI * 2 * i * 0.02).toFloat(), i * 20L))
        assertEquals(PhoneMotion.MOVING, m)
        // The readings stop: not known after a window.
        assertEquals(PhoneMotion.UNKNOWN, d.motion(300 * 20L + 2_500))
    }

    @Test
    fun theTrackerDrawsTheBubbleIndoorsWithTheAccelerometer() = runTest {
        val clock = Clock { testScheduler.currentTime }
        val rnd = Random(16)
        val fixes = object : PositionSource {
            override fun fixes(): Flow<Fix> = flow {
                for (i in 0 until 8) {
                    emit(fix(rnd, 0.0, 0.0, sd = 50.0, acc = 100f, ms = clock.elapsedMs()))
                    delay(20_000)
                }
            }
        }
        val still = object : MotionSource {
            override fun samples(): Flow<AccelSample> = flow {
                while (true) {
                    emit(AccelSample(0f, 0f, 9.81f, clock.elapsedMs()))
                    delay(100)
                }
            }
        }
        val tracker = PuckTracker(fixes, null, PositionRules(staleAfterS = 30.0), clock, still)
        tracker.start(backgroundScope)
        advanceTimeBy(150_000)
        runCurrent()
        val p = tracker.puck.value
        assertNotNull(p)
        assertNotNull(p!!.bubbleM, "bubble while indoors")
        assertTrue(p.bubbleM!! in 30f..150f, "bubble ${p.bubbleM}")
        val last = tracker.lastFix.value!!
        assertTrue(p.lat != last.lat || p.lon != last.lon, "the puck stands on the estimate, not the last fix")
        tracker.stop()
    }
}
