package io.github.spysyweeb.beamline.nav

import io.github.spysyweeb.beamline.api.nav.GpsState
import io.github.spysyweeb.beamline.api.nav.NavIntent
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.ReplaceReason
import io.github.spysyweeb.beamline.api.nav.SimEvent
import io.github.spysyweeb.beamline.api.nav.Simulation
import io.github.spysyweeb.beamline.api.nav.TripEvent
import io.github.spysyweeb.beamline.api.nav.TripState
import io.github.spysyweeb.beamline.routing.model.LatLon
import io.github.spysyweeb.beamline.routing.model.ManeuverType
import io.github.spysyweeb.beamline.routing.valhalla.ValhallaResponses
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The named simulation scripts of docs/architecture.md section 8 ("Variants") driven through the
 * session on the exit 24 trip, each with what the variant must show; and the 3-stop OSRM check.
 */
class SimulationScriptsTest {

    /** Drives with [sim] until ARRIVED (or [maxS]); returns the state after every second. */
    private fun Harness.simulate(sim: Simulation, maxS: Int = 900): List<TripState> {
        guideExit24(sim)
        val out = ArrayList<TripState>()
        var s = 0
        while (phase() != Phase.ARRIVED && s++ < maxS) {
            advance(1_000)
            out += state
        }
        return out
    }

    @Test
    fun `the scripts are built from the route - names, places and the jitter noise`() {
        val r = Data.exit24
        assertEquals(listOf(SimEvent.Halt(m(2) - 15.0, 20.0)), SimScripts.build("default", r))
        assertEquals(listOf(SimEvent.Gap(2000.0, 8.0), SimEvent.Gap(3000.0, 35.0)), SimScripts.build("gaps", r))
        assertEquals(listOf(SimEvent.Gap(m(1) - 300.0, 60.0)), SimScripts.build("tunnel", r))
        assertEquals(listOf(SimEvent.Drift(1000.0, 10.0, 5.0)), SimScripts.build("drift", r))
        val wrong = SimScripts.build("wrongturn", r).single() as SimEvent.Detour
        assertEquals(m(2), wrong.atM, 1e-9)
        assertEquals(8, wrong.points.size)
        val end = wrong.points.last()
        assertEquals(400.0, io.github.spysyweeb.beamline.routing.model.Geo.distanceM(EXIT24.pointAt(m(2)), LatLon(end.lat, end.lon)), 2.0)
        assertEquals(8.0, SimScripts.lateralNoiseM(Simulation(scriptName = "jitter")))
        assertEquals(0.0, SimScripts.lateralNoiseM(Simulation(scriptName = "default")))
        assertTrue(SimScripts.build(null, r).isEmpty())
    }

    @Test
    fun `wrongturn - REROUTING on the fifth off fix, a new route, U never advanced while off the line`() = sessionTest { h ->
        val states = h.simulate(Simulation(scriptName = "wrongturn"))
        assertEquals(Phase.ARRIVED, h.phase(), "arrived after the re-route")
        val replaced = h.eventsOf(TripEvent.RouteReplaced::class.java).map { (it as TripEvent.RouteReplaced).reason }
        println("wrongturn: ${replaced.size} re-route(s), arrived after ${states.size} s")
        assertTrue(replaced.isNotEmpty() && replaced.all { it == ReplaceReason.REROUTE }, "$replaced")
        // the fifth off fix confirms (3 off fixes spanning at least 4 s at 1 fix/s)
        val rows = h.rows.map { it.second }
        val firstOff = rows.indexOfFirst { it.guidance?.offRouteSuspected == true }
        val firstRerouting = rows.indexOfFirst { it.phase == Phase.REROUTING || it.guidance?.route?.id != rows[0].guidance?.route?.id }
        assertEquals(firstOff + 4, firstRerouting, "the fifth off fix")
        // U never advanced while off the line; the arrow left the line only while suspected
        val uBefore = rows[firstOff - 1].u
        for (i in firstOff until firstRerouting) assertEquals(uBefore, rows[i].u)
        assertTrue(rows.all { it.guidance?.vehicle == null || it.guidance!!.vehicle!!.snapped || it.guidance!!.offRouteSuspected || it.phase != Phase.GUIDING })
        assertTrue(states.any { it.phase == Phase.REROUTING } || h.rows.any { it.second.phase == Phase.REROUTING })
    }

