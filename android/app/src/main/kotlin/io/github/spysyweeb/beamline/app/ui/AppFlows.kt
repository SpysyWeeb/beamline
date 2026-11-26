package io.github.spysyweeb.beamline.app.ui

import io.github.spysyweeb.beamline.app.online.AddSession
import io.github.spysyweeb.beamline.app.online.EditSession
import io.github.spysyweeb.beamline.app.online.NearbyPlace
import io.github.spysyweeb.beamline.app.online.OnlineTexts
import io.github.spysyweeb.beamline.feature.search.placeTypeIcon
import io.github.spysyweeb.beamline.app.online.EditTarget
import io.github.spysyweeb.beamline.app.online.NoteText
import io.github.spysyweeb.beamline.app.online.OsmRef
import io.github.spysyweeb.beamline.app.online.OsmType
import io.github.spysyweeb.beamline.app.online.OutboxItem
import io.github.spysyweeb.beamline.app.online.OutboxState
import io.github.spysyweeb.beamline.app.online.SpeedLimits
import io.github.spysyweeb.beamline.app.online.SpeedSession
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import io.github.spysyweeb.beamline.api.map.CameraTarget
import io.github.spysyweeb.beamline.api.map.EdgeInsetsDp
import io.github.spysyweeb.beamline.api.map.MapGesture
import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.nav.NavIntent
import io.github.spysyweeb.beamline.api.nav.Origin
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.Simulation
import io.github.spysyweeb.beamline.app.AppGraph
import io.github.spysyweeb.beamline.feature.drive.DriveEvent
import io.github.spysyweeb.beamline.feature.search.NearSource
import io.github.spysyweeb.beamline.feature.search.PlaceAction
import io.github.spysyweeb.beamline.feature.search.PlaceSheetEvent
import io.github.spysyweeb.beamline.feature.search.PlaceSheetUi
import io.github.spysyweeb.beamline.feature.search.ResultsEvent
import io.github.spysyweeb.beamline.feature.search.SavedEvent
import io.github.spysyweeb.beamline.feature.search.SavedOutput
import io.github.spysyweeb.beamline.feature.search.SearchContext
import io.github.spysyweeb.beamline.feature.search.SearchEvent
import io.github.spysyweeb.beamline.feature.search.SearchMode
import io.github.spysyweeb.beamline.feature.search.SearchOutput
import io.github.spysyweeb.beamline.search.SearchAppRules
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale

/** A screen over the map. The search screen remembers the mode it was opened in (5.5: back to it). */
sealed interface Layer {
    data class Search(val id: Int, val mode: SearchMode, val forSave: Boolean) : Layer
    data object SavedList : Layer
    data object Settings : Layer
    data object About : Layer
    data object Licences : Layer
    data object DriverNotice : Layer
    data object States : Layer
    /** Batch O1: the report form for a new note at [target], or for the outbox item [itemId] (docs/online-design.md 4.1, 4.5). */
    data class Report(val target: ReportTarget?, val itemId: String? = null) : Layer
    /** "My reports": the outbox (4.5). */
    data object Reports : Layer
    /** Batch O2: "Edit on OpenStreetMap" (4.2); the session is [AppFlows.edit]. */
    data object EditPlace : Layer
    /** Batch O3: "Add a missing place" (4.3); the session is [AppFlows.add]. */
    data object AddPlace : Layer
    /** Item 32: the speed limit of a road (4.7); the session is [AppFlows.speed]. */
    data object SpeedLimit : Layer
}

/** Where a new note goes: the pressed point or the place's point, never the phone's position (section 3). */
data class ReportTarget(val lat: Double, val lon: Double, val placeName: String?, val startChoice: String? = null, val startText: String? = null) {
    override fun toString(): String = "ReportTarget"
}

/** The two dialogs of rule SP18 ("Drop your trip ...", "End this trip ..."): Cancel / Go. */
data class ConfirmUi(val text: String, val onGo: () -> Unit)

/** What only the activity can do: ask for permissions. */
interface Host {
    /** Asks for location (fine and coarse) unless precise location is granted already, then runs [then]. */
    fun requestLocation(then: (granted: Boolean) -> Unit)

    /** POST_NOTIFICATIONS at the first Start, unless granted or asked already (5.4 item 9). */
    fun requestNotificationsOnce()

    /**
     * The route sheet's "Allow precise location to start guidance" (section 4, D4): asks for precise
     * location again, or says where to allow it when Android no longer shows the question.
     */
    fun requestPreciseLocation()

    /**
     * The place sheet's Call and Website (rule S10): the phone's dialer with [value] (ACTION_DIAL, no
     * permission) or its browser with the address; the app itself makes no request (N1).
     */
    fun openOutside(action: PlaceAction, value: String)
}

/**
 * The screens over the map and the flows between them (docs/architecture.md 5.4 items 5 and 6,
 * docs/architecture-saved-places.md 5.5 and SP18). One per process, beside the session: a trip, a
 * search or a map pick survive the activity. UI state only; every decision about places, trips
 * and packs is the modules'.
 */
class AppFlows(private val g: AppGraph) {
    /** Screens over the map, the top one last. */
    val stack = mutableStateListOf<Layer>()
    var stopsEditorOpen by mutableStateOf(false)
    var confirm by mutableStateOf<ConfirmUi?>(null)
    var snackbar by mutableStateOf<String?>(null)

    /** A new note waiting for the public-note notice's "Send" or "Not now" (docs/online-design.md section 3). */
    var notePending by mutableStateOf<OutboxItem?>(null)

    /** Batch O2: the edit being made (Layer.EditPlace), and the public-edit notice waiting for its answer. */
    var edit by mutableStateOf<EditSession?>(null)
    var editNoticeShown by mutableStateOf(false)
    /** Signed out, "Edit on OpenStreetMap" was tapped: the sign-in dialog for this place (answer 6). */
    var signInFor by mutableStateOf<EditTarget?>(null)
    /** The edit to open once the sign-in it waited for has come back. */
    private var afterSignIn: EditTarget? = null

    /** Batch O3: the sprite icon of the map label tapped last (its tile kind; poi_icons.json map_kinds). */
    private var tappedIcon: String? = null

