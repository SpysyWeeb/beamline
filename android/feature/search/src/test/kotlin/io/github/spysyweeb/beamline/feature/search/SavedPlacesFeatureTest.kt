package io.github.spysyweeb.beamline.feature.search

import io.github.spysyweeb.beamline.api.data.InstalledSet
import io.github.spysyweeb.beamline.api.map.MapPoi
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.model.SavedKind
import io.github.spysyweeb.beamline.api.model.SavedPlace
import io.github.spysyweeb.beamline.feature.search.SearchFeatureTest.Companion.AIRPORT
import io.github.spysyweeb.beamline.feature.search.SearchFeatureTest.Companion.MALL
import io.github.spysyweeb.beamline.feature.search.SearchFeatureTest.Companion.MUSEUM
import io.github.spysyweeb.beamline.feature.search.SearchFeatureTest.Companion.PITTSBURGH
import io.github.spysyweeb.beamline.feature.search.SearchFeatureTest.Companion.ZOO
import io.github.spysyweeb.beamline.feature.search.SearchFeatureTest.Companion.gps
import io.github.spysyweeb.beamline.feature.search.SearchFeatureTest.Companion.mallPlace
import io.github.spysyweeb.beamline.feature.search.SearchFeatureTest.Companion.museumPlace
import io.github.spysyweeb.beamline.feature.search.SearchFeatureTest.Companion.units
import io.github.spysyweeb.beamline.feature.search.SearchFeatureTest.Companion.withMaps
import io.github.spysyweeb.beamline.search.RecentPlaces
import io.github.spysyweeb.beamline.search.awaitStoreWrites
import io.github.spysyweeb.beamline.search.saved.SaveResult
import io.github.spysyweeb.beamline.search.saved.SavedPlaces
import io.github.spysyweeb.beamline.search.saved.SavedRules
import java.io.File
import java.util.concurrent.Executor
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** SavedPlacesFeature (docs/architecture-saved-places.md 2.2-2.6 and 10.1 :feature:search items 5 and 7). Made-up places. */
@OptIn(ExperimentalCoroutinesApi::class)
class SavedPlacesFeatureTest {
    @TempDir
    lateinit var dir: File

    private val dispatcher = StandardTestDispatcher()
    private val scope = TestScope(dispatcher)
    private val installed = MutableStateFlow(withMaps())
    private lateinit var recents: RecentPlaces
    private lateinit var store: SavedPlaces
    private lateinit var f: SavedPlacesFeature

    @BeforeEach
    fun setUp() {
        recents = RecentPlaces(File(dir, "recents.json"))
        store = SavedPlaces(File(dir, "saved-places.json"), SavedRules.bundled(), Executor { it.run() })
        f = SavedPlacesFeature(store, recents, installed, MutableStateFlow(units), scope)
    }

    @AfterEach
    fun tearDown() {
        scope.cancel()
        awaitStoreWrites()
    }

    private val ui get() = f.ui.value
    private val list get() = ui.list!!

    private fun done(r: SaveResult) = (r as SaveResult.Done).place!!
    private fun home(p: Place = museumPlace) = done(store.save(p, SavedKind.HOME))
    private fun other(name: String, p: Place) = done(store.save(p, SavedKind.OTHER, name))
    private fun pin(p: GeoPoint) = Place("Point on the map", p, PlaceSource.MAP_PIN)

