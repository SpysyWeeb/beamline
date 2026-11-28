package io.github.spysyweeb.beamline.search

import io.github.spysyweeb.beamline.search.address.AddressHit
import io.github.spysyweeb.beamline.search.address.AddressIndex
import io.github.spysyweeb.beamline.search.address.AlongStreet
import io.github.spysyweeb.beamline.search.address.ParsedAddress

/**
 * What a screen calls: the search engine (the Python reference's answers, unchanged) plus the
 * address lookup for typed house numbers. NOT part of the Python reference; the rule is new and
 * kept small so it is easy to change:
 *
 *  - the engine answers first; it locates a house number only when the pack's address file has
 *    an exact point for it (address_rows) on one of its street results;
 *  - when the query has a house number, the engine located none, and the address lookup
 *    (addrlib's port) finds a point or an interpolated position for a street in the typed town
 *    or ZIP, that answer goes first as an ADDRESS result;
 *  - when the lookup only finds the street in another town (localityMatch = false, or a typed
 *    state other than the pack's), its answer goes last, marked as a guess;
 *  - with no town or ZIP typed, the lookup by name is not used (it would pick a street of that
 *    name anywhere in the state, not near the user); instead (docs/next-round.md item 55, 0.1.42)
 *    the number is looked up along the street the engine shows where the search is ranked, by
 *    place and not by name ([AddressIndex.alongStreet]: points and census or OSM ranges whose
 *    geometry lies along its line, in any spelling and any ZIP, "Example Flynn Rd" in the next
 *    town's ZIP for OSM's "Example Flinn Road"; points only of its own name or its join key's core
 *    with no part contradicting it, or of a near spelling proven by its range along the line). The
 *    street: of the street rows with the first one's name match (not any_word), the one nearest to
 *    the place the words name, else to the position, else the first; when the engine located the
 *    house on it, nothing more. Its own answers first, else those of the road's next pieces (rows
 *    of the same name whose line joins its line). The closest name wins, then a point, then the
 *    nearest to the line, then the nearest to where the search is ranked. The answer goes first,
 *    exact or estimated, titled with the number and the street row's name when it is under the
 *    street's own name, else the data's ("12 N Main St", "120 Example Flynn Rd"), its own town and
 *    ZIP below, localityMatch null (a link's words open it straight away only in a matched typed
 *    town: app HandoverRules); not when the engine located a house within [ALONG_SAME_HOUSE_M] of
 *    it or of its street's line, or on the answer's own row (that house is in the list already: a
 *    rural house 300 m back from the road). A road of the name elsewhere is never looked along,
 *    not even one the list shows; another state named: nothing, as before;
 *  - with a town or ZIP of the pack's state typed: a house the engine located in or near that
 *    place is the answer (nothing more, the town's lookup not run); else, when the lookup locates
 *    no house there (only the street, nothing, or a guess elsewhere: a census range filed under
 *    the next town's ZIP), the same along the street rows in that place: in a municipality it is
 *    or whose mail it takes (a borough's postal town serving the township around it), or within
 *    [ALONG_TOWN_KM] of the place the words name ([ALONG_POSTAL_KM] for a postal town or a ZIP),
 *    each looked along: the nearest row's answer, which only a row joined to it or in its
 *    municipality may beat, in place of the guess; localityMatch true when the street's municipality
 *    is one the place serves or the answer is filed under it, then first, else false and after the
 *    engine's rows. With no answer: as before (nothing beside a house the engine located). A typed
 *    town that locates the house: as before, unless the engine located that house;
 *  - the address result's score is on the engine's scale, so that rule S4's merge by score across
 *    packs keeps exact addresses first (next run, part 1: "247 Main Street, Conneaut", exact in
 *    Ohio, ranked below an estimated "247 Main St, Conneautville" from Pennsylvania because the
 *    lookup's own score, 4-9, was used): first and exact (a point) = the best engine score kept +
 *    ranking.json weights.house_located (like an engine-located house); first and estimated (or
 *    street only) = that best score + 0.0001 (first in its pack, below any exact address); last =
 *    the lowest engine score kept - 0.0001 (0 when the engine found nothing).
 * Address results get a stable negative id (FNV-1a 64 of street key, number and ZIP).
 *
 * The input cap (rule S5) is the engine's own: it searches the first ranking.json
 * search.max_query_chars code points, and the address lookup parses that same cut text
 * ([SearchResponse.query]). [limit] as in [SearchEngine.resolveLimit]. Safe to share between
 * threads, like the engine and the index.
 */
