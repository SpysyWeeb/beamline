package io.github.spysyweeb.beamline.search.address

import io.github.spysyweeb.beamline.search.Py

/** Fields of a typed address (addrlib.parser.parse output). */
data class ParsedAddress(
    val input: String,
    val houseNumber: String? = null,
    val street: String? = null,
    val streetKey: String? = null,
    val unit: String? = null,
    val city: String? = null,
    val cityKey: String? = null,
    val state: String? = null,
    val zip: String? = null,
    val zip4: String? = null,
    val zipInvalid: String? = null,
)

/**
 * Port of packbuilder/addresses/addrlib/parser.py: a rule-based parser for typed US addresses.
 * Word lists come from us_street_norm.json; only number shapes and the rule order are code.
 */
class AddressParser(private val n: AddressNormalizer) {
    private val t = n.t

    /** (token, comma-part index, typed-word index) */
    private data class Tok(val s: String, val part: Int, val word: Int)

    private fun tokenise(text: String?): List<Tok> {
        var s = n.asciiFold(text ?: "").lowercase()
        s = APOS.replace(s, "")
        s = PERIOD.replace(s, " ")
        val parts = PART_SPLIT.split(s)
        val toks = ArrayList<Tok>()
        var wi = 0
        for ((pi, p0) in parts.withIndex()) {
            var p = p0.replace("#", " # ")
            p = NON_TOKEN.replace(p, " ")
            for (t0 in Py.split(p)) {
                val tk = t0.trim('-')
                if (tk.isEmpty()) continue
                val m = PREFIX_NUM.matchEntire(tk)
                if (m != null) {
                    toks.add(Tok(m.groupValues[1], pi, wi)); toks.add(Tok(m.groupValues[2], pi, wi))
                } else if ('-' in tk && !KEEP_DASH.matches(tk)) {
                    for (x in tk.split("-")) if (x.isNotEmpty()) toks.add(Tok(x, pi, wi))
                } else {
                    toks.add(Tok(tk, pi, wi))
                }
                wi++
            }
        }
        return toks
    }

    private fun dropCountry(toks: List<Tok>): List<Tok> {
        for (seq in t.countryMulti) {
            val k = seq.size
            if (toks.size > k + 1 && toks.subList(toks.size - k, toks.size).map { it.s } == seq) return toks.subList(0, toks.size - k)
        }
        return toks
    }

    private fun partEnd(toks: List<Tok>, i: Int) = i == toks.size - 1 || toks[i + 1].part != toks[i].part

    /** (unit text, position after it) for a unit starting at street token sidx[k], else null. */
    private fun unit(toks: List<Tok>, sidx: List<Int>, k: Int, knownPlaces: Set<String>?, afterUnit: Boolean): Pair<String, Int>? {
        val tok = toks[sidx[k]].s
        val part = toks[sidx[k]].part
        fun same(x: Int) = x < sidx.size && toks[sidx[x]].part == part
        val prev = toks[sidx[k - 1]].s
        val prevBoundary = toks[sidx[k - 1]].part != part
        val canon = t.units[tok]
        if (canon == null) {
            val m = ORD_RE.matchEntire(tok)   // '2nd floor' / '3rd fl'
            if (m != null && same(k + 1) && t.units[toks[sidx[k + 1]].s] == "fl" &&
                (afterUnit || prevBoundary || prev in t.types)
            ) return "fl ${Py.intStr(m.groupValues[1])}" to k + 2
            return null
        }
        if (t.unitNeedsId.getValue(canon)) {
            var x = k + 1
            if (canon != "#" && same(x) && toks[sidx[x]].s == "#") x++   // 'apt #4b'
            if (!same(x)) return null
            val word = toks[sidx[x]].word
            var end = x + 1
            while (end < sidx.size && toks[sidx[end]].word == word) end++   // 'a-1' was split
            var ident = (x until end).joinToString("-") { toks[sidx[it]].s }
            if (!UNIT_ID_RE.matches(ident) || ident in t.types) return null
            val m = ORD_RE.matchEntire(ident)
            if (canon == "fl" && m != null) ident = m.groupValues[1]   // 'floor 2nd'
            return (if (canon == "#") "# $ident" else "$canon $ident") to end
        }
        // designator without identifier
        var partEnd = k
        while (same(partEnd + 1)) partEnd++
        val alone = prevBoundary && partEnd == k
        if (!(afterUnit || alone || t.types[prev] in t.strongTypes)) return null
        if ((k + 1..partEnd).any { t.types[toks[sidx[it]].s] in t.strongTypes }) return null   // 'trail side court'
        if (knownPlaces != null && knownPlaces.isNotEmpty()) {
            val words = (k..partEnd).map { toks[sidx[it]].s }
            if ((2..words.size).any { m -> n.placeKey(words.subList(0, m).joinToString(" ")) in knownPlaces }) return null
        }
        return canon to k + 1
    }

