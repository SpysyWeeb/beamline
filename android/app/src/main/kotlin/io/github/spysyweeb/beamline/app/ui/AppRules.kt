package io.github.spysyweeb.beamline.app.ui

import io.github.spysyweeb.beamline.api.map.EdgeInsetsDp
import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.model.SavedPlace
import io.github.spysyweeb.beamline.api.nav.END_UNDO_MS
import io.github.spysyweeb.beamline.api.nav.NavIntent
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.StopPreview
import io.github.spysyweeb.beamline.api.nav.TripState
import io.github.spysyweeb.beamline.feature.about.FIRST_RUN_NOTICE_VERSION
import io.github.spysyweeb.beamline.feature.drive.DriveUi
import io.github.spysyweeb.beamline.feature.drive.GuidanceStatusUi
import io.github.spysyweeb.beamline.feature.drive.GuidanceUi
import io.github.spysyweeb.beamline.feature.search.NearSource
import io.github.spysyweeb.beamline.feature.search.ResultsUi
import io.github.spysyweeb.beamline.feature.search.SearchContext
import io.github.spysyweeb.beamline.feature.search.SearchMode
import io.github.spysyweeb.beamline.nav.StopLeg
import io.github.spysyweeb.beamline.settings.AppState
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/** P21 (0.1.40): the route step the map shows (AppFlows.shownStep): its route, its index in RouteChoice.steps, where it is. */
data class ShownStep(val choiceId: Long, val index: Int, val point: GeoPoint)

/** Item 51: the stop results of [requestId] ended; [all]: no newer stop results took their place (AppRules.stopResultsEnded). */
data class StopEnd(val requestId: Long, val all: Boolean)

/** Small decisions of the app shell, kept free of Android so the JVM tests hold them. */
object AppRules {
    /**
     * SP19 (next run, part 1): after a save from the open sheet's own SAVE, the sheet's place with
     * the new saved id, so a point (a dropped pin, which SavedPlaces.same never matches) shows
     * "Saved as {name}" and Edit; null when the save was not this sheet's (no sheet, another sheet,
     * a place already carrying a saved id, or a saved point more than about 2 m from the sheet's).
     * Since 0.1.40 (P17, F42) "this sheet" is [sameSheetPlace]: a dropped pin's street line that came in
     * while the save dialog was open does not make it another sheet; the latest line is kept.
     */
    fun sheetAfterSave(sheet: Place?, saveFrom: Place?, saved: SavedPlace): Place? {
        if (sheet == null || saveFrom == null || !sameSheetPlace(sheet, saveFrom) || sheet.savedId != null) return null
        if (abs(saved.point.lat - sheet.point.lat) > 2e-5 || abs(saved.point.lon - sheet.point.lon) > 2e-5) return null
        return sheet.copy(savedId = saved.id)
    }

    /** F42 (0.1.40): two Places are the same place window (the line or the saved id may differ): same source, point, name, pack entry. */
    fun sameSheetPlace(a: Place, b: Place): Boolean =
        a.source == b.source && a.point == b.point && a.name == b.name && a.packId == b.packId && a.searchId == b.searchId

    /** "42.13646, -80.08718": a point's line before its street is known (AppFlows.coordinates, pointText). */
    private val COORDINATES = Regex("""^-?\d{1,3}\.\d+, -?\d{1,3}\.\d+$""")

    /** P17/P18 (0.1.40): a point (MAP_PIN) whose line is still its coordinates, or that has none: its street is looked up. */
    fun lacksStreet(p: Place): Boolean =
        p.source == PlaceSource.MAP_PIN && (p.subtitle.isNullOrBlank() || COORDINATES.matches(p.subtitle!!.trim()))

    /** P58 (0.1.40): the Undo bar's words after a saved place's Remove: "Home removed", "Mom's removed". */
    fun removedText(name: String) = "$name removed"

    /** P58: Undo could not put it back (its Home or Work slot, or its name, was taken in the meantime). */
    fun notRestoredText(name: String) = "$name could not be put back."

