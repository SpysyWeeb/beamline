package io.github.spysyweeb.beamline.search

import io.github.spysyweeb.beamline.search.db.SqlDb
import java.io.File
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.jsonPrimitive

/**
 * Kotlin port of the Checker in packbuilder/search/tests/run_tests.py: is a result what a test
 * case expects, and does the database hold anything that satisfies the expectation at all
 * (else the miss is a data gap, not an engine failure).
 */
class Checker(searchDb: String, addressesDb: String?, private val norm: SearchNormalizer) : AutoCloseable {
    private val db = SqlDb.openReadOnly(searchDb).also { d ->
        if (!addressesDb.isNullOrEmpty()) d.query("ATTACH DATABASE ? AS addr", listOf(SqlDb.readOnlyUri(File(addressesDb))))
    }

    override fun close() = db.close()

    private fun names(e: JsonObject, k: String): List<String> = when (val v = e[k]) {
        is JsonArray -> v.map { it.jsonPrimitive.content }
        else -> listOf(v!!.jsonPrimitive.content)
    }

    private fun poiInfo(eid: Long): Pair<List<String>, String?> {
        val r = db.first("SELECT cats, brand_wikidata FROM poi WHERE entry_id = ?", eid)!!
        return Py.split((r[0] as String?) ?: "") to (r[1] as String?)
    }

    fun match(res: SearchResult, exp: JsonObject, anchor: LatLon?): Boolean {
        exp["within_km"]?.let { w ->
            if (anchor != null && Geo.haversineKm(anchor.lat, anchor.lon, res.lat, res.lon) > w.jsonPrimitive.double) return false
        }
        val kind = res.kind.code
        if ("place" in exp) {
            if (kind != "place" || norm.key(res.title) !in names(exp, "place").map { norm.key(it) }.toSet()) return false
            exp["place_type"]?.let { if (res.categoryId != it.jsonPrimitive.content) return false }
            return true
        }
        if ("zip" in exp) return kind == "place" && res.categoryId == "zip" && res.title == exp["zip"]!!.jsonPrimitive.content
        if ("house" in exp) {
            val a = res.address ?: return false
            if (kind != "street" || a.accuracy != AddressInfo.Accuracy.POINT || a.houseNumber != exp["house"]!!.jsonPrimitive.content) return false
            val hl = exp["house_at"] as JsonArray
            return Geo.haversineKm(hl[0].jsonPrimitive.double, hl[1].jsonPrimitive.double, res.lat, res.lon) <= 0.1
        }
        if ("street_ref" in exp) {
            if (kind != "street") return false
            val r = db.first("SELECT name_key, alt_keys FROM entry WHERE id = ?", res.id)!!
            val keys = setOf(r[0] as String) + ((r[1] as String?) ?: "").split("|").filter { it.isNotEmpty() }.map { it.trimStart('~') }
            return exp["street_ref"]!!.jsonPrimitive.content in keys
        }
        if ("street" in exp) {
            val title = res.address?.streetName ?: res.title
            if (kind != "street" || norm.key(title) !in names(exp, "street").map { norm.key(it) }.toSet()) return false
            exp["in"]?.let { if (it.jsonPrimitive.content.lowercase() !in res.subtitle.lowercase()) return false }
            return true
        }
        if ("addr_street" in exp) {
            if (kind != "poi") return false
            val st = db.first("SELECT addr_street FROM poi WHERE entry_id = ?", res.id)!![0] as String?
            if (st.isNullOrEmpty() || norm.key(st) != norm.key(exp["addr_street"]!!.jsonPrimitive.content)) return false
        }
        if ("brand_wikidata" in exp) {
            if (kind != "poi") return false
            return poiInfo(res.id).second == exp["brand_wikidata"]!!.jsonPrimitive.content
        }
        if ("category" in exp) {
            if (kind != "poi") return false
            val cats = poiInfo(res.id).first
            return names(exp, "category").any { it in cats }
        }
        if ("title_contains" in exp) {
            exp["kind"]?.let { if (kind != it.jsonPrimitive.content) return false }
            return exp["title_contains"]!!.jsonPrimitive.content.lowercase() in res.title.lowercase()
        }
        error("unknown expectation $exp")
    }

