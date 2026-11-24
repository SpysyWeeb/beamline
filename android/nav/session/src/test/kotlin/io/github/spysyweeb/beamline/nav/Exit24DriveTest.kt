package io.github.spysyweeb.beamline.nav

import io.github.spysyweeb.beamline.api.nav.GpsState
import io.github.spysyweeb.beamline.api.nav.Motion
import io.github.spysyweeb.beamline.api.nav.NavIntent
import io.github.spysyweeb.beamline.api.nav.Phase
import io.github.spysyweeb.beamline.api.nav.Simulation
import io.github.spysyweeb.beamline.api.nav.TripEvent
import io.github.spysyweeb.beamline.api.nav.TripState
import io.github.spysyweeb.beamline.location.Fix
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.math.BigDecimal
import java.math.RoundingMode

/**
 * The owner's exit 24 trip (docs/architecture.md section 8) driven by the session with the built-in
 * simulation ("default" script: speed factor 1, 1 fix/s, accuracy 5 m, a 20 s halt about 15 m
 * before maneuver 2), the geometric follower and the recorded engine, on a virtual clock; checked
 * row by row against the golden trace android/routing/testdata/nav/exit24.json and against the
 * rules the clean run must show (the table of section 8).
 *
 * -Pbeamline.writeGolden=true rewrites the trace (then look at the diff before committing it).
 */
class Exit24DriveTest {

    @Test
    fun `the exit 24 drive with the default script matches the golden trace`() = runTest {
        val h = Harness(this)
        try {
            h.planExit24()
            h.assertAllRecorded()
            assertEquals(Phase.CHOOSING, h.phase(), "${h.state.problem}")
            val fastest = h.state.choices.first { "fastest" in it.typeIds }
            assertEquals(fastest.id, h.state.selectedChoiceId)
            h.send(NavIntent.Start(Simulation(scriptName = "default")))
            assertEquals(Phase.GUIDING, h.phase(), "${h.state.problem}")
            var s = 0
            while (h.phase() != Phase.ARRIVED && s++ < 900) h.advance(1_000)
            h.assertAllRecorded()
            assertEquals(Phase.ARRIVED, h.phase())
            val trace = GoldenTrace.of(h.rows)
            GoldenTrace.check(trace)
            checkCleanRun(h)
        } finally {
            h.close()
        }
    }

