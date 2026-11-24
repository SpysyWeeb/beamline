package io.github.spysyweeb.beamline.packs

/**
 * What this build of the app can read, part by part (pack format 3, docs/architecture.md 2.11).
 *
 * A pack is refused ([problem]) when its manifest version is not [maxManifestVersion], when a
 * required part is missing, or when the map, routing or routing index part has a format or version
 * this app cannot read. The search and addresses parts are judged on their own ([partProblem]): a
 * pack whose search part the app cannot read keeps its map and routing.
 *
 * The map reader comes from the style that ships in the app: the style is written for one tile
 * schema and one major version of it (assets/rules/map-compose.json).
 */
data class Readers(
    val maxManifestVersion: Int = PackManifest.SUPPORTED_VERSION,
    val mapFormat: String = "pmtiles",
    val mapFormatVersion: Int = 3,
    val mapSchema: String,
    val mapSchemaMajor: Int,
    val routingFormat: String = ROUTING_FORMAT,
    val routingFormatVersion: Int = 1,
    val routingIndexFormat: String = ROUTING_INDEX_FORMAT,
    val routingIndexFormatVersion: Int = 1,
    val searchFormat: String = SEARCH_FORMAT,
    val searchFormatVersion: Int = 1,
    val addressesFormat: String = ADDRESSES_FORMAT,
    val addressesFormatVersion: Int = 1,
    /**
     * The street-key fingerprint of the app's address normaliser (docs/architecture.md 2.11; the
     * search port computes it over its copy of fingerprint_spellings.json). A search part built
     * with another one is unusable (PackInfo.searchProblem); null: not checked here.
     */
    val addrKeyFingerprint: String? = null,
) {
    /** Why this app cannot use the pack, or null. */
    fun problem(m: PackManifest): String? {
        if (m.manifestVersion > maxManifestVersion) return PackManifest.newerFormat(m.manifestVersion)
        if (m.manifestVersion < maxManifestVersion) return PackManifest.oldFormat(m.manifestVersion)
        for (name in PackManifest.REQUIRED_PARTS) {
            if (name !in m.parts) return "the pack has no \"$name\" part"
        }
        val map = m.map
        if (map.format != mapFormat || map.formatVersion != mapFormatVersion) {
            return "map part is ${map.format} v${map.formatVersion}; this app reads $mapFormat v$mapFormatVersion"
        }
        val major = map.schemaVersion.substringBefore('.').toIntOrNull()
        if (map.schema != mapSchema || major != mapSchemaMajor) {
            return "map tiles are ${map.schema} ${map.schemaVersion}; this app's style draws $mapSchema $mapSchemaMajor.x"
        }
        partProblem(m, PackManifest.ROUTING)?.let { return "routing part is $it" }
        partProblem(m, PackManifest.ROUTING_INDEX)?.let { return "routing_index part is $it" }
        return null
    }

    /**
     * Why this app cannot read the part [name] of [m] ("beamline-search-sqlite v2; this app reads
     * beamline-search-sqlite v1"), or null when it can or the pack has no such part. Parts of names
     * this app does not know are never judged.
     */
    fun partProblem(m: PackManifest, name: String): String? {
        val part = m.allParts[name] ?: return null
        val (format, version) = when (name) {
            PackManifest.MAP -> mapFormat to mapFormatVersion
            PackManifest.ROUTING -> routingFormat to routingFormatVersion
            PackManifest.ROUTING_INDEX -> routingIndexFormat to routingIndexFormatVersion
            PackManifest.SEARCH -> searchFormat to searchFormatVersion
            PackManifest.ADDRESSES -> addressesFormat to addressesFormatVersion
            else -> return null
        }
        return if (part.format == format && part.formatVersion == version) null
        else "${part.format} v${part.formatVersion}; this app reads $format v$version"
    }

    companion object {
        const val ROUTING_FORMAT = "valhalla-tar"

        /** The routing builder's manifest (packbuilder/routing/build.py, beamline-routing-pack/1). */
        const val ROUTING_INDEX_FORMAT = "beamline-routing-pack"
        const val SEARCH_FORMAT = "beamline-search-sqlite"
        const val ADDRESSES_FORMAT = "beamline-addresses-sqlite"
    }
}

