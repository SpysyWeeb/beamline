package io.github.spysyweeb.beamline.nav

import io.github.spysyweeb.beamline.api.data.InstalledSet
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.nav.NavIntent
import io.github.spysyweeb.beamline.api.nav.Origin
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.ProblemKind
import io.github.spysyweeb.beamline.api.nav.ReplaceReason
import io.github.spysyweeb.beamline.api.nav.RouteOptions
import io.github.spysyweeb.beamline.api.nav.TripEvent
import io.github.spysyweeb.beamline.location.Fix
import io.github.spysyweeb.beamline.routing.engine.EngineException
import io.github.spysyweeb.beamline.routing.model.Route
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Rules A1-A3 (arrival) and C1-C7 (stops, changes, route type), docs/architecture.md 3.4, on the exit 24 trip. */
class ArrivalAndStopsTest {

    private fun Harness.toLastStretch() {
        drive(4100.0, m(1) + 15.0, stepM = 20.0)
        drive(m(2) - 30.0, m(2) + 15.0, stepM = 15.0)
        drive(m(3) - 30.0, m(3) + 15.0, stepM = 15.0)
        assertEquals(4, state.u)
    }

    /** The exit 24 trip with a stop on Peach St (two legs), planned and guided. */
    private fun Harness.guideWithPeachStop() {
        planExit24()
        send(NavIntent.AddStop(PEACH_STOP))
        advance(1_000)
        assertAllRecorded()
        assertEquals(Phase.CHOOSING, phase(), "${state.problem}")
        assertEquals(listOf("Peach St stop", "Downs Drive"), state.stops.map { it.place.name })
        send(NavIntent.Start())
        assertEquals(Phase.GUIDING, phase())
        assertEquals(2, state.guidance!!.route.legEnds.size)
    }

    @Test
    fun `A1 arrived when U is the arrival and the shown distance is within 30 m`() = sessionTest { h ->
        h.guideExit24()
        h.toLastStretch()
        h.at(m(4) - 40.0)
        assertEquals(Phase.GUIDING, h.phase())
        h.at(m(4) - 25.0)
        assertEquals(Phase.ARRIVED, h.phase())
    }

    @Test
    fun `A1 a stop passed in a gap is reached by the catch-up loop`() = sessionTest { h ->
        h.guideWithPeachStop()
        h.drive(4100.0, m(1) + 15.0, stepM = 20.0)
        h.drive(m(2) - 30.0, m(2) + 15.0, stepM = 15.0)
        val stopAlong = m(2) + 150.0
        h.at(stopAlong + 55.0, afterMs = 8_000)          // no fix from 15 m past the turn to 55 m past the stop
        val reached = h.eventsOf(TripEvent.StopReached::class.java).single() as TripEvent.StopReached
        assertEquals(0, reached.stopIndex)
        assertEquals("Turn right", h.state.guidance!!.banner!!.view.primary, "U moved past the arrival and the departure")
    }

    @Test
    fun `A1 a follower that reports complete on the last leg arrives`() = sessionTest(
        followers = { r, rules -> CompleteNearEnd(GeometricFollower(r.route, rules)) },
    ) { h ->
        h.guideExit24()
        h.toLastStretch()
        h.at(m(4) - 60.0)
        assertEquals(Phase.ARRIVED, h.phase(), "complete, 60 m before the end")
    }

    /** A follower that says complete within 80 m of the end (a stand-in for Ferrostar's own arrival). */
    private class CompleteNearEnd(val inner: GeometricFollower) : RouteFollower by inner {
        override fun update(fix: Fix): FollowerProgress {
            val p = inner.update(fix)
            return if (p.remainingM < 80.0) p.copy(complete = true) else p
        }
    }

