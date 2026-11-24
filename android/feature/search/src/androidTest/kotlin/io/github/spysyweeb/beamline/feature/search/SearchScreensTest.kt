package io.github.spysyweeb.beamline.feature.search

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import android.os.Build
import android.view.accessibility.AccessibilityNodeInfo
import io.github.spysyweeb.beamline.api.data.InstalledSet
import io.github.spysyweeb.beamline.api.data.PackInfo
import io.github.spysyweeb.beamline.api.data.RoutingSet
import io.github.spysyweeb.beamline.api.map.MapPoi
import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.model.SavedKind
import io.github.spysyweeb.beamline.search.MultiSearchResponse
import io.github.spysyweeb.beamline.search.PackResult
import io.github.spysyweeb.beamline.search.RecentPlaces
import io.github.spysyweeb.beamline.search.SearchHub
import io.github.spysyweeb.beamline.search.SearchResult
import io.github.spysyweeb.beamline.search.awaitStoreWrites
import io.github.spysyweeb.beamline.search.saved.SavedPlaces
import io.github.spysyweeb.beamline.search.saved.SavedRules
import io.github.spysyweeb.beamline.ui.theme.BeamlineTheme
import io.github.spysyweeb.beamline.units.UnitFormatter
import io.github.spysyweeb.beamline.units.UnitRules
import io.github.spysyweeb.beamline.units.UnitSystem
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The search and saved-places screens on the emulator (docs/architecture.md 5.1 S "Done when";
 * docs/architecture-saved-places.md 10.4): PNGs by day and night in the test APK's external files
 * folder (screens/), pulled with tools/round/adb.sh. The engine screenshots ("sheetz", "rest stop",
 * "4319 peach st" near Erie) use the PA search and address files pushed into that folder
 * (pa-search.sqlite, pa-addresses.sqlite) and are skipped without them. Made-up saved places at
 * public points in Erie only (Home = Erie Maritime Museum, Work = a point at the airport, "Quokka
 * Shop" = Millcreek Mall).
 */
@RunWith(AndroidJUnit4::class)
class SearchScreensTest {
    @get:Rule
    val compose = createComposeRule()

    private var content by mutableStateOf<@Composable () -> Unit>({})
    private var night by mutableStateOf(false)
    private var fontScale by mutableStateOf<Float?>(null)

    private val ctx get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val files get() = ctx.getExternalFilesDir(null)!!
    private lateinit var work: File
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    private val museum = Place("Erie Maritime Museum", GeoPoint(42.13646, -80.08718), PlaceSource.SEARCH, "100 East Front Street, Erie", "museum")
    private val mall = Place("Millcreek Mall", GeoPoint(42.06875, -80.09813), PlaceSource.SEARCH, "5800 Peach Street, Erie", "mall")
    private val art = Place("Erie Art Museum", GeoPoint(42.13108, -80.08557), PlaceSource.SEARCH, "20 East 5th Street, Erie", "museum")
    private val zoo = GeoPoint(42.0971, -80.07578)                   // the user, at the Erie Zoo
    private val gps = SearchContext(zoo, NearSource.GPS, 8.0)
    private val units = MutableStateFlow(UnitFormatter(UnitRules.bundled(), UnitSystem.IMPERIAL))

    private val erieTiles = buildSet {
        var lat = 41.9
        while (lat < 42.3) {
            var lon = -80.4
            while (lon < -79.9) { add(RoutingSet.level2TileId(GeoPoint(lat, lon))); lon += 0.1 }
            lat += 0.1
        }
    }

    private fun installed(search: File? = null, addresses: File? = null) = MutableStateFlow(InstalledSet(1, true, listOf(PackInfo(
        packId = "us-pa", name = "Pennsylvania", state = "PA", osmSnapshot = "2026-11-14", bounds = Bounds(-80.52109, 39.71977, -74.68967, 42.51613),
        attribution = "OpenStreetMap", folder = "packs/us-pa", mapFile = "packs/us-pa/map.pmtiles", mapMinZoom = 0, mapMaxZoom = 14,
        searchFile = search?.absolutePath, addressesFile = addresses?.absolutePath, addressesSkipped = addresses == null, searchProblem = null,
        searchRankingSha256 = "r", inRoutingSet = true, verified = true, bytesOnDisk = 0,
    )), RoutingSet("valhalla.json", "g", "3.9.1", listOf("PA"), erieTiles)))

    /** A searcher that answers nothing (for the screens that show no engine rows). */
    private object Quiet : PackSearcher {
        override fun prepare(set: InstalledSet) {}
        override fun hasSearchData(set: InstalledSet) = true
        override fun search(set: InstalledSet, text: String, near: GeoPoint?, limit: Int) =
            MultiSearchResponse(text, emptyList(), listOf("us-pa"), true, null, false, 0.0)
    }

    /** A searcher that answers one row: the Millcreek Mall (for the refused change of location). */
    private object MallOnly : PackSearcher {
        override fun prepare(set: InstalledSet) {}
        override fun hasSearchData(set: InstalledSet) = true
        override fun search(set: InstalledSet, text: String, near: GeoPoint?, limit: Int) = MultiSearchResponse(
            text, listOf(PackResult("us-pa", SearchResult(12, SearchResult.Kind.POI, "Millcreek Mall", "5800 Peach Street, Erie", "mall", null,
                42.06875, -80.09813, null, null, null, 1.0, "t", null))), listOf("us-pa"), true, null, false, 0.0,
        )
    }

    private fun store(name: String) = SavedPlaces(File(work, "$name.json"), SavedRules.bundled(), Executor { it.run() })

    @Before
    fun setUp() {
        work = File(ctx.cacheDir, "screens-work").apply { deleteRecursively(); mkdirs() }
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale ?: d.fontScale)) {
                BeamlineTheme(night = night) {
                    Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                        Box(Modifier.size(360.dp, 640.dp).testTag(TAG)) { content() }
                    }
                }
            }
        }
    }

    @After
    fun tearDown() {
        scope.cancel()
        awaitStoreWrites()
    }

    private fun shot(name: String, n: Boolean, fs: Float? = null, c: @Composable () -> Unit) {
        compose.runOnUiThread {
            content = c
            night = n
            fontScale = fs
        }
        compose.waitForIdle()
        Thread.sleep(150)
        compose.waitForIdle()
        save(compose, "$name-${if (n) "night" else "day"}")
    }

    private fun bothThemes(name: String, fs: Float? = null, c: @Composable () -> Unit) {
        shot(name, false, fs, c)
        shot(name, true, fs, c)
    }

    /** A plain map-coloured background with [overlay] on it (the pill, the sheet, the pick bar). */
    @Composable
    private fun OnMap(overlay: @Composable BoxScope.() -> Unit) {
        Box(Modifier.fillMaxSize().background(if (night) Color(0xFF1C242C) else Color(0xFFE7E5DF))) { overlay() }
    }

    // ---------------------------------------------------------------- the pill and the START screens
    @Test
    fun pillAndStartScreens() {
        bothThemes("pill") { OnMap { Box(Modifier.padding(8.dp).fillMaxWidth()) { SearchBar(onClick = {}, modifier = Modifier.fillMaxWidth()) } } }

        val recents = RecentPlaces(File(work, "recents.json"))
        recents.add(art.copy(searchId = 3, packId = "us-pa"))
        recents.add(Place("Erie Zoo", zoo, PlaceSource.SEARCH, "423 West 38th Street, Erie", "zoo", 4, "us-pa"))
        awaitStoreWrites()
        // empty slots
        val empty = store("empty")
        val f1 = SearchFeature(installed(), recents, empty, units, scope, Dispatchers.Unconfined, Quiet)
        f1.open(SearchMode.GO_TO, gps)
        assertEquals(listOf("Set home", "Set work"), f1.ui.value.slots.map { it.title })
        bothThemes("search-start-empty") { SearchScreen(f1.ui.value, {}) }
        // set slots, a saved place, recents and shortcuts
        val s = store("set")
        s.save(museum, SavedKind.HOME)
        s.save(Place("Point on the map", GeoPoint(42.08418, -80.17321), PlaceSource.MAP_PIN), SavedKind.WORK)
        s.save(mall, SavedKind.OTHER, "Quokka Shop")
        val f2 = SearchFeature(installed(), recents, s, units, scope, Dispatchers.Unconfined, Quiet)
        f2.open(SearchMode.GO_TO, gps)
        assertEquals("Home", f2.ui.value.slots[0].title)
        bothThemes("search-start-set") { SearchScreen(f2.ui.value, {}) }
        // the BROKEN notice
        File(work, "broken.json").writeText("{")
        val broken = store("broken")
        val f3 = SearchFeature(installed(), recents, broken, units, scope, Dispatchers.Unconfined, Quiet)
        f3.open(SearchMode.GO_TO, gps)
        bothThemes("search-start-broken") { SearchScreen(f3.ui.value, {}) }
        // SAVE START with "Use my position"
        f2.openToSave(SaveTarget.Work, gps)
        assertTrue(f2.ui.value.useMyPosition)
        bothThemes("search-save-start") { SearchScreen(f2.ui.value, {}) }
        // ADD_STOP while driving: rows at the top, no keyboard (SP4)
        f2.open(SearchMode.ADD_STOP, gps, driving = true)
        bothThemes("search-addstop-driving") { SearchScreen(f2.ui.value, {}) }
        // a refused pick in SAVE mode says why: "Change location" of an other place onto the mall, saved as "Quokka Shop"
        val moms = (s.save(art, SavedKind.OTHER, "Mom's") as io.github.spysyweeb.beamline.search.saved.SaveResult.Done).place!!
        val f4 = SearchFeature(installed(), recents, s, units, scope, Dispatchers.Unconfined, MallOnly)
        f4.openToSave(SaveTarget.Move(moms.id), gps)
        f4.onEvent(SearchEvent.QueryChanged("millcreek mall"))
        waitFor(f4) { it.phase == SearchPhase.RESULTS }
        f4.onEvent(SearchEvent.RowTapped(f4.ui.value.results[0].key))
        assertEquals("This place is already saved as Quokka Shop.", f4.ui.value.message)
        bothThemes("search-move-refused") { SearchScreen(f4.ui.value, {}) }
        // nothing installed (first start): NO_SEARCH_DATA with "Add a state", the saved rows stay
        val f5 = SearchFeature(MutableStateFlow(InstalledSet(9, true, emptyList(), null)), recents, s, units, scope, Dispatchers.Unconfined, NoData)
        f5.open(SearchMode.GO_TO, gps)
        waitFor(f5) { it.phase == SearchPhase.NO_SEARCH_DATA }
        assertEquals(Texts.NO_SEARCH_DATA, f5.ui.value.message)
        bothThemes("search-no-data") { SearchScreen(f5.ui.value, {}) }
    }

    /** A searcher with nothing to search (nothing installed). */
    private object NoData : PackSearcher {
        override fun prepare(set: InstalledSet) {}
        override fun hasSearchData(set: InstalledSet) = false
        override fun search(set: InstalledSet, text: String, near: GeoPoint?, limit: Int) =
            MultiSearchResponse(text, emptyList(), emptyList(), true, null, null, 0.0)
    }

    // ---------------------------------------------------------------- the engine on the PA files
    private fun waitFor(f: SearchFeature, ok: (SearchUi) -> Boolean) {
        val t0 = System.currentTimeMillis()
        while (!ok(f.ui.value) && System.currentTimeMillis() - t0 < 20_000) Thread.sleep(50)
    }

    @Test
    fun engineResultsNearErie() {
        val search = File(files, "pa-search.sqlite")
        val addresses = File(files, "pa-addresses.sqlite")
        assumeTrue("push pa-search.sqlite into ${files.path}", search.isFile)
        val set = installed(search, addresses.takeIf { it.isFile })
        val recents = RecentPlaces(File(work, "recents2.json"))
        val s = store("engine")
        s.save(museum, SavedKind.HOME)
        // decision 6.17 on the emulator (indicative only: shared PC; the files were just pushed, so the
        // OS page cache may hold them, but each hub opens new SQLite connections with empty caches of
        // their own): 6 rounds, each a new hub with the warm-up and one without, in alternating order;
        // per hub the time to make it (the first one computes the fingerprint of the bundled
        // spellings), to open and check the packs (and warm them up), then the first two queries
        fun ms(a: Long, b: Long) = (b - a) / 1e6
        val runs = ArrayList<String>()
        val table = HashMap<String, ArrayList<Double>>()
        fun note(key: String, v: Double) { table.getOrPut(key) { ArrayList() }.add(v) }
        for (round in 1..6) {
            val order = if (round % 2 == 1) listOf(true, false) else listOf(false, true)
            for (warm in order) {
                val t0 = System.nanoTime()
                val h = SearchHub(warmUp = warm)
                val t1 = System.nanoTime()
                h.prepare(set.value)
                val t2 = System.nanoTime()
                val first = h.search(set.value, "sheetz", zoo, 10)
                val t3 = System.nanoTime()
                h.search(set.value, "4319 peach st", zoo, 10)
                val t4 = System.nanoTime()
                assertEquals(emptyMap<String, String>(), h.problems)   // P's v3 files pass the fingerprint check at open
                assertEquals(10, first.results.size)
                h.close()
                val w = if (warm) "warm" else "nowarm"
                runs.add("round=$round warmup=$warm hub_made_ms=${"%.1f".format(ms(t0, t1))} prepare_ms=${"%.1f".format(ms(t1, t2))} " +
                    "sheetz_ms=${"%.1f".format(ms(t2, t3))} peach_ms=${"%.1f".format(ms(t3, t4))}")
                note("$w.prepare", ms(t1, t2)); note("$w.sheetz", ms(t2, t3)); note("$w.peach", ms(t3, t4))
                note("$w.prepare+sheetz", ms(t1, t3))
            }
        }
        fun summary(key: String): String {
            val v = table.getValue(key).sorted()
            val median = if (v.size % 2 == 1) v[v.size / 2] else (v[v.size / 2 - 1] + v[v.size / 2]) / 2
            return "$key n=${v.size} min=${"%.1f".format(v.first())} median=${"%.1f".format(median)} max=${"%.1f".format(v.last())}"
        }
        File(screensDir(), "timing.txt").writeText(
            "device=${Build.MODEL} api=${Build.VERSION.SDK_INT} addresses=${addresses.isFile}\n" + runs.joinToString("\n") + "\n" +
                listOf("warm", "nowarm").flatMap { w -> listOf("prepare", "sheetz", "peach", "prepare+sheetz").map { summary("$w.$it") } }.joinToString("\n") + "\n",
        )
        val hub = SearchHub()
        hub.prepare(set.value)
        assertEquals(emptyMap<String, String>(), hub.problems)
        val f = SearchFeature(set, recents, s, units, scope, Dispatchers.Unconfined, HubSearcher { hub })
        try {
            for ((q, name) in listOf("sheetz" to "search-sheetz", "rest stop" to "search-rest-stop", "4319 peach st" to "search-4319-peach-st", "home" to "search-home")) {
                f.open(SearchMode.GO_TO, gps)
                f.onEvent(SearchEvent.QueryChanged(q))
                waitFor(f) { it.phase == SearchPhase.RESULTS || it.phase == SearchPhase.NO_RESULTS || it.phase == SearchPhase.NO_SEARCH_DATA }
                assertEquals(q, SearchPhase.RESULTS, f.ui.value.phase)
                bothThemes(name) { SearchScreen(f.ui.value, {}) }
            }
            // the sheets: a routable result and a place outside the maps
            f.open(SearchMode.GO_TO, gps)
            f.onEvent(SearchEvent.QueryChanged("sheetz"))
            waitFor(f) { it.phase == SearchPhase.RESULTS }
            val picked = f.onEvent(SearchEvent.RowTapped(f.ui.value.results[0].key)) as SearchOutput.Picked
            val sheet = f.placeSheet(picked.place, gps, tripActive = false)
            bothThemes("sheet-routable") { OnMap { Box(Modifier.align(Alignment.BottomCenter)) { PlaceSheet(sheet, {}) } } }
            val outside = f.placeSheet(Place("Point State Park", GeoPoint(40.4416, -80.0128), PlaceSource.SEARCH, "Pittsburgh", "park"), gps, tripActive = false)
            bothThemes("sheet-outside") { OnMap { Box(Modifier.align(Alignment.BottomCenter)) { PlaceSheet(outside, {}) } } }
            val saved = f.placeSheet(s.home()!!.toPlace(), gps, tripActive = true)
            bothThemes("sheet-saved-home-trip") { OnMap { Box(Modifier.align(Alignment.BottomCenter)) { PlaceSheet(saved, {}) } } }
            val savedAs = f.placeSheet(museum, gps, tripActive = false)
            bothThemes("sheet-saved-as") { OnMap { Box(Modifier.align(Alignment.BottomCenter)) { PlaceSheet(savedAs, {}) } } }
        } finally {
            hub.close()
        }
        // NO_SEARCH_DATA with the state installed: the real hub refuses P's PA files when it expects
        // another normaliser's fingerprint; the line says the maps cannot be searched, not "add a state"
        val other = SearchHub(expectedFingerprint = "0".repeat(64))
        try {
            val g = SearchFeature(set, recents, s, units, scope, Dispatchers.Unconfined, HubSearcher { other })
            g.open(SearchMode.GO_TO, gps)
            waitFor(g) { it.phase == SearchPhase.NO_SEARCH_DATA }
            assertEquals(SearchPhase.NO_SEARCH_DATA, g.ui.value.phase)
            assertEquals(Texts.SEARCH_UNUSABLE, g.ui.value.message)
            assertEquals(setOf("us-pa"), other.problems.keys)
            bothThemes("search-not-searchable") { SearchScreen(g.ui.value, {}) }
        } finally {
            other.close()
        }
    }

    /**
     * Decision 6.17 with a cold page cache: one hub in a new process, after the scratch script
     * data/r3-s/device-cold-timing.sh has dropped the emulator's page cache (root, emulator only);
     * the script passes `-e warm true|false`. Appends one line to screens/timing-cold.txt. Skipped
     * without the argument (a plain run of the class).
     */
    @Test
    fun coldOpenTiming() {
        val warmArg = InstrumentationRegistry.getArguments().getString("warm")
        assumeTrue("run by data/r3-s/device-cold-timing.sh with -e warm true|false", warmArg == "true" || warmArg == "false")
        val search = File(files, "pa-search.sqlite")
        val addresses = File(files, "pa-addresses.sqlite")
        assumeTrue("push pa-search.sqlite into ${files.path}", search.isFile)
        val set = installed(search, addresses.takeIf { it.isFile }).value
        val warm = warmArg == "true"
        val t0 = System.nanoTime()
        val h = SearchHub(warmUp = warm)
        val t1 = System.nanoTime()
        h.prepare(set)
        val t2 = System.nanoTime()
        val first = h.search(set, "sheetz", zoo, 10)
        val t3 = System.nanoTime()
        h.search(set, "4319 peach st", zoo, 10)
        val t4 = System.nanoTime()
        assertEquals(emptyMap<String, String>(), h.problems)
        assertEquals(10, first.results.size)
        h.close()
        fun ms(a: Long, b: Long) = "%.1f".format((b - a) / 1e6)
        File(screensDir(), "timing-cold.txt").appendText(
            "warmup=$warm hub_made_ms=${ms(t0, t1)} prepare_ms=${ms(t1, t2)} sheetz_ms=${ms(t2, t3)} peach_ms=${ms(t3, t4)} prepare_plus_sheetz_ms=${ms(t1, t3)}\n",
        )
    }

    // ---------------------------------------------------------------- the saved places screen, dialogs, pick
    @Test
    fun savedPlacesScreensAndDialogs() {
        val recents = RecentPlaces(File(work, "recents3.json"))
        val s = store("list")
        s.save(museum, SavedKind.HOME)
        s.save(mall, SavedKind.OTHER, "Quokka Shop")
        s.save(art, SavedKind.OTHER, "Mom's")
        s.save(Place("Point State Park", GeoPoint(40.4416, -80.0128), PlaceSource.SEARCH, "Pittsburgh", "park"), SavedKind.OTHER, "Point park")
        val f = SavedPlacesFeature(s, recents, installed(), units, scope)
        f.openList(null, gps)
        bothThemes("saved-list-full") { SavedPlacesScreen(f.ui.value.list!!, {}) }
        f.openList(SearchMode.ADD_STOP, gps)
        bothThemes("saved-list-pick-only") { SavedPlacesScreen(f.ui.value.list!!, {}) }
        // a file from a newer app: the list shows only the notice (SP53)
        File(work, "newer.json").writeText("""{"format":"beamline-saved-places/2","places":[]}""")
        val nf = SavedPlacesFeature(store("newer"), recents, installed(), units, scope)
        nf.openList(null, gps)
        assertEquals(emptyList<SavedRowUi>(), nf.ui.value.list!!.rows)
        bothThemes("saved-list-newer") { SavedPlacesScreen(nf.ui.value.list!!, {}) }
        // a 40-character name at font scale 1.0 and 1.5 (SP6)
        val long = store("long")
        long.save(museum, SavedKind.HOME)
        val forty = "Quokka Shop on the Peach Street side 123"
        assertEquals(40, forty.length)
        assertTrue(long.save(mall, SavedKind.OTHER, forty) is io.github.spysyweeb.beamline.search.saved.SaveResult.Done)
        val lf = SavedPlacesFeature(long, recents, installed(), units, scope)
        lf.openList(null, gps)
        bothThemes("saved-list-long-name-1.0", 1.0f) { SavedPlacesScreen(lf.ui.value.list!!, {}) }
        bothThemes("saved-list-long-name-1.5", 1.5f) { SavedPlacesScreen(lf.ui.value.list!!, {}) }
        // the save dialog: Other, then Home with the replace line
        f.openSave(Place("Erie Zoo", zoo, PlaceSource.SEARCH, "423 West 38th Street, Erie", "zoo"))
        bothThemes("dialog-save-other") { SavedDialog(f.ui.value.dialog!!, {}) }
        f.onEvent(SavedEvent.KindChosen(SavedKind.HOME))
        bothThemes("dialog-save-home-replace") { SavedDialog(f.ui.value.dialog!!, {}) }
        f.onEvent(SavedEvent.DialogDismiss)
        // the Edit menus and a confirm
        f.openEdit(s.home()!!.id)
        bothThemes("dialog-menu-home") { SavedDialog(f.ui.value.dialog!!, {}) }
        val q = s.places.value.first { it.name == "Quokka Shop" }
        f.openEdit(q.id)
        bothThemes("dialog-menu-other") { SavedDialog(f.ui.value.dialog!!, {}) }
        f.onEvent(SavedEvent.MenuChosen(EditAction.MAKE_HOME))
        bothThemes("dialog-confirm-make-home") { SavedDialog(f.ui.value.dialog!!, {}) }
        f.onEvent(SavedEvent.DialogDismiss)
        f.openEdit(q.id)
        f.onEvent(SavedEvent.MenuChosen(EditAction.RENAME))
        f.onEvent(SavedEvent.NameChanged("mom's"))
        bothThemes("dialog-rename-taken") { SavedDialog(f.ui.value.dialog!!, {}) }
        f.onEvent(SavedEvent.DialogDismiss)
        // the map pick: before a tap, after a tap at zoom 17 and at zoom 14
        f.startMapPick(SaveTarget.Work)
        bothThemes("pick-before") { OnMap { MapPickBar(f.ui.value.mapPick!!, {}) } }
        f.onMapTap(GeoPoint(42.08418, -80.17321), null, 17.0)
        bothThemes("pick-z17") { OnMap { MapPickBar(f.ui.value.mapPick!!, {}) } }
        f.onMapTap(GeoPoint(42.08418, -80.17321), null, 14.0)
        bothThemes("pick-z14") { OnMap { MapPickBar(f.ui.value.mapPick!!, {}) } }
        f.onMapTap(GeoPoint(42.13646, -80.08718), MapPoi("Erie Maritime Museum", "museum", GeoPoint(42.13646, -80.08718)), 12.0)
        bothThemes("pick-poi-z12") { OnMap { MapPickBar(f.ui.value.mapPick!!, {}) } }
    }

    // ---------------------------------------------------------------- SP5: what a screen reader gets
    /** One line per node of the window's accessibility tree (what TalkBack reads), written to screens/a11y-<name>.txt. */
    /** [expect]: words the screen on show has, so a tree cached from the screen before is never read. */
    private fun a11yTree(name: String, expect: String): List<String> {
        val ua = InstrumentationRegistry.getInstrumentation().uiAutomation
        var lines: List<String> = emptyList()
        val t0 = System.currentTimeMillis()
        while (System.currentTimeMillis() - t0 < 8_000) {   // the first query after a change can come too early
            runCatching { ua.waitForIdle(200, 2_000) }
            if (Build.VERSION.SDK_INT >= 34) ua.clearCache()
            val root = ua.rootInActiveWindow
            if (root != null) {
                lines = dumpTree(root)
                if (lines.any { expect in it }) break
            }
            Thread.sleep(250)
        }
        assertTrue("$name: no accessibility tree with '$expect': $lines", lines.any { expect in it })
        File(screensDir(), "a11y-$name.txt").writeText(lines.joinToString("\n") + "\n")
        return lines
    }

    private fun dumpTree(root: AccessibilityNodeInfo): List<String> {
        val lines = ArrayList<String>()
        fun walk(n: AccessibilityNodeInfo, depth: Int) {
            val b = android.graphics.Rect().also { n.getBoundsInScreen(it) }
            val sb = StringBuilder("  ".repeat(depth)).append(n.className?.toString()?.substringAfterLast('.'))
            if (n.text != null) sb.append(" text=\"").append(n.text).append('"')
            if (n.contentDescription != null) sb.append(" desc=\"").append(n.contentDescription).append('"')
            if (Build.VERSION.SDK_INT >= 30 && n.stateDescription != null) sb.append(" state=\"").append(n.stateDescription).append('"')
            if (n.isClickable) sb.append(" clickable")
            if (n.isCheckable) sb.append(" checkable checked=").append(n.isChecked)
            if (n.isSelected) sb.append(" selected")
            if (n.liveRegion == android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE) sb.append(" live=polite")
            if (Build.VERSION.SDK_INT >= 28 && n.isHeading) sb.append(" heading")
            sb.append(' ').append(b.toShortString())
            lines.add(sb.toString())
            for (i in 0 until n.childCount) n.getChild(i)?.let { walk(it, depth + 1) }
        }
        walk(root, 0)
        return lines
    }

    private fun show(c: @Composable () -> Unit) {
        compose.runOnUiThread { content = c; night = false; fontScale = null }
        compose.waitForIdle()
        Thread.sleep(300)
        compose.waitForIdle()
    }

    /** SP5 item 2: every enabled tap target of the screen on show is at least 48 by 48 dp (its touch bounds). */
    private fun assertTargets(name: String) {
        val nodes = compose.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        assertTrue("$name: no tap targets", nodes.isNotEmpty())
        for (n in nodes) {
            if (n.config.contains(SemanticsProperties.Disabled)) continue
            val min = with(n.layoutInfo.density) { 48.dp.toPx() } - 1f
            // the target's own size, not clipped by a scrolling row (a chip scrolled off is still 48 dp)
            val w = maxOf(n.size.width.toFloat(), n.touchBoundsInRoot.width)
            val h = maxOf(n.size.height.toFloat(), n.touchBoundsInRoot.height)
            val label = n.config.getOrNull(SemanticsProperties.ContentDescription)?.joinToString()
                ?: n.config.getOrNull(SemanticsProperties.Text)?.joinToString() ?: "node ${n.id}"
            assertTrue("$name: '$label' is $w x $h px, below 48 dp", w >= min && h >= min)
        }
    }

    private class A11yNode(val depth: Int, val line: String) { val children = ArrayList<A11yNode>() }

    private fun attr(line: String, key: String) = Regex("""$key="([^"]*)"""").find(line)?.groupValues?.get(1)

    /**
     * Every tap target of a dump (a clickable node) with what a screen reader reads for it: its own
     * description, else its own text, else those of the nodes under it that are not targets
     * themselves, in order (Compose puts a target's description and role on generated child
     * nodes). Second: the target's line and its children's lines (states).
     */
    private fun stops(lines: List<String>): List<Pair<String, String>> {
        val roots = ArrayList<A11yNode>()
        val stack = ArrayDeque<A11yNode>()
        for (l in lines) {
            val n = A11yNode((l.length - l.trimStart().length) / 2, l)
            while (stack.isNotEmpty() && stack.last().depth >= n.depth) stack.removeLast()
            if (stack.isEmpty()) roots.add(n) else stack.last().children.add(n)
            stack.addLast(n)
        }
        fun words(n: A11yNode): List<String> =
            listOfNotNull(attr(n.line, "desc")?.ifEmpty { null } ?: attr(n.line, "text")?.ifEmpty { null }) +
                n.children.filter { " clickable" !in it.line }.flatMap { words(it) }
        val out = ArrayList<Pair<String, String>>()
        fun walk(n: A11yNode) {
            if (" clickable" in n.line) out.add(words(n).joinToString(", ") to (n.line + n.children.joinToString("") { " | " + it.line.trim() }))
            n.children.forEach { walk(it) }
        }
        roots.forEach { walk(it) }
        return out
    }

    /** Every tap target of [lines] has a name (SP5); returns the stops. */
    private fun named(name: String, lines: List<String>): List<Pair<String, String>> {
        val s = stops(lines)
        assertTrue("$name: a tap target without a name: ${s.filter { it.first.isBlank() }}", s.none { it.first.isBlank() })
        File(screensDir(), "a11y-$name-stops.txt").writeText(s.joinToString("\n") { it.first } + "\n")
        return s
    }

    @Test
    fun accessibilityLabelsStatesAndTargets() {
        val recents = RecentPlaces(File(work, "recents5.json"))
        recents.add(art.copy(searchId = 3, packId = "us-pa"))
        awaitStoreWrites()
        val s = store("a11y")
        s.save(museum, SavedKind.HOME)
        s.save(mall, SavedKind.OTHER, "Quokka Shop")
        // (1) the pill: its label is its text, read once
        show { OnMap { Box(Modifier.padding(8.dp).fillMaxWidth()) { SearchBar(onClick = {}, modifier = Modifier.fillMaxWidth()) } } }
        val pill = named("pill", a11yTree("pill", "Search places or addresses"))
        assertEquals(listOf("Search places or addresses"), pill.map { it.first })
        assertTargets("pill")
        // (2) the search START: one stop per row reading its texts in order; every target 48 dp
        val sf = SearchFeature(installed(), recents, s, units, scope, Dispatchers.Unconfined, Quiet)
        sf.open(SearchMode.GO_TO, gps)
        show { SearchScreen(sf.ui.value, {}) }
        val start = named("search-start", a11yTree("search-start", "Saved places, 2 places"))
        val said = start.map { it.first }
        assertTrue(said.toString(), said.any { Regex("""Home, Erie Maritime Museum, 100 East Front Street, Erie, [0-9.]+ mi""").matches(it) })
        assertTrue(said.toString(), "Set work" in said && "Saved places, 2 places" in said && "Gas" in said && "Remove from recents" in said)
        assertTrue(said.toString(), "Search places or addresses" in said)   // the field: its label once, the hint not again
        assertTargets("search-start")
        // (2) the saved places screen: "Edit {name}" stops; (3) the switch with its state
        val lf = SavedPlacesFeature(s, recents, installed(), units, scope)
        lf.openList(null, gps)
        show { SavedPlacesScreen(lf.ui.value.list!!, {}) }
        val list = named("saved-list", a11yTree("saved-list", "Edit Quokka Shop"))
        assertTrue(list.map { it.first }.toString(), list.any { it.first == "Edit Home" } && list.any { it.first == "Edit Quokka Shop" })
        // one stop for the switch: its words, checkable, on (no second, unlabelled switch)
        val switch = list.filter { "checkable" in it.second }
        assertTrue(switch.toString(), switch.size == 1 && switch[0].first == "Show on the map" && "checked=true" in switch[0].second)
        assertTargets("saved-list")
        // (3) the save dialog's chips are radio buttons with a selected state
        lf.openSave(Place("Erie Zoo", zoo, PlaceSource.SEARCH, "423 West 38th Street, Erie", "zoo"))
        show { SavedDialog(lf.ui.value.dialog!!, {}) }
        val dialogLines = a11yTree("save-dialog", "Save place")
        val dialog = named("save-dialog", dialogLines)
        // the chosen chip is checked and has no click of its own (Compose drops it on a selected radio button);
        // the others are checkable, not checked, and clickable
        val o = dialogLines.indexOfFirst { "desc=\"Other\"" in it }
        assertTrue(dialogLines.joinToString("\n"), o > 0 && "checkable checked=true" in dialogLines[o - 1] && "RadioButton" in dialogLines[o + 2])
        val home = dialog.first { it.first == "Home" }.second
        assertTrue(home, "RadioButton" in home && "checkable checked=false" in home && "clickable" in home)
        assertTargets("save-dialog")
        // (4) a problem line is a polite live region (the pick bar's "Zoom in ...")
        lf.onEvent(SavedEvent.DialogDismiss)
        lf.startMapPick(SaveTarget.Home)
        lf.onMapTap(GeoPoint(42.13646, -80.08718), null, 14.0)
        show { OnMap { MapPickBar(lf.ui.value.mapPick!!, {}) } }
        val pick = a11yTree("pick-z14", "Zoom in to choose the exact spot.")
        assertTrue(pick.joinToString("\n"), pick.any { "Zoom in to choose the exact spot." in it && "live=polite" in it })
        named("pick-z14", pick)
        assertTargets("pick-z14")
        // the Edit menu and the sheet: named targets of 48 dp
        lf.onEvent(SavedEvent.PickCancel)
        lf.openEdit(s.places.value.first { it.name == "Quokka Shop" }.id)
        show { SavedDialog(lf.ui.value.dialog!!, {}) }
        val menu = named("menu", a11yTree("menu", "Make work"))
        assertEquals(listOf("Rename", "Change location", "Make home", "Make work", "Remove", "Cancel"), menu.map { it.first })
        assertTargets("menu")
        val sheet = sf.placeSheet(s.home()!!.toPlace(), gps, tripActive = true)
        show { OnMap { Box(Modifier.align(Alignment.BottomCenter)) { PlaceSheet(sheet, {}) } } }
        val sh = named("sheet", a11yTree("sheet", "Add stop"))
        assertTrue(sh.toString(), sh.map { it.first }.containsAll(listOf("Close", "Add stop", "Directions", "Edit")))
        assertTargets("sheet")
    }

    // ---------------------------------------------------------------- PV8: the keyboard does not learn
    private fun shell(cmd: String): String {
        val pfd = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(cmd)
        return android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd).use { it.readBytes().toString(Charsets.UTF_8) }
    }

    /** The imeOptions and inputType of the editor the keyboard is bound to now (dumpsys input_method). */
    private var lastDump = ""

    private fun editorFlags(): Pair<Int, Int>? {
        val d = shell("dumpsys input_method")
        lastDump = d
        // the editor bound now: the line after "curEditorInfo:" (the rest of the dump is history)
        val m = Regex("""curEditorInfo:\s*\n\s*inputType=0x([0-9a-fA-F]+) imeOptions=0x([0-9a-fA-F]+)""").find(d) ?: return null
        return m.groupValues[2].toLong(16).toInt() to m.groupValues[1].toLong(16).toInt()
    }

    private fun keyboardShot(name: String) {
        val bmp = InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot() ?: return
        FileOutputStream(File(screensDir(), "$name.png")).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    @Test
    fun keyboardDoesNotLearnOnTheThreeFields() {
        val before = shell("settings get secure show_ime_with_hard_keyboard").trim()
        shell("settings put secure show_ime_with_hard_keyboard 1")
        try {
            val s = store("kbd")
            s.save(mall, SavedKind.OTHER, "Quokka Shop")
            val recents = RecentPlaces(File(work, "recents4.json"))
            val sf = SearchFeature(installed(), recents, s, units, scope, Dispatchers.Unconfined, Quiet)
            sf.open(SearchMode.GO_TO, gps)
            val lf = SavedPlacesFeature(s, recents, installed(), units, scope)
            val fields = ArrayList<Pair<String, @Composable () -> Unit>>()
            fields.add("keyboard-search" to { SearchScreen(sf.ui.value, {}) })
            lf.openSave(Place("Erie Zoo", zoo, PlaceSource.SEARCH, "423 West 38th Street, Erie", "zoo"))
            val saveUi = lf.ui.value.dialog!!
            fields.add("keyboard-save-name" to { SavedDialog(saveUi, {}) })
            lf.openEdit(s.places.value.first().id)
            lf.onEvent(SavedEvent.MenuChosen(EditAction.RENAME))
            val renameUi = lf.ui.value.dialog!!
            fields.add("keyboard-rename" to { SavedDialog(renameUi, {}) })
            for ((name, c) in fields) {
                compose.runOnUiThread { content = {}; night = false; fontScale = null }
                compose.waitForIdle()
                compose.runOnUiThread { content = c }
                compose.waitForIdle()
                // a tap on the field, as a user would (it asks for the soft keyboard), then time to bind
                runCatching { compose.onAllNodes(hasSetTextAction()).onFirst().performClick() }
                compose.waitForIdle()
                Thread.sleep(2_000)
                val flags = editorFlags()
                val shown = Regex("""mInputShown=(true|false)""").find(lastDump)?.groupValues?.get(1) ?: "?"
                val ime = Regex("""mCurMethodId=(\S+)""").find(lastDump)?.groupValues?.get(1) ?: "?"
                File(screensDir(), "$name-dumpsys.txt").writeText(lastDump)
                keyboardShot(name)
                assertTrue("$name: no editor bound", flags != null)
                val (imeOptions, type) = flags!!
                assertTrue("$name: IME_FLAG_NO_PERSONALIZED_LEARNING missing (imeOptions=0x${Integer.toHexString(imeOptions)})",
                    imeOptions and android.view.inputmethod.EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING != 0)
                assertTrue("$name: suggestions not off (inputType=0x${Integer.toHexString(type)})",
                    type and android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0)
                assertTrue("$name: autocorrect on", type and android.text.InputType.TYPE_TEXT_FLAG_AUTO_CORRECT == 0)
                File(screensDir(), "$name.txt").writeText(
                    "imeOptions=0x${Integer.toHexString(imeOptions)} inputType=0x${Integer.toHexString(type)} keyboard=$ime shown=$shown\n",
                )
            }
        } finally {
            shell("settings put secure show_ime_with_hard_keyboard ${before.ifEmpty { "0" }}")
        }
    }

    companion object {
        const val TAG = "shot"

        fun screensDir(): File =
            File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), "screens").apply { mkdirs() }

        fun save(rule: ComposeContentTestRule, name: String): File {
            rule.waitForIdle()
            val bitmap = rule.onNodeWithTag(TAG).captureToImage().asAndroidBitmap()
            val file = File(screensDir(), "$name.png")
            FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return file
        }
    }

    @Suppress("unused")
    @Composable
    private fun Unused() = Column {}
}
