package io.github.spysyweeb.beamline.download

import io.github.spysyweeb.beamline.api.data.ArchiveInfo
import io.github.spysyweeb.beamline.api.data.InstallStatus
import io.github.spysyweeb.beamline.api.data.InstallStep
import io.github.spysyweeb.beamline.api.data.InstalledData
import io.github.spysyweeb.beamline.api.data.InstalledSet
import io.github.spysyweeb.beamline.api.data.PackArchive
import io.github.spysyweeb.beamline.api.data.PackFolder
import io.github.spysyweeb.beamline.api.data.PackInfo
import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.download.net.CatalogAddress
import io.github.spysyweeb.beamline.download.net.PackHttp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

fun gzip(b: ByteArray): ByteArray = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(b) } }.toByteArray()

/** Bytes that gzip cannot shrink. */
fun randomBytes(n: Int, seed: Int = n): ByteArray = Random(seed).nextBytes(n)

/** Bytes that gzip shrinks well (repeated text with a counter). */
fun textBytes(n: Int): ByteArray {
    val sb = StringBuilder()
    var i = 0
    while (sb.length < n) sb.append("row ").append(i++ % 997).append(" of the routing tiles\n")
    return sb.substring(0, n).toByteArray()
}

class TestPart(val name: String, val file: String, val bytes: ByteArray, val gzip: Boolean, val optional: Boolean = false) {
    val transfer: ByteArray by lazy { if (gzip) gzip(bytes) else bytes }
    val transferName get() = if (gzip) "$file.gz" else file
}

/** One synthetic pack: its manifest, its transfer files and its catalog entry. */
class TestPack(
    val parts: List<TestPart>,
    val id: String = "us-pa",
    val state: String = "PA",
    val name: String = "Pennsylvania",
    val packVersion: Long = 202611152241,
    val snapshot: String = "2026-11-14",
    val manifestVersion: Int = 3,
    /** Changes the manifest only (the catalog keeps [snapshot]): a list mismatch. */
    val manifestSnapshot: String = snapshot,
) {
    val release get() = "$id-$packVersion"

    val manifestBytes: ByteArray by lazy {
        val partsJson = parts.joinToString(",") {
            "\"${it.name}\":{\"file\":\"${it.file}\",\"format\":\"test\",\"format_version\":1,\"bytes\":${it.bytes.size},\"sha256\":\"${sha256(it.bytes)}\"}"
        }
        ("{\"manifest_version\":$manifestVersion,\"pack_id\":\"$id\",\"pack_version\":$packVersion,\"name\":\"$name\"," +
            "\"country\":\"US\",\"state\":\"$state\",\"osm_snapshot\":\"$manifestSnapshot\",\"bounds\":[-80.51985,39.7198,-74.68956,42.51607]," +
            "\"attribution\":\"© OpenStreetMap contributors\",\"parts\":{$partsJson}}\n").toByteArray()
    }

    fun path(file: String) = "/$release/$file"

    fun entryJson(): String {
        val ordered = parts.sortedBy { it.transfer.size }
        val partsJson = ordered.joinToString(",") {
            "{\"name\":\"${it.name}\",\"file\":\"${it.file}\",\"optional\":${it.optional},\"bytes\":${it.bytes.size},\"sha256\":\"${sha256(it.bytes)}\"," +
                "\"credit\":\"© OpenStreetMap contributors\",\"transfer\":{\"path\":\"../$release/${it.transferName}\",\"encoding\":\"${if (it.gzip) "gzip" else "identity"}\"," +
                "\"bytes\":${it.transfer.size},\"sha256\":\"${sha256(it.transfer)}\"}}"
        }
        return "{\"id\":\"$id\",\"state\":\"$state\",\"name\":\"$name\",\"pack_version\":$packVersion,\"manifest_version\":$manifestVersion," +
            "\"osm_snapshot\":\"$snapshot\",\"credit\":\"© OpenStreetMap contributors\",\"bounds\":[-80.51985,39.7198,-74.68956,42.51607]," +
            "\"manifest\":{\"path\":\"../$release/manifest.json\",\"encoding\":\"identity\",\"bytes\":${manifestBytes.size},\"sha256\":\"${sha256(manifestBytes)}\"}," +
            "\"parts\":[$partsJson]}"
    }

    fun serveOn(server: TestPackServer) {
        server.put(path("manifest.json"), manifestBytes)
        parts.forEach { server.put(path(it.transferName), it.transfer) }
    }

    fun part(name: String) = parts.first { it.name == name }
}

fun catalogJson(packs: List<String>, serial: Long = 20261115224107): String =
    "{\"format\":\"beamline-catalog/1\",\"serial\":$serial,\"generated_utc\":\"2026-11-15T22:41:07Z\"," +
        "\"licence\":{\"id\":\"ODbL-1.0\",\"name\":\"Open Database License 1.0\",\"url\":\"https://opendatacommons.org/licenses/odbl/1-0/\"," +
        "\"notice\":\"Map data © OpenStreetMap contributors, available under the Open Database License.\"}," +
        "\"packs\":[${packs.joinToString(",")}]}"

