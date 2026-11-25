package io.github.spysyweeb.beamline.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.spysyweeb.beamline.search.hours.OpenState
import io.github.spysyweeb.beamline.ui.theme.SheetFrame
import io.github.spysyweeb.beamline.ui.theme.SheetHeight
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

    fun keyOf(ui: ResultsUi): String = ui.query + "|" + ui.rows.size + "|" + (ui.rows.firstOrNull()?.key ?: "")
    fun heightFor(key: String): SheetHeight = if (key == this.key) height else SheetHeight.MIDDLE
    fun remember(key: String, h: SheetHeight) { this.key = key; height = h }
}

/** Rule D10: the list's highest heights, about 30 % (at least 150 dp) and 60 % of the screen. */
internal fun resultsListMaxDp(height: SheetHeight, screenHeightDp: Int): Float = when (height) {
    SheetHeight.LOW -> 0f
    SheetHeight.MIDDLE -> (screenHeightDp * 0.3f).coerceAtLeast(150f)
    SheetHeight.FULL -> screenHeightDp * 0.6f
}

/**
 * The results list at the bottom of the map (rules S11, D10): the handle and a header with the
 * query, the count and a close button, then the rows. Three heights: only the handle and the header
 * (the map free), about three rows, about 60 % of the screen; a drag on the handle, the header or the
 * rows (when they are scrolled to the top) moves it, a tap on the handle or the header raises it
 * (from the highest back to the lowest). Opaque (batch C2): the result pins never show through.
 */
@Composable
internal fun ResultsSheetContent(ui: ResultsUi, onEvent: (ResultsEvent) -> Unit, modifier: Modifier) {
    val night = isNight()
    val p = palette(night)
    val key = ResultsHeightMemory.keyOf(ui)
    val sheet = rememberSheetState(SheetSteps.THREE, ResultsHeightMemory.heightFor(key), key)
    LaunchedEffect(key, sheet.height) { ResultsHeightMemory.remember(key, sheet.height) }
    val screenH = LocalConfiguration.current.screenHeightDp
    Surface(
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp), color = p.panel.copy(alpha = 1f), contentColor = p.panelText, shadowElevation = 6.dp,
        modifier = modifier.fillMaxWidth().testTag(RESULTS_SHEET_TAG),
    ) {
        SheetFrame(
            sheet, Texts.RESULTS_LIST, p.subtle, Modifier.navigationBarsPadding(),
            header = {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 16.dp)) {
                    Column(Modifier.weight(1f)) {
                        Text(ui.query, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.semantics { heading() })
                        Text(Texts.resultsCount(ui.rows.size), color = p.subtle, fontSize = 13.sp)
                    }
                    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(48.dp).clickable { onEvent(ResultsEvent.Close) }
                        .semantics { contentDescription = Texts.CLOSE_RESULTS; role = Role.Button }) {
                        Text("×", color = p.subtle, fontSize = 24.sp, modifier = Modifier.clearAndSetSemantics {})
                    }
                }
            },
        ) {
            if (sheet.height != SheetHeight.LOW) {
                HorizontalDivider(color = p.divider)
                LazyColumn(Modifier.fillMaxWidth().heightIn(max = resultsListMaxDp(sheet.height, screenH).dp).testTag(RESULTS_ROWS_TAG),
                    verticalArrangement = Arrangement.Top) {
                    itemsIndexed(ui.rows, key = { _, r -> r.key }) { i, r ->
                        ResultRow(r, night) { onEvent(ResultsEvent.Select(i)) }
                    }
                }
            }
        }
    }
}

/** Test tags of the results list and its rows. */
internal const val RESULTS_SHEET_TAG = "results-sheet"
internal const val RESULTS_ROWS_TAG = "results-rows"

/** A result row with its hours status under the title (S10), used by the search screen and the results list. */
@Composable
internal fun ResultRow(r: ResultRowUi, night: Boolean, onClick: () -> Unit) {
    PlaceRow(r.iconName, r.title, r.subtitle, r.distanceText, r.note, night, onClick = onClick, hours = r.hours)
}
