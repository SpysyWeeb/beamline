package io.github.spysyweeb.beamline.feature.states.model

import io.github.spysyweeb.beamline.api.data.InstallStatus
import io.github.spysyweeb.beamline.api.data.InstallStep
import io.github.spysyweeb.beamline.api.data.InstalledSet
import io.github.spysyweeb.beamline.api.data.PackInfo
import io.github.spysyweeb.beamline.download.CatalogPack
import io.github.spysyweeb.beamline.download.CatalogProblem
import io.github.spysyweeb.beamline.download.CatalogState
import io.github.spysyweeb.beamline.download.CatalogView
import io.github.spysyweeb.beamline.download.DownloadJob
import io.github.spysyweeb.beamline.download.FailReason
import io.github.spysyweeb.beamline.download.JobKind
import io.github.spysyweeb.beamline.download.JobPhase
import io.github.spysyweeb.beamline.download.NetworkState
import io.github.spysyweeb.beamline.download.PauseReason
import io.github.spysyweeb.beamline.download.Space
import io.github.spysyweeb.beamline.download.Unusable
import io.github.spysyweeb.beamline.download.WaitReason
import io.github.spysyweeb.beamline.feature.states.StatesMode
import io.github.spysyweeb.beamline.units.UnitFormatter
import java.time.ZoneId

/** Per-state status (6.7). */
enum class StateStatus { INSTALLED, UPDATE_AVAILABLE, IN_PROGRESS, AVAILABLE, NOT_AVAILABLE, NEEDS_NEWER_APP, LOADING, UNKNOWN }

/** What the user did; the screen sends these to GetPacks. */
sealed interface StatesEvent {
    data class Select(val code: String) : StatesEvent
    data class ChooseOne(val code: String) : StatesEvent
    data object CloseSheet : StatesEvent
    data class Query(val text: String) : StatesEvent
    data class HouseNumbers(val on: Boolean) : StatesEvent
    data class Download(val allowMetered: Boolean) : StatesEvent
    data object Pause : StatesEvent
    data object Continue : StatesEvent
    data object UseMobileData : StatesEvent
    data object Cancel : StatesEvent
    data object ConfirmCancel : StatesEvent
    data object Dismiss : StatesEvent
    data object FreeUpSpace : StatesEvent
    data object CheckAgain : StatesEvent
    data class Remove(val packId: String) : StatesEvent
    data class ConfirmRemove(val packId: String) : StatesEvent
    data class Update(val packId: String) : StatesEvent
    data class AddHouseNumbers(val packId: String) : StatesEvent
    data class RemoveHouseNumbers(val packId: String) : StatesEvent
    data class ConfirmRemoveHouseNumbers(val packId: String) : StatesEvent
    data object Details : StatesEvent
    data object TurnOnNotifications : StatesEvent
    data object UseFile : StatesEvent
    data object NotNow : StatesEvent
    data object OpenMap : StatesEvent
    data object TapOutside : StatesEvent
    data object CloseDialog : StatesEvent
    data class ShowOnMap(val packId: String) : StatesEvent
    data object CloseBanner : StatesEvent
    data object InstallFile : StatesEvent
    /** OK on a file install the installer refused. */
    data object DismissFileProblem : StatesEvent
}

data class Action(val label: String, val event: StatesEvent, val enabled: Boolean = true)

/** What kind of sheet is open. */
enum class SheetKind { STATE, UPDATE, ADD_HOUSE_NUMBERS }

/** A dialog the screen shows over everything. */
sealed interface Dialog {
    data object CancelJob : Dialog
    data class Remove(val packId: String) : Dialog
    data class RemoveHouseNumbers(val packId: String) : Dialog
    /** The installer's own words (Details): shown on screen only, never logged (rule N3). */
    data class Details(val text: String) : Dialog
}

/** A file the user picked, read by the installer's inspect (1.4 "Using a file"). */
data class FileChoice(val fileName: String, val name: String, val snapshot: String, val neededBytes: Long, val addressesBytes: Long?, val problems: List<String>, val packId: String = "")

data class StatesInput(
    val mode: StatesMode,
    /** Every state the drawn map knows: USPS code -> name. */
    val names: Map<String, String>,
    /** USPS code -> neighbouring codes (the shapes' neighbours). */
    val neighbours: Map<String, List<String>> = emptyMap(),
    val catalog: CatalogState,
    val installed: InstalledSet,
    val install: InstallStatus = InstallStatus.Idle,
    val job: DownloadJob?,
    val network: NetworkState,
    val units: UnitFormatter,
    val nowMs: Long,
    val zone: ZoneId,
    val freeBytes: Long,
    val moves: Boolean,
    val routingSetBytes: Long = 0,
    val marginBytes: Long = 200_000_000,
    val selected: String? = null,
    /** The catalog entry the open sheet started with: a refresh does not change it (1.1 step 4). */
    val sheetPack: CatalogPack? = null,
    val sheetKind: SheetKind = SheetKind.STATE,
    val sheetOpen: Boolean = false,
    val includeAddresses: Boolean = true,
    val query: String = "",
    val chooser: List<String>? = null,
    val dialog: Dialog? = null,
    val mapHint: Boolean = false,
    val notificationsOff: Boolean = false,
    /** The job was continued by onAppVisible after the process ended. */
    val continuing: Boolean = false,
    /** The job was switched to a newer build after a 404. */
    val switched: Boolean = false,
    /** Android refused the foreground service: GetPacks runs the engine itself. */
    val serviceRefused: Boolean = false,
    /** States screen: the pack just installed ("Ohio is ready."). */
    val readyPackId: String? = null,
    /** A removal the installer refused: pack id and the installer's words. */
    val removeProblem: Pair<String, String>? = null,
    /** InstalledData.changesOptionalParts. */
    val changesOptionalParts: Boolean = false,
    /** BuildConfig.DEBUG: the offline banner names the condition that failed. */
    val debug: Boolean = false,
    val file: FileChoice? = null,
    /** A file install started from a screen is running or waits for its result to be seen: the pack's name. */
    val fileInstallName: String? = null,
    /** A file install the installer refused: the pack's name and the installer's words (Details only). */
    val fileProblem: Pair<String, String>? = null,
)