/** A ustar entry as the test's own reader sees it. */
class TarEntry(val name: String, val size: Long, val data: ByteArray)

/** The test's own ustar reader: headers, checksums, sizes, padding, two zero blocks at the end. */
fun readUstar(input: InputStream): List<TarEntry> {
    val out = mutableListOf<TarEntry>()
    while (true) {
        val h = input.readNBytes(512)
        check(h.size == 512) { "short header" }
        if (h.all { it == 0.toByte() }) {
            check(input.readNBytes(512).all { it == 0.toByte() }) { "second zero block missing" }
            check(input.read() == -1) { "bytes after the end" }
            return out
        }
        val name = String(h, 0, (0 until 100).firstOrNull { h[it] == 0.toByte() } ?: 100, Charsets.UTF_8)
        check(String(h, 257, 8, Charsets.US_ASCII) == "ustar\u000000") { "magic" }
        val size = String(h, 124, 11, Charsets.US_ASCII).toLong(8)
        val chk = String(h, 148, 6, Charsets.US_ASCII).toInt(8)
        val sum = h.mapIndexed { i, b -> if (i in 148 until 156) ' '.code else b.toInt() and 0xff }.sum()
        check(chk == sum) { "checksum of $name" }
        check(h[156] == '0'.code.toByte()) { "type of $name" }
        val data = input.readNBytes(size.toInt())
        check(data.size.toLong() == size) { "data of $name" }
        val pad = ((512 - size % 512) % 512).toInt()
        check(input.readNBytes(pad).all { it == 0.toByte() }) { "padding of $name" }
        out += TarEntry(name, size, data)
    }
}

/** Records every call and installs what it is given (docs/architecture-downloads.md 10.1: "a fake InstalledData"). */
class FakeInstalled : InstalledData {
    override val installed = MutableStateFlow(InstalledSet(scanned = true))
    override val install = MutableStateFlow<InstallStatus>(InstallStatus.Idle)
    var folders = false
    var optionalParts = false
    override val installsFromFolder: Boolean get() = folders
    override val changesOptionalParts: Boolean get() = optionalParts
    val calls = CopyOnWriteArrayList<String>()
    var problems: List<String> = emptyList()
    var neededBytes = 0L
    var busy = false
    var failWith: String? = null
    var cancelResult = false
    var lastTar: List<TarEntry>? = null
    var lastFolder: String? = null
    var lastFolderFiles: List<String>? = null

    override suspend fun refresh() {}

    override suspend fun inspect(archive: PackArchive): ArchiveInfo {
        calls += "inspect"
        val entries = archive.open().use { readUstar(it) }
        val m = Json.parseToJsonElement(entries.first { it.name == "manifest.json" }.data.decodeToString()).jsonObject
        val parts = m["parts"]!!.jsonObject
        val addresses = (parts["addresses"] as? JsonObject)?.get("bytes")?.jsonPrimitive?.long
        return ArchiveInfo(
            packId = m["pack_id"]!!.jsonPrimitive.content, name = m["name"]!!.jsonPrimitive.content,
            state = m["state"]!!.jsonPrimitive.content, osmSnapshot = m["osm_snapshot"]!!.jsonPrimitive.content,
            neededBytes = neededBytes, addressesBytes = addresses, replacesInstalled = false, freeBytes = 0, problems = problems,
        )
    }

    private fun held() = installed.value.changesHeldBy != null

    override fun startInstall(archive: PackArchive, includeAddresses: Boolean): Boolean {
        calls += "startInstall"
        if (busy || held()) return false
        val entries = archive.open().use { readUstar(it) }
        lastTar = entries
        finish(Json.parseToJsonElement(entries.first { it.name == "manifest.json" }.data.decodeToString()).jsonObject, archive.label, includeAddresses)
        return true
    }

    override fun startInstallFromFolder(folder: PackFolder, includeAddresses: Boolean): Boolean {
        calls += "startInstallFromFolder"
        if (!folders || busy || held()) return false
        lastFolder = folder.dir
        lastFolderFiles = File(folder.dir).list()!!.sorted()
        finish(Json.parseToJsonElement(File(folder.dir, "manifest.json").readText()).jsonObject, folder.label, includeAddresses)
        return true
    }

    override fun startAddOptionalParts(folder: PackFolder): Boolean {
        calls += "startAddOptionalParts"
        if (!optionalParts || busy || held()) return false
        lastFolder = folder.dir
        lastFolderFiles = File(folder.dir).list()!!.sorted()
        val m = Json.parseToJsonElement(File(folder.dir, "manifest.json").readText()).jsonObject
        install.value = InstallStatus.Running(folder.label, InstallStep.COPYING, 0, 1, null)
        val id = m["pack_id"]!!.jsonPrimitive.content
        installed.value = installed.value.copy(
            generation = installed.value.generation + 1,
            packs = installed.value.packs.map { if (it.packId == id) it.copy(addressesFile = "/fake/addresses.sqlite", addressesSkipped = false) else it },
        )
        install.value = InstallStatus.Done(m["name"]!!.jsonPrimitive.content, m["osm_snapshot"]!!.jsonPrimitive.content, emptyList())
        return true
    }

