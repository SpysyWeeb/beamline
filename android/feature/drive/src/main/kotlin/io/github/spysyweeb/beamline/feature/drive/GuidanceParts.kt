package io.github.spysyweeb.beamline.feature.drive

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.LaunchedEffect
import io.github.spysyweeb.beamline.ui.theme.rememberSheetState
import io.github.spysyweeb.beamline.ui.theme.LocalSheetLag
import io.github.spysyweeb.beamline.ui.theme.SHEET_STEP
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.ScrollableDefaults
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.stopScroll
import androidx.compose.foundation.layout.offset
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.scrollBy
import androidx.compose.ui.unit.IntOffset
import kotlinx.coroutines.launch
import io.github.spysyweeb.beamline.ui.theme.SheetSteps
import io.github.spysyweeb.beamline.ui.theme.SheetHeight
import io.github.spysyweeb.beamline.ui.theme.SheetFrame
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.spysyweeb.beamline.api.nav.NavIntent
import io.github.spysyweeb.beamline.ui.theme.BeamlineIcons

/** Status colours: amber for a weak or lost signal and for problems, the theme's surface otherwise. */
private val Amber = Color(0xFFF2A93B)
private val AmberInk = Color(0xFF2B1D05)

/**
 * The status in place of the banner while re-routing (rule D7): "Finding a new route..." with a
 * spinner, or the failure text with Retry.
 */
