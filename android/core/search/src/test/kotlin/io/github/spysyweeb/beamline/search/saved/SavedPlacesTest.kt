package io.github.spysyweeb.beamline.search.saved

import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import io.github.spysyweeb.beamline.api.model.SavedKind
import io.github.spysyweeb.beamline.api.model.SavedPlace
import io.github.spysyweeb.beamline.search.Geo
import io.github.spysyweeb.beamline.search.TestEnv
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir

/**
 * docs/architecture-saved-places.md 10.1, :core:search SavedPlacesTest items 1-16. Temporary
 * folders, a direct executor unless the test is about the writer, made-up places at public
 * points in Erie only.
 */
class SavedPlacesTest {
    @TempDir
    lateinit var dir: File

    private val file get() = File(dir, "saved-places.json")

    // ---------------------------------------------------------------- helpers
    companion object {
        val direct = Executor { it.run() }
        val rules = SavedRules.bundled()

        val MUSEUM = GeoPoint(42.13646, -80.08718)        // Erie Maritime Museum
        val AIRPORT = GeoPoint(42.08418, -80.17321)       // Erie International Airport
        val MALL = GeoPoint(42.06875, -80.09813)          // Millcreek Mall
        val ART = GeoPoint(42.13108, -80.08557)           // Erie Art Museum
        val ZOO = GeoPoint(42.0971, -80.07578)            // Erie Zoo

        fun ids(): () -> String {
            var n = 0
            return { "s%016x".format(++n) }
        }

        fun search(name: String, p: GeoPoint, subtitle: String? = null, category: String? = null, id: Long = 7) =
            Place(name, p, PlaceSource.SEARCH, subtitle, category, searchId = id, packId = "us-pa")

        fun pin(p: GeoPoint) = Place("Point on the map", p, PlaceSource.MAP_PIN, subtitle = pointText(p))

        /** [p] moved [m] metres north. */
        fun north(p: GeoPoint, m: Double) = GeoPoint(p.lat + m / (6371.0088 * 1000 * Math.PI / 180), p.lon)

        fun distM(a: GeoPoint, b: GeoPoint) = Geo.haversineKm(a.lat, a.lon, b.lat, b.lon) * 1000

        val museum = search("Erie Maritime Museum", MUSEUM, "100 East Front Street, Erie", "museum")
        val mall = search("Millcreek Mall", MALL, "5800 Peach Street, Erie", "mall", id = 9)
        val art = search("Erie Art Museum", ART, "20 East 5th Street, Erie", "museum", id = 11)

        const val FAMILY = "👨‍👩‍👧‍👦"

        fun fixture(name: String): String =
            File(TestEnv.repoRoot, "android/core/search/src/test/resources/saved/$name").readText(Charsets.UTF_8)
    }

    private fun open(executor: Executor = direct, newId: () -> String = ids()) = SavedPlaces(file, rules, executor, newId)

    private fun done(r: SaveResult): SavedPlace {
        assertTrue(r is SaveResult.Done, "expected Done, got $r")
        return (r as SaveResult.Done).place!!
    }

    private fun refused(r: SaveResult) = (r as SaveResult.Refused).problem

    // ---------------------------------------------------------------- 1
    @Test
    fun noFile() {
        val s = open()
        assertEquals(emptyList<SavedPlace>(), s.places.value)
        assertTrue(s.showOnMap.value)
        assertNull(s.loadProblem)
        assertNull(s.notice.value)
        assertFalse(s.readOnly)
        assertFalse(file.exists())
    }

    // ---------------------------------------------------------------- 2
    @Test
    fun saveHomeWritesTheExactFormat() {
        val s = open()
        done(s.save(museum, SavedKind.HOME))
        assertEquals(
            """{"format":"beamline-saved-places/1","show_on_map":true,"places":[{"id":"s0000000000000001","kind":"home","name":"Home",""" +
                """"lat":42.13646,"lon":-80.08718,"place_name":"Erie Maritime Museum","subtitle":"100 East Front Street, Erie","category":"museum"}]}""",
            file.readText(),
        )
        assertEquals(s.places.value, open().places.value)
        assertFalse(File(dir, "saved-places.json.tmp").exists())
        // a fixed list writes the same bytes every time
        val first = file.readBytes()
        s.setShowOnMap(false)
        s.setShowOnMap(true)
        assertArrayEquals(first, file.readBytes())
        // the point in memory is the rounded one, so a reload equals memory
        val h2 = done(s.save(search("Erie Zoo", GeoPoint(42.09710049, -80.07578051)), SavedKind.WORK))
        assertEquals(GeoPoint(42.0971, -80.075781), h2.point)
        assertEquals(s.places.value, open().places.value)
    }

