package io.github.spysyweeb.beamline.location

import kotlinx.coroutines.flow.Flow
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The browsing position and its uncertainty bubble (docs/next-round.md item 3; rule G7 of
 * docs/architecture.md 3.1). Browsing only: guidance keeps its own route-snapped position.
 *
 * Read from position.json "bubble"; field names are the JSON keys.
 */
@Serializable
data class BubbleRules(
    /** The bubble shows when the estimate's radius is larger than this (and the puck then sits on the estimate). */
    @SerialName("show_above_m") val showAboveM: Double = 25.0,
    /** The bubble is never drawn larger than this. */
    @SerialName("max_m") val maxM: Double = 2000.0,
    /** Standing still: the fixes of the last [stillWindowS] seconds are averaged ... */
    @SerialName("still_window_s") val stillWindowS: Double = 120.0,
    /** ... at most this many of them. */
    @SerialName("still_max_fixes") val stillMaxFixes: Int = 12,
    /** Moving: the filter's process noise, the standard deviation of the acceleration (m/s²). */
    @SerialName("moving_accel_mps2") val movingAccelMps2: Double = 1.5,
    /** A fix farther from the estimate than this many combined radii starts the estimate again at the fix. */
    @SerialName("reset_sigmas") val resetSigmas: Double = 3.0,
    /** A fix reporting at least this speed means moving, whatever the accelerometer says ... */
    @SerialName("moving_speed_mps") val movingSpeedMps: Double = 1.0,
    /** ... when its accuracy radius is at most this (a poor fix's speed is noise). */
    @SerialName("speed_max_accuracy_m") val speedMaxAccuracyM: Double = 30.0,
    /** A fix without an accuracy radius counts as this inaccurate. */
    @SerialName("unknown_accuracy_m") val unknownAccuracyM: Double = 50.0,
    /** Accelerometer: the window the shake is measured over. */
    @SerialName("accel_window_s") val accelWindowS: Double = 2.0,
    /** Accelerometer: standing still when the standard deviation of |a| over the window is below this (m/s²). */
    @SerialName("still_below_mps2") val stillBelowMps2: Double = 0.15,
)

/** Whether the phone is standing still, from the accelerometer ([MotionDetector]). */
enum class PhoneMotion { STILL, MOVING, UNKNOWN }

/** One accelerometer reading, m/s², gravity included (Android's TYPE_ACCELEROMETER). */
data class AccelSample(val x: Float, val y: Float, val z: Float, val elapsedMs: Long)

/** Accelerometer readings, e.g. Android's sensor in :app. Cold: starts on collect. */
interface MotionSource {
    fun samples(): Flow<AccelSample>
}

/**
 * Standing still or moving, from the shake of the phone: the standard deviation of the magnitude
 * of the acceleration over the last accel_window_s. A phone on a table or held still varies by a
 * few hundredths of m/s²; walking by several m/s², a phone in a moving car by tenths. UNKNOWN until
 * most of a window has been seen, and when the readings stop.
 */
class MotionDetector(private val rules: BubbleRules) {
    private val window = ArrayDeque<Pair<Long, Double>>()

    fun add(s: AccelSample): PhoneMotion {
        val windowMs = (rules.accelWindowS * 1000).toLong()
        window.addLast(s.elapsedMs to sqrt((s.x * s.x + s.y * s.y + s.z * s.z).toDouble()))
        while (window.isNotEmpty() && window.first().first < s.elapsedMs - windowMs) window.removeFirst()
        return motion(s.elapsedMs)
    }

    /** The motion at [nowMs] (UNKNOWN when the last reading is older than a window). */
    fun motion(nowMs: Long): PhoneMotion {
        val windowMs = (rules.accelWindowS * 1000).toLong()
        if (window.size < 3 || nowMs - window.last().first > windowMs) return PhoneMotion.UNKNOWN
        if (window.last().first - window.first().first < windowMs * 0.75) return PhoneMotion.UNKNOWN
        val mean = window.sumOf { it.second } / window.size
        val sd = sqrt(window.sumOf { (it.second - mean) * (it.second - mean) } / window.size)
        return if (sd < rules.stillBelowMps2) PhoneMotion.STILL else PhoneMotion.MOVING
    }

    fun reset() = window.clear()
}

