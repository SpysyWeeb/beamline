package io.github.spysyweeb.beamline.feature.states.model

import io.github.spysyweeb.beamline.api.data.InstallStep
import io.github.spysyweeb.beamline.api.data.InstalledSet
import io.github.spysyweeb.beamline.api.data.PackInfo
import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.download.Catalog
import io.github.spysyweeb.beamline.download.CatalogLicence
import io.github.spysyweeb.beamline.download.CatalogPack
import io.github.spysyweeb.beamline.download.CatalogPart
import io.github.spysyweeb.beamline.download.CatalogProblem
import io.github.spysyweeb.beamline.download.CatalogState
import io.github.spysyweeb.beamline.download.CatalogView
import io.github.spysyweeb.beamline.download.DownloadJob
import io.github.spysyweeb.beamline.download.FailReason
import io.github.spysyweeb.beamline.download.JobKind
import io.github.spysyweeb.beamline.download.JobPhase
import io.github.spysyweeb.beamline.download.JobProgress
import io.github.spysyweeb.beamline.download.NetworkState
import io.github.spysyweeb.beamline.download.PauseReason
import io.github.spysyweeb.beamline.download.TransferFile
import io.github.spysyweeb.beamline.download.Unusable
import io.github.spysyweeb.beamline.download.UnusablePack
import io.github.spysyweeb.beamline.download.WaitReason
import io.github.spysyweeb.beamline.feature.states.StatesMode
import io.github.spysyweeb.beamline.units.UnitFormatter
import io.github.spysyweeb.beamline.units.UnitRules
import io.github.spysyweeb.beamline.units.UnitSystem
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.ZoneId

/** docs/architecture-downloads.md 10.2: every text of section 1 the presenter makes. */
class StatesPresenterTest {
    private val units = UnitFormatter(UnitRules.bundled(), UnitSystem.IMPERIAL)
    private val zone = ZoneId.of("America/New_York")
    private val now = 1_794_830_400_000L // Nov 16, 2026, 12:00 UTC
    private val hex = "a".repeat(64)

    private fun part(name: String, file: String, bytes: Long, transfer: Long, optional: Boolean = false, credit: String = "© OpenStreetMap contributors") =
        CatalogPart(name, file, optional, bytes, hex, TransferFile("../us-pa-1/$file.gz", if (transfer == bytes) "identity" else "gzip", transfer, hex), credit)

    /** Pennsylvania as section 1 counts it: 548 MB measured plus a 31 MB house-number placeholder. */
    private fun pa(version: Long = 202611152241, snapshot: String = "2026-11-14", id: String = "us-pa", state: String = "PA", name: String = "Pennsylvania") = CatalogPack(
        id, state, name, version, 3, snapshot, "© OpenStreetMap contributors", Bounds(-80.51985, 39.7198, -74.68956, 42.51607),
        TransferFile("../us-pa-1/manifest.json", "identity", 2210, hex),
        listOf(
            part("addresses", "addresses.sqlite", 80_000_000, 31_000_000, optional = true, credit = "© OpenStreetMap contributors; U.S. Census Bureau TIGER/Line; USDOT NAD; City of Scranton"),
            part("search", "search.sqlite", 154_705_920, 63_346_936),
            part("routing", "routing.tar", 634_429_440, 240_430_789),
            part("map", "map.pmtiles", 244_703_514, 244_703_514),
        ),
    )

    private val licence = CatalogLicence("ODbL-1.0", "Open Database License 1.0", "https://opendatacommons.org/licenses/odbl/1-0/",
        "Map data © OpenStreetMap contributors, available under the Open Database License.")

    private fun catalog(vararg packs: CatalogPack, unusable: List<UnusablePack> = emptyList(), checked: Long = now) =
        CatalogState.Ready(CatalogView(Catalog(1, "2026-11-15T22:41:07Z", licence, packs.toList(), unusable), checked, fromCache = false))

    private val names = mapOf("PA" to "Pennsylvania", "OH" to "Ohio", "RI" to "Rhode Island", "CT" to "Connecticut", "MA" to "Massachusetts", "NY" to "New York")

    private fun input(
        catalog: CatalogState = catalog(pa(), pa(id = "us-oh", state = "OH", name = "Ohio"), pa(id = "us-ct", state = "CT", name = "Connecticut"), pa(id = "us-ma", state = "MA", name = "Massachusetts")),
        mode: StatesMode = StatesMode.FIRST_RUN,
        job: DownloadJob? = null,
        installed: InstalledSet = InstalledSet(scanned = true),
        network: NetworkState = NetworkState(connected = true, unmetered = true),
        free: Long = 24_000_000_000,
        selected: String? = null,
        sheet: Boolean = false,
        kind: SheetKind = SheetKind.STATE,
        include: Boolean = true,
    ) = StatesInput(
        mode = mode, names = names, neighbours = mapOf("RI" to listOf("CT", "MA")), catalog = catalog, installed = installed, job = job,
        network = network, units = units, nowMs = now, zone = zone, freeBytes = free, moves = true, selected = selected, sheetOpen = sheet,
        sheetKind = kind, includeAddresses = include, changesOptionalParts = true,
    )

    private fun job(phase: JobPhase, done: Long = 238_000_000, wait: WaitReason? = null, pause: PauseReason? = null, fail: FailReason? = null,
                    speed: Long? = null, part: String? = null, step: InstallStep? = null, kind: JobKind = JobKind.INSTALL, detail: String? = null) =
        DownloadJob("us-pa", 202611152241, "Pennsylvania", "PA", "2026-11-14", kind, true, false, true, phase,
            JobProgress(done, 579_483_446, part, speed), wait, null, pause, fail, detail, 1_900_000_000, 800_000_000, step, 0, 0)

