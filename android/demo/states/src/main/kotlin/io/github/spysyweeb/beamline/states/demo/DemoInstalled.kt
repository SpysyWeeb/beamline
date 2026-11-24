package io.github.spysyweeb.beamline.states.demo

import io.github.spysyweeb.beamline.api.data.ArchiveInfo
import io.github.spysyweeb.beamline.api.data.InstallStatus
import io.github.spysyweeb.beamline.api.data.InstallStep
import io.github.spysyweeb.beamline.api.data.InstalledData
import io.github.spysyweeb.beamline.api.data.InstalledSet
import io.github.spysyweeb.beamline.api.data.PackArchive
import io.github.spysyweeb.beamline.api.data.PackFolder
import io.github.spysyweeb.beamline.api.data.PackInfo
import io.github.spysyweeb.beamline.api.model.Bounds
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream
import java.security.MessageDigest

/**
 * The demo's stand-in for track P's InstalledLibrary (docs/architecture-downloads.md 10.3): it reads
 * manifests (format 2 or 3), checks every part's size and SHA-256, and moves a downloaded folder's
 * parts into `<packs>/<pack_id>/` (a file archive is copied). It merges no routing and opens
 * nothing: the demo only proves the download side. Never shipped.
 */
class DemoInstalled(private val packs: File, private val scope: CoroutineScope, private val free: (File) -> Long) : InstalledData {
    private val _installed = MutableStateFlow(InstalledSet())
    private val _install = MutableStateFlow<InstallStatus>(InstallStatus.Idle)
    override val installed: StateFlow<InstalledSet> get() = _installed
    override val install: StateFlow<InstallStatus> get() = _install
    override val installsFromFolder: Boolean get() = true
    @Volatile private var held: String? = null

    init {
        scope.launch(Dispatchers.IO) { refresh() }
    }

    /** Reads the packs folder on Dispatchers.IO (a suspend function is called from the main thread too). */
    override suspend fun refresh() = withContext(Dispatchers.IO) {
        packs.mkdirs()
        val list = packs.listFiles()?.filter { it.isDirectory && !it.name.startsWith(".") && File(it, "manifest.json").isFile }
            ?.mapNotNull { runCatching { info(it, JSONObject(File(it, "manifest.json").readText())) }.getOrNull() } ?: emptyList()
        _installed.value = InstalledSet(generation = _installed.value.generation + 1, scanned = true, packs = list, changesHeldBy = held)
    }

    private fun info(folder: File, m: JSONObject): PackInfo {
        val b = m.getJSONArray("bounds")
        val parts = m.getJSONObject("parts")
        fun file(name: String) = parts.optJSONObject(name)?.getString("file")?.let { File(folder, it) }?.takeIf { it.isFile }?.absolutePath
        val hasAddresses = parts.has("addresses")
        return PackInfo(
            packId = m.getString("pack_id"), name = m.getString("name"), state = m.getString("state"), osmSnapshot = m.getString("osm_snapshot"),
            bounds = Bounds(b.getDouble(0), b.getDouble(1), b.getDouble(2), b.getDouble(3)), attribution = m.optString("attribution"),
            folder = folder.absolutePath, mapFile = file("map") ?: "", mapMinZoom = 0, mapMaxZoom = 14, searchFile = file("search"),
            addressesFile = file("addresses"), addressesSkipped = hasAddresses && file("addresses") == null, searchProblem = null,
            searchRankingSha256 = null, inRoutingSet = false, verified = true,
            bytesOnDisk = folder.listFiles()?.sumOf { it.length() } ?: 0, packVersion = if (m.has("pack_version")) m.getLong("pack_version") else versionOf(m),
        )
    }

    /** As build_catalog.py derives it for a format-2 manifest: created_utc as YYYYMMDDhhmm. */
    private fun versionOf(m: JSONObject): Long? = m.optString("created_utc").takeIf { it.length >= 16 }?.let {
        (it.substring(0, 4) + it.substring(5, 7) + it.substring(8, 10) + it.substring(11, 13) + it.substring(14, 16)).toLongOrNull()
    }

