package io.github.spysyweeb.beamline.search

import io.github.spysyweeb.beamline.api.data.InstalledSet
import io.github.spysyweeb.beamline.api.data.PackInfo
import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.search.address.AddressNormalizer
import java.io.File
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The fingerprint check at open, SearchHub and MultiPackSearch (docs/architecture.md 2.11, 3.8
 * S3-S5, 5.1 S "Done when"). Pure tests first; then track P's PA and OH search files of
 * data/r3-packs (announced on the board in p-search-files.md), skipped (or failed in strict mode)
 * when they are missing.
 */
class MultiPackTest {
    companion object {
        val r3Packs = File(TestEnv.repoRoot, "data/r3-packs")
        val paFile = File(r3Packs, "pa-search.sqlite")
        val ohFile = File(r3Packs, "oh-search.sqlite")
        const val P_FINGERPRINT = "97c8d281115ec4d4b186051b18efe3f15bb5b1c74d60902854665f83d228d73a"
        const val RANKING = "989553d1507d63aebc9c4ab0e52d22abd789f541130070693483a73ef7dfbbfb"
        val PA_BOUNDS = Bounds(-80.52109, 39.71977, -74.68967, 42.51613)   // the search meta bbox
        val OH_BOUNDS = Bounds(-84.82034, 38.40314, -80.51896, 42.32469)

        val ERIE = GeoPoint(42.1292, -80.0851)
        val CONNEAUT = GeoPoint(41.9476, -80.5545)
        val HARRISBURG = GeoPoint(40.2732, -76.8867)
        val CLEVELAND = GeoPoint(41.4993, -81.6944)
        val LONDON = GeoPoint(51.5074, -0.1278)
        val LINE = GeoPoint(41.2278, -80.5191)   // South State Line Road, in both packs

        fun pack(id: String, state: String, bounds: Bounds, search: File?, ranking: String? = RANKING, problem: String? = null) = PackInfo(
            packId = id, name = state, state = state, osmSnapshot = "2026-11-14", bounds = bounds, attribution = "OpenStreetMap",
            folder = "packs/$id", mapFile = "packs/$id/map.pmtiles", mapMinZoom = 0, mapMaxZoom = 14,
            searchFile = search?.absolutePath, addressesFile = null, addressesSkipped = true, searchProblem = problem,
            searchRankingSha256 = ranking, inRoutingSet = true, verified = true, bytesOnDisk = 0,
        )

        fun row(id: Long, title: String, lat: Double, lon: Double, score: Double, kind: SearchResult.Kind = SearchResult.Kind.POI) =
            SearchResult(id, kind, title, "", null, null, lat, lon, null, null, null, score, "test", null)

        fun answer(vararg rows: SearchResult) = SearchResponse("q", emptyList(), rows.toList(), 1.0, false, false)

        fun needPacks() = TestEnv.need(paFile.isFile && ohFile.isFile, "data/r3-packs/{pa,oh}-search.sqlite missing (track P's first wave)")
    }

    // ---------------------------------------------------------------- fingerprint
    @Test
    fun fingerprintOfThisPortEqualsTheBuilders() {
        assertEquals(1482, AddrKeyFingerprint.bundledSpellings().size)
        assertEquals(P_FINGERPRINT, AddrKeyFingerprint.app())
        val lines = File(r3Packs, "key-fingerprint-lines.txt")
        if (lines.isFile) {
            val n = AddressNormalizer()
            val mine = AddrKeyFingerprint.lines(AddrKeyFingerprint.bundledSpellings()) { n.streetKey(it) }
            val theirs = lines.readText(Charsets.UTF_8).split("\n").dropLast(1).map { it + "\n" }
            assertEquals(theirs.size, mine.size)
            for (i in mine.indices) assertEquals(theirs[i], mine[i], "line ${i + 1}")
        }
        // any change of the key changes the fingerprint
        val n = AddressNormalizer()
        assertFalse(P_FINGERPRINT == AddrKeyFingerprint.compute(AddrKeyFingerprint.bundledSpellings()) { n.streetKey(it) + " " })
    }

