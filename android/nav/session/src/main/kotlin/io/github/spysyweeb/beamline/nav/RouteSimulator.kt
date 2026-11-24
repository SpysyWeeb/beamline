package io.github.spysyweeb.beamline.nav

import io.github.spysyweeb.beamline.api.nav.SimEvent
import io.github.spysyweeb.beamline.api.nav.Simulation
import io.github.spysyweeb.beamline.location.Clock
import io.github.spysyweeb.beamline.location.Fix
import io.github.spysyweeb.beamline.location.PositionSource
import io.github.spysyweeb.beamline.routing.model.LatLon
import io.github.spysyweeb.beamline.routing.model.Route
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.Random
import kotlin.math.roundToLong

/**
 * The built-in drive along the route being guided (docs/architecture.md 2.4, decision 6.29): a
 * [PositionSource] of provider "simulated" that drives [route] (read again on every step, so after
 * a re-route or a stop change the car follows the route the session guides then) from its start.
 *
 * - Speed: each maneuver's stretch at its own speed (lengthM / timeS) times
 *   [Simulation.speedFactor]; a stretch without a time keeps the previous speed.
 * - One fix every 1 / [Simulation.fixHz] simulated seconds (delay, so a test's virtual clock
 *   drives it), stamped with [clock], accuracy [Simulation.accuracyM], speed and the route's
 *   bearing; sideways noise from a normal distribution of [Simulation.lateralNoiseM] metres
 *   (seeded with [Simulation.seed]); positive offsets are to the right of the route.
 * - [events] (the named script plus [Simulation.script], see [SimScripts]) happen when the car's
 *   odometer (metres driven since Start, detours included) reaches their [SimEvent.atM]; a Halt
 *   stops the car exactly there.
 * - At the route's end the car stands still and fixes go on (the session ends the drive).
 */
