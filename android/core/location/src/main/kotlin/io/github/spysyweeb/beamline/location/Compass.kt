package io.github.spysyweeb.beamline.location

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Compass direction from a rotation matrix, as Android's `SensorManager.getRotationMatrixFromVector`
 * gives it for the rotation-vector sensor: a 3x3 row-major matrix R with world = R * device, world
 * axes East, North, Up.
 *
 * Which way "the phone points": the top edge of the screen while the phone lies flat or is tilted
 * up to 45 degrees; the back of the phone (where the camera looks) once it stands more upright,
 * as in a car mount. Taking whichever of the two has the longer horizontal shadow keeps the result
 * steady through the change-over. [displayRotation] is the quarter turns of the screen
 * (`Display.getRotation()`: 0..3), so landscape works too.
 */
object Compass {

    /** Degrees clockwise from magnetic north, 0..360; null when the matrix gives no direction. */
    fun azimuth(r: FloatArray, displayRotation: Int): Float? {
        require(r.size >= 9) { "3x3 rotation matrix expected" }
        // The screen's "up" in device coordinates. ROTATION_90: the phone turned counter-clockwise,
        // its right edge (+X) is now up.
        val up = when (displayRotation and 3) {
            0 -> floatArrayOf(0f, 1f, 0f)
            1 -> floatArrayOf(1f, 0f, 0f)
            2 -> floatArrayOf(0f, -1f, 0f)
            else -> floatArrayOf(-1f, 0f, 0f)
        }
        val back = floatArrayOf(0f, 0f, -1f)
        val a = toWorld(r, up)
        val b = toWorld(r, back)
        val ha = hypot(a[0], a[1])
        val hb = hypot(b[0], b[1])
        val v = if (ha >= hb) a else b
        if (maxOf(ha, hb) < 1e-3f) return null
        return normalize(Math.toDegrees(atan2(v[0].toDouble(), v[1].toDouble())).toFloat())
    }

    private fun toWorld(r: FloatArray, d: FloatArray) = floatArrayOf(
        r[0] * d[0] + r[1] * d[1] + r[2] * d[2],
        r[3] * d[0] + r[4] * d[1] + r[5] * d[2],
        r[6] * d[0] + r[7] * d[1] + r[8] * d[2],
    )

    fun normalize(deg: Float): Float {
        val d = deg % 360f
        return if (d < 0f) d + 360f else d
    }
}

/** Low-pass filter on the circle: 359 and 1 average to 0, not 180. */
class HeadingSmoother(private val weight: Double) {
    private var x = 0.0
    private var y = 0.0
    private var has = false

    fun add(deg: Float): Float {
        val rad = Math.toRadians(deg.toDouble())
        if (!has) {
            x = cos(rad)
            y = sin(rad)
            has = true
        } else {
            x += weight * (cos(rad) - x)
            y += weight * (sin(rad) - y)
        }
        return Compass.normalize(Math.toDegrees(atan2(y, x)).toFloat())
    }

    fun reset() {
        has = false
    }
}
