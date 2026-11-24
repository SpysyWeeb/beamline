package io.github.spysyweeb.beamline.feature.drive

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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.spysyweeb.beamline.api.nav.NavIntent
import io.github.spysyweeb.beamline.ui.theme.BeamlineIcons

/**
 * The stops editor (rules C1, C6): each stop with up, down and remove; reached stops greyed and
 * fixed; "Add stop" opens search (ADD_STOP; while driving without the keyboard, SP4) and "Best order"
 * reorders the stops not reached. Every change goes to the session as it is.
 */
@Composable
internal fun StopsEditor(editor: StopsEditorUi, onEvent: (DriveEvent) -> Unit, modifier: Modifier = Modifier) {
    val nav = { i: NavIntent -> onEvent(DriveEvent.Nav(i)) }
    Column(modifier.fillMaxWidth().testTag(DriveTags.STOPS_EDITOR)) {
        Box(Modifier.fillMaxWidth().height(4.dp)) {
            if (editor.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag(DriveTags.BUSY))
        }
        Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(DriveTexts.STOPS, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            TextButton(onClick = { onEvent(DriveEvent.CloseStopsEditor) }, modifier = Modifier.testTag(DriveTags.CLOSE_STOPS)) { Text(DriveTexts.DONE) }
        }
        // The rows scroll and give up room first, so the header and the buttons stay in view on a short screen.
        Column(Modifier.weight(1f, fill = false).heightIn(max = 440.dp).verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 4.dp)) {
            editor.rows.forEachIndexed { i, row ->
                if (i > 0) HorizontalDivider(Modifier.padding(start = 44.dp), color = MaterialTheme.colorScheme.outlineVariant)
                StopEditorRow(row, nav)
            }
        }
        Row(
            Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            OutlinedButton(onClick = { onEvent(DriveEvent.SearchForStop) }, modifier = Modifier.testTag(DriveTags.ADD_STOP)) {
                Icon(BeamlineIcons.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text(DriveTexts.ADD_STOP)
            }
            if (editor.canOptimize) {
                OutlinedButton(onClick = { nav(NavIntent.OptimizeStopOrder) }, enabled = !editor.busy, modifier = Modifier.testTag(DriveTags.BEST_ORDER)) {
                    Text(DriveTexts.BEST_ORDER)
                }
            }
        }
    }
}

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
                Icon(DriveIcons.up, contentDescription = DriveTexts.MOVE_UP)
            }
            IconButton(onClick = { nav(NavIntent.MoveStop(row.index, row.index + 1)) }, enabled = row.canMoveDown) {
                Icon(DriveIcons.down, contentDescription = DriveTexts.MOVE_DOWN)
            }
            IconButton(onClick = { nav(NavIntent.RemoveStop(row.index)) }, enabled = row.canRemove) {
                Icon(DriveIcons.close, contentDescription = DriveTexts.REMOVE)
            }
        }
    }
}