    // ---------------------------------------------------------------- S3
    @Test
    fun packsAsked() {
        val pa = pack("us-pa", "PA", PA_BOUNDS, null)
        val oh = pack("us-oh", "OH", OH_BOUNDS, null)
        val both = listOf(oh, pa)
        fun ask(p: GeoPoint?) = MultiPackSearch.packsToAsk(both, p, 50.0).map { it.packId }
        assertEquals(listOf("us-pa", "us-oh"), ask(ERIE))            // Ohio is 36 km from Erie: asked, Pennsylvania first
        assertEquals(listOf("us-oh", "us-pa"), ask(CONNEAUT))        // in Ohio, 3 km from the line
        assertEquals(listOf("us-pa"), ask(HARRISBURG))
        assertEquals(listOf("us-oh"), ask(CLEVELAND))                // 98 km from Pennsylvania's box
        assertEquals(listOf("us-pa"), ask(LONDON))                   // none within 50 km: the nearest
        assertEquals(listOf("us-oh", "us-pa"), ask(null))            // no position: all, by pack id
        assertEquals(emptyList<String>(), MultiPackSearch.packsToAsk(emptyList(), ERIE, 50.0))
        assertEquals(0.0, MultiPackSearch.distanceToBoundsKm(ERIE, pa))
        assertTrue(MultiPackSearch.distanceToBoundsKm(ERIE, oh) in 30.0..40.0)
    }

    // ---------------------------------------------------------------- S4
    @Test
    fun mergeByScoreOrPackFirst() {
        val pa = pack("us-pa", "PA", PA_BOUNDS, null)
        val oh = pack("us-oh", "OH", OH_BOUNDS, null)
        val a = answer(row(1, "Sheetz", 42.10, -80.10, 0.9), row(2, "Citgo", 42.11, -80.11, 0.5))
        val b = answer(row(3, "Shell", 41.95, -80.55, 0.7), row(4, "Sunoco", 41.96, -80.56, 0.4))
        val (byScore, merged) = MultiPackSearch.merge(listOf(pa to a, oh to b), 100.0, 10)
        assertTrue(merged)
        assertEquals(listOf(1L, 3L, 2L, 4L), byScore.map { it.result.id })
        assertEquals(listOf("us-pa", "us-oh", "us-pa", "us-oh"), byScore.map { it.packId })
        // another ranking hash (or an unknown one): the pack containing the position first, then the other
        val ohOther = pack("us-oh", "OH", OH_BOUNDS, null, ranking = "0".repeat(64))
        val (first, merged2) = MultiPackSearch.merge(listOf(pa to a, ohOther to b), 100.0, 10)
        assertFalse(merged2)
        assertEquals(listOf(1L, 2L, 3L, 4L), first.map { it.result.id })
        assertFalse(MultiPackSearch.merge(listOf(pa to a, pack("us-oh", "OH", OH_BOUNDS, null, ranking = null) to b), 100.0, 10).second)
        // limit
        assertEquals(3, MultiPackSearch.merge(listOf(pa to a, oh to b), 100.0, 3).first.size)
    }

    @Test
    fun duplicatesKeepTheHigherScore() {
        val pa = pack("us-pa", "PA", PA_BOUNDS, null)
        val oh = pack("us-oh", "OH", OH_BOUNDS, null)
        val north80m = 41.2278 + 80.0 / 111195.0
        val a = answer(row(1, "South State Line Road", 41.2278, -80.5191, 0.8, SearchResult.Kind.STREET))
        val b = answer(
            row(2, "SOUTH STATE LINE ROAD!", north80m, -80.5191, 0.95, SearchResult.Kind.STREET),                       // same folded title, 80 m
            row(3, "South State Line Road", 41.2278 - 150.0 / 111195.0, -80.5191, 0.7, SearchResult.Kind.STREET),        // 150 m: another
            row(4, "South State Line Road", 41.2278, -80.5191, 0.6, SearchResult.Kind.POI),                              // another kind
        )
        val (rows, _) = MultiPackSearch.merge(listOf(pa to a, oh to b), 100.0, 10)
        assertEquals(listOf(2L, 3L, 4L), rows.map { it.result.id })
        // pack-first: the higher score replaces the first seen in its place
        val (rows2, _) = MultiPackSearch.merge(listOf(pa to a, pack("us-oh", "OH", OH_BOUNDS, null, ranking = "x") to b), 100.0, 10)
        assertEquals(listOf(2L, 3L, 4L), rows2.map { it.result.id })
        assertEquals("us-oh", rows2[0].packId)
    }