data class StateRowUi(
    val code: String,
    val name: String,
    val status: StateStatus,
    val line: String?,
    /** An installed pack: when it was built ([Texts.builtLine]); null for a pack without a build time and for a state not on this phone. */
    val builtLine: String? = null,
    val houseNumbersLine: String? = null,
    val houseNumbersAction: Action? = null,
    val note: String? = null,
    val actions: List<Action> = emptyList(),
    /** For TalkBack: the row's status in words. */
    val stateDescription: String,
    val progress: Float? = null,
    val packId: String? = null,
    val highlighted: Boolean = false,
)

data class HouseNumbersUi(val checked: Boolean, val label: String, val hint: String, val sourcesLine: String?)

data class SheetUi(
    val code: String,
    val title: String,
    val lines: List<String>,
    val houseNumbers: HouseNumbersUi? = null,
    val connectionLine: String? = null,
    val spaceLine: String? = null,
    val primary: Action? = null,
    val secondary: Action? = null,
    val creditLine: String? = null,
    val privacyLine: String? = null,
)

data class JobUi(
    val title: String,
    val line: String,
    val hint: String?,
    /** 0..1; null = indeterminate. */
    val progress: Float?,
    val actions: List<Action>,
    val notificationsLine: String? = null,
    val notificationsAction: Action? = null,
    val keepOpenLine: String? = null,
    val message: String? = null,
    /** False: no progress bar at all (a failed job). True with a null [progress]: a moving bar (work of unknown length). */
    val bar: Boolean = true,
)

data class BannerUi(val text: String, val detail: String? = null, val action: Action? = null, val secondary: Action? = null)

data class DialogUi(val title: String, val text: String?, val confirm: Action, val dismiss: Action?)

data class ChooserUi(val title: String, val rows: List<Pair<String, String>>)

data class StatesUi(
    val mode: StatesMode,
    val title: String,
    val intro: String?,
    val loadingLine: String?,
    val banner: BannerUi?,
    val job: JobUi?,
    val onPhoneTitle: String?,
    val onPhone: List<StateRowUi>,
    val addTitle: String?,
    val availableTitle: String?,
    val available: List<StateRowUi>,
    val notAvailableTitle: String?,
    val notAvailable: List<StateRowUi>,
    val needsNewerTitle: String?,
    val needsNewer: List<StateRowUi>,
    val mapStatus: Map<String, StateStatus>,
    val selected: String?,
    val sheet: SheetUi?,
    val dialog: DialogUi?,
    val chooser: ChooserUi?,
    val query: String,
    /** Null: no search field (nothing to search: the list of states to add is hidden while downloads are off). */
    val searchHint: String?,
    val checkedLine: String?,
    val checkAgain: Action?,
    val bottomBar: List<Action>,
    val mapHint: String?,
    val mapDescription: String,
    val readyBanner: BannerUi?,
    val fileSheet: SheetUi?,
    /** A file install in progress: "Installing Pennsylvania", its step line, 0..1, "Keep Beamline open until the install has finished." */
    val fileProgress: JobUi? = null,
)

/** Pure: every text of section 1 from the state of the world (6.7). */
object StatesPresenter {

    /**
     * 1.1 step 5: "Notifications are off, so Beamline can't tell you when it's done." only after a
     * refusal, not before the question nor while it is on screen. [enabled]: notifications can be
     * shown; [askable]: Android 13+ without POST_NOTIFICATIONS, so the question can come; [asked]: the
     * question was asked once (ui.json); [questionOpen]: asked and not yet answered. Notifications
     * turned off in the system settings (granted, not enabled) show the line at once.
     */
    fun notificationsOffShown(enabled: Boolean, askable: Boolean, asked: Boolean, questionOpen: Boolean): Boolean =
        !enabled && !questionOpen && !(askable && !asked)

    private fun view(c: CatalogState): CatalogView? = when (c) {
        is CatalogState.Ready -> c.view
        is CatalogState.Loading -> c.cached
        is CatalogState.Failed -> c.cached
        else -> null
    }

    /**
     * Item 40: the installed packs whose catalog entry is newer (1.3) and above what the user has seen
     * on the States screen ([seen]: pack_id to pack_version), each with that entry; none without a list.
     */
    fun updates(catalog: CatalogState, installed: InstalledSet, seen: Map<String, Long>): List<Pair<PackInfo, CatalogPack>> {
        val v = view(catalog) ?: return emptyList()
        return installed.packs.mapNotNull { pack ->
            val entry = v.catalog.packs.firstOrNull { it.state == pack.state && it.id == pack.packId } ?: return@mapNotNull null
            if (isNewer(entry, pack) && entry.packVersion > (seen[pack.packId] ?: Long.MIN_VALUE)) pack to entry else null
        }
    }

    /** Item 40: the map's chip for [updates] when no download or first-run chip shows; null for none. */
    fun updateChip(updates: List<Pair<PackInfo, CatalogPack>>): Pair<String, String>? {
        if (updates.isEmpty()) return null
        val text = Texts.chipUpdates(updates.map { it.first.name })
        return text to Texts.chipDescription(text)
    }

    /** A catalog entry newer than the installed pack (1.3). */
    fun isNewer(entry: CatalogPack, pack: PackInfo): Boolean =
        pack.packVersion?.let { entry.packVersion > it } ?: (entry.osmSnapshot > pack.osmSnapshot)

    fun status(code: String, input: StatesInput): StateStatus {
        val job = input.job
        if (job != null && job.state == code && job.phase != JobPhase.DONE) return StateStatus.IN_PROGRESS
        val v = view(input.catalog)
        val pack = input.installed.packs.firstOrNull { it.state == code }
        val entry = v?.catalog?.packs?.firstOrNull { it.state == code }
        if (pack != null) return if (entry != null && entry.id == pack.packId && isNewer(entry, pack)) StateStatus.UPDATE_AVAILABLE else StateStatus.INSTALLED
        if (v == null) return if (input.catalog is CatalogState.Loading) StateStatus.LOADING else StateStatus.UNKNOWN
        if (entry != null) return StateStatus.AVAILABLE
        val unusable = v.catalog.unusable.firstOrNull { it.state == code }
        if (unusable?.reason == Unusable.NEEDS_NEWER_APP) return StateStatus.NEEDS_NEWER_APP
        return StateStatus.NOT_AVAILABLE
    }

