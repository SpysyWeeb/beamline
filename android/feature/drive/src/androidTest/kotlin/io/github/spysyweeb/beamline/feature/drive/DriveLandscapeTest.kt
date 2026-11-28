package io.github.spysyweeb.beamline.feature.drive

import android.content.res.Configuration
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.ui.Alignment
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasTestTag
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipe
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.spysyweeb.beamline.api.map.CameraMode
import io.github.spysyweeb.beamline.api.map.CameraState
import io.github.spysyweeb.beamline.api.map.EdgeInsetsDp
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.nav.GpsState
import io.github.spysyweeb.beamline.api.nav.LiveBanner
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.TripStop
import io.github.spysyweeb.beamline.feature.drive.Exit24.onRoad
import io.github.spysyweeb.beamline.ui.theme.BeamlineTheme
import io.github.spysyweeb.beamline.ui.theme.SHEET_HANDLE_TAG
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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

    /**
     * Play test P10 (review): the tester's emulator on its side (915 x 412 dp, laid out from the window's top start, so
     * the panel is on screen whatever the system bars take). Guided with the stops editor open, the editor in the side
     * panel stops under the banner (it reached over the turn), with Done, Add stop and the first stop in view.
     */
    @Test
    fun tallPhone() {
        val three = listOf(TripStop(sheetz), TripStop(zoo), TripStop(downsDrive))
        ui = present(guiding(banner = LiveBanner(Exit24.arriveView, 100.0, false), stops = three), stopsEditorOpen = true)
        compose.setContent {
            BeamlineTheme(night = false) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).size(915.dp, 412.dp)) {
                        DriveOverlayContent(ui, {}, { insets = it }, safe = WindowInsets(top = 24.dp))
                    }
                }
            }
        }
        compose.waitForIdle()
        val banner = rect(DriveTags.BANNER)
        val editor = rect(DriveTags.STOPS_EDITOR)
        saveDeviceScreen("land412-stops-editor-guided-day")
        assertTrue("banner $banner, editor $editor", banner.bottom <= editor.top + px)
        compose.onNodeWithTag(DriveTags.CLOSE_STOPS).assertIsDisplayed()
        compose.onNode(hasTestTag(DriveTags.ADD_STOP) and hasAnyAncestor(hasTestTag(DriveTags.STOPS_EDITOR))).assertIsDisplayed()
        compose.onNodeWithTag(DriveTags.stopRow(0)).assertIsDisplayed()
        assertEquals(EdgeInsetsDp(start = 400f), insets)
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

    /**
     * The 0.1.40 replay (item 51, P10): the tester's phone on its side (914 x 411 dp), guided with one stop and the
     * destination, the stops editor open under the banner. Both stop rows are whole (the second was cut in half under
     * the empty note line kept below Add stop); a note with Undo that comes and goes then does not move Done (the
     * editor is as tall as the panel lets it be: the rows give the room). With three stops the rows say they scroll.
     */
    @Test
    fun theStopsEditorUnderTheBannerShowsItsRowsWhole() {
        val two = listOf(TripStop(sheetz), TripStop(downsDrive))
        val arrive = LiveBanner(Exit24.arriveView, 100.0, false)   // a two-line banner, as the replay's turn
        ui = present(guiding(banner = arrive, stops = two), stopsEditorOpen = true)
        compose.setContent {
            BeamlineTheme(night = false) {
                Box(Modifier.fillMaxSize()) {
                    Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).size(914.dp, 411.dp)) {
                        DriveOverlayContent(ui, {}, { insets = it }, safe = WindowInsets(top = 24.dp))
                    }
                }
            }
        }
        compose.waitForIdle()
        saveDeviceScreen("land411-stops-editor-two-stops-day")
        val banner = rect(DriveTags.BANNER)
        val editor = rect(DriveTags.STOPS_EDITOR)
        assertTrue("banner $banner, editor $editor", banner.bottom <= editor.top + px)
        compose.assertWhollyShown(DriveTags.stopRow(0))
        compose.assertWhollyShown(DriveTags.stopRow(1))
        compose.onNodeWithTag(DriveTags.STOPS_MORE_BELOW).assertDoesNotExist()
        val done = rect(DriveTags.CLOSE_STOPS)
        compose.runOnUiThread { ui = ui.copy(stopsEditor = ui.stopsEditor!!.copy(note = "Stop removed", canUndo = true)) }
        compose.waitForIdle()
        compose.onNodeWithTag(DriveTags.STOPS_UNDO).assertIsDisplayed()
        assertEquals("Done with the note", done.top.value, rect(DriveTags.CLOSE_STOPS).top.value, 0.5f)
        compose.runOnUiThread { ui = ui.copy(stopsEditor = ui.stopsEditor!!.copy(note = null, canUndo = false)) }
        compose.waitForIdle()
        assertEquals("Done after the note", done.top.value, rect(DriveTags.CLOSE_STOPS).top.value, 0.5f)
        compose.runOnUiThread { ui = present(guiding(banner = arrive, stops = listOf(TripStop(sheetz), TripStop(zoo), TripStop(downsDrive))), stopsEditorOpen = true) }
        compose.waitForIdle()
        saveDeviceScreen("land411-stops-editor-three-stops-day")
        compose.assertWhollyShown(DriveTags.stopRow(0))
        compose.onNodeWithTag(DriveTags.STOPS_MORE_BELOW).assertExists()
    }

    /**
     * The same on the emulator of the replay: the stops editor opened upright, then the phone turned on its side (the
     * window's height changes under the open editor): the rows are whole on its side too.
     */
    @Test
    fun theStopsEditorTurnedOnItsSideShowsItsRowsWhole() {
        var turned by mutableStateOf(false)
        ui = present(guiding(banner = LiveBanner(Exit24.arriveView, 100.0, false), stops = listOf(TripStop(sheetz), TripStop(downsDrive))), stopsEditorOpen = true)
        compose.setContent {
            val config = LocalConfiguration.current
            val shown = Configuration(config).apply {
                screenWidthDp = if (turned) 914 else 411
                screenHeightDp = if (turned) 411 else 914
            }
            CompositionLocalProvider(LocalConfiguration provides shown) {
                BeamlineTheme(night = false) {
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.wrapContentSize(Alignment.TopStart, unbounded = true).size(if (turned) DpSize(914.dp, 411.dp) else DpSize(411.dp, 914.dp))) {
                            DriveOverlayContent(ui, {}, { insets = it }, safe = WindowInsets(top = 24.dp))
                        }
                    }
                }
            }
        }
        compose.waitForIdle()
        compose.runOnUiThread { turned = true }
        compose.waitForIdle()
        assertEquals(EdgeInsetsDp(start = 400f), insets)
        compose.assertWhollyShown(DriveTags.stopRow(0))
        compose.assertWhollyShown(DriveTags.stopRow(1))
    }

    private fun state(): String = compose.onNodeWithTag(SHEET_HANDLE_TAG).fetchSemanticsNode().config[SemanticsProperties.StateDescription]

    private val px = 0.5.dp
    private fun rect(tag: String): DpRect = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
    private fun overlaps(a: DpRect, b: DpRect) = a.left < b.right && b.left < a.right && a.top < b.bottom && b.top < a.bottom

    /**
     * Play test P52: a route sheet opened upright (the middle height) and then turned into the side panel opens at the
     * panel's height (full: the options and the steps), says it scrolls further, and turned back is at the middle again.
     */
    @Test
    fun turningIntoTheSidePanelShowsTheWholeRouteSheet() {
        var box by mutableStateOf(DpSize(500.dp, 360.dp))
        ui = DriveUi(planning = Exit24.routeSheet(steps = 7))
        compose.setContent {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.3f)) {
                BeamlineTheme(night = false) { Box(Modifier.size(box)) { DriveOverlayContent(ui, {}, { insets = it }) } }
            }
        }
        compose.waitForIdle()
        // narrower than 560 dp: under the map, at the middle height
        assertEquals("Half open", state())
        compose.runOnUiThread { box = DpSize(800.dp, 360.dp) }
        compose.waitForIdle()
        assertEquals("Full height", state())
        assertEquals(EdgeInsetsDp(start = 400f), insets)
        compose.onNodeWithTag(DriveTags.MORE_BELOW).assertExists()
        compose.assertWhollyShown(DriveTags.START)
        // review (0.1.40): the selected card is brought into view clear of the fade, not under it ("cut off above Start")
        val card = rect(DriveTags.card(1))
        val fade = rect(DriveTags.MORE_BELOW)
        assertTrue("card $card, fade $fade", card.bottom <= fade.top + px)
        compose.saveShotOf(DriveTags.OVERLAY, "land-sheet-turned-font130-day")
        compose.onNodeWithTag(SHOW_ALL_STEPS_TAG).performScrollTo()
        compose.waitForIdle()
        compose.onNodeWithTag(DriveTags.MORE_BELOW).assertDoesNotExist()
        compose.runOnUiThread { box = DpSize(500.dp, 360.dp) }
        compose.waitForIdle()
        assertEquals("Half open", state())
    }

    /**
     * Play test P32 (and P1 inside the overlay): on its side with a navigation bar at the end, the road label's end room
     * counts from the bar, as the map buttons' does: a long name never runs under Overview.
     */
    @Test
    fun roadLabelKeepsClearOfTheButtonsBesideANavigationBar() {
        ui = present(onRoad(guiding(banner = frame2), "General Strong Vincent Memorial Hwy"))
        compose.setContent {
            BeamlineTheme(night = false) {
                Box(Modifier.size(800.dp, 360.dp).testTag("box")) {
                    DriveOverlayContent(ui, {}, { insets = it }, safe = WindowInsets(top = 24.dp, right = 48.dp))
                }
            }
        }
        compose.waitForIdle()
        val box = rect("box")
        val label = rect(DriveTags.CURRENT_ROAD)
        val overview = rect(DriveTags.OVERVIEW)
        assertFalse("label $label overlaps Overview $overview", overlaps(label, overview))
        assertTrue("Overview $overview, box $box", overview.right <= box.left + 752.dp + px)
        assertTrue("label $label, box $box", label.right <= box.left + 676.dp + px)
    }

    /**
     * Play test P7: the overlay reports where its top stack ends (the banner, and the chip under it), so the app keeps
     * the windows it puts in the side panel under the banner; 0 with nothing at the top.
     */
    @Test
    fun theTopStackIsReportedForTheWindowsInThePanel() {
        var top = -1f
        ui = present(guiding(banner = frame2))
        compose.setContent {
            BeamlineTheme(night = false) {
                Box(Modifier.size(800.dp, 360.dp).testTag("box")) { DriveOverlayContent(ui, {}, { insets = it }, onTopStack = { top = it }) }
            }
        }
        compose.waitForIdle()
        val box = rect("box")
        val banner = rect(DriveTags.BANNER)
        assertEquals("the banner block ends with the banner", (banner.bottom - box.top).value, top, 1f)
        show(present(guiding(banner = frame2, gps = GpsState.WEAK)), false)
        val chip = rect(DriveTags.STATUS_CHIP)
        assertTrue("top $top, chip $chip", top >= (chip.bottom - box.top).value - 1f)
        show(DriveUi(), false)
        assertEquals(0f, top)
    }
}
