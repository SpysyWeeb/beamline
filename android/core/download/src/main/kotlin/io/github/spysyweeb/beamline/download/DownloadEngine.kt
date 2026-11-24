// FILE: core/download/src/main/kotlin/io/github/spysyweeb/beamline/download/DownloadEngine.kt
package io.github.spysyweeb.beamline.download

import io.github.spysyweeb.beamline.api.data.InstalledData
import io.github.spysyweeb.beamline.download.net.PackHttp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * Downloads one state pack at a time into [root] (`<external files>/downloads/`, the volume of the
 * packs folder) and hands it to the installer through [handoff]. Persists everything it needs to
 * go on after process death in `<root>/<pack_id>-<pack_version>/job.json` (section 6.5); the bytes
 * themselves are the truth for progress. UI-free and Android-free: [network] and [freeBytes] come
 * from the platform; [run] is called by the Android service.
 */
class DownloadEngine(
    private val root: File,
    private val http: PackHttp,
    private val catalogs: CatalogSource,
    private val handoff: PackHandoff,
    private val installed: InstalledData,
    private val network: StateFlow<NetworkState>,
    private val freeBytes: (File) -> Long,
    private val rules: DownloadRules,
    private val scope: CoroutineScope,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /** The one job (null when there is none). Updated at most every progress_every_ms while bytes move. */
    val job: StateFlow<DownloadJob?> get() = core.job

    /** The work behind this class (EngineCore.kt). */
    internal val core = EngineCore(root, http, catalogs, handoff, installed, network, freeBytes, rules, scope, io, clock)

    /**
     * True while [job] is in CHECKING, DOWNLOADING, UNPACKING, READY (not held), INSTALLING, or
     * WAITING for NO_NETWORK, NOT_UNMETERED (each at most wait_for_network_max_min) or RETRYING
     * (at most max_attempts_without_progress attempts): the service must run.
     */
    val needsService: StateFlow<Boolean> get() = core.needsService

    /**
     * Once per process, before any other call: waits for the installer's first scan
     * (InstalledSet.scanned), reads job.json files, deletes folders of finished or unreadable jobs
     * and every folder in [root] that is not a job (a FAILED job keeps its job.json until
     * [dismiss]). A job whose pack is installed
     * at its pack_version or newer with every part it chose becomes DONE (folder deleted). A job
     * that was running gets one more death counted: PAUSED(KEEPS_STOPPING) when the count reaches
     * max_deaths_without_progress, else PAUSED(APP_ENDED). The exit reason is not consulted.
     */
    suspend fun recover(): Unit = core.recover()

    /**
     * Creates the job (phase CHECKING) and persists it; the caller then starts the service.
     * ADD_OPTIONAL fetches the manifest and the optional parts only, and is refused (NOT_SUPPORTED)
     * unless the installer adds parts and the installed pack has [pack]'s pack_version.
     */
    fun start(pack: CatalogPack, kind: JobKind, includeOptional: Boolean, allowMetered: Boolean, firstRun: Boolean): StartResult = core.start(pack, kind, includeOptional, allowMetered, firstRun)

    /**
     * Works on the job until it is DONE, FAILED or PAUSED, or WAITING for TRIP_IN_PROGRESS or
     * INSTALLER_BUSY (then the in-process watcher of GetPacks finishes it). Returns at once when there
     * is nothing to do. Cancelling the calling coroutine stops the work and leaves the job as it is
     * on disk (the service's end, the process's end).
     */
    suspend fun run(): Unit = core.run()

    /**
     * PAUSED(USER): stops the transfer and any unpacking at its next buffer (a half-inflated part
     * file is deleted, its verified transfer file kept); keeps the files. Not while INSTALLING.
     */
    fun pause(): Unit = core.pause()

    /** From PAUSED back to CHECKING; [allowMetered] non-null changes the job's choice. */
    fun resume(allowMetered: Boolean? = null): Unit = core.resume(allowMetered)

    /** "Use mobile data" for this job (WAITING NOT_UNMETERED goes on at once). */
    fun allowMetered(): Unit = core.allowMetered()

    /** Stops and deletes the job folder; [job] becomes null. The UI confirms first. Not possible while INSTALLING. */
    fun cancel(): Unit = core.cancel()

    /** Forgets a DONE or FAILED job ([job] becomes null); a FAILED job's folder (job.json only) is deleted. */
    fun dismiss(): Unit = core.dismiss()

    /** The service got Service.onTimeout (Android 15+): PAUSED(SYSTEM_STOPPED) within a second. */
    fun stopForSystem(): Unit = core.stopForSystem()
}
