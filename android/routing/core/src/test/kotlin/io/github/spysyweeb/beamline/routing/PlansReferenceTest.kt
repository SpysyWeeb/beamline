package io.github.spysyweeb.beamline.routing

import io.github.spysyweeb.beamline.routing.model.Geo
import io.github.spysyweeb.beamline.routing.model.LatLon
import io.github.spysyweeb.beamline.routing.model.ManeuverType
import io.github.spysyweeb.beamline.routing.model.RoadFlag
import io.github.spysyweeb.beamline.routing.model.RouteRequest
import io.github.spysyweeb.beamline.routing.model.Stop
import io.github.spysyweeb.beamline.routing.model.StopEnd
import io.github.spysyweeb.beamline.routing.planner.PlanResult
import io.github.spysyweeb.beamline.routing.planner.TieCheckResult
import io.github.spysyweeb.beamline.routing.planner.ArrivalSideResult
import io.github.spysyweeb.beamline.routing.planner.RoutePlanner
import io.github.spysyweeb.beamline.routing.planner.RouteWarning
import io.github.spysyweeb.beamline.routing.preset.PresetSet
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs

/**
 * Presets format 2: the Kotlin planner against the Python reference (route_lib.compose and
 * run_preset) on 21 trips x {fastest, simpler, eco} x {none, highways, tolls, both} (Shortest left in batch D)
 * and the cases of docs/architecture.md 2.11, with the engine answers recorded from pyvalhalla
 * 3.9.1 on the PA+OH device tiles (reference_routes.py --only plans: reference/plans.json,
 * reference/recordings-plans.json.gz plus the format-1 recordings for the requests both make).
 */
class PlansReferenceTest {
    private val trips = Fixtures.json("trips.json")
    private val places = trips["places"]!!.jsonObject
    private val reference = Fixtures.json("reference/plans.json")
    private val engine = ReplayEngine.from("reference/recordings.json.gz", "reference/recordings-plans.json.gz")
    private val presets = PresetSet.bundled()
    private val planner = RoutePlanner(engine, presets)
    private val optionSets = reference["option_sets"]!!.jsonObject.mapValues { (_, v) -> v.jsonArray.map { it.jsonPrimitive.content }.toSet() }

    private fun stop(place: String) = places[place]!!.jsonObject.let {
        Stop(LatLon(it["lat"]!!.jsonPrimitive.double, it["lon"]!!.jsonPrimitive.double))
    }

    private fun tripStops(t: JsonObject) = listOf(stop(t["from"]!!.jsonPrimitive.content), stop(t["to"]!!.jsonPrimitive.content))

    private fun assertSame(want: JsonObject, got: PlanResult, where: String): PlanResult.Ok {
        assertTrue(want["ok"]!!.jsonPrimitive.booleanOrNull == true, "$where: the reference failed: $want")
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
        assertEquals(want["has_toll"]!!.jsonPrimitive.booleanOrNull, r.hasToll, "$where has_toll")
        assertEquals(want["has_highway"]!!.jsonPrimitive.booleanOrNull, r.hasHighway, "$where has_highway")
        val gaps = want["endpoint_gap_m"]!!.jsonArray.map { it.jsonPrimitive.double }
        val (g0, g1) = r.endpointGapsM()!!
        assertClose(gaps[0], g0, 0.51, "$where start gap")
        assertClose(gaps[1], g1, 0.51, "$where end gap")
        assertEquals(want["used_fallback"]?.jsonPrimitive?.booleanOrNull ?: false, got.usedFallback, "$where fallback")
        assertEquals(want["fallback_reason"]?.jsonPrimitive?.content == "no_route", got.noRouteFallback, "$where no-route fallback")
        assertEquals(want["candidates_considered"]?.jsonPrimitive?.int, got.candidatesConsidered, "$where pool size")
        val warning = got.warnings.filterIsInstance<RouteWarning.OptionFallback>().singleOrNull()?.userText
        assertEquals(want["fallback_warning"]?.jsonPrimitive?.content, warning, "$where warning")
        val tie = when (want["tie_check"]?.jsonPrimitive?.content) { "kept" -> TieCheckResult.KEPT; "untied" -> TieCheckResult.UNTIED; else -> TieCheckResult.NOT_ASKED }
        assertEquals(tie, got.tieCheck, "$where tie check")
        val side = when (want["arrival_side"]?.jsonPrimitive?.content) { "asked" -> ArrivalSideResult.ASKED_KEPT; "side" -> ArrivalSideResult.SIDE; else -> ArrivalSideResult.NOT_ASKED }
        assertEquals(side, got.arrivalSide, "$where arrival side")
        assertEquals(want["route_index"]?.jsonPrimitive?.int ?: 0, got.chosen.routeIndex, "$where route index")
        // batch E2: the end decided for the last stop and the route's last point
        want["end"]?.jsonPrimitive?.content?.let { assertEquals(it, got.ends.lastOrNull()?.end?.id, "$where end") }
        // item 35: whether the plan leaves the engine's first way into the lot out
        want["entry_closed"]?.jsonPrimitive?.content?.let { assertEquals(it.toBoolean(), got.ends.lastOrNull()?.entry?.isNotEmpty(), "$where entry_closed") }
        want["end_point"]?.jsonArray?.let { p ->
            val last = r.legs.last().shape.last()
            assertClose(p[0].jsonPrimitive.double, last.lat, 1.5e-6, "$where end point lat")
            assertClose(p[1].jsonPrimitive.double, last.lon, 1.5e-6, "$where end point lon")
        }
        return got
    }

