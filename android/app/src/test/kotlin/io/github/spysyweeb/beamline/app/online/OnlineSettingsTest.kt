package io.github.spysyweeb.beamline.app.online

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The switches of docs/online-design.md section 2 (batch O1). */
class OnlineSettingsTest {

    @Test
    fun theDefaultIsOfflineOnlyWithTheChoiceStillToMake() {
        val s = OnlineSettings()
        assertFalse(s.choiceMade)
        assertFalse(s.online)
        assertFalse(s.osmAllowed)
    }

    @Test
    fun allowingOnlineTurnsEveryFeatureOnAndEachCanBeTurnedOffAlone() {
        val on = OnlineSettings().allowOnline()
        assertTrue(on.choiceMade && on.online && on.contributeOsm && on.osmAllowed)
        val noOsm = on.withContributeOsm(false)
        assertTrue(noOsm.online)
        assertFalse(noOsm.osmAllowed)
        // The main switch off: nothing is allowed, and a feature's switch cannot be turned on under it.
        val off = on.offlineOnly()
        assertTrue(off.choiceMade)
        assertFalse(off.osmAllowed)
        assertFalse(off.withContributeOsm(true).osmAllowed)
        // The main switch on again turns every feature on again (the owner, 2026-11-20).
        assertTrue(noOsm.offlineOnly().allowOnline().osmAllowed)
        // Offline only on the first run.
        val offline = OnlineSettings().offlineOnly()
        assertTrue(offline.choiceMade)
        assertFalse(offline.online || offline.osmAllowed)
    }

    @Test
    fun mapUpdateChecksFollowTheMainSwitchAndTheirOwn(@TempDir dir: File) {
        val on = OnlineSettings().allowOnline()
        assertTrue(on.mapUpdatesAllowed, "item 40: on with every online feature")
        assertFalse(on.withCheckMapUpdates(false).mapUpdatesAllowed)
        assertTrue(on.withCheckMapUpdates(false).osmAllowed, "the other switch stays as it was")
        assertFalse(on.offlineOnly().mapUpdatesAllowed)
        assertFalse(on.offlineOnly().withCheckMapUpdates(true).mapUpdatesAllowed, "not under a main switch that is off")
        // a file from before the switch takes the main switch's value
        val f = File(dir, OnlineSettingsStore.FILE_NAME)
        f.writeText("""{"choice_made":true,"online":true,"contribute_osm":true}""")
        assertTrue(OnlineSettingsStore(f).state.value.mapUpdatesAllowed)
        f.writeText("""{"choice_made":true,"online":false,"contribute_osm":false}""")
        assertFalse(OnlineSettingsStore(f).state.value.checkMapUpdates)
        f.writeText("""{"choice_made":true,"online":true,"contribute_osm":true,"check_map_updates":false}""")
        assertFalse(OnlineSettingsStore(f).state.value.mapUpdatesAllowed, "turned off stays off")
    }

    @Test
    fun theStoreKeepsTheSwitchesAndReadsABrokenFileAsTheDefaults(@TempDir dir: File) {
        val f = File(dir, OnlineSettingsStore.FILE_NAME)
        assertEquals(OnlineSettings(), OnlineSettingsStore(f).state.value, "no file: an update from an earlier version asks once")
        val store = OnlineSettingsStore(f)
        store.update { it.allowOnline().copy(noteNoticeSeen = true) }
        assertEquals(OnlineSettings(choiceMade = true, online = true, contributeOsm = true, noteNoticeSeen = true, checkMapUpdates = true), OnlineSettingsStore(f).state.value)
        f.writeText("[1,2")
        assertEquals(OnlineSettings(), OnlineSettingsStore(f).state.value)
        f.writeText("""{"choice_made":"true","online":1}""")
        assertEquals(OnlineSettings(), OnlineSettingsStore(f).state.value, "foreign values read as off")
    }
}
