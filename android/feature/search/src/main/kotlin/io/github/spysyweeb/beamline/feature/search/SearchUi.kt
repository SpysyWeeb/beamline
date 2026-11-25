// FILE: feature/search/src/main/kotlin/io/github/spysyweeb/beamline/feature/search/SearchUi.kt
package io.github.spysyweeb.beamline.feature.search

import io.github.spysyweeb.beamline.api.data.InstalledSet
import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.SavedKind
import io.github.spysyweeb.beamline.search.NearbyPoi
import io.github.spysyweeb.beamline.search.PoiDetails
import io.github.spysyweeb.beamline.search.RecentPlaces
import io.github.spysyweeb.beamline.search.hours.OpenState
import io.github.spysyweeb.beamline.search.saved.SavedPlaces
import io.github.spysyweeb.beamline.units.UnitFormatter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.LocalDateTime

/** What a pick is for: a new trip, a stop of the current trip, the trip's start point, or a saved place (SaveTarget). */
enum class SearchMode { GO_TO, ADD_STOP, SET_START, SAVE }

/** What a pick in SAVE mode is for (docs/architecture-saved-places.md, rule SP15). */
sealed interface SaveTarget {
    data object Home : SaveTarget
    data object Work : SaveTarget
    /** A new place with a name: the pick returns SearchOutput.SaveNew and the app opens the save dialog. */
    data object NewPlace : SaveTarget
    /** A new point for the saved place [savedId]; its id, kind and name stay. */
    data class Move(val savedId: String) : SaveTarget
}

enum class NearSource { GPS, MAP_CENTER, NONE }

/**
 * Where results are ranked from: a fresh fix (60 s) when there is one, else the map centre.
 * [accuracyM]: the fix's horizontal accuracy (GPS only; null when unknown): "Use my position" needs
 * it at most saved.position_max_accuracy_m (SP23).
 */
data class SearchContext(
    val near: GeoPoint?,
    val source: NearSource,
    val accuracyM: Double? = null,
    /** S1b: the user's position when [near] is the map centre (results ranked there, distances from the user). */
    val distanceFrom: GeoPoint? = null,
)

data class ShortcutUi(val id: String, val label: String, val iconName: String)

/**
 * Batch O3 (docs/next-round.md item 27): one place type of the "View all" grid and of the type picker
 * of "Add a missing place" (one component, [PlaceTypeGrid]): the category [code], its [label], its
 * sprite [iconName] (the same as the map and the result rows) and the [query] a tap in the search types.
 */
data class PlaceTypeUi(val code: String, val label: String, val iconName: String, val query: String)

/** A plain heading of the grid ("Food and drink") and its types. */
data class PlaceTypeGroupUi(val heading: String, val types: List<PlaceTypeUi>)

/**
 * Batch O3 (docs/next-round.md item 26): one of the user's own sent changes (an added place, an edit, a
 * replacement or a closing), shown in search and on the place sheet as if the map had it, until an
 * installed pack does (the app makes these from its outbox and passes them to [SearchFeature.ownPlaces]).
 * [name]: the name now; [oldName]: the pack's name at that spot it replaces (its pack row is not shown);
 * [categoryCode] / [categoryLabel]: its type; [terms]: words besides its name that find it ("food");
 * [overrides]: "opening_hours", "phone", "website" set by the user (a null value: removed; a key not
 * there comes from the pack); [closed]: closed permanently; [osm]: its OSM object. [toString] prints
 * none of it (PV2).
 */
data class OwnPlaceUi(
    val key: String,
    val name: String,
    val point: GeoPoint,
    val categoryCode: String?,
    val categoryLabel: String?,
    val terms: List<String> = emptyList(),
    val oldName: String? = null,
    val overrides: Map<String, String?> = emptyMap(),
    val closed: Boolean = false,
    val osm: String? = null,
) {
    override fun toString(): String = "OwnPlaceUi"
}

/** [iconName]: a sprite name of the style bundle (assets/poi_icons.json, or saved_home / saved_work / saved_place). [note]: "Exact address", "Street only", "Saved", ... */
data class ResultRowUi(
    val key: String, val iconName: String, val title: String, val subtitle: String?, val distanceText: String?, val note: String?,
    /** Open, closing soon or closed now (rule S10); null when the place has no hours or they cannot be read. */
    val hours: HoursUi? = null,
)

