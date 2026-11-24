package io.github.spysyweeb.beamline.installed

import io.github.spysyweeb.beamline.api.data.InstallStatus
import io.github.spysyweeb.beamline.api.data.PackArchive
import io.github.spysyweeb.beamline.api.data.PackFolder
import io.github.spysyweeb.beamline.api.data.RoutingSet
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.packs.InstallRecord
import io.github.spysyweeb.beamline.packs.InstallRules
import io.github.spysyweeb.beamline.packs.PackManifest
import io.github.spysyweeb.beamline.packs.Readers
import io.github.spysyweeb.beamline.routing.packs.RoutingPackManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files

/**
 * The installer on the real format-3 PA and OH packs of packbuilder/pack/assemble.py
 * (data/r3-packs, read only: download folders are made of hard links, which a move does not
 * change). About 1.3 GB per install; skipped when the packs are not there.
 */
class RealArchivesInstallTest {

    @TempDir
    lateinit var tmp: File

    private val r3 = File(System.getProperty("beamline.r3Packs") ?: "data/r3-packs")
    private val scopes = mutableListOf<CoroutineScope>()
    private val packs get() = File(tmp, "packs")
    private val routing get() = File(tmp, "routing")
    private val readers = Readers(mapSchema = "protomaps-basemap", mapSchemaMajor = 4)

    private val osm = "© OpenStreetMap contributors"
    private val erie = GeoPoint(42.1292, -80.0851)
    private val pittsburgh = GeoPoint(40.4406, -79.9959)
    private val conneaut = GeoPoint(41.9476, -80.5542)
    private val columbus = GeoPoint(39.9612, -82.9988)

    private fun archive(id: String): File? = r3.listFiles { f -> f.name.startsWith("$id-") && f.name.endsWith(".tar") }?.firstOrNull()

    private fun need(vararg ids: String): Map<String, File> {
        val found = ids.associateWith { archive(it) }
        assumeTrue(found.values.all { it != null }, "the format-3 archives ${ids.toList()} are not in $r3")
        return found.mapValues { it.value!! }
    }

    private fun library(): InstalledLibrary {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        return InstalledLibrary(packs, routing, readers, InstallRules(), scope, Dispatchers.IO)
    }

    /** Nothing may still work in the temporary folder when JUnit deletes it (Windows keeps open files). */
    @AfterEach
    fun stop() = runBlocking { scopes.forEach { it.coroutineContext.job.cancelAndJoin() } }

    /** Wall time of each change, printed for the report (docs/experiments/12-packs-and-installed.md). */
    private fun measure(what: String, t0: Long) = println("measured: $what ${(System.nanoTime() - t0) / 1_000_000} ms")

    private fun InstalledLibrary.done(started: Boolean, what: String = "install", t0: Long = System.nanoTime()): InstallStatus.Done {
        assertTrue(started)
        val r = runBlocking { withTimeout(300_000) { install.first { it !is InstallStatus.Running && it !is InstallStatus.Idle } } }
        measure(what, t0)
        assertTrue(r is InstallStatus.Done, r.toString())
        dismissInstallResult()
        return r as InstallStatus.Done
    }

    private fun source(f: File) = PackArchive(f.name) { f.inputStream() }

    private fun record(id: String) = InstallRecord.parse(File(packs, "$id/install-record.json").readText())

    private fun RoutingSet.tileUrl(): String? =
        Json.parseToJsonElement(File(engineConfigFile).readText()).jsonObject["mjolnir"]?.jsonObject?.get("tile_url")?.jsonPrimitive?.content

    /** A download folder made of hard links to the real pack folder's files (moving a link leaves the original). */
    private fun linkedFolder(id: String, name: String, parts: Collection<String>? = null): File {
        val src = File(r3, id)
        assumeTrue(src.isDirectory, "no pack folder $src")
        val dir = File(tmp, "downloads/$name").apply { mkdirs() }
        Files.copy(File(src, "manifest.json").toPath(), File(dir, "manifest.json").toPath())
        val m = PackManifest.parse(File(src, "manifest.json").readText())
        for (part in m.allParts) {
            if (parts != null && part.key !in parts) continue
            Files.createLink(File(dir, part.value.file).toPath(), File(src, part.value.file).toPath())
        }
        return dir
    }