    /** What section 8 says the clean run shows (rows 1 to 10 and "Throughout"). */
    private fun checkCleanRun(h: Harness) {
        val rows = h.rows.map { it.second }.filter { it.guidance?.banner != null }
        assertTrue(rows.size > 250, "rows ${rows.size}")
        // row 1: "Exit 24", "US 19 / Peach St", "toward Waterford", 2.6 mi; chip left "In 0.3 mi"
        val first = rows.first().guidance!!.banner!!
        assertEquals(1, first.view.maneuverIndex)
        assertEquals("Exit 24", first.view.primary)
        assertEquals(listOf("US 19", "Peach St"), first.view.roads)
        assertEquals(listOf("Waterford"), first.view.towards)
        assertEquals(4173.0, first.distanceM, 25.0)
        assertEquals("Turn left", first.view.following!!.primary)
        assertEquals(421.0, first.view.following!!.distanceM, 5.0)
        assertTrue(!first.showLanes)
        // U advances 1 -> 2 -> 3 -> 4 and never back; the shown distance never goes up within a maneuver
        val us = rows.map { it.guidance!!.banner!!.view.maneuverIndex }
        assertEquals(listOf(1, 2, 3, 4), us.distinct())
        assertEquals(us, us.sorted())
        rows.zipWithNext().forEach { (a, b) ->
            val ba = a.guidance!!.banner!!
            val bb = b.guidance!!.banner!!
            if (ba.view.maneuverIndex == bb.view.maneuverIndex) assertTrue(bb.distanceM <= ba.distanceM + 1e-9, "distance went up: ${ba.distanceM} -> ${bb.distanceM}")
        }
        // row 4: at the left turn the lanes show at once (422 m < 0.3 mi), chip right "In 0.2 mi"
        val left = rows.first { it.guidance!!.banner!!.view.maneuverIndex == 2 }.guidance!!.banner!!
        // item 22: the end of the exit ramp is a traffic light, and the banner says so
        assertEquals("At the light, turn left", left.view.primary)
        assertEquals(listOf("US 19", "Peach St", "General Strong Vincent Memorial Hwy"), left.view.roads)
        assertTrue(left.showLanes)
        assertEquals("Turn right", left.view.following!!.primary)
        // row 7: Turn right onto Downs Dr, no chip, lanes at once
        val right = rows.first { it.guidance!!.banner!!.view.maneuverIndex == 3 }.guidance!!.banner!!
        assertEquals(listOf("Downs Dr"), right.view.roads)
        assertEquals(null, right.view.following)
        assertTrue(right.showLanes)
        // row 9: "Arrive" with no chip, no lanes, counting down
        val arrive = rows.first { it.guidance!!.banner!!.view.maneuverIndex == 4 }.guidance!!.banner!!
        assertTrue(arrive.view.primary.startsWith("Arrive"))
        assertEquals(null, arrive.view.following)
        assertEquals(null, arrive.view.lanes)
        // row 6: the halt before the left turn: STOPPED after a second, nothing advances
        val halted = h.rows.map { it.second }.filter { it.guidance?.vehicle?.motion == Motion.STOPPED && it.phase == Phase.GUIDING }
        assertTrue(halted.size >= 15, "stopped rows ${halted.size}")
        assertTrue(halted.all { it.guidance!!.banner!!.view.maneuverIndex == 2 && it.guidance!!.banner!!.distanceM in 10.0..20.0 },
            "halted ${halted.map { it.guidance!!.banner!!.distanceM }}")
        // batch A, rule D8: the posted limit of the stretch driven: 89 km/h (55 mph) on I-79, none known
        // on the ramp, 72 (45 mph) on Peach St, none known on Downs Dr at the end
        assertEquals(89.0, rows.first().guidance!!.speedLimitKmh)
        val limits = rows.map { it.guidance!!.speedLimitKmh }
        assertEquals(listOf(89.0, null, 72.0, null), limits.fold(listOf<Double?>()) { acc, l -> if (acc.isNotEmpty() && acc.last() == l) acc else acc + l })
        // throughout: gps GOOD, no REROUTING, never suspected
        assertTrue(h.rows.all { it.second.phase != Phase.REROUTING })
        assertTrue(h.rows.all { it.second.gps == GpsState.GOOD })
        assertTrue(h.rows.all { it.second.guidance?.offRouteSuspected != true })
        // row 10: ARRIVED, the Arrived event with the destination's name
        assertEquals(listOf(TripEvent.Arrived("Downs Drive")), h.eventsOf(TripEvent.Arrived::class.java))
        assertEquals(1, h.eventsOf(TripEvent.GuidanceStarted::class.java).size)
        assertTrue(h.eventsOf(TripEvent.RouteReplaced::class.java).isEmpty())
    }
}

/**
 * The golden trace of the exit 24 drive (android/routing/testdata/nav/exit24.json, format
 * beamline-golden-trace/1): its inputs, and per fix the session handled the session's outputs.
 * Numbers are rounded (metres to 0.01, degrees to 1e-6) so the file is stable and readable by a
 * later Python port of the session (comma 3X, docs/architecture.md 7) and by track A.
 */
object GoldenTrace {
    const val FILE = "nav/exit24.json"

    private fun r(v: Double?, d: Int): JsonElement = v?.let { JsonPrimitive(BigDecimal(it).setScale(d, RoundingMode.HALF_EVEN).toDouble()) } ?: JsonNull

    fun row(fix: Fix, s: TripState): JsonObject {
        val g = s.guidance
        val b = g?.banner
        return JsonObject(linkedMapOf(
            "t_ms" to JsonPrimitive(fix.elapsedMs),
            "fix" to JsonObject(linkedMapOf(
                "lat" to r(fix.lat, 6), "lon" to r(fix.lon, 6), "accuracy_m" to r(fix.accuracyM?.toDouble(), 1),
                "speed_mps" to r(fix.speedMps?.toDouble(), 2), "bearing_deg" to r(fix.bearingDeg?.toDouble(), 1),
                "provider" to JsonPrimitive(fix.provider),
            )),
            "phase" to JsonPrimitive(s.phase.name),
            "u" to (b?.view?.maneuverIndex?.let(::JsonPrimitive) ?: JsonNull),
            "primary" to (b?.view?.primary?.let(::JsonPrimitive) ?: JsonNull),
            "shown_m" to r(b?.distanceM, 2),
            "chip" to (b?.view?.following?.let { JsonObject(linkedMapOf("primary" to JsonPrimitive(it.primary), "distance_m" to r(it.distanceM, 2))) } ?: JsonNull),
            "lanes_shown" to JsonPrimitive(b?.showLanes ?: false),
            "gps" to JsonPrimitive(s.gps.name),
            "motion" to JsonPrimitive(g?.vehicle?.motion?.name ?: "UNKNOWN"),
            "vehicle" to (g?.vehicle?.let { v -> JsonObject(linkedMapOf("lat" to r(v.point.lat, 6), "lon" to r(v.point.lon, 6), "snapped" to JsonPrimitive(v.snapped))) } ?: JsonNull),
            "off_route_suspected" to JsonPrimitive(g?.offRouteSuspected ?: false),
            "remaining_m" to r(g?.remainingM, 2),
        ))
    }

