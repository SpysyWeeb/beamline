package io.github.spysyweeb.beamline.app.online

import androidx.compose.ui.test.assert
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.spysyweeb.beamline.ui.theme.BeamlineTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** P64 / audit F71 (0.1.40, settings-misc.md): the "Paste the hours" field has a name for a screen reader, also once text is in it. */
@RunWith(AndroidJUnit4::class)
class PasteHoursDialogTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun thePastedHoursFieldHasAName() {
        compose.setContent { BeamlineTheme(night = false) { PasteHoursDialog(onUse = {}, onDismiss = {}) } }
        compose.onNodeWithTag("hours-paste-text").performTextInput("Mon-Fri 9-5")
        compose.onNodeWithTag("hours-paste-text").assert(hasText(OnlineTexts.HOURS))
    }
}