    @Test
    fun `PA and OH install with every part, update, and OH is removed while PA still routes`() {
        val tars = need("us-pa", "us-oh")
        val lib = library()
        runBlocking { lib.refresh() }
        var g = lib.installed.value.generation
        var t0 = System.nanoTime()
        val done = lib.done(lib.startInstall(source(tars.getValue("us-pa")), true), "PA archive install (first state)", t0)
        assertEquals("Pennsylvania", done.name)
        assertEquals("2026-11-14", done.osmSnapshot)
        assertEquals(g + 1, lib.installed.value.generation)
        val pa = lib.installed.value.packs.single()
        val dir = File(packs, "us-pa")
        assertEquals(setOf("manifest.json", "map.pmtiles", "routing.json", "search.sqlite", "addresses.sqlite", "install-record.json"),
            dir.list()!!.toSet(), "routing merged, the staged tar gone, routing.json kept")
        assertTrue(record("us-pa").routingMerged)
        assertEquals("archive", record("us-pa").source)
        assertTrue(pa.attribution.startsWith("$osm; U.S. Census Bureau TIGER/Line"), pa.attribution)
        assertNotNull(pa.searchFile)
        assertNotNull(pa.addressesFile)
        assertTrue(pa.inRoutingSet && pa.verified)
        var r = lib.installed.value.routing!!
        assertTrue(r.tileUrl().isNullOrEmpty(), "rule N5: mjolnir.tile_url empty or absent")
        assertTrue(r.covers(erie) && r.covers(pittsburgh))
        assertFalse(r.covers(columbus))
        assertEquals(packs.list()!!.toList(), listOf("us-pa"), "no staging folder left")

        g = lib.installed.value.generation
        t0 = System.nanoTime()
        lib.done(lib.startInstall(source(tars.getValue("us-oh")), true), "OH archive install (second state)", t0)
        assertEquals(g + 1, lib.installed.value.generation)
        assertEquals(setOf("manifest.json", "map.pmtiles", "routing.json", "search.sqlite", "addresses.sqlite", "install-record.json"),
            File(packs, "us-oh").list()!!.toSet(), "OH: routing merged, the staged tar gone, routing.json kept")
        assertTrue(lib.installed.value.packs.single { it.packId == "us-oh" }.let { it.inRoutingSet && it.verified && it.addressesFile != null })
        r = lib.installed.value.routing!!
        assertEquals(listOf("OH", "PA"), r.states)
        assertTrue(r.covers(columbus) && r.covers(conneaut) && r.covers(erie))
        val merged = RoutingPackManager(routing, "3.9.1").installed()
        assertEquals(setOf("pa", "oh"), merged.keys)

        // update of an installed state: the same build again replaces it in one generation
        g = lib.installed.value.generation
        t0 = System.nanoTime()
        lib.done(lib.startInstall(source(tars.getValue("us-pa")), true), "PA archive update (OH installed)", t0)
        assertEquals(g + 1, lib.installed.value.generation)
        assertEquals(listOf("us-oh", "us-pa"), packs.list()!!.sorted())

        g = lib.installed.value.generation
        t0 = System.nanoTime()
        assertNull(runBlocking { lib.remove("us-oh") })
        measure("OH removal (PA stays)", t0)
        assertEquals(g + 1, lib.installed.value.generation)
        r = lib.installed.value.routing!!
        assertEquals(listOf("PA"), r.states)
        assertTrue(r.covers(erie) && r.covers(pittsburgh), "the other state still routes")
        assertFalse(r.covers(columbus))
        assertEquals(setOf("pa"), RoutingPackManager(routing, "3.9.1").installed().keys)
        assertEquals(listOf("us-pa"), packs.list()!!.toList())
        // the tiles of the remaining set are those of PA's routing index (hash-checked by the merge)
        val paIndex = merged.getValue("pa")
        assertEquals(paIndex.tiles.size, RoutingPackManager(routing, "3.9.1").installed().getValue("pa").tiles.size)
    }

