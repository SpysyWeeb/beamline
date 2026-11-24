package io.github.spysyweeb.beamline.search.bench

import io.github.spysyweeb.beamline.search.LatLon
import io.github.spysyweeb.beamline.search.SearchEngine
import io.github.spysyweeb.beamline.search.address.AddressIndex
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * Latency benchmark of the search engine and the address lookup, the same code on the JVM
 * (BenchMain) and on a device (the demo app's bench mode). The protocol follows
 * packbuilder/search/measure.py, so the numbers compare with report 03's Python figures:
 *  - test-set queries (tuned + held-out, by type): "cold" = a new engine (new connection, empty
 *    SQLite page cache; the OS file cache is NOT flushed) then one run; "warm" = 5 more runs;
 *    no addresses database attached (as measure.py --only latency);
 *  - typing: every 1-letter (26) and 2-letter (676) prefix and the 100 most common 3-letter word
 *    starts of the vocabulary, plus 8 common whole words, at Erie, Philadelphia and Altoona; each
 *    query run twice on one engine, the second run counted;
 *  - house numbers: 8 queries with the addresses database attached, median of 5 warm runs;
 *  - address lookup (AddressIndex): typed addresses, parse and lookup timed separately, 5 runs;
 *  - open times: engine without / with the addresses database, address index.
 */
class SearchBench(
    private val searchDb: String,
    private val addressesDb: String?,
    queriesJson: String,
    private val log: (String) -> Unit = {},
) {
    private val queries = Json.parseToJsonElement(queriesJson).jsonObject["queries"]!!.jsonArray.map { it.jsonObject }

    fun run(): JsonObject = buildJsonObject {
        val sqliteInfo = SearchEngine(searchDb).use { it.sqliteInfo() }
        put("sqlite_version", sqliteInfo.first)
        put("sqlite_compile_options", buildJsonArray { sqliteInfo.second.forEach { add(kotlinx.serialization.json.JsonPrimitive(it)) } })
        put("math_checksum", MathParity.checksum())
        put("open", openTimes())
        put("query_types", queryTypes())
        put("typing", typing())
        if (addressesDb != null) {
            put("house_numbers", houses())
            put("address_lookup", addressLookup())
        }
    }

    private fun ms(t0: Long) = (System.nanoTime() - t0) / 1e6

    private fun JsonObjectBuilder.stats(xs: List<Double>) {
        val s = xs.sorted()
        put("n", s.size)
        put("median_ms", r2(median(s)))
        put("p95_ms", r2(pct(s, 0.95)))
        put("max_ms", r2(s.lastOrNull() ?: 0.0))
    }

    private fun openTimes(): JsonObject = buildJsonObject {
        val plain = ArrayList<Double>()
        val withAddr = ArrayList<Double>()
        val addrIdx = ArrayList<Double>()
        repeat(5) {
            var t0 = System.nanoTime()
            SearchEngine(searchDb).close()
            plain.add(ms(t0))
            if (addressesDb != null) {
                t0 = System.nanoTime()
                SearchEngine(searchDb, addressesDb).close()
                withAddr.add(ms(t0))
                t0 = System.nanoTime()
                AddressIndex(addressesDb).close()
                addrIdx.add(ms(t0))
            }
        }
        put("search_engine_first_ms", r2(plain[0]))
        put("search_engine_next_median_ms", r2(median(plain.drop(1).sorted())))
        if (addressesDb != null) {
            put("search_engine_with_addresses_first_ms", r2(withAddr[0]))
            put("search_engine_with_addresses_next_median_ms", r2(median(withAddr.drop(1).sorted())))
            put("address_index_first_ms", r2(addrIdx[0]))
            put("address_index_next_median_ms", r2(median(addrIdx.drop(1).sorted())))
        }
        log("open: $plain / $withAddr / $addrIdx")
    }

    private fun position(q: JsonObject): LatLon? {
        q["at"]?.let { return POINTS.getValue(it.jsonPrimitive.content) }
        q["near"]?.let { a ->
            val arr = a.jsonArray
            val la = arr[0].jsonPrimitive.content.toDoubleOrNull()
            val lo = arr[1].jsonPrimitive.content.toDoubleOrNull()
            return if (la != null && lo != null) LatLon(la, lo) else null
        }
        return null
    }

    private fun queryTypes(): JsonObject {
        val cases = queries.filter { it["set"]!!.jsonPrimitive.content in setOf("tuned", "holdout") }
        val cold = LinkedHashMap<String, MutableList<Double>>()
        val warm = LinkedHashMap<String, MutableList<Double>>()
        val review = ArrayList<Double>()
        for (c in cases + queries.filter { it["set"]!!.jsonPrimitive.content == "review" && it["type"]!!.jsonPrimitive.content != "house" }) {
            val p = position(c)
            val text = c["q"]!!.jsonPrimitive.content
            val type = if (c["set"]!!.jsonPrimitive.content == "review") "review set" else c["type"]!!.jsonPrimitive.content
            SearchEngine(searchDb).use { eng ->
                var t0 = System.nanoTime()
                eng.search(text, p?.lat, p?.lon)
                cold.getOrPut(type) { ArrayList() }.add(ms(t0))
                repeat(5) {
                    t0 = System.nanoTime()
                    eng.search(text, p?.lat, p?.lon)
                    val x = ms(t0)
                    if (type == "review set") review.add(x) else warm.getOrPut(type) { ArrayList() }.add(x)
                }
            }
        }
        log("query types done")
        return buildJsonObject {
            for (t in cold.keys.filter { it != "review set" }.sorted()) put(t, buildJsonObject {
                put("queries", cold.getValue(t).size)
                put("cold", buildJsonObject { stats(cold.getValue(t)) })
                put("warm", buildJsonObject { stats(warm.getValue(t)) })
            })
            val allCold = cold.filterKeys { it != "review set" }.values.flatten()
            val allWarm = warm.values.flatten()
            put("ALL", buildJsonObject {
                put("queries", allCold.size)
                put("cold", buildJsonObject { stats(allCold) })
                put("warm", buildJsonObject { stats(allWarm) })
            })
            put("review set", buildJsonObject {
                put("queries", cold["review set"]?.size ?: 0)
                put("cold", buildJsonObject { stats(cold["review set"] ?: emptyList()) })
                put("warm", buildJsonObject { stats(review) })
            })
        }
    }

    private fun typing(): JsonObject {
        val letters = "abcdefghijklmnopqrstuvwxyz"
        val one = letters.map { it.toString() }
        val two = letters.flatMap { a -> letters.map { b -> "$a$b" } }
        val three: List<String> = SearchEngine(searchDb).use { it.commonStarts(100) }
        val words = listOf("street", "road", "lane", "church", "avenue", "main", "park", "school")
        val groups = linkedMapOf("1 letter" to one, "2 letters" to two, "3 letters (100 most common)" to three, "common words" to words)
        val combined = LinkedHashMap<String, MutableList<Double>>()
        val slowest = LinkedHashMap<String, MutableList<Pair<Double, String>>>()
        for (pt in listOf("erie", "philadelphia", "altoona")) {
            val p = POINTS.getValue(pt)
            SearchEngine(searchDb).use { eng ->
                for ((g, qs) in groups) {
                    for (q in qs) {
                        eng.search(q, p.lat, p.lon)
                        val t0 = System.nanoTime()
                        eng.search(q, p.lat, p.lon)
                        val x = ms(t0)
                        combined.getOrPut(g) { ArrayList() }.add(x)
                        slowest.getOrPut(g) { ArrayList() }.add(x to "$q@$pt")
                    }
                }
            }
            log("typing at $pt done")
        }
        return buildJsonObject {
            for ((g, xs) in combined) put(g, buildJsonObject {
                stats(xs)
                put("slowest", buildJsonArray {
                    slowest.getValue(g).sortedByDescending { it.first }.take(5).forEach { add(kotlinx.serialization.json.JsonPrimitive("${r2(it.first)} ms ${it.second}")) }
                })
            })
        }
    }

    private fun houses(): JsonObject = buildJsonObject {
        for ((label, addr) in listOf("without addresses" to null, "with addresses" to addressesDb)) {
            put(label, buildJsonObject {
                SearchEngine(searchDb, addr).use { eng ->
                    for ((q, pt) in HOUSE_QUERIES) {
                        val p = POINTS.getValue(pt)
                        eng.search(q, p.lat, p.lon)
                        val xs = ArrayList<Double>()
                        var top = ""
                        repeat(5) {
                            val t0 = System.nanoTime()
                            val r = eng.search(q, p.lat, p.lon)
                            xs.add(ms(t0))
                            top = r.results.firstOrNull()?.let { "${it.title} (${it.address?.accuracy})" } ?: ""
                        }
                        put(q, buildJsonObject { put("median_ms", r2(median(xs.sorted()))); put("max_ms", r2(xs.max())); put("top", top) })
                    }
                }
            })
        }
        log("houses done")
    }

    private fun addressLookup(): JsonObject {
        val parse = ArrayList<Double>()
        val lookup = ArrayList<Double>()
        val per = LinkedHashMap<String, String>()
        AddressIndex(addressesDb!!).use { idx ->
            for (text in ADDRESS_QUERIES) {
                idx.geocode(text)
                val mine = ArrayList<Double>()
                var answer = "none"
                repeat(5) {
                    var t0 = System.nanoTime()
                    val p = idx.parse(text)
                    parse.add(ms(t0))
                    t0 = System.nanoTime()
                    val r = idx.resolve(p)
                    mine.add(ms(t0))
                    answer = r.firstOrNull()?.let { "${it.accuracy} ${it.match} ${it.city} locality_match=${it.localityMatch}" } ?: "none"
                }
                lookup.addAll(mine)
                per[text] = "${r2(median(mine.sorted()))} ms: $answer"
            }
        }
        log("address lookup done")
        return buildJsonObject {
            put("queries", ADDRESS_QUERIES.size)
            put("parse", buildJsonObject { stats(parse) })
            put("lookup", buildJsonObject { stats(lookup) })
            put("answers", buildJsonObject { per.forEach { (k, v) -> put(k, v) } })
        }
    }

    companion object {
        /** packbuilder/search/tests/points.json (approximate downtown points). */
        val POINTS = mapOf(
            "erie" to LatLon(42.1292, -80.0851), "erie_peach_upper" to LatLon(42.0632, -80.0990),
            "pittsburgh" to LatLon(40.4406, -79.9959), "harrisburg" to LatLon(40.2637, -76.8823),
            "philadelphia" to LatLon(39.9526, -75.1652), "state_college" to LatLon(40.7934, -77.8600),
            "scranton" to LatLon(41.4090, -75.6624), "allentown" to LatLon(40.6023, -75.4714),
            "lancaster" to LatLon(40.0379, -76.3055), "altoona" to LatLon(40.5187, -78.3947),
            "williamsport" to LatLon(41.2412, -77.0011), "wilkes_barre" to LatLon(41.2459, -75.8813),
            "breezewood" to LatLon(39.9987, -78.2420), "i80_clarion" to LatLon(41.1960, -79.3950),
            "cleveland" to LatLon(41.4993, -81.6944), "youngstown" to LatLon(41.0998, -80.6495),
            "columbus" to LatLon(39.9612, -82.9988),
        )
        val HOUSE_QUERIES = listOf("1 main st" to "erie", "100 main st" to "erie", "500 church st" to "erie",
            "123 n main st" to "erie", "4319 peach st" to "erie", "1600 market st philadelphia" to "pittsburgh",
            "600 grant street pittsburgh" to "erie", "72 s main st wilkes barre" to "wilkes_barre")
        /** The addresses track's spot checks (step_variants.py) and contract examples. */
        val ADDRESS_QUERIES = listOf("626 State St Erie PA", "414 Grant St Pittsburgh PA", "501 N 3rd St Harrisburg PA",
            "1400 JFK Blvd Philadelphia PA", "243 S Allen St State College PA", "340 N Washington Ave Scranton PA",
            "1200 E Pittsburgh St Greensburg PA", "150 Main St Brookville PA", "1 Saint Marys St Erie",
            "626 State St Erie OH", "123 n mian st, erie pa 16501", "4319 Peach St", "100 Hill Side Rd",
            "500 Walnut St Apt #4B Erie PA", "Route 8 4500 Gibsonia", "10450 US-19 Wexford, PA")

        fun median(s: List<Double>): Double = when {
            s.isEmpty() -> 0.0
            s.size % 2 == 1 -> s[s.size / 2]
            else -> (s[s.size / 2 - 1] + s[s.size / 2]) / 2
        }

        /** measure.py pct(): the value at index min(n - 1, round(q * (n - 1))) of the sorted list. */
        fun pct(s: List<Double>, q: Double): Double =
            if (s.isEmpty()) 0.0 else s[minOf(s.size - 1, Math.rint(q * (s.size - 1)).toInt())]

        fun r2(x: Double) = Math.round(x * 100) / 100.0
    }
}
