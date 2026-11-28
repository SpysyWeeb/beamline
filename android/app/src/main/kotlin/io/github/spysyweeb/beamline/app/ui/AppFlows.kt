package io.github.spysyweeb.beamline.app.ui

import io.github.spysyweeb.beamline.app.online.AddSession
import io.github.spysyweeb.beamline.app.online.ArrivalAsker
import io.github.spysyweeb.beamline.app.online.ArrivalInput
import io.github.spysyweeb.beamline.app.online.EditFocus
import io.github.spysyweeb.beamline.app.online.EditSession
import io.github.spysyweeb.beamline.app.online.HoursCheck
import io.github.spysyweeb.beamline.app.online.HoursTarget
import io.github.spysyweeb.beamline.app.online.PlaceQuestions
import io.github.spysyweeb.beamline.app.online.Undo
import io.github.spysyweeb.beamline.app.online.UploadAsk
import io.github.spysyweeb.beamline.app.online.UploadChecks
import io.github.spysyweeb.beamline.feature.drive.ArrivalAnswer
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
import io.github.spysyweeb.beamline.api.nav.TripState
import io.github.spysyweeb.beamline.nav.StopLeg
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
import io.github.spysyweeb.beamline.app.handover.Handover
import io.github.spysyweeb.beamline.app.handover.HandoverLink
import io.github.spysyweeb.beamline.app.handover.HandoverNote
import io.github.spysyweeb.beamline.app.handover.HandoverRules
import io.github.spysyweeb.beamline.app.handover.HandoverTexts
import io.github.spysyweeb.beamline.app.handover.LinkTickets
import io.github.spysyweeb.beamline.search.Geo
import io.github.spysyweeb.beamline.search.MultiSearchResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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

    /** Play test P28: leaves the app as the system's Back does (the task to the back; guidance and the trip go on). */
    fun leaveApp()
}

/**
 * One offer to take something back (0.1.40, one mechanism for the app: a removed stop, play test P10 and
 * audit F30; a removed saved place, P58): its words, its number ([seq]: each offer restarts the bar's
 * time), whether it shows in the stops editor's note line while the editor is open ([inStopsEditor]),
 * whether it goes when the trip leaves IDLE ([idleOnly]: an offer about browsing), whether Undo can still
 * do something for the trip as it is now ([stillUseful]), and what Undo does.
 */
