package io.github.spysyweeb.beamline.search

import io.github.spysyweeb.beamline.search.address.AddressIndex
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/** The facade's merge rule (not part of the Python reference). */
class SearchServiceTest {
    @Test
    fun addressesComeBackExactOrInterpolated() {
        SearchService.open(TestEnv.requireSearchDb(), TestEnv.requireAddressesDb()).use { s ->
            val erie = TestEnv.points.getValue("erie")
            val pgh = TestEnv.points.getValue("pittsburgh")
            // the engine locates the house itself: unchanged, exact point
            val a = s.search("4319 peach st", erie.lat, erie.lon)
            assertEquals("4319 Peach Street", a.results[0].title)
            assertEquals(AddressInfo.Accuracy.POINT, a.results[0].address!!.accuracy)
            // next run, part 1: the exact address ranks above every row without one (house_located)
            assertTrue(a.results[0].score >= s.engine.rk.wHouseLocated)
            assertTrue(a.results.drop(1).all { it.score < a.results[0].score })
            // US spellings (next run, part 1): the neighborhood's type label and the pack's built subtitle
            val terrace = a.results.first { it.title == "Peach Street Terrace" }
            assertEquals("Neighborhood", terrace.categoryLabel)
            assertTrue(terrace.subtitle.startsWith("Neighborhood in Erie"), terrace.subtitle)
            // no point in the pack: interpolated from TIGER ranges, put first (typed town matches)
            val b = s.search("150 Main St Brookville PA", pgh.lat, pgh.lon)
            println("150 Main St Brookville PA -> ${b.results.take(3).map { "${it.kind} ${it.title} ${it.address?.accuracy} ${it.distanceKm}" }}")
            assertEquals(SearchResult.Kind.ADDRESS, b.results[0].kind)
            assertEquals(AddressInfo.Accuracy.INTERPOLATED, b.results[0].address!!.accuracy)
            assertEquals(true, b.results[0].address!!.localityMatch)
            assertTrue(b.results[0].id < 0)
            // its score is on the engine's scale: just above the best engine row (rule S4 merges by score)
            if (b.results.size > 1) assertEquals(Py.round(b.results.drop(1).maxOf { it.score } + 0.0001, 4), b.results[0].score)
            assertEquals(b.results[0].id, s.search("150 Main St Brookville PA", pgh.lat, pgh.lon).results[0].id)
            // another state typed: the lookup's answer is only a guess, last
            val c = s.search("626 State St Erie OH", erie.lat, erie.lon)
            println("626 State St Erie OH -> ${c.results.map { "${it.kind} ${it.title} ${it.address}" }.take(10)}")
            val guess = c.results.last()
            if (guess.kind == SearchResult.Kind.ADDRESS) {
                assertTrue(guess.address!!.stateMismatch)
                assertFalse(guess.address!!.localityMatch!!)
            }
            // no house number: the engine's answer, untouched
            assertEquals(s.engine.search("sheetz", erie.lat, erie.lon).results.map { it.id },
                s.search("sheetz", erie.lat, erie.lon).results.map { it.id })
        }
    }

