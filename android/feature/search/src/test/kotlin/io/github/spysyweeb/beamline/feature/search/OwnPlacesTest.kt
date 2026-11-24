package io.github.spysyweeb.beamline.feature.search

import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.search.CategoryInfo
import io.github.spysyweeb.beamline.search.PoiDetails
import io.github.spysyweeb.beamline.search.RecentPlaces
import io.github.spysyweeb.beamline.search.awaitStoreWrites
import io.github.spysyweeb.beamline.search.saved.SavedPlaces
import io.github.spysyweeb.beamline.search.saved.SavedRules
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.Executor

/**
 * Batch O3: the user's own changes in search and on the sheet as if the map had them (docs/next-round.md
 * item 26), and the "View all" grid of place types (item 27).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class OwnPlacesTest {
    @TempDir
    lateinit var dir: File

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val installed = MutableStateFlow(SearchFeatureTest.withMaps())
    private val fake = FakeSearcher()
    private lateinit var f: SearchFeature

    private val subwayAt = GeoPoint(42.12915, -80.08530)       // 425 State Street, Erie
    private val subway = SearchFeatureTest.row(5, "Subway", subwayAt, cat = "fast_food")

    @BeforeEach
    fun setUp() {
        val saved = SavedPlaces(File(dir, "saved-places.json"), SavedRules.bundled(), Executor { it.run() })
        f = SearchFeature(installed, RecentPlaces(File(dir, "recents.json")), saved, MutableStateFlow(SearchFeatureTest.units), scope, dispatcher, fake)
        fake.answers = { t -> if (t.contains("sub", true) || t.contains("food", true)) listOf(subway) else emptyList() }
        fake.details = mapOf(5L to PoiDetails("Mo-Su 08:00-22:00", "+1 814 456 2718", null, "425 State Street, Erie", "Fast food", "n7570266250"))
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

    private fun search(text: String): List<ResultRowUi> {
        f.onEvent(SearchEvent.QueryChanged(text))
        settle()
        return f.ui.value.results
    }

    @Test
    fun aRenamedPlaceIsFoundByItsNewNameAndTheOldEntryIsNotShown() {
        f.ownPlaces = listOf(OwnPlaceUi("e1", "Subway Downtown", subwayAt, null, null, oldName = "Subway", overrides = mapOf("opening_hours" to "Mo-Su 07:00-23:00")))
        f.open(SearchMode.GO_TO, SearchFeatureTest.gps)
        val rows = search("subway")
        assertEquals(listOf("Subway Downtown"), rows.map { it.title }, "the pack's Subway at that spot is not shown")
        assertEquals("own:e1", rows[0].key)
        val out = f.onEvent(SearchEvent.RowTapped("own:e1")) as SearchOutput.Picked
        // routing takes the point; the sheet shows the user's values with the small tag
        assertEquals(subwayAt, out.place.point)
        val sheet = f.placeSheet(out.place, SearchFeatureTest.gps, tripActive = false)
        assertEquals("Subway Downtown", sheet.title)
        assertEquals(Texts.OWN_TAG, sheet.ownTag)
        assertTrue(PlaceAction.DIRECTIONS in sheet.actions)
    }

    @Test
    fun anAddedPlaceIsFoundByItsNameAndItsType() {
        val at = GeoPoint(42.1300, -80.0840)
        f.ownPlaces = listOf(OwnPlaceUi("a1", "Joe's Pizza", at, "pizza", "Pizza", terms = listOf("food"),
            overrides = mapOf("opening_hours" to "Mo-Su 11:00-22:00", "phone" to null, "website" to null), osm = "n77"))
        f.open(SearchMode.GO_TO, SearchFeatureTest.gps)
        assertEquals("Joe's Pizza", search("joe").first().title)
        assertEquals(listOf("Joe's Pizza", "Subway"), search("food").map { it.title }, "its type's word finds it, beside the pack's places")
        assertEquals("Joe's Pizza", search("pizza").first().title)
        val sheet = f.placeSheet(Place("Joe's Pizza", at, PlaceSource.MAP_POI), SearchFeatureTest.gps, tripActive = false)
        assertEquals(Texts.OWN_TAG, sheet.ownTag)
        assertEquals("Pizza", sheet.category)
        assertNotNull(sheet.hours)
        assertEquals("n77", sheet.osm)
    }

    @Test
    fun aClosedPlaceShowsAsClosed() {
        f.ownPlaces = listOf(OwnPlaceUi("c1", "Subway", subwayAt, null, null, oldName = "Subway", closed = true))
        f.open(SearchMode.GO_TO, SearchFeatureTest.gps)
        val rows = search("subway")
        assertEquals(1, rows.size)
        assertEquals(Texts.CLOSED_PERMANENTLY, rows[0].note)
        assertNull(rows[0].hours)
        val sheet = f.placeSheet(Place("Subway", subwayAt, PlaceSource.MAP_POI), SearchFeatureTest.gps, tripActive = false)
        assertEquals(Texts.CLOSED_PERMANENTLY, sheet.category)
        assertNull(sheet.hours)
        assertNull(sheet.phone)
    }

    @Test
    fun theOtherPlacesAreUntouched() {
        f.ownPlaces = listOf(OwnPlaceUi("e1", "Subway Downtown", GeoPoint(42.20, -80.00), null, null, oldName = "Subway"))
        f.open(SearchMode.GO_TO, SearchFeatureTest.gps)
        assertEquals(listOf("Subway Downtown", "Subway"), search("subway").map { it.title }, "a Subway far from the changed one stays")
    }

    @Test
    fun viewAllShowsEveryTypeUnderHeadingsAndATapSearchesAsAChipDoes() = runTest(dispatcher) {
        val cats = listOf(
            CategoryInfo("fuel", "Gas station", "car", false, "gas station"),
            CategoryInfo("restaurant", "Restaurant", "food", false, "restaurant"),
            CategoryInfo("coffee", "Coffee", "food", false, "coffee"),
        )
        val withCats = object : PackSearcher by fake {
            override fun categories(set: io.github.spysyweeb.beamline.api.data.InstalledSet) = cats
        }
        val g = SearchFeature(installed, RecentPlaces(File(dir, "r2.json")), SavedPlaces(File(dir, "s2.json"), SavedRules.bundled(), Executor { it.run() }),
            MutableStateFlow(SearchFeatureTest.units), scope, dispatcher, withCats)
        g.open(SearchMode.GO_TO, SearchFeatureTest.gps)
        g.onEvent(SearchEvent.ViewAll)
        settle()
        val groups = g.ui.value.allTypes!!
        assertEquals(listOf("For the car", "Food and drink"), groups.map { it.heading })
        assertEquals(listOf("restaurant", "coffee"), groups[1].types.map { it.code })
        assertEquals("fuel", groups[0].types[0].iconName, "the map's and the rows' icon")
        g.onEvent(SearchEvent.TypeTapped(groups[1].types[1]))
        settle()
        assertNull(g.ui.value.allTypes)
        assertEquals("coffee", g.ui.value.query)
        assertEquals("coffee", fake.asked.last())
        // Back closes the grid first, not the search
        g.onEvent(SearchEvent.ViewAll)
        settle()
        assertEquals(null, g.onEvent(SearchEvent.Close))
        assertNull(g.ui.value.allTypes)
    }
}