    /** Parses a typed address. [knownPlaces]: optional set of place keys (AddressNormalizer.placeKey). */
    fun parse(text: String, knownPlaces: Set<String>? = null): ParsedAddress {
        var res = ParsedAddress(input = text)
        val toks = dropCountry(tokenise(text))
        val used = BooleanArray(toks.size)
        if (toks.isEmpty()) return res
        if (toks.size == 1 && ZIP_RE.matches(toks[0].s)) {   // a ZIP on its own
            val digits = toks[0].s.replace("-", "")
            return res.copy(zip = digits.take(5), zip4 = digits.drop(5).ifEmpty { null })
        }
        var zip: String? = null
        var zip4: String? = null
        var zipInvalid: String? = null
        var state: String? = null
        var city: String? = null

        // 2. ZIP
        var zipI: Int? = null
        for (i in toks.size - 1 downTo 1) {
            val tok = toks[i].s
            if (ZIP_RE.matches(tok)) {
                val prevState = toks[i - 1].s in t.states
                if (partEnd(toks, i) || prevState) {
                    val digits = tok.replace("-", "")
                    zip = digits.take(5); zip4 = digits.drop(5).ifEmpty { null }
                    used[i] = true
                    zipI = i
                    break
                }
            }
        }
        if (zipI == null && toks.size >= 3 && BAD_ZIP_RE.matches(toks.last().s) && toks[toks.size - 2].s in t.states) {
            zipInvalid = toks.last().s   // 'erie pa 1650'
            used[toks.size - 1] = true
        }

        // 3. state (longest multi-word match)
        var stateSpan: IntRange? = null
        loop@ for (i in toks.size - 1 downTo 1) {
            if (used[i]) continue
            for (seq in t.stateMulti) {
                val k = seq.size
                if (i - k + 1 < 1) continue
                val span = toks.subList(i - k + 1, i + 1)
                if (span.map { it.s } != seq || (i - k + 1..i).any { used[it] }) continue
                if (span.map { it.part }.toSet().size != 1) continue
                val beforeZip = zipI != null && zipI == i + 1
                val atEnd = (i + 1 until toks.size).all { used[it] } || partEnd(toks, i)
                if (!(beforeZip || atEnd)) continue
                // an ambiguous token right after a house number is a street word
                if (k == 1 && seq[0] in t.ambiguousStates && i - 1 >= 0 && HN_RE.matches(toks[i - 1].s) && i - 1 == 0) continue
                stateSpan = (i - k + 1)..i
                break@loop
            }
        }
        if (stateSpan != null) {
            state = t.states.getValue(stateSpan.joinToString(" ") { toks[it].s })
            for (k in stateSpan) used[k] = true
        }

        // locality-first order: "erie pa 16501 123 main st" or "erie pa, 123 main st"
        var locEnd: Int? = null
        val marks = (if (zipI != null) listOf(zipI) else emptyList()) + (if (stateSpan != null) listOf(stateSpan.last) else emptyList())
        if (marks.isNotEmpty()) {
            val lastMark = marks.max()
            val rest = (lastMark + 1 until toks.size).filter { !used[it] }
            if (rest.isNotEmpty() && HN_RE.matches(toks[rest[0]].s) && rest.size >= 2) locEnd = lastMark
        }
        var streetIdx: List<Int>
        if (locEnd != null) {
            val cityIdx = (0 until marks.min()).filter { !used[it] }
            streetIdx = (locEnd + 1 until toks.size).filter { !used[it] }
            if (cityIdx.isNotEmpty()) city = cityIdx.joinToString(" ") { toks[it].s }
            for (k in cityIdx) used[k] = true
        } else {
            streetIdx = toks.indices.filter { !used[it] }
        }

        // if the first comma part has no number but a later part starts with one, use that part
        if (streetIdx.isNotEmpty() && !HN_RE.matches(toks[streetIdx[0]].s)) {
            for (k in streetIdx) {
                if (HN_RE.matches(toks[k].s) && (k == 0 || toks[k - 1].part != toks[k].part)) {
                    val pre = streetIdx.filter { it < k }
                    if (pre.isNotEmpty() && city == null) {
                        city = pre.joinToString(" ") { toks[it].s }
                        for (j in pre) used[j] = true
                    }
                    streetIdx = streetIdx.filter { it >= k }
                    break
                }
            }
        }

        // 4. house number
        var houseNumber: String? = null
        if (streetIdx.isNotEmpty() && HN_RE.matches(toks[streetIdx[0]].s)) {
            val hnToks = arrayListOf(toks[streetIdx[0]].s)
            used[streetIdx[0]] = true
            streetIdx = streetIdx.drop(1)
            val nxt = streetIdx.map { toks[it].s }
            if (nxt.isNotEmpty() && nxt[0] in t.fractions) {
                hnToks.add(nxt[0])
                used[streetIdx[0]] = true
                streetIdx = streetIdx.drop(1)
            } else if (nxt.size >= 3 && ONE_LETTER.matches(nxt[0]) && nxt[0] !in t.dir &&
                nxt[1] !in t.types && Py.isDigits(hnToks[0]) &&
                toks[streetIdx[1]].part == toks[streetIdx[0]].part &&
                n.findHighway(nxt)?.start != 0
            ) {
                hnToks[0] = hnToks[0] + nxt[0]   // '123 A Main St' -> 123a ('123 A St' keeps street 'A St')
                used[streetIdx[0]] = true
                streetIdx = streetIdx.drop(1)
            }
            houseNumber = n.houseNumber(hnToks.joinToString(" "))?.text
        }

        // 5. units (after at least one street word)
        val units = ArrayList<String>()
        var splitAt: Int? = null
        var k = 1
        while (k < streetIdx.size) {
            val got = unit(toks, streetIdx, k, knownPlaces, units.isNotEmpty())
            if (got == null) {
                if (units.isNotEmpty()) break
                k++
                continue
            }
            val (textU, end) = got
            units.add(textU)
            for (j in streetIdx.subList(k, end)) used[j] = true
            streetIdx = streetIdx.subList(0, k) + streetIdx.subList(end, streetIdx.size)
            splitAt = k   // a unit closes the street part: tokens after it (same part) are the city
        }
        val unitText = units.joinToString(" ").ifEmpty { null }

        // 6. no leading house number: 'state st 626 erie', 'main st 123', 'route 8 4500 gibsonia'
        if (houseNumber == null && streetIdx.size > 1) {
            val words = streetIdx.map { toks[it].s }
            val hw = n.findHighway(words)
            var pos: Int? = null
            for (x in 1 until words.size) {
                if (HN_RE.matches(words[x]) && !(hw != null && hw.start <= x && x < hw.end) &&
                    (words[x - 1] in t.types || (hw != null && x == hw.end))
                ) {
                    pos = x
                    break
                }
            }
            if (pos == null && HN_RE.matches(words.last()) && hw == null) pos = words.size - 1
            if (pos != null) {
                houseNumber = n.houseNumber(words[pos])?.text
                used[streetIdx[pos]] = true
                streetIdx = streetIdx.subList(0, pos) + streetIdx.subList(pos + 1, streetIdx.size)
                if (pos < streetIdx.size && splitAt == null) splitAt = pos
            }
        }

        // 7. only a place name left: 'Erie PA 16501' is a locality, not a street
        if (houseNumber == null && units.isEmpty() && city == null && streetIdx.isNotEmpty() && !knownPlaces.isNullOrEmpty()) {
            if (n.placeKey(streetIdx.joinToString(" ") { toks[it].s }) in knownPlaces) {
                city = streetIdx.joinToString(" ") { toks[it].s }
                streetIdx = emptyList()
            }
        }

        // 8. street / city split
        if (streetIdx.isNotEmpty() && city == null) {
            val parts = streetIdx.map { toks[it].part }.toSortedSet()
            val sIdx: List<Int>
            val cIdx: List<Int>
            if (parts.size > 1) {
                val first = parts.first()
                sIdx = streetIdx.filter { toks[it].part == first }
                cIdx = streetIdx.filter { toks[it].part != first }
            } else if (splitAt != null && splitAt < streetIdx.size) {
                sIdx = streetIdx.subList(0, splitAt)
                cIdx = streetIdx.subList(splitAt, streetIdx.size)
            } else {
                val sp = splitStreetCity(streetIdx.map { toks[it].s }, streetIdx, knownPlaces, state != null || zip != null)
                sIdx = sp.first; cIdx = sp.second
            }
            streetIdx = sIdx
            if (cIdx.isNotEmpty()) city = cIdx.joinToString(" ") { toks[it].s }
        }
        var street: String? = null
        var streetKey: String? = null
        if (streetIdx.isNotEmpty()) {
            street = streetIdx.joinToString(" ") { toks[it].s }
            streetKey = n.streetKey(street)
        }
        val cityKey = if (!city.isNullOrEmpty()) n.placeKey(city) else null
        res = res.copy(houseNumber = houseNumber, street = street, streetKey = streetKey, unit = unitText,
            city = city, cityKey = cityKey, state = state, zip = zip, zip4 = zip4, zipInvalid = zipInvalid)
        return res
    }

