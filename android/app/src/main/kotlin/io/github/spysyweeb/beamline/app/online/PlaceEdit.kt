package io.github.spysyweeb.beamline.app.online

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/** The three kinds of OSM object; batch O2 edits nodes and ways, a relation opens openstreetmap.org. */
enum class OsmType(val letter: Char, val word: String) {
    NODE('n', "node"), WAY('w', "way"), RELATION('r', "relation");
}

/** One OSM object, as the search packs keep it in `poi.osm` ("n25479855", "w123"). */
data class OsmRef(val type: OsmType, val id: Long) {
    override fun toString(): String = "${type.letter}$id"

    companion object {
        /** "n25479855" and the like; null for anything else. */
        fun parse(text: String?): OsmRef? {
            val t = text?.trim() ?: return null
            if (t.length < 2) return null
            val type = OsmType.entries.firstOrNull { it.letter == t[0] } ?: return null
            val id = t.substring(1).toLongOrNull()?.takeIf { it > 0 } ?: return null
            return OsmRef(type, id)
        }
    }
}

/**
 * A node or way as OSM has it now (batch O2, docs/online-design.md 4.2): its [version], [tags], and
 * what an upload must send back unchanged (a node's [lat]/[lon], a way's [nodes]). Its tags can be
 * a place's phone or website: [toString] prints none of them (PV2).
 */
data class OsmElement(
    val ref: OsmRef,
    val version: Int,
    val tags: Map<String, String>,
    val lat: Double?,
    val lon: Double?,
    val nodes: List<Long>,
) {
    override fun toString(): String = "OsmElement($ref v$version)"

    companion object {
        /** The first element of an API 0.6 JSON answer (`{"elements":[{...}]}`), or null. */
        fun fromJson(json: String): OsmElement? = try {
            val root = Json.parseToJsonElement(json) as JsonObject
            val e = (root["elements"] as JsonArray).first() as JsonObject
            val type = OsmType.entries.first { it.word == (e["type"] as JsonPrimitive).content }
            val tags = (e["tags"] as? JsonObject)?.mapValues { (it.value as JsonPrimitive).content } ?: emptyMap()
            OsmElement(
                OsmRef(type, (e["id"] as JsonPrimitive).longOrNull!!),
                (e["version"] as JsonPrimitive).intOrNull!!,
                tags,
                (e["lat"] as? JsonPrimitive)?.doubleOrNull,
                (e["lon"] as? JsonPrimitive)?.doubleOrNull,
                (e["nodes"] as? JsonArray)?.map { (it as JsonPrimitive).longOrNull!! } ?: emptyList(),
            )
        } catch (_: Exception) {
            null
        }
    }
}

/** The XML bodies of API 0.6's PUT requests (OSM takes XML there, not JSON). */
object OsmXml {
    fun changeset(tags: Map<String, String>): String = buildString {
        append("<osm><changeset>")
        for ((k, v) in tags) append("<tag k=\"").append(esc(k)).append("\" v=\"").append(esc(v)).append("\"/>")
        append("</changeset></osm>")
    }

    /** [e] with its tags in [changeset]; [create]: a new node (no id, no version). */
    fun element(e: OsmElement, changeset: Long, create: Boolean = false): String = buildString {
        val word = e.ref.type.word
        append("<osm><").append(word)
        if (!create) append(" id=\"").append(e.ref.id).append("\" version=\"").append(e.version).append('"')
        append(" changeset=\"").append(changeset).append('"')
        if (e.ref.type == OsmType.NODE) {
            append(" lat=\"").append(OsmHttp.coordinate(e.lat ?: 0.0)).append("\" lon=\"").append(OsmHttp.coordinate(e.lon ?: 0.0)).append('"')
        }
        append('>')
        for (n in e.nodes) append("<nd ref=\"").append(n).append("\"/>")
        for ((k, v) in e.tags.toSortedMap()) append("<tag k=\"").append(esc(k)).append("\" v=\"").append(esc(v)).append("\"/>")
        append("</").append(word).append("></osm>")
    }

