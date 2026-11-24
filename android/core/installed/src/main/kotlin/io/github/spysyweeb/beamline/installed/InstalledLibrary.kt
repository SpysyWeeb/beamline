// FILE: core/installed/src/main/kotlin/io/github/spysyweeb/beamline/installed/InstalledLibrary.kt
package io.github.spysyweeb.beamline.installed

import io.github.spysyweeb.beamline.api.data.ArchiveInfo
import io.github.spysyweeb.beamline.api.data.InstallStatus
import io.github.spysyweeb.beamline.api.data.InstalledData
import io.github.spysyweeb.beamline.api.data.InstalledSet
import io.github.spysyweeb.beamline.api.data.PackArchive
import io.github.spysyweeb.beamline.packs.InstallRules
import io.github.spysyweeb.beamline.packs.Readers
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/**
 * The installed state packs of this device: one install and removal flow for map, routing (merged
 * into one tile set), search and addresses (section 2.2, pack format 3 in docs/pack-format.md).
 * [packsDir] holds one folder per pack, [routingDir] the merged routing tile set and its
 * valhalla.json. [readers] says what the app can read (ComposeRules.readers()); [rules] are the
 * install rules (assets/rules/packs.json). File work runs on [io]; [freeBytes] is the usable space
 * of a folder.
 */
class InstalledLibrary(
    packsDir: File,
    routingDir: File,
    readers: Readers,
    rules: InstallRules,
    scope: CoroutineScope,
    io: CoroutineDispatcher,
    freeBytes: (File) -> Long = { it.usableSpace },
) : InstalledData {
    override val installed: StateFlow<InstalledSet> get() = core.installed

    /** The work (InstalledCore.kt); internal so the module's tests can reach its fault hook. */
    internal val core = InstalledCore(packsDir, routingDir, readers, rules, scope, io, freeBytes)
    override val install: StateFlow<InstallStatus> get() = core.install
    override suspend fun refresh(): Unit = core.refresh()
    override suspend fun inspect(archive: PackArchive): ArchiveInfo = core.inspect(archive)
    override fun startInstall(archive: PackArchive, includeAddresses: Boolean): Boolean = core.startInstall(archive, includeAddresses)
    override fun cancelInstall(): Unit = core.cancelInstall()
    override fun dismissInstallResult(): Unit = core.dismissInstallResult()
    override suspend fun remove(packId: String): String? = core.remove(packId)
    override fun holdChanges(reason: String): AutoCloseable? = core.holdChanges(reason)

    // The installer hand-off for downloads (docs/architecture-downloads.md 6.6).
    override val installsFromFolder: Boolean get() = true

    override fun startInstallFromFolder(
        folder: io.github.spysyweeb.beamline.api.data.PackFolder,
        includeAddresses: Boolean,
    ): Boolean = core.startInstallFromFolder(folder, includeAddresses)

    override val changesOptionalParts: Boolean get() = true

    override fun startAddOptionalParts(folder: io.github.spysyweeb.beamline.api.data.PackFolder): Boolean =
        core.startAddOptionalParts(folder)

    override suspend fun removeOptionalParts(packId: String): String? = core.removeOptionalParts(packId)
}
