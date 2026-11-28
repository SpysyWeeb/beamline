package io.github.spysyweeb.beamline.feature.states

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import io.github.spysyweeb.beamline.feature.states.map.StateShapes
import io.github.spysyweeb.beamline.feature.states.map.StatesMap
import io.github.spysyweeb.beamline.feature.states.map.StatesMapView
import io.github.spysyweeb.beamline.feature.states.map.TapResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The owner, 2026-11-26: "if the user zooms in and taps on a state that isn't centered, it will
 * sometimes highlight the wrong state". Zoomed in on Pennsylvania, a tap on Ohio's label (away from
 * the centre) picks Ohio, and a tap on New Jersey's picks New Jersey.
 *
 * states.md P15, P25, P12: at the whole country the map does not move under a drag; a double tap
 * keeps the tapped point under the finger; a tap on the map does not zoom; a list selection leaves
 * no sea past the country's edge; a selection a search hides goes back to the whole country.
 */
class StatesMapTapTest {

    @get:Rule
    val compose = createComposeRule()

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val shapes: StateShapes get() = StatesTestInputs.shapes
    private val density get() = context.resources.displayMetrics.density

    @Test
    fun zoomedInTapAwayFromTheCentrePicksTheStateUnderTheFinger() {
        val view = StatesMapView(shapes.width, shapes.height)
        val taps = ArrayList<TapResult>()
        compose.setContent {
            Box(Modifier.size(400.dp, 600.dp).testTag(MAP)) {
                StatesMap(shapes, emptyMap(), selected = null, onTap = { taps.add(it) }, view = view)
            }
        }
        compose.waitForIdle()
        // a first touch at the whole country's zoom (as the pinch that zooms in is): the map's gesture
        // handlers start on the first touch, so this is what made a later tap use the old zoom
        compose.onNodeWithTag(MAP).performTouchInput { click(center) }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        // zoomed in 4x on Pennsylvania (as a pinch would leave it)
        val pa = shapes.byCode("PA")!!
        compose.runOnUiThread {
            view.centerX = (pa.box[0] + pa.box[2]) / 2
            view.centerY = (pa.box[1] + pa.box[3]) / 2
        }
        runBlocking { view.zoom.snapTo(4f) }
        compose.waitForIdle()
        val node = compose.onNodeWithTag(MAP).fetchSemanticsNode()
        val w = node.size.width.toFloat()
        val h = node.size.height.toFloat()
        val margin = 16f * density
        val fit = minOf((w - 2 * margin) / shapes.width, (h - 2 * margin) / shapes.height)
        val scale = fit * 4f
        fun screenOf(code: String): Offset {
            val s = shapes.byCode(code)!!
            return Offset(w / 2 + (s.labelX - view.centerX) * scale, h / 2 + (s.labelY - view.centerY) * scale)
        }
        for (code in listOf("OH", "NJ")) {
            val at = screenOf(code)
            check(at.x in 0f..w && at.y in 0f..h) { "$code's label at $at is off the ${w}x$h map" }
            compose.onNodeWithTag(MAP).performTouchInput { click(at) }
            // the map also takes double taps: a single tap is one once the double-tap time has passed
            compose.mainClock.advanceTimeBy(600)
            compose.waitForIdle()
            assertEquals("a tap on $code's label at $at", TapResult.One(code), taps.lastOrNull())
        }
    }

    // ------------------------------------------------------------------ P15, P25, P12 (a 400 x 270 dp card: no slack at 1x)

    /** The map's size on screen (px) and its scale at 1x (px per map unit). */
    private fun card(): Triple<Float, Float, Float> {
        val node = compose.onNodeWithTag(MAP).fetchSemanticsNode()
        val w = node.size.width.toFloat()
        val h = node.size.height.toFloat()
        val margin = 16f * density
        return Triple(w, h, minOf((w - 2 * margin) / shapes.width, (h - 2 * margin) / shapes.height))
    }

    /** [code]'s label on screen at the whole country (1x, centred). */
    private fun at1x(code: String): Offset {
        val (w, h, fit) = card()
        val s = shapes.byCode(code)!!
        return Offset(w / 2 + (s.labelX - shapes.width / 2) * fit, h / 2 + (s.labelY - shapes.height / 2) * fit)
    }

    private fun tapsMap(view: StatesMapView, taps: MutableList<TapResult>) {
        compose.setContent {
            Box(Modifier.size(400.dp, 270.dp).testTag(MAP)) {
                StatesMap(shapes, emptyMap(), selected = null, onTap = { taps.add(it) }, view = view)
            }
        }
        compose.waitForIdle()
    }

