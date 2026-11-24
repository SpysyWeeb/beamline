package io.github.spysyweeb.beamline.packs

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The `manifest.json` at the top of every pack (pack format 3: docs/pack-format.md,
 * docs/architecture.md 2.11).
 *
 * Plain JSON so the Python pack tools and a future comma 3X client can read and write it with
 * nothing but a JSON library. Unknown keys are ignored, so newer builders can add fields without
 * breaking older apps; a change older apps cannot read raises [manifestVersion]. Each part (map,
 * routing, routing_index, search, addresses) carries its own `format`, `format_version` and
 * `attribution`, so the app can refuse exactly the part it cannot read and credit exactly the parts
 * installed.
 *
 * Packs carry data only. The map style (style JSON, glyphs, sprites) ships inside the app.
 */
@Serializable
data class PackManifest(
    @SerialName("manifest_version") val manifestVersion: Int,
    @SerialName("pack_id") val packId: String,
    /**
     * The build time `YYYYMMDDhhmm` (UTC) of [createdUtc]: a higher number is a newer build of the
     * same [packId] (format 3; read into PackInfo.packVersion). Null in a manifest without one.
     */
    @SerialName("pack_version") val packVersion: Long? = null,
    val name: String,
    val country: String,
    val state: String,
    /** Date of the OpenStreetMap data every part was built from, `YYYY-MM-DD`. One per pack. */
    @SerialName("osm_snapshot") val osmSnapshot: String,
    @SerialName("created_utc") val createdUtc: String? = null,
    /** west, south, east, north in degrees. */
    val bounds: List<Double>,
    /**
     * The credit of the pack's required parts (the catalog's `credit`). The app credits the parts
     * installed instead ([creditFor]).
     */
    val attribution: String = "",
    /**
     * The pack's parts by name. Kept as raw JSON so a part this app does not know is still copied
     * and checked at install.
     */
    val parts: Map<String, JsonObject>,
) {
    /** Every part, with the fields all parts share. Throws if a part lacks them. */
    val allParts: Map<String, PartFile> by lazy { parts.mapValues { (_, v) -> json.decodeFromJsonElement(PartFile.serializer(), v) } }

    /** The map part (required). */
    val map: MapPart by lazy {
        val raw = parts[MAP] ?: throw IllegalArgumentException("the pack has no \"$MAP\" part")
        json.decodeFromJsonElement(MapPart.serializer(), raw)
    }

    val routing: RoutingPart? by lazy { parts[ROUTING]?.let { json.decodeFromJsonElement(RoutingPart.serializer(), it) } }

    /** The routing builder's manifest of the routing tiles (every tile with its SHA-256). */
    val routingIndex: PartFile? by lazy { allParts[ROUTING_INDEX] }

    val search: SearchPart? by lazy { parts[SEARCH]?.let { json.decodeFromJsonElement(SearchPart.serializer(), it) } }

    val addresses: AddressesPart? by lazy { parts[ADDRESSES]?.let { json.decodeFromJsonElement(AddressesPart.serializer(), it) } }

    /** Total size of all parts in bytes, from the manifest. */
    val totalBytes: Long get() = allParts.values.sumOf { it.bytes }

    /** Total size of the parts an install with [skipped] left out writes. */
    fun bytesWithout(skipped: Collection<String>): Long = allParts.filterKeys { it !in skipped }.values.sumOf { it.bytes }

    /**
     * The credit for the parts installed (every part but [skipped]): each part's `attribution`
     * split at "; ", in part order (map first), each item once. Falls back to [attribution] for
     * parts without their own. Never empty for a format-3 pack (its parts name OpenStreetMap).
     */
    fun creditFor(skipped: Collection<String> = emptyList()): String {
        val order = listOf(MAP, ROUTING, ROUTING_INDEX, SEARCH, ADDRESSES)
        val names = allParts.keys.filter { it !in skipped }.sortedBy { order.indexOf(it).let { i -> if (i < 0) order.size else i } }
        val items = LinkedHashSet<String>()
        for (n in names) {
            val text = allParts.getValue(n).attribution ?: attribution
            for (item in text.split(";")) item.trim().takeIf { it.isNotEmpty() }?.let { items += it }
        }
        if (items.isEmpty()) attribution.trim().takeIf { it.isNotEmpty() }?.let { items += it }
        return items.joinToString("; ")
    }

    companion object {
        /**
         * The manifest_version this code reads: 3. Version 2 packs are refused ("an old pack format;
         * rebuild the pack"), newer ones too ("update the app"). Version 1 was the skeleton's.
         */
        const val SUPPORTED_VERSION = 3
        const val FILE_NAME = "manifest.json"
        const val MAP = "map"
        const val ROUTING = "routing"
        const val ROUTING_INDEX = "routing_index"
        const val SEARCH = "search"
        const val ADDRESSES = "addresses"

        /** Parts an install may skip (format 3: house numbers). */
        val OPTIONAL_PARTS: Set<String> = setOf(ADDRESSES)

        /** Parts a format-3 pack must have. */
        val REQUIRED_PARTS: List<String> = listOf(MAP, ROUTING, ROUTING_INDEX, SEARCH)

        /** The refusal of a pack older than this app reads (docs/architecture.md 2.11). */
        fun oldFormat(version: Int) = "manifest_version $version is an old pack format; rebuild the pack"

        fun newerFormat(version: Int) = "manifest_version $version is newer than this app reads ($SUPPORTED_VERSION): update the app"

        val json = Json {
            ignoreUnknownKeys = true
            prettyPrint = true
        }

        /** The manifest_version of [text] without reading the rest; null when it has none. */
        fun versionOf(text: String): Int? =
            ((json.parseToJsonElement(text) as? JsonObject)?.get("manifest_version") as? JsonPrimitive)?.content?.toIntOrNull()

        /**
         * Parses and checks that the shared part fields are present (lazy fields are forced).
         * A manifest of another version is refused by its version number first, so the message
         * says "update the app" or "rebuild the pack" rather than naming a missing field.
         */
        fun parse(text: String): PackManifest {
            val version = versionOf(text) ?: throw IllegalArgumentException("no manifest_version")
            if (version > SUPPORTED_VERSION) throw IllegalArgumentException(newerFormat(version))
            if (version < SUPPORTED_VERSION) throw IllegalArgumentException(oldFormat(version))
            val m = json.decodeFromString(serializer(), text)
            m.allParts
            m.map
            m.routing
            m.search
            m.addresses
            return m
        }
    }
}