    private fun installedPa(version: Long? = 202611152241, addresses: Boolean? = true, verified: Boolean = true) = PackInfo(
        "us-pa", "Pennsylvania", "PA", "2026-11-14", Bounds(-80.5, 39.7, -74.7, 42.5), "© OpenStreetMap contributors", "/p/us-pa", "/p/us-pa/map.pmtiles",
        0, 14, "/p/us-pa/search.sqlite", if (addresses == true) "/p/us-pa/addresses.sqlite" else null, addresses == false, null, null, true, verified,
        1_113_841_084, version,
    )

    @Test
    fun sizesDatesAndDurations() {
        assertEquals("8.5 MB", Texts.size(8_500_000))
        assertEquals("553 MB", Texts.size(553_000_000))
        assertEquals("1.0 GB", Texts.size(1_000_000_000))
        assertEquals("1.0 GB", Texts.size(999_600_000))
        assertEquals("less than 1 MB", Texts.size(999_999))
        assertEquals("579 MB", Texts.size(pa().downloadBytes(true)))
        assertEquals("1.1 GB", Texts.size(pa().unpackedBytes(true)))
        assertEquals("Nov 14, 2026", Texts.date("2026-11-14"))
        assertEquals("Dec 1, 2026", Texts.date("2026-12-01"))
        assertEquals("Nov 16, 2026", Texts.date(now, zone))
        assertEquals("24 GB", Texts.space(24_000_000_000))
        assertEquals("0.8 GB", Texts.space(800_000_000))
        val down = StatesPresenter.jobUi(job(JobPhase.DOWNLOADING, speed = 1_900_000), input())
        assertEquals("238 MB of 579 MB · about ${units.duration((579_483_446 - 238_000_000) / 1_900_000.0)} left", down.line)
        assertEquals("238 MB of 579 MB · about 3 min left", down.line)
        val fast = StatesPresenter.jobUi(job(JobPhase.DOWNLOADING, speed = 100_000_000), input())
        assertEquals("238 MB of 579 MB · less than a minute left", fast.line)
        assertEquals("238 MB of 579 MB", StatesPresenter.jobUi(job(JobPhase.DOWNLOADING), input()).line)
    }

    @Test
    fun firstRunScreenAndRows() {
        val ui = StatesPresenter.present(input(catalog = catalog(pa(), unusable = listOf(UnusablePack("us-oh", "OH", "Ohio", Unusable.NEEDS_NEWER_APP, "x")))))
        assertEquals("Choose your state", ui.title)
        assertEquals("Beamline downloads the maps, directions and search for your state once. After that it works without a signal.", ui.intro)
        assertEquals("Search states", ui.searchHint)
        assertEquals("Map of the United States. The list below has every state.", ui.mapDescription)
        assertEquals("Available", ui.availableTitle)
        assertEquals("579 MB download · data from Nov 14, 2026", ui.available.single { it.code == "PA" }.line)
        assertEquals("Not available yet", ui.notAvailableTitle)
        assertEquals("Not available yet", ui.notAvailable.first { it.code == "RI" }.line)
        assertEquals("Needs a newer Beamline", ui.needsNewerTitle)
        assertEquals("Needs a newer version of Beamline", ui.needsNewer.single().line)
        assertEquals(listOf("Use a file instead", "Not now"), ui.bottomBar.map { it.label })
        assertEquals(StateStatus.AVAILABLE, ui.mapStatus["PA"])
        assertEquals(StateStatus.NOT_AVAILABLE, ui.mapStatus["RI"])
        assertEquals(StateStatus.NEEDS_NEWER_APP, ui.mapStatus["OH"])
        // without house numbers the size line follows the box
        assertEquals("548 MB download · data from Nov 14, 2026", StatesPresenter.present(input(include = false)).available.first { it.code == "PA" }.line)
        // search by name or postal code
        assertEquals(listOf("PA"), StatesPresenter.present(input().copy(query = "penn")).available.map { it.code })
        assertEquals(listOf("OH"), StatesPresenter.present(input().copy(query = "oh")).available.map { it.code })
    }

    @Test
    fun loadingOfflineFailedAndOff() {
        val loading = StatesPresenter.present(input(catalog = CatalogState.Loading(null), selected = "PA", sheet = true))
        assertEquals("Getting the list of states…", loading.loadingLine)
        assertEquals(StateStatus.LOADING, loading.mapStatus["PA"])
        assertEquals(listOf("Getting the list of states…"), loading.sheet!!.lines)
        assertEquals("Pennsylvania", loading.sheet!!.title)
        assertNull(loading.available.first().line)

        val offline = StatesPresenter.present(input(catalog = CatalogState.Failed(CatalogProblem.NO_NETWORK, null), selected = "PA", sheet = true))
        assertEquals("You're offline. Connect to the internet to download your state, or use a file.", offline.banner!!.text)
        assertEquals("Try again", offline.banner!!.action!!.label)
        assertEquals(StateStatus.UNKNOWN, offline.mapStatus["PA"])
        assertEquals(listOf("Connect to the internet to see the download for Pennsylvania."), offline.sheet!!.lines)
        assertEquals(listOf("Try again", "Use a file instead"), listOf(offline.sheet!!.primary!!.label, offline.sheet!!.secondary!!.label))

        val failed = StatesPresenter.present(input(catalog = CatalogState.Failed(CatalogProblem.UNREACHABLE, null), selected = "PA", sheet = true))
        assertEquals("Can't load the list of states right now. Try again later, or use a file.", failed.banner!!.text)
        assertEquals(listOf("Can't load the list of states right now."), failed.sheet!!.lines)

        val cachedView = (catalog(pa(), checked = 1_794_312_000_000) as CatalogState.Ready).view.copy(fromCache = true)
        val cached = StatesPresenter.present(input(catalog = CatalogState.Failed(CatalogProblem.UNREACHABLE, cachedView)))
        assertNull(cached.banner)
        assertEquals("List from Nov 10, 2026. Couldn't check for changes.", cached.checkedLine)
        assertEquals("579 MB download · data from Nov 14, 2026", cached.available.first { it.code == "PA" }.line)

        assertEquals("Can't check the list of states: your phone's date or time looks wrong. Check it in Settings, then try again.",
            StatesPresenter.present(input(catalog = CatalogState.Failed(CatalogProblem.CLOCK, null))).banner!!.text)
        assertEquals("This version of Beamline can't read the list of states. Update Beamline, or use a file.",
            StatesPresenter.present(input(catalog = CatalogState.Failed(CatalogProblem.NEWER_FORMAT, cachedView))).banner!!.text)
        assertEquals("Downloads are off in this build. Use a file instead: a state file (.tar) copied to this phone.", StatesPresenter.present(input(catalog = CatalogState.Off)).banner!!.text)
    }

