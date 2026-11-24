package io.github.spysyweeb.beamline.routing.planner

import io.github.spysyweeb.beamline.routing.engine.EngineException
import io.github.spysyweeb.beamline.routing.engine.RoutingEngine
import io.github.spysyweeb.beamline.routing.model.Geo
import io.github.spysyweeb.beamline.routing.model.LatLon
import io.github.spysyweeb.beamline.routing.model.Route
import io.github.spysyweeb.beamline.routing.model.RouteRequest
import io.github.spysyweeb.beamline.routing.model.Stop
import io.github.spysyweeb.beamline.routing.model.StopEnd
import io.github.spysyweeb.beamline.routing.preset.Preset
import io.github.spysyweeb.beamline.routing.preset.PresetSet
import io.github.spysyweeb.beamline.routing.preset.SelectRule
import io.github.spysyweeb.beamline.routing.valhalla.RouteResponse
import io.github.spysyweeb.beamline.routing.valhalla.ValhallaRequests
import io.github.spysyweeb.beamline.routing.valhalla.ValhallaResponses
import io.github.spysyweeb.beamline.routing.valhalla.canonicalJson
import io.github.spysyweeb.beamline.routing.model.ManeuverType
import io.github.spysyweeb.beamline.routing.preset.EveryRequest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.intOrNull
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.hypot

/**
 * The request that produced the chosen route, and which route of its answer it is (0 = the main
 * route, n = alternate n). Asking the engine again with this request in another format
 * ([ValhallaRequests.osrm]) gives the same path, e.g. for Ferrostar.
 */
data class ChosenRoute(val request: JsonObject, val routeIndex: Int)

/** The arrival side rule's outcome (asked once per plan, when the ends are decided): not asked (no stop on the left), asked and the first route kept, the side route. */
enum class ArrivalSideResult { NOT_ASKED, ASKED_KEPT, SIDE }

/**
 * The ends' check (every_request.tie_check; batch E2: once per plan, when the ends are decided): not asked
 * (no stop had a choice, or every stop was decided before), every stop kept its first end, a stop took a
 * later end (its lot failed, so its street; or its address street failed, so the nearest street).
 */
enum class TieCheckResult { NOT_ASKED, KEPT, UNTIED }

/**
 * Address-street and lot tie points already found ([AddressTie]), so a re-route to the same place
 * does not probe again. One per route service; at most [max] places.
 */
class AddressTies(private val max: Int = 32) {
    /** What one probe found for a place: the point on its address street, the point in its lot (batch E), the drive-through lanes near it (batch E2). */
    internal data class Found(val street: LatLon?, val lot: LatLon?, val rings: List<List<LatLon>> = emptyList())

    private data class Key(val lat: Double, val lon: Double, val street: String, val poi: Boolean, val door: LatLon?)

    private val found = object : LinkedHashMap<Key, Found>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Key, Found>?) = size > max
    }

    @Synchronized
    internal fun get(stop: Stop, find: () -> Found): Found {
        val key = Key(stop.point.lat, stop.point.lon, stop.street.orEmpty(), stop.pointOfInterest, stop.door)
        found[key]?.let { return it }
        return find().also { found[key] = it }
    }
}

sealed interface PlanResult {
    data class Ok(
        val route: Route,
        val alternates: List<Route>,
        val chosen: ChosenRoute,
        /** Far-snapped stops, and the option fallback's warning ([RouteWarning.OptionFallback]). */
        val warnings: List<RouteWarning>,
        /** True when the fallback options produced the route (no route with the options, or a fallback_when limit). */
        val usedFallback: Boolean,
        /** Size of the candidate pool for pool presets (Simpler), else null. */
        val candidatesConsidered: Int?,
        /** Engine calls this plan made (alternates and pools cost extra calls). */
        val engineCalls: Int,
        /** True when the fallback ran because the request with the options found no route (2.11 rule 2). */
        val noRouteFallback: Boolean = false,
        /** What the arrival side rule did (presets.json every_request.arrival_side). */
        val arrivalSide: ArrivalSideResult = ArrivalSideResult.NOT_ASKED,
        /** What the ends' check did (presets.json every_request.tie_check). */
        val tieCheck: TieCheckResult = TieCheckResult.NOT_ASKED,
        /**
         * The stops as planned, the origin first: each later stop decided ([Stop.fixed], [Stop.end]; batch
         * E2). Plan another route type or re-route with these and every route ends at the same points.
         */
        val ends: List<Stop> = emptyList(),
    ) : PlanResult

    data class Failed(val problem: RoutingProblem, val engineCalls: Int) : PlanResult
}

