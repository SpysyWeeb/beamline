package io.github.spysyweeb.beamline.nav

import io.github.spysyweeb.beamline.api.nav.END_UNDO_MS
import io.github.spysyweeb.beamline.api.nav.NavIntent
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.RouteOptions
import io.github.spysyweeb.beamline.api.nav.Simulation
import io.github.spysyweeb.beamline.api.nav.TripEvent
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Rule R4 (docs/architecture.md 3.10): End during guidance can be taken back for [END_UNDO_MS]. */
class UndoEndTest {

    /** A fix at the exit 24 origin, moving 10 m/s on bearing 57 (the trip the recordings hold). */
    private fun Harness.fixAtOrigin() =
        gps(Data.EXIT24_ORIGIN.point.lat, Data.EXIT24_ORIGIN.point.lon, 8f, 10f, 57f)

    @Test
    fun `R4 UndoEnd within 10 s guides the same trip again - same stops and route type, re-planned from the current position`() =
        sessionTest { h ->
            h.planExit24()
            h.send(NavIntent.SetRouteType("simpler"))
            h.send(NavIntent.Start())
            assertEquals(Phase.GUIDING, h.phase(), "${h.state.problem}")
            h.fixAtOrigin()
            h.send(NavIntent.End)
            assertEquals(Phase.IDLE, h.phase())
            assertNull(h.store.load(), "End deletes trip.json at once (R1)")
            assertEquals(1, h.released)
            val requestsBefore = h.engine.routeRequests.size

            h.advance(END_UNDO_MS - 1_000)
            h.send(NavIntent.UndoEnd)
            h.advance(500)
            assertEquals(Phase.GUIDING, h.phase(), "${h.state.problem}")
            assertEquals(listOf("Downs Drive"), h.state.stops.map { it.place.name })
            assertEquals("simpler", h.state.routeType)
            assertEquals(RouteOptions(), h.state.options)
            assertEquals(2, h.holds, "guidance holds the packs again")
            assertNotNull(h.store.load(), "trip.json written again")
            // a new plan from the current position (the fix, not the trip's first origin), then guidance
            val asked = h.engine.routeRequests.drop(requestsBefore).filter { "format" !in it }
            assertTrue(asked.any { "maneuver_penalty" in it["costing_options"]!!.jsonObject["auto"]!!.jsonObject }, "the trip's Simpler")
            val kinds = h.events.map { it::class.simpleName }
            assertEquals(listOf("GuidanceStarted", "GuidanceEnded", "GuidanceStarted"), kinds, "no RouteReplaced: it is not a resume")
        }

    @Test
    fun `R4 UndoEnd later than 10 s after End, or after any other intent, does nothing`() = sessionTest { h ->
        h.guideExit24()
        h.fixAtOrigin()
        h.send(NavIntent.End)
        h.advance(END_UNDO_MS + 1)
        h.send(NavIntent.UndoEnd)
        h.advance(500)
        assertEquals(Phase.IDLE, h.phase())
        assertEquals(1, h.holds)

        h.guideExit24()
        h.fixAtOrigin()
        h.send(NavIntent.End)
        h.send(NavIntent.Done)                    // ignored in IDLE, but it is the next intent: End is final now
        h.send(NavIntent.UndoEnd)
        h.advance(500)
        assertEquals(Phase.IDLE, h.phase())
        assertEquals(2, h.holds)
        assertNull(h.store.load())
    }

    @Test
    fun `R4 a simulated drive comes back simulated`() = sessionTest { h ->
        h.guideExit24(Simulation())
        h.advance(3_000)
        h.send(NavIntent.End)
        h.fixAtOrigin()                            // the real position, as the phone has it after End
        h.send(NavIntent.UndoEnd)
        h.advance(500)
        assertEquals(Phase.GUIDING, h.phase(), "${h.state.problem}")
        assertTrue(h.state.guidance!!.simulated)
        assertTrue(h.events.filterIsInstance<TripEvent.GuidanceStarted>().last().simulated)
    }

    /** Audit F1: a place window open at End asks for its drive time at once; that question is not "the next intent". */
    @Test
    fun `an estimate between End and UndoEnd leaves the Undo`() = sessionTest { h ->
        h.guideExit24()
        h.fixAtOrigin()
        h.send(NavIntent.End)
        h.send(NavIntent.Estimate(Data.DOWNS_DRIVE))
        h.advance(1_000)
        h.send(NavIntent.UndoEnd)
        h.advance(500)
        assertEquals(Phase.GUIDING, h.phase(), "${h.state.problem}")
        assertEquals(listOf("Downs Drive"), h.state.stops.map { it.place.name })
    }

    /** Audit F31: a route closed before it started (X or Back) comes back as it was, planned again. */
    @Test
    fun `UndoEnd brings back a closed route, planned again with its type`() = sessionTest { h ->
        h.planExit24()
        h.send(NavIntent.SetRouteType("simpler"))
        assertEquals(Phase.CHOOSING, h.phase())
        h.send(NavIntent.End)
        assertEquals(Phase.IDLE, h.phase())
        h.advance(2_000)
        h.fixAtOrigin()
        h.send(NavIntent.UndoEnd)
        h.advance(2_000)
        assertEquals(Phase.CHOOSING, h.phase(), "${h.state.problem}")
        assertEquals(listOf("Downs Drive"), h.state.stops.map { it.place.name })
        assertEquals("simpler", h.state.routeType)
        assertTrue(h.state.choices.isNotEmpty())
        assertEquals(0, h.holds, "planning holds nothing; only guidance does")
        // and closed again, then anything else first: final
        h.send(NavIntent.End)
        h.send(NavIntent.Done)
        h.send(NavIntent.UndoEnd)
        h.advance(500)
        assertEquals(Phase.IDLE, h.phase())
    }
}
