package io.github.spysyweeb.beamline.app.online

import io.github.spysyweeb.beamline.search.hours.OpeningHours
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Batch O3 (docs/next-round.md item 24): the friendlier editor's hours: day groups (grouping of existing
 * hours, writing), split days, Open 24 hours, closing after midnight, the public holidays line; every
 * written text read back with the app's hours parser.
 */
class HoursDraftTest {
    private fun h(hm: String) = hm.substringBefore(':').toInt() * 60 + hm.substringAfter(':').toInt()
    private fun r(a: String, b: String) = TimeRange(h(a), h(b))

    @Test
    fun existingHoursOpenGroupedTheSameWay() {
        val d = HoursDraft.of("Mo-Th 15:00-22:00")!!
        assertEquals(listOf(DayGroup(listOf(0, 1, 2, 3), listOf(r("15:00", "22:00")))), d.groups)
        assertEquals(listOf(4, 5, 6), d.closedDays())
        val two = HoursDraft.of("Mo-Fr 08:00-18:00; Sa 08:00-13:00")!!
        assertEquals(listOf(listOf(0, 1, 2, 3, 4), listOf(5)), two.groups.map { it.days })
    }

    @Test
    fun writesGroupsSplitDaysAndOpen24() {
        val d = HoursDraft(listOf(
            DayGroup(listOf(0, 1, 2, 3, 4), listOf(r("11:00", "14:00"), r("17:00", "22:00"))),
            DayGroup(listOf(5, 6), emptyList(), allDay = true),
        ))
        assertEquals("Mo-Fr 11:00-14:00,17:00-22:00; Sa,Su 00:00-24:00", d.text())
        assertTrue(d.check())
        assertEquals("24/7", HoursDraft(listOf(DayGroup((0..6).toList(), emptyList(), allDay = true))).text())
        assertEquals("", HoursDraft.EMPTY.text())
    }

    @Test
    fun closingAfterMidnightIsTheNextDay() {
        val g = DayGroup(listOf(4, 5), listOf(r("18:00", "02:00")))
        assertTrue(g.ranges[0].nextDay)
        assertFalse(r("18:00", "00:00").let { TimeRange(it.from, 1440) }.nextDay, "closing at midnight is not the next day")
        val d = HoursDraft(listOf(g))
        assertEquals("Fr,Sa 18:00-02:00", d.text())
        assertTrue(d.check())
        assertEquals(d.groups, HoursDraft.of(d.text())!!.groups)
        // Sunday into Monday
        val sun = HoursDraft(listOf(DayGroup(listOf(6), listOf(r("20:00", "03:00")))))
        assertEquals("Su 20:00-03:00", sun.text())
        assertTrue(sun.check())
    }

    @Test
    fun publicHolidaysLine() {
        val base = listOf(DayGroup(listOf(0, 1, 2, 3, 4), listOf(r("08:00", "17:00"))))
        assertEquals("Mo-Fr 08:00-17:00", HoursDraft(base, PhMode.UNSURE).text())
        assertEquals("Mo-Fr 08:00-17:00", HoursDraft(base, PhMode.SAME).text(), "same as usual needs no holiday rule")
        assertEquals("Mo-Fr 08:00-17:00; PH off", HoursDraft(base, PhMode.CLOSED).text())
        assertEquals("Mo-Fr 08:00-17:00; PH 10:00-14:00", HoursDraft(base, PhMode.CUSTOM, listOf(r("10:00", "14:00"))).text())
        for (ph in PhMode.entries) {
            val d = HoursDraft(base, ph, if (ph == PhMode.CUSTOM) listOf(r("10:00", "14:00")) else emptyList())
            assertTrue(d.check(), "check $ph")
            assertNotNull(OpeningHours.parse(d.text()), "the parser reads ${d.text()}")
        }
        // existing holiday rules are kept and shown
        val read = HoursDraft.of("Mo-Sa 09:00-18:00; Su,PH off")!!
        assertEquals(PhMode.CLOSED, read.ph)
        assertEquals(listOf(listOf(0, 1, 2, 3, 4, 5)), read.groups.map { it.days })
        assertEquals("Mo-Sa 09:00-18:00; PH off", read.text())
        val custom = HoursDraft.of("Mo-Fr 08:00-17:00; PH 10:00-14:00")!!
        assertEquals(PhMode.CUSTOM, custom.ph)
        assertEquals(listOf(r("10:00", "14:00")), custom.phRanges)
        assertEquals(PhMode.CLOSED, HoursDraft.of("Mo-Fr 08:00-17:00; PH closed")!!.ph)
    }

