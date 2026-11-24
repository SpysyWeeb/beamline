package io.github.spysyweeb.beamline.installed

import io.github.spysyweeb.beamline.api.data.PackArchive
import io.github.spysyweeb.beamline.api.data.PackFolder
import io.github.spysyweeb.beamline.packs.Readers
import io.github.spysyweeb.beamline.routing.packs.RoutingPackManager
import io.github.spysyweeb.beamline.routing.packs.TileTar
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.MessageDigest

/**
 * Small but complete format-3 state packs for the installer's rule tests: a PMTiles header, a
 * routing tar of synthetic Valhalla tiles (version at byte 16, as RoutingPackManager reads it) with
 * its routing.json, SQLite headers for search and addresses. The real archives are read by
 * RealArchivesInstallTest.
 */
object MiniStates {

    const val OSM = "© OpenStreetMap contributors"
    const val ADDRESS_CREDIT = "$OSM; U.S. Census Bureau TIGER/Line; USDOT National Address Database; City of Scranton GIS"
    const val FP = "97c8d281115ec4d4b186051b18efe3f15bb5b1c74d60902854665f83d228d73a"
    const val BUILD = "261114-pa-oh-v3.9.1-b53087"

    val READERS = Readers(mapSchema = "protomaps-basemap", mapSchemaMajor = 4)

    /** Level-2 tiles of each mini state; 2/000/752/000 is on both (a tile on the state line). */
    val TILES = mapOf(
        "pa" to listOf("2/000/752/000.gph", "2/000/752/001.gph", "0/002/904.gph"),
        "oh" to listOf("2/000/752/000.gph", "2/000/751/999.gph", "0/002/903.gph"),
    )

    fun tile(path: String, salt: Int = 0): ByteArray {
        val b = ByteArray(300 + path.length * 7 + salt)
        path.toByteArray().copyInto(b, 40)
        "3.9.1".toByteArray().copyInto(b, 16)
        b[b.size - 1] = salt.toByte()
        return b
    }

