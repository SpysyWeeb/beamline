package io.github.spysyweeb.beamline.app.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.spysyweeb.beamline.api.map.EdgeInsetsDp
import io.github.spysyweeb.beamline.ui.theme.BeamlineIcons
import io.github.spysyweeb.beamline.ui.theme.BeamlineTheme
import io.github.spysyweeb.beamline.ui.theme.isSideLayout
import io.github.spysyweeb.beamline.ui.theme.sidePanelWidth
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P1, P7 (0.1.40, landscape.md T4): the map's own chrome (MapChromeLayout) on a phone on its side with 3-button
 * navigation. The device's own insets play no part: the pieces get [bars] (a cutout at the left, the status bar,
 * the navigation bar at the right; bottom 0). The 891 x 411 dp box overflows the portrait test window on both
 * sides, so every rect is read unclipped.
 *
 * Run (never :app:connectedDebugAndroidTest, which uninstalls the app and its packs): build
 * :app:assembleDebug :app:assembleDebugAndroidTest, `install -r` both APKs, then
 * `am instrument -w -e class io.github.spysyweeb.beamline.app.ui.MapChromeLandscapeTest io.github.spysyweeb.beamline.test/androidx.test.runner.AndroidJUnitRunner`.
 */
@RunWith(AndroidJUnit4::class)
class MapChromeLandscapeTest {
    @get:Rule
    val compose = createComposeRule()

    private val bars = WindowInsets(left = 32.dp, top = 24.dp, right = 48.dp)
    private val px = 0.5.dp   // about one pixel of tolerance (32 dp = 24 + 8 is an exact boundary)
    private fun s(): DpRect = compose.onNodeWithTag("screen").getUnclippedBoundsInRoot()
    private fun at(desc: String): DpRect = compose.onNodeWithContentDescription(desc).getUnclippedBoundsInRoot()
    private fun tagged(tag: String): DpRect = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
    private fun text(t: String): DpRect = compose.onNodeWithText(t).getUnclippedBoundsInRoot()

    private var sheet by mutableStateOf<Dp?>(null)

    private fun screen(w: Dp, h: Dp) = compose.setContent {
        BeamlineTheme(night = false) {
            Box(Modifier.requiredSize(w, h).testTag("screen")) {
                val panel = if (isSideLayout(w, h)) sidePanelWidth(w) else null
                val room = AppRules.bottomChrome(sheet?.value, panel?.value, EdgeInsetsDp(), null)
                MapTopChrome(bars, onHeight = {}, Modifier.align(Alignment.TopStart),
                    start = { Box(Modifier.fillMaxWidth().height(48.dp).testTag("pill")) },
                    end = {
                        RoundButton(BeamlineIcons.Settings, "Settings", night = false) {}
                        RoundButton(MapStyleIcon, "Map style", night = false) {}
                    })
                MapBottomChrome(bars, start = room.startDp.dp, bottom = room.bottomDp.dp, onRowHeight = {}, Modifier.align(Alignment.BottomCenter),
                    message = null, credit = { Spacer(Modifier.weight(1f)) },
                    location = { LocationButton(following = false, compass = false, bearing = MutableStateFlow(0f), night = false, onClick = {}) })
                sheet?.let { sh ->
                    MapSheetBox(panel, bars, maxHeight = Dp.Infinity, onHeight = {}, Modifier.align(if (panel != null) Alignment.BottomStart else Alignment.BottomCenter)) {
                        Box(Modifier.fillMaxWidth().height(sh).testTag("sheet"))
                    }
                }
            }
        }
    }

    @Test
    fun onItsSideTheButtonsKeepClearOfTheBars() {
        screen(891.dp, 411.dp)
        val s = s()
        for (d in listOf("Settings", "Map style", "Show my position")) {
            val r = at(d)
            assertTrue("$d ends at ${r.right}, the bar starts at ${s.right - 48.dp}", r.right <= s.right - 48.dp + px)
        }
        assertTrue("Settings under the status bar: ${at("Settings").top}", at("Settings").top >= s.top + 32.dp - px)
        assertTrue("the pill clear of the cutout: ${tagged("pill").left}", tagged("pill").left >= s.left + 40.dp - px)
        assertTrue("my-location off the bottom edge: ${at("Show my position").bottom}", at("Show my position").bottom <= s.bottom - 8.dp + px)
    }

    @Test
    fun theRaisedPlaceWindowGoesToTheSideAndMyLocationStaysPut() {
        sheet = 130.dp
        screen(891.dp, 411.dp)
        compose.waitForIdle()
        val s = s()
        val low = at("Show my position")
        compose.runOnIdle { sheet = 286.dp }
        compose.waitForIdle()
        val loc = at("Show my position")
        assertEquals(low.top.value, loc.top.value, px.value)
        assertEquals(low.left.value, loc.left.value, px.value)
        assertTrue("my-location clear of the bar: ${loc.right}", loc.right <= s.right - 48.dp + px)
        val style = at("Map style")
        assertTrue("my-location $loc overlaps Map style $style",
            loc.top >= style.bottom || loc.bottom <= style.top || loc.left >= style.right || loc.right <= style.left)
        val sh = tagged("sheet")
        assertEquals(s.left.value, sh.left.value, px.value)
        assertEquals(400f, (sh.right - sh.left).value, px.value)
    }

    @Test
    fun uprightTheWindowStaysAtTheBottomAndMyLocationRidesAboveIt() {
        sheet = 286.dp
        screen(360.dp, 640.dp)
        compose.waitForIdle()
        val s = s()
        val sh = tagged("sheet")
        assertEquals(360f, (sh.right - sh.left).value, px.value)
        assertEquals(s.bottom.value, sh.bottom.value, px.value)
        assertEquals((s.bottom - 286.dp).value, at("Show my position").bottom.value, px.value)
    }

    @Test
    fun theMapStyleCardKeepsClearOfTheBars() {
        compose.setContent {
            BeamlineTheme(night = false) {
                Box(Modifier.requiredSize(891.dp, 411.dp).testTag("screen")) {
                    MapTopCard(bars, top = 32.dp, Modifier.align(Alignment.TopStart)) {
                        MessageCard("The map style could not be loaded.", night = false, action = "States" to {}, onDismiss = {})
                    }
                }
            }
        }
        val s = s()
        val t = text("The map style could not be loaded.")
        assertTrue("the card's text starts at ${t.left - s.left}", t.left >= s.left + 56.dp - px)
        assertTrue("the card's text top at ${t.top - s.top}", t.top >= s.top + 32.dp - px)
    }

    @Test
    fun onItsSideASnackbarCentresInTheMapBesideThePanel() {
        compose.setContent {
            BeamlineTheme(night = false) {
                Box(Modifier.requiredSize(891.dp, 411.dp).testTag("screen")) {
                    Snackbar("Press Back again to leave Beamline. Guidance keeps running.", night = false,
                        modifier = Modifier.align(Alignment.BottomCenter).snackbarPlace(start = 400.dp, bottom = 88.dp))
                }
            }
        }
        val s = s()
        val t = text("Press Back again to leave Beamline. Guidance keeps running.")
        assertTrue("the bar's text starts at ${t.left - s.left}, over the 400 dp panel", t.left >= s.left + 400.dp - px)
    }
}
