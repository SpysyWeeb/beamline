package io.github.spysyweeb.beamline.search.saved

import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.model.SavedKind
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.util.concurrent.Executor
import java.util.logging.Handler
import java.util.logging.LogManager
import java.util.logging.LogRecord
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * PV3 item 2 (docs/architecture-saved-places.md 10.1 item 17): every failure path of the store,
 * run with the canary place "Quokka Shop" at (42.06875, -80.09813) placed "Millcreek Mall", with
 * System.out, System.err, a root java.util.logging handler and a default uncaught-exception
 * handler captured. No canary in anything captured, in any exception message (causes included)
 * or in the toString() of any SaveResult, LoadProblem or Place the store returns.
 */
class SavedPlacesLeakTest {
    @TempDir
    lateinit var dir: File

    private val canaries = listOf("quokka", "millcreek", "42.068", "80.098")
    private val seen = ArrayList<String>()

    /** Millcreek Mall from search, which the tests save as "Quokka Shop". */
    private val canary = Place("Millcreek Mall", GeoPoint(42.06875, -80.09813), PlaceSource.SEARCH, "5800 Peach Street, Erie", "mall", 41, "us-pa")
    private val canaryPin = Place("Point on the map", GeoPoint(42.06875, -80.09813), PlaceSource.MAP_PIN, "42.06875, -80.09813")
    private val canaryFile = """{"format":"beamline-saved-places/1","places":[{"id":"s1234567890abcdef","kind":"other","name":"Quokka Shop","lat":42.06875,"lon":-80.09813,"place_name":"Millcreek Mall"}]}"""
    private val direct = Executor { it.run() }

    private fun store(sub: String, text: String? = null): SavedPlaces {
        val d = File(dir, sub).apply { mkdirs() }
        val f = File(d, "saved-places.json")
        if (text != null) f.writeText(text)
        var n = 0
        return SavedPlaces(f, SavedRules.bundled(), direct) { "s%016x".format(++n) }
    }

    private fun note(x: Any?) {
        seen.add(x.toString())
    }

