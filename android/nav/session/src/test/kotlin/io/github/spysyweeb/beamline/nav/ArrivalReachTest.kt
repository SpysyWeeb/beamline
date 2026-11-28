package io.github.spysyweeb.beamline.nav

import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.nav.NavIntent
import io.github.spysyweeb.beamline.api.nav.Origin
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.ReplaceReason
import io.github.spysyweeb.beamline.api.nav.Simulation
import io.github.spysyweeb.beamline.api.nav.TripEvent
import io.github.spysyweeb.beamline.routing.engine.EngineException
import io.github.spysyweeb.beamline.routing.model.Geo
import io.github.spysyweeb.beamline.routing.model.LatLon
import io.github.spysyweeb.beamline.routing.valhalla.ValhallaResponses
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Rule A4 (docs/next-round.md item 56; the owner, 2026-11-28: "Simply getting close enough to the
 * location should count as arriving, but not so much focused on the location's pin, but the road
 * that the route ends at"): a leg's end is reached when a usable fix (accuracy up to 50 m) is
 * within arrival.reach_m (50 m), in a straight line, of the point the leg's route ends at; on the
 * route or off it, in GUIDING or REROUTING. On the exit 24 trip (made-up stops on public roads).
 */
class ArrivalReachTest {

    /** The point the exit 24 route ends at (Downs Dr, the route's end, not the place's pin). */
    private val end: LatLon = EXIT24.pointAt(m(4))

    private fun toEnd(p: LatLon) = Geo.distanceM(p, end)

    /** I-90, the exit, Peach St and Downs Dr up to [toM] metres along, on the route. */
    private fun Harness.toDownsDr(toM: Double) {
        drive(4100.0, m(1) + 15.0, stepM = 20.0)
        drive(m(2) - 30.0, m(2) + 15.0, stepM = 15.0)
        drive(m(3) - 30.0, m(3) + 15.0, stepM = 15.0)
        drive(m(3) + 40.0, toM, stepM = 25.0)
        assertEquals(4, state.u)
        assertEquals(Phase.GUIDING, phase())
    }

    /** A re-route fails on purpose (no route request is asked of the recordings): REROUTING stays visible. */
    private fun Harness.failReroutes() {
        engine.failWhen = { req -> if ("format" !in req) EngineException(442, "No path could be found for input") else null }
    }

    private fun Harness.assertArrived() {
        assertEquals(Phase.ARRIVED, phase(), "${state.problem}")
        assertEquals(listOf(TripEvent.Arrived("Downs Drive")), eventsOf(TripEvent.Arrived::class.java))
        assertNull(store.load(), "trip.json deleted (A3)")
        assertNull(state.problem)
        assertFalse(state.replanning)
    }

    @Test
    fun `A4 pulling into a lot beside the route's end, off the route, arrives - also after the re-route it started`() = sessionTest { h ->
        h.guideExit24()
        h.failReroutes()
        h.toDownsDr(m(4) - 100.0)
        // off the road to the right, into a lot beside the end: off the route from the second fix (lateral above 35 m)
        val path = listOf(90.0 to 20.0, 80.0 to 40.0, 70.0 to 55.0, 55.0 to 60.0, 40.0 to 60.0, 30.0 to 50.0)
        for ((before, side) in path) {
            h.at(m(4) - before, sideM = side, speed = 4f)
            assertTrue(toEnd(EXIT24.offset(m(4) - before, side)) > 50.0)
        }
        assertEquals(Phase.REROUTING, h.phase(), "five off fixes: the re-route started (O3)")
        val lot = EXIT24.offset(m(4) - 25.0, 31.0)
        assertTrue(toEnd(lot) in 30.0..45.0, "the parking place, ${toEnd(lot)} m from the end")
        h.at(m(4) - 25.0, sideM = 31.0, speed = 0.5f)
        h.assertArrived()
        assertFalse(h.state.guidance!!.offRouteSuspected, "review 6: the off-route count ends with the arrival")
    }

    @Test
    fun `A4 parking 45 m short on the street arrives`() = sessionTest { h ->
        h.guideExit24()
        h.toDownsDr(m(4) - 75.0)
        h.at(m(4) - 60.0, speed = 5f)
        assertEquals(Phase.GUIDING, h.phase())
        assertTrue(toEnd(EXIT24.pointAt(m(4) - 45.0)) in 40.0..50.0)
        h.at(m(4) - 45.0, speed = 0f)
        h.assertArrived()
    }

    @Test
    fun `A4 standing 60 m short is still guiding, and the drive on arrives`() = sessionTest { h ->
        h.guideExit24()
        h.toDownsDr(m(4) - 75.0)
        assertTrue(toEnd(EXIT24.pointAt(m(4) - 60.0)) > 55.0)
        repeat(60) { h.at(m(4) - 60.0, speed = 0f) }          // a minute
        assertEquals(Phase.GUIDING, h.phase())
        assertTrue(h.eventsOf(TripEvent.Arrived::class.java).isEmpty())
        assertEquals(4, h.state.u)
        h.at(m(4) - 45.0, speed = 3f)
        h.assertArrived()
    }

    @Test
    fun `A4 a red light 40 m before the end arrives (the owner's rule, accepted)`() = sessionTest { h ->
        h.guideExit24()
        h.toDownsDr(m(4) - 75.0)
        h.at(m(4) - 60.0, speed = 5f)
        h.at(m(4) - 40.0, speed = 0f)
        h.assertArrived()
    }

    @Test
    fun `A4 fixes of 45 m accuracy at the end arrive, an unusable one does not`() = sessionTest { h ->
        h.guideExit24()
        h.toDownsDr(m(4) - 75.0)
        h.at(m(4) - 8.0, accuracy = 55f, speed = 1f)          // above progress_max_accuracy_m: dropped (G1)
        assertEquals(Phase.GUIDING, h.phase())
        h.at(m(4) - 8.0, accuracy = 45f, speed = 1f)          // usable, not deciding: the fix before it (75 m back) was not within reach
        assertEquals(Phase.GUIDING, h.phase(), "review 4: one poor fix alone does not arrive")
        h.at(m(4) - 6.0, accuracy = 45f, speed = 1f)          // a second one within reach
        h.assertArrived()
    }

    @Test
    fun `A4 review 4 - one poor fix within reach while the car is 120 m back does not arrive`() = sessionTest { h ->
        h.guideExit24()
        h.toDownsDr(m(4) - 120.0)
        h.at(m(4) - 10.0, accuracy = 40f, speed = 10f)        // a multipath jump, 110 m ahead of the car
        assertEquals(Phase.GUIDING, h.phase())
        h.at(m(4) - 100.0, speed = 10f)
        assertEquals(Phase.GUIDING, h.phase())
        assertTrue(h.store.load() != null, "trip.json kept")
        h.at(m(4) - 35.0, accuracy = 40f, speed = 5f)         // poor, and the fix before it was 100 m back
        assertEquals(Phase.GUIDING, h.phase())
        h.at(m(4) - 30.0, accuracy = 40f, speed = 3f)         // two poor fixes in a row within reach
        h.assertArrived()
    }

    @Test
    fun `A4 a re-route started near the end, then within 50 m, arrives`() = sessionTest { h ->
        h.guideExit24()
        h.failReroutes()
        h.toDownsDr(m(4) - 175.0)
        for (before in listOf(150.0, 130.0, 110.0, 90.0, 70.0)) {
            assertTrue(toEnd(EXIT24.offset(m(4) - before, 70.0)) > 50.0)
            h.at(m(4) - before, sideM = 70.0, speed = 6f)
        }
        assertEquals(Phase.REROUTING, h.phase())
        assertTrue(h.eventsOf(TripEvent.RerouteFailed::class.java).isNotEmpty())
        h.at(m(4) - 60.0, sideM = 60.0, speed = 4f)
        assertEquals(Phase.REROUTING, h.phase(), "85 m away")
        assertTrue(toEnd(EXIT24.offset(m(4) - 35.0, 30.0)) < 50.0)
        h.at(m(4) - 35.0, sideM = 30.0, speed = 2f)
        h.assertArrived()
        val asked = h.engine.routeRequests.size
        repeat(20) { h.at(m(4) - 35.0, sideM = 30.0, speed = 0f) }
        assertEquals(asked, h.engine.routeRequests.size, "no re-route after the arrival (O7's retries end)")
        assertEquals(Phase.ARRIVED, h.phase())
    }

    /** The exit 24 trip with a stop on Peach St (two legs), planned and guided. */
    private fun Harness.guideWithPeachStop() {
        planExit24()
        send(NavIntent.AddStop(PEACH_STOP))
        advance(1_000)
        assertAllRecorded()
        assertEquals(Phase.CHOOSING, phase(), "${state.problem}")
        send(NavIntent.Start())
        assertEquals(Phase.GUIDING, phase())
        assertEquals(2, state.guidance!!.route.legEnds.size)
    }

    private val stopAlong get() = m(2) + 150.0
    private fun toStop(p: LatLon) = Geo.distanceM(p, EXIT24.pointAt(stopAlong))

    @Test
    fun `A4 an intermediate stop passed within 50 m, off the route, is reached and guidance goes on to the next leg`() = sessionTest { h ->
        h.guideWithPeachStop()
        h.drive(4100.0, m(1) + 15.0, stepM = 20.0)
        h.drive(m(2) - 30.0, m(2) + 15.0, stepM = 15.0)
        h.drive(m(2) + 40.0, m(2) + 90.0, stepM = 25.0)
        assertTrue(h.eventsOf(TripEvent.StopReached::class.java).isEmpty(), "60 m before the stop")
        // by the stop 40 m to the side of the route (a lot beside the road), without stopping
        assertTrue(toStop(EXIT24.offset(m(2) + 115.0, 40.0)) > 50.0)
        h.at(m(2) + 115.0, sideM = 40.0, speed = 8f)
        assertTrue(h.eventsOf(TripEvent.StopReached::class.java).isEmpty())
        assertTrue(toStop(EXIT24.offset(m(2) + 140.0, 40.0)) < 50.0)
        h.at(m(2) + 140.0, sideM = 40.0, speed = 8f)
        assertEquals(listOf(TripEvent.StopReached(0, "Peach St stop")), h.eventsOf(TripEvent.StopReached::class.java))
        assertTrue(h.state.stops[0].reached)
        assertFalse(h.state.stops[1].reached)
        assertTrue(h.store.load()!!.stops[0].reached, "trip.json saved (A2)")
        assertEquals(Phase.GUIDING, h.phase())
        assertEquals(1, h.state.guidance!!.legIndex)
        assertEquals(1, h.state.guidance!!.nextStopIndex)
        assertEquals("Turn right", h.state.guidance!!.banner!!.view.primary, "the next leg's turn, never the reached stop's arrival")
        h.at(m(2) + 175.0, speed = 8f)                        // back on the route
        assertEquals(Phase.GUIDING, h.phase())
        assertEquals("Turn right", h.state.guidance!!.banner!!.view.primary)
        assertEquals(1, h.eventsOf(TripEvent.StopReached::class.java).size, "reached once")
    }

    @Test
    fun `A4 an intermediate stop reached in REROUTING asks the re-route again without it`() = sessionTest { h ->
        h.guideWithPeachStop()
        h.failReroutes()
        h.drive(4100.0, m(1) + 15.0, stepM = 20.0)
        h.drive(m(2) - 30.0, m(2) + 15.0, stepM = 15.0)
        for (along in listOf(40.0, 55.0, 70.0, 85.0, 100.0)) {
            assertTrue(toStop(EXIT24.offset(m(2) + along, 80.0)) > 50.0)
            h.at(m(2) + along, sideM = 80.0, speed = 6f)
        }
        assertEquals(Phase.REROUTING, h.phase())
        val first = h.engine.routeRequests.last { "format" !in it }
        assertEquals(3, first["locations"]!!.jsonArray.size, "the car, Peach St, Downs Dr")
        h.at(stopAlong, sideM = 40.0, speed = 3f)
        assertEquals(listOf(TripEvent.StopReached(0, "Peach St stop")), h.eventsOf(TripEvent.StopReached::class.java))
        val again = h.engine.routeRequests.last { "format" !in it }
        assertEquals(2, again["locations"]!!.jsonArray.size, "asked again from the car to Downs Dr only")
        assertEquals(Phase.REROUTING, h.phase())
    }

    @Test
    fun `A4 a simulated drive arrives where A1 says, as in the golden trace`() = sessionTest { h ->
        h.planExit24()
        h.send(NavIntent.Start(Simulation(scriptName = "default")))
        var s = 0
        while (h.phase() != Phase.ARRIVED && s++ < 900) h.advance(1_000)
        assertEquals(Phase.ARRIVED, h.phase())
        val at = h.rows.first { it.second.phase == Phase.ARRIVED }.second.guidance!!.remainingM
        assertTrue(at <= 30.0, "arrived $at m before the end: A1's within_m, not the straight-line reach")
    }

    // ------------------------------------------------------------------ the review of A4 (item 56, 2026-11-28)

    private val onI90: Place by lazy {
        val p = EXIT24.pointAt(2000.0)
        Place("On I-90", GeoPoint(RouteSimulator.round6(p.lat), RouteSimulator.round6(p.lon)), PlaceSource.MAP_PIN)
    }

    /** Plans and guides [stops] after the exit 24 destination's plan; the guided route's line (from its recorded plan). */
    private fun Harness.guideWith(vararg stops: Place): RouteGeometry {
        planExit24()
        for (s in stops) send(NavIntent.AddStop(s))
        advance(1_000)
        assertAllRecorded()
        assertEquals(Phase.CHOOSING, phase(), "${state.problem}")
        send(NavIntent.Start())
        assertEquals(Phase.GUIDING, phase(), "${state.problem}")
        val osrmReq = engine.routeRequests.last { it["format"]?.jsonPrimitive?.content == "osrm" }
        val plan = engine.routeRequests.last { "format" !in it && it["locations"] == osrmReq["locations"] && it["costing_options"] == osrmReq["costing_options"] }
        return RouteGeometry(ValhallaResponses.parseRoute(RecordedEngine.standard().route(plan.toString())).main)
    }

    @Test
    fun `A4 review 1 - the C6 trip's other carriageway passes the I-90 stop, which is reached only at its end`() = sessionTest { h ->
        val geo = h.guideWith(PEACH_STOP, onI90)
        assertEquals(listOf("Peach St stop", "On I-90", "Downs Drive"), h.state.stops.map { it.place.name })
        h.failReroutes()
        val end1 = geo.legEndAlong(1)
        val endPoint = geo.pointAt(end1)
        // where leg 1 passes its own end before reaching it (the other carriageway of I-90)
        val pass = generateSequence(geo.legEndAlong(0)) { it + 5.0 }.takeWhile { it < end1 - 200.0 }
            .map { it to Geo.distanceM(geo.pointAt(it), endPoint) }.minByOrNull { it.second }!!
        println("C6 leg 1 passes ${"%.1f".format(pass.second)} m from its end with ${"%.0f".format(end1 - pass.first)} m still to drive")
        assertTrue(pass.second < 50.0, "the recording passes within reach of the stop's end")
        val reachedAt = HashMap<Int, Double>()
        val phases = HashSet<Phase>()
        var a = 0.0
        while (a <= geo.lengthM && h.phase() != Phase.ARRIVED) {
            h.at(a, geo = geo, speed = 25f)
            phases += h.phase()
            h.eventsOf(TripEvent.StopReached::class.java).forEach { e -> reachedAt.putIfAbsent((e as TripEvent.StopReached).stopIndex, a) }
            a += 15.0
        }
        assertEquals(setOf(0, 1), reachedAt.keys, "both stops reached")
        val early = end1 - reachedAt.getValue(1)
        assertTrue(early in -45.0..60.0, "the I-90 stop reached $early m before its end along the route, not on the other carriageway")
        assertEquals(Phase.ARRIVED, h.phase())
        assertFalse(Phase.REROUTING in phases, "never off the route")
    }

    @Test
    fun `A4 review 1 - off the route, a fast pass within 50 m of the end does not arrive, a slow one does`() = sessionTest { h ->
        h.guideExit24()
        h.failReroutes()
        h.toDownsDr(m(4) - 200.0)
        for (before in listOf(180.0, 160.0, 140.0, 120.0, 100.0)) h.at(m(4) - before, sideM = 90.0, speed = 20f)
        assertEquals(Phase.REROUTING, h.phase())
        // another road 35 m beside the end, at 20 m/s
        for (before in listOf(40.0, 20.0, 0.0)) {
            assertTrue(toEnd(EXIT24.offset(m(4) - before, 35.0)) < 50.0)
            h.at(m(4) - before, sideM = 35.0, speed = 20f)
            assertEquals(Phase.REROUTING, h.phase(), "$before m before the end at 20 m/s")
        }
        h.at(m(4) - 10.0, sideM = 35.0, speed = 4f)            // back, slowly, into a lot there
        h.assertArrived()
    }

    @Test
    fun `A4 review 2 - a stop reached 45 m short does not re-route the car still standing short of it`() = sessionTest { h ->
        h.guideWithPeachStop()
        h.failReroutes()
        h.drive(4100.0, m(1) + 15.0, stepM = 20.0)
        h.drive(m(2) - 30.0, m(2) + 15.0, stepM = 15.0)
        h.drive(m(2) + 60.0, m(2) + 85.0, stepM = 25.0)
        h.at(m(2) + 105.0, speed = 3f)
        assertEquals(listOf(TripEvent.StopReached(0, "Peach St stop")), h.eventsOf(TripEvent.StopReached::class.java), "45 m short")
        repeat(5) { h.at(m(2) + 105.0, speed = 0f) }
        assertEquals(Phase.GUIDING, h.phase(), "standing where the stop was reached is not off the route")
        assertFalse(h.state.guidance!!.offRouteSuspected)
        h.drive(m(2) + 130.0, m(2) + 230.0, stepM = 25.0)    // on along Peach St
        assertEquals(Phase.GUIDING, h.phase())
        assertEquals("Turn right", h.state.guidance!!.banner!!.view.primary)
    }

    /** A stop on Downs Dr, 150 m after the right turn: its leg has four maneuvers before its arrival. */
    private val downsStop: Place by lazy {
        val p = EXIT24.pointAt(m(3) + 150.0)
        Place("Downs Dr stop", GeoPoint(RouteSimulator.round6(p.lat), RouteSimulator.round6(p.lon)), PlaceSource.MAP_PIN)
    }

    @Test
    fun `A4 review 5 - a stop reached with four maneuvers still ahead moves U to the next leg, never to its departure`() = sessionTest { h ->
        val geo = h.guideWith(downsStop)
        h.at(300.0, geo = geo)                                 // on I-90: the follower at the start
        val arrival0 = (0 until geo.maneuverCount).first { geo.maneuverLeg[it] == 0 && geo.route.maneuvers[it].type.isDestination }
        assertTrue(arrival0 >= 4, "the stop's arrival is maneuver $arrival0")
        // after a long gap the car is beside the stop, off the route and slow (a lot by the road)
        h.at(geo.legEndAlong(0) - 10.0, sideM = 40.0, speed = 3f, geo = geo, afterMs = 120_000)
        assertEquals(listOf(TripEvent.StopReached(0, "Downs Dr stop")), h.eventsOf(TripEvent.StopReached::class.java))
        assertEquals(1, h.state.guidance!!.legIndex)
        val u = h.state.u!!
        assertTrue(u > arrival0 + 1, "U is the next leg's maneuver $u, past the stop's departure ${arrival0 + 1}")
        assertFalse(geo.route.maneuvers[u].type.isStart)
    }

    @Test
    fun `A4 review 6 - a stop reached in REROUTING drops the held answer and installs the route without it`() = sessionTest { h ->
        h.guideWithPeachStop()
        h.drive(4100.0, m(1) + 15.0, stepM = 20.0)
        h.drive(m(2) - 30.0, m(2) + 15.0, stepM = 15.0)
        h.routingGate.gated = true
        for (along in listOf(40.0, 55.0, 70.0, 85.0, 100.0)) h.at(m(2) + along, sideM = 80.0, speed = 6f)
        assertEquals(Phase.REROUTING, h.phase())
        assertTrue(h.routingGate.waiting > 0, "the re-route for both stops waits")
        val asked = h.engine.routeRequests.size
        h.at(stopAlong, sideM = 40.0, speed = 3f)
        assertEquals(listOf(TripEvent.StopReached(0, "Peach St stop")), h.eventsOf(TripEvent.StopReached::class.java))
        h.routingGate.release()
        h.run()
        val after = h.engine.routeRequests.drop(asked).filter { "format" !in it }
        assertEquals(listOf(2), after.map { it["locations"]!!.jsonArray.size }, "only the request without the reached stop ran")
        assertEquals(Phase.GUIDING, h.phase(), "${h.state.problem}")
        assertEquals(1, h.state.guidance!!.route.legEnds.size, "the new route goes to Downs Dr only")
        assertEquals(listOf(ReplaceReason.REROUTE), h.eventsOf(TripEvent.RouteReplaced::class.java).map { (it as TripEvent.RouteReplaced).reason })
    }

    @Test
    fun `A4 review 7 - a skipped stop - off the route and slow at the destination's end, the trip ends ARRIVED`() = sessionTest { h ->
        h.guideWithPeachStop()
        h.failReroutes()
        h.drive(4100.0, m(1) + 15.0, stepM = 20.0)
        for (along in listOf(40.0, 70.0, 100.0, 130.0, 160.0)) h.at(m(1) + along, sideM = 300.0, speed = 15f)
        assertEquals(Phase.REROUTING, h.phase())
        h.at(m(4) - 30.0, sideM = 50.0, speed = 6f, afterMs = 60_000)
        assertTrue(toEnd(EXIT24.offset(m(4) - 30.0, 50.0)) > 50.0)
        h.at(m(4) - 20.0, sideM = 40.0, speed = 2f)            // in a lot 40 m beside the road, off the route (lateral above 35 m)
        assertEquals(Phase.REROUTING, h.phase(), "slow, not stopped: a skipped stop needs the car stopped there")
        h.at(m(4) - 20.0, sideM = 40.0, speed = 0f)
        assertEquals(Phase.REROUTING, h.phase(), "below 0.5 m/s for less than a second")
        h.at(m(4) - 20.0, sideM = 40.0, speed = 0f)            // STOPPED (M2)
        assertEquals(Phase.ARRIVED, h.phase(), "${h.state.problem}")
        assertFalse(h.state.guidance!!.offRouteSuspected, "review 6: the off-route count ends with the arrival")
        assertEquals(listOf(TripEvent.Arrived("Downs Drive")), h.eventsOf(TripEvent.Arrived::class.java))
        assertEquals(listOf(false, true), h.state.stops.map { it.reached }, "the skipped stop stays unreached")
        assertNull(h.store.load())
    }

    // ------------------------------------------------------------------ the second review of A4 (2026-11-28)

    /** Plans [destination] with [stops] before it from the exit 24 origin and guides it; the guided route's line. */
    private fun Harness.guideTrip(destination: Place, vararg stops: Place): RouteGeometry {
        send(NavIntent.PlanTo(destination))
        send(NavIntent.SetOrigin(Origin.FromPlace(Data.EXIT24_ORIGIN, 57.0)))
        for (s in stops) send(NavIntent.AddStop(s))
        advance(1_000)
        assertAllRecorded()
        assertEquals(Phase.CHOOSING, phase(), "${state.problem}")
        send(NavIntent.Start())
        assertEquals(Phase.GUIDING, phase(), "${state.problem}")
        val osrmReq = engine.routeRequests.last { it["format"]?.jsonPrimitive?.content == "osrm" }
        val plan = engine.routeRequests.last { "format" !in it && it["locations"] == osrmReq["locations"] && it["costing_options"] == osrmReq["costing_options"] }
        return RouteGeometry(ValhallaResponses.parseRoute(RecordedEngine.standard().route(plan.toString())).main)
    }

    /** A round trip's end: back where the exit 24 trip starts (a made-up point on a public road). */
    private val tripStart = Place("Trip start", Data.EXIT24_ORIGIN.point, PlaceSource.MAP_PIN)

    @Test
    fun `A4 second review 1 - a round trip standing off the route at its start does not end as a skipped stop`() = sessionTest { h ->
        val geo = h.guideTrip(tripStart, PEACH_STOP)
        assertEquals(listOf("Peach St stop", "Trip start"), h.state.stops.map { it.place.name })
        h.failReroutes()
        val finalEnd = geo.pointAt(geo.lengthM)
        val driveway = geo.offset(15.0, 40.0)
        assertTrue(Geo.distanceM(driveway, finalEnd) < 50.0, "the round trip ends ${Geo.distanceM(driveway, finalEnd)} m from the driveway")
        repeat(8) { h.at(15.0, sideM = 40.0, speed = 0f, geo = geo) }   // in the driveway at Start: off the route, stopped
        assertTrue(h.phase() == Phase.GUIDING || h.phase() == Phase.REROUTING, "${h.phase()}")
        assertTrue(h.eventsOf(TripEvent.Arrived::class.java).isEmpty(), "not ended at its start")
        assertEquals(listOf(false, false), h.state.stops.map { it.reached })
        assertTrue(h.store.load() != null, "trip.json kept")
    }

    @Test
    fun `A4 second review 1 - a slow pass off the route by the destination keeps the stop beyond it`() = sessionTest { h ->
        // Downs Dr first, then back to the Peach St stop: the first leg passes the destination on Peach St
        val geo = h.guideTrip(PEACH_STOP, Data.DOWNS_DRIVE)
        assertEquals(listOf("Downs Drive", "Peach St stop"), h.state.stops.map { it.place.name })
        val finalEnd = geo.pointAt(geo.lengthM)
        val pass = geo.project(LatLon(PEACH_STOP.point.lat, PEACH_STOP.point.lon), 0.0, geo.legEndAlong(0)).alongM
        var a = 0.0
        while (a <= pass - 200.0) {
            h.at(a, geo = geo)
            a += 15.0
        }
        h.failReroutes()
        for (back in listOf(190.0, 170.0, 150.0, 130.0, 110.0)) {
            assertTrue(Geo.distanceM(geo.offset(pass - back, 80.0), finalEnd) > 50.0)
            h.at(pass - back, sideM = 80.0, speed = 8f, geo = geo)
        }
        assertEquals(Phase.REROUTING, h.phase())
        for (d in listOf(-20.0, 0.0, 20.0)) {
            assertTrue(Geo.distanceM(geo.offset(pass + d, 30.0), finalEnd) < 50.0)
            h.at(pass + d, sideM = 30.0, speed = 5f, geo = geo)
            assertEquals(Phase.REROUTING, h.phase(), "driving by slowly, $d m")
        }
        assertTrue(h.eventsOf(TripEvent.Arrived::class.java).isEmpty())
        assertEquals(listOf(false, false), h.state.stops.map { it.reached }, "Downs Drive still to come")
    }

    @Test
    fun `A4 second review 2 - past the stop reached short, a fix beside it later counts as off the route again`() = sessionTest { h ->
        h.guideWithPeachStop()
        h.failReroutes()
        h.drive(4100.0, m(1) + 15.0, stepM = 20.0)
        h.drive(m(2) - 30.0, m(2) + 15.0, stepM = 15.0)
        h.drive(m(2) + 60.0, m(2) + 85.0, stepM = 25.0)
        h.at(m(2) + 105.0, speed = 3f)
        assertEquals(1, h.eventsOf(TripEvent.StopReached::class.java).size, "reached 45 m short")
        h.drive(m(2) + 130.0, m(2) + 230.0, stepM = 25.0)    // on along Peach St, 80 m past the stop
        assertEquals(Phase.GUIDING, h.phase())
        // back by the stop, 40 m beside the route: off the route there now
        for (along in listOf(170.0, 160.0, 150.0, 140.0, 130.0)) h.at(m(2) + along, sideM = 40.0, speed = 4f)
        assertEquals(Phase.REROUTING, h.phase(), "the reached stop's spot no longer hides an off-route car")
    }
}
