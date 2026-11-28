package io.github.spysyweeb.beamline.search.address

import io.github.spysyweeb.beamline.search.Py
import io.github.spysyweeb.beamline.search.RuleFiles
import io.github.spysyweeb.beamline.search.StreetShape
import io.github.spysyweeb.beamline.search.db.SqlDb
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

/** One answer of the address lookup (addrlib.lookup result dict). */
data class AddressHit(
    val lat: Double,
    val lon: Double,
    /** "point", "interpolated" or "street" */
    val accuracy: String,
    val source: String,
    val houseNumber: String?,
    val street: String,
    val streetKey: String,
    val zip: String?,
    val city: String?,
    val municipality: String?,
    val countyFips: String?,
    /** exact_key, highway_alternative, core_key, key_variant, fuzzy_key */
    val match: String,
    val score: Double,
    /** null when no town or ZIP was typed; false: shown as a guess (the street is elsewhere). */
    val localityMatch: Boolean?,
    val stateMismatch: Boolean = false,
    val houseNumberRejected: String? = null,
    val unit: String? = null,
    val addressId: Long? = null,
    val baseNumberOf: String? = null,
    val edgeId: Long? = null,
    val side: String? = null,
    val hnGap: Long? = null,
    /** Item 55 ([AddressIndex.alongStreet]): how the answer's street is named next to the street shown (NAME_OWN first). */
    val alongName: Int? = null,
    /** Item 55: metres from the answer (a point; a range's point before its side offset) to the street shown's line. */
    val alongLineM: Double? = null,
)

/**
 * Port of packbuilder/addresses/addrlib/lookup.py: resolves typed or parsed addresses against a
 * pack's address file (pa-addresses-slim.sqlite on the device).
 *
 * Contract (docs/experiments/04-addresses.md, section 3): every result carries accuracy, match
 * and locality_match. When the typed street is not in the typed town, the best match elsewhere
 * in the state comes back with localityMatch = false and must be shown as a guess. A typed state
 * other than the pack's sets stateMismatch (and localityMatch = false). Input without a street
 * returns nothing (place search is the search engine's job). Works on points-only and
 * ranges-only files.
 *
 * The file must have been built with the normaliser table version this port implements
 * ([StreetTables.version]); any other version is refused at open time.
 *
 * Safe to share between threads (lookups are serialised on the one connection).
 */