    @Test
    fun `A2 an intermediate stop is marked reached, saved, announced, and guidance goes on`() = sessionTest { h ->
        h.guideWithPeachStop()
        h.drive(4100.0, m(1) + 15.0, stepM = 20.0)
        h.drive(m(2) - 30.0, m(2) + 15.0, stepM = 15.0)
        h.drive(m(2) + 60.0, m(2) + 110.0, stepM = 25.0)
        assertTrue(h.eventsOf(TripEvent.StopReached::class.java).isEmpty(), "40 m before the stop")
        h.at(m(2) + 125.0, speed = 5f)                     // 25 m before the stop: within arrival.within_m
        assertEquals(listOf(TripEvent.StopReached(0, "Peach St stop")), h.eventsOf(TripEvent.StopReached::class.java))
        assertTrue(h.state.stops[0].reached)
        assertFalse(h.state.stops[1].reached)
        assertEquals(1, h.state.guidance!!.nextStopIndex)
        assertEquals(1, h.state.guidance!!.legIndex)
        assertTrue(h.store.load()!!.stops[0].reached, "trip.json saved")
        assertEquals("Turn right", h.state.guidance!!.banner!!.view.primary)
        assertEquals(Phase.GUIDING, h.phase())
        // C1: a reached stop cannot be changed
        h.send(NavIntent.RemoveStop(0))
        assertEquals(2, h.state.stops.size)
    }

    @Test
    fun `A2 a stop the follower passed on one fix (entry and exit at once) is reached, U goes on to the next leg`() = sessionTest { h ->
        h.guideWithPeachStop()
        h.drive(4100.0, m(1) + 15.0, stepM = 20.0)
        h.drive(m(2) - 30.0, m(2) + 15.0, stepM = 15.0)
        h.drive(m(2) + 60.0, m(2) + 110.0, stepM = 25.0)      // 40 m before the stop: not within arrival.within_m yet
        assertTrue(h.eventsOf(TripEvent.StopReached::class.java).isEmpty())
        val revision = h.state.revision
        h.at(m(2) + 164.0, speed = 7f, afterMs = 2_000)        // 2 s later, 14 m past it: the follower entered and left the stop on this fix
        assertEquals(listOf(TripEvent.StopReached(0, "Peach St stop")), h.eventsOf(TripEvent.StopReached::class.java))
        assertTrue(h.state.stops[0].reached)
        assertTrue(h.state.revision > revision, "a stop reached is a change of the stops (TripState.revision)")
        assertEquals("Turn right", h.state.guidance!!.banner!!.view.primary, "the next leg's turn, never its departure")
        assertEquals(1, h.state.guidance!!.legIndex)
        assertEquals(Phase.GUIDING, h.phase())
        h.at(m(2) + 190.0)
        assertEquals(1, h.eventsOf(TripEvent.StopReached::class.java).size, "reached once")
    }

    @Test
    fun `A3 the destination - ARRIVED, Arrived, trip json deleted, IDLE when the car leaves`() = sessionTest { h ->
        h.guideExit24()
        assertNotNull(h.store.load())
        h.toLastStretch()
        h.at(m(4) - 20.0)
        assertEquals(Phase.ARRIVED, h.phase())
        assertEquals(listOf(TripEvent.Arrived("Downs Drive")), h.eventsOf(TripEvent.Arrived::class.java))
        assertNull(h.store.load())
        assertEquals(0, h.released, "the hold is kept in ARRIVED")
        val near = EXIT24.offset(m(4) - 20.0, 200.0)
        h.advance(1_000)
        h.gps(near.lat, near.lon)
        assertEquals(Phase.ARRIVED, h.phase(), "200 m away")
        val far = EXIT24.offset(m(4) - 20.0, 320.0)
        h.advance(1_000)
        h.gps(far.lat, far.lon)
        assertEquals(Phase.IDLE, h.phase(), "more than arrival.leave_m away")
        assertEquals(1, h.released)
        assertTrue(h.events.last() is TripEvent.GuidanceEnded)
    }

    @Test
    fun `A3 Done leaves ARRIVED`() = sessionTest { h ->
        h.guideExit24()
        h.toLastStretch()
        h.at(m(4) - 20.0)
        h.send(NavIntent.Done)
        assertEquals(Phase.IDLE, h.phase())
        assertEquals(1, h.released)
    }

