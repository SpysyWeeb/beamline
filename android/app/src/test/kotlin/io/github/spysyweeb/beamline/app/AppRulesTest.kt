package io.github.spysyweeb.beamline.app

import io.github.spysyweeb.beamline.api.map.EdgeInsetsDp
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.model.SavedKind
import io.github.spysyweeb.beamline.api.model.SavedPlace
import io.github.spysyweeb.beamline.app.ui.AppRules
import io.github.spysyweeb.beamline.app.ui.BottomChrome
import io.github.spysyweeb.beamline.ui.theme.isSideLayout
import io.github.spysyweeb.beamline.ui.theme.sidePanelWidth
import androidx.compose.ui.unit.dp
import io.github.spysyweeb.beamline.feature.drive.BannerUi
import io.github.spysyweeb.beamline.feature.search.NearSource
import io.github.spysyweeb.beamline.feature.search.SearchContext
import io.github.spysyweeb.beamline.feature.search.SearchMode
import io.github.spysyweeb.beamline.app.ui.ShownStep
import io.github.spysyweeb.beamline.app.ui.StopEnd
import io.github.spysyweeb.beamline.app.ui.AddStopPanelPlace
import io.github.spysyweeb.beamline.app.ui.Layer
import io.github.spysyweeb.beamline.app.ui.StopView
import io.github.spysyweeb.beamline.app.ui.StopViewCall
import io.github.spysyweeb.beamline.feature.drive.ArrivalQuestionUi
import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.api.nav.END_UNDO_MS
import io.github.spysyweeb.beamline.api.nav.ManeuverKind
import io.github.spysyweeb.beamline.api.nav.NavIntent
import io.github.spysyweeb.beamline.api.nav.RouteChoice
import io.github.spysyweeb.beamline.api.nav.RouteStep
import io.github.spysyweeb.beamline.api.nav.StopPreview
import io.github.spysyweeb.beamline.api.nav.TripState
import io.github.spysyweeb.beamline.api.nav.TripStop
import io.github.spysyweeb.beamline.feature.drive.DriveUi
import io.github.spysyweeb.beamline.feature.drive.GuidanceStatusUi
import io.github.spysyweeb.beamline.feature.drive.GuidanceUi
import io.github.spysyweeb.beamline.feature.drive.StopsEditorUi
import io.github.spysyweeb.beamline.feature.drive.TripBarUi
import io.github.spysyweeb.beamline.feature.search.ResultRowUi
import io.github.spysyweeb.beamline.feature.search.ResultsUi
import io.github.spysyweeb.beamline.feature.search.StopResultsUi
import io.github.spysyweeb.beamline.feature.about.FIRST_RUN_NOTICE_VERSION
import io.github.spysyweeb.beamline.settings.AppState
import io.github.spysyweeb.beamline.api.nav.Phase
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The app shell's own rules (AppRules), found by the integration review. */
class AppRulesTest {

    /** SP19, next run part 1: a dropped pin saved from its own sheet keeps the saved id (shown as saved, Edit). */
    @Test
    fun savedDroppedPinSheetKeepsTheSavedId() {
        val pt = GeoPoint(42.12919, -80.08506)   // downtown Erie, a made-up saved spot
        val pin = Place("Dropped pin", pt, PlaceSource.MAP_PIN, subtitle = "42.12919, -80.08506")
        val sp = SavedPlace("s0123456789abcdef", SavedKind.OTHER, "Lunch spot", pt, "42.12919, -80.08506")
        assertEquals(pin.copy(savedId = sp.id), AppRules.sheetAfterSave(pin, pin, sp))
        assertNull(AppRules.sheetAfterSave(null, pin, sp))                       // the sheet was closed
        assertNull(AppRules.sheetAfterSave(pin, null, sp))                       // not this sheet's Save
        assertNull(AppRules.sheetAfterSave(pin, pin.copy(name = "Point on the map"), sp))   // another sheet now
        assertNull(AppRules.sheetAfterSave(pin, pin, sp.copy(point = GeoPoint(42.13, -80.085))))   // saved elsewhere
        val known = pin.copy(savedId = "s1111111111111111")
        assertNull(AppRules.sheetAfterSave(known, known, sp))                    // already a saved place's sheet
    }

    @Test
    fun `the States screen and its catalog request wait for the accepted driver notice`() {
        assertFalse(AppRules.noticeAccepted(AppState()), "a fresh install")
        assertFalse(AppRules.noticeAccepted(AppState(firstRunNoticeAccepted = FIRST_RUN_NOTICE_VERSION - 1)), "an older notice")
        assertTrue(AppRules.noticeAccepted(AppState(firstRunNoticeAccepted = FIRST_RUN_NOTICE_VERSION)))
    }

    @Test
    fun `the route preview is padded for the status bar and the credit line, the guide camera is not moved`() {
        // CHOOSING: D's top block is empty, its bottom block is the route sheet
        val sheet = EdgeInsetsDp(top = 0f, bottom = 420f)
        assertEquals(EdgeInsetsDp(top = 24f, bottom = 420f + 72f), AppRules.cameraInsets(sheet, statusBarTop = 24f, chromeBottom = 72f, driving = false))
        // GUIDING: the banner block already reaches under the status bar; the credit is not added
        val banner = EdgeInsetsDp(top = 160f, bottom = 96f)
        assertEquals(banner, AppRules.cameraInsets(banner, statusBarTop = 24f, chromeBottom = 72f, driving = true))
        // on its side D's panel takes the start; the credit sits beside it, not under the map
        // browsing with a place sheet open (a picked place, K1): the place shows above the sheet and the credit
        assertEquals(EdgeInsetsDp(top = 24f, bottom = 300f + 72f), AppRules.cameraInsets(EdgeInsetsDp(), statusBarTop = 24f, chromeBottom = 72f, driving = false, placeSheet = 300f))
        // a place sheet over the route sheet: the higher of the two
        assertEquals(EdgeInsetsDp(top = 24f, bottom = 420f + 72f), AppRules.cameraInsets(sheet, statusBarTop = 24f, chromeBottom = 72f, driving = false, placeSheet = 300f))
        // while driving a sheet does not move the guide camera
        assertEquals(banner, AppRules.cameraInsets(banner, statusBarTop = 24f, chromeBottom = 72f, driving = true, placeSheet = 300f))
        val side = EdgeInsetsDp(start = 360f)
        assertEquals(EdgeInsetsDp(top = 24f, start = 360f), AppRules.cameraInsets(side, statusBarTop = 24f, chromeBottom = 72f, driving = false))
    }