    // ---------------------------------------------------------------- 3
    @Test
    fun leftoverTmpIsDeleted() {
        done(open().save(museum, SavedKind.HOME))
        val before = file.readBytes()
        val tmp = File(dir, "saved-places.json.tmp")
        tmp.writeText("{garbage")
        val s = open()
        assertEquals("Home", s.home()!!.name)
        assertFalse(tmp.exists())
        assertArrayEquals(before, file.readBytes())
    }

    @Test
    fun failedWriteKeepsMemoryAndTheOldFile() {
        val s = open()
        done(s.save(museum, SavedKind.HOME))
        val before = file.readBytes()
        val tmp = File(dir, "saved-places.json.tmp")
        assertTrue(tmp.mkdir())   // a directory in place of the temporary file: the write fails
        File(tmp, "x").writeText("x")
        val work = done(s.save(pin(AIRPORT), SavedKind.WORK))
        assertEquals(work, s.work())
        assertEquals(SaveProblem.WRITE_FAILED, s.writeProblem.value)
        assertArrayEquals(before, file.readBytes())
        tmp.deleteRecursively()
        done(s.save(mall, SavedKind.OTHER, "Quokka Shop"))
        assertNull(s.writeProblem.value)
        assertEquals(3, open().places.value.size)
    }

    @Test
    fun manyQuickChangesOnARealWriterEndAsTheFinalList() {
        val pool = Executors.newSingleThreadExecutor()
        try {
            val s = open(pool)
            for (i in 0 until 200) {
                if (i % 2 == 0) done(s.save(search("Place $i", north(ZOO, i * 100.0)), SavedKind.OTHER, "Place $i"))
                else s.setShowOnMap(i % 4 == 1)
            }
            s.awaitWrites()
            val again = open()
            assertEquals(s.places.value, again.places.value)
            assertEquals(s.showOnMap.value, again.showOnMap.value)
            assertEquals(100, again.places.value.size)
        } finally {
            pool.shutdown()
            pool.awaitTermination(10, TimeUnit.SECONDS)
        }
    }

    // ---------------------------------------------------------------- 4
    private val quokkaFile = """{"format":"beamline-saved-places/1","places":[{"id":"s1234567890abcdef","kind":"other","name":"Quokka Shop","lat":42.06875,"lon":-80.09813,"place_name":"Millcreek Mall"}]}"""

    @Test
    fun brokenFileIsKeptAsideAndTheNoticeLasts() {
        val truncated = quokkaFile.take(110)
        assertTrue(truncated.contains("Quokka Shop"))
        file.writeText(truncated)
        val s = open()
        assertEquals(LoadProblem.BROKEN, s.loadProblem)
        assertEquals(truncated, File(dir, "saved-places.broken-1.json").readText())
        assertEquals(emptyList<SavedPlace>(), s.places.value)
        assertFalse(s.readOnly)
        assertEquals(LoadProblem.BROKEN, s.notice.value)
        assertTrue(file.readText().contains("\"notice\":\"broken\""))
        // a new store on it still has the notice
        val s2 = open()
        assertNull(s2.loadProblem)
        assertEquals(LoadProblem.BROKEN, s2.notice.value)
        s2.dismissNotice()
        assertNull(s2.notice.value)
        assertFalse(file.readText().contains("notice"))
        assertNull(open().notice.value)
        // writable: a save works and the notice does not come back
        done(s2.save(museum, SavedKind.HOME))
        assertNull(open().notice.value)
        // a second broken start gives broken-2
        file.writeText("{")
        val s3 = open()
        assertEquals(LoadProblem.BROKEN, s3.loadProblem)
        assertEquals("{", File(dir, "saved-places.broken-2.json").readText())
        assertEquals(truncated, File(dir, "saved-places.broken-1.json").readText())
        for (t in listOf(s.loadProblem.toString(), s3.loadProblem.toString())) {
            assertFalse(t.contains("Quokka", ignoreCase = true) || t.contains("42.068"))
        }
    }

