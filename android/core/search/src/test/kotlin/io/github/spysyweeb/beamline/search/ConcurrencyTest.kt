package io.github.spysyweeb.beamline.search

import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Review findings of 2026-11-15 (docs/experiments/10-search-on-device.md, "Review and fixes"):
 *  - one engine (or service) shared by several threads must give the single-thread answers and
 *    finish, not spin forever in SQLite (the module serialises access to its connection);
 *  - several engines, each with its own connection, reading the same file in parallel must not
 *    fail with SQLITE_BUSY ("database is locked"): on Windows a reader briefly takes SQLite's
 *    PENDING byte while it acquires its shared lock, so parallel readers can collide; the busy
 *    timeout (as Python's sqlite3 default of 5 s) waits instead of failing.
 * The worker threads are daemon threads and the wait is bounded, so a regression fails the test
 * instead of hanging the build.
 */
class ConcurrencyTest {
    private val queries = listOf("wal", "rest stop", "sheetz", "pizza near erie", "erie city hall", "gas", "cvs",
        "4319 peach st", "walmart", "peach st", "150 Main St Brookville PA")

    private class Outcome(val ok: Int, val mismatches: Int, val errors: List<String>, val finished: Boolean)

    private fun runThreads(threads: Int, perThread: Int, timeoutS: Long, body: (thread: Int, i: Int) -> Boolean): Outcome {
        val ok = AtomicInteger()
        val bad = AtomicInteger()
        val errors = ConcurrentLinkedQueue<String>()
        val latch = CountDownLatch(threads)
        repeat(threads) { t ->
            Thread({
                try {
                    repeat(perThread) { i ->
                        try {
                            if (body(t, i)) ok.incrementAndGet() else bad.incrementAndGet()
                        } catch (e: Throwable) {
                            if (errors.size < 5) errors.add("${e::class.qualifiedName}: ${e.message?.take(160)}")
                        }
                    }
                } finally {
                    latch.countDown()
                }
            }, "search-test-$t").apply { isDaemon = true }.start()
        }
        val finished = latch.await(timeoutS, TimeUnit.SECONDS)
        return Outcome(ok.get(), bad.get(), errors.toList(), finished)
    }

    @Test
    fun oneServiceSharedByFourThreads() {
        SearchService.open(TestEnv.requireSearchDb(), TestEnv.requireAddressesDb()).use { s ->
            val erie = TestEnv.points.getValue("erie")
            val expected = queries.associateWith { q -> s.search(q, erie.lat, erie.lon, 10).results.map { it.id } }
            val t0 = System.nanoTime()
            val out = runThreads(4, 40, 180) { t, i ->
                val q = queries[(i + t) % queries.size]
                s.search(q, erie.lat, erie.lon, 10).results.map { it.id } == expected[q]
            }
            println("4 threads x 40 queries on one SearchService: finished=${out.finished} ok=${out.ok} " +
                "mismatches=${out.mismatches} errors=${out.errors} in ${(System.nanoTime() - t0) / 1e9} s")
            assertTrue(out.finished, "threads did not finish within 180 s (shared connection not serialised?)")
            assertEquals(emptyList<String>(), out.errors)
            assertEquals(160, out.ok)
        }
    }

    @Test
    fun oneAddressIndexSharedByFourThreads() {
        SearchService.open(TestEnv.requireSearchDb(), TestEnv.requireAddressesDb()).use { s ->
            val idx = s.addresses!!
            val texts = listOf("626 State St Erie PA", "150 Main St Brookville PA", "1 Saint Marys St Erie",
                "123 n mian st, erie pa 16501", "414 Grant St Pittsburgh PA")
            val expected = texts.associateWith { idx.geocode(it) }
            val out = runThreads(4, 15, 180) { t, i ->
                val q = texts[(i + t) % texts.size]
                idx.geocode(q) == expected[q]
            }
            println("4 threads x 15 lookups on one AddressIndex: finished=${out.finished} ok=${out.ok} mismatches=${out.mismatches} errors=${out.errors}")
            assertTrue(out.finished)
            assertEquals(emptyList<String>(), out.errors)
            assertEquals(60, out.ok)
        }
    }

    /** Every connection waits for file locks as Python's sqlite3 does (5 s); the driver's own default is 0. */
    @Test
    fun connectionsHaveABusyTimeout() {
        val db = TestEnv.requireSearchDb()
        val raw = androidx.sqlite.driver.bundled.BundledSQLiteDriver().open(io.github.spysyweeb.beamline.search.db.SqlDb.readOnlyUri(java.io.File(db)),
            androidx.sqlite.driver.bundled.SQLITE_OPEN_READONLY or androidx.sqlite.driver.bundled.SQLITE_OPEN_URI)
        val driverDefault = raw.prepare("PRAGMA busy_timeout").use { it.step(); it.getLong(0) }
        raw.close()
        io.github.spysyweeb.beamline.search.db.SqlDb.openReadOnly(db).use { assertEquals(5000L, it.first("PRAGMA busy_timeout")!![0]) }
        println("busy timeout: bundled driver default $driverDefault ms, SqlDb ${io.github.spysyweeb.beamline.search.db.SqlDb.BUSY_TIMEOUT_MS} ms")
    }

    @Test
    fun parallelReadersWithTheirOwnConnectionsAreNeverBusy() {
        val db = TestEnv.requireSearchDb()
        val erie = TestEnv.points.getValue("erie")
        val engines = List(6) { SearchEngine(db) }
        try {
            val expected = queries.associateWith { q -> engines[0].search(q, erie.lat, erie.lon, 10).results.map { it.id } }
            val t0 = System.nanoTime()
            val out = runThreads(6, 60, 300) { t, i ->
                val q = queries[(i * 7 + t) % queries.size]
                engines[t].search(q, erie.lat, erie.lon, 10).results.map { it.id } == expected[q]
            }
            println("6 engines (own connections) x 60 queries in parallel: finished=${out.finished} ok=${out.ok} " +
                "mismatches=${out.mismatches} errors=${out.errors} in ${(System.nanoTime() - t0) / 1e9} s")
            assertTrue(out.finished)
            assertEquals(emptyList<String>(), out.errors)
            assertEquals(360, out.ok)
        } finally {
            engines.forEach { it.close() }
        }
    }
}