    private fun readManifest(archive: PackArchive): JSONObject = archive.open().use { input ->
        val header = readFully(input, 512)
        val name = String(header, 0, 100).trimEnd('\u0000')
        require(name == "manifest.json" || name == "./manifest.json") { "the first entry is not manifest.json" }
        val size = String(header, 124, 11).trim().toLong(8)
        JSONObject(String(readFully(input, size.toInt())))
    }

    override suspend fun inspect(archive: PackArchive): ArchiveInfo {
        val m = readManifest(archive)
        val parts = m.getJSONObject("parts")
        var total = 0L
        var addresses: Long? = null
        for (k in parts.keys()) {
            val bytes = parts.getJSONObject(k).getLong("bytes")
            total += bytes
            if (k == "addresses") addresses = bytes
        }
        val version = m.getInt("manifest_version")
        val problems = buildList {
            if (version > 3) add("manifest_version $version is newer than this app reads (3): update the app")
            if (version < 2) add("manifest_version $version is an old pack format; rebuild the pack")
        }
        return ArchiveInfo(m.getString("pack_id"), m.getString("name"), m.getString("state"), m.getString("osm_snapshot"),
            neededBytes = total + 200_000_000, addressesBytes = addresses,
            replacesInstalled = _installed.value.packs.any { it.packId == m.getString("pack_id") }, freeBytes = free(packs), problems = problems)
    }

    override fun startInstall(archive: PackArchive, includeAddresses: Boolean): Boolean {
        if (_install.value is InstallStatus.Running || held != null) return false
        _install.value = InstallStatus.Running(archive.label, InstallStep.CHECKING, 0, 0, null)
        scope.launch(Dispatchers.IO) {
            try {
                val m = readManifest(archive)
                val id = m.getString("pack_id")
                val staging = File(packs, ".staging-$id").apply { deleteRecursively(); mkdirs() }
                val want = partsOf(m, includeAddresses)
                val total = want.values.sumOf { it.first }
                var done = 0L
                archive.open().use { input ->
                    while (true) {
                        val h = readFully(input, 512)
                        if (h.size < 512 || h.all { it == 0.toByte() }) break
                        val name = String(h, 0, 100).trimEnd('\u0000').removePrefix("./")
                        val size = String(h, 124, 11).trim().toLong(8)
                        val target = want[name]
                        if (target != null && name != "manifest.json") {
                            val d = MessageDigest.getInstance("SHA-256")
                            File(staging, name).outputStream().use { out ->
                                copy(input, size) { b, n ->
                                    out.write(b, 0, n); d.update(b, 0, n); done += n
                                    _install.value = InstallStatus.Running(archive.label, InstallStep.COPYING, done, total, name)
                                }
                            }
                            check(hex(d.digest()) == target.second) { "$name does not match its manifest" }
                        } else {
                            copy(input, size) { _, _ -> }
                        }
                        readFully(input, ((512 - size % 512) % 512).toInt())
                    }
                }
                File(staging, "manifest.json").writeText(m.toString())
                finish(id, staging, archive.label, m)
            } catch (e: Exception) {
                _install.value = InstallStatus.Failed(archive.label, e.message ?: e.javaClass.simpleName)
            }
        }
        return true
    }