    // ---------------------------------------------------------------- the list (2.6)
    @Test
    fun listRowsAndTexts() {
        f.openList(null, gps)
        scope.runCurrent()
        assertEquals(listOf("Set home", "Set work"), list.rows.map { it.title })
        assertEquals("Places you save from search or the map show here.", list.hint)
        assertTrue(list.canAdd)
        assertTrue(list.showOnMap)
        assertNull(list.notice)
        assertFalse(list.pickOnly)
        val h = home()
        val mom = other("Mom's", Place("Erie Art Museum", GeoPoint(42.13108, -80.08557), PlaceSource.SEARCH, "20 East 5th Street, Erie"))
        val quokka = other("Quokka Shop", mallPlace)
        scope.runCurrent()
        assertEquals(listOf("Home", "Set work", "Mom's", "Quokka Shop"), list.rows.map { it.title })
        val row = list.rows[0]
        assertEquals(h.id, row.savedId)
        assertEquals("Erie Maritime Museum, 100 East Front Street, Erie", row.subtitle)
        assertEquals("Edit Home", row.editLabel)
        assertTrue(row.distanceText!!.endsWith("mi"))
        assertEquals("Edit Mom's", list.rows[2].editLabel)
        assertEquals(quokka.id, list.rows[3].savedId)
        assertNull(list.rows[1].editLabel)
        assertNull(list.hint)
        // outside the routing tiles: the note
        other("Point State Park", Place("Point State Park", PITTSBURGH, PlaceSource.SEARCH))
        scope.runCurrent()
        assertEquals("Outside the installed maps", list.rows.first { it.title == "Point State Park" }.note)
        assertTrue(mom.id.isNotEmpty())
    }

    @Test
    fun atTheLimit() {
        for (i in 0 until 100) other("Place $i", Place("P$i", GeoPoint(41.0 + i * 0.01, -78.0), PlaceSource.SEARCH))
        f.openList(null, gps)
        assertFalse(list.canAdd)
        assertEquals("You can save 100 places besides Home and Work. Remove one to add another.", list.hint)
        assertNull(f.onEvent(SavedEvent.AddTapped))
    }

    @Test
    fun pickOnlyLists() {
        val h = home()
        other("Quokka Shop", mallPlace)
        for (m in listOf(SearchMode.ADD_STOP, SearchMode.SET_START)) {
            f.openList(m, gps, destination = h.toPlace())
            assertTrue(list.pickOnly)
            assertFalse(list.canAdd)
            assertNull(list.hint)
            assertEquals(listOf("Home", "Quokka Shop"), list.rows.map { it.title })   // no Set rows
            assertTrue(list.rows.all { it.editLabel == null })
        }
        assertEquals("Already your destination", list.rows[0].note)
        assertNull(f.onEvent(SavedEvent.RowTapped(h.id)))                              // SET_START: the destination does nothing
    }

    @Test
    fun notices() {
        File(dir, "saved-places.json").writeText("{")
        val broken = SavedPlaces(File(dir, "saved-places.json"), SavedRules.bundled(), Executor { it.run() })
        val g = SavedPlacesFeature(broken, recents, installed, MutableStateFlow(units), scope)
        g.openList(null, gps)
        assertEquals(Texts.BROKEN_NOTICE, g.ui.value.list!!.notice)
        g.onEvent(SavedEvent.NoticeDismissed)
        assertNull(g.ui.value.list!!.notice)
        File(dir, "n").mkdirs()
        File(dir, "n/saved-places.json").writeText("""{"format":"beamline-saved-places/2","places":[]}""")
        val newer = SavedPlaces(File(dir, "n/saved-places.json"), SavedRules.bundled(), Executor { it.run() })
        val n = SavedPlacesFeature(newer, recents, installed, MutableStateFlow(units), scope)
        n.openList(null, gps)
        assertEquals(Texts.NEWER_NOTICE, n.ui.value.list!!.notice)
        assertEquals(emptyList<SavedRowUi>(), n.ui.value.list!!.rows)
        assertFalse(n.ui.value.list!!.canAdd)
        // a failed write
        File(dir, "w").mkdirs()
        val wf = SavedPlaces(File(dir, "w/saved-places.json"), SavedRules.bundled(), Executor { it.run() })
        File(dir, "w/saved-places.json.tmp").mkdirs()
        File(dir, "w/saved-places.json.tmp/x").writeText("x")
        val w = SavedPlacesFeature(wf, recents, installed, MutableStateFlow(units), scope)
        w.openList(null, gps)
        wf.save(museumPlace, SavedKind.HOME)
        scope.runCurrent()
        assertEquals("Could not save. The phone may be out of space.", w.ui.value.list!!.notice)
    }

