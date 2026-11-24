package io.github.spysyweeb.beamline.installed

import io.github.spysyweeb.beamline.api.data.InstallStatus
import io.github.spysyweeb.beamline.api.data.PackFolder
import io.github.spysyweeb.beamline.packs.InstallRecord
import io.github.spysyweeb.beamline.packs.InstallRules
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
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** The installer hand-off for downloads (docs/architecture-downloads.md 6.6) on synthetic packs. */
class InstalledFolderTest {

    @TempDir
    lateinit var tmp: File

    private val scopes = mutableListOf<CoroutineScope>()
    private val packs get() = File(tmp, "packs")
    private val work get() = File(tmp, "work")

    /** A new library; the one made before is ended first (one library on the folders at a time, as in the app). */
    private fun library(free: (File) -> Long = { Long.MAX_VALUE / 4 }): InstalledLibrary {
        killAll()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also { scopes += it }
        return InstalledLibrary(packs, File(tmp, "routing"), MiniStates.READERS, InstallRules(freeSpaceMarginMb = 1), scope, Dispatchers.IO, free)
    }

    private fun killAll() = runBlocking { scopes.forEach { it.coroutineContext.job.cancelAndJoin() } }

    /** Nothing may still work in the temporary folder when JUnit deletes it (Windows keeps open files). */
    @AfterEach
    fun stop() = killAll()

    private fun InstalledLibrary.result(): InstallStatus = runBlocking {
        withTimeout(60_000) { install.first { it !is InstallStatus.Running && it !is InstallStatus.Idle } }
    }

    private fun downloads(name: String) = File(tmp, "downloads/$name")

    private fun record(id: String) = InstallRecord.parse(File(packs, "$id/install-record.json").readText())

    @Test
    fun `a download folder is installed by moving its parts, none copied`() {
        val lib = library()
        assertTrue(lib.installsFromFolder)
        val pa = MiniStates.pack("pa", routingTmp = work)
        val dir = downloads("us-pa-202611160400")
        val folder = pa.folder(dir)
        // distinct last-modified times, which a rename keeps and a copy does not
        val times = HashMap<String, Long>()
        var t = 1_700_000_000_000L
        for (name in pa.files.keys - "routing.tar") {
            File(dir, name).setLastModified(t)
            times[name] = File(dir, name).lastModified()
            t += 86_400_000L
        }
        assertTrue(lib.startInstallFromFolder(folder, true))
        val r = lib.result()
        assertTrue(r is InstallStatus.Done, r.toString())
        assertEquals(listOf("manifest.json"), dir.list()!!.toList(), "the folder holds no part file afterwards")
        for ((name, time) in times) assertEquals(time, File(packs, "us-pa/$name").lastModified(), "$name kept its time: moved, not copied")
        assertEquals("download", record("us-pa").source)
        assertTrue(record("us-pa").routingMerged)
        val p = lib.installed.value.packs.single()
        assertEquals(202611160400L, p.packVersion)
        assertTrue(p.inRoutingSet)
        assertFalse(File(packs, "us-pa/routing.tar").exists())
    }

    @Test
    fun `house numbers left out stay in the download folder`() {
        val lib = library()
        val pa = MiniStates.pack("pa", routingTmp = work)
        val dir = downloads("us-pa-1")
        assertTrue(lib.startInstallFromFolder(pa.folder(dir), false))
        assertTrue(lib.result() is InstallStatus.Done)
        assertEquals(setOf("manifest.json", "addresses.sqlite"), dir.list()!!.toSet())
        assertEquals(listOf("addresses"), record("us-pa").skippedParts)
        assertEquals(MiniStates.OSM, lib.installed.value.packs.single().attribution)
    }

    @Test
    fun `a failure in the middle moves the files back`() {
        val lib = library()
        val pa = MiniStates.pack("pa", routingTmp = work)
        val dir = downloads("us-pa-2")
        pa.folder(dir)
        // the last part moved (addresses) is damaged: map, routing, routing.json and search were moved first
        File(dir, "addresses.sqlite").writeBytes(MiniStates.sqlite(16384, 77))
        val before = dir.listFiles()!!.associate { it.name to (it.length() to it.lastModified()) }
        assertTrue(lib.startInstallFromFolder(PackFolder("us-pa-2", dir.absolutePath), true))
        val r = lib.result() as InstallStatus.Failed
        assertEquals("addresses.sqlite is damaged: its SHA-256 does not match the manifest", r.message)
        assertEquals(before, dir.listFiles()!!.associate { it.name to (it.length() to it.lastModified()) }, "every moved file is back")
        assertEquals(emptyList<String>(), packs.list()!!.toList())
        assertTrue(lib.installed.value.packs.isEmpty())
    }