    @Test
    fun `21 trips x 3 route types x 4 option sets pick the same route as the Python reference`() {
        var compared = 0
        val tripList = trips["trips"]!!.jsonArray.map { it.jsonObject }
        for (type in presets.routeTypes) for ((key, opts) in optionSets) {
            val preset = presets.compose(type.id, opts)
            val ref = reference["plans"]!!.jsonObject[type.id]!!.jsonObject[key]!!.jsonObject
            for (t in tripList) {
                val id = t["id"]!!.jsonPrimitive.content
                assertSame(ref[id]!!.jsonObject, planner.plan(RouteRequest(tripStops(t)), preset), "${preset.id} $id")
                compared++
            }
        }
        assertEquals(21 * 3 * 4, compared)
    }

    private fun case(id: String, run: String): Pair<List<Stop>, JsonObject> {
        val row = reference["cases"]!!.jsonObject[id]!!.jsonObject
        val stops = row["stops"]!!.jsonArray.map { s ->
            s.jsonObject.let {
                Stop(LatLon(it["lat"]!!.jsonPrimitive.double, it["lon"]!!.jsonPrimitive.double),
                    locationOptions = JsonObject(it.filterKeys { k -> k !in setOf("lat", "lon", "_street", "_tie", "_poi", "_door") }).takeIf { o -> o.isNotEmpty() },
                    street = it["_street"]?.jsonPrimitive?.content,
                    tied = it["_tie"]?.jsonPrimitive?.content != "none",
                    pointOfInterest = it["_poi"]?.jsonPrimitive?.booleanOrNull == true,
                    door = it["_door"]?.jsonObject?.let { d -> LatLon(d["lat"]!!.jsonPrimitive.double, d["lon"]!!.jsonPrimitive.double) })
            }
        }
        return stops to row[run]!!.jsonObject
    }

    private fun planCase(id: String, run: String): PlanResult.Ok {
        val (stops, want) = case(id, run)
        val (type, key) = run.split("/")
        return assertSame(want, planner.plan(RouteRequest(stops), presets.compose(type, optionSets.getValue(key))), "$id $run")
    }

