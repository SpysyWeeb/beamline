package io.github.spysyweeb.beamline.search

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * search_app.json (packbuilder/search/search_app.json, format beamline-search-app/1; the copy in this
 * module's rules folder, made by android/scripts/copy_search_rules.py) without its "saved" section,
 * which [io.github.spysyweeb.beamline.search.saved.SavedRules] reads. Rules S1-S6, S11 and S12 of
 * docs/architecture.md 3.8. Field = JSON key; a key or section missing from the file takes the default.
 */
data class SearchAppRules(
    val fixMaxAgeS: Double,                 // near.fix_max_age_s
    val debounceMs: Long,                   // typing.debounce_ms
    val packMarginKm: Double,               // merge.pack_margin_km
    val dedupeM: Double,                    // merge.dedupe_m
    val mergeLimit: Int,                    // merge.limit
    val recentsMax: Int,                    // recents.max
    val recentsSamePlaceM: Double,          // recents.same_place_m
    val shortcuts: List<Shortcut>,          // shortcuts
    val mapMaxResults: Int = 20,            // on_map.max_results (rule S11; the lead's cap, batch B)
    val mapWithinKm: Double = 40.23,        // on_map.within_km (S11; 25 miles, the owner after batch B)
    val mapNearestWhenNone: Int = 5,        // on_map.nearest_when_none_within (S11: none that close, the nearest few anyway)
    val mapCandidates: Int = 100,           // on_map.candidates (S11: asked of the engine, then sorted by distance and cut)
    val detailsMatchM: Double = 100.0,      // details.match_within_m (S10: a place without a search id, by name and position)
    val areaNameWithinKm: Double = 25.0,    // near.area_name_within_km (P6, 0.1.40: how far the town of "Near Corry" may be from the map's centre)
    val alongAheadKm: Double = 60.0,        // along_route.ahead_km (S12: how far ahead of where a new stop joins the trip places are looked for)
    val alongSampleKm: Double = 10.0,       // along_route.sample_km (S12: one search around a point this often along the route)
    val alongCorridorKm: Double = 3.0,      // along_route.corridor_km (S12: category and brand answers only)
    val alongMaxResults: Int = 20,          // along_route.max_results (S12; also the most places one NavIntent.Detours asks)
    val alongNearestWhenNone: Int = 5,      // along_route.nearest_when_none_within (S12: none in the corridor, these nearest the route)
    val detoursWaitMs: Long = 4000,         // along_route.detours_wait_ms (S12: "Working out detours..." at most this long)
) {
    /** A shortcut button: [query] is a term of packbuilder/search/categories.json, [icon] a sprite name. */
    data class Shortcut(val id: String, val label: String, val query: String, val icon: String)

    companion object {
        const val FORMAT = "beamline-search-app/1"
        const val FILE = "search_app.json"

        /** From the whole text of search_app.json. Throws IllegalArgumentException with a fixed message when it is not one. */
        fun parse(text: String): SearchAppRules {
            val o = try {
                Json.parseToJsonElement(text).jsonObject
            } catch (_: Exception) {
                throw IllegalArgumentException("search_app.json is not a JSON object")
            }
            require(o["format"]?.jsonPrimitive?.content == FORMAT) { "search_app.json has another format" }
            fun sec(k: String): JsonObject = o[k]?.jsonObject ?: throw IllegalArgumentException("search_app.json lacks a section")
            val near = sec("near")
            val typing = sec("typing")
            val merge = sec("merge")
            val recents = sec("recents")
            val along = o["along_route"]?.jsonObject
            val shortcuts = (o["shortcuts"] ?: throw IllegalArgumentException("search_app.json lacks shortcuts")).jsonArray.map {
                val s = it.jsonObject
                Shortcut(
                    s["id"]!!.jsonPrimitive.content, s["label"]!!.jsonPrimitive.content,
                    s["query"]!!.jsonPrimitive.content, s["icon"]!!.jsonPrimitive.content,
                )
            }
            return SearchAppRules(
                fixMaxAgeS = near["fix_max_age_s"]!!.jsonPrimitive.double,
                debounceMs = typing["debounce_ms"]!!.jsonPrimitive.int.toLong(),
                packMarginKm = merge["pack_margin_km"]!!.jsonPrimitive.double,
                dedupeM = merge["dedupe_m"]!!.jsonPrimitive.double,
                mergeLimit = merge["limit"]!!.jsonPrimitive.int,
                recentsMax = recents["max"]!!.jsonPrimitive.int,
                recentsSamePlaceM = recents["same_place_m"]!!.jsonPrimitive.double,
                shortcuts = shortcuts,
                mapMaxResults = o["on_map"]?.jsonObject?.get("max_results")?.jsonPrimitive?.int ?: 20,
                mapWithinKm = o["on_map"]?.jsonObject?.get("within_km")?.jsonPrimitive?.double ?: 40.23,
                mapNearestWhenNone = o["on_map"]?.jsonObject?.get("nearest_when_none_within")?.jsonPrimitive?.int ?: 5,
                mapCandidates = o["on_map"]?.jsonObject?.get("candidates")?.jsonPrimitive?.int ?: 100,
                detailsMatchM = o["details"]?.jsonObject?.get("match_within_m")?.jsonPrimitive?.double ?: 100.0,
                areaNameWithinKm = near["area_name_within_km"]?.jsonPrimitive?.double ?: 25.0,
                alongAheadKm = along?.get("ahead_km")?.jsonPrimitive?.double ?: 60.0,
                alongSampleKm = along?.get("sample_km")?.jsonPrimitive?.double ?: 10.0,
                alongCorridorKm = along?.get("corridor_km")?.jsonPrimitive?.double ?: 3.0,
                alongMaxResults = along?.get("max_results")?.jsonPrimitive?.int ?: 20,
                alongNearestWhenNone = along?.get("nearest_when_none_within")?.jsonPrimitive?.int ?: 5,
                detoursWaitMs = along?.get("detours_wait_ms")?.jsonPrimitive?.int?.toLong() ?: 4000L,
            )
        }

        /** The copy in the resources (rules/search_app.json). */
        fun bundled(): SearchAppRules = bundledRules

        /** The whole text of the bundled copy (for SavedRules.bundled and :feature:search). */
        fun bundledText(): String = RuleFiles.text(FILE)

        private val bundledRules: SearchAppRules by lazy { parse(bundledText()) }
    }
}
