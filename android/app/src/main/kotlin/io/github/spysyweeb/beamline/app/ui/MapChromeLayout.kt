package io.github.spysyweeb.beamline.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.platform.testTag
import io.github.spysyweeb.beamline.ui.theme.SHEET_CORNER
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** P1 (0.1.40): what the map's own chrome keeps clear of on every side: the status and navigation bars and a display cutout. */
@Composable
internal fun mapSafeInsets(): WindowInsets = WindowInsets.systemBars.union(WindowInsets.displayCutout)

/**
 * The top of the map (S8, SP2): [start] (the search pill, its chips and notices) and, at the end, [end]
 * (Settings, Map style), under the status bar and, P1 (0.1.40), clear of a navigation bar or a display
 * cutout on either side ([safe]): with 3-button navigation on its side Settings and Map style sat under
 * the system buttons and a tap opened Recents. [onHeight]: the block's height with the status bar's band
 * (the camera's top chrome while results show, S11). P7: [onStartBottom] and [onEndBottom], where each
 * column ends, from the top of the window (the root): on its side only the start column is over the
 * place window's panel, and my-location meets only the end column.
 */
@Composable
internal fun MapTopChrome(
    safe: WindowInsets,
    onHeight: (Dp) -> Unit,
    modifier: Modifier = Modifier,
    onStartBottom: (Dp) -> Unit = {},
    onEndBottom: (Dp) -> Unit = {},
    start: @Composable ColumnScope.() -> Unit,
    end: @Composable ColumnScope.() -> Unit,
) {
    val density = LocalDensity.current
    Column(
        modifier
            .onSizeChanged { onHeight(with(density) { it.height.toDp() }) }
            .windowInsetsPadding(safe.only(WindowInsetsSides.Top + WindowInsetsSides.Horizontal))
            .padding(8.dp)
            .fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.Top) {
            // unclipped: position plus size (boundsInRoot would clip)
            Column(
                Modifier.weight(1f).onGloballyPositioned { c -> onStartBottom(with(density) { (c.positionInRoot().y + c.size.height).toDp() }) },
                verticalArrangement = Arrangement.spacedBy(8.dp), content = start,
            )
            Gap(8)
            Column(
                Modifier.onGloballyPositioned { c -> onEndBottom(with(density) { (c.positionInRoot().y + c.size.height).toDp() }) },
                verticalArrangement = Arrangement.spacedBy(10.dp), content = end,
            )
        }
    }
}

/**
 * P1 (0.1.40): a card at the top of the map (the map style's failure, with "States"): [top] from the
 * window's top (under the search pill's block while it shows, else under the status bar), clear of a
 * navigation bar or a display cutout on either side ([safe]). It had no inset at all.
 */
@Composable
internal fun MapTopCard(safe: WindowInsets, top: Dp, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.windowInsetsPadding(safe.only(WindowInsetsSides.Horizontal)).padding(start = 8.dp, end = 8.dp, top = top)) { content() }
}

/**
 * P7 (0.1.40): where a snackbar sits (with `align(BottomCenter)`): centred in the map beside a side panel
 * [start] wide (0 upright), [bottom] above the navigation bar. On its side they lay across the whole
 * width, half over the route sheet, the trip bar or the place window. A long line keeps
 * [SNACKBAR_MARGIN] from the map's sides, as the search screen's Undo bar does.
 */
internal fun Modifier.snackbarPlace(start: Dp, bottom: Dp): Modifier =
    this.padding(start = start).navigationBarsPadding().padding(start = SNACKBAR_MARGIN, end = SNACKBAR_MARGIN, bottom = bottom)

internal val SNACKBAR_MARGIN = 12.dp

/**
 * The bottom of the map (5.4 item 4): [message] (a location message) over one row, [credit] at its start
 * and [location] (my-location; null while driving, or while a raised window would bring it to Settings
 * and Map style, P7) at its end. [bottom]: the room under the row (a sheet, the pick bar, the drive
 * overlay's block; 0: the bars' inset instead, at least 8 dp); [start]: the room at the start (a side
 * panel, which holds the start inset). P1 (0.1.40): the row keeps clear of a navigation bar or a cutout
 * on either side ([safe]) whatever is under it: with a sheet or the pick bar open it had no side inset
 * and my-location sat under the system buttons; on its side with 3-button navigation it sat on the
 * bottom edge. [onRowHeight]: the row's height (the camera's room for it, K1).
 */
