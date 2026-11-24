package io.github.spysyweeb.beamline.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.time.OffsetDateTime
import kotlin.math.abs

/**
 * Batch A (docs/next-round.md item 4): the sun on the phone against the US Naval Observatory's
 * published times (aa.usno.navy.mil, "Rise/Set/Transit/Twilight data", looked up on 2026-11-18;
 * given to the minute, so 90 s are allowed), the switch rule, and the old setting.
 */
class SunTest {
    private fun ms(iso: String) = OffsetDateTime.parse(iso).toInstant().toEpochMilli()

    /** The crossings of [elevation] on the local day starting at [midnight]. */
    private fun day(midnight: String, lat: Double, lon: Double, elevation: Double): List<Sun.Crossing> {
        val from = ms(midnight)
        return Sun.crossings(from, from + 86_400_000L, lat, lon, elevation)
    }

    private fun assertAt(expected: String, actualMs: Long) {
        val diff = abs(actualMs - ms(expected)) / 1000
        assertTrue(diff <= 90, "expected $expected, off by $diff s")
    }

    @Test
    fun `Erie in June - sunrise, sunset and civil twilight as published`() {
        val rise = day("2026-06-21T00:00-04:00", ERIE_LAT, ERIE_LON, -0.833)
        assertEquals(listOf(true, false), rise.map { it.rising })
        assertAt("2026-06-21T05:45-04:00", rise[0].epochMs)
        assertAt("2026-06-21T21:00-04:00", rise[1].epochMs)
        val civil = day("2026-06-21T00:00-04:00", ERIE_LAT, ERIE_LON, -6.0)
        assertAt("2026-06-21T05:10-04:00", civil[0].epochMs)
        assertAt("2026-06-21T21:35-04:00", civil[1].epochMs)
    }

    @Test
    fun `Erie in December and Pittsburgh in March`() {
        val erie = day("2026-12-21T00:00-05:00", ERIE_LAT, ERIE_LON, -0.833)
        assertAt("2026-12-21T07:45-05:00", erie[0].epochMs)
        assertAt("2026-12-21T16:52-05:00", erie[1].epochMs)
        val civil = day("2026-12-21T00:00-05:00", ERIE_LAT, ERIE_LON, -6.0)
        assertAt("2026-12-21T07:14-05:00", civil[0].epochMs)
        assertAt("2026-12-21T17:23-05:00", civil[1].epochMs)
        val pgh = day("2026-03-20T00:00-04:00", 40.4406, -79.9959, -0.833)
        assertAt("2026-03-20T07:23-04:00", pgh[0].epochMs)
        assertAt("2026-03-20T19:32-04:00", pgh[1].epochMs)
    }

    @Test
    fun `polar night and midnight sun - no crash, no sunrise, the rule still answers`() {
        // Utqiagvik, Alaska: USNO "continuously below the horizon" on 2026-12-21, civil twilight 11:56-14:55 AKST
        val lat = 71.2906
        val lon = -156.7886
        assertTrue(day("2026-12-21T00:00-09:00", lat, lon, -0.833).isEmpty())
        val civil = day("2026-12-21T00:00-09:00", lat, lon, -6.0)
        assertAt("2026-12-21T11:56-09:00", civil[0].epochMs)
        assertAt("2026-12-21T14:55-09:00", civil[1].epochMs)
        val rule = SunRule()
        assertTrue(rule.dark(ms("2026-12-21T20:00-09:00"), lat, lon, null))
        // midnight sun: never below the threshold, no switch within a day, check again in a minute
        val june = ms("2026-06-21T00:00-08:00")
        assertTrue(day("2026-06-21T00:00-08:00", lat, lon, -0.833).isEmpty())
        assertFalse(rule.dark(june, lat, lon, null))
        assertNull(rule.nextSwitchMs(june, lat, lon, dark = false))
        assertEquals(SunRule.CHECK_MS, rule.delayMs(june, lat, lon, dark = false))
        // the North Pole and the date line: still numbers
        for (h in 0..23) {
            val e = Sun.elevationDeg(ms("2026-03-20T00:00Z") + h * 3_600_000L, 90.0, 180.0)
            assertTrue(e.isFinite() && e in -90.0..90.0)
        }
    }

