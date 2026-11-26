package io.github.spysyweeb.beamline.feature.drive

import io.github.spysyweeb.beamline.ui.theme.SheetHeight
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
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
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
}

/**
 * When the overlay is wider than tall and at least this wide (a phone on its side), the banner and
 * the sheets go into one panel on the start side and the map keeps the rest of the width; otherwise
 * the banner is at the top and the sheets at the bottom, across the whole width.
 */
internal val SIDE_LAYOUT_MIN_WIDTH: Dp = 560.dp

/** Rule D8: the speed pair's gap above the trip bar (or the bottom inset on its side), level with the map buttons. */
internal val SPEED_CLUSTER_BOTTOM_GAP: Dp = 16.dp

/** Rule D8: the same gap while the map credit's one short line shows in that corner; the pair slides down when it fades. */
internal val SPEED_CLUSTER_CREDIT_GAP: Dp = 36.dp

/** Rule D8: the room the speed pair keeps under the top stack, and its height before it was first measured. */
internal val SPEED_CLUSTER_TOP_CLEARANCE: Dp = 8.dp
internal val SPEED_CLUSTER_HEIGHT_GUESS: Dp = 76.dp

/** Item 22: the room the current-road label leaves at the start for the speed pair (sign, gap, badge) and at the end for the map buttons. */
internal val CURRENT_ROAD_START_ROOM: Dp = 148.dp
internal val CURRENT_ROAD_END_ROOM: Dp = 76.dp

/** The side panel: half the width, at least 320 dp (the banner needs it) and at most 400 dp. */
internal fun sidePanelWidth(overlayWidth: Dp): Dp = (overlayWidth / 2).coerceIn(320.dp, 400.dp)

/**
 * The body of [DriveOverlay]. Upright: the banner (or the re-route card, D7) at the top under the
 * status bar, the GPS chip and the stop card under it, the route sheet, trip bar, arrival or resume
 * card at the bottom, the map buttons above it, the stops editor over everything; [onInsets] gets
 * the height of the top block and of the bottom block. On its side (see [SIDE_LAYOUT_MIN_WIDTH]): the
 * same blocks in a panel on the start side, the map buttons at the bottom end; [onInsets] gets the
 * panel's width as `start`. The chips, cards and buttons are left out of the insets, so the camera
 * does not jump when they come and go.
 */
