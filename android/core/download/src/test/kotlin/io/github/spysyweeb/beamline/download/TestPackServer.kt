package io.github.spysyweeb.beamline.download

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * A local HTTP server for the JVM tests (docs/architecture-downloads.md 10.1): files in memory,
 * Range, strong ETags, If-Match, If-None-Match, redirects, a request log and faults (cut after N
 * bytes, ignore Range, a changed file, 503, a status, corrupt bytes, extra headers, a longer body,
 * a slow body). Test sources only: the app's network code is PackHttp alone.
 */
class TestPackServer(fixedPort: Int = 0) : AutoCloseable {
    class Request(val method: String, val path: String, val headers: Map<String, String>, val time: Long) {
        @Volatile var status: Int = 0
        fun header(name: String): String? = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        override fun toString() = "$method $path ${header("Range") ?: ""} -> $status"
    }

    private val files = ConcurrentHashMap<String, ByteArray>()
    private val etags = ConcurrentHashMap<String, String>()
    private val version = AtomicInteger(0)
    val log = CopyOnWriteArrayList<Request>()
    val sentBytes = AtomicLong(0)

    /** path -> cut the body after this many bytes, once. */
    val cutOnce = ConcurrentHashMap<String, Long>()
    @Volatile var ignoreRange = false
    /** Every request answers 503 while true. */
    @Volatile var fail503 = false
    /** path -> status to answer with (no body). */
    val status = ConcurrentHashMap<String, Int>()
    /** path -> Location for a 302. */
    val redirects = ConcurrentHashMap<String, String>()
    /** path -> times its body is sent with one byte flipped. */
    val corrupt = ConcurrentHashMap<String, Int>()
    /** path -> extra response headers. */
    val extraHeaders = ConcurrentHashMap<String, Map<String, String>>()
    /** path -> bytes appended to the body, sent chunked (no Content-Length). */
    val longer = ConcurrentHashMap<String, Int>()
    /** Milliseconds to sleep after every 64 KiB of body. */
    @Volatile var slowMsPer64k = 0L
    /** Called with each request before it is answered (a test changes the server between attempts). */
    @Volatile var beforeServe: ((Request) -> Unit)? = null
    /** Stamps each request (the tests' virtual clock). */
    @Volatile var timeSource: () -> Long = { 0L }
    /** Sends Set-Cookie on every answer. */
    @Volatile var setCookie = false

    private val server: HttpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), fixedPort), 0).apply {
        executor = Executors.newCachedThreadPool { r -> Thread(r, "test-pack-server").apply { isDaemon = true } }
        createContext("/") { ex -> handle(ex) }
        start()
    }
    val port: Int get() = server.address.port

    fun url(path: String) = "http://127.0.0.1:$port$path"

    fun put(path: String, bytes: ByteArray) {
        files[path] = bytes
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
        etags[path] = "\"${sha.take(12)}-${version.incrementAndGet()}\""
    }

    fun etag(path: String): String? = etags[path]

    fun requests(path: String) = log.filter { it.path == path }

    @Volatile private var closed = false

    override fun close() {
        if (closed) return
        closed = true
        server.stop(0)
    }

    private fun handle(ex: HttpExchange) {
        val path = ex.requestURI.rawPath
        val headers = ex.requestHeaders.entries.associate { it.key to it.value.joinToString(",") }
        val req = Request(ex.requestMethod, path, headers, timeSource())
        log += req
        beforeServe?.invoke(req)
        try {
            if (setCookie) ex.responseHeaders.add("Set-Cookie", "session=abc; Path=/")
            extraHeaders[path]?.forEach { (k, v) -> ex.responseHeaders.add(k, v) }
            if (fail503) return empty(ex, req, 503)
            status[path]?.let { return empty(ex, req, it) }
            redirects[path]?.let {
                ex.responseHeaders.add("Location", it)
                return empty(ex, req, 302)
            }
            val body = files[path] ?: return empty(ex, req, 404)
            val etag = etags.getValue(path)
            ex.responseHeaders.add("ETag", etag)
            ex.responseHeaders.add("Accept-Ranges", "bytes")
            val ifMatch = headers.entries.firstOrNull { it.key.equals("If-Match", true) }?.value
            if (ifMatch != null && ifMatch != etag) return empty(ex, req, 412)
            val ifNoneMatch = headers.entries.firstOrNull { it.key.equals("If-None-Match", true) }?.value
            if (ifNoneMatch != null && ifNoneMatch == etag) return empty(ex, req, 304)
            val range = headers.entries.firstOrNull { it.key.equals("Range", true) }?.value
            var start = 0L
            var code = 200
            if (range != null && !ignoreRange) {
                val m = Regex("bytes=(\\d+)-").matchEntire(range.trim())
                if (m != null) {
                    start = m.groupValues[1].toLong()
                    if (start >= body.size) {
                        ex.responseHeaders.add("Content-Range", "bytes */${body.size}")
                        return empty(ex, req, 416)
                    }
                    code = 206
                    ex.responseHeaders.add("Content-Range", "bytes $start-${body.size - 1}/${body.size}")
                }
            }
            var data = body.copyOfRange(start.toInt(), body.size)
            corrupt[path]?.let { left ->
                if (left > 0) {
                    corrupt[path] = left - 1
                    data = data.copyOf().also { if (it.isNotEmpty()) it[it.size / 2] = (it[it.size / 2].toInt() xor 0x5a).toByte() }
                }
            }
            req.status = code
            val extra = longer[path]
            if (extra != null) {
                ex.sendResponseHeaders(code, 0) // chunked: no Content-Length
                ex.responseBody.use { out ->
                    out.write(data)
                    out.write(ByteArray(extra) { 7 })
                }
                sentBytes.addAndGet(data.size.toLong() + extra)
                return
            }
            ex.sendResponseHeaders(code, data.size.toLong())
            val cut = cutOnce.remove(path)
            val out = ex.responseBody
            var sent = 0
            try {
                while (sent < data.size) {
                    val n = minOf(65536, data.size - sent, if (cut != null) (cut - sent).toInt() else Int.MAX_VALUE)
                    if (n <= 0) break
                    out.write(data, sent, n)
                    sent += n
                    sentBytes.addAndGet(n.toLong())
                    if (slowMsPer64k > 0) {
                        out.flush()
                        Thread.sleep(slowMsPer64k)
                    }
                }
                out.flush()
            } finally {
                try {
                    ex.close() // with bytes missing, the connection is closed: the client sees the body end early
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
            try {
                ex.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun empty(ex: HttpExchange, req: Request, code: Int) {
        req.status = code
        ex.sendResponseHeaders(code, -1)
        ex.close()
    }
}
