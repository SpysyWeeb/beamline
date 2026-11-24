package io.github.spysyweeb.beamline.nav

import io.github.spysyweeb.beamline.api.nav.AnnounceStage
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.TripEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Rules B1-B8 (the banner over a drive), docs/architecture.md 3.2, on the exit 24 route. */
class BannerRulesSessionTest {

    @Test
    fun `B1 the banner is maneuver U's view, U = 1 from the first progress`() = sessionTest { h ->
        h.guideExit24()
        assertNull(h.state.guidance!!.banner, "no banner before the first progress")
        h.at(100.0)
        val b = h.state.guidance!!.banner!!
        assertEquals(1, b.view.maneuverIndex)
        assertEquals("Exit 24", b.view.primary)
        assertEquals("24", b.view.exitNumber)
        assertEquals(m(1) - 100.0, b.distanceM, 1.0)
    }

    @Test
    fun `B2 U advances on entry then exit, a forced advance catches up after a gap, never while off the route`() = sessionTest { h ->
        h.guideExit24()
        h.drive(4100.0, m(1) - 10.0, stepM = 20.0)
        assertEquals(1, h.state.u)
        h.at(m(1) + 5.0)
        assertEquals(1, h.state.u, "entered, not yet exit_m past")
        h.at(m(1) + 12.0)
        assertEquals(2, h.state.u, "exit_m past the maneuver")
        // tunnel: no fix from before the left turn until 60 m past it -> "Turn right" at once
        h.at(m(2) - 200.0)
        h.at(m(2) + 60.0, afterMs = 20_000)
        assertEquals(3, h.state.u, "catch-up loop")
        assertEquals("Turn right", h.state.guidance!!.banner!!.view.primary)
    }

    @Test
    fun `B2 a fix past the maneuver but off the route does not advance U`() = sessionTest { h ->
        h.guideExit24()
        h.drive(4000.0, 4100.0)
        h.at(m(1) + 60.0, sideM = 50.0)
        assertTrue(h.state.guidance!!.offRouteSuspected)
        assertEquals(1, h.state.u, "no forced advance while suspected")
    }

    @Test
    fun `B3 the shown distance holds against jitter, follows a large jump back, resets when U changes`() = sessionTest { h ->
        h.guideExit24()
        h.at(2000.0)
        val s0 = h.state.shown!!
        h.at(1980.0, speed = 1f)                       // 20 m back (more than the 15 m hold): d is 20 m larger
        assertEquals(s0, h.state.shown!!, 1e-9, "within distance_hold_m: s stays")
        h.at(1960.0, speed = 1f)                       // 40 m back of s: d > s + 30
        assertEquals(m(1) - 1960.0, h.state.shown!!, 1.0)
        h.drive(4100.0, m(1) + 15.0, stepM = 20.0)
        assertEquals(2, h.state.u)
        assertEquals(m(2) - h.state.vehicleAlong(), h.state.shown!!, 1.0, "reset to d at the new U")
    }

    @Test
    fun `B4 the Then chip comes with the view when the following turn is close, not before an arrival`() = sessionTest { h ->
        h.guideExit24()
        h.at(1000.0)
        val exit = h.state.guidance!!.banner!!.view
        assertEquals("Turn left", exit.following!!.primary)
        assertEquals(421.0, exit.following!!.distanceM, 3.0, "the stretch between the two, route's own estimate")
        h.at(2900.0)
        h.drive(4100.0, m(1) + 15.0, stepM = 20.0)
        assertEquals("Turn right", h.state.guidance!!.banner!!.view.following!!.primary)
        h.drive(m(2) - 30.0, m(2) + 15.0, stepM = 15.0)
        assertEquals(3, h.state.u)
        assertNull(h.state.guidance!!.banner!!.view.following, "arriving is not a following turn")
    }