    /** The owner, 2026-11-21: while downloads are off, managing shows no list of states that cannot be added. */
    @Test
    fun downloadsOffHidesTheListOfStatesToAdd() {
        val off = StatesPresenter.present(input(catalog = CatalogState.Off, mode = StatesMode.MANAGE))
        assertNull(off.addTitle)
        assertTrue(off.available.isEmpty() && off.notAvailable.isEmpty() && off.needsNewer.isEmpty())
        assertNull(off.searchHint)
        val on = StatesPresenter.present(input(mode = StatesMode.MANAGE))
        assertEquals(on.onPhone, off.onPhone, "what is on the phone stays")
        assertEquals("Add a state", on.addTitle)
        assertTrue(on.available.isNotEmpty() && on.searchHint != null)
        // the first-run screen keeps its list: it is that screen's content
        val first = StatesPresenter.present(input(catalog = CatalogState.Off))
        assertTrue(first.available.isNotEmpty() && first.searchHint != null)
    }

    @Test
    fun theStateSheetOnWifiMobileOfflineAndShortOfSpace() {
        val s = StatesPresenter.present(input(selected = "PA", sheet = true)).sheet!!
        assertEquals("Pennsylvania", s.title)
        assertEquals(listOf("Maps, directions and search for Pennsylvania, from OpenStreetMap data of Nov 14, 2026.", "Download: 579 MB"), s.lines)
        assertEquals("On this phone: 1.1 GB. Free now: 24 GB.", s.spaceLine)
        assertEquals("House numbers", s.houseNumbers!!.label)
        assertEquals("Find addresses like \"4319 Peach St\". Adds 31 MB.", s.houseNumbers!!.hint)
        assertEquals("Sources: © OpenStreetMap contributors; U.S. Census Bureau TIGER/Line; USDOT NAD; City of Scranton", s.houseNumbers!!.sourcesLine)
        assertEquals("Download", s.primary!!.label)
        assertEquals("Cancel", s.secondary!!.label)
        assertEquals("Map data © OpenStreetMap contributors, available under the Open Database License.", s.creditLine)
        assertEquals("Downloading tells the download server your IP address and which state you chose. Nothing else is sent.", s.privacyLine)
        assertNull(s.connectionLine)

        val mobile = StatesPresenter.present(input(selected = "PA", sheet = true, network = NetworkState(connected = true, unmetered = false))).sheet!!
        assertEquals("You're on mobile data. This download uses 579 MB of your data plan.", mobile.connectionLine)
        assertEquals("Download on Wi-Fi only", mobile.primary!!.label)
        assertEquals(StatesEvent.Download(false), mobile.primary!!.event)
        assertEquals("Use mobile data", mobile.secondary!!.label)
        assertEquals(StatesEvent.Download(true), mobile.secondary!!.event)

        val off = StatesPresenter.present(input(selected = "PA", sheet = true, network = NetworkState.NONE)).sheet!!
        assertEquals("You're offline. The download starts when you're connected.", off.connectionLine)
        assertEquals("Download when online", off.primary!!.label)

        val short = StatesPresenter.present(input(selected = "PA", sheet = true, free = 800_000_000)).sheet!!
        assertEquals("Needs about 1.9 GB free. This phone has 0.8 GB free.", short.spaceLine)
        assertEquals("Free up space", short.primary!!.label)
        assertEquals("Cancel", short.secondary!!.label)

        val noBox = StatesPresenter.present(input(selected = "PA", sheet = true, include = false)).sheet!!
        assertEquals("Download: 548 MB", noBox.lines[1])
        assertFalse(noBox.houseNumbers!!.checked)
    }

    @Test
    fun aStateWithoutAPackAndOneThatNeedsANewerApp() {
        val ri = StatesPresenter.present(input(selected = "RI", sheet = true)).sheet!!
        assertEquals(listOf("There's no download for Rhode Island yet.", "Nearby states you can download: Connecticut, Massachusetts."), ri.lines)
        assertEquals(listOf("Use a file instead", "Close"), listOf(ri.primary!!.label, ri.secondary!!.label))
        val newer = StatesPresenter.present(input(catalog = catalog(pa(), unusable = listOf(UnusablePack("us-oh", "OH", "Ohio", Unusable.NEEDS_NEWER_APP, "x"))), selected = "OH", sheet = true)).sheet!!
        assertEquals(listOf("This download needs a newer version of Beamline."), newer.lines)
        assertEquals("Use a file instead", newer.primary!!.label)
    }

