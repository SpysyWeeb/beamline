package io.github.spysyweeb.beamline.download

import io.github.spysyweeb.beamline.api.data.ArchiveInfo
import io.github.spysyweeb.beamline.api.data.InstallStatus
import io.github.spysyweeb.beamline.api.data.InstalledData
import io.github.spysyweeb.beamline.download.net.BadResponse
import io.github.spysyweeb.beamline.download.net.ClockProblem
import io.github.spysyweeb.beamline.download.net.NetworkRuleRefused
import io.github.spysyweeb.beamline.download.net.PackHttp
import io.github.spysyweeb.beamline.download.net.PackRequest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.util.ArrayDeque
import java.util.zip.GZIPInputStream
import java.util.zip.ZipException

/** Test seams of the engine (JVM tests only; null in the app). */
internal object EngineHooks {
    /** Called before every write into a job folder with the file and the bytes it holds; may throw IOException (an injected ENOSPC). */
    @Volatile
    var beforeWrite: ((File, Long) -> Unit)? = null

    /** Called after every buffer the engine has inflated (UNPACKING), with the part name and the bytes written so far. */
    @Volatile
    var afterInflate: ((String, Long) -> Unit)? = null
}

/** PAUSED for one of these reasons is continued by GetPacks.onAppVisible without a tap (docs/architecture-downloads.md 5.2). */
fun DownloadJob.continuesByItself(): Boolean =
    phase == JobPhase.PAUSED && pause in setOf(PauseReason.APP_ENDED, PauseReason.SYSTEM_STOPPED, PauseReason.SERVER_UNREACHABLE)

/**
 * The work behind [DownloadEngine] (state machine of docs/architecture-downloads.md 6.4).
 *
 * Threading: every change of the job happens under [lock]; the worker (one coroutine at a time,
 * inside [run]) does the file and network work outside it. A stop (pause, cancel, system stop)
 * changes the job at once and bumps [Active.generation]; the worker notices at its next buffer or
 * transition and leaves without touching the job again.
 */