@Composable
internal fun DriveOverlayContent(ui: DriveUi, onEvent: (DriveEvent) -> Unit, onInsets: (EdgeInsetsDp) -> Unit, modifier: Modifier = Modifier, creditShown: Boolean = false) {
    BoxWithConstraints(modifier.fillMaxSize().testTag(DriveTags.OVERLAY)) {
        val side = maxWidth > maxHeight && maxWidth >= SIDE_LAYOUT_MIN_WIDTH
        val panelWidth = if (side) sidePanelWidth(maxWidth) else maxWidth
        val density = LocalDensity.current
        var topPx by remember { mutableIntStateOf(0) }
        var bottomPx by remember { mutableIntStateOf(0) }
        // D8 (batch B): the bottom of the chip / stop card column and the speed pair's height, to
        // know whether the pair has room between the top stack and the bottom block
        var chipBottomPx by remember { mutableIntStateOf(0) }
        var clusterPx by remember { mutableIntStateOf(0) }
        val insets = when {
            !side -> EdgeInsetsDp(top = topPx / density.density, bottom = bottomPx / density.density)
            topPx > 0 || bottomPx > 0 -> EdgeInsetsDp(start = panelWidth.value)
            else -> EdgeInsetsDp()
        }
        val reportInsets by rememberUpdatedState(onInsets)
        LaunchedEffect(insets) { reportInsets(insets) }

        // The system bars and a display cutout: the banner's colour and the sheets reach under them, their content does not.
        val safe = WindowInsets.systemBars.union(WindowInsets.displayCutout)
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
        if (guidance != null && !arrived) {
            val chip = status != null && guidance.banner != null && status !is GuidanceStatusUi.OnRoute && status !is GuidanceStatusUi.StopReached
            val chipNoBanner = status != null && guidance.banner == null &&
                (status is GuidanceStatusUi.WaitingForGps || status is GuidanceStatusUi.GpsWeak || status is GuidanceStatusUi.GpsLost)
            val chipShown = chip || chipNoBanner || status is GuidanceStatusUi.StopReached
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
                    if (status is GuidanceStatusUi.StopReached) StopReachedCard(status) else StatusChip(status, onEvent)
                }
            }
            // D8: the speed-limit sign and the current speed at the bottom start of the map (the owner,
            // 2026-11-18): above the trip bar upright, beside the side panel on its side, level with
            // the map buttons at the bottom end; higher while the map credit's short line shows in that
            // corner (the first seconds of guiding), sliding down when it fades.
            val speedGap by animateDpAsState(if (creditShown) SPEED_CLUSTER_CREDIT_GAP else SPEED_CLUSTER_BOTTOM_GAP, tween(500), label = "speedGap")
            // In a window too short for it (split screen, about 390 dp tall) the pair is left out while
            // it would reach the top stack (banner, chip, stop card); it comes back when there is room.
            // On its side the pair is beside the panel, clear of the stack.
            val room = side || with(density) {
                val topStack = maxOf(topPx, if (chipShown) chipBottomPx else 0)
                val cluster = if (clusterPx > 0) clusterPx else (SPEED_CLUSTER_HEIGHT_GUESS * fontScale).roundToPx()
                maxHeight.toPx() - bottomPx - speedGap.toPx() - cluster >= topStack + SPEED_CLUSTER_TOP_CLEARANCE.toPx()
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
                                Modifier.padding(bottom = with(density) { bottomPx.toDp() })
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
        if (guidance != null && !arrived) {
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
                                    Modifier.windowInsetsPadding(safe.only(WindowInsetsSides.Bottom)).padding(start = panelWidth)
                                } else {
                                    Modifier.padding(bottom = with(density) { bottomPx.toDp() })
                                },
                            )
                            .padding(start = CURRENT_ROAD_START_ROOM, end = CURRENT_ROAD_END_ROOM, top = SPEED_CLUSTER_BOTTOM_GAP, bottom = SPEED_CLUSTER_BOTTOM_GAP),
                    )
                }
            }
        }
        // Bottom block: measured for the camera; never taller than the room under the status bar.
        Column(
            Modifier
                .align(if (side) Alignment.BottomStart else Alignment.BottomCenter)
                .width(panelWidth)
                .heightIn(max = (maxHeight - safeTop - 8.dp).coerceAtLeast(0.dp))
                .onSizeChanged { bottomPx = it.height },
            horizontalAlignment = panelAlign,
        ) {
            val sheetInsets = safe.only(WindowInsetsSides.Bottom + horizontal)
            when {
                // item 47: the routes and Start under the map, everything in the side panel
                ui.planning != null -> BottomSheetSurface(sheetInsets) { RouteSheet(ui.planning, onEvent, opensAt = if (side) SheetHeight.FULL else SheetHeight.MIDDLE) }
                guidance != null && status is GuidanceStatusUi.Arrived -> BottomSheetSurface(sheetInsets) { ArrivedCard(status, onEvent) }
                guidance != null -> BottomSheetSurface(sheetInsets) { TripBar(guidance.tripBar, onEvent) }
                ui.resume != null -> BottomSheetSurface(sheetInsets) { ResumeCard(ui.resume, onEvent) }
            }
        }
        if (guidance != null && !arrived) {
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
        // The stops editor over everything; a tap beside it closes it.
        if (ui.stopsEditor != null) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.4f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onEvent(DriveEvent.CloseStopsEditor) }
                    .semantics { contentDescription = DriveTexts.CLOSE_STOPS_EDITOR },
            )
            Column(
                Modifier
                    .align(if (side) Alignment.BottomStart else Alignment.BottomCenter)
                    .width(panelWidth)
                    .heightIn(max = (maxHeight - safeTop - 8.dp).coerceAtLeast(0.dp)),
            ) {
                BottomSheetSurface(safe.only(WindowInsetsSides.Bottom + horizontal)) { StopsEditor(ui.stopsEditor, onEvent) }
            }
        }
    }
}

/** The bottom sheets' surface: rounded top, raised, reaching under the navigation bar (its content padded by [insets]). */
@Composable
private fun BottomSheetSurface(insets: WindowInsets, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
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
