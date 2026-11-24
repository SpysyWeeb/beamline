package io.github.spysyweeb.beamline.search

import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Kotlin engine against the Python reference (conformance/README.md): same result ids in the same
 * order for the top 10 of every query, and the same test-set pass rates. Writes the findings to
 * data/search-conformance/kotlin/search-diff.json.
 */
class SearchConformanceTest {
    private val json = Json { prettyPrint = true }

    private fun loadQueries(): List<JsonObject> {
        val f = File(TestEnv.repoRoot, "android/core/search/conformance/search_queries.json")
        return Json.parseToJsonElement(f.readText()).jsonObject["queries"]!!.jsonArray.map { it.jsonObject }
    }

    private fun pos(e: JsonElement?): Double? = when {
        e == null || e is JsonNull -> null
        e.jsonPrimitive.isString -> when (e.jsonPrimitive.content) {
            "nan" -> Double.NaN; "inf" -> Double.POSITIVE_INFINITY; "-inf" -> Double.NEGATIVE_INFINITY
            else -> error("bad position ${e.jsonPrimitive.content}")
        }
        else -> e.jsonPrimitive.doubleOrNull
    }

    fun position(q: JsonObject): Pair<Double?, Double?> = when {
        "at" in q -> TestEnv.points.getValue(q["at"]!!.jsonPrimitive.content).let { it.lat to it.lon }
        "near" in q -> q["near"]!!.jsonArray.let { pos(it[0]) to pos(it[1]) }
        else -> null to null
    }

    /** A Kotlin result in the reference's field names and value forms. */
    private fun asPy(r: SearchResult): Map<String, Any?> = linkedMapOf(
        "id" to r.id, "kind" to r.kind.code, "title" to r.title, "subtitle" to r.subtitle, "category" to r.categoryLabel,
        "type" to r.categoryId, "lat" to r.lat, "lon" to r.lon, "distance_km" to r.distanceKm, "brand_id" to r.brandId,
        "score" to r.score, "how" to r.how, "tier" to r.tier, "house_number" to r.address?.houseNumber,
        "street_name" to r.address?.streetName, "house_located" to r.address?.let { it.accuracy == AddressInfo.Accuracy.POINT },
    )

    private fun pyValue(e: JsonElement?): Any? = when {
        e == null || e is JsonNull -> null
        e is JsonPrimitive && e.isString -> e.content
        e is JsonPrimitive -> e.booleanOrNull ?: e.longOrNull ?: e.doubleOrNull
        else -> e.toString()
    }

    private fun same(a: Any?, b: Any?): Boolean = when {
        a is Number && b is Number -> a.toDouble() == b.toDouble()
        else -> a == b
    }

    /** Parse labels as comparable strings; brand name lists as sets (Python builds them from a set). */
    private fun labelKey(role: String, text: String, match: String?, matches: List<String>?, fuzzy: Boolean?): String {
        val m = matches?.let { if (role == "brand") it.sorted() else it }?.joinToString(",") ?: (match ?: "")
        return "$role|$text|$m|${fuzzy ?: ""}"
    }

    private fun pyLabels(arr: JsonArray): List<String> = arr.map { it.jsonObject }.map { o ->
        val m = o["match"]
        labelKey(o["as"]!!.jsonPrimitive.content, o["text"]!!.jsonPrimitive.content,
            if (m is JsonPrimitive) m.content else null, (m as? JsonArray)?.map { it.jsonPrimitive.content },
            o["fuzzy"]?.jsonPrimitive?.booleanOrNull)
    }

