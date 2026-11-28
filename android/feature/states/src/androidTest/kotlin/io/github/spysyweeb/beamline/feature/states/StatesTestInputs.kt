package io.github.spysyweeb.beamline.feature.states

import androidx.test.platform.app.InstrumentationRegistry
import io.github.spysyweeb.beamline.api.data.ArchiveInfo
import io.github.spysyweeb.beamline.api.data.InstallStatus
import io.github.spysyweeb.beamline.api.data.InstalledData
import io.github.spysyweeb.beamline.api.data.InstalledSet
import io.github.spysyweeb.beamline.api.data.PackArchive
import io.github.spysyweeb.beamline.api.data.PackInfo
import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.download.Catalog
import io.github.spysyweeb.beamline.download.CatalogLicence
import io.github.spysyweeb.beamline.download.CatalogPack
import io.github.spysyweeb.beamline.download.CatalogPart
import io.github.spysyweeb.beamline.download.CatalogState
import io.github.spysyweeb.beamline.download.CatalogView
import io.github.spysyweeb.beamline.download.DownloadJob
import io.github.spysyweeb.beamline.download.JobKind
import io.github.spysyweeb.beamline.download.JobPhase
import io.github.spysyweeb.beamline.download.JobProgress
import io.github.spysyweeb.beamline.download.NetworkState
import io.github.spysyweeb.beamline.download.TransferFile
import io.github.spysyweeb.beamline.feature.states.map.StateShapes
import io.github.spysyweeb.beamline.feature.states.model.StatesInput
import io.github.spysyweeb.beamline.feature.states.model.StatesPresenter
import io.github.spysyweeb.beamline.feature.states.model.StatesUi
import io.github.spysyweeb.beamline.units.UnitFormatter
import io.github.spysyweeb.beamline.units.UnitRules
import io.github.spysyweeb.beamline.units.UnitSystem
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.IOException
import java.time.ZoneId

/**
 * The device tests' one copy of their inputs (states.md, settings-misc.md P64): the real shapes, a
 * catalog that offers only Pennsylvania (every other state is "Not available yet"), the presenter's
 * own screen for a mode, Pennsylvania on this phone, and an InstalledData that holds a set and does
 * nothing else. Made-up and public data only.
 */
internal object StatesTestInputs {
    val shapes: StateShapes by lazy {
        InstrumentationRegistry.getInstrumentation().targetContext.assets.open("states/us_states.json")
            .use { StateShapes.parse(it.readBytes().decodeToString()) }
    }
    private const val HEX = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
    const val NOW = 1_794_830_400_000L // Nov 16, 2026, 12:00 UTC

    private fun part(name: String, file: String, bytes: Long, transfer: Long) =
        CatalogPart(name, file, false, bytes, HEX, TransferFile("../us-pa-1/$file.gz", if (transfer == bytes) "identity" else "gzip", transfer, HEX), "© OpenStreetMap contributors")

    /** Moved from ReadingOrderTest unchanged. */
    val pa = CatalogPack(
        "us-pa", "PA", "Pennsylvania", 202611160419, 3, "2026-11-14", "© OpenStreetMap contributors", Bounds(-80.51985, 39.7198, -74.68956, 42.51607),
        TransferFile("../us-pa-1/manifest.json", "identity", 1237, HEX),
        listOf(part("search", "search.sqlite", 154_705_920, 63_346_929), part("routing", "routing.tar", 634_429_440, 240_430_781), part("map", "map.pmtiles", 244_703_514, 244_703_514)),
    )
    val catalog = CatalogState.Ready(
        CatalogView(
            Catalog(1, "2026-11-16T08:55:21Z", CatalogLicence("ODbL-1.0", "Open Database License 1.0", "https://opendatacommons.org/licenses/odbl/1-0/", "Map data © OpenStreetMap contributors, available under the Open Database License."), listOf(pa), emptyList()),
            NOW, fromCache = false,
        ),
    )

    /** Pennsylvania on this phone (the same build as [pa]); with [addresses], house numbers included, so its row offers "Remove house numbers". */
    fun paInstalled(addresses: Boolean = false): InstalledSet = InstalledSet(
        scanned = true,
        packs = listOf(
            PackInfo(
                "us-pa", "Pennsylvania", "PA", "2026-11-14", Bounds(-80.5, 39.7, -74.7, 42.5), "© OpenStreetMap contributors", "/p/us-pa", "/p/us-pa/map.pmtiles",
                0, 14, "/p/us-pa/search.sqlite", if (addresses) "/p/us-pa/addresses.sqlite" else null, false, null, null, true, true, 1_033_800_000, 202611160419,
            ),
        ),
    )

    val downloading = DownloadJob(
        "us-pa", 202611160419, "Pennsylvania", "PA", "2026-11-14", JobKind.INSTALL, false, false, true, JobPhase.DOWNLOADING,
        JobProgress(238_000_000, 548_481_239, "routing", 1_900_000),
    )

    fun input(
        mode: StatesMode,
        job: DownloadJob? = null,
        installed: InstalledSet = InstalledSet(scanned = true),
        query: String = "",
        selected: String? = null,
        sheetOpen: Boolean = false,
        changesOptionalParts: Boolean = false,
    ): StatesInput = StatesInput(
        mode = mode, names = shapes.states.associate { it.code to it.name }, neighbours = shapes.states.associate { it.code to it.neighbours },
        catalog = catalog, installed = installed, job = job, network = NetworkState(connected = true, unmetered = true),
        units = UnitFormatter(UnitRules.bundled(), UnitSystem.IMPERIAL), nowMs = NOW, zone = ZoneId.of("America/New_York"),
        freeBytes = 9_900_000_000, moves = true, query = query, selected = selected, sheetOpen = sheetOpen,
        changesOptionalParts = changesOptionalParts,
    )

    /** The presenter's screen for these inputs. */
    fun ui(
        mode: StatesMode,
        job: DownloadJob? = null,
        installed: InstalledSet = InstalledSet(scanned = true),
        query: String = "",
        selected: String? = null,
        sheetOpen: Boolean = false,
        changesOptionalParts: Boolean = false,
    ): StatesUi = StatesPresenter.present(input(mode, job, installed, query, selected, sheetOpen, changesOptionalParts))
}

/** An InstalledData that holds [set] and does nothing else (no files, no installer). */
internal class StubInstalled(set: InstalledSet) : InstalledData {
    override val installed: StateFlow<InstalledSet> = MutableStateFlow(set)
    override val install: StateFlow<InstallStatus> = MutableStateFlow(InstallStatus.Idle)
    override suspend fun refresh() = Unit
    override suspend fun inspect(archive: PackArchive): ArchiveInfo = throw IOException("not in this test")
    override fun startInstall(archive: PackArchive, includeAddresses: Boolean): Boolean = false
    override fun cancelInstall() = Unit
    override fun dismissInstallResult() = Unit
    override suspend fun remove(packId: String): String? = null
    override fun holdChanges(reason: String): AutoCloseable? = null
}
