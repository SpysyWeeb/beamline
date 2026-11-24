package io.github.spysyweeb.beamline.download

import io.github.spysyweeb.beamline.api.data.InstallStatus
import io.github.spysyweeb.beamline.api.data.InstallStep
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File

/** docs/architecture-downloads.md 10.1: the state rows (installed, network, hold and busy, hand-off, house numbers, 404, formats) and 1.4's other transitions. */
@OptIn(ExperimentalCoroutinesApi::class)
class EngineStateTest {
    @TempDir
    lateinit var dir: File
    private val server = TestPackServer()

    @AfterEach
    fun stop() {
        server.close()
        EngineHooks.beforeWrite = null
        EngineHooks.afterInflate = null
    }

    private fun TestScope.harness(): Harness {
        server.timeSource = { testScheduler.currentTime }
        return Harness(dir, server, StandardTestDispatcher(testScheduler), { testScheduler.currentTime })
    }

    private val pa = TestPack(
        listOf(
            TestPart("map", "map.pmtiles", randomBytes(600_000), gzip = false),
            TestPart("routing", "routing.tar", textBytes(900_000), gzip = true),
            TestPart("search", "search.sqlite", textBytes(400_000), gzip = true),
            TestPart("addresses", "addresses.sqlite", textBytes(300_000).reversedArray(), gzip = true, optional = true),
        ),
    )

    private fun installedPa(h: Harness, version: Long?, addresses: Boolean, state: String = "PA") {
        h.installed.installed.value = h.installed.installed.value.copy(
            packs = listOf(FakeInstalled.packInfo("us-pa", state, version, "2026-11-14", addresses)),
        )
    }

    @Test
    fun anAlreadyInstalledPackEndsAtRecoverAndAtChecking() = runTest {
        val h = harness()
        h.publish(pa)
        val e = h.engine(backgroundScope)
        e.recover()
        e.start(h.entry(pa), JobKind.INSTALL, includeOptional = true, allowMetered = false, firstRun = true)
        installedPa(h, pa.packVersion, addresses = true)
        // a crash after the installer's rename: a new process finds the pack installed
        val e2 = h.engine(backgroundScope)
        e2.recover()
        assertEquals(JobPhase.DONE, e2.job.value!!.phase)
        assertFalse(h.jobFolder(pa).exists())
        assertTrue(server.log.isEmpty())

        // at CHECKING
        val h3 = Harness(File(dir, "b"), server, StandardTestDispatcher(testScheduler), { testScheduler.currentTime })
        val e3 = h3.engine(backgroundScope)
        e3.recover()
        e3.start(h3.entry(pa), JobKind.INSTALL, true, false, false)
        installedPa(h3, pa.packVersion + 1, addresses = true)
        e3.run()
        assertEquals(JobPhase.DONE, e3.job.value!!.phase)
        assertFalse(h3.jobFolder(pa).exists())
        assertTrue(server.log.isEmpty())

        // installed without house numbers, and the job wants them: not "already installed"
        val h4 = Harness(File(dir, "c"), server, StandardTestDispatcher(testScheduler), { testScheduler.currentTime })
        val e4 = h4.engine(backgroundScope)
        e4.recover()
        e4.start(h4.entry(pa), JobKind.UPDATE, true, false, false)
        installedPa(h4, pa.packVersion, addresses = false)
        e4.run()
        assertTrue(server.log.isNotEmpty())
    }

    @Test
    fun aPackIdInstalledForAnotherStateIsAListMismatch() = runTest {
        val h = harness()
        h.publish(pa)
        installedPa(h, 1, addresses = false, state = "OH")
        val e = h.engine(backgroundScope)
        e.recover()
        e.start(h.entry(pa), JobKind.INSTALL, true, false, false)
        e.run()
        assertEquals(FailReason.LIST_MISMATCH, e.job.value!!.fail)
        assertTrue(server.log.isEmpty())
    }

    @Test
    fun networkStates() = runTest {
        val h = harness()
        h.publish(pa)
        val e = h.engine(backgroundScope)
        e.recover()
        h.network.value = NetworkState.NONE
        e.start(h.entry(pa), JobKind.INSTALL, true, allowMetered = false, firstRun = false)
        val run = launch { e.run() }
        runCurrent()
        assertEquals(JobPhase.WAITING, e.job.value!!.phase)
        assertEquals(WaitReason.NO_NETWORK, e.job.value!!.wait)
        assertTrue(e.needsService.value)
        assertTrue(server.log.isEmpty())

        h.network.value = NetworkState(connected = true, unmetered = false) // mobile data only
        runCurrent()
        assertEquals(WaitReason.NOT_UNMETERED, e.job.value!!.wait)
        assertTrue(server.log.isEmpty())

        e.allowMetered()
        runCurrent()
        run.join()
        assertEquals(JobPhase.DONE, e.job.value!!.phase)
        assertTrue(server.log.isNotEmpty())
        assertTrue(e.job.value!!.allowMetered)
    }