/**
 * Plans a route with a preset ([PresetSet.compose]: a route type with its options), with the same
 * rules as packbuilder/routing/route_lib.py `run_preset` (the reference; tests compare the two on
 * 21 trips x 4 types x 4 option sets, the cases of docs/architecture.md 2.11 and the old 126 plans
 * of format 1):
 *  - a plain preset is one request; with `fallback_costing_options` it is asked again when the
 *    first finds no route (a stop on an excluded road: the route then carries
 *    [RouteWarning.OptionFallback]), or when a `fallback_when` condition holds (Avoid highways
 *    keeps more than 0.5 motorway km: Valhalla's exclusion only forbids driving onto a motorway),
 *    and the route with less of that road wins;
 *  - a pool preset (Simpler) asks once per candidate, the first with alternates, keeps routes
 *    within `max_time_increase` of the quickest and at most `max_time_extra_s` over it ([poolLimitS]),
 *    and picks the fewest maneuvers (or shortest); with
 *    options the whole pool runs again with the fallback options when every candidate failed or the
 *    picked route exceeds a `fallback_when` limit, and the picked route with less of the flagged
 *    road wins (ties: the first).
 * First the end of each stop is decided once ([decide], batch E2, docs/next-round.md item 19) with the
 * plan's Fastest request ([PresetSet.decisionPreset]); the type's requests then go to those ends, and
 * [PlanResult.Ok.ends] gives them back so that the other route types and a re-route use the same ones.
 * Alternates for two-stop trips come from one more request with `alternates`, with the options
 * that produced the chosen route (shared with the pool's first request when it is the same), and
 * obey the preset's `fallback_when` limit (see [alternatesFor]). The app does not offer alternates
 * this round (decision 6.6); the rule is kept as it was.
 */
