package io.github.spysyweeb.beamline.download

import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.download.net.CatalogAddress
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.time.LocalDate
import java.time.format.DateTimeParseException

/**
 * CatalogFormat.parse (docs/architecture-downloads.md 2.3). Refusals and entry details are fixed
 * sentences that name a field, never a value the server sent.
 */
internal object CatalogParser {

    val PACK_ID = Regex("^[a-z0-9][a-z0-9._-]{0,63}$")
    val STATE = Regex("^[A-Z]{2}$")
    val PART_NAME = Regex("^[a-z][a-z0-9_]{0,31}$")
    val SHA256 = Regex("^[0-9a-f]{64}$")
    private val DATE = Regex("^\\d{4}-\\d{2}-\\d{2}$")
    /** Names a part file may never have: the pack's own files and the downloader's. */
    val RESERVED_FILES = setOf("manifest.json", "install-record.json", "job.json")
    /** The part the pack format calls skippable. */
    const val OPTIONAL_PART = "addresses"

    /** Plain file name of docs/pack-format.md: no '/', '\', ':', no leading '.', at most 128 characters, not a reserved name. */
    fun plainFileName(name: String): Boolean =
        name.isNotEmpty() && name.length <= 128 && !name.startsWith(".") &&
            name.none { it == '/' || it == '\\' || it == ':' || it.code < 0x20 } && name !in RESERVED_FILES

    private class Refuse(val reason: CatalogRefusal, val detail: String) : Exception(detail)

    /** An entry this app cannot use, with the reason. */
    private class EntryUnusable(val reason: Unusable, val detail: String) : Exception(detail)

    fun parse(bytes: ByteArray, address: CatalogAddress, minVersion: Int, maxVersion: Int, maxCatalogBytes: Long, maxFileBytes: Long): CatalogParse =
        try {
            CatalogParse.Ok(parseOrThrow(bytes, address, minVersion, maxVersion, maxCatalogBytes, maxFileBytes))
        } catch (r: Refuse) {
            CatalogParse.Refused(r.reason, r.detail)
        }

    private fun parseOrThrow(bytes: ByteArray, address: CatalogAddress, minVersion: Int, maxVersion: Int, maxCatalogBytes: Long, maxFileBytes: Long): Catalog {
        if (bytes.size > maxCatalogBytes) throw Refuse(CatalogRefusal.TOO_LARGE, "larger than $maxCatalogBytes bytes")
        val text = try {
            Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString().removePrefix("﻿")
        } catch (_: CharacterCodingException) {
            throw Refuse(CatalogRefusal.NOT_A_CATALOG, "not UTF-8")
        }
        val root = try {
            Json.parseToJsonElement(text)
        } catch (_: Exception) {
            throw Refuse(CatalogRefusal.NOT_A_CATALOG, "not JSON")
        } as? JsonObject ?: throw Refuse(CatalogRefusal.NOT_A_CATALOG, "not a JSON object")

        val format = root.str("format") ?: throw Refuse(CatalogRefusal.NOT_A_CATALOG, "no format")
        if (!format.startsWith(CatalogFormat.FORMAT_PREFIX)) throw Refuse(CatalogRefusal.NOT_A_CATALOG, "another format")
        val major = format.removePrefix(CatalogFormat.FORMAT_PREFIX).takeIf { it.matches(Regex("^[0-9]{1,6}$")) }?.toInt()
            ?: throw Refuse(CatalogRefusal.NOT_A_CATALOG, "format without a major version")
        if (major > 1) throw Refuse(CatalogRefusal.NEWER_FORMAT, "format major $major")
        if (major < 1) throw Refuse(CatalogRefusal.NOT_A_CATALOG, "format major $major")

        val serial = root.long("serial") ?: throw Refuse(CatalogRefusal.NOT_A_CATALOG, "serial missing or not an integer")
        val generated = root.str("generated_utc")?.takeIf { it.length <= CatalogFormat.MAX_GENERATED_UTC }
            ?: throw Refuse(CatalogRefusal.NOT_A_CATALOG, "generated_utc missing or too long")
        val licence = licence(root["licence"])
        val packs = root["packs"] as? JsonArray ?: throw Refuse(CatalogRefusal.NOT_A_CATALOG, "packs missing or not an array")

        // Rule 2 first, over the whole list: one bad path refuses everything.
        for (entry in packs) {
            for (path in paths(entry)) {
                try {
                    address.resolve(path)
                } catch (_: IllegalArgumentException) {
                    throw Refuse(CatalogRefusal.OUTSIDE_SCOPE, "a path breaks the path rule or leaves the scope")
                }
            }
        }

        val usable = mutableListOf<CatalogPack>()
        val unusable = mutableListOf<UnusablePack>()
        val seenIds = HashSet<String>()
        val seenStates = HashSet<String>()
        for (entry in packs) {
            val obj = entry as? JsonObject
            val rawId = obj?.str("id")
            val rawState = obj?.str("state")
            val shownState = rawState?.takeIf { STATE.matches(it) }
            val shownName = obj?.str("name")?.takeIf { it.isNotBlank() && it.length <= CatalogFormat.MAX_NAME }
            val duplicate = (rawId != null && !seenIds.add(rawId)) or (rawState != null && !seenStates.add(rawState))
            try {
                if (obj == null) throw EntryUnusable(Unusable.BROKEN, "entry is not an object")
                if (duplicate) throw EntryUnusable(Unusable.BROKEN, "duplicate id or state")
                usable += pack(obj, minVersion, maxVersion, maxFileBytes)
            } catch (u: EntryUnusable) {
                unusable += UnusablePack(rawId ?: "", shownState, shownName, u.reason, u.detail)
            }
        }
        return Catalog(serial, generated, licence, usable, unusable)
    }

