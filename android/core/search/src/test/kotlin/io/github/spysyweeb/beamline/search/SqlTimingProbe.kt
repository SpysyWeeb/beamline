package io.github.spysyweeb.beamline.search

import io.github.spysyweeb.beamline.search.db.SqlDb
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test

/**
 * Not a test: a timing probe that separates SQLite time from engine time for heavy queries.
 * Runs only with -Pprobe (BEAMLINE_PROBE=1). Compare with conformance/sql_probe.py (Python sqlite3).
 */
class SqlTimingProbe {
    /** The house-number queries of :core:search-bench's SearchBench.HOUSE_QUERIES (measure.py's set). */
    private val HOUSE_QUERIES = listOf("1 main st" to "erie", "100 main st" to "erie", "500 church st" to "erie",
        "123 n main st" to "erie", "4319 peach st" to "erie", "1600 market st philadelphia" to "pittsburgh",
        "600 grant street pittsburgh" to "erie", "72 s main st wilkes barre" to "wilkes_barre")

    @Test
    fun probe() {
        assumeTrue(System.getenv("BEAMLINE_PROBE") == "1", "probe only on request")
        val dbPath = TestEnv.requireSearchDb()
        val cols = "e.id, e.kind, e.name, e.name_key, e.alt_keys, e.type, e.lat, e.lon, e.importance, e.muni_id, e.county_id, e.brand_id, e.subtitle"
        val phl = TestEnv.points.getValue("philadelphia")
        val c = StrictMath.cos(Math.toRadians(phl.lat)); val k = c * c
        val sqlDist = "SELECT $cols FROM entry_fts f JOIN entry e ON e.id = f.rowid WHERE entry_fts MATCH ? ORDER BY (e.lat - ?) * (e.lat - ?) + (e.lon - ?) * (e.lon - ?) * ? LIMIT ?"
        val sqlCount = "SELECT count(*) FROM entry_fts f JOIN entry e ON e.id = f.rowid WHERE entry_fts MATCH ?"
        SqlDb.openReadOnly(dbPath).use { db ->
            for (word in listOf("\"road\"*", "\"street\"*", "\"walmart\"*")) {
                repeat(2) { db.query(sqlDist, word, phl.lat, phl.lat, phl.lon, phl.lon, k, 400) }
                val t = LongArray(5) { val t0 = System.nanoTime(); db.query(sqlDist, word, phl.lat, phl.lat, phl.lon, phl.lon, k, 400); System.nanoTime() - t0 }
                val tc = LongArray(5) { val t0 = System.nanoTime(); db.query(sqlCount, word); System.nanoTime() - t0 }
                val n = db.query(sqlCount, word)[0][0]
                println("PROBE $word matches=$n  dist-sorted top 400: ${t.sorted()[2] / 1e6} ms   count only: ${tc.sorted()[2] / 1e6} ms")
            }
        }
        SearchEngine(dbPath, TestEnv.addressesDb.ifEmpty { null }).use { eng ->
            for ((q, pt) in HOUSE_QUERIES) {
                val p = TestEnv.points.getValue(pt)
                eng.search(q, p.lat, p.lon)
                val t = LongArray(5) { val t0 = System.nanoTime(); eng.search(q, p.lat, p.lon); System.nanoTime() - t0 }
                println("PROBE house '$q' (slim attached) median ${t.sorted()[2] / 1e6} ms")
            }
        }
        SearchEngine(dbPath).use { eng ->
            for (q in listOf("road", "street", "walmart", "gas", "sheetz")) {
                repeat(2) { eng.search(q, phl.lat, phl.lon) }
                val t = LongArray(5) { val t0 = System.nanoTime(); eng.search(q, phl.lat, phl.lon); System.nanoTime() - t0 }
                println("PROBE engine '$q' @philadelphia median ${t.sorted()[2] / 1e6} ms")
            }
        }
    }
}