    @Test
    fun `the credit keeps clear of the Recenter pill`() {
        assertEquals(8f, AppRules.creditEndPaddingDp(driving = false, recenterShown = false, fontScale = 1f))
        assertEquals(72f, AppRules.creditEndPaddingDp(driving = true, recenterShown = false, fontScale = 1f))
        // the pill measured on the emulator: 129 dp wide plus its 12 dp margin
        assertTrue(AppRules.creditEndPaddingDp(driving = true, recenterShown = true, fontScale = 1f) >= 141f + 8f)
        assertTrue(AppRules.creditEndPaddingDp(driving = true, recenterShown = true, fontScale = 1.3f) > AppRules.creditEndPaddingDp(true, true, 1f))
    }

    @Test
    fun `the credit is one short line that fades, findable again by its mark, gone while guiding`() {
        val line = "Natural Earth · US Census Bureau · © OpenStreetMap contributors · U.S. Census Bureau TIGER/Line"
        assertEquals("© OpenStreetMap", AppRules.shortCredit(line))
        // only the overview (public domain): no line on the map, the mark still opens the list
        assertNull(AppRules.shortCredit("Natural Earth · US Census Bureau"))
        // five seconds after the map appears, then faded (and at once after a touch: until = 0)
        assertTrue(AppRules.creditShown(Phase.IDLE, nowMs = 10_000, untilMs = 10_000 + 1))
        assertFalse(AppRules.creditShown(Phase.IDLE, nowMs = 15_000, untilMs = 15_000))
        assertFalse(AppRules.creditShown(Phase.IDLE, nowMs = 10_000, untilMs = 0))
        // the whole route preview
        assertTrue(AppRules.creditShown(Phase.CHOOSING, nowMs = 99_000, untilMs = 0))
        assertTrue(AppRules.creditShown(Phase.PLANNING, nowMs = 99_000, untilMs = 0))
        // guiding: the first seconds only, then nothing (no mark either)
        assertTrue(AppRules.creditShown(Phase.GUIDING, nowMs = 1_000, untilMs = 1_000 + AppRules.CREDIT_SHOWN_MS))
        assertFalse(AppRules.creditShown(Phase.GUIDING, nowMs = 1_000 + AppRules.CREDIT_SHOWN_MS, untilMs = 1_000 + AppRules.CREDIT_SHOWN_MS))
        assertFalse(AppRules.creditMark(driving = true, creditLine = line))
        assertTrue(AppRules.creditMark(driving = false, creditLine = line))
        assertFalse(AppRules.creditMark(driving = false, creditLine = ""))
        assertTrue(AppRules.OSM_LICENCE.contains("openstreetmap.org/copyright"))
    }

    @Test
    fun `R4 the end bar - Trip ended with Undo, then Continuing until the phase leaves IDLE`() {
        assertEquals("Trip ended", AppRules.endBar(tripEnded = 1L, continuing = false, phase = Phase.IDLE))
        assertEquals("Continuing the trip...", AppRules.endBar(tripEnded = null, continuing = true, phase = Phase.IDLE))
        assertNull(AppRules.endBar(tripEnded = null, continuing = true, phase = Phase.GUIDING))
        // audit F31: a route closed before it started says so, with the same Undo
        assertEquals("Directions closed", AppRules.endBar(tripEnded = 1L, continuing = false, phase = Phase.IDLE, endedText = io.github.spysyweeb.beamline.app.ui.DIRECTIONS_CLOSED))
        assertNull(AppRules.endBar(tripEnded = null, continuing = true, phase = Phase.RESUME_OFFERED))
        assertNull(AppRules.endBar(tripEnded = 1L, continuing = false, phase = Phase.CHOOSING))
        assertNull(AppRules.endBar(tripEnded = null, continuing = false, phase = Phase.IDLE))
    }

    // ------------------------------------------------------------------ 0.1.40 (track I part 1)

    /** Made-up places on a line north of Erie (no real addresses). */
    private val start = GeoPoint(42.10, -80.10)
    private val mid = GeoPoint(42.15, -80.10)
    private val end = GeoPoint(42.20, -80.10)
    private val stopA = Place("Stop A", GeoPoint(42.12, -80.09), PlaceSource.SEARCH)
    private val stopB = Place("Stop B", GeoPoint(42.16, -80.11), PlaceSource.SEARCH)
    private val dest = Place("Made-up Park", end, PlaceSource.SEARCH)

    private fun choice(id: Long, geometry: List<GeoPoint>, legEnds: List<Int>, steps: Int = 0) = RouteChoice(
        id, listOf("fastest"), 10_000.0, 900.0, 3, false, false, emptyList(), geometry, legEnds,
        Bounds(-80.12, 42.09, -80.08, 42.21), emptyList(),
        steps = (0 until steps).map { RouteStep(ManeuverKind.LEFT, "Turn left", emptyList(), 100.0, geometry[it % geometry.size]) },
    )

    @Test
    fun `item 51 the stop results count in the camera insets while driving`() {
        // AS test 8: guiding, the window 300 dp high over the map; the banner's 120 stays on top
        assertEquals(EdgeInsetsDp(top = 120f, bottom = 340f),
            AppRules.cameraInsets(EdgeInsetsDp(top = 120f, bottom = 80f), 24f, 40f, driving = true, stopSheet = 300f))
    }

    @Test
    fun `item 51 the stop results in the side panel keep the start inset, and planning has no stale route sheet height`() {
        // AS test 9b: on its side the window is in the panel (stopSheet 0): the start inset holds it
        val side = AppRules.cameraInsets(EdgeInsetsDp(start = 400f), 24f, 40f, driving = true, stopSheet = 0f)
        assertEquals(400f, side.start)
        assertEquals(0f, side.bottom)
        // planning: the stop overlay reports top-only insets (no route sheet), the window counts
        val planning = AppRules.cameraInsets(EdgeInsetsDp(top = 0f), 24f, 40f, driving = false, stopSheet = 300f)
        assertEquals(24f, planning.top)
        assertEquals(340f, planning.bottom)
    }

