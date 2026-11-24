package io.github.spysyweeb.beamline.download

import io.github.spysyweeb.beamline.download.net.CatalogAddress
import io.github.spysyweeb.beamline.download.net.PackHttp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.net.Socket
import java.security.cert.CertificateExpiredException
import javax.net.SocketFactory

/** docs/architecture-downloads.md 10.1 "Catalog cache" (CatalogSource, 6.3 and 6.5). */
class CatalogSourceTest {
    @TempDir
    lateinit var dir: File
    private val server = TestPackServer()
    private val rules = DownloadRules()
    private val address = CatalogAddress.parse(server.url("/catalog/catalog.json"), allowCleartext = true)!!
    private val network = MutableStateFlow(NetworkState(connected = true, unmetered = true))
    private var now = 1_000_000_000_000L
    private val pa = TestPack(listOf(TestPart("map", "map.pmtiles", randomBytes(2000), gzip = false)))

    @AfterEach
    fun stop() = server.close()

    private fun source(http: PackHttp = PackHttp(address, rules), cache: File = File(dir, "getpacks")) =
        CatalogSource(address, http, cache, rules, 3, 3, network, { now })

    private fun serve(serial: Long) = server.put("/catalog/catalog.json", catalogJson(listOf(pa.entryJson()), serial).toByteArray())

    @Test
    fun fetchCacheAndConditionalRequest() = runTest {
        serve(20261115000000)
        val s = source()
        s.loadCache()
        assertEquals(CatalogState.NotLoaded, s.state.value)
        s.refresh(force = false)
        val ready = s.state.value as CatalogState.Ready
        assertFalse(ready.view.fromCache)
        assertEquals(now, ready.view.checkedAtMs)
        assertTrue(File(dir, "getpacks/catalog.json").isFile)
        assertTrue(File(dir, "getpacks/catalog-meta.json").readText().contains("\"serial\":20261115000000"))

        // a new process: the cache only, then a fresh enough cache is not fetched again
        val again = source()
        again.loadCache()
        assertTrue((again.state.value as CatalogState.Ready).view.fromCache)
        now += 60_000
        again.refresh(force = false)
        assertEquals(1, server.log.size)
        // forced: If-None-Match with the cached ETag, 304, checked time moves on
        again.refresh(force = true)
        assertEquals(2, server.log.size)
        assertEquals(server.etag("/catalog/catalog.json"), server.log[1].header("If-None-Match"))
        assertEquals(304, server.log[1].status)
        val v = (again.state.value as CatalogState.Ready).view
        assertEquals(now, v.checkedAtMs)
        assertFalse(v.fromCache)
        // after 6 hours: fetched without force
        now += 361 * 60_000L
        again.refresh(force = false)
        assertEquals(3, server.log.size)
    }

    @Test
    fun aLowerSerialReplacesTheCache() = runTest {
        serve(20261115000000)
        val s = source()
        s.refresh(force = true)
        serve(20250101000000)
        s.refresh(force = true)
        assertEquals(20250101000000, (s.state.value as CatalogState.Ready).view.catalog.serial)
        assertTrue(File(dir, "getpacks/catalog-meta.json").readText().contains("\"serial\":20250101000000"))
    }

    @Test
    fun aCacheFromTheFutureIsRefreshed() = runTest {
        serve(20261115000000)
        source().refresh(force = true)
        now -= 24 * 3_600_000L // the clock was set back a day
        val s = source()
        s.loadCache()
        s.refresh(force = false)
        assertEquals(2, server.log.size)
        assertFalse((s.state.value as CatalogState.Ready).view.fromCache)
    }

    @Test
    fun aCacheForAnotherUrlIsIgnored() = runTest {
        serve(20261115000000)
        source().refresh(force = true)
        val meta = File(dir, "getpacks/catalog-meta.json")
        meta.writeText(meta.readText().replace(address.url, "http://10.0.2.2:8765/catalog/catalog.json"))
        val s = source()
        s.loadCache()
        assertEquals(CatalogState.NotLoaded, s.state.value)
    }

    @Test
    fun failuresKeepTheCachedList() = runTest {
        serve(20261115000000)
        source().refresh(force = true)
        // clock problem: Failed(CLOCK) with the cache
        val clockHttp = PackHttp(address, rules, object : SocketFactory() {
            override fun createSocket(): Socket = object : Socket() {
                override fun connect(endpoint: java.net.SocketAddress?, timeout: Int) =
                    throw IOException("handshake", CertificateExpiredException("expired"))
            }
            override fun createSocket(host: String?, port: Int) = throw UnsupportedOperationException()
            override fun createSocket(host: String?, port: Int, localHost: java.net.InetAddress?, localPort: Int) = throw UnsupportedOperationException()
            override fun createSocket(host: java.net.InetAddress?, port: Int) = throw UnsupportedOperationException()
            override fun createSocket(address: java.net.InetAddress?, port: Int, localAddress: java.net.InetAddress?, localPort: Int) = throw UnsupportedOperationException()
        })
        val s = source(clockHttp)
        s.loadCache()
        s.refresh(force = true)
        val failed = s.state.value as CatalogState.Failed
        assertEquals(CatalogProblem.CLOCK, failed.problem)
        assertEquals(20261115000000, failed.cached!!.catalog.serial)

        // no network: no request at all
        network.value = NetworkState.NONE
        val n = source()
        n.refresh(force = true)
        assertEquals(CatalogProblem.NO_NETWORK, (n.state.value as CatalogState.Failed).problem)
        network.value = NetworkState(true, true)

        // HTTP error, a newer format, a broken list
        val before = server.log.size
        server.status["/catalog/catalog.json"] = 500
        val e = source()
        e.refresh(force = true)
        assertEquals(CatalogProblem.SERVER_ERROR, (e.state.value as CatalogState.Failed).problem)
        server.status.remove("/catalog/catalog.json")
        server.put("/catalog/catalog.json", catalogJson(emptyList()).replace("beamline-catalog/1", "beamline-catalog/2").toByteArray())
        e.refresh(force = true)
        assertEquals(CatalogProblem.NEWER_FORMAT, (e.state.value as CatalogState.Failed).problem)
        server.put("/catalog/catalog.json", "{".toByteArray())
        e.refresh(force = true)
        val broken = e.state.value as CatalogState.Failed
        assertEquals(CatalogProblem.BROKEN, broken.problem)
        assertEquals(20261115000000, broken.cached!!.catalog.serial)
        assertEquals(before + 3, server.log.size)

        // unreachable
        server.close()
        e.refresh(force = true)
        assertEquals(CatalogProblem.UNREACHABLE, (e.state.value as CatalogState.Failed).problem)
    }
}