    private fun optionalBytes(p: CatalogPack) = p.parts.filter { it.optional }.sumOf { it.bytes }

    private fun optionalCredit(p: CatalogPack) = p.parts.firstOrNull { it.optional }?.credit

    fun present(input: StatesInput): StatesUi {
        val v = view(input.catalog)
        val codes = (input.names.keys + (v?.catalog?.packs?.map { it.state } ?: emptyList()) + input.installed.packs.map { it.state }).toSortedSet()
        fun name(code: String) = input.names[code] ?: v?.catalog?.packs?.firstOrNull { it.state == code }?.name
            ?: input.installed.packs.firstOrNull { it.state == code }?.name ?: code
        val statuses = codes.associateWith { status(it, input) }
        val q = input.query.trim()
        fun matches(code: String) = q.isEmpty() || name(code).contains(q, ignoreCase = true) || code.equals(q, ignoreCase = true)

        val job = input.job?.takeIf { it.phase != JobPhase.DONE || input.mode == StatesMode.FIRST_RUN }
        val listKnown = v != null
        // Managing with downloads off (the owner, 2026-11-21): no list of 49 states that cannot be added here; the
        // banner says so and "Use a file instead" stays. The first-run screen keeps its list (the map's own index).
        val addHidden = input.catalog is CatalogState.Off && input.mode == StatesMode.MANAGE

        // rows
        val onPhone = if (input.mode == StatesMode.MANAGE) input.installed.packs.sortedBy { it.name }.map { installedRow(it, input, v) } else emptyList()
        val installedCodes = input.installed.packs.map { it.state }.toSet()
        val addCodes = codes.filter { it !in installedCodes || input.mode == StatesMode.FIRST_RUN }.filter(::matches).sortedBy { name(it) }
        fun row(code: String): StateRowUi {
            val st = statuses.getValue(code)
            val entry = v?.catalog?.packs?.firstOrNull { it.state == code }
            val line = when (st) {
                StateStatus.AVAILABLE -> entry?.let { Texts.availableLine(it.downloadBytes(input.includeAddresses), it.osmSnapshot) }
                StateStatus.NOT_AVAILABLE -> Texts.NOT_AVAILABLE
                StateStatus.NEEDS_NEWER_APP -> Texts.NEEDS_NEWER_LINE
                StateStatus.IN_PROGRESS -> job?.let { progressLine(it) }
                else -> null
            }
            return StateRowUi(code, name(code), st, line, stateDescription = line ?: name(code), packId = entry?.id, highlighted = code == input.selected,
                progress = if (st == StateStatus.IN_PROGRESS && job != null) fraction(job) else null)
        }
        val available: List<StateRowUi>
        val notAvailable: List<StateRowUi>
        val needsNewer: List<StateRowUi>
        if (listKnown) {
            available = addCodes.filter { statuses[it] == StateStatus.AVAILABLE || statuses[it] == StateStatus.IN_PROGRESS || (input.mode == StatesMode.FIRST_RUN && statuses[it] in setOf(StateStatus.INSTALLED, StateStatus.UPDATE_AVAILABLE)) }.map(::row)
            notAvailable = addCodes.filter { statuses[it] == StateStatus.NOT_AVAILABLE }.map(::row)
            needsNewer = addCodes.filter { statuses[it] == StateStatus.NEEDS_NEWER_APP }.map(::row)
        } else {
            available = if (addHidden) emptyList() else addCodes.map(::row) // neutral, without a size line
            notAvailable = emptyList()
            needsNewer = emptyList()
        }

        val banner = banner(input, v)
        val loading = if (input.catalog is CatalogState.Loading && v == null) Texts.LOADING else null
        val checked = when (val c = input.catalog) {
            is CatalogState.Failed -> c.cached?.let { Texts.listFrom(Texts.date(it.checkedAtMs, input.zone)) }
            is CatalogState.Ready -> if (input.mode == StatesMode.MANAGE) Texts.listChecked(Texts.date(c.view.checkedAtMs, input.zone)) else null
            else -> null
        }
        val bottom = when (input.mode) {
            StatesMode.FIRST_RUN -> listOf(Action(Texts.USE_FILE, StatesEvent.UseFile), if (job != null) Action(Texts.LOOK_AT_MAP, StatesEvent.OpenMap) else Action(Texts.NOT_NOW, StatesEvent.NotNow))
            StatesMode.MANAGE -> listOf(Action(Texts.USE_FILE, StatesEvent.UseFile))
        }
        val readyBanner = input.readyPackId?.let { id ->
            input.installed.packs.firstOrNull { it.packId == id }?.let {
                BannerUi(Texts.ready(it.name), action = Action(Texts.SHOW_ON_MAP, StatesEvent.ShowOnMap(id)), secondary = Action(Texts.OK, StatesEvent.CloseBanner))
            }
        } ?: input.removeProblem?.let { (id, _) ->
            val n = input.installed.packs.firstOrNull { it.packId == id }?.name ?: id
            BannerUi(Texts.couldNotRemove(n), action = Action(Texts.DETAILS, StatesEvent.Details), secondary = Action(Texts.OK, StatesEvent.CloseBanner))
        } ?: input.fileProblem?.let { (n, _) ->
            // 1.4 "The installer rejects the pack", for a file: the installer's words only under Details.
            BannerUi(Texts.cantInstall(n), detail = Texts.NOTHING_CHANGED, action = Action(Texts.DETAILS, StatesEvent.Details), secondary = Action(Texts.OK, StatesEvent.DismissFileProblem))
        }

        return StatesUi(
            mode = input.mode,
            title = if (input.mode == StatesMode.FIRST_RUN) Texts.CHOOSE_TITLE else Texts.STATES_TITLE,
            intro = if (input.mode == StatesMode.FIRST_RUN) Texts.CHOOSE_INTRO else null,
            loadingLine = loading,
            banner = banner,
            job = job?.let { jobUi(it, input) },
            onPhoneTitle = if (input.mode == StatesMode.MANAGE && onPhone.isNotEmpty()) Texts.ON_THIS_PHONE else null,
            onPhone = onPhone,
            addTitle = if (input.mode == StatesMode.MANAGE && !addHidden) Texts.ADD_A_STATE else null,
            availableTitle = if (listKnown && available.isNotEmpty()) Texts.AVAILABLE else null,
            available = available,
            notAvailableTitle = if (notAvailable.isNotEmpty()) Texts.NOT_AVAILABLE else null,
            notAvailable = notAvailable,
            needsNewerTitle = if (needsNewer.isNotEmpty()) Texts.NEEDS_NEWER_SECTION else null,
            needsNewer = needsNewer,
            mapStatus = statuses,
            selected = input.selected,
            sheet = if (input.sheetOpen && input.selected != null) sheet(input.selected, input, v, statuses.getValue(input.selected)) else null,
            dialog = dialog(input),
            chooser = input.chooser?.let { c -> ChooserUi(Texts.WHICH_ONE, c.map { it to name(it) }) },
            query = input.query,
            searchHint = if (addHidden) null else Texts.SEARCH,
            checkedLine = checked,
            checkAgain = if (input.mode == StatesMode.MANAGE && input.catalog !is CatalogState.Off) Action(Texts.CHECK_AGAIN, StatesEvent.CheckAgain) else null,
            bottomBar = bottom,
            mapHint = if (input.mapHint) Texts.MAP_HINT else null,
            mapDescription = if (addHidden) Texts.MAP_DESCRIPTION_NO_LIST else Texts.MAP_DESCRIPTION,
            readyBanner = readyBanner,
            fileSheet = input.file?.let { fileSheet(it, input) },
            fileProgress = fileProgress(input),
        )
    }

