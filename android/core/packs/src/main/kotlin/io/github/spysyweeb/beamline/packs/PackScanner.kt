package io.github.spysyweeb.beamline.packs

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import java.io.File

/**
 * Written by the installer into the pack folder once every part's size and SHA-256 matched the
 * manifest (pack format 3: `{"installed_utc","verified":"sha256","source":"archive",
 * "skipped_parts":[],"routing_merged":true}`). Packs without it were copied some other way (adb, a
 * test script): they are used, but shown as not verified.
 */
@Serializable
data class InstallRecord(
    @SerialName("installed_utc") val installedUtc: String,
    /** "sha256": every part was hashed while it was copied and matched the manifest. */
    val verified: String,
    /** "archive" (a .tar file), "download" (a download folder, moved in) or "folder". */
    val source: String,
    /** Optional parts the install left out ("addresses"): their files are not in the pack folder. */
    @SerialName("skipped_parts") val skippedParts: List<String> = emptyList(),
    /**
     * The routing part's tiles are in the merged tile set; the pack folder keeps no routing.tar
     * (docs/architecture.md 2.11, decision 6.3).
     */
    @SerialName("routing_merged") val routingMerged: Boolean = false,
) {
    /** Parts of [m] whose files this record says are not in the pack folder. */
    fun absentParts(m: PackManifest): Set<String> =
        skippedParts.filter { it in PackManifest.OPTIONAL_PARTS && it in m.parts }.toSet() +
            (if (routingMerged && PackManifest.ROUTING in m.parts) setOf(PackManifest.ROUTING) else emptySet())

    companion object {
        const val FILE_NAME = "install-record.json"
        const val SOURCE_ARCHIVE = "archive"
        const val SOURCE_DOWNLOAD = "download"
        const val SOURCE_FOLDER = "folder"

        fun parse(text: String): InstallRecord = PackManifest.json.decodeFromString(serializer(), text)

        /** Every field written, defaults included (the record of 2.11 names them all). */
        fun encode(r: InstallRecord): String = recordJson.encodeToString(serializer(), r)

        private val recordJson = kotlinx.serialization.json.Json {
            encodeDefaults = true
            prettyPrint = true
        }
    }
}

/** A pack folder whose manifest was read and whose parts passed the cheap checks. */
data class InstalledPack(
    val dir: File,
    val manifest: PackManifest,
    /** Null when the pack was not installed by the app (no checksum check was made). */
    val install: InstallRecord?,
    /** Bytes of all files in the folder. */
    val bytesOnDisk: Long,
) {
    val id: String get() = manifest.packId

    /** A part file inside this pack. */
    fun file(name: String): File = File(dir, name)

    val mapFile: File get() = file(manifest.map.file)
}

/** Why a folder under the packs root was not used. */
data class PackProblem(val folder: String, val reason: String)

data class ScanResult(val packs: List<InstalledPack>, val problems: List<PackProblem>) {
    val isEmpty: Boolean get() = packs.isEmpty()
}

/**
 * Finds installed packs: every direct subfolder of [root] that holds a valid `manifest.json`.
 * Never throws for bad packs; they are reported in [ScanResult.problems] so the UI can say why.
 *
 * Checks are cheap enough to run on every app resume (no hashing; checksums are checked once, at
 * install): manifest fields, versions against [readers], bounds, every part present with the size
 * the manifest gives, and a header check per part format (PMTiles, tar, SQLite).
 */
class PackScanner(private val root: File, private val readers: Readers) {

    fun scan(): ScanResult {
        // Folders starting with "." are ignored: installs in progress and removals use such names.
        val folders = root.listFiles { f -> f.isDirectory && !f.name.startsWith(".") }
            ?.sortedBy { it.name } ?: emptyList()
        val packs = mutableListOf<InstalledPack>()
        val problems = mutableListOf<PackProblem>()
        for (dir in folders) {
            when (val r = read(dir)) {
                is Read.Ok -> packs += r.pack
                is Read.Bad -> problems += PackProblem(dir.name, r.reason)
            }
        }
        // Two folders with the same pack id: keep the first (sorted by name), report the rest.
        val kept = packs.distinctBy { it.id }
        (packs - kept.toSet()).forEach { problems += PackProblem(it.dir.name, "duplicate pack_id ${it.id}") }
        return ScanResult(kept, problems)
    }

