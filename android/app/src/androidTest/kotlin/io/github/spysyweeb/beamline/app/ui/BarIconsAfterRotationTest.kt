package io.github.spysyweeb.beamline.app.ui

import android.content.res.Configuration
import android.os.Build
import android.view.WindowInsetsController
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * P39 (0.1.40, map-look.md): after a rotation both system bars are set again from the map, the status bar not
 * while the drive overlay's dark banner holds it upright. The platform's redraw with light icons is played by
 * the test (it clears both appearance bits) before each change. Read through the platform's own controller
 * (API 35 and later), not through the helper, so the code is not checked with itself.
 */
@RunWith(AndroidJUnit4::class)
class BarIconsAfterRotationTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    private var upright by mutableStateOf(true)
    private var rotation by mutableIntStateOf(Configuration.ORIENTATION_PORTRAIT)
    private var darkTop = false

    private val lightStatus = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
    private val lightNav = WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS

    private fun appearance(): Int {
        var a = 0
        compose.runOnUiThread { a = compose.activity.window.insetsController!!.systemBarsAppearance }
        return a
    }

    /** The platform's redraw after a rotation: light icons on both bars ([keepStatus]: the overlay's own light status icons). */
    private fun redraw() {
        compose.runOnUiThread { compose.activity.window.insetsController!!.setSystemBarsAppearance(0, lightStatus or lightNav) }
    }

    private fun turn(toUpright: Boolean) {
        compose.runOnIdle {
            upright = toUpright
            rotation = if (toUpright) Configuration.ORIENTATION_PORTRAIT else Configuration.ORIENTATION_LANDSCAPE
        }
        compose.waitForIdle()
    }

    @Test
    fun theBarsFollowTheMapAfterARotationUnlessTheBannerHoldsTheStatusBar() {
        assumeTrue("the window's appearance is read through WindowInsetsController from API 35", Build.VERSION.SDK_INT >= 35)
        compose.setContent {
            BarIconsAfterRotation(compose.activity.window, night = false, revealDone = true, upright = upright, rotation = rotation, driveDarkTop = { darkTop })
        }
        compose.waitForIdle()
        // 1. landscape, no banner: dark icons on both bars over the day map (P39: the clock was white)
        redraw()
        turn(toUpright = false)
        assertEquals("1: status bar", lightStatus, appearance() and lightStatus)
        assertEquals("1: navigation bar", lightNav, appearance() and lightNav)
        // 2. upright with the dark banner: the navigation bar follows the map, the status bar stays the overlay's (light icons)
        darkTop = true
        redraw()
        turn(toUpright = true)
        assertEquals("2: navigation bar", lightNav, appearance() and lightNav)
        assertEquals("2: status bar left to the overlay", 0, appearance() and lightStatus)
        // 3. on its side the overlay does not hold it, banner or not
        redraw()
        turn(toUpright = false)
        assertEquals("3: status bar", lightStatus, appearance() and lightStatus)
        // 4. no banner (waiting for GPS, or a screen over the map), upright: the status bar follows the map
        darkTop = false
        redraw()
        turn(toUpright = true)
        assertEquals("4: status bar", lightStatus, appearance() and lightStatus)
    }
}