    @Test
    fun `item 51 the stop view - the list with where it joins, the card's way to the stop`() {
        // AS test 9: CHOOSING one leg start -> end; J is the route's first point (StopLeg.lineAhead)
        val route = choice(1, listOf(start, mid, end), legEnds = listOf(2))
        val trip = TripState(phase = Phase.CHOOSING, stops = listOf(TripStop(dest)), choices = listOf(route), selectedChoiceId = 1)
        val rows = listOf(ResultRowUi("r0", "fuel", "Stop A", null, null, null), ResultRowUi("r1", "fuel", "Stop B", null, null, null))
        val list = ResultsUi("gas", rows, listOf(stopA, stopB), stop = StopResultsUi(7, working = false))
        assertEquals(listOf(stopA.point, stopB.point, start), AppRules.stopViewPoints(list, null, trip))
        // a row selected, its route not there yet: J and the place
        val card = list.copy(selected = 1)
        assertEquals(listOf(start, stopB.point), AppRules.stopViewPoints(card, null, trip))
        assertEquals(listOf(start, stopB.point), AppRules.stopViewPoints(card, StopPreview(stopB, null, null, busy = true), trip))
        // its route there: the leg into the stop (StopLeg.lineToStop) and the place
        val withStop = choice(9, listOf(start, GeoPoint(42.13, -80.11), stopB.point, end), legEnds = listOf(2, 3))
        assertEquals(listOf(start, GeoPoint(42.13, -80.11), stopB.point, stopB.point),
            AppRules.stopViewPoints(card, StopPreview(stopB, withStop, 120.0, busy = false), trip))
        // a preview of another place (an older one) is not this card's
        assertEquals(listOf(start, stopB.point), AppRules.stopViewPoints(card, StopPreview(stopA, withStop, 120.0, busy = false), trip))
        // no stop results: none (the teardown ends the view), plain results neither
        assertNull(AppRules.stopViewPoints(null, null, trip))
        assertNull(AppRules.stopViewPoints(list.copy(stop = null), null, trip))
    }

    @Test
    fun `item 51 the stop results end - the old request is dropped, the view and preview only when none took over`() {
        // AS test 9c
        assertEquals(StopEnd(1, all = true), AppRules.stopResultsEnded(1, null))
        assertEquals(StopEnd(1, all = false), AppRules.stopResultsEnded(1, 2))
        assertNull(AppRules.stopResultsEnded(null, 1))
        assertNull(AppRules.stopResultsEnded(1, 1))
        assertNull(AppRules.stopResultsEnded(null, null))
    }

    @Test
    fun `the search bar is in the camera's top insets while a place sheet is open (P8)`() {
        // a place sheet over the map: the bar's bottom (status bar 24 + 8 + bar 48), not the buttons beside it
        assertEquals(80f, AppRules.cameraTopChrome(pillShown = true, resultsShown = false, sheetShown = true, pillBlock = 146f, barBottom = 80f))
        // the results on the map: the pill's whole block, as before (S11)
        assertEquals(146f, AppRules.cameraTopChrome(pillShown = true, resultsShown = true, sheetShown = false, pillBlock = 146f, barBottom = 80f))
        // the plain map, or a trip (no pill): nothing
        assertEquals(0f, AppRules.cameraTopChrome(pillShown = true, resultsShown = false, sheetShown = false, pillBlock = 146f, barBottom = 80f))
        assertEquals(0f, AppRules.cameraTopChrome(pillShown = false, resultsShown = false, sheetShown = true, pillBlock = 146f, barBottom = 80f))
    }

    @Test
    fun `the arrival view counts the destination's window in the camera's insets, the guide camera does not (P4)`() {
        assertTrue(AppRules.guideCamera(Phase.GUIDING))
        assertTrue(AppRules.guideCamera(Phase.REROUTING))
        assertFalse(AppRules.guideCamera(Phase.ARRIVED))
        assertFalse(AppRules.guideCamera(Phase.IDLE))
        // ARRIVED: the arrival card's block (200) under the destination's window raised to 500, the credit row 40 + 8
        assertEquals(EdgeInsetsDp(top = 24f, bottom = 548f),
            AppRules.cameraInsets(EdgeInsetsDp(bottom = 200f), 24f, 48f, AppRules.guideCamera(Phase.ARRIVED), placeSheet = 500f))
    }

    @Test
    fun stepShownStaysWithItsRoute() {
        // RS T21d
        val p = GeoPoint(42.15, -80.10)
        val t = TripState(phase = Phase.CHOOSING, stops = listOf(TripStop(dest)),
            choices = listOf(choice(1, listOf(start, mid, end), listOf(2), steps = 3), choice(2, listOf(start, end), listOf(1), steps = 1)), selectedChoiceId = 1)
        assertEquals(2, AppRules.shownStep(ShownStep(1, 2, p), t))
        assertNull(AppRules.shownStep(ShownStep(1, 3, p), t))
        assertNull(AppRules.shownStep(ShownStep(1, 2, p), t.copy(selectedChoiceId = 2)))
        assertNull(AppRules.shownStep(ShownStep(1, 2, p), t.copy(phase = Phase.GUIDING)))
        assertNull(AppRules.shownStep(null, t))
    }

    @Test
    fun `P10 Undo of a removed stop puts it back where it was, only while the trip can change`() {
        // stops [A, C] after B (index 1) was removed
        val c = Place("Stop C", GeoPoint(42.18, -80.10), PlaceSource.SEARCH)
        val trip = TripState(phase = Phase.CHOOSING, stops = listOf(TripStop(stopA), TripStop(c)))
        assertEquals(NavIntent.AddStop(stopB, 1), AppRules.undoRemovedStop(stopB, 1, trip))
        for (ph in listOf(Phase.IDLE, Phase.ARRIVED, Phase.RESUME_OFFERED)) assertNull(AppRules.undoRemovedStop(stopB, 1, trip.copy(phase = ph)), "$ph")
        assertEquals(NavIntent.AddStop(stopB, 1), AppRules.undoRemovedStop(stopB, 1, trip.copy(phase = Phase.GUIDING)))
        // B is back already (a failed change during guidance was put back by the session)
        assertNull(AppRules.undoRemovedStop(stopB, 1, trip.copy(stops = listOf(TripStop(stopA), TripStop(stopB), TripStop(c)))))
        // an index past the end is clamped; one inside the reached stops goes to the first unreached place
        assertEquals(NavIntent.AddStop(stopB, 2), AppRules.undoRemovedStop(stopB, 5, trip))
        val reached = trip.copy(phase = Phase.GUIDING, stops = listOf(TripStop(stopA, reached = true), TripStop(c)))
        assertEquals(NavIntent.AddStop(stopB, 1), AppRules.undoRemovedStop(stopB, 0, reached))
        // the stops editor's note line carries the offer with its Undo
        val editor = StopsEditorUi(emptyList(), canOptimize = false, busy = false, note = "Stops reordered.")
        val drive = DriveUi(stopsEditor = editor)
        assertEquals(editor.copy(note = "Stop removed", canUndo = true), AppRules.withStopUndo(drive, AppRules.STOP_REMOVED).stopsEditor)
        assertEquals(drive, AppRules.withStopUndo(drive, null))
        assertEquals(DriveUi(), AppRules.withStopUndo(DriveUi(), AppRules.STOP_REMOVED))
        assertEquals(END_UNDO_MS, AppRules.UNDO_BAR_MS)
    }

