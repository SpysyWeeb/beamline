package io.github.spysyweeb.beamline.feature.search

import io.github.spysyweeb.beamline.api.data.InstalledSet
import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.model.SavedKind
import io.github.spysyweeb.beamline.api.model.SavedPlace
import io.github.spysyweeb.beamline.api.nav.ProblemKind
import io.github.spysyweeb.beamline.api.nav.StopDetours
import io.github.spysyweeb.beamline.api.nav.StopPreview
import io.github.spysyweeb.beamline.api.nav.TripProblem
import io.github.spysyweeb.beamline.search.Along
import io.github.spysyweeb.beamline.search.AlongRoute
import io.github.spysyweeb.beamline.search.CategoryInfo
import io.github.spysyweeb.beamline.search.Geo
import io.github.spysyweeb.beamline.search.MultiSearchResponse
import io.github.spysyweeb.beamline.search.NearbyPoi
import io.github.spysyweeb.beamline.search.PackResult
import io.github.spysyweeb.beamline.search.hours.OpenState
import io.github.spysyweeb.beamline.search.foldText
import io.github.spysyweeb.beamline.search.PoiDetails
import io.github.spysyweeb.beamline.search.RecentPlaces
import io.github.spysyweeb.beamline.search.SearchAppRules
import io.github.spysyweeb.beamline.search.SearchHub
import io.github.spysyweeb.beamline.search.saved.SaveResult
import io.github.spysyweeb.beamline.search.saved.SavedPlaces
import io.github.spysyweeb.beamline.search.saved.SavedRules
import io.github.spysyweeb.beamline.search.saved.pointText
import io.github.spysyweeb.beamline.units.UnitFormatter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.floor

/** What the controller asks of the packs; [SearchHub] in the app, a fake in tests. Called on the search dispatcher only. */
internal interface PackSearcher {
    fun prepare(set: InstalledSet)
    fun hasSearchData(set: InstalledSet): Boolean
    fun search(set: InstalledSet, text: String, near: GeoPoint?, limit: Int): MultiSearchResponse
    /** Hours, phone and website of POI [ids] of the pack [packId] (rule S10); empty when none. */
    fun details(set: InstalledSet, packId: String, ids: Collection<Long>): Map<Long, PoiDetails> = emptyMap()
    /** S10 for a place without a search id: the details of the one POI of that name near [point]; null when no match is sure. */
    fun detailsAt(set: InstalledSet, name: String, point: GeoPoint): PoiDetails? = null
    /** S10 for one place with a search id: by [id] while that entry is still the place (its name near [point]), else as [detailsAt] (batch G). */
    fun detailsOf(set: InstalledSet, packId: String, id: Long, name: String, point: GeoPoint): PoiDetails? = details(set, packId, listOf(id))[id]
    /** Batch O3: the named POIs within [withinM] of [point] (SearchHub.poisNear). */
    fun poisNear(set: InstalledSet, point: GeoPoint, withinM: Double): List<NearbyPoi> = emptyList()
    /** Batch O3: the categories of the installed packs (SearchHub.categories). */
    fun categories(set: InstalledSet): List<CategoryInfo> = emptyList()
    /** P6 (0.1.40): the town nearest [point] within [withinKm] (SearchHub.placeNear); null when none. */
    fun placeNear(set: InstalledSet, point: GeoPoint, withinKm: Double): String? = null
}

/** [SearchHub] made on first use, on the search thread (its fingerprint is computed there, not at app start). */
internal class HubSearcher(make: () -> SearchHub = { SearchHub() }) : PackSearcher {
    private val hub by lazy(make)
    override fun prepare(set: InstalledSet) = hub.prepare(set)
    override fun hasSearchData(set: InstalledSet) = hub.hasSearchData(set)
    override fun search(set: InstalledSet, text: String, near: GeoPoint?, limit: Int) = hub.search(set, text, near, limit)
    override fun details(set: InstalledSet, packId: String, ids: Collection<Long>) = hub.details(set, packId, ids)
    override fun detailsAt(set: InstalledSet, name: String, point: GeoPoint) = hub.detailsAt(set, name, point)?.second
    override fun detailsOf(set: InstalledSet, packId: String, id: Long, name: String, point: GeoPoint) = hub.detailsOf(set, packId, id, name, point)
    override fun poisNear(set: InstalledSet, point: GeoPoint, withinM: Double) = hub.poisNear(set, point, withinM)
    override fun categories(set: InstalledSet) = hub.categories(set)
    override fun placeNear(set: InstalledSet, point: GeoPoint, withinKm: Double) = hub.placeNear(set, point, withinKm)
}

/**
 * The body of [SearchFeature] (rules S1-S7, S11 and S12 of docs/architecture.md 3.8; SP4, SP10-SP15,
 * SP18-SP23, SP30, SP31 of docs/architecture-saved-places.md). Every public call runs on the main
 * thread ([scope]'s); engine calls run on [searchDispatcher] after the debounce and their answers are
 * dropped when a newer text has been typed meanwhile (never cancelled: SQLite cannot be
 * interrupted). Never logs (PV2).
 */
