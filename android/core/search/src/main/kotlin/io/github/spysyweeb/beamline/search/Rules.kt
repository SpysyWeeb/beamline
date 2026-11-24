package io.github.spysyweeb.beamline.search

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/**
 * The rule files. They are copied from packbuilder/ by android/scripts/copy_search_rules.py into
 * this module's resources (io/github/spysyweeb/beamline/search/rules/) and never edited here.
 */
object RuleFiles {
    const val ABBREVIATIONS = "abbreviations.json"
    const val RANKING = "ranking.json"
    const val STREET_NORM = "us_street_norm.json"
    const val LOOKUP_CONFIG = "lookup_config.json"
    const val SEARCH_APP = "search_app.json"
    /** packbuilder/addresses/addrlib/fingerprint_spellings.json (docs/architecture.md 2.11). */
    const val FINGERPRINT_SPELLINGS = "fingerprint_spellings.json"
    /** assets/poi_icons.json: search category id -> sprite icon name (decision 6.20). */
    const val POI_ICONS = "poi_icons.json"

    fun text(name: String): String {
        val s = RuleFiles::class.java.getResourceAsStream("/io/github/spysyweeb/beamline/search/rules/$name")
            ?: error("rule file $name missing from the module resources")
        return s.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    fun json(name: String): JsonObject = Json.parseToJsonElement(text(name)).jsonObject
}

/** ranking.json, typed. */
class Ranking(val json: JsonObject) {
    private val w = json["weights"]!!.jsonObject
    private val s = json["search"]!!.jsonObject
    private val d = json["distance"]!!.jsonObject
    private val a = json["addresses"]!!.jsonObject

    val wName = w.d("name")
    val wImportance = w.d("importance")
    val wPlaceImportance = w.d("place_importance")
    val wDistance = w.d("distance")
    val wContext = w.d("context")
    val wCategoryHit = w.d("category_hit")
    val wBrandHit = w.d("brand_hit")
    val wBrowseImportance = w.d("browse_importance")
    val wStreetAddress = w.d("street_address")
    /** Added to a street result whose typed house number was located (an exact address). */
    val wHouseLocated = w.d("house_located")
    val wNameWhenCategoryOrBrand = w.d("name_when_category_or_brand")
    val wNameWhenCategoryOrBrandPrefix = w.d("name_when_category_or_brand_prefix")

    val tiers: Map<String, Double> = json["tiers"]!!.jsonObject.numbers()
    val kindBonus: Map<String, Double> = json["kind_bonus"]!!.jsonObject.numbers()
    val rawTypeLabelSuffix: Map<String, String> = json["raw_type_label_suffix"]?.jsonObject
        ?.filterKeys { !it.startsWith("_") }?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()
    /** US spelling of the words of a type label made from OSM tag values (query.py _us). */
    val usSpellings: Map<String, String> = json["us_spellings"]?.jsonObject
        ?.filterKeys { !it.startsWith("_") }?.mapValues { it.value.jsonPrimitive.content } ?: emptyMap()

    val scaleKm = d.d("scale_km")
    val browseScaleKm = d.d("browse_scale_km")
    val contextBrowseScaleKm = d.d("context_browse_scale_km")

