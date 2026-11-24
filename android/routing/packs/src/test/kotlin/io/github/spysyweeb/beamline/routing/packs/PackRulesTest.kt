package io.github.spysyweeb.beamline.routing.packs

import io.github.spysyweeb.beamline.routing.config.EngineConfig
import io.github.spysyweeb.beamline.routing.model.LatLon
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/**
 * The install / update / remove rules of packbuilder/routing/packs.py (tar layout), on small
 * synthetic packs (MiniPacks). The same flows run on the real PA and OH packs in RealPacksTest.
 */
class PackRulesTest {
    @TempDir
    lateinit var tmp: File

    private val pa = mapOf("2/000/752/000.gph" to "pa1", "2/000/752/001.gph" to "pa2", "2/000/752/002.gph" to "border",
        "1/047/000.gph" to "pa-l1", "0/002/904.gph" to "l0")
    private val oh = mapOf("2/000/752/002.gph" to "border", "2/000/752/003.gph" to "oh1", "0/002/904.gph" to "l0")

    private fun bytes(spec: Map<String, String>, salt: Map<String, Int> = emptyMap()) =
        spec.mapValues { (p, id) -> MiniPacks.tile("$p/$id", salt = salt[p] ?: 0) }

    private fun device() = RoutingPackManager(File(tmp, "device"), "3.9.1")

    /** The tile set on the device: path -> sha256, read back from routing.tar through its index. */
    private fun tileSet(m: RoutingPackManager): Map<String, String> {
        val members = TileTar.members(m.tileSet).filter { it.name.endsWith(".gph") }
        val index = TileTar.readIndex(m.tileSet)
        assertEquals(members.map { Triple(it.offset, TileTar.tileId(it.name), it.size) }, index, "index.bin matches the members")
        return java.io.RandomAccessFile(m.tileSet, "r").use { f ->
            members.associate { mem ->
                val b = ByteArray(mem.size.toInt()); f.seek(mem.offset); f.readFully(b)
                mem.name to RoutingPackManager.sha256(b)
            }
        }
    }

    private fun expected(vararg packs: PackSource) = packs.flatMap { p -> p.manifest.tiles.map { it.path to it.sha256 } }.toMap()

    @Test
    fun `install, add a neighbour, remove it - border tiles stored once`() {
        val a = File(tmp, "a")
        val pPa = MiniPacks.write(a, "pa", "B1", bytes(pa))
        val pOh = MiniPacks.write(a, "oh", "B1", bytes(oh))
        val m = device()
        val r1 = m.install(listOf(pPa))
        assertEquals(5, r1.tilesAdded)
        assertEquals(expected(pPa), tileSet(m))
        val r2 = m.install(listOf(pOh))
        assertEquals(1, r2.tilesAdded)
        assertEquals(2, r2.tilesAlreadyPresent, "the border tile and the level-0 tile are shared")
        assertEquals(6, tileSet(m).size)
        assertEquals(expected(pPa, pOh), tileSet(m))
        val u = m.uninstall("oh")
        assertEquals(1, u.tilesRemoved)
        assertEquals(2, u.tilesKeptShared)
        assertEquals(expected(pPa), tileSet(m))
        assertEquals(setOf("pa"), m.installed().keys)
        m.uninstall("pa")
        assertFalse(m.tileSet.exists())
        assertFalse(m.engineConfigFile.exists())
        assertEquals(emptySet<String>(), m.installed().keys)
    }

    @Test
    fun `a pack from another graph build is refused, and the device stays as it was`() {
        val m = device()
        m.install(listOf(MiniPacks.write(File(tmp, "a"), "pa", "B1", bytes(pa))))
        val before = RoutingPackManager.sha256(m.tileSet)
        val ohB2 = MiniPacks.write(File(tmp, "b"), "oh", "B2", bytes(oh))
        val e = assertThrows(PackException::class.java) { m.install(listOf(ohB2)) }
        assertTrue(e.message!!.contains("is from graph build B2 but installed pack pa is from B1"), e.message)
        assertEquals(PackException.Kind.GRAPH_BUILD, e.kind)
        assertEquals(before, RoutingPackManager.sha256(m.tileSet))
        assertEquals(setOf("pa"), m.installed().keys)
        assertFalse(File(m.deviceDir, "routing.tar.new").exists())
    }