/**
 * A place's opening hours now (rule S10, batch B): [state] gives the colour (green, yellow, red),
 * [label] the words always beside it ("Open now", "Closing soon", "Closed now"), [next] the next
 * change ("until 9 PM", "closes at 9 PM", "opens at 7 AM", "opens Mon at 7 AM", "open 24 hours";
 * null when there is none in the week ahead). [week]: Monday to Sunday (the place sheet's tap);
 * [holidayNote]: "Holiday hours may differ" when holiday rules were left out.
 */
data class HoursUi(
    val state: OpenState,
    val label: String,
    val next: String?,
    val week: List<HoursDayUi> = emptyList(),
    val holidayNote: String? = null,
)

/** One day of the week view: "Mon", "8 AM–5 PM" or "Closed" or "Open 24 hours"; [today] is drawn bold. */
data class HoursDayUi(val day: String, val text: String, val today: Boolean)

/**
 * The results of a search shown on the map (rule S11): [rows] and [places] in the same order,
 * nearest first, at most search_app.json on_map.max_results (20); [selected]: the index whose
 * sheet is open (its pin stands out), null while the list shows.
 */
data class ResultsUi(val query: String, val rows: List<ResultRowUi>, val places: List<Place>, val selected: Int? = null)

/** The results view (rule S11). */
sealed interface ResultsEvent {
    /** A row or a result pin: that place's sheet opens (the app moves the map to it, K1). */
    data class Select(val index: Int) : ResultsEvent
    /** The sheet of a result closed: the list shows again. */
    data object Deselect : ResultsEvent
    /** The list's close button or Back: the results and their pins are gone. */
    data object Close : ResultsEvent
}

/**
 * A Home, Work or other saved-place row of the search screen and the saved places screen.
 * [savedId] null: the "Set home" / "Set work" row. [distanceText] only with a GPS position (SP10).
 */
data class SavedRowUi(
    val savedId: String?,
    val kind: SavedKind,
    val iconName: String,
    val title: String,
    val subtitle: String?,
    val distanceText: String?,
    /** "Outside the installed maps", or "Already your destination" (SET_START, SP14; a tap does nothing). */
    val note: String?,
    /** The row's "Edit" and its accessibility label "Edit Mom's" (SP5); null: no Edit (search rows, a pick-only list). */
    val editLabel: String? = null,
)

enum class SearchPhase { START, SEARCHING, RESULTS, NO_RESULTS, NO_SEARCH_DATA }

data class SearchUi(
    val mode: SearchMode = SearchMode.GO_TO,
    val query: String = "",
    val phase: SearchPhase = SearchPhase.START,
    val shortcuts: List<ShortcutUi> = emptyList(),
    val recents: List<ResultRowUi> = emptyList(),
    val results: List<ResultRowUi> = emptyList(),
    /** "Near your position", "Near the map center". */
    val nearText: String? = null,
    val message: String? = null,
    /** SAVE mode: the line above the field ("Set home", "Set work", "Save place", "New location for Mom's"); null otherwise. */
    val title: String? = null,
    /** START: the Home and Work rows (table of 2.1). */
    val slots: List<SavedRowUi> = emptyList(),
    /** START: the subtitle of the "Saved places" row ("None yet", "1 place", "4 places"); null: no row. */
    val savedPlacesText: String? = null,
    /** START in SAVE mode: the "Choose on the map" row. */
    val chooseOnMap: Boolean = false,
    /** Saved places whose name matches the typed text (SP20, SP21), shown above [results]; keys "saved:<id>"; always empty in SAVE mode. */
    val savedResults: List<ResultRowUi> = emptyList(),
    /** GO_TO START: the notice of SP52 or SP53, shown first with "OK"; null otherwise. */
    val savedNotice: String? = null,
    /** START in SAVE mode: the "Use my position" row (a fresh fix within saved.position_max_accuracy_m, SP23). */
    val useMyPosition: Boolean = false,
    /** Raise the keyboard when the screen opens; false in ADD_STOP while driving (SP4). */
    val focusField: Boolean = true,
    /** Batch O3 ("View all", item 27): the grid of every place type shows over the start rows; null: not shown. */
    val allTypes: List<PlaceTypeGroupUi>? = null,
)

