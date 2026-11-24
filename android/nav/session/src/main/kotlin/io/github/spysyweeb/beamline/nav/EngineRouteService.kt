package io.github.spysyweeb.beamline.nav

import io.github.spysyweeb.beamline.api.data.RoutingSet
import io.github.spysyweeb.beamline.routing.engine.EngineException
import io.github.spysyweeb.beamline.routing.engine.RoutingEngine
import io.github.spysyweeb.beamline.routing.model.LatLon
import io.github.spysyweeb.beamline.routing.model.RouteRequest
import io.github.spysyweeb.beamline.routing.model.Stop
import io.github.spysyweeb.beamline.routing.planner.AddressTies
import io.github.spysyweeb.beamline.routing.planner.ChosenRoute
import io.github.spysyweeb.beamline.routing.planner.PlanResult
import io.github.spysyweeb.beamline.routing.planner.RoutePlanner
import io.github.spysyweeb.beamline.routing.planner.RoutingProblem
import io.github.spysyweeb.beamline.routing.planner.StopOrder
import io.github.spysyweeb.beamline.routing.planner.StopOrderer
import io.github.spysyweeb.beamline.routing.planner.TileCoverage
import io.github.spysyweeb.beamline.routing.preset.Preset
import io.github.spysyweeb.beamline.routing.preset.PresetSet
import io.github.spysyweeb.beamline.routing.valhalla.ValhallaRequests
import java.io.File

/**
 * Planning, the OSRM request and the stop-order matrix on the routing engine (docs/architecture.md
 * 2.4, 1.2 and 2.2 "How each consumer obtains its data"). Every method runs on `beamline-routing`
 * only: the engine is never used from two threads.
 *
 * The engine holder of 2.2 rule 3: one engine per installed generation, opened with [opener] on
 * the first call that needs that generation's tile set; when a newer generation is asked for, the
 * old engine is closed first (the call that ran on it has returned: calls are one after the other
 * on this thread). [close] closes it without replacement (the session in IDLE after a new
 * generation, or a set without routing).
 */
class EngineRouteService(
    private val opener: EngineOpener,
    private val presets: PresetSet,
    private val rules: GuidanceRules = GuidanceRules.bundled(),
    private val bannerRules: io.github.spysyweeb.beamline.routing.banner.BannerRules = io.github.spysyweeb.beamline.routing.banner.BannerRules.bundled(),
    /** Item 36: the traffic lights the installed packs have in the given cells ([RouteLights.cell]); null: none to ask. */
    private val signals: ((Collection<Long>) -> List<LatLon>)? = null,
) {
    private var engine: RoutingEngine? = null
    private var engineGeneration: Long? = null
    private var engineConfig: String? = null
    /** Address-street tie points found for this routing set (a re-route to the same place does not probe again). */
    private var ties = AddressTies()
    private var tiesGeneration: Long? = null

    /** How many times an engine was opened (tests: one per generation). */
    var opened = 0
        private set

    private fun engineFor(generation: Long, set: RoutingSet): RoutingEngine {
        val e = engine
        if (e != null && engineGeneration == generation && engineConfig == set.engineConfigFile) return e
        close()
        val fresh = opener.open(File(set.engineConfigFile))
        opened++
        engine = fresh
        engineGeneration = generation
        engineConfig = set.engineConfigFile
        return fresh
    }

    /** Closes the engine, if one is open. */
    fun close() {
        (engine as? AutoCloseable)?.close()
        engine = null
        engineGeneration = null
        engineConfig = null
    }

    val isOpen get() = engine != null

    /**
     * One plan; an engine that cannot be opened, or anything else the planner did not turn into a
     * failed plan itself, is a failed plan (ENGINE), never an exception: the session must not be
     * left half way (a hold taken, PLANNING for ever).
     */
    fun plan(generation: Long, set: RoutingSet, stops: List<Stop>, preset: Preset): PlanResult {
        val e = try {
            engineFor(generation, set)
        } catch (x: Exception) {
            return PlanResult.Failed(RoutingProblem.EngineFailure("engine could not be opened: ${x.javaClass.simpleName}", null), 0)
        }
        return try {
            if (tiesGeneration != generation) {
                ties = AddressTies()
                tiesGeneration = generation
            }
            RoutePlanner(e, presets, TileCoverage(set.level2Tiles), ties).plan(RouteRequest(stops), preset)
        } catch (x: Exception) {
            PlanResult.Failed(RoutingProblem.EngineFailure("planner: ${x.javaClass.simpleName}", null), 0)
        }
    }

    /** The engine's OSRM-format answer for the chosen request ([FollowerRoute.osrmJson]; decision 6.15). */
    fun osrm(generation: Long, set: RoutingSet, chosen: ChosenRoute): String =
        engineFor(generation, set).route(ValhallaRequests.osrm(chosen.request).toString())

    /**
     * The OSRM answer of a chosen route and its lane warnings (rule B9, batch C1: one
     * trace_attributes call per intersection where a lane may end in a turn, at most
     * lane_warning.max_calls; a failed call only drops that warning).
     */
    fun forGuidance(generation: Long, set: RoutingSet, plan: PlanResult.Ok): GuideAnswer {
        val osrm = osrm(generation, set, plan.chosen)
        val lanes = try {
            val e = engineFor(generation, set)
            LaneWarnings.build(RouteGeometry(plan.route), osrm, plan.chosen.routeIndex, rules, bannerRules) { req -> e.traceAttributes(req) }
        } catch (x: Exception) {
            LaneWarnings.NONE
        }
        // item 22: the traffic lights on the route (one more trace_attributes call per stretch; a failure: none);
        // item 36: and the packs' lights at its junctions
        val lights = try {
            val e = engineFor(generation, set)
            RouteLights.build(RouteGeometry(plan.route), signals) { req -> e.traceAttributes(req) }
        } catch (x: Exception) {
            RouteLights.NONE
        }
        return GuideAnswer(osrm, lanes, lights)
    }

    /** A plan and, when it worked, its OSRM form and lane warnings: what a re-route or a change during guidance needs. */
    fun planForGuidance(generation: Long, set: RoutingSet, stops: List<Stop>, preset: Preset): Pair<PlanResult, GuideAnswer?> {
        val r = plan(generation, set, stops, preset)
        if (r !is PlanResult.Ok) return r to null
        return try {
            r to forGuidance(generation, set, r)
        } catch (x: EngineException) {
            PlanResult.Failed(RoutingProblem.EngineFailure("osrm: code ${x.code}", x.code), r.engineCalls + 1) to null
        } catch (x: Exception) {
            PlanResult.Failed(RoutingProblem.EngineFailure("osrm: ${x.javaClass.simpleName}", null), r.engineCalls + 1) to null
        }
    }

    /** Rule C6: the quickest order of [points] (the origin first, the destination kept last); null when none connects. */
    fun bestOrder(generation: Long, set: RoutingSet, points: List<LatLon>, preset: Preset): StopOrder? =
        StopOrderer(engineFor(generation, set), presets).bestOrder(points, keepLast = true, preset)
}

/** What guiding a chosen route needs from the engine besides the plan: the OSRM answer, the lane warnings and the traffic lights (item 22). */
class GuideAnswer(val osrm: String, val lanes: LaneWarnings = LaneWarnings.NONE, val lights: RouteLights = RouteLights.NONE)