    @Test
    fun `the rule - night from civil dusk to civil dawn, with a margin once a theme is shown`() {
        val rule = SunRule()
        val dusk = ms("2026-06-21T21:35-04:00") // civil dusk at Erie
        assertFalse(rule.dark(dusk - 15 * 60_000L, ERIE_LAT, ERIE_LON, null))
        assertTrue(rule.dark(dusk + 15 * 60_000L, ERIE_LAT, ERIE_LON, null))
        // just past the boundary: the day map stays until the margin is passed too, and back
        val justAfter = dusk + 60_000L
        assertTrue(rule.dark(justAfter, ERIE_LAT, ERIE_LON, null))
        assertFalse(rule.dark(justAfter, ERIE_LAT, ERIE_LON, wasDark = false))
        assertTrue(rule.dark(dusk - 60_000L, ERIE_LAT, ERIE_LON, wasDark = true))
        // the next switch: dusk plus the margin (a few minutes), and the timer waits for it when sooner than a minute
        val next = rule.nextSwitchMs(dusk - 10 * 60_000L, ERIE_LAT, ERIE_LON, dark = false)!!
        assertTrue(next > dusk && next < dusk + 6 * 60_000L, "switch ${(next - dusk) / 1000} s after dusk")
        assertFalse(rule.dark(next - 2_000L, ERIE_LAT, ERIE_LON, wasDark = false))
        assertTrue(rule.dark(next + 2_000L, ERIE_LAT, ERIE_LON, wasDark = false))
        assertEquals(SunRule.CHECK_MS, rule.delayMs(dusk - 10 * 60_000L, ERIE_LAT, ERIE_LON, dark = false))
        val d = rule.delayMs(next - 20_000L, ERIE_LAT, ERIE_LON, dark = false)
        assertTrue(d in 20_000L..22_000L, "delay $d")
        // the morning: day again after civil dawn plus the margin
        val dawn = ms("2026-06-22T05:11-04:00")
        assertTrue(rule.dark(dawn - 60_000L, ERIE_LAT, ERIE_LON, wasDark = true))
        assertFalse(rule.dark(dawn + 6 * 60_000L, ERIE_LAT, ERIE_LON, wasDark = true))
    }

    @Test
    fun `map style - Automatic follows the sun, Same as phone the phone, the old file keeps working`() {
        val auto = Settings(mapStyle = MapStyle.AUTOMATIC)
        assertTrue(auto.nightMap(systemDark = false, sunDark = true))
        assertFalse(auto.nightMap(systemDark = true, sunDark = false))
        assertTrue(auto.nightMap(systemDark = true, sunDark = null)) // no place to use: the phone's setting
        assertTrue(Settings(mapStyle = MapStyle.SAME_AS_PHONE).nightMap(systemDark = true, sunDark = false))
        assertFalse(Settings(mapStyle = MapStyle.SAME_AS_PHONE).nightMap(systemDark = false, sunDark = true))
        assertFalse(Settings(mapStyle = MapStyle.DAY).nightMap(systemDark = true, sunDark = true))
        val dir = Files.createTempDirectory("sun").toFile()
        val file = File(dir, "settings.json")
        file.writeText("""{"map_style":"automatic","units":"miles"}""")
        assertEquals(MapStyle.AUTOMATIC, SettingsStore(file).settings.value.mapStyle)
        file.writeText("""{"map_style":"phone"}""")
        assertEquals(MapStyle.SAME_AS_PHONE, SettingsStore(file).settings.value.mapStyle)
        SettingsStore(file).update { it.copy(mapStyle = MapStyle.SAME_AS_PHONE) }
        assertTrue(file.readText().contains("\"map_style\":\"phone\""))
        dir.deleteRecursively()
    }

    companion object {
        /** Downtown Erie (a public point). */
        const val ERIE_LAT = 42.1292
        const val ERIE_LON = -80.0851
    }
}
