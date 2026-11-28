package io.github.spysyweeb.beamline.feature.search

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.longClick
import io.github.spysyweeb.beamline.search.hours.OpenState
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.ui.theme.BeamlineTheme
import io.github.spysyweeb.beamline.ui.theme.SHEET_HANDLE_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Rule D10 (batch F, docs/next-round.md item 15) on the device: the results list's three heights and
 * the place sheet's three (batch G: its details height), moved by a drag on the handle, a drag on the rows (at their top), a tap and
 * the screen reader's actions; the handle's description and state.
 */
@RunWith(AndroidJUnit4::class)
class SheetHeightsScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val events = ArrayList<Any>()
    private val rows = (0 until 20).map { ResultRowUi("r$it", "fuel", "Sheetz $it", "Peach Street, Erie", "${it + 1} mi", null) }
    /** A query of each test's own: the list's height is remembered for the latest results (ResultsHeightMemory). */
    private fun results(query: String) = ResultsUi(query, rows, emptyList())
    private fun show(c: @Composable () -> Unit) {
        compose.setContent { BeamlineTheme(night = false) { c() } }
        compose.waitForIdle()
    }

    private fun handle() = compose.onNodeWithTag(SHEET_HANDLE_TAG)
    private fun state(): String = handle().fetchSemanticsNode().config[SemanticsProperties.StateDescription]
    private fun height(tag: String) = compose.onNodeWithTag(tag).getBoundsInRoot().height
    private fun SemanticsNodeInteraction.action(label: String) {
        val a = fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == label }
        compose.runOnUiThread { a.action() }
        compose.waitForIdle()
    }
    private fun rowsShown() = compose.onAllNodes(hasTestTag(RESULTS_ROWS_TAG)).fetchSemanticsNodes().isNotEmpty()

    /** P2 and P6 (e), 0.1.40: "Other matches" over the name-only rows, in the window and on the search screen; the count line says where. */
    @Test
    fun otherMatchesHeadingAndNearLine() {
        val five = (0 until 5).map { ResultRowUi("h$it", "hospital", "Hospital $it", "Erie", "${it + 1} mi", null, otherMatch = it >= 3) }
        show { Box(Modifier.size(360.dp, 700.dp)) { ResultsSheetContent(ResultsUi("hospital", five, emptyList(), nearText = "Near Corry"), { events.add(it) }, Modifier.align(Alignment.BottomCenter)) } }
        compose.onNodeWithText("5 places · Near Corry").assertIsDisplayed()
        val heading = compose.onAllNodesWithText("Other matches").fetchSemanticsNodes()
        assertEquals(1, heading.size)
        assertTrue(heading[0].config.contains(SemanticsProperties.Heading))
        val top = heading[0].boundsInRoot.top
        val row2 = compose.onNodeWithContentDescription("Hospital 2", substring = true).fetchSemanticsNode().boundsInRoot.top
        val row3 = compose.onNodeWithContentDescription("Hospital 3", substring = true).fetchSemanticsNode().boundsInRoot.top
        assertTrue("heading $top between rows $row2 and $row3", top in row2..row3)
        compose.onNodeWithContentDescription("Hospital 3", substring = true).performClick()
        assertEquals(listOf<Any>(ResultsEvent.Select(3)), events)
    }

    /** P2 (0.1.40): the same heading in the search screen's list. */
    @Test
    fun otherMatchesHeadingOnTheSearchScreen() {
        val five = (0 until 5).map { ResultRowUi("h$it", "hospital", "Hospital $it", "Erie", "${it + 1} mi", null, otherMatch = it >= 3) }
        show { SearchScreen(SearchUi(query = "hospital", phase = SearchPhase.RESULTS, results = five), {}) }
        assertEquals(1, compose.onAllNodesWithText("Other matches").fetchSemanticsNodes().size)
    }

    @Test
    fun resultsListHeightsByHandleTapAndActions() {
        show { Box(Modifier.size(360.dp, 700.dp)) { ResultsSheetContent(results("sheetz handle"), { events.add(it) }, Modifier.align(Alignment.BottomCenter)) } }
        val desc = handle().fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
        assertTrue(desc.toString(), desc.contains(Texts.RESULTS_LIST))
        assertEquals("Half open", state())
        val middle = height(RESULTS_SHEET_TAG)
        // a drag down on the handle: the lowest height, only the handle, the query and the count
        handle().performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals("Lowered", state())
        assertTrue(!rowsShown())
        compose.onNodeWithText("20 places").assertIsDisplayed()
        val low = height(RESULTS_SHEET_TAG)
        assertTrue("low $low < middle $middle", low < middle)
        assertTrue("low $low is about the header", low < 120.dp)
        // a tap raises one height; from the highest a tap goes back to the lowest
        handle().performClick(); compose.waitForIdle()
        assertEquals("Half open", state())
        handle().performClick(); compose.waitForIdle()
        assertEquals("Full height", state())
        assertTrue(height(RESULTS_SHEET_TAG) > middle)
        handle().performClick(); compose.waitForIdle()
        assertEquals("Lowered", state())
        // the screen reader's actions, no drag
        handle().action("Raise the sheet")
        assertEquals("Half open", state())
        handle().action("Lower the sheet")
        assertEquals("Lowered", state())
        // the close button still closes
        compose.onNodeWithTag(SHEET_HANDLE_TAG).assertIsDisplayed()
    }

    @Test
    fun resultsListDraggedFromTheRows() {
        show { Box(Modifier.size(360.dp, 700.dp)) { ResultsSheetContent(results("sheetz rows"), { events.add(it) }, Modifier.align(Alignment.BottomCenter)) } }
        // rows at their top: a drag down on them lowers the list
        compose.onNodeWithTag(RESULTS_ROWS_TAG).performTouchInput { swipeDown() }
        compose.waitForIdle()
        assertEquals("Lowered", state())
        handle().performClick(); compose.waitForIdle()
        // a drag up on the rows raises the list first
        compose.onNodeWithTag(RESULTS_ROWS_TAG).performTouchInput { swipeUp(startY = centerY, endY = centerY - 150f) }
        compose.waitForIdle()
        assertEquals("Full height", state())
        // at the highest a drag up scrolls the rows; scrolled, a short drag down scrolls them back, the list stays
        compose.onNodeWithTag(RESULTS_ROWS_TAG).performScrollToIndex(12)
        compose.onNodeWithTag(RESULTS_ROWS_TAG).performTouchInput { swipeDown(startY = centerY, endY = centerY + 150f) }
        compose.waitForIdle()
        assertEquals("Full height", state())
        // a row still opens its place
        compose.onNodeWithText("Sheetz 12", useUnmergedTree = true).performClick()
        compose.waitForIdle()
        assertTrue(events.toString(), events.contains(ResultsEvent.Select(12)))
    }

    /**
     * P1 (0.1.40, T5 of landscape.md): with a navigation bar or a cutout at the sides the place window's and the results
     * list's content keeps clear of them, while their surfaces still reach the edges.
     */
    @Test
    fun placeWindowAndResultsKeepTheirContentOffTheBars() {
        val place = Place("Erie Maritime Museum", GeoPoint(42.13646, -80.08718), PlaceSource.SEARCH, "100 East Front Street, Erie", "museum")
        val ui = PlaceSheetUi(place, "museum", place.name, place.subtitle, "2.3 mi", null, listOf(PlaceAction.DIRECTIONS, PlaceAction.SAVE))
        val bars = WindowInsets(left = 40.dp, right = 48.dp)
        var list by mutableStateOf(false)
        show {
            Box(Modifier.size(400.dp, 600.dp)) {
                if (list) ResultsSheetContent(results("bars"), {}, Modifier.align(Alignment.BottomCenter), insets = bars)
                else PlaceSheetContent(ui, {}, Modifier.align(Alignment.BottomCenter), insets = bars)
            }
        }
        val d = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        fun px(v: Float) = v * d
        val sheet = compose.onNodeWithTag(PLACE_SHEET_TAG).getBoundsInRoot()
        val left = sheet.left.value * d
        assertTrue("the surface spans the box: $sheet", kotlin.math.abs((sheet.right - sheet.left).value - 400f) < 1f)
        val actions = compose.onNodeWithTag(PLACE_PEEK_ACTIONS_TAG).fetchSemanticsNode().boundsInRoot
        assertTrue("actions start at ${actions.left - left} px", actions.left - left >= px(56f) - 1f)
        val close = compose.onNodeWithContentDescription("Close").fetchSemanticsNode().boundsInRoot
        assertTrue("Close ends at ${close.right - left} px", close.right - left <= px(352f) + 1f)
        compose.runOnUiThread { list = true }
        compose.waitForIdle()
        val rl = compose.onNodeWithTag(RESULTS_SHEET_TAG).getBoundsInRoot()
        assertTrue("the list spans the box: $rl", kotlin.math.abs((rl.right - rl.left).value - 400f) < 1f)
        val x = compose.onNodeWithContentDescription(Texts.CLOSE_RESULTS).fetchSemanticsNode().boundsInRoot
        assertTrue("Close results ends at ${x.right - rl.left.value * d} px", x.right - rl.left.value * d <= px(352f) + 1f)
    }

    /** Item 47 (the owner, 2026-11-25): a new place opens at peek, with its name, Directions and Save. */
    @Test
    fun placeSheetOpensAtPeekWithItsButtons() {
        val place = Place("Erie Maritime Museum", GeoPoint(42.13646, -80.08718), PlaceSource.SEARCH, "100 East Front Street, Erie", "museum")
        val ui = PlaceSheetUi(place, "museum", place.name, place.subtitle, "2.3 mi", null, listOf(PlaceAction.DIRECTIONS, PlaceAction.SAVE))
        show { Box(Modifier.size(360.dp, 700.dp)) { PlaceSheetContent(ui, { events.add(it) }, Modifier.align(Alignment.BottomCenter)) } }
        assertEquals("Lowered", state())
        compose.onNodeWithTag(PLACE_PEEK_ACTIONS_TAG).assertIsDisplayed()
        compose.onNodeWithText(Texts.DIRECTIONS).assertIsDisplayed()
        compose.onNodeWithText(Texts.SAVE).assertIsDisplayed()
        compose.onNodeWithText("Erie Maritime Museum").assertIsDisplayed()
        val low = height(PLACE_SHEET_TAG)
        val desc = handle().fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
        assertTrue(desc.toString(), desc.contains(Texts.PLACE_DETAILS))
        // a drag up anywhere on it raises it; the peek buttons give way to the full row
        compose.onNodeWithTag(PLACE_SHEET_TAG).performTouchInput { swipeUp(startY = centerY, endY = centerY - 300f) }
        compose.waitForIdle()
        assertTrue(state() != "Lowered")
        assertTrue(height(PLACE_SHEET_TAG) > low)
        assertEquals(0, compose.onAllNodes(hasTestTag(PLACE_PEEK_ACTIONS_TAG)).fetchSemanticsNodes().size)
        compose.onNodeWithText(Texts.DIRECTIONS).performClick()
        assertTrue(events.toString(), events.contains(PlaceSheetEvent.Act(PlaceAction.DIRECTIONS)))
    }

    /** Item 47: Directions with the drive time ("Directions · 6 min") at peek and on the full row. */
    @Test
    fun placeSheetDirectionsShowsTheDriveTime() {
        val place = Place("Erie Maritime Museum", GeoPoint(42.13646, -80.08718), PlaceSource.SEARCH, "100 East Front Street, Erie", "museum")
        val ui = PlaceSheetUi(place, "museum", place.name, place.subtitle, "2.3 mi", null, listOf(PlaceAction.DIRECTIONS, PlaceAction.SAVE), driveTimeText = "6 min")
        show { Box(Modifier.size(360.dp, 700.dp)) { PlaceSheetContent(ui, { events.add(it) }, Modifier.align(Alignment.BottomCenter)) } }
        compose.onNodeWithText("${Texts.DIRECTIONS} · 6 min").assertIsDisplayed().performClick()
        assertTrue(events.toString(), events.contains(PlaceSheetEvent.Act(PlaceAction.DIRECTIONS)))
        compose.onNodeWithTag(PLACE_SHEET_TAG).performTouchInput { swipeUp(startY = centerY, endY = centerY - 300f) }
        compose.waitForIdle()
        compose.onNodeWithText("${Texts.DIRECTIONS} · 6 min").assertIsDisplayed()
    }

    private fun detailsUi(canOpen: Boolean): PlaceSheetUi {
        val place = Place("Sheetz", GeoPoint(42.1, -80.07), PlaceSource.SEARCH, "4662 West Ridge Road, Erie", "convenience", 7L, "us-pa")
        val week = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").map { HoursDayUi(it, "6 AM–10 PM", it == "Wed") }
        return PlaceSheetUi(
            place, "fuel", place.name, place.subtitle, "2.3 mi", null,
            if (canOpen) listOf(PlaceAction.DIRECTIONS, PlaceAction.SAVE, PlaceAction.CALL, PlaceAction.WEBSITE) else listOf(PlaceAction.DIRECTIONS),
            hours = HoursUi(OpenState.OPEN, HoursTexts.OPEN, "until 10 PM", week, HoursTexts.HOLIDAYS),
            phone = "+1 814-555-0100".takeIf { canOpen }, website = "https://example.com/menu/".takeIf { canOpen },
            phones = HoursTexts.phones("+1 814-555-0100; 814.555.0101"), websites = HoursTexts.websites("https://example.com/menu/"),
            address = "4662 West Ridge Road, Erie, PA 16506", category = "Convenience store", canOpenOutside = canOpen,
        )
    }

    /** Batch G (item 20): the status line raises the sheet to the details height; a number opens the dialer, a long press copies. */
    @Test
    fun placeSheetDetailsHeight() {
        show { Box(Modifier.size(360.dp, 700.dp)) { PlaceSheetContent(detailsUi(true), { events.add(it) }, Modifier.align(Alignment.BottomCenter)) } }
        assertEquals("Lowered", state()) // item 47: opens at peek; one raise to the middle height
        handle().action("Raise the sheet")
        assertEquals("Half open", state())
        assertEquals(0, compose.onAllNodes(hasTestTag(PLACE_DETAILS_TAG)).fetchSemanticsNodes().size)
        val middle = height(PLACE_SHEET_TAG)
        compose.onNodeWithTag(PLACE_STATUS_TAG).performClick()
        compose.waitForIdle()
        assertEquals("Full height", state())
        assertTrue(height(PLACE_SHEET_TAG) > middle)
        compose.onNodeWithText("Convenience store").assertIsDisplayed()
        compose.onNodeWithText("Sun").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText(HoursTexts.HOLIDAYS).performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("detail-phone-1").performScrollTo()
        compose.onNodeWithText("(814) 555-0101").assertIsDisplayed()
        compose.onNodeWithTag("detail-phone-0").performScrollTo().performClick()
        assertTrue(events.toString(), events.contains(PlaceSheetEvent.OpenOutside(PlaceAction.CALL, "+18145550100")))
        compose.onNodeWithTag("detail-website-0").performScrollTo()
        compose.onNodeWithText("example.com/menu").assertIsDisplayed()
        compose.onNodeWithTag("detail-website-0").performClick()
        assertTrue(events.toString(), events.contains(PlaceSheetEvent.OpenOutside(PlaceAction.WEBSITE, "https://example.com/menu/")))
        compose.onNodeWithTag("detail-address").performScrollTo().performTouchInput { longClick() }
        compose.waitForIdle()
        compose.onNodeWithText(Texts.COPIED).assertIsDisplayed()
        // the screen reader's action and a tap on the handle: from the highest back to the lowest
        handle().action("Lower the sheet")
        assertEquals("Half open", state())
        handle().action("Raise the sheet")
        assertEquals("Full height", state())
        handle().performClick()
        compose.waitForIdle()
        assertEquals("Lowered", state())
    }

    /** Batch G: while guiding the numbers and web addresses are written out but a tap does nothing. */
    @Test
    fun placeSheetDetailsWhileGuiding() {
        show { Box(Modifier.size(360.dp, 700.dp)) { PlaceSheetContent(detailsUi(false), { events.add(it) }, Modifier.align(Alignment.BottomCenter)) } }
        handle().action("Raise the sheet") // item 47: from peek, two raises to the details height
        handle().action("Raise the sheet")
        assertEquals("Full height", state())
        compose.onNodeWithTag("detail-phone-0").performScrollTo()
        compose.onNodeWithText("(814) 555-0100").assertIsDisplayed().performClick()
        compose.onNodeWithTag("detail-website-0").performScrollTo().performClick()
        compose.onNodeWithTag("detail-address").performScrollTo().performTouchInput { longClick() }
        compose.waitForIdle()
        assertTrue(events.toString(), events.none { it is PlaceSheetEvent.OpenOutside })
        assertEquals(0, compose.onAllNodes(hasText(Texts.COPIED)).fetchSemanticsNodes().size)
        assertEquals(0, compose.onAllNodes(hasText(Texts.CALL)).fetchSemanticsNodes().size)
    }
}