class RoutePlanner(
    private val engine: RoutingEngine,
    private val presets: PresetSet = PresetSet.bundled(),
    /** Installed map tiles, to say which stop is outside them (null: not known). */
    private val coverage: TileCoverage? = null,
    /** Address-street tie points found before (keep one per route service; a new one probes again). */
    private val ties: AddressTies = AddressTies(),
) {
    /** Plans with the preset [RouteRequest.presetId] names ([PresetSet.resolve]: "fastest", "fastest+avoid_tolls"). */
    fun plan(request: RouteRequest): PlanResult = plan(request, presets.resolve(request.presetId))

    /** Plans [request]'s stops with [preset] ([RouteRequest.presetId] is not read). */
    fun plan(request: RouteRequest, preset: Preset): PlanResult {
        val calls = Calls(engine, presets.everyRequest)
        if (request.stops.size < 2) return PlanResult.Failed(RoutingProblem.NeedTwoStops, 0)
        val decided = decide(calls, request.stops, presets.decisionPreset(preset))
        // item 35: the way into each stop's lot, decided by every plan (also for stops whose end is fixed)
        val stops = decideEntrances(calls, decided.stops, presets.decisionPreset(preset))
        val planned = runType(calls, stops, preset)
        if (planned is Outcome.Error) {
            return PlanResult.Failed(RoutingProblem.from(planned.e, request.stops.map { it.point }, coverage), calls.count)
        }
        planned as Outcome.Picked
        val alternates = if (request.withAlternates && stops.size == 2) alternatesFor(calls, stops, preset, planned) else emptyList()
        val optionWarning = if (planned.noRouteFallback && preset.avoids.isNotEmpty()) {
            listOf(RouteWarning.OptionFallback(preset.avoids, preset.avoids.filter { planned.route.flaggedM(it) > 0.0 }.toSet()))
        } else emptyList()
        return PlanResult.Ok(
            route = planned.route,
            alternates = alternates,
            chosen = planned.chosen,
            warnings = farSnapWarnings(planned.route) + optionWarning,
            usedFallback = planned.usedFallback,
            candidatesConsidered = planned.candidates,
            engineCalls = calls.count,
            noRouteFallback = planned.noRouteFallback,
            arrivalSide = decided.side,
            tieCheck = decided.tie,
            ends = stops,
        )
    }

    private fun runType(calls: Calls, stops: List<Stop>, preset: Preset): Outcome =
        if (preset.isPool) runPoolPreset(calls, stops, preset) else runPlain(calls, stops, preset)

    /**
     * route_lib.stop_ends (batch E2): the possible ends of a stop after the first, best first, each marked
     * with its [Stop.end]:
     * - a point of interest ([Stop.pointOfInterest]): its own lot when [AddressTie.lotPoint] finds one (the
     *   place as [Stop.displayPoint], no stop tie ([Stop.tied] false: rule 2 would take it off the lot),
     *   preferred_side [EveryRequest.LOT_SIDE]), then its address street, then the nearest street; the lot
     *   is looked for around its door when it has one ([Stop.door]: the small probe and every distance of
     *   [AddressTie.lotPoint] are the door's), else around the place;
     * - a place that names its address street ([Stop.street]): the point on that street (the place as
     *   [Stop.displayPoint]), then the nearest street;
     * - any other tied place: the nearest street (the place as it is, rule 2's tie);
     * - batch H (docs/next-round.md, the owner's answers after batch G): every list above ends with the place
     *   itself, untied ([StopEnd.PLACE]: its own point, [Stop.tied] false), so the end before it has to pass
     *   the check against it ([decide]);
     * - a map point (not [Stop.tied]) or a stop decided before ([Stop.fixed]): exactly as it is.
     * The probes ([AddressTie]: the address probe, with edge.use for a point of interest, which also gets
     * the small lot probe near it; none when [ties] knows the place; a failed call finds nothing) also give
     * a point of interest's drive-through lanes and alleys ([AddressTie.leftOutRings]), kept on each of its ends
     * ([Stop.avoid]).
     */
    private fun ends(calls: Calls, s: Stop): List<Stop> {
        if (s.fixed) return listOf(s)
        val plain = s.copy(street = null, pointOfInterest = false, door = null)
        if (!s.tied) return listOf(plain.copy(end = StopEnd.PIN))
        val near = plain.copy(end = StopEnd.NEAR)
        val place = plain.copy(tied = false, end = StopEnd.PLACE)
        val every = presets.everyRequest
        val rule = every.addressStreet ?: return listOf(near, place)
        val poi = s.pointOfInterest && every.lot != null
        if (s.street.isNullOrBlank() && !poi) return listOf(near, place)
        val found = ties.get(s) {
            val resp = calls.traceAttributes(AddressTie.request(s.point.lat, s.point.lon, rule, use = poi))
            val street = s.street?.takeIf { it.isNotBlank() }?.let { st ->
                resp?.let { AddressTie.point(it, s.point.lat, s.point.lon, st, rule, every.tieClasses) }
            }
            if (!poi) AddressTies.Found(street, null) else {
                val door = s.door ?: s.point
                val small = calls.traceAttributes(AddressTie.lotRequest(door.lat, door.lon, rule, every.lot!!))
                val resps = listOfNotNull(resp, small)
                val lot = AddressTie.lotPoint(resps, door.lat, door.lon, every.lot, rule.endMarginM, every.tieClasses, door = s.door != null)
                val rings = every.leftOut?.let { AddressTie.leftOutRings(resps, s.point.lat, s.point.lon, it) } ?: emptyList()
                AddressTies.Found(street, lot, rings)
            }
        }
        val out = mutableListOf<Stop>()
        found.lot?.let {
            out += plain.copy(point = it, displayPoint = s.point, tied = false, end = StopEnd.LOT,
                locationOptions = JsonObject((plain.locationOptions ?: JsonObject(emptyMap())) + ("preferred_side" to JsonPrimitive(EveryRequest.LOT_SIDE))))
        }
        found.street?.let { out += plain.copy(point = it, displayPoint = s.point, end = StopEnd.STREET) }
        out += near
        out += place
        return if (found.rings.isEmpty()) out else out.map { it.copy(avoid = found.rings) }
    }

    /** The decided stops of a plan and what the checks did. */
    private class Decided(val stops: List<Stop>, val tie: TieCheckResult, val side: ArrivalSideResult)

    /**
     * route_lib.decide_ends (batch E2, docs/next-round.md item 19; presets.json every_request.tie_check): the
     * end of each stop not yet [Stop.fixed], decided once per plan with [preset] (the plan's Fastest with the
     * driver's options). Stop by stop (the others at their end so far), its ends ([ends]) are tried best
     * first; an end is kept when its route finds a way, is at most max_extra_s slower than the route to the
     * next end on the list with no more U-turns (when that one finds a way), and its last tail_m metres use
     * none of never_uses (an alley, a drive-through lane: one trace_attributes call, asked only for the end
     * about to be kept; a failed call does not count against it). The last end is kept when none before it
     * is. Item 28 (front doors): a lot found around the place's door ([Stop.door]) may be slower by max_extra_s
     * plus the walk it saves ([walkSavedS]). Batch H: the last end is the place itself, untied ([ends]); it never wins with an alley or a
     * drive-through lane in its last tail_m metres (C1: no alleys unless there is no other way): such a place
     * route does not count against the end before it (the nearest street), and when the place end would be
     * kept anyway the nearest street is kept instead when its route finds a way. Its tail is asked only when
     * it would win (at most one call per stop). Then the arrival side rule ([arrivalSide]) once, on the route to the chosen ends, for the stops
     * decided here; a side route kept puts its side options on those stops. Every stop comes back [Stop.fixed].
     */
    private fun decide(calls: Calls, stops: List<Stop>, preset: Preset): Decided {
        val todo = (1 until stops.size).filter { !stops[it].fixed }
        if (todo.isEmpty()) return Decided(stops, TieCheckResult.NOT_ASKED, ArrivalSideResult.NOT_ASKED)
        val ends = todo.associateWith { ends(calls, stops[it]) }
        val chosen = stops.indices.map { ends[it]?.first() ?: stops[it] }.toMutableList()
        val rule = presets.everyRequest.tieCheck
        fun run(i: Int, end: Stop): Outcome.Picked? = runType(calls, chosen.toMutableList().also { it[i] = end }, preset) as? Outcome.Picked
        var asked = false
        var moved = false
        for (i in todo) {
            val list = ends.getValue(i)
            if (list.size == 1 || rule == null) continue
            asked = true
            val last = list.size - 1
            var placeBad: Boolean? = null // batch H: true once the place end's way in is known to use a lane or an alley
            fun badPlace(): Boolean = placeBad ?: (list[last].end == StopEnd.PLACE &&
                run(i, list[last])?.let { tailNever(calls, it.route, i - 1, rule) } == true).also { placeBad = it }
            var pick = list.last()
            val door = stops[i].door
            for (k in 0 until last) {
                val here = run(i, list[k]) ?: continue
                val next = run(i, list[k + 1])
                val allowed = rule.maxExtraS + if (door != null && list[k].end == StopEnd.LOT && next != null && rule.doorWalkMPerS != null) {
                    walkSavedS(here.route, next.route, i - 1, door, rule.doorWalkMPerS)
                } else 0.0
                if (next != null && (here.route.timeS > next.route.timeS + allowed || uturns(here.route) > uturns(next.route)) &&
                    !(k + 1 == last && badPlace())) continue
                if (tailNever(calls, here.route, i - 1, rule)) continue
                pick = list[k]
                break
            }
            if (pick === list.last() && last > 0 && badPlace() && run(i, list[last - 1]) != null) pick = list[last - 1]
            chosen[i] = pick
            if (pick !== list.first()) moved = true
        }
        val final = runType(calls, chosen, preset) as? Outcome.Picked
        val sided = final?.let { arrivalSide(calls, it, todo.toSet()) }
        val out = chosen.toMutableList()
        for (i in todo) {
            var s = chosen[i]
            if (sided?.side == ArrivalSideResult.SIDE) {
                val rl = sided.chosen.request["locations"]?.jsonArray?.getOrNull(i)?.jsonObject
                val add = listOf("preferred_side", "street_side_cutoff").filter { rl != null && it in rl && s.locationOptions?.containsKey(it) != true }
                if (add.isNotEmpty()) s = s.copy(locationOptions = JsonObject((s.locationOptions ?: JsonObject(emptyMap())) + add.associateWith { rl!![it]!! }))
            }
            out[i] = s.copy(fixed = true)
        }
        val tie = if (!asked) TieCheckResult.NOT_ASKED else if (moved) TieCheckResult.UNTIED else TieCheckResult.KEPT
        return Decided(out, tie, sided?.side ?: ArrivalSideResult.NOT_ASKED)
    }

    /**
     * route_lib._walk_saved_s (item 28): the walking time the end of [here] saves over the end of [next]: how much
     * nearer to the place's [door] leg [leg] of [here] ends, at [speed] m/s; 0 when it is not nearer.
     */
    private fun walkSavedS(here: Route, next: Route, leg: Int, door: LatLon, speed: Double): Double {
        fun toDoor(r: Route): Double? = r.legs.getOrNull(leg)?.shape?.lastOrNull()?.let { Geo.distanceM(it, door) }
        val a = toDoor(here) ?: return 0.0
        val b = toDoor(next) ?: return 0.0
        return maxOf(0.0, b - a) / speed
    }

    /**
     * route_lib.decide_entrances (docs/next-round.md item 35; presets.json every_request.entrance): the way
     * into each stop's lot. The engine picks a way in by its own cost, which counts every small junction of an
     * entrance; two ways into one lot can be near ties there while one is clearly faster by the clock. So,
     * stop by stop, for a stop that ends in a lot: the plan's route is asked ([preset]: the plan's Fastest),
     * the place where it leaves the road is closed ([entranceRing]) and the route is asked again. The other
     * way in is taken ([Stop.entry]: every route of the plan leaves the first one out) when it is at least
     * min_gain_s faster, costs at most within_cost times as much and has no more U-turns. A ring of an
     * earlier plan is dropped first: a driver who missed the turn is not sent back to it.
     */
    private fun decideEntrances(calls: Calls, stops: List<Stop>, preset: Preset): List<Stop> {
        var out = stops.map { if (it.entry.isEmpty()) it else it.copy(entry = emptyList()) }
        val rule = presets.everyRequest.entrance ?: return out
        for (i in 1 until out.size) {
            if (out[i].end != StopEnd.LOT) continue
            val first = runType(calls, out, preset) as? Outcome.Picked ?: break
            val ring = entranceRing(calls, first.route, i - 1, rule) ?: continue
            val trial = out.toMutableList().also { it[i] = it[i].copy(entry = listOf(ring)) }
            val other = runType(calls, trial, preset) as? Outcome.Picked ?: continue
            // the review of 2026-11-21: a request that found no route was asked again without any exclusion
            // ([Calls.route]); that answer is not another way in. Nor is a route that ends somewhere else.
            if ("exclude_polygons" !in other.chosen.request) continue
            val end1 = first.route.legs.getOrNull(i - 1)?.shape?.lastOrNull() ?: continue
            val end2 = other.route.legs.getOrNull(i - 1)?.shape?.lastOrNull() ?: continue
            if (Geo.distanceM(end1, end2) > rule.sameEndM) continue
            val c1 = first.route.cost ?: continue
            val c2 = other.route.cost ?: continue
            if (other.route.timeS <= first.route.timeS - rule.minGainS && c2 <= c1 * rule.withinCost && uturns(other.route) <= uturns(first.route)) out = trial
        }
        return out
    }

    /**
     * route_lib.entrance_ring: where leg [leg] of [route] leaves the public roads for the lot it ends in, as one
     * exclude_polygons ring: a square of 2 x box_half_m, in whole micro-degrees, into_m along the way the route
     * takes inside the lot. Null when the leg does not end in a lot, lies in one for all of its last tail_m,
     * has too short a way inside, or the engine cannot walk the route's own edges (no second call).
     */
    private fun entranceRing(calls: Calls, route: Route, leg: Int, rule: EveryRequest.Entrance): List<LatLon>? {
        val shape = route.legs.getOrNull(leg)?.shape?.takeIf { it.size >= 2 } ?: return null
        val resp = calls.traceAttributes(entranceRequest(shape, rule)) ?: return null
        val edges = (resp["edges"] as? JsonArray)?.map { it as? JsonObject ?: return null } ?: return null
        val pts = Geo.decodePolyline6((resp["shape"] as? JsonPrimitive)?.contentOrNull ?: "")
        var k = edges.size
        while (k > 0 && (edges[k - 1]["use"] as? JsonPrimitive)?.contentOrNull in rule.lotUses) k--
        if (k == 0 || k == edges.size) return null
        val i0 = (edges[k]["begin_shape_index"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: return null
        val i1 = (edges.last()["end_shape_index"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull ?: return null
        // Python's slice pts[i0:i1 + 1]
        val from = if (i0 < 0) maxOf(0, pts.size + i0) else minOf(i0, pts.size)
        val to = (i1 + 1).let { e -> if (e < 0) maxOf(0, pts.size + e) else minOf(e, pts.size) }
        val path = if (to > from) pts.subList(from, to) else emptyList()
        if (path.size < 2) return null
        val lat = path[0].lat
        val lon = path[0].lon
        val kx = 111320.0 * cos(Math.toRadians(lat))
        val ky = 111320.0
        val xy = path.map { q -> (q.lon - lon) * kx to (q.lat - lat) * ky }
        val seg = (1 until xy.size).map { n -> hypot(xy[n].first - xy[n - 1].first, xy[n].second - xy[n - 1].second) }
        val total = seg.sum()
        if (total < rule.intoM + rule.boxHalfM) return null
        // route_lib._point_inside(xy, seg, into_m, 0, lat, lon): the point into_m along the way inside
        var at = maxOf(0.0, minOf(total, rule.intoM.toDouble()))
        var (x, y) = xy.last()
        for (n in seg.indices) {
            val l = seg[n]
            if (at <= l) {
                val f = if (l == 0.0) 0.0 else at / l
                x = xy[n].first + f * (xy[n + 1].first - xy[n].first)
                y = xy[n].second + f * (xy[n + 1].second - xy[n].second)
                break
            }
            at -= l
        }
        val midLat = floor((lat + y / ky) * 1e6 + 0.5) / 1e6
        val midLon = floor((lon + x / kx) * 1e6 + 0.5) / 1e6
        val cu = floor(midLat * 1e6 + 0.5).toLong()
        val lu = floor(midLon * 1e6 + 0.5).toLong()
        val dLat = floor(rule.boxHalfM / 111320.0 * 1e6 + 0.5).toLong()
        val dLon = floor(dLat / cos(Math.toRadians(Math.floorDiv(cu, 10000L) / 100.0)) + 0.5).toLong()
        return listOf(-1 to -1, 1 to -1, 1 to 1, -1 to 1, -1 to -1).map { (i, j) -> LatLon((cu + j * dLat) / 1e6, (lu + i * dLon) / 1e6) }
    }

    /** route_lib._tail_never: the last tail_m metres of leg [leg] use one of never_uses (a failed tail call: false). */
    private fun tailNever(calls: Calls, route: Route, leg: Int, rule: EveryRequest.TieCheck): Boolean =
        tailUses(calls, route, leg, rule.tailM)?.any { it in rule.neverUses } == true

    /**
     * route_lib.tail_uses: the Valhalla edge uses of the last [tailM] metres of leg [leg] of [route]: edge_walk,
     * and when the engine finds no exact match map_snap once more; null when both calls fail.
     */
    private fun tailUses(calls: Calls, route: Route, leg: Int, tailM: Int): List<String?>? {
        val shape = route.legs.getOrNull(leg)?.shape?.takeIf { it.isNotEmpty() } ?: return null
        val resp = calls.traceAttributes(tailRequest(shape, tailM)) ?: calls.traceAttributes(tailRequest(shape, tailM, "map_snap")) ?: return null
        val edges = resp["edges"] as? JsonArray ?: return emptyList()
        return edges.map { ((it as? JsonObject)?.get("use") as? JsonPrimitive)?.contentOrNull }
    }

    /** A check request's answer for the picked route ([checkRoute]): the route, and the request and index that give it. */
    private data class Checked(val route: Route, val chosen: ChosenRoute)

    /**
     * route_lib._check_route: the route a check request (tie check, arrival side) gives for the
     * picked route. Its main route when the picked one is the main route of its request (the
     * request asked without alternates); for an alternate a pool picked (batch D) the request keeps
     * its alternates and the route the same way as the picked one ([sameWay]) is the answer; null
     * when the call fails or no route is the same way.
     */
    private fun checkRoute(calls: Calls, picked: Outcome.Picked, req: JsonObject): Checked? {
        if (picked.chosen.routeIndex == 0) {
            val plain = JsonObject(req.filterKeys { it != "alternates" })
            val main = calls.route(plain).getOrNull()?.main ?: return null
            return Checked(main, ChosenRoute(calls.effective(plain), 0))
        }
        val resp = calls.route(req).getOrNull() ?: return null
        val routes = listOf(resp.main) + resp.alternates
        val j = sameWay(picked.route, routes) ?: return null
        return Checked(routes[j], ChosenRoute(calls.effective(req), j))
    }

    private fun uturns(r: Route) = r.legs.sumOf { l -> l.maneuvers.count { it.type == ManeuverType.UTURN_LEFT || it.type == ManeuverType.UTURN_RIGHT } }

    private data class Sided(val route: Route, val chosen: ChosenRoute, val side: ArrivalSideResult)

    /**
     * presets.json every_request.arrival_side (route_lib.arrival_side, the same rule): when the
     * chosen route arrives at a stop with the destination on the left (drive on the right), the
     * same request is asked once more with preferred_side on those stops (street_side_cutoff
     * tertiary: on a residential street or below the engine ignores the side, so the plain route
     * stays; batch D); that route is kept when it succeeds, arrives at each of them on the right
     * (maneuver type 5; type 4, no side, is not enough), has no more U-turns, takes at most
     * max_extra_s longer and has at most max_extra_maneuvers more guidance instructions
     * ([Route.turnCount]; one block at most, batch D). For an alternate a pool (Simpler) picked
     * the side request keeps its alternates and its route the same way is the side route
     * ([checkRoute]; batch D).
     */
    private fun arrivalSide(calls: Calls, picked: Outcome.Picked, only: Set<Int>? = null): Sided {
        val keep = Sided(picked.route, picked.chosen, ArrivalSideResult.NOT_ASKED)
        val rule = presets.everyRequest.arrivalSide ?: return keep
        val req = picked.chosen.request
        val locs = req["locations"]?.jsonArray ?: return keep
        val legs = picked.route.legs
        if (legs.size != locs.size - 1) return keep
        // a stop that already says its side (a lot stop, preferred_side "either"; batch E) is not asked
        val left = legs.indices.filter {
            legs[it].maneuvers.last().type == ManeuverType.DESTINATION_LEFT && "preferred_side" !in locs[it + 1].jsonObject &&
                (only == null || it + 1 in only)
        }.map { it + 1 }
        if (left.isEmpty()) return keep
        val sideLocs = JsonArray(locs.mapIndexed { i, l -> if (i in left) JsonObject(l.jsonObject + rule.locationOptions) else l })
        val sideReq = JsonObject(req + ("locations" to sideLocs))
        val asked = keep.copy(side = ArrivalSideResult.ASKED_KEPT)
        val checked = checkRoute(calls, picked, sideReq) ?: return asked
        val side = checked.route
        val ok = side.legs.size == legs.size &&
            left.all { side.legs[it - 1].maneuvers.last().type == ManeuverType.DESTINATION_RIGHT } &&
            uturns(side) <= uturns(picked.route) &&
            side.timeS <= picked.route.timeS + rule.maxExtraS &&
            (rule.maxExtraManeuvers == null || side.turnCount <= picked.route.turnCount + rule.maxExtraManeuvers)
        return if (ok) Sided(side, checked.chosen, ArrivalSideResult.SIDE) else asked
    }

    private sealed interface Outcome {
        data class Picked(
            val route: Route,
            val chosen: ChosenRoute,
            val usedFallback: Boolean = false,
            val candidates: Int? = null,
            val noRouteFallback: Boolean = false,
        ) : Outcome
        data class Error(val e: EngineException) : Outcome
    }

    private fun runPlain(calls: Calls, stops: List<Stop>, preset: Preset): Outcome {
        val req = ValhallaRequests.route(stops, preset.costingOptions, every = presets.everyRequest)
        val first = calls.route(req)
        val fbOpts = preset.fallbackCostingOptions ?: return first.picked(calls, req)
        val fbReq = ValhallaRequests.route(stops, fbOpts, every = presets.everyRequest)
        if (first.isFailure) {
            // route_lib: the fallback answer is returned even when it fails too
            return calls.route(fbReq).picked(calls, fbReq, usedFallback = true, noRoute = true)
        }
        val main = first.getOrThrow().main
        for ((flag, limitM) in preset.fallbackWhen) {
            if (main.flaggedM(flag) > limitM) {
                val alt = calls.route(fbReq)
                if (alt.isSuccess && alt.getOrThrow().main.flaggedM(flag) < main.flaggedM(flag)) {
                    return alt.picked(calls, fbReq, usedFallback = true)
                }
            }
        }
        return first.picked(calls, req)
    }

    /** route_lib.run_preset for a pool: the pool, then (with options) the fallback pool when needed. */
    private fun runPoolPreset(calls: Calls, stops: List<Stop>, preset: Preset): Outcome {
        val best = runPool(calls, stops, preset, preset.candidates)
        if (preset.fallbackCandidates.isEmpty()) return best
        if (best is Outcome.Error) {
            val fb = runPool(calls, stops, preset, preset.fallbackCandidates)
            return if (fb is Outcome.Picked) fb.copy(usedFallback = true, noRouteFallback = true) else fb
        }
        best as Outcome.Picked
        for ((flag, limitM) in preset.fallbackWhen) {
            if (best.route.flaggedM(flag) > limitM) {
                val alt = runPool(calls, stops, preset, preset.fallbackCandidates)
                if (alt is Outcome.Picked && alt.route.flaggedM(flag) < best.route.flaggedM(flag)) return alt.copy(usedFallback = true)
            }
        }
        return best
    }

    private fun runPool(calls: Calls, stops: List<Stop>, preset: Preset, candidates: List<JsonObject>): Outcome {
        data class Candidate(val route: Route, val chosen: ChosenRoute)
        val pool = mutableListOf<Candidate>()
        var lastError: EngineException? = null
        candidates.forEachIndexed { i, opts ->
            val alts = if (i == 0 && preset.poolAlternates != null && preset.poolAlternates > 0 && stops.size == 2) preset.poolAlternates else null
            val req = ValhallaRequests.route(stops, opts, alts, presets.everyRequest)
            val r = calls.route(req)
            r.exceptionOrNull()?.let { lastError = it as EngineException; return@forEachIndexed }
            val resp = r.getOrThrow()
            val used = calls.effective(req)
            pool += Candidate(resp.main, ChosenRoute(used, 0))
            resp.alternates.forEachIndexed { j, a -> pool += Candidate(a, ChosenRoute(used, j + 1)) }
        }
        if (pool.isEmpty()) return Outcome.Error(lastError ?: EngineException(442, "no candidate route"))
        val limit = poolLimitS(pool.minOf { it.route.timeS }, preset)
        val ok = pool.filter { it.route.timeS <= limit }
        // first minimum in pool order, as Python's min()
        val key: (Candidate) -> Pair<Double, Double> = when (preset.select!!) {
            SelectRule.MIN_MANEUVERS -> { c -> c.route.turnCount.toDouble() to c.route.timeS }
            SelectRule.MIN_DISTANCE -> { c -> c.route.lengthM to c.route.timeS }
        }
        var best = ok.first()
        for (c in ok.drop(1)) {
            val (a1, a2) = key(c)
            val (b1, b2) = key(best)
            if (a1 < b1 || (a1 == b1 && a2 < b2)) best = c
        }
        return Outcome.Picked(best.route, best.chosen, candidates = pool.size)
    }

    /**
     * Alternates are asked with the options that produced the chosen route: the pool's first
     * request for a pool preset (Simpler: no extra call), the fallback options when the fallback
     * produced the route (Avoid highways from a start on a motorway), else the preset's own options.
     * The preset's own limit then applies to them too: an alternate that keeps more of a
     * `fallback_when` road than both the limit and the chosen route is dropped (an Avoid highways
     * alternate never has more motorway than the route the fallback rule accepted).
     * Reference: android/routing/scripts/reference_routes.py `alternates_for`.
     */
    private fun alternatesFor(calls: Calls, stops: List<Stop>, preset: Preset, picked: Outcome.Picked): List<Route> {
        val opts = when {
            preset.isPool && picked.usedFallback -> preset.fallbackCandidates.first()
            preset.isPool -> preset.candidates.first()
            picked.usedFallback -> preset.fallbackCostingOptions!!
            else -> preset.costingOptions
        }
        val chosen = picked.route
        val r = calls.route(ValhallaRequests.route(stops, opts, presets.maxAlternates, presets.everyRequest)).getOrNull() ?: return emptyList()
        return (listOf(r.main) + r.alternates)
            .filterNot { sameRoute(it, chosen) }
            .filter { a -> preset.fallbackWhen.all { (flag, limitM) -> a.flaggedM(flag) <= maxOf(limitM, chosen.flaggedM(flag)) + 1.0 } }
            .take(presets.maxAlternates)
    }

    private fun sameRoute(a: Route, b: Route) = abs(a.lengthM - b.lengthM) < 1.0 && abs(a.timeS - b.timeS) < 0.5 &&
        a.shape.size == b.shape.size

    companion object {
        /**
         * route_lib.SAME_WAY_SHARE: a check request's route is the same way as an alternate a pool
         * picked when its shape starts with at least this share of the picked route's length.
         */
        const val SAME_WAY_SHARE = 0.5

        /**
         * route_lib.tail_request (batch E2): the trace_attributes request over a leg's last [tailM] metres (its
         * shape from the end back to the first point past tailM), asked with edge.use; [match] edge_walk, else map_snap.
         */
        fun tailRequest(shape: List<LatLon>, tailM: Int, match: String = "edge_walk"): JsonObject {
            val tail = mutableListOf(shape.last())
            var d = 0.0
            for (k in shape.size - 2 downTo 0) {
                d += Geo.distanceM(shape[k], tail.last())
                tail += shape[k]
                if (d > tailM) break
            }
            tail.reverse()
            return buildJsonObject {
                put("shape", JsonArray(tail.map { p -> buildJsonObject { put("lat", p.lat); put("lon", p.lon) } }))
                put("costing", "auto")
                put("shape_match", match)
                put("filters", buildJsonObject {
                    put("attributes", JsonArray(listOf(JsonPrimitive("edge.use"))))
                    put("action", "include")
                })
            }
        }

        /**
         * route_lib.entrance_request (item 35): the trace_attributes request over a leg's last tail_m metres, the
         * route's own edges (edge_walk), asked with each edge's use and its place in the answer's shape.
         */
        fun entranceRequest(shape: List<LatLon>, rule: EveryRequest.Entrance): JsonObject {
            val tail = mutableListOf(shape.last())
            var d = 0.0
            for (k in shape.size - 2 downTo 0) {
                d += Geo.distanceM(shape[k], tail.last())
                tail += shape[k]
                if (d > rule.tailM) break
            }
            tail.reverse()
            return buildJsonObject {
                put("shape", JsonArray(tail.map { p -> buildJsonObject { put("lat", p.lat); put("lon", p.lon) } }))
                put("costing", "auto")
                put("shape_match", "edge_walk")
                put("filters", buildJsonObject {
                    put("attributes", JsonArray(listOf("edge.use", "edge.begin_shape_index", "edge.end_shape_index", "shape").map { JsonPrimitive(it) }))
                    put("action", "include")
                })
            }
        }

        /**
         * route_lib.pool_limit_s: the longest time a pool route may take, the quickest of the pool
         * [tMinS] plus max_time_increase of it and never more than max_time_extra_s over it (batch D,
         * the owner's answer: 10 %, at most 10 minutes; no cap without the key).
         */
        fun poolLimitS(tMinS: Double, preset: Preset): Double {
            val lim = tMinS * (1 + preset.maxTimeIncrease!!)
            return preset.maxTimeExtraS?.let { minOf(lim, tMinS + it) } ?: lim
        }

        /**
         * route_lib.same_way: the index in [routes] (a check request's main route and alternates) of
         * the route whose shape starts with the longest run of [picked]'s shape points (ties: the
         * first), when that run covers at least [SAME_WAY_SHARE] of [picked]'s length; else null.
         */
        fun sameWay(picked: Route, routes: List<Route>): Int? {
            val ps = picked.shape
            if (ps.size < 2) return null
            var total = 0.0
            for (i in 0 until ps.size - 1) total += Geo.distanceM(ps[i], ps[i + 1])
            var best: Int? = null
            var bestN = 0
            routes.forEachIndexed { j, r ->
                val rs = r.shape
                var n = 0
                while (n < ps.size && n < rs.size && ps[n] == rs[n]) n++
                if (n > bestN) {
                    best = j
                    bestN = n
                }
            }
            val b = best ?: return null
            var run = 0.0
            for (i in 0 until bestN - 1) run += Geo.distanceM(ps[i], ps[i + 1])
            return if (run >= SAME_WAY_SHARE * total) b else null
        }
    }

    /** route_lib.far_snap_warnings, for every stop that starts or ends a leg. */
    private fun farSnapWarnings(route: Route): List<RouteWarning> {
        val limit = presets.checks.warnIfEndpointGapMOver
        val out = mutableListOf<RouteWarning>()
        val ends = buildList {
            add(route.legs.first().shape.firstOrNull())
            route.legs.forEach { add(it.shape.lastOrNull()) }
        }
        if (ends.size == route.requested.size) {
            ends.forEachIndexed { i, p -> if (p != null) Geo.distanceM(route.requested[i], p).let { if (it > limit) out += RouteWarning.StopSnappedFar(i, it) } }
        } else {
            route.endpointGapsM()?.let { (s, e) ->
                if (s > limit) out += RouteWarning.StopSnappedFar(0, s)
                if (e > limit) out += RouteWarning.StopSnappedFar(route.requested.lastIndex, e)
            }
        }
        return out
    }

    private fun Result<RouteResponse>.picked(calls: Calls, req: JsonObject, usedFallback: Boolean = false, noRoute: Boolean = false): Outcome =
        fold({ Outcome.Picked(it.main, ChosenRoute(calls.effective(req), 0), usedFallback, noRouteFallback = noRoute) }, { Outcome.Error(it as EngineException) })

    /**
     * Engine calls of one plan, answered once per distinct request. A request whose stops' tie
     * (every_request stops) finds no street (171) is asked once more without it
     * ([EveryRequest.untie], route_lib.route); [effective] then gives the request that answered.
     */
    private class Calls(private val engine: RoutingEngine, private val every: EveryRequest) {
        private val memo = HashMap<String, Result<RouteResponse>>()
        private val untied = HashMap<String, JsonObject>()
        var count = 0
            private set

        fun effective(req: JsonObject): JsonObject = untied[canonicalJson(req)] ?: req

        /** One trace_attributes call (the address street's probe); null when the engine fails it. */
        fun traceAttributes(req: JsonObject): JsonObject? {
            count++
            return try {
                Json.parseToJsonElement(engine.traceAttributes(req.toString())).jsonObject
            } catch (e: EngineException) {
                null
            } catch (e: RuntimeException) {
                null
            }
        }

        fun route(req: JsonObject): Result<RouteResponse> {
            val key = canonicalJson(req)
            memo[key]?.let { return it }
            var r = once(req)
            var asked = req
            if ((r.exceptionOrNull() as? EngineException)?.code == 171) {
                every.untie(req)?.let { loose ->
                    r = once(loose)
                    asked = loose
                    if (r.isSuccess) untied[key] = loose
                }
            }
            // batch E2 (route_lib.route): still no route while drive-through lanes and alleys are left out: once more without them
            if (r.isFailure && "exclude_polygons" in asked) {
                val open = JsonObject(asked - "exclude_polygons")
                r = once(open)
                if (r.isSuccess) untied[key] = open
            }
            memo[key] = r
            return r
        }

        private fun once(req: JsonObject): Result<RouteResponse> = memo.getOrPut(canonicalJson(req)) {
            count++
            try {
                Result.success(ValhallaResponses.parseRoute(engine.route(req.toString())))
            } catch (e: EngineException) {
                Result.failure(e)
            } catch (e: RuntimeException) {
                // an answer that is not a route (not JSON, fields missing, unknown units): a failed
                // plan the UI can show (RoutingProblem.EngineFailure), never an exception out of plan()
                Result.failure(EngineException(null, "unreadable engine answer: $e", e))
            }
        }
    }
}
