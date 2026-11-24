package io.github.spysyweeb.beamline.packs

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Where a pack to install comes from. Android implements these over the system file picker. */
sealed interface PackSource {
    /** Shown in messages, e.g. the file name. */
    val label: String

    /** A pack archive: an uncompressed tar with manifest.json as its first entry, parts at the top. */
    class Archive(override val label: String, val open: () -> InputStream) : PackSource

    /** A pack folder: manifest.json and the part files side by side. [open] returns null if missing. */
    class Folder(override val label: String, val open: (fileName: String) -> InputStream?) : PackSource
}

data class InstallProgress(val copiedBytes: Long, val totalBytes: Long, val file: String)

/** A failed install, with a message for the screen. Nothing of the pack is left behind. */
class InstallException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** The install was cancelled. Nothing of the pack is left behind. */
class InstallCancelledException : Exception("cancelled")

/**
 * Rules for installing packs, read from the app's `assets/rules/packs.json` (rules live in data
 * files). Field names are the JSON keys.
 */
@Serializable
data class InstallRules(
    /** Kept free beyond the pack itself, so the phone is never filled to the last byte. */
    @SerialName("free_space_margin_mb") val freeSpaceMarginMb: Long = 200,
) {
    val freeSpaceMarginBytes: Long get() = freeSpaceMarginMb * 1_000_000

    companion object {
        fun parse(text: String): InstallRules = PackManifest.json.decodeFromString(serializer(), text)
    }
}

/**
 * Installs packs into [root] (one folder per pack, named by its pack_id) so that a pack is either
 * fully there or not there at all:
 *
 * 1. the manifest is read first and checked (versions, names, bounds, free space) before any
 *    large copy starts;
 * 2. every part is copied into a hidden staging folder `.staging-<id>-<n>`, hashed with SHA-256
 *    while it is copied, and compared with the manifest's size and checksum (the only time the
 *    checksums are checked; afterwards the app does the cheap size and header check);
 * 3. the staged pack must pass the same checks as an installed one ([PackScanner]);
 * 4. it is renamed into place. An older copy of the same pack is first renamed aside and deleted
 *    after the switch.
 *
 * Any failure or a cancel deletes the staging folder. Folders starting with "." are never shown
 * as packs, and [cleanUp] removes or restores what a crash may leave (call it before scanning).
 */
