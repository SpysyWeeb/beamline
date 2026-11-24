// FILE: core/api/src/main/kotlin/io/github/spysyweeb/beamline/api/data/InstalledData.kt
package io.github.spysyweeb.beamline.api.data

import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.api.model.GeoPoint
import kotlinx.coroutines.flow.StateFlow
import java.io.InputStream
import kotlin.math.floor

/** One installed state pack, as every consumer sees it. Paths are absolute. */
data class PackInfo(
    val packId: String,
    val name: String,
    /** USPS code, "PA". */
    val state: String,
    val osmSnapshot: String,
    val bounds: Bounds,
    /**
     * The credit for the parts installed, from the manifest's per-part attribution: OpenStreetMap
     * for map, routing and search; the address sources (TIGER/Line, NAD, city layers) only when the
     * addresses part is installed. Shown on the map's credit line and in About.
     */
    val attribution: String,
    val folder: String,
    val mapFile: String,
    val mapMinZoom: Int,
    val mapMaxZoom: Int,
    /** Null when the pack has no usable search part ([searchProblem] says why when it has one). */
    val searchFile: String?,
    /** Null when the pack has no addresses part or the user skipped it ([addressesSkipped]). */
    val addressesFile: String?,
    val addressesSkipped: Boolean,
    /** Why the search part is not used ("built with another address normaliser"); null when fine. */
    val searchProblem: String?,
    /** SHA-256 of the ranking rules the search part was built with (search meta ranking_sha256). */
    val searchRankingSha256: String?,
    /** The pack's routing tiles are in the merged tile set ([InstalledSet.routing]). */
    val inRoutingSet: Boolean,
    /** Checksums were verified at install (false: copied in some other way, e.g. adb). */
    val verified: Boolean,
    val bytesOnDisk: Long,
    /**
     * The manifest's pack_version (pack format 3: the build time as YYYYMMDDhhmm, UTC); null for a
     * pack without one. A higher number is a newer build of the same pack_id.
     */
    val packVersion: Long? = null,
)

/** The merged routing tile set of all installed packs (one Valhalla tar, one graph build). */
data class RoutingSet(
    /** valhalla.json to open the engine with (ValhallaEngine.open). */
    val engineConfigFile: String,
    val graphBuild: String,
    val valhallaVersion: String,
    val states: List<String>,
    /** Valhalla level-2 tile ids present (0.25 degree tiles). */
    val level2Tiles: Set<Int>,
) {
    /** Whether a point lies on an installed routing tile. */
    fun covers(p: GeoPoint): Boolean = level2TileId(p) in level2Tiles

    companion object {
        /** Same as :routing:core Geo.tileId(p, 2): row-major from (-90, -180), 0.25 degree tiles. */
        fun level2TileId(p: GeoPoint): Int {
            val cols = (360.0 / 0.25).toInt()
            val row = floor((p.lat + 90.0) / 0.25).toInt()
            val col = floor((p.lon + 180.0) / 0.25).toInt()
            return row * cols + col
        }
    }
}

data class PackIssue(val folder: String, val reason: String)

/**
 * One immutable view of what is installed. A new one, with a higher [generation], is published
 * after every install, update, removal or rescan that changed anything.
 */
data class InstalledSet(
    val generation: Long = 0,
    val scanned: Boolean = false,
    val packs: List<PackInfo> = emptyList(),
    /** Null when no installed pack has routing tiles. */
    val routing: RoutingSet? = null,
    val issues: List<PackIssue> = emptyList(),
    /** Non-null while installs and removals are refused; the text says why ("Not while guiding"). */
    val changesHeldBy: String? = null,
)

/** A pack archive the user picked (system file picker). [open] may be called more than once. */
class PackArchive(val label: String, val open: () -> InputStream)

/**
 * A pack already on this device as a folder (the downloader's): manifest.json and the part files
 * side by side, on the same volume as the packs folder. [dir] is an absolute path.
 */
class PackFolder(val label: String, val dir: String)

