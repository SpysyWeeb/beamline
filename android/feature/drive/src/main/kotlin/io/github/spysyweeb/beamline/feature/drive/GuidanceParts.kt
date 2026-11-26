package io.github.spysyweeb.beamline.feature.drive

import androidx.compose.foundation.layout.heightIn
import androidx.compose.runtime.LaunchedEffect
import io.github.spysyweeb.beamline.ui.theme.rememberSheetState
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
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
    SheetFrame(height, DriveTexts.ARRIVED, MaterialTheme.colorScheme.onSurfaceVariant, modifier.fillMaxWidth().testTag(DriveTags.ARRIVED_CARD)) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 12.dp).semantics { liveRegion = LiveRegionMode.Polite }) {
            Text(DriveTexts.ARRIVED, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(status.name, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
            status.sideText?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            status.awayText?.let { Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.testTag(DriveTags.DESTINATION_AWAY)) }
            Spacer(Modifier.size(16.dp))
            Button(onClick = { onEvent(DriveEvent.Nav(NavIntent.Done)) }, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag(DriveTags.DONE)) {
                Text(DriveTexts.DONE)
            }
            TextButton(onClick = { onEvent(DriveEvent.SaveDestination) }, modifier = Modifier.align(Alignment.CenterHorizontally).heightIn(min = 48.dp).testTag(DriveTags.SAVE_DESTINATION)) {
                Text(DriveTexts.SAVE_PLACE)
            }
        }
    }
}

/** Rule D3: arrival clock, remaining time and distance, the next stop, the "Simulated" badge; stops and End. */
@Composable
internal fun TripBar(bar: TripBarUi, onEvent: (DriveEvent) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 12.dp, bottom = 12.dp).testTag(DriveTags.TRIP_BAR),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
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
                if (bar.simulated) {
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

/** Rule R3: "Continue to …?" in the visible app after a restart; Continue or Discard. */
@Composable
internal fun ResumeCard(resume: ResumeUi, onEvent: (DriveEvent) -> Unit, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(20.dp).testTag(DriveTags.RESUME_CARD)) {
        Text(DriveTexts.continueTo(resume.destinationName), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        DriveTexts.stopsLeft(resume.stopsLeft)?.let {
            Text(it, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.size(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { onEvent(DriveEvent.Nav(NavIntent.DiscardResume)) }, modifier = Modifier.weight(1f).testTag(DriveTags.DISCARD)) {
                Text(DriveTexts.DISCARD)
            }
            Button(onClick = { onEvent(DriveEvent.Nav(NavIntent.Resume)) }, modifier = Modifier.weight(1f).testTag(DriveTags.CONTINUE)) {
                Text(DriveTexts.CONTINUE)
            }
        }
    }
}