sealed interface SearchEvent {
    data class QueryChanged(val text: String) : SearchEvent
    data class ShortcutTapped(val id: String) : SearchEvent
    /** A result, a recent or a saved result ("saved:<id>"). */
    data class RowTapped(val key: String) : SearchEvent
    data class RemoveRecent(val key: String) : SearchEvent
    data object ClearRecents : SearchEvent
    /** "Add a state" on the NO_SEARCH_DATA screen. */
    data object OpenStates : SearchEvent
    /** The Home or Work row: set, a pick (SP12-SP14); empty, SAVE mode for that slot (SP15). */
    data class SlotTapped(val kind: SavedKind) : SearchEvent
    /** The "Saved places" row. */
    data object OpenSavedPlaces : SearchEvent
    /** The "Choose on the map" row (SAVE mode). */
    data object ChooseOnMap : SearchEvent
    /** The "Use my position" row (SAVE mode, SP23). */
    data object UseMyPosition : SearchEvent
    /** "OK" on the notice of SP52 or SP53. */
    data object DismissSavedNotice : SearchEvent
    data object Close : SearchEvent
    /** The keyboard's search action (Enter): in GO_TO while not driving, the matches go on the map (rule S11). */
    data object Submit : SearchEvent
    /** Batch O3: the "View all" chip at the end of the shortcuts: the grid of every place type opens. */
    data object ViewAll : SearchEvent
    /** Batch O3: a type of the grid: its search runs as a chip's does, and the grid closes. */
    data class TypeTapped(val type: PlaceTypeUi) : SearchEvent
    /** Batch O3: the grid's Back. */
    data object CloseViewAll : SearchEvent
}

sealed interface SearchOutput {
    /** Never with mode SAVE: SAVE picks are saved here (Home, Work, Move) or returned as SaveNew. */
    data class Picked(val place: Place, val mode: SearchMode) : SearchOutput
    /** The app opens the States screen. */
    data object OpenStates : SearchOutput
    data object Closed : SearchOutput
    /** GO_TO: a saved place inside the installed maps was tapped; plan the trip now, no sheet (SP12, SP18). */
    data class PlanTrip(val place: Place) : SearchOutput
    /** Show this place's sheet on the map (a saved place outside the installed maps, SP13). */
    data class ShowPlace(val place: Place) : SearchOutput
    /** Open the saved places screen; a row tap there picks for [mode] (SP27). */
    data class OpenSavedPlaces(val mode: SearchMode) : SearchOutput
    /** SAVE mode for SaveTarget.NewPlace: [place] was picked; the app opens the save dialog. */
    data class SaveNew(val place: Place) : SearchOutput
    /** SAVE mode: "Choose on the map" for [target] (SP16); SAVE mode has ended (back at GO_TO START, or done when opened by openToSave, SP25). */
    data class PickOnMap(val target: SaveTarget) : SearchOutput
    /** Enter found several matches (rule S11): the search screen is done; SearchFeature.results holds them and the app fits [places]. */
    data class ShowResults(val places: List<Place>) : SearchOutput
}

/**
 * CALL and WEBSITE (rule S10) last, never while driving: the app opens the dialer or the browser.
 * REPORT is never in [PlaceSheetUi.actions]: the sheet offers it by [PlaceSheetUi.canReport]
 * (docs/online-design.md 4.1, batch O1); EDIT_OSM neither, by [PlaceSheetUi.canEditOsm] (4.2, batch O2);
 * ADD_PLACE neither, by [PlaceSheetUi.canAddPlace] (4.3, batch O3).
 */
enum class PlaceAction { DIRECTIONS, ADD_STOP, OPEN_STATES, SAVE, EDIT, CALL, WEBSITE, REPORT, EDIT_OSM, ADD_PLACE }