/** Where the phone most likely is and how sure that is: [radiusM] is about the 68 % radius, like a fix's accuracy. */
data class PositionEstimate(val lat: Double, val lon: Double, val radiusM: Double, val still: Boolean)

/**
 * The position estimate behind the browsing puck (rule G7). Two simple filters, one at a time:
 *
 * - Standing still (the accelerometer says so and no accurate fix reports a speed): the mean of the
 *   fixes of the last still_window_s, each weighted by 1 / accuracy² (an accurate fix counts far
 *   more than a poor one). Radius: the weighted spread of those fixes around the mean (the RMS
 *   distance), at least the mean's own error 1 / sqrt(sum of weights), so the bubble is about as
 *   wide as the fixes scatter and never claims more than they can give.
 * - Moving (or the accelerometer is not known): a constant-velocity Kalman filter per axis in local
 *   metres, measurement noise = accuracy², process noise from moving_accel_mps2. With accurate fixes
 *   it follows them within a metre or two; poor fixes are smoothed as far as the time between them
 *   allows. Radius: sqrt of the position variances of both axes.
 *
 * A fix farther from the estimate than reset_sigmas times the combined radius (the phone moved
 * while the accelerometer said still, or a far jump) starts the estimate again at that fix. Plain
 * Kotlin, no Android, no Google Play services; Android's own providers feed it (decision 6.79).
 */
class PositionEstimator(private val rules: BubbleRules) {
    private class Point(val x: Double, val y: Double, val w: Double, val ms: Long)

    // The local plane: metres east (x) and north (y) of [lat0], [lon0].
    private var lat0 = 0.0
    private var lon0 = 0.0
    private var hasOrigin = false
    private val still = ArrayDeque<Point>()

    // The moving filter: x, vx, and its covariance (pxx, pxv, pvv), the same for y.
    private var k: DoubleArray? = null
    private var lastMs = 0L
    private var last: PositionEstimate? = null

    val current: PositionEstimate? get() = last

    fun reset() {
        hasOrigin = false
        still.clear()
        k = null
        last = null
    }

    fun add(fix: Fix, motion: PhoneMotion): PositionEstimate {
        val acc = max(1.0, fix.accuracyM?.toDouble() ?: rules.unknownAccuracyM)
        if (!hasOrigin) {
            lat0 = fix.lat
            lon0 = fix.lon
            hasOrigin = true
        }
        val (x, y) = toLocal(fix.lat, fix.lon)
        val reportsSpeed = (fix.speedMps ?: 0f) >= rules.movingSpeedMps && acc <= rules.speedMaxAccuracyM
        val isStill = motion == PhoneMotion.STILL && !reportsSpeed
        val prev = last
        if (prev != null) {
            // Compared with where the estimate expects the phone now: moving, carried on at its velocity.
            val f = k
            val dt = ((fix.elapsedMs - lastMs) / 1000.0).coerceIn(0.0, 600.0)
            val (px, py) = if (f != null) f[0] + f[1] * dt to f[4] + f[5] * dt else toLocal(prev.lat, prev.lon)
            val grown = if (f != null) prev.radiusM + rules.movingAccelMps2 * dt * dt / 2 else prev.radiusM
            if (hypot(x - px, y - py) > rules.resetSigmas * hypot(grown, acc)) {
                still.clear()
                k = null
            }
        }
        val out = if (isStill) addStill(x, y, acc, fix.elapsedMs) else addMoving(x, y, acc, fix)
        lastMs = fix.elapsedMs
        // Keep the local plane near the phone (its error grows with the distance from the origin).
        if (hypot(x, y) > 50_000) {
            val keep = out
            reset()
            lat0 = keep.lat
            lon0 = keep.lon
            hasOrigin = true
            last = keep
            return keep
        }
        last = out
        return out
    }

    private fun addStill(x: Double, y: Double, acc: Double, ms: Long): PositionEstimate {
        val from = k
        if (from != null && still.isEmpty()) {
            // Just stopped: the moving filter's position starts the average with its own weight.
            val v = max(1.0, from[2] + from[6])
            still.addLast(Point(from[0], from[4], 1.0 / v, lastMs))
        }
        k = null
        still.addLast(Point(x, y, 1.0 / (acc * acc), ms))
        val oldest = ms - (rules.stillWindowS * 1000).toLong()
        while (still.size > 1 && (still.first().ms < oldest || still.size > rules.stillMaxFixes)) still.removeFirst()
        val sw = still.sumOf { it.w }
        val cx = still.sumOf { it.w * it.x } / sw
        val cy = still.sumOf { it.w * it.y } / sw
        val spread = sqrt(still.sumOf { it.w * ((it.x - cx) * (it.x - cx) + (it.y - cy) * (it.y - cy)) } / sw)
        val r = max(spread, 1.0 / sqrt(sw))
        val (lat, lon) = toGeo(cx, cy)
        return PositionEstimate(lat, lon, r, still = true)
    }