    /** The installer's progress for a file install (1.4 "Using a file"), with the keep-open line of docs/architecture.md 4. */
    private fun fileProgress(input: StatesInput): JobUi? {
        val st = input.install as? InstallStatus.Running ?: return null
        val name = input.fileInstallName ?: return null
        val line = when (st.step) {
            InstallStep.MERGING_ROUTING -> Texts.COMBINING
            InstallStep.FINISHING -> Texts.FINISHING
            else -> Texts.CHECKING_FILES
        }
        val progress = if (st.totalBytes > 0) (st.doneBytes.toFloat() / st.totalBytes).coerceIn(0f, 1f) else null
        return JobUi(Texts.installing(name), line, Texts.KEEP_OPEN_INSTALL, progress, emptyList())
    }

    private fun banner(input: StatesInput, v: CatalogView?): BannerUi? {
        val retry = Action(Texts.TRY_AGAIN, StatesEvent.CheckAgain)
        return when (val c = input.catalog) {
            CatalogState.Off -> BannerUi(Texts.DOWNLOADS_OFF)
            is CatalogState.Failed -> when {
                c.problem == CatalogProblem.CLOCK -> BannerUi(Texts.CLOCK_BANNER, action = retry)
                c.problem == CatalogProblem.NEWER_FORMAT -> BannerUi(Texts.NEWER_FORMAT_BANNER)
                v != null -> null // the cached list is used; the footer says so
                c.problem == CatalogProblem.NO_NETWORK -> BannerUi(Texts.OFFLINE_BANNER, detail = if (input.debug) input.network.detail.ifEmpty { null } else null, action = retry)
                else -> BannerUi(Texts.CANT_LOAD_BANNER, action = retry)
            }
            else -> null
        }
    }

    private fun fraction(job: DownloadJob): Float? = when (job.phase) {
        JobPhase.CHECKING -> null
        JobPhase.INSTALLING -> if (job.installTotalBytes > 0) job.installDoneBytes.toFloat() / job.installTotalBytes else null
        else -> if (job.progress.totalBytes > 0) job.progress.doneBytes.toFloat() / job.progress.totalBytes else null
    }

    /** The row line of a state with a job (1.2). */
    fun progressLine(job: DownloadJob): String {
        val p = Texts.percent(job.progress.doneBytes, job.progress.totalBytes)
        return when (job.phase) {
            JobPhase.INSTALLING, JobPhase.READY -> Texts.PROGRESS_INSTALLING
            JobPhase.WAITING -> if (job.wait == WaitReason.NOT_UNMETERED) Texts.progressWaitingWifi(p) else if (job.wait == WaitReason.TRIP_IN_PROGRESS || job.wait == WaitReason.INSTALLER_BUSY) Texts.PROGRESS_INSTALLING else Texts.progressDownloading(p)
            JobPhase.PAUSED -> Texts.progressPaused(p)
            // Section 1 has no row line for a failed job; its files are gone, so not "Paused · 0 %": 1.4's own title.
            JobPhase.FAILED -> Texts.DOWNLOAD_FAILED
            else -> Texts.progressDownloading(p)
        }
    }

    /** A sheet's lines for a state on this phone: what it holds, and when it was built. */
    private fun installedLines(pack: PackInfo): List<String> =
        listOfNotNull(Texts.installedLine(pack.osmSnapshot, pack.bytesOnDisk), pack.packVersion?.let { Texts.builtLine(it) })

