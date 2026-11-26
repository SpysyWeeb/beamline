package io.github.spysyweeb.beamline.feature.drive

import androidx.compose.ui.test.onNodeWithContentDescription
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.os.SystemClock
import android.view.View
import android.view.WindowInsetsController
import android.view.accessibility.AccessibilityNodeInfo
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.spysyweeb.beamline.api.map.CameraMode
import io.github.spysyweeb.beamline.api.map.CameraState
import io.github.spysyweeb.beamline.api.map.EdgeInsetsDp
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.nav.GpsState
import io.github.spysyweeb.beamline.api.nav.LiveBanner
import io.github.spysyweeb.beamline.api.nav.NavIntent
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.ProblemKind
import io.github.spysyweeb.beamline.api.nav.RouteOptions
import io.github.spysyweeb.beamline.api.nav.Simulation
import io.github.spysyweeb.beamline.api.nav.TripEvent
import io.github.spysyweeb.beamline.api.nav.TripProblem
import io.github.spysyweeb.beamline.api.nav.TripState
import io.github.spysyweeb.beamline.api.nav.TripStop
import io.github.spysyweeb.beamline.feature.drive.Exit24.choice
import io.github.spysyweeb.beamline.feature.drive.Exit24.choosing
import io.github.spysyweeb.beamline.feature.drive.Exit24.downsDrive
import io.github.spysyweeb.beamline.feature.drive.Exit24.fastest
import io.github.spysyweeb.beamline.feature.drive.Exit24.fastestOnly
import io.github.spysyweeb.beamline.feature.drive.Exit24.frame1
import io.github.spysyweeb.beamline.feature.drive.Exit24.frame2
import io.github.spysyweeb.beamline.feature.drive.Exit24.frame3
import io.github.spysyweeb.beamline.feature.drive.Exit24.guiding
import io.github.spysyweeb.beamline.feature.drive.Exit24.present
import io.github.spysyweeb.beamline.feature.drive.Exit24.sheetz
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The drive overlay on the emulator (track D, docs/architecture.md 5.1): every screen by day and
 * night as PNGs in the test APK's files folder (screens/), and the events its buttons send.
 */
@RunWith(AndroidJUnit4::class)
class DriveScreensTest {

    @get:Rule
    val compose = createComposeRule()

    private var ui by mutableStateOf(DriveUi())
    private var night by mutableStateOf(false)
    private var fontScale by mutableStateOf<Float?>(null)
    private val events = mutableListOf<DriveEvent>()
    private var insets = EdgeInsetsDp()

    private val simpler = choice(2, listOf("simpler"), lengthM = 5200.0, timeS = 420.0, hasTolls = true, hasHighways = false, via = listOf("US 20"))
    private val zoo = Place("Erie Zoo", GeoPoint(42.1489, -80.0869), PlaceSource.SEARCH, subtitle = "West 38th Street, Erie")
    private val noRoute = TripProblem(ProblemKind.NO_ROUTE, "No route found from here.", canRetry = true)

    private fun host(start: DriveUi) {
        ui = start
        compose.setContent { PhoneScreen(ui, night, onEvent = { events += it }, onInsets = { insets = it }, fontScale = fontScale) }
        compose.waitForIdle()
    }

    private fun show(u: DriveUi, n: Boolean = night) {
        compose.runOnUiThread {
            ui = u
            night = n
        }
        compose.waitForIdle()
    }

    /** Each screen by day, then by night. */
    private fun shoot(shots: List<Pair<String, DriveUi>>) {
        host(DriveUi())
        for (n in listOf(false, true)) for ((name, u) in shots) {
            show(u, n)
            compose.saveShot("$name-${if (n) "night" else "day"}")
        }
    }

    private fun frameUi(banner: LiveBanner, remainingM: Double, remainingS: Double) =
        present(guiding(banner = banner, remainingM = remainingM, remainingS = remainingS))

    private val frame1Ui get() = frameUi(frame1, Exit24.REMAINING_AT_FRAME1_M, 150.0)
    private val frame2Ui get() = frameUi(frame2, 121.92 + 356.0 + 391.0, 70.0)
    private val frame3Ui get() = frameUi(frame3, 321.8688 + 391.0, 55.0)