    override fun startInstallFromFolder(folder: PackFolder, includeAddresses: Boolean): Boolean {
        if (_install.value is InstallStatus.Running || held != null) return false
        _install.value = InstallStatus.Running(folder.label, InstallStep.CHECKING, 0, 0, null)
        scope.launch(Dispatchers.IO) {
            val dir = File(folder.dir)
            val moved = mutableListOf<Pair<File, File>>()
            try {
                val m = JSONObject(File(dir, "manifest.json").readText())
                val id = m.getString("pack_id")
                val staging = File(packs, ".staging-$id").apply { deleteRecursively(); mkdirs() }
                val want = partsOf(m, includeAddresses).filterKeys { it != "manifest.json" }
                val total = want.values.sumOf { it.first }
                var done = 0L
                for ((name, spec) in want) {
                    val src = File(dir, name)
                    val dst = File(staging, name)
                    check(src.renameTo(dst)) { "$name could not be moved" }
                    moved += src to dst
                    val d = MessageDigest.getInstance("SHA-256")
                    dst.inputStream().use { input -> copy(input, dst.length()) { b, n -> d.update(b, 0, n); done += n
                        _install.value = InstallStatus.Running(folder.label, InstallStep.CHECKING, done, total, name) } }
                    check(dst.length() == spec.first && hex(d.digest()) == spec.second) { "$name does not match its manifest" }
                }
                File(dir, "manifest.json").copyTo(File(staging, "manifest.json"), overwrite = true)
                finish(id, staging, folder.label, m)
            } catch (e: Exception) {
                moved.forEach { (src, dst) -> dst.renameTo(src) }
                _install.value = InstallStatus.Failed(folder.label, e.message ?: e.javaClass.simpleName)
            }
        }
        return true
    }

    private fun finish(id: String, staging: File, label: String, m: JSONObject) {
        _install.value = InstallStatus.Running(label, InstallStep.MERGING_ROUTING, 1, 1, null)
        _install.value = InstallStatus.Running(label, InstallStep.FINISHING, 1, 1, null)
        File(staging, "install-record.json").writeText("{\"verified\":\"sha256\",\"source\":\"download\"}")
        val target = File(packs, id)
        val aside = File(packs, ".removing-$id")
        if (target.exists()) target.renameTo(aside)
        check(staging.renameTo(target)) { "the pack could not be put in place" }
        aside.deleteRecursively()
        val skipped = if (m.getJSONObject("parts").has("addresses") && !File(target, m.getJSONObject("parts").getJSONObject("addresses").getString("file")).exists()) listOf("addresses") else emptyList()
        kotlinx.coroutines.runBlocking { refresh() }
        _install.value = InstallStatus.Done(m.getString("name"), m.getString("osm_snapshot"), skipped)
    }

    private fun partsOf(m: JSONObject, includeAddresses: Boolean): Map<String, Pair<Long, String>> {
        val parts = m.getJSONObject("parts")
        return parts.keys().asSequence().filter { includeAddresses || it != "addresses" }.associate { k ->
            val p = parts.getJSONObject(k)
            p.getString("file") to (p.getLong("bytes") to p.getString("sha256").lowercase())
        } + ("manifest.json" to (0L to ""))
    }

    private inline fun copy(input: InputStream, size: Long, each: (ByteArray, Int) -> Unit) {
        val buf = ByteArray(256 * 1024)
        var left = size
        while (left > 0) {
            val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
            if (n < 0) error("the archive ends early")
            each(buf, n)
            left -= n
        }
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    /** Up to [n] bytes (fewer only at the end of the stream): InputStream's own reader of n bytes needs API 33. */
    private fun readFully(input: InputStream, n: Int): ByteArray {
        val out = ByteArray(n)
        var got = 0
        while (got < n) {
            val r = input.read(out, got, n - got)
            if (r < 0) break
            got += r
        }
        return if (got == n) out else out.copyOf(got)
    }

    override fun cancelInstall() = Unit

    override fun dismissInstallResult() {
        if (_install.value !is InstallStatus.Running) _install.value = InstallStatus.Idle
    }

    /** On Dispatchers.IO: GetPacks calls it from the main thread (StrictMode found the delete there on the device). */
    override suspend fun remove(packId: String): String? = withContext(Dispatchers.IO) {
        if (held != null) return@withContext held
        val f = File(packs, packId)
        if (!f.exists()) return@withContext "Not installed."
        f.deleteRecursively()
        refresh()
        null
    }

    override fun holdChanges(reason: String): AutoCloseable? {
        if (_install.value is InstallStatus.Running) return null
        held = reason
        _installed.value = _installed.value.copy(changesHeldBy = reason)
        return AutoCloseable {
            held = null
            _installed.value = _installed.value.copy(changesHeldBy = null)
        }
    }
}
