package io.github.spysyweeb.beamline.feature.drive

import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.activity.compose.BackHandler
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.relocation.BringIntoViewRequester
import androidx.compose.foundation.relocation.bringIntoViewRequester
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import io.github.spysyweeb.beamline.api.nav.NavIntent
import io.github.spysyweeb.beamline.api.nav.RouteOptions
import io.github.spysyweeb.beamline.api.nav.Simulation
import io.github.spysyweeb.beamline.ui.theme.BeamlineIcons
import io.github.spysyweeb.beamline.ui.theme.SheetFrame
import io.github.spysyweeb.beamline.ui.theme.SheetHeight
import io.github.spysyweeb.beamline.ui.theme.SheetSteps
import io.github.spysyweeb.beamline.ui.theme.rememberSheetState
import kotlin.math.roundToInt

/**
 * The route-choice sheet (PLANNING, CHOOSING): the start row, the stops, one card per distinct route,
 * the two option toggles, Start (off with its reason without precise location, D4) and, in the
 * overflow menu, "Simulate drive" (6.29), which stays available whenever a route is chosen.
 */
@Composable
internal fun RouteSheet(sheet: RouteSheetUi, onEvent: (DriveEvent) -> Unit, modifier: Modifier = Modifier, opensAt: SheetHeight = SheetHeight.MIDDLE) {
    // Rule D10 (three heights since item 47, 2026-11-26): kept while the sheet shows and across a turn
    // of the phone; a new trip's sheet opens at [opensAt]: the middle height (the routes and Start)
    // under the map, full height in the side panel. At most 560 dp tall (less on a short screen).
    val height = rememberSheetState(SheetSteps.THREE, opensAt)
    // audit F31: Back at full height steps down to the routes first (under the map); at the lower
    // heights it closes the route, with Undo (AppFlows.onBack)
    BackHandler(enabled = height.height == SheetHeight.FULL && opensAt != SheetHeight.FULL) { height.set(SheetHeight.MIDDLE) }
    SheetFrame(
        height, DriveTexts.ROUTE_CHOICE, MaterialTheme.colorScheme.onSurfaceVariant,
        modifier.fillMaxWidth().heightIn(max = 560.dp).testTag(DriveTags.ROUTE_SHEET),
    ) {
        Box(Modifier.fillMaxWidth().height(4.dp)) {
            if (sheet.busy) LinearProgressIndicator(Modifier.fillMaxWidth().testTag(DriveTags.BUSY))
        }
        when (height.height) {
            SheetHeight.FULL -> FullRouteSheet(sheet, onEvent) { step -> onEvent(DriveEvent.ShowStep(step.point)); height.set(SheetHeight.MIDDLE) }
            SheetHeight.MIDDLE -> LowRouteSheet(sheet, onEvent)
            SheetHeight.LOW -> PeekRouteSheet(sheet, onEvent) { height.set(SheetHeight.MIDDLE) }
        }
    }
}

/**
 * The lowest height (item 47, 2026-11-26; the critiques): when the trip arrives, where it goes with its
 * time and length, a chip with the number of routes (a tap: the middle height, to choose), and Start;
 * the map above shows the route.
 */
