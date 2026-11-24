// FILE: core/download/src/main/kotlin/io/github/spysyweeb/beamline/download/PackHandoff.kt
package io.github.spysyweeb.beamline.download

import io.github.spysyweeb.beamline.api.data.ArchiveInfo
import io.github.spysyweeb.beamline.api.data.InstalledData
import io.github.spysyweeb.beamline.api.data.PackArchive
import io.github.spysyweeb.beamline.api.data.PackFolder
import java.io.File
import java.io.InputStream

/** Gives a finished download to the installer (track P's InstalledData). */
interface PackHandoff {
    /** True: the installer moves the parts (no second copy). False: it copies them from a stream. */
    val moves: Boolean

    /** The installer's reading of [folder]'s manifest.json; nothing is copied. */
    suspend fun inspect(folder: File, label: String): ArchiveInfo

    /** Starts the install of [folder]; false when the installer is busy or changes are held. */
    fun start(folder: File, label: String, includeOptional: Boolean): Boolean

    /** Adds the optional parts in [folder] to the installed pack (JobKind.ADD_OPTIONAL); false as [start]. */
    fun startAddOptional(folder: File, label: String): Boolean
}

/** P's folder install (InstalledData.startInstallFromFolder): parts are moved. */
class FolderHandoff(private val installed: InstalledData) : PackHandoff {
    override val moves: Boolean get() = true
    override suspend fun inspect(folder: File, label: String): ArchiveInfo =
        installed.inspect(PackArchive(label) { VirtualTar.stream(listOf("manifest.json" to File(folder, "manifest.json"))) })
    override fun start(folder: File, label: String, includeOptional: Boolean): Boolean =
        installed.startInstallFromFolder(PackFolder(label, folder.absolutePath), includeOptional)
    override fun startAddOptional(folder: File, label: String): Boolean =
        installed.startAddOptionalParts(PackFolder(label, folder.absolutePath))
}

/**
 * The stand-in until P's folder install exists: the frozen archive install, fed a tar made on the
 * fly from the folder (manifest.json first, then the parts in manifest order). The installer copies,
 * so the parts need their space twice; [Space] counts that.
 */
class ArchiveStreamHandoff(private val installed: InstalledData) : PackHandoff {
    override val moves: Boolean get() = false
    override suspend fun inspect(folder: File, label: String): ArchiveInfo =
        installed.inspect(PackArchive(label) { VirtualTar.stream(listOf("manifest.json" to File(folder, "manifest.json"))) })
    override fun start(folder: File, label: String, includeOptional: Boolean): Boolean =
        installed.startInstall(PackArchive(label) { VirtualTar.stream(VirtualTar.packFiles(folder)) }, includeOptional)
    /** The frozen archive install cannot add a part: the UI hides "Add house numbers" (1.2). */
    override fun startAddOptional(folder: File, label: String): Boolean = false
}

fun handoffFor(installed: InstalledData): PackHandoff =
    if (installed.installsFromFolder) FolderHandoff(installed) else ArchiveStreamHandoff(installed)

/** An uncompressed ustar stream made from files on disk while it is read (nothing is written). */
object VirtualTar {
    /**
     * ustar: per file a 512-byte header (name as given, mode 0644, uid and gid 0, empty user and group
     * names, size and mtime in octal, type '0', magic "ustar\u000000"), the bytes, zero padding to 512;
     * then two zero blocks. Names must be plain file names of at most 100 bytes.
     */
    fun stream(files: List<Pair<String, File>>): InputStream = TarStream.stream(files)

    /** manifest.json, then every part file the manifest names that exists in [folder], in manifest order. */
    fun packFiles(folder: File): List<Pair<String, File>> = TarStream.packFiles(folder)
}
