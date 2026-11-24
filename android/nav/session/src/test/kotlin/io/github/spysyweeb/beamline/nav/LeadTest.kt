package io.github.spysyweeb.beamline.nav

import io.github.spysyweeb.beamline.api.nav.GpsState
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.routing.model.ManeuverType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Rule G8 (docs/architecture.md 3.1; the owner, 2026-11-24: "the location on screen is a little
 * behind where I'm at"): the arrow is drawn where the car is when its glide ends, not where the fix
 * was taken. These tests use the bundled rules (the other session tests turn the lead off).
 */
class LeadTest {
    /** The bundled rules with extra_s 0, so the sums below are the fix's age and glide alone (extra_s has its own test). */
    private val withLead = GuidanceRules.bundled().let { it.copy(lead = it.lead.copy(extraS = 0.0)) }

    @Test
    fun `G8 the arrow leads the fix by its speed times the fix's age and its glide`() = sessionTest(rules = withLead) { h ->
        h.guideExit24()
        h.at(1000.0)
        assertEquals(1000.0, h.state.vehicleAlong(), 1.0, "the first fix: no glide to make up, no age")
        h.at(1025.0)
        assertEquals(1050.0, h.state.vehicleAlong(), 1.0, "1 s after the last fix at 25 m/s: the arrow glides 1 s, so 25 m ahead")
        assertEquals(m(1) - 1050.0, h.state.shown!!, 1.0, "the banner's distance is the arrow's")
        h.at(1050.0, ageMs = 500)
        assertEquals(1075.0, h.state.vehicleAlong(), 1.0, "0.5 s old and 0.5 s of glide: 25 m ahead")
    }

    @Test
    fun `G8 extra_s adds the receiver's own delay`() = sessionTest(rules = GuidanceRules.bundled()) { h ->
        val extra = GuidanceRules.bundled().lead.extraS
        assertTrue(extra > 0.0, "tuned after the owner's drive (0.1.25)")
        h.guideExit24()
        h.at(1000.0)
        h.at(1025.0)
        assertEquals(1025.0 + 25.0 * (1.0 + extra), h.state.vehicleAlong(), 1.0, "speed x (glide + extra_s)")
    }

    @Test
    fun `G8 no lead when slow or from a poor fix`() = sessionTest(rules = withLead) { h ->
        h.guideExit24()
        h.at(1000.0, speed = 2f)
        h.at(1002.0, speed = 2f)
        assertEquals(1002.0, h.state.vehicleAlong(), 1.0, "2 m/s is below lead.min_speed_mps")
        h.at(1027.0, accuracy = 40f)
        assertEquals(1027.0, h.state.vehicleAlong(), 1.0, "40 m is poorer than lead.max_accuracy_m (still a usable fix)")
        h.at(1052.0, speed = null, accuracy = 40f)
        assertEquals(1052.0, h.state.vehicleAlong(), 1.0)
    }

    @Test
    fun `G8 the lead passes an exit but stops at a turn the car slows for`() = sessionTest(rules = withLead) { h ->
        val types = Data.exit24.maneuvers.map { it.type }
        assertTrue(types[1] !in withLead.lead.stopAt && types[2] == ManeuverType.LEFT, "exit 24 is not a stop, the left onto Peach St is: $types")
        h.guideExit24()
        h.drive(m(1) - 100.0, m(1) - 50.0)
        h.at(m(1) - 10.0)
        assertEquals(m(1) + 15.0, h.state.vehicleAlong(), 1.0, "25 m ahead, past the exit's point")
        h.drive(m(1) + 40.0, m(2) - 60.0, stepM = 20.0)
        h.at(m(2) - 10.0)
        assertEquals(m(2), h.state.vehicleAlong(), 1.0, "held at the turn's point, not drawn turning early")
        h.at(m(2) + 15.0)
        assertTrue(h.state.vehicleAlong() > m(2) + 15.0, "past the turn the lead goes on")
    }

    @Test
    fun `G8 an arrival waits for the car, not for the arrow`() = sessionTest(rules = withLead) { h ->
        h.guideExit24()
        h.drive(4100.0, m(1) + 15.0, stepM = 20.0)
        h.drive(m(2) - 30.0, m(2) + 15.0, stepM = 15.0)
        h.drive(m(3) - 30.0, m(3) + 15.0, stepM = 15.0)
        assertEquals(4, h.state.u)
        h.at(m(4) - 40.0)
        assertTrue(h.state.vehicleAlong() > m(4) - 30.0, "the arrow is within 30 m of the end")
        assertEquals(Phase.GUIDING, h.phase(), "but the car is 40 m away")
        h.at(m(4) - 25.0)
        assertEquals(Phase.ARRIVED, h.phase())
    }

    @Test
    fun `G8 dead reckoning in WEAK keeps the lead, so the arrow neither stalls nor jumps`() = sessionTest(rules = withLead) { h ->
        h.guideExit24()
        h.at(1000.0)
        h.at(1025.0)
        assertEquals(1050.0, h.state.vehicleAlong(), 1.0)
        h.advance(3_000)
        assertEquals(GpsState.WEAK, h.state.gps)
        val along = h.state.vehicleAlong()
        assertTrue(along >= 1100.0 && along <= 1161.0, "moved on from 1,050: the car's 1,100 at 3 s plus a lead of at most 60 m: $along")
    }

    @Test
    fun `G8 one poor fix while moving holds the arrow instead of pulling it back`() = sessionTest(rules = withLead) { h ->
        h.guideExit24()
        h.at(1000.0)
        h.at(1025.0)
        assertEquals(1050.0, h.state.vehicleAlong(), 1.0)
        h.at(1045.0, accuracy = 40f)
        assertEquals(1050.0, h.state.vehicleAlong(), 1.0, "too poor for a lead, and the car is behind the arrow: held, not pulled back")
        h.at(1070.0)
        assertEquals(1095.0, h.state.vehicleAlong(), 1.0, "the next good fix leads again")
    }

    @Test
    fun `G8 when the car slows to a stop the arrow goes back to it, then G6 holds it against jitter`() = sessionTest(rules = withLead) { h ->
        h.guideExit24()
        h.at(1000.0)
        h.at(1025.0)
        h.at(1040.0, speed = 5f)
        assertEquals(1050.0, h.state.vehicleAlong(), 1.0, "braking: the lead shrank behind the arrow, still moving: held")
        h.at(1042.0, speed = 1f)
        assertEquals(1042.0, h.state.vehicleAlong(), 1.0, "about to stop: the arrow goes back to the car")
        h.at(1041.0, speed = 0f)
        assertEquals(1042.0, h.state.vehicleAlong(), 1.0, "standing, 1 m of jitter back: G6 holds")
    }
}
