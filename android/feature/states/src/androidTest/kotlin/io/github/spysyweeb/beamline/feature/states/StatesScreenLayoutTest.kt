package io.github.spysyweeb.beamline.feature.states

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.spysyweeb.beamline.feature.states.StatesTestInputs.downloading
import io.github.spysyweeb.beamline.feature.states.StatesTestInputs.paInstalled
import io.github.spysyweeb.beamline.feature.states.StatesTestInputs.shapes
import io.github.spysyweeb.beamline.feature.states.StatesTestInputs.ui
import io.github.spysyweeb.beamline.feature.states.model.StatesEvent
import io.github.spysyweeb.beamline.feature.states.model.StatesUi
import io.github.spysyweeb.beamline.feature.states.model.Texts
import io.github.spysyweeb.beamline.ui.theme.BeamlineTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The States screen's layout (states.md P11, P12, P13, P14, P62): one scrolling list, the top bar
 * with Back, the search field going to the top, Remove apart from "Remove house numbers". Every case
 * passes landscape and imeVisible itself, so the result does not depend on how the emulator is held;
 * a case in a box of its own size has the window's bars consumed, so its height is the box's.
 * Run: sh tools/round/gradle.sh g :feature:states:connectedDebugAndroidTest (emulator beamline_g up).
 */
@RunWith(AndroidJUnit4::class)
class StatesScreenLayoutTest {
    @get:Rule
    val compose = createComposeRule()

    /** Portrait, the whole window, no keyboard. */
    private fun show(ui: StatesUi, on: (StatesEvent) -> Unit = {}) {
        compose.setContent { BeamlineTheme(night = false) { StatesBody(ui, shapes, on = on, onMapTap = {}, landscape = false, imeVisible = false) } }
        compose.waitForIdle()
    }

    /** In a [width] x [height] box without the window's bars. */
    private fun showIn(width: Dp, height: Dp, ui: StatesUi, landscape: Boolean, imeVisible: Boolean = false) {
        compose.setContent {
            BeamlineTheme(night = false) {
                Box(Modifier.size(width, height).consumeWindowInsets(WindowInsets.safeDrawing)) {
                    StatesBody(ui, shapes, on = {}, onMapTap = {}, landscape = landscape, imeVisible = imeVisible)
                }
            }
        }
        compose.waitForIdle()
    }

