package io.github.spysyweeb.beamline.search.address

import io.github.spysyweeb.beamline.search.Py
import io.github.spysyweeb.beamline.search.RuleFiles
import java.math.BigInteger
import java.text.Normalizer
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * us_street_norm.json (copied from packbuilder/addresses/addrlib), as addrlib/normalize.py's
 * Tables class reads it. [version] is the normaliser table version this port implements; a
 * database keyed with another version is refused (see [AddressIndex]).
 */
class StreetTables(val raw: JsonObject) {
    val version: String = raw["version"]!!.jsonPrimitive.content
    val dir: Map<String, String> = invert(raw["directionals"]!!.jsonObject)
    val weakDirs: Set<String> = strings(raw["weak_directionals"]).toSet()
    val saintPrefixes: Set<String> = strings(raw["saint_prefix_words"]).toSet()
    val types: Map<String, String> = invert(raw["street_types"]!!.jsonObject)
    val typeCanon: Set<String> = raw["street_types"]!!.jsonObject.keys.toSet()
    /** canonical type -> its first spelling (the full word: "street", "avenue"), in file order */
    val typeFirstSpelling: List<Pair<String, String>> = raw["street_types"]!!.jsonObject.entries
        .filter { !it.key.startsWith("_") }.map { it.key to it.value.jsonArray[0].jsonPrimitive.content }
    val strongTypes: Set<String> = strings(raw["strong_street_types"]).toSet()
    val nameWords: Map<String, String> = invert(JsonObject(raw["name_words"]!!.jsonObject.filterKeys { !it.startsWith("_") }))
    val ordUnits: Map<String, Int>
    val ordTeens: Map<String, Int>
    val ordTens: Map<String, Int>
    val cardTens: Map<String, Int>
    /** (prefix tokens, system) in file order */
    val hwyPrefixes: List<Pair<List<String>, String>>
    val statePrefixes: List<List<String>>
    val stateFollowers: List<List<String>>
    val hwyMods: Map<String, String>
    val hwyFallbacks: Map<String, List<String>>
    val gluePrefixes: Set<String>
    val units = LinkedHashMap<String, String>()
    val unitNeedsId = HashMap<String, Boolean>()
    val fractions: List<String> = strings(raw["house_number_fractions"])
    val hnPlaceholderSuffixes: List<String> = strings(raw["house_number_placeholder_suffixes"])
    val cityPlaceholders: Set<String> = strings(raw["city_placeholders"]).toSet()
    val countryMulti: List<List<String>>
    val states: Map<String, String> = invert(raw["states"]!!.jsonObject)
    val stateMulti: List<List<String>>
    val ambiguousStates: Set<String> = strings(raw["ambiguous_state_tokens"]).toSet()
    val placeWords: Map<String, String> = invert(JsonObject(raw["place_words"]!!.jsonObject.filterKeys { !it.startsWith("_") }))
    val placeDropPrefixes: List<List<String>> = strings(raw["place_drop_prefixes"]).map { Py.split(it) }
    val placeDropSuffixes: Set<String> = strings(raw["place_drop_suffixes"]).toSet()

