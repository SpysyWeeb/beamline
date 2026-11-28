package io.github.spysyweeb.beamline.app.ui

import android.graphics.Color
import android.os.Build
import android.os.SystemClock
import android.view.WindowInsetsController
import android.view.inspector.WindowInspector
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.spysyweeb.beamline.settings.MapStyle
import io.github.spysyweeb.beamline.ui.theme.BeamlineTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import kotlin.math.abs

/**
 * P41 (0.1.40, map-look.md): the map style sheet (a Material modal sheet in its own dialog window) shows itself
 * under the 3-button navigation bar in the theme it has now, not a contrast band coloured by the theme it opened
 * in, and its bars' icons follow the theme. The pixel tests need 3-button navigation (a gesture bar carries no
 * band) and are skipped otherwise. No AppGraph and no map.
 */
@RunWith(AndroidJUnit4::class)
class MapStyleSheetBarsTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var night by mutableStateOf(false)
    private var open by mutableStateOf(true)

    private fun show(startNight: Boolean) {
        night = startNight
        compose.setContent {
            BeamlineTheme(night) {
                if (open) MapStyleSheet(if (night) MapStyle.NIGHT else MapStyle.DAY, onPick = {}, onClose = {})
            }
        }
        compose.waitForIdle()
        compose.onNodeWithTag("map-style-sheet").assertExists()
    }

    private fun near(a: Int, b: Int, tol: Int = 4) =
        abs(Color.red(a) - Color.red(b)) <= tol && abs(Color.green(a) - Color.green(b)) <= tol && abs(Color.blue(a) - Color.blue(b)) <= tol

    private fun hex(c: Int) = String.format("#%06X", c and 0xFFFFFF)

    /** The navigation bar's pixel beside the Back button and the sheet's empty bottom padding just above the bar. */
    private fun barAndSheet(): Pair<Int, Int> {
        assumeTrue("the navigation bar's insets type", Build.VERSION.SDK_INT >= 30)
        val density = compose.activity.resources.displayMetrics.density
        var bottom = 0
        compose.runOnUiThread {
            bottom = compose.activity.window.decorView.rootWindowInsets.getInsets(android.view.WindowInsets.Type.navigationBars()).bottom
        }
        assumeTrue("3-button navigation", bottom >= 40 * density)
        compose.waitForIdle()
        val ua = InstrumentationRegistry.getInstrumentation().uiAutomation
        val until = SystemClock.uptimeMillis() + 3_000
        var bar: Int
        var sheet: Int
        while (true) {
            val shot = ua.takeScreenshot()
            val x = shot.width / 20
            bar = shot.getPixel(x, shot.height - bottom / 2)
            sheet = shot.getPixel(x, shot.height - bottom - (8 * density).toInt())
            shot.recycle()
            if (near(bar, sheet) || SystemClock.uptimeMillis() > until) break
            Thread.sleep(100)
        }
        return bar to sheet
    }

    private fun assertBarShowsSheet(surface: Int) {
        val (bar, sheet) = barAndSheet()
        assertTrue("the bar ${hex(bar)} shows the sheet ${hex(sheet)}", near(bar, sheet))
        assertTrue("the sheet ${hex(sheet)} is the app's surface ${hex(surface)}", near(sheet, surface))
    }

    @Test
    fun aSheetOpenedByNightThenSwitchedToDayShowsTheDaySheetUnderTheBar() {
        show(startNight = true)
        compose.runOnIdle { night = false }
        compose.waitForIdle()
        assertBarShowsSheet(0xFFFFFF)
    }

    @Test
    fun aSheetOpenedByDayThenSwitchedToNightShowsTheNightSheetUnderTheBar() {
        show(startNight = false)
        compose.runOnIdle { night = true }
        compose.waitForIdle()
        assertBarShowsSheet(0x151C23)
    }

    @Test
    fun barIconsFollowTheThemeWhileTheSheetIsOpen() {
        assumeTrue("the platform's own controller is read from API 35", Build.VERSION.SDK_INT >= 35)
        show(startNight = false)
        fun sheetAppearance(): Int {
            var a = -1
            compose.runOnUiThread {
                val v = WindowInspector.getGlobalWindowViews().last { it !== compose.activity.window.decorView }
                a = v.windowInsetsController!!.systemBarsAppearance
            }
            return a
        }
        val both = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS or WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
        compose.runOnIdle { night = true }
        compose.waitForIdle()
        assertEquals("light icons by night", 0, sheetAppearance() and both)
        compose.runOnIdle { night = false }
        compose.waitForIdle()
        assertEquals("dark icons by day", both, sheetAppearance() and both)
    }

    @Test
    fun theBarShowsTheSheetWhenOpenedInItsTheme() {
        show(startNight = false)
        assertBarShowsSheet(0xFFFFFF)
        compose.runOnIdle { open = false }
        compose.waitForIdle()
        compose.runOnIdle { night = true }
        compose.runOnIdle { open = true }
        compose.waitForIdle()
        assertBarShowsSheet(0x151C23)
    }
}
