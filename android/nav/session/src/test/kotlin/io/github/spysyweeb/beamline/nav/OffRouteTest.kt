package io.github.spysyweeb.beamline.nav

import io.github.spysyweeb.beamline.api.data.InstalledSet
import io.github.spysyweeb.beamline.api.data.RoutingSet
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.nav.GpsState
import io.github.spysyweeb.beamline.api.nav.NavIntent
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.ProblemKind
import io.github.spysyweeb.beamline.api.nav.ReplaceReason
import io.github.spysyweeb.beamline.api.nav.TripEvent
import io.github.spysyweeb.beamline.routing.engine.EngineException
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.double
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Rules O1-O9 (off route and re-route), docs/architecture.md 3.3, on the exit 24 route. */
class OffRouteTest {

    /** Five fixes 80 m right of I-90, one a second: off route confirmed on the fifth (O3). */
    private fun Harness.leaveI90(from: Double = 625.0, side: Double = 80.0) {
        for (i in 0 until 5) at(from + 25.0 * i, sideM = side)
    }

    @Test
    fun `O1 the threshold grows with the fix's accuracy`() = sessionTest { h ->
        h.guideExit24()
        h.at(1000.0)
        h.at(1025.0, sideM = 40.0, accuracy = 30f)          // T = max(35, 1.5 x 30) = 45: on
        assertFalse(h.state.guidance!!.offRouteSuspected)
        h.at(1050.0, sideM = 40.0, accuracy = 5f)           // T = 35: off
        assertTrue(h.state.guidance!!.offRouteSuspected)
    }

    @Test
    fun `O2 wrong way counts as off, only deciding fixes count or reset, the arrow is drawn at the raw fix`() = sessionTest { h ->
        h.guideExit24()
        h.at(1000.0)
        val back = (EXIT24.bearingAt(1000.0)!! + 180.0) % 360.0
        h.at(1000.0, bearing = back, speed = 2f)            // below wrong_way_min_speed_mps: not off
        assertFalse(h.state.guidance!!.offRouteSuspected)
        h.at(990.0, bearing = back, speed = 5f)             // 180 degrees at 5 m/s: off
        assertTrue(h.state.guidance!!.offRouteSuspected)
        val v = h.state.guidance!!.vehicle!!
        assertFalse(v.snapped)
        assertEquals(back, v.bearingDeg!!, 0.01, "the GPS course")
        h.at(1000.0, accuracy = 40f)                        // usable, not deciding: neither resets ...
        assertTrue(h.state.guidance!!.offRouteSuspected)
        h.at(1500.0, sideM = 100.0, accuracy = 40f)         // ... nor counts
        h.at(1050.0)
        assertFalse(h.state.guidance!!.offRouteSuspected, "a deciding on fix resets")
    }

    @Test
    fun `O3 off route is confirmed on the fifth off fix at 1 per second`() = sessionTest { h ->
        h.guideExit24()
        h.drive(500.0, 600.0)
        for (i in 0 until 4) {
            h.at(625.0 + 25.0 * i, sideM = 80.0)
            assertEquals(Phase.GUIDING, h.phase(), "off fix ${i + 1}")
            assertTrue(h.state.guidance!!.offRouteSuspected)
        }
        h.engine.failWhen = { EngineException(442, "held") }      // keep REROUTING visible
        h.at(725.0, sideM = 80.0)
        assertEquals(Phase.REROUTING, h.phase())
    }