    init {
        val o = raw["ordinals"]!!.jsonObject
        fun ints(k: String) = o[k]!!.jsonObject.mapValues { it.value.jsonPrimitive.int }
        ordUnits = ints("units"); ordTeens = ints("teens"); ordTens = ints("tens_ordinal"); cardTens = ints("tens_cardinal")
        val h = raw["highways"]!!.jsonObject
        val hp = ArrayList<Pair<List<String>, String>>()
        for ((system, variants) in h["systems"]!!.jsonObject) for (v in variants.jsonArray) hp.add(Py.split(v.jsonPrimitive.content) to system)
        hwyPrefixes = hp
        statePrefixes = strings(h["state_prefixes"]).map { Py.split(it) }
        stateFollowers = strings(h["state_prefix_followers"]).map { Py.split(it) }
        hwyMods = invert(h["modifiers"]!!.jsonObject)
        hwyFallbacks = h["lookup_fallbacks"]!!.jsonObject.mapValues { strings(it.value) }
        gluePrefixes = hwyPrefixes.filter { it.first.size == 1 }.map { it.first[0] }.toSet() +
            statePrefixes.filter { it.size == 1 }.map { it[0] }.toSet()
        for ((canon, spec) in raw["unit_designators"]!!.jsonObject) {
            for (w in spec.jsonObject["words"]!!.jsonArray) units[w.jsonPrimitive.content] = canon
            unitNeedsId[canon] = spec.jsonObject["needs_id"]!!.jsonPrimitive.content == "true"
        }
        // sorted(set(...), key=len, reverse=True): only the length order matters (see the parser)
        countryMulti = strings(raw["country_words"]).map { Py.split(it) }.distinct().sortedByDescending { it.size }
        stateMulti = raw["states"]!!.jsonObject.values.flatMap { strings(it) }.map { Py.split(it) }.distinct()
            .sortedByDescending { it.size }
    }

    companion object {
        fun load(): StreetTables = StreetTables(RuleFiles.json(RuleFiles.STREET_NORM))

        private fun strings(e: kotlinx.serialization.json.JsonElement?): List<String> =
            (e as JsonArray).map { it.jsonPrimitive.content }

        /** addrlib normalize._invert: every spelling -> canonical (first mapping wins), canon -> canon. */
        private fun invert(m: JsonObject): Map<String, String> {
            val out = LinkedHashMap<String, String>()
            for ((canon, variants) in m) {
                if (canon.startsWith("_")) continue
                for (v in variants.jsonArray) out.putIfAbsent(v.jsonPrimitive.content, canon)
                out.putIfAbsent(canon, canon)
            }
            return out
        }
    }
}

/** A highway form found in a token list: tokens [start, end) became [canon]. */
data class Highway(val start: Int, val end: Int, val canon: List<String>)

/** House number in canonical text and its leading integer. */
data class HouseNumber(val text: String, val int: BigInteger)

/** Port of packbuilder/addresses/addrlib/normalize.py (the parts the parser and lookup use). */
class AddressNormalizer(val t: StreetTables = StreetTables.load()) {

    fun asciiFold(s: String): String {
        val r = s.replace("½", " 1/2").replace("¼", " 1/4").replace("¾", " 3/4")
        val n = Normalizer.normalize(r, Normalizer.Form.NFKD)
        val sb = StringBuilder(n.length)
        for (c in n) if (c.code < 128) sb.append(c)
        return sb.toString()
    }

    /** Lower-case, fold accents, drop apostrophes/periods, other punctuation -> space. */
    fun clean(s: String?, keep: String = ""): String {
        var x = asciiFold(s ?: "").lowercase().replace("&", " and ")
        x = APOS_DOT.replace(x, "")
        val sb = StringBuilder(x.length)
        var inRun = false
        for (c in x) {
            if (c in 'a'..'z' || c in '0'..'9' || c in keep) {
                sb.append(c); inRun = false
            } else if (!inRun) {
                sb.append(' '); inRun = true
            }
        }
        return Py.split(sb.toString()).joinToString(" ")
    }

    fun tokens(s: String?): List<String> {
        val out = ArrayList<String>()
        for (tok in Py.split(clean(s))) {
            val m = GLUED_RE.matchEntire(tok)
            if (m != null && m.groupValues[1] in t.gluePrefixes) {
                out.add(m.groupValues[1]); out.add(m.groupValues[2])
            } else {
                out.add(tok)
            }
        }
        return out
    }

    private fun routeNumber(tok: String): String? {
        val m = NUM_RE.matchEntire(tok) ?: return null
        return m.groupValues[1] + m.groupValues[2]
    }

    private fun matchSeq(toks: List<String>, i: Int, seq: List<String>): Boolean =
        i + seq.size <= toks.size && toks.subList(i, i + seq.size) == seq

