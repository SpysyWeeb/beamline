// FILE: feature/search/src/main/kotlin/io/github/spysyweeb/beamline/feature/search/SearchScreens.kt
package io.github.spysyweeb.beamline.feature.search

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * The search pill on the map. While the results of a search show on the map (rule S11) it holds that
 * search's words ([query]) and a button that ends the results ([onClear]); a tap on the words opens
 * the search with them typed (the owner, 2026-11-21: the bar forgot what was searched for).
 */
@Composable
fun SearchBar(onClick: () -> Unit, modifier: Modifier = Modifier, query: String? = null, onClear: () -> Unit = {}): Unit =
    SearchPill(onClick, modifier, query, onClear)

@Composable
fun SearchScreen(ui: SearchUi, onEvent: (SearchEvent) -> Unit, modifier: Modifier = Modifier): Unit = SearchScreenContent(ui, onEvent, modifier)

@Composable
fun PlaceSheet(ui: PlaceSheetUi, onEvent: (PlaceSheetEvent) -> Unit, modifier: Modifier = Modifier): Unit = PlaceSheetContent(ui, onEvent, modifier)

/** The results on the map after Enter (rule S11): the list at the bottom, pulled up for more. */
@Composable
fun ResultsSheet(ui: ResultsUi, onEvent: (ResultsEvent) -> Unit, modifier: Modifier = Modifier): Unit = ResultsSheetContent(ui, onEvent, modifier)

/** The saved places screen (2.6). */
@Composable
fun SavedPlacesScreen(ui: SavedListUi, onEvent: (SavedEvent) -> Unit, modifier: Modifier = Modifier): Unit = SavedPlacesContent(ui, onEvent, modifier)

/** The Edit menu or a dialog of 2.2-2.6, over whatever is on screen. */
@Composable
fun SavedDialog(ui: SavedDialogUi, onEvent: (SavedEvent) -> Unit, modifier: Modifier = Modifier): Unit = SavedDialogContent(ui, onEvent, modifier)

/** The pick prompt at the top (in the search pill's place) and the bar at the bottom, over the map; the app keeps its my-location button above the bar. */
@Composable
fun MapPickBar(ui: MapPickUi, onEvent: (SavedEvent) -> Unit, modifier: Modifier = Modifier): Unit = MapPickContent(ui, onEvent, modifier)