    @Test
    fun progressViewTextsByPhase() {
        val i = input()
        assertEquals("Downloading Pennsylvania", StatesPresenter.jobUi(job(JobPhase.CHECKING), i).title)
        assertEquals("Getting ready…", StatesPresenter.jobUi(job(JobPhase.CHECKING), i).line)
        assertNull(StatesPresenter.jobUi(job(JobPhase.CHECKING), i).progress, "indeterminate")
        assertEquals("Continuing the download…", StatesPresenter.jobUi(job(JobPhase.CHECKING), i.copy(continuing = true)).line)
        // ... and while the continued download has not moved yet (GetPacksCore clears the flag then).
        assertEquals("Continuing the download…", StatesPresenter.jobUi(job(JobPhase.DOWNLOADING), i.copy(continuing = true)).line)
        assertEquals(listOf("Pause", "Cancel"), StatesPresenter.jobUi(job(JobPhase.DOWNLOADING), i.copy(continuing = true)).actions.map { it.label })
        val d = StatesPresenter.jobUi(job(JobPhase.DOWNLOADING), i)
        assertEquals("You can leave Beamline or lock your phone. The download keeps going.", d.hint)
        assertEquals(listOf("Pause", "Cancel"), d.actions.map { it.label })
        assertEquals("Unpacking routing data…", StatesPresenter.jobUi(job(JobPhase.UNPACKING, part = "routing"), i).line)
        assertEquals("Unpacking search data…", StatesPresenter.jobUi(job(JobPhase.UNPACKING, part = "search"), i).line)
        assertEquals("Unpacking house numbers…", StatesPresenter.jobUi(job(JobPhase.UNPACKING, part = "addresses"), i).line)
        val inst = StatesPresenter.jobUi(job(JobPhase.INSTALLING, step = InstallStep.COPYING), i)
        assertEquals("Installing Pennsylvania", inst.title)
        assertEquals("Checking the files…", inst.line)
        assertTrue(inst.actions.isEmpty(), "no Pause or Cancel while installing")
        assertEquals("Checking the files…", StatesPresenter.jobUi(job(JobPhase.INSTALLING, step = InstallStep.CHECKING), i).line)
        assertEquals("Combining routing data…", StatesPresenter.jobUi(job(JobPhase.INSTALLING, step = InstallStep.MERGING_ROUTING), i).line)
        assertEquals("Finishing…", StatesPresenter.jobUi(job(JobPhase.INSTALLING, step = InstallStep.FINISHING), i).line)
        assertEquals("Adding house numbers to Pennsylvania", StatesPresenter.jobUi(job(JobPhase.DOWNLOADING, kind = JobKind.ADD_OPTIONAL), i).title)
        val off = StatesPresenter.jobUi(job(JobPhase.DOWNLOADING), i.copy(notificationsOff = true))
        assertEquals("Notifications are off, so Beamline can't tell you when it's done.", off.notificationsLine)
        assertEquals("Turn on", off.notificationsAction!!.label)
        assertEquals("Keep Beamline open until the download has finished.", StatesPresenter.jobUi(job(JobPhase.DOWNLOADING), i.copy(serviceRefused = true)).keepOpenLine)
        assertEquals("A newer Pennsylvania was published. Downloading that one.", StatesPresenter.jobUi(job(JobPhase.CHECKING), i.copy(switched = true)).message)
    }

