package io.github.spysyweeb.beamline.map.scene

import kotlin.math.PI
import kotlin.math.abs

/**
 * K1a (0.1.30, the owner: "a super small amount of smoothing without adding any kind of delay"): the
 * One Euro filter (Casiez, Roussel and Vogel, CHI 2012) on the compass. Its cut-off rises with the
 * speed of the turn, so a phone held still is calmed and a turn passes almost untouched. Simulated at
 * 125 readings a second (docs/architecture.md K1a): turning at a steady speed the map is under 0.1
 * degree behind; when a turn starts from still at most about 0.4 degree (2 ms at a quick turn); a
 * phone held still wobbles about half as much as the sensor. Degrees, across north without a swing.
 */
internal class CompassFilter(
    private val minCutoffHz: Double = MIN_CUTOFF_HZ,
    private val beta: Double = BETA,
    private val derivativeCutoffHz: Double = DERIVATIVE_CUTOFF_HZ,
) {
    private var x = 0.0
    private var dx = 0.0
    private var lastMs = 0L
    private var started = false

    /** Start again: the next reading is taken as it is. */
    fun reset() {
        started = false
    }

    /** The smoothed bearing (0 until 360) after the reading [deg] at [atMs]. */
    fun next(deg: Double, atMs: Long): Double {
        if (!started) {
            started = true
            x = normalize(deg)
            dx = 0.0
            lastMs = atMs
            return x
        }
        val te = (atMs - lastMs).coerceAtLeast(1L) / 1000.0
        lastMs = atMs
        val z = x + signedDiff(deg, x) // the reading nearest the last output: no swing through 360
        dx += alpha(te, derivativeCutoffHz) * ((z - x) / te - dx)
        x = normalize(x + alpha(te, minCutoffHz + beta * abs(dx)) * (z - x))
        return x
    }

    private fun alpha(te: Double, cutoffHz: Double): Double {
        val r = 2 * PI * cutoffHz * te
        return r / (r + 1)
    }

    private fun normalize(d: Double): Double = ((d % 360.0) + 360.0) % 360.0

    companion object {
        const val MIN_CUTOFF_HZ = 1.0
        const val BETA = 2.0
        const val DERIVATIVE_CUTOFF_HZ = 3.0
    }
}
