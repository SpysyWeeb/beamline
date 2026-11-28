package io.github.spysyweeb.beamline.feature.search

import io.github.spysyweeb.beamline.api.map.MapPoi
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.model.SavedKind
import io.github.spysyweeb.beamline.feature.search.SearchFeatureTest.Companion.gps
import io.github.spysyweeb.beamline.feature.search.SearchFeatureTest.Companion.units
import io.github.spysyweeb.beamline.feature.search.SearchFeatureTest.Companion.withMaps
import io.github.spysyweeb.beamline.search.PackResult
import io.github.spysyweeb.beamline.search.RecentPlaces
import io.github.spysyweeb.beamline.search.SearchResult
import io.github.spysyweeb.beamline.search.awaitStoreWrites
import io.github.spysyweeb.beamline.search.saved.SavedPlaces
import io.github.spysyweeb.beamline.search.saved.SavedRules
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.concurrent.Executor
import java.util.logging.Handler
import java.util.logging.LogManager
import java.util.logging.LogRecord
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * PV3 item 2 for :feature:search (docs/architecture-saved-places.md 10.1 item 8): the failure paths
 * of SearchFeature and SavedPlacesFeature with the canary place "Quokka Shop" at (42.06875,
 * -80.09813) placed "Millcreek Mall", with System.out, System.err, a root java.util.logging handler
 * and a default uncaught-exception handler captured: no canary in anything captured, in any
 * exception message (causes included) or in the toString() of any SearchOutput, SavedOutput or
 * Place returned. (The UI states hold names as texts by design and are not printed: PV2 item 1.)
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SavedLeakTest {
    @TempDir
    lateinit var dir: File

    private val canaries = listOf("quokka", "millcreek", "42.068", "80.098")
    private val seen = ArrayList<String>()
    private val mallPoint = GeoPoint(42.06875, -80.09813)
    private val mall = Place("Millcreek Mall", mallPoint, PlaceSource.SEARCH, "5800 Peach Street, Erie", "mall", 41, "us-pa")
    private val canaryFile = """{"format":"beamline-saved-places/1","places":[{"id":"s1234567890abcdef","kind":"other","name":"Quokka Shop","lat":42.06875,"lon":-80.09813,"place_name":"Millcreek Mall"}]}"""

    private fun note(x: Any?) {
        seen.add(x.toString())
    }

    private fun run(block: () -> Any?) {
        try {
            note(block())
        } catch (e: Throwable) {
            var t: Throwable? = e
            while (t != null) {
                seen.add(t.toString())
                seen.add(t.message ?: "")
                seen.add(t.stackTraceToString())
                t = t.cause
            }
        }
    }

    @Test
    fun noCanaryAnywhere() {
        val out = ByteArrayOutputStream()
        val err = ByteArrayOutputStream()
        val oldOut = System.out
        val oldErr = System.err
        val oldHandler = Thread.getDefaultUncaughtExceptionHandler()
        val root = LogManager.getLogManager().getLogger("")
        val logged = ArrayList<String>()
        val handler = object : Handler() {
            override fun publish(r: LogRecord) { logged.add("${r.message} ${r.parameters?.joinToString()} ${r.thrown}") }
            override fun flush() {}
            override fun close() {}
        }
        val uncaught = ArrayList<String>()
        root.addHandler(handler)
        System.setOut(PrintStream(out, true))
        System.setErr(PrintStream(err, true))
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.add(e.toString() + e.stackTraceToString()) }
        try {
            paths()
        } finally {
            System.setOut(oldOut)
            System.setErr(oldErr)
            Thread.setDefaultUncaughtExceptionHandler(oldHandler)
            root.removeHandler(handler)
            awaitStoreWrites()
        }
        assertTrue(seen.size > 30, "too few paths ran: ${seen.size}")
        for (text in seen + logged + uncaught + out.toString() + err.toString()) {
            for (c in canaries) if (text.lowercase().contains(c)) fail<Unit>("canary '$c' leaked into: ${text.take(300)}")
        }
    }

    private fun paths() {
        val dispatcher = StandardTestDispatcher()
        val scope = TestScope(dispatcher)
        val installed = MutableStateFlow(withMaps())
        val recents = RecentPlaces(File(dir, "recents.json"))
        val fake = FakeSearcher()
        fake.answers = { listOf(PackResult("us-pa", SearchResult(41, SearchResult.Kind.POI, "Millcreek Mall", "5800 Peach Street, Erie", "mall", null,
            mallPoint.lat, mallPoint.lon, 1.0, null, null, 1.0, "t", null))) }
        try {
            // a broken and a newer store holding the canary
            for ((i, text) in listOf(canaryFile.take(120), canaryFile.replace("/1", "/2")).withIndex()) {
                File(dir, "s$i").mkdirs()
                File(dir, "s$i/saved-places.json").writeText(text)
                val st = SavedPlaces(File(dir, "s$i/saved-places.json"), SavedRules.bundled(), Executor { it.run() })
                val sf = SearchFeature(installed, recents, st, MutableStateFlow(units), scope, dispatcher, fake)
                val lf = SavedPlacesFeature(st, recents, installed, MutableStateFlow(units), scope)
                sf.open(SearchMode.GO_TO, gps)
                lf.openList(null, gps)
                run { sf.onEvent(SearchEvent.QueryChanged("quokka")) }
                scope.advanceTimeBy(1_000); scope.runCurrent()
                run { sf.onEvent(SearchEvent.RowTapped(sf.ui.value.results.firstOrNull()?.key ?: "r:x")) }
                run { lf.onEvent(SavedEvent.RowTapped("s1234567890abcdef")) }
                run { lf.openSave(mall); lf.onEvent(SavedEvent.NameChanged("Quokka Shop")); lf.onEvent(SavedEvent.DialogConfirm) }
                run { sf.placeSheet(mall, gps, tripActive = false).place }
            }
            // a writable store with the canary, then every refusal
            File(dir, "ok").mkdirs()
            val st = SavedPlaces(File(dir, "ok/saved-places.json"), SavedRules.bundled(), Executor { it.run() })
            val sf = SearchFeature(installed, recents, st, MutableStateFlow(units), scope, dispatcher, fake)
            val lf = SavedPlacesFeature(st, recents, installed, MutableStateFlow(units), scope)
            lf.openList(null, gps)
            run { lf.openSave(mall); lf.onEvent(SavedEvent.NameChanged("Quokka Shop")); lf.onEvent(SavedEvent.DialogConfirm) }
            val q = st.places.value.first().id
            for (name in listOf("", "***", "Quokka ".repeat(10), "Home", "quokka shop")) {
                run { lf.openSave(mall.copy(point = GeoPoint(42.0971, -80.07578))); lf.onEvent(SavedEvent.NameChanged(name)); lf.onEvent(SavedEvent.DialogConfirm) }
            }
            run { lf.openSave(mall) }                                           // already saved
            run { lf.openEdit(q); lf.onEvent(SavedEvent.MenuChosen(EditAction.RENAME)) }
            for (name in listOf("", "home", "Quokka ".repeat(10))) {
                run { lf.onEvent(SavedEvent.NameChanged(name)); lf.onEvent(SavedEvent.DialogConfirm) }
            }
            run { lf.onEvent(SavedEvent.DialogDismiss) }
            // a refused change of location (onto the Home, which is the same place as the canary)
            run { st.save(mall.copy(name = "Erie Zoo", point = GeoPoint(42.0971, -80.07578)), SavedKind.HOME) }
            run { sf.openToSave(SaveTarget.Move(q), gps); sf.onEvent(SearchEvent.QueryChanged("millcreek")) }
            scope.advanceTimeBy(1_000); scope.runCurrent()
            run { sf.onEvent(SearchEvent.RowTapped(sf.ui.value.results.firstOrNull()?.key ?: "r:x")) }
            run { sf.onEvent(SearchEvent.Close) }
            // a cancelled pick and a refused pick
            run { lf.startMapPick(SaveTarget.Move(q)); lf.onMapTap(mallPoint, MapPoi("Millcreek Mall", "mall", mallPoint), 12.0) }
            run { lf.ui.value.mapPick?.place }
            run { lf.onEvent(SavedEvent.PickConfirm) }
            run { lf.onEvent(SavedEvent.PickCancel) }
            // unknown ids
            for (e in listOf(SavedEvent.RowTapped("s00000000000000ff"), SavedEvent.EditTapped("s00000000000000ff"), SavedEvent.MenuChosen(EditAction.DELETE),
                SavedEvent.DialogConfirm, SavedEvent.PickConfirm)) run { lf.onEvent(e) }
            run { sf.onEvent(SearchEvent.RowTapped("saved:s00000000000000ff")) }
            run { sf.placeSheet(mall.copy(source = PlaceSource.SAVED, savedId = "s00000000000000ff"), gps, tripActive = false).place }
            // the outputs of the ordinary paths
            run { sf.open(SearchMode.GO_TO, gps); sf.onEvent(SearchEvent.QueryChanged("quo")) }
            note(sf.ui.value.savedResults.map { it.key })
            run { sf.onEvent(SearchEvent.RowTapped("saved:$q")) }
            run { lf.openList(SearchMode.ADD_STOP, gps); lf.onEvent(SavedEvent.RowTapped(q)) }
            run { st.byId(q)?.toPlace() }
            run { sf.open(SearchMode.ADD_STOP, gps); sf.onEvent(SearchEvent.QueryChanged("millcreek")) }
            scope.advanceTimeBy(1_000); scope.runCurrent()
            run { sf.onEvent(SearchEvent.RowTapped(sf.ui.value.results.firstOrNull()?.key ?: "r:x")) }
            // P58: Remove (its output) and Undo, twice (the second does nothing)
            run { lf.openEdit(q); lf.onEvent(SavedEvent.MenuChosen(EditAction.DELETE)) }
            run { lf.onEvent(SavedEvent.DialogConfirm) }
            run { lf.onEvent(SavedEvent.UndoRemove(q)) }
            run { lf.onEvent(SavedEvent.UndoRemove(q)) }
            // P18: a street found for a pick and a save dialog over the canary's point
            run { lf.startMapPick(SaveTarget.NewPlace); lf.onMapTap(mallPoint, null, 17.0); lf.streetFound(mallPoint, "Near Quokka Street, Millcreek") }
            run { lf.onEvent(SavedEvent.PickConfirm) }
            run { lf.onEvent(SavedEvent.NameChanged("")); lf.onEvent(SavedEvent.DialogConfirm) }
            run { lf.onEvent(SavedEvent.PickCancel) }
            // a failed write
            File(dir, "ok/saved-places.json.tmp").mkdirs()
            File(dir, "ok/saved-places.json.tmp/x").writeText("x")
            run { lf.openSave(mall.copy(point = GeoPoint(42.11, -80.13))); lf.onEvent(SavedEvent.NameChanged("Quokka Annex")); lf.onEvent(SavedEvent.DialogConfirm) }
            scope.runCurrent()
            note(st.writeProblem.value)
        } finally {
            scope.cancel()
        }
    }
}