    /**
     * P58: the place window after Undo of a removal. [window] was the removed place's window when it was removed
     * (null: none). A saved place's own window (closed by the removal) opens again when nothing opened meanwhile
     * ([current] null, [sameScreens]); a saved dropped pin's window, still open, takes its saved id back
     * ([sheetAfterSave]: "Saved as ...", Edit). Null: leave the window as it is.
     */
    fun windowAfterUndo(window: Place?, current: Place?, sameScreens: Boolean, back: SavedPlace): Place? = when {
        window == null -> null
        window.source == PlaceSource.SAVED -> back.toPlace().takeIf { current == null && sameScreens }
        else -> sheetAfterSave(current, window.copy(savedId = null), back)
    }

    /**
     * S1b (0.1.31; the owner, 2026-11-25), since 0.1.40 (play test P6) only after the user moved the map by
     * hand: a search for a place (GO_TO) ranks from the map's [center] when [seen] (the window at its last
     * rest) does not hold the user's position and [movedByUser] (a drag, pinch or turn since the window last
     * held it); distances stay from the user. A camera the app moved (K1, S11) keeps [ctx], as do another
     * mode, no fix, or a camera not yet at rest.
     */
    fun searchContextFor(mode: SearchMode, ctx: SearchContext, seen: Bounds?, center: GeoPoint?, movedByUser: Boolean): SearchContext {
        if (mode != SearchMode.GO_TO || ctx.source != NearSource.GPS || !movedByUser) return ctx
        val me = ctx.near ?: return ctx
        if (seen == null || center == null || seen.contains(me)) return ctx
        return SearchContext(center, NearSource.MAP_CENTER, distanceFrom = me)
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
     * under it). At the bottom, while not [driving] and not in the side layout, the place sheet when it
     * is higher than the overlay's block ([placeSheet], so a picked place shows above it, K1) and the
     * credit line with the my-location button above it ([chromeBottom]); while [driving] ([guideCamera]:
     * guiding, re-routing) they are left out, so the guide camera's arrow stays where the drive test
     * checked it. [topChrome]: from [cameraTopChrome]: the search pill's block while the results are on
     * the map (S11), the search bar's bottom while a place sheet is open (K1b), else 0. [stopSheet]
     * (item 51, rule K8): the stop results' window at the bottom (upright), counted while driving too
     * (the camera is top-down then), with the credit row above it; on its side the window is in the
     * start panel, which the overlay's start inset already keeps clear. [sheetPanel]: the place window's or
     * the results list's side panel (P7), which takes the start as the drive overlay's does, while the credit
     * row stays at the bottom; [safeLeft], [safeRight]: a navigation bar or a display cutout at the left or the
     * right (P1), which fitted things keep clear of (physical sides, as EdgeInsetsDp is).
     */
    fun cameraInsets(
        overlay: EdgeInsetsDp, statusBarTop: Float, chromeBottom: Float, driving: Boolean, placeSheet: Float = 0f, topChrome: Float = 0f,
        sheetPanel: Float = 0f, safeLeft: Float = 0f, safeRight: Float = 0f, stopSheet: Float = 0f,
    ): EdgeInsetsDp = overlay.copy(
        top = max(max(overlay.top, statusBarTop), topChrome),
        bottom = when {
            overlay.start > 0f -> overlay.bottom                              // the drive overlay's side panel (also the stop overlay's)
            sheetPanel > 0f -> overlay.bottom + chromeBottom                  // P7: a browsing window in the panel
            stopSheet > 0f -> max(overlay.bottom, stopSheet) + chromeBottom   // item 51, upright, also while driving
            driving -> overlay.bottom
            else -> max(overlay.bottom, placeSheet) + chromeBottom
        },
        start = max(max(overlay.start, sheetPanel), safeLeft),
        end = max(overlay.end, safeRight),
    )

    /**
     * P7 (0.1.40): where the credit row with my-location sits: [BottomChrome.startDp] from the start edge,
     * [BottomChrome.bottomDp] above the bottom edge (0: on the bars' inset, at least 8 dp),
     * [BottomChrome.onSheet] while it rides on a browsing window (P7's locationFits). [sheetDp]: the place window's,
     * the results list's or the stop results' height (null: none shows); [panelDp]: the side panel's width while the
     * window is on its side (:ui:theme isSideLayout; null upright); [overlay]: the drive overlay's blocks;
     * [pickDp]: the pick bar's room (null: no map pick). Upright the row rides above the sheet or the
     * overlay's block (K1, 5.4 item 4); on its side the sheet is in the start panel, as the route sheet,
     * and the row stays at the bottom beside it (riding up with it, it covered Map style).
     */
    fun bottomChrome(sheetDp: Float?, panelDp: Float?, overlay: EdgeInsetsDp, pickDp: Float?): BottomChrome {
        val inPanel = sheetDp != null && panelDp != null
        return BottomChrome(
            startDp = if (inPanel) max(overlay.start, panelDp) else overlay.start,
            bottomDp = when {
                pickDp != null -> pickDp
                sheetDp != null && !inPanel -> max(sheetDp, overlay.bottom)
                else -> overlay.bottom
            },
            onSheet = pickDp == null && sheetDp != null && !inPanel,
        )
    }