    /** The C1 routing fix (docs/experiments/23-batch-c.md): the owner's address case, its fallback, a dropped pin. */
    @Test
    fun `a place is tied to its address street, falls back when it is out of reach, and a pin is not tied`() {
        fun lastStreet(r: PlanResult.Ok) = r.route.legs.last().maneuvers.last { it.streetNames.isNotEmpty() }.streetNames
        fun dest(r: PlanResult.Ok) = r.chosen.request["locations"]!!.jsonArray.last().jsonObject
        val fd = planCase("address_street", "fastest/none")
        assertTrue("East 12th Street" in lastStreet(fd), "Family Dollar ends on East 12th Street: ${lastStreet(fd)}")
        assertTrue("display_lat" in dest(fd), "the place is the display point (its side of the street)")
        assertTrue("East 12th Street" in lastStreet(planCase("address_street", "simpler/none")))
        val ulta = planCase("address_out_of_reach", "fastest/none")
        assertFalse("display_lat" in dest(ulta), "Interchange Road is out of reach: the place as it is")
        // batch H: the place itself, untied, is the last end on the list; the nearest street (586.6 s, 191 m away, a
        // U-turn) has one U-turn more than the place (621.9 s, 32 m, as 0.1.6's untied route), so the place
        // (batch E2 kept the nearest street with no check; as a point of interest Ulta Beauty ends in its lot)
        assertEquals(TieCheckResult.UNTIED, ulta.tieCheck, "the nearest street lost to the place")
        assertEquals(StopEnd.PLACE, ulta.ends.last().end)
        assertFalse("search_filter" in dest(ulta), "the place itself: no stop tie")
        assertEquals(0, ulta.route.legs.sumOf { l -> l.maneuvers.count { it.type in setOf(ManeuverType.UTURN_LEFT, ManeuverType.UTURN_RIGHT) } }, "no U-turn")
        val pin = planCase("dropped_pin", "fastest/none")
        assertFalse("search_filter" in dest(pin), "a dropped pin is not tied")
        assertEquals(TieCheckResult.NOT_ASKED, pin.tieCheck)
        assertEquals(5, pin.chosen.request["costing_options"]!!.jsonObject["auto"]!!.jsonObject["alley_factor"]!!.jsonPrimitive.int, "alleys still avoided")
    }

    /** Batch E (docs/next-round.md item 18): a point of interest ends in its own lot; the street when there is none or the lot is much longer. */
    @Test
    fun `a point of interest ends in its own lot, else on its street as before`() {
        fun dest(r: PlanResult.Ok) = r.chosen.request["locations"]!!.jsonArray.last().jsonObject
        fun inLot(r: PlanResult.Ok) = dest(r)["preferred_side"]?.jsonPrimitive?.content == "either" && "search_filter" !in dest(r)
        fun lastNamed(r: PlanResult.Ok) = r.route.legs.last().maneuvers.last { it.streetNames.isNotEmpty() }.streetNames
        for (run in listOf("fastest/none", "simpler/none")) {
            val r = planCase("lot_restaurant", run)
            assertTrue(inLot(r), "$run: the restaurant's route ends in its lot")
            assertTrue("display_lat" in dest(r), "the place is the display point")
            assertTrue("Mandy Lane" in lastNamed(r), "$run: entered from the side street behind it: ${lastNamed(r)}")
            assertEquals(ArrivalSideResult.NOT_ASKED, r.arrivalSide, "no side request in a lot")
        }
        val plaza = planCase("lot_plaza", "fastest/none")
        assertTrue(inLot(plaza), "a strip plaza store ends in the plaza lot")
        assertTrue("Scott Drive" in lastNamed(plaza), "entered from Scott Drive: ${lastNamed(plaza)}")
        val fd = planCase("lot_alley_case", "fastest/none")
        assertTrue(inLot(fd) && "East 12th Street" in lastNamed(fd), "Family Dollar: East 12th Street, then its lot")
        val dominos = planCase("lot_none_downtown", "fastest/none")
        assertFalse(inLot(dominos), "no lot nearer than its street")
        assertTrue("search_filter" in dest(dominos) && "Turnpike Street" in lastNamed(dominos), "its address street as before: ${lastNamed(dominos)}")
        assertTrue(inLot(planCase("lot_alley_case", "simpler/none")), "Simpler: the same lot")
        val back = planCase("lot_back_to_street", "fastest/none")
        assertEquals(TieCheckResult.UNTIED, back.tieCheck, "the lot route is more than 90 s longer: the street end")
        assertFalse(inLot(back))
        assertTrue("Interchange Road" in lastNamed(back), "its address street: ${lastNamed(back)}")
    }