    /** Batch O3: "Add a missing place" being made (Layer.AddPlace). */
    var add by mutableStateOf<AddSession?>(null)
    /** Batch O3: signed out, "Add a missing place" was tapped at this point: sign in or a note. */
    var signInToAdd by mutableStateOf<GeoPoint?>(null)
    /** Item 32: the speed limit fix being made (Layer.SpeedLimit), and the picker a tap on the speed sign opened. */
    var speed by mutableStateOf<SpeedSession?>(null)
    var speedPicking by mutableStateOf(false)
    private var afterSignInAdd: GeoPoint? = null

    /**
     * Rule R4: the "Trip ended" bar with Undo, shown for END_UNDO_MS after the trip bar's End ended
     * guidance; the value tells one End from the next (each restarts the bar's time). Null: no bar.
     */
    var tripEnded by mutableStateOf<Long?>(null)

    /** R4: Undo was tapped and the session is still IDLE re-planning ("Continuing the trip..."). */
    var continuing by mutableStateOf(false)

    /** The splash reveal plays once per process (A's README). */
    var revealDone by mutableStateOf(false)

    /** The first-run picker was closed in this process ("Not now", "Look at the map while you wait"). */
    var firstRunClosed by mutableStateOf(false)

    /** The map has drawn its first full frame of a loaded style (the reveal waits for it, at most 2 s). */
    var mapReady by mutableStateOf(false)

    /** A camera move asked for by the test extras `--ef lat/lon/zoom`; the map applies it once. */
    var cameraRequest by mutableStateOf<CameraTarget?>(null)

    /** A plain-words line about location (refused, off) with OK. */
    var locationMessage by mutableStateOf<String?>(null)

    /** The space the drive overlay covers (DriveOverlay.onInsets). */
    var overlayInsets by mutableStateOf(EdgeInsetsDp())

    /** What the camera director pads by: the overlay plus the app's own chrome (AppRules.cameraInsets). */
    var cameraInsets by mutableStateOf(EdgeInsetsDp())

    /** Until when the map's short credit shows (wall clock, AppRules.creditShown); 0 after the first touch. */
    var creditUntilMs by mutableStateOf(0L)

    fun showCredit() {
        creditUntilMs = System.currentTimeMillis() + AppRules.CREDIT_SHOWN_MS
    }

    /**
     * The installed states of the style on which a map last placed its camera: a map made again
     * (the activity recreated) over the same states puts the camera back at [cameraCenter] and
     * [cameraZoom] instead of fitting the state again.
     */
    @Volatile var placedPackIds: List<String>? = null

    /** The camera as last seen by the map (search's map centre, the pick's zoom, SP24). */
    @Volatile var cameraCenter: GeoPoint? = null
    @Volatile var cameraZoom: Double = 0.0

    /**
     * The map rests on an area no installed state covers, zoomed in far enough that only the plain
     * overview shows there (the owner, 2026-11-21: a blank grey map with no word why). The map then
     * says "No map for this area. Add a state." By the states' bounding boxes: a corner of a
     * neighbouring state or a lake inside a box counts as covered.
     */
    var noMapHere by mutableStateOf(false)
        private set

    /** S11b: "Search this area" below the search bar while results show: offered, searching, or found nothing there. */
    enum class AreaSearch { OFFERED, SEARCHING, NONE_HERE }

    /** S11b: null while not offered (no results, or the map not moved by the user since they showed). */
    var areaSearch by mutableStateOf<AreaSearch?>(null)
        private set

    /** S11b: the part of the map the user sees, as last at rest. */
    @Volatile private var cameraBounds: Bounds? = null

    /** S11b: the user moved the map since these results showed. */
    private var movedOverResults = false

    /** Where the camera came to rest (the map's idle callback, once the camera was placed); [bounds]: the part the user sees. */
    fun onCameraRest(center: GeoPoint, zoom: Double, bounds: Bounds? = null) {
        cameraCenter = center
        cameraZoom = zoom
        cameraBounds = bounds
        refreshNoMapHere()
        // S11b: the user dragged or zoomed the map over results: offer them again in this window
        if (movedOverResults && bounds != null && g.search.results.value != null && areaSearch != AreaSearch.SEARCHING) {
            areaSearch = AreaSearch.OFFERED
        }
    }

    /** S11b: the tap on "Search this area": the results' words in the window as it rests now; the camera stays. */
    fun onSearchArea() {
        if (areaSearch != AreaSearch.OFFERED) return
        val center = cameraCenter ?: return
        val area = cameraBounds ?: return
        areaSearch = AreaSearch.SEARCHING
        movedOverResults = false
        g.scope.launch {
            val found = g.search.searchArea(center, area)
            if (found != null && found > 0 && g.sheetPlace.value != null) {
                // the list of the new results shows, not the sheet of an old one
                g.director.onPlaceLeft()
                g.sheetPlace.value = null
            }
            areaSearch = if (found == 0) AreaSearch.NONE_HERE else null
        }
    }

    /** [noMapHere] for where the camera rests and what is installed now (nothing is said before the first scan). */
    private fun refreshNoMapHere() {
        val center = cameraCenter
        val set = g.installed.installed.value
        noMapHere = center != null && set.scanned && cameraZoom >= NO_MAP_FROM_ZOOM && set.packs.none { it.bounds.contains(center) }
    }

    var host: Host? = null

    private var nextSearchId = 1

    /** The search layer whose session SearchFeature holds now (an openToSave replaces it). */
    private var searchOwner = 0
    private var testJobs: List<Job> = emptyList()

    private val fixMaxAgeMs = (SearchAppRules.bundled().fixMaxAgeS * 1000).toLong()

