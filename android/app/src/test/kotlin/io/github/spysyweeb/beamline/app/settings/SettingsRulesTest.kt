package io.github.spysyweeb.beamline.app.settings

import io.github.spysyweeb.beamline.app.online.OnlineSettings
import io.github.spysyweeb.beamline.app.online.OnlineTexts
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Play test P55 / audit F88: why a Settings row cannot be used now (SettingsRules). */
class SettingsRulesTest {
    private val offline = OnlineSettings(choiceMade = true)
    private val on = OnlineSettings().allowOnline()

    @Test
    fun `rows under Online features say to turn it on first`() {
        assertEquals(SettingsRules.NEEDS_ONLINE, SettingsRules.featureBlocked(offline))
        assertEquals(SettingsRules.NEEDS_ONLINE, SettingsRules.signInBlocked(offline, false))
        assertEquals(SettingsRules.NEEDS_ONLINE, SettingsRules.checkNowBlocked(offline, false))
        assertNull(SettingsRules.featureBlocked(on))
        assertNull(SettingsRules.signInBlocked(on, false))
        assertNull(SettingsRules.checkNowBlocked(on, false))
    }

    @Test
    fun `sign in also needs Contribute to OpenStreetMap`() {
        val noOsm = on.withContributeOsm(false)
        assertEquals(SettingsRules.NEEDS_OSM, SettingsRules.signInBlocked(noOsm, false))
        assertNull(SettingsRules.featureBlocked(noOsm))   // the switch itself can be turned back on
    }

    @Test
    fun `while guiding the rows that open something say so`() {
        assertEquals("Not while guiding", SettingsRules.signInBlocked(on, true))
        assertEquals("Not while guiding", SettingsRules.checkNowBlocked(on, true))
        assertEquals("Not while guiding", SettingsRules.drivingBlocked(true))
        assertNull(SettingsRules.drivingBlocked(false))
        assertEquals(SettingsRules.NEEDS_ONLINE, SettingsRules.signInBlocked(offline, true))   // the main switch first
    }

    @Test
    fun `the descriptions under Online features are short`() {
        for (t in listOf(OnlineTexts.SETTINGS_MAIN_ON, OnlineTexts.SETTINGS_MAIN_OFF, OnlineTexts.SETTINGS_OSM_TEXT, OnlineTexts.SIGN_IN_TEXT,
            OnlineTexts.SETTINGS_MAP_UPDATES_TEXT, OnlineTexts.SETTINGS_APP_UPDATES_TEXT)) {
            assertTrue(t.length <= 115, "${t.length}: $t")
        }
        assertNotEquals(OnlineTexts.SETTINGS_MAIN, OnlineTexts.SETTINGS_SECTION)
    }
}
