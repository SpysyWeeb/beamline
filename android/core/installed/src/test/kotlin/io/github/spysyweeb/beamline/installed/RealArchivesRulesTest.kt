package io.github.spysyweeb.beamline.installed

import io.github.spysyweeb.beamline.api.data.InstallStatus
import io.github.spysyweeb.beamline.api.data.InstallStep
import io.github.spysyweeb.beamline.api.data.PackArchive
import io.github.spysyweeb.beamline.api.data.PackFolder
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.packs.InstallRecord
import io.github.spysyweeb.beamline.packs.InstallRules
import io.github.spysyweeb.beamline.packs.PackManifest
import io.github.spysyweeb.beamline.packs.Readers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The installer's rules of docs/architecture.md 5.1 P ("Done when") on the real format-3 PA and OH
 * archives of packbuilder/pack/assemble.py (data/r3-packs, read only): refusal while held,
 * holdChanges null while an install or a removal runs, too little space counting the merge, a
 * version-2 pack, a fingerprint mismatch, and crash recovery at the four points. The same rules
 * run on small synthetic packs in InstalledLibraryTest. Skipped when the archives are not there.
 */
class RealArchivesRulesTest {

    @TempDir
    lateinit var tmp: File

    private val r3 = File(System.getProperty("beamline.r3Packs") ?: "data/r3-packs")

    /** A version-2 archive of round 2 (data/android/archives), read only; only its manifest is read. */
    private val v2Archive = File(System.getProperty("beamline.v2Archive") ?: "data/android/archives/us-pa-2026-11-14.tar")

    private val scopes = LinkedHashMap<InstalledLibrary, CoroutineScope>()
    private val packs get() = File(tmp, "packs")
    private val routing get() = File(tmp, "routing")
    private val readers = Readers(mapSchema = "protomaps-basemap", mapSchemaMajor = 4)

    /** The street-key fingerprint of packbuilder/addresses/key_fingerprint.py (both real packs carry it). */
    private val fingerprint = "97c8d281115ec4d4b186051b18efe3f15bb5b1c74d60902854665f83d228d73a"

    private val erie = GeoPoint(42.1292, -80.0851)
    private val pittsburgh = GeoPoint(40.4406, -79.9959)
    private val columbus = GeoPoint(39.9612, -82.9988)

    private fun archive(id: String): File? = r3.listFiles { f -> f.name.startsWith("$id-") && f.name.endsWith(".tar") }?.firstOrNull()

    private fun need(vararg ids: String): Map<String, File> {
        val found = ids.associateWith { archive(it) }
        assumeTrue(found.values.all { it != null }, "the format-3 archives ${ids.toList()} are not in $r3")
        return found.mapValues { it.value!! }
    }

    /** Free bytes the libraries of a test see; a test lowers it to refuse an install. */
    @Volatile
    private var free = Long.MAX_VALUE / 4

    /** A new library on the test's folders; the one made before is ended first (one process at a time, as in the app). */
    private fun library(r: Readers = readers): InstalledLibrary {
        killAll()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return InstalledLibrary(packs, routing, r, InstallRules(), scope, Dispatchers.IO) { free }.also { scopes[it] = scope }
    }

    /** Ends every library as a killed process ends: its coroutines are cancelled and waited for. */
    private fun killAll() = runBlocking { scopes.values.forEach { it.coroutineContext.job.cancelAndJoin() } }

    @AfterEach
    fun stop() = killAll()

    private fun source(f: File) = PackArchive(f.name) { f.inputStream() }

    private fun InstalledLibrary.result(): InstallStatus = runBlocking {
        withTimeout(300_000) { install.first { it !is InstallStatus.Running && it !is InstallStatus.Idle } }.also { dismissInstallResult() }
    }

    private fun InstalledLibrary.installOk(f: File, addresses: Boolean = true): InstallStatus.Done {
        assertTrue(startInstall(source(f), addresses))
        val r = result()
        assertTrue(r is InstallStatus.Done, r.toString())
        return r as InstallStatus.Done
    }

    private fun hidden() = packs.listFiles { f -> f.name.startsWith(".") }.orEmpty().map { it.name }

    private fun visible() = packs.list().orEmpty().filter { !it.startsWith(".") }.sorted()

    private fun record(id: String) = InstallRecord.parse(File(packs, "$id/install-record.json").readText())

    private val installedFiles = setOf("manifest.json", "map.pmtiles", "routing.json", "search.sqlite", "addresses.sqlite", "install-record.json")