    init {
        // S11: the answers that come after Enter (the results, or the one match picked)
        g.scope.launch { g.search.later.collect { onSearchOutput(it) } }
        // S11: the results are a browsing view: a trip planned or guided ends them
        g.scope.launch {
            g.session.state.collect { s ->
                if (s.phase != Phase.IDLE && s.phase != Phase.RESUME_OFFERED && g.search.results.value != null) closeResults()
            }
        }
        // S11b: other results, or none, start without "Search this area" (a selection keeps it)
        g.scope.launch {
            var shownPlaces: List<Place>? = null
            g.search.results.collect { r ->
                if (r == null || r.places !== shownPlaces) {
                    shownPlaces = r?.places
                    movedOverResults = false
                    if (areaSearch != AreaSearch.SEARCHING) areaSearch = null
                }
            }
        }
        // S11: a state removed while its results show ends them (the camera stays)
        g.scope.launch {
            g.installed.installed.collect { set ->
                if (g.search.closeResultsGoneFrom(set)) g.director.onPlaceLeft()
                // a state added or removed under a resting camera: the "No map for this area" chip follows
                refreshNoMapHere()
            }
        }
    }

    private fun send(intent: NavIntent) = g.session.send(intent)
    private val phase: Phase get() = g.session.state.value.phase

    // ------------------------------------------------------------------ search

    /** S1: a fresh fix (search_app.json near.fix_max_age_s) when there is one, else the map centre. */
    fun searchContext(): SearchContext {
        val fix = g.sharedPositions.latest.value ?: g.puck.lastFix.value
        if (fix != null && g.clock.elapsedMs() - fix.elapsedMs <= fixMaxAgeMs) {
            return SearchContext(GeoPoint(fix.lat, fix.lon), NearSource.GPS, fix.accuracyM?.toDouble())
        }
        cameraCenter?.let { return SearchContext(it, NearSource.MAP_CENTER) }
        return SearchContext(null, NearSource.NONE)
    }

    /**
     * S1b (the owner, 2026-11-25: "If the user drags the map to a different area and searches for
     * something, does the app drag the map back to the user and search, or does it leave the map
     * where it is and search that area?"): a search for a place (GO_TO) on a map moved so that the
     * user's position is out of sight searches the map instead: near its centre ("Near the map
     * center"), distances still from the user. The position in sight, the camera not yet at rest, or
     * another kind of search (a start, a stop, a save): [searchContext] as before.
     */
    private fun searchContextFor(mode: SearchMode): SearchContext {
        val ctx = searchContext()
        if (mode != SearchMode.GO_TO || ctx.source != NearSource.GPS) return ctx
        val me = ctx.near ?: return ctx
        val seen = cameraBounds ?: return ctx
        val center = cameraCenter ?: return ctx
        return if (seen.contains(me)) ctx else SearchContext(center, NearSource.MAP_CENTER, distanceFrom = me)
    }

    private fun destinationFor(mode: SearchMode): Place? =
        if (mode == SearchMode.SET_START) g.session.state.value.stops.lastOrNull()?.place else null

    /** Search in GO_TO (the pill), ADD_STOP (DriveEvent.SearchForStop) or SET_START (DriveEvent.ChooseStart). */
    fun openSearch(mode: SearchMode, typed: String? = null) {
        val layer = Layer.Search(nextSearchId++, mode, forSave = false)
        g.search.open(mode, searchContextFor(mode), driving = g.driving(), destination = destinationFor(mode))
        searchOwner = layer.id
        stack.add(layer)
        // from the bar that holds the words of the results on the map: the search opens with them typed
        typed?.takeIf { it.isNotBlank() }?.let { g.search.onEvent(SearchEvent.QueryChanged(it)) }
    }

    fun onSearchEvent(e: SearchEvent) {
        // the results that come next are a type's of the "View all" grid, or not: Back from them returns there
        when (e) {
            is SearchEvent.TypeTapped -> resultsFromGrid = stack.lastOrNull().let { it is Layer.Search && it.mode == SearchMode.GO_TO }
            is SearchEvent.ShortcutTapped, is SearchEvent.QueryChanged, SearchEvent.Submit -> resultsFromGrid = false
            else -> Unit
        }
        g.search.onEvent(e)?.let(::onSearchOutput)
    }

    /** The results on the map are a place type's, picked in the "View all" grid ([backFromResults]). */
    private var resultsFromGrid = false

    private fun onSearchOutput(o: SearchOutput) {
        when (o) {
            is SearchOutput.Picked -> {
                closeSearchAndList()
                when (o.mode) {
                    SearchMode.GO_TO -> showPicked(o.place)
                    SearchMode.ADD_STOP -> send(NavIntent.AddStop(o.place))
                    SearchMode.SET_START -> send(NavIntent.SetOrigin(Origin.FromPlace(o.place)))
                    SearchMode.SAVE -> Unit
                }
            }
            SearchOutput.OpenStates -> {
                closeSearchAndList()
                openStates()
            }
            SearchOutput.Closed -> popSearch()
            is SearchOutput.PlanTrip -> {
                closeSearchAndList()
                planTrip(o.place)
            }
            is SearchOutput.ShowPlace -> {
                closeSearchAndList()
                stack.remove(Layer.Settings)
                showPicked(o.place)
            }
            is SearchOutput.OpenSavedPlaces -> {
                g.savedFeature.openList(o.mode, searchContext(), destinationFor(o.mode))
                stack.add(Layer.SavedList)
            }
            is SearchOutput.SaveNew -> {
                popSearch()
                g.savedFeature.openSave(o.place)
            }
            is SearchOutput.ShowResults -> {
                // S11: the search screen is done; the pins and the list show, the camera fits them
                closeSearchAndList()
                if (g.sheetPlace.value != null) {
                    g.director.onPlaceLeft()
                    g.sheetPlace.value = null
                }
                fitResults()
            }
            is SearchOutput.PickOnMap -> {
                // Opened by openToSave: this search is done. Else it is back at GO_TO START and only
                // hidden while the pick runs (the stack is not drawn during a pick, SP25).
                val top = stack.lastOrNull()
                if (top is Layer.Search && top.forSave) stack.removeAt(stack.lastIndex)
                g.savedFeature.startMapPick(o.target)
            }
        }
    }

    /** Pops the top search layer; a search below it whose session was replaced is opened again. */
    private fun popSearch() {
        val i = stack.indexOfLast { it is Layer.Search }
        if (i >= 0) stack.removeAt(i)
        reopenTopSearch()
    }

    private fun reopenTopSearch() {
        val top = stack.lastOrNull()
        if (top is Layer.Search && top.id != searchOwner && !top.forSave) {
            g.search.open(top.mode, searchContextFor(top.mode), driving = g.driving(), destination = destinationFor(top.mode))
            searchOwner = top.id
        }
    }

