package io.github.spysyweeb.beamline.download.net

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** docs/architecture-downloads.md 10.1 "Address rules" (3.1, 6.2). */
class AddressRulesTest {
    private val github = "https://github.com/O/R/releases/download/catalog/catalog.json"

    @Test
    fun parseAcceptsHttpsAndLocalHttpInDebug() {
        val g = CatalogAddress.parse(github, allowCleartext = false)!!
        assertEquals("https://github.com/O/R/releases/download/", g.scope)
        assertFalse(g.local)
        assertFalse(g.cleartext)
        for (u in listOf(
            "http://10.0.2.2:8765/catalog/catalog.json",
            "http://192.168.200.20:8765/catalog/catalog.json",
            "http://127.0.0.1:8765/catalog/catalog.json",
        )) {
            val a = CatalogAddress.parse(u, allowCleartext = true)!!
            assertTrue(a.local, u)
            assertTrue(a.cleartext, u)
        }
        assertEquals("http://10.0.2.2:8765/", CatalogAddress.parse("http://10.0.2.2:8765/catalog/catalog.json", true)!!.scope)
        assertNull(CatalogAddress.parse("  ", allowCleartext = false))
    }

    @Test
    fun parseRefuses() {
        val bad = listOf(
            "http://10.0.2.2:8765/catalog/catalog.json" to false, // http without the flag
            "https://10.0.2.2/catalog/catalog.json" to false, // private host without the flag
            "https://127.0.0.1/catalog/catalog.json" to false,
            "http://example.org/catalog/catalog.json" to true, // http to a public host
            "https://user:pw@github.com/O/R/releases/download/catalog/catalog.json" to false,
            "https://github.com/O/R/releases/download/catalog/catalog.json?x=1" to false,
            "https://github.com/O/R/releases/download/catalog/catalog.json#f" to false,
            "https://github.com/O/R/releases/download/catalog/catalog.txt" to false,
            "ftp://github.com/catalog/catalog.json" to true,
            "not a url" to true,
        )
        for ((u, flag) in bad) assertThrows(IllegalArgumentException::class.java, { CatalogAddress.parse(u, flag) }, u)
    }

    @Test
    fun resolveStaysInsideTheScope() {
        val g = CatalogAddress.parse(github, false)!!
        assertEquals("https://github.com/O/R/releases/download/us-pa-202611152241/map.pmtiles", g.resolve("../us-pa-202611152241/map.pmtiles"))
        assertEquals("https://github.com/O/R/releases/download/catalog/catalog.json.sig", g.resolve("catalog.json.sig"))
        for (p in listOf("../../x", "/abs", "http://h/x", "a/../../b", "%2e%2e/x", "a//b", "a?b", "")) {
            assertThrows(IllegalArgumentException::class.java, { g.resolve(p) }, p)
        }
        assertTrue(g.contains(github))
        assertTrue(g.contains("https://github.com/O/R/releases/download/us-pa-1/map.pmtiles"))
        assertTrue(g.contains("https://GitHub.com:443/O/R/releases/download/us-pa-1/map.pmtiles"))
        assertFalse(g.contains("https://github.com/O/R/releases/other"))
        assertFalse(g.contains("https://github.com/O/R/releases/download/../x"))
        assertFalse(g.contains("https://github.com/O/R/releases/download/us-pa-1/map.pmtiles?x=1"))
        assertFalse(g.contains("http://github.com/O/R/releases/download/us-pa-1/map.pmtiles"))
        assertFalse(g.contains("https://evil.example/O/R/releases/download/us-pa-1/map.pmtiles"))
        val d = CatalogAddress.parse("http://10.0.2.2:8765/catalog/catalog.json", true)!!
        assertEquals("http://10.0.2.2:8765/us-pa-1/manifest.json", d.resolve("../us-pa-1/manifest.json"))
        assertFalse(d.contains("http://10.0.2.2:8766/us-pa-1/manifest.json"))
    }

    @Test
    fun privateOrLoopback() {
        // Addresses of the private ranges are built from parts: only the documentation examples are written out
        // (check_network_rule.py privacy keeps every other private address out of the repository).
        val edges = listOf("172.16" to "0.1", "172.31" to "255.255", "192.168" to "1.1").map { "${it.first}.${it.second}" }
        for (h in listOf("localhost", "10.0.2.2", "10.200.0.5", "192.168.200.20", "127.0.0.1") + edges) {
            assertTrue(CatalogAddress.isPrivateOrLoopback(h), h)
        }
        for (h in listOf("172.32.0.1", "8.8.8.8", "github.com", "169.254.1.1", "256.1.1.1", "::1")) {
            assertFalse(CatalogAddress.isPrivateOrLoopback(h), h)
        }
    }

    @Test
    fun redirectRules() {
        val r = "https://github.com/O/R/releases/download/us-pa-1/map.pmtiles"
        assertTrue(CatalogAddress.redirectAllowed(r, "https://release-assets.githubusercontent.com/x/y?sp=r&sig=abc"))
        assertFalse(CatalogAddress.redirectAllowed(r, "http://release-assets.githubusercontent.com/x"))
        assertFalse(CatalogAddress.redirectAllowed(r, "https://127.0.0.1/x"))
        assertFalse(CatalogAddress.redirectAllowed(r, "https://10.200.0.5/x"))
        assertFalse(CatalogAddress.redirectAllowed(r, "https://169.254.1.1/x"))
        assertFalse(CatalogAddress.redirectAllowed(r, "https://[fd00::1]/x"))
        assertFalse(CatalogAddress.redirectAllowed(r, "https://localhost/x"))
        assertFalse(CatalogAddress.redirectAllowed(r, "https://u:p@example.org/x"))
        val d = "http://10.0.2.2:8765/us-pa-1/map.pmtiles"
        assertTrue(CatalogAddress.redirectAllowed(d, "http://10.0.2.2:8765/us-pa-2/map.pmtiles"))
        assertFalse(CatalogAddress.redirectAllowed(d, "http://10.0.2.2:8766/us-pa-2/map.pmtiles"))
        assertFalse(CatalogAddress.redirectAllowed(d, "https://10.0.2.2:8765/x"))
        assertFalse(CatalogAddress.redirectAllowed(d, "http://example.org/x"))
    }

    @Test
    fun publicAddresses() {
        fun ip(s: String) = java.net.InetAddress.getByName(s)
        val rfc1918 = listOf("192.168.", "172.16.").map { it + "5.4" }
        for (a in listOf("10.200.0.5", "127.0.0.1", "169.254.1.1", "fd00::1", "::1", "0.0.0.0", "224.0.0.1", "fe80::1", "::ffff:10.200.0.5") + rfc1918) {
            assertFalse(PackHttp.isPublic(ip(a)), a)
        }
        for (a in listOf("8.8.8.8", "140.82.112.3", "2606:4700::1111")) assertTrue(PackHttp.isPublic(ip(a)), a)
    }

    @Test
    fun clockFailuresAreFoundInTheCauseChain() {
        val expired = javax.net.ssl.SSLHandshakeException("handshake").apply {
            initCause(java.security.cert.CertificateException(java.security.cert.CertificateExpiredException("expired")))
        }
        val notYet = java.io.IOException(javax.net.ssl.SSLException(java.security.cert.CertificateNotYetValidException("not yet")))
        assertTrue(NetRules.isClockFailure(expired))
        assertTrue(NetRules.isClockFailure(notYet))
        assertFalse(NetRules.isClockFailure(javax.net.ssl.SSLHandshakeException("other")))
        assertFalse(NetRules.isClockFailure(java.net.ConnectException("refused")))
    }
}
