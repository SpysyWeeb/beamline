package io.github.spysyweeb.beamline.search.hours

import io.github.spysyweeb.beamline.search.SearchEngine
import io.github.spysyweeb.beamline.search.TestEnv
import io.github.spysyweeb.beamline.search.db.SqlDb
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Rule S10 on the real Pennsylvania pack (skipped without it, failing in strict mode): the engine
 * reads hours, phone and website of chain results near downtown Erie, and how many of the pack's
 * distinct opening_hours texts this app can evaluate (printed for the report; counts only).
 */
class PoiDetailsPackTest {
    @Test
    fun detailsOfSheetzNearErieAndTheShareOfReadableTexts() {
        val db = TestEnv.requireSearchDb()
        SearchEngine(db, null).use { eng ->
            val erie = TestEnv.points.getValue("erie")
            val r = eng.search("sheetz", erie.lat, erie.lon, 20)
            assertTrue(r.results.size >= 5, "sheetz near Erie: ${r.results.size}")
            val d = eng.poiDetails(r.results.map { it.id })
            println("sheetz near Erie: ${r.results.size} results, ${d.size} with details, " +
                "${d.values.count { it.openingHours != null }} with hours, ${d.values.count { OpeningHours.parse(it.openingHours) != null }} readable")
            assertTrue(d.isNotEmpty())
            assertTrue(eng.poiDetails(listOf(-5L, 0L)).isEmpty())
        }
        SqlDb.openReadOnly(db).use { sql ->
            val rows = sql.query("SELECT opening_hours, COUNT(*) FROM poi WHERE opening_hours IS NOT NULL GROUP BY opening_hours")
            var places = 0L
            var readable = 0L
            var holidays = 0L
            for (row in rows) {
                val n = row[1] as Long
                places += n
                val h = OpeningHours.parse(row[0] as String) ?: continue
                readable += n
                if (h.holidaysIgnored) holidays += n
            }
            println("opening_hours in the pack: ${rows.size} distinct texts on $places places; readable $readable " +
                "(%.1f %%), of them with holiday parts left out $holidays".format(100.0 * readable / places))
            assertTrue(readable > places / 2)
        }
    }

    /**
     * Batch C2 (S10 without a search id): a place tapped on the map, a saved place or a recent finds
     * the hours, phone and website of its pack POI by name and position; nothing when the name
     * differs, the point is too far, or the POIs of that name near it have different details (never
     * a guess). The Sheetz on West Ridge Road is a convenience store and two fuel canopies within
     * 40 m: only the store has details, so they are sure.
     */
    @Test
    fun aPlaceWithoutASearchIdIsFoundByNameAndPosition() {
        val db = TestEnv.requireSearchDb()
        SearchEngine(db, null).use { eng ->
            val erie = TestEnv.points.getValue("erie")
            val r = eng.search("sheetz", erie.lat, erie.lon, 20).results.filter { it.kind == io.github.spysyweeb.beamline.search.SearchResult.Kind.POI }
            val withDetails = eng.poiDetails(r.map { it.id })
            val one = r.first { withDetails[it.id]?.hasContact == true }
            val north = one.lat + 40.0 / 111_195.0                 // 40 m north (a tapped label is rarely on the pack's point)
            val near = eng.poisNamedNear(one.title, north, one.lon, 100.0)
            println("Sheetz ${one.id}: ${near.size} POIs of that name within 100 m of a point 40 m north")
            assertTrue(one.id in near)
            assertEquals(one.id to withDetails.getValue(one.id), eng.detailsNamedNear(one.title, north, one.lon, 100.0))
            assertEquals(one.id, eng.detailsNamedNear(one.title.uppercase(), one.lat, one.lon, 100.0)?.first)   // the name's key, not its case
            assertNull(eng.detailsNamedNear(one.title, one.lat + 300.0 / 111_195.0, one.lon, 100.0))            // too far
            assertNull(eng.detailsNamedNear("Not A Place Here", one.lat, one.lon, 100.0))
            assertNull(eng.detailsNamedNear("", one.lat, one.lon, 100.0))
            // Every Sheetz within 30 km: several with different details, so not sure.
            val wide = eng.poisNamedNear(one.title, one.lat, one.lon, 30_000.0)
            assertTrue(eng.poiDetails(wide).values.filter { it.hasContact }.map { it.phone }.distinct().size > 1)
            assertNull(eng.detailsNamedNear(one.title, one.lat, one.lon, 30_000.0))
        }
    }

