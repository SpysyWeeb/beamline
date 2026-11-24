package io.github.spysyweeb.beamline.routing.packs

import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.io.RandomAccessFile

/**
 * Install, add, remove on the real Pennsylvania and Ohio routing packs (graph build
 * 261114-pa-oh-v3.9.1-b53087, 634 MB + 644 MB). The packs are only read. The devices written here
 * stay for the next steps: <work>/pa-oh (PA then OH, the tile set pushed to the emulator) and
 * <work>/pa-only (for the "outside the installed maps" checks).
 */
class RealPacksTest {
    private val packs = File(System.getProperty("beamline.routingPacks") ?: "")
    private val work = File(System.getProperty("beamline.routingPacksWork") ?: "build/routing-packs-test")

    private fun hashes(tar: File): Map<String, String> = RandomAccessFile(tar, "r").use { f ->
        TileTar.members(tar).filter { it.name.endsWith(".gph") }.associate { m ->
            val b = ByteArray(m.size.toInt()); f.seek(m.offset); f.readFully(b)
            m.name to RoutingPackManager.sha256(b)
        }
    }

    private fun indexBytes(tar: File): ByteArray {
        val idx = TileTar.members(tar).first()
        return RandomAccessFile(tar, "r").use { f -> ByteArray(idx.size.toInt()).also { f.seek(idx.offset); f.readFully(it) } }
    }

    private fun timed(what: String, block: () -> Unit) {
        val t0 = System.nanoTime()
        block()
        println("TIMING $what: ${(System.nanoTime() - t0) / 1_000_000} ms")
    }

    @Test
    fun `PA, then OH, then OH removed - same tile sets and the same index as packs-py`() {
        assumeTrue(File(packs, "pa/manifest.json").exists() && File(packs, "oh/manifest.json").exists(), "real packs not found at $packs")
        val pa = PackSource.fromDir(File(packs, "pa"))
        val oh = PackSource.fromDir(File(packs, "oh"))
        val dir = File(work, "pa-oh").apply { deleteRecursively() }
        val m = RoutingPackManager(dir, "3.9.1")

        timed("install PA on an empty device") { println(m.install(listOf(pa))) }
        val paTiles = pa.manifest.tiles.associate { it.path to it.sha256 }
        assertEquals(paTiles, hashes(m.tileSet))
        val paIndex = indexBytes(m.tileSet)
        assertEquals(286, paTiles.size)

        timed("install OH on top of PA") { println(m.install(listOf(oh))) }
        val both = paTiles + oh.manifest.tiles.associate { it.path to it.sha256 }
        assertEquals(510, both.size, "286 + 239 - 15 shared border tiles")
        assertEquals(both, hashes(m.tileSet))
        // packs.py wrote the same two packs into data/routing/device: same index (offsets, ids, sizes)
        val pyTar = File(packs.parentFile, "device/routing.tar")
        if (pyTar.exists()) {
            assertArrayEquals(indexBytes(pyTar), indexBytes(m.tileSet), "index.bin equals the one packs.py wrote")
            assertEquals(pyTar.length(), m.tileSet.length())
            println("SAME_INDEX_AS_PACKS_PY ${pyTar.length()} bytes")
        }

        timed("uninstall OH") { println(m.uninstall("oh")) }
        assertEquals(paTiles, hashes(m.tileSet))
        assertArrayEquals(paIndex, indexBytes(m.tileSet), "back to the PA-only tile set")

        timed("install PA+OH in one call (release update of both)") { println(m.install(listOf(pa, oh))) }
        assertEquals(both, hashes(m.tileSet))

        // a PA-only device for the "outside the installed maps" checks
        val paOnly = RoutingPackManager(File(work, "pa-only").apply { deleteRecursively() }, "3.9.1", verifyTarHash = false)
        timed("install PA without the whole-tar hash") { paOnly.install(listOf(pa)) }
    }
}