    @Test
    fun rowTapsForEachOpening() {
        val h = home()
        val far = other("Point State Park", Place("Point State Park", PITTSBURGH, PlaceSource.SEARCH))
        f.openList(SearchMode.GO_TO, gps)
        assertEquals(SavedOutput.PlanTrip(h.toPlace()), f.onEvent(SavedEvent.RowTapped(h.id)))
        assertNull(ui.list)                                         // closed after a pick
        f.openList(SearchMode.GO_TO, gps)
        assertEquals(SavedOutput.ShowPlace(far.toPlace()), f.onEvent(SavedEvent.RowTapped(far.id)))
        f.openList(SearchMode.ADD_STOP, gps)
        assertEquals(SavedOutput.Picked(h.toPlace(), SearchMode.ADD_STOP), f.onEvent(SavedEvent.RowTapped(h.id)))
        f.openList(SearchMode.SET_START, gps)
        assertEquals(SavedOutput.Picked(h.toPlace(), SearchMode.SET_START), f.onEvent(SavedEvent.RowTapped(h.id)))
        f.openList(null, gps)                                       // from Settings: the sheet
        assertEquals(SavedOutput.ShowPlace(h.toPlace()), f.onEvent(SavedEvent.RowTapped(h.id)))
        f.openList(null, gps)
        assertEquals(SavedOutput.SearchToSave(SaveTarget.Work), f.onEvent(SavedEvent.SlotTapped(SavedKind.WORK)))
        assertEquals(SavedOutput.SearchToSave(SaveTarget.NewPlace), f.onEvent(SavedEvent.AddTapped))
        assertEquals(SavedOutput.Closed, f.onEvent(SavedEvent.Back))
        assertNull(ui.list)
        assertNull(f.onEvent(SavedEvent.RowTapped("s00000000000000ff")))
    }

    @Test
    fun switchWritesShowOnMap() {
        f.openList(null, gps)
        f.onEvent(SavedEvent.ShowOnMapChanged(false))
        assertFalse(store.showOnMap.value)
        assertFalse(list.showOnMap)
        assertTrue(File(dir, "saved-places.json").readText().contains("\"show_on_map\":false"))
    }

    // ---------------------------------------------------------------- the Edit menu and its dialogs
    @Test
    fun menusPerKind() {
        val h = home()
        val w = done(store.save(pin(AIRPORT), SavedKind.WORK))
        val q = other("Quokka Shop", mallPlace)
        f.openEdit(h.id)
        assertEquals(SavedDialogUi.Menu("Home", listOf(MenuItemUi(EditAction.MOVE, "Change location"), MenuItemUi(EditAction.DELETE, "Remove home"))), ui.dialog)
        f.openEdit(w.id)
        assertEquals(listOf("Change location", "Remove work"), (ui.dialog as SavedDialogUi.Menu).items.map { it.label })
        f.openEdit(q.id)
        assertEquals(SavedDialogUi.Menu("Quokka Shop", listOf("Rename", "Change location", "Make home", "Make work", "Remove")
            .zip(listOf(EditAction.RENAME, EditAction.MOVE, EditAction.MAKE_HOME, EditAction.MAKE_WORK, EditAction.DELETE)) { l, a -> MenuItemUi(a, l) }), ui.dialog)
        // Change location -> search in SAVE mode for Move
        assertEquals(SavedOutput.SearchToSave(SaveTarget.Move(q.id)), f.onEvent(SavedEvent.MenuChosen(EditAction.MOVE)))
        assertNull(ui.dialog)
    }