    @Test
    fun `PA without house numbers, then added from a folder and removed again`() {
        val tars = need("us-pa")
        val lib = library()
        lib.done(lib.startInstall(source(tars.getValue("us-pa")), false))
        val p0 = lib.installed.value.packs.single()
        assertEquals(osm, p0.attribution, "a state added without house numbers credits no address source")
        assertTrue(p0.addressesSkipped)
        assertNull(p0.addressesFile)
        val dir = File(packs, "us-pa")
        val others = dir.listFiles()!!.filter { it.name != "install-record.json" }.associate { it.name to (it.length() to it.lastModified()) }

        val add = linkedFolder("us-pa", "us-pa-addresses", parts = listOf("addresses"))
        lib.done(lib.startAddOptionalParts(PackFolder("us-pa-addresses", add.absolutePath)))
        val p1 = lib.installed.value.packs.single()
        assertTrue(p1.attribution.startsWith("$osm; U.S. Census Bureau TIGER/Line"), p1.attribution)
        assertNotNull(p1.addressesFile)
        assertEquals(listOf("manifest.json"), add.list()!!.toList())
        val after = dir.listFiles()!!.filter { it.name != "install-record.json" && it.name != "addresses.sqlite" }
            .associate { it.name to (it.length() to it.lastModified()) }
        assertEquals(others, after, "nothing else in the pack folder changes")

        assertNull(runBlocking { lib.removeOptionalParts("us-pa") })
        val p2 = lib.installed.value.packs.single()
        assertEquals(osm, p2.attribution)
        assertFalse(File(dir, "addresses.sqlite").exists())
    }

    @Test
    fun `the real PA folder is installed by moving its files, and a failure moves them back`() {
        need("us-pa")
        val lib = library()
        // a failure in the middle: the house-number file is not the one the manifest names
        val bad = linkedFolder("us-pa", "us-pa-bad", parts = listOf("map", "routing", "routing_index", "search"))
        File(bad, "addresses.sqlite").writeBytes(ByteArray(4096))
        val before = bad.listFiles()!!.associate { it.name to (it.length() to it.lastModified()) }
        assertTrue(lib.startInstallFromFolder(PackFolder("us-pa-bad", bad.absolutePath), true))
        val failed = runBlocking { withTimeout(300_000) { lib.install.first { it !is InstallStatus.Running && it !is InstallStatus.Idle } } }
        assertTrue(failed is InstallStatus.Failed, failed.toString())
        lib.dismissInstallResult()
        assertEquals(before, bad.listFiles()!!.associate { it.name to (it.length() to it.lastModified()) }, "every moved file is back")
        assertEquals(emptyList<String>(), packs.list()?.toList().orEmpty())

        val good = linkedFolder("us-pa", "us-pa-202611")
        val times = good.listFiles()!!.filter { it.name != "manifest.json" }.associate { it.name to it.lastModified() }
        val info = runBlocking { lib.inspect(PackArchive("x") { archive("us-pa")!!.inputStream() }) }
        assertEquals(emptyList<String>(), info.problems)
        lib.done(lib.startInstallFromFolder(PackFolder("us-pa-202611", good.absolutePath), true), "PA folder install (moves)")
        assertEquals(listOf("manifest.json"), good.list()!!.toList(), "the folder holds no part file afterwards")
        for ((name, t) in times.filterKeys { it != "routing.tar" }) {
            assertEquals(t, File(packs, "us-pa/$name").lastModified(), "$name was moved, not copied")
        }
        assertEquals("download", record("us-pa").source)
        val p = lib.installed.value.packs.single()
        assertEquals(PackManifest.parse(File(r3, "us-pa/manifest.json").readText()).packVersion, p.packVersion)
        assertTrue(p.inRoutingSet)
    }
}