internal class SearchController(
    private val installed: StateFlow<InstalledSet>,
    private val recents: RecentPlaces,
    private val saved: SavedPlaces,
    private val units: StateFlow<UnitFormatter>,
    private val scope: CoroutineScope,
    private val searchDispatcher: CoroutineDispatcher,
    var searcher: PackSearcher,
    private val rules: SearchAppRules = SearchAppRules.bundled(),
    private val savedRules: SavedRules = SavedRules.bundled(),
) {
    private val state = MutableStateFlow(SearchUi())
    val ui: StateFlow<SearchUi> = state.asStateFlow()

    private var mode = SearchMode.GO_TO
    private var target: SaveTarget? = null
    private var openedToSave = false
    private var context = SearchContext(null, NearSource.NONE)
    private var driving = false
    private var destination: Place? = null
    private var query = ""
    /** Every typed change counts one up; an answer for an older count is dropped (S2). */
    private var seq = 0L
    /** NO_SEARCH_DATA learned on the search thread (every pack refused); reset on open. */
    private var noData = false
    /** The set [noData] was learned on had packs: installed, but none searchable (its own line). */
    private var noDataWithPacks = false
    private var engineRows: List<Pair<ResultRowUi, Place>> = emptyList()
    /** The text [engineRows] answer (null: none yet), so Enter can show the list's own rows on the map. */
    private var engineRowsText: String? = null
    private var answered = false
    private var message: String? = null
    private var recentRows: Map<String, Place> = emptyMap()
    private var resultPlaces: Map<String, Place> = emptyMap()
    /** P44: the text [engineRows] answer, kept while a newer text is being searched (the rows a tap picks from). */
    private var rowsText: String? = null
    /** P6: the town at the map's centre when the search is ranked there ("Near Corry"); null until known, or none near. */
    private var areaName: String? = null
    /** P6: the place the shown rows' words name ("coffee in corry"); null otherwise. */
    private var answerPlace: String? = null
    /** P45: what "Clear" removed from the recents, until "Undo" puts it back, the bar's time is up, a text is typed or the screen closes. */
    private var clearedRecents: List<Place>? = null
    /** P47: the text was typed (QueryChanged), not a chip's or a type's: "No places found" then has a spelling hint. */
    private var typedByHand = false

    // ---- item 51 (rule S12): places for a new stop along the trip's route, and the stop results
    /** ADD_STOP: the trip's route ahead from where a new stop joins it (StopLeg.lineAhead); empty: none (search around the user). Set by open(). */
    private var along: List<GeoPoint> = emptyList()
    /** ADD_STOP: the stop a new one goes after ("Wegmans"); null when it joins at the car or the trip's start. */
    private var alongAfter: String? = null
    /** The shown rows answer a class (category, brand) with none in the corridor: the nearest the route are shown. */
    private var alongWidened = false
    /** The last stop results' request id (NavIntent.Detours). */
    private var stopSeq = 0L
    /** The wait of the stop results showing "Working out detours..." (at most along_route.detours_wait_ms). */
    private var stopWait: Job? = null
    /** The detours answered so far for the stop results that wait (their "+N min" when the wait runs out). */
    private var stopPartial: StopDetours? = null
    /** R4 (review F2): the seconds a card's own route gave each place looked at in the stop results that show; their rows keep them. */
    private val stopLooked = HashMap<Place, Double>()

    private var isOpen = false

    // ---- batch O3: the user's own changes as if in the pack (item 26), the "View all" grid (item 27)
    /** The app's own changes (see [OwnPlaceUi]); setting them refreshes an open screen. */
    var ownPlaces: List<OwnPlaceUi> = emptyList()
        set(value) {
            field = value
            if (isOpen) refresh()
        }
    private var allTypes: List<PlaceTypeGroupUi>? = null

    // ---- rule S11: the results on the map; rule S10: hours, phone and website
    private val resultsState = MutableStateFlow<ResultsUi?>(null)
    val results: StateFlow<ResultsUi?> = resultsState.asStateFlow()
    private val laterFlow = MutableSharedFlow<SearchOutput>(extraBufferCapacity = 8)
    val later: SharedFlow<SearchOutput> = laterFlow.asSharedFlow()
    /** Details by "packId:searchId" (null: asked, none); filled on the search thread, read on the main thread. */
    private val details = HashMap<String, PoiDetails?>()
    private val detailsCount = MutableStateFlow(0)
    val detailsLoaded: StateFlow<Int> = detailsCount.asStateFlow()
    var hoursClock: () -> LocalDateTime = { LocalDateTime.now() }

    init {
        scope.launch { saved.places.collect { if (isOpen) refresh() } }
        scope.launch { recents.places.collect { if (isOpen) refresh() } }
        scope.launch { saved.notice.collect { if (isOpen) refresh() } }
        // S10: a place whose details were not found is asked again once the installed states change.
        scope.launch { installed.collect { synchronized(details) { details.entries.removeIf { it.value == null } } } }
    }

    // ---------------------------------------------------------------- opening and closing
    fun open(mode: SearchMode, context: SearchContext, driving: Boolean, destination: Place?, along: List<GeoPoint> = emptyList(), alongAfter: String? = null) {
        require(mode != SearchMode.SAVE) { "SAVE mode opens through openToSave" }
        reset()
        resultsState.value = null   // a new search ends the results on the map (S11), the stop results too (S12)
        endStopState()
        this.mode = mode
        this.context = context
        this.driving = driving
        this.destination = destination
        // item 51 (S12): a new stop is looked for along the trip's route ahead (kept after close(), for showStopPlace)
        this.along = if (mode == SearchMode.ADD_STOP) along else emptyList()
        this.alongAfter = if (mode == SearchMode.ADD_STOP) alongAfter else null
        isOpen = true
        prepare()
        nameArea()
        refresh()
    }

    fun openToSave(target: SaveTarget, context: SearchContext) {
        reset()
        this.mode = SearchMode.SAVE
        this.target = target
        this.openedToSave = true
        this.context = context
        along = emptyList()
        alongAfter = null
        isOpen = true
        prepare()
        nameArea()
        refresh()
    }

    fun close() {
        reset()
        state.value = SearchUi()
    }

    private fun reset() {
        seq++
        isOpen = false
        mode = SearchMode.GO_TO
        target = null
        openedToSave = false
        driving = false
        destination = null
        query = ""
        noData = false
        noDataWithPacks = false
        engineRows = emptyList()
        rowsText = null
        answered = false
        message = null
        allTypes = null
        areaName = null
        answerPlace = null
        clearedRecents = null
        typedByHand = false
        alongWidened = false
        // [along] and [alongAfter] stay until the next open: the saved places list opened from Add stop picks after the
        // app has closed the search (showStopPlace), and the stop still joins the trip where it did
    }

    /** P6: names the town at the map's centre when the search is ranked there (S1, S1b); on the search thread. */
    private fun nameArea() {
        val ctx = context
        val at = ctx.near?.takeIf { ctx.source == NearSource.MAP_CENTER } ?: return
        val set = installed.value
        scope.launch {
            val name = withContext(searchDispatcher) {
                try { searcher.placeNear(set, at, rules.areaNameWithinKm) } catch (_: Exception) { null }
            }
            if (!isOpen || context != ctx || name == null) return@launch
            areaName = name
            refresh()
        }
    }

    /** Opens and warms the packs on the search thread (6.17); learns when none can be searched. */
    private fun prepare() {
        val set = installed.value
        val my = seq
        scope.launch {
            val has = withContext(searchDispatcher) {
                try {
                    searcher.prepare(set)
                    searcher.hasSearchData(set)
                } catch (_: Exception) {
                    true   // decided again by the first search; never kept as a message (PV2)
                }
            }
            if (my == seq && !has && !noData) {
                noData = true
                noDataWithPacks = set.packs.isNotEmpty()
                refresh()
            }
        }
    }

    // ---------------------------------------------------------------- events
    fun onEvent(e: SearchEvent): SearchOutput? = when (e) {
        is SearchEvent.QueryChanged -> {
            typedByHand = true
            typed(e.text, debounce = true)
            null
        }
        is SearchEvent.ShortcutTapped -> {
            // a chip is a whole question ("Gas"): its answer goes straight to the map as pins, as Enter does (the
            // owner, 2026-11-21); choosing a stop: the stop results (item 51, S12); where Enter does not apply
            // (saving, driving) it is a list as before
            rules.shortcuts.firstOrNull { it.id == e.id }?.let {
                typedByHand = false
                typed(it.query, debounce = false, thenToMap = true)
            }
            null
        }
        SearchEvent.ViewAll -> { openViewAll(); null }
        is SearchEvent.TypeTapped -> {
            allTypes = null
            typedByHand = false
            typed(e.type.query, debounce = false, thenToMap = true)
            null
        }
        SearchEvent.CloseViewAll -> { allTypes = null; refresh(); null }
        is SearchEvent.RowTapped -> rowTapped(e.key)
        is SearchEvent.RemoveRecent -> { recentRows[e.key]?.let { recents.remove(it) }; null }
        SearchEvent.ClearRecents -> { clearRecents(); null }
        SearchEvent.UndoClearRecents -> { undoClearRecents(); null }
        SearchEvent.UndoExpired -> {
            if (clearedRecents != null) {
                clearedRecents = null
                refresh()
            }
            null
        }
        SearchEvent.OpenStates -> if (state.value.statesOffered) SearchOutput.OpenStates else null   // review F4: not during a trip (P30)
        is SearchEvent.SlotTapped -> slotTapped(e.kind)
        SearchEvent.OpenSavedPlaces -> if (mode == SearchMode.SAVE) null else SearchOutput.OpenSavedPlaces(mode)
        SearchEvent.ChooseOnMap -> chooseOnMap()
        SearchEvent.UseMyPosition -> useMyPosition()
        SearchEvent.DismissSavedNotice -> { saved.dismissNotice(); refresh(); null }
        SearchEvent.Close -> closeTapped()
        SearchEvent.Submit -> { submit(); null }
    }

    /** Batch O3: the grid of every place type the installed packs know (read on the search thread). */
    private fun openViewAll() {
        val my = seq
        scope.launch {
            val groups = placeTypes()
            if (my != seq || !isOpen) return@launch
            allTypes = groups
            refresh()
        }
    }

    /** Batch O3: the place types of the installed packs, grouped under plain headings in the packs' order. */
    suspend fun placeTypes(): List<PlaceTypeGroupUi> {
        val set = installed.value
        val cats = withContext(searchDispatcher) {
            try { searcher.categories(set) } catch (_: Exception) { emptyList() }
        }
        return groupTypes(cats)
    }

    /**
     * Item 52 (rule H6): [text] as the engine and merge answer it (S3-S5, best score first, not the screen's order) around
     * [near], on the search thread; null without search data or when a pack cannot be read (the exception is dropped, PV2).
     */
    suspend fun lookUp(text: String, near: GeoPoint?): MultiSearchResponse? {
        val set = installed.value
        return withContext(searchDispatcher) {
            try {
                if (!searcher.hasSearchData(set)) null else searcher.search(set, text, near, maxOf(rules.mergeLimit, rules.mapCandidates))
            } catch (_: Exception) {
                null
            }
        }
    }

    /** Batch O3: [PackSearcher.poisNear] on the search thread; empty when the packs cannot be read. */
    suspend fun poisNear(point: GeoPoint, withinM: Double): List<NearbyPoi> {
        val set = installed.value
        return withContext(searchDispatcher) {
            try { searcher.poisNear(set, point, withinM) } catch (_: Exception) { emptyList() }
        }
    }

    private fun closeTapped(): SearchOutput? {
        if (allTypes != null) {
            allTypes = null
            refresh()
            return null
        }
        if (mode == SearchMode.SAVE && !openedToSave) {   // SAVE mode entered from an empty slot: back to GO_TO START
            backToGoTo()
            return null
        }
        close()
        return SearchOutput.Closed
    }

    /** The kinds of match that answer "which of these is near me": a category word ("gas") or a brand ("sheetz"). */
    private val classTiers = setOf("category", "brand")

    /**
     * The rows shown for an answer, the same in the list and on the map (the owner, 2026-11-21). An answer
     * led by a category or a brand match: its own places first by S11's rule (the nearest first, up to
     * on_map.max_results within on_map.within_km, 25 miles, or the nearest on_map.nearest_when_none_within when
     * none is that close, [nearestOnMap]), then the rows that only matched by name, within 25 miles, nearest
     * first (P2, 0.1.40). Any other answer (a name, a street, an address): the engine's best merge.limit, its
     * best match first and the others nearest first (P6, 0.1.40, [bestThenNearest]). "Nearest" is measured
     * from the place the words name when they name one ("coffee in corry"), else from the ranking position.
     */
    private fun shaped(r: MultiSearchResponse): MultiSearchResponse {
        val first = r.results.firstOrNull()?.result ?: return r
        val km = distanceFor(r)
        if (first.tier !in classTiers) return r.copy(results = bestThenNearest(r.results.take(rules.mergeLimit), km))
        val (own, other) = r.results.partition { it.result.tier in classTiers }
        val head = nearestOnMap(own, km, rules)
        return r.copy(results = (head + withinNearest(other, km, rules)).take(rules.mapMaxResults))
    }

    /** P6: how far a row is for the order and the 25 miles: from the place the words name, else the engine's distance from the ranking position. */
    private fun distanceFor(r: MultiSearchResponse): (PackResult) -> Double? {
        val at = r.namedPlace ?: return { it.result.distanceKm }
        return { Geo.haversineKm(at.lat, at.lon, it.result.lat, it.result.lon) }
    }

    /** P2: the keys of [r]'s rows that only matched by name, when [r] is led by a category or brand match; none otherwise. */
    private fun nameOnlyKeys(r: MultiSearchResponse): Set<String> {
        if (r.results.firstOrNull()?.result?.tier !in classTiers) return emptySet()
        return r.results.filter { it.result.tier !in classTiers }.mapTo(HashSet()) { rowKey(it) }
    }

    private fun rowKey(pr: PackResult) = "r:${pr.packId}:${pr.result.id}"

    /** An engine answer as the rows take it: [along] (with S12's [Along.widened]) and the keys of its name-only rows (P2). */
    private class Answer(val along: Along, val other: Set<String>)

    /**
     * [thenToMap]: once answered, the rows go to the map as Enter puts them ([showOnMap]). In ADD_STOP with the
     * trip's route ahead ([along]) the text is searched along it (item 51, rule S12, [AlongRoute]): one search
     * per point every along_route.sample_km, the rows in order along the route; else around [context] as before.
     */
    private fun typed(text: String, debounce: Boolean, thenToMap: Boolean = false) {
        if (text.isNotBlank()) clearedRecents = null              // P45: a new search makes a clear final
        query = text
        message = null
        val my = ++seq
        engineRowsText = null
        if (text.isBlank()) {
            engineRows = emptyList()
            rowsText = null
            answerPlace = null
            alongWidened = false
            answered = false
            refresh()
            return
        }
        answered = false
        refresh()
        if (noData) return
        val set = installed.value
        val near = context.near
        val line = if (mode == SearchMode.ADD_STOP) along else emptyList()
        scope.launch {
            if (debounce) delay(rules.debounceMs)
            if (my != seq) return@launch                      // a newer text is pending: only the newest runs
            val r = withContext(searchDispatcher) {
                try {
                    // asked for on_map.candidates rows: a category's nearest are not always the engine's first few
                    val limit = maxOf(rules.mergeLimit, rules.mapCandidates)
                    if (!searcher.hasSearchData(set)) null
                    else if (line.isNotEmpty()) {
                        // S12: along the route ahead; in order along it (no P2 heading: the stop results sort by minutes)
                        val a = AlongRoute.search(line, rules, rules.mergeLimit) { p -> searcher.search(set, text, p, limit) }
                        fetchDetails(set, a.response)
                        Answer(a, emptySet())
                    } else {
                        val shaped = shaped(searcher.search(set, text, near, limit))
                        fetchDetails(set, shaped)
                        Answer(Along(shaped, false), nameOnlyKeys(shaped))
                    }
                } catch (_: Exception) {
                    FAILED_ANSWER   // a pack that cannot be read now: no rows (the exception and its text are dropped, PV2)
                }
            }
            if (my != seq) return@launch                      // answered too late: dropped
            if (r === FAILED_ANSWER) {
                engineRows = emptyList()
                rowsText = null
                answerPlace = null
                alongWidened = false
                answered = true
                refresh()
                return@launch
            }
            if (r == null) {
                noData = true
                noDataWithPacks = set.packs.isNotEmpty()
                refresh()
                return@launch
            }
            val res = r.along.response
            engineRows = rowsOf(res, r.other)
            engineRowsText = text
            rowsText = text
            answerPlace = res.namedPlace?.name
            alongWidened = r.along.widened
            answered = true
            refresh()
            if (thenToMap) showOnMap(text)
        }
    }

    /** [other]: the keys of the rows that only matched by name (P2: ResultRowUi.otherMatch). */
    private fun rowsOf(r: MultiSearchResponse, other: Set<String> = emptySet()): List<Pair<ResultRowUi, Place>> = r.results.map { pr ->
        val res = pr.result
        val place = Place(res.title, GeoPoint(res.lat, res.lon), PlaceSource.SEARCH, res.subtitle.ifEmpty { null }, res.categoryId, res.id, pr.packId)
        ResultRowUi(
            key = rowKey(pr), iconName = Icons.forResult(res), title = res.title,
            subtitle = res.subtitle.ifEmpty { null }, distanceText = distanceText(context, place.point, units.value),
            note = addressNote(res.address), hours = hoursOf(place, withWeek = false), otherMatch = rowKey(pr) in other,
        ) to place
    }

    /** On the search thread: the details of the answer's POIs, by pack, into [details] (S10). Never fails a search. */
    private fun fetchDetails(set: InstalledSet, r: MultiSearchResponse) {
        val byPack = r.results.filter { it.result.id > 0 }.groupBy({ it.packId }, { it.result.id })
        val found = HashMap<String, PoiDetails?>()
        for ((pack, ids) in byPack) {
            val d = try { searcher.details(set, pack, ids) } catch (_: Exception) { emptyMap() }
            for (id in ids) found["$pack:$id"] = d[id]
        }
        synchronized(details) { details.putAll(found) }
    }

    private fun detailsOf(place: Place): PoiDetails? {
        val key = detailsKey(place) ?: return null
        return synchronized(details) { details[key] }
    }

    /** "packId:searchId", or for a place without a search id (a tapped POI, a saved place, a recent) its name and point (S10). */
    private fun detailsKey(place: Place): String? {
        val pack = place.packId
        val id = place.searchId?.takeIf { it > 0 }
        if (pack != null && id != null) return "$pack:$id"
        // A dropped pin has no name of its own (SP19: the owner names the places, not the pin).
        val name = lookupName(place)
        if (name.isBlank() || place.source == PlaceSource.MAP_PIN) return null
        return "at:${place.point.lat},${place.point.lon}:$name"
    }

    /**
     * The name a place is looked up by (S10): a saved place's own name when it was saved ("Erie Zoo",
     * SavedPlace.placeName), not the name the owner gave it ("Home"); every other place's name (batch G).
     */
    private fun lookupName(place: Place): String =
        place.savedId?.let { id -> if (saved.readOnly) null else saved.byId(id) }?.placeName ?: place.name

    private fun hoursOf(place: Place, withWeek: Boolean): HoursUi? =
        HoursTexts.ui(detailsOf(place)?.openingHours, hoursClock(), withWeek)

    /**
     * The place sheet asked for a place whose details were never read (a recent, a ShowPlace, a POI
     * tapped on the map, a saved place): read them once (S10); a place without a search id by its
     * name and point ([PackSearcher.detailsAt]: nothing when no match is sure).
     */
    private fun loadDetails(place: Place) {
        val key = detailsKey(place) ?: return
        synchronized(details) {
            if (details.containsKey(key)) return
            details[key] = null
        }
        val set = installed.value
        val pack = place.packId
        val id = place.searchId?.takeIf { it > 0 }
        scope.launch {
            val d = withContext(searchDispatcher) {
                try {
                    val name = lookupName(place)
                    if (pack != null && id != null) searcher.detailsOf(set, pack, id, name, place.point) else searcher.detailsAt(set, name, place.point)
                } catch (_: Exception) {
                    null
                }
            }
            if (d != null) {
                synchronized(details) { details[key] = d }
                detailsCount.value++
            }
        }
    }

    /**
     * Enter (S11): GO_TO while not driving, and ADD_STOP (item 51, S12: the stop results). The map shows the
     * rows the list shows for this text, in the list's order, at most on_map.max_results of them (the owner,
     * 2026-11-21: until then Enter asked the engine again for the nearest matches, and "walmart" listed nine
     * places and showed five). An answer that has not come yet is waited for.
     */
    private fun submit() {
        val offered = (mode == SearchMode.GO_TO && !driving) || mode == SearchMode.ADD_STOP
        if (!offered || query.isBlank() || noData) return
        if (answered && engineRowsText == query) showOnMap(query) else typed(query, debounce = false, thenToMap = true)
    }

    /** The list's rows for [text] as the map shows them (S11): the own places first, at most [max]. */
    private fun mapRows(text: String, max: Int = rules.mapMaxResults): List<Pair<ResultRowUi, Place>> =
        (ownRows(text) + engineRows.filter { (_, p) -> !hiddenByOwn(p) }).take(max)

    /** P6 (e): the results window's line: where the list is ranked from when that is not the user's position; null for GPS. */
    private fun resultsNear(): String? =
        answerPlace?.let(Texts::near) ?: if (context.source == NearSource.GPS) null else Texts.nearText(context, areaName)

    /**
     * The list's rows for [text] as pins on the map: none stays the list (its "no results" line), one opens that
     * place, more close the search and show them. ADD_STOP: the stop results (S12), one place or more.
     */
    private fun showOnMap(text: String) {
        if (!answered || engineRowsText != text) return
        if (mode == SearchMode.ADD_STOP) {
            showStopResults(text, typed = if (typedByHand) text else "")
            return
        }
        if (mode != SearchMode.GO_TO || driving) return
        val rows = mapRows(text)
        when (rows.size) {
            0 -> Unit
            1 -> picked(rows[0].second)?.let { laterFlow.tryEmit(it) }
            else -> {
                val shown = ResultsUi(text, rows.map { it.first }, rows.map { it.second }, nearText = resultsNear())
                close()
                endStopState()
                resultsState.value = shown
                laterFlow.tryEmit(SearchOutput.ShowResults(shown.places))
            }
        }
    }

    /**
     * S11b (the owner, 2026-11-24: "when they drag the map to a different area, a button should pop up
     * below the search bar that says "Search this area" that updates the pins and list with searches in
     * that window"): the results' words again around [center], only the places inside [area]: those
     * already shown there first, then the nearest the center, at most on_map.max_results, listed
     * nearest the center first. They replace the results (none selected; the rows'
     * distances stay from the user's position). The category's or brand's own places come before the rows
     * that only matched by name (P2), each nearest the center first; the window's line names the place the
     * words name, else the town at the center (P6 e). The number found: 0 leaves the results as they were;
     * null when the results ended or changed meanwhile, no pack could be read, or they are stop results.
     */
    suspend fun searchArea(center: GeoPoint, area: Bounds): Int? {
        val cur = resultsState.value ?: return null
        if (cur.stop != null) return null
        val text = cur.query
        val set = installed.value
        val area0 = withContext(searchDispatcher) {
            try {
                if (!searcher.hasSearchData(set)) null
                else {
                    val all = searcher.search(set, text, center, maxOf(rules.mergeLimit, rules.mapCandidates))
                    val inside = all.copy(results = all.results.filter { area.contains(GeoPoint(it.result.lat, it.result.lon)) }
                        .sortedBy { it.result.distanceKm ?: Double.MAX_VALUE })
                    fetchDetails(set, inside)
                    // P2: the name matches are known from the whole answer (its first row says whether it is a category's);
                    // P6 (e): the place the words name, else the town at the centre
                    val town = all.namedPlace?.name ?: searcher.placeNear(set, center, rules.areaNameWithinKm)
                    AreaAnswer(inside, nameOnlyKeys(all), town)
                }
            } catch (_: Exception) {
                null // a pack that cannot be read now (the exception and its text are dropped, PV2)
            }
        } ?: return null
        val now = resultsState.value
        if (now == null || now.query != text || now.places != cur.places) return null
        val fresh = ownRows(text).filter { area.contains(it.second.point) } + rowsOf(area0.response, area0.other).filter { (_, p) -> !hiddenByOwn(p) }
        // a tester, 2026-11-25: zoomed out over four pins, "Search this area" dropped some still in the
        // window (more than max_results matches there, the ones nearest the centre won). The places
        // shown and still in the window stay; the rest of max_results are the nearest the centre.
        val kept = cur.rows.zip(cur.places).filter { (_, p) -> area.contains(p.point) }
        val seen = HashSet<String>()
        val rows = (kept + fresh).filter { seen.add(it.first.key) }.take(rules.mapMaxResults)
            .sortedWith(compareBy<Pair<ResultRowUi, Place>>({ it.first.otherMatch }, { Geo.haversineKm(center.lat, center.lon, it.second.point.lat, it.second.point.lon) }))
        if (rows.isEmpty()) return 0
        resultsState.value = ResultsUi(text, rows.map { it.first }, rows.map { it.second }, nearText = area0.town?.let(Texts::near) ?: Texts.NEAR_MAP)
        return rows.size
    }

    /** "Search this area"'s answer: the rows inside the window, the keys of the name-only rows (P2), the town (P6). */
    private class AreaAnswer(val response: MultiSearchResponse, val other: Set<String>, val town: String?)

    /** S11: the results end when a state one of them came from is no longer installed; true when they ended (stop results too, S12). */
    fun closeResultsGoneFrom(set: InstalledSet): Boolean {
        val cur = resultsState.value ?: return false
        if (!fromInstalled(cur, set)) {
            resultsState.value = null
            endStopState()
            return true
        }
        return false
    }

    private fun fromInstalled(r: ResultsUi, set: InstalledSet): Boolean {
        val packs = set.packs.mapTo(HashSet()) { it.packId }
        return r.places.all { p -> p.packId.let { it == null || it in packs || it.startsWith(OWN_PACK) } }
    }

    /**
     * P46 (0.1.40): results kept while a route preview ran ([r] as [results] gave them) shown again, their
     * hours as of now; false, and nothing changes, while other results show, when a state one of them came
     * from is no longer installed (as [closeResultsGoneFrom]) or for stop results (item 51: never kept).
     */
    fun restoreResults(r: ResultsUi): Boolean {
        if (resultsState.value != null || r.stop != null) return false
        if (!fromInstalled(r, installed.value)) return false
        resultsState.value = r.copy(rows = r.rows.mapIndexed { i, row -> row.copy(hours = hoursOf(r.places[i], withWeek = false)) })
        return true
    }

    fun onResultsEvent(e: ResultsEvent): SearchOutput? {
        val cur = resultsState.value ?: return null
        cur.stop?.let { return onStopEvent(cur, it, e) }
        return when (e) {
            is ResultsEvent.Select -> {
                val place = cur.places.getOrNull(e.index) ?: return null
                recents.add(place)
                resultsState.value = cur.copy(selected = e.index)
                SearchOutput.ShowPlace(place)
            }
            ResultsEvent.Deselect -> {
                resultsState.value = cur.copy(selected = null)
                null
            }
            ResultsEvent.Close -> {
                resultsState.value = null
                null
            }
            ResultsEvent.AddStop, ResultsEvent.GoInstead -> null   // stop results only
        }
    }

    // ---------------------------------------------------------------- item 51 (rule S12): the stop results

    /**
     * The stop results' events: a row or pin opens its card and asks for the route with it ([SearchOutput.PreviewStop]),
     * Deselect goes back to the list, only the card's "Add stop" adds it (recents get it then, unless it is a saved
     * place, SP31), Close ends them.
     */
    private fun onStopEvent(cur: ResultsUi, stop: StopResultsUi, e: ResultsEvent): SearchOutput? = when (e) {
        is ResultsEvent.Select -> {
            val place = cur.places.getOrNull(e.index)
            if (place == null) null
            else {
                resultsState.value = cur.copy(selected = e.index, stop = stop.copy(preview = busyCard(cur.rows[e.index], stop.after)))
                SearchOutput.PreviewStop(place)
            }
        }
        ResultsEvent.Deselect -> {
            resultsState.value = cur.copy(selected = null, stop = stop.copy(preview = null))
            SearchOutput.PreviewStop(null)
        }
        ResultsEvent.AddStop -> {
            val place = cur.selected?.let { cur.places.getOrNull(it) }
            if (place == null || stop.preview?.canAdd == false) null
            else {
                if (place.source != PlaceSource.SAVED) recents.add(place)
                resultsState.value = null
                endStopState()
                SearchOutput.AddStop(place)
            }
        }
        // item 52 (rule H7): only a handed-over place's card offers it; recents get the place then, as at Add stop
        ResultsEvent.GoInstead -> {
            val place = cur.selected?.let { cur.places.getOrNull(it) }
            if (!stop.instead || place == null) null
            else {
                if (place.source != PlaceSource.SAVED) recents.add(place)
                resultsState.value = null
                endStopState()
                SearchOutput.GoInstead(place)
            }
        }
        ResultsEvent.Close -> {
            resultsState.value = null
            endStopState()
            null
        }
    }

    /** The stop results' wait and partial answer go (new stop results, or none). */
    private fun endStopState() {
        stopWait?.cancel()
        stopWait = null
        stopPartial = null
        stopLooked.clear()
    }

    /** Enter, a chip or a type in ADD_STOP: the list's rows for [text] as stop results (none: the list's "no results" line stays). */
    private fun showStopResults(text: String, typed: String) {
        val rows = mapRows(text, rules.alongMaxResults)
        if (rows.isEmpty()) return
        laterFlow.tryEmit(startStop(text, rows, typed, selected = null))
    }

    /**
     * New stop results: the search screen closes, the rows (nearest where the stop joins the trip first, so a
     * fallback needs no new order) wait for the detours at most along_route.detours_wait_ms; [selected] opens
     * that row's card at once (one place picked). [typed]: the words Back types again.
     */
    private fun startStop(query: String, rows: List<Pair<ResultRowUi, Place>>, typed: String, selected: Int?, instead: Boolean = false): SearchOutput.ShowStopResults {
        endStopState()
        val id = ++stopSeq
        val join = along.firstOrNull() ?: context.near
        val after = alongAfter
        val widened = alongWidened
        close()
        val ordered = if (join == null) rows else rows.withIndex()
            .sortedWith(compareBy({ Geo.haversineKm(join.lat, join.lon, it.value.second.point.lat, it.value.second.point.lon) }, { it.index }))
            .map { it.value }
        val sel = selected?.let { ordered.indexOf(rows[it]) }
        // review F1 (0.1.40): P2's "Other matches" is the search list's; these rows are sorted by distance and minutes
        val shown = ordered.map { it.first.copy(otherMatch = false) }
        val places = ordered.map { it.second }
        resultsState.value = ResultsUi(
            query, shown, places, sel,
            stop = StopResultsUi(id, working = true, typed = typed, after = after, widened = widened, preview = sel?.let { busyCard(shown[it], after) },
                instead = instead),
        )
        stopWait = scope.launch {
            delay(rules.detoursWaitMs)
            finishStop(id, timedOut = true)
        }
        return SearchOutput.ShowStopResults(id, places, sel)
    }

    /** One place picked for a stop (a row, a recent, Home or Work, a saved place): its stop results with its card open; [instead]: item 52's card. */
    private fun stopPick(place: Place, row: ResultRowUi?, typed: String, instead: Boolean = false): SearchOutput.ShowStopResults {
        val r = row ?: ResultRowUi(
            STOP_ROW_KEY, Icons.forPlace(place, if (saved.readOnly) null else saved.find(place)), place.name, place.subtitle,
            distanceText(context, place.point, units.value), null, hours = hoursOf(place, withWeek = false),
        )
        return startStop(place.name, listOf(r to place), typed, selected = 0, instead = instead)
    }

    fun showStopPlace(place: Place, context: SearchContext, instead: Boolean = false, after: String? = null): SearchOutput.ShowStopResults {
        this.context = context
        if (instead) {
            // item 52: the stop joins where the app says now, never where an older Add stop search left it
            along = emptyList()
            alongAfter = after
            alongWidened = false
        }
        return stopPick(place, null, typed = "", instead = instead)
    }

    /** The card while its route is worked out: the row's own figure until the session's. */
    private fun busyCard(row: ResultRowUi, after: String?) =
        StopPreviewUi(row.title, row.subtitle, addedText = row.detourText, arriveText = null, busy = true, afterText = after?.let(Texts::goesAfter))

    fun onDetours(d: StopDetours?) {
        val stop = resultsState.value?.stop ?: return
        if (!stop.working) return
        when {
            d == null -> finishStop(stop.requestId, timedOut = false, d = null)   // the session dropped it (a re-plan)
            d.requestId != stop.requestId -> Unit                                   // an older list's answer
            d.done -> finishStop(stop.requestId, timedOut = false, d = d)
            else -> stopPartial = d
        }
    }

    /**
     * The wait of stop results [id] ends: done (sorted by the minutes each adds, "On the way" first, closed now
     * after open within a minute, nearest first among equals, unknown last), or [timedOut] (nearest first as they
     * are, the minutes known so far shown). Nothing for older results or after it ended.
     */
    private fun finishStop(id: Long, timedOut: Boolean, d: StopDetours? = stopPartial) {
        val cur = resultsState.value ?: return
        val stop = cur.stop ?: return
        if (stop.requestId != id || !stop.working) return
        stopWait?.cancel()
        stopWait = null
        stopPartial = null
        val n = cur.rows.size
        val known = d?.takeIf { it.requestId == id }?.addedS
        val matrix = List(n) { known?.getOrNull(it) }
        // R4 (review F2): a place looked at meanwhile keeps its card's figure, in its row and in the order
        val added = List(n) { stopLooked[cur.places[it]] ?: matrix[it] }
        val order = if (timedOut || matrix.all { it == null }) (0 until n).toList()
        else (0 until n).sortedWith(compareBy(
            { added[it] == null }, { added[it]?.let(::minuteKey) ?: 0L }, { cur.rows[it].hours?.state == OpenState.CLOSED }, { it },
        ))
        val rows = order.map { i -> cur.rows[i].copy(detourText = added[i]?.let(::detourText)) }
        val sel = cur.selected?.let { order.indexOf(it) }
        val note = when {
            timedOut -> Texts.DETOURS_SLOW
            matrix.all { it == null } -> Texts.DETOURS_NONE
            else -> null
        }
        val preview = stop.preview?.let { p -> if (p.addedText == null && sel != null) p.copy(addedText = rows[sel].detourText) else p }
        resultsState.value = cur.copy(rows = rows, places = order.map { cur.places[it] }, selected = sel,
            stop = stop.copy(working = false, note = note, preview = preview))
    }

    /** The sort key of a detour: 0 under a minute ("On the way"), else its minutes as shown. */
    private fun minuteKey(s: Double): Long = if (s < ON_THE_WAY_S) 0L else floor(s / 60.0 + 0.5).toLong()

    private fun detourText(s: Double): String = if (s < ON_THE_WAY_S) Texts.ON_THE_WAY else "+" + units.value.duration(s)

    fun onStopPreview(p: StopPreview?, nowEpochMs: Long, zone: ZoneId) {
        val cur = resultsState.value ?: return
        val stop = cur.stop ?: return
        val sel = cur.selected ?: return
        if (p == null || p.place != cur.places.getOrNull(sel)) return
        val row = cur.rows[sel]
        val added = p.addedS?.let(::detourText)
        val card = StopPreviewUi(
            title = row.title, subtitle = row.subtitle, addedText = added ?: row.detourText,
            arriveText = p.route?.let { Texts.ARRIVE + units.value.clockTime(nowEpochMs + (it.timeS * 1000).toLong(), zone) },
            busy = p.busy, problem = p.problem?.let(::cardProblem), canAdd = p.problem?.kind != ProblemKind.TOO_MANY_STOPS,
            afterText = stop.after?.let(Texts::goesAfter),
        )
        // R4: after a look the card's figure is the row's too (no new order), so the list and the card agree; kept for
        // the end of the detours' wait (review F2)
        val rows = if (added != null && !p.busy) cur.rows.toMutableList().also { it[sel] = row.copy(detourText = added) } else cur.rows
        if (added != null && !p.busy) p.addedS?.let { stopLooked[p.place] = it }
        resultsState.value = cur.copy(rows = rows, stop = stop.copy(preview = card))
    }

    /** A stop too many keeps its own words; any other problem is left to the re-plan "Add stop" starts (C3). */
    private fun cardProblem(p: TripProblem): String = if (p.kind == ProblemKind.TOO_MANY_STOPS) p.text else Texts.PREVIEW_FAILED

    // ---------------------------------------------------------------- batch O3: own changes (item 26)

    /** Own places whose name, a word of it, or their type's words begin with the typed [text] (folded). */
    private fun ownMatching(text: String): List<OwnPlaceUi> {
        val q = foldText(text)
        if (q.isEmpty()) return emptyList()
        return ownPlaces.filter { o ->
            val name = foldText(o.name)
            val words = (listOf(name) + name.split(' ')).filter { it.isNotEmpty() }
            val type = (listOfNotNull(o.categoryLabel) + o.terms).map(::foldText)
            words.any { it.startsWith(q) } || type.any { t -> t == q || t.startsWith(q) || t.split(' ').any { it.startsWith(q) } }
        }
    }

    private fun ownPlace(o: OwnPlaceUi) = Place(o.name, o.point, PlaceSource.SEARCH, o.categoryLabel, o.categoryCode, null, OWN_PACK + o.key)

    /** The rows of the own places matching [text], first in the list (they are what the user just made). */
    private fun ownRows(text: String): List<Pair<ResultRowUi, Place>> = ownMatching(text).map { o ->
        val place = ownPlace(o)
        ResultRowUi(
            key = "own:${o.key}", iconName = Icons.forCategory(o.categoryCode) ?: Icons.OTHER, title = o.name,
            subtitle = o.categoryLabel, distanceText = distanceText(context, o.point, units.value),
            note = if (o.closed) Texts.CLOSED_PERMANENTLY else null,
            hours = if (o.closed) null else HoursTexts.ui(ownDetails(o, null).openingHours, hoursClock(), withWeek = false),
        ) to place
    }

    /** The own place a place is (an own row, or the pack's place it changed: its old or new name within [OWN_MATCH_M]), or null. */
    private fun ownFor(place: Place): OwnPlaceUi? {
        place.packId?.takeIf { it.startsWith(OWN_PACK) }?.let { k -> ownPlaces.firstOrNull { OWN_PACK + it.key == k }?.let { return it } }
        val n = foldText(lookupName(place))
        if (n.isEmpty()) return null
        return ownPlaces.firstOrNull { o ->
            (foldText(o.name) == n || foldText(o.oldName) == n) &&
                Geo.haversineKm(o.point.lat, o.point.lon, place.point.lat, place.point.lon) * 1000.0 <= OWN_MATCH_M
        }
    }

    /** A pack row an own change replaces (its old name, or the same name, within [OWN_MATCH_M]): not shown. */
    private fun hiddenByOwn(place: Place): Boolean {
        if (place.packId?.startsWith(OWN_PACK) == true || ownPlaces.isEmpty()) return false
        val n = foldText(place.name)
        return ownPlaces.any { o ->
            (foldText(o.oldName) == n || foldText(o.name) == n) &&
                Geo.haversineKm(o.point.lat, o.point.lon, place.point.lat, place.point.lon) * 1000.0 <= OWN_MATCH_M
        }
    }

    /** The place's values with the user's own on top: hours, phone and website set by the user, the type in words, the name and type (0.1.41). */
    private fun ownDetails(o: OwnPlaceUi, pack: PoiDetails?): PoiDetails {
        fun v(key: String, packValue: String?) = if (key in o.overrides) o.overrides[key] else packValue
        return PoiDetails(
            openingHours = if (o.closed) null else v("opening_hours", pack?.openingHours),
            phone = if (o.closed) null else v("phone", pack?.phone),
            website = if (o.closed) null else v("website", pack?.website),
            address = pack?.address,
            category = if (o.closed) Texts.CLOSED_PERMANENTLY else o.categoryLabel ?: pack?.category,
            osm = o.osm ?: pack?.osm,
            // 0.1.41 (docs/online-design.md 4.8): the user's rename and type win; the point stays the pack entry's
            // (OSM's node is checked against it; a place the user added has none). The name is the place's on the map
            // (AP12): an own change's name only when it renamed the place, else the pack's (review: an edit's name
            // is its outbox place name, which 0.1.40 took from a saved place's label)
            name = o.name.takeIf { it.isNotBlank() && o.oldName != null && it != o.oldName } ?: pack?.name,
            type = o.categoryCode ?: pack?.type,
            lat = pack?.lat, lon = pack?.lon,
        )
    }

    fun refreshHours() {
        resultsState.value?.let { cur ->
            resultsState.value = cur.copy(rows = cur.rows.mapIndexed { i, row -> row.copy(hours = hoursOf(cur.places[i], withWeek = false)) })
        }
        if (isOpen && engineRows.isNotEmpty()) {
            engineRows = engineRows.map { (row, place) -> row.copy(hours = hoursOf(place, withWeek = false)) to place }
            refresh()
        }
    }

    private fun rowTapped(key: String): SearchOutput? {
        if (key.startsWith(SAVED_KEY)) {
            if (mode == SearchMode.SAVE) return null
            val sp = saved.byId(key.removePrefix(SAVED_KEY)) ?: return null
            return useSaved(sp, typed = query)   // review F3: a row of the typed list; Back types its words again
        }
        resultPlaces[key]?.let { place -> listPicked(key, place)?.let { return it } }
        val place = resultPlaces[key] ?: recentRows[key] ?: return null
        return picked(place, state.value.results.firstOrNull { it.key == key })
    }

    /**
     * P44 (0.1.40, play test 2026-11-26; audit F52): a row of a typed list in GO_TO while not driving, when
     * the list has two or more places: the list goes on the map as Enter puts it (S11) with that place's
     * sheet open, so closing the sheet returns to the list and the bar keeps the words, as after a chip.
     * Null otherwise (one row, another mode, driving): the pick of S7.
     */
    private fun listPicked(key: String, place: Place): SearchOutput? {
        if (mode != SearchMode.GO_TO || driving) return null
        val text = rowsText ?: return null
        val rows = mapRows(text)
        val i = rows.indexOfFirst { it.first.key == key }
        if (rows.size < 2 || i < 0) return null
        recents.add(place)
        val shown = ResultsUi(text, rows.map { it.first }, rows.map { it.second }, selected = i, nearText = resultsNear())
        close()
        endStopState()
        resultsState.value = shown
        return SearchOutput.ShowPlace(rows[i].second)
    }

    /** A result or a recent (or "Use my position") picked in the current mode; ADD_STOP: its card on the map (S12, [row]: its list row). */
    private fun picked(place: Place, row: ResultRowUi? = null): SearchOutput? {
        if (mode == SearchMode.SAVE) return savePick(place)
        if (mode == SearchMode.ADD_STOP) return stopPick(place, row, typed = query)   // recents get it at "Add stop"
        if (place.source != PlaceSource.SAVED) recents.add(place)
        val out = SearchOutput.Picked(place, mode)
        close()
        return out
    }

    /** A saved place tapped (slot, saved result row): SP12-SP14. Never a recent (SP31). [typed]: ADD_STOP's words for Back ("" from a slot). */
    private fun useSaved(sp: SavedPlace, typed: String = ""): SearchOutput? {
        val p = sp.toPlace()
        if (mode == SearchMode.SET_START && isDestination(sp)) return null
        val inside = covered(installed.value, sp.point)
        // item 51: Home, Work and saved places open their card like any row (the orchestrator's answer 3)
        if (inside && mode == SearchMode.ADD_STOP) return stopPick(p, null, typed = typed)
        val out = when {
            !inside -> SearchOutput.ShowPlace(p)
            mode == SearchMode.GO_TO -> SearchOutput.PlanTrip(p)
            else -> SearchOutput.Picked(p, mode)
        }
        close()
        return out
    }

    private fun slotTapped(kind: SavedKind): SearchOutput? {
        if (saved.readOnly || mode == SearchMode.SAVE) return null
        val sp = when (kind) {
            SavedKind.HOME -> saved.home()
            SavedKind.WORK -> saved.work()
            SavedKind.OTHER -> null
        }
        if (sp != null) return useSaved(sp)
        if (mode != SearchMode.GO_TO || kind == SavedKind.OTHER) return null
        // SP15: an empty slot in GO_TO enters SAVE mode for it; back to GO_TO START after the save
        seq++
        mode = SearchMode.SAVE
        target = if (kind == SavedKind.HOME) SaveTarget.Home else SaveTarget.Work
        openedToSave = false
        query = ""
        engineRows = emptyList()
        rowsText = null
        answerPlace = null
        answered = false
        message = null
        refresh()
        return null
    }

    private fun savePick(place: Place): SearchOutput? {
        val t = target ?: return null
        when (t) {
            SaveTarget.NewPlace -> {
                close()
                return SearchOutput.SaveNew(place)
            }
            SaveTarget.Home, SaveTarget.Work -> {
                val r = saved.save(place, if (t == SaveTarget.Home) SavedKind.HOME else SavedKind.WORK)
                if (r is SaveResult.Refused) {
                    message = Texts.problem(r.problem)
                    refresh()
                    return null
                }
            }
            SaveTarget.Start -> return null   // P22: never a SAVE target (SET_START's map pick, SavedController)
            is SaveTarget.Move -> {
                val r = saved.move(t.savedId, place)
                if (r is SaveResult.Refused) {
                    message = Texts.problem(r.problem, already = saved.find(place)?.name)
                    refresh()
                    return null
                }
            }
        }
        return saveDone()
    }

    /** After a save in SAVE mode: back to GO_TO START, or done when openToSave opened it (SP15). */
    private fun saveDone(): SearchOutput? {
        if (openedToSave) {
            close()
            return SearchOutput.Closed
        }
        backToGoTo()
        return null
    }

    private fun backToGoTo() {
        seq++
        mode = SearchMode.GO_TO
        target = null
        query = ""
        engineRows = emptyList()
        rowsText = null
        answerPlace = null
        answered = false
        message = null
        refresh()
    }

    private fun chooseOnMap(): SearchOutput? {
        // P22: Change start's "Choose on the map"; this search stays (hidden while the pick runs; Cancel comes back to it)
        if (mode == SearchMode.SET_START) return SearchOutput.PickOnMap(SaveTarget.Start)
        val t = target ?: return null
        if (mode != SearchMode.SAVE) return null
        if (openedToSave) close() else backToGoTo()
        return SearchOutput.PickOnMap(t)
    }

    private fun useMyPosition(): SearchOutput? {
        // P22: Change start's "Your position": from the current position again (the session waits for a fix itself)
        if (mode == SearchMode.SET_START) return SearchOutput.StartFromYourPosition
        if (mode != SearchMode.SAVE || !positionUsable()) return null
        val fix = context.near ?: return null
        return savePick(Place(Texts.YOUR_POSITION, fix, PlaceSource.MAP_PIN, subtitle = pointText(fix)))
    }

    /** P45 (0.1.40): "Clear" empties the recents and offers "Undo" (the bar, [SearchUi.undoText]). */
    private fun clearRecents() {
        val was = recents.places.value
        if (was.isEmpty()) return
        recents.clear()
        clearedRecents = was
        refresh()
    }

    /** P45: "Undo": the recents as they were (each add puts its place first, so the oldest goes in first). */
    private fun undoClearRecents() {
        val back = clearedRecents ?: return
        clearedRecents = null
        for (p in back.asReversed()) recents.add(p)
        refresh()
    }

    private fun positionUsable(): Boolean {
        val acc = context.accuracyM ?: return false
        return context.source == NearSource.GPS && context.near != null && acc <= savedRules.positionMaxAccuracyM
    }

    private fun isDestination(sp: SavedPlace): Boolean {
        val d = destination ?: return false
        val id = d.savedId ?: saved.find(d)?.id
        return id == sp.id
    }

    // ---------------------------------------------------------------- the place sheet
    fun placeSheet(place: Place, context: SearchContext, tripActive: Boolean, driving: Boolean, guiding: Boolean = driving): PlaceSheetUi {
        val set = installed.value
        val sp = if (saved.readOnly) null else place.savedId?.let { saved.byId(it) } ?: if (place.source == PlaceSource.SAVED) null else saved.find(place)
        val inside = covered(set, place.point)
        val actions = ArrayList<PlaceAction>()
        if (!inside) {
            actions.add(PlaceAction.OPEN_STATES)
        } else {
            if (tripActive) actions.add(PlaceAction.ADD_STOP)
            actions.add(PlaceAction.DIRECTIONS)
        }
        if (!driving && !saved.readOnly) actions.add(if (sp != null) PlaceAction.EDIT else PlaceAction.SAVE)
        // S10: hours, Call and Website from the pack (read once on the search thread; detailsLoaded says when)
        val packD = detailsOf(place)
        if (packD == null) loadDetails(place)
        // Batch O3 (item 26): the user's own change of this place, as if the map had it
        val own = ownFor(place)
        val d = if (own != null) ownDetails(own, packD) else packD
        val phone = HoursTexts.phone(d?.phone)
        val website = HoursTexts.website(d?.website)
        // Not while guiding; again once the trip has arrived (the owner after batch B).
        if (!guiding && phone != null) actions.add(PlaceAction.CALL)
        if (!guiding && website != null) actions.add(PlaceAction.WEBSITE)
        // P17/P18 (0.1.40): a point (a dropped pin, a map point, a saved place kept as a point: no subtitle, no
        // category) lists its coordinates in the details' pin row, which copies them on a long press
        val isPoint = place.source == PlaceSource.MAP_PIN || (sp != null && sp.subtitle == null && sp.categoryId == null)
        return PlaceSheetUi(
            place = place, iconName = own?.categoryCode?.let { Icons.forCategory(it) } ?: Icons.forPlace(place, sp),
            title = own?.name?.takeIf { it.isNotBlank() } ?: place.name, subtitle = place.subtitle,
            distanceText = distanceText(context, place.point, units.value), note = if (inside) null else Texts.OUTSIDE,
            actions = actions, savedText = sp?.takeIf { it.name != place.name }?.let { Texts.savedAs(it.name) }, savedId = sp?.id,
            hours = HoursTexts.ui(d?.openingHours, hoursClock(), withWeek = true),
            phone = phone.takeIf { !guiding }, website = website.takeIf { !guiding },
            // The details height (batch G): written out while driving too; a tap opens nothing while guiding.
            phones = HoursTexts.phones(d?.phone), websites = HoursTexts.websites(d?.website),
            address = d?.address ?: if (isPoint) pointText(place.point) else null, category = d?.category, canOpenOutside = !guiding,
            osm = d?.osm, poi = d,
            ownTag = if (own != null) Texts.OWN_TAG else null,
        )
    }

    // ---------------------------------------------------------------- the screen state
    private fun refresh() {
        if (!isOpen) return
        val set = installed.value
        val u = units.value
        val save = mode == SearchMode.SAVE
        val start = query.isBlank()
        val readOnly = saved.readOnly
        val places = saved.places.value

        val slots = ArrayList<SavedRowUi>()
        var savedPlacesText: String? = null
        var notice: String? = null
        if (start && !save) {
            if (mode == SearchMode.GO_TO) notice = Texts.notice(saved.notice.value)
            if (!readOnly) {
                for (kind in listOf(SavedKind.HOME, SavedKind.WORK)) {
                    val sp = places.firstOrNull { it.kind == kind }
                    if (sp != null) slots.add(savedRow(sp, set, u))
                    else if (mode == SearchMode.GO_TO) slots.add(
                        SavedRowUi(null, kind, Icons.forKind(kind), if (kind == SavedKind.HOME) Texts.SET_HOME else Texts.SET_WORK, null, null, null),
                    )
                }
                savedPlacesText = when {
                    mode == SearchMode.GO_TO -> Texts.savedPlacesCount(places.size)
                    places.any { it.kind == SavedKind.OTHER } -> Texts.savedPlacesCount(places.size)
                    else -> null
                }
            }
        }

        // recents, minus saved places (SP30)
        val recentList = recents.places.value.filter { r -> places.none { saved.same(it, r) } }
        val recentMap = LinkedHashMap<String, Place>()
        val recentUi = recentList.mapIndexed { i, p ->
            val key = "recent:$i"
            recentMap[key] = p
            ResultRowUi(key, Icons.forPlace(p, null), p.name, p.subtitle, distanceText(context, p.point, u), null)
        }
        recentRows = recentMap

        // saved names matching the typed text (SP20, SP21): never in SAVE mode
        val matches = if (!start && !save && !readOnly) saved.matching(query) else emptyList()
        val savedResults = matches.map { sp ->
            val r = savedRow(sp, set, u)
            ResultRowUi(SAVED_KEY + sp.id, r.iconName, r.title, r.subtitle, r.distanceText, r.note)
        }
        val matchedIds = matches.map { it.id }.toSet()

        // engine rows: the saved place already listed is dropped, others get " · Saved" (SP22)
        val places2 = LinkedHashMap<String, Place>()
        val results = ArrayList<ResultRowUi>()
        // Batch O3 (item 26): the user's own changes first; the pack rows they change are not shown
        val own = if (!start && answered) ownRows(query) else emptyList()
        for ((row, place) in own + engineRows.filter { (_, p) -> !hiddenByOwn(p) }) {
            val sp = if (readOnly) null else saved.find(place)
            if (sp != null && sp.id in matchedIds) continue
            val note = if (sp == null) row.note else listOfNotNull(row.note, Texts.SAVED).joinToString(" · ")
            results.add(row.copy(note = note))
            places2[row.key] = place
        }
        resultPlaces = places2

        val phase = when {
            noData && !start -> SearchPhase.NO_SEARCH_DATA
            noData && start -> SearchPhase.NO_SEARCH_DATA
            start -> SearchPhase.START
            !answered -> SearchPhase.SEARCHING
            results.isEmpty() && savedResults.isEmpty() -> SearchPhase.NO_RESULTS
            else -> SearchPhase.RESULTS
        }
        val msg = message ?: when (phase) {
            // nothing installed: add a state; installed but none searchable: telling the driver to
            // add the state they already have would send them in a circle (review finding)
            SearchPhase.NO_SEARCH_DATA -> if (noDataWithPacks) Texts.SEARCH_UNUSABLE else Texts.NO_SEARCH_DATA
            SearchPhase.NO_RESULTS -> Texts.NO_RESULTS
            else -> null
        }
        val startPick = mode == SearchMode.SET_START
        state.value = SearchUi(
            mode = mode,
            query = query,
            phase = phase,
            shortcuts = if (start && !save && !noData) rules.shortcuts.map { ShortcutUi(it.id, it.label, it.icon) } else emptyList(),
            recents = if (start) recentUi else emptyList(),
            results = if (phase == SearchPhase.RESULTS || phase == SearchPhase.SEARCHING) results else emptyList(),
            nearText = nearLine(start),
            message = msg,
            undoText = if (clearedRecents != null) Texts.RECENTS_CLEARED else null,
            noResultsHint = if (phase == SearchPhase.NO_RESULTS && typedByHand) Texts.NO_RESULTS_HINT else null,
            title = if (save) titleFor(target) else if (startPick) Texts.CHOOSE_START else null,
            slots = slots,
            savedPlacesText = savedPlacesText,
            chooseOnMap = start && (save || startPick),
            savedResults = savedResults,
            savedNotice = notice,
            // P22: "Your position" with a fresh fix (the context is GPS only then); without one it would lead back to
            // the NO_POSITION problem whose "Choose start" opened this picker
            useMyPosition = start && ((save && positionUsable()) || (startPick && context.source == NearSource.GPS && context.near != null)),
            focusField = true,   // P26 (0.1.40, decisions.md): in every mode, ADD_STOP while driving too
            allTypes = allTypes,
            // review F4 (P30): a GO_TO search during a trip (a link's words while guiding) offers no States
            statesOffered = !(mode == SearchMode.GO_TO && driving),
        )
    }

    /**
     * The line under the field: ADD_STOP along the trip's route (S12) says so ("Along your route", "Along your
     * route after Wegmans", "None close to your route. Nearest shown."); otherwise where the rows are ranked from
     * (S1, P6): the place the words name ("Near Corry"), the town at the map's centre, or the user's position.
     */
    private fun nearLine(start: Boolean): String? = when {
        mode != SearchMode.ADD_STOP || along.isEmpty() -> answerPlace?.takeIf { !start }?.let(Texts::near) ?: Texts.nearText(context, areaName)
        alongWidened && !start -> Texts.ALONG_NONE_CLOSE
        alongAfter != null -> Texts.alongAfter(alongAfter!!)
        else -> Texts.ALONG_ROUTE
    }

    private fun titleFor(t: SaveTarget?): String? = when (t) {
        SaveTarget.Home -> Texts.SET_HOME
        SaveTarget.Work -> Texts.SET_WORK
        SaveTarget.NewPlace -> Texts.SAVE_PLACE
        is SaveTarget.Move -> Texts.newLocationFor(saved.byId(t.savedId)?.name ?: "")
        SaveTarget.Start -> Texts.CHOOSE_START   // P22 (never a SAVE target; for completeness)
        null -> null
    }

    private fun savedRow(sp: SavedPlace, set: InstalledSet, u: UnitFormatter): SavedRowUi {
        val note = when {
            mode == SearchMode.SET_START && isDestination(sp) -> Texts.ALREADY_DESTINATION
            !covered(set, sp.point) -> Texts.OUTSIDE
            else -> null
        }
        return SavedRowUi(sp.id, sp.kind, Icons.forKind(sp.kind), sp.name, sp.description, distanceText(context, sp.point, u), note)
    }

    companion object {
        const val SAVED_KEY = "saved:"
        /** Batch O3: the packId of an own place's Place ("own:<outbox id>"). */
        const val OWN_PACK = "own:"
        /** Batch O3: how near an own change must be to a pack place of its old or new name to be that place. */
        const val OWN_MATCH_M = 75.0
        /** Item 51: a detour under this many seconds reads "On the way" and sorts first. */
        const val ON_THE_WAY_S = 60.0
        /** Item 51: the key of the one row of a place picked for a stop that is not an engine row (a saved place, a recent). */
        const val STOP_ROW_KEY = "stop:0"
        private val FAILED = MultiSearchResponse("", emptyList(), emptyList(), true, null, null, 0.0)
        private val FAILED_ANSWER = Answer(Along(FAILED, false), emptySet())
    }
}

