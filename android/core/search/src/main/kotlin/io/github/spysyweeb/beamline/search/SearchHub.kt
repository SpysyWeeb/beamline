package io.github.spysyweeb.beamline.search

import io.github.spysyweeb.beamline.api.data.InstalledSet
import io.github.spysyweeb.beamline.api.data.PackInfo
import io.github.spysyweeb.beamline.api.model.GeoPoint

/**
 * Search over the installed packs (docs/architecture.md 2.2 "how each consumer obtains its data",
 * rules S3-S5 of 3.8, decisions 6.11, 6.12 and 6.17). One handle ([SearchService]) per pack per
 * generation of [InstalledSet]: on a new generation the next call opens new handles and closes the
 * old ones first (no call is running on them: the hub is confined to the `beamline-search` thread
 * and its public calls are serialised), so a replaced or removed pack's file is never held open
 * after the next call (rule 3 of 2.2). Each handle is opened lazily, checked and warmed up:
 *
 *  - the fingerprint check at open (2.11): the search part's `addr_key_fingerprint` must equal
 *    [expectedFingerprint] (this app's port over the bundled spelling list), and an attached
 *    address file's `key_fingerprint`, when it has one, too; otherwise that pack's search is
 *    unusable ([problems], "built with another address normaliser") and map and routing are not
 *    touched. A search part without a fingerprint is refused the same way. [expectedFingerprint]
 *    null switches the check off (tests and tools only);
 *  - the warm-up read of decision 6.17 ([SearchService.warmUp]) when [warmUp] is true.
 *
 * A pack is searched when it has a search file and no [PackInfo.searchProblem]. Never logs (PV2);
 * the problems are fixed texts (no exception message is kept).
 */
