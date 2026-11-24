package io.github.spysyweeb.beamline.search

import io.github.spysyweeb.beamline.search.address.AddressHit
import io.github.spysyweeb.beamline.search.address.AddressIndex
import io.github.spysyweeb.beamline.search.address.AddressNormalizer
import io.github.spysyweeb.beamline.search.address.ParsedAddress
import java.io.File
import kotlin.math.abs
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
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Address parser, normaliser and lookup against addrlib (Python): the addresses track's
 * end-to-end variants (435 typed addresses) and extra strings, on the pack file and on the copy
 * with the targets removed; and the normaliser on every street, place and house-number spelling
 * of the pack. Findings go to data/search-conformance/kotlin/addresses-diff.json.
 */
class AddressConformanceTest {
    private val json = Json { prettyPrint = true }

    private fun py(e: JsonElement?): Any? = when {
        e == null || e is JsonNull -> null
        e is JsonPrimitive && e.isString -> e.content
        e is JsonPrimitive -> e.booleanOrNull ?: e.longOrNull ?: e.doubleOrNull
        else -> e.toString()
    }

    private fun parseMap(p: ParsedAddress): Map<String, Any?> = linkedMapOf(
        "house_number" to p.houseNumber, "street" to p.street, "street_key" to p.streetKey, "unit" to p.unit,
        "city" to p.city, "city_key" to p.cityKey, "state" to p.state, "zip" to p.zip, "zip4" to p.zip4,
        "zip_invalid" to p.zipInvalid,
    )

    private fun hitMap(x: AddressHit): Map<String, Any?> = linkedMapOf(
        "lat" to x.lat, "lon" to x.lon, "accuracy" to x.accuracy, "source" to x.source, "house_number" to x.houseNumber,
        "street" to x.street, "street_key" to x.streetKey, "zip" to x.zip, "city" to x.city, "municipality" to x.municipality,
        "county_fips" to x.countyFips, "match" to x.match, "score" to x.score, "locality_match" to x.localityMatch,
        "state_mismatch" to if (x.stateMismatch) true else null, "house_number_rejected" to x.houseNumberRejected,
        "unit" to x.unit, "address_id" to x.addressId, "base_number_of" to x.baseNumberOf, "edge_id" to x.edgeId,
        "side" to x.side, "hn_gap" to x.hnGap,
    )

    /** lat/lon may differ in the last bits (math.hypot and cos are not bit-identical across libraries). */
    private fun sameField(field: String, k: Any?, p: Any?): Boolean = when {
        k is Number && p is Number && (field == "lat" || field == "lon") -> abs(k.toDouble() - p.toDouble()) <= 1e-9
        k is Number && p is Number -> k.toDouble() == p.toDouble()
        else -> k == p
    }

    @Test
    fun sameAnswersAsAddrlib() {
        val refFile = File(TestEnv.conformanceDir, "reference-addresses.json")
        TestEnv.need(refFile.isFile, "no reference: run conformance/make_reference.py first")
        val packDb = TestEnv.requireAddressesDb()
        val ref = Json.parseToJsonElement(refFile.readText()).jsonObject
        assertEquals(emptyList<String>(), TestEnv.staleReference(ref), "the Python reference is out of date: run conformance/make_reference.py")
        val diffs = ArrayList<JsonObject>()
        val summary = LinkedHashMap<String, Any>()
        var maxCoordDiff = 0.0
        for ((run, path) in listOf("pack" to packDb, "holdout" to File(TestEnv.conformanceDir, "e2e-holdout.sqlite").path)) {
            val cases = ref["runs"]!!.jsonObject[run]!!.jsonObject["cases"]!!.jsonArray.map { it.jsonObject }
            val idx = AddressIndex(path)
            var parseSame = 0
            var resultsSame = 0
            val t0 = System.nanoTime()
            for (c in cases) {
                val text = c["text"]!!.jsonPrimitive.content
                val p = idx.parse(text)
                val hits = idx.resolve(p)
                val pp = c["parse"]!!.jsonObject
                val pDiff = parseMap(p).filter { (k, v) -> v != py(pp[k]) }
                if (pDiff.isEmpty()) parseSame++ else diffs.add(buildJsonObject {
                    put("run", run); put("text", text); put("what", "parse")
                    put("python", pDiff.keys.joinToString { "$it=${py(pp[it])}" }); put("kotlin", pDiff.entries.joinToString { "${it.key}=${it.value}" })
                })
                val pr = c["results"]!!.jsonArray.map { it.jsonObject }
                var ok = pr.size == hits.size
                val fieldDiffs = ArrayList<String>()
                if (ok) for ((i, h) in hits.withIndex()) {
                    for ((f, v) in hitMap(h)) {
                        val pv = py(pr[i][f])
                        if (f == "lat" || f == "lon") maxCoordDiff = maxOf(maxCoordDiff, abs((v as Double) - (pv as Number).toDouble()))
                        if (!sameField(f, v, pv)) {
                            ok = false; fieldDiffs.add("#${i + 1} $f: py=$pv kt=$v")
                        }
                    }
                }
                if (ok) resultsSame++ else diffs.add(buildJsonObject {
                    put("run", run); put("text", text); put("what", "results")
                    put("python", buildJsonArray { pr.forEach { add(JsonPrimitive("${py(it["street_key"])} ${py(it["accuracy"])} ${py(it["lat"])},${py(it["lon"])} ${py(it["score"])}")) } })
                    put("kotlin", buildJsonArray { hits.forEach { add(JsonPrimitive("${it.streetKey} ${it.accuracy} ${it.lat},${it.lon} ${it.score}")) } })
                    put("fields", fieldDiffs.joinToString("; "))
                })
            }
            summary["$run: strings"] = cases.size
            summary["$run: parse equal"] = parseSame
            summary["$run: results equal (top 5, every field)"] = resultsSame
            summary["$run: kotlin wall s"] = (System.nanoTime() - t0) / 1e9
            summary["$run: python wall s"] = ref["runs"]!!.jsonObject[run]!!.jsonObject["wall_s"]!!.jsonPrimitive.content
            idx.close()
        }
        summary["max |lat/lon difference| (deg)"] = maxCoordDiff
        val out = buildJsonObject {
            put("summary", buildJsonObject { summary.forEach { (k, v) -> put(k, v.toString()) } })
            put("differences", JsonArray(diffs))
        }
        File(TestEnv.outDir, "addresses-diff.json").writeText(json.encodeToString(JsonObject.serializer(), out))
        summary.forEach { (k, v) -> println("$k: $v") }
        diffs.take(30).forEach { println("DIFF $it") }
        assertEquals(0, diffs.size, "address answers that differ from addrlib (see addresses-diff.json)")
    }

