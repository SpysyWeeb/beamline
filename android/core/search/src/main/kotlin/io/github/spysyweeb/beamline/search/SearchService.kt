package io.github.spysyweeb.beamline.search

import io.github.spysyweeb.beamline.search.address.AddressHit
import io.github.spysyweeb.beamline.search.address.AddressIndex

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
 *  - with no town or ZIP typed, the lookup is not used (it would pick a street of that name
 *    anywhere in the state, not near the user);
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
        val r = engine.search(text, lat, lon, lim)
        val q = r.query   // the engine's cut text (rule S5)
        val idx = addresses ?: return r
        if (r.houseNumber == null || r.results.any { it.address?.accuracy == AddressInfo.Accuracy.POINT }) return r
        val t0 = System.nanoTime()
        val parsed = idx.parse(q)
        if (parsed.zip == null && parsed.city.isNullOrEmpty()) return r
        val hit = idx.resolve(parsed, 1).firstOrNull() ?: return r
        if (hit.accuracy == "street" && hit.houseNumber == null) return r   // only the street: the engine has it
        val user = SearchEngine.validPosition(lat, lon)
        val kept = r.results.take(lim - 1)
        val first = hit.localityMatch == true && !hit.stateMismatch
        val res = toResult(hit, user).let { it.copy(score = scoreOf(it, first, kept)) }
        val merged = if (first) listOf(res) + kept else kept + res
        val ms = Py.round(r.ms + (System.nanoTime() - t0) / 1e6, 2)
        return r.copy(results = merged, ms = ms)
    }

    /** The address result's score on the engine's scale (see the class comment). */
    private fun scoreOf(res: SearchResult, first: Boolean, kept: List<SearchResult>): Double {
        if (!first) return Py.round((kept.minOfOrNull { it.score }?.minus(0.0001)) ?: 0.0, 4)
        val best = kept.maxOfOrNull { it.score } ?: 0.0
        val exact = res.address?.accuracy == AddressInfo.Accuracy.POINT
        return Py.round(best + if (exact) engine.rk.wHouseLocated else 0.0001, 4)
    }

    private fun toResult(h: AddressHit, user: LatLon?): SearchResult {
        val acc = when (h.accuracy) {
            "point" -> AddressInfo.Accuracy.POINT
            "interpolated" -> AddressInfo.Accuracy.INTERPOLATED
            else -> AddressInfo.Accuracy.STREET
        }
        val place = listOfNotNull(h.city ?: h.municipality, listOfNotNull(addresses?.state?.uppercase(), h.zip).joinToString(" ").ifEmpty { null })
        val d = user?.let { Py.round(Geo.haversineKm(it.lat, it.lon, h.lat, h.lon), 2) }
        return SearchResult(
            id = addressId(h), kind = SearchResult.Kind.ADDRESS,
            title = listOfNotNull(h.houseNumber, h.street).joinToString(" "),
            subtitle = place.joinToString(", "), categoryId = "address", categoryLabel = "Address",
            lat = h.lat, lon = h.lon, distanceKm = d, brandId = null,
            address = AddressInfo(h.houseNumber ?: "", h.street, acc, h.localityMatch, h.stateMismatch),
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