    /** Item 36: the pack's traffic lights, read by their 0.01 degree cells (packbuilder/search/signals.py). */
    @Test
    fun theTrafficLightsOfTheCellsAsked() {
        val searchDb = TestEnv.requireSearchDb()
        val addressesDb = TestEnv.requireAddressesDb()
        fun cell(lat: Double, lon: Double) = (Math.floor(lat * 100).toLong() + 9000) * 40000 + Math.floor(lon * 100).toLong() + 18000
        SearchEngine(searchDb, addressesDb).use { eng ->
            TestEnv.need(eng.hasSignals, "the search pack was built before the traffic lights")
            val erie = cell(42.1295, -80.0852)          // the middle of Erie
            val pittsburgh = cell(40.4406, -79.9959)    // the middle of Pittsburgh
            val a = eng.signals(listOf(erie))
            val b = eng.signals(listOf(pittsburgh))
            assertTrue(a.isNotEmpty() && b.isNotEmpty(), "both city centres have traffic lights")
            assertTrue(a.all { cell(it.first, it.second) == erie }, "only the lights of the cell asked")
            assertEquals((a + b).toSet(), eng.signals(listOf(pittsburgh, erie, erie)).toSet())
            assertEquals(a.size + b.size, eng.signals(listOf(pittsburgh, erie, erie)).size, "a cell asked twice is read once")
            assertEquals(emptyList<Pair<Double, Double>>(), eng.signals(emptyList()))
            assertEquals(emptyList<Pair<Double, Double>>(), eng.signals(listOf(cell(0.0, 0.0), -5L)))
            // more cells than one query takes: the row of cells east and west of it
            val row = (-600..600).map { erie + it }
            val got = eng.signals(row)
            assertTrue(got.containsAll(a) && got.size > a.size, "${got.size} lights in ${row.size} cells")
            assertEquals(got.size, got.toSet().size)
        }
    }