    @Test
    fun roundTripsWithTheParser() {
        val texts = listOf(
            "Mo-Th 15:00-22:00", "Mo-Fr 08:00-18:00; Sa 08:00-13:00", "24/7", "Tu-Sa 10:00-14:00,16:00-20:00",
            "Mo-Th 11:00-22:00; Fr,Sa 11:00-02:00; Su 12:00-21:00", "Sa,Su 00:00-24:00", "Mo-Fr 09:00-17:00; PH off",
            "Mo-Su 06:00-22:00; PH 08:00-12:00", "Su 20:00-03:00",
        )
        for (t in texts) {
            val d = HoursDraft.of(t)
            assertNotNull(d, t)
            assertTrue(d!!.check(), "check $t -> ${d.text()}")
            assertEquals(d.week(), HoursDraft.of(d.text())!!.week(), "week of $t")
        }
    }

    @Test
    fun whatTheEditorCannotExpressStaysReadOnly() {
        assertNull(HoursDraft.of("Mo-Fr 08:00-17:00 \"by appointment\""))
        assertNull(HoursDraft.of("Jan-Mar Mo-Fr 08:00-17:00"))
        assertNull(HoursDraft.of("Mo-Fr 08:00-17:00; SH off"))
        assertNull(HoursDraft.of("Mo-Fr 08:00-17:00; PH -1 day off"))
        assertNull(HoursDraft.of("Mo-Fr sunrise-sunset"))
        assertEquals(HoursDraft.EMPTY, HoursDraft.of(null))
    }

    @Test
    fun theFormKeepsReadOnlyHoursAndUndoes() {
        val f = FormFields(FormValues("Cafe", "Mo-Fr 08:00-17:00 \"call ahead\"", null, null))
        assertEquals("Mo-Fr 08:00-17:00 \"call ahead\"", f.hoursReadOnly)
        assertEquals("Mo-Fr 08:00-17:00 \"call ahead\"", f.hoursText(), "kept as it was")
        val g = FormFields(FormValues("Cafe", "Mo-Fr 8:00-17:00", "+1 814 555 0100", "example.org"))
        assertFalse(g.hoursChanged, "opening the form changes nothing")
        assertEquals("Mo-Fr 8:00-17:00", g.hoursText(), "an unchanged text is never rewritten")
        g.hours = g.hours.copy(ph = PhMode.CLOSED)
        assertTrue(g.hoursChanged)
        assertEquals("Mo-Fr 08:00-17:00; PH off", g.hoursText())
        g.undoHours()
        assertFalse(g.hoursChanged)
        g.name = "Cafe Two"
        assertTrue(g.nameChanged && g.anyChanged)
        g.undoName()
        assertFalse(g.anyChanged)
        assertEquals("https://example.org", g.websiteText())
    }

    @Test
    fun phoneAndWebsiteChecks() {
        assertNull(Checks.phone("814-555-0123"))
        assertNull(Checks.phone("+1 814 555 0123; +1 814 555 0199"))
        assertNull(Checks.phone(""))
        assertNotNull(Checks.phone("call us"))
        assertNotNull(Checks.phone("555"))
        assertNull(Checks.website("example.com"))
        assertNull(Checks.website("https://www.example.com/menu?x=1"))
        assertNotNull(Checks.website("not a site"))
        assertNotNull(Checks.website("example"))
    }
}
