package io.github.spysyweeb.beamline.routing.preset

import io.github.spysyweeb.beamline.routing.model.RoadFlag
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/** How a candidate-pool preset picks its route (presets.json "select"). */
enum class SelectRule(val id: String) { MIN_MANEUVERS("min_maneuvers"), MIN_DISTANCE("min_distance") }

/**
 * What the planner runs: one route type with its options composed ([PresetSet.compose]), in the
 * shape of a format-1 preset (packbuilder/routing/route_lib.py `compose` and `run_preset` are the
 * reference).
 *
 * Either one request ([costingOptions], with [fallbackCostingOptions] when it finds no route or
 * when a [fallbackWhen] condition holds), or a candidate pool ([candidates], the first one also
 * asking for [poolAlternates]) from which [select] picks within [maxTimeIncrease] of the fastest,
 * and never more than [maxTimeExtraS] seconds over it (batch D; null: no such cap);
 * a pool with options has [fallbackCandidates], the same pool with the options' fallback options.
 * [optionIds] are the options composed in (file order) and [avoids] the roads they avoid; both
 * are empty for a type without options and for a format-1 preset.
 */
data class Preset(
    val id: String,
    val label: String,
    val costing: String,
    val costingOptions: JsonObject,
    val fallbackCostingOptions: JsonObject? = null,
    val fallbackWhen: Map<RoadFlag, Double> = emptyMap(),
    val select: SelectRule? = null,
    val maxTimeIncrease: Double? = null,
    val maxTimeExtraS: Double? = null,
    val poolAlternates: Int? = null,
    val candidates: List<JsonObject> = emptyList(),
    val fallbackCandidates: List<JsonObject> = emptyList(),
    val optionIds: List<String> = emptyList(),
    val avoids: Set<RoadFlag> = emptySet(),
) {
    val isPool get() = select != null

    /** True when the planner runs both the same way (every field but the names; route_lib.same_run). */
    fun sameRun(other: Preset): Boolean =
        copy(id = other.id, label = other.label, optionIds = other.optionIds, avoids = other.avoids) == other
}

/**
 * One route type of presets.json format 2 ("route_types"): what the route sheet offers as a card
 * when [offered] (Fastest, Simpler; Eco is not offered; Shortest left in batch D).
 */
data class RouteType(
    val id: String,
    val label: String,
    val offered: Boolean,
    val costing: String,
    val costingOptions: JsonObject,
    /** False: the options' fallback_when re-run is skipped (docs/architecture.md 2.11 rule 2; no type since Shortest left in batch D). */
    val optionFallbackWhen: Boolean,
    val select: SelectRule?,
    val maxTimeIncrease: Double?,
    val maxTimeExtraS: Double?,
    val poolAlternates: Int?,
    val candidates: List<JsonObject>,
) {
    val isPool get() = select != null
}

/** One option of presets.json format 2 ("options"): a toggle that applies to every route type. */
data class RouteOption(
    val id: String,
    val label: String,
    val costingOptions: JsonObject,
    /** Applied instead of [costingOptions] when the request finds no route, or past a [fallbackWhen] limit. */
    val fallbackCostingOptions: JsonObject,
    val fallbackWhen: Map<RoadFlag, Double>,
) {
    /** The roads this option avoids: the flags whose hard exclusion (exclude_highways, exclude_tolls) it sets. */
    val avoids: Set<RoadFlag>
        get() = RoadFlag.entries.filter { (costingOptions["exclude_${it.key}s"] as? JsonPrimitive)?.booleanOrNull == true }.toSet()
}

/** presets.json "default": the route type and options a new user starts with. */
data class PresetDefault(val routeType: String, val options: Set<String>)

/** App-side result checks from presets.json "checks". */
data class ResultChecks(val warnIfEndpointGapMOver: Double)

/**
 * presets.json format 2 (packbuilder/routing/presets.json is the source; the copy in this
 * module's resources is made by android/routing/scripts/sync_data.py and never edited here):
 * route types plus options that apply to every type, composed by [compose] (docs/architecture.md
 * 2.11).
 */
