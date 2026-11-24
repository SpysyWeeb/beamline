package io.github.spysyweeb.beamline.search

import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The stores' writer on an executor with many threads, as Dispatchers.IO is: the newest text wins,
 * and awaitIdle / awaitStoreWrites wait for every write submitted (not just for a marker task that
 * a pool may run first).
 */
class AtomicWriteTest {
    @TempDir
    lateinit var dir: File

    @Test
    fun onAPoolTheNewestTextWinsAndTheWaitsWaitForEveryWrite() {
        val pool = Executors.newFixedThreadPool(8)
        try {
            val f = File(dir, "store.json")
            val w = LatestTextWriter(f, pool)
            for (i in 1..300) w.submit("""{"n":$i,"pad":"${"x".repeat(2000)}"}""")
            w.awaitIdle()
            assertEquals("""{"n":300,"pad":"${"x".repeat(2000)}"}""", f.readText())
            assertFalse(File(dir, "store.json.tmp").exists())
            // the same through the module-wide wait, on Dispatchers.IO itself
            val g = File(dir, "io.json")
            val io = LatestTextWriter(g, LatestTextWriter.io)
            for (i in 1..300) io.submit("""{"m":$i}""")
            awaitStoreWrites()
            assertEquals("""{"m":300}""", g.readText())
        } finally {
            pool.shutdown()
            pool.awaitTermination(10, TimeUnit.SECONDS)
        }
    }
}