    @Test
    fun `gaps - 8 s WEAK with the arrow moving on, 35 s WEAK then LOST, back to GOOD without a re-route`() = sessionTest { h ->
        val states = h.simulate(Simulation(scriptName = "gaps"))
        assertEquals(Phase.ARRIVED, h.phase())
        val gps = states.map { it.gps }
        assertTrue(GpsState.WEAK in gps && GpsState.LOST in gps, "$gps")
        val firstWeak = gps.indexOf(GpsState.WEAK)
        // during the first gap the arrow keeps moving along the line and the distance falls
        val weakRun = states.drop(firstWeak).takeWhile { it.gps == GpsState.WEAK }
        assertTrue(weakRun.size >= 4)
        assertTrue(weakRun.zipWithNext().all { (a, b) -> b.vehicleAlong() >= a.vehicleAlong() && b.shown!! <= a.shown!! })
        assertTrue(weakRun.last().vehicleAlong() > weakRun.first().vehicleAlong() + 50.0)
        assertTrue(states.none { it.phase == Phase.REROUTING })
        assertTrue(h.eventsOf(TripEvent.RouteReplaced::class.java).isEmpty())
        // LOST: the arrow stays, the distance is frozen
        val lost = states.filter { it.gps == GpsState.LOST }
        assertTrue(lost.map { it.vehicleAlong() }.distinct().size == 1 && lost.map { it.shown }.distinct().size == 1)
        assertEquals(GpsState.GOOD, states.last().gps)
    }

    @Test
    fun `tunnel - LOST in the gap, Turn right onto Downs Dr on the first fix after it, no REROUTING`() = sessionTest { h ->
        val states = h.simulate(Simulation(scriptName = "tunnel"))
        assertEquals(Phase.ARRIVED, h.phase())
        assertTrue(states.any { it.gps == GpsState.LOST })
        val rows = h.rows.map { it.second }
        val gapEnd = h.rows.zipWithNext().indexOfFirst { (a, b) -> b.first.elapsedMs - a.first.elapsedMs > 30_000 } + 1
        assertTrue(gapEnd > 0)
        assertEquals(1, rows[gapEnd - 1].u, "before the tunnel: exit 24")
        assertEquals(3, rows[gapEnd].u, "the first fix after the tunnel")
        assertEquals("Turn right", rows[gapEnd].guidance!!.banner!!.view.primary)
        assertEquals(listOf("Downs Dr"), rows[gapEnd].guidance!!.banner!!.view.roads)
        assertTrue(rows.drop(gapEnd).all { (it.u ?: 4) >= 3 }, "no wrong banner later")
        assertTrue(rows.none { it.phase == Phase.REROUTING })
    }

    @Test
    fun `jitter - no REROUTING, suspected for the jumped fix only, the stale fix ignored, advances near the clean run's`() = sessionTest { h ->
        h.simulate(Simulation(scriptName = "jitter"))
        assertEquals(Phase.ARRIVED, h.phase())
        val rows = h.rows.map { it.second }
        assertTrue(rows.none { it.phase == Phase.REROUTING })
        assertEquals(1, rows.count { it.guidance?.offRouteSuspected == true }, "the 70 m jump only")
        // the stale fix (15 s old) reached the session and was dropped (G1): the vehicle never went back
        val now = h.rows.map { it.first.elapsedMs }
        assertTrue(now.zipWithNext().any { (a, b) -> b < a }, "the stale fix was delivered")
        val along = rows.mapNotNull { r -> r.guidance?.vehicle?.takeIf { it.snapped }?.let { r.vehicleAlong() } }
        assertTrue(along.zipWithNext().all { (a, b) -> b >= a - 15.0 })
        // advances near the clean run's (the golden trace)
        val clean = Data.json(GoldenTrace.FILE)["rows"]!!.jsonArray.map { it.jsonObject }
        for (k in 2..4) {
            val c = clean.first { it["u"]?.jsonPrimitive?.content == k.toString() }["fix"]!!.jsonObject
            val cleanAlong = EXIT24.project(LatLon(c["lat"]!!.jsonPrimitive.double, c["lon"]!!.jsonPrimitive.double)).alongM
            val j = h.rows.first { it.second.u == k }.first
            val jAlong = EXIT24.project(LatLon(j.lat, j.lon)).alongM
            println("jitter: U=$k from ${"%.1f".format(jAlong)} m, clean run ${"%.1f".format(cleanAlong)} m")
            assertTrue(kotlin.math.abs(jAlong - cleanAlong) <= 15.0, "U=$k at $jAlong m, clean run $cleanAlong m")
        }
    }

    @Test
    fun `drift - 5 m to the right for 10 s never sets offRouteSuspected, the arrow stays on the line`() = sessionTest { h ->
        h.simulate(Simulation(scriptName = "drift"))
        assertEquals(Phase.ARRIVED, h.phase())
        val rows = h.rows.map { it.second }
        assertTrue(rows.none { it.guidance?.offRouteSuspected == true })
        assertTrue(rows.filter { it.phase == Phase.GUIDING }.all { it.guidance!!.vehicle!!.snapped })
        val drifted = h.rows.filter { (f, _) -> EXIT24.project(LatLon(f.lat, f.lon)).distanceM in 4.0..6.0 }
        assertTrue(drifted.size >= 9, "the script drifted ${drifted.size} fixes")
    }