    @Test
    fun theThreeSketchFrames() {
        shoot(listOf("frame1" to frame1Ui, "frame2" to frame2Ui, "frame3" to frame3Ui))
        // and their texts, as the overlay shows them (day)
        show(frame1Ui, false)
        compose.onNodeWithTag(DriveTags.BANNER_DISTANCE).assertTextEquals("0.5 mi")
        compose.onNodeWithTag(DriveTags.BANNER_TEXT).assertPlainText("Exit 24 US 19 / Peach St, toward Waterford")
        compose.onNodeWithTag(DriveTags.THEN_CHIP).assert(hasContentDescription("Then left, In 0.3 mi"))
        compose.onNodeWithTag(DriveTags.LANES).assertDoesNotExist()
        show(frame2Ui)
        compose.onNodeWithTag(DriveTags.BANNER_DISTANCE).assertTextEquals("400 ft")
        compose.onNodeWithTag(DriveTags.BANNER_TEXT).assertPlainText("Turn left onto US 19 / Peach St")
        compose.onNodeWithTag(DriveTags.THEN_CHIP).assert(hasContentDescription("Then right, In 0.2 mi"))
        compose.onNodeWithTag(DriveTags.LANES).assert(hasContentDescription("Lanes, left to right: left (allowed), left or straight (best), right"))
        show(frame3Ui)
        compose.onNodeWithTag(DriveTags.BANNER_DISTANCE).assertTextEquals("0.2 mi")
        compose.onNodeWithTag(DriveTags.BANNER_TEXT).assertPlainText("Turn right onto Downs Dr")
        compose.onNodeWithTag(DriveTags.THEN_CHIP).assertDoesNotExist()
        compose.onNodeWithTag(DriveTags.LANES)
            .assert(hasContentDescription("Lanes, left to right: left, straight, straight, straight or right (best)"))
        compose.onNodeWithTag(DriveTags.TRIP_BAR).assertIsDisplayed()
        compose.onNodeWithTag(DriveTags.SIMULATED).assertIsDisplayed()
        // the camera is told the banner's and the trip bar's heights
        assertTrue("top inset $insets", insets.top > 80f)
        assertTrue("bottom inset $insets", insets.bottom > 40f)
    }

    @Test
    fun routeSheetScreens() {
        val two = choosing(choices = listOf(fastestOnly, simpler), selected = 1)
        shoot(
            listOf(
                "sheet" to present(two),
                "sheet-start-blocked" to present(two, locationAllowed = false),
                "sheet-planning" to present(choosing(phase = Phase.PLANNING, choices = emptyList())),
                "sheet-problem" to present(choosing(choices = emptyList(), selected = null, problem = noRoute)),
                "sheet-two-stops" to present(
                    choosing(
                        choices = listOf(choice(3, listOf("fastest", "simpler"), lengthM = 7400.0, timeS = 760.0, warnings = listOf("The route reaches stop 1 0.2 mi from the point you chose."))),
                        stops = listOf(TripStop(sheetz), TripStop(downsDrive)),
                        options = RouteOptions(avoidHighways = true),
                    ),
                ),
            ),
        )
        // the overflow menu is a popup window: the whole screen is taken for it
        show(present(two), false)
        compose.raiseSheetToFull() // item 47: the menu is on the full height's start row
        compose.onNodeWithTag(DriveTags.MORE).performClick()
        compose.waitForIdle()
        saveDeviceScreen("sheet-menu-day")
    }

    @Test
    fun routeSheetEvents() {
        host(present(choosing(choices = listOf(fastestOnly, simpler), selected = 1)))
        compose.raiseSheetToFull() // item 47: it opens at the middle height
        compose.onNodeWithTag(DriveTags.card(2)).performClick()
        compose.onNodeWithTag(DriveTags.AVOID_HIGHWAYS).performClick()
        compose.onNodeWithTag(DriveTags.AVOID_TOLLS).performClick()
        compose.onNodeWithTag(DriveTags.START).assertIsEnabled().performClick()
        compose.onNodeWithTag(DriveTags.START_ROW).performClick()
        compose.onNodeWithTag(DriveTags.ADD_STOP).performClick()
        compose.onNodeWithTag(DriveTags.MORE).performClick()
        compose.onNodeWithTag(DriveTags.SIMULATE).assertIsEnabled().performClick()
        compose.onNodeWithTag(DriveTags.CANCEL).performClick()
        assertEquals(
            listOf(
                DriveEvent.Nav(NavIntent.Choose(2)),
                DriveEvent.Nav(NavIntent.SetOptions(RouteOptions(avoidHighways = true, avoidTolls = false))),
                DriveEvent.Nav(NavIntent.SetOptions(RouteOptions(avoidHighways = false, avoidTolls = true))),
                DriveEvent.Nav(NavIntent.Start()),
                DriveEvent.ChooseStart,
                DriveEvent.SearchForStop,
                DriveEvent.Nav(NavIntent.Start(Simulation())),
                DriveEvent.Nav(NavIntent.End),
            ),
            events,
        )
    }

