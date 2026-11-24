package io.github.spysyweeb.beamline.app.online

import io.github.spysyweeb.beamline.search.foldText
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * A place near the pressed point (batch O3, docs/online-design.md 4.3, docs/next-round.md item 25),
 * from the installed pack ([fromOsm] false: its name, category and OSM id) or from OSM's own map of the
 * spot ([fromOsm] true: its current [tags] and [version]). [inBuilding]: in the same building outline
 * as the pressed point (OSM's map only). [toString] prints none of it (PV2).
 */
data class NearbyPlace(
    val osm: String?,
    val name: String?,
    val typeCode: String?,
    val typeWords: String?,
    val lat: Double,
    val lon: Double,
    val distanceM: Double,
    val fromOsm: Boolean,
    val tags: Map<String, String> = emptyMap(),
    val version: Int? = null,
    val inBuilding: Boolean = false,
    /** A pack place's own values (hours, phone, website) for the edit form when OSM cannot be read. */
    val packHours: String? = null,
    val packPhone: String? = null,
    val packWebsite: String? = null,
    /** The pack's own OSM object (the map tiles' feature); differs from [osm] only for a test object (4.6). */
    val packOsm: String? = null,
) {
    override fun toString(): String = "NearbyPlace"

    /** A business (a type key, or a closed one), as far as the source says (a pack POI with a category counts). */
    val business: Boolean get() = if (fromOsm) BusinessTags.isBusiness(tags) else typeCode != null && BusinessTags.isBusinessType(typeCode)

    /** "Subway (Fast food)", "Primo Tailoring", "an unnamed ATM". */
    fun line(): String = when {
        !name.isNullOrBlank() && typeWords != null -> "$name ($typeWords)"
        !name.isNullOrBlank() -> name
        typeWords != null -> "An unnamed " + typeWords.lowercase()
        else -> "A place without a name"
    }
}

/**
 * The rules of the spot check (batch O3), plain Kotlin:
 *
 *  - **Duplicates** (before upload, 4.3; again when an outbox item goes): a place within [DUP_M] (50 m,
 *    the design's "about 50 m") with the same name (folded: case, accents, punctuation and "&" do not
 *    count), or of the same type with a similar name (one contains the other, or at most two letters
 *    differ); for a type without names (an ATM, a restroom) the same type within [DUP_UNNAMED_M] (25 m:
 *    two ATMs a block apart are two ATMs). Places the user already decided about are left out.
 *  - **Already on the map here** (item 25 b): the businesses within [HERE_M] (30 m) or in the same
 *    building outline as the pressed point, any name, nearest first, at most [HERE_MAX]; when there is
 *    none, an object that is only an address within [ADDRESS_M] (20 m) or in the same building: the
 *    new place's tags go onto it.
 *  - The pack and OSM are merged by OSM id; OSM's current object wins (it is newer than the pack).
 */
object SpotCheck {
    const val DUP_M = 50.0
    const val DUP_UNNAMED_M = 25.0
    const val HERE_M = 30.0
    const val HERE_MAX = 5
    const val ADDRESS_M = 20.0
    /** How far around the point OSM's map is asked for (the box is twice this wide and high). */
    const val BOX_M = 60.0

    /** The pack's and OSM's places merged: one per OSM id, OSM's when both have it; nearest first. */
    fun merge(pack: List<NearbyPlace>, osm: List<NearbyPlace>?): List<NearbyPlace> {
        if (osm == null) return pack.sortedBy { it.distanceM }
        val ids = osm.mapNotNull { it.osm }.toSet()
        val packById = pack.filter { it.osm != null }.associateBy { it.osm }
        val fromOsm = osm.map { o -> packById[o.osm]?.let { p -> o.copy(packOsm = p.packOsm ?: p.osm) } ?: o }
        return (fromOsm + pack.filter { it.osm == null || it.osm !in ids }).sortedBy { it.distanceM }
    }

    fun duplicates(type: AddableType, name: String, places: List<NearbyPlace>, decided: Set<String> = emptySet()): List<NearbyPlace> {
        val n = foldText(name)
        return places.filter { p ->
            if (p.osm != null && p.osm in decided) return@filter false
            val sameType = p.typeCode == type.code || (p.fromOsm && type.tags.all { (k, v) -> p.tags[k] == v })
            val pn = foldText(p.name)
            when {
                n.isNotEmpty() && pn == n -> p.distanceM <= DUP_M
                n.isNotEmpty() && sameType && similar(n, pn) -> p.distanceM <= DUP_M
                n.isEmpty() && sameType -> p.distanceM <= DUP_UNNAMED_M
                else -> false
            }
        }
    }

    /** The businesses already at the spot (item 25 b). */
    fun here(places: List<NearbyPlace>): List<NearbyPlace> =
        places.filter { it.business && (it.distanceM <= HERE_M || it.inBuilding) }.take(HERE_MAX)

    /** An object that is only an address at the spot, when no business is there. */
    fun bareAddress(places: List<NearbyPlace>): NearbyPlace? =
        places.firstOrNull { it.fromOsm && it.osm?.startsWith("n") == true && BusinessTags.isBareAddress(it.tags) && (it.distanceM <= ADDRESS_M || it.inBuilding) }

    /** Two folded names are alike: one holds the other (4 letters or more), or they differ in at most two letters (one for short names). */
    fun similar(a: String, b: String): Boolean {
        if (a.isEmpty() || b.isEmpty()) return false
        if (a == b) return true
        if (minOf(a.length, b.length) >= 4 && (a.contains(b) || b.contains(a))) return true
        val limit = when {
            minOf(a.length, b.length) >= 6 -> 2
            minOf(a.length, b.length) >= 4 -> 1
            else -> 0
        }
        return limit > 0 && kotlin.math.abs(a.length - b.length) <= limit && distance(a, b) <= limit
    }

    private fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            prev = cur
        }
        return prev[b.length]
    }

    /** Metres between two points (equirectangular: exact enough within a few hundred metres). */
    fun metres(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dy = (lat2 - lat1) * 111_195.0
        val dx = (lon2 - lon1) * 111_195.0 * kotlin.math.cos(Math.toRadians((lat1 + lat2) / 2))
        return kotlin.math.sqrt(dx * dx + dy * dy)
    }

    /** The box OSM's map is asked for: west, south, east, north. */
    fun box(lat: Double, lon: Double, m: Double = BOX_M): DoubleArray {
        val dLat = m / 111_195.0
        val dLon = dLat / kotlin.math.cos(Math.toRadians(lat)).coerceAtLeast(0.01)
        return doubleArrayOf(lon - dLon, lat - dLat, lon + dLon, lat + dLat)
    }

    /**
     * The tagged places of an OSM map answer (`GET /api/0.6/map.json`) around ([lat], [lon]): nodes with
     * a name, a type key or an address, and closed ways (their centre) that are businesses; each marked
     * when it lies in the same building outline as the point. Null when the answer cannot be read.
     */
    fun fromMap(json: String, lat: Double, lon: Double): List<NearbyPlace>? = try {
        val els = (Json.parseToJsonElement(json) as JsonObject)["elements"] as JsonArray
        val coords = HashMap<Long, Pair<Double, Double>>()
        val nodes = ArrayList<JsonObject>()
        val ways = ArrayList<JsonObject>()
        for (e in els) {
            val o = e as? JsonObject ?: continue
            when ((o["type"] as? JsonPrimitive)?.content) {
                "node" -> {
                    val id = (o["id"] as JsonPrimitive).longOrNull ?: continue
                    val la = (o["lat"] as? JsonPrimitive)?.doubleOrNull ?: continue
                    val lo = (o["lon"] as? JsonPrimitive)?.doubleOrNull ?: continue
                    coords[id] = la to lo
                    if (o["tags"] != null) nodes += o
                }
                "way" -> if (o["tags"] != null) ways += o
            }
        }
        fun tagsOf(o: JsonObject) = (o["tags"] as JsonObject).mapValues { (it.value as JsonPrimitive).content }
        fun ring(o: JsonObject) = (o["nodes"] as? JsonArray)?.mapNotNull { (it as JsonPrimitive).longOrNull?.let(coords::get) }.orEmpty()
        val buildings = ways.filter { "building" in tagsOf(it) }.map(::ring).filter { it.size >= 4 && it.first() == it.last() }
        val home = buildings.filter { inside(lat, lon, it) }
        fun sameBuilding(la: Double, lo: Double) = home.any { inside(la, lo, it) }
        val out = ArrayList<NearbyPlace>()
        for (o in nodes) {
            val t = tagsOf(o)
            if (!BusinessTags.isBusiness(t) && !BusinessTags.isBareAddress(t) && "name" !in t) continue
            if (t.keys.any { it == "highway" || it == "railway" || it == "public_transport" } && !BusinessTags.isBusiness(t)) continue
            val (la, lo) = coords[(o["id"] as JsonPrimitive).longOrNull!!]!!
            out += place("n", o, t, la, lo, lat, lon, sameBuilding(la, lo))
        }
        for (o in ways) {
            val t = tagsOf(o)
            if (!BusinessTags.isBusiness(t) && !BusinessTags.isBareAddress(t)) continue
            val r = ring(o)
            if (r.isEmpty()) continue
            val la = r.map { it.first }.average()
            val lo = r.map { it.second }.average()
            val closed = r.size >= 4 && r.first() == r.last()
            out += place("w", o, t, la, lo, lat, lon, closed && inside(lat, lon, r) || sameBuilding(la, lo))
        }
        out.sortedBy { it.distanceM }
    } catch (_: Exception) {
        null
    }

    private fun place(letter: String, o: JsonObject, t: Map<String, String>, la: Double, lo: Double, lat: Double, lon: Double, inB: Boolean) = NearbyPlace(
        osm = letter + (o["id"] as JsonPrimitive).longOrNull, name = t["name"], typeCode = PlaceTypes.of(t)?.code, typeWords = PlaceTypes.words(t),
        lat = la, lon = lo, distanceM = metres(lat, lon, la, lo), fromOsm = true, tags = t, version = (o["version"] as? JsonPrimitive)?.intOrNull,
        inBuilding = inB,
    )

    /** Ray casting: whether ([lat], [lon]) lies inside the closed [ring] of (lat, lon) points. */
    fun inside(lat: Double, lon: Double, ring: List<Pair<Double, Double>>): Boolean {
        var c = false
        var j = ring.size - 1
        for (i in ring.indices) {
            val (yi, xi) = ring[i]
            val (yj, xj) = ring[j]
            if ((yi > lat) != (yj > lat) && lon < (xj - xi) * (lat - yi) / (yj - yi) + xi) c = !c
            j = i
        }
        return c
    }
}