    @Test
    fun `P31 the arrival card says Saved once the destination is saved, and only then`() {
        val zoo = Place("Made-up Zoo", GeoPoint(42.15, -80.08), PlaceSource.SEARCH)
        val sp = SavedPlace("s00000000000000aa", SavedKind.OTHER, "Zoo", zoo.point, "Made-up Zoo")
        assertFalse(AppRules.destinationSaved(zoo, emptyList(), null, null))
        assertTrue(AppRules.destinationSaved(zoo, listOf(sp), null, sp))
        // a dropped pin is never found by name: saved at arrival, while that saved place is still there
        val pin = Place("Dropped pin", GeoPoint(42.16, -80.07), PlaceSource.MAP_PIN)
        val pinSaved = sp.copy(id = "s00000000000000bb", point = pin.point)
        assertTrue(AppRules.destinationSaved(pin, listOf(pinSaved), pinSaved.id, null))
        assertFalse(AppRules.destinationSaved(pin, emptyList(), pinSaved.id, null))
        assertFalse(AppRules.destinationSaved(null, listOf(sp), sp.id, sp))
        // the card's link or the place's own window: a save at the destination's point while ARRIVED
        assertTrue(AppRules.savedAtArrival(Phase.ARRIVED, pin, pinSaved))
        assertFalse(AppRules.savedAtArrival(Phase.ARRIVED, pin, pinSaved.copy(point = GeoPoint(42.169, -80.07))))   // 1 km away
        assertFalse(AppRules.savedAtArrival(Phase.GUIDING, pin, pinSaved))
        assertFalse(AppRules.savedAtArrival(Phase.ARRIVED, null, pinSaved))
        // the card gets the flag; a guiding DriveUi is left as it is
        val bar = TripBarUi("Arrived", "", "", null, simulated = false)
        val arrived = DriveUi(guidance = GuidanceUi(null, GuidanceStatusUi.Arrived("Made-up Zoo", "On the right"), bar, showRecenter = false))
        assertEquals(GuidanceStatusUi.Arrived("Made-up Zoo", "On the right", saved = true), AppRules.withDestinationSaved(arrived, true).guidance!!.status)
        assertEquals(arrived, AppRules.withDestinationSaved(arrived, false))
        val guiding = DriveUi(guidance = GuidanceUi(null, GuidanceStatusUi.OnRoute, bar, showRecenter = false))
        assertEquals(guiding, AppRules.withDestinationSaved(guiding, true))
        assertEquals(DriveUi(), AppRules.withDestinationSaved(DriveUi(), true))
    }

    @Test
    fun `P28 Back while a trip is driven or offered asks for a second press within 2 s`() {
        assertEquals("Press Back again to leave Beamline. Guidance keeps running.", AppRules.backHint(Phase.GUIDING))
        assertEquals(AppRules.backHint(Phase.GUIDING), AppRules.backHint(Phase.REROUTING))
        assertEquals("Press Back again to leave Beamline.", AppRules.backHint(Phase.ARRIVED))
        assertEquals(AppRules.backHint(Phase.ARRIVED), AppRules.backHint(Phase.RESUME_OFFERED))
        for (ph in listOf(Phase.IDLE, Phase.PLANNING, Phase.CHOOSING)) assertNull(AppRules.backHint(ph), "$ph")
        assertFalse(AppRules.secondBack(null, 5_000))
        assertTrue(AppRules.secondBack(5_000, 6_900))
        assertFalse(AppRules.secondBack(5_000, 7_100))
        assertFalse(AppRules.secondBack(5_000, 4_000))   // a clock that went back does not count
    }

    @Test
    fun `P28 a reroute between the two presses keeps the first Back armed`() {
        // both phases say the same hint: the second press within 2 s still leaves
        assertTrue(AppRules.backArmKept(Phase.GUIDING, Phase.REROUTING))
        assertTrue(AppRules.backArmKept(Phase.REROUTING, Phase.GUIDING))
        assertTrue(AppRules.backArmKept(Phase.GUIDING, Phase.GUIDING))   // the screen built again (a rotation)
        assertTrue(AppRules.backArmKept(Phase.ARRIVED, Phase.RESUME_OFFERED))
        // a change of hint, or a phase that does not hold Back, disarms it
        assertFalse(AppRules.backArmKept(Phase.GUIDING, Phase.ARRIVED))
        assertFalse(AppRules.backArmKept(Phase.RESUME_OFFERED, Phase.GUIDING))
        for (ph in listOf(Phase.IDLE, Phase.PLANNING, Phase.CHOOSING)) assertFalse(AppRules.backArmKept(Phase.GUIDING, ph), "$ph")
        assertFalse(AppRules.backArmKept(null, Phase.GUIDING))
    }

    // ------------------------------------------------------------------ 0.1.40 (track I part 2)

    @Test
    fun `P7 on its side the place window goes into the start panel and my-location stays at the bottom`() {
        // LS T1
        assertTrue(isSideLayout(891.dp, 411.dp)); assertFalse(isSideLayout(500.dp, 360.dp)); assertFalse(isSideLayout(411.dp, 891.dp))
        assertEquals(400.dp, sidePanelWidth(891.dp)); assertEquals(320.dp, sidePanelWidth(600.dp))
        assertEquals(BottomChrome(startDp = 400f, bottomDp = 0f), AppRules.bottomChrome(sheetDp = 286f, panelDp = 400f, overlay = EdgeInsetsDp(), pickDp = null))
        // upright: K1 as today
        assertEquals(BottomChrome(0f, 286f, onSheet = true), AppRules.bottomChrome(286f, null, EdgeInsetsDp(), null))
        // a route preview on its side with a place window over it
        assertEquals(BottomChrome(400f, 0f), AppRules.bottomChrome(286f, 400f, EdgeInsetsDp(start = 400f), null))
        // a pick
        assertEquals(BottomChrome(0f, 180f), AppRules.bottomChrome(null, 400f, EdgeInsetsDp(), 180f))
        // camera: the window in the panel keeps the credit row's room at the bottom
        assertEquals(EdgeInsetsDp(top = 24f, bottom = 72f, start = 400f, end = 48f),
            AppRules.cameraInsets(EdgeInsetsDp(), statusBarTop = 24f, chromeBottom = 72f, driving = false, placeSheet = 286f, sheetPanel = 400f, safeRight = 48f))
    }