@Composable
private fun PeekRouteSheet(sheet: RouteSheetUi, onEvent: (DriveEvent) -> Unit, onRoutes: () -> Unit) {
    val chosen = sheet.cards.firstOrNull { it.selected } ?: sheet.cards.firstOrNull()
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            if (chosen != null) {
                Text(chosen.arriveText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val where = listOfNotNull(sheet.stops.lastOrNull()?.title, chosen.durationText, chosen.distanceText).joinToString(" · ")
                Text(where, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            } else {
                sheet.stops.lastOrNull()?.let { Text(it.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        if (sheet.cards.size > 1) {
            androidx.compose.material3.AssistChip(
                onClick = onRoutes,
                label = { Text(DriveTexts.routes(sheet.cards.size)) },
                modifier = Modifier.heightIn(min = 48.dp).testTag(ROUTES_CHIP_TAG),
            )
        }
    }
    NoticePart(sheet, onEvent)
    StartPart(sheet, onEvent)
}

/** Test tag of the peek's chip with the number of routes (item 47). */
internal const val ROUTES_CHIP_TAG = "routes-chip"

/**
 * The middle height (rule D10; the lowest one before item 47): one small card per route (its time and types; a tap chooses it, as
 * the full cards do), the problem when there is no route, and Start with its reason; the map above
 * shows the routes.
 */
@Composable
private fun LowRouteSheet(sheet: RouteSheetUi, onEvent: (DriveEvent) -> Unit) {
    // audit F3: where the route goes and Cancel at this height too (it is where a new trip opens)
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            listOfNotNull(DriveTexts.TO, sheet.stops.lastOrNull()?.title).joinToString(" "), style = MaterialTheme.typography.titleMedium,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f),
        )
        IconButton(onClick = { onEvent(DriveEvent.Nav(NavIntent.End)) }, modifier = Modifier.testTag(DriveTags.CANCEL)) {
            Icon(DriveIcons.close, contentDescription = DriveTexts.CANCEL_ROUTE)
        }
    }
    if (sheet.cards.isNotEmpty()) {
        Row(
            Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(start = 16.dp, end = 16.dp, top = 4.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            for (card in sheet.cards) LowRouteCard(card) { onEvent(DriveEvent.Nav(NavIntent.Choose(card.choiceId))) }
        }
    }
    NoticePart(sheet, onEvent)
    StartPart(sheet, onEvent)
}

/**
 * Audit F2, F3: below the full height, what stands in the way and what to do about it, above Start:
 * the problem with its Retry and "Choose start", else "Finding routes..." while there is no route
 * yet, else the chosen route's first warning ("No route avoids tolls..."). The full height has its
 * own rows for all of them.
 */
@Composable
private fun NoticePart(sheet: RouteSheetUi, onEvent: (DriveEvent) -> Unit) {
    val problem = sheet.problem
    val pad = Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 6.dp)
    when {
        problem != null -> Column(pad.testTag(DriveTags.PROBLEM).semantics { liveRegion = LiveRegionMode.Polite }) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(DriveIcons.warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(problem, style = MaterialTheme.typography.bodyMedium, maxLines = 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
            if (sheet.canRetry || sheet.canChooseStart) Row(Modifier.padding(start = 20.dp)) {
                if (sheet.canChooseStart) TextButton(onClick = { onEvent(DriveEvent.ChooseStart) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(DriveTexts.CHOOSE_START) }
                if (sheet.canRetry) TextButton(onClick = { onEvent(DriveEvent.Nav(NavIntent.Retry)) }, modifier = Modifier.heightIn(min = 48.dp)) { Text(DriveTexts.RETRY) }
            }
        }
        sheet.busy && sheet.cards.isEmpty() -> Text(
            DriveTexts.FINDING_ROUTES, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = pad.semantics { liveRegion = LiveRegionMode.Polite },
        )
        else -> sheet.warnings.firstOrNull()?.let { w ->
            Row(pad, verticalAlignment = Alignment.Top) {
                Icon(DriveIcons.warning, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(w, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** A route at the middle height: its time over its types (or how much slower than the fastest), outlined in blue and filled when chosen. */
@Composable
private fun LowRouteCard(card: RouteCardUi, onClick: () -> Unit) {
    OutlinedCard(
        onClick = onClick,
        border = if (card.selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        // item 47: the chosen card filled (a light tint of the one blue), the others plain
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (card.selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f).compositeOver(MaterialTheme.colorScheme.surface) else MaterialTheme.colorScheme.surface,
        ),
        modifier = Modifier.heightIn(min = 48.dp).semantics { selected = card.selected }.testTag(DriveTags.lowCard(card.choiceId)),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 6.dp)) {
            Text(card.durationText, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold, maxLines = 1)
            // item 47: its types, then how much slower than the fastest ("Simpler · +3 min")
            Row {
                Text(card.labels, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1)
                card.slowerText?.let {
                    Text(DriveTexts.LABEL_SEPARATOR + it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
                // audit F2: tolls cost money; said at this height too
                if (DriveTexts.NOTE_TOLLS in card.notes) {
                    Text(DriveTexts.LABEL_SEPARATOR + DriveTexts.NOTE_TOLLS, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                }
            }
        }
    }
}

/** Start with its reason, at the bottom of the sheet at every height. */
@Composable
private fun StartPart(sheet: RouteSheetUi, onEvent: (DriveEvent) -> Unit) {
    Column(Modifier.padding(start = 16.dp, end = 8.dp, bottom = 16.dp)) {
        Button(
            onClick = { onEvent(DriveEvent.Nav(NavIntent.Start())) },
            enabled = sheet.canStart,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp, end = 8.dp).height(52.dp).testTag(DriveTags.START),
        ) { Text(DriveTexts.START, style = MaterialTheme.typography.titleMedium) }
        sheet.startNote?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, end = 8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        sheet.startBlockedText?.let {
            // Section 4: Start is off "and the app offers to ask for precise location again": the
            // reason is the way to ask (a text button, at least 48 dp tall for the finger).
            TextButton(
                onClick = { onEvent(DriveEvent.AskPreciseLocation) },
                modifier = Modifier.fillMaxWidth().padding(top = 2.dp, end = 8.dp).heightIn(min = 48.dp).testTag(DriveTags.START_BLOCKED),
            ) {
                Text(it, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
            }
        }
    }
}

/**
 * The full height: the start row with the menu and Cancel stays at the top and Start with its reason
 * at the bottom, the part between scrolls, so they stay in view at any font size and with the phone
 * on its side.
 */
@Composable
private fun ColumnScope.FullRouteSheet(sheet: RouteSheetUi, onEvent: (DriveEvent) -> Unit, onStep: (StepUi) -> Unit = {}) {
    val nav = { i: NavIntent -> onEvent(DriveEvent.Nav(i)) }
    val chosen = sheet.cards.any { it.selected }
    // Start row, overflow menu, cancel.
    Row(Modifier.padding(start = 16.dp, end = 8.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(
            Modifier
                .weight(1f)
                .clickable(role = Role.Button) { onEvent(DriveEvent.ChooseStart) }
                .padding(vertical = 6.dp)
                .testTag(DriveTags.START_ROW),
        ) {
            Text(DriveTexts.FROM, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(sheet.fromText, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        // item 47: the trip the other way round (which way it goes back, and how long)
        IconButton(onClick = { nav(NavIntent.SwapEnds) }, enabled = !sheet.busy, modifier = Modifier.testTag(DriveTags.SWAP)) {
            Icon(BeamlineIcons.Swap, contentDescription = DriveTexts.SWAP)
        }
        OverflowMenu(simulateEnabled = chosen && !sheet.busy, onSimulate = { nav(NavIntent.Start(Simulation())) })
        IconButton(onClick = { nav(NavIntent.End) }, modifier = Modifier.testTag(DriveTags.CANCEL)) {
            Icon(DriveIcons.close, contentDescription = DriveTexts.CANCEL_ROUTE)
        }
    }
    // A line under the start row while the part below is scrolled.
    val scroll = rememberScrollState()
    HorizontalDivider(color = if (scroll.value > 0) MaterialTheme.colorScheme.outlineVariant else Color.Transparent)
    // Whenever the part comes to rest (after a drag or fling, after the selected card brought itself
    // into view, after its content changed), no stop line stays cut under the start row: with its
    // name scrolled away, a destination's second line ("Erie, Pennsylvania") read as the start's.
    val positions = remember { SheetPositions() }
    positions.selectedId = sheet.cards.firstOrNull { it.selected }?.choiceId
    val nameStyle = MaterialTheme.typography.titleMedium
    with(LocalDensity.current) {
        // A cut through empty space is no cut: the line's padding, and above the name's letters the
        // half of its line height its font does not fill (titleMedium: 16 sp letters in a 24 sp line).
        val leading = if (nameStyle.lineHeight.isSp && nameStyle.fontSize.isSp) {
            ((nameStyle.lineHeight.toPx() - nameStyle.fontSize.toPx()) / 2f).coerceAtLeast(0f).roundToInt()
        } else {
            0
        }
        positions.lineTopFreePx = STOP_LINE_PAD.roundToPx() + leading
        positions.lineBottomFreePx = STOP_LINE_PAD.roundToPx()
    }
    var layoutVersion by remember { mutableIntStateOf(0) }
    LaunchedEffect(scroll) {
        snapshotFlow { scroll.isScrollInProgress to layoutVersion }.collect { (moving, _) ->
            // Look again when handling it: an emission may be handled after another scroll began
            // (the selected card's bring-into-view); never move the part in the middle of a scroll.
            if (!moving && !scroll.isScrollInProgress) positions.restingScroll(scroll)?.let { scroll.animateScrollTo(it) }
        }
    }
    Column(
        Modifier
            .weight(1f, fill = false)
            .onSizeChanged { positions.viewportPx = it.height; layoutVersion++ }
            .verticalScroll(scroll)
            .onPlaced { positions.content = it }
            .onSizeChanged { layoutVersion++ }
            .padding(start = 16.dp, end = 8.dp),
    ) {
        // Stops: "To" and the destination, or the numbered stops.
        Column(Modifier.padding(end = 8.dp)) {
            Text(
                DriveTexts.TO,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.onPlaced { positions.toLabel = it },
            )
            for (row in sheet.stops) {
                StopLine(
                    row,
                    numbered = sheet.stops.size > 1,
                    modifier = Modifier.onPlaced { positions.lines[row.index] = it }.testTag(DriveTags.stopLine(row.index)),
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                // item 47: "Add a stop" as a row of the stops (a plus where a stop's number would be)
                Row(
                    Modifier.heightIn(min = 48.dp).clickable(role = Role.Button) { onEvent(DriveEvent.SearchForStop) }
                        .padding(end = 12.dp).testTag(DriveTags.ADD_STOP),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(BeamlineIcons.Add, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(DriveTexts.ADD_STOP, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.primary)
                }
                if (sheet.stops.size > 1) {
                    TextButton(onClick = { onEvent(DriveEvent.OpenStopsEditor) }, modifier = Modifier.testTag(DriveTags.OPEN_STOPS)) {
                        Text(DriveTexts.EDIT_STOPS)
                    }
                }
            }
        }
        // Cards.
        Column(Modifier.padding(end = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (card in sheet.cards) {
                RouteCard(card, Modifier.onPlaced { positions.cards[card.choiceId] = it }) { nav(NavIntent.Choose(card.choiceId)) }
            }
        }
        sheet.problem?.let { problem ->
            Row(Modifier.padding(top = 10.dp, end = 8.dp).testTag(DriveTags.PROBLEM), verticalAlignment = Alignment.CenterVertically) {
                Icon(DriveIcons.warning, contentDescription = null, tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
                Text(problem, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (sheet.canRetry) TextButton(onClick = { nav(NavIntent.Retry) }) { Text(DriveTexts.RETRY) }
            }
        }
        for (w in sheet.warnings) {
            Row(Modifier.padding(top = 8.dp, end = 8.dp), verticalAlignment = Alignment.Top) {
                Icon(DriveIcons.warning, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp))
                Text(w, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        // The two option toggles.
        Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(
                selected = sheet.avoidHighways,
                onClick = { nav(NavIntent.SetOptions(RouteOptions(avoidHighways = !sheet.avoidHighways, avoidTolls = sheet.avoidTolls))) },
                label = { Text(DriveTexts.AVOID_HIGHWAYS) },
                leadingIcon = onCheck(sheet.avoidHighways),
                colors = toggleColors(),
                modifier = Modifier.testTag(DriveTags.AVOID_HIGHWAYS),
            )
            FilterChip(
                selected = sheet.avoidTolls,
                onClick = { nav(NavIntent.SetOptions(RouteOptions(avoidHighways = sheet.avoidHighways, avoidTolls = !sheet.avoidTolls))) },
                label = { Text(DriveTexts.AVOID_TOLLS) },
                leadingIcon = onCheck(sheet.avoidTolls),
                colors = toggleColors(),
                modifier = Modifier.testTag(DriveTags.AVOID_TOLLS),
            )
        }
        if (sheet.steps.isNotEmpty()) StepsPreview(sheet.steps, onStep)
    }
    StartPart(sheet, onEvent)
}

/**
 * The steps preview (item 47, the owner, 2026-11-25: "The steps preview is good, we'll do that"): the
 * chosen route's turns in the banner's words, each with how far the road goes after it; the first
 * [STEPS_SHOWN], then "Show all N steps". A tap on a step looks at it on the map.
 */
@Composable
private fun StepsPreview(steps: List<StepUi>, onStep: (StepUi) -> Unit) {
    var all by androidx.compose.runtime.saveable.rememberSaveable(steps.size, steps.firstOrNull()?.text) { androidx.compose.runtime.mutableStateOf(false) }
    Text(
        DriveTexts.STEPS, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 14.dp, bottom = 2.dp),
    )
    val shown = if (all) steps else steps.take(STEPS_SHOWN)
    shown.forEachIndexed { i, step ->
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onStep(step) }.testTag(stepTag(i)),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(DriveIcons.maneuverSmall(step.kind), contentDescription = null, tint = MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(12.dp))
            Text(step.text, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            if (step.distanceText.isNotEmpty()) {
                Spacer(Modifier.width(8.dp))
                Text(step.distanceText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    if (!all && steps.size > STEPS_SHOWN) {
        TextButton(onClick = { all = true }, modifier = Modifier.heightIn(min = 48.dp).testTag(SHOW_ALL_STEPS_TAG)) {
            Text(DriveTexts.showAllSteps(steps.size))
        }
    }
}

/** How many steps the preview shows before "Show all N steps" (item 47). */
internal const val STEPS_SHOWN = 5

/** Test tags of the steps preview (item 47). */
internal fun stepTag(i: Int) = "step-$i"
internal const val SHOW_ALL_STEPS_TAG = "show-all-steps"

/** The option toggles: blue B when on (the theme has no secondary colour of its own). */
@Composable
private fun toggleColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary,
    selectedLeadingIconColor = MaterialTheme.colorScheme.onPrimary,
)

/** Item 47: an option chip that is on shows a check before its words (not by colour alone). */
private fun onCheck(on: Boolean): (@Composable () -> Unit)? =
    if (on) ({ Icon(BeamlineIcons.Check, contentDescription = null, modifier = Modifier.size(18.dp)) }) else null

@Composable
private fun OverflowMenu(simulateEnabled: Boolean, onSimulate: () -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }, modifier = Modifier.testTag(DriveTags.MORE)) {
            Icon(DriveIcons.more, contentDescription = DriveTexts.MORE_OPTIONS)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = MaterialTheme.colorScheme.surfaceContainerHigh) {
            DropdownMenuItem(
                text = { Text(DriveTexts.SIMULATE_DRIVE) },
                enabled = simulateEnabled,
                onClick = {
                    open = false
                    onSimulate()
                },
                modifier = Modifier.testTag(DriveTags.SIMULATE),
            )
        }
    }
}

/** Where the sheet's stop lines and cards were placed, read when the scrolling part comes to rest (not state: nothing is drawn from it). */
private class SheetPositions {
    var content: LayoutCoordinates? = null
    var viewportPx = 0
    var toLabel: LayoutCoordinates? = null
    val lines = HashMap<Int, LayoutCoordinates>()
    val cards = HashMap<Long, LayoutCoordinates>()
    var selectedId: Long? = null
    /** Empty px above a stop line's letters (its padding and the name's top leading) and below its text (padding). */
    var lineTopFreePx = 0
    var lineBottomFreePx = 0

    private fun span(c: LayoutCoordinates?, padTop: Int = 0, padBottom: Int = 0): Span? {
        val root = content ?: return null
        if (c == null || !c.isAttached || !root.isAttached) return null
        val y = root.localPositionOf(c, Offset.Zero).y.roundToInt()
        return Span(y, y + c.size.height, padTop, padBottom)
    }

    fun restingScroll(scroll: ScrollState): Int? {
        val spans = lines.entries.sortedBy { it.key }.mapNotNull { span(it.value, lineTopFreePx, lineBottomFreePx) }.toMutableList()
        val label = span(toLabel)
        // the "To" label belongs to the first line; it has no padding of its own
        if (spans.isNotEmpty() && label != null && label.top < spans[0].top) spans[0] = spans[0].copy(top = label.top, padTop = 0)
        return sheetRestingScroll(scroll.value, viewportPx, scroll.maxValue, spans, span(selectedId?.let { cards[it] }))
    }
}

/** A stop line's empty space above and below its text. */
private val STOP_LINE_PAD = 4.dp

/** One stop on the route sheet: a number when there are several, the name and its second line. */
@Composable
private fun StopLine(row: StopRowUi, numbered: Boolean, modifier: Modifier = Modifier) {
    Row(modifier.padding(vertical = STOP_LINE_PAD), verticalAlignment = Alignment.CenterVertically) {
        if (numbered) {
            StopNumber(row.index + 1, row.reached)
            Spacer(Modifier.width(10.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(row.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            row.subtitle?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}

/** The numbered dot of a stop (blue B, white numeral, as the stop pins; grey once reached). */
@Composable
internal fun StopNumber(number: Int, reached: Boolean) {
    Box(
        Modifier
            .size(24.dp)
            .background(if (reached) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.primary, CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        Text("$number", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimary, fontWeight = FontWeight.SemiBold)
    }
}

/**
 * One route: its types ("Fastest · Simpler"), time, distance, arrival, roads and notes; selected =
 * outlined in blue. The selected card scrolls itself into view (on a short screen the stops above
 * it would otherwise push it out of sight behind Start).
 */
@Composable
private fun RouteCard(card: RouteCardUi, modifier: Modifier, onClick: () -> Unit) {
    val requester = remember { BringIntoViewRequester() }
    LaunchedEffect(card.choiceId, card.selected) { if (card.selected) requester.bringIntoView() }
    OutlinedCard(
        onClick = onClick,
        border = if (card.selected) BorderStroke(2.dp, MaterialTheme.colorScheme.primary) else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        // item 47: the chosen card filled (a light tint of the one blue), the others plain
        colors = CardDefaults.outlinedCardColors(
            containerColor = if (card.selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.10f).compositeOver(MaterialTheme.colorScheme.surface) else MaterialTheme.colorScheme.surface,
        ),
        modifier = modifier
            .fillMaxWidth()
            .bringIntoViewRequester(requester)
            .semantics { selected = card.selected }
            .testTag(DriveTags.card(card.choiceId)),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(card.labels, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary, modifier = Modifier.weight(1f))
                for (note in card.notes) {
                    Text(
                        note,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .padding(start = 6.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp),
                    )
                }
            }
            Row(verticalAlignment = Alignment.Bottom) {
                Text(card.durationText, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.SemiBold)
                card.slowerText?.let {
                    Spacer(Modifier.width(8.dp))
                    Text(it, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
                }
                Spacer(Modifier.width(10.dp))
                Text(card.distanceText, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 2.dp))
                Spacer(Modifier.weight(1f))
                Text(card.arriveText, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
            }
            card.viaText?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
}