    @Test
    fun `held, busy and too little space on the real archives`() {
        val tars = need("us-pa", "us-oh")
        val pa = tars.getValue("us-pa")
        val oh = tars.getValue("us-oh")
        var lib = library()
        runBlocking { lib.refresh() }

        // the need counts every part, the new merged tile set (nothing installed: the routing part) and the margins
        val paInfo = runBlocking { lib.inspect(source(pa)) }
        val paManifest = PackManifest.parse(File(r3, "us-pa/manifest.json").readText())
        val margin = InstallRules().freeSpaceMarginBytes + InstalledCore.ROUTING_RESERVE
        assertEquals(paManifest.totalBytes + InstalledCore.MANIFEST_RESERVE + paManifest.routing!!.bytes + margin, paInfo.neededBytes)
        assertEquals(paManifest.addresses!!.bytes, paInfo.addressesBytes)
        assertEquals(emptyList<String>(), paInfo.problems)

        // too little space: refused before anything is copied
        free = paInfo.neededBytes - 1
        assertTrue(lib.startInstall(source(pa), true))
        val tight = lib.result() as InstallStatus.Failed
        assertTrue(tight.message.contains("needs") && tight.message.contains("free space"), tight.message)
        assertEquals(emptyList<String>(), packs.list()!!.toList(), "nothing copied")
        assertFalse(File(routing, "routing.tar").exists())
        free = paInfo.neededBytes

        // refused while held: install, folder install, removal; inspect names the hold
        val hold = lib.holdChanges("Not while guiding")!!
        assertFalse(lib.startInstall(source(pa), true))
        assertFalse(lib.startInstallFromFolder(PackFolder("us-pa", File(r3, "us-pa").absolutePath), true))
        assertEquals(listOf("Not while guiding"), runBlocking { lib.inspect(source(oh)) }.problems)
        hold.close()

        // holdChanges is null while the real install runs
        assertTrue(lib.startInstall(source(pa), true))
        runBlocking {
            withTimeout(120_000) {
                lib.install.first { it is InstallStatus.Running && it.step == InstallStep.COPYING && it.doneBytes > 0 || it !is InstallStatus.Running }
            }
        }
        assertTrue(lib.install.value is InstallStatus.Running, "the install was still running: ${lib.install.value}")
        assertNull(lib.holdChanges("Not while guiding"), "no hold while an install runs (rule P5)")
        assertFalse(lib.startInstall(source(oh), true), "one change at a time")
        assertEquals("Another change of the installed states is running.", runBlocking { lib.remove("us-pa") })
        assertTrue(lib.result() is InstallStatus.Done)
        assertEquals(listOf("us-pa"), visible())

        // with PA's merged set on the device, OH's need counts that set too (the merge writes the new set beside it)
        val ohInfo = runBlocking { lib.inspect(source(oh)) }
        val ohManifest = PackManifest.parse(File(r3, "us-oh/manifest.json").readText())
        val tileSet = File(routing, "routing.tar").length()
        assertTrue(tileSet > 600_000_000L, "PA's merged tile set is on the device")
        assertEquals(ohManifest.totalBytes + InstalledCore.MANIFEST_RESERVE + tileSet + ohManifest.routing!!.bytes + margin, ohInfo.neededBytes)
        val g = lib.installed.value.generation
        free = ohInfo.neededBytes - 1
        assertTrue(lib.startInstall(source(oh), true))
        assertTrue(lib.result() is InstallStatus.Failed)
        assertEquals(g, lib.installed.value.generation, "a refused install changes nothing")
        assertEquals(listOf("us-pa"), visible())
        assertEquals(emptyList<String>(), hidden())
        free = Long.MAX_VALUE / 4

        // a removal refused while held, then holdChanges null while the removal runs
        val hold2 = lib.holdChanges("Not while guiding")!!
        assertEquals("Not while guiding", runBlocking { lib.remove("us-pa") })
        assertEquals("Not while guiding", runBlocking { lib.removeOptionalParts("us-pa") })
        hold2.close()
        lib = library()
        runBlocking { lib.refresh() }
        val aside = CountDownLatch(1)
        val go = CountDownLatch(1)
        lib.core.faultHook = {
            if (it == "removal-aside") {
                aside.countDown()
                go.await(60, TimeUnit.SECONDS)
            }
        }
        val removed = CompletableFuture<String?>()
        Thread { removed.complete(runBlocking { lib.remove("us-pa") }) }.start()
        assertTrue(aside.await(60, TimeUnit.SECONDS))
        assertNull(lib.holdChanges("Not while guiding"), "no hold while a removal runs (rule P5)")
        go.countDown()
        assertNull(removed.get(120, TimeUnit.SECONDS))
        assertEquals(emptyList<String>(), visible())
        assertNull(lib.installed.value.routing)
        assertFalse(File(routing, "routing.tar").exists(), "no tile set without a state")
    }

