package io.github.spysyweeb.beamline.feature.search

import androidx.compose.foundation.BorderStroke
import io.github.spysyweeb.beamline.ui.theme.PRIMARY_BUTTON_HEIGHT
import io.github.spysyweeb.beamline.ui.theme.SHEET_CORNER
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.spysyweeb.beamline.search.hours.OpenState
import io.github.spysyweeb.beamline.ui.theme.SheetFrame
import io.github.spysyweeb.beamline.ui.theme.SheetHeight
import io.github.spysyweeb.beamline.ui.theme.SheetState
import io.github.spysyweeb.beamline.ui.theme.SheetSteps
import io.github.spysyweeb.beamline.ui.theme.rememberSheetState

/** Rule S10: green, yellow, red; the words are always beside the dot (never the colour alone). */
internal fun hoursColor(state: OpenState, night: Boolean): Color = when (state) {
    OpenState.OPEN -> if (night) Color(0xFF5CC97B) else Color(0xFF2E9E4F)
    OpenState.CLOSING_SOON -> if (night) Color(0xFFFFD54F) else Color(0xFFF2C200)
    OpenState.CLOSED -> if (night) Color(0xFFFF6B60) else Color(0xFFD93025)
}

/** "Open now, until 9 PM" (the row's and the sheet's spoken words). */
internal fun hoursWords(h: HoursUi): String = listOfNotNull(h.label, h.next).joinToString(", ")