    private fun installedRow(pack: PackInfo, input: StatesInput, v: CatalogView?): StateRowUi {
        val entry = v?.catalog?.packs?.firstOrNull { it.id == pack.packId }
        val job = input.job?.takeIf { it.packId == pack.packId && it.phase != JobPhase.DONE }
        val held = input.installed.changesHeldBy != null
        val update = entry != null && isNewer(entry, pack)
        val line = when {
            job != null -> progressLine(job)
            update -> Texts.updateLine(entry!!.osmSnapshot, entry.downloadBytes(pack.addressesFile != null))
            else -> Texts.installedLine(pack.osmSnapshot, pack.bytesOnDisk)
        }
        // of the pack on this phone, also while an update of it is offered or runs
        val built = pack.packVersion?.let { Texts.builtLine(it) }
        val hasPart = pack.addressesFile != null || pack.addressesSkipped
        val hn = when {
            !hasPart -> null
            pack.addressesFile != null -> Texts.HOUSE_NUMBERS_INCLUDED
            else -> Texts.HOUSE_NUMBERS_NOT_INCLUDED
        }
        var note: String? = if (!pack.verified) Texts.NOT_CHECKED else null
        var hnAction: Action? = null
        if (hasPart && pack.addressesFile == null && job == null) {
            val same = entry != null && entry.packVersion == pack.packVersion && entry.parts.any { it.optional }
            when {
                same && input.changesOptionalParts -> hnAction = Action(Texts.addHouseNumbers(entry.optionalDownloadBytes()), StatesEvent.AddHouseNumbers(pack.packId), enabled = unfinished(input) == null)
                update -> Unit // the Update sheet's box does both
                else -> note = Texts.removeAndDownloadAgain(pack.name)
            }
        }
        if (pack.addressesFile != null && input.changesOptionalParts && job == null) {
            hnAction = Action(Texts.REMOVE_HOUSE_NUMBERS, StatesEvent.RemoveHouseNumbers(pack.packId), enabled = !held)
        }
        val removeBlock = when {
            held -> Texts.NO_REMOVE_DURING_TRIP
            job != null -> Texts.beingUpdated(pack.name)
            else -> null
        }
        val actions = buildList {
            if (update && job == null) add(Action(Texts.UPDATE, StatesEvent.Update(pack.packId), enabled = unfinished(input) == null))
            add(Action(Texts.REMOVE, StatesEvent.Remove(pack.packId), enabled = removeBlock == null))
        }
        return StateRowUi(
            code = pack.state, name = pack.name, status = if (job != null) StateStatus.IN_PROGRESS else if (update) StateStatus.UPDATE_AVAILABLE else StateStatus.INSTALLED,
            line = line, builtLine = built, houseNumbersLine = hn, houseNumbersAction = hnAction, note = listOfNotNull(note, removeBlock).joinToString("\n").ifEmpty { null },
            actions = actions, stateDescription = listOfNotNull(line, built, hn).joinToString(". "), progress = job?.let { fraction(it) }, packId = pack.packId,
            highlighted = pack.state == input.selected,
        )
    }

    /**
     * The job that makes it "one state at a time" (1.4 "Other transitions"): one not finished. The
     * engine keeps a DONE job (and a FAILED one until dismissed) as its current job, and starts a new
     * one over it, so neither may disable Update or Add house numbers (found on the device: after a
     * download, Update stayed off for the rest of the session).
     */
    private fun unfinished(input: StatesInput): DownloadJob? = input.job?.takeIf { it.phase != JobPhase.DONE && it.phase != JobPhase.FAILED }