    private fun closeSearchAndList() {
        val hadSearch = stack.any { it is Layer.Search }
        stack.removeAll { it is Layer.Search || it == Layer.SavedList }
        if (hadSearch) g.search.close()
    }

    // ------------------------------------------------------------------ the results on the map (S11)

    fun onResultsEvent(e: ResultsEvent) {
        if (e == ResultsEvent.Close) return backFromResults()
        g.search.onResultsEvent(e)?.let(::onSearchOutput)
    }

    /**
     * Back from the results (the system's Back, the list's close): they end; when they were a place
     * type's from the "View all" grid, the search opens on that grid again (the owner, 2026-11-21: Back
     * skipped the grid), so another type is one tap away.
     */
    private fun backFromResults() {
        val grid = resultsFromGrid
        resultsFromGrid = false
        closeResults()
        if (grid && g.session.state.value.phase.let { it == Phase.IDLE || it == Phase.RESUME_OFFERED }) {
            openSearch(SearchMode.GO_TO)
            g.search.onEvent(SearchEvent.ViewAll)
        }
    }

    /** The results' pins and list gone; the camera stays where it is. */
    fun closeResults() {
        resultsFromGrid = false
        g.search.onResultsEvent(ResultsEvent.Close)
        g.director.onPlaceLeft()
    }

    private fun fitResults() {
        val r = g.search.results.value ?: return
        g.director.onShowResults(r.places.map { it.point }, g.style.rules.stateMinZoom)
    }

    // ------------------------------------------------------------------ saved places

    fun onSavedEvent(e: SavedEvent) {
        g.savedFeature.onEvent(e)?.let(::onSavedOutput)
    }

    private fun onSavedOutput(o: SavedOutput) {
        when (o) {
            is SavedOutput.PlanTrip -> {
                closeSearchAndList()
                planTrip(o.place)
            }
            is SavedOutput.ShowPlace -> {
                closeSearchAndList()
                stack.remove(Layer.Settings)
                showPicked(o.place)
            }
            is SavedOutput.Picked -> {
                closeSearchAndList()
                when (o.mode) {
                    SearchMode.ADD_STOP -> send(NavIntent.AddStop(o.place))
                    SearchMode.SET_START -> send(NavIntent.SetOrigin(Origin.FromPlace(o.place)))
                    SearchMode.GO_TO -> showPicked(o.place)
                    SearchMode.SAVE -> Unit
                }
            }
            is SavedOutput.SearchToSave -> {
                val layer = Layer.Search(nextSearchId++, SearchMode.SAVE, forSave = true)
                g.search.openToSave(o.target, searchContext())
                searchOwner = layer.id
                stack.add(layer)
            }
            is SavedOutput.Saved -> {
                // the list and an open sheet rebuild from the store (SP19); a sheet whose own Save this
                // was keeps the saved id, so a saved dropped pin says "Saved as ..." with Edit
                AppRules.sheetAfterSave(g.sheetPlace.value, saveFrom, o.place)?.let { g.sheetPlace.value = it }
                saveFrom = null
            }
            is SavedOutput.PickEnded -> Unit   // what the pick hid shows again by itself (SP25)
            SavedOutput.Closed -> {
                val i = stack.lastIndexOf(Layer.SavedList)
                if (i >= 0) stack.removeAt(i)
                reopenTopSearch()
            }
        }
    }

    /** Settings' "Saved places" row (5.6): the list with pickFor = null; nothing while driving (SP4). */
    fun openSavedPlacesFromSettings() {
        if (g.driving()) return
        g.savedFeature.openList(null, searchContext())
        stack.add(Layer.SavedList)
    }

    // ------------------------------------------------------------------ the place sheet

    /** The sheet place whose SAVE opened the save dialog (SP19), until that save ends. */
    private var saveFrom: Place? = null

    fun showSheet(place: Place) {
        // K1: a place tapped on the map replaces a picked one; the camera no longer returns to that
        if (g.sheetPlace.value != place) g.director.onPlaceLeft()
        g.sheetPlace.value = place
    }

    /**
     * A place picked from a search, the saved places, the recents or a sheet: its sheet, and the
     * camera goes to it (K1, docs/next-round.md item 2). A place tapped on the map is on screen
     * already: [showSheet] alone.
     */
    fun showPicked(place: Place) {
        showSheet(place)
        g.director.onShowPlace(place.point)
    }

    fun closeSheet() {
        val wasOpen = g.sheetPlace.value != null
        g.director.onPlaceLeft()
        g.sheetPlace.value = null
        // S11: a result's sheet closed: back to the results, the camera fits them again
        if (wasOpen && g.search.results.value?.selected != null) {
            g.search.onResultsEvent(ResultsEvent.Deselect)
            fitResults()
        }
    }

    fun onSheetEvent(e: PlaceSheetEvent, ui: PlaceSheetUi) {
        when (e) {
            PlaceSheetEvent.Close -> closeSheet()
            // Batch G: a number or web address of the details height, as the Call and Website buttons (never while guiding, S10).
            is PlaceSheetEvent.OpenOutside -> if (ui.canOpenOutside && (e.action == PlaceAction.CALL || e.action == PlaceAction.WEBSITE)) {
                host?.openOutside(e.action, e.value)
            }
            is PlaceSheetEvent.Act -> when (e.action) {
                PlaceAction.DIRECTIONS -> {
                    closeResults()
                    closeSheet()
                    planTrip(ui.place)
                }
                PlaceAction.ADD_STOP -> {
                    closeResults()
                    closeSheet()
                    send(NavIntent.AddStop(ui.place))
                }
                PlaceAction.CALL -> ui.phone?.let { host?.openOutside(PlaceAction.CALL, it) }
                PlaceAction.WEBSITE -> ui.website?.let { host?.openOutside(PlaceAction.WEBSITE, it) }
                PlaceAction.OPEN_STATES -> openStates()
                PlaceAction.SAVE -> {
                    saveFrom = ui.place
                    g.savedFeature.openSave(ui.place)
                }
                PlaceAction.EDIT -> ui.savedId?.let { g.savedFeature.openEdit(it) }
                PlaceAction.REPORT -> if (ui.canReport) {
                    val name = ui.place.name.takeIf { ui.place.source != PlaceSource.MAP_PIN }
                    push(Layer.Report(ReportTarget(ui.place.point.lat, ui.place.point.lon, name)))
                }
                PlaceAction.EDIT_OSM -> if (ui.canEditOsm) editOnOsm(ui)
                PlaceAction.ADD_PLACE -> if (ui.canAddPlace) addPlace(ui.place.point)
            }
        }
    }

