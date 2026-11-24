package io.github.spysyweeb.beamline.packs

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/** Writes small but complete packs (folders and tar archives) for the tests. */
object TestPacks {

    val READERS = Readers(mapSchema = "protomaps-basemap", mapSchemaMajor = 4)

    /** A format-3 manifest with every part (fixture; sizes and hashes are filled by [manifestFor]). */
    fun manifestText(): String =
        TestPacks::class.java.getResource("/manifest-v3.json")!!.readText(Charsets.UTF_8)

    /** A format-2 manifest, as the round-2 packs have (refused: "rebuild the pack"). */
    fun manifestV2Text(): String =
        TestPacks::class.java.getResource("/manifest-v2.json")!!.readText(Charsets.UTF_8)

    const val STYLE_DAY = """{
      "version": 8,
      "sources": {"protomaps": {"type": "vector", "url": "pmtiles://{{PACK_URL}}"}},
      "glyphs": "{{STYLE_BASE_URL}}/glyphs/{fontstack}/{range}.pbf",
      "sprite": "{{STYLE_BASE_URL}}/sprites/light",
      "layers": [
        {"id": "background", "type": "background", "paint": {"background-color": "#ffffff"}},
        {"id": "water", "type": "fill", "source": "protomaps", "source-layer": "water"},
        {"id": "roads", "type": "line", "source": "protomaps", "source-layer": "roads", "maxzoom": 12},
        {"id": "pois_dot", "type": "circle", "source": "protomaps", "source-layer": "pois"},
        {"id": "address", "type": "symbol", "source": "protomaps", "source-layer": "buildings", "minzoom": 18},
        {"id": "places", "type": "symbol", "source": "protomaps", "source-layer": "places"}
      ]
    }"""

    val STYLE_NIGHT = STYLE_DAY.replace("#ffffff", "#000000").replace("sprites/light", "sprites/dark")

    /**
     * The smallest archive the scanner accepts: a PMTiles v3 header whose root directory
     * (1 byte at 127) and tile data (1 byte at 128) lie inside the 129-byte file.
     */
    fun pmtiles(
        rootOffset: Long = 127, rootLength: Long = 1,
        tileOffset: Long = 128, tileLength: Long = 1,
        version: Int = 3, totalBytes: Int = 129,
    ): ByteArray {
        val b = ByteBuffer.allocate(maxOf(totalBytes, 0)).order(ByteOrder.LITTLE_ENDIAN)
        if (totalBytes >= PartHeaders.PMTILES_HEADER_BYTES) {
            b.put("PMTiles".toByteArray(Charsets.US_ASCII)).put(version.toByte())
            b.putLong(8, rootOffset).putLong(16, rootLength)
            b.putLong(24, 128).putLong(32, 0) // metadata (empty)
            b.putLong(40, 0).putLong(48, 0) // no leaf directories
            b.putLong(56, tileOffset).putLong(64, tileLength)
        }
        return b.array()
    }

    /** A tar with one small file: the routing part's stand-in. */
    fun routingTar(): ByteArray = tar(listOf("index.bin" to ByteArray(40) { it.toByte() }), endBlocks = true)

