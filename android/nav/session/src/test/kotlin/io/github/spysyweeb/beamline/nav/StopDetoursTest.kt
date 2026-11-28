package io.github.spysyweeb.beamline.nav

import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.nav.NavIntent
import io.github.spysyweeb.beamline.api.nav.Origin
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.ProblemKind
import io.github.spysyweeb.beamline.api.nav.ReplaceReason
import io.github.spysyweeb.beamline.api.nav.TripEvent
import io.github.spysyweeb.beamline.routing.engine.EngineException
import io.github.spysyweeb.beamline.routing.model.Geo
import io.github.spysyweeb.beamline.routing.model.LatLon
import io.github.spysyweeb.beamline.routing.valhalla.canonicalJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.max

/**
 * Item 51, rule C8 (docs/architecture.md 3.4): what a new stop would add to the trip ([NavIntent.Detours]) and
 * the route with it ([NavIntent.PreviewStop]), on the exit 24 trip. The matrices are made up ([fakeMatrix]);
 * the previews' routes are recorded requests.
 */
class StopDetoursTest {
    private fun place(name: String, p: LatLon) = Place(name, GeoPoint(RouteSimulator.round6(p.lat), RouteSimulator.round6(p.lon)), PlaceSource.SEARCH)

    /** On the route (I-90), 2 km off it, and one the fake matrix cannot reach. */
    private val a = place("On the way", EXIT24.pointAt(3000.0))
    private val b = place("Off the way", EXIT24.offset(3000.0, 2000.0))
    private val c = place("No road", EXIT24.offset(2000.0, 3000.0))

    private fun GeoPoint.ll() = LatLon(lat, lon)
    private fun near(want: LatLon, got: JsonObject, m: Double = 1.0) =
        assertTrue(Geo.distanceM(want, got.latLon()) <= m, "${got.latLon()} is not $want")

    private fun Harness.matrices(from: Int = 0) = engine.asked.drop(from).filter { "sources" in it }
    private fun JsonObject.list(k: String) = this[k]!!.jsonArray.map { it.jsonObject }

    /** A fix at the exit 24 origin, moving 10 m/s on bearing 57 (the trip the recordings hold). */
    private fun Harness.fixAtOrigin() = gps(Data.EXIT24_ORIGIN.point.lat, Data.EXIT24_ORIGIN.point.lon, 8f, 10f, 57f)

    @Test
    fun `C8 detours from the car - to the place and on to the destination, less that leg now`() = sessionTest { h ->
        h.guideExit24()
        h.drive(1000.0, 1100.0)
        h.engine.matrixAnswer = fakeMatrix(unreachable = setOf(c.point.ll()))
        val routeId = h.state.guidance!!.route.id
        val revision = h.state.revision
        val stops = h.state.stops
        val events = h.events.size
        val from = h.engine.asked.size
        h.send(NavIntent.Detours(7, listOf(a, b, c)))
        h.run()
        val m = h.matrices(from)
        assertEquals(2, m.size, "two matrices for one chunk")
        // J: the car as a re-route's origin (heading and radius); R: the destination (the trip is shorter than 10 km past the places)
        val j = m[0].list("sources").single()
        assertTrue("heading" in j && "radius" in j, "$j")
        val targets = m[0].list("targets")
        assertEquals(4, targets.size)
        near(a.point.ll(), targets[0]); near(b.point.ll(), targets[1]); near(c.point.ll(), targets[2])
        near(Data.DOWNS_DRIVE.point.ll(), targets[3], 20.0)
        assertEquals(3, m[1].list("sources").size)
        listOf(a, b, c).forEachIndexed { i, p -> near(p.point.ll(), m[1].list("sources")[i]) }
        val r = m[1].list("targets").single()
        near(targets[3].latLon(), r, 0.01)
        val d = h.state.detours!!
        assertEquals(7, d.requestId)
        assertTrue(d.done)
        val legS = fakeTimeS(j.latLon(), r.latLon())
        assertEquals(legS, d.legS!!, 0.5)
        for ((i, p) in listOf(a, b).withIndex()) {
            val want = max(0.0, fakeTimeS(j.latLon(), p.point.ll()) + fakeTimeS(p.point.ll(), r.latLon()) - legS)
            assertEquals(want, d.addedS[i]!!, 0.5, p.name)
        }
        assertTrue(d.addedS[1]!! > d.addedS[0]!! + 60.0, "2 km off the route adds more")
        assertNull(d.addedS[2], "no connection")
        assertEquals(stops, h.state.stops)
        assertEquals(revision, h.state.revision)
        assertEquals(routeId, h.state.guidance!!.route.id)
        assertFalse(h.state.replanning, "the route sheet does not look busy")
        assertEquals(events, h.events.size, "no event")
    }