/** What an archive holds, read from its manifest only (nothing copied). */
data class ArchiveInfo(
    val packId: String,
    val name: String,
    val state: String,
    val osmSnapshot: String,
    /** Bytes the install needs with every part, the routing merge's peak included. */
    val neededBytes: Long,
    /** Size of the addresses part; null when the archive has none. */
    val addressesBytes: Long?,
    val replacesInstalled: Boolean,
    val freeBytes: Long,
    /** Empty when it can be installed. Free space is never listed here: compare [neededBytes] with [freeBytes]. */
    val problems: List<String>,
)

enum class InstallStep { CHECKING, COPYING, MERGING_ROUTING, FINISHING }

sealed interface InstallStatus {
    data object Idle : InstallStatus
    data class Running(val label: String, val step: InstallStep, val doneBytes: Long, val totalBytes: Long, val file: String?) : InstallStatus
    data class Done(val name: String, val osmSnapshot: String, val skippedParts: List<String>) : InstallStatus
    data class Failed(val label: String, val message: String) : InstallStatus
    data class Cancelled(val label: String) : InstallStatus
}

/** Installed packs for every screen and for the session. Implemented by :core:installed (track P). */
interface InstalledData {
    val installed: StateFlow<InstalledSet>
    val install: StateFlow<InstallStatus>

    /** Rescans (cheap checks only) and finishes or undoes what a killed process left half done. */
    suspend fun refresh()

    /** Reads an archive's manifest; nothing is copied. */
    suspend fun inspect(archive: PackArchive): ArchiveInfo

    /** Starts an install; false when one is running or changes are held. */
    fun startInstall(archive: PackArchive, includeAddresses: Boolean): Boolean

    /** True when [startInstallFromFolder] is implemented (track P). */
    val installsFromFolder: Boolean get() = false

    /**
     * Starts an install from [folder] that moves its part files into the pack (rename, no copy)
     * after checking each one's size and SHA-256 against the folder's manifest.json, then goes on as
     * [startInstall] does (merge, checks, rename into place, a new generation). The free-space check
     * counts only bytes that are not already in [folder]. On failure or cancel every moved file is
     * moved back into [folder]. Progress and result in [install]; install-record.json says
     * "source": "download". False when an install is running, changes are held, or
     * [installsFromFolder] is false.
     */
    fun startInstallFromFolder(folder: PackFolder, includeAddresses: Boolean): Boolean = false

    /** True when [startAddOptionalParts] and [removeOptionalParts] are implemented (track P). */
    val changesOptionalParts: Boolean get() = false

    /**
     * Adds the optional parts found in [folder] (today only "addresses") to the installed pack whose
     * pack_id and pack_version equal those of [folder]'s manifest.json. Each part file is checked
     * (size and SHA-256 against that manifest) and moved into the pack folder; then the install
     * record (skipped_parts) is replaced atomically and a new generation is published with the
     * pack's addressesFile and attribution updated. No other file of the pack is touched. Progress
     * and result in [install]. False when an install is running, changes are held, no such pack is
     * installed, or [changesOptionalParts] is false.
     */
    fun startAddOptionalParts(folder: PackFolder): Boolean = false

    /**
     * Removes the optional parts of the installed pack [packId]: the install record first, a new
     * generation (search closes the old address handle, rule 3 of section 2.2), then the files.
     * Returns null when removed, else why not (plain words); refused while changes are held.
     */
    suspend fun removeOptionalParts(packId: String): String? = "This version of Beamline can't remove house numbers."

    fun cancelInstall()
    fun dismissInstallResult()

    /** Removes a pack with all its parts. Returns null when removed, else why not (plain words). */
    suspend fun remove(packId: String): String?

    /**
     * Refuses installs and removals until the returned handle is closed (the session holds it
     * while guiding). Returns null, holding nothing, while an install or removal is running: the
     * caller does not start guidance then (rule P5, ProblemKind.PACKS_BUSY).
     */
    fun holdChanges(reason: String): AutoCloseable?
}
