package io.github.spysyweeb.beamline.search.hours

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Rule S10 (batch B): which OSM opening_hours texts are read, and the status at the boundaries. */
class OpeningHoursTest {
    // 2026-11-16 is a Monday
    private fun at(day: Int, h: Int, m: Int = 0) = LocalDateTime.of(2026, 11, day, h, m)
    private fun read(text: String): OpeningHours = OpeningHours.parse(text) ?: throw AssertionError("not read: $text")

    @Test
    fun twentyFourSevenIsOpenWithNoChange() {
        val s = read("24/7").status(at(18, 3))
        assertEquals(HoursStatus(OpenState.OPEN, null), s)
    }

    @Test
    fun weekdaysAndSaturday() {
        val h = read("Mo-Fr 08:00-17:00; Sa 09:00-12:00")
        assertEquals(HoursStatus(OpenState.OPEN, at(18, 17)), h.status(at(18, 10)))          // Wednesday morning
        assertEquals(HoursStatus(OpenState.CLOSED, at(19, 8)), h.status(at(18, 20)))         // Wednesday night: opens Thursday 8
        assertEquals(HoursStatus(OpenState.CLOSED, at(21, 9)), h.status(at(20, 18)))         // Friday evening: Saturday 9
        assertEquals(HoursStatus(OpenState.CLOSED, at(23, 8)), h.status(at(21, 13)))         // Saturday afternoon: Monday 8
        assertEquals(HoursStatus(OpenState.CLOSED, at(23, 8)), h.status(at(22, 12)))         // Sunday
        val week = h.week(at(18, 10).toLocalDate())
        assertEquals(DayOfWeek.MONDAY, week.first().day)
        assertEquals(listOf(480 until 1020), week[0].spans)
        assertEquals(listOf(540 until 720), week[5].spans)
        assertTrue(week[6].spans.isEmpty())
        assertFalse(h.holidaysIgnored)
    }

    @Test
    fun boundariesOfTheLastHour() {
        val h = read("Mo-Fr 08:00-17:00")
        assertEquals(OpenState.OPEN, h.status(at(18, 15, 59)).state)
        assertEquals(HoursStatus(OpenState.CLOSING_SOON, at(18, 17)), h.status(at(18, 16)))   // exactly one hour before closing
        assertEquals(OpenState.CLOSING_SOON, h.status(at(18, 16, 59)).state)
        assertEquals(HoursStatus(OpenState.CLOSED, at(19, 8)), h.status(at(18, 17)))         // at closing
        assertEquals(HoursStatus(OpenState.OPEN, at(18, 17)), h.status(at(18, 8)))           // at opening
        assertEquals(HoursStatus(OpenState.CLOSED, at(18, 8)), h.status(at(18, 7, 59)))
    }

    @Test
    fun pastMidnight() {
        val h = read("Fr-Sa 18:00-02:00")
        assertEquals(HoursStatus(OpenState.OPEN, at(21, 2)), h.status(at(20, 23)))           // Friday 23:00, until Saturday 2:00
        assertEquals(HoursStatus(OpenState.CLOSING_SOON, at(21, 2)), h.status(at(21, 1, 30)))  // Saturday 1:30 (Friday's night)
        assertEquals(HoursStatus(OpenState.CLOSED, at(21, 18)), h.status(at(21, 2)))
        assertEquals(HoursStatus(OpenState.OPEN, at(22, 2)), h.status(at(22, 0, 30)))        // Sunday 0:30 is Saturday's night
        assertEquals(HoursStatus(OpenState.CLOSED, at(27, 18)), h.status(at(22, 2, 0)))      // then the next Friday
        assertEquals(listOf(1080 until 1560), h.week(at(18, 1).toLocalDate())[4].spans)       // the week view keeps "until 2 AM"
    }

    @Test
    fun offDaysAndOverrides() {
        val h = read("Mo-Su 07:00-22:00; Su off")
        assertEquals(HoursStatus(OpenState.CLOSED, at(23, 7)), h.status(at(22, 12)))
        val c = read("off")
        assertEquals(HoursStatus(OpenState.CLOSED, null), c.status(at(18, 12)))
        val lunch = read("Mo-Fr 08:00-17:00; Mo-Fr 12:00-13:00 off")
        assertEquals(HoursStatus(OpenState.CLOSED, at(18, 13)), lunch.status(at(18, 12, 30)))
        val additive = read("Mo-Fr 08:00-12:00, Sa 10:00-14:00")
        assertEquals(OpenState.OPEN, additive.status(at(21, 11)).state)
        assertEquals(OpenState.OPEN, additive.status(at(18, 9)).state)
    }

    @Test
    fun publicHolidayPartsAreLeftOutAndSaid() {
        val h = read("Mo-Fr 08:00-17:00; PH off")
        assertTrue(h.holidaysIgnored)
        assertEquals(OpenState.OPEN, h.status(at(18, 10)).state)
        val both = read("Mo-Fr,PH 10:00-14:00")
        assertTrue(both.holidaysIgnored)
        assertEquals(OpenState.OPEN, both.status(at(18, 11)).state)
        assertEquals(OpenState.CLOSED, both.status(at(21, 11)).state)
        assertNull(OpeningHours.parse("PH off"))   // nothing left to evaluate
    }

    @Test
    fun whatCannotBeReadGivesNothing() {
        for (t in listOf(
            "", "   ", "open sometimes", "Mo-Fr 08:00-", "Apr-Oct Mo-Su 10:00-18:00", "2026 Mo-Fr 08:00-17:00",
            "Mo-Fr sunrise-sunset", "Mo-Fr 18:00+", "Su[1] 10:00-12:00", "\"by appointment\"", "Mo-Fr 08:00-17:00 unknown",
            "Mo-Fr 08:00-17:00 || \"by appointment\"", "week 1-53/2 Mo 10:00-12:00", "Mo-Fr 10:00-16:00/90",
            // good, then bad (batch C2, the batch B review): one unreadable rule spoils the whole text
            "Mo-Fr 08:00-17:00; garbage", "Mo-Fr 08:00-17:00; Sa 10:00-", "Mo-Fr 08:00-17:00, Dec 25 off",
            "Mo-Fr 08:00-17:00; Sa sunrise-sunset", "24/7; Su 18:00+",
        )) {
            assertNull(OpeningHours.parse(t), t)
        }
        assertNull(OpeningHours.parse(null))
    }

    @Test
    fun theTimeZoneIsThePhones() {
        val h = read("Mo-Fr 08:00-17:00")
        val instant = Instant.parse("2026-11-18T21:30:00Z")   // Wednesday
        // 16:30 in Erie (EST, UTC-5): closing soon; 13:30 in Los Angeles: open; 22:30 in Berlin: closed
        assertEquals(OpenState.CLOSING_SOON, h.status(instant, ZoneId.of("America/New_York")).state)
        assertEquals(OpenState.OPEN, h.status(instant, ZoneId.of("America/Los_Angeles")).state)
        assertEquals(OpenState.CLOSED, h.status(instant, ZoneId.of("Europe/Berlin")).state)
    }
}
