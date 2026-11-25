package io.github.spysyweeb.beamline.feature.search

import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.search.PackResult
import io.github.spysyweeb.beamline.search.PoiDetails
import io.github.spysyweeb.beamline.search.RecentPlaces
import io.github.spysyweeb.beamline.search.SearchResult
import io.github.spysyweeb.beamline.search.awaitStoreWrites
import io.github.spysyweeb.beamline.search.hours.OpenState
import io.github.spysyweeb.beamline.search.saved.SavedPlaces
import io.github.spysyweeb.beamline.search.saved.SavedRules
import java.io.File
import java.time.LocalDateTime
import java.util.concurrent.Executor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Rule S11 (results on the map after Enter) and the controller part of S10 (hours, Call and Website
 * on rows and the sheet), batch B. A virtual clock, a fake of the packs, made-up chain stores at
 * points around downtown Erie.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SearchResultsTest {
    @TempDir
    lateinit var dir: File

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val installed = MutableStateFlow(SearchFeatureTest.withMaps())
    private val fake = FakeSearcher()
    private lateinit var recents: RecentPlaces
    private lateinit var f: SearchFeature
    private lateinit var saved: SavedPlaces
    private val outputs = ArrayList<SearchOutput>()

    /** Wednesday 2026-11-18, 16:30 on the phone's clock. */
    private var now = LocalDateTime.of(2026, 11, 18, 16, 30)

    /** Store [i] lies i * 0.01 degrees north of the zoo; the engine answers them farthest first (by score). */
    /** A Sheetz found by its brand ([tier] "brand": a class answer, shown nearest first); [tier] "exact": found by its name. */
    private fun store(i: Int, km: Double, tier: String? = "brand") = PackResult(
        "us-pa",
        SearchResult(100L + i, SearchResult.Kind.POI, "Sheetz", "Erie", "fuel", "Gas station",
            SearchFeatureTest.ZOO.lat + i * 0.01, SearchFeatureTest.ZOO.lon, km, null, null, 10.0 - i, "t", tier),
    )

    /** S11b (the owner, 2026-11-24): "Search this area" asks again around the map's centre and keeps the places in the window. */
    @Test
    fun searchThisAreaKeepsThePlacesInTheWindow() {
        fake.answers = { (1..25).map { i -> store(i, km = (26 - i).toDouble()) } }
        f.open(SearchMode.GO_TO, SearchFeatureTest.gps)
        f.onEvent(SearchEvent.QueryChanged("sheetz"))
        scope.testScheduler.advanceUntilIdle()
        f.onEvent(SearchEvent.Submit)
        scope.testScheduler.advanceUntilIdle()
        f.onResultsEvent(ResultsEvent.Select(0))
        val zoo = SearchFeatureTest.ZOO
        // the window holds stores 3 to 7 (0.03 to 0.07 degrees north of the zoo)
        val window = Bounds(west = zoo.lon - 0.01, south = zoo.lat + 0.025, east = zoo.lon + 0.01, north = zoo.lat + 0.075)
        val centre = GeoPoint(zoo.lat + 0.05, zoo.lon)
        var found: Int? = -1
        scope.launch { found = f.searchArea(centre, window) }
        scope.testScheduler.advanceUntilIdle()
        assertEquals(5, found)
        assertEquals(centre, fake.nears.last(), "asked around the map's centre")
        val after = f.results.value!!
        assertEquals("sheetz", after.query)
        assertEquals((107 downTo 103).map { "r:us-pa:$it" }, after.rows.map { it.key }, "only the window's, nearest first")
        assertNull(after.selected, "a new list: nothing selected")
        // a window with none of them: the results stay as they were
        val far = Bounds(west = zoo.lon + 1.0, south = zoo.lat + 1.0, east = zoo.lon + 1.1, north = zoo.lat + 1.1)
        scope.launch { found = f.searchArea(GeoPoint(zoo.lat + 1.05, zoo.lon + 1.05), far) }
        scope.testScheduler.advanceUntilIdle()
        assertEquals(0, found)
        assertEquals(after, f.results.value)
        // the results closed: nothing to search again
        f.onResultsEvent(ResultsEvent.Close)
        scope.launch { found = f.searchArea(centre, window) }
        scope.testScheduler.advanceUntilIdle()
        assertNull(found)
        assertNull(f.results.value)
    }

    /** The owner, 2026-11-21: the list while typing and the map after Enter show the same places in the same order. */
    @Test
    fun theListAndTheMapShowTheSamePlaces() {
        // a brand: nearest first in the list already, and Enter asks nothing again
        fake.answers = { (1..25).map { i -> store(i, km = (26 - i).toDouble()) } }
        f.open(SearchMode.GO_TO, SearchFeatureTest.gps)
        f.onEvent(SearchEvent.QueryChanged("sheetz"))
        scope.testScheduler.advanceUntilIdle()
        val listed = f.ui.value.results.map { it.key }
        assertEquals(20, listed.size)
        assertEquals("r:us-pa:${100 + 25}", listed.first())
        val asked = fake.limits.size
        f.onEvent(SearchEvent.Submit)
        scope.testScheduler.advanceUntilIdle()
        assertEquals(asked, fake.limits.size, "Enter shows the list's own rows")
        assertEquals(listed, f.results.value!!.rows.map { it.key })
        // a name (not a category or a brand): the engine's best ten in its order, far ones too, in the list and on the map
        f.onResultsEvent(ResultsEvent.Close)
        fake.answers = { (1..25).map { i -> store(i, km = (26 - i).toDouble(), tier = "exact") } }
        f.open(SearchMode.GO_TO, SearchFeatureTest.gps)
        f.onEvent(SearchEvent.QueryChanged("sheetz on peach"))
        scope.testScheduler.advanceUntilIdle()
        val named = f.ui.value.results.map { it.key }
        assertEquals((1..10).map { "r:us-pa:${100 + it}" }, named)
        f.onEvent(SearchEvent.Submit)
        scope.testScheduler.advanceUntilIdle()
        assertEquals(named, f.results.value!!.rows.map { it.key })
    }

    @BeforeEach
    fun setUp() {
        recents = RecentPlaces(File(dir, "recents.json"))
        saved = SavedPlaces(File(dir, "saved-places.json"), SavedRules.bundled(), Executor { it.run() })
        f = SearchFeature(installed, recents, saved, MutableStateFlow(SearchFeatureTest.units), scope, dispatcher, fake)
        f.hoursClock = { now }
        scope.launch { f.later.collect { outputs.add(it) } }
        scope.runCurrent()
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        awaitStoreWrites()
    }

    private fun settle() {
        scope.advanceTimeBy(1_000)
        scope.runCurrent()
    }

    private fun enter(text: String, ctx: SearchContext = SearchFeatureTest.gps, mode: SearchMode = SearchMode.GO_TO, driving: Boolean = false) {
        f.open(mode, ctx, driving = driving)
        f.onEvent(SearchEvent.QueryChanged(text))
        f.onEvent(SearchEvent.Submit)
        settle()
    }

    @Test
    fun enterShowsTheNearestUpToTwentyOnTheMap() {
        // 25 matches, the engine's order not by distance: the 20 nearest, nearest first
        fake.answers = { (1..25).map { i -> store(i, km = (26 - i).toDouble()) } }
        enter("sheetz")
        assertEquals(100, fake.limits.last())                      // asked for on_map.candidates (batch C2), then cut
        val r = f.results.value!!
        assertEquals(20, r.rows.size)
        assertEquals("sheetz", r.query)
        assertEquals(r.rows.size, r.places.size)
        assertEquals("r:us-pa:${100 + 25}", r.rows.first().key)    // the 20 truly nearest of the 25, nearest (1 km) first
        assertEquals("r:us-pa:${100 + 6}", r.rows.last().key)      // 20 km; the 5 farther ones left out
        assertEquals(r.places.map { it.searchId }, r.rows.map { it.key.substringAfterLast(':').toLong() })
        assertNull(r.selected)
        assertEquals(SearchUi(), f.ui.value)                       // the search screen is done
        assertEquals(listOf<SearchOutput>(SearchOutput.ShowResults(r.places)), outputs)
    }

    @Test
    fun aSingleMatchIsPicked() {
        fake.answers = { listOf(store(1, 1.0)) }
        enter("sheetz")
        assertNull(f.results.value)
        val o = outputs.single() as SearchOutput.Picked
        assertEquals(SearchMode.GO_TO, o.mode)
        assertEquals(101L, o.place.searchId)
        assertEquals(101L, recents.places.value.first().searchId)
    }

    @Test
    fun noMatchStaysOnTheScreen() {
        fake.answers = { emptyList() }
        enter("nothing here")
        assertNull(f.results.value)
        assertTrue(outputs.isEmpty())
        assertEquals(SearchPhase.NO_RESULTS, f.ui.value.phase)
    }

    @Test
    fun notOfferedOutsideGoToOrWhileDriving() {
        fake.answers = { (1..5).map { store(it, it.toDouble()) } }
        enter("sheetz", mode = SearchMode.ADD_STOP, driving = true)
        enter("sheetz", mode = SearchMode.ADD_STOP)
        enter("sheetz", mode = SearchMode.GO_TO, driving = true)
        assertNull(f.results.value)
        assertTrue(outputs.isEmpty())
        assertEquals(3, fake.limits.size)                         // only the typing searches ran: Enter asked nothing and showed nothing
    }

    @Test
    fun typingWithoutEnterKeepsTheList() {
        fake.answers = { (1..5).map { store(it, it.toDouble()) } }
        f.open(SearchMode.GO_TO, SearchFeatureTest.gps)
        f.onEvent(SearchEvent.QueryChanged("sheetz"))
        settle()
        assertEquals(SearchPhase.RESULTS, f.ui.value.phase)
        assertEquals(5, f.ui.value.results.size)
        assertNull(f.results.value)
    }

    @Test
    fun selectDeselectAndClose() {
        fake.answers = { (1..5).map { store(it, it.toDouble()) } }
        enter("sheetz")
        val o = f.onResultsEvent(ResultsEvent.Select(2)) as SearchOutput.ShowPlace
        assertEquals(103L, o.place.searchId)
        assertEquals(2, f.results.value!!.selected)
        assertEquals(103L, recents.places.value.first().searchId)
        assertNull(f.onResultsEvent(ResultsEvent.Deselect))       // the sheet closed: back to the list
        assertNull(f.results.value!!.selected)
        assertEquals(5, f.results.value!!.rows.size)
        assertNull(f.onResultsEvent(ResultsEvent.Close))
        assertNull(f.results.value)                                // pins and list gone
        assertNull(f.onResultsEvent(ResultsEvent.Select(0)))       // nothing to select any more
    }

    @Test
    fun aNewSearchEndsTheResults() {
        fake.answers = { (1..5).map { store(it, it.toDouble()) } }
        enter("sheetz")
        f.open(SearchMode.GO_TO, SearchFeatureTest.gps)
        assertNull(f.results.value)
    }

    @Test
    fun hoursOnRowsAndSheetCallAndWebsite() {
        fake.answers = { (1..3).map { store(it, it.toDouble()) } }
        fake.details = mapOf(
            101L to PoiDetails("Mo-Fr 08:00-17:00", "+1 814-555-0100; +1 814-555-0101", "example.com/store"),
            102L to PoiDetails("24/7", null, null),
            103L to PoiDetails("by appointment", null, "ftp://example.com"),
        )
        enter("sheetz")
        val rows = f.results.value!!.rows
        assertEquals(HoursUi(OpenState.CLOSING_SOON, "Closing soon", "closes at 5 PM"), rows[0].hours)
        assertEquals(HoursUi(OpenState.OPEN, "Open now", "open 24 hours"), rows[1].hours)
        assertNull(rows[2].hours)                                  // unreadable: nothing, never a guess

        val place = f.results.value!!.places[0]
        val sheet = f.placeSheet(place, SearchFeatureTest.gps, tripActive = false)
        assertEquals(OpenState.CLOSING_SOON, sheet.hours!!.state)
        assertEquals(7, sheet.hours!!.week.size)
        assertEquals(HoursDayUi("Wed", "8 AM–5 PM", today = true), sheet.hours!!.week[2])
        assertEquals(HoursDayUi("Sun", "Closed", today = false), sheet.hours!!.week[6])
        assertEquals("+1 814-555-0100", sheet.phone)
        assertEquals("https://example.com/store", sheet.website)
        assertEquals(listOf(PlaceAction.DIRECTIONS, PlaceAction.SAVE, PlaceAction.CALL, PlaceAction.WEBSITE), sheet.actions)

        val driving = f.placeSheet(place, SearchFeatureTest.gps, tripActive = true, driving = true)
        assertEquals(listOf(PlaceAction.ADD_STOP, PlaceAction.DIRECTIONS), driving.actions)
        assertNull(driving.phone)
        assertNull(driving.website)

        val third = f.placeSheet(f.results.value!!.places[2], SearchFeatureTest.gps, tripActive = false)
        assertNull(third.hours)
        assertNull(third.website)                                  // not http(s): not offered

        now = LocalDateTime.of(2026, 11, 18, 17, 0)                // at closing
        f.refreshHours()
        assertEquals(HoursUi(OpenState.CLOSED, "Closed now", "opens at 8 AM"), f.results.value!!.rows[0].hours)
        now = LocalDateTime.of(2026, 11, 21, 12, 0)                // Saturday noon
        f.refreshHours()
        assertEquals("opens Mon at 8 AM", f.results.value!!.rows[0].hours!!.next)
    }

    @Test
    fun aRecentGetsItsDetailsLater() {
        fake.details = mapOf(101L to PoiDetails("Mo-Su 06:00-22:00", null, null))
        val recent = io.github.spysyweeb.beamline.api.model.Place("Sheetz", GeoPoint(42.1, -80.07), io.github.spysyweeb.beamline.api.model.PlaceSource.SEARCH,
            "Erie", "fuel", 101L, "us-pa")
        val before = f.detailsLoaded.value
        assertNull(f.placeSheet(recent, SearchFeatureTest.gps, tripActive = false).hours)   // not read yet
        scope.runCurrent()
        assertEquals(before + 1, f.detailsLoaded.value)
        assertEquals(HoursUi(OpenState.OPEN, "Open now", "until 10 PM").state, f.placeSheet(recent, SearchFeatureTest.gps, tripActive = false).hours!!.state)
    }

    // ------------------------------------------------------------ batch C2: the owner's answers after batch B

    @Test
    fun theTrulyNearestTwentyWithin25MilesOrTheNearestFewAnyway() {
        // The engine's top 20 by rank are the far ones; 10 near ones come after them: those are shown.
        fake.answers = { (1..20).map { store(it, km = 100.0 + it) } + (21..30).map { store(it, km = (it - 20).toDouble()) } }
        enter("sheetz")
        val r = f.results.value!!
        assertEquals((21..30).map { "r:us-pa:${100 + it}" }, r.rows.map { it.key })    // within 40.23 km only, nearest first
        // None within 25 miles: the nearest 5 anyway, nearest first.
        f.open(SearchMode.GO_TO, SearchFeatureTest.gps)
        fake.answers = { (1..12).map { store(it, km = 200.0 - it) } }
        enter("sheetz")
        assertEquals((12 downTo 8).map { "r:us-pa:${100 + it}" }, f.results.value!!.rows.map { it.key })
        // The edge: 40.23 km is within, 40.3 km is not.
        val rules = io.github.spysyweeb.beamline.search.SearchAppRules.bundled()
        assertEquals(listOf(40.23), nearestOnMap(listOf(40.3, 40.23), { it }, rules))
        assertEquals(listOf(1.0, 2.0), nearestOnMap(listOf(null, 2.0, 1.0), { it }, rules))   // no distance: not within
        assertEquals(listOf(null, null), nearestOnMap(listOf<Double?>(null, null), { it }, rules))   // no ranking position: the engine's order
    }

    @Test
    fun removingTheStateOfTheResultsEndsThem() {
        fake.answers = { (1..5).map { store(it, it.toDouble()) } }
        enter("sheetz")
        assertTrue(f.results.value != null)
        assertTrue(!f.closeResultsGoneFrom(installed.value))      // the state is still there: kept
        assertTrue(f.results.value != null)
        val without = installed.value.copy(generation = installed.value.generation + 1, packs = emptyList())
        installed.value = without
        assertTrue(f.closeResultsGoneFrom(without))                // us-pa removed: the results and their pins end
        assertNull(f.results.value)
        assertTrue(!f.closeResultsGoneFrom(without))               // nothing left to end
    }

    @Test
    fun aTappedPoiASavedPlaceAndARecentGetDetailsByNameAndPosition() {
        val sheetz = PoiDetails("Mo-Su 06:00-22:00", "+1 814-555-0100", "https://example.com")
        fake.detailsByName = mapOf("Sheetz" to sheetz)
        val tapped = io.github.spysyweeb.beamline.api.model.Place("Sheetz", GeoPoint(42.1, -80.07), io.github.spysyweeb.beamline.api.model.PlaceSource.MAP_POI)
        assertNull(f.placeSheet(tapped, SearchFeatureTest.gps, tripActive = false).hours)   // asked, not read yet
        scope.runCurrent()
        assertEquals(listOf("Sheetz" to GeoPoint(42.1, -80.07)), fake.askedAt)
        val sheet = f.placeSheet(tapped, SearchFeatureTest.gps, tripActive = false)
        assertEquals(OpenState.OPEN, sheet.hours!!.state)
        assertEquals("+1 814-555-0100", sheet.phone)
        assertTrue(PlaceAction.CALL in sheet.actions && PlaceAction.WEBSITE in sheet.actions)
        // A place with no sure match (the fake answers null): nothing, and it is asked once only.
        val other = io.github.spysyweeb.beamline.api.model.Place("My Garage", GeoPoint(42.11, -80.08), io.github.spysyweeb.beamline.api.model.PlaceSource.SAVED)
        f.placeSheet(other, SearchFeatureTest.gps, tripActive = false)
        scope.runCurrent()
        val again = f.placeSheet(other, SearchFeatureTest.gps, tripActive = false)
        assertNull(again.hours)
        assertNull(again.phone)
        assertEquals(2, fake.askedAt.size)
        // A place with a search id is read by its id, never by name.
        fake.details = mapOf(101L to PoiDetails("24/7", null, null))
        val recent = io.github.spysyweeb.beamline.api.model.Place("Sheetz", GeoPoint(42.1, -80.07), io.github.spysyweeb.beamline.api.model.PlaceSource.SEARCH,
            "Erie", "fuel", 101L, "us-pa")
        f.placeSheet(recent, SearchFeatureTest.gps, tripActive = false)
        scope.runCurrent()
        assertEquals(2, fake.askedAt.size)
        assertEquals("open 24 hours", f.placeSheet(recent, SearchFeatureTest.gps, tripActive = false).hours!!.next)
    }

    @Test
    fun callAndWebsiteAgainOnceArrivedNeverWhileGuiding() {
        fake.answers = { (1..3).map { store(it, it.toDouble()) } }
        fake.details = mapOf(101L to PoiDetails("24/7", "+1 814-555-0100", "https://example.com"))
        enter("sheetz")
        val place = f.results.value!!.places[0]
        val guiding = f.placeSheet(place, SearchFeatureTest.gps, tripActive = true, driving = true, guiding = true)
        assertTrue(PlaceAction.CALL !in guiding.actions && PlaceAction.WEBSITE !in guiding.actions)
        assertNull(guiding.phone)
        // ARRIVED: still "driving" (no SAVE, SP4), but Call and Website are back.
        val arrived = f.placeSheet(place, SearchFeatureTest.gps, tripActive = false, driving = true, guiding = false)
        assertEquals(listOf(PlaceAction.DIRECTIONS, PlaceAction.CALL, PlaceAction.WEBSITE), arrived.actions)
        assertEquals("+1 814-555-0100", arrived.phone)
        assertEquals("https://example.com", arrived.website)
    }

    /** Batch G (item 20): the details height's lines, written out while guiding but not opening anything. */
    @Test
    fun theDetailsHeightWritesEveryNumberAndAddressOut() {
        fake.answers = { (1..3).map { store(it, it.toDouble()) } }
        fake.details = mapOf(101L to PoiDetails("Mo-Fr 08:00-17:00; PH off", "+1 814-555-0100; 814.555.0101", "https://example.com/menu/; www.example.org",
            "1 Main Street, Erie, PA 16501", "Gas station"))
        enter("sheetz")
        val place = f.results.value!!.places[0]
        val sheet = f.placeSheet(place, SearchFeatureTest.gps, tripActive = false)
        assertEquals(listOf("(814) 555-0100", "(814) 555-0101"), sheet.phones.map { it.text })
        assertEquals(listOf("+18145550100", "+18145550101"), sheet.phones.map { it.value })
        assertEquals(listOf("example.com/menu", "www.example.org"), sheet.websites.map { it.text })
        assertEquals(listOf("https://example.com/menu/", "https://www.example.org"), sheet.websites.map { it.value })
        assertEquals("1 Main Street, Erie, PA 16501", sheet.address)
        assertEquals("Gas station", sheet.category)
        assertEquals(7, sheet.hours!!.week.size)
        assertEquals("Holiday hours may differ", sheet.hours!!.holidayNote)
        assertTrue(sheet.canOpenOutside)
        val guiding = f.placeSheet(place, SearchFeatureTest.gps, tripActive = true, driving = true, guiding = true)
        assertEquals(2, guiding.phones.size)
        assertEquals(2, guiding.websites.size)
        assertTrue(!guiding.canOpenOutside)
        assertTrue(f.placeSheet(place, SearchFeatureTest.gps, tripActive = false, driving = true, guiding = false).canOpenOutside)   // ARRIVED
    }

    /** Batch G: a saved place is looked up by its own name, not the name the owner gave it; a dropped pin never. */
    @Test
    fun aSavedPlaceIsLookedUpByItsOwnName() {
        fake.detailsByName = mapOf("Sheetz" to PoiDetails("24/7", "+1 814-555-0100", null))
        val tapped = io.github.spysyweeb.beamline.api.model.Place("Sheetz", GeoPoint(42.1, -80.07), io.github.spysyweeb.beamline.api.model.PlaceSource.MAP_POI)
        saved.save(tapped, io.github.spysyweeb.beamline.api.model.SavedKind.HOME)
        val home = saved.home()!!.toPlace()
        assertEquals("Home", home.name)
        f.placeSheet(home, SearchFeatureTest.gps, tripActive = false)
        scope.runCurrent()
        assertEquals(listOf("Sheetz" to GeoPoint(42.1, -80.07)), fake.askedAt)
        assertEquals(listOf("(814) 555-0100"), f.placeSheet(home, SearchFeatureTest.gps, tripActive = false).phones.map { it.text })
        val pin = io.github.spysyweeb.beamline.api.model.Place("Dropped pin", GeoPoint(42.2, -80.1), io.github.spysyweeb.beamline.api.model.PlaceSource.MAP_PIN)
        f.placeSheet(pin, SearchFeatureTest.gps, tripActive = false)
        scope.runCurrent()
        assertEquals(1, fake.askedAt.size)
    }

    @Test
    fun phoneAndWebsiteTexts() {
        assertEquals(listOf("(814) 555-0123"), HoursTexts.phones("+1 814-555-0123").map { it.text })
        assertEquals(listOf("(814) 555-0123"), HoursTexts.phones("1 (814) 555 0123").map { it.text })
        assertEquals(listOf("(814) 555-0123"), HoursTexts.phones("814.555.0123").map { it.text })
        // Not US-shaped: as the pack has it, dialled with its digits.
        assertEquals(listOf(ContactUi("+44 20 7946 0958", "+44 20 7946 0958", "+44 20 7946 0958")), HoursTexts.phones("+44 20 7946 0958"))
        assertEquals("+1 814-555-0123 ext. 4", HoursTexts.phones("+1 814-555-0123 ext. 4").single().text)
        assertEquals(ContactUi("911", null, "911"), HoursTexts.phones("911").single())
        assertEquals(emptyList<ContactUi>(), HoursTexts.phones(null))
        assertEquals(1, HoursTexts.phones("814-555-0123; +1 814 555 0123").map { it.text }.distinct().size)
        assertEquals(listOf("example.com/menu"), HoursTexts.websites("https://example.com/menu/").map { it.text })
        assertEquals(listOf("example.com"), HoursTexts.websites("HTTP://example.com").map { it.text })
        assertEquals(emptyList<ContactUi>(), HoursTexts.websites("ftp://example.com; not a site"))
        val long = HoursTexts.websites("https://orders.sheetz.com/findasheetz/store/245/menu/breakfast").single()
        assertEquals(40, long.text.length)
        assertTrue(long.text.startsWith("orders.sheetz.com") && long.text.endsWith("breakfast") && "…" in long.text)
        assertEquals("https://orders.sheetz.com/findasheetz/store/245/menu/breakfast", long.value)
    }

    @Test
    fun clockTexts() {
        assertEquals("12 AM", HoursTexts.clock(0))
        assertEquals("12 PM", HoursTexts.clock(720))
        assertEquals("9:30 PM", HoursTexts.clock(21 * 60 + 30))
        assertEquals("2 AM", HoursTexts.clock(26 * 60))
        assertEquals("12 AM", HoursTexts.clock(1440))
        assertNull(HoursTexts.phone("call us"))
        assertNull(HoursTexts.website("javascript:alert(1)"))
        assertEquals("http://example.com", HoursTexts.website("http://example.com;https://example.org"))
    }
}