    @Test
    fun `B5 the lane row shows within the window and stays until U changes`() = sessionTest(
        rules = GuidanceRules.bundled().copy(lanesWithinOtherM = 100.0),
    ) { h ->
        h.guideExit24()
        h.drive(4100.0, m(1) + 15.0, stepM = 20.0)
        h.drive(m(2) - 30.0, m(2) + 15.0, stepM = 15.0)
        assertEquals(3, h.state.u, "Turn right onto Downs Dr: lanes, approach not on a highway")
        assertTrue(h.state.guidance!!.banner!!.view.lanes != null)
        assertFalse(h.state.guidance!!.banner!!.showLanes, "344 m: outside the 100 m window of this test")
        h.drive(m(3) - 150.0, m(3) - 90.0, stepM = 30.0)
        assertTrue(h.state.guidance!!.banner!!.showLanes)
        h.at(m(3) - 140.0, speed = 1f)                 // d jumps back up by 50 m: s follows, the row stays
        assertTrue(h.state.shown!! > 100.0)
        assertTrue(h.state.guidance!!.banner!!.showLanes, "once shown it stays")
    }

    @Test
    fun `B6 a new route rebuilds the banners and U comes from its first progress`() = sessionTest { h ->
        h.guideExit24()
        h.drive(500.0, 600.0)
        // off route to the right of I-90 for five fixes: re-route (O3), new route (O6)
        for (i in 0 until 5) h.at(625.0 + 25.0 * i, sideM = 80.0)
        h.run()
        assertEquals(Phase.GUIDING, h.phase(), "${h.state.problem}")
        assertEquals(1, h.eventsOf(TripEvent.RouteReplaced::class.java).size)
        assertNull(h.state.guidance!!.banner, "no banner until the new route's first progress")
        val newRoute = h.state.guidance!!.route
        assertTrue(newRoute.geometry.first().let { kotlin.math.abs(it.lat - EXIT24.shape.first().lat) > 1e-5 || kotlin.math.abs(it.lon - EXIT24.shape.first().lon) > 1e-5 })
        // a fix on the new route
        val p = newRoute.geometry[minOf(2, newRoute.geometry.lastIndex)]
        h.advance(1_000)
        h.gps(p.lat, p.lon, 5f, 20f, null)
        assertTrue(h.state.guidance!!.banner != null)
        assertEquals(1, h.state.u)
    }

    @Test
    fun `B7 the arrival is a normal banner with a counting distance, no chip and no lanes`() = sessionTest { h ->
        h.guideExit24()
        h.drive(4100.0, m(1) + 15.0, stepM = 20.0)
        h.drive(m(2) - 30.0, m(2) + 15.0, stepM = 15.0)
        h.drive(m(3) - 30.0, m(3) + 15.0, stepM = 15.0)
        assertEquals(4, h.state.u)
        val b = h.state.guidance!!.banner!!
        assertTrue(b.view.primary.startsWith("Arrive"), b.view.primary)
        assertNull(b.view.following)
        assertNull(b.view.lanes)
        assertFalse(b.showLanes)
        val s1 = b.distanceM
        h.at(m(3) + 100.0)
        assertTrue(h.state.shown!! < s1 - 50.0, "counts down")
    }

    @Test
    fun `B8 PREPARE once at each announce distance, NOW once inside now_s`() = sessionTest { h ->
        h.guideExit24()
        h.drive(0.0, m(1) - 20.0, stepM = 25.0)
        val a = h.events.filterIsInstance<TripEvent.Announce>().filter { it.maneuverIndex == 1 }
        val highway = Data.exit24.maneuvers[0].highway
        val list = if (highway) listOf(3218.688, 804.672) else listOf(804.672, 152.4)
        assertEquals(list.map { AnnounceStage.PREPARE to it }, a.filter { it.stage == AnnounceStage.PREPARE }.map { it.stage to it.distanceM })
        val now = a.filter { it.stage == AnnounceStage.NOW }
        assertEquals(1, now.size, "NOW once")
        assertTrue(now.single().distanceM / 25.0 < 6.0, "time to the maneuver below announce.now_s at 25 m/s")
    }
}
