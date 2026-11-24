// FILE: core/download/src/main/kotlin/io/github/spysyweeb/beamline/download/Catalog.kt
package io.github.spysyweeb.beamline.download

import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.download.net.CatalogAddress

/** How one file travels (docs/catalog-format.md). [path] is relative to the catalog's address. */
data class TransferFile(
    val path: String,
    /** "identity" (the file as installed) or "gzip" (one gzip member of it). */
    val encoding: String,
    /** Size of the file on the server. */
    val bytes: Long,
    /** SHA-256 of the file on the server, 64 lowercase hex digits. */
    val sha256: String,
)

/** One part of a pack, as named in its manifest (`parts.<name>`). */
data class CatalogPart(
    /** "map", "routing", "routing_index", "search", "addresses", or a later part. */
    val name: String,
    /** The file name inside the pack folder (the manifest's `file`). */
    val file: String,
    /** True for a part the user may skip ("addresses"). */
    val optional: Boolean,
    /** Size and SHA-256 of the file as installed: equal to the manifest's. */
    val bytes: Long,
    val sha256: String,
    val transfer: TransferFile,
    /** The part's own credit (the manifest part's `attribution`); null when it has none. */
    val credit: String?,
)

/** One state pack the server offers. */
data class CatalogPack(
    /** The manifest's pack_id, "us-pa". */
    val id: String,
    /** USPS code, "PA". */
    val state: String,
    val name: String,
    /** The manifest's pack_version (YYYYMMDDhhmm of its build, UTC). */
    val packVersion: Long,
    val manifestVersion: Int,
    /** "2026-11-14": the date of the OpenStreetMap data. */
    val osmSnapshot: String,
    /** The pack's credit line (manifest `attribution`). */
    val credit: String,
    val bounds: Bounds,
    /** The pack's manifest.json, always "identity". */
    val manifest: TransferFile,
    /** In download order: the manifest's parts, small ones first. */
    val parts: List<CatalogPart>,
) {
    fun chosenParts(includeOptional: Boolean): List<CatalogPart> = parts.filter { includeOptional || !it.optional }

    /** Bytes that travel over the network. */
    fun downloadBytes(includeOptional: Boolean): Long = manifest.bytes + chosenParts(includeOptional).sumOf { it.transfer.bytes }

    /** Bytes of the pack folder once everything is unpacked (before the installer merges routing). */
    fun unpackedBytes(includeOptional: Boolean): Long = manifest.bytes + chosenParts(includeOptional).sumOf { it.bytes }

    /** The optional parts' download size; 0 when there are none. */
    fun optionalDownloadBytes(): Long = parts.filter { it.optional }.sumOf { it.transfer.bytes }
}

data class CatalogLicence(
    /** SPDX id, "ODbL-1.0". */
    val id: String,
    val name: String,
    val url: String,
    /** Shown with the download ("Map data © OpenStreetMap contributors, available under ..."). */
    val notice: String,
)

/** NEEDS_NEWER_APP: "Needs a newer version". OLD_FORMAT and BROKEN: listed as "Not available yet". */
enum class Unusable { NEEDS_NEWER_APP, OLD_FORMAT, BROKEN }

/** An entry this app lists but cannot download; [state] and [name] are null when unreadable. */
data class UnusablePack(val id: String, val state: String?, val name: String?, val reason: Unusable, val detail: String)

data class Catalog(
    /** Grows with every catalog the builder writes (its UTC time as YYYYMMDDhhmmss). */
    val serial: Long,
    val generatedUtc: String,
    val licence: CatalogLicence,
    val packs: List<CatalogPack>,
    val unusable: List<UnusablePack>,
)

enum class CatalogRefusal {
    /** `format` is beamline-catalog/N with N > 1. */
    NEWER_FORMAT,
    /** Not JSON, not an object, no or another `format`, a required top-level field missing or too long, a licence URL that is not https. */
    NOT_A_CATALOG,
    /** Larger than DownloadRules.catalogMaxBytes. */
    TOO_LARGE,
    /** A path breaks the path rule or leaves the catalog's scope: the whole list is refused. */
    OUTSIDE_SCOPE,
}

sealed interface CatalogParse {
    data class Ok(val catalog: Catalog) : CatalogParse
    data class Refused(val reason: CatalogRefusal, val detail: String) : CatalogParse
}

object CatalogFormat {
    const val FORMAT = "beamline-catalog/1"
    const val FORMAT_PREFIX = "beamline-catalog/"
    val ENCODINGS: Set<String> = setOf("identity", "gzip")

    /** Longest accepted strings (section 2.3): an entry over a limit is BROKEN, a licence over one refuses the list. */
    const val MAX_NAME = 64
    const val MAX_CREDIT = 300
    const val MAX_LICENCE_NAME = 100
    const val MAX_LICENCE_URL = 200
    const val MAX_NOTICE = 300
    const val MAX_GENERATED_UTC = 32

    /**
     * Parses and checks a catalog (rules in section 2.3 of docs/architecture-downloads.md). Entries this app
     * cannot use go to [Catalog.unusable]: a manifest_version above [maxManifestVersion] or an unknown
     * encoding is NEEDS_NEWER_APP; one below [minManifestVersion] is OLD_FORMAT; a missing,
     * malformed or too long field, a size above [maxFileBytes], an id other than "us-" + the state in
     * lower case, a duplicate id or state is BROKEN. Paths are checked with [CatalogAddress.resolve].
     */
    fun parse(
        bytes: ByteArray,
        address: CatalogAddress,
        minManifestVersion: Int,
        maxManifestVersion: Int,
        maxCatalogBytes: Long,
        maxFileBytes: Long,
    ): CatalogParse = CatalogParser.parse(bytes, address, minManifestVersion, maxManifestVersion, maxCatalogBytes, maxFileBytes)
}