    /** P15: at 1x a one-finger drag does not pan the map off the country (before: a tap on Ohio then found nothing). */
    @Test
    fun aDragAtTheWholeCountryDoesNotMoveTheMap() {
        val view = StatesMapView(shapes.width, shapes.height)
        val taps = ArrayList<TapResult>()
        tapsMap(view, taps)
        compose.onNodeWithTag(MAP).performTouchInput {
            down(center)
            moveBy(Offset(-300.dp.toPx(), 0f))
            up()
        }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        val oh = at1x("OH")
        compose.onNodeWithTag(MAP).performTouchInput { click(oh) }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertEquals("a tap on Ohio's label at $oh after the drag", TapResult.One("OH"), taps.lastOrNull())
    }

    /** P15: a double tap zooms about the finger: the tapped state stays under it (before: it moved to the centre). */
    @Test
    fun aDoubleTapKeepsTheTappedStateUnderTheFinger() {
        val view = StatesMapView(shapes.width, shapes.height)
        val taps = ArrayList<TapResult>()
        tapsMap(view, taps)
        val oh = at1x("OH")
        compose.onNodeWithTag(MAP).performTouchInput { doubleClick(oh) }
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertTrue("the double tap zoomed in", view.zoomedIn)
        compose.onNodeWithTag(MAP).performTouchInput { click(oh) }
        compose.mainClock.advanceTimeBy(600)
        compose.waitForIdle()
        assertEquals("a tap at $oh after the double tap", TapResult.One("OH"), taps.lastOrNull())
    }

    /** P25: a state picked on the map is outlined, not zoomed (the zoom went on behind the sheet). */
    @Test
    fun aTapOnTheMapDoesNotZoom() {
        val view = StatesMapView(shapes.width, shapes.height)
        compose.setContent {
            // what the screen does with a map tap: the selection, marked as made on the map
            var sel by remember { mutableStateOf<String?>(null) }
            var onMap by remember { mutableStateOf(false) }
            Box(Modifier.size(400.dp, 270.dp).testTag(MAP)) {
                StatesMap(shapes, emptyMap(), sel, onTap = { if (it is TapResult.One) { sel = it.code; onMap = true } }, view = view, zoomToSelection = !onMap)
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag(MAP).performTouchInput { click(at1x("TX")) }
        compose.mainClock.advanceTimeBy(600)
        compose.mainClock.advanceTimeBy(1_000)
        compose.waitForIdle()
        assertFalse("a tap on Texas zoomed the map", view.zoomedIn)
    }

    /** P25, P15: a list selection of California zooms without sea past the country's west edge (before: about 38 % of the card). */
    @Test
    fun aListSelectionOfCaliforniaLeavesNoSeaBesideIt() {
        val view = StatesMapView(shapes.width, shapes.height)
        var sel by mutableStateOf<String?>(null)
        compose.setContent {
            Box(Modifier.size(400.dp, 270.dp).testTag(MAP)) { StatesMap(shapes, emptyMap(), sel, onTap = {}, view = view) }
        }
        compose.waitForIdle()
        compose.runOnUiThread { sel = "CA" }
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        assertTrue("a list selection zooms", view.zoomedIn)
        val (w, _, fit) = card()
        // the map's west edge (x 0, California's box starts there) on screen
        val edge = w / 2 + (0f - view.centerX) * fit * view.zoom.value
        assertTrue("the map's edge is at $edge px", edge <= 16f * density + 1f)
    }

    /** P12: a selection the search hides goes back to the whole country (before: the map stayed on California). */
    @Test
    fun aSelectionTheSearchHidesGoesBackToTheWholeCountry() {
        val view = StatesMapView(shapes.width, shapes.height)
        var sel by mutableStateOf<String?>(null)
        compose.setContent {
            Box(Modifier.size(400.dp, 270.dp).testTag(MAP)) { StatesMap(shapes, emptyMap(), sel, onTap = {}, view = view) }
        }
        compose.waitForIdle()
        compose.runOnUiThread { sel = "CA" }
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        assertTrue("a list selection zooms", view.zoomedIn)
        compose.runOnUiThread { sel = null }
        compose.mainClock.advanceTimeBy(1_500)
        compose.waitForIdle()
        assertFalse("the map stayed zoomed on California", view.zoomedIn)
    }

    private companion object {
        const val MAP = "states-map-under-test"
    }
}
