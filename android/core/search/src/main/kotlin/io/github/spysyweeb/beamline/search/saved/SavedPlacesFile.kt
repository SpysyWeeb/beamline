package io.github.spysyweeb.beamline.search.saved

import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.model.SavedKind
import io.github.spysyweeb.beamline.api.model.SavedPlace
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlin.math.round

/**
 * saved-places.json, format beamline-saved-places/1 (docs/architecture-saved-places.md 3.1): the
 * store's codec and the export format of 3.6. [encode] writes compact JSON with the keys in the
 * order of 3.1; [decode] checks the structure only (SP52), never today's name rules. Neither
 * ever puts the file's text, a name or a point into an exception or a result (PV2).
 */
object SavedPlacesFile {
    const val FORMAT = "beamline-saved-places/1"
    const val FORMAT_PREFIX = "beamline-saved-places/"
    const val NOTICE_BROKEN = "broken"

    private val ID = Regex("s[0-9a-f]{16}")

    /** What the file holds. [notice]: "broken" while the notice of SP52 waits for "OK" (never exported). */
    data class Content(val showOnMap: Boolean = true, val notice: String? = null, val places: List<SavedPlace> = emptyList()) {
        override fun toString(): String = "Content(showOnMap=$showOnMap, notice=$notice, places=${places.size})"
    }

    sealed interface Decoded {
        data class Ok(val content: Content) : Decoded
        /** The structure is damaged (SP52). */
        data object Broken : Decoded
        /** beamline-saved-places/N with N > 1 (SP53). */
        data object Newer : Decoded
    }

    /** Degrees rounded to 6 decimals, as saved (3.1). */
    fun round6(x: Double): Double = round(x * 1e6) / 1e6

    /** The whole file. With [withNotice] false (an export, 3.6) the notice key is left out. */
    fun encode(content: Content, withNotice: Boolean = true): String {
        val top = LinkedHashMap<String, JsonElement>()
        top["format"] = JsonPrimitive(FORMAT)
        top["show_on_map"] = JsonPrimitive(content.showOnMap)
        if (withNotice && content.notice != null) top["notice"] = JsonPrimitive(content.notice)
        top["places"] = JsonArray(content.places.map { encodePlace(it) })
        return JsonObject(top).toString()
    }

    private fun encodePlace(p: SavedPlace): JsonObject {
        val m = LinkedHashMap<String, JsonElement>()
        m["id"] = JsonPrimitive(p.id)
        m["kind"] = JsonPrimitive(kindText(p.kind))
        m["name"] = JsonPrimitive(p.name)
        m["lat"] = JsonPrimitive(p.point.lat)
        m["lon"] = JsonPrimitive(p.point.lon)
        m["place_name"] = JsonPrimitive(p.placeName)
        p.subtitle?.let { m["subtitle"] = JsonPrimitive(it) }
        p.categoryId?.let { m["category"] = JsonPrimitive(it) }
        return JsonObject(m)
    }

    fun kindText(k: SavedKind): String = when (k) {
        SavedKind.HOME -> "home"
        SavedKind.WORK -> "work"
        SavedKind.OTHER -> "other"
    }

    private fun kindOf(s: String): SavedKind? = when (s) {
        "home" -> SavedKind.HOME
        "work" -> SavedKind.WORK
        "other" -> SavedKind.OTHER
        else -> null
    }

    /**
     * The structure checks of SP52: a JSON object; a format starting with beamline-saved-places/
     * (N > 1: [Decoded.Newer]); places an array of objects, each with an id of the right form,
     * unique, a known kind (at most one home and one work), lat and lon numbers in range, name and
     * place_name non-empty strings (not trimmed: the name rules bind saves only), subtitle and
     * category strings when present (JSON null is not a string); show_on_map a boolean and notice
     * a string when present. Unknown keys are ignored. A home reads as "Home", a work as "Work",
     * whatever the file says. The places come back in the file's order.
     */
    fun decode(text: String): Decoded {
        val root = try {
            Json.parseToJsonElement(text)
        } catch (_: Exception) {
            return Decoded.Broken   // the parser's message quotes the input: dropped (PV2)
        }
        if (root !is JsonObject) return Decoded.Broken
        val format = root["format"].stringOrNull() ?: return Decoded.Broken
        if (!format.startsWith(FORMAT_PREFIX)) return Decoded.Broken
        val version = format.removePrefix(FORMAT_PREFIX)
        if (!version.matches(Regex("[1-9][0-9]{0,8}"))) return Decoded.Broken
        if (version != "1") return Decoded.Newer
        val show = when (val s = root["show_on_map"]) {
            null -> true
            is JsonPrimitive -> if (s.isString) return Decoded.Broken else (s.booleanOrNull ?: return Decoded.Broken)
            else -> return Decoded.Broken
        }
        val notice = when (val n = root["notice"]) {
            null -> null
            else -> n.stringOrNull() ?: return Decoded.Broken
        }
        val arr = root["places"] as? JsonArray ?: return Decoded.Broken
        val ids = HashSet<String>()
        var homes = 0
        var works = 0
        val places = ArrayList<SavedPlace>(arr.size)
        for (e in arr) {
            val o = e as? JsonObject ?: return Decoded.Broken
            val id = o["id"].stringOrNull() ?: return Decoded.Broken
            if (!ID.matches(id) || !ids.add(id)) return Decoded.Broken
            val kind = kindOf(o["kind"].stringOrNull() ?: return Decoded.Broken) ?: return Decoded.Broken
            if (kind == SavedKind.HOME && ++homes > 1) return Decoded.Broken
            if (kind == SavedKind.WORK && ++works > 1) return Decoded.Broken
            val lat = o["lat"].numberOrNull() ?: return Decoded.Broken
            val lon = o["lon"].numberOrNull() ?: return Decoded.Broken
            if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return Decoded.Broken
            val name = o["name"].stringOrNull()?.takeIf { it.isNotEmpty() } ?: return Decoded.Broken
            val placeName = o["place_name"].stringOrNull()?.takeIf { it.isNotEmpty() } ?: return Decoded.Broken
            val subtitle = if ("subtitle" in o) o["subtitle"].stringOrNull() ?: return Decoded.Broken else null
            val category = if ("category" in o) o["category"].stringOrNull() ?: return Decoded.Broken else null
            places.add(
                SavedPlace(
                    id = id, kind = kind,
                    name = when (kind) {
                        SavedKind.HOME -> HOME_NAME
                        SavedKind.WORK -> WORK_NAME
                        SavedKind.OTHER -> name
                    },
                    point = GeoPoint(lat, lon), placeName = placeName, subtitle = subtitle, categoryId = category,
                ),
            )
        }
        return Decoded.Ok(Content(show, notice, places))
    }

    const val HOME_NAME = "Home"
    const val WORK_NAME = "Work"

    private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content

    private fun JsonElement?.numberOrNull(): Double? {
        val p = this as? JsonPrimitive ?: return null
        if (p.isString) return null
        val c = p.content
        if (c == "true" || c == "false" || c == "null") return null
        return c.toDoubleOrNull()?.takeIf { it.isFinite() }
    }
}
