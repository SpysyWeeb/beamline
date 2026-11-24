package io.github.spysyweeb.beamline.download

import io.github.spysyweeb.beamline.download.net.BadResponse
import io.github.spysyweeb.beamline.download.net.CatalogAddress
import io.github.spysyweeb.beamline.download.net.ClockProblem
import io.github.spysyweeb.beamline.download.net.NetworkRuleRefused
import io.github.spysyweeb.beamline.download.net.PackHttp
import io.github.spysyweeb.beamline.download.net.PackRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.InputStream

/** catalog-meta.json beside the cached catalog.json (docs/architecture-downloads.md 6.5). */
@Serializable
internal data class CatalogCacheMeta(
    val url: String,
    val etag: String? = null,
    val serial: Long,
    @SerialName("checked_ms") val checkedMs: Long,
)

/** The work behind [CatalogSource]: cache, fetch, parse. */
internal class CatalogSourceCore(
    val address: CatalogAddress,
    private val http: PackHttp,
    private val cacheDir: File,
    val rules: DownloadRules,
    val minManifestVersion: Int,
    val maxManifestVersion: Int,
    private val network: kotlinx.coroutines.flow.StateFlow<NetworkState>,
    private val clock: () -> Long,
) {
    val state = MutableStateFlow<CatalogState>(CatalogState.NotLoaded)
    private val mutex = Mutex()
    private var meta: CatalogCacheMeta? = null

    private val bytesFile get() = File(cacheDir, "catalog.json")
    private val metaFile get() = File(cacheDir, "catalog-meta.json")

    suspend fun loadCache() {
        mutex.withLock { withContext(Dispatchers.IO) { loadCacheLocked() } }
    }

    private fun loadCacheLocked() {
        if (state.value !is CatalogState.NotLoaded) return
        readCache()?.let { state.value = CatalogState.Ready(it) }
    }

    fun parse(bytes: ByteArray): CatalogParse =
        CatalogFormat.parse(bytes, address, minManifestVersion, maxManifestVersion, rules.catalogMaxBytes, rules.maxFileBytes)

    private fun readCache(): CatalogView? {
        if (!metaFile.isFile || !bytesFile.isFile) return null
        val m = try {
            JSON.decodeFromString(CatalogCacheMeta.serializer(), metaFile.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            return null
        }
        if (m.url != address.url) return null // a cache made for another address is ignored
        val parsed = try {
            parse(bytesFile.readBytes())
        } catch (_: IOException) {
            return null
        }
        if (parsed !is CatalogParse.Ok) return null
        meta = m
        return CatalogView(parsed.catalog, m.checkedMs, fromCache = true)
    }

    private fun currentView(): CatalogView? = when (val s = state.value) {
        is CatalogState.Ready -> s.view
        is CatalogState.Loading -> s.cached
        is CatalogState.Failed -> s.cached
        else -> null
    }

    suspend fun refresh(force: Boolean) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                loadCacheLocked()
                val cached = currentView()
                val now = clock()
                if (!force && cached != null && cached.checkedAtMs <= now &&
                    now - cached.checkedAtMs < rules.catalogRefreshAfterMin * 60_000L
                ) {
                    state.value = CatalogState.Ready(cached)
                    return@withContext
                }
                if (!network.value.connected) {
                    state.value = CatalogState.Failed(CatalogProblem.NO_NETWORK, cached)
                    return@withContext
                }
                state.value = CatalogState.Loading(cached)
                try {
                    state.value = fetch(cached)
                } catch (e: CancellationException) {
                    state.value = cached?.let { CatalogState.Ready(it) } ?: CatalogState.NotLoaded
                    throw e
                }
            }
        }
    }

    private fun fetch(cached: CatalogView?): CatalogState {
        val etag = if (cached != null) meta?.etag else null
        val response = try {
            http.get(PackRequest(address.url, ifNoneMatch = etag))
        } catch (_: ClockProblem) {
            return CatalogState.Failed(CatalogProblem.CLOCK, cached)
        } catch (_: NetworkRuleRefused) {
            return CatalogState.Failed(CatalogProblem.SERVER_ERROR, cached)
        } catch (_: BadResponse) {
            return CatalogState.Failed(CatalogProblem.SERVER_ERROR, cached)
        } catch (_: IOException) {
            return CatalogState.Failed(CatalogProblem.UNREACHABLE, cached)
        }
        response.use { r ->
            when (r.status) {
                304 -> {
                    val m = meta
                    if (cached == null || m == null) return CatalogState.Failed(CatalogProblem.SERVER_ERROR, null)
                    val now = clock()
                    val newMeta = m.copy(checkedMs = now)
                    try {
                        writeAtomically(metaFile, JSON.encodeToString(CatalogCacheMeta.serializer(), newMeta).toByteArray(Charsets.UTF_8))
                    } catch (_: IOException) {
                        // The list is still good; only its time stamp did not reach the disk.
                    }
                    meta = newMeta
                    return CatalogState.Ready(cached.copy(checkedAtMs = now, fromCache = false))
                }
                200 -> {
                    val bytes = try {
                        readAtMost(r.body, rules.catalogMaxBytes + 1)
                    } catch (_: IOException) {
                        return CatalogState.Failed(CatalogProblem.UNREACHABLE, cached)
                    }
                    return when (val parsed = parse(bytes)) {
                        is CatalogParse.Refused -> CatalogState.Failed(
                            if (parsed.reason == CatalogRefusal.NEWER_FORMAT) CatalogProblem.NEWER_FORMAT else CatalogProblem.BROKEN,
                            cached,
                        )
                        is CatalogParse.Ok -> {
                            val now = clock()
                            val old = meta
                            if (old != null && parsed.catalog.serial < old.serial) {
                                DownloadLog.info("catalog serial ${parsed.catalog.serial} is lower than the cached ${old.serial}; using it") // log-ok: two numbers
                            }
                            val newMeta = CatalogCacheMeta(address.url, r.etag, parsed.catalog.serial, now)
                            try {
                                cacheDir.mkdirs()
                                writeAtomically(bytesFile, bytes)
                                writeAtomically(metaFile, JSON.encodeToString(CatalogCacheMeta.serializer(), newMeta).toByteArray(Charsets.UTF_8))
                            } catch (_: IOException) {
                                DownloadLog.info("catalog cache not written") // log-ok: plain text
                            }
                            meta = newMeta
                            CatalogState.Ready(CatalogView(parsed.catalog, now, fromCache = false))
                        }
                    }
                }
                else -> return CatalogState.Failed(CatalogProblem.SERVER_ERROR, cached)
            }
        }
    }

    companion object {
        val JSON = Json { ignoreUnknownKeys = true }

        fun readAtMost(input: InputStream, limit: Long): ByteArray {
            val out = ByteArrayOutputStream()
            val buf = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                total += n
                out.write(buf, 0, n)
                if (total >= limit) break
            }
            return out.toByteArray()
        }
    }
}

/** Writes [bytes] to a temporary file beside [target], syncs it and renames it over [target]. */
internal fun writeAtomically(target: File, bytes: ByteArray) {
    target.parentFile?.mkdirs()
    val tmp = File(target.parentFile, target.name + ".tmp")
    java.io.FileOutputStream(tmp).use { out ->
        out.write(bytes)
        out.flush()
        out.fd.sync()
    }
    try {
        java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    } catch (_: java.nio.file.AtomicMoveNotSupportedException) {
        java.nio.file.Files.move(tmp.toPath(), target.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }
}

/** The module's few log lines (java.util.logging reaches logcat on Android). Every call is marked // log-ok: (rule PV2, N3). */
internal object DownloadLog {
    private val logger = java.util.logging.Logger.getLogger("Beamline.download")
    fun info(line: String) = logger.info(line) // log-ok: callers pass only numbers, enum names, pack ids and catalog-relative paths
}