    @Test
    fun `P1 fitted things keep clear of a navigation bar or a cutout at the sides`() {
        // LS T2
        assertEquals(EdgeInsetsDp(top = 24f, bottom = 72f, start = 32f, end = 48f), AppRules.cameraInsets(EdgeInsetsDp(), 24f, 72f, false, safeLeft = 32f, safeRight = 48f))
        // the overlay's panel already holds the start
        assertEquals(EdgeInsetsDp(top = 24f, start = 400f, end = 48f), AppRules.cameraInsets(EdgeInsetsDp(start = 400f), 24f, 72f, false, safeLeft = 32f, safeRight = 48f))
        // upright guiding unchanged
        assertEquals(EdgeInsetsDp(top = 160f, bottom = 96f), AppRules.cameraInsets(EdgeInsetsDp(top = 160f, bottom = 96f), 24f, 72f, true))
        // item 51 on its side: the stop window is in the panel (stopSheet 0), the side's inset still counts
        assertEquals(EdgeInsetsDp(top = 120f, start = 400f, end = 48f), AppRules.cameraInsets(EdgeInsetsDp(top = 120f, start = 400f), 24f, 40f, true, safeRight = 48f))
    }

    @Test
    fun `P7 my-location is left out while a raised window would bring it to Map style`() {
        // LS T3 (the end column ends at 134 dp: 24 + 8 + 46 + 10 + 46)
        assertTrue(AppRules.locationFits(BottomChrome(0f, 286f, onSheet = true), windowDp = 891f, topChromeDp = 134f))
        assertFalse(AppRules.locationFits(BottomChrome(0f, 280f, onSheet = true), 400f, 134f))     // split screen
        assertFalse(AppRules.locationFits(BottomChrome(0f, 448f, onSheet = true), 640f, 134f))     // a 360 x 640 dp phone, the window at its cap
        assertTrue(AppRules.locationFits(BottomChrome(0f, 560f), 640f, 24f))                       // the route preview at full height: as today
        assertTrue(AppRules.locationFits(BottomChrome(400f, 0f), 411f, 134f))                      // on its side, beside the panel
        assertTrue(AppRules.locationFits(BottomChrome(0f, 286f, onSheet = true), 0f, 0f))          // not measured yet
    }

    @Test
    fun `P7 a window keeps under the pill's column and the banner`() {
        assertNull(AppRules.sheetRoomDp(0f, 96f, 0f, 184f))                                       // not measured
        assertEquals(307f, AppRules.sheetRoomDp(411f, 96f, 0f, 184f)!!)                           // on its side, the pill without chips
        assertEquals(184f, AppRules.sheetRoomDp(360f, 188f, 0f, 184f)!!)                          // two chips in a 360 dp tall window: the lowest height wins
        assertEquals(239.2f, AppRules.sheetRoomDp(360f, 188f, 0f, 184f * 1.3f)!!, 0.01f)          // font 1.3
        assertEquals(173f, AppRules.sheetRoomDp(411f, 24f, 230f, 184f)!!)                         // guiding on its side: the banner wins
        assertEquals(709f, AppRules.sheetRoomDp(891f, 24f, 174f, 184f)!!)                         // upright guiding: far above every cap
    }

    @Test
    fun `P17 a point's window keeps its save across its street line (F42)`() {
        val pt = GeoPoint(42.12919, -80.08506)   // downtown Erie, a made-up saved spot
        val pin = Place("Dropped pin", pt, PlaceSource.MAP_PIN, subtitle = "42.12919, -80.08506")
        val sp = SavedPlace("s0123456789abcdef", SavedKind.OTHER, "Lunch spot", pt, "42.12919, -80.08506")
        val withStreet = pin.copy(subtitle = "Near East 5th Street, Erie")
        assertEquals(withStreet.copy(savedId = sp.id), AppRules.sheetAfterSave(withStreet, pin, sp))
        assertTrue(AppRules.sameSheetPlace(pin, withStreet.copy(savedId = sp.id)))
        assertFalse(AppRules.sameSheetPlace(pin, pin.copy(name = "Point on the map")))
        assertFalse(AppRules.sameSheetPlace(pin, pin.copy(point = GeoPoint(42.13, -80.085))))
    }

    @Test
    fun `a point without its street is looked up`() {
        val p = GeoPoint(42.13646, -80.08718)
        assertTrue(AppRules.lacksStreet(Place("Dropped pin", p, PlaceSource.MAP_PIN, subtitle = "42.13646, -80.08718")))
        assertTrue(AppRules.lacksStreet(Place("Dropped pin", p, PlaceSource.MAP_PIN)))
        assertFalse(AppRules.lacksStreet(Place("Dropped pin", p, PlaceSource.MAP_PIN, subtitle = "Near East 5th Street, Erie")))
        assertFalse(AppRules.lacksStreet(Place("Made-up Cafe", p, PlaceSource.MAP_POI)))
        assertFalse(AppRules.lacksStreet(Place("Dropped pin", p, PlaceSource.RESUMED, subtitle = "42.13646, -80.08718")))
    }

    @Test
    fun `P58 the window after Undo of a removal`() {
        val home = SavedPlace("s00000000000000h1", SavedKind.HOME, "Home", GeoPoint(42.11, -80.09), "Made-up Street, Erie")
        val pt = GeoPoint(42.14, -80.07)
        val pinSaved = SavedPlace("s00000000000000p1", SavedKind.OTHER, "Gate", pt, "Near East 5th Street, Erie")
        assertEquals(home.toPlace(), AppRules.windowAfterUndo(home.toPlace(), null, true, home))
        assertNull(AppRules.windowAfterUndo(home.toPlace(), Place("Other", pt, PlaceSource.SEARCH), true, home))   // another window opened
        assertNull(AppRules.windowAfterUndo(home.toPlace(), null, false, home))                                    // another screen opened
        val w = Place("Dropped pin", pt, PlaceSource.MAP_PIN, "Near East 5th Street, Erie", savedId = pinSaved.id)
        assertEquals(w.copy(savedId = pinSaved.id), AppRules.windowAfterUndo(w, w.copy(savedId = null), true, pinSaved))
        assertNull(AppRules.windowAfterUndo(w, Place("Other", GeoPoint(42.2, -80.2), PlaceSource.MAP_PIN), true, pinSaved))
        assertNull(AppRules.windowAfterUndo(null, null, true, pinSaved))
        assertEquals("Home could not be put back.", AppRules.notRestoredText("Home"))
        assertEquals("Home removed", AppRules.removedText("Home"))
    }

