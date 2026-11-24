package io.github.spysyweeb.beamline.download

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException

/** docs/architecture-downloads.md 10.1: the transfer rows (resume, ignored range, changed file, 416, hashes, unreachable, bad response, gzip, space, process death). */
@OptIn(ExperimentalCoroutinesApi::class)
class EngineTransferTest {
    @TempDir
    lateinit var dir: File
    private var server = TestPackServer()

    @AfterEach
    fun stop() {
        server.close()
        EngineHooks.beforeWrite = null
        EngineHooks.afterInflate = null
    }

    private fun TestScope.harness(rules: DownloadRules = TEST_RULES, root: File = dir): Harness {
        server.timeSource = { testScheduler.currentTime }
        return Harness(root, server, StandardTestDispatcher(testScheduler), { testScheduler.currentTime }, rules)
    }

    private val tenMb = randomBytes(10 * 1024 * 1024)
    private fun mapOnly() = TestPack(listOf(TestPart("map", "map.pmtiles", tenMb, gzip = false)))

    private fun threeParts() = TestPack(
        listOf(
            TestPart("map", "map.pmtiles", randomBytes(1_500_000), gzip = false),
            TestPart("routing", "routing.tar", textBytes(3_000_000), gzip = true),
            TestPart("search", "search.sqlite", randomBytes(400_000, seed = 7), gzip = false),
        ),
    )

    private suspend fun Harness.startAndRun(pack: TestPack, scope: CoroutineScope, includeOptional: Boolean = true): DownloadEngine {
        publish(pack)
        val e = engine(scope)
        e.recover()
        assertEquals(StartResult.Started, e.start(entry(pack), JobKind.INSTALL, includeOptional, allowMetered = false, firstRun = true))
        e.run()
        return e
    }

    private fun tarData(h: Harness, name: String) = h.installed.lastTar!!.first { it.name == name }.data

    @Test
    fun resumeAfterACut() = runTest {
        val h = harness()
        val pack = mapOnly()
        val path = pack.path("map.pmtiles")
        server.cutOnce[path] = 3_145_728
        val e = h.startAndRun(pack, backgroundScope)
        assertEquals(JobPhase.DONE, e.job.value!!.phase)
        val reqs = server.requests(path)
        assertEquals(2, reqs.size)
        assertNull(reqs[0].header("Range"))
        assertEquals("bytes=3145728-", reqs[1].header("Range"))
        assertEquals(server.etag(path), reqs[1].header("If-Match"))
        assertEquals(206, reqs[1].status)
        assertEquals(2000, reqs[1].time - reqs[0].time) // the first back-off step
        assertEquals(sha256(tenMb), sha256(tarData(h, "map.pmtiles")))
        assertTrue(server.sentBytes.get() <= pack.manifestBytes.size + tenMb.size + TEST_RULES.bufferBytes)
        assertFalse(h.jobFolder(pack).exists())
    }

    @Test
    fun anIgnoredRangeStartsOver() = runTest {
        val h = harness()
        val pack = mapOnly()
        val path = pack.path("map.pmtiles")
        server.cutOnce[path] = 3_145_728
        server.ignoreRange = true
        val e = h.startAndRun(pack, backgroundScope)
        assertEquals(JobPhase.DONE, e.job.value!!.phase)
        val reqs = server.requests(path)
        assertEquals("bytes=3145728-", reqs[1].header("Range"))
        assertEquals(200, reqs[1].status)
        assertEquals(sha256(tenMb), sha256(tarData(h, "map.pmtiles")))
    }

