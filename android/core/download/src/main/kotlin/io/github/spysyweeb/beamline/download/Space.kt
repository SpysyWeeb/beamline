// FILE: core/download/src/main/kotlin/io/github/spysyweeb/beamline/download/Space.kt
package io.github.spysyweeb.beamline.download

import io.github.spysyweeb.beamline.api.data.ArchiveInfo

/**
 * How many more bytes a download needs, from now until its install has finished (section 2.6 of
 * docs/architecture-downloads.md). R = the manifest plus the parts the job fetches, unpacked; G = the
 * largest gzip transfer file among them (kept while it is unpacked); D = what is already in the job
 * folder; M = space_margin_bytes. The download's own peak is R + G + M; the install's is what the
 * installer needs beyond the parts when it moves them, or its whole need when it copies them.
 */
object Space {
    /** The parts a job fetches: ADD_OPTIONAL only the optional ones. */
    fun parts(pack: CatalogPack, kind: JobKind, includeOptional: Boolean): List<CatalogPart> =
        if (kind == JobKind.ADD_OPTIONAL) pack.parts.filter { it.optional } else pack.chosenParts(includeOptional)

    /**
     * Before the manifest is fetched (the sheets). [routingSetBytes] is the merged routing tile set
     * on the phone now: the installer writes a new one beside it, as large as it plus the new
     * routing part (an upper bound for an update, whose old tiles leave the new set).
     */
    fun estimate(pack: CatalogPack, kind: JobKind, includeOptional: Boolean, moves: Boolean, routingSetBytes: Long, onDiskBytes: Long, marginBytes: Long): Long {
        val parts = parts(pack, kind, includeOptional)
        val r = pack.manifest.bytes + parts.sumOf { it.bytes }
        val routing = parts.filter { it.name == "routing" }.sumOf { it.bytes }
        val newRoutingSet = if (routing > 0) routingSetBytes + routing else 0
        val install = (if (moves) 0 else r) + newRoutingSet + marginBytes
        return need(r, largestGzip(parts), onDiskBytes, marginBytes, install)
    }

    /** At CHECKING, with the installer's own figure ([info] from PackHandoff.inspect, which includes the routing merge's peak). */
    fun exact(pack: CatalogPack, kind: JobKind, includeOptional: Boolean, moves: Boolean, onDiskBytes: Long, marginBytes: Long, info: ArchiveInfo): Long {
        val parts = parts(pack, kind, includeOptional)
        val r = pack.manifest.bytes + parts.sumOf { it.bytes }
        val install = if (kind == JobKind.ADD_OPTIONAL) marginBytes else {
            val installerNeed = info.neededBytes - if (includeOptional) 0 else (info.addressesBytes ?: 0)
            if (moves) maxOf(0, installerNeed - r) else installerNeed
        }
        return need(r, largestGzip(parts), onDiskBytes, marginBytes, install)
    }

    private fun largestGzip(parts: List<CatalogPart>): Long =
        parts.filter { it.transfer.encoding == "gzip" }.maxOfOrNull { it.transfer.bytes } ?: 0

    private fun need(r: Long, g: Long, d: Long, m: Long, install: Long): Long =
        maxOf(0, r - d) + maxOf(g + m, install)
}
