package io.github.spysyweeb.beamline.routing.planner

import io.github.spysyweeb.beamline.routing.engine.EngineException
import io.github.spysyweeb.beamline.routing.model.Geo
import io.github.spysyweeb.beamline.routing.model.LatLon
import io.github.spysyweeb.beamline.routing.model.RoadFlag

/**
 * Why no route came back, in terms a driver can act on. The engine's codes are kept for logs.
 * Codes: valhalla/src/exceptions.cc at 3.9.1.
 */
sealed class RoutingProblem(val userText: String, val engineCode: Int?) {
    /**
     * A stop lies outside the installed maps: Valhalla 170 ("Locations are in unconnected regions")
     * or 171 ("No suitable edges near location"). With only Pennsylvania installed, a stop in Ohio
     * gives 170 up to about 50 km past the line and 171 beyond (docs/experiments/01-routing.md).
     * [stopIndices] are the stops whose map tile is not installed (empty when that is not known).
     */
    class OutsideInstalledMaps(val stopIndices: List<Int>, code: Int) :
        RoutingProblem(
            if (stopIndices.isEmpty()) "A stop is outside the installed maps. Install the map of the state it is in."
            else "Stop ${stopIndices.joinToString(", ") { (it + 1).toString() }} is outside the installed maps. " +
                "Install the map of the state it is in.",
            code,
        )

    /** All stops are on installed maps, but no road connects them (170 or 442). */
    class NoRoute(code: Int?) : RoutingProblem("No road connects these stops.", code)

    /**
     * 171 ("No suitable edges near location") although every stop lies on an installed map: the
     * engine found no road it may use near one of the stops (loki does not say which one).
     */
    class NoRoadNearStop(code: Int?) : RoutingProblem("No road was found near one of the stops. Move the stop closer to a road.", code)

    /** 154: longer than the engine allows for this costing. */
    class TooLong(code: Int?) : RoutingProblem("This trip is too long to plan in one go. Add a stop in between.", code)

    /** 150: more stops than the engine allows. */
    class TooManyStops(val max: Int?, code: Int?) : RoutingProblem("Too many stops for one trip.", code)

    /** Fewer than two stops. */
    data object NeedTwoStops : RoutingProblem("Choose where to go.", null)

    /** Anything else; [detail] is the engine's text, for logs only. */
    class EngineFailure(val detail: String, code: Int?) : RoutingProblem("The route could not be planned.", code)

    override fun toString() = "${javaClass.simpleName}(code=$engineCode, \"$userText\")"

    companion object {
        /**
         * Maps an engine error. [installed] says whether a point lies on an installed map tile
         * (from the routing pack manager); without it, 170 and 171 are both reported as "outside".
         * With it and every stop inside: 170 is "no road connects", 171 "no road near a stop".
         */
        fun from(e: EngineException, stops: List<LatLon>, installed: TileCoverage?): RoutingProblem {
            val code = e.code
            return when (code) {
                170, 171 -> {
                    val outside = installed?.let { cov -> stops.indices.filter { !cov.covers(stops[it]) } }
                    when {
                        outside == null -> OutsideInstalledMaps(emptyList(), code)
                        outside.isNotEmpty() -> OutsideInstalledMaps(outside, code)
                        code == 170 -> NoRoute(code)
                        else -> NoRoadNearStop(code)
                    }
                }
                442 -> NoRoute(code)
                154, 166 -> TooLong(code)
                150 -> TooManyStops(null, code)
                120 -> NeedTwoStops
                else -> EngineFailure(e.message ?: "", code)
            }
        }
    }
}

/** A warning that comes with a route the app still shows. */
sealed class RouteWarning(val userText: String) {
    /**
     * The engine snapped a stop to a road [gapM] metres from where it was asked (it does this
     * silently up to loki's 35 km search_cutoff: Lake Erie gives 14 km). Threshold: presets.json
     * checks.warn_if_endpoint_gap_m_over.
     */
    class StopSnappedFar(val stopIndex: Int, val gapM: Double) :
        RouteWarning(
            "The route ${if (stopIndex == 0) "starts" else "reaches stop ${stopIndex + 1}"} " +
                String.format(java.util.Locale.US, "%.1f", gapM / 1609.344) + " mi from the point you chose.",
        )

    /**
     * No route avoids what the options avoid (a stop or the destination on a toll road or a
     * motorway: Valhalla's exclusions forbid driving onto one), so the options' fallback planned
     * this route (docs/architecture.md 2.11 rules 2 and 6). [avoided] are the options' roads,
     * [used] those the route drives on. Same text as route_lib.option_fallback_warning:
     * "No route to this place avoids toll roads. This route uses them."
     */
    class OptionFallback(val avoided: Set<RoadFlag>, val used: Set<RoadFlag>) : RouteWarning(text(avoided, used)) {
        companion object {
            fun text(avoided: Set<RoadFlag>, used: Set<RoadFlag>): String {
                val named = RoadFlag.entries.filter { it in used.ifEmpty { avoided } }
                val words = named.joinToString(" and ") { if (it == RoadFlag.HIGHWAY) "highways" else "toll roads" }
                return "No route to this place avoids $words." + if (used.isNotEmpty()) " This route uses them." else ""
            }
        }
    }
}

/**
 * Which points lie on installed map tiles. Built from the level-2 tiles (0.25 degrees) of the
 * installed routing packs.
 */
class TileCoverage(private val level2TileIds: Set<Int>) {
    fun covers(p: LatLon): Boolean = Geo.tileId(p, 2) in level2TileIds

    companion object {
        /** From tile paths as listed in routing pack manifests ("2/000/747/851.gph"). */
        fun fromTilePaths(paths: Iterable<String>): TileCoverage = TileCoverage(
            paths.filter { it.startsWith("2/") }
                .map { it.removePrefix("2/").removeSuffix(".gph").replace("/", "").toInt() }
                .toSet(),
        )
    }
}
