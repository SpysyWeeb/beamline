package io.github.spysyweeb.beamline.download.net

import io.github.spysyweeb.beamline.download.DownloadRules
import io.github.spysyweeb.beamline.download.TestPackServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetAddress

/** docs/architecture-downloads.md 4.2 item 4: the network rule, through the real PackHttp and a local test server. */
class NetworkRuleTest {
    private val server = TestPackServer()
    private val rules = DownloadRules()
    private val address = CatalogAddress.parse(server.url("/catalog/catalog.json"), allowCleartext = true)!!
    private val http = PackHttp(address, rules)

    @AfterEach
    fun stop() = server.close()

    @Test
    fun exactlyTheHeadersOfN3() {
        server.put("/catalog/catalog.json", "{}".toByteArray())
        server.put("/us-pa-1/map.pmtiles", ByteArray(1000) { it.toByte() })
        http.get(PackRequest(address.url)).use { it.body.readBytes() }
        val etag = server.etag("/us-pa-1/map.pmtiles")
        http.get(PackRequest(address.resolve("../us-pa-1/map.pmtiles"), rangeFrom = 10, ifMatch = etag)).use {
            assertEquals(206, it.status)
            assertEquals(10L, it.rangeStart)
            assertEquals(1000L, it.totalLength)
            assertEquals(990L, it.contentLength)
            assertEquals(etag, it.etag)
            assertEquals(990, it.body.readBytes().size)
        }
        http.get(PackRequest(address.url, ifNoneMatch = server.etag("/catalog/catalog.json"))).use { assertEquals(304, it.status) }
        val (plain, ranged, conditional) = server.log
        assertEquals(setOf("Host", "Connection", "User-agent", "Accept-encoding"), plain.headers.keys)
        assertEquals("Beamline-Packs/1", plain.header("User-Agent"))
        assertEquals("identity", plain.header("Accept-Encoding"))
        assertEquals(setOf("Host", "Connection", "User-agent", "Accept-encoding", "Range", "If-match"), ranged.headers.keys)
        assertEquals("bytes=10-", ranged.header("Range"))
        assertEquals(setOf("Host", "Connection", "User-agent", "Accept-encoding", "If-none-match"), conditional.headers.keys)
        server.log.forEach { assertEquals("GET", it.method) }
    }

    @Test
    fun aUrlOutsideTheScopeIsRefusedBeforeAnyConnection() {
        assertThrows(NetworkRuleRefused::class.java) { http.get(PackRequest("http://127.0.0.1:${server.port + 1}/catalog/catalog.json")) }
        assertThrows(NetworkRuleRefused::class.java) { http.get(PackRequest("http://example.org/x.json")) }
        assertThrows(NetworkRuleRefused::class.java) { http.get(PackRequest(server.url("/catalog/catalog.json?x=1"))) }
        assertTrue(server.log.isEmpty())
    }

    @Test
    fun redirectsToAnotherPortAndASixthHopAreRefused() {
        server.redirects["/us-pa-1/a"] = "http://127.0.0.1:${server.port + 1}/us-pa-1/b"
        assertThrows(NetworkRuleRefused::class.java) { http.get(PackRequest(address.resolve("../us-pa-1/a"))) }
        assertEquals(1, server.log.size)

        server.log.clear()
        for (i in 1..6) server.redirects["/us-pa-1/h$i"] = "/us-pa-1/h${i + 1}"
        server.put("/us-pa-1/h6", "six".toByteArray())
        server.put("/us-pa-1/h7", "seven".toByteArray())
        // five hops are followed
        server.redirects.remove("/us-pa-1/h6")
        http.get(PackRequest(address.resolve("../us-pa-1/h1"))).use { assertEquals("six", it.body.readBytes().decodeToString()) }
        assertEquals(6, server.log.size)
        // a sixth is refused
        server.log.clear()
        server.redirects["/us-pa-1/h6"] = "/us-pa-1/h7"
        assertThrows(NetworkRuleRefused::class.java) { http.get(PackRequest(address.resolve("../us-pa-1/h1"))) }
        assertEquals(6, server.log.size)

        // to another host of the same machine: refused too (only the configured host is allowed)
        server.redirects["/us-pa-1/c"] = "http://localhost:${server.port}/us-pa-1/h7"
        assertThrows(NetworkRuleRefused::class.java) { http.get(PackRequest(address.resolve("../us-pa-1/c"))) }
    }

    @Test
    fun aPublicNameThatResolvesIntoAPrivateNetworkIsRefused() {
        val public = CatalogAddress.parse("https://packs.example.org/catalog/catalog.json", allowCleartext = false)!!
        for (answer in listOf("10.200.0.5", "127.0.0.1", "169.254.1.1", "fd00::1")) {
            val h = PackHttp(public, rules, resolve = { listOf(InetAddress.getByName(answer)) })
            assertThrows(NetworkRuleRefused::class.java, { h.get(PackRequest(public.url)) }, answer)
        }
    }

    @Test
    fun aLocalAddressAllowsOnlyItsOwnHost() {
        server.put("/catalog/catalog.json", "{}".toByteArray())
        http.get(PackRequest(address.url)).use { assertEquals(200, it.status) }
        // "localhost" is another host than the configured 127.0.0.1, even though it points at it
        val viaName = CatalogAddress.parse("http://localhost:${server.port}/catalog/catalog.json", allowCleartext = true)!!
        val h = PackHttp(viaName, rules, resolve = { if (it == "localhost") listOf(InetAddress.getLoopbackAddress()) else emptyList() })
        h.get(PackRequest(viaName.url)).use { assertEquals(200, it.status) }
        server.redirects["/catalog/x.json"] = "http://127.0.0.1:${server.port}/catalog/catalog.json"
        assertThrows(NetworkRuleRefused::class.java) { h.get(PackRequest(viaName.resolve("x.json"))) }
    }

    @Test
    fun noCookieIsSentBack() {
        server.setCookie = true
        server.put("/catalog/catalog.json", "{}".toByteArray())
        repeat(2) { http.get(PackRequest(address.url)).use { it.body.readBytes() } }
        assertEquals(2, server.log.size)
        server.log.forEach { assertNull(it.header("Cookie")) }
    }

    @Test
    fun aContentEncodingIsAnError() {
        server.put("/us-pa-1/z", "zzz".toByteArray())
        server.extraHeaders["/us-pa-1/z"] = mapOf("Content-Encoding" to "gzip")
        assertThrows(BadResponse::class.java) { http.get(PackRequest(address.resolve("../us-pa-1/z"))) }
    }

    @Test
    fun cancelAllStopsAReadInFlight() {
        server.put("/us-pa-1/slow", ByteArray(4_000_000))
        server.slowMsPer64k = 50
        val r = http.get(PackRequest(address.resolve("../us-pa-1/slow")))
        val buf = ByteArray(65536)
        r.body.read(buf)
        http.cancelAll()
        assertThrows(java.io.IOException::class.java) { while (r.body.read(buf) >= 0) Unit }
        r.close()
    }
}
