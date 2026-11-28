package io.github.spysyweeb.beamline.feature.drive

import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.Alignment
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import io.github.spysyweeb.beamline.ui.theme.SheetTexts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasAnyAncestor
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
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.height
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        // P21: the step's place in the list goes with it
        assertTrue(events.toString(), events.contains(DriveEvent.ShowStep(point, 2)))
        assertEquals("Half open", state())
    }

    /** The app's answer to the step events (AppFlows.shownStep, AppRoot): the step shown is marked in the sheet. */
    private fun stepHost(e: DriveEvent) {
        events.add(e)
        when (e) {
            is DriveEvent.ShowStep -> ui = ui.copy(planning = ui.planning!!.copy(shownStep = e.index))
            DriveEvent.CloseStep -> ui = ui.copy(planning = ui.planning!!.copy(shownStep = null))
            else -> Unit
        }
    }

    /** The handle's screen-reader action ("Raise the sheet", "Lower the sheet"): one height at a time, no drag to land. */
    private fun handleAction(label: String) {
        val action = compose.onNodeWithTag(SHEET_HANDLE_TAG).fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == label }
        compose.runOnUiThread { action.action() }
        compose.waitForIdle()
    }

    /**
     * A box wider than the portrait test window (the side panel's layout): laid out from the window's top start, so the
     * panel (the box's start) is on screen and the rest runs off to the right; [fontScale] as the system's font size.
     */
    @Composable
    private fun Wide(width: Dp, height: Dp, fontScale: Float, content: @Composable () -> Unit) {
        CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
            BeamlineTheme(night = false) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).size(width, height)) { content() }
                }
            }
        }
    }

    private fun stopRow(i: Int, name: String) = StopRowUi(i, name, "Peach Street, Erie", reached = false, canRemove = true, canMoveUp = i > 0, canMoveDown = false)

    /**
     * Play test P21: a step tapped at full height is shown at the middle height ("Step 3 of 7", Previous / Next, Cancel),
     * the back arrow goes up to the list with the step marked, and Back there lets it go (the cards again).
     */
    @Test
    fun stepViewSaysWhichStepAndMovesThroughThem() {
        ui = DriveUi(planning = Exit24.routeSheet(steps = 7))
        var back: androidx.activity.OnBackPressedDispatcher? = null
        compose.setContent {
            back = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            BeamlineTheme(night = false) {
                Box(Modifier.size(411.dp, 891.dp)) { DriveOverlayContent(ui, onEvent = ::stepHost, onInsets = { insets = it }) }
            }
        }
        compose.waitForIdle()
        compose.raiseSheetToFull()
        compose.onNodeWithTag(stepTag(2)).performScrollTo().performClick()
        compose.waitForIdle()
        assertTrue(events.toString(), events.contains(DriveEvent.ShowStep(Exit24.stepPoint(2), 2)))
        assertEquals("Half open", state())
        compose.onNodeWithText("Step 3 of 7").assertIsDisplayed()
        compose.saveShotOf(DriveTags.ROUTE_SHEET, "sheet-step-view-day")
        compose.onNodeWithTag(DriveTags.STEP_PREVIOUS).assertIsEnabled()
        compose.onNodeWithTag(DriveTags.STEP_NEXT).assertIsEnabled()
        // P20 (decisions: Cancel at every height): also while a step is shown
        compose.onNodeWithTag(DriveTags.CANCEL).assertIsDisplayed()
        compose.onNodeWithTag(DriveTags.START).assertIsDisplayed()
        compose.onNodeWithTag(DriveTags.STEP_NEXT).performClick()
        compose.waitForIdle()
        assertEquals(DriveEvent.ShowStep(Exit24.stepPoint(3), 3), events.last())
        compose.onNodeWithText("Step 4 of 7").assertIsDisplayed()
        compose.onNodeWithTag(DriveTags.STEP_PREVIOUS).performClick()
        compose.waitForIdle()
        assertEquals(DriveEvent.ShowStep(Exit24.stepPoint(2), 2), events.last())
        compose.onNodeWithTag(DriveTags.STEP_NEXT).performClick()
        compose.waitForIdle()
        // the back arrow: the full list, the step shown still marked
        compose.onNodeWithTag(DriveTags.STEP_BACK).performClick()
        compose.waitForIdle()
        assertEquals("Full height", state())
        compose.onNodeWithTag(stepTag(3)).assertIsDisplayed().assertIsSelected()
        compose.onNodeWithTag(stepTag(2)).assertIsNotSelected()
        // P52 with P21: the marked step is in view clear of the fade over the part's last lines (more is below it)
        val marked = compose.onNodeWithTag(stepTag(3)).getUnclippedBoundsInRoot()
        val fade = compose.onNodeWithTag(DriveTags.MORE_BELOW).getUnclippedBoundsInRoot()
        assertTrue("marked step $marked, fade $fade", marked.bottom <= fade.top + 0.5.dp)
        compose.saveShotOf(DriveTags.ROUTE_SHEET, "sheet-step-marked-day")
        // Back at full height lets the step go (the whole route again) and steps down to the cards
        compose.runOnUiThread { back!!.onBackPressed() }
        compose.waitForIdle()
        assertEquals(DriveEvent.CloseStep, events.last())
        assertEquals("Half open", state())
        compose.onNodeWithTag(DriveTags.lowCard(1)).assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasTestTag(DriveTags.STEP_VIEW)).fetchSemanticsNodes().size)
    }

    /**
     * P21 on its side at font 1.3 (the side panel opens at full height): a tapped step stays in the list, marked; lowered
     * to the middle height the step view keeps Cancel and Start in view, with a long step and a warning row.
     */
    @Test
    fun stepViewKeepsStartInAShortWindowAtLargeFont() {
        val sheet = Exit24.routeSheet(steps = 7)
        val long = "Turn right onto General Strong Vincent Memorial Highway toward Waterford, Edinboro and Meadville"
        ui = DriveUi(planning = sheet.copy(steps = sheet.steps.mapIndexed { i, s -> if (i == 2) s.copy(text = long) else s }, warnings = listOf("No route avoids tolls")))
        compose.setContent { Wide(891.dp, 411.dp, 1.3f) { DriveOverlayContent(ui, onEvent = ::stepHost, onInsets = { insets = it }) } }
        compose.waitForIdle()
        assertEquals("Full height", state())
        compose.onNodeWithTag(stepTag(2)).performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(DriveEvent.ShowStep(Exit24.stepPoint(2), 2), events.last())
        // the side panel keeps its list: the step outlined there
        assertEquals("Full height", state())
        compose.onNodeWithTag(stepTag(2)).assertIsSelected()
        handleAction(SheetTexts.LOWER)
        assertEquals("Half open", state())
        compose.onNodeWithTag(DriveTags.STEP_VIEW).assertIsDisplayed()
        compose.assertWhollyShown(DriveTags.CANCEL)
        compose.assertWhollyShown(DriveTags.START)
        compose.saveShotOf(DriveTags.ROUTE_SHEET, "land-sheet-step-view-font130-day")
    }

    /** Play test P20: Cancel at the lowest height too, and the middle height says where the preview starts and its stops. */
    @Test
    fun cancelAndContextBelowFullHeight() {
        ui = DriveUi(planning = sheet(1).copy(contextLine = "From Butler · 1 stop"))
        compose.setContent {
            BeamlineTheme(night = false) {
                Box(Modifier.size(411.dp, 891.dp)) { DriveOverlayContent(ui, onEvent = { e -> events.add(e) }, onInsets = { insets = it }) }
            }
        }
        compose.waitForIdle()
        assertEquals("Half open", state())
        compose.onNodeWithTag(DriveTags.CONTEXT_LINE).assertIsDisplayed().assertTextEquals("From Butler · 1 stop")
        compose.onNodeWithText("To Krispy Kreme").assertIsDisplayed()
        compose.saveShotOf(DriveTags.ROUTE_SHEET, "sheet-middle-context-day")
        handleAction(SheetTexts.LOWER)
        assertEquals("Lowered", state())
        compose.saveShotOf(DriveTags.ROUTE_SHEET, "sheet-peek-cancel-day")
        compose.onNodeWithTag(DriveTags.CANCEL).assertIsDisplayed().performClick()
        assertEquals(DriveEvent.Nav(NavIntent.End), events.last())
    }

    /** Play test P19: "Guidance starts from where you are." is read above Start, at every height. */
    @Test
    fun startNoteAboveStart() {
        ui = DriveUi(planning = sheet(1).copy(startNote = DriveTexts.STARTS_FROM_HERE))
        compose.setContent {
            BeamlineTheme(night = false) {
                Box(Modifier.size(411.dp, 891.dp)) { DriveOverlayContent(ui, onEvent = { e -> events.add(e) }, onInsets = { insets = it }) }
            }
        }
        compose.waitForIdle()
        fun noteAboveStart(height: String) {
            assertEquals(height, state())
            val note = compose.onNodeWithTag(DriveTags.START_NOTE).assertIsDisplayed().getUnclippedBoundsInRoot()
            val start = compose.onNodeWithTag(DriveTags.START).getUnclippedBoundsInRoot()
            assertTrue("$height: note $note, Start $start", note.bottom <= start.top + 0.5.dp)
        }
        noteAboveStart("Half open")
        compose.saveShotOf(DriveTags.ROUTE_SHEET, "sheet-start-note-day")
        handleAction(SheetTexts.LOWER)
        noteAboveStart("Lowered")
        compose.raiseSheetToFull()
        noteAboveStart("Full height")
    }

    /** Play test P48: after a change of the stops the full sheet shows them from the top (the kept offset hid the new first stop). */
    @Test
    fun aChangeOfTheStopsShowsThemFromTheTop() {
        ui = DriveUi(planning = Exit24.routeSheet(steps = 7))
        compose.setContent {
            BeamlineTheme(night = false) {
                Box(Modifier.size(411.dp, 640.dp)) { DriveOverlayContent(ui, onEvent = { e -> events.add(e) }, onInsets = { insets = it }) }
            }
        }
        compose.waitForIdle()
        compose.raiseSheetToFull()
        compose.onNodeWithTag(SHOW_ALL_STEPS_TAG).performScrollTo().performClick()
        compose.waitForIdle()
        compose.onNodeWithTag(DriveTags.stopLine(0)).assertIsNotDisplayed()
        compose.runOnUiThread { ui = DriveUi(planning = ui.planning!!.copy(stops = listOf(stopRow(0, "Peach Street Beer"), stopRow(1, "Krispy Kreme")))) }
        compose.waitForIdle()
        compose.onNodeWithTag(DriveTags.stopLine(0)).assertIsDisplayed()
    }

    /**
     * P48 on its side at font 1.3: the stops change (the replan running), then the new routes come with another card
     * selected; that card does not scroll the new first stop away again.
     */
    @Test
    fun theReplanAfterAStopChangeKeepsTheStopsInView() {
        ui = DriveUi(planning = Exit24.routeSheet(selected = 2, steps = 7))
        compose.setContent { Wide(891.dp, 411.dp, 1.3f) { DriveOverlayContent(ui, onEvent = { e -> events.add(e) }, onInsets = { insets = it }) } }
        compose.waitForIdle()
        assertEquals("Full height", state())
        compose.onNodeWithTag(SHOW_ALL_STEPS_TAG).performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithTag(DriveTags.stopLine(0)).assertIsNotDisplayed()
        compose.runOnUiThread {
            ui = DriveUi(planning = ui.planning!!.copy(stops = listOf(stopRow(0, "Peach Street Beer"), stopRow(1, "Krispy Kreme")), busy = true))
        }
        compose.waitForIdle()
        compose.runOnUiThread {
            ui = DriveUi(
                planning = ui.planning!!.copy(
                    busy = false,
                    cards = listOf(
                        RouteCardUi(3, "Fastest", "14 min", "5.6 mi", "Arrive 3:40 PM", "via Peach St", listOf("Highways"), selected = false),
                        RouteCardUi(4, "Simpler", "15 min", "5.8 mi", "Arrive 3:41 PM", "via Greengarden Blvd", emptyList(), selected = true),
                    ),
                ),
            )
        }
        compose.waitForIdle()
        compose.onNodeWithTag(DriveTags.stopLine(0)).assertIsDisplayed()
    }

    /**
     * P52 (review, 0.1.40): on its side at font 1.3 the selected card is brought into view clear of the fade over the
     * part's last lines: when the sheet opens with the lower card selected, and when a card is chosen.
     */
    @Test
    fun theSelectedCardIsClearOfTheFade() {
        ui = DriveUi(planning = Exit24.routeSheet(selected = 2, steps = 7))
        val choose = { e: DriveEvent ->
            events.add(e)
            val id = ((e as? DriveEvent.Nav)?.intent as? NavIntent.Choose)?.choiceId
            if (id != null) ui = ui.copy(planning = ui.planning!!.let { s -> s.copy(cards = s.cards.map { it.copy(selected = it.choiceId == id) }) })
        }
        compose.setContent { Wide(891.dp, 411.dp, 1.3f) { DriveOverlayContent(ui, onEvent = choose, onInsets = { insets = it }) } }
        compose.waitForIdle()
        assertEquals("Full height", state())
        fun clearOfTheFade(id: Long) {
            compose.waitForIdle()
            val card = compose.onNodeWithTag(DriveTags.card(id)).assertIsSelected().getUnclippedBoundsInRoot()
            val fade = compose.onNodeWithTag(DriveTags.MORE_BELOW).getUnclippedBoundsInRoot()
            assertTrue("card $id $card, fade $fade", card.bottom <= fade.top + 0.5.dp)
        }
        clearOfTheFade(2)
        compose.onNodeWithTag(DriveTags.card(1)).performScrollTo().performClick()
        clearOfTheFade(1)
        compose.onNodeWithTag(DriveTags.card(2)).performScrollTo().performClick()
        clearOfTheFade(2)
        compose.saveShotOf(DriveTags.ROUTE_SHEET, "land-sheet-card-clear-font130-day")
    }

    /**
     * Rule D10 (review, 0.1.40): a new trip planned while a preview shows (PLANNING to PLANNING: a map place's Directions,
     * a link handed over by another app) opens at the middle height as every new trip's sheet does; a change of the same
     * trip (the ends swapped, a stop added: replanned with the old routes still shown) keeps the height.
     */
    @Test
    fun aNewTripPlannedDuringAPreviewOpensAtTheMiddle() {
        ui = DriveUi(planning = Exit24.routeSheet(steps = 7))
        compose.setContent {
            BeamlineTheme(night = false) {
                Box(Modifier.size(411.dp, 891.dp)) { DriveOverlayContent(ui, onEvent = { e -> events.add(e) }, onInsets = { insets = it }) }
            }
        }
        compose.waitForIdle()
        compose.raiseSheetToFull()
        // the ends swapped: from Krispy Kreme back to where the swap was made (none of the stops as before, the routes kept)
        compose.runOnUiThread {
            ui = DriveUi(planning = ui.planning!!.copy(fromText = "Krispy Kreme", stops = listOf(StopRowUi(0, "Starting point", "Where you swapped", false, false, false, false)), busy = true))
        }
        compose.waitForIdle()
        assertEquals("Full height", state())
        // a stop added
        compose.runOnUiThread { ui = DriveUi(planning = ui.planning!!.copy(stops = listOf(stopRow(0, "Peach Street Beer"), stopRow(1, "Starting point")), busy = true)) }
        compose.waitForIdle()
        assertEquals("Full height", state())
        // a new trip (the session's newTrip: from the position, the new place, no routes yet)
        compose.runOnUiThread {
            ui = DriveUi(
                planning = Exit24.routeSheet(steps = 7).copy(
                    stops = listOf(StopRowUi(0, "Erie Zoo", "West 38th Street, Erie", false, false, false, false)), cards = emptyList(), steps = emptyList(), busy = true,
                ),
            )
        }
        compose.waitForIdle()
        assertEquals("Half open", state())
        // its routes come: still the middle height
        compose.runOnUiThread { ui = DriveUi(planning = ui.planning!!.copy(cards = Exit24.routeSheet().cards, busy = false)) }
        compose.waitForIdle()
        assertEquals("Half open", state())
        compose.onNodeWithTag(DriveTags.lowCard(1)).assertIsDisplayed()
    }

    /** Item 47: the stops editor opens full; lowered it keeps its title, the count, Done and Add stop; it never closes by a drag. */
    @Test
    fun stopsEditorTwoHeights() {
        val rows = (0 until 3).map { i -> StopRowUi(i, "Stop $i", "Street $i", reached = false, canMoveUp = i > 0, canMoveDown = i < 2, canRemove = true) }
        val editor = StopsEditorUi(rows, canOptimize = true, busy = false)
        compose.setContent {
            BeamlineTheme(night = false) {
                Box(Modifier.size(411.dp, 891.dp)) { StopsEditor(editor, onEvent = { e -> events.add(e) }, modifier = Modifier.align(Alignment.BottomCenter)) }
            }
        }
        compose.waitForIdle()
        assertEquals("Full height", state())
        compose.onNodeWithTag(DriveTags.stopRow(2)).assertIsDisplayed()
        compose.onNodeWithTag(DriveTags.STOPS_EDITOR).performTouchInput { swipeDown(startY = top + 10f, endY = top + 400f) }
        compose.waitForIdle()
        assertEquals("Lowered", state())
        assertEquals(0, compose.onAllNodes(hasTestTag(DriveTags.stopRow(0))).fetchSemanticsNodes().size)
        compose.onNodeWithText("3 stops").assertIsDisplayed()
        compose.onNodeWithTag(DriveTags.ADD_STOP).assertIsDisplayed()
        // another drag down: still there (the lowest height is the floor)
        compose.onNodeWithTag(DriveTags.STOPS_EDITOR).performTouchInput { swipeDown(startY = top + 10f, endY = top + 300f) }
        compose.waitForIdle()
        assertEquals("Lowered", state())
        assertTrue(events.toString(), DriveEvent.CloseStopsEditor !in events)
        compose.onNodeWithTag(SHEET_HANDLE_TAG).performClick()
        compose.waitForIdle()
        assertEquals("Full height", state())
        compose.onNodeWithTag(DriveTags.CLOSE_STOPS).performClick()
        assertTrue(events.contains(DriveEvent.CloseStopsEditor))
    }

    /**
     * Play test P49 (review, 0.1.40): lowered, the stops editor's Done and buttons stay where they are when Best order's
     * note comes and when it goes, and when Best order is no longer offered (a stop reached while guided).
     */
    @Test
    fun loweredStopsEditorKeepsDoneStill() {
        val rows = (0 until 3).map { i -> StopRowUi(i, "Stop $i", "Street $i", reached = false, canMoveUp = i > 0, canMoveDown = i < 2, canRemove = true) }
        var editor by mutableStateOf(StopsEditorUi(rows, canOptimize = true, busy = false))
        compose.setContent {
            BeamlineTheme(night = false) {
                Box(Modifier.size(411.dp, 891.dp)) { StopsEditor(editor, onEvent = { e -> events.add(e) }, modifier = Modifier.align(Alignment.BottomCenter)) }
            }
        }
        compose.waitForIdle()
        handleAction(SheetTexts.LOWER)
        assertEquals("Lowered", state())
        fun top(tag: String) = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().top
        val done = top(DriveTags.CLOSE_STOPS)
        val add = top(DriveTags.ADD_STOP)
        fun still(step: String) {
            compose.waitForIdle()
            assertEquals("$step: Done", done.value, top(DriveTags.CLOSE_STOPS).value, 0.5f)
            assertEquals("$step: Add stop", add.value, top(DriveTags.ADD_STOP).value, 0.5f)
        }
        compose.runOnUiThread { editor = editor.copy(note = DriveTexts.STOPS_REORDERED) }
        still("the note came")
        compose.onNodeWithTag(DriveTags.STOPS_NOTE).assertTextEquals(DriveTexts.STOPS_REORDERED)
        compose.saveShotOf(DriveTags.STOPS_EDITOR, "stops-editor-lowered-note-day")
        compose.runOnUiThread { editor = editor.copy(note = null) }
        still("the note went")
        compose.runOnUiThread { editor = editor.copy(rows = rows.mapIndexed { i, r -> if (i == 0) r.copy(reached = true) else r }, canOptimize = false) }
        still("Best order went")
    }

    /** Item 47: the arrival card: Done, Save as a link, and a drag up opens the destination's window. */
    @Test
    fun arrivalCardSaveAndDetails() {
        compose.setContent {
            BeamlineTheme(night = false) {
                Box(Modifier.size(411.dp, 891.dp)) {
                    ArrivedCard(GuidanceStatusUi.Arrived("Erie Zoo", "On your right"), onEvent = { e -> events.add(e) }, modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(DriveTags.DONE).assertIsDisplayed()
        compose.onNodeWithTag(DriveTags.SAVE_DESTINATION).assertIsDisplayed().performClick()
        assertTrue(events.contains(DriveEvent.SaveDestination))
        compose.onNodeWithTag(DriveTags.ARRIVED_CARD).performTouchInput { swipeUp(startY = top + 10f, endY = top - 300f) }
        compose.waitForIdle()
        assertTrue(events.toString(), events.contains(DriveEvent.ShowDestination))
        assertEquals("Lowered", state())
        compose.onNodeWithTag(DriveTags.DONE).performClick()
        assertTrue(events.contains(DriveEvent.Nav(NavIntent.Done)))
    }

    /**
     * Play test P31, audit F34: once the destination is saved (the app sets Arrived.saved) the card says "Saved" with a
     * check in place of "Save this place", so it cannot save twice; its handle's raise opens the details and says so.
     */
    @Test
    fun arrivalCardSavedAndItsOwnWords() {
        var status by mutableStateOf(GuidanceStatusUi.Arrived("Erie Zoo", "On the right"))
        compose.setContent {
            BeamlineTheme(night = false) {
                Box(Modifier.size(411.dp, 891.dp)) {
                    ArrivedCard(status, onEvent = { e -> events.add(e) }, modifier = Modifier.align(Alignment.BottomCenter))
                }
            }
        }
        compose.waitForIdle()
        val handle = compose.onNode(hasTestTag(SHEET_HANDLE_TAG) and hasAnyAncestor(hasTestTag(DriveTags.ARRIVED_CARD))).fetchSemanticsNode().config
        assertEquals(DriveTexts.SHOW_DETAILS, handle[SemanticsActions.OnClick].label)
        val actions = handle[SemanticsActions.CustomActions].map { it.label }
        assertTrue(actions.toString(), DriveTexts.SHOW_DETAILS in actions && SheetTexts.RAISE !in actions)
        compose.onNodeWithTag(DriveTags.SAVE_DESTINATION).assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasTestTag(DriveTags.DESTINATION_SAVED)).fetchSemanticsNodes().size)
        compose.runOnUiThread { status = status.copy(saved = true) }
        compose.waitForIdle()
        compose.onNodeWithTag(DriveTags.DESTINATION_SAVED).assertIsDisplayed().assertTextEquals(DriveTexts.SAVED)
        assertEquals(0, compose.onAllNodes(hasTestTag(DriveTags.SAVE_DESTINATION)).fetchSemanticsNodes().size)
        compose.onNodeWithTag(DriveTags.DONE).assertIsDisplayed()
        compose.saveShotOf(DriveTags.ARRIVED_CARD, "arrived-saved-day")
    }

    /**
     * 0.1.41 (docs/online-design.md 4.8, item 53g; plan AP-3, AP-10, AP-12): the arrival card's one question on its tonal
     * panel above Done: its title, lines and buttons, each sending its answer; a question that adds a field has only its
     * edit button and Not now. Done and Save are pinned at the card's foot: Done does not move when the question comes,
     * not even while the card grows; on its side at 640 x 360 dp with font 1.3 a 7-line question leaves Done in view with
     * no scroll, and a finger's drag scrolls the lines and buttons into view (it does not raise the card to the place's
     * details); every line is reachable by a screen reader's scroll too. Once the part is at its end, a drag up opens the
     * details as before (item 47).
     */
    @Test
    fun arrivalCardAsksOneQuestion() {
        var window by mutableStateOf(DpSize(360.dp, 640.dp))
        var scale by mutableStateOf(1f)
        var night by mutableStateOf(false)
        var status by mutableStateOf(GuidanceStatusUi.Arrived("Erie Zoo", "On your right"))
        compose.setContent { ArrivalScreen(window, scale, night, status) }
        compose.waitForIdle()
        fun done() = compose.onNodeWithTag(DriveTags.DONE).getUnclippedBoundsInRoot()
        fun sameAs(before: DpRect, step: String) {
            val now = done()
            for ((name, a, b) in listOf(Triple("left", before.left, now.left), Triple("top", before.top, now.top), Triple("right", before.right, now.right), Triple("bottom", before.bottom, now.bottom))) {
                assertEquals("$step: Done's $name", a.value, b.value, 0.5f)
            }
        }
        fun answers() = events.filterIsInstance<DriveEvent.AnswerArrival>().map { it.answer }
        val hours = ArrivalQuestionUi("Hours still right?", listOf("Mon–Fri 8 AM–6 PM"), yes = "Yes", edit = "Edit hours", notNow = "Not now")

        // upright (360 x 640 dp), day: no question, nothing in its place
        assertEquals(0, compose.onAllNodes(hasTestTag(DriveTags.ARRIVAL_QUESTION)).fetchSemanticsNodes().size)
        val upright = done()
        compose.saveShot("arrived-question-none-day")
        // the question comes: Done stays where it was, frame by frame while the card grows (upward from its bottom edge)
        compose.mainClock.autoAdvance = false
        compose.runOnUiThread { status = status.copy(question = hours) }
        repeat(8) { frame ->
            compose.mainClock.advanceTimeByFrame()
            sameAs(upright, "upright, frame $frame of the card's growth")
        }
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        sameAs(upright, "upright, with the question")
        compose.onNodeWithTag(DriveTags.ARRIVAL_QUESTION).assertIsDisplayed()
        compose.onNodeWithText("Hours still right?").assertIsDisplayed()
        compose.onNodeWithText("Mon–Fri 8 AM–6 PM").assertIsDisplayed()
        compose.onNodeWithTag(DriveTags.ARRIVAL_YES).assertIsDisplayed().assertTextEquals("Yes")
        compose.onNodeWithTag(DriveTags.ARRIVAL_EDIT).assertIsDisplayed().assertTextEquals("Edit hours")
        compose.onNodeWithTag(DriveTags.ARRIVAL_NOT_NOW).assertIsDisplayed().assertTextEquals("Not now")
        for (tag in listOf(DriveTags.ARRIVAL_YES, DriveTags.ARRIVAL_EDIT, DriveTags.ARRIVAL_NOT_NOW)) {
            val h = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot().height
            assertTrue("$tag: $h high", h.value >= 47.5f)
        }
        compose.assertWhollyShown(DriveTags.DONE)
        compose.assertWhollyShown(DriveTags.SAVE_DESTINATION)
        compose.saveShot("arrived-question-day")
        compose.runOnUiThread { night = true }
        compose.saveShot("arrived-question-night")
        compose.onNodeWithTag(DriveTags.ARRIVAL_YES).performClick()
        compose.onNodeWithTag(DriveTags.ARRIVAL_EDIT).performClick()
        compose.onNodeWithTag(DriveTags.ARRIVAL_NOT_NOW).performClick()
        assertEquals(listOf(ArrivalAnswer.YES, ArrivalAnswer.EDIT, ArrivalAnswer.NOT_NOW), answers())
        compose.runOnUiThread { status = status.copy(question = null) }
        compose.waitForIdle()
        sameAs(upright, "upright, the question gone")
        compose.saveShot("arrived-question-none-night")

        // a question that adds a field: only its edit button ("Add hours") and Not now, Done still where it was
        val addHours = ArrivalQuestionUi("This place has no hours on the map. Add them?", emptyList(), yes = null, edit = "Add hours", notNow = "Not now")
        compose.runOnUiThread { night = false; status = status.copy(question = addHours) }
        compose.waitForIdle()
        compose.onNodeWithText("This place has no hours on the map. Add them?").assertIsDisplayed()
        assertEquals(0, compose.onAllNodes(hasTestTag(DriveTags.ARRIVAL_YES)).fetchSemanticsNodes().size)
        val buttons = compose.onAllNodes(hasClickAction() and hasAnyAncestor(hasTestTag(DriveTags.ARRIVAL_QUESTION))).fetchSemanticsNodes()
            .map { it.config[SemanticsProperties.Text].joinToString(" ") }
        assertEquals(listOf("Add hours", "Not now"), buttons)
        sameAs(upright, "upright, Add hours")
        compose.saveShot("arrived-question-add-hours-day")
        compose.onNodeWithTag(DriveTags.ARRIVAL_EDIT).assertTextEquals("Add hours").performClick()
        assertEquals(ArrivalAnswer.EDIT, answers().last())

        // on its side, 640 x 360 dp, font 1.3: Done in view, then a 7-line question that does not fit
        compose.runOnUiThread { status = status.copy(question = null); window = DpSize(640.dp, 360.dp); scale = 1.3f }
        compose.waitForIdle()
        compose.assertWhollyShown(DriveTags.DONE)
        val side = done()
        compose.saveShot("land640-arrived-question-none-font130-day")
        val week = listOf("Mon 8 AM–6 PM", "Tue 8 AM–6 PM", "Wed 8 AM–6 PM", "Thu 8 AM–8 PM", "Fri 8 AM–8 PM", "Sat 9 AM–1 PM", "Sun closed")
        compose.runOnUiThread { status = status.copy(question = hours.copy(lines = week)) }
        compose.waitForIdle()
        // with no scroll: Done and Save whole and where Done was; the question's title in view, its end below the part's edge
        compose.onNodeWithTag(DriveTags.DONE).assertIsDisplayed()
        compose.assertWhollyShown(DriveTags.DONE)
        compose.assertWhollyShown(DriveTags.SAVE_DESTINATION)
        sameAs(side, "on its side, the 7-line question")
        compose.onNodeWithText("Hours still right?").assertIsDisplayed()
        assertFalse("the last line fits without a scroll: the test proves nothing", shownWhole(compose.onNodeWithText("Sun closed")))
        assertFalse("Not now fits without a scroll: the test proves nothing", shownWhole(compose.onNodeWithTag(DriveTags.ARRIVAL_NOT_NOW)))
        compose.saveShot("land640-arrived-question-font130-day")
        // a finger's drag up scrolls the part: the lines and buttons come into view, the card is not raised to the details
        var drags = 0
        while (!shownWhole(compose.onNodeWithTag(DriveTags.ARRIVAL_NOT_NOW)) && drags < 8) {
            dragUpOverTheCard()
            drags++
        }
        assertTrue(events.toString(), DriveEvent.ShowDestination !in events)
        assertTrue("Not now after $drags drags", shownWhole(compose.onNodeWithTag(DriveTags.ARRIVAL_NOT_NOW)))
        assertTrue("the last line after $drags drags", shownWhole(compose.onNodeWithText("Sun closed")))
        assertEquals(listOf(ArrivalAnswer.YES, ArrivalAnswer.EDIT, ArrivalAnswer.NOT_NOW, ArrivalAnswer.EDIT), answers())
        compose.assertWhollyShown(DriveTags.DONE)
        sameAs(side, "on its side, scrolled")
        compose.saveShot("land640-arrived-question-scrolled-font130-day")
        compose.runOnUiThread { night = true }
        compose.saveShot("land640-arrived-question-scrolled-font130-night")
        compose.onNodeWithTag(DriveTags.ARRIVAL_NOT_NOW).performClick()
        assertEquals(ArrivalAnswer.NOT_NOW, answers().last())
        // at the part's end a drag up raises the card again: the place's details (item 47). A drag that scrolled the part
        // never raises it, so the first may still take the part's last few dp; the second starts at the end.
        repeat(2) { dragUpOverTheCard() }
        assertTrue(events.toString(), DriveEvent.ShowDestination in events)
        assertEquals("Lowered", state())
        // a screen reader's scroll reaches every line (back up to the title), without raising the card to the details
        val raised = events.count { it == DriveEvent.ShowDestination }
        for (line in week.reversed()) {
            readerScrollTo(compose.onNodeWithText(line))
            assertTrue("$line after a screen reader's scroll", shownWhole(compose.onNodeWithText(line)))
        }
        readerScrollTo(compose.onNodeWithText("Hours still right?"))
        assertTrue("the title after a screen reader's scroll", shownWhole(compose.onNodeWithText("Hours still right?")))
        assertEquals(events.toString(), raised, events.count { it == DriveEvent.ShowDestination })
        compose.runOnUiThread { status = status.copy(question = null) }
        compose.waitForIdle()
        sameAs(side, "on its side, the question gone")
        compose.saveShot("land640-arrived-question-none-font130-night")
    }

    /** A finger's slow drag up over the arrival card's scrolling part (from near Done up to under the handle), in the root's pixels. */
    private fun dragUpOverTheCard() {
        val card = compose.onNodeWithTag(DriveTags.ARRIVED_CARD).fetchSemanticsNode().boundsInRoot
        val done = compose.onNodeWithTag(DriveTags.DONE).fetchSemanticsNode().boundsInRoot
        val x = card.left + card.width * 0.9f
        val part = done.top - card.top
        compose.onRoot().performTouchInput { swipe(Offset(x, card.top + part * 0.85f), Offset(x, card.top + part * 0.3f), durationMillis = 600) }
        compose.waitForIdle()
    }

    /**
     * A screen reader's scroll of the arrival card's part to [node]: the part's ScrollBy action (what TalkBack's scroll
     * uses), once, by the distance that brings the node whole into the part. Not performScrollTo, which repeats until the
     * node shows and so hangs when the card swallows the scroll (0.1.40's card raised itself to the details instead).
     */
    private fun readerScrollTo(node: SemanticsNodeInteraction) {
        compose.waitForIdle()
        val part = compose.onNode(hasScrollAction() and hasAnyAncestor(hasTestTag(DriveTags.ARRIVED_CARD))).fetchSemanticsNode()
        val view = part.boundsInRoot
        val target = node.fetchSemanticsNode()
        val top = target.positionInRoot.y
        val bottom = top + target.size.height
        val dy = when {
            bottom > view.bottom -> bottom - view.bottom
            top < view.top -> top - view.top
            else -> 0f
        }
        compose.runOnUiThread { part.config[SemanticsActions.ScrollBy].action!!.invoke(0f, dy) }
        compose.waitForIdle()
    }

    /** [node] is shown whole: its bounds clipped by every ancestor (the scrolling part, the window) are its own size. */
    private fun shownWhole(node: SemanticsNodeInteraction): Boolean {
        compose.waitForIdle()
        val coordinates = node.fetchSemanticsNode().layoutInfo.coordinates
        val shown = coordinates.boundsInWindow()
        return shown.height >= coordinates.size.height - 1f && shown.width >= coordinates.size.width - 1f
    }

    /**
     * The overlay with the arrival card in a [window] (no system bars) at [fontScale], on a plain land colour, tagged
     * [SHOT_TAG]. A window wider than the emulator's screen (640 dp across an upright 411 dp phone) is drawn at a smaller
     * pixel size: the same dp, so the same layout, and the whole window on screen.
     */
    @Composable
    private fun ArrivalScreen(window: DpSize, fontScale: Float, night: Boolean, status: GuidanceStatusUi.Arrived) {
        val base = LocalDensity.current
        val screenDp = LocalWindowInfo.current.containerSize.width / base.density
        val k = minOf(1f, (screenDp - 8f) / window.width.value)
        val ui = DriveUi(guidance = GuidanceUi(banner = null, status = status, tripBar = TripBarUi("Arrive 3:36 PM", "0 min", "0 ft", null, false), showRecenter = false))
        CompositionLocalProvider(LocalDensity provides Density(base.density * k, fontScale)) {
            BeamlineTheme(night = night) {
                Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                    Box(Modifier.size(window).clipToBounds().background(if (night) Color(0xFF1C242C) else Color(0xFFE7E5DF)).testTag(SHOT_TAG)) {
                        DriveOverlayContent(ui, onEvent = { e -> events.add(e) }, onInsets = { insets = it }, safe = WindowInsets(0, 0, 0, 0))
                    }
                }
            }
        }
    }

    /** Audit F2, F3: below full height a problem shows with its buttons, and the middle height has Cancel. */
    @Test
    fun problemAndCancelAtTheMiddleHeight() {
        ui = DriveUi(planning = sheet(1).copy(problem = "No GPS position yet. Choose a start point or try again.", canRetry = true, canChooseStart = true))
        compose.setContent {
            BeamlineTheme(night = false) {
                Box(Modifier.size(411.dp, 891.dp)) { DriveOverlayContent(ui, onEvent = { e -> events.add(e) }, onInsets = { insets = it }) }
            }
        }
        compose.waitForIdle()
        assertEquals("Half open", state())
        compose.onNodeWithText("No GPS position yet. Choose a start point or try again.").assertIsDisplayed()
        compose.onNodeWithText(DriveTexts.CHOOSE_START).performClick()
        compose.onNodeWithText(DriveTexts.RETRY).performClick()
        compose.onNodeWithTag(DriveTags.CANCEL).performClick()
        assertEquals(listOf(DriveEvent.ChooseStart, DriveEvent.Nav(NavIntent.Retry), DriveEvent.Nav(NavIntent.End)), events.toList())
    }

    /** Audit F31: Back at full height steps down to the routes; it does not close the route there. */
    @Test
    fun backAtFullHeightStepsDown() {
        ui = DriveUi(planning = sheet(1))
        var back: androidx.activity.OnBackPressedDispatcher? = null
        compose.setContent {
            back = androidx.activity.compose.LocalOnBackPressedDispatcherOwner.current?.onBackPressedDispatcher
            BeamlineTheme(night = false) {
                Box(Modifier.size(411.dp, 891.dp)) { DriveOverlayContent(ui, onEvent = { e -> events.add(e) }, onInsets = { insets = it }) }
            }
        }
        compose.waitForIdle()
        compose.raiseSheetToFull()
        assertEquals("Full height", state())
        compose.runOnUiThread { back!!.onBackPressed() }
        compose.waitForIdle()
        assertEquals("Half open", state())
        assertTrue(events.toString(), events.none { it == DriveEvent.Nav(NavIntent.End) })
    }

    /**
     * The owner, 2026-11-26: "when dragging the window that shows the faster or simpler route, the map
     * zoom to match window height is delayed". The camera is given the height the sheet is going to
     * as the slide starts (LocalSheetLag), not the heights it passes through.
     */
    @Test
    fun theCameraGetsTheHeightTheSheetIsGoingToAtOnce() {
        ui = DriveUi(planning = sheet(1))
        compose.setContent {
            BeamlineTheme(night = false) {
                Box(Modifier.size(411.dp, 891.dp)) { DriveOverlayContent(ui, onEvent = { e -> events.add(e) }, onInsets = { insets = it }) }
            }
        }
        compose.waitForIdle()
        assertEquals("Half open", state())
        val middle = insets.bottom
        compose.mainClock.autoAdvance = false
        compose.onNodeWithTag(SHEET_HANDLE_TAG).performClick()
        repeat(3) { compose.mainClock.advanceTimeByFrame() }
        val early = insets.bottom
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertEquals("Full height", state())
        val full = insets.bottom
        assertTrue("full $full > middle $middle", full > middle + 50f)
        assertEquals("three frames into the slide the camera already has the full height", full, early, 1f)
    }
}