@Composable
internal fun MapBottomChrome(
    safe: WindowInsets,
    start: Dp,
    bottom: Dp,
    onRowHeight: (Dp) -> Unit,
    modifier: Modifier = Modifier,
    message: (@Composable () -> Unit)?,
    credit: @Composable RowScope.() -> Unit,
    location: (@Composable () -> Unit)?,
) {
    val density = LocalDensity.current
    val dir = LocalLayoutDirection.current
    val bars = safe.asPaddingValues()
    Column(
        modifier.fillMaxWidth().padding(
            start = maxOf(start, bars.calculateStartPadding(dir)),
            end = bars.calculateEndPadding(dir),
            bottom = if (bottom == 0.dp) maxOf(bars.calculateBottomPadding(), 8.dp) else bottom,
        ),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        message?.let { m -> Box(Modifier.padding(horizontal = 8.dp)) { m() } }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp).onSizeChanged { onRowHeight(with(density) { it.height.toDp() }) },
            verticalAlignment = Alignment.Bottom,
        ) {
            credit()
            location?.invoke()
        }
    }
}

/**
 * P7 (0.1.40): the place window, the results list or item 51's stop results. Upright at the bottom across
 * the width; on its side ([panel] wide, :ui:theme sidePanelWidth) in a panel at the bottom start, as the
 * drive overlay's route sheet, so it no longer spans the width under the map buttons. A panel at the start
 * has nothing at the end to keep clear of: the end's inset is consumed here, so the sheet's own padding
 * keeps the start and the bottom only. Never taller than [maxHeight] (AppRules.sheetRoomDp: under the top
 * chrome over it and the drive overlay's banner). [onHeight]: its height (the camera's insets upright, K1).
 */
@Composable
internal fun MapSheetBox(
    panel: Dp?,
    safe: WindowInsets,
    maxHeight: Dp,
    onHeight: (Dp) -> Unit,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val density = LocalDensity.current
    Box(
        modifier
            .then(if (panel != null) Modifier.width(panel).consumeWindowInsets(safe.only(WindowInsetsSides.End)) else Modifier.fillMaxWidth())
            .heightIn(max = maxHeight)
            .onSizeChanged { onHeight(with(density) { it.height.toDp() }) },
    ) { content() }
}

/**
 * Item 54b (rule S12): the Add stop search as a panel over the trip's map: on the keyboard ([keyboard], padded and
 * consumed, so the search's own imePadding adds nothing), [height] tall, across the width upright or [panel] wide at
 * the start on its side (the end's bar or cutout consumed, as MapSheetBox), the status bar consumed (the search's
 * statusBarsPadding adds nothing: the field is at the panel's top). The windows' top corners and shadow. Only the
 * panel takes touches: above or beside it the map gets them.
 */
@Composable
internal fun AddStopPanelBox(
    panel: Dp?, safe: WindowInsets, height: Dp, modifier: Modifier = Modifier,
    keyboard: WindowInsets = WindowInsets.ime, content: @Composable () -> Unit,
) {
    val shape = RoundedCornerShape(topStart = SHEET_CORNER, topEnd = SHEET_CORNER)
    Box(
        modifier
            .windowInsetsPadding(keyboard.only(WindowInsetsSides.Bottom))
            .then(if (panel != null) Modifier.width(panel).consumeWindowInsets(safe.only(WindowInsetsSides.End)) else Modifier.fillMaxWidth())
            .height(height)
            .shadow(8.dp, shape)
            .clip(shape)
            .consumeWindowInsets(WindowInsets.statusBars)
            .testTag(ADD_STOP_PANEL_TAG),
    ) { content() }
}

/** Item 54b: the Add stop panel's test tag. */
internal const val ADD_STOP_PANEL_TAG = "add-stop-panel"

/**
 * Item 54b (plan AF-1): the stop overlay's place over the Add stop panel's map (DriveOverlay with stopWindowDp): the whole
 * window; on its side with the keyboard up ([sideKeyboard]) it ends on the keyboard ([keyboardDp] high), so the speed
 * pair, which sits at the overlay's foot there (the panel is in the start panel), stays above the keyboard.
 */
internal fun stopOverlayModifier(sideKeyboard: Boolean, keyboardDp: Float): Modifier =
    if (sideKeyboard) Modifier.fillMaxSize().padding(bottom = keyboardDp.dp) else Modifier.fillMaxSize()