    private fun sheet(code: String, input: StatesInput, v: CatalogView?, status: StateStatus): SheetUi {
        val name = input.names[code] ?: input.sheetPack?.name ?: input.installed.packs.firstOrNull { it.state == code }?.name ?: code
        val close = Action(Texts.CLOSE, StatesEvent.CloseSheet)
        val useFile = Action(Texts.USE_FILE, StatesEvent.UseFile)
        val retry = Action(Texts.TRY_AGAIN, StatesEvent.CheckAgain)
        if (input.catalog is CatalogState.Off) return SheetUi(code, name, listOf(Texts.DOWNLOADS_OFF), primary = useFile, secondary = close)
        val pack = input.sheetPack ?: v?.catalog?.packs?.firstOrNull { it.state == code }
        val installed = input.installed.packs.firstOrNull { it.state == code }
        if (pack == null) {
            if (v == null) {
                return when (val c = input.catalog) {
                    is CatalogState.Loading, CatalogState.NotLoaded -> SheetUi(code, name, listOf(Texts.LOADING))
                    is CatalogState.Failed -> if (c.problem == CatalogProblem.NO_NETWORK) SheetUi(code, name, listOf(Texts.connectToSee(name)), primary = retry, secondary = useFile)
                    else SheetUi(code, name, listOf(Texts.CANT_LOAD_SHORT), primary = retry, secondary = useFile)
                    else -> SheetUi(code, name, emptyList(), secondary = close)
                }
            }
            if (installed != null) return SheetUi(code, name, installedLines(installed), secondary = close)
            if (status == StateStatus.NEEDS_NEWER_APP) return SheetUi(code, name, listOf(Texts.NEEDS_NEWER_SHEET), primary = useFile, secondary = close)
            val near = (input.neighbours[code] ?: emptyList()).filter { n -> v.catalog.packs.any { it.state == n } }.map { input.names[it] ?: it }.sorted()
            return SheetUi(code, name, listOfNotNull(Texts.noDownload(name), near.takeIf { it.isNotEmpty() }?.let { Texts.nearby(it) }), primary = useFile, secondary = close)
        }
        if (installed != null && input.sheetKind == SheetKind.STATE && !isNewer(pack, installed)) {
            return SheetUi(code, name, installedLines(installed), secondary = close)
        }
        val kind = when (input.sheetKind) {
            SheetKind.ADD_HOUSE_NUMBERS -> JobKind.ADD_OPTIONAL
            SheetKind.UPDATE -> JobKind.UPDATE
            SheetKind.STATE -> if (installed != null) JobKind.UPDATE else JobKind.INSTALL
        }
        val include = if (kind == JobKind.ADD_OPTIONAL) true else input.includeAddresses
        val parts = Space.parts(pack, kind, include)
        val download = pack.manifest.bytes + parts.sumOf { it.transfer.bytes }
        val unpacked = pack.manifest.bytes + parts.sumOf { it.bytes }
        val need = Space.estimate(pack, kind, include, input.moves, input.routingSetBytes, 0, input.marginBytes)
        val short = need > input.freeBytes
        val optional = pack.parts.any { it.optional }
        val lines = mutableListOf<String>()
        val title: String
        var houseNumbers: HouseNumbersUi? = null
        val primaryLabel: String
        val secondary: Action
        when (kind) {
            JobKind.ADD_OPTIONAL -> {
                title = Texts.addHouseNumbersTitle(pack.name)
                lines += Texts.ADD_HOUSE_NUMBERS_TEXT
                lines += Texts.addHouseNumbersSizes(download - pack.manifest.bytes, optionalBytes(pack))
                optionalCredit(pack)?.let { lines += Texts.sources(it) }
                primaryLabel = Texts.ADD
                secondary = Action(Texts.CANCEL, StatesEvent.CloseSheet)
            }
            JobKind.UPDATE -> {
                title = Texts.updateTitle(pack.name)
                lines += Texts.updateIntro(pack.osmSnapshot, installed?.osmSnapshot ?: pack.osmSnapshot)
                lines += Texts.downloadLine(download)
                lines += Texts.keepsWorking(pack.name)
                if (optional) houseNumbers = HouseNumbersUi(include, Texts.HOUSE_NUMBERS, Texts.houseNumbersAdds(pack.optionalDownloadBytes()), optionalCredit(pack)?.let { Texts.sources(it) })
                primaryLabel = Texts.UPDATE
                secondary = Action(Texts.NOT_NOW, StatesEvent.CloseSheet)
            }
            JobKind.INSTALL -> {
                title = pack.name
                lines += Texts.sheetIntro(pack.name, pack.osmSnapshot)
                lines += Texts.downloadLine(download)
                if (optional) houseNumbers = HouseNumbersUi(include, Texts.HOUSE_NUMBERS, Texts.houseNumbersHint(pack.optionalDownloadBytes()), optionalCredit(pack)?.let { Texts.sources(it) })
                primaryLabel = Texts.DOWNLOAD
                secondary = Action(Texts.CANCEL, StatesEvent.CloseSheet)
            }
        }
        val spaceLine = if (short) Texts.needsSpace(need, input.freeBytes) else if (kind == JobKind.INSTALL) Texts.onPhoneLine(unpacked, input.freeBytes) else null
        val other = input.job?.takeIf { it.phase != JobPhase.DONE && it.phase != JobPhase.FAILED }
        val connection: String?
        var primary: Action
        var second: Action = secondary
        when {
            other != null -> {
                connection = Texts.oneAtATime(other.name)
                primary = Action(primaryLabel, StatesEvent.Download(false), enabled = false)
            }
            short -> {
                connection = null
                primary = Action(Texts.FREE_UP_SPACE, StatesEvent.FreeUpSpace)
                second = Action(Texts.CANCEL, StatesEvent.CloseSheet)
            }
            !input.network.connected -> {
                connection = Texts.OFFLINE_SHEET
                primary = Action(Texts.WHEN_ONLINE, StatesEvent.Download(false))
            }
            !input.network.unmetered -> {
                connection = Texts.mobileData(download)
                primary = Action(Texts.WIFI_ONLY, StatesEvent.Download(false))
                second = Action(Texts.USE_MOBILE_DATA, StatesEvent.Download(true))
            }
            else -> {
                connection = null
                primary = Action(primaryLabel, StatesEvent.Download(false))
            }
        }
        return SheetUi(
            code = code, title = title, lines = lines, houseNumbers = houseNumbers, connectionLine = connection, spaceLine = spaceLine,
            primary = primary, secondary = second, creditLine = v?.catalog?.licence?.notice, privacyLine = Texts.PRIVACY,
        )
    }

    private fun fileSheet(f: FileChoice, input: StatesInput): SheetUi {
        val other = input.job?.takeIf { it.phase != JobPhase.DONE && it.phase != JobPhase.FAILED }
        return SheetUi(
            code = "", title = f.name,
            lines = listOf(Texts.fromFile(f.fileName, f.snapshot), Texts.fileNeeds(f.neededBytes - if (input.includeAddresses) 0 else (f.addressesBytes ?: 0), input.freeBytes)) + f.problems,
            houseNumbers = f.addressesBytes?.let { HouseNumbersUi(input.includeAddresses, Texts.HOUSE_NUMBERS, Texts.houseNumbersHint(it), null) },
            connectionLine = other?.let { Texts.oneAtATime(it.name) },
            primary = Action(Texts.INSTALL, StatesEvent.InstallFile, enabled = f.problems.isEmpty() && other == null && input.install !is InstallStatus.Running),
            secondary = Action(Texts.CANCEL, StatesEvent.CloseSheet),
        )
    }

    private fun dialog(input: StatesInput): DialogUi? {
        fun packName(id: String) = input.installed.packs.firstOrNull { it.packId == id }?.name ?: id
        return when (val d = input.dialog) {
            null -> null
            Dialog.CancelJob -> input.job?.let {
                DialogUi(Texts.stopDownloading(it.name), Texts.deletedSoFar(it.progress.doneBytes), Action(Texts.DELETE, StatesEvent.ConfirmCancel), Action(Texts.KEEP_DOWNLOADING, StatesEvent.CloseDialog))
            }
            is Dialog.Remove -> DialogUi(Texts.removeTitle(packName(d.packId)), Texts.REMOVE_TEXT, Action(Texts.REMOVE, StatesEvent.ConfirmRemove(d.packId)), Action(Texts.KEEP, StatesEvent.CloseDialog))
            is Dialog.RemoveHouseNumbers -> {
                val frees = input.installed.packs.firstOrNull { it.packId == d.packId }?.let { p ->
                    (view(input.catalog)?.catalog?.packs?.firstOrNull { it.id == p.packId }?.let { optionalBytes(it) })
                } ?: 0L
                DialogUi(Texts.removeHouseNumbersTitle(packName(d.packId)), Texts.removeHouseNumbersText(frees), Action(Texts.REMOVE, StatesEvent.ConfirmRemoveHouseNumbers(d.packId)), Action(Texts.KEEP, StatesEvent.CloseDialog))
            }
            is Dialog.Details -> DialogUi(Texts.DETAILS, d.text, Action(Texts.OK, StatesEvent.CloseDialog), null)
        }
    }

