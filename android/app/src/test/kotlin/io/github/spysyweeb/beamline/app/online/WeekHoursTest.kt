package io.github.spysyweeb.beamline.app.online

import io.github.spysyweeb.beamline.search.hours.OpeningHours
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Batch O2 (docs/online-design.md 4.2): the week editor's writer, checked with the app's hours parser. */
class WeekHoursTest {
    private fun day(vararg spans: Pair<String, String>) = spans.map { WeekHours.spanOf(it.first, it.second)!! }

    @Test
    fun writesTheUsualShortForm() {
        val w = WeekHours(List(5) { day("08:00" to "18:00") } + listOf(day("08:00" to "13:00"), emptyList()))
        assertEquals("Mo-Fr 08:00-18:00; Sa 08:00-13:00", w.text())
        assertEquals("24/7", WeekHours(List(7) { day("00:00" to "24:00") }).text())
        assertEquals("", WeekHours.CLOSED.text())
        val split = WeekHours(listOf(day("11:00" to "14:00", "17:00" to "22:00"), emptyList(), day("11:00" to "14:00", "17:00" to "22:00"),
            emptyList(), day("11:00" to "14:00", "17:00" to "22:00"), day("12:00" to "02:00"), day("12:00" to "02:00")))
        assertEquals("Mo,We,Fr 11:00-14:00,17:00-22:00; Sa,Su 12:00-02:00", split.text())
    }

    @Test
    fun everyWrittenTextReadsBackAsTheSameWeek() {
        val texts = listOf(
            "Mo-Fr 08:00-18:00; Sa 08:00-13:00", "24/7", "Mo-Su 06:00-22:00", "Tu-Sa 10:00-14:00,16:00-20:00",
            "Mo-Th 11:00-22:00; Fr,Sa 11:00-02:00; Su 12:00-21:00", "Mo-Fr 07:00-15:30", "Sa,Su 00:00-24:00",
            "Mo-Fr 09:00-17:00; We off", "08:00-20:00",
        )
        for (t in texts) {
            val w = WeekHours.of(t)
            assertNotNull(w, t)
            val out = w!!.text()
            assertNotNull(OpeningHours.parse(out), "the parser reads $out")
            assertEquals(w, WeekHours.readBack(out), "round trip of $t -> $out")
        }
        assertEquals("Mo,Tu,Th,Fr 09:00-17:00", WeekHours.of("Mo-Fr 09:00-17:00; We off")!!.text())
        assertEquals("Mo-Su 08:00-20:00", WeekHours.of("08:00-20:00")!!.text())
    }

    @Test
    fun overnightAndSundayIntoMonday() {
        // O2 review: a closing after midnight, and Sunday's hours running into Monday morning
        val late = WeekHours(List(4) { emptyList<IntRange>() } + listOf(day("18:00" to "02:00"), day("18:00" to "02:00"), emptyList()))
        assertEquals("Fr,Sa 18:00-02:00", late.text())
        assertEquals(late, WeekHours.readBack(late.text()))
        val sun = WeekHours(List(6) { day("09:00" to "17:00") } + listOf(day("20:00" to "03:00")))
        assertEquals("Mo-Sa 09:00-17:00; Su 20:00-03:00", sun.text())
        assertEquals(sun, WeekHours.readBack(sun.text()), "Sunday keeps its own span; Monday is not changed by it")
        assertEquals(listOf(9 * 60 until 17 * 60), WeekHours.readBack(sun.text())!!.days[0])
        assertEquals(sun, WeekHours.of("Mo-Sa 09:00-17:00; Su 20:00-03:00"))
    }

    @Test
    fun whatTheFormCannotExpressIsLeftReadOnly() {
        assertNull(WeekHours.of("Mo-Fr 08:00-17:00; PH off"), "holidays")
        assertNull(WeekHours.of("Mo-Fr 08:00-17:00 \"by appointment\""), "a comment")
        assertNull(WeekHours.of("Jan-Mar Mo-Fr 08:00-17:00"), "months")
        assertNull(WeekHours.of("Mo-Fr sunrise-sunset"), "variable times")
        assertNull(WeekHours.of("Mo-Fr 18:00+"), "open end")
        assertNull(WeekHours.of("not hours"))
        assertEquals(WeekHours.CLOSED, WeekHours.of(null))
    }

    @Test
    fun clockTexts() {
        assertEquals(480, WeekHours.parseClock("8:00"))
        assertEquals(480, WeekHours.parseClock("08:00"))
        assertEquals(510, WeekHours.parseClock("830"))
        assertEquals(1440, WeekHours.parseClock("24:00"))
        assertNull(WeekHours.parseClock("25:00"))
        assertNull(WeekHours.parseClock("8:5"))
        assertNull(WeekHours.parseClock("x"))
        assertEquals("18:00-02:00", WeekHours.span(WeekHours.spanOf("18:00", "2:00")!!))
        assertNull(WeekHours.spanOf("09:00", "09:00"))
    }
}
