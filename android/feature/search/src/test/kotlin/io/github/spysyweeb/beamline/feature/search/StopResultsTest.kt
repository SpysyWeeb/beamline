package io.github.spysyweeb.beamline.feature.search

import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.model.SavedKind
import io.github.spysyweeb.beamline.api.nav.ProblemKind
import io.github.spysyweeb.beamline.api.nav.RouteChoice
import io.github.spysyweeb.beamline.api.nav.StopDetours
import io.github.spysyweeb.beamline.api.nav.StopPreview
import io.github.spysyweeb.beamline.api.nav.TripProblem
import io.github.spysyweeb.beamline.search.MultiSearchResponse
import io.github.spysyweeb.beamline.search.PackResult
import io.github.spysyweeb.beamline.search.PoiDetails
import io.github.spysyweeb.beamline.search.RecentPlaces
import io.github.spysyweeb.beamline.search.SearchAppRules
import io.github.spysyweeb.beamline.search.SearchResult
import io.github.spysyweeb.beamline.search.awaitStoreWrites
import io.github.spysyweeb.beamline.search.saved.SaveResult
import io.github.spysyweeb.beamline.search.saved.SavedPlaces
import io.github.spysyweeb.beamline.search.saved.SavedRules
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.concurrent.Executor
import kotlin.math.cos
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * Item 51 (rule S12, docs/next-round.md item 51): places for a new stop are looked for along the trip's route
 * ahead; Enter, a chip, a type or a single pick in ADD_STOP shows them on the map as stop results, which wait
 * for the detours ("Working out detours...") and then show sorted by the minutes each adds; a row's card
 * previews the route with it and only its "Add stop" adds it. A virtual clock, a fake of the packs, made-up
 * stores along a straight road north of the Erie Zoo (where the user is).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StopResultsTest {
    @TempDir
    lateinit var dir: File

    private val zoo = SearchFeatureTest.ZOO
    private val gps = SearchFeatureTest.gps
    private val kmPerDegLat = 111.195
    private val kmPerDegLon = kmPerDegLat * cos(Math.toRadians(zoo.lat))

    /** The trip's route ahead: a straight road 70 km north from the zoo, a point every km. */
    private val line = (0..70).map { GeoPoint(zoo.lat + it / kmPerDegLat, zoo.lon) }

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val installed = MutableStateFlow(SearchFeatureTest.withMaps())
    private val fake = FakeSearcher()
    private lateinit var recents: RecentPlaces
    private lateinit var saved: SavedPlaces
    private lateinit var f: SearchFeature
    private val outputs = ArrayList<SearchOutput>()

    /** Wednesday 2026-11-18, 16:30 on the phone's clock (the stores' hours). */
    private val now = LocalDateTime.of(2026, 11, 18, 16, 30)

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

    /** A made-up gas station [kmAlong] up the road and [kmOff] east of it, found as a category's place unless [tier] says otherwise. */
    private fun station(id: Long, title: String, kmAlong: Double, kmOff: Double, tier: String? = "category") = PackResult(
        "us-pa",
        SearchResult(id, SearchResult.Kind.POI, title, "Erie", "fuel", "Gas station",
            zoo.lat + kmAlong / kmPerDegLat, zoo.lon + kmOff / kmPerDegLon, kmAlong, null, null, 10.0 - id * 0.01, "t", tier),
    )

    /** Five stations 1 to 5 km up the road, 0.5 km off it, answered farthest first. */
    private val five = (5 downTo 1).map { station(it.toLong(), "Station $it", it.toDouble(), 0.5) }

    private fun settle() {
        scope.advanceTimeBy(1_000)
        scope.runCurrent()
    }

    private fun gasChip(along: List<GeoPoint> = line, after: String? = null) {
        f.open(SearchMode.ADD_STOP, gps, driving = true, along = along, alongAfter = after)
        f.onEvent(SearchEvent.ShortcutTapped("gas"))
        scope.runCurrent()
    }

    private val results get() = f.results.value!!
    private val stop get() = results.stop!!

    private fun route(timeS: Double) = RouteChoice(
        id = 1, typeIds = listOf("fastest"), lengthM = 30_000.0, timeS = timeS, turnCount = 3, hasTolls = false, hasHighways = false,
        via = emptyList(), geometry = listOf(zoo, line[5]), legEnds = listOf(1), bounds = Bounds(zoo.lon, zoo.lat, zoo.lon, line[5].lat), warnings = emptyList(),
    )

    // ---------------------------------------------------------------- 1: candidates along the route

    @Test
    fun alongTheRouteAsksAroundPointsAhead() {
        fake.answers = { five }
        f.open(SearchMode.ADD_STOP, gps, driving = true, along = line)
        f.onEvent(SearchEvent.QueryChanged("gas"))
        settle()
        // one search around a point every 10 km from where the stop joins, 60 km ahead: 0, 10, ... 60 km
        assertEquals(7, fake.nears.size, fake.nears.toString())
        for ((k, p) in fake.nears.withIndex()) {
            val want = line[k * 10]
            assertTrue(Math.abs(p!!.lat - want.lat) < 1e-5 && Math.abs(p.lon - want.lon) < 1e-5, "anchor $k: $p")
        }
        assertEquals("Along your route", f.ui.value.nearText)
        assertEquals((1..5).map { "Station $it" }, f.ui.value.results.map { it.title })   // deduplicated, in order along the road
    }

    @Test
    fun aCategoryAlongTheRouteKeepsTheCorridor() {
        // a category's places: those within 3 km of the road, in order along it; 8 km off is not on the way
        fake.answers = { listOf(station(1, "Far East", 4.0, 8.0), station(2, "North", 9.0, 1.0), station(3, "South", 2.0, 1.0), station(4, "Far West", 1.0, -8.0)) }
        f.open(SearchMode.ADD_STOP, gps, driving = true, along = line)
        f.onEvent(SearchEvent.QueryChanged("gas"))
        settle()
        assertEquals(listOf("South", "North"), f.ui.value.results.map { it.title })
        assertEquals("Along your route", f.ui.value.nearText)
    }

    @Test
    fun noneInTheCorridorShowsTheNearestAndSaysSo() {
        fake.answers = { (1..8).map { station(it.toLong(), "Station $it", it * 3.0, 4.0 + it * 0.5) } }
        f.open(SearchMode.ADD_STOP, gps, driving = true, along = line)
        f.onEvent(SearchEvent.QueryChanged("gas"))
        settle()
        // none within 3 km: the 5 nearest the road anyway (4.5 to 6.5 km off), in order along it
        assertEquals((1..5).map { "Station $it" }, f.ui.value.results.map { it.title })
        assertEquals(SearchPhase.RESULTS, f.ui.value.phase)
        assertEquals("None close to your route. Nearest shown.", f.ui.value.nearText)
        // a chip: the stop results say it too
        f.onEvent(SearchEvent.ShortcutTapped("gas"))
        scope.runCurrent()
        assertTrue(stop.widened)
    }

    @Test
    fun aStopAheadIsNamed() {
        f.open(SearchMode.ADD_STOP, gps, driving = true, along = line, alongAfter = "Wegmans")
        assertEquals("Along your route after Wegmans", f.ui.value.nearText)
        // without a route (no trip line): around the user, as before
        f.open(SearchMode.ADD_STOP, gps, driving = true)
        assertEquals("Near your position", f.ui.value.nearText)
    }

    // ---------------------------------------------------------------- 2: the stop results and their wait

    @Test
    fun aChipForAStopGoesToTheMapAndWaitsForDetours() {
        fake.answers = { five }
        gasChip()
        val out = outputs.single() as SearchOutput.ShowStopResults
        assertEquals(SearchUi(), f.ui.value)                                  // the search screen closed
        assertNull(out.selected)
        assertEquals(results.places, out.places)
        assertEquals(stop.requestId, out.requestId)
        assertTrue(stop.working)
        assertEquals("", stop.typed)                                          // Back after a chip: an empty search
        assertEquals((1..5).map { "Station $it" }, results.rows.map { it.title })   // nearest where the stop joins first
        assertTrue(results.rows.all { it.detourText == null })
        assertEquals("gas", results.query)
    }

    @Test
    fun doneDetoursSortByMinutesThenDistanceUnknownLast() {
        fake.answers = { five }
        gasChip()
        val id = stop.requestId
        f.onDetours(StopDetours(id, listOf(400.0, 30.0, null, 95.0, 70.0), 900.0, done = true))
        assertFalse(stop.working)
        assertNull(stop.note)
        assertEquals(listOf("Station 2", "Station 5", "Station 4", "Station 1", "Station 3"), results.rows.map { it.title })
        assertEquals(listOf("On the way", "+1 min", "+2 min", "+7 min", null), results.rows.map { it.detourText })
        assertEquals(results.rows.map { it.title }, results.places.map { it.name })   // the places follow their rows
        // equal minutes: the nearer first
        fake.answers = { five }
        gasChip()
        f.onDetours(StopDetours(stop.requestId, listOf(130.0, 100.0, 125.0, 20.0, 50.0), 900.0, done = true))
        assertEquals(listOf("Station 4", "Station 5", "Station 1", "Station 2", "Station 3"), results.rows.map { it.title })
        assertEquals(listOf("On the way", "On the way", "+2 min", "+2 min", "+2 min"), results.rows.map { it.detourText })
    }

    @Test
    fun aPartialAnswerWaitsAndTheWaitFallsBackToNearestFirst() {
        fake.answers = { five }
        gasChip()
        val id = stop.requestId
        f.onDetours(StopDetours(id, listOf(400.0, 30.0, null, null, null), null, done = false))
        scope.advanceTimeBy(3_999)
        scope.runCurrent()
        assertTrue(stop.working)
        scope.advanceTimeBy(1)
        scope.runCurrent()
        assertFalse(stop.working)
        assertEquals("Detours took too long. Nearest first.", stop.note)
        assertEquals((1..5).map { "Station $it" }, results.rows.map { it.title })
        assertEquals(listOf("+7 min", "On the way", null, null, null), results.rows.map { it.detourText })
        // a later full answer changes nothing
        val before = results
        f.onDetours(StopDetours(id, listOf(400.0, 30.0, 10.0, 10.0, 10.0), 900.0, done = true))
        assertEquals(before, f.results.value)
    }

    @Test
    fun noDetourAtAllSaysSo() {
        fake.answers = { five }
        gasChip()
        f.onDetours(StopDetours(stop.requestId, List(5) { null }, null, done = true))
        assertFalse(stop.working)
        assertEquals("Detours could not be worked out. Nearest first.", stop.note)
        assertEquals((1..5).map { "Station $it" }, results.rows.map { it.title })
    }

    @Test
    fun aSessionDropMeansNoDetours() {
        fake.answers = { five }
        gasChip()
        f.onDetours(StopDetours(stop.requestId, listOf(30.0, null, null, null, null), null, done = false))
        f.onDetours(null)
        assertFalse(stop.working)
        assertEquals("Detours could not be worked out. Nearest first.", stop.note)
    }

    @Test
    fun newResultsReplaceOldAndGetANewRequest() {
        fake.answers = { five }
        gasChip()
        val first = stop.requestId
        gasChip()
        val second = stop.requestId
        assertNotEquals(first, second)
        f.onDetours(StopDetours(first, List(5) { 10.0 }, 900.0, done = true))   // the old list's answer
        assertTrue(stop.working)
        assertEquals(2, outputs.size)
    }

    @Test
    fun aStaleWaitNeverTouchesANewerList() {
        fake.answers = { five }
        gasChip()
        val first = stop.requestId
        f.onDetours(StopDetours(first, listOf(30.0, 30.0, 30.0, 30.0, 30.0), null, done = false))
        scope.advanceTimeBy(2_000)
        gasChip()                                                              // request 2 at 2 s
        scope.advanceTimeBy(2_000)
        scope.runCurrent()                                                     // 4 s: request 1's wait would end now
        assertTrue(stop.working)
        assertNull(stop.note)
        scope.advanceTimeBy(2_000)
        scope.runCurrent()                                                     // 6 s: request 2's own wait ends
        assertFalse(stop.working)
        assertEquals("Detours took too long. Nearest first.", stop.note)
        assertTrue(results.rows.all { it.detourText == null })                 // nothing from request 1's answer
    }

    @Test
    fun closedNowGoesAfterOpenInTheSameMinute() {
        fake.answers = { listOf(station(1, "Near But Closed", 1.0, 0.5), station(2, "Open Farther", 2.0, 0.5)) }
        fake.details = mapOf(1L to PoiDetails("Mo-Su 06:00-07:00", null, null), 2L to PoiDetails("24/7", null, null))
        gasChip()
        f.onDetours(StopDetours(stop.requestId, listOf(20.0, 40.0), 900.0, done = true))
        assertEquals(listOf("Open Farther", "Near But Closed"), results.rows.map { it.title })
        assertEquals(listOf("On the way", "On the way"), results.rows.map { it.detourText })
    }

    // ---------------------------------------------------------------- 3: the card

    @Test
    fun aSelectedRowPreviewsAndOnlyAddStopAdds() {
        fake.answers = { five }
        gasChip()
        f.onDetours(StopDetours(stop.requestId, listOf(60.0, 120.0, 180.0, 240.0, 300.0), 900.0, done = true))
        val place = results.places[1]
        assertEquals(SearchOutput.PreviewStop(place), f.onResultsEvent(ResultsEvent.Select(1)))
        assertEquals(1, results.selected)
        var card = stop.preview!!
        assertEquals("Station 2", card.title)
        assertTrue(card.busy)
        assertEquals("+2 min", card.addedText)                                 // the row's figure until the route is known
        assertTrue(recents.places.value.isEmpty())                             // looking is not picking
        val zone = ZoneId.of("America/New_York")
        val at = LocalDateTime.of(2026, 11, 26, 16, 3).atZone(zone).toInstant().toEpochMilli()
        f.onStopPreview(StopPreview(place, route(1800.0), 180.0, busy = false), at, zone)
        card = stop.preview!!
        assertFalse(card.busy)
        assertEquals("+3 min", card.addedText)
        assertEquals("Arrive 4:33 PM", card.arriveText)
        assertNull(card.problem)
        assertTrue(card.canAdd)
        assertNull(card.afterText)
        // back to the list: the card goes and the preview is dropped
        assertEquals(SearchOutput.PreviewStop(null), f.onResultsEvent(ResultsEvent.Deselect))
        assertNull(results.selected)
        assertNull(stop.preview)
        // only "Add stop" adds it
        f.onResultsEvent(ResultsEvent.Select(1))
        assertEquals(SearchOutput.AddStop(place), f.onResultsEvent(ResultsEvent.AddStop))
        assertNull(f.results.value)
        assertEquals("Station 2", recents.places.value.first().name)
        assertNull(f.onResultsEvent(ResultsEvent.AddStop))
    }

    @Test
    fun aPreviewProblemStillAdds() {
        fake.answers = { five }
        gasChip()
        val place = results.places[0]
        f.onResultsEvent(ResultsEvent.Select(0))
        val zone = ZoneId.of("America/New_York")
        f.onStopPreview(StopPreview(place, null, null, false, TripProblem(ProblemKind.NO_POSITION, "Waiting for your position.", true)), 0L, zone)
        assertEquals("Could not work out the route with this stop.", stop.preview!!.problem)
        assertTrue(stop.preview!!.canAdd)
        assertEquals(SearchOutput.AddStop(place), f.onResultsEvent(ResultsEvent.AddStop))
        // a stop too many: its own words, and "Add stop" does nothing
        fake.answers = { five }
        gasChip()
        val p2 = results.places[0]
        f.onResultsEvent(ResultsEvent.Select(0))
        f.onStopPreview(StopPreview(p2, null, null, false, TripProblem(ProblemKind.TOO_MANY_STOPS, "A trip can have up to 9 stops.", false)), 0L, zone)
        assertEquals("A trip can have up to 9 stops.", stop.preview!!.problem)
        assertFalse(stop.preview!!.canAdd)
        assertNull(f.onResultsEvent(ResultsEvent.AddStop))
        assertTrue(f.results.value != null)
        // a preview for another place than the selected one is ignored
        f.onStopPreview(StopPreview(results.places[3], route(600.0), 60.0, false), 0L, zone)
        assertEquals("A trip can have up to 9 stops.", stop.preview!!.problem)
    }

    @Test
    fun theCardsFigureIsWrittenBackToItsRow() {
        fake.answers = { five }
        gasChip()
        f.onDetours(StopDetours(stop.requestId, listOf(60.0, 120.0, 180.0, 240.0, 300.0), 900.0, done = true))
        assertEquals("+2 min", results.rows[1].detourText)
        val place = results.places[1]
        f.onResultsEvent(ResultsEvent.Select(1))
        f.onStopPreview(StopPreview(place, route(1800.0), 180.0, busy = false), 0L, ZoneId.of("UTC"))
        assertEquals("+3 min", stop.preview!!.addedText)
        f.onResultsEvent(ResultsEvent.Deselect)
        assertEquals("Station 2", results.rows[1].title)                       // not sorted again
        assertEquals("+3 min", results.rows[1].detourText)
    }

    @Test
    fun aStopAheadIsNamedOnTheCard() {
        fake.answers = { five }
        gasChip(after = "Wegmans")
        assertEquals("Wegmans", stop.after)
        val place = results.places[0]
        f.onResultsEvent(ResultsEvent.Select(0))
        f.onStopPreview(StopPreview(place, route(1800.0), 60.0, busy = false), 0L, ZoneId.of("UTC"))
        assertEquals("Goes after Wegmans", stop.preview!!.afterText)
    }

    // ---------------------------------------------------------------- 4: single picks and saved places

    @Test
    fun aSinglePickInAddStopOpensItsCard() {
        val home = (saved.save(SearchFeatureTest.museumPlace, SavedKind.HOME) as SaveResult.Done).place!!.toPlace()
        f.open(SearchMode.ADD_STOP, gps, driving = true, along = line)
        val out = f.onEvent(SearchEvent.SlotTapped(SavedKind.HOME)) as SearchOutput.ShowStopResults
        assertEquals(listOf(home), out.places)
        assertEquals(0, out.selected)
        assertEquals(SearchUi(), f.ui.value)
        assertEquals(0, results.selected)
        assertTrue(stop.preview!!.busy)
        assertEquals("Home", stop.preview!!.title)
        assertEquals("", stop.typed)
        assertTrue(recents.places.value.isEmpty())
        // a typed row: its words come back on Back
        fake.answers = { five }
        f.open(SearchMode.ADD_STOP, gps, driving = true, along = line)
        f.onEvent(SearchEvent.QueryChanged("station 3"))
        settle()
        val key = f.ui.value.results.first { it.title == "Station 3" }.key
        val typed = f.onEvent(SearchEvent.RowTapped(key)) as SearchOutput.ShowStopResults
        assertEquals(0, typed.selected)
        assertEquals("Station 3", typed.places.single().name)
        assertEquals("station 3", stop.typed)
        // from the saved places list (opened from Add stop)
        val shown = f.showStopPlace(home, gps)
        assertEquals(listOf(home), shown.places)
        assertEquals(0, shown.selected)
        assertNotEquals(typed.requestId, shown.requestId)
        assertEquals("Home", stop.preview!!.title)
    }

    /** The saved places list opened from Add stop: the app closes the search before it asks; the stop still goes after the same stop. */
    @Test
    fun aSavedPlaceFromTheListKeepsWhereTheStopGoes() {
        val home = (saved.save(SearchFeatureTest.museumPlace, SavedKind.HOME) as SaveResult.Done).place!!.toPlace()
        f.open(SearchMode.ADD_STOP, gps, driving = true, along = line, alongAfter = "Wegmans")
        f.close()
        val out = f.showStopPlace(home, gps)
        assertEquals(listOf(home), out.places)
        assertEquals("Wegmans", stop.after)
        assertEquals("Goes after Wegmans", stop.preview!!.afterText)
        // a search opened for something else forgets them
        f.onResultsEvent(ResultsEvent.Close)
        f.open(SearchMode.GO_TO, gps)
        f.close()
        f.showStopPlace(home, gps)
        assertNull(stop.after)
    }

    @Test
    fun theStopResultsAreNeverKeptForBack() {
        fake.answers = { five }
        gasChip()
        val kept = results
        f.onResultsEvent(ResultsEvent.Close)
        assertNull(f.results.value)
        assertFalse(f.restoreResults(kept))
        assertNull(f.results.value)
    }

    // ---------------------------------------------------------------- 5: item 52, a place handed over by another app

    /** A made-up place 2 km up the road, as the app names a handed-over point. */
    private val quokka = Place("Quokka Den", GeoPoint(zoo.lat + 2 / kmPerDegLat, zoo.lon), PlaceSource.SEARCH, "Near 815 Example Street, Erie")

    /** H7: one card, no list behind it, "Go here instead" ends the stop results and hands the place back; recents get it then. */
    @Test
    fun aHandedOverPlaceIsOneCardThatCanGoInstead() {
        val out = f.showStopPlace(quokka, gps, instead = true, after = "Wegmans")
        assertEquals(listOf(quokka), out.places)
        assertEquals(0, out.selected)
        assertEquals(0, results.selected)
        assertTrue(stop.instead)
        assertEquals("Wegmans", stop.after)
        assertEquals("Goes after Wegmans", stop.preview!!.afterText)
        assertTrue(recents.places.value.isEmpty())                             // looking is not picking (K16)
        assertEquals(SearchOutput.GoInstead(quokka), f.onResultsEvent(ResultsEvent.GoInstead))
        assertNull(f.results.value)
        assertEquals("Quokka Den", recents.places.value.first().name)
        assertNull(f.onResultsEvent(ResultsEvent.GoInstead))                   // ended: nothing more
    }

    @Test
    fun addStopStillAddsFromAHandedOverCard() {
        f.showStopPlace(quokka, gps, instead = true, after = null)
        assertEquals(SearchOutput.AddStop(quokka), f.onResultsEvent(ResultsEvent.AddStop))
        assertNull(f.results.value)
        assertEquals("Quokka Den", recents.places.value.first().name)
    }

    /** H7: the stop goes where the app says now, never where an older Add stop search left it. */
    @Test
    fun aHandedOverCardSaysWhereTheStopGoesNow() {
        f.open(SearchMode.ADD_STOP, gps, driving = true, along = line, alongAfter = "Wegmans")
        f.close()
        f.showStopPlace(quokka, gps, instead = true, after = null)
        assertNull(stop.after)
        assertNull(stop.preview!!.afterText)
        f.onResultsEvent(ResultsEvent.Close)
        // an ordinary single place still keeps the Add stop search's (item 51, aSavedPlaceFromTheListKeepsWhereTheStopGoes)
        f.open(SearchMode.ADD_STOP, gps, driving = true, along = line, alongAfter = "Wegmans")
        f.close()
        f.showStopPlace(quokka, gps)
        assertEquals("Wegmans", stop.after)
        assertFalse(stop.instead)
    }

    @Test
    fun goInsteadOnlyOnAHandedOverCard() {
        fake.answers = { five }
        gasChip()
        f.onResultsEvent(ResultsEvent.Select(0))
        val before = f.results.value
        assertNull(f.onResultsEvent(ResultsEvent.GoInstead))
        assertEquals(before, f.results.value)
        f.onResultsEvent(ResultsEvent.Close)
        // S11 results (the made-up rows of a typed search on the map)
        val a = Place("Quokka Mart", GeoPoint(zoo.lat + 0.01, zoo.lon), PlaceSource.SEARCH, "Erie", "shop", 11L, "us-pa")
        val b = Place("Quokka Mart", GeoPoint(zoo.lat + 0.02, zoo.lon), PlaceSource.SEARCH, "Erie", "shop", 12L, "us-pa")
        val s11 = ResultsUi("quokka mart", listOf(ResultRowUi("r:us-pa:11", "shop", "Quokka Mart", "Erie", null, null),
            ResultRowUi("r:us-pa:12", "shop", "Quokka Mart", "Erie", null, null)), listOf(a, b))
        assertTrue(f.restoreResults(s11))
        assertNull(f.onResultsEvent(ResultsEvent.GoInstead))
        assertEquals(s11.places, f.results.value!!.places)
        assertNull(f.results.value!!.stop)
    }

    /** K6: the words of a link are looked up as the search screen looks them up, on the search thread, without touching the screen. */
    @Test
    fun aHandedOverLinksWordsAreLookedUpOnTheSearchThread() {
        fake.answers = { five }
        var got: MultiSearchResponse? = null
        scope.launch { got = f.lookUp("gas", zoo) }
        scope.runCurrent()
        assertEquals((5 downTo 1).map { "Station $it" }, got!!.results.map { it.result.title })   // the engine's order, not the screen's
        assertEquals(zoo, fake.nears.last())
        val rules = SearchAppRules.bundled()
        assertEquals(maxOf(rules.mergeLimit, rules.mapCandidates), fake.limits.last())
        assertEquals(SearchUi(), f.ui.value)
        assertNull(f.results.value)
        // no search data: null; a pack that cannot be read: null, nothing thrown
        fake.hasData = false
        scope.launch { got = f.lookUp("gas", zoo) }
        scope.runCurrent()
        assertNull(got)
        fake.hasData = true
        fake.during = { throw IllegalStateException("unreadable") }
        got = MultiSearchResponse("x", emptyList(), emptyList(), true, null, null, 0.0)
        scope.launch { got = f.lookUp("gas", null) }
        scope.runCurrent()
        assertNull(got)
    }

    // ---------------------------------------------------------------- 6: the review's fixes (0.1.40)

    /**
     * Review F1: P2's "Other matches" belongs to the search list. Stop results are sorted again (nearest where the stop
     * joins, then by minutes), which mixes the name matches in, so no stop result carries the flag (two headings with one
     * key crashed the window). Here Add stop has no route yet (the first plan, no fix): the list is a class answer
     * around the user. Made-up places up the road.
     */
    @Test
    fun stopResultsCarryNoOtherMatches() {
        fake.answers = {
            listOf(
                station(1, "Lakeside Hospital", 0.5, 0.0), station(2, "North Hospital", 2.0, 0.0), station(3, "Hillcrest Hospital", 6.2, 0.0),
                station(4, "Corner Pet Hospital", 5.5, 0.0, tier = "word_prefix"), station(5, "Hospitality Inn", 8.9, 0.0, tier = "prefix"),
            )
        }
        f.open(SearchMode.ADD_STOP, gps, driving = false, along = emptyList())
        f.onEvent(SearchEvent.QueryChanged("hospital"))
        settle()
        // the search list keeps P2 (Add stop without a route): the name matches after the category's own
        assertEquals(listOf(false, false, false, true, true), f.ui.value.results.map { it.otherMatch })
        f.onEvent(SearchEvent.Submit)
        settle()
        assertEquals(listOf("Lakeside Hospital", "North Hospital", "Corner Pet Hospital", "Hillcrest Hospital", "Hospitality Inn"), results.rows.map { it.title })
        assertEquals(List(5) { false }, results.rows.map { it.otherMatch })
        f.onDetours(StopDetours(stop.requestId, listOf(300.0, 60.0, 30.0, 400.0, 200.0), 900.0, done = true))
        assertEquals(listOf("Corner Pet Hospital", "North Hospital", "Hospitality Inn", "Lakeside Hospital", "Hillcrest Hospital"), results.rows.map { it.title })
        assertEquals(List(5) { false }, results.rows.map { it.otherMatch })
        // a single pick from that list: no flag either
        f.onResultsEvent(ResultsEvent.Close)
        f.open(SearchMode.ADD_STOP, gps, driving = false, along = emptyList())
        f.onEvent(SearchEvent.QueryChanged("hospital"))
        settle()
        f.onEvent(SearchEvent.RowTapped(f.ui.value.results.first { it.title == "Hospitality Inn" }.key))
        assertEquals(listOf(false), results.rows.map { it.otherMatch })
    }

    /** Review F2 (R4): a look taken while the detours are worked out keeps its card's figure when they end, in the row and in the order. */
    @Test
    fun aLookDuringTheWaitKeepsItsFigure() {
        val utc = ZoneId.of("UTC")
        fake.answers = { five }
        gasChip()
        f.onResultsEvent(ResultsEvent.Select(1))                               // Station 2, before the detours
        f.onStopPreview(StopPreview(results.places[1], route(1800.0), 30.0, busy = false), 0L, utc)
        assertEquals("On the way", stop.preview!!.addedText)
        f.onDetours(StopDetours(stop.requestId, listOf(60.0, 400.0, 180.0, 240.0, 300.0), 900.0, done = true))
        assertEquals(listOf("Station 2", "Station 1", "Station 3", "Station 4", "Station 5"), results.rows.map { it.title })
        assertEquals(listOf("On the way", "+1 min", "+3 min", "+4 min", "+5 min"), results.rows.map { it.detourText })
        assertEquals(0, results.selected)
        assertEquals("On the way", stop.preview!!.addedText)
        f.onResultsEvent(ResultsEvent.Deselect)
        assertEquals("On the way", results.rows[0].detourText)                 // the list and the card agree after the look
        // looked at and back to the list before the detours, which have no figure for it: the look's figure stays, sorted by it
        fake.answers = { five }
        gasChip()
        f.onResultsEvent(ResultsEvent.Select(1))
        f.onStopPreview(StopPreview(results.places[1], route(1800.0), 100.0, busy = false), 0L, utc)
        f.onResultsEvent(ResultsEvent.Deselect)
        f.onDetours(StopDetours(stop.requestId, listOf(60.0, null, 180.0, 240.0, 300.0), 900.0, done = true))
        assertEquals(listOf("Station 1", "Station 2", "Station 3", "Station 4", "Station 5"), results.rows.map { it.title })
        assertEquals(listOf("+1 min", "+2 min", "+3 min", "+4 min", "+5 min"), results.rows.map { it.detourText })
        // the wait runs out: nearest first as before, the look's figure on its row
        fake.answers = { five }
        gasChip()
        f.onResultsEvent(ResultsEvent.Select(2))
        f.onStopPreview(StopPreview(results.places[2], route(1800.0), 30.0, busy = false), 0L, utc)
        scope.advanceTimeBy(4_000)
        scope.runCurrent()
        assertEquals("Detours took too long. Nearest first.", stop.note)
        assertEquals((1..5).map { "Station $it" }, results.rows.map { it.title })
        assertEquals(listOf(null, null, "On the way", null, null), results.rows.map { it.detourText })
        // a look at newer stop results starts again: nothing from the older one's
        fake.answers = { five }
        gasChip()
        f.onDetours(StopDetours(stop.requestId, listOf(60.0, 120.0, 180.0, 240.0, 300.0), 900.0, done = true))
        assertEquals(listOf("+1 min", "+2 min", "+3 min", "+4 min", "+5 min"), results.rows.map { it.detourText })
    }

    /** Review F3 (add-stop.md section 6): a saved place picked from a typed Add stop list keeps the words for Back; Home and Work give "". */
    @Test
    fun aSavedRowOfATypedListKeepsTheWordsForBack() {
        val moms = (saved.save(SearchFeatureTest.museumPlace, SavedKind.OTHER, "Mom's") as SaveResult.Done).place!!
        f.open(SearchMode.ADD_STOP, gps, driving = true, along = line)
        f.onEvent(SearchEvent.QueryChanged("mom"))
        settle()
        val row = f.ui.value.savedResults.single()
        val out = f.onEvent(SearchEvent.RowTapped(row.key)) as SearchOutput.ShowStopResults
        assertEquals(listOf(moms.toPlace()), out.places)
        assertEquals(0, out.selected)
        assertEquals("Mom's", stop.preview!!.title)
        assertEquals("mom", stop.typed)
    }

    @Suppress("unused")
    private fun Place.dummy() = this
}
