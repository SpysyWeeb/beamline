// FILE: core/download/src/main/kotlin/io/github/spysyweeb/beamline/download/net/PackHttp.kt
package io.github.spysyweeb.beamline.download.net

import io.github.spysyweeb.beamline.download.DownloadRules
import java.io.Closeable
import java.io.InputStream
import java.net.InetAddress
import javax.net.SocketFactory

/** One GET. Nothing else is ever sent (network rule N3). */
data class PackRequest(
    /** Absolute URL: [CatalogAddress.url] or a URL inside its scope. */
    val url: String,
    /** When > 0: "Range: bytes=<rangeFrom>-". */
    val rangeFrom: Long = 0,
    /** With a range: "If-Match: <ifMatch>" (a strong ETag from an earlier response for the same file). */
    val ifMatch: String? = null,
    /** For the catalog: "If-None-Match: <ifNoneMatch>". */
    val ifNoneMatch: String? = null,
)

/** The answer after redirects. The caller reads [body] and closes the response. */
class PackResponse(
    val status: Int,
    /** The URL that answered (after redirects); never stored. */
    val finalUrl: String,
    /** A strong ETag, or null (weak or missing). */
    val etag: String?,
    val contentLength: Long?,
    /** From Content-Range on a 206: the first byte. */
    val rangeStart: Long?,
    /** From Content-Range on a 206 or 416: the full size. */
    val totalLength: Long?,
    val body: InputStream,
) : Closeable {
    override fun close() = body.close()
}

/** Thrown before any connection is made for a request or redirect the network rule refuses. */
class NetworkRuleRefused(message: String) : Exception(message)

/** TLS failed because a certificate is not yet valid or has expired by this phone's clock. */
class ClockProblem(cause: Throwable) : java.io.IOException(cause)

/**
 * The only code in the app that opens network connections (network rule N1). It sends exactly
 * the requests of rule N3 to the addresses of rule N2 and follows at most
 * [DownloadRules.maxRedirects] redirects itself, checking each with [CatalogAddress.redirectAllowed].
 * Every host name goes through [resolve]; a request whose host has an address that is not
 * [isPublic] is refused with NetworkRuleRefused before any connection, except the configured
 * host itself when [CatalogAddress.local]. A response with a Content-Encoding other than identity
 * is an error. On Android [socketFactory] tags every socket (TrafficStats tag 0x0B1A) and, for a
 * local host, binds it to the chosen network (rule N1), so the device accounting of section 4.2
 * can tell its traffic apart.
 */