/** The status line: a coloured dot, the status words in bold and the next change. */
@Composable
internal fun HoursLine(h: HoursUi, night: Boolean, fontSize: Int = 14, modifier: Modifier = Modifier) {
    val p = palette(night)
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Box(Modifier.size(10.dp).background(hoursColor(h.state, night), CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(h.label, color = p.panelText, fontSize = fontSize.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, softWrap = false)
        if (h.next != null) Text(" · ${h.next}", color = p.subtle, fontSize = fontSize.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** The week, Monday to Sunday, today in bold (S10): the place sheet's details height (batch G). */
@Composable
internal fun HoursWeek(h: HoursUi, night: Boolean, modifier: Modifier = Modifier.padding(start = 16.dp, top = 4.dp)) {
    val p = palette(night)
    Column(modifier.testTag("hours-week")) {
        for (d in h.week) {
            Row(Modifier.semantics(mergeDescendants = true) {}) {
                val w = if (d.today) FontWeight.SemiBold else FontWeight.Normal
                Text(d.day, color = p.panelText, fontSize = 14.sp, fontWeight = w, modifier = Modifier.width(48.dp))
                Text(d.text, color = p.panelText, fontSize = 14.sp, fontWeight = w)
            }
        }
        if (h.holidayNote != null) Text(h.holidayNote, color = p.subtle, fontSize = 13.sp, modifier = Modifier.padding(top = 2.dp))
    }
}

/**
 * The results list's height, kept while a result's place sheet is open (the list leaves the screen
 * then) so that closing the sheet brings the list back at the height the driver left it (rule D10).
 * Only the latest results are remembered; new results start at the middle height.
 */
internal object ResultsHeightMemory {
    private var key: String? = null
    private var height = SheetHeight.MIDDLE

    /** Item 51: stop results keep their height while they are sorted; their card is a height of its own (it opens at the middle). */
    fun keyOf(ui: ResultsUi): String = ui.stop?.let { s -> "stop:" + s.requestId + if (cardOf(ui) != null) ":card" else "" }
        ?: (ui.query + "|" + ui.rows.size + "|" + (ui.rows.firstOrNull()?.key ?: ""))
    fun heightFor(key: String): SheetHeight = if (key == this.key) height else SheetHeight.MIDDLE
    fun remember(key: String, h: SheetHeight) { this.key = key; height = h }
}

/** Rule D10: the list's highest heights, about 30 % (at least 150 dp) and 60 % of the screen. */
internal fun resultsListMaxDp(height: SheetHeight, screenHeightDp: Int): Float = when (height) {
    SheetHeight.LOW -> 0f
    SheetHeight.MIDDLE -> (screenHeightDp * 0.3f).coerceAtLeast(150f)
    SheetHeight.FULL -> screenHeightDp * 0.6f
}

/** Item 51: the card of the selected stop result, null while the list shows. */
internal fun cardOf(ui: ResultsUi): StopPreviewUi? = ui.stop?.preview?.takeIf { ui.selected != null }

/**
 * The results list at the bottom of the map (rules S11, D10): the handle and a header with the
 * query, the count and a close button, then the rows. Three heights: only the handle and the header
 * (the map free), about three rows, about 60 % of the screen; a drag on the handle, the header or the
 * rows (when they are scrolled to the top) moves it, a tap on the handle or the header raises it
 * (from the highest back to the lowest). Opaque (batch C2): the result pins never show through.
 * Item 51 (rule S12): stop results say "Working out detours..." and show no rows until they are sorted
 * ("+4 min" on each row); a selected one shows its card (the time it adds, the arrival, "Add stop").
 * [insets] (P1, 0.1.40): what its content keeps clear of (a side navigation bar, a cutout); its surface reaches under them.
 */
@Composable
internal fun ResultsSheetContent(ui: ResultsUi, onEvent: (ResultsEvent) -> Unit, modifier: Modifier, insets: WindowInsets = sheetInsets()) {
    val night = isNight()
    val p = palette(night)
    val key = ResultsHeightMemory.keyOf(ui)
    val card = cardOf(ui)
    val sheet = rememberSheetState(SheetSteps.THREE, ResultsHeightMemory.heightFor(key), key)
    LaunchedEffect(key, sheet.height) { if (card == null) ResultsHeightMemory.remember(key, sheet.height) }
    val screenH = LocalConfiguration.current.screenHeightDp
    Surface(
        shape = RoundedCornerShape(topStart = SHEET_CORNER, topEnd = SHEET_CORNER), color = p.panel.copy(alpha = 1f), contentColor = p.panelText, shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth().testTag(RESULTS_SHEET_TAG),
    ) {
        if (card != null) {
            // review F5 (S12): a one-place result has no list behind its card (Back goes to the search, AppFlows.backFromStop)
            val instead = ui.stop?.instead == true
            StopCard(card, instead, listBehind = !instead && ui.places.size > 1, night, sheet, insets, onEvent)
            return@Surface
        }
        val stop = ui.stop
        SheetFrame(
            sheet, Texts.RESULTS_LIST, p.subtle, Modifier.windowInsetsPadding(insets),
            header = {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 16.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(ui.query, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { heading() })
                        if (stop == null) {
                            // P6 (e): where the list is ranked from when that is not the user's position ("20 places · Near Corry")
                            Text(listOfNotNull(Texts.resultsCount(ui.rows.size), ui.nearText).joinToString(" · "),
                                color = p.subtle, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        } else {
                            // S12: TalkBack says when the wait ends
                            Text(if (stop.working) Texts.WORKING_DETOURS else Texts.resultsCount(ui.rows.size), color = p.subtle, fontSize = 13.sp,
                                modifier = Modifier.testTag(STOP_STATUS_TAG).semantics { liveRegion = LiveRegionMode.Polite })
                            val where = if (stop.widened) Texts.ALONG_NONE_CLOSE else stop.after?.let(Texts::alongAfter)
                            if (where != null) Text(where, color = p.subtle, fontSize = 13.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                    CloseResultsButton(p) { onEvent(ResultsEvent.Close) }
                }
            },
        ) {
            if (stop?.working == true) {
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).testTag(STOP_WORKING_TAG))
            } else if (sheet.height != SheetHeight.LOW) {
                HorizontalDivider(color = p.divider)
                if (stop?.note != null) {
                    Text(stop.note, color = p.subtle, fontSize = 13.sp,
                        modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp).testTag(STOP_NOTE_TAG).semantics { liveRegion = LiveRegionMode.Polite })
                }
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = resultsListMaxDp(sheet.height, screenH).dp).testTag(RESULTS_ROWS_TAG),
                    verticalArrangement = Arrangement.Top) {
                    ui.rows.forEachIndexed { i, r ->
                        // P2: the name matches after the category's own, under their heading; never in stop results (sorted
                        // by minutes), and each heading its own key (review F1: two with one key threw)
                        if (stop == null && i > 0 && r.otherMatch && !ui.rows[i - 1].otherMatch) item(key = "$OTHER_MATCHES_TAG:$i") { OtherMatchesHeading(night) }
                        item(key = r.key) { ResultRow(r, night) { onEvent(ResultsEvent.Select(i)) } }
                    }
                }
            }
        }
    }
}

@Composable
private fun CloseResultsButton(p: Palette, onClick: () -> Unit) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp).clickable(onClick = onClick)
        .semantics { contentDescription = Texts.CLOSE_RESULTS; role = Role.Button }) {
        Text("×", color = p.subtle, fontSize = 24.sp, modifier = Modifier.clearAndSetSemantics {})
    }
}