    /**
     * Batch G (item 20): the details height's address and category come with the details; an id is
     * read only while it is still the place (a recent from an older pack build may name another entry).
     */
    @Test
    fun addressCategoryAndAnIdThatIsStillThePlace() {
        val db = TestEnv.requireSearchDb()
        SearchEngine(db, null).use { eng ->
            val erie = TestEnv.points.getValue("erie")
            val r = eng.search("sheetz", erie.lat, erie.lon, 20).results.filter { it.kind == io.github.spysyweeb.beamline.search.SearchResult.Kind.POI }
            val d = eng.poiDetails(r.map { it.id })
            val one = r.first { d[it.id]?.hasContact == true && d[it.id]?.address != null }
            val det = d.getValue(one.id)
            assertTrue(det.address!!.contains(", PA "), "address in US form with the state")
            assertTrue(det.category != null)
            assertTrue(eng.isNamedNear(one.id, one.title, one.lat, one.lon, 100.0))
            assertTrue(eng.isNamedNear(one.id, one.title.lowercase(), one.lat + 50.0 / 111_195.0, one.lon, 100.0))
            assertTrue(!eng.isNamedNear(one.id, "Another Place", one.lat, one.lon, 100.0))                  // the id now names another place
            assertTrue(!eng.isNamedNear(one.id, one.title, one.lat + 500.0 / 111_195.0, one.lon, 100.0))   // or the same name elsewhere
            assertTrue(!eng.isNamedNear(-3L, one.title, one.lat, one.lon, 100.0))
            println("details of one Sheetz: $det")   // PV2: toString shows only which fields are there
        }
    }

    /**
     * 0.1.41 (docs/online-design.md 4.8; plan OP-5, AP-4): the details carry the entry's own name (its OSM name, for
     * a public changeset comment), its type (the kinds the arrival question asks about) and its point in the pack
     * (the move check of a confirmation or an offline edit), read in the same query as the rest. The Sheetz store
     * near Erie (the one with hours, phone and address) against its row in the pack's `entry` table.
     */
    @Test
    fun detailsCarryTheEntrysNameTypeAndPoint() {
        val db = TestEnv.requireSearchDb()
        val erie = TestEnv.points.getValue("erie")
        val (id, det) = SearchEngine(db, null).use { eng ->
            val r = eng.search("sheetz", erie.lat, erie.lon, 20).results.filter { it.kind == io.github.spysyweeb.beamline.search.SearchResult.Kind.POI }
            val d = eng.poiDetails(r.map { it.id })
            val one = r.first { d[it.id]?.hasContact == true && d[it.id]?.address != null }
            one.id to d.getValue(one.id)
        }
        SqlDb.openReadOnly(db).use { sql ->
            val row = sql.query("SELECT name, type, lat, lon FROM entry WHERE id = ?", listOf(id)).single()
            assertEquals(row[0] as String, det.name)
            assertTrue(det.name!!.contains("sheetz", ignoreCase = true))
            assertEquals(row[1] as String?, det.type)
            assertTrue(det.type != null, "the store's entry has a type")
            val lat = (row[2] as Number).toDouble()
            val lon = (row[3] as Number).toDouble()
            val dy = (det.lat!! - lat) * 111_195.0
            val dx = (det.lon!! - lon) * 111_195.0 * StrictMath.cos(Math.toRadians(lat))
            assertTrue(StrictMath.sqrt(dx * dx + dy * dy) <= 1.0, "the entry's own point")
        }
        // PV2: none of it in the text a log could print
        val s = det.toString()
        for (secret in listOfNotNull(det.name, det.type, det.lat?.toString(), det.lon?.toString())) assertTrue(secret !in s)
    }

    @Test
    fun postalAddressAndCategoryWords() {
        assertEquals("4662 West Ridge Road, Erie, PA 16506", SearchEngine.postalAddress("4662", "West Ridge Road", "Erie", "16506", "PA"))
        assertEquals("West Ridge Road, Erie, PA", SearchEngine.postalAddress(null, "West Ridge Road", "Erie", null, "PA"))
        assertEquals("12 Main Street, 16501", SearchEngine.postalAddress("12", "Main Street", null, "16501", null))
        assertNull(SearchEngine.postalAddress("12", null, "Erie", "16501", "PA"))
        assertEquals("Hairdresser", SearchEngine.categoryWords("shop=hairdresser"))
        assertEquals("Social facility", SearchEngine.categoryWords("amenity=social_facility"))
        assertNull(SearchEngine.categoryWords("shop=yes"))
        assertNull(SearchEngine.categoryWords(null))
    }
}
