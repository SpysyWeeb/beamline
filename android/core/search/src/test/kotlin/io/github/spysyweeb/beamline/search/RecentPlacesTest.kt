package io.github.spysyweeb.beamline.search

import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.Place
import io.github.spysyweeb.beamline.api.model.PlaceSource
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/** RecentPlaces (docs/architecture.md 2.6, 2.12): order, de-dup, max, broken file. Made-up picks at public points. */
class RecentPlacesTest {
    @TempDir
    lateinit var dir: File

    private val file get() = File(dir, "recents.json")

    /** The temporary folder is deleted after each test: no write may still be running in it. */
    @AfterEach
    fun waitForWrites() = awaitStoreWrites()

    private fun pick(name: String, lat: Double, lon: Double, id: Long? = null, pack: String? = "us-pa") =
        Place(name, GeoPoint(lat, lon), PlaceSource.SEARCH, "Erie", "museum", id, if (id == null) null else pack)

    private val museum = pick("Erie Maritime Museum", 42.13646, -80.08718, 1)
    private val mall = pick("Millcreek Mall", 42.06875, -80.09813, 2)
    private val zoo = pick("Erie Zoo", 42.0971, -80.07578, 3)

    private fun reopen(r: RecentPlaces): RecentPlaces {
        r.awaitWrites()
        return RecentPlaces(file)
    }

    @Test
    fun newestFirstAndKeptAcrossRestarts() {
        val r = RecentPlaces(file)
        assertEquals(emptyList<Place>(), r.places.value)
        r.add(museum)
        r.add(mall)
        r.add(zoo)
        assertEquals(listOf("Erie Zoo", "Millcreek Mall", "Erie Maritime Museum"), r.places.value.map { it.name })
        assertTrue(r.places.value.all { it.source == PlaceSource.RECENT })
        val again = reopen(r)
        assertEquals(r.places.value, again.places.value)
        assertEquals(3L, again.places.value[0].searchId)
        assertEquals("us-pa", again.places.value[0].packId)
        assertEquals("beamline-recents/1", Json.parseToJsonElement(file.readText()).jsonObject["format"]!!.jsonPrimitive.content)
        assertFalse(File(dir, "recents.json.tmp").exists())
    }

    @Test
    fun samePlaceIsMovedToTheTopNotRepeated() {
        val r = RecentPlaces(file)
        r.add(museum)
        r.add(mall)
        // same pack and search id, another name: the same place
        r.add(museum.copy(name = "Maritime museum (renamed)"))
        assertEquals(listOf("Maritime museum (renamed)", "Millcreek Mall"), r.places.value.map { it.name })
        // no search id, the same folded name within 50 m: the same place
        val near = Place("MILLCREEK MALL", GeoPoint(42.06875 + 30.0 / 111195.0, -80.09813), PlaceSource.MAP_POI)
        r.add(near)
        assertEquals(listOf("MILLCREEK MALL", "Maritime museum (renamed)"), r.places.value.map { it.name })
        // the same name 80 m away: another place
        r.add(Place("Millcreek Mall", GeoPoint(42.06875 + 80.0 / 111195.0, -80.09813), PlaceSource.MAP_POI))
        assertEquals(3, r.places.value.size)
        // a saved place never becomes a recent (SP31)
        r.add(museum.copy(source = PlaceSource.SAVED, savedId = "s0000000000000001"))
        assertEquals(3, r.places.value.size)
    }

    @Test
    fun maxIsKept() {
        val r = RecentPlaces(file, max = 5)
        for (i in 0 until 8) r.add(pick("Place $i", 42.0971 + i * 0.01, -80.07578, 100L + i))
        assertEquals((7 downTo 3).map { "Place $it" }, r.places.value.map { it.name })
        assertEquals(5, reopen(r).places.value.size)
        // the default is search_app.json recents.max (30)
        val d = RecentPlaces(File(dir, "other.json"))
        for (i in 0 until 40) d.add(pick("P$i", 41.0 + i * 0.01, -80.0, 1000L + i))
        assertEquals(30, d.places.value.size)
        assertEquals(30, SearchAppRules.bundled().recentsMax)
        d.awaitWrites()   // the temporary folder is deleted after the test: no write may still run in it
    }

    @Test
    fun removeAndClear() {
        val r = RecentPlaces(file)
        r.add(museum)
        r.add(mall)
        r.add(zoo)
        r.remove(Place("millcreek mall", GeoPoint(42.06875, -80.09813), PlaceSource.SEARCH))
        assertEquals(listOf("Erie Zoo", "Erie Maritime Museum"), r.places.value.map { it.name })
        assertEquals(r.places.value, reopen(r).places.value)
        r.clear()
        assertEquals(emptyList<Place>(), r.places.value)
        assertEquals(emptyList<Place>(), reopen(r).places.value)
    }

    /**
     * recents.json is written on Dispatchers.IO (docs/architecture.md 1.2: JSON stores), not on a
     * thread of its own: after writes, no thread named "beamline-..." exists beyond the ones 1.2
     * lists (review finding: a private "beamline-store-writer" thread).
     */
    @Test
    fun writesRunOnNoThreadOutsideTheThreadTable() {
        val r = RecentPlaces(file)
        r.add(museum)
        r.add(mall)
        r.awaitWrites()
        assertEquals(listOf("Millcreek Mall", "Erie Maritime Museum"), RecentPlaces(file).places.value.map { it.name })
        val table = setOf("beamline-nav", "beamline-routing", "beamline-search")
        val extra = Thread.getAllStackTraces().keys.map { it.name }.filter { it.startsWith("beamline-") && it !in table }
        assertEquals(emptyList<String>(), extra)
    }

    @Test
    fun brokenFileReadsAsEmpty() {
        for (text in listOf("{", "", "[]", """{"format":"beamline-recents/2","places":[]}""", """{"format":"beamline-recents/1","places":[{"name":"x","lat":91,"lon":0}]}""",
            """{"format":"beamline-recents/1","places":[{"name":5,"lat":42,"lon":-80}]}""", "\u0000\u0001garbage")) {
            file.writeText(text)
            val r = RecentPlaces(file)
            assertEquals(emptyList<Place>(), r.places.value, text)
            // usable: the next add writes a good file
            r.add(museum)
            assertEquals(listOf("Erie Maritime Museum"), reopen(r).places.value.map { it.name })
        }
        file.delete()
        assertNull(RecentPlaces(file).places.value.firstOrNull())
    }
}