    @Test
    fun waitsPausesAndFailures() {
        val i = input()
        fun ui(j: DownloadJob) = StatesPresenter.jobUi(j, i).let { Triple(it.title, it.line, it.actions.map { a -> a.label }) }
        assertEquals(Triple("Waiting for Wi-Fi", "238 MB of 579 MB. The download goes on when you're on Wi-Fi.", listOf("Use mobile data", "Cancel")),
            ui(job(JobPhase.WAITING, wait = WaitReason.NOT_UNMETERED)))
        assertEquals("Waiting for a connection", ui(job(JobPhase.WAITING, wait = WaitReason.NO_NETWORK)).first)
        assertEquals("238 MB of 579 MB. The download goes on when you're online.", ui(job(JobPhase.WAITING, wait = WaitReason.NO_NETWORK)).second)
        assertEquals("Connection lost" to "Trying again in a moment.", ui(job(JobPhase.WAITING, wait = WaitReason.RETRYING)).let { it.first to it.second })
        assertEquals("Pennsylvania is downloaded" to "It will be installed when your trip ends.", ui(job(JobPhase.WAITING, wait = WaitReason.TRIP_IN_PROGRESS)).let { it.first to it.second })
        assertEquals(Triple("Not enough space", "Pennsylvania needs 1.9 GB free. This phone has 0.8 GB free.", listOf("Free up space", "Continue", "Cancel")),
            ui(job(JobPhase.PAUSED, pause = PauseReason.NO_SPACE)))
        assertEquals(Triple("Couldn't reach the download server", "238 MB of 579 MB. Your progress is kept.", listOf("Continue", "Cancel")),
            ui(job(JobPhase.PAUSED, pause = PauseReason.SERVER_UNREACHABLE)))
        assertEquals("Your phone's date or time looks wrong, so the download server can't be checked. Check it in Settings, then tap Continue.",
            ui(job(JobPhase.PAUSED, pause = PauseReason.CLOCK)).second)
        assertEquals("Download paused" to "Beamline closed twice while working on Pennsylvania.", ui(job(JobPhase.PAUSED, pause = PauseReason.KEEPS_STOPPING)).let { it.first to it.second })
        assertEquals(Triple("Download stopped", "Part of Pennsylvania arrived damaged twice. The parts that arrived whole are kept. Try again later.", listOf("Try again", "Cancel")),
            ui(job(JobPhase.PAUSED, pause = PauseReason.DAMAGED)))
        assertEquals("The download server sent something Beamline doesn't accept. Your progress is kept. Try again later.", ui(job(JobPhase.PAUSED, pause = PauseReason.BAD_RESPONSE)).second)
        assertEquals("Continuing the download…", ui(job(JobPhase.PAUSED, pause = PauseReason.APP_ENDED)).second)
        assertEquals("Download paused", ui(job(JobPhase.PAUSED, pause = PauseReason.USER)).first)
        assertEquals(Triple("Download paused", "238 MB of 579 MB. Your progress is kept.", listOf("Continue", "Cancel")),
            ui(job(JobPhase.PAUSED, pause = PauseReason.SYSTEM_STOPPED)))
        assertEquals("Pennsylvania is downloaded" to "It will be installed when the other install has finished.",
            ui(job(JobPhase.WAITING, wait = WaitReason.INSTALLER_BUSY)).let { it.first to it.second })
        assertEquals(Triple("Download failed", "This download needs a newer version of Beamline.", listOf("Use a file instead", "OK")),
            ui(job(JobPhase.FAILED, fail = FailReason.NEEDS_NEWER_APP)))
        // Every wait, pause and fail reason has a title and a line in the view, the row and the notification.
        for (w in WaitReason.entries) ui(job(JobPhase.WAITING, wait = w)).let { assertTrue(it.first.isNotBlank() && it.second.isNotBlank(), "$w") }
        for (p in PauseReason.entries) {
            val j = job(JobPhase.PAUSED, pause = p)
            ui(j).let { assertTrue(it.first.isNotBlank() && it.second.isNotBlank() && it.third.isNotEmpty(), "$p") }
            assertEquals("Paused · 41 %", StatesPresenter.progressLine(j))
            assertTrue(StatesPresenter.notification(j).let { n -> n.title.isNotBlank() && n.text.isNotBlank() && !n.ongoing }, "$p")
        }
        for (f in FailReason.entries) ui(job(JobPhase.FAILED, fail = f)).let { assertTrue(it.first.isNotBlank() && it.second.isNotBlank() && "OK" in it.third, "$f") }
        // Seen on the device: a failed job drew a moving (indeterminate) bar and its row said "Paused · 0 %". Now no bar and "Download failed".
        for (f in FailReason.entries) assertFalse(StatesPresenter.jobUi(job(JobPhase.FAILED, fail = f), i).bar, "$f")
        assertEquals("Download failed", StatesPresenter.progressLine(job(JobPhase.FAILED, fail = FailReason.NO_LONGER_OFFERED)))
        assertTrue(StatesPresenter.jobUi(job(JobPhase.CHECKING), i).let { it.bar && it.progress == null }) // still working: a moving bar
        assertTrue(StatesPresenter.jobUi(job(JobPhase.PAUSED, pause = PauseReason.USER), i).bar)
        assertEquals(Triple("Pennsylvania is no longer offered", "The list of states changed. Check the list again.", listOf("Check again", "OK")),
            ui(job(JobPhase.FAILED, fail = FailReason.NO_LONGER_OFFERED)))
        assertEquals(Triple("Download failed", "The list of states and the download server disagree about Pennsylvania. Check the list again later.", listOf("Check again", "OK")),
            ui(job(JobPhase.FAILED, fail = FailReason.LIST_MISMATCH)))
        // the installer's words: only under Details, never in the view
        val rejected = StatesPresenter.jobUi(job(JobPhase.FAILED, fail = FailReason.REJECTED, detail = "manifest.json: bounds are not valid"), i)
        assertEquals("Pennsylvania can't be installed", rejected.title)
        assertEquals("Nothing was changed on this phone.", rejected.line)
        assertEquals(listOf("Details", "OK"), rejected.actions.map { it.label })
        assertFalse(listOfNotNull(rejected.title, rejected.line, rejected.hint, rejected.message).any { "bounds" in it })
        val details = StatesPresenter.present(i.copy(job = job(JobPhase.FAILED, fail = FailReason.REJECTED, detail = "manifest.json: bounds are not valid"), dialog = Dialog.Details("manifest.json: bounds are not valid"))).dialog!!
        assertEquals("manifest.json: bounds are not valid", details.text)
    }

    @Test
    fun cancelConfirmationAndTheBottomBarDuringAJob() {
        val ui = StatesPresenter.present(input(job = job(JobPhase.DOWNLOADING)).copy(dialog = Dialog.CancelJob))
        assertEquals("Stop downloading Pennsylvania?", ui.dialog!!.title)
        assertEquals("The 238 MB downloaded so far are deleted.", ui.dialog!!.text)
        assertEquals(listOf("Delete", "Keep downloading"), listOf(ui.dialog!!.confirm.label, ui.dialog!!.dismiss!!.label))
        assertEquals(listOf("Use a file instead", "Look at the map while you wait"), ui.bottomBar.map { it.label })
        assertEquals(StateStatus.IN_PROGRESS, ui.mapStatus["PA"])
    }

    @Test
    fun anotherStatesSheetDuringAJob() {
        val ohio = StatesPresenter.present(input(job = job(JobPhase.DOWNLOADING), selected = "OH", sheet = true)).sheet!!
        assertEquals("One state at a time. Pennsylvania is downloading.", ohio.connectionLine)
        assertFalse(ohio.primary!!.enabled)
    }

    @Test
    fun theSheetKeepsTheEntryItOpenedWith() {
        val opened = pa()
        val refreshed = catalog(pa(version = 202612010000, snapshot = "2026-12-01").copy(parts = pa().parts.take(1) + pa().parts.drop(1)), pa(id = "us-oh", state = "OH", name = "Ohio"))
        val s = StatesPresenter.present(input(catalog = refreshed, selected = "PA", sheet = true).copy(sheetPack = opened)).sheet!!
        assertEquals("Maps, directions and search for Pennsylvania, from OpenStreetMap data of Nov 14, 2026.", s.lines[0])
    }