/**
 * Batch O3 ("View all", docs/next-round.md item 27): the categories under plain headings, groups in the
 * order the packs list them (categories.json's), each with its icon (the map's and the rows') and query.
 */
internal fun groupTypes(cats: List<CategoryInfo>): List<PlaceTypeGroupUi> {
    val groups = LinkedHashMap<String, MutableList<PlaceTypeUi>>()
    for (c in cats) {
        val t = PlaceTypeUi(c.code, c.label, Icons.forCategory(c.code) ?: Icons.OTHER, c.query)
        groups.getOrPut(Texts.typeGroup(c.group)) { ArrayList() }.add(t)
    }
    return groups.map { (h, t) -> PlaceTypeGroupUi(h, t) }
}

/** Batch O3: the sprite icon of a place type (the same as the map's and the result rows'), or null when it has none. */
fun placeTypeIcon(code: String): String? = Icons.forCategory(code)

/**
 * Rule S11: which of the engine's matches go on the map, nearest first (ties in the engine's order):
 * those within [SearchAppRules.mapWithinKm], at most [SearchAppRules.mapMaxResults]; when none is
 * that close, the nearest [SearchAppRules.mapNearestWhenNone] anyway. Matches without a distance
 * come after the others in the engine's order and count as not within; when none has one (no
 * ranking position), the engine's first [SearchAppRules.mapMaxResults].
 */
