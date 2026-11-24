package io.github.spysyweeb.beamline.search

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class SmokeTest {
    @Test
    fun opensThePackAndAnswers() {
        SearchEngine(TestEnv.requireSearchDb(), TestEnv.addressesDb.ifEmpty { null }).use { eng ->
            val (ver, opts) = eng.sqliteInfo()
            println("JVM bundled SQLite $ver; compile options: ${opts.joinToString(" ")}")
            val erie = TestEnv.points.getValue("erie")
            val r = eng.search("walmart erie pa", erie.lat, erie.lon)
            r.results.take(3).forEach { println("  ${it.title} | ${it.subtitle} | ${it.distanceKm} km") }
            assertTrue(r.results.isNotEmpty())
            assertTrue(r.results[0].title.startsWith("Walmart"))
            val h = eng.search("4319 peach st", erie.lat, erie.lon)
            println("  4319 peach st -> ${h.results[0].title} ${h.results[0].address}")
            assertEquals("4319 Peach Street", h.results[0].title)
        }
    }
}
