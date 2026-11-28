// FILE: nav/session/src/main/kotlin/io/github/spysyweeb/beamline/nav/GuidanceRules.kt
package io.github.spysyweeb.beamline.nav

import io.github.spysyweeb.beamline.routing.model.ManeuverType

/**
 * guidance.json (source: packbuilder/routing/guidance.json, copied by android/routing/scripts/sync_data.py).
 * Each field names its JSON key. Parsing and validation: track N.
 */
data class GuidanceRules(
    val fixMaxAgeS: Double,                 // fixes.max_age_s
    val progressMaxAccuracyM: Double,       // fixes.progress_max_accuracy_m
    val decideMaxAccuracyM: Double,         // fixes.decide_max_accuracy_m
    val guidingProviders: List<String>,     // fixes.guiding_providers
    val weakAfterS: Double,                 // gps.weak_after_s
    val lostAfterS: Double,                 // gps.lost_after_s
    val deadReckonMinSpeedMps: Double,      // dead_reckoning.min_speed_mps
    val backstepHoldM: Double,              // snap.backstep_hold_m
    val advanceEntryM: Double,              // advance.entry_m
    val advanceExitM: Double,               // advance.exit_m
    val advancePastM: Double,               // advance.past_m
    val offRouteBaseM: Double,              // off_route.base_m
    val offRouteAccuracyFactor: Double,     // off_route.accuracy_factor
    val offRouteConfirmFixes: Int,          // off_route.confirm_fixes
    val offRouteConfirmS: Double,           // off_route.confirm_s
    val rejoinFixes: Int,                   // off_route.rejoin_fixes
    val wrongWayDeg: Double,                // off_route.wrong_way_deg
    val wrongWayMinSpeedMps: Double,        // off_route.wrong_way_min_speed_mps
    val rerouteMinIntervalS: Double,        // reroute.min_interval_s
    val rerouteRetryS: Double,              // reroute.retry_s
    val rerouteMaxAttempts: Int,            // reroute.max_attempts
    val rerouteHeadingMinSpeedMps: Double,  // reroute.heading_min_speed_mps
    val rerouteOriginRadiusM: Int,          // reroute.origin_radius_m
    val startFarFromRouteM: Double,         // reroute.start_far_from_route_m
    val arriveWithinM: Double,              // arrival.within_m
    val arrivePassedM: Double,              // arrival.passed_m
    val arriveReachM: Double,               // arrival.reach_m
    val arriveReachRouteM: Double,          // arrival.reach_route_m
    val arriveReachOffRouteMaxMps: Double,  // arrival.reach_off_route_max_mps
    val arriveLeaveM: Double,               // arrival.leave_m
    val stopCardS: Double,                  // arrival.stop_card_s
    val distanceHoldM: Double,              // banner.distance_hold_m
    val lanesWithinHighwayM: Double,        // banner.lanes_within_m.highway
    val lanesWithinOtherM: Double,          // banner.lanes_within_m.other
    val stoppedBelowMps: Double,            // motion.stopped_below_mps
    val stoppedAfterS: Double,              // motion.stopped_after_s
    val movingAboveMps: Double,             // motion.moving_above_mps
    val originMaxAgeS: Double,              // planning.origin_max_age_s
    val originMaxAccuracyM: Double,         // planning.origin_max_accuracy_m
    val waitForFixS: Double,                // planning.wait_for_fix_s
    val planCoalesceMs: Long,               // planning.coalesce_ms
    val maxStops: Int,                      // planning.max_stops
    val problemHoldS: Double,               // planning.problem_hold_s
    val announceHighwayM: List<Double>,     // announce.highway_m
    val announceOtherM: List<Double>,       // announce.other_m
    val announceNowS: Double,               // announce.now_s
    val resumeMaxAgeS: Double,              // resume.max_age_s
    val resumeRefreshS: Double,             // resume.refresh_s
    val notificationMinIntervalS: Double,   // notification.min_interval_s
    val tickS: Double,                      // tick_s
    /** lane_warning: rule B9 (batch C1). */
    val laneWarning: LaneWarningRules = LaneWarningRules(),
    /** lead: rule G8 (2026-11-24). */
    val lead: LeadRules = LeadRules(),
) {
    companion object {
        fun parse(text: String): GuidanceRules = GuidanceRulesFile.parse(text)
        fun bundled(): GuidanceRules = GuidanceRulesFile.parse(GuidanceRulesFile.bundledText())
    }
}

/**
 * guidance.json `lane_warning` (rule B9, batch C1): the notice of a lane that ends in a turn shows
 * from max(min_m, lead_s x speed), at most max_m, before the intersection until after_m past it;
 * lookback_m: how far back the road's own lane count is read; max_calls: trace_attributes calls per
 * route at most (nearest first); same_count_evidence_m: road with as many lanes as the intersection
 * that counts as the road's own lane count (else the stretch may be the turn pocket itself);
 * fork_angle_deg (item 37): another road that leaves the junction within this of straight ahead makes
 * it a fork, where the lanes to use are shown though no lane ends.
 */
/**
 * guidance.json `lead` (rule G8; the owner, 2026-11-24: "the location on screen is a little behind
 * where I'm at"). A fix tells where the car was when the fix was taken, and the arrow then glides to
 * it over the time to the next fix (K3), so without this the arrow trails the car by the fix's age
 * plus about a second. The shown position is moved ahead along the route by the speed times (the
 * fix's age + the glide + [extraS]), so the glide ends where the car is when it ends.
 * [minSpeedMps]: no lead slower than this; [maxAccuracyM]: none from a poorer fix; [maxGlideS]: a
 * gap longer than this between two fixes counts as no glide (the map jumps there, K3); [extraS]:
 * the receiver's own delay that the fix's time does not show (to tune on a drive); [maxM]: never
 * more than this ahead of the fix; [stopAt]: never past the next maneuver of these types (the car
 * slows for them, and the arrow must not turn early); never behind the fix.
 */
data class LeadRules(
    val minSpeedMps: Double = 2.2,
    val maxAccuracyM: Double = 30.0,
    val maxGlideS: Double = 3.0,
    val extraS: Double = 0.5,
    val maxM: Double = 60.0,
    val stopAt: Set<ManeuverType> = setOf(
        ManeuverType.LEFT, ManeuverType.RIGHT, ManeuverType.SHARP_LEFT, ManeuverType.SHARP_RIGHT,
        ManeuverType.UTURN_LEFT, ManeuverType.UTURN_RIGHT, ManeuverType.ROUNDABOUT_ENTER, ManeuverType.ROUNDABOUT_EXIT,
        ManeuverType.FERRY_ENTER, ManeuverType.DESTINATION, ManeuverType.DESTINATION_LEFT, ManeuverType.DESTINATION_RIGHT,
    ),
)

data class LaneWarningRules(
    val leadS: Double = 18.0,
    val minM: Double = 150.0,
    val maxM: Double = 600.0,
    val afterM: Double = 5.0,
    val lookbackM: Double = 500.0,
    val maxCalls: Int = 40,
    val sameCountEvidenceM: Double = 300.0,
    val forkAngleDeg: Double = 50.0,
)