/**
 * Item 51: a stop result looked at before it is added: back to the list ([listBehind]), its name (it takes the screen
 * reader's focus when the card opens), the window's X, "+4 min · Arrive 4:33 PM" (or "Working out the
 * route..." or why there is none), where it goes, and "Add stop", the only thing that changes the trip.
 * Item 52 ([instead]): a handed-over place's card also offers "Go here instead". No back arrow without a list
 * behind the card: a handed-over place, or one place (review F5); the X and the system's Back stay.
 */
@Composable
private fun StopCard(card: StopPreviewUi, instead: Boolean, listBehind: Boolean, night: Boolean, sheet: SheetState, insets: WindowInsets, onEvent: (ResultsEvent) -> Unit) {
    val p = palette(night)
    val titleFocus = remember { FocusRequester() }
    LaunchedEffect(card.title) { runCatching { titleFocus.requestFocus() } }
    SheetFrame(
        sheet, Texts.RESULTS_LIST, p.subtle, Modifier.windowInsetsPadding(insets).testTag(STOP_CARD_TAG),
        header = {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                // item 52, review F5: no list behind the card; the title starts where the list header's does
                if (listBehind) BackButton(night, label = Texts.BACK_TO_LIST) { onEvent(ResultsEvent.Deselect) } else Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(card.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.focusRequester(titleFocus).focusable().semantics { heading() })
                    if (card.subtitle != null) Text(card.subtitle, color = p.subtle, fontSize = 14.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
                CloseResultsButton(p) { onEvent(ResultsEvent.Close) }
            }
        },
    ) {
        Column(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, bottom = 12.dp)) {
            // review F6 (S12, "TalkBack: wait end"): one status line, a polite live region whose words change in place, so
            // TalkBack says when the route is known ("Adds 4 min, arrive 4:33 PM") or why there is none
            val time = listOfNotNull(card.addedText, card.arriveText).joinToString(" · ")
            val status = when {
                card.busy -> Texts.WORKING_ROUTE
                card.problem != null -> card.problem
                else -> time.takeIf { it.isNotEmpty() }
            }
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 4.dp)) {
                if (card.busy) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(8.dp))
                }
                if (status != null) {
                    val timeLine = !card.busy && card.problem == null
                    Text(status, color = when { card.busy -> p.subtle; card.problem != null -> p.problem; else -> p.panelText },
                        fontSize = if (timeLine) 16.sp else 15.sp, fontWeight = if (timeLine) FontWeight.Medium else FontWeight.Normal,
                        modifier = Modifier.testTag(STOP_TIME_TAG).then(
                            if (timeLine) Modifier.clearAndSetSemantics {
                                contentDescription = Texts.addedWords(card.addedText, card.arriveText); liveRegion = LiveRegionMode.Polite
                            } else Modifier.semantics { liveRegion = LiveRegionMode.Polite },
                        ))
                }
            }
            if (card.afterText != null) Text(card.afterText, color = p.subtle, fontSize = 14.sp, modifier = Modifier.padding(top = 2.dp))
            Spacer(Modifier.size(12.dp))
            WideButton(Texts.ADD_STOP, Texts.addStopLabel(card.title), night, enabled = card.canAdd) { onEvent(ResultsEvent.AddStop) }
            if (instead) {
                Spacer(Modifier.size(8.dp))
                WideButton(Texts.GO_INSTEAD, Texts.goInsteadLabel(card.title), night, enabled = true, primary = false, tag = STOP_INSTEAD_TAG) {
                    onEvent(ResultsEvent.GoInstead)
                }
            }
        }
    }
}