    private fun licence(e: JsonElement?): CatalogLicence {
        val o = e as? JsonObject ?: throw Refuse(CatalogRefusal.NOT_A_CATALOG, "licence missing")
        val id = o.str("id")?.takeIf { it.isNotBlank() && it.length <= 64 } ?: throw Refuse(CatalogRefusal.NOT_A_CATALOG, "licence id")
        val name = o.str("name")?.takeIf { it.isNotBlank() && it.length <= CatalogFormat.MAX_LICENCE_NAME }
            ?: throw Refuse(CatalogRefusal.NOT_A_CATALOG, "licence name")
        val url = o.str("url")?.takeIf { it.length <= CatalogFormat.MAX_LICENCE_URL && it.startsWith("https://") && it.none(Char::isWhitespace) }
            ?: throw Refuse(CatalogRefusal.NOT_A_CATALOG, "licence url")
        val notice = o.str("notice")?.takeIf { it.isNotBlank() && it.length <= CatalogFormat.MAX_NOTICE && it.none { c -> c == '\n' || c == '\r' } }
            ?: throw Refuse(CatalogRefusal.NOT_A_CATALOG, "licence notice")
        return CatalogLicence(id, name, url, notice)
    }

    /** Every string `path` of one entry (manifest and part transfers). */
    private fun paths(entry: JsonElement): List<String> {
        val o = entry as? JsonObject ?: return emptyList()
        val out = mutableListOf<String>()
        (o["manifest"] as? JsonObject)?.str("path")?.let(out::add)
        (o["parts"] as? JsonArray)?.forEach { part ->
            ((part as? JsonObject)?.get("transfer") as? JsonObject)?.str("path")?.let(out::add)
        }
        return out
    }

    private fun broken(detail: String): Nothing = throw EntryUnusable(Unusable.BROKEN, detail)