    @Test
    fun `an unreadable state manifest stops nothing and is uninstalled`() {
        val a = File(tmp, "a")
        val pPa = MiniPacks.write(a, "pa", "B1", bytes(pa))
        val pOh = MiniPacks.write(a, "oh", "B1", bytes(oh))
        val m = device()
        m.install(listOf(pPa, pOh))
        // damaged storage: Ohio's installed manifest is cut short
        val ohJson = File(m.deviceDir, "packs/oh.json")
        ohJson.writeText(ohJson.readText().take(40))
        assertEquals(setOf("pa"), m.installed().keys, "the readable states are listed, nothing is thrown")
        assertEquals(listOf("oh"), m.unreadable())
        assertFalse(m.hasPendingChange())
        // uninstalling the broken state rewrites the set from the readable states only
        m.uninstall("oh")
        assertEquals(emptyList<String>(), m.unreadable())
        assertFalse(ohJson.exists())
        assertEquals(expected(pPa), tileSet(m))
        // the same through an install: the broken state's tiles and file go with the next change
        m.install(listOf(pOh))
        ohJson.writeText("{")
        m.install(listOf(pPa))
        assertEquals(setOf("pa"), m.installed().keys)
        assertEquals(emptyList<String>(), m.unreadable())
        assertEquals(expected(pPa), tileSet(m))
        // the only state broken: the tile set goes away with it
        File(m.deviceDir, "packs/pa.json").writeText("not json")
        m.uninstall("pa")
        assertFalse(m.tileSet.exists())
        assertEquals(emptySet<String>(), m.installed().keys)
        assertThrows(PackException::class.java) { m.uninstall("pa") }
    }

    @Test
    fun `refusals carry their kind`() {
        val m = RoutingPackManager(File(tmp, "device"), "3.9.1", freeSpace = { 1L })
        val e = assertThrows(PackException::class.java) { m.install(listOf(MiniPacks.write(File(tmp, "a"), "pa", "B1", bytes(pa)))) }
        assertEquals(PackException.Kind.NO_SPACE, e.kind)
        val v = assertThrows(PackException::class.java) {
            RoutingPackManager(File(tmp, "device2"), "3.9.0").install(listOf(MiniPacks.write(File(tmp, "b"), "pa", "B1", bytes(pa))))
        }
        assertEquals(PackException.Kind.ENGINE_VERSION, v.kind)
    }

    @Test
    fun `release update - one state alone is refused, all together replace changed and drop stale tiles`() {
        val m = device()
        val a = File(tmp, "a")
        m.install(listOf(MiniPacks.write(a, "pa", "B1", bytes(pa)), MiniPacks.write(a, "oh", "B1", bytes(oh))))
        // build B2: pa2 changed bytes, pa-l1 dropped, the border tile changed in both packs
        val b = File(tmp, "b")
        val pa2 = pa - "1/047/000.gph"
        val paB = MiniPacks.write(b, "pa", "B2", bytes(pa2, mapOf("2/000/752/001.gph" to 5, "2/000/752/002.gph" to 3)))
        val ohB = MiniPacks.write(b, "oh", "B2", bytes(oh, mapOf("2/000/752/002.gph" to 3)))
        assertThrows(PackException::class.java) { m.install(listOf(paB)) }
        val r = m.install(listOf(paB, ohB))
        assertEquals(listOf("oh", "pa"), r.replaced.sorted())
        assertEquals(2, r.tilesChanged)
        assertEquals(1, r.tilesRemovedStale)
        assertEquals(expected(paB, ohB), tileSet(m))
        // back to build B1 for pa only, after removing oh: the dropped tile returns
        m.uninstall("oh")
        m.install(listOf(PackSource.fromDir(File(a, "pa"))))
        assertEquals(expected(PackSource.fromDir(File(a, "pa"))), tileSet(m))
    }