    @Test
    fun `a version-2 archive is refused with rebuild the pack`() {
        assumeTrue(v2Archive.isFile, "no version-2 archive at $v2Archive")
        val lib = library()
        val info = runBlocking { lib.inspect(source(v2Archive)) }
        assertEquals(listOf("manifest_version 2 is an old pack format; rebuild the pack"), info.problems)
        assertTrue(lib.startInstall(source(v2Archive), true))
        val r = lib.result() as InstallStatus.Failed
        assertTrue(r.message.endsWith("manifest_version 2 is an old pack format; rebuild the pack"), r.message)
        assertEquals(emptyList<String>(), packs.list()!!.toList(), "nothing copied")
        assertFalse(File(routing, "routing.tar").exists())
    }

    /** A change stopped where a killed process would stop; then a new library (a new process) refreshes. */
    private fun crash(point: String, change: (InstalledLibrary) -> Unit): InstalledLibrary {
        val lib = library()
        runBlocking { lib.refresh() }
        lib.core.faultHook = { if (it == point) throw SimulatedCrash(it) }
        change(lib)
        return library().also { runBlocking { it.refresh() } }
    }

    private fun crashedInstall(f: File): (InstalledLibrary) -> Unit = { lib ->
        assertTrue(lib.startInstall(source(f), true))
        val r = lib.result()
        assertTrue(r is InstallStatus.Failed && r.message.contains("simulated crash"), r.toString())
    }

    @Test
    fun `crash recovery at the four points, and a fingerprint mismatch, on the real archives`() {
        val tars = need("us-pa", "us-oh")
        val pa = tars.getValue("us-pa")
        val oh = tars.getValue("us-oh")

        // 1. after staging (every part copied and hashed, before the routing commit): undone
        var lib = crash("staged", crashedInstall(pa))
        assertEquals(emptyList<String>(), hidden(), "the staging folder is gone")
        assertEquals(emptyList<String>(), visible())
        assertTrue(lib.installed.value.packs.isEmpty())
        assertNull(lib.installed.value.routing)
        assertFalse(File(routing, "routing.tar").exists())

        // 2. after the routing commit: rolled forward (the per-state tar is not kept, so it cannot be undone)
        lib = crash("routing-committed", crashedInstall(pa))
        assertEquals(emptyList<String>(), hidden())
        assertEquals(listOf("us-pa"), visible())
        assertEquals(installedFiles, File(packs, "us-pa").list()!!.toSet(), "staged tar gone, routing.json kept")
        assertTrue(record("us-pa").routingMerged)
        var p = lib.installed.value.packs.single()
        assertTrue(p.inRoutingSet && p.verified)
        assertEquals(listOf("PA"), lib.installed.value.routing!!.states)
        assertTrue(lib.installed.value.routing!!.covers(erie))

        // 3. before the rename of an update: the update is finished (a new folder with a new record)
        val before = record("us-pa").installedUtc
        Thread.sleep(1100) // installed_utc has whole seconds
        lib = crash("before-rename", crashedInstall(pa))
        assertEquals(emptyList<String>(), hidden())
        assertEquals(listOf("us-pa"), visible())
        assertEquals(installedFiles, File(packs, "us-pa").list()!!.toSet())
        assertNotEquals(before, record("us-pa").installedUtc, "the updated folder replaced the old one")
        assertTrue(record("us-pa").routingMerged)
        assertTrue(lib.installed.value.packs.single().inRoutingSet)
        assertEquals(listOf("PA"), lib.installed.value.routing!!.states)

        // 4. during removal (the folder moved aside, routing not yet uninstalled): the removal is finished
        lib.installOk(oh)
        assertEquals(listOf("OH", "PA"), lib.installed.value.routing!!.states)
        lib = crash("removal-aside") { l ->
            org.junit.jupiter.api.assertThrows<SimulatedCrash> { runBlocking { l.remove("us-oh") } }
            assertTrue(hidden().any { it.startsWith(".removing-us-oh") })
        }
        assertEquals(emptyList<String>(), hidden())
        assertEquals(listOf("us-pa"), visible())
        val r = lib.installed.value.routing!!
        assertEquals(listOf("PA"), r.states, "Ohio's routing is uninstalled")
        assertTrue(r.covers(erie) && r.covers(pittsburgh), "the other state still routes")
        assertFalse(r.covers(columbus))

        // a fingerprint mismatch: search unusable, map and routing usable
        lib = library(readers.copy(addrKeyFingerprint = "0".repeat(64)))
        runBlocking { lib.refresh() }
        p = lib.installed.value.packs.single()
        assertEquals("built with another address normaliser", p.searchProblem)
        assertNull(p.searchFile)
        assertTrue(File(p.mapFile).isFile)
        assertTrue(p.inRoutingSet)
        assertTrue(lib.installed.value.routing!!.covers(erie))
        assertNotNull(p.addressesFile)
        lib = library(readers.copy(addrKeyFingerprint = fingerprint))
        runBlocking { lib.refresh() }
        p = lib.installed.value.packs.single()
        assertNull(p.searchProblem)
        assertNotNull(p.searchFile)
    }
}