    @Test
    fun startBlockedWithoutPreciseLocationButSimulateStays() {
        host(present(choosing(), locationAllowed = false))
        compose.onNodeWithTag(DriveTags.START).assertIsNotEnabled()
        compose.onNodeWithText("Allow precise location to start guidance").assertIsDisplayed()
        compose.raiseSheetToFull() // item 47: the menu is on the full height's start row
        compose.onNodeWithTag(DriveTags.MORE).performClick()
        compose.onNodeWithTag(DriveTags.SIMULATE).assertIsEnabled().performClick()
        assertEquals(listOf(DriveEvent.Nav(NavIntent.Start(Simulation()))), events)
        // while planning nothing can start, not even a simulation
        events.clear()
        show(present(choosing(phase = Phase.PLANNING)))
        compose.onNodeWithTag(DriveTags.START).assertIsNotEnabled()
        compose.onNodeWithTag(DriveTags.BUSY).assertIsDisplayed()
        compose.onNodeWithTag(DriveTags.MORE).performClick()
        compose.onNodeWithTag(DriveTags.SIMULATE).assertIsNotEnabled()
    }

    @Test
    fun stopsEditorScreensAndEvents() {
        val three = listOf(TripStop(sheetz), TripStop(zoo), TripStop(downsDrive))
        shoot(
            listOf(
                "stops-editor" to present(choosing(stops = three), stopsEditorOpen = true),
                "stops-editor-guided" to present(
                    guiding(banner = frame1, stops = listOf(TripStop(sheetz, reached = true), TripStop(zoo), TripStop(downsDrive)), nextStopIndex = 1, toNextStopM = 6600.0),
                    stopsEditorOpen = true,
                ),
            ),
        )
        events.clear()
        show(present(choosing(stops = three), stopsEditorOpen = true), false)
        val up = compose.onAllNodesWithContentDescription(DriveTexts.MOVE_UP)
        up[0].assertIsNotEnabled()
        up[1].performClick()
        compose.onAllNodesWithContentDescription(DriveTexts.MOVE_DOWN)[2].assertIsNotEnabled()
        compose.onAllNodesWithContentDescription(DriveTexts.REMOVE)[2].performClick()
        compose.onNodeWithTag(DriveTags.BEST_ORDER).performClick()
        compose.onNodeWithTag(DriveTags.STOPS_EDITOR).assertIsDisplayed()
        compose.onNode(hasTestTag(DriveTags.ADD_STOP) and hasAnyAncestor(hasTestTag(DriveTags.STOPS_EDITOR))).performClick()
        compose.onNodeWithTag(DriveTags.CLOSE_STOPS).performClick()
        assertEquals(
            listOf(
                DriveEvent.Nav(NavIntent.MoveStop(1, 0)),
                DriveEvent.Nav(NavIntent.RemoveStop(2)),
                DriveEvent.Nav(NavIntent.OptimizeStopOrder),
                DriveEvent.SearchForStop,
                DriveEvent.CloseStopsEditor,
            ),
            events,
        )
    }

