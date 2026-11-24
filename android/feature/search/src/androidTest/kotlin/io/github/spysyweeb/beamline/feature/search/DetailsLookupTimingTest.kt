package io.github.spysyweeb.beamline.feature.search

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import io.github.spysyweeb.beamline.search.SearchEngine
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Batch G (docs/next-round.md item 20): how long the details lookup of a place without a search id
 * takes on the device ([SearchEngine.detailsNamedNear], rule S10), for a sample of named points of
 * the map tiles (a POI tapped on the map). Runs only when the files are there (skipped otherwise):
 * the test app's files folder `lookup/` (written with `run-as`) holds `search.sqlite` (a state's search part) and
 * `sample.tsv` (name, lat, lon per line; data/r3-run2/g/lookup_sample.py --export). Writes counts
 * and times only to `lookup/result.txt` (no names: rule PV2).
 */
@RunWith(AndroidJUnit4::class)
class DetailsLookupTimingTest {
    @Test
    fun lookupTimes() {
        val dir = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "lookup")
        val db = File(dir, "search.sqlite")
        val sample = File(dir, "sample.tsv")
        assumeTrue("no lookup files on the device", db.isFile && sample.isFile)
        val points = sample.readLines().mapNotNull { l -> l.split('\t').takeIf { it.size == 3 }?.let { Triple(it[0], it[1].toDouble(), it[2].toDouble()) } }
        val t0 = System.nanoTime()
        SearchEngine(db.path, null).use { eng ->
            val openMs = (System.nanoTime() - t0) / 1e6
            // first round: cold pages; second round: as a sheet asked again
            val rounds = (1..2).map {
                var found = 0
                var shown = 0
                val ms = points.map { (name, lat, lon) ->
                    val s = System.nanoTime()
                    val d = eng.detailsNamedNear(name, lat, lon, 100.0)       // what the app runs (SearchHub.detailsAt)
                    val took = (System.nanoTime() - s) / 1e6
                    if (d?.second?.hasContact == true) shown++
                    if (eng.poisNamedNear(name, lat, lon, 100.0).isNotEmpty()) found++
                    took
                }.sorted()
                Triple(found, shown, ms)
            }
            val text = buildString {
                append("points ${points.size}, open ${"%.1f".format(openMs)} ms\n")
                rounds.forEachIndexed { i, (found, shown, ms) ->
                    fun q(p: Double) = ms[minOf(ms.size - 1, (p * ms.size).toInt())]
                    append("round ${i + 1}: found $found, with hours/phone/website $shown; ms per lookup " +
                        "median ${"%.2f".format(q(0.5))}, 90 % ${"%.2f".format(q(0.9))}, 99 % ${"%.2f".format(q(0.99))}, max ${"%.2f".format(ms.last())}, " +
                        "mean ${"%.2f".format(ms.average())}\n")
                }
            }
            File(dir, "result.txt").writeText(text)
            assertTrue(rounds[0].first > 0)
        }
    }
}
