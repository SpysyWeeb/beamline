package io.github.spysyweeb.beamline.installed

import io.github.spysyweeb.beamline.api.data.InstallStatus
import io.github.spysyweeb.beamline.api.data.InstalledSet
import io.github.spysyweeb.beamline.api.data.PackArchive
import io.github.spysyweeb.beamline.api.data.PackIssue
import io.github.spysyweeb.beamline.packs.InstallRecord
import io.github.spysyweeb.beamline.packs.InstallRules
import io.github.spysyweeb.beamline.packs.Readers
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
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
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** The rules of docs/architecture.md 2.2 and 5.1 P on small synthetic format-3 packs. */
class InstalledLibraryTest {

    @TempDir
    lateinit var tmp: File

    private val scopes = LinkedHashMap<Any, CoroutineScope>()
    private val packs get() = File(tmp, "packs")
    private val routing get() = File(tmp, "routing")
    private val work get() = File(tmp, "work")

    /**
     * A new library on the test's folders. As in the app (one process, one library), the one made
     * before is ended first ([killAll]): two libraries working on the same folders at once would
     * take each other's staging folders for leftovers of a crash.
     */
    private fun library(readers: Readers = MiniStates.READERS, free: (File) -> Long = { Long.MAX_VALUE / 4 }): InstalledLibrary {
        killAll()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        return InstalledLibrary(packs, routing, readers, InstallRules(freeSpaceMarginMb = 1), scope, Dispatchers.IO, free)
            .also { scopes[it] = scope }
    }

    /** Ends every library as a killed process ends: its coroutines are cancelled and waited for. */
    private fun killAll() = runBlocking { scopes.values.forEach { it.coroutineContext.job.cancelAndJoin() } }

    /** Nothing may still work in the temporary folder when JUnit deletes it (Windows keeps open files). */
    @AfterEach
    fun stop() = killAll()

    private fun pa(version: Long = 202611160400, salt: Int = 0) = MiniStates.pack("pa", version, salt, routingTmp = work)
    private fun oh() = MiniStates.pack("oh", routingTmp = work)

    private fun InstalledLibrary.result(): InstallStatus = runBlocking {
        withTimeout(60_000) { install.first { it !is InstallStatus.Running && it !is InstallStatus.Idle } }
    }

    private fun InstalledLibrary.installOk(archive: PackArchive, addresses: Boolean = true): InstallStatus.Done {
        assertTrue(startInstall(archive, addresses))
        val r = result()
        assertTrue(r is InstallStatus.Done, r.toString())
        dismissInstallResult()
        return r as InstallStatus.Done
    }

    private fun set(lib: InstalledLibrary): InstalledSet = lib.installed.value

    private fun hidden() = packs.listFiles { f -> f.name.startsWith(".") }.orEmpty().map { it.name }

    private fun record(id: String) = InstallRecord.parse(File(packs, "$id/install-record.json").readText())

    @Test
    fun `an archive installs all parts, merges routing and keeps no per-state tar`() {
        val lib = library()
        runBlocking { lib.refresh() }
        val g0 = set(lib).generation
        val done = lib.installOk(pa().archiveSource())
        assertEquals("Pennsylvania", done.name)
        assertEquals(emptyList<String>(), done.skippedParts)
        val s = set(lib)
        assertEquals(g0 + 1, s.generation, "one generation per change")
        val p = s.packs.single()
        assertEquals("us-pa", p.packId)
        assertEquals(202611160400L, p.packVersion)
        assertEquals(MiniStates.ADDRESS_CREDIT, p.attribution)
        assertTrue(p.inRoutingSet)
        assertTrue(p.verified)
        assertNotNull(p.searchFile)
        assertNotNull(p.addressesFile)
        assertFalse(p.addressesSkipped)
        assertNull(p.searchProblem)
        assertEquals("c".repeat(64), p.searchRankingSha256)
        val dir = File(packs, "us-pa")
        assertEquals(setOf("manifest.json", "map.pmtiles", "routing.json", "search.sqlite", "addresses.sqlite", "install-record.json"), dir.list()!!.toSet())
        val rec = record("us-pa")
        assertEquals(InstallRecord("x", "sha256", "archive", emptyList(), true).copy(installedUtc = rec.installedUtc), rec)
        assertEquals(emptyList<String>(), hidden(), "no staging folder is left")
        val r = s.routing!!
        assertEquals(listOf("PA"), r.states)
        assertEquals(MiniStates.BUILD, r.graphBuild)
        assertTrue(752000 in r.level2Tiles && 752001 in r.level2Tiles)
        assertTrue(File(routing, "routing.tar").isFile)
        assertFalse(File(routing, "routing.tar.new").exists())
        // rule N5: the merged set's engine configuration never sets a tile URL
        val cfg = Json.parseToJsonElement(File(r.engineConfigFile).readText()).jsonObject
        val tileUrl = cfg["mjolnir"]?.jsonObject?.get("tile_url")?.jsonPrimitive?.content
        assertTrue(tileUrl.isNullOrEmpty(), "mjolnir.tile_url must be empty or absent, was $tileUrl")
    }