    /**
     * Rule D8 (batch A; at the bottom start since 2026-11-18): the speed-limit sign and the speed badge by day and night, with the lanes,
     * a GPS chip, the stop card and Recenter shown; neither covers the banner (its lane row
     * included), the chip or card, the trip bar or the map buttons.
     */
    @Test
    fun speedSignAndBadgeCoverNothing() {
        fun withSpeed(t: TripState, limitKmh: Double?, mph: Double?): TripState {
            val v = io.github.spysyweeb.beamline.api.nav.Vehicle(GeoPoint(42.05, -80.08), 0.0, true, io.github.spysyweeb.beamline.api.nav.Motion.MOVING, mph?.let { it / 2.2369362920544 }, 0L)
            return t.copy(guidance = t.guidance!!.copy(vehicle = v, speedLimitKmh = limitKmh))
        }
        val free = CameraState(CameraMode.GUIDE_FREE, showRecenter = true)
        val withStop = listOf(TripStop(sheetz, reached = true), TripStop(downsDrive))
        val shots = listOf(
            "speed-lanes-yellow" to present(withSpeed(guiding(banner = frame2), 72.0, 51.0), camera = free),
            "speed-red" to present(withSpeed(guiding(banner = frame1), 89.0, 66.0)),
            "speed-no-limit" to present(withSpeed(guiding(banner = frame3), null, 24.0)),
            "speed-gps-weak" to present(withSpeed(guiding(banner = frame1, gps = GpsState.WEAK), 89.0, 55.0)),
            "speed-stop-card" to present(withSpeed(guiding(banner = frame1, stops = withStop, nextStopIndex = 1), 89.0, 50.0), shownEvent = TripEvent.StopReached(0, "Sheetz")),
        )
        host(DriveUi())
        for (n in listOf(false, true)) for ((name, u) in shots) {
            show(u, n)
            compose.saveShot("$name-${if (n) "night" else "day"}")
            fun bounds(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().map { it.boundsInRoot }
            val ours = bounds(DriveTags.SPEED_BADGE) + bounds(DriveTags.SPEED_SIGN)
            assertTrue("$name: badge shown", bounds(DriveTags.SPEED_BADGE).size == 1)
            for (tag in listOf(DriveTags.BANNER, DriveTags.LANES, DriveTags.STATUS_CHIP, DriveTags.STOP_CARD, DriveTags.TRIP_BAR, DriveTags.RECENTER, DriveTags.OVERVIEW)) {
                for (other in bounds(tag)) for (r in ours) {
                    assertTrue("$name: speed $r covers $tag $other", !r.overlaps(other))
                }
            }
        }
        show(shots[0].second, false)
        compose.onNodeWithTag(DriveTags.SPEED_SIGN).assert(hasContentDescription("Speed limit 45 mph"))
        compose.onNodeWithTag(DriveTags.SPEED_BADGE).assert(hasContentDescription("Speed 51 mph"))
        show(shots[2].second)
        compose.onNodeWithTag(DriveTags.SPEED_SIGN).assertDoesNotExist()
        show(shots[3].second)
        compose.onNodeWithTag(DriveTags.SPEED_BADGE).assert(hasContentDescription("Speed unknown"))
    }

    /**
     * D8 (batch B): in a window about 390 dp tall (split screen) the stop card under the banner would
     * reach the speed pair: the pair is left out while there is no room and comes back after the card.
     */
    @Test
    fun speedPairHidesInAShortWindowAndComesBack() {
        fun withSpeed(t: TripState): TripState {
            val v = io.github.spysyweeb.beamline.api.nav.Vehicle(GeoPoint(42.05, -80.08), 0.0, true, io.github.spysyweeb.beamline.api.nav.Motion.MOVING, 50 / 2.2369362920544, 0L)
            return t.copy(guidance = t.guidance!!.copy(vehicle = v, speedLimitKmh = 89.0))
        }
        val withStop = listOf(TripStop(sheetz, reached = true), TripStop(downsDrive))
        val card = present(withSpeed(guiding(banner = frame1, stops = withStop, nextStopIndex = 1)), shownEvent = TripEvent.StopReached(0, "Sheetz"))
        val plain = present(withSpeed(guiding(banner = frame1, stops = withStop, nextStopIndex = 1)))
        ui = card
        compose.setContent { PhoneScreen(ui, night, heightDp = 390, onEvent = { events += it }, onInsets = { insets = it }) }
        compose.waitForIdle()
        compose.saveShot("speed-short-card-day")
        fun bounds(tag: String) = compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().map { it.boundsInRoot }
        assertTrue("stop card shown", bounds(DriveTags.STOP_CARD).size == 1)
        val ours = bounds(DriveTags.SPEED_BADGE) + bounds(DriveTags.SPEED_SIGN)
        for (tag in listOf(DriveTags.BANNER, DriveTags.STOP_CARD, DriveTags.TRIP_BAR)) {
            for (other in bounds(tag)) for (r in ours) assertTrue("short: speed $r covers $tag $other", !r.overlaps(other))
        }
        assertTrue("short window with the card: the pair is left out", bounds(DriveTags.SPEED_BADGE).isEmpty())
        show(plain)
        compose.saveShot("speed-short-plain-day")
        assertTrue("short window without the card: the pair is back", bounds(DriveTags.SPEED_BADGE).size == 1)
        for (tag in listOf(DriveTags.BANNER, DriveTags.TRIP_BAR)) {
            for (other in bounds(tag)) for (r in bounds(DriveTags.SPEED_BADGE) + bounds(DriveTags.SPEED_SIGN)) {
                assertTrue("short plain: speed $r covers $tag $other", !r.overlaps(other))
            }
        }
    }

    @Test
    fun statusAndCardScreens() {
        val withStop = listOf(TripStop(sheetz, reached = true), TripStop(downsDrive))
        shoot(
            listOf(
                "rerouting" to present(guiding(banner = frame2, phase = Phase.REROUTING)),
                "reroute-failed" to present(guiding(banner = frame2, phase = Phase.REROUTING, problem = noRoute)),
                "gps-lost" to present(guiding(banner = frame2, gps = GpsState.LOST)),
                "gps-weak" to present(guiding(banner = frame1, gps = GpsState.WEAK)),
                "waiting-for-gps" to present(guiding(banner = null, gps = GpsState.WAITING, simulated = false)),
                "stop-reached" to present(guiding(banner = frame1, stops = withStop, nextStopIndex = 1), shownEvent = TripEvent.StopReached(0, "Sheetz")),
                "next-stop" to present(guiding(banner = frame1, stops = listOf(TripStop(sheetz), TripStop(downsDrive)), nextStopIndex = 0, toNextStopM = 1609.344)),
                "recenter" to present(guiding(banner = frame3), camera = CameraState(CameraMode.GUIDE_FREE, showRecenter = true)),
                "arrived" to present(guiding(banner = LiveBanner(Exit24.arriveView, 12.0, false), phase = Phase.ARRIVED, remainingM = 12.0, remainingS = 2.0)),
                "resume" to present(TripState(phase = Phase.RESUME_OFFERED, stops = listOf(TripStop(sheetz), TripStop(downsDrive)))),
            ),
        )
    }

    @Test
    fun guidanceEvents() {
        host(present(guiding(banner = frame3), camera = CameraState(CameraMode.GUIDE_FREE, showRecenter = true)))
        compose.onNodeWithTag(DriveTags.RECENTER).performClick()
        compose.onNodeWithTag(DriveTags.OVERVIEW).performClick()
        compose.onNodeWithTag(DriveTags.OPEN_STOPS).performClick()
        compose.onNodeWithTag(DriveTags.END).performClick()
        // D7: no banner while re-routing, the card in its place, Retry when the failure allows it
        show(present(guiding(banner = frame2, phase = Phase.REROUTING)))
        compose.onNodeWithTag(DriveTags.BANNER).assertDoesNotExist()
        compose.onNodeWithText("Finding a new route...").assertIsDisplayed()
        show(present(guiding(banner = frame2, phase = Phase.REROUTING, problem = noRoute)))
        compose.onNodeWithText(DriveTexts.RETRY).performClick()
        show(present(guiding(banner = LiveBanner(Exit24.arriveView, 12.0, false), phase = Phase.ARRIVED)))
        compose.onNodeWithTag(DriveTags.BANNER).assertDoesNotExist()
        compose.onNodeWithTag(DriveTags.RECENTER).assertDoesNotExist()
        compose.onNodeWithTag(DriveTags.DONE).performClick()
        show(present(TripState(phase = Phase.RESUME_OFFERED, stops = listOf(TripStop(downsDrive)))))
        compose.onNodeWithText("Continue to Downs Drive?").assertIsDisplayed()
        compose.onNodeWithTag(DriveTags.CONTINUE).performClick()
        compose.onNodeWithTag(DriveTags.DISCARD).performClick()
        assertEquals(
            listOf(
                DriveEvent.Recenter,
                DriveEvent.ShowOverview,
                DriveEvent.OpenStopsEditor,
                DriveEvent.Nav(NavIntent.End),
                DriveEvent.Nav(NavIntent.Retry),
                DriveEvent.Nav(NavIntent.Done),
                DriveEvent.Nav(NavIntent.Resume),
                DriveEvent.Nav(NavIntent.DiscardResume),
            ),
            events,
        )
        // nothing drawn in IDLE, and nothing covered
        show(DriveUi())
        compose.onNodeWithTag(DriveTags.TRIP_BAR).assertDoesNotExist()
        assertEquals(EdgeInsetsDp(0f, 0f), insets)
    }

    /** The largest common system font size (1.3) on the banner with the longest frame text, the sheet and the trip bar. */
    @Test
    fun largeFontScale() {
        fontScale = 1.3f
        val longNames = guiding(banner = frame1.copy(view = Exit24.exitView.copy(towards = listOf("Waterford", "Edinboro"))), stops = listOf(TripStop(sheetz), TripStop(downsDrive)), nextStopIndex = 0, toNextStopM = 1609.344)
        shoot(
            listOf(
                "font130-frame1" to present(longNames),
                "font130-frame2" to frame2Ui,
                "font130-sheet" to present(choosing(choices = listOf(fastestOnly, simpler), selected = 1), locationAllowed = false),
            ),
        )
        show(present(longNames), false)
        compose.onNodeWithTag(DriveTags.THEN_CHIP).assertIsDisplayed()
        compose.assertWhollyShown(DriveTags.END)
        show(present(choosing(choices = listOf(fastestOnly, simpler), selected = 1), locationAllowed = false), false)
        compose.raiseSheetToFull()
        compose.assertWhollyShown(DriveTags.CANCEL)
        compose.assertWhollyShown(DriveTags.card(1))
        compose.assertWhollyShown(DriveTags.START)
        compose.assertWhollyShown(DriveTags.START_BLOCKED)
        compose.assertNotCutAtTop(DriveTags.stopLine(0), fontScale = 1.3f)
    }

    /** Eight lanes on a 360 dp phone, the best on the far right: every lane whole (contract review finding 1). */
    @Test
    fun manyLanesFitTheBanner() {
        val eight = present(guiding(banner = Exit24.eightLanes))
        shoot(listOf("lanes8" to eight))
        show(eight, false)
        compose.assertLanesWhole(8)
        compose.onNodeWithTag(DriveTags.LANES).assert(
            hasContentDescription("Lanes, left to right: left, left, left or straight, straight, straight, straight, straight or right (allowed), right (best)"),
        )
        // frame 3 has a lane to choose: since 0.1.30 the lane strip (D9b), boxes 64 dp tall across the banner
        show(frame3Ui, false)
        compose.assertLanesWhole(4)
        val box = compose.onNodeWithTag(DriveTags.LANES, useUnmergedTree = true).fetchSemanticsNode().children.first().size
        val density = InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density
        assertEquals("frame 3 lane strip box height (px)", Math.round(64 * density), box.height)
    }

    @Test
    fun selectedCardIsMarkedForScreenReaders() {
        host(present(choosing(choices = listOf(fastestOnly, simpler), selected = 1)))
        compose.raiseSheetToFull() // item 47: the full cards
        compose.onNodeWithTag(DriveTags.card(1)).assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, true))
        compose.onNodeWithTag(DriveTags.card(2)).assert(SemanticsMatcher.expectValue(SemanticsProperties.Selected, false))
    }

    /** Item 47: the From row's swap button asks for the trip the other way round. */
    @Test
    fun swapButtonSwapsTheEnds() {
        host(present(choosing(choices = listOf(fastestOnly, simpler), selected = 1)))
        compose.raiseSheetToFull()
        events.clear()
        compose.onNodeWithContentDescription(DriveTexts.SWAP).assertIsDisplayed().assertIsEnabled().performClick()
        assertEquals(listOf<DriveEvent>(DriveEvent.Nav(NavIntent.SwapEnds)), events)
    }

    /** Item 47: a slower card says by how much against the fastest; the fastest says nothing; an option that is on has a check. */
    @Test
    fun slowerCardsSayByHowMuch() {
        // 370 s and 610 s: "+4 min" (the sheet's own simpler is 50 s slower: within a minute, nothing)
        assertEquals(null, present(choosing(choices = listOf(fastestOnly, simpler), selected = 1)).planning!!.cards.first { it.choiceId == 2L }.slowerText)
        val ui = present(choosing(choices = listOf(fastestOnly, simpler.copy(timeS = 610.0)), selected = 1))
        val fast = ui.planning!!.cards.first { it.choiceId == 1L }
        val slow = ui.planning!!.cards.first { it.choiceId == 2L }
        assertEquals(null, fast.slowerText)
        assertTrue(slow.slowerText.toString(), slow.slowerText!!.startsWith("+") && slow.slowerText!!.endsWith("min"))
        host(ui)
        compose.onNodeWithTag(DriveTags.lowCard(2)).assertIsDisplayed()
        compose.onNodeWithText(DriveTexts.LABEL_SEPARATOR + slow.slowerText!!, useUnmergedTree = true).assertIsDisplayed()
        compose.raiseSheetToFull()
        compose.onNodeWithText(slow.slowerText!!, useUnmergedTree = true).assertIsDisplayed()
    }

    /**
     * The real status bar over the banner (whole-device screenshots, the overlay edge to edge as in
     * the app): its icons are light while the dark banner or the re-route card is under them, and
     * follow the map again afterwards (dark over the day map, light over the night map).
     */
    @Test
    fun statusBarIconsOverTheBanner() {
        // Read and set through the platform's own insets controller (what androidx core uses from API 35),
        // not through the overlay's helper, so the test does not check the code with itself.
        assumeTrue("the window's appearance is read through WindowInsetsController from API 35", Build.VERSION.SDK_INT >= 35)
        var view: View? = null
        ui = present(choosing())
        compose.setContent { PhoneScreen(ui, night, fullWindow = true, onView = { view = it }) }
        compose.waitForIdle()
        val lightBackground = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
        fun lightIcons(): Boolean {
            var light = false
            compose.runOnUiThread {
                val controller = view!!.context.activity().window.insetsController!!
                light = controller.systemBarsAppearance and lightBackground == 0
            }
            return light
        }
        for (n in listOf(false, true)) {
            val label = if (n) "night" else "day"
            // Start as :app leaves the window for this map (MainActivity: dark icons by day, light by night).
            show(present(choosing()), n)
            compose.runOnUiThread {
                view!!.context.activity().window.insetsController!!.setSystemBarsAppearance(if (n) 0 else lightBackground, lightBackground)
            }
            assertEquals("start ($label)", n, lightIcons())
            show(frame1Ui, n)
            assertTrue("light icons over the banner ($label)", lightIcons())
            saveDeviceScreen("statusbar-frame1-$label")
            show(present(guiding(banner = frame2, phase = Phase.REROUTING)), n)
            assertTrue("light icons over the re-route card ($label)", lightIcons())
            show(present(choosing()), n)
            assertEquals("icons follow the map once the banner is gone ($label)", n, lightIcons())
            saveDeviceScreen("statusbar-sheet-$label")
        }
    }

    /**
     * What a screen reader is given (the accessibility nodes the app exposes, as an accessibility
     * service reads them through UiAutomation; no TalkBack on this emulator image): every control has
     * a name, and the frame texts, chip, lanes and trip bar are there. Written to screens/a11y-*.txt.
     */
    @Test
    fun screenReaderNodes() {
        val bar = "Arrive 3:31 PM"
        val cases = listOf(
            Triple("a11y-frame2", frame2Ui, listOf("400 ft", "Turn left onto US 19 / Peach St", "Then right, In 0.2 mi", "Lanes, left to right: left (allowed), left or straight (best), right", bar, DriveTexts.STOPS, DriveTexts.END_TRIP, DriveTexts.OVERVIEW)),
            Triple("a11y-sheet", present(choosing(choices = listOf(fastestOnly, simpler), selected = 1), locationAllowed = false), listOf("Your position", "Downs Drive", "Fastest", "Simpler", DriveTexts.AVOID_HIGHWAYS, DriveTexts.AVOID_TOLLS, DriveTexts.START, "Allow precise location to start guidance", DriveTexts.MORE_OPTIONS, DriveTexts.CANCEL_ROUTE)),
            Triple("a11y-stops-editor", present(choosing(stops = listOf(TripStop(sheetz), TripStop(zoo), TripStop(downsDrive))), stopsEditorOpen = true), listOf(DriveTexts.STOPS, "Sheetz", "Erie Zoo", DriveTexts.MOVE_UP, DriveTexts.MOVE_DOWN, DriveTexts.REMOVE, DriveTexts.ADD_STOP, DriveTexts.BEST_ORDER, DriveTexts.DONE)),
            Triple("a11y-reroute-failed", present(guiding(banner = frame2, phase = Phase.REROUTING, problem = noRoute)), listOf("No route found from here.", DriveTexts.RETRY, "Arrive 3:33 PM")),
            Triple("a11y-arrived", present(guiding(banner = LiveBanner(Exit24.arriveView, 12.0, false), phase = Phase.ARRIVED)), listOf("Arrived", "Downs Drive", "On the right", DriveTexts.DONE)),
        )
        host(DriveUi())
        for ((name, u, wanted) in cases) {
            show(u, false)
            if (name == "a11y-sheet") compose.raiseSheetToFull() // item 47: the sheet's every line, at full height
            // The accessibility side follows the screen a moment later (Compose sends its changes at
            // most every 100 ms): read again until every wanted name is there, for at most 5 s.
            val deadline = SystemClock.uptimeMillis() + 5_000
            var nodes = accessibilityNodes()
            while (wanted.any { w -> nodes.none { it.label.contains(w) } } && SystemClock.uptimeMillis() < deadline) {
                Thread.sleep(200)
                nodes = accessibilityNodes()
            }
            File(screensDir(), "$name.txt").writeText(nodes.joinToString("\n") { it.line() } + "\n")
            val unnamed = nodes.filter { it.clickable && it.label.isBlank() }
            assertTrue("$name: controls without a name: ${unnamed.map { it.line() }}", unnamed.isEmpty())
            val all = nodes.joinToString("\n") { it.label }
            for (w in wanted) assertTrue("$name: '$w' not given to a screen reader:\n$all", all.contains(w))
            if (name == "a11y-sheet") {
                // the chosen route says so (selected, or checked as Compose reports `selected` outside tabs), the other does not
                fun card(prefix: String) = nodes.first { it.clickable && it.label.startsWith("<- $prefix") }
                val chosen = card("Fastest")
                val other = card("Simpler")
                assertTrue("chosen card: ${chosen.line()}", chosen.selected || chosen.state.startsWith("checked"))
                assertTrue("other card: ${other.line()}", !other.selected && !other.state.startsWith("checked"))
            }
        }
    }
}