    /** P13: a Back arrow as Settings has; it closes the screen. */
    @Test
    fun theStatesScreenHasABackArrow() {
        val events = ArrayList<StatesEvent>()
        show(ui(StatesMode.MANAGE, installed = paInstalled()), on = { events += it })
        compose.onNodeWithContentDescription(Texts.BACK).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf<StatesEvent>(StatesEvent.Back), events) }
    }

    /** P11: during a download in a short window the list is still reachable (before: the fixed part left it 0 dp). */
    @Test
    fun aShortWindowStillReachesTheList() {
        showIn(360.dp, 420.dp, ui(StatesMode.MANAGE, job = downloading), landscape = false)
        // first the list's own height: on the old layout it was 0 dp, and scrolling it never ended
        val list = compose.onNode(hasScrollAction()).getBoundsInRoot()
        assertTrue("the list is ${list.bottom - list.top} tall", list.bottom - list.top >= 100.dp)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(Texts.ADD_A_STATE))
        compose.onNodeWithText(Texts.ADD_A_STATE).assertIsDisplayed()
    }

    /** P11, P15: in portrait a drag that starts on the map at the whole country scrolls the list. */
    @Test
    fun aDragOnTheMapScrollsTheList() {
        show(ui(StatesMode.MANAGE, installed = paInstalled()))
        val before = compose.onNode(hasSetTextAction()).getBoundsInRoot().top
        compose.onNode(hasContentDescription(Texts.MAP_DESCRIPTION)).performTouchInput { swipeUp(startY = bottom - 10f, endY = top + 10f) }
        compose.waitForIdle()
        val after = compose.onNode(hasSetTextAction()).getBoundsInRoot().top
        assertTrue("the field moved from $before to $after", before - after >= 100.dp)
    }

    /** P11: a focused search goes to the top, so the rows fill the rest above the keyboard. */
    @Test
    fun focusingTheSearchTakesItToTheTop() {
        show(ui(StatesMode.MANAGE, installed = paInstalled()))
        compose.onNode(hasSetTextAction()).performClick()
        compose.waitForIdle()
        val top = compose.onNode(hasSetTextAction()).getBoundsInRoot().top
        assertTrue("the field's top is at $top", top < FIELD_AT_TOP)
    }

    /** P11: typing a search that leaves few rows keeps the field at the top (the list does not clamp it down). */
    @Test
    fun typingKeepsTheFieldAtTheTop() {
        var q by mutableStateOf("")
        compose.setContent {
            BeamlineTheme(night = false) {
                StatesBody(
                    ui(StatesMode.MANAGE, installed = paInstalled(), query = q), shapes, on = { if (it is StatesEvent.Query) q = it.text }, onMapTap = {},
                    landscape = false, imeVisible = false,
                )
            }
        }
        compose.waitForIdle()
        compose.onNode(hasSetTextAction()).performClick()
        compose.onNode(hasSetTextAction()).performTextInput("zz")
        compose.waitForIdle()
        val top = compose.onNode(hasSetTextAction()).getBoundsInRoot().top
        assertTrue("the field's top is at $top", top < FIELD_AT_TOP)
    }

    /** P11, P25, F66 (landscape): a selected row off screen is scrolled to just under the pinned search field. */
    @Test
    fun aSelectedRowScrollsToJustUnderTheSearch() {
        showIn(800.dp, 360.dp, ui(StatesMode.MANAGE, installed = paInstalled(), selected = "OH"), landscape = true)
        val field = compose.onNode(hasSetTextAction()).getBoundsInRoot()
        val row = compose.onNodeWithText("Ohio").getBoundsInRoot()
        // the field's own 8 dp bottom padding is part of the pinned header
        assertTrue("the field ends at ${field.bottom}, Ohio's row starts at ${row.top}", row.top >= field.bottom - 1.dp && row.top <= field.bottom + 10.dp)
    }

    /** P11 (landscape, keyboard up): the field and at least one row still show (before: 19 dp of list). */
    @Test
    fun aLandscapeWindowWithTheKeyboardUpShowsTheFieldAndARow() {
        showIn(800.dp, 200.dp, ui(StatesMode.MANAGE, installed = paInstalled(), query = "oh"), landscape = true, imeVisible = true)
        compose.onNode(hasSetTextAction()).assertIsDisplayed()
        compose.onNodeWithText("Ohio").assertIsDisplayed()
        compose.onNodeWithText(Texts.USE_FILE).assertDoesNotExist() // the bar is hidden while typing
        compose.onNodeWithContentDescription(Texts.BACK).assertIsDisplayed() // Back stays, over the map
    }

    /**
     * P12: nothing matches: the line says so, no empty "Add a state", and Clear empties the field. Its X is
     * "Clear text", as the place search field's (P44); "Clear search" is the map pill's, which ends the results.
     */
    @Test
    fun aSearchThatMatchesNothingSaysSo() {
        val events = ArrayList<StatesEvent>()
        show(ui(StatesMode.MANAGE, installed = paInstalled(), query = "zz"), on = { events += it })
        compose.onNodeWithText(Texts.NO_MATCH).assertIsDisplayed()
        compose.onNodeWithText(Texts.ADD_A_STATE).assertDoesNotExist()
        compose.onNodeWithText("Pennsylvania").assertDoesNotExist()
        compose.onAllNodesWithContentDescription("Clear search").assertCountEquals(0)
        compose.onAllNodesWithContentDescription("Clear text").assertCountEquals(1)
        compose.onNodeWithContentDescription("Clear text").performClick()
        compose.runOnIdle { assertEquals(listOf<StatesEvent>(StatesEvent.Query("")), events) }
    }

    /** P14: the window of a state on this phone has the row's information and actions. */
    @Test
    fun theInstalledSheetShowsItsActions() {
        val events = ArrayList<StatesEvent>()
        val sheet = ui(StatesMode.MANAGE, installed = paInstalled(addresses = true), selected = "PA", sheetOpen = true, changesOptionalParts = true).sheet!!
        compose.setContent { BeamlineTheme(night = false) { SheetView(sheet, on = { events += it }) } }
        compose.waitForIdle()
        compose.onNodeWithText(Texts.HOUSE_NUMBERS_INCLUDED).assertIsDisplayed()
        compose.onNodeWithText(Texts.REMOVE_HOUSE_NUMBERS).assertIsDisplayed()
        compose.onNodeWithText(Texts.CLOSE).assertIsDisplayed()
        compose.onNodeWithText(Texts.REMOVE).assertIsDisplayed().performClick()
        compose.runOnIdle { assertEquals(listOf<StatesEvent>(StatesEvent.Remove("us-pa")), events) }
    }

    /** P14, P62: in the window, Remove sits alone at the far end, below the house-number line. */
    @Test
    fun removeSitsApartInTheWindow() {
        val sheet = ui(StatesMode.MANAGE, installed = paInstalled(addresses = true), selected = "PA", sheetOpen = true, changesOptionalParts = true).sheet!!
        compose.setContent { BeamlineTheme(night = false) { SheetView(sheet, on = {}) } }
        compose.waitForIdle()
        val remove = compose.onNodeWithText(Texts.REMOVE).getBoundsInRoot()
        val close = compose.onNodeWithText(Texts.CLOSE).getBoundsInRoot()
        val houseNumbers = compose.onNodeWithText(Texts.REMOVE_HOUSE_NUMBERS).getBoundsInRoot()
        assertTrue("Remove at $remove, Close at $close", remove.left >= close.right + 16.dp)
        assertTrue("Remove at $remove, Remove house numbers at $houseNumbers", remove.top >= houseNumbers.bottom)
    }

    /** P62: Remove is not beside "Remove house numbers" (the play test's mis-tap). */
    @Test
    fun removeSitsApartFromRemoveHouseNumbers() {
        show(ui(StatesMode.MANAGE, installed = paInstalled(addresses = true), changesOptionalParts = true))
        compose.onNode(hasScrollAction()).performScrollToNode(hasText(Texts.REMOVE))
        val remove = compose.onNodeWithText(Texts.REMOVE).getBoundsInRoot()
        val houseNumbers = compose.onNodeWithText(Texts.REMOVE_HOUSE_NUMBERS).getBoundsInRoot()
        assertTrue("Remove at $remove, Remove house numbers at $houseNumbers", remove.top >= houseNumbers.bottom)
    }

    private companion object {
        /** Just under the top bar (states.md: 120 dp; the status bar and the 56 dp top bar are above it). */
        val FIELD_AT_TOP = 120.dp
    }
}
