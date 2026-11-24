package io.github.spysyweeb.beamline.download.net

import java.net.URI
import java.net.URISyntaxException

/**
 * The URL arithmetic behind [CatalogAddress] (docs/architecture-downloads.md 2.3 rule 2, 3.1, N2).
 * Works on the text of URLs only: no name is resolved here (PackHttp resolves and checks addresses).
 */
internal object AddressRules {

    /** One parsed and checked catalog address, before CatalogAddress wraps it. */
    class Parsed(val url: String, val scope: String, val cleartext: Boolean, val local: Boolean)

    /** A URL split into what the rules compare: lower-case scheme and host, the port with defaults made explicit, the raw path. */
    class Parts(val scheme: String, val host: String, val port: Int, val path: String, val hasExtras: Boolean)

    private val SEGMENT = Regex("^[A-Za-z0-9][A-Za-z0-9._-]*$")
    private val IPV4 = Regex("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$")

    fun split(url: String): Parts? {
        val u = try {
            URI(url)
        } catch (_: URISyntaxException) {
            return null
        }
        val scheme = u.scheme?.lowercase() ?: return null
        val host = u.host?.lowercase() ?: return null
        val port = when {
            u.port != -1 -> u.port
            scheme == "https" -> 443
            scheme == "http" -> 80
            else -> -1
        }
        val extras = u.rawUserInfo != null || u.rawQuery != null || u.rawFragment != null
        return Parts(scheme, host, port, u.rawPath ?: "", extras)
    }

    private fun origin(p: Parts): String {
        val defaultPort = (p.scheme == "https" && p.port == 443) || (p.scheme == "http" && p.port == 80)
        return if (defaultPort) "${p.scheme}://${p.host}" else "${p.scheme}://${p.host}:${p.port}"
    }

    /** Segments of an absolute path "/a/b/c.json" -> [a, b, c.json]; null when one is empty or not a plain segment. */
    private fun segments(path: String): List<String>? {
        if (!path.startsWith("/")) return null
        val segs = path.substring(1).split('/')
        return if (segs.all { SEGMENT.matches(it) }) segs else null
    }

    fun parse(raw: String, allowCleartext: Boolean): Parsed? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        val u = try {
            URI(text)
        } catch (_: URISyntaxException) {
            throw IllegalArgumentException("catalog address is not a URL")
        }
        val scheme = u.scheme?.lowercase() ?: throw IllegalArgumentException("catalog address has no scheme")
        require(scheme == "https" || scheme == "http") { "catalog address must be https" }
        require(u.rawUserInfo == null) { "catalog address must not hold a user name or password" }
        require(u.rawQuery == null) { "catalog address must not hold a query" }
        require(u.rawFragment == null) { "catalog address must not hold a fragment" }
        val host = u.host?.lowercase() ?: throw IllegalArgumentException("catalog address has no host")
        val local = isPrivateOrLoopback(host)
        if (scheme == "http") {
            require(allowCleartext) { "plain http is allowed in debug builds only" }
            require(local) { "plain http is allowed only to a private or loopback IPv4 address" }
        }
        require(!local || allowCleartext) { "a private or loopback host is allowed in debug builds only" }
        require(!host.startsWith("[")) { "an IPv6 literal is not allowed as the catalog host" }
        val path = u.rawPath ?: ""
        require(path.endsWith(".json")) { "the catalog address must end in .json" }
        val segs = segments(path) ?: throw IllegalArgumentException("the catalog path has an empty or odd segment")
        val parts = split(text)!!
        // The scope is the folder above the catalog's own folder ("/" when the catalog sits at the root or one level down).
        val parent = if (segs.size <= 1) "/" else "/" + segs.dropLast(2).joinToString("") { "$it/" }
        return Parsed(url = text, scope = origin(parts) + parent, cleartext = scheme == "http", local = local)
    }

    /** The catalog's own folder as an absolute URL ending in "/". */
    private fun catalogFolder(url: String): String {
        val p = split(url)!!
        return origin(p) + p.path.substringBeforeLast('/') + "/"
    }

    fun resolve(address: CatalogAddress, path: String): String {
        require(CatalogAddress.PATH_RULE.matches(path)) { "path breaks the path rule" }
        val result = if (path.startsWith("../")) address.scope + path.removePrefix("../") else catalogFolder(address.url) + path
        require(contains(address, result)) { "path leaves the catalog's scope" }
        return result
    }

    fun contains(address: CatalogAddress, absoluteUrl: String): Boolean {
        val u = split(absoluteUrl) ?: return false
        if (u.hasExtras) return false
        val catalog = split(address.url) ?: return false
        if (u.scheme != catalog.scheme || u.host != catalog.host || u.port != catalog.port) return false
        if (u.path == catalog.path) return true
        val scope = split(address.scope) ?: return false
        if (!u.path.startsWith(scope.path)) return false
        val rest = u.path.substring(scope.path.length)
        return rest.isNotEmpty() && rest.split('/').all { SEGMENT.matches(it) }
    }

    private fun ipv4(host: String): IntArray? {
        val m = IPV4.matchEntire(host) ?: return null
        val parts = m.groupValues.drop(1).map { it.toInt() }
        if (parts.any { it > 255 }) return null
        return parts.toIntArray()
    }

    fun isPrivateOrLoopback(host: String): Boolean {
        if (host.equals("localhost", ignoreCase = true)) return true
        val a = ipv4(host) ?: return false
        return a[0] == 10 || (a[0] == 172 && a[1] in 16..31) || (a[0] == 192 && a[1] == 168) || a[0] == 127
    }

    private fun linkLocalOrAny(host: String): Boolean {
        val a = ipv4(host) ?: return false
        return (a[0] == 169 && a[1] == 254) || a[0] == 0
    }

    fun redirectAllowed(from: String, to: String): Boolean {
        val f = split(from) ?: return false
        val t = split(to) ?: return false
        // A query is fine (GitHub's storage redirect carries a signed one); user info never is.
        if (URI(to).rawUserInfo != null) return false
        return when (f.scheme) {
            "https" -> t.scheme == "https" && !t.host.startsWith("[") && !isPrivateOrLoopback(t.host) && !linkLocalOrAny(t.host)
            "http" -> t.scheme == "http" && t.host == f.host && t.port == f.port
            else -> false
        }
    }
}
