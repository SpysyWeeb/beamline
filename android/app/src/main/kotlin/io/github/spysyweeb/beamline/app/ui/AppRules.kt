package io.github.spysyweeb.beamline.app.ui

import io.github.spysyweeb.beamline.api.map.EdgeInsetsDp
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.SavedPlace
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.feature.about.FIRST_RUN_NOTICE_VERSION
import io.github.spysyweeb.beamline.settings.AppState
import kotlin.math.abs
import kotlin.math.max

/** Small decisions of the app shell, kept free of Android so the JVM tests hold them. */
object AppRules {
    /**
     * SP19 (next run, part 1): after a save from the open sheet's own SAVE, the sheet's place with
     * the new saved id, so a point (a dropped pin, which SavedPlaces.same never matches) shows
     * "Saved as {name}" and Edit; null when the save was not this sheet's (no sheet, another sheet,
     * a place already carrying a saved id, or a saved point more than about 2 m from the sheet's).
     */
    fun sheetAfterSave(sheet: Place?, saveFrom: Place?, saved: SavedPlace): Place? {
        if (sheet == null || saveFrom == null || sheet != saveFrom || sheet.savedId != null) return null
        if (abs(saved.point.lat - sheet.point.lat) > 2e-5 || abs(saved.point.lon - sheet.point.lon) > 2e-5) return null
        return sheet.copy(savedId = saved.id)
    }


    /**
     * Item 3 and rule N4: nothing that can reach the network runs before the driver notice is
     * accepted. The States screen fetches the list of states when it opens, so it is not opened
     * before (the activity is exported: any app can send it the OPEN_STATES intent).
     */
    fun noticeAccepted(state: AppState): Boolean = state.firstRunNoticeAccepted >= FIRST_RUN_NOTICE_VERSION

    /**
     * The space over the map the camera director pads by (rule K2's "overlay insets"): the drive
     * overlay's blocks, and the app's own chrome. At the top the status bar's band (the overlay's top
     * block includes it only when it has a banner, so a route preview put the destination pin's head
     * under it). At the bottom, while not driving and not in the side layout, the place sheet when it
     * is higher than the overlay's block ([placeSheet], so a picked place shows above it, K1) and the
     * credit line with the my-location button above it ([chromeBottom]); while driving they are left
     * out, so the guide camera's arrow stays where the drive test checked it. [topChrome]: the search
     * pill's block (status bar included) while the results are on the map (S11), so the fit keeps
     * the pins clear of it.
     */
    fun cameraInsets(overlay: EdgeInsetsDp, statusBarTop: Float, chromeBottom: Float, driving: Boolean, placeSheet: Float = 0f, topChrome: Float = 0f): EdgeInsetsDp {
        val side = overlay.start > 0f
        return overlay.copy(
            top = max(max(overlay.top, statusBarTop), topChrome),
            bottom = if (driving || side) overlay.bottom else max(overlay.bottom, placeSheet) + chromeBottom,
        )
    }

    /**
     * The map's credit (5.4 item 4, docs/next-round.md item 8; OpenStreetMap's attribution guidelines
     * let it fade after five seconds or at the first touch while the licence stays findable): the
     * short line shows for [CREDIT_SHOWN_MS] after the map appears, after the app comes back and
     * after each change between browsing, a route preview, guiding and ARRIVED, until the first touch
     * of the map; during the whole route preview; while guiding only those first seconds.
     */
    fun creditShown(phase: Phase, nowMs: Long, untilMs: Long): Boolean = when (phase) {
        Phase.PLANNING, Phase.CHOOSING -> true
        else -> nowMs < untilMs
    }

    /** After the short line fades the info mark stays, except while driving (the space belongs to the map). */
    fun creditMark(driving: Boolean, creditLine: String): Boolean = !driving && creditLine.isNotBlank()

    /** Only OpenStreetMap asks for a credit on the map itself: the short line when its data is drawn, else none. */
    fun shortCredit(creditLine: String): String? = if (creditLine.contains("OpenStreetMap")) SHORT_CREDIT else null

    const val SHORT_CREDIT = "© OpenStreetMap"
    const val CREDIT_SHOWN_MS = 5_000L

    /** The licence line of the full list (the map data dialog and About's "Map data"). */
    const val OSM_LICENCE = "Map data © OpenStreetMap contributors, available under the Open Database License (ODbL 1.0): openstreetmap.org/copyright"

    /**
     * The credit line's end padding while driving: clear of D's map buttons at the bottom end, and
     * of the wider Recenter pill when it shows (after a pan or in the overview), which otherwise
     * covered the end of the credit's first two lines.
     */
    fun creditEndPaddingDp(driving: Boolean, recenterShown: Boolean, fontScale: Float): Float = when {
        !driving -> 8f
        recenterShown -> 84f + 80f * fontScale
        else -> 72f
    }

    /**
     * Rule R4's bar at the bottom: "Trip ended" with Undo while [tripEnded] (the End's number) is set
     * and the session is IDLE; after Undo, "Continuing the trip..." while the session is still IDLE
     * planning (up to the 15 s wait for a fix), so the tap is seen to have worked. Neither shows once
     * the phase has left IDLE (guidance again, the resume card, a new trip).
     */
    fun endBar(tripEnded: Long?, continuing: Boolean, phase: Phase, endedText: String = TRIP_ENDED): String? = when {
        phase != Phase.IDLE -> null
        continuing -> CONTINUING_TRIP
        tripEnded != null -> endedText
        else -> null
    }
}