    @Test
    fun wifiBackContinuesAndEachWaitEndsAfterSixHours() = runTest {
        val h = harness()
        h.publish(pa)
        val e = h.engine(backgroundScope)
        e.recover()
        h.network.value = NetworkState(connected = true, unmetered = false)
        e.start(h.entry(pa), JobKind.INSTALL, true, allowMetered = false, firstRun = false)
        val run = launch { e.run() }
        runCurrent()
        assertEquals(WaitReason.NOT_UNMETERED, e.job.value!!.wait)
        h.network.value = NetworkState(connected = true, unmetered = true) // Wi-Fi back
        runCurrent()
        run.join()
        assertEquals(JobPhase.DONE, e.job.value!!.phase)

        for ((i, state) in listOf(NetworkState.NONE, NetworkState(connected = true, unmetered = false)).withIndex()) {
            val hh = Harness(File(dir, "w$i"), server, StandardTestDispatcher(testScheduler), { testScheduler.currentTime })
            val ee = hh.engine(backgroundScope)
            ee.recover()
            hh.network.value = state
            ee.start(hh.entry(pa), JobKind.INSTALL, true, false, false)
            val r = launch { ee.run() }
            runCurrent()
            advanceTimeBy(360 * 60_000L - 1)
            runCurrent()
            assertEquals(JobPhase.WAITING, ee.job.value!!.phase)
            advanceTimeBy(2)
            runCurrent()
            r.join()
            assertEquals(PauseReason.SYSTEM_STOPPED, ee.job.value!!.pause)
            assertTrue(ee.job.value!!.continuesByItself())
            assertFalse(ee.needsService.value)
        }
    }

    @Test
    fun holdAndBusy() = runTest {
        val h = harness()
        h.publish(pa)
        val hold = h.installed.holdChanges("Not while guiding")!!
        val e = h.engine(backgroundScope)
        e.recover()
        e.start(h.entry(pa), JobKind.INSTALL, true, false, false)
        e.run()
        assertEquals(JobPhase.WAITING, e.job.value!!.phase)
        assertEquals(WaitReason.TRIP_IN_PROGRESS, e.job.value!!.wait)
        assertFalse(e.needsService.value)
        assertFalse(h.installed.calls.any { it.startsWith("start") })
        hold.close()
        e.run()
        assertEquals(JobPhase.DONE, e.job.value!!.phase)

        val h2 = Harness(File(dir, "busy"), server, StandardTestDispatcher(testScheduler), { testScheduler.currentTime })
        h2.installed.busy = true
        h2.installed.install.value = InstallStatus.Running("Ohio", InstallStep.COPYING, 1, 2, null)
        val e2 = h2.engine(backgroundScope)
        e2.recover()
        e2.start(h2.entry(pa), JobKind.INSTALL, true, false, false)
        e2.run()
        assertEquals(WaitReason.INSTALLER_BUSY, e2.job.value!!.wait)
        h2.installed.busy = false
        h2.installed.install.value = InstallStatus.Idle
        e2.run()
        assertEquals(JobPhase.DONE, e2.job.value!!.phase)
    }

    @Test
    fun handOffByFolderOrByTheArchiveStandIn() = runTest {
        val h = harness()
        h.publish(pa)
        h.installed.folders = true
        val e = h.engine(backgroundScope)
        e.recover()
        e.start(h.entry(pa), JobKind.INSTALL, includeOptional = false, allowMetered = false, firstRun = false)
        e.run()
        assertEquals(JobPhase.DONE, e.job.value!!.phase)
        assertTrue(h.installed.calls.contains("startInstallFromFolder"))
        assertEquals(h.jobFolder(pa).absolutePath, h.installed.lastFolder)
        assertEquals(listOf("job.json", "manifest.json", "map.pmtiles", "routing.tar", "search.sqlite"), h.installed.lastFolderFiles)
        assertFalse(server.log.any { it.path.contains("addresses") }, "a skipped optional part is not fetched")

        val h2 = Harness(File(dir, "tar"), server, StandardTestDispatcher(testScheduler), { testScheduler.currentTime })
        val e2 = h2.engine(backgroundScope)
        e2.recover()
        e2.start(h2.entry(pa), JobKind.INSTALL, includeOptional = true, allowMetered = false, firstRun = false)
        e2.run()
        assertEquals(JobPhase.DONE, e2.job.value!!.phase)
        assertTrue(h2.installed.calls.contains("startInstall"))
        val tar = h2.installed.lastTar!!
        assertEquals(listOf("manifest.json", "map.pmtiles", "routing.tar", "search.sqlite", "addresses.sqlite"), tar.map { it.name }, "manifest first, then the manifest's order")
        for (p in pa.parts) assertEquals(sha256(p.bytes), sha256(tar.first { it.name == p.file }.data))
        assertTrue(h2.installed.calls.contains("dismissInstallResult"))
    }