    // ---------------------------------------------------------------- SearchHub on the real PA and OH files
    private fun set(gen: Long, vararg packs: PackInfo) = InstalledSet(generation = gen, scanned = true, packs = packs.toList())

    @Test
    fun nearErieBothStatesAnswer() {
        needPacks()
        SearchHub().use { hub ->
            val s = set(1, pack("us-pa", "PA", PA_BOUNDS, paFile), pack("us-oh", "OH", OH_BOUNDS, ohFile))
            val r = hub.search(s, "conneaut", ERIE)
            println("conneaut near Erie -> ${r.results.map { "${it.packId} ${it.result.title} ${"%.3f".format(it.result.score)}" }}")
            assertEquals(listOf("us-pa", "us-oh"), r.packsAsked)
            assertTrue(r.mergedByScore)
            assertTrue(r.results.any { it.packId == "us-oh" && it.result.title == "Conneaut" && it.result.kind == SearchResult.Kind.PLACE })
            assertTrue(r.results.any { it.packId == "us-pa" })
            assertEquals(r.results.sortedByDescending { it.result.score }, r.results)
            assertEquals(10, r.results.size)
            assertEquals(emptyMap<String, String>(), hub.problems)
            // Harrisburg: only Pennsylvania
            assertEquals(listOf("us-pa"), hub.search(s, "sheetz", HARRISBURG).packsAsked)
        }
    }

    @Test
    fun duplicateAcrossTheLineIsMerged() {
        needPacks()
        SearchHub().use { hub ->
            val s = set(1, pack("us-pa", "PA", PA_BOUNDS, paFile), pack("us-oh", "OH", OH_BOUNDS, ohFile))
            val r = hub.search(s, "south state line road", LINE)
            println("south state line road -> ${r.results.take(4).map { "${it.packId} ${it.result.title} ${it.result.lat},${it.result.lon} ${it.result.score}" }}")
            val near = r.results.filter { Geo.haversineKm(it.result.lat, it.result.lon, LINE.lat, LINE.lon) < 0.2 }
            assertEquals(1, near.size, "the two packs' South State Line Road (99 m apart) are one row")
            assertEquals("us-pa", near[0].packId)   // PA's has the higher score (1.6926 against 1.6879 in query.py)
            // each pack alone has it
            val paOnly = hub.search(set(2, pack("us-pa", "PA", PA_BOUNDS, paFile)), "south state line road", LINE)
            val ohOnly = hub.search(set(3, pack("us-oh", "OH", OH_BOUNDS, ohFile)), "south state line road", LINE)
            assertEquals("South State Line Road", paOnly.results[0].result.title)
            assertEquals("South State Line Road", ohOnly.results[0].result.title)
        }
    }

    @Test
    fun otherRankingFallsBackToPackFirst() {
        needPacks()
        SearchHub().use { hub ->
            val s = set(1, pack("us-pa", "PA", PA_BOUNDS, paFile), pack("us-oh", "OH", OH_BOUNDS, ohFile, ranking = "1".repeat(64)))
            for (q in listOf("gas", "conneaut", "state line")) {
                val r = hub.search(s, q, CONNEAUT)
                assertFalse(r.mergedByScore)
                assertEquals(listOf("us-oh", "us-pa"), r.packsAsked)
                val firstPa = r.results.indexOfFirst { it.packId == "us-pa" }
                if (firstPa >= 0) assertTrue(r.results.drop(firstPa).all { it.packId == "us-pa" }, "$q: Ohio's rows first, then Pennsylvania's")
                // Ohio's rows are Ohio's own answer, in its own order (not interleaved by score)
                val ohOnly = hub.search(set(5, pack("us-oh", "OH", OH_BOUNDS, ohFile, ranking = "1".repeat(64))), q, CONNEAUT)
                val ohRows = r.results.filter { it.packId == "us-oh" }.map { it.result.id }
                assertEquals(ohOnly.results.map { it.result.id }.take(ohRows.size), ohRows, q)
                assertTrue(ohRows.isNotEmpty(), q)
            }
            // merged by score, Pennsylvania's best "conneaut" rows come between Ohio's
            val same = hub.search(set(6, pack("us-pa", "PA", PA_BOUNDS, paFile), pack("us-oh", "OH", OH_BOUNDS, ohFile)), "conneaut", CONNEAUT)
            assertTrue(same.mergedByScore)
            assertEquals(same.results.sortedByDescending { it.result.score }, same.results)
        }
    }