    @Test
    fun `packs for another Valhalla version are refused - manifest and tile header`() {
        val m = device()
        val e1 = assertThrows(PackException::class.java) {
            m.install(listOf(MiniPacks.write(File(tmp, "v"), "pa", "B1", bytes(pa), version = "3.10.0")))
        }
        assertTrue(e1.message!!.contains("built for Valhalla 3.10.0 but this engine is Valhalla 3.9.1"), e1.message)
        // a tile whose header says 3.8.0, with consistent hashes everywhere
        val t = bytes(pa).toMutableMap()
        t["2/000/752/001.gph"] = MiniPacks.tile("x", version = "3.8.0")
        val e2 = assertThrows(PackException::class.java) { m.install(listOf(MiniPacks.write(File(tmp, "h"), "pa", "B1", t))) }
        assertTrue(e2.message!!.contains("was built by Valhalla 3.8.0, expected 3.9.1"), e2.message)
        assertFalse(m.tileSet.exists())
        assertEquals(emptySet<String>(), m.installed().keys)
    }

    @Test
    fun `a tile that does not match its manifest sha256 is refused`() {
        val m = device()
        m.install(listOf(MiniPacks.write(File(tmp, "a"), "pa", "B1", bytes(pa))))
        val before = RoutingPackManager.sha256(m.tileSet)
        val bad = MiniPacks.write(File(tmp, "w"), "oh", "B1", bytes(oh), manifestSha = mapOf("2/000/752/003.gph" to "0".repeat(64)))
        val e = assertThrows(PackException::class.java) { m.install(listOf(bad)) }
        assertTrue(e.message!!.contains("does not match its manifest sha256"), e.message)
        assertEquals(before, RoutingPackManager.sha256(m.tileSet), "the failed write left routing.tar untouched")
        assertFalse(File(m.deviceDir, "routing.tar.new").exists())
    }

    @Test
    fun `a tampered pack tar and a shared tile that differs are refused`() {
        val m = device()
        val p = MiniPacks.write(File(tmp, "a"), "pa", "B1", bytes(pa))
        p.tar.appendBytes(ByteArray(512))
        assertThrows(PackException::class.java) { m.install(listOf(p)) }
        m.install(listOf(MiniPacks.write(File(tmp, "c"), "pa", "B1", bytes(pa))))
        val ohOther = MiniPacks.write(File(tmp, "d"), "oh", "B1", bytes(oh, mapOf("2/000/752/002.gph" to 9)))
        val e = assertThrows(PackException::class.java) { m.install(listOf(ohOther)) }
        assertTrue(e.message!!.contains("shared tile 2/000/752/002.gph differs"), e.message)
    }

    /** Thrown by the fault hook: the process "dies" there, nothing after it runs. */
    private class Killed(at: String) : Error("killed at $at")

    private fun killingAt(point: String) = device().apply { faultHook = { if (it == point) throw Killed(it) } }

    @Test
    fun `a change killed after its commit point is completed by the next call`() {
        // review finding: the manifests were written after the tile set swap, so a kill in between
        // left new tiles with old manifests and the next install failed on the keep hashes
        val a = File(tmp, "a")
        val pPa = MiniPacks.write(a, "pa", "B1", bytes(pa))
        val pOh = MiniPacks.write(a, "oh", "B1", bytes(oh))
        for (point in listOf("committed", "tileset-swapped")) {
            File(tmp, "device").deleteRecursively()
            device().install(listOf(pPa))
            assertThrows(Killed::class.java) { killingAt(point).install(listOf(pOh)) }
            assertTrue(File(tmp, "device/pending/COMMIT").isFile, point)
            val next = device()
            assertEquals(RoutingPackManager.Recovery.COMPLETED, next.recover(), point)
            assertEquals(setOf("oh", "pa"), next.installed().keys, point)
            assertEquals(expected(pPa, pOh), tileSet(next), point)
            assertFalse(File(tmp, "device/pending").exists(), point)
            assertFalse(File(tmp, "device/routing.tar.new").exists(), point)
            // and the device keeps working: the next change finds hashes that match its manifests
            next.uninstall("oh")
            assertEquals(expected(pPa), tileSet(next), point)
        }
        // the last pack removed, killed after the commit: the next call removes the tile set and device files
        assertThrows(Killed::class.java) { killingAt("committed").uninstall("pa") }
        assertTrue(File(tmp, "device/routing.tar").exists())
        assertEquals(emptySet<String>(), device().installed().keys)
        assertFalse(File(tmp, "device/routing.tar").exists())
        assertFalse(File(tmp, "device/valhalla.json").exists())
        assertFalse(File(tmp, "device/device.json").exists())
    }