    private fun splitStreetCity(words: List<String>, idx: List<Int>, knownPlaces: Set<String>?, hasLocalityMarker: Boolean): Pair<List<Int>, List<Int>> {
        val n0 = words.size
        // (a) gazetteer: longest trailing run that is a known place, leaving >= 1 street word
        if (!knownPlaces.isNullOrEmpty()) {
            for (start in 1 until n0) {
                val cand = n.placeKey(words.subList(start, n0).joinToString(" "))
                if (cand.isNotEmpty() && cand in knownPlaces) return idx.subList(0, start) to idx.subList(start, idx.size)
            }
        }
        // (b) highway form: street ends after the route number (+ modifiers, + directional)
        val hw = n.findHighway(words)
        if (hw != null) {
            var end = hw.end
            if (end < n0 && words[end] in t.dir) end++
            return idx.subList(0, end) to idx.subList(end, idx.size)
        }
        // (c) last strong street type (else last weak type), plus a following directional
        for (pool in listOf(t.strongTypes, t.typeCanon)) {
            var last: Int? = null
            for (i in 1 until n0) {
                val canon = t.types[words[i]]
                if (canon != null && canon in pool) last = i
            }
            if (last != null) {
                var end = last + 1
                if (end < n0 && words[end] in t.dir && words[end] !in t.weakDirs) end++
                return idx.subList(0, end) to idx.subList(end, idx.size)
            }
        }
        // (d) nothing to go on
        if (hasLocalityMarker && n0 >= 2) return idx.subList(0, n0 - 1) to idx.subList(n0 - 1, idx.size)
        return idx to emptyList()
    }

    companion object {
        private val APOS = Regex("['’`]")
        private val PERIOD = Regex("(?<![0-9])\\.|\\.(?![0-9])")
        private val PART_SPLIT = Regex("[,;\n]+")
        private val NON_TOKEN = Regex("[^a-z0-9#/\\- ]+")
        private val PREFIX_NUM = Regex("([a-z]+)-([0-9]+[a-z]?)")
        private val KEEP_DASH = Regex("[0-9]+-[0-9]+[a-z]?|[0-9]{5}-[0-9]{4}|[0-9]+-[a-z]")
        private val HN_RE = Regex("[0-9]+[a-z]?|[0-9]+-[0-9]+[a-z]?|[0-9]+-[a-z]")
        private val ZIP_RE = Regex("[0-9]{5}(-[0-9]{4})?|[0-9]{9}")
        private val BAD_ZIP_RE = Regex("[0-9]{3,4}|[0-9]{6,8}")
        private val UNIT_ID_RE = Regex("[a-z0-9]{1,6}(-[a-z0-9]{1,4})?")
        private val ORD_RE = Regex("([0-9]+)(st|nd|rd|th)")
        private val ONE_LETTER = Regex("[a-z]")
    }
}
