package io.github.spysyweeb.beamline.feature.search

import io.github.spysyweeb.beamline.api.data.InstalledSet
import io.github.spysyweeb.beamline.api.data.PackInfo
import io.github.spysyweeb.beamline.api.data.RoutingSet
import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.model.SavedKind
import io.github.spysyweeb.beamline.search.AddressInfo
import io.github.spysyweeb.beamline.search.MultiSearchResponse
import io.github.spysyweeb.beamline.search.PackResult
import io.github.spysyweeb.beamline.search.RecentPlaces
import io.github.spysyweeb.beamline.search.SearchResult
import io.github.spysyweeb.beamline.search.awaitStoreWrites
import io.github.spysyweeb.beamline.search.saved.SaveResult
import io.github.spysyweeb.beamline.search.saved.SavedPlaces
import io.github.spysyweeb.beamline.search.saved.SavedRules
import io.github.spysyweeb.beamline.units.UnitFormatter
import io.github.spysyweeb.beamline.units.UnitRules
import io.github.spysyweeb.beamline.units.UnitSystem
import java.io.File
import java.util.concurrent.Executor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** A fake of the packs: answers by text, records every search, can type during a search. */
internal class FakeSearcher : PackSearcher {
    val asked = ArrayList<String>()
    var hasData = true
    var answers: (String) -> List<PackResult> = { emptyList() }
    var during: (String) -> Unit = {}
    override fun prepare(set: InstalledSet) {}
    override fun hasSearchData(set: InstalledSet) = hasData
    override fun search(set: InstalledSet, text: String, near: GeoPoint?, limit: Int): MultiSearchResponse {
        asked.add(text)
        during(text)
        limits.add(limit)
        return MultiSearchResponse(text, answers(text).take(limit), listOf("us-pa"), true, null, false, 1.0)
    }
    val limits = ArrayList<Int>()
    /** Rule S10: details by search id (pack us-pa). */
    var details: Map<Long, io.github.spysyweeb.beamline.search.PoiDetails> = emptyMap()
    override fun details(set: InstalledSet, packId: String, ids: Collection<Long>) = details.filterKeys { it in ids }
    /** Rule S10 without a search id (batch C2): the details by name; the points asked are kept. */
    var detailsByName: Map<String, io.github.spysyweeb.beamline.search.PoiDetails> = emptyMap()
    val askedAt = ArrayList<Pair<String, GeoPoint>>()
    override fun detailsAt(set: InstalledSet, name: String, point: GeoPoint): io.github.spysyweeb.beamline.search.PoiDetails? {
        askedAt.add(name to point)
        return detailsByName[name]
    }
}