    /** The first highway form, else null. */
    fun findHighway(toks: List<String>): Highway? {
        for (i in toks.indices) {
            var best: Triple<Int, String, Int>? = null
            for (sp in t.statePrefixes) {
                if (matchSeq(toks, i, sp)) {
                    var j = i + sp.size
                    var flBest = 0
                    for (fl in t.stateFollowers) if (matchSeq(toks, j, fl) && fl.size > flBest) flBest = fl.size
                    j += flBest
                    if (j < toks.size && routeNumber(toks[j]) != null) {
                        if (best == null || j - i > best.first) best = Triple(j - i, "sr", j)
                    }
                }
            }
            for ((seq, system) in t.hwyPrefixes) {
                if (matchSeq(toks, i, seq)) {
                    val j = i + seq.size
                    if (j < toks.size && routeNumber(toks[j]) != null) {
                        if (best == null || seq.size > best.first) best = Triple(seq.size, system, j)
                    }
                }
            }
            if (best != null) {
                val (_, system, j) = best
                val out = arrayListOf(system, routeNumber(toks[j])!!)
                var k = j + 1
                while (k < toks.size && toks[k] in t.hwyMods) {
                    out.add(t.hwyMods.getValue(toks[k])); k++
                }
                return Highway(i, k, out)
            }
        }
        return null
    }

    private fun ordinals(toks: List<String>): List<String> {
        val out = ArrayList<String>()
        var i = 0
        while (i < toks.size) {
            val w = toks[i]
            val nxt = if (i + 1 < toks.size) toks[i + 1] else null
            if (Py.isDigits(w) && nxt == ordinalSuffix(w) && i + 2 < toks.size) {
                out.add(Py.intStr(w) + nxt)    // '21 st street' -> '21st street'
                i += 2
                continue
            }
            if (w in t.cardTens && nxt != null && nxt in t.ordUnits) {
                val n = t.cardTens.getValue(w) + t.ordUnits.getValue(nxt)
                out.add("$n${ordinalSuffix(n.toString())}")
                i += 2
                continue
            }
            val n = t.ordUnits[w]?.takeIf { it != 0 } ?: t.ordTeens[w]?.takeIf { it != 0 } ?: t.ordTens[w]?.takeIf { it != 0 }
            if (n != null) {
                out.add("$n${ordinalSuffix(n.toString())}")
            } else {
                val m = ORD_NUM_RE.matchEntire(w)
                if (m != null) {
                    val digits = Py.intStr(m.groupValues[1])
                    out.add(digits + ordinalSuffix(digits))
                } else {
                    out.add(w)
                }
            }
            i++
        }
        return out
    }

    private fun saintPosition(toks: List<String>, i: Int): Boolean =
        i == 0 || (i == 1 && toks[0] in t.dir) || toks[i - 1] in t.saintPrefixes

    private fun canonWords(toks: List<String>): List<String> {
        val out = ArrayList<String>()
        val n = toks.size
        for ((i, w) in toks.withIndex()) {
            val nxt = if (i + 1 < n) toks[i + 1] else null
            if ((w == "st" || w == "saint" || w == "snt") && nxt != null && nxt !in t.types &&
                nxt !in t.dir && !nxt[0].isAsciiDigit() && (w != "st" || saintPosition(toks, i))
            ) {
                out.add("saint")
                continue
            }
            if (w in t.weakDirs) {
                out.add(if (i == 0 && n > 1) t.dir.getValue(w) else w)
                continue
            }
            when (w) {
                in t.dir -> out.add(t.dir.getValue(w))
                in t.types -> out.add(t.types.getValue(w))
                in t.nameWords -> out.addAll(Py.split(t.nameWords.getValue(w)))
                else -> out.add(w)
            }
        }
        return out
    }

