package io.github.spysyweeb.beamline.app.online

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
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
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.spysyweeb.beamline.ui.theme.BeamlineTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** OP test 27 (0.1.41, docs/next-round.md item 53d): My reports' Undo, and the rows of an undo and a confirmation. */
@RunWith(AndroidJUnit4::class)
class ReportsScreenTest {
    @get:Rule
    val compose = createComposeRule()

    private val sent = OutboxItem(
        "s1", 42.1, -80.1, "Hours: Mon–Fri 8 AM–6 PM → Mon–Fri 8 AM–5 PM", "Test Cafe", 1L, OutboxState.SENT,
        link = "https://www.openstreetmap.org/changeset/900", sentMs = 2L,
        edit = EditData("n42", 3, mapOf("opening_hours" to "Mo-Fr 08:00-18:00"), mapOf("opening_hours" to "Mo-Fr 08:00-17:00"), "Updated hours", changeset = 900, newVersion = 4),
    )
    private val older = sent.copy(id = "s2", text = "Website: (none) → https://cafe.example", createdMs = 0L)
    private val undo = OutboxItem(
        "u1", 42.1, -80.1, "Hours: Mon–Fri 8 AM–5 PM → Mon–Fri 8 AM–6 PM", "Test Cafe", 3L,
        edit = EditData("n42", 4, mapOf("opening_hours" to "Mo-Fr 08:00-17:00"), mapOf("opening_hours" to "Mo-Fr 08:00-18:00"), "Undid my change to a place", undoOf = "s1"),
    )
    private val waitingCheck = HoursCheck.item(HoursTarget("n43", "Corner Bakery", 42.2, -80.2, "Mo-Sa 07:00-15:00"), "c1", 4L, java.time.ZoneId.of("UTC"), atPlace = true)
    private val reviewCheck = waitingCheck.copy(id = "c2", createdMs = 5L, state = OutboxState.REVIEW)

    @Test
    fun aSentEditOffersUndoAndAnUndoRowNeverOpens() {
        var night by mutableStateOf(false)
        val undone = ArrayList<String>()
        val opened = ArrayList<String>()
        val removed = ArrayList<String>()
        compose.setContent {
            BeamlineTheme(night = night) {
                // the narrow phone: three buttons of a sent row wrap, each whole
                Box(Modifier.width(360.dp)) {
                    ReportsScreen(
                        items = listOf(reviewCheck, waitingCheck, undo, sent, older), sending = null, canSend = true, signedIn = true,
                        onOpen = { opened += it.id }, onOpenLink = {}, onRemove = { removed += it.id }, onSendNow = {}, onBack = {},
                        undoable = setOf("s1"), onUndo = { undone += it.id },
                    )
                }
            }
        }
        compose.onNodeWithTag("undo-s1").assertIsDisplayed()
        compose.onNode(hasText(OnlineTexts.OPEN_NOTE) and hasAnyAncestor(hasTestTag("report-row-s1"))).assertIsDisplayed()
        compose.onNode(hasText(OnlineTexts.REMOVE) and hasAnyAncestor(hasTestTag("report-row-s1"))).assertIsDisplayed()
        OnlineShots.take("reports-undo-day")
        night = true
        compose.waitForIdle()
        OnlineShots.take("reports-undo-night")
        compose.onNodeWithTag("undo-s1").performClick()
        assertEquals(listOf("s1"), undone)
        compose.onNodeWithTag("undo-s2").assertDoesNotExist()
        // the undo's own row: "Delete" before it goes; its text opens no form
        compose.onNodeWithText(undo.text).performClick()
        compose.onNodeWithTag("delete-u1").assertIsDisplayed().performClick()
        assertEquals(listOf("u1"), removed)
        // a confirmation waiting: "Delete", no form; one that came back (REVIEW) opens
        compose.onNodeWithTag("delete-c1").assertIsDisplayed()
        compose.onNodeWithTag("delete-c2").assertDoesNotExist()
        compose.onNodeWithTag("report-row-c1").performClick()
        compose.onNodeWithTag("report-row-c2").performClick()
        assertEquals(listOf("c2"), opened)
        compose.onNodeWithText("Undo · Test Cafe").assertIsDisplayed()
        compose.onNode(hasTestTag("report-row-c1") and hasText("Hours check · Corner Bakery")).assertIsDisplayed()
        assertTrue(removed.size == 1)
    }
}
