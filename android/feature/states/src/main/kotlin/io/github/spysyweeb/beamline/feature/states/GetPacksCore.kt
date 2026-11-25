package io.github.spysyweeb.beamline.feature.states

import android.content.Context
import android.util.Log
import io.github.spysyweeb.beamline.api.data.InstallStatus
import io.github.spysyweeb.beamline.api.data.InstalledData
import io.github.spysyweeb.beamline.api.data.PackArchive
import io.github.spysyweeb.beamline.api.data.PackInfo
import io.github.spysyweeb.beamline.download.CatalogPack
import io.github.spysyweeb.beamline.download.CatalogSource
import io.github.spysyweeb.beamline.download.CatalogState
import io.github.spysyweeb.beamline.download.DownloadEngine
import io.github.spysyweeb.beamline.download.DownloadJob
import io.github.spysyweeb.beamline.download.DownloadRules
import io.github.spysyweeb.beamline.download.JobKind
import io.github.spysyweeb.beamline.download.JobPhase
import io.github.spysyweeb.beamline.download.NetworkState
import io.github.spysyweeb.beamline.download.StartResult
import io.github.spysyweeb.beamline.download.WaitReason
import io.github.spysyweeb.beamline.download.continuesByItself
import io.github.spysyweeb.beamline.download.handoffFor
import io.github.spysyweeb.beamline.download.net.CatalogAddress
import io.github.spysyweeb.beamline.download.net.PackHttp
import io.github.spysyweeb.beamline.feature.states.map.StateShapes
import io.github.spysyweeb.beamline.feature.states.model.Continuing
import io.github.spysyweeb.beamline.feature.states.model.Dialog
import io.github.spysyweeb.beamline.feature.states.model.FileChoice
import io.github.spysyweeb.beamline.feature.states.model.FirstRun
import io.github.spysyweeb.beamline.feature.states.model.JobSwitch
import io.github.spysyweeb.beamline.feature.states.model.SheetKind
import io.github.spysyweeb.beamline.feature.states.model.StatesInput
import io.github.spysyweeb.beamline.feature.states.model.StatesPresenter
import io.github.spysyweeb.beamline.feature.states.model.StatesUi
import io.github.spysyweeb.beamline.feature.states.model.Texts
import io.github.spysyweeb.beamline.settings.SettingsStore
import io.github.spysyweeb.beamline.units.UnitFormatter
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.time.ZoneId

/** What one open States screen is showing (not persisted: 2.12 "Not persisted"). */
internal data class ScreenState(
    val selected: String? = null,
    val sheetOpen: Boolean = false,
    val sheetPack: CatalogPack? = null,
    val sheetKind: SheetKind = SheetKind.STATE,
    val query: String = "",
    val chooser: List<String>? = null,
    val dialog: Dialog? = null,
    val mapHint: Boolean = false,
    val readyPackId: String? = null,
    val removeProblem: Pair<String, String>? = null,
    val file: FileChoice? = null,
    val fileArchive: PackArchive? = null,
    /** A file install the installer refused: the pack's name and the installer's words (Details only). */
    val fileProblem: Pair<String, String>? = null,
)

/** A file install started from a screen (not persisted: docs/architecture.md 2.12). */
internal data class FileInstall(val packId: String, val name: String, val firstRun: Boolean)

/** Process-wide flags the progress view shows. */
internal data class RunFlags(
    val continuing: Boolean = false,
    val switched: Boolean = false,
    val serviceRefused: Boolean = false,
    /** When bytes first moved after onAppVisible continued the job (epoch ms; 0: not yet): model/Continuing.kt. */
    val movingSinceMs: Long = 0,
)

/**
 * The work behind [GetPacks] (one per process, created by I in AppGraph): the catalog source, the
 * engine, ui.json, the first-run rule, the map chip, show-on-map, and the actions of the screens.
 * Nothing here touches the network on its own (rule N4): the list is fetched when a screen opens or
 * the user asks, a download only after a tap or as the continuation of one.
 */
