package io.github.spysyweeb.beamline.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

class SettingsTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `defaults are the owner's choices`() {
        val s = SettingsStore(File(dir, "settings.json")).settings.value
        assertEquals(Settings(MapStyle.AUTOMATIC, FrameRate.SCREEN, GuidanceSound.SILENT, Units.MILES), s)
    }

    @Test
    fun `changes are stored and read back`() {
        val file = File(dir, "settings.json")
        val store = SettingsStore(file)
        store.update { it.copy(mapStyle = MapStyle.NIGHT, frameRate = FrameRate.FPS_60, guidanceSound = GuidanceSound.VOICE, units = Units.KILOMETRES) }
        assertEquals(
            """{"map_style":"night","frame_rate":"60","guidance_sound":"voice","units":"kilometres",""" +
                """"route_type":"fastest","avoid_highways":false,"avoid_tolls":false,"include_addresses":true}""",
            file.readText(),
        )
        assertEquals(store.settings.value, SettingsStore(file).settings.value)
        assertFalse(File(dir, "settings.json.tmp").exists())
    }

    @Test
    fun `unknown, missing or broken values fall back to the default`() {
        val file = File(dir, "settings.json")
        file.writeText("""{"map_style":"sepia","frame_rate":"60","extra":"from a newer app"}""")
        assertEquals(Settings(frameRate = FrameRate.FPS_60), SettingsStore(file).settings.value)
        file.writeText("not json")
        assertEquals(Settings(), SettingsStore(file).settings.value)
    }

    @Test
    fun `a stored 30 frames a second, a choice the owner removed, reads as 60`() {
        val file = File(dir, "settings.json")
        file.writeText("""{"map_style":"night","frame_rate":"30","units":"kilometres"}""")
        assertEquals(Settings(MapStyle.NIGHT, FrameRate.FPS_60, GuidanceSound.SILENT, Units.KILOMETRES), SettingsStore(file).settings.value)
        assertEquals(listOf(FrameRate.SCREEN, FrameRate.FPS_60), FrameRate.entries.toList())
    }

    @Test
    fun `map style decides day or night`() {
        assertTrue(Settings(mapStyle = MapStyle.AUTOMATIC).nightMap(systemDark = true))
        assertFalse(Settings(mapStyle = MapStyle.AUTOMATIC).nightMap(systemDark = false))
        assertFalse(Settings(mapStyle = MapStyle.DAY).nightMap(systemDark = true))
        assertTrue(Settings(mapStyle = MapStyle.NIGHT).nightMap(systemDark = false))
    }

    @Test
    fun `screen rate asks for the fastest mode at the current resolution`() {
        val modes = listOf(
            DisplayMode(1, 1080, 2400, 60f),
            DisplayMode(2, 1080, 2400, 120f),
            DisplayMode(3, 1440, 3200, 144f), // another resolution: not chosen
            DisplayMode(4, 1080, 2400, 90f),
        )
        assertEquals(FrameRatePlan(120, 120f, 2), FrameRates.plan(FrameRate.SCREEN, modes, modes[0]))
        assertEquals(FrameRatePlan(60, 60f, 0), FrameRates.plan(FrameRate.FPS_60, modes, modes[0]))
        // The emulator: one 60 Hz mode (Android reports 60.000004).
        val emu = DisplayMode(1, 1080, 2400, 60.000004f)
        assertEquals(FrameRatePlan(60, 60.000004f, 1), FrameRates.plan(FrameRate.SCREEN, listOf(emu), emu))
        assertEquals(60, FrameRates.plan(FrameRate.SCREEN, emptyList(), null).mapMaxFps)
    }
}
