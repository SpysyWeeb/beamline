package io.github.spysyweeb.beamline.routing.banner

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * The banner rules from banner.json (source: packbuilder/routing/banner.json, copied by
 * android/routing/scripts/sync_data.py). Thresholds and road-name abbreviations live there, not
 * here. Distance texts are not made by this module: every distance on screen comes from
 * :core:units UnitFormatter (docs/architecture.md 2.9).
 */
data class BannerRules(
    /** The following turn is shown when it comes within this distance ... */
    val followingMaxDistanceM: Double,
    /** ... and within this time of the main maneuver (the route's own estimate). */
    val followingMaxTimeS: Double,
    /** Maneuver types that are never a following turn (arriving). */
    val notATurnTypes: Set<Int>,
    /** A lane row needs at least one lane the engine marked valid or active. */
    val rowNeedsMarkedLane: Boolean,
    /** Narrow several best lanes to the one on the side of the following turn. */
    val refineBestLaneForFollowingTurn: Boolean,
    /** Following maneuver types without a side (no narrowing). */
    val noSideTypes: Set<Int>,
    /** road_abbreviations.words: the last word of a road name and its short form ("Street" -> "St"). */
    val roadAbbreviations: Map<String, String>,
) {
    /**
     * [name] with its last word abbreviated ("Peach Street" -> "Peach St"), when that word is one of
     * [roadAbbreviations] (exact match) and another word comes before it; otherwise unchanged.
     */
    fun abbreviate(name: String): String {
        val cut = name.trimEnd().lastIndexOf(' ')
        if (cut <= 0) return name
        val last = name.trimEnd().substring(cut + 1)
        val short = roadAbbreviations[last] ?: return name
        return name.substring(0, cut + 1) + short
    }

    companion object {
        const val FORMAT = "beamline-guidance-banner/1"

        fun parse(text: String): BannerRules {
            val root = Json.parseToJsonElement(text).jsonObject
            require(root["format"]?.jsonPrimitive?.content == FORMAT) { "banner.json: format is not $FORMAT" }
            require("distance_text" !in root) { "banner.json: distance_text moved to units.json (docs/architecture.md 2.9)" }
            val f = root["following_turn"]!!.jsonObject
            val l = root["lanes"]!!.jsonObject
            val words = root["road_abbreviations"]!!.jsonObject["words"]!!.jsonObject
                .mapValues { (k, v) ->
                    val s = v.jsonPrimitive.content
                    require(k.isNotBlank() && ' ' !in k && s.isNotBlank()) { "banner.json: road_abbreviations '$k' -> '$s' is not one word to a short form" }
                    s
                }
            fun ints(o: JsonObject, k: String) = o[k]!!.jsonArray.map { it.jsonPrimitive.int }.toSet()
            return BannerRules(
                followingMaxDistanceM = f["max_distance_m"]!!.jsonPrimitive.double,
                followingMaxTimeS = f["max_time_s"]!!.jsonPrimitive.double,
                notATurnTypes = ints(f, "not_a_turn_types"),
                rowNeedsMarkedLane = l["row_needs_a_marked_lane"]!!.jsonPrimitive.boolean,
                refineBestLaneForFollowingTurn = l["refine_best_lane_for_following_turn"]!!.jsonPrimitive.boolean,
                noSideTypes = ints(l, "no_side_types"),
                roadAbbreviations = words,
            )
        }

        fun bundled(): BannerRules = parse(
            (BannerRules::class.java.getResourceAsStream("/io/github/spysyweeb/beamline/routing/banner/banner.json")
                ?: throw IllegalStateException("banner.json missing (run android/routing/scripts/sync_data.py)"))
                .use { it.readBytes().toString(Charsets.UTF_8) },
        )
    }
}