    @Test
    fun confirmTexts() {
        val h = home()
        val q = other("Quokka Shop", mallPlace)
        f.openEdit(q.id)
        f.onEvent(SavedEvent.MenuChosen(EditAction.DELETE))
        assertEquals(SavedDialogUi.Confirm("Remove Quokka Shop?", null, "Remove"), ui.dialog)
        f.onEvent(SavedEvent.DialogDismiss)
        f.openEdit(h.id)
        f.onEvent(SavedEvent.MenuChosen(EditAction.DELETE))
        assertEquals(SavedDialogUi.Confirm("Remove your home?", null, "Remove"), ui.dialog)
        f.onEvent(SavedEvent.DialogDismiss)
        f.openEdit(q.id)
        f.onEvent(SavedEvent.MenuChosen(EditAction.MAKE_HOME))
        assertEquals(SavedDialogUi.Confirm("Make Quokka Shop your home?",
            "Your current home (Erie Maritime Museum, 100 East Front Street, Erie) will be removed. It will be called Home.", "Make home"), ui.dialog)
        f.onEvent(SavedEvent.DialogConfirm)
        assertEquals(SavedKind.HOME, store.byId(q.id)!!.kind)
        assertNull(store.byId(h.id))
        assertNull(ui.dialog)
        val r = other("Mom's", Place("Erie Art Museum", GeoPoint(42.13108, -80.08557), PlaceSource.SEARCH))
        f.openEdit(r.id)
        f.onEvent(SavedEvent.MenuChosen(EditAction.MAKE_WORK))
        assertEquals(SavedDialogUi.Confirm("Make Mom's your work?", "It will be called Work.", "Make work"), ui.dialog)
    }

    @Test
    fun renameDialog() {
        home()
        val q = other("Quokka Shop", mallPlace)
        other("Mom's", Place("Erie Art Museum", GeoPoint(42.13108, -80.08557), PlaceSource.SEARCH))
        f.openEdit(q.id)
        f.onEvent(SavedEvent.MenuChosen(EditAction.RENAME))
        assertEquals(SavedDialogUi.Rename("Quokka Shop", null, true), ui.dialog)
        for ((text, problem) in listOf(
            "" to "Type a name.", "***" to "Use at least one letter or number.", "x".repeat(41) to "Up to 40 characters.",
            "moms" to "You already have a place called Mom's.", "home" to "Use Make home to make this your home.",
            "Work!" to "Use Make work to make this your work.",
        )) {
            f.onEvent(SavedEvent.NameChanged(text))
            val d = ui.dialog as SavedDialogUi.Rename
            assertEquals(problem, d.problem, text)
            assertFalse(d.canConfirm)
            assertNull(f.onEvent(SavedEvent.DialogConfirm))
        }
        f.onEvent(SavedEvent.NameChanged("QUOKKA SHOP"))   // its own name in another case is fine
        assertTrue((ui.dialog as SavedDialogUi.Rename).canConfirm)
        f.onEvent(SavedEvent.NameChanged("Quokka Mall"))
        assertNull(f.onEvent(SavedEvent.DialogConfirm))
        assertEquals("Quokka Mall", store.byId(q.id)!!.name)
        assertNull(ui.dialog)
    }