/**
 * SearchFeature (docs/architecture.md 3.8 S1-S7, 5.1 S "the controller"; docs/architecture-saved-places.md
 * 10.1 :feature:search items 1-6). A virtual clock, a fake of the packs, made-up places at public points.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchFeatureTest {
    @TempDir
    lateinit var dir: File

    companion object {
        val MUSEUM = GeoPoint(42.13646, -80.08718)        // Erie Maritime Museum
        val AIRPORT = GeoPoint(42.08418, -80.17321)       // Erie International Airport
        val MALL = GeoPoint(42.06875, -80.09813)          // Millcreek Mall
        val ZOO = GeoPoint(42.0971, -80.07578)            // Erie Zoo (where the user is)
        val PITTSBURGH = GeoPoint(40.4406, -79.9959)

        val gps = SearchContext(ZOO, NearSource.GPS, accuracyM = 10.0)
        val mapCentre = SearchContext(ZOO, NearSource.MAP_CENTER)
        val units = UnitFormatter(UnitRules.bundled(), UnitSystem.IMPERIAL)

        /** Routing tiles around Erie only: Pittsburgh is outside the installed maps. */
        val erieTiles = buildSet {
            var lat = 41.9
            while (lat < 42.3) {
                var lon = -80.4
                while (lon < -79.9) {
                    add(RoutingSet.level2TileId(GeoPoint(lat, lon)))
                    lon += 0.1
                }
                lat += 0.1
            }
        }

        fun pa(searchFile: String? = "pa-search.sqlite") = PackInfo(
            packId = "us-pa", name = "Pennsylvania", state = "PA", osmSnapshot = "2026-11-14",
            bounds = Bounds(-80.52109, 39.71977, -74.68967, 42.51613), attribution = "OpenStreetMap", folder = "packs/us-pa",
            mapFile = "packs/us-pa/map.pmtiles", mapMinZoom = 0, mapMaxZoom = 14, searchFile = searchFile, addressesFile = null,
            addressesSkipped = true, searchProblem = null, searchRankingSha256 = "r", inRoutingSet = true, verified = true, bytesOnDisk = 0,
        )

        fun withMaps() = InstalledSet(1, true, listOf(pa()), RoutingSet("valhalla.json", "g", "3.9.1", listOf("PA"), erieTiles))
        fun noRouting() = InstalledSet(2, true, listOf(pa()), null)

        fun row(id: Long, title: String, p: GeoPoint, sub: String = "Erie", cat: String? = "museum", score: Double = 1.0,
                kind: SearchResult.Kind = SearchResult.Kind.POI, address: AddressInfo? = null) =
            PackResult("us-pa", SearchResult(id, kind, title, sub, cat, null, p.lat, p.lon, 1.0, null, address, score, "t", null))

        fun place(name: String, p: GeoPoint, sub: String? = "Erie", id: Long = 1) = Place(name, p, PlaceSource.SEARCH, sub, "museum", id, "us-pa")

        val museumPlace = place("Erie Maritime Museum", MUSEUM, "100 East Front Street, Erie", 11)
        val mallPlace = place("Millcreek Mall", MALL, "5800 Peach Street, Erie", 12)
    }

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val installed = MutableStateFlow(withMaps())
    private lateinit var recents: RecentPlaces
    private lateinit var saved: SavedPlaces
    private val fake = FakeSearcher()
    private lateinit var f: SearchFeature

    @BeforeEach
    fun setUp() {
        recents = RecentPlaces(File(dir, "recents.json"))
        saved = SavedPlaces(File(dir, "saved-places.json"), SavedRules.bundled(), Executor { it.run() })
        f = SearchFeature(installed, recents, saved, MutableStateFlow(units), scope, dispatcher, fake)
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        awaitStoreWrites()
    }

    private val ui get() = f.ui.value

    private fun type(text: String) {
        f.onEvent(SearchEvent.QueryChanged(text))
    }

    private fun settle() {
        scope.advanceTimeBy(1_000)
        scope.runCurrent()
    }

    private fun home(p: Place = museumPlace) = (saved.save(p, SavedKind.HOME) as SaveResult.Done).place!!
    private fun work(p: Place = Place("Point on the map", AIRPORT, PlaceSource.MAP_PIN)) = (saved.save(p, SavedKind.WORK) as SaveResult.Done).place!!

    // ---------------------------------------------------------------- S1, S2, S6 and the START screen
    @Test
    fun startScreenWithShortcutsNearTextAndNothingSaved() {
        f.open(SearchMode.GO_TO, gps)
        settle()
        assertEquals(SearchPhase.START, ui.phase)
        assertEquals("Near your position", ui.nearText)
        assertEquals(listOf("Gas", "Rest stops", "Food", "Coffee", "Parking", "EV charging", "Hotels"), ui.shortcuts.map { it.label })
        assertEquals(listOf("fuel", "rest_area", "restaurant", "cafe", "parking", "charging_station", "hotel"), ui.shortcuts.map { it.iconName })
        assertEquals(listOf("Set home", "Set work"), ui.slots.map { it.title })
        assertTrue(ui.slots.all { it.savedId == null && it.subtitle == null && it.distanceText == null })
        assertEquals(listOf("saved_home", "saved_work"), ui.slots.map { it.iconName })
        assertEquals("None yet", ui.savedPlacesText)
        assertNull(ui.savedNotice)
        assertTrue(ui.focusField)
        f.open(SearchMode.GO_TO, mapCentre)
        assertEquals("Near the map center", ui.nearText)
    }

    @Test
    fun slotsWithHomeSet() {
        home()
        f.open(SearchMode.GO_TO, gps)
        settle()
        val h = ui.slots[0]
        assertEquals("Home", h.title)
        assertEquals("Erie Maritime Museum, 100 East Front Street, Erie", h.subtitle)
        assertTrue(h.distanceText!!.endsWith("mi"), h.distanceText)
        assertEquals("Set work", ui.slots[1].title)
        assertEquals("1 place", ui.savedPlacesText)
        // the map centre: no distance (SP10)
        f.open(SearchMode.GO_TO, mapCentre)
        assertNull(ui.slots[0].distanceText)
        // outside the routing tiles: the note
        installed.value = noRouting()
        f.open(SearchMode.GO_TO, gps)
        assertEquals("Outside the installed maps", ui.slots[0].note)
        // counts every saved place
        installed.value = withMaps()
        work()
        saved.save(mallPlace, SavedKind.OTHER, "Quokka Shop")
        f.open(SearchMode.GO_TO, gps)
        assertEquals("3 places", ui.savedPlacesText)
    }

    @Test
    fun addStopAndSetStartShowOnlySetSlots() {
        f.open(SearchMode.ADD_STOP, gps)
        assertEquals(emptyList<SavedRowUi>(), ui.slots)
        assertNull(ui.savedPlacesText)                     // no other place: no "Saved places" row
        home()
        f.open(SearchMode.ADD_STOP, gps)
        assertEquals(listOf("Home"), ui.slots.map { it.title })
        assertNull(ui.savedPlacesText)
        saved.save(mallPlace, SavedKind.OTHER, "Quokka Shop")
        f.open(SearchMode.SET_START, gps)
        assertEquals("2 places", ui.savedPlacesText)
        // SP4: ADD_STOP while driving does not raise the keyboard
        f.open(SearchMode.ADD_STOP, gps, driving = true)
        assertFalse(ui.focusField)
        assertEquals(listOf("Home"), ui.slots.map { it.title })
        f.open(SearchMode.ADD_STOP, gps, driving = false)
        assertTrue(ui.focusField)
    }

    @Test
    fun notices() {
        File(dir, "b").mkdirs()
        File(dir, "b/saved-places.json").writeText("{")
        val broken = SavedPlaces(File(dir, "b/saved-places.json"), SavedRules.bundled(), Executor { it.run() })
        val g = SearchFeature(installed, recents, broken, MutableStateFlow(units), scope, dispatcher, fake)
        g.open(SearchMode.GO_TO, gps)
        assertEquals(Texts.BROKEN_NOTICE, g.ui.value.savedNotice)
        g.onEvent(SearchEvent.DismissSavedNotice)
        assertNull(g.ui.value.savedNotice)
        File(dir, "n").mkdirs()
        File(dir, "n/saved-places.json").writeText("""{"format":"beamline-saved-places/2","places":[]}""")
        val newer = SavedPlaces(File(dir, "n/saved-places.json"), SavedRules.bundled(), Executor { it.run() })
        val h = SearchFeature(installed, recents, newer, MutableStateFlow(units), scope, dispatcher, fake)
        h.open(SearchMode.GO_TO, gps)
        assertEquals(Texts.NEWER_NOTICE, h.ui.value.savedNotice)
        assertEquals(emptyList<SavedRowUi>(), h.ui.value.slots)   // SP11: no rows in a read-only store
        assertNull(h.ui.value.savedPlacesText)
        h.onEvent(SearchEvent.DismissSavedNotice)
        assertNull(h.ui.value.savedNotice)
    }

    // ---------------------------------------------------------------- S2: debounce and stale answers
    @Test
    fun onlyTheNewestPendingTextIsSearched() {
        f.open(SearchMode.GO_TO, gps)
        type("s")
        scope.advanceTimeBy(50)
        type("sh")
        scope.advanceTimeBy(50)
        type("she")
        assertEquals(SearchPhase.SEARCHING, ui.phase)
        assertEquals("she", ui.query)
        scope.advanceTimeBy(119)
        scope.runCurrent()
        assertEquals(emptyList<String>(), fake.asked)        // 120 ms debounce not over yet
        settle()
        assertEquals(listOf("she"), fake.asked)
    }

    @Test
    fun anAnswerForAnOlderTextIsDropped() {
        fake.answers = { t -> listOf(row(if (t == "sheetz") 2 else 1, if (t == "sheetz") "Sheetz" else "Shell", MALL)) }
        fake.during = { t -> if (t == "she") type("sheetz") }   // the user types on while "she" is running
        f.open(SearchMode.GO_TO, gps)
        type("she")
        settle()
        assertEquals(listOf("she", "sheetz"), fake.asked)
        assertEquals(listOf("Sheetz"), ui.results.map { it.title })
        assertEquals("sheetz", ui.query)
        assertEquals(SearchPhase.RESULTS, ui.phase)
    }

    @Test
    fun clearingTheTextShowsStartAgain() {
        recents.add(mallPlace)
        f.open(SearchMode.GO_TO, gps)
        settle()
        assertEquals(listOf("Millcreek Mall"), ui.recents.map { it.title })
        fake.answers = { listOf(row(1, "Shell", MALL)) }
        type("sh")
        settle()
        assertEquals(SearchPhase.RESULTS, ui.phase)
        assertEquals(emptyList<ResultRowUi>(), ui.recents)
        type("")
        assertEquals(SearchPhase.START, ui.phase)
        assertEquals(listOf("Millcreek Mall"), ui.recents.map { it.title })
    }

    @Test
    fun shortcutsSearchTheirTermAtOnce() {
        fake.answers = { listOf(row(5, "Sheetz", MALL, cat = "fuel"), row(6, "Sheetz", ZOO, cat = "fuel")) }
        f.open(SearchMode.GO_TO, gps)
        f.onEvent(SearchEvent.ShortcutTapped("hotels"))
        assertEquals("lodging", ui.query)
        scope.runCurrent()
        assertEquals(listOf("lodging"), fake.asked)          // no debounce for a tap
        // the owner, 2026-11-21: a chip's answer goes straight to the map as pins, as Enter does
        assertEquals(listOf("fuel", "fuel"), f.results.value!!.rows.map { it.iconName })
        assertEquals("lodging", f.results.value!!.query)
        assertEquals(SearchUi(), f.ui.value)
        // choosing a stop: a list, as before (Enter does not apply there)
        f.onResultsEvent(ResultsEvent.Close)
        f.open(SearchMode.ADD_STOP, gps)
        f.onEvent(SearchEvent.ShortcutTapped("hotels"))
        scope.runCurrent()
        assertNull(f.results.value)
        assertEquals("fuel", ui.results[0].iconName)
    }

    @Test
    fun noResultsAndNoSearchData() {
        f.open(SearchMode.GO_TO, gps)
        type("zzzq")
        settle()
        assertEquals(SearchPhase.NO_RESULTS, ui.phase)
        assertEquals(Texts.NO_RESULTS, ui.message)
        // nothing installed: NO_SEARCH_DATA with "Add a state"
        fake.hasData = false
        installed.value = InstalledSet(3, true, emptyList(), null)
        f.open(SearchMode.GO_TO, gps)
        settle()
        assertEquals(SearchPhase.NO_SEARCH_DATA, ui.phase)
        assertEquals(Texts.NO_SEARCH_DATA, ui.message)
        assertEquals(emptyList<ShortcutUi>(), ui.shortcuts)
        assertEquals(listOf("Set home", "Set work"), ui.slots.map { it.title })   // the rows stay
        assertEquals(SearchOutput.OpenStates, f.onEvent(SearchEvent.OpenStates))
        // saved names still match with no search data (SP21)
        home()
        type("ho")
        settle()
        assertEquals(SearchPhase.NO_SEARCH_DATA, ui.phase)
        assertEquals(listOf("Home"), ui.savedResults.map { it.title })
    }

    /**
     * A state is installed but none can be searched (P marked the search part unusable, or the hub
     * refused it at open): still NO_SEARCH_DATA with "Add a state" (3.8 S7), but the line does not
     * tell the driver to add the state they already have (review finding).
     */
    @Test
    fun installedButNotSearchableSaysSo() {
        fake.hasData = false
        installed.value = InstalledSet(4, true, listOf(pa(searchFile = null).copy(searchProblem = "built with another address normaliser")), null)
        f.open(SearchMode.GO_TO, gps)
        settle()
        assertEquals(SearchPhase.NO_SEARCH_DATA, ui.phase)
        assertEquals(Texts.SEARCH_UNUSABLE, ui.message)
        assertFalse(ui.message!!.contains("needs a state"))
        assertEquals(SearchOutput.OpenStates, f.onEvent(SearchEvent.OpenStates))
        // learned on a typed search too (the first check said yes, the pack failed later)
        fake.hasData = true
        installed.value = withMaps()
        f.open(SearchMode.GO_TO, gps)
        settle()
        assertEquals(SearchPhase.START, ui.phase)
        fake.hasData = false
        type("sheetz")
        settle()
        assertEquals(SearchPhase.NO_SEARCH_DATA, ui.phase)
        assertEquals(Texts.SEARCH_UNUSABLE, ui.message)
        // and nothing installed keeps its own line
        installed.value = InstalledSet(5, true, emptyList(), null)
        f.open(SearchMode.GO_TO, gps)
        settle()
        assertEquals(Texts.NO_SEARCH_DATA, ui.message)
    }

    @Test
    fun aPackThatFailsGivesNoResultsNotAnEndlessSearch() {
        fake.answers = { throw IllegalStateException("database is closed") }
        f.open(SearchMode.GO_TO, gps)
        type("sheetz")
        settle()
        assertEquals(SearchPhase.NO_RESULTS, ui.phase)
        assertEquals(Texts.NO_RESULTS, ui.message)
    }

    // ---------------------------------------------------------------- picks and modes
    @Test
    fun picksInEachModeAndRecents() {
        fake.answers = { listOf(row(12, "Millcreek Mall", MALL, "5800 Peach Street, Erie", "mall")) }
        for (mode in listOf(SearchMode.GO_TO, SearchMode.ADD_STOP, SearchMode.SET_START)) {
            f.open(mode, gps)
            type("millcreek")
            settle()
            val out = f.onEvent(SearchEvent.RowTapped(ui.results[0].key)) as SearchOutput.Picked
            assertEquals(mode, out.mode)
            assertEquals("Millcreek Mall", out.place.name)
            assertEquals(12L, out.place.searchId)
            assertEquals("us-pa", out.place.packId)
            assertEquals(PlaceSource.SEARCH, out.place.source)
        }
        assertEquals(listOf("Millcreek Mall"), recents.places.value.map { it.name })   // one recent, de-duplicated
        // a recent row picks too
        f.open(SearchMode.GO_TO, gps)
        val r = f.onEvent(SearchEvent.RowTapped(ui.recents[0].key)) as SearchOutput.Picked
        assertEquals(PlaceSource.RECENT, r.place.source)
        f.open(SearchMode.GO_TO, gps)
        f.onEvent(SearchEvent.RemoveRecent(ui.recents[0].key))
        assertEquals(emptyList<Place>(), recents.places.value)
        recents.add(museumPlace)
        f.onEvent(SearchEvent.ClearRecents)
        assertEquals(emptyList<Place>(), recents.places.value)
        assertEquals(SearchOutput.Closed, f.onEvent(SearchEvent.Close))
        assertEquals(SearchOutput.OpenSavedPlaces(SearchMode.GO_TO), run { f.open(SearchMode.GO_TO, gps); f.onEvent(SearchEvent.OpenSavedPlaces) })
    }

    @Test
    fun savedPlaceTaps() {
        val h = home()
        // GO_TO covered: PlanTrip with source SAVED and the saved id; no recent
        f.open(SearchMode.GO_TO, gps)
        val plan = f.onEvent(SearchEvent.SlotTapped(SavedKind.HOME)) as SearchOutput.PlanTrip
        assertEquals(PlaceSource.SAVED, plan.place.source)
        assertEquals(h.id, plan.place.savedId)
        assertEquals("Home", plan.place.name)
        // uncovered (no routing; and a point outside the tiles): ShowPlace
        installed.value = noRouting()
        f.open(SearchMode.GO_TO, gps)
        assertTrue(f.onEvent(SearchEvent.SlotTapped(SavedKind.HOME)) is SearchOutput.ShowPlace)
        installed.value = withMaps()
        val far = (saved.save(place("Point State Park", PITTSBURGH, id = 77), SavedKind.WORK) as SaveResult.Done).place!!
        f.open(SearchMode.GO_TO, gps)
        assertEquals(SearchOutput.ShowPlace(far.toPlace()), f.onEvent(SearchEvent.SlotTapped(SavedKind.WORK)))
        // ADD_STOP: Picked; uncovered: ShowPlace
        f.open(SearchMode.ADD_STOP, gps)
        assertEquals(SearchOutput.Picked(h.toPlace(), SearchMode.ADD_STOP), f.onEvent(SearchEvent.SlotTapped(SavedKind.HOME)))
        f.open(SearchMode.ADD_STOP, gps)
        assertTrue(f.onEvent(SearchEvent.SlotTapped(SavedKind.WORK)) is SearchOutput.ShowPlace)
        // SET_START: the destination's row reads "Already your destination" and does nothing
        f.open(SearchMode.SET_START, gps, destination = h.toPlace())
        assertEquals("Already your destination", ui.slots.first { it.kind == SavedKind.HOME }.note)
        assertNull(f.onEvent(SearchEvent.SlotTapped(SavedKind.HOME)))
        f.open(SearchMode.SET_START, gps, destination = museumPlace)   // found through SavedPlaces.find
        assertNull(f.onEvent(SearchEvent.SlotTapped(SavedKind.HOME)))
        f.open(SearchMode.SET_START, gps, destination = mallPlace)
        assertEquals(SearchOutput.Picked(h.toPlace(), SearchMode.SET_START), f.onEvent(SearchEvent.SlotTapped(SavedKind.HOME)))
        // a saved result row behaves the same
        f.open(SearchMode.GO_TO, gps)
        type("home")
        assertEquals("saved:${h.id}", ui.savedResults[0].key)
        assertEquals(SearchOutput.PlanTrip(h.toPlace()), f.onEvent(SearchEvent.RowTapped("saved:${h.id}")))
        f.open(SearchMode.SET_START, gps, destination = h.toPlace())
        type("home")
        assertNull(f.onEvent(SearchEvent.RowTapped("saved:${h.id}")))
        assertEquals(emptyList<Place>(), recents.places.value)       // no recent added by any of them
    }

    @Test
    fun saveModeFromAnEmptySlot() {
        fake.answers = { listOf(row(11, "Erie Maritime Museum", MUSEUM, "100 East Front Street, Erie")) }
        f.open(SearchMode.GO_TO, gps)
        assertNull(f.onEvent(SearchEvent.SlotTapped(SavedKind.HOME)))
        assertEquals(SearchMode.SAVE, ui.mode)
        assertEquals("Set home", ui.title)
        assertTrue(ui.chooseOnMap)
        assertTrue(ui.useMyPosition)
        assertEquals(emptyList<SavedRowUi>(), ui.slots)
        assertEquals(emptyList<ShortcutUi>(), ui.shortcuts)
        assertNull(ui.savedPlacesText)
        type("erie maritime museum")
        settle()
        assertEquals(emptyList<ResultRowUi>(), ui.savedResults)
        assertNull(f.onEvent(SearchEvent.RowTapped(ui.results[0].key)))
        assertEquals("Erie Maritime Museum", saved.home()!!.placeName)
        assertEquals(SearchMode.GO_TO, ui.mode)
        assertEquals(SearchPhase.START, ui.phase)
        assertEquals("Home", ui.slots[0].title)
        assertEquals(emptyList<Place>(), recents.places.value)   // SP31
        // Set work with "Use my position": the fix, its coordinates as place name
        f.onEvent(SearchEvent.SlotTapped(SavedKind.WORK))
        assertNull(f.onEvent(SearchEvent.UseMyPosition))
        assertEquals(ZOO, saved.work()!!.point)
        assertEquals("42.09710, -80.07578", saved.work()!!.placeName)
        // "Choose on the map": PickOnMap, back at GO_TO START
        saved.delete(saved.work()!!.id)
        f.onEvent(SearchEvent.SlotTapped(SavedKind.WORK))
        assertEquals(SearchOutput.PickOnMap(SaveTarget.Work), f.onEvent(SearchEvent.ChooseOnMap))
        assertEquals(SearchMode.GO_TO, ui.mode)
        // Close in SAVE mode entered from a slot: back to GO_TO START, search stays open
        f.onEvent(SearchEvent.SlotTapped(SavedKind.WORK))
        assertNull(f.onEvent(SearchEvent.Close))
        assertEquals(SearchMode.GO_TO, ui.mode)
    }

    @Test
    fun useMyPositionNeedsAFreshPreciseFix() {
        f.openToSave(SaveTarget.Home, gps)
        assertTrue(ui.useMyPosition)
        f.openToSave(SaveTarget.Home, gps.copy(accuracyM = 100.0))
        assertFalse(ui.useMyPosition)
        f.openToSave(SaveTarget.Home, gps.copy(accuracyM = null))
        assertFalse(ui.useMyPosition)
        f.openToSave(SaveTarget.Home, mapCentre.copy(accuracyM = 10.0))
        assertFalse(ui.useMyPosition)
        assertNull(f.onEvent(SearchEvent.UseMyPosition))
        assertNull(saved.home())
    }

    @Test
    fun openToSaveTargets() {
        fake.answers = { listOf(row(12, "Millcreek Mall", MALL, "5800 Peach Street, Erie", "mall")) }
        val q = (saved.save(museumPlace, SavedKind.OTHER, "Mom's") as SaveResult.Done).place!!
        for ((t, title) in listOf(SaveTarget.Home to "Set home", SaveTarget.Work to "Set work", SaveTarget.NewPlace to "Save place",
            SaveTarget.Move(q.id) to "New location for Mom's")) {
            f.openToSave(t, gps)
            assertEquals(title, ui.title)
        }
        // Move: a pick moves it and returns Closed
        f.openToSave(SaveTarget.Move(q.id), gps)
        type("millcreek")
        settle()
        assertEquals(SearchOutput.Closed, f.onEvent(SearchEvent.RowTapped(ui.results[0].key)))
        assertEquals(MALL, saved.byId(q.id)!!.point)
        assertEquals("Mom's", saved.byId(q.id)!!.name)
        // NewPlace: a pick returns SaveNew
        f.openToSave(SaveTarget.NewPlace, gps)
        type("millcreek")
        settle()
        val out = f.onEvent(SearchEvent.RowTapped(ui.results[0].key)) as SearchOutput.SaveNew
        assertEquals("Millcreek Mall", out.place.name)
        // Choose on the map after openToSave: PickOnMap and the search is done
        f.openToSave(SaveTarget.NewPlace, gps)
        assertEquals(SearchOutput.PickOnMap(SaveTarget.NewPlace), f.onEvent(SearchEvent.ChooseOnMap))
        // Close after openToSave: Closed
        f.openToSave(SaveTarget.Home, gps)
        assertEquals(SearchOutput.Closed, f.onEvent(SearchEvent.Close))
        assertEquals(emptyList<Place>(), recents.places.value)
    }

    /** A refused pick in SAVE mode stays in SAVE mode and says why (ui.message, shown by the screen as a problem line). */
    @Test
    fun aRefusedChangeOfLocationSaysWhy() {
        fake.answers = { listOf(row(12, "Millcreek Mall", MALL, "5800 Peach Street, Erie", "mall")) }
        saved.save(mallPlace, SavedKind.OTHER, "Gym")
        val moms = (saved.save(museumPlace, SavedKind.OTHER, "Mom's") as SaveResult.Done).place!!
        f.openToSave(SaveTarget.Move(moms.id), gps)
        type("millcreek")
        settle()
        assertNull(f.onEvent(SearchEvent.RowTapped(ui.results[0].key)))
        assertEquals("This place is already saved as Gym.", ui.message)
        assertEquals(SearchMode.SAVE, ui.mode)
        assertEquals(SearchPhase.RESULTS, ui.phase)
        assertEquals(MUSEUM, saved.byId(moms.id)!!.point)              // not moved
        type("millcreek mall")                                          // typing on clears the line
        assertNull(ui.message)
    }

    // ---------------------------------------------------------------- SP20-SP22: saved names and engine rows
    @Test
    fun savedMatchesAboveTheEngineRows() {
        val h = home()
        fake.answers = { t ->
            when (t) {
                "home" -> listOf(row(11, "Erie Maritime Museum", MUSEUM, "100 East Front Street, Erie"), row(30, "Home Depot", MALL, cat = "hardware"))
                "erie maritime" -> listOf(row(11, "Erie Maritime Museum", MUSEUM, "100 East Front Street, Erie"))
                "100 east front" -> listOf(row(-5, "100 East Front Street", MUSEUM, "Erie, PA", null, kind = SearchResult.Kind.STREET,
                    address = AddressInfo("100", "East Front Street", AddressInfo.Accuracy.POINT)))
                else -> emptyList()
            }
        }
        f.open(SearchMode.GO_TO, gps)
        type("home")
        assertEquals(SearchPhase.SEARCHING, ui.phase)
        assertEquals(listOf("Home"), ui.savedResults.map { it.title })      // at once, no engine call yet
        assertEquals("saved_home", ui.savedResults[0].iconName)
        settle()
        assertEquals(SearchPhase.RESULTS, ui.phase)
        assertEquals(listOf("Home Depot"), ui.results.map { it.title })      // the engine row that is Home is dropped
        type("erie maritime")
        settle()
        assertEquals(emptyList<ResultRowUi>(), ui.savedResults)
        assertEquals("Saved", ui.results[0].note)
        // in SAVE mode no saved place is matched and the engine row keeps its note
        f.openToSave(SaveTarget.Work, gps)
        type("home")
        settle()
        assertEquals(emptyList<ResultRowUi>(), ui.savedResults)
        assertEquals(listOf("Erie Maritime Museum", "Home Depot"), ui.results.map { it.title })
        assertEquals("Saved", ui.results[0].note)
        // an address row: "Exact address · Saved" for a Home saved from that address
        saved.save(Place("100 East Front Street", MUSEUM, PlaceSource.SEARCH, "Erie, PA", null, -5, "us-pa"), SavedKind.HOME)
        f.open(SearchMode.GO_TO, gps)
        type("100 east front")
        settle()
        assertEquals("Exact address · Saved", ui.results[0].note)
        // engine none, a saved match: RESULTS
        type("ho")
        settle()
        assertEquals(SearchPhase.RESULTS, ui.phase)
        assertEquals(emptyList<ResultRowUi>(), ui.results)
        assertTrue(h.id != saved.home()!!.id)
    }

    @Test
    fun recentsHideSavedPlaces() {
        recents.add(Place("Erie Maritime Museum", GeoPoint(MUSEUM.lat + 0.0002, MUSEUM.lon), PlaceSource.SEARCH, "Erie"))
        recents.add(mallPlace)
        f.open(SearchMode.GO_TO, gps)
        assertEquals(listOf("Millcreek Mall", "Erie Maritime Museum"), ui.recents.map { it.title })
        val h = home()
        settle()
        assertEquals(listOf("Millcreek Mall"), ui.recents.map { it.title })   // within 50 m with the Home's place name
        // Home only changes location: the recent shows again
        saved.move(h.id, Place("Point on the map", AIRPORT, PlaceSource.MAP_PIN))
        settle()
        assertEquals(listOf("Millcreek Mall", "Erie Maritime Museum"), ui.recents.map { it.title })
    }

    // ---------------------------------------------------------------- the place sheet (S7, SP4, SP19)
    @Test
    fun placeSheetActions() {
        assertEquals(listOf(PlaceAction.DIRECTIONS, PlaceAction.SAVE), f.placeSheet(mallPlace, gps, tripActive = false).actions)
        assertEquals(listOf(PlaceAction.ADD_STOP, PlaceAction.DIRECTIONS, PlaceAction.SAVE), f.placeSheet(mallPlace, gps, tripActive = true).actions)
        val pgh = place("Point State Park", PITTSBURGH)
        val outside = f.placeSheet(pgh, gps, tripActive = true)
        assertEquals(listOf(PlaceAction.OPEN_STATES, PlaceAction.SAVE), outside.actions)
        assertEquals("Outside the installed maps", outside.note)
        val h = home()
        val asResult = f.placeSheet(museumPlace, gps, tripActive = false)
        assertEquals(listOf(PlaceAction.DIRECTIONS, PlaceAction.EDIT), asResult.actions)
        assertEquals("Saved as Home", asResult.savedText)
        assertEquals(h.id, asResult.savedId)
        val fromSaved = f.placeSheet(h.toPlace(), gps, tripActive = false)
        assertEquals("Home", fromSaved.title)
        assertNull(fromSaved.savedText)
        assertEquals("saved_home", fromSaved.iconName)
        assertEquals("Erie Maritime Museum, 100 East Front Street, Erie", fromSaved.subtitle)
        assertTrue(fromSaved.distanceText != null)
        assertNull(f.placeSheet(h.toPlace(), mapCentre, tripActive = false).distanceText)
        // a dropped pin 20 m from Home: SAVE, no "Saved as"
        val pin = Place("Dropped pin", GeoPoint(MUSEUM.lat + 20.0 / 111195.0, MUSEUM.lon), PlaceSource.MAP_PIN)
        val p = f.placeSheet(pin, gps, tripActive = false)
        assertEquals(listOf(PlaceAction.DIRECTIONS, PlaceAction.SAVE), p.actions)
        assertNull(p.savedText)
        // per phase (SP4): GUIDING/REROUTING and ARRIVED, unsaved and saved alike
        for (pl in listOf(mallPlace, h.toPlace())) {
            assertEquals(listOf(PlaceAction.ADD_STOP, PlaceAction.DIRECTIONS), f.placeSheet(pl, gps, tripActive = true, driving = true).actions)
            assertEquals(listOf(PlaceAction.DIRECTIONS), f.placeSheet(pl, gps, tripActive = false, driving = true).actions)
        }
        assertEquals(listOf(PlaceAction.OPEN_STATES), f.placeSheet(pgh, gps, tripActive = true, driving = true).actions)
        assertEquals("museum", f.placeSheet(museumPlace.copy(savedId = null, name = "x"), gps, false).iconName)
    }

    @Test
    fun readOnlyStoreSheetHasNoSaveOrEdit() {
        File(dir, "n").mkdirs()
        File(dir, "n/saved-places.json").writeText("""{"format":"beamline-saved-places/9","places":[]}""")
        val newer = SavedPlaces(File(dir, "n/saved-places.json"), SavedRules.bundled(), Executor { it.run() })
        val g = SearchFeature(installed, recents, newer, MutableStateFlow(units), scope, dispatcher, fake)
        assertEquals(listOf(PlaceAction.DIRECTIONS), g.placeSheet(mallPlace, gps, false).actions)
        assertEquals(listOf(PlaceAction.OPEN_STATES), g.placeSheet(place("Point State Park", PITTSBURGH), gps, false).actions)
    }
}
