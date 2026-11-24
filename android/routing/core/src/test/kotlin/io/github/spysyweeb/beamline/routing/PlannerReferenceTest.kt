package io.github.spysyweeb.beamline.routing

import io.github.spysyweeb.beamline.routing.model.LatLon
import io.github.spysyweeb.beamline.routing.model.RoadFlag
import io.github.spysyweeb.beamline.routing.model.RouteRequest
import io.github.spysyweeb.beamline.routing.model.Stop
import io.github.spysyweeb.beamline.routing.planner.ArrivalSideResult
import io.github.spysyweeb.beamline.routing.planner.PlanResult
import io.github.spysyweeb.beamline.routing.planner.TieCheckResult
import io.github.spysyweeb.beamline.routing.planner.RoutePlanner
import io.github.spysyweeb.beamline.routing.preset.PresetSet
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * The old 126 plans: the Kotlin planner against the Python reference (route_lib.run_preset) on the
 * 21 trips of trips.json and the six presets of format 1 (reference/presets-format1.json, the file
 * before format 2), with the engine answers recorded from pyvalhalla 3.9.1 on the PA+OH device
 * tiles (android/routing/scripts/reference_routes.py; rerunning it with format 2 in place gives the
 * same bytes). The replay engine fails on any request the reference did not make, so the requests
 * are the same too. Format 2: [PlansReferenceTest].
 */
class PlannerReferenceTest {
    private val trips = Fixtures.json("trips.json")
    private val places = trips["places"]!!.jsonObject
    private val reference = Fixtures.json("reference/trips.json")
    private val engine = ReplayEngine.from("reference/recordings.json.gz")
    private val presets = PresetSet.bundled()
    private val format1 = PresetSet.parseFormat1(Fixtures.text("reference/presets-format1.json"))
    private val planner = RoutePlanner(engine, presets)

    private fun stop(place: String) = places[place]!!.jsonObject.let {
        Stop(LatLon(it["lat"]!!.jsonPrimitive.double, it["lon"]!!.jsonPrimitive.double))
    }

    private fun tripStops(t: JsonObject) = listOf(stop(t["from"]!!.jsonPrimitive.content), stop(t["to"]!!.jsonPrimitive.content))

    private fun f1(id: String) = format1.first { it.id == id }

    @Test
    fun `six presets x 21 trips pick the same route as the Python reference`() {
        var compared = 0
        for (preset in format1) {
            val ref = reference["presets"]!!.jsonObject[preset.id]!!.jsonObject
            for (t in trips["trips"]!!.jsonArray.map { it.jsonObject }) {
                val id = t["id"]!!.jsonPrimitive.content
                val want = ref[id]!!.jsonObject
                val got = planner.plan(RouteRequest(tripStops(t)), preset)
                val where = "${preset.id} $id"
                assertTrue(got is PlanResult.Ok, "$where: $got")
                got as PlanResult.Ok
                val r = got.route
                assertClose(want["time_s"]!!.jsonPrimitive.double, r.timeS, 0.051, "$where time")
                assertClose(want["length_km"]!!.jsonPrimitive.double, r.lengthM / 1000, 0.0006, "$where length")
                assertEquals(want["maneuvers"]!!.jsonPrimitive.int, r.turnCount, "$where maneuvers")
                assertEquals(want["maneuvers_total"]!!.jsonPrimitive.int, r.maneuvers.size, "$where maneuvers total")
                assertEquals(want["legs"]!!.jsonPrimitive.int, r.legs.size, "$where legs")
                assertClose(want["highway_km"]!!.jsonPrimitive.double, r.flaggedM(RoadFlag.HIGHWAY) / 1000, 0.006, "$where highway km")
                assertClose(want["toll_km"]!!.jsonPrimitive.double, r.flaggedM(RoadFlag.TOLL) / 1000, 0.006, "$where toll km")
                val gaps = want["endpoint_gap_m"]!!.jsonArray.map { it.jsonPrimitive.double }
                val (g0, g1) = r.endpointGapsM()!!
                assertClose(gaps[0], g0, 0.51, "$where start gap")
                assertClose(gaps[1], g1, 0.51, "$where end gap")
                assertEquals(want["used_fallback"]?.jsonPrimitive?.booleanOrNull ?: false, got.usedFallback, "$where fallback")
                assertEquals(want["candidates_considered"]?.jsonPrimitive?.int, got.candidatesConsidered, "$where pool size")
                compared++
            }
        }
        assertEquals(6 * 21, compared)
    }

    private fun assertSameAlternates(want: List<JsonObject>, got: List<io.github.spysyweeb.beamline.routing.model.Route>, where: String) {
        assertEquals(want.size, got.size, "$where alternates")
        want.zip(got).forEach { (w, a) ->
            assertClose(w["time_s"]!!.jsonPrimitive.double, a.timeS, 0.051, "$where alternate time")
            assertClose(w["length_km"]!!.jsonPrimitive.double, a.lengthM / 1000, 0.0006, "$where alternate length")
            assertClose(w["highway_km"]!!.jsonPrimitive.double, a.flaggedM(RoadFlag.HIGHWAY) / 1000, 0.006, "$where alternate highway km")
        }
    }

