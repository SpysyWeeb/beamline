package io.github.spysyweeb.beamline.search

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