    @Test
    fun `every case of 2_11 matches the reference`() {
        var compared = 0
        for ((id, row) in reference["cases"]!!.jsonObject) for (run in row.jsonObject.keys - setOf("stops", "note")) {
            planCase(id, run)
            compared++
        }
        assertEquals(48, compared, "8 cases of 2.11, 4 of the C1 routing fix, 4 of batch D's Simpler, 3 of its arrival side, 8 of batch E's lots, 7 of item 28's front doors, "
            + "2 of item 35's other way into a lot, 12 of batch E2's one end point")
    }

    /** Batch D (docs/next-round.md item 16): the arrival side without loops. */
    @Test
    fun `the arrival side - none on a residential street, one block on a busier one, never a longer loop`() {
        fun end(r: PlanResult.Ok) = r.route.legs.last().maneuvers.last().type
        val quiet = planCase("side_residential", "fastest/none")
        assertEquals(ManeuverType.DESTINATION_LEFT, end(quiet), "residential: the plain route, on the left")
        assertEquals("East 20th Street", quiet.route.legs.last().maneuvers.last { it.streetNames.isNotEmpty() }.streetNames.first())
        val block = planCase("side_one_block", "fastest/none")
        assertEquals(ArrivalSideResult.SIDE, block.arrivalSide)
        assertEquals(ManeuverType.DESTINATION_RIGHT, end(block), "secondary: one block more, on the right")
        val loop = planCase("side_long_loop", "fastest/none")
        assertEquals(ArrivalSideResult.ASKED_KEPT, loop.arrivalSide, "+4 instructions: not one block")
        assertEquals(ManeuverType.DESTINATION_LEFT, end(loop))
    }

    @Test
    fun `Avoid tolls drives no toll road - Carlisle to King of Prussia`() {
        // review finding F2 (found with Shortest, which left in batch D): exclude_tolls for every type
        val withTolls = planCase("carlisle_kop", "fastest/none")
        val avoid = planCase("carlisle_kop", "fastest/tolls")
        assertTrue(withTolls.route.hasToll)
        assertFalse(avoid.route.hasToll, "has_toll false")
        assertEquals(0.0, avoid.route.flaggedM(RoadFlag.TOLL))
        assertFalse(avoid.usedFallback)
        for (type in listOf("fastest", "simpler", "eco")) {
            val t2 = reference["plans"]!!.jsonObject[type]!!.jsonObject["tolls"]!!.jsonObject["T2"]!!.jsonObject
            assertEquals(false, t2["has_toll"]!!.jsonPrimitive.booleanOrNull, "$type T2 (Carlisle to King of Prussia)")
        }
    }

    /** Batch D (docs/next-round.md item 13): Simpler's window and its major-road candidate in the Erie region. */
    @Test
    fun `Simpler keeps exit 99 and US 422 to Butler and finds it near the owner's case`() {
        fun roads(r: PlanResult.Ok) = r.route.maneuvers.flatMap { it.streetNames }.toSet()
        val fastButler = planCase("erie_butler", "fastest/none")
        val butler = planCase("erie_butler", "simpler/none")
        for (r in listOf(fastButler, butler)) assertTrue("US 422" in roads(r) && "PA 258" !in roads(r), "${roads(r)}")
        assertTrue(butler.route.turnCount < fastButler.route.turnCount)
        val fastCenter = planCase("erie_center_township", "fastest/none")
        val center = planCase("erie_center_township", "simpler/none")
        assertTrue("PA 258" in roads(fastCenter) && "US 422" !in roads(fastCenter), "Fastest leaves I-79 at exit 113: ${roads(fastCenter)}")
        assertTrue("US 422" in roads(center) && "PA 258" !in roads(center), "Simpler: exit 99 and US 422: ${roads(center)}")
        assertTrue(center.route.turnCount < fastCenter.route.turnCount)
        assertTrue(center.route.timeS <= fastCenter.route.timeS + 600.0 && center.route.timeS <= fastCenter.route.timeS * 1.10)
        assertEquals(6, center.candidatesConsidered, "4 candidates, the first with 2 alternates")
    }

