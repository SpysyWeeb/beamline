package io.github.spysyweeb.beamline.app.online

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** Hours as people write them, read into OSM's text (docs/next-round.md item 34). */
class HoursTextTest {
    private fun osm(text: String): String? = HoursText.parse(text)?.draft?.text()
    private fun guessed(text: String): Boolean? = HoursText.parse(text)?.guessed

    @Test
    fun whatABusinessPublishes() {
        // the owner's first real case: an operator's app (access hours, then office hours)
        assertEquals("Mo-Su 06:00-22:00", osm("Daily 6:00 am - 10:00 pm"))
        assertEquals("Mo-Fr 09:30-18:00; Sa 09:00-17:30", osm("Mon - Fri 9:30 am - 6:00 pm\nSat 9:00 am - 5:30 pm\nSun Closed"))
        assertEquals("Mo-Fr 09:30-18:00; Sa 09:00-17:30", osm("Mon–Fri 9:30am–6pm, Sat 9–5:30, Sun closed"))
        assertEquals("Mo-Fr 08:00-17:00; Sa 09:00-13:00", osm("Weekdays 8 to 5; Sat 9-1"))
        assertEquals("Mo-Fr 08:00-17:00", osm("M-F 8am-5pm"))
        assertEquals("Mo-Th 11:00-22:00; Fr,Sa 11:00-23:00; Su 12:00-21:00",
            osm("Monday through Thursday: 11 a.m. to 10 p.m.\nFriday & Saturday: 11 a.m. to 11 p.m.\nSunday: noon to 9 p.m."))
        assertEquals("Mo,We,Fr 10:00-16:00", osm("Mon, Wed and Fri 10am-4pm"))
        assertEquals("Mo-Fr 09:00-12:00,13:00-17:00", osm("Mon-Fri 9-12, 1-5"))
        assertEquals("Sa,Su 10:00-14:00", osm("Weekends 10 AM - 2 PM"))
        assertEquals("Mo-Fr 08:00-17:00", osm("Weekdays - 8am to 5pm"))
    }

    @Test
    fun aroundTheClockAndAfterMidnight() {
        assertEquals("24/7", osm("Open 24 hours"))
        assertEquals("24/7", osm("open 24 hours a day"))
        assertEquals("24/7", osm("24/7"))
        assertEquals("Fr,Sa 17:00-02:00", osm("Fri-Sat 5pm-2am"))
        assertEquals("Mo-Su 21:00-24:00", osm("Daily 9pm - midnight"))
        // a range of days that wraps the week: Friday, Saturday, Sunday, Monday
        assertEquals(HoursDraft.of("Mo,Fr-Su 10:00-18:00")!!.text(), osm("Fri-Mon 10am to 6pm"))
        assertEquals(HoursDraft.of("Mo-Fr 00:00-24:00; Sa 08:00-20:00")!!.text(), osm("Mon-Fri 24 hours, Sat 8am-8pm"))
    }

    @Test
    fun amAndPmThatAreNotWritten() {
        assertEquals("Mo-Su 09:00-18:00", osm("9-6pm"))
        assertEquals(false, guessed("9-6pm"))
        assertEquals("Mo-Su 13:00-17:00", osm("1-5pm"))
        assertEquals("Mo-Su 09:00-17:00", osm("9am-5"))
        assertEquals("Mo-Su 22:00-02:00", osm("10-2am"))
        assertEquals("Mo-Su 18:00-02:00", osm("6pm-2"))
        // neither says: worked out, and flagged so the form asks the user to check
        assertEquals("Mo-Su 09:00-17:30", osm("9-5:30"))
        assertEquals(true, guessed("9-5:30"))
        assertEquals("Mo-Su 13:00-17:00", osm("1-5"))
        assertEquals("Mo-Su 10:00-22:00", osm("10-10"))
        assertEquals("Mo-Su 08:00-12:00", osm("8-12"))
        // a 24-hour clock says it by itself
        assertEquals("Mo-Fr 09:30-18:00", osm("Mon-Fri 09:30-18:00"))
        assertEquals("Mo-Fr 09:30-18:00", osm("Mon-Fri 0930-1800"))
        assertEquals(false, guessed("Mon-Fri 0930-1800"))
    }

    @Test
    fun aHalfOfTheDayItChoseIsSaid() {
        // one time says AM or PM and the other fits both halves: chosen, and the form asks (the review of 2026-11-21)
        assertEquals("Mo-Su 06:00-21:00", osm("6am-9"))
        assertEquals(true, guessed("6am-9"))
        assertEquals("Mo-Su 08:00-21:00", osm("8-9pm"))
        assertEquals(true, guessed("8-9pm"))
        assertEquals(true, guessed("1-5pm"))
        assertEquals("Mo-Su 12:00-20:00", osm("12-8pm"))
        // only one reading fits: nothing to ask
        assertEquals(false, guessed("9am-5"))
        assertEquals(false, guessed("6pm-9"))
        assertEquals("Mo-Su 18:00-21:00", osm("6pm-9"))
        assertEquals("Mo-Su 06:00-11:00", osm("6-11am"))
    }

    @Test
    fun anOrderItDoesNotReadGivesNothing() {
        // each of these once gave wrong hours without a word (the review of 2026-11-21)
        for (t in listOf(
            "9am-5pm Mon-Fri",                         // the days after their times
            "24 hours Mon-Fri", "Open 24 hours Mon-Fri, closed Sat-Sun",
            "Mon-Fri 9-5, Wed closed", "Mon-Fri 9-5, closed Wed",   // a day said twice
            "Mon-Fri 9-5, Fri 9-3",
            "9am-5pm, Sat 10-2",                       // times for every day, then a day
            "Mon-Fri 9-5, Sat",                        // days with no times
            "Mon Tue-Thu 9-5 Fri",
            "9pm-5pm", "Mon-Fri 10pm-6pm",             // from an evening to the next afternoon
        )) assertNull(HoursText.parse(t), t)
        // and what stays readable beside them
        assertEquals("Mo-Fr 09:00-17:00; Sa 10:00-14:00", osm("Mon-Fri 9-5 Sat 10-2"))
        assertEquals("Mo,Tu 09:00-17:00", osm("Mon 9-5, Tue 9-5"))
        assertEquals("Mo-Fr 22:00-06:00", osm("Mon-Fri 10pm-6am"))
        assertEquals("Tu-Th,Sa 10:00-18:00", osm("Tue-Thu & Sat 10-6"))
    }

    @Test
    fun openStreetMapsOwnTextIsReadAsItIs() {
        assertEquals("Mo-Fr 09:00-17:00; Sa 09:00-12:00", osm("Mo-Fr 09:00-17:00; Sa 09:00-12:00"))
        assertEquals("Mo-Fr 08:00-18:00; PH off", osm("Mo-Fr 08:00-18:00; PH off"))
    }

    @Test
    fun whatItDoesNotUnderstandGivesNothing() {
        for (t in listOf("", "   ", "call for hours", "by appointment", "Mon-Fri", "9", "Mon 9-", "Mon-Fri 9-5 except holidays",
            "Sun closed", "closed", "Mon 25-30", "Mon 9:75-5", "- 9 to 5", "Sun closed, 9-5", "x".repeat(500))) {
            assertNull(HoursText.parse(t), t)
        }
    }
}