    /**
     * A file that exists but cannot be read (here a folder in its place; on a phone an I/O error)
     * is not a damaged structure: nothing is moved aside, nothing is written over it, and the store
     * is read-only with the BROKEN notice in this process only (review finding, SP52).
     */
    @Test
    fun anUnreadableFileIsLeftAloneAndTheStoreIsReadOnly() {
        assertTrue(file.mkdir())
        val inside = File(file, "keep.txt").apply { writeText("x") }
        val s = open()
        assertEquals(LoadProblem.BROKEN, s.loadProblem)
        assertEquals(LoadProblem.BROKEN, s.notice.value)
        assertTrue(s.readOnly)
        assertEquals(emptyList<SavedPlace>(), s.places.value)
        assertEquals(SaveProblem.READ_ONLY, refused(s.save(museum, SavedKind.HOME)))
        assertEquals(SaveProblem.READ_ONLY, refused(s.setShowOnMap(false)))
        s.dismissNotice()
        assertNull(s.notice.value)
        // untouched: still the folder with its content, no broken-<n> copy, no new file or tmp
        assertTrue(file.isDirectory)
        assertEquals("x", inside.readText())
        assertEquals(listOf("saved-places.json"), dir.list()!!.sorted())
        // and when it can be read again, the list is back as it was
        inside.delete()
        assertTrue(file.delete())
        done(open().save(museum, SavedKind.HOME))
        val again = open()
        assertNull(again.loadProblem)
        assertEquals("Home", again.home()!!.name)
    }

    @Test
    fun eachStructureBreakIsBroken() {
        val p = """"kind":"other","name":"Quokka Shop","lat":42.06875,"lon":-80.09813,"place_name":"Millcreek Mall""""
        val cases = listOf(
            """{"format":"beamline-saved-places/1","places":[{"id":"s0000000000000001","kind":"home","name":"Home","lat":42.1,"lon":-80.1,"place_name":"A"},{"id":"s0000000000000002","kind":"home","name":"Home","lat":42.1,"lon":-80.1,"place_name":"B"}]}""",
            """{"format":"beamline-saved-places/1","places":[{"id":"s1234567890abcdef",$p},{"id":"s1234567890abcdef",$p}]}""",
            """{"format":"beamline-saved-places/1","places":[{"id":"x12",$p}]}""",
            """{"format":"beamline-saved-places/1","places":[{"id":"s1234567890abcdef","kind":"other","name":"Quokka Shop","lat":91,"lon":-80.09813,"place_name":"Millcreek Mall"}]}""",
            """{"format":"beamline-saved-places/1","places":[{"id":"s1234567890abcdef","kind":"other","name":"","lat":42.06875,"lon":-80.09813,"place_name":"Millcreek Mall"}]}""",
            """{"format":"beamline-saved-places/1","places":[{"id":"s1234567890abcdef","kind":"garage","name":"Quokka Shop","lat":42.06875,"lon":-80.09813,"place_name":"Millcreek Mall"}]}""",
            """{"places":[{"id":"s1234567890abcdef",$p}]}""",
        )
        for ((i, text) in cases.withIndex()) {
            val d = File(dir, "case$i").apply { mkdirs() }
            val f = File(d, "saved-places.json")
            f.writeText(text)
            val s = SavedPlaces(f, rules, direct, ids())
            assertEquals(LoadProblem.BROKEN, s.loadProblem, "case $i")
            assertEquals(text, File(d, "saved-places.broken-1.json").readText(), "case $i")
        }
    }

    @Test
    fun readAsTheyAreNotBroken() {
        val sb = StringBuilder("""{"format":"beamline-saved-places/1","show_on_map":false,"places":[""")
        val entries = ArrayList<String>()
        entries.add("""{"id":"s00000000000000aa","kind":"home","name":"House","lat":42.13646,"lon":-80.08718,"place_name":"Erie Maritime Museum"}""")
        entries.add("""{"id":"s00000000000000ab","kind":"other","name":"${"x".repeat(60)}","lat":42.06875,"lon":-80.09813,"place_name":"Millcreek Mall"}""")
        entries.add("""{"id":"s00000000000000ac","kind":"other","name":"Mom's","lat":42.13108,"lon":-80.08557,"place_name":"Erie Art Museum"}""")
        entries.add("""{"id":"s00000000000000ad","kind":"other","name":"moms","lat":42.0971,"lon":-80.07578,"place_name":"Erie Zoo"}""")
        entries.add("""{"id":"s00000000000000ae","kind":"other","name":"Home","lat":42.11,"lon":-80.13,"place_name":"42.11000, -80.13000"}""")
        for (i in 0 until 116) entries.add("""{"id":"s%016x","kind":"other","name":"Place %03d","lat":42.0971,"lon":-80.07578,"place_name":"Erie Zoo"}""".format(0x1000 + i, i))
        sb.append(entries.joinToString(",")).append("]}")
        file.writeText(sb.toString())
        val s = open()
        assertNull(s.loadProblem)
        assertEquals(121, s.places.value.size)
        assertEquals(120, s.places.value.count { it.kind == SavedKind.OTHER })
        assertEquals("Home", s.home()!!.name)
        assertFalse(s.showOnMap.value)
        // an unrelated change rewrites the file; everything survives a reload
        done(s.save(pin(AIRPORT), SavedKind.WORK))
        val again = open()
        assertEquals(s.places.value, again.places.value)
        assertTrue(again.places.value.any { it.name == "x".repeat(60) })
        assertTrue(again.places.value.any { it.name == "Mom's" } && again.places.value.any { it.name == "moms" })
        assertEquals(2, again.places.value.count { it.name == "Home" })   // the HOME and the OTHER named "Home"
        // over the limit: read and kept; a new other place is refused
        assertEquals(SaveProblem.LIMIT, refused(again.save(search("Lookout", north(ZOO, 3000.0)), SavedKind.OTHER, "Lookout")))
    }