class UndoOffer(
    val text: String,
    val seq: Long,
    val inStopsEditor: Boolean,
    val idleOnly: Boolean,
    val stillUseful: (TripState) -> Boolean,
    val undo: () -> Unit,
)

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

    /** P21: the route step the map marks and the route sheet shows (AppRules.shownStep); null: none. */
    var shownStep by mutableStateOf<ShownStep?>(null)
        private set

    /** The Undo offer standing (W3: the app's one Undo); null: none. Each offer replaces the one before and restarts its time (AppRules.UNDO_BAR_MS). */
    var undoOffer by mutableStateOf<UndoOffer?>(null)
    private var undoSeq = 0L

    /** Play test P31, audit F34: the saved place made at arrival for the trip's destination (its id), until the trip leaves ARRIVED. */
    var arrivalSavedId by mutableStateOf<String?>(null)

    /** P28: when the first Back of a pair was pressed (monotonic ms); null: none pending. */
    var backArmedAtMs by mutableStateOf<Long?>(null)

    /** P28: the trip's phase when that first Back was pressed (AppRules.backArmKept). */
    var backArmedIn: Phase? = null
        private set

    /** P46: the way back from a route preview to the place window whose Directions planned it. */
    private val preview = PreviewBack()
    var confirm by mutableStateOf<ConfirmUi?>(null)
    var snackbar by mutableStateOf<String?>(null)

    /** A new note waiting for the public-note notice's "Send" or "Not now" (docs/online-design.md section 3). */
    var notePending by mutableStateOf<OutboxItem?>(null)

    /** Batch O2: the edit being made (Layer.EditPlace). */
    var edit by mutableStateOf<EditSession?>(null)
    /** Signed out, "Edit on OpenStreetMap" was tapped: the sign-in dialog for this place (answer 6). */
    var signInFor by mutableStateOf<EditTarget?>(null)
    /** The edit to open once the sign-in it waited for has come back. */
    private var afterSignIn: EditTarget? = null

    /** Batch O3: the sprite icon of the map label tapped last (its tile kind; poi_icons.json map_kinds). */
    private var tappedIcon: String? = null
    /** The window that tap opened: only its edit carries [tappedIcon] (AppRules.sheetIcon). */
    private var tappedPlace: Place? = null

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
    /** What the End bar says: [TRIP_ENDED] or [DIRECTIONS_CLOSED] (audit F31). */
    var endedText by mutableStateOf(TRIP_ENDED)

    /** R4: Undo was tapped and the session is still IDLE re-planning ("Continuing the trip..."). */
    var continuing by mutableStateOf(false)

    /** The splash reveal plays once per process (A's README). */
    var revealDone by mutableStateOf(false)

    /**
     * P39: the drive overlay is on screen with its dark banner or re-route card at the top
     * (AppRules.driveDarkTop). Written by AppRoot after each composition; read by MainActivity after a
     * rotation. Plain: nothing recomposes on it.
     */
    var driveDarkTop = false

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

    /** S1b (0.1.40, P6): the user moved the map by hand since its window last held their position (K1 and S11 moves are the app's). */
    private var mapMovedByUser = false

    /** Where the camera came to rest (the map's idle callback, once the camera was placed); [bounds]: the part the user sees. */
    fun onCameraRest(center: GeoPoint, zoom: Double, bounds: Bounds? = null) {
        cameraCenter = center
        cameraZoom = zoom
        cameraBounds = bounds
        // P6: the user in sight again (a drag back, or my-location's follow): a later app move away is not theirs
        // (near is another module's property: no smart cast)
        if (bounds != null && searchContext().takeIf { it.source == NearSource.GPS }?.near?.let { bounds.contains(it) } == true) mapMovedByUser = false
        refreshNoMapHere()
        // S11b: the user dragged or zoomed the map over results: offer them again in this window
        if (movedOverResults && bounds != null && g.search.results.value != null && g.search.results.value?.stop == null &&
            areaSearch != AreaSearch.SEARCHING) {
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

    /** Item 54b (rule K8): the route ahead the camera looks along while an Add stop search is open; null: none. */
    private val addStopView = MutableStateFlow<List<GeoPoint>?>(null)
    private val aheadKm = SearchAppRules.bundled().alongAheadKm

    /** Item 54b: the route ahead of the trip as it is now (AppRules.addStopView of StopLeg.lineAhead), taken when the search opens. */
    private fun addStopViewNow(): List<GeoPoint>? =
        AppRules.addStopView(StopLeg.lineAhead(g.session.state.value), AppRules.ADD_STOP_VIEW_STEP_KM, aheadKm)

    /** Item 54b: the route ahead goes with the last Add stop search on the stack. */
    private fun addStopClosed() {
        if (stack.none { it is Layer.Search && it.mode == SearchMode.ADD_STOP }) addStopView.value = null
    }

    init {
        // S11: the answers that come after Enter (the results, or the one match picked)
        g.scope.launch { g.search.later.collect { onSearchOutput(it) } }
        // S11: the results are a browsing view: a trip planned or guided ends them (item 51: not the stop
        // results, which belong to the trip). P46: Back from directions puts back the results that were
        // behind the window once the route has ended. P21: the step shown belongs to the preview's route.
        g.scope.launch {
            var prev = g.session.state.value.phase
            g.session.state.collect { s ->
                val r = g.search.results.value
                if (s.phase != Phase.IDLE && s.phase != Phase.RESUME_OFFERED && r != null && r.stop == null) closeResults()
                preview.onPhase(prev, s.phase)?.let(::restoreAfterBack)
                val previewing = s.phase == Phase.PLANNING || s.phase == Phase.CHOOSING
                shownStep?.let { st -> if (!previewing || s.selectedChoiceId != st.choiceId) clearStep(recenter = false) }
                // item 51: adding a stop belongs to a trip being planned or driven (an arrival or the end ends it)
                if (r?.stop != null && s.phase !in STOP_PHASES) endStopFlow()
                // item 54b (open question 7): an arrival or the trip's end closes the Add stop search too
                if (AppRules.addStopEnds(s.phase, stack.toList())) closeSearchAndList()
                // item 52 (K16): a place handed over by another app is a recent once the user drives to it
                if (s.phase == Phase.GUIDING) linkRecent?.let { lr ->
                    linkRecent = null
                    if (HandoverRules.guidedTo(lr, s.phase, s.stops.lastOrNull()?.place?.point)) g.recents.add(lr)
                }
                prev = s.phase
            }
        }
        // item 51 (rule S12): the session's detours and the looked-at stop's route, for the stop results
        g.scope.launch { g.session.state.map { it.detours }.distinctUntilChanged().collect { g.search.onDetours(it) } }
        g.scope.launch {
            g.session.state.map { it.stopPreview }.distinctUntilChanged().collect { g.search.onStopPreview(it, System.currentTimeMillis(), ZoneId.systemDefault()) }
        }
        // item 51: the one teardown. Every way the stop results end (X, Back, Add stop, a new search, a state
        // removed, the phase guard above) shows here as their request id going away: that request is dropped;
        // the preview and the camera's stop view end unless newer stop results took over (they sent their own).
        g.scope.launch {
            var shownId: Long? = null
            g.search.results.map { it?.stop?.requestId }.distinctUntilChanged().collect { id ->
                val end = AppRules.stopResultsEnded(shownId, id)
                shownId = id
                if (end != null) {
                    send(NavIntent.Detours(end.requestId, emptyList()))   // drops only that request (C8): never a newer one
                    // the camera's stop view ends in the stop-view collector below (item 54b: unless the route ahead takes over)
                    if (end.all) send(NavIntent.PreviewStop(null))
                }
            }
        }
        // item 51: the list shows (sorted, or nearest first after the wait): what is still being worked out is dropped
        g.scope.launch {
            var working: Long? = null
            g.search.results.map { r -> r?.stop?.takeIf { it.working }?.requestId }.distinctUntilChanged().collect { id ->
                val was = working
                working = id
                if (was != null && id == null && g.search.results.value?.stop?.requestId == was) send(NavIntent.Detours(was, emptyList()))
            }
        }
        // item 51 (rule K8), item 54b: the camera looks at the stop results, the way to the one looked at, or the route
        // ahead while Add stop is typed in; this collector alone starts and ends the view (AppRules.stopViewCall:
        // typing, the results and typing again follow each other with no end between them)
        g.scope.launch {
            var viewing = false
            combine(g.search.results, g.session.state.map { it.stopPreview }.distinctUntilChanged(), addStopView) { r, p, typing ->
                AppRules.stopView(r, p, g.session.state.value, typing)
            }.distinctUntilChanged { a, b -> a?.points?.toSet() == b?.points?.toSet() && a?.paddingDp == b?.paddingDp }
                .collect { v ->
                    // AF-3: a route step shown on the route sheet (P21) is shown again when adding a stop ends
                    val step = shownStep?.takeIf { AppRules.shownStep(it, g.session.state.value) != null }?.point
                    when (val c = AppRules.stopViewCall(viewing, v, step)) {
                        is StopViewCall.Show -> {
                            viewing = true
                            g.director.onStopView(c.view.points, c.view.paddingDp)
                        }
                        is StopViewCall.End -> {
                            viewing = false
                            g.director.onStopView(emptyList())
                            c.reshow?.let(g.director::onShowPlace)
                        }
                        StopViewCall.None -> Unit
                    }
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
                // P8: a window open over them keeps its camera (the close stays first, it has the side effect)
                if (g.search.closeResultsGoneFrom(set) && g.sheetPlace.value == null) g.director.onPlaceLeft()
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
     * another kind of search (a start, a stop, a save): [searchContext] as before. Since 0.1.40 (P6) only
     * after the user's own move ([mapMovedByUser]), see AppRules.searchContextFor.
     */
    private fun searchContextFor(mode: SearchMode): SearchContext =
        AppRules.searchContextFor(mode, searchContext(), cameraBounds, cameraCenter, mapMovedByUser)

    private fun destinationFor(mode: SearchMode): Place? =
        if (mode == SearchMode.SET_START) g.session.state.value.stops.lastOrNull()?.place else null

    /**
     * Search in GO_TO (the pill), ADD_STOP (DriveEvent.SearchForStop) or SET_START (DriveEvent.ChooseStart). Item 52: [near],
     * the point a handed-over link gave (the rows are ranked there); [onePick]: the search a link's words opened during a
     * trip, where a row is one place for the stop card (no results on the map, as while driving).
     */
    fun openSearch(mode: SearchMode, typed: String? = null, near: GeoPoint? = null, onePick: Boolean = false) {
        val layer = Layer.Search(nextSearchId++, mode, forSave = false)
        openSession(mode, near, onePick)
        searchOwner = layer.id
        stack.add(layer)
        // item 54b (rule K8): the camera looks along the route ahead the search looks along (taken as it opens)
        if (mode == SearchMode.ADD_STOP) addStopView.value = addStopViewNow()
        // from the bar that holds the words of the results on the map: the search opens with them typed
        typed?.takeIf { it.isNotBlank() }?.let { g.search.onEvent(SearchEvent.QueryChanged(it)) }
    }

    fun onSearchEvent(e: SearchEvent) {
        // the results that come next are a type's of the "View all" grid, or not: Back from them returns there
        when (e) {
            is SearchEvent.TypeTapped -> resultsFromGrid = stack.lastOrNull().let { it is Layer.Search && it.mode == SearchMode.GO_TO }
            // P44: a typed list's row now puts that list on the map: Back from it must not reopen an older grid
            is SearchEvent.ShortcutTapped, is SearchEvent.QueryChanged, SearchEvent.Submit, is SearchEvent.RowTapped -> resultsFromGrid = false
            else -> Unit
        }
        g.search.onEvent(e)?.let(::onSearchOutput)
    }

    /** The results on the map are a place type's, picked in the "View all" grid ([backFromResults]). */
    private var resultsFromGrid = false

    private fun onSearchOutput(o: SearchOutput) {
        // item 52 (H7, K10): a place picked in the search a link's words opened, during a trip, opens the stop card
        val fromLink = linkSearchId != null && (stack.lastOrNull() as? Layer.Search)?.let { it.id == linkSearchId && it.mode == SearchMode.GO_TO } == true &&
            HandoverRules.pickToCard(phase)
        when (o) {
            is SearchOutput.Picked -> {
                closeSearchAndList()
                when (o.mode) {
                    SearchMode.GO_TO -> if (fromLink) openLinkPlace(o.place) else showPicked(o.place)
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
                if (fromLink) openLinkPlace(o.place) else planTrip(o.place)
            }
            is SearchOutput.ShowPlace -> {
                closeSearchAndList()
                stack.remove(Layer.Settings)
                if (fromLink) openLinkPlace(o.place) else showPicked(o.place)
            }
            is SearchOutput.OpenSavedPlaces -> {
                g.savedFeature.openList(o.mode, searchContext(), destinationFor(o.mode))
                stack.add(Layer.SavedList)
            }
            is SearchOutput.SaveNew -> {
                popSearch()
                g.savedFeature.openSave(o.place)
                // P18: "Use my position" (a point): the dialog shows and saves the street it is near
                val p = o.place
                if (AppRules.lacksStreet(p)) lookUpStreet(p.point) { g.savedFeature.streetFound(p.point, it) }
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
            is SearchOutput.ShowStopResults -> {
                // item 51 (rule S12): the places for a new stop on the map, over the trip; the session works out
                // what each adds, and the route with the one picked (null ends an older list's look)
                closeSearchAndList()
                g.sheetPlace.value = null
                send(NavIntent.Detours(o.requestId, o.places))
                send(NavIntent.PreviewStop(o.selected?.let { o.places.getOrNull(it) }))
            }
            // item 51: a stop result looked at (its card), or its card closed (null)
            is SearchOutput.PreviewStop -> send(NavIntent.PreviewStop(o.place))
            // item 51: "Add stop" on the card; the stop results have ended (the teardown collector does the rest)
            is SearchOutput.AddStop -> send(NavIntent.AddStop(o.place))
            // item 52 (H7): "Go here instead" on a handed-over place's card: Directions' own rule (S7: while guiding, "End this trip ...?")
            is SearchOutput.GoInstead -> planTrip(o.place)
            SearchOutput.StartFromYourPosition -> {
                // P22: Choose start's "Your position": the trip starts from the current position again
                closeSearchAndList()
                if (g.session.state.value.origin != Origin.CurrentPosition) send(NavIntent.SetOrigin(Origin.CurrentPosition))
            }
        }
    }

    /** Pops the top search layer; a search below it whose session was replaced is opened again. */
    private fun popSearch() {
        val i = stack.indexOfLast { it is Layer.Search }
        if (i >= 0) stack.removeAt(i)
        addStopClosed()
        reopenTopSearch()
    }

    private fun reopenTopSearch() {
        val top = stack.lastOrNull()
        if (top is Layer.Search && top.id != searchOwner && !top.forSave) {
            openSession(top.mode)
            searchOwner = top.id
        }
    }

    /**
     * The search session for [mode]; ADD_STOP looks along the trip's route ahead from where a new stop joins it (item 51,
     * rule S12). Item 52: with [near], ranked around that point (distances still from a fresh fix); [onePick] as openSearch.
     */
    private fun openSession(mode: SearchMode, near: GeoPoint? = null, onePick: Boolean = false) {
        val trip = g.session.state.value
        val stop = mode == SearchMode.ADD_STOP
        val context = if (near != null) {
            val searchable = g.installed.installed.value.packs.filter { it.searchFile != null }.map { it.bounds }
            HandoverRules.linkContext(near, searchable, from = searchContext().takeIf { it.source == NearSource.GPS }?.near)
        } else searchContextFor(mode)
        g.search.open(mode, context, driving = g.driving() || onePick, destination = destinationFor(mode),
            along = if (stop) StopLeg.lineAhead(trip) else emptyList(), alongAfter = if (stop) StopLeg.joinsAfter(trip)?.name else null)
    }

    private fun closeSearchAndList() {
        val hadSearch = stack.any { it is Layer.Search }
        stack.removeAll { it is Layer.Search || it == Layer.SavedList }
        addStopClosed()
        if (hadSearch) g.search.close()
    }

    // ------------------------------------------------------------------ the results on the map (S11)

    fun onResultsEvent(e: ResultsEvent) {
        if (g.search.results.value?.stop != null) {
            // item 51: the stop results' X ends adding a stop (the teardown collector tells the session and the camera)
            if (e == ResultsEvent.Close) return endStopFlow()
            g.search.onResultsEvent(e)?.let(::onSearchOutput)
            return
        }
        if (e == ResultsEvent.Close) return backFromResults()
        g.search.onResultsEvent(e)?.let(::onSearchOutput)
    }

    /** Item 51: the stop results end (the trip as before); the init's teardown collector does the rest. */
    private fun endStopFlow() {
        g.search.onResultsEvent(ResultsEvent.Close)
    }

    /**
     * Item 51, Back on the stop results: from a card, the list (a list of one place has nothing to choose:
     * as from the list); from the list, the Add stop search again with its words typed ("" after a
     * shortcut, a type or a single place).
     */
    private fun backFromStop() {
        val r = g.search.results.value ?: return
        val stop = r.stop ?: return
        // item 52: a place handed over by another app has no list or search behind it: Back ends it
        if (stop.instead) return endStopFlow()
        if (r.selected != null && r.places.size > 1) return onResultsEvent(ResultsEvent.Deselect)
        val typed = stop.typed
        // item 54b: the camera goes from the results straight back to the route ahead (never the follow in between)
        addStopView.value = addStopViewNow()
        endStopFlow()
        openSearch(SearchMode.ADD_STOP, typed = typed)
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

    /** The results' pins and list gone; the camera stays where it is (a place window open over them keeps its camera, K1, K1b). */
    fun closeResults() {
        resultsFromGrid = false
        g.search.onResultsEvent(ResultsEvent.Close)
        // P8: the director's results ended when a window opened over them: then nothing to end here
        if (g.sheetPlace.value == null) g.director.onPlaceLeft()
    }

    /**
     * P46: the results that were behind the reopened window, back under it (hidden while it shows, AppRoot's
     * resultsShown; closing it deselects and fits them, closeSheet). Only while that window still shows: one
     * closed or replaced meanwhile leaves them gone. No fit now: the camera stays on the place (K2a).
     */
    private fun restoreAfterBack(way: PreviewBack.Way) {
        val r = way.results ?: return
        if (g.sheetPlace.value != way.place) return
        if (g.search.restoreResults(r)) resultsFromGrid = way.fromGrid
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
                    // item 51: a saved place for a stop opens its card on the map like any row (nothing changes until "Add stop")
                    SearchMode.ADD_STOP -> onSearchOutput(g.search.showStopPlace(o.place, searchContext()))
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
                // play test P31: a save of the destination while arrived (the card's link or the place's own window)
                val trip = g.session.state.value
                if (AppRules.savedAtArrival(trip.phase, trip.stops.lastOrNull()?.place, o.place)) arrivalSavedId = o.place.id
            }
            is SavedOutput.PickEnded -> Unit   // what the pick hid shows again by itself (SP25)
            is SavedOutput.Removed -> {
                // P58: a saved place removed: the shared Undo bar ("Home removed"), only while no trip runs (SP4)
                val id = o.place.id
                val name = o.place.name
                // the window of that saved place: its own window closes; a saved dropped pin's says "Save" again
                val window = g.sheetPlace.value?.takeIf { it.savedId == id }
                window?.let { if (it.source == PlaceSource.SAVED) closeSheet() else g.sheetPlace.value = it.copy(savedId = null) }
                val screens = stack.toList()
                offerUndo(AppRules.removedText(name), idleOnly = true) {
                    onSavedEvent(SavedEvent.UndoRemove(id))
                    val back = g.saved.byId(id)
                    if (back == null) snackbar = AppRules.notRestoredText(name)   // its Home slot or name was taken meanwhile
                    else AppRules.windowAfterUndo(window, g.sheetPlace.value, stack.toList() == screens, back)?.let { g.sheetPlace.value = it }
                }
            }
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
        // P17/P18: a point still showing its coordinates: its street from the packs
        if (AppRules.lacksStreet(place)) lookUpStreet(place.point) { line ->
            // still this point's window (saved meanwhile too): its line; its height stays (F42, placeSheetKey)
            g.sheetPlace.value?.takeIf { AppRules.sameSheetPlace(it, place) }?.let { g.sheetPlace.value = it.copy(subtitle = line) }
            // a save dialog opened from it shows and saves the street too
            g.savedFeature.streetFound(place.point, line)
        }
    }

    /**
     * P17/P18: the street at [point] from the installed packs, read off the main thread (as the dropped pin did);
     * [then] gets its line ("Near East 5th Street, Erie") on the main thread, and never runs when no street is
     * near. Nothing is logged (PV2).
     */
    private fun lookUpStreet(point: GeoPoint, then: (String) -> Unit) {
        g.scope.launch {
            val s = runCatching { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) { g.streetAt(point) } }.getOrNull() ?: return@launch
            then(nearStreetText(s.name, s.subtitle, s.house))
        }
    }

    /**
     * A place picked from a search, the saved places, the recents or a sheet: its sheet, and the
     * camera goes to it (K1, docs/next-round.md item 2). A place tapped on the map is on screen
     * already: [showTapped].
     */
    fun showPicked(place: Place) {
        showSheet(place)
        g.director.onShowPlace(place.point)
    }

    /**
     * K1b (P8): a place tapped on the map (a POI label, a saved place's marker, a dropped pin): its
     * sheet; the camera stays unless the sheet or the search bar would cover it (CameraDirector.onPlaceTapped).
     */
    private fun showTapped(place: Place) {
        showSheet(place)
        g.director.onPlaceTapped(place.point)
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
                    // P46: Back from this preview comes back to this window and to the results behind it:
                    // a copy is kept (PreviewBack); the live ones close as before, so nothing hidden lingers
                    val way = PreviewBack.Way(ui.place, g.search.results.value, resultsFromGrid)
                    closeResults()
                    closeSheet()
                    planTrip(ui.place, way)
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
                // 0.1.41 (docs/online-design.md 4.8, AP10): "Hours still right?" at the details height: Yes confirms in
                // one tap; "Edit hours" opens the form at its hours, made at the place (its at-the-place test just passed)
                PlaceAction.CONFIRM_HOURS -> if (ui.canCheckHours) PlaceQuestions.hoursTarget(ui, sheetIcon(ui))?.let(::confirmHours)
                PlaceAction.EDIT_HOURS -> if (ui.canCheckHours) editOnOsm(ui, EditFocus.HOURS, atPlace = true)
            }
        }
    }

    /** "Directions" and a one-tap PlanTrip, by phase (rule S7, SP18), location asked first. */
    fun planTrip(place: Place) = planTrip(place, null)

    /** [way] (P46): Back from the preview returns to the window whose Directions this was; null: planned another way. */
    private fun planTrip(place: Place, way: PreviewBack.Way?) {
        val h = host
        if (h == null) planByPhase(place, way) else h.requestLocation { planByPhase(place, way) }
    }

    private fun planByPhase(place: Place, way: PreviewBack.Way?) {
        val trip = g.session.state.value
        when (trip.phase) {
            Phase.IDLE, Phase.PLANNING, Phase.CHOOSING -> {
                preview.planned(way)   // P46: null for a trip planned another way: Back closes it with Undo
                send(NavIntent.PlanTo(place))
            }
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
        if (gesture == MapGesture.CameraMovedByUser) mapMovedByUser = true   // P6: S1b follows the user's own moves only
        creditUntilMs = 0L // the first touch of the map fades the credit (item 8)
        // item 54b (rule S12): while the Add stop search is typed in over the map, pans and pinches move it (K8), taps do nothing
        if (AppRules.addStopOverMap(stack.lastOrNull(), g.search.ui.value.allTypes != null)) return
        val picking = g.savedFeature.ui.value.mapPick != null
        // item 51: while the stop results show only their pins act: no window, no dropped pin over the stop window
        val stopResults = g.search.results.value?.stop != null
        when (gesture) {
            is MapGesture.Tap -> when {
                // SP16, SP24: during a map pick every tap is the pick; no sheet, no dropped pin. P18: a point's bar
                // shows the street it is near once the packs answer (a later tap's pick drops an older answer)
                picking -> {
                    g.savedFeature.onMapTap(gesture.point, gesture.poi, cameraZoom)
                    if (gesture.poi == null) lookUpStreet(gesture.point) { g.savedFeature.streetFound(gesture.point, it) }
                }
                gesture.pinId?.startsWith(RESULT_PIN) == true ->
                    gesture.pinId!!.removePrefix(RESULT_PIN).toIntOrNull()?.let { onResultsEvent(ResultsEvent.Select(it)) }
                stopResults -> Unit
                gesture.pinId?.startsWith(SAVED_PIN) == true ->
                    g.saved.byId(gesture.pinId!!.removePrefix(SAVED_PIN))?.let { showTapped(it.toPlace()) }
                // P23: another route tapped on the map is chosen, as its card is; the camera stays where the user looks.
                // Never during item 51's stop preview (its line under the new route is the trip's own, AlternativeLine.tripRoute)
                gesture.pinId?.startsWith(ROUTE_PIN) == true -> gesture.pinId!!.removePrefix(ROUTE_PIN).toLongOrNull()?.let { id ->
                    val trip = g.session.state.value
                    if ((trip.phase == Phase.PLANNING || trip.phase == Phase.CHOOSING) && trip.stopPreview == null && trip.selectedChoiceId != id) {
                        clearStep(recenter = false)
                        send(NavIntent.Choose(id))
                    }
                }
                gesture.pinId != null -> Unit   // a stop, the destination, the start or the picked pin: nothing
                gesture.poi != null -> {
                    // batch O3: the label's own icon, for the own-changes layer if the user edits it
                    tappedIcon = gesture.poi!!.kind.takeIf { it.isNotEmpty() }?.let { k -> MAP_KIND_ICONS[k] ?: k }
                    val place = Place(gesture.poi!!.name, gesture.poi!!.point, PlaceSource.MAP_POI)
                    tappedPlace = place
                    showTapped(place)
                }
                else -> closeSheet()
            }
            is MapGesture.LongPress ->
                if (picking) {
                    g.savedFeature.onMapTap(gesture.point, null, cameraZoom)
                    lookUpStreet(gesture.point) { g.savedFeature.streetFound(gesture.point, it) }   // P18
                } else if (!stopResults) {
                    // the coordinates first; showSheet asks for the street it is on (P17: kept across a quick Save)
                    showTapped(Place(DROPPED_PIN, gesture.point, PlaceSource.MAP_PIN, subtitle = coordinates(gesture.point)))
                }
            MapGesture.CameraMovedByUser -> Unit
        }
    }

    // ------------------------------------------------------------------ the drive overlay (item 6)

    fun onDriveEvent(e: DriveEvent) {
        when (e) {
            is DriveEvent.Nav -> {
                // P21: a route chosen or closed lets the step shown go (a Choose recenters below anyway)
                if (e.intent is NavIntent.Choose || e.intent is NavIntent.End) clearStep(recenter = false)
                if (e.intent is NavIntent.Start || e.intent is NavIntent.Resume) host?.requestNotificationsOnce()
                val endsGuidance = e.intent is NavIntent.End && (phase == Phase.GUIDING || phase == Phase.REROUTING)
                val closesRoute = e.intent is NavIntent.End && (phase == Phase.PLANNING || phase == Phase.CHOOSING)
                if (e.intent is NavIntent.End) stopsEditorOpen = false
                // P46: X closes the route with Undo as before (Undo plans it again with no way back to a window)
                if (closesRoute) preview.forget()
                // play test P10, audit F30: a removed stop can be taken back for a few seconds
                val removed = (e.intent as? NavIntent.RemoveStop)?.let { r ->
                    g.session.state.value.stops.getOrNull(r.index)?.takeIf { !it.reached }?.let { it.place to r.index }
                }
                send(e.intent)
                // audit F29: a route card chosen (again) looks at the whole route, after a step or a pan
                if (e.intent is NavIntent.Choose && (phase == Phase.PLANNING || phase == Phase.CHOOSING)) g.director.onRecenter()
                // R4: one tap ends the trip at once; the bar offers Undo for END_UNDO_MS (audit F31: a route
                // not yet started too, "Directions closed")
                if (endsGuidance || closesRoute) offerEndUndo(if (endsGuidance) TRIP_ENDED else DIRECTIONS_CLOSED)
                removed?.let { (place, index) ->
                    offerUndo(AppRules.STOP_REMOVED, inStopsEditor = true, stillUseful = { t -> AppRules.undoRemovedStop(place, index, t) != null }) {
                        AppRules.undoRemovedStop(place, index, g.session.state.value)?.let(::send)
                    }
                }
            }
            DriveEvent.Recenter -> g.director.onRecenter()
            DriveEvent.ShowOverview -> g.director.onOverview()
            is DriveEvent.ShowStep -> {
                // item 47, P21: the step on the map, marked, and the sheet says which one it is
                // (locals: ShowStep.index and TripState.selectedChoiceId are other modules' properties, no smart cast)
                val idx = e.index
                val id = g.session.state.value.selectedChoiceId
                if (idx != null && id != null) {
                    shownStep = ShownStep(id, idx, e.point)
                    g.stepMark.value = e.point
                }
                g.director.onShowPlace(e.point)
            }
            // P21: the step let go (Back at full height, the peek's chip): the whole route again
            DriveEvent.CloseStep -> clearStep(recenter = true)
            // P10, F30: the Undo in the stops editor's note line
            DriveEvent.UndoStopRemoval -> takeUndo()
            DriveEvent.OpenStopsEditor -> stopsEditorOpen = true
            DriveEvent.CloseStopsEditor -> stopsEditorOpen = false
            // item 47: the arrival card raised opens the destination's window; its Save, the save dialog
            DriveEvent.ShowDestination -> g.session.state.value.stops.lastOrNull()?.place?.let(::showSheet)
            DriveEvent.SaveDestination -> if (!destinationSaved()) g.session.state.value.stops.lastOrNull()?.place?.let { place ->
                // P31: once saved the card says "Saved" and cannot save again
                saveFrom = place
                g.savedFeature.openSave(place)
                // P18: a pin destination still on its coordinates: the dialog shows and saves its street
                if (AppRules.lacksStreet(place)) lookUpStreet(place.point) { g.savedFeature.streetFound(place.point, it) }
            }
            // 0.1.41 (docs/online-design.md 4.8, AP7): the arrival's question answered; nothing more is asked this
            // arrival; the answer, whatever it is, is the one thing kept on disk (AP6, plan AP-6: 90 days)
            is DriveEvent.AnswerArrival -> {
                val s = arrivalAsker.answer()
                arrivalShown = null
                val sheet = arrivalSheet
                if (s != null) g.markAnswered(s.osm, LocalDate.now(ZoneId.systemDefault()))
                if (s != null && sheet != null) when (e.answer) {
                    ArrivalAnswer.YES -> if (s.ask == PlaceQuestions.Ask.CHECK_HOURS) {
                        PlaceQuestions.hoursTarget(sheet, sheet.iconName, hours = s.hours)?.let(::confirmHours)
                    }
                    ArrivalAnswer.EDIT -> editOnOsm(sheet, PlaceQuestions.focusOf(s.ask), atPlace = true)
                    ArrivalAnswer.NOT_NOW -> Unit
                }
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
                // 53e: a limit picked while driving was picked at the road: made at the place
                state = OutboxState.REVIEW, speedMph = mph, atPlace = true,
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

    /** "Upload" on the speed limit's summary: 53e's asks first ([beginUpload]). */
    fun uploadSpeed() {
        val s = speed ?: return
        beginUpload(g.onSite.madeAt(s)) { s.upload(it) }
    }

    /** R4: Undo on the "Trip ended" bar: the same trip again, re-planned from here (the session decides). */
    fun undoEnd() {
        // "Continuing the trip..." only for a guided trip (a closed route is planned again at once)
        continuing = endedText == TRIP_ENDED
        tripEnded = null
        send(NavIntent.UndoEnd)
    }

    /** R4 and audit F31: the End bar ("Trip ended" or "Directions closed") with Undo; the newest bar wins (W4). */
    private fun offerEndUndo(text: String) {
        endedText = text
        tripEnded = (tripEnded ?: 0L) + 1
        continuing = false
        undoOffer = null
    }

    /**
     * W3: a new Undo offer (a removed stop, P10; a removed saved place, P58), shown for AppRules.UNDO_BAR_MS
     * (longer when the phone's accessibility settings ask for it); it replaces the one before, and an End
     * bar still showing goes (the newest bar wins).
     */
    fun offerUndo(text: String, inStopsEditor: Boolean = false, idleOnly: Boolean = false, stillUseful: (TripState) -> Boolean = { true }, undo: () -> Unit) {
        undoOffer = UndoOffer(text, ++undoSeq, inStopsEditor, idleOnly, stillUseful, undo)
        tripEnded = null
    }

    /** Undo on the offer's bar or in the stops editor's note line: done once, and only while it can still do something. */
    fun takeUndo() {
        val o = undoOffer ?: return
        undoOffer = null
        if (o.stillUseful(g.session.state.value)) o.undo()
    }

    /** P31, F34: whether the trip's destination is one of the saved places (the arrival card's "Saved"). */
    fun destinationSaved(): Boolean {
        val dest = g.session.state.value.stops.lastOrNull()?.place ?: return false
        return AppRules.destinationSaved(dest, g.saved.places.value, arrivalSavedId, g.saved.find(dest))
    }

    /** P21: no step marked any more; [recenter]: the camera shows the whole route again (audit F29). */
    private fun clearStep(recenter: Boolean) {
        if (shownStep == null && g.stepMark.value == null) return
        shownStep = null
        g.stepMark.value = null
        if (recenter) g.director.onRecenter()
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

    /**
     * Batch O3: the sprite of a window's place: the map label's own when that tap opened this window; the arrival's
     * destination and any other window their own (review of 0.1.41: a label tapped before stayed on them).
     */
    private fun sheetIcon(ui: PlaceSheetUi): String? = AppRules.sheetIcon(ui.place, ui.iconName, tappedPlace, tappedIcon)

    /**
     * "Edit on OpenStreetMap": a relation opens on openstreetmap.org; signed out, sign in or a note (answer 6). 0.1.41:
     * the target is [PlaceQuestions.editTarget]'s (the place's name on the map, never a saved place's label, plan AP-11
     * and OP-1; the pack entry's point and node point, OP-5); [focus] the field the form opens at (4.8, AP9);
     * [atPlace] the form was opened from a question at the place (53e, AP-7).
     */
    private fun editOnOsm(ui: PlaceSheetUi, focus: EditFocus? = null, atPlace: Boolean = false) {
        val osm = ui.osm ?: return
        val ref = OsmRef.parse(osm) ?: return
        if (ref.type == OsmType.RELATION) {
            host?.openOutside(PlaceAction.WEBSITE, g.osmServer.elementLink(ref))
            return
        }
        val t = PlaceQuestions.editTarget(ui, sheetIcon(ui), focus, atPlace) ?: return
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
        // 0.1.41 (4.8): a confirmation in review opens the form at its hours
        openEdit(EditTarget(e.osm, item.placeName ?: e.osm, item.lat, item.lon, null, null, null, null,
            focus = EditFocus.HOURS.takeIf { e.confirm != null }), item.id)
    }

    fun closeEdit() {
        edit = null
        pop(Layer.EditPlace)
    }

    /** "Upload" on the summary: 53e's asks first ([beginUpload]); a form opened from a question at the place counts as made there (AP-7). */
    fun uploadEdit() {
        val s = edit ?: return
        beginUpload(g.onSite.madeAt(s)) { s.upload(it) }
    }

    // ---- 0.1.41, 53e (docs/online-design.md 4.2, 4.5 "Were you there?"): the asks before an upload

    /** The question shown before an edit goes ([UploadChecks.next]), or null; [uploadAskSurvey]: the public notice also says what source=survey tells. */
    var uploadAsk by mutableStateOf<UploadAsk?>(null)
        private set
    var uploadAskSurvey by mutableStateOf(false)
        private set

    /** An upload waiting for its asks: made at the place or not (decided at the tap), what goes then, the asks answered. */
    private class PendingUpload(val atPlace: Boolean, val go: (Boolean) -> Unit) {
        val answered = HashSet<UploadAsk>()
    }
    private var pendingUpload: PendingUpload? = null

    /** Upload of an edit, a new place, a speed limit or a confirmation: the asks of [UploadChecks], then [go] with "made at the place". */
    private fun beginUpload(atPlace: Boolean, go: (atPlace: Boolean) -> Unit) {
        pendingUpload = PendingUpload(atPlace, go)
        nextUploadAsk()
    }

    private fun nextUploadAsk() {
        val p = pendingUpload ?: return
        val s = g.online.state.value
        val ask = UploadChecks.next(s.editNoticeSeen, s.surveyNoticeSeen, p.atPlace, p.answered)
        uploadAskSurvey = ask == UploadAsk.PUBLIC && UploadChecks.publicWithSurvey(s.surveyNoticeSeen, p.atPlace)
        uploadAsk = ask
        if (ask == null) {
            pendingUpload = null
            p.go(p.atPlace)
        }
    }

    /** The answer to [uploadAsk]: the second answer (Not now, Cancel, Back) goes back to the summary; nothing kept, nothing sent. */
    fun answerUpload(yes: Boolean) {
        val p = pendingUpload ?: return
        val ask = uploadAsk ?: return
        if (!yes) {
            pendingUpload = null
            uploadAsk = null
            uploadAskSurvey = false
            return
        }
        p.answered += ask
        when (ask) {
            UploadAsk.PUBLIC -> {
                val both = uploadAskSurvey
                if (both) p.answered += UploadAsk.SURVEY
                g.updateOnline { it.copy(editNoticeSeen = true, surveyNoticeSeen = it.surveyNoticeSeen || both) }
            }
            UploadAsk.SURVEY -> g.updateOnline { it.copy(surveyNoticeSeen = true) }
            UploadAsk.ON_SITE -> Unit
        }
        nextUploadAsk()
    }

    /** OP 5.2: guiding or re-routing (ARRIVED is not: the arrival card asks there). */
    private fun guiding(): Boolean = phase == Phase.GUIDING || phase == Phase.REROUTING

    /**
     * 53c (docs/online-design.md 4.5 "Still right?", 4.8): one tap confirms the hours OSM has (the place window or the
     * arrival card; never while guiding). The question shows the hours it confirms, so it is the summary and the tap is
     * the Upload (N4); the first-time notices and the on-site question come as for any edit (53e); Undo is in My
     * reports for 12 hours (53d). Signed out: a line saying so, nothing kept.
     */
    fun confirmHours(t: HoursTarget) {
        if (!g.online.state.value.osmAllowed || guiding()) return
        if (g.accounts.account.value == null) {
            snackbar = OnlineTexts.CONFIRM_SIGN_IN
            return
        }
        if (!HoursCheck.due(t.hours, g.outbox.items.value, t.osm, t.packOsm, System.currentTimeMillis())) return   // a second tap
        beginUpload(g.onSite.madeAt(t)) { atPlace ->
            // a trip started, the switch went off or the same confirmation went meanwhile (the asks can take a while)
            if (guiding() || !g.online.state.value.osmAllowed) return@beginUpload
            if (!HoursCheck.due(t.hours, g.outbox.items.value, t.osm, t.packOsm, System.currentTimeMillis())) return@beginUpload
            val item = HoursCheck.item(t, java.util.UUID.randomUUID().toString(), System.currentTimeMillis(), ZoneId.systemDefault(), atPlace)
            g.outbox.add(item)
            g.sendOutbox { snackbar = OnlineTexts.confirmResult(g.outbox.byId(item.id)) }
        }
    }

    // ---- 0.1.41, 53d (docs/online-design.md 4.5 "Undo"): Undo after upload, from My reports

    /** The sent edit whose Undo was tapped, waiting for the dialog's answer ([OnlineTexts.UNDO_TITLE]); null: none. */
    var undoAsk by mutableStateOf<OutboxItem?>(null)
        private set

    /** My reports' Undo on a sent edit: the dialog with what goes back, while it is still offered. */
    fun askUndo(item: OutboxItem) {
        if (Undo.offered(item, g.outbox.items.value, System.currentTimeMillis())) undoAsk = item
    }

    /** The Undo dialog's answer: its Undo is the undo's Upload (N4); it goes through the outbox, never while guiding. */
    fun answerUndo(yes: Boolean) {
        val item = undoAsk ?: return
        undoAsk = null
        if (!yes || !g.online.state.value.osmAllowed || guiding()) return
        val now = System.currentTimeMillis()
        if (!Undo.offered(item, g.outbox.items.value, now)) return
        val u = Undo.item(item, java.util.UUID.randomUUID().toString(), now) ?: return
        g.outbox.add(u)
        g.sendOutbox { snackbar = OnlineTexts.undoResult(g.outbox.byId(u.id), signedIn = g.accounts.account.value != null) }
    }

    // ---- 0.1.41 (docs/online-design.md 4.8, AP2-AP7): one question on arrival

    private val arrivalAsker = ArrivalAsker()

    /** The arrival card's question now (AppRoot puts it into the card, AppRules.withArrivalQuestion), or null. */
    var arrivalShown by mutableStateOf<ArrivalAsker.Shown?>(null)
        private set

    /** The destination's window data the last tick used (its answer builds the edit or the confirmation from it). */
    private var arrivalSheet: PlaceSheetUi? = null

    /**
     * AP2-AP6: about once a second while ARRIVED (AppRoot); [sheet]: the destination's window data, null until known;
     * [displayed] (plan AP-1): the card is on screen now, so a question chosen counts as seen (in memory only, AP-6).
     */
    fun arrivalTick(sheet: PlaceSheetUi?, elapsedMs: Long, nowMs: Long, displayed: Boolean) {
        val t = g.session.state.value
        val osm = sheet?.osm
        val today = Instant.ofEpochMilli(nowMs).atZone(ZoneId.systemDefault()).toLocalDate()
        arrivalSheet = sheet
        val input = ArrivalInput(
            phase = t.phase, motion = t.guidance?.vehicle?.motion, simulated = t.guidance?.simulated == true,
            allowed = g.online.state.value.osmAllowed && g.accounts.account.value != null,
            atPlace = g.onSite.arrivedAt(sheet, elapsedMs),
            facts = sheet?.let { PlaceQuestions.facts(it, ownClosed = g.ownChanges.value.any { c -> c.ui.closed && c.ui.osm == osm }) },
            items = g.outbox.items.value, nowMs = nowMs,
            askedRecently = osm != null && g.arrivalAsked.recent(osm, today),
            displayed = displayed,
        )
        val next = arrivalAsker.update(input, elapsedMs) { first -> g.arrivalAsked.noteShown(first) }
        if (next != arrivalShown) arrivalShown = next
    }

    /** The trip left ARRIVED: this arrival's question goes. */
    fun arrivalEnded() {
        arrivalAsker.reset()
        arrivalShown = null
        arrivalSheet = null
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

    /** "Upload" on the add summary: 53e's asks first ([beginUpload]). */
    fun uploadAdd() {
        val s = add ?: return
        beginUpload(g.onSite.madeAt(s)) { s.upload(it) }
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
            g.sheetPlace.value != null || g.search.results.value != null || phase == Phase.PLANNING || phase == Phase.CHOOSING ||
            AppRules.backHint(phase) != null
    }

    /**
     * Back: dialog, pick, the top screen, the stop results (item 51), the stops editor, the sheet, the
     * results, then the route sheet (5.5's Back row; P46: back to the window its Directions came from);
     * while a trip is driven or offered, a pair of presses leaves the app (P28).
     */
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
        // item 51: the stop results (the stops editor is hidden under them)
        if (g.search.results.value?.stop != null) return backFromStop()
        if (stopsEditorOpen) {
            stopsEditorOpen = false
            return
        }
        if (g.sheetPlace.value != null) return closeSheet()
        if (g.search.results.value != null) return backFromResults()
        if (phase == Phase.PLANNING || phase == Phase.CHOOSING) {
            stopsEditorOpen = false
            clearStep(recenter = false)
            val back = preview.back()
            if (back != null) {
                // P46 (decisions 0.1.40): Back from directions opens the destination's window again; its
                // results come back under it once the route has ended (restoreAfterBack); the camera shows
                // the place as a picked one (K2a)
                g.director.onShowPlaceAfterRoute(back.point)
                send(NavIntent.End)
                showSheet(back)
            } else {
                send(NavIntent.End)
                offerEndUndo(DIRECTIONS_CLOSED) // audit F31: a route planned another way: closed, with Undo
            }
            return
        }
        // play test P28: while a trip is driven (or offered) a single Back never hides it by accident
        if (AppRules.backHint(phase) == null) return
        val now = g.clock.elapsedMs()
        if (AppRules.secondBack(backArmedAtMs, now)) {
            backArmedAtMs = null
            host?.leaveApp()
        } else {
            backArmedAtMs = now
            backArmedIn = phase
        }
    }

    // ------------------------------------------------------------------ item 52: directions handed over by another app

    /**
     * H3: the link waiting until the map can take it (H4); [seq] tells one from the next, [receivedMs] (monotonic) and
     * [firstRun] (it came during the first-run steps) how long it may wait (K5). Never saved, never logged.
     */
    class PendingLink(val seq: Long, val link: Handover, val receivedMs: Long, val firstRun: Boolean) {
        override fun toString(): String = "PendingLink"
    }

    var pendingLink by mutableStateOf<PendingLink?>(null)
        private set
    /** H3, K15: each link's number; a lookup opens what it found only while its link's is live. */
    private val linkTickets = LinkTickets()

    /** H4-H7: the line about a link that cannot open, with "Add a state" (the map's message slot); notes without it are the plain bar (K3). */
    var handoverNote by mutableStateOf<HandoverNote?>(null)

    /** K8: counts each time a link opens something; AppRoot closes its own map style sheet and map data dialog then. */
    var linkClears by mutableStateOf(0)
        private set

    /** H6/H7: the search a link's words opened (its Layer.Search id): during a trip its pick opens the stop card (K10). */
    private var linkSearchId: Int? = null

    /** K16: the place a link's route preview went to: a recent once the user drives to it. */
    private var linkRecent: Place? = null

    /** H6: the address parser's state spellings (us_street_norm.json), read once off the main thread. */
    private val streetTables by lazy { io.github.spysyweeb.beamline.search.address.StreetTables.load() }

    /** H6 (a): street names compared as the address parser reads them ("W 38th St" is "West 38th Street"); made off the main thread. */
    private val streetKeys by lazy { io.github.spysyweeb.beamline.search.address.AddressNormalizer(streetTables) }

    /** MainActivity: a VIEW link arrived (H3); a newer one replaces one not yet opened. K5: behind the user's own work it says so. */
    fun receiveLink(uri: String) {
        val set = g.installed.installed.value
        val firstRun = !AppRules.noticeAccepted(g.appState.state.value) || !g.online.state.value.choiceMade || !set.scanned || set.packs.isEmpty() ||
            (g.getPacks.showFirstRunPicker.value && !firstRunClosed)
        pendingLink = PendingLink(linkTickets.next(), HandoverLink.parse(uri), g.clock.elapsedMs(), firstRun)
        handoverNote = null
        linkRecent = null
        if (!firstRun && linkBusy()) snackbar = HandoverTexts.WAITING
    }

    /**
     * K15: the activity finished: a link still waiting is not opened by a later launch, and one whose lookup is still
     * running ("Finding the place...") opens nothing when it ends.
     */
    fun dropPendingLink() {
        pendingLink = null
        linkTickets.voidAll()
        if (snackbar == HandoverTexts.FINDING) snackbar = null
    }

    /** H4: something of the user's is open (a form, the save dialog, a map pick, a public notice, a search to save). */
    private fun linkBusy(): Boolean {
        val su = g.savedFeature.ui.value
        return HandoverRules.busy(stack.toList(), saveDialog = su.dialog != null, mapPick = su.mapPick != null,
            publicNotice = notePending != null || uploadAsk != null || undoAsk != null, saveSearch = g.search.ui.value.mode == SearchMode.SAVE)
    }

    /** H4: the pending link opens now (AppRoot calls it when HandoverRules.ready); one that waited too long goes (K5). */
    fun runPendingLink() {
        val p = pendingLink ?: return
        pendingLink = null
        if (HandoverRules.expired(p.receivedMs, g.clock.elapsedMs(), p.firstRun)) {
            Log.i(TAG, "handover: dropped, it waited too long") // plain literal
            return
        }
        Log.i(TAG, "handover: ${HandoverRules.kindName(p.link)}") // log-ok: a fixed word for the link's form
        when (val l = p.link) {
            Handover.Unreadable -> showLinkNote(HandoverTexts.UNREADABLE, states = false)
            is Handover.Point -> openLinkPoint(p, l)
            is Handover.Text -> openLinkText(p, l)
        }
    }

    /** H4: a lookup ended while the user opened something of their own: the link waits for it again (K5's bar). */
    private fun requeue(p: PendingLink) {
        if (pendingLink != null) return
        pendingLink = p
        snackbar = HandoverTexts.WAITING
    }

    /**
     * H4 (K3): the map in front for a link that opens something: what is open over it goes (never something of the
     * user's: H4 waits for that), the map style sheet and the map data dialog too (AppRoot, [linkClears]).
     */
    private fun clearForLink() {
        linkClears++
        confirm = null
        signInFor = null
        signInToAdd = null
        speedPicking = false
        handoverNote = null
        closeSearchAndList()
        stack.removeAll { it == Layer.Settings || it == Layer.About || it == Layer.Licences || it == Layer.DriverNotice || it == Layer.States || it == Layer.Reports }
        stopsEditorOpen = false
        clearStep(recenter = false)
        if (g.sheetPlace.value != null) {
            g.director.onPlaceLeft()
            g.sheetPlace.value = null
        }
        if (g.search.results.value != null) closeResults()   // S11 results, or item 51's stop results (its teardown runs)
        linkSearchId = null
    }

    /** K3: a note with "Add a state" closes what is open and shows on the map; any other is the plain bar over the screen as it is. */
    private fun showLinkNote(text: String, states: Boolean) {
        Log.i(TAG, "handover opened: note") // plain literal
        if (states) {
            clearForLink()
            snackbar = null
            handoverNote = HandoverNote(text, states = true)
        } else {
            handoverNote = null
            snackbar = text
        }
    }

    /** H5: a point, named from the packs inside the installed maps (off the main thread), then opened by phase. */
    private fun openLinkPoint(p: PendingLink, l: Handover.Point) {
        val set = g.installed.installed.value
        if (set.packs.isEmpty()) return showLinkNote(HandoverTexts.NEED_STATE, HandoverRules.statesOffered(phase))
        if (set.routing?.covers(l.point) != true) return openLinkPlace(HandoverRules.pointPlace(l.point, l.label, emptyList(), null))
        g.scope.launch {
            val pois = runCatching { g.search.poisNear(l.point, HandoverRules.LABEL_POI_M) }.getOrDefault(emptyList())
            val street = runCatching { withContext(Dispatchers.Default) { g.streetAt(l.point) } }.getOrNull()
            if (!linkTickets.live(p.seq)) return@launch   // a newer link came meanwhile
            if (linkBusy()) return@launch requeue(p)
            val line = street?.let { nearStreetText(it.name, it.subtitle, it.house) }
            openLinkPlace(HandoverRules.pointPlace(l.point, l.label, pois, line))
        }
    }

    /**
     * H6 in K1's order: a state named that is not installed is said at once; else the words are looked up on the search
     * thread ("Finding the place..."): one clear match opens as the same place picked in search, "Name, 12 Some Street,
     * ..." is tried as the address, anything else opens the search with the words typed.
     */
    private fun openLinkText(p: PendingLink, l: Handover.Text) {
        val set = g.installed.installed.value
        if (set.packs.isEmpty()) return showLinkNote(HandoverTexts.NEED_STATE, HandoverRules.statesOffered(phase))
        val near = l.near ?: searchContext().near
        val installedStates = set.packs.map { it.state.lowercase(Locale.US) }.toSet()
        val packStates = set.packs.associate { it.packId to it.state.lowercase(Locale.US) }
        g.scope.launch {
            val (tables, keys) = withContext(Dispatchers.Default) { streetTables to streetKeys }
            if (!linkTickets.live(p.seq)) return@launch
            val streetKey: (String) -> String = keys::streetKey
            fun decide(r: MultiSearchResponse?) = HandoverRules.decideText(l.text, r, tables.states, tables.ambiguousStates, installedStates, packStates, streetKey)
            fun outside(code: String) {
                val canAdd = HandoverRules.statesOffered(phase)
                showLinkNote(HandoverTexts.outsideState(HandoverRules.stateName(code, tables.states), canAdd), canAdd)
            }
            (decide(null) as? HandoverRules.TextAct.OutsideState)?.let { return@launch outside(it.code) }
            snackbar = HandoverTexts.FINDING
            val r = g.search.lookUp(l.text, near)
            if (!linkTickets.live(p.seq)) return@launch
            if (linkBusy()) return@launch requeue(p)
            when (val d = decide(r)) {
                is HandoverRules.TextAct.OutsideState -> outside(d.code)
                is HandoverRules.TextAct.Clear -> openLinkPlace(HandoverRules.placeOf(d.row))
                is HandoverRules.TextAct.TryAddress -> {
                    val state = HandoverRules.namedState(l.text, tables.states, tables.ambiguousStates)
                    val a = HandoverRules.clearAddress(d.address, g.search.lookUp(d.address, near), state, packStates, streetKey)
                    if (!linkTickets.live(p.seq)) return@launch
                    if (linkBusy()) return@launch requeue(p)
                    if (a == null) return@launch openLinkSearch(l.text, l.near)
                    val at = GeoPoint(a.result.lat, a.result.lon)
                    val pois = runCatching { g.search.poisNear(at, HandoverRules.LABEL_POI_M) }.getOrDefault(emptyList())
                    if (!linkTickets.live(p.seq)) return@launch
                    openLinkPlace(HandoverRules.pointPlace(at, d.name, pois, HandoverRules.addressLine(a)))
                }
                HandoverRules.TextAct.Search -> openLinkSearch(l.text, l.near)
            }
        }
    }

    /** H6: the search (GO_TO) with the words typed, ranked near the link's point when it gave one; its rows work as a typed search's. */
    private fun openLinkSearch(text: String, near: GeoPoint?) {
        Log.i(TAG, "handover opened: search") // plain literal
        clearForLink()
        snackbar = null
        openSearch(SearchMode.GO_TO, typed = text, near = near, onePick = HandoverRules.pickToCard(phase))
        linkSearchId = (stack.lastOrNull() as? Layer.Search)?.id
    }

    /** H7 (K2, K3, K16): a handed-over place opens by phase (HandoverRules.act). */
    private fun openLinkPlace(place: Place) {
        snackbar = null
        val trip = g.session.state.value
        val inside = g.installed.installed.value.routing?.covers(place.point) == true
        val dest = trip.stops.lastOrNull()?.place?.point
        val atDestination = dest != null && Geo.haversineKm(dest.lat, dest.lon, place.point.lat, place.point.lon) * 1000.0 <= HandoverRules.SAME_PLACE_M
        when (val act = HandoverRules.act(trip.phase, inside, atDestination)) {
            HandoverRules.Act.NOTE -> showLinkNote(HandoverTexts.OUTSIDE_MAPS, states = false)
            HandoverRules.Act.ALREADY -> showLinkNote(HandoverRules.alreadyText(trip.phase), states = false)
            else -> {
                Log.i(TAG, "handover opened: ${act.name}") // log-ok: an enum name
                clearForLink()
                when (act) {
                    HandoverRules.Act.PREVIEW -> {
                        linkRecent = place   // K16: a recent once the trip to it is guided, not now
                        planTrip(place)
                    }
                    HandoverRules.Act.STOP_CARD -> onSearchOutput(
                        g.search.showStopPlace(place, searchContext(), instead = true, after = StopLeg.joinsAfter(trip)?.name),
                    )
                    else -> showPicked(place)   // WINDOW: "Outside the installed maps" with Add a state
                }
            }
        }
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
        /** P23: MapSceneMapper's alternatives as the map reports a tap on one: "route:<choice id>". */
        const val ROUTE_PIN = "route:"
        /** Item 51: the phases a stop can be added in (rule C8); the stop results end in any other. */
        private val STOP_PHASES = setOf(Phase.PLANNING, Phase.CHOOSING, Phase.GUIDING, Phase.REROUTING)

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