    /** The first 100 bytes of a SQLite file are its header; the scanner reads only the magic. */
    fun sqlite(): ByteArray = ByteArray(4096).also { "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII).copyInto(it) }

    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    /** The routing builder's manifest stand-in (the routing_index part). */
    fun routingJson(): ByteArray = """{"format": "beamline-routing-pack/1", "state": "pa", "tiles": []}""".toByteArray()

    /**
     * Part files of a complete format-3 pack: map, routing, routing index, search, addresses and a
     * part the app does not know.
     */
    fun partFiles(map: ByteArray = pmtiles()): Map<String, ByteArray> = linkedMapOf(
        "map.pmtiles" to map,
        "routing.tar" to routingTar(),
        "routing.json" to routingJson(),
        "search.sqlite" to sqlite(),
        "addresses.sqlite" to sqlite(),
        "future.bin" to "future data".toByteArray(),
    )

    /** The manifest for [files] with correct sizes and checksums, then [edit]ed as text. */
    fun manifestFor(
        files: Map<String, ByteArray>,
        id: String = "us-pa",
        base: String = manifestText(),
        edit: (String) -> String = { it },
    ): String {
        val base = PackManifest.json.parseToJsonElement(base).jsonObject
        val parts = base["parts"]!!.jsonObject.mapValues { (_, v) ->
            val p = v.jsonObject
            val f = (p["file"] as JsonPrimitive).content
            val data = files[f]
            if (data == null) p else JsonObject(p + mapOf("bytes" to JsonPrimitive(data.size), "sha256" to JsonPrimitive(sha256(data))))
        }
        val out = JsonObject(base + mapOf("parts" to JsonObject(parts), "pack_id" to JsonPrimitive(id)))
        // Compact JSON, so tests can edit it with plain string replacements ("bounds":[...]).
        return edit(kotlinx.serialization.json.Json.encodeToString(JsonObject.serializer(), out))
    }

    /** A valid installed pack folder [folder] with pack id [id]; [edit] may change the manifest text. */
    fun write(root: File, folder: String, id: String = folder, edit: (String) -> String = { it }): File {
        val dir = File(root, folder).apply { mkdirs() }
        val files = partFiles()
        File(dir, "manifest.json").writeText(manifestFor(files, id, edit = edit), Charsets.UTF_8)
        files.forEach { (name, data) -> File(dir, name).writeBytes(data) }
        return dir
    }

    /** A pack folder to install from (not under the packs root). */
    fun sourceFolder(dir: File, files: Map<String, ByteArray> = partFiles(), manifest: String = manifestFor(files)): File {
        dir.mkdirs()
        File(dir, "manifest.json").writeText(manifest, Charsets.UTF_8)
        files.forEach { (name, data) -> File(dir, name).writeBytes(data) }
        return dir
    }

    fun folderSource(dir: File) = PackSource.Folder(dir.name) { name -> File(dir, name).takeIf { it.isFile }?.inputStream() }

    fun archiveSource(bytes: ByteArray, label: String = "pack.tar") = PackSource.Archive(label) { bytes.inputStream() }

    /** A pack archive: manifest.json first, then the parts (as the Python pack tool writes it). */
    fun archive(files: Map<String, ByteArray> = partFiles(), manifest: String = manifestFor(files)): ByteArray =
        tar(listOf("manifest.json" to manifest.toByteArray(Charsets.UTF_8)) + files.toList())

    /** A minimal ustar writer for the tests (the real archives come from Python's tarfile). */
    fun tar(entries: List<Pair<String, ByteArray>>, endBlocks: Boolean = true): ByteArray {
        val out = ByteArrayOutputStream()
        for ((name, data) in entries) {
            val h = ByteArray(512)
            name.toByteArray(Charsets.UTF_8).copyInto(h, 0)
            octal(0b110100100, 8).copyInto(h, 100)
            octal(0, 8).copyInto(h, 108)
            octal(0, 8).copyInto(h, 116)
            octal(data.size.toLong(), 12).copyInto(h, 124)
            octal(1763236800, 12).copyInto(h, 136)
            h[156] = '0'.code.toByte()
            "ustar\u000000".toByteArray(Charsets.US_ASCII).copyInto(h, 257)
            for (i in 148 until 156) h[i] = ' '.code.toByte()
            val sum = h.sumOf { it.toInt() and 0xff }
            "%06o\u0000 ".format(sum).toByteArray(Charsets.US_ASCII).copyInto(h, 148)
            out.write(h)
            out.write(data)
            out.write(ByteArray(((512 - data.size % 512) % 512)))
        }
        if (endBlocks) out.write(ByteArray(1024))
        return out.toByteArray()
    }

    private fun octal(v: Long, len: Int): ByteArray =
        (v.toString(8).padStart(len - 1, '0') + "\u0000").toByteArray(Charsets.US_ASCII)
}
