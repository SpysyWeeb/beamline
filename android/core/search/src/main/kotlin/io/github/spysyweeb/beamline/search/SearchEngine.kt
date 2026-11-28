package io.github.spysyweeb.beamline.search

import androidx.sqlite.SQLiteException
import io.github.spysyweeb.beamline.search.address.StreetTables
import io.github.spysyweeb.beamline.search.db.SqlDb
import java.io.File
import kotlin.math.abs
import kotlin.math.floor
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * Offline search over a pack's search database: a line-by-line port of
 * packbuilder/search/query.py (the reference). It must give the same answers; the conformance
 * test (conformance/README.md) compares the two on every test-set query and a few hundred more.
 *
 * Pipeline per query (see query.py for the long version): validate the position; normalise;
 * split off "near me", a trailing state word, a connector ("near X", "in X", "on X") and a
 * leading house number; classify the phrase (category word, brand alias, known name, trailing
 * place); typo candidates; candidates by SQL (category browse in R*Tree rings, brand browse by
 * distance, FTS5 names, short prefixes from poi_prefix); score with ranking.json; house numbers
 * through the addresses database; merge, de-duplicate, top N.
 *
 * Safe to share between threads: every database call is serialised on the one connection
 * ([SqlDb]); concurrent searches interleave and give the single-thread answers.
 *
 * Like the reference, the engine cuts the text to ranking.json's search.max_query_chars code points
 * before anything else (rule S5 of docs/architecture.md; uncapped, a single 80,000-letter word
 * exhausted a 1 GB heap in the typo step), and its answer's [SearchResponse.query] is the cut text.
 *
 * Stricter than the reference on purpose (the app must not mix packs of different builds): the
 * search database must have schema version [SCHEMA_VERSION], and an attached address file must
 * record the normaliser table version the search index was keyed with, which must also be the
 * version this port implements ([io.github.spysyweeb.beamline.search.address.StreetTables]). query.py attaches a
 * file without a version row, and does not compare with its own version.
 */