    /**
     * P7 (0.1.40): my-location rides above a browsing window (K1); in a window too short for that (split
     * screen, a 640 dp phone with the window at its cap) it would reach Settings and Map style, so it is
     * left out while the row ([room], riding on a window) plus the button and a gap do not fit under
     * [topChromeDp] (the end column's bottom edge, or the status bar's while the pill is not shown) in a
     * window [windowDp] tall. A row not on a window (the drive overlay's blocks, the pick bar, nothing)
     * and a window not yet measured (0) fit, as today.
     */
    fun locationFits(room: BottomChrome, windowDp: Float, topChromeDp: Float): Boolean =
        !room.onSheet || windowDp <= 0f || room.bottomDp + LOCATION_ROW_DP + SHEET_GAP_DP <= windowDp - topChromeDp

    /**
     * P7 (0.1.40): the most a browsing window (the place window, the results list, item 51's stop results)
     * may be tall in a window [windowDp] tall: under the top chrome over it ([chromeDp], from the window's
     * top: upright the whole top row, on its side the search pill's own column, else the status bar) and a
     * gap, at least [minDp] (its lowest height, which does not scroll); and never over the drive overlay's
     * top stack ([overlayTopDp]: the banner and the chip or stop card under it; 0: none), which wins over
     * [minDp] (item 51: the banner stays in view while a stop is added). Null: not measured yet, no cap.
     */
    fun sheetRoomDp(windowDp: Float, chromeDp: Float, overlayTopDp: Float, minDp: Float): Float? {
        if (windowDp <= 0f) return null
        val underChrome = max(windowDp - chromeDp - SHEET_GAP_DP, minDp)
        return if (overlayTopDp > 0f) max(min(underChrome, windowDp - overlayTopDp - SHEET_GAP_DP), 0f) else underChrome
    }

    /** The my-location button's height (MapChrome.LocationButton). */
    const val LOCATION_ROW_DP = 56f

    /** P7: the gap a window and my-location keep under what is above them (as D8's SPEED_CLUSTER_TOP_CLEARANCE). */
    const val SHEET_GAP_DP = 8f

    /**
     * P7: the least room a browsing window keeps under the top chrome, at font 1.0 (times the font scale):
     * the place window's lowest height with its tallest header (title, distance, hours, the own-values tag)
     * and its two 52 dp buttons. That height does not scroll; a cap under it cut Directions.
     */
    const val SHEET_MIN_ROOM_DP = 184f

    /**
     * [cameraInsets]'s `driving`: the phases whose camera leaves the place sheet and the credit row out of
     * the bottom inset, guiding and re-routing, so the guide camera's arrow stays where the drive test
     * checked it. Not ARRIVED (P4): the destination's window raised from the arrival card, and the credit
     * row above the card, are in the insets, so the arrival view frames the car and the place above them.
     */
    fun guideCamera(phase: Phase): Boolean = phase == Phase.GUIDING || phase == Phase.REROUTING

    /**
     * [cameraInsets]'s topChrome, from the top of the window: while the results are on the map the
     * pill's whole block ([pillBlock]: the bar, its chips and the buttons beside it, S11); while a
     * place sheet is open the search bar's bottom edge ([barBottom], K1b: a place above its sheet keeps
     * clear of the bar; the buttons at the side are left out, else too little room is left above a
     * sheet at its details height); else none. Only while the pill shows (no trip, no search, no pick).
     */
    fun cameraTopChrome(pillShown: Boolean, resultsShown: Boolean, sheetShown: Boolean, pillBlock: Float, barBottom: Float): Float = when {
        !pillShown -> 0f
        resultsShown -> pillBlock
        sheetShown -> barBottom
        else -> 0f
    }

