package io.github.spysyweeb.beamline.app.online

import io.github.spysyweeb.beamline.api.data.PackInfo
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.feature.search.OwnPlaceUi
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * One of the user's own sent changes as the app shows it until a pack has it (batch O3, docs/next-round.md
 * item 26): [ui] for the search and the place sheet; on the map [drawn] (a point with the map's own icon
 * [icon] and label style, by the own-changes layer; [kind]: the tiles' kind of such a place, the OSM value of
 * its main tag, which the style colours its label by) and [hiddenTileIds] (the map tiles' features whose
 * label it replaces: a renamed, replaced or closed place). [toString] prints none of it (PV2).
 */
data class OwnChange(val ui: OwnPlaceUi, val icon: String?, val drawn: Boolean, val hiddenTileIds: List<Long>, val kind: String? = null) {
    override fun toString(): String = "OwnChange"
}

/**
 * The rules of "your changes on your map at once" (batch O3, item 26), plain Kotlin:
 *  - every sent addition, edit, replacement and closing counts (the outbox's SENT items; notes do not);
 *    edits of one OSM object are taken together, oldest first (a later rename or closing wins);
 *  - an addition is drawn at its point with its type's icon; a rename or a replacement is drawn with the
 *    new name and the tiles' old label is hidden; a closing hides the old label and draws nothing (the
 *    next pack will not show a `disused:` place either); an edit of hours, phone or website alone
 *    changes nothing on the map (the label stays), only the search rows and the sheet;
 *  - it steps aside ([inPack]) once an installed pack covering its point was made from OSM data of a
 *    later day than the day it went (UTC): that pack has it, or the place changed again since.
 */
object OwnChanges {
    /** The sprite the sheet and the search rows show for a place of no known type (feature/search Icons.OTHER). */
    const val GENERIC_ICON = "building"

    /** The Protomaps tiles' feature id of an OSM object: (type << 44) | id, node 1, way 2, relation 3. */
    fun tileId(osm: String?): Long? {
        val r = OsmRef.parse(osm) ?: return null
        val t = when (r.type) {
            OsmType.NODE -> 1L
            OsmType.WAY -> 2L
            OsmType.RELATION -> 3L
        }
        return (t shl 44) or r.id
    }

    /** Whether an installed pack covering [point] has OSM data of a day after [sentMs] (UTC). */
    fun inPack(point: GeoPoint, sentMs: Long, packs: List<PackInfo>): Boolean {
        val sentDay = Instant.ofEpochMilli(sentMs).atZone(ZoneOffset.UTC).toLocalDate()
        return packs.any { p ->
            p.bounds.contains(point) && (runCatching { LocalDate.parse(p.osmSnapshot.take(10)) }.getOrNull()?.isAfter(sentDay) == true)
        }
    }

    fun of(items: List<OutboxItem>, packs: List<PackInfo>, iconOf: (String) -> String?): List<OwnChange> {
        // a road's speed limit (item 32) is not a place: nothing of it is drawn, found or shown on a sheet; 0.1.41 (53c, 53d):
        // an undo is no change to show, an undone edit no longer the user's value (the pack's is right again), and a
        // confirmation of hours changes nothing the map shows
        val sent = items.filter {
            it.state == OutboxState.SENT && (it.add != null || it.edit != null) && it.edit?.road != true &&
                it.edit?.undoOf == null && it.edit?.undone != true && it.edit?.confirm == null
        }
            .filter { !inPack(GeoPoint(it.lat, it.lon), it.sentMs ?: it.createdMs, packs) }
            .sortedBy { it.createdMs }
        val out = ArrayList<OwnChange>()
        for (i in sent) {
            val a = i.add ?: continue
            val type = PlaceTypes.byCode(a.typeCode)
            val name = a.tags["name"] ?: type?.label ?: continue
            out += OwnChange(
                OwnPlaceUi(
                    key = i.id, name = name, point = GeoPoint(i.lat, i.lon), categoryCode = a.typeCode, categoryLabel = type?.label,
                    terms = type?.terms.orEmpty(), overrides = contactOf(a.tags, all = true), osm = a.node?.let { "n$it" },
                ),
                iconOf(a.typeCode), drawn = true, hiddenTileIds = emptyList(), kind = type?.tags?.values?.firstOrNull(),
            )
        }
        for ((osm, edits) in sent.filter { it.edit != null }.groupBy { it.edit!!.osm }) {
            val first = edits.first()
            val oldName = first.placeName
            var name = oldName ?: continue
            var renamed = false
            var closed = false
            // the place's type as the edit form saw its tags, so the sheet, the search row and the own layer
            // keep the place's own icon after a rename
            var code: String? = edits.firstNotNullOfOrNull { it.edit?.typeCode ?: PlaceTypes.of(it.edit!!.base)?.code }
            // the latest edit's icon (an edit made from the map label knows the label's own icon), else the type's;
            // the generic "building" a sheet shows for a place of no known type is no icon of the place's own
            var icon: String? = edits.lastOrNull { it.edit?.icon != null && it.edit?.icon != GENERIC_ICON }?.edit?.icon ?: code?.let(iconOf)
            val overrides = LinkedHashMap<String, String?>()
            for (i in edits) {
                val e = i.edit!!
                val r = e.replaceWith
                when {
                    r != null -> {
                        val type = PlaceTypes.of(r)
                        name = r["name"] ?: type?.label ?: name
                        renamed = true
                        closed = false
                        code = type?.code
                        icon = type?.code?.let(iconOf) ?: icon
                        overrides.clear()
                        overrides.putAll(contactOf(r, all = true))
                    }
                    e.changes.keys.any { it.startsWith("disused:") } -> closed = true
                    else -> {
                        e.changes["name"]?.let {
                            name = it
                            renamed = true
                        }
                        overrides.putAll(contactOf(e.changes.filterKeys { it != "name" }.mapValues { it.value }, all = false))
                    }
                }
            }
            val packOsm = edits.firstNotNullOfOrNull { it.edit?.packOsm } ?: osm
            val type = PlaceTypes.byCode(code)
            out += OwnChange(
                OwnPlaceUi(
                    key = first.id, name = name, point = GeoPoint(first.lat, first.lon), categoryCode = code, categoryLabel = type?.label,
                    terms = type?.terms.orEmpty(), oldName = oldName.takeIf { renamed || closed || it != name }, overrides = overrides,
                    closed = closed, osm = osm,
                ),
                icon, drawn = renamed && !closed, hiddenTileIds = if (renamed || closed) listOfNotNull(tileId(packOsm)) else emptyList(),
                kind = type?.tags?.values?.firstOrNull(),
            )
        }
        return out
    }

    /** The hours, phone and website of [tags] by the keys the sheet reads; [all]: every key, absent ones removed (null). */
    private fun contactOf(tags: Map<String, String?>, all: Boolean): Map<String, String?> {
        val out = LinkedHashMap<String, String?>()
        fun one(key: String, vararg keys: String) {
            val k = keys.firstOrNull { it in tags }
            if (k != null) out[key] = tags[k] else if (all) out[key] = null
        }
        one("opening_hours", "opening_hours")
        one("phone", "phone", "contact:phone")
        one("website", "website", "contact:website")
        return out
    }
}