    private fun finish(m: JsonObject, label: String, includeAddresses: Boolean) {
        install.value = InstallStatus.Running(label, InstallStep.CHECKING, 0, 100, null)
        install.value = InstallStatus.Running(label, InstallStep.COPYING, 50, 100, "map.pmtiles")
        failWith?.let {
            install.value = InstallStatus.Failed(label, it)
            return
        }
        if (cancelResult) {
            install.value = InstallStatus.Cancelled(label)
            return
        }
        val hasAddresses = (m["parts"] as JsonObject).containsKey("addresses") && includeAddresses
        installed.value = installed.value.copy(
            generation = installed.value.generation + 1,
            packs = installed.value.packs.filter { it.packId != m["pack_id"]!!.jsonPrimitive.content } + packInfo(
                m["pack_id"]!!.jsonPrimitive.content, m["state"]!!.jsonPrimitive.content,
                (m["pack_version"] as? JsonPrimitive)?.long, m["osm_snapshot"]!!.jsonPrimitive.content, hasAddresses,
            ),
        )
        install.value = InstallStatus.Done(m["name"]!!.jsonPrimitive.content, m["osm_snapshot"]!!.jsonPrimitive.content, emptyList())
    }

    override fun cancelInstall() {
        calls += "cancelInstall"
    }

    override fun dismissInstallResult() {
        calls += "dismissInstallResult"
        install.value = InstallStatus.Idle
    }

    override suspend fun remove(packId: String): String? = null

    override fun holdChanges(reason: String): AutoCloseable? {
        installed.value = installed.value.copy(changesHeldBy = reason)
        return AutoCloseable { installed.value = installed.value.copy(changesHeldBy = null) }
    }

    companion object {
        fun packInfo(id: String, state: String, version: Long?, snapshot: String, addresses: Boolean) = PackInfo(
            packId = id, name = "Pennsylvania", state = state, osmSnapshot = snapshot, bounds = Bounds(-80.5, 39.7, -74.7, 42.5),
            attribution = "© OpenStreetMap contributors", folder = "/fake/$id", mapFile = "/fake/$id/map.pmtiles", mapMinZoom = 0, mapMaxZoom = 14,
            searchFile = null, addressesFile = if (addresses) "/fake/$id/addresses.sqlite" else null, addressesSkipped = !addresses,
            searchProblem = null, searchRankingSha256 = null, inRoutingSet = true, verified = true, bytesOnDisk = 1, packVersion = version,
        )
    }
}

/** Rules for the tests: the file's values, with a small sync step so the death-count tests need megabytes, not 64 MiB. */
val TEST_RULES = DownloadRules(syncEveryBytes = 1_048_576, progressEveryMs = 0)

/** Everything one engine test needs, around one [TestPackServer]. */
class Harness(
    val root: File,
    val server: TestPackServer,
    val io: CoroutineDispatcher,
    val clock: () -> Long,
    val rules: DownloadRules = TEST_RULES,
    socketFactory: javax.net.SocketFactory = javax.net.SocketFactory.getDefault(),
) {
    val address: CatalogAddress = CatalogAddress.parse(server.url("/catalog/catalog.json"), allowCleartext = true)!!
    val http = PackHttp(address, rules, socketFactory)
    val network = MutableStateFlow(NetworkState(connected = true, unmetered = true))
    val installed = FakeInstalled()
    val catalogs = CatalogSource(address, http, File(root, "getpacks"), rules, 3, 3, network, clock)
    @Volatile var free: Long = 1L shl 50
    val downloads = File(root, "downloads")

    fun publish(vararg packs: TestPack, serial: Long = 20261115224107) {
        packs.forEach { it.serveOn(server) }
        server.put("/catalog/catalog.json", catalogJson(packs.map { it.entryJson() }, serial).toByteArray())
    }

    /** The catalog entry of [pack], parsed as the app parses it. */
    fun entry(pack: TestPack): CatalogPack {
        val parsed = CatalogFormat.parse(catalogJson(listOf(pack.entryJson())).toByteArray(), address, 3, 3, rules.catalogMaxBytes, rules.maxFileBytes)
        return (parsed as CatalogParse.Ok).catalog.packs.single()
    }

    fun engine(scope: CoroutineScope, handoff: PackHandoff = handoffFor(installed)) =
        DownloadEngine(downloads, http, catalogs, handoff, installed, network, { free }, rules, scope, io, clock)

    fun jobFolder(pack: TestPack) = File(downloads, pack.release)
}