    /**
     * Item 51 (rule K8, add-stop.md section 5 "What the app passes"): the points the camera shows while a
     * stop is added on the map. The list: the places and where a new stop joins the trip (J: the first
     * point of StopLeg.lineAhead, else the vehicle); a row selected whose route is not there yet: J and
     * the place; its route there (the session's [preview] of that place): the leg into the stop
     * (StopLeg.lineToStop) and the place. Null without stop results (the teardown ends the view).
     */
    fun stopViewPoints(results: ResultsUi?, preview: StopPreview?, trip: TripState): List<GeoPoint>? {
        if (results?.stop == null) return null
        val join = StopLeg.lineAhead(trip).firstOrNull() ?: trip.guidance?.vehicle?.point
        val place = results.selected?.let { results.places.getOrNull(it) }
            ?: return results.places.map { it.point } + listOfNotNull(join)
        val route = preview?.takeIf { it.place == place }?.route ?: return listOfNotNull(join) + place.point
        return StopLeg.lineToStop(route) + place.point
    }

    /**
     * Item 51: the stop results shown went from request [old] to [now] (null: none). Their end when [old]
     * is no longer shown: its Detours request is dropped; [StopEnd.all] when no newer stop results took
     * over (then the preview and the camera's stop view end too). Null when nothing ended.
     */
    fun stopResultsEnded(old: Long?, now: Long?): StopEnd? = if (old != null && now != old) StopEnd(old, all = now == null) else null

    /** P21: [step]'s index while it is a step of the selected route of a preview (PLANNING, CHOOSING); null otherwise. */
    fun shownStep(step: ShownStep?, trip: TripState): Int? {
        step ?: return null
        if (trip.phase != Phase.PLANNING && trip.phase != Phase.CHOOSING) return null
        if (trip.selectedChoiceId != step.choiceId) return null
        val steps = trip.choices.firstOrNull { it.id == step.choiceId }?.steps ?: return null
        return step.index.takeIf { it in steps.indices }
    }

    /**
     * Play test P10, audit F30: Undo of a removed stop puts [place] back at [index] (the stops' own index,
     * reached ones included): only while the trip can still change its stops (C1), and not when the stop
     * is back already (a change during guidance that failed is undone by the session, C3). Null: nothing to do.
     */
    fun undoRemovedStop(place: Place, index: Int, trip: TripState): NavIntent.AddStop? {
        if (trip.phase !in STOP_EDIT_PHASES) return null
        if (trip.stops.any { !it.reached && it.place == place }) return null
        return NavIntent.AddStop(place, index.coerceIn(trip.stops.count { it.reached }, trip.stops.size))
    }

    private val STOP_EDIT_PHASES = setOf(Phase.PLANNING, Phase.CHOOSING, Phase.GUIDING, Phase.REROUTING)

    /**
     * Play test P10 (review): a stop's Undo offer ([offerText]) shows in the open stops editor's note line,
     * with its Undo button, over "Stops reordered." or a problem (the latest thing the person did; a
     * failed change during guidance puts the stop back itself, and the offer is then no longer useful).
     */
    fun withStopUndo(drive: DriveUi, offerText: String?): DriveUi {
        val e = drive.stopsEditor ?: return drive
        if (offerText == null) return drive
        return drive.copy(stopsEditor = e.copy(note = offerText, canUndo = true))
    }

    const val STOP_REMOVED = "Stop removed"

    /** How long an Undo offer stands (a removed stop, P58, P45): as long as the End bar's (R4). */
    const val UNDO_BAR_MS = END_UNDO_MS

    /**
     * Play test P31, audit F34: the destination is saved when the store finds it ([found]: SavedPlaces.find,
     * its saved id or a same-named place nearby), or it was saved at arrival ([arrivalSavedId], still in
     * [saved]: a dropped pin is never found by name).
     */
    fun destinationSaved(destination: Place?, saved: List<SavedPlace>, arrivalSavedId: String?, found: SavedPlace?): Boolean =
        destination != null && (found != null || (arrivalSavedId != null && saved.any { it.id == arrivalSavedId }))