    @Test
    fun s1bOnlyAfterTheUsersOwnMove() {
        // SE T17 (P6 d)
        val me = GeoPoint(42.0971, -80.07578)
        val gpsCtx = SearchContext(me, NearSource.GPS, 10.0)
        val corryBox = Bounds(-79.70, 41.88, -79.58, 41.96)
        val corry = GeoPoint(41.92, -79.64)
        assertEquals(gpsCtx, AppRules.searchContextFor(SearchMode.GO_TO, gpsCtx, corryBox, corry, movedByUser = false))   // the app moved the camera
        assertEquals(SearchContext(corry, NearSource.MAP_CENTER, distanceFrom = me), AppRules.searchContextFor(SearchMode.GO_TO, gpsCtx, corryBox, corry, movedByUser = true))
        assertEquals(gpsCtx, AppRules.searchContextFor(SearchMode.GO_TO, gpsCtx, Bounds(-80.2, 42.0, -80.0, 42.2), corry, movedByUser = true))   // a window holding me
        assertEquals(gpsCtx, AppRules.searchContextFor(SearchMode.ADD_STOP, gpsCtx, corryBox, corry, movedByUser = true))
        assertEquals(gpsCtx, AppRules.searchContextFor(SearchMode.GO_TO, gpsCtx, null, corry, movedByUser = true))
        val mapCtx = SearchContext(corry, NearSource.MAP_CENTER)
        assertEquals(mapCtx, AppRules.searchContextFor(SearchMode.GO_TO, mapCtx, corryBox, corry, movedByUser = true))
    }

    @Test
    fun `after a rotation both bars follow the map, the status bar left to the drive overlay while it holds it (P39)`() {
        assertEquals(AppRules.BarIcons(navForLightBackground = true, statusForLightBackground = true), AppRules.barIconsAfterRotation(night = false, revealDone = true, driveHoldsStatusBar = false))
        assertEquals(AppRules.BarIcons(false, false), AppRules.barIconsAfterRotation(night = true, revealDone = true, driveHoldsStatusBar = false))
        assertEquals(AppRules.BarIcons(false, false), AppRules.barIconsAfterRotation(night = false, revealDone = false, driveHoldsStatusBar = false))
        assertEquals(AppRules.BarIcons(true, null), AppRules.barIconsAfterRotation(night = false, revealDone = true, driveHoldsStatusBar = true))
        assertEquals(AppRules.BarIcons(false, null), AppRules.barIconsAfterRotation(night = true, revealDone = true, driveHoldsStatusBar = true))
    }

    @Test
    fun `the drive overlay holds the status bar only with its banner or re-route card at the top (P39)`() {
        val bar = TripBarUi("Arrive 3:42 PM", "12 min", "4.1 mi", null, simulated = false)
        val banner = BannerUi(ManeuverKind.LEFT, null, "0.3 mi", "Turn left", null, null, null, null)
        fun g(banner: BannerUi?, status: GuidanceStatusUi) = GuidanceUi(banner, status, bar, showRecenter = false)
        assertFalse(AppRules.driveDarkTop(null))                                         // browsing, planning
        assertTrue(AppRules.driveDarkTop(g(banner, GuidanceStatusUi.OnRoute)))
        assertFalse(AppRules.driveDarkTop(g(null, GuidanceStatusUi.WaitingForGps)))      // no banner before the first fix
        assertFalse(AppRules.driveDarkTop(g(null, GuidanceStatusUi.GpsLost)))
        assertTrue(AppRules.driveDarkTop(g(null, GuidanceStatusUi.Rerouting)))
        assertTrue(AppRules.driveDarkTop(g(null, GuidanceStatusUi.RerouteFailed("No route found", canRetry = true))))
        assertFalse(AppRules.driveDarkTop(g(banner, GuidanceStatusUi.Arrived("Test Diner", null))))
    }

    // ------------------------------------------------------------------ 0.1.41 (track i)

    @Test
    fun `54b the Add stop panel - half the room above the keyboard upright, the start panel on its side, over the banner only without room`() {
        // AF T5 (min 140 unless said)
        assertEquals(AddStopPanelPlace(311.5f, 279.5f), AppRules.addStopPanelPlace(891f, 300f, 0f, 24f, side = false, 140f))      // planning, keyboard 300
        assertEquals(AddStopPanelPlace(359.5f, 231.5f), AppRules.addStopPanelPlace(891f, 300f, 120f, 24f, false, 140f))            // guiding, banner to 120
        assertEquals(AddStopPanelPlace(461.5f, 429.5f), AppRules.addStopPanelPlace(891f, 0f, 0f, 24f, false, 140f))                // keyboard down
        assertEquals(AddStopPanelPlace(128f, 232f), AppRules.addStopPanelPlace(640f, 280f, 120f, 24f, false, 140f))                // a 640 dp phone: no strip, the whole room
        assertEquals(AddStopPanelPlace(359.5f, 231.5f), AppRules.addStopPanelPlace(891f, 300f, 120f, 24f, false, 182f))            // font 1.3: half is still over the least height
        // AF-6: where the least height matters (a taller top stack): at 1.3x the field and one row stay whole
        assertEquals(AddStopPanelPlace(409f, 182f), AppRules.addStopPanelPlace(891f, 300f, 233f, 24f, false, 182f))
        assertEquals(AddStopPanelPlace(416f, 175f), AppRules.addStopPanelPlace(891f, 300f, 233f, 24f, false, 140f))
        assertEquals(AddStopPanelPlace(24f, 176f), AppRules.addStopPanelPlace(450f, 250f, 120f, 24f, false, 140f))                 // split screen: over the banner
        assertEquals(AddStopPanelPlace(32f, 179f), AppRules.addStopPanelPlace(411f, 200f, 0f, 24f, side = true, 140f))             // on its side, planning
        assertEquals(AddStopPanelPlace(24f, 187f), AppRules.addStopPanelPlace(411f, 200f, 120f, 24f, true, 140f))                  // on its side guiding: over the banner
        assertEquals(AddStopPanelPlace(128f, 283f), AppRules.addStopPanelPlace(411f, 0f, 120f, 24f, true, 140f))                   // on its side guiding, keyboard down
        assertEquals(AddStopPanelPlace(0f, 0f), AppRules.addStopPanelPlace(0f, 300f, 0f, 24f, false, 140f))                        // not measured
        assertEquals(16f, AppRules.ADD_STOP_VIEW_PADDING_DP)
    }