    /** norm-fingerprint.json, written by make_reference.py with the normaliser dumps. */
    private fun normFingerprint(): JsonObject {
        val f = File(TestEnv.conformanceDir, "norm-fingerprint.json")
        return if (f.isFile) JsonObject(mapOf("fingerprint" to Json.parseToJsonElement(f.readText()))) else JsonObject(emptyMap())
    }

    @Test
    fun normaliserMatchesOnEveryPackSpelling() {
        val f = File(TestEnv.conformanceDir, "norm-addresses.jsonl")
        TestEnv.need(f.isFile, "no reference: run conformance/make_reference.py first")
        TestEnv.requireAddressesDb()
        assertEquals(emptyList<String>(), TestEnv.staleReference(normFingerprint()), "the normaliser dumps are out of date: run conformance/make_reference.py")
        val n = AddressNormalizer()
        val counts = LinkedHashMap<String, IntArray>()   // kind -> n, equal
        val diffs = ArrayList<String>()
        f.forEachLine { line ->
            val a = Json.parseToJsonElement(line).jsonArray
            val kind = a[0].jsonPrimitive.content
            val c = counts.getOrPut(kind) { IntArray(2) }
            c[0]++
            val ok = when (kind) {
                "street" -> {
                    val s = a[1].jsonPrimitive.content
                    val k = n.streetKey(s)
                    val variants = a[4].jsonArray.map { it.jsonPrimitive.content }
                    val same = k == a[2].jsonPrimitive.content && n.streetCore(k) == a[3].jsonPrimitive.content && n.keyVariants(k) == variants
                    if (!same) diffs.add("street '$s': py=${a[2]}|${a[3]}|$variants kt=$k|${n.streetCore(k)}|${n.keyVariants(k)}")
                    same
                }
                "place" -> {
                    val s = a[1].jsonPrimitive.content
                    val same = n.placeKey(s) == a[2].jsonPrimitive.content
                    if (!same) diffs.add("place '$s': py=${a[2]} kt=${n.placeKey(s)}")
                    same
                }
                else -> {
                    val s = a[1].jsonPrimitive.content
                    val h = n.houseNumber(s)
                    val same = h?.text == py(a[2]) && h?.int?.toString() == py(a[3])
                    if (!same) diffs.add("hn '$s': py=${a[2]},${a[3]} kt=${h?.text},${h?.int}")
                    same
                }
            }
            if (ok) c[1]++
        }
        counts.forEach { (k, v) -> println("address normaliser $k: ${v[1]}/${v[0]} equal") }
        diffs.take(30).forEach { println("NORM DIFF $it") }
        File(TestEnv.outDir, "norm-addresses-diff.txt").writeText(diffs.joinToString("\n"))
        assertEquals(0, diffs.size)
    }

    @Test
    fun searchKeyMatchesOnEveryEntryName() {
        val f = File(TestEnv.conformanceDir, "norm-search.jsonl")
        TestEnv.need(f.isFile, "no reference: run conformance/make_reference.py first")
        TestEnv.requireSearchDb()
        assertEquals(emptyList<String>(), TestEnv.staleReference(normFingerprint()), "the normaliser dumps are out of date: run conformance/make_reference.py")
        val norm = SearchNormalizer(SearchTables(RuleFiles.json(RuleFiles.ABBREVIATIONS)))
        var total = 0
        val diffs = ArrayList<String>()
        f.forEachLine { line ->
            val a = Json.parseToJsonElement(line).jsonArray
            total++
            val name = a[0].jsonPrimitive.content
            val k = norm.key(name)
            if (k != a[1].jsonPrimitive.content) diffs.add("'$name': py='${a[1].jsonPrimitive.content}' kt='$k'")
        }
        println("search key: ${total - diffs.size}/$total names equal")
        diffs.take(30).forEach { println("KEY DIFF $it") }
        File(TestEnv.outDir, "norm-search-diff.txt").writeText(diffs.joinToString("\n"))
        assertEquals(0, diffs.size)
    }
}
