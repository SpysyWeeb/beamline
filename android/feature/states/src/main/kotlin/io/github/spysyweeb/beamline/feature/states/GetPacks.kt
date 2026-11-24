// FILE: feature/states/src/main/kotlin/io/github/spysyweeb/beamline/feature/states/GetPacks.kt
package io.github.spysyweeb.beamline.feature.states

import android.content.Context
import android.content.Intent
import io.github.spysyweeb.beamline.api.data.InstalledData
import io.github.spysyweeb.beamline.api.data.PackInfo
import io.github.spysyweeb.beamline.download.CatalogState
import io.github.spysyweeb.beamline.download.DownloadJob
import io.github.spysyweeb.beamline.settings.SettingsStore
import io.github.spysyweeb.beamline.units.UnitFormatter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.io.File

data class GetPacksConfig(
    /** BuildConfig.CATALOG_URL; "" = downloads are off in this build. */
    val catalogUrl: String,
    /** BuildConfig.DEBUG: plain http to a private or loopback IPv4 address is allowed. */
    val allowCleartext: Boolean,
    /** :core:packs PackManifest.SUPPORTED_VERSION (the installer reads exactly one version today). */
    val minManifestVersion: Int,
    /** :core:packs PackManifest.SUPPORTED_VERSION. */
    val maxManifestVersion: Int,
    /** getExternalFilesDir(null)/downloads: on the volume of the packs folder. */
    val downloadsDir: File,
    /** filesDir/getpacks: catalog cache and ui.json. */
    val stateDir: File,
    /** The same function InstalledLibrary gets: File.usableSpace (section 2.6). */
    val freeBytes: (File) -> Long,
    /** Bytes of the merged routing tile set on the phone now: the files in InstalledLibrary's routing folder; 0 when none. */
    val routingSetBytes: () -> Long,
    /** Opens the app on the States screen (notification tap). */
    val openStates: () -> Intent,
    /** Opens the app on the map (tap on "ready"). */
    val openMap: () -> Intent,
)

/** The map's chip (section 1.5); I shows it at the top of the map and opens the States screen on a tap. */
data class MapChip(
    /** "Choose your state", "Downloading Pennsylvania · 43 %", "Download paused · Pennsylvania", … */
    val text: String,
    /** For TalkBack: the text plus "Opens States". */
    val description: String,
)

/** Track G's part of AppGraph: one per process. */
class GetPacks(
    context: Context,
    installed: InstalledData,
    settings: SettingsStore,
    units: StateFlow<UnitFormatter>,
    config: GetPacksConfig,
    scope: CoroutineScope,
) {
    val catalog: StateFlow<CatalogState> get() = core.catalog

    /** The work behind this class (GetPacksCore.kt): the screens and DownloadService use it. */
    internal val core = GetPacksCore(context, installed, settings, units, config, scope)
    val job: StateFlow<DownloadJob?> get() = core.job

    /**
     * False until the first scan (InstalledSet.scanned). Then true while no pack is on the phone
     * (none installed, none among the issues) and either the user has not picked a state, a file
     * or "Not now", or a download started on the first-run screen is not finished (any phase;
     * FAILED until dismissed). I shows StatesScreen(FIRST_RUN) after the driver notice while true.
     */
    val showFirstRunPicker: StateFlow<Boolean> get() = core.showFirstRunPicker

    /**
     * True once [showFirstRunPicker] is decided (ui.json read, the first scan done, an unfinished
     * download recovered when the app became visible, or no downloader): I holds the splash reveal
     * until then (it waits 2 s at most), so the map never shows for a moment before the picker.
     */
    val firstRunDecided: StateFlow<Boolean> get() = core.firstRunDecided

    /** The map's chip; null when there is nothing to show (a state installed, no job and no unseen update). */
    val mapChip: StateFlow<MapChip?> get() = core.mapChip

    /**
     * A pack to show: a first-run download finished (kept in ui.json until done, so it survives the
     * process and works from the launcher as well as from the "ready" notification), or the user
     * tapped "Show on map". I closes the States screen, fits the map to [PackInfo.bounds], shows
     * the snackbar, then calls [showOnMapDone]. Null when there is none.
     */
    val showOnMap: StateFlow<PackInfo?> get() = core.showOnMap

    /** I has moved the camera and shown the snackbar for [showOnMap]'s pack. */
    fun showOnMapDone(): Unit = core.showOnMapDone()

    /** From the activity's onStart: recovers (once per process) and continues an unfinished job by starting the service. */
    fun onAppVisible(): Unit = core.onAppVisible()

    /**
     * Item 40 (the owner, 2026-11-24): I calls this when the app becomes visible and the user allows it
     * (Online features, "Check for map updates"). It fetches the list of states, never a pack, when the
     * last fetch is a day old or more; [mapChip] then names an installed state with newer map data until
     * the user has seen it on the States screen.
     */
    fun checkForUpdates(): Unit = core.checkForUpdates()
}

/** Implemented by the Application (I) so DownloadService finds the process's GetPacks. */
interface GetPacksHost {
    val getPacks: GetPacks
}
