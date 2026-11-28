package io.github.spysyweeb.beamline.feature.drive

import io.github.spysyweeb.beamline.ui.theme.LocalSheetLag
import androidx.compose.runtime.CompositionLocalProvider
import io.github.spysyweeb.beamline.ui.theme.SHEET_CORNER
import io.github.spysyweeb.beamline.ui.theme.SIDE_LAYOUT_MIN_WIDTH
import io.github.spysyweeb.beamline.ui.theme.SheetHeight
import io.github.spysyweeb.beamline.ui.theme.isSideLayout
import io.github.spysyweeb.beamline.ui.theme.sidePanelWidth
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.os.Build
import android.view.View
import android.view.Window
import android.view.WindowInsetsController
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.NonRestartableComposable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceAtLeast
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.spysyweeb.beamline.api.map.EdgeInsetsDp
import io.github.spysyweeb.beamline.api.nav.LaneMark

/** Test tags of the overlay's parts (the device tests and the integrator's checks find them by these). */
object DriveTags {
    const val OVERLAY = "drive-overlay"
    const val BANNER = "banner"
    const val SPEED_SIGN = "speed-sign"
    const val SPEED_BADGE = "speed-badge"
    /** Item 32: the sign and the badge together, a button while [SpeedUi.canReport]. */
    const val SPEED_PAIR = "speed-pair"
    const val CURRENT_ROAD = "current-road"
    const val BANNER_ICON = "banner-icon"
    const val BANNER_DISTANCE = "banner-distance"
    const val BANNER_TEXT = "banner-text"
    const val THEN_CHIP = "then-chip"
    const val LANES = "lanes"
    const val LANE_NOTE = "lane-note"
    fun lane(mark: LaneMark) = "lane-" + mark.name.lowercase()
    const val STATUS_CARD = "status-card"
    const val STATUS_CHIP = "status-chip"
    const val STOP_CARD = "stop-card"
    const val ARRIVED_CARD = "arrived-card"
    const val SAVE_DESTINATION = "save-destination"
    const val SWAP = "swap-ends"
    const val DESTINATION_AWAY = "destination-away"
    const val TRIP_BAR = "trip-bar"
    const val SIMULATED = "simulated"
    const val RECENTER = "recenter"
    const val OVERVIEW = "overview"
    const val RESUME_CARD = "resume-card"
    const val ROUTE_SHEET = "route-sheet"
    const val START_ROW = "start-row"
    const val MORE = "more"
    const val SIMULATE = "simulate"
    const val CANCEL = "cancel"
    const val ADD_STOP = "add-stop"
    const val OPEN_STOPS = "open-stops"
    /** A stop line of the route sheet (the stops editor's rows are [stopRow]). */
    fun stopLine(index: Int) = "stop-line-$index"
    fun card(choiceId: Long) = "card-$choiceId"
    /** A route's small card at the route sheet's lowest height (rule D10). */
    fun lowCard(choiceId: Long) = "card-low-$choiceId"
    const val PROBLEM = "problem"
    const val AVOID_HIGHWAYS = "avoid-highways"
    const val AVOID_TOLLS = "avoid-tolls"
    const val START = "start"
    const val START_BLOCKED = "start-blocked"
    const val BUSY = "busy"
    const val STOPS_EDITOR = "stops-editor"
    const val CLOSE_STOPS = "close-stops"
    const val BEST_ORDER = "best-order"
    fun stopRow(index: Int) = "stop-row-$index"
    const val DONE = "done"
    const val END = "end"
    const val CONTINUE = "continue"
    const val DISCARD = "discard"
    /** P52: the fade over the route sheet's scrolling part while it scrolls further. */
    const val MORE_BELOW = "more-below"
    /** P21: the step view at the route sheet's middle height (which step, Previous / Next, back to the list). */
    const val STEP_VIEW = "step-view"
    const val STEP_TITLE = "step-title"
    const val STEP_BACK = "step-back"
    const val STEP_PREVIOUS = "step-previous"
    const val STEP_NEXT = "step-next"
    /** P19, P20: "From Butler · 1 stop" under "To ..." at the middle height. */
    const val CONTEXT_LINE = "context-line"
    /** Audit F27, P19: "Guidance starts from where you are." above Start. */
    const val START_NOTE = "start-note"
    /** Play test P49, P10: the stops editor's note line ("Stops reordered.", a problem, "Stop removed"), its Undo, and its arrival line while guided. */
    const val STOPS_NOTE = "stops-note"
    const val STOPS_UNDO = "stops-undo"
    /** The 0.1.40 replay: the fade over the stops editor's last rows while they scroll further (as [MORE_BELOW]). */
    const val STOPS_MORE_BELOW = "stops-more-below"
    const val STOPS_ARRIVE = "stops-arrive"
    /** Play test P31, audit F34: the arrival card's "Saved" (in place of "Save this place"). */
    const val DESTINATION_SAVED = "destination-saved"
    /** Play test P5: the trip bar's "No route from here". */
    const val NO_ROUTE = "no-route"
}

