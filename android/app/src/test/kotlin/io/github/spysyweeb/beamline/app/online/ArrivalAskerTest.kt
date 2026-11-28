package io.github.spysyweeb.beamline.app.online

import io.github.spysyweeb.beamline.api.nav.Motion
import io.github.spysyweeb.beamline.api.nav.Phase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Test

/** 0.1.41 (docs/online-design.md 4.8, AP3-AP7; plan AP-1): one arrival's one question, parked 15 s, seen once. */
class ArrivalAskerTest {
    private val now = 1_795_878_000_000L
    private val noHours = PlaceQuestions.PlaceFacts("n77", "n77", "Test Cafe", "fast_food", null, null, "+1-814-555-0100", "https://cafe.example", false)

    private fun input(
        phase: Phase = Phase.ARRIVED, motion: Motion? = Motion.STOPPED, simulated: Boolean = false, allowed: Boolean = true, atPlace: Boolean = true,
        facts: PlaceQuestions.PlaceFacts? = noHours, askedRecently: Boolean = false, displayed: Boolean = true,
    ) = ArrivalInput(phase, motion, simulated, allowed, atPlace, facts, emptyList(), now, askedRecently, displayed)

    @Test
    fun parkedForFifteenSecondsThenAskedOnce() {
        val a = ArrivalAsker()
        val firsts = ArrayList<String>()
        assertNull(a.update(input(), 0) { firsts += it })
        assertNull(a.update(input(), 14_000) { firsts += it }, "14 s: not parked yet")
        val s = a.update(input(), 15_000) { firsts += it }!!
        assertEquals(PlaceQuestions.Ask.ADD_HOURS, s.ask)
        assertEquals("n77", s.osm)
        assertEquals(listOf("n77"), firsts)
        assertSame(s, a.update(input(), 16_000) { firsts += it }, "the same question")
        assertEquals(listOf("n77"), firsts, "once per arrival")
        assertEquals("ArrivalInput(ARRIVED)", input().toString(), "the phase only (PV2)")
    }

    @Test
    fun movingHidesItAndOneArrivalAsksOneThing() {
        val a = ArrivalAsker()
        a.update(input(), 0)
        val s = a.update(input(), 15_000)!!
        assertSame(s, a.update(input(motion = Motion.MOVING), 16_000))
        assertSame(s, a.update(input(motion = Motion.MOVING), 18_000), "moving 2 s: a GPS jump")
        assertNull(a.update(input(motion = Motion.MOVING), 19_000), "moving 3 s: gone")
        assertNull(a.update(input(), 20_000))
        // the facts change so another question would win: this arrival keeps its own
        val other = noHours.copy(hours = "Mo-Fr 08:00-18:00", phone = null)
        assertNull(a.update(input(facts = other), 34_000), "stopped 14 s")
        assertSame(s, a.update(input(facts = other), 35_000), "parked again: the same question")
        assertSame(s, a.update(input(motion = Motion.UNKNOWN), 36_000), "GPS lost changes nothing")
        assertSame(s, a.update(input(motion = null), 37_000))
        assertSame(s, a.answer())
        assertNull(a.update(input(), 38_000), "answered: nothing more this arrival")
        assertNull(a.answer(), "answered once")
        assertNull(a.update(input(), 60_000))
    }

    @Test
    fun neverWhileGuidingSimulatedSignedOutOrAway() {
        for (p in listOf(Phase.GUIDING, Phase.REROUTING, Phase.IDLE, Phase.PLANNING)) {
            val a = ArrivalAsker()
            assertNull(a.update(input(phase = p), 0))
            assertNull(a.update(input(phase = p), 20_000), "$p")
        }
        for (i in listOf(input(simulated = true), input(allowed = false), input(atPlace = false), input(facts = null), input(askedRecently = true))) {
            val a = ArrivalAsker()
            a.update(i, 0)
            assertNull(a.update(i, 20_000), "$i")
        }
        // a later arrival after IDLE starts again
        val a = ArrivalAsker()
        a.update(input(), 0)
        a.update(input(), 15_000)!!
        a.answer()
        assertNull(a.update(input(phase = Phase.IDLE), 16_000))
        assertNull(a.update(input(), 17_000), "the next arrival: not parked yet")
        assertEquals(PlaceQuestions.Ask.ADD_HOURS, a.update(input(), 32_000)?.ask)
    }

    /** Plan AP-1: the question counts as seen only once it is on screen. */
    @Test
    fun markedOnlyOnceSeen() {
        val a = ArrivalAsker()
        val firsts = ArrayList<String>()
        a.update(input(displayed = false), 0) { firsts += it }
        val s = a.update(input(displayed = false), 15_000) { firsts += it }
        assertEquals(PlaceQuestions.Ask.ADD_HOURS, s?.ask, "chosen")
        assertEquals(emptyList<String>(), firsts, "not on screen: not counted")
        a.update(input(displayed = false), 16_000) { firsts += it }
        assertEquals(emptyList<String>(), firsts)
        a.update(input(displayed = true), 17_000) { firsts += it }
        assertEquals(listOf("n77"), firsts, "on screen: once")
        a.update(input(displayed = false), 18_000) { firsts += it }
        a.update(input(displayed = true), 19_000) { firsts += it }
        assertEquals(listOf("n77"), firsts)
    }
}