    /** The street at a point (a dropped pin): the same street, distance and point as query.py near_street. */
    @Test
    fun sameStreetAtAPointAsPython() {
        val refFile = File(TestEnv.conformanceDir, "reference-search.json")
        TestEnv.need(refFile.isFile, "no reference: run conformance/make_reference.py first")
        val searchDb = TestEnv.requireSearchDb()
        val addressesDb = TestEnv.requireAddressesDb()
        val ref = Json.parseToJsonElement(refFile.readText()).jsonObject
        assertEquals(emptyList<String>(), TestEnv.staleReference(ref), "the Python reference is out of date: run conformance/make_reference.py")
        val points = ref["near_street"]!!.jsonArray.map { it.jsonObject }
        var houses = 0
        SearchEngine(searchDb, addressesDb).use { eng ->
            assertEquals(ref["street_lines"]!!.jsonPrimitive.content.toBoolean(), eng.hasStreetLines)
            var named = 0
            val diffs = ArrayList<String>()
            for (pt in points) {
                val lat = pt["lat"]!!.jsonPrimitive.double
                val lon = pt["lon"]!!.jsonPrimitive.double
                val want = pt["street"]?.takeIf { it !is JsonNull }?.jsonObject
                val got = eng.nearStreet(lat, lon)
                val same = if (want == null) got == null else got != null &&
                    got.id == want["id"]!!.jsonPrimitive.long && got.name == want["name"]!!.jsonPrimitive.content &&
                    got.subtitle == want["subtitle"]!!.jsonPrimitive.content &&
                    got.distanceM == want["distance_m"]!!.jsonPrimitive.double &&
                    got.lat == want["lat"]!!.jsonPrimitive.double && got.lon == want["lon"]!!.jsonPrimitive.double &&
                    got.house == want["house"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
                if (want != null) named++
                if (got?.house != null) houses++
                if (!same) diffs.add("point ${points.indexOf(pt)}: Python ${want != null}, Kotlin ${got != null}")
            }
            assertEquals(emptyList<String>(), diffs, "the street at a point differs from Python")
            if (eng.hasStreetLines) assertTrue(named > points.size / 4, "the reference names a street at $named of ${points.size} points only")
            if (eng.hasStreetLines && eng.addrOk) assertTrue(houses > 0, "no point of the reference has a house number")
            assertEquals(null, eng.nearStreet(Double.NaN, -80.0))
            assertEquals(null, eng.nearStreet(95.0, -80.0))
        }
    }

    @Test
    fun sameAnswersAsPython() {
        val refFile = File(TestEnv.conformanceDir, "reference-search.json")
        TestEnv.need(refFile.isFile, "no reference: run conformance/make_reference.py first")
        val searchDb = TestEnv.requireSearchDb()
        val addressesDb = TestEnv.requireAddressesDb()
        val ref = Json.parseToJsonElement(refFile.readText()).jsonObject
        val stale = TestEnv.staleReference(ref)
        assertEquals(emptyList<String>(), stale, "the Python reference is out of date: run conformance/make_reference.py")
        val refById = ref["results"]!!.jsonArray.map { it.jsonObject }.associateBy { it["id"]!!.jsonPrimitive.content }
        val queries = loadQueries()
        val eng = SearchEngine(searchDb, addressesDb)
        val checker = Checker(searchDb, addressesDb, eng.norm)
        val idDiffs = ArrayList<JsonObject>()
        val fieldDiffs = ArrayList<JsonObject>()
        val labelDiffs = ArrayList<JsonObject>()
        val flagDiffs = ArrayList<JsonObject>()
        val verdictDiffs = ArrayList<JsonObject>()
        val bySet = LinkedHashMap<String, IntArray>()       // n, ids equal
        val passK = LinkedHashMap<String, IntArray>()       // n, top1, top5, gap
        val passP = LinkedHashMap<String, IntArray>()
        val dump = ArrayList<JsonObject>()   // the Kotlin answers in the reference's form (for compare.py)
        val t0 = System.nanoTime()
        for (q in queries) {
            val id = q["id"]!!.jsonPrimitive.content
            val pr = refById.getValue(id)
            val (lat, lon) = position(q)
            val r = eng.search(q["q"]!!.jsonPrimitive.content, lat, lon, 10)
            dump.add(buildJsonObject {
                put("id", id)
                put("results", buildJsonArray {
                    r.results.forEach { x ->
                        add(buildJsonObject {
                            for ((k, v) in asPy(x)) when (v) {
                                null -> put(k, JsonNull)
                                is Number -> put(k, v)
                                is Boolean -> put(k, v)
                                else -> put(k, v.toString())
                            }
                        })
                    }
                })
            })
            val kIds = r.results.map { it.id }
            val pRes = pr["results"]!!.jsonArray.map { it.jsonObject }
            val pIds = pRes.map { it["id"]!!.jsonPrimitive.content.toLong() }
            val setKey = q["set"]!!.jsonPrimitive.content.let { if (it == "extra") "extra:" + q["type"]!!.jsonPrimitive.content else it }
            val s = bySet.getOrPut(setKey) { IntArray(2) }
            s[0]++
            if (kIds == pIds) s[1]++ else idDiffs.add(buildJsonObject {
                put("id", id); put("q", q["q"]!!.jsonPrimitive.content); put("set", setKey)
                put("python", buildJsonArray { pRes.forEach { add(JsonPrimitive("${it["id"]!!.jsonPrimitive.content} ${it["title"]!!.jsonPrimitive.content} ${it["score"]}")) } })
                put("kotlin", buildJsonArray { r.results.forEach { add(JsonPrimitive("${it.id} ${it.title} ${it.score}")) } })
            })
            if (kIds == pIds) {
                for ((i, kr) in r.results.withIndex()) {
                    val k = asPy(kr)
                    for ((field, kv) in k) {
                        val pv = pyValue(pRes[i][field])
                        if (!same(kv, pv)) fieldDiffs.add(buildJsonObject {
                            put("id", id); put("q", q["q"]!!.jsonPrimitive.content); put("rank", i + 1); put("field", field)
                            put("python", pv.toString()); put("kotlin", kv.toString())
                        })
                    }
                }
            }
            val kl = r.parse.map { labelKey(it.role, it.text, it.match, it.matches, it.fuzzy) }
            val pl = pyLabels(pr["parse"]!!.jsonArray)
            if (kl != pl) labelDiffs.add(buildJsonObject {
                put("id", id); put("q", q["q"]!!.jsonPrimitive.content)
                put("python", pl.joinToString(" ; ")); put("kotlin", kl.joinToString(" ; "))
            })
            val pOut = pyValue(pr["out_of_coverage"]) as Boolean?
            val pIgn = pyValue(pr["position_ignored"]) as Boolean?
            if (pOut != r.outOfCoverage || pIgn != r.positionIgnored) flagDiffs.add(buildJsonObject {
                put("id", id); put("python", "$pOut/$pIgn"); put("kotlin", "${r.outOfCoverage}/${r.positionIgnored}")
            })
            // test-set verdicts (run_tests.py's Checker, ported)
            val exp = q["expect"]?.jsonObject
            if (exp != null) {
                var anchor: LatLon? = if (lat != null && lon != null) LatLon(lat, lon) else null
                exp["of"]?.let { anchor = TestEnv.points.getValue(it.jsonPrimitive.content) }
                exp["of_point"]?.let { a -> anchor = LatLon(a.jsonArray[0].jsonPrimitive.content.toDouble(), a.jsonArray[1].jsonPrimitive.content.toDouble()) }
                val hits = r.results.take(5).map { checker.match(it, exp, anchor) }
                val top1 = hits.firstOrNull() == true
                val top5 = hits.any { it }
                val gap = !top5 && !checker.exists(exp, anchor)
                val set = q["set"]!!.jsonPrimitive.content
                passK.getOrPut(set) { IntArray(4) }.let { it[0]++; if (top1) it[1]++; if (top5) it[2]++; if (gap) it[3]++ }
                val pv = pr["verdict"]!!.jsonObject
                val p1 = pv["top1"]!!.jsonPrimitive.content.toBoolean()
                val p5 = pv["top5"]!!.jsonPrimitive.content.toBoolean()
                val pg = pv["gap"]!!.jsonPrimitive.content.toBoolean()
                passP.getOrPut(set) { IntArray(4) }.let { it[0]++; if (p1) it[1]++; if (p5) it[2]++; if (pg) it[3]++ }
                if (top1 != p1 || top5 != p5 || gap != pg) verdictDiffs.add(buildJsonObject {
                    put("id", id); put("q", q["q"]!!.jsonPrimitive.content)
                    put("python", "top1=$p1 top5=$p5 gap=$pg"); put("kotlin", "top1=$top1 top5=$top5 gap=$gap")
                })
            }
        }
        val wall = (System.nanoTime() - t0) / 1e9
        checker.close()
        eng.close()
        val nSame = bySet.values.sumOf { it[1] }
        val summary = buildJsonObject {
            put("queries", queries.size)
            put("ids_equal_top10", nSame)
            put("match_rate", nSame.toDouble() / queries.size)
            put("by_set", buildJsonObject { bySet.forEach { (k, v) -> put(k, "${v[1]}/${v[0]}") } })
            put("field_differences", fieldDiffs.size)
            put("label_differences", labelDiffs.size)
            put("flag_differences", flagDiffs.size)
            put("verdict_differences", verdictDiffs.size)
            put("pass_rates_kotlin", buildJsonObject { passK.forEach { (k, v) -> put(k, "n=${v[0]} top1=${v[1]} top5=${v[2]} gaps=${v[3]}") } })
            put("pass_rates_python", buildJsonObject { passP.forEach { (k, v) -> put(k, "n=${v[0]} top1=${v[1]} top5=${v[2]} gaps=${v[3]}") } })
            put("kotlin_wall_s", wall)
            put("python_wall_s", ref["wall_s"]!!.jsonPrimitive.content.toDouble())
        }
        val out = buildJsonObject {
            put("summary", summary)
            put("id_differences", JsonArray(idDiffs)); put("field_differences", JsonArray(fieldDiffs.take(500)))
            put("label_differences", JsonArray(labelDiffs)); put("flag_differences", JsonArray(flagDiffs))
            put("verdict_differences", JsonArray(verdictDiffs))
        }
        File(TestEnv.outDir, "search-diff.json").writeText(json.encodeToString(JsonObject.serializer(), out))
        File(TestEnv.outDir, "kotlin-search.json").writeText(Json.encodeToString(JsonArray.serializer(), JsonArray(dump)))
        println(json.encodeToString(JsonObject.serializer(), summary))
        idDiffs.take(30).forEach { println("ID DIFF ${it["id"]} ${it["q"]}\n   py: ${it["python"]}\n   kt: ${it["kotlin"]}") }
        fieldDiffs.take(30).forEach { println("FIELD DIFF $it") }
        labelDiffs.take(15).forEach { println("LABEL DIFF $it") }
        flagDiffs.take(10).forEach { println("FLAG DIFF $it") }
        verdictDiffs.take(10).forEach { println("VERDICT DIFF $it") }
        // every kind of difference fails the test (not only ids and pass rates; review of 2026-11-15)
        for (set in passP.keys) assertEquals(passP.getValue(set).toList(), passK.getValue(set).toList(), "pass rates of $set")
        assertEquals(queries.size, nSame, "queries whose top-10 ids differ from Python (see search-diff.json)")
        assertEquals(0, fieldDiffs.size, "result fields that differ from Python (see search-diff.json)")
        assertEquals(0, labelDiffs.size, "parse labels that differ from Python (see search-diff.json)")
        assertEquals(0, flagDiffs.size, "out-of-coverage / position flags that differ from Python (see search-diff.json)")
        assertEquals(0, verdictDiffs.size, "test-set verdicts that differ from Python (see search-diff.json)")
    }
}
