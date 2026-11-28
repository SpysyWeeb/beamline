package io.github.spysyweeb.beamline.app.online

import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.nav.Motion
import io.github.spysyweeb.beamline.api.nav.Phase
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** 0.1.41 (docs/online-design.md 4.8, AP3-AP7; plan AP-1): one arrival's one question, parked 15 s, seen once. */
class ArrivalAskerTest {
    private val now = 1_795_878_000_000L
    private val noHours = PlaceQuestions.PlaceFacts("n77", "n77", "Test Cafe", "fast_food", null, null, "+1-814-555-0100", "https://cafe.example", false)

    private fun input(
        phase: Phase = Phase.ARRIVED, motion: Motion? = Motion.STOPPED, simulated: Boolean = false, allowed: Boolean = true, atPlace: Boolean = true,
        facts: PlaceQuestions.PlaceFacts? = noHours, askedRecently: Boolean = false, displayed: Boolean = true, atStop: Boolean = true,
    ) = ArrivalInput(phase, motion, simulated, allowed, atPlace, facts, emptyList(), now, askedRecently, displayed, atStop)

    /**
     * Item 56's review (phone safety): A4 arrives up to 50 m before the route's end, so a car can be ARRIVED while it
     * waits at a red light short of it. Standing there is not parked: no question until it stands at the stop.
     */
    @Test
    fun notAskedWhileStandingOnTheRoadShortOfTheEnd() {
        val a = ArrivalAsker()
        for (t in 0..30_000L step 1_000L) assertNull(a.update(input(atStop = false), t), "a red light 40 m short, $t ms")
        assertNull(a.update(input(), 31_000), "parked at the end: counted from now")
        assertNull(a.update(input(), 45_000), "14 s")
        assertEquals(PlaceQuestions.Ask.ADD_HOURS, a.update(input(), 46_000)?.ask, "15 s at the stop")
        assertTrue(a.update(input(atStop = false), 47_000) != null, "once shown, a GPS jump off the spot keeps it")
    }

    /** Line: 200 m due east along a parallel at 42.05 N; its end at the east. */
    private val line = (0..8).map { GeoPoint(42.05, -80.08 + it * 25.0 / (111_195.0 * Math.cos(Math.toRadians(42.05)))) }
    private fun along(m: Double, northM: Double = 0.0) =
        GeoPoint(42.05 + northM / 111_195.0, -80.08 + m / (111_195.0 * Math.cos(Math.toRadians(42.05))))

    @Test
    fun atTheStopIsNearTheRouteEndOrOffTheRouteLine() {
        assertFalse(PlaceQuestions.atStop(along(160.0), line), "on the line 40 m before the end")
        assertFalse(PlaceQuestions.atStop(along(160.0, northM = 10.0), line), "10 m beside the line: a wide road")
        assertTrue(PlaceQuestions.atStop(along(175.0), line), "25 m before the end")
        assertTrue(PlaceQuestions.atStop(along(210.0, northM = 5.0), line), "just past the end")
        assertTrue(PlaceQuestions.atStop(along(140.0, northM = 20.0), line), "a lot 20 m beside the road")
        assertFalse(PlaceQuestions.atStop(null, line), "no position")
        assertFalse(PlaceQuestions.atStop(along(175.0), emptyList()), "no route")
    }

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