    @Test
    fun `the size on the phone counts the routing part in the merged set and no house numbers being removed`() {
        val lib = library()
        lib.installOk(pa().archiveSource())
        val dir = File(packs, "us-pa")
        fun folderBytes() = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        val m = io.github.spysyweeb.beamline.packs.PackManifest.parse(File(dir, "manifest.json").readText())
        val routingBytes = m.routing!!.bytes
        assertTrue(routingBytes > 0)
        assertFalse(File(dir, m.routing!!.file).exists(), "the routing part lives in the merged set, not in the folder")
        // docs/architecture-downloads.md 1.2: the row's size is the state's whole size on the phone
        assertEquals(folderBytes() + routingBytes, set(lib).packs.single().bytesOnDisk)
        // every set published while the house numbers go (the generation comes before the delete) has the new size
        val seen = java.util.Collections.synchronizedList(mutableListOf<Long>())
        val watch = CoroutineScope(Dispatchers.Unconfined).launch {
            lib.installed.collect { s -> s.packs.singleOrNull()?.takeIf { it.addressesSkipped }?.let { seen += it.bytesOnDisk } }
        }
        assertNull(runBlocking { lib.removeOptionalParts("us-pa") })
        runBlocking { watch.cancelAndJoin() }
        val after = folderBytes() + routingBytes
        assertFalse(File(dir, "addresses.sqlite").exists())
        assertEquals(after, set(lib).packs.single().bytesOnDisk)
        assertTrue(seen.isNotEmpty())
        assertEquals(listOf(after), seen.distinct(), "no set showed the old size after the house numbers were removed")
    }

    @Test
    fun `without house numbers the credit names no address source`() {
        val lib = library()
        val done = lib.installOk(pa().archiveSource(), addresses = false)
        assertEquals(listOf("addresses"), done.skippedParts)
        val p = set(lib).packs.single()
        assertEquals(MiniStates.OSM, p.attribution)
        assertNull(p.addressesFile)
        assertTrue(p.addressesSkipped)
        assertFalse(File(packs, "us-pa/addresses.sqlite").exists())
        assertEquals(listOf("addresses"), record("us-pa").skippedParts)
    }

    @Test
    fun `an update replaces the installed state in one generation`() {
        val lib = library()
        lib.installOk(pa().archiveSource())
        val g = set(lib).generation
        lib.installOk(pa(version = 202611170900, salt = 3).archiveSource())
        val s = set(lib)
        assertEquals(g + 1, s.generation)
        assertEquals(202611170900L, s.packs.single().packVersion)
        assertEquals(listOf("us-pa"), packs.list()!!.filter { !it.startsWith(".") })
        assertEquals(emptyList<String>(), hidden())
        assertEquals(listOf("PA"), s.routing!!.states)
    }

    @Test
    fun `removal uninstalls routing and the other state still routes`() {
        val lib = library()
        lib.installOk(pa().archiveSource())
        lib.installOk(oh().archiveSource())
        assertEquals(listOf("OH", "PA"), set(lib).routing!!.states)
        val g = set(lib).generation
        assertNull(runBlocking { lib.remove("us-oh") })
        val s = set(lib)
        assertEquals(g + 1, s.generation)
        assertEquals(listOf("us-pa"), s.packs.map { it.packId })
        assertEquals(listOf("PA"), s.routing!!.states)
        assertTrue(751999 !in s.routing!!.level2Tiles, "Ohio's own tile left the set")
        assertTrue(752000 in s.routing!!.level2Tiles, "the shared tile stays for Pennsylvania")
        assertFalse(File(packs, "us-oh").exists())
        assertEquals(emptyList<String>(), hidden())
        assertNull(runBlocking { lib.remove("us-pa") })
        assertNull(set(lib).routing, "no routing set without a state")
        assertEquals("No state with the id us-pa is installed.", runBlocking { lib.remove("us-pa") })
    }