internal class GetPacksCore(
    val context: Context,
    val installed: InstalledData,
    val settings: SettingsStore,
    val units: StateFlow<UnitFormatter>,
    val config: GetPacksConfig,
    val scope: CoroutineScope,
) {
    val rules: DownloadRules = DownloadRules.bundled()
    val address: CatalogAddress? = try {
        CatalogAddress.parse(config.catalogUrl, config.allowCleartext)
    } catch (_: IllegalArgumentException) {
        Log.w(TAG, "catalog address refused by the address rules; downloads are off")
        null
    }
    val watcher: AndroidNetworkWatcher? = address?.let { AndroidNetworkWatcher(context, it) }
    val network: StateFlow<NetworkState> = watcher?.state ?: MutableStateFlow(NetworkState.NONE)
    private val http: PackHttp? = address?.let { PackHttp(it, rules, TaggingSocketFactory { watcher?.boundNetwork() }) }
    val source: CatalogSource? = address?.let {
        CatalogSource(it, http!!, config.stateDir, rules, config.minManifestVersion, config.maxManifestVersion, network)
    }
    val handoff = handoffFor(installed)
    val engine: DownloadEngine? = address?.let {
        DownloadEngine(config.downloadsDir, http!!, source!!, handoff, installed, network, config.freeBytes, rules, scope, Dispatchers.IO)
    }
    val catalog: StateFlow<CatalogState> = source?.state ?: MutableStateFlow(CatalogState.Off)
    val job: StateFlow<DownloadJob?> = engine?.job ?: MutableStateFlow(null)
    /**
     * One task at a time on Dispatchers.IO, in the order asked for: the engine's actions (they write
     * job.json with fsync; Cancel deletes the job folder) and every read and write of ui.json, so the
     * main thread does no file work (docs/architecture.md 1.2) and a user's taps keep their order.
     */
    val actions: CoroutineDispatcher = Dispatchers.IO.limitedParallelism(1)
    private val ui = UiStore(File(config.stateDir, "ui.json"), scope, actions)
    val choices: StateFlow<UiChoices> get() = ui.state
    val screen = MutableStateFlow(ScreenState())
    val flags = MutableStateFlow(RunFlags())
    /** Parsed on first use, off the main thread (the screen's input flow runs on Dispatchers.IO). */
    val shapes: StateShapes by lazy {
        context.assets.open("states/us_states.json").use { StateShapes.parse(it.readBytes().decodeToString()) }
    }
    @Volatile private var recovered = false

    /** An unfinished download was looked for (onAppVisible's recover), or there is no downloader. */
    private val jobRecovered = MutableStateFlow(engine == null)

    /** The notification question is on screen: asked, not yet answered (1.1 step 5). */
    private val notificationQuestion = MutableStateFlow(false)
    /** The file install started from a screen, until its result is handled. */
    val fileInstall = MutableStateFlow<FileInstall?>(null)

    /** False until ui.json has been read as well as the first scan, so a remembered "Not now" never shows the picker for a moment. */
    val showFirstRunPicker: StateFlow<Boolean> = combine(installed.installed, ui.state, ui.loaded, job, fileInstall) { set, c, loaded, j, f ->
        loaded && FirstRun.show(set, c, j, fileInstallFromFirstRun = f?.firstRun == true)
    }.stateIn(scope, SharingStarted.Eagerly, false)

    /**
     * True once [showFirstRunPicker] is decided: ui.json read, the first scan done and an unfinished
     * download looked for. The app holds its splash reveal until then, so the map does not show for a
     * moment before the first-run picker of an unfinished first-run download.
     */
    val firstRunDecided: StateFlow<Boolean> = combine(ui.loaded, installed.installed, jobRecovered) { loaded, set, r ->
        loaded && set.scanned && r
    }.stateIn(scope, SharingStarted.Eagerly, false)

    val mapChip: StateFlow<MapChip?> = combine(job, installed.installed, catalog, ui.state) { j, set, c, choices ->
        // a download or the first-run choice first; else an installed state's newer map data (item 40)
        (StatesPresenter.chip(j, set) ?: StatesPresenter.updateChip(StatesPresenter.updates(c, set, choices.updatesSeen)))
            ?.let { MapChip(it.first, it.second) }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    val showOnMap: StateFlow<PackInfo?> = combine(ui.state, installed.installed) { c, set ->
        c.showOnMap?.let { id -> set.packs.firstOrNull { it.packId == id } }
    }.stateIn(scope, SharingStarted.Eagerly, null)

    init {
        // A phone that already has any pack folder never sees the picker; the choice is written then (1.5).
        scope.launch {
            installed.installed.collect { set -> ui.update { FirstRun.afterScan(it, set) } }
        }
        // A file install's end (1.4 "Using a file"): on the first run the map opens on the state (1.1 step 6),
        // on the States screen the "is ready" banner; a refusal shows the installer's words under Details.
        scope.launch {
            installed.install.collect { st ->
                val f = fileInstall.value ?: return@collect
                when (st) {
                    is InstallStatus.Done -> {
                        if (f.firstRun) ui.update { it.copy(showOnMap = f.packId) } else screen.update { it.copy(readyPackId = f.packId) }
                        fileInstall.value = null
                        installed.dismissInstallResult()
                    }
                    is InstallStatus.Failed -> screen.update { it.copy(fileProblem = f.name to st.message) }
                    is InstallStatus.Cancelled -> fileProblemDismissed()
                    else -> Unit
                }
            }
        }
        scope.launch {
            var prev: DownloadJob? = null
            job.collect { j ->
                // "A newer Pennsylvania was published. Downloading that one." (1.4): the engine switched the job to a newer build.
                val before = prev
                prev = j
                flags.update { it.copy(switched = JobSwitch.flag(it.switched, before, j)) }
                if (j == null) return@collect
                if (j.phase == JobPhase.DONE) {
                    if (j.firstRun) ui.update { it.copy(showOnMap = j.packId, firstRunPickerDone = true) }
                    screen.update { it.copy(readyPackId = j.packId) }
                    flags.value = RunFlags()
                }
                // "Continuing the download…" (1.1 step 7, 1.4): until bytes move again, then 3 s more (model/Continuing.kt).
                flags.update { f ->
                    val c = Continuing.next(Continuing.State(f.continuing, f.movingSinceMs), j, System.currentTimeMillis())
                    f.copy(continuing = c.on, movingSinceMs = c.movingSinceMs)
                }
            }
        }
        // The in-process watcher (6.4 rule 5): a download held by a trip or a busy installer is installed when that ends.
        scope.launch {
            combine(job, installed.installed, installed.install) { j, set, st -> Triple(j, set, st) }.collect { (j, set, st) ->
                if (j?.phase != JobPhase.WAITING) return@collect
                val go = (j.wait == WaitReason.TRIP_IN_PROGRESS && set.changesHeldBy == null) ||
                    (j.wait == WaitReason.INSTALLER_BUSY && st !is InstallStatus.Running && set.changesHeldBy == null)
                if (go) runEngine()
            }
        }
    }

    /** Starts the service (or, when Android refuses, the engine in this scope). */
    fun runEngine() {
        val e = engine ?: return
        if (DownloadService.start(context)) return
        flags.update { it.copy(serviceRefused = true) }
        scope.launch(Dispatchers.IO) { e.run() }
    }

    /** Runs [action] on [actions] (after every earlier one); the service waits for the returned job before it runs the engine. */
    fun launchAction(action: (DownloadEngine) -> Unit): Job? {
        val e = engine ?: return null
        return scope.launch(actions) { action(e) }
    }

    fun onAppVisible() {
        val e = engine ?: return
        scope.launch(actions) {
            if (!recovered) {
                recovered = true
                e.recover()
            }
            jobRecovered.value = true
            val j = e.job.value ?: return@launch
            if (j.continuesByItself()) {
                flags.update { Continuing.started().let { c -> it.copy(continuing = c.on, movingSinceMs = c.movingSinceMs) } }
                e.resume()
                runEngine()
            } else if (e.needsService.value) {
                runEngine()
            }
        }
    }

    /**
     * The screen opened: the cache first, then a fetch of the list unless it is under
     * [SCREEN_FRESH_MS] old (0.1.32, the owner, 2026-11-25: "Maybe we can have it check if the user
     * opens the states tab?": so each state's Update button is there at once; before, a list up to
     * 6 h old (N4) hid a new pack until "Check again"). The updates it then lists count as seen (item
     * 40): the map's chip stops naming them.
     */
    fun screenOpened() {
        val s = source ?: return
        scope.launch {
            s.loadCache()
            val checked = (catalog.value as? CatalogState.Ready)?.view?.checkedAtMs
            s.refresh(force = listIsStale(checked, System.currentTimeMillis(), SCREEN_FRESH_MS))
            markUpdatesSeen()
        }
    }

    private fun markUpdatesSeen() {
        val shown = StatesPresenter.updates(catalog.value, installed.installed.value, emptyMap())
        if (shown.isEmpty()) return
        ui.update { c -> c.copy(updatesSeen = c.updatesSeen + shown.associate { (pack, entry) -> pack.packId to entry.packVersion }) }
    }

    /**
     * Item 40 (the owner, 2026-11-24; I calls it when the app becomes visible and the user allows it:
     * Online features, "Check for map updates"): the list of states only (never a pack), fetched when
     * the last fetch is [UPDATE_CHECK_AFTER_MS] old or more, or there is none, or it lies in the future.
     */
    fun checkForUpdates() {
        val s = source ?: return
        scope.launch {
            s.loadCache()
            val checked = (catalog.value as? CatalogState.Ready)?.view?.checkedAtMs
            if (listIsStale(checked, System.currentTimeMillis(), UPDATE_CHECK_AFTER_MS)) s.refresh(force = true)
        }
    }

    fun checkAgain() {
        val s = source ?: return
        scope.launch { s.refresh(force = true) }
    }

    /**
     * What the screen shows, made off the main thread (Dispatchers.IO: the shapes are parsed there on
     * first use, and free space is a file-system call); a new value only when the texts change. The
     * clock ticks every 2 s so free space and the sheets' estimates stay current.
     */
    fun ui(mode: StatesMode, debug: Boolean): Flow<StatesUi> =
        input(mode, debug).map { StatesPresenter.present(it) }.distinctUntilChanged().flowOn(Dispatchers.IO)

    private val ticker: Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(2_000)
        }
    }

    fun input(mode: StatesMode, debug: Boolean): Flow<StatesInput> = combine(
        combine(catalog, installed.installed, installed.install, job) { c, set, st, j -> Quad(c, set, st, j) },
        combine(network, units, settings.settings, ticker) { n, u, s, t -> Quad2(n, u, s, t) },
        screen, combine(flags, fileInstall, notificationQuestion) { f, fi, q -> Triple(f, fi, q) }, ui.state,
    ) { a, b, sc, ff, c ->
        val (f, fi, question) = ff
        val nowMs = b.t
        val names = shapes.states.associate { it.code to it.name }
        StatesInput(
            mode = mode, names = names, neighbours = shapes.states.associate { it.code to it.neighbours },
            catalog = a.c, installed = a.set, install = a.st, job = a.j, network = b.n, units = b.u,
            nowMs = nowMs, zone = ZoneId.systemDefault(), freeBytes = freeNow(), moves = handoff.moves,
            routingSetBytes = config.routingSetBytes(), marginBytes = rules.spaceMarginBytes, selected = sc.selected, sheetPack = sc.sheetPack,
            sheetKind = sc.sheetKind, sheetOpen = sc.sheetOpen, includeAddresses = b.s.includeAddresses, query = sc.query,
            chooser = sc.chooser, dialog = sc.dialog, mapHint = sc.mapHint, notificationsOff = StatesPresenter.notificationsOffShown(
                enabled = notificationsEnabled(), askable = notificationsAskable(), asked = c.notificationsAsked, questionOpen = question,
            ),
            continuing = f.continuing, switched = f.switched, serviceRefused = f.serviceRefused, readyPackId = if (mode == StatesMode.MANAGE) sc.readyPackId else null,
            removeProblem = sc.removeProblem, changesOptionalParts = installed.changesOptionalParts, debug = debug, file = sc.file,
            fileInstallName = fi?.name, fileProblem = sc.fileProblem,
        )
    }

    /** File.usableSpace is 0 for a folder that does not exist, so the downloads folder is made first. */
    fun freeNow(): Long {
        config.downloadsDir.mkdirs()
        return config.freeBytes(config.downloadsDir)
    }

    private data class Quad(val c: CatalogState, val set: io.github.spysyweeb.beamline.api.data.InstalledSet, val st: InstallStatus, val j: DownloadJob?)
    private data class Quad2(val n: NetworkState, val u: UnitFormatter, val s: io.github.spysyweeb.beamline.settings.Settings, val t: Long)

    fun notificationsEnabled(): Boolean = androidx.core.app.NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Android 13+ without POST_NOTIFICATIONS: the question can still be asked. */
    private fun notificationsAskable(): Boolean = android.os.Build.VERSION.SDK_INT >= 33 &&
        context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED

    // --------------------------------------------------------------- actions of the screens

    fun select(code: String, kind: SheetKind = SheetKind.STATE) {
        val entry = (catalog.value as? CatalogState.Ready)?.view?.catalog?.packs?.firstOrNull { it.state == code }
            ?: when (val c = catalog.value) {
                is CatalogState.Failed -> c.cached?.catalog?.packs?.firstOrNull { it.state == code }
                is CatalogState.Loading -> c.cached?.catalog?.packs?.firstOrNull { it.state == code }
                else -> null
            }
        screen.update { it.copy(selected = code, sheetOpen = true, sheetPack = entry, sheetKind = kind, chooser = null, mapHint = false) }
    }

    fun closeSheet() {
        screen.update { it.copy(sheetOpen = false, sheetPack = null, sheetKind = SheetKind.STATE, file = null, fileArchive = null) }
    }

    fun setIncludeAddresses(on: Boolean) = settings.update { it.copy(includeAddresses = on) }

    /**
     * Download (or Update, or Add house numbers) from the open sheet. The engine's start (it writes
     * job.json) runs on [actions]; [then] gets the result on the main thread (the screen asks for the
     * notification permission after a start).
     */
    fun download(allowMetered: Boolean, firstRun: Boolean, then: (StartResult?) -> Unit = {}) {
        val e = engine ?: return then(null)
        val sc = screen.value
        val entry = sc.sheetPack ?: return then(null)
        val inst = installed.installed.value.packs.firstOrNull { it.packId == entry.id }
        val kind = when (sc.sheetKind) {
            SheetKind.ADD_HOUSE_NUMBERS -> JobKind.ADD_OPTIONAL
            SheetKind.UPDATE -> JobKind.UPDATE
            SheetKind.STATE -> if (inst != null) JobKind.UPDATE else JobKind.INSTALL
        }
        val include = if (kind == JobKind.ADD_OPTIONAL) true else settings.settings.value.includeAddresses
        scope.launch(actions) {
            val result = e.start(entry, kind, include, allowMetered, firstRun)
            if (result == StartResult.Started) {
                if (firstRun) ui.applyNow { FirstRun.picked(it) }
                closeSheet()
                runEngine()
            }
            withContext(Dispatchers.Main.immediate) { then(result) }
        }
    }

    fun pause() {
        launchAction { it.pause() }
    }

    fun resume() {
        launchAction {
            it.resume()
            runEngine()
        }
    }

    fun useMobileData() {
        launchAction { e ->
            if (e.job.value?.phase == JobPhase.PAUSED) e.resume(allowMetered = true) else e.allowMetered()
            runEngine()
        }
    }

    /**
     * Cancel confirmed: the job folder is deleted on [actions]. The remembered choice changes first,
     * in the same task, so the first-run picker never sees "no job, choice made" for a moment (it
     * would close).
     */
    fun cancel() {
        val j = job.value
        screen.update { it.copy(dialog = null) }
        scope.launch(actions) {
            ui.applyNow { FirstRun.afterCancel(it, j, installed.installed.value) }
            engine?.cancel()
        }
    }

    fun dismiss() {
        val j = job.value
        scope.launch(actions) {
            ui.applyNow { FirstRun.afterDismiss(it, j, installed.installed.value) }
            engine?.dismiss()
        }
    }

    fun notNow() = ui.update { FirstRun.picked(it) }

    fun notificationsAsked() {
        notificationQuestion.value = true
        ui.update { it.copy(notificationsAsked = true) }
    }

    /** The notification question was answered (either way). */
    fun notificationsAnswered() {
        notificationQuestion.value = false
    }

    fun showOnMapRequest(packId: String) = ui.update { it.copy(showOnMap = packId) }

    fun showOnMapDone() = ui.update { it.copy(showOnMap = null) }

    fun remove(packId: String) {
        screen.update { it.copy(dialog = null) }
        scope.launch {
            val problem = installed.remove(packId)
            screen.update { it.copy(removeProblem = problem?.let { packId to it }) }
        }
    }

    fun removeHouseNumbers(packId: String) {
        screen.update { it.copy(dialog = null) }
        scope.launch {
            val problem = installed.removeOptionalParts(packId)
            screen.update { it.copy(removeProblem = problem?.let { packId to it }) }
        }
    }

    /**
     * "Use a file instead": the installer reads the manifest first (inspect, on Dispatchers.IO: it
     * reads the file), nothing is copied yet. A file it cannot read is refused as 1.4 refuses a pack:
     * "<file> can't be installed" / "Nothing was changed on this phone." [Details] [OK], Details
     * saying Texts.FILE_NOT_A_PACK (a draft text).
     */
    fun fileChosen(label: String, archive: PackArchive) {
        scope.launch(Dispatchers.IO) {
            val info = try {
                installed.inspect(archive)
            } catch (e: Exception) {
                Log.w(TAG, "file not readable: ${e.javaClass.simpleName}") // log-ok: exception class name
                screen.update { it.copy(file = null, fileArchive = null, fileProblem = label to Texts.FILE_NOT_A_PACK) }
                return@launch
            }
            screen.update {
                it.copy(
                    file = FileChoice(label, info.name, info.osmSnapshot, info.neededBytes, info.addressesBytes, info.problems, info.packId),
                    fileArchive = archive, sheetOpen = false, fileProblem = null,
                )
            }
        }
    }

    /**
     * Install from the file sheet. The choice is remembered when the install starts (1.5); on the
     * first-run screen the picker stays while it runs (its progress, "Keep Beamline open...") and the
     * map opens on the state when it is done.
     */
    fun installFile(firstRun: Boolean): Boolean {
        val sc = screen.value
        val archive = sc.fileArchive ?: return false
        val choice = sc.file ?: return false
        if (job.value?.let { it.phase != JobPhase.DONE && it.phase != JobPhase.FAILED } == true) return false
        fileInstall.value = FileInstall(choice.packId, choice.name, firstRun)
        val ok = installed.startInstall(archive, settings.settings.value.includeAddresses)
        if (ok) {
            ui.update { FirstRun.picked(it) }
            screen.update { it.copy(file = null, fileArchive = null, fileProblem = null) }
        } else {
            fileInstall.value = null
        }
        return ok
    }

    /**
     * OK on a refused file install: the result is dismissed; on the first run the picker shows again
     * (as 1.5 does for a download). On [actions], and the remembered choice first, so the first-run
     * picker does not close for a moment in between.
     */
    fun fileProblemDismissed() {
        val f = fileInstall.value
        scope.launch(actions) {
            if (f?.firstRun == true && installed.installed.value.packs.isEmpty()) ui.applyNow { it.copy(firstRunPickerDone = false) }
            fileInstall.value = null
            screen.update { it.copy(fileProblem = null) }
            installed.dismissInstallResult()
        }
    }

    suspend fun awaitScan() = installed.installed.first { it.scanned }

    private companion object {
        const val TAG = "Beamline.states"
        /** Item 40: the daily map-update check fetches the list at most this often. */
        const val UPDATE_CHECK_AFTER_MS = 24L * 60 * 60 * 1000

        /** The States screen fetches the list again on opening unless it is younger than this (0.1.32). */
        const val SCREEN_FRESH_MS = 60L * 1000
    }
}

/** A list of states last fetched at [checkedAtMs] is to be fetched again: none yet, [maxAgeMs] old or more, or from the future (a clock moved back). */
internal fun listIsStale(checkedAtMs: Long?, nowMs: Long, maxAgeMs: Long): Boolean =
    checkedAtMs == null || nowMs - checkedAtMs >= maxAgeMs || checkedAtMs > nowMs
