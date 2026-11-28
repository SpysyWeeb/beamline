package io.github.spysyweeb.beamline.app.online

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.spysyweeb.beamline.ui.theme.BeamlineTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * OP test 28 (0.1.41, docs/next-round.md items 53a, 53d, 53e): the three asks before an upload and the merged notice,
 * the Undo dialog, and (plan) the GONE screen and the ALREADY result of the edit form.
 */
@RunWith(AndroidJUnit4::class)
class UploadAskDialogTest {
    @get:Rule
    val compose = createComposeRule()

    private fun inAsk(text: String) = hasText(text) and hasAnyAncestor(hasTestTag("upload-ask"))

    @Test
    fun theThreeAsksAndTheMergedNotice() {
        var ask by mutableStateOf(UploadAsk.PUBLIC)
        var withSurvey by mutableStateOf(false)
        var night by mutableStateOf(false)
        val answers = ArrayList<Boolean>()
        compose.setContent { BeamlineTheme(night = night) { UploadAskDialog(ask, withSurvey) { answers += it } } }
        // the public-edit notice alone
        compose.onNode(inAsk(OnlineTexts.EDIT_NOTICE_TITLE)).assertIsDisplayed()
        compose.onNode(inAsk(OnlineTexts.EDIT_NOTICE_TEXT)).assertIsDisplayed()
        compose.onNode(inAsk(OnlineTexts.UPLOAD)).assertIsDisplayed()
        compose.onNode(inAsk(OnlineTexts.NOT_NOW)).assertIsDisplayed()
        // the very first edit made at the place: one notice with what source=survey tells
        withSurvey = true
        compose.waitForIdle()
        compose.onNode(inAsk(OnlineTexts.EDIT_NOTICE_TEXT + "\n\n" + OnlineTexts.SURVEY_TEXT)).assertExists()
        OnlineShots.take("upload-ask-public-survey-day")
        night = true
        compose.waitForIdle()
        OnlineShots.take("upload-ask-public-survey-night")
        compose.onNodeWithTag("upload-ask-yes").performClick()
        compose.onNodeWithTag("upload-ask-no").performClick()
        assertEquals(listOf(true, false), answers)
        // not at the place: Yes / Cancel
        ask = UploadAsk.ON_SITE
        withSurvey = false
        night = false
        compose.waitForIdle()
        compose.onNode(inAsk(OnlineTexts.ON_SITE_TITLE)).assertIsDisplayed()
        compose.onNode(inAsk("Are you sure you checked this on-site?")).assertIsDisplayed()
        compose.onNode(inAsk(OnlineTexts.ON_SITE_TEXT)).assertIsDisplayed()
        compose.onNode(inAsk("Yes")).assertIsDisplayed()
        compose.onNode(inAsk("Cancel")).assertIsDisplayed()
        OnlineShots.take("upload-ask-on-site-day")
        night = true
        compose.waitForIdle()
        OnlineShots.take("upload-ask-on-site-night")
        compose.onNodeWithTag("upload-ask-yes").performClick()
        assertEquals(true, answers.last())
        // made there, the survey notice once
        ask = UploadAsk.SURVEY
        night = false
        compose.waitForIdle()
        compose.onNode(inAsk(OnlineTexts.SURVEY_TITLE)).assertIsDisplayed()
        compose.onNode(inAsk(OnlineTexts.SURVEY_TEXT)).assertExists()
        compose.onNode(inAsk(OnlineTexts.UPLOAD)).assertIsDisplayed()
        compose.onNode(inAsk(OnlineTexts.NOT_NOW)).assertIsDisplayed()
        OnlineShots.take("upload-ask-survey-day")
        night = true
        compose.waitForIdle()
        OnlineShots.take("upload-ask-survey-night")
        compose.onNodeWithTag("upload-ask-no").performClick()
        assertEquals(false, answers.last())
    }

    @Test
    fun theUndoDialogListsWhatGoesBack() {
        var night by mutableStateOf(false)
        val answers = ArrayList<Boolean>()
        val lines = listOf("Hours: Mon–Fri 8 AM–5 PM → Mon–Fri 8 AM–6 PM", "Website: https://cafe.example → (none)")
        compose.setContent { BeamlineTheme(night = night) { UndoDialog(lines) { answers += it } } }
        compose.onNodeWithText(OnlineTexts.UNDO_TITLE).assertIsDisplayed()
        lines.forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText(OnlineTexts.UNDO_TEXT).assertIsDisplayed()
        OnlineShots.take("undo-dialog-day")
        night = true
        compose.waitForIdle()
        OnlineShots.take("undo-dialog-night")
        compose.onNodeWithTag("undo-ask-yes").assertIsDisplayed().performClick()
        compose.onNodeWithText(OnlineTexts.CANCEL).performClick()
        assertEquals(listOf(true, false), answers)
    }

    /** Plan 4.o: the GONE screen (53a) and the ALREADY result (OP-4) of the edit form. */
    @Test
    fun theGoneScreenAndTheAlreadyResult() {
        var phase by mutableStateOf(EditPhase.GONE)
        var already by mutableStateOf(false)
        var goneItem by mutableStateOf(false)
        var night by mutableStateOf(false)
        val links = ArrayList<String>()
        var deleted = 0
        var closed = 0
        compose.setContent {
            BeamlineTheme(night = night) {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    Column {
                        EditOutcome(phase, already, goneItem, "https://www.openstreetmap.org/node/42", null,
                            onOpenLink = { links += it }, onDelete = { deleted++ }, onClose = { closed++ })
                    }
                }
            }
        }
        // a new edit of a place deleted from OSM: nothing to delete
        compose.onNodeWithTag("edit-gone").assertIsDisplayed()
        compose.onNodeWithText(OnlineTexts.EDIT_GONE).assertIsDisplayed()
        compose.onNodeWithTag("edit-gone-delete").assertDoesNotExist()
        compose.onNodeWithText(OnlineTexts.CLOSE).performClick()
        assertEquals(1, closed)
        // an item of it: "Delete"
        goneItem = true
        compose.waitForIdle()
        compose.onNodeWithText(OnlineTexts.EDIT_GONE_ITEM).assertIsDisplayed()
        OnlineShots.take("edit-gone-day")
        night = true
        compose.waitForIdle()
        OnlineShots.take("edit-gone-night")
        compose.onNodeWithTag("edit-gone-delete").assertIsDisplayed().performClick()
        assertEquals(1, deleted)
        // OSM already had every value: said so, and the place's page
        phase = EditPhase.DONE
        already = true
        night = false
        compose.waitForIdle()
        compose.onNodeWithText("Already on OpenStreetMap: nothing needed to change").assertIsDisplayed()
        OnlineShots.take("edit-already-day")
        night = true
        compose.waitForIdle()
        OnlineShots.take("edit-already-night")
        compose.onNodeWithText(OnlineTexts.OPEN_NOTE).performClick()
        assertEquals(listOf("https://www.openstreetmap.org/node/42"), links)
        // an upload: the usual words
        already = false
        compose.waitForIdle()
        compose.onNodeWithText(OnlineTexts.DONE).assertIsDisplayed()
        compose.onNodeWithText(OnlineTexts.OPEN_CHANGE).assertIsDisplayed()
    }
}