    @Test
    fun `installs and removals are refused while held`() {
        val lib = library()
        lib.installOk(pa().archiveSource())
        val g = set(lib).generation
        val hold = lib.holdChanges("Not while guiding")!!
        assertEquals("Not while guiding", set(lib).changesHeldBy)
        assertEquals(g, set(lib).generation, "a hold is not a change of the installed data")
        assertFalse(lib.startInstall(oh().archiveSource(), true))
        assertEquals("Not while guiding", runBlocking { lib.remove("us-pa") })
        assertTrue(runBlocking { lib.inspect(oh().archiveSource()) }.problems.contains("Not while guiding"))
        hold.close()
        hold.close()
        assertNull(set(lib).changesHeldBy)
        lib.installOk(oh().archiveSource())
    }

    @Test
    fun `holdChanges is null while an install runs`() {
        val lib = library()
        val bytes = pa().archive()
        val started = CountDownLatch(1)
        val go = CountDownLatch(1)
        val slow = PackArchive("slow.tar") {
            object : InputStream() {
                val inner = bytes.inputStream()
                var first = true
                override fun read(): Int = inner.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (first) {
                        first = false
                        started.countDown()
                        go.await(30, TimeUnit.SECONDS)
                    }
                    return inner.read(b, off, len)
                }
            }
        }
        assertTrue(lib.startInstall(slow, true))
        assertTrue(started.await(30, TimeUnit.SECONDS))
        assertNull(lib.holdChanges("Not while guiding"), "no hold while an install runs (rule P5)")
        assertFalse(lib.startInstall(oh().archiveSource(), true), "one change at a time")
        go.countDown()
        assertTrue(lib.result() is InstallStatus.Done)
        assertNotNull(lib.holdChanges("Not while guiding"))
    }

    @Test
    fun `holdChanges is null while a removal runs`() {
        val lib = library()
        lib.installOk(pa().archiveSource())
        val aside = CountDownLatch(1)
        val go = CountDownLatch(1)
        lib.core.faultHook = {
            if (it == "removal-aside") {
                aside.countDown()
                go.await(30, TimeUnit.SECONDS)
            }
        }
        val removed = CompletableFuture<String?>()
        Thread { removed.complete(runBlocking { lib.remove("us-pa") }) }.start()
        assertTrue(aside.await(30, TimeUnit.SECONDS))
        assertNull(lib.holdChanges("Not while guiding"), "no hold while a removal runs (rule P5)")
        assertFalse(lib.startInstall(oh().archiveSource(), true), "one change at a time")
        assertEquals("Another change of the installed states is running.", runBlocking { lib.removeOptionalParts("us-pa") })
        go.countDown()
        assertNull(removed.get(30, TimeUnit.SECONDS))
        assertTrue(set(lib).packs.isEmpty())
        assertNotNull(lib.holdChanges("Not while guiding"))
    }

    @Test
    fun `a version-2 pack is refused with rebuild the pack`() {
        val lib = library()
        val v2 = MiniStates.pack("pa", manifestVersion = 2, routingTmp = work)
        val info = runBlocking { lib.inspect(v2.archiveSource()) }
        assertEquals(listOf("manifest_version 2 is an old pack format; rebuild the pack"), info.problems)
        assertTrue(lib.startInstall(v2.archiveSource(), true))
        val r = lib.result() as InstallStatus.Failed
        assertTrue(r.message.endsWith("an old pack format; rebuild the pack"), r.message)
        assertEquals(emptyList<String>(), packs.list()!!.toList())
        assertFalse(File(routing, "routing.tar").exists())
    }

    @Test
    fun `too little space is refused before copying, counting the routing merge`() {
        val mini = pa()
        val probe = library()
        val info = runBlocking { probe.inspect(mini.archiveSource()) }
        val parts = mini.files.values.sumOf { it.size.toLong() }
        val routingPart = mini.files.getValue("routing.tar").size.toLong()
        val margin = 1_000_000L + InstalledCore.ROUTING_RESERVE
        assertEquals(parts + InstalledCore.MANIFEST_RESERVE + routingPart + margin, info.neededBytes, "parts + the new merged set + the margins")
        assertEquals(mini.files.getValue("addresses.sqlite").size.toLong(), info.addressesBytes)
        assertEquals(emptyList<String>(), info.problems, "free space is never a problem of inspect")
        val tight = library(free = { info.neededBytes - 1 })
        assertTrue(tight.startInstall(mini.archiveSource(), true))
        val r = tight.result() as InstallStatus.Failed
        assertTrue(r.message.contains("needs") && r.message.contains("free space"), r.message)
        assertEquals(emptyList<String>(), packs.list()!!.toList(), "nothing copied")
        val enough = library(free = { info.neededBytes })
        enough.installOk(mini.archiveSource())
        // with Pennsylvania's merged set on the phone, Ohio's need counts that set too
        val ohNeed = runBlocking { enough.inspect(oh().archiveSource()) }.neededBytes
        val ohFiles = oh().files
        assertEquals(ohFiles.values.sumOf { it.size.toLong() } + InstalledCore.MANIFEST_RESERVE + File(routing, "routing.tar").length() +
            ohFiles.getValue("routing.tar").size + margin, ohNeed)
    }

    @Test
    fun `a fingerprint mismatch makes search unusable, map and routing stay`() {
        val lib = library(readers = MiniStates.READERS.copy(addrKeyFingerprint = "f".repeat(64)))
        lib.installOk(pa().archiveSource())
        val p = set(lib).packs.single()
        assertEquals("built with another address normaliser", p.searchProblem)
        assertNull(p.searchFile)
        assertTrue(File(p.mapFile).isFile)
        assertTrue(p.inRoutingSet)
        assertNotNull(set(lib).routing)
        val same = library(readers = MiniStates.READERS.copy(addrKeyFingerprint = MiniStates.FP))
        runBlocking { same.refresh() }
        assertNull(same.installed.value.packs.single().searchProblem)
    }

    @Test
    fun `cancel before the merge leaves nothing behind`() {
        val lib = library()
        val bytes = pa().archive()
        val started = CountDownLatch(1)
        val slow = PackArchive("slow.tar") {
            object : InputStream() {
                val inner = bytes.inputStream()
                var n = 0
                override fun read(): Int = inner.read()
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (n++ == 1) {
                        started.countDown()
                        Thread.sleep(500)
                    }
                    return inner.read(b, off, minOf(len, 512))
                }
            }
        }
        assertTrue(lib.startInstall(slow, true))
        assertTrue(started.await(30, TimeUnit.SECONDS))
        lib.cancelInstall()
        val r = lib.result()
        assertTrue(r is InstallStatus.Cancelled, r.toString())
        assertEquals(emptyList<String>(), packs.list()!!.toList())
        assertFalse(File(routing, "routing.tar").exists())
    }

    @Test
    fun `a damaged part fails the install and nothing changes`() {
        val lib = library()
        lib.installOk(oh().archiveSource())
        val good = pa()
        val bad = MiniStates.Pack(good.id, good.files + ("search.sqlite" to MiniStates.sqlite(8192, 99)), good.manifest)
        val g = set(lib).generation
        assertTrue(lib.startInstall(bad.archiveSource(), true))
        val r = lib.result() as InstallStatus.Failed
        assertEquals("search.sqlite is damaged: its SHA-256 does not match the manifest", r.message)
        assertEquals(g, set(lib).generation)
        assertEquals(listOf("OH"), set(lib).routing!!.states)
        assertEquals(listOf("us-oh"), packs.list()!!.toList())
    }

    // ------------------------------------------------------------------ crash recovery

    private fun crashAt(point: String, block: (InstalledLibrary) -> Unit) {
        val lib = library()
        lib.core.faultHook = { if (it == point) throw SimulatedCrash(it) }
        block(lib)
    }

    /** A new process: a new library on the same folders, then refresh (cleanUp). */
    private fun restart(): InstalledLibrary = library().also { runBlocking { it.refresh() } }

    @Test
    fun `a crash after staging leaves the state as it was`() {
        library().installOk(oh().archiveSource())
        crashAt("staged") { lib ->
            assertTrue(lib.startInstall(pa().archiveSource(), true))
            assertTrue(lib.result() is InstallStatus.Failed)
        }
        assertTrue(hidden().any { it.startsWith(".staging-us-pa") }, "the crash left the staging folder")
        val lib = restart()
        assertEquals(emptyList<String>(), hidden())
        assertEquals(listOf("us-oh"), set(lib).packs.map { it.packId })
        assertEquals(listOf("OH"), set(lib).routing!!.states)
    }

    @Test
    fun `a crash after the routing commit rolls the install forward`() {
        crashAt("routing-committed") { lib ->
            assertTrue(lib.startInstall(pa().archiveSource(), true))
            assertTrue(lib.result() is InstallStatus.Failed)
        }
        val lib = restart()
        assertEquals(emptyList<String>(), hidden())
        val p = set(lib).packs.single()
        assertEquals("us-pa", p.packId)
        assertTrue(p.inRoutingSet)
        assertFalse(File(packs, "us-pa/routing.tar").exists())
        assertTrue(record("us-pa").routingMerged)
        assertEquals(listOf("PA"), set(lib).routing!!.states)
    }

    @Test
    fun `a crash before the rename of an update finishes the update`() {
        library().installOk(pa().archiveSource())
        crashAt("before-rename") { lib ->
            assertTrue(lib.startInstall(pa(version = 202611170900, salt = 5).archiveSource(), true))
            assertTrue(lib.result() is InstallStatus.Failed)
        }
        val lib = restart()
        assertEquals(emptyList<String>(), hidden())
        assertEquals(202611170900L, set(lib).packs.single().packVersion)
        assertEquals(listOf("PA"), set(lib).routing!!.states)
    }

    @Test
    fun `a crash during removal finishes the removal`() {
        val first = library()
        first.installOk(pa().archiveSource())
        first.installOk(oh().archiveSource())
        crashAt("removal-aside") { lib ->
            runBlocking { lib.refresh() }
            assertThrows<SimulatedCrash> { runBlocking { lib.remove("us-oh") } }
        }
        assertTrue(hidden().any { it.startsWith(".removing-us-oh") })
        val lib = restart()
        assertEquals(emptyList<String>(), hidden())
        assertEquals(listOf("us-pa"), set(lib).packs.map { it.packId })
        assertEquals(listOf("PA"), set(lib).routing!!.states, "Ohio's routing is uninstalled")
    }

    // ------------------------------------------------------------------ after the review (report 12, section 9)

    private fun visible() = packs.list().orEmpty().filter { !it.startsWith(".") }.sorted()

    private fun routingStates() = File(routing, "packs").list().orEmpty().map { it.removeSuffix(".json") }.sorted()

    private fun InstalledLibrary.failed(archive: PackArchive): String {
        assertTrue(startInstall(archive, true))
        val r = result()
        assertTrue(r is InstallStatus.Failed, r.toString())
        dismissInstallResult()
        return (r as InstallStatus.Failed).message
    }

    @Test
    fun `while held a refresh changes no file and publishes nothing, the repair runs at the release`() {
        val lib = library()
        lib.installOk(pa().archiveSource())
        lib.installOk(oh().archiveSource())
        runBlocking { lib.refresh() }
        val before = set(lib)
        val tileSet = File(routing, "routing.tar")
        val tileSetFile = tileSet.length() to tileSet.lastModified()
        val hold = lib.holdChanges("Not while guiding")!!
        // Ohio's folder stops passing its checks during the trip: its routing state is now an orphan
        assertTrue(File(packs, "us-oh/search.sqlite").delete())
        runBlocking { lib.refresh() }
        assertEquals(before.generation, set(lib).generation, "no new generation under a trip")
        assertEquals(before.routing, set(lib).routing)
        assertEquals(listOf("oh", "pa"), routingStates(), "no routing state uninstalled under a trip")
        assertEquals(tileSetFile, tileSet.length() to tileSet.lastModified(), "the tile set is untouched")
        hold.close()
        // the repair and the rescan run by themselves once the last hold is released
        val after = runBlocking { withTimeout(30_000) { lib.installed.first { it.generation > before.generation } } }
        assertEquals(before.generation + 1, after.generation)
        assertEquals(listOf("PA"), after.routing!!.states)
        assertTrue(751999 !in after.routing!!.level2Tiles, "Ohio's own tile left the set")
        assertEquals(listOf("pa"), routingStates())
        assertEquals(listOf("us-pa"), after.packs.map { it.packId })
        assertTrue(after.issues.any { it.folder == "us-oh" }, after.issues.toString())
        assertNull(after.changesHeldBy)
    }

    @Test
    fun `a removal on a full phone uninstalls the routing tiles once the folder is gone`() {
        // the phone is full until the removed folder is deleted
        val lib = library(free = { if (packs.list().orEmpty().any { it.startsWith(".removing-") }) 0L else Long.MAX_VALUE / 4 })
        lib.installOk(pa().archiveSource())
        lib.installOk(oh().archiveSource())
        val g = set(lib).generation
        assertNull(runBlocking { lib.remove("us-oh") })
        val s = set(lib)
        assertEquals(g + 2, s.generation, "the removal, then the routing tiles once the folder freed the space")
        assertEquals(listOf("PA"), s.routing!!.states)
        assertTrue(751999 !in s.routing!!.level2Tiles, "Ohio's own tile left the set")
        assertEquals(listOf("pa"), routingStates())
        assertEquals(emptyList<PackIssue>(), s.issues)
        assertEquals(listOf("us-pa"), visible())
        assertEquals(emptyList<String>(), hidden())
    }

    @Test
    fun `a removal that cannot free the routing tiles says so, and a later refresh removes them`() {
        val full = AtomicBoolean(false)
        val lib = library(free = { if (full.get()) 0L else Long.MAX_VALUE / 4 })
        lib.installOk(pa().archiveSource())
        lib.installOk(oh().archiveSource())
        full.set(true)
        assertEquals(
            "Ohio is removed, but its roads stay in the route planner until the phone has more free space. Beamline removes them then.",
            runBlocking { lib.remove("us-oh") },
        )
        var s = set(lib)
        assertEquals(listOf("us-pa"), s.packs.map { it.packId })
        assertEquals(listOf("PA"), s.routing!!.states)
        assertTrue(751999 in s.routing!!.level2Tiles, "the engine still has Ohio's tiles, and the set says so")
        assertEquals(listOf(PackIssue("routing/oh", "the roads of OH stay in the route planner until there is enough free space to remove them")), s.issues)
        assertEquals(listOf("us-pa"), visible())
        assertEquals(emptyList<String>(), hidden())
        // a refresh on the still full phone neither throws nor changes anything
        runBlocking { lib.refresh() }
        assertEquals(s, set(lib))
        full.set(false)
        runBlocking { lib.refresh() }
        s = set(lib)
        assertTrue(751999 !in s.routing!!.level2Tiles)
        assertEquals(listOf("pa"), routingStates())
        assertEquals(emptyList<PackIssue>(), s.issues)
    }

    @Test
    fun `a roll forward that fails keeps the staged install, which owns its routing, and finishes later`() {
        crashAt("routing-committed") { lib ->
            assertTrue(lib.startInstall(pa().archiveSource(), true))
            assertTrue(lib.result() is InstallStatus.Failed)
        }
        killAll()
        // the next process cannot write the record or rename (a full disk): the roll forward fails
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val core = InstalledCore(
            packs, routing, MiniStates.READERS, InstallRules(freeSpaceMarginMb = 1), scope, Dispatchers.IO, { Long.MAX_VALUE / 4 },
            initialFaultHook = { if (it == "roll-forward") throw IOException("no space left") },
        )
        scopes[core] = scope
        runBlocking { core.refresh() }
        val s = core.installed.value
        assertTrue(hidden().any { it.startsWith(".staging-us-pa") }, "the staged install is kept for the next try")
        assertEquals(listOf("pa"), routingStates(), "its routing state is not taken for an orphan")
        assertTrue(s.packs.isEmpty())
        val issue = s.issues.single()
        assertTrue(issue.folder.startsWith(".staging-us-pa"), issue.toString())
        assertEquals("the install of Pennsylvania could not be finished (the app's storage could not be written); Beamline tries again later", issue.reason)
        // the next start rolls it forward
        val lib = restart()
        assertEquals(emptyList<String>(), hidden())
        val p = set(lib).packs.single()
        assertEquals("us-pa", p.packId)
        assertTrue(p.inRoutingSet)
        assertTrue(record("us-pa").routingMerged)
        assertEquals(emptyList<PackIssue>(), set(lib).issues)
    }

    @Test
    fun `routing tiles of another state, and another pack for an installed state, are refused`() {
        val lib = library()
        lib.installOk(pa().archiveSource())
        val g = set(lib).generation
        // an Ohio pack whose routing.json names Pennsylvania (and holds other Pennsylvania tiles)
        val wrong = MiniStates.pack("oh", salt = 7, routingTmp = work, routingState = "pa")
        assertEquals("Ohio: its routing tiles are for another state (PA), not OH. Get the pack again.", lib.failed(wrong.archiveSource()))
        assertEquals(g, set(lib).generation)
        assertEquals(listOf("us-pa"), visible())
        assertEquals(emptyList<String>(), hidden())
        assertEquals(listOf("pa"), routingStates())
        assertTrue(set(lib).packs.single().inRoutingSet, "Pennsylvania's tiles were not replaced")
        // a second pack_id for Pennsylvania would take over the same routing state
        val other = MiniStates.pack("pa", routingTmp = work, packId = "us-pa-west")
        val why = "Pennsylvania is installed already (as us-pa). Remove it first."
        assertEquals(listOf(why), runBlocking { lib.inspect(other.archiveSource()) }.problems)
        assertEquals(why, lib.failed(other.archiveSource()))
        assertEquals(g, set(lib).generation)
        assertEquals(listOf("us-pa"), visible())
    }

    @Test
    fun `a pack from another road build is refused in plain words, and remove, install, add again works`() {
        val lib = library()
        lib.installOk(pa().archiveSource())
        lib.installOk(oh().archiveSource())
        val g = set(lib).generation
        val newBuild = "261201-pa-oh-v3.9.1-c00001"
        val paNew = MiniStates.pack("pa", version = 202612010400, salt = 9, routingTmp = work, build = newBuild)
        assertEquals(
            "Pennsylvania was built from another version of the road data than Ohio on this phone, and the two cannot be mixed. " +
                "Remove Ohio first, then install Pennsylvania and add Ohio again.",
            lib.failed(paNew.archiveSource()),
        )
        assertEquals(g, set(lib).generation)
        assertEquals(emptyList<String>(), hidden())
        assertEquals(MiniStates.BUILD, set(lib).routing!!.graphBuild)
        assertEquals(202611160400L, set(lib).packs.first { it.packId == "us-pa" }.packVersion)
        // the way forward: remove Ohio, install the new Pennsylvania, add Ohio of the same build
        assertNull(runBlocking { lib.remove("us-oh") })
        lib.installOk(paNew.archiveSource())
        assertEquals(newBuild, set(lib).routing!!.graphBuild)
        assertTrue(lib.failed(oh().archiveSource()).startsWith("Ohio was built from another version of the road data than Pennsylvania"))
        lib.installOk(MiniStates.pack("oh", version = 202612010400, routingTmp = work, build = newBuild).archiveSource())
        assertEquals(listOf("OH", "PA"), set(lib).routing!!.states)
        assertEquals(newBuild, set(lib).routing!!.graphBuild)
    }

    @Test
    fun `an unexpected exception gives fixed words, never its own text`() {
        val lib = library()
        val gone = PackArchive("gone.tar") { throw FileNotFoundException("/private/folder/gone.tar (No such file or directory)") }
        val message = lib.failed(gone)
        assertEquals("Reading gone.tar or writing into the app's storage failed. Try again.", message)
        assertFalse(message.contains("/private/folder"))
        val odd = PackArchive("odd.tar") { throw IllegalStateException("engineer text /private/folder") }
        assertEquals("The install stopped because of an unexpected problem (IllegalStateException).", lib.failed(odd))
        assertEquals(emptyList<String>(), packs.list()!!.toList())
    }

    @Test
    fun `a damaged routing state manifest stops no scan, and the state can be installed again`() {
        val first = library()
        first.installOk(pa().archiveSource())
        first.installOk(oh().archiveSource())
        File(routing, "packs/oh.json").writeText("{\"format\": \"beamline-rou")
        val lib = restart() // a new process: its first scan and refresh() must not throw
        val s = set(lib)
        assertTrue(s.scanned)
        assertEquals(listOf("us-oh", "us-pa"), s.packs.map { it.packId })
        assertEquals(listOf("PA"), s.routing!!.states)
        assertTrue(751999 !in s.routing!!.level2Tiles, "the damaged state left the set")
        assertEquals(listOf("pa"), routingStates())
        assertFalse(s.packs.first { it.packId == "us-oh" }.inRoutingSet)
        assertTrue(s.issues.any { it.folder == "us-oh" && it.reason.contains("not in the routing set") }, s.issues.toString())
        lib.installOk(oh().archiveSource())
        assertEquals(listOf("OH", "PA"), set(lib).routing!!.states)
        assertEquals(emptyList<PackIssue>(), set(lib).issues)
    }
}