/**
 * Item 51: the card's primary button, as wide as the window; [label] is what a screen reader says. Item 52: [primary]
 * false is the outlined second button (the panel's colour, the accent's text and a 1 dp accent border).
 */
@Composable
private fun WideButton(text: String, label: String, night: Boolean, enabled: Boolean, primary: Boolean = true, tag: String = STOP_ADD_TAG, onClick: () -> Unit) {
    val p = palette(night)
    Surface(
        onClick = onClick, enabled = enabled, shape = RoundedCornerShape(22.dp),
        color = when { !enabled -> p.off; primary -> p.accent; else -> p.panel.copy(alpha = 1f) },
        contentColor = when { !enabled -> p.offText; primary -> p.onAccent; else -> p.accent },
        border = if (primary || !enabled) null else BorderStroke(1.dp, p.accent),
        modifier = Modifier.fillMaxWidth().heightIn(min = PRIMARY_BUTTON_HEIGHT).testTag(tag)
            .semantics { contentDescription = label; role = Role.Button },
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = PRIMARY_BUTTON_HEIGHT).padding(horizontal = 18.dp)) {
            Text(text, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, softWrap = false, modifier = Modifier.clearAndSetSemantics {})
        }
    }
}

/** P2 (0.1.40): before the first row that only matched by name ("Erie Animal Hospital" for "hospital"), in both lists. */
@Composable
internal fun OtherMatchesHeading(night: Boolean) {
    val p = palette(night)
    Column(Modifier.fillMaxWidth()) {
        HorizontalDivider(color = p.divider)
        Text(Texts.OTHER_MATCHES, color = p.subtle, fontSize = 13.sp, fontWeight = FontWeight.Medium,
            modifier = Modifier.padding(start = 16.dp, top = 8.dp, bottom = 4.dp).semantics { heading() })
    }
}

/** Test tags of the results list and its rows. */
internal const val RESULTS_SHEET_TAG = "results-sheet"
internal const val RESULTS_ROWS_TAG = "results-rows"
internal const val OTHER_MATCHES_TAG = "other-matches"
/** Item 51: the stop results' status line, progress, note, card, its status line (the time line once known; review F6) and its "Add stop". */
internal const val STOP_STATUS_TAG = "stop-status"
internal const val STOP_WORKING_TAG = "stop-working"
internal const val STOP_NOTE_TAG = "stop-note"
internal const val STOP_CARD_TAG = "stop-card"
internal const val STOP_TIME_TAG = "stop-time"
internal const val STOP_ADD_TAG = "stop-add"
/** Item 52: a handed-over place's "Go here instead". */
internal const val STOP_INSTEAD_TAG = "stop-instead"

/** A result row with its hours status under the title (S10), used by the search screen and the results list; item 51's "+4 min" first. */
@Composable
internal fun ResultRow(r: ResultRowUi, night: Boolean, onClick: () -> Unit) {
    PlaceRow(r.iconName, r.title, r.subtitle, r.distanceText, r.note, night, onClick = onClick, hours = r.hours, lead = r.detourText)
}