    val ftsCandidates = s.i("fts_candidates")
    val shortPrefixChars = s.i("short_prefix_chars")
    val nearRadiusKm: List<Double> = s["near_radius_km"]!!.jsonArray.map { it.jsonPrimitive.double }
    val browseRtreeFirstMaxKm: Double = s["browse_rtree_first_max_km"]?.jsonPrimitive?.double ?: 0.0
    val browseMinHits = s.i("browse_min_hits")
    val streetPoiMarginKm = s.d("street_poi_margin_km")
    val fuzzyMinChars = s.i("fuzzy_min_chars")
    val trigramFallbackFromChars = s.i("trigram_fallback_from_chars")
    val trigramCandidates = s.i("trigram_candidates")
    val trigramMaxDistance = s.i("trigram_max_distance")
    val fuzzyCandidatesPerToken = s.i("fuzzy_candidates_per_token")
    val fuzzyFactor = s.d("fuzzy_factor")
    val fuzzyIfStrongBelow = s.i("fuzzy_if_strong_below")
    val dominanceRatio = s["dominance_ratio"]!!.jsonPrimitive.long
    val dominanceMinFreq = s["dominance_min_freq"]!!.jsonPrimitive.long
    val prefixFactor = s.d("prefix_factor")
    val brandPrefixMinChars = s.i("brand_prefix_min_chars")
    val brandMinPois = s["brand_min_pois"]!!.jsonPrimitive.long
    val shortBrandMinCount = s["short_brand_min_count"]!!.jsonPrimitive.long
    val topN = s.i("top_n")
    val categoryKeepRatio = s.d("category_keep_ratio")
    val dedupeKm = s.d("dedupe_km")
    val shortPrefixPoiRings: List<Int> = s["short_prefix_poi_rings"]!!.jsonArray.map { it.jsonPrimitive.int }
    val shortPrefixPoiMinHits = s.i("short_prefix_poi_min_hits")
    val shortPrefixPoiKeep = s.i("short_prefix_poi_keep")
    val coverageMarginKm = s.d("coverage_margin_km")
    val contextPlaceTypes: Set<String> = s["context_place_types"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
    /** Rule S5: the engine searches only the first this many code points of a text (query.py alike). */
    val maxQueryChars = s.i("max_query_chars")

    // the streets' lines (query.py step 7): where two streets meet, and the street at a point
    private val x = json["crossing"]!!.jsonObject
    private val ns = json["near_street"]!!.jsonObject
    val crossWords: Set<String> = x["words"]!!.jsonArray.map { it.jsonPrimitive.content }.toSet()
    val crossWithinM = x.d("within_m")
    val crossApartM = x.d("apart_m")
    val crossCandidatesPerSide = x.i("candidates_per_side")
    val crossMaxPairs = x.i("max_pairs")
    val crossMaxSegmentTests = x["max_segment_tests"]!!.jsonPrimitive.long
    val crossBoxMarginDeg = x.d("box_margin_deg")
    val crossMaxResults = x.i("max_results")
    val crossScore = x.d("score")
    val nearStreetWithinM = ns.d("within_m")
    val nearStreetMaxStreets = ns.i("max_streets")
    val nearStreetHouseWithinM = ns.d("house_within_m")

    val addrTable = a.str("table")
    val addrHousenumber = a.str("housenumber")
    val addrStreetKey = a.str("street_key")
    val addrLat = a.str("lat")
    val addrLon = a.str("lon")
    val addrVersionTable = a.str("version_table")
    val addrVersionKey = a.str("version_key")
    val houseMarginDeg = a.d("house_margin_deg")

    init {
        require(maxQueryChars > 0) { "ranking.json search.max_query_chars must be positive" }
        // identifiers go into SQL text (as in the reference): allow plain names only
        for (id in listOf(addrTable, addrHousenumber, addrStreetKey, addrLat, addrLon, addrVersionTable)) {
            require(Regex("[A-Za-z_][A-Za-z0-9_]*").matches(id)) { "bad identifier in ranking.json addresses: $id" }
        }
    }

    companion object {
        fun load(): Ranking = Ranking(RuleFiles.json(RuleFiles.RANKING))
        private fun JsonObject.d(k: String) = this[k]!!.jsonPrimitive.double
        private fun JsonObject.i(k: String) = this[k]!!.jsonPrimitive.int
        private fun JsonObject.str(k: String) = this[k]!!.jsonPrimitive.content
        private fun JsonObject.numbers(): Map<String, Double> = entries
            .filter { !it.key.startsWith("_") && it.value.isNumber() }
            .associate { it.key to it.value.jsonPrimitive.double }
        private fun JsonElement.isNumber() = this is JsonPrimitive && !this.isString && this.content.toDoubleOrNull() != null
    }
}
