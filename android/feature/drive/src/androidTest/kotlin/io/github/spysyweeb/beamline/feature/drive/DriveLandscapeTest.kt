package io.github.spysyweeb.beamline.feature.drive

import android.content.res.Configuration
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.spysyweeb.beamline.api.map.CameraMode
import io.github.spysyweeb.beamline.api.map.CameraState
import io.github.spysyweeb.beamline.api.map.EdgeInsetsDp
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.nav.LiveBanner
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.TripStop
import io.github.spysyweeb.beamline.feature.drive.Exit24.choice
import io.github.spysyweeb.beamline.feature.drive.Exit24.choosing
import io.github.spysyweeb.beamline.feature.drive.Exit24.downsDrive
import io.github.spysyweeb.beamline.feature.drive.Exit24.fastest
import io.github.spysyweeb.beamline.feature.drive.Exit24.fastestOnly
import io.github.spysyweeb.beamline.feature.drive.Exit24.frame1
import io.github.spysyweeb.beamline.feature.drive.Exit24.frame2
import io.github.spysyweeb.beamline.feature.drive.Exit24.guiding
import io.github.spysyweeb.beamline.feature.drive.Exit24.present
import io.github.spysyweeb.beamline.feature.drive.Exit24.sheetz
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** The host of [DriveLandscapeTest]: asks for landscape in the test APK's manifest (src/androidTest). */
class LandscapeHostActivity : ComponentActivity()

/**
 * The overlay with the phone on its side (section 8's "rotate" variant): the banner and the sheets
 * in a panel on the start side, the map buttons at the bottom end, the panel's width reported to the
 * camera as the start inset. The host activity asks for landscape, so the device's own rotation
 * settings are never changed; where the system ignores that request the test is skipped, saying so.
 */
@RunWith(AndroidJUnit4::class)
class DriveLandscapeTest {

    @get:Rule
    val compose = createAndroidComposeRule<LandscapeHostActivity>()

    private var ui by mutableStateOf(DriveUi())
    private var night by mutableStateOf(false)
    private var insets = EdgeInsetsDp()

    private val simpler = choice(2, listOf("simpler"), lengthM = 5200.0, timeS = 420.0, hasTolls = true, hasHighways = false, via = listOf("US 20"))
    private val zoo = Place("Erie Zoo", GeoPoint(42.1489, -80.0869), PlaceSource.SEARCH, subtitle = "West 38th Street, Erie")

    @Before
    fun onItsSide() {
        // The display turns when the activity comes up; give it a few seconds.
        val deadline = SystemClock.uptimeMillis() + 5_000
        fun landscape() = compose.activity.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        while (!landscape() && SystemClock.uptimeMillis() < deadline) Thread.sleep(100)
        assumeTrue("the system kept the host activity upright", landscape())
    }

    private fun host(widthDp: Int, heightDp: Int) {
        compose.setContent { PhoneScreen(ui, night, widthDp = widthDp, heightDp = heightDp, onInsets = { insets = it }) }
        compose.waitForIdle()
    }

    private fun show(u: DriveUi, n: Boolean) {
        compose.runOnUiThread {
            ui = u
            night = n
        }
        compose.waitForIdle()
    }

    private val frame2Ui get() = present(guiding(banner = frame2, remainingM = 121.92 + 356.0 + 391.0, remainingS = 70.0), camera = CameraState(CameraMode.GUIDE_FREE, showRecenter = true))