/**
 * The node's text with D1's no-break spaces (U+00A0, short road names kept on one line) read as plain
 * spaces, so the expected banner texts stay readable.
 */
private fun SemanticsNodeInteraction.assertPlainText(expected: String): SemanticsNodeInteraction = assert(
    SemanticsMatcher("text (no-break spaces as spaces) = '$expected'") { n ->
        n.config.getOrElseNullable(SemanticsProperties.Text) { null }?.joinToString("") { it.text }?.replace('\u00A0', ' ') == expected
    },
)

/** One accessibility node as a service sees it ([state]: checked / not checked and the state description, when it has them). */
private class A11yNode(val label: String, val role: String, val clickable: Boolean, val enabled: Boolean, val selected: Boolean, val state: String = "") {
    fun line() = buildString {
        append(role.substringAfterLast('.'))
        if (clickable) append(" [click]")
        if (!enabled) append(" [off]")
        if (selected) append(" [selected]")
        if (state.isNotEmpty()) append(" [").append(state).append("]")
        append(": ").append(label.replace('\n', ' '))
    }
}

/**
 * The visible nodes of the active window that carry a name or take a click, in tree order. A control
 * without a name of its own is named by its descendants that are not controls themselves (an icon
 * button's icon), as a screen reader speaks it; that name is shown as "<- ...".
 */