    private fun addMoving(x: Double, y: Double, acc: Double, fix: Fix): PositionEstimate {
        // Per axis: a fix's accuracy is about the RMS radial error, so each axis gets half its square.
        val r2 = acc * acc / 2
        var s = k
        if (s == null) {
            val base = last?.takeIf { still.isNotEmpty() }
            still.clear()
            // Start at the fix (or the still average, if it was just left), velocity from the fix when it has one.
            val v = fix.speedMps?.toDouble()?.takeIf { fix.bearingDeg != null }
            val b = Math.toRadians((fix.bearingDeg ?: 0f).toDouble())
            val vx = v?.let { it * sin(b) } ?: 0.0
            val vy = v?.let { it * cos(b) } ?: 0.0
            val vv = if (v != null) 4.0 else 25.0
            s = if (base != null) {
                val (bx, by) = toLocal(base.lat, base.lon)
                val p = base.radiusM * base.radiusM / 2
                doubleArrayOf(bx, vx, p, 0.0, by, vy, p, 0.0, vv, vv)
            } else {
                doubleArrayOf(x, vx, r2, 0.0, y, vy, r2, 0.0, vv, vv)
            }
            k = s
            if (base == null) {
                lastMs = fix.elapsedMs
                return estimateOf(s)
            }
        }
        val dt = ((fix.elapsedMs - lastMs) / 1000.0).coerceIn(0.0, 600.0)
        // Layout: [x, vx, pxx, pxv, y, vy, pyy, pyv, pvvx, pvvy]
        axis(s, 0, 1, 2, 3, 8, x, r2, dt)
        axis(s, 4, 5, 6, 7, 9, y, r2, dt)
        return estimateOf(s)
    }

    /** One axis of the constant-velocity filter: predict over [dt], then update with position [z] of variance [r2]. */
    private fun axis(s: DoubleArray, ip: Int, iv: Int, ipp: Int, ipv: Int, ivv: Int, z: Double, r2: Double, dt: Double) {
        val q = rules.movingAccelMps2 * rules.movingAccelMps2
        var p = s[ip] + s[iv] * dt
        val v = s[iv]
        var pp = s[ipp] + 2 * dt * s[ipv] + dt * dt * s[ivv] + q * dt * dt * dt * dt / 4
        var pv = s[ipv] + dt * s[ivv] + q * dt * dt * dt / 2
        var vv = s[ivv] + q * dt * dt
        val sInn = pp + r2
        val kp = pp / sInn
        val kv = pv / sInn
        val inn = z - p
        p += kp * inn
        val vNew = v + kv * inn
        val ppN = (1 - kp) * pp
        val pvN = (1 - kp) * pv
        val vvN = vv - kv * pv
        pp = ppN
        pv = pvN
        vv = vvN
        s[ip] = p
        s[iv] = vNew
        s[ipp] = pp
        s[ipv] = pv
        s[ivv] = vv
    }

    private fun estimateOf(s: DoubleArray): PositionEstimate {
        val (lat, lon) = toGeo(s[0], s[4])
        return PositionEstimate(lat, lon, sqrt(max(0.0, s[2]) + max(0.0, s[6])), still = false)
    }

    private fun toLocal(lat: Double, lon: Double): Pair<Double, Double> =
        (lon - lon0) * M_PER_DEG * cos(lat0 * PI / 180) to (lat - lat0) * M_PER_DEG

    private fun toGeo(x: Double, y: Double): Pair<Double, Double> =
        lat0 + y / M_PER_DEG to lon0 + x / (M_PER_DEG * cos(lat0 * PI / 180))

    companion object {
        /** Metres per degree of latitude (a sphere of 6371 km; plenty for a bubble). */
        const val M_PER_DEG = 6_371_000.0 * PI / 180
    }
}