    @Test
    fun fingerprintMismatchIsRefused() {
        needPacks()
        // the expected fingerprint of another normaliser: both packs refused, nothing searched
        SearchHub(expectedFingerprint = "0".repeat(64)).use { hub ->
            val s = set(1, pack("us-pa", "PA", PA_BOUNDS, paFile), pack("us-oh", "OH", OH_BOUNDS, ohFile))
            val r = hub.search(s, "sheetz", ERIE)
            assertEquals(emptyList<String>(), r.packsAsked)
            assertEquals(emptyList<PackResult>(), r.results)
            assertEquals(mapOf("us-pa" to SearchHub.OTHER_NORMALISER, "us-oh" to SearchHub.OTHER_NORMALISER), hub.problems)
            assertFalse(hub.hasSearchData(s))
            assertEquals("sheetz", r.query)
        }
        // a search part without a fingerprint (the round-2 build in data/search) is refused; the other pack answers
        val old = File(TestEnv.repoRoot, "data/search/pa-search.sqlite")
        if (old.isFile) {
            SearchHub().use { hub ->
                val s = set(1, pack("us-pa", "PA", PA_BOUNDS, old), pack("us-oh", "OH", OH_BOUNDS, ohFile))
                val r = hub.search(s, "sheetz", ERIE)
                assertEquals(listOf("us-oh"), r.packsAsked)
                assertEquals(mapOf("us-pa" to SearchHub.NO_FINGERPRINT), hub.problems)
                assertTrue(hub.hasSearchData(s))
            }
        }
        // P's own searchProblem, a missing search file and a file that cannot be opened
        SearchHub().use { hub ->
            val s = set(1, pack("us-pa", "PA", PA_BOUNDS, paFile, problem = "built with another address normaliser"),
                pack("us-oh", "OH", OH_BOUNDS, null), pack("us-xx", "XX", OH_BOUNDS, File(r3Packs, "no-such-file.sqlite")))
            assertFalse(hub.hasSearchData(s))
            assertEquals(mapOf("us-xx" to SearchHub.CANNOT_OPEN), hub.problems)
            assertEquals(emptyList<PackResult>(), hub.search(s, "erie", ERIE).results)
        }
    }

    /**
     * problems and openGeneration are read without waiting for a call that runs on the search
     * thread (review finding: a main-thread reader would block for the length of a query). No pack
     * file needed: the opener stands in for a slow open.
     */
    @Test
    fun problemsAreReadWhileACallRuns() {
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val hub = SearchHub(expectedFingerprint = null, warmUp = false, opener = { p ->
            if (p.packId == "us-oh") throw java.io.IOException("cannot open")
            entered.countDown()
            release.await(20, java.util.concurrent.TimeUnit.SECONDS)
            throw java.io.IOException("cannot open either")
        })
        val s = set(7, pack("us-oh", "OH", OH_BOUNDS, File(r3Packs, "a.sqlite")), pack("us-pa", "PA", PA_BOUNDS, File(r3Packs, "b.sqlite")))
        val call = Thread { hub.prepare(s) }.apply { start() }
        val reader = java.util.concurrent.Executors.newSingleThreadExecutor()
        try {
            assertTrue(entered.await(10, java.util.concurrent.TimeUnit.SECONDS))
            // prepare is inside the slow open of us-pa now; the reads must not wait for it
            val read = reader.submit<Pair<Map<String, String>, Long?>> { hub.problems to hub.openGeneration }
            val (problems, gen) = read.get(2, java.util.concurrent.TimeUnit.SECONDS)
            assertEquals(mapOf("us-oh" to SearchHub.CANNOT_OPEN), problems)
            assertEquals(7L, gen)
        } finally {
            release.countDown()
            call.join(10_000)
            reader.shutdownNow()
        }
        assertEquals(mapOf("us-oh" to SearchHub.CANNOT_OPEN, "us-pa" to SearchHub.CANNOT_OPEN), hub.problems)
        hub.close()
        assertEquals(emptyMap<String, String>(), hub.problems)
    }

