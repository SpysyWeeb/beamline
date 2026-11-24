package io.github.spysyweeb.beamline.routing.packs

import io.github.spysyweeb.beamline.routing.config.EngineConfig
import io.github.spysyweeb.beamline.routing.planner.TileCoverage
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.put
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.MessageDigest

/** One tile of a pack manifest. */
data class PackTile(val path: String, val level: Int, val bytes: Long, val sha256: String)

/**
 * A routing pack manifest as packbuilder/routing/build.py writes it (format beamline-routing-pack/1):
 * the state, the graph build (`261114-pa-oh-v3.9.1-b53087`, the same for all packs cut from one
 * build), the Valhalla version, the tar and every tile with its SHA-256.
 */
data class RoutingPackManifest(
    val state: String,
    val graphBuild: String,
    val valhallaVersion: String?,
    val tar: String,
    val tarBytes: Long,
    val tarSha256: String,
    val tiles: List<PackTile>,
    /** The manifest file's text, stored unchanged on the device. */
    val text: String,
) {
    companion object {
        const val FORMAT = "beamline-routing-pack/1"

        /** A state id as packbuilder names packs ("pa", "oh"): it becomes the file name packs/<state>.json. */
        private val STATE = Regex("[a-z0-9_-]{1,32}")

        /** A plain file name in the pack folder ("routing-pa-261114.tar"): no folder part, no "..". */
        private val FILE_NAME = Regex("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")

        /** A Valhalla tile path ("2/000/752/000.gph", "0/002/904.gph"). */
        private val TILE_PATH = Regex("[0-2](/[0-9]{3}){2,3}\\.gph")

        fun parse(text: String): RoutingPackManifest {
            val o = Json.parseToJsonElement(text).jsonObject
            require(o["format"]?.jsonPrimitive?.content == FORMAT) { "not a $FORMAT manifest" }
            val m = fields(o, text)
            // The manifest has no hash of its own (tar_sha256 is inside it) and its names become file
            // and member names, so anything but plain names is refused.
            if (!STATE.matches(m.state)) throw PackException("manifest state '${m.state}' is not a plain pack name")
            if (!FILE_NAME.matches(m.tar) || ".." in m.tar) throw PackException("manifest tar '${m.tar}' is not a plain file name")
            m.tiles.firstOrNull { !TILE_PATH.matches(it.path) }?.let { throw PackException("manifest tile '${it.path}' is not a tile path") }
            return m
        }

        private fun fields(o: JsonObject, text: String): RoutingPackManifest {
            return RoutingPackManifest(
                state = o["state"]!!.jsonPrimitive.content,
                graphBuild = o["graph_build"]!!.jsonPrimitive.content,
                valhallaVersion = o["valhalla_version"]?.jsonPrimitive?.content,
                tar = o["tar"]!!.jsonPrimitive.content,
                tarBytes = o["tar_bytes"]!!.jsonPrimitive.long,
                tarSha256 = o["tar_sha256"]!!.jsonPrimitive.content,
                tiles = o["tiles"]!!.jsonArray.map {
                    val t = it.jsonObject
                    PackTile(t["path"]!!.jsonPrimitive.content, t["level"]!!.jsonPrimitive.int, t["bytes"]!!.jsonPrimitive.long,
                        t["sha256"]!!.jsonPrimitive.content)
                },
                text = text,
            )
        }
    }
}

/** A downloaded pack: its manifest and its tile tar. */
data class PackSource(val manifest: RoutingPackManifest, val tar: File) {
    companion object {
        /** A pack folder as build.py writes it: manifest.json and the tar it names. */
        fun fromDir(dir: File): PackSource {
            val m = RoutingPackManifest.parse(File(dir, "manifest.json").readText(Charsets.UTF_8))
            return PackSource(m, File(dir, m.tar))
        }
    }
}

/**
 * A refused or failed routing change. [kind] says what went wrong, so a caller can tell the user in
 * plain words without showing [message] (which is written for engineers).
 */
class PackException(message: String, val kind: Kind = Kind.OTHER) : Exception(message) {
    enum class Kind {
        /** The pack comes from another graph build than a pack it would sit beside. */
        GRAPH_BUILD,

        /** The pack (or one of its tiles) was built for another Valhalla version than the engine. */
        ENGINE_VERSION,

        /** The new tile set does not fit beside the old one. */
        NO_SPACE,

        /** A tar, a tile or a manifest does not match what it should be. */
        DAMAGED,
        OTHER,
    }
}

