package io.github.spysyweeb.beamline.nav

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.Test
import java.io.File

/** guidance.json: every value of docs/architecture.md section 3 read into GuidanceRules, and the refusals. */
class GuidanceRulesTest {
    private val text = GuidanceRulesFile.bundledText()

    @Test
    fun `the bundled file gives the values of section 3`() {
        val r = GuidanceRules.bundled()
        val want = GuidanceRules(
            fixMaxAgeS = 3.0, progressMaxAccuracyM = 50.0, decideMaxAccuracyM = 30.0, guidingProviders = listOf("gps", "simulated"),
            weakAfterS = 2.5, lostAfterS = 30.0, deadReckonMinSpeedMps = 2.0, backstepHoldM = 15.0,
            advanceEntryM = 20.0, advanceExitM = 10.0, advancePastM = 40.0,
            offRouteBaseM = 35.0, offRouteAccuracyFactor = 1.5, offRouteConfirmFixes = 3, offRouteConfirmS = 4.0, rejoinFixes = 3,
            wrongWayDeg = 120.0, wrongWayMinSpeedMps = 3.0,
            rerouteMinIntervalS = 10.0, rerouteRetryS = 15.0, rerouteMaxAttempts = 5, rerouteHeadingMinSpeedMps = 2.0,
            rerouteOriginRadiusM = 60, startFarFromRouteM = 200.0,
            arriveWithinM = 30.0, arrivePassedM = 40.0, arriveReachM = 50.0, arriveReachRouteM = 200.0, arriveReachOffRouteMaxMps = 10.0, arriveLeaveM = 300.0, stopCardS = 8.0,
            distanceHoldM = 30.0, lanesWithinHighwayM = 1609.344, lanesWithinOtherM = 482.8032,
            stoppedBelowMps = 0.5, stoppedAfterS = 1.0, movingAboveMps = 1.0,
            originMaxAgeS = 30.0, originMaxAccuracyM = 100.0, waitForFixS = 15.0, planCoalesceMs = 300, maxStops = 9, problemHoldS = 8.0,
            announceHighwayM = listOf(3218.688, 804.672), announceOtherM = listOf(804.672, 152.4), announceNowS = 6.0,
            resumeMaxAgeS = 1800.0, resumeRefreshS = 30.0, notificationMinIntervalS = 5.0, tickS = 1.0,
        )
        assertEquals(want, r)
        assertEquals(r, GuidanceRules.parse(text))
    }

    @Test
    fun `the copy equals packbuilder's source`() {
        val root = System.getProperty("beamline.repoRoot")?.let(::File)
        assumeTrue(root != null && File(root, "packbuilder/routing/guidance.json").exists(), "repo root not available")
        val src = File(root, "packbuilder/routing/guidance.json").readBytes().toString(Charsets.UTF_8).replace("\r\n", "\n")
        assertEquals(src, text, "run python android/routing/scripts/sync_data.py --only guidance.json")
    }

    private fun refused(edited: String, words: String) {
        val e = assertThrows(IllegalArgumentException::class.java) { GuidanceRules.parse(edited) }
        assertTrue(e.message!!.contains(words), "${e.message} should say '$words'")
    }

    @Test
    fun `a broken or inconsistent file is refused with the key named`() {
        refused("not json", "not a JSON object")
        refused(text.replace("beamline-guidance/1", "beamline-guidance/2"), "format is not beamline-guidance/1")
        refused(text.replace("\"weak_after_s\": 2.5", "\"weak_afer_s\": 2.5"), "unknown keys in gps: [weak_afer_s]")
        refused(text.replace("\"tick_s\": 1", "\"tick\": 1"), "unknown keys [tick]")
        refused(text.replace("\"confirm_fixes\": 3", "\"confirm_fixes\": 3.5"), "off_route.confirm_fixes is not a whole number")
        refused(text.replace("\"base_m\": 35", "\"base_m\": \"35\""), "off_route.base_m is not a number")
        refused(text.replace("\"lost_after_s\": 30", "\"lost_after_s\": 2"), "weak_after_s must be below lost_after_s")
        refused(text.replace("\"decide_max_accuracy_m\": 30", "\"decide_max_accuracy_m\": 60"), "a deciding fix must be usable")
        refused(text.replace("\"max_stops\": 9", "\"max_stops\": 10"), "planning.max_stops must be 1..9")
        refused(text.replace("[\"gps\", \"simulated\"]", "[\"gps\", \"network\"]"), "network provider never guides")
        refused(text.replace("[3218.688, 804.672]", "[804.672, 3218.688]"), "announce.highway_m must go from far to near")
        refused(text.replace("\"past_m\": 40", "\"past_m\": 5"), "advance.past_m must be above exit_m")
        refused(text.replace("\"min_speed_mps\": 2.0", "\"min_speed_mps\": -1"), "dead_reckoning.min_speed_mps must be a number of at least 0")
        refused(text.replace("\"other\": 482.8032", "\"lanes_other\": 482.8032"), "banner.lanes_within_m needs exactly highway and other")
        refused(text.replace("\"reach_m\": 50", "\"reach_m\": 300"), "arrival.reach_m must be below leave_m")
        refused(text.replace("\"reach_m\": 50, ", ""), "missing in arrival: [reach_m]")
        refused(text.replace("\"reach_route_m\": 200", "\"reach_route_m\": 20"), "arrival.reach_route_m must be at least reach_m")
    }
}