    @Test
    fun aChangedFileStartsOverAtZero() = runTest {
        val h = harness()
        val pack = mapOnly()
        val path = pack.path("map.pmtiles")
        server.cutOnce[path] = 3_145_728
        server.beforeServe = { r -> if (r.path == path && r.header("Range") != null) server.put(path, tenMb) } // re-uploaded: new ETag
        val e = h.startAndRun(pack, backgroundScope)
        assertEquals(JobPhase.DONE, e.job.value!!.phase)
        val reqs = server.requests(path)
        assertEquals(listOf(200, 412, 200), reqs.map { it.status })
        assertNull(reqs[2].header("Range"))
        assertEquals(sha256(tenMb), sha256(tarData(h, "map.pmtiles")))
    }

    @Test
    fun aCompleteFileEndsWith416() = runTest {
        val h = harness()
        val pack = mapOnly()
        h.publish(pack)
        val e = h.engine(backgroundScope)
        e.recover()
        e.start(h.entry(pack), JobKind.INSTALL, true, false, false)
        File(h.jobFolder(pack), ".transfer-map").writeBytes(tenMb)
        e.run()
        assertEquals(JobPhase.DONE, e.job.value!!.phase)
        val reqs = server.requests(pack.path("map.pmtiles"))
        assertEquals(1, reqs.size)
        assertEquals("bytes=${tenMb.size}-", reqs[0].header("Range"))
        assertEquals(416, reqs[0].status)
        assertEquals(sha256(tenMb), sha256(tarData(h, "map.pmtiles")))
    }

    @Test
    fun oneBadHashIsFetchedAgainTwoPause() = runTest {
        val h = harness()
        val pack = threeParts()
        server.corrupt[pack.path("map.pmtiles")] = 1
        val e = h.startAndRun(pack, backgroundScope)
        assertEquals(JobPhase.DONE, e.job.value!!.phase)
        val reqs = server.requests(pack.path("map.pmtiles"))
        assertEquals(2, reqs.size)
        reqs.forEach { assertNull(it.header("Range")) }
    }

    @Test
    fun theSecondPartDamagedTwicePausesAndOnlyTheMissingPartsAreFetchedAgain() = runTest {
        val h = harness()
        val pack = threeParts()
        h.publish(pack)
        val order = h.entry(pack).parts.map { it.name } // ascending transfer size
        val second = pack.part(order[1])
        server.corrupt[pack.path(second.transferName)] = 2
        val e = h.engine(backgroundScope)
        e.recover()
        e.start(h.entry(pack), JobKind.INSTALL, true, false, false)
        e.run()
        val j = e.job.value!!
        assertEquals(JobPhase.PAUSED, j.phase)
        assertEquals(PauseReason.DAMAGED, j.pause)
        val folder = h.jobFolder(pack)
        assertFalse(File(folder, second.file).exists())
        assertFalse(File(folder, ".transfer-${second.name}").exists())
        assertTrue(File(folder, pack.part(order[0]).file).isFile, "the verified first part is kept")
        assertFalse(j.continuesByItself())

        server.log.clear()
        e.resume()
        e.run()
        assertEquals(JobPhase.DONE, e.job.value!!.phase)
        assertEquals(setOf(pack.path(second.transferName), pack.path(pack.part(order[2]).transferName)), server.log.map { it.path }.toSet())
    }

    @Test
    fun anUnreachableServerPausesAfterEightAttemptsAndAReopenedAppContinues() = runTest {
        val h = harness()
        val pack = TestPack(listOf(TestPart("map", "map.pmtiles", randomBytes(4_000_000), gzip = false)))
        val path = pack.path("map.pmtiles")
        val port = server.port
        server.cutOnce[path] = 1_200_000 // 30 %
        server.beforeServe = { r -> if (r.path == path && r.header("Range") != null) server.close() }
        h.publish(pack)
        val e = h.engine(backgroundScope)
        val waits = mutableListOf<Long>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
            var last: DownloadJob? = null
            e.job.collect { j ->
                if (j?.wait == WaitReason.RETRYING && j.retryAtMs != last?.retryAtMs) waits += j.retryAtMs!! - testScheduler.currentTime
                last = j
            }
        }
        e.recover()
        e.start(h.entry(pack), JobKind.INSTALL, true, false, false)
        e.run()
        val j = e.job.value!!
        assertEquals(PauseReason.SERVER_UNREACHABLE, j.pause)
        assertEquals(listOf(2000L, 5000, 10000, 30000, 60000, 60000, 60000, 60000), waits, "the back-off of 1.4: 287 s after the cut")
        val onDisk = File(h.jobFolder(pack), ".transfer-map").length()
        assertEquals(1_200_000, onDisk, "every byte kept")
        assertTrue(j.continuesByItself())

