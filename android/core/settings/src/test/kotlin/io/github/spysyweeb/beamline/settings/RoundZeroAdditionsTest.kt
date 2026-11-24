package io.github.spysyweeb.beamline.settings

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The settings and app-state pieces docs/architecture.md 2.10 adds. */
class RoundZeroAdditionsTest {

    @TempDir
    lateinit var dir: File

    @Test
    fun `route type, avoid flags and house numbers are stored and read back`() {
        val file = File(dir, "settings.json")
        val store = SettingsStore(file)
        assertEquals(Settings(routeType = "fastest", avoidHighways = false, avoidTolls = false, includeAddresses = true), store.settings.value)
        store.update { it.copy(routeType = "shortest", avoidHighways = true, avoidTolls = true, includeAddresses = false) }
        val back = SettingsStore(file).settings.value
        assertEquals("shortest", back.routeType)
        assertEquals(true, back.avoidHighways)
        assertEquals(true, back.avoidTolls)
        assertEquals(false, back.includeAddresses)
    }

    @Test
    fun `the route type is kept as written, even one the presets do not offer`() {
        // The store does not know the presets; the session checks the id (docs/architecture.md 2.10).
        val file = File(dir, "settings.json")
        file.writeText("""{"route_type":"eco","avoid_tolls":"yes","include_addresses":false}""")
        val s = SettingsStore(file).settings.value
        assertEquals("eco", s.routeType)
        assertEquals(false, s.avoidTolls) // not a boolean: the default
        assertEquals(false, s.includeAddresses)
        file.writeText("""{"route_type":"","avoid_highways":1}""")
        assertEquals(Settings(), SettingsStore(file).settings.value)
        // a file of the app shell (no new keys) still reads
        file.writeText("""{"map_style":"night","frame_rate":"60","guidance_sound":"voice","units":"kilometres"}""")
        assertEquals(Settings(MapStyle.NIGHT, FrameRate.FPS_60, GuidanceSound.VOICE, Units.KILOMETRES), SettingsStore(file).settings.value)
    }

    @Test
    fun `the accepted first-run notice version is remembered`() {
        val file = File(dir, "app-state.json")
        val store = AppStateStore(file)
        assertEquals(AppState(firstRunNoticeAccepted = 0), store.state.value)
        assertFalse(file.exists())
        store.update { it.copy(firstRunNoticeAccepted = 1) }
        assertEquals("""{"first_run_notice_accepted":1}""", file.readText())
        assertEquals(1, AppStateStore(file).state.value.firstRunNoticeAccepted)
        assertFalse(File(dir, "app-state.json.tmp").exists())
    }

    @Test
    fun `a broken app-state file reads as never accepted`() {
        val file = File(dir, "app-state.json")
        file.writeText("{not json")
        assertEquals(AppState(), AppStateStore(file).state.value)
        file.writeText("""{"first_run_notice_accepted":"one"}""")
        assertEquals(AppState(), AppStateStore(file).state.value)
        file.writeText("[1]")
        assertEquals(AppState(), AppStateStore(file).state.value)
    }
}