internal fun <T> nearestOnMap(items: List<T>, km: (T) -> Double?, rules: SearchAppRules): List<T> {
    if (items.none { km(it) != null }) return items.take(rules.mapMaxResults)
    val sorted = items.withIndex().sortedWith(compareBy({ km(it.value) ?: Double.MAX_VALUE }, { it.index })).map { it.value }
    val within = sorted.filter { d -> km(d)?.let { it <= rules.mapWithinKm } == true }
    return if (within.isNotEmpty()) within.take(rules.mapMaxResults) else sorted.take(minOf(rules.mapNearestWhenNone, rules.mapMaxResults))
}

/**
 * P6 (0.1.40): an answer not led by a category or a brand: the engine's best match first, then the
 * others nearest first (ties, and rows without a distance at the end, in the engine's order); none with
 * a distance (no ranking position, no named place): the engine's order.
 */
internal fun <T> bestThenNearest(items: List<T>, km: (T) -> Double?): List<T> {
    if (items.size < 3 || items.drop(1).none { km(it) != null }) return items
    val rest = items.drop(1).withIndex().sortedWith(compareBy({ km(it.value) ?: Double.MAX_VALUE }, { it.index })).map { it.value }
    return listOf(items[0]) + rest
}

/**
 * P2 (0.1.40): the rows of a category's or brand's answer that only matched by name ("Erie Animal
 * Hospital" for "hospital"): after the category's own, only those within on_map.within_km, nearest
 * first; none with a distance (no position): the engine's order.
 */
internal fun <T> withinNearest(items: List<T>, km: (T) -> Double?, rules: SearchAppRules): List<T> {
    if (items.none { km(it) != null }) return items
    return items.withIndex().filter { (_, v) -> km(v)?.let { it <= rules.mapWithinKm } == true }
        .sortedWith(compareBy({ km(it.value)!! }, { it.index })).map { it.value }
}