    // ---------------------------------------------------------------- 5
    @Test
    fun newerFileIsLeftAloneAndReadOnly() {
        val text = quokkaFile.replace("beamline-saved-places/1", "beamline-saved-places/2")
        file.writeText(text)
        val s = open()
        assertEquals(LoadProblem.NEWER, s.loadProblem)
        assertEquals(LoadProblem.NEWER, s.notice.value)
        assertTrue(s.readOnly)
        assertEquals(emptyList<SavedPlace>(), s.places.value)
        val attempts = listOf(
            s.save(museum, SavedKind.HOME), s.save(mall, SavedKind.OTHER, "Q"), s.rename("s1234567890abcdef", "X"),
            s.move("s1234567890abcdef", art), s.makeKind("s1234567890abcdef", SavedKind.HOME), s.delete("s1234567890abcdef"),
            s.setShowOnMap(false),
        )
        for (r in attempts) assertEquals(SaveProblem.READ_ONLY, refused(r))
        s.dismissNotice()
        assertNull(s.notice.value)
        assertEquals(text, file.readText())
        assertEquals(LoadProblem.NEWER, open().notice.value)
    }

    // ---------------------------------------------------------------- 6
    @Test
    fun samePlace() {
        val s = open()
        val home = done(s.save(museum, SavedKind.HOME))
        // within 50 m with the same folded name -> ALREADY_SAVED as OTHER
        val near = search("ERIE MARITIME MUSEUM!", north(MUSEUM, 40.0), id = 99)
        assertTrue(distM(near.point, MUSEUM) in 39.0..41.0)
        assertEquals(SaveProblem.ALREADY_SAVED, refused(s.save(near, SavedKind.OTHER, "Museum")))
        assertTrue(s.same(home, near))
        assertEquals(home, s.find(near))
        // 60 m away -> saved
        val far = search("Erie Maritime Museum", north(MUSEUM, 60.0), id = 98)
        assertFalse(s.same(home, far))
        assertNull(s.find(far))
        done(s.save(far, SavedKind.OTHER, "Museum annex"))
        // same point, different name -> saved
        done(s.save(search("Flagship Niagara", MUSEUM, id = 97), SavedKind.OTHER, "Ship"))
        // a map point 20 m from a Home saved from a map point -> saved, and find is null
        val d2 = File(dir, "pins").apply { mkdirs() }
        val s2 = SavedPlaces(File(d2, "saved-places.json"), rules, direct, ids())
        val pinHome = done(s2.save(pin(ZOO), SavedKind.HOME))
        val pin20 = pin(north(ZOO, 20.0))
        assertNull(s2.find(pin20))
        assertFalse(s2.same(pinHome, pin20))
        done(s2.save(pin20, SavedKind.OTHER, "Next door"))
        // Home and Work at one point: both saved, find gives Home (tie rule)
        val d3 = File(dir, "both").apply { mkdirs() }
        val s3 = SavedPlaces(File(d3, "saved-places.json"), rules, direct, ids())
        val h = done(s3.save(art, SavedKind.HOME))
        val w = done(s3.save(art, SavedKind.WORK))
        assertEquals(h, s3.find(art))
        assertTrue(s3.same(w, art))
        // by saved id; and none
        assertEquals(w, s3.find(w.toPlace()))
        assertTrue(s3.same(w, w.toPlace()))
        assertFalse(s3.same(h, w.toPlace()))
        assertNull(s3.find(mall))
        assertFalse(s3.same(h, mall))
    }

