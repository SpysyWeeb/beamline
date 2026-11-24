package io.github.spysyweeb.beamline.feature.drive

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.spysyweeb.beamline.api.map.EdgeInsetsDp
import io.github.spysyweeb.beamline.api.nav.NavIntent
import io.github.spysyweeb.beamline.ui.theme.BeamlineTheme
import io.github.spysyweeb.beamline.ui.theme.SHEET_HANDLE_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Rule D10 (batch F, docs/next-round.md item 15): the route sheet's lowest height keeps each route's
 * time and Start, a card can still be chosen there, and the overlay's bottom inset (what the camera
 * fits the routes above) follows the sheet's height, down and back up.
 */
@RunWith(AndroidJUnit4::class)
class RouteSheetHeightsTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = ArrayList<DriveEvent>()
    private var insets = EdgeInsetsDp()

    private fun sheet(selected: Long) = RouteSheetUi(
        fromText = DriveTexts.YOUR_POSITION,
        stops = listOf(StopRowUi(0, "Krispy Kreme", "Peach Street, Erie", reached = false, canRemove = false, canMoveUp = false, canMoveDown = false)),
        cards = listOf(
            RouteCardUi(1, "Fastest", "10 min", "4.1 mi", "Arrive 3:36 PM", "via Peach St", listOf("Highways"), selected = selected == 1L),
            RouteCardUi(2, "Simpler", "11 min", "4.3 mi", "Arrive 3:37 PM", "via Greengarden Blvd", emptyList(), selected = selected == 2L),
        ),
        avoidHighways = false, avoidTolls = false, busy = false, problem = null, canRetry = false, warnings = emptyList(),
        canStart = true, startBlockedText = null,
    )

    private var ui by mutableStateOf(DriveUi(planning = sheet(1)))

    private fun state(): String = compose.onNodeWithTag(SHEET_HANDLE_TAG).fetchSemanticsNode().config[SemanticsProperties.StateDescription]

    @Test
    fun lowestHeightKeepsTheTimesAndStartAndTheInsetsFollow() {
        compose.setContent {
            BeamlineTheme(night = false) {
                Box(Modifier.size(411.dp, 891.dp)) {
                    DriveOverlayContent(ui, onEvent = { e ->
                        events.add(e)
                        // the session's answer to a choice: that card selected
                        if (e is DriveEvent.Nav && e.intent is NavIntent.Choose) ui = DriveUi(planning = sheet(e.intent.choiceId))
                    }, onInsets = { insets = it })
                }
            }
        }
        compose.waitForIdle()
        val desc = compose.onNodeWithTag(SHEET_HANDLE_TAG).fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
        assertTrue(desc.toString(), desc.contains(DriveTexts.ROUTE_CHOICE))
        assertEquals("Full height", state())
        val full = insets.bottom
        compose.onNodeWithTag(DriveTags.card(1)).assertIsDisplayed()
        // a drag down on the start row: the lowest height
        compose.onNodeWithTag(DriveTags.START_ROW).performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals("Lowered", state())
        assertEquals(0, compose.onAllNodes(hasTestTag(DriveTags.card(1))).fetchSemanticsNodes().size)
        compose.onNodeWithTag(DriveTags.lowCard(1)).assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag(DriveTags.lowCard(2)).assertIsDisplayed()
        compose.onNodeWithTag(DriveTags.START).assertIsDisplayed()
        val low = insets.bottom
        assertTrue("low $low < full $full", low > 0f && low < full - 150f)
        // a route chosen at the lowest height
        compose.onNodeWithTag(DriveTags.lowCard(2)).performClick()
        compose.waitForIdle()
        assertTrue(events.toString(), events.contains(DriveEvent.Nav(NavIntent.Choose(2))))
        compose.onNodeWithTag(DriveTags.lowCard(2)).assertIsSelected()
        assertEquals("Lowered", state())
        // Start works at the lowest height
        compose.onNodeWithTag(DriveTags.START).performClick()
        assertTrue(events.contains(DriveEvent.Nav(NavIntent.Start())))
        // back up by the screen reader's action, then by a tap on the handle
        val raise = compose.onNodeWithTag(SHEET_HANDLE_TAG).fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == "Raise the sheet" }
        compose.runOnUiThread { raise.action() }
        compose.waitForIdle()
        assertEquals("Full height", state())
        assertEquals(full, insets.bottom, 1f)
        compose.onNodeWithTag(DriveTags.card(2)).assertIsSelected()
        compose.onNodeWithTag(DriveTags.card(1)).performClick()
        compose.waitForIdle()
        assertTrue(events.contains(DriveEvent.Nav(NavIntent.Choose(1))))
        compose.onNodeWithTag(SHEET_HANDLE_TAG).performClick()
        compose.waitForIdle()
        assertEquals("Lowered", state())
    }
}