    @Test
    fun `C1 add, move and remove stops before guidance, at most 9, the last is the destination`() = sessionTest { h ->
        h.planExit24()
        h.send(NavIntent.AddStop(PEACH_STOP))                  // null index: before the destination
        assertEquals(listOf("Peach St stop", "Downs Drive"), h.state.stops.map { it.place.name })
        h.send(NavIntent.MoveStop(1, 0))                       // the destination moved up: Peach St is the destination now
        assertEquals(listOf("Downs Drive", "Peach St stop"), h.state.stops.map { it.place.name })
        h.send(NavIntent.RemoveStop(1))
        assertEquals(listOf("Downs Drive"), h.state.stops.map { it.place.name })
        for (i in 0 until 8) h.send(NavIntent.AddStop(Place("Stop $i", GeoPoint(42.03 + i * 0.001, -80.1), PlaceSource.MAP_PIN)))
        assertEquals(9, h.state.stops.size)
        h.send(NavIntent.AddStop(PEACH_STOP))
        assertEquals(9, h.state.stops.size)
        assertEquals(ProblemKind.TOO_MANY_STOPS, h.state.problem!!.kind)
        assertEquals("A trip can have up to 9 stops.", h.state.problem!!.text)
        h.send(NavIntent.End)                                  // before the coalesced plan runs
        assertEquals(Phase.IDLE, h.phase())
    }

    @Test
    fun `C2 changes within coalesce_ms plan once, an answer for an older revision is dropped`() = sessionTest { h ->
        h.send(NavIntent.PlanTo(Data.DOWNS_DRIVE))
        h.send(NavIntent.SetOrigin(Origin.FromPlace(Data.EXIT24_ORIGIN, 57.0)))
        h.advance(200)
        h.send(NavIntent.AddStop(PEACH_STOP))
        h.advance(1_000)
        assertEquals(Phase.CHOOSING, h.phase())
        val planning = h.engine.routeRequests.filter { "format" !in it }
        assertTrue(planning.all { it["locations"]!!.jsonArray.size == 3 }, "only the last revision was planned")
        // a call running when a change comes: its answer is dropped
        val before = h.engine.routeRequests.size
        h.routingGate.gated = true
        h.send(NavIntent.RemoveStop(0))
        h.advance(400)
        assertTrue(h.routingGate.waiting > 0, "the first call waits")
        h.send(NavIntent.AddStop(PEACH_STOP))
        h.routingGate.release()
        h.advance(1_000)
        assertEquals(Phase.CHOOSING, h.phase())
        assertTrue(h.state.choices.all { it.legEnds.size == 2 }, "the choices are the newest revision's")
        assertTrue(h.engine.routeRequests.size > before)
    }

    @Test
    fun `C3 a stop added during guidance re-plans from the car, swaps the route, saves trip json`() = sessionTest { h ->
        h.guideExit24()
        h.drive(1000.0, 1100.0)
        h.send(NavIntent.AddStop(PEACH_STOP))
        h.run()
        val e = h.eventsOf(TripEvent.RouteReplaced::class.java).single() as TripEvent.RouteReplaced
        assertEquals(ReplaceReason.STOPS_CHANGED, e.reason)
        assertEquals(2, h.state.guidance!!.route.legEnds.size)
        assertEquals(2, h.store.load()!!.stops.size)
        assertFalse(h.state.replanning)
    }

    @Test
    fun `C3 a change that fails is undone, its problem shows for 8 s, RerouteFailed`() = sessionTest { h ->
        h.guideExit24()
        h.drive(1000.0, 1100.0)
        val routeId = h.state.guidance!!.route.id
        h.engine.failWhen = { req -> if ("format" !in req) EngineException(442, "No path could be found for input") else null }
        h.routingGate.gated = true
        h.send(NavIntent.AddStop(PEACH_STOP))
        assertTrue(h.state.replanning)
        assertEquals(2, h.state.stops.size, "shown while it runs")
        h.at(1125.0)
        assertEquals(Phase.GUIDING, h.phase(), "guidance continues on the old route")
        h.routingGate.release()
        h.run()
        assertEquals(1, h.state.stops.size, "undone")
        assertEquals(routeId, h.state.guidance!!.route.id)
        assertEquals(ProblemKind.NO_ROUTE, h.state.problem!!.kind)
        assertEquals(1, h.eventsOf(TripEvent.RerouteFailed::class.java).size)
        h.at(1150.0, afterMs = 7_000)
        assertNotNull(h.state.problem)
        h.at(1175.0, afterMs = 2_000)
        assertNull(h.state.problem, "gone after planning.problem_hold_s")
    }