    @Test
    fun `a change killed before its commit point is undone by the next call`() {
        val a = File(tmp, "a")
        val m = device()
        m.install(listOf(MiniPacks.write(a, "pa", "B1", bytes(pa))))
        val before = RoutingPackManager.sha256(m.tileSet)
        assertThrows(Killed::class.java) { killingAt("tileset-written").install(listOf(MiniPacks.write(a, "oh", "B1", bytes(oh)))) }
        assertTrue(File(m.deviceDir, "routing.tar.new").exists(), "the kill left the new tile set behind")
        val next = device()
        assertEquals(RoutingPackManager.Recovery.ROLLED_BACK, next.recover())
        assertEquals(setOf("pa"), next.installed().keys)
        assertEquals(before, RoutingPackManager.sha256(next.tileSet))
        assertFalse(File(m.deviceDir, "routing.tar.new").exists())
        assertEquals(RoutingPackManager.Recovery.NOTHING_PENDING, next.recover())
    }

    @Test
    fun `a change without the free space for the new tile set is refused before writing`() {
        val a = File(tmp, "a")
        val pPa = MiniPacks.write(a, "pa", "B1", bytes(pa))
        val need = TileTar.sizeOf(pPa.manifest.tiles.map { it.bytes })
        val tight = RoutingPackManager(File(tmp, "device"), "3.9.1", freeSpace = { need })
        val e = assertThrows(PackException::class.java) { tight.install(listOf(pPa)) }
        assertTrue(e.message!!.startsWith("not enough free space"), e.message)
        assertFalse(tight.tileSet.exists())
        assertFalse(File(tmp, "device/routing.tar.new").exists())
        // with room it installs, and the size estimate is exactly the written length
        val m = device()
        m.install(listOf(pPa))
        assertEquals(need, m.tileSet.length())
    }

    @Test
    fun `manifest names that are not plain names are refused`() {
        val good = MiniPacks.write(File(tmp, "a"), "pa", "B1", bytes(pa)).manifest.text
        for ((from, to) in listOf("\"state\":\"pa\"" to "\"state\":\"../pa\"", "\"tar\":\"routing-pa.tar\"" to "\"tar\":\"../../x.tar\"",
            "\"tar\":\"routing-pa.tar\"" to "\"tar\":\"sub/x.tar\"", "\"path\":\"0/002/904.gph\"" to "\"path\":\"../0/002/904.gph\"")) {
            assertTrue(good.contains(from), from)
            assertThrows(PackException::class.java) { RoutingPackManifest.parse(good.replace(from, to)) }
        }
        RoutingPackManifest.parse(good) // the original parses
    }

    @Test
    fun `engine config is written with the absolute tar path and every device setting`() {
        val m = device()
        m.install(listOf(MiniPacks.write(File(tmp, "a"), "pa", "B1", bytes(pa))))
        val cfg = RoutingPackManager.engineConfig(m.deviceDir)
        assertEquals(m.tileSet.absolutePath, EngineConfig.get(cfg, "mjolnir.tile_extract")!!.jsonPrimitive.content)
        assertEquals(emptyList<String>(), EngineConfig.mismatches(cfg, EngineConfig.bundledDeviceSettings()))
        // tile 2/000/752/000 = row 522, column 320: 40.50..40.75 N, 100.00..99.75 W
        assertTrue(m.coverage().covers(LatLon(40.6, -99.9)))
        assertFalse(m.coverage().covers(LatLon(40.0, -77.0)))
    }
}