class RouteSimulator(
    private val route: () -> Route,
    private val simulation: Simulation,
    events: List<SimEvent>,
    private val clock: Clock,
) : PositionSource {
    private val events = events.sortedBy { it.atM }

    override fun fixes(): Flow<Fix> = flow {
        val dtS = 1.0 / simulation.fixHz.coerceAtLeast(0.01)
        val dtMs = (dtS * 1000.0).roundToLong().coerceAtLeast(1)
        val rnd = Random(simulation.seed)
        var current = route()
        var geo = RouteGeometry(current)
        var along = 0.0
        var odometer = 0.0
        var speed = 0.0
        val pending = events.toMutableList()
        var haltUntil = Long.MIN_VALUE
        var gapUntil = Long.MIN_VALUE
        var driftUntil = Long.MIN_VALUE
        var driftM = 0.0
        var poorUntil = Long.MIN_VALUE
        var poorM = 0.0
        var jumpM: Double? = null
        var staleS: Double? = null
        var detour: ArrayDeque<LatLon>? = null
        var detourSpeed = 0.0
        var detourPos: LatLon? = null
        var first = true

        while (true) {
            val now = clock.elapsedMs()
            if (!first) {
                // follow the route the session guides now (a re-route or a stop change replaces it)
                val r = route()
                if (r !== current && detour == null) {
                    val pos = geo.pointAt(along)
                    current = r
                    geo = RouteGeometry(r)
                    along = geo.project(pos).alongM
                }
                // move for one step
                var move = when {
                    now < haltUntil -> 0.0
                    detour != null -> detourSpeed * dtS
                    else -> stretchSpeed(geo, current, along, speed) * dtS
                }
                speed = move / dtS
                // a Halt ahead within this step stops the car exactly at it
                val halt = pending.firstOrNull { it is SimEvent.Halt && it.atM <= odometer + move }
                if (halt != null && move > 0.0) move = (halt.atM - odometer).coerceAtLeast(0.0)
                if (detour != null) {
                    var left = move
                    var pos = detourPos!!
                    while (left > 0.0 && detour.isNotEmpty()) {
                        val next = detour.first()
                        val d = io.github.spysyweeb.beamline.routing.model.Geo.distanceM(pos, next)
                        if (d <= left) {
                            pos = next
                            left -= d
                            detour.removeFirst()
                        } else {
                            pos = RouteGeometry.move(pos, RouteGeometry.bearing(pos, next), left)
                            left = 0.0
                        }
                    }
                    detourPos = pos
                    if (detour.isEmpty()) {
                        // back on whatever route is guided now, from its nearest point
                        detour = null
                        current = route()
                        geo = RouteGeometry(current)
                        along = geo.project(pos).alongM
                        detourPos = null
                    }
                } else {
                    along = (along + move).coerceAtMost(geo.lengthM)
                    if (along >= geo.lengthM) speed = 0.0
                }
                odometer += move
            }
            first = false
            // events reached by the odometer
            val due = pending.filter { it.atM <= odometer + 1e-6 }
            pending.removeAll(due)
            for (e in due) when (e) {
                is SimEvent.Halt -> { haltUntil = now + (e.seconds * 1000).roundToLong(); speed = 0.0 }
                is SimEvent.Gap -> gapUntil = now + (e.seconds * 1000).roundToLong()
                is SimEvent.Jump -> jumpM = e.sidewaysM
                is SimEvent.Drift -> { driftUntil = now + (e.seconds * 1000).roundToLong(); driftM = e.sidewaysM }
                is SimEvent.PoorAccuracy -> { poorUntil = now + (e.seconds * 1000).roundToLong(); poorM = e.accuracyM }
                is SimEvent.StaleFix -> staleS = e.ageS
                is SimEvent.Detour -> if (e.points.isNotEmpty()) {
                    detourPos = geo.pointAt(along)
                    detour = ArrayDeque(e.points.map { LatLon(it.lat, it.lon) })
                    detourSpeed = e.speedMps
                }
            }
            // the fix of this step
            val noise = if (simulation.lateralNoiseM > 0.0) rnd.nextGaussian() * simulation.lateralNoiseM else 0.0
            if (now >= gapUntil) {
                val side = noise + (if (now < driftUntil) driftM else 0.0) + (jumpM ?: 0.0)
                jumpM = null
                val (point, bearing) = if (detourPos != null) {
                    val target = detour?.firstOrNull()
                    detourPos!! to (target?.let { RouteGeometry.bearing(detourPos!!, it) })
                } else geo.pointAt(along) to geo.bearingAt(along)
                val shown = if (side != 0.0 && bearing != null) RouteGeometry.move(point, bearing + 90.0, side) else point
                val stamp = staleS?.let { now - (it * 1000).roundToLong() } ?: now
                staleS = null
                emit(Fix(
                    lat = round6(shown.lat),
                    lon = round6(shown.lon),
                    accuracyM = (if (now < poorUntil) poorM else simulation.accuracyM).toFloat(),
                    elapsedMs = stamp,
                    speedMps = speed.toFloat(),
                    bearingDeg = bearing?.toFloat(),
                    provider = PROVIDER,
                ))
            } else {
                staleS = null
            }
            delay(dtMs)
        }
    }

    /** Route speed of the stretch holding [along]: its length over its time, times the speed factor. */
    private fun stretchSpeed(geo: RouteGeometry, route: Route, along: Double, previous: Double): Double {
        if (along >= geo.lengthM) return 0.0
        val k = geo.stepAt(along)
        val m = route.maneuvers[k]
        val v = if (m.timeS > 0.0 && m.lengthM > 0.0) m.lengthM / m.timeS else previous.takeIf { it > 0.0 } ?: DEFAULT_SPEED_MPS
        return v * simulation.speedFactor
    }

    companion object {
        const val PROVIDER = "simulated"
        private const val DEFAULT_SPEED_MPS = 10.0

        /** Simulated fixes carry 6 decimals (about 0.1 m), as a phone's do; requests made from them stay short and exact. */
        fun round6(v: Double): Double = Math.round(v * 1e6) / 1e6
    }
}