    @Test
    fun `alternates of two-stop trips match the reference, every preset`() {
        var compared = 0
        for (preset in format1) for (t in trips["trips"]!!.jsonArray.map { it.jsonObject }) {
            val id = t["id"]!!.jsonPrimitive.content
            val want = reference["alternates"]!!.jsonObject[preset.id]!!.jsonObject[id]!!.jsonObject["alternates"]!!.jsonArray.map { it.jsonObject }
            val got = planner.plan(RouteRequest(tripStops(t), withAlternates = true), preset) as PlanResult.Ok
            assertSameAlternates(want, got.alternates, "${preset.id} $id")
            // fastest + alternates: one route call and one alternates call, one tie check call (every_request.tie_check)
            // and one arrival side call when the route arrives with the destination on the left (every_request.arrival_side)
            if (preset.id == "fastest") assertEquals(2 + extraCalls(got), got.engineCalls, "$id calls")
            compared++
        }
        assertEquals(6 * 21, compared)
    }

    /**
     * Review finding (major): after Avoid highways' fallback the alternates were asked with
     * exclude_highways, the options the fallback had just rejected. Trips that start on a motorway.
     */
    @Test
    fun `trips that start on a motorway - avoid highways fallback and its alternates match the reference`() {
        val cases = reference["motorway_origin"]!!.jsonObject
        assertEquals(4, cases.size)
        var fallbacks = 0
        for ((name, row) in cases) {
            val stops = row.jsonObject["stops"]!!.jsonArray.map { s ->
                val o = s.jsonObject
                Stop(LatLon(o["lat"]!!.jsonPrimitive.double, o["lon"]!!.jsonPrimitive.double),
                    headingDeg = o["heading"]?.jsonPrimitive?.int, radiusM = o["radius"]?.jsonPrimitive?.int,
                    locationOptions = o["search_filter"]?.let { JsonObject(mapOf("search_filter" to it)) })
            }
            for (pid in listOf("fastest", "avoid_highways")) {
                val want = row.jsonObject[pid]!!.jsonObject
                val got = planner.plan(RouteRequest(stops, withAlternates = true), f1(pid)) as PlanResult.Ok
                // format 2 composes the same preset: the same answers
                val composed = planner.plan(RouteRequest(stops, withAlternates = true), presets.resolve(pid)) as PlanResult.Ok
                assertEquals(got.route.timeS, composed.route.timeS, "$name $pid composed")
                assertEquals(got.alternates.map { it.timeS }, composed.alternates.map { it.timeS }, "$name $pid composed alternates")
                val where = "$name $pid"
                assertClose(want["time_s"]!!.jsonPrimitive.double, got.route.timeS, 0.051, "$where time")
                assertClose(want["highway_km"]!!.jsonPrimitive.double, got.route.flaggedM(RoadFlag.HIGHWAY) / 1000, 0.006, "$where highway km")
                assertEquals(want["used_fallback"]?.jsonPrimitive?.booleanOrNull ?: false, got.usedFallback, "$where fallback")
                if (got.usedFallback) fallbacks++
                assertSameAlternates(want["alternates"]!!.jsonArray.map { it.jsonObject }, got.alternates, where)
                if (pid == "avoid_highways") {
                    val limit = got.route.flaggedM(RoadFlag.HIGHWAY).coerceAtLeast(500.0)
                    assertTrue(got.alternates.all { it.flaggedM(RoadFlag.HIGHWAY) <= limit + 1.0 }, "$where: no alternate keeps more motorway")
                }
            }
        }
        assertEquals(3, fallbacks, "I-90 to Erie, I-90 into Ohio and I-80 to Sharon use the fallback; exit 24 keeps the exclusion")
    }

    // batch H: a tied stop's ends are the nearest street and the place itself, untied: the place's route and one
    // tail call (the kept end's, or the place's when it would win)
    private fun extraCalls(r: PlanResult.Ok) =
        (if (r.tieCheck == TieCheckResult.NOT_ASKED) 0 else 2) + (if (r.arrivalSide == ArrivalSideResult.NOT_ASKED) 0 else 1)

    @Test
    fun `simpler reuses its first pool request for the alternates`() {
        val t = trips["trips"]!!.jsonArray.map { it.jsonObject }.first { it["id"]!!.jsonPrimitive.content == "I1" }
        // format 1's Simpler (three candidates): recordings.json.gz holds the format-1 requests; format 2's
        // Simpler (four candidates since batch D) is compared in PlansReferenceTest
        val got = planner.plan(RouteRequest(tripStops(t), withAlternates = true), f1("simpler")) as PlanResult.Ok
        // the decision's Fastest (batch E2), three candidates (the alternates come from the first), the arrival side call when asked
        assertEquals(1 + 3 + extraCalls(got), got.engineCalls, "the decision, three candidates, the alternates come from the first")
        assertTrue(got.alternates.none { abs(it.timeS - got.route.timeS) < 0.5 && abs(it.lengthM - got.route.lengthM) < 1 })
    }

    private fun assertClose(want: Double, got: Double, tol: Double, what: String) =
        assertTrue(abs(want - got) <= tol, "$what: want $want, got $got")
}