class SearchHub(
    private val rules: SearchAppRules = SearchAppRules.bundled(),
    private val expectedFingerprint: String? = AddrKeyFingerprint.app(),
    private val opener: (PackInfo) -> SearchService = { SearchService.open(it.searchFile!!, it.addressesFile) },
    private val warmUp: Boolean = true,
) : AutoCloseable {
    @Volatile private var generation: Long? = null
    private val open = LinkedHashMap<String, SearchService>()
    private val refused = LinkedHashMap<String, String>()
    /** A copy of [refused], replaced whenever it changes: read from any thread without waiting for a running query. */
    @Volatile private var refusedSnapshot: Map<String, String> = emptyMap()
    private val maxQueryChars by lazy { Ranking.load().maxQueryChars }

    /** The generation whose handles are open (null before the first call). Never blocks. */
    val openGeneration: Long? get() = generation

    /**
     * Why a pack's search is not used in the current generation (this hub's checks), by pack id.
     * Never blocks (a snapshot), so the main thread may read it while a query runs.
     */
    val problems: Map<String, String> get() = refusedSnapshot

    /** The packs of [set] with a search part the hub may use (it may still refuse one at open). */
    fun candidates(set: InstalledSet): List<PackInfo> = set.packs.filter { it.searchFile != null && it.searchProblem == null }

    /** Opens, checks and warms up every candidate of [set] (call when the search screen opens). */
    @Synchronized
    fun prepare(set: InstalledSet) {
        switchTo(set)
        for (p in candidates(set)) service(p)
    }

    /** True when at least one pack of [set] can be searched (opens the packs it needs to know). */
    @Synchronized
    fun hasSearchData(set: InstalledSet): Boolean {
        switchTo(set)
        return candidates(set).any { service(it) != null }
    }

    /**
     * Rules S3-S5: asks the packs of [MultiPackSearch.packsToAsk] around [near] with [text] (cut by
     * each engine to max_query_chars) and merges their answers ([MultiPackSearch.merge]).
     */
    @Synchronized
    fun search(set: InstalledSet, text: String, near: GeoPoint?, limit: Int = rules.mergeLimit): MultiSearchResponse {
        val t0 = System.nanoTime()
        switchTo(set)
        val usable = candidates(set).filter { service(it) != null }
        val asked = MultiPackSearch.packsToAsk(usable, near, rules.packMarginKm)
        val answers = asked.map { p -> p to open.getValue(p.packId).search(text, near?.lat, near?.lon, limit) }
        val (rows, byScore) = MultiPackSearch.merge(answers, rules.dedupeM, limit)
        val cover = answers.map { it.second.outOfCoverage }
        return MultiSearchResponse(
            query = answers.firstOrNull()?.second?.query ?: SearchEngine.capCodePoints(text, maxQueryChars),
            results = rows,
            packsAsked = asked.map { it.packId },
            mergedByScore = byScore,
            houseNumber = answers.firstNotNullOfOrNull { it.second.houseNumber },
            outOfCoverage = when {
                cover.isEmpty() || cover.any { it == null } -> null
                else -> cover.all { it == true }
            },
            ms = Py.round((System.nanoTime() - t0) / 1e6, 2),
            namedPlace = answers.firstNotNullOfOrNull { it.second.namedPlace },
        )
    }

    /**
     * Rule S10 for a place without a search id: hours, phone and website of the POIs named [name]
     * within details.match_within_m of [point] in an installed pack that covers it, when they are
     * sure ([SearchEngine.detailsNamedNear]), with "packId:searchId" of the nearest; null otherwise.
     */
    @Synchronized
    fun detailsAt(set: InstalledSet, name: String, point: GeoPoint): Pair<String, PoiDetails>? {
        switchTo(set)
        val usable = candidates(set).filter { service(it) != null }
        for (p in MultiPackSearch.packsToAsk(usable, point, 0.0).filter { it.bounds.contains(point) }) {
            val s = service(p) ?: continue
            val (id, d) = s.detailsNamedNear(name, point.lat, point.lon, rules.detailsMatchM) ?: continue
            return "${p.packId}:$id" to d
        }
        return null
    }

    /**
     * Where customers go into the place named [name] at [point] (docs/next-round.md item 28): the front
     * door of the POI of that name within details.match_within_m of the point, in an installed pack that
     * covers it ([SearchEngine.doorNamedNear]); null when it has none. The trip planner ends a route to a
     * large store at the lane by this door.
     */
    @Synchronized
    fun doorAt(set: InstalledSet, name: String, point: GeoPoint): GeoPoint? {
        switchTo(set)
        val usable = candidates(set).filter { service(it) != null }
        for (p in MultiPackSearch.packsToAsk(usable, point, 0.0).filter { it.bounds.contains(point) }) {
            val s = service(p) ?: continue
            val (lat, lon) = s.doorNamedNear(name, point.lat, point.lon, rules.detailsMatchM) ?: continue
            return GeoPoint(lat, lon)
        }
        return null
    }

    /**
     * The traffic lights the installed packs have in the given cells (docs/next-round.md item 36;
     * [SearchEngine.signals]): every usable pack is asked (a cell on a state line has lights of both).
     */
    @Synchronized
    fun signalsIn(set: InstalledSet, cells: Collection<Long>): List<GeoPoint> {
        switchTo(set)
        val out = LinkedHashSet<GeoPoint>()
        for (p in candidates(set)) {
            val s = service(p) ?: continue
            for ((lat, lon) in s.signals(cells)) out += GeoPoint(lat, lon)
        }
        return out.toList()
    }

    /**
     * The street at [point] (a dropped pin; docs/next-round.md item 31): the nearest street line of an
     * installed pack that covers the point ([SearchEngine.nearStreet]); null when no street is that near
     * or the pack was built before the streets' lines.
     */
    @Synchronized
    fun streetAt(set: InstalledSet, point: GeoPoint): NearStreet? {
        switchTo(set)
        val usable = candidates(set).filter { service(it) != null }
        for (p in MultiPackSearch.packsToAsk(usable, point, 0.0).filter { it.bounds.contains(point) }) {
            val s = service(p) ?: continue
            return s.nearStreet(point.lat, point.lon) ?: continue
        }
        return null
    }

    /**
     * 0.1.40 (play test P6): the town nearest [point] within [withinKm] ([SearchEngine.placeNear]) in an
     * installed pack that covers the point; null when none is that near or no pack there can be searched.
     */
    @Synchronized
    fun placeNear(set: InstalledSet, point: GeoPoint, withinKm: Double): String? {
        switchTo(set)
        val usable = candidates(set).filter { service(it) != null }
        for (p in MultiPackSearch.packsToAsk(usable, point, 0.0).filter { it.bounds.contains(point) }) {
            val s = service(p) ?: continue
            s.placeNear(point.lat, point.lon, withinKm)?.let { return it }
        }
        return null
    }

    /**
     * Rule S10 for one place with a search id (a result, a recent): the details of [id] in the pack
     * [packId] when that entry is still the place ([SearchEngine.isNamedNear]: its name and within
     * details.match_within_m of [point]); else, as for a place without one, by [name] and [point]
     * ([detailsAt]). A recent kept from an older pack build never shows another place's details (batch G).
     */
    @Synchronized
    fun detailsOf(set: InstalledSet, packId: String, id: Long, name: String, point: GeoPoint): PoiDetails? {
        switchTo(set)
        val pack = candidates(set).firstOrNull { it.packId == packId }
        val s = pack?.let { service(it) }
        if (s != null && s.isNamedNear(id, name, point.lat, point.lon, rules.detailsMatchM)) {
            s.poiDetails(listOf(id))[id]?.let { return it }
        }
        return detailsAt(set, name, point)?.second
    }

    /**
     * Opening hours, phone and website of [ids] in the pack [packId] of [set] ([SearchEngine.poiDetails]);
     * empty when that pack is not installed, has no usable search part or the ids are not POIs.
     */
    @Synchronized
    fun details(set: InstalledSet, packId: String, ids: Collection<Long>): Map<Long, PoiDetails> {
        switchTo(set)
        val pack = candidates(set).firstOrNull { it.packId == packId } ?: return emptyMap()
        return service(pack)?.poiDetails(ids) ?: emptyMap()
    }

    /**
     * Batch O3 (docs/online-design.md 4.3): the named POIs within [withinM] of [point] in the installed
     * packs that cover it ([SearchEngine.poisNear]), nearest first; empty without search data there.
     */
    @Synchronized
    fun poisNear(set: InstalledSet, point: GeoPoint, withinM: Double): List<NearbyPoi> {
        switchTo(set)
        val usable = candidates(set).filter { service(it) != null }
        return MultiPackSearch.packsToAsk(usable, point, 0.0).filter { it.bounds.contains(point) }
            .flatMap { p -> service(p)?.poisNear(point.lat, point.lon, withinM).orEmpty() }
            .sortedBy { it.distanceM }
    }

    /** Batch O3 ("View all"): the categories of the first installed pack with search data ([SearchEngine.categories]). */
    @Synchronized
    fun categories(set: InstalledSet): List<CategoryInfo> {
        switchTo(set)
        for (p in candidates(set)) {
            val s = service(p) ?: continue
            return s.categories()
        }
        return emptyList()
    }

    @Synchronized
    override fun close() {
        closeAll()
        generation = null
    }

    private fun switchTo(set: InstalledSet) {
        if (generation == set.generation) return
        closeAll()
        generation = set.generation
    }

    private fun closeAll() {
        for (s in open.values) runCatching { s.close() }
        open.clear()
        refused.clear()
        refusedSnapshot = emptyMap()
    }

    private fun refuse(packId: String, why: String) {
        refused[packId] = why
        refusedSnapshot = LinkedHashMap(refused)
    }

    /** The open handle of [pack] in this generation, opening it now; null when refused. */
    private fun service(pack: PackInfo): SearchService? {
        open[pack.packId]?.let { return it }
        if (pack.packId in refused) return null
        val s = try {
            opener(pack)
        } catch (_: Exception) {
            refuse(pack.packId, CANNOT_OPEN)
            return null
        }
        val why = fingerprintProblem(s)
        if (why != null) {
            runCatching { s.close() }
            refuse(pack.packId, why)
            return null
        }
        if (warmUp) runCatching { s.warmUp() }
        open[pack.packId] = s
        return s
    }

    private fun fingerprintProblem(s: SearchService): String? {
        val want = expectedFingerprint ?: return null
        val got = s.engine.meta[META_FINGERPRINT] ?: return NO_FINGERPRINT
        if (got != want) return OTHER_NORMALISER
        val addr = s.addresses?.keyFingerprint
        if (addr != null && addr != want) return OTHER_NORMALISER
        return null
    }

    companion object {
        const val META_FINGERPRINT = "addr_key_fingerprint"
        const val META_RANKING = "ranking_sha256"
        /** The words of PackInfo.searchProblem's own example (docs/architecture.md 2.2). */
        const val OTHER_NORMALISER = "built with another address normaliser"
        const val NO_FINGERPRINT = "built without the address normaliser's fingerprint; rebuild the pack"
        const val CANNOT_OPEN = "the search file could not be opened"
    }
}
