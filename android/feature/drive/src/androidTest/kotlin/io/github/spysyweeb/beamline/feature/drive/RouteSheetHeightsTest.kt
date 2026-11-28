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