    /** "Directions" and a one-tap PlanTrip, by phase (rule S7, SP18), location asked first. */
    fun planTrip(place: Place) {
        val h = host
        if (h == null) planByPhase(place) else h.requestLocation { planByPhase(place) }
    }

    private fun planByPhase(place: Place) {
        val trip = g.session.state.value
        when (trip.phase) {
            Phase.IDLE, Phase.PLANNING, Phase.CHOOSING -> send(NavIntent.PlanTo(place))
            Phase.RESUME_OFFERED -> {
                val destination = trip.stops.lastOrNull()?.place?.name ?: ""
                confirm = ConfirmUi("Drop your trip to $destination and go to ${place.name}?") {
                    send(NavIntent.DiscardResume)
                    send(NavIntent.PlanTo(place))
                }
            }
            Phase.GUIDING, Phase.REROUTING -> confirm = ConfirmUi("End this trip and go to ${place.name}?") {
                send(NavIntent.End)
                send(NavIntent.PlanTo(place))
            }
            Phase.ARRIVED -> {
                send(NavIntent.Done)
                send(NavIntent.PlanTo(place))
            }
        }
    }

    // ------------------------------------------------------------------ map gestures (item 5)

    fun onGesture(gesture: MapGesture) {
        g.director.onGesture(gesture)
        if (gesture == MapGesture.CameraMovedByUser && g.search.results.value != null) movedOverResults = true
        creditUntilMs = 0L // the first touch of the map fades the credit (item 8)
        val picking = g.savedFeature.ui.value.mapPick != null
        when (gesture) {
            is MapGesture.Tap -> when {
                // SP16, SP24: during a map pick every tap is the pick; no sheet, no dropped pin.
                picking -> g.savedFeature.onMapTap(gesture.point, gesture.poi, cameraZoom)
                gesture.pinId?.startsWith(SAVED_PIN) == true ->
                    g.saved.byId(gesture.pinId!!.removePrefix(SAVED_PIN))?.let { showSheet(it.toPlace()) }
                gesture.pinId?.startsWith(RESULT_PIN) == true ->
                    gesture.pinId!!.removePrefix(RESULT_PIN).toIntOrNull()?.let { onResultsEvent(ResultsEvent.Select(it)) }
                gesture.pinId != null -> Unit   // a stop, the destination or the picked pin: nothing
                gesture.poi != null -> {
                    // batch O3: the label's own icon, for the own-changes layer if the user edits it
                    tappedIcon = gesture.poi!!.kind.takeIf { it.isNotEmpty() }?.let { k -> MAP_KIND_ICONS[k] ?: k }
                    showSheet(Place(gesture.poi!!.name, gesture.poi!!.point, PlaceSource.MAP_POI))
                }
                else -> closeSheet()
            }
            is MapGesture.LongPress ->
                if (picking) {
                    g.savedFeature.onMapTap(gesture.point, null, cameraZoom)
                } else {
                    val pin = Place(DROPPED_PIN, gesture.point, PlaceSource.MAP_PIN, subtitle = coordinates(gesture.point))
                    showSheet(pin)
                    // which street it is on, once the pack has been asked; while this pin's sheet still shows
                    g.scope.launch {
                        val street = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { g.streetAt(pin.point) } }.getOrNull()
                        if (street != null && g.sheetPlace.value == pin) g.sheetPlace.value = pin.copy(subtitle = nearStreetText(street.name, street.subtitle, street.house))
                    }
                }
            MapGesture.CameraMovedByUser -> Unit
        }
    }

    // ------------------------------------------------------------------ the drive overlay (item 6)

    fun onDriveEvent(e: DriveEvent) {
        when (e) {
            is DriveEvent.Nav -> {
                if (e.intent is NavIntent.Start || e.intent is NavIntent.Resume) host?.requestNotificationsOnce()
                val endsGuidance = e.intent is NavIntent.End && (phase == Phase.GUIDING || phase == Phase.REROUTING)
                if (e.intent is NavIntent.End) stopsEditorOpen = false
                send(e.intent)
                // R4: one tap ends the trip at once; the bar offers Undo for END_UNDO_MS
                if (endsGuidance) { tripEnded = (tripEnded ?: 0L) + 1; continuing = false }
            }
            DriveEvent.Recenter -> g.director.onRecenter()
            DriveEvent.ShowOverview -> g.director.onOverview()
            is DriveEvent.ShowStep -> g.director.onShowPlace(e.point) // item 47: the step on the map
            DriveEvent.OpenStopsEditor -> stopsEditorOpen = true
            DriveEvent.CloseStopsEditor -> stopsEditorOpen = false
            // item 47: the arrival card raised opens the destination's window; its Save, the save dialog
            DriveEvent.ShowDestination -> g.session.state.value.stops.lastOrNull()?.place?.let(::showSheet)
            DriveEvent.SaveDestination -> g.session.state.value.stops.lastOrNull()?.place?.let { place ->
                saveFrom = place
                g.savedFeature.openSave(place)
            }
            DriveEvent.SearchForStop -> openSearch(SearchMode.ADD_STOP)
            DriveEvent.ChooseStart -> openSearch(SearchMode.SET_START)
            DriveEvent.AskPreciseLocation -> host?.requestPreciseLocation()
            // item 32: the sign is tappable only while Contribute to OpenStreetMap is on (AppRoot sets SpeedUi.canReport)
            DriveEvent.SpeedLimitTapped -> if (g.online.state.value.osmAllowed) speedPicking = true
        }
    }

    // ---- item 32: the speed limit of a road (docs/online-design.md 4.7)

    /**
     * The posted limit, picked on the dialog while driving: kept in My reports with the position and the
     * road's name as the trip knew it. Nothing is read from or sent to OpenStreetMap now; the driver
     * opens it there when stopped ([openSpeedItem]).
     */
    fun pickSpeedWhileDriving(mph: Int) {
        speedPicking = false
        if (mph !in SpeedLimits.CHOICES) return
        val fix = (g.sharedPositions.latest.value ?: g.puck.lastFix.value)?.takeIf { g.clock.elapsedMs() - it.elapsedMs <= fixMaxAgeMs }
        if (fix == null) {
            snackbar = OnlineTexts.SPEED_NO_FIX
            return
        }
        g.outbox.add(
            OutboxItem(
                id = java.util.UUID.randomUUID().toString(), lat = fix.lat, lon = fix.lon, text = OnlineTexts.speedPicked(mph),
                placeName = g.session.state.value.guidance?.currentRoad, createdMs = System.currentTimeMillis(),
                state = OutboxState.REVIEW, speedMph = mph,
            ),
        )
        snackbar = OnlineTexts.SPEED_SAVED
    }

    /** The speed limit screen for the road at a point, or for an item of My reports ([picked]: the limit chosen before). */
    fun openSpeed(lat: Double, lon: Double, roadName: String?, picked: Int?, itemId: String?) {
        if (!g.online.state.value.osmAllowed) {
            snackbar = NOTE_SWITCH_OFF
            return
        }
        if (g.accounts.account.value == null) {
            snackbar = OnlineTexts.SPEED_SIGN_IN
            return
        }
        speed = SpeedSession(lat, lon, roadName, picked, itemId, g.osm, g.outbox, g.outboxSender, g.scope, kotlinx.coroutines.Dispatchers.IO).also { it.load() }
        push(Layer.SpeedLimit)
    }

    fun openSpeedItem(item: OutboxItem) = openSpeed(item.lat, item.lon, item.placeName, item.speedMph, item.id)

    /** "Fix the speed limit of this road" on the report form of a point on the map: the form closes, the fix opens. */
    fun speedFromReport(target: ReportTarget) {
        stack.removeAll { it is Layer.Report }
        openSpeed(target.lat, target.lon, null, null, null)
    }

    fun closeSpeed() {
        speed = null
        pop(Layer.SpeedLimit)
    }

    /** "Upload" on the speed limit's summary: the public-edit notice the first time (section 3). */
    fun uploadSpeed() {
        val s = speed ?: return
        if (!g.online.state.value.editNoticeSeen) editNoticeShown = true else s.upload()
    }

    /** R4: Undo on the "Trip ended" bar: the same trip again, re-planned from here (the session decides). */
    fun undoEnd() {
        tripEnded = null
        continuing = true
        send(NavIntent.UndoEnd)
    }

    fun onInsets(insets: EdgeInsetsDp) {
        overlayInsets = insets
    }

    // ------------------------------------------------------------------ screens

    /** The States screen; never before the driver notice is accepted (it fetches the catalog when it opens: item 3, N4). */
    fun openStates() {
        if (!AppRules.noticeAccepted(g.appState.state.value)) {
            Log.i(TAG, "States not opened: the driver notice is not accepted yet")
            return
        }
        stack.remove(Layer.States)
        stack.add(Layer.States)
    }

    // ------------------------------------------------------------------ online features (batch O1)

    // ---- batch O2: sign-in (4.4) and fixing a place (4.2)

    /** "Edit on OpenStreetMap": a relation opens on openstreetmap.org; signed out, sign in or a note (answer 6). */
    private fun editOnOsm(ui: PlaceSheetUi) {
        val osm = ui.osm ?: return
        val ref = OsmRef.parse(osm) ?: return
        if (ref.type == OsmType.RELATION) {
            host?.openOutside(PlaceAction.WEBSITE, g.osmServer.elementLink(ref))
            return
        }
        val t = EditTarget(osm, ui.title, ui.place.point.lat, ui.place.point.lon, ui.title, ui.poi?.openingHours,
            ui.poi?.phone, ui.poi?.website, packOsm = ui.poi?.osm ?: osm,
            icon = tappedIcon.takeIf { ui.place.source == PlaceSource.MAP_POI } ?: ui.iconName)
        if (g.accounts.account.value == null) signInFor = t else openEdit(t, null)
    }

    /** The edit form for [t], or for the outbox item [itemId] looked at again. */
    fun openEdit(t: EditTarget, itemId: String?) {
        if (!g.online.state.value.osmAllowed) {
            snackbar = NOTE_SWITCH_OFF
            return
        }
        edit = EditSession(t, itemId, g.osm, g.outbox, g.outboxSender, g.scope, kotlinx.coroutines.Dispatchers.IO).also { it.load() }
        push(Layer.EditPlace)
    }

    /** An edit in My reports: opened again with the place read anew (REVIEW, WAITING or REFUSED). */
    fun openEditItem(item: OutboxItem) {
        val e = item.edit ?: return
        openEdit(EditTarget(e.osm, item.placeName ?: e.osm, item.lat, item.lon, null, null, null, null), item.id)
    }

    fun closeEdit() {
        edit = null
        pop(Layer.EditPlace)
    }

    /** "Upload" on the summary: the public-edit notice the first time (section 3). */
    fun uploadEdit() {
        val s = edit ?: return
        if (!g.online.state.value.editNoticeSeen) editNoticeShown = true else s.upload()
    }

    fun answerEditNotice(upload: Boolean) {
        editNoticeShown = false
        if (!upload) return
        g.updateOnline { it.copy(editNoticeSeen = true) }
        when (stack.lastOrNull()) {
            Layer.AddPlace -> add?.upload()
            Layer.SpeedLimit -> speed?.upload()
            else -> edit?.upload()
        }
    }

    // ---- batch O3: add a missing place (4.3) and a new business in an old place (item 25 b)

    /** "Add a missing place" on a dropped pin: signed out, sign in or a note ("Missing place: ..."). */
    private fun addPlace(p: GeoPoint) {
        if (!g.online.state.value.osmAllowed) {
            snackbar = NOTE_SWITCH_OFF
            return
        }
        if (g.accounts.account.value == null) signInToAdd = p else openAdd(p.lat, p.lon, null)
    }

    /** The add form at ([lat], [lon]), or for the outbox item [itemId] looked at again. */
    fun openAdd(lat: Double, lon: Double, itemId: String?) {
        if (!g.online.state.value.osmAllowed) {
            snackbar = NOTE_SWITCH_OFF
            return
        }
        val point = GeoPoint(lat, lon)
        add = AddSession(lat, lon, itemId, g.osm, g.outbox, g.outboxSender, g.scope, kotlinx.coroutines.Dispatchers.IO,
            packNear = { m -> g.search.poisNear(point, m).map { g.nearbyOf(it) } }).also { it.load() }
        push(Layer.AddPlace)
    }

    fun openAddItem(item: OutboxItem) = openAdd(item.lat, item.lon, item.id)

    fun closeAdd() {
        add = null
        pop(Layer.AddPlace)
    }

    /** "Upload" on the add summary: the public-edit notice the first time (section 3). */
    fun uploadAdd() {
        val s = add ?: return
        if (!g.online.state.value.editNoticeSeen) editNoticeShown = true else s.upload()
    }

    /** "It's the same place" or "Edit that one instead": the edit form of [p] (a relation opens on openstreetmap.org). */
    fun editInstead(p: NearbyPlace) {
        val osm = p.osm ?: return
        closeAdd()
        val ref = OsmRef.parse(osm) ?: return
        if (ref.type == OsmType.RELATION) {
            host?.openOutside(PlaceAction.WEBSITE, g.osmServer.elementLink(ref))
            return
        }
        val name = p.name ?: p.typeWords ?: osm
        openEdit(EditTarget(osm, name, p.lat, p.lon, p.name, p.packHours, p.packPhone, p.packWebsite, packOsm = p.packOsm ?: osm,
            icon = p.typeCode?.let { placeTypeIcon(it) }), null)
    }

    /** The answers of the sign-in dialog of "Add a missing place". */
    fun answerSignInToAdd(signIn: Boolean, note: Boolean) {
        val p = signInToAdd ?: return
        signInToAdd = null
        when {
            signIn -> {
                afterSignInAdd = p
                signIn(null)
            }
            note -> push(Layer.Report(ReportTarget(p.lat, p.lon, null, startText = OnlineTexts.MISSING_PLACE)))
        }
    }

    /** "Sign in to OpenStreetMap" (Settings, or the dialog of an edit): OSM's own page in the browser. */
    fun signIn(then: EditTarget? = null) {
        val url = g.startSignIn()
        if (url == null) {
            snackbar = SIGN_IN_SWITCH_OFF
            return
        }
        afterSignIn = then
        host?.openOutside(PlaceAction.WEBSITE, url)
    }

    /** The sign-in dialog's answers. */
    fun answerSignIn(signIn: Boolean, note: Boolean) {
        val t = signInFor ?: return
        signInFor = null
        when {
            signIn -> signIn(t)
            note -> push(Layer.Report(ReportTarget(t.lat, t.lon, t.name, startChoice = NoteText.CHOICES.first())))
        }
    }

    /** The redirect of a sign-in came back to the app (MainActivity). */
    fun finishSignIn(uri: String) {
        g.finishSignIn(uri) { ok ->
            when (ok) {
                null -> {}
                true -> {
                    snackbar = SIGNED_IN
                    afterSignIn?.let { t ->
                        afterSignIn = null
                        if (g.sheetPlace.value != null) openEdit(t, null)
                    }
                    afterSignInAdd?.let { p ->
                        afterSignInAdd = null
                        openAdd(p.lat, p.lon, null)
                    }
                }
                false -> {
                    afterSignIn = null
                    afterSignInAdd = null
                    snackbar = SIGN_IN_FAILED
                }
            }
        }
    }

    /** "Send" on the report form: the first time the public-note notice comes first (section 3). */
    fun sendNote(target: ReportTarget, text: String, withName: Boolean = false) {
        val item = OutboxItem(
            id = java.util.UUID.randomUUID().toString(), lat = target.lat, lon = target.lon, text = text,
            placeName = target.placeName, createdMs = System.currentTimeMillis(), withName = withName,
        )
        if (!g.online.state.value.noteNoticeSeen) notePending = item else commitNote(item)
    }

    /** The notice's answer: Send keeps it from showing again; Not now goes back to the form. */
    fun answerNoteNotice(send: Boolean) {
        val item = notePending ?: return
        notePending = null
        if (send) {
            g.updateOnline { it.copy(noteNoticeSeen = true) }
            commitNote(item)
        }
    }

    /** Confirmed: into the outbox, then sent at once when it can be (4.5). */
    private fun commitNote(item: OutboxItem) {
        val added = g.outbox.add(item)
        stack.removeAll { it is Layer.Report }
        if (!added) {
            // the same words at the same point are in My reports already (waiting, or sent minutes ago)
            snackbar = NOTE_ALREADY
            return
        }
        snackbar = NOTE_SENDING
        sendOutboxNow()
    }

    /** Sends what waits and says how it went (the form's Send, "Send now" in My reports). */
    fun sendOutboxNow() {
        if (g.outbox.items.value.none { it.state == OutboxState.WAITING }) return
        if (!g.online.state.value.osmAllowed) {
            snackbar = NOTE_SWITCH_OFF
            return
        }
        g.sendOutbox { r ->
            snackbar = when {
                r.review > 0 -> EDIT_REVIEW
                r.sent > 0 && r.left == 0 -> if (r.sent == 1) NOTE_SENT else "${r.sent} sent to OpenStreetMap"
                r.offline -> NOTE_WAITING
                r.refused > 0 -> NOTE_REFUSED
                else -> snackbar
            }
        }
    }

    fun openSettings() {
        stack.remove(Layer.Settings)
        stack.add(Layer.Settings)
    }

    fun push(layer: Layer) {
        stack.remove(layer)
        stack.add(layer)
    }

    fun pop(layer: Layer) {
        val i = stack.lastIndexOf(layer)
        if (i >= 0) stack.removeAt(i)
        reopenTopSearch()
    }

    /** States closed (also "Show on map", 9.4 item 5). */
    fun closeStates() {
        stack.removeAll { it == Layer.States }
    }

    /** Whether Back has something of ours to close (the root BackHandler is enabled by it). */
    fun canGoBack(): Boolean {
        val su = g.savedFeature.ui.value
        return confirm != null || su.dialog != null || su.mapPick != null || stack.isNotEmpty() || stopsEditorOpen ||
            g.sheetPlace.value != null || g.search.results.value != null || phase == Phase.PLANNING || phase == Phase.CHOOSING
    }

    /** Back: dialog, pick, the top screen, the stops editor, the sheet, then the route sheet (5.5's Back row). */
    fun onBack() {
        if (confirm != null) {
            confirm = null
            return
        }
        val su = g.savedFeature.ui.value
        if (su.dialog != null) return onSavedEvent(SavedEvent.DialogDismiss)
        if (su.mapPick != null) return onSavedEvent(SavedEvent.PickCancel)
        when (val top = stack.lastOrNull()) {
            is Layer.Search -> return onSearchEvent(SearchEvent.Close)
            Layer.SavedList -> return onSavedEvent(SavedEvent.Back)
            null -> Unit
            else -> return pop(top)
        }
        if (stopsEditorOpen) {
            stopsEditorOpen = false
            return
        }
        if (g.sheetPlace.value != null) return closeSheet()
        if (g.search.results.value != null) return backFromResults()
        if (phase == Phase.PLANNING || phase == Phase.CHOOSING) send(NavIntent.End)
    }

    // ------------------------------------------------------------------ test extras (5.4 item 12)

    /**
     * `--es dest "lat,lon,name"` with optional `--es origin "lat,lon,heading"`, `--es simulate <script>`
     * and `--es addstop "lat,lon,name" --ef addstop_at_m <m>`: plans the trip once the installed maps are
     * scanned, starts the simulated drive when the choices are ready, and adds the stop when the
     * simulated car has driven [addStopAtM] metres along its route.
     */
    fun startTestTrip(dest: Place, origin: Origin.FromPlace?, simulate: String?, addStop: Place?, addStopAtM: Double, speedFactor: Double = 1.0) {
        testJobs.forEach { it.cancel() }
        testJobs = listOf(g.scope.launch {
            g.installed.installed.first { it.scanned }
            if (g.driving()) send(NavIntent.End)
            closeSearchAndList()
            closeSheet()
            send(NavIntent.PlanTo(dest))
            origin?.let { send(NavIntent.SetOrigin(it)) }
            if (simulate == null) return@launch
            val ready = withTimeoutOrNull(180_000) {
                g.session.state.first { it.phase == Phase.CHOOSING && !it.replanning && it.selectedChoiceId != null && it.problem == null }
            }
            if (ready == null) {
                Log.w(TAG, "test trip: no route choice within 180 s; not started")
                return@launch
            }
            Log.i(TAG, "test trip: starting the simulated drive") // plain literal
            send(NavIntent.Start(Simulation(scriptName = simulate, speedFactor = speedFactor)))
            if (addStop == null) return@launch
            val at = g.session.state.first { s ->
                val gd = s.guidance
                s.phase == Phase.IDLE || (gd != null && gd.simulated && gd.route.lengthM - gd.remainingM >= addStopAtM)
            }
            if (at.phase == Phase.IDLE) return@launch
            Log.i(TAG, "test trip: adding the stop") // plain literal
            send(NavIntent.AddStop(addStop))
        })
    }

    companion object {
        const val TAG = "Beamline"
        const val SAVED_PIN = "saved:"
        /** MapSceneMapper's RESULT pins: "result:<index into SearchFeature.results>" (S11). */
        const val RESULT_PIN = "result:"

        const val DROPPED_PIN = "Dropped pin"

        /**
         * A dropped pin's line once its street is known: "Near West 8th Street, Erie", with the nearest
         * [house] number of that street when the pack knows one close by: "Near 10 North Park Row, Erie"
         * ([town]: the street's municipality and county as the pack writes them, "Erie, Erie County"; its
         * first part is said).
         */
        fun nearStreetText(street: String, town: String, house: String? = null): String {
            val first = town.substringBefore(',').trim()
            val where = if (house.isNullOrBlank()) street else "${house.trim()} $street"
            return if (first.isEmpty()) "Near $where" else "Near $where, $first"
        }

        /** "42.13646, -80.08718" (five decimals, as the map pick writes a point). */
        fun coordinates(p: GeoPoint): String = String.format(Locale.US, "%.5f, %.5f", p.lat, p.lon)
    }
}