class PresetSet(
    val routeTypes: List<RouteType>,
    val options: List<RouteOption>,
    val default: PresetDefault,
    val checks: ResultChecks,
    /** Engine settings the presets need (dotted Valhalla config keys), e.g. allow_hard_exclusions. */
    val requiresConfig: Map<String, JsonElement>,
    val valhallaVersion: String,
    /** presets.json `every_request`: what every route request gets ([EveryRequest]). */
    val everyRequest: EveryRequest = EveryRequest.NONE,
) {
    /** The route types the route sheet offers, in file order (Eco is not offered). */
    fun offered(): List<RouteType> = routeTypes.filter { it.offered }

    fun type(id: String): RouteType = routeTypes.firstOrNull { it.id == id } ?: throw IllegalArgumentException("no route type $id")

    /** The alternates count the engine allows (service_limits.max_alternates in requires_config). */
    val maxAlternates: Int get() = requiresConfig["service_limits.max_alternates"]?.jsonPrimitive?.int ?: 2

    /**
     * One route type with a set of options, as the planner runs it (route_lib.compose, the same
     * rules): every request of the type (its one request, or each pool candidate) gets the type's
     * costing options, then the candidate's, then each selected option's costing options on top
     * (options in file order, whatever the order of [optionIds]); the fallback takes each option's
     * fallback costing options instead. fallback_when maps are united (the smaller limit wins),
     * except for a type with option_fallback_when false. With no options this equals format 1's
     * preset of the same id; compose("fastest", setOf("avoid_highways")) equals format 1's
     * avoid_highways (checked by the tests with [Preset.sameRun]).
     */
    fun compose(typeId: String, optionIds: Set<String>): Preset {
        val t = type(typeId)
        val unknown = optionIds - options.map { it.id }.toSet()
        require(unknown.isEmpty()) { "no option ${unknown.sorted()}" }
        val sel = options.filter { it.id in optionIds }
        fun merged(vararg parts: JsonObject): JsonObject = JsonObject(LinkedHashMap<String, JsonElement>().apply { parts.forEach { putAll(it) } })
        val opts = sel.map { it.costingOptions }.toTypedArray()
        val fbs = sel.map { it.fallbackCostingOptions }.toTypedArray()
        val fallbackWhen = if (sel.isEmpty() || !t.optionFallbackWhen) emptyMap() else
            buildMap { sel.forEach { o -> o.fallbackWhen.forEach { (k, v) -> put(k, minOf(v, get(k) ?: v)) } } }
        return Preset(
            id = (listOf(t.id) + sel.map { it.id }).joinToString("+"),
            label = t.label,
            costing = t.costing,
            costingOptions = if (t.isPool) JsonObject(emptyMap()) else merged(t.costingOptions, *opts),
            fallbackCostingOptions = if (t.isPool || sel.isEmpty()) null else merged(t.costingOptions, *fbs),
            fallbackWhen = fallbackWhen,
            select = t.select,
            maxTimeIncrease = t.maxTimeIncrease,
            maxTimeExtraS = t.maxTimeExtraS,
            poolAlternates = t.poolAlternates,
            candidates = t.candidates.map { merged(t.costingOptions, it, *opts) },
            fallbackCandidates = if (sel.isEmpty()) emptyList() else t.candidates.map { merged(t.costingOptions, it, *fbs) },
            optionIds = sel.map { it.id },
            avoids = RoadFlag.entries.filter { f -> sel.any { f in it.avoids } }.toSet(),
        )
    }

    /**
     * route_lib.decision_preset (batch E2, docs/next-round.md item 19): the preset that decides the end of
     * each stop, Fastest with the same options when [p] is a route type of this set ("simpler+avoid_tolls"
     * -> "fastest+avoid_tolls"), else [p] itself (a format-1 preset of the old reference plans).
     */
    fun decisionPreset(p: Preset): Preset {
        val parts = p.id.split("+")
        val known = options.map { it.id }.toSet()
        return if (routeTypes.any { it.id == "fastest" } && routeTypes.any { it.id == parts[0] } && parts.drop(1).all { it in known }) {
            compose("fastest", parts.drop(1).toSet())
        } else p
    }

    /**
     * A preset by name: a route type ("simpler"), a type with options joined by "+"
     * ("fastest+avoid_tolls"), or one of the two option ids of format 1 alone ("avoid_highways"),
     * which means the default type with that option. For callers that name a plan by one string
     * (the demo, the tests, [io.github.spysyweeb.beamline.routing.model.RouteRequest.presetId]).
     */
    fun resolve(id: String): Preset {
        val parts = id.split("+")
        return if (routeTypes.any { it.id == parts[0] }) compose(parts[0], parts.drop(1).toSet())
        else compose(default.routeType, parts.toSet())
    }

    companion object {
        const val FORMAT = "beamline-route-presets/2"
        const val FORMAT_1 = "beamline-route-presets/1"

        fun parse(text: String): PresetSet {
            val root = Json.parseToJsonElement(text).jsonObject
            require(root["format"]?.jsonPrimitive?.content == FORMAT) { "presets.json: format is not $FORMAT" }
            val typesJson = root["route_types"]!!.jsonArray.map { it.jsonObject }
            val optionsJson = root["options"]!!.jsonArray.map { it.jsonObject }
            val errs = mutableListOf<String>()
            val ids = (typesJson + optionsJson).map { it["id"]?.jsonPrimitive?.content }
            if (ids.any { it == null } || ids.toSet().size != ids.size) errs += "route type and option ids must be present and unique: $ids"
            for (o in typesJson + optionsJson) for (k in UNSUPPORTED_PRESET_KEYS) if (k in o) {
                errs += "${o["id"]?.jsonPrimitive?.content}: $k is not supported by the app planner (route_lib.request applies it, RoutePlanner would not)"
            }
            require(errs.isEmpty()) { "presets.json: " + errs.joinToString("; ") }
            val types = typesJson.map(::parseType)
            val options = optionsJson.map(::parseOption)
            if (types.none { it.offered }) errs += "no route type is offered"
            options.forEachIndexed { i, a ->
                for (b in options.drop(i + 1)) {
                    val both = (a.costingOptions.keys + a.fallbackCostingOptions.keys) intersect (b.costingOptions.keys + b.fallbackCostingOptions.keys)
                    if (both.isNotEmpty()) errs += "options ${a.id} and ${b.id} both set ${both.sorted()}"
                }
            }
            val d = root["default"]!!.jsonObject
            val default = PresetDefault(d["route_type"]!!.jsonPrimitive.content, d["options"]?.jsonArray?.map { it.jsonPrimitive.content }?.toSet() ?: emptySet())
            if (types.none { it.offered && it.id == default.routeType }) errs += "default route_type ${default.routeType} is not an offered type"
            if (!options.map { it.id }.containsAll(default.options)) errs += "default options ${default.options} unknown"
            require(errs.isEmpty()) { "presets.json: " + errs.joinToString("; ") }
            val checks = root["checks"]?.jsonObject
            val set = PresetSet(
                routeTypes = types,
                options = options,
                default = default,
                checks = ResultChecks(checks?.get("warn_if_endpoint_gap_m_over")?.jsonPrimitive?.doubleOrNull ?: 1000.0),
                requiresConfig = root["requires_config"]?.jsonObject ?: emptyMap(),
                valhallaVersion = root["valhalla_version"]!!.jsonPrimitive.content,
                everyRequest = EveryRequest.parse(root["every_request"]?.jsonObject ?: error("presets.json: every_request missing")),
            )
            // every composition is valid for Valhalla 3.9.1 (route_lib.validate_preset_file does the same)
            val ids2 = options.map { it.id }
            val subsets = (0 until (1 shl ids2.size)).map { m -> ids2.filterIndexed { i, _ -> m and (1 shl i) != 0 }.toSet() }
            val problems = types.flatMap { t -> subsets.flatMap { s -> PresetValidator.validate(set.compose(t.id, s)) } }
            require(problems.isEmpty()) { "presets.json: " + problems.joinToString("; ") }
            return set
        }

        /** The presets.json bundled with this module. */
        fun bundled(): PresetSet = parse(resourceText("presets.json"))

        /**
         * Preset keys route_lib.py understands but this planner does not apply. route_lib.request()
         * merges `request_options` into the request; refusing it here keeps the two planners from
         * silently diverging when someone adds it to presets.json.
         */
        val UNSUPPORTED_PRESET_KEYS = setOf("request_options")

        /**
         * The presets of a format-1 file, as they were before format 2 (the old reference of 126
         * plans is recorded with them: android/routing/testdata/reference/presets-format1.json).
         */
        fun parseFormat1(text: String): List<Preset> {
            val root = Json.parseToJsonElement(text).jsonObject
            require(root["format"]?.jsonPrimitive?.content == FORMAT_1) { "presets: format is not $FORMAT_1" }
            val presets = root["presets"]!!.jsonArray.map { parseFormat1Preset(it.jsonObject) }
            val problems = presets.flatMap { PresetValidator.validate(it) }
            require(problems.isEmpty()) { "presets: " + problems.joinToString("; ") }
            return presets
        }

        private fun JsonObject.str(k: String) = this[k]?.jsonPrimitive?.content

        private fun fallbackWhenOf(p: JsonObject): Map<RoadFlag, Double> =
            p["fallback_when"]?.jsonObject?.map { (k, v) ->
                val flag = RoadFlag.entries.firstOrNull { "${it.key}_km_over" == k }
                    ?: throw IllegalArgumentException("${p.str("id")}: unknown fallback_when condition $k")
                flag to v.jsonPrimitive.doubleOrNull!! * 1000.0 // km in the file, metres here
            }?.toMap() ?: emptyMap()

        private fun selectOf(p: JsonObject): SelectRule? = p.str("select")?.let { s ->
            SelectRule.entries.firstOrNull { it.id == s } ?: throw IllegalArgumentException("${p.str("id")}: unknown select rule $s")
        }

        private fun boolOf(p: JsonObject, k: String, default: Boolean?): Boolean {
            val e = p[k] ?: return default ?: throw IllegalArgumentException("${p.str("id")}: $k missing")
            return (e as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull
                ?: throw IllegalArgumentException("${p.str("id")}: $k must be true or false")
        }

        private fun parseType(p: JsonObject) = RouteType(
            id = p.str("id")!!,
            label = p.str("label") ?: throw IllegalArgumentException("${p.str("id")}: label missing"),
            offered = boolOf(p, "offered", null),
            costing = p.str("costing") ?: "auto",
            costingOptions = p["costing_options"]?.jsonObject ?: JsonObject(emptyMap()),
            optionFallbackWhen = boolOf(p, "option_fallback_when", true),
            select = selectOf(p),
            maxTimeIncrease = p["max_time_increase"]?.jsonPrimitive?.doubleOrNull,
            maxTimeExtraS = maxTimeExtraSOf(p),
            poolAlternates = p["alternates"]?.jsonPrimitive?.int,
            candidates = p["candidates"]?.jsonArray?.map { it.jsonObject } ?: emptyList(),
        )

        private fun maxTimeExtraSOf(p: JsonObject): Double? = p["max_time_extra_s"]?.let { v ->
            (v as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull
                ?: throw IllegalArgumentException("${p.str("id")}: max_time_extra_s must be a number of seconds")
        }

        private fun parseOption(p: JsonObject) = RouteOption(
            id = p.str("id")!!,
            label = p.str("label") ?: throw IllegalArgumentException("${p.str("id")}: label missing"),
            costingOptions = p["costing_options"]?.jsonObject ?: throw IllegalArgumentException("${p.str("id")}: costing_options missing"),
            fallbackCostingOptions = p["fallback_costing_options"]?.jsonObject
                ?: throw IllegalArgumentException("${p.str("id")}: fallback_costing_options missing"),
            fallbackWhen = fallbackWhenOf(p),
        )

        private fun parseFormat1Preset(p: JsonObject): Preset {
            for (k in UNSUPPORTED_PRESET_KEYS) require(k !in p) {
                "preset ${p.str("id")}: $k is not supported by the app planner (route_lib.request applies it, RoutePlanner would not)"
            }
            return Preset(
                id = p.str("id")!!,
                label = p.str("label")!!,
                costing = p.str("costing") ?: "auto",
                costingOptions = p["costing_options"]?.jsonObject ?: JsonObject(emptyMap()),
                fallbackCostingOptions = p["fallback_costing_options"]?.jsonObject,
                fallbackWhen = fallbackWhenOf(p),
                select = selectOf(p),
                maxTimeIncrease = p["max_time_increase"]?.jsonPrimitive?.doubleOrNull,
                maxTimeExtraS = maxTimeExtraSOf(p),
                poolAlternates = p["alternates"]?.jsonPrimitive?.int,
                candidates = p["candidates"]?.jsonArray?.map { it.jsonObject } ?: emptyList(),
            )
        }
    }
}

/**
 * The same checks as route_lib.validate_preset: Valhalla silently ignores unknown option names and
 * out-of-range values, so a typo in presets.json must fail here, not route like Fastest.
 * Option list: Valhalla 3.9.1 auto costing (route_lib.AUTO_OPTIONS_3_9_1).
 */
object PresetValidator {
    private sealed interface Kind
    private data class Num(val min: Double?, val max: Double?) : Kind
    private data object Bool : Kind
    private data object ListKind : Kind
    private data object ObjKind : Kind

    private val options: Map<String, Kind> = buildMap {
        for (k in listOf("maneuver_penalty", "gate_cost", "gate_penalty", "private_access_penalty",
            "destination_only_penalty", "toll_booth_cost", "toll_booth_penalty", "ferry_cost", "service_penalty",
            "service_factor", "country_crossing_cost", "country_crossing_penalty", "height", "width", "length",
            "weight", "speed_penalty_factor", "alley_penalty")) put(k, Num(0.0, null))
        put("alley_factor", Num(0.1, 100000.0)) // src/sif/autocost.cc kAlleyFactorRange
        for (k in listOf("use_ferry", "use_highways", "use_tolls", "use_living_streets", "use_tracks", "use_distance"))
            put(k, Num(0.0, 1.0))
        put("top_speed", Num(10.0, 252.0))
        put("fixed_speed", Num(0.0, 252.0))
        put("closure_factor", Num(1.0, 10.0))
        for (k in listOf("shortest", "disable_hierarchy_pruning", "ignore_closures", "ignore_restrictions",
            "ignore_oneways", "ignore_non_vehicular_restrictions", "ignore_access", "ignore_construction",
            "exclude_unpaved", "exclude_cash_only_tolls", "include_hov2", "include_hov3", "include_hot",
            "exclude_bridges", "exclude_tunnels", "exclude_tolls", "exclude_highways", "exclude_ferries")) put(k, Bool)
        put("speed_types", ListKind)
        put("hierarchy_limits", ObjKind)
    }

    fun validateOptions(opts: JsonObject): List<String> = opts.mapNotNull { (k, v) ->
        when (val kind = options[k]) {
            null -> "unknown auto costing option '$k'"
            is Num -> {
                val p = v as? JsonPrimitive
                val d = if (p == null || p.isString || p.booleanOrNull != null) null else p.doubleOrNull
                when {
                    d == null -> "$k=$v is not a number"
                    (kind.min != null && d < kind.min) || (kind.max != null && d > kind.max) -> "$k=$v is outside ${kind.min}..${kind.max}"
                    else -> null
                }
            }
            Bool -> if ((v as? JsonPrimitive)?.takeIf { !it.isString }?.booleanOrNull == null) "$k=$v is not a bool" else null
            ListKind -> if (v !is JsonArray) "$k=$v is not a list" else null
            ObjKind -> if (v !is JsonObject) "$k=$v is not an object" else null
        }
    }

    fun validate(p: Preset): List<String> {
        val errs = mutableListOf<String>()
        if (p.costing != "auto") errs += "${p.id}: only auto costing is supported"
        val groups = mutableListOf("costing_options" to p.costingOptions)
        p.fallbackCostingOptions?.let { groups += "fallback_costing_options" to it }
        if (p.isPool) {
            val m = p.maxTimeIncrease
            if (m == null || m < 0 || m > 1) errs += "${p.id}: max_time_increase must be 0..1"
            p.maxTimeExtraS?.let { if (it < 0) errs += "${p.id}: max_time_extra_s must be a number of seconds >= 0" }
            if (p.candidates.isEmpty()) errs += "${p.id}: select rule without candidates"
            p.candidates.forEachIndexed { i, c -> groups += "candidates[$i]" to c }
            p.fallbackCandidates.forEachIndexed { i, c -> groups += "fallback_candidates[$i]" to c }
            if (p.fallbackCandidates.isNotEmpty() && p.fallbackCandidates.size != p.candidates.size) {
                errs += "${p.id}: fallback_candidates must match candidates one to one"
            }
        }
        if (p.fallbackWhen.isNotEmpty() && p.fallbackCostingOptions == null && p.fallbackCandidates.isEmpty()) {
            errs += "${p.id}: fallback_when without fallback_costing_options"
        }
        for ((name, opts) in groups) errs += validateOptions(opts).map { "${p.id} $name: $it" }
        return errs
    }
}

/**
 * presets.json `every_request` (docs/architecture.md 2.11, batch C1; the same rules as
 * route_lib.request, route_lib.route and route_lib.arrival_side):
 *  - [costingOptions] go under the preset's costing options in every route request;
 *  - [stopOptions] (search_filter, search_cutoff) go on every location after the first, so a stop is
 *    tied to a street, not to the alley or the car park behind it; when no street passes within the
 *    cutoff the engine answers 171 and the planner asks again without them ([untie]);
 *  - [tieCheck] (batch E2, docs/next-round.md item 19): the end of each stop is decided once per plan
 *    with the plan's Fastest request: an end is kept when its route is at most [TieCheck.maxExtraS]
 *    slower than the route to the next end on the list, has no more U-turns and its last
 *    [TieCheck.tailM] metres use none of [TieCheck.neverUses] (route_lib.decide_ends);
 *  - [arrivalSide]: a route that arrives with a stop on the left is asked again with
 *    preferred_side and street_side_cutoff on those stops (cutoff tertiary: on a residential
 *    street or below the engine ignores the side) and kept when it arrives on the right, costs at
 *    most [ArrivalSide.maxExtraS] more and has at most [ArrivalSide.maxExtraManeuvers] more
 *    guidance instructions (batch D: one block at most);
 *  - [addressStreet]: a place whose address names its street is tied to that street when it is
 *    within reach ([io.github.spysyweeb.beamline.routing.planner.AddressTie]);
 *  - [lot] (batch E): a point of interest ends in its own lot when a driveway or parking aisle is
 *    within [Lot.withinM] and at least [Lot.streetMarginM] nearer than every street (route_lib.lot_tie_point);
 *  - [leftOut] (batch E2): the drive-through lanes and alleys a point of interest's probes see are left
 *    out of every route of the plan (exclude_polygons; route_lib.left_out_rings).
 */
class EveryRequest(
    val costingOptions: JsonObject,
    val stopOptions: JsonObject,
    val arrivalSide: ArrivalSide?,
    val tieCheck: TieCheck? = null,
    val addressStreet: AddressStreet? = null,
    val lot: Lot? = null,
    val leftOut: LeftOut? = null,
    val entrance: Entrance? = null,
) {
    /**
     * presets.json every_request.entrance (docs/next-round.md item 35): the place where a route's last [tailM]
     * metres leave the public roads for a lot (the first edge whose use is in [lotUses] and all after it) is
     * closed with a square of 2 x [boxHalfM] put [intoM] along the way inside, and the route asked again; the
     * other way in is taken when it is at least [minGainS] faster and costs at most [withinCost] times as much.
     */
    class Entrance(
        val tailM: Int, val lotUses: List<String>, val intoM: Int, val boxHalfM: Int, val minGainS: Double, val withinCost: Double,
        /** The other route must end within this of where the first one ends (the square must not have moved the stop). */
        val sameEndM: Int,
    )

    /** [maxExtraManeuvers] null: no such limit (batch D added it: 2). */
    class ArrivalSide(val locationOptions: JsonObject, val maxExtraS: Double, val maxExtraManeuvers: Int? = null)
    /**
     * [tailM] and [neverUses] (batch E2): the last metres of the way to an end judged by their edge uses (an alley,
     * a drive-through lane). [doorWalkMPerS] (docs/next-round.md item 28; door_walk_m_per_s): a lot found around
     * the place's door may be slower by [maxExtraS] plus the walk it saves at this speed; null: [maxExtraS] alone.
     */
    class TieCheck(val maxExtraS: Double, val tailM: Int = 300, val neverUses: List<String> = emptyList(), val doorWalkMPerS: Double? = null)
    /** presets.json every_request.left_out (batch E2): a square of 2 x [boxHalfM] at the middle of each edge of one of [uses] within [withinM], [clearM] from every other edge; at most [maxRings] per request. */
    class LeftOut(val uses: List<String>, val withinM: Int, val boxHalfM: Int, val clearM: Int, val maxRings: Int)
    class AddressStreet(val withinM: Int, val probeStepM: Int, val probeRadiusM: Int, val endMarginM: Int, val words: Map<String, String>)
    /**
     * presets.json every_request.lot (batch E): [uses] a subset of [LOT_USES]; the probe's finer grid every
     * [probeStepM] within [probeWithinM]. [doorUses] (docs/next-round.md item 28; door_uses, a subset of
     * [DOOR_USES]; [uses] when the file has none): the uses when the lot is looked for around the place's door.
     */
    class Lot(val withinM: Int, val uses: List<String>, val streetMarginM: Int, val probeStepM: Int, val probeWithinM: Int,
        val doorUses: List<String> = uses)

    /** The road classes the stop tie allows (search_filter max..min road class), for the address street. */
    val tieClasses: List<String>
        get() {
            val sf = stopOptions["search_filter"]?.jsonObject
            val hi = ROAD_CLASS_ORDER.indexOf(sf?.get("max_road_class")?.jsonPrimitive?.content ?: "motorway")
            val lo = ROAD_CLASS_ORDER.indexOf(sf?.get("min_road_class")?.jsonPrimitive?.content ?: "service_other")
            return ROAD_CLASS_ORDER.subList(hi, lo + 1)
        }

    /** [req] without [stopOptions] (each key a location after the first holds with the rule's value); null when none holds any. */
    fun untie(req: JsonObject): JsonObject? {
        val locs = req["locations"]?.jsonArray ?: return null
        var changed = false
        val out = locs.mapIndexed { i, l ->
            val o = l.jsonObject
            val drop = if (i == 0) emptySet() else stopOptions.filter { (k, v) -> o[k] == v }.keys
            if (drop.isNotEmpty()) {
                changed = true
                JsonObject(o.filterKeys { it !in drop })
            } else o
        }
        return if (changed) JsonObject(req + ("locations" to JsonArray(out))) else null
    }

    companion object {
        val NONE = EveryRequest(JsonObject(emptyMap()), JsonObject(emptyMap()), null)
        /** route_lib.LOT_USES: the edge uses a point of interest may end on (never an alley or a drive-through lane). */
        val LOT_USES = listOf("driveway", "parking_aisle")
        /** route_lib.DOOR_USES: the same around a place's door, where a plain service road is the lane along the front. */
        val DOOR_USES = LOT_USES + "service_road"
        /** route_lib.LOT_SIDE: the preferred_side of a stop that ends in a lot (Valhalla's default; it marks the stop). */
        const val LOT_SIDE = "either"
        /** route_lib.MAX_RINGS: exclude_polygons rings of 5 vertices a request may carry (the app's Valhalla config allows 100 vertices). */
        const val MAX_RINGS = 20
        /** route_lib.ROAD_CLASSES, from the biggest road to the smallest. */
        val ROAD_CLASS_ORDER = listOf("motorway", "trunk", "primary", "secondary", "tertiary", "unclassified", "residential", "service_other")
        private val ROAD_CLASSES = ROAD_CLASS_ORDER.toSet()

        fun parse(o: JsonObject): EveryRequest {
            val costing = o["costing_options"]?.jsonObject ?: JsonObject(emptyMap())
            val errs = PresetValidator.validateOptions(costing).map { "every_request costing_options: $it" }.toMutableList()
            val stops = o["stops"]?.jsonObject ?: JsonObject(emptyMap())
            for ((k, v) in stops) when (k) {
                "search_filter" -> for ((fk, fv) in v.jsonObject) {
                    val p = fv as? JsonPrimitive
                    when (fk) {
                        "min_road_class", "max_road_class" -> if (p == null || !p.isString || p.content !in ROAD_CLASSES) errs += "every_request stops search_filter: bad $fk=$fv"
                        "exclude_tunnel", "exclude_bridge", "exclude_ramp", "exclude_closures" -> if (p == null || p.isString || p.booleanOrNull == null) errs += "every_request stops search_filter: bad $fk=$fv"
                        else -> errs += "every_request stops search_filter: bad $fk=$fv"
                    }
                }
                "search_cutoff" -> if ((v as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it > 0 } == null) errs += "every_request stops search_cutoff must be a positive whole number of metres"
                else -> errs += "every_request stops: $k is not supported by the app planner"
            }
            val side = o["arrival_side"]?.jsonObject?.let { s ->
                val ps = s["preferred_side"]?.jsonPrimitive?.content
                if (ps != "same" && ps != "opposite") errs += "every_request arrival_side preferred_side must be same or opposite"
                val cut = s["street_side_cutoff"]?.jsonPrimitive?.content ?: "service_other"
                if (cut !in ROAD_CLASSES) errs += "every_request arrival_side street_side_cutoff is not a road class"
                val mx = s["max_extra_s"]?.jsonPrimitive?.takeIf { !it.isString }?.doubleOrNull
                if (mx == null || mx < 0) errs += "every_request arrival_side max_extra_s must be a number >= 0"
                val mm = s["max_extra_maneuvers"]?.let { v ->
                    val n = (v as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull
                    if (n == null || n < 0) errs += "every_request arrival_side max_extra_maneuvers must be a whole number >= 0"
                    n
                }
                ArrivalSide(JsonObject(s.filterKeys { it == "preferred_side" || it == "street_side_cutoff" }), mx ?: 0.0, mm)
            }
            val tie = o["tie_check"]?.jsonObject?.let { s ->
                val mx = s["max_extra_s"]?.jsonPrimitive?.takeIf { !it.isString }?.doubleOrNull
                if (mx == null || mx < 0) errs += "every_request tie_check max_extra_s must be a number >= 0"
                val tail = if (s["tail_m"] == null) 300 else (s["tail_m"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it > 0 }
                if (tail == null) errs += "every_request tie_check tail_m must be a positive whole number of metres"
                val never = if (s["never_uses"] == null) emptyList() else (s["never_uses"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf { c -> c.isNotEmpty() } }
                if (never == null || never.any { it == null }) errs += "every_request tie_check never_uses must be a list of Valhalla edge uses"
                val walk = if (s["door_walk_m_per_s"] == null) null else (s["door_walk_m_per_s"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it > 0 }
                if (s["door_walk_m_per_s"] != null && walk == null) errs += "every_request tie_check door_walk_m_per_s must be a number > 0"
                TieCheck(mx ?: 0.0, tail ?: 300, never?.filterNotNull() ?: emptyList(), walk)
            }
            val address = o["address_street"]?.jsonObject?.let { s ->
                fun metres(k: String): Int = (s[k] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it > 0 }
                    ?: 1.also { errs += "every_request address_street $k must be a positive whole number of metres" }
                val words = if (s["words"] == null) emptyMap() else (s["words"] as? JsonObject)?.mapValues { (_, v) -> (v as? JsonPrimitive)?.takeIf { it.isString }?.content }
                if (words == null || words.values.any { it == null }) errs += "every_request address_street words must map words to words"
                AddressStreet(metres("within_m"), metres("probe_step_m"), metres("probe_radius_m"), metres("end_margin_m"),
                    words?.mapValues { it.value.orEmpty() } ?: emptyMap())
            }
            val lot = o["lot"]?.jsonObject?.let { s ->
                fun metres(k: String): Int = (s[k] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it > 0 }
                    ?: 1.also { errs += "every_request lot $k must be a positive whole number of metres" }
                val uses = (s["uses"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                if (uses.isNullOrEmpty() || uses.any { it !in LOT_USES }) errs += "every_request lot uses must be a list of ${LOT_USES.joinToString(", ")}"
                val doorUses = if (s["door_uses"] == null) null else (s["door_uses"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content }
                if (s["door_uses"] != null && (doorUses.isNullOrEmpty() || doorUses.any { it !in DOOR_USES })) {
                    errs += "every_request lot door_uses must be a list of ${DOOR_USES.joinToString(", ")}"
                }
                if (address == null) errs += "every_request lot needs address_street (its probe)"
                val lotUses = uses?.filterNotNull() ?: emptyList()
                Lot(metres("within_m"), lotUses, metres("street_margin_m"), metres("probe_step_m"), metres("probe_within_m"),
                    doorUses?.filterNotNull() ?: lotUses)
            }
            val drive = o["left_out"]?.jsonObject?.let { s ->
                fun metres(k: String): Int = (s[k] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it > 0 }
                    ?: 1.also { errs += "every_request left_out $k must be a positive whole number of metres" }
                val uses = (s["uses"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf { c -> c.isNotEmpty() } }
                if (uses.isNullOrEmpty() || uses.any { it == null }) errs += "every_request left_out uses must be a list of Valhalla edge uses"
                val rings = (s["max_rings"] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it in 1..MAX_RINGS }
                if (rings == null) errs += "every_request left_out max_rings must be a whole number from 1 to $MAX_RINGS"
                LeftOut(uses?.filterNotNull() ?: emptyList(), metres("within_m"), metres("box_half_m"), metres("clear_m"), rings ?: 1)
            }
            val entrance = o["entrance"]?.jsonObject?.let { s ->
                fun metres(k: String): Int = (s[k] as? JsonPrimitive)?.takeIf { !it.isString }?.intOrNull?.takeIf { it > 0 }
                    ?: 1.also { errs += "every_request entrance $k must be a positive whole number of metres" }
                val uses = (s["lot_uses"] as? JsonArray)?.map { (it as? JsonPrimitive)?.takeIf { p -> p.isString }?.content?.takeIf { c -> c.isNotEmpty() } }
                if (uses.isNullOrEmpty() || uses.any { it == null }) errs += "every_request entrance lot_uses must be a list of Valhalla edge uses"
                val gain = (s["min_gain_s"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it >= 0.0 }
                if (gain == null) errs += "every_request entrance min_gain_s must be a number of seconds, 0 or more"
                val within = (s["within_cost"] as? JsonPrimitive)?.takeIf { !it.isString }?.doubleOrNull?.takeIf { it >= 1.0 }
                if (within == null) errs += "every_request entrance within_cost must be a number, 1 or more"
                Entrance(metres("tail_m"), uses?.filterNotNull() ?: emptyList(), metres("into_m"), metres("box_half_m"), gain ?: 0.0, within ?: 1.0,
                    metres("same_end_m"))
            }
            require(errs.isEmpty()) { "presets.json: " + errs.joinToString("; ") }
            return EveryRequest(costing, stops, side, tie, address, lot, drive, entrance)
        }
    }
}

internal fun resourceText(name: String): String =
    (PresetSet::class.java.getResourceAsStream("/io/github/spysyweeb/beamline/routing/$name")
        ?: throw IllegalStateException("resource $name missing (run android/routing/scripts/sync_data.py)"))
        .use { it.readBytes().toString(Charsets.UTF_8) }