class PackHttp(
    private val address: CatalogAddress,
    private val rules: DownloadRules,
    private val socketFactory: SocketFactory = SocketFactory.getDefault(),
    /** Host name to addresses: the system resolver; tests answer what they like. */
    private val resolve: (String) -> List<InetAddress> = { InetAddress.getAllByName(it).toList() },
) {
    /** Blocking; call on an IO thread. Throws NetworkRuleRefused, or IOException for network errors. */
    fun get(request: PackRequest): PackResponse = fetch(request)

    // ---- Implementation (track G). OkHttp is used in this file only (network rule N1); the import
    // list above is part of the contract, so OkHttp's types carry their package name here.

    /** The configured host when it is the debug build's own local server: the one non-public host allowed (3.1). */
    private val localHost: String? = if (address.local) AddressRules.split(address.url)?.host else null

    /** Calls whose response is not closed yet: OkHttp's dispatcher forgets a call once its headers are in. */
    private val active: MutableSet<okhttp3.Call> =
        java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<okhttp3.Call, Boolean>())

    private val client: okhttp3.OkHttpClient = okhttp3.OkHttpClient.Builder()
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .cache(null)
        .cookieJar(okhttp3.CookieJar.NO_COOKIES)
        .proxy(java.net.Proxy.NO_PROXY)
        .connectTimeout(rules.connectTimeoutS.toLong(), java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(rules.readTimeoutS.toLong(), java.util.concurrent.TimeUnit.SECONDS)
        .writeTimeout(rules.readTimeoutS.toLong(), java.util.concurrent.TimeUnit.SECONDS)
        .socketFactory(socketFactory)
        .dns(object : okhttp3.Dns {
            override fun lookup(hostname: String): List<InetAddress> = checkedAddresses(hostname)
        })
        .build()

    /** Resolves [host] with [resolve] and applies the address rule (N2); throws RefusedByRule before any connection. */
    private fun checkedAddresses(host: String): List<InetAddress> {
        val name = host.removePrefix("[").removeSuffix("]").lowercase()
        val addresses = resolve(name)
        if (addresses.isEmpty()) throw java.net.UnknownHostException("no address")
        if (name != localHost && addresses.any { !isPublic(it) }) {
            throw RefusedByRule(NetworkRuleRefused("the host has an address that is not public"))
        }
        return addresses
    }

    /** OkHttp connects to an IP literal without asking the DNS callback, so literals are checked here first. */
    private fun checkLiteralHost(url: String) {
        val host = AddressRules.split(url)?.host ?: throw NetworkRuleRefused("the URL has no host")
        val literal = host.startsWith("[") || Regex("^[0-9.]+$").matches(host)
        if (!literal || host == localHost) return
        val a = try {
            InetAddress.getByName(host)
        } catch (_: java.io.IOException) {
            throw NetworkRuleRefused("the URL's address cannot be read")
        }
        if (!isPublic(a)) throw NetworkRuleRefused("the URL's address is not public")
    }

    private fun build(url: String, request: PackRequest): okhttp3.Request {
        val b = okhttp3.Request.Builder().url(url).get()
            .header("User-Agent", rules.userAgent)
            .header("Accept-Encoding", "identity")
        if (request.rangeFrom > 0) {
            b.header("Range", "bytes=${request.rangeFrom}-")
            request.ifMatch?.let { b.header("If-Match", it) }
        }
        request.ifNoneMatch?.let { b.header("If-None-Match", it) }
        return b.build()
    }

    /** An IOException as the caller must see it: a rule refusal, a clock problem, or itself. */
    private fun classify(e: java.io.IOException): Exception {
        var c: Throwable? = e
        var depth = 0
        while (c != null && depth < 32) {
            if (c is RefusedByRule) return c.refusal
            c = c.cause
            depth++
        }
        e.suppressed.forEach { s -> if (s is RefusedByRule) return s.refusal }
        return if (NetRules.isClockFailure(e)) ClockProblem(e) else e
    }

    private fun fetch(request: PackRequest): PackResponse {
        if (!address.contains(request.url)) throw NetworkRuleRefused("the URL is outside the catalog's scope")
        var url = request.url
        var hops = 0
        while (true) {
            checkLiteralHost(url)
            val call = client.newCall(build(url, request))
            active += call
            val response = try {
                call.execute()
            } catch (e: java.io.IOException) {
                active -= call
                throw classify(e)
            } catch (e: RuntimeException) {
                active -= call
                throw e
            }
            val code = response.code
            if (code in REDIRECT_CODES) {
                val location = response.header("Location")
                response.close()
                active -= call
                if (location.isNullOrBlank()) throw BadResponse("a redirect without a Location")
                val next = try {
                    java.net.URI(url).resolve(location.trim()).toString()
                } catch (_: Exception) {
                    throw NetworkRuleRefused("a redirect to an address that cannot be read")
                }
                hops += 1
                if (hops > rules.maxRedirects) throw NetworkRuleRefused("too many redirects")
                if (!CatalogAddress.redirectAllowed(url, next)) throw NetworkRuleRefused("a redirect the network rule refuses")
                url = next
                continue
            }
            val encoding = response.header("Content-Encoding")
            if (encoding != null && !encoding.trim().equals("identity", ignoreCase = true)) {
                response.close()
                active -= call
                throw BadResponse("a Content-Encoding")
            }
            val etag = response.header("ETag")?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("W/") }
            val body = response.body
            val length = body.contentLength().takeIf { it >= 0 }
            val (start, total) = NetRules.contentRange(response.header("Content-Range"))
            val stream = object : java.io.FilterInputStream(body.byteStream()) {
                override fun close() {
                    try {
                        response.close()
                    } finally {
                        active -= call
                    }
                }
            }
            return PackResponse(code, url, etag, length, start, total, stream)
        }
    }

    private val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)

    /** Cancels every call in flight (pause, cancel, process shutting down); they throw IOException. */
    fun cancelAll(): Unit = active.toList().forEach { it.cancel() }.also { client.dispatcher.cancelAll() }

    companion object {
        /** False for loopback, site-local (private), link-local, any-local, multicast and IPv6 unique-local (fc00::/7) addresses. */
        fun isPublic(a: InetAddress): Boolean = NetRules.isPublicAddress(a.address)
    }
}