/** Fields every part has. `file` is a plain file name in the pack folder (no folders). */
@Serializable
data class PartFile(
    val file: String,
    val format: String,
    @SerialName("format_version") val formatVersion: Int,
    val bytes: Long,
    val sha256: String,
    /** The credit of this part's data (format 3), e.g. "© OpenStreetMap contributors". */
    val attribution: String? = null,
)

@Serializable
data class MapPart(
    val file: String,
    /** `pmtiles` */
    val format: String,
    /** PMTiles spec version, 3. */
    @SerialName("format_version") val formatVersion: Int,
    /** Tile schema, e.g. `protomaps-basemap`. The app's style is written for one schema. */
    val schema: String,
    /** Version of the tile schema, e.g. `4.15.2`; the style needs the same major version. */
    @SerialName("schema_version") val schemaVersion: String,
    @SerialName("min_zoom") val minZoom: Int? = null,
    @SerialName("max_zoom") val maxZoom: Int? = null,
    /** `mvt` (format 3). */
    @SerialName("tile_type") val tileType: String? = null,
    /** Beamline's additions to the schema: `lanes_minzoom`, `top_zoom` (format 3). */
    @SerialName("schema_extensions") val schemaExtensions: JsonObject? = null,
    /** What the tiles were clipped to: source file name, its SHA-256, state (format 3). */
    val clip: JsonObject? = null,
    val bytes: Long,
    val sha256: String,
    val attribution: String? = null,
)

/** A Valhalla tile tar (with index.bin). Its tiles go into the one merged tile set at install. */
@Serializable
data class RoutingPart(
    val file: String,
    val format: String,
    @SerialName("format_version") val formatVersion: Int,
    @SerialName("valhalla_version") val valhallaVersion: String? = null,
    /** Packs can share routing tiles only when they come from the same graph build. */
    @SerialName("graph_build") val graphBuild: String? = null,
    val bytes: Long,
    val sha256: String,
    val attribution: String? = null,
)

/** The search index: SQLite with FTS5 and R*Tree. */
@Serializable
data class SearchPart(
    val file: String,
    val format: String,
    @SerialName("format_version") val formatVersion: Int,
    /** The address normaliser's table version the street join keys were made with. */
    @SerialName("addr_key_table_version") val addrKeyTableVersion: Int? = null,
    /** The street-key fingerprint (docs/architecture.md 2.11) of the normaliser the index was built with. */
    @SerialName("addr_key_fingerprint") val addrKeyFingerprint: String? = null,
    /** LF-normalised SHA-256 of the ranking.json the index was built with. */
    @SerialName("ranking_sha256") val rankingSha256: String? = null,
    val bytes: Long,
    val sha256: String,
    val attribution: String? = null,
)

/** House numbers (optional part): SQLite, the slim variant. */
@Serializable
data class AddressesPart(
    val file: String,
    val format: String,
    @SerialName("format_version") val formatVersion: Int,
    @SerialName("normalisation_table_version") val normalisationTableVersion: Int? = null,
    /** The street-key fingerprint of the normaliser the file was built with; equals the search part's. */
    @SerialName("key_fingerprint") val keyFingerprint: String? = null,
    /** `slim`. */
    val variant: String? = null,
    val bytes: Long,
    val sha256: String,
    /** Names every address source in the file (OpenStreetMap, TIGER/Line, NAD, city layers). */
    val attribution: String? = null,
)