    @Test
    fun saveDialogDefaultsProblemsAndReplace() {
        // a search result: its own name, "Other" chosen, "Save"
        f.openSave(mallPlace)
        var d = ui.dialog as SavedDialogUi.Save
        assertEquals("Millcreek Mall", d.placeTitle)
        assertEquals("5800 Peach Street, Erie", d.placeSubtitle)
        assertEquals("Millcreek Mall", d.name)
        assertEquals(listOf("Home", "Work", "Other"), d.kinds.map { it.label })
        assertEquals(listOf(false, false, true), d.kinds.map { it.selected })
        assertEquals("Save", d.confirmText)
        assertTrue(d.canConfirm)
        assertNull(d.problem)
        // a point: empty name, Save off, no problem line yet
        f.openSave(Place("Dropped pin", ZOO, PlaceSource.MAP_PIN, "42.09710, -80.07578"))
        d = ui.dialog as SavedDialogUi.Save
        assertEquals("", d.name)
        assertFalse(d.canConfirm)
        assertNull(d.problem)
        f.onEvent(SavedEvent.NameChanged("x"))
        f.onEvent(SavedEvent.NameChanged(""))
        assertEquals("Type a name.", (ui.dialog as SavedDialogUi.Save).problem)
        // a long name is cut to 40 characters as the eye counts them
        f.openSave(Place("A".repeat(60), MALL, PlaceSource.MAP_POI))
        assertEquals("A".repeat(40), (ui.dialog as SavedDialogUi.Save).name)
        // reserved and taken names, and "Choose Home above"
        val h = home()
        other("Quokka Shop", Place("Erie Art Museum", GeoPoint(42.13108, -80.08557), PlaceSource.SEARCH))
        f.openSave(mallPlace)
        for ((text, problem) in listOf("Home" to "Choose Home above to save your home.", "work" to "Choose Work above to save your work.",
            "quokka shop" to "You already have a place called Quokka Shop.")) {
            f.onEvent(SavedEvent.NameChanged(text))
            assertEquals(problem, (ui.dialog as SavedDialogUi.Save).problem)
        }
        // the place is already saved: "This place is already saved as Home."
        f.openSave(museumPlace)
        assertEquals("This place is already saved as Home.", (ui.dialog as SavedDialogUi.Save).problem)
        // Home chosen with a Home: the replace line and "Replace"; the name field hidden
        f.openSave(mallPlace)
        f.onEvent(SavedEvent.KindChosen(SavedKind.HOME))
        d = ui.dialog as SavedDialogUi.Save
        assertNull(d.name)
        assertEquals("This replaces your home: Erie Maritime Museum, 100 East Front Street, Erie.", d.replaceText)
        assertEquals("Replace", d.confirmText)
        assertTrue(d.canConfirm)
        val out = f.onEvent(SavedEvent.DialogConfirm) as SavedOutput.Saved
        assertEquals(SavedKind.HOME, out.place.kind)
        assertNull(store.byId(h.id))
        assertEquals(MALL, store.home()!!.point)
        // Work chosen with no Work: "Save", no replace line
        f.openSave(Place("Erie International Airport", AIRPORT, PlaceSource.MAP_POI))
        f.onEvent(SavedEvent.KindChosen(SavedKind.WORK))
        d = ui.dialog as SavedDialogUi.Save
        assertNull(d.replaceText)
        assertEquals("Save", d.confirmText)
        // the limit, only with "Other"
        for (i in 0 until 99) other("Place $i", Place("P$i", GeoPoint(41.0 + i * 0.01, -78.0), PlaceSource.SEARCH))
        f.openSave(Place("Erie Zoo", ZOO, PlaceSource.SEARCH))
        assertEquals("You can save 100 places besides Home and Work. Remove one to save another.", (ui.dialog as SavedDialogUi.Save).problem)
        f.onEvent(SavedEvent.KindChosen(SavedKind.WORK))
        assertTrue((ui.dialog as SavedDialogUi.Save).canConfirm)
        // the problem shown after a failed write
        f.onEvent(SavedEvent.DialogDismiss)
        assertNull(ui.dialog)
    }

    // ---------------------------------------------------------------- SP30: removing a place removes its recents
    @Test
    fun removingHomeForgetsItsRecents() {
        recents.add(Place("Erie Maritime Museum", GeoPoint(MUSEUM.lat + 0.0002, MUSEUM.lon), PlaceSource.SEARCH, "Erie"))
        recents.add(mallPlace)
        val h = home()
        f.openEdit(h.id)
        f.onEvent(SavedEvent.MenuChosen(EditAction.DELETE))
        f.onEvent(SavedEvent.DialogConfirm)
        assertNull(store.home())
        assertEquals(listOf("Millcreek Mall"), recents.places.value.map { it.name })
        // replacing Home through "Replace" forgets the old Home's recents too
        recents.add(museumPlace)
        home()
        f.openSave(mallPlace)
        f.onEvent(SavedEvent.KindChosen(SavedKind.HOME))
        f.onEvent(SavedEvent.DialogConfirm)
        assertEquals(listOf("Millcreek Mall"), recents.places.value.map { it.name })
    }

