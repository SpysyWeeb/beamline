package io.github.spysyweeb.beamline.feature.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.ui.theme.BeamlineTheme
import io.github.spysyweeb.beamline.ui.theme.SHEET_HANDLE_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Item 51 (rule S12) on the device: the stop results' window ("Working out detours...", then the rows sorted
 * with "+N min"), the rows at large text, the card and its "Add stop", by day and night (screens/ PNGs), and
 * in a side panel as in landscape. Made-up gas stations at public points in Erie.
 */
@RunWith(AndroidJUnit4::class)
class StopResultsScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private var content by mutableStateOf<@Composable () -> Unit>({})
    private var night by mutableStateOf(false)
    private var fontScale by mutableStateOf<Float?>(null)

    @Before
    fun setUp() {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale ?: d.fontScale)) {
                BeamlineTheme(night = night) { content() }
            }
        }
    }

    private fun show(n: Boolean = false, fs: Float? = null, c: @Composable () -> Unit) {
        compose.runOnUiThread { content = c; night = n; fontScale = fs }
        compose.waitForIdle()
        Thread.sleep(150)
        compose.waitForIdle()
    }

    /** [c] at the bottom of a 360 x 700 dp map; a screenshot by day and night when [name] is set. */
    private fun onMap(name: String?, fs: Float? = null, c: @Composable () -> Unit) {
        for (n in listOf(false, true)) {
            show(n, fs) {
                Box(Modifier.size(360.dp, 700.dp).background(if (night) Color(0xFF1C242C) else Color(0xFFE7E5DF)).testTag(SearchScreensTest.TAG)) {
                    Box(Modifier.align(Alignment.BottomCenter)) { c() }
                }
            }
            if (name != null) SearchScreensTest.save(compose, "$name-${if (n) "night" else "day"}")
            if (name == null) break
        }
        show(false, fs) {
            Box(Modifier.size(360.dp, 700.dp).testTag(SearchScreensTest.TAG)) { Box(Modifier.align(Alignment.BottomCenter)) { c() } }
        }
    }

    private val zoo = GeoPoint(42.0971, -80.07578)
    private fun place(i: Int, name: String) = Place(name, GeoPoint(zoo.lat + i * 0.01, zoo.lon), PlaceSource.SEARCH, "Erie", "fuel", 100L + i, "us-pa")
    private val names = listOf("Sheetz", "Country Fair", "Kwik Fill", "GetGo", "Sunoco")
    private val places = names.mapIndexed { i, n -> place(i + 1, n) }
    private val distances = listOf("2.1 mi", "3.4 mi", "4.0 mi", "5.2 mi", "6.8 mi")
    private val leads = listOf("+4 min", "On the way", "+1 h 5 min", "+12 min", null)
    private fun rows(withLeads: Boolean) = names.mapIndexed { i, n ->
        ResultRowUi("r:us-pa:${101 + i}", "fuel", n, "Erie", distances[i], null, detourText = if (withLeads) leads[i] else null)
    }

    private val events = ArrayList<ResultsEvent>()

    /** 10: while working the window says so and shows no rows; then the sorted rows with the time each adds. */
    @Test
    fun workingShowsNoRowsThenTheSortedList() {
        val working = ResultsUi("Gas", rows(false), places, stop = StopResultsUi(1, working = true, after = "Wegmans"))
        onMap("stop-results-working") { ResultsSheet(working, { events.add(it) }) }
        compose.onNodeWithText("Working out detours...").assertIsDisplayed()
        compose.onNodeWithText("Along your route after Wegmans").assertIsDisplayed()
        compose.onNodeWithTag(STOP_WORKING_TAG).assertIsDisplayed()
        for (n in names) assertEquals(0, compose.onAllNodesWithContentDescription(n, substring = true).fetchSemanticsNodes().size)
        // TalkBack says when the wait ends: the status line is a polite live region
        // (the header is one merged node for TalkBack; the line keeps its own live region inside it)
        assertNotNull(compose.onNodeWithTag(STOP_STATUS_TAG, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion))
        val done = working.copy(rows = rows(true), stop = working.stop!!.copy(working = false))
        onMap("stop-results-sorted") { ResultsSheet(done, { events.add(it) }) }
        compose.onNodeWithText("5 places").assertIsDisplayed()
        compose.onNodeWithContentDescription("Sheetz, Erie, +4 min, 2.1 mi").assertIsDisplayed()
        compose.onNodeWithContentDescription("Country Fair, Erie, On the way, 3.4 mi").assertIsDisplayed()
        val tops = names.take(3).map { compose.onNodeWithContentDescription("$it, Erie", substring = true).fetchSemanticsNode().boundsInRoot.top }
        assertTrue("rows in their order: $tops", tops == tops.sorted())
        compose.onNodeWithContentDescription("Country Fair, Erie", substring = true).performClick()
        assertEquals(listOf<ResultsEvent>(ResultsEvent.Select(1)), events)
        // a fallback says why the order is nearest first
        val slow = done.copy(stop = done.stop!!.copy(note = "Detours took too long. Nearest first."))
        onMap("stop-results-slow") { ResultsSheet(slow, {}) }
        compose.onNodeWithText("Detours took too long. Nearest first.").assertIsDisplayed()
        assertNotNull(compose.onNodeWithTag(STOP_NOTE_TAG, useUnmergedTree = true).fetchSemanticsNode().config.getOrNull(SemanticsProperties.LiveRegion))
    }

    /** 10b: at large text (stacked rows) every row still says what it adds; the screenshots show 1.3 and 1.5. */
    @Test
    fun theTimeAddedShowsAtLargeText() {
        val done = ResultsUi("Gas", rows(true), places, stop = StopResultsUi(1, working = false))
        for (fs in listOf(1.3f, 1.5f)) {
            onMap("stop-results-font-$fs", fs) { ResultsSheet(done, {}) }
            for ((i, n) in names.withIndex()) {
                val lead = leads[i] ?: continue
                compose.onNodeWithTag(RESULTS_ROWS_TAG).performScrollToIndex(i)   // the list composes only the rows in view
                compose.waitForIdle()
                compose.onNodeWithContentDescription("$n, Erie, $lead, ${distances[i]}").assertIsDisplayed()
            }
        }
    }

    /** 11: the card: back to the list, the X, the time line's words, "Goes after", and only its button adds the stop. */
    @Test
    fun theCardAddsOnlyFromItsButton() {
        val card = StopPreviewUi("Sheetz", "Erie", addedText = "+4 min", arriveText = "Arrive 4:33 PM", busy = false, afterText = "Goes after Wegmans")
        val ui = ResultsUi("Gas", rows(true), places, selected = 0, stop = StopResultsUi(1, working = false, after = "Wegmans", preview = card))
        onMap("stop-card") { ResultsSheet(ui, { events.add(it) }) }
        compose.onNodeWithTag(STOP_CARD_TAG).assertIsDisplayed()
        compose.onNodeWithText("Goes after Wegmans").assertIsDisplayed()
        assertEquals("Adds 4 min, arrive 4:33 PM",
            compose.onNodeWithTag(STOP_TIME_TAG).fetchSemanticsNode().config[SemanticsProperties.ContentDescription].joinToString())
        compose.onNodeWithContentDescription("Add Sheetz as a stop").assertIsEnabled().performClick()
        assertEquals(listOf<ResultsEvent>(ResultsEvent.AddStop), events)
        compose.onNodeWithContentDescription("Back to the list").performClick()
        compose.onNodeWithContentDescription("Close results").performClick()
        assertEquals(listOf(ResultsEvent.AddStop, ResultsEvent.Deselect, ResultsEvent.Close), events)
        // no other row or button adds it
        assertEquals(0, compose.onAllNodesWithContentDescription("Country Fair", substring = true).fetchSemanticsNodes().size)
        assertTargets()
        // while the route is worked out; a stop too many: the button is off
        onMap("stop-card-busy") { ResultsSheet(ui.copy(stop = ui.stop!!.copy(preview = card.copy(busy = true, arriveText = null))), {}) }
        compose.onNodeWithText("Working out the route...").assertIsDisplayed()
        val tooMany = card.copy(problem = "A trip can have up to 9 stops.", canAdd = false)
        onMap("stop-card-too-many") { ResultsSheet(ui.copy(stop = ui.stop!!.copy(preview = tooMany)), { events.add(it) }) }
        compose.onNodeWithText("A trip can have up to 9 stops.").assertIsDisplayed()
        compose.onNodeWithContentDescription("Add Sheetz as a stop").assertIsNotEnabled()
    }

    /**
     * Item 52 (rule H7): a place handed over by another app during a trip opens as one card with no list behind it (no
     * back arrow), "Add stop" and "Go here instead" under it; both buttons show at font scale 2.0.
     */
    @Test
    fun aHandedOverCardHasNoListBehindItAndCanGoInstead() {
        val quokka = Place("Quokka Den", zoo, PlaceSource.SEARCH, "Near 815 Example Street, Erie")
        val row = ResultRowUi("stop", "restaurant", "Quokka Den", "Near 815 Example Street, Erie", "2.1 mi", null)
        val card = StopPreviewUi("Quokka Den", "Near 815 Example Street, Erie", "+6 min", "Arrive 4:33 PM", busy = false)
        val ui = ResultsUi("Quokka Den", listOf(row), listOf(quokka), selected = 0,
            stop = StopResultsUi(1, working = false, instead = true, preview = card))
        onMap("stop-card-instead") { ResultsSheet(ui, { events.add(it) }) }
        assertEquals(0, compose.onAllNodesWithContentDescription("Back to the list").fetchSemanticsNodes().size)
        compose.onNodeWithTag(STOP_INSTEAD_TAG).assertIsDisplayed()
        compose.onNodeWithContentDescription("Go to Quokka Den instead of your destination").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("Add Quokka Den as a stop").performClick()
        compose.onNodeWithContentDescription("Close results").performClick()
        assertEquals(listOf(ResultsEvent.GoInstead, ResultsEvent.AddStop, ResultsEvent.Close), events)
        assertTargets()
        // large text: both buttons still in the card
        onMap("stop-card-instead-font-2", 2f) { ResultsSheet(ui, {}) }
        compose.onNodeWithContentDescription("Go to Quokka Den instead of your destination").assertIsDisplayed()
        compose.onNodeWithContentDescription("Add Quokka Den as a stop").assertIsDisplayed()
        // an ordinary card of a list keeps its way back and has no "Go here instead"
        val two = ResultsUi("Gas", rows(true).take(2), places.take(2), selected = 0, stop = StopResultsUi(1, working = false, preview = card))
        onMap(null) { ResultsSheet(two, {}) }
        compose.onNodeWithContentDescription("Back to the list").assertIsDisplayed()
        assertEquals(0, compose.onAllNodesWithTag(STOP_INSTEAD_TAG).fetchSemanticsNodes().size)
    }

    /**
     * Review F5 (rule S12): the card of a one-place result (a single pick) has no list behind it: no "Back to the list"
     * (system Back goes to the Add stop search, AppFlows.backFromStop); its X and "Add stop" stay.
     */
    @Test
    fun aOnePlaceCardHasNoArrowBackToAList() {
        val card = StopPreviewUi("Sheetz", "Erie", addedText = "+4 min", arriveText = "Arrive 4:33 PM", busy = false)
        val one = ResultsUi("Sheetz", rows(true).take(1), places.take(1), selected = 0, stop = StopResultsUi(1, working = false, typed = "sheetz", preview = card))
        onMap("stop-card-one-place") { ResultsSheet(one, { events.add(it) }) }
        assertEquals(0, compose.onAllNodesWithContentDescription("Back to the list").fetchSemanticsNodes().size)
        compose.onNodeWithText("Sheetz").assertIsDisplayed()
        compose.onNodeWithContentDescription("Add Sheetz as a stop").performClick()
        compose.onNodeWithContentDescription("Close results").performClick()
        assertEquals(listOf(ResultsEvent.AddStop, ResultsEvent.Close), events)
        assertTargets()
    }

    /**
     * Review F6 (S12, "TalkBack: wait end"): the card's status is one line, a polite live region, whose words change in
     * place: "Working out the route...", then "+4 min · Arrive 4:33 PM" (or why there is none), so TalkBack says the end.
     */
    @Test
    fun theCardsStatusIsOneLiveLine() {
        var card by mutableStateOf(StopPreviewUi("Sheetz", "Erie", addedText = null, arriveText = null, busy = true))
        onMap(null) { ResultsSheet(ResultsUi("Gas", rows(true), places, selected = 0, stop = StopResultsUi(61, working = false, preview = card)), {}) }
        fun line() = compose.onNodeWithTag(STOP_TIME_TAG, useUnmergedTree = true).fetchSemanticsNode()
        val busy = line()
        assertNotNull(busy.config.getOrNull(SemanticsProperties.LiveRegion))
        compose.onNodeWithText("Working out the route...").assertIsDisplayed()
        compose.runOnUiThread { card = card.copy(addedText = "+4 min", arriveText = "Arrive 4:33 PM", busy = false) }
        compose.waitForIdle()
        val done = line()
        assertEquals("the same node", busy.id, done.id)
        assertNotNull(done.config.getOrNull(SemanticsProperties.LiveRegion))
        assertEquals("Adds 4 min, arrive 4:33 PM", done.config[SemanticsProperties.ContentDescription].joinToString())
        compose.runOnUiThread { card = card.copy(busy = true) }
        compose.waitForIdle()
        compose.runOnUiThread { card = card.copy(busy = false, problem = "Could not work out the route with this stop.") }
        compose.waitForIdle()
        val failed = line()
        assertEquals("the same node", busy.id, failed.id)
        assertNotNull(failed.config.getOrNull(SemanticsProperties.LiveRegion))
        compose.onNodeWithText("Could not work out the route with this stop.").assertIsDisplayed()
    }

    /**
     * Review F1: rows that still carry P2's flag in two runs. Stop results (sorted by minutes) show no "Other matches";
     * a results list with two runs has a heading over each, each its own key. At full height every row is composed at
     * once (two items with one key threw there).
     */
    @Test
    fun otherMatchesHeadingsNeverShareAKey() {
        val flags = listOf(false, false, true, false, true)
        val mixed = rows(true).mapIndexed { i, r -> r.copy(otherMatch = flags[i]) }
        val stopUi = ResultsUi("Gas", mixed, places, stop = StopResultsUi(71, working = false))
        val s11 = ResultsUi("hospital two runs", mixed.map { it.copy(detourText = null) }, places)
        for ((ui, headings) in listOf(stopUi to 0, s11 to 2)) {
            onMap(null) { ResultsSheet(ui, {}) }
            compose.onNodeWithTag(SHEET_HANDLE_TAG).performClick()
            compose.waitForIdle()
            assertEquals("Full height", compose.onNodeWithTag(SHEET_HANDLE_TAG).fetchSemanticsNode().config[SemanticsProperties.StateDescription])
            for (n in names) compose.onNodeWithContentDescription("$n, Erie", substring = true).assertIsDisplayed()
            assertEquals(headings, compose.onAllNodesWithText("Other matches").fetchSemanticsNodes().size)
        }
    }

    /** 10b: landscape (891 x 411 dp): the list and the card in a 400 dp side panel (screenshots). */
    @Test
    fun inTheSidePanel() {
        val done = ResultsUi("Gas", rows(true), places, stop = StopResultsUi(1, working = false))
        val card = StopPreviewUi("Sheetz", "Erie", addedText = "+4 min", arriveText = "Arrive 4:33 PM", busy = false)
        for ((name, ui) in listOf("stop-results-landscape" to done, "stop-card-landscape" to done.copy(selected = 0, stop = done.stop!!.copy(preview = card)))) {
            for (n in listOf(false, true)) {
                show(n) {
                    Box(Modifier.requiredSize(891.dp, 411.dp).background(if (night) Color(0xFF1C242C) else Color(0xFFE7E5DF)).testTag(SearchScreensTest.TAG)) {
                        Box(Modifier.width(400.dp).fillMaxHeight()) { Box(Modifier.align(Alignment.BottomCenter)) { ResultsSheet(ui, {}) } }
                    }
                }
                SearchScreensTest.save(compose, "$name-${if (n) "night" else "day"}")
            }
            compose.onNodeWithTag(RESULTS_SHEET_TAG).assertIsDisplayed()
        }
    }

    /** SP5 item 2: every enabled tap target is at least 48 by 48 dp. */
    private fun assertTargets() {
        val nodes = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        for (n in nodes) {
            if (n.config.contains(SemanticsProperties.Disabled)) continue
            val min = with(n.layoutInfo.density) { 48.dp.toPx() } - 1f
            val w = maxOf(n.size.width.toFloat(), n.touchBoundsInRoot.width)
            val h = maxOf(n.size.height.toFloat(), n.touchBoundsInRoot.height)
            assertTrue("a target is $w x $h px, below 48 dp", w >= min && h >= min)
        }
    }

    @Suppress("unused")
    @Composable
    private fun Unused() = Box(Modifier.fillMaxSize())
}