    fun of(rows: List<Pair<Fix, TripState>>): JsonObject = JsonObject(linkedMapOf(
        "format" to JsonPrimitive("beamline-golden-trace/1"),
        "note" to JsonPrimitive("The owner's exit 24 trip (docs/architecture.md section 8) driven by the navigation session " +
            "(android/nav/session, Exit24DriveTest) with the built-in simulation, the geometric follower (GeometricFollower: " +
            "the follower contract of 2.4) and guidance.json; one row per fix the session handled, after it handled it. " +
            "u = the upcoming maneuver (index into the route's maneuvers, legs flattened); shown_m = the banner distance " +
            "after rule B3; chip = the following turn (B4); lanes_shown (B5); vehicle = where the arrow is drawn. " +
            "Rewrite: sh tools/round/gradle.sh n :nav:session:test -Pbeamline.writeGolden=true"),
        "inputs" to JsonObject(linkedMapOf(
            "route_recording" to JsonPrimitive("android/routing/testdata/reference/banner-routes.json.gz (case exit24: the Fastest request)"),
            "osrm_recording" to JsonPrimitive("android/routing/testdata/reference/exit24-osrm.json"),
            "origin" to JsonObject(linkedMapOf("lat" to JsonPrimitive(42.028694), "lon" to JsonPrimitive(-80.128884), "heading_deg" to JsonPrimitive(57))),
            "destination" to JsonObject(linkedMapOf("lat" to JsonPrimitive(42.053308), "lon" to JsonPrimitive(-80.079579), "name" to JsonPrimitive("Downs Drive"))),
            "route_type" to JsonPrimitive("fastest"),
            "simulation" to JsonObject(linkedMapOf(
                "script_name" to JsonPrimitive("default"), "speed_factor" to JsonPrimitive(1.0), "fix_hz" to JsonPrimitive(1.0),
                "accuracy_m" to JsonPrimitive(5.0), "lateral_noise_m" to JsonPrimitive(0.0), "seed" to JsonPrimitive(1),
                "events" to JsonArray(SimScripts.build("default", Data.exit24).map { JsonPrimitive(it.toString()) }),
            )),
            "guidance_rules" to JsonPrimitive("packbuilder/routing/guidance.json"),
            "follower" to JsonPrimitive("GeometricFollower (android/nav/session)"),
        )),
        "rows" to JsonArray(rows.map { (f, s) -> row(f, s) }),
    ))

    /** Compares with the committed trace, or writes it with -Pbeamline.writeGolden=true. */
    fun check(trace: JsonObject) {
        val root = System.getProperty("beamline.repoRoot")?.let(::File)
        val pretty = Json { prettyPrint = true }
        val text = pretty.encodeToString(JsonObject.serializer(), trace) + "\n"
        if (System.getProperty("beamline.writeGolden") == "true") {
            requireNotNull(root) { "beamline.repoRoot not set" }
            File(root, "android/routing/testdata/$FILE").apply { parentFile.mkdirs() }.writeText(text)
            return
        }
        check(Data.exists(FILE)) { "no golden trace yet: run with -Pbeamline.writeGolden=true" }
        val want = Data.json(FILE)
        val wantRows = want["rows"]!!.jsonArray
        val gotRows = trace["rows"]!!.jsonArray
        for (i in 0 until minOf(wantRows.size, gotRows.size)) {
            assertEquals(wantRows[i].jsonObject, gotRows[i].jsonObject, "row $i (t_ms ${gotRows[i].jsonObject["t_ms"]})")
        }
        assertEquals(wantRows.size, gotRows.size, "rows")
        assertEquals(want["inputs"], trace["inputs"])
    }
}
