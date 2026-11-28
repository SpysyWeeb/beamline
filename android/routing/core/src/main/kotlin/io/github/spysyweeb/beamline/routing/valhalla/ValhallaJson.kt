package io.github.spysyweeb.beamline.routing.valhalla

import io.github.spysyweeb.beamline.routing.model.ExitSign
import io.github.spysyweeb.beamline.routing.model.Geo
import io.github.spysyweeb.beamline.routing.model.Lane
import io.github.spysyweeb.beamline.routing.model.LaneState
import io.github.spysyweeb.beamline.routing.model.LatLon
import io.github.spysyweeb.beamline.routing.model.Leg
import io.github.spysyweeb.beamline.routing.model.Maneuver
import io.github.spysyweeb.beamline.routing.model.ManeuverType
import io.github.spysyweeb.beamline.routing.model.Route
import io.github.spysyweeb.beamline.routing.model.Stop
import io.github.spysyweeb.beamline.routing.model.StopType
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import io.github.spysyweeb.beamline.routing.preset.EveryRequest

/**
 * Requests the app sends to Valhalla 3.9.1. The route request is the one
 * packbuilder/routing/route_lib.py `request()` builds, plus `turn_lanes: true` (lanes do not change
 * the route). Keeping them identical is what lets the tests compare with the Python reference.
 * presets.json `every_request` ([EveryRequest]) applies: its costing options under the preset's,
 * its stop options on every location after the first.
 */
object ValhallaRequests {
    const val COSTING = "auto"

    fun location(stop: Stop): JsonObject = buildJsonObject {
        put("lat", stop.point.lat)
        put("lon", stop.point.lon)
        if (stop.type != StopType.BREAK) put("type", stop.type.valhalla)
        stop.headingDeg?.let { put("heading", it) }
        stop.radiusM?.let { put("radius", it) }
        stop.name?.let { put("name", it) }
        stop.displayPoint?.let {
            put("display_lat", it.lat)
            put("display_lon", it.lon)
        }
        stop.locationOptions?.forEach { (k, v) -> put(k, v) }
    }

    fun route(
        stops: List<Stop>,
        costingOptions: JsonObject,
        alternates: Int? = null,
        every: EveryRequest = EveryRequest.NONE,
    ): JsonObject = buildJsonObject {
        put("locations", JsonArray(stops.mapIndexed { i, s ->
            val l = location(s)
            if (i == 0 || !s.tied || every.stopOptions.isEmpty()) l else JsonObject(every.stopOptions + l)
        }))
        put("costing", COSTING)
        put("costing_options", buildJsonObject { put(COSTING, JsonObject(every.costingOptions + costingOptions)) })
        put("units", "kilometers")
        put("directions_options", buildJsonObject { put("units", "kilometers") })
        put("turn_lanes", true)
        alternates?.let { put("alternates", it) }
        // batch E2 (route_lib.request): the drive-through lanes and alleys near the stops, left out of every route of the
        // plan; item 35: before them the way into a lot this plan leaves out, so max_rings never drops it
        val rings = ArrayList<List<LatLon>>()
        for (s in stops.drop(1)) for (r in s.entry) if (r !in rings) rings += r
        for (s in stops.drop(1)) for (r in s.avoid) if (r !in rings) rings += r
        if (rings.isNotEmpty()) {
            val max = every.leftOut?.maxRings ?: rings.size
            put("exclude_polygons", JsonArray(rings.take(max).map { r ->
                JsonArray(r.map { p -> JsonArray(listOf(JsonPrimitive(p.lon), JsonPrimitive(p.lat))) })
            }))
        }
    }

    fun matrix(sources: List<LatLon>, targets: List<LatLon>, costingOptions: JsonObject = JsonObject(emptyMap())) =
        matrixOf(sources.map { Stop(it) }, targets.map { Stop(it) }, costingOptions)