    /**
     * docs/architecture.md 5.1 N: a 3-stop route shows OSRM step k = Valhalla maneuver k (flattened).
     * Valhalla's OSRM answer has one step per maneuver in every leg (depart ... arrive), so the
     * flattened indexes agree and FollowerProgress.stepIndex needs no mapping.
     */
    /**
     * Guides [stops] after the exit 24 destination's plan and checks that the engine's OSRM answer
     * (Ferrostar's input) has one step per Valhalla maneuver, in the same order over all legs: the
     * same kind (depart, arrive, a turn), the same length within 1 m and the same place within 1 m.
     */
    private fun Harness.checkOsrmStepsEqualManeuvers(stops: List<io.github.spysyweeb.beamline.api.model.Place>, legs: Int) {
        planExit24()
        for (s in stops) send(NavIntent.AddStop(s))
        advance(1_000)
        assertEquals(Phase.CHOOSING, phase(), "${state.problem}")
        send(NavIntent.Start())
        assertEquals(Phase.GUIDING, phase(), "${state.problem}")
        val osrmReq = engine.routeRequests.last { it["format"]?.jsonPrimitive?.content == "osrm" }
        assertEquals(legs + 1, osrmReq["locations"]!!.jsonArray.size)
        val osrm = Json.parseToJsonElement(RecordedEngine.standard().route(osrmReq.toString())).jsonObject
        // the plan request the OSRM request was made from (not merely the last one: the arrival side rule may ask once more)
        val plan = engine.routeRequests.last { "format" !in it && it["locations"] == osrmReq["locations"] && it["costing_options"] == osrmReq["costing_options"] }
        val route = ValhallaResponses.parseRoute(RecordedEngine.standard().route(plan.toString())).main
        val steps = osrm["routes"]!!.jsonArray[0].jsonObject["legs"]!!.jsonArray.flatMap { it.jsonObject["steps"]!!.jsonArray.map { s -> s.jsonObject } }
        val mans = route.maneuvers
        val geo = RouteGeometry(route)
        assertEquals(legs, route.legs.size)
        assertEquals(legs, osrm["routes"]!!.jsonArray[0].jsonObject["legs"]!!.jsonArray.size)
        assertEquals(mans.size, steps.size, "one OSRM step per maneuver")
        steps.zip(mans).forEachIndexed { k, (s, mv) ->
            val type = s["maneuver"]!!.jsonObject["type"]!!.jsonPrimitive.content
            when {
                mv.type.isStart -> assertEquals("depart", type, "step $k")
                mv.type.isDestination -> assertEquals("arrive", type, "step $k")
                else -> assertFalse(type == "depart" || type == "arrive", "step $k: $type for ${mv.type}")
            }
            assertEquals(mv.lengthM, s["distance"]!!.jsonPrimitive.double, 1.0, "step $k distance")
            val loc = s["maneuver"]!!.jsonObject["location"]!!.jsonArray      // [lon, lat]
            val at = geo.shape[geo.maneuverShapeIndex[k]]
            val apart = io.github.spysyweeb.beamline.routing.model.Geo.distanceM(at, LatLon(loc[1].jsonPrimitive.double, loc[0].jsonPrimitive.double))
            assertTrue(apart <= 1.0, "step $k is $apart m from maneuver $k")
        }
        assertEquals(legs, mans.count { it.type.isDestination }, "one arrival per leg")
        assertEquals(mans.indices.filter { mans[it].type.isDestination }.map { geo.maneuverLeg[it] }, (0 until legs).toList())
    }

    /**
     * docs/architecture.md 5.1 N: a 3-stop route shows OSRM step k = Valhalla maneuver k (flattened).
     * Valhalla's OSRM answer has one step per maneuver in every leg (depart ... arrive), so the
     * flattened indexes agree and FollowerProgress.stepIndex needs no mapping. Checked on the
     * exit 24 trip with one stop on Peach St (origin + 2 stops, 2 legs) and with a stop on I-90 as
     * well (origin + 3 stops, 3 legs).
     */
    @Test
    fun `a 3-stop route - OSRM step k is Valhalla maneuver k, legs flattened`() = sessionTest { h ->
        h.checkOsrmStepsEqualManeuvers(listOf(PEACH_STOP), legs = 2)
    }

    @Test
    fun `a route with three stops and three legs - OSRM step k is Valhalla maneuver k`() = sessionTest { h ->
        val i90 = EXIT24.pointAt(2000.0)
        val onI90 = io.github.spysyweeb.beamline.api.model.Place("On I-90",
            io.github.spysyweeb.beamline.api.model.GeoPoint(RouteSimulator.round6(i90.lat), RouteSimulator.round6(i90.lon)),
            io.github.spysyweeb.beamline.api.model.PlaceSource.MAP_PIN)
        // AddStop with no index goes before the destination: the order is I-90, Peach St, Downs Dr
        h.checkOsrmStepsEqualManeuvers(listOf(onI90, PEACH_STOP), legs = 3)
        assertEquals(listOf("On I-90", "Peach St stop", "Downs Drive"), h.state.stops.map { it.place.name })
    }
}