    /** A stop on Downs Dr, 150 m after the right turn (on the route). */
    private val downsStop: Place by lazy {
        val p = EXIT24.pointAt(m(3) + 150.0)
        Place("Downs Dr stop", GeoPoint(RouteSimulator.round6(p.lat), RouteSimulator.round6(p.lon)), PlaceSource.MAP_PIN)
    }

    @Test
    fun `C3 a stop reached while a change waits for its route is reached once, and the change is asked again from there`() = sessionTest { h ->
        h.guideWithPeachStop()
        h.drive(4100.0, m(1) + 15.0, stepM = 20.0)
        h.drive(m(2) - 30.0, m(2) + 15.0, stepM = 15.0)
        h.drive(m(2) + 60.0, m(2) + 110.0, stepM = 25.0)
        h.routingGate.gated = true
        h.send(NavIntent.AddStop(downsStop))                    // before the destination
        assertTrue(h.state.replanning)
        assertEquals(listOf("Peach St stop", "Downs Dr stop", "Downs Drive"), h.state.stops.map { it.place.name }, "shown at once")
        h.at(m(2) + 125.0, speed = 5f)                          // within 30 m of the Peach St stop, still on the old route
        assertEquals(listOf(TripEvent.StopReached(0, "Peach St stop")), h.eventsOf(TripEvent.StopReached::class.java))
        assertEquals(listOf(true, false, false), h.state.stops.map { it.reached })
        h.routingGate.release()
        h.run()
        val replaced = h.eventsOf(TripEvent.RouteReplaced::class.java).map { (it as TripEvent.RouteReplaced).reason }
        assertEquals(listOf(ReplaceReason.STOPS_CHANGED), replaced, "one swap, with the answer asked from the stop")
        val asked = h.engine.routeRequests.last { "format" !in it }["locations"]!!.jsonArray
        assertEquals(3, asked.size, "the car and the two stops not reached")
        assertEquals(2, h.state.guidance!!.route.legEnds.size)
        assertEquals(listOf(true, false, false), h.state.stops.map { it.reached })
        assertFalse(h.state.replanning)
        assertTrue(h.store.load()!!.stops[0].reached, "trip.json")
        h.at(m(2) + 150.0)
        h.at(m(2) + 175.0)
        assertEquals(1, h.eventsOf(TripEvent.StopReached::class.java).size, "not reached a second time")
    }

    @Test
    fun `C3 a failed change goes back to the trip of the guided route, also after a second change, settings too`() = sessionTest { h ->
        h.guideExit24()
        h.drive(1000.0, 1100.0)
        val routeId = h.state.guidance!!.route.id
        h.routingGate.gated = true
        h.send(NavIntent.SetOptions(RouteOptions(avoidTolls = true)))
        h.send(NavIntent.AddStop(PEACH_STOP))                   // a second change while the first waits
        assertEquals(listOf("Peach St stop", "Downs Drive"), h.state.stops.map { it.place.name })
        assertTrue(h.settings.settings.value.avoidTolls)
        h.engine.failWhen = { req -> if ("format" !in req) EngineException(442, "No path could be found for input") else null }
        h.routingGate.release()
        h.run()
        assertEquals(listOf("Downs Drive"), h.state.stops.map { it.place.name }, "the guided route's stops, not the first change's")
        assertEquals(RouteOptions(), h.state.options)
        assertFalse(h.settings.settings.value.avoidTolls, "settings back as well")
        assertEquals(routeId, h.state.guidance!!.route.id)
        assertEquals(1, h.eventsOf(TripEvent.RerouteFailed::class.java).size)
        assertFalse(h.state.replanning)
        assertEquals(Phase.GUIDING, h.phase())
    }