class AddressIndex(
    path: String,
    val normalizer: AddressNormalizer = AddressNormalizer(),
    private val cfg: JsonObject = RuleFiles.json(RuleFiles.LOOKUP_CONFIG),
) : AutoCloseable {
    private val db = SqlDb.openReadOnly(path)
    val parser = AddressParser(normalizer)
    private val tables: Set<String>
    val knownPlaces: Set<String>
    /** The pack's state ("pa"), from meta. */
    val state: String?
    /** meta.normalisation_table_version of the file. */
    val tableVersion: String?
    /** meta.key_fingerprint (docs/architecture.md 2.11); null in files built before round 3. */
    val keyFingerprint: String?
    /** Shared by threads (the connection itself is serialised in [SqlDb]). */
    private val placeZipsCache = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

    /**
     * Item 55 ([alongStreet]): the localities of each municipality key, read once (some files have no index on
     * localities.muni_key, and the table is small: 12,843 rows in PA).
     */
    /** Item 55: the streets of one municipality: ids, keys, cores and representative points (lat_e6, lon_e6; Long.MIN_VALUE for none). */
    private class MuniStreets(val ids: LongArray, val keys: Array<String>, val cores: Array<String>, val lat: LongArray, val lon: LongArray)

    /** Item 55: [MuniStreets] of the last [MUNI_CACHE] municipalities (keys) asked for. */
    private val muniStreetCache = object : LinkedHashMap<String, MuniStreets>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MuniStreets>?): Boolean = size > MUNI_CACHE
    }

    private fun muniStreets(muniKey: String): MuniStreets? {
        synchronized(muniStreetCache) { muniStreetCache[muniKey] }?.let { return it }
        val locs = muniLocalities[muniKey] ?: return null
        val rows = ArrayList<Array<Any?>>()
        for (chunk in locs.asList().chunked(CHUNK)) {
            rows += db.query("SELECT id, street_key, street_core, lat_e6, lon_e6 FROM streets WHERE locality_id IN (${marks(chunk.size)})", chunk)
        }
        val m = MuniStreets(
            LongArray(rows.size) { rows[it][0] as Long }, Array(rows.size) { rows[it][1] as String }, Array(rows.size) { rows[it][2] as String },
            LongArray(rows.size) { rows[it][3] as Long? ?: Long.MIN_VALUE }, LongArray(rows.size) { rows[it][4] as Long? ?: Long.MIN_VALUE },
        )
        synchronized(muniStreetCache) { muniStreetCache[muniKey] = m }
        return m
    }

    private val muniLocalities: Map<String, LongArray> by lazy {
        val m = HashMap<String, ArrayList<Long>>()
        for (r in db.query("SELECT id, muni_key FROM localities WHERE muni_key IS NOT NULL AND muni_key != ''")) {
            m.getOrPut(r[1] as String) { ArrayList() }.add(r[0] as Long)
        }
        m.mapValues { it.value.toLongArray() }
    }

    private val locW = cfg["locality_score"]!!.jsonObject
    private val matchW = cfg["match_score"]!!.jsonObject
    private val accW = cfg["accuracy_score"]!!.jsonObject
    private val srcW = cfg["source_score"]!!.jsonObject
    private val fuzzy = cfg["fuzzy"]!!.jsonObject
    private val maxHouseNumber = BigInteger.valueOf(cfg["max_house_number"]!!.jsonPrimitive.long)
    private val maxResults = cfg["max_results"]!!.jsonPrimitive.int
    private val offsetM = cfg["interpolation_offset_m"]!!.jsonPrimitive.double

    init {
        try {
            tables = db.query("SELECT name FROM sqlite_master WHERE type = 'table'").map { it[0] as String }.toSet()
            var st: String? = null
            var ver: String? = null
            var fp: String? = null
            if ("meta" in tables) {
                st = (db.first("SELECT value FROM meta WHERE key = 'state'")?.get(0) as String?)?.lowercase()?.ifEmpty { null }
                ver = db.first("SELECT value FROM meta WHERE key = 'normalisation_table_version'")?.get(0)?.toString()
                fp = db.first("SELECT value FROM meta WHERE key = 'key_fingerprint'")?.get(0)?.toString()
            }
            state = st
            tableVersion = ver
            keyFingerprint = fp
            if (ver != normalizer.t.version) {
                throw IllegalStateException("address file $path was built with normaliser table version $ver; " +
                    "this app implements version ${normalizer.t.version}. Install a matching pack.")
            }
            knownPlaces = db.query("SELECT DISTINCT place_key FROM places").map { it[0] as String }.toSet()
        } catch (e: Throwable) {
            db.close()
            throw e
        }
    }

    override fun close() = db.close()

    private class Street(
        val id: Long, val street: String, val key: String, val zip: String?, val city: String?, val cityKey: String?,
        val municipality: String?, val muniKey: String?, val countyFips: String?, val latE6: Long?, val lonE6: Long?,
    )

    private fun placeZips(ck: String): Set<String> = placeZipsCache.getOrPut(ck) {
        db.query("SELECT DISTINCT zip FROM places WHERE place_key = ? AND zip IS NOT NULL", ck).map { it[0] as String }.toSet()
    }

    private fun streets(where: String, args: List<Any?>): List<Street> =
        db.query("SELECT s.id, s.street, s.street_key, l.zip, l.city, l.city_key, l.municipality, l.muni_key, " +
            "l.county_fips, s.lat_e6, s.lon_e6 FROM streets s JOIN localities l ON l.id = s.locality_id WHERE " + where, args)
            .map { r ->
                Street(r[0] as Long, r[1] as String, r[2] as String, r[3] as String?, r[4] as String?, r[5] as String?,
                    r[6] as String?, r[7] as String?, r[8] as String?, r[9] as Long?, r[10] as Long?)
            }

    private fun locScore(row: Street, z: String?, ck: String?): Double {
        var s = 0.0
        if (z != null && row.zip == z) s += locW.d("zip")
        if (ck != null) {
            if (row.cityKey == ck) s += locW.d("city")
            else if (row.muniKey == ck) s += locW.d("municipality")
            else if (row.zip != null && row.zip in placeZips(ck)) s += locW.d("zip_of_named_place")
        }
        return s
    }

    private fun localityStreets(z: String?, ck: String?): List<Street> {
        val conds = ArrayList<String>()
        val args = ArrayList<Any?>()
        if (z != null) {
            conds.add("l.zip = ?"); args.add(z)
        }
        if (ck != null) {
            conds.add("l.city_key = ? OR l.muni_key = ?"); args.add(ck); args.add(ck)
            val zs = placeZips(ck).sorted()
            if (zs.isNotEmpty()) {
                conds.add("l.zip IN (${zs.joinToString(",") { "?" }})"); args.addAll(zs)
            }
        }
        if (conds.isEmpty()) return emptyList()
        return streets("(" + conds.joinToString(") OR (") + ")", args)
    }

    private fun keyCandidates(key: String, exactKind: String): Pair<List<Pair<Street, String>>, List<Pair<Street, String>>> {
        val exact = ArrayList(streets("s.street_key = ?", listOf(key)).map { it to exactKind })
        val alts = normalizer.highwayAlternatives(key)
        if (alts.isNotEmpty()) {
            val k = if (exactKind == "exact_key") "highway_alternative" else exactKind
            exact += streets("s.street_key IN (${alts.joinToString(",") { "?" }})", alts).map { it to k }
        }
        val coreKind = if (exactKind == "exact_key") "core_key" else exactKind
        val core = streets("s.street_core = ? AND s.street_key != ?", listOf(normalizer.streetCore(key), key))
            .filter { compatible(key, it.key) }.map { it to coreKind }
        return exact to core
    }

    /** (leading directional, trailing type, trailing directional) of a street key. */
    private fun parts(key: String): Triple<String?, String?, String?> {
        val t = normalizer.t
        val toks = Py.split(key)
        val pre = if (toks.size > 1 && toks[0] in t.dir) toks[0] else null
        val post = if (toks.size > 1 && toks.last() in t.dir) toks.last() else null
        val body = toks.subList(if (pre != null) 1 else 0, if (post != null) toks.size - 1 else toks.size)
        val typ = if (body.size > 1 && body.last() in t.typeCanon) body.last() else null
        return Triple(pre, typ, post)
    }

    /** A core-key candidate may fill in parts the user left out, but not contradict typed ones. */
    fun compatible(typed: String, cand: String): Boolean {
        val a = parts(typed)
        val b = parts(cand)
        fun ok(x: String?, y: String?) = x == null || y == null || x == y
        return ok(a.first, b.first) && ok(a.second, b.second) && ok(a.third, b.third)
    }

    private fun candidateTiers(key: String, z: String?, ck: String?): List<Pair<List<Pair<Street, String>>, Boolean>> {
        val (exact, core) = keyCandidates(key, "exact_key")
        fun inloc(t: List<Pair<Street, String>>) = t.filter { locScore(it.first, z, ck) > 0 }
        var variant: List<Pair<Street, String>> = emptyList()
        var variantInloc: List<Pair<Street, String>> = emptyList()
        val hasLoc = z != null || ck != null
        for (v in normalizer.keyVariants(key)) {
            val (e2, c2) = keyCandidates(v, "key_variant")
            val rows = e2 + c2
            if (rows.isNotEmpty() && variant.isEmpty()) variant = rows
            if (hasLoc && variantInloc.isEmpty()) variantInloc = inloc(rows)
            if (variant.isNotEmpty() && (variantInloc.isNotEmpty() || !hasLoc)) break
        }
        if (!hasLoc) return listOf(exact, core, variant).filter { it.isNotEmpty() }.map { it to false }
        val tiers = arrayListOf(inloc(exact) to true, inloc(core) to true, variantInloc to true)
        if (tiers.all { it.first.isEmpty() }) {
            val pool = localityStreets(z, ck)
            val limit = if (key.length >= fuzzy["min_key_length_for_2_edits"]!!.jsonPrimitive.int) fuzzy["max_distance"]!!.jsonPrimitive.int else 1
            val dist = HashMap<String, Int>()
            for (r in pool) if (r.key !in dist) dist[r.key] = AddressNormalizer.osaDistance(key, r.key, limit)
            val best = dist.values.minOrNull() ?: (limit + 1)
            if (best <= limit) tiers.add(pool.filter { dist[it.key] == best }.map { it to "fuzzy_key" } to true)
        }
        tiers += listOf(exact to false, core to false, variant to false)
        return tiers.filter { it.first.isNotEmpty() }
    }

    private fun resolveStreets(cands: List<Pair<Street, String>>, hnn: String?, hni: Long?, z: String?, ck: String?): List<AddressHit> {
        val byId = LinkedHashMap<Long, Pair<Street, String>>()
        for (c in cands) byId[c.first.id] = c
        val ids = byId.keys.toList()
        val results = ArrayList<AddressHit>()

        fun add(row: Street, kind: String, lat: Double, lon: Double, acc: String, src: String, hnOut: String?,
                f: (AddressHit) -> AddressHit = { it }) {
            val loc = locScore(row, z, ck)
            val score = loc + matchW.d(kind) + accW.d(acc) + (srcW[src]?.jsonPrimitive?.double ?: 0.0)
            results.add(f(AddressHit(
                lat = lat, lon = lon, accuracy = acc, source = src, houseNumber = hnOut, street = row.street,
                streetKey = row.key, zip = row.zip, city = row.city, municipality = row.municipality,
                countyFips = row.countyFips, match = kind, score = Py.round(score, 2),
                localityMatch = if (z != null || ck != null) loc > 0 else null,
            )))
        }

        val qmarks = ids.joinToString(",") { "?" }
        val hasPoints = "addresses" in tables
        val hasRanges = "ranges" in tables
        if (hnn != null && hni != null) {
            if (hasPoints) {
                val tries = listOf(hnn) + (if (hni.toString() != hnn) listOf(hni.toString()) else emptyList())
                for (want in tries) {
                    for (r in db.query("SELECT street_id, hn, unit, lat_e6, lon_e6, source, id FROM addresses " +
                        "WHERE street_id IN ($qmarks) AND hn = ?", ids + want)) {
                        val (row, kind) = byId.getValue(r[0] as Long)
                        add(row, kind, (r[3] as Long) / 1e6, (r[4] as Long) / 1e6, "point", r[5] as String, r[1] as String) {
                            it.copy(unit = r[2] as String?, addressId = r[6] as Long, baseNumberOf = if (want != hnn) hnn else null)
                        }
                    }
                    if (results.isNotEmpty()) break
                }
            }
            val havePoint = results.map { it.streetKey + "|" + (it.zip ?: "None") }.toSet()
            if (hasRanges) {
                for (r in db.query("SELECT street_id, edge_id, side, hn_from, hn_to, parity, source FROM ranges " +
                    "WHERE street_id IN ($qmarks) AND hn_lo <= ? AND hn_hi >= ?", ids + hni + hni)) {
                    val par = r[5] as String?
                    if (par == "O" && hni % 2 == 0L || par == "E" && hni % 2 == 1L) continue
                    val (row, kind) = byId.getValue(r[0] as Long)
                    if (row.key + "|" + (row.zip ?: "None") in havePoint) continue
                    val edge = r[1] as Long
                    val geom = decodeLine(db.first("SELECT geom FROM edges WHERE id = ?", edge)!![0] as ByteArray)
                    val a = r[3] as Long
                    val b = r[4] as Long
                    val frac = if (a == b) 0.5 else (hni - a).toDouble() / (b - a).toDouble()
                    val side = r[2] as String?
                    val (lon, lat) = interpolate(geom, frac, side, offsetM)
                    add(row, kind, lat, lon, "interpolated", r[6] as String, hnn) { it.copy(edgeId = edge, side = side) }
                }
            }
            if (results.isEmpty() && hasPoints) {   // nearest numbered point on the same street(s)
                for (r in db.query("SELECT street_id, hn, hn_int, lat_e6, lon_e6, source FROM addresses " +
                    "WHERE street_id IN ($qmarks) AND hn_int IS NOT NULL ORDER BY abs(hn_int - ?) LIMIT 20", ids + hni)) {
                    val (row, kind) = byId.getValue(r[0] as Long)
                    val gap = kotlin.math.abs((r[2] as Long) - hni)
                    add(row, kind, (r[3] as Long) / 1e6, (r[4] as Long) / 1e6, "street", r[5] as String, r[1] as String) {
                        it.copy(hnGap = gap)
                    }
                }
            }
        }
        if (results.isEmpty()) {   // street only
            for ((row, kind) in cands) {
                if (row.latE6 != null) add(row, kind, row.latE6 / 1e6, row.lonE6!! / 1e6, "street", "osm", null)
            }
        }
        return results.sortedWith(compareBy<AddressHit> { -it.score }.thenBy { it.hnGap ?: 0L })
    }

    /** Resolves parsed fields to ranked positions. */
    fun resolve(p: ParsedAddress, limit: Int? = null): List<AddressHit> {
        val lim = limit ?: maxResults
        val key = p.streetKey
        if (key.isNullOrEmpty()) return emptyList()
        val z = p.zip?.ifEmpty { null }
        val ck = (p.cityKey?.ifEmpty { null } ?: if (!p.city.isNullOrEmpty()) normalizer.placeKey(p.city) else null)?.ifEmpty { null }
        val hn = p.houseNumber
        val parsedHn = if (!hn.isNullOrEmpty()) normalizer.houseNumber(hn) else null
        var hnn = parsedHn?.text
        val rejected = parsedHn != null && parsedHn.int > maxHouseNumber
        var hni: Long? = if (parsedHn != null && !rejected) parsedHn.int.toLong() else null
        if (rejected) {
            hnn = null; hni = null
        }
        val st = p.state?.lowercase()?.ifEmpty { null }
        val mismatch = st != null && state != null && st != state
        var fallback: List<AddressHit> = emptyList()
        var out: List<AddressHit>? = null
        for ((cands, inLoc) in candidateTiers(key, z, ck)) {
            if (fallback.isNotEmpty() && !inLoc && fallback[0].localityMatch == true) break
            val res = resolveStreets(cands, hnn, hni, z, ck)
            if (res.any { it.accuracy != "street" } || (hnn == null && res.isNotEmpty())) {
                out = res.take(lim)
                break
            }
            if (res.isNotEmpty() && fallback.isEmpty()) fallback = res
        }
        return (out ?: fallback.take(lim)).map { x ->
            var y = x
            if (rejected) y = y.copy(houseNumberRejected = hn)
            if (mismatch) y = y.copy(stateMismatch = true, localityMatch = false)
            y
        }
    }

    // ------------------------------------------------------------------ item 55: along a street the search shows
    /**
     * Item 55 (docs/next-round.md; NOT part of the Python reference, which has no such lookup): house number [hn] along
     * a street the search engine shows ([shape]: its box, its line, its keys and its municipality), found by place and
     * not by that street's own name, so no town or ZIP is needed and a street of the name elsewhere never answers.
     *
     * The streets of this file looked at have their representative point in the street's box grown by [ALONG_MARGIN_DEG]
     * (north-south; east-west the same distance), each with how its name relates to the street's ([AddressHit.alongName],
     * closest first): [NAME_OWN] one of the street's own keys (its join key, its other names' keys, never the search
     * pack's derived ones); [NAME_CORE] the join key's core with no part contradicting the join key ([compatible]: not
     * "main ave" for "main st", not "n main st" for "s main st"); [NAME_DERIVED] a derived key ("main st" for "South Main
     * Street", "rte 8" for a ref) or a highway alternative of a key ("twp 8" for "sr 8"); [NAME_SPELLING] a core of
     * [ALONG_SPELLING_MIN_CHARS] letters or more one edit from the join key's core, no part contradicting ("example
     * flynn" for "example flinn"); [NAME_OTHER] any other name. First the street's keys, its derived keys and their
     * highway alternatives, in any ZIP;
     * then, unless that found an address point, the streets of the localities of the street's municipality (whatever
     * their ZIP; read once per municipality and kept): of another name only those whose representative point lies within
     * [ALONG_OTHER_WITHIN_M] of the line (the same road under another name). Of them:
     *  - a range (census or OSM) covering the number counts when the point it gives before the side offset lies within
     *    [ALONG_RANGE_WITHIN_M] of the street's line and the edge runs there within [ALONG_MAX_ANGLE_DEG] of the line,
     *    either way (a crossing street's range that starts at the corner does not count, nor an edge of no length); its
     *    position is the lookup's own ([interpolate], offset to its side), so it is the same house as typed with the
     *    range's town;
     *  - an address point with the number (then its base number, as [resolve]) counts within [ALONG_POINT_WITHIN_M] of the
     *    line (a house set back from the road), and only on a street of [NAME_OWN] or [NAME_CORE], or of [NAME_SPELLING]
     *    when one of that same street's ranges was found along the line in this lookup (the proof that the other spelling
     *    is this road): "120 Example Hill Ln" 120 m off "Example Mill Lane" is never its house, nor a crossing "Township
     *    Road 8" house a state route 8's.
     * Returns them all with [AddressHit.alongName] and [AddressHit.alongLineM]: accuracy "point" or "interpolated", match
     * [MATCH_ALONG], localityMatch null; the caller ranks them. Empty for a number that is not one or is too large, or a
     * shape with no line.
     */
    fun alongStreet(hn: String, shape: StreetShape): List<AddressHit> {
        val parsedHn = normalizer.houseNumber(hn) ?: return emptyList()
        if (parsedHn.int > maxHouseNumber || shape.lines.isEmpty() || shape.box.size != 4) return emptyList()
        val hnn = parsedHn.text
        val hni = parsedHn.int.toLong()
        val primary = shape.addrKey ?: normalizer.streetKey(shape.name)
        val own = LinkedHashSet<String>()
        for (k in listOf(primary) + shape.altKeys.map { normalizer.streetKey(it) }) if (k.isNotEmpty()) own.add(k)
        val derived = LinkedHashSet<String>()
        for (k in shape.derivedKeys.map { normalizer.streetKey(it) }) if (k.isNotEmpty()) derived.add(k)
        for (k in own + derived) derived.addAll(normalizer.highwayAlternatives(k))
        derived.removeAll(own)
        val core = normalizer.streetCore(primary)
        val b = shape.box
        val cosLat = StrictMath.cos(Math.toRadians((b[0] + b[1]) / 2))
        val dLon = ALONG_MARGIN_DEG / cosLat
        val area = listOf(e6(b[0] - ALONG_MARGIN_DEG), e6(b[1] + ALONG_MARGIN_DEG), e6(b[2] - dLon), e6(b[3] + dLon))
        // 1. the street's keys and their highway alternatives, in any ZIP (one index lookup)
        val byKey = LinkedHashMap<Long, Pair<Street, Int>>()
        val keys = own + derived
        if (keys.isNotEmpty()) {
            for (s in streets("s.street_key IN (${marks(keys.size)}) AND s.lat_e6 BETWEEN ? AND ? AND s.lon_e6 BETWEEN ? AND ?", keys.toList() + area)) {
                byKey[s.id] = s to (if (s.key in own) NAME_OWN else NAME_DERIVED)
            }
        }
        val first = probeAlong(byKey, shape, hnn, hni, ranges = true)
        if (first.any { it.accuracy == "point" }) return first
        // 2. the streets of its municipality in the area, any name and ZIP: points of a near name; ranges (when the
        // keys found none) of any name, another name only near the line (its box grown by ALONG_OTHER_WITHIN_M first)
        val inMuni = shape.municipality?.let { normalizer.placeKey(it) }?.ifEmpty { null }?.let { muniStreets(it) } ?: return first
        val ranges = first.isEmpty()
        val dLat300 = ALONG_OTHER_WITHIN_M / M_PER_DEG
        val near = listOf(e6(b[0] - dLat300), e6(b[1] + dLat300), e6(b[2] - dLat300 / cosLat), e6(b[3] + dLat300 / cosLat))
        val classOf = LinkedHashMap<Long, Int>()
        val cells by lazy { AlongStreet.Cells(shape.lines, ALONG_OTHER_WITHIN_M) }
        for (j in inMuni.ids.indices) {
            val la = inMuni.lat[j]
            val lo = inMuni.lon[j]
            val id = inMuni.ids[j]
            if (la !in area[0]..area[1] || lo !in area[2]..area[3] || id in byKey) continue
            val cls = nameClass(inMuni.keys[j], inMuni.cores[j], own, primary, core)
            if (cls == NAME_OTHER) {
                if (!ranges || la !in near[0]..near[1] || lo !in near[2]..near[3] || !cells.mayHold(la / 1e6, lo / 1e6)) continue
                if ((AlongStreet.nearest(shape.lines, la / 1e6, lo / 1e6)?.get(0) ?: Double.MAX_VALUE) > ALONG_OTHER_WITHIN_M) continue
            } else if (!ranges && cls != NAME_CORE) {
                continue   // a near spelling's point needs its own range along the line, looked for only with the ranges
            }
            classOf[id] = cls
        }
        if (classOf.isEmpty()) return first
        val rows = LinkedHashMap<Long, Pair<Street, Int>>()
        for (chunk in classOf.keys.toList().chunked(CHUNK)) {
            for (s in streets("s.id IN (${marks(chunk.size)})", chunk)) rows[s.id] = s to classOf.getValue(s.id)
        }
        return first + probeAlong(rows, shape, hnn, hni, ranges)
    }

    /**
     * How a street of the file named [key] (core [core]) relates to a street of own keys [own] and join key [primary]
     * (core [primaryCore]) ([alongStreet]).
     */
    private fun nameClass(key: String, core: String, own: Set<String>, primary: String, primaryCore: String): Int {
        if (key in own) return NAME_OWN
        if (core == primaryCore) return if (compatible(primary, key)) NAME_CORE else NAME_OTHER
        if (core.length >= ALONG_SPELLING_MIN_CHARS && primaryCore.length >= ALONG_SPELLING_MIN_CHARS &&
            AddressNormalizer.osaDistance(core, primaryCore, 1) <= 1 && compatible(primary, key)) return NAME_SPELLING
        return NAME_OTHER
    }

    /** Item 55: whether the typed place [placeKey] is a postal town (the places table's kind "postal"). */
    fun isPostalPlace(placeKey: String?): Boolean =
        placeKey != null && postalCache.getOrPut(placeKey) {
            db.first("SELECT 1 FROM places WHERE place_key = ? AND kind = 'postal' LIMIT 1", placeKey) != null
        }

    private val postalCache = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

    /**
     * [alongStreet]'s answers among [streets] (id to (street, name class)): with [ranges], the ranges covering the number
     * along the line; then the points with it, of the classes that may give one.
     */
    private fun probeAlong(streets: Map<Long, Pair<Street, Int>>, shape: StreetShape, hnn: String, hni: Long, ranges: Boolean): List<AddressHit> {
        if (streets.isEmpty()) return emptyList()
        fun hit(id: Long, lat: Double, lon: Double, acc: String, src: String, hnOut: String, lineM: Double): AddressHit {
            val (row, cls) = streets.getValue(id)
            return AddressHit(
                lat = lat, lon = lon, accuracy = acc, source = src, houseNumber = hnOut, street = row.street, streetKey = row.key,
                zip = row.zip, city = row.city, municipality = row.municipality, countyFips = row.countyFips, match = MATCH_ALONG,
                score = Py.round(accW.d(acc) + (srcW[src]?.jsonPrimitive?.double ?: 0.0), 2), localityMatch = null,
                alongName = cls, alongLineM = Py.round(lineM, 1),
            )
        }
        val found = ArrayList<AddressHit>()
        val proved = HashSet<Long>()
        if (ranges && "ranges" in tables) {
            for (ids in streets.keys.chunked(CHUNK)) {
                for (r in db.query("SELECT street_id, edge_id, side, hn_from, hn_to, parity, source FROM ranges " +
                    "WHERE street_id IN (${marks(ids.size)}) AND hn_lo <= ? AND hn_hi >= ?", ids + hni + hni)) {
                    val par = r[5] as String?
                    if (par == "O" && hni % 2 == 0L || par == "E" && hni % 2 == 1L) continue
                    val edge = r[1] as Long
                    val blob = db.first("SELECT geom FROM edges WHERE id = ?", edge)?.get(0) as? ByteArray ?: continue
                    val geom = decodeLine(blob)
                    if (geom.isEmpty()) continue
                    val a = r[3] as Long
                    val z = r[4] as Long
                    val frac = if (a == z) 0.5 else (hni - a).toDouble() / (z - a).toDouble()
                    val on = AlongStreet.pointOn(geom, frac)
                    val near = AlongStreet.nearest(shape.lines, on[0], on[1]) ?: continue
                    if (near[0] > ALONG_RANGE_WITHIN_M) continue
                    if (!AlongStreet.parallel(on[2], on[3], near[1], near[2], ALONG_MAX_ANGLE_DEG)) continue
                    val side = r[2] as String?
                    val (lon, lat) = interpolate(geom, frac, side, offsetM)
                    val id = r[0] as Long
                    proved.add(id)
                    found += hit(id, lat, lon, "interpolated", r[6] as String, hnn, near[0]).copy(edgeId = edge, side = side)
                }
            }
        }
        val pointIds = streets.filter { (id, v) -> v.second == NAME_OWN || v.second == NAME_CORE || (v.second == NAME_SPELLING && id in proved) }.keys.toList()
        if ("addresses" in tables && pointIds.isNotEmpty()) {
            val points = ArrayList<AddressHit>()
            val tries = listOf(hnn) + (if (hni.toString() != hnn) listOf(hni.toString()) else emptyList())
            for (want in tries) {
                for (ids in pointIds.chunked(CHUNK)) {
                    for (r in db.query("SELECT street_id, hn, unit, lat_e6, lon_e6, source, id FROM addresses " +
                        "WHERE street_id IN (${marks(ids.size)}) AND hn = ?", ids + want)) {
                        val lat = (r[3] as Long) / 1e6
                        val lon = (r[4] as Long) / 1e6
                        val near = AlongStreet.nearest(shape.lines, lat, lon) ?: continue
                        if (near[0] > ALONG_POINT_WITHIN_M) continue
                        points += hit(r[0] as Long, lat, lon, "point", r[5] as String, r[1] as String, near[0])
                            .copy(unit = r[2] as String?, addressId = r[6] as Long, baseNumberOf = if (want != hnn) hnn else null)
                    }
                }
                if (points.isNotEmpty()) break
            }
            return points + found
        }
        return found
    }

    /** Item 55: the place key of the town typed in [p] (as [resolve] reads it), or null. */
    fun typedPlaceKey(p: ParsedAddress): String? =
        (p.cityKey?.ifEmpty { null } ?: if (!p.city.isNullOrEmpty()) normalizer.placeKey(p.city) else null)?.ifEmpty { null }

    /** Item 55: the place key of a municipality as the search pack names it ("Example Township": "example"), or null. */
    fun municipalityKey(name: String?): String? = name?.let { normalizer.placeKey(it) }?.ifEmpty { null }

    /**
     * Item 55: the municipalities (place keys) of the typed place [placeKey] or ZIP [zip]: itself, and those of the
     * localities whose postal town it is or that have its ZIPs (a borough's postal town serves the township around it:
     * the file's localities pair each ZIP and postal town with the municipalities its mail goes to). Empty for neither.
     */
    fun servedMunicipalities(placeKey: String?, zip: String?): Set<String> {
        val z = zip?.ifEmpty { null }
        if (placeKey == null && z == null) return emptySet()
        return servedCache.getOrPut("$placeKey|$z") {
            val conds = ArrayList<String>()
            val args = ArrayList<Any?>()
            if (placeKey != null) {
                conds += "city_key = ?"; args += placeKey
                conds += "muni_key = ?"; args += placeKey
                val zs = placeZips(placeKey).sorted()
                if (zs.isNotEmpty()) {
                    conds += "zip IN (${marks(zs.size)})"; args.addAll(zs)
                }
            }
            if (z != null) {
                conds += "zip = ?"; args += z
            }
            val out = LinkedHashSet<String>()
            if (placeKey != null) out += placeKey
            for (r in db.query("SELECT DISTINCT muni_key FROM localities WHERE muni_key IS NOT NULL AND (" + conds.joinToString(" OR ") + ")", args)) {
                out += r[0] as String
            }
            out
        }
    }

    private val servedCache = java.util.concurrent.ConcurrentHashMap<String, Set<String>>()

    /** Item 55: whether answer [h] is filed under the town or ZIP typed in [p] (its ZIP, postal town or municipality). */
    fun filedIn(h: AddressHit, p: ParsedAddress): Boolean {
        val z = p.zip?.ifEmpty { null }
        if (z != null && h.zip == z) return true
        val ck = typedPlaceKey(p) ?: return false
        return (h.city != null && normalizer.placeKey(h.city) == ck) || (h.municipality != null && normalizer.placeKey(h.municipality) == ck) ||
            (h.zip != null && h.zip in placeZips(ck))
    }

    fun geocode(text: String, limit: Int? = null): List<AddressHit> = resolve(parse(text), limit)

    fun parse(text: String): ParsedAddress = parser.parse(text, knownPlaces)

    companion object {
        private const val R_EARTH = 6_371_008.8

        /**
         * Item 55 ([alongStreet]): how far around the street's box a street of the file may have its representative
         * point (degrees of latitude, about 1.1 km), how near the street's line a range's point (25 m; census edges
         * lie within 6 m of OSM's line for 95 % of the same-named streets measured on the PA pack, 98 % within 20 m)
         * and an address point (200 m: 98 % of the same-named streets' points measured there lie within it) must be,
         * at what angle a range's edge may cross the line (30 degrees; 99 % of them within it), the shortest core a
         * near spelling (one edit) is tried for, and how near the line the representative point of a street of another
         * name must be.
         */
        const val ALONG_MARGIN_DEG = 0.01
        const val ALONG_RANGE_WITHIN_M = 25.0
        const val ALONG_POINT_WITHIN_M = 200.0
        const val ALONG_MAX_ANGLE_DEG = 30.0
        const val ALONG_SPELLING_MIN_CHARS = 7
        const val ALONG_OTHER_WITHIN_M = 300.0

        /** Item 55: [AddressHit.alongName], closest first (see [alongStreet]). */
        const val NAME_OWN = 0
        const val NAME_CORE = 1
        const val NAME_DERIVED = 2
        const val NAME_SPELLING = 3
        const val NAME_OTHER = 4

        /** Item 55: [AddressHit.match] (and so the result's tier) of an answer along the street shown. */
        const val MATCH_ALONG = "along_street"

        private const val M_PER_DEG = 111_320.0

        /** Municipalities whose streets [alongStreet] keeps in memory (a city of 11,000 streets: about 1 MB, an estimate). */
        private const val MUNI_CACHE = 32

        /** Street ids per IN (...) of [alongStreet]. */
        private const val CHUNK = 500

        private fun marks(n: Int) = List(n) { "?" }.joinToString(",")

        private fun e6(deg: Double): Long = Math.round(deg * 1e6)

        private fun JsonObject.d(k: String) = this[k]!!.jsonPrimitive.double

        /** edges.geom: little-endian int32 (lon, lat) micro-degree pairs -> [(lon, lat)]. */
        fun decodeLine(blob: ByteArray): List<DoubleArray> {
            val bb = ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN)
            val n = blob.size / 4
            val out = ArrayList<DoubleArray>(n / 2)
            var i = 0
            while (i + 1 < n) {
                val lon = bb.getInt(i * 4) / 1e6
                val lat = bb.getInt((i + 1) * 4) / 1e6
                out.add(doubleArrayOf(lon, lat))
                i += 2
            }
            return out
        }

        /** addrlib.geo.interpolate: point at fraction frac of the line, offset to side L/R by offsetM metres. Returns (lon, lat). */
        fun interpolate(coords: List<DoubleArray>, frac: Double, side: String?, offsetM: Double): Pair<Double, Double> {
            if (coords.size == 1) return coords[0][0] to coords[0][1]
            // Python: sum(c[1] for c in coords) / len(coords); sum() of floats is compensated in 3.12
            val lat0 = Py.sum(DoubleArray(coords.size) { coords[it][1] }) / coords.size
            val kx = StrictMath.cos(Math.toRadians(lat0)) * Math.PI / 180 * R_EARTH
            val ky = Math.PI / 180 * R_EARTH
            val xy = coords.map { doubleArrayOf(it[0] * kx, it[1] * ky) }
            val seg = DoubleArray(xy.size - 1) { i -> StrictMath.hypot(xy[i + 1][0] - xy[i][0], xy[i + 1][1] - xy[i][1]) }
            val total = Py.sum(seg)
            if (total == 0.0) return coords[0][0] to coords[0][1]
            val target = maxOf(0.0, minOf(1.0, frac)) * total
            var acc = 0.0
            for (i in seg.indices) {
                val s = seg[i]
                if (acc + s >= target || i == seg.size - 1) {
                    val tt = if (s == 0.0) 0.0 else (target - acc) / s
                    val x0 = xy[i][0]; val y0 = xy[i][1]; val x1 = xy[i + 1][0]; val y1 = xy[i + 1][1]
                    var x = x0 + tt * (x1 - x0)
                    var y = y0 + tt * (y1 - y0)
                    if ((side == "L" || side == "R") && offsetM != 0.0 && s > 0) {
                        var nx = -(y1 - y0) / s
                        var ny = (x1 - x0) / s
                        if (side == "R") {
                            nx = -nx; ny = -ny
                        }
                        x += nx * offsetM
                        y += ny * offsetM
                    }
                    return x / kx to y / ky
                }
                acc += s
            }
            return coords.last()[0] to coords.last()[1]
        }
    }
}
