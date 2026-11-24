package io.github.spysyweeb.beamline.feature.states

import android.net.Network
import android.net.TrafficStats
import java.net.InetAddress
import java.net.Socket
import javax.net.SocketFactory

/**
 * The sockets PackHttp gets on Android (network rule N1, docs/architecture-downloads.md 4.1).
 * TrafficStats.tagSocket applies the calling thread's tag and OkHttp makes sockets on its own
 * threads, so the tag is set around the creation of each socket: the device's per-app accounting
 * then shows the downloader's traffic under tag 0x0B1A and anything else as untagged. For a local
 * catalog host the socket is bound to the Wi-Fi or Ethernet network the watcher chose (5.2), before
 * OkHttp connects it. Every overload that would connect at once is refused.
 */
class TaggingSocketFactory(
    /** The tag: [TAG] for PackHttp; [OSM_TAG] for the app's OsmHttp (docs/online-design.md section 6). */
    private val tag: Int = TAG,
    private val network: () -> Network?,
) : SocketFactory() {

    override fun createSocket(): Socket {
        val old = TrafficStats.getAndSetThreadStatsTag(tag)
        try {
            val s = Socket()
            // Batch O1: an unconnected Socket has no file descriptor until something needs it, and
            // Android tags a socket's descriptor when it is made, with the making thread's tag. Without
            // a network to bind (bindSocket makes it) it was made later on OkHttp's thread, untagged:
            // the device accounting of 4.2 item 5 saw OsmHttp's traffic (and PackHttp's to a public
            // host) as untagged. Reading an option makes it here, under the tag.
            s.soTimeout
            network()?.bindSocket(s)
            TrafficStats.tagSocket(s)
            return s
        } finally {
            TrafficStats.setThreadStatsTag(old)
        }
    }

    override fun createSocket(host: String?, port: Int): Socket = throw UnsupportedOperationException(REFUSED)

    override fun createSocket(host: String?, port: Int, localHost: InetAddress?, localPort: Int): Socket = throw UnsupportedOperationException(REFUSED)

    override fun createSocket(host: InetAddress?, port: Int): Socket = throw UnsupportedOperationException(REFUSED)

    override fun createSocket(address: InetAddress?, port: Int, localAddress: InetAddress?, localPort: Int): Socket =
        throw UnsupportedOperationException(REFUSED)

    companion object {
        /** TrafficStats tag of every socket of PackHttp ("0xb1a" in dumpsys netstats). */
        const val TAG = 0x0B1A
        /** TrafficStats tag of every socket of OsmHttp ("0xb1b" in dumpsys netstats). */
        const val OSM_TAG = 0x0B1B
        private const val REFUSED = "only unconnected sockets: they are tagged and bound before OkHttp connects them"
    }
}
