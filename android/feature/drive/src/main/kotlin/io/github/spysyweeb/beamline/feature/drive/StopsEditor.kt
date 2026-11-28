package io.github.spysyweeb.beamline.feature.drive

import androidx.activity.compose.BackHandler
import io.github.spysyweeb.beamline.ui.theme.rememberSheetState
import io.github.spysyweeb.beamline.ui.theme.SheetSteps
import io.github.spysyweeb.beamline.ui.theme.SheetHeight
import io.github.spysyweeb.beamline.ui.theme.SheetFrame
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.spysyweeb.beamline.api.nav.NavIntent
import io.github.spysyweeb.beamline.ui.theme.BeamlineIcons

/**
 * The stops editor (rules C1, C6): each stop with up, down and remove; reached stops greyed and
 * fixed; "Add stop" opens search (ADD_STOP, the keyboard up, P26) and "Best order"
 * reorders the stops not reached. Every change goes to the session as it is.
 *
 * Item 47: a sheet of two heights on the sheet frame, opened full. Lowered (a drag down, or a tap on
 * its handle at full height) it keeps its title with the number of stops, Done and its buttons, and
 * the map shows above it; it never closes by a drag or a tap on the map, only by Done or Back.
 *
 * Play test P49: it takes the route sheet's (or, guided, the trip bar's) place, so it has its own note line
 * under its buttons ([StopsEditorUi.note]: "Stops reordered.", the trip's problem, or the app's "Stop removed"
 * with Undo, P10) and, guided at full height, the trip's arrival under its title ([StopsEditorUi.arriveText]).
 * Done does not move under the finger: at each height everything under the header keeps the tallest height it
 * had since the editor reached that height, and the note line's room is always kept at full height and, lowered,
 * while Best order is offered (its note comes under the button just tapped).
 */
