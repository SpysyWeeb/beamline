package io.github.spysyweeb.beamline.feature.search

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.spysyweeb.beamline.api.data.InstalledSet
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.model.SavedKind
import io.github.spysyweeb.beamline.search.RecentPlaces
import io.github.spysyweeb.beamline.search.awaitStoreWrites
import io.github.spysyweeb.beamline.search.hours.OpenState
import io.github.spysyweeb.beamline.search.saved.SavedPlaces
import io.github.spysyweeb.beamline.search.saved.SavedRules
import io.github.spysyweeb.beamline.ui.theme.BeamlineTheme
import io.github.spysyweeb.beamline.ui.theme.SHEET_HANDLE_TAG
import io.github.spysyweeb.beamline.units.UnitFormatter
import io.github.spysyweeb.beamline.units.UnitRules
import io.github.spysyweeb.beamline.units.UnitSystem
import java.io.File
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The place window and saved places on the device (0.1.40, play test P9, P17, P51, P53/F41, P56, P57/F47, P58,
 * P60; design places.md). Made-up places at public points in Erie; one content per test, swapped through state.
 */
@RunWith(AndroidJUnit4::class)
class PlaceWindowScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private val ctx = InstrumentationRegistry.getInstrumentation().targetContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private lateinit var work: File

    private var content by mutableStateOf<@Composable () -> Unit>({})
    private var fontScale by mutableStateOf<Float?>(null)
    /** P60: the window height the screen reads (LocalConfiguration.screenHeightDp); null: the device's own. */
    private var screenHeight by mutableStateOf<Int?>(null)

    @Before
    fun setUp() {
        work = File(ctx.cacheDir, "place-window-work").apply { deleteRecursively(); mkdirs() }
        compose.setContent {
            val d = LocalDensity.current
            val config = LocalConfiguration.current
            val shown = screenHeight?.let { h -> Configuration(config).apply { screenHeightDp = h } } ?: config
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale ?: d.fontScale), LocalConfiguration provides shown) {
                BeamlineTheme(night = false) { content() }
            }
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        awaitStoreWrites()
    }

    private fun show(fs: Float? = null, height: Int? = null, c: @Composable () -> Unit) {
        compose.runOnUiThread { content = c; fontScale = fs; screenHeight = height }
        compose.waitForIdle()
    }

    private fun handle() = compose.onNodeWithTag(SHEET_HANDLE_TAG)
    private fun state(): String = handle().fetchSemanticsNode().config[SemanticsProperties.StateDescription]
    private fun SemanticsNodeInteraction.action(label: String) {
        val a = fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == label }
        compose.runOnUiThread { a.action() }
        compose.waitForIdle()
    }
    private fun bounds(n: SemanticsNodeInteraction): Rect = n.fetchSemanticsNode().boundsInRoot
    private fun px(dp: Dp): Float = dp.value * ctx.resources.displayMetrics.density
    private fun overlapVertically(a: Rect, b: Rect) = a.top < b.bottom && b.top < a.bottom

    private val museum = Place("Erie Maritime Museum", GeoPoint(42.13646, -80.08718), PlaceSource.SEARCH, "100 East Front Street, Erie", "museum")
    private fun museumUi() = PlaceSheetUi(museum, "museum", museum.name, museum.subtitle, "2.3 mi", null, listOf(PlaceAction.DIRECTIONS, PlaceAction.SAVE))

    /** The pixel [fromRight] in from the right edge of [tag]'s node, at its vertical middle. */
    private fun pixelNearRight(tag: String, fromRight: Dp): Int {
        val image = compose.onNodeWithTag(tag).captureToImage().asAndroidBitmap()
        return image.getPixel((image.width - px(fromRight)).toInt(), image.height / 2)
    }
    private fun red(c: Int) = (c shr 16) and 0xFF
    private fun green(c: Int) = (c shr 8) and 0xFF
    private fun blue(c: Int) = c and 0xFF

    // ---------------------------------------------------------------- P9
    /** P9: the place window is opaque: over pure red its plain panel stays white (today 95 %: about (255, 242, 242)). */
    @Test
    fun placeWindowIsOpaque() {
        show { Box(Modifier.size(360.dp, 700.dp).background(Color.Red)) { PlaceSheetContent(museumUi(), {}, Modifier.align(Alignment.BottomCenter)) } }
        val c = pixelNearRight(PLACE_SHEET_TAG, 8.dp)   // right of the close button's 16 dp end padding: plain panel
        assertTrue("panel pixel r=${red(c)} g=${green(c)} b=${blue(c)}", green(c) >= 250 && blue(c) >= 250)
    }

    /** P9: the map pick's bar too. */
    @Test
    fun mapPickBarIsOpaque() {
        val ui = MapPickUi("Tap the map at your work", null, "No point chosen yet", null, null, "Set work", false, null)
        show { Box(Modifier.size(360.dp, 700.dp).background(Color.Red)) { MapPickBar(ui, {}) } }
        val c = pixelNearRight(MAP_PICK_BAR_TAG, 8.dp)   // inside the column's 16 dp padding
        assertTrue("bar pixel r=${red(c)} g=${green(c)} b=${blue(c)}", green(c) >= 250 && blue(c) >= 250)
    }

    /** P9, P51, P53, F41: at peek the window says where the place is ("distance · where") and why it offers "Add a state". */
    @Test
    fun peekSaysWhereThePlaceIs() {
        var ui by mutableStateOf(museumUi())
        show { Box(Modifier.size(360.dp, 700.dp)) { PlaceSheetContent(ui, {}, Modifier.align(Alignment.BottomCenter)) } }
        // (a) P51: a city's line from the list
        val pgh = Place("Pittsburgh", GeoPoint(40.4406, -79.9959), PlaceSource.SEARCH, "City, Allegheny County, PA", "city")
        compose.runOnUiThread { ui = PlaceSheetUi(pgh, "city", pgh.name, pgh.subtitle, "117 mi", null, listOf(PlaceAction.DIRECTIONS, PlaceAction.SAVE)) }
        compose.waitForIdle()
        assertEquals("Lowered", state())
        compose.onNodeWithText("117 mi · City, Allegheny County, PA", useUnmergedTree = true).assertIsDisplayed()
        // (b) P9: a map label with no line of its own: its pack address
        val bean = Place("Tipsy Bean", GeoPoint(42.1275, -80.0851), PlaceSource.MAP_POI)
        compose.runOnUiThread {
            ui = PlaceSheetUi(bean, "cafe", bean.name, null, "0.4 mi", null, listOf(PlaceAction.DIRECTIONS, PlaceAction.SAVE), address = "123 Made Up Street, Erie, PA 16501")
        }
        compose.waitForIdle()
        assertEquals("Lowered", state())
        compose.onNodeWithText("123 Made Up Street, Erie, PA 16501", substring = true, useUnmergedTree = true).assertIsDisplayed()
        // (c) P53: Home's window says where Home is
        val home = Place("Home", GeoPoint(42.0971, -80.07578), PlaceSource.SAVED, "Erie Maritime Museum, 100 East Front Street, Erie", savedId = "s0000000000000001")
        compose.runOnUiThread { ui = PlaceSheetUi(home, "saved_home", "Home", home.subtitle, "2.3 mi", null, listOf(PlaceAction.DIRECTIONS, PlaceAction.EDIT)) }
        compose.waitForIdle()
        assertEquals("Lowered", state())
        compose.onNodeWithText("Erie Maritime Museum, 100 East Front Street, Erie", substring = true, useUnmergedTree = true).assertIsDisplayed()
        // (d) F41: outside the maps, the note says why the main button reads "Add a state"
        val far = Place("Point State Park", GeoPoint(40.4416, -80.0128), PlaceSource.SEARCH, "Pittsburgh", "park")
        compose.runOnUiThread { ui = PlaceSheetUi(far, "park", far.name, far.subtitle, "118 mi", "Outside the installed maps", listOf(PlaceAction.OPEN_STATES, PlaceAction.SAVE)) }
        compose.waitForIdle()
        assertEquals("Lowered", state())
        compose.onNodeWithText("Outside the installed maps", substring = true, useUnmergedTree = true).assertIsDisplayed()
        compose.onNodeWithText(Texts.ADD_STATE_BUTTON).assertIsDisplayed()
    }

    /**
     * The 0.1.40 replay: raised, the open-status line sits right under the distance, at the middle height and at the
     * details height (it sat at the middle of a 48 dp tap box, about 30 dp under the distance, "a missing row"); at
     * the middle height, where a tap shows the week, it is still a 48 dp tap target.
     */
    @Test
    fun theStatusLineSitsUnderTheDistance() {
        val subs = Place("Made Up Subs", GeoPoint(42.12795, -80.08596), PlaceSource.MAP_POI, "123 Made Up Street, Erie, PA 16501", "fast_food")
        val week = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun").map { HoursDayUi(it, "8 AM–10 PM", it == "Fri") }
        val ui = PlaceSheetUi(
            subs, "fast_food", subs.name, subs.subtitle, "0.1 mi", null, listOf(PlaceAction.DIRECTIONS, PlaceAction.SAVE, PlaceAction.CALL),
            hours = HoursUi(OpenState.CLOSING_SOON, HoursTexts.CLOSING_SOON, "closes at 10 PM", week), phone = "+1 814-555-0100",
        )
        show { Box(Modifier.size(411.dp, 891.dp)) { PlaceSheetContent(ui, {}, Modifier.align(Alignment.BottomCenter)) } }
        fun gap(): Float {
            val distance = bounds(compose.onNodeWithText("0.1 mi", useUnmergedTree = true))
            val status = bounds(compose.onNodeWithText(HoursTexts.CLOSING_SOON, useUnmergedTree = true))
            return status.top - distance.bottom
        }
        handle().action("Raise the sheet")
        assertEquals("Half open", state())
        assertTrue("middle: ${gap()} px between the distance and the status", gap() <= px(6.dp))
        assertTrue(bounds(compose.onNodeWithTag(PLACE_STATUS_TAG)).height >= px(48.dp) - 1f)
        compose.onNodeWithTag(PLACE_STATUS_TAG).performClick()
        compose.waitForIdle()
        assertEquals("Full height", state())
        assertTrue("details: ${gap()} px between the distance and the status", gap() <= px(6.dp))
    }

    /** P9 guard: on its side at large text the where part costs the peek no extra line. */
    @Test
    fun peekKeepsItsHeightWithItsWhereLine() {
        val pgh = Place("Pittsburgh", GeoPoint(40.4406, -79.9959), PlaceSource.SEARCH, "City, Allegheny County, PA", "city")
        val with = PlaceSheetUi(pgh, "city", pgh.name, pgh.subtitle, "117 mi", null, listOf(PlaceAction.DIRECTIONS, PlaceAction.SAVE))
        var ui by mutableStateOf(with)
        show(fs = 1.3f) { Box(Modifier.size(640.dp, 360.dp)) { PlaceSheetContent(ui, {}, Modifier.align(Alignment.BottomCenter)) } }
        assertEquals("Lowered", state())
        val h1 = bounds(compose.onNodeWithTag(PLACE_SHEET_TAG)).height
        compose.runOnUiThread { ui = with.copy(subtitle = null) }
        compose.waitForIdle()
        assertEquals("Lowered", state())
        assertEquals(bounds(compose.onNodeWithTag(PLACE_SHEET_TAG)).height, h1, 0.5f)
    }

    // ---------------------------------------------------------------- P17, F42
    private fun pinUi(line: String) = PlaceSheetUi(
        Place("Dropped pin", GeoPoint(42.13646, -80.08718), PlaceSource.MAP_PIN, subtitle = line), Icons.PLACE, "Dropped pin", line, "450 ft", null,
        listOf(PlaceAction.DIRECTIONS, PlaceAction.SAVE),
    )

    /** P17: a dropped pin has a pin, shows its line at peek, and keeps its height when the street arrives and when it is saved. */
    @Test
    fun droppedPinHasAPinAndKeepsItsHeight() {
        var ui by mutableStateOf(pinUi("42.13646, -80.08718"))
        show { Box(Modifier.size(360.dp, 700.dp)) { PlaceSheetContent(ui, {}, Modifier.align(Alignment.BottomCenter)) } }
        assertEquals(1, compose.onAllNodes(hasTestTag(PLACE_PIN_ICON_TAG), useUnmergedTree = true).fetchSemanticsNodes().size)
        compose.onNodeWithText("450 ft · 42.13646, -80.08718", useUnmergedTree = true).assertIsDisplayed()
        handle().action("Raise the sheet")
        assertEquals("Half open", state())
        compose.runOnUiThread { ui = pinUi("Near East 5th Street, Erie") }
        compose.waitForIdle()
        assertEquals("Half open", state())
        compose.onNodeWithText("Near East 5th Street, Erie", useUnmergedTree = true).assertIsDisplayed()
        compose.runOnUiThread { ui = ui.copy(place = ui.place.copy(savedId = "s0000000000000001"), actions = listOf(PlaceAction.DIRECTIONS, PlaceAction.EDIT)) }
        compose.waitForIdle()
        assertEquals("Half open", state())
        // a place that is not a dropped pin keeps its sprite
        compose.runOnUiThread { ui = museumUi() }
        compose.waitForIdle()
        assertEquals(0, compose.onAllNodes(hasTestTag(PLACE_PIN_ICON_TAG), useUnmergedTree = true).fetchSemanticsNodes().size)
        assertEquals("Lowered", state())   // another place opens at peek (item 47)
    }

    // ---------------------------------------------------------------- P53 / F41
    /** F41: at 1.3x with a long drive time, Save wraps under Directions, whole and inside the window, without scrolling. */
    @Test
    fun peekButtonsWrapAtLargeText() {
        val ui = museumUi().copy(driveTimeText = "1 h 59 min")
        show(fs = 1.3f) { Box(Modifier.size(300.dp, 700.dp)) { PlaceSheetContent(ui, {}, Modifier.align(Alignment.BottomCenter)) } }
        val save = compose.onNodeWithText(Texts.SAVE, useUnmergedTree = true)
        save.assertIsDisplayed().assertWidthIsAtLeast(30.dp)
        val sheet = bounds(compose.onNodeWithTag(PLACE_SHEET_TAG))
        assertTrue("Save ends at ${bounds(save).right}, the window at ${sheet.right}", bounds(save).right <= sheet.right - px(16.dp) + 1f)
    }

    // ---------------------------------------------------------------- P56
    /** P56: Rename (and Save) open with the name selected, so typing replaces it. */
    @Test
    fun renameOpensWithTheNameSelected() {
        var dialog by mutableStateOf<SavedDialogUi>(SavedDialogUi.Rename("Quokka Shop", null, true))
        show { SavedDialog(dialog, {}) }
        assertEquals(TextRange(0, 11), compose.onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
        val kinds = listOf(SavedKind.HOME, SavedKind.WORK, SavedKind.OTHER).map { KindChoiceUi(it, Texts.kindLabel(it), it == SavedKind.OTHER) }
        compose.runOnUiThread { dialog = SavedDialogUi.Save("Millcreek Mall", "5800 Peach Street, Erie", kinds, "Millcreek Mall", null, null, "Save", true) }
        compose.waitForIdle()
        assertEquals(TextRange(0, 14), compose.onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.TextSelectionRange])
    }

    // ---------------------------------------------------------------- P57, F47
    /** P57: a point's empty Name field shows the hint "Name this place", drawn only (never read). */
    @Test
    fun aPointsSaveDialogHintsAtTheName() {
        val kinds = listOf(SavedKind.HOME, SavedKind.WORK, SavedKind.OTHER).map { KindChoiceUi(it, Texts.kindLabel(it), it == SavedKind.OTHER) }
        show { SavedDialog(SavedDialogUi.Save("Dropped pin", "Near East 5th Street, Erie", kinds, "", null, null, "Save", true), {}) }
        compose.onNodeWithTag(FIELD_HINT_TAG, useUnmergedTree = true).assertExists().assertWidthIsAtLeast(80.dp)
        compose.onNodeWithText(Texts.NAME_HINT).assertDoesNotExist()
        compose.onNodeWithText(Texts.NAME_HINT, useUnmergedTree = true).assertDoesNotExist()
        compose.onNode(hasText(Texts.SAVE) and hasClickAction()).assertIsDisplayed()
    }

    /** F47: in a short window at 1.3x the buttons stay on screen and the body scrolls to the last chip. */
    @Test
    fun saveDialogFitsAShortWindow() {
        val kinds = listOf(SavedKind.HOME, SavedKind.WORK, SavedKind.OTHER).map { KindChoiceUi(it, Texts.kindLabel(it), it == SavedKind.HOME) }
        val ui = SavedDialogUi.Save("Erie Zoo", "423 West 38th Street, Erie", kinds, null,
            "This replaces your home: Erie Maritime Museum, 100 East Front Street, Erie.", null, "Replace", true)
        show(fs = 1.3f) { Box(Modifier.size(360.dp, 300.dp)) { SavedDialog(ui, {}) } }
        compose.onNode(hasText("Replace") and hasClickAction()).assertIsDisplayed()
        compose.onNodeWithContentDescription("Other").performScrollTo().assertIsDisplayed()
    }

    // ---------------------------------------------------------------- P58
    /** P58: Remove's confirm button is red, as the menu's Remove (today the accent blue). */
    @Test
    fun removeConfirmIsRed() {
        val store = SavedPlaces(File(work, "saved.json"), SavedRules.bundled(), Executor { it.run() })
        val mall = Place("Millcreek Mall", GeoPoint(42.06875, -80.09813), PlaceSource.SEARCH, "5800 Peach Street, Erie", "mall")
        store.save(mall, SavedKind.OTHER, "Quokka Shop")
        val f = SavedPlacesFeature(store, RecentPlaces(File(work, "recents.json")), MutableStateFlow(InstalledSet(1, true, emptyList(), null)),
            MutableStateFlow(UnitFormatter(UnitRules.bundled(), UnitSystem.IMPERIAL)), scope)
        f.openEdit(store.places.value.first().id)
        f.onEvent(SavedEvent.MenuChosen(EditAction.DELETE))
        val dialog = f.ui.value.dialog!!
        show { SavedDialog(dialog, {}) }
        val image = compose.onNode(hasText(Texts.REMOVE) and hasClickAction()).captureToImage().asAndroidBitmap()
        val c = image.getPixel(px(6.dp).toInt(), image.height / 2)
        assertTrue("Remove pixel r=${red(c)} g=${green(c)} b=${blue(c)}", red(c) > 150 && blue(c) < 90)
    }

    // ---------------------------------------------------------------- P60
    /** P60: a titled search has the pages' header (Back and the title on one row); its rows have icons; a short window keeps one row. */
    @Test
    fun titledSearchHasTheHeaderOfThePages() {
        val ui = SearchUi(mode = SearchMode.SAVE, title = "Set home", useMyPosition = true, chooseOnMap = true, focusField = false)
        var list by mutableStateOf(false)
        show(height = 800) {
            if (list) SavedPlacesScreen(SavedListUi(emptyList(), true, canAdd = true, hint = null, notice = null), {})
            else SearchScreen(ui, {})
        }
        var back = bounds(compose.onNodeWithContentDescription(Texts.BACK))
        var title = bounds(compose.onNodeWithText("Set home"))
        assertTrue("back $back title $title", overlapVertically(back, title) && title.left >= back.right - 1f)
        val field = bounds(compose.onNode(hasSetTextAction()))
        assertTrue("field $field under the title $title", field.top >= title.bottom - 1f)
        assertEquals(2, compose.onAllNodes(hasTestTag(PLAIN_ROW_ICON_TAG), useUnmergedTree = true).fetchSemanticsNodes().size)
        // the saved places screen's "Add a place"
        compose.runOnUiThread { list = true }
        compose.waitForIdle()
        assertEquals(1, compose.onAllNodes(hasTestTag(PLAIN_ROW_ICON_TAG), useUnmergedTree = true).fetchSemanticsNodes().size)
        // a short window: Back, the title and the field on one row
        compose.runOnUiThread { list = false; screenHeight = 400 }
        compose.waitForIdle()
        back = bounds(compose.onNodeWithContentDescription(Texts.BACK))
        title = bounds(compose.onNodeWithText("Set home"))
        val short = bounds(compose.onNode(hasSetTextAction()))
        assertTrue("back $back title $title field $short", overlapVertically(back, title) && overlapVertically(title, short) && short.left >= title.right - 1f)
    }
}