    @Test
    fun houseNumbersLater() = runTest {
        val h = harness()
        h.publish(pa)
        installedPa(h, pa.packVersion, addresses = false)
        h.installed.folders = true // P delivers the folder install and the optional-part install together
        val e = h.engine(backgroundScope)
        e.recover()
        // the installer cannot add parts yet: not supported
        assertEquals(StartResult.Refused(StartRefusal.NOT_SUPPORTED), e.start(h.entry(pa), JobKind.ADD_OPTIONAL, true, false, false))
        h.installed.optionalParts = true
        installedPa(h, pa.packVersion - 1, addresses = false)
        assertEquals(StartResult.Refused(StartRefusal.NOT_SUPPORTED), e.start(h.entry(pa), JobKind.ADD_OPTIONAL, true, false, false))
        installedPa(h, pa.packVersion, addresses = false)
        assertEquals(StartResult.Started, e.start(h.entry(pa), JobKind.ADD_OPTIONAL, true, false, false))
        e.run()
        assertEquals(JobPhase.DONE, e.job.value!!.phase)
        assertEquals(setOf(pa.path("manifest.json"), pa.path("addresses.sqlite.gz")), server.log.map { it.path }.toSet())
        assertTrue(h.installed.calls.contains("startAddOptionalParts"))
        assertEquals(listOf("addresses.sqlite", "manifest.json"), h.installed.lastFolderFiles!! - "job.json")
        assertEquals(pa.manifestBytes.size.toLong() + pa.part("addresses").transfer.size, e.job.value!!.progress.totalBytes)
    }

    @Test
    fun aGoneFileSwitchesToTheNewerBuild() = runTest {
        val h = harness()
        h.publish(pa)
        val newer = TestPack(pa.parts, packVersion = pa.packVersion + 100)
        val e = h.engine(backgroundScope)
        e.recover()
        e.start(h.entry(pa), JobKind.UPDATE, includeOptional = false, allowMetered = true, firstRun = true)
        // the old build disappears from the server and a newer one is listed
        h.publish(newer)
        server.status[pa.path("map.pmtiles")] = 404
        server.status[pa.path("routing.tar.gz")] = 404
        server.status[pa.path("search.sqlite.gz")] = 404
        val seen = mutableListOf<DownloadJob?>()
        val watch = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { e.job.collect { seen += it } }
        e.run()
        watch.cancel()
        // What the screen recognises as the switch ("A newer Pennsylvania was published. Downloading
        // that one.", JobSwitch in :feature:states): the old build's job is followed by the newer
        // build's, same pack id and kind, with no end (DONE, FAILED) and no null in between.
        val firstNew = seen.indexOfFirst { it?.packVersion == newer.packVersion }
        assertTrue(firstNew > 0, "$seen")
        assertTrue(seen.subList(0, firstNew).all { it != null && it.packId == pa.id && it.packVersion == pa.packVersion &&
            it.phase != JobPhase.DONE && it.phase != JobPhase.FAILED }, "$seen")
        assertEquals(JobKind.UPDATE, seen[firstNew]!!.kind)
        assertEquals(pa.id, seen[firstNew]!!.packId)
        val j = e.job.value!!
        assertEquals(JobPhase.DONE, j.phase)
        assertEquals(newer.packVersion, j.packVersion)
        assertEquals(JobKind.UPDATE, j.kind)
        assertFalse(j.includeOptional)
        assertTrue(j.allowMetered)
        assertTrue(j.firstRun)
        assertFalse(h.jobFolder(pa).exists())
        assertTrue(server.log.any { it.path == "/catalog/catalog.json" })

        // nothing newer: no longer offered, files deleted
        val h2 = Harness(File(dir, "gone"), server, StandardTestDispatcher(testScheduler), { testScheduler.currentTime })
        server.put("/catalog/catalog.json", catalogJson(emptyList()).toByteArray())
        val e2 = h2.engine(backgroundScope)
        e2.recover()
        e2.start(h2.entry(pa), JobKind.INSTALL, true, false, false)
        e2.run()
        assertEquals(FailReason.NO_LONGER_OFFERED, e2.job.value!!.fail)
        assertEquals(listOf("job.json"), h2.jobFolder(pa).list()!!.toList())
    }

