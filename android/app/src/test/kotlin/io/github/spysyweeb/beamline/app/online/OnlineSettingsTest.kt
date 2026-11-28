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
        // item 41: the app-update switch the same way
        assertTrue(on.appUpdatesAllowed)
        assertFalse(on.withCheckAppUpdates(false).appUpdatesAllowed)
        assertTrue(on.withCheckAppUpdates(false).mapUpdatesAllowed, "each switch alone")
        assertFalse(on.offlineOnly().withCheckAppUpdates(true).appUpdatesAllowed)
        f.writeText("""{"choice_made":true,"online":true,"contribute_osm":true,"check_map_updates":false}""")
        assertTrue(OnlineSettingsStore(f).state.value.appUpdatesAllowed, "a file from before item 41 takes the main switch")
    }

    /** OP test 21 (0.1.41, 53e): the source=survey notice is remembered, and a file without the key reads as not seen. */
    @Test
    fun theSurveyNoticeIsRememberedAndDefaultsToNotSeen(@TempDir dir: File) {
        val f = File(dir, OnlineSettingsStore.FILE_NAME)
        f.writeText("""{"choice_made":true,"online":true,"contribute_osm":true,"edit_notice_seen":true}""")
        val old = OnlineSettingsStore(f).state.value
        assertTrue(old.editNoticeSeen)
        assertFalse(old.surveyNoticeSeen, "a file of 0.1.40: not seen")
        val store = OnlineSettingsStore(f)
        store.update { it.copy(surveyNoticeSeen = true) }
        assertTrue(f.readText().contains("\"survey_notice_seen\":true"))
        assertTrue(OnlineSettingsStore(f).state.value.surveyNoticeSeen)
        assertTrue(OnlineSettingsStore(f).state.value.editNoticeSeen, "the others kept")
        assertFalse(OnlineSettings().surveyNoticeSeen)
    }

    @Test
    fun theStoreKeepsTheSwitchesAndReadsABrokenFileAsTheDefaults(@TempDir dir: File) {
        val f = File(dir, OnlineSettingsStore.FILE_NAME)
        assertEquals(OnlineSettings(), OnlineSettingsStore(f).state.value, "no file: an update from an earlier version asks once")
        val store = OnlineSettingsStore(f)
        store.update { it.allowOnline().copy(noteNoticeSeen = true) }
        assertEquals(OnlineSettings(choiceMade = true, online = true, contributeOsm = true, noteNoticeSeen = true, checkMapUpdates = true, checkAppUpdates = true), OnlineSettingsStore(f).state.value)
        f.writeText("[1,2")
        assertEquals(OnlineSettings(), OnlineSettingsStore(f).state.value)
        f.writeText("""{"choice_made":"true","online":1}""")
        assertEquals(OnlineSettings(), OnlineSettingsStore(f).state.value, "foreign values read as off")
    }
}