class SearchService(
    val engine: SearchEngine,
    val addresses: AddressIndex?,
) : AutoCloseable {

    fun search(text: String, lat: Double? = null, lon: Double? = null, limit: Int? = null): SearchResponse {
        val lim = engine.resolveLimit(limit)
        return withAddress(engine.search(text, lat, lon, lim), lat, lon, lim)
    }

    /** The rule of the class comment applied to the engine's answer [r] for the same position and [lim] (tests hand it one). */
    internal fun withAddress(r: SearchResponse, lat: Double?, lon: Double?, lim: Int): SearchResponse {
        val q = r.query   // the engine's cut text (rule S5)
        val idx = addresses ?: return r
        if (r.houseNumber == null) return r
        val t0 = System.nanoTime()
        val parsed = idx.parse(q)
        val user = SearchEngine.validPosition(lat, lon)
        val otherState = parsed.state?.lowercase()?.let { st -> idx.state != null && st != idx.state } == true
        val shapes = HashMap<Long, StreetShape?>()
        val shape: (SearchResult) -> StreetShape? = { row -> shapes.getOrPut(row.id) { engine.streetShape(row.id) } }
        // the houses the engine located: an answer near one of them or near its street's line (a rural house 300 m back
        // from the road; the next piece's house by the line between them), or on its own row, is that house, shown already
        val located = r.results.filter { it.address?.accuracy == AddressInfo.Accuracy.POINT }
        val shown: (AddressHit, SearchResult?) -> Boolean = { h, row ->
            located.any { l ->
                Geo.haversineKm(l.lat, l.lon, h.lat, h.lon) * 1000.0 <= ALONG_SAME_HOUSE_M ||
                    shape(l)?.let { lineM(it, h.lat, h.lon) <= ALONG_SAME_HOUSE_M } == true || l.id == row?.id
            }
        }
        if (parsed.zip == null && parsed.city.isNullOrEmpty()) {
            // item 55: no town or ZIP typed: the number along the street the engine shows where the search is ranked
            // (another state named: nothing, as before)
            if (otherState) return r
            return alongFirst(idx, r, user, null, lim, t0, shape, shown) ?: r
        }
        val key = idx.typedPlaceKey(parsed)
        val typed = Typed(parsed, idx.servedMunicipalities(key, parsed.zip), r.namedPlace?.let { LatLon(it.lat, it.lon) },
            if (key == null || idx.isPostalPlace(key)) ALONG_POSTAL_KM else ALONG_TOWN_KM)
        // a house the engine located in or near the typed place is the answer: nothing more, the town's lookup not run
        if (located.any { inPlace(idx, typed, it, shape) }) return r
        val hit = idx.resolve(parsed, 1).firstOrNull()
        val inTown = hit != null && hit.accuracy != "street" && hit.localityMatch == true && !hit.stateMismatch
        // a typed town that locates the house: as before, unless the engine located that house already
        if (inTown && shown(hit!!, null)) return r
        // item 55: one of the pack's state where the lookup locates no house: along the street shown in or near that town
        if (!inTown && !otherState) alongFirst(idx, r, user, typed, lim, t0, shape, shown)?.let { return it }
        // no answer of the town there: as before, nothing beside a house the engine located
        if (!inTown && located.isNotEmpty()) return r
        if (hit == null) return r
        if (hit.accuracy == "street" && hit.houseNumber == null) return r   // only the street: the engine has it
        val kept = r.results.take(lim - 1)
        val first = hit.localityMatch == true && !hit.stateMismatch
        val res = toResult(hit, user).let { it.copy(score = scoreOf(it, first, kept)) }
        val merged = if (first) listOf(res) + kept else kept + res
        val ms = Py.round(r.ms + (System.nanoTime() - t0) / 1e6, 2)
        return r.copy(results = merged, ms = ms)
    }

    /**
     * Item 55: the town or ZIP typed ([parsed]): the municipalities it is or whose mail it takes ([served],
     * [AddressIndex.servedMunicipalities]), the place the words name ([named], the engine's), and how far from it a
     * street may be ([radiusKm]: [ALONG_POSTAL_KM] for a postal town or a ZIP, else [ALONG_TOWN_KM]).
     */
    private class Typed(val parsed: ParsedAddress, val served: Set<String>, val named: LatLon?, val radiusKm: Double)

    /** Item 55: whether a row is in the typed place: in a municipality it serves, or within its radius of the place named. */
    private fun inPlace(idx: AddressIndex, t: Typed, row: SearchResult, shape: (SearchResult) -> StreetShape?): Boolean {
        val s = shape(row)
        if (idx.municipalityKey(s?.municipality) in t.served) return true
        val n = t.named ?: return false
        // a located row's point is the house; a street's distance is to its line
        val km = if (row.address?.accuracy == AddressInfo.Accuracy.POINT || s == null) Geo.haversineKm(n.lat, n.lon, row.lat, row.lon)
            else lineM(s, n.lat, n.lon) / 1000.0
        return km <= t.radiusKm
    }

    private fun lineM(s: StreetShape, lat: Double, lon: Double): Double = AlongStreet.nearest(s.lines, lat, lon)?.get(0) ?: Double.MAX_VALUE

    /**
     * Item 55: [r] with the answer along the street it shows ([alongStreet]): first, or after the engine's rows when a
     * typed town did not match it (localityMatch false); null without one, or when it is a house the engine located
     * ([shown]). Titled with the street row's name when the answer is under the street's own name, else with the data's
     * (so that "12 N Main St" or "120 Example Flynn Rd" says where the number was found).
     */
    private fun alongFirst(
        idx: AddressIndex, r: SearchResponse, user: LatLon?, typed: Typed?, lim: Int, t0: Long,
        shape: (SearchResult) -> StreetShape?, shown: (AddressHit, SearchResult?) -> Boolean,
    ): SearchResponse? {
        val (hit, row, match) = alongStreet(idx, r, user, typed, shape) ?: return null
        if (shown(hit, row)) return null
        val kept = r.results.take(lim - 1)
        val name = if (hit.alongName == AddressIndex.NAME_OWN) row.title else hit.street
        val first = match != false
        val res = toResult(hit, user, name).let { it.copy(address = it.address?.copy(localityMatch = match), score = scoreOf(it, first, kept)) }
        return r.copy(results = if (first) listOf(res) + kept else kept + res, ms = Py.round(r.ms + (System.nanoTime() - t0) / 1e6, 2))
    }

    /** The address result's score on the engine's scale (see the class comment). */
    private fun scoreOf(res: SearchResult, first: Boolean, kept: List<SearchResult>): Double {
        if (!first) return Py.round((kept.minOfOrNull { it.score }?.minus(0.0001)) ?: 0.0, 4)
        val best = kept.maxOfOrNull { it.score } ?: 0.0
        val exact = res.address?.accuracy == AddressInfo.Accuracy.POINT
        return Py.round(best + if (exact) engine.rk.wHouseLocated else 0.0001, 4)
    }

    /**
     * Item 55: the house number of [r] along the street it shows where the search is ranked: the answer, that street's
     * row, and the localityMatch to show (null with no town typed; with one, true when the street's municipality is one
     * the typed place serves or the answer is filed under it, else false); or null.
     *
     * The street rows looked at: those (not an any_word match: a street that only shares a word with the query) with the
     * name match of the first of them. With no town typed, the one nearest to the place the words name (its line's
     * nearest point), else to the position, else the first; when the engine located the house on it, null (its row is
     * the answer); its own answers first, with none those of the road's next pieces: rows of the same name whose line
     * starts or ends within [ALONG_JOIN_M] of its line, [ALONG_MAX_STREETS] rows in all. With a town or ZIP typed, the
     * rows in the typed place ([inPlace]), nearest the place named first (none: null, the lookup's answer as before;
     * the nearest located: null), [ALONG_MAX_STREETS] at most, each looked along (a borough's own piece of the road need
     * not join the township's piece its postal town serves): the answer of the nearest row with one, which only a row
     * joined to that row's street or in its municipality may beat (a match of the typed town first), never a road of the
     * name in another of the town's municipalities for a closer name. Of a street's answers: the
     * closest name ([AddressHit.alongName]: the street's own range before a point of another direction), then points
     * first, then the nearest to the line (by [ALONG_LINE_STEP_M]), then the nearest to where the search is ranked (to
     * the street's point without it).
     */
    private fun alongStreet(
        idx: AddressIndex, r: SearchResponse, user: LatLon?, typed: Typed?, shape: (SearchResult) -> StreetShape?,
    ): Triple<AddressHit, SearchResult, Boolean?>? {
        val hn = r.houseNumber ?: return null
        val streets = r.results.filter { it.kind == SearchResult.Kind.STREET && it.address != null && it.tier != "any_word" }
        val tier = streets.firstOrNull()?.tier
        val same = streets.filter { it.tier == tier }
        if (same.isEmpty()) return null
        val named = r.namedPlace?.let { LatLon(it.lat, it.lon) }
        fun lineKm(p: LatLon, row: SearchResult): Double = shape(row)?.let { lineM(it, p.lat, p.lon) / 1000.0 } ?: Double.MAX_VALUE
        fun userKm(row: SearchResult): Double = user?.let { Geo.haversineKm(it.lat, it.lon, row.lat, row.lon) } ?: 0.0   // the engine's point nearest the user
        val at = named ?: user
        if (typed != null) {
            val pool = same.filter { inPlace(idx, typed, it, shape) }.sortedBy { row -> named?.let { lineKm(it, row) } ?: userKm(row) }
            if (pool.firstOrNull()?.address?.accuracy != AddressInfo.Accuracy.STREET) return null
            var best: Triple<AddressHit, SearchResult, Boolean?>? = null
            var first: StreetShape? = null   // the street of the nearest row with an answer
            for (row in pool.filter { it.address?.accuracy == AddressInfo.Accuracy.STREET }.take(ALONG_MAX_STREETS)) {
                val s = shape(row) ?: continue
                val f = first
                if (f != null && !joins(f, s) && idx.municipalityKey(s.municipality) != idx.municipalityKey(f.municipality)) continue
                val from = at ?: LatLon(row.lat, row.lon)
                val h = hitsAlong(idx, row.id, hn, s).minWithOrNull(order(from)) ?: continue
                val m = idx.municipalityKey(s.municipality) in typed.served || idx.filedIn(h, typed.parsed)
                val b = best
                if (b == null || (m && b.third != true) || (m == b.third && order(from).compare(h, b.first) < 0)) best = Triple(h, row, m)
                if (f == null) first = s
            }
            return best
        }
        val street = when {
            named != null -> same.minBy { lineKm(named, it) }
            user != null -> same.minBy { userKm(it) }
            else -> same.first()
        }
        if (street.address?.accuracy != AddressInfo.Accuracy.STREET) return null
        val s = shape(street) ?: return null
        val order = order(at ?: LatLon(street.lat, street.lon))
        var best: Pair<AddressHit, SearchResult>? = hitsAlong(idx, street.id, hn, s).minWithOrNull(order)?.let { it to street }
        if (best == null) {
            var n = 1
            for (row in same) {
                if (n >= ALONG_MAX_STREETS) break
                if (row === street || row.title != street.title || row.address?.accuracy != AddressInfo.Accuracy.STREET) continue
                if (Geo.haversineKm(street.lat, street.lon, row.lat, row.lon) > ALONG_PIECE_KM) continue   // before reading its line
                val piece = shape(row) ?: continue
                if (!joins(s, piece)) continue
                n++
                val h = hitsAlong(idx, row.id, hn, piece).minWithOrNull(order) ?: continue
                if (best == null || order.compare(h, best.first) < 0) best = h to row
            }
        }
        val (h, row) = best ?: return null
        return Triple(h, row, null)
    }

    /** Item 55: how a street's answers rank (see [alongStreet]), the last step by distance from [from]. */
    private fun order(from: LatLon) = compareBy<AddressHit>({ it.alongName ?: Int.MAX_VALUE }, { if (it.accuracy == "point") 0 else 1 },
        { Math.floor((it.alongLineM ?: 0.0) / ALONG_LINE_STEP_M) }, { Geo.haversineKm(from.lat, from.lon, it.lat, it.lon) })

    /** Item 55: [AddressIndex.alongStreet] for street entry [eid] and number [hn], kept for the next queries (typing on). */
    private fun hitsAlong(idx: AddressIndex, eid: Long, hn: String, shape: StreetShape): List<AddressHit> {
        val key = "$eid|$hn"
        synchronized(alongCache) { alongCache[key] }?.let { return it }
        val hits = idx.alongStreet(hn, shape)
        synchronized(alongCache) { alongCache[key] = hits }
        return hits
    }

    private val alongCache = object : LinkedHashMap<String, List<AddressHit>>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<AddressHit>>?): Boolean = size > ALONG_CACHE
    }

    /** Item 55: whether one of the lines of [b] starts or ends within [ALONG_JOIN_M] of a line of [a], or the other way round. */
    private fun joins(a: StreetShape, b: StreetShape): Boolean {
        fun ends(s: StreetShape) = s.lines.flatMap { l -> listOf(l[0] to l[1], l[l.size - 2] to l[l.size - 1]) }
        fun touches(ends: List<Pair<Int, Int>>, lines: List<IntArray>) = ends.any { (la, lo) ->
            (AlongStreet.nearest(lines, la / 1e5, lo / 1e5)?.get(0) ?: Double.MAX_VALUE) <= ALONG_JOIN_M
        }
        return touches(ends(b), a.lines) || touches(ends(a), b.lines)
    }

    private fun toResult(h: AddressHit, user: LatLon?, streetName: String = h.street): SearchResult {
        val acc = when (h.accuracy) {
            "point" -> AddressInfo.Accuracy.POINT
            "interpolated" -> AddressInfo.Accuracy.INTERPOLATED
            else -> AddressInfo.Accuracy.STREET
        }
        val place = listOfNotNull(h.city ?: h.municipality, listOfNotNull(addresses?.state?.uppercase(), h.zip).joinToString(" ").ifEmpty { null })
        val d = user?.let { Py.round(Geo.haversineKm(it.lat, it.lon, h.lat, h.lon), 2) }
        return SearchResult(
            id = addressId(h), kind = SearchResult.Kind.ADDRESS,
            title = listOfNotNull(h.houseNumber, streetName).joinToString(" "),
            subtitle = place.joinToString(", "), categoryId = "address", categoryLabel = "Address",
            lat = h.lat, lon = h.lon, distanceKm = d, brandId = null,
            address = AddressInfo(h.houseNumber ?: "", streetName, acc, h.localityMatch, h.stateMismatch),
            score = h.score, how = "address", tier = h.match,
        )
    }

    /**
     * Decision 6.17: reads what the first queries need once, so the first typed letters do not
     * wait for SQLite to page in the hot tables (report 10: 0.4-1 s, 3 s for one prefix, on the
     * emulator after a cold start). Run on the search thread right after opening ([SearchHub]).
     */
    fun warmUp() {
        engine.warmUp()
    }

    /** Batch O3: [SearchEngine.poisNear]. */
    fun poisNear(lat: Double, lon: Double, withinM: Double): List<NearbyPoi> = engine.poisNear(lat, lon, withinM)

    /** Batch O3: [SearchEngine.categories]. */
    fun categories(): List<CategoryInfo> = engine.categories()

    /** [SearchEngine.poiDetails]; address results (negative ids) have none. */
    fun poiDetails(ids: Collection<Long>): Map<Long, PoiDetails> = engine.poiDetails(ids)

    /** Rule S10: the details of the POIs named [name] within [withinM] of the point when sure, or null ([SearchEngine.detailsNamedNear]). */
    fun detailsNamedNear(name: String, lat: Double, lon: Double, withinM: Double): Pair<Long, PoiDetails>? = engine.detailsNamedNear(name, lat, lon, withinM)

    /** Item 28: the front door of the POI named [name] near the point, as (lat, lon), or null ([SearchEngine.doorNamedNear]). */
    fun doorNamedNear(name: String, lat: Double, lon: Double, withinM: Double): Pair<Double, Double>? = engine.doorNamedNear(name, lat, lon, withinM)

    /** Item 36: the pack's traffic lights in the given cells, each as (lat, lon) ([SearchEngine.signals]). */
    fun signals(cells: Collection<Long>): List<Pair<Double, Double>> = engine.signals(cells)

    /** P6 (0.1.40): the town nearest a point within [withinKm], or null ([SearchEngine.placeNear]). */
    fun placeNear(lat: Double, lon: Double, withinKm: Double): String? = engine.placeNear(lat, lon, withinKm)

    /** Item 31: the street at a point (a dropped pin), or null ([SearchEngine.nearStreet]). */
    fun nearStreet(lat: Double, lon: Double): NearStreet? = engine.nearStreet(lat, lon)

    /** Batch G: whether entry [id] is still the place named [name] near the point ([SearchEngine.isNamedNear]). */
    fun isNamedNear(id: Long, name: String, lat: Double, lon: Double, withinM: Double): Boolean = engine.isNamedNear(id, name, lat, lon, withinM)

    override fun close() {
        engine.close()
        addresses?.close()
    }

    companion object {
        /**
         * Item 55: how many street rows (the street where the search is ranked and the next pieces of that road) a house
         * number is looked up along, each a few queries; how near a piece's line must start or end to the street's line
         * (and how far apart the two rows' points may be, so that far rows of the name are not read);
         * how far from the place the words name a street may be when a town (a postal town or a ZIP) is typed; how near
         * a house the engine located must be to an answer to be that house; the step of the distance to
         * the line by which answers of the same name are ranked; and how many (street, number) answers are kept.
         */
        const val ALONG_MAX_STREETS = 3
        const val ALONG_JOIN_M = 40.0
        const val ALONG_PIECE_KM = 30.0
        const val ALONG_TOWN_KM = 5.0
        const val ALONG_POSTAL_KM = 12.0
        const val ALONG_SAME_HOUSE_M = 200.0
        const val ALONG_LINE_STEP_M = 25.0
        private const val ALONG_CACHE = 64

        fun open(searchDb: String, addressesDb: String?): SearchService {
            val eng = SearchEngine(searchDb, addressesDb)
            val idx = try {
                addressesDb?.let { AddressIndex(it) }
            } catch (e: Throwable) {
                eng.close()
                throw e
            }
            return SearchService(eng, idx)
        }

        /** Stable id of an address answer: -(FNV-1a 64 of "street_key|house number|zip") & Long.MAX_VALUE. */
        fun addressId(h: AddressHit): Long {
            var x = -0x340d631b7bdddcdbL   // 0xcbf29ce484222325
            for (b in "${h.streetKey}|${h.houseNumber}|${h.zip}".toByteArray(Charsets.UTF_8)) {
                x = x xor (b.toLong() and 0xFF)
                x *= 0x100000001b3L
            }
            return -((x and Long.MAX_VALUE).coerceAtLeast(1))
        }
    }
}