    fun sha256(b: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(b).joinToString("") { "%02x".format(it) }

    fun pmtiles(): ByteArray {
        val b = ByteBuffer.allocate(129).order(ByteOrder.LITTLE_ENDIAN)
        b.put("PMTiles".toByteArray(Charsets.US_ASCII)).put(3.toByte())
        b.putLong(8, 127).putLong(16, 1).putLong(24, 128).putLong(32, 0).putLong(40, 0).putLong(48, 0).putLong(56, 128).putLong(64, 1)
        return b.array()
    }

    fun sqlite(size: Int, salt: Int): ByteArray = ByteArray(size).also {
        "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII).copyInto(it)
        it[size - 1] = salt.toByte()
    }

    /** One state's part files by file name and its manifest text. */
    class Pack(val id: String, val files: Map<String, ByteArray>, val manifest: String) {
        val manifestBytes: ByteArray get() = manifest.toByteArray(Charsets.UTF_8)

        /** The archive: manifest.json first, then the parts from the smallest to the largest. */
        fun archive(): ByteArray = tar(listOf("manifest.json" to manifestBytes) + files.entries.sortedBy { it.value.size }.map { it.key to it.value })

        fun archiveSource(label: String = "$id.tar") = PackArchive(label) { archive().inputStream() }

        /** A download folder as the downloader leaves it: manifest.json and the part files. */
        fun folder(dir: File, parts: Collection<String> = files.keys): PackFolder {
            dir.mkdirs()
            File(dir, "manifest.json").writeBytes(manifestBytes)
            for (name in parts) File(dir, name).writeBytes(files.getValue(name))
            return PackFolder(dir.name, dir.absolutePath)
        }
    }

    /**
     * A format-3 pack of the mini state [state] ("pa" or "oh"); [version] its pack_version, [salt]
     * changes the bytes (a newer build), [searchFp] / [manifestVersion] for the refusal tests;
     * [build] the routing graph build, [routingState] the state its routing.json names (and whose
     * tiles it holds), [packId] its pack_id.
     */
    fun pack(
        state: String,
        version: Long = 202611160400,
        salt: Int = 0,
        searchFp: String = FP,
        manifestVersion: Int = 3,
        routingTmp: File,
        build: String = BUILD,
        routingState: String = state,
        packId: String = "us-$state",
    ): Pack {
        val tiles = TILES.getValue(routingState).associateWith { tile(it, if (it == "2/000/752/000.gph") 0 else salt) }
        routingTmp.mkdirs()
        val tarFile = File.createTempFile("routing-$routingState", ".tar", routingTmp)
        TileTar.write(tarFile, tiles.keys.sorted(), 1_700_000_000L) { tiles.getValue(it) }
        val tarBytes = tarFile.readBytes()
        tarFile.delete()
        val index = buildJsonObject {
            put("format", "beamline-routing-pack/1")
            put("state", routingState)
            put("graph_build", build)
            put("valhalla_version", "3.9.1")
            put("tar", "routing-$routingState-261114.tar")
            put("tar_bytes", tarBytes.size)
            put("tar_sha256", RoutingPackManager.sha256(tarBytes))
            put("tiles", JsonArray(tiles.keys.sorted().map { p ->
                buildJsonObject {
                    put("path", p)
                    put("level", p.substringBefore('/').toInt())
                    put("bytes", tiles.getValue(p).size)
                    put("sha256", RoutingPackManager.sha256(tiles.getValue(p)))
                }
            }))
        }.toString().toByteArray()
        val files = linkedMapOf(
            "map.pmtiles" to pmtiles(),
            "routing.tar" to tarBytes,
            "routing.json" to index,
            "search.sqlite" to sqlite(8192, salt),
            "addresses.sqlite" to sqlite(16384, salt + 1),
        )
        val usps = state.uppercase()
        val bounds = if (state == "pa") listOf(-80.52, 39.72, -74.69, 42.52) else listOf(-84.82, 38.40, -80.52, 42.33)
        fun part(file: String, extra: Map<String, Any>, credit: String = OSM): JsonObject {
            val b = files.getValue(file)
            val m = LinkedHashMap<String, kotlinx.serialization.json.JsonElement>()
            m["file"] = JsonPrimitive(file)
            for ((k, v) in extra) m[k] = when (v) {
                is Number -> JsonPrimitive(v)
                is JsonObject -> v
                else -> JsonPrimitive(v.toString())
            }
            m["bytes"] = JsonPrimitive(b.size)
            m["sha256"] = JsonPrimitive(sha256(b))
            m["attribution"] = JsonPrimitive(credit)
            return JsonObject(m)
        }
        val manifest = buildJsonObject {
            put("manifest_version", manifestVersion)
            put("pack_id", packId)
            put("pack_version", version)
            put("name", if (state == "pa") "Pennsylvania" else "Ohio")
            put("country", "US")
            put("state", usps)
            put("osm_snapshot", "2026-11-14")
            put("created_utc", "2026-11-16T04:00:00Z")
            put("bounds", JsonArray(bounds.map { JsonPrimitive(it) }))
            put("attribution", OSM)
            put("parts", JsonObject(linkedMapOf(
                "map" to part("map.pmtiles", mapOf("format" to "pmtiles", "format_version" to 3, "schema" to "protomaps-basemap",
                    "schema_version" to "4.15.2", "min_zoom" to 0, "max_zoom" to 14, "tile_type" to "mvt")),
                "routing" to part("routing.tar", mapOf("format" to "valhalla-tar", "format_version" to 1, "valhalla_version" to "3.9.1", "graph_build" to build)),
                "routing_index" to part("routing.json", mapOf("format" to "beamline-routing-pack", "format_version" to 1)),
                "search" to part("search.sqlite", mapOf("format" to "beamline-search-sqlite", "format_version" to 1,
                    "addr_key_table_version" to 2, "addr_key_fingerprint" to searchFp, "ranking_sha256" to "c".repeat(64))),
                "addresses" to part("addresses.sqlite", mapOf("format" to "beamline-addresses-sqlite", "format_version" to 1,
                    "normalisation_table_version" to 2, "key_fingerprint" to searchFp, "variant" to "slim"), ADDRESS_CREDIT),
            )))
        }
        return Pack(packId, files, manifest.toString())
    }

    /** A minimal ustar writer (the real archives come from Python's tarfile). */
    fun tar(entries: List<Pair<String, ByteArray>>): ByteArray {
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
            out.write(ByteArray((512 - data.size % 512) % 512))
        }
        out.write(ByteArray(1024))
        return out.toByteArray()
    }

    private fun octal(v: Long, len: Int): ByteArray = (v.toString(8).padStart(len - 1, '0') + "\u0000").toByteArray(Charsets.US_ASCII)
}
