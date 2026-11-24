package io.github.spysyweeb.beamline.app

import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.Response
import java.io.IOException
import java.net.Proxy

/**
 * The HTTP client MapLibre gets (rule N5 of docs/architecture-downloads.md 4.1:
 * `HttpRequestImpl.setOkHttpClient(OfflineMapHttp.client)` in `Application.onCreate`, before
 * `MapLibre.getInstance`). It refuses every request before any connection is made: an
 * application interceptor fails the call, so OkHttp never resolves a name or opens a socket. The
 * app's styles point only at local files (`asset://`, `file://`, `pmtiles://...`), which MapLibre
 * reads without this client; a request reaching it would be a mistake, and it fails.
 *
 * This file and `PackHttp` are the only places that touch OkHttp (rule N1, `check_network_rule.py scan`).
 */
object OfflineMapHttp {

    /** The fixed reason every call fails with. Names nothing of the request (rule N3). */
    const val REFUSED = "map network use is off"

    private object Refuse : Interceptor {
        override fun intercept(chain: Interceptor.Chain): Response = throw IOException(REFUSED)
    }

    val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(Refuse)
        .proxy(Proxy.NO_PROXY)
        .cache(null)
        .retryOnConnectionFailure(false)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
}