    @Test
    fun `54b while typing the camera looks along the route ahead, the stop results win`() {
        // AF T6: a line north from (42.0, -80.0) every 0.01 degree to 42.9 (about 100 km)
        val long = (0..90).map { GeoPoint(42.0 + it * 0.01, -80.0) }
        val ahead = AppRules.addStopView(long, 1.0, 60.0)!!
        assertEquals(61, ahead.size)
        assertEquals(long[0], ahead.first())
        assertEquals(42.5396, ahead.last().lat, 0.001)
        // a line to 42.08 (about 8.9 km): a point every km and the line's end
        val short = (0..8).map { GeoPoint(42.0 + it * 0.01, -80.0) }
        val near = AppRules.addStopView(short, 1.0, 60.0)!!
        assertEquals(10, near.size)
        assertEquals(short.last(), near.last())
        assertNull(AppRules.addStopView(emptyList(), 1.0, 60.0))
        // with the CHOOSING trip of the item 51 test
        val trip = TripState(phase = Phase.CHOOSING, stops = listOf(TripStop(dest)), choices = listOf(choice(1, listOf(start, mid, end), legEnds = listOf(2))), selectedChoiceId = 1)
        val rows = listOf(ResultRowUi("r0", "fuel", "Stop A", null, null, null), ResultRowUi("r1", "fuel", "Stop B", null, null, null))
        val list = ResultsUi("gas", rows, listOf(stopA, stopB), stop = StopResultsUi(7, working = false))
        assertEquals(near, AppRules.stopViewPoints(null, null, trip, typing = near))
        assertEquals(listOf(stopA.point, stopB.point, start), AppRules.stopViewPoints(list, null, trip, typing = near))
        // the view and its padding: the typing view pads by 16 dp, the results by the preview's own (null)
        assertEquals(StopView(near, AppRules.ADD_STOP_VIEW_PADDING_DP), AppRules.stopView(null, null, trip, typing = near))
        assertEquals(StopView(listOf(stopA.point, stopB.point, start), null), AppRules.stopView(list, null, trip, typing = near))
        assertNull(AppRules.stopView(null, null, trip, typing = null))
        assertNull(AppRules.stopView(null, null, trip, typing = emptyList()))
        assertNull(AppRules.stopView(list.copy(stop = null), null, trip, typing = null))
    }

    @Test
    fun `54b the panel counts in the camera's insets, the keyboard on its side`() {
        // AF T7: on its side the keyboard covers the map's lower part
        assertEquals(EdgeInsetsDp(top = 24f, bottom = 200f, start = 400f), AppRules.cameraInsets(EdgeInsetsDp(start = 400f), 24f, 40f, driving = true, keyboard = 200f))
        // upright guiding: stopSheet = the panel's reach (its height and the keyboard under it)
        assertEquals(EdgeInsetsDp(top = 120f, bottom = 571.5f), AppRules.cameraInsets(EdgeInsetsDp(top = 120f), 24f, 40f, driving = true, stopSheet = 531.5f))
        // AF-2: with the credit row left out, the panel's reach alone
        assertEquals(EdgeInsetsDp(top = 120f, bottom = 531.5f), AppRules.cameraInsets(EdgeInsetsDp(top = 120f), 24f, 0f, driving = true, stopSheet = 531.5f))
    }

    @Test
    fun `54b only the Add stop search shows over the map`() {
        // AF T8
        assertTrue(AppRules.addStopOverMap(Layer.Search(3, SearchMode.ADD_STOP, forSave = false), grid = false))
        assertFalse(AppRules.addStopOverMap(Layer.Search(3, SearchMode.ADD_STOP, forSave = false), grid = true))
        assertFalse(AppRules.addStopOverMap(Layer.Search(3, SearchMode.GO_TO, forSave = false), grid = false))
        assertFalse(AppRules.addStopOverMap(Layer.Search(3, SearchMode.SET_START, forSave = false), grid = false))
        assertFalse(AppRules.addStopOverMap(Layer.Search(3, SearchMode.SAVE, forSave = true), grid = false))
        assertFalse(AppRules.addStopOverMap(Layer.SavedList, grid = false))
        assertFalse(AppRules.addStopOverMap(null, grid = false))
    }

    @Test
    fun `54b the stop view goes from typing to the results and back without ending`() {
        // AF-6 and AF-3: what the one collector does with each view, as it folds them
        val typing = StopView(listOf(GeoPoint(42.10, -80.10), GeoPoint(42.11, -80.10)), AppRules.ADD_STOP_VIEW_PADDING_DP)
        val results = StopView(listOf(stopA.point, stopB.point, start), null)
        val step = GeoPoint(42.13, -80.10)
        fun run(views: List<StopView?>, step: GeoPoint?): List<StopViewCall> {
            var viewing = false
            return views.map { v ->
                AppRules.stopViewCall(viewing, v, step).also { c ->
                    when (c) {
                        is StopViewCall.Show -> viewing = true
                        is StopViewCall.End -> viewing = false
                        StopViewCall.None -> Unit
                    }
                }
            }
        }
        // typing -> results (Enter) -> typing again (Back from the list): three views, never an end between them
        assertEquals(listOf(StopViewCall.Show(typing), StopViewCall.Show(results), StopViewCall.Show(typing)), run(listOf(typing, results, typing), null))
        // the panel closed: the end, with a step shown in a route preview put back on the camera (AF-3)
        assertEquals(listOf(StopViewCall.Show(typing), StopViewCall.End(reshow = step), StopViewCall.None), run(listOf(typing, null, null), step))
        assertEquals(listOf(StopViewCall.Show(results), StopViewCall.End(reshow = null)), run(listOf(results, null), null))
        // nothing shown before: nothing to end
        assertEquals(listOf(StopViewCall.None), run(listOf(null), step))
    }

    @Test
    fun `54b an arrival or the end closes the Add stop search`() {
        val adding = listOf(Layer.Search(1, SearchMode.ADD_STOP, forSave = false))
        for (ph in listOf(Phase.ARRIVED, Phase.IDLE, Phase.RESUME_OFFERED)) assertTrue(AppRules.addStopEnds(ph, adding), "$ph")
        for (ph in listOf(Phase.PLANNING, Phase.CHOOSING, Phase.GUIDING, Phase.REROUTING)) assertFalse(AppRules.addStopEnds(ph, adding), "$ph")
        // its Saved places over it too; another search is not closed
        assertTrue(AppRules.addStopEnds(Phase.ARRIVED, adding + Layer.SavedList))
        assertFalse(AppRules.addStopEnds(Phase.ARRIVED, listOf(Layer.Search(1, SearchMode.GO_TO, forSave = false))))
        assertFalse(AppRules.addStopEnds(Phase.IDLE, emptyList()))
    }

    @Test
    fun `54b Saved places and View all open over the panel at once`() {
        // AF-4: the screen comes in at once (no slide over a bare map), and only over the panel
        val panel = Layer.Search(1, SearchMode.ADD_STOP, forSave = false)
        assertFalse(AppRules.opensOverPanel(listOf(panel), grid = false))               // the panel itself: no screen
        assertTrue(AppRules.opensOverPanel(listOf(panel), grid = true))                 // its "View all" grid
        assertTrue(AppRules.opensOverPanel(listOf(panel, Layer.SavedList), grid = false))
        val goTo = Layer.Search(2, SearchMode.GO_TO, forSave = false)
        assertFalse(AppRules.opensOverPanel(listOf(goTo), grid = true))
        assertFalse(AppRules.opensOverPanel(listOf(goTo, Layer.SavedList), grid = false))
        assertFalse(AppRules.opensOverPanel(listOf(Layer.Settings), grid = false))
        assertFalse(AppRules.opensOverPanel(emptyList(), grid = false))
    }

