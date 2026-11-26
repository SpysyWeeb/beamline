package io.github.spysyweeb.beamline.feature.states

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.click
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
import org.junit.Rule
import org.junit.Test

/**
 * The owner, 2026-11-26: "if the user zooms in and taps on a state that isn't centered, it will
 * sometimes highlight the wrong state". Zoomed in on Pennsylvania, a tap on Ohio's label (away from
 * the centre) picks Ohio, and a tap on New Jersey's picks New Jersey.
 */
class StatesMapTapTest {

    @get:Rule
    val compose = createComposeRule()

    @Test
    fun zoomedInTapAwayFromTheCentrePicksTheStateUnderTheFinger() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val shapes = context.assets.open("states/us_states.json").use { StateShapes.parse(it.readBytes().decodeToString()) }
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
        val density = context.resources.displayMetrics.density
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

    private companion object {
        const val MAP = "states-map-under-test"
    }
}