    @Test
    fun oneHandlePerGenerationOldOnesClosed() {
        needPacks()
        val opened = ArrayList<SearchService>()
        val hub = SearchHub(opener = { p -> SearchService.open(p.searchFile!!, null).also { opened.add(it) } })
        val pa = pack("us-pa", "PA", PA_BOUNDS, paFile)
        val oh = pack("us-oh", "OH", OH_BOUNDS, ohFile)
        hub.prepare(set(1, pa, oh))
        assertEquals(2, opened.size)                  // opened and warmed up at once
        assertEquals(1L, hub.openGeneration)
        hub.search(set(1, pa, oh), "sheetz", ERIE)
        hub.search(set(1, pa, oh), "gas", ERIE)
        assertEquals(2, opened.size)                  // reused within the generation
        // generation 2 (Ohio removed): the old handles are closed before the next call runs
        val r = hub.search(set(2, pa), "sheetz", ERIE)
        assertEquals(listOf("us-pa"), r.packsAsked)
        assertEquals(3, opened.size)
        assertThrows<IllegalStateException> { opened[0].search("sheetz") }
        assertThrows<IllegalStateException> { opened[1].search("sheetz") }
        opened[2].search("sheetz")
        hub.close()
        assertThrows<IllegalStateException> { opened[2].search("sheetz") }
        assertNull(hub.openGeneration)
    }

    /** Track P's format-3 pack folders (p-archives.md): search and addresses both carry the fingerprint; house numbers work. */
    @Test
    fun formatThreePackFoldersWithHouseNumbers() {
        val pa = File(r3Packs, "us-pa")
        val oh = File(r3Packs, "us-oh")
        TestEnv.need(File(pa, "addresses.sqlite").isFile && File(oh, "addresses.sqlite").isFile, "data/r3-packs/us-{pa,oh}/ missing (p-archives.md)")
        val packs = listOf(
            pack("us-pa", "PA", PA_BOUNDS, File(pa, "search.sqlite")).copy(addressesFile = File(pa, "addresses.sqlite").absolutePath, addressesSkipped = false),
            pack("us-oh", "OH", OH_BOUNDS, File(oh, "search.sqlite")).copy(addressesFile = File(oh, "addresses.sqlite").absolutePath, addressesSkipped = false),
        )
        SearchHub().use { hub ->
            val s = set(1, *packs.toTypedArray())
            hub.prepare(s)
            assertEquals(emptyMap<String, String>(), hub.problems)
            val r = hub.search(s, "4319 peach st", ERIE)
            assertEquals("4319 Peach Street", r.results[0].result.title)
            assertEquals(AddressInfo.Accuracy.POINT, r.results[0].result.address!!.accuracy)
            assertEquals("4319", r.houseNumber)
            // next run, part 1: the exact address in Ohio first, above Pennsylvania's estimated
            // "247 Main St, Conneautville" (the lookup's own score once won the merge by score)
            for (q in listOf("247 main st conneaut", "247 Main Street, Conneaut")) {
                val c = hub.search(s, q, ERIE)
                println("$q -> ${c.results.take(4).map { "${it.packId} ${it.result.title} | ${it.result.subtitle} ${it.result.address?.accuracy} ${it.result.score}" }}")
                assertEquals("us-oh", c.results[0].packId, q)
                assertEquals("247 Main Street", c.results[0].result.title, q)
                assertEquals(AddressInfo.Accuracy.POINT, c.results[0].result.address!!.accuracy, q)
                val est = c.results.indexOfFirst { it.result.address?.accuracy == AddressInfo.Accuracy.INTERPOLATED }
                assertTrue(est != 0, q)
            }
            // an address file keyed with another normaliser would be refused: simulated by expecting another fingerprint
            SearchHub(expectedFingerprint = "f".repeat(64)).use { other ->
                other.prepare(s)
                assertEquals(setOf("us-pa", "us-oh"), other.problems.keys)
            }
        }
    }

    @Test
    fun capAndWarmUp() {
        needPacks()
        SearchHub().use { hub ->
            val s = set(1, pack("us-pa", "PA", PA_BOUNDS, paFile))
            val t0 = System.nanoTime()
            hub.prepare(s)
            val prepMs = (System.nanoTime() - t0) / 1e6
            val t1 = System.nanoTime()
            val r = hub.search(s, "sheetz " + "z".repeat(400), ERIE)
            val firstMs = (System.nanoTime() - t1) / 1e6
            println("open + warm-up of the PA search file: ${"%.0f".format(prepMs)} ms; first query after it: ${"%.0f".format(firstMs)} ms (JVM, file cache warm)")
            assertEquals(256, r.query.length)
        }
    }
}