    fun exists(exp: JsonObject, anchor: LatLon?): Boolean {
        val within = exp["within_km"]?.jsonPrimitive?.double
        var rows: List<Array<Any?>> = emptyList()   // id, lat, lon, extra
        if ("place" in exp) {
            val keys = names(exp, "place").map { norm.key(it) }
            rows = db.query("SELECT id, lat, lon, type FROM entry WHERE kind = 1 AND name_key IN (${keys.joinToString(",") { "?" }})", keys)
            exp["place_type"]?.let { pt -> rows = rows.filter { it[3] == pt.jsonPrimitive.content } }
        } else if ("zip" in exp) {
            rows = db.query("SELECT id, lat, lon, type FROM entry WHERE kind = 1 AND type = 'zip' AND name = ?", exp["zip"]!!.jsonPrimitive.content)
        } else if ("house" in exp) {
            val hl = exp["house_at"] as JsonArray
            val la = hl[0].jsonPrimitive.double
            val lo = hl[1].jsonPrimitive.double
            val d = 0.0015
            rows = try {
                db.query("SELECT a.id, a.lat_e6 / 1e6, a.lon_e6 / 1e6 FROM addr.addresses a WHERE a.hn = ? " +
                    "AND a.lat_e6 BETWEEN ? AND ? AND a.lon_e6 BETWEEN ? AND ?",
                    exp["house"]!!.jsonPrimitive.content, ((la - d) * 1e6).toLong(), ((la + d) * 1e6).toLong(),
                    ((lo - d) * 1e6).toLong(), ((lo + d) * 1e6).toLong())
            } catch (_: Exception) {
                emptyList()
            }
            return rows.any { Geo.haversineKm(la, lo, (it[1] as Number).toDouble(), (it[2] as Number).toDouble()) <= 0.1 }
        } else if ("street_ref" in exp) {
            val k = exp["street_ref"]!!.jsonPrimitive.content
            rows = db.query("SELECT id, lat, lon, NULL FROM entry WHERE kind = 2 AND (name_key = ? " +
                "OR '|' || alt_keys || '|' LIKE ? OR '|' || alt_keys || '|' LIKE ?)", k, "%|$k|%", "%|~$k|%")
        } else if ("street" in exp) {
            val keys = names(exp, "street").map { norm.key(it) }
            rows = db.query("SELECT id, lat, lon, subtitle FROM entry WHERE kind = 2 AND name_key IN (${keys.joinToString(",") { "?" }})", keys)
            exp["in"]?.let { inn -> rows = rows.filter { inn.jsonPrimitive.content.lowercase() in ((it[3] as String?) ?: "").lowercase() } }
        } else if ("brand_wikidata" in exp) {
            rows = db.query("SELECT e.id, e.lat, e.lon, p.addr_street FROM poi p JOIN entry e ON e.id = p.entry_id WHERE p.brand_wikidata = ?",
                exp["brand_wikidata"]!!.jsonPrimitive.content)
        } else if ("category" in exp) {
            val want = names(exp, "category")
            rows = db.query("SELECT e.id, e.lat, e.lon, p.addr_street FROM poi_cat c JOIN category k ON k.id = c.cat_id " +
                "JOIN entry e ON e.id = c.entry_id JOIN poi p ON p.entry_id = e.id WHERE k.code IN (${want.joinToString(",") { "?" }})", want)
        }
        if ("addr_street" in exp) {
            val want = norm.key(exp["addr_street"]!!.jsonPrimitive.content)
            rows = rows.filter { !(it[3] as String?).isNullOrEmpty() && norm.key(it[3] as String) == want }
        } else if ("title_contains" in exp) {
            val kind = mapOf("place" to 1, "street" to 2, "poi" to 3)[exp["kind"]?.jsonPrimitive?.content]
            rows = db.query("SELECT e.id, e.lat, e.lon, p.addr_street FROM entry e LEFT JOIN poi p ON p.entry_id = e.id " +
                "WHERE instr(lower(e.name), ?) > 0" + (if (kind != null) " AND e.kind = $kind" else ""),
                exp["title_contains"]!!.jsonPrimitive.content.lowercase())
        }
        if (within != null && anchor != null) {
            rows = rows.filter { Geo.haversineKm(anchor.lat, anchor.lon, (it[1] as Number).toDouble(), (it[2] as Number).toDouble()) <= within }
        }
        return rows.isNotEmpty()
    }

    companion object {
        fun isNumber(e: Any?) = e is JsonPrimitive && !e.isString
    }
}
