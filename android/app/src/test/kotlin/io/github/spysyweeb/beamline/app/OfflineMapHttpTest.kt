package io.github.spysyweeb.beamline.app

import okhttp3.Request
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * Rule N5 (docs/architecture-downloads.md 4.2 item 3): a request through OfflineMapHttp.client to a
 * local test server fails with IOException, and the server sees no connection.
 */
class OfflineMapHttpTest {

    @Test
    fun everyRequestFailsAndNoConnectionIsMade() {
        ServerSocket(0, 50, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 200
            val accepted = AtomicInteger()
            val listener = thread(isDaemon = true) {
                while (!server.isClosed) {
                    try {
                        server.accept().use { accepted.incrementAndGet() }
                    } catch (_: SocketTimeoutException) {
                    } catch (_: IOException) {
                        break
                    }
                }
            }
            // The server works: a plain socket reaches it (so a zero below means the client made none).
            java.net.Socket(InetAddress.getLoopbackAddress(), server.localPort).close()
            waitFor { accepted.get() == 1 }
            assertEquals(1, accepted.get())

            for (url in listOf("http://127.0.0.1:${server.localPort}/style.json", "https://127.0.0.1:${server.localPort}/tiles/1/2/3.pbf")) {
                val e = assertThrows<IOException> {
                    OfflineMapHttp.client.newCall(Request.Builder().url(url).build()).execute()
                }
                assertEquals(OfflineMapHttp.REFUSED, e.message)
            }
            Thread.sleep(500) // time for a connection that should not exist to arrive
            assertEquals(1, accepted.get(), "the offline client must not connect")
            server.close()
            listener.join(2000)
        }
        assertTrue(OfflineMapHttp.client.cache == null)
    }

    private fun waitFor(cond: () -> Boolean) {
        val until = System.currentTimeMillis() + 5000
        while (!cond() && System.currentTimeMillis() < until) Thread.sleep(20)
    }
}