    @Test
    fun aNewerFormatOrOtherProblemsFailBeforeAnyPart() = runTest {
        for ((i, case) in listOf(
            listOf("manifest_version 4 is newer than this app reads (3): update the app") to FailReason.NEEDS_NEWER_APP,
            listOf("the bounds are not valid") to FailReason.REJECTED,
        ).withIndex()) {
            val h = Harness(File(dir, "f$i"), server, StandardTestDispatcher(testScheduler), { testScheduler.currentTime })
            h.publish(pa)
            server.log.clear()
            h.installed.problems = case.first
            val e = h.engine(backgroundScope)
            e.recover()
            e.start(h.entry(pa), JobKind.INSTALL, true, false, false)
            e.run()
            val j = e.job.value!!
            assertEquals(case.second, j.fail)
            assertEquals(case.first.single(), j.detail)
            assertEquals(listOf(pa.path("manifest.json")), server.log.map { it.path })
        }
    }

    @Test
    fun theHeldChangeIsNotAProblemOfInspect() = runTest {
        val h = harness()
        h.publish(pa)
        h.installed.problems = listOf("Not while guiding")
        val hold = h.installed.holdChanges("Not while guiding")
        val e = h.engine(backgroundScope)
        e.recover()
        e.start(h.entry(pa), JobKind.INSTALL, true, false, false)
        e.run()
        assertEquals(WaitReason.TRIP_IN_PROGRESS, e.job.value!!.wait)
        hold!!.close()
    }

    @Test
    fun installerOutcomes() = runTest {
        // refused: FAILED(REJECTED) with its words, files deleted
        val h = harness()
        h.publish(pa)
        h.installed.failWith = "The map part is not a PMTiles file."
        val e = h.engine(backgroundScope)
        e.recover()
        e.start(h.entry(pa), JobKind.INSTALL, true, false, true)
        e.run()
        assertEquals(FailReason.REJECTED, e.job.value!!.fail)
        assertEquals("The map part is not a PMTiles file.", e.job.value!!.detail)
        assertEquals(listOf("job.json"), h.jobFolder(pa).list()!!.toList())

        // cancelled by another caller: PAUSED(USER), files kept
        val h3 = Harness(File(dir, "cancel"), server, StandardTestDispatcher(testScheduler), { testScheduler.currentTime })
        h3.installed.cancelResult = true
        val e3 = h3.engine(backgroundScope)
        e3.recover()
        e3.start(h3.entry(pa), JobKind.INSTALL, true, false, false)
        e3.run()
        assertEquals(PauseReason.USER, e3.job.value!!.pause)
        assertTrue(File(h3.jobFolder(pa), "map.pmtiles").isFile)
    }

    @Test
    fun aFailureWithTooLittleSpaceIsAPause() = runTest {
        val h = harness()
        h.publish(pa)
        h.installed.failWith = "Not enough space."
        h.installed.neededBytes = 10_000_000
        val e = h.engine(backgroundScope)
        e.recover()
        e.start(h.entry(pa), JobKind.INSTALL, true, false, false)
        // enough space at CHECKING; the disk fills up while the parts are unpacked, then the installer fails
        val freeAtFailure = 1_000L
        EngineHooks.afterInflate = { _, _ -> h.free = freeAtFailure }
        e.run()
        val j = e.job.value!!
        assertEquals(PauseReason.NO_SPACE, j.pause)
        assertEquals(freeAtFailure, j.freeBytes)
        assertTrue(File(h.jobFolder(pa), "map.pmtiles").isFile, "files kept")
    }

    @Test
    fun oneJobAtATimeCancelAndDismiss() = runTest {
        val h = harness()
        h.publish(pa)
        val e = h.engine(backgroundScope)
        e.recover()
        assertEquals(StartResult.Started, e.start(h.entry(pa), JobKind.INSTALL, true, false, true))
        val oh = TestPack(pa.parts, id = "us-oh", state = "OH", name = "Ohio")
        h.publish(pa, oh)
        assertEquals(StartResult.Refused(StartRefusal.ANOTHER_JOB), e.start(h.entry(oh), JobKind.INSTALL, true, false, false))
        e.cancel()
        assertNull(e.job.value)
        assertFalse(h.jobFolder(pa).exists())
        assertFalse(e.needsService.value)
        assertEquals(StartResult.Started, e.start(h.entry(oh), JobKind.INSTALL, true, false, false))
        e.pause()
        assertEquals(PauseReason.USER, e.job.value!!.pause)
        e.resume(allowMetered = true)
        assertEquals(JobPhase.CHECKING, e.job.value!!.phase)
        assertTrue(e.job.value!!.allowMetered)
        e.run()
        assertEquals(JobPhase.DONE, e.job.value!!.phase)
        assertEquals(e.job.value!!.progress.totalBytes, e.job.value!!.progress.doneBytes)
        e.dismiss()
        assertNull(e.job.value)
    }
}