    @Test
    fun theStatesScreen() {
        val installed = InstalledSet(scanned = true, packs = listOf(installedPa()))
        val ui = StatesPresenter.present(input(mode = StatesMode.MANAGE, installed = installed))
        assertEquals("States", ui.title)
        assertEquals("On this phone", ui.onPhoneTitle)
        val row = ui.onPhone.single()
        assertEquals("On this phone · data from Nov 14, 2026 · 1.1 GB", row.line)
        // when the pack was built: two packs of one OpenStreetMap day are told apart by it (the owner, 2026-11-21)
        assertEquals("Built Nov 15, 2026", row.builtLine)
        assertEquals("On this phone · data from Nov 14, 2026 · 1.1 GB. Built Nov 15, 2026. House numbers: included", row.stateDescription)
        val old = StatesPresenter.present(input(mode = StatesMode.MANAGE, installed = InstalledSet(scanned = true, packs = listOf(installedPa(version = null)))))
        assertEquals(null, old.onPhone.single().builtLine, "a pack without a build time says nothing")
        assertEquals("Built Nov 21, 2026", Texts.builtLine(202611210936))
        assertEquals(null, Texts.builtLine(7), "not a build time")
        assertEquals(null, Texts.builtLine(202613450000), "not a date")
        assertEquals("House numbers: included", row.houseNumbersLine)
        assertEquals("Remove house numbers", row.houseNumbersAction!!.label)
        assertEquals(listOf("Remove"), row.actions.map { it.label })
        assertEquals("Add a state", ui.addTitle)
        assertFalse(ui.available.any { it.code == "PA" })
        assertEquals("List checked Nov 16, 2026.", ui.checkedLine)
        assertEquals("Check again", ui.checkAgain!!.label)
        assertEquals(listOf("Use a file instead"), ui.bottomBar.map { it.label })

        val without = StatesPresenter.present(input(mode = StatesMode.MANAGE, installed = InstalledSet(scanned = true, packs = listOf(installedPa(addresses = false))))).onPhone.single()
        assertEquals("House numbers: not included", without.houseNumbersLine)
        assertEquals("Add house numbers · 31 MB", without.houseNumbersAction!!.label)
        val noPart = StatesPresenter.present(input(mode = StatesMode.MANAGE, installed = InstalledSet(scanned = true, packs = listOf(installedPa(addresses = null))))).onPhone.single()
        assertNull(noPart.houseNumbersLine)
        val other = StatesPresenter.present(input(mode = StatesMode.MANAGE, installed = InstalledSet(scanned = true, packs = listOf(installedPa(version = 1, addresses = false)))))
        assertEquals("Update available · data from Nov 14, 2026 · 548 MB download", other.onPhone.single().line)
        assertEquals(listOf("Update", "Remove"), other.onPhone.single().actions.map { it.label })
        assertTrue(other.onPhone.single().actions.all { it.enabled })
        // A finished job stays the engine's current one (DONE; FAILED until dismissed): it must not turn Update or Add
        // house numbers off (found on the device after a download: Update stayed off). One still running does.
        val oh = { phase: JobPhase -> job(phase, fail = if (phase == JobPhase.FAILED) FailReason.NO_LONGER_OFFERED else null).copy(packId = "us-oh", name = "Ohio", state = "OH") }
        val older = InstalledSet(scanned = true, packs = listOf(installedPa(version = 1, addresses = false)))
        for (phase in listOf(JobPhase.DONE, JobPhase.FAILED)) {
            val row = StatesPresenter.present(input(mode = StatesMode.MANAGE, installed = older, job = oh(phase))).onPhone.single()
            assertTrue(row.actions.single { it.label == "Update" }.enabled, "$phase")
        }
        val running = StatesPresenter.present(input(mode = StatesMode.MANAGE, installed = older, job = oh(JobPhase.DOWNLOADING))).onPhone.single()
        assertFalse(running.actions.single { it.label == "Update" }.enabled)
        val addLater = { j: DownloadJob? -> StatesPresenter.present(input(mode = StatesMode.MANAGE, installed = InstalledSet(scanned = true, packs = listOf(installedPa(addresses = false))), job = j)).onPhone.single().houseNumbersAction!! }
        assertTrue(addLater(oh(JobPhase.DONE)).enabled)
        assertFalse(addLater(oh(JobPhase.DOWNLOADING)).enabled)
        val oldVersion = StatesPresenter.present(input(mode = StatesMode.MANAGE, catalog = CatalogState.Failed(CatalogProblem.NO_NETWORK, null),
            installed = InstalledSet(scanned = true, packs = listOf(installedPa(addresses = false))))).onPhone.single()
        assertEquals("To add house numbers, remove Pennsylvania and download it again.", oldVersion.note)
        val copied = StatesPresenter.present(input(mode = StatesMode.MANAGE, installed = InstalledSet(scanned = true, packs = listOf(installedPa(verified = false))))).onPhone.single()
        assertTrue(copied.note!!.contains("copied outside the app, not checked"))
    }

    @Test
    fun removeIsDisabledDuringATripOrAJobAndItsDialogs() {
        val trip = StatesPresenter.present(input(mode = StatesMode.MANAGE, installed = InstalledSet(scanned = true, packs = listOf(installedPa()), changesHeldBy = "Not while guiding"))).onPhone.single()
        assertFalse(trip.actions.single { it.label == "Remove" }.enabled)
        assertEquals("You can't remove a state during a trip.", trip.note)
        assertFalse(trip.houseNumbersAction!!.enabled)
        val updating = StatesPresenter.present(input(mode = StatesMode.MANAGE, job = job(JobPhase.DOWNLOADING, kind = JobKind.UPDATE), installed = InstalledSet(scanned = true, packs = listOf(installedPa())))).onPhone.single()
        assertFalse(updating.actions.single { it.label == "Remove" }.enabled)
        assertEquals("Pennsylvania is being updated. Cancel the update to remove it.", updating.note)
        assertEquals("Downloading · 41 %", updating.line)

        val base = input(mode = StatesMode.MANAGE, installed = InstalledSet(scanned = true, packs = listOf(installedPa())))
        val remove = StatesPresenter.present(base.copy(dialog = Dialog.Remove("us-pa"))).dialog!!
        assertEquals("Remove Pennsylvania?", remove.title)
        assertEquals("Its maps, directions and search are deleted from this phone. You can download it again.", remove.text)
        assertEquals(listOf("Remove", "Keep"), listOf(remove.confirm.label, remove.dismiss!!.label))
        val hn = StatesPresenter.present(base.copy(dialog = Dialog.RemoveHouseNumbers("us-pa"))).dialog!!
        assertEquals("Remove house numbers from Pennsylvania?", hn.title)
        assertEquals("Searching for addresses like \"4319 Peach St\" stops working. This frees 80 MB.", hn.text)
        val failed = StatesPresenter.present(base.copy(removeProblem = "us-pa" to "files are in use"))
        assertEquals("Couldn't remove Pennsylvania. Nothing was changed.", failed.readyBanner!!.text)
        assertEquals("Details", failed.readyBanner!!.action!!.label)
        assertEquals("Ohio is ready.", StatesPresenter.present(input(mode = StatesMode.MANAGE, installed = InstalledSet(scanned = true,
            packs = listOf(installedPa().copy(packId = "us-oh", name = "Ohio", state = "OH")))).copy(readyPackId = "us-oh")).readyBanner!!.text)
        // states screen progress lines
        assertEquals("Waiting for Wi-Fi · 41 %", StatesPresenter.progressLine(job(JobPhase.WAITING, wait = WaitReason.NOT_UNMETERED)))
        assertEquals("Paused · 41 %", StatesPresenter.progressLine(job(JobPhase.PAUSED, pause = PauseReason.USER)))
        assertEquals("Installing…", StatesPresenter.progressLine(job(JobPhase.INSTALLING)))
    }

