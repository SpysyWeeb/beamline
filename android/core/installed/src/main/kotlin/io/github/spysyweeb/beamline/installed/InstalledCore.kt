package io.github.spysyweeb.beamline.installed

import io.github.spysyweeb.beamline.api.data.ArchiveInfo
import io.github.spysyweeb.beamline.api.data.InstallStatus
import io.github.spysyweeb.beamline.api.data.InstallStep
import io.github.spysyweeb.beamline.api.data.InstalledSet
import io.github.spysyweeb.beamline.api.data.PackArchive
import io.github.spysyweeb.beamline.api.data.PackFolder
import io.github.spysyweeb.beamline.api.data.PackInfo
import io.github.spysyweeb.beamline.api.data.PackIssue
import io.github.spysyweeb.beamline.api.data.RoutingSet
import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.packs.InstallRecord
import io.github.spysyweeb.beamline.packs.InstallRules
import io.github.spysyweeb.beamline.packs.InstalledPack
import io.github.spysyweeb.beamline.packs.PackManifest
import io.github.spysyweeb.beamline.packs.PackScanner
import io.github.spysyweeb.beamline.packs.PartFile
import io.github.spysyweeb.beamline.packs.Readers
import io.github.spysyweeb.beamline.packs.TarFormatException
import io.github.spysyweeb.beamline.packs.TarReader
import io.github.spysyweeb.beamline.routing.packs.PackException
import io.github.spysyweeb.beamline.routing.packs.PackSource
import io.github.spysyweeb.beamline.routing.packs.RoutingPackManager
import io.github.spysyweeb.beamline.routing.packs.RoutingPackManifest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit

/** An install that failed with a message for the screen; the installed packs are as they were. */
internal class InstallFailure(message: String) : Exception(message)

/** Thrown by a test's fault hook to stop a change where a killed process would stop (tests only). */
internal class SimulatedCrash(val point: String) : Error("simulated crash at $point")

/**
 * The work behind [InstalledLibrary] (docs/architecture.md 2.2, 5.1 P; docs/architecture-downloads.md
 * 6.6): one install and removal flow for the four parts of a format-3 pack, the merged routing tile
 * set, the generations, the hold during guidance and the recovery of a change a killed process left
 * half done.
 *
 * Every file change runs on [io] under [files] (one at a time; [RoutingPackManager] is not
 * thread-safe), so a refresh waits for a running install. The hold and the busy flag are guarded by
 * [lock], so "refused while held" and "holdChanges is null while an install runs" are exact.
 *
 * Crash safety. An install stages every part in `.staging-<id>-<n>` and verifies it (SHA-256), then
 * writes a preliminary install record (routing_merged false), merges the routing tiles (the
 * routing commit), deletes the staged routing.tar, writes the final record and renames the folder
 * into place. [cleanUp] (every refresh and before every change) rolls a staged folder forward when
 * its routing commit happened (its routing.json is the routing set's manifest of that state), else
 * deletes it; it restores a pack moved aside for an update when nothing replaced it; it finishes a
 * removal (`.removing-*`: routing uninstalled, folder deleted); and it uninstalls routing states no
 * installed pack (or staged install being rolled forward) owns. So the merged set's states are
 * always the installed packs whose record says routing_merged (docs/architecture.md 2.11), once
 * every repair is done. A roll forward that fails keeps its staging folder (it owns its routing
 * state) and is tried again by the next [cleanUp]; what could not be repaired is listed in
 * [InstalledSet.issues].
 *
 * While changes are held (a trip, 2.2 rule 4) nothing changes under the trip: a [refresh] changes no
 * file and publishes no new generation (only the very first scan is published); a repair it found
 * waiting runs, with a rescan, as soon as the last hold is released. A repair takes the busy flag
 * like any change, so [holdChanges] is null while one runs.
 */