    @Test
    fun `C4 options before guidance re-plan and are saved, during guidance they re-plan from the car`() = sessionTest { h ->
        h.planExit24()
        h.send(NavIntent.SetOptions(RouteOptions(avoidHighways = true)))
        assertEquals(Phase.PLANNING, h.phase())
        h.advance(1_000)
        assertEquals(Phase.CHOOSING, h.phase(), "${h.state.problem}")
        assertTrue(h.settings.settings.value.avoidHighways)
        assertTrue(h.engine.routeRequests.any { (it["costing_options"]!!.jsonObject["auto"] as JsonObject)["exclude_highways"] != null })
        h.send(NavIntent.Start())
        h.drive(1000.0, 1050.0)
        h.send(NavIntent.SetOptions(RouteOptions(avoidHighways = true, avoidTolls = true)))
        h.run()
        val e = h.eventsOf(TripEvent.RouteReplaced::class.java).single() as TripEvent.RouteReplaced
        assertEquals(ReplaceReason.OPTIONS_CHANGED, e.reason)
        assertTrue(h.settings.settings.value.avoidTolls)
        assertEquals(RouteOptions(true, true), h.store.load()!!.options)
    }

    @Test
    fun `C5 a new generation while choosing re-plans, in IDLE the engine is closed`() = sessionTest { h ->
        h.planExit24()
        val opened = h.opens
        h.installed.value = h.installed.value.copy(generation = 2)
        h.run()
        assertEquals(Phase.PLANNING, h.phase())
        assertEquals(ProblemKind.PACKS_CHANGED, h.state.problem!!.kind)
        assertTrue(h.state.choices.isEmpty(), "choices of the older generation are discarded")
        h.advance(1_000)
        assertEquals(Phase.CHOOSING, h.phase())
        assertEquals(opened + 1, h.opens, "the new generation's engine")
        assertTrue(h.state.choices.isNotEmpty())
        // IDLE: a new generation closes the engine at once (2.2 rule 3)
        h.send(NavIntent.End)
        val closed = h.engine.closed
        h.installed.value = h.installed.value.copy(generation = 3)
        h.run()
        assertEquals(closed + 1, h.engine.closed)
        // the routing set removed while choosing: outside the installed maps
        h.planExit24()
        h.installed.value = InstalledSet(generation = 4, scanned = true, routing = null)
        h.advance(1_000)
        assertEquals(Phase.CHOOSING, h.phase())
        assertEquals(ProblemKind.OUTSIDE_INSTALLED_MAPS, h.state.problem!!.kind)
        assertTrue(h.state.choices.isEmpty())
    }

    @Test
    fun `C6 best order - one matrix with the trip's type and options, the destination kept last`() = sessionTest { h ->
        val i90 = EXIT24.pointAt(2000.0)
        val onI90 = Place("On I-90", GeoPoint(RouteSimulator.round6(i90.lat), RouteSimulator.round6(i90.lon)), PlaceSource.MAP_PIN)
        h.planExit24()
        h.send(NavIntent.AddStop(PEACH_STOP))
        h.send(NavIntent.AddStop(onI90))                     // the order: Peach St, I-90, Downs Dr (I-90 comes first on the road)
        h.advance(1_000)
        assertEquals(listOf("Peach St stop", "On I-90", "Downs Drive"), h.state.stops.map { it.place.name })
        h.send(NavIntent.OptimizeStopOrder)
        h.advance(1_000)
        assertEquals(listOf("On I-90", "Peach St stop", "Downs Drive"), h.state.stops.map { it.place.name })
        assertEquals(1, h.engine.asked.count { "sources" in it })
        // play test P49: Best order says what it did (the stops editor's "Stops reordered.")
        assertEquals(1, h.events.count { it == TripEvent.StopsReordered }, "a changed order is told")
        assertEquals(Phase.CHOOSING, h.phase(), "re-planned as C2")
        // no connected order: unchanged, NO_ROUTE
        h.engine.failWhen = { req -> if ("sources" in req) EngineException(442, "no") else null }
        h.send(NavIntent.MoveStop(0, 1))
        h.advance(1_000)
        h.send(NavIntent.OptimizeStopOrder)
        h.advance(500)
        assertEquals(listOf("Peach St stop", "On I-90", "Downs Drive"), h.state.stops.map { it.place.name })
        assertEquals(ProblemKind.NO_ROUTE, h.state.problem!!.kind)
        assertEquals(1, h.events.count { it == TripEvent.StopsReordered }, "a refused reorder says nothing more")
    }

    private fun JsonObject.auto() = (this["costing_options"]?.jsonObject?.get("auto") as? JsonObject) ?: JsonObject(emptyMap())