    /**
     * Item 55 (docs/next-round.md) on the PA pack, a public road far from the owner's report: OSM's "Wolf Run Road" in
     * Cogan House Township (Lycoming County) is "Wolfe Run Rd" in the census ranges, filed under ZIP 17771 (Trout Run);
     * no address point has 451. Typed without a town and ranked on that road, 451 is an estimated address along it, first.
     * The state has 17 streets of that name; one in Wolf Township of the same county also has a census range 401-499.
     */
    @Test
    fun houseNumberAlongTheStreetShown() {
        SearchService.open(TestEnv.requireSearchDbWithLines(), TestEnv.requireAddressesDb()).use { s ->
            val road = LatLon(41.430195, -77.17495)   // the street entry's point
            val edge = LatLon(41.43964, -77.18155)    // the middle of the census edge of 401-499
            val e = s.engine.search("451 wolf run road", road.lat, road.lon)
            assertEquals("Wolf Run Road", e.results[0].title)
            assertEquals(AddressInfo.Accuracy.STREET, e.results[0].address?.accuracy, "the engine alone: Street only")
            val r = s.search("451 wolf run road", road.lat, road.lon)
            val a = r.results[0]
            assertEquals(SearchResult.Kind.ADDRESS, a.kind)
            assertEquals("451 Wolf Run Road", a.title)
            assertEquals(AddressInfo.Accuracy.INTERPOLATED, a.address!!.accuracy)
            assertEquals("Trout Run, PA 17771", a.subtitle)
            assertTrue(Geo.haversineKm(a.lat, a.lon, edge.lat, edge.lon) < 0.3, "${Geo.haversineKm(a.lat, a.lon, edge.lat, edge.lon)} km from the edge")
            assertEquals(e.results.take(r.results.size - 1).map { it.id }, r.results.drop(1).map { it.id })
            // with the town of the census range typed: the lookup's own answer (the same house)
            val t = s.search("451 wolfe run rd trout run", road.lat, road.lon).results[0]
            assertEquals(AddressInfo.Accuracy.INTERPOLATED, t.address!!.accuracy)
            assertEquals(t.id, a.id)
            assertTrue(Geo.haversineKm(a.lat, a.lon, t.lat, t.lon) < 0.001)
            // (OSM gives that road the alternative name "Wolfe Run Road": the census street is one of its own names.) A
            // road with no such name: OSM's "Breinigsville Road" (Upper Macungie Township, Lehigh County) is "Breiningsville
            // Rd" in the census ranges (one letter more); no address point has 9161; the answer under the census's name
            val bRoad = LatLon(40.5372693, -75.6336933)
            val bEdge = LatLon(40.53944, -75.63757)   // the middle of the census edge of 9121-9199
            assertEquals(AddressInfo.Accuracy.STREET, s.engine.search("9161 breinigsville road", bRoad.lat, bRoad.lon).results[0].address?.accuracy)
            val b = s.search("9161 breinigsville road", bRoad.lat, bRoad.lon).results[0]
            assertEquals(AddressIndex.MATCH_ALONG, b.tier)
            assertEquals("9161 Breiningsville Rd", b.title)
            assertEquals(AddressInfo.Accuracy.INTERPOLATED, b.address?.accuracy)
            assertTrue(Geo.haversineKm(b.lat, b.lon, bEdge.lat, bEdge.lon) < 0.3, "${Geo.haversineKm(b.lat, b.lon, bEdge.lat, bEdge.lon)} km from the edge")
        }
    }

    /**
     * Item 55: what the lookup along the street costs on the PA pack, on top of the engine: house-number queries of 300
     * street entries spread over the state (every n-th entry with a line; the number made from its id), each ranked at the
     * street's own point, no town; first cold (another place each time), then the same answer again (as typing on, which
     * finds the municipality's streets in memory). Then the same queries with the street's municipality typed after them
     * (the lookup in that town, and along the street where it locates no house there): the whole address step. Prints
     * counts and times only (PV2: no query, no place).
     */
    @Test
    fun alongStreetCost() {
        val searchDb = TestEnv.requireSearchDbWithLines()
        SearchService.open(searchDb, TestEnv.requireAddressesDb()).use { s ->
            val db = io.github.spysyweeb.beamline.search.db.SqlDb.openReadOnly(searchDb)
            val rows = db.use { it.query("SELECT e.id, e.name, e.lat, e.lon, e.subtitle FROM entry e JOIN street_line l ON l.entry_id = e.id WHERE e.kind = 2 AND e.id % 997 = 0 LIMIT 300") }
            val lim = s.engine.resolveLimit(null)
            s.warmUp()
            for (r in rows.takeLast(20)) s.search("${100 + (r[0] as Long) % 900} ${r[1]}", r[2] as Double, r[3] as Double)   // the JIT
            val cold = ArrayList<Double>()
            val warm = ArrayList<Double>()
            var along = 0
            for (r in rows.dropLast(20)) {
                val lat = r[2] as Double
                val lon = r[3] as Double
                val e = s.engine.search("${100 + (r[0] as Long) % 900} ${r[1]}", lat, lon, lim)
                var t0 = System.nanoTime()
                val out = s.withAddress(e, lat, lon, lim)
                cold.add((System.nanoTime() - t0) / 1e6)
                t0 = System.nanoTime()
                assertEquals(out, s.withAddress(e, lat, lon, lim).copy(ms = out.ms))
                warm.add((System.nanoTime() - t0) / 1e6)
                if (out.results.firstOrNull()?.kind == SearchResult.Kind.ADDRESS && e.results.firstOrNull()?.kind != SearchResult.Kind.ADDRESS) along++
            }
            fun q(v: List<Double>, p: Double) = "%.2f".format(v.sorted()[((v.size - 1) * p).toInt()])
            for ((name, v) in listOf("cold" to cold, "again" to warm)) {
                println("item 55 along-street cost, $name, over ${v.size} queries: p50 ${q(v, 0.5)} ms, p90 ${q(v, 0.9)} ms, " +
                    "p99 ${q(v, 0.99)} ms, max ${q(v, 1.0)} ms")
            }
            println("item 55: an address row put first in $along of ${cold.size}")
            assertTrue(cold.size >= 100)
            val town = ArrayList<Double>()
            var alongInTown = 0
            for (r in rows.dropLast(20)) {
                val lat = r[2] as Double
                val lon = r[3] as Double
                val muni = (r[4] as String?)?.substringBefore(',')?.trim().orEmpty()
                val e = s.engine.search("${100 + (r[0] as Long) % 900} ${r[1]} $muni", lat, lon, lim)
                val t0 = System.nanoTime()
                val out = s.withAddress(e, lat, lon, lim)
                town.add((System.nanoTime() - t0) / 1e6)
                if (out.results.firstOrNull()?.tier == "along_street") alongInTown++
            }
            println("item 55 address step with the municipality typed, over ${town.size} queries: p50 ${q(town, 0.5)} ms, " +
                "p90 ${q(town, 0.9)} ms, p99 ${q(town, 0.99)} ms, max ${q(town, 1.0)} ms; the answer along the street first in $alongInTown")
        }
    }

