package io.github.spysyweeb.beamline.feature.search

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Batch O3 (docs/next-round.md item 27): the scrolling grid of place types, each with its icon (the
 * map's and the result rows', day and night) and its name, under plain headings. One component, two
 * uses: the search's "View all" ([AllTypesScreen]) and the type picker of "Add a missing place" and of
 * "This is now a different business" (the app). Cells are at least 104 dp wide (two columns on the
 * narrowest phone, more in landscape) and at least 88 dp high; each is one button for a screen reader
 * ("Gas station").
 */
@Composable
fun PlaceTypeGrid(groups: List<PlaceTypeGroupUi>, onPick: (PlaceTypeUi) -> Unit, modifier: Modifier = Modifier, selected: String? = null) {
    val night = isNight()
    val p = palette(night)
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 104.dp), modifier = modifier.testTag(TYPE_GRID_TAG),
        horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 12.dp, end = 12.dp, bottom = 24.dp),
    ) {
        for (g in groups) {
            item(span = { GridItemSpan(maxLineSpan) }, key = "h:" + g.heading) {
                Text(g.heading, color = p.subtle, fontSize = 14.sp, fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(start = 4.dp, top = 14.dp, bottom = 2.dp).semantics { heading() })
            }
            items(g.types, key = { "t:" + g.heading + ":" + it.code }) { t ->
                val on = t.code == selected
                Surface(
                    onClick = { onPick(t) }, shape = RoundedCornerShape(12.dp), color = if (on) p.accent.copy(alpha = 0.16f) else p.panel,
                    contentColor = p.panelText, border = BorderStroke(if (on) 2.dp else 1.dp, if (on) p.accent else p.divider),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp).testTag("type-" + t.code)
                        .semantics(mergeDescendants = true) { contentDescription = t.label; role = Role.Button },
                ) {
                    Column(
                        Modifier.padding(horizontal = 6.dp, vertical = 10.dp).clearAndSetSemantics {},
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        SpriteIcon(t.iconName, night, 32.dp)
                        Text(t.label, fontSize = 14.sp, textAlign = TextAlign.Center, maxLines = 3, lineHeight = 17.sp)
                    }
                }
            }
        }
    }
}

/** The search's "View all": [PlaceTypeGrid] full screen with a Back button; a tap searches as a chip does. */
@Composable
internal fun AllTypesScreen(groups: List<PlaceTypeGroupUi>, onEvent: (SearchEvent) -> Unit, modifier: Modifier) {
    val night = isNight()
    val p = palette(night)
    // Back: the app sends SearchEvent.Close, which closes the grid first (SearchController.closeTapped)
    Column(modifier.fillMaxSize().background(MaterialTheme.colorScheme.background).statusBarsPadding().testTag(ALL_TYPES_TAG)) {
        Surface(color = p.panel, shadowElevation = 2.dp) {
            Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
                BackButton(night) { onEvent(SearchEvent.CloseViewAll) }
                Text(Texts.ALL_TYPES, color = p.panelText, fontSize = 19.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.semantics { heading() })
            }
        }
        PlaceTypeGrid(groups, onPick = { onEvent(SearchEvent.TypeTapped(it)) }, modifier = Modifier.weight(1f).fillMaxWidth().navigationBarsPadding())
        Spacer(Modifier.size(0.dp))
    }
}

internal const val TYPE_GRID_TAG = "type-grid"
internal const val ALL_TYPES_TAG = "all-types"
internal const val VIEW_ALL_TAG = "view-all"

/**
 * Batch O3 (docs/next-round.md item 24 c): the week of an OSM hours [text] in plain words, exactly as the
 * place sheet's details height shows it (Monday to Sunday, "8 AM–5 PM", "Closed", "Open 24 hours",
 * today in bold, "Holiday hours may differ"); [empty] when the text gives no week.
 */
@Composable
fun HoursPreview(text: String?, empty: String, modifier: Modifier = Modifier) {
    val night = isNight()
    val h = HoursTexts.ui(text, java.time.LocalDateTime.now(), withWeek = true)
    if (h == null || h.week.isEmpty()) {
        Text(empty, color = palette(night).panelText, fontSize = 14.sp, modifier = modifier)
    } else {
        HoursWeek(h, night, modifier)
    }
}

/** Batch O3: the place sheet's clock words for [minutes] after midnight ("3 PM", "9:30 AM"), US 12-hour as the sheet shows them. */
fun sheetClock(minutes: Int): String = HoursTexts.clock(minutes)