    /** Item 28 (docs/next-round.md): a large store's route ends at the lane by its door, not behind the building. */
    @Test
    fun `a large store ends at the lane by its front door`() {
        fun lastPoint(r: PlanResult.Ok) = r.route.legs.last().shape.last()
        fun door(id: String) = case(id, "fastest/none").first.last().door!!
        for (id in listOf("door_parking", "door_entrance", "door_walk")) {
            val fast = planCase(id, "fastest/none")
            val simple = planCase(id, "simpler/none")
            assertEquals(StopEnd.LOT, fast.ends.last().end, id)
            assertTrue(Geo.distanceM(lastPoint(fast), door(id)) < 20.0, "$id: ${Geo.distanceM(lastPoint(fast), door(id))} m from the door")
            assertEquals(lastPoint(fast), lastPoint(simple), "$id: both route types end at the same point")
            assertEquals(null, fast.ends.last().door, "$id: a decided stop is sent as it is")
        }
        // the lot route to the third store is more than max_extra_s slower than its street end; the walk saved allows it
        val walk = planCase("door_walk", "fastest/none")
        val rule = presets.everyRequest.tieCheck!!
        assertEquals(1.4, rule.doorWalkMPerS)
        assertEquals(TieCheckResult.KEPT, walk.tieCheck)
        // without the door (an older pack): as before, the lane nearest to the middle of the roof, behind the building
        val (stops, _) = case("door_parking", "fastest/none")
        val old = planCase("door_none", "fastest/none")
        assertEquals(StopEnd.LOT, old.ends.last().end)
        assertTrue(Geo.distanceM(lastPoint(old), stops.last().door!!) > 80.0, "${Geo.distanceM(lastPoint(old), stops.last().door!!)} m")
    }

    /** Batch E2 (docs/next-round.md item 19): the end of each stop is decided once and every route type ends there. */
    @Test
    fun `every route type ends at the same point, decided once with Fastest`() {
        fun dest(r: PlanResult.Ok) = r.chosen.request["locations"]!!.jsonArray.last().jsonObject
        fun lastPoint(r: PlanResult.Ok) = r.route.legs.last().shape.last()
        fun lastNamed(r: PlanResult.Ok) = r.route.legs.last().maneuvers.last { it.streetNames.isNotEmpty() }.streetNames
        for ((id, end) in listOf("end_same_west" to StopEnd.LOT, "end_same_north" to StopEnd.LOT, "end_same_south" to StopEnd.LOT,
            "car_wash_street" to StopEnd.STREET, "drive_through_lot" to StopEnd.LOT, "lot_alley_case" to StopEnd.LOT,
            "lot_none_downtown" to StopEnd.STREET, "lot_restaurant" to StopEnd.LOT, "address_street" to StopEnd.STREET)) {
            val fast = planCase(id, "fastest/none")
            val simple = planCase(id, "simpler/none")
            assertEquals(end, fast.ends.last().end, id)
            assertEquals(dest(fast), dest(simple), "$id: the same location for both types")
            assertEquals(lastPoint(fast), lastPoint(simple), "$id: both routes end at the same point")
            // the session plans the second type with the first's decided stops: the same route, nothing asked again
            val again = planner.plan(RouteRequest(fast.ends), presets.compose("simpler", emptySet())) as PlanResult.Ok
            assertEquals(simple.route.timeS, again.route.timeS, 0.001, "$id: Simpler to the decided ends")
            assertEquals(TieCheckResult.NOT_ASKED, again.tieCheck)
            assertEquals(ArrivalSideResult.NOT_ASKED, again.arrivalSide)
        }
        // 0.1.6's Simpler ended on Peach Street from the west (its own way into the lot was > 90 s slower than its own street end)
        val west = planCase("end_same_west", "simpler/none")
        assertTrue(dest(west)["preferred_side"]?.jsonPrimitive?.content == "either" && "search_filter" !in dest(west), "Simpler ends in the lot")
        // the car wash: its lot is reached only through its wash lane, so the street at the entrance
        val wash = planCase("car_wash_street", "fastest/none")
        assertEquals(TieCheckResult.UNTIED, wash.tieCheck)
        assertTrue("West 12th Street" in lastNamed(wash), "${lastNamed(wash)}")
        // the drive-through restaurant: in its lot, its lanes left out of every request
        val mc = planCase("drive_through_lot", "simpler/none")
        assertTrue((mc.chosen.request["exclude_polygons"]?.jsonArray?.size ?: 0) > 0, "the lanes left out")
        // with an option the decision is Fastest with that option
        assertEquals(dest(planCase("end_same_avoid", "fastest/tolls")), dest(planCase("end_same_avoid", "simpler/tolls")))
    }