    sealed interface Read {
        data class Ok(val pack: InstalledPack) : Read
        data class Bad(val reason: String) : Read
    }

    /** Reads and checks one pack folder. */
    fun read(dir: File): Read = try {
        readUnchecked(dir)
    } catch (e: Exception) {
        // the exception's own text is for engineers (and may hold a path): its class only
        Read.Bad("cannot read the pack (${e::class.simpleName})")
    }

    private fun readUnchecked(dir: File): Read {
        val manifestFile = File(dir, PackManifest.FILE_NAME)
        if (!manifestFile.isFile) return Read.Bad("no ${PackManifest.FILE_NAME}")
        val m = try {
            PackManifest.parse(manifestFile.readText(Charsets.UTF_8))
        } catch (e: Exception) {
            val msg = e.message?.lineSequence()?.firstOrNull().orEmpty()
            return Read.Bad(if (msg.startsWith("manifest_version")) msg else "unreadable ${PackManifest.FILE_NAME}: $msg")
        }
        manifestProblem(m, readers)?.let { return Read.Bad(it) }
        val record = File(dir, InstallRecord.FILE_NAME).takeIf { it.isFile }?.let {
            runCatching { InstallRecord.parse(it.readText(Charsets.UTF_8)) }.getOrNull()
        }
        // Skipped optional parts and the merged routing tar are, by the record, not in the folder.
        val absent = record?.absentParts(m).orEmpty()
        for ((name, part) in m.allParts) {
            if (name in absent) continue
            PartHeaders.problem(File(dir, part.file), part.format, part.bytes)?.let { return Read.Bad("$name part ${part.file}: $it") }
        }
        val bytes = dir.walkTopDown().filter { it.isFile }.sumOf { it.length() }
        return Read.Ok(InstalledPack(dir, m, record, bytes))
    }

    companion object {
        private val PACK_ID = Regex("[a-z0-9][a-z0-9._-]{0,63}")

        /** Everything about a manifest that can be checked without its files. */
        fun manifestProblem(m: PackManifest, readers: Readers): String? {
            readers.problem(m)?.let { return it }
            if (!PACK_ID.matches(m.packId)) {
                return "pack_id '${m.packId}' must be 1-64 of a-z 0-9 . _ -, starting with a-z or 0-9 (it names the folder)"
            }
            boundsProblem(m.bounds)?.let { return it }
            if (!m.osmSnapshot.matches(Regex("\\d{4}-\\d{2}-\\d{2}"))) return "osm_snapshot '${m.osmSnapshot}' must be YYYY-MM-DD"
            m.packVersion?.let { v ->
                if (v !in 100_001_010_000L..999_912_312_359L) return "pack_version $v must be the build time as YYYYMMDDhhmm"
            }
            val files = m.allParts.values.map { it.file }
            for ((name, part) in m.allParts) {
                if (!isPlainFileName(part.file)) return "$name part: file '${part.file}' must be a plain file name in the pack folder"
                if (part.bytes < 0) return "$name part: bytes must not be negative"
                if (!part.sha256.matches(Regex("[0-9a-fA-F]{64}"))) return "$name part: sha256 must be 64 hex digits"
            }
            if (files.size != files.toSet().size) return "two parts name the same file"
            return null
        }

        /** A file name directly in the pack folder: no folders, nothing hidden, not the manifest. */
        fun isPlainFileName(p: String): Boolean =
            p.isNotEmpty() && p.length <= 128 && !p.startsWith(".") && '/' !in p && '\\' !in p && ':' !in p &&
                p != PackManifest.FILE_NAME && p != InstallRecord.FILE_NAME && p.none { it.code < 32 }

        /**
         * Bounds must be usable as a camera target: four finite numbers, west < east within
         * -180..180, south < north within -90..90. (A pack across the antimeridian, e.g. Alaska's
         * Aleutians, must use west -180 and east 180.)
         */
        fun boundsProblem(b: List<Double>): String? {
            if (b.size != 4) return "bounds must be [west, south, east, north]"
            if (b.any { !it.isFinite() }) return "bounds must be finite numbers"
            val (w, s, e, n) = b
            if (w < -180.0 || e > 180.0 || w >= e) {
                return "bounds $b: west and east must be within -180..180 and west < east"
            }
            if (s < -90.0 || n > 90.0 || s >= n) {
                return "bounds $b: south and north must be within -90..90 and south < north"
            }
            return null
        }
    }
}
