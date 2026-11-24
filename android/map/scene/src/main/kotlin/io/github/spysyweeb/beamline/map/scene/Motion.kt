package io.github.spysyweeb.beamline.map.scene

import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.nav.Vehicle
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The time between the last two vehicle positions (rule K3): how long the arrow glides and the
 * camera moves from the previous position to the new one. One per map surface, in the mapper and
 * in the camera director alike, so both animate over the same time.
 *
 * A position is new when its time or its point changed; the same vehicle seen again (a scene built
 * for another reason) keeps the last glide. A gap longer than [MAX_GLIDE_MS] (fixes back after a
 * GPS loss, rule G4 and the `gaps` script of docs/architecture.md 8: "the arrow jumps ahead") gives
 * 0: the move is a jump, not a crawl over half a minute.
 */
internal class VehicleGlide {
    private var last: Vehicle? = null

    /** The glide of the last new position; 0 for the first one and after a jump. */
    var glideMs: Int = 0
        private set

    /** True when the last [update] was the first position after [reset] (nothing to glide from). */
    var first: Boolean = true
        private set

    /** Takes [v] and returns its glide. */
    fun update(v: Vehicle): Int {
        val prev = last
        if (prev == null) {
            glideMs = 0
            first = true
        } else if (prev.elapsedMs == v.elapsedMs && prev.point == v.point) {
            return glideMs
        } else {
            val d = v.elapsedMs - prev.elapsedMs
            glideMs = if (d in 1..MAX_GLIDE_MS) d.toInt() else 0
            first = false
        }
        last = v
        return glideMs
    }

    /** True when [v] is a position this glide has not seen yet. */
    fun isNew(v: Vehicle): Boolean = last.let { it == null || it.elapsedMs != v.elapsedMs || it.point != v.point }

    fun reset() {
        last = null
        glideMs = 0
        first = true
    }

    companion object {
        /**
         * Longest gap that still glides. Fixes come at 1 Hz and dead reckoning ticks every
         * guidance.json tick_s (1 s) in WEAK (rule G3), so a normal gap is 1-2.5 s; 3 s keeps one
         * missed fix gliding and turns a LOST gap (30 s and more) into a jump. Not in camera.json,
         * whose keys are fixed by the contract this round (docs/experiments/16-map-layers.md).
         */
        const val MAX_GLIDE_MS = 3_000L
    }
}

/**
 * Low-pass filter of a bearing on the circle (rule K3, camera.json guide.bearing_smoothing and
 * guide.bearing_turn_deg): each new bearing moves the smoothed one a share of the shorter way round,
 * so 350 and 10 meet at 0 and a U-turn in one position turns the camera smoothly instead of flipping it.
 */
internal class BearingFilter(private val weight: Double, private val turnDeg: Double = 0.0) {
    var value: Double? = null
        private set

    /**
     * K3: a low-pass on the circle. With [turnDeg] > 0 the weight grows with the size of the change,
     * from [weight] for none to 1 at [turnDeg] and above: the small wobble of straight driving stays
     * smoothed, a turn is followed at the next position (the camera eases over the glide, about 1 s).
     * It stays 1 up to four times [turnDeg] (120 degrees: a sharp corner of a skewed grid between two
     * fixes) and falls back to [weight] at 5.5 times (165), so a reversal or a glitch while stopped
     * (about 180) is smoothed as before and does not flip the map. No step anywhere.
     */
    fun add(deg: Double): Double {
        val cur = value
        val next = if (cur == null) normalizeDeg(deg) else {
            val d = signedDiff(deg, cur)
            val turn = Math.abs(d)
            val w = turnWeight(turn)
            normalizeDeg(cur + w * d)
        }
        value = next
        return next
    }

    /** The filter's weight for a change of [turn] degrees (0..180); see [add]. */
    internal fun turnWeight(turn: Double): Double = when {
        turnDeg <= 0.0 -> weight
        turn <= turnDeg -> weight + (1.0 - weight) * (turn / turnDeg)
        turn <= FULL_UP_TO * turnDeg -> 1.0
        turn < PLAIN_FROM * turnDeg -> 1.0 - (1.0 - weight) * (turn - FULL_UP_TO * turnDeg) / ((PLAIN_FROM - FULL_UP_TO) * turnDeg)
        else -> weight
    }

    private companion object {
        const val FULL_UP_TO = 4.0
        const val PLAIN_FROM = 5.5
    }

    /** Sets the value without smoothing (the first bearing of a drive). */
    fun set(deg: Double) {
        value = normalizeDeg(deg)
    }

    fun reset() {
        value = null
    }
}

/** 0 <= result < 360. */
internal fun normalizeDeg(deg: Double): Double {
    val d = deg % 360.0
    return if (d < 0.0) d + 360.0 else if (d >= 360.0) 0.0 else d
}

/** [to] - [from] the shorter way round, -180 < result <= 180. */
internal fun signedDiff(to: Double, from: Double): Double {
    var d = (to - from) % 360.0
    if (d <= -180.0) d += 360.0
    if (d > 180.0) d -= 360.0
    return d
}

/** Initial bearing from [a] to [b], degrees from true north (0..360). */
internal fun bearingDeg(a: GeoPoint, b: GeoPoint): Double {
    val la1 = Math.toRadians(a.lat)
    val la2 = Math.toRadians(b.lat)
    val dLon = Math.toRadians(b.lon - a.lon)
    val y = sin(dLon) * cos(la2)
    val x = cos(la1) * sin(la2) - sin(la1) * cos(la2) * cos(dLon)
    return normalizeDeg(Math.toDegrees(atan2(y, x)))
}

/** The bearing of the first piece of [line] that has a length; null for fewer than two distinct points. */
internal fun startBearing(line: List<GeoPoint>): Double? {
    val a = line.firstOrNull() ?: return null
    val b = line.firstOrNull { it != a } ?: return null
    return bearingDeg(a, b)
}

/** The box around every box and point given; null when there is none. */
internal fun union(boxes: List<Bounds>, points: List<GeoPoint>): Bounds? {
    val all = boxes.flatMap { listOf(GeoPoint(it.south, it.west), GeoPoint(it.north, it.east)) } + points
    return Bounds.around(all)
}