/** Rule D8: the speed pair's gap above the trip bar (or the bottom inset on its side), level with the map buttons. */
internal val SPEED_CLUSTER_BOTTOM_GAP: Dp = 16.dp

/** Rule D8: the same gap while the map credit's one short line shows in that corner; the pair slides down when it fades. */
internal val SPEED_CLUSTER_CREDIT_GAP: Dp = 36.dp

/** Rule D8: the room the speed pair keeps under the top stack, and its height before it was first measured. */
internal val SPEED_CLUSTER_TOP_CLEARANCE: Dp = 8.dp
internal val SPEED_CLUSTER_HEIGHT_GUESS: Dp = 76.dp

/**
 * Play test P10 (review): the least height the bottom block keeps while guided when the room under the banner is
 * smaller: the stops editor's header, one row, its buttons and its note line (72 + 64 + 56 + 52, rounded up), times
 * the font scale. Then the editor may cover the banner's lower part (its lanes) in a very short window.
 */
internal val STOPS_EDITOR_MIN_HEIGHT: Dp = 260.dp

/** P49: the key of the route sheet's kept state while the stops editor has its place; one per trip shown (rule D10). */
private fun routeSheetState(trip: Int) = "route-sheet-$trip"

/** Item 22: the room the current-road label leaves at the start for the speed pair (sign, gap, badge) and at the end for the map buttons. */
internal val CURRENT_ROAD_START_ROOM: Dp = 148.dp
internal val CURRENT_ROAD_END_ROOM: Dp = 76.dp

/**
 * The body of [DriveOverlay]. Upright: the banner (or the re-route card, D7) at the top under the
 * status bar, the GPS chip and the stop card under it, the route sheet, the stops editor (in the route
 * sheet's or the trip bar's place, play test P49; while guided never above the banner but for its
 * [STOPS_EDITOR_MIN_HEIGHT]), trip bar, arrival or resume card at the bottom, the map buttons above it;
 * [onInsets] gets the height of the top block and of the bottom block. On its side (`:ui:theme`'s [isSideLayout], wider
 * than tall and at least [SIDE_LAYOUT_MIN_WIDTH]): the same blocks in a panel on the start side
 * ([sidePanelWidth]), the map buttons at the bottom end; [onInsets] gets the panel's width as `start`.
 * The chips, cards and buttons are left out of the insets, so the camera does not jump when they come
 * and go.
 *
 * [stopWindowDp] (item 51, rule S12): the stop results' window is this high at the bottom (0 on its side,
 * where it is in the start panel): only the top block, the chip or stop card and the speed pair above the
 * window are drawn, and the insets are the top block's height (upright) or the panel's width (on its side).
 * [onTopStack] (play test P7): where the top stack ends, in whole dp from the overlay's top, in both layouts.
 * [safe]: the system bars and a display cutout, which the content keeps clear of (a parameter for the tests).
 */