    @Test
    fun theMapChipNamesANewerPackUntilItWasSeen() {
        val installed = InstalledSet(scanned = true, packs = listOf(installedPa(version = 202611152241)))
        val newer = catalog(pa(version = 202612011200, snapshot = "2026-12-01"))
        val u = StatesPresenter.updates(newer, installed, emptyMap())
        assertEquals(listOf("us-pa"), u.map { it.first.packId })
        assertEquals("Pennsylvania: new map data" to "Pennsylvania: new map data. Opens States", StatesPresenter.updateChip(u))
        assertTrue(StatesPresenter.updates(newer, installed, mapOf("us-pa" to 202612011200)).isEmpty(), "seen on the States screen")
        assertEquals(1, StatesPresenter.updates(catalog(pa(version = 202612081200)), installed, mapOf("us-pa" to 202612011200)).size, "a newer one again")
        assertTrue(StatesPresenter.updates(catalog(pa()), installed, emptyMap()).isEmpty(), "the installed version")
        assertTrue(StatesPresenter.updates(CatalogState.NotLoaded, installed, emptyMap()).isEmpty(), "no list, no chip")
        assertNull(StatesPresenter.updateChip(emptyList()))
    }

    @Test
    fun updateAndAddHouseNumberSheets() {
        val installed = InstalledSet(scanned = true, packs = listOf(installedPa(version = 202611152241, addresses = true)))
        val newer = catalog(pa(version = 202612011200, snapshot = "2026-12-01"))
        val u = StatesPresenter.present(input(mode = StatesMode.MANAGE, catalog = newer, installed = installed, selected = "PA", sheet = true, kind = SheetKind.UPDATE)).sheet!!
        assertEquals("Update Pennsylvania", u.title)
        assertEquals(listOf("New OpenStreetMap data of Dec 1, 2026. Yours is from Nov 14, 2026.", "Download: 579 MB", "Your Pennsylvania keeps working until the update is installed."), u.lines)
        assertEquals("Adds 31 MB.", u.houseNumbers!!.hint)
        assertEquals(listOf("Update", "Not now"), listOf(u.primary!!.label, u.secondary!!.label))
        assertEquals(StateStatus.UPDATE_AVAILABLE, StatesPresenter.present(input(mode = StatesMode.MANAGE, catalog = newer, installed = installed)).mapStatus["PA"])

        val add = StatesPresenter.present(input(mode = StatesMode.MANAGE, installed = InstalledSet(scanned = true, packs = listOf(installedPa(addresses = false))),
            selected = "PA", sheet = true, kind = SheetKind.ADD_HOUSE_NUMBERS)).sheet!!
        assertEquals("Add house numbers to Pennsylvania", add.title)
        assertEquals(listOf("Find addresses like \"4319 Peach St\".", "Download: 31 MB. On this phone: 80 MB more.",
            "Sources: © OpenStreetMap contributors; U.S. Census Bureau TIGER/Line; USDOT NAD; City of Scranton"), add.lines)
        assertEquals(listOf("Add", "Cancel"), listOf(add.primary!!.label, add.secondary!!.label))
    }

    @Test
    fun theMapChip() {
        val none = InstalledSet(scanned = true)
        val some = InstalledSet(scanned = true, packs = listOf(installedPa()))
        assertEquals("Choose your state", StatesPresenter.chip(null, none)!!.first)
        assertEquals("Choose your state. Opens States", StatesPresenter.chip(null, none)!!.second)
        assertNull(StatesPresenter.chip(null, some))
        assertEquals("Downloading Pennsylvania · 41 %", StatesPresenter.chip(job(JobPhase.DOWNLOADING), none)!!.first)
        assertEquals("Waiting for Wi-Fi · Pennsylvania", StatesPresenter.chip(job(JobPhase.WAITING, wait = WaitReason.NOT_UNMETERED), none)!!.first)
        assertEquals("Download paused · Pennsylvania", StatesPresenter.chip(job(JobPhase.PAUSED, pause = PauseReason.USER), none)!!.first)
        assertEquals("Installing Pennsylvania…", StatesPresenter.chip(job(JobPhase.INSTALLING), none)!!.first)
        assertEquals("Pennsylvania wasn't installed", StatesPresenter.chip(job(JobPhase.FAILED, fail = FailReason.REJECTED), none)!!.first)
    }