    /** Batch E2: a re-route keeps the end the plan decided (the session sends the plan's [PlanResult.Ok.ends]). */
    @Test
    fun `a re-route from elsewhere keeps the decided end - no probe, no check`() {
        val north = planCase("end_same_north", "fastest/none")
        val (westStops, _) = case("end_same_west", "fastest/none")
        val reroute = planner.plan(RouteRequest(listOf(westStops.first()) + north.ends.drop(1)), presets.compose("fastest", emptySet())) as PlanResult.Ok
        // the route, and since item 35 one look at the way into the lot it ends in (decided anew by every plan); the
        // end itself is not probed or checked again
        assertEquals(2, reroute.engineCalls, "one route request and one look at the way in: the end is not probed or checked again")
        assertEquals(north.chosen.request["locations"]!!.jsonArray.last(), reroute.chosen.request["locations"]!!.jsonArray.last())
        assertEquals(north.route.legs.last().shape.last(), reroute.route.legs.last().shape.last(), "the same end point")
        assertTrue(reroute.ends.last().fixed)
        // item 35: a way in closed for the plan before is open again for this one (decided anew by every plan):
        // the same requests are asked as without it, and the stop comes back without the ring
        val ring = listOf(LatLon(40.0, -77.0), LatLon(40.0, -76.9999), LatLon(40.0001, -76.9999), LatLon(40.0001, -77.0), LatLon(40.0, -77.0))
        val stale = planner.plan(RouteRequest(listOf(westStops.first()) + north.ends.drop(1).map { it.copy(entry = listOf(ring)) }),
            presets.compose("fastest", emptySet())) as PlanResult.Ok
        assertTrue(stale.ends.last().entry.isEmpty(), "the ring of the plan before is dropped")
        assertEquals(reroute.chosen.request, stale.chosen.request, "and nothing is left out because of it: the very same request")
        assertEquals(reroute.route.timeS, stale.route.timeS)
    }

    @Test
    fun `a destination on the Turnpike with Avoid tolls gives a route with the warning, not no route`() {
        for (run in listOf("fastest/tolls")) {
            val r = planCase("carlisle_turnpike", run)
            assertTrue(r.usedFallback && r.noRouteFallback, run)
            assertTrue(r.route.hasToll, "$run: the place can only be reached on the toll road")
            assertEquals("No route to this place avoids toll roads. This route uses them.",
                r.warnings.filterIsInstance<RouteWarning.OptionFallback>().single().userText)
        }
        assertEquals(141.5, planCase("carlisle_turnpike", "fastest/tolls").route.lengthM / 1000, 0.05)
        assertEquals(126.3, planCase("carlisle_turnpike", "fastest/none").route.lengthM / 1000, 0.05)
    }

    @Test
    fun `a destination on I-90 with Avoid highways gives a route with the warning`() {
        for (run in listOf("fastest/highways")) {
            val r = planCase("erie_i90", run)
            assertTrue(r.noRouteFallback, run)
            assertEquals("No route to this place avoids highways. This route uses them.",
                r.warnings.filterIsInstance<RouteWarning.OptionFallback>().single().userText, run)
        }
    }

    @Test
    fun `a trip that starts on the Turnpike may stay on it with Avoid tolls`() {
        val plain = planCase("turnpike_start_kop", "fastest/none")
        val avoid = planCase("turnpike_start_kop", "fastest/tolls")
        assertEquals(plain.route.lengthM, avoid.route.lengthM, 1.0)
        assertTrue(avoid.route.hasToll)
        assertTrue(avoid.warnings.none { it is RouteWarning.OptionFallback }, "no fallback ran: no warning")
        assertEquals(93.9, avoid.route.lengthM / 1000, 0.05)
    }

    private fun assertClose(want: Double, got: Double, tol: Double, what: String) =
        assertTrue(abs(want - got) <= tol, "$what: want $want, got $got")
}