    /** A 20:9 phone on its side (800 x 360 dp): a 400 dp panel. */
    @Test
    fun widePhone() {
        host(800, 360)
        val shots = listOf(
            "land-frame2" to frame2Ui,
            "land-sheet" to present(choosing(choices = listOf(fastestOnly, simpler), selected = 1, stops = listOf(TripStop(sheetz), TripStop(downsDrive)))),
            "land-stops-editor" to present(choosing(stops = listOf(TripStop(sheetz), TripStop(zoo), TripStop(downsDrive))), stopsEditorOpen = true),
            "land-rerouting" to present(guiding(banner = frame2, phase = Phase.REROUTING)),
            "land-arrived" to present(guiding(banner = LiveBanner(Exit24.arriveView, 12.0, false), phase = Phase.ARRIVED)),
        )
        for (n in listOf(false, true)) for ((name, u) in shots) {
            show(u, n)
            compose.saveShot("$name-${if (n) "night" else "day"}")
        }
        show(frame2Ui, false)
        assertEquals(EdgeInsetsDp(start = 400f), insets)
        compose.onNodeWithTag(DriveTags.BANNER).assertIsDisplayed()
        compose.onNodeWithTag(DriveTags.LANES).assertIsDisplayed()
        compose.onNodeWithTag(DriveTags.TRIP_BAR).assertIsDisplayed()
        compose.onNodeWithTag(DriveTags.RECENTER).assertIsDisplayed()
        // the route sheet keeps Cancel, the chosen route and Start in view; the stops editor its buttons
        show(present(choosing(choices = listOf(fastestOnly, simpler), selected = 1, stops = listOf(TripStop(sheetz), TripStop(downsDrive)))), false)
        compose.assertWhollyShown(DriveTags.CANCEL)
        compose.assertWhollyShown(DriveTags.card(1))
        compose.assertWhollyShown(DriveTags.START)
        compose.assertNotCutAtTop(DriveTags.stopLine(0))
        compose.assertNotCutAtTop(DriveTags.stopLine(1))
        show(present(choosing(choices = listOf(fastestOnly, simpler), selected = 2, stops = listOf(TripStop(sheetz), TripStop(downsDrive)))), false)
        compose.assertWhollyShown(DriveTags.card(2))
        compose.assertNotCutAtTop(DriveTags.stopLine(0))
        compose.assertNotCutAtTop(DriveTags.stopLine(1))
        // eight lanes in the 400 dp panel
        show(present(guiding(banner = Exit24.eightLanes)), false)
        compose.assertLanesWhole(8)
        show(present(choosing(stops = listOf(TripStop(sheetz), TripStop(zoo), TripStop(downsDrive))), stopsEditorOpen = true), false)
        compose.onNodeWithTag(DriveTags.CLOSE_STOPS).assertIsDisplayed()
        compose.onNodeWithTag(DriveTags.BEST_ORDER).assertIsDisplayed()
        // nothing drawn, nothing covered
        show(DriveUi(), false)
        assertEquals(EdgeInsetsDp(), insets)
    }

    /** A 16:9 phone on its side (640 x 360 dp): the narrowest panel, 320 dp. */
    @Test
    fun smallPhone() {
        host(640, 360)
        val long = guiding(banner = frame1.copy(view = Exit24.exitView.copy(towards = listOf("Waterford", "Edinboro"))), stops = listOf(TripStop(sheetz), TripStop(downsDrive)), nextStopIndex = 0, toNextStopM = 1609.344)
        for (n in listOf(false, true)) {
            val label = if (n) "night" else "day"
            show(present(long), n)
            compose.saveShot("land640-frame1-$label")
            show(present(choosing(choices = listOf(fastestOnly, simpler), selected = 1), locationAllowed = false), n)
            compose.saveShot("land640-sheet-$label")
            show(present(guiding(banner = Exit24.eightLanes)), n)
            compose.saveShot("land640-lanes8-$label")
        }
        show(present(long), false)
        assertEquals(EdgeInsetsDp(start = 320f), insets)
        compose.onNodeWithTag(DriveTags.THEN_CHIP).assertIsDisplayed()
        compose.onNodeWithTag(DriveTags.END).assertIsDisplayed()
        show(present(choosing(choices = listOf(fastestOnly, simpler), selected = 1), locationAllowed = false), false)
        compose.assertWhollyShown(DriveTags.card(1))
        compose.assertWhollyShown(DriveTags.START)
        compose.assertWhollyShown(DriveTags.START_BLOCKED)
        compose.onNodeWithText("Allow precise location to start guidance").assertIsDisplayed()
        // the destination's lines are never cut under the start row (reproduce review finding 2) ...
        compose.assertNotCutAtTop(DriveTags.stopLine(0))
        // ... also after a slow drag by hand that stops inside them
        compose.onNodeWithTag(DriveTags.card(1)).performTouchInput { swipe(center, center + Offset(0f, 30.dp.toPx()), durationMillis = 1_500) }
        compose.assertNotCutAtTop(DriveTags.stopLine(0))
        compose.saveShot("land640-sheet-dragged-day")
        compose.onNodeWithTag(DriveTags.card(1)).performTouchInput { swipe(center, center + Offset(0f, 12.dp.toPx()), durationMillis = 1_500) }
        compose.assertNotCutAtTop(DriveTags.stopLine(0))
        // eight lanes in the narrowest panel (320 dp)
        show(present(guiding(banner = Exit24.eightLanes)), false)
        compose.assertLanesWhole(8)
    }
}