    fun esc(s: String): String = buildString {
        for (c in s) when (c) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            '\'' -> append("&apos;")
            '\n' -> append("&#10;")
            '\r' -> append("&#13;")
            '\t' -> append("&#9;")
            else -> append(c)
        }
    }
}

/**
 * What the edit form shows and changes (docs/online-design.md 4.2): the name, the opening hours
 * text, the phone and the website. Null: OSM has none.
 */
data class EditFields(
    val name: String?, val hours: String?, val phone: String?, val website: String?,
    /** Item 33: a second set of hours ([MoreHours]): its key and its text (null text: none, the tag goes). */
    val moreKey: String? = null, val moreHours: String? = null,
) {
    override fun toString(): String = "EditFields"

    /** The four values a pack also has (a pack carries no second set of hours). */
    fun core(): EditFields = copy(moreKey = null, moreHours = null)

    companion object {
        /** From an element's tags: `phone` or else `contact:phone`, `website` or else `contact:website`; the first second set of hours. */
        fun of(tags: Map<String, String>): EditFields {
            val more = MoreHours.of(tags)
            return EditFields(
                tags["name"], tags["opening_hours"], tags["phone"] ?: tags["contact:phone"], tags["website"] ?: tags["contact:website"],
                more?.key, more?.let { tags[it.key] },
            )
        }
    }
}

/**
 * The rules of an edit (docs/online-design.md 4.2), plain Kotlin:
 *  - the changes are tag by tag: key to its new value, null to remove the tag;
 *  - the phone and the website change the key the place already uses (`contact:phone` when it has
 *    only that), else `phone` and `website`;
 *  - "Closed permanently" follows OSM's lifecycle prefix (wiki Key:disused: and Lifecycle_prefix):
 *    every main key the place has ([MAIN_KEYS]: `shop=bakery`) becomes `disused:shop=bakery`, so the
 *    record stays as a closed place that can be undone if it opens again; the name stays ("the name is
 *    still relevant", Key:disused:); its hours, phone and website are removed ([CLOSED_REMOVED]).
 */
object PlaceEdits {
    /** The keys that make a place a business or a public place; a closed one keeps them under `disused:`. */
    val MAIN_KEYS = listOf("amenity", "shop", "craft", "office", "leisure", "tourism", "healthcare", "club")

    /** Removed from a closed place: the hours, phone and website, as the design says. */
    val CLOSED_REMOVED = listOf("opening_hours", "opening_hours:office", "opening_hours:drive_through", "opening_hours:kitchen",
        "phone", "contact:phone", "website", "contact:website")

    /** "Closed permanently" can be offered: the place has a main key ([MAIN_KEYS]). */
    fun canClose(tags: Map<String, String>): Boolean = MAIN_KEYS.any { it in tags }

    /** The changes "Closed permanently" makes to [tags]. */
    fun closedChanges(tags: Map<String, String>): Map<String, String?> {
        val out = LinkedHashMap<String, String?>()
        for (k in MAIN_KEYS) {
            val v = tags[k] ?: continue
            out[k] = null
            out["disused:$k"] = v
        }
        for (k in CLOSED_REMOVED) if (k in tags) out[k] = null
        return out
    }

    /**
     * The changes from [base] (the tags the form started from) to the form's [form] values. A blank
     * name keeps the old one (a place is never left without its name by mistake); a blank hours, phone
     * or website removes that tag.
     */
    fun changes(base: Map<String, String>, form: EditFields, closed: Boolean): Map<String, String?> {
        if (closed) return closedChanges(base)
        val out = LinkedHashMap<String, String?>()
        val name = form.name?.trim().orEmpty()
        if (name.isNotEmpty() && name != base["name"]) out["name"] = name
        fun field(key: String, alt: String, value: String?) {
            val v = value?.trim()?.takeIf { it.isNotEmpty() }
            val k = if (key !in base && alt in base) alt else key
            if (v != base[k]) out[k] = v
        }
        field("opening_hours", "opening_hours", form.hours)
        form.moreKey?.let { k -> if (MoreHours.ofKey(k) != null) field(k, k, form.moreHours) }
        field("phone", "contact:phone", form.phone)
        field("website", "contact:website", form.website)
        return out
    }