    fun streetKey(name: String?): String {
        val toks = tokens(name)
        if (toks.isEmpty()) return ""
        val hw = findHighway(toks)
        if (hw != null) {
            val before = canonWords(ordinals(toks.subList(0, hw.start)))
            val after = canonWords(ordinals(toks.subList(hw.end, toks.size)))
            return (before + hw.canon + after).joinToString(" ")
        }
        return canonWords(ordinals(toks)).joinToString(" ")
    }

    /** Key without leading/trailing directionals and trailing street type. */
    fun streetCore(key: String): String {
        var toks = Py.split(key)
        if (toks.size > 1 && toks[0] in t.dir) toks = toks.drop(1)
        if (toks.size > 1 && toks.last() in t.dir) toks = toks.dropLast(1)
        if (toks.size > 1 && toks.last() in t.typeCanon && toks[toks.size - 2] !in t.hwyMods) toks = toks.dropLast(1)
        return toks.joinToString(" ")
    }

    /** Other keys a highway key may be stored under ('rte 8' <-> 'sr 8', 'us 8', ...). */
    fun highwayAlternatives(key: String): List<String> {
        val toks = Py.split(key)
        for ((i, w) in toks.withIndex()) {
            if (w in t.hwyFallbacks && i + 1 < toks.size && NUM_RE.matchEntire(toks[i + 1]) != null) {
                return t.hwyFallbacks.getValue(w).map { alt -> (toks.subList(0, i) + alt + toks.subList(i + 1, toks.size)).joinToString(" ") }
            }
        }
        return emptyList()
    }

    /** Canonical street type for a misspelt type word, else null (see normalize.fix_type_typo). */
    fun fixTypeTypo(word: String): String? {
        if (word.length < 4 || word in t.types || !Py.isAlpha(word)) return null
        var hits = LinkedHashSet<String>()
        for ((canon, first) in t.typeFirstSpelling) {
            if (first.length >= 4 && osaDistance(word, first, 1) <= 1) hits.add(canon)
        }
        if (hits.size > 1) hits = LinkedHashSet(hits.filter { it in t.strongTypes })
        return if (hits.size == 1) hits.first() else null
    }

    /** Second-chance keys for the lookup, smallest change first (see normalize.key_variants). */
    fun keyVariants(key: String): List<String> {
        val toks = Py.split(key)
        val fixes = LinkedHashMap<Int, String>()
        for ((i, w) in toks.withIndex()) fixTypeTypo(w)?.let { fixes[i] = it }
        var j = toks.size - 1
        while (j > 0 && toks[j] in t.dir) j--
        val order = (if (j in fixes) listOf(j) else emptyList()) + fixes.keys.sorted().filter { it != j }
        val out = ArrayList<String>()
        for (i in order) out.add((toks.subList(0, i) + fixes.getValue(i) + toks.subList(i + 1, toks.size)).joinToString(" "))
        val fixed = toks.mapIndexed { i, w -> fixes[i] ?: w }
        val ords = fixed.toMutableList()
        for ((i, w) in fixed.withIndex()) {
            if (Py.isDigits(w) && i + 1 < fixed.size && fixed[i + 1] in t.typeCanon &&
                !(i > 0 && fixed[i - 1] in t.hwyFallbacks)
            ) {
                val n = Py.intStr(w)
                ords[i] = n + ordinalSuffix(n)
            }
        }
        out.add(ords.joinToString(" "))
        out.add(fixed.joinToString(" "))
        return LinkedHashSet(out).filter { it != key }
    }

    /** Canonical house number text and its integer part, or null when there is no leading number. */
    fun houseNumber(hn: String?): HouseNumber? {
        var s = asciiFold(hn ?: "").lowercase().trim()
        s = WS_RUN.replace(s, " ")
        s = DASH_WS.replace(s, "-")
        fracRe.matchEntire(s)?.let { m ->
            val n = Py.intStr(m.groupValues[1])
            return HouseNumber("$n${m.groupValues[2]} ${m.groupValues[3]}", BigInteger(n))
        }
        HN1.matchEntire(s)?.let { m ->
            val n = Py.intStr(m.groupValues[1])
            return HouseNumber(n + m.groupValues[2], BigInteger(n))
        }
        HN2.matchEntire(s)?.let { m ->
            val n = Py.intStr(m.groupValues[1])
            return HouseNumber("$n-${Py.intStr(m.groupValues[2])}${m.groupValues[3]}", BigInteger(n))
        }
        HN3.find(s)?.let { m ->
            return HouseNumber(NOT_HN_CHARS.replace(s, ""), BigInteger(Py.intStr(m.groupValues[1])))
        }
        return null
    }