    /**
     * Item 51 (rule C8): sources_to_targets with whole stops (a heading and a radius for the car, as a
     * re-route's origin has them). A different name from [matrix]: an overload on List<Stop> would clash on
     * the JVM with List<LatLon>.
     */
    fun matrixOf(sources: List<Stop>, targets: List<Stop>, costingOptions: JsonObject = JsonObject(emptyMap())) =
        buildJsonObject {
            put("sources", JsonArray(sources.map { location(it) }))
            put("targets", JsonArray(targets.map { location(it) }))
            put("costing", COSTING)
            put("costing_options", buildJsonObject { put(COSTING, costingOptions) })
            put("units", "kilometers")
        }

    /**
     * The same route in OSRM format with Valhalla's banner and voice instruction extensions: what
     * Ferrostar 0.59's Valhalla adapter asks for (common/ferrostar/src/routing_adapters/valhalla.rs),
     * parsed by its OSRM parser with polyline precision 6. Costing options, locations and
     * alternates stay as in [routeRequest], so the engine finds the same path.
     */
    fun osrm(routeRequest: JsonObject, units: String = "miles", language: String = "en-US"): JsonObject =
        JsonObject(routeRequest.filterKeys { it != "turn_lanes" && it != "directions_options" && it != "units" } + mapOf(
            "format" to JsonPrimitive("osrm"),
            "banner_instructions" to JsonPrimitive(true),
            "voice_instructions" to JsonPrimitive(true),
            "filters" to buildJsonObject {
                put("action", "include")
                put("attributes", buildJsonArray {
                    for (a in listOf("speed", "speed_limit", "time", "length")) add(JsonPrimitive("shape_attributes.$a"))
                })
            },
            "units" to JsonPrimitive(units),
            "language" to JsonPrimitive(language),
        ))
}

/** A parsed `route` answer: the main route and the alternates the engine added. */
data class RouteResponse(val main: Route, val alternates: List<Route>)

/** A parsed `sources_to_targets` answer; a null cell means the engine found no connection. */
class Matrix(val timesS: List<List<Double?>>, val distancesM: List<List<Double?>>) {
    val nulls get() = timesS.sumOf { row -> row.count { it == null } }
}

object ValhallaResponses {
    private val json = Json { ignoreUnknownKeys = true }

    fun parseRoute(body: String): RouteResponse {
        val root = json.parseToJsonElement(body).jsonObject
        val main = parseTrip(root.obj("trip") ?: error("no trip in the route response"))
        val alts = root["alternates"]?.jsonArray?.map { parseTrip(it.jsonObject.obj("trip")!!) } ?: emptyList()
        return RouteResponse(main, alts)
    }

    fun parseTrip(trip: JsonObject): Route {
        val toM = unitsToMetres(trip.str("units"))
        val legs = trip.arr("legs").map { parseLeg(it.jsonObject, toM) }
        val summary = trip.obj("summary")!!
        val requested = trip.arr("locations").map { it.jsonObject.let { l -> LatLon(l.dbl("lat")!!, l.dbl("lon")!!) } }
        return Route(
            lengthM = summary.dbl("length")!! * toM,
            timeS = summary.dbl("time")!!,
            legs = legs,
            requested = requested,
            hasToll = summary.bool("has_toll"),
            hasHighway = summary.bool("has_highway"),
            cost = summary.dbl("cost"),
        )
    }

    private fun parseLeg(leg: JsonObject, toM: Double): Leg {
        val s = leg.obj("summary")!!
        return Leg(
            lengthM = s.dbl("length")!! * toM,
            timeS = s.dbl("time")!!,
            shape = Geo.decodePolyline6(leg.str("shape") ?: ""),
            maneuvers = leg.arr("maneuvers").map { parseManeuver(it.jsonObject, toM) },
        )
    }