    /** The progress view (1.1 step 5) and the texts of 1.4 in it. */
    fun jobUi(job: DownloadJob, input: StatesInput): JobUi {
        val name = job.name
        val done = job.progress.doneBytes
        val total = job.progress.totalBytes
        val pause = Action(Texts.PAUSE, StatesEvent.Pause)
        val cancel = Action(Texts.CANCEL, StatesEvent.Cancel)
        val cont = Action(Texts.CONTINUE, StatesEvent.Continue)
        val title = when (job.kind) {
            JobKind.ADD_OPTIONAL -> Texts.addingHouseNumbers(name)
            else -> Texts.downloading(name)
        }
        val notif = if (input.notificationsOff) Texts.NOTIFICATIONS_OFF else null
        val notifAction = if (input.notificationsOff) Action(Texts.TURN_ON, StatesEvent.TurnOnNotifications) else null
        val keepOpen = if (input.serviceRefused) Texts.KEEP_OPEN_DOWNLOAD else null
        val message = if (input.switched) Texts.switched(name) else null
        fun moving(line: String, progress: Float?) = JobUi(title, line, Texts.KEEPS_GOING, progress, listOf(pause, cancel), notif, notifAction, keepOpen, message)
        return when (job.phase) {
            JobPhase.CHECKING -> moving(if (input.continuing) Texts.CONTINUING else Texts.GETTING_READY, null)
            JobPhase.DOWNLOADING -> if (input.continuing) moving(Texts.CONTINUING, fraction(job)) else {
                val speed = job.progress.bytesPerSecond
                val eta = if (speed != null && speed > 0) {
                    val s = (total - done).toDouble() / speed
                    if (s < 60) Texts.LESS_THAN_A_MINUTE else Texts.left(input.units.duration(s))
                } else ""
                moving(Texts.ofTotal(done, total) + eta, fraction(job))
            }
            JobPhase.UNPACKING -> moving(
                when (job.progress.part) {
                    "search" -> Texts.UNPACKING_SEARCH
                    "addresses" -> Texts.UNPACKING_HOUSE_NUMBERS
                    else -> Texts.UNPACKING_ROUTING
                },
                fraction(job),
            )
            JobPhase.READY, JobPhase.INSTALLING -> JobUi(
                Texts.installing(name),
                when (job.installStep) {
                    InstallStep.MERGING_ROUTING -> Texts.COMBINING
                    InstallStep.FINISHING -> Texts.FINISHING
                    else -> Texts.CHECKING_FILES
                },
                null, fraction(job), emptyList(), notif, notifAction, keepOpen, message,
            )
            JobPhase.WAITING -> when (job.wait) {
                WaitReason.NOT_UNMETERED -> JobUi(Texts.WAITING_WIFI, Texts.waitingWifiLine(done, total), null, fraction(job),
                    listOf(Action(Texts.USE_MOBILE_DATA, StatesEvent.UseMobileData), cancel), notif, notifAction, keepOpen, message)
                WaitReason.NO_NETWORK -> JobUi(Texts.WAITING_CONNECTION, Texts.waitingConnectionLine(done, total), null, fraction(job), listOf(pause, cancel), notif, notifAction, keepOpen, message)
                WaitReason.RETRYING -> JobUi(Texts.CONNECTION_LOST, Texts.TRYING_AGAIN, null, fraction(job), listOf(pause, cancel), notif, notifAction, keepOpen, message)
                WaitReason.TRIP_IN_PROGRESS -> JobUi(Texts.downloaded(name), Texts.AFTER_TRIP, null, 1f, listOf(cancel), notif, notifAction, keepOpen, message)
                WaitReason.INSTALLER_BUSY, null -> JobUi(Texts.downloaded(name), Texts.AFTER_OTHER_INSTALL, null, 1f, listOf(cancel), notif, notifAction, keepOpen, message)
            }
            JobPhase.PAUSED -> when (job.pause) {
                PauseReason.NO_SPACE -> JobUi(Texts.NOT_ENOUGH_SPACE, Texts.needsFree(name, job.needBytes ?: 0, job.freeBytes ?: input.freeBytes), null, fraction(job),
                    listOf(Action(Texts.FREE_UP_SPACE, StatesEvent.FreeUpSpace), cont, cancel))
                PauseReason.SERVER_UNREACHABLE -> JobUi(Texts.UNREACHABLE, Texts.progressKept(done, total), null, fraction(job), listOf(cont, cancel))
                PauseReason.CLOCK -> JobUi(Texts.DOWNLOAD_PAUSED, Texts.CLOCK_DOWNLOAD, null, fraction(job), listOf(cont, cancel))
                PauseReason.KEEPS_STOPPING -> JobUi(Texts.DOWNLOAD_PAUSED, Texts.closedTwice(name), null, fraction(job), listOf(cont, cancel))
                PauseReason.DAMAGED -> JobUi(Texts.DOWNLOAD_STOPPED, Texts.damaged(name), null, fraction(job), listOf(Action(Texts.TRY_AGAIN, StatesEvent.Continue), cancel))
                PauseReason.BAD_RESPONSE -> JobUi(Texts.DOWNLOAD_PAUSED, Texts.BAD_RESPONSE, null, fraction(job), listOf(cont, cancel))
                PauseReason.APP_ENDED -> moving(Texts.CONTINUING, fraction(job))
                else -> JobUi(Texts.DOWNLOAD_PAUSED, Texts.progressKept(done, total), null, fraction(job), listOf(cont, cancel), notif, notifAction)
            }
            // A failed job shows no progress bar (an empty progress was drawn as a moving, indeterminate bar).
            JobPhase.FAILED -> when (job.fail) {
                FailReason.NO_LONGER_OFFERED -> JobUi(Texts.noLongerOffered(name), Texts.LIST_CHANGED, null, null, listOf(Action(Texts.CHECK_AGAIN, StatesEvent.CheckAgain), Action(Texts.OK, StatesEvent.Dismiss)))
                FailReason.LIST_MISMATCH -> JobUi(Texts.DOWNLOAD_FAILED, Texts.disagree(name), null, null, listOf(Action(Texts.CHECK_AGAIN, StatesEvent.CheckAgain), Action(Texts.OK, StatesEvent.Dismiss)))
                FailReason.REJECTED -> JobUi(Texts.cantInstall(name), Texts.NOTHING_CHANGED, null, null,
                    listOfNotNull(job.detail?.let { Action(Texts.DETAILS, StatesEvent.Details) }, Action(Texts.OK, StatesEvent.Dismiss)))
                FailReason.NEEDS_NEWER_APP, null -> JobUi(Texts.DOWNLOAD_FAILED, Texts.NEEDS_NEWER_SHEET, null, null, listOf(Action(Texts.USE_FILE, StatesEvent.UseFile), Action(Texts.OK, StatesEvent.Dismiss)))
            }.copy(bar = false)
            JobPhase.DONE -> JobUi(Texts.ready(name), "", null, 1f, emptyList())
        }
    }