/** Batch O3: poi_icons.json map_kinds, the tiles' kind -> the sprite the map draws it with (other kinds: the kind itself). */
private val MAP_KIND_ICONS: Map<String, String> by lazy {
    try {
        (io.github.spysyweeb.beamline.search.RuleFiles.json(io.github.spysyweeb.beamline.search.RuleFiles.POI_ICONS)["map_kinds"]
            as kotlinx.serialization.json.JsonObject).filterKeys { !it.startsWith("_") }
            .mapValues { (it.value as kotlinx.serialization.json.JsonPrimitive).content }
    } catch (_: Exception) {
        emptyMap()
    }
}

/** Batch O1's short messages (docs/online-design.md 4.1, 4.5). */
const val NOTE_SENDING = "Sending your note..."
const val NOTE_SENT = "Note sent to OpenStreetMap"
const val NOTE_ALREADY = "You already reported this here. See My reports."
const val NOTE_WAITING = "No connection: the note waits in My reports and goes when you're online"
const val NOTE_REFUSED = "OpenStreetMap did not accept a note. See My reports."
const val NOTE_SWITCH_OFF = "Contribute to OpenStreetMap is off: the note waits in My reports"
const val EDIT_REVIEW = "A place changed on OpenStreetMap meanwhile: look at your edit again in My reports"
const val SIGNED_IN = "Signed in to OpenStreetMap"
const val SIGN_IN_FAILED = "The sign-in to OpenStreetMap did not finish. Try again from Settings."
const val SIGN_IN_SWITCH_OFF = "Turn on Contribute to OpenStreetMap in Settings first"