    // ---------------------------------------------------------------- the map pick (SP16, SP24, SP25)
    @Test
    fun mapPick() {
        val prompts = mapOf(SaveTarget.Home to ("Tap the map at your home" to "Set home"), SaveTarget.Work to ("Tap the map at your work" to "Set work"),
            SaveTarget.NewPlace to ("Tap the map at the place" to "Save"), SaveTarget.Move("s0000000000000001") to ("Tap the map at the new location" to "Use this point"))
        for ((t, texts) in prompts) {
            f.startMapPick(t)
            val p = ui.mapPick!!
            assertEquals(texts.first, p.prompt)
            assertEquals(texts.second, p.confirmText)
            assertEquals("No point chosen yet", p.title)
            assertNull(p.place)
            assertFalse(p.canConfirm)
        }
        f.startMapPick(SaveTarget.Home)
        f.onMapTap(GeoPoint(42.136459, -80.087181), null, 17.0)
        var p = ui.mapPick!!
        assertEquals("Point on the map", p.title)
        assertEquals("42.13646, -80.08718", p.subtitle)
        assertTrue(p.canConfirm)
        assertNull(p.problem)
        f.onMapTap(GeoPoint(42.136459, -80.087181), null, 14.0)
        p = ui.mapPick!!
        assertEquals("Zoom in to choose the exact spot.", p.problem)
        assertFalse(p.canConfirm)
        assertNull(f.onEvent(SavedEvent.PickConfirm))
        // a POI label is exact at any zoom
        f.onMapTap(MUSEUM, MapPoi("Erie Maritime Museum", "museum", MUSEUM), 12.0)
        p = ui.mapPick!!
        assertEquals("Erie Maritime Museum", p.title)
        assertTrue(p.canConfirm)
        // outside every pack box
        f.onMapTap(GeoPoint(41.4993, -81.6944), null, 17.0)
        assertEquals("Add this state to choose a point here.", ui.mapPick!!.problem)
        // inside the pack box but outside the routing tiles: the note, confirm on
        f.onMapTap(PITTSBURGH, null, 17.0)
        assertEquals("Outside the installed maps", ui.mapPick!!.note)
        assertTrue(ui.mapPick!!.canConfirm)
        // confirm for Home
        f.onMapTap(ZOO, null, 17.0)
        val ended = f.onEvent(SavedEvent.PickConfirm) as SavedOutput.PickEnded
        assertEquals(SavedKind.HOME, ended.place!!.kind)
        assertEquals("42.09710, -80.07578", ended.place!!.placeName)
        assertNull(ui.mapPick)
        // Work, Move and cancel
        f.startMapPick(SaveTarget.Work)
        f.onMapTap(AIRPORT, null, 16.0)
        assertEquals(SavedKind.WORK, (f.onEvent(SavedEvent.PickConfirm) as SavedOutput.PickEnded).place!!.kind)
        val q = other("Quokka Shop", mallPlace)
        f.startMapPick(SaveTarget.Move(q.id))
        f.onMapTap(MUSEUM, null, 18.0)
        val moved = (f.onEvent(SavedEvent.PickConfirm) as SavedOutput.PickEnded).place!!
        assertEquals(q.id, moved.id)
        assertEquals("42.13646, -80.08718", moved.placeName)
        f.startMapPick(SaveTarget.Home)
        f.onMapTap(MALL, null, 17.0)
        assertEquals(SavedOutput.PickEnded(null), f.onEvent(SavedEvent.PickCancel))
        assertNull(ui.mapPick)
        // NewPlace: the save dialog over the map; Cancel returns to the pick; Save ends it
        f.startMapPick(SaveTarget.NewPlace)
        f.onMapTap(ZOO, null, 17.0)
        assertNull(f.onEvent(SavedEvent.PickConfirm))
        assertTrue(ui.dialog is SavedDialogUi.Save)
        f.onEvent(SavedEvent.DialogDismiss)
        assertTrue(ui.mapPick != null && ui.dialog == null)
        f.onEvent(SavedEvent.PickConfirm)
        f.onEvent(SavedEvent.NameChanged("Zoo gate"))
        val saved = f.onEvent(SavedEvent.DialogConfirm) as SavedOutput.PickEnded
        assertEquals("Zoo gate", saved.place!!.name)
        assertNull(ui.mapPick)
    }

    @Test
    fun aSavedPlaceOpensItsEditMenuNotTheSaveDialog() {
        val h = home()
        f.openSave(h.toPlace())
        assertTrue(ui.dialog is SavedDialogUi.Menu)
        assertTrue(store.places.value.all { it.kind != SavedKind.OTHER })
        assertTrue(h.id.startsWith("s"))
    }

    @Suppress("unused")
    private fun SavedPlace.dummy() = this
}