@Composable
internal fun DriveOverlayContent(
    ui: DriveUi, onEvent: (DriveEvent) -> Unit, onInsets: (EdgeInsetsDp) -> Unit, modifier: Modifier = Modifier, creditShown: Boolean = false,
    stopWindowDp: Float? = null, onTopStack: (Float) -> Unit = {},
    // The system bars and a display cutout: the banner's colour and the sheets reach under them, their content does not
    // (a parameter so a screen test can give the overlay a navigation bar at the side, play test P1, P32).
    safe: WindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout),
) {
    BoxWithConstraints(modifier.fillMaxSize().testTag(DriveTags.OVERLAY)) {
        // play test P7: the side rule is :ui:theme's, shared with the app's browsing windows (the same 560 dp and 320-400 dp panel)
        val side = isSideLayout(maxWidth, maxHeight)
        val panelWidth = if (side) sidePanelWidth(maxWidth) else maxWidth
        val density = LocalDensity.current
        var topPx by remember { mutableIntStateOf(0) }
        var bottomPx by remember { mutableIntStateOf(0) }
        // the sheet's remaining slide (LocalSheetLag): the camera gets the height it is going to at once
        val sheetLag = remember { mutableIntStateOf(0) }
        // P49: the route sheet's own state (height, scroll, "Show all steps", the step shown) while the stops editor has its
        // place (or the stop results' window hides the bottom block). A new trip's sheet starts afresh (rule D10): its
        // state goes when the sheet goes, and (review, 0.1.40) a trip planned while a preview shows (PLANNING to PLANNING:
        // a map place's Directions, a link from another app; [newTripShown]) gets a sheet of its own under a new key.
        val sheets = rememberSaveableStateHolder()
        var sheetTrip by rememberSaveable { mutableIntStateOf(0) }
        // the stops last shown, a key of each (not saved: after a restart the sheet shows the same trip)
        val shownStops = remember { arrayOf(emptyList<Int>()) }
        val planning = ui.planning
        if (planning == null) {
            shownStops[0] = emptyList()
            val gone = sheetTrip
            SideEffect { sheets.removeState(routeSheetState(gone)) }
        } else {
            val stops = planning.stops.map { (it.title to it.subtitle).hashCode() }
            if (newTripShown(shownStops[0], stops, hasRoutes = planning.cards.isNotEmpty())) {
                val old = sheetTrip
                SideEffect { sheets.removeState(routeSheetState(old)) }
                sheetTrip = old + 1
            }
            shownStops[0] = stops
        }
        // P49: what the bottom block holds; when that changes, the slide left over from the sheet that went is dropped
        // (TripBar and ResumeCard have no SheetFrame to write it again), so the camera's inset is the new content's height.
        // Reset here, before the insets are read and before the new content's first layout writes its own slide.
        val bottomKind = when {
            stopWindowDp != null -> 6 // item 51: nothing at the bottom
            ui.stopsEditor != null -> 1
            ui.planning != null -> 2
            ui.guidance?.status is GuidanceStatusUi.Arrived -> 3
            ui.guidance != null -> 4
            ui.resume != null -> 5
            else -> 0
        }
        val shownKind = remember { intArrayOf(bottomKind) }
        if (shownKind[0] != bottomKind) {
            shownKind[0] = bottomKind
            sheetLag.intValue = 0
        }
        // D8 (batch B): the bottom of the chip / stop card column and the speed pair's height, to
        // know whether the pair has room between the top stack and the bottom block
        var chipBottomPx by remember { mutableIntStateOf(0) }
        var clusterPx by remember { mutableIntStateOf(0) }
        // item 51 (rule S12): while the stop results' window shows, only the top and the speed pair are drawn; the
        // pair sits above the window (upright) instead of above the bottom block, which is not drawn
        val stopWindow = stopWindowDp != null
        val bottomRoomPx = if (stopWindowDp != null) with(density) { stopWindowDp.dp.roundToPx() } else bottomPx
        val insets = when {
            // item 51: the app pads the camera by the window itself; the top block here (or the panel on its side, where
            // the window is, also with no banner)
            stopWindow -> if (side) EdgeInsetsDp(start = panelWidth.value) else EdgeInsetsDp(top = topPx / density.density)
            // whole dp: the height now and the remaining slide add up to the same target every frame
            // (P49: the stops editor is in the bottom block, so its height is the camera's inset as the sheet's is)
            !side -> EdgeInsetsDp(top = topPx / density.density, bottom = kotlin.math.round((bottomPx + sheetLag.intValue) / density.density))
            topPx > 0 || bottomPx > 0 -> EdgeInsetsDp(start = panelWidth.value)
            else -> EdgeInsetsDp()
        }
        val reportInsets by rememberUpdatedState(onInsets)
        LaunchedEffect(insets) { reportInsets(insets) }

        // The system bars and a display cutout ([safe]): the banner's colour and the sheets reach under them, their content does not.
        val horizontal = if (side) WindowInsetsSides.Start else WindowInsetsSides.Horizontal
        val safeTop = safe.asPaddingValues().calculateTopPadding()
        val panelAlign = if (side) Alignment.Start else Alignment.CenterHorizontally

        val guidance = ui.guidance
        val status = guidance?.status
        val arrived = status is GuidanceStatusUi.Arrived
        val night = isNightTheme()
        val palette = if (night) BannerPalette.Night else BannerPalette.Day
        val darkTop = guidance != null && !arrived &&
            (guidance.banner != null || status is GuidanceStatusUi.Rerouting || status is GuidanceStatusUi.RerouteFailed)
        // Upright, the banner's colour reaches up behind the status bar; on its side the panel starts under it.
        val topBox = if (side) {
            Modifier
                .windowInsetsPadding(safe.only(WindowInsetsSides.Top + horizontal))
                .clip(RoundedCornerShape(bottomEnd = 16.dp))
                .background(palette.background)
        } else {
            Modifier.background(palette.background).windowInsetsPadding(safe.only(WindowInsetsSides.Top + horizontal))
        }
        LightStatusBarIcons(on = darkTop && !side, night = night)

        // Top block: measured for the camera.
        Column(Modifier.align(if (side) Alignment.TopStart else Alignment.TopCenter).width(panelWidth).onSizeChanged { topPx = it.height }) {
            if (guidance != null && !arrived) {
                val banner = guidance.banner
                if (banner != null) {
                    Column(topBox) { TurnBanner(banner, palette = palette) }
                } else if (status is GuidanceStatusUi.Rerouting || status is GuidanceStatusUi.RerouteFailed) {
                    Column(topBox) { StatusCard(status, onEvent) }
                }
            }
        }
        // Under the top block: the GPS chip (or a failed change while the banner stays) and the stop card.
        val chipShown = guidance != null && !arrived && run {
            val chip = status != null && guidance.banner != null && status !is GuidanceStatusUi.OnRoute && status !is GuidanceStatusUi.StopReached
            val chipNoBanner = status != null && guidance.banner == null &&
                (status is GuidanceStatusUi.WaitingForGps || status is GuidanceStatusUi.GpsWeak || status is GuidanceStatusUi.GpsLost)
            chip || chipNoBanner || status is GuidanceStatusUi.StopReached
        }
        // P7: a chip that went leaves no stale bottom
        if (!chipShown) chipBottomPx = 0
        // D8's top stack: the banner block, and the chip or stop card under it, from the overlay's top
        val topStackPx = maxOf(topPx, if (chipShown) chipBottomPx else 0)
        // P7 (0.1.40): where the top stack ends, in whole dp from the overlay's top, in both layouts: the app keeps its
        // browsing windows under it (on its side they share the start panel with the banner; item 51: the banner stays in view)
        val topStackDp = kotlin.math.round(topStackPx / density.density)
        val reportTopStack by rememberUpdatedState(onTopStack)
        LaunchedEffect(topStackDp) { reportTopStack(topStackDp) }
        if (guidance != null && !arrived) {
            if (chipShown) {
                Column(
                    Modifier
                        .align(if (side) Alignment.TopStart else Alignment.TopCenter)
                        .onSizeChanged { chipBottomPx = it.height }
                        .width(panelWidth)
                        .padding(top = with(density) { topPx.toDp() })
                        .then(if (topPx == 0) Modifier.windowInsetsPadding(safe.only(WindowInsetsSides.Top + horizontal)) else Modifier)
                        .padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    val shownStatus = guidance.status
                    if (shownStatus is GuidanceStatusUi.StopReached) StopReachedCard(shownStatus) else StatusChip(shownStatus, onEvent)
                }
            }
            // D8: the speed-limit sign and the current speed at the bottom start of the map (the owner,
            // 2026-11-18): above the trip bar upright, beside the side panel on its side, level with
            // the map buttons at the bottom end; higher while the map credit's short line shows in that
            // corner (the first seconds of guiding), sliding down when it fades.
            val speedGap by animateDpAsState(if (creditShown) SPEED_CLUSTER_CREDIT_GAP else SPEED_CLUSTER_BOTTOM_GAP, tween(500), label = "speedGap")
            // In a window too short for it (split screen, about 390 dp tall) the pair is left out while
            // it would reach the top stack (banner, chip, stop card); it comes back when there is room.
            // On its side the pair is beside the panel, clear of the stack. Item 51: above the stop
            // results' window, left out the same way while the window leaves no room (raised high).
            val room = side || with(density) {
                val cluster = if (clusterPx > 0) clusterPx else (SPEED_CLUSTER_HEIGHT_GUESS * fontScale).roundToPx()
                maxHeight.toPx() - bottomRoomPx - speedGap.toPx() - cluster >= topStackPx + SPEED_CLUSTER_TOP_CLEARANCE.toPx()
            }
            guidance.speed?.takeIf { room }?.let { speed ->
                SpeedCluster(
                    speed,
                    palette,
                    Modifier
                        .align(Alignment.BottomStart)
                        .then(
                            if (side) {
                                Modifier.windowInsetsPadding(safe.only(WindowInsetsSides.Bottom)).padding(start = panelWidth)
                            } else {
                                Modifier.padding(bottom = with(density) { bottomRoomPx.toDp() })
                            },
                        )
                        .padding(start = 12.dp, bottom = speedGap)
                        .onSizeChanged { clusterPx = it.height },   // the pair itself, inside the paddings
                    onReport = { onEvent(DriveEvent.SpeedLimitTapped) },
                )
            }
        }
        // Item 22: the road being driven, a small label at the bottom of the map, level with the speed
        // pair and the map buttons and centred in the room between them (left out when the window is too short).
        // P32 (0.1.40): also left out while Recenter shows (the overview, or the map moved by hand): the pill,
        // level with it at the bottom end, covered its end; the driver is then not following the road's name.
        // Item 51: not drawn while the stop results' window shows.
        if (!stopWindow && guidance != null && !arrived && !guidance.showRecenter) {
            guidance.currentRoad?.let { road ->
                val short = with(density) { maxHeight.toPx() - bottomPx < topPx + (SPEED_CLUSTER_HEIGHT_GUESS * fontScale).toPx() * 2 }
                if (side || !short) {
                    CurrentRoadLabel(
                        road,
                        palette,
                        Modifier
                            .align(Alignment.BottomCenter)
                            .then(
                                if (side) {
                                    // P32, P1: its end room counts from a navigation bar at the end, as the map buttons' does
                                    Modifier.windowInsetsPadding(safe.only(WindowInsetsSides.Bottom + WindowInsetsSides.End)).padding(start = panelWidth)
                                } else {
                                    Modifier.padding(bottom = with(density) { bottomPx.toDp() })
                                },
                            )
                            .padding(start = CURRENT_ROAD_START_ROOM, end = CURRENT_ROAD_END_ROOM, top = SPEED_CLUSTER_BOTTOM_GAP, bottom = SPEED_CLUSTER_BOTTOM_GAP),
                    )
                }
            }
        }
        // Item 51: while the stop results' window shows, none of the bottom: no trip bar or sheet, map buttons or
        // stops editor (they come back as they were when the window goes).
        // Bottom block: measured for the camera; never taller than the room under the status bar.
        // P10 (review): while guided it stops under the banner or the re-route card (both layouts), so the stops editor
        // does not cover the turn; the editor's rows give up room first and scroll. In a window too short for it the block
        // keeps STOPS_EDITOR_MIN_HEIGHT (the editor's header, one row, its buttons and its note) and may then cover the
        // banner's lower part (its lanes). topPx holds the status bar (both layouts pad it inside the top block).
        val bottomMax = if (guidance != null && !arrived && topPx > 0) {
            minOf(maxHeight - safeTop - 8.dp, maxOf(maxHeight - with(density) { topPx.toDp() } - 8.dp, STOPS_EDITOR_MIN_HEIGHT * density.fontScale))
        } else {
            maxHeight - safeTop - 8.dp
        }
        if (!stopWindow) Column(
            Modifier
                .align(if (side) Alignment.BottomStart else Alignment.BottomCenter)
                .width(panelWidth)
                .heightIn(max = bottomMax.coerceAtLeast(0.dp))
                .onSizeChanged { bottomPx = it.height },
            horizontalAlignment = panelAlign,
        ) {
            val sheetInsets = safe.only(WindowInsetsSides.Bottom + horizontal)
            CompositionLocalProvider(LocalSheetLag provides sheetLag) { when {
                // play test P49, P10: the stops editor in place of the route sheet or the trip bar (never stacked on them):
                // the camera, the map buttons and the speed pair keep above it
                ui.stopsEditor != null -> BottomSheetSurface(sheetInsets) { StopsEditor(ui.stopsEditor, onEvent) }
                // item 47: the routes and Start under the map, everything in the side panel; its height, scroll and
                // "Show all steps" are kept while the editor has its place (sheets, above)
                ui.planning != null -> sheets.SaveableStateProvider(routeSheetState(sheetTrip)) {
                    BottomSheetSurface(sheetInsets) { RouteSheet(ui.planning, onEvent, opensAt = if (side) SheetHeight.FULL else SheetHeight.MIDDLE) }
                }
                guidance != null && status is GuidanceStatusUi.Arrived -> BottomSheetSurface(sheetInsets) { ArrivedCard(status, onEvent) }
                guidance != null -> BottomSheetSurface(sheetInsets) { TripBar(guidance.tripBar, onEvent) }
                ui.resume != null -> BottomSheetSurface(sheetInsets) { ResumeCard(ui.resume, onEvent) }
            } }
        }
        // P49, P10: with the stops editor open the buttons sit on its top edge; in a window too short for them under the
        // banner (and the chip or stop card) they are left out until Done. Without the editor: as before. Worked out from
        // what is shown now (Overview a 40 dp small FAB; Recenter 10 + 10 dp round a bodyLarge line, 24 sp), never from
        // a stale measurement.
        val buttonsRoom = side || ui.stopsEditor == null || guidance == null || with(density) {
            val need = if (guidance.showRecenter) (40.dp + 12.dp + 20.dp).toPx() + 24.sp.toPx() else 40.dp.toPx()
            maxHeight.toPx() - bottomPx - 16.dp.toPx() - need >= topStackPx + SPEED_CLUSTER_TOP_CLEARANCE.toPx()
        }
        if (!stopWindow && guidance != null && !arrived && buttonsRoom) {
            MapButtons(
                showRecenter = guidance.showRecenter,
                onEvent = onEvent,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .then(
                        if (side) {
                            Modifier.windowInsetsPadding(safe.only(WindowInsetsSides.Bottom + WindowInsetsSides.End))
                        } else {
                            Modifier.padding(bottom = with(density) { bottomPx.toDp() })
                        },
                    )
                    .padding(bottom = 16.dp, end = 12.dp),
            )
        }
    }
}