    @Test
    fun `free space is counted without the moved parts`() {
        val pa = MiniStates.pack("pa", routingTmp = work)
        val parts = pa.files.values.sumOf { it.size.toLong() }
        val routingPart = pa.files.getValue("routing.tar").size.toLong()
        val probe = library()
        val archiveNeed = runBlocking { probe.inspect(pa.archiveSource()) }.neededBytes
        val folderNeed = routingPart + InstalledCore.ROUTING_RESERVE + 1_000_000L
        assertEquals(archiveNeed - parts - InstalledCore.MANIFEST_RESERVE, folderNeed)
        val tight = library(free = { folderNeed - 1 })
        val dir = downloads("us-pa-3")
        assertTrue(tight.startInstallFromFolder(pa.folder(dir), true))
        assertTrue((tight.result() as InstallStatus.Failed).message.contains("free space"))
        assertEquals(pa.files.keys + "manifest.json", dir.list()!!.toSet(), "nothing moved")
        val enough = library(free = { folderNeed })
        assertTrue(enough.startInstallFromFolder(PackFolder("us-pa-3", dir.absolutePath), true))
        assertTrue(enough.result() is InstallStatus.Done)
    }

    @Test
    fun `house numbers are added from a folder and removed again`() {
        val lib = library()
        val pa = MiniStates.pack("pa", routingTmp = work)
        assertTrue(lib.changesOptionalParts)
        assertTrue(lib.startInstall(pa.archiveSource(), false))
        assertTrue(lib.result() is InstallStatus.Done)
        lib.dismissInstallResult()
        val packDir = File(packs, "us-pa")
        val others = packDir.listFiles()!!.filter { it.name != "install-record.json" }.associate { it.name to (it.length() to it.lastModified()) }
        assertEquals(MiniStates.OSM, lib.installed.value.packs.single().attribution)

        // refused while held, and for another build of the state
        val hold = lib.holdChanges("Not while guiding")!!
        val add = downloads("us-pa-add")
        val folder = pa.folder(add, parts = listOf("addresses.sqlite"))
        assertFalse(lib.startAddOptionalParts(folder))
        assertEquals("Not while guiding", runBlocking { lib.removeOptionalParts("us-pa") })
        hold.close()
        val newer = MiniStates.pack("pa", version = 202611170900, routingTmp = work).folder(downloads("us-pa-newer"), parts = listOf("addresses.sqlite"))
        assertFalse(lib.startAddOptionalParts(newer), "a folder whose manifest has another pack_version")

        val g = lib.installed.value.generation
        assertTrue(lib.startAddOptionalParts(folder))
        assertTrue(lib.result() is InstallStatus.Done)
        lib.dismissInstallResult()
        val p = lib.installed.value.packs.single()
        assertEquals(g + 1, lib.installed.value.generation)
        assertEquals(MiniStates.ADDRESS_CREDIT, p.attribution, "the credit gains the address sources")
        assertFalse(p.addressesSkipped)
        assertTrue(File(p.addressesFile!!).isFile)
        assertEquals(listOf("manifest.json"), add.list()!!.toList())
        assertEquals(emptyList<String>(), record("us-pa").skippedParts)
        val after = packDir.listFiles()!!.filter { it.name != "install-record.json" && it.name != "addresses.sqlite" }
            .associate { it.name to (it.length() to it.lastModified()) }
        assertEquals(others, after, "nothing else in the pack folder changes")

        assertNull(runBlocking { lib.removeOptionalParts("us-pa") })
        val q = lib.installed.value.packs.single()
        assertEquals(g + 2, lib.installed.value.generation)
        assertEquals(MiniStates.OSM, q.attribution, "credit back")
        assertNull(q.addressesFile)
        assertTrue(q.addressesSkipped)
        assertFalse(File(packDir, "addresses.sqlite").exists(), "file gone")
        assertEquals(listOf("addresses"), record("us-pa").skippedParts)
        assertTrue(q.inRoutingSet)
    }
}