    @Test
    fun notifications() {
        val d = StatesPresenter.notification(job(JobPhase.DOWNLOADING))
        assertEquals("Downloading Pennsylvania" to "238 MB of 579 MB", d.title to d.text)
        assertTrue(d.ongoing && d.pause && !d.useMobileData)
        assertEquals(41, d.progress)
        val wifi = StatesPresenter.notification(job(JobPhase.WAITING, wait = WaitReason.NOT_UNMETERED))
        assertTrue(wifi.useMobileData)
        val paused = StatesPresenter.notification(job(JobPhase.PAUSED, pause = PauseReason.USER))
        assertEquals("Download paused · 41 %" to "Open Beamline to continue.", paused.title to paused.text)
        assertFalse(paused.ongoing)
        val space = StatesPresenter.notification(job(JobPhase.PAUSED, pause = PauseReason.NO_SPACE))
        assertEquals("Download paused" to "Not enough space. Open Beamline to see what's needed.", space.title to space.text)
        val gone = StatesPresenter.notification(job(JobPhase.PAUSED, pause = PauseReason.SERVER_UNREACHABLE))
        assertEquals("Download paused" to "Couldn't reach the download server. Open Beamline to continue.", gone.title to gone.text)
        val system = StatesPresenter.notification(job(JobPhase.PAUSED, pause = PauseReason.SYSTEM_STOPPED))
        assertEquals("Download paused" to "Open Beamline to continue.", system.title to system.text)
        val done = StatesPresenter.notification(job(JobPhase.DONE))
        assertEquals("Pennsylvania is ready" to "Tap to open the map.", done.title to done.text)
        assertTrue(done.opensMap)
        val trip = StatesPresenter.notification(job(JobPhase.WAITING, wait = WaitReason.TRIP_IN_PROGRESS))
        assertEquals("It will be installed when your trip ends.", trip.text)
    }

    @Test
    fun theChooserAndTheMapHint() {
        val ui = StatesPresenter.present(input().copy(chooser = listOf("RI", "CT", "MA"), mapHint = true))
        assertEquals("Which one?", ui.chooser!!.title)
        assertEquals(listOf("Rhode Island", "Connecticut", "Massachusetts"), ui.chooser!!.rows.map { it.second })
        assertEquals("Tap a state, or pick it from the list.", ui.mapHint)
    }

    /** 1.4 "Using a file": the sheet after inspect, the installer's progress with the keep-open line, a refusal. */
    @Test
    fun usingAFile() {
        val file = FileChoice("us-pa-2026-11-14.tar", "Pennsylvania", "2026-11-14", 1_900_000_000, 80_000_000, emptyList(), "us-pa")
        val sheet = StatesPresenter.present(input().copy(file = file)).fileSheet!!
        assertEquals("Pennsylvania", sheet.title)
        assertEquals(listOf("From us-pa-2026-11-14.tar, OpenStreetMap data of Nov 14, 2026.", "Needs 1.9 GB free. Free now: 24 GB."), sheet.lines)
        assertEquals("House numbers", sheet.houseNumbers!!.label)
        assertEquals(listOf("Install", "Cancel"), listOf(sheet.primary!!.label, sheet.secondary!!.label))
        assertTrue(sheet.primary!!.enabled)
        // Without house numbers the need drops by their size.
        assertEquals("Needs 1.8 GB free. Free now: 24 GB.", StatesPresenter.present(input(include = false).copy(file = file)).fileSheet!!.lines[1])
        // An archive the installer cannot take: its problems are listed and Install is off.
        val old = StatesPresenter.present(input().copy(file = file.copy(problems = listOf("manifest_version 2 is an old pack format; rebuild the pack")))).fileSheet!!
        assertFalse(old.primary!!.enabled)
        // One state at a time: not while a download runs.
        val busy = StatesPresenter.present(input(job = job(JobPhase.DOWNLOADING)).copy(file = file)).fileSheet!!
        assertEquals("One state at a time. Pennsylvania is downloading.", busy.connectionLine)
        assertFalse(busy.primary!!.enabled)

        val running = io.github.spysyweeb.beamline.api.data.InstallStatus.Running("us-pa-2026-11-14.tar", InstallStep.COPYING, 250_000_000, 1_000_000_000, "routing.tar")
        val progress = StatesPresenter.present(input().copy(install = running, fileInstallName = "Pennsylvania")).fileProgress!!
        assertEquals("Installing Pennsylvania", progress.title)
        assertEquals("Checking the files…", progress.line)
        assertEquals("Keep Beamline open until the install has finished.", progress.hint)
        assertEquals(0.25f, progress.progress)
        assertTrue(progress.actions.isEmpty())
        val merging = StatesPresenter.present(input().copy(install = running.copy(step = InstallStep.MERGING_ROUTING), fileInstallName = "Pennsylvania")).fileProgress!!
        assertEquals("Combining routing data…", merging.line)
        // The download's own install is the job's progress view, not this one.
        assertNull(StatesPresenter.present(input().copy(install = running)).fileProgress)

        val words = "routing.tar does not match its manifest"
        val refused = StatesPresenter.present(input().copy(fileProblem = "Pennsylvania" to words))
        assertEquals("Pennsylvania can't be installed", refused.readyBanner!!.text)
        assertEquals("Nothing was changed on this phone.", refused.readyBanner!!.detail)
        assertEquals(listOf("Details", "OK"), listOf(refused.readyBanner!!.action!!.label, refused.readyBanner!!.secondary!!.label))
        assertFalse(listOfNotNull(refused.readyBanner!!.text, refused.readyBanner!!.detail).any { "manifest" in it })

        // A file the installer cannot read at all: the same refusal under the file's name, the draft text under Details only.
        val unreadable = StatesPresenter.present(input().copy(fileProblem = "notes.tar" to Texts.FILE_NOT_A_PACK))
        assertEquals("notes.tar can't be installed", unreadable.readyBanner!!.text)
        assertEquals("Nothing was changed on this phone.", unreadable.readyBanner!!.detail)
        assertEquals(StatesEvent.Details, unreadable.readyBanner!!.action!!.event)
        assertEquals("This file is not a Beamline state pack.", StatesPresenter.present(input().copy(dialog = Dialog.Details(Texts.FILE_NOT_A_PACK))).dialog!!.text)
    }
}