private fun accessibilityNodes(): List<A11yNode> {
    val ua = InstrumentationRegistry.getInstrumentation().uiAutomation
    val root = ua.rootInActiveWindow ?: error("no active window")
    // D1 puts no-break spaces into short road names; a screen reader speaks them as spaces
    fun own(n: AccessibilityNodeInfo) = listOfNotNull(n.contentDescription, n.text).joinToString(" ").replace('\u00A0', ' ').trim()
    fun childNames(n: AccessibilityNodeInfo): List<String> = (0 until n.childCount).mapNotNull { n.getChild(it) }
        .filter { !it.isClickable }
        .flatMap { c -> listOf(own(c)).filter { it.isNotEmpty() } + childNames(c) }
    val out = ArrayList<A11yNode>()
    fun walk(n: AccessibilityNodeInfo?) {
        if (n == null) return
        var label = own(n)
        if (label.isEmpty() && n.isClickable) label = childNames(n).joinToString(" ").let { if (it.isEmpty()) it else "<- $it" }
        if (n.isVisibleToUser && (label.isNotEmpty() || n.isClickable)) {
            val state = listOfNotNull(
                if (n.isCheckable) (if (n.isChecked) "checked" else "not checked") else null,
                n.stateDescription?.toString()?.takeIf { it.isNotEmpty() },
            ).joinToString(", ")
            out += A11yNode(label, n.className?.toString().orEmpty(), n.isClickable, n.isEnabled, n.isSelected, state)
        }
        for (i in 0 until n.childCount) walk(n.getChild(i))
    }
    walk(root)
    return out
}

private tailrec fun Context.activity(): Activity = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.activity()
    else -> error("no activity")
}