    @Test
    fun twoOthersAtOnePointFindTheFirstByNameKeyThenId() {
        file.writeText(
            """{"format":"beamline-saved-places/1","places":[""" +
                """{"id":"s0000000000000009","kind":"other","name":"b","lat":42.06875,"lon":-80.09813,"place_name":"Millcreek Mall"},""" +
                """{"id":"s0000000000000008","kind":"other","name":"B!","lat":42.06875,"lon":-80.09813,"place_name":"Millcreek Mall"},""" +
                """{"id":"s0000000000000007","kind":"other","name":"c","lat":42.06875,"lon":-80.09813,"place_name":"Millcreek Mall"}]}""",
        )
        val s = open()
        assertEquals("s0000000000000008", s.find(mall)!!.id)
    }

    // ---------------------------------------------------------------- 7
    @Test
    fun names() {
        val s = open()
        assertEquals(SaveProblem.NAME_EMPTY, s.checkName(""))
        assertEquals(SaveProblem.NAME_EMPTY, s.checkName("  "))
        assertEquals(SaveProblem.NAME_EMPTY, refused(s.save(mall, SavedKind.OTHER, null)))
        assertEquals(SaveProblem.NAME_NO_LETTERS, s.checkName("***"))
        assertEquals(SaveProblem.NAME_TOO_LONG, s.checkName("a".repeat(41)))
        assertNull(s.checkName("a".repeat(40)))
        assertEquals(7, FAMILY.codePointCount(0, FAMILY.length))
        assertEquals(1, graphemeCount(FAMILY))
        assertNull(s.checkName(FAMILY.repeat(40)))
        assertEquals(SaveProblem.NAME_TOO_LONG, s.checkName(FAMILY.repeat(41)))
        var n = 0
        fun at() = north(ZOO, 100.0 * ++n)   // a new point each time, so no ALREADY_SAVED
        done(s.save(search("A", at()), SavedKind.OTHER, "🏠"))
        done(s.save(search("B", at()), SavedKind.OTHER, "🏡"))
        done(s.save(search("C", at()), SavedKind.OTHER, "Дом"))
        assertEquals(listOf("Дом"), s.matching("до").map { it.name })
        done(s.save(search("D", at()), SavedKind.OTHER, "家"))
        done(s.save(search("E", at()), SavedKind.OTHER, "Café"))
        assertEquals(SaveProblem.NAME_TAKEN, refused(s.save(search("F", at()), SavedKind.OTHER, "cafe")))
        for (r in listOf("HOME", "work ", "Home!")) assertEquals(SaveProblem.NAME_RESERVED, s.checkName(r), r)
        val moms = done(s.save(search("G", at()), SavedKind.OTHER, "Mom's"))
        assertEquals(SaveProblem.NAME_TAKEN, s.checkName("moms"))
        val nl = done(s.save(search("H", at()), SavedKind.OTHER, "Line\nOne"))
        assertEquals("LineOne", nl.name)
        // a rename may keep its own name in another case
        assertEquals("MOM'S", done(s.rename(moms.id, "MOM'S")).name)
        // the keys of SP26
        assertEquals("moms", savedNameKey("Mom's"))
        assertEquals("moms", savedNameKey("moms"))
        assertEquals("cafe", savedNameKey("Café"))
        assertEquals("дом", savedNameKey("Дом"))
        assertEquals("家", savedNameKey("家"))
        assertEquals("🏠", savedNameKey("🏠"))
        assertNotEquals(savedNameKey("🏠"), savedNameKey("🏡"))
        assertEquals("", savedNameKey("***"))
        assertEquals("home", savedNameKey("HOME"))
        assertEquals("work", savedNameKey("work "))
        assertEquals("home", savedNameKey("Home!"))
    }

    /** Item 16, third fixture: the Kotlin key equals the Python key for every listed name. */
    @Test
    fun nameKeysEqualTheSharedList() {
        val names = Json.parseToJsonElement(fixture("saved-name-keys.json")).jsonObject["names"]!!.jsonArray
        assertTrue(names.size >= 40)
        for (e in names) {
            val o = e.jsonObject
            val name = o["name"]!!.jsonPrimitive.content
            assertEquals(o["key"]!!.jsonPrimitive.content, savedNameKey(name), "key of ${name.codePoints().toArray().joinToString(" ") { Integer.toHexString(it) }}")
        }
    }

