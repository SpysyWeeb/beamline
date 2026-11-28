package io.github.spysyweeb.beamline.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.click
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.spysyweeb.beamline.feature.search.ResultRowUi
import io.github.spysyweeb.beamline.feature.search.SearchMode
import io.github.spysyweeb.beamline.feature.search.SearchPhase
import io.github.spysyweeb.beamline.feature.search.SearchScreen
import io.github.spysyweeb.beamline.feature.search.SearchUi
import io.github.spysyweeb.beamline.ui.theme.BeamlineTheme
import io.github.spysyweeb.beamline.ui.theme.isSideLayout
import io.github.spysyweeb.beamline.ui.theme.sidePanelWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Item 54b (rule S12; design app-fixes T9, plan AF-1): the Add stop search as a panel over the trip's map
 * (MapChromeLayout's AddStopPanelBox, placed by AppRules.addStopPanelPlace) with today's SearchScreen in it, and the
 * stop overlay's place on its side with the keyboard up (stopOverlayModifier). The keyboard is a stand-in inset
 * (`WindowInsets(bottom = kb)`); the device's own insets play no part. The 891 dp boxes overflow the test window, so
 * every rect is read unclipped and the clicks land where the box is on screen. Made-up places only.
 *
 * Run (never :app:connectedDebugAndroidTest, which uninstalls the app and its packs): build
 * :app:assembleDebug :app:assembleDebugAndroidTest, `install -r` both APKs, then
 * `am instrument -w -e class io.github.spysyweeb.beamline.app.ui.AddStopPanelTest io.github.spysyweeb.beamline.test/androidx.test.runner.AndroidJUnitRunner`.
 */
@RunWith(AndroidJUnit4::class)
class AddStopPanelTest {
    @get:Rule
    val compose = createComposeRule()

    private val bars = WindowInsets(top = 24.dp)
    private val px = 0.5.dp
    private val mapClicks = mutableIntStateOf(0)
    private fun s(): DpRect = compose.onNodeWithTag("screen").getUnclippedBoundsInRoot()
    private fun panel(): DpRect = compose.onNodeWithTag(ADD_STOP_PANEL_TAG).getUnclippedBoundsInRoot()

    private val ui = SearchUi(
        mode = SearchMode.ADD_STOP, query = "fuel", phase = SearchPhase.RESULTS, nearText = "Along your route", focusField = false,
        results = listOf(
            ResultRowUi("r0", "fuel", "Pump One", "Made-up Road", "0.4 mi", null),
            ResultRowUi("r1", "fuel", "Corner Fuel", "Made-up Avenue", "1.2 mi", null),
            ResultRowUi("r2", "fuel", "Lakeside Gas", "Made-up Lane", "3.0 mi", null),
        ),
    )

    /** The window [w] x [h] dp, the keyboard [kb] dp high, the drive overlay's top stack [above] dp from the top. */
    private fun screen(w: Dp, h: Dp, kb: Dp, above: Float = 0f) = compose.setContent {
        BeamlineTheme(night = false) {
            Box(Modifier.requiredSize(w, h).testTag("screen")) {
                Box(Modifier.fillMaxSize().testTag("map").clickable { mapClicks.intValue++ })
                val side = isSideLayout(w, h)
                val panel = if (side) sidePanelWidth(w) else null
                val place = AppRules.addStopPanelPlace(h.value, kb.value, above, 24f, side, AppRules.ADD_STOP_PANEL_MIN_DP)
                AddStopPanelBox(panel, bars, place.heightDp.dp, Modifier.align(if (side) Alignment.BottomStart else Alignment.BottomCenter),
                    keyboard = WindowInsets(bottom = kb)) {
                    SearchScreen(ui, onEvent = {})
                }
            }
        }
    }

    /** A click at ([x], [y]) dp of the window's box. */
    private fun clickMapAt(x: Dp, y: Dp) = compose.onNodeWithTag("map").performTouchInput { click(Offset(x.toPx(), y.toPx())) }

    @Test
    fun uprightThePanelSitsOnTheKeyboardWithTheMapAboveIt() {
        screen(411.dp, 891.dp, kb = 300.dp)
        compose.waitForIdle()
        val s = s()
        val p = panel()
        assertEquals("the panel's top", 311.5f, (p.top - s.top).value, px.value)
        assertEquals("the panel's bottom, on the keyboard", 591f, (p.bottom - s.top).value, px.value)
        assertEquals("across the width", 411f, (p.right - p.left).value, px.value)
        val field = compose.onNode(hasSetTextAction()).getUnclippedBoundsInRoot()
        assertTrue("the field at the panel's top: ${field.top - p.top}", field.top - p.top <= 8.dp + px && field.top >= p.top - px)
        // a row is one node for TalkBack ("Pump One, Made-up Road, 0.4 mi")
        val row = compose.onNodeWithContentDescription("Pump One", substring = true).getUnclippedBoundsInRoot()
        assertTrue("the first row inside the panel: $row in $p", row.top >= p.top && row.bottom <= p.bottom + px)
        // the map above the panel takes taps; the panel's own rows do not reach it
        clickMapAt(200.dp, 150.dp)
        compose.waitForIdle()
        assertEquals(1, mapClicks.intValue)
        compose.onNodeWithContentDescription("Pump One", substring = true).performClick()
        compose.waitForIdle()
        assertEquals(1, mapClicks.intValue)
    }

    @Test
    fun onItsSideThePanelIsInTheStartPanelWithTheMapBesideIt() {
        screen(891.dp, 411.dp, kb = 200.dp)
        compose.waitForIdle()
        val s = s()
        val p = panel()
        assertEquals("at the start", 0f, (p.left - s.left).value, px.value)
        assertEquals("the side panel's width", 400f, (p.right - p.left).value, px.value)
        assertEquals("under the status bar", 32f, (p.top - s.top).value, px.value)
        assertEquals("on the keyboard", 211f, (p.bottom - s.top).value, px.value)
        // beside the panel the map takes taps (x 560: on screen in a portrait test window, right of the 400 dp panel)
        clickMapAt(560.dp, 100.dp)
        compose.waitForIdle()
        assertEquals(1, mapClicks.intValue)
    }

    @Test
    fun onItsSideWhileGuidingWithTheKeyboardUpThePanelGoesOverTheBanner() {
        screen(891.dp, 411.dp, kb = 200.dp, above = 120f)
        compose.waitForIdle()
        assertEquals("from under the status bar", 24f, (panel().top - s().top).value, px.value)
    }

    @Test
    fun onItsSideTheSpeedPairStaysAboveTheKeyboard() {
        // plan AF-1: the stop overlay (whose speed pair sits at its foot beside the side panel) ends on the keyboard
        var kb by mutableFloatStateOf(200f)
        compose.setContent {
            BeamlineTheme(night = false) {
                Box(Modifier.requiredSize(891.dp, 411.dp).testTag("screen")) {
                    Box(stopOverlayModifier(sideKeyboard = kb > 0f, keyboardDp = kb)) {
                        Box(Modifier.align(Alignment.BottomEnd).size(96.dp, 120.dp).testTag("speed"))
                    }
                }
            }
        }
        compose.waitForIdle()
        val s = s()
        val pair = compose.onNodeWithTag("speed").getUnclippedBoundsInRoot()
        assertTrue("the speed pair's bottom ${pair.bottom - s.top} above the keyboard's top 211", pair.bottom <= s.top + 211.dp + px)
        // the keyboard down: the overlay is the whole window again
        compose.runOnIdle { kb = 0f }
        compose.waitForIdle()
        assertEquals(411f, (compose.onNodeWithTag("speed").getUnclippedBoundsInRoot().bottom - s.top).value, px.value)
    }
}