data class PlaceSheetUi(
    val place: Place,
    val iconName: String,
    val title: String,
    val subtitle: String?,
    val distanceText: String?,
    /** "Outside the installed maps" when no routing tile covers it. */
    val note: String?,
    /** In display order (SP19); OPEN_STATES then SAVE or EDIT when the place cannot be routed to. */
    val actions: List<PlaceAction>,
    /** "Saved as Home", "Saved as Mom's"; null when not saved or when the title already is the saved name. */
    val savedText: String? = null,
    /** The saved place this is (EDIT acts on it); null when not saved. */
    val savedId: String? = null,
    /** Opening hours now and the week (rule S10); null when OSM has none or the text cannot be read. */
    val hours: HoursUi? = null,
    /** The number the CALL action dials (the first of OSM's `phone`); null: no CALL. */
    val phone: String? = null,
    /** The address the WEBSITE action opens (http or https, the first of OSM's `website`); null: no WEBSITE. */
    val website: String? = null,
    /** The details height (batch G, docs/next-round.md item 20): every number of OSM's `phone`, each on its own line. */
    val phones: List<ContactUi> = emptyList(),
    /** Every web address of OSM's `website` that a browser can open, each on its own line. */
    val websites: List<ContactUi> = emptyList(),
    /** The postal address from the pack ("4662 West Ridge Road, Erie, PA 16506"); null when it has none. */
    val address: String? = null,
    /** What the place is, in words ("Convenience store"); null when the pack does not say. */
    val category: String? = null,
    /** False while guiding (GUIDING, REROUTING): the numbers and web addresses show, but a tap or a long press does nothing (S10). */
    val canOpenOutside: Boolean = true,
    /**
     * "Report a problem" (docs/online-design.md 4.1): the app sets it while "Contribute to
     * OpenStreetMap" is on and no trip is being driven. A dropped pin shows "Report a problem here"
     * beside its buttons; any other place at the details height. Either sends Act(REPORT).
     */
    val canReport: Boolean = false,
    /** Batch O2 (docs/online-design.md 4.2): the OSM object of the place from its pack ("n25479855"), or null. */
    val osm: String? = null,
    /** The pack's own values of the place (hours, phone, website as OSM had them), or null. */
    val poi: PoiDetails? = null,
    /**
     * "Edit on OpenStreetMap" at the details height: the app sets it for a place with [osm] while
     * "Contribute to OpenStreetMap" is on and no trip is being driven. It sends Act(EDIT_OSM).
     */
    val canEditOsm: Boolean = false,
    /**
     * Batch O3 (docs/next-round.md item 26): the place is one of the user's own sent changes not yet in an
     * installed pack; its values above are the user's, and the sheet shows this one small tag ("Your
     * edit · waiting for the map update"); null otherwise.
     */
    val ownTag: String? = null,
    /**
     * Batch O3 (docs/online-design.md 4.3): "Add a missing place" beside "Report a problem here" on a
     * dropped pin; the app sets it with [canReport]. It sends Act(ADD_PLACE).
     */
    val canAddPlace: Boolean = false,
)

/**
 * A phone number or a web address on the place sheet's details height (batch G): [text] as shown
 * ("(814) 555-0123", "example.com/menu", long ones shortened in the middle), [value] what a tap
 * opens (the number dialled, the http(s) address; null: the text is shown but opens nothing) and
 * [copy] what a long press copies (the number as written, the whole address).
 */
data class ContactUi(val text: String, val value: String?, val copy: String)

sealed interface PlaceSheetEvent {
    data class Act(val action: PlaceAction) : PlaceSheetEvent
    /** A tap on a number (CALL) or a web address (WEBSITE) of the details height: the app opens the dialer or the browser with [value], as the buttons do (S10). */
    data class OpenOutside(val action: PlaceAction, val value: String) : PlaceSheetEvent
    data object Close : PlaceSheetEvent
}

/**
 * UI-free search controller (rules S1-S7; saved places SP10-SP31). Queries run on
 * [searchDispatcher]; [ui] is read on the main thread. [saved] is read for the rows and written for
 * SAVE-mode picks of Home, Work and Move.
 */