class PackInstaller(
    private val root: File,
    private val readers: Readers,
    private val now: () -> Instant = Instant::now,
    private val rules: InstallRules = InstallRules(),
    /** Bytes free for the packs folder (a parameter so tests can fill the phone). */
    private val freeSpace: () -> Long = { root.usableSpace },
) {

    private val scanner = PackScanner(root, readers)

    /**
     * Installs from [source]. [progress] is called after every copied chunk (1 MiB) from the
     * calling thread; [isCancelled] is polled as often. Blocking: call it on an IO thread.
     * Throws [InstallException] or [InstallCancelledException]; returns the installed pack.
     */
    fun install(
        source: PackSource,
        progress: (InstallProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ): InstalledPack {
        cleanUp()
        root.mkdirs()
        var staging: File? = null
        try {
            return when (source) {
                is PackSource.Archive -> source.open().use { input ->
                    val tar = TarReader(input.buffered(BUFFER))
                    val first = tar.next() ?: throw InstallException("${source.label} is an empty archive")
                    if (!first.isFile || first.name != PackManifest.FILE_NAME) {
                        throw InstallException("${source.label}: the first entry must be ${PackManifest.FILE_NAME}, found '${first.name}'")
                    }
                    if (first.size > MAX_MANIFEST) throw InstallException("${PackManifest.FILE_NAME} is too large (${first.size} bytes)")
                    val text = tar.entryStream.readBytes().toString(Charsets.UTF_8)
                    val m = checkManifest(text, source)
                    staging = stage(m, text)
                    val expected = m.allParts.values.associateBy { it.file }
                    val done = mutableSetOf<String>()
                    var copied = 0L
                    while (true) {
                        val e = tar.next() ?: break
                        val part = expected[e.name]
                        if (!e.isFile || part == null || e.name in done) continue // folders, extras: not part of the pack
                        if (e.size != part.bytes) {
                            throw InstallException("${e.name} is ${e.size} bytes in the archive, the manifest says ${part.bytes}")
                        }
                        copied = copyVerified(tar.entryStream, File(staging, e.name), part, copied, m.totalBytes, progress, isCancelled)
                        done += e.name
                    }
                    val missing = expected.keys - done
                    if (missing.isNotEmpty()) throw InstallException("${source.label} lacks ${missing.sorted().joinToString()}")
                    finish(m, staging!!, "archive")
                }
                is PackSource.Folder -> {
                    val text = (source.open(PackManifest.FILE_NAME) ?: throw InstallException("${source.label} has no ${PackManifest.FILE_NAME}"))
                        .use { readLimited(it) }
                    val m = checkManifest(text, source)
                    staging = stage(m, text)
                    var copied = 0L
                    for (part in m.allParts.values) {
                        val input = source.open(part.file) ?: throw InstallException("${source.label} lacks ${part.file}")
                        copied = input.use { copyVerified(it.buffered(BUFFER), File(staging, part.file), part, copied, m.totalBytes, progress, isCancelled) }
                    }
                    finish(m, staging!!, "folder")
                }
            }
        } catch (e: InstallException) {
            throw e
        } catch (e: InstallCancelledException) {
            throw e
        } catch (e: TarFormatException) {
            throw InstallException("${source.label} is not a pack file: it is not a tar archive", e)
        } catch (e: IOException) {
            // Write errors are turned into their own message where they happen ([writing]); what
            // arrives here failed while reading the source.
            throw InstallException("reading ${source.label} failed: ${e.message ?: e::class.simpleName}", e)
        } finally {
            staging?.let { if (it.exists()) it.deleteRecursively() }
        }
    }

    /** Removes an installed pack folder: renamed aside first, so it is never seen half-deleted. */
    fun remove(folder: String) {
        require(PackScanner.isPlainFileName(folder)) { "not a pack folder name: $folder" }
        val dir = File(root, folder)
        if (!dir.isDirectory) return
        val aside = File(root, ".removing-$folder-${System.nanoTime()}")
        if (!dir.renameTo(aside)) throw IOException("cannot move $folder aside to remove it")
        aside.deleteRecursively()
    }

    /**
     * Deletes leftovers of an interrupted install or removal. If a crash came between moving an
     * old copy aside and moving the new one in, the old copy is put back.
     */
    fun cleanUp() {
        val hidden = root.listFiles { f -> f.isDirectory && f.name.startsWith(".") } ?: return
        for (f in hidden) {
            when {
                f.name.startsWith(".old-") -> {
                    val folder = f.name.removePrefix(".old-").substringBeforeLast('-')
                    val target = File(root, folder)
                    if (!target.exists() && PackScanner.isPlainFileName(folder)) f.renameTo(target) else f.deleteRecursively()
                }
                f.name.startsWith(".staging-") || f.name.startsWith(".removing-") -> f.deleteRecursively()
            }
        }
    }

    private fun checkManifest(text: String, source: PackSource): PackManifest {
        val m = try {
            PackManifest.parse(text)
        } catch (e: Exception) {
            val msg = e.message?.lineSequence()?.firstOrNull().orEmpty()
            throw InstallException("${source.label}: " + if (msg.startsWith("manifest_version")) msg else "unreadable ${PackManifest.FILE_NAME}: $msg")
        }
        PackScanner.manifestProblem(m, readers)?.let { throw InstallException("${m.name}: $it") }
        val need = m.totalBytes + rules.freeSpaceMarginBytes
        // 0 free bytes is a full phone, not "unknown": the packs folder exists at this point.
        val free = freeSpace()
        if (free < need) {
            throw InstallException("${m.name} needs ${mb(need)} MB of free space, the phone has ${mb(free)} MB")
        }
        return m
    }

    private fun stage(m: PackManifest, manifestText: String): File {
        val dir = File(root, ".staging-${m.packId}-${System.nanoTime()}")
        if (!dir.mkdirs()) throw InstallException("cannot create ${dir.name} in the packs folder")
        writing(PackManifest.FILE_NAME) { File(dir, PackManifest.FILE_NAME).writeText(manifestText, Charsets.UTF_8) }
        return dir
    }

    /** Runs a write into the app's storage; a failure gets a message about writing, not reading. */
    private fun <T> writing(file: String, block: () -> T): T = try {
        block()
    } catch (e: IOException) {
        throw InstallException(writeFailure(file, e), e)
    }

    private fun copyVerified(
        input: InputStream,
        dest: File,
        part: PartFile,
        copiedBefore: Long,
        total: Long,
        progress: (InstallProgress) -> Unit,
        isCancelled: () -> Boolean,
    ): Long {
        val digest = MessageDigest.getInstance("SHA-256")
        val buf = ByteArray(BUFFER)
        var n = 0L
        val out = writing(part.file) { FileOutputStream(dest) }
        try {
            while (true) {
                if (isCancelled()) throw InstallCancelledException()
                val r = input.read(buf) // a failure here is a read error of the source
                if (r < 0) break
                if (n + r > part.bytes) throw InstallException("${part.file} is longer than the manifest says (${part.bytes} bytes)")
                writing(part.file) { out.write(buf, 0, r) }
                digest.update(buf, 0, r)
                n += r
                progress(InstallProgress(copiedBefore + n, total, part.file))
            }
            writing(part.file) { out.fd.sync() }
        } finally {
            try {
                out.close()
            } catch (ignored: IOException) {
                // a failed write or sync has already been reported
            }
        }
        if (n != part.bytes) throw InstallException("${part.file} ends after $n of ${part.bytes} bytes")
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (!hex.equals(part.sha256, ignoreCase = true)) {
            throw InstallException("${part.file} is damaged: its SHA-256 does not match the manifest")
        }
        return copiedBefore + n
    }

    private fun finish(m: PackManifest, staging: File, how: String): InstalledPack {
        val record = InstallRecord(now().truncatedTo(ChronoUnit.SECONDS).toString(), "sha256", how)
        writing(InstallRecord.FILE_NAME) {
            File(staging, InstallRecord.FILE_NAME).writeText(InstallRecord.encode(record), Charsets.UTF_8)
        }
        when (val r = scanner.read(staging)) {
            is PackScanner.Read.Bad -> throw InstallException("${m.name}: ${r.reason}")
            is PackScanner.Read.Ok -> Unit
        }
        // Other folders holding the same pack (e.g. copied with adb under another name) are replaced too.
        val replaced = scanner.scan().packs.filter { it.id == m.packId }.map { it.dir } + File(root, m.packId)
        val aside = mutableListOf<Pair<File, File>>()
        try {
            for (old in replaced.distinct().filter { it.exists() }) {
                val to = File(root, ".old-${old.name}-${System.nanoTime()}")
                if (!old.renameTo(to)) throw InstallException("cannot move the installed ${old.name} aside")
                aside += old to to
            }
            val target = File(root, m.packId)
            if (!staging.renameTo(target)) throw InstallException("cannot move the new pack into place")
        } catch (e: Exception) {
            aside.forEach { (old, to) -> to.renameTo(old) }
            throw e
        }
        aside.forEach { (_, to) -> to.deleteRecursively() }
        return (scanner.read(File(root, m.packId)) as? PackScanner.Read.Ok)?.pack
            ?: throw InstallException("${m.name} was installed but cannot be read back")
    }

    // (InputStream.readNBytes is Android API 33+, this module must run on API 26.)
    private fun readLimited(input: InputStream): String {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (true) {
            val r = input.read(buf)
            if (r < 0) break
            out.write(buf, 0, r)
            if (out.size() > MAX_MANIFEST) throw InstallException("${PackManifest.FILE_NAME} is too large")
        }
        return out.toString("UTF-8")
    }

    private fun mb(b: Long) = (b + 999_999) / 1_000_000

    companion object {
        const val BUFFER = 1 shl 20

        /** A safety limit on reading manifest.json into memory (a real one is about 1 KB). */
        const val MAX_MANIFEST = 1L shl 20

        /** The screen's message for a failed write into the packs folder. */
        fun writeFailure(file: String, e: IOException): String {
            val msg = e.message.orEmpty()
            return if ("ENOSPC" in msg || "No space left" in msg) {
                "The phone ran out of storage space while copying $file. Free some space and try again."
            } else {
                "Writing $file into the app's storage failed: ${msg.ifEmpty { e::class.simpleName.orEmpty() }}"
            }
        }
    }
}
