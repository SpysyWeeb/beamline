package io.github.spysyweeb.beamline.units

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.File
import java.time.ZoneId
import java.time.ZonedDateTime

class UnitFormatterTest {

    private val rules = UnitRules.bundled()
    private val miles = UnitFormatter(rules, UnitSystem.IMPERIAL)
    private val km = UnitFormatter(rules, UnitSystem.METRIC)
    private val newYork = ZoneId.of("America/New_York")

    /** The text's value in metres ("0.3 mi" -> 482.8). */
    private fun metres(text: String): Double {
        val (n, unit) = text.split(" ")
        val per = mapOf("ft" to 0.3048, "mi" to 1609.344, "m" to 1.0, "km" to 1000.0).getValue(unit)
        return n.toDouble() * per
    }

    @Test
    fun `the exit 24 trip reads as on the sketch`() {
        // report 09: 4,173 m to exit 24, then 422 m, 356 m and 391 m; 5,341.6 m in all
        assertEquals("2.6 mi", miles.distance(4173.0))
        assertEquals("3.3 mi", miles.distance(5341.6))
        assertEquals("0.3 mi", miles.distance(421.0))
        assertEquals("In 0.3 mi", miles.distanceIn(421.0))
        assertEquals("In 0.2 mi", miles.distanceIn(356.0))
        assertEquals("0.2 mi", miles.distance(391.8))
        assertEquals("0.5 mi", miles.distance(804.672))
        assertEquals("400 ft", miles.distance(121.92))
        assertEquals("50 ft", miles.distance(15.0))
        assertEquals("250 ft", miles.distance(76.2))
        assertEquals("10 ft", miles.distance(0.0))
        assertEquals("12 mi", miles.distance(19312.0))
    }

    @Test
    fun `metric texts`() {
        assertEquals("4.2 km", km.distance(4173.0))
        assertEquals("400 m", km.distance(421.0))
        assertEquals("In 350 m", km.distanceIn(356.0))
        assertEquals("1.4 km", km.distance(1400.0))
        assertEquals("1.0 km", km.distance(950.0))
        assertEquals("950 m", km.distance(949.0))
        assertEquals("10 km", km.distance(9950.0))
        assertEquals("20 m", km.distance(15.0))
    }

    @Test
    fun `a shrinking distance never shows a larger text, 0 to 40 km in steps of 10 cm`() {
        for (f in listOf(miles, km)) {
            var previous = metres(f.distance(0.0))
            var steps = 0
            var i = 1
            while (i <= 400_000) {
                val v = metres(f.distance(i / 10.0))
                assertTrue(v >= previous - 1e-9, "${f.system}: ${f.distance(i / 10.0)} at ${i / 10.0} m after a larger text")
                previous = v
                steps++
                i++
            }
            assertEquals(400_000, steps)
        }
    }

    @Test
    fun durations() {
        assertEquals("< 1 min", miles.duration(29.0))
        assertEquals("1 min", miles.duration(30.0))
        assertEquals("12 min", miles.duration(12 * 60.0 + 10))
        assertEquals("1 h", miles.duration(59 * 60.0 + 31))
        assertEquals("1 h 5 min", miles.duration(3900.0))
        assertEquals("< 1 min", miles.duration(-3.0))
        assertEquals("2 h", km.duration(7200.0))
    }

    @Test
    fun `the clock follows the 12-hour or 24-hour setting, not the units`() {
        val t = ZonedDateTime.of(2026, 11, 15, 15, 42, 10, 0, newYork).toInstant().toEpochMilli()
        assertEquals("3:42 PM", miles.clockTime(t, newYork))
        assertEquals("3:42 PM", km.clockTime(t, newYork))
        assertEquals("15:42", UnitFormatter(rules, UnitSystem.IMPERIAL, clock24h = true).clockTime(t, newYork))
        val morning = ZonedDateTime.of(2026, 11, 15, 9, 5, 0, 0, newYork).toInstant().toEpochMilli()
        assertEquals("9:05 AM", miles.clockTime(morning, newYork))
        assertEquals("09:05", UnitFormatter(rules, UnitSystem.METRIC, clock24h = true).clockTime(morning, newYork))
    }

    @Test
    fun `broken rules are refused`() {
        val good = File(repoRoot(), "packbuilder/routing/units.json").readText()
        assertThrows<IllegalArgumentException> { UnitRules.parse(good.replace("beamline-units/1", "beamline-units/9")) }
        assertThrows<IllegalArgumentException> { UnitRules.parse(good.replace("\"unit\": \"ft\", \"round_to\": 10", "\"unit\": \"yd\", \"round_to\": 10")) }
    }

    @Test
    fun `the bundled copy equals packbuilder's units json`() {
        val source = File(repoRoot(), "packbuilder/routing/units.json").readBytes().toString(Charsets.UTF_8).replace("\r\n", "\n")
        val copy = File(repoRoot(), "android/core/units/src/main/resources/io/github/spysyweeb/beamline/units/units.json")
            .readBytes().toString(Charsets.UTF_8)
        assertEquals(source, copy, "run python android/routing/scripts/sync_data.py")
    }

    private fun repoRoot() = File(System.getProperty("beamline.repoRoot") ?: error("beamline.repoRoot not set"))
}