@Composable
internal fun StopsEditor(editor: StopsEditorUi, onEvent: (DriveEvent) -> Unit, modifier: Modifier = Modifier) {
    val nav = { i: NavIntent -> onEvent(DriveEvent.Nav(i)) }
    val height = rememberSheetState(SheetSteps.TWO, SheetHeight.FULL)
    BackHandler { onEvent(DriveEvent.CloseStopsEditor) } // above the route sheet's own Back (audit F31)
    val full = height.height == SheetHeight.FULL
    // play test P49: a removed stop, or a note that goes, does not pull Done and the buttons down under the finger: at
    // each height everything under the header keeps the tallest height it had since the editor reached that height
    // (rows at the top; buttons and note at the bottom, room between). A new height, window height or text size
    // starts afresh. (Review, 0.1.40: lowered, the note line came and went, and Done with it, by about 36 dp.)
    val density = LocalDensity.current
    val screenHeightDp = LocalConfiguration.current.screenHeightDp
    var bodyTallestPx by remember(full, screenHeightDp, density.fontScale) { mutableIntStateOf(0) }
    // The 0.1.40 replay (on its side under the banner): the rows were cut by the room the editor has, not by their own
    // ROWS_MAX_HEIGHT. The editor is then as tall as it may be, so a note that comes cannot move Done (the rows give it
    // the room and scroll): the empty note line's room goes to the rows (it had cut the second stop in half).
    val rowsScroll = rememberScrollState()
    var rowsPx by remember { mutableIntStateOf(0) }
    // (one state per height, window height and text size; the watcher restarts with it, so after a turn it sets the new one)
    val tightState = remember(full, screenHeightDp, density.fontScale) { mutableStateOf(false) }
    val tight = tightState.value
    val rowsCapPx = with(density) { ROWS_MAX_HEIGHT.toPx() }
    LaunchedEffect(tightState, rowsScroll, rowsCapPx) {
        snapshotFlow { full && rowsScroll.maxValue > 0 && rowsPx > 0 && rowsPx < rowsCapPx - 1f }.collect { if (it) tightState.value = true }
    }
    // the note line's room: at full height (unless the rows are short of room); lowered while Best order is offered;
    // always while a note shows
    val noteRoom = editor.note != null || editor.canUndo || (!tight && (full || editor.canOptimize))
    SheetFrame(
        height, DriveTexts.STOPS, MaterialTheme.colorScheme.onSurfaceVariant,
        modifier.fillMaxWidth().testTag(DriveTags.STOPS_EDITOR),
        header = {
            Box(Modifier.fillMaxWidth().height(4.dp)) {
                if (editor.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag(DriveTags.BUSY))
            }
            Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(DriveTexts.STOPS, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                    // lowered: the number of stops; guided at full height: the trip's arrival (the trip bar is not in view)
                    val second = if (!full) DriveTexts.stopsCount(editor.rows.count { !it.reached }) else editor.arriveText
                    second?.let {
                        Text(
                            it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = if (full) Modifier.testTag(DriveTags.STOPS_ARRIVE) else Modifier,
                        )
                    }
                }
                TextButton(onClick = { onEvent(DriveEvent.CloseStopsEditor) }, modifier = Modifier.testTag(DriveTags.CLOSE_STOPS)) { Text(DriveTexts.DONE) }
            }
        },
    ) {
        Column(
            Modifier
                .weight(1f, fill = false)
                .heightIn(min = with(density) { bodyTallestPx.toDp() })
                .onSizeChanged { if (it.height > bodyTallestPx) bodyTallestPx = it.height },
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            // The rows scroll and give up room first, so the header and the buttons stay in view on a short screen.
            if (full) Box(Modifier.weight(1f, fill = false).heightIn(max = ROWS_MAX_HEIGHT).onSizeChanged { rowsPx = it.height }) {
                Column(Modifier.verticalScroll(rowsScroll).padding(start = 12.dp, end = 4.dp)) {
                    editor.rows.forEachIndexed { i, row ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 44.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        StopEditorRow(row, nav)
                    }
                }
                // more rows below (the route sheet's P52 cue): a fade over the last one while they scroll further; no
                // pointer input and no label (drags and taps reach the row under it, a screen reader skips it)
                if (rowsScroll.canScrollForward) Box(
                    Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(MORE_BELOW_FADE)
                        .background(Brush.verticalGradient(listOf(Color.Transparent, MaterialTheme.colorScheme.surface)))
                        .testTag(DriveTags.STOPS_MORE_BELOW),
                )
            }
            Column {
                Row(
                    Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = if (noteRoom) 0.dp else 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    SecondaryButton(onClick = { onEvent(DriveEvent.SearchForStop) }, modifier = Modifier.testTag(DriveTags.ADD_STOP)) {
                        Icon(BeamlineIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(DriveTexts.ADD_STOP)
                    }
                    if (editor.canOptimize) {
                        SecondaryButton(onClick = { nav(NavIntent.OptimizeStopOrder) }, enabled = !editor.busy, modifier = Modifier.testTag(DriveTags.BEST_ORDER)) {
                            Text(DriveTexts.BEST_ORDER)
                        }
                    }
                }
                // play test P49, P10: what Best order did, the trip's problem, or "Stop removed" with Undo. At full height its
                // room is always kept (48 dp: the Undo button's height, or two lines of note at font 1.0), and lowered while
                // Best order is offered, so a note or an Undo that comes or goes does not move Done.
                if (noteRoom) Row(
                    Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp, bottom = 4.dp).heightIn(min = 48.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        editor.note ?: "", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).semantics { liveRegion = LiveRegionMode.Polite }.testTag(DriveTags.STOPS_NOTE),
                    )
                    if (editor.canUndo) {
                        TextButton(onClick = { onEvent(DriveEvent.UndoStopRemoval) }, modifier = Modifier.testTag(DriveTags.STOPS_UNDO)) { Text(DriveTexts.UNDO) }
                    }
                }
            }
        }
    }
}

/** The most the stop rows take at full height; more rows scroll. */
private val ROWS_MAX_HEIGHT = 440.dp

@Composable
private fun StopEditorRow(row: StopRowUi, nav: (NavIntent) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 4.dp).testTag(DriveTags.stopRow(row.index)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        StopNumber(row.index + 1, row.reached)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f).alpha(if (row.reached) 0.55f else 1f)) {
            Text(row.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val second = if (row.reached) DriveTexts.REACHED else row.subtitle
            second?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
        if (!row.reached) {
            IconButton(onClick = { nav(NavIntent.MoveStop(row.index, row.index - 1)) }, enabled = row.canMoveUp) {
                Icon(DriveIcons.up, contentDescription = DriveTexts.MOVE_UP + ": " + row.title)
            }
            IconButton(onClick = { nav(NavIntent.MoveStop(row.index, row.index + 1)) }, enabled = row.canMoveDown) {
                Icon(DriveIcons.down, contentDescription = DriveTexts.MOVE_DOWN + ": " + row.title)
            }
            // audit F30: Remove kept apart from the arrows
            Spacer(Modifier.width(8.dp))
            IconButton(onClick = { nav(NavIntent.RemoveStop(row.index)) }, enabled = row.canRemove) {
                Icon(DriveIcons.close, contentDescription = DriveTexts.REMOVE + ": " + row.title)
            }
        }
    }
}