/** The bottom sheets' surface: rounded top, raised, reaching under the navigation bar (its content padded by [insets]). */
@Composable
private fun BottomSheetSurface(insets: WindowInsets, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(topStart = SHEET_CORNER, topEnd = SHEET_CORNER), // item 47: 24 dp on every sheet
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 8.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.windowInsetsPadding(insets), content = content)
    }
}

/**
 * The banner and the re-route card are dark by day and by night, and upright they reach up behind
 * the status bar: while [on], the status bar's icons are drawn light (set again after every
 * composition, about once per fix, so a change made meanwhile by the app is undone); when [on] ends
 * or the overlay goes, they follow the map again, as :app sets them (dark over the day map, light
 * over the night map). Nothing is changed while [on] is false.
 */
@Composable
@NonRestartableComposable // never skipped: runs with every composition of the overlay
private fun LightStatusBarIcons(on: Boolean, night: Boolean) {
    val view = LocalView.current
    val window = remember(view) { view.context.findActivity()?.window } ?: return
    val nightNow by rememberUpdatedState(night)
    SideEffect {
        if (on) window.setStatusBarForLightBackground(false)
    }
    DisposableEffect(on, window) {
        onDispose {
            if (on) window.setStatusBarForLightBackground(!nightNow)
        }
    }
}

/**
 * Dark status-bar icons ([light] background) or light ones, written to the same place androidx core
 * 1.19's `WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars` writes it (what
 * `MainActivity`'s `enableEdgeToEdge` uses): from API 35 the window's insets controller, below it the
 * decor view's `SYSTEM_UI_FLAG_LIGHT_STATUS_BAR`. The same place matters on API 30-34: once anything
 * calls `setSystemBarsAppearance`, the platform ignores that flag from then on, so the app's later
 * day/night switches would stop working. Platform calls only: section 1.1 gives `:feature:drive` no
 * library beyond Compose.
 */
@Suppress("DEPRECATION")
internal fun Window.setStatusBarForLightBackground(light: Boolean) {
    if (Build.VERSION.SDK_INT >= 35) {
        val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS
        insetsController?.setSystemBarsAppearance(if (light) mask else 0, mask)
    } else {
        val decor = decorView
        val flag = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
        decor.systemUiVisibility = if (light) decor.systemUiVisibility or flag else decor.systemUiVisibility and flag.inv()
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
