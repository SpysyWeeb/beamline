package io.github.spysyweeb.beamline.nav

import io.github.spysyweeb.beamline.api.model.Bounds
import io.github.spysyweeb.beamline.api.model.GeoPoint
import io.github.spysyweeb.beamline.api.nav.BannerView
import io.github.spysyweeb.beamline.api.nav.FollowingView
import io.github.spysyweeb.beamline.api.nav.LaneArrow
import io.github.spysyweeb.beamline.api.nav.LaneMark
import io.github.spysyweeb.beamline.api.nav.LaneView
import io.github.spysyweeb.beamline.api.nav.ManeuverKind
import io.github.spysyweeb.beamline.api.nav.RouteChoice
import io.github.spysyweeb.beamline.api.nav.RouteStep
import io.github.spysyweeb.beamline.routing.banner.BannerModel
import io.github.spysyweeb.beamline.routing.banner.BannerRules
import io.github.spysyweeb.beamline.routing.model.LaneBits
import io.github.spysyweeb.beamline.routing.model.RoadFlag
import io.github.spysyweeb.beamline.routing.model.Route

/** :routing:banner's model as :core:api's views (the names are the same: ManeuverKind = ManeuverIcon, LaneMark = LaneMark). */
object Views {
    private val arrows = listOf(
        LaneBits.REVERSE to LaneArrow.REVERSE, LaneBits.SHARP_LEFT to LaneArrow.SHARP_LEFT, LaneBits.LEFT to LaneArrow.LEFT,
        LaneBits.SLIGHT_LEFT to LaneArrow.SLIGHT_LEFT, LaneBits.THROUGH to LaneArrow.THROUGH, LaneBits.SLIGHT_RIGHT to LaneArrow.SLIGHT_RIGHT,
        LaneBits.RIGHT to LaneArrow.RIGHT, LaneBits.SHARP_RIGHT to LaneArrow.SHARP_RIGHT, LaneBits.MERGE_TO_LEFT to LaneArrow.MERGE_TO_LEFT,
        LaneBits.MERGE_TO_RIGHT to LaneArrow.MERGE_TO_RIGHT, LaneBits.NONE to LaneArrow.NONE,
    )

    fun arrowsOf(mask: Int): Set<LaneArrow> = arrows.filter { (bit, _) -> mask and bit != 0 }.map { it.second }.toSet()

    /**
     * One view per maneuver of [route] (all legs flattened), built once per route (rule B1).
     * Arrivals get no chip and no lane row (B7); [BannerView.approachOnHighway] is the engine's
     * motorway flag of the stretch before the maneuver.
     */
    fun banners(route: Route, rules: BannerRules): List<BannerView> {
        val mans = route.maneuvers
        return BannerModel.build(route, rules).map { b ->
            val m = mans[b.maneuverIndex]
            val arrival = m.type.isDestination
            BannerView(
                maneuverIndex = b.maneuverIndex,
                kind = ManeuverKind.valueOf(b.main.icon.name),
                primary = b.main.text,
                roads = b.main.roads,
                towards = b.main.towards,
                exitNumber = b.main.exitNumber,
                roundaboutExit = b.main.roundaboutExit,
                approachOnHighway = b.maneuverIndex > 0 && mans[b.maneuverIndex - 1].highway,
                following = if (arrival) null else b.following?.let { f -> FollowingView(ManeuverKind.valueOf(f.icon.name), f.text, f.distanceM, f.timeS) },
                lanes = if (arrival) null else b.lanes?.map { c ->
                    val mark = LaneMark.valueOf(c.mark.name)
                    LaneView(arrowsOf(c.directions), mark, if (mark == LaneMark.NOT_FOR_ROUTE) null else c.usedDirection?.let { arrowsOf(it).firstOrNull() })
                },
            )
        }
    }

    /** The maneuvers whose words may say "At the light" (item 22): plain turns, never a ramp, an exit, a fork or an arrival. */
    private val LIGHT_KINDS = setOf(
        ManeuverKind.LEFT, ManeuverKind.RIGHT, ManeuverKind.SLIGHT_LEFT, ManeuverKind.SLIGHT_RIGHT, ManeuverKind.SHARP_LEFT,
        ManeuverKind.SHARP_RIGHT, ManeuverKind.UTURN_LEFT, ManeuverKind.UTURN_RIGHT,
    )

    /**
     * Item 22 (docs/next-round.md): [banners] with "At the light, turn left" for each plain turn whose
     * maneuver point is at a traffic light of [lights] ("Turn left" elsewhere). The chip of a following
     * turn keeps its short words.
     */
    fun atLights(banners: List<BannerView>, geo: RouteGeometry, lights: RouteLights): List<BannerView> {
        if (lights.size == 0) return banners
        return banners.map { b ->
            if (b.kind in LIGHT_KINDS && b.maneuverIndex in 0 until geo.maneuverCount && lights.isAt(geo.maneuverAlong[b.maneuverIndex])) {
                b.copy(primary = "At the light, " + b.primary.replaceFirstChar { it.lowercase() })
            } else b
        }
    }