    /**
     * Play test P31: a place just saved is the arrived trip's destination: the trip is ARRIVED and the
     * saved point is the destination's (within about 2 m, as [sheetAfterSave] compares). Any other save
     * (another place, another phase) is not.
     */
    fun savedAtArrival(phase: Phase, destination: Place?, saved: SavedPlace): Boolean =
        phase == Phase.ARRIVED && destination != null &&
            abs(saved.point.lat - destination.point.lat) <= 2e-5 && abs(saved.point.lon - destination.point.lon) <= 2e-5

    /** The arrival card with its saved flag (the presenter cannot see the saved places). */
    fun withDestinationSaved(drive: DriveUi, saved: Boolean): DriveUi {
        val g = drive.guidance ?: return drive
        val a = g.status as? GuidanceStatusUi.Arrived ?: return drive
        return if (a.saved == saved) drive else drive.copy(guidance = g.copy(status = a.copy(saved = saved)))
    }

    /**
     * Play test P28: what the first Back says while a trip is driven, arrived or offered; the second within
     * [BACK_AGAIN_MS] leaves the app ([secondBack]). Null: Back is not held in that phase.
     */
    fun backHint(phase: Phase): String? = when (phase) {
        Phase.GUIDING, Phase.REROUTING -> BACK_AGAIN_GUIDING
        Phase.ARRIVED, Phase.RESUME_OFFERED -> BACK_AGAIN
        Phase.IDLE, Phase.PLANNING, Phase.CHOOSING -> null
    }

    /** P28: a Back at [nowMs] (monotonic) is the second of a pair whose first came at [armedAtMs]. */
    fun secondBack(armedAtMs: Long?, nowMs: Long): Boolean = armedAtMs != null && nowMs - armedAtMs in 0..BACK_AGAIN_MS

    /**
     * P28: a first Back pressed in [armedIn] stays armed when the trip goes to [phase] while its hint says the same (a
     * reroute between the two presses, GUIDING and REROUTING); another hint, or a phase that does not hold Back, disarms it.
     */
    fun backArmKept(armedIn: Phase?, phase: Phase): Boolean {
        val hint = armedIn?.let(::backHint) ?: return false
        return hint == backHint(phase)
    }

    const val BACK_AGAIN_MS = 2_000L
    const val BACK_AGAIN_GUIDING = "Press Back again to leave Beamline. Guidance keeps running."
    /** On the arrival card (it says "Arrived" with Done) and the resume card (nothing runs yet). */
    const val BACK_AGAIN = "Press Back again to leave Beamline."

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

    /**
     * P39: the system bars' icons set from the map after a rotation. "ForLightBackground" as Overlay.kt's
     * setStatusBarForLightBackground names it: true means dark icons. [statusForLightBackground] null:
     * the status bar is left as it is.
     */
    data class BarIcons(val navForLightBackground: Boolean, val statusForLightBackground: Boolean?)

    /**
     * P39: a rotation lets the platform redraw the system bars with light icons (seen on the emulator:
     * the navigation bar, and the status bar's clock white over the day map in landscape), so both are
     * set again from the map: dark icons over the day map once the reveal is done, light over the night
     * map and the reveal. The status bar is left alone while [driveHoldsStatusBar]: the drive overlay's
     * dark banner or re-route card is under it (LightStatusBarIcons in :feature:drive).
     */
    fun barIconsAfterRotation(night: Boolean, revealDone: Boolean, driveHoldsStatusBar: Boolean): BarIcons {
        val forLight = !night && revealDone
        return BarIcons(navForLightBackground = forLight, statusForLightBackground = if (driveHoldsStatusBar) null else forLight)
    }

    /**
     * P39: the drive overlay has its dark turn banner or re-route card at the top, the same test as its
     * own `darkTop` (feature/drive Overlay.kt; keep the two in step). Upright that block reaches under the
     * status bar and the overlay holds the bar's icons light; on its side it does not.
     */
    fun driveDarkTop(guidance: GuidanceUi?): Boolean = guidance != null && guidance.status !is GuidanceStatusUi.Arrived &&
        (guidance.banner != null || guidance.status is GuidanceStatusUi.Rerouting || guidance.status is GuidanceStatusUi.RerouteFailed)
}

/** P7: the credit row's place (AppRules.bottomChrome), in dp; [onSheet]: it rides on a browsing window (upright). */
data class BottomChrome(val startDp: Float, val bottomDp: Float, val onSheet: Boolean = false)
