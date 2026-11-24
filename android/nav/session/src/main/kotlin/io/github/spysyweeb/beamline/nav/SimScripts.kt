package io.github.spysyweeb.beamline.nav

import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.nav.SimEvent
import io.github.spysyweeb.beamline.api.nav.Simulation
import io.github.spysyweeb.beamline.routing.model.Route

/**
 * The named scripts of [Simulation.scriptName] (docs/architecture.md section 8, "Variants"), built
 * from the route being guided at Start. They are written for the owner's exit 24 trip (maneuver 1
 * "Exit 24", 2 "Turn left onto US 19 / Peach St", 3 "Turn right onto Downs Dr") and placed by
 * maneuver index and distance, so on another route they do the same things at the same places
 * relative to its first maneuvers. Distances are along the route (the simulator's odometer).
 */
object SimScripts {
    val NAMES = listOf("default", "wrongturn", "gaps", "jitter", "drift", "tunnel")

    /** Sideways noise of a drive: the jitter script's 8 m unless the simulation asks for more. */
    fun lateralNoiseM(simulation: Simulation): Double =
        if (simulation.scriptName == "jitter") maxOf(simulation.lateralNoiseM, JITTER_NOISE_M) else simulation.lateralNoiseM

    /** The events of [simulation]: its named script built from [route], then its own script. */
    fun events(simulation: Simulation, route: Route): List<SimEvent> = build(simulation.scriptName, route) + simulation.script

    fun build(name: String?, route: Route): List<SimEvent> {
        if (name == null) return emptyList()
        require(name in NAMES) { "no simulation script named $name" }
        val g = RouteGeometry(route)
        fun at(k: Int): Double? = if (k < g.maneuverCount && !route.maneuvers[k].type.isDestination) g.maneuverAlong[k] else null
        val m1 = at(1)
        val m2 = at(2)
        val m3 = if (g.maneuverCount > 3) g.maneuverAlong[3] else null
        return when (name) {
            // a 20 s halt about 15 m before maneuver 2 (row 6 of the clean run)
            "default" -> listOfNotNull(m2?.let { SimEvent.Halt((it - 15.0).coerceAtLeast(0.0), 20.0) })
            // at the end of the ramp (maneuver 2), 400 m the other way along the road the route turns onto
            "wrongturn" -> listOfNotNull(m2?.let { a ->
                val after = g.bearingAt(a + 1.0) ?: g.bearingAt(a) ?: 0.0
                val start = g.pointAt(a)
                val points = (1..8).map { i -> RouteGeometry.move(start, after + 180.0, 50.0 * i) }
                SimEvent.Detour(a, points.map { GeoPoint(RouteSimulator.round6(it.lat), RouteSimulator.round6(it.lon)) }, WRONG_TURN_SPEED_MPS)
            })
            // no fixes for 8 s at 2,000 m, later for 35 s at 3,000 m
            "gaps" -> listOf(SimEvent.Gap(minOf(2000.0, g.lengthM), 8.0), SimEvent.Gap(minOf(3000.0, g.lengthM), 35.0))
            // no fixes for 60 s from 300 m before maneuver 1 (exit 24) to past the left turn
            "tunnel" -> listOfNotNull(m1?.let { SimEvent.Gap((it - 300.0).coerceAtLeast(0.0), 60.0) })
            // 8 m sideways noise all along (lateralNoiseM), one fix 70 m sideways halfway along stretch 2
            // (Peach St), one fix 15 s stale at 1,500 m
            "jitter" -> listOfNotNull(
                SimEvent.StaleFix(minOf(1500.0, g.lengthM), 15.0),
                m2?.let { a -> SimEvent.Jump(a + ((m3 ?: g.lengthM) - a) / 2.0, 70.0) },
            )
            // every fix 5 m to the right for 10 s at 1,000 m (on I-90)
            "drift" -> listOf(SimEvent.Drift(minOf(1000.0, g.lengthM), 10.0, 5.0))
            else -> emptyList()
        }
    }

    const val JITTER_NOISE_M = 8.0
    const val WRONG_TURN_SPEED_MPS = 12.0
}