    @Test
    fun `C8 with a stop before the destination the detour joins at that stop`() = sessionTest { h ->
        h.planExit24()
        h.send(NavIntent.AddStop(PEACH_STOP))
        h.advance(1_000)
        assertEquals(Phase.CHOOSING, h.phase(), "${h.state.problem}")
        h.engine.matrixAnswer = fakeMatrix()
        val from = h.engine.asked.size
        h.send(NavIntent.Detours(1, listOf(a)))
        h.run()
        val first = h.matrices(from).first()
        val j = first.list("sources").single()
        near(PEACH_STOP.point.ll(), j, 5.0)
        assertFalse("heading" in j, "a stop, not the car: no heading")
        near(Data.DOWNS_DRIVE.point.ll(), first.list("targets").last(), 20.0)
        assertTrue(h.state.detours!!.done)
        assertNotNull(h.state.detours!!.addedS.single())
    }

    @Test
    fun `C8 chunks of ten, a newer request replaces the older, an empty one drops it`() = sessionTest { h ->
        h.planExit24()
        h.engine.matrixAnswer = fakeMatrix()
        val many = List(25) { i -> place("Place $i", EXIT24.offset(150.0 * i + 100.0, 300.0)) }
        h.routingGate.gated = true
        h.send(NavIntent.Detours(1, many))
        h.run()
        assertEquals(1, h.state.detours!!.requestId)
        assertFalse(h.state.detours!!.done)
        assertEquals(List<Double?>(25) { null }, h.state.detours!!.addedS, "nothing worked out yet")
        // one held call let through; the gate is closed again for the job's next call
        h.routingGate.release()
        h.routingGate.gated = true
        h.run()
        assertEquals(11, h.matrices().last().list("targets").size, "ten places and the rejoin point")
        val mark = h.engine.asked.size
        h.send(NavIntent.Detours(2, listOf(a)))
        h.run()
        assertEquals(2, h.state.detours!!.requestId, "the newer request replaces the older")
        h.routingGate.release()
        h.run()
        val d = h.state.detours!!
        assertEquals(2, d.requestId)
        assertTrue(d.done)
        assertEquals(1, d.addedS.size)
        assertNotNull(d.addedS.single())
        assertTrue(h.matrices(mark).isNotEmpty())
        assertTrue(h.matrices(mark).none { it.list("targets").size == 11 }, "request 1's next chunk never ran")
        assertTrue(h.matrices(mark).none { it.list("sources").size == 10 }, "request 1's held call was dropped")
        // an older list's late "drop it" leaves the newer one; its own drops it
        val asked = h.engine.asked.size
        h.send(NavIntent.Detours(1, emptyList()))
        assertEquals(2, h.state.detours!!.requestId)
        h.send(NavIntent.Detours(2, emptyList()))
        assertNull(h.state.detours)
        h.advance(1_000)
        assertEquals(asked, h.engine.asked.size, "nothing more asked")
    }

    @Test
    fun `C8 a change of the stops drops the answer, a stop reached keeps it`() = sessionTest { h ->
        h.guideExit24()
        h.drive(1000.0, 1100.0)
        h.engine.matrixAnswer = fakeMatrix()
        h.routingGate.gated = true
        h.send(NavIntent.Detours(1, listOf(a, b)))
        h.send(NavIntent.AddStop(PEACH_STOP))
        assertNull(h.state.detours, "dropped at once")
        h.routingGate.release()
        h.run()
        assertNull(h.state.detours, "no late answer")
        assertEquals(1, h.eventsOf(TripEvent.RouteReplaced::class.java).size, "the C3 re-plan as today")
        assertEquals(ReplaceReason.STOPS_CHANGED, (h.eventsOf(TripEvent.RouteReplaced::class.java).single() as TripEvent.RouteReplaced).reason)
        // a stop reached changes only its flag: the answer stays
        h.send(NavIntent.Detours(2, listOf(a)))
        h.run()
        assertTrue(h.state.detours!!.done)
        h.drive(4100.0, m(1) + 15.0, stepM = 20.0)
        h.drive(m(2) - 30.0, m(2) + 15.0, stepM = 15.0)
        h.drive(m(2) + 60.0, m(2) + 110.0, stepM = 25.0)
        h.at(m(2) + 125.0, speed = 5f)
        assertEquals(1, h.eventsOf(TripEvent.StopReached::class.java).size)
        assertEquals(2, h.state.detours?.requestId, "kept when a stop is reached")
    }