    @Test
    fun `O4 the request starts at the last deciding fix with radius and heading, to the stops not reached, not twice in 10 s`() = sessionTest { h ->
        h.guideExit24()
        h.drive(500.0, 600.0)
        h.leaveI90()
        val req = h.engine.routeRequests.last { "format" !in it }
        val origin = req["locations"]!!.jsonArray[0].jsonObject
        val last = h.rows.last().first
        assertEquals(RouteSimulator.round6(last.lat), origin["lat"]!!.jsonPrimitive.double, 1e-9, "6 decimals, as a phone's fix")
        assertEquals(RouteSimulator.round6(last.lon), origin["lon"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(60, origin["radius"]!!.jsonPrimitive.int)
        assertEquals(Math.round(last.bearingDeg!!.toDouble()).toInt() % 360, origin["heading"]!!.jsonPrimitive.int)
        assertEquals(2, req["locations"]!!.jsonArray.size, "the origin and Downs Dr")
        assertEquals(Data.DOWNS_DRIVE.point.lat, req["locations"]!!.jsonArray[1].jsonObject["lat"]!!.jsonPrimitive.double, 1e-9)
        assertEquals(Phase.GUIDING, h.phase())
        // off the new route at once: the next request waits for reroute.min_interval_s
        val asked = h.engine.routeRequests.size
        for (i in 0 until 5) h.at(900.0 + 25.0 * i, sideM = 250.0)
        assertEquals(Phase.REROUTING, h.phase())
        assertTrue(h.engine.routeRequests.size == asked || h.state.replanning, "no request before 10 s")
        assertTrue(h.engine.routeRequests.size == asked, "asked ${h.engine.routeRequests.size - asked} within 10 s")
        h.advance(6_000)
        h.run()
        assertTrue(h.engine.routeRequests.size > asked, "asked once 10 s had passed")
    }

    @Test
    fun `O4 a slow car's origin keeps the radius, without a heading`() = sessionTest { h ->
        h.guideExit24()
        h.drive(500.0, 600.0)
        for (i in 0 until 5) h.at(625.0 + 1.0 * i, sideM = 80.0, speed = 1.0f)   // below heading_min_speed_mps
        assertEquals(Phase.GUIDING, h.phase(), "re-routed")
        val origin = h.engine.routeRequests.last { "format" !in it }["locations"]!!.jsonArray[0].jsonObject
        assertEquals(60, origin["radius"]!!.jsonPrimitive.int, "radiusM = origin_radius_m always")
        assertNull(origin["heading"], "no heading below 2 m/s")
    }

    @Test
    fun `O4 a Reroute before the first fix is asked with the first usable fix - REROUTING never waits for nothing`() = sessionTest { h ->
        h.guideExit24()                                       // real GPS: no fix yet
        assertEquals(GpsState.WAITING, h.state.gps)
        val asked = h.engine.routeRequests.size
        h.send(NavIntent.Reroute)
        assertEquals(Phase.REROUTING, h.phase())
        assertEquals(asked, h.engine.routeRequests.size, "no position yet: nothing to ask from")
        h.advance(3_000)
        assertEquals(asked, h.engine.routeRequests.size)
        h.at(600.0)                                           // the first fix
        assertTrue(h.engine.routeRequests.size > asked, "asked with the first usable fix")
        val origin = h.engine.routeRequests[asked]["locations"]!!.jsonArray[0].jsonObject
        assertEquals(60, origin["radius"]!!.jsonPrimitive.int)
        assertEquals(Phase.GUIDING, h.phase(), "${h.state.problem}")
        val e = h.eventsOf(TripEvent.RouteReplaced::class.java).single() as TripEvent.RouteReplaced
        assertEquals(ReplaceReason.REROUTE, e.reason)
        assertFalse(h.state.replanning)
    }

    @Test
    fun `O5 back on the old route before the answer - the answer is dropped, GUIDING, no event`() = sessionTest { h ->
        h.guideExit24()
        h.drive(500.0, 600.0)
        val routeId = h.state.guidance!!.route.id
        h.routingGate.gated = true
        h.leaveI90()
        assertEquals(Phase.REROUTING, h.phase())
        assertTrue(h.state.replanning)
        h.drive(750.0, 800.0)                                // three on fixes
        assertEquals(Phase.GUIDING, h.phase())
        h.routingGate.release()
        h.run()
        assertEquals(Phase.GUIDING, h.phase())
        assertTrue(h.eventsOf(TripEvent.RouteReplaced::class.java).isEmpty(), "the answer was dropped")
        assertEquals(routeId, h.state.guidance!!.route.id, "still the old route")
    }

    @Test
    fun `O6 a good answer replaces the route - GUIDING and RouteReplaced(REROUTE)`() = sessionTest { h ->
        h.guideExit24()
        h.drive(500.0, 600.0)
        val before = h.state.guidance!!.route.id
        val revision = h.state.revision
        h.leaveI90()
        assertEquals(Phase.GUIDING, h.phase())
        val e = h.eventsOf(TripEvent.RouteReplaced::class.java).single() as TripEvent.RouteReplaced
        assertEquals(ReplaceReason.REROUTE, e.reason)
        assertEquals(h.state.guidance!!.route.id, e.routeId)
        assertTrue(e.routeId != before)
        assertTrue(h.state.revision > revision, "a new route is a new revision (TripState.revision)")
        assertFalse(h.state.replanning)
        assertEquals(0, h.state.guidance!!.rerouteAttempts)
    }

    @Test
    fun `O7 failed answers count, retry every 15 s up to 5, then only on Retry`() = sessionTest { h ->
        h.guideExit24()
        h.drive(500.0, 600.0)
        h.engine.failWhen = { req -> if ("format" !in req) EngineException(442, "No path could be found for input") else null }
        h.leaveI90()
        assertEquals(Phase.REROUTING, h.phase())
        assertEquals(1, h.state.guidance!!.rerouteAttempts)
        assertEquals(ProblemKind.NO_ROUTE, h.state.problem!!.kind)
        assertEquals(1, h.eventsOf(TripEvent.RerouteFailed::class.java).size)
        // keep the car off the route (fixes every second), let the retries run
        var t = 0
        while (t < 90) { h.at(750.0 + t, sideM = 80.0); t++ }
        assertEquals(5, h.state.guidance!!.rerouteAttempts)
        assertEquals(5, h.eventsOf(TripEvent.RerouteFailed::class.java).size, "max_attempts")
        h.engine.failWhen = null
        h.send(NavIntent.Retry)
        assertEquals(Phase.GUIDING, h.phase(), "Retry asked again: answered")
    }

    @Test
    fun `O8 outside the installed states - no engine call, no attempt, asked again on a covered fix`() = sessionTest { h ->
        h.guideExit24()
        h.drive(500.0, 600.0)
        // the car's tile is not in the set (as if the car had left the installed states)
        val all = Harness.paOh()
        val here = RoutingSet.level2TileId(GeoPoint(EXIT24.pointAt(700.0).lat, EXIT24.pointAt(700.0).lon))
        h.installed.value = InstalledSet(generation = 1, scanned = true, routing = all.copy(level2Tiles = all.level2Tiles - here))
        val asked = h.engine.routeRequests.size
        h.leaveI90()
        assertEquals(Phase.REROUTING, h.phase())
        assertEquals(ProblemKind.OUTSIDE_INSTALLED_MAPS, h.state.problem!!.kind)
        assertEquals("You are outside the installed maps.", h.state.problem!!.text)
        assertEquals(asked, h.engine.routeRequests.size, "no engine call")
        assertEquals(0, h.state.guidance!!.rerouteAttempts)
        h.installed.value = InstalledSet(generation = 1, scanned = true, routing = all)
        h.at(750.0, sideM = 80.0)
        assertEquals(Phase.GUIDING, h.phase(), "asked as soon as a deciding fix was covered")
    }

    @Test
    fun `O8 an engine answer 171 for the origin is treated as outside`() = sessionTest { h ->
        h.guideExit24()
        h.drive(500.0, 600.0)
        h.engine.failWhen = { req -> if ("format" !in req) EngineException(171, "No suitable edges near location") else null }
        h.leaveI90()
        assertEquals(ProblemKind.OUTSIDE_INSTALLED_MAPS, h.state.problem!!.kind)
        assertEquals(0, h.state.guidance!!.rerouteAttempts)
        assertTrue(h.eventsOf(TripEvent.RerouteFailed::class.java).isEmpty())
    }

    @Test
    fun `O8 after an engine 171 the car standing still is asked for again once per min_interval_s, not on every fix`() = sessionTest { h ->
        h.guideExit24()
        h.drive(500.0, 600.0)
        h.engine.failWhen = { req -> if ("format" !in req) EngineException(171, "No suitable edges near location") else null }
        h.leaveI90()
        assertEquals(ProblemKind.OUTSIDE_INSTALLED_MAPS, h.state.problem!!.kind)
        // one plan = one route request: the destination is a dropped pin (Data.DOWNS_DRIVE, MAP_PIN), not tied
        // (C1 routing fix), so there is no request without the tie after the 171; OSRM requests are not plans
        fun plans() = h.engine.routeRequests.count { r -> "format" !in r }
        val asked = plans()
        repeat(30) { h.at(725.0, sideM = 80.0, speed = 0f) }   // 30 s standing where the engine finds no road
        val more = plans() - asked
        println("O8 after 171: $more engine requests in 30 s standing still")   // the report quotes this (JUnit XML system-out)
        assertTrue(more in 2..4, "asked again $more times in 30 s; O4 allows one request per reroute.min_interval_s (10 s)")
        assertEquals(Phase.REROUTING, h.phase())
        assertEquals(ProblemKind.OUTSIDE_INSTALLED_MAPS, h.state.problem!!.kind)
        assertEquals(0, h.state.guidance!!.rerouteAttempts, "no attempt counted (O8)")
        assertTrue(h.eventsOf(TripEvent.RerouteFailed::class.java).isEmpty())
        // the engine finds a road again: the next request (at most 10 s later) is answered
        h.engine.failWhen = null
        repeat(11) { if (h.phase() == Phase.REROUTING) h.at(725.0, sideM = 80.0, speed = 0f) }
        assertEquals(Phase.GUIDING, h.phase(), "${h.state.problem}")
    }

    @Test
    fun `O9 a start far from the route re-routes at once from the car`() = sessionTest { h ->
        h.guideExit24()
        val far = EXIT24.offset(300.0, 500.0)
        h.advance(1_000)
        h.gps(far.lat, far.lon, 5f, 10f, EXIT24.bearingAt(300.0)!!.toFloat())
        assertEquals(1, h.eventsOf(TripEvent.RouteReplaced::class.java).size, "${h.state.problem}")
        assertEquals(Phase.GUIDING, h.phase())
        assertNull(h.state.problem)
    }
}
