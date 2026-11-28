package io.github.spysyweeb.beamline.app.online

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotFocused
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.spysyweeb.beamline.ui.theme.BeamlineTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * 0.1.41 (docs/online-design.md 4.8, AP9; plan AP-12 a): the edit form opened at a field. A 600 dp spacer above the
 * fields in a 360 x 480 dp scrolling column puts them all below the fold, so a field is on screen only when the form
 * brought it there.
 */
@RunWith(AndroidJUnit4::class)
class EditFocusScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private var used = 0

    private fun form(focus: EditFocus?) {
        compose.setContent {
            BeamlineTheme(night = false) {
                Surface(color = MaterialTheme.colorScheme.background) {
                    Column(Modifier.size(360.dp, 480.dp).verticalScroll(rememberScrollState())) {
                        Spacer(Modifier.height(600.dp))
                        FormFieldsView(FormFields(), withType = false, nameLabel = "Name", focus = focus, onFocused = { used++ }) {}
                    }
                }
            }
        }
        compose.waitForIdle()
    }

    @Test
    fun openedAtThePhone() {
        form(EditFocus.PHONE)
        compose.onNodeWithTag("edit-phone").assertIsDisplayed().assertIsFocused()
        compose.onNodeWithTag("edit-website").assertIsNotFocused()
        assertEquals(1, used)
        OnlineShots.take("edit-focus-phone-day")
    }

    @Test
    fun openedAtTheWebsite() {
        form(EditFocus.WEBSITE)
        compose.onNodeWithTag("edit-website").assertIsDisplayed().assertIsFocused()
        compose.onNodeWithTag("edit-phone").assertIsNotFocused()
        assertEquals(1, used)
    }

    @Test
    fun openedAtTheHours() {
        form(EditFocus.HOURS)
        compose.onNodeWithTag("edit-hours").assertIsDisplayed()
        compose.onNodeWithTag("edit-phone").assertIsNotFocused()
        compose.onNodeWithTag("edit-website").assertIsNotFocused()
        compose.onNodeWithTag("edit-name").assertIsNotFocused()
        assertEquals(1, used)
        OnlineShots.take("edit-focus-hours-day")
    }

    @Test
    fun openedAtItsTopWithoutAField() {
        form(null)
        compose.onNodeWithTag("edit-hours").assertIsNotDisplayed()
        compose.onNodeWithTag("edit-phone").assertIsNotFocused()
        compose.onNodeWithTag("edit-website").assertIsNotFocused()
        assertEquals(0, used)
    }
}