    /**
     * Item 55 (review 2, 8): the same cost in a big municipality: the 40 longest streets of Philadelphia (Roosevelt
     * Boulevard, the expressways, Broad Street: long, several carriageways, many pieces), each ranked at its own point, no
     * town, once with a number outside every range (nothing found: every step runs) and once with a number made from its
     * id; first cold, then the same query again (typing on). Prints counts and times only (PV2: no query, no place).
     */
    @Test
    fun alongStreetCostInACity() {
        val searchDb = TestEnv.requireSearchDbWithLines()
        SearchService.open(searchDb, TestEnv.requireAddressesDb()).use { s ->
            val rows = io.github.spysyweeb.beamline.search.db.SqlDb.openReadOnly(searchDb).use {
                it.query("SELECT e.id, e.name, e.lat, e.lon FROM entry e JOIN street s ON s.entry_id = e.id JOIN street_line l ON l.entry_id = e.id " +
                    "WHERE e.kind = 2 AND e.subtitle LIKE 'Philadelphia, Philadelphia County%' ORDER BY s.length_m DESC LIMIT 40")
            }
            assertTrue(rows.size >= 20)
            val lim = s.engine.resolveLimit(null)
            s.warmUp()
            for ((name, number) in listOf<Pair<String, (Long) -> Long>>("outside every range" to { _ -> 99001L }, "a number from the id" to { id -> 100 + id % 8000 })) {
                val cold = ArrayList<Double>()
                val again = ArrayList<Double>()
                var first = 0
                for (r in rows) {
                    val lat = r[2] as Double
                    val lon = r[3] as Double
                    val e = s.engine.search("${number(r[0] as Long)} ${r[1]}", lat, lon, lim)
                    var t0 = System.nanoTime()
                    val out = s.withAddress(e, lat, lon, lim)
                    cold.add((System.nanoTime() - t0) / 1e6)
                    t0 = System.nanoTime()
                    s.withAddress(e, lat, lon, lim)
                    again.add((System.nanoTime() - t0) / 1e6)
                    if (out.results.firstOrNull()?.tier == AddressIndex.MATCH_ALONG) first++
                }
                fun q(v: List<Double>, p: Double) = "%.2f".format(v.sorted()[((v.size - 1) * p).toInt()])
                println("item 55 city cost, $name, over ${cold.size} queries: cold p50 ${q(cold, 0.5)} / p90 ${q(cold, 0.9)} / p99 ${q(cold, 0.99)} ms, " +
                    "again p50 ${q(again, 0.5)} / p90 ${q(again, 0.9)} / p99 ${q(again, 0.99)} ms; the answer along the street first in $first")
            }
        }
    }

