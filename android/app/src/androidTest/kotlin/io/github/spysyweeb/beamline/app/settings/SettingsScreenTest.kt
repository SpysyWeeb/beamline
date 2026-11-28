package io.github.spysyweeb.beamline.app.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performSemanticsAction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.spysyweeb.beamline.app.online.OnlineSettings
import io.github.spysyweeb.beamline.app.online.OnlineTexts
import io.github.spysyweeb.beamline.settings.SettingsStore
import io.github.spysyweeb.beamline.ui.theme.BeamlineTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * P55 / audit F88 (0.1.40, settings-misc.md): a Settings row that cannot be used now says why, a tap on it shows
 * the reason in a bar (each tap its full time) and changes nothing; the sections are headings and the link rows
 * buttons. P64 guard: every tappable row reads as one label.
 *
 * Run (never :app:connectedDebugAndroidTest, which uninstalls the app and its packs): build
 * :app:assembleDebug :app:assembleDebugAndroidTest, `install -r` both APKs, then
 * `am instrument -w -e class io.github.spysyweeb.beamline.app.settings.SettingsScreenTest io.github.spysyweeb.beamline.test/androidx.test.runner.AndroidJUnitRunner`.
 */
@RunWith(AndroidJUnit4::class)
class SettingsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    // made once, off the main thread; write = {} so nothing is stored
    private val store = SettingsStore(File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "settings-test.json"))
    private var online by mutableStateOf(OnlineSettings(choiceMade = true))
    private var driving by mutableStateOf(false)
    private val changes = mutableListOf<String>()

    private fun show(appUpdateReady: Boolean = false, appUpdateLine: String = "Up to date") = compose.setContent {
        BeamlineTheme(night = false) {
            SettingsScreen(
                store = store, write = {}, driving = driving,
                onSavedPlaces = { changes += "saved places" }, onStates = { changes += "states" },
                online = online, onOnline = { changes += "online $it" }, onContributeOsm = { changes += "osm $it" },
                onCheckMapUpdates = { changes += "map updates $it" }, appUpdates = true, onCheckAppUpdates = { changes += "app updates $it" },
                appUpdateLine = appUpdateLine, onCheckAppNow = { changes += "check now" }, appUpdateReady = appUpdateReady,
                onInstallApp = { changes += "install" }, reportsText = "None yet", accountName = null, signingIn = false,
                onSignIn = { changes += "sign in" }, onSignOut = { changes += "sign out" }, onReports = { changes += "reports" },
                onAbout = { changes += "about" }, onBack = { changes += "back" },
            )
        }
    }

    /** A screen reader's double tap on the row (its click action): the reason bar at the bottom never covers it. */
    private fun SemanticsNodeInteraction.tap(): SemanticsNodeInteraction = performScrollTo().performSemanticsAction(SemanticsActions.OnClick)

    private fun signIn() = compose.onNode(hasText(OnlineTexts.SIGN_IN) and hasClickAction())
    private fun bar() = compose.onNodeWithTag("settings-reason")

    @Test
    fun rowsThatNeedOnlineFeaturesSayWhyAndATapSaysItAgain() {
        show()
        val rows = listOf(compose.onNodeWithTag("switch-osm"), compose.onNodeWithTag("switch-map-updates"), compose.onNodeWithTag("switch-app-updates"), signIn())
        for (row in rows) {
            row.performScrollTo().assert(hasText(SettingsRules.NEEDS_ONLINE))
            row.tap()
            bar().assert(hasText(SettingsRules.NEEDS_ONLINE))
            bar().assert(SemanticsMatcher.expectValue(SemanticsProperties.LiveRegion, LiveRegionMode.Polite))
        }
        compose.waitForIdle()
        assertTrue("$changes", changes.isEmpty())
    }

    @Test
    fun eachTapGivesTheReasonItsFullTime() {
        show()
        compose.waitForIdle()
        compose.mainClock.autoAdvance = false
        // no scrolling with the clock held: the rows are composed (a plain scrolling Column), their click action works
        compose.onNodeWithTag("switch-osm").performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeBy(3_000)
        compose.onNodeWithTag("switch-map-updates").performSemanticsAction(SemanticsActions.OnClick)
        compose.mainClock.advanceTimeBy(2_000)
        bar().assertIsDisplayed().assert(hasText(SettingsRules.NEEDS_ONLINE))   // the same words, a new tap: its own 4 s
        compose.mainClock.advanceTimeBy(2_500)
        bar().assertDoesNotExist()
    }

    @Test
    fun theReasonGoesWhenOnlineFeaturesAreTurnedOn() {
        show()
        compose.onNodeWithTag("switch-osm").tap()
        bar().assertExists()
        compose.runOnIdle { online = online.allowOnline() }
        compose.waitForIdle()
        bar().assertDoesNotExist()
        compose.onNodeWithTag("switch-osm").assert(hasText(OnlineTexts.SETTINGS_OSM_TEXT))
    }

    @Test
    fun signInSaysContributeFirst() {
        online = OnlineSettings().allowOnline().withContributeOsm(false)
        show()
        signIn().performScrollTo().assert(hasText(SettingsRules.NEEDS_OSM))
        signIn().tap()
        bar().assert(hasText(SettingsRules.NEEDS_OSM))
        compose.onNodeWithTag("switch-osm").tap()
        compose.waitForIdle()
        assertTrue("$changes", changes == listOf("osm true"))
    }

    @Test
    fun anUpdateReadyOfflineKeepsItsLineAndInstall() {
        show(appUpdateReady = true, appUpdateLine = "Beamline 0.1.41 is ready to install")
        compose.onNode(hasText("Check for an update now") and hasClickAction()).performScrollTo()
            .assert(hasText("Beamline 0.1.41 is ready to install")).assert(hasText(SettingsRules.NEEDS_ONLINE))
        compose.onNodeWithText("Install").performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue("$changes", changes == listOf("install"))
    }

    @Test
    fun sectionsAreHeadingsAndLinksAreButtons() {
        show()
        for (t in listOf("Settings", "Map style", "Units", "Places", "Internet", "About")) {
            compose.onNode(hasText(t) and isHeading()).assertExists("heading $t")
        }
        compose.onNode(hasText("States") and hasClickAction()).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Button))
    }

    @Test
    fun onlineFeaturesIsReadOnce() {
        show()
        compose.onAllNodesWithText(OnlineTexts.SETTINGS_MAIN).assertCountEquals(1)
    }

    @Test
    fun everyTappableRowReadsAsOneLabel() {
        show()
        fun check(label: String) {
            compose.waitForIdle()
            for (n in compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()) {
                val text = n.config.getOrNull(SemanticsProperties.Text)?.joinToString(" ") { it.text }.orEmpty()
                val desc = n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString(" ").orEmpty()
                assertTrue("$label: a tappable node without a name: ${n.config}", text.isNotBlank() || desc.isNotBlank())
            }
        }
        check("offline")
        compose.runOnIdle { online = OnlineSettings().allowOnline() }
        check("online")
    }
}