    // ---------------------------------------------------------------- 8
    @Test
    fun rename() {
        val s = open()
        val home = done(s.save(museum, SavedKind.HOME))
        val q = done(s.save(mall, SavedKind.OTHER, "Quokka Shop"))
        val r = done(s.rename(q.id, "Quokka Mall"))
        assertEquals(q.copy(name = "Quokka Mall"), r)
        assertEquals(SaveProblem.NOT_FOUND, refused(s.rename("s00000000000000ff", "X")))
        assertThrows<IllegalArgumentException> { s.rename(home.id, "House") }
        val work = done(s.save(pin(AIRPORT), SavedKind.WORK))
        assertThrows<IllegalArgumentException> { s.rename(work.id, "Office") }
    }

    // ---------------------------------------------------------------- 9
    @Test
    fun changeOfLocation() {
        val s = open()
        val q = done(s.save(mall, SavedKind.OTHER, "Quokka Shop"))
        val moved = done(s.move(q.id, art))
        assertEquals(q.id, moved.id)
        assertEquals(q.kind, moved.kind)
        assertEquals("Quokka Shop", moved.name)
        assertEquals(ART, moved.point)
        assertEquals("Erie Art Museum", moved.placeName)
        assertEquals("20 East 5th Street, Erie", moved.subtitle)
        assertEquals("museum", moved.categoryId)
        // a MAP_PIN place: its coordinates as place name, no subtitle, no category
        val p = done(s.move(q.id, pin(AIRPORT)))
        assertEquals("42.08418, -80.17321", p.placeName)
        assertNull(p.subtitle)
        assertNull(p.categoryId)
        assertEquals("42.08418, -80.17321", p.description)
        // onto another saved place -> ALREADY_SAVED; onto itself -> fine
        done(s.save(museum, SavedKind.HOME))
        assertEquals(SaveProblem.ALREADY_SAVED, refused(s.move(q.id, museum)))
        done(s.move(q.id, mall))
        done(s.move(q.id, mall))
        // Home may move onto a place saved as an other place
        assertEquals(MALL, done(s.move(s.home()!!.id, mall)).point)
        // a SAVED place -> IllegalArgumentException, no name in the message
        val e = assertThrows<IllegalArgumentException> { s.save(q.toPlace(), SavedKind.OTHER, "Again") }
        assertFalse(e.message!!.contains("Quokka"))
        assertThrows<IllegalArgumentException> { s.move(q.id, q.toPlace()) }
    }

    /** P18 (0.1.40): a point keeps the street line it carries ("Near ..."), else its coordinates; no subtitle, no category. */
    @Test
    fun aPointKeepsTheStreetItWasSavedWith() {
        val s = open()
        val street = Place("Point on the map", AIRPORT, PlaceSource.MAP_PIN, subtitle = "Near West 12th Street, Erie")
        val w = done(s.save(street, SavedKind.WORK))
        assertEquals("Near West 12th Street, Erie", w.placeName)
        assertNull(w.subtitle)
        assertNull(w.categoryId)
        assertEquals("Near West 12th Street, Erie", w.description)
        assertEquals(AIRPORT, w.point)
        // a change of location onto a point with its street
        val q = done(s.save(mall, SavedKind.OTHER, "Quokka Shop"))
        val moved = done(s.move(q.id, street.copy(point = ZOO, subtitle = "Near West 38th Street, Erie")))
        assertEquals("Near West 38th Street, Erie", moved.placeName)
        assertNull(moved.subtitle)
        // still the coordinates: a pin carrying them, a pin with no line, a blank line
        assertEquals("42.08418, -80.17321", done(s.save(pin(AIRPORT), SavedKind.HOME)).placeName)
        assertEquals("42.08418, -80.17321", done(s.save(Place("Point on the map", AIRPORT, PlaceSource.MAP_PIN), SavedKind.HOME)).placeName)
        assertEquals("42.08418, -80.17321", done(s.save(Place("Point on the map", AIRPORT, PlaceSource.MAP_PIN, subtitle = "  "), SavedKind.HOME)).placeName)
        // a named place is unchanged
        assertEquals("Millcreek Mall", s.byId(q.id)!!.let { done(s.move(it.id, mall)).placeName })
    }