    /** The map chip (1.5); null with a state installed and no job. */
    fun chip(job: DownloadJob?, installed: InstalledSet): Pair<String, String>? {
        val text = when {
            job == null || job.phase == JobPhase.DONE -> if (installed.packs.isEmpty()) Texts.CHIP_CHOOSE else null
            job.phase == JobPhase.FAILED -> Texts.chipFailed(job.name)
            job.phase == JobPhase.INSTALLING || job.phase == JobPhase.READY -> Texts.chipInstalling(job.name)
            job.phase == JobPhase.PAUSED && job.pause != PauseReason.APP_ENDED -> Texts.chipPaused(job.name)
            job.phase == JobPhase.WAITING && job.wait == WaitReason.NOT_UNMETERED -> Texts.chipWaitingWifi(job.name)
            job.phase == JobPhase.WAITING && (job.wait == WaitReason.TRIP_IN_PROGRESS || job.wait == WaitReason.INSTALLER_BUSY) -> Texts.chipInstalling(job.name)
            else -> Texts.chipDownloading(job.name, Texts.percent(job.progress.doneBytes, job.progress.totalBytes))
        } ?: return null
        return text to Texts.chipDescription(text)
    }

    /** The download notification (1.1 step 7, 1.4): title, text, progress 0..100 (null = none, -1 = indeterminate), ongoing, actions. */
    data class NotificationUi(val title: String, val text: String, val progress: Int?, val ongoing: Boolean, val pause: Boolean, val useMobileData: Boolean, val opensMap: Boolean)

    fun notification(job: DownloadJob): NotificationUi {
        val name = job.name
        val p = Texts.percent(job.progress.doneBytes, job.progress.totalBytes)
        val ofTotal = Texts.ofTotal(job.progress.doneBytes, job.progress.totalBytes)
        return when (job.phase) {
            JobPhase.CHECKING -> NotificationUi(Texts.downloading(name), Texts.GETTING_READY, -1, true, pause = true, useMobileData = false, opensMap = false)
            JobPhase.DOWNLOADING, JobPhase.UNPACKING -> NotificationUi(if (job.kind == JobKind.ADD_OPTIONAL) Texts.addingHouseNumbers(name) else Texts.downloading(name), ofTotal, p, true, pause = true, useMobileData = false, opensMap = false)
            JobPhase.READY, JobPhase.INSTALLING -> NotificationUi(Texts.installing(name), Texts.CHECKING_FILES, -1, true, pause = false, useMobileData = false, opensMap = false)
            JobPhase.WAITING -> when (job.wait) {
                WaitReason.NOT_UNMETERED -> NotificationUi(Texts.WAITING_WIFI, Texts.waitingWifiLine(job.progress.doneBytes, job.progress.totalBytes), p, true, pause = true, useMobileData = true, opensMap = false)
                WaitReason.NO_NETWORK -> NotificationUi(Texts.WAITING_CONNECTION, Texts.waitingConnectionLine(job.progress.doneBytes, job.progress.totalBytes), p, true, pause = true, useMobileData = false, opensMap = false)
                WaitReason.RETRYING -> NotificationUi(Texts.CONNECTION_LOST, Texts.TRYING_AGAIN, p, true, pause = true, useMobileData = false, opensMap = false)
                WaitReason.TRIP_IN_PROGRESS -> NotificationUi(Texts.downloaded(name), Texts.AFTER_TRIP, null, false, pause = false, useMobileData = false, opensMap = false)
                WaitReason.INSTALLER_BUSY, null -> NotificationUi(Texts.downloaded(name), Texts.AFTER_OTHER_INSTALL, null, false, pause = false, useMobileData = false, opensMap = false)
            }
            JobPhase.PAUSED -> NotificationUi(
                Texts.DOWNLOAD_PAUSED.takeIf { job.pause == PauseReason.NO_SPACE || job.pause == PauseReason.SERVER_UNREACHABLE || job.pause == PauseReason.SYSTEM_STOPPED } ?: Texts.pausedPercent(p),
                when (job.pause) {
                    PauseReason.NO_SPACE -> Texts.NO_SPACE_NOTIFICATION
                    PauseReason.SERVER_UNREACHABLE -> Texts.UNREACHABLE_NOTIFICATION
                    else -> Texts.OPEN_TO_CONTINUE
                },
                null, false, pause = false, useMobileData = false, opensMap = false,
            )
            JobPhase.FAILED -> {
                val t = when (job.fail) {
                    FailReason.NO_LONGER_OFFERED -> Texts.noLongerOffered(name)
                    FailReason.REJECTED -> Texts.cantInstall(name)
                    else -> Texts.DOWNLOAD_FAILED
                }
                NotificationUi(t, Texts.OPEN_TO_CONTINUE, null, false, pause = false, useMobileData = false, opensMap = false)
            }
            JobPhase.DONE -> NotificationUi(Texts.readyNotification(name), Texts.TAP_TO_OPEN_MAP, null, false, pause = false, useMobileData = false, opensMap = true)
        }
    }
}
