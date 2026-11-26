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
import androidx.compose.ui.test.performScrollTo
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
        // item 47: a new trip's sheet opens at the middle height: the small cards and Start
        assertEquals("Half open", state())
        val middle = insets.bottom
        compose.onNodeWithTag(DriveTags.lowCard(1)).assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag(DriveTags.lowCard(2)).assertIsDisplayed()
        compose.onNodeWithTag(DriveTags.START).assertIsDisplayed()
        // a route chosen at the middle height
        compose.onNodeWithTag(DriveTags.lowCard(2)).performClick()
        compose.waitForIdle()
        assertTrue(events.toString(), events.contains(DriveEvent.Nav(NavIntent.Choose(2))))
        compose.onNodeWithTag(DriveTags.lowCard(2)).assertIsSelected()
        // a drag down: the peek, where it arrives, "2 routes" and Start; the map gets the most room
        compose.onNodeWithTag(DriveTags.ROUTE_SHEET).performTouchInput { swipeDown(startY = top + 10f, endY = top + 400f) }
        compose.waitForIdle()
        assertEquals("Lowered", state())
        assertEquals(0, compose.onAllNodes(hasTestTag(DriveTags.lowCard(1))).fetchSemanticsNodes().size)
        compose.onNodeWithTag(ROUTES_CHIP_TAG).assertIsDisplayed()
        compose.onNodeWithTag(DriveTags.START).assertIsDisplayed()
        val low = insets.bottom
        assertTrue("low $low < middle $middle", low > 0f && low < middle)
        // Start works at the lowest height
        compose.onNodeWithTag(DriveTags.START).performClick()
        assertTrue(events.contains(DriveEvent.Nav(NavIntent.Start())))
        // the chip goes back to the routes
        compose.onNodeWithTag(ROUTES_CHIP_TAG).performClick()
        compose.waitForIdle()
        assertEquals("Half open", state())
        // up by the screen reader's action: full height, the full cards and the rest
        val raise = compose.onNodeWithTag(SHEET_HANDLE_TAG).fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == "Raise the sheet" }
        compose.runOnUiThread { raise.action() }
        compose.waitForIdle()
        assertEquals("Full height", state())
        assertTrue("middle $middle < full ${insets.bottom}", insets.bottom > middle)
        compose.onNodeWithTag(DriveTags.card(2)).assertIsSelected()
        compose.onNodeWithTag(DriveTags.card(1)).performClick()
        compose.waitForIdle()
        assertTrue(events.contains(DriveEvent.Nav(NavIntent.Choose(1))))
        // a tap on the handle at the highest goes back to the lowest
        compose.onNodeWithTag(SHEET_HANDLE_TAG).performClick()
        compose.waitForIdle()
        assertEquals("Lowered", state())
    }

    /** Item 47: the steps preview at full height; a tap on a step looks at it and lowers the sheet to the routes. */
    @Test
    fun stepsPreviewAtFullHeight() {
        val point = io.github.spysyweeb.beamline.api.model.GeoPoint(42.1, -80.08)
        val steps = (0 until 7).map { i -> StepUi(io.github.spysyweeb.beamline.api.nav.ManeuverKind.RIGHT, "Turn right onto Street $i", "0.$i mi", point) }
        ui = DriveUi(planning = sheet(1).copy(steps = steps))
        compose.setContent {
            BeamlineTheme(night = false) {
                Box(Modifier.size(411.dp, 891.dp)) { DriveOverlayContent(ui, onEvent = { e -> events.add(e) }, onInsets = { insets = it }) }
            }
        }
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodes(hasTestTag(stepTag(0))).fetchSemanticsNodes().size)
        compose.raiseSheetToFull()
        compose.onNodeWithTag(stepTag(0)).performScrollTo().assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasTestTag(stepTag(STEPS_SHOWN))).fetchSemanticsNodes().size)
        compose.onNodeWithTag(SHOW_ALL_STEPS_TAG).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(stepTag(6)).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag(stepTag(2)).performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(events.toString(), events.contains(DriveEvent.ShowStep(point)))
        assertEquals("Half open", state())
    }
}