data class InstallReport(
    val installed: List<String>,
    val replaced: List<String>,
    val tilesInPacks: Int,
    val tilesAdded: Int,
    val tilesChanged: Int,
    val tilesRemovedStale: Int,
    val tilesAlreadyPresent: Int,
    val graphBuild: String,
    val tilesetBytes: Long,
    val wallMs: Long,
)

data class UninstallReport(val uninstalled: String, val tilesRemoved: Int, val tilesKeptShared: Int, val tilesetBytes: Long, val wallMs: Long)

/**
 * Installs, updates and removes routing packs in the one tile set the engine loads, with the rules
 * of packbuilder/routing/packs.py, tar layout (the layout the phone uses: the swap is atomic):
 *
 *  - one tile set: a single routing.tar with index.bin, rewritten from the union of the installed
 *    packs' tiles and swapped in with an atomic rename; tiles on a border are in both neighbouring
 *    packs and are stored once; each installed manifest lists its tiles (a reference count);
 *  - all installed packs come from one graph build: edges refer to node indexes in neighbour tiles
 *    that are only valid within one build, and Valhalla does not check this. A pack from another
 *    build is refused unless every pack it would sit beside is updated in the same call;
 *  - installing an installed state is an update: its tiles are replaced, tiles only its old copy
 *    listed are removed;
 *  - a pack built for another Valhalla version than the engine is refused (manifest and every tile
 *    header): Valhalla itself accepts tiles from another version;
 *  - every tile written is checked against its manifest SHA-256, the pack tar against tar_sha256;
 *  - a refused or failed install leaves the device as it was; a change that needs more free space
 *    than the folder has (the new tile set is written beside the old one) is refused before writing;
 *  - every refusal is a [PackException] whose [PackException.kind] says what went wrong;
 *  - an installed manifest that cannot be read (damaged storage) never stops a call: [installed]
 *    leaves the state out, [unreadable] names it, and the next change rewrites the set without it.
 *
 * Device folder: routing.tar, device.json (layout and engine version), packs/<state>.json (the
 * installed manifests) and valhalla.json (the engine config with the absolute tar path). Reopen the
 * engine after a change: an engine opened before keeps routing on the old file.
 *
 * Crash safety (beyond packs.py, which writes the manifests after the swap): a change first writes
 * routing.tar.new and stages its manifests and device files in pending/, then writes
 * pending/COMMIT (the commit point), then swaps the tile set and moves the staged files in. If the
 * process dies before COMMIT exists, the next call ([recover], run by every public method) deletes
 * the new files and the device is as it was; if it dies after, the next call completes the change.
 */