/** Cheap checks of a part file's first bytes, by the part's `format`. No hashing. */
object PartHeaders {

    /** Size of a PMTiles v3 header. */
    const val PMTILES_HEADER_BYTES = 127

    /**
     * Problem with [file] for a part of [format] whose manifest size is [expectedBytes], or null.
     * Formats without a known header get the size check only.
     */
    fun problem(file: java.io.File, format: String, expectedBytes: Long): String? {
        if (!file.isFile) return "missing"
        val size = file.length()
        if (size != expectedBytes) return "file has $size bytes, the manifest says $expectedBytes (copy not finished?)"
        return when (format) {
            "pmtiles" -> pmtilesProblem(file, size)
            Readers.ROUTING_FORMAT -> tarProblem(file, size)
            Readers.SEARCH_FORMAT, Readers.ADDRESSES_FORMAT -> sqliteProblem(file, size)
            Readers.ROUTING_INDEX_FORMAT -> jsonProblem(file, size)
            else -> null
        }
    }

    /**
     * PMTiles v3: magic and version, and the root directory, metadata, leaf directories and tile
     * data named in the header must lie inside the file. Catches foreign and cut-off files.
     */
    fun pmtilesProblem(file: java.io.File, size: Long = file.length()): String? {
        if (size < PMTILES_HEADER_BYTES) return "file has only $size bytes, too short for PMTiles"
        val h = readHead(file, PMTILES_HEADER_BYTES)
        if (String(h, 0, 7, Charsets.US_ASCII) != "PMTiles") return "not a PMTiles file"
        if (h[7].toInt() != 3) return "PMTiles version ${h[7]}, this app reads version 3"
        val buf = java.nio.ByteBuffer.wrap(h).order(java.nio.ByteOrder.LITTLE_ENDIAN)
        val sections = listOf("root directory" to 8, "metadata" to 24, "leaf directories" to 40, "tile data" to 56)
        for ((name, at) in sections) {
            val offset = buf.getLong(at)
            val length = buf.getLong(at + 8)
            if (offset < 0 || length < 0 || offset > size - length) {
                return "file is cut short or damaged: its $name ends past the end of the file ($size bytes)"
            }
        }
        if (buf.getLong(16) == 0L || buf.getLong(64) == 0L) return "the archive holds no tiles"
        return null
    }

    /** A POSIX tar: the first header block has the `ustar` magic at offset 257. */
    fun tarProblem(file: java.io.File, size: Long = file.length()): String? {
        if (size < 512) return "file has only $size bytes, too short for a tar"
        val h = readHead(file, 512)
        return if (String(h, 257, 5, Charsets.US_ASCII) == "ustar") null else "not a tar file"
    }

    fun sqliteProblem(file: java.io.File, size: Long = file.length()): String? {
        if (size < 100) return "file has only $size bytes, too short for SQLite"
        val h = readHead(file, 16)
        return if (String(h, 0, 16, Charsets.US_ASCII) == "SQLite format 3\u0000") null else "not a SQLite file"
    }

    /** A JSON object: its first byte that is not white space is '{'. */
    fun jsonProblem(file: java.io.File, size: Long = file.length()): String? {
        if (size < 2) return "file has only $size bytes, too short for JSON"
        val h = readHead(file, minOf(size, 64L).toInt())
        val first = h.firstOrNull { it.toInt() != 0x20 && it.toInt() != 0x09 && it.toInt() != 0x0a && it.toInt() != 0x0d }
        return if (first == '{'.code.toByte()) null else "not a JSON object"
    }

    private fun readHead(file: java.io.File, n: Int): ByteArray {
        val b = ByteArray(n)
        java.io.RandomAccessFile(file, "r").use { it.readFully(b) }
        return b
    }
}