    /** P58 (0.1.40): Undo of a removal puts the place back as it was, unless its slot, its id or its name was taken meanwhile. */
    @Test
    fun restorePutsADeletedPlaceBack() {
        val s = open()
        val h = done(s.save(museum, SavedKind.HOME))
        val q = done(s.save(mall, SavedKind.OTHER, "Quokka Shop"))
        assertEquals(SaveResult.Done(null), s.delete(q.id))
        assertNull(s.byId(q.id))
        assertEquals(SaveResult.Done(q), s.restore(q))
        assertEquals(q, s.byId(q.id))
        assertEquals(listOf(h.id, q.id), s.places.value.map { it.id })
        assertTrue(file.readText().contains(q.id))
        // twice: its id is in the list again
        assertEquals(SaveProblem.ALREADY_SAVED, refused(s.restore(q)))
        // Home removed, a new Home saved, then the old one put back: its slot is taken
        s.delete(h.id)
        val h2 = done(s.save(art, SavedKind.HOME))
        assertEquals(SaveProblem.ALREADY_SAVED, refused(s.restore(h)))
        assertEquals(h2, s.home())
        // an other place whose name was taken meanwhile
        s.delete(q.id)
        done(s.save(search("Erie Zoo", ZOO), SavedKind.OTHER, "quokka shop"))
        assertEquals(SaveProblem.NAME_TAKEN, refused(s.restore(q)))
        assertNull(s.byId(q.id))
        // with Home free again, the old Home comes back
        s.delete(h2.id)
        assertEquals(SaveResult.Done(h), s.restore(h))
        assertEquals(h, s.home())
        // a read-only store
        val d = File(dir, "newer").apply { mkdirs() }
        File(d, "saved-places.json").writeText("""{"format":"beamline-saved-places/2","places":[]}""")
        val ro = SavedPlaces(File(d, "saved-places.json"), rules, direct, ids())
        assertEquals(SaveProblem.READ_ONLY, refused(ro.restore(q)))
    }

    // ---------------------------------------------------------------- 10, 11
    @Test
    fun replaceHomeAndMakeHome() {
        val s = open()
        val h1 = done(s.save(museum, SavedKind.HOME))
        val h2 = done(s.save(art, SavedKind.HOME))
        assertEquals(1, s.places.value.count { it.kind == SavedKind.HOME })
        assertEquals(ART, s.home()!!.point)
        assertNotEquals(h1.id, h2.id)
        assertNull(s.byId(h1.id))
        val q = done(s.save(mall, SavedKind.OTHER, "Quokka Shop"))
        val made = done(s.makeKind(q.id, SavedKind.HOME))
        assertNull(s.byId(h2.id))
        assertEquals(SavedKind.HOME, made.kind)
        assertEquals("Home", made.name)
        assertEquals(q.id, made.id)
        assertEquals(q.placeName, made.placeName)
        assertEquals(q.point, made.point)
        assertEquals(made, s.home())
        val w = done(s.save(pin(AIRPORT), SavedKind.WORK))
        assertThrows<IllegalArgumentException> { s.makeKind(w.id, SavedKind.HOME) }
        assertThrows<IllegalArgumentException> { s.makeKind(made.id, SavedKind.OTHER) }
        assertEquals(SaveProblem.NOT_FOUND, refused(s.makeKind("s00000000000000ff", SavedKind.WORK)))
        assertEquals(SaveProblem.NOT_FOUND, refused(s.delete("s00000000000000ff")))
        assertEquals(SaveResult.Done(null), s.delete(w.id))
        assertNull(s.work())
    }

    // ---------------------------------------------------------------- 12
    @Test
    fun limit() {
        val s = open()
        for (i in 0 until 100) done(s.save(search("P$i", north(ZOO, 100.0 * i)), SavedKind.OTHER, "Place $i"))
        assertEquals(SaveProblem.LIMIT, refused(s.save(mall, SavedKind.OTHER, "One more")))
        done(s.save(museum, SavedKind.HOME))
        done(s.save(pin(AIRPORT), SavedKind.WORK))
        assertEquals(102, s.places.value.size)
    }

    // ---------------------------------------------------------------- 13
    @Test
    fun order() {
        val s = open()
        done(s.save(search("A", north(ZOO, 100.0)), SavedKind.OTHER, "Mom's"))
        done(s.save(search("B", north(ZOO, 200.0)), SavedKind.OTHER, "apple"))
        done(s.save(search("C", north(ZOO, 300.0)), SavedKind.OTHER, "Äpfel"))
        done(s.save(pin(AIRPORT), SavedKind.WORK))
        done(s.save(museum, SavedKind.HOME))
        assertEquals(listOf("Home", "Work", "Äpfel", "apple", "Mom's"), s.places.value.map { it.name })
        assertEquals(s.places.value, open().places.value)
    }

