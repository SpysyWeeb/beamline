package io.github.spysyweeb.beamline.app

import io.github.spysyweeb.beamline.api.map.EdgeInsetsDp
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.model.SavedKind
import io.github.spysyweeb.beamline.api.model.SavedPlace
import io.github.spysyweeb.beamline.app.ui.AppRules
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
        assertNull(AppRules.endBar(tripEnded = null, continuing = true, phase = Phase.RESUME_OFFERED))
        assertNull(AppRules.endBar(tripEnded = 1L, continuing = false, phase = Phase.CHOOSING))
        assertNull(AppRules.endBar(tripEnded = null, continuing = false, phase = Phase.IDLE))
    }
}
