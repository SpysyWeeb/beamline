// FILE: core/download/src/main/kotlin/io/github/spysyweeb/beamline/download/CatalogSource.kt
package io.github.spysyweeb.beamline.download

import io.github.spysyweeb.beamline.download.net.CatalogAddress
import io.github.spysyweeb.beamline.download.net.PackHttp
import kotlinx.coroutines.flow.StateFlow
import java.io.File

data class CatalogView(
    val catalog: Catalog,
    /** When this list was last fetched or confirmed (304) from the server, epoch ms. */
    val checkedAtMs: Long,
    /** True while only the cached copy is known in this process. */
    val fromCache: Boolean,
)

enum class CatalogProblem {
    /** NetworkState.connected is false. */
    NO_NETWORK,
    /** DNS, connect or read failed, or a timeout. */
    UNREACHABLE,
    /** The server's certificate is not yet valid or has expired by this phone's clock (ClockProblem). */
    CLOCK,
    /** HTTP status other than 200 and 304. */
    SERVER_ERROR,
    /** CatalogRefusal.NEWER_FORMAT. */
    NEWER_FORMAT,
    /** Any other CatalogRefusal. */
    BROKEN,
}

sealed interface CatalogState {
    /** No catalog address in this build. */
    data object Off : CatalogState
    data object NotLoaded : CatalogState
    data class Loading(val cached: CatalogView?) : CatalogState
    data class Ready(val view: CatalogView) : CatalogState
    data class Failed(val problem: CatalogProblem, val cached: CatalogView?) : CatalogState
}

/**
 * The list of states: fetched from [address] with [http], cached in [cacheDir] (catalog.json, the
 * bytes as received, and catalog-meta.json: {"url", "etag", "serial", "checked_ms"}). A cache made
 * for another URL is ignored. Format 1 uses the list it fetched whatever its serial (section 2.3);
 * a serial lower than the cached one is only logged. Fetches only when asked: never on its own.
 */
class CatalogSource(
    private val address: CatalogAddress,
    private val http: PackHttp,
    private val cacheDir: File,
    private val rules: DownloadRules,
    private val minManifestVersion: Int,
    private val maxManifestVersion: Int,
    private val network: StateFlow<NetworkState>,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    val state: StateFlow<CatalogState> get() = core.state

    /** The work behind this class (CatalogStore.kt); the engine reads the address and the version range from it. */
    internal val core = CatalogSourceCore(address, http, cacheDir, rules, minManifestVersion, maxManifestVersion, network, clock)

    /** Reads the cache only (no network): NotLoaded or Ready(fromCache = true). */
    suspend fun loadCache(): Unit = core.loadCache()

    /**
     * Fetches when [force], when the cache is older than catalog_refresh_after_min, or when its
     * checked_ms lies in the future (the phone's clock was set back); sends If-None-Match with the
     * cached ETag. On failure the cached view stays available in Failed.
     */
    suspend fun refresh(force: Boolean): Unit = core.refresh(force)
}