class SearchEngine(
    dbPath: String,
    addressesPath: String? = null,
    private val allowAddrVersionMismatch: Boolean = false,
    val rk: Ranking = Ranking.load(),
    tables: SearchTables = SearchTables(RuleFiles.json(RuleFiles.ABBREVIATIONS)),
) : AutoCloseable {

    /** One row of `entry` (ENTRY_COLS), optionally with the poi_cat category id of a browse hit. */
    class Entry(
        val id: Long, val kind: Int, val name: String, val key: String, val alt: String?, val type: String?,
        val lat: Double, val lon: Double, val imp: Double, val muni: Long?, val county: Long?, val brand: Long?,
        val sub: String?, val catId: Long? = null, val hasCat: Boolean = false,
    )

    private class Phrase(raw: List<String>, partial: Boolean, stop: Set<String>, norm: SearchNormalizer) {
        val raw: List<String> = raw.toList()
        val partial: Boolean = partial && raw.isNotEmpty()
        val exp: List<String>
        val key: String
        val keyRaw: String
        val lastRaw: String

        init {
            val e = norm.expandTokens(this.raw)
            exp = e.filter { it !in stop }.ifEmpty { e }
            val rawNs = this.raw.filter { it !in stop }.ifEmpty { this.raw }
            key = exp.joinToString(" ")
            keyRaw = if (exp.isNotEmpty() && rawNs.isNotEmpty()) (exp.dropLast(1) + rawNs.last()).joinToString(" ") else key
            lastRaw = if (rawNs.isNotEmpty()) rawNs.last() else ""
        }

        val truthy get() = exp.isNotEmpty()
    }

    private class Candidate(val row: Entry, val score: Double, val how: String, val tier: String?)

    /** Parse of the query text (query.py parse()). */
    data class Parsed(
        val tokens: List<String>, val head: List<String>, val tail: List<String>, val conn: String?,
        val nearMe: Boolean, val house: String?, val partial: Boolean, val stateDropped: Boolean,
    )

    private val db: SqlDb = SqlDb.openReadOnly(dbPath)
    val norm = SearchNormalizer(tables)
    private val stop = tables.stop
    private val catLabel = HashMap<Long, String?>()
    private val catCode = LinkedHashMap<Long, String>()
    private val catIdByCode = HashMap<String, Long>()
    val meta: Map<String, String?>
    /** minlat, maxlat, minlon, maxlon of the pack's state. */
    val bbox: DoubleArray?
    private val hasPoiPrefix: Boolean
    /** The pack carries front doors (the poi_door table; packs built before 2026-11-21 do not). */
    val hasPoiDoor: Boolean
    /** The pack carries the streets' lines (the street_line table; packs built before 2026-11-22 do not). */
    val hasStreetLines: Boolean
    /** The pack carries the traffic lights (the signal table; packs built before 2026-11-22 do not). */
    val hasSignals: Boolean
    private val lineCache = HashMap<Long, List<IntArray>>()
    private val hasAddrKey: Boolean
    private val stateNames = tables.stateNames
    private val streetTypes = tables.streetTypes
    private val noFix: Set<String>
    var addrOk = false
        private set
    var addrWarning: String? = null
        private set
    /** Normaliser table version the search index's house-number join key was built with. */
    val addrKeyTableVersion: String?
    /** The same version as read from the attached addresses database (null when none attached). */
    var addressesTableVersion: String? = null
        private set
    private val connWords: List<Pair<List<String>, String>>
    private val nearMePhrases: List<List<String>>

    init {
        try {
            for (r in db.query("SELECT id, code, label FROM category")) {
                val id = r[0] as Long
                catLabel[id] = r[2] as String?
                catCode[id] = r[1] as String
            }
            catCode.forEach { (k, v) -> catIdByCode[v] = k }
            meta = db.query("SELECT key, value FROM meta").associate { (it[0] as String) to (it[1] as String?) }
            check(meta["schema_version"] == SCHEMA_VERSION) {
                "search database $dbPath has schema version ${meta["schema_version"]}; this app reads version " +
                    "$SCHEMA_VERSION. Install a matching pack."
            }
            bbox =meta["bbox"]?.let { b -> b.split(",").map { it.trim().toDouble() }.toDoubleArray() }
            val tableNames = db.query("SELECT name FROM sqlite_master WHERE type = 'table'").map { it[0] as String }.toSet()
            hasPoiPrefix = "poi_prefix" in tableNames
            hasPoiDoor = "poi_door" in tableNames
            hasStreetLines = "street_line" in tableNames
            hasSignals = "signal" in tableNames
            hasAddrKey = db.query("PRAGMA table_info(street)").any { it[1] == "addr_key" }
            val nf = HashSet<String>()
            for (words in tables.connectors.values) for (ph in words) nf.addAll(Py.split(ph))
            for (ph in tables.nearMe) nf.addAll(Py.split(ph))
            noFix = nf
            addrKeyTableVersion = meta["addr_key"]?.let { s ->
                runCatching { Json.parseToJsonElement(s).jsonObject["table_version"]?.jsonPrimitive?.content }.getOrNull()
            }
            if (addressesPath != null && File(addressesPath).exists()) attachAddresses(addressesPath)
            connWords = tables.connectors.entries.flatMap { (kind, words) -> words.map { Py.split(it) to kind } }
                .sortedByDescending { it.first.size }
            nearMePhrases = tables.nearMe.map { Py.split(it) }.sortedByDescending { it.size }
        } catch (e: Throwable) {
            db.close()
            throw e
        }
    }

    override fun close() = db.close()

    /** SQLite version and compile options of the driver (for the report and the demo). */
    fun sqliteInfo(): Pair<String, List<String>> =
        (db.first("SELECT sqlite_version()")!![0] as String) to db.query("PRAGMA compile_options").map { it[0] as String }

    /**
     * Decision 6.17 (report 10 #4): reads the small hot tables whole (categories and their terms,
     * brands and their aliases) and walks the indexes the first typed letters use (the vocabulary
     * words, the place keys), so their pages are in SQLite's and the system's caches before the
     * first query. Then two cheap searches at the pack's centre: a one-letter prefix (places by key
     * prefix, POIs from poi_prefix) and a category browse. Answers are thrown away.
     */
    fun warmUp() {
        for (sql in WARM_UP_SQL) runCatching { db.query(sql) }
        val b = bbox
        val lat = b?.let { (it[0] + it[1]) / 2 }
        val lon = b?.let { (it[2] + it[3]) / 2 }
        search("a", lat, lon, 1)
        search("gas", lat, lon, 1)
    }

    /**
     * Opening hours, phone and website of the POI entries [ids] (the pack's `poi` table, from
     * OpenStreetMap: `opening_hours`, `phone` or `contact:phone`, `website` or `contact:website`;
     * batch B, docs/architecture.md S10), with the postal address and the category in words (batch G,
     * the place sheet's details height) and the OSM object (batch O2, "Edit on OpenStreetMap"). Ids
     * that are not POIs, and POIs with none of these, are left out. Not part of [search]: its answers (and the conformance reference) stay as they are.
     */
    fun poiDetails(ids: Collection<Long>): Map<Long, PoiDetails> {
        val list = ids.filter { it > 0 }.distinct()
        val out = HashMap<Long, PoiDetails>()
        val state = meta["state"]?.trim()?.takeIf { it.isNotEmpty() }
        var i = 0
        while (i < list.size) {
            val chunk = list.subList(i, minOf(i + 900, list.size))
            for (r in db.query(
                "SELECT p.entry_id, p.opening_hours, p.phone, p.website, p.addr_housenumber, p.addr_street, p.addr_city, p.addr_postcode, " +
                    "e.type, c.label, p.osm FROM poi p JOIN entry e ON e.id = p.entry_id LEFT JOIN category c ON c.code = e.type " +
                    "WHERE p.entry_id IN (${chunk.joinToString(",") { "?" }})",
                chunk,
            )) {
                val d = PoiDetails(
                    clean(r[1]), clean(r[2]), clean(r[3]),
                    address = postalAddress(clean(r[4]), clean(r[5]), clean(r[6]), clean(r[7]), state),
                    category = clean(r[9]) ?: categoryWords(clean(r[8])),
                    osm = clean(r[10]),
                )
                if (d.hasContact || d.address != null || d.category != null || d.osm != null) out[r[0] as Long] = d
            }
            i += 900
        }
        return out
    }

    private fun clean(v: Any?): String? = (v as? String)?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * Rule S10 for a place without a search id (a POI tapped on the map, a saved place, a recent):
     * hours, phone and website of the POIs whose name has the same key as [name] within [withinM]
     * metres of [lat], [lon], with the id of the nearest that has them, when they are sure: every
     * such POI that has hours, phone or website has the same ones (one shop often comes as several
     * POIs: a Sheetz near Erie is a convenience store and two fuel canopies within 40 m, only the
     * store has hours). When none of them has any, the nearest one's address and category (batch G).
     * Null when none has details or their hours, phone or website differ (never a guess).
     */
    fun detailsNamedNear(name: String, lat: Double, lon: Double, withinM: Double): Pair<Long, PoiDetails>? {
        val ids = poisNamedNear(name, lat, lon, withinM)
        val d = poiDetails(ids)
        val contact = ids.mapNotNull { d[it] }.filter { it.hasContact }
        if (contact.map { Triple(it.openingHours, it.phone, it.website) }.distinct().size > 1) return null
        val id = ids.firstOrNull { d[it]?.hasContact == true } ?: ids.firstOrNull { it in d } ?: return null
        return id to d.getValue(id)
    }

    /**
     * Whether the entry [id] is still the place it was (batch G): a POI whose name has the key of
     * [name] within [withinM] metres of [lat], [lon]. A search id kept by a recent may name another
     * entry once the pack is rebuilt (between the 2026-11-16 and 2026-11-20 Pennsylvania builds the
     * ids moved by up to a few thousand), so a sheet reads details by id only when this holds.
     */
    fun isNamedNear(id: Long, name: String, lat: Double, lon: Double, withinM: Double): Boolean {
        if (id <= 0) return false
        val key = norm.key(name)
        if (key.isEmpty()) return false
        val r = db.query("SELECT name_key, kind, lat, lon FROM entry WHERE id = ?", listOf(id)).firstOrNull() ?: return false
        if (r[0] as? String != key || (r[1] as Number).toInt() != KIND_POI) return false
        val cosLat = StrictMath.cos(Math.toRadians(lat)).coerceAtLeast(0.01)
        val dy = (num(r[2]) - lat) * M_PER_DEG_LAT
        val dx = (num(r[3]) - lon) * M_PER_DEG_LAT * cosLat
        return dx * dx + dy * dy <= withinM * withinM
    }

    /**
     * Where customers go into the place named [name] at [lat], [lon] (docs/next-round.md item 28; the
     * pack's poi_door table, packbuilder/search/doors.py): the door of the nearest POI of that name
     * within [withinM] metres that has one, as (lat, lon). Null when none has (a small building, a
     * place that is not a building, a pack without the table).
     */
    fun doorNamedNear(name: String, lat: Double, lon: Double, withinM: Double): Pair<Double, Double>? {
        if (!hasPoiDoor) return null
        for (id in poisNamedNear(name, lat, lon, withinM)) {
            val r = db.query("SELECT lat, lon FROM poi_door WHERE entry_id = ?", listOf(id)).firstOrNull() ?: continue
            return num(r[0]) to num(r[1])
        }
        return null
    }

    /** The POIs whose name has the same key as [name] within [withinM] metres of [lat], [lon], nearest first. */
    fun poisNamedNear(name: String, lat: Double, lon: Double, withinM: Double): List<Long> {
        val key = norm.key(name)
        if (key.isEmpty() || withinM <= 0) return emptyList()
        val dLat = withinM / M_PER_DEG_LAT
        val cosLat = StrictMath.cos(Math.toRadians(lat)).coerceAtLeast(0.01)
        val dLon = dLat / cosLat
        val near = db.query(
            "SELECT id, lat, lon FROM entry WHERE name_key = ? AND kind = ? AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ?",
            listOf(key, KIND_POI.toLong(), lat - dLat, lat + dLat, lon - dLon, lon + dLon),
        ).map { r ->
            val dy = (num(r[1]) - lat) * M_PER_DEG_LAT
            val dx = (num(r[2]) - lon) * M_PER_DEG_LAT * cosLat
            (r[0] as Number).toLong() to dx * dx + dy * dy
        }.filter { it.second <= withinM * withinM }
        return near.sortedWith(compareBy({ it.second }, { it.first })).map { it.first }
    }

    /**
     * Batch O3 (docs/online-design.md 4.3, docs/next-round.md items 25 and 27): the named POIs within
     * [withinM] metres of [lat], [lon], nearest first (by the entry R*Tree), with their category, OSM
     * object and point: the duplicate check of "Add a missing place" and the "Already on the map here" line.
     */
    fun poisNear(lat: Double, lon: Double, withinM: Double, limit: Int = 50): List<NearbyPoi> {
        if (withinM <= 0) return emptyList()
        val dLat = withinM / M_PER_DEG_LAT
        val cosLat = StrictMath.cos(Math.toRadians(lat)).coerceAtLeast(0.01)
        val dLon = dLat / cosLat
        val rows = db.query(
            "SELECT e.id, e.name, e.type, c.label, p.osm, e.lat, e.lon, p.opening_hours, p.phone, p.website, " +
                "p.addr_housenumber, p.addr_street FROM entry_rtree r JOIN entry e ON e.id = r.id " +
                "JOIN poi p ON p.entry_id = e.id LEFT JOIN category c ON c.code = e.type " +
                "WHERE r.maxlat >= ? AND r.minlat <= ? AND r.maxlon >= ? AND r.minlon <= ? AND e.kind = ?",
            listOf(lat - dLat, lat + dLat, lon - dLon, lon + dLon, KIND_POI.toLong()),
        )
        return rows.mapNotNull { r ->
            val dy = (num(r[5]) - lat) * M_PER_DEG_LAT
            val dx = (num(r[6]) - lon) * M_PER_DEG_LAT * cosLat
            val d = StrictMath.sqrt(dx * dx + dy * dy)
            if (d > withinM) return@mapNotNull null
            NearbyPoi(
                id = (r[0] as Number).toLong(), name = r[1] as String, code = clean(r[2]),
                label = clean(r[3]) ?: categoryWords(clean(r[2])), osm = clean(r[4]), lat = num(r[5]), lon = num(r[6]),
                distanceM = d, openingHours = clean(r[7]), phone = clean(r[8]), website = clean(r[9]),
                address = listOfNotNull(clean(r[10]), clean(r[11])).joinToString(" ").takeIf { it.isNotEmpty() },
            )
        }.sortedWith(compareBy({ it.distanceM }, { it.id })).take(limit)
    }

    /**
     * Batch O3 (docs/next-round.md item 27, "View all"): every category of the pack, in its table's
     * order, with the term a tap types ([CategoryInfo.query]): the label itself when it is a term of
     * this category alone (weight 1), else the category's own term sharing the most words with the
     * label (then the shortest), so the search finds that category as a chip's query does.
     */
    fun categories(): List<CategoryInfo> {
        val own = HashMap<Long, MutableList<String>>()
        for (r in db.query(
            "SELECT t.cat_id, t.term FROM category_term t WHERE t.weight >= 1.0 AND " +
                "(SELECT count(*) FROM category_term u WHERE u.term = t.term AND u.cat_id <> t.cat_id AND u.weight >= 1.0) = 0",
        )) own.getOrPut((r[0] as Number).toLong()) { ArrayList() }.add(r[1] as String)
        return db.query("SELECT id, code, label, grp, umbrella FROM category ORDER BY id").map { r ->
            val id = (r[0] as Number).toLong()
            val code = r[1] as String
            val label = clean(r[2]) ?: categoryWords(code) ?: code
            val terms = own[id].orEmpty()
            val lab = label.lowercase()
            val words = lab.split(' ', ',', '(', ')', '&').filter { it.length > 2 && it != "and" }.toSet()
            val query = if (lab in terms) lab else terms.maxWithOrNull(
                compareBy<String>({ t -> t.split(' ').count { it in words } }, { -it.length }),
            ) ?: lab
            CategoryInfo(code, label, clean(r[3]), (r[4] as? Number)?.toInt() == 1, query)
        }
    }

    /** The [n] most common 3-letter word starts of the vocabulary (measure.py's typing set). */
    fun commonStarts(n: Int): List<String> {
        val starts = LinkedHashMap<String, Long>()
        for (r in db.query("SELECT word, freq FROM vocab WHERE length(word) >= 3")) {
            val p = (r[0] as String).take(3)
            if (Py.isAlpha(p)) starts[p] = (starts[p] ?: 0L) + (r[1] as Long)
        }
        return starts.entries.sortedByDescending { it.value }.take(n).map { it.key }
    }

    private fun attachAddresses(addressesPath: String) {
        if (!hasAddrKey) {
            throw IllegalStateException("search database has no street.addr_key column: rebuild it with build.py or open the engine without addressesPath")
        }
        val built = addrKeyTableVersion
        if (built.isNullOrEmpty()) throw IllegalStateException("search database was built without the house-number join key: ${meta["addr_key"]}")
        db.query("ATTACH DATABASE ? AS addr", listOf(SqlDb.readOnlyUri(File(addressesPath))))
        val r = try {
            db.first("SELECT value FROM addr.${rk.addrVersionTable} WHERE key = ?", rk.addrVersionKey)
        } catch (_: SQLiteException) {
            null   // no meta table: treated as a missing version
        }
        addressesTableVersion = r?.get(0)?.toString()
        val port = StreetTables.load().version
        val problem = when {
            addressesTableVersion == null -> "addresses database $addressesPath records no normalisation table version " +
                "(${rk.addrVersionTable}.${rk.addrVersionKey}); the search database was keyed with version $built"
            addressesTableVersion != built -> "addresses database uses normalisation table version $addressesTableVersion, " +
                "the search database was keyed with version $built: rebuild whichever is older"
            built != port -> "the search and address packs were keyed with normalisation table version $built; " +
                "this app implements version $port. Install matching packs."
            else -> null
        }
        if (problem != null) {
            if (!allowAddrVersionMismatch) {
                db.exec("DETACH DATABASE addr")
                throw IllegalStateException(problem)
            }
            addrWarning = "$problem (attached anyway: allowAddrVersionMismatch)"
        }
        db.query("SELECT ${rk.addrHousenumber}, ${rk.addrStreetKey}, ${rk.addrLat}, ${rk.addrLon} FROM addr.${rk.addrTable} LIMIT 1")
        addrOk = true
    }

    // ------------------------------------------------------------------ word lookups
    private fun vocabFreq(tok: String, prefix: Boolean = false): Long {
        if (prefix) {
            return if (db.first("SELECT 1 FROM vocab WHERE word >= ? AND word < ? LIMIT 1", tok, prefixUpper(tok)) != null) 1 else 0
        }
        return (db.first("SELECT freq FROM vocab WHERE word = ?", tok)?.get(0) as Long?) ?: 0
    }

    /** Typo candidates (word, distance, freq), best first: hashed distance-1 deletions, then trigrams. */
    private fun corrections(tok: String): List<Triple<String, Int, Long>> {
        if (tok.length < rk.fuzzyMinChars || tok.any { it.isDigit() }) return emptyList()
        val hs = LinkedHashSet<Int>()
        for (d in SearchNormalizer.deletes1(tok)) hs.add(SearchNormalizer.fnv1a32(d))
        val q = "SELECT DISTINCT v.word, v.freq FROM spell_del d JOIN vocab v ON v.id = d.word_id " +
            "WHERE d.h IN (${hs.joinToString(",") { "?" }})"
        val out = ArrayList<Triple<String, Int, Long>>()
        for (r in db.query(q, hs.map { it.toLong() })) {
            val w = r[0] as String
            val d = osaDistance(tok, w)
            if (d <= 1 && w != tok) out.add(Triple(w, d, r[1] as Long))
        }
        if (out.isEmpty() && tok.length >= rk.trigramFallbackFromChars) {
            val grams = (0 until tok.length - 2).map { tok.substring(it, it + 3) }.toSortedSet()
            val match = grams.joinToString(" OR ") { ftsQuote(it) }
            val rows = try {
                db.query("SELECT v.word, v.freq FROM vocab_tri t JOIN vocab v ON v.id = t.rowid " +
                    "WHERE vocab_tri MATCH ? ORDER BY rank LIMIT ?", listOf(match, rk.trigramCandidates))
            } catch (_: SQLiteException) {
                emptyList()
            }
            for (r in rows) {
                val w = r[0] as String
                val d = osaDistance(tok, w)
                if (d <= rk.trigramMaxDistance && w != tok) out.add(Triple(w, d, r[1] as Long))
            }
        }
        val sorted = out.sortedWith(compareBy<Triple<String, Int, Long>> { it.second }.thenBy { -it.third })
        return sorted.take(rk.fuzzyCandidatesPerToken)
    }

    private fun categoryLookup(phKey: String, prefixKey: String? = null): LinkedHashMap<Long, Double> {
        if (phKey.isEmpty()) return LinkedHashMap()
        var rows: List<Pair<Long, Double>> = db.query("SELECT cat_id, weight FROM category_term WHERE term = ?", phKey)
            .map { (it[0] as Long) to num(it[1]) }
        if (rows.isEmpty() && phKey.endsWith("s") && phKey.length > 3) {
            rows = db.query("SELECT cat_id, weight FROM category_term WHERE term IN (?, ?)",
                phKey.dropLast(1), if (phKey.endsWith("es")) phKey.dropLast(2) else phKey.dropLast(1))
                .map { (it[0] as Long) to num(it[1]) }
        }
        var factor = 1.0
        if (rows.isEmpty() && !prefixKey.isNullOrEmpty() && prefixKey.length >= 3) {
            val found = db.query("SELECT cat_id, weight, term FROM category_term WHERE term >= ? AND term < ? LIMIT 50",
                prefixKey, prefixUpper(prefixKey))
            if (found.isNotEmpty()) {
                val shortest = found.minOf { (it[2] as String).length }
                rows = found.map { (it[0] as Long) to (num(it[1]) * shortest / (it[2] as String).length) }
            }
            factor = rk.prefixFactor
        }
        if (rows.isEmpty()) return LinkedHashMap()
        val best = rows.maxOf { it.second }
        val keep = rk.categoryKeepRatio
        val out = LinkedHashMap<Long, Double>()
        for ((c, w) in rows) {
            if (w >= best * keep) out[c] = maxOf(out[c] ?: 0.0, w * factor)
        }
        return out
    }

    private fun brandLookup(phKey: String, prefixKey: String? = null): LinkedHashMap<Long, Double> {
        if (phKey.isEmpty()) return LinkedHashMap()
        val keys = listOf(phKey) + (if (' ' in phKey) listOf(phKey.replace(" ", "")) else emptyList())
        val rows = ArrayList<Triple<Long, Long, Double>>()
        db.query("SELECT a.brand_id, a.count, 1.0 FROM brand_alias a JOIN brand b ON b.id = a.brand_id " +
            "WHERE a.alias IN (${keys.joinToString(",") { "?" }}) AND b.poi_count >= ?", keys + rk.brandMinPois)
            .mapTo(rows) { Triple(it[0] as Long, it[1] as Long, num(it[2])) }
        if (prefixKey != null && prefixKey.length >= rk.brandPrefixMinChars) {
            val short = prefixKey.length < 3
            for (k in linkedSetOf(prefixKey, prefixKey.replace(" ", ""))) {
                db.query("SELECT brand_id, count, ? FROM brand_alias WHERE alias > ? AND alias < ? " +
                    "AND count >= ? ORDER BY count DESC LIMIT 20",
                    rk.prefixFactor, k, prefixUpper(k), if (short) rk.shortBrandMinCount else rk.brandMinPois)
                    .mapTo(rows) { Triple(it[0] as Long, it[1] as Long, num(it[2])) }
            }
        }
        if (rows.isEmpty()) return LinkedHashMap()
        val top = rows.maxOf { it.second }
        val out = LinkedHashMap<Long, Double>()
        for ((b, c, f) in rows) {
            if (c.toDouble() >= maxOf(1.0, 0.05 * top)) out[b] = maxOf(out[b] ?: 0.0, f)
        }
        return out
    }

    private fun placeLookup(key: String, anchor: LatLon?, allowPrefix: Boolean = false, suffixes: Boolean = true): List<Entry> {
        var rows = entries("SELECT $ENTRY_COLS FROM entry e WHERE e.name_key = ? AND e.kind = 1", key)
        if (rows.isEmpty() && suffixes) {
            rows = entries("SELECT $ENTRY_COLS FROM entry e WHERE e.name_key IN (?, ?, ?) AND e.kind = 1",
                "$key township", "$key county", "$key borough")
        }
        if (rows.isEmpty() && allowPrefix && key.length >= 3) {
            rows = entries("SELECT $ENTRY_COLS FROM entry e WHERE e.name_key >= ? AND e.name_key < ? " +
                "AND e.kind = 1 ORDER BY e.importance DESC LIMIT 10", key, prefixUpper(key))
        }
        if (rows.isEmpty()) {
            rows = if (key.isNotEmpty()) entries(
                "SELECT $ENTRY_COLS FROM entry_fts f JOIN entry e ON e.id = f.rowid " +
                    "WHERE entry_fts MATCH ? AND e.kind = 1 ORDER BY e.importance DESC LIMIT 20",
                Py.split(key).joinToString(" AND ") { ftsQuote(it) }) else emptyList()
            rows = rows.filter { key in (it.alt ?: "").split("|") }
        }
        return rows.sortedBy { r ->
            val d = if (anchor != null) Geo.haversineKm(anchor.lat, anchor.lon, r.lat, r.lon) else 0.0
            -(r.imp - minOf(d, 300.0) / 1000.0)
        }
    }

    private fun nameExists(key: String): Boolean = db.first("SELECT 1 FROM entry WHERE name_key = ? LIMIT 1", key) != null

    // ------------------------------------------------------------------ parsing
    fun parse(text: String): Parsed {
        var partial = !text.endsWith(" ")
        var toks = Py.split(norm.fold(text))
        var nearMe = false
        for (ph in nearMePhrases) {
            val n = ph.size
            for (i in 0 until toks.size - n + 1) {
                if (toks.subList(i, i + n) == ph) {
                    nearMe = true
                    if (i + n == toks.size) partial = false
                    toks = toks.subList(0, i) + toks.subList(i + n, toks.size)
                    break
                }
            }
        }
        val (dropped, stateDropped) = dropState(toks)
        toks = dropped
        if (stateDropped == "last") partial = false
        var house: String? = null
        if (toks.size >= 2 && HOUSE_RE.matches(toks[0]) && !numberStartsName(toks)) {
            house = toks[0]
            toks = toks.drop(1)
        }
        var conn: String? = null
        var head = toks
        var tail = emptyList<String>()
        outer@ for (i in 1 until toks.size) {
            for ((ph, kind) in connWords) {
                val n = ph.size
                if (i + n <= toks.size && toks.subList(i, i + n) == ph && i + n < toks.size) {
                    conn = kind
                    head = toks.subList(0, i)
                    tail = toks.subList(i + n, toks.size)
                    break@outer
                }
            }
        }
        return Parsed(toks, head, tail, conn, nearMe, house, partial, stateDropped != null)
    }

    private fun knownPhrase(key: String): Boolean {
        if (key.isEmpty()) return false
        if (nameExists(key) || categoryLookup(key).isNotEmpty()) return true
        return db.first("SELECT 1 FROM brand_alias WHERE alias IN (?, ?) LIMIT 1", key, key.replace(" ", "")) != null
    }

    private fun dropState(toks: List<String>): Pair<List<String>, String?> {
        if (toks.size < 2) return toks to null
        val cand: List<String>
        val how: String
        if (toks.last() in stateNames) {
            cand = toks.dropLast(1); how = "last"
        } else if (toks.size >= 3 && toks[toks.size - 2] in stateNames && ZIP5_RE.matches(toks.last())) {
            cand = toks.dropLast(2) + toks.last(); how = "before_zip"
        } else {
            return toks to null
        }
        if (knownPhrase(norm.key(toks.joinToString(" ")))) return toks to null
        return cand to how
    }

    private fun numberStartsName(toks: List<String>): Boolean {
        var head = toks
        for (i in 1 until toks.size - 1) {
            if (connWords.any { (ph, _) -> i + ph.size <= toks.size && toks.subList(i, i + ph.size) == ph }) {
                head = toks.subList(0, i)
                break
            }
        }
        if (norm.expandTokens(head.drop(1)).any { it in streetTypes }) return false
        for (n in setOf(head.size, 2).sortedDescending()) {
            if (n > head.size) continue
            val k = norm.key(head.subList(0, n).joinToString(" "))
            if (knownPhrase(k)) return true
            if (db.first("SELECT 1 FROM entry WHERE name_key >= ? AND name_key < ? AND kind IN (1, 3) LIMIT 1",
                    k, prefixUpper(k)) != null) return true
        }
        return false
    }

    // ------------------------------------------------------------------ candidate fetchers
    private fun fts(match: String, anchor: LatLon?, kinds: List<Int>? = null, limit: Int? = null): List<Entry> {
        val lim = limit ?: rk.ftsCandidates
        val kindSql = if (kinds != null) " AND e.kind IN (${kinds.joinToString(",")})" else ""
        val rows = ArrayList<Entry>()
        try {
            if (anchor != null) {
                val c = StrictMath.cos(Math.toRadians(anchor.lat))
                val k = c * c
                rows += entries("SELECT $ENTRY_COLS FROM entry_fts f JOIN entry e ON e.id = f.rowid WHERE entry_fts MATCH ?$kindSql " +
                    "ORDER BY (e.lat - ?) * (e.lat - ?) + (e.lon - ?) * (e.lon - ?) * ? LIMIT ?",
                    match, anchor.lat, anchor.lat, anchor.lon, anchor.lon, k, lim)
            }
            rows += entries("SELECT $ENTRY_COLS FROM entry_fts f JOIN entry e ON e.id = f.rowid WHERE entry_fts MATCH ?$kindSql " +
                "ORDER BY e.importance DESC LIMIT ?", match, if (anchor != null) lim / 4 else lim)
        } catch (_: SQLiteException) {
            // FTS5 syntax error: what was fetched so far is kept (as in the reference)
        }
        return rows
    }

    private fun ftsMatch(options: List<List<String>>, partial: Boolean, lastRaw: String?): String {
        val parts = ArrayList<String>()
        for ((i, opts) in options.withIndex()) {
            var alts = opts.map { ftsQuote(it) }
            if (i == options.size - 1 && partial) {
                alts = alts.map { "$it*" }
                if (!lastRaw.isNullOrEmpty() && lastRaw !in opts) alts = alts + (ftsQuote(lastRaw) + "*")
            }
            parts.add(if (alts.size == 1) alts[0] else "(" + alts.joinToString(" OR ") + ")")
        }
        return parts.joinToString(" AND ")
    }

    private fun shortPrefixCandidates(key: String, anchor: LatLon?): List<Entry> {
        val hi = prefixUpper(key)
        val rows = entries("SELECT $ENTRY_COLS FROM entry e WHERE e.kind = 1 AND e.name_key >= ? AND e.name_key < ? " +
            "ORDER BY e.importance DESC LIMIT 30", key, hi)
        if (anchor != null && hasPoiPrefix) {
            val p3 = key.take(3)
            val la0 = floor(anchor.lat * 10).toLong()
            val lo0 = floor(anchor.lon * 10).toLong()
            val ids = ArrayList<Long>()
            val done = HashSet<Long>()
            for (ring in rk.shortPrefixPoiRings) {
                val cells = ArrayList<Long>()
                for (la in la0 - ring..la0 + ring) for (lo in lo0 - ring..lo0 + ring) {
                    val c = (la + 900) * 4000 + lo + 1800
                    if (c !in done) cells.add(c)
                }
                done.addAll(cells)
                db.query("SELECT entry_id FROM poi_prefix WHERE cell IN (${cells.joinToString(",")}) " +
                    "AND p3 >= ? AND p3 < ? LIMIT 5000", p3, prefixUpper(p3)).mapTo(ids) { it[0] as Long }
                if (ids.size >= rk.shortPrefixPoiMinHits) break
            }
            var near = ArrayList<Entry>()
            var i = 0
            while (i < ids.size) {
                val chunk = ids.subList(i, minOf(i + 900, ids.size))
                near.addAll(entries("SELECT $ENTRY_COLS FROM entry e WHERE e.id IN (${chunk.joinToString(",") { "?" }})", chunk))
                i += 900
            }
            if (key.length > 3) near = ArrayList(near.filter { it.key.startsWith(key) })
            val c = StrictMath.cos(Math.toRadians(anchor.lat))
            val k = c * c
            val sorted = near.sortedBy { r -> (r.lat - anchor.lat) * (r.lat - anchor.lat) + (r.lon - anchor.lon) * (r.lon - anchor.lon) * k }
            return rows + sorted.take(rk.shortPrefixPoiKeep)
        }
        val out = ArrayList(rows)
        if (anchor != null) {
            // databases built before poi_prefix existed
            for (rKm in listOf(5.0, 20.0)) {
                val dlat = rKm / 111.0
                val dlon = rKm / (111.0 * maxOf(0.2, StrictMath.cos(Math.toRadians(anchor.lat))))
                val near = entries("SELECT $ENTRY_COLS FROM entry_rtree r JOIN entry e ON e.id = r.id " +
                    "WHERE r.maxlat >= ? AND r.minlat <= ? AND r.maxlon >= ? AND r.minlon <= ? " +
                    "AND e.name_key >= ? AND e.name_key < ? AND e.kind = 3 LIMIT 300",
                    anchor.lat - dlat, anchor.lat + dlat, anchor.lon - dlon, anchor.lon + dlon, key, hi)
                out += near
                if (near.size >= 20) break
            }
        }
        return out
    }

    private fun browseCategory(catIds: Collection<Long>, anchor: LatLon?, limit: Int? = null): List<Entry> {
        val cats = catIds.joinToString(",")
        val want = limit ?: rk.browseMinHits
        if (anchor == null) {
            return entries("SELECT $ENTRY_COLS, c.cat_id FROM poi_cat c JOIN entry e ON e.id = c.entry_id " +
                "WHERE c.cat_id IN ($cats) ORDER BY e.importance DESC LIMIT ?", want)
        }
        var rows: List<Entry> = emptyList()
        for (rKm in rk.nearRadiusKm) {
            val dlat = rKm / 111.0
            val dlon = rKm / (111.0 * maxOf(0.2, StrictMath.cos(Math.toRadians(anchor.lat))))
            val box = arrayOf<Any?>(anchor.lat - dlat, anchor.lat + dlat, anchor.lon - dlon, anchor.lon + dlon)
            rows = if (rKm <= rk.browseRtreeFirstMaxKm) {
                entries("SELECT $ENTRY_COLS, c.cat_id FROM entry_rtree r CROSS JOIN poi_cat c CROSS JOIN entry e " +
                    "WHERE c.entry_id = r.id AND e.id = r.id AND c.cat_id IN ($cats) " +
                    "AND r.maxlat >= ? AND r.minlat <= ? AND r.maxlon >= ? AND r.minlon <= ?", *box)
            } else {
                entries("SELECT $ENTRY_COLS, c.cat_id FROM entry_rtree r JOIN poi_cat c ON c.entry_id = r.id " +
                    "JOIN entry e ON e.id = r.id WHERE c.cat_id IN ($cats) " +
                    "AND r.maxlat >= ? AND r.minlat <= ? AND r.maxlon >= ? AND r.minlon <= ?", *box)
            }
            if (rows.size >= want) break
        }
        return rows
    }

    private fun browseBrand(brandIds: Collection<Long>, anchor: LatLon?, limit: Int = 60): List<Entry> {
        val b = brandIds.joinToString(",")
        if (anchor == null) {
            return entries("SELECT $ENTRY_COLS FROM entry e WHERE e.brand_id IN ($b) ORDER BY e.importance DESC LIMIT ?", limit)
        }
        val c = StrictMath.cos(Math.toRadians(anchor.lat))
        val k = c * c
        return entries("SELECT $ENTRY_COLS FROM entry e WHERE e.brand_id IN ($b) " +
            "ORDER BY (e.lat - ?) * (e.lat - ?) + (e.lon - ?) * (e.lon - ?) * ? LIMIT ?",
            anchor.lat, anchor.lat, anchor.lon, anchor.lon, k, limit)
    }

    private fun inBbox(bbox: DoubleArray, catIds: Collection<Long>? = null, brandIds: Collection<Long>? = null): List<Entry> {
        val (minlat, maxlat, minlon, maxlon) = bbox.toList()
        if (!catIds.isNullOrEmpty() && brandIds.isNullOrEmpty()) {
            return entries("SELECT DISTINCT $ENTRY_COLS FROM entry_rtree r CROSS JOIN poi_cat c CROSS JOIN entry e " +
                "WHERE c.entry_id = r.id AND e.id = r.id AND c.cat_id IN (${catIds.joinToString(",")}) " +
                "AND r.maxlat >= ? AND r.minlat <= ? AND r.maxlon >= ? AND r.minlon <= ? AND e.kind = 3 LIMIT 500",
                minlat, maxlat, minlon, maxlon)
        }
        var cond = ""
        if (!catIds.isNullOrEmpty()) cond += " AND e.id IN (SELECT entry_id FROM poi_cat WHERE cat_id IN (${catIds.joinToString(",")}))"
        if (!brandIds.isNullOrEmpty()) cond += " AND e.brand_id IN (${brandIds.joinToString(",")})"
        return entries("SELECT $ENTRY_COLS FROM entry_rtree r JOIN entry e ON e.id = r.id " +
            "WHERE r.maxlat >= ? AND r.minlat <= ? AND r.maxlon >= ? AND r.minlon <= ? AND e.kind = 3$cond LIMIT 500",
            minlat, maxlat, minlon, maxlon)
    }

    // ------------------------------------------------------------------ the streets' lines (query.py step 7)
    /** The lines of a street entry; none without one, or when its blob is broken (query.py street_lines). */
    private fun streetLines(eid: Long): List<IntArray> {
        if (!hasStreetLines) return emptyList()
        synchronized(lineCache) { lineCache[eid] }?.let { return it }
        val blob = db.first("SELECT line FROM street_line WHERE entry_id = ?", eid)?.get(0) as? ByteArray
        val lines = if (blob == null) emptyList() else try {
            StreetLines.decode(blob)
        } catch (_: IllegalArgumentException) {
            emptyList()
        }
        synchronized(lineCache) {
            if (lineCache.size >= 512) lineCache.clear()
            lineCache[eid] = lines
        }
        return lines
    }

    /** The streets one side of a crossing names, the nearest first, each once; true: by its own name (query.py _street_side). */
    private fun streetSide(toks: List<String>, partial: Boolean, user: LatLon?): List<Pair<Entry, Boolean>> {
        val ph = Phrase(toks, partial, stop, norm)
        if (!ph.truthy) return emptyList()
        val n = rk.crossCandidatesPerSide
        var key = ph.key
        var keyRaw = ph.keyRaw
        var rows = fts(ftsMatch(ph.exp.map { listOf(it) }, partial, ph.lastRaw), user, kinds = listOf(KIND_STREET), limit = n)
        if (rows.isEmpty()) {
            val fixed = ph.exp.map { bestFix(it) }
            if (fixed != ph.exp) {
                rows = fts(ftsMatch(fixed.map { listOf(it) }, partial, null), user, kinds = listOf(KIND_STREET), limit = n)
                key = fixed.joinToString(" ")
                keyRaw = key
            }
        }
        val ok = setOf("exact", "exact_alt", "prefix", "word_prefix")
        val out = ArrayList<Pair<Entry, Boolean>>()
        val seen = HashSet<Long>()
        for (r in rows) {
            if (r.id !in seen && (nameTier(key, r) in ok || nameTier(keyRaw, r) in ok)) {
                seen.add(r.id)
                val bare = Entry(r.id, r.kind, r.name, r.key, null, r.type, r.lat, r.lon, r.imp, r.muni, r.county, r.brand, r.sub)
                out.add(r to (nameTier(key, bare) in ok || nameTier(keyRaw, bare) in ok))
            }
        }
        return out
    }

    private class Meeting(val a: Entry, val b: Entry, val lat: Double, val lon: Double, val own: Boolean, val k: Int)

    /**
     * Where two streets the text names meet, as results, the nearest to [user] first (query.py crossings).
     * A result's id is negative and its own: the two street ids and the number of the meeting among that
     * pair's, so two crossings never share an id in a list or a store.
     */
    private fun crossings(text: String, user: LatLon?): List<SearchResult> {
        if (!hasStreetLines) return emptyList()
        val pp = parse(text.replace("@", " at "))
        if (pp.house != null) return emptyList()
        val toks = pp.tokens
        val partial = pp.partial
        var found = ArrayList<Meeting>()
        for (i in 1 until toks.size - 1) {
            if (toks[i] !in rk.crossWords) continue
            val left = streetSide(toks.subList(0, i), false, user)
            val right = if (left.isNotEmpty()) streetSide(toks.subList(i + 1, toks.size), partial, user) else emptyList()
            if (right.isEmpty()) continue
            val boxes = HashMap<Long, DoubleArray>()
            for (chunk in (left + right).map { it.first.id }.chunked(900)) {
                for (row in db.query("SELECT id, minlat, maxlat, minlon, maxlon FROM entry_rtree WHERE id IN (${chunk.joinToString(",") { "?" }})", chunk)) {
                    boxes[row[0] as Long] = doubleArrayOf(num(row[1]), num(row[2]), num(row[3]), num(row[4]))
                }
            }
            val m = rk.crossBoxMarginDeg
            var tests = 0
            var work = 0L
            for ((a, ownA) in left) {
                val ba = boxes[a.id]
                for ((b, ownB) in right) {
                    val bb = boxes[b.id]
                    if (a.id == b.id || a.key == b.key || ba == null || bb == null) continue
                    if (ba[0] > bb[1] + m || bb[0] > ba[1] + m || ba[2] > bb[3] + m || bb[2] > ba[3] + m) continue
                    if (tests >= rk.crossMaxPairs) break
                    val la = streetLines(a.id)
                    val lb = streetLines(b.id)
                    // the work of one pair: its segments against each other (query.py)
                    val cost = la.sumOf { (it.size / 2 - 1).toLong() } * lb.sumOf { (it.size / 2 - 1).toLong() }
                    if (work + cost > rk.crossMaxSegmentTests) continue
                    work += cost
                    tests++
                    for ((k, pt) in StreetLines.crossings(la, lb, rk.crossWithinM, rk.crossApartM).take(8).withIndex()) {
                        found.add(Meeting(a, b, pt.lat, pt.lon, ownA && ownB, k))
                    }
                }
            }
            if (found.isNotEmpty()) break
        }
        if (found.isEmpty()) return emptyList()
        if (found.any { it.own }) found = ArrayList(found.filter { it.own })
        val order: List<Meeting> = if (user != null) found.sortedBy { Geo.haversineKm(user.lat, user.lon, it.lat, it.lon) } else found   // stable
        val out = ArrayList<SearchResult>()
        val shown = ArrayList<LatLon>()
        for (f in order) {
            if (shown.any { s -> Geo.haversineKm(f.lat, f.lon, s.lat, s.lon) * 1000.0 < rk.crossApartM }) continue
            shown.add(LatLon(f.lat, f.lon))
            val lat = Py.round(f.lat, 6)
            val lon = Py.round(f.lon, 6)
            val d = if (user != null) Geo.haversineKm(user.lat, user.lon, lat, lon) else null
            out.add(SearchResult(
                id = -((((f.a.id shl 32) or f.b.id) shl 3) or f.k.toLong()), kind = SearchResult.Kind.STREET, title = "${f.a.name} & ${f.b.name}",
                subtitle = f.a.sub ?: "", categoryId = CROSSING_TYPE, categoryLabel = "Intersection", lat = lat, lon = lon,
                distanceKm = d?.let { Py.round(it, 2) }, brandId = null, address = null,
                score = rk.crossScore, how = "crossing", tier = "crossing",
            ))
            if (out.size >= rk.crossMaxResults) break
        }
        return out
    }

    /**
     * The traffic lights of the pack in the given cells (docs/next-round.md item 36; a cell is a square of
     * 0.01 degree: (floor(lat*100)+9000)*40000 + floor(lon*100)+18000, packbuilder/search/signals.py), each as
     * (lat, lon). None when the pack was built before them.
     */
    fun signals(cells: Collection<Long>): List<Pair<Double, Double>> {
        if (!hasSignals || cells.isEmpty()) return emptyList()
        val out = ArrayList<Pair<Double, Double>>()
        for (part in cells.distinct().sorted().chunked(SIGNAL_CELLS_PER_QUERY)) {
            val rows = db.query("SELECT lat, lon FROM signal WHERE cell IN (${part.joinToString(",") { "?" }})", part)
            for (r in rows) out += ((r[0] as Number).toDouble()) to ((r[1] as Number).toDouble())
        }
        return out
    }

    /**
     * The street at a point (a dropped pin): the street whose line is nearest, within ranking.json
     * near_street.within_m; null without one, without lines in the pack, or for a position that is not
     * usable. Equal distances: the more important street, then the lower id (query.py near_street).
     */
    fun nearStreet(lat: Double, lon: Double): NearStreet? {
        val at = validPosition(lat, lon) ?: return null
        if (!hasStreetLines) return null
        val dlat = rk.nearStreetWithinM / StreetLines.M_PER_DEG
        val dlon = rk.nearStreetWithinM / (StreetLines.M_PER_DEG * StrictMath.cos(Math.toRadians(at.lat)))
        val rows = db.query(
            "SELECT e.id, e.name, e.subtitle, e.importance FROM entry_rtree t JOIN entry e ON e.id = t.id " +
                "WHERE t.minlat <= ? AND t.maxlat >= ? AND t.minlon <= ? AND t.maxlon >= ? AND e.kind = 2 ORDER BY e.id LIMIT ?",
            at.lat + dlat, at.lat - dlat, at.lon + dlon, at.lon - dlon, rk.nearStreetMaxStreets,
        )
        var bestKey: DoubleArray? = null
        var best: NearStreet? = null
        for (r in rows) {
            val eid = r[0] as Long
            val nb = StreetLines.nearest(streetLines(eid), at.lat, at.lon) ?: continue
            if (nb[0] > rk.nearStreetWithinM) continue
            val key = doubleArrayOf(Py.round(nb[0], 1), -num(r[3]), eid.toDouble())
            if (bestKey == null || tupleLess(key, bestKey)) {
                bestKey = key
                best = NearStreet(eid, r[1] as String, (r[2] as String?) ?: "", Py.round(nb[0], 1), Py.round(nb[1], 6), Py.round(nb[2], 6))
            }
        }
        return best?.let { it.copy(house = houseNear(it.id, at)) }
    }

    /**
     * 0.1.40 (play test P6): the name of the town nearest a point: the place entry of type city, town,
     * borough or village ([AREA_PLACE_TYPES]) whose point is nearest, within [withinKm]; equal distances
     * (to 0.1 km): the more important, then the lower id. Null without one, or for a position that is
     * not usable. The search screen says "Near Corry" for a search around the map's centre. Kotlin only
     * (query.py has no counterpart; the conformance answers are unchanged).
     */
    fun placeNear(lat: Double, lon: Double, withinKm: Double): String? {
        val at = validPosition(lat, lon) ?: return null
        if (!withinKm.isFinite() || withinKm <= 0.0) return null
        val dlat = withinKm / 111.0
        val dlon = withinKm / (111.0 * maxOf(0.2, StrictMath.cos(Math.toRadians(at.lat))))
        val rows = db.query(
            "SELECT e.id, e.name, e.lat, e.lon, e.importance FROM entry_rtree r JOIN entry e ON e.id = r.id " +
                "WHERE r.maxlat >= ? AND r.minlat <= ? AND r.maxlon >= ? AND r.minlon <= ? AND e.kind = $KIND_PLACE " +
                "AND e.type IN (${AREA_PLACE_TYPES.joinToString(",") { "'$it'" }})",
            at.lat - dlat, at.lat + dlat, at.lon - dlon, at.lon + dlon,
        )
        var bestKey: DoubleArray? = null
        var best: String? = null
        for (r in rows) {
            val d = Geo.haversineKm(at.lat, at.lon, num(r[2]), num(r[3]))
            if (d > withinKm) continue
            val key = doubleArrayOf(Py.round(d, 1), -num(r[4]), (r[0] as Long).toDouble())
            if (bestKey == null || tupleLess(key, bestKey)) {
                bestKey = key
                best = r[1] as String
            }
        }
        return best
    }

    /**
     * The house number on street entry [eid] nearest to [at], among the address points of the street's
     * key within ranking.json near_street.house_within_m; equal distances: the lower number as text
     * (query.py _house_near). Null without the addresses database or when none is that near.
     */
    private fun houseNear(eid: Long, at: LatLon): String? {
        if (!addrOk || !hasAddrKey) return null
        val k = db.first("SELECT addr_key FROM street WHERE entry_id = ?", eid)?.get(0) as String?
        if (k.isNullOrEmpty()) return null
        val within = rk.nearStreetHouseWithinM
        val dlat = within / StreetLines.M_PER_DEG
        val dlon = within / (StreetLines.M_PER_DEG * StrictMath.cos(Math.toRadians(at.lat)))
        val rows = db.query(
            "SELECT ${rk.addrHousenumber}, ${rk.addrLat}, ${rk.addrLon} FROM addr.${rk.addrTable} WHERE ${rk.addrStreetKey} = ? " +
                "AND ${rk.addrLat} BETWEEN ? AND ? AND ${rk.addrLon} BETWEEN ? AND ?",
            k, at.lat - dlat, at.lat + dlat, at.lon - dlon, at.lon + dlon,
        )
        var bestD = 0.0
        var best: String? = null
        for (r in rows) {
            val hn = r[0]?.toString()
            if (hn.isNullOrEmpty()) continue
            val d = Geo.haversineKm(at.lat, at.lon, num(r[1]), num(r[2])) * 1000.0
            if (d > within) continue
            val rd = Py.round(d, 1)
            if (best == null || rd < bestD || (rd == bestD && hn < best)) {
                bestD = rd
                best = hn
            }
        }
        return best
    }

    private fun bboxOf(eid: Long): DoubleArray {
        val r = db.first("SELECT minlat, maxlat, minlon, maxlon FROM entry_rtree WHERE id = ?", eid)!!
        return doubleArrayOf(num(r[0]), num(r[1]), num(r[2]), num(r[3]))
    }

    private fun contextOf(ids: List<Long>): HashMap<Long, LinkedHashMap<Long, Double>> {
        val out = HashMap<Long, LinkedHashMap<Long, Double>>()
        var i = 0
        while (i < ids.size) {
            val chunk = ids.subList(i, minOf(i + 900, ids.size))
            for (r in db.query("SELECT entry_id, place_id, weight FROM context WHERE entry_id IN (${chunk.joinToString(",") { "?" }})", chunk)) {
                out.getOrPut(r[0] as Long) { LinkedHashMap() }[r[1] as Long] = num(r[2])
            }
            i += 900
        }
        return out
    }

    // ------------------------------------------------------------------ main entry
    /**
     * The number of results for [limit], as query.py's `limit or top_n`: null or 0 means
     * ranking.json's top_n. A negative limit is refused (query.py gives one result or several,
     * depending on the query, for those).
     */
    fun resolveLimit(limit: Int?): Int {
        require(limit == null || limit >= 0) { "limit must be 0 (the default) or more: $limit" }
        return if (limit == null || limit == 0) rk.topN else limit
    }

    /**
     * Searches [text] (as typed: a trailing space means the last word is finished) around an
     * optional position. A position that is not finite or out of range is ignored.
     */
    fun search(text: String, lat: Double? = null, lon: Double? = null, limit: Int? = null): SearchResponse {
        val t0 = System.nanoTime()
        @Suppress("NAME_SHADOWING")
        val text = capCodePoints(text, rk.maxQueryChars)   // rule S5, as query.py's text[:max_query_chars]
        val lim = resolveLimit(limit)
        val user = validPosition(lat, lon)
        val positionIgnored = user == null && (lat != null || lon != null)
        val outCov = outOfCoverage(user)
        val p = parse(text)
        val partial = p.partial
        val labels = ArrayList<ParseLabel>()
        val cands = LinkedHashMap<Long, Candidate>()

        fun add(row: Entry, score: Double, how: String, tier: String?) {
            val c = cands[row.id]
            if (c == null || score > c.score) cands[row.id] = Candidate(row, score, how, tier)
        }

        fun decay(row: Entry, anchor: LatLon?, scale: Double): Double {
            if (anchor == null) return 0.0
            return 1.0 / (1.0 + Geo.haversineKm(anchor.lat, anchor.lon, row.lat, row.lon) / scale)
        }

        // ---------- 1. explicit connector
        var ctxPlaces: List<Entry> = emptyList()
        var streetRows: List<Entry> = emptyList()
        var head = p.head
        var conn = p.conn
        if (conn == "near" || conn == "in") {
            val tail = Phrase(p.tail, partial, stop, norm)
            ctxPlaces = placeLookup(tail.key, user, allowPrefix = partial)
            if (ctxPlaces.isEmpty()) {
                val fixed = tail.exp.map { bestFix(it) }
                if (fixed != tail.exp) ctxPlaces = placeLookup(fixed.joinToString(" "), user)
            }
            if (ctxPlaces.isNotEmpty()) labels.add(ParseLabel(p.tail.joinToString(" "), "place", match = ctxPlaces[0].name))
        } else if (conn == "on") {
            val tail = Phrase(p.tail, partial, stop, norm)
            var rows = fts(ftsMatch(tail.exp.map { listOf(it) }, partial, tail.lastRaw), user, kinds = listOf(KIND_STREET), limit = 60)
            if (rows.isEmpty()) {
                val fixed = tail.exp.map { listOf(bestFix(it)) }
                rows = fts(ftsMatch(fixed, partial, null), user, kinds = listOf(KIND_STREET), limit = 60)
            }
            val good = setOf("exact", "prefix", "word_prefix")
            rows = rows.filter { nameTier(tail.key, it) in good || nameTier(tail.keyRaw, it) in good }
            if (rows.isNotEmpty()) {
                val first = rows[0].key
                streetRows = rows.filter { it.key == first }.take(8) + rows.filter { it.key != first }.take(2)
                labels.add(ParseLabel(p.tail.joinToString(" "), "street", match = first))
            }
        }
        if (conn != null && ctxPlaces.isEmpty() && streetRows.isEmpty()) {
            head = p.tokens
            conn = null
        }

        // ---------- 2. the head phrase, and a trailing place
        val whole = Phrase(head, partial, stop, norm)
        val wholeCats = categoryLookup(whole.key)
        val wholeBrands = brandLookup(whole.key)
        val wholeKnown = wholeCats.isNotEmpty() || wholeBrands.isNotEmpty() || nameExists(whole.key)
        val variants = ArrayList<Pair<Phrase, Boolean>>()
        if (conn != null) {
            variants.add(whole to true)
        } else {
            if (head.size >= 2 && !wholeKnown) {
                for (k in intArrayOf(3, 2, 1)) {
                    if (head.size - k < 1) continue
                    val tail = Phrase(head.takeLast(k), false, stop, norm)
                    val rest = Phrase(head.dropLast(k), false, stop, norm)
                    if (tail.key.length < 3) continue
                    var pl = placeLookup(tail.key, user, suffixes = false)
                    val restTyped = categoryLookup(rest.key).isNotEmpty() || brandLookup(rest.key).isNotEmpty()
                    pl = pl.filter { it.type in rk.contextPlaceTypes || restTyped }
                    if (pl.isNotEmpty()) {
                        ctxPlaces = pl
                        labels.add(ParseLabel(head.takeLast(k).joinToString(" "), "place", match = pl[0].name))
                        variants.add(Phrase(head.dropLast(k), false, stop, norm) to true)
                        break
                    }
                }
            }
            variants.add(whole to false)
        }
        val anchor: LatLon? = if (ctxPlaces.isNotEmpty()) LatLon(ctxPlaces[0].lat, ctxPlaces[0].lon) else user
        val ctxIds = ctxPlaces.take(3).map { it.id }.toSet()
        if (p.nearMe) labels.add(ParseLabel("near me", "near me"))
        if (p.house != null) labels.add(ParseLabel(p.house, "house number"))

        // ---------- 3. typo candidates
        class FuzzyRun(val opts: List<List<String>>, val bound: Boolean, val forced: Boolean)
        val fuzzyRuns = ArrayList<FuzzyRun>()
        for ((ph, bound) in variants) {
            if (!ph.truthy) continue
            val typedClass = categoryLookup(ph.key).isNotEmpty() || brandLookup(ph.key).isNotEmpty()
            val opts = ArrayList<List<String>>()
            var changed = false
            var forced = false
            for ((i, t) in ph.exp.withIndex()) {
                val last = i == ph.exp.size - 1
                if (t in noFix) {
                    opts.add(listOf(t)); continue
                }
                val f = vocabFreq(t)
                if (last && ph.partial && f == 0L && (vocabFreq(t, prefix = true) != 0L || vocabFreq(ph.lastRaw, prefix = true) != 0L)) {
                    opts.add(listOf(t)); continue
                }
                val corr = corrections(t)
                if (f == 0L && corr.isNotEmpty()) {
                    opts.add(corr.map { it.first })
                    changed = true
                } else if (f > 0 && corr.isNotEmpty() && !typedClass) {
                    val dom = corr.filter { it.second == 1 && it.third >= maxOf(rk.dominanceMinFreq, rk.dominanceRatio * f) }
                    if (dom.isNotEmpty()) {
                        opts.add(dom.map { it.first } + t)
                        changed = true; forced = true
                    } else {
                        opts.add(listOf(t))
                    }
                } else {
                    opts.add(listOf(t))
                }
            }
            if (changed) fuzzyRuns.add(FuzzyRun(opts, bound, forced))
        }

        // ---------- 4. candidates per interpretation
        fun ctxBonus(rows: List<Entry>): Map<Long, Double> {
            if (ctxIds.isEmpty()) return emptyMap()
            val cm = contextOf(rows.map { it.id })
            val out = HashMap<Long, Double>()
            for (r in rows) {
                if (r.muni in ctxIds || r.county in ctxIds || r.id in ctxIds) {
                    out[r.id] = 1.0
                } else {
                    var best = 0.0
                    var any = false
                    cm[r.id]?.forEach { (pid, w) -> if (pid in ctxIds) { best = if (!any) w else maxOf(best, w); any = true } }
                    out[r.id] = if (any) best else 0.0
                }
            }
            return out
        }

        fun streetBonus(rows: List<Entry>): Map<Long, Double> {
            if (streetRows.isEmpty()) return emptyMap()
            val skeys = streetRows.map { it.key }.toSet()
            val ids = rows.map { it.id }
            val out = HashMap<Long, Double>()
            var i = 0
            while (i < ids.size) {
                val chunk = ids.subList(i, minOf(i + 900, ids.size))
                for (r in db.query("SELECT entry_id, addr_street FROM poi WHERE entry_id IN (${chunk.joinToString(",") { "?" }})", chunk)) {
                    val ast = r[1] as String?
                    out[r[0] as Long] = if (!ast.isNullOrEmpty() && norm.streetKey(ast) in skeys) 1.0 else 0.0
                }
                i += 900
            }
            return out
        }

        fun alongStreet(catIds: Collection<Long>? = null, brandIds: Collection<Long>? = null): List<Entry> {
            val rows = ArrayList<Entry>()
            val m = rk.streetPoiMarginKm / 111.0
            for (s in streetRows) {
                val bb = bboxOf(s.id)
                rows += inBbox(doubleArrayOf(bb[0] - m, bb[1] + m, bb[2] - m * 1.3, bb[3] + m * 1.3), catIds, brandIds)
            }
            return rows
        }

        fun run(options: List<List<String>>, ph: Phrase, fuzzy: Boolean, bound: Boolean) {
            val best = options.map { it[0] }
            val key = best.joinToString(" ")
            val keyRaw = if (!fuzzy) ph.keyRaw else key
            val vAnchor = if (bound || ctxPlaces.isEmpty()) anchor else user
            val useCtx = bound && ctxIds.isNotEmpty()
            val prefixOk = ph.partial && !fuzzy
            val cats = categoryLookup(key, if (prefixOk) keyRaw else null)
            val brands = brandLookup(key, if (prefixOk) keyRaw else null)
            val fz = if (fuzzy) rk.fuzzyFactor else 1.0
            val browseScale = if (vAnchor == user) rk.browseScaleKm else rk.contextBrowseScaleKm
            var catRows = 0
            for ((kind, hit) in listOf("category" to cats, "brand" to brands)) {
                if (hit.isEmpty()) continue
                val rows: List<Entry>
                val lab: ParseLabel
                if (kind == "category") {
                    lab = ParseLabel(best.joinToString(" "), kind, matches = hit.keys.map { catCode.getValue(it) }, fuzzy = fuzzy)
                    rows = if (streetRows.isNotEmpty()) alongStreet(catIds = hit.keys) else browseCategory(hit.keys, vAnchor)
                    catRows += rows.size
                } else {
                    lab = ParseLabel(best.joinToString(" "), kind, matches = hit.keys.take(3).map { brandName(it) }, fuzzy = fuzzy)
                    rows = if (streetRows.isNotEmpty()) alongStreet(brandIds = hit.keys) else browseBrand(hit.keys, vAnchor)
                }
                labels.add(lab)
                val cb = if (useCtx) ctxBonus(rows) else emptyMap()
                val sb = streetBonus(rows)
                val maxHit = hit.values.max()
                for (r in rows) {
                    val base = if (kind == "category") {
                        val w = if (r.hasCat) (hit[r.catId] ?: maxHit) else maxHit
                        rk.wCategoryHit * w
                    } else {
                        rk.wBrandHit * (hit[r.brand] ?: maxHit)
                    }
                    val sc = base * fz + rk.wDistance * decay(r, vAnchor ?: user, browseScale) +
                        rk.wBrowseImportance * r.imp + rk.wContext * (cb[r.id] ?: 0.0) +
                        rk.wStreetAddress * (sb[r.id] ?: 0.0)
                    add(r, sc, kind, kind)
                }
            }
            if (streetRows.isNotEmpty()) return
            // plain name search; a phrase that is a category/brand word demotes name matches
            val exactClass = cats.values.any { it >= 1.0 } || brands.values.any { it >= 1.0 }
            if (outCov == true && catRows == 0 && cats.values.any { it >= 1.0 }) return
            val nameScale = if (exactClass) rk.wNameWhenCategoryOrBrand
            else if (cats.isNotEmpty() || brands.isNotEmpty()) rk.wNameWhenCategoryOrBrandPrefix else 1.0
            val rows = if (!fuzzy && ph.partial && best.size == 1 && keyRaw.length <= rk.shortPrefixChars) {
                shortPrefixCandidates(keyRaw, vAnchor)
            } else {
                fts(ftsMatch(options, ph.partial && !fuzzy, if (!fuzzy) ph.lastRaw else null), vAnchor)
            }
            val cb = if (useCtx) ctxBonus(rows) else emptyMap()
            val catCodes = cats.keys.map { catCode.getValue(it) }.toSet()
            val generic: Set<String> = if (cats.isNotEmpty()) db.query(
                "SELECT term FROM category_term WHERE cat_id IN (${cats.keys.joinToString(",")})").map { it[0] as String }.toSet()
            else emptySet()
            for (r in rows) {
                if (r.kind == KIND_POI && (r.type in catCodes || r.key in generic)) continue
                var tier = if (fuzzy) fuzzyTier(options, r) else nameTier(key, r)
                if (prefixOk && keyRaw != key) {
                    val t2 = nameTier(keyRaw, r)
                    if (TIER_ORDER.indexOf(t2) < TIER_ORDER.indexOf(tier)) tier = t2
                }
                if (tier == null) continue
                if (fuzzy) tier = when (tier) { "exact" -> "fuzzy"; "prefix" -> "fuzzy_prefix"; else -> "fuzzy_word" }
                val impW = rk.wImportance + (if (r.kind == KIND_PLACE) rk.wPlaceImportance else 0.0)
                val sc = rk.wName * rk.tiers.getValue(tier) + impW * r.imp +
                    rk.wDistance * decay(r, vAnchor, rk.scaleKm) +
                    rk.wContext * (cb[r.id] ?: 0.0) + rk.kindBonus.getValue(KIND_NAME.getValue(r.kind))
                add(r, sc * nameScale, "name", tier)
            }
        }

        for ((ph, bound) in variants) {
            if (ph.truthy) run(ph.exp.map { listOf(it) }, ph, false, bound)
        }
        if (fuzzyRuns.isNotEmpty()) {
            val strong = cands.values.count { it.tier in STRONG_TIERS }
            for (fr in fuzzyRuns) {
                if (fr.forced || strong < rk.fuzzyIfStrongBelow) {
                    val ph = Phrase(fr.opts.map { it[0] }, false, stop, norm)
                    run(fr.opts, ph, true, fr.bound)
                    labels.add(ParseLabel(fr.opts.joinToString(" ") { it[0] }, "corrected"))
                }
            }
        }
        if (head.isEmpty() && ctxPlaces.isNotEmpty()) {
            for (r in ctxPlaces.take(3)) add(r, 2.0, "place", "exact")
        }
        if (cands.isEmpty() && whole.exp.size >= 2 && !(outCov == true && wholeCats.isNotEmpty())) {
            // last resort: entries matching any one word ('cracker barell' -> 'Cracker Barrel')
            val words = whole.exp.filter { it.length >= 3 }
            if (words.isNotEmpty()) {
                val rows = fts(words.joinToString(" OR ") { ftsQuote(it) + "*" }, anchor)
                for (r in rows) {
                    val kt = Py.split(r.key)
                    val hits = words.count { t -> kt.any { it.startsWith(t) } }
                    if (hits > 0) {
                        val sc = rk.wName * rk.tiers.getValue("any_word") * hits / words.size + rk.wImportance * r.imp +
                            rk.wDistance * decay(r, anchor, rk.scaleKm)
                        add(r, sc, "name", "any_word")
                    }
                }
            }
        }

        // ---------- 5. results
        var ranked = cands.values.sortedByDescending { it.score }.take(lim * 4)
        val houses = if (p.house != null) houses(p.house, ranked) else emptyMap()
        if (houses.isNotEmpty()) {
            // an exact address (a located house number) goes first: house_located on top of its
            // score, times the distance decay of the house from the anchor (1 without one), and
            // only in the named place's municipality or county when the query names one; then the
            // order again (stable: equal scores keep their order), as query.py; scaled by the street
            // name's tier value, so a house on a street that only shares a word with the query does
            // not jump a street whose name was typed; none at all for an any_word street (the typed
            // street name is not in its name), also when the named town is not in this pack
            fun hbonus(c: Candidate): Double {
                if (c.tier == "any_word") return 0.0
                val row = c.row
                if (ctxIds.isNotEmpty() && row.muni !in ctxIds && row.county !in ctxIds) return 0.0
                val pt = houses.getValue(row.id)
                val d = if (anchor == null) 1.0 else 1.0 / (1.0 + Geo.haversineKm(anchor.lat, anchor.lon, pt.lat, pt.lon) / rk.scaleKm)
                return rk.wHouseLocated * (c.tier?.let { rk.tiers[it] } ?: 0.0) * d
            }
            ranked = ranked.map { c -> if (c.row.id in houses) Candidate(c.row, c.score + hbonus(c), c.how, c.tier) else c }
                .sortedByDescending { it.score }
        }
        val results = ArrayList<SearchResult>()
        val shown = ArrayList<Triple<String, Double, Double>>()
        if (p.house == null) {
            // two streets and the word between them: where they meet goes first (query.py step 7)
            results.addAll(crossings(text, user).take(lim))
            if (results.isNotEmpty()) labels.add(ParseLabel(text.trim(), "crossing", match = results[0].title))
        }
        for (c in ranked) {
            if (results.size >= lim) break
            val r = c.row
            var title = r.name
            var rlat = r.lat
            var rlon = r.lon
            val hit = houses[r.id]
            if (hit != null) {
                title = "${p.house} ${r.name}"
                rlat = hit.lat
                rlon = hit.lon
            } else if (r.kind == KIND_STREET && user != null && hasStreetLines) {
                // a street is where its line comes nearest to the user, not at its middle (query.py step 7)
                val nb = StreetLines.nearest(streetLines(r.id), user.lat, user.lon)
                if (nb != null) {
                    val nlat = Py.round(nb[1], 6)
                    val nlon = Py.round(nb[2], 6)
                    if (Geo.haversineKm(user.lat, user.lon, nlat, nlon) < Geo.haversineKm(user.lat, user.lon, rlat, rlon)) {
                        rlat = nlat
                        rlon = nlon
                    }
                }
            }
            if (shown.any { s -> s.first == title && Geo.haversineKm(s.second, s.third, rlat, rlon) < rk.dedupeKm }) continue
            shown.add(Triple(title, rlat, rlon))
            val d = if (user != null) Geo.haversineKm(user.lat, user.lon, rlat, rlon) else null
            val kind = KIND_NAME.getValue(r.kind)
            val cat: String? = when (kind) {
                "poi" -> poiLabel(r.type)
                "place" -> if (r.type == "zip") "ZIP code" else us(Py.capitalize(r.type?.ifEmpty { null } ?: "place"))
                else -> "Street"
            }
            var sub = r.sub ?: ""
            if (kind == "place" && !r.type.isNullOrEmpty()) {
                // a pack built before the US spellings says "Neighbourhood in Erie": its leading type
                // label only (names such as "Centre County" stay as they are), as query.py
                val raw = Py.capitalize(r.type)
                val rest = if (sub.startsWith(raw)) sub.substring(raw.length) else null
                if (raw != cat && rest != null && (rest.isEmpty() || rest[0] == ' ' || rest[0] == ',')) sub = cat + rest
            }
            val address = if (p.house != null && kind == "street") AddressInfo(
                houseNumber = p.house, streetName = r.name,
                accuracy = if (hit != null) AddressInfo.Accuracy.POINT else AddressInfo.Accuracy.STREET,
            ) else null
            results.add(SearchResult(
                id = r.id, kind = KINDS.getValue(r.kind), title = title, subtitle = sub,
                categoryId = r.type, categoryLabel = cat, lat = rlat, lon = rlon,
                distanceKm = d?.let { Py.round(it, 2) }, brandId = r.brand, address = address,
                score = Py.round(c.score, 4), how = c.how, tier = c.tier,
            ))
            if (results.size >= lim) break
        }
        val ms = Py.round((System.nanoTime() - t0) / 1e6, 2)
        // P6 (0.1.40): the place the words name is the anchor the results were ranked around
        val named = ctxPlaces.firstOrNull()?.let { NamedPlace(it.name, it.lat, it.lon) }
        return SearchResponse(text, labels, results, ms, outCov, positionIgnored, p.house, named)
    }

    // ------------------------------------------------------------------ helpers
    private fun bestFix(tok: String): String {
        if (vocabFreq(tok) != 0L) return tok
        val c = corrections(tok)
        return if (c.isNotEmpty()) c[0].first else tok
    }

    private fun brandName(bid: Long): String =
        db.first("SELECT name FROM brand WHERE id = ?", bid)?.get(0) as String? ?: bid.toString()

    private fun poiLabel(t: String?): String? {
        if (t.isNullOrEmpty()) return null
        if ('=' in t) {
            val k = t.substringBefore('=')
            val v = t.substringAfter('=')
            return us(Py.capitalize(v.replace("_", " "))) + (rk.rawTypeLabelSuffix[k] ?: "")
        }
        return catIdByCode[t]?.let { catLabel[it] }
    }

    /** A type label in US spelling, word by word through ranking.json us_spellings, the first letter's case kept (query.py _us). */
    private fun us(label: String): String = label.split(" ").joinToString(" ") { w ->
        val u = if (w.startsWith("_")) null else rk.usSpellings[w.lowercase()]
        when {
            u == null -> w
            w.firstOrNull()?.isUpperCase() == true -> u.replaceFirstChar { it.uppercaseChar() }
            else -> u
        }
    }

    /** True when the position lies more than coverage_margin_km outside the pack's box; null when unknown. */
    fun outOfCoverage(user: LatLon?): Boolean? {
        val b = bbox ?: return null
        if (user == null) return null
        val (minlat, maxlat, minlon, maxlon) = b.toList()
        val cl = LatLon(minOf(maxOf(user.lat, minlat), maxlat), minOf(maxOf(user.lon, minlon), maxlon))
        return Geo.haversineKm(user.lat, user.lon, cl.lat, cl.lon) > rk.coverageMarginKm
    }

    /**
     * House number -> point for the street candidates, through the addresses database's
     * address_rows view. One lookup per distinct street key; a point goes to the street whose
     * box (+ margin) holds it best. Returns {street entry id: point}.
     */
    private fun houses(number: String, ranked: List<Candidate>): Map<Long, LatLon> {
        if (!addrOk) return emptyMap()
        val m = rk.houseMarginDeg
        val cache = HashMap<Pair<String, String>, List<LatLon>>()
        val pick = LinkedHashMap<Long, LatLon>()
        val owner = HashMap<LatLon, Pair<Double, Long>>()
        for (c in ranked) {
            val r = c.row
            if (r.kind != KIND_STREET) continue
            val k = db.first("SELECT addr_key FROM street WHERE entry_id = ?", r.id)?.get(0) as String?
            if (k.isNullOrEmpty()) continue
            val ck = k to number
            val pts = cache.getOrPut(ck) {
                db.query("SELECT ${rk.addrLat}, ${rk.addrLon} FROM addr.${rk.addrTable} " +
                    "WHERE ${rk.addrStreetKey} = ? AND ${rk.addrHousenumber} = ?", k, number)
                    .map { LatLon(num(it[0]), num(it[1])) }
            }
            if (pts.isEmpty()) continue
            val bb = bboxOf(r.id)
            var best: DoubleArray? = null    // (box distance, km from the street point, lat, lon)
            for (pt in pts) {
                val la = pt.lat
                val lo = pt.lon
                val dlat = maxOf(bb[0] - la, 0.0, la - bb[1])
                val dlon = maxOf(bb[2] - lo, 0.0, lo - bb[3])
                if (dlat <= m && dlon <= m * 1.3) {
                    val cand = doubleArrayOf(dlat + dlon, Geo.haversineKm(r.lat, r.lon, la, lo), la, lo)
                    if (best == null || tupleLess(cand, best)) best = cand
                }
            }
            if (best != null) {
                val pt = LatLon(best[2], best[3])
                pick[r.id] = pt
                val o = owner[pt]
                if (o == null || best[0] < o.first) owner[pt] = best[0] to r.id
            }
        }
        return pick.filter { (eid, pt) -> owner.getValue(pt).second == eid }
    }

    private fun entries(sql: String, vararg args: Any?): List<Entry> = entries(sql, args.toList())

    private fun entries(sql: String, args: List<Any?>): List<Entry> = db.query(sql, args).map { r ->
        Entry(
            id = r[0] as Long, kind = (r[1] as Long).toInt(), name = r[2] as String, key = r[3] as String,
            alt = r[4] as String?, type = r[5] as String?, lat = num(r[6]), lon = num(r[7]), imp = num(r[8]),
            muni = r[9] as Long?, county = r[10] as Long?, brand = r[11] as Long?, sub = r[12] as String?,
            catId = if (r.size > 13) r[13] as Long? else null, hasCat = r.size > 13,
        )
    }

    companion object {
        /** The search database schema this port reads (packbuilder/search/build.py SCHEMA_VERSION). */
        const val SCHEMA_VERSION = "1"
        /** [signals] asks for this many cells in one query (SQLite's own limit of bound values is far higher). */
        const val SIGNAL_CELLS_PER_QUERY = 400
        const val KIND_PLACE = 1
        const val KIND_STREET = 2
        /** P6 (0.1.40): the place types a town is named from ("Near Corry"); hamlets are left out on purpose. */
        val AREA_PLACE_TYPES = listOf("city", "town", "borough", "village")
        /** "4662 West Ridge Road, Erie, PA 16506" from OSM's addr:* tags and the pack's [state]; null without a street. */
        internal fun postalAddress(number: String?, street: String?, city: String?, postcode: String?, state: String?): String? {
            if (street == null) return null
            val first = listOfNotNull(number, street).joinToString(" ")
            val last = listOfNotNull(state, postcode).joinToString(" ").ifEmpty { null }
            return listOfNotNull(first, city, last).joinToString(", ")
        }

        /** An entry type the categories do not name ("shop=hairdresser") in words ("Hairdresser"); null for "yes" and the like. */
        internal fun categoryWords(type: String?): String? {
            val v = type?.substringAfter('=')?.replace('_', ' ')?.trim()
            if (v.isNullOrEmpty() || v == "yes" || v == "no" || ';' in v || v.any { it.isDigit() }) return null
            return v.replaceFirstChar { it.uppercaseChar() }
        }

        const val KIND_POI = 3
        val KIND_NAME = mapOf(1 to "place", 2 to "street", 3 to "poi")
        private val KINDS = mapOf(1 to SearchResult.Kind.PLACE, 2 to SearchResult.Kind.STREET, 3 to SearchResult.Kind.POI)
        private const val ENTRY_COLS = "e.id, e.kind, e.name, e.name_key, e.alt_keys, e.type, e.lat, e.lon, e.importance, " +
            "e.muni_id, e.county_id, e.brand_id, e.subtitle"
        private val HOUSE_RE = Regex("[0-9]+[a-z]?")
        private val ZIP5_RE = Regex("[0-9]{5}")
        private val TIER_ORDER = listOf("exact", "exact_alt", "prefix", "word_prefix", "contains", null)
        private val STRONG_TIERS = setOf("exact", "prefix", "category", "brand")

        private fun num(v: Any?): Double = (v as Number).toDouble()

        /** [SearchResult.categoryId] of a result that is the place where two streets meet. */
        const val CROSSING_TYPE = "intersection"
        private const val M_PER_DEG_LAT = 111_195.0

        /**
         * The first [max] code points of [text], as Python's `text[:max]` (a surrogate pair is one
         * code point, an unpaired surrogate is one too), so both engines search the same text.
         */
        fun capCodePoints(text: String, max: Int): String {
            if (text.length <= max) return text
            if (text.codePointCount(0, text.length) <= max) return text
            return text.substring(0, text.offsetByCodePoints(0, max))
        }

        /** What [warmUp] reads (each query skipped quietly when a table is missing). */
        private val WARM_UP_SQL = listOf(
            "SELECT count(*), sum(length(code)), sum(length(label)) FROM category",
            "SELECT count(*), sum(length(term)) FROM category_term",
            "SELECT count(*), sum(length(name)) FROM brand",
            "SELECT count(*), sum(length(alias)) FROM brand_alias",
            "SELECT count(*) FROM vocab INDEXED BY vocab_word WHERE word >= ''",
            "SELECT count(*) FROM entry INDEXED BY entry_place_key WHERE kind = 1 AND name_key >= ''",
        )

        fun validPosition(lat: Double?, lon: Double?): LatLon? {
            if (lat == null || lon == null) return null
            if (!lat.isFinite() || !lon.isFinite() || abs(lat) > 90 || abs(lon) > 180) return null
            return LatLon(lat, lon)
        }

        /** Smallest string greater than every string that starts with p. */
        fun prefixUpper(p: String): String = if (p.isEmpty()) "￿" else p.dropLast(1) + (p.last() + 1)

        fun ftsQuote(tok: String): String = "\"" + tok.replace("\"", "\"\"") + "\""

        /** Optimal string alignment distance; 3 when the lengths differ by more than 2. */
        fun osaDistance(a: String, b: String): Int {
            val la = a.length
            val lb = b.length
            if (abs(la - lb) > 2) return 3
            var prev2: IntArray? = null
            var prev = IntArray(lb + 1) { it }
            for (i in 1..la) {
                val cur = IntArray(lb + 1)
                cur[0] = i
                for (j in 1..lb) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                    if (i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                        cur[j] = minOf(cur[j], prev2!![j - 2] + 1)
                    }
                }
                prev2 = prev
                prev = cur
            }
            return prev[lb]
        }

        fun nameTier(q: String, row: Entry): String? {
            if (q.isEmpty()) return null
            val keys = listOf(row.key) + (if (!row.alt.isNullOrEmpty()) row.alt.split("|") else emptyList())
            val qt = Py.split(q)
            val order = listOf("exact", "exact_alt", "prefix", "word_prefix", "contains")
            var best: String? = null
            for (k0 in keys) {
                if (k0.isEmpty()) continue
                val altDir = k0.startsWith("~")
                val key = if (altDir) k0.substring(1) else k0
                if (key == q) {
                    if (!altDir) return "exact"
                    best = "exact_alt"
                    continue
                }
                var t: String? = null
                if (key.startsWith(q)) {
                    t = "prefix"
                } else {
                    val kt = Py.split(key)
                    var j = 0
                    for (tok in kt) if (j < qt.size && tok.startsWith(qt[j])) j++
                    if (j == qt.size) t = "word_prefix"
                    else if (qt.all { x -> kt.any { it.startsWith(x) } }) t = "contains"
                }
                if (t != null && (best == null || order.indexOf(t) < order.indexOf(best))) best = t
            }
            return best
        }

        fun fuzzyTier(options: List<List<String>>, row: Entry): String? {
            val keys = listOf(row.key) + (if (!row.alt.isNullOrEmpty()) row.alt.split("|") else emptyList())
            var best: String? = null
            val order = listOf("exact", "prefix", "word_prefix")
            val n = options.size
            for (key in keys) {
                val kt = if (key.isNotEmpty()) Py.split(key.trimStart('~')) else emptyList()
                if (kt.isEmpty()) continue
                if (kt.size == n && kt.indices.all { kt[it] in options[it] }) return "exact"
                val t: String? = if (kt.size >= n && (0 until n - 1).all { kt[it] in options[it] } &&
                    options[n - 1].any { kt[n - 1].startsWith(it) }) {
                    "prefix"
                } else {
                    var j = 0
                    for (tok in kt) if (j < n && options[j].any { tok.startsWith(it) }) j++
                    if (j == n) "word_prefix" else null
                }
                if (t != null && (best == null || order.indexOf(t) < order.indexOf(best))) best = t
            }
            return best
        }

        /** Python tuple comparison a < b for equal-length float tuples. */
        private fun tupleLess(a: DoubleArray, b: DoubleArray): Boolean {
            for (i in a.indices) {
                if (a[i] < b[i]) return true
                if (a[i] > b[i]) return false
            }
            return false
        }
    }
}