    /** [tags] with [changes] made. */
    fun apply(tags: Map<String, String>, changes: Map<String, String?>): Map<String, String> {
        val out = LinkedHashMap(tags)
        for ((k, v) in changes) if (v == null) out.remove(k) else out[k] = v
        return out
    }

    /** Whether [tags] already say what [changes] say (nothing left to upload). */
    fun already(tags: Map<String, String>, changes: Map<String, String?>): Boolean = changes.all { (k, v) -> tags[k] == v }

    /**
     * The summary before upload, one line a change. Hours in plain words where the week can be read (the
     * owner, 2026-11-21): "Hours: Mon–Fri 8 AM–6 PM; Sat–Sun closed → Mon–Fri 8 AM–5 PM; Sat–Sun closed";
     * a text the form cannot express stays as written.
     */
    fun summary(base: Map<String, String>, changes: Map<String, String?>): List<String> {
        if (changes.keys.any { it.startsWith("disused:") }) {
            val moved = changes.filter { it.key.startsWith("disused:") }.map { (k, v) -> "${k.removePrefix("disused:")}=$v becomes $k=$v" }
            val removed = changes.filter { it.value == null && it.key in CLOSED_REMOVED }.keys
            return listOf("Closed permanently: " + moved.joinToString("; ")) +
                (if (removed.isEmpty()) emptyList() else listOf("Removed: " + removed.joinToString(", "))) +
                listOf("The name stays, so mappers see what was there.")
        }
        fun shown(k: String, v: String?): String = when {
            v == null -> NONE
            k == "opening_hours" || MoreHours.ofKey(k) != null -> WeekHours.of(v)?.plain() ?: v
            else -> v
        }
        return changes.map { (k, v) -> "${label(k)}: ${shown(k, base[k])} → ${shown(k, v)}" }
    }

    /** The changeset's comment (4.2: "Updated opening hours"). */
    fun comment(changes: Map<String, String?>): String {
        if (changes.keys.any { it.startsWith("disused:") }) return "Marked a place as closed permanently"
        val what = changes.keys.map { label(it).lowercase() }.distinct()
        return "Updated " + when (what.size) {
            0 -> "a place"
            1 -> what[0]
            else -> what.dropLast(1).joinToString(", ") + " and " + what.last()
        }
    }

    /** The changeset's tags (4.2): the comment, `created_by=Beamline <version>`. No `source`: the app cannot know that the user stood at the place (owner, 2026-11-21). */
    fun changesetTags(comment: String, version: String): Map<String, String> =
        linkedMapOf("comment" to comment, "created_by" to "Beamline $version")

    /**
     * Whether the place changed on OSM since the edit was made, so it waits for the user to look
     * again instead of overwriting (4.5): with a [baseVersion] (the form read the place from OSM) any
     * newer version; without one (the form showed the pack's values, offline) any of the edited keys
     * whose value is no longer the one the form started from ([base]).
     */
    fun changedMeanwhile(current: OsmElement, baseVersion: Int?, base: Map<String, String>, changes: Map<String, String?>): Boolean =
        if (baseVersion != null) current.version != baseVersion
        else changes.keys.any { current.tags[it] != base[it] }

    fun label(key: String): String = when (key) {
        "name" -> "Name"
        "opening_hours" -> "Hours"
        MoreHours.OFFICE.key -> MoreHours.OFFICE.label
        MoreHours.DRIVE_THROUGH.key -> MoreHours.DRIVE_THROUGH.label
        MoreHours.KITCHEN.key -> MoreHours.KITCHEN.label
        "phone", "contact:phone" -> "Phone"
        "website", "contact:website" -> "Website"
        else -> key
    }

    const val NONE = "(none)"
}