    private fun parseManeuver(m: JsonObject, toM: Double): Maneuver {
        val code = m.int("type") ?: 0
        return Maneuver(
            type = ManeuverType.of(code),
            typeCode = code,
            instruction = m.str("instruction") ?: "",
            verbalPreTransition = m.str("verbal_pre_transition_instruction"),
            lengthM = (m.dbl("length") ?: 0.0) * toM,
            timeS = m.dbl("time") ?: 0.0,
            streetNames = m.strings("street_names"),
            beginStreetNames = m.strings("begin_street_names"),
            beginShapeIndex = m.int("begin_shape_index") ?: 0,
            endShapeIndex = m.int("end_shape_index") ?: 0,
            toll = m.bool("toll"),
            highway = m.bool("highway"),
            sign = m.obj("sign")?.let(::parseSign),
            roundaboutExitCount = m.int("roundabout_exit_count"),
            lanes = m["lanes"]?.jsonArray?.map { parseLane(it.jsonObject) },
            multiCue = m.bool("verbal_multi_cue"),
        )
    }

    private fun parseSign(s: JsonObject): ExitSign {
        fun texts(key: String) = s[key]?.jsonArray?.mapNotNull { it.jsonObject.str("text") } ?: emptyList()
        return ExitSign(texts("exit_number_elements"), texts("exit_branch_elements"), texts("exit_toward_elements"),
            texts("exit_name_elements"))
    }

    /** route_serializer_valhalla.cc turn_lanes(): "active" or "valid" hold the direction the lane is used for. */
    private fun parseLane(l: JsonObject): Lane {
        val dirs = l.int("directions") ?: 0
        val active = l.int("active")
        val valid = l.int("valid")
        return when {
            active != null -> Lane(dirs, LaneState.ACTIVE, active)
            valid != null -> Lane(dirs, LaneState.VALID, valid)
            else -> Lane(dirs, LaneState.NONE, null)
        }
    }

    fun parseMatrix(body: String): Matrix {
        val root = json.parseToJsonElement(body).jsonObject
        val toM = unitsToMetres(root.str("units"))
        val rows = root.arr("sources_to_targets")
        val times = rows.map { r -> r.jsonArray.map { c -> c.jsonObject.dbl("time") } }
        val dists = rows.map { r -> r.jsonArray.map { c -> c.jsonObject.dbl("distance")?.times(toM) } }
        return Matrix(times, dists)
    }

    private fun unitsToMetres(units: String?) = when (units) {
        null, "kilometers", "km" -> 1000.0
        "miles", "mi" -> 1609.344
        else -> error("unknown units $units")
    }
}

internal fun JsonObject.obj(key: String): JsonObject? = (this[key] as? JsonObject)
internal fun JsonObject.arr(key: String): JsonArray = (this[key] as? JsonArray) ?: JsonArray(emptyList())
internal fun JsonObject.str(key: String): String? = (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
internal fun JsonObject.dbl(key: String): Double? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.doubleOrNull
internal fun JsonObject.int(key: String): Int? = (this[key] as? JsonPrimitive)?.takeIf { it !is JsonNull }?.intOrNull
internal fun JsonObject.bool(key: String): Boolean = (this[key] as? JsonPrimitive)?.booleanOrNull ?: false
internal fun JsonObject.strings(key: String): List<String> =
    (this[key] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content } ?: emptyList()

/** A canonical text form of a JSON value: object keys sorted, numbers in their shortest decimal form. */
fun canonicalJson(e: JsonElement): String = when (e) {
    is JsonObject -> e.entries.sortedBy { it.key }.joinToString(",", "{", "}") { (k, v) -> "\"$k\":" + canonicalJson(v) }
    is JsonArray -> e.joinToString(",", "[", "]") { canonicalJson(it) }
    is JsonNull -> "null"
    is JsonPrimitive -> when {
        e.isString -> e.toString()
        e.booleanOrNull != null -> e.content
        else -> java.math.BigDecimal(e.content).stripTrailingZeros().toPlainString()
    }
}