class RoutingPackManager(
    val deviceDir: File,
    /** The Valhalla version of the engine that loads the tiles (valhalla-mobile 0.6.4: 3.9.1). */
    val engineVersion: String,
    /** Hash every pack tar before installing (as packs.py). Tiles are always hashed. */
    private val verifyTarHash: Boolean = true,
    private val clock: () -> Long = System::currentTimeMillis,
    /** Usable bytes in a folder (File.getUsableSpace); replaceable in tests. */
    private val freeSpace: (File) -> Long = { it.usableSpace },
) {
    val tileSet = File(deviceDir, "routing.tar")
    private val newTileSet = File(deviceDir, "routing.tar.new")
    private val packsDir = File(deviceDir, "packs")
    private val deviceFile = File(deviceDir, "device.json")
    val engineConfigFile = File(deviceDir, "valhalla.json")
    private val pendingDir = File(deviceDir, "pending")
    private val commitFile = File(pendingDir, "COMMIT")

    /** Test hook, called at "tileset-written", "committed" and "tileset-swapped"; a test throws to simulate a kill. */
    internal var faultHook: (String) -> Unit = {}

    /** The installed states and their manifests; a state whose manifest cannot be read is left out ([unreadable]). */
    fun installed(): Map<String, RoutingPackManifest> {
        recover()
        return readInstalled()
    }

    /**
     * States whose installed manifest (packs/<state>.json) cannot be read, e.g. after damaged
     * storage. [installed] leaves them out, so they never stop a scan; [uninstall] removes them
     * (their tiles leave the set when it is rewritten from the readable states), and any install
     * rewrites the set without their tiles.
     */
    fun unreadable(): List<String> {
        recover()
        return stateFiles().filter { readState(it) == null }.map { it.name.removeSuffix(".json") }
    }

    /** True while a change a killed process left half done waits for [recover]. */
    fun hasPendingChange(): Boolean = commitFile.isFile || pendingDir.exists() || newTileSet.exists()

    private fun stateFiles(): List<File> = (packsDir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).sortedBy { it.name }

    private fun readState(f: File): RoutingPackManifest? = try {
        RoutingPackManifest.parse(f.readText(Charsets.UTF_8))
    } catch (e: Exception) {
        null
    }

    private fun readInstalled(): Map<String, RoutingPackManifest> {
        val out = LinkedHashMap<String, RoutingPackManifest>()
        for (f in stateFiles()) readState(f)?.let { out[f.name.removeSuffix(".json")] = it }
        return out
    }

    /** What [recover] found. */
    enum class Recovery { NOTHING_PENDING, ROLLED_BACK, COMPLETED }

    /**
     * Finishes or undoes a change that a killed process left half done (see the class comment).
     * Every public method calls it first; the app may also call it at start-up.
     */
    fun recover(): Recovery = when {
        commitFile.isFile -> {
            applyCommitted()
            Recovery.COMPLETED
        }
        pendingDir.exists() || newTileSet.exists() -> {
            newTileSet.delete()
            pendingDir.deleteRecursively()
            Recovery.ROLLED_BACK
        }
        else -> Recovery.NOTHING_PENDING
    }

    /** Which points lie on installed tiles (for the planner's "outside the installed maps"). */
    fun coverage(): TileCoverage = TileCoverage.fromTilePaths(installed().values.flatMap { m -> m.tiles.map { it.path } })

    private fun checkLayout() {
        if (deviceFile.exists()) {
            val layout = Json.parseToJsonElement(deviceFile.readText()).jsonObject["layout"]?.jsonPrimitive?.content
            if (layout != "tar" && readInstalled().isNotEmpty()) throw PackException("this device uses the $layout layout; only tar is supported here")
        }
    }

    /** Install (or update) one or more packs in one step. */
    fun install(sources: List<PackSource>): InstallReport {
        val t0 = clock()
        recover()
        checkLayout()
        val new = LinkedHashMap<String, PackSource>()
        for (s in sources) {
            if (s.manifest.state in new) throw PackException("pack ${s.manifest.state} given twice")
            if (!s.tar.isFile) throw PackException("${s.tar.name} is missing")
            if (s.tar.length() != s.manifest.tarBytes) {
                throw PackException("${s.tar.name} has ${s.tar.length()} bytes, its manifest says ${s.manifest.tarBytes}", PackException.Kind.DAMAGED)
            }
            if (verifyTarHash && sha256(s.tar) != s.manifest.tarSha256) {
                throw PackException("${s.tar.name} does not match its manifest sha256", PackException.Kind.DAMAGED)
            }
            new[s.manifest.state] = s
        }
        val current = readInstalled()
        val remaining = current.filterKeys { it !in new }
        val replaced = current.keys.filter { it in new }
        checkCompatible(new.mapValues { it.value.manifest }, remaining)

        val keep = remaining.values.flatMap { m -> m.tiles.map { it.path to it.sha256 } }.toMap()
        val incoming = LinkedHashMap<String, Pair<String, String>>() // path -> (sha, state)
        for ((st, s) in new) for (t in s.manifest.tiles) incoming.putIfAbsent(t.path, t.sha256 to st)
        val oldPresent = current.values.flatMap { m -> m.tiles.map { it.path to it.sha256 } }.toMap()
        val final = keep.keys + incoming.keys
        val stale = oldPresent.keys - final
        val changed = incoming.count { (p, v) -> p in oldPresent && oldPresent[p] != v.first }
        val added = incoming.keys.count { it !in oldPresent }

        deviceDir.mkdirs()
        packsDir.mkdirs()
        val sizes = HashMap<String, Long>()
        for (m in remaining.values) for (t in m.tiles) sizes[t.path] = t.bytes
        for (s in new.values) for (t in s.manifest.tiles) sizes[t.path] = t.bytes
        checkFreeSpace(sizes.values)
        // source of every tile: new pack bytes win over the installed copy
        val wanted = HashMap<String, TileSource>()
        val installedMembers by lazy { TileTar.members(tileSet).associateBy { it.name } }
        for ((p, sha) in keep) wanted[p] = TileSource(tileSet, sha, "the installed routing.tar") { installedMembers[p] }
        val packMembers = new.mapValues { (_, s) -> TileTar.members(s.tar).associateBy { it.name } }
        for ((p, v) in incoming) {
            val s = new.getValue(v.second)
            wanted[p] = TileSource(s.tar, v.first, s.tar.name) { packMembers.getValue(v.second)[p] }
        }
        writeTileSet(wanted, engineVersion)
        commit(staged = new.mapValues { it.value.manifest.text }, finalStates = (remaining.keys + new.keys).sorted(), removeTileSet = false)
        return InstallReport(new.keys.toList(), replaced, incoming.size, added, changed, stale.size,
            incoming.size - added - changed, new.values.first().manifest.graphBuild, tileSet.length(), clock() - t0)
    }

    /**
     * Removes [state] from the tile set. A state whose manifest cannot be read ([unreadable]) is
     * removed too: the set is rewritten from the readable states only.
     */
    fun uninstall(state: String): UninstallReport {
        val t0 = clock()
        recover()
        checkLayout()
        val current = readInstalled()
        val broken = state !in current && File(packsDir, "$state.json").isFile
        val mine = current[state] ?: if (broken) null else throw PackException("$state is not installed")
        val remaining = current.filterKeys { it != state }
        val keep = remaining.values.flatMap { m -> m.tiles.map { it.path to it.sha256 } }.toMap()
        val mineTiles = mine?.tiles.orEmpty()
        val drop = mineTiles.map { it.path }.toSet() - keep.keys
        if (keep.isNotEmpty()) {
            checkFreeSpace(remaining.values.flatMap { m -> m.tiles.map { it.path to it.bytes } }.toMap().values)
            val members by lazy { TileTar.members(tileSet).associateBy { it.name } }
            // the kept tiles were version-checked at install; uninstall re-checks their hashes
            writeTileSet(keep.mapValues { (p, sha) -> TileSource(tileSet, sha, "the installed routing.tar") { members[p] } }, null)
        }
        commit(staged = emptyMap(), finalStates = remaining.keys.sorted(), removeTileSet = keep.isEmpty())
        return UninstallReport(state, drop.size, mineTiles.size - drop.size, if (tileSet.exists()) tileSet.length() else 0, clock() - t0)
    }

    /** The new tile set is written beside the old one before the swap: it needs its whole size free. */
    private fun checkFreeSpace(tileBytes: Collection<Long>) {
        deviceDir.mkdirs()
        val need = TileTar.sizeOf(tileBytes) + STAGING_RESERVE
        val free = freeSpace(deviceDir)
        if (free < need) {
            throw PackException("not enough free space: the new routing tile set needs ${mib(need)} MB, ${mib(free)} MB are free", PackException.Kind.NO_SPACE)
        }
    }

    /**
     * Stages the manifests of [staged] (state -> manifest text) and the device files, writes
     * pending/COMMIT (the commit point) and applies the change. routing.tar.new must be complete,
     * unless [removeTileSet]. [finalStates] are the states installed after the change.
     */
    private fun commit(staged: Map<String, String>, finalStates: List<String>, removeTileSet: Boolean) {
        faultHook("tileset-written")
        pendingDir.deleteRecursively()
        val stagedPacks = File(pendingDir, "packs").apply { mkdirs() }
        for ((st, text) in staged) writeSynced(File(stagedPacks, "$st.json"), text)
        if (!removeTileSet) {
            writeSynced(File(pendingDir, deviceFile.name), deviceJson())
            writeSynced(File(pendingDir, engineConfigFile.name), EngineConfig.build(tileSet.absolutePath).toString())
        }
        val marker = File(pendingDir, "COMMIT.tmp")
        writeSynced(marker, buildJsonObject {
            put("format", "beamline-routing-commit/1")
            put("states", JsonArray(finalStates.map { JsonPrimitive(it) }))
            put("tileset", if (removeTileSet) "remove" else "replace")
        }.toString())
        syncDir(stagedPacks)
        syncDir(pendingDir)
        move(marker, commitFile)
        syncDir(pendingDir)
        faultHook("committed")
        applyCommitted()
    }

    /** Applies a committed change; safe to run again after a kill at any point inside it. */
    private fun applyCommitted() {
        val c = Json.parseToJsonElement(commitFile.readText(Charsets.UTF_8)).jsonObject
        val states = c["states"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
        if (c["tileset"]!!.jsonPrimitive.content == "remove") {
            tileSet.delete()
        } else if (newTileSet.exists()) {
            // Atomic on Android (rename(2)) and on Windows (MoveFileEx). An engine that still maps
            // the old file keeps routing on it until it is reopened.
            move(newTileSet, tileSet)
        }
        syncDir(deviceDir)
        faultHook("tileset-swapped")
        packsDir.mkdirs()
        File(pendingDir, "packs").listFiles()?.forEach { move(it, File(packsDir, it.name)) }
        packsDir.listFiles { f -> f.name.endsWith(".json") && f.name.removeSuffix(".json") !in states }?.forEach { it.delete() }
        syncDir(packsDir)
        if (states.isEmpty()) {
            deviceFile.delete()
            engineConfigFile.delete()
        } else {
            for (f in listOf(deviceFile, engineConfigFile)) File(pendingDir, f.name).takeIf { it.isFile }?.let { move(it, f) }
        }
        syncDir(deviceDir)
        commitFile.delete()
        pendingDir.deleteRecursively()
    }

    private class TileSource(val tar: File, val sha256: String, val origin: String, val member: () -> TileTar.Member?)

    private fun checkCompatible(new: Map<String, RoutingPackManifest>, remaining: Map<String, RoutingPackManifest>) {
        val builds = new.values.map { it.graphBuild }.toSet()
        if (builds.size != 1) {
            throw PackException("the packs being installed come from different graph builds: ${builds.sorted()}", PackException.Kind.GRAPH_BUILD)
        }
        val build = builds.single()
        for ((st, m) in new) {
            if (m.valhallaVersion != engineVersion) {
                throw PackException("pack $st was built for Valhalla ${m.valhallaVersion} but this engine is Valhalla $engineVersion",
                    PackException.Kind.ENGINE_VERSION)
            }
        }
        for ((st, m) in remaining) {
            if (m.graphBuild != build) {
                val names = new.keys.sorted().joinToString(" ")
                throw PackException("pack $names is from graph build $build but installed pack $st is from ${m.graphBuild}; " +
                    "update all packs to one build in one step ($names $st) or remove $st first", PackException.Kind.GRAPH_BUILD)
            }
        }
        val seen = HashMap<String, Pair<String, String>>()
        for ((st, m) in remaining + new) for (t in m.tiles) {
            val prev = seen[t.path]
            if (prev != null && prev.first != t.sha256) {
                throw PackException("shared tile ${t.path} differs between ${prev.second} and $st although both claim build $build",
                    PackException.Kind.DAMAGED)
            }
            seen.putIfAbsent(t.path, t.sha256 to st)
        }
    }

    /** Writes routing.tar.new from [wanted], checking every tile; [commit] swaps it in. */
    private fun writeTileSet(wanted: Map<String, TileSource>, version: String?) {
        val tmp = newTileSet
        val handles = HashMap<File, RandomAccessFile>()
        try {
            TileTar.write(tmp, wanted.keys.sorted(), clock() / 1000) { name ->
                val src = wanted.getValue(name)
                val m = src.member() ?: throw PackException("$name is listed in the manifest but missing from ${src.origin}", PackException.Kind.DAMAGED)
                val f = handles.getOrPut(src.tar) { RandomAccessFile(src.tar, "r") }
                val data = ByteArray(m.size.toInt())
                f.seek(m.offset)
                f.readFully(data)
                if (sha256(data) != src.sha256) throw PackException("$name in ${src.origin} does not match its manifest sha256", PackException.Kind.DAMAGED)
                if (version != null) {
                    val v = TileTar.tileVersion(data)
                    if (v != version) throw PackException("$name in ${src.origin} was built by Valhalla $v, expected $version", PackException.Kind.ENGINE_VERSION)
                }
                data
            }
        } catch (e: Throwable) {
            handles.values.forEach { it.close() }
            tmp.delete() // leave the installed tile set untouched
            throw e
        }
        handles.values.forEach { it.close() }
    }

    private fun deviceJson() = buildJsonObject {
        put("format", "beamline-routing-device/1")
        put("layout", "tar")
        put("engine_version", engineVersion)
    }.toString()

    companion object {
        /** Room kept free beside the new tile set for the staged manifests and device files. */
        private const val STAGING_RESERVE = 4L shl 20

        private fun mib(b: Long) = b / (1L shl 20)

        private fun move(from: File, to: File) {
            Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }

        private fun writeSynced(f: File, text: String) {
            f.parentFile?.mkdirs()
            FileOutputStream(f).use { out ->
                out.write(text.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
        }

        /** Makes renames in [dir] durable where the OS allows it (Linux, Android); a no-op on Windows. */
        private fun syncDir(dir: File) {
            try {
                FileChannel.open(dir.toPath(), StandardOpenOption.READ).use { it.force(true) }
            } catch (_: Exception) {
            }
        }

        fun sha256(data: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(data).toHex()

        fun sha256(file: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            file.inputStream().buffered(1 shl 20).use { s ->
                val buf = ByteArray(1 shl 20)
                while (true) {
                    val n = s.read(buf)
                    if (n < 0) break
                    md.update(buf, 0, n)
                }
            }
            return md.digest().toHex()
        }

        private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

        /** The engine config of an installed device, as JSON (for valhalla-mobile's Valhalla(configPath)). */
        fun engineConfig(deviceDir: File): JsonObject = Json.parseToJsonElement(File(deviceDir, "valhalla.json").readText()).jsonObject
    }
}
