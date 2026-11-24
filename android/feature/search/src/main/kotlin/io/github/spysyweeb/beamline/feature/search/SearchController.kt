package io.github.spysyweeb.beamline.feature.search

import io.github.spysyweeb.beamline.api.data.InstalledSet
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.model.SavedKind
import io.github.spysyweeb.beamline.api.model.SavedPlace
import io.github.spysyweeb.beamline.search.CategoryInfo
import io.github.spysyweeb.beamline.search.Geo
import io.github.spysyweeb.beamline.search.MultiSearchResponse
import io.github.spysyweeb.beamline.search.NearbyPoi
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
}

/**
 * The body of [SearchFeature] (rules S1-S7 of docs/architecture.md 3.8; SP4, SP10-SP15,
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
    fun open(mode: SearchMode, context: SearchContext, driving: Boolean, destination: Place?) {
        require(mode != SearchMode.SAVE) { "SAVE mode opens through openToSave" }
        reset()
        resultsState.value = null   // a new search ends the results on the map (S11)
        this.mode = mode
        this.context = context
        this.driving = driving
        this.destination = destination
        isOpen = true
        prepare()
        refresh()
    }

    fun openToSave(target: SaveTarget, context: SearchContext) {
        reset()
        this.mode = SearchMode.SAVE
        this.target = target
        this.openedToSave = true
        this.context = context
        isOpen = true
        prepare()
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
        answered = false
        message = null
        allTypes = null
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
        is SearchEvent.QueryChanged -> { typed(e.text, debounce = true); null }
        is SearchEvent.ShortcutTapped -> {
            // a chip is a whole question ("Gas"): its answer goes straight to the map as pins, as Enter does (the
            // owner, 2026-11-21); where Enter does not apply (choosing a stop, saving, driving) it is a list as before
            rules.shortcuts.firstOrNull { it.id == e.id }?.let { typed(it.query, debounce = false, thenToMap = true) }
            null
        }
        SearchEvent.ViewAll -> { openViewAll(); null }
        is SearchEvent.TypeTapped -> {
            allTypes = null
            typed(e.type.query, debounce = false, thenToMap = true)
            null
        }
        SearchEvent.CloseViewAll -> { allTypes = null; refresh(); null }
        is SearchEvent.RowTapped -> rowTapped(e.key)
        is SearchEvent.RemoveRecent -> { recentRows[e.key]?.let { recents.remove(it) }; null }
        SearchEvent.ClearRecents -> { recents.clear(); null }
        SearchEvent.OpenStates -> SearchOutput.OpenStates
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
     * led by a category or a brand match: the nearest first, up to on_map.max_results within
     * on_map.within_km (25 miles), or the nearest on_map.nearest_when_none_within when none is that close
     * ([nearestOnMap]; the owner's rule for the map since batch C2, now the list's too, so the list is in
     * distance order). Any other answer (a name, a street, an address): the engine's best merge.limit, in
     * its order.
     */
    private fun shaped(r: MultiSearchResponse): MultiSearchResponse {
        val first = r.results.firstOrNull()?.result
        return if (first != null && first.tier in classTiers) r.copy(results = nearestOnMap(r.results, { it.result.distanceKm }, rules))
        else r.copy(results = r.results.take(rules.mergeLimit))
    }

    /** [thenToMap]: once answered, the rows go to the map as Enter puts them ([showOnMap]). */
    private fun typed(text: String, debounce: Boolean, thenToMap: Boolean = false) {
        query = text
        message = null
        val my = ++seq
        engineRowsText = null
        if (text.isBlank()) {
            engineRows = emptyList()
            answered = false
            refresh()
            return
        }
        answered = false
        refresh()
        if (noData) return
        val set = installed.value
        val near = context.near
        scope.launch {
            if (debounce) delay(rules.debounceMs)
            if (my != seq) return@launch                      // a newer text is pending: only the newest runs
            val r = withContext(searchDispatcher) {
                try {
                    // asked for on_map.candidates rows: a category's nearest are not always the engine's first few
                    if (!searcher.hasSearchData(set)) null
                    else shaped(searcher.search(set, text, near, maxOf(rules.mergeLimit, rules.mapCandidates))).also { fetchDetails(set, it) }
                } catch (_: Exception) {
                    FAILED   // a pack that cannot be read now: no rows (the exception and its text are dropped, PV2)
                }
            }
            if (my != seq) return@launch                      // answered too late: dropped
            if (r === FAILED) {
                engineRows = emptyList()
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
            engineRows = rowsOf(r)
            engineRowsText = text
            answered = true
            refresh()
            if (thenToMap) showOnMap(text)
        }
    }

    private fun rowsOf(r: MultiSearchResponse): List<Pair<ResultRowUi, Place>> = r.results.map { pr ->
        val res = pr.result
        val place = Place(res.title, GeoPoint(res.lat, res.lon), PlaceSource.SEARCH, res.subtitle.ifEmpty { null }, res.categoryId, res.id, pr.packId)
        ResultRowUi(
            key = "r:${pr.packId}:${res.id}", iconName = Icons.forResult(res), title = res.title,
            subtitle = res.subtitle.ifEmpty { null }, distanceText = distanceText(context, place.point, units.value),
            note = addressNote(res.address), hours = hoursOf(place, withWeek = false),
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
     * Enter (S11): GO_TO only, not driving. The map shows the rows the list shows for this text, in the
     * list's order, at most on_map.max_results of them (the owner, 2026-11-21: until then Enter asked
     * the engine again for the nearest matches, and "walmart" listed nine places and showed five). An
     * answer that has not come yet is waited for.
     */
    private fun submit() {
        if (mode != SearchMode.GO_TO || driving || query.isBlank() || noData) return
        if (answered && engineRowsText == query) showOnMap(query) else typed(query, debounce = false, thenToMap = true)
    }

    /** The list's rows for [text] as pins on the map: none stays the list (its "no results" line), one opens that place, more close the search and show them. */
    private fun showOnMap(text: String) {
        if (mode != SearchMode.GO_TO || driving || !answered || engineRowsText != text) return
        val rows = (ownRows(text) + engineRows.filter { (_, p) -> !hiddenByOwn(p) }).take(rules.mapMaxResults)
        when (rows.size) {
            0 -> Unit
            1 -> picked(rows[0].second)?.let { laterFlow.tryEmit(it) }
            else -> {
                val shown = ResultsUi(text, rows.map { it.first }, rows.map { it.second })
                close()
                resultsState.value = shown
                laterFlow.tryEmit(SearchOutput.ShowResults(shown.places))
            }
        }
    }

    /** S11: the results end when a state one of them came from is no longer installed; true when they ended. */
    fun closeResultsGoneFrom(set: InstalledSet): Boolean {
        val cur = resultsState.value ?: return false
        val packs = set.packs.mapTo(HashSet()) { it.packId }
        if (cur.places.all { p -> p.packId.let { it == null || it in packs || it.startsWith(OWN_PACK) } }) return false
        resultsState.value = null
        return true
    }

    fun onResultsEvent(e: ResultsEvent): SearchOutput? {
        val cur = resultsState.value ?: return null
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
        }
    }

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

    /** The place's values with the user's own on top: hours, phone and website set by the user, the type in words. */
    private fun ownDetails(o: OwnPlaceUi, pack: PoiDetails?): PoiDetails {
        fun v(key: String, packValue: String?) = if (key in o.overrides) o.overrides[key] else packValue
        return PoiDetails(
            openingHours = if (o.closed) null else v("opening_hours", pack?.openingHours),
            phone = if (o.closed) null else v("phone", pack?.phone),
            website = if (o.closed) null else v("website", pack?.website),
            address = pack?.address,
            category = if (o.closed) Texts.CLOSED_PERMANENTLY else o.categoryLabel ?: pack?.category,
            osm = o.osm ?: pack?.osm,
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
            return useSaved(sp)
        }
        val place = resultPlaces[key] ?: recentRows[key] ?: return null
        return picked(place)
    }

    /** A result or a recent (or "Use my position") picked in the current mode. */
    private fun picked(place: Place): SearchOutput? {
        if (mode == SearchMode.SAVE) return savePick(place)
        if (place.source != PlaceSource.SAVED) recents.add(place)
        val out = SearchOutput.Picked(place, mode)
        close()
        return out
    }

    /** A saved place tapped (slot, saved result row): SP12-SP14. Never a recent (SP31). */
    private fun useSaved(sp: SavedPlace): SearchOutput? {
        val p = sp.toPlace()
        if (mode == SearchMode.SET_START && isDestination(sp)) return null
        val inside = covered(installed.value, sp.point)
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
        answered = false
        message = null
        refresh()
    }

    private fun chooseOnMap(): SearchOutput? {
        val t = target ?: return null
        if (mode != SearchMode.SAVE) return null
        if (openedToSave) close() else backToGoTo()
        return SearchOutput.PickOnMap(t)
    }

    private fun useMyPosition(): SearchOutput? {
        if (mode != SearchMode.SAVE || !positionUsable()) return null
        val fix = context.near ?: return null
        return savePick(Place(Texts.YOUR_POSITION, fix, PlaceSource.MAP_PIN, subtitle = pointText(fix)))
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
        return PlaceSheetUi(
            place = place, iconName = own?.categoryCode?.let { Icons.forCategory(it) } ?: Icons.forPlace(place, sp),
            title = own?.name?.takeIf { it.isNotBlank() } ?: place.name, subtitle = place.subtitle,
            distanceText = distanceText(context, place.point, units.value), note = if (inside) null else Texts.OUTSIDE,
            actions = actions, savedText = sp?.takeIf { it.name != place.name }?.let { Texts.savedAs(it.name) }, savedId = sp?.id,
            hours = HoursTexts.ui(d?.openingHours, hoursClock(), withWeek = true),
            phone = phone.takeIf { !guiding }, website = website.takeIf { !guiding },
            // The details height (batch G): written out while driving too; a tap opens nothing while guiding.
            phones = HoursTexts.phones(d?.phone), websites = HoursTexts.websites(d?.website),
            address = d?.address, category = d?.category, canOpenOutside = !guiding,
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
        state.value = SearchUi(
            mode = mode,
            query = query,
            phase = phase,
            shortcuts = if (start && !save && !noData) rules.shortcuts.map { ShortcutUi(it.id, it.label, it.icon) } else emptyList(),
            recents = if (start) recentUi else emptyList(),
            results = if (phase == SearchPhase.RESULTS || phase == SearchPhase.SEARCHING) results else emptyList(),
            nearText = Texts.nearText(context),
            message = msg,
            title = if (save) titleFor(target) else null,
            slots = slots,
            savedPlacesText = savedPlacesText,
            chooseOnMap = save && start,
            savedResults = savedResults,
            savedNotice = notice,
            useMyPosition = save && start && positionUsable(),
            focusField = !(mode == SearchMode.ADD_STOP && driving),
            allTypes = allTypes,
        )
    }

    private fun titleFor(t: SaveTarget?): String? = when (t) {
        SaveTarget.Home -> Texts.SET_HOME
        SaveTarget.Work -> Texts.SET_WORK
        SaveTarget.NewPlace -> Texts.SAVE_PLACE
        is SaveTarget.Move -> Texts.newLocationFor(saved.byId(t.savedId)?.name ?: "")
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
        private val FAILED = MultiSearchResponse("", emptyList(), emptyList(), true, null, null, 0.0)
    }
}

/**
 * Rule S11: which of the engine's matches go on the map, nearest first (ties in the engine's order):
 * those within [SearchAppRules.mapWithinKm], at most [SearchAppRules.mapMaxResults]; when none is
 * that close, the nearest [SearchAppRules.mapNearestWhenNone] anyway. Matches without a distance
 * come after the others in the engine's order and count as not within; when none has one (no
 * ranking position), the engine's first [SearchAppRules.mapMaxResults].
 */
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

internal fun <T> nearestOnMap(items: List<T>, km: (T) -> Double?, rules: SearchAppRules): List<T> {
    if (items.none { km(it) != null }) return items.take(rules.mapMaxResults)
    val sorted = items.withIndex().sortedWith(compareBy({ km(it.value) ?: Double.MAX_VALUE }, { it.index })).map { it.value }
    val within = sorted.filter { d -> km(d)?.let { it <= rules.mapWithinKm } == true }
    return if (within.isNotEmpty()) within.take(rules.mapMaxResults) else sorted.take(minOf(rules.mapNearestWhenNone, rules.mapMaxResults))
}