    /**
     * Review F-n 1: a change while guiding that fails (C3: the trip goes back to the guided route's stops) drops
     * the detours and the preview asked while it was planned: they were worked out for the stops of the change.
     */
    @Test
    fun `C8 a failed change while guiding drops the detours and the preview asked meanwhile`() = sessionTest { h ->
        h.guideExit24()
        h.drive(1000.0, 1100.0)
        h.engine.matrixAnswer = fakeMatrix()
        h.routingGate.gated = true
        h.send(NavIntent.AddStop(PEACH_STOP))
        assertTrue(h.state.replanning)
        assertEquals(listOf("Peach St stop", "Downs Drive"), h.state.stops.map { it.place.name })
        h.send(NavIntent.Detours(1, listOf(a)))
        h.send(NavIntent.PreviewStop(a))
        assertFalse(h.state.detours!!.done)
        assertTrue(h.state.stopPreview!!.busy)
        // no route to the new stop: the C3 plan (and the preview's, from the same stops) fails
        h.engine.failWhen = { req -> if ("sources" !in req && "shape" !in req && "format" !in req) EngineException(442, "No path could be found for input") else null }
        h.routingGate.release()
        h.run()
        assertEquals(listOf("Downs Drive"), h.state.stops.map { it.place.name }, "back to the guided route's stops")
        assertEquals(1, h.eventsOf(TripEvent.RerouteFailed::class.java).size)
        assertNull(h.state.detours, "worked out for a stop no longer in the trip")
        assertNull(h.state.stopPreview, "not busy for ever")
        // asked again: from the trip as it is
        h.engine.failWhen = null
        h.send(NavIntent.Detours(2, listOf(a)))
        h.run()
        assertTrue(h.state.detours!!.done)
        assertNotNull(h.state.detours!!.addedS.single())
    }

    @Test
    fun `C8 a new start before guidance and arriving drop the answer`() = sessionTest { h ->
        h.planExit24()
        h.engine.matrixAnswer = fakeMatrix()
        h.routingGate.gated = true
        h.send(NavIntent.Detours(1, listOf(a)))
        h.send(NavIntent.SetOrigin(Origin.FromPlace(Data.EXIT24_ORIGIN, 57.0)))
        assertNull(h.state.detours, "dropped at once")
        h.routingGate.release()
        h.advance(1_000)
        assertEquals(Phase.CHOOSING, h.phase(), "${h.state.problem}")
        assertNull(h.state.detours, "no later answer for id 1")
        // the chosen card (the route the line is read from) drops it too
        h.send(NavIntent.Detours(2, listOf(a)))
        h.run()
        assertNotNull(h.state.detours)
        h.send(NavIntent.Choose(h.state.selectedChoiceId!!))
        assertNull(h.state.detours)
        h.send(NavIntent.End)

        h.guideExit24()
        h.routingGate.gated = true
        h.send(NavIntent.Detours(3, listOf(a)))
        h.drive(4100.0, m(1) + 15.0, stepM = 20.0)
        h.drive(m(2) - 30.0, m(2) + 15.0, stepM = 15.0)
        h.drive(m(3) - 30.0, m(3) + 15.0, stepM = 15.0)
        h.at(m(4) - 20.0)
        assertEquals(Phase.ARRIVED, h.phase())
        h.routingGate.release()
        h.run()
        assertNull(h.state.detours, "never published in ARRIVED")
    }

    @Test
    fun `C8 detours and previews do not count against Undo (R4)`() = sessionTest { h ->
        h.guideExit24()
        h.fixAtOrigin()
        h.send(NavIntent.End)
        h.send(NavIntent.Detours(1, listOf(a)))
        h.send(NavIntent.PreviewStop(a))
        assertNull(h.state.detours)
        assertNull(h.state.stopPreview)
        h.send(NavIntent.UndoEnd)
        h.advance(500)
        assertEquals(Phase.GUIDING, h.phase(), "${h.state.problem}")
        assertNull(h.state.detours)
        assertNull(h.state.stopPreview)
    }

