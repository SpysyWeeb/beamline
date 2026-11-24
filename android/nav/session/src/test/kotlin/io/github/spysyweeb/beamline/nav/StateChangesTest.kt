package io.github.spysyweeb.beamline.nav

import io.github.spysyweeb.beamline.api.nav.NavIntent
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.TripEvent
import io.github.spysyweeb.beamline.api.nav.TripState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * docs/architecture.md 2.3, NavigationSession.state: "Changes at most once per accepted fix or tick,
 * plus once per intent." A screen collects the state on another thread, so a fix that wrote the
 * state three times could show REROUTING with the old banner, or a phase without its busy flag.
 * An unconfined collector sees every write the session makes (the StateFlow resumes it at once).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StateChangesTest {

    private fun TestScope.watch(h: Harness): MutableList<TripState> {
        val seen = mutableListOf<TripState>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { h.session.state.collect { seen += it } }
        return seen
    }

    /** One fix [alongM] along the exit 24 route, [sideM] to its right, after the second's tick has run. */
    private fun Harness.fixOnly(alongM: Double, sideM: Double = 0.0) {
        advance(1_000)
        val p = if (sideM == 0.0) EXIT24.pointAt(alongM) else EXIT24.offset(alongM, sideM)
        gps(p.lat, p.lon, 5f, 25f, EXIT24.bearingAt(alongM)!!.toFloat())
    }

    @Test
    fun `2-3 O3 the fix that confirms off route changes the state once - REROUTING and replanning together`() = sessionTest { h ->
        h.guideExit24()
        h.drive(500.0, 600.0)
        val seen = watch(h)
        h.routingGate.gated = true                            // the answer comes later: only the fix's own change is seen
        for (i in 0 until 4) h.at(625.0 + 25.0 * i, sideM = 80.0)
        assertEquals(Phase.GUIDING, h.phase())
        val before = seen.size
        h.fixOnly(725.0, sideM = 80.0)                        // the fifth off fix
        val changes = seen.drop(before)
        assertEquals(1, changes.size, "states seen for one fix: ${changes.map { Triple(it.phase, it.replanning, it.guidance?.vehicle?.elapsedMs) }}")
        assertEquals(Phase.REROUTING, changes[0].phase)
        assertTrue(changes[0].replanning, "the request runs: busy in the same change")
        h.routingGate.release()
        h.run()
        assertEquals(Phase.GUIDING, h.phase())
    }

    @Test
    fun `2-3 A3 the fix that arrives changes the state once, and the Arrived event follows it`() = sessionTest { h ->
        h.guideExit24()
        h.drive(4100.0, m(1) + 15.0, stepM = 20.0)
        h.drive(m(2) - 30.0, m(2) + 15.0, stepM = 15.0)
        h.drive(m(3) - 30.0, m(3) + 15.0, stepM = 15.0)
        val seen = watch(h)
        val arrivedSeenWith = mutableListOf<Phase>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            h.session.events.collect { if (it is TripEvent.Arrived) arrivedSeenWith += h.session.state.value.phase }
        }
        h.fixOnly(m(4) - 40.0)
        assertEquals(Phase.GUIDING, h.phase())
        val before = seen.size
        h.fixOnly(m(4) - 20.0)
        val changes = seen.drop(before)
        assertEquals(1, changes.size, "states seen for one fix: ${changes.map { it.phase }}")
        assertEquals(Phase.ARRIVED, changes[0].phase)
        assertEquals(listOf(Phase.ARRIVED), arrivedSeenWith, "the event comes after the state that holds it")
    }

    @Test
    fun `2-3 O4 the Reroute intent changes the state once`() = sessionTest { h ->
        h.guideExit24()
        h.drive(500.0, 600.0)
        val seen = watch(h)
        h.routingGate.gated = true
        h.advance(1_000)
        val before = seen.size
        h.send(NavIntent.Reroute)
        val changes = seen.drop(before)
        assertEquals(1, changes.size, "states seen for one intent: ${changes.map { it.phase to it.replanning }}")
        assertEquals(Phase.REROUTING, changes[0].phase)
        assertTrue(changes[0].replanning)
        h.routingGate.release()
        h.run()
        assertEquals(Phase.GUIDING, h.phase())
    }
}
