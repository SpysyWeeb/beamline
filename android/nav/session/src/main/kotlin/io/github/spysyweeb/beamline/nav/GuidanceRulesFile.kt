package io.github.spysyweeb.beamline.nav

import io.github.spysyweeb.beamline.routing.model.ManeuverType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Reads guidance.json (format beamline-guidance/1; source packbuilder/routing/guidance.json, the
 * copy in this module's resources made by android/routing/scripts/sync_data.py) into
 * [GuidanceRules], strictly: every key of the format must be there, no other key is accepted
 * (a typo must fail here, not silently keep a default), and the values must make sense together
 * (for example weak before lost, a deciding fix no worse than a usable one). Messages name the key
 * and the rule, never a value from elsewhere.
 */
internal object GuidanceRulesFile {
    const val FORMAT = "beamline-guidance/1"
    private const val RESOURCE = "/io/github/spysyweeb/beamline/nav/guidance.json"

    /** The keys of each section, as docs/architecture.md section 3 quotes the file. */
    private val SECTIONS: Map<String, Set<String>> = mapOf(
        "fixes" to setOf("max_age_s", "progress_max_accuracy_m", "decide_max_accuracy_m", "guiding_providers"),
        "gps" to setOf("weak_after_s", "lost_after_s"),
        "dead_reckoning" to setOf("min_speed_mps"),
        "snap" to setOf("backstep_hold_m"),
        "advance" to setOf("entry_m", "exit_m", "past_m"),
        "off_route" to setOf("base_m", "accuracy_factor", "confirm_fixes", "confirm_s", "rejoin_fixes", "wrong_way_deg",
            "wrong_way_min_speed_mps"),
        "reroute" to setOf("min_interval_s", "retry_s", "max_attempts", "heading_min_speed_mps", "origin_radius_m",
            "start_far_from_route_m"),
        "arrival" to setOf("within_m", "passed_m", "leave_m", "stop_card_s"),
        "banner" to setOf("distance_hold_m", "lanes_within_m"),
        "motion" to setOf("stopped_below_mps", "stopped_after_s", "moving_above_mps"),
        "planning" to setOf("origin_max_age_s", "origin_max_accuracy_m", "wait_for_fix_s", "coalesce_ms", "max_stops",
            "problem_hold_s"),
        "announce" to setOf("highway_m", "other_m", "now_s"),
        "resume" to setOf("max_age_s", "refresh_s"),
        "notification" to setOf("min_interval_s"),
        "lane_warning" to setOf("lead_s", "min_m", "max_m", "after_m", "lookback_m", "max_calls", "same_count_evidence_m",
            "fork_angle_deg"),
        "lead" to setOf("min_speed_mps", "max_accuracy_m", "max_glide_s", "extra_s", "max_m", "stop_at"),
    )

    /** At most this many stops: the origin and the stops must fit StopOrderer's 10 points (rule C1). */
    const val MAX_STOPS_LIMIT = 9

    fun bundledText(): String =
        (GuidanceRulesFile::class.java.getResourceAsStream(RESOURCE)
            ?: throw IllegalStateException("guidance.json missing (run android/routing/scripts/sync_data.py --only guidance.json)"))
            .use { it.readBytes().toString(Charsets.UTF_8) }

    fun parse(text: String): GuidanceRules {
        val root = try {
            Json.parseToJsonElement(text) as? JsonObject
        } catch (e: RuntimeException) {
            null
        } ?: throw IllegalArgumentException("guidance.json: not a JSON object")
        require((root["format"] as? JsonPrimitive)?.content == FORMAT) { "guidance.json: format is not $FORMAT" }
        val allowedTop = SECTIONS.keys + setOf("format", "note", "tick_s")
        val extra = root.keys - allowedTop
        require(extra.isEmpty()) { "guidance.json: unknown keys ${extra.sorted()}" }
        val missingTop = allowedTop - root.keys - setOf("note")
        require(missingTop.isEmpty()) { "guidance.json: missing ${missingTop.sorted()}" }
        val sec = SECTIONS.mapValues { (name, keys) ->
            val o = root[name] as? JsonObject ?: throw IllegalArgumentException("guidance.json: $name is not an object")
            val unknown = o.keys - keys
            require(unknown.isEmpty()) { "guidance.json: unknown keys in $name: ${unknown.sorted()}" }
            val missing = keys - o.keys
            require(missing.isEmpty()) { "guidance.json: missing in $name: ${missing.sorted()}" }
            o
        }

        fun num(s: String, k: String): Double {
            val p = sec.getValue(s)[k] as? JsonPrimitive
            val d = p?.takeIf { !it.isString }?.doubleOrNull ?: throw IllegalArgumentException("guidance.json: $s.$k is not a number")
            require(d.isFinite() && d >= 0.0) { "guidance.json: $s.$k must be a number of at least 0" }
            return d
        }
        fun whole(s: String, k: String): Long {
            val p = sec.getValue(s)[k] as? JsonPrimitive
            val n = p?.takeIf { !it.isString }?.longOrNull ?: throw IllegalArgumentException("guidance.json: $s.$k is not a whole number")
            require(n >= 0) { "guidance.json: $s.$k must be at least 0" }
            return n
        }
        fun distances(s: String, k: String): List<Double> {
            val a = sec.getValue(s)[k] as? JsonArray ?: throw IllegalArgumentException("guidance.json: $s.$k is not a list")
            val out = a.map { (it as? JsonPrimitive)?.takeIf { p -> !p.isString }?.doubleOrNull ?: throw IllegalArgumentException("guidance.json: $s.$k holds a non-number") }
            require(out.isNotEmpty() && out.all { it > 0.0 }) { "guidance.json: $s.$k must list distances above 0" }
            require(out.zipWithNext().all { (a1, b1) -> a1 > b1 }) { "guidance.json: $s.$k must go from far to near" }
            return out
        }
        /** Maneuver types by their names in lower case ("left", "roundabout_enter"). */
        fun maneuverTypes(s: String, k: String): Set<ManeuverType> {
            val a = sec.getValue(s)[k] as? JsonArray ?: throw IllegalArgumentException("guidance.json: $s.$k is not a list")
            return a.map { e ->
                val name = (e as? JsonPrimitive)?.takeIf { it.isString }?.content
                    ?: throw IllegalArgumentException("guidance.json: $s.$k holds a non-text")
                ManeuverType.entries.firstOrNull { it.name.lowercase() == name }
                    ?: throw IllegalArgumentException("guidance.json: $s.$k: '$name' is not a maneuver type")
            }.toSet()
        }
        val lanes = sec.getValue("banner")["lanes_within_m"] as? JsonObject
            ?: throw IllegalArgumentException("guidance.json: banner.lanes_within_m is not an object")
        require(lanes.keys == setOf("highway", "other")) { "guidance.json: banner.lanes_within_m needs exactly highway and other" }
        fun lane(k: String): Double = (lanes[k] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it > 0.0 }
            ?: throw IllegalArgumentException("guidance.json: banner.lanes_within_m.$k must be a number above 0")
        val providers = (sec.getValue("fixes")["guiding_providers"] as? JsonArray)?.map {
            (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content ?: throw IllegalArgumentException("guidance.json: fixes.guiding_providers holds a non-text")
        } ?: throw IllegalArgumentException("guidance.json: fixes.guiding_providers is not a list")
        require(providers.isNotEmpty()) { "guidance.json: fixes.guiding_providers is empty" }
        require("network" !in providers) { "guidance.json: the network provider never guides (rule G1)" }
        val tick = (root["tick_s"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it > 0.0 }
            ?: throw IllegalArgumentException("guidance.json: tick_s must be a number above 0")

        fun Long.toIntChecked(what: String): Int {
            require(this <= Int.MAX_VALUE) { "guidance.json: $what is too large" }
            return toInt()
        }

        val r = GuidanceRules(
            fixMaxAgeS = num("fixes", "max_age_s"),
            progressMaxAccuracyM = num("fixes", "progress_max_accuracy_m"),
            decideMaxAccuracyM = num("fixes", "decide_max_accuracy_m"),
            guidingProviders = providers,
            weakAfterS = num("gps", "weak_after_s"),
            lostAfterS = num("gps", "lost_after_s"),
            deadReckonMinSpeedMps = num("dead_reckoning", "min_speed_mps"),
            backstepHoldM = num("snap", "backstep_hold_m"),
            advanceEntryM = num("advance", "entry_m"),
            advanceExitM = num("advance", "exit_m"),
            advancePastM = num("advance", "past_m"),
            offRouteBaseM = num("off_route", "base_m"),
            offRouteAccuracyFactor = num("off_route", "accuracy_factor"),
            offRouteConfirmFixes = whole("off_route", "confirm_fixes").toIntChecked("off_route.confirm_fixes"),
            offRouteConfirmS = num("off_route", "confirm_s"),
            rejoinFixes = whole("off_route", "rejoin_fixes").toIntChecked("off_route.rejoin_fixes"),
            wrongWayDeg = num("off_route", "wrong_way_deg"),
            wrongWayMinSpeedMps = num("off_route", "wrong_way_min_speed_mps"),
            rerouteMinIntervalS = num("reroute", "min_interval_s"),
            rerouteRetryS = num("reroute", "retry_s"),
            rerouteMaxAttempts = whole("reroute", "max_attempts").toIntChecked("reroute.max_attempts"),
            rerouteHeadingMinSpeedMps = num("reroute", "heading_min_speed_mps"),
            rerouteOriginRadiusM = whole("reroute", "origin_radius_m").toIntChecked("reroute.origin_radius_m"),
            startFarFromRouteM = num("reroute", "start_far_from_route_m"),
            arriveWithinM = num("arrival", "within_m"),
            arrivePassedM = num("arrival", "passed_m"),
            arriveLeaveM = num("arrival", "leave_m"),
            stopCardS = num("arrival", "stop_card_s"),
            distanceHoldM = num("banner", "distance_hold_m"),
            lanesWithinHighwayM = lane("highway"),
            lanesWithinOtherM = lane("other"),
            stoppedBelowMps = num("motion", "stopped_below_mps"),
            stoppedAfterS = num("motion", "stopped_after_s"),
            movingAboveMps = num("motion", "moving_above_mps"),
            originMaxAgeS = num("planning", "origin_max_age_s"),
            originMaxAccuracyM = num("planning", "origin_max_accuracy_m"),
            waitForFixS = num("planning", "wait_for_fix_s"),
            planCoalesceMs = whole("planning", "coalesce_ms"),
            maxStops = whole("planning", "max_stops").toIntChecked("planning.max_stops"),
            problemHoldS = num("planning", "problem_hold_s"),
            announceHighwayM = distances("announce", "highway_m"),
            announceOtherM = distances("announce", "other_m"),
            announceNowS = num("announce", "now_s"),
            resumeMaxAgeS = num("resume", "max_age_s"),
            resumeRefreshS = num("resume", "refresh_s"),
            notificationMinIntervalS = num("notification", "min_interval_s"),
            tickS = tick,
            laneWarning = LaneWarningRules(
                leadS = num("lane_warning", "lead_s"),
                minM = num("lane_warning", "min_m"),
                maxM = num("lane_warning", "max_m"),
                afterM = num("lane_warning", "after_m"),
                lookbackM = num("lane_warning", "lookback_m"),
                maxCalls = whole("lane_warning", "max_calls").toIntChecked("lane_warning.max_calls"),
                sameCountEvidenceM = num("lane_warning", "same_count_evidence_m"),
                forkAngleDeg = num("lane_warning", "fork_angle_deg"),
            ),
            lead = LeadRules(
                minSpeedMps = num("lead", "min_speed_mps"),
                maxAccuracyM = num("lead", "max_accuracy_m"),
                maxGlideS = num("lead", "max_glide_s"),
                extraS = num("lead", "extra_s"),
                maxM = num("lead", "max_m"),
                stopAt = maneuverTypes("lead", "stop_at"),
            ),
        )
        val problems = consistency(r)
        require(problems.isEmpty()) { "guidance.json: " + problems.joinToString("; ") }
        return r
    }

    /** The rules of section 3 that tie two values together. */
    fun consistency(r: GuidanceRules): List<String> = buildList {
        if (r.decideMaxAccuracyM > r.progressMaxAccuracyM) add("fixes.decide_max_accuracy_m above progress_max_accuracy_m: a deciding fix must be usable")
        if (r.weakAfterS >= r.lostAfterS) add("gps.weak_after_s must be below lost_after_s (G2)")
        if (r.fixMaxAgeS <= 0.0) add("fixes.max_age_s must be above 0")
        if (r.stoppedBelowMps > r.movingAboveMps) add("motion.stopped_below_mps above moving_above_mps (M2)")
        if (r.advanceEntryM <= 0.0 || r.advanceExitM <= 0.0) add("advance.entry_m and exit_m must be above 0 (B2)")
        if (r.advancePastM <= r.advanceExitM) add("advance.past_m must be above exit_m (B2's forced advance comes after the normal one)")
        if (r.offRouteConfirmFixes < 1) add("off_route.confirm_fixes must be at least 1 (O3)")
        if (r.rejoinFixes < 1) add("off_route.rejoin_fixes must be at least 1 (O5)")
        if (r.wrongWayDeg > 180.0) add("off_route.wrong_way_deg must be at most 180")
        if (r.offRouteBaseM <= 0.0) add("off_route.base_m must be above 0 (O1)")
        if (r.rerouteMaxAttempts < 1) add("reroute.max_attempts must be at least 1 (O7)")
        if (r.arriveWithinM >= r.arriveLeaveM) add("arrival.within_m must be below leave_m (A3)")
        if (r.maxStops < 1 || r.maxStops > MAX_STOPS_LIMIT) add("planning.max_stops must be 1..$MAX_STOPS_LIMIT (C1: the origin and the stops fit StopOrderer's 10 points)")
        if (r.resumeRefreshS <= 0.0 || r.resumeRefreshS >= r.resumeMaxAgeS) add("resume.refresh_s must be above 0 and below max_age_s (R1, R2)")
        if (r.tickS > r.weakAfterS) add("tick_s above gps.weak_after_s: WEAK would start between ticks (G3)")
        if (r.lead.maxGlideS <= 0.0 || r.lead.maxGlideS > 3.0) add("lead.max_glide_s must be above 0 and at most 3, the map's longest glide (G8, VehicleGlide.MAX_GLIDE_MS)")
        if (r.lead.extraS > 2.0) add("lead.extra_s above 2: no receiver is that slow (G8)")
        if (r.lead.maxAccuracyM > r.progressMaxAccuracyM) add("lead.max_accuracy_m above fixes.progress_max_accuracy_m: no usable fix is that poor (G8)")
    }
}