    /**
     * Item 22 (the lead's walkthrough of 0.1.13: a bare "Turn left" into a store's lot): for each leg of
     * [lotLegs] (legs whose stop ends in its lot, StopEnd.LOT), the turn that leaves the last named road
     * says where it goes: "Turn left into the parking lot". That is the first of the unnamed maneuvers
     * that end the leg, when it is a plain turn and at most [INTO_LOT_WITHIN_M] before the arrival.
     */
    fun intoLots(banners: List<BannerView>, geo: RouteGeometry, lotLegs: Set<Int>): List<BannerView> {
        if (lotLegs.isEmpty()) return banners
        // one banner per maneuver, in order (Views.banners); anything else is left as it is
        if (banners.size != geo.maneuverCount || banners.indices.any { banners[it].maneuverIndex != it }) return banners
        val changed = HashSet<Int>()
        var legStart = 0
        for (i in banners.indices) {
            val arrival = i == banners.lastIndex || geo.maneuverLeg[i + 1] != geo.maneuverLeg[i]
            if (!arrival) continue
            val leg = geo.maneuverLeg[i]
            // the unnamed maneuvers just before the arrival i
            var first = i
            while (first - 1 > legStart && banners[first - 1].roads.isEmpty() && banners[first - 1].towards.isEmpty()) first--
            if (leg in lotLegs && first < i && banners[first].kind in LIGHT_KINDS &&
                geo.maneuverAlong[i] - geo.maneuverAlong[first] <= INTO_LOT_WITHIN_M) changed += first
            legStart = i + 1
        }
        return banners.map { b -> if (b.maneuverIndex in changed) b.copy(primary = b.primary + INTO_THE_PARKING_LOT) else b }
    }

    const val INTO_THE_PARKING_LOT = " into the parking lot"
    /** A lot's own lanes are short; an unnamed road longer than this before the arrival is something else. */
    const val INTO_LOT_WITHIN_M = 400.0

    /**
     * Item 22: the road being driven while the banner shows maneuver [u] of [route]: the names of the
     * stretch after the maneuver before it (at most two, the last word short as on the banner:
     * "US 19 / Peach St"), without a memorial name when the road has another ("I 90 East", not
     * "I 90 East / AMVETS Memorial Hwy": nobody looks for that on a sign); null where that stretch has
     * no name (a ramp, a parking lot).
     */
    fun currentRoad(route: Route, u: Int, rules: BannerRules): String? {
        val m = route.maneuvers.getOrNull(u - 1) ?: return null
        if (m.type.isDestination) return null
        // "AMVETS Memorial Highway", "General Strong Vincent Memorial Highway": an honorary name of three words
        // or more; "Memorial Drive" is a street's own name and stays
        val names = m.streetNames.filterNot { "Memorial" in it && it.trim().split(' ').size >= 3 }.ifEmpty { m.streetNames }
        return names.take(2).map(rules::abbreviate).joinToString(" / ").takeIf { it.isNotBlank() }
    }

    /** A route as a choice card / the guided route of TripState (docs/architecture.md 2.3). */
    fun choice(id: Long, typeIds: List<String>, route: Route, warnings: List<String>, rules: BannerRules): RouteChoice {
        val geometry = route.shape.map { GeoPoint(it.lat, it.lon) }
        var end = -1
        val legEnds = route.legs.map { leg -> end += leg.shape.size; end }
        return RouteChoice(
            id = id,
            typeIds = typeIds,
            lengthM = route.lengthM,
            timeS = route.timeS,
            turnCount = route.turnCount,
            hasTolls = route.hasToll || route.flaggedM(RoadFlag.TOLL) > 0.0,
            hasHighways = route.hasHighway || route.flaggedM(RoadFlag.HIGHWAY) > 0.0,
            via = via(route, rules),
            geometry = geometry,
            legEnds = legEnds,
            bounds = Bounds.around(geometry) ?: Bounds(0.0, 0.0, 0.0, 0.0),
            warnings = warnings,
            steps = steps(route, rules, geometry),
        )
    }

    /** Item 47: the steps preview's turns, from the banners (one per maneuver, all legs flattened). */
    fun steps(route: Route, rules: BannerRules, geometry: List<GeoPoint>): List<RouteStep> {
        if (geometry.isEmpty()) return emptyList()
        val starts = ArrayList<Int>()
        var offset = 0
        for (leg in route.legs) {
            for (m in leg.maneuvers) starts += offset + m.beginShapeIndex
            offset += leg.shape.size
        }
        val mans = route.maneuvers
        return banners(route, rules).map { v ->
            val at = starts.getOrElse(v.maneuverIndex) { 0 }.coerceIn(0, geometry.size - 1)
            RouteStep(v.kind, v.primary, v.roads, mans[v.maneuverIndex].lengthM, geometry[at])
        }
    }

    /** Names of the two longest stretches (the road a maneuver leads onto, by its length), for "via I 90, US 19". */
    fun via(route: Route, rules: BannerRules): List<String> {
        val byName = LinkedHashMap<String, Double>()
        for (m in route.maneuvers) {
            val name = m.streetNames.firstOrNull() ?: continue
            byName[name] = (byName[name] ?: 0.0) + m.lengthM
        }
        return byName.entries.sortedByDescending { it.value }.take(2).map { rules.abbreviate(it.key) }
    }
}