        // The server is back; a new process opens the app: recover, then onAppVisible continues the job.
        server = TestPackServer(port)
        server.timeSource = { testScheduler.currentTime }
        pack.serveOn(server)
        val h2 = Harness(dir, server, StandardTestDispatcher(testScheduler), { testScheduler.currentTime })
        val e2 = h2.engine(backgroundScope)
        e2.recover()
        val recovered = e2.job.value!!
        assertEquals(PauseReason.SERVER_UNREACHABLE, recovered.pause)
        assertTrue(recovered.continuesByItself())
        e2.resume()
        e2.run()
        assertEquals(JobPhase.DONE, e2.job.value!!.phase)
        assertEquals("bytes=1200000-", server.requests(path).first().header("Range"))
    }

    @Test
    fun a503EveryTimePausesTheSameWay() = runTest {
        val h = harness()
        val pack = TestPack(listOf(TestPart("map", "map.pmtiles", randomBytes(300_000), gzip = false)))
        val path = pack.path("map.pmtiles")
        h.publish(pack)
        server.status[path] = 503
        val e = h.engine(backgroundScope)
        e.recover()
        e.start(h.entry(pack), JobKind.INSTALL, true, false, false)
        e.run()
        assertEquals(PauseReason.SERVER_UNREACHABLE, e.job.value!!.pause)
        val times = server.requests(path).map { it.time }
        assertEquals(8, times.size)
        assertEquals(listOf(2000L, 5000, 10000, 30000, 60000, 60000, 60000), times.zipWithNext { a, b -> b - a })
    }

    @Test
    fun badResponsesPauseWithTheirRuleAndKeepTheFiles() = runTest {
        val cases = listOf<Pair<String, (TestPackServer, String) -> Unit>>(
            "a redirect the network rule refuses" to { s, p -> s.redirects[p] = "https://127.0.0.1:${s.port}/x" },
            "a redirect the network rule refuses" to { s, p -> s.redirects[p] = "http://example.org/x" },
            "too many redirects" to { s, p -> for (i in 0..5) s.redirects[if (i == 0) p else "$p.$i"] = "$p.${i + 1}" },
            "a body longer than the catalog says" to { s, p -> s.longer[p] = 1000 },
            "a Content-Encoding" to { s, p -> s.extraHeaders[p] = mapOf("Content-Encoding" to "gzip") },
            "HTTP 403" to { s, p -> s.status[p] = 403 },
        )
        for ((i, case) in cases.withIndex()) {
            val (rule, breakIt) = case
            server.close()
            server = TestPackServer()
            val h = harness(root = File(dir, "case$i"))
            val pack = TestPack(listOf(TestPart("map", "map.pmtiles", randomBytes(300_000), gzip = false)))
            h.publish(pack)
            breakIt(server, pack.path("map.pmtiles"))
            val e = h.engine(backgroundScope)
            e.recover()
            e.start(h.entry(pack), JobKind.INSTALL, true, false, false)
            e.run()
            val j = e.job.value!!
            assertEquals(PauseReason.BAD_RESPONSE, j.pause, rule)
            assertEquals(rule, j.detail)
            assertFalse(j.continuesByItself())
            assertTrue(File(h.jobFolder(pack), "manifest.json").isFile, "files kept: $rule")
            assertTrue(File(h.jobFolder(pack), "job.json").isFile)
        }
    }

    @Test
    fun aClockProblemPausesAtOnce() = runTest {
        server.timeSource = { testScheduler.currentTime }
        val h = Harness(dir, server, StandardTestDispatcher(testScheduler), { testScheduler.currentTime }, TEST_RULES, ClockSockets())
        val pack = mapOnly()
        val e = h.startAndRun(pack, backgroundScope)
        assertEquals(PauseReason.CLOCK, e.job.value!!.pause)
        assertEquals(0, testScheduler.currentTime, "no retry loop")
        assertTrue(server.log.isEmpty())
    }

    @Test
    fun aManifestThatDisagreesFailsBeforeAnyPart() = runTest {
        val h = harness()
        val pack = TestPack(listOf(TestPart("map", "map.pmtiles", randomBytes(300_000), gzip = false)), manifestSnapshot = "2026-11-13")
        val e = h.startAndRun(pack, backgroundScope)
        val j = e.job.value!!
        assertEquals(JobPhase.FAILED, j.phase)
        assertEquals(FailReason.LIST_MISMATCH, j.fail)
        assertEquals(listOf(pack.path("manifest.json")), server.log.map { it.path })
        assertEquals(listOf("job.json"), h.jobFolder(pack).list()!!.toList())
        e.dismiss()
        assertNull(e.job.value)
        assertFalse(h.jobFolder(pack).exists())
    }

    @Test
    fun aGzipPartIsInflatedHashedAndCanPauseWhileInflating() = runTest {
        val h = harness()
        val raw = textBytes(6_000_000)
        val pack = TestPack(listOf(TestPart("routing", "routing.tar", raw, gzip = true)))
        val folder = h.jobFolder(pack)
        var peak = 0L
        var inflated = 0
        var pausedAt = -1
        lateinit var e: DownloadEngine
        EngineHooks.beforeWrite = { _, _ ->
            peak = maxOf(peak, folder.listFiles()!!.filter { it.name != "job.json" }.sumOf { it.length() })
        }
        EngineHooks.afterInflate = { _, _ ->
            inflated++
            if (pausedAt < 0) {
                pausedAt = inflated
                e.pause()
            }
        }
        h.publish(pack)
        e = h.engine(backgroundScope)
        e.recover()
        e.start(h.entry(pack), JobKind.INSTALL, true, false, false)
        e.run()
        assertEquals(PauseReason.USER, e.job.value!!.pause)
        assertEquals(pausedAt, inflated, "stopped within one buffer")
        assertFalse(File(folder, "routing.tar").exists(), "the half part file is deleted")
        assertEquals(pack.part("routing").transfer.size.toLong(), File(folder, ".transfer-routing").length(), "the transfer file is kept")

        val requests = server.log.size
        EngineHooks.afterInflate = null
        e.resume()
        e.run()
        assertEquals(JobPhase.DONE, e.job.value!!.phase)
        assertEquals(requests, server.log.size, "Continue inflates without a request")
        assertEquals(sha256(raw), sha256(tarData(h, "routing.tar")))
        val r = pack.manifestBytes.size + raw.size.toLong()
        val g = pack.part("routing").transfer.size.toLong()
        assertTrue(peak <= r + g, "peak $peak <= R + G ${r + g}")
    }

    @Test
    fun tooLittleSpaceAtCheckingAndAnEnospcDuringTheDownload() = runTest {
        val h = harness()
        val pack = mapOnly()
        h.free = 1000
        val e = h.startAndRun(pack, backgroundScope)
        val j = e.job.value!!
        assertEquals(PauseReason.NO_SPACE, j.pause)
        assertEquals(1000, j.freeBytes)
        assertTrue(j.needBytes!! > tenMb.size)
        assertEquals(listOf(pack.path("manifest.json")), server.log.map { it.path })

        h.free = 1L shl 50
        var thrown = false
        EngineHooks.beforeWrite = { f, have ->
            if (!thrown && f.name == ".transfer-map" && have >= 2_000_000) {
                thrown = true
                throw IOException("write failed: ENOSPC (No space left on device)")
            }
        }
        e.resume()
        e.run()
        assertEquals(PauseReason.NO_SPACE, e.job.value!!.pause)
        val kept = File(h.jobFolder(pack), ".transfer-map").length()
        assertTrue(kept >= 2_000_000, "files kept")
        e.resume()
        e.run()
        assertEquals(JobPhase.DONE, e.job.value!!.phase)
        assertEquals("bytes=$kept-", server.requests(pack.path("map.pmtiles")).last().header("Range"))
    }

    @Test
    fun processDeathAndTheBreaker() = runBlocking {
        val pack = mapOnly()
        val path = pack.path("map.pmtiles")
        fun harness() = Harness(dir, server, Dispatchers.IO, System::currentTimeMillis)
        val h = harness()
        h.publish(pack)
        server.slowMsPer64k = 15
        val scope = CoroutineScope(Dispatchers.IO)

        suspend fun runUntil(e: DownloadEngine, bytes: Long) {
            val job = scope.launch { e.run() }
            withTimeout(60_000) {
                while ((e.job.value?.progress?.doneBytes ?: 0) < bytes) delay(10)
            }
            job.cancelAndJoin()
        }

        val e1 = h.engine(scope)
        e1.recover()
        e1.start(h.entry(pack), JobKind.INSTALL, true, false, true)
        runUntil(e1, (tenMb.size * 0.4).toLong())
        val onDisk = File(h.jobFolder(pack), ".transfer-map").length()
        assertTrue(onDisk >= tenMb.size * 0.4)

        // A new process: recover makes it PAUSED(APP_ENDED); continuing starts at the bytes on disk.
        val e2 = harness().engine(scope)
        e2.recover()
        assertEquals(PauseReason.APP_ENDED, e2.job.value!!.pause)
        assertTrue(e2.job.value!!.continuesByItself())
        e2.resume()
        // ... and the process ends again before anything is gained: the second death without progress
        val e3 = harness().engine(scope)
        e3.recover()
        assertEquals(PauseReason.KEEPS_STOPPING, e3.job.value!!.pause)
        assertFalse(e3.job.value!!.continuesByItself())

        // Continued by a tap, it gains more than sync_every_bytes before the next death: the count starts again.
        e3.resume()
        server.log.clear()
        runUntil(e3, onDisk + 3 * TEST_RULES.syncEveryBytes)
        assertEquals("bytes=$onDisk-", server.requests(path).first().header("Range"))
        val h4 = harness()
        val e4 = h4.engine(scope)
        e4.recover()
        assertEquals(PauseReason.APP_ENDED, e4.job.value!!.pause)

        server.slowMsPer64k = 0
        e4.resume()
        e4.run()
        assertEquals(JobPhase.DONE, e4.job.value!!.phase)
        assertEquals(sha256(tenMb), sha256(tarData(h4, "map.pmtiles")))
    }
}

/** Sockets whose connect fails the way a TLS handshake fails on a certificate that has expired. */
class ClockSockets : javax.net.SocketFactory() {
    override fun createSocket(): java.net.Socket = object : java.net.Socket() {
        override fun connect(endpoint: java.net.SocketAddress?, timeout: Int) =
            throw javax.net.ssl.SSLHandshakeException("handshake").apply { initCause(java.security.cert.CertificateExpiredException("expired")) }
    }
    override fun createSocket(host: String?, port: Int) = throw UnsupportedOperationException()
    override fun createSocket(host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int) = throw UnsupportedOperationException()
    override fun createSocket(host: java.net.InetAddress?, port: Int) = throw UnsupportedOperationException()
    override fun createSocket(address: java.net.InetAddress?, port: Int, localAddress: java.net.InetAddress?, localPort: Int) = throw UnsupportedOperationException()
}