    // ---------------------------------------------------------------- 14
    @Test
    fun matchingOfItem14() {
        val s = open()
        done(s.save(museum, SavedKind.HOME))
        done(s.save(pin(AIRPORT), SavedKind.WORK))
        var n = 0
        for (name in listOf("Mom's", "Mom's House", "Momo Sushi", "Gym")) done(s.save(search("X$n", north(ZOO, 100.0 * ++n)), SavedKind.OTHER, name))
        fun m(t: String) = s.matching(t).map { it.name }
        assertEquals(emptyList<String>(), m(""))
        assertEquals(listOf("Home", "Mom's House"), m("h"))
        assertEquals(listOf("Home"), m("home"))
        assertEquals(listOf("Momo Sushi", "Mom's", "Mom's House"), m("mom"))
        assertEquals(listOf("Mom's", "Mom's House"), m("moms"))
        assertEquals(listOf("Mom's House"), m("ho mo"))
        assertEquals(emptyList<String>(), m("xyz"))
        for (t in listOf("m", "o", "s", "h", "mo")) assertTrue(m(t).size <= 3)
        done(s.save(search("Y", north(ZOO, 2000.0)), SavedKind.OTHER, "🏠"))
        assertEquals(listOf("🏠"), m("🏠"))
    }

    /** Item 16, second fixture: the same answers as packbuilder/search/saved_places.py. */
    @Test
    fun matchingEqualsTheSharedCases() {
        val doc = Json.parseToJsonElement(fixture("saved-matching.json")).jsonObject
        val decoded = SavedPlacesFile.decode(doc["file"]!!.toString())
        val places = SavedStore.ordered((decoded as SavedPlacesFile.Decoded.Ok).content.places)
        val r = doc["rules"]!!.jsonObject
        val rr = rules.copy(searchMinChars = r["search_min_chars"]!!.jsonPrimitive.int, searchMaxRows = r["search_max_rows"]!!.jsonPrimitive.int)
        val cases = doc["cases"]!!.jsonArray
        assertTrue(cases.size >= 10)
        for (c in cases) {
            val typed = c.jsonObject["typed"]!!.jsonPrimitive.content
            val want = (c.jsonObject["expect"] as JsonArray).map { it.jsonPrimitive.content }
            assertEquals(want, SavedMatcher.matching(places, typed, rr).map { it.name }, typed)
        }
    }

    // ---------------------------------------------------------------- 15
    @Test
    fun savedRules() {
        val copy = SavedRules.bundled()
        assertSame(copy, SavedRules.bundled())
        val src = SavedRules.parse(File(TestEnv.repoRoot, "packbuilder/search/search_app.json").readText())
        assertEquals(src, copy)
        assertEquals(SavedRules(100, 40, 50.0, 1, 3, 16.0, 30.0), copy)
        assertThrows<IllegalArgumentException> { SavedRules.parse("""{"format":"beamline-search-app/1"}""") }
        assertThrows<IllegalArgumentException> { SavedRules.parse("{") }
    }

    // ---------------------------------------------------------------- 16
    @Test
    fun fixtureDecodesAndReencodesByteIdentically() {
        val text = fixture("saved-places-v1.json")
        val d = SavedPlacesFile.decode(text) as SavedPlacesFile.Decoded.Ok
        assertEquals(listOf("Home", "Work", "Quokka Shop"), d.content.places.map { it.name })
        assertEquals(text, SavedPlacesFile.encode(d.content))
        // through the store too: written back unchanged after a no-op change
        file.writeText(text)
        val s = open()
        s.setShowOnMap(false)
        s.setShowOnMap(true)
        assertEquals(text, file.readText())
        // the export form leaves the notice out
        val withNotice = d.content.copy(notice = SavedPlacesFile.NOTICE_BROKEN)
        assertTrue(SavedPlacesFile.encode(withNotice).contains("\"notice\":\"broken\""))
        assertEquals(text, SavedPlacesFile.encode(withNotice, withNotice = false))
    }

    @Test
    fun randomIds() {
        val seen = HashSet<String>()
        repeat(1000) {
            val id = randomSavedId()
            assertTrue(Regex("s[0-9a-f]{16}").matches(id), id)
            seen.add(id)
        }
        assertEquals(1000, seen.size)
        // an id already in the file is drawn again
        val s = open(newId = sequenceOf("s0000000000000001", "s0000000000000001", "s0000000000000002").iterator().let { i -> { i.next() } })
        assertEquals("s0000000000000001", done(s.save(museum, SavedKind.HOME)).id)
        assertEquals("s0000000000000002", done(s.save(pin(AIRPORT), SavedKind.WORK)).id)
        assertTrue(JsonObject::class.isInstance(Json.parseToJsonElement(file.readText())))
    }
}