    private fun pack(o: JsonObject, minVersion: Int, maxVersion: Int, maxFileBytes: Long): CatalogPack {
        // The version decides first: a newer entry may have fields this app does not know.
        val manifestVersion = o.long("manifest_version")?.takeIf { it in 0..Int.MAX_VALUE }?.toInt() ?: broken("manifest_version")
        if (manifestVersion > maxVersion) throw EntryUnusable(Unusable.NEEDS_NEWER_APP, "manifest_version above this app's maximum")
        if (manifestVersion < minVersion) throw EntryUnusable(Unusable.OLD_FORMAT, "manifest_version below this app's minimum")
        val encodings = mutableListOf<String?>()
        (o["manifest"] as? JsonObject)?.let { encodings += (it["encoding"] as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
        (o["parts"] as? JsonArray)?.forEach { p ->
            encodings += (((p as? JsonObject)?.get("transfer") as? JsonObject)?.get("encoding") as? JsonPrimitive)?.takeIf { it.isString }?.content
        }
        if (encodings.any { it != null && it !in CatalogFormat.ENCODINGS }) {
            throw EntryUnusable(Unusable.NEEDS_NEWER_APP, "an encoding this app does not know")
        }

        val id = o.str("id")?.takeIf { PACK_ID.matches(it) } ?: broken("id")
        val state = o.str("state")?.takeIf { STATE.matches(it) } ?: broken("state")
        if (id != "us-" + state.lowercase()) broken("id is not us- and the state")
        val name = o.str("name")?.takeIf { it.isNotBlank() && it.length <= CatalogFormat.MAX_NAME } ?: broken("name")
        val packVersion = o.long("pack_version")?.takeIf { it > 0 } ?: broken("pack_version")
        val snapshot = o.str("osm_snapshot")?.takeIf { DATE.matches(it) && validDate(it) } ?: broken("osm_snapshot")
        val credit = o.str("credit")?.takeIf { it.length <= CatalogFormat.MAX_CREDIT } ?: broken("credit")
        val bounds = bounds(o["bounds"]) ?: broken("bounds")
        val manifest = transfer(o["manifest"], maxFileBytes) ?: broken("manifest")
        if (manifest.encoding != "identity") broken("manifest encoding is not identity")
        val partsJson = o["parts"] as? JsonArray ?: broken("parts")
        if (partsJson.isEmpty()) broken("no parts")
        val parts = partsJson.map { part(it, maxFileBytes) }
        if (parts.map { it.name }.toSet().size != parts.size) broken("duplicate part name")
        if (parts.map { it.file }.toSet().size != parts.size) broken("duplicate part file")
        return CatalogPack(id, state, name, packVersion, manifestVersion, snapshot, credit, bounds, manifest, parts)
    }

    private fun validDate(s: String): Boolean = try {
        LocalDate.parse(s); true
    } catch (_: DateTimeParseException) {
        false
    }

    private fun bounds(e: JsonElement?): Bounds? {
        val a = e as? JsonArray ?: return null
        if (a.size != 4) return null
        val v = a.map { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.doubleOrNull ?: return null }
        if (v.any { !it.isFinite() }) return null
        val (w, s, east, n) = v
        if (!(w >= -180 && w < east && east <= 180 && s >= -90 && s < n && n <= 90)) return null
        return Bounds(w, s, east, n)
    }

    private fun transfer(e: JsonElement?, maxFileBytes: Long): TransferFile? {
        val o = e as? JsonObject ?: return null
        val path = o.str("path") ?: return null
        val encoding = o.str("encoding")?.takeIf { it in CatalogFormat.ENCODINGS } ?: return null
        val bytes = o.long("bytes")?.takeIf { it in 0..maxFileBytes } ?: return null
        val sha = o.str("sha256")?.takeIf { SHA256.matches(it) } ?: return null
        return TransferFile(path, encoding, bytes, sha)
    }

    private fun part(e: JsonElement, maxFileBytes: Long): CatalogPart {
        val o = e as? JsonObject ?: broken("part is not an object")
        val name = o.str("name")?.takeIf { PART_NAME.matches(it) } ?: broken("part name")
        val file = o.str("file")?.takeIf { plainFileName(it) } ?: broken("part file")
        val optional = (o["optional"] as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull ?: broken("part optional")
        if (optional && name != OPTIONAL_PART) broken("only the addresses part may be optional")
        val bytes = o.long("bytes")?.takeIf { it in 0..maxFileBytes } ?: broken("part bytes")
        val sha = o.str("sha256")?.takeIf { SHA256.matches(it) } ?: broken("part sha256")
        val credit = when (val c = o["credit"]) {
            null -> null
            else -> (c as? JsonPrimitive)?.takeIf { it.isString && it.content.length <= CatalogFormat.MAX_CREDIT }?.content ?: broken("part credit")
        }
        val transfer = transfer(o["transfer"], maxFileBytes) ?: broken("part transfer")
        if (transfer.encoding == "identity" && (transfer.bytes != bytes || transfer.sha256 != sha)) broken("identity transfer differs from its part")
        return CatalogPart(name, file, optional, bytes, sha, transfer, credit)
    }

    private fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.takeIf { !it.isString }?.longOrNull
}