internal class InstalledCore(
    private val packsDir: File,
    routingDir: File,
    private val readers: Readers,
    private val rules: InstallRules,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val freeBytes: (File) -> Long,
    private val now: () -> Instant = Instant::now,
    /** Tests: the [faultHook] in place before the first scan starts. */
    initialFaultHook: (String) -> Unit = {},
) {
    val routing = RoutingPackManager(routingDir, VALHALLA_VERSION, verifyTarHash = false, freeSpace = freeBytes)
    private val scanner = PackScanner(packsDir, readers)
    private val files = Mutex()
    private val lock = Any()
    private var busy = false
    private val holds = LinkedHashMap<Any, String>()
    private var job: Job? = null

    /** A rescan or repair found while changes were held waits for the last hold's release. Under [lock]. */
    private var afterHold = false

    /** What the last [cleanUp] could not repair: folder (or "routing/<state>") -> reason. Under [files]. */
    private val repairIssues = LinkedHashMap<String, String>()

    val installed = MutableStateFlow(InstalledSet())
    val install = MutableStateFlow<InstallStatus>(InstallStatus.Idle)

    /**
     * Tests: called at "staged", "routing-committed", "before-rename", "removal-aside" and
     * "roll-forward" (in [cleanUp], before a staged install is renamed into place).
     */
    @Volatile
    var faultHook: (String) -> Unit = initialFaultHook

    /**
     * The first scan (and the recovery of a change a killed process left half done) starts at
     * once, so `installed.scanned` turns true without the caller's first refresh. [refresh] waits
     * for it, so a refresh always ends with the newest view published.
     */
    private val firstScan: Job = scope.launch { runCatching { scanAndRecover() } }

    // ------------------------------------------------------------------ hold and busy

    fun holdChanges(reason: String): AutoCloseable? {
        val token = Any()
        synchronized(lock) {
            if (busy) return null
            holds[token] = reason
            publishHold()
        }
        return AutoCloseable {
            var rescan = false
            synchronized(lock) {
                if (holds.remove(token) != null) {
                    publishHold()
                    if (holds.isEmpty() && afterHold) {
                        afterHold = false
                        rescan = true
                    }
                }
            }
            // what a refresh found during the trip: repaired and published now
            if (rescan) scope.launch { runCatching { scanAndRecover() } }
        }
    }

    /** The hold is not a change of the installed data: the generation stays. Call under [lock]. */
    private fun publishHold() {
        val by = holds.values.firstOrNull()
        val cur = installed.value
        if (cur.changesHeldBy != by) installed.value = cur.copy(changesHeldBy = by)
    }

    private fun heldBy(): String? = synchronized(lock) { holds.values.firstOrNull() }

    /** Takes the busy flag unless changes are held or one runs; false when it could not. */
    private fun takeBusy(): Boolean = synchronized(lock) {
        if (busy || holds.isNotEmpty()) false else {
            busy = true
            true
        }
    }

    private fun dropBusy() = synchronized(lock) { busy = false }

    // ------------------------------------------------------------------ refresh and the view

    suspend fun refresh() {
        firstScan.join()
        scanAndRecover()
    }

    private suspend fun scanAndRecover() {
        files.withLock {
            withContext(io) {
                if (needsRepair() && takeBusyForRepair()) {
                    try {
                        cleanUp()
                    } finally {
                        dropBusy()
                    }
                }
                publishScan()
            }
        }
    }

    /**
     * The busy flag for a repair found by a rescan. Refused while changes are held (the repair
     * waits for the release, class comment) or while a change is about to run (it repairs first).
     */
    private fun takeBusyForRepair(): Boolean = synchronized(lock) {
        when {
            holds.isNotEmpty() -> {
                afterHold = true
                false
            }
            busy -> false
            else -> {
                busy = true
                true
            }
        }
    }

    /** Whether [cleanUp] has anything to do (read only). Call under [files]. */
    private fun needsRepair(): Boolean {
        if (routing.hasPendingChange()) return true
        val hidden = packsDir.listFiles { f -> f.isDirectory && (f.name.startsWith(STAGING) || f.name.startsWith(OLD) || f.name.startsWith(REMOVING)) }
        if (!hidden.isNullOrEmpty()) return true
        return runCatching { orphanStates().isNotEmpty() }.getOrDefault(false)
    }

    /**
     * Publishes the installed packs as found on disk: after a change ([changed]: an install,
     * update or removal, which replaced files even when the view looks the same) always a new
     * generation; after a rescan only when something differs (or for the first scan), and not
     * while changes are held (the view stays as the trip found it until the release). Call under
     * [files].
     */
    private fun publishScan(changed: Boolean = false) {
        val (packs, routingSet, issues) = view()
        synchronized(lock) {
            val cur = installed.value
            val differs = changed || !cur.scanned || cur.packs != packs || cur.routing != routingSet || cur.issues != issues
            if (!differs) return
            if (!changed && cur.scanned && holds.isNotEmpty()) {
                afterHold = true
                return
            }
            installed.value = InstalledSet(cur.generation + 1, true, packs, routingSet, issues, holds.values.firstOrNull())
        }
    }

    private data class View(val packs: List<PackInfo>, val routing: RoutingSet?, val issues: List<PackIssue>)

    private fun view(): View {
        packsDir.mkdirs()
        val scan = scanner.scan()
        val issues = scan.problems.map { PackIssue(it.folder, it.reason) }.toMutableList()
        issues += repairIssues.map { (folder, why) -> PackIssue(folder, why) }
        // A routing folder that cannot be read at all must not stop the scan: the packs are listed
        // without a routing set, and the issue says why.
        val routingStates = try {
            routing.installed()
        } catch (e: Exception) {
            issues += PackIssue(ROUTING_ISSUE, "the route planner's files cannot be read (${e::class.simpleName})")
            emptyMap()
        }
        val packs = mutableListOf<PackInfo>()
        val inSet = mutableListOf<Pair<InstalledPack, RoutingPackManifest>>()
        for (p in scan.packs) {
            val rm = routingIndexOf(p.dir, p.manifest)
            val merged = p.install?.routingMerged == true && rm != null && routingStates[rm.state]?.text == rm.text
            if (p.install?.routingMerged == true && !merged) {
                issues += PackIssue(p.dir.name, "its routing tiles are not in the routing set: install the state again")
            }
            if (merged && rm != null) inSet += p to rm
            packs += info(p, merged)
        }
        val routingSet = if (routingStates.isEmpty()) null else RoutingSet(
            engineConfigFile = routing.engineConfigFile.absolutePath,
            graphBuild = routingStates.values.first().graphBuild,
            valhallaVersion = routingStates.values.first().valhallaVersion ?: VALHALLA_VERSION,
            states = inSet.map { it.first.manifest.state }.sorted(),
            level2Tiles = routingStates.values.flatMap { m -> m.tiles.mapNotNull { level2Id(it.path) } }.toSet(),
        )
        return View(packs.sortedBy { it.packId }, routingSet, issues.sortedBy { it.folder })
    }

    private fun info(p: InstalledPack, merged: Boolean): PackInfo {
        val m = p.manifest
        val absent = p.install?.absentParts(m).orEmpty()
        val skipped = p.install?.skippedParts.orEmpty().filter { it in PackManifest.OPTIONAL_PARTS }
        val search = m.search
        val searchProblem = when {
            search == null -> null
            else -> readers.partProblem(m, PackManifest.SEARCH)?.let { "search part is $it" } ?: searchKeyProblem(m)
        }
        val addresses = m.addresses?.takeIf { PackManifest.ADDRESSES !in absent && readers.partProblem(m, PackManifest.ADDRESSES) == null }
        return PackInfo(
            packId = m.packId,
            name = m.name,
            state = m.state,
            osmSnapshot = m.osmSnapshot,
            bounds = Bounds.of(m.bounds),
            attribution = m.creditFor(skipped),
            folder = p.dir.absolutePath,
            mapFile = p.mapFile.absolutePath,
            mapMinZoom = m.map.minZoom ?: 0,
            mapMaxZoom = m.map.maxZoom ?: 14,
            searchFile = if (search != null && searchProblem == null) p.file(search.file).absolutePath else null,
            addressesFile = addresses?.let { p.file(it.file).absolutePath },
            addressesSkipped = PackManifest.ADDRESSES in skipped,
            searchProblem = searchProblem,
            searchRankingSha256 = search?.rankingSha256,
            inRoutingSet = merged,
            verified = p.install?.verified == "sha256",
            bytesOnDisk = onPhoneBytes(p, merged, skipped),
            packVersion = m.packVersion,
        )
    }

    /**
     * The state's whole size on the phone, the one number the States row, its sheet and About show
     * (docs/architecture-downloads.md 1.1 and 1.2: "On this phone · ... · 1.1 GB" in both): the pack
     * folder, plus its routing part, whose tiles live in the merged set once merged (the folder
     * alone left out about half: 688 MB of Pennsylvania's 1.3 GB); a house-number part being removed
     * no longer counts (removeOptionalParts publishes the change before it deletes the file).
     */
    private fun onPhoneBytes(p: InstalledPack, merged: Boolean, skipped: Collection<String>): Long {
        val m = p.manifest
        var bytes = p.bytesOnDisk
        val routing = m.routing
        if (merged && routing != null && !p.file(routing.file).isFile) bytes += routing.bytes
        val addresses = m.addresses
        if (addresses != null && PackManifest.ADDRESSES in skipped) {
            val f = p.file(addresses.file)
            if (f.isFile) bytes -= f.length()
        }
        return bytes.coerceAtLeast(0)
    }

    /**
     * The app's street-key fingerprint ([Readers.addrKeyFingerprint], computed by the search port)
     * against the one the search part was built with (docs/architecture.md 2.11, decision 6.11).
     */
    private fun searchKeyProblem(m: PackManifest): String? {
        val want = readers.addrKeyFingerprint ?: return null
        val have = m.search?.addrKeyFingerprint
        return if (have == want) null else "built with another address normaliser"
    }

    private fun routingIndexOf(dir: File, m: PackManifest): RoutingPackManifest? {
        val part = m.routingIndex ?: return null
        val f = File(dir, part.file)
        return if (f.isFile) runCatching { RoutingPackManifest.parse(f.readText(Charsets.UTF_8)) }.getOrNull() else null
    }

    // ------------------------------------------------------------------ recovery

    /**
     * Finishes or undoes what a killed process left half done (class comment). Call under [files]
     * (a running change holds it, so its own folders are never taken for leftovers).
     */
    private fun cleanUp() {
        packsDir.mkdirs()
        repairIssues.clear()
        try {
            routing.recover()
        } catch (e: Exception) {
            repairIssues[ROUTING_ISSUE] = "a change of the route planner's files could not be finished (${e::class.simpleName})"
        }
        val hidden = packsDir.listFiles { f -> f.isDirectory && f.name.startsWith(".") }.orEmpty().sortedBy { it.name }
        for (f in hidden.filter { it.name.startsWith(STAGING) }) {
            val staged = committedStage(f)
            if (staged == null) {
                // cut short before its routing commit: undone (moved download parts are lost, 6.6 item 2)
                f.deleteRecursively()
                continue
            }
            try {
                rollForward(f, staged.first, staged.second)
            } catch (e: Exception) {
                // Kept: its tiles are in the merged set and it owns that state; tried again next time.
                repairIssues[f.name] = "the install of ${staged.first.name} could not be finished (${failureWords(e)}); Beamline tries again later"
            }
        }
        for (f in packsDir.listFiles { x -> x.isDirectory && x.name.startsWith(OLD) }.orEmpty()) {
            val folder = f.name.removePrefix(OLD).substringBeforeLast('-')
            val target = File(packsDir, folder)
            if (!target.exists() && PackScanner.isPlainFileName(folder)) f.renameTo(target) else f.deleteRecursively()
        }
        for (f in packsDir.listFiles { x -> x.isDirectory && x.name.startsWith(REMOVING) }.orEmpty()) f.deleteRecursively()
        uninstallOrphans()
    }

    /**
     * The manifest and record of a staging folder whose routing commit happened (its routing.json
     * is the merged set's manifest of its state), or null when it is not complete or not committed.
     */
    private fun committedStage(f: File): Pair<PackManifest, InstallRecord>? {
        val m = runCatching { PackManifest.parse(File(f, PackManifest.FILE_NAME).readText(Charsets.UTF_8)) }.getOrNull() ?: return null
        val record = readRecord(f) ?: return null
        val rm = routingIndexOf(f, m) ?: return null
        val inSet = runCatching { routing.installed()[rm.state] }.getOrNull() ?: return null
        return if (inSet.text == rm.text) m to record else null
    }

    /**
     * The routing states no installed pack owns: neither an installed pack whose record says
     * routing_merged nor a staged install kept for its roll forward; and every state whose manifest
     * cannot be read. Call under [files].
     */
    private fun orphanStates(): Set<String> {
        val owned = scanner.scan().packs.filter { it.install?.routingMerged == true }
            .mapNotNull { p -> routingIndexOf(p.dir, p.manifest)?.state }.toMutableSet()
        packsDir.listFiles { f -> f.isDirectory && f.name.startsWith(STAGING) }.orEmpty().forEach { f ->
            committedStage(f)?.let { (m, _) -> routingIndexOf(f, m)?.state?.let(owned::add) }
        }
        return (routing.installed().keys - owned) + routing.unreadable()
    }

    /**
     * Routing states no installed pack owns (a removal or a new install cut short, a pack folder
     * that no longer passes its checks, a damaged state manifest) leave the merged set, so its states
     * are exactly the installed packs whose record says routing_merged (docs/architecture.md 2.11).
     * Never throws: a state that cannot be uninstalled (not enough free space to rewrite the set)
     * stays, is listed in [repairIssues] and is tried again by the next [cleanUp]. Returns the
     * states left. Call under [files].
     */
    private fun uninstallOrphans(): List<String> {
        val left = mutableListOf<String>()
        val orphans = try {
            orphanStates()
        } catch (e: Exception) {
            return left
        }
        for (state in orphans.sorted()) {
            try {
                routing.uninstall(state)
                repairIssues.remove("$ROUTING_ISSUE/$state")
            } catch (e: Exception) {
                left += state
                repairIssues["$ROUTING_ISSUE/$state"] = when ((e as? PackException)?.kind) {
                    PackException.Kind.NO_SPACE ->
                        "the roads of ${state.uppercase()} stay in the route planner until there is enough free space to remove them"
                    else -> "the roads of ${state.uppercase()} could not be removed from the route planner (${e::class.simpleName})"
                }
            }
        }
        return left
    }

    /** Completes an install whose routing commit happened: staged tar gone, final record, renamed in. */
    private fun rollForward(staging: File, m: PackManifest, record: InstallRecord) {
        m.routing?.let { File(staging, it.file).delete() }
        if (!record.routingMerged) writeRecord(staging, record.copy(routingMerged = true))
        faultHook("roll-forward")
        moveIntoPlace(staging, m)
    }

    // ------------------------------------------------------------------ inspect

    fun neededBytes(m: PackManifest, skipped: Collection<String>, notCopied: Collection<String> = emptyList()): Long {
        val copied = m.allParts.filterKeys { it !in skipped && it !in notCopied }.values.sumOf { it.bytes }
        val manifest = if (notCopied.isEmpty()) MANIFEST_RESERVE else 0L
        val mergedSet = (routing.tileSet.takeIf { it.isFile }?.length() ?: 0L) + (m.routing?.bytes ?: 0L)
        return copied + manifest + mergedSet + ROUTING_RESERVE + rules.freeSpaceMarginBytes
    }

    suspend fun inspect(archive: PackArchive): ArchiveInfo = withContext(io) {
        val text = try {
            archive.open().use { readArchiveManifest(it, archive.label) }
        } catch (e: InstallFailure) {
            return@withContext refused(archive.label, e.message ?: "cannot read the pack")
        }
        inspectText(text, archive.label)
    }

    suspend fun inspectFolder(folder: PackFolder): ArchiveInfo = withContext(io) {
        val f = File(folder.dir, PackManifest.FILE_NAME)
        if (!f.isFile) return@withContext refused(folder.label, "${folder.label} has no ${PackManifest.FILE_NAME}")
        inspectText(f.readText(Charsets.UTF_8), folder.label, moved = true)
    }

    private fun inspectText(text: String, label: String, moved: Boolean = false): ArchiveInfo {
        val m = try {
            PackManifest.parse(text)
        } catch (e: Exception) {
            return refused(label, manifestMessage(e))
        }
        val problems = listOfNotNull(
            PackScanner.manifestProblem(m, readers),
            stateTaken(m, installed.value.packs.map { it.packId to it.state }),
            heldBy(),
        )
        val notCopied = if (moved) m.allParts.keys else emptyList()
        return ArchiveInfo(
            packId = m.packId, name = m.name, state = m.state, osmSnapshot = m.osmSnapshot,
            neededBytes = neededBytes(m, emptyList(), notCopied),
            addressesBytes = m.addresses?.bytes,
            replacesInstalled = installed.value.packs.any { it.packId == m.packId },
            freeBytes = freeBytes(packsDir.apply { mkdirs() }),
            problems = problems,
        )
    }

    private fun refused(label: String, why: String) = ArchiveInfo(
        packId = "", name = label, state = "", osmSnapshot = "", neededBytes = 0, addressesBytes = null,
        replacesInstalled = false, freeBytes = freeBytes(packsDir.apply { mkdirs() }), problems = listOf(why),
    )

    // ------------------------------------------------------------------ the install job

    /**
     * Runs [work] as the one change (busy until it ends); progress and the result in [install].
     * False when changes are held or one runs.
     */
    private fun start(label: String, work: (cancelled: () -> Boolean) -> InstallStatus): Boolean {
        if (!takeBusy()) return false
        install.value = InstallStatus.Running(label, InstallStep.CHECKING, 0, 0, null)
        job = scope.launch {
            val me = coroutineContext[Job]!!
            var finished: InstallStatus? = null
            val result = try {
                files.withLock {
                    withContext(io) {
                        work { !me.isActive }.also { finished = it }
                    }
                }
            } catch (e: InstallCancelled) {
                InstallStatus.Cancelled(label)
            } catch (e: InstallFailure) {
                InstallStatus.Failed(label, e.message ?: "install failed")
            } catch (e: CancellationException) {
                finished ?: InstallStatus.Cancelled(label)
            } catch (e: SimulatedCrash) {
                // a test's kill: the process "dies" here, nothing is cleaned up
                InstallStatus.Failed(label, e.message ?: "crash")
            } catch (e: Exception) {
                // Never the exception's own text (engineer wording, maybe a path): a fixed sentence.
                InstallStatus.Failed(label, unexpectedFailure(label, e))
            } catch (e: Error) {
                // an error of the runtime (out of memory, ...): the change is over, the flag must go
                dropBusy()
                install.value = InstallStatus.Failed(label, e::class.simpleName ?: "install failed")
                throw e
            }
            // A failed change may still have changed the routing set (a commit before the failure):
            // published while the change still holds the busy flag, so no hold can see it.
            if (result !is InstallStatus.Done) withContext(NonCancellable) { runCatching { files.withLock { withContext(io) { publishScan() } } } }
            dropBusy()
            install.value = result
        }
        return true
    }

    /** The words for an exception no step turned into an [InstallFailure]. */
    private fun unexpectedFailure(label: String, e: Exception): String = when (e) {
        is SecurityException -> "Beamline may no longer read $label. Choose the file again."
        is IOException -> "Reading $label or writing into the app's storage failed. Try again."
        else -> "The install stopped because of an unexpected problem (${e::class.simpleName})."
    }

    /** Plain words for why a repair failed (shown in [InstalledSet.issues]). */
    private fun failureWords(e: Exception): String = when (e) {
        is InstallFailure -> e.message ?: "it failed"
        is IOException -> "the app's storage could not be written"
        else -> e::class.simpleName ?: "it failed"
    }

    private class InstallCancelled : Exception("cancelled")

    fun cancelInstall() {
        job?.cancel()
    }

    fun dismissInstallResult() {
        if (install.value !is InstallStatus.Running) install.value = InstallStatus.Idle
    }

    fun startInstall(archive: PackArchive, includeAddresses: Boolean): Boolean = start(archive.label) { cancelled ->
        cleanUp()
        archive.open().use { raw ->
            val tar = TarReader(raw.buffered(BUFFER))
            val text = readFirstEntry(tar, archive.label)
            val m = checkManifest(text, archive.label)
            val skipped = skippedFor(m, includeAddresses)
            checkSpace(m, skipped)
            val staging = newStaging(m, text)
            val stage = Stage(staging, archive.label, m, skipped)
            try {
                val byFile = m.allParts.entries.associate { (name, part) -> part.file to (name to part) }
                val done = mutableSetOf<String>()
                while (true) {
                    val e = try {
                        tar.next()
                    } catch (x: TarFormatException) {
                        throw InstallFailure("${archive.label} is not a pack file: it is not a tar archive")
                    } ?: break
                    val (name, part) = byFile[e.name] ?: continue
                    if (!e.isFile || e.name in done || name in skipped) continue
                    if (e.size != part.bytes) throw InstallFailure("${e.name} is ${e.size} bytes in the archive, the manifest says ${part.bytes}")
                    stage.copy(tar.entryStream, part, cancelled)
                    done += e.name
                }
                val missing = m.allParts.filterKeys { it !in skipped }.values.map { it.file } - done
                if (missing.isNotEmpty()) throw InstallFailure("${archive.label} lacks ${missing.sorted().joinToString()}")
                finishStaged(stage, InstallRecord.SOURCE_ARCHIVE, cancelled)
            } catch (e: Exception) {
                stage.abandon()
                throw e
            }
        }
    }

    fun startInstallFromFolder(folder: PackFolder, includeAddresses: Boolean): Boolean = start(folder.label) { cancelled ->
        cleanUp()
        val dir = File(folder.dir)
        val mf = File(dir, PackManifest.FILE_NAME)
        if (!mf.isFile) throw InstallFailure("${folder.label} has no ${PackManifest.FILE_NAME}")
        val text = mf.readText(Charsets.UTF_8)
        val m = checkManifest(text, folder.label)
        val skipped = skippedFor(m, includeAddresses)
        checkSpace(m, skipped, notCopied = m.allParts.keys)
        val staging = newStaging(m, text)
        val stage = Stage(staging, folder.label, m, skipped, from = dir)
        try {
            for ((name, part) in m.allParts) {
                if (name in skipped) continue
                stage.move(part, cancelled)
            }
            finishStaged(stage, InstallRecord.SOURCE_DOWNLOAD, cancelled)
        } catch (e: Exception) {
            stage.abandon()
            throw e
        }
    }

    private fun readFirstEntry(tar: TarReader, label: String): String {
        val first = try {
            tar.next()
        } catch (e: TarFormatException) {
            throw InstallFailure("$label is not a pack file: it is not a tar archive")
        } ?: throw InstallFailure("$label is an empty archive")
        if (!first.isFile || first.name != PackManifest.FILE_NAME) {
            throw InstallFailure("$label: the first entry must be ${PackManifest.FILE_NAME}, found '${first.name}'")
        }
        if (first.size > MAX_MANIFEST) throw InstallFailure("${PackManifest.FILE_NAME} is too large (${first.size} bytes)")
        return tar.entryStream.readBytes().toString(Charsets.UTF_8)
    }

    private fun readArchiveManifest(input: InputStream, label: String): String = try {
        readFirstEntry(TarReader(input.buffered(64 * 1024)), label)
    } catch (e: IOException) {
        throw InstallFailure("reading $label failed: ${e::class.simpleName}")
    }

    private fun manifestMessage(e: Exception): String {
        val msg = e.message?.lineSequence()?.firstOrNull().orEmpty()
        return if (msg.startsWith("manifest_version")) msg else "unreadable ${PackManifest.FILE_NAME}: $msg"
    }

    private fun checkManifest(text: String, label: String): PackManifest {
        val m = try {
            PackManifest.parse(text)
        } catch (e: Exception) {
            throw InstallFailure("$label: ${manifestMessage(e)}")
        }
        PackScanner.manifestProblem(m, readers)?.let { throw InstallFailure("${m.name}: $it") }
        stateTaken(m, scanner.scan().packs.map { it.id to it.manifest.state })?.let { throw InstallFailure(it) }
        return m
    }

    /**
     * One pack per state: the merged routing set holds one routing state per USPS state, so a pack
     * with another pack_id for an installed state would take over that pack's routing tiles.
     */
    private fun stateTaken(m: PackManifest, installedIdsAndStates: List<Pair<String, String>>): String? {
        val other = installedIdsAndStates.firstOrNull { (id, state) -> id != m.packId && state.equals(m.state, ignoreCase = true) } ?: return null
        return "${m.name} is installed already (as ${other.first}). Remove it first."
    }

    private fun skippedFor(m: PackManifest, includeAddresses: Boolean): List<String> =
        if (!includeAddresses && PackManifest.ADDRESSES in m.parts) listOf(PackManifest.ADDRESSES) else emptyList()

    private fun checkSpace(m: PackManifest, skipped: List<String>, notCopied: Collection<String> = emptyList()) {
        val need = neededBytes(m, skipped, notCopied)
        val free = freeBytes(packsDir.apply { mkdirs() })
        if (free < need) throw InstallFailure("${m.name} needs ${mb(need)} MB of free space, the phone has ${mb(free)} MB")
    }

    private fun newStaging(m: PackManifest, manifestText: String): File {
        val dir = File(packsDir, "$STAGING${m.packId}-${System.nanoTime()}")
        if (!dir.mkdirs()) throw InstallFailure("cannot create ${dir.name} in the packs folder")
        writing(PackManifest.FILE_NAME) { File(dir, PackManifest.FILE_NAME).writeText(manifestText, Charsets.UTF_8) }
        return dir
    }

    /** One staging folder being filled: copied (archive) or moved (folder) parts, each hashed once. */
    private inner class Stage(
        val dir: File,
        val label: String,
        val m: PackManifest,
        val skipped: List<String>,
        /** The download folder parts are moved from; null for an archive. */
        val from: File? = null,
    ) {
        private val total = m.bytesWithout(skipped)
        private var doneBytes = 0L
        private val moved = mutableListOf<String>()
        private var lastReport = 0L

        /** True once the routing merge committed: from then on the install rolls forward. */
        var committed = false

        fun copy(input: InputStream, part: PartFile, cancelled: () -> Boolean) {
            val dest = File(dir, part.file)
            val digest = MessageDigest.getInstance("SHA-256")
            val buf = ByteArray(BUFFER)
            var n = 0L
            val out = writing(part.file) { FileOutputStream(dest) }
            try {
                while (true) {
                    if (cancelled()) throw InstallCancelled()
                    val r = try {
                        input.read(buf)
                    } catch (e: IOException) {
                        throw InstallFailure("reading $label failed: ${e::class.simpleName}")
                    }
                    if (r < 0) break
                    if (n + r > part.bytes) throw InstallFailure("${part.file} is longer than the manifest says (${part.bytes} bytes)")
                    writing(part.file) { out.write(buf, 0, r) }
                    digest.update(buf, 0, r)
                    n += r
                    progress(InstallStep.COPYING, n, part.file)
                }
                writing(part.file) { out.fd.sync() }
            } finally {
                runCatching { out.close() }
            }
            check(part, n, digest)
            doneBytes += n
        }

        /** Moves (renames) the part file from the download folder into staging, then hashes it there. */
        fun move(part: PartFile, cancelled: () -> Boolean) {
            if (cancelled()) throw InstallCancelled()
            val src = File(from!!, part.file)
            if (!src.isFile) throw InstallFailure("$label lacks ${part.file}")
            if (src.length() != part.bytes) throw InstallFailure("${part.file} has ${src.length()} bytes, the manifest says ${part.bytes}")
            val dest = File(dir, part.file)
            try {
                Files.move(src.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE)
            } catch (e: IOException) {
                throw InstallFailure("cannot move ${part.file} into the packs folder (${e::class.simpleName}): the download folder must be on the same storage")
            }
            moved += part.file
            val digest = MessageDigest.getInstance("SHA-256")
            var n = 0L
            dest.inputStream().use { input ->
                val buf = ByteArray(BUFFER)
                while (true) {
                    if (cancelled()) throw InstallCancelled()
                    val r = input.read(buf)
                    if (r < 0) break
                    digest.update(buf, 0, r)
                    n += r
                    progress(InstallStep.COPYING, n, part.file)
                }
            }
            check(part, n, digest)
            doneBytes += n
        }

        private fun check(part: PartFile, n: Long, digest: MessageDigest) {
            if (n != part.bytes) throw InstallFailure("${part.file} ends after $n of ${part.bytes} bytes")
            val hex = digest.digest().joinToString("") { "%02x".format(it) }
            if (!hex.equals(part.sha256, ignoreCase = true)) {
                throw InstallFailure("${part.file} is damaged: its SHA-256 does not match the manifest")
            }
        }

        fun progress(step: InstallStep, inFile: Long, file: String?) {
            val t = System.nanoTime()
            val done = doneBytes + inFile
            if (t - lastReport > 50_000_000 || done == total || step != InstallStep.COPYING) {
                lastReport = t
                install.value = InstallStatus.Running(label, step, done, total, file)
            }
        }

        /**
         * A failure or a cancel before the routing commit: moved files go back into the download
         * folder, the staging folder is deleted. After the commit the folder is kept: [cleanUp]
         * rolls it forward.
         */
        fun abandon() {
            if (committed) return
            if (from != null) for (name in moved) {
                val f = File(dir, name)
                if (f.isFile) runCatching { Files.move(f.toPath(), File(from, name).toPath(), StandardCopyOption.ATOMIC_MOVE) }
            }
            dir.deleteRecursively()
        }
    }

    /** Cheap checks, the routing merge, the final record and the rename into place; one generation. */
    private fun finishStaged(stage: Stage, source: String, cancelled: () -> Boolean): InstallStatus {
        val m = stage.m
        if (cancelled()) throw InstallCancelled()
        stage.progress(InstallStep.FINISHING, 0, null)
        val record = InstallRecord(now().truncatedTo(ChronoUnit.SECONDS).toString(), "sha256", source, stage.skipped, routingMerged = false)
        writeRecord(stage.dir, record)
        when (val r = scanner.read(stage.dir)) {
            is PackScanner.Read.Bad -> throw InstallFailure("${m.name}: ${r.reason}")
            is PackScanner.Read.Ok -> Unit
        }
        val routingPart = m.routing ?: throw InstallFailure("${m.name}: the pack has no routing part")
        val index = routingIndexOf(stage.dir, m) ?: throw InstallFailure("${m.name}: its routing index (routing.json) cannot be read")
        if (index.tarBytes != routingPart.bytes || !index.tarSha256.equals(routingPart.sha256, ignoreCase = true)) {
            throw InstallFailure("${m.name}: its routing index does not describe its routing tiles")
        }
        // The merged set is keyed by the routing state: tiles of another state would replace that
        // state's tiles under its installed pack (assemble.py refuses this; a hand-made file may not).
        if (!index.state.equals(m.state, ignoreCase = true)) {
            throw InstallFailure("${m.name}: its routing tiles are for another state (${index.state.uppercase()}), not ${m.state}. Get the pack again.")
        }
        faultHook("staged")
        if (cancelled()) throw InstallCancelled()
        stage.progress(InstallStep.MERGING_ROUTING, 0, routingPart.file)
        try {
            routing.install(listOf(PackSource(index, File(stage.dir, routingPart.file))))
        } catch (e: PackException) {
            throw InstallFailure(routingRefusal(m, e))
        }
        stage.committed = true
        faultHook("routing-committed")
        File(stage.dir, routingPart.file).delete()
        stage.progress(InstallStep.FINISHING, 0, null)
        writeRecord(stage.dir, record.copy(routingMerged = true))
        faultHook("before-rename")
        moveIntoPlace(stage.dir, m)
        publishScan(changed = true)
        return InstallStatus.Done(m.name, m.osmSnapshot, stage.skipped)
    }

    /**
     * Plain words for a refused routing merge (the routing manager's own text is for engineers).
     * A pack from another graph build cannot sit beside the installed states, and this installer
     * adds one state per change, so moving to a new build is: remove the others, install, add the
     * others again (report 12, section 8).
     */
    private fun routingRefusal(m: PackManifest, e: PackException): String = when (e.kind) {
        PackException.Kind.GRAPH_BUILD -> {
            val others = scanner.scan().packs.filter { it.id != m.packId && it.install?.routingMerged == true }
                .map { it.manifest.name }.distinct().sorted()
            val list = when (others.size) {
                0 -> "the other states"
                1 -> others[0]
                else -> others.dropLast(1).joinToString(", ") + " and " + others.last()
            }
            "${m.name} was built from another version of the road data than $list on this phone, and the two cannot be mixed. " +
                "Remove $list first, then install ${m.name} and add $list again."
        }
        PackException.Kind.ENGINE_VERSION -> "${m.name} was built for another version of Beamline's route planner. Get a pack made for this version of Beamline."
        PackException.Kind.NO_SPACE -> "There is not enough free space to add the roads of ${m.name} to the route planner. Free some space and try again."
        PackException.Kind.DAMAGED -> "The roads of ${m.name} are damaged in this pack. Get the pack again."
        PackException.Kind.OTHER -> "The roads of ${m.name} could not be added to the route planner."
    }

    /** Renames [staging] to packs/<id>; an older copy (and any other folder of that id) goes aside, then away. */
    private fun moveIntoPlace(staging: File, m: PackManifest) {
        val target = File(packsDir, m.packId)
        val others = scanner.scan().packs.filter { it.id == m.packId }.map { it.dir }
        val aside = mutableListOf<Pair<File, File>>()
        try {
            for (old in (others + target).distinct().filter { it.exists() }) {
                val to = File(packsDir, "$OLD${old.name}-${System.nanoTime()}")
                if (!old.renameTo(to)) throw InstallFailure("cannot move the installed ${old.name} aside")
                aside += old to to
            }
            if (!staging.renameTo(target)) throw InstallFailure("cannot move the new pack into place")
        } catch (e: Exception) {
            aside.forEach { (old, to) -> to.renameTo(old) }
            throw e
        }
        aside.forEach { (_, to) -> to.deleteRecursively() }
    }

    // ------------------------------------------------------------------ removal

    suspend fun remove(packId: String): String? = removal { removeLocked(packId) }

    /**
     * Runs a removal as the one change: refused with the hold's text while held, busy while it
     * runs, and its failure returned in plain words (never thrown at the caller).
     */
    private suspend fun removal(work: () -> String?): String? {
        if (!takeBusy()) return heldBy() ?: "Another change of the installed states is running."
        try {
            return files.withLock {
                withContext(io) {
                    try {
                        work()
                    } catch (e: InstallFailure) {
                        e.message ?: "The change failed."
                    } catch (e: IOException) {
                        "The change could not be finished (${e::class.simpleName}); it is finished the next time Beamline starts."
                    } catch (e: Exception) {
                        "The change stopped because of an unexpected problem (${e::class.simpleName})."
                    }
                }
            }
        } finally {
            dropBusy()
        }
    }

    private fun removeLocked(packId: String): String? {
        val pack = scanner.scan().packs.firstOrNull { it.id == packId }
        val dir = pack?.dir ?: File(packsDir, packId).takeIf { it.isDirectory && PackScanner.isPlainFileName(packId) }
            ?: return "No state with the id $packId is installed."
        val name = pack?.manifest?.name ?: packId
        val myState = pack?.let { routingIndexOf(it.dir, it.manifest)?.state }
        val aside = File(packsDir, "$REMOVING${dir.name}-${System.nanoTime()}")
        if (!dir.renameTo(aside)) return "The state could not be removed: its folder is in use."
        faultHook("removal-aside")
        // The removed pack's routing state (and any other no pack owns, e.g. of a folder that
        // failed its checks) leaves the merged set. Rewriting the set needs the remaining set's
        // size free beside it, which a full phone may not have until the folder below is gone.
        val left = uninstallOrphans()
        publishScan(changed = true)
        // after the generation that no longer shows the pack (2.2 rule 5)
        aside.deleteRecursively()
        if (left.isEmpty()) return null
        // The removed folder's space may be enough now. When the set changed, a second generation
        // (rule 2: a generation only after the change is complete on disk).
        val stillLeft = uninstallOrphans()
        if (stillLeft.size < left.size) publishScan(changed = true)
        return if (myState != null && myState in stillLeft) {
            "$name is removed, but its roads stay in the route planner until the phone has more free space. Beamline removes them then."
        } else {
            null
        }
    }

    // ------------------------------------------------------------------ optional parts

    fun startAddOptionalParts(folder: PackFolder): Boolean {
        val dir = File(folder.dir)
        val m = runCatching { PackManifest.parse(File(dir, PackManifest.FILE_NAME).readText(Charsets.UTF_8)) }.getOrNull() ?: return false
        val target = installed.value.packs.firstOrNull { it.packId == m.packId && it.packVersion == m.packVersion } ?: return false
        return start(folder.label) { cancelled -> addOptional(dir, folder.label, m, File(target.folder), cancelled) }
    }

    private fun addOptional(dir: File, label: String, m: PackManifest, packDir: File, cancelled: () -> Boolean): InstallStatus {
        val installedManifest = PackManifest.parse(File(packDir, PackManifest.FILE_NAME).readText(Charsets.UTF_8))
        if (installedManifest.packVersion != m.packVersion || installedManifest.packId != m.packId) {
            throw InstallFailure("$label is another build of ${m.name} than the one installed")
        }
        val record = readRecord(packDir) ?: throw InstallFailure("${m.name} was not installed by Beamline; install it again")
        val adding = PackManifest.OPTIONAL_PARTS.filter {
            it in record.skippedParts && it in installedManifest.parts && it in m.parts && File(dir, m.allParts.getValue(it).file).isFile
        }
        if (adding.isEmpty()) throw InstallFailure("$label holds no part that ${m.name} is missing")
        // the parts are moved, not copied: only the margin must stay free
        val need = rules.freeSpaceMarginBytes
        val free = freeBytes(packsDir)
        if (free < need) throw InstallFailure("${m.name} needs ${mb(need)} MB of free space, the phone has ${mb(free)} MB")
        val moved = mutableListOf<String>()
        try {
            for (name in adding) {
                val part = m.allParts.getValue(name)
                if (part.file != installedManifest.allParts.getValue(name).file) throw InstallFailure("$label names another file for $name")
                if (cancelled()) throw InstallCancelled()
                val src = File(dir, part.file)
                if (src.length() != part.bytes) throw InstallFailure("${part.file} has ${src.length()} bytes, the manifest says ${part.bytes}")
                val dest = File(packDir, part.file)
                try {
                    Files.move(src.toPath(), dest.toPath(), StandardCopyOption.ATOMIC_MOVE)
                } catch (e: IOException) {
                    throw InstallFailure("cannot move ${part.file} into the packs folder (${e::class.simpleName}): the download folder must be on the same storage")
                }
                moved += part.file
                install.value = InstallStatus.Running(label, InstallStep.COPYING, 0, part.bytes, part.file)
                val (n, hex) = sha256(dest, cancelled)
                if (n != part.bytes || !hex.equals(part.sha256, ignoreCase = true)) {
                    throw InstallFailure("${part.file} is damaged: its SHA-256 does not match the manifest")
                }
            }
            install.value = InstallStatus.Running(label, InstallStep.FINISHING, 0, 0, null)
            writeRecord(packDir, record.copy(skippedParts = record.skippedParts - adding.toSet()))
        } catch (e: Exception) {
            for (f in moved) runCatching { Files.move(File(packDir, f).toPath(), File(dir, f).toPath(), StandardCopyOption.ATOMIC_MOVE) }
            throw e
        }
        publishScan(changed = true)
        return InstallStatus.Done(m.name, m.osmSnapshot, emptyList())
    }

    suspend fun removeOptionalParts(packId: String): String? = removal { removeOptionalLocked(packId) }

    /** The record first, then a generation (search closes the old handle), then the files. */
    private fun removeOptionalLocked(packId: String): String? {
        val pack = scanner.scan().packs.firstOrNull { it.id == packId } ?: return "No state with the id $packId is installed."
        val record = pack.install ?: return "${pack.manifest.name} was not installed by Beamline."
        val present = PackManifest.OPTIONAL_PARTS.filter { it in pack.manifest.parts && it !in record.skippedParts }
        if (present.isEmpty()) return "${pack.manifest.name} has no house numbers to remove."
        writeRecord(pack.dir, record.copy(skippedParts = (record.skippedParts + present).distinct()))
        publishScan(changed = true)
        for (name in present) File(pack.dir, pack.manifest.allParts.getValue(name).file).delete()
        return null
    }

    // ------------------------------------------------------------------ files

    private fun readRecord(dir: File): InstallRecord? = File(dir, InstallRecord.FILE_NAME).takeIf { it.isFile }
        ?.let { runCatching { InstallRecord.parse(it.readText(Charsets.UTF_8)) }.getOrNull() }

    /** Replaces install-record.json atomically (a temporary file, then a rename). */
    private fun writeRecord(dir: File, r: InstallRecord) {
        val tmp = File(dir, InstallRecord.FILE_NAME + ".tmp")
        writing(InstallRecord.FILE_NAME) {
            FileOutputStream(tmp).use { out ->
                out.write(InstallRecord.encode(r).toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            Files.move(tmp.toPath(), File(dir, InstallRecord.FILE_NAME).toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun <T> writing(file: String, block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        val msg = e.message.orEmpty()
        throw InstallFailure(
            if ("ENOSPC" in msg || "No space left" in msg) "The phone ran out of storage space while writing $file. Free some space and try again."
            else "Writing $file into the app's storage failed (${e::class.simpleName}).",
        )
    }

    private fun sha256(f: File, cancelled: () -> Boolean): Pair<Long, String> {
        val md = MessageDigest.getInstance("SHA-256")
        var n = 0L
        f.inputStream().use { input ->
            val buf = ByteArray(BUFFER)
            while (true) {
                if (cancelled()) throw InstallCancelled()
                val r = input.read(buf)
                if (r < 0) break
                md.update(buf, 0, r)
                n += r
            }
        }
        return n to md.digest().joinToString("") { "%02x".format(it) }
    }

    private fun mb(b: Long) = (b + 999_999) / 1_000_000

    companion object {
        /** The Valhalla version of the engine the app ships (valhalla-mobile 0.6.4). */
        const val VALHALLA_VERSION = "3.9.1"
        const val STAGING = ".staging-"
        const val OLD = ".old-"
        const val REMOVING = ".removing-"

        /** [PackIssue.folder] of an issue of the merged routing set ("routing", "routing/<state>"). */
        const val ROUTING_ISSUE = "routing"
        const val BUFFER = 1 shl 20
        const val MAX_MANIFEST = 1L shl 20

        /** Room counted for manifest.json and install-record.json in a staging folder. */
        const val MANIFEST_RESERVE = 64L * 1024

        /** What RoutingPackManager keeps free beside the new tile set (its staged manifests). */
        const val ROUTING_RESERVE = 4L shl 20

        /** Level-2 tile id of a tile path ("2/000/752/000.gph" -> 752000), as RoutingSet.level2TileId counts. */
        fun level2Id(path: String): Int? =
            if (path.startsWith("2/") && path.endsWith(".gph")) path.removePrefix("2/").removeSuffix(".gph").replace("/", "").toIntOrNull() else null
    }
}
