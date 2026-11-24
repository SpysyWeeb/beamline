package io.github.spysyweeb.beamline.download

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import java.io.File

/** One part's state in job.json (docs/architecture-downloads.md 6.5). */
@Serializable
internal data class PartRecord(
    val etag: String? = null,
    @SerialName("transfer_verified") val transferVerified: Boolean = false,
    val verified: Boolean = false,
    @SerialName("hash_failures") val hashFailures: Int = 0,
)

/**
 * job.json, format beamline-download-job/1. Written atomically on every change of phase, reason,
 * ETag, verified flag or death count; never for byte progress (the file lengths are the progress).
 * [entry] is the job's catalog entry in the catalog's own JSON shape (re-encoded from the parsed
 * entry, so unknown keys of the server's list are not kept).
 */
@Serializable
internal data class JobRecord(
    val format: String = FORMAT,
    @SerialName("catalog_url") val catalogUrl: String,
    @SerialName("catalog_serial") val catalogSerial: Long,
    val entry: JsonObject,
    val kind: JobKind,
    @SerialName("include_optional") val includeOptional: Boolean,
    @SerialName("allow_metered") val allowMetered: Boolean,
    @SerialName("first_run") val firstRun: Boolean,
    val phase: JobPhase,
    val wait: WaitReason? = null,
    val pause: PauseReason? = null,
    val fail: FailReason? = null,
    val detail: String? = null,
    @SerialName("deaths_without_progress") val deathsWithoutProgress: Int = 0,
    @SerialName("bytes_at_last_death") val bytesAtLastDeath: Long = 0,
    @SerialName("need_bytes") val needBytes: Long? = null,
    @SerialName("free_bytes") val freeBytes: Long? = null,
    val parts: Map<String, PartRecord> = emptyMap(),
) {
    companion object {
        const val FORMAT = "beamline-download-job/1"
        const val FILE = "job.json"
        private val JSON = Json { ignoreUnknownKeys = true; encodeDefaults = true; explicitNulls = false }

        fun read(folder: File): JobRecord? = try {
            JSON.decodeFromString(serializer(), File(folder, FILE).readText(Charsets.UTF_8)).takeIf { it.format == FORMAT }
        } catch (_: Exception) {
            null
        }

        fun write(folder: File, record: JobRecord) =
            writeAtomically(File(folder, FILE), JSON.encodeToString(serializer(), record).toByteArray(Charsets.UTF_8))

        /** The entry as the catalog writes it. */
        fun encodeEntry(p: CatalogPack): JsonObject = buildJsonObject {
            put("id", p.id)
            put("state", p.state)
            put("name", p.name)
            put("pack_version", p.packVersion)
            put("manifest_version", p.manifestVersion)
            put("osm_snapshot", p.osmSnapshot)
            put("credit", p.credit)
            putJsonArray("bounds") {
                add(p.bounds.west); add(p.bounds.south); add(p.bounds.east); add(p.bounds.north)
            }
            put("manifest", transfer(p.manifest))
            putJsonArray("parts") {
                p.parts.forEach { part ->
                    add(buildJsonObject {
                        put("name", part.name)
                        put("file", part.file)
                        put("optional", part.optional)
                        put("bytes", part.bytes)
                        put("sha256", part.sha256)
                        part.credit?.let { put("credit", it) }
                        put("transfer", transfer(part.transfer))
                    })
                }
            }
        }

        private fun transfer(t: TransferFile) = buildJsonObject {
            put("path", t.path)
            put("encoding", t.encoding)
            put("bytes", t.bytes)
            put("sha256", t.sha256)
        }

        /** The entry back as a [CatalogPack], checked by the catalog's own rules; null when they refuse it now. */
        fun decodeEntry(entry: JsonObject, core: CatalogSourceCore): CatalogPack? {
            val catalog = buildJsonObject {
                put("format", CatalogFormat.FORMAT)
                put("serial", 0)
                put("generated_utc", "")
                put("licence", buildJsonObject {
                    put("id", "job"); put("name", "job"); put("url", "https://job.invalid/"); put("notice", "job")
                })
                putJsonArray("packs") { add(entry) }
            }
            val parsed = core.parse(catalog.toString().toByteArray(Charsets.UTF_8))
            return (parsed as? CatalogParse.Ok)?.catalog?.packs?.singleOrNull()
        }
    }
}