    /**
     * Item 28 (docs/next-round.md): the front door of a large store, from the pack's poi_door table
     * (packbuilder/search/doors.py). A Walmart Supercenter near Erie whose front is known from its parking.
     */
    @Test
    fun frontDoors() {
        SearchService.open(TestEnv.requireSearchDb(), TestEnv.requireAddressesDb()).use { s ->
            TestEnv.need(s.engine.hasPoiDoor, "the search file has no poi_door table (built before 2026-11-21)")
            val lat = 42.0520658
            val lon = -80.0798629
            val d = s.doorNamedNear("Walmart Supercenter", lat, lon, 100.0)!!
            assertEquals(42.0518263, d.first, 2e-4)
            assertEquals(-80.0804005, d.second, 2e-4)
            // a place tapped on the map or saved from an older pack: near the entry's point, the same name
            assertEquals(d, s.doorNamedNear("walmart supercenter", lat + 0.0002, lon - 0.0003, 100.0))
            assertEquals(null, s.doorNamedNear("Walmart Supercenter", lat + 0.01, lon, 100.0), "no such place there")
            assertEquals(null, s.doorNamedNear("Target", lat, lon, 100.0), "another name")
            assertEquals(null, s.doorNamedNear("Krispy Kreme", 42.0525432, -80.0840009, 100.0), "a small building keeps its centroid")
            assertEquals(null, s.doorNamedNear("", lat, lon, 100.0))
        }
        // a pack built before the table existed: no door, no error
        val old = java.io.File(TestEnv.repoRoot, "data/r3-packs/batchF/us-pa/search.sqlite")
        if (old.isFile) {
            SearchService.open(old.path, null).use { s ->
                assertFalse(s.engine.hasPoiDoor)
                assertEquals(null, s.doorNamedNear("Walmart Supercenter", 42.0520658, -80.0798629, 100.0))
            }
        }
    }

    /** limit as query.py's `limit or top_n`; negative refused (review of 2026-11-15: limit 0 threw). */
    @Test
    fun limits() {
        SearchService.open(TestEnv.requireSearchDb(), TestEnv.requireAddressesDb()).use { s ->
            val erie = TestEnv.points.getValue("erie")
            val topN = s.engine.rk.topN
            for (q in listOf("sheetz", "4319 peach st erie", "150 Main St Brookville PA")) {
                val default = s.search(q, erie.lat, erie.lon).results.map { it.id }
                assertEquals(default, s.search(q, erie.lat, erie.lon, 0).results.map { it.id }, q)
                assertEquals(default, s.search(q, erie.lat, erie.lon, topN).results.map { it.id }, q)
                assertEquals(1, s.search(q, erie.lat, erie.lon, 1).results.size, q)
                assertEquals(2, s.search(q, erie.lat, erie.lon, 2).results.size, q)
                assertThrows<IllegalArgumentException> { s.search(q, erie.lat, erie.lon, -1) }
                assertThrows<IllegalArgumentException> { s.engine.search(q, erie.lat, erie.lon, -1) }
            }
            assertEquals(topN, s.engine.search("sheetz", erie.lat, erie.lon, 0).results.size)
        }
    }

    /**
     * Over-long input is cut by the engine itself to ranking.json max_query_chars code points (rule
     * S5; review: an 80,000-letter word ran out of a 1 GB heap); the service adds no cap of its own.
     */
    @Test
    fun longInputIsCapped() {
        SearchService.open(TestEnv.requireSearchDb(), TestEnv.requireAddressesDb()).use { s ->
            val erie = TestEnv.points.getValue("erie")
            val max = s.engine.rk.maxQueryChars
            assertEquals(256, max)
            for (text in listOf("ab".repeat(40_000), "4319 peach st " + "x".repeat(100_000), "sheetz ".repeat(20_000),
                "a".repeat(max - 1) + "😀" + "b".repeat(50), "😀".repeat(400))) {
                val t0 = System.nanoTime()
                val r = s.search(text, erie.lat, erie.lon)
                val ms = (System.nanoTime() - t0) / 1e6
                println("${text.length} chars -> ${r.query.codePointCount(0, r.query.length)} code points searched, ${r.results.size} results in ${"%.0f".format(ms)} ms")
                assertEquals(max, r.query.codePointCount(0, r.query.length))
                assertTrue(text.startsWith(r.query))
                assertFalse(Character.isHighSurrogate(r.query.last()))
                assertTrue(ms < 10_000, "took $ms ms")
            }
            // the emoji is code point 256: kept whole
            assertTrue(s.search("a".repeat(max - 1) + "😀" + "b".repeat(50), erie.lat, erie.lon).query.endsWith("😀"))
            // the cut text gives the engine's answer for the same 256 code points, through the service and the engine alike
            val long = "sheetz " + "z".repeat(1000)
            val cut = long.substring(0, max)
            assertEquals(s.engine.search(cut, erie.lat, erie.lon).results.map { it.id },
                s.search(long, erie.lat, erie.lon).results.map { it.id })
            assertEquals(s.engine.search(cut, erie.lat, erie.lon).results.map { it.id },
                s.engine.search(long, erie.lat, erie.lon).results.map { it.id })
        }
    }
}