@Composable
internal fun StatusCard(status: GuidanceStatusUi, onEvent: (DriveEvent) -> Unit, modifier: Modifier = Modifier) {
    val palette = if (isNightTheme()) BannerPalette.Night else BannerPalette.Day
    Row(
        modifier
            .fillMaxWidth()
            .background(palette.background)
            .padding(horizontal = 16.dp, vertical = 16.dp)
            .semantics { liveRegion = LiveRegionMode.Polite }
            .testTag(DriveTags.STATUS_CARD),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        when (status) {
            is GuidanceStatusUi.RerouteFailed -> {
                Icon(DriveIcons.warning, contentDescription = null, tint = Amber, modifier = Modifier.size(32.dp))
                Text(status.text, color = palette.ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
                if (status.canRetry) {
                    Button(onClick = { onEvent(DriveEvent.Nav(NavIntent.Retry)) }) { Text(DriveTexts.RETRY) }
                }
            }
            else -> {
                CircularProgressIndicator(color = palette.lanePreferred, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
                Text(
                    Presentation.statusText(status) ?: DriveTexts.REROUTING,
                    color = palette.ink,
                    fontSize = 19.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** The small chip under the banner for the GPS states, and for a failed change while the banner stays (C3). */
@Composable
internal fun StatusChip(status: GuidanceStatusUi, onEvent: (DriveEvent) -> Unit, modifier: Modifier = Modifier) {
    val text = Presentation.statusText(status) ?: return
    val alert = status is GuidanceStatusUi.GpsLost || status is GuidanceStatusUi.RerouteFailed
    Surface(
        shape = RoundedCornerShape(50),
        color = if (alert) Amber else MaterialTheme.colorScheme.surface,
        contentColor = if (alert) AmberInk else MaterialTheme.colorScheme.onSurface,
        shadowElevation = 3.dp,
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite }.testTag(DriveTags.STATUS_CHIP),
    ) {
        Row(Modifier.padding(start = 14.dp, end = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            if (status is GuidanceStatusUi.WaitingForGps) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(vertical = 8.dp), maxLines = 2)
            if (status is GuidanceStatusUi.RerouteFailed && status.canRetry) {
                TextButton(onClick = { onEvent(DriveEvent.Nav(NavIntent.Retry)) }) { Text(DriveTexts.RETRY, color = AmberInk) }
            } else {
                Spacer(Modifier.width(8.dp))
            }
        }
    }
}

/** The card shown while a stop has just been reached (EventCards' time, rule A2). */
@Composable
internal fun StopReachedCard(status: GuidanceStatusUi.StopReached, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surface,
        shadowElevation = 4.dp,
        modifier = modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite }.testTag(DriveTags.STOP_CARD),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
            Box(Modifier.size(14.dp).background(MaterialTheme.colorScheme.primary, CircleShape))
            Column {
                Text(DriveTexts.STOP_REACHED, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(status.name, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * At the destination: its name and side, and Done (rule A3). Item 47: on the sheet frame; Save is a
 * link under Done; a drag up (or a tap on its handle) opens the place's own window, its details.
 * Play test P31, audit F34: once the place is saved ([GuidanceStatusUi.Arrived.saved]) the link reads
 * "Saved" with a check and cannot save twice; a screen reader hears the handle's raise as "Show place details".
 * 0.1.41 (docs/online-design.md 4.8): the app's one question ([GuidanceStatusUi.Arrived.question]) under the
 * place's lines. The lines and the question scroll; Done and Save are pinned at the card's foot, so Done stays in
 * view on its side and with large text and stays where it was when the question comes (also while the card grows).
 */
@Composable
internal fun ArrivedCard(status: GuidanceStatusUi.Arrived, onEvent: (DriveEvent) -> Unit, modifier: Modifier = Modifier) {
    val height = rememberSheetState(SheetSteps.TWO, SheetHeight.LOW, status.name)
    LaunchedEffect(height.height) {
        if (height.height == SheetHeight.FULL) {
            onEvent(DriveEvent.ShowDestination)
            height.set(SheetHeight.LOW)
        }
    }
    // The sheet frame raises the card first on every drag up, a screen reader's scroll too (both come as user input),
    // and the card's raise opens the details: so the scrolling part moves itself. A drag scrolls the part first; a drag
    // that could not scroll it (at its end, or nothing to scroll) moves the card as before (item 47).
    val scroll = rememberScrollState()
    val scope = rememberCoroutineScope()
    val fling = ScrollableDefaults.flingBehavior()
    val stepPx = with(LocalDensity.current) { SHEET_STEP.toPx() }
    val scrolled = remember { BooleanArray(1) } // this drag has scrolled the part
    val drag = rememberDraggableState { dy ->
        if (scroll.dispatchRawDelta(-dy) != 0f) scrolled[0] = true
        if (!scrolled[0]) height.drag(dy, stepPx)
    }
    // the card's remaining slide to its new height (LocalSheetLag, positive while it grows)
    val lag = LocalSheetLag.current
    SheetFrame(
        height, DriveTexts.ARRIVED, MaterialTheme.colorScheme.onSurfaceVariant, modifier.fillMaxWidth().testTag(DriveTags.ARRIVED_CARD),
        raiseLabel = DriveTexts.SHOW_DETAILS,
    ) {
        Column(
            Modifier
                .weight(1f, fill = false)
                .fillMaxWidth()
                // while the card slides (the question came or went) everything under the handle keeps to the card's
                // bottom edge, so Done and Save stay still; nothing is drawn over the handle meanwhile
                .drawWithContent { clipRect(top = 0f, bottom = size.height + 100_000f) { this@drawWithContent.drawContent() } }
                .offset { IntOffset(0, -(lag?.intValue ?: 0)) }
                .semantics { liveRegion = LiveRegionMode.Polite },
        ) {
            // audit F34, AP-3: a long name, landscape, large text or a long question scroll here, never cutting Done off
            Column(
                Modifier
                    .weight(1f, fill = false)
                    .fillMaxWidth()
                    .draggable(
                        drag, Orientation.Vertical,
                        onDragStarted = {
                            scrolled[0] = false
                            scroll.stopScroll()
                        },
                        onDragStopped = { velocity ->
                            if (!height.endDrag() && scrolled[0]) scroll.scroll { with(fling) { performFling(-velocity) } }
                            scrolled[0] = false
                        },
                    )
                    .verticalScroll(scroll, enabled = false)
                    .semantics {
                        scrollBy { _, dy ->
                            scope.launch { scroll.animateScrollBy(dy) }
                            true
                        }
                    }
                    .padding(start = 20.dp, end = 20.dp),
            ) {
                Text(DriveTexts.ARRIVED, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                Text(status.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                status.sideText?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                status.awayText?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag(DriveTags.DESTINATION_AWAY)) }
                status.question?.let { ArrivalQuestion(it, onEvent) }
            }
            Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 12.dp)) {
                Button(onClick = { onEvent(DriveEvent.Nav(NavIntent.Done)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(DriveTags.DONE)) {
                    Text(DriveTexts.DONE)
                }
                if (status.saved) {
                    // play test P31, audit F34: saved once, said so; no second save (the column is a polite live region)
                    Row(
                        Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp)
                            .semantics(mergeDescendants = true) {}.testTag(DriveTags.DESTINATION_SAVED),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(BeamlineIcons.Check, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(DriveTexts.SAVED, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                } else {
                    TextButton(onClick = { onEvent(DriveEvent.SaveDestination) }, modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp).testTag(DriveTags.SAVE_DESTINATION)) {
                        Text(DriveTexts.SAVE_PLACE)
                    }
                }
            }
        }
    }
}

/**
 * 0.1.41 (docs/online-design.md 4.8): the arrival's one question on a tonal panel: the question, the lines under it
 * (the hours it asks about; never capped, the question is its own summary), then Yes (when there is one), the edit
 * button and Not now, each 48 dp high, wrapping to a second row when they do not fit. Read by the card's polite live
 * region.
 */
@Composable
private fun ArrivalQuestion(q: ArrivalQuestionUi, onEvent: (DriveEvent) -> Unit) {
    Surface(
        shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp).testTag(DriveTags.ARRIVAL_QUESTION),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(q.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(end = 8.dp))
            q.lines.forEach { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 8.dp)) {
                val yes = q.yes
                val edit = { onEvent(DriveEvent.AnswerArrival(ArrivalAnswer.EDIT)) }
                if (yes != null) {
                    FilledTonalButton(onClick = { onEvent(DriveEvent.AnswerArrival(ArrivalAnswer.YES)) }, modifier = Modifier.heightIn(min = 48.dp).testTag(DriveTags.ARRIVAL_YES)) { Text(yes) }
                    OutlinedButton(onClick = edit, modifier = Modifier.heightIn(min = 48.dp).testTag(DriveTags.ARRIVAL_EDIT)) { Text(q.edit) }
                } else {
                    // nothing to confirm: the edit button is the answer
                    FilledTonalButton(onClick = edit, modifier = Modifier.heightIn(min = 48.dp).testTag(DriveTags.ARRIVAL_EDIT)) { Text(q.edit) }
                }
                TextButton(onClick = { onEvent(DriveEvent.AnswerArrival(ArrivalAnswer.NOT_NOW)) }, modifier = Modifier.heightIn(min = 48.dp).testTag(DriveTags.ARRIVAL_NOT_NOW)) { Text(q.notNow) }
            }
        }
    }
}

/**
 * Rule D3: arrival clock, remaining time and distance, the next stop, the "Simulated" badge; stops and End.
 * Play test P5: with no route from the car (outside the installed maps) "No route from here" in place of the
 * old route's arrival and remaining time and distance.
 */
@Composable
internal fun TripBar(bar: TripBarUi, onEvent: (DriveEvent) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp).testTag(DriveTags.TRIP_BAR),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            val noRoute = bar.noRouteText
            if (noRoute != null) {
                // play test P5: the old route's times are not the trip's
                Text(noRoute, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.testTag(DriveTags.NO_ROUTE))
                if (bar.simulated) SimulatedBadge()
            } else {
                Text(bar.arriveText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                // The badge goes to the next line when the remaining time and distance need the room.
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${bar.remainingTimeText}${DriveTexts.LABEL_SEPARATOR}${bar.remainingDistanceText}",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (bar.simulated) SimulatedBadge()
                }
            }
            bar.nextStopText?.let {
                Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        FilledTonalIconButton(
            onClick = { onEvent(DriveEvent.OpenStopsEditor) },
            colors = IconButtonDefaults.filledTonalIconButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            modifier = Modifier.testTag(DriveTags.OPEN_STOPS),
        ) {
            Icon(DriveIcons.stops, contentDescription = DriveTexts.STOPS)
        }
        Spacer(Modifier.width(8.dp))
        // End: the close cross in an outlined circle, named "End trip" for screen readers (room for the texts at large font sizes).
        OutlinedIconButton(
            onClick = { onEvent(DriveEvent.Nav(NavIntent.End)) },
            modifier = Modifier.testTag(DriveTags.END),
        ) {
            Icon(DriveIcons.close, contentDescription = DriveTexts.END_TRIP)
        }
    }
}

/** The trip bar's "Simulated" badge (a simulated drive, D4). */
@Composable
private fun SimulatedBadge() {
    Text(
        DriveTexts.SIMULATED,
        color = MaterialTheme.colorScheme.primary,
        fontSize = 12.sp,
        fontWeight = FontWeight.SemiBold,
        maxLines = 1,
        softWrap = false,
        modifier = Modifier
            .border(1.dp, MaterialTheme.colorScheme.primary, RoundedCornerShape(6.dp))
            .padding(horizontal = 6.dp, vertical = 1.dp)
            .testTag(DriveTags.SIMULATED),
    )
}

/** The map buttons while guided: Overview always, Recenter when the camera left the car (K4, K5). */
@Composable
internal fun MapButtons(showRecenter: Boolean, onEvent: (DriveEvent) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(12.dp), horizontalAlignment = Alignment.End) {
        SmallFloatingActionButton(
            onClick = { onEvent(DriveEvent.ShowOverview) },
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.testTag(DriveTags.OVERVIEW),
        ) { Icon(DriveIcons.overview, contentDescription = DriveTexts.OVERVIEW) }
        if (showRecenter) {
            Surface(
                onClick = { onEvent(DriveEvent.Recenter) },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                shadowElevation = 4.dp,
                modifier = Modifier.testTag(DriveTags.RECENTER),
            ) {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(DriveIcons.recenter, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(DriveTexts.RECENTER, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

/** Rule R3: "Continue to …?" in the visible app after a restart; Continue or Discard. Play test P27: a simulated drive says so. */
@Composable
internal fun ResumeCard(resume: ResumeUi, onEvent: (DriveEvent) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(20.dp).testTag(DriveTags.RESUME_CARD)) {
        Text(DriveTexts.continueTo(resume.destinationName, resume.simulated), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        DriveTexts.stopsLeft(resume.stopsLeft)?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.size(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            SecondaryButton(onClick = { onEvent(DriveEvent.Nav(NavIntent.DiscardResume)) }, modifier = Modifier.weight(1f).testTag(DriveTags.DISCARD)) {
                Text(DriveTexts.DISCARD)
            }
            Button(onClick = { onEvent(DriveEvent.Nav(NavIntent.Resume)) }, modifier = Modifier.weight(1f).testTag(DriveTags.CONTINUE)) {
                Text(DriveTexts.CONTINUE)
            }
        }
    }
}

/** Item 47's style rules: a secondary button on a sheet is grey-filled with dark text, never outlined. */
@Composable
internal fun SecondaryButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit) {
    Button(
        onClick = onClick, enabled = enabled, modifier = modifier,
        colors = androidx.compose.material3.ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant, contentColor = MaterialTheme.colorScheme.onSurface,
        ),
        content = content,
    )
}
