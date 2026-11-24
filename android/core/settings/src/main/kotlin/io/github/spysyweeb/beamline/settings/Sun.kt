package io.github.spysyweeb.beamline.settings

import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan

/**
 * The sun's height above the horizon on the phone, with no network: the equations of NOAA's solar
 * calculator (NOAA Global Monitoring Laboratory, "General Solar Position Calculations", after Jean
 * Meeus, Astronomical Algorithms; US government work, public domain). Good to about a minute for
 * sunrise and sunset between the polar circles; never throws, also where the sun does not rise or
 * set for days (polar night, midnight sun).
 */
object Sun {
    private const val RAD = PI / 180.0
    private const val DAY_MS = 86_400_000L

    /** The sun's elevation in degrees (geometric, no refraction) at [epochMs] (UTC) seen from [lat], [lon]. */
    fun elevationDeg(epochMs: Long, lat: Double, lon: Double): Double {
        val jd = epochMs / DAY_MS.toDouble() + 2440587.5
        val t = (jd - 2451545.0) / 36525.0
        val l0 = norm(280.46646 + t * (36000.76983 + t * 0.0003032))
        val m = 357.52911 + t * (35999.05029 - 0.0001537 * t)
        val e = 0.016708634 - t * (0.000042037 + 0.0000001267 * t)
        val c = sin(m * RAD) * (1.914602 - t * (0.004817 + 0.000014 * t)) +
            sin(2 * m * RAD) * (0.019993 - 0.000101 * t) + sin(3 * m * RAD) * 0.000289
        val omega = 125.04 - 1934.136 * t
        val lambda = l0 + c - 0.00569 - 0.00478 * sin(omega * RAD)
        val eps0 = 23.0 + (26.0 + (21.448 - t * (46.815 + t * (0.00059 - t * 0.001813))) / 60.0) / 60.0
        val eps = eps0 + 0.00256 * cos(omega * RAD)
        val decl = asin(sin(eps * RAD) * sin(lambda * RAD))
        val y = tan(eps * RAD / 2).let { it * it }
        val eqTimeMin = 4.0 / RAD * (
            y * sin(2 * l0 * RAD) - 2 * e * sin(m * RAD) + 4 * e * y * sin(m * RAD) * cos(2 * l0 * RAD) -
                0.5 * y * y * sin(4 * l0 * RAD) - 1.25 * e * e * sin(2 * m * RAD)
            )
        val utcMin = Math.floorMod(epochMs, DAY_MS) / 60_000.0
        val solarMin = utcMin + eqTimeMin + 4.0 * lon
        val hourAngle = (solarMin / 4.0 - 180.0) * RAD
        val cosZenith = (sin(lat * RAD) * sin(decl) + cos(lat * RAD) * cos(decl) * cos(hourAngle)).coerceIn(-1.0, 1.0)
        return 90.0 - acos(cosZenith) / RAD
    }

    /** The moment the sun crossed [elevationDeg]: going up ([rising]) or down. */
    data class Crossing(val epochMs: Long, val rising: Boolean)

    /**
     * Every crossing of [elevationDeg] from [fromMs] to [toMs], in order (sampled every 10 minutes,
     * then halved to a second). -0.833 gives sunrise and sunset (refraction and the sun's radius),
     * -6 civil dawn and dusk. None where the sun stays above or below it.
     */
    fun crossings(fromMs: Long, toMs: Long, lat: Double, lon: Double, elevationDeg: Double): List<Crossing> {
        val out = ArrayList<Crossing>()
        var a = fromMs
        var above = elevationDeg(a, lat, lon) > elevationDeg
        while (a < toMs) {
            val b = min(a + STEP_MS, toMs)
            val nowAbove = elevationDeg(b, lat, lon) > elevationDeg
            if (nowAbove != above) {
                var lo = a
                var hi = b
                while (hi - lo > 1_000L) {
                    val mid = (lo + hi) / 2
                    if ((elevationDeg(mid, lat, lon) > elevationDeg) == above) lo = mid else hi = mid
                }
                out += Crossing(hi, rising = nowAbove)
                above = nowAbove
            }
            a = b
        }
        return out
    }

    // Sampled every 10 minutes: the sun crosses each elevation at most twice a day, hours apart, so
    // no pair of crossings falls inside one step; SunRule re-evaluates every minute anyway.
    private const val STEP_MS = 10 * 60_000L

    private fun norm(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0
}

/**
 * Settings' map style Automatic (the owner, 2026-11-16, docs/next-round.md item 4): the night map
 * from civil dusk to civil dawn (the sun [thresholdDeg] = 6 degrees below the horizon), with a
 * margin of [marginDeg] on each side once a theme is shown, so a position that moves a little at
 * the boundary does not switch back and forth: the night map comes when the sun goes below
 * threshold - margin, the day map when it comes above threshold + margin (about 2 to 3 minutes
 * either way at Erie's latitude).
 */
class SunRule(val thresholdDeg: Double = -6.0, val marginDeg: Double = 0.5) {

    /** Whether the night map is shown at [epochMs] at [lat], [lon]; [wasDark]: the theme the rule chose before (null: none yet). */
    fun dark(epochMs: Long, lat: Double, lon: Double, wasDark: Boolean?): Boolean {
        val e = Sun.elevationDeg(epochMs, lat, lon)
        return when (wasDark) {
            null -> e < thresholdDeg
            true -> e < thresholdDeg + marginDeg
            false -> e < thresholdDeg - marginDeg
        }
    }

    /** When the theme [dark] changes next (within a day), or null (polar night or midnight sun). */
    fun nextSwitchMs(epochMs: Long, lat: Double, lon: Double, dark: Boolean): Long? {
        val at = if (dark) thresholdDeg + marginDeg else thresholdDeg - marginDeg
        return Sun.crossings(epochMs, epochMs + 86_400_000L, lat, lon, at).firstOrNull { it.rising == dark }?.epochMs
    }

    /** How long until the rule should look again: [CHECK_MS] (one minute), or less when the switch comes sooner. */
    fun delayMs(epochMs: Long, lat: Double, lon: Double, dark: Boolean): Long {
        val next = nextSwitchMs(epochMs, lat, lon, dark) ?: return CHECK_MS
        return (next - epochMs + 1_000L).coerceIn(1_000L, CHECK_MS)
    }

    companion object {
        /** The rule looks again every minute while the app is open (and when it comes to the front). */
        const val CHECK_MS = 60_000L
    }
}