class SearchFeature(
    installed: StateFlow<InstalledSet>,
    recents: RecentPlaces,
    saved: SavedPlaces,
    units: StateFlow<UnitFormatter>,
    scope: CoroutineScope,
    searchDispatcher: CoroutineDispatcher,
) {
    val ui: StateFlow<SearchUi> get() = impl.ui
    /**
     * GO_TO, ADD_STOP or SET_START; SAVE goes through [openToSave]. [driving]: phase GUIDING,
     * REROUTING or ARRIVED (no keyboard at once, SP4). [destination]: in SET_START, the trip's last
     * stop, whose saved row reads "Already your destination" (SP14).
     */
    fun open(mode: SearchMode, context: SearchContext, driving: Boolean = false, destination: Place? = null): Unit = impl.open(mode, context, driving, destination)
    /** SAVE mode for [target] (from the saved places screen or a sheet's Edit); after the save, on Close or on "Choose on the map", onEvent returns Closed or PickOnMap and this search is done. */
    fun openToSave(target: SaveTarget, context: SearchContext): Unit = impl.openToSave(target, context)
    /** Picked places are added to the recents here, except saved places and SAVE-mode picks (SP31). */
    fun onEvent(event: SearchEvent): SearchOutput? = impl.onEvent(event)
    /**
     * For a search pick, a recent, a tapped POI, a dropped pin or a saved place; [tripActive] (phase
     * PLANNING, CHOOSING, GUIDING or REROUTING) offers ADD_STOP first; [driving] (GUIDING, REROUTING
     * or ARRIVED) leaves out SAVE and EDIT (SP4, SP19); [guiding] (GUIDING or REROUTING) leaves out
     * CALL and WEBSITE (S10: offered again once the trip has arrived).
     */
    fun placeSheet(place: Place, context: SearchContext, tripActive: Boolean, driving: Boolean = false, guiding: Boolean = driving): PlaceSheetUi = impl.placeSheet(place, context, tripActive, driving, guiding)
    fun close(): Unit = impl.close()

    /** The results on the map after Enter (rule S11); null when none are shown. */
    val results: StateFlow<ResultsUi?> get() = impl.results
    /** Outputs that come with an answer after [onEvent] returned: ShowResults, or Picked for a single match (S11). */
    val later: SharedFlow<SearchOutput> get() = impl.later
    /** A row or pin selected (ShowPlace of that result), the sheet closed or the results closed (S11). */
    fun onResultsEvent(event: ResultsEvent): SearchOutput? = impl.onResultsEvent(event)
    /** S11: ends the results when a state one of them came from is no longer in [set] (removed while they show); true when they ended. */
    fun closeResultsGoneFrom(set: InstalledSet): Boolean = impl.closeResultsGoneFrom(set)
    /** S11b: "Search this area": the results' words again around [center], only places inside [area]; they replace the results, the camera stays. The number found (0: the results stay as they were); null when they ended or changed meanwhile, or no pack could be read. */
    suspend fun searchArea(center: GeoPoint, area: Bounds): Int? = impl.searchArea(center, area)
    /** Counts up when hours, phone or website of a place arrived from its pack: a place sheet made before is made again (S10). */
    val detailsLoaded: StateFlow<Int> get() = impl.detailsLoaded
    /** Recomputes the statuses of the results rows at the clock (the app calls it each minute, S10). */
    fun refreshHours(): Unit = impl.refreshHours()
    /**
     * Batch O3 (docs/next-round.md item 26): the user's own sent changes shown as if the map had them
     * (search rows, the place sheet) until an installed pack does; the app sets them from its outbox.
     */
    var ownPlaces: List<OwnPlaceUi>
        get() = impl.ownPlaces
        set(value) = run { impl.ownPlaces = value }
    /** Batch O3: the named POIs of the installed packs within [withinM] of [point], nearest first (on the search thread). */
    suspend fun poisNear(point: GeoPoint, withinM: Double): List<NearbyPoi> = impl.poisNear(point, withinM)
    /** Batch O3: the place types of the installed packs, grouped as the "View all" grid shows them. */
    suspend fun placeTypes(): List<PlaceTypeGroupUi> = impl.placeTypes()
    /** The phone's local date and time the hours are evaluated at (S10); the test extras may set another. */
    var hoursClock: () -> LocalDateTime
        get() = impl.hoursClock
        set(value) = run { impl.hoursClock = value }

    // Track S: the controller behind the contract above (SearchController.kt), searching through a SearchHub.
    private val impl = SearchController(installed, recents, saved, units, scope, searchDispatcher, HubSearcher())

    /** Tests: the same controller with another searcher (a fake, or a hub on chosen files). */
    internal constructor(
        installed: StateFlow<InstalledSet>, recents: RecentPlaces, saved: SavedPlaces, units: StateFlow<UnitFormatter>,
        scope: CoroutineScope, searchDispatcher: CoroutineDispatcher, searcher: PackSearcher,
    ) : this(installed, recents, saved, units, scope, searchDispatcher) {
        impl.searcher = searcher
    }
}