    /** Simpler's pool asks with maneuver_penalty (its second and later candidates); Fastest never does. */
    private fun JsonObject.isSimpler() = "maneuver_penalty" in auto()

    @Test
    fun `C7 the chosen card's type is the trip's - settings, re-route, trip json and resume use it`() = sessionTest { h ->
        h.planExit24()
        val simpler = h.state.choices.first { "simpler" in it.typeIds }
        if (simpler.typeIds.first() == "simpler") h.send(NavIntent.Choose(simpler.id)) else h.send(NavIntent.SetRouteType("simpler"))
        assertEquals("simpler", h.state.routeType)
        assertEquals(simpler.id, h.state.selectedChoiceId)
        assertEquals("simpler", h.settings.settings.value.routeType)
        // choosing a card without the trip's type takes the card's first type
        val other = h.state.choices.firstOrNull { "simpler" !in it.typeIds }
        if (other != null) {
            h.send(NavIntent.Choose(other.id))
            assertEquals(other.typeIds.first(), h.state.routeType)
            h.send(NavIntent.SetRouteType("simpler"))
        }
        h.send(NavIntent.Start(io.github.spysyweeb.beamline.api.nav.Simulation(scriptName = "wrongturn")))
        assertEquals("simpler", h.store.load()!!.routeType)
        val before = h.engine.routeRequests.size
        var s = 0
        while (h.eventsOf(TripEvent.RouteReplaced::class.java).isEmpty() && s++ < 400) h.advance(1_000)
        assertTrue(h.engine.routeRequests.drop(before).filter { "format" !in it }.any { it.isSimpler() }, "a Simpler re-route")
        assertEquals("simpler", h.store.load()!!.routeType)
        // process death (the session ends without End: trip.json stays), the app started again a minute later
        h.close()
        val again = Harness(this, dir = h.dir, wallStartMs = h.wallStartMs + 60_000L)
        try {
            assertEquals(Phase.RESUME_OFFERED, again.phase())
            assertEquals("simpler", again.state.routeType)
            again.gps(Data.EXIT24_ORIGIN.point.lat, Data.EXIT24_ORIGIN.point.lon, 8f, 10f, 57f)
            again.send(NavIntent.Resume)
            again.advance(500)
            assertEquals(Phase.GUIDING, again.phase(), "${again.state.problem}")
            assertTrue(again.engine.routeRequests.filter { "format" !in it }.any { it.isSimpler() }, "Resume keeps Simpler")
            assertEquals("simpler", again.state.routeType)
            again.assertAllRecorded()
        } finally {
            again.close()
        }
    }

    @Test
    fun `C7 SetRouteType selects the card holding the type, a type no card holds leaves the selected card's type`() = sessionTest { h ->
        h.planExit24()
        val simpler = h.state.choices.first { "simpler" in it.typeIds }
        h.send(NavIntent.SetRouteType("simpler"))
        assertEquals(simpler.id, h.state.selectedChoiceId)
        assertEquals("simpler", h.state.routeType)
        h.send(NavIntent.End)
        // this time Simpler finds no route (every candidate of its pool fails): no card holds it
        h.settings.update { it.copy(routeType = "fastest") }
        h.engine.failWhen = { req ->
            if (req.isSimpler() || "alternates" in req) EngineException(442, "No path could be found for input") else null
        }
        h.planExit24()
        assertEquals(Phase.CHOOSING, h.phase())
        assertTrue(h.state.choices.none { "simpler" in it.typeIds })
        val selected = h.state.selectedChoiceId
        h.send(NavIntent.SetRouteType("simpler"))
        assertEquals(selected, h.state.selectedChoiceId)
        assertTrue(h.state.routeType in h.state.choices.first { it.id == selected }.typeIds, "P4, C7: the trip's type is the selected card's")
        assertEquals("simpler", h.settings.settings.value.routeType, "the choice is written to settings (C4)")
        // a type that is not offered (Shortest left in batch D; Eco never was) changes nothing
        h.send(NavIntent.SetRouteType("shortest"))
        assertEquals("simpler", h.settings.settings.value.routeType)
        assertEquals(selected, h.state.selectedChoiceId)
    }
}