    /** 'City of Erie' -> 'erie', 'Millcreek Township' -> 'millcreek', 'Mt. Lebanon' -> 'mount lebanon'. */
    fun placeKey(name: String?): String {
        var toks = Py.split(clean(name)).map { t.placeWords[it] ?: it }
        for (pre in t.placeDropPrefixes) {
            if (toks.size > pre.size && toks.subList(0, pre.size) == pre) {
                toks = toks.drop(pre.size)
                break
            }
        }
        if (toks.size > 1 && toks.last() in t.placeDropSuffixes) toks = toks.dropLast(1)
        return toks.joinToString(" ")
    }

    /** normalize._frac_re: ^(\d+)\s*([a-z]?)\s*(1/2|1/4|3/4)$ (fractions from the data file). */
    private val fracRe: Regex by lazy {
        val alts = t.fractions.joinToString("|") { Regex.escape(it) }
        Regex("([0-9]+)$WS*([a-z]?)$WS*($alts)")
    }

    companion object {
        /** Python's \s for the ASCII strings these rules see. */
        private const val WS = "[\\t\\n\\x0B\\f\\r \\x1C-\\x1F]"
        private val APOS_DOT = Regex("['’`.]")
        private val NUM_RE = Regex("0*([0-9]+)([a-z]?)")
        private val ORD_NUM_RE = Regex("0*([0-9]+)(st|nd|rd|th|d)")
        private val GLUED_RE = Regex("([a-z]+)([0-9]+[a-z]?)")
        private val WS_RUN = Regex("$WS+")
        private val DASH_WS = Regex("$WS*-$WS*")
        private val HN1 = Regex("0*([0-9]+)(?:-?$WS?([a-z]))?")
        private val HN2 = Regex("0*([0-9]+)-0*([0-9]+)([a-z]?)")
        private val HN3 = Regex("^0*([0-9]+)")
        private val NOT_HN_CHARS = Regex("[^a-z0-9/-]")

        /** Ordinal suffix of a non-negative decimal number given as digits. */
        fun ordinalSuffix(digits: String): String {
            val d = digits.trimStart('0').ifEmpty { "0" }
            val last2 = if (d.length >= 2) d.substring(d.length - 2).toInt() else d.toInt()
            if (last2 in 10..13) return "th"
            return when (last2 % 10) { 1 -> "st"; 2 -> "nd"; 3 -> "rd"; else -> "th" }
        }

        /** Optimal string alignment distance; anything above [limit] is reported as limit + 1. */
        fun osaDistance(a: String, b: String, limit: Int): Int {
            if (kotlin.math.abs(a.length - b.length) > limit) return limit + 1
            var prev2: IntArray? = null
            var prev = IntArray(b.length + 1) { it }
            for (i in 1..a.length) {
                val cur = IntArray(b.length + 1)
                cur[0] = i
                var best = cur[0]
                for (j in 1..b.length) {
                    val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                    var v = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
                    if (prev2 != null && i > 1 && j > 1 && a[i - 1] == b[j - 2] && a[i - 2] == b[j - 1]) {
                        v = minOf(v, prev2[j - 2] + 1)
                    }
                    cur[j] = v
                    best = minOf(best, v)
                }
                if (best > limit) return limit + 1
                prev2 = prev
                prev = cur
            }
            return prev[b.length]
        }

        private fun Char.isAsciiDigit() = this in '0'..'9'
    }
}