    private fun noteThrown(block: () -> Any?) {
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
            override fun publish(r: LogRecord) {
                logged.add("${r.message} ${r.parameters?.joinToString()} ${r.thrown}")
            }
            override fun flush() {}
            override fun close() {}
        }
        val uncaught = ArrayList<String>()
        root.addHandler(handler)
        System.setOut(PrintStream(out, true))
        System.setErr(PrintStream(err, true))
        Thread.setDefaultUncaughtExceptionHandler { _, e -> uncaught.add(e.toString() + " " + e.stackTraceToString()) }
        try {
            runFailurePaths()
        } finally {
            System.setOut(oldOut)
            System.setErr(oldErr)
            Thread.setDefaultUncaughtExceptionHandler(oldHandler)
            root.removeHandler(handler)
        }
        val everything = seen + logged + uncaught + out.toString() + err.toString()
        assertTrue(seen.size > 40, "too few paths ran: ${seen.size}")
        for (text in everything) {
            for (c in canaries) {
                if (text.lowercase().contains(c)) fail<Unit>("canary '$c' leaked into: ${text.take(300)}")
            }
        }
    }

    private fun runFailurePaths() {
        // a broken file (truncated, and with other structure faults) holding the canary
        for ((i, text) in listOf(canaryFile.take(120), canaryFile.replace("\"other\"", "\"garage\""),
            canaryFile.replace("42.06875", "91.06875"), canaryFile.replace("s1234567890abcdef", "x12"))
            .withIndex()) {
            val s = store("broken$i", text)
            note(s.loadProblem)
            note(s.notice.value)
            note(s.places.value)
            assertEquals(LoadProblem.BROKEN, s.loadProblem)
        }
        // a newer file holding the canary: every change refused
        val newer = store("newer", canaryFile.replace("/1", "/2"))
        note(newer.loadProblem)
        for (r in listOf(
            { newer.save(canary, SavedKind.OTHER, "Quokka Shop") }, { newer.save(canary, SavedKind.HOME) },
            { newer.rename("s1234567890abcdef", "Quokka Mall") }, { newer.move("s1234567890abcdef", canary) },
            { newer.makeKind("s1234567890abcdef", SavedKind.HOME) }, { newer.delete("s1234567890abcdef") },
            { newer.setShowOnMap(false) }, { newer.checkName("Quokka Shop") }, { newer.matching("quokka") },
        )) noteThrown(r)
        // a failed write of the canary
        val failing = store("failing")
        File(dir, "failing/saved-places.json.tmp").apply { mkdirs(); File(this, "x").writeText("x") }
        noteThrown { failing.save(canary, SavedKind.OTHER, "Quokka Shop") }
        note(failing.writeProblem.value)
        noteThrown { failing.save(canaryPin, SavedKind.HOME) }
        // every refused save, rename and change of location
        val s = store("ok")
        noteThrown { s.save(canary, SavedKind.OTHER, "Quokka Shop") }
        noteThrown { s.save(canary, SavedKind.OTHER, "Quokka Shop") }                      // ALREADY_SAVED
        noteThrown { s.save(canary.copy(point = GeoPoint(42.0971, -80.07578)), SavedKind.OTHER, "quokka shop") } // NAME_TAKEN
        noteThrown { s.save(canary.copy(point = GeoPoint(42.11, -80.13)), SavedKind.OTHER, "") }               // NAME_EMPTY
        noteThrown { s.save(canary.copy(point = GeoPoint(42.11, -80.13)), SavedKind.OTHER, "***") }            // NAME_NO_LETTERS
        noteThrown { s.save(canary.copy(point = GeoPoint(42.11, -80.13)), SavedKind.OTHER, "Quokka ".repeat(10)) } // NAME_TOO_LONG
        noteThrown { s.save(canary.copy(point = GeoPoint(42.11, -80.13)), SavedKind.OTHER, "Home") }           // NAME_RESERVED
        noteThrown { s.save(canary.copy(source = PlaceSource.SAVED, savedId = "s0000000000000001"), SavedKind.OTHER, "Quokka Shop") }
        val id = s.places.value.first().id
        noteThrown { s.rename(id, "") }
        noteThrown { s.rename(id, "Home") }
        noteThrown { s.rename(id, "Quokka ".repeat(10)) }
        val home = (s.save(canaryPin, SavedKind.HOME) as SaveResult.Done).place!!
        note(home)
        note(home.toPlace())
        noteThrown { s.rename(home.id, "Quokka House") }                                // IllegalArgumentException
        noteThrown { s.makeKind(home.id, SavedKind.WORK) }                             // IllegalArgumentException
        noteThrown { s.makeKind(id, SavedKind.OTHER) }                                 // IllegalArgumentException
        noteThrown { s.save(Place("Zoo", GeoPoint(42.0971, -80.07578), PlaceSource.SEARCH), SavedKind.OTHER, "Zoo") }
        val zoo = s.places.value.first { it.name == "Zoo" }.id
        noteThrown { s.move(zoo, canary) }                                              // ALREADY_SAVED
        noteThrown { s.move(zoo, home.toPlace()) }                                      // IllegalArgumentException
        noteThrown { s.checkName("quokka shop") }
        noteThrown { s.find(canary) }
        noteThrown { s.matching("quokka") }
        // an unknown id
        for (r in listOf({ s.rename("s00000000000000ff", "Quokka Mall") }, { s.move("s00000000000000ff", canary) },
            { s.makeKind("s00000000000000ff", SavedKind.HOME) }, { s.delete("s00000000000000ff") }, { s.byId("s00000000000000ff") })) {
            noteThrown(r)
        }
        // P58: every refused Undo of a removal (restore)
        val q = s.byId(id)!!
        noteThrown { newer.restore(q) }                                                  // READ_ONLY
        noteThrown { s.restore(q) }                                                      // ALREADY_SAVED: its id is in the list
        noteThrown { s.delete(id) }
        noteThrown { s.save(canary, SavedKind.OTHER, "Quokka Shop") }                   // the name taken again, by a new id
        noteThrown { s.restore(q) }                                                      // NAME_TAKEN
        note(s.places.value)
        // a crash on another thread goes to the default handler: its text holds no canary either
        val t = Thread { s.save(canary.copy(source = PlaceSource.SAVED, savedId = id), SavedKind.HOME) }
        t.start()
        t.join()
        // the codec alone
        note(SavedPlacesFile.decode(canaryFile.take(100)))
        note(SavedPlacesFile.decode(canaryFile))
    }
}
