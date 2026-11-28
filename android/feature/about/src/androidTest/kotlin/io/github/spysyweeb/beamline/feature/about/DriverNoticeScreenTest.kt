package io.github.spysyweeb.beamline.feature.about

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.spysyweeb.beamline.ui.theme.BeamlineTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Play test P59: About's "Driver notice" row opened the first-run page (the icon, the notice and
 * "I understand", no title, no Back), so it read as a new acceptance. The page About opens is
 * DriverNoticeScreen: a top bar with Back and the title, the same notice text, nothing to accept.
 *
 * Run: sh tools/round/gradle.sh a :feature:about:connectedDebugAndroidTest (emulator beamline_a up).
 */
@RunWith(AndroidJUnit4::class)
class DriverNoticeScreenTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var backs = 0

    private fun show(night: Boolean = false) {
        compose.setContent { BeamlineTheme(night = night) { DriverNoticeScreen(onBack = { backs++ }) } }
    }

    @Test
    fun theNoticeFromAboutHasATitleAndBackAndNothingToAccept() {
        show()
        compose.onNode(hasText(AboutTexts.DRIVER_NOTICE_ROW) and isHeading()).assertIsDisplayed()
        compose.onNodeWithText(AboutTexts.FIRST_RUN_NOTICE).assertIsDisplayed()
        compose.onNodeWithText(AboutTexts.FIRST_RUN_ACCEPT).assertDoesNotExist()
        compose.onNodeWithContentDescription(AboutTexts.BACK).performClick()
        compose.waitForIdle()
        assertEquals(1, backs)
    }

    /** About's screens handle the system back key themselves (feature/about/README.md); so does this one. */
    @Test
    fun theSystemBackKeyLeavesTheNotice() {
        show(night = true)
        compose.onNodeWithText(AboutTexts.FIRST_RUN_NOTICE).assertIsDisplayed()
        compose.runOnUiThread { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.waitForIdle()
        assertEquals(1, backs)
    }
}