internal class EngineCore(
    private val root: File,
    private val http: PackHttp,
    private val catalogs: CatalogSource,
    private val handoff: PackHandoff,
    private val installed: InstalledData,
    private val network: StateFlow<NetworkState>,
    private val freeBytes: (File) -> Long,
    private val rules: DownloadRules,
    @Suppress("unused") private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long,
) {
    val job = MutableStateFlow<DownloadJob?>(null)
    val needsService = MutableStateFlow(false)

    private val source get() = catalogs.core
    private val lock = Any()
    private val runMutex = Mutex()
    private var current: Active? = null
    private var worker: kotlinx.coroutines.Job? = null
    private var recovered = false
    /** Bumped by allowMetered() so a NOT_UNMETERED wait looks again. */
    private val meteredTick = MutableStateFlow(0)

    /** The job in memory: the catalog entry, the record as on disk, and what is not persisted. */
    private class Active(val pack: CatalogPack, var record: JobRecord, val folder: File) {
        /** Bumped by every stop; a worker of an older generation must not touch the job. */
        var generation = 0
        var cancelled = false
        /** manifest.json fetched and checked, inspect and space done in this run. */
        var checked = false
        var info: ArchiveInfo? = null
        var attempts = 0
        var backoffIndex = 0
        var retryAtMs: Long? = null
        var currentPart: String? = null
        var received = 0L
        var installStep: io.github.spysyweeb.beamline.api.data.InstallStep? = null
        var installDone = 0L
        var installTotal = 0L
        var lastPublishNs = 0L
        val samples = ArrayDeque<Pair<Long, Long>>()
        /** The SHA-256 state of a transfer file's first bytes, kept between attempts so a resume need not read them again. */
        var partial: Triple<String, Long, MessageDigest>? = null
        val phase get() = record.phase
    }

    private class End : Exception()
    private class Stopped : CancellationException("stopped")
    private class Gone : IOException("gone")
    private class HashMismatch : Exception()
    private class LocalWriteFailed(cause: IOException) : Exception(cause)
    private class Retryable(why: String) : IOException(why)

    // ---------------------------------------------------------------- public operations

    suspend fun recover() {
        synchronized(lock) {
            if (recovered) return
            recovered = true
        }
        installed.installed.first { it.scanned }
        withContext(io) { recoverFolders() }
    }

    private fun recoverFolders() {
        val dirs = root.listFiles() ?: return
        val found = mutableListOf<Triple<File, JobRecord, CatalogPack>>()
        for (f in dirs) {
            if (!f.isDirectory) {
                f.delete()
                continue
            }
            val rec = JobRecord.read(f)
            val pack = rec?.let { JobRecord.decodeEntry(it.entry, source) }
            if (rec == null || pack == null || rec.catalogUrl != source.address.url || f.name != folderName(pack) || rec.phase == JobPhase.DONE) {
                f.deleteRecursively()
                continue
            }
            found += Triple(f, rec, pack)
        }
        // Only one job folder may exist (a crash while switching builds can leave two): keep the newest record.
        val keep = found.maxByOrNull { File(it.first, JobRecord.FILE).lastModified() } ?: return
        found.filter { it !== keep }.forEach { it.first.deleteRecursively() }
        val (folder, rec, pack) = keep
        synchronized(lock) {
            if (current != null) return
            val a = Active(pack, rec, folder)
            current = a
            when {
                rec.phase == JobPhase.FAILED -> deleteAllBut(folder, JobRecord.FILE)
                alreadyInstalled(a) -> {
                    folder.deleteRecursively()
                    a.record = rec.copy(phase = JobPhase.DONE, wait = null, pause = null, fail = null)
                }
                rec.phase == JobPhase.PAUSED -> Unit
                rec.phase == JobPhase.WAITING && (rec.wait == WaitReason.TRIP_IN_PROGRESS || rec.wait == WaitReason.INSTALLER_BUSY) ->
                    setRecord(a, rec.copy(phase = JobPhase.PAUSED, wait = null, pause = PauseReason.APP_ENDED))
                else -> {
                    // It was running when the process ended: one more death, unless it gained enough since the last one.
                    val onDisk = onDiskBytes(a)
                    val deaths = if (onDisk - rec.bytesAtLastDeath >= rules.syncEveryBytes) 1 else rec.deathsWithoutProgress + 1
                    val reason = if (deaths >= rules.maxDeathsWithoutProgress) PauseReason.KEEPS_STOPPING else PauseReason.APP_ENDED
                    DownloadLog.info("job ${pack.id} ended with the process; deaths $deaths, $reason") // log-ok: pack id, count, enum
                    setRecord(a, rec.copy(phase = JobPhase.PAUSED, wait = null, pause = reason, deathsWithoutProgress = deaths, bytesAtLastDeath = onDisk))
                }
            }
            publish(a, force = true)
        }
    }

    fun start(pack: CatalogPack, kind: JobKind, includeOptional: Boolean, allowMetered: Boolean, firstRun: Boolean): StartResult {
        synchronized(lock) {
            val cur = current
            if (cur != null) {
                if (cur.phase != JobPhase.DONE && cur.phase != JobPhase.FAILED) return StartResult.Refused(StartRefusal.ANOTHER_JOB)
                forget(cur)
            }
            if (pack.manifest.encoding != "identity" || pack.parts.any { it.transfer.encoding !in CatalogFormat.ENCODINGS } ||
                pack.manifestVersion !in source.minManifestVersion..source.maxManifestVersion
            ) {
                return StartResult.Refused(StartRefusal.NEEDS_NEWER_APP)
            }
            if (kind == JobKind.ADD_OPTIONAL) {
                val p = installed.installed.value.packs.firstOrNull { it.packId == pack.id }
                // The archive stand-in cannot add a part (PackHandoff.kt): only P's folder install can.
                if (!installed.changesOptionalParts || handoff is ArchiveStreamHandoff || p == null ||
                    p.packVersion != pack.packVersion || pack.parts.none { it.optional }
                ) {
                    return StartResult.Refused(StartRefusal.NOT_SUPPORTED)
                }
            }
            val a = newActive(pack, kind, includeOptional, allowMetered, firstRun) ?: return StartResult.Refused(StartRefusal.NOT_SUPPORTED)
            current = a
            publish(a, force = true)
            return StartResult.Started
        }
    }

    /** A fresh job folder and record (CHECKING); null when the folder cannot be made safely. */
    private fun newActive(pack: CatalogPack, kind: JobKind, includeOptional: Boolean, allowMetered: Boolean, firstRun: Boolean): Active? {
        val folder = File(root, folderName(pack))
        if (!insideRoot(folder)) return null
        if (folder.exists()) folder.deleteRecursively()
        folder.mkdirs()
        val record = JobRecord(
            catalogUrl = source.address.url,
            catalogSerial = (catalogs.state.value as? CatalogState.Ready)?.view?.catalog?.serial ?: 0,
            entry = JobRecord.encodeEntry(pack),
            kind = kind,
            includeOptional = includeOptional,
            allowMetered = allowMetered,
            firstRun = firstRun,
            phase = JobPhase.CHECKING,
            parts = Space.parts(pack, kind, includeOptional).associate { it.name to PartRecord() },
        )
        val a = Active(pack, record, folder)
        persist(a)
        return a
    }

    suspend fun run() {
        runMutex.withLock {
            withContext(io) {
                coroutineScope {
                    // A cancelled caller (the service's end) must also break a read that is blocked in the network.
                    val scopeJob = coroutineContext.job
                    val watcher = launch {
                        try {
                            awaitCancellation()
                        } finally {
                            if (scopeJob.isCancelled) http.cancelAll()
                        }
                    }
                    try {
                        while (isActive) {
                            val a = synchronized(lock) { current } ?: break
                            if (!workable(a)) break
                            val gen = synchronized(lock) { a.generation }
                            val w = launch(start = CoroutineStart.LAZY) { work(a, gen) }
                            synchronized(lock) { worker = w }
                            w.start()
                            w.join()
                            synchronized(lock) {
                                worker = null
                                if (a.cancelled && current?.folder != a.folder) a.folder.deleteRecursively()
                            }
                            if (synchronized(lock) { current } === a) break
                        }
                    } finally {
                        watcher.cancel()
                    }
                }
            }
        }
    }

    private fun workable(a: Active): Boolean = when (a.phase) {
        JobPhase.DONE, JobPhase.PAUSED, JobPhase.FAILED -> false
        else -> true
    }

    fun pause() = stop(PauseReason.USER)

    fun stopForSystem() = stop(PauseReason.SYSTEM_STOPPED)

    private fun stop(reason: PauseReason) {
        val hadWorker: Boolean
        synchronized(lock) {
            val a = current ?: return
            if (a.phase in setOf(JobPhase.INSTALLING, JobPhase.DONE, JobPhase.FAILED, JobPhase.PAUSED)) return
            a.generation++
            setRecord(a, a.record.copy(phase = JobPhase.PAUSED, wait = null, pause = reason, fail = null))
            a.retryAtMs = null
            a.checked = false
            publish(a, force = true)
            hadWorker = worker?.isActive == true
            worker?.cancel(Stopped())
        }
        if (hadWorker) http.cancelAll()
    }

    fun resume(allowMetered: Boolean?) {
        synchronized(lock) {
            val a = current ?: return
            if (a.phase != JobPhase.PAUSED) return
            a.generation++
            a.checked = false
            a.attempts = 0
            a.backoffIndex = 0
            val parts = a.record.parts.mapValues { it.value.copy(hashFailures = 0) }
            setRecord(a, a.record.copy(
                phase = JobPhase.CHECKING, pause = null, wait = null, fail = null, detail = null, needBytes = null, freeBytes = null,
                allowMetered = allowMetered ?: a.record.allowMetered, parts = parts,
            ))
            publish(a, force = true)
        }
    }

    fun allowMetered() {
        synchronized(lock) {
            val a = current ?: return
            if (!a.record.allowMetered) {
                setRecord(a, a.record.copy(allowMetered = true))
                publish(a, force = true)
            }
        }
        meteredTick.value++
    }

    fun cancel() {
        val hadWorker: Boolean
        val folder: File
        synchronized(lock) {
            val a = current ?: return
            if (a.phase == JobPhase.INSTALLING) return
            a.generation++
            a.cancelled = true
            folder = a.folder
            current = null
            job.value = null
            needsService.value = false
            hadWorker = worker?.isActive == true
            worker?.cancel(Stopped())
        }
        if (hadWorker) http.cancelAll()
        folder.deleteRecursively() // what an open file still holds is deleted again when the worker has left (run)
        DownloadLog.info("job cancelled") // log-ok: plain text
    }

    fun dismiss() {
        synchronized(lock) {
            val a = current ?: return
            if (a.phase != JobPhase.DONE && a.phase != JobPhase.FAILED) return
            forget(a)
        }
    }

    private fun forget(a: Active) {
        if (a.phase == JobPhase.FAILED) a.folder.deleteRecursively()
        current = null
        job.value = null
        needsService.value = false
    }

    // ---------------------------------------------------------------- the worker

    private suspend fun work(a: Active, gen: Int) {
        try {
            when {
                a.phase == JobPhase.INSTALLING -> followInstall(a, gen)
                a.phase == JobPhase.WAITING && (a.record.wait == WaitReason.TRIP_IN_PROGRESS || a.record.wait == WaitReason.INSTALLER_BUSY) ->
                    install(a, gen)
                else -> {
                    if (!a.checked) check(a, gen)
                    download(a, gen)
                    install(a, gen)
                }
            }
        } catch (_: End) {
            // the job's state is set
        }
    }

    /** Throws [Stopped] when the job was stopped, cancelled or replaced since this worker began. */
    private fun checkpoint(a: Active, gen: Int) {
        synchronized(lock) {
            if (current !== a || a.generation != gen || worker?.isCancelled == true) throw Stopped()
        }
    }

    private inline fun <T> transition(a: Active, gen: Int, block: () -> T): T = synchronized(lock) {
        if (current !== a || a.generation != gen) throw Stopped()
        block()
    }

    private fun setPhase(a: Active, gen: Int, phase: JobPhase) = transition(a, gen) {
        if (a.record.phase != phase || a.record.wait != null) {
            setRecord(a, a.record.copy(phase = phase, wait = null))
            a.retryAtMs = null
        }
        publish(a, force = true)
    }

    private fun pauseNow(a: Active, gen: Int, reason: PauseReason, detail: String? = null, need: Long? = null, free: Long? = null): Nothing {
        transition(a, gen) {
            setRecord(a, a.record.copy(phase = JobPhase.PAUSED, wait = null, pause = reason, detail = detail, needBytes = need, freeBytes = free))
            a.retryAtMs = null
            a.checked = false
            publish(a, force = true)
        }
        DownloadLog.info("job ${a.pack.id} paused: $reason") // log-ok: pack id and enum name
        throw End()
    }

    private fun failNow(a: Active, gen: Int, reason: FailReason, detail: String? = null): Nothing {
        transition(a, gen) {
            setRecord(a, a.record.copy(phase = JobPhase.FAILED, wait = null, pause = null, fail = reason, detail = detail))
            deleteAllBut(a.folder, JobRecord.FILE)
            publish(a, force = true)
        }
        DownloadLog.info("job ${a.pack.id} failed: $reason") // log-ok: pack id and enum name
        throw End()
    }

    /** File.usableSpace is 0 for a folder that does not exist: the downloads folder is made first. */
    private fun freeHere(): Long {
        root.mkdirs()
        return freeBytes(root)
    }

    private fun parts(a: Active): List<CatalogPart> = Space.parts(a.pack, a.record.kind, a.record.includeOptional)

    private fun url(a: Active, path: String): String = source.address.resolve(path)

    // ---------------------------------------------------------------- CHECKING

    private suspend fun check(a: Active, gen: Int) {
        setPhase(a, gen, JobPhase.CHECKING)
        if (alreadyInstalled(a)) finishDone(a, gen)
        val others = installed.installed.value.packs.filter { it.packId == a.pack.id && it.state != a.pack.state }
        if (others.isNotEmpty()) failNow(a, gen, FailReason.LIST_MISMATCH)

        val manifestFile = File(a.folder, "manifest.json")
        if (!fileMatches(manifestFile, a.pack.manifest.bytes, a.pack.manifest.sha256)) {
            val bytes = attempt(a, gen, JobPhase.CHECKING) { fetchManifest(a, gen) }
            if (bytes.size.toLong() != a.pack.manifest.bytes || sha256(bytes) != a.pack.manifest.sha256) failNow(a, gen, FailReason.LIST_MISMATCH)
            try {
                writeAtomically(manifestFile, bytes)
            } catch (_: IOException) {
                pauseNoSpace(a, gen)
            }
        }
        if (!manifestAgrees(a, manifestFile)) failNow(a, gen, FailReason.LIST_MISMATCH)

        val info = try {
            handoff.inspect(a.folder, a.pack.name)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failNow(a, gen, FailReason.REJECTED, e.message ?: e.javaClass.simpleName)
        }
        checkpoint(a, gen)
        val held = installed.installed.value.changesHeldBy
        val problems = info.problems.filter { held == null || !it.contains(held) }
        if (problems.isNotEmpty()) {
            val newer = problems.any { it.contains("newer", ignoreCase = true) || it.contains("update the app", ignoreCase = true) }
            failNow(a, gen, if (newer) FailReason.NEEDS_NEWER_APP else FailReason.REJECTED, problems.joinToString("\n"))
        }
        a.info = info
        val need = needBytes(a, info)
        val free = freeHere()
        if (need > free) pauseNow(a, gen, PauseReason.NO_SPACE, need = need, free = free)
        a.checked = true
    }

    private fun needBytes(a: Active, info: ArchiveInfo?): Long {
        val onDisk = verifiedBytes(a)
        return if (info != null) {
            Space.exact(a.pack, a.record.kind, a.record.includeOptional, handoff.moves, onDisk, rules.spaceMarginBytes, info)
        } else {
            Space.estimate(a.pack, a.record.kind, a.record.includeOptional, handoff.moves, 0, onDisk, rules.spaceMarginBytes)
        }
    }

    private fun pauseNoSpace(a: Active, gen: Int): Nothing =
        pauseNow(a, gen, PauseReason.NO_SPACE, need = needBytes(a, a.info), free = freeHere())

    private fun fetchManifest(a: Active, gen: Int): ByteArray {
        val r = http.get(PackRequest(url(a, a.pack.manifest.path)))
        r.use {
            statusCheck(r.status, ok = setOf(200))
            val limit = a.pack.manifest.bytes
            if (r.contentLength != null && r.contentLength > limit) throw BadResponse("a body longer than the catalog says")
            val bytes = CatalogSourceCore.readAtMost(r.body, limit + 1)
            checkpoint(a, gen)
            if (bytes.size > limit) throw BadResponse("a body longer than the catalog says")
            if (bytes.size < limit) throw Retryable("cut short")
            return bytes
        }
    }

    /** Rule 4 of 2.3: the manifest's pack_id, pack_version (when present), state, osm_snapshot and every chosen part's file, bytes and sha256 equal the catalog's. */
    private fun manifestAgrees(a: Active, file: File): Boolean {
        val m = try {
            Json.parseToJsonElement(file.readText(Charsets.UTF_8).removePrefix("﻿")) as? JsonObject
        } catch (_: Exception) {
            null
        } ?: return false
        fun str(o: JsonObject, k: String) = (o[k] as? JsonPrimitive)?.takeIf { it.isString }?.content
        if (str(m, "pack_id") != a.pack.id || str(m, "state") != a.pack.state || str(m, "osm_snapshot") != a.pack.osmSnapshot) return false
        m["pack_version"]?.let { v -> if ((v as? JsonPrimitive)?.longOrNull != a.pack.packVersion) return false }
        val mp = m["parts"] as? JsonObject ?: return false
        for (part in parts(a)) {
            val p = mp[part.name] as? JsonObject ?: return false
            if (str(p, "file") != part.file) return false
            if ((p["bytes"] as? JsonPrimitive)?.longOrNull != part.bytes) return false
            if (str(p, "sha256")?.lowercase() != part.sha256) return false
        }
        return true
    }

    // ---------------------------------------------------------------- DOWNLOADING and UNPACKING

    private suspend fun download(a: Active, gen: Int) {
        setPhase(a, gen, JobPhase.DOWNLOADING)
        for (part in parts(a)) ensurePart(a, gen, part)
    }

    private fun partRecord(a: Active, name: String): PartRecord = a.record.parts[name] ?: PartRecord()

    private fun updatePart(a: Active, gen: Int, name: String, change: (PartRecord) -> PartRecord) = transition(a, gen) {
        val old = partRecord(a, name)
        val new = change(old)
        if (new != old) setRecord(a, a.record.copy(parts = a.record.parts + (name to new)))
    }

    private suspend fun ensurePart(a: Active, gen: Int, part: CatalogPart) {
        val final = File(a.folder, part.file)
        val transferFile = File(a.folder, ".transfer-${part.name}")
        if (!insideFolder(a.folder, final) || !insideFolder(a.folder, transferFile)) failNow(a, gen, FailReason.LIST_MISMATCH)
        val rec = partRecord(a, part.name)
        if (rec.verified && final.isFile && final.length() == part.bytes) return
        if (rec.verified) updatePart(a, gen, part.name) { it.copy(verified = false, transferVerified = false) }

        while (true) {
            val haveTransfer = partRecord(a, part.name).transferVerified && transferFile.isFile && transferFile.length() == part.transfer.bytes
            if (!haveTransfer) {
                if (partRecord(a, part.name).transferVerified) updatePart(a, gen, part.name) { it.copy(transferVerified = false) }
                val ok = attempt(a, gen, JobPhase.DOWNLOADING) { transfer(a, gen, part, transferFile) }
                if (!ok) {
                    damaged(a, gen, part, transferFile, final)
                    continue
                }
                updatePart(a, gen, part.name) { it.copy(transferVerified = true) }
            }
            if (part.transfer.encoding == "identity") {
                try {
                    if (final.exists()) final.delete()
                    if (!transferFile.renameTo(final)) throw IOException("rename failed")
                } catch (_: IOException) {
                    pauseNoSpace(a, gen)
                }
                partDone(a, gen, part)
                return
            }
            val ok = unpack(a, gen, part, transferFile, final)
            if (ok) {
                transferFile.delete()
                partDone(a, gen, part)
                setPhase(a, gen, JobPhase.DOWNLOADING)
                return
            }
            damaged(a, gen, part, transferFile, final)
        }
    }

    private fun partDone(a: Active, gen: Int, part: CatalogPart) {
        transition(a, gen) {
            val p = partRecord(a, part.name).copy(verified = true, transferVerified = true)
            setRecord(a, a.record.copy(parts = a.record.parts + (part.name to p), deathsWithoutProgress = 0, bytesAtLastDeath = onDiskBytes(a)))
            a.currentPart = null
            a.partial = null
            publish(a, force = true)
        }
    }

    /** A hash mismatch: once more from 0, then PAUSED(DAMAGED) with that part's files deleted. */
    private fun damaged(a: Active, gen: Int, part: CatalogPart, transferFile: File, final: File) {
        transferFile.delete()
        final.delete()
        a.partial = null
        val failures = partRecord(a, part.name).hashFailures + 1
        updatePart(a, gen, part.name) { it.copy(hashFailures = failures, transferVerified = false, verified = false, etag = null) }
        DownloadLog.info("part ${part.name} of ${a.pack.id} arrived damaged ($failures)") // log-ok: part name, pack id, count
        if (failures > rules.hashRetries) pauseNow(a, gen, PauseReason.DAMAGED)
        transition(a, gen) { publish(a, force = true) }
    }

    /**
     * One network attempt with the retry rules of 1.4 and 6.4: waits for a usable network first;
     * network errors, timeouts, 5xx and 429 wait with back-off (reset after any byte gained) until
     * max_attempts_without_progress attempts gained nothing.
     */
    private suspend fun <T> attempt(a: Active, gen: Int, phase: JobPhase, block: () -> T): T {
        while (true) {
            awaitNetwork(a, gen, phase)
            checkpoint(a, gen)
            val receivedBefore = a.received
            val failure: Exception = try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                e
            }
            checkpoint(a, gen) // a pause or cancel shows up here as the cancelled call's IOException
            when (failure) {
                is ClockProblem -> pauseNow(a, gen, PauseReason.CLOCK)
                is NetworkRuleRefused -> pauseNow(a, gen, PauseReason.BAD_RESPONSE, failure.message)
                is BadResponse -> pauseNow(a, gen, PauseReason.BAD_RESPONSE, failure.rule)
                is LocalWriteFailed -> pauseNoSpace(a, gen)
                is Gone -> gone(a, gen) // switches, fails, or returns when the list could not be fetched again
                is IOException -> Unit
                else -> throw failure
            }
            if (a.received > receivedBefore) {
                // Bytes were gained: the count and the back-off start again, and this attempt does not count.
                a.attempts = 0
                a.backoffIndex = 0
            } else {
                a.attempts++
            }
            DownloadLog.info("attempt ${a.attempts} for ${a.pack.id} failed: ${failure.javaClass.simpleName}") // log-ok: count, pack id, exception class name
            if (a.attempts >= rules.maxAttemptsWithoutProgress) pauseNow(a, gen, PauseReason.SERVER_UNREACHABLE)
            if (!usable(network.value, a)) continue // the next round waits for the network
            val seconds = rules.retryBackoffS[minOf(a.backoffIndex, rules.retryBackoffS.size - 1)]
            a.backoffIndex++
            transition(a, gen) {
                setRecord(a, a.record.copy(phase = JobPhase.WAITING, wait = WaitReason.RETRYING))
                a.retryAtMs = clock() + seconds * 1000L
                publish(a, force = true)
            }
            delay(seconds * 1000L)
            setPhase(a, gen, phase)
        }
    }

    private fun usable(s: NetworkState, a: Active) = s.connected && (s.unmetered || a.record.allowMetered)

    private suspend fun awaitNetwork(a: Active, gen: Int, phase: JobPhase) {
        if (usable(network.value, a)) return
        fun reason(s: NetworkState) = if (!s.connected) WaitReason.NO_NETWORK else WaitReason.NOT_UNMETERED
        transition(a, gen) {
            setRecord(a, a.record.copy(phase = JobPhase.WAITING, wait = reason(network.value)))
            a.retryAtMs = null
            publish(a, force = true)
        }
        val ok = withTimeoutOrNull(rules.waitForNetworkMaxMin * 60_000L) {
            combine(network, meteredTick) { s, _ -> s }.first { s ->
                val good = usable(s, a)
                if (!good) transition(a, gen) {
                    if (a.record.wait != reason(s)) {
                        setRecord(a, a.record.copy(wait = reason(s)))
                        publish(a, force = true)
                    }
                }
                good
            }
        }
        if (ok == null) pauseNow(a, gen, PauseReason.SYSTEM_STOPPED)
        a.attempts = 0
        a.backoffIndex = 0
        setPhase(a, gen, phase)
    }

    private fun statusCheck(status: Int, ok: Set<Int>) {
        when {
            status in ok -> Unit
            status == 404 || status == 410 -> throw Gone()
            status == 429 || status in 500..599 -> throw Retryable("HTTP $status")
            else -> throw BadResponse("HTTP $status")
        }
    }

    /**
     * One transfer attempt of [part] into [file] (2.4); true when the whole file is there with the
     * catalog's SHA-256, false on a mismatch. A transfer cut short keeps its bytes and throws.
     */
    private fun transfer(a: Active, gen: Int, part: CatalogPart, file: File): Boolean {
        val size = part.transfer.bytes
        var restarts = 0
        transition(a, gen) {
            a.currentPart = part.name
            publish(a, force = true)
        }
        while (true) {
            var have = if (file.isFile) file.length() else 0L
            if (have > size) {
                file.delete()
                have = 0
            }
            val digest = prefixDigest(a, gen, part, file, have)
            val etag = partRecord(a, part.name).etag
            val r = http.get(PackRequest(url(a, part.transfer.path), rangeFrom = have, ifMatch = if (have > 0) etag else null))
            var startOver = false
            r.use {
                when (r.status) {
                    206 -> when {
                        have == 0L -> throw BadResponse("a 206 that was not asked for")
                        r.rangeStart != have -> throw BadResponse("a 206 that does not start where asked")
                        etag != null && r.etag != null && r.etag != etag -> startOver = true
                    }
                    200 -> if (have > 0) {
                        have = 0
                        digest.reset()
                    }
                    416 -> if (!(have == size && r.totalLength == size)) startOver = true
                    412 -> startOver = true
                    else -> statusCheck(r.status, ok = emptySet())
                }
                if (startOver) return@use
                if (r.status == 416) return@use
                if (r.etag != etag) updatePart(a, gen, part.name) { it.copy(etag = r.etag) }
                val remaining = size - have
                if (r.contentLength != null && r.contentLength > remaining) throw BadResponse("a body longer than the catalog says")
                have = stream(a, gen, part, r.body, file, have, remaining, digest)
            }
            if (startOver) {
                restarts++
                if (restarts > 3) throw BadResponse("the file keeps changing on the server")
                file.delete()
                a.partial = null
                updatePart(a, gen, part.name) { it.copy(etag = null) }
                continue
            }
            if (have < size) throw Retryable("cut short")
            a.partial = null
            val ok = digest.digest().toHex() == part.transfer.sha256
            return ok
        }
    }

    /** SHA-256 over the first [have] bytes of [file] (2.4: re-hash them), from memory when this run hashed them already. */
    private fun prefixDigest(a: Active, gen: Int, part: CatalogPart, file: File, have: Long): MessageDigest {
        val kept = a.partial
        if (kept != null && kept.first == part.name && kept.second == have) return kept.third.clone() as MessageDigest
        val d = MessageDigest.getInstance("SHA-256")
        if (have > 0) {
            FileInputStream(file).use { input ->
                val buf = ByteArray(rules.bufferBytes)
                var left = have
                while (left > 0) {
                    val n = input.read(buf, 0, minOf(buf.size.toLong(), left).toInt())
                    if (n < 0) break
                    d.update(buf, 0, n)
                    left -= n
                    checkpoint(a, gen)
                }
            }
        }
        return d
    }

    private fun stream(a: Active, gen: Int, part: CatalogPart, body: java.io.InputStream, file: File, start: Long, remaining: Long, digest: MessageDigest): Long {
        var have = start
        var written = 0L
        var sinceSync = 0L
        val buf = ByteArray(rules.bufferBytes)
        val out = try {
            if (start == 0L) FileOutputStream(file, false) else FileOutputStream(file, true)
        } catch (e: IOException) {
            throw LocalWriteFailed(e)
        }
        out.use {
            try {
                while (true) {
                    if (!usable(network.value, a)) throw Retryable("network lost")
                    val n = body.read(buf)
                    if (n < 0) break
                    checkpoint(a, gen)
                    val take = minOf(n.toLong(), remaining - written).toInt()
                    if (take > 0) {
                        try {
                            EngineHooks.beforeWrite?.invoke(file, have)
                            out.write(buf, 0, take)
                        } catch (e: IOException) {
                            throw LocalWriteFailed(e)
                        }
                        digest.update(buf, 0, take)
                        have += take
                        written += take
                        sinceSync += take
                        a.received += take
                        a.partial = Triple(part.name, have, digest.clone() as MessageDigest)
                        if (sinceSync >= rules.syncEveryBytes) {
                            syncQuietly(out)
                            sinceSync = 0
                            progressSynced(a, gen)
                        }
                        progress(a, gen)
                    }
                    if (take < n) throw BadResponse("a body longer than the catalog says")
                }
            } finally {
                syncQuietly(out)
            }
        }
        return have
    }

    private fun syncQuietly(out: FileOutputStream) {
        try {
            out.flush()
            out.fd.sync()
        } catch (e: IOException) {
            throw LocalWriteFailed(e)
        }
    }

    /** The count of deaths goes back to 0 when the job has gained sync_every_bytes on disk (5.2). */
    private fun progressSynced(a: Active, gen: Int) = transition(a, gen) {
        if (a.record.deathsWithoutProgress > 0 && onDiskBytes(a) - a.record.bytesAtLastDeath >= rules.syncEveryBytes) {
            setRecord(a, a.record.copy(deathsWithoutProgress = 0, bytesAtLastDeath = onDiskBytes(a)))
        }
    }

    /** Inflates a gzip transfer file into the part file, hashing the output; false on a mismatch or a broken stream. */
    private fun unpack(a: Active, gen: Int, part: CatalogPart, transferFile: File, final: File): Boolean {
        transition(a, gen) {
            setRecord(a, a.record.copy(phase = JobPhase.UNPACKING, wait = null))
            a.currentPart = part.name
            publish(a, force = true)
        }
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        var complete = false
        try {
            GZIPInputStream(FileInputStream(transferFile), rules.bufferBytes).use { input ->
                val out = try {
                    FileOutputStream(final, false)
                } catch (e: IOException) {
                    throw LocalWriteFailed(e)
                }
                out.use {
                    val buf = ByteArray(rules.bufferBytes)
                    var sinceSync = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        checkpoint(a, gen)
                        total += n
                        if (total > part.bytes) return false
                        try {
                            EngineHooks.beforeWrite?.invoke(final, total - n)
                            out.write(buf, 0, n)
                        } catch (e: IOException) {
                            throw LocalWriteFailed(e)
                        }
                        digest.update(buf, 0, n)
                        sinceSync += n
                        if (sinceSync >= rules.syncEveryBytes) {
                            syncQuietly(out)
                            sinceSync = 0
                        }
                        EngineHooks.afterInflate?.invoke(part.name, total)
                    }
                    syncQuietly(out)
                }
            }
            complete = total == part.bytes && digest.digest().toHex() == part.sha256
            return complete
        } catch (_: ZipException) {
            return false
        } catch (_: java.io.EOFException) {
            return false
        } catch (e: LocalWriteFailed) {
            final.delete()
            throw e
        } catch (e: IOException) {
            // Reading our own verified transfer file failed: treat it as damaged.
            return false
        } finally {
            if (!complete) final.delete()
        }
    }

    // ---------------------------------------------------------------- 404 and 410

    /** 404 or 410: fetch the list again; switch to a newer build of the same pack, or fail. Returns only when the list could not be fetched. */
    private suspend fun gone(a: Active, gen: Int) {
        catalogs.refresh(force = true)
        checkpoint(a, gen)
        val catalog = (catalogs.state.value as? CatalogState.Ready)?.takeIf { !it.view.fromCache }?.view?.catalog ?: return
        val newer = catalog.packs.firstOrNull { it.id == a.pack.id && it.packVersion > a.pack.packVersion }
        if (newer == null || a.record.kind == JobKind.ADD_OPTIONAL) failNow(a, gen, FailReason.NO_LONGER_OFFERED)
        transition(a, gen) {
            val r = a.record
            a.generation++
            a.cancelled = true
            val next = newActive(newer, r.kind, r.includeOptional, r.allowMetered, r.firstRun)
            if (next == null) {
                a.cancelled = false
                setRecord(a, r.copy(phase = JobPhase.FAILED, fail = FailReason.NO_LONGER_OFFERED, wait = null))
                deleteAllBut(a.folder, JobRecord.FILE)
                publish(a, force = true)
            } else {
                current = next
                publish(next, force = true)
                DownloadLog.info("job ${a.pack.id} switched to build ${newer.packVersion}") // log-ok: pack id and a number
            }
        }
        a.folder.deleteRecursively()
        throw End()
    }

    // ---------------------------------------------------------------- READY and INSTALLING

    private suspend fun install(a: Active, gen: Int) {
        transition(a, gen) {
            setRecord(a, a.record.copy(phase = JobPhase.READY, wait = null))
            a.currentPart = null
            publish(a, force = true)
        }
        if (installed.installed.value.changesHeldBy != null) waitFor(a, gen, WaitReason.TRIP_IN_PROGRESS)
        val before = installed.install.value
        val started = if (a.record.kind == JobKind.ADD_OPTIONAL) handoff.startAddOptional(a.folder, a.pack.name)
        else handoff.start(a.folder, a.pack.name, a.record.includeOptional)
        if (!started) {
            waitFor(a, gen, if (installed.installed.value.changesHeldBy != null) WaitReason.TRIP_IN_PROGRESS else WaitReason.INSTALLER_BUSY)
        }
        transition(a, gen) {
            setRecord(a, a.record.copy(phase = JobPhase.INSTALLING))
            publish(a, force = true)
        }
        followInstall(a, gen, before)
    }

    private fun waitFor(a: Active, gen: Int, reason: WaitReason): Nothing {
        transition(a, gen) {
            setRecord(a, a.record.copy(phase = JobPhase.WAITING, wait = reason))
            publish(a, force = true)
        }
        throw End()
    }

    private suspend fun followInstall(a: Active, gen: Int, before: InstallStatus? = null) {
        var sawRunning = false
        val end = installed.install.first { s ->
            when (s) {
                is InstallStatus.Running -> {
                    sawRunning = true
                    transition(a, gen) {
                        a.installStep = s.step
                        a.installDone = s.doneBytes
                        a.installTotal = s.totalBytes
                        publish(a, force = false)
                    }
                    false
                }
                InstallStatus.Idle -> sawRunning
                else -> sawRunning || before == null || s != before
            }
        }
        withContext(NonCancellable) {
            when (end) {
                is InstallStatus.Done, InstallStatus.Idle -> {
                    if (end is InstallStatus.Done) installed.dismissInstallResult()
                    if (end is InstallStatus.Idle && !alreadyInstalled(a)) {
                        pauseNow(a, gen, PauseReason.USER)
                    }
                    finishDone(a, gen)
                }
                is InstallStatus.Failed -> {
                    installed.dismissInstallResult()
                    val need = needBytes(a, a.info)
                    val free = freeHere()
                    if (free < need) pauseNow(a, gen, PauseReason.NO_SPACE, end.message, need, free)
                    failNow(a, gen, FailReason.REJECTED, end.message)
                }
                is InstallStatus.Cancelled -> {
                    installed.dismissInstallResult()
                    pauseNow(a, gen, PauseReason.USER)
                }
                is InstallStatus.Running -> Unit
            }
        }
    }

    private fun finishDone(a: Active, gen: Int): Nothing {
        transition(a, gen) {
            a.folder.deleteRecursively()
            a.record = a.record.copy(phase = JobPhase.DONE, wait = null, pause = null, fail = null)
            a.installStep = null
            publish(a, force = true)
        }
        DownloadLog.info("job ${a.pack.id} done") // log-ok: pack id
        throw End()
    }

    // ---------------------------------------------------------------- helpers

    private fun alreadyInstalled(a: Active): Boolean {
        val p = installed.installed.value.packs.firstOrNull { it.packId == a.pack.id && it.state == a.pack.state } ?: return false
        if (a.record.kind == JobKind.ADD_OPTIONAL) return p.packVersion == a.pack.packVersion && p.addressesFile != null
        val versionOk = p.packVersion?.let { it >= a.pack.packVersion } ?: (p.osmSnapshot >= a.pack.osmSnapshot)
        val wantsOptional = a.record.includeOptional && a.pack.parts.any { it.optional }
        return versionOk && (!wantsOptional || p.addressesFile != null)
    }

    /** Bytes of the chosen transfer files on disk now (JobProgress.doneBytes), the manifest included. */
    private fun onDiskBytes(a: Active): Long {
        var sum = if (File(a.folder, "manifest.json").isFile) a.pack.manifest.bytes else 0L
        for (part in parts(a)) {
            val rec = partRecord(a, part.name)
            val final = File(a.folder, part.file)
            val t = File(a.folder, ".transfer-${part.name}")
            sum += when {
                rec.verified && final.isFile -> part.transfer.bytes
                t.isFile -> minOf(t.length(), part.transfer.bytes)
                else -> 0L
            }
        }
        return sum
    }

    /** Unpacked bytes already in the folder and verified (D of 2.6). */
    private fun verifiedBytes(a: Active): Long {
        var sum = if (File(a.folder, "manifest.json").isFile) a.pack.manifest.bytes else 0L
        for (part in parts(a)) {
            if (partRecord(a, part.name).verified && File(a.folder, part.file).isFile) sum += part.bytes
        }
        return sum
    }

    private fun setRecord(a: Active, r: JobRecord) {
        a.record = r
        persist(a)
    }

    private fun persist(a: Active) {
        if (a.record.phase == JobPhase.DONE || a.cancelled) return
        try {
            a.folder.mkdirs()
            JobRecord.write(a.folder, a.record)
        } catch (e: IOException) {
            DownloadLog.info("job.json not written: ${e.javaClass.simpleName}") // log-ok: exception class name
        }
    }

    private fun progress(a: Active, gen: Int) {
        val now = System.nanoTime()
        if (now - a.lastPublishNs < rules.progressEveryMs * 1_000_000L) return
        transition(a, gen) { publish(a, force = false) }
    }

    /** Builds the public job from [a] (under [lock]). */
    private fun publish(a: Active, force: Boolean) {
        if (current !== a) return
        val now = System.nanoTime()
        if (!force && now - a.lastPublishNs < rules.progressEveryMs * 1_000_000L) return
        a.lastPublishNs = now
        val total = a.pack.manifest.bytes + parts(a).sumOf { it.transfer.bytes }
        val done = if (a.record.phase == JobPhase.DONE) total else onDiskBytes(a)
        val moving = a.record.phase == JobPhase.DOWNLOADING
        if (moving) {
            a.samples.addLast(now to done)
            while (a.samples.size > 2 && now - a.samples.first().first > rules.speedWindowS * 1_000_000_000L) a.samples.removeFirst()
        } else {
            a.samples.clear()
        }
        val speed = if (moving && a.samples.size >= 2) {
            val (t0, b0) = a.samples.first()
            val dt = now - t0
            if (dt >= 1_000_000_000L && done > b0) (done - b0) * 1_000_000_000L / dt else null
        } else null
        val r = a.record
        val j = DownloadJob(
            packId = a.pack.id,
            packVersion = a.pack.packVersion,
            name = a.pack.name,
            state = a.pack.state,
            osmSnapshot = a.pack.osmSnapshot,
            kind = r.kind,
            includeOptional = r.includeOptional,
            allowMetered = r.allowMetered,
            firstRun = r.firstRun,
            phase = r.phase,
            progress = JobProgress(done, total, a.currentPart, speed),
            wait = if (r.phase == JobPhase.WAITING) r.wait else null,
            retryAtMs = if (r.phase == JobPhase.WAITING && r.wait == WaitReason.RETRYING) a.retryAtMs else null,
            pause = if (r.phase == JobPhase.PAUSED) r.pause else null,
            fail = if (r.phase == JobPhase.FAILED) r.fail else null,
            detail = if (r.phase == JobPhase.PAUSED || r.phase == JobPhase.FAILED) r.detail else null,
            needBytes = if (r.pause == PauseReason.NO_SPACE && r.phase == JobPhase.PAUSED) r.needBytes else null,
            freeBytes = if (r.pause == PauseReason.NO_SPACE && r.phase == JobPhase.PAUSED) r.freeBytes else null,
            installStep = if (r.phase == JobPhase.INSTALLING) a.installStep else null,
            installDoneBytes = if (r.phase == JobPhase.INSTALLING) a.installDone else 0,
            installTotalBytes = if (r.phase == JobPhase.INSTALLING) a.installTotal else 0,
        )
        job.value = j
        needsService.value = needsService(j)
    }

    private fun needsService(j: DownloadJob): Boolean = when (j.phase) {
        JobPhase.CHECKING, JobPhase.DOWNLOADING, JobPhase.UNPACKING, JobPhase.READY, JobPhase.INSTALLING -> true
        JobPhase.WAITING -> j.wait == WaitReason.NO_NETWORK || j.wait == WaitReason.NOT_UNMETERED || j.wait == WaitReason.RETRYING
        else -> false
    }

    private fun folderName(p: CatalogPack) = "${p.id}-${p.packVersion}"

    private fun insideRoot(f: File): Boolean = insideFolder(root, f)

    private fun insideFolder(folder: File, f: File): Boolean {
        val base = folder.canonicalFile.toPath()
        val target = f.canonicalFile.toPath()
        return target.startsWith(base) && target != base
    }

    private fun deleteAllBut(folder: File, keep: String) {
        folder.listFiles()?.forEach { if (it.name != keep) it.deleteRecursively() }
    }

    private fun fileMatches(f: File, bytes: Long, sha: String): Boolean =
        f.isFile && f.length() == bytes && sha256(f.readBytes()) == sha

    private fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it.toInt() and 0xff) }

    @Suppress("unused")
    private suspend fun stillActive() = currentCoroutineContext().isActive
}