    @Test
    fun `C8 without a position before guidance nothing is worked out`() = sessionTest(puck = false) { h ->
        h.send(NavIntent.PlanTo(Data.DOWNS_DRIVE))
        h.run()
        h.send(NavIntent.Detours(1, listOf(a, b)))
        val d = h.state.detours!!
        assertTrue(d.done)
        assertEquals(listOf<Double?>(null, null), d.addedS)
        assertTrue(h.matrices().isEmpty(), "no matrix asked")
        h.send(NavIntent.End)
    }

    @Test
    fun `C8 a preview before guidance plans the route with the stop and changes nothing`() = sessionTest { h ->
        h.planExit24()
        assertEquals(Phase.CHOOSING, h.phase(), "${h.state.problem}")
        val choices = h.state.choices
        val revision = h.state.revision
        val selected = choices.first { it.id == h.state.selectedChoiceId }
        val from = h.engine.routeRequests.size
        h.routingGate.gated = true
        h.send(NavIntent.PreviewStop(PEACH_STOP))
        assertTrue(h.state.stopPreview!!.busy)
        assertNull(h.state.stopPreview!!.route)
        h.routingGate.release()
        h.advance(1_000)
        val p = h.state.stopPreview!!
        assertFalse(p.busy)
        assertNull(p.problem)
        assertEquals(PEACH_STOP, p.place)
        assertEquals(2, p.route!!.legEnds.size)
        assertEquals(max(0.0, p.route!!.timeS - selected.timeS), p.addedS!!, 1e-6)
        assertEquals(listOf("Downs Drive"), h.state.stops.map { it.place.name })
        assertEquals(Phase.CHOOSING, h.phase())
        assertEquals(revision, h.state.revision)
        assertEquals(choices, h.state.choices)
        assertFalse(h.state.replanning)
        val asked = h.engine.routeRequests.drop(from)
        assertTrue(asked.isNotEmpty() && asked.all { it["locations"]!!.jsonArray.size == 3 }, "$asked")
        h.send(NavIntent.PreviewStop(null))
        assertNull(h.state.stopPreview)
        // the same request C2's first plan with the stop added asks
        val before = h.engine.routeRequests.size
        h.send(NavIntent.AddStop(PEACH_STOP))
        h.advance(1_000)
        assertEquals(canonicalJson(asked.first()), canonicalJson(h.engine.routeRequests[before]))
    }

    @Test
    fun `C8 a preview while guiding plans from the car, Add stop then re-plans as C3`() = sessionTest { h ->
        h.guideExit24()
        h.drive(1000.0, 1100.0)
        val routeId = h.state.guidance!!.route.id
        val from = h.engine.routeRequests.size
        h.send(NavIntent.PreviewStop(PEACH_STOP))
        h.run()
        val p = h.state.stopPreview!!
        assertFalse(p.busy)
        assertEquals(2, p.route!!.legEnds.size)
        assertEquals(max(0.0, p.route!!.timeS - h.state.guidance!!.remainingS), p.addedS!!, 1.0)
        val req = h.engine.routeRequests.drop(from).first()
        val car = req["locations"]!!.jsonArray[0].jsonObject
        assertTrue("heading" in car && car["radius"]!!.jsonPrimitive.int > 0, "$car")
        assertTrue(h.eventsOf(TripEvent.RouteReplaced::class.java).isEmpty())
        assertEquals(routeId, h.state.guidance!!.route.id)
        assertEquals(1, h.state.stops.size)
        val before = h.engine.routeRequests.size
        h.send(NavIntent.AddStop(PEACH_STOP))
        h.run()
        assertNull(h.state.stopPreview, "Add stop ends the look")
        assertEquals(1, h.eventsOf(TripEvent.RouteReplaced::class.java).size)
        assertEquals(canonicalJson(req), canonicalJson(h.engine.routeRequests[before]), "the same request C3 asks")
    }

    @Test
    fun `C8 a preview that would be a tenth stop says so`() = sessionTest { h ->
        h.planExit24()
        for (i in 0 until 8) h.send(NavIntent.AddStop(Place("Stop $i", GeoPoint(42.03 + i * 0.001, -80.1), PlaceSource.MAP_PIN)))
        assertEquals(9, h.state.stops.size)
        val from = h.engine.routeRequests.size
        h.send(NavIntent.PreviewStop(PEACH_STOP))
        assertEquals(ProblemKind.TOO_MANY_STOPS, h.state.stopPreview!!.problem!!.kind)
        assertNull(h.state.stopPreview!!.route)
        assertEquals(from, h.engine.routeRequests.size, "no route asked")
        h.send(NavIntent.End)                                  // before the coalesced plan of nine stops runs
    }
}