    @Test
    fun `53g the arrival question counts as seen only on screen`() {
        // AP-1: resumed, no map pick, no screen, no stop results, no Add stop panel, no place window over the card
        fun shown(resumed: Boolean = true, picking: Boolean = false, layerOnTop: Boolean = false, stopShown: Boolean = false,
                  addStopShown: Boolean = false, sheetShown: Boolean = false) =
            AppRules.arrivalDisplayed(resumed, picking, layerOnTop, stopShown, addStopShown, sheetShown)
        assertTrue(shown())
        assertFalse(shown(resumed = false))
        assertFalse(shown(picking = true))
        assertFalse(shown(layerOnTop = true))
        assertFalse(shown(stopShown = true))
        assertFalse(shown(addStopShown = true))
        // review of 0.1.41: the card raised opens the destination's window, drawn over the card
        assertFalse(shown(sheetShown = true))
    }

    @Test
    fun `54b on its side with the keyboard up the credit row stays above the keyboard`() {
        // review of 0.1.41: 891 x 411 dp, the Add stop panel in the start panel (its reach 387 dp, the keyboard
        // 200 dp of it), the stop overlay's top-only insets: the row beside the panel, on the keyboard (it was at 0, under it)
        assertEquals(BottomChrome(startDp = 400f, bottomDp = 200f), AppRules.bottomChrome(sheetDp = 387f, panelDp = 400f,
            overlay = EdgeInsetsDp(top = 24f), pickDp = null, keyboardDp = 200f))
        // guiding on its side: the overlay's panel start as before
        assertEquals(BottomChrome(400f, 200f), AppRules.bottomChrome(387f, 400f, EdgeInsetsDp(start = 400f), null, keyboardDp = 200f))
        // the keyboard down: at the bottom as before
        assertEquals(BottomChrome(400f, 0f), AppRules.bottomChrome(387f, 400f, EdgeInsetsDp(top = 24f), null, keyboardDp = 0f))
        // upright the panel's reach already holds the keyboard: unchanged
        assertEquals(BottomChrome(0f, 620f, onSheet = true), AppRules.bottomChrome(620f, null, EdgeInsetsDp(top = 120f), null, keyboardDp = 300f))
    }

    @Test
    fun `54b a bottom bar sits above the Add stop panel and the keyboard`() {
        // review of 0.1.41: without the panel, 88 dp over the drive overlay's blocks as before
        assertEquals(388f, AppRules.barBottomDp(overlayBottomDp = 300f, addStopReachDp = null, navBarDp = 48f))
        assertEquals(88f, AppRules.barBottomDp(0f, null, 48f))
        // upright, the stops editor's "Stop removed" Undo while the panel (320 dp) and the keyboard (300 dp, the
        // navigation bar's 48 in it) show and the stop overlay reports top-only insets: above the panel, not behind the keyboard
        assertEquals(660f, AppRules.barBottomDp(overlayBottomDp = 0f, addStopReachDp = 620f, navBarDp = 48f))
        // the keyboard down: above the panel's top, not over its lower rows
        assertEquals(360f, AppRules.barBottomDp(0f, 320f, 48f))
        // on its side: above the keyboard beside the panel
        assertEquals(240f, AppRules.barBottomDp(0f, 200f, 48f))
        // on its side with the keyboard down: as without the panel
        assertEquals(88f, AppRules.barBottomDp(0f, 0f, 48f))
    }

    @Test
    fun `O3 an edit carries the tapped label's icon only for the window that tap opened`() {
        // review of 0.1.41: a Sheetz label tapped while planning, then the arrival at the Subway (a map label
        // earlier, now the destination): its Add phone edit carries the Subway's icon, not the fuel pump
        val sheetz = Place("Sheetz", GeoPoint(42.1201, -80.0802), PlaceSource.MAP_POI)
        val subway = Place("Subway", GeoPoint(42.1290, -80.0851), PlaceSource.MAP_POI)
        assertEquals("fast_food", AppRules.sheetIcon(subway, "fast_food", tapped = sheetz, tappedIcon = "fuel"))
        // the tapped window itself: the label's own icon (its line or saved id may differ)
        assertEquals("fuel", AppRules.sheetIcon(sheetz, "shop", tapped = sheetz, tappedIcon = "fuel"))
        assertEquals("fuel", AppRules.sheetIcon(sheetz.copy(savedId = "s1"), "shop", tapped = sheetz, tappedIcon = "fuel"))
        // no tap yet, or a window that is not a map label
        assertEquals("fast_food", AppRules.sheetIcon(subway, "fast_food", tapped = null, tappedIcon = null))
        val found = Place("Sheetz", sheetz.point, PlaceSource.SEARCH, searchId = 7L)
        assertEquals("shop", AppRules.sheetIcon(found, "shop", tapped = sheetz, tappedIcon = "fuel"))
    }

    @Test
    fun arrivalQuestionGoesIntoTheCardOnly() {
        // AP 10: the app's one question goes into the arrival card; any other drive UI is left as it is
        val q = ArrivalQuestionUi("Hours still right?", listOf("Mon–Fri 8 AM–6 PM"), "Yes", "Edit hours", "Not now")
        val bar = TripBarUi("Arrived", "", "", null, simulated = false)
        val arrived = DriveUi(guidance = GuidanceUi(null, GuidanceStatusUi.Arrived("Made-up Cafe", "On the right"), bar, showRecenter = false))
        val asked = AppRules.withArrivalQuestion(arrived, q)
        assertEquals(GuidanceStatusUi.Arrived("Made-up Cafe", "On the right", question = q), asked.guidance!!.status)
        assertTrue(asked === AppRules.withArrivalQuestion(asked, q))         // the same question: the same instance
        assertEquals(arrived, AppRules.withArrivalQuestion(asked, null))     // gone again
        assertTrue(arrived === AppRules.withArrivalQuestion(arrived, null))
        val guiding = DriveUi(guidance = GuidanceUi(null, GuidanceStatusUi.OnRoute, bar, showRecenter = false))
        assertTrue(guiding === AppRules.withArrivalQuestion(guiding, q))
        assertTrue(DriveUi().let { it === AppRules.withArrivalQuestion(it, q) })   // planning, browsing
    }
}
